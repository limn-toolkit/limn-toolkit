package limn.scene;

import limn.backend.CrashPhase;
import limn.backend.Cursor;
import limn.concurrent.internal.Listeners;
import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.i18n.I18n;
import limn.scene.event.CharEvent;
import limn.scene.event.FileDropEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.event.PreeditEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Base of the widget tree. A widget has bounds in its <em>parent's</em>
 * coordinate space, visibility/enabled/focusable flags, children, and takes
 * part in the two-phase layout ({@link #measure} → {@link #layoutBox}),
 * per-frame painting and event bubbling.
 *
 * <p>Thread confinement is a hard rule: every tree/state mutation calls
 * {@link Ui#checkUiThread()} and throws off the UI thread.
 *
 * <p>Invalidation model: <b>nothing repaints on its own.</b>
 * {@link #invalidate()} asks for a frame and records this widget's bounds as
 * damage; a scene with {@linkplain Scene#setPartialRendering(boolean) partial
 * rendering} on (the default) then repaints only the damaged region, one without it
 * repaints the window, and either way the loop goes back to sleep once nothing
 * is asking. {@link #markNeedsLayout()} additionally re-runs measure/layout,
 * which repaints everything. A change that goes through neither is not drawn:
 * every setter here invalidates, but a field written directly (from a posted
 * task, a timer, a background result) does not, and neither does anything a
 * custom {@link #onPaint} reads from outside the tree.
 */
public abstract class Widget {

    private Widget parent;
    private final List<Widget> children = new ArrayList<>();
    private final List<Widget> childrenView = Collections.unmodifiableList(children);

    private float x;
    private float y;
    private float width;
    private float height;
    private boolean visible = true;
    private boolean enabled = true;
    private boolean focusable;
    private Cursor cursor; // null = inherit from an ancestor (arrow at the root)

    Scene scene; // set on the root by Scene, propagated on attach

    private Constraints lastConstraints;
    private Size lastSize = Size.ZERO;
    private boolean needsMeasure = true;

    // ------------------------------------------------------- control size axis
    // Read the resolution contract on ControlSize before touching any of this.

    /**
     * Global validity stamp for every widget's {@link #controlSize()} memo. UI-thread
     * confined (every writer either calls {@link Ui#checkUiThread()} itself or runs inside
     * a tree mutation that already did), so a plain {@code long} suffices: no volatile, no
     * atomics. Starts at 1 so a fresh widget's {@code resolvedEpoch == 0} is always stale.
     *
     * <p>Bumped by exactly five writers of a resolution input, each O(1):
     * {@link #setControlSize}, {@link #setInheritanceHost}, {@link #setSceneRecursively}
     * (the single funnel for {@code add}, {@code remove}, {@link Scene}'s constructor,
     * {@code pushOverlay} and {@code removeOverlay}), {@link Scene#setControlSize} and
     * {@link ControlSize#setProcessDefault}. Worst case is a degradation (reads fall back
     * to the parent walk), never a wrong answer, which is why a global counter is preferred
     * over precise subtree invalidation.
     */
    private static long controlSizeEpoch = 1;

    /**
     * The step this widget declares for itself and its subtree; {@code null} = inherit.
     * <b>Never resolved in a constructor</b> (see {@link ControlSize}), because
     * {@link #add} assigns the parent after the child is fully built and there is no
     * reparent hook to correct a captured value.
     */
    private ControlSize declaredControlSize;

    /**
     * Logical parent for an out-of-tree root (a {@link Scene} root, an overlay). Shared by every
     * inherited axis: a link means "this parentless panel belongs to that widget", which is not a
     * fact about size or about direction, and a second link that could name a different widget
     * would be a bug with no honest resolution.
     */
    private Widget inheritanceHost;

    /** Memo; valid iff {@code resolvedEpoch == controlSizeEpoch}. */
    private ControlSize resolvedControlSize;
    private long resolvedEpoch;

    /**
     * The resolved axes {@link #lastSize} was measured under: one value, not a field per
     * axis, which is the move the direction axis's own doc reserved for the first time a
     * third axis wanted into {@link #measure}'s key. The locale is that third axis (a keyed
     * string resolves to different text, so a subtree in another language genuinely
     * measures differently); allocated only when a measure actually runs, compared
     * field-by-field so a cache hit allocates nothing.
     */
    private MeasuredAxes measuredAxes;

    /** See {@link #measuredAxes}. */
    private record MeasuredAxes(ControlSize size, LayoutDirection direction, Locale locale) {
    }

    /** Invalidates every memo in the process. O(1). Package-private: the five writers only. */
    static void bumpControlSizeEpoch() {
        controlSizeEpoch++;
    }

    // --------------------------------------------------- layout direction axis
    // The same shape as the size axis above, deliberately: read LayoutDirection's
    // resolution contract before touching any of this, and keep the two apart.

    /**
     * Global validity stamp for every widget's {@link #layoutDirection()} memo, and a
     * <b>separate counter</b> from {@link #controlSizeEpoch}.
     *
     * <p><b>The separation buys cost, not correctness</b>, and it is worth being exact about
     * that because it is easy to overclaim. Merging the two counters would still give every
     * right answer: a memo is a pure function of its inputs, so a spurious bump only forces a
     * re-resolution that arrives at the same value, and {@link #measure}'s key compares resolved
     * <em>values</em> rather than epochs, so an unchanged axis still hits its size cache. What
     * merging would cost is the re-resolution itself — every widget in the process walking one
     * link on the next read of an axis that did not move — and the ability to say which axis a
     * re-resolution was for. Two counters are two {@code long}s and one increment; that is a
     * cheap price for not paying the other one.
     *
     * <p>UI-thread confined and starting at 1, for the reasons given on the size epoch. Bumped
     * by exactly five writers of a resolution input, each O(1): {@link #setLayoutDirection},
     * {@link #setInheritanceHost}, {@link #setSceneRecursively}, {@link Scene#setLayoutDirection}
     * and {@link LayoutDirection#setProcessDefault}.
     */
    private static long layoutDirectionEpoch = 1;

    /**
     * The direction this widget declares for itself and its subtree; {@code null} = inherit.
     * <b>Never resolved in a constructor</b> (see {@link LayoutDirection}), for the reason a
     * declared step is not: {@link #add} assigns the parent after the child is fully built.
     */
    private LayoutDirection declaredLayoutDirection;

    /** Memo; valid iff {@code resolvedDirectionEpoch == layoutDirectionEpoch}. */
    private LayoutDirection resolvedLayoutDirection;
    private long resolvedDirectionEpoch;

    /** Invalidates every direction memo in the process. O(1). Package-private: the writers only. */
    static void bumpLayoutDirectionEpoch() {
        layoutDirectionEpoch++;
    }

    // ------------------------------------------------------------- locale axis
    // The third resolved axis, the same shape again on purpose: read the resolution
    // contract on Widget.locale() before touching any of this, and keep all three apart.

    /**
     * Global validity stamp for every widget's {@link #locale()} memo: its own counter,
     * for the cost reason the direction epoch is not the size epoch. Bumped by the four
     * tree-side writers of a resolution input, each O(1): {@link #setLocale},
     * {@link #setInheritanceHost}, {@link #setSceneRecursively} and
     * {@link Scene#setLocale}.
     *
     * <p>The chain's fifth input, the process locale, lives in {@link I18n} and cannot
     * bump a package-private counter here, so the memo is validated against <b>two</b>
     * stamps: this one and {@link I18n#epoch()}, which every process-locale switch
     * already moves. An i18n epoch bump that did not move the locale (a bundle
     * registration) forces a spurious re-resolution that arrives at the same value:
     * one link walked per widget per rare event, the price the direction axis's own
     * doc puts on a merged counter, paid here for not putting a cross-package hook on
     * the hot path of every {@code setLocale}.
     */
    private static long localeEpoch = 1;

    /**
     * The locale this widget declares for itself and its subtree; {@code null} = inherit.
     * <b>Never resolved in a constructor</b>, for the reason a declared step is not:
     * {@link #add} assigns the parent after the child is fully built.
     */
    private Locale declaredLocale;

    /** Memo; valid iff both stamps below are current. */
    private Locale resolvedLocale;
    private long resolvedLocaleEpoch;
    private long resolvedLocaleI18nEpoch;

    /** Invalidates every locale memo in the process. O(1). Package-private: the writers only. */
    static void bumpLocaleEpoch() {
        localeEpoch++;
    }

    // ------------------------------------------------------------------ tree

    /** The parent this widget was added to, or {@code null} while it is unattached. */
    public final Widget parent() {
        return parent;
    }

    /** The children, in paint and hit-test order: an unmodifiable view of live state. */
    public final List<Widget> children() {
        return childrenView;
    }

    /**
     * Appends a child (UI thread only). Protected: a widget arranges children of its own, and only a
     * {@link limn.scene.layout.Container} takes them from outside (ADR 046 §3).
     */
    protected void add(Widget child) {
        Ui.checkUiThread();
        insert(children.size(), child);
    }

    /**
     * Inserts a child at a position in {@link #children()} (UI thread only).
     *
     * <p>{@link #add(Widget)} appends, which is right for every container that builds its children
     * once and in the order they are meant to be read. It is wrong for one that mounts them on
     * demand from both ends: a virtualized list scrolling back up realizes the rows above its
     * anchor in descending order, and appending each would leave {@code children()} — which is
     * reading order and Tab order both — holding the order the rows happened to be realized in
     * rather than the order the data is in. Re-adding a child to move it is not the alternative:
     * {@link #remove} detaches the subtree, which revokes the focus, hover and press inside it.
     *
     * @param index where the child goes, in {@code [0, children().size()]}
     * @param child the child to insert; never {@code null}
     * @throws IndexOutOfBoundsException if {@code index} is outside that range
     * @throws NullPointerException      if {@code child} is {@code null}
     * @throws IllegalStateException     if {@code child} already has a parent
     * @throws IllegalArgumentException  if {@code child} is an ancestor of this widget
     */
    protected void add(int index, Widget child) {
        Ui.checkUiThread();
        Objects.checkIndex(index, children.size() + 1);
        insert(index, child);
    }

    /** The one place a child joins the tree; both {@code add} overloads end here. */
    private void insert(int index, Widget child) {
        Objects.requireNonNull(child, "child");
        if (child.parent != null) {
            throw new IllegalStateException("widget already has a parent");
        }
        for (Widget ancestor = this; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor == child) {
                throw new IllegalArgumentException("cycle: child is an ancestor of this widget");
            }
        }
        children.add(index, child);
        child.parent = this;
        child.setSceneRecursively(scene);
        markNeedsLayout();
        // Structure is announced on the parent, from the one funnel every child joins through,
        // so a watcher of a tree hears a mount as it happens rather than by diffing child lists.
        notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE));
    }

    /** Removes a child (UI thread only); protected for the reason {@link #add(Widget)} is. */
    protected void remove(Widget child) {
        Ui.checkUiThread();
        if (children.remove(child)) {
            child.parent = null;
            child.setSceneRecursively(null);
            if (scene != null) {
                scene.onWidgetDetached(child);
            }
            markNeedsLayout();
            notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE));
        }
    }

    /**
     * The single funnel through which a subtree's scene is written: {@link #add},
     * {@link #remove}, {@link Scene}'s constructor, {@code Scene.pushOverlay} and
     * {@code Scene.removeOverlay}. Bumps every inherited axis's epoch once, up front:
     * {@code pushOverlay}/{@code removeOverlay} move a whole <b>parentless</b> subtree
     * between scenes without touching any parent field, so a widget that resolved and
     * memoized before being pushed into a scene with a different default would otherwise
     * keep a stale answer forever. Bumping here rather than in {@code add}/{@code remove}
     * is what makes the memo provably exact rather than hopefully exact.
     */
    final void setSceneRecursively(Scene newScene) {
        bumpControlSizeEpoch();
        bumpLayoutDirectionEpoch();
        bumpLocaleEpoch();
        setSceneRecursivelyInternal(newScene);
    }

    private void setSceneRecursivelyInternal(Scene newScene) {
        Scene old = this.scene;
        // Attach fires top-down (this before children) so a parent's onAttached
        // sees itself in a scene; detach fires bottom-up (children before this).
        boolean attaching = old == null && newScene != null;
        boolean detaching = old != null && newScene == null;
        if (!detaching) {
            this.scene = newScene;
        }
        if (attaching) {
            if (paintsFromBackdrop()) {
                newScene.addBackdropDependant(this);
            }
            onAttached();
        }
        // Indexed and growth- AND shrink-tolerant: a lifecycle hook may legally
        // add or remove siblings (lazy adorners, self-removing children); an
        // iterator would throw ConcurrentModification, and a plain i++ after a
        // removal at or before i would skip the sibling that shifted into slot i.
        for (int i = 0; i < children.size(); ) {
            Widget child = children.get(i);
            child.setSceneRecursivelyInternal(newScene);
            if (i < children.size() && children.get(i) == child) {
                i++;
            }
        }
        if (detaching) {
            // The field is cleared AFTER the hook, so a widget being removed can
            // still answer "which scene am I leaving?" (the one thing it needs in
            // order to hand a GPU resource to Scene.disposeLater, or to retire a
            // ticker). Clearing first (which this used to do) forced every such
            // widget to mirror the scene into a field of its own during onAttached
            // and remember to null it again; two widgets in this repo alone had
            // grown that field, which is a design saying it got the order wrong.
            onDetached();
            if (paintsFromBackdrop() && old != null) {
                old.removeBackdropDependant(this);
            }
            this.scene = null;
            // The funnel's epoch bump ran BEFORE the hook, so a memo resolved inside
            // onDetached — legal there, and answered from the scene being left — is
            // stamped current and nothing later says otherwise: the widget would keep
            // the left scene's answer for as long as no global input moved. Zero is
            // the stamp that is never current, on any axis, by the fields' own
            // contract.
            resolvedEpoch = 0;
            resolvedDirectionEpoch = 0;
            resolvedLocaleEpoch = 0;
        }
    }

    /**
     * Called when this widget enters a scene (attached to the tree). {@link #scene()}
     * is the scene it just joined. Fires top-down, so a parent runs before its
     * children. Default no-op.
     */
    protected void onAttached() {
    }

    /**
     * Called when this widget leaves the scene (detached from the tree).
     *
     * <p>{@link #scene()} still answers <b>the scene being left</b>, and becomes
     * {@code null} once this returns, so releasing something the scene owns needs
     * no field of your own to remember it by. Fires bottom-up, so children run
     * before their parent, and every one of them can still reach the scene.
     *
     * <p>Release resources here, but GPU resources must be handed to
     * {@link Scene#disposeLater} (disposal needs the owning GL context, which is
     * only current inside a frame). Not called on {@link #setVisible(boolean)}.
     * Default no-op.
     */
    protected void onDetached() {
    }

    /** The scene this widget belongs to, or {@code null} until it is added to one. */
    public final Scene scene() {
        return scene;
    }

    /**
     * The nanosecond clock this widget's scene animates on, or the wall clock while it belongs to
     * no scene.
     *
     * <p>For a widget that keeps a timer of its own -- a hold before a fade, a delay before a
     * reveal -- and would otherwise read {@code System.nanoTime()} directly. A scene's clock is
     * injectable precisely so a test can move time by decree instead of waiting for it, and a
     * timer read off the wall clock is the one part of such a widget that injection would not
     * reach: it expires on its own schedule, in the middle of whatever a test was measuring.
     *
     * @return nanoseconds on the scene's clock, comparable only with other readings of it
     */
    protected final long sceneNanos() {
        return scene != null ? scene.nanoTime() : System.nanoTime();
    }

    // ---------------------------------------------------------------- bounds

    /** @return x in parent coordinates */
    public final float x() {
        return x;
    }

    /** @return y in parent coordinates */
    public final float y() {
        return y;
    }

    /** Laid-out width in logical points; {@code 0} until the first layout pass. */
    public final float width() {
        return width;
    }

    /** Laid-out height in logical points; {@code 0} until the first layout pass. */
    public final float height() {
        return height;
    }

    /** Converts a scene x coordinate into this widget's local space. */
    public final float sceneToLocalX(float sceneX) {
        float local = sceneX;
        for (Widget w = this; w != null; w = w.parent) {
            local -= w.x;
        }
        return local;
    }

    /** Converts a scene y coordinate into this widget's local space. */
    public final float sceneToLocalY(float sceneY) {
        float local = sceneY;
        for (Widget w = this; w != null; w = w.parent) {
            local -= w.y;
        }
        return local;
    }

    /** This widget's origin x in scene coordinates (its offsets summed to the root). */
    public final float localToSceneX() {
        float sceneX = 0;
        for (Widget w = this; w != null; w = w.parent) {
            sceneX += w.x;
        }
        return sceneX;
    }

    /** This widget's origin y in scene coordinates (its offsets summed to the root). */
    public final float localToSceneY() {
        float sceneY = 0;
        for (Widget w = this; w != null; w = w.parent) {
            sceneY += w.y;
        }
        return sceneY;
    }

    /**
     * Asks every {@link Scrollable} ancestor, innermost first, to scroll this
     * widget's bounds into view (each is handed the bounds in its own local
     * coordinates, re-read after inner scrolls so nested scrollables compose).
     * A no-op when everything is already visible. The {@link Scene} calls this
     * on focus changes, so keyboard traversal reveals the focused widget.
     *
     * <p><b>The bounds are grown by {@link #paintOutset()} first</b>, because the
     * bounds are not what has to be visible. A focus ring is drawn outside the box
     * it belongs to — {@code Strokes.FOCUS_RING_OUTSET} is 3 points for a Button,
     * and the ring is the whole reason the widget is being revealed. Revealing the
     * bare bounds parks the widget flush against the viewport's clip, which is
     * exactly where the ring is chopped: the reveal reported success and the reader
     * could not see what was focused.
     *
     * <p>Nothing opts in and nothing is configured. Every widget already declares
     * how far it paints, for partial rendering, and a widget that paints nothing
     * outside its box returns 0 and reveals exactly as it did before. A viewport
     * too small to hold bounds-plus-outset aligns the near edge, the same rule
     * {@link Scrollable#revealRect} already applies to any oversize rectangle.
     */
    public final void revealInView() {
        float outset = paintOutset();
        revealInView(-outset, -outset, width + 2 * outset, height + 2 * outset);
    }

    /**
     * Reveals one rectangle of this widget's own content, in this widget's coordinates, through
     * every scrolling ancestor — what {@link #revealInView()} does for the whole widget.
     *
     * <p>For a widget whose parts are not widgets: a calendar's day cell, a table's row, a
     * chart's point. Such a part has no box of its own to reveal and no {@code paintOutset()} of
     * its own to grow by, so <b>the rectangle is taken as given</b>, and a caller that draws
     * outside it passes the larger rectangle rather than relying on an inflation this method
     * cannot know the size of. Added for decision 81 of 2026-09-17, where a reader's
     * {@code SCROLL_INTO_VIEW} on a day cell had to move the scroll view the calendar sits in and
     * the whole-widget reveal would have scrolled the calendar's own edge into view instead.
     *
     * @param rectX      the rectangle's left, in this widget's coordinates
     * @param rectY      the rectangle's top, in this widget's coordinates
     * @param rectWidth  its width
     * @param rectHeight its height
     */
    public final void revealInView(float rectX, float rectY, float rectWidth, float rectHeight) {
        for (Widget ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor instanceof Scrollable scrollable) {
                float left = rectX;
                float top = rectY;
                for (Widget w = this; w != ancestor; w = w.parent) {
                    left += w.x;
                    top += w.y;
                }
                scrollable.revealRect(left, top, rectWidth, rectHeight);
            }
        }
    }

    // ----------------------------------------------------------------- flags

    /** This widget's own visibility flag; see {@link #isShowing()} for whether it is on screen. */
    public final boolean isVisible() {
        return visible;
    }

    /**
     * @return whether this widget is actually on screen: attached to a scene,
     *         visible together with every ancestor (unlike {@link #isVisible()},
     *         which reflects only this widget's own flag), and not scrolled
     *         fully out of an ancestor that {@linkplain #clipsChildren() clips}
     *         (a scroll viewport). Animations use this to pause while their
     *         widget is inside a hidden container (an unselected tab) or
     *         clipped out of view; without it, a progress bar scrolled away
     *         would keep ticking the frame loop at full rate while painting
     *         nothing. The repaint that reveals the widget re-arms them (the
     *         established re-arm-on-paint pattern).
     */
    public final boolean isShowing() {
        for (Widget w = this; w != null; w = w.parent) {
            if (!w.visible) {
                return false;
            }
        }
        if (scene == null) {
            return false;
        }
        // Clip walk: intersect these bounds with every clipping ancestor's clip rectangle, which
        // is its box unless it says otherwise for the child the walk came up through.
        float x0 = 0;
        float y0 = 0;
        float x1 = width;
        float y1 = height;
        Widget below = null;
        for (Widget node = this; node != null; below = node, node = node.parent) {
            if (node != this && node.clipsChildren()) {
                float cx = node.clipX(below);
                float cy = node.clipY(below);
                x0 = Math.max(x0, cx);
                y0 = Math.max(y0, cy);
                x1 = Math.min(x1, cx + node.clipWidth(below));
                y1 = Math.min(y1, cy + node.clipHeight(below));
                if (x1 <= x0 || y1 <= y0) {
                    return false; // fully clipped away
                }
            }
            x0 += node.x;
            y0 += node.y;
            x1 += node.x;
            y1 += node.y;
        }
        return true;
    }

    /**
     * The rectangle, in scene coordinates, that this widget's {@linkplain #clipsChildren()
     * clipping} ancestors leave of it: the intersection of every clipping ancestor's clip
     * rectangle for the child the walk came up through, which is exactly what {@link #isShowing()}
     * tests this widget's own box against. The accessible walk narrows a synthetic child by it
     * (decision 100, 2026-09-22): a row a calendar draws below the fold of the scroll pane it
     * sits in is no more on screen than a widget child there would be, and until this the walk
     * published every synthetic child with its owner's showing bit, so the rows and cells beyond
     * the pane said {@code SHOWING} and the four invariants refused them.
     *
     * <p>Fills {@code out} with {@code x0, y0, x1, y1} and answers true only when some ancestor
     * clips; with no clipping ancestor nothing narrows and {@code out} is left alone, so a
     * synthetic child a widget places outside its own box (a popup's row, a chart's overflow) is
     * not touched by this and keeps the bit its owner gives it. Allocates nothing, because the
     * walk runs it once per widget of every accessible walk.
     *
     * @param out four floats to fill, in scene coordinates
     * @return whether an ancestor clips this widget at all
     */
    final boolean showingClip(float[] out) {
        boolean clipped = false;
        float x0 = Float.NEGATIVE_INFINITY;
        float y0 = Float.NEGATIVE_INFINITY;
        float x1 = Float.POSITIVE_INFINITY;
        float y1 = Float.POSITIVE_INFINITY;
        Widget below = null;
        for (Widget node = this; node != null; below = node, node = node.parent) {
            if (node != this && node.clipsChildren()) {
                float cx = node.clipX(below);
                float cy = node.clipY(below);
                x0 = Math.max(x0, cx);
                y0 = Math.max(y0, cy);
                x1 = Math.min(x1, cx + node.clipWidth(below));
                y1 = Math.min(y1, cy + node.clipHeight(below));
                clipped = true;
            }
            // Into the parent's coordinates, as isShowing does; the rectangle so far is in
            // node's own, and after this line in its parent's.
            x0 += node.x;
            y0 += node.y;
            x1 += node.x;
            y1 += node.y;
        }
        if (clipped) {
            out[0] = x0;
            out[1] = y0;
            out[2] = x1;
            out[3] = y1;
        }
        return clipped;
    }

    /**
     * Shows or hides this widget and its subtree, re-running layout so siblings take
     * the space back. Hiding revokes focus, hover and any press inside the subtree.
     * UI thread only.
     *
     * <p>Announces {@code VISIBLE}/{@code CODE}, and <b>after</b> the revocation's
     * {@code FOCUS}/{@code ADJUSTMENT} when hiding took the focus away: the settling order of
     * this record in the smallest case there is, with the call's own aspect last.
     *
     * <p>{@code final}, like the three setters beside it, because an override that forgot
     * {@code super} would silently reopen the hole this closes -- a widget whose visibility
     * changed and told nobody -- and because a subclass wanting to react to its own visibility
     * has {@link #observeChanges}.
     */
    public final void setVisible(boolean visible) {
        Ui.checkUiThread();
        if (this.visible != visible) {
            this.visible = visible;
            if (!visible && scene != null) {
                scene.onWidgetDetached(this); // revoke focus/hover/press in this subtree
            }
            // Every measure on the way up is stale, exactly as for markNeedsLayout(): whichever
            // pass lays this out -- the scene's narrow one or a full one it escalates to -- has
            // to re-measure from here. What differs is only what the scene is told.
            for (Widget w = this; w != null; w = w.parent) {
                w.needsMeasure = true;
            }
            if (scene != null && parent != null) {
                // Not a full layout: a visibility change can move nothing outside the nearest
                // ancestor whose size survives it, and the scene lays out and damages only that.
                // ADR 043 §9.4.4. A full layout here repainted the whole window to hide one button.
                scene.markVisibilityChanged(this);
            } else if (scene != null) {
                scene.markLayoutDirty(this); // a root has no parent to absorb it
            }
            notifyChange(Change.of(Change.Aspect.VISIBLE, Change.Origin.CODE));
        }
    }

    /** Whether this widget accepts input; a disabled widget still occupies its box. */
    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables input. Repaints without re-laying-out, since the box does not
     * change; disabling revokes focus, hover and any press inside the subtree.
     * UI thread only.
     *
     * <p>Announces {@code ENABLED}/{@code CODE}, after the revocation's {@code FOCUS} when
     * disabling took the focus away. {@code final} for the reason {@link #setVisible} is.
     */
    public final void setEnabled(boolean enabled) {
        Ui.checkUiThread();
        if (this.enabled != enabled) {
            this.enabled = enabled;
            if (!enabled && scene != null) {
                scene.onWidgetDetached(this); // revoke focus/hover/press in this subtree
            }
            invalidate();
            notifyChange(Change.of(Change.Aspect.ENABLED, Change.Origin.CODE));
        }
    }

    /** Whether keyboard focus can land here: false for containers and static chrome. */
    public final boolean isFocusable() {
        return focusable;
    }

    /**
     * Declares whether this widget can take keyboard focus. Does not move focus away
     * if it currently holds it. UI thread only.
     *
     * <p>It neither repaints nor re-lays-out, because being focusable changes nothing on screen
     * until the focus actually arrives. It does {@linkplain #invalidateAccessible() tell the
     * accessible tree}, and this is the sharper of the two entry points that reach nothing else:
     * being focusable is both a published state and the thing that keeps a widget in the tree at
     * all, so making a scaffold widget focusable changes what the tree <em>contains</em> — and
     * would otherwise do so silently, on the next unrelated repaint.
     *
     * <p>It announces {@code FOCUSABLE}/{@code CODE}, which is the only way this change is
     * observable at all: it produces no frame, so nothing sampling the tree could ever see it.
     * The guard in front is what keeps that honest where a roving-focus reassignment writes
     * {@code false} over every non-holder in a group on every selection change.
     */
    public final void setFocusable(boolean focusable) {
        Ui.checkUiThread();
        if (this.focusable == focusable) {
            return;
        }
        this.focusable = focusable;
        invalidateAccessible();
        notifyChange(Change.of(Change.Aspect.FOCUSABLE, Change.Origin.CODE));
    }

    /**
     * @return the mouse cursor this widget requests while hovered, or
     *         {@code null} to inherit from an ancestor (the scene falls back to
     *         {@link Cursor#DEFAULT} at the root). Non-final so a component can
     *         override it with a state-dependent shape.
     */
    public Cursor cursor() {
        return cursor;
    }

    /**
     * Sets the mouse cursor shown while the pointer is over this widget (and any
     * descendant that does not set its own, since cursor inherits down the tree).
     * {@code null} restores inheritance. UI thread only.
     */
    public void setCursor(Cursor cursor) {
        Ui.checkUiThread();
        this.cursor = cursor;
        // If we're the widget currently under the pointer (or an ancestor of it),
        // reflect the change immediately rather than waiting for the next hover.
        if (scene != null) {
            scene.cursorChanged(this);
        }
    }

    private limn.backend.ImageCursor imageCursor;

    /**
     * @return the custom image cursor this widget requests while hovered, or
     *         {@code null} to inherit. Resolved like {@link #cursor()}, with
     *         an image cursor winning over a shape on the same widget.
     */
    public limn.backend.ImageCursor imageCursor() {
        return imageCursor;
    }

    /**
     * Sets a custom {@link limn.backend.ImageCursor} shown while the pointer is
     * over this widget (inherits down the tree like {@link #setCursor}).
     * {@code null} restores inheritance/shape resolution. UI thread only.
     */
    public void setImageCursor(limn.backend.ImageCursor cursor) {
        Ui.checkUiThread();
        this.imageCursor = cursor;
        if (scene != null) {
            scene.cursorChanged(this);
        }
    }

    private limn.i18n.I18nString tooltip;

    /**
     * @return the hover tooltip text for this widget, or {@code null}/empty for
     *         none. The scene shows it after a short dwell, near the pointer.
     *         Resolved by walking up from the hovered leaf (like {@link #cursor}),
     *         under this widget's own {@linkplain #locale() locale}: a tooltip
     *         belongs to the subtree it annotates, and the scene painting it is
     *         outside any pass that would put that locale in scope.
     */
    public String tooltip() {
        if (tooltip == null) {
            return null;
        }
        Locale enclosing = I18n.pushScope(locale());
        try {
            return tooltip.get();
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /**
     * @return the localizable string behind {@link #tooltip()}, or {@code null} for none. The
     *         "is it set here" reader, matching the {@code text()}/{@code textSource()} pairs the
     *         components expose: a tooltip is the natural description of an icon-only control and
     *         the natural name when it has no other, and neither can be re-resolved when the
     *         translation epoch moves unless the string itself is reachable.
     */
    public limn.i18n.I18nString tooltipSource() {
        return tooltip;
    }

    /** Sets the hover tooltip text ({@code null} clears it). UI thread only. */
    public final void setTooltip(String text) {
        setTooltip(text == null ? null : limn.i18n.I18nString.literal(text));
    }

    /**
     * Sets a tooltip that follows the UI language ({@code null} clears it). UI thread only.
     *
     * <p>It repaints nothing, because a tooltip is painted only while it is showing and the
     * scene re-reads it then. It does {@linkplain #invalidateAccessible() tell the accessible
     * tree}, because a tooltip is a description there and often a name, and a change that paints
     * nothing would otherwise reach a screen reader on the next unrelated repaint, or never.
     *
     * <p>Announces {@code DESCRIPTION}/{@code CODE}, which is the other change here that produces
     * no frame. <b>The guard in front of it compares by value and not by reference</b>, and that
     * is load-bearing rather than a nicety: the {@code String} overload wraps its argument in a
     * fresh {@link limn.i18n.I18nString} on every call, so a {@code !=} guard would never hold
     * and every repeated {@code setTooltip("Play")} would announce a description that did not
     * change. {@code I18nString} answers this already, over its key and its English.
     */
    public final void setTooltip(limn.i18n.I18nString text) {
        Ui.checkUiThread();
        if (Objects.equals(this.tooltip, text)) {
            return;
        }
        this.tooltip = text;
        invalidateAccessible();
        notifyChange(Change.of(Change.Aspect.DESCRIPTION, Change.Origin.CODE));
    }

    /** Whether this widget currently holds its scene's keyboard focus. */
    public final boolean isFocused() {
        return scene != null && scene.focusedWidget() == this;
    }

    /**
     * Asks the scene to move keyboard focus here (UI thread only).
     *
     * <p>This is the method an application calls, so the focus move it makes is {@code CODE}.
     */
    public final void requestFocus() {
        requestFocus(Change.Origin.CODE);
    }

    /**
     * The same, for a component moving focus as part of a gesture it is handling: a tab strip's
     * arrow key, a radio group's. Announces {@code FOCUS} with {@code origin} on the widget
     * losing focus and on the widget gaining it.
     *
     * <p>{@code protected} and not package-private, because the two components that need it are
     * in {@code limn.components} and this is {@code limn.scene}. That places it where a component
     * author can reach it and an application cannot -- which is the right side of the line, since
     * labelling the origin is a component author's obligation and an application asserting that
     * it is the user would be a claim the toolkit cannot check. A component reaches it on
     * <em>another</em> widget through a package-private one-liner on that widget's own class, the
     * way a radio group already drives its members.
     *
     * @param origin what moved the focus
     */
    protected final void requestFocus(Change.Origin origin) {
        Ui.checkUiThread();
        if (scene != null) {
            scene.requestFocus(this, origin);
        }
    }

    // ------------------------------------------------------- control size axis

    /**
     * @return the step this widget declares for itself <em>and its subtree</em>, or
     *         {@code null} when it inherits. This is the "is it set here" reader; use
     *         {@link #controlSize()} for the effective value (cf. {@link #isVisible()}
     *         vs {@link #isShowing()}).
     */
    public final ControlSize declaredControlSize() {
        return declaredControlSize;
    }

    /**
     * @return the effective size step, never {@code null}: this widget's declared value,
     *         else the nearest declaring ancestor's, else its {@linkplain Scene#controlSize()
     *         scene default}, else its {@linkplain #setInheritanceHost host}'s, else
     *         {@link ControlSize#processDefault()}.
     *
     * <p><b>Read this inside {@link #onMeasure}, {@link #onPaint} or an event handler.</b>
     * Never in a constructor or a field initializer: a widget has no parent while it is
     * being constructed, so the answer there is the process default no matter what the
     * eventual parent declares, and a captured value can never be corrected.
     *
     * <p>Steady-state cost is one {@code long} compare and one field read. On the first
     * resolution after an epoch bump this recurses <em>one</em> step and delegates, so a
     * top-down measure or paint pass re-memoizes the whole tree in O(n) links total.
     *
     * <p>{@code final} by contract: {@link #measure} keys its cache on the resolved step, so
     * a subclass that computed a step on the fly would produce sizes the invalidation system
     * cannot see. A composite that owns widgets outside its own subtree links them with
     * {@link #setInheritanceHost} instead of overriding anything.
     */
    public final ControlSize controlSize() {
        if (resolvedEpoch == controlSizeEpoch) {
            return resolvedControlSize;
        }
        ControlSize resolved = resolveControlSize();
        resolvedControlSize = resolved;
        resolvedEpoch = controlSizeEpoch;
        return resolved;
    }

    /**
     * One link of the resolution chain. Recursion, not a loop, on purpose: the parent
     * memoizes its own answer on the way, and both the measure and the paint pass walk
     * top-down, so the chain is walked once per epoch per path rather than once per read.
     *
     * <p>The scene default is consulted <b>before</b> the host link, and the order is
     * load-bearing: every hosted root (a combo popup panel, a menu surface, a dialog panel)
     * has a host link, so consulting the host first would make {@link Scene#setControlSize}
     * unreachable for every popup, menu and dialog scene in the process. A {@code null}
     * scene default is exactly what lets a popup fall through to its host.
     *
     * <p>A host link on a widget that <em>does</em> have a parent is ignored: the tree wins.
     * The link exists for the two shapes the tree cannot express: a {@link Scene} root and
     * an overlay pushed with {@code pushOverlay}, both parentless.
     */
    private ControlSize resolveControlSize() {
        if (declaredControlSize != null) {
            return declaredControlSize;
        }
        if (parent != null) {
            return parent.controlSize();
        }
        if (scene != null) {
            ControlSize sceneDefault = scene.controlSize();
            if (sceneDefault != null) {
                return sceneDefault;
            }
        }
        if (inheritanceHost != null) {
            return inheritanceHost.controlSize();
        }
        return ControlSize.processDefault();
    }

    /**
     * Sets the step for this widget and every descendant that does not declare its own;
     * it inherits down the tree like {@link #setCursor}. {@code null} restores inheritance.
     * Re-measures whatever actually changed and repaints; a descendant that declares its own
     * step keeps its measure cache. No-op when unchanged. UI thread only.
     */
    public final void setControlSize(ControlSize size) {
        Ui.checkUiThread();
        if (declaredControlSize == size) {
            return;
        }
        declaredControlSize = size;
        bumpControlSizeEpoch();
        markNeedsLayout();
    }

    /**
     * @return the widget this one's inherited axes resolve through when the tree cannot say, or
     *         {@code null} when there is none. Every popup, menu and dialog root writes this
     *         link, and it is the only path from such a root back to the control that opened it:
     *         the accessible tree publishes that link as a relation in both directions, so a
     *         client walking either way finds the other.
     * @see #setInheritanceHost(Widget)
     */
    public final Widget inheritanceHost() {
        return inheritanceHost;
    }

    /**
     * Links this widget's <b>inherited axes</b> — its {@link ControlSize}, its
     * {@link LayoutDirection} and its {@linkplain #locale() locale} alike — to {@code host},
     * for the case the tree cannot express: a widget that is the root of its own {@link Scene}
     * (a popup or dialog window) or a {@linkplain Scene#pushOverlay overlay}, both of which
     * have no parent. One link carries them all, because it says "this parentless panel
     * belongs to that widget", which is a fact about no single axis; a second link that could
     * name a different widget per axis would be a bug with no honest resolution.
     *
     * <p>The chain then continues from {@code host}, <b>live</b>, so a later change on the host
     * reaches the popup while it is open, which explicit forwarding could not do (and which
     * would additionally convert an inherited value into a declared one, pinning the popup if a
     * process default changed underneath it). {@code null} unlinks. UI thread only.
     *
     * <p>Consulted <em>after</em> this widget's own scene default, so a popup scene that
     * declares a step or a direction keeps it and one that declares nothing falls through to
     * its host.
     *
     * <p><b>Install it before anything sizes the surface.</b> A native popup or dialog
     * measures its content to size its window <em>before</em> binding a scene; installing
     * the host after that sizes the window at the process defaults and then re-measures the
     * content at the owner's step inside a wrongly-sized window.
     *
     * <p><b>Live resolution is not live repaint.</b> A hosted root in its own scene resolves
     * the new value on its next pass, but nothing marks that scene dirty when the owner's axis
     * changes, so a component holding an open popup has to ask for the pass itself.
     *
     * @param host the widget to resolve through, or {@code null} to unlink
     * @throws IllegalArgumentException if {@code host} resolves through this widget
     */
    public final void setInheritanceHost(Widget host) {
        Ui.checkUiThread();
        // Walk the chain host would resolve through, exactly as resolveControlSize does.
        // Terminates: add() forbids tree cycles, and every previously installed host link
        // was validated the same way, so by induction the chain is finite.
        for (Widget w = host; w != null; w = w.parent != null ? w.parent : w.inheritanceHost) {
            if (w == this) {
                throw new IllegalArgumentException("cycle: host resolves through this widget");
            }
        }
        if (this.inheritanceHost == host) {
            return;
        }
        this.inheritanceHost = host;
        bumpControlSizeEpoch();
        bumpLayoutDirectionEpoch();
        bumpLocaleEpoch();
        markNeedsLayout();
    }

    // --------------------------------------------------- layout direction axis

    /**
     * @return the direction this widget declares for itself <em>and its subtree</em>, or
     *         {@code null} when it inherits. The "is it set here" reader; use
     *         {@link #layoutDirection()} for the effective value.
     */
    public final LayoutDirection declaredLayoutDirection() {
        return declaredLayoutDirection;
    }

    /**
     * @return the effective layout direction, never {@code null}: this widget's declared value,
     *         else the nearest declaring ancestor's, else its
     *         {@linkplain Scene#layoutDirection() scene default}, else its
     *         {@linkplain #setInheritanceHost host}'s, else
     *         {@link LayoutDirection#processDefault()}.
     *
     * <p><b>Read this inside {@link #onMeasure}, {@link #onPaint}, {@link #onLayout} or an event
     * handler</b>, and resolve it <b>once per pass</b> into a local. Never in a constructor or a
     * field initializer: a widget has no parent while it is being constructed, so the answer
     * there is the process default no matter what the eventual parent declares, and a captured
     * value can never be corrected. Two resolutions that disagree inside one {@code onPaint} put
     * the caret on one side and the selection band on the other.
     *
     * <p>Steady-state cost is one {@code long} compare and one field read, and the epoch is its
     * own: a control-size change does not invalidate this memo, and this does not invalidate
     * that one. On the first resolution after a bump this recurses <em>one</em> step and
     * delegates, so a top-down pass re-memoizes the whole tree in O(n) links total.
     *
     * <p>{@code final} by contract, for {@link #controlSize()}'s reason: {@link #measure} keys
     * its cache on the resolved direction, so a subclass computing one on the fly would produce
     * sizes the invalidation system cannot see.
     */
    public final LayoutDirection layoutDirection() {
        if (resolvedDirectionEpoch == layoutDirectionEpoch) {
            return resolvedLayoutDirection;
        }
        LayoutDirection resolved = resolveLayoutDirection();
        resolvedLayoutDirection = resolved;
        resolvedDirectionEpoch = layoutDirectionEpoch;
        return resolved;
    }

    /**
     * Whether this widget reads right to left: {@link #layoutDirection()} is {@code RTL}. The
     * test every mirrored pass makes, here once rather than as a private method in each widget.
     * Read it where {@code layoutDirection()} may be read: in a pass or an event handler, never
     * in a constructor.
     *
     * @return whether the resolved direction is right to left
     */
    public final boolean isRightToLeft() {
        return layoutDirection().isRightToLeft();
    }

    /**
     * One link of the resolution chain, and {@link #resolveControlSize()}'s chain exactly: the
     * same order, for the same reason. The scene default is consulted <b>before</b> the host
     * link because every hosted root (a combo popup panel, a menu surface, a dialog panel) has a
     * host link, so consulting the host first would make {@link Scene#setLayoutDirection}
     * unreachable for every popup, menu and dialog scene in the process.
     *
     * <p>A host link on a widget that <em>does</em> have a parent is ignored: the tree wins.
     */
    private LayoutDirection resolveLayoutDirection() {
        if (declaredLayoutDirection != null) {
            return declaredLayoutDirection;
        }
        if (parent != null) {
            return parent.layoutDirection();
        }
        if (scene != null) {
            LayoutDirection sceneDefault = scene.layoutDirection();
            if (sceneDefault != null) {
                return sceneDefault;
            }
        }
        if (inheritanceHost != null) {
            return inheritanceHost.layoutDirection();
        }
        return LayoutDirection.processDefault();
    }

    /**
     * Sets the layout direction for this widget and every descendant that does not declare its
     * own; it inherits down the tree like {@link #setControlSize}. {@code null} restores
     * inheritance. Re-measures whatever actually changed and repaints; a descendant that declares
     * its own direction keeps its measure cache. No-op when unchanged. UI thread only.
     */
    public final void setLayoutDirection(LayoutDirection direction) {
        Ui.checkUiThread();
        if (declaredLayoutDirection == direction) {
            return;
        }
        declaredLayoutDirection = direction;
        bumpLayoutDirectionEpoch();
        markNeedsLayout();
    }

    // ------------------------------------------------------------- locale axis

    /**
     * @return the locale this widget declares for itself <em>and its subtree</em>, or
     *         {@code null} when it inherits. The "is it set here" reader; use
     *         {@link #locale()} for the effective value.
     */
    public final Locale declaredLocale() {
        return declaredLocale;
    }

    /**
     * @return the effective locale, never {@code null}: this widget's declared value, else
     *         the nearest declaring ancestor's, else its {@linkplain Scene#locale() scene
     *         default}, else its {@linkplain #setInheritanceHost host}'s, else the
     *         {@linkplain I18n#processLocale() process locale}. The language this subtree's
     *         strings resolve in, its numbers take their digits from, and its text breaks
     *         lines under (ADR 035); it is <b>not</b> a direction &mdash; a Hebrew-locale
     *         subtree still lays out by its {@link #layoutDirection()}, and the two axes are
     *         declared separately because they genuinely vary separately.
     *
     * <p><b>Widgets rarely need to read this.</b> While the toolkit is inside this widget's
     * measure, layout, paint or an event handler, {@link I18n#locale()} already answers it
     * (the pass holds it {@linkplain I18n#pushScope in scope}), so {@code I18nString.get()},
     * {@code I18n.localizeDigits} and every other locale reader is correct unchanged. Read
     * it explicitly to hand the answer somewhere the scope cannot follow: a posted task, a
     * native window's title.
     *
     * <p><b>Never read it in a constructor or a field initializer</b>, for the reason the
     * other axes forbid it: a widget has no parent while it is being constructed, so the
     * answer there is the process locale no matter what the eventual parent declares.
     *
     * <p>Steady-state cost is two {@code long} compares and a field read. The memo is
     * validated against its own epoch <em>and</em> {@link I18n#epoch()}, because the chain
     * bottoms out in the process locale and {@link I18n#setLocale} cannot reach a counter in
     * this package; a bundle registration therefore re-resolves this memo spuriously, one
     * link per widget, which is the recorded price of keeping the axes' writers apart.
     *
     * <p>{@code final} by contract, for {@link #controlSize()}'s reason: {@link #measure}
     * keys its cache on the resolved locale.
     */
    public final Locale locale() {
        if (resolvedLocaleEpoch == localeEpoch && resolvedLocaleI18nEpoch == I18n.epoch()) {
            return resolvedLocale;
        }
        Locale resolved = resolveLocale();
        resolvedLocale = resolved;
        resolvedLocaleEpoch = localeEpoch;
        resolvedLocaleI18nEpoch = I18n.epoch();
        return resolved;
    }

    /**
     * One link of the resolution chain, and {@link #resolveControlSize()}'s chain exactly:
     * the same order, for the same reasons, the scene default before the host link so
     * {@link Scene#setLocale} stays reachable for every popup, menu and dialog scene. The
     * bottom is {@link I18n#processLocale()} and deliberately not {@link I18n#locale()}:
     * during a pass the latter answers the <em>enclosing</em> widget's scope, and a chain
     * that consulted it would hand a parentless popup root whatever subtree happened to be
     * mid-paint.
     */
    private Locale resolveLocale() {
        if (declaredLocale != null) {
            return declaredLocale;
        }
        if (parent != null) {
            return parent.locale();
        }
        if (scene != null) {
            Locale sceneDefault = scene.locale();
            if (sceneDefault != null) {
                return sceneDefault;
            }
        }
        if (inheritanceHost != null) {
            return inheritanceHost.locale();
        }
        return I18n.processLocale();
    }

    /**
     * Sets the locale for this widget and every descendant that does not declare its own; it
     * inherits down the tree like {@link #setControlSize}. {@code null} restores inheritance.
     * This is ADR 006 §4's escape hatch, delivered by ADR 035: the recorded case is a Hebrew
     * interface holding a left-to-right, English-locale code pane, where reading everything
     * off the process locale is the shortcut that breaks it.
     *
     * <p>The declared locale is {@linkplain I18n#retainLocale retained} while the
     * declaration stands, so every bundle keeps a prepared table for it; clearing or
     * replacing the declaration releases it. A widget discarded while still declaring one
     * keeps that retain &mdash; clear the declaration (or accept a resident table per
     * language the process ever declared, which is usually one) when a locale-declaring
     * subtree is dropped for good.
     *
     * <p>Re-measures whatever actually changed and repaints; a descendant that declares its
     * own locale keeps its measure cache. No-op when unchanged. UI thread only.
     */
    public final void setLocale(Locale locale) {
        Ui.checkUiThread();
        if (Objects.equals(declaredLocale, locale)) {
            return;
        }
        Locale previous = declaredLocale;
        declaredLocale = locale;
        if (locale != null) {
            I18n.retainLocale(locale);
        }
        if (previous != null) {
            I18n.releaseLocale(previous);
        }
        bumpLocaleEpoch();
        markNeedsLayout();
    }

    /**
     * Distance from this widget's top edge to its first text baseline, in logical points:
     * the alignment reference for {@link limn.scene.layout.Flex.CrossAlignment#BASELINE}.
     * Default {@code height()}: align on the bottom edge, the correct fallback for a widget
     * with no text. Text-bearing components override with the expression they already paint
     * with, {@code (height() - metrics.height()) / 2 + metrics.ascent()}.
     *
     * <p>Valid only once this widget has been given a box. {@code Flex} guarantees that: it
     * lays every child of a BASELINE line out at cross position 0 first, reads the baselines,
     * then repositions with {@link #moveChild}, which moves without re-running layout.
     */
    protected float baselineOffset() {
        return height();
    }

    /**
     * Reads {@code child}'s {@link #baselineOffset()}. For container authors, the same
     * {@code protected static} bridge shape as {@link #moveChild}, and necessary for the
     * same reason: {@code baselineOffset()} is {@code protected}, so a {@code Flex} in
     * {@code limn.scene.layout} cannot invoke it on another instance (JLS 6.6.2.1), but a
     * {@code protected static} member carries no qualifying-type restriction.
     */
    protected static float baselineOffsetOf(Widget child) {
        return child.baselineOffset();
    }

    // ---------------------------------------------------------------- layout

    /**
     * Measures the preferred size under {@code constraints}. Results are cached until
     * {@link #markNeedsLayout()}, and the cache key includes the <b>resolved axes</b> — the
     * control size, the layout direction and the locale, as one {@code MeasuredAxes} value —
     * so a container's change on any axis re-measures exactly the descendants whose resolved
     * value actually changed and leaves overriding subtrees on their caches. That is why no
     * deep-invalidation API is needed for any axis. Subclasses implement {@link #onMeasure}.
     *
     * <p>The direction belongs in the key because a line of mixed content genuinely measures a
     * fraction of a point differently in the two directions: the paragraph direction decides
     * which bidi level a boundary neutral takes, which decides which run it extends, which
     * decides which face measures it. The locale belongs there because a keyed string resolves
     * to different <em>text</em> under a different language, and a number to different digits.
     * A cache that cannot see an axis returns a stale size.
     *
     * <p>The widget's effective locale is held {@linkplain I18n#pushScope in scope} while
     * {@link #onMeasure} runs, so everything the measure resolves or formats — an
     * {@code I18nString}, a chart tick, a line break — answers in this subtree's language
     * without the subclass doing anything (ADR 035).
     *
     * <p>Correctness is a property of the key: the only way this can return a stale size is
     * if the resolved axes <em>and</em> the constraints <em>and</em> {@code needsMeasure} all
     * say nothing changed, in which case nothing did.
     */
    public final Size measure(Constraints constraints) {
        ControlSize step = controlSize();
        LayoutDirection direction = layoutDirection();
        Locale locale = locale();
        MeasuredAxes measured = measuredAxes;
        if (!needsMeasure
                && measured != null
                && measured.size() == step
                && measured.direction() == direction
                && measured.locale().equals(locale)
                && constraints.equals(lastConstraints)) {
            return lastSize;
        }
        Locale enclosing = I18n.pushScope(locale);
        try {
            lastSize = Objects.requireNonNull(onMeasure(constraints), "onMeasure returned null");
        } finally {
            I18n.popScope(enclosing);
        }
        lastConstraints = constraints;
        measuredAxes = new MeasuredAxes(step, direction, locale);
        needsMeasure = false;
        return lastSize;
    }

    /**
     * Reports the size this widget wants within {@code constraints}. Called once per
     * layout pass, and the result is cached against the constraints and the resolved
     * axes (size step, layout direction, locale), so it must be a pure function of them
     * and of this widget's own state.
     *
     * <p>Resolve the {@link ControlSize} and the {@link LayoutDirection} once each here and
     * thread them down; never read either in a constructor. The locale needs no threading:
     * it is in scope, and {@link I18n#locale()} answers it wherever text is resolved.
     */
    protected abstract Size onMeasure(Constraints constraints);

    /** Parent assigns final bounds (parent coords); then {@link #onLayout()} places children. */
    public final void layoutBox(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        Locale enclosing = I18n.pushScope(locale());
        try {
            onLayout();
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /** Containers position children here (measure + {@link #layoutBox} per child). */
    protected void onLayout() {
    }

    /**
     * Moves a child without re-running layout: the scroll fast path (size is
     * unchanged; only the offset moves). For container authors.
     */
    protected static void moveChild(Widget child, float x, float y) {
        child.x = x;
        child.y = y;
    }

    /** Marks this widget's measure dirty and schedules a scene layout pass. */
    public final void markNeedsLayout() {
        Ui.checkUiThread();
        for (Widget w = this; w != null; w = w.parent) {
            w.needsMeasure = true;
        }
        if (scene != null) {
            scene.markLayoutDirty(this);
        }
    }

    /**
     * Marks this widget's measure dirty and asks for a layout that repaints only what it moves:
     * the form for a widget whose size may change for a reason of its own, such as a label whose
     * text changed width.
     *
     * <p>It is the pass {@link #setVisible} uses. The scene climbs from the parent re-running each
     * ancestor's own measure against the constraints it last had, stops at the first whose size
     * did not change, lays that one out in place, and repaints the children that moved, where
     * they were and where they went, plus this widget. Because each ancestor's own layout code
     * runs, a parent that measures a child twice (loose to learn what it wants, then tight) sees
     * the new size as a full pass would; whatever the scene cannot compare, it lays out in full.
     * {@link #markNeedsLayout()} remains the form that makes no such claim.
     */
    protected final void markNeedsLayoutInPlace() {
        Ui.checkUiThread();
        for (Widget w = this; w != null; w = w.parent) {
            w.needsMeasure = true;
        }
        if (scene != null && parent != null) {
            scene.markVisibilityChanged(this);
        } else if (scene != null) {
            scene.markLayoutDirty(this); // a root has no parent to absorb it
        }
    }

    /**
     * Asks for this widget's own subtree to be laid out again, <b>without</b> declaring that the
     * frame is a full repaint: the form for a container whose insides move while its box does
     * not, such as a virtualised list mounting and recycling rows as it scrolls.
     *
     * <p>A scene treats an ordinary {@link #markNeedsLayout()} as full damage, because a layout
     * pass may move any widget without that widget invalidating where it used to be. This is the
     * narrow case where that cannot happen, and it is narrow on purpose:
     *
     * <ul>
     *   <li>the widget must {@linkplain #clipsChildren() clip its children}, so nothing inside it
     *       can paint outside the rectangle that gets damaged;</li>
     *   <li>its own measured size must come out unchanged. The scene re-measures against the
     *       constraints its parent last gave it and compares: <b>if the size moved, the parent's
     *       layout is stale and the scene falls back to a full pass</b>, so getting this wrong
     *       costs a frame rather than correctness.</li>
     * </ul>
     *
     * <p>Damage is this widget's bounds. A widget that paints outside them still has its
     * {@link #paintOutset()} honoured, because the scene damages through the same path
     * {@link #invalidate()} uses.
     *
     * <p><b>Adding or removing children from inside the pass is expected and does not
     * escalate.</b> That is what a list does when it recycles a row. The same call from
     * anywhere else, or for a widget outside the subtree being laid out, escalates the way it
     * always did.
     */
    public final void markNeedsContainedLayout() {
        Ui.checkUiThread();
        needsMeasure = true;
        if (scene != null) {
            scene.markContainedLayout(this);
        }
    }

    /** The constraints this widget last measured against, or {@code null} before its first. */
    final Constraints lastConstraints() {
        return lastConstraints;
    }

    /** The size {@link #measure} last answered, or {@code null} before its first. */
    final Size lastSize() {
        return lastSize;
    }

    /**
     * Invalidates this whole subtree's measure caches. Used when a global input
     * to measurement changes (e.g. the UI font family is switched at runtime), so
     * every widget re-measures against the new metrics, not just the path to root.
     */
    final void markMeasureDirtyDeep() {
        needsMeasure = true;
        for (int i = 0; i < children.size(); i++) {
            children.get(i).markMeasureDirtyDeep();
        }
    }

    /**
     * Requests a repaint (event-driven: the loop wakes and redraws once). Also
     * records this widget's bounds as damage so a scene with
     * {@linkplain Scene#setPartialRendering(boolean) partial rendering} on, which is the
     * default, repaints only the changed region. A widget whose painting can extend
     * beyond its bounds must widen the region via {@link Scene#damage(Rect)}.
     */
    public final void invalidate() {
        if (scene != null) {
            scene.damageWidget(this);
        }
    }

    /**
     * Requests a repaint of a region in this widget's <em>local</em>
     * coordinates: the fine-grained {@link #invalidate()} for widgets that
     * know exactly which pixels changed (a blinking caret, one cell of a
     * grid). Under partial rendering only that region is repainted; regions
     * may extend beyond this widget's bounds.
     */
    public final void invalidate(float x, float y, float width, float height) {
        if (scene != null) {
            scene.damageWidgetRegion(this, x, y, width, height);
        }
    }

    /** Text measurer for layout-time metrics (never null; NONE when detached). */
    protected final limn.graphics.TextRuler textRuler() {
        return scene != null ? scene.textRuler() : limn.graphics.TextRuler.NONE;
    }

    /**
     * What a run of text with no strong character of its own falls back to: this widget's
     * resolved direction, as the shaper's neutral base. A caption that is a bare number, a
     * clock face or a punctuation mark reads the way the interface around it reads, and the
     * first-strong rule cannot know that; the widget can. It is a fallback and not an
     * imposition &mdash; a Latin caption in a right-to-left tree still reads left to right,
     * because a strong character already decided it.
     *
     * <p>Read it where {@link #layoutDirection()} may be read: in a pass or an event handler,
     * never in a constructor or a field initializer.
     */
    protected final limn.graphics.ShapedText.Direction neutralBase() {
        return layoutDirection().neutralBase();
    }

    /**
     * Shapes one line of {@code text} the way this widget reads: the first-strong rule decides
     * for any string that can decide for itself, and {@link #neutralBase()} decides for the
     * rest. This is <b>the</b> way for a widget to get a line. Hold the result (see
     * {@code ShapedText.matches} for the idiom), take the natural width from the line's own
     * metrics, and hand the line itself to the canvas.
     *
     * <p>A chart is the widget this matters most to, and the reason is worth keeping in view:
     * its chrome is application data that is very often entirely neutral &mdash; a series named
     * {@code 2024}, a tooltip row reading {@code 3.5}, a category called {@code Q1}. Not one of
     * those has a strong character, so the first-strong rule has nothing to decide with and the
     * fallback decides all of it, and the fallback is the direction of the interface, which only
     * the widget knows. A series named {@code Vendas} is unaffected, because its V already decided.
     *
     * <p>The alternatives quietly drop the direction: {@code Canvas.drawText(String, …)} and
     * {@code TextRuler.measure} carry no base, so they resolve every all-neutral string &mdash;
     * a count, a year, a price &mdash; with a hard-coded left-to-right fallback, which is right
     * almost always and silent when it is not. Neither signature can gain a direction (the
     * ruler is a {@code @FunctionalInterface} every test fake satisfies with a lambda), so the
     * seam is here, on the widget, which is the one place that knows the answer.
     */
    protected final limn.graphics.ShapedText shapeText(String text, limn.graphics.Font font) {
        return textRuler().shape(text, font,
                limn.graphics.ShapedText.Direction.of(text, neutralBase()));
    }

    /** System clipboard (never null; a local no-op when detached). */
    protected final limn.backend.Clipboard clipboard() {
        return scene != null ? scene.clipboard() : limn.backend.Clipboard.NONE;
    }

    // --------------------------------------------------------------- payload

    /**
     * Paints this widget and its children ({@code canvas} origin = this widget).
     *
     * <p><b>It leaves the canvas at the depth it found it.</b> A widget's paint may push clips and
     * transforms, down its own branch and through code the toolkit does not own (an application's
     * icon, an adapter's row, a 3D render callback), and it may stop halfway by throwing. Either a
     * forgotten {@code restore()} or a throw would otherwise leak a {@code save()} into every
     * ancestor still unwinding, and the frame would end unbalanced with the warning naming nobody,
     * because whatever caused it left the stack long before.
     *
     * <p>So the depth is taken before and trimmed back after, in a {@code finally}. Individual
     * containers still guard their own clips, which is more precise; this is the net under them,
     * not a licence to stop.
     *
     * <p>An imbalance on a NORMAL return is a plain bug in the widget rather than fallout from
     * something else, so it is reported once per class. On an exceptional return nothing is
     * reported: the throw is already being handled, and a second message about its side effect
     * would only bury it.
     */
    public final void paintWidget(Canvas canvas) {
        if (!visible) {
            return;
        }
        if (scene != null && scene.culledFromPaint(this)) {
            return; // partial rendering: this subtree misses the repaint pass
        }
        if (missesClip(canvas)) {
            return; // every pixel of this subtree is clipped away: scrolled out of a viewport
        }
        if (scene != null) {
            scene.metrics().countPaintedWidget();
        }
        int depth = canvas.saveCount();
        boolean completed = false;
        // The paint runs with this widget's locale in scope, so text resolved on the way
        // down answers in this subtree's language; a child's own paintWidget nests its own.
        Locale enclosing = I18n.pushScope(locale());
        try {
            onPaint(canvas);
            paintChildren(canvas);
            onPaintOverlay(canvas);
            completed = true;
        } finally {
            I18n.popScope(enclosing);
            int leaked = canvas.saveCount() - depth;
            if (leaked != 0) {
                if (completed) {
                    reportUnbalancedPaint(leaked);
                }
                canvas.restoreToCount(depth);
            }
        }
    }

    /**
     * Whether this widget's box, grown by {@link #paintOutset()} and the pixel analytic
     * antialiasing feathers, lies wholly outside the canvas's current clip. The same test partial
     * rendering makes against its pass rect ({@code Scene.culledFromPaint}), made against the
     * clip instead, so that a ScrollView over a long column walks and emits only the rows that
     * can show: without it every scrolled-out row still ran its paint and put its quads in the
     * batch for the GPU to discard. A canvas that cannot report its clip answers null, and then
     * nothing is skipped.
     */
    private boolean missesClip(Canvas canvas) {
        limn.graphics.Rect clip = canvas.clipBounds();
        if (clip == null) {
            return false;
        }
        float outset = 1 + paintOutset();
        return width + outset <= clip.x() || -outset >= clip.x() + clip.width()
                || height + outset <= clip.y() || -outset >= clip.y() + clip.height();
    }

    /** Class names already reported, so a widget painted every frame is named once and not 60 times. */
    private static final java.util.Set<String> UNBALANCED_REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void reportUnbalancedPaint(int leaked) {
        String name = getClass().getName();
        if (UNBALANCED_REPORTED.add(name)) {
            System.getLogger(Widget.class.getName()).log(System.Logger.Level.WARNING,
                    "{0} returned from paint with {1} unbalanced save(s); the canvas was trimmed "
                            + "back, but the widget should restore what it saves",
                    name, leaked);
        }
    }

    /**
     * How far beyond this widget's bounds its painting may reach, in logical
     * points: its own painting and any descendant no ancestor clips (a drop
     * shadow, a glow, a child positioned outside the parent's box). Partial
     * rendering uses it both ways: {@link #invalidate()} damage grows by it,
     * and a repaint pass skips this subtree only when bounds + outset miss the
     * pass region; painting farther than declared leaves stale pixels.
     * Default {@code 0}: everything stays inside the bounds, which is true
     * for most built-in components, but not all. {@code Label} (icon overhang)
     * and {@code Button}, {@code Checkbox} and {@code RadioButton} (focus rings
     * drawn outside the box) override it.
     */
    protected float paintOutset() {
        return 0;
    }

    /** Widget's own background/content, in local coordinates. */
    protected void onPaint(Canvas canvas) {
    }

    /**
     * Whether {@link #paintChildren} clips its children to this widget's
     * bounds (scroll views, list viewports, tab strips). Partial rendering
     * uses it to clamp a descendant's {@link #invalidate()} damage to the
     * visible region; a widget scrolled out of view damages nothing. Any
     * override that clips in {@code paintChildren} should also override this, and one whose clip
     * is smaller than its box for some child should override the four clip accessors below too.
     */
    protected boolean clipsChildren() {
        return false;
    }

    /**
     * The left edge, in this widget's own coordinates, of the rectangle {@link #clipsChildren()}
     * clips {@code child} to.
     *
     * <p>The whole box by default. A widget that paints one child inside an inset -- a scroll pane
     * whose reserved gutters hold its bars, clipping the content to the viewport and the bars to
     * the box -- answers per child, so that {@link #isShowing()} and the damage clamp agree with
     * what it paints: until it did, a descendant lying wholly inside the gutter was published on
     * screen in the very rectangle the bar occupies. Consulted only while {@code clipsChildren()}
     * is true, and it must not allocate, because the showing test runs for every node of every
     * accessible walk.
     *
     * @param child the direct child the clipped descendant is under
     * @return the clip rectangle's left edge
     */
    protected float clipX(Widget child) {
        return 0;
    }

    /**
     * The top edge of the rectangle this widget clips {@code child} to; see {@link #clipX(Widget)}.
     *
     * @param child the direct child the clipped descendant is under
     * @return the clip rectangle's top edge
     */
    protected float clipY(Widget child) {
        return 0;
    }

    /**
     * The width of the rectangle this widget clips {@code child} to; see {@link #clipX(Widget)}.
     *
     * @param child the direct child the clipped descendant is under
     * @return the clip rectangle's width
     */
    protected float clipWidth(Widget child) {
        return width;
    }

    /**
     * The height of the rectangle this widget clips {@code child} to; see {@link #clipX(Widget)}.
     *
     * @param child the direct child the clipped descendant is under
     * @return the clip rectangle's height
     */
    protected float clipHeight(Widget child) {
        return height;
    }

    /**
     * Children pass; override to clip (e.g. scroll views).
     *
     * <p>Indexed rather than an enhanced {@code for}, for the reason {@code Scene}'s own overlay
     * loop already carries: an iterator here is an allocation per widget per frame, and an idle
     * window is the one place in this toolkit that is not allowed to allocate at all. A subtree of
     * two hundred widgets was paying two hundred short-lived objects a frame to walk a list it can
     * index.
     */
    protected void paintChildren(Canvas canvas) {
        for (int i = 0; i < children.size(); i++) {
            Widget child = children.get(i);
            canvas.save();
            try {
                canvas.translate(child.x, child.y);
                child.paintWidget(canvas);
            } finally {
                // In a finally so a widget that throws mid-paint cannot leak
                // canvas state: the frame is contained higher up (see Crashes),
                // but every ancestor in the unwind would otherwise leave a
                // save() behind and the "unbalanced save()/restore()" warning
                // would fire for a bug that has nothing to do with balance,
                // masking the real ones.
                canvas.restore();
            }
        }
    }

    /** Painted after children (scrollbars, focus rings…). */
    protected void onPaintOverlay(Canvas canvas) {
    }

    // ---------------------------------------------------------------- events

    /**
     * Deepest visible/enabled descendant containing the point (local coords),
     * or this widget itself; {@code null} when outside. Later children win
     * because they paint on top.
     */
    public Widget hitTest(float localX, float localY) {
        if (!visible || !enabled
                || localX < 0 || localY < 0 || localX >= width || localY >= height) {
            return null;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            Widget hit = child.hitTest(localX - child.x, localY - child.y);
            if (hit != null) {
                return hit;
            }
        }
        return this;
    }

    /**
     * When this widget is the active modal overlay, whether a pointer hit at the
     * given <b>scene</b> point should fall through to the content beneath it
     * instead of being captured. Default {@code false}: an overlay owns all
     * pointer input. The in-scene menu overrides this so the menu bar behind the
     * fullscreen fallback keeps hover-switching and its cursor, exactly as it does
     * when the menu is a separate native window.
     */
    protected boolean overlayPassesPointer(float sceneX, float sceneY) {
        return false;
    }

    /** Mouse events (bubbling); call {@code event.consume()} when handled. */
    protected void onMouseEvent(MouseEvent event) {
    }

    /** Key events (focused widget first, then ancestors). */
    protected void onKeyEvent(KeyEvent event) {
    }

    /** Committed text input (focused widget first, then ancestors). */
    protected void onCharTyped(CharEvent event) {
    }

    /**
     * In-progress IME composition ("preedit") for this widget while it is
     * focused: the still-composing text, shown inline but not yet committed
     * (the commit later arrives as {@link #onCharTyped}). Only widgets that
     * {@linkplain #acceptsTextInput() accept text input} receive it. Default:
     * ignored.
     */
    protected void onPreedit(PreeditEvent event) {
    }

    /**
     * Whether this widget edits text and should drive the platform input method
     * (IME): the scene enables the IME while such a widget holds focus and
     * disables it otherwise, so composition never intercepts keys meant for
     * non-text UI. Text widgets override this to {@code true}. Default:
     * {@code false}.
     */
    protected boolean acceptsTextInput() {
        return false;
    }

    /**
     * The caret rectangle in <em>scene</em> coordinates (logical points) used to
     * place the IME candidate window; {@code null} when there is no caret to
     * anchor (or the widget is not laid out yet). Consulted by the scene only
     * while this widget is focused and {@link #acceptsTextInput()} is true.
     */
    protected limn.graphics.Rect caretRect() {
        return null;
    }

    /**
     * Files dropped from the OS onto this widget (bubbling from the widget under
     * the pointer); call {@code event.consume()} when handled. Default: ignored.
     */
    protected void onFileDrop(FileDropEvent event) {
    }

    // ------------------------------------------------------------- the change channel

    /**
     * The watchers of this widget alone, null until the first one arrives: four bytes on a widget
     * nobody watches, which is most widgets most of the time. Swapped rather than mutated, so a
     * dispatch in flight keeps walking the array it started with.
     */
    private ChangeObserver[] watchers;

    private static final ChangeObserver[] NO_WATCHERS = new ChangeObserver[0];

    /**
     * How deep the UI thread is inside a paint. Read by every announcement, so the rule that
     * <b>nothing announces from inside a paint</b> is enforced rather than promised.
     *
     * <p>On the thread and not on a {@link Scene}, because a widget mutated during a paint need
     * not belong to the scene being painted, or to any scene at all; a counter and not a boolean,
     * because nesting must not clear it early. There is exactly one UI thread in a process and
     * both the paint and every announcement are confined to it, so this is a plain field that one
     * thread writes and reads.
     */
    private static int paintDepth;

    /** Raised by {@link Scene} around a paint pass. */
    static void beginPaint() {
        paintDepth++;
    }

    /** Lowered by {@link Scene}, in a {@code finally}. */
    static void endPaint() {
        paintDepth--;
    }

    /**
     * Watches every change to this widget, from every origin, without touching whatever handler
     * the application registered.
     *
     * <p>A watcher hears what a handler does not: a value a caller wrote, a selection the widget
     * adjusted by itself, a focus move, a label's new text. It is the channel a two-way binding,
     * an inspector, a test and anything describing this tree to something outside it uses, and
     * any number of parties may use it at once without being able to disturb each other or the
     * application.
     *
     * <p>The subscription belongs to the widget: it survives detach and re-attach, costs nothing
     * to keep across the mount and unmount a recycled list cell performs on every frame of a
     * scroll, and dies with the widget, so there are no weak references here and none are needed.
     * <b>A late subscriber is owed nothing</b> -- there is no replay and no priming call, because
     * nothing can enumerate every aspect of an arbitrary widget and a synthetic change is
     * indistinguishable from a real one to a listener that pushes an undo entry or plays a sound.
     * What a late subscriber does instead is read the widget: subscribe, then read.
     *
     * @param observer told about every change to this widget; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    public final Subscription observeChanges(ChangeObserver observer) {
        Ui.checkUiThread();
        Objects.requireNonNull(observer, "observer");
        watchers = Listeners.added(watchers, observer, NO_WATCHERS);
        return new WatcherHandle(observer);
    }

    /** One registration, dropped once: a second cancel has nothing left to take off the array. */
    private final class WatcherHandle implements Subscription {

        private ChangeObserver observer;

        WatcherHandle(ChangeObserver observer) {
            this.observer = observer;
        }

        @Override
        public void cancel() {
            Ui.checkUiThread();
            ChangeObserver taken = observer;
            if (taken == null) {
                return;
            }
            observer = null;
            watchers = Listeners.removed(watchers, taken);
        }
    }

    /**
     * Announces a change that has already settled, and then runs the handler if the origin is
     * {@code USER}: the last statement of an ordinary mutator, after the state, the layout marks,
     * the focus move and the reveal.
     *
     * <p>Exactly {@link #announceChange} followed by {@link #runHandler} -- the two halves in one
     * call, which is what every seam but a compound's wants. The order it delivers in is the
     * guarantee:
     *
     * <ol>
     * <li>this widget's watchers, in registration order;</li>
     * <li>the watchers of the scene this widget is in, if it is in one;</li>
     * <li>this widget's handler, if the origin is {@code USER}.</li>
     * </ol>
     *
     * <p>The handler runs <b>last</b>, and that is load-bearing rather than arbitrary: a handler
     * may mutate other widgets and each of those mutations announces itself as it happens, so a
     * handler that ran first would have every consequence of a change announced before the change
     * itself, and a watcher would see a label's new text before the checkbox that caused it.
     *
     * <p>Announce only what moved: a mutator handed the state it already holds calls nothing.
     * Where a call moved other aspects on its way to its own, those are announced in the order
     * they settled and the call's own aspect last, which is the signal that the call is finished.
     *
     * @param change what changed on this widget, and what moved it
     * @throws IllegalStateException if called from inside a paint. UI thread
     */
    protected final void notifyChange(Change change) {
        announceChange(change);
        runHandler(change);
    }

    /**
     * The announcing half alone: this widget's watchers, then its scene's watchers, and no
     * handler at any origin.
     *
     * <p>For a compound whose members must all be announced before any handler runs -- a radio
     * group's leaver and enterer -- which is its only caller in this repository. <b>A seam that
     * calls this owes a matching {@link #runHandler} for the same change</b>, or it has silently
     * disabled the application's handler.
     *
     * @param change what changed on this widget, and what moved it
     * @throws IllegalStateException if called from inside a paint. UI thread
     */
    protected final void announceChange(Change change) {
        checkNotPainting(change.aspect());
        announce(change);
    }

    /**
     * The handler half alone: runs this widget's handler for a change this widget has already
     * announced, and nothing at any origin but {@code USER}.
     *
     * <p>It takes the whole change rather than its aspect, because the origin is what decides
     * whether the handler runs at all -- which is what lets a compound seam hand it every member's
     * change without branching once per member.
     *
     * @param change the change this widget has just announced
     */
    protected final void runHandler(Change change) {
        if (change.origin() == Change.Origin.USER) {
            handleUserChange(change.aspect());
        }
    }

    /**
     * The same as {@link #notifyChange}, for a text edit, taking the edit's shape rather than a
     * built value so that a keystroke on a tree nobody watches constructs nothing. The three
     * numbers come from the editing model, which is the only thing that has them.
     *
     * <p>There is no announce-only sibling: no compound in this repository spans a text edit.
     *
     * <p>It reaches the handler exactly as {@code notifyChange} does, and without a
     * {@code Change} to hand to {@link #runHandler}: the handler half needs only the aspect and
     * the origin, and both are already here. So after every watcher has run it calls
     * {@code handleUserChange(TEXT)} when {@code origin} is {@code USER}, and nothing when it is
     * not -- a user keystroke on a widget nobody watches still reaches the application's handler
     * and still allocates nothing on this path.
     *
     * @param origin what moved the edit
     * @param offset where the damaged range begins, in chars
     * @param removed how many chars it replaced
     * @param inserted how many chars it inserted
     * @throws IllegalStateException if called from inside a paint. UI thread
     */
    protected final void notifyTextEdit(Change.Origin origin, int offset, int removed, int inserted) {
        checkNotPainting(Change.Aspect.TEXT);
        if (watchers != null || (scene != null && scene.hasChangeWatchers())) {
            announce(Change.edit(origin, offset, removed, inserted));
        }
        if (origin == Change.Origin.USER) {
            handleUserChange(Change.Aspect.TEXT);
        }
    }

    /** This widget's watchers and then its scene's, each inside its own try. */
    private void announce(Change change) {
        ChangeObserver[] mine = watchers;
        if (mine != null) {
            for (ChangeObserver observer : mine) {
                try {
                    observer.changed(this, change);
                } catch (Throwable error) {
                    Listeners.failed(CrashPhase.OBSERVER, error);
                }
            }
        }
        if (scene != null) {
            scene.announceChange(this, change);
        }
    }

    /**
     * Runs this widget's application handler for a change it has just announced. Called by
     * {@link #runHandler}, and so by {@link #notifyChange}, only for a {@code USER} origin and
     * after every watcher has run.
     *
     * <p>A component overrides it once and switches on the aspect, reading its own accessors for
     * the payload: a slider passes {@code value()}, a combo box {@code selectedIndex()}, a text
     * field {@code text()}. A component with no handler does not override it.
     *
     * <p><b>An override that does not recognise the aspect must call {@code super}</b>, because a
     * subclass of a component that has a handler inherits that component's dispatch through this
     * method: a {@code SearchField} that overrode this for its own aspect and did not chain would
     * silently delete {@code TextField}'s. The ordering lives in the base class, this is the one
     * link in it a subclass can break, and the contract test drives every subclass through its
     * parent's gestures to catch one that does.
     *
     * @param aspect what changed; the origin is {@code USER} or this is not called
     */
    protected void handleUserChange(Change.Aspect aspect) {
    }

    /**
     * The paint rule, enforced at the announcing site: a paint paints, and announces nothing.
     *
     * <p>Three reasons, and containment is not among them -- a watcher's throw never escapes the
     * frame, because each is invoked inside its own try. A paint is re-run when nothing changed (a
     * resize, an occlusion redraw, a swap-chain re-present), so a notification from one would
     * report a change nobody made. A watcher may legally mutate, and a mutation during a paint
     * lands on a frame that has already laid out. And a mutator must settle its layout marks and
     * then announce, which a mutator running after the pass cannot do: its announcement would be
     * truthful about the state and false about everything the state implies.
     *
     * <p><b>The check sits before the nobody-is-watching early return, deliberately.</b> After it
     * is cheaper and is the wrong place, because it makes the enforcement absent in exactly the
     * state the mistake is made in: a component author writes the offending {@code onPaint} with
     * no watcher anywhere, since a watcher is something a bridge or an inspector attaches later,
     * so the check that would have named the mistake never runs, the habit sets, and the first
     * thing to trip it is a screen reader attaching to a shipped application. What it costs the
     * quiet path is one field read and one branch.
     */
    private static void checkNotPainting(Change.Aspect aspect) {
        if (paintDepth > 0) {
            throw new IllegalStateException(
                    "a paint may not announce a change (" + aspect + "): move the mutation out of onPaint");
        }
    }

    // --------------------------------------------------------- accessibility

    /**
     * Says what this widget is, for an assistive technology.
     *
     * <p>Called on the UI thread inside the publish step, with this widget's own locale in scope,
     * and never from a paint. A widget that overrides nothing still gets a node with its bounds,
     * its enabled, focusable, visible and showing states, its language, its children in tree
     * order, the focus and scroll-into-view verbs when it is focusable, and its tooltip as a name
     * when nothing else supplied one. What it does <em>not</em> get is a role: a widget that
     * declares no role, no name, no description, no state, no verb and no facet is scaffolding,
     * and the tree removes it and hoists its children in its place, so that a screen reader hears
     * the controls rather than the boxes.
     *
     * <p><b>Nothing here may allocate.</b> The builder's setters take a facet's fields rather than
     * a facet, and a name is handed over as the localizable string this widget holds rather than
     * as a resolved one, so that a frame which damaged this widget and changed nothing about it
     * costs a comparison and no memory at all. {@link limn.accessibility.Accessibility} states
     * the two rules and what
     * breaks when they are ignored.
     *
     * @param a the node being described; write into it, and never keep it
     */
    protected void onAccessibility(limn.accessibility.Accessibility a) {
    }

    /**
     * Says who one of this widget's children <em>is</em>, before that child describes itself: the
     * {@linkplain limn.accessibility.Accessibility#key(long) identity key} a container that pools
     * its children owns and nothing else can, and, for a container that draws its rows, the
     * {@linkplain limn.accessibility.Accessibility#under(long) synthetic row} the child hangs
     * under.
     *
     * <p>Called on the UI thread before the child's node exists, so the only two things that may
     * be written here are the two above; a fact about the child &mdash; its role, its position,
     * a state &mdash; goes in {@link #onAccessibilityChild}, which runs after the child's own
     * hook. The split is what makes identity right (ADR 039 §1.3): the child's node is begun
     * under the identifier the key decides, so its name is carried over from the last frame
     * under that identifier, and every node it declares inside itself &mdash; a synthetic child,
     * a widget it holds &mdash; is scoped under it and follows the row when the child is recycled.
     *
     * @param child the child about to be described
     * @param a     the builder, open only for the two identity calls
     */
    protected void onAccessibilityChildIdentity(Widget child, limn.accessibility.Accessibility a) {
    }

    /**
     * Adds what only this widget knows about one of its children: the role a mounted list cell
     * takes, its position in the data, its selected state.
     *
     * <p>Called on the UI thread with the <em>child's</em> node current and this widget's locale
     * in scope, after the child has described itself, so what is written here wins. The child's
     * identity is not decided here but in {@link #onAccessibilityChildIdentity}, which runs
     * first; {@link limn.accessibility.Accessibility#key(long)} refuses to be called from here.
     *
     * <p><b>This is the only place {@link limn.accessibility.Accessibility#delegate} may be
     * called</b> (ADR 039 §1.5, amended 2026-09-14): a verb this widget claims on the child —
     * a list's {@code SELECT} on a row — is published on the child's node and routed to
     * {@link #onAccessibilityChildAction}. The builder refuses a delegation from anywhere else,
     * and refuses one here for a verb the child declared for itself, for a verb that takes an
     * argument, and for {@code FOCUS} or {@code SCROLL_INTO_VIEW} on a focusable child, which the
     * walk performs on its own. A verb <em>written</em> here with
     * {@link limn.accessibility.Accessibility#action} would be dispatched to the child's own
     * {@link #onAccessibilityAction}, which is why a child hook writes facts and delegates verbs.
     *
     * @param child the child being described
     * @param a     the child's node
     */
    protected void onAccessibilityChild(Widget child, limn.accessibility.Accessibility a) {
    }

    /**
     * Performs an action an assistive technology asked of this widget, as the user.
     *
     * <p>Called on the UI thread from a posted task, after the scene has re-checked that this
     * widget is still attached, still enabled with every ancestor, still showing and still
     * reachable. <b>The widget performs its own action</b>: it reaches its own private
     * from-the-user path with its own guards, so an assistive technology's toggle notifies the
     * application exactly as a click does and no component has to grow a public method that
     * re-derives a guard it already has.
     *
     * <p><b>The platform is not told the answer.</b> It was answered from the published snapshot
     * before this ran (ADR 039 §1.9, §1.5's amendment of 2026-09-14): the bridge accepts a verb
     * the node publishes and refuses one it does not, synchronously, and the post that reaches
     * here carries no reply back. So {@code false} here is honest bookkeeping and nothing more
     * &mdash; a refused {@code PRESS} raises no {@code INVOKED} &mdash; and the one contract a
     * reader can see is the published one: a node accepts exactly the parameterless verbs in its
     * action facet plus the setters its writable facets imply. A widget that would answer
     * {@code true} for a verb it did not publish is a defect the gallery ratchet in
     * {@code limn-demo} reports; a widget that accepts a synonym publishes it.
     *
     * @param action what was asked; one of the seventeen, including the four that carry an
     *               argument
     * @param arg    the argument, or {@link limn.accessibility.Accessible.Argument#NONE}
     * @return whether this widget did it. {@code false} is the honest answer for a verb it does
     *         not offer.
     */
    protected boolean onAccessibilityAction(limn.accessibility.Accessible.Action action,
                                            limn.accessibility.Accessible.Argument arg) {
        return false;
    }

    /**
     * Performs a verb this widget {@linkplain limn.accessibility.Accessibility#delegate claimed}
     * on one of its widget children: a list's {@code SELECT} on a row that is the application's
     * own cell, published on the row where a reader addresses it and performed by the list,
     * which is the only thing that knows what selecting that row means (ADR 039 §1.5, amended
     * 2026-09-14).
     *
     * <p>Called on the UI thread from a posted task, after the scene re-checked the child the
     * way it re-checks any node an action lands on, and that this widget is still its parent —
     * with one difference: the child need only be visible, and it is this widget that must be
     * showing, because this widget performs the verb and a cursor row kept outside its viewport
     * is not showing (ADR 039 §1.5, amended 2026-09-15).
     * The child is named twice on purpose: by the key this widget gave it in
     * {@link #onAccessibilityChildIdentity}, which is how a pooling container thinks of a row,
     * and by the widget itself, for a container that keys nothing. Every verb the child
     * declared for itself still reaches the child's own {@link #onAccessibilityAction}; only the
     * verbs this widget delegated arrive here.
     *
     * @param child  the child the verb was addressed to
     * @param key    the identity key this widget gave that child, or {@code 0} when it gave none
     * @param action what was asked
     * @param arg    the argument, or {@link limn.accessibility.Accessible.Argument#NONE}
     * @return whether this widget did it
     */
    protected boolean onAccessibilityChildAction(Widget child, long key,
                                                 limn.accessibility.Accessible.Action action,
                                                 limn.accessibility.Accessible.Argument arg) {
        return false;
    }

    /**
     * Performs an action addressed to one of this widget's synthetic children: a menu row, a combo
     * option, a chart series — something this widget draws and never instantiated as a widget.
     *
     * <p>Separate from {@link #onAccessibilityAction} deliberately, and not distinguished by a
     * sentinel key: a model index of zero is a legitimate key, so the two cannot share one hook
     * without the widget having to guess which of them it is in.
     *
     * @param key    the key this widget gave that child when it declared it
     * @param action what was asked
     * @param arg    the argument, or {@link limn.accessibility.Accessible.Argument#NONE}
     * @return whether this widget did it
     */
    protected boolean onSyntheticAction(long key, limn.accessibility.Accessible.Action action,
                                        limn.accessibility.Accessible.Argument arg) {
        return false;
    }

    /**
     * Whether this widget's picture is made partly of the pixels <b>behind</b> it: a wash, a
     * frost, a refraction. Default {@code false}.
     *
     * <p>It exists for one mode and is inert outside it. With partial rendering on, a frame
     * repaints only what was invalidated, and a widget like this can be stale without having
     * changed at all: nothing about it moved, but what it is made of did. ADR 019 &sect;6 recorded
     * that as the mode's known limit and named the fix; this is it. A widget that answers
     * {@code true} is registered with its scene, and its rectangle joins the damage of any frame
     * whose damage reaches it.
     *
     * <p><b>Reaching it is over-approximated on purpose.</b> What actually stales the picture is a
     * change <em>behind</em> it, and the test here is any intersection, front or back. Deciding
     * front from back means knowing paint order, which the damage list does not carry; the
     * over-approximation costs a repaint of something already being painted over and can never
     * miss one. That is the right way round for a correctness rule.
     *
     * <p>Answer it from the class rather than from state: it is read when the widget joins a
     * scene, not on every frame. A widget whose answer could change with a setter would have to
     * re-register, and nothing in this toolkit needs that &mdash; a backdrop panel is built with
     * an effect and never loses it.
     *
     * @return whether the pixels behind this widget are part of what it draws
     */
    protected boolean paintsFromBackdrop() {
        return false;
    }

    /**
     * Whether what {@link #onPaint} draws is material rather than meaning: a wash, a blur, a
     * shadow, a rule. Default {@code false}.
     *
     * <p>A widget that draws its own content and declares nothing is removed from the accessible
     * tree by the transparency rule, and the toolkit says so once per class — because the usual
     * cause is a gauge or a sparkline nobody named, and the interface it drew is then simply
     * absent with nothing said. That guard asks "does this class draw?", which reflection can
     * answer; it cannot ask whether the drawing means anything, and this widget is the only thing
     * that can. Overriding this to {@code true} is that answer, and the removal then happens in
     * silence.
     *
     * <p><b>It is not {@link #setAccessibleIgnored}, and the difference is the subtree.</b> Ignored
     * takes this widget's children out of the tree with it, which on a panel whose whole purpose
     * is to sit behind controls hides every control it wraps. Saying the painting is decoration
     * changes nothing about the tree at all: the node is removed exactly as it would have been,
     * the children hoist into its place, and only the warning goes away.
     *
     * <p>Answer it {@code true} only for a class whose drawing a reader loses nothing by never
     * hearing about. A widget that paints information is named or given a role instead; there is
     * no third answer that leaves the picture out and keeps the user informed.
     *
     * @return whether this widget's own drawing carries no information
     */
    protected boolean paintsDecoration() {
        return false;
    }

    /**
     * What an application declared about this widget for an assistive technology. {@code null}
     * until it declares something, which is the whole per-widget memory cost of the accessible
     * tree: one reference, never read on a widget nobody named.
     */
    private AccessibleOverrides accessibleOverrides;

    /** The application-set overrides, in one object so an unnamed widget carries one field. */
    private static final class AccessibleOverrides {
        Widget labelledBy;
        Widget describedBy;
        limn.i18n.I18nString name;
        limn.i18n.I18nString description;
        limn.accessibility.Accessible.Role role;
        boolean ignored;
    }

    private AccessibleOverrides overrides() {
        if (accessibleOverrides == null) {
            accessibleOverrides = new AccessibleOverrides();
        }
        return accessibleOverrides;
    }

    /**
     * Names this widget from another widget's text, the way a form's caption names the field it
     * sits beside. UI thread only.
     *
     * <p><b>One at a time, and declared rather than inferred.</b> A caption that merely sits near
     * a field does not name it: proximity is a layout accident, and a tree built from it says
     * confident wrong things. So the link is written, it replaces any previous one, and passing
     * {@code null} removes it. The named node carries a {@link
     * limn.accessibility.Accessible.Relation#LABELLED_BY} relation to the label, so a client that
     * would rather read the label's own node than a copied string can walk to it.
     *
     * <p>The text is read from the label on every publish rather than copied here, so a caption
     * that changes its string, or is re-resolved into another language, renames what it labels
     * with nothing to keep in step. A widget that has no text to offer names nothing, and the
     * relation still stands.
     *
     * <p>An explicit {@link #setAccessibleName(limn.i18n.I18nString)} still wins over this, and
     * this wins over whatever the widget would have derived for itself.
     *
     * @param label the widget whose text names this one, or {@code null} to remove the link
     * @see limn.components.Label#setLabelFor(Widget)
     */
    public final void setAccessibleLabelledBy(Widget label) {
        Ui.checkUiThread();
        overrides().labelledBy = label;
        invalidateAccessible();
    }

    /**
     * The text this widget offers when it is named as another's label, or {@code null} when it is
     * not the kind of widget that captions anything.
     *
     * <p>Overridden by the widgets that are captions in their own right; everything else declines,
     * which is why a link to one names nothing rather than inventing a string from its children.
     *
     * @return the caption text, or {@code null}
     */
    protected limn.i18n.I18nString accessibleLabelText() {
        return null;
    }

    /** The widget whose text names this one, or {@code null}. Read by the publish step. */
    final Widget accessibleLabelledBy() {
        return accessibleOverrides == null ? null : accessibleOverrides.labelledBy;
    }

    /**
     * The widget a label bound to this one names: this widget, unless it is a composite whose
     * keyboard lands on an inner control, in which case that control (ADR 039 §1.5, amended
     * 2026-09-14; decision 55).
     *
     * <p>A form's caption is bound to the widget the application holds &mdash; a date picker
     * &mdash; and what a reader arrives at is the field inside it. Naming the group would put
     * the caption on a node the keyboard never lands on and leave the focused field nameless. So
     * a composite answers the child that should carry the caption, and the publish step names
     * that child from the label with {@code NameFrom.LABEL}, gives it the {@code LABELLED_BY}
     * relation, resolves the label's own {@code LABEL_FOR} to it, and leaves this widget's node
     * without either &mdash; a group that declares nothing else is then transparent. The answer
     * may redirect again (a composite inside a composite) and it must be a descendant of this
     * widget: anything else is refused by the walk, loudly, because a caption that lands on a
     * stranger is exactly the confidently wrong name §11 refuses to infer.
     *
     * <p>Called on the UI thread by the publish step, only while a label is bound to this
     * widget. An explicit {@link #setAccessibleName(limn.i18n.I18nString)} on this widget is
     * unaffected: it names this node, as it always did. A widget that answers {@code null}
     * keeps the label itself.
     *
     * @return the widget that carries a label bound to this one; {@code this} by default
     * @see limn.components.Label#setLabelFor(Widget)
     */
    protected Widget accessibleLabelTarget() {
        return this;
    }

    /**
     * Describes this widget from another widget's text, the way the message beneath a field says
     * why the field is invalid. UI thread only.
     *
     * <p>The same rule as {@link #setAccessibleLabelledBy}: declared and never inferred, one at a
     * time, {@code null} removes it, and the text is read from the source on every publish so a
     * message that changes its string describes with nothing to keep in step. The described node
     * carries a {@link limn.accessibility.Accessible.Relation#DESCRIBED_BY} relation to the
     * source, so a client may read either.
     *
     * <p>A bound description beats the tooltip the walk would otherwise fall back on, and an
     * explicit {@link #setAccessibleDescription(limn.i18n.I18nString)} beats both.
     *
     * @param source the widget whose text describes this one, or {@code null} to remove the link
     * @see limn.components.Label#setDescriptionFor(Widget)
     */
    public final void setAccessibleDescribedBy(Widget source) {
        Ui.checkUiThread();
        overrides().describedBy = source;
        invalidateAccessible();
    }

    /** The widget whose text describes this one, or {@code null}. Read by the publish step. */
    final Widget accessibleDescribedBy() {
        return accessibleOverrides == null ? null : accessibleOverrides.describedBy;
    }

    /**
     * Names this widget for an assistive technology, overriding whatever it would have derived for
     * itself. UI thread only.
     *
     * <p>An application-set name always wins, so renaming a component never requires subclassing
     * it. It is resolved under this widget's own locale, like every other name in the tree.
     *
     * @param name the name, or {@code null} to let the widget name itself again
     */
    public final void setAccessibleName(limn.i18n.I18nString name) {
        Ui.checkUiThread();
        overrides().name = name;
        invalidateAccessible();
    }

    /**
     * Names this widget with a fixed string. UI thread only.
     *
     * @param name the name, or {@code null} to let the widget name itself again
     */
    public final void setAccessibleName(String name) {
        setAccessibleName(name == null ? null : limn.i18n.I18nString.literal(name));
    }

    /**
     * Describes this widget at more length than its name does, for an assistive technology.
     * UI thread only.
     *
     * @param text the description, or {@code null} to let the widget describe itself again
     */
    public final void setAccessibleDescription(limn.i18n.I18nString text) {
        Ui.checkUiThread();
        overrides().description = text;
        invalidateAccessible();
    }

    /**
     * Describes this widget with a fixed string. UI thread only.
     *
     * @param text the description, or {@code null} to let the widget describe itself again
     */
    public final void setAccessibleDescription(String text) {
        setAccessibleDescription(text == null ? null : limn.i18n.I18nString.literal(text));
    }

    /**
     * Says what this widget is, overriding whatever role it would have declared. UI thread only.
     *
     * <p>This is also how a custom widget that paints its own content joins the tree at all: a
     * widget that declares no role is scaffolding and is removed, and the toolkit warns once per
     * class when the class it removes paints something.
     *
     * @param role the role, or {@code null} to let the widget declare its own
     */
    public final void setAccessibleRole(limn.accessibility.Accessible.Role role) {
        Ui.checkUiThread();
        overrides().role = role;
        invalidateAccessible();
    }

    /**
     * Removes this widget and its subtree from the accessible tree: a spacer, a decorative rule,
     * an image that repeats what the text beside it already says. UI thread only.
     *
     * <p>Not for a control that can be operated. Marking one of those decorative is not a
     * deferral, it is hiding it from the one user who cannot see it.
     *
     * @param ignored whether to leave this widget out of the tree entirely
     */
    public final void setAccessibleIgnored(boolean ignored) {
        Ui.checkUiThread();
        overrides().ignored = ignored;
        invalidateAccessible();
    }

    /**
     * Says that something about this widget an assistive technology would care about has changed,
     * when nothing repainted and nothing moved.
     *
     * <p>Almost nothing needs this. The accessible tree is rebuilt from the funnel every repaint
     * goes through, so a value, a state, a caret and a selection all reach it already. What does
     * not is a change that paints nothing at all — an application writing a field behind a
     * setter's back, or setting one of the four accessibility overrides above, all of which call
     * this themselves.
     *
     * <p>It sets the flag whether or not anything is listening, so switching a bridge on
     * mid-session needs no audit of what was missed, and that costs one store. It buys the frame
     * that reads the flag only while something <em>is</em> listening, because a flag set with no
     * frame coming is a no-op with a comforting name.
     */
    public final void invalidateAccessible() {
        if (scene != null) {
            scene.invalidateAccessible();
        }
    }

    /** The application's name override, or {@code null}. Read by the publish step. */
    final limn.i18n.I18nString accessibleName() {
        return accessibleOverrides == null ? null : accessibleOverrides.name;
    }

    /** The application's description override, or {@code null}. Read by the publish step. */
    final limn.i18n.I18nString accessibleDescription() {
        return accessibleOverrides == null ? null : accessibleOverrides.description;
    }

    /** The application's role override, or {@code null}. Read by the publish step. */
    final limn.accessibility.Accessible.Role accessibleRole() {
        return accessibleOverrides == null ? null : accessibleOverrides.role;
    }

    /** Whether the application struck this widget out of the tree. Read by the publish step. */
    final boolean isAccessibleIgnored() {
        return accessibleOverrides != null && accessibleOverrides.ignored;
    }

    /** Called when this widget takes keyboard focus. Default: nothing. */
    protected void onFocusGained() {
    }

    /**
     * How the focus this widget is being given arrived: {@code true} for Tab or Shift+Tab,
     * {@code false} for a click, a {@link #requestFocus()} from code, or focus restored when an
     * overlay closed.
     *
     * <p>The distinction exists because several desktop conventions apply to one and not the
     * other: a single-line field selects its contents when tabbed into, so the next keystroke
     * replaces them, and must not when clicked into, where the click placed a caret the user
     * chose.
     *
     * <p><b>Only meaningful inside {@link #onFocusGained()}.</b> Asked at any other time it
     * answers {@code false}, because the flag is set for exactly the duration of the traversal
     * that raised it. A widget that stored the answer to consult later would be reading the last
     * traversal in the scene, not its own.
     *
     * @return whether Tab brought the focus here
     */
    protected final boolean focusArrivedByTraversal() {
        return scene != null && scene.focusCameFromTraversal();
    }

    /**
     * Whether the focus this widget is being given arrived by Shift+Tab, walking backward: a
     * widget with more than one stop of its own (a table's header and its rows) enters at its
     * last stop then, and at its first otherwise, so that the two directions mirror. {@code false}
     * for a forward Tab and for every arrival that is not a traversal. Only meaningful inside
     * {@link #onFocusGained()}, as {@link #focusArrivedByTraversal()} is.
     *
     * @return whether Shift+Tab brought the focus here
     */
    protected final boolean focusArrivedBackward() {
        return scene != null && scene.focusTraversalWentBackward();
    }

    /** Called when this widget loses keyboard focus. Default: nothing. */
    protected void onFocusLost() {
    }

    // Package-private dispatch bridges for Scene. Each holds this widget's locale in
    // scope while the handler runs, so a handler that formats or resolves text (a
    // spinner committing a typed value, a menu built on right-click) answers in this
    // subtree's language, exactly as the measure and paint that will draw it do.
    final void dispatchMouse(MouseEvent event) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            onMouseEvent(event);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    final void dispatchKey(KeyEvent event) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            onKeyEvent(event);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    final void dispatchChar(CharEvent event) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            onCharTyped(event);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    final void dispatchPreedit(PreeditEvent event) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            onPreedit(event);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    // Package bridges so Scene (same package) reads these without widening them.
    final boolean acceptsTextInputInternal() {
        return acceptsTextInput();
    }

    final limn.graphics.Rect caretRectInternal() {
        return caretRect();
    }

    final void dispatchFileDrop(FileDropEvent event) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            onFileDrop(event);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    // The accessibility hooks reach application code exactly as the input ones do, with this
    // widget's locale in scope: a name resolved here answers in this subtree's language, and the
    // publish step runs outside any pass that would have put it there.
    final void describeAccessible(limn.accessibility.Accessibility a) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            onAccessibility(a);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    final void describeAccessibleChildIdentity(Widget child, limn.accessibility.Accessibility a) {
        // Nothing here resolves a string, so no language is pushed: the hook answers a key and a
        // row, which are numbers, and the child's own describe pass has not begun.
        onAccessibilityChildIdentity(child, a);
    }

    final void describeAccessibleChild(Widget child, limn.accessibility.Accessibility a) {
        // The CHILD's language and not this widget's, because everything the hook writes lands in
        // the child's slot and the builder stamps every name it resolves with that slot's locale.
        // Pushing the parent's resolved a name in one language and published it as being in
        // another, and the two never met again: a name is carried over from the last frame while
        // its slot's locale and the translation epoch both hold, so the mismatched node kept a
        // caption its own declared language says it is not in, until something unrelated moved
        // the epoch. This is the only hook that writes into a slot it does not own.
        Locale enclosing = I18n.pushScope(child.locale());
        try {
            onAccessibilityChild(child, a);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    final boolean performAccessibleAction(limn.accessibility.Accessible.Action action,
                                          limn.accessibility.Accessible.Argument arg) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            return onAccessibilityAction(action, arg);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    final boolean performSyntheticAction(long key, limn.accessibility.Accessible.Action action,
                                         limn.accessibility.Accessible.Argument arg) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            return onSyntheticAction(key, action, arg);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    final boolean performChildAction(Widget child, long key,
                                     limn.accessibility.Accessible.Action action,
                                     limn.accessibility.Accessible.Argument arg) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            return onAccessibilityChildAction(child, key, action, arg);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /** Whether this class or any between it and {@link Widget} declares {@link #onPaint}. */
    final boolean paintsItself() {
        return PAINTS.get(getClass());
    }

    /**
     * Whether a class draws its own content, computed once per class.
     *
     * <p>Only the transparency verdict asks, and only on the path where it is about to delete a
     * node. A widget that paints something and declares nothing is a picture with nothing said
     * about it, which is worth a warning and is not worth a node: only the application has a name
     * for it.
     */
    private static final ClassValue<Boolean> PAINTS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> at = type; at != null && at != Widget.class; at = at.getSuperclass()) {
                try {
                    at.getDeclaredMethod("onPaint", Canvas.class);
                    return Boolean.TRUE;
                } catch (NoSuchMethodException absent) {
                    // Not this class's; keep walking up to Widget, which declares the empty one.
                }
            }
            return Boolean.FALSE;
        }
    };

    /**
     * The scene's focus funnel telling this widget it gained or lost focus: the subclass hook
     * first, with this widget's locale in scope, and then the announcement, so a watcher reads a
     * widget whose own reaction has already run.
     */
    final void notifyFocus(boolean gained, Change.Origin origin) {
        Locale enclosing = I18n.pushScope(locale());
        try {
            if (gained) {
                onFocusGained();
            } else {
                onFocusLost();
            }
        } finally {
            I18n.popScope(enclosing);
        }
        notifyChange(Change.of(Change.Aspect.FOCUS, origin));
    }
}

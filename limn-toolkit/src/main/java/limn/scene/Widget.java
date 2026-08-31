package limn.scene;

import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.scene.event.CharEvent;
import limn.scene.event.FileDropEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.event.PreeditEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * rendering} enabled then repaints only the damaged region, one without it
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

    /** The step {@link #lastSize} was measured at, part of {@link #measure}'s cache key. */
    private ControlSize measuredControlSize;

    /** Invalidates every memo in the process. O(1). Package-private: the five writers only. */
    static void bumpControlSizeEpoch() {
        controlSizeEpoch++;
    }

    // --------------------------------------------------- layout direction axis
    // The same shape as the size axis above, deliberately: read LayoutDirection's
    // resolution contract before touching any of this, and keep the two apart.

    /**
     * Global validity stamp for every widget's {@link #layoutDirection()} memo, and a
     * <b>separate counter</b> from {@link #controlSizeEpoch} on purpose. Merging them is the
     * obvious simplification and a wrong one: a theme change that bumps the size epoch would
     * then re-shape every string in the process for a direction that did not move, and a
     * repository with one counter cannot say which axis a re-measure was for.
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

    /**
     * The direction {@link #lastSize} was measured in, part of {@link #measure}'s cache key.
     * A line of mixed content measures a fraction of a point differently in the two directions,
     * so a size cache that cannot see the axis returns a stale answer across a change.
     */
    private LayoutDirection measuredLayoutDirection;

    /** Invalidates every direction memo in the process. O(1). Package-private: the writers only. */
    static void bumpLayoutDirectionEpoch() {
        layoutDirectionEpoch++;
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

    /** Appends a child (UI thread only). */
    public void add(Widget child) {
        Ui.checkUiThread();
        Objects.requireNonNull(child, "child");
        if (child.parent != null) {
            throw new IllegalStateException("widget already has a parent");
        }
        for (Widget ancestor = this; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor == child) {
                throw new IllegalArgumentException("cycle: child is an ancestor of this widget");
            }
        }
        children.add(child);
        child.parent = this;
        child.setSceneRecursively(scene);
        markNeedsLayout();
    }

    /** Removes a child (UI thread only). */
    public void remove(Widget child) {
        Ui.checkUiThread();
        if (children.remove(child)) {
            child.parent = null;
            child.setSceneRecursively(null);
            if (scene != null) {
                scene.onWidgetDetached(child);
            }
            markNeedsLayout();
        }
    }

    /**
     * The single funnel through which a subtree's scene is written: {@link #add},
     * {@link #remove}, {@link Scene}'s constructor, {@code Scene.pushOverlay} and
     * {@code Scene.removeOverlay}. Bumps both inherited axes' epochs once, up front:
     * {@code pushOverlay}/{@code removeOverlay} move a whole <b>parentless</b> subtree
     * between scenes without touching any parent field, so a widget that resolved and
     * memoized before being pushed into a scene with a different default would otherwise
     * keep a stale answer forever. Bumping here rather than in {@code add}/{@code remove}
     * is what makes the memo provably exact rather than hopefully exact.
     */
    final void setSceneRecursively(Scene newScene) {
        bumpControlSizeEpoch();
        bumpLayoutDirectionEpoch();
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
            this.scene = null;
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
        for (Widget ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor instanceof Scrollable scrollable) {
                float rectX = 0;
                float rectY = 0;
                for (Widget w = this; w != ancestor; w = w.parent) {
                    rectX += w.x;
                    rectY += w.y;
                }
                // Points, not a scale factor: the walk above sums offsets only, so one
                // inflation is correct in every ancestor's coordinates.
                scrollable.revealRect(rectX - outset, rectY - outset,
                        width + 2 * outset, height + 2 * outset);
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
        // Clip walk: intersect these bounds with every clipping ancestor's box.
        float x0 = 0;
        float y0 = 0;
        float x1 = width;
        float y1 = height;
        for (Widget node = this; node != null; node = node.parent) {
            if (node != this && node.clipsChildren()) {
                x0 = Math.max(x0, 0);
                y0 = Math.max(y0, 0);
                x1 = Math.min(x1, node.width);
                y1 = Math.min(y1, node.height);
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
     * Shows or hides this widget and its subtree, re-running layout so siblings take
     * the space back. Hiding revokes focus, hover and any press inside the subtree.
     * UI thread only.
     */
    public void setVisible(boolean visible) {
        Ui.checkUiThread();
        if (this.visible != visible) {
            this.visible = visible;
            if (!visible && scene != null) {
                scene.onWidgetDetached(this); // revoke focus/hover/press in this subtree
            }
            markNeedsLayout();
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
     */
    public void setEnabled(boolean enabled) {
        Ui.checkUiThread();
        if (this.enabled != enabled) {
            this.enabled = enabled;
            if (!enabled && scene != null) {
                scene.onWidgetDetached(this); // revoke focus/hover/press in this subtree
            }
            invalidate();
        }
    }

    /** Whether keyboard focus can land here: false for containers and static chrome. */
    public final boolean isFocusable() {
        return focusable;
    }

    /**
     * Declares whether this widget can take keyboard focus. Does not move focus away
     * if it currently holds it. UI thread only.
     */
    public final void setFocusable(boolean focusable) {
        Ui.checkUiThread();
        this.focusable = focusable;
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
     *         Resolved by walking up from the hovered leaf (like {@link #cursor}).
     */
    public String tooltip() {
        return tooltip == null ? null : tooltip.get();
    }

    /** Sets the hover tooltip text ({@code null} clears it). UI thread only. */
    public void setTooltip(String text) {
        setTooltip(text == null ? null : limn.i18n.I18nString.literal(text));
    }

    /** Sets a tooltip that follows the UI language ({@code null} clears it). UI thread only. */
    public void setTooltip(limn.i18n.I18nString text) {
        Ui.checkUiThread();
        this.tooltip = text;
    }

    /** Whether this widget currently holds its scene's keyboard focus. */
    public final boolean isFocused() {
        return scene != null && scene.focusedWidget() == this;
    }

    /** Asks the scene to move keyboard focus here (UI thread only). */
    public final void requestFocus() {
        Ui.checkUiThread();
        if (scene != null) {
            scene.requestFocus(this);
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
     * Links this widget's size inheritance to {@code host} for the case the tree cannot
     * express: a widget that is the root of its own {@link Scene} (a popup or dialog window)
     * or a {@linkplain Scene#pushOverlay overlay}, both of which have no parent. The chain
     * then continues from {@code host}, <b>live</b>, so a later change on the host reaches
     * the popup while it is open, which explicit forwarding could not do (and which would
     * additionally convert an inherited value into a declared one, pinning the popup if the
     * process default changed underneath it). {@code null} unlinks. UI thread only.
     *
     * <p>Consulted <em>after</em> this widget's own scene default, so a popup scene that
     * declares a step keeps it and one that declares nothing falls through to its host.
     *
     * <p><b>Install it before anything sizes the surface.</b> A native popup or dialog
     * measures its content to size its window <em>before</em> binding a scene; installing
     * the host after that sizes the window at the process default and then re-measures the
     * content at the owner's step inside a wrongly-sized window.
     *
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
        markNeedsLayout();
    }

    /**
     * @deprecated the link was never about size. It says "this parentless panel belongs to that
     *         widget", which every inherited axis needs and none of them owns, so it is named
     *         {@link #setInheritanceHost} and this delegates. A second link that could name a
     *         different widget per axis would be a bug with no honest resolution.
     */
    @Deprecated
    public final void setControlSizeHost(Widget host) {
        setInheritanceHost(host);
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
     * {@link #markNeedsLayout()}, and the cache key includes the <b>resolved control
     * size</b> and the <b>resolved layout direction</b>, so a container's change on either axis
     * re-measures exactly the descendants whose resolved value actually changed and leaves
     * overriding subtrees on their caches. That is why no deep-invalidation API is needed for
     * either axis. Subclasses implement {@link #onMeasure}.
     *
     * <p>The direction belongs in the key because a line of mixed content genuinely measures a
     * fraction of a point differently in the two directions: the paragraph direction decides
     * which bidi level a boundary neutral takes, which decides which run it extends, which
     * decides which face measures it. A cache that cannot see the axis returns a stale size.
     *
     * <p>Correctness is a property of the key: the only way this can return a stale size is
     * if the resolved step <em>and</em> the resolved direction <em>and</em> the constraints
     * <em>and</em> {@code needsMeasure} all say nothing changed, in which case nothing did.
     *
     * <p>A third axis wanting into this key would be the smell: the right move then is a single
     * resolved-axes value rather than a fourth field, and it should be noticed the first time.
     */
    public final Size measure(Constraints constraints) {
        ControlSize step = controlSize();
        LayoutDirection direction = layoutDirection();
        if (!needsMeasure
                && step == measuredControlSize
                && direction == measuredLayoutDirection
                && constraints.equals(lastConstraints)) {
            return lastSize;
        }
        lastSize = Objects.requireNonNull(onMeasure(constraints), "onMeasure returned null");
        lastConstraints = constraints;
        measuredControlSize = step;
        measuredLayoutDirection = direction;
        needsMeasure = false;
        return lastSize;
    }

    /**
     * Reports the size this widget wants within {@code constraints}. Called once per
     * layout pass, and the result is cached against the constraints, the resolved size step
     * and the resolved layout direction, so it must be a pure function of them and of this
     * widget's own state.
     *
     * <p>Resolve the {@link ControlSize} and the {@link LayoutDirection} once each here and
     * thread them down; never read either in a constructor.
     */
    protected abstract Size onMeasure(Constraints constraints);

    /** Parent assigns final bounds (parent coords); then {@link #onLayout()} places children. */
    public final void layoutBox(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        onLayout();
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
     * {@linkplain Scene#setPartialRendering(boolean) partial rendering} enabled
     * repaints only the changed region. A widget whose painting can extend
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
        if (scene != null) {
            scene.metrics().countPaintedWidget();
        }
        int depth = canvas.saveCount();
        boolean completed = false;
        try {
            onPaint(canvas);
            paintChildren(canvas);
            onPaintOverlay(canvas);
            completed = true;
        } finally {
            int leaked = canvas.saveCount() - depth;
            if (leaked != 0) {
                if (completed) {
                    reportUnbalancedPaint(leaked);
                }
                canvas.restoreToCount(depth);
            }
        }
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
     * override that clips in {@code paintChildren} should also override this.
     */
    protected boolean clipsChildren() {
        return false;
    }

    /** Children pass; override to clip (e.g. scroll views). */
    protected void paintChildren(Canvas canvas) {
        for (Widget child : children) {
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

    /** Called when this widget loses keyboard focus. Default: nothing. */
    protected void onFocusLost() {
    }

    // package-private dispatch bridges for Scene
    final void dispatchMouse(MouseEvent event) {
        onMouseEvent(event);
    }

    final void dispatchKey(KeyEvent event) {
        onKeyEvent(event);
    }

    final void dispatchChar(CharEvent event) {
        onCharTyped(event);
    }

    final void dispatchPreedit(PreeditEvent event) {
        onPreedit(event);
    }

    // Package bridges so Scene (same package) reads these without widening them.
    final boolean acceptsTextInputInternal() {
        return acceptsTextInput();
    }

    final limn.graphics.Rect caretRectInternal() {
        return caretRect();
    }

    final void dispatchFileDrop(FileDropEvent event) {
        onFileDrop(event);
    }

    final void notifyFocus(boolean gained) {
        if (gained) {
            onFocusGained();
        } else {
            onFocusLost();
        }
    }
}

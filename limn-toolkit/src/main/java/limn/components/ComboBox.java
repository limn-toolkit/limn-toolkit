package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.a11y.PopupOwnerAccessibility;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.a11y.RowsAccessibility;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import limn.concurrent.Subscription;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.graphics.Path2D;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.lang.Checks;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Change;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Dropdown selector. The popup is a real native window: undecorated,
 * floating, non-focus-stealing and with a <em>transparent framebuffer</em>,
 * so its rounded corners (and the translucent panel behind them) composite
 * over whatever is on screen, exactly like the rest of the theme. It can
 * overflow the parent window like a native menu.
 *
 * <p>Keyboard: Space/Enter/Down opens; Up/Down move the highlight; Enter
 * commits; Esc closes. The popup never takes focus; the combo keeps it, so
 * clicking anywhere else in the parent window closes the popup via focus
 * loss. Headless scenes (tests) keep the same open/highlight/commit state
 * machine without a native window.
 *
 * <p><b>In-scene fallback.</b> Where the window reports no
 * {@link limn.backend.NativeWindow#supportsAbsolutePositioning()} (Wayland), a list in a window
 * of its own would open somewhere other than under the field, so the list is drawn as an overlay
 * inside the owner window instead. Same rows, same keys, same type-ahead; the difference is that
 * it is clamped to that window rather than to the display, so a combo near the bottom edge shows
 * fewer rows and scrolls sooner. Chosen by the platform, not by the application, and
 * {@link #popupWindow()} is {@code null} throughout.
 *
 * <p>Field and dropdown share one {@link limn.scene.ControlSize} step: the panel is the root
 * of its own window-bound {@link Scene}, so it inherits through
 * {@link Widget#setInheritanceHost} rather than through the tree. A dropdown at a different
 * density from the field that opened it is simply a bug, so there is no setter on the panel.
 * The in-scene presentation carries the same link on the overlay layer, which is the parentless
 * widget there — the panel's own would be ignored, because a host link on a widget that has a
 * parent loses to the tree.
 *
 * <p>The {@link LayoutDirection} arrives by that same link and for that same reason: a list
 * whose rows read the other way from the field that opened them is a bug rather than a
 * configuration. Reading right to left the label sits against the right edge, the chevron takes
 * the gutter on the left, the list hangs from the field's right edge and the marker column, the
 * row labels and the scrollbar all move to the other side. The chevron itself does not turn
 * over: it points up and down.
 */
public class ComboBox extends Widget {

    /** Breathing room kept from the work-area edge when clamping the popup. */
    private static final float EDGE_MARGIN = 8;

    private final List<I18nString> items;
    private int selectedIndex;
    private int highlightedIndex;

    /** How long a pause splits one type-ahead word from the next. */
    private static final long TYPE_AHEAD_RESET_NANOS = 1_000_000_000L;

    private final StringBuilder typeAhead = new StringBuilder();
    private long lastTypedNanos;
    private boolean open;
    private final Transition hover =
            new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);
    private IntConsumer onSelect;

    /** Reused each paint; the caret's 3 points are recomputed from the size. */
    private final Path2D caret = new Path2D();

    private NativeWindow popupWindow;
    private Scene popupScene;
    private PopupPanel popupPanel;
    /**
     * The in-scene presentation, non-null instead of {@link #popupWindow} on a platform that
     * cannot place a window. Exactly one of the two is ever set while the popup is open.
     */
    private ScenePopup scenePopup;
    /** What the application asked for. The platform may override it towards IN_SCENE, never away. */
    private DisplayMode requested = DisplayMode.NATIVE_WINDOW;
    /** Overlay fade, 0..1: the in-scene twin of the popup window's compositor fade. */
    private float sceneFade;
    /**
     * The step the open popup's window was <em>sized</em> at. The window is sized once, at
     * creation, from {@code popupItemHeight}/{@code popupPadV}; a step change while it is open
     * would re-measure the rows inside a stale window and clip the last one, so the popup
     * closes instead and reopens at the new step.
     *
     * <p>The in-scene presentation is laid out on every pass and so re-fits by itself; the
     * close-and-reopen is guarded on there being a window, not on this field.
     */
    private ControlSize popupStep;
    /**
     * The direction the open popup <em>window</em> was last laid out for, resolved when the
     * window was created and again whenever this field re-measures.
     *
     * <p>Not a size key and not a reason to close: a flip changes neither the window's width
     * nor its height (the pad is on both sides and the caret gutter is a magnitude), so the
     * panel re-lays out and repaints in place. It is held at all because the panel is the root
     * of <em>another</em> scene, reachable only through the host link: bumping the direction
     * epoch in this tree damages nothing over there, and the list would keep painting
     * yesterday's side until something else touched that window.
     *
     * <p>Unused by the in-scene presentation, which is an overlay in the owner's own scene and
     * so is laid out and repainted by the pass that already noticed the change.
     */
    private boolean popupRtl;
    /** Unregisters the outside-press dismiss observer while the popup is open. */
    private Subscription dismissHandle;
    private Subscription blurHandle;
    private Subscription popupBlurHandle;

    /** A combo over fixed labels; see {@link #localized} for items that follow the UI language. */
    public ComboBox(List<String> items) {
        this(items.stream().map(I18nString::literal).toArray(I18nString[]::new));
    }

    /**
     * A combo whose items follow the UI language: a theme or unit picker, as opposed
     * to a list of file names. The items are held as values, so a language change
     * re-measures the popup instead of leaving yesterday's words in it.
     */
    public static ComboBox localized(List<I18nString> items) {
        return new ComboBox(items.toArray(I18nString[]::new));
    }

    /** An array, only because the two {@code List} constructors would erase to one. */
    private ComboBox(I18nString[] items) {
        if (items.length == 0) {
            throw new IllegalArgumentException("ComboBox needs at least one item");
        }
        this.items = List.of(items);
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    // ------------------------------------------------------------------- API

    /**
     * The application's response to the user picking an item: a click on a row, Enter on the
     * highlight, an assistive technology's select. Never for {@link #setSelectedIndex}, which is
     * a caller's write; to hear every change whatever caused it, {@linkplain #observeChanges
     * watch} the combo instead.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this combo
     * @throws IllegalStateException if a handler is already registered
     */
    public ComboBox onSelect(IntConsumer listener) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, listener, "ComboBox.onSelect");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.SELECTION) {
            if (onSelect != null) {
                onSelect.accept(selectedIndex);
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    /**
     * The selected item's index; never negative, since a combo refuses an empty item list and so
     * always has exactly one selection. There is nothing to clear to.
     */
    public int selectedIndex() {
        return selectedIndex;
    }

    /** The selected item as it currently reads, in the UI language. */
    public String selectedItem() {
        return items.get(selectedIndex).get();
    }

    /**
     * Selects an item: a caller's write, so it announces {@code SELECTION}/{@code CODE} and
     * reaches no handler. Selecting what is already selected changes nothing and announces
     * nothing. UI thread only.
     *
     * @param index an item in {@code [0, itemCount)}
     * @throws IndexOutOfBoundsException if {@code index} is not an item; an index computed from a
     *         lookup that missed is a caller's bug here, not a request for the nearest item
     */
    public ComboBox setSelectedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, items.size());
        select(index, Change.Origin.CODE);
        return this;
    }

    /**
     * The one seam every selection goes through: the public setter passes {@code CODE}, and a
     * pick from the popup, the Enter/Space path and an assistive technology's select pass
     * {@code USER} through {@link #commit}. Announces only when the index moved.
     */
    private void select(int index, Change.Origin origin) {
        if (index == selectedIndex) {
            return;
        }
        selectedIndex = index;
        invalidate();
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /** Whether the popup is showing. */
    public boolean isOpen() {
        return open;
    }

    /** @return the highlighted popup row (keyboard navigation state) */
    public int highlightedIndex() {
        return highlightedIndex;
    }

    /**
     * @return the popup's native window while open (screenshots/tests), else {@code null},
     *         which is also the answer while an open popup is drawn
     *         {@linkplain ComboBox in the scene}, where there is no window to return
     */
    public NativeWindow popupWindow() {
        return popupWindow;
    }

    // ------------------------------------------------------------ open/close

    /**
     * Opens the popup (native window when bound; pure state when headless). Announces
     * {@code EXPANDED}/{@code CODE}; the click, the keys and an assistive technology's expand
     * announce it as {@code USER} through the same seam.
     */
    public void open() {
        open(Change.Origin.CODE);
    }

    private void open(Change.Origin origin) {
        Ui.checkUiThread();
        if (open) {
            return;
        }
        open = true;
        boolean highlightMoved = highlightedIndex != selectedIndex;
        highlightedIndex = selectedIndex;
        typeAhead.setLength(0); // a new list is a new word, however recently the last one was typed
        invalidate();
        if (highlightMoved) {
            // The highlight snapping back to the selection is a consequence of opening.
            notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.ADJUSTMENT));
        }
        Scene scene = scene();
        if (scene == null) {
            notifyChange(Change.of(Change.Aspect.EXPANDED, origin));
            return;
        }
        // A press in another window/app never reaches observePresses: dismiss on OS focus loss
        // instead, or the always-on-top popup floats over foreign applications indefinitely.
        // In-scene the same rule reads as "the application went away", which closes it too.
        blurHandle = scene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        if (requested == DisplayMode.IN_SCENE
                || (scene.window() != null && !scene.window().supportsAbsolutePositioning())) {
            presentInScene();
            return;
        }
        // Any press elsewhere in the parent window dismisses the popup, even on non-focusable
        // widgets, which never move focus. Native only: an overlay captures every press in the
        // scene, including its own list's, so this observer would close the popup on the press
        // that was choosing an item.
        dismissHandle = scene.observePresses(target -> {
            if (target != this) {
                close(Change.Origin.USER);
            }
        });
        if (scene.window() != null && popupWindow == null) {
            // Defer: creating a window switches the GL context, which must
            // not happen in the middle of another window's frame callback.
            Ui.post(this::createPopupWindow);
        }
    }

    /**
     * @return whether the open popup is drawn in the owner scene; {@link #popupWindow()} is
     *         {@code null} both here and headless, so it cannot tell the two apart
     */
    boolean isInSceneForTest() {
        return scenePopup != null;
    }

    /**
     * Asks for a presentation of the drop-down list; see {@link DisplayMode}. Default
     * {@link DisplayMode#NATIVE_WINDOW}, so a long list can extend past the window's edge the way
     * a platform combo's does.
     *
     * <p><b>A preference, not a guarantee.</b> A list asked for {@code NATIVE_WINDOW} is still
     * drawn in scene on a window that cannot be positioned at an anchor (Wayland). {@code IN_SCENE}
     * is always honoured, and it is the choice to make when the window has to contain everything
     * it shows: a screenshot, a recording or a screen share of the owner window captures an
     * in-scene list and never a native one.
     *
     * <p>Takes effect on the next open.
     */
    public ComboBox setDisplayMode(DisplayMode mode) {
        this.requested = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * @return how the list is presented: while it is open, what it actually chose, including a
     *         platform forcing {@code IN_SCENE}. While it is closed, what was last asked for.
     */
    public DisplayMode displayMode() {
        return scenePopup != null ? DisplayMode.IN_SCENE : requested;
    }

    /**
     * Dismisses the popup without changing the selection. No-op when closed. Announces
     * {@code EXPANDED}/{@code CODE}; a dismissal by the user is {@code USER}, and one the widget
     * makes for itself -- the window losing focus, the field being detached -- is an adjustment.
     */
    public void close() {
        close(Change.Origin.CODE);
    }

    private void close(Change.Origin origin) {
        Ui.checkUiThread();
        if (!open) {
            return;
        }
        open = false;
        invalidate();
        notifyChange(Change.of(Change.Aspect.EXPANDED, origin));
        if (dismissHandle != null) {
            dismissHandle.cancel();
            dismissHandle = null;
        }
        if (blurHandle != null) {
            blurHandle.cancel();
            blurHandle = null;
        }
        if (popupBlurHandle != null) {
            popupBlurHandle.cancel();
            popupBlurHandle = null;
        }
        if (popupWindow != null) {
            // The options stop offering their verbs now (optionsOperable() reads `open`), and a
            // window's fade moves only its opacity and damages nothing in the panel, so the walk
            // that withdraws them is asked for: without it the popup's tree kept publishing
            // SELECT, PRESS and FOCUS through the whole fade (2026-09-15, semantics 5).
            if (popupPanel != null) {
                popupPanel.invalidateAccessible();
            }
            // Relinquish the fields now (a fresh open() can build a new popup
            // while this one fades out), then fade the old window to transparent
            // and destroy it once it has fully vanished.
            NativeWindow closing = popupWindow;
            Scene closingScene = popupScene;
            popupWindow = null;
            popupScene = null;
            popupPanel = null;
            popupStep = null;
            NativeWindow parent = scene() != null ? scene().window() : null;
            Runnable destroy = () -> {
                if (parent != null && !parent.isClosed()) {
                    parent.unregisterChildPopup(closing);
                }
                closing.requestClose();
            };
            if (closingScene != null) {
                closingScene.fadeWindowOut(Theme.current().animWindow, destroy);
            } else {
                destroy.run();
            }
        }
        if (scenePopup != null) {
            ScenePopup closing = scenePopup;
            Scene owner = scene();
            scenePopup = null;
            popupPanel = null;
            popupStep = null;
            if (owner == null) {
                return; // detached mid-open: nothing holds the overlay any more
            }
            if (owner.window() == null) {
                owner.removeOverlay(closing); // headless: no frame pump to advance a fade
                return;
            }
            // Wall time, not frame time: this fade's last frame is what removes the overlay
            // holding input capture and focus. Frozen, the list would stay open over a paused
            // application with the field underneath unreachable.
            owner.addRealTimeTicker(dt -> {
                sceneFade = (float) Math.max(0, sceneFade - dt / Theme.current().animWindow);
                closing.invalidate();
                if (sceneFade > 0) {
                    return true;
                }
                owner.removeOverlay(closing); // restores focus to the field
                return false;
            });
        }
    }

    /**
     * Deferred one event-loop turn so an intra-app focus switch (owner blurs,
     * popup gains focus in the same input batch, or vice versa) settles first:
     * the popup survives while OS focus is on the owner window or on the popup
     * itself, and dismisses when it moved anywhere else.
     */
    private void closeUnlessRefocused() {
        if (!open) {
            return;
        }
        boolean ownerFocused = scene() != null && scene().isWindowFocused();
        boolean popupFocused = popupScene != null && popupScene.isWindowFocused();
        if (!ownerFocused && !popupFocused) {
            close(Change.Origin.ADJUSTMENT);
        }
    }

    /**
     * Which way the list drops and how tall it ends up, the one decision both presentations
     * make, from spaces each measures for itself against a different bound (the display's work
     * area for a window, the owner scene for an overlay).
     *
     * @param above  whether the list flips above the field
     * @param height its height in logical points, clamped to the space that direction has
     */
    private record Drop(boolean above, float height) {
    }

    /** The full list height at {@code t}, before any clamping. */
    private float contentHeight(SizeTokens t) {
        return items.size() * t.popupItemHeight() + 2 * t.popupPadV();
    }

    /**
     * Prefers dropping below the field, flipping above only when the list does not fit below
     * <em>and</em> above has more room, so a list that fits nowhere still opens downward, where
     * a dropdown is looked for. Never shorter than two rows: a list clamped to a sliver is
     * unusable, and one clamped to nothing looks like a click that did nothing.
     */
    private Drop dropFor(SizeTokens t, float spaceAbove, float spaceBelow) {
        float contentH = contentHeight(t);
        float minH = Math.min(contentH, 2 * t.popupItemHeight() + 2 * t.popupPadV());
        boolean above = contentH > spaceBelow && spaceAbove > spaceBelow;
        return new Drop(above,
                Math.max(minH, Math.min(contentH, above ? spaceAbove : spaceBelow)));
    }

    /**
     * Draws the list inside the owner scene, for a window that cannot place one of its own.
     *
     * <p>Not deferred, unlike {@link #createPopupWindow()}: there is no window to create and so
     * no GL context to switch, and posting would leave one frame with the field showing itself
     * open and nothing dropped down.
     */
    private void presentInScene() {
        Scene owner = scene();
        popupStep = controlSize();
        popupPanel = new PopupPanel();
        // Before the overlay is pushed, for the reason the native path resolves it before
        // binding: a panel that resolved the process defaults would lay its rows out at one step,
        // or in one direction, inside a box measured at another.
        popupPanel.setInheritanceHost(this);
        scenePopup = new ScenePopup(popupPanel);
        // The link goes on the OVERLAY as well, and it is the overlay's that does the work: the
        // panel's own is ignored here, because a host link on a widget that has a parent loses to
        // the tree, and in this presentation the panel's parent is the overlay. Without this the
        // whole in-scene subtree resolves the owner scene's default and then the process default,
        // so a field at a declared step opens a list whose box is measured at the field's step
        // (this class reads the field's tokens for that) and whose rows are painted and hit at
        // another, and a list opened by a field in its own language resolves its rows in a
        // different one. Before the push, for the reason the panel's own goes on before it:
        // pushOverlay measures, and a link installed afterwards sizes at the wrong step first.
        //
        // It reaches as far as the link is defined to reach and no further: a host is consulted
        // after the widget's own scene default, so an owner scene that declares a step or a
        // language still wins over the field here, where a popup in a window of its own falls
        // through to the field because its scene declares nothing. That is the axis rule rather
        // than this component's, and the case this fixes is the one the class promises.
        scenePopup.setInheritanceHost(this);
        // Assigned before pushOverlay, which moves focus off the field: onFocusLost reads this
        // field to tell "the user clicked elsewhere" from "our own overlay took the keyboard",
        // and the second one must not close the popup it is opening.
        boolean animate = owner.window() != null;
        sceneFade = animate ? 0f : 1f;
        owner.pushOverlay(scenePopup);
        if (animate) {
            ScenePopup fading = scenePopup;
            owner.addRealTimeTicker(dt -> {
                if (!open) {
                    return false; // closed mid-fade: the fade-out ticker takes over
                }
                sceneFade = (float) Math.min(1, sceneFade + dt / Theme.current().animWindow);
                fading.invalidate();
                return sceneFade < 1;
            });
        }
    }

    private void createPopupWindow() {
        // Re-check: the combo may have closed (or detached) before this post
        // ran, or the parent window may already be gone.
        Scene scene = scene();
        if (!open || popupWindow != null || scene == null
                || scene.window() == null || scene.window().isClosed()) {
            return;
        }
        NativeWindow parent = scene.window();
        SizeTokens t = Theme.current().tokensFor(this);
        float gap = t.popupGap();
        float factor = parent.logicalToScreenFactor();
        int screenX = parent.screenX() + Math.round(localToSceneX() * factor);
        int anchorTop = parent.screenY() + Math.round(localToSceneY() * factor);
        int anchorBottom = parent.screenY() + Math.round((localToSceneY() + height() + gap) * factor);

        // Clamp the list to the display work area: prefer dropping below the field;
        // flip above when the list doesn't fit below and there is more room above.
        // A clamped list scrolls (wheel + keyboard highlight auto-reveals).
        limn.backend.ScreenRect area = parent.display() != null ? parent.display().workArea() : null;
        float contentH = contentHeight(t);
        float spaceBelow = area != null
                ? (area.bottom() - anchorBottom) / factor - EDGE_MARGIN : contentH;
        float spaceAbove = area != null
                ? (anchorTop - area.y()) / factor - gap - EDGE_MARGIN : contentH;
        Drop drop = dropFor(t, spaceAbove, spaceBelow);

        int popupWidth = Math.max(1, Math.round(width()));
        int popupHeight = Math.max(1, Math.round(drop.height()));
        int screenY = drop.above()
                ? anchorTop - Math.round(gap * factor) - Math.round(drop.height() * factor)
                : anchorBottom;
        popupWindow = parent.backend().createWindow(WindowConfig.popup(popupWidth, popupHeight));
        parent.registerChildPopup(popupWindow); // parent close ⇒ popup close
        popupStep = controlSize();
        popupRtl = isRightToLeft();

        popupPanel = new PopupPanel();
        // Before the scene binds: binding measures the panel, and a panel that resolved the
        // process defaults there would lay rows out at one step, or in one direction, inside a
        // window sized at another.
        popupPanel.setInheritanceHost(this);
        // On the owner's clock, so the fade-out that destroys this window advances with it
        // (Scene#clock).
        popupScene = new Scene(popupPanel, scene().clock());
        popupScene.inheritRenderingFlags(scene()); // partial/debug follow the owner window
        popupScene.bind(popupWindow);
        // Clicking the list may hand OS focus to the popup window itself; when
        // THAT later blurs, the same dismiss rule applies.
        popupBlurHandle = popupScene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        popupScene.setBackground(Color.TRANSPARENT); // the translucent-window core

        popupWindow.setScreenPosition(screenX, screenY);
        // Screenshot mode keeps everything hidden; interactive mode shows the
        // popup without stealing focus (WindowConfig.popup → focusOnShow=false).
        if (parent.isVisible()) {
            popupScene.fadeWindowIn(Theme.current().animWindow); // transparent → visible
            popupWindow.show();
        }
        popupWindow.requestFrame();
    }

    /**
     * Whether an option of the open list can be chosen or walked onto right now: the list is open
     * and not fading out, and the combo is enabled. The one condition the panel's describe hook
     * publishes {@code SELECT}, {@code PRESS} and {@code FOCUS} on and its action hook performs
     * them on (2026-09-15, semantics 5).
     */
    private boolean optionsOperable() {
        return open && isEnabled();
    }

    private void commit(int index) {
        if (!open) {
            return; // the popup is already closing (e.g. a click during its fade-out)
        }
        if (!isEnabled()) {
            // The one choke point every commit goes through: the row a click landed in, the
            // keyboard's highlight, and an assistive technology's SELECT. A combo disabled while
            // its list is open kept committing on a click, because the list is a parentless
            // overlay and the scene's ancestor gate never reaches the field -- so the guard on
            // the synthetic hook was refusing the one caller that was doing it politely while
            // the pointer went through. Refusing here is what makes the two agree.
            return;
        }
        // Clamped rather than range-checked: both callers are the popup's own bookkeeping (the
        // row a click landed in, and the keyboard highlight), and neither is an application
        // naming an index, which is the only thing setSelectedIndex refuses.
        int chosen = Math.max(0, Math.min(index, items.size() - 1));
        // The list closing is the pick's own settling, announced before the pick; re-picking
        // the item already selected closes the popup and announces nothing else, because the
        // selection did not move.
        close(Change.Origin.USER);
        select(chosen, Change.Origin.USER);
    }

    // ----------------------------------------------------------- field visual


    /**
     * What a label with no strong character of its own falls back to, given the direction the
     * caller already resolved for its pass: an item that is all digits or punctuation
     * ({@code "--"}, {@code "1/2"}, {@code "1.0"}) reads the way the form around it does.
     *
     * <p>The resolved direction is a parameter rather than a second read, so that one pass
     * cannot shape a label against one direction and place it against the other. Every strong
     * character still decides for itself, which is why a Latin item in a right-to-left combo
     * still reads left to right.
     */
    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        if (open && popupWindow != null && popupStep != controlSize()) {
            // The window was sized at popupStep and cannot be resized in place without
            // reflowing rows the user is aiming at; deferred so a measure pass never mutates
            // the tree it is running over.
            Ui.post(this::close);
        }
        boolean rtl = isRightToLeft();
        if (open && popupWindow != null && popupRtl != rtl) {
            // Not deferred, and not a close: this touches the popup window's own tree and not
            // the one being measured, and the window keeps its size (the field measures the
            // same in both directions). Without it the list stays as it was drawn, because
            // nothing in this scene damages a panel that lives in another one.
            popupRtl = rtl;
            popupPanel.markNeedsLayout();
            popupWindow.requestFrame();
        }
        TextMetrics metrics = textRuler().measure(widestItem(t.body()), t.body());
        return constraints.constrain(
                metrics.width() + 2 * t.fieldPadH() + t.comboCaretGutter(),
                t.resolvedHeight(metrics.lineHeight()));
    }

    private String widestItem(Font font) {
        // By MEASURED width, not character count: proportional fonts make
        // "WWWW" wider than "iiiiii" despite fewer characters.
        String widest = items.get(0).get();
        float widestWidth = textRuler().measure(widest, font).width();
        for (I18nString each : items) {
            String item = each.get();
            float itemWidth = textRuler().measure(item, font).width();
            if (itemWidth > widestWidth) {
                widest = item;
                widestWidth = itemWidth;
            }
        }
        return widest;
    }

    /** Mixed-step rows align on this, not on the box; see {@code Flex.CrossAlignment.BASELINE}. */
    @Override
    protected float baselineOffset() {
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure(selectedItem(), t.body());
        return (height() - metrics.height()) / 2 + metrics.ascent();
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        Font font = t.body();
        // One resolution for the whole pass: the label, the clip that keeps it off the chevron
        // and the chevron's own gutter have to agree about which side reading starts on.
        boolean rtl = isRightToLeft();
        Color fill = !isEnabled() ? theme.disabledFill
                : theme.surface.lerp(theme.surfaceRaised, open ? 1f : hover.value());
        canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(), fill);
        float focus = focusFade.value();
        // One rect whose weight animates BORDER -> FOCUS_RING; a ternary here would delete
        // the focus fade outright.
        canvas.drawRoundRect(Strokes.HALF_PIXEL_INSET, Strokes.HALF_PIXEL_INSET,
                width() - Strokes.BORDER, height() - Strokes.BORDER, t.radiusMedium(),
                Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus,
                theme.outline.lerp(theme.focusRing, focus));

        String label = selectedItem();
        // The vertical band stays the measured one, because that is what baselineOffset()
        // reports and a row aligned on a baseline the paint does not use is a row out of line.
        // Only the horizontal placement needs the shaped run.
        TextMetrics metrics = textRuler().measure(label, font);
        Color ink = isEnabled() ? theme.text : theme.disabledText;
        canvas.save();
        // The band that keeps the label off the chevron: its variable edge is the one reading
        // ends on, so the reserved strip is on the left of a right-to-left field.
        canvas.clipRect(rtl ? t.comboTextClip() : 0, 0, width() - t.comboTextClip(), height());
        // Shaped here rather than left to the canvas, because the canvas has no widget to ask
        // and falls back to left-to-right for a string with no strong character of its own.
        // Costs nothing extra: the string overload shapes through the same ruler memo.
        ShapedText line = textRuler().shape(label, font,
                ShapedText.Direction.of(label, neutralBase()));
        // drawText places the LEFT edge of the run's box for either base direction — a
        // right-to-left run fills the same box from the other end rather than growing leftwards
        // — so aligning to the edge reading starts from is choosing x, and choosing it takes
        // the run's own width.
        float textX = rtl ? width() - t.fieldPadH() - line.metrics().width() : t.fieldPadH();
        canvas.drawText(line, textX,
                (height() - metrics.height()) / 2 + metrics.ascent(), ink);
        canvas.restore();

        // Caret triangle (flips while open). Half-height is half the half-width at every
        // step, so the arrow angle is invariant: the same arrow, smaller. Only the gutter it
        // sits in changes sides; the triangle is symmetric about cx and points up and down,
        // so nothing about the path below is directional.
        float cx = rtl ? t.comboCaretCenterX() : width() - t.comboCaretCenterX();
        float cy = height() / 2;
        float halfW = t.chevronHalfW();
        float halfH = halfW / 2;
        caret.reset();
        if (open) {
            caret.moveTo(cx - halfW, cy + halfH).lineTo(cx, cy - halfH).lineTo(cx + halfW, cy + halfH);
        } else {
            caret.moveTo(cx - halfW, cy - halfH).lineTo(cx, cy + halfH).lineTo(cx + halfW, cy - halfH);
        }
        canvas.drawPath(caret, Strokes.ARROW_PEN,
                isEnabled() ? theme.textMuted : theme.disabledText);
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        switch (event.type()) {
            case ENTER -> hover.to(1);
            case EXIT -> hover.to(0);
            case PRESS -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    if (open) {
                        close(Change.Origin.USER);
                    } else {
                        open(Change.Origin.USER);
                    }
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        boolean handled = true;
        if (!open) {
            switch (event.key()) {
                case Keys.SPACE, Keys.ENTER, Keys.DOWN -> open(Change.Origin.USER);
                default -> handled = false;
            }
        } else {
            switch (event.key()) {
                case Keys.DOWN -> setHighlight(highlightedIndex + 1);
                case Keys.UP -> setHighlight(highlightedIndex - 1);
                case Keys.HOME -> setHighlight(0);
                case Keys.END -> setHighlight(items.size() - 1);
                case Keys.PAGE_DOWN -> setHighlight(highlightedIndex + visibleRows());
                case Keys.PAGE_UP -> setHighlight(highlightedIndex - visibleRows());
                case Keys.ENTER, Keys.SPACE -> commit(highlightedIndex);
                case Keys.ESCAPE -> close(Change.Origin.USER);
                default -> handled = false;
            }
        }
        if (handled) {
            event.consume();
        }
    }

    /**
     * How many rows a Page key steps: what the popup is actually showing, since the list is
     * clamped to the display work area and a long one scrolls. At least one, so a popup clamped to
     * a sliver still moves rather than becoming a dead key.
     */
    private int visibleRows() {
        if (popupPanel == null) {
            return 1;
        }
        SizeTokens t = Theme.current().tokensFor(popupPanel);
        float itemHeight = t.popupItemHeight();
        if (itemHeight <= 0) {
            return 1;
        }
        float body = popupPanel.height() - 2 * t.popupPadV();
        return Math.max(1, (int) (body / itemHeight));
    }

    /**
     * Type-ahead: letters typed into an open list jump to what they spell, the way a combo does on
     * Windows and a pop-up button does on GTK and macOS. Without it the only way through a long
     * clamped list is one arrow press per item.
     *
     * <p>Two behaviours a user expects and neither of which falls out of plain prefix matching.
     * The buffer expires after {@link #TYPE_AHEAD_RESET_NANOS}, so "so" typed slowly is two
     * separate jumps rather than a search for "so": a pause means a new word. And repeating one
     * letter cycles through the items starting with it instead of re-finding the first, which is
     * how a list of six things beginning with S is navigated at all.
     *
     * <p>The search wraps and starts <em>after</em> the current highlight, so a match already
     * under the cursor is not what a fresh keystroke finds.
     */
    @Override
    protected void onCharTyped(limn.scene.event.CharEvent event) {
        int codepoint = event.codepoint();
        if (!open || codepoint < 0x20 || codepoint == 0x7F || items.isEmpty()) {
            return;
        }
        // The scene's clock rather than the wall's. They are one clock in an application; in a
        // test or a filmed capture the scene's is the one every other interval on screen is
        // measured on, where the wall made the pause however long the machine took between keys.
        long now = sceneNanos();
        if (now - lastTypedNanos > TYPE_AHEAD_RESET_NANOS) {
            typeAhead.setLength(0);
        }
        lastTypedNanos = now;
        typeAhead.appendCodePoint(codepoint);

        String prefix = typeAhead.toString();
        boolean cycling = prefix.length() > 1 && prefix.chars().distinct().count() == 1;
        String wanted = cycling ? prefix.substring(0, 1) : prefix;
        // From the row after the highlight when the buffer is one letter or is cycling; from the
        // highlight itself while a longer prefix is being extended, or typing "ba" after "b"
        // matched would skip the very item "b" had just found.
        int from = (cycling || prefix.length() == 1) ? highlightedIndex + 1 : highlightedIndex;
        for (int step = 0; step < items.size(); step++) {
            int candidate = Math.floorMod(from + step, items.size());
            if (startsWithIgnoreCase(items.get(candidate).get(), wanted)) {
                setHighlight(candidate);
                event.consume();
                return;
            }
        }
        // Nothing spells this: drop the buffer rather than leaving a prefix that can never match
        // poisoning the next keystroke.
        typeAhead.setLength(0);
    }

    /**
     * Case-insensitive in the text locale, not per {@code char}: both sides are upper-cased
     * whole, with a lower-cased retry for the scripts where only one direction folds. A
     * per-char comparison can never match "stras" to "Straße" — one ß upper-cases to two
     * letters — and matches a Turkish dotted İ only by accident of the accentless tables.
     */
    private static boolean startsWithIgnoreCase(String label, String prefix) {
        return I18n.toUpperCase(label).startsWith(I18n.toUpperCase(prefix))
                || I18n.toLowerCase(label).startsWith(I18n.toLowerCase(prefix));
    }

    /** The keyboard highlight, which is the active descendant a reader follows: every caller is a key. */
    private void setHighlight(int index) {
        int old = highlightedIndex;
        highlightedIndex = Math.max(0, Math.min(index, items.size() - 1));
        if (highlightedIndex != old) {
            notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
        }
        if (popupPanel != null) {
            // Resolved once for the whole event, on the panel: damage and reveal must agree
            // with each other and with the paint loop, or the highlight scrolls to one row
            // and repaints another.
            SizeTokens t = Theme.current().tokensFor(popupPanel);
            popupPanel.damageRow(old, t); // partial rendering: repaint the two affected rows
            popupPanel.damageRow(highlightedIndex, t);
            popupPanel.revealRow(highlightedIndex, t); // keyboard highlight stays in view
        }
        if (popupWindow != null) {
            popupWindow.requestFrame();
        }
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
        if (scenePopup == null) {
            close(Change.Origin.USER); // clicking anywhere else (a popup window never takes focus) dismisses
        }
        // An in-scene popup takes the focus itself (pushOverlay confines it to the overlay), so
        // here focus loss means the popup opening, not the user leaving. Closing on it would
        // dismiss the list in the same pass that opened it.
        invalidate();
    }

    /**
     * A combo removed from the tree must never strand its dropdown on screen:
     * a programmatically-opened popup has no focus to lose, so focus-lost alone
     * does not cover this.
     */
    @Override
    protected void onDetached() {
        close(Change.Origin.ADJUSTMENT);
    }

    // -------------------------------------------------------- accessibility

    /**
     * The field, which is a combo box that is open or shut and is showing one of its items. The
     * options are not here: they are drawn by the panel and described by it, in whichever scene
     * that panel is mounted in, which is what keeps their rectangles right in both presentations.
     *
     * <p><b>The selected item is the value and never the name.</b> A combo has no caption of its
     * own — its items are values and not a label — so the name is left for an application's
     * {@code setAccessibleName} or for the tooltip default to supply, and a combo with neither is
     * published unnamed. Naming it from the item it shows would be worse than that: the name would
     * change on every pick, and on the one platform that maps a name and a value to different
     * attributes a reader would hear the same word twice for every combo in the interface.
     *
     * <p><b>The number is what carries the text.</b> The publish step builds a value facet only
     * for a node that declared a number, so the selected item's text alone would be dropped in
     * silence; and the difference raises its value event off the number and never off the text, so
     * a constant standing in for one would make every pick silent on the channel a reader listens
     * to. The index is the honest number — nothing else about the selection is ordered — and
     * declaring it is also what advertises {@code SET_VALUE}, which is how an assistive technology
     * picks an item without opening the list at all.
     *
     * <p><b>Nothing here is formatted.</b> The item's text comes from the {@link I18nString} the
     * combo already holds, through that string's own per-language memo, so a hover fade or a focus
     * fade — each of which damages this widget on every frame it lasts — walks this hook without
     * allocating anything to conclude that nothing moved.
     *
     * @param a the builder for this node
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.COMBO_BOX);
        // The item it is showing, because a focusable node with no name at all is the one thing
        // §12.1's gallery test refuses, and a combo has no caption of its own to offer instead.
        // The item is the value as well, so a reader does hear the word twice where a platform
        // maps name and value to different attributes -- that is the accepted cost of a default
        // that needs no application to remember anything. An application that wants better says
        // so, and both ways of saying it win over this: setAccessibleName replaces it outright,
        // and a Label bound to this field names it through LABELLED_BY. The source form, never
        // the cached one: the item is an I18nString this widget holds, compared by reference, so
        // a hover or focus fade walks this hook without allocating.
        a.name(items.get(selectedIndex), Accessible.NameFrom.CONTENT);
        // The POPUP_OWNER shape, written once (ADR 045 §3): HAS_POPUP always, and not only
        // while the list is down, because a combo that could not be opened would not be a combo;
        // the open state as the facet, never state(EXPANDED, ...), which the builder ignores by
        // design so the bit and the facet cannot disagree; and one verb and not both, because
        // the other one is what the widget is already doing. PRESS is neither offered nor
        // accepted: what a press on a combo means is exactly the ambiguity these two verbs
        // remove, and a platform whose only activation verb is a press has the expand facet to
        // route it through, which is one decision in one bridge rather than a third meaning
        // here. While the list is down in the scene the walk takes the COLLAPSE off again, with
        // every verb beneath the layer that owns input (ADR 039 §1.13, amended 2026-09-15); in a
        // window of its own the field keeps the input and the verb stands.
        PopupOwnerAccessibility.describe(a, open);
        a.value(selectedIndex, 0, items.size() - 1, 1);
        // The witness is the pair the item's text is a function of: which item is selected, and
        // the epoch every translation moves on. There is no cache to guard here -- the string is
        // the model's own, resolved through its memo -- and the witness says so rather than
        // pretending a counter exists.
        a.valueText(selectedItem(), I18n.epoch() ^ ((long) selectedIndex << 32));
        // No CONTROLLER_FOR. In the scene presentation the overlay is the popup's parentless root
        // and carries the field as its inheritance host, so the walk publishes POPUP_FOR there and
        // this mirror here; declaring it again would put two of the same relation on this node. In
        // a window of its own the list is in another tree, where neither end can resolve the
        // other, and a relation naming a node this tree does not contain is worse than none.
    }

    /**
     * Opens the list, closes it, or picks an item, each through the path the user's own gesture
     * takes.
     *
     * <p>No enabled check of its own, unlike the panel's: the node acted on here is this widget,
     * so the scene's own gate has already walked this field and every ancestor for
     * {@code isEnabled()}, checked that it is showing, that the window is not modal-blocked and
     * that it is inside the layer that owns input. That last test is why a
     * {@link Accessible.Action#COLLAPSE} sent to an open in-scene list does nothing, and why the
     * walk does not publish one there (ADR 039 §1.13, amended 2026-09-15): the list's own
     * overlay is that layer, the field is behind it, and the dismissal that lands there is the
     * overlay's {@code CANCEL} — the same asymmetry the keyboard has, where Esc is delivered to
     * the overlay and handed back here.
     *
     * @param action what is being asked
     * @param arg    the item to select for {@code SET_VALUE}, by number or by text
     * @return whether this widget did it
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (action == Accessible.Action.SET_VALUE) {
            return selectFromArgument(arg);
        }
        return PopupOwnerAccessibility.perform(popupHost, action);
    }

    /**
     * The field's mechanisms as the popup-owner shape drives them (ADR 045 §3): EXPAND on a
     * closed list opens it and COLLAPSE on an open one closes it, each through the path the
     * user's own gesture takes; the other state refuses, because nothing was done and saying
     * otherwise is a lie.
     */
    private final class PopupHost implements PopupOwnerAccessibility.Host {
        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void open() {
            ComboBox.this.open(Change.Origin.USER);
        }

        @Override
        public void close() {
            ComboBox.this.close(Change.Origin.USER);
        }
    }

    private final PopupHost popupHost = new PopupHost();

    /**
     * Selects the item an argument names, by index or by its text.
     *
     * <p>Both funnels are the widget's own and both are the user's: an open list commits, which
     * is the path a click on a row takes and which closes the list and reports a change only when
     * there was one; a shut one enters the selection seam at {@code USER} directly, and not
     * through the public setter, which is a caller's write and would reach no handler. So a pick
     * from an assistive technology reaches the application exactly as a pick from the pointer
     * does.
     */
    private boolean selectFromArgument(Accessible.Argument arg) {
        int index;
        if (arg instanceof Accessible.Argument.OfValue value) {
            double raw = value.value();
            if (!Double.isFinite(raw)) {
                return false; // rounding a NaN would land on item zero
            }
            long rounded = Math.round(raw);
            index = rounded < 0 || rounded >= items.size() ? -1 : (int) rounded;
        } else if (arg instanceof Accessible.Argument.OfText text) {
            index = indexOfItem(text.text());
        } else {
            return false;
        }
        if (index < 0 || index >= items.size()) {
            return false; // an index nothing answers to is refused, never clamped to a neighbour
        }
        if (open) {
            commit(index);
        } else {
            select(index, Change.Origin.USER);
        }
        return true;
    }

    /**
     * The item that reads exactly as {@code wanted}, or {@code -1}.
     *
     * <p>Exact equality, and deliberately not the type-ahead's rule: that one matches a prefix
     * case-insensitively, which is what a user typing into an open list wants and is the wrong
     * answer for a client that was handed a value and is asking for it back. "Tw" would set the
     * selection to "Two" there, and a client would have no way to tell that from a combo that
     * genuinely holds "Tw".
     */
    private int indexOfItem(String wanted) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).get().equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------- scene popup

    /**
     * The dropdown as a scene overlay: a transparent layer the size of the owner scene, holding
     * the same {@link PopupPanel} the native popup puts in a window of its own.
     *
     * <p>Two things it must do that the native popup gets from the OS for free.
     *
     * <p>It <b>holds the keyboard</b>, because {@code pushOverlay} confines focus to the overlay
     * and the field underneath can no longer receive a key. So this layer is focusable and hands
     * what it receives back to the combo, which is where the list's whole key and type-ahead
     * behaviour lives. Nothing is duplicated: both presentations run the same handlers.
     *
     * <p>It <b>dismisses on a press outside the list</b>, which for the native popup is the
     * owner scene's press observer. That observer cannot serve here: an overlay captures every
     * press in the scene, the list's own included, so it would fire on the press that is
     * choosing an item and close the popup before the click landed.
     *
     * <p>The list is clamped to the scene rather than to the display, so a combo near the bottom
     * of a window has less room than the same combo would on a platform that can put its list in
     * a window. That is the visible cost of the fallback, and it is why it is a fallback.
     *
     * <p><b>It is a node in the accessible tree, and holding the keyboard is why.</b> A layer
     * that paints nothing would ordinarily be deleted as scaffolding, but this one is the
     * scene's focused widget and its only tab stop for as long as the list is open, so deleting
     * it would put the tree and the keyboard into disagreement. It says what it is, names itself
     * because nothing else can, and carries the active descendant a reader follows the arrows
     * by; the options themselves are the panel's, which is where they are drawn.
     */
    private final class ScenePopup extends Widget {

        private final PopupPanel panel;

        ScenePopup(PopupPanel panel) {
            this.panel = panel;
            setFocusable(true);
            add(panel);
        }

        /** The overlay is laid out tight to the scene; that size is the bound the list clamps to. */
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            SizeTokens t = Theme.current().tokensFor(ComboBox.this);
            // The field's direction and not the overlay's, for the reason the tokens are the
            // field's: this list belongs to the combo, and the overlay is only the layer it is
            // drawn on. Resolved once for the pass.
            boolean rtl = ComboBox.this.isRightToLeft();
            float gap = t.popupGap();
            // Read after the root has been laid out: the scene lays overlays out last, which is
            // what makes the field's scene position current here rather than one frame stale.
            float anchorX = ComboBox.this.localToSceneX();
            float anchorTop = ComboBox.this.localToSceneY();
            float anchorBottom = anchorTop + ComboBox.this.height() + gap;
            Drop drop = dropFor(t,
                    anchorTop - gap - EDGE_MARGIN,
                    height() - anchorBottom - EDGE_MARGIN);

            float listWidth = Math.min(ComboBox.this.width(), width());
            // Hung from the field's LEADING edge, which is its right one reading right to left;
            // anchorX stays the field's physical left either way.
            //
            // The two branches compute the same number today, and saying so is the point: the
            // list is the field's own width unless the scene is narrower still, and a box the
            // width of the field aligned to either of the field's edges lands on the field. The
            // alignment is written down anyway because that is the invariant it rests on, and
            // the day a list is narrower than the field that it opened under, this line is
            // already the one that has to be right.
            float wanted = rtl ? anchorX + ComboBox.this.width() - listWidth : anchorX;
            // The clamp keeps its form, and it needs no direction of its own: listWidth is a
            // min with this overlay's width, so the interval is never empty and clamping the
            // leading edge first agrees with clamping the trailing one first.
            float x = Math.max(0, Math.min(wanted, width() - listWidth));
            float y = drop.above() ? anchorTop - gap - drop.height() : anchorBottom;
            panel.measure(Constraints.tight(listWidth, drop.height()));
            panel.layoutBox(x, y, listWidth, drop.height());
        }

        /** Keys reach the list through here; see the class note on why they are not handled here. */
        @Override
        protected void onKeyEvent(KeyEvent event) {
            ComboBox.this.onKeyEvent(event);
        }

        @Override
        protected void onCharTyped(limn.scene.event.CharEvent event) {
            ComboBox.this.onCharTyped(event);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            // Only presses that missed the list arrive here; one inside it is the panel's.
            if (event.type() == MouseEvent.Type.PRESS) {
                close(Change.Origin.USER);
                event.consume();
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            // This layer is not scaffolding, whatever a survey of it from the outside says. The
            // constructor makes it focusable and pushOverlay's focus traverse finds nothing
            // focusable beneath it, so for the whole life of an in-scene list it is the scene's
            // focused widget and its only tab stop. Deleting it would leave the published
            // focusable set empty while the keyboard reaches one widget, which is the equality
            // the modal rule states, and would leave the focused identifier naming a node the
            // tree does not contain. Every parentless top overlay is also declared modal by the
            // walk before the transparency predicate runs, so none of them can be deleted.
            //
            // The role is declared rather than left to the reset default, because a declared
            // role is what stops a focusable node being published as UNKNOWN with a warning
            // logged once and read by nobody. GROUP and not LIST: the panel below is the list,
            // it has the list's own rectangle, and two nested lists is a shape every bridge has
            // to invent its way out of. Not WINDOW, which the tree's own root already is; not
            // DIALOG, which this is not and which a Windows client acts on.
            a.role(Accessible.Role.GROUP);
            // A constant this class holds, never a string built here: an open list runs a fade
            // and damages this layer on every frame of it, and anything allocated in this hook
            // is allocated per frame to conclude that nothing moved. Nothing else can name it --
            // it paints nothing, has no tooltip, and is private to this file -- and the field's
            // own name is not reachable from this package. The relation below is what carries
            // the link to the field instead. The provenance is the control's own name and not a
            // description of it, which is the slot a tooltip would take.
            a.name(ComponentStrings.COMBO_POPUP, Accessible.NameFrom.CONTENT);
            // The whole reason for describing this node well. Focus never moves while the list
            // is open, so the only way a reader can follow Up, Down, Home, End, the page keys or
            // type-ahead is an active descendant on the node that holds the focus -- which is
            // this one, and not the list. The facet declares the container; which of its
            // descendants the cursor is on is the first one published ACTIVE, filled in by the
            // publish step, so this is inert until the panel marks its highlighted row. In this
            // list the cursor and the selection are two things: the highlight moves under the
            // arrows and the selection moves only on commit -- which is about the ACTIVE
            // descendant and not about whether a selection exists. Required is true for the
            // combo's own documented reason, the same one the panel's facet gives over these
            // very members: it refuses an empty item list, so there is always exactly one
            // selection and nothing to clear to.
            a.selection(false, true);
            // The single-argument form, never the variable-argument one, which allocates an
            // array per call. CANCEL and nothing else: Esc dismisses the list, a press on this
            // layer is the click-outside dismissal that CANCEL already names, and offering both
            // would be two verbs for one behaviour. FOCUS and SCROLL_INTO_VIEW arrive free
            // because the widget is focusable, and MODAL arrives free from the walk, so neither
            // is written here. Only while the list is open: through the fade-out after close()
            // the layer is still the top overlay and was still published with CANCEL, which
            // close()'s own guard then dropped while the hook answered done (2026-09-15,
            // semantics 5); the hook below reads the same field.
            if (open) {
                a.action(Accessible.Action.CANCEL);
            }
            // No bounds call. The node's box is this overlay's own, which is the whole scene --
            // the layer that genuinely captures every press, the same shape as a dialog's scrim
            // -- so a platform hit test outside the list resolves to this node, and that is
            // true: a press there closes the popup. The list's rectangle is the panel's node.
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action,
                                                Accessible.Argument arg) {
            if (action != Accessible.Action.CANCEL || !open) {
                return false; // not while the list fades out, where nothing publishes CANCEL
            }
            // The path Esc takes, with its own guards: close() checks the thread and returns on
            // a popup that is already closing, and the posted action has already re-checked
            // attachment, the enabled chain, showing and reachability. Nothing here gains an
            // entry point the keyboard does not already use.
            ComboBox.this.close(Change.Origin.USER);
            return true;
        }
    }

    // ---------------------------------------------------------- popup panel

    /**
     * Rounded, translucent item list living in its own transparent window. When
     * the list was clamped to the work area it scrolls: wheel + draggable
     * {@link ScrollBar}, and the keyboard highlight auto-reveals ({@link Scrollable}).
     *
     * <p><b>{@link ScrollBar#thickness()} does not participate in the size axis</b>: it is
     * 15pt at every step. Unlike {@code TextArea} and {@code ListView},
     * which overlay their bar, this panel <em>reserves</em> the column: bar plus
     * {@code popupBarInsetX} takes 17 / 17 / 18 / 19 / 20 pt of the panel's width from XSMALL
     * to XLARGE. Against a field at the step's {@code fieldWidth} that is 9.9% of an XSMALL
     * popup against 7.5% of a MEDIUM one, and the 15pt thumb track covers 62.5% of a 24pt
     * XSMALL row against 50% of a 30pt MEDIUM one. Accepted cost of one scrollbar geometry
     * process-wide; it is stated here rather than absorbed silently.
     */
    private final class PopupPanel extends Widget implements Scrollable {

        private final ScrollBar vBar;
        private int hoverIndex = -1;
        private float scroll;
        private boolean initialRevealDone;

        PopupPanel() {
            setCursor(Cursor.POINTER); // the whole list reads as clickable
            vBar = new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
                @Override
                public float contentLength() {
                    return contentHeight(tokens());
                }

                @Override
                public float viewportLength() {
                    return height();
                }

                @Override
                public float offset() {
                    return scroll;
                }

                @Override
                public void setOffset(float value) {
                    scroll = clampScroll(value, tokens());
                    invalidate();
                }
            });
            add(vBar);
        }

        /**
         * The panel's row, resolved through the host link to the owning combo. Called once at
         * the top of each pass, and from the {@link ScrollBar.Model} callbacks, which are
         * entered from the scroll bar's own pass and so cannot be handed one.
         */
        private SizeTokens tokens() {
            return Theme.current().tokensFor(this);
        }

        private float clampScroll(float value, SizeTokens t) {
            return Math.max(0, Math.min(value, Math.max(0, contentHeight(t) - height())));
        }

        private void scrollListBy(float dy, SizeTokens t) {
            float clamped = clampScroll(scroll + dy, t);
            if (clamped != scroll) {
                scroll = clamped;
                vBar.onScrolled();
                invalidate();
            }
        }

        /**
         * Local y of row {@code index}'s top edge, <b>the</b> row formula. Painting, damage,
         * reveal and hit-testing all go through this and {@link #rowAt(float, SizeTokens)};
         * they used to carry four independent copies of the same expression, which is how a
         * hit index and a paint index drift apart silently. The tokens are a parameter for the
         * same reason: two resolutions inside one gesture would put the copies back.
         */
        float rowTop(int index, SizeTokens t) {
            return t.popupPadV() + index * t.popupItemHeight() - scroll;
        }

        /**
         * Inverse of {@link #rowTop(int, SizeTokens)}: the row a local y lands in.
         * {@code rowAt(rowTop(i)) == i} for every row at every scroll offset; that identity is
         * the whole contract, and {@code PopupPanelTest} asserts it exhaustively.
         *
         * <p>Truncating rather than flooring, deliberately, because that is what the four
         * copies this replaces did: a click in the panel's <em>top</em> padding truncates to 0
         * and therefore hits row 0, extending its target upward. Note the asymmetry: the
         * bottom padding yields {@code items.size()}, which is out of range and inert. Whether
         * the top padding should be inert too is a real question, but it is a behaviour change
         * and not this refactor's business.
         */
        int rowAt(float localY, SizeTokens t) {
            return (int) ((localY + scroll - t.popupPadV()) / t.popupItemHeight());
        }

        /**
         * Whether the list reads right to left: <b>the field's</b> resolved direction, taken
         * once per pass into a local exactly as the row tokens are.
         *
         * <p>Asking the field rather than resolving here is not an override, and there is no
         * setter to override: a list whose rows read the other way from the field that opened
         * them is a bug in either presentation. The two answers agree wherever the direction
         * comes from above them both, and they part in the one case the axis exists for. A
         * panel in a window of its own is parentless and reaches the field through its host
         * link, but the in-scene panel is a child of the overlay layer, and the tree wins over
         * a host link: a field declaring a direction its scene does not share would open a list
         * that read the other way.
         *
         * <p>Never in this panel's constructor, which runs before it is linked to the field at
         * all.
         */
        private boolean isRtl() {
            return ComboBox.this.isRightToLeft();
        }

        /**
         * Local x of the selected-row marker's centre: the marker column sits at the start of a
         * row, which is the right side of a row that reads right to left.
         *
         * <p>A method for {@link #rowTop(int, SizeTokens)}'s reason, and taking its resolved
         * inputs for that reason too. The x axis has the same failure the y axis had: three
         * copies of {@code width() - inset - …} across the dot, the label and the label's clip
         * is how a mark and the text it marks drift onto different columns.
         */
        float rowDotX(boolean rtl, SizeTokens t) {
            float inset = t.popupRowInsetX();
            return rtl ? width() - inset - t.popupDotCol() : inset + t.popupDotCol();
        }

        /**
         * Local x where a row's label starts reading: past the marker column, on the side
         * reading starts from. It is the label's left edge reading left to right and its
         * <em>right</em> edge reading right to left, which is why the paint subtracts the run's
         * own width there and the clip band is measured from the other side.
         */
        float rowTextX(boolean rtl, SizeTokens t) {
            float inset = t.popupRowInsetX();
            return rtl ? width() - inset - t.popupMarkerCol() : inset + t.popupMarkerCol();
        }

        void revealRow(int index, SizeTokens t) {
            reveal(rowTop(index, t), t.popupItemHeight(), t);
        }

        /** Repaints a single row; hover/highlight moves must not damage the whole panel. */
        void damageRow(int index, SizeTokens t) {
            if (index >= 0 && index < items.size()) {
                invalidate(0, rowTop(index, t), width(), t.popupItemHeight());
            }
        }

        @Override
        public void revealRect(float x, float y, float rectWidth, float rectHeight) {
            reveal(y, rectHeight, tokens());
        }

        /** Scrolls the minimum so the band is fully visible (viewport coordinates). */
        private void reveal(float y, float bandHeight, SizeTokens t) {
            if (y < 0) {
                scrollListBy(y, t);
            } else if (y + bandHeight > height()) {
                scrollListBy(Math.min(y, y + bandHeight - height()), t);
            }
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            SizeTokens t = tokens();
            boolean rtl = isRtl();
            float barW = ScrollBar.thickness();
            float barH = height() - 2 * t.popupPadV();
            vBar.measure(Constraints.tight(barW, barH));
            // The reserved bar column is on the trailing edge, which is the left one reading
            // right to left. The bar's own geometry is vertical and is untouched by any of this.
            vBar.layoutBox(rtl ? t.popupBarInsetX() : width() - barW - t.popupBarInsetX(),
                    t.popupPadV(), barW, barH);
            scroll = clampScroll(scroll, t);
            if (!initialRevealDone && height() > 0) {
                initialRevealDone = true;
                revealRow(highlightedIndex, t); // open with the current selection in view
            }
            vBar.refresh();
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            SizeTokens t = tokens();
            boolean rtl = isRtl();
            float itemH = t.popupItemHeight();
            float inset = t.popupRowInsetX();
            // In-scene the canvas opacity does the fade; a popup window fades through its own
            // compositor opacity, so that presentation paints solid here.
            boolean fading = scenePopup != null && sceneFade < 1f;
            if (fading) {
                canvas.save();
                canvas.setOpacity(sceneFade);
            }
            // Slightly translucent panel: the desktop shows through, and the rounded corners are
            // genuinely transparent (framebuffer alpha 0). In-scene the same 0.94 composites
            // over the owner's own content instead of over the desktop, and a list you can read
            // the page through is not frosted glass: it is unreadable. So that presentation
            // paints the theme's surface as the theme wrote it.
            canvas.fillRoundRect(Strokes.HALF_PIXEL_INSET, Strokes.HALF_PIXEL_INSET,
                    width() - Strokes.BORDER, height() - Strokes.BORDER, t.radiusLarge(),
                    scenePopup != null ? theme.surfaceRaised
                            : theme.surfaceRaised.withAlpha(0.94f));
            canvas.drawRoundRect(Strokes.HALF_PIXEL_INSET, Strokes.HALF_PIXEL_INSET,
                    width() - Strokes.BORDER, height() - Strokes.BORDER, t.radiusLarge(),
                    Strokes.BORDER, theme.outline);

            Font font = t.body();
            TextMetrics metrics = textRuler().measure("Hg", font);
            // The two row columns and the label's band, composed once: they are the same for
            // every row, and computing them per row is how three copies of one expression get
            // back in.
            float dotX = rowDotX(rtl, t);
            float textStart = rowTextX(rtl, t);
            // The band runs from where the label starts to the panel's far inset, so it is the
            // label's trailing limit that is computed and the sides swap with the direction.
            float bandX = rtl ? inset : textStart;
            float bandW = rtl ? textStart - inset : width() - inset - textStart;
            canvas.save();
            // Keep rows off the border: the clip inset IS the border width.
            canvas.clipRect(Strokes.ROW_CLIP, Strokes.ROW_CLIP,
                    width() - 2 * Strokes.ROW_CLIP, height() - 2 * Strokes.ROW_CLIP);
            for (int i = 0; i < items.size(); i++) {
                float top = rowTop(i, t);
                if (top + itemH < 0 || top > height()) {
                    continue; // scrolled out
                }
                boolean active = i == highlightedIndex || i == hoverIndex;
                if (active) {
                    // ROW_GUTTER, not a scaled inset: the 1pt gap exists to stop two
                    // highlighted rows fusing, and 3pt of it reads as a deliberate stripe.
                    canvas.fillRoundRect(inset, top + Strokes.ROW_GUTTER,
                            width() - 2 * inset, itemH - 2 * Strokes.ROW_GUTTER,
                            t.radiusSmall(), theme.primary.withAlpha(0.28f));
                }
                if (i == selectedIndex) {
                    // The one mark that scales: pure area, no pen, and 2.5pt vanishes in a
                    // 42pt row.
                    canvas.fillCircle(dotX, top + itemH / 2, t.popupDotRadius(), theme.primary);
                }
                // Shaped against this list's own direction as the neutral fallback, for the
                // reason the field's label is, and only for the rows that survived the
                // visibility check above. It is also where the run's width comes from: aligning
                // to the edge reading starts from needs it, and the row loop has no other
                // measurement of the label.
                String label = items.get(i).get();
                ShapedText line = shapeText(label, font);
                // Clip each row so an over-long label can't reach the rounded border.
                canvas.save();
                canvas.clipRect(bandX, top, bandW, itemH);
                canvas.drawText(line, rtl ? textStart - line.metrics().width() : textStart,
                        top + (itemH - metrics.height()) / 2 + metrics.ascent(),
                        theme.text);
                canvas.restore();
            }
            canvas.restore();
            if (fading) {
                canvas.restore();
            }
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            SizeTokens t = tokens(); // one resolution per event: the hit row is this row formula
            float ly = sceneToLocalY(event.y());
            int index = rowAt(ly, t);
            boolean valid = index >= 0 && index < items.size();
            switch (event.type()) {
                case MOVE, ENTER -> {
                    vBar.onHostActivity();
                    int newIndex = valid ? index : -1;
                    if (newIndex != hoverIndex) { // partial rendering: two rows, not the panel
                        damageRow(hoverIndex, t);
                        hoverIndex = newIndex;
                        damageRow(hoverIndex, t);
                    }
                }
                case EXIT -> {
                    if (hoverIndex != -1) {
                        damageRow(hoverIndex, t);
                        hoverIndex = -1;
                    }
                }
                case WHEEL -> {
                    if (event.scrollY() != 0 && contentHeight(t) > height()) {
                        scrollListBy(-event.scrollY() * Strokes.WHEEL_STEP, t);
                        event.consume();
                    }
                }
                case CLICK -> {
                    if (valid) {
                        commit(index);
                    }
                    event.consume();
                }
                case PRESS -> event.consume();
                default -> {
                }
            }
        }

        /**
         * The list itself, and one node per option.
         *
         * <p><b>Why it is described at all.</b> The panel is not focusable, has no tooltip and
         * declares nothing free, so without this hook the transparency predicate deletes it — and
         * because it paints, the deletion is a warning logged once per class and read by nobody,
         * while the options themselves, which are painted rows and not widgets, would simply not
         * exist. It is the widget that draws the options, so it is the widget that describes them.
         *
         * <p><b>No name.</b> The panel is not focusable, so the every-focusable-node-is-named rule
         * does not reach it, and the layer above already carries the list's name and the link to
         * the field. A second invented string would be one more thing to translate and one more
         * thing a reader has to hear before the options.
         *
         * <p><b>No relation either, and that is a correction.</b> In the in-scene presentation the
         * overlay is the popup's root and the walk gives it {@code POPUP_FOR} the field and the
         * field the mirror, so a second link declared here would have two nodes in one tree
         * claiming to be the field's popup. In a window of its own the panel is parentless with a
         * host link, which is exactly the shape the walk's own popup gate answers, so the relation
         * is registered there too without a line here. Both mountings are covered by the walk.
         *
         * <p><b>Nothing is formatted here.</b> Every option's name is an {@link I18nString} the
         * combo already holds, handed over as a source to be compared by reference; the rest is
         * primitives. An open list is damaged on every frame of its fade and on every hover move,
         * so a string built in this hook would be built once a frame to conclude that nothing
         * moved.
         *
         * @param a the builder for this node
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            // Once for the pass, as rowTop, rowAt and damageRow already require: two resolutions
            // inside one description could place a row against one step and size it against
            // another.
            SizeTokens t = tokens();
            float itemH = t.popupItemHeight();
            float inset = t.popupRowInsetX();
            float viewport = height();
            float contentH = contentHeight(t);
            float maxScroll = Math.max(0, contentH - viewport);

            a.role(Accessible.Role.LIST);
            // The container half of the ROWS shape (ADR 045 §3): single-selection and
            // always-selected, which are the combo's own documented invariant: it refuses an
            // empty item list, so there is exactly one selection and nothing to clear to. No
            // PRESS of its own. The active descendant is not declared and cannot be: it is
            // resolved in the copy from the first node in this subtree published ACTIVE, which is
            // the highlighted option below.
            RowsAccessibility.describeContainer(a, RowsAccessibility.Selection.SINGLE, true,
                    false);
            // The survey's row omits this and the panel scrolls: the wheel, a real ScrollBar child
            // with a real model, Scrollable#revealRect and the keyboard's auto-reveal all move it,
            // and a list clamped to the work area or to the owner scene is the ordinary case
            // rather than the long one. Computed from the same expressions clampScroll uses, so
            // the facet and the clamp cannot drift apart.
            a.scrollFrom(0, 0, 1, 0, scroll, maxScroll, viewport, contentH);
            // No MODAL. In scene the walk already stamps it on the parentless top overlay, which
            // is the layer this panel is a child of; in a window of its own the modality is the
            // popup window's. Declaring it here would publish it twice in one tree.

            // Read once, and read again by onSyntheticAction: the three verbs are published
            // exactly while they are performed (semantics 5), and not through the fade-out or on
            // a combo disabled under its open list, where the hook refuses all three.
            boolean operable = optionsOperable();
            // A combo disabled under its open list: the options say so themselves. The walk no
            // longer reads a parentless overlay's enabled axis off its inheritance host, because
            // the keyboard and the pointer never did (ADR 039 §1.9, amended 2026-09-15), and a
            // list in a window of its own was never walked through the field at all, so this is
            // the one place both mountings learn it. Narrowing takes every verb off the row too.
            boolean inert = !ComboBox.this.isEnabled();
            for (int i = 0; i < items.size(); i++) {
                float top = rowTop(i, t);
                a.child(i);
                // From the row formula the paint, the damage, the reveal and the hit test all
                // share, and never from an index times a nominal height: rowTop subtracts the
                // scroll, and a clamped list scrolls. The full row height and not the painted
                // highlight's inset band, because the gutter is a cosmetic gap and rowAt hits the
                // whole row: the box published has to be the box a click lands in.
                a.bounds(inset, top, width() - 2 * inset, itemH);
                a.role(Accessible.Role.LIST_ITEM);
                // The string the combo holds, not the string it reads as: reference, locale and
                // translation epoch are what the difference compares, and get() would allocate one
                // per option per frame.
                a.name(items.get(i), Accessible.NameFrom.CONTENT);
                // The ROWS shape, written once (ADR 045 §3), read against this widget: the
                // membership; the cursor, which here is not the selection — the highlight moves
                // under the arrows and type-ahead while the selection moves only on commit, and
                // a reader that heard only the selection could enumerate the options and never
                // learn which one the user is on; not the hover, which is a pointer affordance
                // and would republish the tree on every mouse move — and the three verbs, only
                // while they are performed (semantics 5): SELECT and PRESS both, because choosing
                // an option in a combo is one gesture, and FOCUS, which moves the highlight
                // without choosing (decision 11, 2026-09-15). No SCROLL_INTO_VIEW: the list
                // reveals its highlight itself. A narrowed row carries no verb, as below.
                boolean verbs = !inert && operable;
                RowsAccessibility.describeRow(a, RowsAccessibility.Offer.OWNED,
                        verbs ? RowsAccessibility.Selection.SINGLE : RowsAccessibility.Selection.NONE,
                        i == selectedIndex, i + 1, items.size(), false, false,
                        i == highlightedIndex, verbs, verbs, false);
                // The negation of the paint loop's own skip test, so the tree and the pixels agree
                // by construction rather than by two people remembering the same rule. Every
                // option is still published, because the count and each option's position in it
                // are what a reader is told and they do not change with the scroll.
                if (top + itemH < 0 || top > viewport) {
                    a.offScreen();
                }
                if (inert) {
                    a.disabled();
                }
                a.endChild();
            }
        }

        /**
         * Chooses an option, through the same private path a click on it takes, or moves the
         * highlight onto it, through the path the arrow keys take.
         *
         * <p>All three verbs are refused, and none of them published, while
         * {@code optionsOperable()} is false (2026-09-15, semantics 5): through the list's
         * fade-out the three were published and {@code FOCUS} was refused here while
         * {@code SELECT} and {@code PRESS} were answered {@code true} for a commit that its own
         * guard dropped. {@code commit} keeps that guard for the pointer, clamps the index,
         * closes the list and notifies the application only when the selection actually moved —
         * so re-picking what is already selected says nothing here either, which is the rule the
         * pointer already obeys. Nothing gains a public entry point for this.
         *
         * <p>The enabled half is load-bearing in a window of its own: the scene's gate walks the
         * owner's ancestors, and there the panel's chain is the panel alone, with the combo in
         * another scene entirely. A list opened through the public {@code open()} on a disabled
         * combo would otherwise accept a commit.
         *
         * @param key    the option's index, which is its key
         * @param action what is being asked
         * @param arg    unused; all three verbs here are parameterless
         * @return whether the option was chosen
         */
        @Override
        protected boolean onSyntheticAction(long key, Accessible.Action action,
                                            Accessible.Argument arg) {
            if (!optionsOperable()) {
                return false;
            }
            int index = (int) key;
            if (index < 0 || index >= items.size()) {
                return false;
            }
            return RowsAccessibility.performOnRow(rowsHost, index, action);
        }

        /**
         * The list's mechanisms as the rows shape drives them: {@code SELECT} and {@code PRESS}
         * both commit the option, because choosing one in a combo is one gesture, and
         * {@code FOCUS} moves the highlight through the arrows' path. Nothing reveals: the verb
         * is not published on an option and the list reveals its highlight itself.
         */
        private final class RowsHost implements RowsAccessibility.Host<Integer> {
            @Override
            public RowsAccessibility.Selection selection() {
                return RowsAccessibility.Selection.SINGLE;
            }

            @Override
            public boolean cursorIsTheSelection() {
                return false;
            }

            @Override
            public boolean rowsActivate() {
                return true;
            }

            @Override
            public boolean isSelected(Integer index) {
                return index == selectedIndex;
            }

            @Override
            public boolean select(Integer index, boolean moveCursor) {
                commit(index);
                return true;
            }

            @Override
            public void moveCursor(Integer index) {
                setHighlight(index); // the arrows' path: announced, damaged, revealed
            }

            @Override
            public void reveal(Integer index) {
                // Not published on an option, so never asked for; the highlight reveals itself.
            }

            @Override
            public void activate(Integer index) {
                commit(index);
            }
        }

        private final RowsHost rowsHost = new RowsHost();
    }
}

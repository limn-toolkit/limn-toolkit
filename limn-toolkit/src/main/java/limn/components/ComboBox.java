package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.i18n.I18nString;
import limn.graphics.Path2D;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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
    private Consumer<Integer> onSelect = index -> {
    };

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
    private Runnable dismissHandle;
    private Runnable blurHandle;
    private Runnable popupBlurHandle;

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
     * Called with the chosen index whenever the selection changes: a pick from the popup and a
     * {@link #setSelectedIndex} from code both arrive here.
     */
    public ComboBox onSelect(Consumer<Integer> listener) {
        Ui.checkUiThread();
        this.onSelect = Objects.requireNonNull(listener, "listener");
        return this;
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
     * Selects an item and fires {@link #onSelect}; code and a pick from the popup take the same
     * path, so a listener sees every change either way. Selecting what is already selected changes
     * nothing and fires nothing; that early return is what keeps two controls bound to each other
     * from recursing, so do not remove it. UI thread only.
     *
     * @param index an item in {@code [0, itemCount)}
     * @throws IndexOutOfBoundsException if {@code index} is not an item; an index computed from a
     *         lookup that missed is a caller's bug here, not a request for the nearest item
     */
    public ComboBox setSelectedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, items.size());
        if (index == selectedIndex) {
            return this;
        }
        selectedIndex = index;
        invalidate();
        onSelect.accept(selectedIndex);
        return this;
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

    /** Opens the popup (native window when bound; pure state when headless). */
    public void open() {
        Ui.checkUiThread();
        if (open) {
            return;
        }
        open = true;
        highlightedIndex = selectedIndex;
        typeAhead.setLength(0); // a new list is a new word, however recently the last one was typed
        invalidate();
        Scene scene = scene();
        if (scene == null) {
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
                close();
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

    /** Dismisses the popup without changing the selection. No-op when closed. */
    public void close() {
        Ui.checkUiThread();
        if (!open) {
            return;
        }
        open = false;
        invalidate();
        if (dismissHandle != null) {
            dismissHandle.run();
            dismissHandle = null;
        }
        if (blurHandle != null) {
            blurHandle.run();
            blurHandle = null;
        }
        if (popupBlurHandle != null) {
            popupBlurHandle.run();
            popupBlurHandle = null;
        }
        if (popupWindow != null) {
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
            close();
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
        popupRtl = isRtl();

        popupPanel = new PopupPanel();
        // Before the scene binds: binding measures the panel, and a panel that resolved the
        // process defaults there would lay rows out at one step, or in one direction, inside a
        // window sized at another.
        popupPanel.setInheritanceHost(this);
        popupScene = new Scene(popupPanel);
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

    private void commit(int index) {
        if (!open) {
            return; // the popup is already closing (e.g. a click during its fade-out)
        }
        // Clamped rather than range-checked: both callers are the popup's own bookkeeping (the
        // row a click landed in, and the keyboard highlight), and neither is an application
        // naming an index, which is the only thing setSelectedIndex refuses.
        int chosen = Math.max(0, Math.min(index, items.size() - 1));
        boolean changed = chosen != selectedIndex;
        selectedIndex = chosen;
        close();
        invalidate();
        if (changed) {
            // Re-picking the item already selected closes the popup and says nothing: the
            // listener reports the selection, and the selection did not move.
            onSelect.accept(selectedIndex);
        }
    }

    // ----------------------------------------------------------- field visual

    /**
     * Whether this combo reads right to left. Resolve it <b>once per pass</b> into a local and
     * never in a constructor: this one is read from {@code onMeasure} and {@code onPaint}, where
     * the field has a parent and the answer is the one the interface around it gives.
     */
    private boolean isRtl() {
        return layoutDirection() == LayoutDirection.RTL;
    }

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
    private static ShapedText.Direction neutralBase(boolean rtl) {
        return rtl ? ShapedText.Direction.RTL : ShapedText.Direction.LTR;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        if (open && popupWindow != null && popupStep != controlSize()) {
            // The window was sized at popupStep and cannot be resized in place without
            // reflowing rows the user is aiming at; deferred so a measure pass never mutates
            // the tree it is running over.
            Ui.post(this::close);
        }
        boolean rtl = isRtl();
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
        boolean rtl = isRtl();
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
                ShapedText.Direction.of(label, neutralBase(rtl)));
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
                        close();
                    } else {
                        open();
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
                case Keys.SPACE, Keys.ENTER, Keys.DOWN -> open();
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
                case Keys.ESCAPE -> close();
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
        long now = System.nanoTime();
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

    private static boolean startsWithIgnoreCase(String label, String prefix) {
        return label.length() >= prefix.length()
                && label.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private void setHighlight(int index) {
        int old = highlightedIndex;
        highlightedIndex = Math.max(0, Math.min(index, items.size() - 1));
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
            close(); // clicking anywhere else (a popup window never takes focus) dismisses
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
        close();
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
            boolean rtl = ComboBox.this.isRtl();
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
                close();
                event.consume();
            }
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
            return ComboBox.this.isRtl();
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
    }
}

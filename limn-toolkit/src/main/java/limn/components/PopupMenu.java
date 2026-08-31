package limn.components;

import limn.backend.NativeWindow;
import limn.backend.ScreenRect;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Path2D;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A context menu / dropdown that presents a {@link Menu} (with checkable items,
 * separators and cascading submenus) as a real <b>native window</b>: an
 * undecorated, floating, transparent OS window (like a ComboBox popup) that may
 * overflow the owner window exactly like a true OS menu.
 *
 * <p>The menu is positioned relative to the <b>screen</b> and <b>never overflows
 * the visible area</b>: each column flips (opens above / to the left) and then
 * clamps against the monitor's {@linkplain limn.backend.Display#workArea() work
 * area}, so it stays on screen no matter where the pointer is. A single native
 * window is sized to the open cascade's bounding box and re-fit as submenus
 * open/close; the whole cascade is rendered and hit-tested inside it.
 *
 * <p>It grabs keyboard focus while open (arrows navigate, Enter chooses, Esc
 * closes); a press back in the owner window, an item choice, or Esc dismisses.
 * Requires a windowed scene; headless scenes have no display to place it on.
 *
 * <p><b>Two presentations:</b> a window of its own, or an overlay inside the owner window;
 * same cascade, flip, clamp and navigation either way, but an overlay is clamped to that window
 * rather than to the display, so a menu near the bottom edge has less room than it would
 * elsewhere. {@link #setDisplayMode} chooses; {@link #displayMode()} answers what was actually
 * used, which is not always the same thing. Two platforms have no choice and always draw in
 * scene: macOS exclusive fullscreen, where taking focus minimizes the fullscreen owner, and any
 * window reporting no {@link limn.backend.NativeWindow#supportsAbsolutePositioning()} (Wayland),
 * where a menu window would open somewhere other than the anchor.
 *
 * <pre>{@code
 * new PopupMenu(menu).showAt(grid, mouseX, mouseY); // context menu at the pointer
 * }</pre>
 *
 * <p><b>The whole cascade renders at one {@link ControlSize} step</b>, resolved once when
 * the menu opens: from {@link #setControlSize} if set, else through the anchor widget, else
 * from the owner scene. Mixing steps across columns would desynchronise the submenu
 * y-alignment, the column overlap and the shared border weight.
 *
 * <p><b>And at one {@link LayoutDirection}</b>, resolved through the same link at the same
 * moment. Reading right to left the whole cascade is mirrored: the root column aligns to the
 * anchor's right edge, submenus open to the left, the check gutter is on the right and the
 * accelerator hint and the submenu chevron on the left, and the arrow keys that open and close
 * a submenu swap. What does not mirror is the check mark itself, which no platform mirrors,
 * and the accelerator strings, which name physical keys.
 */
public final class PopupMenu {

    /**
     * Scroll dead-band: within half a point a clamped column counts as "parked at the edge",
     * which is what makes the hint band's paint condition, its hit region and the scroll clamp
     * agree. A threshold, not a length: it does not move with the step.
     */
    private static final float SCROLL_EPSILON = 0.5f;

    /** @see #setDefaultDisplayMode */
    private static volatile DisplayMode defaultDisplayMode = DisplayMode.NATIVE_WINDOW;

    private final Menu rootMenu;
    private Runnable onClose = () -> { };
    // The arrow keys at the root column with nowhere to go: a MenuBar hooks these to walk to
    // the previous/next top-level menu (no-op for a standalone popup). Named for the sides
    // rather than for the keys because the key that reaches them is flipped in handleKey and
    // these are not: leading always means the previous menu, in either direction, and a second
    // flip out here would cancel the first one and walk the bar against its own submenus.
    private Runnable onRootLeading = () -> { };
    private Runnable onRootTrailing = () -> { };
    // Scene points the fullscreen in-scene overlay should let through to the content
    // beneath it: a MenuBar sets this to its own strip so its titles keep hover-
    // switching (and their cursor) while a dropdown is open, as in native mode.
    private java.util.function.BiPredicate<Float, Float> inScenePassThrough = (x, y) -> false;
    private boolean modal;
    /** Explicit step override; {@code null} inherits through the anchor / owner scene. */
    private ControlSize declaredSize;
    /**
     * The <b>one</b> resolved metric row for the whole cascade, captured at open time and never
     * re-read. Both sides of the geometry read this field: {@link Column}'s constructor, which
     * measures the labels and builds {@code top[]}/{@code hgt[]}, and
     * {@link MenuSurface#paintColumn}, which draws them. They used to read
     * {@code Theme.current().body} independently, which is a live bug the moment the two reads
     * can disagree: a column would measure at one step and paint at another.
     *
     * <p>Fixed at open on purpose: a menu is constructed, shown and discarded, so there is no
     * live re-size to handle, and a step change while open would have to invalidate the column
     * snapshots, which is the {@code Menu.modCount} path, and that must never be used for a
     * restyle (it means "the model mutated" and drops all deeper columns).
     */
    private SizeTokens tokens;

    /**
     * The <b>one</b> resolved layout direction for the whole cascade, captured at open time
     * beside {@link #tokens} and never re-read, because it has that field's lifetime and that
     * field's failure mode. Every column is placed against it ({@code positionRoot},
     * {@code positionSubmenu}), painted against it ({@link MenuSurface#paintColumn}) and
     * navigated against it ({@link MenuSurface#handleKey}); two reads that could disagree
     * inside one open menu would open a submenu on one side and draw its chevron on the other.
     *
     * <p>Resolved in {@link #beginOpen}, which is neither a constructor nor a field
     * initializer, and only after the host link is installed: a menu surface is parentless in
     * both presentations, so that link is the only path the direction has from the widget that
     * opened the menu. A test that opens with no host gets the process default, exactly as it
     * gets the process default step.
     *
     * <p>The initializer is a placeholder for the window between construction and open, not a
     * resolution: resolving a direction where this field is declared would capture it before
     * the host link exists, which is the one way to make it permanently wrong.
     */
    private LayoutDirection direction = LayoutDirection.LTR;

    /** Whether the open cascade reads right to left. */
    private boolean isRtl() {
        return direction == LayoutDirection.RTL;
    }

    private boolean open;
    private boolean inScene; // what this open actually did: an overlay instead of a native window
    /** What the application asked for. The platform may override it towards IN_SCENE, never away. */
    private DisplayMode requested = defaultDisplayMode;
    // In-scene overlay opacity: 1 in native mode (the window opacity does the fade);
    // animated 0→1 on open and 1→0 on close for the fullscreen fallback, which has no
    // separate window to fade, so it mirrors the native popup's fade in/out.
    private float sceneFade = 1f;
    private Scene owner;
    private MenuSurface surface;

    // native-window state
    private NativeWindow parentWindow;
    private NativeWindow popupWindow;
    private Scene popupScene;
    private Runnable dismissHandle;
    private Runnable blurHandle;
    private Runnable popupBlurHandle;
    private ScreenRect workArea;
    private float screenFactor = 1;
    private int workAreaScreenX;
    private int workAreaScreenY;
    // Last size/position applied to the popup window, so a highlight-only change
    // (same geometry) never re-issues a resize/move, which would flash on HiDPI.
    private int appliedW = -1;
    private int appliedH = -1;
    private int appliedX;
    private int appliedY;

    /** A popup presenting {@code menu}; show it with one of the {@code show*} methods. */
    public PopupMenu(Menu menu) {
        this.rootMenu = Objects.requireNonNull(menu, "menu");
    }

    // ------------------------------------------------------------------- API

    /** Runs {@code listener} when the menu closes (dismissed or an item chosen). */
    public PopupMenu onClose(Runnable listener) {
        this.onClose = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Hook for the arrow that walks toward the leading side at the root column (MenuBar: go to
     * the previous menu). That is LEFT reading left to right and RIGHT reading right to left;
     * the physical key is flipped once, inside this class, so a listener registered here means
     * "the previous menu" in either direction and must not flip again.
     */
    public PopupMenu onRootLeading(Runnable listener) {
        this.onRootLeading = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Hook for the arrow that walks toward the trailing side on a root item without a submenu
     * (MenuBar: the next menu). The trailing arrow is the one that opens a submenu where there
     * is one, which is why it is the one that walks on where there is not;
     * {@link #onRootLeading} states the direction rule both share.
     */
    public PopupMenu onRootTrailing(Runnable listener) {
        this.onRootTrailing = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * MenuBar-only: scene points the fullscreen in-scene overlay should NOT capture,
     * letting them reach the content beneath (the bar strip), so hovering a sibling
     * title still switches menus and shows its cursor, matching the native window
     * mode, where the separate popup window never covers the bar. No effect on the
     * native presentation. UI thread only.
     */
    void inScenePassThrough(java.util.function.BiPredicate<Float, Float> region) {
        this.inScenePassThrough = Objects.requireNonNull(region, "region");
    }

    /**
     * Makes the native popup <b>block the owner window</b> (window-modal), enforced
     * by the backend exactly like a {@link Dialog}: a press on the parent is ignored
     * and beeps, and the menu closes only on a choice or Escape. Default {@code false}
     * (a press outside dismisses). Purely native: no in-scene overlay.
     */
    public PopupMenu setModal(boolean value) {
        this.modal = value;
        return this;
    }

    /**
     * Pins the step of the whole cascade, overriding what the anchor or the owner scene would
     * give it, the escape hatch for "a compact context menu over a MEDIUM surface". Applied
     * when the menu opens; a call on an already-open menu is ignored, because the columns'
     * geometry snapshots were built from the row captured at open (see {@link #tokens}).
     * {@code null} restores inheritance. UI thread only.
     */
    public PopupMenu setControlSize(ControlSize size) {
        Ui.checkUiThread();
        this.declaredSize = size;
        return this;
    }

    /** Whether the cascade is currently showing. */
    public boolean isOpen() {
        return open;
    }

    /** @return the popup's native window while open (screenshots/tests), else null. */
    public NativeWindow popupWindow() {
        return popupWindow;
    }

    /**
     * Opens the menu with its corner at the scene point {@code (x, y)} (a context menu at the
     * pointer), sized at the step resolved on {@code anchor}. This is the overload apps should
     * use: {@code new PopupMenu(m).showAt(grid, event.x(), event.y())} makes a context menu over
     * an XSMALL data grid XSMALL, which the {@link Scene} overload cannot express.
     *
     * <p>The coordinates are the anchor's <em>scene</em> coordinates (what
     * {@link limn.scene.event.MouseEvent#x()} reports), not anchor-local ones.
     */
    public void showAt(Widget anchor, float x, float y) {
        showAnchored(anchor, x, y, 0, 0);
    }

    /** Opens the menu with its corner at the scene point {@code (x, y)}, at the scene's step. */
    public void showAt(Scene ownerScene, float x, float y) {
        showAnchored(ownerScene, x, y, 0, 0);
    }

    /**
     * Opens the menu attached to an anchor rectangle in {@code anchor}'s scene coordinates,
     * sized at the step resolved on {@code anchor}: it drops below the rect (a menu-bar
     * dropdown) and flips above when there is no room. A no-op for a widget outside a scene.
     * UI thread only.
     */
    public void showAnchored(Widget anchor, float ax, float ay, float aw, float ah) {
        Objects.requireNonNull(anchor, "anchor");
        show(anchor.scene(), anchor, ax, ay, aw, ah);
    }

    /**
     * Opens the menu attached to an anchor rectangle in scene coordinates. A no-op on a
     * headless scene (no display to place the window on). UI thread only.
     *
     * <p>The cascade takes the step resolved on the scene's <b>root</b>, the documented
     * fallback, and the difference between "a context menu over an XSMALL data grid is XSMALL"
     * and "it is whatever the scene says". Pass the widget it belongs to
     * ({@link #showAnchored(Widget, float, float, float, float)}) to get the former.
     */
    public void showAnchored(Scene ownerScene, float ax, float ay, float aw, float ah) {
        Objects.requireNonNull(ownerScene, "ownerScene");
        show(ownerScene, ownerScene.root(), ax, ay, aw, ah);
    }

    private void show(Scene ownerScene, Widget host, float ax, float ay, float aw, float ah) {
        Ui.checkUiThread();
        NativeWindow win = ownerScene == null ? null : ownerScene.window();
        if (open || rootMenu.isEmpty() || win == null || win.display() == null) {
            return; // native menus need a real window + display
        }
        open = true;
        this.owner = ownerScene;
        beginOpen(ownerScene.textRuler(), host);
        // Two platforms cannot hold a menu in a window of its own. macOS exclusive fullscreen
        // has no room for a second one (taking focus/click minimizes the fullscreen owner), and
        // Wayland has no way to put one where the anchor is: the cascade would open near the
        // middle of the display, wearing whatever frame the compositor decorates toplevels with.
        // Both fall back to an in-scene overlay drawn inside the owner window.
        if (requested == DisplayMode.IN_SCENE
                || win.isFullscreen() || !win.supportsAbsolutePositioning()) {
            presentInScene(ax, ay, aw, ah);
        } else {
            presentNative(ax, ay, aw, ah);
        }
    }

    /**
     * Builds the surface, links it to the size chain and resolves the cascade's row, in that
     * order, and <b>before</b> {@code pushRoot()}, because building the root column measures its
     * labels against {@code tokens.body()} and lays its rows out on {@code menuRowHeight}.
     *
     * <p>The surface is parentless in both presentations (a native popup scene's root, or an
     * overlay pushed with {@code pushOverlay}), so the tree walk cannot reach the anchor: the
     * host link is the only way the step <em>and the direction</em> get there, and it must be
     * installed before the geometry that sizes the native window is computed.
     */
    private void beginOpen(limn.graphics.TextRuler ruler, Widget host) {
        surface = new MenuSurface(ruler);
        if (declaredSize != null) {
            surface.setControlSize(declaredSize);
        }
        if (host != null) {
            surface.setInheritanceHost(host);
        }
        tokens = Theme.current().tokensFor(surface);
        // Both inherited axes are captured here, on the same line of the same method, because
        // they are read together by the same two halves of the geometry: the Column constructor
        // below measures against the row, and every placement and paint reads the direction.
        direction = surface.layoutDirection();
        surface.pushRoot();
    }

    /** Closes the menu (idempotent). UI thread only. */
    public void close() {
        Ui.checkUiThread();
        if (!open) {
            return;
        }
        open = false;
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
            NativeWindow closing = popupWindow;
            Scene closingScene = popupScene;
            NativeWindow parent = parentWindow;
            popupWindow = null;
            popupScene = null;
            Runnable destroy = () -> {
                if (modal && !closing.isClosed()) {
                    closing.backend().popModal(closing); // release the owner lock
                }
                if (parent != null && !parent.isClosed()) {
                    parent.unregisterChildPopup(closing);
                    if (modal && !parent.isModalBlocked()) {
                        parent.focus(); // re-activate the owner now the modal is gone
                    }
                }
                closing.requestClose();
            };
            if (closingScene != null) {
                closingScene.fadeWindowOut(Theme.current().animWindow, destroy);
            } else {
                destroy.run();
            }
        }
        if (inScene && owner != null) {
            inScene = false;
            Scene o = owner;
            MenuSurface s = surface;
            if (o.window() != null) {
                // Fade the overlay out, then remove it (restoring focus): the in-scene
                // twin of the native window's fade-out.
                // Wall time: this fade's last frame is what removes the overlay that is holding
                // input capture and focus. Frozen, the menu would stay open over a paused app.
                o.addRealTimeTicker(dt -> {
                    sceneFade = (float) Math.max(0, sceneFade - dt / Theme.current().animWindow);
                    s.invalidate();
                    if (sceneFade > 0) {
                        return true;
                    }
                    o.removeOverlay(s); // restores focus to what had it before
                    return false;
                });
            } else {
                o.removeOverlay(s); // headless: no frame pump, remove at once
            }
        }
        onClose.run();
    }

    // --------------------------------------------------- in-scene fallback

    /** Fullscreen fallback: draw the menu as an overlay inside the owner scene (no window). */
    private void presentInScene(float ax, float ay, float aw, float ah) {
        inScene = true;
        surface.fillScene = true;            // bounds track the scene (set in onLayout)
        // Zero bounds = "unknown until onLayout installs the scene size"; Column.fit reads it
        // that way and leaves the column at its natural size instead of shrinking it to nothing.
        surface.configure(ax, ay, aw, ah, 0, 0);
        surface.setRenderOffset(0, 0);
        // There is no separate window here, so the overlay content itself fades in,
        // matching the native popup's compositor fade. Headless scenes (tests) have no
        // frame pump to advance the ticker, so they snap straight to fully shown.
        boolean animate = owner.window() != null;
        sceneFade = animate ? 0f : 1f;
        owner.pushOverlay(surface);          // captures input + focuses the surface
        if (animate) {
            owner.addRealTimeTicker(dt -> { // wall time, like its fade-out twin
                if (!open) {
                    return false; // closed mid-fade: let the fade-out ticker take over
                }
                sceneFade = (float) Math.min(1, sceneFade + dt / Theme.current().animWindow);
                surface.invalidate();
                return sceneFade < 1;
            });
        }
    }

    // ----------------------------------------------------------- native window

    private void presentNative(float ax, float ay, float aw, float ah) {
        parentWindow = owner.window();
        workArea = parentWindow.display().workArea();
        screenFactor = parentWindow.logicalToScreenFactor();
        workAreaScreenX = workArea.x();
        workAreaScreenY = workArea.y();
        // Anchor: scene point → screen → work-area-relative logical coordinates.
        float screenAx = parentWindow.screenX() + ax * screenFactor;
        float screenAy = parentWindow.screenY() + ay * screenFactor;
        float boundsW = workArea.width() / screenFactor;
        float boundsH = workArea.height() / screenFactor;
        surface.configure(
                (screenAx - workAreaScreenX) / screenFactor,
                (screenAy - workAreaScreenY) / screenFactor,
                aw, ah, boundsW, boundsH);
        // Defer window creation: it switches the GL context, which must not happen
        // inside another window's frame callback. The outside-press dismiss is
        // armed there too, after the opening press has finished dispatching, so
        // that very click does not immediately close the menu.
        Ui.post(this::createNativeWindow);
    }

    private void createNativeWindow() {
        if (!open || popupWindow != null || parentWindow == null || parentWindow.isClosed()) {
            return;
        }
        surface.reposition();
        float[] box = surface.boundingBox();
        int w = Math.max(1, (int) Math.ceil(box[2] - box[0]));
        int h = Math.max(1, (int) Math.ceil(box[3] - box[1]));
        surface.setRenderOffset(box[0], box[1]);

        popupWindow = parentWindow.backend().createWindow(new WindowConfig(
                "menu", w, h, false, false, false, true, true, true)); // undecorated, floating, transparent, focus-stealing
        parentWindow.registerChildPopup(popupWindow);
        popupScene = new Scene(surface);
        popupScene.inheritRenderingFlags(owner); // partial/debug follow the owner window
        popupScene.bind(popupWindow);
        popupScene.setBackground(Color.TRANSPARENT);

        appliedW = w;
        appliedH = h;
        appliedX = workAreaScreenX + Math.round(box[0] * screenFactor);
        appliedY = workAreaScreenY + Math.round(box[1] * screenFactor);
        popupWindow.setScreenPosition(appliedX, appliedY);
        if (parentWindow.isVisible()) {
            popupScene.fadeWindowIn(Theme.current().animWindow);
            popupWindow.show();
        }
        if (modal) {
            // Backend-enforced window-modal (like Dialog): the owner ignores input
            // and beeps on a click; the menu closes only on a choice or Escape.
            parentWindow.backend().pushModal(popupWindow, parentWindow);
        }
        popupScene.focusTraverse(false); // focus the surface so it gets keys
        popupWindow.requestFrame();
        if (!modal) {
            // Non-modal: a press back in the owner window dismisses the menu (the
            // owner is not blocked, so it receives the press).
            dismissHandle = owner.observePresses(target -> close());
            // A press in another window/app never reaches observePresses: dismiss
            // when OS focus leaves the owner/menu pair, or the always-on-top menu
            // floats over foreign applications indefinitely.
            blurHandle = owner.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
            popupBlurHandle = popupScene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        }
    }

    /**
     * Deferred one event-loop turn so the open-moment handover settles (the
     * focus-stealing menu window blurs its owner as it opens): the menu
     * survives while OS focus is on the owner window or on the menu itself,
     * and dismisses when it moved anywhere else.
     */
    private void closeUnlessRefocused() {
        if (!open) {
            return;
        }
        boolean ownerFocused = owner != null && owner.isWindowFocused();
        boolean menuFocused = popupScene != null && popupScene.isWindowFocused();
        if (!ownerFocused && !menuFocused) {
            close();
        }
    }

    /**
     * Native mode: re-fit the window to the (already repositioned) cascade after a
     * submenu opened/closed. A move/resize is issued <b>only when the geometry
     * actually changed</b>: a highlight move keeps the same bounds and just
     * repaints, so it never triggers a resize (and its HiDPI stretch flash).
     */
    private void refitNativeWindow() {
        if (popupWindow == null) {
            return;
        }
        float[] box = surface.boundingBox();
        int w = Math.max(1, (int) Math.ceil(box[2] - box[0]));
        int h = Math.max(1, (int) Math.ceil(box[3] - box[1]));
        int x = workAreaScreenX + Math.round(box[0] * screenFactor);
        int y = workAreaScreenY + Math.round(box[1] * screenFactor);
        surface.setRenderOffset(box[0], box[1]);
        boolean moved = x != appliedX || y != appliedY;
        boolean resized = w != appliedW || h != appliedH;
        if (moved) {
            popupWindow.setScreenPosition(x, y);
            appliedX = x;
            appliedY = y;
        }
        if (resized) {
            popupWindow.setSize(w, h); // backend presents the new size once its drawable settles
            appliedW = w;
            appliedH = h;
        }
        if (!resized) {
            popupWindow.requestFrame(); // geometry unchanged → just repaint
        }
    }

    // ------------------------------------------------------- test hooks (pkg)
    // The native presentation needs a real window; these drive the surface's
    // layout/input directly (in layout space, offset 0) so behavior and the
    // flip/clamp geometry can be tested headlessly.

    /**
     * Builds the surface and lays it out against {@code (boundsW, boundsH)} at anchor
     * {@code (ax, ay, aw, ah)}. No scene and no anchor, so the cascade resolves to
     * {@link #setControlSize} or the process default, which is what
     * {@link #tokensForTest()} reports.
     */
    void showForTest(TextRuler ruler, float ax, float ay, float aw, float ah, float boundsW, float boundsH) {
        open = true;
        beginOpen(ruler, null);
        surface.configure(ax, ay, aw, ah, boundsW, boundsH);
        surface.reposition();
    }

    /**
     * @return whether the open menu chose the in-scene presentation, the only difference a
     *         platform fallback makes that a test can see from outside, since both presentations
     *         answer {@link #isOpen()} the same way
     */
    boolean isInSceneForTest() {
        return inScene;
    }

    /**
     * Asks for a presentation; see {@link DisplayMode}. Default {@link DisplayMode#NATIVE_WINDOW},
     * which is what the platform's own menus do wherever a window can be placed at an anchor.
     *
     * <p><b>A preference, not a guarantee</b>, and only in one direction: a menu asked for
     * {@code NATIVE_WINDOW} still draws in scene on Wayland and in macOS exclusive fullscreen,
     * because on those a menu window does not land where the anchor is. {@code IN_SCENE} is always
     * honoured; it needs nothing from the platform. Read {@link #displayMode()} for what
     * happened.
     *
     * <p>Takes effect on the next open; a menu already on screen is not re-presented under it.
     */
    public PopupMenu setDisplayMode(DisplayMode mode) {
        this.requested = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * The presentation every {@code PopupMenu} built after this call starts from, including the
     * ones the toolkit builds where an application never sees the object: {@link MenuBar}'s
     * dropdowns, {@link ContextMenus}, and a text field's own context menu.
     *
     * <p>This exists because the choice is almost never per-menu. An application either shows its
     * popups in windows or it does not: because everything it draws has to be inside one window
     * for a screen share, because it is being screenshotted for its own documentation, or because
     * it is a kiosk with no desktop behind it to float over. Setting that per call site means
     * finding every call site, including the ones inside this toolkit.
     *
     * <p>Process-wide, like {@link Theme#setCurrent} and {@link limn.graphics.Fonts}. Menus
     * already open are unaffected, and {@link #setDisplayMode} still overrides it for one menu.
     * UI thread.
     */
    public static void setDefaultDisplayMode(DisplayMode mode) {
        defaultDisplayMode = Objects.requireNonNull(mode, "mode");
    }

    /** @return the presentation new menus start from. */
    public static DisplayMode defaultDisplayMode() {
        return defaultDisplayMode;
    }

    /**
     * @return how the menu is presented: while it is open, what it actually chose, including a
     *         platform forcing {@code IN_SCENE} over a request for a window. While it is closed,
     *         what {@link #setDisplayMode} last asked for, since nothing has been decided yet.
     */
    public DisplayMode displayMode() {
        return open && inScene ? DisplayMode.IN_SCENE : requested;
    }

    /** Drives the in-scene (fullscreen fallback) presentation directly on a real scene. */
    void showInSceneForTest(Scene ownerScene, float ax, float ay, float aw, float ah) {
        open = true;
        owner = ownerScene;
        beginOpen(ownerScene.textRuler(), ownerScene.root());
        presentInScene(ax, ay, aw, ah);
    }

    /** @return the row the open cascade was built from, the numbers a test must assert against. */
    SizeTokens tokensForTest() {
        return tokens;
    }

    void keyForTest(int key) {
        surface.handleKey(key, 0);
    }

    /** @return whether the surface handled (and would have consumed) the key */
    boolean keyForTest(int key, int modifiers) {
        return surface.handleKey(key, modifiers);
    }

    /** @return the accelerator hint column {@code c} drew for item {@code i}, or null. */
    String accelTextForTest(int c, int i) {
        return surface.cols.get(c).accel[i];
    }

    void moveForTest(float lx, float ly) {
        surface.moveAt(lx, ly);
    }

    void clickForTest(float lx, float ly) {
        surface.clickAt(lx, ly);
    }

    void pressForTest(float lx, float ly) {
        surface.pressAt(lx, ly);
    }

    int columnCountForTest() {
        return surface == null ? 0 : surface.cols.size();
    }

    /** @return {x, y, w, h} of column {@code c} in layout space. */
    float[] columnRectForTest(int c) {
        Column col = surface.cols.get(c);
        return new float[]{col.x, col.y, col.w, col.h};
    }

    int highlightForTest(int c) {
        return surface.cols.get(c).highlight;
    }

    float columnScrollForTest(int c) {
        return surface.cols.get(c).scroll;
    }

    float columnVisibleHeightForTest(int c) {
        return surface.cols.get(c).visibleH;
    }

    // ========================================================== the surface

    /**
     * Renders the whole open cascade (root column plus any open submenu columns),
     * positioning each with flip + clamp against its bounds, and handling
     * hover/click and keyboard navigation. Serves both roles: the root of the
     * native popup window's scene (bounds = monitor work area, a render offset maps
     * into the window sized to the cascade), and ({@link #fillScene}) an in-scene
     * overlay for the fullscreen fallback (bounds = the scene, no offset).
     *
     * <p>It paints text but deliberately declares <b>no</b> {@code baselineOffset()}: it is a
     * parentless surface (a scene root or an overlay), never a child of a {@code Flex}, and its
     * "first baseline" (the first row of a column that may have been flipped above the anchor
     * and scrolled) is not a meaningful alignment reference for anything.
     */
    private final class MenuSurface extends Widget {

        private final TextRuler ruler;
        private final List<Column> cols = new ArrayList<>();
        private final Path2D arrow = new Path2D();

        // Layout space: the monitor work area (native) or the test bounds.
        private float boundsX;
        private float boundsY;
        private float boundsW;
        private float boundsH;
        private float anchorX;
        private float anchorY;
        private float anchorW;
        private float anchorH;
        private float offsetX; // layout-space → window-local
        private float offsetY;
        // Fullscreen fallback: this surface is a scene overlay, so its bounds are
        // the scene (tracked from the widget size) and the offset is zero.
        boolean fillScene;

        MenuSurface(TextRuler ruler) {
            this.ruler = ruler;
            setFocusable(true); // receive keyboard while open
        }

        void pushRoot() {
            cols.clear();
            Column root = new Column(rootMenu, -1);
            root.highlight = root.firstSelectable();
            cols.add(root);
        }

        void configure(float ax, float ay, float aw, float ah, float bW, float bH) {
            this.boundsX = 0;
            this.boundsY = 0;
            this.boundsW = bW;
            this.boundsH = bH;
            this.anchorX = ax;
            this.anchorY = ay;
            this.anchorW = aw;
            this.anchorH = ah;
        }

        void setRenderOffset(float ox, float oy) {
            this.offsetX = ox;
            this.offsetY = oy;
        }

        // ------------------------------------------------------------ layout

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            if (fillScene) {
                // In-scene overlay: bounds = the whole scene, no offset (render in
                // scene coordinates). Tracks the scene size across resizes.
                boundsX = 0;
                boundsY = 0;
                boundsW = width();
                boundsH = height();
                offsetX = 0;
                offsetY = 0;
            }
            reposition(); // native: bounds are the fixed work area; the offset maps into the window
        }

        /** Positions every open column (flip then clamp) against the bounds. */
        void reposition() {
            for (int c = 0; c < cols.size(); c++) {
                Column col = cols.get(c);
                if (c == 0) {
                    positionRoot(col);
                } else {
                    positionSubmenu(cols.get(c - 1), col);
                }
            }
        }

        private void positionRoot(Column col) {
            col.fit(boundsW, boundsH); // wider/taller than the bounds → shrink + scroll
            boolean rtl = isRtl();
            // The column's corner sits at the anchor's LEADING edge, which is the anchor's left
            // reading left to right and its right reading right to left; a context menu, whose
            // anchor is the pointer with no width at all, therefore extends to the left of the
            // pointer in a right-to-left interface, the way a platform one does.
            // The two placements swap under a mirror rather than one of them changing: the
            // fallback is always "align to the anchor's other edge", and it is reached when the
            // preferred one has run out of bounds on the side it grows toward.
            float leading = rtl ? anchorX + anchorW - col.w : anchorX;
            float trailing = rtl ? anchorX : anchorX + anchorW - col.w;
            boolean overflows = rtl ? leading < boundsX : leading + col.w > boundsX + boundsW;
            col.x = clamp(overflows ? trailing : leading, boundsX, boundsX + boundsW - col.w);

            float y = anchorY + anchorH; // drop below the anchor
            if (y + col.visibleH > boundsY + boundsH) {
                float above = anchorY - col.visibleH; // flip above
                y = above >= boundsY ? above : boundsY + boundsH - col.visibleH;
            }
            col.y = clamp(y, boundsY, boundsY + boundsH - col.visibleH);
            col.clampScroll();
        }

        private void positionSubmenu(Column parent, Column col) {
            col.fit(boundsW, boundsH);
            float parentItemTop = parent.y + parent.top[col.parentItem] - parent.scroll;
            boolean rtl = isRtl();
            // A submenu opens toward the TRAILING side first — right reading left to right,
            // left reading right to left — and falls back to the leading side when there is no
            // room, which is the direction the chevron in the parent row points either way.
            // The overlap hides the seam between the two columns' 1pt borders: exactly twice
            // the border weight, so it is locked rather than tabled, and it keeps the same
            // magnitude on whichever edge the two columns meet at.
            float trailing = rtl
                    ? parent.x - col.w + Strokes.SUBMENU_OVERLAP
                    : parent.x + parent.w - Strokes.SUBMENU_OVERLAP;
            float leading = rtl
                    ? parent.x + parent.w - Strokes.SUBMENU_OVERLAP
                    : parent.x - col.w + Strokes.SUBMENU_OVERLAP;
            boolean overflows = rtl ? trailing < boundsX : trailing + col.w > boundsX + boundsW;
            col.x = clamp(overflows ? leading : trailing, boundsX, boundsX + boundsW - col.w);

            // Align its first row with the parent item: SUBMENU_Y_ALIGN follows menuPadV and
            // has no token of its own.
            float y = parentItemTop - tokens.menuPadV();
            if (y + col.visibleH > boundsY + boundsH) {
                y = boundsY + boundsH - col.visibleH;
            }
            col.y = clamp(y, boundsY, boundsY + boundsH - col.visibleH);
            col.clampScroll();
        }

        /**
         * Clamps into {@code [lo, hi]}. The {@code hi < lo} branch is now a pure backstop for a
         * degenerate (zero-sized) bounds: {@link Column#fit} shrinks the column to the bounds
         * first, so a column that does not fit is made to fit instead of being pinned at
         * {@code lo} and left overflowing.
         */
        private float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi < lo ? lo : hi, v));
        }

        /** @return {minX, minY, maxX, maxY} over all open columns (layout space). */
        float[] boundingBox() {
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (Column col : cols) {
                minX = Math.min(minX, col.x);
                minY = Math.min(minY, col.y);
                maxX = Math.max(maxX, col.x + col.w);
                maxY = Math.max(maxY, col.y + col.visibleH);
            }
            return new float[]{minX, minY, maxX, maxY};
        }

        // ------------------------------------------------------------- paint

        @Override
        protected void onPaint(Canvas canvas) {
            if (columnsStale()) {
                // A window refit must not run mid-frame: rebuild right after this
                // frame; painting below is bounds-clamped so the stale snapshot
                // cannot index past the live item list.
                Ui.post(this::rebuildStaleColumns);
            }
            // In-scene fallback fades the whole cascade via the canvas opacity; the
            // native popup fades through its window opacity, so it paints solid here.
            boolean fading = fillScene && sceneFade < 1f;
            if (fading) {
                canvas.save();
                canvas.setOpacity(sceneFade);
            }
            for (Column col : cols) {
                paintColumn(canvas, col);
            }
            if (fading) {
                canvas.restore();
            }
        }

        private void paintColumn(Canvas canvas, Column col) {
            Theme theme = Theme.current();
            SizeTokens t = tokens; // the row the columns were BUILT from, never re-resolved here
            // And the direction the columns were PLACED with, for the same reason: a row whose
            // gutters were drawn against a direction the cascade was not positioned with would
            // put the check on the side the label starts from.
            boolean rtl = isRtl();
            float x = col.x - offsetX;
            float y = col.y - offsetY;
            float inset = Strokes.HALF_PIXEL_INSET;
            canvas.fillRoundRect(x, y, col.w, col.visibleH, t.radiusMedium(),
                    theme.surfaceRaised.withAlpha(0.98f));
            canvas.drawRoundRect(x + inset, y + inset, col.w - 2 * inset, col.visibleH - 2 * inset,
                    t.radiusMedium(), Strokes.BORDER, theme.outline);

            Font font = t.body();
            TextMetrics fm = ruler.measure("Hg", font);
            List<MenuItem> items = col.menu.items();
            canvas.save();
            // Rows stay off the border: the clip inset IS the border width.
            canvas.clipRect(x + Strokes.ROW_CLIP, y + Strokes.ROW_CLIP,
                    col.w - 2 * Strokes.ROW_CLIP, col.visibleH - 2 * Strokes.ROW_CLIP);
            // The geometry snapshot may momentarily lag a live-mutated Menu
            // (rebuild is already scheduled): never index past either side.
            int rows = Math.min(items.size(), col.top.length);
            for (int i = 0; i < rows; i++) {
                MenuItem item = items.get(i);
                float iy = y + col.top[i] - col.scroll;
                if (iy + col.hgt[i] < y || iy > y + col.visibleH) {
                    continue; // scrolled out of the visible band
                }
                if (item.isSeparator()) {
                    // No half-pixel snap here, deliberately (a request for one was refused).
                    // Two reasons. (1) It would be a no-op on the term it targets: separatorBox
                    // is ODD at every step and menuPadV / menuRowHeight are integral, so top[i]
                    // is whole and hgt[i] / 2 contributes exactly the .5 a centred 1pt rule
                    // wants. (2) It would be wrong even if it weren't. The origin this row is
                    // measured from (col.y, clamped from a fractional pointer anchor, minus the
                    // render offset) is not device-aligned, and at contentScale 1.25/1.5/2 a
                    // half POINT is not a half PIXEL: a snap in point space would push the
                    // rule OFF the device grid at exactly the scales where crispness is at
                    // stake. The backend already places stroke centrelines on the right device
                    // half-pixel (Snapping.snapCenter), so a second, coarser snap here can only
                    // fight it. The one point-space correction this file makes is
                    // HALF_PIXEL_INSET on the border, and that is measured from the column's
                    // own box, not from the scene.
                    float sepY = iy + col.hgt[i] / 2;
                    canvas.drawLine(x + t.menuSepInsetX(), sepY, x + col.w - t.menuSepInsetX(),
                            sepY, Strokes.HAIRLINE, theme.outline);
                    continue;
                }
                float rowH = col.hgt[i];
                boolean active = i == col.highlight && item.isSelectable();
                if (active) {
                    canvas.fillRoundRect(x + t.menuHiliteInsetX(), iy + t.menuHiliteInsetY(),
                            col.w - 2 * t.menuHiliteInsetX(), rowH - 2 * t.menuHiliteInsetY(),
                            t.radiusSmall(), theme.primary.withAlpha(0.28f));
                }
                Color ink = item.isEnabled() ? theme.text : theme.disabledText;
                if (item.kind() == MenuItem.Kind.CHECK && item.isChecked()) {
                    // The check sits in the leading gutter. The tick itself is NOT mirrored —
                    // no platform mirrors a check mark — so only its box moves, and because
                    // paintCheck draws rightwards from the x it is given, the mirrored gutter
                    // has to be handed its LEFT edge rather than its leading one.
                    float checkX = rtl
                            ? x + col.w - t.menuCheckInset() - t.checkGlyphW()
                            : x + t.menuCheckInset();
                    paintCheck(canvas, checkX, iy + rowH / 2,
                            item.isEnabled() ? theme.primary : theme.disabledText, t);
                }
                float baseline = iy + (rowH - fm.height()) / 2 + fm.ascent();
                // drawText places a run's LEFT edge for either base direction (a right-to-left
                // line fills the same box from the other end), so a leading-aligned label in a
                // mirrored column needs its own width to find that edge. That width is the
                // column's snapshot, measured with the call the paint draws through: measuring
                // here instead would run for every visible row of every column of every frame.
                float labelX = rtl
                        ? x + col.w - t.menuCheckGutter() - col.labelW[i]
                        : x + t.menuCheckGutter();
                canvas.drawText(item.label(), labelX, baseline, font, ink);
                // The rule takes both its edges off the line's own shaping and copes with a
                // right-to-left run itself, so the mirrored left edge is all it needs.
                MenuInk.underlineMnemonic(canvas, ruler, item.label(), item.mnemonicIndex(),
                        labelX, baseline, font, ink);
                // The hint is aligned against the SAME trailing margin the submenu arrow uses,
                // so the two kinds of row end at one edge, and an item can carry only one of
                // them, so they can never collide inside a row. Only the placement mirrors: the
                // string names physical keys and is never reversed.
                String accel = col.accel[i];
                if (accel != null) {
                    float accelW = ruler.measure(accel, font).width();
                    float accelX = rtl
                            ? x + t.menuArrowGutter()
                            : x + col.w - t.menuArrowGutter() - accelW;
                    canvas.drawText(accel, accelX, baseline, font,
                            item.isEnabled() ? theme.textMuted : theme.disabledText);
                }
                if (item.hasSubmenu()) {
                    // The chevron sits in the trailing gutter and must point at the side the
                    // submenu actually opens toward, so the gutter and the nudge mirror
                    // together with the glyph.
                    float arrowX = rtl
                            ? x + t.menuArrowGutter() - t.menuArrowNudge()
                            : x + col.w - t.menuArrowGutter() + t.menuArrowNudge();
                    paintArrow(canvas, arrowX, iy + rowH / 2, ink, t, rtl);
                }
            }
            // Scroll affordances: chevrons over a small band at the clamped edges. The band is
            // a control height (locked), and its 1pt insets are the border width.
            if (col.scroll > SCROLL_EPSILON) {
                paintScrollHint(canvas, col, x, y + Strokes.ROW_CLIP, true, t);
            }
            if (col.scroll < col.maxScroll() - SCROLL_EPSILON) {
                paintScrollHint(canvas, col, x,
                        y + col.visibleH - Strokes.MENU_SCROLL_HINT_H - Strokes.ROW_CLIP, false, t);
            }
            canvas.restore();
        }

        /**
         * A subtle "more items this way" band: panel-colored fill + chevron. The band's height
         * is locked (it is a <em>control</em> that intercepts clicks before item activation,
         * and below ~10 pt the scroll/activate boundary is unaimable), while the chevron inside
         * it is a glyph and scales, its half-height held at half its half-width so the arrow
         * angle is the same at every step.
         */
        private void paintScrollHint(Canvas canvas, Column col, float x, float bandY, boolean up,
                                    SizeTokens t) {
            Theme theme = Theme.current();
            canvas.fillRect(x + Strokes.ROW_CLIP, bandY, col.w - 2 * Strokes.ROW_CLIP,
                    Strokes.MENU_SCROLL_HINT_H, theme.surfaceRaised.withAlpha(0.92f));
            float cx = x + col.w / 2;
            float cy = bandY + Strokes.MENU_SCROLL_HINT_H / 2;
            float s = t.scrollChevronHalf();
            if (up) {
                canvas.drawLine(cx - s, cy + s / 2, cx, cy - s / 2, Strokes.ARROW_PEN, theme.textMuted);
                canvas.drawLine(cx, cy - s / 2, cx + s, cy + s / 2, Strokes.ARROW_PEN, theme.textMuted);
            } else {
                canvas.drawLine(cx - s, cy - s / 2, cx, cy + s / 2, Strokes.ARROW_PEN, theme.textMuted);
                canvas.drawLine(cx, cy + s / 2, cx + s, cy - s / 2, Strokes.ARROW_PEN, theme.textMuted);
            }
        }

        /**
         * The tick, drawn from its left edge at {@code cx}. Its path offsets are the
         * MEDIUM literals scaled by {@code checkGlyphW / 9}, exact at MEDIUM, and the extent is
         * the only thing that moves: the pen is locked, which is the whole size-vs-weight point.
         *
         * <p><b>A check mark does not mirror</b>, on any platform, so this takes no direction and
         * must never grow one: what mirrors is the gutter it is placed in, at the call site. It
         * is asymmetric ink drawn with {@code drawLine} rather than a path, which is worth
         * saying here so a later sweep of the mirrored chevron does not collect it too.
         */
        private void paintCheck(Canvas canvas, float cx, float cy, Color color, SizeTokens t) {
            float k = t.checkGlyphW() / 9f;
            canvas.drawLine(cx, cy + k, cx + 3.5f * k, cy + 4.5f * k, Strokes.MENU_CHECK_PEN, color);
            canvas.drawLine(cx + 3.5f * k, cy + 4.5f * k, cx + 9 * k, cy - 4 * k,
                    Strokes.MENU_CHECK_PEN, color);
        }

        /**
         * The submenu chevron: {@code menuArrowW} wide, {@code menuArrowH} tall, locked pen.
         *
         * <p>The one piece of ink in this toolkit that mirrors, and it is a sign flip on the two
         * horizontal offsets rather than a transform: the glyph means "the submenu is that way",
         * so it has to point at the side the submenu opens toward. The tick beside it does not
         * mirror, which is the difference between a mark that names a direction and one that
         * does not.
         */
        private void paintArrow(Canvas canvas, float cx, float cy, Color color, SizeTokens t,
                boolean rtl) {
            float sign = rtl ? -1 : 1;
            float back = sign * 2 * t.menuArrowW() / 5f; // 2 of the 5pt width sits behind cx at MEDIUM
            float tip = sign * 3 * t.menuArrowW() / 5f;
            float half = t.menuArrowH() / 2;
            arrow.reset();
            arrow.moveTo(cx - back, cy - half).lineTo(cx + tip, cy).lineTo(cx - back, cy + half);
            canvas.drawPath(arrow, Strokes.ARROW_PEN, color);
        }

        // ------------------------------------------------------------- input

        /** @return {columnIndex, itemIndex} at the layout-space point, or null when outside every column. */
        private int[] hit(float lx, float ly) {
            for (int c = cols.size() - 1; c >= 0; c--) { // topmost (deepest) column wins
                Column col = cols.get(c);
                if (lx >= col.x && lx < col.x + col.w && ly >= col.y && ly < col.y + col.visibleH) {
                    int item = col.itemAt(ly - col.y);
                    return new int[]{c, item};
                }
            }
            return null;
        }

        /** Scrolls {@code col} by {@code dy}, keeping any open submenu anchored. */
        private void scrollColumn(Column col, float dy) {
            float before = col.scroll;
            col.scroll = before + dy;
            col.clampScroll();
            if (col.scroll == before) {
                return;
            }
            // A submenu is anchored to a parent ROW: scrolling moved that row,
            // so the cascade must re-fit or the child floats at the old spot.
            int index = cols.indexOf(col);
            if (index >= 0 && index + 1 < cols.size()) {
                reposition();
                invalidate(); // cascade moved: repaint it all
                refitNativeWindow();
            } else {
                // Only this column's rows moved: damage just its rect.
                invalidate(col.x - offsetX, col.y - offsetY, col.w, col.visibleH);
            }
            if (popupWindow != null) {
                popupWindow.requestFrame();
            }
        }

        /**
         * @return the scroll direction when the point sits on a visible
         *         scroll-hint band of {@code col} (matching the paint
         *         conditions), else 0; the bands are controls, not veneers.
         */
        private int hintBandDirection(Column col, float ly) {
            if (col.maxScroll() <= 0) {
                return 0;
            }
            float local = ly - col.y;
            if (col.scroll > SCROLL_EPSILON && local < Strokes.MENU_SCROLL_HINT_H) {
                return -1;
            }
            if (col.scroll < col.maxScroll() - SCROLL_EPSILON
                    && local > col.visibleH - Strokes.MENU_SCROLL_HINT_H) {
                return 1;
            }
            return 0;
        }

        /** @return the column under the layout-space point (even between items), or null. */
        private Column columnAt(float lx, float ly) {
            for (int c = cols.size() - 1; c >= 0; c--) {
                Column col = cols.get(c);
                if (lx >= col.x && lx < col.x + col.w && ly >= col.y && ly < col.y + col.visibleH) {
                    return col;
                }
            }
            return null;
        }

        @Override
        protected boolean overlayPassesPointer(float sceneX, float sceneY) {
            // Fullscreen fallback only: yield points over the MenuBar strip (never
            // over one of our columns) so the bar keeps hover-switching + its cursor.
            if (!fillScene || !inScenePassThrough.test(sceneX, sceneY)) {
                return false;
            }
            float lx = sceneToLocalX(sceneX) + offsetX;
            float ly = sceneToLocalY(sceneY) + offsetY;
            return hit(lx, ly) == null;
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            rebuildStaleColumns(); // never hit-test against a live-mutated Menu
            float lx = sceneToLocalX(event.x()) + offsetX;
            float ly = sceneToLocalY(event.y()) + offsetY;
            switch (event.type()) {
                case MOVE, ENTER, DRAG -> moveAt(lx, ly);
                case WHEEL -> {
                    Column col = columnAt(lx, ly);
                    if (col != null && col.maxScroll() > 0 && event.scrollY() != 0) {
                        // A wheel detent is a device unit: the same flick travels the same
                        // distance in a dense menu and a roomy one (~2.0 rows at XSMALL,
                        // ~1.7 at MEDIUM, ~1.2 at XLARGE).
                        scrollColumn(col, -event.scrollY() * Strokes.WHEEL_STEP);
                        event.consume();
                    }
                }
                case PRESS -> {
                    pressAt(lx, ly);
                    event.consume();
                }
                case CLICK -> {
                    clickAt(lx, ly);
                    event.consume();
                }
                default -> {
                }
            }
        }

        /**
         * Whether any column's geometry snapshot no longer matches its {@link Menu}.
         *
         * <p>Model mutation is the whole of this key, and the layout direction deliberately does
         * not join it: the cascade's direction is captured once at open (see
         * {@link PopupMenu#direction}), so the numbers a column holds — {@code contentW}, the
         * accelerator strings, {@code labelW} — cannot have been taken under a direction the
         * paint disagrees with. They are also measured through the direction-blind two-argument
         * ruler, so a change of direction would not move them even if one could arrive.
         */
        private boolean columnsStale() {
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).builtModCount != cols.get(i).menu.modCount()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Rebuilds the first column whose {@link Menu} was mutated while open
         * (the async-populated "recent files" pattern): its geometry snapshot no
         * longer matches the live item list. Deeper columns are dropped (their
         * parent-item indexes may have shifted), and the cascade is re-fitted.
         */
        private void rebuildStaleColumns() {
            if (!open) {
                return;
            }
            for (int i = 0; i < cols.size(); i++) {
                Column col = cols.get(i);
                if (col.builtModCount == col.menu.modCount()) {
                    continue;
                }
                while (cols.size() > i + 1) {
                    cols.remove(cols.size() - 1);
                }
                Column rebuilt = new Column(col.menu, col.parentItem);
                rebuilt.highlight = Math.min(col.highlight, rebuilt.top.length - 1);
                rebuilt.scroll = col.scroll;
                cols.set(i, rebuilt);
                reposition();
                rebuilt.clampScroll();
                refitNativeWindow();
                invalidate();
                return;
            }
        }

        /** Hover in layout space: highlight (and open the submenu of) the item under the pointer. */
        void moveAt(float lx, float ly) {
            int[] target = hit(lx, ly);
            if (target != null && target[1] >= 0
                    && hintBandDirection(cols.get(target[0]), ly) == 0) {
                hoverItem(target[0], target[1]);
            }
        }

        /** Press in layout space: a press outside every column dismisses the menu. */
        void pressAt(float lx, float ly) {
            if (hit(lx, ly) == null) {
                close();
            }
        }

        /** Click in layout space: choose (or open the submenu of) the item under the pointer. */
        void clickAt(float lx, float ly) {
            int[] target = hit(lx, ly);
            if (target == null) {
                return;
            }
            Column col = cols.get(target[0]);
            int direction = hintBandDirection(col, ly);
            if (direction != 0) {
                // Clicking the "more items" chevron steps the scroll; it must
                // never activate the mostly-hidden item painted beneath it.
                scrollColumn(col, direction * Strokes.WHEEL_STEP);
                return;
            }
            if (target[1] >= 0) {
                chooseItem(target[0], target[1]);
            }
        }

        private void hoverItem(int c, int i) {
            MenuItem item = cols.get(c).menu.items().get(i);
            if (!item.isSelectable()) {
                return;
            }
            Column col = cols.get(c);
            if (c == cols.size() - 1 && !item.hasSubmenu()) {
                // Pure highlight move in the deepest column: the cascade keeps
                // its bounds: repaint just the two affected rows (partial
                // rendering must not flash the whole menu on every hover).
                if (col.highlight != i) {
                    damageRow(col, col.highlight);
                    col.highlight = i;
                    damageRow(col, i);
                    if (popupWindow != null) {
                        popupWindow.requestFrame();
                    }
                }
                return;
            }
            truncateTo(c);
            col.highlight = i;
            if (item.hasSubmenu()) {
                openSubmenu(c, i);
            }
            changed();
        }

        /** Damages one item row (surface coordinates), the partial-rendering hover path. */
        private void damageRow(Column col, int i) {
            if (i >= 0 && i < col.top.length && i < col.hgt.length) {
                invalidate(col.x - offsetX, col.y - offsetY + col.top[i] - col.scroll,
                        col.w, col.hgt[i]);
            }
        }

        private void chooseItem(int c, int i) {
            MenuItem item = cols.get(c).menu.items().get(i);
            if (!item.isSelectable()) {
                return;
            }
            if (item.hasSubmenu()) {
                truncateTo(c);
                cols.get(c).highlight = i;
                openSubmenu(c, i);
                changed();
            } else {
                item.activate();
                close();
            }
        }

        /** Drops every column deeper than {@code c} (mutates only). */
        private void truncateTo(int c) {
            while (cols.size() > c + 1) {
                cols.remove(cols.size() - 1);
            }
        }

        /** Appends the submenu of item {@code i} in column {@code c} (mutates only). */
        private void openSubmenu(int c, int i) {
            MenuItem item = cols.get(c).menu.items().get(i);
            Column sub = new Column(item.submenu(), i);
            sub.highlight = sub.firstSelectable();
            cols.add(sub);
        }

        /** Re-position the cascade, then drive the native window (or repaint, headless). */
        private void changed() {
            reposition();
            // Structural change (columns opened/closed, highlight via keys,
            // rebuild): the whole cascade repaints; this is also the damage
            // partial rendering needs, requestFrame alone provides none.
            invalidate();
            if (popupWindow != null) {
                refitNativeWindow(); // moves/resizes only if the bounds changed, else repaints
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (event.isPressed() && handleKey(event.key(), event.modifiers())) {
                event.consume();
            }
        }

        /** Handles a navigation key in the deepest open column; {@code @return} whether handled. */
        boolean handleKey(int key, int modifiers) {
            rebuildStaleColumns(); // never navigate against a live-mutated Menu
            int deep = cols.size() - 1;
            Column col = cols.get(deep); // deepest open column
            switch (key) {
                case Keys.DOWN -> col.reveal(col.highlight = col.step(col.highlight, +1));
                case Keys.UP -> col.reveal(col.highlight = col.step(col.highlight, -1));
                case Keys.HOME -> col.reveal(col.highlight = col.step(-1, +1));
                case Keys.END -> col.reveal(col.highlight = col.step(col.menu.items().size(), -1));
                case Keys.RIGHT, Keys.LEFT -> {
                    // The one place the physical arrow becomes a direction, and the only place:
                    // the trailing arrow opens a submenu (which is the side one opens toward)
                    // and the leading arrow closes it. Reading right to left that is LEFT to
                    // open and RIGHT to close. The two root hooks below are NOT flipped in turn
                    // — they already mean previous/next — because a second flip out in the
                    // MenuBar would cancel this one and leave the bar walking one way while its
                    // submenus opened the other.
                    if ((key == Keys.RIGHT) != isRtl()) {
                        int i = col.highlight;
                        if (i >= 0 && col.menu.items().get(i).hasSubmenu()) {
                            openSubmenu(deep, i);
                        } else if (deep == 0) {
                            onRootTrailing.run(); // MenuBar: walk to the next menu
                            return true;
                        }
                    } else if (deep > 0) {
                        truncateTo(deep - 1);
                    } else {
                        onRootLeading.run(); // MenuBar: walk to the previous menu
                        return true;
                    }
                }
                case Keys.ENTER, Keys.SPACE -> {
                    int i = col.highlight;
                    if (i >= 0) {
                        MenuItem item = col.menu.items().get(i);
                        if (item.hasSubmenu()) {
                            openSubmenu(deep, i);
                        } else if (item.isSelectable()) {
                            item.activate();
                            close();
                            return true;
                        }
                    }
                }
                case Keys.ESCAPE -> {
                    if (deep > 0) {
                        truncateTo(deep - 1);
                    } else {
                        close();
                        return true;
                    }
                }
                default -> {
                    // A bare access letter chooses its row. Only bare: a chord belongs to an
                    // accelerator, and Shift is excluded too because the letters are matched
                    // case-insensitively anyway, so Shift could only ever mean something else.
                    return modifiers == 0 && handleMnemonic(deep, col, key);
                }
            }
            changed();
            return true;
        }

        /**
         * Chooses the item in {@code col} whose mnemonic is {@code key}. One match is chosen
         * outright; several move the highlight to the next of them instead, which is the only
         * behaviour that lets a menu with a duplicated letter stay reachable. Disabled rows and
         * separators match nothing.
         *
         * @return whether a row answered
         */
        private boolean handleMnemonic(int deep, Column col, int key) {
            List<MenuItem> items = col.menu.items();
            int first = -1;
            int next = -1;
            int count = 0;
            for (int i = 0; i < items.size(); i++) {
                if (!items.get(i).matchesMnemonic(key)) {
                    continue;
                }
                count++;
                if (first < 0) {
                    first = i;
                }
                if (next < 0 && i > col.highlight) {
                    next = i;
                }
            }
            if (count == 0) {
                return false;
            }
            if (count == 1) {
                chooseItem(deep, first);
                return true;
            }
            col.reveal(col.highlight = next >= 0 ? next : first);
            changed();
            return true;
        }
    }

    /**
     * One rendered menu column: its {@link Menu}, the parent item index that
     * opened it (−1 for the root), the per-item vertical extents, its highlighted
     * row, and its computed position/size in layout space.
     */
    private final class Column {
        final Menu menu;
        final int parentItem;
        final int builtModCount; // Menu.modCount() this geometry was built from
        final float[] top;  // per-item top, relative to the column's CONTENT top
        final float[] hgt;  // per-item height
        /**
         * Per-item accelerator hint, or {@code null} where there is none. Resolved once here, not
         * re-derived in the paint: the string is what the column's width was measured against, and
         * a second call to {@code display()} at paint time would be a second authority on it.
         */
        final String[] accel;
        /**
         * Per-item label width, {@code 0} for a separator. Held for the same reason
         * {@link #accel} is: a mirrored column aligns a label by its own width, and deriving it
         * in the paint would measure every visible row of every column on every frame. It is
         * measured with the call the paint draws through, so the label sits exactly in its
         * gutter rather than a fraction of a point off it.
         */
        final float[] labelW;
        final float contentW; // natural width: the widest label plus both gutters
        final float h;      // full content height
        float x;
        float y;
        float w;            // on-screen width (== contentW, or the bounds width when clamped)
        float visibleH;     // on-screen height (== h, or the bounds height when clamped)
        float scroll;       // content offset when clamped (the column scrolls)
        int highlight = -1;

        Column(Menu menu, int parentItem) {
            this.menu = menu;
            this.parentItem = parentItem;
            this.builtModCount = menu.modCount();
            List<MenuItem> items = menu.items();
            top = new float[items.size()];
            hgt = new float[items.size()];
            accel = new String[items.size()];
            labelW = new float[items.size()];
            TextRuler ruler = surface.ruler;
            // The cascade's row, captured at open. Measuring here with a freshly resolved step
            // while paintColumn resolved another is exactly the defect this field prevents.
            SizeTokens t = tokens;
            Font font = t.body();
            float maxLabel = 0;
            float maxAccel = 0;
            float yy = t.menuPadV();
            for (int i = 0; i < items.size(); i++) {
                MenuItem item = items.get(i);
                // separatorBox is odd at every step so the 1pt rule centred in it stays crisp;
                // menuRowHeight is >= 24 at every step, which is what makes a menu row a
                // conformant pointer target without any hit mechanism (rows are painted, not
                // widgets, so nothing outside this class can enlarge them).
                float ih = item.isSeparator() ? t.separatorBox() : t.menuRowHeight();
                top[i] = yy;
                hgt[i] = ih;
                yy += ih;
                if (!item.isSeparator()) {
                    labelW[i] = ruler.measure(item.label(), font).width();
                    maxLabel = Math.max(maxLabel, labelW[i]);
                    Accelerator shortcut = item.accelerator();
                    if (shortcut != null) {
                        accel[i] = shortcut.display();
                        maxAccel = Math.max(maxAccel, ruler.measure(accel[i], font).width());
                    }
                }
            }
            this.h = yy + t.menuPadV();
            this.visibleH = this.h;
            // The hint's column is paid for in the width, gap included: a hint drawn over the
            // label it belongs to is worse than no hint, and the label is the thing the width was
            // sized to. A column with no accelerator anywhere pays nothing: its rows are exactly
            // as wide as they were.
            float hintColumn = maxAccel > 0 ? t.spacingLarge() + maxAccel : 0;
            // Mirroring swaps which gutter holds the check and which holds the arrow, and the
            // sum is the same either way: the column width, fit(), maxScroll(), the bounding
            // box and the native window size are all the same numbers in both directions.
            this.contentW = Math.max(t.menuMinWidth(),
                    t.menuCheckGutter() + maxLabel + hintColumn + t.menuArrowGutter());
            this.w = this.contentW;
        }

        /**
         * Sizes the column against the available bounds. Both axes shrink: a column taller than
         * the bounds clamps and scrolls, and a column <em>wider</em> than them clamps too (its
         * labels then clip) instead of hanging off the edge; {@code menuMinWidth} is 224 at
         * XLARGE, so a narrow work area reaches this on a real screen. Only a work
         * area below 168 pt reaches it at MEDIUM.
         */
        void fit(float boundsW, float boundsH) {
            // A non-positive bound means "not measured yet", NOT "no room". The in-scene
            // overlay is configured with (0, 0) because its bounds are the scene, which only
            // MenuSurface.onLayout knows; any reposition() before that first layout (an early
            // hover, a key, a submenu opened from the open call) would otherwise collapse the
            // column to 0x0, and every reader of w/visibleH (boundingBox, hit(), the paint
            // clip) would see a degenerate column until the next layout pass happened to run.
            // Guarding here rather than seeding configure() from the owner root: the root's
            // size is itself 0 before the scene's own first layout, so a seed moves the hole
            // instead of closing it, and it would make presentInScene a second authority on
            // bounds that onLayout immediately overwrites. This is the one place both axes
            // narrow, so "0 means unknown" is stated exactly once.
            w = boundsW > 0 ? Math.min(contentW, boundsW) : contentW;
            visibleH = boundsH > 0 ? Math.min(h, boundsH) : h;
        }

        float maxScroll() {
            return Math.max(0, h - visibleH);
        }

        void clampScroll() {
            scroll = Math.max(0, Math.min(scroll, maxScroll()));
        }

        /** Scrolls the minimum so item {@code index} is fully inside the visible band. */
        void reveal(int index) {
            if (index < 0 || index >= top.length) {
                return;
            }
            float pad = tokens.menuPadV(); // the same pad the column's content starts at
            if (top[index] < scroll + pad) {
                scroll = top[index] - pad;
            } else if (top[index] + hgt[index] > scroll + visibleH - pad) {
                scroll = top[index] + hgt[index] - visibleH + pad;
            }
            clampScroll();
        }

        /** @return the item index at {@code localY} (relative to the column's visible top), or −1. */
        int itemAt(float localY) {
            float contentY = localY + scroll;
            for (int i = 0; i < top.length; i++) {
                if (contentY >= top[i] && contentY < top[i] + hgt[i]) {
                    return i;
                }
            }
            return -1;
        }

        int firstSelectable() {
            return step(-1, +1);
        }

        /** Next selectable item from {@code from} in direction {@code dir} (clamped, skips separators/disabled). */
        int step(int from, int dir) {
            List<MenuItem> items = menu.items();
            int i = from + dir;
            while (i >= 0 && i < items.size()) {
                if (items.get(i).isSelectable()) {
                    return i;
                }
                i += dir;
            }
            return from >= 0 && from < items.size() && items.get(from).isSelectable() ? from : firstSelectableFallback();
        }

        private int firstSelectableFallback() {
            List<MenuItem> items = menu.items();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).isSelectable()) {
                    return i;
                }
            }
            return -1;
        }
    }
}

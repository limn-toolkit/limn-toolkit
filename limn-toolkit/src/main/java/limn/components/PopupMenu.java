package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.ToggleFacet;
import limn.components.internal.FloatingSurfaces;
import limn.components.internal.a11y.MenuAccessibility;
import limn.backend.CrashPhase;
import limn.backend.NativeWindow;
import limn.backend.spi.WindowInput;
import limn.backend.ScreenRect;
import limn.backend.WindowConfig;
import limn.concurrent.internal.Listeners;
import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Path2D;
import limn.graphics.Rect;
import limn.graphics.ShapedText;
import limn.graphics.TextRuler;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.math.Scalars;
import limn.math.Vec2;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.SceneAccess;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import limn.backend.spi.WindowPeer;
import limn.backend.spi.BackendPeer;

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
 * used, which is not always the same thing. Two cases have no choice and always draw in scene: a
 * {@linkplain limn.backend.NativeWindow#isFullscreen() fullscreen} window, which at a named mode
 * is minimized as soon as another window takes the focus, as a menu's does at once, and which at
 * the mode the display is in already covers the screen, so the menu loses nothing inside it; and
 * any window reporting no {@link limn.backend.spi.WindowPeer#supportsAbsolutePositioning()}
 * (Wayland), where a menu window would open somewhere other than the anchor. Both are asked when
 * the menu opens; a menu already open in a window of its own when the owner enters or leaves
 * fullscreen closes instead, cascade and all, as if dismissed, running no item: see
 * {@link DisplayMode}.
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

    /**
     * The synthetic key every column carries in the accessible tree, root and submenu alike.
     *
     * <p>One constant for all of them because a synthetic identifier is minted from the owner's
     * identifier and the key together: the root column's owner is the surface, a submenu column's
     * owner is the row that opened it, so the same key under two owners is two identities. What
     * the value has to be is <em>neither a {@link MenuItem} serial nor a scroll-band key</em>, and
     * this one is both — serials are minted from one and count up, bands are the small negatives —
     * so {@link MenuSurface#onSyntheticAction} can tell the three apart from the key alone, which
     * is all it is given. It is also the one {@code long} whose negation is itself, which is why
     * the band decoding below tests for it first rather than after.
     */
    private static final long COLUMN_KEY = Long.MIN_VALUE;

    /** @see #setDefaultDisplayMode */
    private static volatile DisplayMode defaultDisplayMode = DisplayMode.NATIVE_WINDOW;

    private final Menu rootMenu;
    /**
     * Who wants to hear that this menu closed: a lifecycle observer list, because a close is
     * something that happened once and will not un-happen, and a menu bar and an application
     * may both want to know. Null until the first subscriber.
     */
    private Runnable[] closeObservers;
    private static final Runnable[] NO_OBSERVERS = new Runnable[0];
    // The arrow keys at the root column with nowhere to go: a MenuBar hooks these to walk to
    // the previous/next top-level menu (null for a standalone popup). Named for the sides
    // rather than for the keys because the key that reaches them is flipped in handleKey and
    // these are not: leading always means the previous menu, in either direction, and a second
    // flip out here would cancel the first one and walk the bar against its own submenus.
    private Runnable onRootLeading;
    private Runnable onRootTrailing;
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
     * {@code Theme.of(this).body} independently, which is a live bug the moment the two reads
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
    /** The transparent margin around the cascade in its own window, for its shadows; zero for none. */
    private int nativeMargin;
    /** What the application asked for. The platform may override it towards IN_SCENE, never away. */
    private DisplayMode requested = defaultDisplayMode;
    // In-scene overlay opacity: 1 in native mode (the window opacity does the fade);
    // animated 0→1 on open and 1→0 on close for the fullscreen fallback, which has no
    // separate window to fade, so it mirrors the native popup's fade in/out. The cascade is
    // composited with it as one picture (MenuSurface.saveFadeLayer).
    private float sceneFade = 1f;
    private Scene owner;
    private MenuSurface surface;

    // native-window state
    private NativeWindow parentWindow;
    private NativeWindow popupWindow;
    private Scene popupScene;
    /**
     * Whether the menu's window gave the owner back the keyboard as an item was chosen, which
     * leaves nothing for the end of its fade to do; see {@link #runChosen}.
     */
    private boolean keyboardHandedBack;
    /** Opened from the keyboard, which is when a menu draws its access letters from the start. */
    private boolean lettersFromKeyboard;
    /**
     * Keys a menu bar's walk passed on to this menu before its window was made, delivered in order
     * once it is; see {@link #keyFromWalk}. Null while there are none.
     */
    private List<LateKey> keysBeforeWindow;
    private Subscription dismissHandle;
    private Subscription blurHandle;
    private Subscription popupBlurHandle;
    /** Closes a menu that opened towards a window of its own when the owner changes fullscreen. */
    private Subscription fullscreenHandle;
    /**
     * The owner's keys, routed here while a menu in a window of its own is open: that window never
     * takes the desktop's keyboard, as a Win32 or an AppKit menu does not; see {@link #keyFromOwner}.
     */
    private Subscription keyRouteHandle;
    private ScreenRect workArea;
    private float screenFactor = 1;
    /**
     * Where the pointer was last seen on the desktop, in the screen coordinates the window is
     * placed in; {@code NaN} while unknown. A pointer event at the same place is not the pointer
     * moving: see {@link #pointerMoved}.
     */
    private float pointerScreenX = Float.NaN;
    private float pointerScreenY = Float.NaN;
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

    /**
     * Observes this menu closing: a dismissal, an item chosen, or a {@link #close()} from code
     * alike, because a close is a lifecycle fact and not a gesture. Any number may observe; the
     * menu bar that built this popup holds one, and an application may hold another.
     *
     * @param observer what to run when the menu closes; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    public Subscription observeClose(Runnable observer) {
        Ui.checkUiThread();
        Objects.requireNonNull(observer, "observer");
        closeObservers = Listeners.added(closeObservers, observer, NO_OBSERVERS);
        return new Subscription() {
            private Runnable pending = observer;

            @Override
            public void cancel() {
                Ui.checkUiThread();
                Runnable taken = pending;
                if (taken == null) {
                    return;
                }
                pending = null;
                closeObservers = Listeners.removed(closeObservers, taken);
            }
        };
    }

    /**
     * Hook for the arrow that walks toward the leading side at the root column (MenuBar: go to
     * the previous menu). That is LEFT reading left to right and RIGHT reading right to left;
     * the physical key is flipped once, inside this class, so a listener registered here means
     * "the previous menu" in either direction and must not flip again.
     *
     * <p>One handler at a time: {@code null} clears it, and setting a second while one is set
     * throws {@link IllegalStateException}.
     */
    public PopupMenu onRootLeading(@Nullable Runnable listener) {
        Ui.checkUiThread();
        this.onRootLeading = Checks.handlerSlot(onRootLeading, listener, "PopupMenu.onRootLeading");
        return this;
    }

    /**
     * Hook for the arrow that walks toward the trailing side on a root item without a submenu
     * (MenuBar: the next menu). The trailing arrow is the one that opens a submenu where there
     * is one, which is why it is the one that walks on where there is not;
     * {@link #onRootLeading} states the direction rule both share.
     *
     * <p>One handler at a time: {@code null} clears it, and setting a second while one is set
     * throws {@link IllegalStateException}.
     */
    public PopupMenu onRootTrailing(@Nullable Runnable listener) {
        Ui.checkUiThread();
        this.onRootTrailing = Checks.handlerSlot(onRootTrailing, listener, "PopupMenu.onRootTrailing");
        return this;
    }

    /**
     * MenuBar-only: scene points the fullscreen in-scene overlay should NOT capture,
     * letting them reach the content beneath (the bar's titles), so hovering a sibling
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
        Ui.checkUiThread();
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
    public PopupMenu setControlSize(@Nullable ControlSize size) {
        Ui.checkUiThread();
        this.declaredSize = size;
        return this;
    }

    /** Whether the cascade is currently showing. */
    public boolean isOpen() {
        return open;
    }

    /**
     * The window the open menu is drawn in, for an application that captures it or a test that
     * drives it: the menu is a window of its own, so a capture of the owner window does not
     * contain it. Supported API, not a test hook.
     *
     * @return this menu's native window while open, else {@code null}, which is also the
     *         answer while the menu is drawn in the scene
     */
    public @Nullable NativeWindow popupWindow() {
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
    public void showAt(Widget<?> anchor, float x, float y) {
        Ui.checkUiThread();
        showAnchored(anchor, x, y, 0, 0);
    }

    /** Opens the menu with its corner at the scene point {@code (x, y)}, at the scene's step. */
    public void showAt(Scene ownerScene, float x, float y) {
        Ui.checkUiThread();
        showAnchored(ownerScene, x, y, 0, 0);
    }

    /**
     * Opens the menu attached to an anchor rectangle in {@code anchor}'s scene coordinates,
     * sized at the step resolved on {@code anchor}: it drops below the rect (a menu-bar
     * dropdown) and flips above when there is no room. A no-op for a widget outside a scene.
     * UI thread only.
     */
    public void showAnchored(Widget<?> anchor, float ax, float ay, float aw, float ah) {
        Ui.checkUiThread();
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
        Ui.checkUiThread();
        Objects.requireNonNull(ownerScene, "ownerScene");
        show(ownerScene, ownerScene.root(), ax, ay, aw, ah);
    }

    private void show(Scene ownerScene, Widget<?> host, float ax, float ay, float aw, float ah) {
        Ui.checkUiThread();
        EditTarget.opening(ownerScene);
        NativeWindow win = ownerScene == null ? null : ownerScene.window();
        if (open || rootMenu.isEmpty() || win == null || win.display() == null) {
            return; // native menus need a real window + display
        }
        open = true;
        this.owner = ownerScene;
        beginOpen(ownerScene.textRuler(), host);
        // Two cases give a menu no window of its own. A fullscreen window: GLFW minimizes one
        // fullscreen at a named mode when another window takes the focus, which the menu's does at
        // once, and one at the mode the display is in already covers the screen, so the cascade
        // loses nothing drawn inside it. And Wayland has no way to put one where the anchor is: the
        // cascade would open near the middle of the display, wearing whatever frame the compositor
        // decorates toplevels with. Both fall back to an in-scene overlay drawn inside the owner
        // window.
        if (requested == DisplayMode.IN_SCENE || !FloatingSurfaces.anchoredWindowAvailable(win)) {
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
    private void beginOpen(limn.graphics.TextRuler ruler, Widget<?> host) {
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
        if (keyRouteHandle != null) {
            // First: a key that arrives after this, in the batch that closed the menu, is the
            // owner's again, or the menu a menu bar walked to takes it.
            keyRouteHandle.cancel();
            keyRouteHandle = null;
        }
        if (surface != null) {
            // Its window or overlay fades from here on and still receives input, none of which
            // the menu answers any more; see MenuSurface#lateKey.
            surface.closed(owner, modal && popupWindow != null && !keyboardHandedBack);
        }
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
        if (fullscreenHandle != null) {
            fullscreenHandle.cancel();
            fullscreenHandle = null;
        }
        if (popupWindow != null) {
            NativeWindow closing = popupWindow;
            Scene closingScene = popupScene;
            NativeWindow parent = parentWindow;
            popupWindow = null;
            popupScene = null;
            boolean handedBack = keyboardHandedBack;
            Runnable destroy = () -> {
                if (modal && !handedBack && !closing.isClosed()) {
                    BackendPeer.of(closing.backend()).popModal(closing); // release the owner lock
                }
                if (parent != null && !parent.isClosed()) {
                    WindowPeer.of(parent).unregisterChildPopup(closing);
                    // The owner asks for the focus back before the window holding it goes, when
                    // no choice gave it back already. A window closed with the focus leaves the
                    // desktop to choose the next one, and it may choose itself: GNOME over X11
                    // did, which takes the keyboard from the owner and minimizes one fullscreen at
                    // a named mode, as it minimized an owner that went fullscreen under an open
                    // menu. Asked here, at the end of the fade, and only if the window still
                    // holds the focus then: another window may have taken it since the close,
                    // the neighbouring menu of a menu bar walked across or one the person clicked,
                    // and the owner does not take it from that one, nor from a modal dialog
                    // blocking it. A modal menu held the focus as the top modal, whatever its
                    // scene last heard.
                    boolean heldFocus = modal
                            || (closingScene != null && closingScene.isWindowFocused());
                    if (!handedBack && heldFocus && !parent.isModalBlocked()) {
                        parent.focus();
                    }
                }
                closing.requestClose();
            };
            if (closingScene != null) {
                closingScene.fadeWindowOut(limn.animation.Motion.duration(Theme.current().animWindow), destroy);
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
                // input capture. Frozen, the menu would stay open over a paused app. The keyboard
                // goes back as the fade starts.
                limn.scene.internal.SceneAccess.releaseOverlayKeyboard(o, s);
                o.addRealTimeTicker(dt -> {
                    sceneFade = (float) Math.max(0, sceneFade - limn.animation.Motion.progress(dt, Theme.current().animWindow));
                    s.invalidateCascade();
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
        List<LateKey> held = keysBeforeWindow;
        keysBeforeWindow = null;
        if (held != null && surface != null) {
            // Passed on by a walk to a menu closed before its window was made: they become what
            // any key reaching this closed menu becomes.
            for (LateKey key : held) {
                surface.lateKey(key);
            }
        }
        Runnable[] closing = closeObservers;
        if (closing != null) {
            for (Runnable observer : closing) {
                try {
                    observer.run();
                } catch (Throwable error) {
                    Listeners.failed(CrashPhase.OBSERVER, error);
                }
            }
        }
    }

    /**
     * Closes the menu as the person dismissed it: Escape at the root, a press outside the
     * cascade, a press back in the owner, an assistive technology's cancel, or the owner's
     * fullscreen changing. The owner is where the keyboard goes next, so a key the fading window
     * still receives is given to the owner's scene; see {@link MenuSurface#passOnWhileClosing}.
     */
    private void dismiss() {
        if (open && surface != null) {
            surface.keysGoToOwner = true;
        }
        close();
    }

    /**
     * Passes the keys this closed menu's fading window still receives on to {@code next}, the menu
     * a menu bar's walk opened from it with Left or Right. The walk hands the keyboard to the next
     * menu's window, but the window walked away from keeps it until the window manager acts on
     * that: a second Right 5 ms after the first reached it, where the menu answers nothing, and the
     * bar walked one menu instead of two. MenuBar only; called after the walk has closed this menu.
     */
    void passKeysOnTo(PopupMenu next) {
        if (!open && surface != null && next != this) {
            surface.walkedTo = next;
        }
    }

    /**
     * A key or a character a menu bar's walk passed on to this menu, handled as if it had reached
     * this menu's own window. Held until that window is made, which is a turn of the event loop
     * after the open, so that the menu answers it with its cascade placed and its window up.
     */
    private void keyFromWalk(LateKey key) {
        if (surface == null) {
            return; // never shown: there is nothing the key could reach
        }
        if (open && popupWindow == null && !inScene) {
            if (keysBeforeWindow == null) {
                keysBeforeWindow = new ArrayList<>();
            }
            keysBeforeWindow.add(key);
            return;
        }
        surface.keyArrived(key);
    }

    /**
     * A key the owner's window received while this menu is open in a window of its own, which never
     * holds the keyboard: held until the window is made, then handed to the menu's scene as if it
     * had reached that window.
     */
    private void keyFromOwner(LateKey key) {
        if (!open || surface == null) {
            return;
        }
        if (popupScene == null) {
            if (keysBeforeWindow == null) {
                keysBeforeWindow = new ArrayList<>();
            }
            keysBeforeWindow.add(key);
            return;
        }
        deliver(key);
    }

    /** Whether the open menu is a grafted window of its own that the owner's keyboard drives. */
    private boolean drivenFromOwner() {
        return open && popupScene != null && keyRouteHandle != null;
    }

    /** Hands a key to the menu's own scene, as its window would, layout and all. */
    private void deliver(LateKey key) {
        WindowInput input = SceneAccess.input(popupScene);
        key.replay(input);
        input.inputBatchEnded();
    }

    /**
     * A key event, or with {@code codepoint >= 0} a typed character, as it reached a menu's surface.
     */
    private record LateKey(int key, int layoutCodePoint, boolean pressed, boolean repeat, int modifiers,
                           int codepoint) {
        static LateKey key(KeyEvent event) {
            return new LateKey(event.key(), event.layoutCodePoint(), event.isPressed(), event.isRepeat(),
                    event.modifiers(), -1);
        }

        static LateKey character(int codepoint) {
            return new LateKey(0, 0, false, false, 0, codepoint);
        }

        /** Replays it into {@code input}, as a backend would have delivered it there, layout and all. */
        void replay(WindowInput input) {
            if (codepoint >= 0) {
                input.charTyped(codepoint);
            } else {
                input.keyEvent(key, layoutCodePoint, pressed, repeat, modifiers);
            }
        }
    }

    /**
     * Runs a chosen item and closes the menu, having first given the owner back the keyboard from
     * the menu's window, at once and while that window is still up: lets go of a modal menu's lock,
     * since a blocked owner hands the focus straight back to the modal over it, then asks for the
     * owner's focus unless another modal blocks the owner. Only when the menu's window holds the
     * focus, which a modal menu does as the top modal, whatever its scene last heard; one that does
     * not, or not yet by its scene's account, is left to the end of its fade as any other close is.
     * Nothing to give back for a menu drawn in scene.
     *
     * <p>At the choice rather than at the end of the fade, which is where every other close still
     * asks. The window fades for {@code animWindow} after the close and held the keyboard until
     * the owner asked for it there, so a key pressed meanwhile went to the fading menu: a Tab some
     * 80 ms after choosing an item was lost, on Windows, macOS and X11 alike. And before the item
     * runs, so that a window the item opens or focuses is asked for after the owner and keeps the
     * focus, whether or not the menu's scene has heard by then that it lost it.
     */
    private void runChosen(MenuItem item) {
        NativeWindow window = popupWindow;
        boolean heldFocus = modal || (popupScene != null && popupScene.isWindowFocused());
        if (window != null && heldFocus && !keyboardHandedBack) {
            keyboardHandedBack = true;
            if (modal && !window.isClosed()) {
                BackendPeer.of(window.backend()).popModal(window); // release the owner lock
            }
            NativeWindow parent = parentWindow;
            if (parent != null && !parent.isClosed() && !parent.isModalBlocked()) {
                parent.focus();
            }
        }
        item.activate(Change.Origin.USER);
        close();
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
        boolean animate = owner.window() != null && !limn.animation.Motion.reduced();
        sceneFade = animate ? 0f : 1f;
        owner.pushPopup(surface);          // captures input + focuses the surface
        if (animate) {
            owner.addRealTimeTicker(dt -> { // wall time, like its fade-out twin
                if (!open) {
                    return false; // closed mid-fade: let the fade-out ticker take over
                }
                sceneFade = (float) Math.min(1, sceneFade + limn.animation.Motion.progress(dt, Theme.current().animWindow));
                surface.invalidateCascade();
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
        // The owner going fullscreen, or leaving it, takes the whole cascade down as a platform's
        // own menus go on a change of their window's state: the change resizes and moves the owner
        // under a cascade placed against its old geometry, and the menu's window, which holds the
        // focus, would minimize an owner fullscreen at a named mode. Armed now rather than with the
        // window, so a change before the window exists closes the menu too, and modal or not: the
        // change is told to a blocked owner as well. No item runs. An in-scene menu is left alone.
        fullscreenHandle = owner.observeWindowFullscreen(fullscreen -> dismiss());
        if (!modal) {
            // From the open on, before the window exists: a key pressed meanwhile, the second Down
            // of two, or the Right a menu bar walks on with, is held for the menu and not given to
            // whatever the owner has focused.
            keyRouteHandle = SceneAccess.routeKeyboard(owner, new SceneAccess.KeyRoute() {
                @Override
                public void key(int key, int layoutCodePoint, boolean pressed, boolean repeat, int modifiers) {
                    keyFromOwner(new LateKey(key, layoutCodePoint, pressed, repeat, modifiers, -1));
                }

                @Override
                public void character(int codepoint) {
                    keyFromOwner(LateKey.character(codepoint));
                }

                @Override
                public @Nullable Scene popup() {
                    return popupScene;
                }
            });
        }
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
        if (!FloatingSurfaces.anchoredWindowAvailable(parentWindow)) {
            // The owner went fullscreen between the open and this turn, and the change has not
            // been dispatched yet: the window it would have closed is never made.
            close();
            return;
        }
        surface.reposition();
        // Where the compositor casts no shadow for such a window, the window is larger than the
        // cascade by the shadow's reach on every side, and each column draws its own into it.
        nativeMargin = limn.components.internal.PopupFrame.marginFor(parentWindow, Theme.current(),
                Elevation.OVERLAY);
        float[] box = windowBox();
        int w = Math.max(1, (int) Math.ceil(box[2] - box[0]));
        int h = Math.max(1, (int) Math.ceil(box[3] - box[1]));
        surface.setRenderOffset(box[0], box[1]);

        keyboardHandedBack = false;
        appliedW = w;
        appliedH = h;
        appliedX = workAreaScreenX + Math.round(box[0] * screenFactor);
        appliedY = workAreaScreenY + Math.round(box[1] * screenFactor);
        // Owned from creation, and placed in the owner's points: the cascade is computed against the
        // work area, and the same point is handed over relative to the window that opened it.
        // The owner keeps the desktop's keyboard and hands its keys here (keyFromOwner), which is
        // how a Win32 menu and an AppKit menu work; only a modal menu, which blocks its owner,
        // takes the focus as a dialog does.
        popupWindow = parentWindow.backend().createWindow(WindowConfig.popup(parentWindow, w, h)
                .title("menu").focusOnShow(modal)
                .atInOwner((appliedX - parentWindow.screenX()) / screenFactor,
                        (appliedY - parentWindow.screenY()) / screenFactor));
        // The owner's clock as well as its rendering flags: this scene owns the fade-out that
        // destroys the window, and a fade on the wall clock under an opener on an injected one
        // never ends (Scene#clock).
        popupScene = new Scene(surface, owner.clock());
        popupScene.inheritRenderingFlags(owner); // partial/debug follow the owner window
        if (!modal) {
            // Published in the owner's tree, under the widget that opened it, as the combo box's
            // list is: the reader never leaves the owner's window, where the keyboard stays, and
            // the menu's window publishes nothing of its own. A modal menu holds the keyboard in
            // its own window, and keeps its own tree there.
            owner.graftPopup(popupScene);
        }
        popupScene.bind(popupWindow);
        popupScene.setBackground(Color.TRANSPARENT);

        if (parentWindow.isVisible()) {
            popupScene.fadeWindowIn(limn.animation.Motion.duration(Theme.current().animWindow));
            popupWindow.show();
        }
        // Where the pointer rests as the menu opens, so the event a window appearing or growing
        // under it produces is known for what it is (the keyboard opened this menu, and a
        // resting pointer takes nothing from it). Polled from the owner, which is on screen.
        float restX = parentWindow.cursorX();
        float restY = parentWindow.cursorY();
        pointerScreenX = Float.isNaN(restX) ? Float.NaN : parentWindow.screenX() + restX * screenFactor;
        pointerScreenY = Float.isNaN(restY) ? Float.NaN : parentWindow.screenY() + restY * screenFactor;
        if (modal) {
            // Backend-enforced window-modal (like Dialog): the owner ignores input
            // and beeps on a click; the menu closes only on a choice or Escape.
            BackendPeer.of(parentWindow.backend()).pushModal(popupWindow, parentWindow);
        }
        popupScene.focusTraverse(false); // focus the surface so it gets keys
        popupWindow.requestFrame();
        List<LateKey> held = keysBeforeWindow;
        keysBeforeWindow = null;
        if (held != null) {
            // Held from the open, or passed on by a menu bar's walk, before this window existed.
            // One of them may close this menu, or walk on in turn, and the rest then follow.
            for (LateKey key : held) {
                if (!open || popupScene == null) {
                    surface.lateKey(key);
                } else {
                    deliver(key);
                }
            }
        }
        if (!modal) {
            // Non-modal: a press back in the owner window dismisses the menu (the
            // owner is not blocked, so it receives the press).
            dismissHandle = owner.observePresses(target -> dismiss());
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
    /** The cascade's bounding box, grown by the margin its window keeps for the columns' shadows. */
    private float[] windowBox() {
        float[] box = surface.boundingBox();
        box[0] -= nativeMargin;
        box[1] -= nativeMargin;
        box[2] += nativeMargin;
        box[3] += nativeMargin;
        return box;
    }

    private void refitNativeWindow() {
        if (popupWindow == null) {
            return;
        }
        float[] box = windowBox();
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
     * {@code NATIVE_WINDOW} still draws in scene on Wayland, where a menu window does not land
     * where the anchor is, and in a fullscreen window, for the reasons {@link DisplayMode} gives.
     * {@code IN_SCENE} is always honoured; it needs nothing from the platform. Read
     * {@link #displayMode()} for what happened.
     *
     * <p>Takes effect on the next open; a menu already on screen is not re-presented under it.
     */
    public PopupMenu setDisplayMode(DisplayMode mode) {
        Ui.checkUiThread();
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
     * Says this menu was opened from the keyboard (Alt+letter, the Menu key, Down on a focused
     * bar), so it draws its access letters from the start, as a keyboard-opened menu does on
     * Windows and Linux; opened with the pointer, they show while Alt is held.
     */
    void openedFromKeyboard() {
        lettersFromKeyboard = true;
    }

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
    private final class MenuSurface extends Widget<MenuSurface> {

        /** Whether the rows' access letters are drawn; never on macOS. */
        private boolean lettersShown() {
            return AccessLetters.onThisSystem()
                    && (lettersFromKeyboard || limn.scene.internal.SceneAccess.accessLettersShown(scene()));
        }


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
        /** Whether the menu closed; the surface is only fading out. See {@link #closed}. */
        private boolean closed;
        /** Whether the menu was dismissed rather than chosen from or replaced. */
        boolean keysGoToOwner;
        /** The menu a menu bar's walk opened from this one; see {@link #passKeysOnTo}. */
        PopupMenu walkedTo;
        /** The scene the menu was opened over, as it was when the menu closed. */
        private Scene ownerAtClose;
        /** Whether the menu still held its own modal lock over the owner when it closed. */
        private boolean ownLockAtClose;

        MenuSurface(TextRuler ruler) {
            this.ruler = ruler;
            setFocusable(true); // receive keyboard while open
            limn.scene.internal.SceneAccess.drawsAccessLetters(this); // the rows' letters
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
            // A zero-sized bounds makes hi < lo, which Scalars.clamp answers with lo: a pure
            // backstop, since Column#fit shrinks the column to the bounds first, so a column that
            // does not fit is made to fit instead of being pinned at lo and left overflowing.
            col.x = Scalars.clamp(overflows ? trailing : leading, boundsX, boundsX + boundsW - col.w);

            float y = anchorY + anchorH; // drop below the anchor
            if (y + col.visibleH > boundsY + boundsH) {
                float above = anchorY - col.visibleH; // flip above
                y = above >= boundsY ? above : boundsY + boundsH - col.visibleH;
            }
            col.y = Scalars.clamp(y, boundsY, boundsY + boundsH - col.visibleH);
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
            col.x = Scalars.clamp(overflows ? leading : trailing, boundsX, boundsX + boundsW - col.w);

            // Align its first row with the parent item: SUBMENU_Y_ALIGN follows menuPadV and
            // has no token of its own.
            float y = parentItemTop - tokens.menuPadV();
            if (y + col.visibleH > boundsY + boundsH) {
                y = boundsY + boundsH - col.visibleH;
            }
            col.y = Scalars.clamp(y, boundsY, boundsY + boundsH - col.visibleH);
            col.clampScroll();
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
            // In the scene the cascade fades as one picture, so a column's shadow does not darken
            // the column and a submenu does not show the menu beneath it through itself; the
            // native popup fades through its window opacity, so it paints solid here.
            boolean fading = fillScene && sceneFade < 1f;
            if (fading) {
                saveFadeLayer(canvas);
            }
            for (Column col : cols) {
                paintColumn(canvas, col);
            }
            if (fading) {
                canvas.restore();
            }
        }

        /**
         * Opens the layer the cascade fades through: over the columns and what their shadows reach,
         * and not this surface's own opacity, because in the scene this surface covers the scene,
         * and a picture of it would be the window's on every frame of the fade.
         */
        private void saveFadeLayer(Canvas canvas) {
            float reach = cascadeReach();
            canvas.saveLayer(cascadeLeft() - reach, cascadeTop() - reach,
                    cascadeRight() - cascadeLeft() + 2 * reach, cascadeBottom() - cascadeTop() + 2 * reach,
                    sceneFade);
        }

        /** Repaints the cascade and what its shadows reach: what a step of its fade changes. */
        void invalidateCascade() {
            if (cols.isEmpty()) {
                return;
            }
            float reach = cascadeReach();
            invalidate(cascadeLeft() - reach, cascadeTop() - reach,
                    cascadeRight() - cascadeLeft() + 2 * reach, cascadeBottom() - cascadeTop() + 2 * reach);
        }

        private float cascadeReach() {
            return 1 + Theme.of(this).elevation(Elevation.OVERLAY).reach();
        }

        // The cascade's edges in this surface's coordinates; none of the four allocates, as a fade
        // frame asks them of every column on every frame.
        private float cascadeLeft() {
            float v = Float.MAX_VALUE;
            for (Column col : cols) {
                v = Math.min(v, col.x - offsetX);
            }
            return cols.isEmpty() ? 0 : v;
        }

        private float cascadeTop() {
            float v = Float.MAX_VALUE;
            for (Column col : cols) {
                v = Math.min(v, col.y - offsetY);
            }
            return cols.isEmpty() ? 0 : v;
        }

        private float cascadeRight() {
            float v = -Float.MAX_VALUE;
            for (Column col : cols) {
                v = Math.max(v, col.x + col.w - offsetX);
            }
            return cols.isEmpty() ? 0 : v;
        }

        private float cascadeBottom() {
            float v = -Float.MAX_VALUE;
            for (Column col : cols) {
                v = Math.max(v, col.y + col.visibleH - offsetY);
            }
            return cols.isEmpty() ? 0 : v;
        }

        private void paintColumn(Canvas canvas, Column col) {
            Theme theme = Theme.of(this);
            SizeTokens t = tokens; // the row the columns were BUILT from, never re-resolved here
            // And the direction the columns were PLACED with, for the same reason: a row whose
            // gutters were drawn against a direction the cascade was not positioned with would
            // put the check on the side the label starts from.
            boolean rtl = isRtl();
            float x = col.x - offsetX;
            float y = col.y - offsetY;
            if (inScene || nativeMargin > 0) {
                // In the scene a menu lies over the page; in a window of its own the shadow is
                // the compositor's, or drawn here into the window's margin where it casts none.
                theme.drawElevation(canvas, limn.graphics.RoundRect.of(x, y, col.w, col.visibleH,
                        t.radiusMedium()), Elevation.OVERLAY);
            }
            canvas.fillRoundRectWithBorder(x, y, col.w, col.visibleH, t.radiusMedium(),
                    theme.surfaceRaised().withAlpha(0.98f), Strokes.BORDER, theme.outline());

            Font font = t.body();
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
                if (col.collapsed[i]) {
                    continue; // a leading, trailing or doubled separator takes no room
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
                    // fight it. The one point-space correction this file makes is the half
                    // border the column's border is inset by, and that is measured from the
                    // column's own box, not from the scene.
                    float sepY = iy + col.hgt[i] / 2;
                    canvas.drawLine(x + t.menuSepInsetX(), sepY, x + col.w - t.menuSepInsetX(),
                            sepY, Strokes.HAIRLINE, theme.outline());
                    continue;
                }
                float rowH = col.hgt[i];
                boolean active = i == col.highlight && item.isSelectable();
                if (active) {
                    canvas.fillRoundRect(x + t.menuHiliteInsetX(), iy + t.menuHiliteInsetY(),
                            col.w - 2 * t.menuHiliteInsetX(), rowH - 2 * t.menuHiliteInsetY(),
                            t.radiusSmall(), theme.primary().withAlpha(0.28f));
                }
                Color ink = item.isEnabled() ? theme.text() : theme.disabledText();
                if (item.kind() == MenuItem.Kind.CHECK && item.isChecked()) {
                    // The check sits in the leading gutter. The tick itself is NOT mirrored —
                    // no platform mirrors a check mark — so only its box moves, and because
                    // paintCheck draws rightwards from the x it is given, the mirrored gutter
                    // has to be handed its LEFT edge rather than its leading one.
                    float checkX = rtl
                            ? x + col.w - t.menuCheckInset() - t.checkGlyphW()
                            : x + t.menuCheckInset();
                    paintCheck(canvas, checkX, iy + rowH / 2,
                            item.isEnabled() ? theme.primary() : theme.disabledText(), t);
                }
                float baseline = iy + ruler.centredBaseline(font, rowH);
                // drawText places a run's LEFT edge for either base direction (a right-to-left
                // line fills the same box from the other end), so a leading-aligned label in a
                // mirrored column needs its own width to find that edge. That width is the
                // column's snapshot, measured with the call the paint draws through: measuring
                // here instead would run for every visible row of every column of every frame.
                ShapedText label = col.label[i];
                limn.graphics.Icon picture = item.icon();
                if (picture != null && col.iconSlot > 0) {
                    // After the check's gutter and before the label, so a checked item with a
                    // picture shows both; mirrored with the label it belongs to.
                    float side = iconSide(ruler, t);
                    float iconX = rtl ? x + col.w - t.menuCheckGutter() - side : x + t.menuCheckGutter();
                    // With no painter, so that an animated icon shows its first frame: no
                    // system's menu moves one, and this one keeps to that.
                    Object painter = limn.graphics.internal.IconPlayer.enter(null);
                    try {
                        picture.paint(canvas, iconX, iy + (rowH - side) / 2, side, ink, theme.isDark());
                    } finally {
                        limn.graphics.internal.IconPlayer.leave(painter);
                    }
                }
                float labelX = rtl
                        ? x + col.w - t.menuCheckGutter() - col.iconSlot - label.metrics().width()
                        : x + t.menuCheckGutter() + col.iconSlot;
                canvas.drawText(label, labelX, baseline, ink);
                // The rule takes both its edges off the line's own shaping and copes with a
                // right-to-left run itself, so the mirrored left edge is all it needs -- and it is
                // handed the very line just drawn, so it cannot mark a different shaping of it.
                if (lettersShown()) {
                    MenuInk.underlineMnemonic(canvas, label, item.mnemonicIndex(),
                            labelX, baseline, ink);
                }
                // The hint is aligned against the SAME trailing margin the submenu arrow uses,
                // so the two kinds of row end at one edge, and an item can carry only one of
                // them, so they can never collide inside a row. Only the placement mirrors: the
                // string names physical keys and is never reversed.
                ShapedText accel = col.accelLine[i];
                if (accel != null) {
                    float accelW = accel.metrics().width();
                    float accelX = rtl
                            ? x + t.menuArrowGutter()
                            : x + col.w - t.menuArrowGutter() - accelW;
                    canvas.drawText(accel, accelX, baseline,
                            item.isEnabled() ? theme.textMuted() : theme.disabledText());
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
            if (hintBandIsLive(col, true)) {
                paintScrollHint(canvas, col, x, y + Strokes.ROW_CLIP, true, t);
            }
            if (hintBandIsLive(col, false)) {
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
            Theme theme = Theme.of(this);
            canvas.fillRect(x + Strokes.ROW_CLIP, bandY, col.w - 2 * Strokes.ROW_CLIP,
                    Strokes.MENU_SCROLL_HINT_H, theme.surfaceRaised().withAlpha(0.92f));
            float cx = x + col.w / 2;
            float cy = bandY + Strokes.MENU_SCROLL_HINT_H / 2;
            float s = t.scrollChevronHalf();
            if (up) {
                canvas.drawLine(cx - s, cy + s / 2, cx, cy - s / 2, Strokes.ARROW_PEN, theme.textMuted());
                canvas.drawLine(cx, cy - s / 2, cx + s, cy + s / 2, Strokes.ARROW_PEN, theme.textMuted());
            } else {
                canvas.drawLine(cx - s, cy - s / 2, cx, cy + s / 2, Strokes.ARROW_PEN, theme.textMuted());
                canvas.drawLine(cx, cy + s / 2, cx + s, cy - s / 2, Strokes.ARROW_PEN, theme.textMuted());
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
                if (Rect.contains(col.x, col.y, col.w, col.visibleH, lx, ly)) {
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
         * Whether one of {@code col}'s scroll-hint bands is on the glass.
         *
         * <p>The one place this is decided, because four callers ask it and the harm of two of
         * them disagreeing is a control on the wrong side of the truth. The paint draws the
         * band; {@link #hintBandDirection} gives a click on it priority over the row beneath;
         * {@code describeColumn} publishes its box; {@link #pressBand} runs the scroll. A band
         * that is painted and not published is a control a reader cannot reach, and one that is
         * published and not painted is an enabled button lying over a row &mdash; it tells a
         * user that pressing there scrolls, where the click chooses an item.
         *
         * @param col a column
         * @param up  whether to ask about the band that scrolls back toward the first item
         * @return whether that band is drawn, published, and pressable
         */
        private boolean hintBandIsLive(Column col, boolean up) {
            return up ? col.scroll > SCROLL_EPSILON
                    : col.scroll < col.maxScroll() - SCROLL_EPSILON;
        }

        /**
         * @return the scroll direction when the point sits on a visible
         *         scroll-hint band of {@code col} (matching the paint
         *         conditions), else 0; the bands are controls, not veneers.
         */
        private int hintBandDirection(Column col, float ly) {
            float local = ly - col.y;
            if (hintBandIsLive(col, true) && local < Strokes.MENU_SCROLL_HINT_H) {
                return -1;
            }
            if (hintBandIsLive(col, false)
                    && local > col.visibleH - Strokes.MENU_SCROLL_HINT_H) {
                return 1;
            }
            return 0;
        }

        /** @return the column under the layout-space point (even between items), or null. */
        private Column columnAt(float lx, float ly) {
            for (int c = cols.size() - 1; c >= 0; c--) {
                Column col = cols.get(c);
                if (Rect.contains(col.x, col.y, col.w, col.visibleH, lx, ly)) {
                    return col;
                }
            }
            return null;
        }

        @Override
        protected boolean overlayPassesPointer(float sceneX, float sceneY) {
            // Fullscreen fallback only: yield points over a MenuBar title (never
            // over one of our columns) so the bar keeps hover-switching + its cursor.
            if (!fillScene || !inScenePassThrough.test(sceneX, sceneY)) {
                return false;
            }
            Vec2 local = sceneToLocal(sceneX, sceneY);
            float lx = local.x() + offsetX;
            float ly = local.y() + offsetY;
            return hit(lx, ly) == null;
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (closed) {
                // Nothing is chosen, highlighted or scrolled on a closed menu. Dropped rather than
                // passed on: its coordinates are the fading window's, or in scene the overlay's
                // that still covers the owner until the fade ends.
                event.consume();
                return;
            }
            rebuildStaleColumns(); // never hit-test against a live-mutated Menu
            Vec2 local = sceneToLocal(event.x(), event.y());
            float lx = local.x() + offsetX;
            float ly = local.y() + offsetY;
            switch (event.type()) {
                case MOVE, ENTER, DRAG -> {
                    if (pointerMoved(event)) {
                        moveAt(lx, ly);
                    }
                }
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
         * not join it &mdash; but the reason is now one reason rather than two. The cascade's
         * direction is captured once at open (see {@link PopupMenu#direction}) and is fixed for
         * the life of the open cascade, so the values a column holds &mdash; {@code contentW}, the
         * accelerator strings, the shaped {@link Column#label} lines &mdash; cannot have been
         * taken under a direction the paint disagrees with.
         *
         * <p>The second reason this used to give has been deliberately removed rather than left
         * to rot: those lines are no longer taken through the direction-blind two-argument ruler,
         * so a direction that <em>could</em> arrive mid-cascade would now genuinely move them.
         * Nothing keeps this key honest but the capture-at-open invariant, which is why that
         * invariant is asserted in a test rather than only stated here.
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

        /**
         * Whether a pointer event is the pointer moving, rather than the menu's window appearing,
         * growing or moving under a pointer that stayed where it was. Windows reports a window
         * that changes under the cursor as a mouse move, and the menu took it for the user
         * pointing: a submenu opened from the keyboard over a resting pointer closed again at
         * once, the highlight jumping to the row under the pointer, and NVDA read that row
         * (measured on the Windows guest, 2026-09-29). A native menu follows the mouse only once
         * it moves. The first event, when nothing is known of where the pointer was, counts.
         */
        private boolean pointerMoved(MouseEvent event) {
            float sx = popupWindow != null ? popupWindow.screenX() + event.x() * screenFactor : event.x();
            float sy = popupWindow != null ? popupWindow.screenY() + event.y() * screenFactor : event.y();
            boolean still = Math.abs(sx - pointerScreenX) < 0.5f && Math.abs(sy - pointerScreenY) < 0.5f;
            pointerScreenX = sx;
            pointerScreenY = sy;
            return !still;
        }

        /** Hover in layout space: highlight (and open the submenu of) the item under the pointer. */
        void moveAt(float lx, float ly) {
            if (closed) {
                return;
            }
            int[] target = hit(lx, ly);
            if (target != null && target[1] >= 0
                    && hintBandDirection(cols.get(target[0]), ly) == 0) {
                hoverItem(target[0], target[1]);
            }
        }

        /** Press in layout space: a press outside every column dismisses the menu. */
        void pressAt(float lx, float ly) {
            if (closed) {
                return;
            }
            if (hit(lx, ly) == null) {
                dismiss();
            }
        }

        /** Click in layout space: choose (or open the submenu of) the item under the pointer. */
        void clickAt(float lx, float ly) {
            if (closed) {
                return; // a second click on the fading menu chooses nothing
            }
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
            if (col.highlight == i && c + 1 < cols.size() && cols.get(c + 1).parentItem == i) {
                // The row whose submenu is already open: nothing to change. Rebuilding it here
                // closed every deeper column and sent the submenu's highlight back to its first
                // row on each pointer move, so a 1 pt twitch of the mouse undid what the keyboard
                // had done inside the submenu.
                return;
            }
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
                runChosen(item); // a click on the row
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
            if (closed) {
                lateKey(LateKey.key(event));
                event.consume();
                return;
            }
            typed = event.layoutCodePoint(); // a letter is also matched as the layout types it
            try {
                if (event.isPressed() && handleKey(event.shortcutKey(), event.modifiers())) {
                    event.consume();
                }
            } finally {
                typed = -1;
            }
        }

        /** What the layout types with the key being handled, or -1; see handleMnemonic. */
        private int typed = -1;

        @Override
        protected void onCharTyped(CharEvent event) {
            if (closed) {
                lateKey(LateKey.character(event.codepoint()));
                event.consume();
            }
        }

        /** A key or a character this surface is handed as if it had reached it: see onKeyEvent. */
        void keyArrived(LateKey key) {
            if (closed) {
                lateKey(key);
            } else if (key.codepoint() < 0 && key.pressed()) {
                handleKey(key.key(), key.modifiers());
            }
        }

        /**
         * What a key, or a character, that reaches this surface after its menu closed becomes. The
         * menu answers none of it: a second Enter or Space there chose the highlighted item again,
         * and so did a second click. After a menu bar's walk it goes to the menu walked to, as if
         * it had reached that menu's window, and that menu's own rules decide it from there: it
         * walks on, or, that menu having closed too, it is passed on or dropped as below.
         */
        void lateKey(LateKey key) {
            if (walkedTo != null) {
                walkedTo.keyFromWalk(key);
            } else {
                passOnWhileClosing(key);
            }
        }

        /**
         * Marks the menu this surface shows as closed. The surface goes on being drawn while its
         * window or its overlay fades out, and goes on receiving input meanwhile.
         *
         * @param ownerScene the scene the menu was opened over
         * @param ownLock    whether the menu still holds its own modal lock over the owner
         */
        void closed(Scene ownerScene, boolean ownLock) {
            closed = true;
            ownerAtClose = ownerScene;
            ownLockAtClose = ownLock;
        }

        /**
         * Where a key, or a character, that reaches this surface after its menu closed goes when
         * no walk took the menu's place.
         *
         * <p>After a {@linkplain PopupMenu#dismiss dismissal}, in a window of its own, it goes to
         * the owner's scene, as if the menu were already gone: the owner is where the keyboard
         * goes, and the fading window holds it until the end of its fade, when the owner asks for
         * it back. A Tab or a letter typed right after Escape then lands where the person expects.
         * Not while a modal other than the menu's own lock blocks the owner, which has the
         * keyboard then.
         *
         * <p>Dropped everywhere else, because nothing says where the keyboard goes. After a choice
         * the owner was asked for it at once, and a key reaches the fading window only until the
         * window manager acts on that, while a window the item opened is asked after the owner and
         * is the one that should have it: an Enter given to the owner instead would press its
         * focused button under the dialog the person is looking at. A menu closed by the
         * application gives no hint either. And in scene the fading overlay still holds the owner
         * scene's focus until the fade ends, so that scene's input would hand the key straight
         * back to it.
         */
        private void passOnWhileClosing(LateKey key) {
            Scene target = ownerAtClose;
            NativeWindow ownerWindow = target == null ? null : target.window();
            if (!keysGoToOwner || target == null || scene() == target || ownerWindow == null
                    || ownerWindow.isClosed()
                    || (ownerWindow.isModalBlocked() && !ownLockAtClose)) {
                return;
            }
            WindowInput input = SceneAccess.input(target);
            key.replay(input);
            input.inputBatchEnded();
        }

        /** Handles a navigation key in the deepest open column; {@code @return} whether handled. */
        boolean handleKey(int key, int modifiers) {
            if (closed) {
                return false;
            }
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
                        // Selectable as well: a row disabled while it held the highlight keeps it,
                        // and only the click path used to ask.
                        if (i >= 0 && col.menu.items().get(i).hasSubmenu()
                                && col.menu.items().get(i).isSelectable()) {
                            openSubmenu(deep, i);
                        } else if (deep == 0) {
                            if (onRootTrailing != null) {
                                onRootTrailing.run(); // MenuBar: walk to the next menu
                            }
                            return true;
                        }
                    } else if (deep > 0) {
                        truncateTo(deep - 1);
                    } else {
                        if (onRootLeading != null) {
                            onRootLeading.run(); // MenuBar: walk to the previous menu
                        }
                        return true;
                    }
                }
                case Keys.ENTER, Keys.SPACE -> {
                    int i = col.highlight;
                    if (i >= 0) {
                        MenuItem item = col.menu.items().get(i);
                        if (!item.isSelectable()) {
                            return true; // disabled while highlighted: neither opens nor runs
                        }
                        if (item.hasSubmenu()) {
                            openSubmenu(deep, i);
                        } else {
                            runChosen(item); // Enter or Space on the highlight
                            return true;
                        }
                    }
                }
                case Keys.ESCAPE -> {
                    if (deep > 0) {
                        truncateTo(deep - 1);
                    } else {
                        dismiss();
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
                if (!items.get(i).matchesMnemonic(key, typed)) {
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

        // ----------------------------------------------------------- accessibility

        /**
         * The capture layer, and the whole open cascade hanging under it as synthetic children.
         *
         * <p><b>This node is not the menu, and its box is never a menu's box.</b> In scene the
         * surface is an overlay and the scene lays every overlay out at the scene's own origin
         * and size, so its rectangle is the whole window; in a window of its own the window is
         * sized to {@link #boundingBox}, the union of every open column, so its rectangle equals
         * the root column only until a submenu opens. What the rectangle honestly is, in both
         * presentations, is the region a press dismisses the menu from — {@link #pressAt} closes
         * on any point that misses every column — which is why the role is {@code GROUP}, why
         * {@code CANCEL} is the verb, and why no {@link Accessibility#bounds} call appears here.
         * The columns beneath carry the menus' role and the menus' rectangles.
         *
         * <p><b>It cannot be transparent and it owes a name.</b> The constructor makes it
         * focusable and both mountings give it the focus — a native popup scene's focus traverse,
         * {@code pushOverlay} in scene — so it is the focused widget for the whole life of the
         * cascade. A focusable node with no declared role is published {@code UNKNOWN} with a
         * warning, and one with no name at all is what the gallery rule refuses; the class holds
         * no string of its own and no application can reach a private inner class to name it, so
         * the toolkit supplies one.
         *
         * <p><b>The hover is published here, unlike everywhere else in this toolkit.</b>
         * {@link #hoverItem} writes {@code col.highlight}, which is the very field
         * {@link #handleKey} moves and {@link #paintColumn} highlights: in a menu the pointer's
         * hover and the keyboard's cursor are one thing, so publishing it is publishing the
         * cursor rather than announcing a row to a user whose keyboard is elsewhere.
         *
         * <p><b>Neither modality nor the link to the opener is declared.</b> The walk stamps
         * {@code MODAL} on a parentless top overlay before the transparency predicate runs, and
         * in a window of its own the modality is the popup window's own
         * ({@code NativeWindow#isModal}, set by {@code Backend#pushModal}); declaring it here
         * would publish it twice in one mounting and lie about a non-modal menu in the other. The
         * surface is parentless with an inheritance host in both mountings, which is exactly the
         * gate the walk turns into {@code POPUP_FOR} and the opener's {@code CONTROLLER_FOR}.
         *
         * <p><b>Nothing here formats.</b> Every name is an {@link I18nString} the model holds,
         * every key binding is the string the {@link Column} resolved when it was built, and
         * everything else is a primitive out of a column's arrays. No counter is owed and no
         * damaged frame allocates, which matters more here than in most widgets: the in-scene
         * overlay damages itself on every frame of its fade, and a hover damages two rows.
         *
         * @param a the builder for this node
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.GROUP);
            if (!drivenFromOwner()) {
                a.name(ComponentStrings.MENU_POPUP, Accessible.NameFrom.CONTENT);
            }
            // Published in the opener's tree and driven from the opener's keyboard, it is never
            // focused and names nothing: an unnamed grouping is what NVDA and VoiceOver pass over,
            // where this one's "Menu, grupo" was read before every menu's first row (2026-09-29).
            // The menu's own column carries the role a reader announces.
            // No selection facet of its own (decision 106, 2026-09-22): the rows are members of
            // the MENU column below, which publishes the selection they resolve to, and until
            // then this panel published a second one above it that no member belonged to (ADR
            // 045 §1.3, the one GROUP that classified as ROWS). Focus never leaves this node, and
            // the moved cursor still becomes an active-descendant event: the ACTIVE row is the
            // first active node below the focused one, whichever container carries the facet.
            // The single-argument form; the variable-argument one allocates an array per call.
            a.action(Accessible.Action.CANCEL);
            if (!cols.isEmpty()) {
                describeColumn(a, 0);
            }
        }

        /**
         * Publishes column {@code c}, its rows, and — nested under the row that opened it — the
         * column below it, which is the nesting all three platforms expect of a cascade.
         *
         * <p>Every box is the paint's own conversion of the coordinates the column already holds:
         * {@code col.x - offsetX}, {@code col.y - offsetY}, and {@link Column#visibleH} rather
         * than {@link Column#h}, because the clamped on-screen height is what the column occupies
         * and what {@link #hit} tests against. Nothing is re-derived from the layout direction
         * here: the columns were placed with the direction the cascade was opened at and hold the
         * result, so a second resolution could only disagree with the first.
         *
         * <p>The row loop is clamped the way {@link #paintColumn}'s is, because a live-mutated
         * {@link Menu} leaves the geometry snapshot lagging for one frame. The rebuild that fixes
         * it is already posted from the paint, and is deliberately not triggered from here: a
         * describe hook that repaired the model would be a second authority on when it happens.
         *
         * @param a the builder
         * @param c the column's index in {@link #cols}
         */
        private void describeColumn(Accessibility a, int c) {
            Column col = cols.get(c);
            a.child(COLUMN_KEY);
            a.bounds(col.x - offsetX, col.y - offsetY, col.w, col.visibleH);
            // The MENU shape's container half, written once (ADR 045 §3; decision 98).
            MenuAccessibility.describeMenu(a);
            if (c > 0) {
                // A submenu is titled by the row that opened it, which is what all three
                // platforms show. The root column is left unnamed: nothing names it, and the
                // layer above already carries a name.
                MenuItem opener = openerOf(c);
                a.name(opener == null ? null : opener.labelForReader(),
                        opener == null ? 0 : opener.labelWitness(), Accessible.NameFrom.CONTENT);
            }
            // Published on every column on every frame, and from the same expressions the clamp
            // uses, so that reaching the clamp moves two numbers instead of making a facet appear
            // and disappear. The horizontal pair is the axis's nothing-to-scroll answer even
            // though Column.fit clamps the WIDTH as well: a width-clamped column clips its labels
            // with no scroll route at all, and that costs a reader nothing, because the name
            // comes from the model and not from the pixels.
            float max = col.maxScroll();
            a.scrollFrom(0, 0, 1, 0, col.scroll, max, col.visibleH, col.h);
            if (hintBandIsLive(col, true)) {
                describeBand(a, c, col, true);
            }
            List<MenuItem> items = col.menu.items();
            int rows = Math.min(items.size(), col.top.length);
            int size = 0;
            for (int i = 0; i < rows; i++) {
                if (!items.get(i).isSeparator()) {
                    size++;
                }
            }
            boolean deepest = c == cols.size() - 1;
            int position = 0;
            for (int i = 0; i < rows; i++) {
                MenuItem item = items.get(i);
                if (col.collapsed[i]) {
                    continue; // not drawn, so not published
                }
                // The counter MenuItem mints once per item and never reuses, never the index:
                // rebuildStaleColumns replaces a Column in place, and an index-keyed row would
                // hand row three's identifier to row nine after an insert.
                a.child(item.serial());
                // The paint's row formula with the scroll subtracted, at the column's full width
                // and the row's full height -- never the highlight's inset band, because hit()
                // maps the whole row, and never clamped to the visible band, because clamping
                // would republish a row's box on every scrolled pixel.
                a.bounds(col.x - offsetX, col.y - offsetY + col.top[i] - col.scroll,
                        col.w, col.hgt[i]);
                if (col.top[i] - col.scroll + col.hgt[i] < 0
                        || col.top[i] - col.scroll > col.visibleH) {
                    // The paint's skip test, negated, in column-local terms. Every row is still
                    // published: its position in the set does not change with the scroll, and
                    // offScreen is the only route to "visible but not showing".
                    a.offScreen();
                }
                if (item.isSeparator()) {
                    // A real hit region that does nothing: no name, no verb, no place in the set.
                    a.role(Accessible.Role.SEPARATOR);
                    a.endChild();
                    continue;
                }
                a.role(item.kind() == MenuItem.Kind.CHECK
                        ? Accessible.Role.CHECK_MENU_ITEM : Accessible.Role.MENU_ITEM);
                // The words, the access letter's mark taken out ("Sa&lvar" is heard "Salvar"),
                // held by the item until they change, so a frame that changed nothing allocates
                // nothing; never mnemonicIndex(), which resolves its own.
                a.name(item.labelForReader(), item.labelWitness(), Accessible.NameFrom.CONTENT);
                if (!item.isEnabled()) {
                    // Said, not only left without verbs (semantics 5, the disabled axis; the 2d
                    // review, 2026-09-15): a reader hears a disabled Cut row as unavailable rather
                    // than as an ordinary row with nothing to do. Narrowing takes every verb and
                    // the key binding off the row, a disabled submenu row's SHOW_MENU and EXPAND
                    // included, which chooseItem refused while the hook answered done.
                    a.disabled();
                }
                boolean current = i == col.highlight && item.isSelectable();
                boolean expanded = item.hasSubmenu()
                        && c + 1 < cols.size() && cols.get(c + 1).parentItem == i;
                // A row by the rules of the MENU shape (MenuAccessibility.describeRow, ADR 045
                // §3; decision 98). ACTIVE in the deepest column and nowhere else: the surface
                // holds the focus and its cursor is the FIRST node published active in document
                // order below it (ADR 039 §1.10, amended 2026-09-14), while the cursor keys act
                // on the deepest column; a parent column's highlight is its SELECTED row and
                // nothing more. FOCUS on every row the cursor can land on (decision 11): a
                // disabled row and a rule are skipped by the arrows and get none; a submenu with
                // nothing in it is landed on and keeps it, though it has nothing to choose and
                // so gets no other verb -- hasSubmenu() is false there while isSelectable()
                // stays true and activate() is a no-op.
                MenuAccessibility.describeRow(a, kindOf(item), item.isSelectable(), current,
                        ++position, size, item.hasSubmenu(), expanded, current && deepest,
                        item.isSelectable(), item.isChecked());
                if (item.isSelectable()) {
                    // The letter that chooses the row while its menu is open, beside the
                    // accelerator below, which runs the command from anywhere.
                    a.accessKey(item.accessKey());
                }
                if (expanded) {
                    describeColumn(a, c + 1);
                } else if (!item.hasSubmenu() && item.isSelectable()
                        && item.kind() != MenuItem.Kind.SUBMENU && col.accel[i] != null) {
                    // The string the column resolved when it was built, which is also what its
                    // width was measured against; accelerator().display() would build one per
                    // row per frame and be a second authority on the spelling.
                    a.keyBinding(col.accel[i]);
                }
                a.endChild();
            }
            if (hintBandIsLive(col, false)) {
                describeBand(a, c, col, false);
            }
            a.endChild();
        }

        private static MenuAccessibility.Kind kindOf(MenuItem item) {
            // What the menu shape calls a row: a check, a submenu (with rows or without), or a
            // command; a separator is never described as a row.
            return switch (item.kind()) {
                case CHECK -> MenuAccessibility.Kind.CHECK;
                case SUBMENU -> MenuAccessibility.Kind.SUBMENU;
                default -> MenuAccessibility.Kind.COMMAND;
            };
        }

        /**
         * @param c a submenu column's index
         * @return the row that opened it, or {@code null} when the parent column's {@link Menu} has
         *         been mutated out from under the snapshot and that row is gone
         */
        private @Nullable MenuItem openerOf(int c) {
            List<MenuItem> items = cols.get(c - 1).menu.items();
            int i = cols.get(c).parentItem;
            return i >= 0 && i < items.size() ? items.get(i) : null;
        }

        /**
         * Publishes one scroll-hint band as the region a click on it lands in.
         *
         * <p>These are controls rather than veneer, and the tree has to say so:
         * {@link #clickAt} gives {@link #hintBandDirection} priority over the row painted beneath
         * the band, so a platform hit test that resolved such a point to a row would tell a user
         * that pressing there chooses "Save" when it scrolls.
         *
         * <p><b>The box is the hit region and not the painted one.</b> {@link #paintScrollHint}
         * insets by {@link Strokes#ROW_CLIP} on both sides and starts the top band a point below
         * the column's edge, while {@code hintBandDirection} measures from {@code col.y} across
         * the column's whole width. What is published is the box a click lands in.
         *
         * <p><b>A parked side is not published at all</b>, which is why this is only ever called
         * for a live one and the verb is unconditional. It could not be published as a disabled
         * button instead: a widget cannot say that of a synthetic child by any route, because the
         * builder refuses the states the publish step owns and the walk overwrites a synthetic
         * node's enabled bit with its owner's &mdash; so the alternative to leaving it out is an
         * enabled, on-screen button over a rectangle where nothing is drawn and where a click
         * chooses the row beneath. The caller's condition is {@link #hintBandIsLive}, which the
         * paint and the hit test ask too, so the three agree by construction.
         *
         * @param a   the builder
         * @param c   the column's index, encoded into the key because
         *            {@link #onSyntheticAction} is given the key alone and could not otherwise
         *            tell two columns' bands apart
         * @param col that column
         * @param up  whether this is the band that scrolls back toward the first item
         */
        private void describeBand(Accessibility a, int c, Column col, boolean up) {
            a.child(up ? -(2L * c + 1) : -(2L * c + 2));
            a.bounds(col.x - offsetX,
                    col.y - offsetY + (up ? 0 : col.visibleH - Strokes.MENU_SCROLL_HINT_H),
                    col.w, Strokes.MENU_SCROLL_HINT_H);
            a.role(Accessible.Role.BUTTON);
            a.name(up ? ComponentStrings.MENU_SCROLL_PREVIOUS : ComponentStrings.MENU_SCROLL_NEXT,
                    Accessible.NameFrom.CONTENT);
            a.action(Accessible.Action.PRESS);
            a.endChild();
        }

        /**
         * Dismisses the whole cascade, through the path a press on this layer already takes.
         *
         * <p>{@code CANCEL} and nothing else, and it reaches {@link PopupMenu#close} rather than
         * {@code handleKey(ESCAPE)} because the verb has to mean what the box means: Escape at
         * depth closes one column, while a press on the capture layer closes the cascade, and
         * this node's box is the capture layer.
         *
         * @param action what is being asked
         * @param arg    unused; the one verb here is parameterless
         * @return whether this surface did it
         */
        @Override
        protected boolean onAccessibilityAction(Accessible.Action action,
                                                Accessible.Argument arg) {
            if (action != Accessible.Action.CANCEL || closed) {
                return false;
            }
            dismiss();
            return true;
        }

        /**
         * Chooses a row, opens or closes a submenu, or steps a column's scroll, through the same
         * private paths the pointer uses.
         *
         * <p>The three kinds of key are told apart by their value alone, which is all this hook is
         * given: {@link PopupMenu#COLUMN_KEY} is a column, which does nothing; a negative key is a
         * scroll band with its column's index encoded in it; a positive one is a
         * {@link MenuItem#serial()}. A serial is searched for rather than indexed, and the
         * outermost occurrence wins — two are possible only for a self-referential {@link Menu},
         * where a click would choose the same one.
         *
         * <p>Every route lands in {@link #chooseItem}, {@link #truncateTo} or
         * {@link #scrollColumn}, which are literally the branches {@link #clickAt} calls, so an
         * assistive technology's press runs the application's {@code Runnable} or flips the check
         * and reports it exactly as a click does, and its open builds the same column with the
         * same identifiers. Nothing gains a public entry point for it.
         *
         * <p>The refusals are facts rather than guards against a caller. A submenu already open is
         * refused rather than reopened, because reopening truncates the cascade and builds another
         * one with new identifiers, which the pointer never does. A collapse addressed to a row
         * whose submenu is not open closes nothing. A disabled row, a separator and a submenu row
         * with nothing in it are refused because none of them can be chosen — {@code chooseItem}
         * refuses the first two silently and {@code activate()} is a no-op on the third, and a
         * hook that answered true there would report an action that did nothing. A band press is
         * answered by whether the scroll actually moved.
         *
         * <p>And the verbs answered are exactly the verbs {@link #describeColumn} publishes for
         * the row's state: {@code SHOW_MENU} and {@code EXPAND} open a closed submenu row,
         * {@code COLLAPSE} closes the open one, {@code PRESS} chooses a command or check row and
         * {@code TOGGLE} a check row alone, and {@code FOCUS} moves the highlight onto any row the
         * arrows can reach without choosing or opening it, closing whatever column was open below
         * that row's own. {@code PRESS} on a submenu row and {@code TOGGLE} on a command row were
         * accepted as unpublished synonyms until 2026-09-14 and are refused now, because a verb a
         * node performs without publishing is one a reader cannot see and one platform invokes by
         * accident.
         *
         * @param key    a column key, a band key, or a row's serial
         * @param action what is being asked
         * @param arg    unused; every verb here is parameterless
         * @return whether this surface did it
         */
        @Override
        protected boolean onSyntheticAction(long key, Accessible.Action action,
                                            Accessible.Argument arg) {
            if (key == COLUMN_KEY || closed) {
                return false; // a closed menu chooses, opens and scrolls nothing
            }
            if (key < 0) {
                return pressBand(key, action);
            }
            for (int c = 0; c < cols.size(); c++) {
                Column col = cols.get(c);
                List<MenuItem> items = col.menu.items();
                int rows = Math.min(items.size(), col.top.length);
                for (int i = 0; i < rows; i++) {
                    if (items.get(i).serial() == key) {
                        return chooseRow(c, i, items.get(i), action);
                    }
                }
            }
            return false;
        }

        /**
         * @param c      the column the row is in
         * @param i      its index there
         * @param item   the row
         * @param action what is being asked
         * @return whether it was done
         */
        private boolean chooseRow(int c, int i, MenuItem item, Accessible.Action action) {
            // By the rules of the MENU shape (MenuAccessibility.performOnRow, ADR 045 §3) over
            // the cascade's own mechanisms in MenuHost.
            return MenuAccessibility.performOnRow(menuHost, new RowRef(c, i, item), action);
        }

        /** A row addressed by a verb: its column, its index there and the item it shows. */
        private record RowRef(int c, int i, MenuItem item) {
        }

        /**
         * The cascade's mechanisms as the menu shape drives them. {@code FOCUS} has the cursor
         * keys' own shape: the row's column becomes the deepest (a submenu open below it closes,
         * as the leading arrow closes one), the highlight moves and is revealed, nothing is
         * chosen and nothing opens. An open and a choice go through {@link #chooseItem}, which
         * is literally the branch {@link #clickAt} calls, so an assistive technology's press runs
         * the application's {@code Runnable} or flips the check exactly as a click does, and its
         * open builds the same column with the same identifiers.
         */
        private final class MenuHost implements MenuAccessibility.Host<RowRef> {
            @Override
            public MenuAccessibility.Kind kindOf(RowRef row) {
                return MenuSurface.kindOf(row.item());
            }

            @Override
            public boolean isSelectable(RowRef row) {
                return row.item().isSelectable();
            }

            @Override
            public boolean hasSubmenu(RowRef row) {
                return row.item().hasSubmenu();
            }

            @Override
            public boolean isOpen(RowRef row) {
                return row.c() + 1 < cols.size() && cols.get(row.c() + 1).parentItem == row.i();
            }

            @Override
            public boolean canFocus(RowRef row) {
                return true;
            }

            @Override
            public boolean moveCursor(RowRef row) {
                truncateTo(row.c());
                Column col = cols.get(row.c());
                col.reveal(col.highlight = row.i());
                changed();
                return true;
            }

            @Override
            public boolean open(RowRef row) {
                chooseItem(row.c(), row.i()); // truncates, moves the highlight, opens: the click branch
                return true;
            }

            @Override
            public void close(RowRef row) {
                truncateTo(row.c());
                changed();
            }

            @Override
            public void choose(RowRef row) {
                chooseItem(row.c(), row.i()); // activate() then close(), which is what a click does
            }
        }

        private final MenuHost menuHost = new MenuHost();

        /**
         * @param key    a band key, which encodes its column's index and its side
         * @param action what is being asked
         * @return whether the column's scroll moved
         */
        private boolean pressBand(long key, Accessible.Action action) {
            if (action != Accessible.Action.PRESS) {
                return false;
            }
            long encoded = -key;
            int c = (int) ((encoded - 1) / 2);
            boolean up = encoded % 2 == 1;
            if (c < 0 || c >= cols.size()) {
                return false; // a key from a tree this cascade no longer has
            }
            Column col = cols.get(c);
            // The same question the tree asked when it published the band, asked again because a
            // key outlives the snapshot it was minted in: the column may have reached this side's
            // clamp between the frame a reader read and the press it sent.
            if (!hintBandIsLive(col, up)) {
                return false;
            }
            float before = col.scroll;
            scrollColumn(col, (up ? -1 : 1) * Strokes.WHEEL_STEP); // the expression clickAt uses
            return col.scroll != before;
        }
    }

    /**
     * One rendered menu column: its {@link Menu}, the parent item index that
     * opened it (−1 for the root), the per-item vertical extents, its highlighted
     * row, and its computed position/size in layout space.
     */
    /**
     * Which of {@code items} are separators the popup does not draw: a separator with no item
     * before it, one with no item after it, and every separator after the first of a run. The
     * model keeps them, so a menu built with conditions that skipped a group still reads as the
     * menu written by hand.
     */
    static boolean[] collapsedSeparators(List<MenuItem> items) {
        boolean[] collapsed = new boolean[items.size()];
        boolean itemBefore = false;
        int pending = -1; // the separator kept so far, if an item has yet to follow it
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isSeparator()) {
                if (!itemBefore || pending >= 0) {
                    collapsed[i] = true;
                } else {
                    pending = i;
                }
            } else {
                itemBefore = true;
                pending = -1;
            }
        }
        if (pending >= 0) {
            collapsed[pending] = true; // nothing came after it
        }
        return collapsed;
    }

    /** The side of an item's picture: the line's own height, so it sits with the text. */
    private static float iconSide(TextRuler ruler, SizeTokens t) {
        return Math.round(ruler.measure("Hg", t.body()).height());
    }

    private final class Column {
        final Menu menu;
        final int parentItem;
        final int builtModCount; // Menu.modCount() this geometry was built from
        final float[] top;  // per-item top, relative to the column's CONTENT top
        final float[] hgt;  // per-item height
        /**
         * Separators the column does not draw: one with nothing before it, one with nothing after
         * it, and each after the first of a run. Such a row keeps its place in the model at zero
         * height, so no pointer lands on it and no reader is told about it.
         */
        final boolean[] collapsed;
        /**
         * Per-item accelerator hint, or {@code null} where there is none. Resolved once here, not
         * re-derived in the paint: the string is what the column's width was measured against, and
         * a second call to {@code display()} at paint time would be a second authority on it.
         */
        final String[] accel;
        /**
         * Per-item label as one shaped line, {@code null} for a separator: the value the width
         * pass sized this column from and the value the paint draws, so the two cannot be
         * different shapings of the same string.
         *
         * <p>Held for the reason {@link #accel} is held and for one more. A mirrored column aligns
         * a label by its own width, and deriving that in the paint would shape every visible row
         * of every column on every frame. And the width has to come from a line shaped for
         * <em>this cascade's</em> paragraph direction: a label with no strong character in it —
         * a menu of years, of channel numbers, of file sizes — falls back to the direction of the
         * interface, and a width taken without that fallback is the width of a different line.
         */
        final ShapedText[] label;
        /** Per-item accelerator hint as one shaped line, {@code null} where {@link #accel} is. */
        final ShapedText[] accelLine;
        /**
         * The room every row keeps before its label for a picture, zero when no item of the
         * column has one: a column pays for it once, so the labels stay in one line.
         */
        final float iconSlot;
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
            collapsed = collapsedSeparators(items);
            accel = new String[items.size()];
            label = new ShapedText[items.size()];
            accelLine = new ShapedText[items.size()];
            TextRuler ruler = surface.ruler;
            // The cascade's row, captured at open. Measuring here with a freshly resolved step
            // while paintColumn resolved another is exactly the defect this field prevents.
            SizeTokens t = tokens;
            Font font = t.body();
            // The cascade's own direction, resolved once for every line this column shapes. It is
            // fixed for the life of an open cascade (see PopupMenu#direction), which is what lets
            // these lines be a snapshot at all: a direction that could move under them would make
            // every width here an answer to a question nobody is still asking.
            ShapedText.Direction base = isRtl() ? ShapedText.Direction.RTL : ShapedText.Direction.LTR;
            float maxLabel = 0;
            float maxAccel = 0;
            boolean anyIcon = false;
            float yy = t.menuPadV();
            for (int i = 0; i < items.size(); i++) {
                MenuItem item = items.get(i);
                // separatorBox is odd at every step so the 1pt rule centred in it stays crisp;
                // menuRowHeight is >= 24 at every step, which is what makes a menu row a
                // conformant pointer target without any hit mechanism (rows are painted, not
                // widgets, so nothing outside this class can enlarge them).
                float ih = collapsed[i] ? 0 : item.isSeparator() ? t.separatorBox() : t.menuRowHeight();
                top[i] = yy;
                hgt[i] = ih;
                yy += ih;
                if (!item.isSeparator()) {
                    anyIcon |= item.icon() != null;
                    String text = item.label();
                    label[i] = ruler.shape(text, font, ShapedText.Direction.of(text, base));
                    maxLabel = Math.max(maxLabel, label[i].metrics().width());
                    Accelerator shortcut = item.accelerator();
                    if (shortcut != null) {
                        accel[i] = shortcut.display();
                        // The hint names physical keys and is never reversed, and mostly says so
                        // in its own first character -- "F5", "Ctrl+N" -- so the first-strong rule
                        // decides it and this fallback is never reached. Shaped through the same
                        // call regardless, so that a hint which IS neutral is decided rather than
                        // defaulted, and so that the width reserved is this line's own.
                        accelLine[i] = ruler.shape(accel[i], font,
                                ShapedText.Direction.of(accel[i], base));
                        maxAccel = Math.max(maxAccel, accelLine[i].metrics().width());
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
            this.iconSlot = anyIcon ? iconSide(ruler, t) + t.spacingSmall() : 0;
            this.contentW = Math.max(t.menuMinWidth(),
                    t.menuCheckGutter() + iconSlot + maxLabel + hintColumn + t.menuArrowGutter());
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

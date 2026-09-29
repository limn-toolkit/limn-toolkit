package limn.backend;

import org.jspecify.annotations.Nullable;

/**
 * A top-level native window. Sizes exposed to the toolkit are in <em>logical
 * points</em>; the backend multiplies by the monitor's {@linkplain
 * #contentScale() content scale} (a float: 1.0, 1.25, 1.5, 2.0…) to obtain
 * physical framebuffer pixels. All methods are UI-thread-only unless noted.
 */
public interface NativeWindow extends AutoCloseable {

    /** Notified when the window moves to a monitor with a different content scale. */
    @FunctionalInterface
    interface ContentScaleListener {
        void onContentScaleChanged(float newScale);
    }

    /** @return the current title */
    String title();

    /** Sets the native title bar text. */
    void setTitle(String title);

    /** @return width in logical points (framebuffer width ÷ content scale) */
    float logicalWidth();

    /** @return height in logical points (framebuffer height ÷ content scale) */
    float logicalHeight();

    /** @return framebuffer width in physical pixels */
    int framebufferWidth();

    /** @return framebuffer height in physical pixels */
    int framebufferHeight();

    /**
     * @return the content scale in effect for this window: the monitor's, or
     *         the {@linkplain #overrideContentScale(float) forced} one.
     *         Fractional values (1.25, 1.5, 1.75) are first-class citizens,
     *         never assume integer
     */
    float contentScale();

    /**
     * Forces the rendering content scale, ignoring the monitor's. Screenshot
     * scenes use it to validate 1.0/1.25/1.5/2.0 rendering on any monitor.
     * Pass {@code 0} to return to the monitor scale.
     */
    void overrideContentScale(float scale);

    /** Resizes the window to the given size in logical points. */
    void setSize(int width, int height);

    /**
     * Constrains user resizing to the given bounds in logical points; pass
     * {@code <= 0} for any bound to leave it unconstrained. Only affects
     * interactive resizes; {@link #setSize} is not clamped. A window made from a
     * {@link WindowConfig} with {@link WindowConfig#minSize} or {@link WindowConfig#maxSize}
     * starts with those. The default implementation ignores it (headless/embedding).
     * UI thread only.
     */
    default void setSizeLimits(int minWidth, int minHeight, int maxWidth, int maxHeight) {
    }

    /**
     * @return the bounds the user may resize this window within, as its configuration or the last
     *         {@link #setSizeLimits} set them; {@link SizeLimits#NONE} where the implementation
     *         keeps none (headless, embedding)
     */
    default SizeLimits sizeLimits() {
        return SizeLimits.NONE;
    }

    /**
     * Sets the window icon (title bar / taskbar) from one or more candidate
     * sizes; the platform picks the closest (16–48px covers the common slots).
     * No-op where the platform has no per-window icons (macOS uses the app
     * bundle's icon; some Wayland compositors ignore it). The default
     * implementation ignores it (headless/embedding). UI thread only.
     */
    default void setIcon(limn.graphics.Image... icons) {
    }

    /**
     * Sets the handler asked whether this window may close: when the <em>user</em> closes it (the
     * OS close button, Alt-F4, Cmd-W), and when the application is ending
     * ({@link Backend#requestQuit()}). It answers through the {@link CloseRequest} it is handed, at
     * once or later, so an "unsaved changes" dialog can decide. With no handler, the window agrees.
     * A program's {@link #requestClose()} bypasses it. {@code null} clears. UI thread only.
     *
     * <p>A window registered with an owner through {@link #registerChildPopup} (a native dialog, a
     * menu) is not asked when the application ends: its owner speaks for it, and it closes with it.
     *
     * <p>No default: a backend that ignored it lost an application's "unsaved changes"
     * veto silently. A backend with no close gesture of its own (headless, embedded) keeps the
     * handler, asks it only when the application ends, and says so in its implementation.
     *
     * @param handler the handler, or {@code null} for none
     */
    void setCloseRequestHandler(@Nullable CloseRequestHandler handler);

    /** @return the handler set with {@link #setCloseRequestHandler}, or {@code null} for none */
    @Nullable CloseRequestHandler closeRequestHandler();

    /**
     * Sets what a user's close does once it is allowed: dispose the window (the default), hide it,
     * or end the application. A program's {@link #requestClose()} disposes whatever this says. UI
     * thread only.
     *
     * @param policy the policy; never {@code null}
     * @throws NullPointerException if {@code policy} is {@code null}
     */
    void setClosePolicy(ClosePolicy policy);

    /** @return what a user's close does; {@link ClosePolicy#DISPOSE} unless set */
    ClosePolicy closePolicy();

    void show();

    void hide();

    /**
     * Sets the whole-window opacity in {@code [0..1]} at the compositor level,
     * the fade primitive behind {@link limn.scene.Scene#fadeWindow}. Affects
     * the entire window uniformly, decorations included. A no-op where the
     * platform's compositor does not support per-window opacity; the default
     * implementation ignores it (headless/embedding). UI thread only.
     */
    default void setOpacity(float opacity) {
    }

    /**
     * Sets the mouse cursor shown while the pointer is over this window. The
     * scene calls this as the hovered widget changes; {@code null} or
     * {@link Cursor#DEFAULT} restores the plain arrow. A shape the platform
     * cannot provide falls back to the arrow. The default implementation ignores
     * it (headless/embedding). UI thread only.
     */
    default void setCursor(@Nullable Cursor cursor) {
    }

    /** Brings this window to the front and gives it input focus. UI thread only. */
    void focus();

    /**
     * Enables or disables the platform input method (IME) for this window. The
     * scene turns it on while a text-editing widget holds focus and off
     * otherwise, so composition keys never leak into non-text UI. UI thread only.
     *
     * <p>No default, nor for the two IME methods below: a backend that ignored them
     * broke Chinese, Japanese and Korean input without a word. A backend with no input method
     * (headless) implements them as no-ops, on purpose and in writing.
     */
    void setImeEnabled(boolean enabled);

    /**
     * Positions the IME candidate/composition window at the caret, so it follows
     * the text being edited. {@code x}/{@code y}/{@code width}/{@code height} are
     * in logical points relative to the window's content area (the scene feeds
     * the focused widget's {@code caretRect}). UI thread only.
     */
    void setPreeditCaretRect(float x, float y, float width, float height);

    /**
     * Cancels any in-progress IME composition held by the platform for this
     * window. The scene calls it when focus leaves a text-editing widget, so a
     * composition started there can never commit into whatever is focused next.
     * A no-op when nothing is being composed. UI thread only.
     */
    void resetPreedit();

    /** @return whether the window is currently visible on screen */
    boolean isVisible();

    /** @return whether this window has already been closed/destroyed */
    boolean isClosed();

    /**
     * Makes the window fullscreen on the monitor it occupies, in one of two ways, chosen by whether
     * the call names a display mode.
     *
     * <p><b>Naming no mode</b>, {@code width}, {@code height} and {@code refreshRate} all &le; 0 as
     * {@link #enterFullscreen()} passes them, leaves the display in the mode it is in, which is
     * the fullscreen a document, a dashboard or any application that is not a game wants:
     * <ul>
     *   <li><b>macOS</b>: native fullscreen, what the green button and Control-Command-F do. The
     *       window moves to a Space of its own with an animation of most of a second, the menu bar
     *       and the title bar slide in when the pointer reaches the top of the screen, and the
     *       person can leave it the way they leave any application's. This call returns before the
     *       animation ends, and the window reports its new size when AppKit resizes it.</li>
     *   <li><b>Windows and X11</b>: a borderless window covering the monitor at the mode it is
     *       in, on X11 through the window manager's fullscreen state where it has one.</li>
     *   <li><b>Wayland</b>: the compositor's fullscreen.</li>
     * </ul>
     *
     * <p><b>Naming a mode</b>, a size or a refresh rate or both, is the exclusive fullscreen a game
     * asks for: the display is switched to the mode it lists nearest to the one named, which
     * holds the application for a noticeable moment each way (more than half a second on a Mac),
     * and is switched back by {@link #exitFullscreen()}. A dimension &le; 0 beside a named one is
     * the current one. The size is in screen coordinates, as a {@link Resolution}'s is: pixels on
     * Windows, X11 and Wayland, points on macOS. On macOS the window covers the menu bar and the
     * Dock and has no Space of its own; the mode a Retina display is in is usually not one it can
     * be switched to, so naming the current mode switches to another. Wayland lets no application
     * change the display's mode, and there the window is fullscreen at the compositor's whatever
     * is named.
     *
     * <p><b>Losing the focus</b> tells the two apart. A window at a named mode is minimized when
     * another window takes the focus, on every platform, and a switched mode is switched back until
     * the window is restored, which is what a game wants when the person switches away from it. A
     * window at the mode the display is in is not: on Windows, X11 and Wayland it stays fullscreen
     * behind the window that took the focus, as a browser's fullscreen does, and the person comes
     * back to it as they left it; on macOS the person moves to another Space and back.
     *
     * <p>A window already fullscreen one way and asked for the other leaves the first: a switched
     * mode is switched back before native fullscreen begins, and native fullscreen is left, with
     * its animation, before the mode is switched. The windowed position and size are remembered
     * for {@link #exitFullscreen()}. UI thread only.
     *
     * @param width       width in screen coordinates, or &le; 0 to name none
     * @param height      height in screen coordinates, or &le; 0 to name none
     * @param refreshRate refresh rate in Hz, or &le; 0 to name none
     */
    void enterFullscreen(int width, int height, int refreshRate);

    /**
     * Makes the window fullscreen without changing the display's mode: native fullscreen on macOS,
     * a borderless window covering the monitor at its current mode on Windows and X11, and the
     * compositor's fullscreen on Wayland. The fullscreen of an application rather than a game: it
     * is not minimized when another window takes the focus, but stays fullscreen behind it. See
     * {@link #enterFullscreen(int, int, int)}. UI thread only.
     */
    default void enterFullscreen() {
        enterFullscreen(0, 0, 0);
    }

    /**
     * Makes the window fullscreen at {@code mode}, switching the display to it: the exclusive
     * fullscreen a game asks for, on every platform but Wayland, which changes no display's mode.
     * The normalized form of {@link #enterFullscreen(int, int, int)} that takes a
     * {@link Resolution}, such as one from {@code display().availableResolutions()}. To keep the
     * display as it is, call {@link #enterFullscreen()} instead: on macOS a Retina display's current
     * mode is usually not among those a switch can reach. The window is minimized when another
     * window takes the focus, and the display switched back until it is restored. UI thread only.
     *
     * @param mode the mode to switch to
     * @throws NullPointerException if {@code mode} is null, as it is from a display that lists none
     */
    default void enterFullscreen(Resolution mode) {
        java.util.Objects.requireNonNull(mode, "mode");
        enterFullscreen(mode.width(), mode.height(), mode.refreshRate());
    }

    /**
     * Leaves fullscreen of either kind, and does nothing for a window that is not fullscreen. A
     * switched mode is switched back and the windowed position and size asked for before this
     * returns, and given back as the window had them: where the window manager answers later, as
     * on X11, a window it gives back maximized when it was not, or elsewhere or at another size, is
     * asked once more when the answer comes. macOS native fullscreen is left with its animation,
     * after this returns, and is left whether the application or the person entered it. UI thread
     * only.
     */
    void exitFullscreen();

    /**
     * Whether the window is fullscreen, of either kind. On macOS that includes native fullscreen
     * the person entered with the green button, Control-Command-F or the menu, and the answer is
     * where the window is going rather than where its animation has got to: {@code true} from the
     * moment {@link #enterFullscreen()} returns or the person starts the animation, {@code false}
     * from the moment {@link #exitFullscreen()} returns or the person starts leaving. Elsewhere it
     * is whether this window was made fullscreen and has not been told to leave.
     *
     * <p>Each change of this answer is told to the window's {@linkplain #setInput input}, through
     * {@link WindowInput#windowFullscreenChanged}, including the changes the person makes on
     * macOS. A window that can never be fullscreen never tells it.
     *
     * @return whether the window is, or is going to be, fullscreen
     */
    boolean isFullscreen();

    /**
     * @return whether this window is currently locked by an active modal (its
     *         input is ignored); the scene dims it while true
     */
    boolean isModalBlocked();

    /**
     * @return whether this window is itself an active modal: one the backend registered through
     *         {@link Backend#pushModal}, blocking the window it was opened over, or every other
     *         window when it was opened over none. The other end of {@link #isModalBlocked()}:
     *         that is the owner's bit and this is the dialog's, and it is this one an accessible
     *         tree publishes on the window's own node, because a client asks a window whether it
     *         blocks what it owns and never whether something else blocks it
     */
    boolean isModal();

    /**
     * What a registered child window <em>is</em>, which decides whether a modal over the owner
     * leaves it usable.
     *
     * <p>Both kinds close and move with the owner; they differ only under an in-scene modal,
     * which exempts its host so the host can keep drawing its own content outside its frame.
     * Without this distinction that exemption reaches anything the host registered, and a
     * non-modal dialog stayed clickable underneath a modal, where a native modal would have
     * frozen it, and where the application had every reason to expect it frozen.
     */
    enum PopupKind {
        /**
         * The owner's own content, drawn outside its frame because the OS gives it no other way:
         * a dropdown's list, a menu cascade, a tooltip. It stays usable under an in-scene modal
         * whose host is this window, because it <em>is</em> that host.
         */
        TRANSIENT,
        /**
         * A window in its own right that happens to be owned for lifetime and movement: a
         * non-modal dialog, a floating palette. A modal over the owner blocks it like any other
         * window.
         */
        OWNED_WINDOW
    }

    /**
     * Registers a child popup to be closed automatically when this window
     * closes, so a combo/menu popup can never outlive its parent.
     *
     * <p>Registers it as {@link PopupKind#TRANSIENT}, which is what a dropdown or a menu is.
     * A window of its own has to say so: see {@link #registerChildPopup(NativeWindow, PopupKind)}.
     */
    default void registerChildPopup(NativeWindow child) {
        registerChildPopup(child, PopupKind.TRANSIENT);
    }

    /**
     * Registers a child popup and says what it is; see {@link PopupKind}, which is the whole
     * reason this overload exists.
     */
    void registerChildPopup(NativeWindow child, PopupKind kind);

    /** Unregisters a child popup that closed on its own. */
    void unregisterChildPopup(NativeWindow child);

    /** Registers the per-frame render callback. */
    void setFrameCallback(FrameCallback callback);

    /** Registers the input sink (usually the scene). See {@link WindowInput}. */
    void setInput(WindowInput input);

    /** @return the backend that owns this window (popup creation, shutdown) */
    Backend backend();

    /** @return the system clipboard */
    Clipboard clipboard();

    /** @return window x in native screen coordinates */
    int screenX();

    /** @return window y in native screen coordinates */
    int screenY();

    /** Moves the window (native screen coordinates; see {@link #logicalToScreenFactor()}). */
    void setScreenPosition(int x, int y);

    /**
     * Whether {@link #screenX()}, {@link #screenY()} and {@link #setScreenPosition(int, int)}
     * actually work on this platform. When {@code false} the getters report a placeholder and
     * the setter is ignored: a window is placed by the desktop and never learns where it went.
     *
     * <p>Ask this before drawing anything <em>outside</em> a window that has to line up with
     * something inside it: a dropdown's list, a menu cascade, a tooltip. Those are not windows
     * in their own right; they are the owner's content, escaping the frame because most desktops
     * offer no other way to overflow it. Where the answer is {@code false} there is no such way,
     * and the content has to be drawn in the scene instead, clipped to the owner.
     *
     * <p>A window that is genuinely a window (a document, a dialog, a palette) needs no such
     * check. It has no position to agree with, so letting the desktop place it is the right
     * outcome rather than a fallback.
     *
     * <p>No default. It answered {@code true} until 2026-09-22, which is the unsafe
     * answer: a backend that said nothing claimed a power Wayland does not have, and a popup that
     * trusted it opened wherever the compositor put it. Absolute window position is absent from
     * that protocol by design, so a backend has to say.
     */
    boolean supportsAbsolutePositioning();

    /**
     * Makes the window transparent to MOUSE input: clicks, wheel and hover pass
     * through to whatever sits beneath it, for overlay/HUD windows whose empty
     * (usually transparent) regions must not steal the desktop's mouse. While
     * enabled this window receives no mouse events at all; poll
     * {@link #cursorX()}/{@link #cursorY()} to decide when to turn it back off
     * (e.g. the cursor entered one of the overlay's visible items). Keyboard
     * focus is unaffected. No-op where the platform lacks support.
     */
    default void setMousePassthrough(boolean passthrough) {
    }

    /**
     * @return the cursor's current x in logical points relative to this
     *         window's content area; polled, so it works while unfocused or
     *         {@linkplain #setMousePassthrough(boolean) mouse-passthrough}
     *         (which mutes events). {@code NaN} when unsupported. UI thread only.
     */
    default float cursorX() {
        return Float.NaN;
    }

    /** @return the cursor's current y; see {@link #cursorX()} */
    default float cursorY() {
        return Float.NaN;
    }

    /**
     * Sets how this window treats the pointer: visibility, confinement,
     * relative capture. See each {@link PointerMode} constant; {@code null}
     * means {@link PointerMode#NORMAL}. In {@link PointerMode#RELATIVE} the
     * window stops receiving {@link WindowInput#mouseMoved} and receives
     * {@link WindowInput#mouseDelta} instead. Interactions that capture from a
     * widget must restore {@code NORMAL} when they end (release/EXIT/detach);
     * the mode is window state and survives the widget. No-op where
     * unsupported. UI thread only.
     */
    default void setPointerMode(@Nullable PointerMode mode) {
    }

    /** @return the current {@link PointerMode} (default {@link PointerMode#NORMAL}) */
    default PointerMode pointerMode() {
        return PointerMode.NORMAL;
    }

    /**
     * Shows a custom {@link ImageCursor} instead of the current standard
     * {@link #setCursor(Cursor) shape}; {@code null} clears it and restores the
     * last shape. The scene drives this from
     * {@link limn.scene.Widget#setImageCursor} hover resolution; call it
     * directly only in windowless/manual setups. UI thread only.
     */
    default void setImageCursor(@Nullable ImageCursor cursor) {
    }

    /**
     * Warps the pointer to ({@code x}, {@code y}) in logical points relative to
     * this window's content area. No input event is synthesized; the next real
     * mouse event reflects the new position. Useful with
     * {@link PointerMode#RELATIVE} interactions and edge-wrap schemes; may be
     * restricted by the platform while the window is unfocused. UI thread only.
     */
    default void setCursorPosition(float x, float y) {
    }

    /**
     * Raises this window above the OS chrome so it can cover the whole display.
     * On macOS the menu bar and Dock draw at window levels above an ordinary
     * always-on-top window, so a full-screen transparent overlay would otherwise
     * be occluded by them. When enabled the window floats above that chrome
     * (a cube can fly over the menu bar; transparent regions still show it
     * through, and {@linkplain #setMousePassthrough(boolean) passthrough} keeps
     * it clickable); disabling restores the normal always-on-top level. No-op
     * where the platform needs nothing extra to cover its chrome. UI thread only.
     */
    default void setAboveSystemChrome(boolean above) {
    }

    /**
     * @return multiplier from logical points to native screen coordinates
     *         (1.0 on macOS, the monitor scale on Windows/X11), for
     *         positioning popups relative to widget bounds
     */
    float logicalToScreenFactor();

    /**
     * @return the {@link Display} this window currently sits on (the monitor
     *         containing the window's centre), or {@code null} when
     *         headless/embedded. Its {@link Display#workArea()} is what external
     *         popups clamp against to stay on screen. UI thread only.
     */
    default @Nullable Display display() {
        return null;
    }

    /**
     * Captures the next rendered frame (post-flush, pre-swap) and hands the
     * pixels to {@code sink}: a per-window screenshot without touching the
     * frame callback, and without a file. Requests a frame, so it works on an
     * idle window. {@code sink} runs on the UI thread inside that frame.
     *
     * <p>The image is display-referred: it is what the composite produced, not
     * scene-referred content awaiting a display transform. See
     * {@link GpuRenderer#captureFramebuffer(java.util.function.Consumer)}.
     */
    void captureNextFrame(java.util.function.Consumer<limn.graphics.Image> sink);

    /**
     * Captures the next rendered frame to a PNG file, creating the parent
     * directories if they are missing: the {@code --screenshot} path.
     */
    default void captureNextFrame(java.nio.file.Path pngFile) {
        java.util.Objects.requireNonNull(pngFile, "pngFile");
        captureNextFrame(image -> limn.graphics.Images.save(
                image, limn.graphics.ImageFormat.PNG, pngFile));
    }

    /**
     * @return the platform accessibility bridge for this window, or
     *         {@link AccessibilityBridge#NONE} when it has none.
     *
     *         <p>{@code default} on the merits rather than as a compatibility dodge: none is the
     *         <em>correct</em> behaviour for a window with no accessibility — a test double, an
     *         embedded surface, any future backend on its first day — and getting exactly zero cost
     *         by writing nothing is better than getting it by writing the same stub in each.
     */
    default AccessibilityBridge accessibility() {
        return AccessibilityBridge.NONE;
    }

    /**
     * Hands this window the platform accessibility bridge an application chose for it.
     *
     * <p>The other half of the seam above, and it was missing: an application could read what a
     * window's bridge was and had no way to say what it should be, so the per-platform bridges
     * ADR&nbsp;039 makes optional artifacts could be added to a classpath and never installed. It
     * is a setter and not a constructor argument because the Windows bridge needs
     * {@link #nativeHandle()} to exist before it can be made, and that handle is the window's.
     *
     * <p>{@code default} for the same reason the getter is: a window with no accessibility is a
     * correct window, and ignoring the offer is the correct thing for one to do.
     *
     * @param bridge what to hand a scene bound here, never {@code null}
     */
    default void setAccessibility(AccessibilityBridge bridge) {
    }

    /**
     * Says this window's contents are published in another window's accessible tree: a popup the
     * scene that opened it grafted into its own tree, as a native drop-down list is its field's
     * child. The window then opens no bridge of its own, and where the platform would tell an
     * assistive technology that a window appeared, it is kept out of that too: VoiceOver said "has
     * a new window" a few seconds after every opening of a combo's list. Called before the window is
     * shown.
     *
     * <p>{@code default} and doing nothing, which is right for a window with no accessibility.
     */
    default void publishAccessibilityElsewhere() {
    }

    /**
     * This window's handle in the platform's own terms: an {@code HWND} on Windows, an
     * {@code NSWindow} on macOS, an {@code xcb_window_t} or {@code wl_surface} on Linux.
     *
     * <p>Exists for one caller and should have no other: a platform accessibility bridge that has
     * to attach to the window itself. The Windows one replaces the window procedure to answer
     * {@code WM_GETOBJECT}; the macOS one will need the {@code NSWindow} to reach its content
     * view. Nothing in the toolkit reads this, and an application that finds itself wanting it for
     * anything else is describing a gap in this interface rather than a use for this method.
     *
     * @return the handle, or {@code 0} where there is none — a test double, an embedded surface, a
     *         platform this backend has not been taught
     */
    default long nativeHandle() {
        return 0;
    }

    /** Registers a listener for runtime monitor/content-scale changes. */
    void setContentScaleListener(ContentScaleListener listener);

    /**
     * Marks the window dirty so the loop renders a frame for it. UI thread
     * only: from a background thread, hop with
     * {@code Ui.post(window::requestFrame)}.
     */
    void requestFrame();

    /** Asks the window to close (processed by the event loop). Any-thread safe. */
    void requestClose();

    /** Same as {@link #requestClose()}; actual destruction happens in the loop/backend. */
    @Override
    void close();
}

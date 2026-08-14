package limn.backend;

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
     * interactive resizes; {@link #setSize} is not clamped. The default
     * implementation ignores it (headless/embedding). UI thread only.
     */
    default void setSizeLimits(int minWidth, int minHeight, int maxWidth, int maxHeight) {
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
     * Consulted when the <em>user</em> asks to close the window (the OS close
     * button, Alt-F4, Cmd-W): return {@code false} to veto and keep the window
     * open, e.g. to show an "unsaved changes" dialog first, closing later via
     * {@link #requestClose()}, which (like every programmatic close) bypasses
     * the handler. {@code null} clears. The default implementation ignores it
     * (headless/embedding). UI thread only.
     */
    default void setCloseRequestHandler(java.util.function.BooleanSupplier handler) {
    }

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
    default void setCursor(Cursor cursor) {
    }

    /** Brings this window to the front and gives it input focus. UI thread only. */
    void focus();

    /**
     * Enables or disables the platform input method (IME) for this window. The
     * scene turns it on while a text-editing widget holds focus and off
     * otherwise, so composition keys never leak into non-text UI. A no-op where
     * the platform has no IME control; the default implementation ignores it
     * (headless/embedding). UI thread only.
     */
    default void setImeEnabled(boolean enabled) {
    }

    /**
     * Positions the IME candidate/composition window at the caret, so it follows
     * the text being edited. {@code x}/{@code y}/{@code width}/{@code height} are
     * in logical points relative to the window's content area (the scene feeds
     * the focused widget's {@code caretRect}). A no-op where unsupported; the
     * default implementation ignores it (headless/embedding). UI thread only.
     */
    default void setPreeditCaretRect(float x, float y, float width, float height) {
    }

    /**
     * Cancels any in-progress IME composition held by the platform for this
     * window. The scene calls it when focus leaves a text-editing widget, so a
     * composition started there can never commit into whatever is focused next.
     * A no-op where unsupported or when nothing is being composed; the default
     * implementation ignores it (headless/embedding). UI thread only.
     */
    default void resetPreedit() {
    }

    /** @return whether the window is currently visible on screen */
    boolean isVisible();

    /** @return whether this window has already been closed/destroyed */
    boolean isClosed();

    /**
     * Enters <b>exclusive</b> (mode-setting) fullscreen on the monitor the
     * window currently occupies. Pass {@code width}/{@code height} &le; 0 to keep
     * the monitor's current resolution (no mode switch); pass {@code refreshRate}
     * &le; 0 for the monitor default. The previous windowed geometry is
     * remembered for {@link #exitFullscreen()}. UI thread only.
     *
     * @param width       target width in physical pixels, or &le; 0 for current
     * @param height      target height in physical pixels, or &le; 0 for current
     * @param refreshRate target refresh in Hz, or &le; 0 for the default
     */
    void enterFullscreen(int width, int height, int refreshRate);

    /** Enters exclusive fullscreen at the monitor's current resolution. UI thread only. */
    default void enterFullscreen() {
        enterFullscreen(0, 0, 0);
    }

    /**
     * Enters exclusive fullscreen at {@code mode} on the current display, the
     * normalized form of {@link #enterFullscreen(int, int, int)} that reuses a
     * {@link Resolution} (e.g. one from {@code display().availableResolutions()}).
     * UI thread only.
     */
    default void enterFullscreen(Resolution mode) {
        enterFullscreen(mode.width(), mode.height(), mode.refreshRate());
    }

    /** Restores the previous windowed geometry (no-op if not fullscreen). UI thread only. */
    void exitFullscreen();

    /** @return whether the window is currently in exclusive fullscreen */
    boolean isFullscreen();

    /**
     * @return whether this window is currently locked by an active modal (its
     *         input is ignored); the scene dims it while true
     */
    boolean isModalBlocked();

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
     * <p>{@code true} by default, because every platform Limn ran on before Wayland could do
     * this and a backend that says nothing is one of those. Wayland is the first that cannot:
     * absolute window position is absent from the protocol by design, not missing from an
     * implementation, so this can never become universally {@code true} again.
     */
    default boolean supportsAbsolutePositioning() {
        return true;
    }

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
    default void setPointerMode(PointerMode mode) {
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
    default void setImageCursor(ImageCursor cursor) {
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
    default Display display() {
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

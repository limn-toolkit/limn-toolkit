package limn.backend;

import java.util.Objects;
import limn.lang.Checks;

/**
 * Initial configuration for a {@link NativeWindow}: start from {@link #of}, then change what differs
 * from a plain window with a wither.
 *
 * <pre>{@code
 * backend.createWindow(WindowConfig.of("Hello, Limn", 480, 320));
 * backend.createWindow(WindowConfig.of("Tools", 300, 500).resizable(false).floating(true));
 * }</pre>
 *
 * <p>A class and not a record (ADR 046 §6): a record's canonical constructor is public, so every option
 * added to it broke every caller that listed the options by position, and six of the eleven were
 * booleans a reader could not tell apart. A new option is a new wither here, and no caller changes.
 *
 * <p>Immutable: every wither returns a copy.
 */
public final class WindowConfig {

    /**
     * {@link #screenX()}/{@link #screenY()} meaning <em>wherever the desktop would put it</em>,
     * which is the default and what every window wanted until one of them had to be reproducible.
     */
    public static final int ANY_POSITION = Integer.MIN_VALUE;

    private final String title;
    private final int width;
    private final int height;
    private final boolean visible;
    private final boolean resizable;
    private final boolean decorated;
    private final boolean floating;
    private final boolean transparent;
    private final boolean focusOnShow;
    private final int screenX;
    private final int screenY;

    private WindowConfig(String title, int width, int height, boolean visible, boolean resizable,
                         boolean decorated, boolean floating, boolean transparent,
                         boolean focusOnShow, int screenX, int screenY) {
        this.title = Objects.requireNonNull(title, "title");
        Checks.positiveSize(width, height, "window size");
        this.width = width;
        this.height = height;
        this.visible = visible;
        this.resizable = resizable;
        this.decorated = decorated;
        this.floating = floating;
        this.transparent = transparent;
        this.focusOnShow = focusOnShow;
        this.screenX = screenX;
        this.screenY = screenY;
    }

    /**
     * A plain window: visible, resizable, decorated, opaque, taking the focus when shown, wherever
     * the desktop puts it. The common case, and where every other one starts.
     *
     * @param title  title bar text
     * @param width  initial width in logical points
     * @param height initial height in logical points
     * @return the configuration
     */
    public static WindowConfig of(String title, int width, int height) {
        return new WindowConfig(title, width, height, true, true, true, false, false, true,
                ANY_POSITION, ANY_POSITION);
    }

    /**
     * Undecorated, floating, transparent, non-focus-stealing window, created hidden so it can be
     * positioned before {@link NativeWindow#show()}: the shape of a combo/menu popup.
     */
    public static WindowConfig popup(int width, int height) {
        return of("popup", width, height).visible(false).resizable(false).decorated(false)
                .floating(true).transparent(true).focusOnShow(false);
    }

    /**
     * A styled window created hidden (position, then {@link NativeWindow#show()}).
     *
     * @param style       decoration/translucency (see {@link WindowStyle})
     * @param floating    always-on-top
     * @param focusOnShow whether showing it steals input focus
     */
    public static WindowConfig styled(String title, int width, int height, WindowStyle style,
                                      boolean floating, boolean focusOnShow) {
        return of(title, width, height).visible(false).resizable(false)
                .decorated(style.decorated()).floating(floating).transparent(style.transparent())
                .focusOnShow(focusOnShow);
    }

    /** @return title bar text */
    public String title() {
        return title;
    }

    /** @return initial width in logical points */
    public int width() {
        return width;
    }

    /** @return initial height in logical points */
    public int height() {
        return height;
    }

    /**
     * @return whether the window starts shown; {@code false} for offscreen or screenshot rendering and
     *         for a window positioned before {@link NativeWindow#show()}
     */
    public boolean visible() {
        return visible;
    }

    /** @return whether the user may resize the window */
    public boolean resizable() {
        return resizable;
    }

    /** @return whether the window has the native title bar and border ({@code false} for popups) */
    public boolean decorated() {
        return decorated;
    }

    /** @return whether the window stays above others (popups, tooltips) */
    public boolean floating() {
        return floating;
    }

    /**
     * @return whether the framebuffer is transparent: pixels with alpha &lt; 1 composite over whatever
     *         is behind the window (rounded popup corners, translucent panels)
     */
    public boolean transparent() {
        return transparent;
    }

    /**
     * @return whether {@link NativeWindow#show()} takes the input focus ({@code false} keeps it in the
     *         parent: combo popups)
     */
    public boolean focusOnShow() {
        return focusOnShow;
    }

    /** @return initial x in native screen coordinates, or {@link #ANY_POSITION} */
    public int screenX() {
        return screenX;
    }

    /** @return initial y in native screen coordinates, or {@link #ANY_POSITION} */
    public int screenY() {
        return screenY;
    }

    /** @return a copy that starts shown or hidden */
    public WindowConfig visible(boolean newVisible) {
        return new WindowConfig(title, width, height, newVisible, resizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY);
    }

    /** @return a copy the user may, or may not, resize */
    public WindowConfig resizable(boolean newResizable) {
        return new WindowConfig(title, width, height, visible, newResizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY);
    }

    /** @return a copy with, or without, the native title bar and border */
    public WindowConfig decorated(boolean newDecorated) {
        return new WindowConfig(title, width, height, visible, resizable, newDecorated, floating,
                transparent, focusOnShow, screenX, screenY);
    }

    /** @return a copy that stays, or does not stay, above other windows */
    public WindowConfig floating(boolean newFloating) {
        return new WindowConfig(title, width, height, visible, resizable, decorated, newFloating,
                transparent, focusOnShow, screenX, screenY);
    }

    /** @return a copy with a transparent, or opaque, framebuffer */
    public WindowConfig transparent(boolean newTransparent) {
        return new WindowConfig(title, width, height, visible, resizable, decorated, floating,
                newTransparent, focusOnShow, screenX, screenY);
    }

    /** @return a copy that does, or does not, take the input focus when shown */
    public WindowConfig focusOnShow(boolean newFocusOnShow) {
        return new WindowConfig(title, width, height, visible, resizable, decorated, floating,
                transparent, newFocusOnShow, screenX, screenY);
    }

    /**
     * A copy created at a given point in native screen coordinates, rather than wherever the
     * desktop decides.
     *
     * <p>Placing a window <em>after</em> it exists is {@link NativeWindow#setScreenPosition} and
     * has always been possible; this is the same thing one step earlier, and the step matters for
     * exactly one reason: <b>a window's content scale is the scale of the monitor it is created
     * on</b>. A window that lands somewhere the caller did not choose is a window whose
     * framebuffer is a size the caller did not choose, and no arithmetic afterwards can undo that
     * &mdash; it has to be right at construction. Everything else about placement is cosmetic and
     * belongs to the desktop.
     *
     * <p>Not every desktop allows it: see {@link NativeWindow#supportsAbsolutePositioning()}. Where
     * it does not, the request is ignored and the window opens where the compositor puts it,
     * which is the same outcome as never asking.
     *
     * @param x native screen x, or {@link #ANY_POSITION} to hand the choice back
     * @param y native screen y
     * @return a copy carrying the position
     */
    public WindowConfig at(int x, int y) {
        return new WindowConfig(title, width, height, visible, resizable, decorated, floating,
                transparent, focusOnShow, x, y);
    }

    /**
     * A copy created on a chosen {@link Display}, at the origin of its work area.
     *
     * <p>The intent-revealing form of {@link #at(int, int)}, and the one worth reaching for: what
     * a caller who cares almost always means is <em>this monitor</em>, because that is what fixes
     * the content scale. A capture harness that must produce the same pixels on a laptop with
     * three monitors as on a runner with one asks for
     * {@link Backend#primaryDisplay() the primary display} and stops caring which machine it is.
     *
     * @param display the monitor to open on; {@code null} (a headless backend's answer) leaves
     *                the placement to the desktop, so a caller need not branch
     * @return a copy anchored to that display, or {@code this} when there is none
     */
    public WindowConfig on(Display display) {
        if (display == null) {
            return this;
        }
        ScreenRect area = display.workArea();
        return at(area.x(), area.y());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WindowConfig c && c.width == width && c.height == height
                && c.visible == visible && c.resizable == resizable && c.decorated == decorated
                && c.floating == floating && c.transparent == transparent
                && c.focusOnShow == focusOnShow && c.screenX == screenX && c.screenY == screenY
                && c.title.equals(title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, width, height, visible, resizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY);
    }

    @Override
    public String toString() {
        return "WindowConfig[" + title + ", " + width + "x" + height + (visible ? "" : ", hidden")
                + (resizable ? "" : ", fixed") + (decorated ? "" : ", undecorated")
                + (floating ? ", floating" : "") + (transparent ? ", transparent" : "")
                + (focusOnShow ? "" : ", no focus") + (screenX == ANY_POSITION ? "" : ", at "
                + screenX + "," + screenY) + "]";
    }
}

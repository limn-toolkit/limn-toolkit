package limn.backend;

import java.util.Objects;
import limn.lang.Checks;

/**
 * Initial configuration for a {@link NativeWindow}.
 *
 * @param title       title bar text
 * @param width       initial width in logical points
 * @param height      initial height in logical points
 * @param visible     {@code false} for offscreen/screenshot rendering or
 *                    windows positioned before {@link NativeWindow#show()}
 * @param resizable   whether the user may resize the window
 * @param decorated   native title bar/border ({@code false} for popups)
 * @param floating    always-on-top (popups, tooltips)
 * @param transparent transparent framebuffer: pixels with alpha &lt; 1
 *                    composite over whatever is behind the window (rounded
 *                    popup corners, translucent panels)
 * @param focusOnShow whether {@link NativeWindow#show()} steals input focus
 *                    ({@code false} keeps focus in the parent: combo popups)
 * @param screenX     initial x in native screen coordinates, or
 *                    {@link #ANY_POSITION} to let the desktop place it
 * @param screenY     initial y, or {@link #ANY_POSITION}; see {@link #on(Display)}
 */
public record WindowConfig(String title, int width, int height, boolean visible, boolean resizable,
                           boolean decorated, boolean floating, boolean transparent,
                           boolean focusOnShow, int screenX, int screenY) {

    /**
     * {@link #screenX()}/{@link #screenY()} meaning <em>wherever the desktop would put it</em>,
     * which is the default and what every window wanted until one of them had to be reproducible.
     */
    public static final int ANY_POSITION = Integer.MIN_VALUE;

    public WindowConfig {
        Objects.requireNonNull(title, "title");
        Checks.positiveSize(width, height, "window size");
    }

    /** The form without a position: the desktop places it. */
    public WindowConfig(String title, int width, int height, boolean visible, boolean resizable,
                        boolean decorated, boolean floating, boolean transparent,
                        boolean focusOnShow) {
        this(title, width, height, visible, resizable, decorated, floating, transparent,
                focusOnShow, ANY_POSITION, ANY_POSITION);
    }

    /** Regular window (decorated, focus on show, opaque). */
    public WindowConfig(String title, int width, int height, boolean visible, boolean resizable) {
        this(title, width, height, visible, resizable, true, false, false, true);
    }

    /** Visible, resizable, decorated window. The common case. */
    public static WindowConfig of(String title, int width, int height) {
        return new WindowConfig(title, width, height, true, true);
    }

    /**
     * Undecorated, floating, transparent, non-focus-stealing window, created
     * hidden so it can be positioned before {@link NativeWindow#show()}:
     * the shape of a combo/menu popup.
     */
    public static WindowConfig popup(int width, int height) {
        return new WindowConfig("popup", width, height, false, false, false, true, true, false);
    }

    /**
     * A styled window created hidden (position, then {@link NativeWindow#show()}).
     *
     * @param style       decoration/translucency (see {@link WindowStyle})
     * @param floating     always-on-top
     * @param focusOnShow  whether showing it steals input focus
     */
    public static WindowConfig styled(String title, int width, int height, WindowStyle style,
                                      boolean floating, boolean focusOnShow) {
        return new WindowConfig(title, width, height, false, false,
                style.decorated(), floating, style.transparent(), focusOnShow);
    }

    /** A copy that starts shown or hidden. */
    public WindowConfig withVisible(boolean newVisible) {
        return new WindowConfig(title, width, height, newVisible, resizable,
                decorated, floating, transparent, focusOnShow, screenX, screenY);
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
        return new WindowConfig(title, width, height, visible, resizable,
                decorated, floating, transparent, focusOnShow, x, y);
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
}

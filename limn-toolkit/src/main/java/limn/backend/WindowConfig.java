package limn.backend;

import java.util.Objects;

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
 */
public record WindowConfig(String title, int width, int height, boolean visible, boolean resizable,
                           boolean decorated, boolean floating, boolean transparent, boolean focusOnShow) {

    public WindowConfig {
        Objects.requireNonNull(title, "title");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window size must be positive, got " + width + "x" + height);
        }
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
                decorated, floating, transparent, focusOnShow);
    }
}

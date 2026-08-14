package limn.backend;

/**
 * Visual framing of a window.
 *
 * <ul>
 *   <li>{@link #DECORATED}: the OS draws the title bar and border; opaque
 *       (native windows are not translucent).</li>
 *   <li>{@link #UNDECORATED_OPAQUE}: borderless, opaque framebuffer (a solid
 *       card; e.g. a plain popup).</li>
 *   <li>{@link #UNDECORATED_TRANSLUCENT}: borderless with a transparent
 *       framebuffer, so pixels with alpha &lt; 1 composite over the desktop
 *       (rounded corners, translucent panels; e.g. a combo popup or a
 *       glassy dialog).</li>
 * </ul>
 */
public enum WindowStyle {
    DECORATED,
    UNDECORATED_OPAQUE,
    UNDECORATED_TRANSLUCENT;

    /** @return whether the OS draws the window frame */
    public boolean decorated() {
        return this == DECORATED;
    }

    /** @return whether the framebuffer is transparent (alpha composites over the desktop) */
    public boolean transparent() {
        return this == UNDECORATED_TRANSLUCENT;
    }
}

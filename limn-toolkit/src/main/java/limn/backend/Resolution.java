package limn.backend;

import limn.internal.lang.Checks;

/**
 * An immutable screen mode: a size and refresh rate. A normalized value
 * type shared across the toolkit: {@link Display} lists the modes it supports
 * and reports its current one, and {@link NativeWindow#enterFullscreen(Resolution)}
 * consumes one.
 *
 * <p>The size is in screen coordinates, the unit of {@link Display#bounds()},
 * and not always in pixels. It is pixels on Windows, X11 and Wayland. It is
 * points on macOS, where a HiDPI mode draws into a framebuffer of
 * {@code width × }{@link Display#contentScale()} pixels, which the system may
 * then scale to fit the panel: a built-in Retina display whose panel is
 * 3456 × 2234 pixels reports its default mode as 1728 × 1117, and its "more
 * space" mode as 2056 × 1285, drawn at 4112 × 2570 and scaled down. So a mode's
 * size says what a window covering the screen measures in screen coordinates,
 * not how many pixels the panel has, and there is no single pixel size to
 * convert it to.
 *
 * @param width       width in screen coordinates ({@code > 0})
 * @param height      height in screen coordinates ({@code > 0})
 * @param refreshRate refresh rate in Hz, or {@code 0} when unspecified/default
 */
public record Resolution(int width, int height, int refreshRate) {

    public Resolution {
        Checks.positiveSize(width, height, "resolution");
        Checks.notNegative(refreshRate, "refreshRate");
    }

    /** A resolution with an unspecified ({@code 0}) refresh rate. */
    public Resolution(int width, int height) {
        this(width, height, 0);
    }

    /** @return width / height (e.g. {@code 1.777…} for 16:9). */
    public double aspectRatio() {
        return (double) width / height;
    }

    @Override
    public String toString() {
        return width + "×" + height + (refreshRate > 0 ? " @" + refreshRate + "Hz" : "");
    }
}

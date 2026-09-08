package limn.backend;

import limn.lang.Checks;

/**
 * An immutable screen mode: a pixel size and refresh rate. A normalized value
 * type shared across the toolkit: {@link Display} lists the modes it supports
 * and reports its current one, and {@link NativeWindow#enterFullscreen(Resolution)}
 * consumes one.
 *
 * @param width       width in physical pixels ({@code > 0})
 * @param height      height in physical pixels ({@code > 0})
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

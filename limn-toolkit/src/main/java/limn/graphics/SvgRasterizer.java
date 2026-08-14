package limn.graphics;

/**
 * SPI that rasterizes SVG icon bytes to an {@link Image}. The backend installs a
 * NanoSVG-based rasterizer at startup (via {@link SvgIcon#installRasterizer});
 * headless tests can inject a fake. No AWT: the impl lives in the LWJGL backend.
 */
@FunctionalInterface
public interface SvgRasterizer {

    /**
     * Rasterizes {@code svgBytes} to fit a {@code pixelSize}×{@code pixelSize} box,
     * preserving aspect ratio. The result is straight-alpha RGBA8 (row 0 at top);
     * draw it with a tint
     * ({@link Canvas#drawImage(Image, float, float, float, float, Color)}) to
     * recolor the shape per theme, or without a tint to keep the SVG's own colors.
     *
     * <p><b>Called from any thread, including several at once.</b> It runs on the UI
     * thread inside a paint and on the worker pool from {@link SvgIcon#imageAsync},
     * which is the whole reason that asynchronous form can exist, so an
     * implementation must be pure CPU: no GL, no window, and no mutable state shared
     * between calls unless it is synchronized. {@code svgBytes} is read, never
     * written: a parser that needs a NUL-terminated or otherwise altered buffer must
     * copy it, because the caller reuses the same array for every size.
     */
    Image rasterize(byte[] svgBytes, int pixelSize);
}

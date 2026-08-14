package limn.backend;

/**
 * Immutable per-frame metrics handed to a {@link FrameCallback}.
 *
 * @param framebufferWidth  framebuffer width in physical pixels
 * @param framebufferHeight framebuffer height in physical pixels
 * @param contentScale      monitor content scale (fractional values are normal)
 * @param rePresent         {@code true} when the backend is re-drawing the same
 *                          already-settled frame purely to converge the double
 *                          buffers (see the loop's double-present); such frames
 *                          repaint identically but must be excluded from metrics
 * @param gpuFrameMs        GPU time of a recently completed frame in ms, measured
 *                          by the backend's timer queries; {@link Float#NaN} when
 *                          no new sample is available (results arrive a few frames
 *                          late, and not every backend can measure)
 */
public record FrameInfo(int framebufferWidth, int framebufferHeight, float contentScale,
                        boolean rePresent, float gpuFrameMs) {

    public FrameInfo {
        if (framebufferWidth < 0 || framebufferHeight < 0) {
            throw new IllegalArgumentException("framebuffer size must be >= 0");
        }
        if (contentScale <= 0) {
            throw new IllegalArgumentException("contentScale must be > 0, got " + contentScale);
        }
    }

    /** Without a GPU-time sample. */
    public FrameInfo(int framebufferWidth, int framebufferHeight, float contentScale, boolean rePresent) {
        this(framebufferWidth, framebufferHeight, contentScale, rePresent, Float.NaN);
    }

    /** A normal (metric-recording) frame without a GPU-time sample. */
    public FrameInfo(int framebufferWidth, int framebufferHeight, float contentScale) {
        this(framebufferWidth, framebufferHeight, contentScale, false, Float.NaN);
    }

    /** @return width in logical points */
    public float logicalWidth() {
        return framebufferWidth / contentScale;
    }

    /** @return height in logical points */
    public float logicalHeight() {
        return framebufferHeight / contentScale;
    }
}

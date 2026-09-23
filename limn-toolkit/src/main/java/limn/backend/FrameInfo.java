package limn.backend;

import limn.internal.lang.Checks;

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
 * @param bufferAge         how many presents old the back buffer's contents are: 1 when it holds the
 *                          previous frame, 2 for ordinary double buffering once two frames of this
 *                          size have been presented, 0 when the backend does not know (the first
 *                          frames, a resize), which repaints the whole window. Partial rendering
 *                          repaints what changed over that many frames (ADR 046 §5) instead of
 *                          assuming two buffers everywhere, as it did until 2026-09-22.
 */
public record FrameInfo(int framebufferWidth, int framebufferHeight, float contentScale,
                        boolean rePresent, float gpuFrameMs, int bufferAge) {

    public FrameInfo {
        Checks.notNegativeSize(framebufferWidth, framebufferHeight, "framebuffer size");
        Checks.positive(contentScale, "contentScale");
        if (bufferAge < 0) {
            throw new IllegalArgumentException("bufferAge must not be negative: " + bufferAge);
        }
    }

    /** Without the buffer's age: 0, the whole window, which is always safe. */
    public FrameInfo(int framebufferWidth, int framebufferHeight, float contentScale, boolean rePresent,
                     float gpuFrameMs) {
        this(framebufferWidth, framebufferHeight, contentScale, rePresent, gpuFrameMs, 0);
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

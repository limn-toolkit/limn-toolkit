package limn.backend.lwjgl;

import limn.backend.GpuRenderer;
import limn.graphics.Canvas;
import limn.graphics.Image;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL33C.GL_BACK;
import static org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_STENCIL_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.glClear;
import static org.lwjgl.opengl.GL33C.glClearColor;
import static org.lwjgl.opengl.GL33C.glPixelStorei;
import static org.lwjgl.opengl.GL33C.glReadBuffer;
import static org.lwjgl.opengl.GL33C.glReadPixels;

/**
 * OpenGL {@link GpuRenderer} for one window: owns the window's {@link GlCanvas}
 * (created lazily on the window's GL context) and runs the frame lifecycle:
 * begin, application callback, batch flush, optional framebuffer capture,
 * swap. Captures are deferred to after the flush so the image contains
 * everything the frame drew.
 */
final class GlRenderer implements GpuRenderer {

    // GPU frame timing: a small ring of GL_TIME_ELAPSED queries bracketing each
    // content frame's GL work. Results are polled non-blocking a few frames
    // later (the event-driven loop may idle for seconds; a blocking read is
    // never acceptable), so the sample lags the frame it measured; fine for a
    // 1 Hz HUD. Only one TIME_ELAPSED query may be active per context, which
    // the single-threaded one-window-at-a-time frame lifecycle guarantees.
    private static final int QUERY_RING = 4;

    private final FontStore fontStore;
    private GlCanvas canvas;
    private Consumer<Image> pendingCapture;
    private boolean inFrame;
    private int fbWidth;
    private int fbHeight;
    private int[] timerQueries; // null until first frame; empty if unsupported
    private final boolean[] timerInFlight = new boolean[QUERY_RING];
    private int timerNext;            // ring slot to issue next
    private boolean timerActive;      // a query is open for the current frame
    private float pendingGpuMs = Float.NaN; // latest unconsumed result

    GlRenderer(FontStore fontStore) {
        this.fontStore = fontStore;
    }

    void beginFrame(int framebufferWidth, int framebufferHeight, float contentScale, boolean rePresent) {
        this.fbWidth = framebufferWidth;
        this.fbHeight = framebufferHeight;
        if (canvas == null) {
            canvas = new GlCanvas(fontStore); // requires this window's context current
        }
        if (timerQueries == null) {
            // Belt and braces: 3.3 core is a window-creation requirement, so
            // timer queries should always exist, but degrade to "no metric"
            // rather than crash if a driver lies.
            org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
            boolean supported = caps.OpenGL33 || caps.GL_ARB_timer_query;
            timerQueries = supported ? new int[QUERY_RING] : new int[0];
            if (supported) {
                org.lwjgl.opengl.GL33C.glGenQueries(timerQueries);
            }
        }
        if (timerQueries.length > 0) {
            pollTimerResults();
            // Re-present frames redraw identical pixels only to converge the
            // double buffers; the scene excludes them from CPU metrics, so the
            // GPU metric mirrors that (poll above still ran).
            if (!rePresent && !timerInFlight[timerNext]) {
                org.lwjgl.opengl.GL33C.glBeginQuery(org.lwjgl.opengl.GL33C.GL_TIME_ELAPSED,
                        timerQueries[timerNext]);
                timerActive = true;
            }
        }
        canvas.beginFrame(framebufferWidth, framebufferHeight, contentScale);
        inFrame = true;
    }

    void endFrame() {
        canvas.endFrame();
        // Close the frame's timer after the last draw is issued but before the
        // optional capture: glReadPixels stalls the pipeline and would inflate
        // screenshot frames' GPU time.
        if (timerActive) {
            org.lwjgl.opengl.GL33C.glEndQuery(org.lwjgl.opengl.GL33C.GL_TIME_ELAPSED);
            timerInFlight[timerNext] = true;
            timerNext = (timerNext + 1) % QUERY_RING;
            timerActive = false;
        }
        inFrame = false;
        if (pendingCapture != null) {
            Consumer<Image> sink = pendingCapture;
            pendingCapture = null;
            sink.accept(readBackBuffer());
        }
    }

    private void pollTimerResults() {
        for (int i = 0; i < QUERY_RING; i++) {
            if (!timerInFlight[i]) {
                continue;
            }
            int available = org.lwjgl.opengl.GL33C.glGetQueryObjecti(timerQueries[i],
                    org.lwjgl.opengl.GL33C.GL_QUERY_RESULT_AVAILABLE);
            if (available == 0) {
                continue;
            }
            long nanos = org.lwjgl.opengl.GL33C.glGetQueryObjectui64(timerQueries[i],
                    org.lwjgl.opengl.GL33C.GL_QUERY_RESULT);
            timerInFlight[i] = false;
            if (nanos > 0) { // some macOS drivers report 0, so treat as "no sample"
                pendingGpuMs = nanos / 1_000_000f;
            }
        }
    }

    /**
     * The most recent GPU frame time in ms, delivered once ({@link Float#NaN}
     * when none arrived since the last take). Lags the frame it measured by the
     * query-ring latency.
     */
    float takeGpuFrameMs() {
        float value = pendingGpuMs;
        pendingGpuMs = Float.NaN;
        return value;
    }

    /** @return texture stats of this window's canvas (empty until the first frame). */
    limn.backend.RenderStats stats() {
        return canvas != null ? canvas.stats() : limn.backend.RenderStats.EMPTY;
    }

    /** @return 3D-subsystem stats of this window's canvas (empty until the first frame). */
    limn.render3d.Render3DStats render3DStats() {
        return canvas != null ? canvas.render3DStats() : limn.render3d.Render3DStats.EMPTY;
    }

    void dispose() {
        // Context is current here (LwjglWindow.destroy); queries die with it.
        if (timerQueries != null && timerQueries.length > 0) {
            org.lwjgl.opengl.GL33C.glDeleteQueries(timerQueries);
        }
        timerQueries = null;
        if (canvas != null) {
            canvas.dispose();
            canvas = null;
        }
    }

    @Override
    public Canvas canvas() {
        if (!inFrame) {
            throw new IllegalStateException("canvas() is only valid inside a frame callback");
        }
        return canvas;
    }

    @Override
    public void clear(float red, float green, float blue, float alpha) {
        if (inFrame) {
            // Canvas.clear flushes pending geometry first (immediate-mode order).
            canvas.clear(new limn.graphics.Color(red, green, blue, alpha));
            return;
        }
        // A damage scissor from a previous frame's last draw must not clip a
        // whole-framebuffer clear.
        org.lwjgl.opengl.GL33C.glDisable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
        glClearColor(red * alpha, green * alpha, blue * alpha, alpha);
        glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    }

    @Override
    public void captureFramebuffer(Consumer<Image> sink) {
        pendingCapture = java.util.Objects.requireNonNull(sink, "sink");
    }

    /**
     * The back buffer as an {@link Image}: display-referred already (the composite ran into it),
     * so no colour transform is applied here, only the two conversions {@code Image}'s contract
     * requires, and both are easy to leave out and hard to notice.
     *
     * <p>Rows are flipped: GL's origin is bottom-left and {@code Image} is top-down. Alpha is
     * divided back out: the canvas blends premultiplied, so a transparent window's framebuffer
     * holds premultiplied colour, and writing that out as straight alpha darkens every partly
     * transparent pixel. An opaque window is the common case and costs nothing: alpha 255 skips
     * the division exactly.
     */
    private Image readBackBuffer() {
        ByteBuffer pixels = MemoryUtil.memAlloc(fbWidth * fbHeight * 4);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadBuffer(GL_BACK); // runs before the swap: back buffer holds this frame
            glReadPixels(0, 0, fbWidth, fbHeight, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            byte[] rgba = new byte[fbWidth * fbHeight * 4];
            int stride = fbWidth * 4;
            for (int row = 0; row < fbHeight; row++) {
                pixels.position((fbHeight - 1 - row) * stride);
                pixels.get(rgba, row * stride, stride);
            }
            unpremultiply(rgba);
            return new Image(fbWidth, fbHeight, rgba);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static void unpremultiply(byte[] rgba) {
        for (int i = 0; i < rgba.length; i += 4) {
            int alpha = rgba[i + 3] & 0xFF;
            if (alpha == 255) {
                continue;
            }
            if (alpha == 0) {
                rgba[i] = 0;
                rgba[i + 1] = 0;
                rgba[i + 2] = 0;
                continue;
            }
            for (int c = 0; c < 3; c++) {
                int value = ((rgba[i + c] & 0xFF) * 255 + alpha / 2) / alpha;
                rgba[i + c] = (byte) Math.min(255, value);
            }
        }
    }
}

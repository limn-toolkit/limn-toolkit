package limn.backend.lwjgl;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.YuvConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A canvas rendering a frame on a real GL context, plus the comparison rule the
 * device conversion is held to.
 *
 * <p><b>The rule.</b> {@link YuvConverter} is the oracle and the device must
 * reproduce it exactly, except where the exact arithmetic lands within
 * {@link #TIE_BAND} of a rounding tie, where either neighbouring code is
 * legitimate: the converter rounds halves up in double precision, while a
 * device computes in single precision and the framebuffer rounds to nearest
 * with ties unspecified. A high-precision float carries about four thousandths
 * of a code of relative error at the top of the range, so anything farther than
 * that from a tie has one correct answer and gets no tolerance at all.
 *
 * <p>The exact values are recomputed here from {@link VideoColor}'s accessors
 * (the same six coefficients the converter and the shader read, never retyped)
 * purely to measure that distance. Every assertion first checks that rounding
 * them reproduces the converter's own output, so a drift in this arithmetic
 * fails the test instead of quietly widening it.
 */
abstract class GlVideoTestBase {

    /** Distance from a rounding tie, in codes, inside which either side is accepted. */
    static final double TIE_BAND = 0.02;

    /** Logical size of the frame the canvas renders; unrelated to any picture size. */
    private static final int FRAME_WIDTH = 64;
    private static final int FRAME_HEIGHT = 64;

    protected GlCanvas canvas;
    private FontStore fonts;
    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void openFrame() {
        HeadlessGl.assumeAvailable();
        // Per test, and removed again below: the device paths assert the
        // UI-thread confinement they document, and other classes in this module
        // install a runtime of their own that a leftover one would collide with.
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
        canvas.beginFrame(FRAME_WIDTH, FRAME_HEIGHT, 1f);
    }

    @AfterEach
    void closeFrame() {
        if (canvas != null) {
            canvas.endFrame();
            canvas.dispose();
            canvas = null;
        }
        if (fonts != null) {
            // A FontStore registers a process-wide Fonts listener and holds
            // native memory; the canvas does not own it and will not close it.
            fonts.close();
            fonts = null;
        }
        if (runtime != null) {
            Ui.uninstall(runtime);
            workers.shutdownNow();
            runtime = null;
        }
    }

    /** Uploads {@code frame} into a fresh surface of this canvas's context. */
    protected GlVideoSurface upload(VideoFrame frame) {
        GlVideoSurface surface = canvas.glVideo().createSurface();
        surface.upload(frame);
        assertNoGlError("uploading " + frame);
        return surface;
    }

    /**
     * The surface's converted picture, rows top-down, as straight RGBA codes <b>in the picture's
     * own code space</b>: {@code [0..255]} for an 8-bit picture and {@code [0..1023]} for a
     * 10-bit one, because the converted target is RGB10_A2 there.
     *
     * <p>Reading a 10-bit target back as bytes instead would let the driver quantize it by a rule
     * the specification leaves open, and every exact comparison below would then be measuring that
     * rule rather than the conversion.
     */
    protected static int[] picture(GlVideoSurface surface) {
        PixelFormat format = surface.pictureFormat();
        int width = surface.widthPx();
        int height = surface.heightPx();
        int[] codes = format.bitDepth() > 8
                ? readTenBitBottomUp(surface.colorTexture(), width, height)
                : widen(readBottomUp(surface.colorTexture(), width, height));
        int[] out = new int[codes.length];
        int rowValues = width * 4;
        for (int row = 0; row < height; row++) {
            System.arraycopy(codes, (height - 1 - row) * rowValues, out, row * rowValues, rowValues);
        }
        return out;
    }

    private static int[] widen(byte[] bytes) {
        int[] out = new int[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            out[index] = bytes[index] & 0xFF;
        }
        return out;
    }

    /**
     * Reads an RGB10_A2 texture as four codes a pixel. {@code GL_UNSIGNED_INT_2_10_10_10_REV} packs
     * red in the ten LEAST significant bits and the two alpha bits at the top, the reverse of the
     * name's reading order, which is what the {@code _REV} is.
     */
    private static int[] readTenBitBottomUp(int texture, int width, int height) {
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int fbo = GL33C.glGenFramebuffers();
        java.nio.IntBuffer packed = MemoryUtil.memAllocInt(width * height);
        try {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE,
                    GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), "readback FBO");
            GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            GL33C.glReadPixels(0, 0, width, height, GL33C.GL_RGBA,
                    GL33C.GL_UNSIGNED_INT_2_10_10_10_REV, packed);
            int[] out = new int[width * height * 4];
            for (int pixel = 0; pixel < width * height; pixel++) {
                int value = packed.get(pixel);
                out[pixel * 4] = value & 0x3FF;
                out[pixel * 4 + 1] = (value >>> 10) & 0x3FF;
                out[pixel * 4 + 2] = (value >>> 20) & 0x3FF;
                // Two bits of alpha, reported in the picture's own full-scale terms so an opaque
                // pixel reads as opaque whatever the depth.
                out[pixel * 4 + 3] = ((value >>> 30) & 0x3) == 0x3 ? 1023 : 0;
            }
            return out;
        } finally {
            MemoryUtil.memFree(packed);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glDeleteFramebuffers(fbo);
        }
    }

    /**
     * Reads an RGBA8 texture exactly as GL hands it over: row 0 is the
     * <em>bottom</em> row of the target. Only the orientation tests use this
     * directly; everything else goes through {@link #picture}.
     */
    protected static byte[] readBottomUp(int texture, int width, int height) {
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int fbo = GL33C.glGenFramebuffers();
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE,
                    GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), "readback FBO");
            GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            GL33C.glReadPixels(0, 0, width, height, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[width * height * 4];
            pixels.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glDeleteFramebuffers(fbo);
        }
    }

    /**
     * Runs a whole canvas frame into a fresh RGBA8 target of this size and
     * returns what it drew, rows top-down: the composite as a window would see
     * it, without needing a visible one.
     */
    protected byte[] renderToPicture(int width, int height, java.util.function.Consumer<GlCanvas> body) {
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE,
                    GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), "render target");
            GL33C.glViewport(0, 0, width, height);

            canvas.beginFrame(width, height, 1f);
            body.accept(canvas);
            canvas.endFrame();
            return topDown(readBottomUp(texture, width, height), width, height);
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
    }

    /** Row-reverses a readback, turning GL's bottom-up rows into picture order. */
    protected static byte[] topDown(byte[] bottomUp, int width, int height) {
        byte[] out = new byte[bottomUp.length];
        int rowBytes = width * 4;
        for (int row = 0; row < height; row++) {
            System.arraycopy(bottomUp, (height - 1 - row) * rowBytes, out, row * rowBytes, rowBytes);
        }
        return out;
    }

    protected static void assertNoGlError(String where) {
        int error = GL33C.glGetError();
        assertEquals(GL33C.GL_NO_ERROR, error, () -> where + ": GL error 0x" + Integer.toHexString(error));
    }

    /**
     * Holds {@code device} to {@link YuvConverter}'s output for the same
     * picture, channel by channel, under the tie rule. {@code frame} must still
     * be held; the sample arrays are the ones it was built from, each in its own
     * plane's grid.
     */
    protected static void assertMatchesReference(int[] device, VideoFrame frame,
                                                 int[] luma, int[] cb, int[] cr, String where) {
        int width = frame.width();
        int height = frame.height();
        PixelFormat format = frame.format();
        VideoColor color = frame.color();
        int bitDepth = format.bitDepth();
        int maxCode = format.maxCode();
        // Before the first comparison rather than inside it: a test either holds this picture to
        // the oracle or does not run, and never reports a partial pass.
        assumeExactColorIsPossible(bitDepth);
        assertEquals(width * height * 4, device.length, where + " picture size");

        int[] reference = new int[4];
        int chromaWidth = format.planeWidth(1, width);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int pixel = row * width + column;
                int chroma = (row >> format.chromaShiftY()) * chromaWidth
                        + (column >> format.chromaShiftX());
                // The oracle, in the picture's own code space, called per pixel rather than over
                // the whole frame: a 10-bit target holds more than toRgba8's eight bits of output,
                // so comparing against that would compare the device to a quantization step.
                YuvConverter.convertPixel(color, bitDepth, luma[pixel], cb[chroma], cr[chroma],
                        reference);
                double[] exact = exactChannels(color, bitDepth, luma[pixel], cb[chroma], cr[chroma]);
                for (int channel = 0; channel < 3; channel++) {
                    int expected = reference[channel];
                    int actual = device[pixel * 4 + channel];
                    String at = where + " pixel " + column + "," + row + " channel " + channel;
                    assertEquals(expected, clampRound(exact[channel], maxCode),
                            at + ": this test's arithmetic no longer matches the converter");
                    if (nearTie(exact[channel], maxCode)) {
                        assertTrue(Math.abs(actual - expected) <= 1,
                                at + ": expected " + expected + " ±1 (exact " + exact[channel]
                                        + " sits on a rounding tie), got " + actual);
                    } else {
                        assertEquals(expected, actual,
                                at + ": exact " + exact[channel] + " is not near a tie, so the"
                                        + " device must agree with the converter exactly");
                    }
                }
                assertEquals(maxCode, device[pixel * 4 + 3],
                        where + " pixel " + column + "," + row + " alpha");
            }
        }
    }

    /**
     * The decode of one YCbCr triple before rounding, from the published
     * formula: green's two chroma terms are summed before the luma is added,
     * because regrouping them changes which side of a tie some codes land on.
     */
    protected static double[] exactChannels(VideoColor color, int bitDepth, int y, int cb, int cr) {
        double luma = color.yScale(bitDepth) * (y - color.yOffset(bitDepth));
        double blueDelta = cb - color.chromaNeutral(bitDepth);
        double redDelta = cr - color.chromaNeutral(bitDepth);
        return new double[] {
            luma + color.crToR(bitDepth) * redDelta,
            luma + (color.cbToG(bitDepth) * blueDelta + color.crToG(bitDepth) * redDelta),
            luma + color.cbToB(bitDepth) * blueDelta,
        };
    }

    /**
     * Skips the calling test when it would hold a deeper-than-eight-bit picture to the oracle on
     * a device that cannot deliver ten bits: a software rasteriser.
     *
     * <p><b>Skipped and not loosened,</b> which is the whole point. A ±1 tolerance here would
     * apply on every device, so a real ten-bit regression would pass on a developer's GPU too,
     * and nothing in a green build would say that a claim had stopped being checked. A skip says
     * it, in the run's own output, every time.
     *
     * <p>The numbers behind it, measured over the 7626 channel comparisons these tests make, on
     * an Apple GPU and on Mesa's llvmpipe:
     *
     * <table><caption>Device against the converter</caption>
     * <tr><th></th><th>eight bits</th><th>ten bits</th></tr>
     * <tr><td>GPU</td><td>exact, 384/384</td><td>exact, 7242/7242</td></tr>
     * <tr><td>llvmpipe</td><td>exact, 384/384</td><td>off by one on 1119 of 7242 (15.5%)</td></tr>
     * </table>
     *
     * <p>Those are not ties resolved the other way: their exact values sit up to 0.4709 of a code
     * from a tie, where the distance cannot exceed 0.5, so the device's pre-rounding value is off
     * by nearly half a code. That is a relative error of about 4.9e-4 — half-precision (2⁻¹¹),
     * not the four thousandths of a code a float carries. Eight bits need only 1/255 and come out
     * exact on the same device, which is the tell: llvmpipe has the precision here for eight bits
     * and not for ten. The shader is not what differs; the device is.
     *
     * <p><b>What this costs.</b> CI is always a software rasteriser, so ten-bit conversion is
     * verified on a developer's machine and not on a runner. Everything else about the ten-bit
     * path still runs there — the sixteen-bit upload, the stride handling, the RGB10_A2 target,
     * the refusal of a non-display-referred picture — because none of those compares a converted
     * code to the oracle.
     */
    protected static void assumeExactColorIsPossible(int bitDepth) {
        Assumptions.assumeFalse(bitDepth > 8 && HeadlessGl.isSoftware(),
                () -> "ten-bit conversion cannot be held to the converter on a software"
                        + " rasteriser (" + HeadlessGl.describe() + "): its precision here is"
                        + " about half a code at ten bits, exact at eight. Run this on a GPU.");
    }

    private static boolean nearTie(double value, int maxCode) {
        if (value <= 0 || value >= maxCode) {
            return false; // both sides clamp to the same code
        }
        return Math.abs(value - Math.floor(value) - 0.5) <= TIE_BAND;
    }

    private static int clampRound(double value, int maxCode) {
        long rounded = Math.round(value);
        return rounded < 0 ? 0 : rounded > maxCode ? maxCode : (int) rounded;
    }
}

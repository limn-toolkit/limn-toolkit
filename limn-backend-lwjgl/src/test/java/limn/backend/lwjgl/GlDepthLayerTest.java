package limn.backend.lwjgl;

import limn.graphics.BackdropEffect;
import limn.graphics.Color;
import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Vec3;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layer composited through a turn in depth, seen in perspective (ADR 072 §2.4, §2.9, stage 3),
 * against a real device: the picture lies where the projection puts each of its points, its
 * texture interpolated perspective-correctly; a plane turned partly past the eye draws the part in
 * front and nothing else; a quarter turn draws nothing and makes no picture; the picture is drawn at
 * the magnification of its nearest corner; and a glass inside reads only what the subtree drew.
 *
 * <p>Every expected pixel is worked out here from the angle and the distance, by sending each
 * corner of the pixel back along its ray to the card's plane; a pixel whose corners fall on both
 * sides of an edge is left out, since its share is the antialiasing's.
 */
class GlDepthLayerTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 400;
    private static final int H_PT = 480;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.rgb(0xF4F5F8);
    private static final Color INK = Color.rgb(0x2050C0);
    private static final Color RED = Color.rgb(0xC03030);
    /** The pivot every case turns about, in points. */
    private static final float CX = 200;
    private static final float CY = 240;

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
    }

    @AfterEach
    void closeContext() {
        if (canvas != null) {
            canvas.dispose();
            canvas = null;
        }
        if (fonts != null) {
            fonts.close();
            fonts = null;
        }
        if (ui != null) {
            ui.close();
            ui = null;
        }
    }

    /** A turn of {@code degrees} about the vertical axis through the pivot, seen from {@code distance}. */
    private static Mat4 turned(float degrees, float distance) {
        Mat4 turn = Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_Y, (float) Math.toRadians(degrees)));
        return Mat4.translation(new Vec3(CX, CY, 0)).multiply(Mat4.perspectiveAt(distance)).multiply(turn)
                .multiply(Mat4.translation(new Vec3(-CX, -CY, 0)));
    }

    /**
     * The point of the card's plane, {@code (u, v)} from the pivot, that the ray through the window
     * point {@code (x, y)} in points meets in front of the eye, or {@code null}: by hand, for a turn
     * of {@code degrees} about the vertical axis seen from {@code distance}, where a point is drawn at
     * {@code (u cos / w, v / w)} from the pivot with {@code w = 1 + u sin / distance}.
     */
    private static double[] sentBack(double x, double y, double degrees, double distance) {
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        double dx = x - CX;
        double u = dx / (cos - dx * sin / distance);
        double w = 1 + u * sin / distance;
        if (!(w > 0)) {
            return null;
        }
        return new double[] {u, (y - CY) * w};
    }

    /**
     * What a pixel shows, by hand: the colour {@code where} gives each corner of the pixel grown by
     * one on every side, sent back to the plane, when all four agree, or {@code null} within a
     * pixel of an edge, whose share is the antialiasing's: the picture's own ramp and the filter
     * that lays it.
     */
    private static Color expected(int px, int py, double degrees, double distance,
                                  java.util.function.Function<double[], Color> where) {
        Color seen = null;
        for (int i = 0; i < 4; i++) {
            double x = (px + (i == 1 || i == 2 ? 2 : -1)) / SCALE;
            double y = (py + (i >= 2 ? 2 : -1)) / SCALE;
            double[] uv = sentBack(x, y, degrees, distance);
            Color c = uv == null ? PAGE : where.apply(uv);
            if (seen != null && !seen.equals(c)) {
                return null;
            }
            seen = c;
        }
        return seen;
    }

    /** Fails naming the first pixel that is not what {@link #expected} says, within two steps. */
    private static void assertEveryPixel(byte[] image, double degrees, double distance,
                                         java.util.function.Function<double[], Color> where, String what) {
        int checked = 0;
        for (int py = 0; py < HEIGHT; py++) {
            for (int px = 0; px < WIDTH; px++) {
                Color c = expected(px, py, degrees, distance, where);
                if (c == null) {
                    continue;
                }
                checked++;
                int[] got = pixel(image, px, py);
                for (int ch = 0; ch < 3; ch++) {
                    if (Math.abs(got[ch] - channel(c, ch)) > 2) {
                        throw new AssertionError(what + ": pixel " + px + "," + py + " is "
                                + java.util.Arrays.toString(got) + ", by hand " + c);
                    }
                }
            }
        }
        assertTrue(checked > WIDTH * HEIGHT / 2, what + ": most pixels are checked, " + checked);
    }

    // ------------------------------------------------------------------ where the picture lies

    @Test
    void aCardTurnedInPerspectiveLiesWhereTheProjectionPutsEachPointInterpolatedPerspectiveCorrectly() {
        // A card whose last quarter is red: an affine interpolation over the quad's two triangles
        // would put that line pixels away from where the projection puts it.
        float degrees = 55;
        float distance = 420;
        byte[] image = render(c -> {
            c.saveLayer(CX - 120, CY - 80, 240, 160, 1f, turned(degrees, distance));
            c.fillRect(CX - 120, CY - 80, 180, 160, INK);
            c.fillRect(CX + 60, CY - 80, 60, 160, RED);
            c.restore();
        });
        assertEveryPixel(image, degrees, distance, uv -> {
            if (uv[0] < -120 || uv[0] > 120 || uv[1] < -80 || uv[1] > 80) {
                return PAGE;
            }
            return uv[0] < 60 ? INK : RED;
        }, "the turned card");
        assertEquals(0, canvas.openLayers());
    }

    @Test
    void thePictureIsDrawnAtTheMagnificationOfItsNearestCorner() {
        // ADR 072 section 2.4's case at half the size, to fit the window: a 200 point card turned 80
        // degrees, the eye 400 points away; its near edge is seen at 1.33 times, and its picture is
        // drawn at that size. On a GPU: a renderer without one draws it at its natural size (below).
        Assumptions.assumeFalse(HeadlessGl.isSoftware(), "a GPU's picture; a software renderer's is the next case");
        float degrees = -80;
        float distance = 400;
        render(c -> {
            c.saveLayer(CX - 100, CY - 75, 200, 150, 1f, turned(degrees, distance));
            c.fillRect(CX - 100, CY - 75, 200, 150, INK);
            c.restore();
        });
        double r = Math.toRadians(degrees);
        double most = 0;
        for (double[] corner : new double[][] {{-100, -75}, {100, -75}, {100, 75}, {-100, 75}}) {
            double u = corner[0];
            double v = corner[1];
            double w = 1 + u * Math.sin(r) / distance;
            // The derivative of (u cos / w, v / w), and its largest singular value.
            double a = Math.cos(r) / (w * w);
            double b = 0;
            double cc = -v * Math.sin(r) / (distance * w * w);
            double d = 1 / w;
            double sum = a * a + b * b + cc * cc + d * d;
            double det = a * d - b * cc;
            most = Math.max(most, Math.sqrt((sum + Math.sqrt(sum * sum - 4 * det * det)) / 2));
        }
        // 1.33 along the near edge, a little more at its corners, where the edge also leans.
        assertTrue(most > 1.33 && most < 1.38, "the near corner's magnification, by hand: " + most);
        assertEquals(SCALE * most, canvas.lastSpaceScale(), SCALE * most * 1e-3,
                "drawn at the near corner's size times the device's");
    }

    @Test
    void onARendererWithoutAGpuAPictureInPerspectiveIsDrawnAtItsNaturalSize() {
        // ADR 072 section 12, the owner's decision of 2026-10-18: the same card, whose near corner
        // would want 1.37 times, is drawn at the device's scale on a renderer that pays every pixel
        // in CPU time, and still lies where the projection puts it; an affine picture keeps its
        // magnification there, a scale above the layer included.
        HeadlessGl.onSoftwareRenderer(() -> {
            GlCanvas software = new GlCanvas(fonts);
            try {
                assertTrue(software.depthAtNaturalSize, "read from the context: " + HeadlessGl.describe());
                render(software, c -> {
                    c.saveLayer(CX - 100, CY - 75, 200, 150, 1f, turned(-80, 400));
                    c.fillRect(CX - 100, CY - 75, 200, 150, INK);
                    c.restore();
                });
                assertEquals(SCALE, software.lastSpaceScale(), 1e-6, "the device's scale, not the near corner's");

                float degrees = 55;
                float distance = 420;
                byte[] image = render(software, c -> {
                    c.saveLayer(CX - 120, CY - 80, 240, 160, 1f, turned(degrees, distance));
                    c.fillRect(CX - 120, CY - 80, 180, 160, INK);
                    c.fillRect(CX + 60, CY - 80, 60, 160, RED);
                    c.restore();
                });
                assertEquals(SCALE, software.lastSpaceScale(), 1e-6);
                assertEveryPixel(image, degrees, distance, uv -> {
                    if (uv[0] < -120 || uv[0] > 120 || uv[1] < -80 || uv[1] > 80) {
                        return PAGE;
                    }
                    return uv[0] < 60 ? INK : RED;
                }, "the turned card at its natural size");

                Mat4 rotated = Mat4.translation(new Vec3(110, 100, 0))
                        .multiply(Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_Z, (float) Math.toRadians(20))))
                        .multiply(Mat4.translation(new Vec3(-110, -100, 0)));
                render(software, c -> {
                    c.scale(1.5f, 1.5f);
                    c.saveLayer(60, 60, 100, 80, 1f, rotated);
                    c.fillRect(60, 60, 100, 80, INK);
                    c.restore();
                });
                assertEquals(SCALE * 1.5, software.lastSpaceScale(), 1e-4, "an affine picture is not capped");
            } finally {
                software.dispose();
            }
        });
    }

    // ------------------------------------------------------------------ the near plane

    @Test
    void aCardTurnedPartlyPastTheEyeDrawsThePartInFrontAndNothingElse() {
        // A 360 point card turned 70 degrees with the eye 100 points away: the part of it more than
        // 106 points left of the pivot lies behind the eye, and the part just in front of that is
        // magnified past the window's left edge.
        float degrees = 70;
        float distance = 100;
        int[] open = new int[1];
        byte[] image = render(c -> {
            c.saveLayer(CX - 180, CY - 60, 360, 120, 1f, turned(degrees, distance));
            open[0] = c.openLayers();
            c.fillRect(CX - 180, CY - 60, 360, 120, INK);
            c.restore();
        });
        assertEquals(1, open[0], "a picture, for the part in front");
        assertEveryPixel(image, degrees, distance,
                uv -> uv[0] >= -180 && uv[0] <= 180 && uv[1] >= -60 && uv[1] <= 60 ? INK : PAGE,
                "the part in front of the eye");
        // The window's left edge is the card's, magnified: ink from top to bottom there.
        assertEquals(channel(INK, 0), pixel(image, 2, 4)[0], 2, "the near part reaches the window's corner");
        assertEquals(channel(INK, 0), pixel(image, 2, HEIGHT - 4)[0], 2);
        assertEquals(0, canvas.openLayers());
        assertEquals(1, canvas.layerStats().textures(), "one picture, no larger than the window");
    }

    @Test
    void aCardWhollyBehindTheEyeDrawsNothing() {
        byte[] page = render(c -> { });
        byte[] image = render(c -> {
            // Turned 70 degrees with the eye 100 points away, the card's left part only: all of it
            // past the eye.
            c.saveLayer(CX - 180, CY - 60, 60, 120, 1f, turned(70, 100));
            c.fillRect(CX - 180, CY - 60, 60, 120, INK);
            c.restore();
        });
        assertAtMost(0, page, image, "nothing of a card behind the eye");
    }

    // ------------------------------------------------------------------ edge-on

    @Test
    void aQuarterTurnDrawsNothingAndMakesNoPicture() {
        byte[] page = render(c -> { });
        for (float distance : new float[] {600, Float.POSITIVE_INFINITY}) {
            for (float degrees : new float[] {90, -90}) {
                int[] open = new int[1];
                byte[] image = render(c -> {
                    c.saveLayer(CX - 120, CY - 80, 240, 160, 1f, turned(degrees, distance));
                    open[0] = c.openLayers();
                    c.fillRect(CX - 120, CY - 80, 240, 160, INK);
                    c.restore();
                });
                assertEquals(0, open[0], degrees + " degrees from " + distance + ": no picture opened");
                assertAtMost(0, page, image, degrees + " degrees from " + distance + ": nothing drawn");
            }
        }
        assertEquals(0, canvas.layerStats().textures(), "and none held");
    }

    // ------------------------------------------------------------------ a glass inside

    /** Sharp stripes six points wide, which a blur would smooth to their mean. */
    private static void stripes(GlCanvas c) {
        for (int i = 0; i * 6 < W_PT; i++) {
            c.fillRect(i * 6, 0, 6, H_PT, i % 2 == 0 ? Color.rgb(0xF2A65A) : Color.rgb(0x3050C8));
        }
    }

    private static final BackdropEffect FROST = new BackdropEffect.Blur(Color.rgba(0xFFFFFF, 0.2f), 10f,
            BackdropEffect.Blur.Axis.X);

    /** The largest difference of any channel between two frames over the middle of the glass. */
    private static int glassDifference(byte[] a, byte[] b) {
        int most = 0;
        for (int y = (int) ((CY - 40) * SCALE); y < (int) ((CY + 40) * SCALE); y++) {
            for (int x = (int) ((CX - 50) * SCALE); x < (int) ((CX + 50) * SCALE); x++) {
                for (int ch = 0; ch < 3; ch++) {
                    most = Math.max(most, Math.abs(pixel(a, x, y)[ch] - pixel(b, x, y)[ch]));
                }
            }
        }
        return most;
    }

    @Test
    void aGlassInsideAPictureSeenInPerspectiveReadsOnlyWhatTheSubtreeDrew() {
        // The same glass over the stripes and over the plain page. Turned flat, a few degrees, it
        // reads the window warped through the turn, and the two differ; turned in depth, the picture
        // is a backdrop root, the glass reads only the subtree's own content, nothing here, and the
        // two are the same: its own tint, no window through it.
        Mat4 flatTurn = Mat4.translation(new Vec3(CX, CY, 0))
                .multiply(Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_Z, (float) Math.toRadians(4))))
                .multiply(Mat4.translation(new Vec3(-CX, -CY, 0)));
        byte[][] frames = new byte[4][];
        int i = 0;
        for (Mat4 turn : new Mat4[] {flatTurn, turned(4, 600)}) {
            for (boolean striped : new boolean[] {true, false}) {
                freshCanvas();
                frames[i++] = render(c -> {
                    if (striped) {
                        stripes(c);
                    }
                    c.saveLayer(CX - 80, CY - 60, 160, 120, 1f, turn);
                    c.fillBackdropRoundRect(CX - 80, CY - 60, 160, 120, 0, FROST);
                    c.restore();
                });
            }
        }
        assertTrue(glassDifference(frames[0], frames[1]) > 40,
                "a flat turn's glass reads the window: " + glassDifference(frames[0], frames[1]));
        assertTrue(glassDifference(frames[2], frames[3]) <= 1,
                "a turn in depth's glass reads none of it: " + glassDifference(frames[2], frames[3]));
    }

    // ------------------------------------------------------------------ plumbing

    private void freshCanvas() {
        canvas.dispose();
        canvas = new GlCanvas(fonts);
    }

    private static int channel(Color c, int channel) {
        return Math.round(255 * (channel == 0 ? c.r() : channel == 1 ? c.g() : c.b()));
    }

    private static int[] pixel(byte[] image, int x, int y) {
        int i = (y * WIDTH + x) * 4;
        return new int[] {image[i] & 0xFF, image[i + 1] & 0xFF, image[i + 2] & 0xFF};
    }

    /** Fails naming the worst pixel when any channel of any pixel differs by more than {@code tolerance}. */
    private static void assertAtMost(int tolerance, byte[] expected, byte[] actual, String what) {
        int worst = 0;
        int where = 0;
        for (int i = 0; i < expected.length; i++) {
            if (i % 4 == 3) {
                continue;
            }
            int d = Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF));
            if (d > worst) {
                worst = d;
                where = i / 4;
            }
        }
        assertTrue(worst <= tolerance, what + ": off by " + worst + " at " + (where % WIDTH) + "," + (where / WIDTH));
    }

    /** One frame over the page into a texture of its own, read back top-down. */
    private byte[] render(Consumer<GlCanvas> paint) {
        return render(canvas, paint);
    }

    /** One frame of {@code canvas} over the page into a texture of its own, read back top-down. */
    private static byte[] render(GlCanvas canvas, Consumer<GlCanvas> paint) {
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER));
            GL33C.glViewport(0, 0, WIDTH, HEIGHT);
            canvas.beginFrame(WIDTH, HEIGHT, SCALE);
            canvas.clear(PAGE);
            paint.accept(canvas);
            canvas.endFrame();
            return readTopDown();
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
    }

    private static byte[] readTopDown() {
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            GL33C.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[WIDTH * HEIGHT * 4];
            int stride = WIDTH * 4;
            for (int row = 0; row < HEIGHT; row++) {
                pixels.position((HEIGHT - 1 - row) * stride);
                pixels.get(out, row * stride, stride);
            }
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}

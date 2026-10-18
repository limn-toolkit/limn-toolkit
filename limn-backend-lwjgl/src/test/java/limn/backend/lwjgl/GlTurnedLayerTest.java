package limn.backend.lwjgl;

import limn.graphics.BackdropEffect;
import limn.graphics.Color;
import limn.graphics.RoundRect;
import limn.math.Mat4;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layer drawn in a space of its own and composited through a transform (ADR 072 §2.4, stage 2)
 * against a real device: a rotated or skewed subtree drawn upright into a picture and laid at the
 * angle. Each case holds the picture against what the canvas draws straight under the same
 * rotation where the two must agree, and against what only the picture can do where they must not.
 */
class GlTurnedLayerTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 400;
    private static final int H_PT = 480;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.rgb(0xF4F5F8);
    private static final Color INK = Color.rgb(0x2050C0);
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

    /** A rotation by {@code degrees} clockwise about the pivot, as a matrix a layer is laid through. */
    private static Mat4 turned(float degrees) {
        double r = Math.toRadians(degrees);
        float cos = (float) Math.cos(r);
        float sin = (float) Math.sin(r);
        return Mat4.affine2D(cos, sin, -sin, cos, CX - cos * CX + sin * CY, CY - sin * CX - cos * CY);
    }

    /** The same rotation applied straight to the canvas, each draw turned on its own. */
    private static void turnStraight(GlCanvas c, float degrees) {
        c.translate(CX, CY);
        c.rotate((float) Math.toRadians(degrees));
        c.translate(-CX, -CY);
    }

    // ------------------------------------------------------------------ the shape and its edges

    @Test
    void aShapeThroughATurnedPictureIsTheShapeDrawnTurnedWithItsEdgesWithinAPixel() {
        Consumer<GlCanvas> card = c -> c.fillRoundRect(CX - 90, CY - 60, 180, 120, 12, INK);
        byte[] straight = render(c -> {
            turnStraight(c, 23);
            card.accept(c);
        });
        int[] open = new int[1];
        byte[] picture = render(c -> {
            c.saveLayer(CX - 91, CY - 61, 182, 122, 1f, turned(23));
            open[0] = c.openLayers();
            card.accept(c);
            c.restore();
        });
        assertEquals(1, open[0], "a rotation is drawn through a picture, at an opacity of 1 too");
        // Inside and outside alike; along the edge the picture's own antialiased ramp is filtered
        // once more as it is laid at the angle, so the two may part there by a share of a pixel.
        int[] centre = pixel(picture, (int) (CX * SCALE), (int) (CY * SCALE));
        assertEquals(channel(INK, 0), centre[0], 1, "the inside is the ink");
        assertEquals(channel(PAGE, 0), pixel(picture, 20, 20)[0], 1, "the outside is the page");
        double inkStraight = coverage(straight);
        double inkPicture = coverage(picture);
        assertEquals(inkStraight, inkPicture, inkStraight * 0.002,
                "the same area covered, so every edge is where the straight draw puts it");
        // Across the card's right edge, along its normal, the ramp from page to ink: the straight
        // draw's analytic ramp is a pixel wide, and the picture's, filtered once more as it is laid
        // at the angle, stays within another pixel of it.
        double straightRamp = rampAcrossRightEdge(straight, 23);
        double pictureRamp = rampAcrossRightEdge(picture, 23);
        assertTrue(straightRamp <= 2.5, "the straight draw's own ramp, read between pixels: " + straightRamp);
        assertTrue(pictureRamp <= straightRamp + 1, "the picture's edge ramps over " + pictureRamp
                + " pixels; drawn straight it ramps over " + straightRamp);
    }

    // ------------------------------------------------------------------ clips inside and around

    @Test
    void aClipInsideATurnedPictureIsExactWhereDrawnStraightItIsTheBoundingBox() {
        // A scroll view's viewport inside a turned panel, and content running past it.
        Consumer<GlCanvas> view = c -> {
            c.clipRect(CX - 80, CY - 50, 160, 100);
            c.fillRect(CX - 150, CY - 150, 300, 300, INK);
        };
        byte[] straight = render(c -> {
            turnStraight(c, 30);
            view.accept(c);
        });
        byte[] picture = render(c -> {
            c.saveLayer(CX - 150, CY - 150, 300, 300, 1f, turned(30));
            view.accept(c);
            c.restore();
        });
        byte[] exact = render(c -> {
            turnStraight(c, 30);
            c.fillRect(CX - 80, CY - 50, 160, 100, INK);
        });
        // In the turned viewport's bounding box, near its top right corner, and past the viewport
        // itself: turned back, the point lies 107 points above the viewport's middle, past its 50.
        int x = (int) ((CX + 85) * SCALE);
        int y = (int) ((CY - 75) * SCALE);
        assertEquals(channel(INK, 0), pixel(straight, x, y)[0], 1,
                "drawn straight, the content shows in the bounding box's corner");
        assertEquals(channel(PAGE, 0), pixel(picture, x, y)[0], 1,
                "through the picture, nothing shows past the turned viewport");
        assertEquals(coverage(exact), coverage(picture), coverage(exact) * 0.003,
                "the picture shows exactly the turned viewport");
    }

    @Test
    void anEnclosingClipCutsThePictureAsItCutsADrawStraight() {
        Consumer<GlCanvas> clip = c -> c.clipRect(130.25f, 170.5f, 150, 120);
        byte[] straight = render(c -> {
            clip.accept(c);
            turnStraight(c, 17);
            c.fillRect(CX - 150, CY - 150, 300, 300, INK);
        });
        byte[] picture = render(c -> {
            clip.accept(c);
            c.saveLayer(CX - 150, CY - 150, 300, 300, 1f, turned(17));
            c.fillRect(CX - 150, CY - 150, 300, 300, INK);
            c.restore();
        });
        assertAtMost(2, straight, picture, "a turned picture under a rectangular clip");
    }

    @Test
    void anEnclosingRoundedClipCutsThePictureAsItCutsADrawStraight() {
        Consumer<GlCanvas> clip = c -> c.clipRoundRect(RoundRect.of(120, 160, 160, 150, 22));
        byte[] straight = render(c -> {
            clip.accept(c);
            turnStraight(c, 17);
            c.fillRect(CX - 150, CY - 150, 300, 300, INK);
        });
        byte[] picture = render(c -> {
            clip.accept(c);
            c.saveLayer(CX - 150, CY - 150, 300, 300, 1f, turned(17));
            c.fillRect(CX - 150, CY - 150, 300, 300, INK);
            c.restore();
        });
        assertAtMost(2, straight, picture, "a turned picture inside a rounded panel, its corners antialiased");
    }

    // ------------------------------------------------------------------ what needs no picture

    @Test
    void aTransformThatKeepsTheAxesIsDrawnStraightWithNoPicture() {
        Consumer<GlCanvas> card = c -> c.fillRoundRect(40, 30, 120, 80, 10, INK);
        byte[] straight = render(c -> {
            c.translate(31.5f, 12.25f);
            c.scale(1.5f, 0.75f);
            card.accept(c);
        });
        int[] open = new int[1];
        byte[] layered = render(c -> {
            c.saveLayer(39, 29, 122, 82, 1f, Mat4.affine2D(1.5f, 0, 0, 0.75f, 31.5f, 12.25f));
            open[0] = c.openLayers();
            card.accept(c);
            c.restore();
        });
        assertEquals(0, open[0], "a move and a scale are drawn straight");
        assertEquals(0, canvas.layerStats().textures(), "and hold no picture");
        assertAtMost(0, straight, layered, "the same pixels as translate and scale");
    }

    @Test
    void thePictureIsFreedTheFrameAfterTheLastThatOpensIt() {
        render(c -> {
            c.saveLayer(CX - 60, CY - 40, 120, 80, 1f, turned(9));
            c.fillRect(CX - 50, CY - 30, 100, 60, INK);
            c.restore();
        });
        assertEquals(1, canvas.layerStats().textures(), "a turned frame holds its picture");
        render(c -> c.fillRect(CX - 50, CY - 30, 100, 60, INK));
        assertEquals(0, canvas.layerStats().textures(), "the frame after the turn ends frees it");
    }

    @Test
    void aPictureTakenLargerThanItsPartIsClearedInARingRoundIt() {
        // A frame fills a turned picture wholly; the next takes the same picture, kept between
        // consecutive frames, for a smaller part. The composite filters, so its edge reads the
        // texels just past the part, which still hold the frame before's ink unless cleared.
        Consumer<GlCanvas> small = c -> {
            c.saveLayer(CX - 40, CY - 30, 80, 60, 1f, turned(12));
            c.fillRect(CX - 39, CY - 29, 78, 58, Color.rgb(0xE8E8E8));
            c.restore();
        };
        render(c -> {
            c.saveLayer(CX - 70, CY - 55, 140, 110, 1f, turned(12));
            c.fillRect(CX - 70, CY - 55, 140, 110, INK);
            c.restore();
        });
        byte[] reused = render(small);
        assertEquals(1, canvas.layerStats().textures(), "the same picture, kept and taken again");
        freshCanvas();
        byte[] fresh = render(small);
        assertAtMost(1, fresh, reused, "a reused picture laid at an angle, against a fresh one");
    }

    @Test
    void aPictureLargerThanTheWindowIsDrawnSmallerAndStillCoversWhatIsSeen() {
        // A skew that stretches a panel well past the window: the picture is capped at the
        // framebuffer, drawn at a smaller scale, and laid over all that can be seen.
        Mat4 skew = Mat4.affine2D(3f, 0.4f, -2.6f, 3f, CX - 3 * CX + 2.6f * CY, CY - 0.4f * CX - 3 * CY);
        byte[] picture = render(c -> {
            c.saveLayer(CX - 120, CY - 120, 240, 240, 1f, skew);
            c.fillRect(CX - 120, CY - 120, 240, 240, INK);
            c.restore();
        });
        assertEquals(channel(INK, 0), pixel(picture, (int) (CX * SCALE), (int) (CY * SCALE))[0], 1);
        assertEquals(channel(INK, 0), pixel(picture, 30, (int) (CY * SCALE))[0], 1,
                "the stretched panel reaches the window's edge");
    }

    // ------------------------------------------------------------------ a glass inside

    /**
     * A page banded across in ramps, each from one colour to the other and back, so that a glass
     * reading two pixels from where it should reads ten steps off, while a ramp read between pixels
     * is the ramp, and the comparison holds what the warp gets right; far past the window, so that
     * turned back for the reference it still covers it.
     */
    private static void bands(GlCanvas c) {
        Color warm = Color.rgb(0xF2A65A);
        Color cool = Color.rgb(0x3050C8);
        for (int i = -6; i * 40 < W_PT + 240; i++) {
            float x = i * 40;
            boolean up = Math.floorMod(i, 2) == 0;
            c.fillRect(x, -200, 40, H_PT + 400,
                    new limn.graphics.LinearGradient(x, 0, x + 40, 0, up ? warm : cool, up ? cool : warm));
        }
    }

    /** A blur across the bands, which smooths them into what a comparison between pixels can hold. */
    private static final BackdropEffect FROST = new BackdropEffect.Blur(Color.rgba(0xFFFFFF, 0.2f), 10f,
            BackdropEffect.Blur.Axis.X);

    @Test
    void aGlassInASlightlyTurnedPictureReadsTheWindowAsTheSameGlassUprightUnderTheInverseTurn() {
        float degrees = 4;
        float gx = CX - 80;
        float gy = CY - 70;
        float gw = 160;
        float gh = 140;
        byte[] turnedGlass = render(c -> {
            bands(c);
            c.saveLayer(gx - 2, gy - 2, gw + 4, gh + 4, 1f, turned(degrees));
            c.fillBackdropRoundRect(gx, gy, gw, gh, 12, FROST);
            c.restore();
        });
        freshCanvas(); // no copy left over from the frame before, which would read right by luck
        byte[] upright = render(c -> {
            c.save();
            turnStraight(c, -degrees);
            bands(c);
            c.restore();
            c.fillBackdropRoundRect(gx, gy, gw, gh, 12, FROST);
        });
        // Each pixel of the upright glass's inside, against the turned frame where the turn draws
        // that point, read between pixels as the picture is.
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        int worst = 0;
        String where = "";
        for (int y = (int) ((gy + 14) * SCALE); y < (int) ((gy + gh - 14) * SCALE); y++) {
            for (int x = (int) ((gx + 14) * SCALE); x < (int) ((gx + gw - 14) * SCALE); x++) {
                double px = (x + 0.5) / SCALE - CX;
                double py = (y + 0.5) / SCALE - CY;
                double tx = (CX + cos * px - sin * py) * SCALE - 0.5;
                double ty = (CY + sin * px + cos * py) * SCALE - 0.5;
                for (int ch = 0; ch < 3; ch++) {
                    int d = (int) Math.round(Math.abs(bilinear(turnedGlass, tx, ty, ch) - pixel(upright, x, y)[ch]));
                    if (d > worst) {
                        worst = d;
                        where = x + "," + y;
                    }

                }
            }
        }
        // A few steps: the turned frame passes the page through two filtered resamplings, the warp
        // into the glass's space and the picture laid at the angle, each of which rounds the
        // ramps' turning points by a share of a pixel where the reference draws them exactly; a
        // glass that read its own pixels of the window instead is off by a hundred and more.
        assertTrue(worst <= 8, "a glass turned 4 degrees reads the window as upright under the inverse "
                + "turn: off by " + worst + " at " + where);
    }

    // ------------------------------------------------------------------ plumbing

    private void freshCanvas() {
        canvas.dispose();
        canvas = new GlCanvas(fonts);
    }

    private static float channel(Color c, int channel) {
        return Math.round(255 * (channel == 0 ? c.r() : channel == 1 ? c.g() : c.b()));
    }

    private static int[] pixel(byte[] image, int x, int y) {
        int i = (y * WIDTH + x) * 4;
        return new int[] {image[i] & 0xFF, image[i + 1] & 0xFF, image[i + 2] & 0xFF};
    }

    private static double bilinear(byte[] image, double x, double y, int ch) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        double a = pixel(image, x0, y0)[ch] * (1 - fx) + pixel(image, x0 + 1, y0)[ch] * fx;
        double b = pixel(image, x0, y0 + 1)[ch] * (1 - fx) + pixel(image, x0 + 1, y0 + 1)[ch] * fx;
        return a * (1 - fy) + b * fy;
    }

    /** How much of the target is ink, in pixels: each pixel's share from the page to the ink. */
    private static double coverage(byte[] image) {
        double page = channel(PAGE, 0);
        double ink = channel(INK, 0);
        double sum = 0;
        for (int i = 0; i < image.length; i += 4) {
            sum += Math.max(0, Math.min(1, ((image[i] & 0xFF) - page) / (ink - page)));
        }
        return sum;
    }

    /**
     * How far, in pixels along the edge's normal, the share of ink across the right edge of the
     * card (90 points from the pivot, turned by {@code degrees}) goes from 5% to 95%, read between
     * pixels at a tenth of a pixel's steps through the edge's middle.
     */
    private static double rampAcrossRightEdge(byte[] image, float degrees) {
        double r = Math.toRadians(degrees);
        double nx = Math.cos(r);
        double ny = Math.sin(r);
        double ex = CX * SCALE + 90 * SCALE * nx;
        double ey = CY * SCALE + 90 * SCALE * ny;
        double page = channel(PAGE, 0);
        double ink = channel(INK, 0);
        double inside = Double.NaN;
        double outside = Double.NaN;
        for (int step = -60; step <= 60; step++) {
            double t = step / 10.0;
            double share = (bilinear(image, ex + t * nx - 0.5, ey + t * ny - 0.5, 0) - page) / (ink - page);
            if (share >= 0.95) {
                inside = t; // the last point still fully ink, going outward
            }
            if (share > 0.05) {
                outside = t; // the last point with any ink
            }
        }
        return outside - inside;
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
            assertEquals(fbo, GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING),
                    "the frame's own target is bound again when every layer has closed");
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

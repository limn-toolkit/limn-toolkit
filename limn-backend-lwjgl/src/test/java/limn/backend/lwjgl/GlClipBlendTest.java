package limn.backend.lwjgl;

import limn.graphics.BlendMode;
import limn.graphics.Color;
import limn.graphics.FillRule;
import limn.graphics.Path2D;
import limn.graphics.Rect;
import limn.math.Affine2D;
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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Path clips and the blend modes on a real device. A path clip lets through what its path fills and
 * nothing else, under either fill rule, intersects another, cuts a layer's composite, and leaves a
 * blend mode what it is without the clip; a rectangle clipped under a turn stops at its turned
 * edges. Each blend mode gives, on a target of any coverage, the premultiplied result of its rule,
 * acts only where the draw covers, and, darken and lighten included, leaves no darker or lighter
 * ring at an antialiased edge.
 */
class GlClipBlendTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 240;
    private static final int H_PT = 160;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.WHITE;
    private static final Color INK = Color.rgb(0x2050C0);
    /**
     * How far from an edge, in points, a point is taken to be clearly on its side: past a right
     * angle a fill's mitred fringe reaches about two of the frame's pixels (and past a sharper tip
     * farther, see {@link #starTips}).
     */
    private static final float MARGIN = 1.5f;

    /** The destination drawn first, and the source drawn over it in each mode. */
    private static final Color DST = Color.rgba(0xFF0000, 0.6f);
    private static final Color SRC = Color.rgba(0x3366FF, 0.7f);
    private static final Rect DST_RECT = new Rect(20, 20, 100, 80);
    private static final Rect SRC_RECT = new Rect(70, 50, 100, 80);

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

    private static Path2D star(float cx, float cy, float r) {
        Path2D star = new Path2D();
        for (int i = 0; i < 5; i++) {
            double a = -Math.PI / 2 + i * 4 * Math.PI / 5;
            float x = cx + (float) (r * Math.cos(a));
            float y = cy + (float) (r * Math.sin(a));
            if (i == 0) {
                star.moveTo(x, y);
            } else {
                star.lineTo(x, y);
            }
        }
        return star.close();
    }

    // ------------------------------------------------------------------ clips

    @Test
    void aPathClipLetsThroughWhatThePathFillsUnderEitherRule() {
        for (FillRule rule : FillRule.values()) {
            Path2D clip = star(120, 80, 70).setFillRule(rule);
            byte[] image = render(PAGE, c -> {
                c.save();
                c.clipPath(clip);
                c.fillRect(0, 0, W_PT, H_PT, INK);
                c.restore();
            });
            // The clip's coverage is the fill's, its antialiasing included, which past a sharp tip
            // reaches a pixel or two along the tip.
            assertSameImage(render(PAGE, c -> c.fillPath(clip, INK)), image, rule + " star against its fill");
            int[] counts = compare(image, clip, MARGIN, rule + " star", starTips(120, 80, 70));
            assertTrue(counts[0] > 500 && counts[1] > 500, rule + ": points on both sides, " + counts[0]
                    + " inside and " + counts[1] + " outside");
        }
    }

    @Test
    void aRectangleClippedUnderATurnStopsAtItsTurnedEdges() {
        // Case 4 of the measure: a square clip turned 30 degrees used to fill its upright box.
        Affine2D turn = Affine2D.rotation((float) Math.toRadians(30), 120, 80);
        Path2D square = new Path2D().addRect(85, 45, 70, 70).transformed(turn);
        byte[] image = render(PAGE, c -> {
            c.save();
            c.rotate((float) Math.toRadians(30), 120, 80);
            c.clipRect(85, 45, 70, 70);
            c.fillRect(0, -100, 400, 400, INK);
            c.restore();
        });
        assertSameImage(render(PAGE, c -> c.fillPath(square, INK)), image, "the turned square against its fill");
        int[] counts = compare(image, square, MARGIN, "the turned square");
        assertTrue(counts[0] > 500 && counts[1] > 500, "both sides sampled");
        // The box's corner, which the old clip filled.
        Rect box = square.bounds();
        assertNear(rgb(PAGE), pixel(image, box.x() + 3, box.y() + 3), 2, "the box's corner is the page");
    }

    @Test
    void twoPathClipsLeaveTheirIntersectionAndTheRestoreLiftsEach() {
        Path2D left = new Path2D().addCircle(95, 80, 55);
        Path2D right = new Path2D().addCircle(145, 80, 55);
        byte[] image = render(PAGE, c -> {
            c.save();
            c.clipPath(left);
            c.save();
            c.clipPath(right);
            c.fillRect(0, 0, W_PT, H_PT, INK);
            c.restore();
            c.fillRect(0, 150, W_PT, 10, INK); // under the first clip only: below both circles
            c.restore();
            c.fillRect(0, 0, 10, 10, INK); // under none
        });
        assertNear(rgb(INK), pixel(image, 120, 80), 2, "in both");
        assertNear(rgb(PAGE), pixel(image, 60, 80), 2, "in the left circle only");
        assertNear(rgb(PAGE), pixel(image, 180, 80), 2, "in the right circle only");
        assertNear(rgb(PAGE), pixel(image, 95, 155), 2, "below the first clip's circle");
        assertNear(rgb(INK), pixel(image, 5, 5), 2, "after both restores, unclipped");
    }

    @Test
    void aPathClipCutsALayersCompositeAndKeepsAMultiplyAMultiply() {
        Path2D clip = new Path2D().addCircle(120, 80, 50);
        Color grey = Color.rgb(0xCCCCCC);
        byte[] image = render(grey, c -> {
            c.save();
            c.clipPath(clip);
            c.setBlendMode(BlendMode.MULTIPLY);
            c.fillRect(0, 0, 120, H_PT, Color.rgb(0xFF0000)); // the left half, multiplied
            c.setBlendMode(BlendMode.NORMAL);
            c.saveLayer(120, 0, 120, H_PT, 0.5f);
            c.fillRect(0, 0, W_PT, H_PT, INK); // the right half, through a layer at half
            c.restore();
            c.restore();
        });
        // A clip by a layer of its own would have isolated the multiply, which then shows as red.
        assertNear(new int[] {0xCC, 0, 0}, pixel(image, 100, 80), 2, "multiplied with the page under the clip");
        int[] half = {Math.round((0x20 + 0xCC) / 2f), Math.round((0x50 + 0xCC) / 2f), Math.round((0xC0 + 0xCC) / 2f)};
        assertNear(half, pixel(image, 140, 80), 2, "the layer at half, inside the clip");
        assertNear(rgb(grey), pixel(image, 100, 20), 2, "the multiply outside the clip");
        assertNear(rgb(grey), pixel(image, 140, 20), 2, "the layer outside the clip");
    }

    @Test
    void aPathClipCostsItsFillAndADrawAfterItCostsNothingMore() {
        Path2D triangle = new Path2D().moveTo(120, 20).lineTo(180, 140).lineTo(60, 140).close();
        render(PAGE, c -> {
            c.save();
            c.clipPath(triangle);
            c.fillRect(0, 0, W_PT, H_PT, INK);
            c.fillRect(10, 10, 50, 50, Color.rgb(0x00AA00));
            c.restore();
        });
        assertEquals(2, canvas.drawCallsLastFrame(), "the triangle's fill into the mask, then the frame's one draw");
    }

    @Test
    void aLoneCircleRectangleOrRoundedRectangleClipsWithNoMask() {
        // The clip a rounded rectangle makes, with no mask drawn: one draw for the frame, where a
        // mask costs a fill into it and a flush on each side of it.
        for (Path2D shape : java.util.List.of(new Path2D().addCircle(120, 80, 50),
                new Path2D().addRect(40, 30, 160, 100), new Path2D().addRoundRect(40, 30, 160, 100, 12))) {
            byte[] byPath = render(PAGE, c -> {
                c.save();
                c.clipPath(shape);
                c.fillRect(0, 0, W_PT, H_PT, INK);
                c.restore();
            });
            assertEquals(1, canvas.drawCallsLastFrame(), shape.bounds() + ": the frame's one draw");
            limn.graphics.RoundRect same = limn.backend.internal.PathShapes.shapeOf(shape);
            byte[] byRoundRect = render(PAGE, c -> {
                c.save();
                c.clipRoundRect(same);
                c.fillRect(0, 0, W_PT, H_PT, INK);
                c.restore();
            });
            assertTrue(java.util.Arrays.equals(byRoundRect, byPath), shape.bounds() + ": the rounded rectangle's clip");
        }
        // Two shapes, a turn or a second rounded clip: the mask, as before.
        Path2D two = new Path2D().addCircle(60, 80, 30).addCircle(180, 80, 30);
        render(PAGE, c -> {
            c.save();
            c.clipPath(two);
            c.fillRect(0, 0, W_PT, H_PT, INK);
            c.restore();
        });
        assertEquals(2, canvas.drawCallsLastFrame(), "two circles: their fill into the mask, then the draw");
    }

    // ------------------------------------------------------------------ blend modes

    @Test
    void everyModeGivesItsRuleOnATargetOfAnyCoverage() {
        for (BlendMode mode : BlendMode.values()) {
            byte[] image = render(Color.TRANSPARENT, c -> {
                c.fillRect(DST_RECT, DST);
                c.setBlendMode(mode);
                c.fillRect(SRC_RECT, SRC);
            });
            assertRule(mode, false, image);
        }
    }

    @Test
    void everyModeInALayerGivesItsRuleAgainstTheLayersOwnPicture() {
        for (BlendMode mode : BlendMode.values()) {
            byte[] image = render(Color.TRANSPARENT, c -> {
                // Laid behind nothing, over a clear target, the composite is the picture itself.
                c.setBlendMode(BlendMode.DESTINATION_OVER);
                c.saveLayer(0, 0, W_PT, H_PT, 1f);
                c.fillRect(DST_RECT, DST);
                c.setBlendMode(mode);
                c.fillRect(SRC_RECT, SRC);
                c.restore();
            });
            assertRule(mode, true, image);
        }
    }

    @Test
    void darkenAndLightenLeaveNoRingAtAnAntialiasedEdge() {
        Color grey = Color.rgb(0x808080);
        byte[] dark = render(grey, c -> {
            c.setBlendMode(BlendMode.DARKEN);
            c.fillCircle(120, 80, 50, Color.WHITE); // white darkens nothing
        });
        byte[] light = render(grey, c -> {
            c.setBlendMode(BlendMode.LIGHTEN);
            c.fillCircle(120, 80, 50, Color.BLACK); // black lightens nothing
        });
        for (int y = 20; y < 140; y++) {
            for (int x = 60; x < 180; x++) {
                int[] d = pixelAt(dark, x * 2, y * 2);
                int[] l = pixelAt(light, x * 2, y * 2);
                for (int i = 0; i < 3; i++) {
                    if (Math.abs(d[i] - 0x80) > 1 || Math.abs(l[i] - 0x80) > 1) {
                        fail("at " + x + ", " + y + ": darken " + d[0] + ", lighten " + l[0] + " over 128");
                    }
                }
            }
        }
    }

    @Test
    void aRuleWeighsItsResultByTheEdgesCoverage() {
        // Source over red: at the circle's edge the red must stay in part, not be replaced whole.
        Color red = Color.rgb(0xFF0000);
        byte[] image = render(red, c -> {
            c.setBlendMode(BlendMode.SOURCE);
            c.fillCircle(120, 80, 50, Color.rgb(0x0000FF));
        });
        int partial = 0;
        // A row where the circle's edge falls inside a pixel, at 331.66 of the frame's pixels.
        for (int x = 320; x < 345; x++) {
            int[] p = pixelAt(image, x, 200);
            assertEquals(255, p[0] + p[2], 2, "red and blue share the edge pixel " + x + ": " + p[0] + ", " + p[2]);
            if (p[0] > 10 && p[2] > 10) {
                partial++;
            }
        }
        assertTrue(partial >= 1, "an edge pixel that is part red and part blue");
    }

    @Test
    void theRulesTheFixedFactorsCannotDrawCopyTheTargetOncePerRun() {
        render(PAGE, c -> {
            c.setBlendMode(BlendMode.SOURCE_IN);
            c.fillRect(10, 10, 20, 20, INK);
            c.fillRect(40, 10, 20, 20, INK);
        });
        assertEquals(1, canvas.targetCopiesLastFrame(), "two shapes apart: one run, one copy");
        assertEquals(1, canvas.drawCallsLastFrame());
        render(PAGE, c -> {
            c.setBlendMode(BlendMode.SOURCE_IN);
            c.fillRect(10, 10, 20, 20, INK);
            c.fillRect(20, 10, 20, 20, INK);
        });
        assertEquals(2, canvas.targetCopiesLastFrame(), "two that overlap: a run each");
        render(PAGE, c -> {
            c.setBlendMode(BlendMode.XOR);
            c.fillRect(10, 10, 20, 20, INK);
            c.fillRect(20, 10, 20, 20, INK);
        });
        assertEquals(0, canvas.targetCopiesLastFrame(), "a rule the factors draw copies nothing");
    }

    // ------------------------------------------------------------------ oracle

    private static void assertRule(BlendMode mode, boolean inGroup, byte[] image) {
        float[] d = premultiplied(DST);
        float[] s = premultiplied(SRC);
        float[] none = {0, 0, 0, 0};
        String where = mode + (inGroup ? " in a layer" : " on the target");
        assertPremultiplied(rule(mode, inGroup, s, d), rgba(image, 95, 75), where + ", over both");
        assertPremultiplied(rule(mode, inGroup, s, none), rgba(image, 150, 115), where + ", over the source alone");
        assertPremultiplied(d, rgba(image, 40, 40), where + ", the destination alone, untouched");
        assertPremultiplied(none, rgba(image, 200, 145), where + ", neither");
    }

    /** The premultiplied pixel {@code mode} leaves for source {@code s} over destination {@code d}. */
    private static float[] rule(BlendMode mode, boolean inGroup, float[] s, float[] d) {
        float as = s[3];
        float ad = d[3];
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            float cs = s[i];
            float cd = d[i];
            boolean alpha = i == 3;
            out[i] = switch (mode) {
                case NORMAL -> cs + cd * (1 - as);
                case CLEAR -> 0;
                case SOURCE -> cs;
                case DESTINATION_OVER -> cd + cs * (1 - ad);
                case SOURCE_IN -> cs * ad;
                case DESTINATION_IN -> cd * as;
                case SOURCE_OUT -> cs * (1 - ad);
                case DESTINATION_OUT -> cd * (1 - as);
                case SOURCE_ATOP -> cs * ad + cd * (1 - as);
                case DESTINATION_ATOP -> cd * as + cs * (1 - ad);
                case XOR -> cs * (1 - ad) + cd * (1 - as);
                case ADDITIVE -> alpha ? (inGroup ? Math.min(1, as + ad) : ad) : Math.min(1, cs + cd);
                case MULTIPLY -> alpha ? (inGroup ? as + ad * (1 - as) : ad)
                        : inGroup ? cs * (1 - ad) + cs * cd + cd * (1 - as) : cs * cd + cd * (1 - as);
                case SCREEN -> alpha ? (inGroup ? as + ad * (1 - as) : ad) : cs + cd - cs * cd;
                case DARKEN, LIGHTEN -> {
                    if (alpha) {
                        yield inGroup ? as + ad * (1 - as) : ad;
                    }
                    float b = mode == BlendMode.DARKEN ? Math.min(cs * ad, cd * as) : Math.max(cs * ad, cd * as);
                    yield b + cs * (1 - ad) + cd * (1 - as);
                }
            };
        }
        return out;
    }

    private static float[] premultiplied(Color c) {
        return new float[] {c.r() * c.a(), c.g() * c.a(), c.b() * c.a(), c.a()};
    }

    private static void assertPremultiplied(float[] expected, int[] actual, String what) {
        for (int i = 0; i < 4; i++) {
            int e = Math.round(Math.min(1, expected[i]) * 255);
            if (Math.abs(e - actual[i]) > 2) {
                fail(what + ": expected " + Math.round(expected[0] * 255) + "," + Math.round(expected[1] * 255) + ","
                        + Math.round(expected[2] * 255) + "," + Math.round(expected[3] * 255) + ", was " + actual[0]
                        + "," + actual[1] + "," + actual[2] + "," + actual[3]);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Asserts the image against a filled region; returns how many points were inside and outside. */
    private static int[] compare(byte[] image, Path2D region, float margin, String what, float... skip) {
        int inside = 0;
        int outside = 0;
        for (float y = 1; y < H_PT - 1; y += 1.5f) {
            for (float x = 1; x < W_PT - 1; x += 1.5f) {
                Boolean side = clearly(region, x, y, margin);
                if (side == null || near(skip, x, y)) {
                    continue;
                }
                int[] p = pixel(image, x, y);
                if (side) {
                    inside++;
                    assertNear(rgb(INK), p, 2, what + ": inside at " + x + ", " + y);
                } else {
                    outside++;
                    assertNear(rgb(PAGE), p, 2, what + ": outside at " + x + ", " + y);
                }
            }
        }
        return new int[] {inside, outside};
    }

    /** Every pixel of {@code actual} within 2 of {@code expected}'s, channel by channel. */
    private static void assertSameImage(byte[] expected, byte[] actual, String what) {
        int worst = 0;
        int at = -1;
        for (int i = 0; i < expected.length; i++) {
            int d = Math.abs((expected[i] & 0xff) - (actual[i] & 0xff));
            if (d > worst) {
                worst = d;
                at = i / 4;
            }
        }
        assertTrue(worst <= 2, what + ": " + worst + " apart at pixel " + (at % WIDTH) + ", " + (at / WIDTH));
    }

    /**
     * A star's five tips, x and y of each: past a tip of 36 degrees a fill's fringe, mitred to twice
     * its width, reaches two of the frame's pixels along the tip, and covers the pixel at the tip
     * about half where the outline covers a tenth of it, which the comparison with the fill holds and
     * a point test cannot.
     */
    private static float[] starTips(float cx, float cy, float r) {
        float[] tips = new float[10];
        for (int i = 0; i < 5; i++) {
            double a = -Math.PI / 2 + i * 4 * Math.PI / 5;
            tips[i * 2] = cx + (float) (r * Math.cos(a));
            tips[i * 2 + 1] = cy + (float) (r * Math.sin(a));
        }
        return tips;
    }

    /** Whether a point lies within 4 points of one of {@code points}, x and y of each. */
    private static boolean near(float[] points, float x, float y) {
        for (int i = 0; i < points.length; i += 2) {
            if (Math.hypot(points[i] - x, points[i + 1] - y) < 4) {
                return true;
            }
        }
        return false;
    }

    private static Boolean clearly(Path2D region, float x, float y, float margin) {
        boolean in = region.contains(x, y);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (region.contains(x + dx * margin, y + dy * margin) != in) {
                    return null;
                }
            }
        }
        return in;
    }

    private static int[] rgb(Color c) {
        return new int[] {Math.round(c.r() * 255), Math.round(c.g() * 255), Math.round(c.b() * 255)};
    }

    private static void assertNear(int[] expected, int[] actual, int tolerance, String what) {
        for (int i = 0; i < 3; i++) {
            if (Math.abs(expected[i] - actual[i]) > tolerance) {
                fail(what + ": expected " + expected[0] + "," + expected[1] + "," + expected[2] + ", was "
                        + actual[0] + "," + actual[1] + "," + actual[2]);
            }
        }
    }

    /** The pixel whose centre is nearest a point given in logical points, its RGB. */
    private static int[] pixel(byte[] image, float xPt, float yPt) {
        return pixelAt(image, Math.min(WIDTH - 1, (int) (xPt * SCALE)), Math.min(HEIGHT - 1, (int) (yPt * SCALE)));
    }

    private static int[] pixelAt(byte[] image, int x, int y) {
        int at = (y * WIDTH + x) * 4;
        return new int[] {image[at] & 0xff, image[at + 1] & 0xff, image[at + 2] & 0xff};
    }

    /** The premultiplied pixel at a point in logical points, its RGBA. */
    private static int[] rgba(byte[] image, float xPt, float yPt) {
        int at = ((int) (yPt * SCALE) * WIDTH + (int) (xPt * SCALE)) * 4;
        return new int[] {image[at] & 0xff, image[at + 1] & 0xff, image[at + 2] & 0xff, image[at + 3] & 0xff};
    }

    /** Draws a frame cleared to {@code page} into a framebuffer of its own with a stencil, read top-down. */
    private byte[] render(Color page, Consumer<GlCanvas> paint) {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int depthStencil = Gl.glGenRenderbuffers();
        int previousFbo = Gl.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            Gl.glActiveTexture(GL33C.GL_TEXTURE0);
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, depthStencil);
            Gl.glRenderbufferStorage(GL33C.GL_RENDERBUFFER, GL33C.GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
            Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, 0);
            Gl.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL33C.GL_RENDERBUFFER, depthStencil);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER));
            Gl.glViewport(0, 0, WIDTH, HEIGHT);
            canvas.beginFrame(WIDTH, HEIGHT, SCALE);
            canvas.clear(page);
            paint.accept(canvas);
            canvas.endFrame();
            ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
            try {
                Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
                Gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
                byte[] image = new byte[WIDTH * HEIGHT * 4];
                int row = WIDTH * 4;
                for (int y = 0; y < HEIGHT; y++) {
                    pixels.position((HEIGHT - 1 - y) * row);
                    pixels.get(image, y * row, row);
                }
                return image;
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } finally {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
            Gl.glDeleteRenderbuffers(depthStencil);
        }
    }
}

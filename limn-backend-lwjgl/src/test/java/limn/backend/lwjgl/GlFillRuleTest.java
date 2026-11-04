package limn.backend.lwjgl;

import limn.graphics.BlendMode;
import limn.graphics.Color;
import limn.graphics.FillRule;
import limn.graphics.Path2D;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A path filled under its rule on a real device: a hole turning the other way is empty under
 * non-zero, one turning the same way only under even-odd, a five-pointed star's middle is filled
 * or empty as the rule says while its points are whole, and an outline crossing itself, as a line
 * chart's area does where its values change sign, fills both lobes and nothing between. The same
 * inside a layer, under a blend drawn in two passes, after another such fill (the stencil is left
 * clean), and on a target with no stencil buffer, which fills each contour on its own; a path that
 * needs no stencil keeps the batch's one draw.
 */
class GlFillRuleTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 240;
    private static final int H_PT = 160;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.WHITE;
    private static final Color INK = Color.rgb(0x2050C0);

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

    /** A square from (20, 20) to (100, 100) and one inside it, the inner one turning as asked. */
    private static Path2D squareWithInner(boolean sameWay, FillRule rule) {
        Path2D path = new Path2D().setFillRule(rule);
        path.moveTo(20, 20).lineTo(100, 20).lineTo(100, 100).lineTo(20, 100).close();
        if (sameWay) {
            path.moveTo(40, 40).lineTo(80, 40).lineTo(80, 80).lineTo(40, 80).close();
        } else {
            path.moveTo(40, 40).lineTo(40, 80).lineTo(80, 80).lineTo(80, 40).close();
        }
        return path;
    }

    /** A five-pointed star as one outline crossing itself, centred at (cx, cy). */
    private static Path2D star(float cx, float cy, float radius, FillRule rule) {
        Path2D path = new Path2D().setFillRule(rule);
        for (int i = 0; i < 5; i++) {
            double angle = -Math.PI / 2 + i * 4 * Math.PI / 5;
            float x = (float) (cx + radius * Math.cos(angle));
            float y = (float) (cy + radius * Math.sin(angle));
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        return path.close();
    }

    @Test
    void aSegmentAfterACloseStartsASubpathOfItsOwnAsContainsReadsIt() {
        // Two triangles sharing a corner, the second drawn on from the first's close without a move:
        // a new subpath from where the closed one began, as Path2D.contains reads it. Collected
        // onto the closed one, the two made one pentagon, and its middle was filled.
        Path2D two = new Path2D().moveTo(60, 20).lineTo(100, 20).lineTo(100, 60).close()
                .lineTo(20, 60).lineTo(20, 20).close();
        assertTrue(two.contains(90, 30) && two.contains(25, 40), "the fixture: each triangle");
        assertTrue(!two.contains(60, 52), "and the fixture: not between them");
        byte[] image = render(c -> c.fillPath(two, INK));
        assertColor(INK, image, 90, 30, "the first triangle");
        assertColor(INK, image, 25, 40, "the second");
        assertColor(PAGE, image, 60, 52, "nothing between them");
    }

    @Test
    void aSubpathThatIsOnlyAMoveDrawsNoDot() {
        // The stroker and the page draw nothing for it; the capsule path drew a dot there.
        Path2D path = new Path2D().moveTo(30, 30).moveTo(60, 80).lineTo(110, 80);
        byte[] image = render(c -> c.drawPath(path, 8, INK));
        assertColor(PAGE, image, 30, 30, "no dot at the lone move");
        assertColor(INK, image, 85, 80, "the line drawn");
    }

    @Test
    void aHoleTurningTheOtherWayIsEmptyUnderNonZero() {
        byte[] image = render(c -> c.fillPath(squareWithInner(false, FillRule.NON_ZERO), INK));
        assertColor(INK, image, 30, 30, "the ring");
        assertColor(PAGE, image, 60, 60, "the hole");
        assertColor(PAGE, image, 110, 60, "beside the square");
    }

    @Test
    void aHoleTurningTheSameWayIsFilledUnderNonZeroAndEmptyUnderEvenOdd() {
        byte[] nonZero = render(c -> c.fillPath(squareWithInner(true, FillRule.NON_ZERO), INK));
        assertColor(INK, nonZero, 60, 60, "the inner square under non-zero");
        byte[] evenOdd = render(c -> c.fillPath(squareWithInner(true, FillRule.EVEN_ODD), INK));
        assertColor(PAGE, evenOdd, 60, 60, "the inner square under even-odd");
        assertColor(INK, evenOdd, 30, 30, "the ring under even-odd");
    }

    @Test
    void aStarIsWholeAndItsMiddleIsAsTheRuleSays() {
        byte[] nonZero = render(c -> c.fillPath(star(120, 80, 70, FillRule.NON_ZERO), INK));
        byte[] evenOdd = render(c -> c.fillPath(star(120, 80, 70, FillRule.EVEN_ODD), INK));
        assertColor(INK, nonZero, 120, 82, "the middle under non-zero");
        assertColor(PAGE, evenOdd, 120, 82, "the middle under even-odd");
        for (int i = 0; i < 5; i++) {
            double angle = -Math.PI / 2 + i * 2 * Math.PI / 5;
            int x = (int) Math.round(120 + 50 * Math.cos(angle));
            int y = (int) Math.round(80 + 50 * Math.sin(angle));
            assertColor(INK, nonZero, x, y, "point " + i + " under non-zero");
            assertColor(INK, evenOdd, x, y, "point " + i + " under even-odd");
        }
        assertColor(PAGE, nonZero, 120, 128, "between the two lower points");
    }

    @Test
    void anOutlineCrossingItselfFillsEveryLobeAndNothingBetween() {
        // A line chart's area whose line crosses its baseline, y = 80, four times: along the line,
        // then back along the baseline. Crossings at x = 60, 100, 140 and 180.
        Path2D area = new Path2D();
        area.moveTo(40, 60).lineTo(80, 100).lineTo(120, 60).lineTo(160, 100).lineTo(200, 60)
                .lineTo(200, 80).lineTo(40, 80).close();
        byte[] image = render(c -> c.fillPath(area, INK));
        for (int lobe = 0; lobe < 5; lobe++) {
            int x = 40 + lobe * 40;
            boolean up = lobe % 2 == 0;
            int inX = lobe == 0 ? 44 : lobe == 4 ? 196 : x;
            assertColor(INK, image, inX, up ? 74 : 86, "inside lobe " + lobe);
            assertColor(PAGE, image, inX, up ? 86 : 74, "across the baseline from lobe " + lobe);
        }
        assertColor(PAGE, image, 120, 54, "over the highest point");
        assertColor(PAGE, image, 80, 106, "under the lowest point");
    }

    @Test
    void theStencilIsLeftCleanForTheNextFill() {
        byte[] image = render(c -> {
            c.fillPath(star(60, 60, 50, FillRule.NON_ZERO), Color.rgb(0xC03020));
            c.fillRect(0, 0, W_PT, H_PT, PAGE); // the star covered over: only its stencil could remain
            c.fillPath(squareWithInner(false, FillRule.NON_ZERO), INK);
        });
        assertColor(PAGE, image, 60, 60, "the hole, where the star's middle counted twice");
        assertColor(INK, image, 30, 30, "the ring");
    }

    @Test
    void aFillInsideALayerUsesTheLayersOwnStencil() {
        byte[] image = render(c -> {
            c.saveLayer(0, 0, W_PT, H_PT, 0.5f);
            c.fillPath(squareWithInner(false, FillRule.NON_ZERO), INK);
            c.restore();
        });
        int[] ring = pixel(image, 30, 30);
        int[] half = blend(INK, PAGE, 0.5f);
        assertNear(half, ring, 2, "the ring at half opacity");
        assertColor(PAGE, image, 60, 60, "the hole");
        assertColor(PAGE, image, 110, 60, "beside the square");
    }

    @Test
    void aBlendDrawnInTwoPassesFillsThroughTheStencilOnce() {
        Color under = Color.rgb(0x40C080);
        Color tint = Color.rgb(0x8080FF);
        byte[] image = render(c -> {
            c.saveLayer(0, 0, W_PT, H_PT, 0.5f);
            c.fillRect(0, 0, W_PT, H_PT, under);
            c.setBlendMode(BlendMode.MULTIPLY);
            c.fillPath(squareWithInner(false, FillRule.NON_ZERO), tint);
            c.fillRect(150, 20, 40, 40, tint); // the same mode on a shape that needs no stencil
            c.setBlendMode(BlendMode.NORMAL);
            c.restore();
        });
        assertNear(pixel(image, 170, 40), pixel(image, 30, 30), 2, "the ring, multiplied as a rectangle is");
        assertNear(pixel(image, 200, 120), pixel(image, 60, 60), 2, "the hole, the group's own colour");
    }

    @Test
    void aTargetWithoutAStencilFillsEachContourOnItsOwn() {
        byte[] image = render(c -> c.fillPath(squareWithInner(false, FillRule.NON_ZERO), INK), false);
        assertColor(INK, image, 30, 30, "the ring");
        assertColor(INK, image, 60, 60, "the inner square, filled as before the stencil");
        assertColor(PAGE, image, 110, 60, "beside the square, which a cover unlimited by a count would paint");
        assertColor(PAGE, image, 10, 10, "outside the square");
    }

    @Test
    void aPathThatNeedsNoStencilKeepsTheOneDraw() {
        Path2D two = new Path2D();
        two.moveTo(20, 20).lineTo(60, 20).lineTo(40, 60).close();
        two.moveTo(120, 20).lineTo(160, 20).lineTo(140, 60).close();
        int[] calls = new int[3];
        render(c -> c.fillRect(20, 20, 30, 30, INK));
        calls[0] = canvas.drawCallsLastFrame();
        render(c -> c.fillPath(two, INK));
        calls[1] = canvas.drawCallsLastFrame();
        render(c -> c.fillPath(squareWithInner(false, FillRule.NON_ZERO), INK));
        calls[2] = canvas.drawCallsLastFrame();
        assertEquals(calls[0], calls[1], "two triangles apart fill in the batch's one draw");
        assertEquals(1, calls[0], "a frame of one shape is one draw");
        assertEquals(3, calls[2], "a hole is the stencil's three draws, with nothing queued before it");
    }

    // ------------------------------------------------------------------ helpers

    private static int[] blend(Color top, Color under, float alpha) {
        return new int[] {
            Math.round((top.r() * alpha + under.r() * (1 - alpha)) * 255),
            Math.round((top.g() * alpha + under.g() * (1 - alpha)) * 255),
            Math.round((top.b() * alpha + under.b() * (1 - alpha)) * 255)};
    }

    private static void assertColor(Color expected, byte[] image, int xPt, int yPt, String what) {
        int[] want = {Math.round(expected.r() * 255), Math.round(expected.g() * 255), Math.round(expected.b() * 255)};
        assertNear(want, pixel(image, xPt, yPt), 1, what);
    }

    private static void assertNear(int[] expected, int[] actual, int tolerance, String what) {
        for (int i = 0; i < 3; i++) {
            if (Math.abs(expected[i] - actual[i]) > tolerance) {
                assertArrayEquals(expected, actual, what);
            }
        }
    }

    /** The pixel at a point given in logical points, its RGB. */
    private static int[] pixel(byte[] image, int xPt, int yPt) {
        int x = (int) (xPt * SCALE);
        int y = (int) (yPt * SCALE);
        int at = (y * WIDTH + x) * 4;
        return new int[] {image[at] & 0xff, image[at + 1] & 0xff, image[at + 2] & 0xff};
    }

    private byte[] render(Consumer<GlCanvas> paint) {
        return render(paint, true);
    }

    /** Draws a frame into a framebuffer of its own, with a stencil buffer or without, read top-down. */
    private byte[] render(Consumer<GlCanvas> paint, boolean stencil) {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int depthStencil = stencil ? Gl.glGenRenderbuffers() : 0;
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
            if (stencil) {
                Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, depthStencil);
                Gl.glRenderbufferStorage(GL33C.GL_RENDERBUFFER, GL33C.GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
                Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, 0);
                Gl.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_STENCIL_ATTACHMENT,
                        GL33C.GL_RENDERBUFFER, depthStencil);
            }
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER));
            Gl.glViewport(0, 0, WIDTH, HEIGHT);
            canvas.beginFrame(WIDTH, HEIGHT, SCALE);
            canvas.clear(PAGE);
            paint.accept(canvas);
            canvas.endFrame();
            return readTopDown();
        } finally {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
            if (depthStencil != 0) {
                Gl.glDeleteRenderbuffers(depthStencil);
            }
        }
    }

    private static byte[] readTopDown() {
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
    }
}

package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.graphics.Path2D;
import limn.graphics.Rect;
import limn.graphics.Stroke;
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
 * Strokes on a real device: every kind of end, corner and dash covers what the stroke's outline
 * covers and nothing else; a translucent stroke is as light where its segments meet as along them,
 * through the {@code Stroke} overload and the width one alike; and an opaque round stroke keeps the
 * frame's one draw. The oracle is {@link Stroke#outline}: a point well inside it is painted, a
 * point well outside shows the page.
 */
class GlStrokeTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 240;
    private static final int H_PT = 160;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.WHITE;
    private static final Color INK = Color.rgb(0x2050C0);
    /** A pixel and a half of the frame, in points, past which the antialiased edge has no say. */
    private static final float MARGIN = 0.75f;

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

    /** A zigzag with a sharp turn, a gentle one and a turn straight back, then a curve. */
    private static Path2D zigzag() {
        return new Path2D().moveTo(30, 120).lineTo(70, 40).lineTo(110, 120).lineTo(150, 60)
                .lineTo(150, 100).lineTo(150, 50).quadTo(190, 10, 210, 120);
    }

    @Test
    void everyKindOfEndCornerAndDashCoversItsOutlineAndNothingElse() {
        Stroke[] strokes = {
            Stroke.of(14),
            Stroke.of(14).withCap(Stroke.Cap.SQUARE).withJoin(Stroke.Join.BEVEL),
            Stroke.of(14).withJoin(Stroke.Join.MITER).withMiterLimit(10),
            Stroke.of(10).withCap(Stroke.Cap.ROUND).withJoin(Stroke.Join.ROUND),
            Stroke.of(8).withDash(18, 9).withDashPhase(5),
            Stroke.of(8).withCap(Stroke.Cap.ROUND).withDash(0, 16),
        };
        Path2D path = zigzag();
        for (Stroke stroke : strokes) {
            Path2D outline = stroke.outline(path);
            byte[] image = render(c -> c.drawPath(path, stroke, INK));
            int[] counts = compare(image, outline, stroke.toString());
            assertTrue(counts[0] > 150 && counts[1] > 150,
                    stroke + ": too few points tested, " + counts[0] + " in, " + counts[1] + " out");
        }
    }

    @Test
    void aTranslucentStrokeIsAsLightWhereItsSegmentsMeetAsAlongThem() {
        Color half = Color.rgba(0x2050C0, 0.45f);
        Path2D path = new Path2D().moveTo(30, 120).lineTo(80, 40).lineTo(130, 120).lineTo(180, 40);
        for (int form = 0; form < 2; form++) {
            boolean width = form == 1;
            byte[] image = render(c -> {
                if (width) {
                    c.drawPath(path, 14f, half);
                } else {
                    c.drawPath(path, Stroke.of(14).withCap(Stroke.Cap.ROUND).withJoin(Stroke.Join.ROUND), half);
                }
            });
            int[] along = pixel(image, 55, 80);
            for (float[] corner : new float[][] {{80, 40}, {130, 120}}) {
                int[] at = pixel(image, corner[0], corner[1]);
                assertNear(along, at, 2, (width ? "the width overload" : "a round Stroke")
                        + ", the joint at " + corner[0] + ", " + corner[1]);
            }
        }
    }

    @Test
    void theEndsAreAsTheCapSays() {
        float[][] beyond = {{203, 80}, {206, 86}}; // past the end along the line, and past its corner
        for (Stroke.Cap cap : Stroke.Cap.values()) {
            byte[] image = render(c -> c.drawLine(40, 80, 200, 80, Stroke.of(14).withCap(cap), INK));
            int[] along = pixel(image, beyond[0][0], beyond[0][1]);
            int[] corner = pixel(image, beyond[1][0], beyond[1][1]);
            assertNear(cap == Stroke.Cap.BUTT ? rgb(PAGE) : rgb(INK), along, 1, cap + " past the end");
            assertNear(cap == Stroke.Cap.SQUARE ? rgb(INK) : rgb(PAGE), corner, 1, cap + " past the end's corner");
        }
    }

    @Test
    void aSharpCornerIsMitredWithinTheLimitAndBevelledPastIt() {
        // Two segments meeting at 33 degrees: the miter's point lies 1/sin(16.7) = 3.5 half widths
        // above the corner, the bevel's edge 0.29 of one.
        Path2D path = new Path2D().moveTo(90, 140).lineTo(120, 40).lineTo(150, 140);
        float tipY = 40 - 7 * 2.4f; // past the bevel's edge, short of the miter's point
        byte[] mitred = render(c -> c.drawPath(path, Stroke.of(14).withMiterLimit(4), INK));
        byte[] cut = render(c -> c.drawPath(path, Stroke.of(14).withMiterLimit(2), INK));
        byte[] bevelled = render(c -> c.drawPath(path, Stroke.of(14).withJoin(Stroke.Join.BEVEL), INK));
        assertNear(rgb(INK), pixel(mitred, 120, tipY), 1, "the miter's point within the limit");
        assertNear(rgb(PAGE), pixel(cut, 120, tipY), 1, "past the limit, the corner bevelled");
        assertNear(rgb(PAGE), pixel(bevelled, 120, tipY), 1, "a bevel");
    }

    @Test
    void aDashedLineLeavesItsGapsEmpty() {
        byte[] image = render(c -> c.drawLine(20, 80, 220, 80, Stroke.of(10).withDash(20, 10), INK));
        for (int k = 0; k < 6; k++) {
            assertNear(rgb(INK), pixel(image, 20 + k * 30 + 10, 80), 1, "dash " + k);
            assertNear(rgb(PAGE), pixel(image, 20 + k * 30 + 25, 80), 1, "gap " + k);
        }
    }

    @Test
    void theWidthIsInTheCurrentCoordinates() {
        byte[] image = render(c -> {
            c.scale(2, 2);
            c.drawLine(20, 40, 100, 40, Stroke.of(6), INK); // 12 points wide on the frame
        });
        assertNear(rgb(INK), pixel(image, 100, 85), 1, "five points below the scaled line's middle");
        assertNear(rgb(PAGE), pixel(image, 100, 88), 1, "eight points below it");
    }

    @Test
    void anOpaqueRoundStrokeKeepsTheOneDrawAndATranslucentOneTakesTheStencil() {
        Path2D path = zigzag();
        Stroke round = Stroke.of(10).withCap(Stroke.Cap.ROUND).withJoin(Stroke.Join.ROUND);
        render(c -> c.drawPath(path, round, INK));
        assertEquals(1, canvas.drawCallsLastFrame(), "opaque and round: the capsules, in the one draw");
        render(c -> c.drawPath(path, 10f, INK));
        assertEquals(1, canvas.drawCallsLastFrame(), "the width overload, opaque: the same");
        render(c -> c.drawPath(path, round, Color.rgba(0x2050C0, 0.5f)));
        assertEquals(3, canvas.drawCallsLastFrame(), "translucent: the outline through the stencil");
    }

    // ------------------------------------------------------------------ helpers

    /** Asserts the image against the outline; returns how many points were inside and outside. */
    private static int[] compare(byte[] image, Path2D outline, String what) {
        Rect box = outline.bounds();
        int inside = 0;
        int outside = 0;
        for (float y = Math.max(1, box.y() - 4); y < Math.min(H_PT - 1, box.y() + box.height() + 4); y += 1.5f) {
            for (float x = Math.max(1, box.x() - 4); x < Math.min(W_PT - 1, box.x() + box.width() + 4); x += 1.5f) {
                Boolean side = clearly(outline, x, y);
                if (side == null) {
                    continue;
                }
                int[] p = pixel(image, x, y);
                if (side) {
                    inside++;
                    assertNear(rgb(INK), p, 2, what + ": inside the outline at " + x + ", " + y);
                } else {
                    outside++;
                    assertNear(rgb(PAGE), p, 2, what + ": outside the outline at " + x + ", " + y);
                }
            }
        }
        return new int[] {inside, outside};
    }

    private static Boolean clearly(Path2D outline, float x, float y) {
        boolean in = outline.contains(x, y);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (outline.contains(x + dx * MARGIN, y + dy * MARGIN) != in) {
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
                assertArrayEquals(expected, actual, what);
            }
        }
    }

    /** The pixel whose centre is nearest a point given in logical points, its RGB. */
    private static int[] pixel(byte[] image, float xPt, float yPt) {
        int x = Math.min(WIDTH - 1, (int) (xPt * SCALE));
        int y = Math.min(HEIGHT - 1, (int) (yPt * SCALE));
        int at = (y * WIDTH + x) * 4;
        return new int[] {image[at] & 0xff, image[at + 1] & 0xff, image[at + 2] & 0xff};
    }

    /** Draws a frame into a framebuffer of its own with a stencil buffer, read top-down. */
    private byte[] render(Consumer<GlCanvas> paint) {
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
            canvas.clear(PAGE);
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

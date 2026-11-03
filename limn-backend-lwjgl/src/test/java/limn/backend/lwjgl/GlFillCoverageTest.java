package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.graphics.FillRule;
import limn.graphics.Path2D;
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

/**
 * A filled path covers what its outline encloses: its antialiased edge is centred on the outline,
 * as an analytic shape's is, so a rectangle, a turned square and a circle drawn as paths cover what
 * the same shapes drawn as shapes cover. A path that takes the stencil, a hole in it, covers at most
 * a quarter of a pixel more than its outline all round. Both covered half a pixel more all round,
 * the fringe running a whole pixel outward from the outline.
 */
class GlFillCoverageTest {

    private static final float SCALE = 2f;
    private static final int WIDTH = 480;
    private static final int HEIGHT = 320;
    private static final Color PAGE = Color.WHITE;
    private static final Color INK = Color.BLACK;

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

    @Test
    void aRectangleAsAPathCoversWhatTheRectangleCovers() {
        double path = coverage(render(PAGE, c -> c.fillPath(new Path2D().addRect(20.3f, 15.7f, 100.4f, 60.2f), INK)));
        double shape = coverage(render(PAGE, c -> c.fillRect(20.3f, 15.7f, 100.4f, 60.2f, INK)));
        // Half a pixel all round was 641 pixels more on these 24,000; the rectangle drawn as a
        // shape, whose edge is a smoothed ramp, comes out some 17 over.
        assertEquals(100.4 * 60.2 * SCALE * SCALE, path, 3, "the rectangle's own area");
        assertEquals(shape, path, 20, "path " + path + ", shape " + shape);
    }

    @Test
    void aTurnedSquareAsAPathCoversWhatTheTurnedSquareCovers() {
        Affine2D turn = Affine2D.rotation(Math.toRadians(23), 120, 80);
        double path = coverage(render(PAGE, c -> c.fillPath(new Path2D().addRect(80, 40, 80, 80).transformed(turn), INK)));
        double shape = coverage(render(PAGE, c -> {
            c.rotate((float) Math.toRadians(23), 120, 80);
            c.fillRect(80, 40, 80, 80, INK);
        }));
        assertEquals(shape, path, 15, "path " + path + ", shape " + shape);
    }

    @Test
    void aCircleAsAPathCoversWhatTheCircleCovers() {
        double path = coverage(render(PAGE, c -> c.fillPath(new Path2D().addCircle(120, 80, 50.3f), INK)));
        double shape = coverage(render(PAGE, c -> c.fillCircle(120, 80, 50.3f, INK)));
        assertEquals(shape, path, shape * 0.002, "path " + path + ", shape " + shape);
    }

    @Test
    void aPathThroughTheStencilCoversAtMostAQuarterPixelMoreAllRound() {
        // A square with a square hole turning the other way: the stencil's route.
        Path2D frame = new Path2D().moveTo(40, 30).lineTo(200, 30).lineTo(200, 130).lineTo(40, 130).close()
                .moveTo(80, 60).lineTo(80, 100).lineTo(160, 100).lineTo(160, 60).close();
        double covered = coverage(render(PAGE, c -> c.fillPath(frame, INK)));
        double area = (160 * 100 - 80 * 40) * SCALE * SCALE;
        double perimeter = (2 * (160 + 100) + 2 * (80 + 40)) * SCALE;
        double excess = covered - area;
        assertTrue(excess >= 0 && excess <= perimeter * 0.25 + 10,
                "covers " + excess + " pixels past the outline along " + perimeter + " of edge");
        // The same frame under the even-odd rule takes the same route and covers the same.
        double evenOdd = coverage(render(PAGE, c -> c.fillPath(frame.copy().setFillRule(FillRule.EVEN_ODD), INK)));
        assertEquals(covered, evenOdd, 2);
    }

    /** Ink on the page, in pixels: each pixel counted by how dark it is. */
    private static double coverage(byte[] image) {
        double sum = 0;
        for (int i = 0; i < image.length; i += 4) {
            sum += (255 - (image[i] & 0xff)) / 255.0;
        }
        return sum;
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

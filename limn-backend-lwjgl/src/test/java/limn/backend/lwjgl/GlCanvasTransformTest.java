package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.math.Affine2D;
import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Vec3;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The OpenGL canvas's shear, concatenation and transform getter on a real device: the matrix it
 * answers is in logical points from the window's corner, the content scale taken out, through a
 * turned layer's picture too, and a perspective layer's own; a shear draws where the matrix says.
 */
class GlCanvasTransformTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 200;
    private static final int H_PT = 160;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.WHITE;
    private static final Color INK = Color.rgb(0x2050C0);
    private static final double EPS = 1e-4;

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

    private static void assertSame(Affine2D expected, Affine2D actual) {
        assertEquals(expected.a(), actual.a(), EPS, "a of " + actual);
        assertEquals(expected.b(), actual.b(), EPS, "b of " + actual);
        assertEquals(expected.c(), actual.c(), EPS, "c of " + actual);
        assertEquals(expected.d(), actual.d(), EPS, "d of " + actual);
        assertEquals(expected.e(), actual.e(), 1e-3, "e of " + actual);
        assertEquals(expected.f(), actual.f(), 1e-3, "f of " + actual);
    }

    @Test
    void theMatrixIsInLogicalPointsFromTheWindowsCorner() {
        Affine2D[] seen = new Affine2D[3];
        render(c -> {
            seen[0] = c.transform();
            c.translate(10, 20);
            c.save();
            c.shear(0.5f, 0);
            c.concat(new Affine2D(1, 0.2, 0, 1, 3, 4));
            c.rotate(0.4f, 5, 5);
            seen[1] = c.transform();
            c.restore();
            seen[2] = c.transform();
        });
        assertSame(Affine2D.IDENTITY, seen[0]);
        assertSame(Affine2D.translation(10, 20).shear(0.5, 0).concat(new Affine2D(1, 0.2, 0, 1, 3, 4))
                .concat(Affine2D.rotation(0.4f, 5, 5)), seen[1]);
        assertSame(Affine2D.translation(10, 20), seen[2]);
    }

    @Test
    void insideATurnedLayerTheMatrixStillReachesTheWindow() {
        Affine2D turn = Affine2D.rotation(Math.toRadians(23), 100, 80);
        Affine2D[] seen = new Affine2D[2];
        int[] layers = new int[1];
        render(c -> {
            c.translate(5, 6);
            c.saveLayer(40, 30, 120, 100, 1f, turn.toMat4());
            layers[0] = c.openLayers();
            c.translate(2, 3);
            seen[0] = c.transform();
            c.restore();
            // Turned 30 degrees about the vertical axis through (100, 80), seen from 400 away.
            Mat4 tilt = Mat4.translation(new Vec3(100, 80, 0)).multiply(Mat4.perspectiveAt(400))
                    .multiply(Mat4.rotation(Quat.fromAxisAngle(new Vec3(0, 1, 0), (float) Math.toRadians(30))))
                    .multiply(Mat4.translation(new Vec3(-100, -80, 0)));
            c.saveLayer(0, 0, 200, 160, 1f, tilt);
            c.translate(2, 3);
            seen[1] = c.transform();
            c.restore();
        });
        assertEquals(1, layers[0], "a turn is drawn through a picture of its own");
        assertSame(Affine2D.translation(5, 6).concat(turn).translate(2, 3), seen[0]);
        // A perspective has no affine place in the window: the layer's own coordinates.
        assertSame(Affine2D.translation(2, 3), seen[1]);
    }

    @Test
    void aShearDrawsWhereTheMatrixSays() {
        // A 20-point square leaned by half its height along x: its bottom edge moves 10 points right.
        byte[] image = render(c -> {
            c.translate(60, 40);
            c.shear(0.5f, 0);
            c.fillRect(0, 0, 20, 60, INK);
        });
        // Near the top the square spans x 60 to 80; near the bottom (y 98) it spans 89 to 109.
        assertInk(image, 62, 42, true);
        assertInk(image, 85, 42, false);
        assertInk(image, 62, 98, false);
        assertInk(image, 100, 98, true);
        byte[] concatenated = render(c -> {
            c.translate(60, 40);
            c.concat(Affine2D.shearing(0.5, 0));
            c.fillRect(0, 0, 20, 60, INK);
        });
        int differing = 0;
        for (int i = 0; i < image.length; i++) {
            if (Math.abs((image[i] & 0xFF) - (concatenated[i] & 0xFF)) > 1) {
                differing++;
            }
        }
        assertEquals(0, differing, "a shear is the concatenation of its matrix");
    }

    private static void assertInk(byte[] image, float xPt, float yPt, boolean ink) {
        int i = ((int) (yPt * SCALE) * WIDTH + (int) (xPt * SCALE)) * 4;
        int red = image[i] & 0xFF;
        assertEquals(ink ? 0x20 : 0xFF, red, 2, (ink ? "ink" : "page") + " expected at " + xPt + "," + yPt);
    }

    private byte[] render(Consumer<GlCanvas> paint) {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
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
        }
    }

    private static byte[] readTopDown() {
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            Gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
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

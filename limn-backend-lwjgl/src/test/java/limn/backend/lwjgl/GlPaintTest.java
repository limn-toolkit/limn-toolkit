package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.graphics.Gradient;
import limn.graphics.Image;
import limn.graphics.ImagePaint;
import limn.graphics.LinearGradient;
import limn.graphics.Path2D;
import limn.graphics.Stroke;
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
 * Gradients of many colours and image paints on a real device, each pixel against the colour the
 * paint names at that point: the stops in order and a hard edge where two share a position; each
 * spread; a radial gradient from its focus; a sweep round its centre; a gradient under a matrix of
 * its own; a picture repeated, mirrored and laid once; and any number of gradients in the frame's
 * one draw.
 */
class GlPaintTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 240;
    private static final int H_PT = 160;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.WHITE;
    private static final Color RED = Color.rgb(0xFF0000);
    private static final Color GREEN = Color.rgb(0x00FF00);
    private static final Color BLUE = Color.rgb(0x0000FF);

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
    void aLinearGradientRunsThroughEveryStopInOrder() {
        Gradient g = Gradient.linear(20, 0, 220, 0, new float[] {0, 0.25f, 1}, RED, GREEN, BLUE);
        byte[] image = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT, g));
        assertColor(RED, pixel(image, 10, 80), "before the start: the first colour held");
        assertColor(RED, pixel(image, 20.25f, 80), "at the start");
        assertColor(mix(RED, GREEN, 0.5f), pixel(image, 45, 80), "halfway to the second stop");
        assertColor(GREEN, pixel(image, 70, 80), "at the second stop");
        assertColor(mix(GREEN, BLUE, 0.5f), pixel(image, 145, 80), "halfway to the last");
        assertColor(BLUE, pixel(image, 230, 80), "past the end: the last held");
    }

    @Test
    void twoStopsAtOnePositionMakeAHardEdge() {
        Gradient g = Gradient.linear(0, 0, 200, 0, new float[] {0, 0.5f, 0.5f, 1}, RED, RED, BLUE, BLUE);
        byte[] image = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT, g));
        assertColor(RED, pixel(image, 99, 80), "a pixel before the edge");
        assertColor(BLUE, pixel(image, 101, 80), "a pixel after");
    }

    @Test
    void repeatAndReflectContinueTheGradientPastItsEnds() {
        Gradient base = Gradient.linear(0, 0, 40, 0, RED, BLUE);
        byte[] repeat = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT, base.withSpread(Gradient.Spread.REPEAT)));
        byte[] reflect = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT, base.withSpread(Gradient.Spread.REFLECT)));
        // At 50, a quarter of the way through the second turn: repeated, a quarter from red; reflected,
        // running back, a quarter from blue.
        assertColor(mix(RED, BLUE, 0.25f), pixel(repeat, 50, 80), "repeated");
        assertColor(mix(RED, BLUE, 0.75f), pixel(reflect, 50, 80), "reflected");
        assertColor(mix(RED, BLUE, 0.25f), pixel(reflect, 90, 80), "reflected, forwards again");
    }

    @Test
    void aRadialGradientRunsFromItsFocusToItsCircle() {
        Gradient g = Gradient.radial(120, 80, 60, RED, BLUE).withFocus(90, 80);
        byte[] image = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT, g));
        assertColor(RED, pixel(image, 90, 80), "at the focus");
        assertColor(mix(RED, BLUE, 0.5f), pixel(image, 75, 80), "halfway from the focus to the circle on its side");
        assertColor(mix(RED, BLUE, 0.5f), pixel(image, 135, 80), "and on the far side");
        assertColor(BLUE, pixel(image, 185, 80), "past the circle");
    }

    @Test
    void aSweepTurnsFromItsStartAngle() {
        Gradient g = Gradient.sweep(120, 80, (float) (Math.PI / 2), new float[] {0, 0.5f, 1}, RED, GREEN, BLUE);
        byte[] image = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT, g));
        // From straight down (y grows down), clockwise: a quarter turn is to the left, half is up.
        assertColor(mix(RED, GREEN, 0.5f), pixel(image, 60, 80.5f), "a quarter turn");
        assertColor(GREEN, pixel(image, 120.5f, 20), "half a turn");
        assertColor(mix(GREEN, BLUE, 0.5f), pixel(image, 180, 79.5f), "three quarters");
    }

    @Test
    void aGradientUnderItsOwnMatrixIsStretchedByIt() {
        // A circle stretched twice as wide: an ellipse of 80 by 40.
        Gradient g = Gradient.radial(0, 0, 40, RED, BLUE)
                .withTransform(Affine2D.translation(120, 80).scale(2, 1));
        byte[] image = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT, g));
        assertColor(mix(RED, BLUE, 0.5f), pixel(image, 160, 80), "half the stretched radius across");
        assertColor(mix(RED, BLUE, 0.5f), pixel(image, 120, 100), "half the radius down");
    }

    @Test
    void twoColoursAsAGradientArePaintedAsTheTwoColourShorthand() {
        byte[] gradient = render(PAGE, c -> c.fillRoundRect(20, 20, 200, 120, 16,
                Gradient.linear(20, 20, 220, 140, RED, Color.rgba(0x0000FF, 0.4f))));
        byte[] shorthand = render(PAGE, c -> c.fillRoundRect(20, 20, 200, 120, 16,
                new LinearGradient(20, 20, 220, 140, RED, Color.rgba(0x0000FF, 0.4f))));
        int worst = 0;
        for (int i = 0; i < gradient.length; i++) {
            worst = Math.max(worst, Math.abs((gradient[i] & 0xff) - (shorthand[i] & 0xff)));
        }
        assertTrue(worst <= 1, "apart by " + worst);
    }

    @Test
    void manyGradientsAndAPathAndAStrokeShareTheFramesOneDraw() {
        render(PAGE, c -> {
            for (int i = 0; i < 10; i++) {
                c.fillRect(10 + i * 20, 10, 15, 15, Gradient.linear(0, 0, 15 + i, 0, RED, GREEN, BLUE));
            }
            c.fillPath(new Path2D().addCircle(60, 100, 30), Gradient.sweep(60, 100, 0, RED, BLUE, RED));
            c.drawPath(new Path2D().moveTo(120, 100).lineTo(200, 120), Stroke.of(6), Gradient.radial(160, 110, 40,
                    RED, BLUE));
        });
        assertEquals(1, canvas.drawCallsLastFrame());
    }

    // ------------------------------------------------------------------ image paints

    /** Four pixels: red, green / blue, white. */
    private static Image quad() {
        byte[] rgba = {
            (byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
            0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255};
        return new Image(2, 2, rgba);
    }

    @Test
    void aPictureIsRepeatedMirroredOrLaidOnce() {
        Affine2D big = Affine2D.translation(20, 20).scale(20, 20); // a picture pixel is 20 points
        ImagePaint base = ImagePaint.of(quad()).withTransform(big).withSampling(limn.graphics.Sampling.PIXELATED);
        byte[] repeat = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT, base));
        byte[] mirror = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT,
                base.withTile(ImagePaint.Tile.MIRROR, ImagePaint.Tile.MIRROR)));
        byte[] once = render(PAGE, c -> c.fillRect(0, 0, W_PT, H_PT,
                base.withTile(ImagePaint.Tile.NONE, ImagePaint.Tile.NONE)));
        assertColor(RED, pixel(repeat, 30, 30), "the picture's first pixel");
        assertColor(GREEN, pixel(repeat, 50, 30), "its second");
        assertColor(RED, pixel(repeat, 70, 30), "repeated: the first again");
        assertColor(GREEN, pixel(mirror, 70, 30), "mirrored: the second, met again at the edge");
        assertColor(RED, pixel(mirror, 90, 30), "then the first");
        assertColor(Color.WHITE, pixel(once, 50, 50), "laid once: its last pixel");
        assertColor(PAGE, pixel(once, 70, 30), "and the page past it");
        assertColor(BLUE, pixel(repeat, 30, 10), "above the picture, its last row repeated");
    }

    // ------------------------------------------------------------------ oracle

    private static Color mix(Color a, Color b, float t) {
        return new Color(a.r() + (b.r() - a.r()) * t, a.g() + (b.g() - a.g()) * t, a.b() + (b.b() - a.b()) * t, 1);
    }

    private static void assertColor(Color expected, int[] actual, String what) {
        int[] e = {Math.round(expected.r() * 255), Math.round(expected.g() * 255), Math.round(expected.b() * 255)};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(e[i] - actual[i]) > 4) {
                fail(what + ": expected " + e[0] + "," + e[1] + "," + e[2] + ", was " + actual[0] + "," + actual[1]
                        + "," + actual[2]);
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

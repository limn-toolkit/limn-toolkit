package limn.backend.lwjgl;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.BackdropEffect;
import limn.graphics.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backdrop effects against a real device: what each variant does to pixels that are already in the
 * framebuffer. Every case draws the same two-band backdrop first, so the only thing that varies
 * between them is the effect.
 *
 * <p>The identity case is the load-bearing one. A clear pane with no rim and no tint must leave the
 * framebuffer exactly as it found it: that single assertion covers the copy landing at the right
 * coordinates, the shader finding itself in it, the un-premultiply, and the blend.
 */
class GlBackdropTest {

    private static final int SIZE = 64;
    private static final Color TOP = new Color(0.8f, 0.2f, 0.1f, 1f);
    private static final Color BOTTOM = new Color(0.1f, 0.3f, 0.9f, 1f);
    /** Where the two bands meet, top-down. Off-centre so the panel's flat middle is one colour. */
    private static final int BAND = 48;
    /** A white line inside the panel's top rim: what the refraction test watches move. */
    private static final int STRIPE = 12;

    private GlCanvas canvas;
    private FontStore fonts;
    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
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
        if (runtime != null) {
            Ui.uninstall(runtime);
            workers.shutdownNow();
            runtime = null;
        }
    }

    @Test
    void aClearPaneWithNoRimAndNoTintIsIdentity() {
        byte[] plain = render(null);
        byte[] glazed = render(new BackdropEffect.Clear(Color.TRANSPARENT, 0f, 0f));

        for (int i = 0; i < plain.length; i++) {
            assertEquals(plain[i] & 0xFF, glazed[i] & 0xFF, 1,
                    "byte " + i + ": a pane that displaces nothing and tints nothing must leave "
                            + "the framebuffer as it found it");
        }
    }

    @Test
    void washTakesTheSaturationOutWithoutMovingAnything() {
        byte[] washed = render(new BackdropEffect.Wash(Color.TRANSPARENT, 0f));

        // Well inside the panel, in the top band: grey, at the luminance of the colour that was
        // there. Grey is the assertion: a wash that sampled the wrong place would still be grey,
        // but it would be the wrong grey.
        int at = index(SIZE / 2, 30);
        int r = washed[at] & 0xFF;
        int g = washed[at + 1] & 0xFF;
        int b = washed[at + 2] & 0xFF;
        assertEquals(r, g, 1, "saturation 0 leaves no colour");
        assertEquals(g, b, 1, "saturation 0 leaves no colour");
        assertEquals(Math.round(luminance(TOP) * 255), r, 2, "the grey of the band it covers");
    }

    @Test
    void washLeavesWhatItDoesNotCover() {
        byte[] washed = render(new BackdropEffect.Wash(Color.TRANSPARENT, 0f));

        // Outside the panel (the inset is 8pt): still the original colour, not grey.
        int at = index(2, 2);
        assertEquals(Math.round(TOP.r() * 255), washed[at] & 0xFF, 2);
        assertNotEquals(washed[at] & 0xFF, washed[at + 2] & 0xFF, "outside the panel keeps its hue");
    }

    @Test
    void pixelateCollapsesEachCellToOneColour() {
        byte[] blocked = render(new BackdropEffect.Pixelate(Color.TRANSPARENT, 24f));

        // The cell grid is anchored to the framebuffer, and a 24pt cell puts the band boundary
        // inside a cell rather than on its edge. Both points then carry that cell's single sample,
        // which is what makes the variant a redaction rather than a blur.
        int above = index(SIZE / 2, BAND - 1);
        int below = index(SIZE / 2, BAND + 1);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(blocked[above + channel] & 0xFF, blocked[below + channel] & 0xFF, 1,
                    "channel " + channel + " must be constant across a cell");
        }
        assertNotEquals(Math.round(TOP.r() * 255), blocked[above] & 0xFF,
                "the pixel above the boundary must have lost its own colour to the cell");
    }

    @Test
    void aRimBendsTheBandBoundary() {
        byte[] straight = render(null);
        byte[] bent = render(new BackdropEffect.Clear(Color.TRANSPARENT, 14f, 0f));

        // Down a column crossing the panel's top rim, where the outward normal is vertical and the
        // white stripe gives the displacement something to move.
        int changed = 0;
        for (int y = 9; y < 22; y++) {
            int i = index(SIZE / 2, y);
            if (Math.abs((straight[i] & 0xFF) - (bent[i] & 0xFF)) > 8) {
                changed++;
            }
        }
        assertTrue(changed > 0, "the rim must displace the backdrop it looks through");
    }

    @Test
    void theMiddleOfAPaneIsNotDisplaced() {
        byte[] straight = render(null);
        byte[] bent = render(new BackdropEffect.Clear(Color.TRANSPARENT, 14f, 0f));

        // Refraction is a rim effect: past the rim width the pane is flat, and content seen
        // through the middle must be where it always was.
        int at = index(SIZE / 2, 30);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(straight[at + channel] & 0xFF, bent[at + channel] & 0xFF, 2,
                    "channel " + channel + " in the middle of the pane");
        }
    }

    private static float luminance(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }

    private static int index(int x, int y) {
        return (y * SIZE + x) * 4;
    }

    /** Two colour bands, then {@code effect} over all but an 8pt margin; rows top-down. */
    private byte[] render(BackdropEffect effect) {
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, SIZE, SIZE, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE,
                    GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), "render target");
            GL33C.glViewport(0, 0, SIZE, SIZE);

            // Content scale 1: a point is a pixel, so a rim width in points is a rim width here.
            canvas.beginFrame(SIZE, SIZE, 1f);
            canvas.fillRect(0, 0, SIZE, BAND, TOP);
            canvas.fillRect(0, BAND, SIZE, SIZE - BAND, BOTTOM);
            // A thin line inside where the panel's rim will fall. Without it the backdrop is
            // uniform along both rims, and a refraction test would pass on a shader that
            // displaced nothing.
            canvas.fillRect(0, STRIPE, SIZE, 2, Color.WHITE);
            if (effect != null) {
                canvas.fillBackdropRoundRect(8, 8, SIZE - 16f, SIZE - 16f, 6, effect);
            }
            canvas.endFrame();
            return readTopDown(SIZE, SIZE);
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
    }

    private static byte[] readTopDown(int width, int height) {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            GL33C.glReadPixels(0, 0, width, height, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[width * height * 4];
            int stride = width * 4;
            for (int row = 0; row < height; row++) {
                pixels.position((height - 1 - row) * stride);
                pixels.get(out, row * stride, stride);
            }
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}

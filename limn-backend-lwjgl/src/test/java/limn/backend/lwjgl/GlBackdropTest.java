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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aTubeWithNoCurveAndNoScanIsIdentity() {
        // The same anchor the Clear test leads with, and it earns its place twice over here: Crt
        // both displaces and darkens, so this one line covers the copy coordinates, the shader's
        // self-location, the un-premultiply, the blend, AND that both parameters are genuinely
        // off at zero rather than merely small.
        byte[] plain = render(null);
        byte[] tube = render(new BackdropEffect.Crt(Color.TRANSPARENT, 0f, 0f));

        for (int i = 0; i < plain.length; i++) {
            assertEquals(plain[i] & 0xFF, tube[i] & 0xFF, 1,
                    "byte " + i + ": a tube that neither bends nor scans must leave the "
                            + "framebuffer as it found it");
        }
    }

    @Test
    void theScanSwingsBetweenItsLinesDownAColumn() {
        byte[] scanned = render(new BackdropEffect.Crt(Color.TRANSPARENT, 1f, 0f));

        // Down ONE column in the flat top band, well inside the panel, where the backdrop is a
        // single colour: the grille is constant along a column, so every difference between
        // neighbouring rows here is the scan and nothing else.
        int darkest = 255;
        int brightest = 0;
        for (int y = 20; y < 20 + Math.round(BackdropEffect.Crt.SCAN_PITCH) * 3; y++) {
            int value = scanned[index(SIZE / 2, y)] & 0xFF;
            darkest = Math.min(darkest, value);
            brightest = Math.max(brightest, value);
        }
        assertTrue(brightest - darkest > 30,
                "a scan at full depth must swing between its lines (got " + darkest + ".."
                        + brightest + ")");
    }

    @Test
    void theApertureGrilleRotatesTheDominantChannelAcrossATriad() {
        byte[] masked = render(new BackdropEffect.Crt(Color.TRANSPARENT, 1f, 0f));

        // Across ONE row, the grille puts each channel's column a third of a triad apart. This is
        // the assertion that separates a tube from horizontal stripes: scan lines alone would
        // leave every column of a row identical, and this walks a full triad and requires the
        // brightest channel to move R -> G -> B with it.
        int pitch = Math.round(BackdropEffect.Crt.SCAN_PITCH);
        int y = 30;
        java.util.List<Integer> dominant = new java.util.ArrayList<>();
        for (int step = 0; step < 3; step++) {
            int at = index(SIZE / 2 + step, y);
            int r = masked[at] & 0xFF;
            int g = masked[at + 1] & 0xFF;
            int b = masked[at + 2] & 0xFF;
            // Against the flat backdrop's own channel ratios, so "dominant" means lifted BY the
            // grille rather than whichever channel the colour behind it already had most of.
            float lr = r / Math.max(TOP.r(), 1e-3f);
            float lg = g / Math.max(TOP.g(), 1e-3f);
            float lb = b / Math.max(TOP.b(), 1e-3f);
            dominant.add(lr >= lg && lr >= lb ? 0 : (lg >= lb ? 1 : 2));
        }
        assertEquals(3, new java.util.HashSet<>(dominant).size(),
                "each column of a triad must favour a different channel, got " + dominant);
    }

    @Test
    void theCentreOfATubeIsNotDisplaced() {
        // Curvature grows with the SQUARE of the distance from the centre, so the middle is the
        // one place that must not move however hard the face bulges. A shader that bent linearly
        // would pass every other test here and fail this one.
        byte[] straight = render(null);
        byte[] bent = render(new BackdropEffect.Crt(Color.TRANSPARENT, 0f, 1f));

        int centre = index(SIZE / 2, SIZE / 2);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(straight[centre + channel] & 0xFF, bent[centre + channel] & 0xFF, 2,
                    "channel " + channel + " at the centre of the tube");
        }
    }

    @Test
    void curvatureMovesTheBandBoundaryAwayFromTheCentre() {
        byte[] straight = render(null);
        byte[] bent = render(new BackdropEffect.Crt(Color.TRANSPARENT, 0f, 0.6f));

        // Down a column below the centre, where the bulge pushes the sample outward and the band
        // boundary therefore arrives at a different row than it does flat.
        int changed = 0;
        for (int y = BAND - 8; y < BAND + 8; y++) {
            int i = index(SIZE / 2, y);
            if (Math.abs((straight[i] & 0xFF) - (bent[i] & 0xFF)) > 8) {
                changed++;
            }
        }
        assertTrue(changed > 0, "a bulging face must move what it looks through");
    }

    @Test
    void aBlurOfNoRadiusIsIdentity() {
        // Every tap lands on the same texel and the weights sum to one, so a zero radius has
        // to be exact rather than nearly exact: this is what lets a caller animate a frost in
        // from nothing without the first frame jumping.
        byte[] plain = render(null);
        byte[] blurred = render(new BackdropEffect.Blur(Color.TRANSPARENT, 0f,
                BackdropEffect.Blur.Axis.X));

        for (int i = 0; i < plain.length; i++) {
            assertEquals(plain[i] & 0xFF, blurred[i] & 0xFF, 1, "byte " + i);
        }
    }

    @Test
    void aHorizontalBlurLeavesAHorizontalBandBoundaryAlone() {
        // The separability assertion, and the one that catches a swapped axis: the backdrop's
        // bands run across, so smearing ACROSS them changes nothing, while smearing DOWN must
        // soften the boundary. A pass that ignored the axis would fail one of the two.
        byte[] straight = render(null);
        byte[] across = render(new BackdropEffect.Blur(Color.TRANSPARENT, 10f,
                BackdropEffect.Blur.Axis.X));
        byte[] down = render(new BackdropEffect.Blur(Color.TRANSPARENT, 10f,
                BackdropEffect.Blur.Axis.Y));

        int at = index(SIZE / 2, BAND - 2);
        assertEquals(straight[at] & 0xFF, across[at] & 0xFF, 2,
                "a horizontal blur cannot move a horizontal edge");
        assertNotEquals(straight[at] & 0xFF, down[at] & 0xFF,
                "a vertical blur must soften it");
    }

    @Test
    void twoCrossedPassesSoftenBothWays() {
        // The stack, which is the whole point of the variant being one axis at a time. The
        // white stripe is the finest thing on the plate, so it is what a real blur has to dim.
        byte[] straight = render(null);
        byte[] frosted = renderStack(
                new BackdropEffect.Blur(Color.TRANSPARENT, 9f, BackdropEffect.Blur.Axis.X),
                new BackdropEffect.Blur(Color.TRANSPARENT, 9f, BackdropEffect.Blur.Axis.Y));

        int onStripe = index(SIZE / 2, STRIPE);
        assertTrue((straight[onStripe] & 0xFF) - (frosted[onStripe] & 0xFF) > 20,
                "the stripe must lose its peak to the blur (was "
                        + (straight[onStripe] & 0xFF) + ", now " + (frosted[onStripe] & 0xFF) + ")");
    }

    @Test
    void aBulgeNeverReadsOutsideItsOwnShape() {
        // The regression this exists for: the bulge used to displace toward the copied region,
        // which is the shape PLUS a margin, so a strong curvature imported whatever the frame
        // had drawn next door and the screen showed that content repeated inside itself.
        //
        // The white stripe sits at y=STRIPE, ABOVE the panel's 8pt top edge, so it is outside
        // the shape and inside the copy. At full curvature every pixel of the panel must still
        // be one of the two band colours: if any of them is near-white, the bulge reached out.
        byte[] bent = renderStack(new BackdropEffect.Crt(Color.TRANSPARENT, 0f, 1f));

        int white = Math.round(0.9f * 255);
        for (int y = 12; y < SIZE - 12; y++) {
            for (int x = 12; x < SIZE - 12; x++) {
                int at = index(x, y);
                boolean allBright = (bent[at] & 0xFF) > white
                        && (bent[at + 1] & 0xFF) > white
                        && (bent[at + 2] & 0xFF) > white;
                assertFalse(allBright, "the stripe above the panel leaked into it at "
                        + x + "," + y);
            }
        }
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

    /** The plate with one effect over it. */
    private byte[] render(BackdropEffect effect) {
        return effect == null ? renderStack() : renderStack(effect);
    }

    /**
     * The plate with several effects drawn over it in order, which is how they compose: each
     * one reads the framebuffer the one before it wrote.
     */
    private byte[] renderStack(BackdropEffect... effects) {
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
            for (BackdropEffect effect : effects) {
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

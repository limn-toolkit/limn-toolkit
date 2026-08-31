package limn.backend.lwjgl;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The paint seam, on a real device.
 *
 * <p><b>What this class used to prove and no longer can.</b> There were two glyph-emitting loops in
 * {@code GlCanvas} — one carrying a pen and kerning as it walked code points, one reading absolute
 * positions a shaper had decided — and this suite held them to the same pixels. There is one loop
 * now: {@code drawText(String, …)} shapes through the installed ruler and calls the shaped
 * overload. Comparing the two overloads byte for byte would therefore pass by construction, and a
 * test that cannot fail is worse than no test, so the comparisons here were re-aimed at the seams
 * that <em>can</em> still disagree.
 *
 * <p>Two of them remain, and both are inside the shaped path. A cluster the value reports as
 * {@link ShapedText#NO_GLYPH} is drawn from its own characters instead of from a glyph id, which is
 * how a colour-emoji strike works and what a whole line becomes when the native cannot load; that
 * fallback and the glyph route must land ink in the same places for text where both are legal. And
 * the {@code String} overload must actually go through the registry's ruler rather than doing
 * anything of its own, which is testable by changing what is installed and watching the pixels
 * follow.
 */
class GlShapedTextPaintTest {

    private static final int SIZE = 96;
    /** No f-ligature anywhere in it, so both paths must choose the same glyphs. */
    private static final String TEXT = "Waltz, bad";
    private static final Font FONT = Font.of(16);
    private static final float BASELINE = 40;
    private static final float ORIGIN_X = 4;

    private GlCanvas canvas;
    private FontStore fonts;
    private ShapingRuler ruler;
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
        ruler = new ShapingRuler(fonts);
        // The canvas shapes through whatever is in the registry, which is what a backend installs
        // at startup. Installing it here is not scaffolding around an awkward design: it is the
        // arrangement under test, because the point of the registry seam is that the ruler a widget
        // laid out through is the ruler the canvas paints through.
        limn.graphics.TextRulers.install(ruler);
    }

    @AfterEach
    void closeContext() {
        limn.graphics.TextRulers.uninstall(ruler);
        ruler = null;
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

    /**
     * The {@code String} overload goes through the installed ruler, and the proof is that swapping
     * the ruler swaps the pixels.
     *
     * <p>The fixture is {@code "office"} rather than the Latin the rest of this class uses, because
     * this needs a string the two rulers <em>disagree</em> about: the shaping ruler folds
     * {@code ffi} into one glyph and a ruler with no shaper draws three. The first assertion is
     * that the string overload paints exactly what the installed ruler's own shaping paints; the
     * second is that installing a different ruler moves the ink. Together they say the canvas asks
     * the registry rather than deciding for itself &mdash; which is the thing that puts a joined
     * Arabic caption on a button, and which cannot be seen at all on a string both rulers agree
     * about.
     */
    @Test
    void theStringOverloadPaintsWhatTheInstalledRulerShaped() {
        String text = "office";
        ShapedText shaped = ruler.shape(text, FONT);
        assertTrue(shaped.glyphCount() < text.length(),
                "the fixture must be a string the two rulers disagree about");

        byte[] fromString = render(c -> c.drawText(text, ORIGIN_X, BASELINE, FONT, Color.WHITE));
        byte[] fromShaped = render(c -> c.drawText(shaped, ORIGIN_X, BASELINE, Color.WHITE));
        assertTrue(inkedPixels(fromString) > 200,
                "the fixture has to actually draw something, or this test proves nothing");
        assertArrayEquals(fromString, fromShaped,
                "the string overload must paint the installed ruler's shaping of the string");

        ShapingRuler degraded = new ShapingRuler(fonts, face -> null);
        limn.graphics.TextRulers.install(degraded);
        try {
            byte[] fromOtherRuler =
                    render(c -> c.drawText(text, ORIGIN_X, BASELINE, FONT, Color.WHITE));
            assertFalse(java.util.Arrays.equals(fromString, fromOtherRuler),
                    "the string overload ignored the registry: the same pixels came out of a "
                            + "ruler that cannot form the ligature");
        } finally {
            limn.graphics.TextRulers.install(ruler);
        }
    }

    @Test
    void theDegradedValuePaintsWhatTheShapedValuePaints() {
        // The other half of ADR 031 decision 4, and the half a width comparison cannot reach: when
        // the native is absent every cluster is NO_GLYPH and the painter falls back to the
        // per-code-point route inside the shaped overload. Latin has to keep working EXACTLY as
        // before — which is a claim about pixels, not about a total. The fixture makes the two
        // legitimately comparable: no ligature falls in it, so the shaper picks the glyphs the cmap
        // picks and any difference in the output is a difference in placement.
        ShapingRuler degraded = new ShapingRuler(fonts, face -> null);
        ShapedText line = degraded.shape(TEXT, FONT);
        for (int g = 0; g < line.glyphCount(); g++) {
            assertTrue(line.glyphId(g) == ShapedText.NO_GLYPH, "the fixture must be degraded");
        }
        ShapedText shaped = ruler.shape(TEXT, FONT);

        byte[] fromShaped = render(c -> c.drawText(shaped, ORIGIN_X, BASELINE, Color.WHITE));
        byte[] fromDegraded = render(c -> c.drawText(line, ORIGIN_X, BASELINE, Color.WHITE));

        assertTrue(inkedPixels(fromShaped) > 200,
                "the fixture has to actually draw something, or this test proves nothing");
        assertArrayEquals(fromShaped, fromDegraded,
                "a missing native narrows what can be drawn; it does not move the Latin already "
                        + "on the screen");
    }

    @Test
    void aDegradedValueWithAFormatCharacterInItPaintsWhereTheShapedOneDoes() {
        // The pixel half of a claim a width comparison states and cannot show. The painter skips a
        // variation selector — it has no ink and no advance — so a degraded value that charged it
        // the .notdef box it gets from the cmap would reserve space nothing fills: a hole after
        // the 'a', and every following cluster pushed 0.44 em to the right of where the shaped
        // value puts it. The fixture above cannot see this: "Waltz, bad" has no format character
        // in it, and neither does any other Latin fixture in this suite.
        String text = "a\uFE0Fb";
        ShapingRuler degraded = new ShapingRuler(fonts, face -> null);
        ShapedText line = degraded.shape(text, FONT);
        ShapedText shaped = ruler.shape(text, FONT);

        byte[] fromShaped = render(c -> c.drawText(shaped, ORIGIN_X, BASELINE, Color.WHITE));
        byte[] fromDegraded = render(c -> c.drawText(line, ORIGIN_X, BASELINE, Color.WHITE));

        assertTrue(inkedPixels(fromShaped) > 20,
                "the fixture has to actually draw two letters, or this test proves nothing");
        assertArrayEquals(fromShaped, fromDegraded,
                "a character nothing draws must cost nothing on the path that measures it either");
    }

    @Test
    void aRunWhoseFaceTheCanvasNoLongerKnowsStillDrawsItsCharacters() {
        // What a value shaped before an eviction becomes. The face id in the run names nothing
        // this store can resolve, and the honest answer is the text it was shaped from, drawn by
        // the slower route — never the wrong glyphs from whichever face inherited the id.
        ShapedText real = ruler.shape(TEXT, FONT);
        ShapedText stale = restamped(real, Integer.MAX_VALUE);

        byte[] fromString = render(c -> c.drawText(TEXT, ORIGIN_X, BASELINE, FONT, Color.WHITE));
        byte[] fromStale = render(c -> c.drawText(stale, ORIGIN_X, BASELINE, Color.WHITE));

        assertTrue(inkedPixels(fromStale) > 200, "an unknown face draws characters, not nothing");
        assertArrayEquals(fromString, fromStale,
                "the right characters by the slower route");
    }

    /** The same line with every run's face id replaced: what an eviction leaves behind. */
    private ShapedText restamped(ShapedText line, int faceId) {
        ShapedText.Builder builder = ShapedText.builder(line.text(), line.font(),
                        line.baseDirection(), line.glyphCount())
                .lineMetrics(line.metrics().ascent(), line.metrics().descent(),
                        line.metrics().lineHeight())
                .epoch(line.epoch());
        for (ShapedText.Run run : line.runs()) {
            builder.run(faceId, run.charStart(), run.charEnd(), run.level());
            for (int g = run.glyphStart(); g < run.glyphEnd(); g++) {
                // The glyph ids are kept deliberately: the point is that a painter which cannot
                // resolve the FACE must not use them, however valid they look.
                builder.glyph(line.glyphId(g), line.glyphCluster(g), line.glyphAdvance(g), 0, 0);
            }
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ harness

    private interface Frame {
        void paint(GlCanvas canvas);
    }

    /** One frame into an offscreen texture, read back top-down, as the backdrop tests do. */
    private byte[] render(Frame frame) {
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
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER,
                    GL33C.GL_NEAREST);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER,
                    GL33C.GL_NEAREST);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            GL33C.glViewport(0, 0, SIZE, SIZE);

            // Content scale 1, so a logical point is a device pixel and the atlas size is the
            // font size: the comparison is about placement, not about resampling.
            canvas.beginFrame(SIZE, SIZE, 1f);
            canvas.fillRect(0, 0, SIZE, SIZE, Color.BLACK);
            frame.paint(canvas);
            canvas.endFrame();
            return readTopDown();
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
    }

    private static byte[] readTopDown() {
        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            GL33C.glReadPixels(0, 0, SIZE, SIZE, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[SIZE * SIZE * 4];
            int stride = SIZE * 4;
            for (int row = 0; row < SIZE; row++) {
                pixels.position((SIZE - 1 - row) * stride);
                pixels.get(out, row * stride, stride);
            }
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** How many pixels carry ink, so a comparison of two blank frames cannot pass for agreement. */
    private static int inkedPixels(byte[] frame) {
        int inked = 0;
        for (int i = 0; i < frame.length; i += 4) {
            if ((frame[i] & 0xFF) > 16) {
                inked++;
            }
        }
        return inked;
    }
}

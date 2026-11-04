package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextDecorations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run shaped with its paragraph as context, which is what a paragraph of several fonts is
 * shaped from, and the decorations a face declares.
 */
class ShapeRunInContextTest {

    private static final Font FONT = Font.of(16);

    /** "Arabic" in Arabic: seven letters that join, so a cut in the middle shows. */
    private static final String WORD = "العربية";

    private ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new limn.concurrent.UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        limn.concurrent.Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        limn.concurrent.Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    private static List<Integer> glyphs(ShapedText text) {
        List<Integer> ids = new ArrayList<>();
        for (int g = 0; g < text.glyphCount(); g++) {
            ids.add(text.glyphId(g));
        }
        return ids;
    }

    @Test
    void halvesOfAWordShapedInContextKeepTheWordsJoiningForms() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        Assumptions.assumeFalse(loaded.scripts().isEmpty(), "the script faces are not bundled here");
        try (FontStore store = new FontStore()) {
            store.installHeavyFallbacks(loaded);
            ShapingRuler ruler = new ShapingRuler(store);
            ShapedText whole = ruler.shape(WORD, FONT, ShapedText.Direction.RTL);
            int cut = 3;
            ShapedText first = ruler.shapeRun(WORD, 0, cut, FONT, ShapedText.Direction.RTL);
            ShapedText second = ruler.shapeRun(WORD, cut, WORD.length(), FONT, ShapedText.Direction.RTL);
            ShapedText alone = ruler.shape(WORD.substring(0, cut), FONT, ShapedText.Direction.RTL);

            assertEquals(WORD.substring(0, cut), first.text(), "the value answers for the run alone");
            // Right to left, the first run's glyphs are the whole word's last ones on screen.
            List<Integer> expected = glyphs(whole);
            List<Integer> joined = new ArrayList<>(glyphs(second));
            joined.addAll(glyphs(first));
            assertEquals(expected, joined, "two runs in context are the word's own glyphs");
            assertNotEquals(glyphs(alone), glyphs(first),
                    "shaped alone, the half takes the forms of a word that ends there");
            assertEquals(whole.metrics().width(),
                    first.metrics().width() + second.metrics().width(), 0.01f);
        }
    }

    @Test
    void aRunsIndicesAreItsOwn() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            String paragraph = "Total: 1234 due";
            ShapedText run = ruler.shapeRun(paragraph, 7, 11, FONT, ShapedText.Direction.LTR);
            assertEquals("1234", run.text());
            assertEquals(0, run.glyphCluster(0));
            assertEquals(4, run.caretCount() - 1, "five caret stops over four digits");
            assertEquals(ruler.shape("1234", FONT).metrics().width(), run.metrics().width(), 0.01f);
        }
    }

    @Test
    void theDecorationsAreTheFacesOwn() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            TextDecorations roboto = ruler.decorations(Font.of(20.48f)); // 100 units of 2048 = 1 point
            // Roboto's post and OS/2, read by the probe of ADR 083: -150/100, 512/102, 977/1331, 287/1331.
            assertEquals(1.50f, roboto.underlineOffset(), 0.01f);
            assertEquals(1.00f, roboto.underlineThickness(), 0.01f);
            assertEquals(5.12f, roboto.strikeoutOffset(), 0.01f);
            assertEquals(1.02f, roboto.strikeoutThickness(), 0.01f);
            assertEquals(9.77f, roboto.superscriptOffset(), 0.01f);
            assertEquals(13.31f, roboto.superscriptSize(), 0.01f);
            assertEquals(2.87f, roboto.subscriptOffset(), 0.01f);
            assertTrue(ruler.decorations(Font.of(40.96f)).underlineThickness() > 1.99f, "scales with the size");
        }
    }
}

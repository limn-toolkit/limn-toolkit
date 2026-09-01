package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the vendored script faces actually draw the digits the numbering-system work promises:
 * Arabic-Indic (U+0660–0669), extended Arabic-Indic (U+06F0–06F9) and Devanagari (U+0966–096F).
 * Localizing a digit the pipeline then renders as {@code .notdef} would be strictly worse than
 * the Latin digit it replaced, so the coverage is asserted before any widget is allowed to
 * produce one.
 *
 * <p>Also pinned here: a run of Arabic-Indic digits keeps its left-to-right order under a
 * right-to-left base, exactly as the European digits in {@link NumericRunOrderTest} do. The bidi
 * class differs (arabic number rather than European number) but the resolved level is even under
 * both bases, and every widget that draws a localized value relies on that.
 *
 * <p>Headless CPU, no GL context and no window, behind the same assumption on the vendored script
 * faces {@link BaseDirectionWidthTest} uses.
 */
class DigitCoverageTest {

    private static final Font FONT = Font.of(16);
    private static final float EPS = 1e-4f;
    /** How many script faces {@code scripts/fetch-fonts.sh} vendors; fewer is an incomplete set. */
    private static final int SCRIPT_FACES = 4;

    private static final String ARABIC_INDIC = "٠١٢٣٤٥٦٧٨٩";
    private static final String EXTENDED_ARABIC_INDIC = "۰۱۲۳۴۵۶۷۸۹";
    private static final String DEVANAGARI = "०१२३४५६७८९";

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

    private static FontStore storeWithScripts() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        boolean present = loaded.scripts().size() == SCRIPT_FACES;
        if (!present) {
            loaded.close();
        }
        Assumptions.assumeTrue(present,
                "the script faces are not bundled on this machine; see scripts/fetch-fonts.sh");
        FontStore store = new FontStore();
        assertTrue(store.installHeavyFallbacks(loaded), "the fallbacks did not fold in");
        return store;
    }

    @Test
    void everyPromisedDigitResolvesToARealGlyph() {
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            for (String digits : new String[]{ARABIC_INDIC, EXTENDED_ARABIC_INDIC, DEVANAGARI}) {
                ShapedText line = ruler.shape(digits, FONT, ShapedText.Direction.LTR);
                assertEquals(10, line.glyphCount(),
                        "ten digits are ten glyphs in " + name(digits));
                for (int g = 0; g < line.glyphCount(); g++) {
                    assertNotEquals(0, line.glyphId(g),
                            "digit " + g + " of " + name(digits) + " fell to .notdef");
                }
                assertTrue(line.metrics().width() > 0,
                        name(digits) + " measures as ink, not as boxes");
            }
        }
    }

    @Test
    void arabicIndicDigitsKeepTheirOrderUnderBothBases() {
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            String clock = "٠٧:٣٠";
            ShapedText ltr = ruler.shape(clock, FONT, ShapedText.Direction.LTR);
            ShapedText rtl = ruler.shape(clock, FONT, ShapedText.Direction.RTL);
            for (int i = 0; i < clock.length(); i++) {
                assertEquals(visualX(ltr, i), visualX(rtl, i), EPS,
                        "char " + i + " of a localized clock face moved with the base");
            }
        }
    }

    private static String name(String digits) {
        if (digits.equals(ARABIC_INDIC)) {
            return "Arabic-Indic";
        }
        return digits.equals(EXTENDED_ARABIC_INDIC) ? "extended Arabic-Indic" : "Devanagari";
    }

    /** The x of the glyph the character produced, in the line's own left-to-right space. */
    private static float visualX(ShapedText line, int charIndex) {
        for (int g = 0; g < line.glyphCount(); g++) {
            if (line.glyphCluster(g) == charIndex) {
                return line.glyphX(g);
            }
        }
        throw new AssertionError("no glyph came from char " + charIndex + " of " + line.text());
    }
}

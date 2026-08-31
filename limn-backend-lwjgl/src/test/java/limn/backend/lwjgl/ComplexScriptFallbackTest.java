package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four vendored script faces are reachable, and reachable <em>the way the shaper reaches
 * them</em>.
 *
 * <p>This is the half of the work that a shaping test cannot cover and a font test cannot either.
 * Shaping was demonstrable with Roboto alone; what it produced for Arabic was a correctly ordered,
 * correctly clustered row of {@code .notdef} boxes, because the face resolution underneath it had
 * nothing to answer with. So the assertion here is not "HarfBuzz works" but "the chain hands the
 * shaper a face that covers the run", which is one lookup — {@code faceForCodepoint} — and one
 * fold-in away from being silently false.
 *
 * <p>Everything is behind an assumption on the binaries being present, like every other test that
 * touches an optional vendored face: {@code scripts/fetch-fonts.sh} is optional, and a checkout
 * without it must still build green.
 */
class ComplexScriptFallbackTest {

    private ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    private static final Font FONT = Font.of(16);

    /** One sample per face: a word in the script, and one code point that only that face has. */
    private record Sample(String name, String text, int codepoint) {
    }

    private static final Sample[] SAMPLES = {
            new Sample("Arabic", "العربية", 0x0639),      // ain
            new Sample("Hebrew", "שלום", 0x05E9),         // shin
            new Sample("Devanagari", "हिन्दी", 0x0939),      // ha
            new Sample("Thai", "ภาษาไทย", 0x0E20),        // pho samphao
    };

    @BeforeEach
    void installRuntime() {
        // FontStore entry points are UI-thread confined (enforced): bind the JUnit thread as the
        // UI thread, exactly as the other font tests do.
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

    @Test
    void everyScriptResolvesToAFaceThatActuallyCoversIt() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        Assumptions.assumeTrue(loaded.scripts().size() == SAMPLES.length,
                "the script faces are not bundled on this machine; see scripts/fetch-fonts.sh");
        try (FontStore store = new FontStore()) {
            StbFont roboto = store.resolve(FONT);
            for (Sample sample : SAMPLES) {
                // Before the fold-in there is no answer but the primary, and that is the state a
                // .notdef box comes from — asserted so that the assertion after it is about the
                // faces arriving and not about the chain having always had them.
                assertEquals(roboto, store.faceForCodepoint(roboto, sample.codepoint()),
                        sample.name() + " cannot resolve before the fallbacks land");
            }
            assertTrue(store.installHeavyFallbacks(loaded));

            for (Sample sample : SAMPLES) {
                StbFont face = store.faceForCodepoint(roboto, sample.codepoint());
                assertNotSame(roboto, face, sample.name() + " still falls through to the primary");
                // The whole point, and not implied by the line above: faceForCodepoint answers the
                // primary as its LAST RESORT, so "not the primary" alone would also be satisfied by
                // some other face that happens to be earlier in the chain and equally blind.
                assertTrue(face.hasGlyph(sample.codepoint()),
                        sample.name() + " resolved to a face that cannot draw it: " + face.name());
            }
        }
    }

    @Test
    void theShaperGetsACoveringFaceForEveryRunAndRealGlyphsBack() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        Assumptions.assumeTrue(loaded.scripts().size() == SAMPLES.length,
                "the script faces are not bundled on this machine; see scripts/fetch-fonts.sh");
        try (FontStore store = new FontStore()) {
            assertTrue(store.installHeavyFallbacks(loaded));
            ShapingRuler ruler = new ShapingRuler(store);

            for (Sample sample : SAMPLES) {
                ShapedText line = ruler.shape(sample.text(), FONT);

                assertEquals(1, line.runs().size(),
                        sample.name() + " is one script in one face and must be one run");
                ShapedText.Run run = line.runs().get(0);
                StbFont face = store.faceById(run.faceId());
                assertNotNull(face, sample.name() + " named a face this store no longer knows");
                assertTrue(face.hasGlyph(sample.codepoint()),
                        sample.name() + " was shaped against " + face.name());
                assertTrue(line.glyphCount() > 0);
                for (int g = run.glyphStart(); g < run.glyphEnd(); g++) {
                    assertNotEquals(ShapedText.NO_GLYPH, line.glyphId(g),
                            sample.name() + " degraded instead of shaping");
                    // Index 0 is .notdef: a real glyph id from the wrong face, which is exactly
                    // what a run resolved to Roboto produces and what this whole phase is for.
                    assertNotEquals(0, line.glyphId(g),
                            sample.name() + " shaped into a box at glyph " + g);
                }
                assertTrue(line.metrics().width() > 0, sample.name() + " measured to nothing");
            }
        }
    }

    @Test
    void devanagariReordersAndLigatesWhichNoPerCodePointPipelineCanDo() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        Assumptions.assumeTrue(loaded.scripts().size() == SAMPLES.length,
                "the script faces are not bundled on this machine; see scripts/fetch-fonts.sh");
        try (FontStore store = new FontStore()) {
            assertTrue(store.installHeavyFallbacks(loaded));
            ShapingRuler ruler = new ShapingRuler(store);

            // ADR 031 Finding 3, the decisive row of its table, now against the vendored face
            // rather than against one found on the machine that wrote the ADR. ksha: consonant,
            // virama, consonant — three code points that become ONE glyph.
            ShapedText conjunct = ruler.shape("क्ष", FONT);
            assertEquals(1, conjunct.glyphCount(), "the ksha conjunct is one glyph");

            // And the reordering. "hindī" is six code points and five glyphs, drawn in cluster
            // order 0 0 2 4 4 — the same row the ADR measured, now against the pinned face.
            ShapedText word = ruler.shape("हिन्दी", FONT);
            assertEquals(6, word.text().length());
            assertEquals(5, word.glyphCount());
            assertArrayEquals(new int[]{0, 0, 2, 4, 4}, clustersOf(word));

            // The clusters alone do not show the reordering, because HarfBuzz merges the i-matra
            // into the cluster of the consonant it belongs to, so the first two glyphs both report
            // 0. What shows it is WHICH of the two comes first: the ha is typed first and drawn
            // second, and the glyph ahead of it is the matra, which the shaper also replaced with a
            // contextual variant the cmap has no way to reach. A pipeline that emits one glyph per
            // code point in code-point order cannot produce either half of that line at any level
            // of effort, which is why this is pinned rather than looked at.
            StbFont deva = store.faceForCodepoint(store.resolve(FONT), 0x0939);
            assertEquals(deva.glyphIndex(0x0939), word.glyphId(1),
                    "the ha is typed first and must be drawn SECOND");
            assertNotEquals(deva.glyphIndex(0x093F), word.glyphId(0),
                    "and the matra ahead of it is a contextual form, not the cmap's glyph");
        }
    }

    /** The cluster each glyph carries, in drawn order. */
    private static int[] clustersOf(ShapedText line) {
        int[] clusters = new int[line.glyphCount()];
        for (int g = 0; g < clusters.length; g++) {
            clusters[g] = line.glyphCluster(g);
        }
        return clusters;
    }

    @Test
    void theFourFacesBecomeSelectableFamiliesLikeTheCjkOneDoes() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        Assumptions.assumeTrue(loaded.scripts().size() == SAMPLES.length,
                "the script faces are not bundled on this machine; see scripts/fetch-fonts.sh");
        try (FontStore store = new FontStore()) {
            assertTrue(store.installHeavyFallbacks(loaded));

            for (String family : new String[]{"Noto Sans Arabic", "Noto Sans Hebrew",
                    "Noto Sans Devanagari", "Noto Sans Thai"}) {
                assertTrue(store.families().contains(family), family + " is not selectable");
                // Selectable has to mean it resolves, not merely that it is listed: an application
                // whose UI is Arabic names this family as its primary, and a name the catalog
                // advertises but resolve() answers Roboto for is worse than one it never offered.
                assertEquals(family, store.resolve(new Font(family, 16)).name());
            }
        }
    }
}

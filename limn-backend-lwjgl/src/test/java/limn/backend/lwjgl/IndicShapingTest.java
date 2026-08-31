package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shaping, with bidi held out of the way.
 *
 * <p>Devanagari and Thai are complex <em>and</em> left-to-right, which is exactly why they are
 * pinned together and apart from Arabic and Hebrew: everything asserted below is a property of the
 * shaper and of the font's own GSUB/GPOS tables, with no reordering by level, no visual-order
 * concatenation and no direction boundary anywhere near it. If one of these fails, the shaping
 * stage broke; nothing here can fail because bidi did.
 *
 * <p>Headless CPU work over a font file, like {@code StbFontTest} and {@code ShapingRulerTest}: no
 * GL context, no window, and therefore a permanent test rather than a screenshot somebody looks at
 * once. That is the whole reason ADR 031 could measure these numbers before the work started, and
 * they are re-measured here against the vendored faces so that the ADR's table stops being a claim
 * about a machine and becomes a claim about this repository.
 *
 * <p><b>Two ways a test like this passes while proving nothing, both guarded.</b> It passes when
 * the face is absent, so the only thing allowed to skip is the presence of the vendored binaries
 * &mdash; {@code scripts/fetch-fonts.sh} is optional and a checkout without it must still build
 * green &mdash; and every fixture additionally asserts that the face it resolved to actually covers
 * the script, because a chain that answers Roboto is a chain that draws boxes. And it passes when
 * shaping silently degraded, so every glyph is checked against {@link ShapedText#NO_GLYPH} (the
 * sentinel the per-code-point fallback emits) and against glyph {@code 0} (a real, drawable
 * {@code .notdef} from a face that has no such character). Neither the missing native nor the
 * missing feature can hide behind a green run here.
 */
class IndicShapingTest {

    private ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    private static final Font FONT = Font.of(16);
    private static final float EPS = 1e-3f;

    /** Every face {@code parseHeavyFallbacks} loads for the four complex scripts. */
    private static final int SCRIPT_FACES = 4;

    // -------------------------------------------------------------------- Devanagari fixtures

    /** ka U+0915, virama U+094D, ssa U+0937: the ksha conjunct. */
    private static final String KSHA = "क्ष";

    /** ha U+0939, i-matra U+093F, na U+0928, virama U+094D, da U+0926, ii U+0940: "hindī". */
    private static final String HINDI = "हिन्दी";

    private static final int KA = 0x0915;
    private static final int VIRAMA = 0x094D;
    private static final int SSA = 0x0937;
    private static final int HA = 0x0939;
    private static final int I_MATRA = 0x093F;

    // -------------------------------------------------------------------------- Thai fixtures

    /** po pla U+0E1B, sara i U+0E34 (above), mai ek U+0E48 (tone, above the vowel), no nu U+0E19. */
    private static final String PIN = "ปิ่น";

    /** no nu U+0E19, mai tho U+0E49 (tone), sara am U+0E33 &mdash; one code point, two glyphs. */
    private static final String NAM = "น้ำ";

    /** "kin khao du": nine code points, three words, and not one space between them. */
    private static final String SENTENCE = "กินข้าวดู";

    /** "phasa thai": seven code points that carry no mark at all, so the plain Thai case. */
    private static final String PHASA_THAI = "ภาษาไทย";

    private static final int PO_PLA = 0x0E1B;

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

    // ============================================================================================
    // Devanagari: the two properties a code-point-ordered pipeline cannot produce.
    // ============================================================================================

    /**
     * ADR 031 Finding 3, row two: three code points, one glyph.
     *
     * <p>The count is the assertion, because that single number is the thing no per-code-point
     * pipeline can ever produce at any level of effort &mdash; it emits one glyph per code point by
     * construction, so it can be wrong about <em>which</em> glyphs and never about how many.
     */
    @Test
    void theKshaConjunctIsThreeCodePointsAndExactlyOneGlyph() {
        try (FontStore store = storeWithScriptFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);
            StbFont deva = faceFor(store, HA);

            // Pinned rather than read off the literal: a reader cannot count code points in a
            // rendered conjunct, and if an editor ever normalises this file the input changes
            // silently and the output claim below stops meaning what it says.
            assertEquals(3, KSHA.length(), "ka, virama, ssa");

            ShapedText line = ruler.shape(KSHA, FONT);

            assertEquals(1, line.glyphCount(), "the ksha conjunct is one glyph");
            assertShapedNotDegraded(line, "ksha");
            // And it is a glyph GSUB built, reachable from no cmap lookup: not ka, not ssa, not the
            // virama that joined them. A test that only counted could be satisfied by a shaper that
            // dropped two glyphs on the floor.
            for (int cp : new int[]{KA, VIRAMA, SSA}) {
                assertNotEquals(deva.glyphIndex(cp), line.glyphId(0),
                        () -> "the conjunct is a cmap glyph, so nothing was substituted");
            }
            // One cluster over all three characters, which is the consequence a caret feels: there
            // is nowhere to stand between the ka and the ssa, because they are not two boxes.
            assertEquals(0, line.glyphCluster(0));
            assertArrayEquals(new int[]{0, 3}, caretStopsOf(line),
                    "a caret cannot be placed inside a conjunct");
        }
    }

    /**
     * ADR 031 Finding 3, row three, and the decisive property of the whole ADR: a glyph drawn
     * <em>before</em> the code point that precedes it.
     *
     * <p>The i-matra is typed after its consonant and drawn to its left. Counts alone do not show
     * that &mdash; HarfBuzz merges the matra into the consonant's cluster, so both report offset 0
     * &mdash; so what is asserted is <b>which of the two comes first in drawn order</b>, plus the
     * x that puts it there.
     */
    @Test
    void theIMatraIsDrawnBeforeTheConsonantItFollows() {
        try (FontStore store = storeWithScriptFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);
            StbFont deva = faceFor(store, HA);

            assertEquals(6, HINDI.length(), "ha, i-matra, na, virama, da, ii");

            ShapedText line = ruler.shape(HINDI, FONT);

            assertEquals(5, line.glyphCount(), "the na+virama+da conjunct took two down to one");
            assertShapedNotDegraded(line, "hindī");
            assertArrayEquals(new int[]{0, 0, 2, 4, 4}, clustersOf(line),
                    "the drawn cluster order ADR 031 measured");

            // THE assertion. The ha is the first character of the string and the SECOND glyph of
            // the line; the glyph ahead of it belongs to the same cluster and is the matra that
            // follows it logically. Both halves are needed: the id proves which glyph is which, and
            // the x proves the one in front is genuinely drawn to the left rather than merely
            // listed first.
            assertEquals(deva.glyphIndex(HA), line.glyphId(1),
                    "the ha is typed first and must be drawn SECOND");
            assertEquals(0, line.glyphCluster(0), "the matra ahead of it belongs to the ha");
            assertTrue(line.glyphX(0) < line.glyphX(1),
                    "and it is drawn to the LEFT of the consonant it comes after");

            // The matra is also not the glyph its own cmap entry names: the shaper replaced it with
            // a contextual form sized to the consonant it wraps, which is a second thing a cmap
            // walk has no way to reach even if somebody hand-reordered the output.
            assertNotEquals(deva.glyphIndex(I_MATRA), line.glyphId(0),
                    "the matra is a contextual form, not the cmap's glyph");

            // What the merge costs the caret, stated so a later change to clustering is loud: the
            // matra's own char index has no stop, so a click on it lands on the consonant.
            assertArrayEquals(new int[]{0, 2, 4, 6}, caretStopsOf(line));
            assertEquals(0, line.caretIndex(line.caretOrdinal(1)),
                    "index 1 is inside a cluster and snaps back to its start");
        }
    }

    // ============================================================================================
    // The script-tag trap.
    // ============================================================================================

    /**
     * A HarfBuzz script tag is the ISO 15924 code with its <b>first letter capitalised</b>:
     * {@code Deva}, not {@code deva}. A lowercase tag is not a registered script, so HarfBuzz
     * quietly selects the generic shaper and Devanagari comes back unreordered and unligated with
     * <em>no error raised anywhere</em>. It cost this project a wrong result once.
     *
     * <p>This runs both tags through the same face and the same handle so the failure is exhibited
     * rather than described, and pins what the wrong one produces: <b>the cmap's own glyphs, in
     * code-point order</b> &mdash; which is to say, precisely the pipeline ADR 031 was written to
     * replace, silently reinstated. The two tests above are what guard this in normal running: a
     * conjunct count of 1 and a drawn order with the consonant second are both unreachable from the
     * generic shaper, so either one fails the moment a tag is spelled by hand.
     */
    @Test
    void aLowercaseScriptTagSilentlyReinstatesTheCodePointPipeline() {
        try (FontStore store = storeWithScriptFaces()) {
            StbFont deva = faceFor(store, HA);
            HarfBuzzShaper.Handle handle = deva.shaper();
            float scale = deva.scaleForSize(FONT.size());

            int correct = HarfBuzzShaper.scriptTag(Character.UnicodeScript.DEVANAGARI);
            int lowercase = ('d' << 24) | ('e' << 16) | ('v' << 8) | 'a';
            assertEquals(('D' << 24) | ('e' << 16) | ('v' << 8) | 'a', correct,
                    "the tag the table hands out must be the capitalised spelling");
            assertNotEquals(correct, lowercase, "one bit apart, and nothing checks it");

            // The conjunct. Right tag: one glyph. Wrong tag: three, and they are ka, virama and ssa
            // exactly as a cmap lookup per code point would have produced them.
            assertEquals(1, shaped(handle, KSHA, correct, scale).count, "Deva ligates ksha");
            HarfBuzzShaper.Output generic = shaped(handle, KSHA, lowercase, scale);
            assertEquals(3, generic.count, "the generic shaper ligates nothing");
            assertArrayEquals(cmapWalk(deva, KSHA), idsOf(generic),
                    "and returns the cmap, in code-point order");

            // The reordering, same story. Six glyphs instead of five, the ha in front instead of
            // second, and every id straight out of the cmap.
            HarfBuzzShaper.Output right = shaped(handle, HINDI, correct, scale);
            HarfBuzzShaper.Output wrong = shaped(handle, HINDI, lowercase, scale);
            assertEquals(5, right.count, "Deva ligates the na+virama+da conjunct");
            assertEquals(6, wrong.count, "the generic shaper leaves all six standing");
            assertArrayEquals(cmapWalk(deva, HINDI), idsOf(wrong),
                    "one glyph per code point, in typing order: the pipeline ADR 031 replaced");
            assertEquals(deva.glyphIndex(HA), wrong.glyphIds[0],
                    "unreordered: the ha is drawn first, where it was typed");
            assertNotEquals(idsOf(right)[0], idsOf(wrong)[0],
                    "the two tags disagree about the very first glyph on the line");

            // The silence itself, which is the reason this is a trap and not a bug: shapeRun
            // reports success for the wrong tag. Nothing logs, nothing throws, nothing returns
            // false. Only the glyphs are different, and only for scripts that need a shaper — the
            // Latin in the same build would look untouched.
            assertTrue(HarfBuzzShaper.shapeRun(handle, HINDI, 0, HINDI.length(), lowercase, false,
                    scale, new HarfBuzzShaper.Output()), "the wrong tag succeeds, loudly nowhere");
        }
    }

    // ============================================================================================
    // Thai: no spaces, and marks that take no room.
    // ============================================================================================

    /**
     * Above and below marks come back with <b>zero advance</b>, placed by GPOS against the base
     * they belong to.
     *
     * <p>This is what keeps a cluster's box the width of its consonant instead of the width of the
     * consonant plus its vowel plus its tone, and it is the difference between a Thai word and
     * three glyphs strung out in a row.
     */
    @Test
    void thaiVowelAndToneMarksCarryNoAdvanceOfTheirOwn() {
        try (FontStore store = storeWithScriptFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);
            faceFor(store, PO_PLA); // the chain has a Thai face at all, before anything is read

            ShapedText line = ruler.shape(PIN, FONT);

            assertEquals(4, PIN.length(), "po pla, sara i, mai ek, no nu");
            assertEquals(4, line.glyphCount());
            assertShapedNotDegraded(line, "pin");
            // Consonant, vowel, tone, consonant: the two in the middle take no room, and the two
            // consonants do. A pipeline that charged the marks an advance would draw this word half
            // again too wide with two holes in it.
            assertTrue(line.glyphAdvance(0) > 0, "the base consonant is a spacing glyph");
            assertEquals(0, line.glyphAdvance(1), EPS, "the above vowel takes no room");
            assertEquals(0, line.glyphAdvance(2), EPS, "nor does the tone mark stacked on it");
            assertTrue(line.glyphAdvance(3) > 0);
            // All three of the first belong to one cluster, so they are one caret stop and one
            // selection box, which is what a user means by "a letter" here.
            assertArrayEquals(new int[]{0, 0, 0, 3}, clustersOf(line));

            // Positioned, not merely emitted: the tone mark is pulled back from the pen and lifted
            // off the baseline by GPOS. A y of zero here would mean the accent is sitting on the
            // baseline, which is the shape of the bug where the shaper's positive-up y is used
            // without the sign flip the Canvas needs.
            assertTrue(line.glyphX(2) < line.glyphX(1), "the tone is placed behind the pen");
            assertTrue(Math.abs(line.glyphY(2)) > EPS, "and off the baseline");
            assertEquals(0, line.glyphY(0), EPS, "while a base glyph sits on it");

            // The mirror of a ligature, and worth pinning beside it: sara am is ONE code point that
            // decomposes into two glyphs, a mark above and a spacing vowel after. One code point in
            // and two glyphs out is as unrepresentable per-code-point as three in and one out.
            ShapedText nam = ruler.shape(NAM, FONT);
            assertEquals(3, NAM.length(), "no nu, mai tho, sara am");
            assertEquals(4, nam.glyphCount(), "sara am decomposes into two glyphs");
            assertShapedNotDegraded(nam, "nam");
            assertEquals(2, countZeroAdvance(nam), "the nikhahit and the tone mark");
        }
    }

    /**
     * A Thai line's width is the sum of its <b>spacing</b> clusters and nothing else.
     *
     * <p>Trivial arithmetic on a Latin line, where every glyph spaces; the reason it is pinned is
     * that these strings have marks and <em>no spaces at all</em>, so the width is the only thing
     * standing between the layout and a word that overflows its label by the count of its accents.
     */
    @Test
    void aThaiWidthIsTheSumOfItsSpacingClustersAndNothingElse() {
        try (FontStore store = storeWithScriptFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);

            for (String text : new String[]{PIN, NAM, SENTENCE, PHASA_THAI}) {
                ShapedText line = ruler.shape(text, FONT);
                assertShapedNotDegraded(line, text);

                float spacing = 0;
                for (int g = 0; g < line.glyphCount(); g++) {
                    // Zero or positive, never negative: a mark that "backed the pen up" would make
                    // the sum below accidentally right while the glyphs sat in the wrong places.
                    assertTrue(line.glyphAdvance(g) >= 0, () -> "negative advance in " + text);
                    spacing += line.glyphAdvance(g);
                }
                assertEquals(line.metrics().width(), spacing, EPS,
                        () -> "the measured width is not what the glyphs account for: " + text);
                assertEquals(line.metrics().width(), line.advanceTo(text.length()), EPS,
                        () -> "and the caret budget does not reach it either: " + text);

                // Not one space anywhere, which is why phase 4's line breaking needs
                // BreakIterator: fitEnd alone would cut this text wherever the budget ran out,
                // and in Thai there is no character that says a cut is allowed there.
                assertEquals(-1, text.indexOf(' '), () -> "fixture has a space in it: " + text);
            }

            // The three mark-carrying fixtures really do carry marks, so the loop above was not
            // quietly measuring four rows of plain spacing glyphs and proving nothing.
            for (String text : new String[]{PIN, NAM, SENTENCE}) {
                assertTrue(countZeroAdvance(ruler.shape(text, FONT)) > 0,
                        () -> "no zero-advance mark in " + text);
                // And the marks merge into their bases: fewer caret stops than characters is the
                // shape of that, and is what a per-code-point walk gets wrong by giving every mark
                // a stop of its own.
                assertTrue(ruler.shape(text, FONT).caretCount() < text.length() + 1,
                        () -> "every character got its own cluster in " + text);
            }
        }
    }

    // ============================================================================================
    // Cluster mapping, which is the contract everything above rests on.
    // ============================================================================================

    /**
     * Under reordering, decomposition and ligation alike, every glyph's cluster is still a valid
     * offset into the original string, and the caret stops still tile it.
     *
     * <p>ADR 031 names this the whole contract: a shaper reports clusters as offsets into what it
     * was handed, every run boundary moves that origin, and an off-by-one is a caret that lands one
     * character away from every click forever. The strong form is asserted rather than a bounds
     * check &mdash; the set of distinct clusters is exactly the set of caret stops minus the final
     * one &mdash; because that says no cluster was invented and none was lost, which bounds alone
     * would not notice.
     */
    @Test
    void everyClusterIsACaretStopAndTheStopsTileTheText() {
        try (FontStore store = storeWithScriptFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);

            for (String text : new String[]{KSHA, HINDI, PIN, NAM, SENTENCE, PHASA_THAI}) {
                ShapedText line = ruler.shape(text, FONT);
                assertShapedNotDegraded(line, text);

                int[] stops = caretStopsOf(line);
                assertEquals(0, stops[0], () -> "no stop at the start of " + text);
                assertEquals(text.length(), stops[stops.length - 1],
                        () -> "no stop at the end of " + text);
                float previous = -1;
                for (int k = 0; k < stops.length; k++) {
                    if (k > 0) {
                        assertTrue(stops[k] > stops[k - 1],
                                () -> "the stop table is not strictly ascending in " + text);
                    }
                    // Logical order, so the budget never goes backwards even where the glyphs did.
                    float advance = line.advanceTo(stops[k]);
                    assertTrue(advance >= previous,
                            () -> "advanceTo went backwards inside " + text);
                    previous = advance;
                }

                Set<Integer> clusters = new LinkedHashSet<>();
                for (int g = 0; g < line.glyphCount(); g++) {
                    int cluster = line.glyphCluster(g);
                    assertTrue(cluster >= 0 && cluster < text.length(),
                            () -> "cluster " + cluster + " is not an offset into " + text);
                    // Never mid-character: these fixtures are all BMP, so a low surrogate here
                    // would mean the cluster was computed in some unit other than chars.
                    assertTrue(Character.isBmpCodePoint(text.codePointAt(cluster)));
                    clusters.add(cluster);
                }
                // The tiling. Every stop but the last is some glyph's cluster, and every glyph's
                // cluster is a stop: the two tables are one table, seen from either end.
                assertEquals(Arrays.stream(stops, 0, stops.length - 1).boxed().toList(),
                        clusters.stream().sorted().toList(),
                        () -> "clusters and caret stops disagree about " + text);
            }
        }
    }

    // ============================================================================================
    // Fixtures.
    // ============================================================================================

    /**
     * A store with the four script faces folded in, or an aborted test.
     *
     * <p>The assumption is on the vendored binaries and on nothing else. A missing HarfBuzz native
     * is deliberately <em>not</em> assumed away: this suite runs on machines that have it, and a
     * degraded run has to fail here rather than skip, or the whole file becomes a test of whether
     * the fonts are on disk.
     */
    private static FontStore storeWithScriptFaces() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        if (loaded.scripts().size() != SCRIPT_FACES) {
            // Freed rather than dropped: parseHeavyFallbacks hands over ownership of whatever it
            // did load, and a skipped test that leaks a 16 MB face is still a leak.
            loaded.close();
            Assumptions.abort("the script faces are not bundled on this machine; "
                    + "see scripts/fetch-fonts.sh");
        }
        FontStore store = new FontStore();
        try {
            assertTrue(store.installHeavyFallbacks(loaded), "the fold-in installed nothing");
            return store;
        } catch (Throwable failure) {
            store.close();
            throw failure;
        }
    }

    /** The face the chain resolves {@code codepoint} to, asserted to actually cover it. */
    private static StbFont faceFor(FontStore store, int codepoint) {
        StbFont face = store.faceForCodepoint(store.resolve(FONT), codepoint);
        assertTrue(face.hasGlyph(codepoint),
                () -> String.format("U+%04X resolved to %s, which cannot draw it",
                        codepoint, face.name()));
        return face;
    }

    /**
     * Neither of the two ways a run comes back looking shaped without being shaped.
     *
     * <p>{@link ShapedText#NO_GLYPH} is what the per-code-point fallback emits when the native is
     * absent; glyph {@code 0} is {@code .notdef}, a real drawable box, and is what a run resolved
     * to a face that has never heard of the script produces. Both would sail past a test that only
     * counted glyphs.
     */
    private static void assertShapedNotDegraded(ShapedText line, String what) {
        assertTrue(line.glyphCount() > 0, () -> what + " shaped to nothing");
        assertTrue(line.metrics().width() > 0, () -> what + " measured to nothing");
        for (int g = 0; g < line.glyphCount(); g++) {
            int at = g;
            int id = line.glyphId(g);
            assertNotEquals(ShapedText.NO_GLYPH, id,
                    () -> what + " degraded to the per-code-point walk at glyph " + at);
            assertNotEquals(0, id, () -> what + " shaped into a .notdef box at glyph " + at);
        }
        // One script, one face: these fixtures are each a single script, so a second run would mean
        // the chain split the word between two faces and shaped the halves in isolation.
        assertEquals(1, line.runs().size(), () -> what + " was split across faces");
    }

    /** Shapes one run directly, bypassing the ruler, so a raw script tag can be handed in. */
    private static HarfBuzzShaper.Output shaped(HarfBuzzShaper.Handle handle, String text,
                                                int scriptTag, float scale) {
        HarfBuzzShaper.Output out = new HarfBuzzShaper.Output();
        assertTrue(HarfBuzzShaper.shapeRun(handle, text, 0, text.length(), scriptTag, false, scale,
                out), "the shaper declined the run");
        return out;
    }

    /** What a per-code-point cmap walk over {@code text} would have drawn, in typing order. */
    private static int[] cmapWalk(StbFont face, String text) {
        int[] ids = new int[text.length()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = face.glyphIndex(text.charAt(i));
        }
        return ids;
    }

    private static int[] idsOf(HarfBuzzShaper.Output out) {
        return Arrays.copyOf(out.glyphIds, out.count);
    }

    /** The cluster each glyph carries, in drawn order. */
    private static int[] clustersOf(ShapedText line) {
        int[] clusters = new int[line.glyphCount()];
        for (int g = 0; g < clusters.length; g++) {
            clusters[g] = line.glyphCluster(g);
        }
        return clusters;
    }

    private static int[] caretStopsOf(ShapedText line) {
        int[] stops = new int[line.caretCount()];
        for (int k = 0; k < stops.length; k++) {
            stops[k] = line.caretIndex(k);
        }
        return stops;
    }

    private static int countZeroAdvance(ShapedText line) {
        int marks = 0;
        for (int g = 0; g < line.glyphCount(); g++) {
            if (Math.abs(line.glyphAdvance(g)) < EPS) {
                marks++;
            }
        }
        return marks;
    }
}

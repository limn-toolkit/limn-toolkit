package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arabic and Hebrew end to end: the two scripts that need <em>both</em> halves of this work, a
 * shaper to choose the glyphs and the bidi algorithm to order them.
 *
 * <p>Headless CPU, no GL context and no window — shaping is arithmetic over a font file, which is
 * the only reason any of this can be pinned at all rather than looked at in a screenshot. ADR 031
 * §5 says exactly why it has to be: bidi caret behaviour is subtle and looks right in a picture
 * while being wrong, so what is written here is logical order in, expected visual positions out.
 *
 * <p><b>Two ways for this file to lie, and both are guarded.</b> A face that is not on this machine
 * would make every assertion vacuous, so the fixtures are behind an assumption on the vendored
 * binaries and every run's face is asserted to actually cover the letters it was handed. A shaper
 * that failed to load would degrade to the per-code-point walk, whose glyphs are all
 * {@link ShapedText#NO_GLYPH} and whose ids are therefore no evidence of anything — so every glyph
 * this file inspects is asserted to be a real index, and a real index that is not {@code 0}, which
 * is the {@code .notdef} box a run resolved to the wrong face produces.
 *
 * <p>Face coverage as such is {@code ComplexScriptFallbackTest}; itemization above the face is
 * {@code ShapingRulerTest}. What is here is what those two cannot see: which glyph a letter takes
 * from the company it keeps, where a mark lands, and where a caret goes on a line that runs the
 * other way.
 */
class RtlShapingTest {

    private ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    private static final Font FONT = Font.of(16);
    private static final float EPS = 1e-3f;

    /** How many script faces {@code scripts/fetch-fonts.sh} vendors; fewer is an incomplete set. */
    private static final int SCRIPT_FACES = 4;

    /** Arabic letter beh: dual-joining, so it has all four positional forms. */
    private static final int BEH = 0x0628;

    /** Arabic fatha: a combining mark, and the one this file follows through GPOS. */
    private static final int FATHA = 0x064E;

    /** The Arabic word for "Arabic", ADR 031 Finding 3's first row: seven code points. */
    private static final String AL_ARABIYYA = "العربية";

    /** Arabic riyal, the RTL word in ADR 031 Finding 4's bidi fixture. */
    private static final String RIYAL = "ريال";

    /** Hebrew shalom, unpointed: four letters, four glyphs, no marks. */
    private static final String SHALOM = "שלום";

    // The three fixtures below are spelled in escapes on purpose, and their unmarked partner with
    // them so the pair reads as a pair. A combining mark inside a source literal is invisible in
    // every editor that renders it correctly — it lands on the letter before it — so the literal
    // form of a vocalized word cannot be reviewed at all, and a fixture nobody can read is a
    // fixture that silently becomes the wrong one. Escaped, the character counts these tests
    // assert are checkable by counting the escapes.

    /** {@code kataba}: kaf, fatha, teh, fatha, beh, fatha — three letters carrying three marks. */
    private static final String KATABA = "\u0643\u064E\u062A\u064E\u0628\u064E";

    /** The same word unvocalized: kaf, teh, beh. The marks are the only difference. */
    private static final String KATABA_BARE = "\u0643\u062A\u0628";

    /** {@code shalom} with niqqud: shin, qamats, shin-dot, lamed, vav, holam, final mem. */
    private static final String SHALOM_POINTED =
            "\u05E9\u05B8\u05C1\u05DC\u05D5\u05B9\u05DD";

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
    // Arabic: the glyph a letter takes depends on the letters beside it.
    // ============================================================================================

    @Test
    void oneArabicLetterTakesFourDifferentGlyphsAndTheCmapsGlyphIsNoneOfThem() {
        try (FontStore store = storeWithTheRtlFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);
            StbFont face = store.faceForCodepoint(store.resolve(FONT), BEH);
            assertTrue(face.hasGlyph(BEH),
                    "Arabic resolved to " + face.name() + ", which cannot draw it");
            int cmap = face.glyphIndex(BEH);

            // One beh, then three, then four — the same character every time, spelled by repeating
            // one constant so no fixture can quietly become a different letter, and with no tatweel
            // anywhere: the joining has to come from the word rather than from a filler character
            // that would make the fixture beg the question.
            String beh = Character.toString(BEH);
            ShapedText one = ruler.shape(beh, FONT);
            ShapedText three = ruler.shape(beh.repeat(3), FONT);
            ShapedText four = ruler.shape(beh.repeat(4), FONT);

            int isolated = baseGlyphAt(one, 0);
            int initial = baseGlyphAt(three, 0);
            int medial = baseGlyphAt(three, 1);
            int last = baseGlyphAt(three, 2);

            // Four positions, four glyphs. This is the property Finding 1 of ADR 031 called
            // unrepresentable rather than unimplemented: one code point in, one glyph out cannot
            // produce four answers for one character.
            assertEquals(4, distinct(isolated, initial, medial, last),
                    "isolated/initial/medial/final must be four distinct glyphs, got "
                            + isolated + "/" + initial + "/" + medial + "/" + last);
            for (int id : new int[]{isolated, initial, medial, last}) {
                assertNotEquals(ShapedText.NO_GLYPH, id, "the shaper degraded and proved nothing");
                assertNotEquals(0, id, "a .notdef box is not a positional form");
                // The whole claim in one line: the naive pipeline would have drawn THIS glyph in
                // all four places, and the shaper draws it in none of them.
                assertNotEquals(cmap, id, "a positional form equal to the cmap's own glyph");
            }

            // The two medial positions of a four-letter word take the SAME glyph, which is what
            // distinguishes a contextual form from a counter: the id follows the company the letter
            // keeps, not how far into the word it sits.
            assertEquals(medial, baseGlyphAt(four, 1));
            assertEquals(medial, baseGlyphAt(four, 2));
            assertEquals(initial, baseGlyphAt(four, 0));
            assertEquals(last, baseGlyphAt(four, 3));

            // Isolated, initial, medial and final are every position a letter can be in, and the
            // cmap's own glyph is none of them — so in this face that glyph is drawn nowhere, by
            // any word, and it is exactly the glyph a per-code-point pipeline has no choice but to
            // draw four times.
            for (ShapedText line : List.of(one, three, four)) {
                for (int g = 0; g < line.glyphCount(); g++) {
                    assertNotEquals(cmap, line.glyphId(g),
                            "the glyph the cmap alone can reach was drawn");
                }
            }
        }
    }

    @Test
    void theAdrsArabicRowIsTenGlyphsAndEveryOneButTheAlefLeavesTheCmapBehind() {
        try (FontStore store = storeWithTheRtlFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);

            ShapedText line = ruler.shape(AL_ARABIYYA, FONT);

            assertEquals(7, AL_ARABIYYA.length());
            // ADR 031 Finding 3, first row, re-taken against the pinned face rather than against
            // whatever was on the machine that wrote the ADR. Ten, not seven: this face draws the
            // dots of beh, yeh and teh marbuta as glyphs of their own, so three of the ten are
            // dot components that GPOS then places, and no cmap lookup can produce them at all.
            assertEquals(10, line.glyphCount());
            assertEquals(1, line.runs().size(), "one script, one face, one shaping call");
            StbFont face = store.faceById(line.runs().get(0).faceId());
            assertNotNull(face);
            assertTrue(face.hasGlyph(0x0639), "shaped against " + face.name() + ", which is blind");

            // "Differs from the naive cmap id" needs the naive id spelled out: the glyph this face
            // would hand back for the character this glyph came from, with no neighbours consulted.
            int same = 0;
            int sameGlyph = -1;
            for (int g = 0; g < line.glyphCount(); g++) {
                int id = line.glyphId(g);
                assertNotEquals(ShapedText.NO_GLYPH, id, "the shaper degraded at glyph " + g);
                assertNotEquals(0, id, "a .notdef box at glyph " + g);
                if (id == face.glyphIndex(AL_ARABIYYA.codePointAt(line.glyphCluster(g)))) {
                    same++;
                    sameGlyph = g;
                }
            }
            // NINE of the ten, not ten of the ten. The ADR's row says every glyph differs, and
            // against this face exactly one does not: the leading alef. Alef joins only to its
            // right, so a word-initial alef has nothing to join to and keeps its isolated form —
            // which is precisely the glyph the cmap gives. That is not shaping failing to happen,
            // it is shaping producing the unjoined answer because the unjoined answer is correct,
            // and the ADR's "every one of the ten" was one letter too strong.
            assertEquals(1, same, "expected exactly the alef to match its cmap glyph");
            assertEquals(line.glyphCount() - 1, sameGlyph,
                    "the matching glyph must be the one drawn furthest right");
            assertEquals(0, line.glyphCluster(sameGlyph), "and it must be the FIRST character");
            assertEquals(0x0627, AL_ARABIYYA.codePointAt(0), "that character is alef");
        }
    }

    @Test
    void arabicMarksComeBackAtZeroAdvanceWithRealInkAndDoNotMoveThePen() {
        try (FontStore store = storeWithTheRtlFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);
            StbFont face = store.faceForCodepoint(store.resolve(FONT), BEH);
            int fatha = face.glyphIndex(FATHA);
            assertNotEquals(0, fatha, "the face has no fatha, so this fixture proves nothing");

            ShapedText marked = ruler.shape(KATABA, FONT);
            ShapedText bare = ruler.shape(KATABA_BARE, FONT);

            // Three letters and three marks arrive as eight glyphs: three bases, three fathas, and
            // the two dot components of teh and beh.
            assertEquals(6, KATABA.length());
            assertEquals(8, marked.glyphCount());

            int marks = 0;
            int fathas = 0;
            List<Float> fathaOffsets = new ArrayList<>();
            for (int g = 0; g < marked.glyphCount(); g++) {
                int id = marked.glyphId(g);
                assertNotEquals(ShapedText.NO_GLYPH, id, "the shaper degraded at glyph " + g);
                assertNotEquals(0, id, "a .notdef box at glyph " + g);
                if (marked.glyphAdvance(g) != 0) {
                    continue;
                }
                marks++;
                // Zero advance AND real ink is what makes it a mark rather than a formatting
                // character the pipeline forgot to filter: a zero-advance glyph with an empty box
                // is a glyph that was charged nothing because it draws nothing.
                assertTrue(hasInk(face, id),
                        "a zero-advance glyph with no bitmap is not a mark: id " + id);
                if (id == fatha) {
                    fathas++;
                    fathaOffsets.add(marked.glyphY(g));
                }
            }
            assertTrue(marks >= 1, "no mark came back at zero advance");
            assertEquals(3, fathas, "one fatha per letter, and each one its own glyph");
            // The same glyph, three times, at three different heights. Only GPOS can do that: a
            // cmap lookup returns one id and a pipeline that draws it draws it at the pen, so a
            // mark that sits differently over kaf, teh and beh is positioning that happened, not a
            // glyph that happened to be picked.
            assertEquals(3, fathaOffsets.stream().distinct().count(),
                    "one fatha glyph placed three times must land at three offsets, got "
                            + fathaOffsets);

            // "Does not move the pen", stated as the only thing that could prove it: the vocalized
            // word is EXACTLY as wide as the same word without its marks. Not approximately — the
            // width is a sum of advances and the marks contribute zero terms to it.
            assertEquals(bare.metrics().width(), marked.metrics().width(), EPS);

            // And the caret does not see them either. Three marks add three characters and not one
            // caret stop, because a caret cannot be placed between a letter and the mark on it.
            assertEquals(bare.caretCount(), marked.caretCount());
            for (int k = 0; k < marked.caretCount(); k++) {
                assertEquals(2 * k, marked.caretIndex(k),
                        "the stops are the letters, and the marks are the odd indices between");
            }
        }
    }

    // ============================================================================================
    // Hebrew: right to left, with the points that hang off the letters.
    // ============================================================================================

    @Test
    void hebrewPointsRunRightToLeftAndCostNeitherAdvanceNorACaretStop() {
        try (FontStore store = storeWithTheRtlFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);

            ShapedText line = ruler.shape(SHALOM_POINTED, FONT);
            ShapedText bare = ruler.shape(SHALOM, FONT);

            assertEquals(7, SHALOM_POINTED.length());
            assertEquals(7, line.glyphCount(), "ADR 031 Finding 3: seven in, seven out");
            assertEquals(ShapedText.Direction.RTL, line.baseDirection(), "first strong is Hebrew");
            assertEquals(1, line.runs().size());
            ShapedText.Run run = line.runs().get(0);
            assertTrue(run.rtl());
            assertEquals(1, run.level());
            StbFont face = store.faceById(run.faceId());
            assertNotNull(face);
            assertTrue(face.hasGlyph(0x05E9), "shaped against " + face.name() + ", which is blind");

            // The visual run reads the other way: glyphs come out leftmost first, so the clusters
            // DESCEND across it. Not strictly — a mark is merged into the cluster of the letter it
            // sits on, so the three points repeat their base's offset — but never ascending, which
            // is what a pipeline emitting glyphs in code-point order would produce.
            int[] clusters = new int[line.glyphCount()];
            for (int g = 0; g < line.glyphCount(); g++) {
                clusters[g] = line.glyphCluster(g);
                assertNotEquals(ShapedText.NO_GLYPH, line.glyphId(g), "degraded at glyph " + g);
                assertNotEquals(0, line.glyphId(g), "a .notdef box at glyph " + g);
                if (g > 0) {
                    assertTrue(clusters[g] <= clusters[g - 1],
                            "cluster rose from " + clusters[g - 1] + " to " + clusters[g]
                                    + " inside a right-to-left run");
                }
            }
            assertEquals(6, clusters[0], "the last character is drawn furthest LEFT");
            assertEquals(0, clusters[line.glyphCount() - 1], "and the first furthest right");

            // Restricted to the letters, the descent is strict: four bases, four decreasing
            // offsets, the reverse of the order they were typed in.
            int previous = Integer.MAX_VALUE;
            int bases = 0;
            List<Integer> points = new ArrayList<>();
            for (int g = 0; g < line.glyphCount(); g++) {
                if (line.glyphAdvance(g) == 0) {
                    // A point: zero advance, and real ink, which is the pair that says GPOS placed
                    // it rather than that the pipeline dropped a character on the floor.
                    assertTrue(hasInk(face, line.glyphId(g)),
                            "a zero-advance glyph with no bitmap is not a niqqud");
                    points.add(line.glyphId(g));
                    // And placed AWAY from the pen. A mark drawn at its cluster's origin would be
                    // sitting wherever the base happens to start, which is what a pipeline with no
                    // GPOS produces and what makes a qamats land under the wrong letter; here each
                    // point is offset from the letter it belongs to.
                    assertNotEquals(baseXAt(line, clusters[g]), line.glyphX(g),
                            "a point drawn at its base's own origin was not positioned at all");
                    continue;
                }
                bases++;
                assertTrue(clusters[g] < previous, "the letters must strictly descend");
                previous = clusters[g];
            }
            assertEquals(4, bases, "shin, lamed, vav, final mem");
            assertEquals(3, points.size(), "qamats, shin dot, holam");
            // Named, not merely counted: these three glyphs are the three niqqud of the fixture and
            // not, say, one of them drawn three times over a line that lost the other two.
            assertEquals(List.of(face.glyphIndex(0x05B8), face.glyphIndex(0x05C1),
                            face.glyphIndex(0x05B9)).stream().sorted().toList(),
                    points.stream().sorted().toList(),
                    "the zero-advance glyphs are not qamats, shin dot and holam");

            // The pen does not move for a point, and the caret does not stop on one: pointed and
            // unpointed shalom are the same width and offer the same number of insertion points,
            // three of the seven character offsets being unreachable.
            assertEquals(bare.metrics().width(), line.metrics().width(), EPS);
            assertEquals(bare.caretCount(), line.caretCount());
            assertEquals(5, line.caretCount());
            int[] stops = new int[line.caretCount()];
            for (int k = 0; k < stops.length; k++) {
                stops[k] = line.caretIndex(k);
            }
            assertArrayEqualsInts(new int[]{0, 3, 4, 6, 7}, stops,
                    "the niqqud at 1, 2 and 5 are not places a caret can go");
        }
    }

    /**
     * The divergence this backend used to hand upwards, pinned at its source: <b>a width from the
     * per-code-point walk is not an upper bound on a shaped one.</b>
     *
     * <p>The walk resolves a face per character, so the space between two Hebrew words is taken
     * from the Latin primary that covers it. {@code shape} itemizes into runs and lets a
     * {@code COMMON} character extend the run it follows, so the same space is taken from the
     * Hebrew face &mdash; a wider one here. The difference is per word seam and it accumulates down
     * the line, which is why a scroll extent built from a scan of walked widths stopped short of
     * where the shaping put the caret.
     *
     * <p><b>What changed, and why this test did not become pointless.</b> {@code ShapingRuler
     * .measure} now answers from {@code shape}, so this backend no longer <em>reports</em> the
     * narrower number to anything: a widget that measures and a widget that shapes get the same
     * float, and the {@code TextArea} bug this caused cannot recur through this ruler. What has not
     * changed is the underlying fact, which is a property of two faces and a fallback rule rather
     * than of an API, and the widget's floor still stands behind rulers that do report two answers
     * &mdash; {@code TextRuler}'s own default {@code shape} is one of them. So the comparison here
     * is against {@link FontStore#measure}, the walk itself, which is where the fact lives.
     *
     * <p>Only the <em>direction</em> and the <em>growth</em> are asserted, never an amount. The
     * amount is two faces' opinion of a space and would change with either of them; what must not
     * change silently is that the shaped side can be the larger one, because that is the half a
     * caller is tempted to assume away.
     */
    @Test
    void aHebrewLineShapesWiderThanThePerCodePointWalkAndTheGapGrowsWithTheLine() {
        try (FontStore store = storeWithTheRtlFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);
            String phrase = SHALOM + " " + SHALOM; // one seam, and a Latin space at it

            float shortGap = ruler.shape(phrase, FONT).metrics().width()
                    - store.measure(FONT, phrase).width();
            assertTrue(shortGap > EPS,
                    "shaping came out no wider than the walk, so the widget's floor is untested "
                            + "against the case it exists for: " + shortGap);

            String longer = (phrase + " ").repeat(10) + phrase;
            float longGap = ruler.shape(longer, FONT).metrics().width()
                    - store.measure(FONT, longer).width();
            // Per seam, not per line: a fixed hairline could be absorbed by a clearance, and this
            // one cannot, which is the whole reason the extent had to change.
            assertTrue(longGap > 5 * shortGap,
                    "the gap did not grow with the seam count (" + shortGap + " -> " + longGap
                            + "), so it is not the per-seam effect this test claims");
        }
    }

    // ============================================================================================
    // Bidi: the runs are built for the screen, and the string is not.
    // ============================================================================================

    @Test
    void aMixedLineItemizesIntoRunsOrderedForTheScreenAndNotForTheString() {
        try (FontStore store = storeWithTheRtlFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);

            // ADR 031 Finding 4's own fixture, asserted first against java.text.Bidi itself, so a
            // failure says whether the platform moved or this code did.
            String priced = "Total: 42 " + RIYAL + " (SAR)";
            Bidi bidi = new Bidi(priced, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
            assertEquals(3, bidi.getRunCount());
            assertEquals(0, bidi.getRunLevel(0));
            assertEquals(1, bidi.getRunLevel(1));
            assertEquals(0, bidi.getRunLevel(2));

            ShapedText line = ruler.shape(priced, FONT);

            assertEquals(ShapedText.Direction.LTR, line.baseDirection(), "first strong is Latin");
            assertFalse(line.isSimple(), "a right-to-left run is not the fast path");
            assertEquals(3, line.runs().size(), "the shaper's runs are the bidi runs here");
            assertRunLevels(line, 0, 1, 0);
            assertRunsTileAndAscendOnTheLine(line);
            // The Latin runs and the Arabic one are drawn by different faces, which is the seam
            // itemization exists to find.
            assertNotEquals(line.runs().get(0).faceId(), line.runs().get(1).faceId());
            assertEquals(line.runs().get(0).faceId(), line.runs().get(2).faceId());

            // What this line does NOT show, said out loud so nobody reads more into it than it
            // proves: its three runs happen to be in ascending character order too, because the
            // right-to-left island sits between two left-to-right stretches and lands where it
            // already was. All of the reordering here is INSIDE the middle run, whose clusters
            // descend. A test that stopped at this fixture would pass with the run reordering
            // deleted entirely.
            assertEquals(0, line.runs().get(0).charStart());
            assertEquals(10, line.runs().get(1).charStart());
            assertEquals(14, line.runs().get(2).charStart());
            ShapedText.Run rtl = line.runs().get(1);
            for (int g = rtl.glyphStart() + 1; g < rtl.glyphEnd(); g++) {
                assertTrue(line.glyphCluster(g) <= line.glyphCluster(g - 1),
                        "the RTL run is the only thing reordered on this line");
            }

            // So here is the line that does show it. The trailing digits take level 2 — an EVEN
            // level above the odd one around them — which puts them inside the Hebrew stretch and
            // draws them to its left. The run that comes back second covers characters 9 to 11 and
            // the run that comes back third covers 4 to 9: visual order, and the character ranges
            // out of order, which is exactly what a caller iterating runs() must never assume away.
            ShapedText mixed = ruler.shape("abc " + SHALOM + " 42", FONT);

            assertEquals(ShapedText.Direction.LTR, mixed.baseDirection());
            assertEquals(3, mixed.runs().size());
            assertRunLevels(mixed, 0, 2, 1);
            assertRunsTileAndAscendOnTheLine(mixed);
            assertEquals(0, mixed.runs().get(0).charStart());
            assertEquals(9, mixed.runs().get(1).charStart(), "the digits are drawn second");
            assertEquals(4, mixed.runs().get(2).charStart(), "and the Hebrew word third");
            assertTrue(mixed.runs().get(1).charStart() > mixed.runs().get(2).charStart(),
                    "character ranges must be allowed to go backwards between runs");
            for (int g = 0; g < mixed.glyphCount(); g++) {
                assertNotEquals(ShapedText.NO_GLYPH, mixed.glyphId(g), "degraded at glyph " + g);
            }
        }
    }

    // ============================================================================================
    // The round trip phase 4 rests on.
    // ============================================================================================

    @Test
    void onAnRtlLineEveryClickRoundTripsThroughCaretXToTheEdgeItLandedOn() {
        try (FontStore store = storeWithTheRtlFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);

            for (String text : rtlLines()) {
                ShapedText line = ruler.shape(text, FONT);
                assertRealShapedGlyphs(line, text);

                // Every stop, both sides: the x a caret is drawn at must be an x that hit-tests
                // back to itself. It need not hit-test back to the same (index, side) pair, and on
                // a direction boundary it cannot — two indices share those two points — so the
                // invariant is about the POINT on the line, which is what the user clicked.
                for (int k = 0; k < line.caretCount(); k++) {
                    int index = line.caretIndex(k);
                    for (ShapedText.Affinity side : ShapedText.Affinity.values()) {
                        float x = line.caretX(new ShapedText.Position(index, side));
                        assertEquals(x, line.caretX(line.hitTest(x)), EPS,
                                "stop " + index + " " + side + " did not round-trip");
                    }
                }

                // And from arbitrary pixels rather than only from the answers: sweep the line in
                // quarter-point steps, well past both ends so the clamp is covered. Each click
                // lands on a caret edge, and clicking that edge lands on it again — a projection,
                // which is what stops a drag from crawling one cluster per frame.
                float[] edges = caretEdges(line);
                int samples = 0;
                for (float x = -5f; x <= line.metrics().width() + 5f; x += 0.25f) {
                    samples++;
                    float once = line.caretX(line.hitTest(x));
                    assertEquals(once, line.caretX(line.hitTest(once)), EPS,
                            "click at " + x + " did not settle");
                    assertTrue(isNear(edges, once),
                            "click at " + x + " landed at " + once + ", which is no caret's x");
                }
                assertTrue(samples > 20, "the sweep covered nothing: " + text);
            }
        }
    }

    @Test
    void theArrowKeysWalkAnRtlLineEndToEndAndBackToWhereTheyStarted() {
        try (FontStore store = storeWithTheRtlFaces()) {
            ShapingRuler ruler = new ShapingRuler(store);

            for (String text : rtlLines()) {
                ShapedText line = ruler.shape(text, FONT);
                assertRealShapedGlyphs(line, text);

                // Start at the left edge of the line, whatever character lives there — on an RTL
                // line that is the LAST character, which is the whole point of moving visually.
                ShapedText.Position start = line.hitTest(-1e6f);
                assertEquals(0, line.caretX(start), EPS);

                List<Float> rightwards = new ArrayList<>();
                ShapedText.Position at = start;
                rightwards.add(line.caretX(at));
                for (int guard = 0; guard <= line.caretCount(); guard++) {
                    ShapedText.Position next = line.caretRight(at);
                    if (next.equals(at)) {
                        break;
                    }
                    at = next;
                    rightwards.add(line.caretX(at));
                }

                // The whole line: as many stopping places as the stop table has, each one strictly
                // right of the last, ending at the far edge. A press that does not move is a
                // keystroke the user paid for and did not get, and a press that skips a cluster is
                // a character they cannot put a caret before.
                //
                // Counted in POINTS, not in distinct char indices, and the difference is real on
                // the mixed line: the two indices either side of an embedded number occupy the very
                // same pair of points, so a visual walk names one of them twice and the other never
                // while still visiting every place a caret can sit. An editor that stored only the
                // index would see the caret stand still there; it is why Position carries a side.
                assertEquals(line.caretCount(), rightwards.size(),
                        "the walk did not visit every stop of: " + text);
                for (int i = 1; i < rightwards.size(); i++) {
                    float here = rightwards.get(i);
                    float before = rightwards.get(i - 1);
                    assertTrue(here > before, "Right arrow moved left or stood still at step " + i);
                }
                float far = rightwards.get(rightwards.size() - 1);
                assertEquals(line.metrics().width(), far, EPS);

                // And back. Same count, the same points in the opposite order, and it returns to
                // the very Position it started from — not merely to the same x, which a caret that
                // silently changed its side would also satisfy and which would then jump on the
                // next press.
                List<Float> leftwards = new ArrayList<>();
                ShapedText.Position back = at;
                leftwards.add(line.caretX(back));
                for (int guard = 0; guard <= line.caretCount(); guard++) {
                    ShapedText.Position previous = line.caretLeft(back);
                    if (previous.equals(back)) {
                        break;
                    }
                    back = previous;
                    leftwards.add(line.caretX(back));
                }
                assertEquals(rightwards.size(), leftwards.size(),
                        "the two walks disagree about how many stops the line has: " + text);
                for (int i = 0; i < leftwards.size(); i++) {
                    float mirrored = rightwards.get(rightwards.size() - 1 - i);
                    float walked = leftwards.get(i);
                    assertEquals(mirrored, walked, EPS,
                            "the leftward walk is not the rightward one reversed, at step " + i);
                }
                assertEquals(start, back, "the caret did not come home on: " + text);
            }
        }
    }

    // ============================================================================================
    // Fixtures and helpers.
    // ============================================================================================

    /**
     * The lines both round-trip tests walk: pure Arabic, pointed Hebrew, and a right-to-left
     * paragraph carrying a Latin word and a number. The mixed one is not decoration — it is the
     * only one of the three whose walk crosses a direction boundary, and a boundary is where a
     * caret goes wrong.
     */
    private static List<String> rtlLines() {
        return List.of(AL_ARABIYYA, SHALOM_POINTED, SHALOM + " 42 abc");
    }

    /**
     * A store with the vendored script faces folded in, or a skipped test.
     *
     * <p>{@code scripts/fetch-fonts.sh} is optional and a checkout without it must still build
     * green, which is why this is an assumption rather than a failure — but a skip here is a claim
     * that nothing in this file was checked, which is why it is loud and why the count is exact
     * rather than "at least one".
     */
    private static FontStore storeWithTheRtlFaces() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        boolean present = loaded.scripts().size() == SCRIPT_FACES;
        if (!present) {
            // These are open native faces. Aborting the test does not unwind them, and nothing else
            // holds them once this frame goes away.
            loaded.close();
        }
        Assumptions.assumeTrue(present,
                "the script faces are not bundled on this machine; see scripts/fetch-fonts.sh");
        FontStore store = new FontStore();
        assertTrue(store.installHeavyFallbacks(loaded), "the fallbacks did not fold in");
        return store;
    }

    /**
     * The glyph id of the letter at {@code cluster}: the one glyph of that cluster that carries the
     * advance. A cluster in these faces is a skeleton plus the dots and marks hung on it, and only
     * the skeleton moves the pen — so this is how a fixture names "the beh" rather than "whichever
     * of the two glyphs came back first".
     */
    private static int baseGlyphAt(ShapedText line, int cluster) {
        for (int g = 0; g < line.glyphCount(); g++) {
            if (line.glyphCluster(g) == cluster && line.glyphAdvance(g) != 0) {
                return line.glyphId(g);
            }
        }
        throw new AssertionError("no advancing glyph for cluster " + cluster);
    }

    /** Whether this glyph rasterizes to a bitmap with area: a mark that draws nothing is none. */
    private static boolean hasInk(StbFont face, int glyphId) {
        StbFont.RasterizedGlyph raster = face.rasterizeGlyph(glyphId, 32f);
        try {
            return raster.bitmap() != null && raster.width() > 0 && raster.height() > 0;
        } finally {
            if (raster.bitmap() != null) {
                MemoryUtil.memFree(raster.bitmap());
            }
        }
    }

    /** The x of the advancing glyph of {@code cluster}: where the pen was when its letter drew. */
    private static float baseXAt(ShapedText line, int cluster) {
        for (int g = 0; g < line.glyphCount(); g++) {
            if (line.glyphCluster(g) == cluster && line.glyphAdvance(g) != 0) {
                return line.glyphX(g);
            }
        }
        throw new AssertionError("no advancing glyph for cluster " + cluster);
    }

    /** How many of these ids are different from one another. */
    private static int distinct(int... ids) {
        return (int) java.util.Arrays.stream(ids).distinct().count();
    }

    /** Both x values of every caret stop, which is every point a click is allowed to land on. */
    private static float[] caretEdges(ShapedText line) {
        float[] edges = new float[2 * line.caretCount()];
        for (int k = 0; k < line.caretCount(); k++) {
            ShapedText.Caret caret = line.caretAt(line.caretIndex(k));
            edges[2 * k] = caret.upstreamX();
            edges[2 * k + 1] = caret.downstreamX();
        }
        return edges;
    }

    private static boolean isNear(float[] values, float x) {
        for (float value : values) {
            if (Math.abs(value - x) <= EPS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every glyph on the line is a real shaped index. Without this, a machine whose HarfBuzz native
     * failed to load would run every geometric assertion in this file against the per-code-point
     * walk and pass — the caret arithmetic is shared, so the round trips would still hold, and the
     * file would go green having checked nothing about shaping at all.
     */
    private static void assertRealShapedGlyphs(ShapedText line, String text) {
        assertTrue(line.glyphCount() > 0, "nothing was shaped for: " + text);
        for (int g = 0; g < line.glyphCount(); g++) {
            assertNotEquals(ShapedText.NO_GLYPH, line.glyphId(g),
                    "the degraded path produced glyph " + g + " of: " + text);
        }
    }

    private static void assertRunLevels(ShapedText line, int... levels) {
        assertEquals(levels.length, line.runs().size(), "run count");
        for (int i = 0; i < levels.length; i++) {
            assertEquals(levels[i], line.runs().get(i).level(), "level of run " + i);
        }
    }

    /**
     * The runs tile the glyphs in order, and each one starts to the right of the one before it.
     * "Starts" is the minimum x over the run rather than its first glyph's, because a mark is
     * offset from its base and can be emitted ahead of it — the Arabic runs here open with a dot.
     */
    private static void assertRunsTileAndAscendOnTheLine(ShapedText line) {
        int expectedStart = 0;
        float previousLeft = Float.NEGATIVE_INFINITY;
        for (ShapedText.Run run : line.runs()) {
            assertEquals(expectedStart, run.glyphStart(), "runs must tile the glyphs in order");
            expectedStart = run.glyphEnd();
            float left = Float.POSITIVE_INFINITY;
            for (int g = run.glyphStart(); g < run.glyphEnd(); g++) {
                left = Math.min(left, line.glyphX(g));
            }
            assertTrue(left > previousLeft,
                    "a run was drawn at or left of the one before it: runs() is not visual order");
            previousLeft = left;
        }
        assertEquals(line.glyphCount(), expectedStart, "the runs must cover every glyph");
    }

    /** {@code assertArrayEquals} with a message that names the offending element. */
    private static void assertArrayEqualsInts(int[] expected, int[] actual, String message) {
        assertEquals(expected.length, actual.length, message + " (length)");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], message + " (at " + i + ")");
        }
    }
}

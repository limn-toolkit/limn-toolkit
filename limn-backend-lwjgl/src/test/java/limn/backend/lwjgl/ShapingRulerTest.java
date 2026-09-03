package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend's shaping seam: itemization, the HarfBuzz path, the memo, and the degraded path.
 *
 * <p>No window and no GPU — shaping is CPU work over a font file, which is the whole reason it can
 * be pinned here rather than looked at in a screenshot. Every store here is a fresh one whose
 * optional fallbacks have not been folded in, so Roboto is the only face any of these fixtures
 * resolves to; that is deliberate, because what is checked here is everything that is decided
 * <em>above</em> the face — cluster origins across a run boundary, run order, reordering, and the
 * degraded fallback. The four script faces reaching the shaper is
 * {@code ComplexScriptFallbackTest}, which folds them in.
 */
class ShapingRulerTest {

    private ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    /** alef, bet, gimel: three strong right-to-left characters, one char apiece. */
    private static final String HEB = "אבג";

    /** Cyrillic Be, Ve, Ge: a different script, and one Roboto covers. */
    private static final String CYR = "БВГ";

    private static final Font FONT = Font.of(16);
    private static final float EPS = 1e-3f;

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

    /**
     * The width of the <b>unshaped</b> per-code-point walk: one advance and one kern pair per code
     * point, with a face resolved per character. This is what {@code ShapingRuler.measure} was
     * before it began answering from {@code shape}, and it is still there, one layer down, because
     * {@code FontStore.measure} is what the walk is.
     *
     * <p>Every comparison in this class that means "the width this had before shaping" has to come
     * from here and not from the ruler. Asking the ruler now asks the shaped answer whether it
     * equals itself, and a suite of tautologies passes a build in which shaping quietly stopped
     * happening — which is the exact failure these tests exist to catch.
     */
    private static float unshaped(FontStore store, String text, Font font) {
        return store.measure(font, text).width();
    }

    // ============================================================================================
    // The shaped path.
    // ============================================================================================

    @Test
    void latinShapesToRealGlyphsAndKeepsTheWidthItHasToday() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            String text = "Waltz, bad nymph";

            ShapedText line = ruler.shape(text, FONT);

            assertEquals(text.length(), line.glyphCount(), "no ligature falls in this string");
            assertTrue(line.isSimple(), "one LTR run in one face");
            assertEquals(1, line.runs().size());
            for (int g = 0; g < line.glyphCount(); g++) {
                assertNotEquals(ShapedText.NO_GLYPH, line.glyphId(g),
                        "a shaped run carries real glyph indices, not the sentinel");
                assertEquals(g, line.glyphCluster(g), "one glyph per char, in order");
            }
            // The measurement ADR 031 Finding 5 took by hand: the shaped width and the
            // pre-shaping per-code-point width are the same number for Latin without a ligature.
            // If this drifts, every existing screen has moved.
            assertEquals(unshaped(store, text, FONT), line.metrics().width(), EPS);
        }
    }

    @Test
    void theStandardLigatureIsTheOneEnumeratedLatinChange() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);

            ShapedText line = ruler.shape("office", FONT);

            // "office" is six characters and four glyphs: f+f+i became one. That is the whole
            // Latin/Greek/Cyrillic delta ADR 031 Finding 5 enumerated, and it is a rendering
            // improvement rather than a regression — but it IS a width change, so it is pinned.
            assertEquals(4, line.glyphCount(), "ffi shaped into one glyph");
            assertTrue(line.metrics().width() < unshaped(store, "office", FONT),
                    "the ligature is narrower than the three glyphs it replaced");
            // The ligature reports the offset of its FIRST character, and the characters it
            // swallowed get no stop of their own: a caret cannot be placed inside a glyph.
            assertEquals(0, line.glyphCluster(0));
            assertEquals(1, line.glyphCluster(1), "the ffi ligature starts at 'f'");
            assertEquals(4, line.glyphCluster(2));
        }
    }

    @Test
    void clustersAreOffsetsIntoTheWholeStringAndNotIntoTheRun() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            // Two scripts Roboto covers, so this splits into two runs with one face and no
            // fallback in the way: Latin [0,3) then Cyrillic [3,6).
            String text = "abc" + CYR;

            ShapedText line = ruler.shape(text, FONT);

            assertEquals(2, line.runs().size(), "a script change opens a run");
            ShapedText.Run second = line.runs().get(1);
            assertEquals(3, second.charStart());
            assertEquals(6, second.charEnd());
            // THE trap. A shaper reports clusters relative to what it was handed, and every run
            // boundary shifts that origin; if the second run's glyphs came back as 0,1,2 then
            // every caret in it would land three characters from the click. They are 3,4,5.
            for (int g = second.glyphStart(); g < second.glyphEnd(); g++) {
                assertEquals(3 + (g - second.glyphStart()), line.glyphCluster(g),
                        "cluster offsets are absolute, not run-relative");
            }
            // And the stop table agrees, which is the form a widget actually asks the question in.
            assertEquals(text.length() + 1, line.caretCount());
            for (int i = 0; i <= text.length(); i++) {
                assertEquals(i, line.caretIndex(i));
            }
        }
    }

    @Test
    void aControlCharacterIsItsOwnGlyphlessRunAndDoesNotShiftWhatFollows() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            // A control in the middle: Roboto maps several controls to real glyphs, so the
            // filter that keeps them away from the shaper has to sit ABOVE the cmap, exactly
            // where the per-code-point walk has it.
            String text = "ab\u0001cd";

            ShapedText line = ruler.shape(text, FONT);

            assertEquals(3, line.runs().size(), "shaped, glyphless, shaped");
            ShapedText.Run control = line.runs().get(1);
            assertEquals(2, control.charStart());
            assertEquals(3, control.charEnd());
            assertEquals(1, control.glyphEnd() - control.glyphStart());
            assertEquals(ShapedText.NO_GLYPH, line.glyphId(control.glyphStart()));
            assertEquals(0, line.glyphAdvance(control.glyphStart()), EPS,
                    "a control carries no advance, exactly as the per-code-point walk had it");

            // The run after the control still reports absolute offsets.
            ShapedText.Run tail = line.runs().get(2);
            assertEquals(3, line.glyphCluster(tail.glyphStart()));
            assertEquals(4, line.glyphCluster(tail.glyphStart() + 1));
            // A control has a caret stop of its own, which is what a glyphless run is FOR.
            assertEquals(text.length() + 1, line.caretCount());
        }
    }

    @Test
    void aRunWithNoStrongScriptShapesRatherThanThrowing() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            ShapingRuler degraded = new ShapingRuler(store, face -> null);

            // Every one of these is COMMON from end to end. COMMON characters have no script of
            // their own — they take their neighbours' — so a run made only of them reaches the
            // shaper with NO script, which the itemizer states as null rather than by inventing
            // one. A tag table that cannot answer for null throws here and only here, and every
            // other fixture in this class opens with a letter, which is why the whole suite passed
            // while a chart axis label, a percentage, a clock and a numeric field could not be
            // drawn at all.
            for (String neutral : new String[]{"42", "100%", " ", "1 + 1 = 2", "...", "12:30"}) {
                ShapedText line = ruler.shape(neutral, FONT);

                for (int g = 0; g < line.glyphCount(); g++) {
                    assertNotEquals(ShapedText.NO_GLYPH, line.glyphId(g),
                            () -> "a run with no script is still SHAPED, not degraded: " + neutral);
                }
                // The generic shaper picks what the cmap picks and kerns what stb kerns, so a run
                // with no script is a run with no width change — which also means a test that
                // asserted only "it did not throw" could not tell shaping from a silent fallback.
                assertEquals(unshaped(store, neutral, FONT), line.metrics().width(), EPS,
                        () -> "shaping moved a line with no strong character in it: " + neutral);
                assertEquals(line.metrics().width(),
                        degraded.shape(neutral, FONT).metrics().width(), EPS,
                        () -> "the two paths disagree about a neutral line: " + neutral);
            }

            // And from the middle of an ordinary string: the digits of "אבג 42 abc" are a bidi run
            // of their own, bounded by two scripts and containing neither.
            String mixed = HEB + " 42 abc";
            ShapedText line = ruler.shape(mixed, FONT);
            assertTrue(line.runs().size() > 1, "the digits are a run with no script of their own");
            assertEquals(mixed.length() + 1, line.caretCount(),
                    "every character is its own cluster, so every index is a caret stop");
        }
    }

    @Test
    void bidiSplitsByLevelAndTheBuilderReordersForTheScreen() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            // The Hebrew face has not been folded into this store, so these draw as .notdef — but
            // itemization, levels, reordering and caret geometry are all decided above the face,
            // and every assertion below holds identically whichever face answers.
            String text = "abc" + HEB;

            ShapedText line = ruler.shape(text, FONT);

            assertEquals(ShapedText.Direction.LTR, line.baseDirection(), "first strong is Latin");
            assertEquals(2, line.runs().size());
            assertEquals(0, line.runs().get(0).level());
            assertEquals(1, line.runs().get(1).level());
            assertTrue(line.runs().get(1).rtl());
            assertFalse(line.isSimple(), "a right-to-left run breaks the fast path");

            // Visual order: the Hebrew run is drawn to the right of the Latin one, and its
            // characters run the other way inside it. The glyph at the line's right edge is the
            // FIRST Hebrew character.
            ShapedText.Run hebrew = line.runs().get(1);
            assertEquals(3, hebrew.charStart());
            assertEquals(6, hebrew.charEnd());
            assertEquals(3, line.glyphCluster(hebrew.glyphEnd() - 1),
                    "alef is logically first and visually rightmost");
            assertEquals(5, line.glyphCluster(hebrew.glyphStart()),
                    "gimel is logically last and visually leftmost");

            // The split caret: index 3 is the seam, and it has two visual positions.
            assertTrue(line.caretAt(3).split(), "a direction boundary has two insertion points");
            assertTrue(line.caretAt(6).split());
            assertEquals(line.caretAt(3).upstreamX(), line.caretAt(6).upstreamX(), EPS);
            assertEquals(line.caretAt(3).downstreamX(), line.caretAt(6).downstreamX(), EPS);
            assertNotEquals(line.caretAt(3).downstreamIsStrong(),
                    line.caretAt(6).downstreamIsStrong(),
                    "the same two points, disagreeing about which one typing lands at");
        }
    }

    @Test
    void anRtlParagraphPutsTheLineTogetherFromTheOtherEnd() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);

            ShapedText line = ruler.shape("abc", FONT, ShapedText.Direction.RTL);

            assertEquals(ShapedText.Direction.RTL, line.baseDirection(), "the caller said so");
            // European text in an RTL paragraph takes level 2, never 0: an even level ABOVE the
            // paragraph's. The run still occupies [0, width] — right-to-left text is not drawn at
            // negative x, it fills the same box from the other end.
            assertEquals(1, line.runs().size());
            assertEquals(2, line.runs().get(0).level());
            assertEquals(0, line.caretAt(3).strongX(), EPS,
                    "the logical end of an RTL paragraph is its left edge");
            assertEquals(line.metrics().width(), line.caretAt(0).strongX(), EPS,
                    "and a base-direction keystroke at logical 0 lands at the right edge");
        }
    }

    // ============================================================================================
    // The memo.
    // ============================================================================================

    @Test
    void theMemoReturnsTheSameValueAndTheEpochThrowsItAway() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);

            ShapedText first = ruler.shape("hello", FONT);

            // A held value is current the moment it is made, which is not automatic: resolving a
            // face is itself one of the things that moves the epoch, so a value stamped before its
            // own resolution would be born stale and re-shape on every frame forever.
            assertTrue(first.matches("hello", FONT, ShapedText.Direction.LTR, ruler));
            assertEquals(store.epoch(), first.epoch());
            assertNotEquals(0, first.epoch(), "a ruler that resolves a face never stamps 0");

            // Same key, same answer: this is the whole point for a caller with nowhere to hold it.
            assertSame(first, ruler.shape("hello", FONT), "the transient caller pays once");
            // Different keys. Neither of these resolves a new face, so the epoch does not move and
            // `first` stays in the memo alongside them.
            assertNotSame(first, ruler.shape("hello!", FONT), "the text is part of the key");
            assertNotSame(first, ruler.shape("hello", FONT, ShapedText.Direction.RTL),
                    "and so is the direction");
            assertSame(first, ruler.shape("hello", FONT), "still current, still memoized");

            // Now the invalidation. Asking for bold parses a face that was not resident, which is
            // a residency change: it moves the epoch, which invalidates every value already handed
            // out and empties the memo. Nothing subscribed to anything for this to work.
            long before = store.epoch();
            ShapedText bold = ruler.shape("hello", FONT.bold());
            assertNotSame(first, bold, "the font is part of the key too");
            assertNotEquals(before, store.epoch(), "a face parsed is an epoch moved");
            assertFalse(first.matches("hello", FONT, ShapedText.Direction.LTR, ruler),
                    "the held value went stale");
            assertNotSame(first, ruler.shape("hello", FONT), "and the memo did not serve it");
        }
    }

    @Test
    void contentScaleIsNotInTheKeyAndCannotBeReachedFromHere() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            ShapedText line = ruler.shape("hello", FONT);
            long epoch = store.epoch();

            // There is deliberately no way to tell this ruler about a monitor change, and that is
            // the promise: positions are unquantized logical points in font units, so a window
            // crossing a display boundary re-rasterizes glyph bitmaps and re-shapes nothing.
            // Shaping the same string again must hand back the very same object.
            assertSame(line, ruler.shape("hello", FONT));
            assertEquals(epoch, store.epoch());
        }
    }

    // ============================================================================================
    // The degraded path: ADR 031 decision 4, and not optional.
    // ============================================================================================

    @Test
    void withoutAShaperEveryClusterIsGlyphlessAndLatinKeepsTodaysWidthExactly() {
        try (FontStore store = new FontStore()) {
            // Exactly what a missing native does one branch further in: no face has a shaper.
            ShapingRuler degraded = new ShapingRuler(store, face -> null);
            String text = "Waltz, bad nymph";

            ShapedText line = degraded.shape(text, FONT);

            for (int g = 0; g < line.glyphCount(); g++) {
                assertEquals(ShapedText.NO_GLYPH, line.glyphId(g),
                        "painting falls back to the per-code-point path that produced these");
            }
            // The point of decision 4: Latin, Greek, Cyrillic and CJK keep working EXACTLY as
            // today. Not approximately — the degraded walk keeps kerning rather than inheriting
            // the interface's per-cluster default, so this is the same float the pre-shaping
            // per-code-point walk produces, taken from that walk and not from the ruler.
            assertEquals(unshaped(store, text, FONT), line.metrics().width(), EPS);
            assertEquals(text.length(), line.glyphCount(), "one cluster per character here");
            assertTrue(line.isSimple(), "still one LTR run");

            // And it is a real ShapedText: every geometric question still answers.
            assertEquals(text.length() + 1, line.caretCount());
            assertEquals(0, line.caretAt(0).strongX(), EPS);
            assertEquals(line.metrics().width(), line.caretAt(text.length()).strongX(), EPS);
            assertEquals(1, line.selection(0, text.length()).size());
        }
    }

    @Test
    void withoutAShaperBidiIsStillReorderedAndTheCaretIsStillCorrect() {
        try (FontStore store = new FontStore()) {
            ShapingRuler degraded = new ShapingRuler(store, face -> null);
            String text = "abc" + HEB;

            ShapedText line = degraded.shape(text, FONT);

            // Reordering costs nothing here because the builder does it for every producer, and
            // it is what keeps bidi caret and selection geometry right on a machine with no
            // native at all. A missing native narrows what can be DRAWN, not where a caret goes.
            assertEquals(2, line.runs().size());
            assertEquals(1, line.runs().get(1).level());
            assertEquals(5, line.glyphCluster(line.runs().get(1).glyphStart()),
                    "the RTL run is still emitted leftmost-first");
            assertTrue(line.caretAt(3).split());

            // Selecting across the direction boundary is still two boxes with untouched text
            // between them, which is the thing a single rectangle cannot express.
            List<ShapedText.Span> boxes = line.selection(2, 5);
            assertEquals(2, boxes.size());
            assertTrue(boxes.get(0).x1() < boxes.get(1).x0(), "the unselected gimel sits between");
        }
    }

    @Test
    void aZeroWidthFormatCharacterIsChargedNothingOnEitherPath() {
        try (FontStore store = new FontStore()) {
            ShapingRuler shaping = new ShapingRuler(store);
            ShapingRuler degraded = new ShapingRuler(store, face -> null);

            // U+FE0F, the emoji variation selector, and the joiner beside it. Roboto has no glyph
            // for the selector, so the cmap answers .notdef — a real, drawable glyph with a real
            // 0.44 em advance — and any walk that does not filter it ABOVE the cmap charges that
            // box for a character nothing ever draws. measure() filters it, both paint loops
            // filter it, and HarfBuzz hides it itself, so the degraded walk is the one place the
            // filter has to be written out and the one place leaving it out is invisible on Latin:
            // the line comes out one box too wide and paints with a hole where the box went.
            for (String text : new String[]{"a\uFE0Fb", "x\uFE0F\uFE0Fy", "\uFE0F", "a\u200Db"}) {
                float measured = unshaped(store, text, FONT);
                assertEquals(measured, degraded.shape(text, FONT).metrics().width(), EPS,
                        () -> "the degraded line is not the width it measures: " + escaped(text));
                assertEquals(measured, shaping.shape(text, FONT).metrics().width(), EPS,
                        () -> "the shaped line is not the width it measures: " + escaped(text));
            }
        }
    }

    /** Readable in a failure message: these fixtures are made of characters with no ink. */
    private static String escaped(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c < 0x80 ? String.valueOf(c) : String.format("\\u%04X", (int) c));
        }
        return out.toString();
    }

    @Test
    void aFaceThatCannotBeShapedDegradesAloneAndNotTheWholeLine() {
        try (FontStore store = new FontStore()) {
            // The mixed case the NO_GLYPH sentinel exists for: one run shapes, one does not. A
            // whole-value "is this shaped" flag could not express it at all.
            StbFont latinFace = store.resolve(FONT);
            ShapingRuler mixed = new ShapingRuler(store,
                    face -> face == latinFace ? null : face.shaper());
            String text = "abc" + CYR;

            ShapedText line = mixed.shape(text, FONT);

            assertEquals(2, line.runs().size());
            ShapedText.Run latin = line.runs().get(0);
            for (int g = latin.glyphStart(); g < latin.glyphEnd(); g++) {
                assertEquals(ShapedText.NO_GLYPH, line.glyphId(g));
            }
            // Same face, same store — the Cyrillic run resolves to the same StbFont, so with this
            // stub both runs degrade. What is asserted is that the value is still coherent: the
            // clusters tile, the width is the measured one, and nothing threw.
            assertEquals(text.length() + 1, line.caretCount());
            assertEquals(store.measure(FONT, text).width(), line.metrics().width(), EPS);
        }
    }

    @Test
    void anEmptyLineShapesToGeometryWithNoRunsAndNoGlyphs() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);

            ShapedText line = ruler.shape("", FONT);

            assertEquals(0, line.glyphCount());
            assertEquals(0, line.runs().size());
            assertEquals(1, line.caretCount(), "an empty line still has one place for a caret");
            assertEquals(0, line.metrics().width(), EPS);
            // The vertical band is the primary face's and is what a widget lays out against, so it
            // is not zero just because the line is.
            assertTrue(line.metrics().lineHeight() > 0);
        }
    }

    @Test
    void theShapedWidthIsTheSumOfTheAdvancesAndTheLogicalAxisAgreesWithIt() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            String text = "abc" + CYR;

            ShapedText line = ruler.shape(text, FONT);

            float summed = 0;
            for (int g = 0; g < line.glyphCount(); g++) {
                summed += line.glyphAdvance(g);
            }
            assertEquals(summed, line.metrics().width(), EPS);
            // advanceTo is a budget over the whole line and has to reach the same total, whatever
            // order the glyphs were laid down in.
            assertEquals(line.metrics().width(), line.advanceTo(text.length()), EPS);
            assertEquals(0, line.advanceTo(0), EPS);
        }
    }

    // ============================================================================================
    // Native lifetime.
    // ============================================================================================

    @Test
    void aFacesShaperIsBuiltOnceAndDiesWithTheFace() {
        FontStore store = new FontStore();
        StbFont face = store.resolve(FONT);

        HarfBuzzShaper.Handle first = face.shaper();
        assertSame(first, face.shaper(), "built lazily, once, and memoized either way");

        store.close();
        assertTrue(face.isClosed());
        // The blob wraps the face's own ByteBuffer without owning it, so closing the face has to
        // destroy the HarfBuzz side FIRST. If it did not, this second close would be a double free
        // of the font bytes rather than a no-op.
        face.close();
    }

    @Test
    void aTenRunLineIsEncodedOnceAndShapedInOneBufferHoweverManyRunsItHas() {
        Assumptions.assumeTrue(HarfBuzzShaper.isAvailable(),
                "no native, no shaping calls to count");
        try (FontStore store = new FontStore(); ShapingRuler ruler = new ShapingRuler(store)) {
            // Five Latin words and five Cyrillic ones, alternating: one bidi run, split by script
            // into ten, all in Roboto. The run count is what this pins the cost model against,
            // and it is asserted rather than assumed so that an itemizer that stopped splitting
            // here could not make the counts below pass by shaping less.
            String text = "ab БВ cd БВ ef БВ gh БВ ij БВ";
            HarfBuzzShaper.Session session = ruler.session();

            ShapedText line = ruler.shape(text, FONT);

            assertEquals(10, line.runs().size(), "the fixture must exercise ten shaping calls");
            assertNotEquals(ShapedText.NO_GLYPH, line.glyphId(0),
                    "the runs degraded, so nothing was counted");
            // Ten runs, one native copy of the paragraph and one buffer: the two numbers the old
            // path paid ten times over, once per hb_buffer_create and once per re-encode of the
            // whole context. A count of ten here is the regression this test exists to catch.
            assertEquals(1, session.encodes, "the paragraph is encoded once, not once per run");
            assertEquals(1, session.buffersCreated, "one buffer, cleared between runs");

            // A second paragraph costs one more encode and no more buffers; a memo hit costs
            // neither.
            ruler.shape("БВ ab", FONT);
            assertEquals(2, session.encodes);
            assertEquals(1, session.buffersCreated);
            ruler.shape(text, FONT);
            assertEquals(2, session.encodes, "a memo hit shapes nothing");
        }
    }

    @Test
    void aClosedRulerStillAnswersAndClosesAgainWithoutComplaint() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            ShapedText before = ruler.shape("abc", FONT);
            assertTrue(before.glyphCount() > 0);

            ruler.close();
            ruler.close(); // a second close is a no-op, as every native owner here has it

            // The buffers are gone, so a run can no longer be shaped and takes the degraded path:
            // the same value the ruler gives on a machine with no native, and never an exception
            // from a widget painting its last frame while the backend goes away.
            ShapedText after = ruler.shape("abd", FONT);
            assertEquals(3, after.glyphCount());
            for (int g = 0; g < after.glyphCount(); g++) {
                assertEquals(ShapedText.NO_GLYPH, after.glyphId(g));
            }
            assertEquals(unshaped(store, "abd", FONT), after.metrics().width(), EPS);
        }
    }

    @Test
    void scriptTagsAreTheCapitalisedConstantsAndNotHandBuiltLowercase() {
        // The trap that costs a day: a HarfBuzz script tag is the ISO 15924 code with its first
        // letter CAPITALISED. A hand-built lowercase "deva" is not a registered script, so
        // HarfBuzz silently selects the generic shaper and Devanagari comes out unreordered and
        // unligated with nothing raised anywhere.
        int deva = HarfBuzzShaper.scriptTag(Character.UnicodeScript.DEVANAGARI);
        assertEquals(('D' << 24) | ('e' << 16) | ('v' << 8) | 'a', deva,
                "capital D: the lowercase spelling reaches the generic shaper in silence");
        assertEquals(('A' << 24) | ('r' << 16) | ('a' << 8) | 'b',
                HarfBuzzShaper.scriptTag(Character.UnicodeScript.ARABIC));
        assertEquals(('H' << 24) | ('e' << 16) | ('b' << 8) | 'r',
                HarfBuzzShaper.scriptTag(Character.UnicodeScript.HEBREW));
        assertNotEquals(deva, ('d' << 24) | ('e' << 16) | ('v' << 8) | 'a');
    }

    @Test
    void anUnmappedScriptFallsToTheGenericShaperRatherThanToARandomTag() {
        // Not in the table, and that has to be a defined answer: HB_SCRIPT_UNKNOWN is the generic
        // shaper, which is correct for a script with no contextual behaviour.
        int unknown = HarfBuzzShaper.scriptTag(Character.UnicodeScript.LINEAR_B);
        assertEquals(HarfBuzzShaper.scriptTag(Character.UnicodeScript.COMMON), unknown);
        // And null, which is a different question with the same answer: not "a script this table
        // lacks" but "a run with no script at all", which is what the itemizer reports for a
        // stretch of digits or punctuation. The table is a Map.ofEntries, and an immutable map
        // throws on a null key from every read path rather than answering absent — so this is a
        // guard standing in FRONT of the lookup, and a getOrDefault behind it would not do.
        assertEquals(unknown, HarfBuzzShaper.scriptTag(null));
    }

    @Test
    void metricsMatchMeasureForTheVerticalBandWhateverTheText() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            // The unshaped walk on purpose. The ruler's own measure() is now shape().metrics(), so
            // comparing the two through it would compare a record with itself; what has to hold is
            // that the pre-shaping walk and the shaped value report the SAME vertical band, since
            // a widget that laid out against one and painted against the other would draw its
            // caret and its underline a fraction of a point off the line everything else sits on.
            TextMetrics measured = store.measure(FONT, "abc");
            TextMetrics shaped = ruler.shape("abc", FONT).metrics();

            // A widget draws the caret, the selection box and the underline against one ascent and
            // one descent; the two paths must not offer it two.
            assertEquals(measured.ascent(), shaped.ascent(), EPS);
            assertEquals(measured.descent(), shaped.descent(), EPS);
            assertEquals(measured.lineHeight(), shaped.lineHeight(), EPS);
        }
    }

    /**
     * The decision this run kept, stated where it can fail: <b>{@code measure} is the shaped
     * answer.</b>
     *
     * <p>It was the per-code-point sum beside {@code shape}, and the two disagreed — which is the
     * whole reason a widget could lay out a line one width and paint it another. Both halves are
     * asserted: {@code measure} equals {@code shape} for every string, and it differs from the
     * unshaped walk exactly where shaping changes a width. A one-sided check would pass a build
     * where {@code measure} had been quietly put back.
     */
    @Test
    void measureAnswersFromShapeAndNotFromThePerCodePointWalk() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);

            for (String text : new String[]{"Waltz, bad nymph", "office", "fi", HEB, CYR, "42"}) {
                assertEquals(ruler.shape(text, FONT).metrics(), ruler.measure(text, FONT),
                        () -> "measure() is not the shaped answer for: " + text);
            }
            // And it really moved: "office" is the enumerated ligature, so the shaped width is
            // strictly below the walk's. If these were equal, measure() would have gone back to
            // summing code points and every assertion above would still pass.
            assertTrue(ruler.measure("office", FONT).width() < unshaped(store, "office", FONT),
                    "measure() reported the unshaped width, so nothing is being shaped");
        }
    }

    /**
     * The one width this ruler answers <b>without</b> shaping, and the reason it exists: a caller
     * scanning every line of a document to size a scroll extent asks about far more text than it
     * draws, and routing that through the memo re-shapes the document on every keystroke.
     *
     * <p>Both halves are checked because either alone passes a build with the bug in it. That it
     * equals the unshaped walk says it did not shape; that it differs from {@code measure} says the
     * walk and the shaping really do disagree here, so the first assertion is not a tautology.
     */
    @Test
    void scanWidthIsTheUnshapedWalkAndNotTheShapedWidth() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);

            assertEquals(unshaped(store, "office", FONT), ruler.scanWidth("office", FONT), EPS,
                    "the scan is supposed to be the walk, and it shaped instead");
            assertTrue(ruler.measure("office", FONT).width() < ruler.scanWidth("office", FONT),
                    "ffi ligates, so the two answers differ and this fixture is a fixture");
            // Same tolerance as measure(), for the same reason: a widget with no text yet hands
            // over whatever it has, and a layout pass is not the place to throw.
            assertEquals(0, ruler.scanWidth(null, FONT), EPS);
        }
    }

    /**
     * <b>Scanning leaves no trace in the memo.</b> This is the whole point of the method and the
     * half that a width comparison cannot see: the memo is process-wide and access-ordered, so a
     * scan that walked its keys would not merely miss on its own strings, it would evict every
     * caption on the screen and make widgets that did nothing repaint cold. Far more strings are
     * scanned here than the memo holds, and the value shaped before them is still the very same
     * object afterwards.
     */
    @Test
    void aScanOverMoreStringsThanTheMemoHoldsEvictsNothing() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            ShapedText held = ruler.shape("hello", FONT);

            for (int i = 0; i < 4096; i++) {
                ruler.scanWidth("a line of a document, number " + i, FONT);
            }

            assertSame(held, ruler.shape("hello", FONT),
                    "the scan went through the memo and threw the screen out of it");
        }
    }

    @Test
    void measuringNullIsStillTheEmptyLineAndNotAnException() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);

            // measure() used to go straight to the store, which took a null as the empty string;
            // shape() rejects one, because a null paragraph has no direction to resolve. Routing
            // one through the other would have turned a widget with no text yet into a
            // NullPointerException in the middle of a layout pass, which is why the tolerance is
            // restated rather than inherited.
            assertEquals(0, ruler.measure(null, FONT).width(), EPS);
            assertEquals(ruler.measure("", FONT), ruler.measure(null, FONT),
                    "a null line and an empty one are the same line, vertical band included");
        }
    }
}

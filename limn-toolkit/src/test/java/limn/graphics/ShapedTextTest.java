package limn.graphics;

import limn.graphics.ShapedText.Affinity;
import limn.graphics.ShapedText.Caret;
import limn.graphics.ShapedText.Direction;
import limn.graphics.ShapedText.Position;
import limn.graphics.ShapedText.Run;
import limn.graphics.ShapedText.Span;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ShapedText} against the geometry it promises, with no backend, no native, no font
 * file and no window: every value here is hand-built through the public {@code Builder} or
 * {@code uniform} factory with advances chosen so that every expected number is an exact integer.
 *
 * <p>That is the point of the type rather than a convenience of the test. Bidi caret behaviour is
 * the part of a toolkit that looks right in a screenshot while being wrong, so it is pinned by
 * stating logical order in and expected visual positions out, over cases worked by hand. Every
 * expectation below is derived from the frozen API's stated semantics; none of it was read off an
 * implementation.
 */
class ShapedTextTest {

    private static final float EPS = 1e-4f;

    /** One advance for every cluster in every fixture: expected geometry stays exact integers. */
    private static final float ADV = 10f;

    private static final Font FONT = Font.of(16);
    private static final Font OTHER_FONT = Font.of(18);

    /** Face ids are opaque to this type; two different ones are all a test needs. */
    private static final int FACE = 7;
    private static final int FACE_B = 9;

    /** alef, bet, gimel: three strong right-to-left characters, one char apiece. */
    private static final String HEB = "אבג";

    /** alef, bet, gimel, dalet, he. */
    private static final String HEB5 = "אבגדה";

    private static final String ELLIPSIS = "…";

    // ------------------------------------------------------------------- fixtures

    /**
     * The fixture the frozen spec states its six editor operations against. Base direction LTR,
     * one face, every cluster 10pt:
     *
     * <pre>
     * text      = "abc" + alef bet gimel                       length 6
     * visual:     a[0,10) b[10,20) c[20,30) | gimel[30,40) bet[40,50) alef[50,60)
     * charIndex:    0        1        2     |     5            4          3
     * </pre>
     *
     * <p>The right-to-left run's glyphs are fed in the order a shaper emits them, which for a
     * right-to-left run is already that run's own left-to-right visual order: gimel, bet, alef.
     */
    private static ShapedText latinThenHebrew() {
        return ShapedText.builder("abc" + HEB, FONT, Direction.LTR, 6)
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, 3, 0)
                .glyph(101, 0, ADV, 0, 0)
                .glyph(102, 1, ADV, 0, 0)
                .glyph(103, 2, ADV, 0, 0)
                .run(FACE, 3, 6, 1)
                .glyph(203, 5, ADV, 0, 0)
                .glyph(202, 4, ADV, 0, 0)
                .glyph(201, 3, ADV, 0, 0)
                .build();
    }

    /** One left-to-right run in one face: the fast path, one glyph and one cluster per char. */
    private static ShapedText latin(String text) {
        ShapedText.Builder b = ShapedText.builder(text, FONT, Direction.LTR, text.length())
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, text.length(), 0);
        for (int i = 0; i < text.length(); i++) {
            b.glyph(100 + i, i, ADV, 0, 0);
        }
        return b.build();
    }

    /**
     * A right-to-left paragraph of {@code n} Hebrew characters and nothing else: one run at level
     * 1, so the first character of the string is the rightmost ink on the line.
     */
    private static ShapedText hebrew(String text) {
        ShapedText.Builder b = ShapedText.builder(text, FONT, Direction.RTL, text.length())
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, text.length(), 1);
        // Visual order inside the run is left to right, which for RTL is the last char first.
        for (int i = text.length() - 1; i >= 0; i--) {
            b.glyph(200 + i, i, ADV, 0, 0);
        }
        return b.build();
    }

    /**
     * A right-to-left paragraph with an embedded left-to-right run: levels 1 then 2, which rule L2
     * reorders to put the Latin on the visual left and the Hebrew on the visual right. The only
     * fixture here whose runs come back with their character ranges out of ascending order.
     */
    private static ShapedText hebrewThenLatin() {
        return ShapedText.builder("אבcd", FONT, Direction.RTL, 4)
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, 2, 1)
                .glyph(201, 1, ADV, 0, 0)
                .glyph(200, 0, ADV, 0, 0)
                .run(FACE, 2, 4, 2)
                .glyph(103, 2, ADV, 0, 0)
                .glyph(104, 3, ADV, 0, 0)
                .build();
    }

    /** "a", a cluster that consumes no advance, "b": one left-to-right run, width 20. */
    private static ShapedText withZeroAdvanceCluster() {
        return ShapedText.builder("a\u200Db", FONT, Direction.LTR, 3)
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, 3, 0)
                .glyph(101, 0, ADV, 0, 0)
                .glyph(ShapedText.NO_GLYPH, 1, 0, 0, 0)
                .glyph(102, 2, ADV, 0, 0)
                .build();
    }

    private static ShapedText empty() {
        return ShapedText.builder("", FONT, Direction.LTR, 0)
                .lineMetrics(8, 2, 12)
                .build();
    }

    /** A ruler whose only interesting property is its epoch. */
    private static TextRuler rulerAt(long epoch) {
        return new TextRuler() {
            @Override
            public TextMetrics measure(String text, Font font) {
                return new TextMetrics(ADV * text.length(), 8, 2, 12);
            }

            @Override
            public long epoch() {
                return epoch;
            }
        };
    }

    private static ShapedText.Builder skeleton(String text) {
        return ShapedText.builder(text, FONT, Direction.LTR, text.length()).lineMetrics(8, 2, 12);
    }

    // ============================================================================================
    // The six editor operations. These are the acceptance criteria for the API: each one is the
    // frozen spec's own call sequence, against the frozen spec's own worked numbers.
    // ============================================================================================

    /**
     * Operation 1, split caret. An index on a direction boundary has two visual positions, and
     * which one the next character lands at depends on the direction of what is typed.
     */
    @Test
    void splitCaretHasTwoPositionsAndTheyDisagreeAboutWhichIsStrong() {
        ShapedText line = latinThenHebrew();

        Caret inLatin = line.caretAt(1);
        assertEquals(10, inLatin.strongX(), EPS);
        assertEquals(10, inLatin.weakX(), EPS);
        assertFalse(inLatin.split(), "off a boundary the two positions are the same point");

        // Index 3: the character before it is Latin (level 0, the base parity) and draws its
        // trailing edge at 30; the character at it is Hebrew and draws its leading edge at 60.
        Caret atSeam = line.caretAt(3);
        assertEquals(30, atSeam.upstreamX(), EPS);
        assertEquals(60, atSeam.downstreamX(), EPS);
        assertFalse(atSeam.downstreamIsStrong());
        assertEquals(30, atSeam.strongX(), EPS);
        assertEquals(60, atSeam.weakX(), EPS);
        assertTrue(atSeam.split());

        Caret inHebrew = line.caretAt(4);
        assertEquals(50, inHebrew.strongX(), EPS);
        assertEquals(50, inHebrew.weakX(), EPS);
        assertFalse(inHebrew.split());

        // Index 6: upstream is gimel's trailing edge, which for RTL is its LEFT edge, 30.
        // Downstream is past the end of the text, so it is the paragraph's own end edge, which for
        // an LTR paragraph is the right edge of the line, 60.
        Caret atEnd = line.caretAt(6);
        assertEquals(30, atEnd.upstreamX(), EPS);
        assertEquals(60, atEnd.downstreamX(), EPS);
        assertTrue(atEnd.downstreamIsStrong());
        assertEquals(60, atEnd.strongX(), EPS);
        assertEquals(30, atEnd.weakX(), EPS);
        assertTrue(atEnd.split());

        // 3 and 6 occupy the SAME pair of points and disagree only about which is strong. Typing
        // Latin at 3 puts the character at 30; typing Latin at 6 puts it at 60. A two-field Caret
        // could not tell them apart, which is why there is a third component.
        assertEquals(atSeam.upstreamX(), atEnd.upstreamX(), EPS);
        assertEquals(atSeam.downstreamX(), atEnd.downstreamX(), EPS);
        assertNotEquals(atSeam.downstreamIsStrong(), atEnd.downstreamIsStrong());
    }

    /**
     * Operation 2, discontiguous selection. A range contiguous in the string is not contiguous on
     * the line once it crosses a direction boundary.
     */
    @Test
    void discontiguousSelectionIsTwoBoxesWithUnselectedTextBetweenThem() {
        ShapedText line = latinThenHebrew();

        // Chars 2, 3, 4 are c, alef and bet. c draws at [20,30) and alef+bet at [40,60); gimel,
        // which is NOT selected, draws at [30,40) between them. The smallest single rectangle
        // covering both would highlight a character the user did not select.
        List<Span> boxes = line.selection(2, 5);
        assertEquals(2, boxes.size());
        assertEquals(20, boxes.get(0).x0(), EPS);
        assertEquals(30, boxes.get(0).x1(), EPS);
        assertEquals(40, boxes.get(1).x0(), EPS);
        assertEquals(60, boxes.get(1).x1(), EPS);
        assertEquals(10, boxes.get(0).width(), EPS);
        assertEquals(20, boxes.get(1).width(), EPS);
    }

    /**
     * Operation 3, visual arrow movement. The frozen spec's own two-press table, plus the return
     * trip that an {@code int}-taking API provably cannot express.
     */
    @Test
    void visualArrowMovementCrossesTheBoundaryAndComesBack() {
        ShapedText line = latinThenHebrew();

        Position start = new Position(5, Affinity.DOWNSTREAM);
        assertEquals(40, line.caretX(start), EPS);

        Position first = line.caretLeft(start);
        assertEquals(new Position(6, Affinity.UPSTREAM), first);
        assertEquals(30, line.caretX(first), EPS);

        Position second = line.caretLeft(first);
        assertEquals(new Position(2, Affinity.DOWNSTREAM), second);
        assertEquals(20, line.caretX(second), EPS);

        // Handed the bare integer 6, an int-taking form can only read that index's strong
        // position, 60, and step to 50 - so the caret walks left, jumps to the far right of the
        // line, then walks left again. Position carries the side, so it does not.
        assertEquals(60, line.caretAt(6).strongX(), EPS);

        assertEquals(new Position(5, Affinity.DOWNSTREAM), line.caretRight(first));
        assertEquals(40, line.caretX(line.caretRight(first)), EPS);
    }

    /**
     * Operation 4, boundary hit-test: two different insertion points at one pixel, separated
     * because the resolution runs through the cluster under the point and reports the side.
     */
    @Test
    void boundaryHitTestResolvesTwoInsertionPointsThatShareOnePixel() {
        ShapedText line = latinThenHebrew();

        // 29 is in the trailing half of c, which reads left to right, so the trailing half is the
        // RIGHT half.
        Position left = line.hitTest(29);
        assertEquals(new Position(3, Affinity.UPSTREAM), left);
        assertEquals(30, line.caretX(left), EPS);

        // 31 is in the trailing half of gimel, which reads right to left, so the trailing half is
        // the LEFT half.
        Position right = line.hitTest(31);
        assertEquals(new Position(6, Affinity.UPSTREAM), right);
        assertEquals(30, line.caretX(right), EPS);

        assertNotEquals(left, right);
        assertEquals(line.caretX(left), line.caretX(right), EPS);

        // A binary search over caret x values could not separate these two: both sit at 30.
        assertEquals(3, line.indexAt(29));
        assertEquals(6, line.indexAt(31));
    }

    /**
     * Operation 5, IME preedit mapping. One shaping of the composed line, and the multi-box
     * selection is the primitive the underline and the conversion highlight are both made of.
     */
    @Test
    void imePreeditUnderlineAndCaretComeFromOneShapingOfTheComposedLine() {
        // committed "abc" with the caret at 3; the preedit is three Hebrew characters. Measuring
        // prefix, preedit and suffix separately would be three wrong numbers, because Arabic and
        // Indic join across those seams.
        int c = 3;
        String preedit = HEB;
        ShapedText line = latinThenHebrew();

        List<Span> underline = line.selection(c, c + preedit.length());
        assertEquals(1, underline.size());
        assertEquals(30, underline.get(0).x0(), EPS);
        assertEquals(60, underline.get(0).x1(), EPS);

        // The block being converted is a sub-range of the SAME shaping, so it cannot drift from
        // the run it sits in.
        List<Span> focus = line.selection(c + 1, c + 2);
        assertEquals(1, focus.size());
        assertEquals(40, focus.get(0).x0(), EPS);
        assertEquals(50, focus.get(0).x1(), EPS);

        // UPSTREAM is not arbitrary: the preedit caret trails the text just typed, so the next
        // character of the same script appears where the caret is drawn - the left end of a
        // right-to-left run, 30. DOWNSTREAM would draw it at 60, where a Latin keystroke lands.
        int preeditCaret = preedit.length();
        assertEquals(30, line.caretX(new Position(c + preeditCaret, Affinity.UPSTREAM)), EPS);
        assertEquals(60, line.caretX(new Position(c + preeditCaret, Affinity.DOWNSTREAM)), EPS);
    }

    /**
     * Operation 6, bidi ellipsis: {@code fitEnd} says where to cut, and shaping the concatenation
     * puts the ellipsis on the visual left of a right-to-left line with no branch anywhere.
     */
    @Test
    void bidiEllipsisCutsWithFitEndAndDrawsOnTheVisualLeft() {
        ShapedText line = hebrew(HEB5);
        float available = 35;
        assertEquals(50, line.metrics().width(), EPS);
        assertTrue(line.metrics().width() > available);

        float ellipsisWidth = ADV;
        assertEquals(2, line.fitEnd(0, available - ellipsisWidth));

        // Label re-shapes text.substring(0, cut) + ELLIPSIS, because the characters at the cut had
        // joined to the ones now removed. In an RTL paragraph the ellipsis is logically last and
        // therefore the LEFTMOST ink: it falls out of shaping the concatenation, not out of
        // positioning two pieces.
        ShapedText shown = ShapedText.builder(HEB5.substring(0, 2) + ELLIPSIS, FONT,
                        Direction.RTL, 3)
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, 3, 1)
                .glyph(300, 2, ADV, 0, 0)
                .glyph(201, 1, ADV, 0, 0)
                .glyph(200, 0, ADV, 0, 0)
                .build();
        assertEquals(2, shown.glyphCluster(0), "the ellipsis is the leftmost glyph");
        assertEquals(0, shown.glyphX(0), EPS);
        assertEquals(0, shown.glyphCluster(2), "the first character of the string is rightmost");
        assertEquals(20, shown.glyphX(2), EPS);
        assertEquals(30, shown.metrics().width(), EPS);
    }

    /**
     * The wrap that shares the ellipsis's primitive: one shaping walked with {@code fitEnd}, whose
     * {@code from} anchor is what keeps a greedy break linear instead of quadratic.
     */
    @Test
    void greedyWrapWalksOneShapingAndTakesAClusterWhenNothingFits() {
        ShapedText para = latin("abcdef");

        assertEquals(2, para.fitEnd(0, 25));
        assertEquals(4, para.fitEnd(2, 25), "anchored at from, not restarted at zero");
        assertEquals(6, para.fitEnd(4, 1000));

        // A word too wide for its line: not even one cluster fits, fitEnd returns from, and the
        // caller has to take one cluster anyway. It takes it from the stop table, so no second
        // rule for finding cluster boundaries can drift from the first.
        assertEquals(0, para.fitEnd(0, 5));
        assertEquals(1, para.caretIndex(para.caretOrdinal(0) + 1));

        // On a line whose clusters are longer than one char the stop table is the only thing that
        // gets this right: taking start + 1 would cut inside a grapheme cluster.
        ShapedText joined = ShapedText.uniform("e\u0301x", FONT, ADV,
                new TextMetrics(0, 8, 2, 12), 0);
        assertEquals(0, joined.fitEnd(0, 5));
        assertEquals(2, joined.caretIndex(joined.caretOrdinal(0) + 1));
    }

    // ============================================================================================
    // The round-trip law: what hitTest returns is consumable by caretX and lands on a caret stop.
    // ============================================================================================

    @Test
    void hitTestRoundTripsThroughCaretXOntoTheClusterEdgeItChose() {
        ShapedText line = latinThenHebrew();

        for (int tenths = 0; tenths <= 600; tenths++) {
            float x = tenths / 10f;
            if (Math.abs(x % ADV - ADV / 2) < EPS) {
                continue; // exactly a cluster midpoint: which half it belongs to is not stated
            }
            Position p = line.hitTest(x);
            float back = line.caretX(p);

            // caretX is defined as caretAt(charIndex).x(affinity), so the two cannot drift.
            assertEquals(line.caretAt(p.charIndex()).x(p.affinity()), back, EPS,
                    "caretX must be the stored side of caretAt at x=" + x);
            // It lands on a caret stop: every stop on this fixture is a multiple of the advance.
            assertEquals(0, Math.round(back) % (int) ADV, "off a cluster edge at x=" + x);
            assertTrue(back >= 0 && back <= line.metrics().width(), "outside the line at x=" + x);
            // And it lands on an edge of the cluster the click was inside, never further.
            assertTrue(Math.abs(back - x) <= ADV / 2 + EPS, "jumped clusters at x=" + x);
            // The convenience form drops the side and nothing else.
            assertEquals(p.charIndex(), line.indexAt(x), "indexAt disagrees at x=" + x);
            // Deterministic: a drag that repaints at frame rate cannot flicker between answers.
            assertEquals(p, line.hitTest(x));
        }
    }

    @Test
    void clusterBoxesAreHalfOpenOnTheRightSoASeamAlwaysResolvesTheSameWay() {
        ShapedText line = latinThenHebrew();

        // x exactly on the seam belongs to the cluster on its right, which here is gimel: it is a
        // right-to-left cluster, 30 is in its LEFT half, and the left half of RTL is the trailing
        // half - so the caret goes to gimel's trailing edge, index 6.
        assertEquals(new Position(6, Affinity.UPSTREAM), line.hitTest(30));
        // A hair to the left of the seam is still inside c.
        assertEquals(new Position(3, Affinity.UPSTREAM), line.hitTest(29.999f));
        // The seam at 20 belongs to c, in its leading half, which for LTR is its left half.
        assertEquals(new Position(2, Affinity.DOWNSTREAM), line.hitTest(20));
        assertEquals(0, line.hitTest(0).charIndex());
        assertEquals(Affinity.DOWNSTREAM, line.hitTest(0).affinity());
    }

    @Test
    void hitTestOutsideTheLineClampsToTheNearestClusterNotToTheLogicalEnd() {
        ShapedText line = latinThenHebrew();

        Position beforeStart = line.hitTest(-40);
        assertEquals(new Position(0, Affinity.DOWNSTREAM), beforeStart);
        assertEquals(0, line.caretX(beforeStart), EPS);

        // The nearest cluster to the right edge is alef, which is char 3, NOT the last character:
        // this line ends in the direction opposite the paragraph's. That is exactly why a caller
        // offering empty space to the right of the line has to compare against width() first
        // instead of leaning on this clamp.
        Position pastEnd = line.hitTest(400);
        assertEquals(3, pastEnd.charIndex());
        assertEquals(Affinity.DOWNSTREAM, pastEnd.affinity());
        assertEquals(60, line.caretX(pastEnd), EPS);
        assertNotEquals(line.text().length(), pastEnd.charIndex());
    }

    // ============================================================================================
    // caretLeft / caretRight: the full traversal in both directions, not one step.
    // ============================================================================================

    /**
     * Rightward across the whole mixed line. Index 3 appears TWICE with different sides - once at
     * x=30 walking out of the Latin run and once at x=60 at the visual right end - which is the
     * sequence an {@code int}-taking API cannot express, because the integer 3 does not say which
     * of its two points the caret is at.
     */
    @Test
    void caretRightTraversesTheWholeLineInVisualOrder() {
        ShapedText line = latinThenHebrew();

        Position[] expected = {
                new Position(1, Affinity.UPSTREAM),
                new Position(2, Affinity.UPSTREAM),
                new Position(3, Affinity.UPSTREAM),
                new Position(5, Affinity.DOWNSTREAM),
                new Position(4, Affinity.DOWNSTREAM),
                new Position(3, Affinity.DOWNSTREAM),
        };
        float[] expectedX = {10, 20, 30, 40, 50, 60};

        Position p = new Position(0, Affinity.DOWNSTREAM);
        assertEquals(0, line.caretX(p), EPS);
        for (int i = 0; i < expected.length; i++) {
            p = line.caretRight(p);
            assertEquals(expected[i], p, "step " + (i + 1) + " of the rightward traversal");
            assertEquals(expectedX[i], line.caretX(p), EPS, "x after step " + (i + 1));
        }
        assertEquals(p, line.caretRight(p), "the right end of the line is a fixed point");

        // The two visits to index 3 are the same insertion point at two different pixels.
        assertEquals(expected[2].charIndex(), expected[5].charIndex());
        assertNotEquals(expected[2].affinity(), expected[5].affinity());
    }

    @Test
    void caretLeftTraversesTheWholeLineInVisualOrder() {
        ShapedText line = latinThenHebrew();

        Position[] expected = {
                new Position(4, Affinity.UPSTREAM),
                new Position(5, Affinity.UPSTREAM),
                new Position(6, Affinity.UPSTREAM),
                new Position(2, Affinity.DOWNSTREAM),
                new Position(1, Affinity.DOWNSTREAM),
                new Position(0, Affinity.DOWNSTREAM),
        };
        float[] expectedX = {50, 40, 30, 20, 10, 0};

        Position p = new Position(3, Affinity.DOWNSTREAM);
        assertEquals(60, line.caretX(p), EPS);
        for (int i = 0; i < expected.length; i++) {
            p = line.caretLeft(p);
            assertEquals(expected[i], p, "step " + (i + 1) + " of the leftward traversal");
            assertEquals(expectedX[i], line.caretX(p), EPS, "x after step " + (i + 1));
        }
        assertEquals(p, line.caretLeft(p), "the left end of the line is a fixed point");
    }

    /**
     * The two are exact inverses <em>on the line</em> at every stop, which is what makes pressing
     * Right and then Left land the caret back on the pixel it started from.
     *
     * <p>They are inverses in <b>x</b> and not in {@link Position}, and that is not a weaker claim
     * so much as the only one that can be true: both rules name a cluster and take an edge of it,
     * so at a direction boundary the return trip arrives at the same point by the other index of
     * the two that share it. Asserting Position equality here would be asserting that a boundary
     * has one insertion point, which is the thing this whole type exists to deny.
     */
    @Test
    void caretLeftUndoesCaretRightAtEveryStop() {
        ShapedText line = latinThenHebrew();

        Position p = new Position(0, Affinity.DOWNSTREAM);
        for (int step = 0; step < 6; step++) {
            Position forward = line.caretRight(p);
            Position back = line.caretLeft(forward);
            assertEquals(line.caretX(p), line.caretX(back), EPS,
                    "caretLeft did not undo caretRight at step " + step);
            p = forward;
        }

        // And the one place the indices differ is the boundary, where the two stops that share a
        // point are 3 (leaving the Latin run) and 6 (the logical end of the line).
        Position atSeam = new Position(3, Affinity.UPSTREAM);
        Position returned = line.caretLeft(line.caretRight(atSeam));
        assertEquals(line.caretX(atSeam), line.caretX(returned), EPS);
        assertEquals(6, returned.charIndex(), "the same pixel, reached by the other index");
    }

    @Test
    void caretMovementStopsAtBothEndsSoAMultiLineCallerKnowsToChangeLine() {
        ShapedText line = latin("ab");

        Position leftEnd = new Position(0, Affinity.DOWNSTREAM);
        assertEquals(leftEnd, line.caretLeft(leftEnd));

        Position rightEnd = new Position(2, Affinity.UPSTREAM);
        assertEquals(rightEnd, line.caretRight(rightEnd));

        // And the one-character line moves once in each direction and no further.
        ShapedText one = latin("a");
        assertEquals(0, one.caretLeft(new Position(1, Affinity.UPSTREAM)).charIndex());
        assertEquals(1, one.caretRight(new Position(0, Affinity.DOWNSTREAM)).charIndex());
    }

    // ============================================================================================
    // selection: merging, gaps, the buffer form, and what is never emitted.
    // ============================================================================================

    @Test
    void selectionMergesAbuttingBoxesWithinARunAndAcrossRuns() {
        ShapedText line = latinThenHebrew();

        List<Span> withinRun = line.selection(0, 2);
        assertEquals(1, withinRun.size(), "two abutting Latin clusters are one box");
        assertEquals(0, withinRun.get(0).x0(), EPS);
        assertEquals(20, withinRun.get(0).x1(), EPS);

        List<Span> wholeLine = line.selection(0, 6);
        assertEquals(1, wholeLine.size(), "a whole-line selection is one box however many runs");
        assertEquals(0, wholeLine.get(0).x0(), EPS);
        assertEquals(60, wholeLine.get(0).x1(), EPS);

        // The seam at x=30 is where the LTR run ends and the RTL run begins. A translucent fill
        // must not double-blend along it, which is why touching boxes merge. The range has to
        // reach char 5 INCLUSIVE (gimel, the cluster drawn at [30,40)) for the two runs to touch
        // at all: [1,5) stops one cluster short and leaves the seam open, which is the next case.
        List<Span> acrossSeam = line.selection(1, 6);
        assertEquals(1, acrossSeam.size());
        assertEquals(10, acrossSeam.get(0).x0(), EPS);
        assertEquals(60, acrossSeam.get(0).x1(), EPS);

        // One cluster short of the seam, and the gap it leaves is gimel — a character the user did
        // not select. Merging these to the single box [10,60] would highlight it.
        List<Span> shortOfSeam = line.selection(1, 5);
        assertEquals(2, shortOfSeam.size(), "gimel is unselected and sits between the two");
        assertEquals(10, shortOfSeam.get(0).x0(), EPS);
        assertEquals(30, shortOfSeam.get(0).x1(), EPS);
        assertEquals(40, shortOfSeam.get(1).x0(), EPS);
        assertEquals(60, shortOfSeam.get(1).x1(), EPS);

        List<Span> rtlOnly = line.selection(3, 6);
        assertEquals(1, rtlOnly.size());
        assertEquals(30, rtlOnly.get(0).x0(), EPS);
        assertEquals(60, rtlOnly.get(0).x1(), EPS);
    }

    @Test
    void selectionDoesNotMergeAcrossAGenuineVisualGap() {
        ShapedText line = latinThenHebrew();

        // Chars 2 and 3 are c and alef, which draw at [20,30) and [50,60). Between them sit gimel
        // and bet, which are NOT in the range. Two boxes, ascending in x, not overlapping.
        List<Span> boxes = line.selection(2, 4);
        assertEquals(2, boxes.size());
        assertEquals(20, boxes.get(0).x0(), EPS);
        assertEquals(30, boxes.get(0).x1(), EPS);
        assertEquals(50, boxes.get(1).x0(), EPS);
        assertEquals(60, boxes.get(1).x1(), EPS);
        assertTrue(boxes.get(0).x1() < boxes.get(1).x0(), "the gap is real and stays open");
    }

    @Test
    void selectionOfAnEmptyOrInvertedRangeIsEmpty() {
        ShapedText line = latinThenHebrew();

        assertTrue(line.selection(3, 3).isEmpty(), "a caret is not a zero-width selection");
        assertTrue(line.selection(5, 2).isEmpty(), "an inverted range selects nothing");
        assertEquals(0, line.selection(3, 3, new float[4]));
        assertEquals(0, line.selection(5, 2, new float[4]));
    }

    @Test
    void selectionNeverEmitsAZeroWidthBox() {
        ShapedText line = withZeroAdvanceCluster();
        assertEquals(20, line.metrics().width(), EPS);

        // The middle cluster consumes no advance, so it contributes no box at all rather than a
        // band the fill could not show.
        assertTrue(line.selection(1, 2).isEmpty());
        assertEquals(0, line.selection(1, 2, new float[2]));

        // And it does not split the boxes on either side of it, because they still touch.
        List<Span> all = line.selection(0, 3);
        assertEquals(1, all.size());
        assertEquals(0, all.get(0).x0(), EPS);
        assertEquals(20, all.get(0).x1(), EPS);
    }

    @Test
    void selectionListIsUnmodifiable() {
        List<Span> boxes = latinThenHebrew().selection(2, 5);
        assertThrows(UnsupportedOperationException.class, () -> boxes.add(new Span(0, 1)));
        assertThrows(UnsupportedOperationException.class, () -> boxes.clear());
    }

    @Test
    void selectionIntoABufferAgreesWithTheListForm() {
        ShapedText line = latinThenHebrew();
        float[] out = new float[2 * line.runs().size()];
        assertEquals(4, out.length, "the exact bound is two floats per run");

        for (int start = 0; start <= 6; start++) {
            for (int end = start; end <= 6; end++) {
                List<Span> boxes = line.selection(start, end);
                int n = line.selection(start, end, out);
                assertEquals(boxes.size(), n, "box count for [" + start + "," + end + ")");
                for (int i = 0; i < n; i++) {
                    assertEquals(boxes.get(i).x0(), out[i * 2], EPS);
                    assertEquals(boxes.get(i).x1(), out[i * 2 + 1], EPS);
                }
            }
        }
    }

    @Test
    void selectionIntoAShortBufferThrowsRatherThanWritingWhatFits() {
        ShapedText line = latinThenHebrew();
        assertEquals(2, line.runs().size());

        // Writing fewer boxes than the selection has paints a band over some of the user's own
        // text and leaves the rest unhighlighted, which reads as a rendering glitch rather than as
        // the sizing bug it is.
        assertThrows(IllegalArgumentException.class, () -> line.selection(2, 5, new float[3]));
        assertThrows(IllegalArgumentException.class, () -> line.selection(0, 6, new float[0]));

        // The bound is 2 * runs().size(), not the number of boxes this particular range needs: a
        // buffer sized off one selection would then be too small for the next one.
        assertThrows(IllegalArgumentException.class, () -> line.selection(0, 1, new float[2]));

        // Exactly the bound is enough, and so is more.
        assertEquals(2, line.selection(2, 5, new float[4]));
        assertEquals(2, line.selection(2, 5, new float[16]));
    }

    @Test
    void selectionWritesOnlyThePairsItReports() {
        ShapedText line = latinThenHebrew();
        float[] out = {-1, -1, -1, -1};
        assertEquals(1, line.selection(0, 3, out));
        assertArrayEquals(new float[]{0, 30, -1, -1}, out, EPS);
    }

    // ============================================================================================
    // The pure-LTR fast path: this is nearly every string the toolkit draws, and it must stay
    // exactly as cheap and exactly as predictable as prefix arithmetic used to be.
    // ============================================================================================

    @Test
    void pureLtrLineIsSimpleAndIsOneRun() {
        ShapedText line = latin("abcd");

        assertTrue(line.isSimple());
        assertEquals(Direction.LTR, line.baseDirection());
        assertEquals(1, line.runs().size());

        Run run = line.runs().get(0);
        assertEquals(FACE, run.faceId());
        assertEquals(0, run.charStart());
        assertEquals(4, run.charEnd());
        assertEquals(0, run.glyphStart());
        assertEquals(4, run.glyphEnd());
        assertEquals(0, run.level());
        assertFalse(run.rtl());
    }

    @Test
    void pureLtrAccessorsAgreeWithNaivePrefixArithmetic() {
        ShapedText line = latin("abcd");
        assertEquals(40, line.metrics().width(), EPS);
        assertEquals(8, line.metrics().ascent(), EPS);
        assertEquals(2, line.metrics().descent(), EPS);
        assertEquals(12, line.metrics().lineHeight(), EPS);
        assertEquals(5, line.caretCount());

        float previous = -1;
        for (int i = 0; i <= 4; i++) {
            assertEquals(i, line.caretIndex(i));
            assertEquals(i, line.caretOrdinal(i));
            assertEquals(ADV * i, line.advanceTo(i), EPS);

            Caret c = line.caretAt(i);
            assertEquals(ADV * i, c.upstreamX(), EPS);
            assertEquals(ADV * i, c.downstreamX(), EPS);
            assertFalse(c.split(), "nothing on a monotone line is ever split");
            assertTrue(c.downstreamIsStrong(),
                    "on the fast path the two components are one caret stop copied twice, so the "
                            + "strong side is the downstream one and split() is exactly false");
            assertEquals(ADV * i, c.strongX(), EPS);
            assertEquals(ADV * i, c.weakX(), EPS);

            // Caret stops are monotone in the char index, which is the promise every fast path in
            // the toolkit rests on; and on this path advanceTo(i) IS caretAt(i).strongX().
            assertTrue(c.strongX() > previous, "caret x must increase with the index");
            previous = c.strongX();
            assertEquals(line.advanceTo(i), c.strongX(), EPS);

            // One box, always, because visual order and logical order agree.
            if (i > 0) {
                List<Span> boxes = line.selection(0, i);
                assertEquals(1, boxes.size());
                assertEquals(0, boxes.get(0).x0(), EPS);
                assertEquals(ADV * i, boxes.get(0).x1(), EPS);
            }
        }

        // Hit testing is monotone too: leading half goes downstream, trailing half upstream.
        assertEquals(new Position(0, Affinity.DOWNSTREAM), line.hitTest(1));
        assertEquals(new Position(1, Affinity.UPSTREAM), line.hitTest(9));
        assertEquals(new Position(1, Affinity.DOWNSTREAM), line.hitTest(11));
        assertEquals(new Position(4, Affinity.UPSTREAM), line.hitTest(39));
        for (int i = 0; i < 40; i++) {
            assertTrue(line.indexAt(i) <= line.indexAt(i + 1), "indexAt must not go backwards");
        }

        // And visual movement is the ordinal plus or minus one, in x as well as in index.
        Position p = new Position(0, Affinity.DOWNSTREAM);
        for (int i = 1; i <= 4; i++) {
            p = line.caretRight(p);
            assertEquals(i, p.charIndex());
            assertEquals(ADV * i, line.caretX(p), EPS);
        }
        for (int i = 3; i >= 0; i--) {
            p = line.caretLeft(p);
            assertEquals(i, p.charIndex());
            assertEquals(ADV * i, line.caretX(p), EPS);
        }
    }

    /**
     * The three things the frozen spec says break {@code isSimple()}. It is derived from the runs
     * and glyphs actually fed in, never accepted from a caller, because a flag left behind by a
     * change to itemization is every fast path in the toolkit silently taking the wrong route.
     */
    @Test
    void isSimpleIsFalseForASecondFaceARightToLeftRunOrAReorderedCluster() {
        assertFalse(latinThenHebrew().isSimple(), "a right-to-left run breaks it");
        assertFalse(hebrew(HEB).isSimple(), "a whole right-to-left line breaks it");
        assertFalse(hebrewThenLatin().isSimple(), "nested levels break it");

        ShapedText twoFaces = skeleton("ab")
                .run(FACE, 0, 1, 0)
                .glyph(101, 0, ADV, 0, 0)
                .run(FACE_B, 1, 2, 0)
                .glyph(1, 1, ADV, 0, 0)
                .build();
        assertFalse(twoFaces.isSimple(), "a second face breaks it");

        // A reordered matra: one left-to-right run, one face, but the glyph for the second
        // character is drawn before the glyph for the first. Character order and screen order no
        // longer agree, so the monotone fast paths are not legal here.
        ShapedText reordered = skeleton("ki")
                .run(FACE, 0, 2, 0)
                .glyph(301, 1, ADV, 0, 0)
                .glyph(300, 0, ADV, 0, 0)
                .build();
        assertFalse(reordered.isSimple(), "a reordered cluster breaks it");

        // Ligatures and kerning do NOT break it: they change how wide characters are, not what
        // order they are in. Here two characters share one glyph and one cluster.
        ShapedText ligature = skeleton("fi")
                .run(FACE, 0, 2, 0)
                .glyph(400, 0, 14, 0, 0)
                .build();
        assertTrue(ligature.isSimple());
        assertEquals(14, ligature.metrics().width(), EPS);
    }

    // ============================================================================================
    // The logical axis: a budget, order-independent, and never a caret x.
    // ============================================================================================

    @Test
    void advanceToIsALogicalPrefixAndNotACaretX() {
        ShapedText line = hebrew(HEB);
        assertEquals(30, line.metrics().width(), EPS);

        // Logical: the first character accounts for the first 10 points of the line's width.
        assertEquals(0, line.advanceTo(0), EPS);
        assertEquals(10, line.advanceTo(1), EPS);
        assertEquals(20, line.advanceTo(2), EPS);
        assertEquals(30, line.advanceTo(3), EPS);

        // Visual: the same first character is drawn at the RIGHT end of the line, so its caret
        // stops run the other way. Substituting one axis for the other is the bug that paints a
        // selection band over the wrong half of a line.
        assertEquals(30, line.caretAt(0).strongX(), EPS);
        assertEquals(20, line.caretAt(1).strongX(), EPS);
        assertEquals(10, line.caretAt(2).strongX(), EPS);
        assertEquals(0, line.caretAt(3).strongX(), EPS);

        // width() is advanceTo(length) by construction, whatever the direction.
        assertEquals(line.metrics().width(), line.advanceTo(line.text().length()), EPS);
        assertEquals(latinThenHebrew().metrics().width(),
                latinThenHebrew().advanceTo(6), EPS);
    }

    @Test
    void indexForAdvanceAndFitEndInvertTheBudget() {
        ShapedText line = latinThenHebrew();

        assertEquals(0, line.indexForAdvance(0));
        assertEquals(0, line.indexForAdvance(9.9f));
        assertEquals(1, line.indexForAdvance(10));
        assertEquals(2, line.indexForAdvance(25));
        assertEquals(3, line.indexForAdvance(30));
        assertEquals(6, line.indexForAdvance(60));

        assertEquals(0, line.indexForAdvance(-1), "below zero yields zero");
        assertEquals(6, line.indexForAdvance(1e9f), "at or above the width yields the length");

        // fitEnd is exactly indexForAdvance(advanceTo(from) + available), and it is named because
        // the from anchoring is the part a caller gets wrong.
        for (int from = 0; from <= 6; from++) {
            for (float available : new float[]{0.5f, 5, 10, 15, 25, 40, 1000}) {
                assertEquals(line.indexForAdvance(line.advanceTo(from) + available),
                        line.fitEnd(from, available),
                        "fitEnd(" + from + ", " + available + ")");
            }
        }

        assertEquals(2, line.fitEnd(2, 0), "a non-positive budget returns from");
        assertEquals(2, line.fitEnd(2, -50));
        assertEquals(6, line.fitEnd(0, 1000));

        // Every answer is a caret stop in [from, length].
        for (int from = 0; from <= 6; from++) {
            int end = line.fitEnd(from, 23);
            assertTrue(end >= from && end <= 6);
            assertEquals(end, line.caretIndex(line.caretOrdinal(end)));
        }
    }

    @Test
    void logicalAccessorsClampOutOfRangeIndices() {
        ShapedText line = latinThenHebrew();

        assertEquals(0, line.advanceTo(-4), EPS);
        assertEquals(60, line.advanceTo(99), EPS);
        assertEquals(0, line.caretIndex(-1));
        assertEquals(6, line.caretIndex(99));
        assertEquals(0, line.caretOrdinal(-9));
        assertEquals(6, line.caretOrdinal(99));
        assertEquals(line.caretAt(0), line.caretAt(-3));
        assertEquals(line.caretAt(6), line.caretAt(99));
        assertEquals(6, line.fitEnd(99, 10));
        assertEquals(line.selection(0, 6), line.selection(-5, 500));
    }

    // ============================================================================================
    // The caret-stop table: the three accessors every other query searches.
    // ============================================================================================

    @Test
    void caretStopTableEnumeratesClusterBoundariesAndInvertsItself() {
        ShapedText line = latinThenHebrew();

        assertEquals(7, line.caretCount(), "one stop per cluster boundary, plus the end");
        for (int ordinal = 0; ordinal < line.caretCount(); ordinal++) {
            assertEquals(ordinal, line.caretIndex(ordinal), "stops are the char offsets 0..6");
            assertEquals(ordinal, line.caretOrdinal(line.caretIndex(ordinal)),
                    "caretOrdinal inverts caretIndex");
        }
        assertEquals(0, line.caretIndex(0));
        assertEquals(6, line.caretIndex(line.caretCount() - 1));

        // The snapping rule every index-taking method obeys, stated in the type's own vocabulary.
        for (int i = 0; i <= 6; i++) {
            assertEquals(line.caretAt(line.caretIndex(line.caretOrdinal(i))), line.caretAt(i));
        }
    }

    // ============================================================================================
    // The paint seam: runs in visual order, glyphs in parallel arrays already reordered.
    // ============================================================================================

    @Test
    void runsComeBackInVisualOrderWithCharacterRangesOutOfOrder() {
        ShapedText line = hebrewThenLatin();
        assertEquals(Direction.RTL, line.baseDirection());
        assertEquals(2, line.runs().size());

        // Rule L2 reorders by level: the level-2 Latin run reverses inside the level-1 sweep and
        // ends up on the visual LEFT, even though it comes second in the string.
        Run first = line.runs().get(0);
        Run second = line.runs().get(1);
        assertEquals(2, first.charStart(), "the Latin run is leftmost on the line");
        assertEquals(4, first.charEnd());
        assertEquals(2, first.level());
        assertFalse(first.rtl(), "an even level reads left to right however deep it nests");
        assertEquals(0, second.charStart());
        assertEquals(2, second.charEnd());
        assertEquals(1, second.level());
        assertTrue(second.rtl());

        // Character ranges are NOT ascending once anything reorders. That is the whole reason this
        // list exists in visual order while the builder is fed in logical order.
        assertTrue(first.charStart() > second.charStart());
    }

    @Test
    void glyphRangesTileInVisualOrderAndTheGlyphsAreAlreadyPlaced() {
        ShapedText line = hebrewThenLatin();

        int cursor = 0;
        for (Run run : line.runs()) {
            assertEquals(cursor, run.glyphStart(), "glyph ranges must tile [0, glyphCount())");
            assertTrue(run.glyphEnd() > run.glyphStart());
            cursor = run.glyphEnd();
        }
        assertEquals(line.glyphCount(), cursor);
        assertEquals(4, line.glyphCount());

        // The glyph arrays are in visual order: c, d, bet, alef - and the builder ran the pen, so
        // the paint loop is a walk with no arithmetic of its own.
        assertArrayEquals(new int[]{2, 3, 1, 0}, new int[]{
                line.glyphCluster(0), line.glyphCluster(1),
                line.glyphCluster(2), line.glyphCluster(3)});
        assertArrayEquals(new float[]{0, 10, 20, 30}, new float[]{
                line.glyphX(0), line.glyphX(1), line.glyphX(2), line.glyphX(3)}, EPS);
        for (int g = 0; g < line.glyphCount(); g++) {
            assertEquals(ADV, line.glyphAdvance(g), EPS);
            assertEquals(0, line.glyphY(g), EPS);
        }
        assertEquals(40, line.metrics().width(), EPS);
    }

    @Test
    void glyphOffsetsPlaceAMarkWithoutMovingThePen() {
        // A base with an attached mark: the mark has its own offsets and zero advance, which is
        // what keeps the cluster's box the width of the base rather than base plus accent.
        ShapedText line = skeleton("a\u0301")
                .run(FACE, 0, 2, 0)
                .glyph(101, 0, ADV, 0, 0)
                .glyph(500, 0, 0, 2.5f, -6)
                .build();

        assertEquals(ADV, line.metrics().width(), EPS);
        assertEquals(2, line.glyphCount());
        assertEquals(0, line.glyphX(0), EPS);
        // glyphX is "the pen position plus whatever the shaper offset it by", and xOffset is
        // "relative to the pen" — so the base's advance is already in the pen when the mark is
        // placed: 10 + 2.5. That is exactly HarfBuzz's own loop (draw at cursor + offset, THEN
        // cursor += advance), which is what lets hb_glyph_position_t be fed to glyph() verbatim.
        assertEquals(12.5f, line.glyphX(1), EPS, "the pen did not move, the offset did");
        assertEquals(-6, line.glyphY(1), EPS, "positive down, so a mark above the baseline is up");
        assertEquals(0, line.glyphAdvance(1), EPS);
        assertEquals(0, line.glyphCluster(1), "a mark reports the offset of its base");

        // Both glyphs are one cluster, so there are two stops and not three.
        assertEquals(2, line.caretCount());
        assertEquals(0, line.caretIndex(0));
        assertEquals(2, line.caretIndex(1));

        // The real-world sign: a combining accent carries a NEGATIVE x_offset to pull back over
        // the base it follows. The pen is past the base, so the mark lands ON it rather than after.
        ShapedText pulledBack = skeleton("a\u0301")
                .run(FACE, 0, 2, 0)
                .glyph(101, 0, ADV, 0, 0)
                .glyph(500, 0, 0, -7.5f, -6)
                .build();
        assertEquals(2.5f, pulledBack.glyphX(1), EPS, "a negative offset pulls back over the base");
        assertEquals(ADV, pulledBack.metrics().width(), EPS, "and still moves the pen not at all");
    }

    @Test
    void noGlyphIsAPerGlyphSentinelAndNotAWholeValueFlag() {
        ShapedText line = withZeroAdvanceCluster();

        assertEquals(-1, ShapedText.NO_GLYPH);
        assertEquals(3, line.glyphCount());
        assertEquals(101, line.glyphId(0));
        assertEquals(ShapedText.NO_GLYPH, line.glyphId(1),
                "one cluster can fall back while its neighbours are shaped");
        assertEquals(102, line.glyphId(2));
    }

    // ============================================================================================
    // uniform(...): the masked field's geometry, in grapheme cluster cells.
    // ============================================================================================

    @Test
    void uniformCellsAreGraphemeClustersNotCodePoints() {
        TextMetrics vertical = new TextMetrics(999, 8, 2, 12);

        // A combining sequence: e + combining acute is ONE user-perceived character, so one cell,
        // one mark and one caret stop - the caret can never sit between the e and its accent.
        ShapedText combining = ShapedText.uniform("e\u0301x", FONT, ADV, vertical, 0);
        assertEquals(3, combining.text().length());
        assertEquals(3, combining.caretCount(), "two clusters means three stops");
        assertEquals(0, combining.caretIndex(0));
        assertEquals(2, combining.caretIndex(1), "the stop skips the combining mark");
        assertEquals(3, combining.caretIndex(2));
        assertEquals(2 * ADV, combining.metrics().width(), EPS);

        // An index inside a cluster snaps back to the cluster's start rather than being rejected.
        assertEquals(0, combining.caretOrdinal(1));
        assertEquals(0, combining.caretAt(1).strongX(), EPS);
        assertTrue(combining.selection(0, 1).isEmpty(), "half a cluster selects nothing");

        // An astral character: one code point, two chars, still one cell.
        ShapedText astral = ShapedText.uniform("😀a", FONT, ADV, vertical, 0);
        assertEquals(3, astral.text().length());
        assertEquals(3, astral.caretCount());
        assertEquals(0, astral.caretIndex(0));
        assertEquals(2, astral.caretIndex(1), "a surrogate pair is never split by a caret stop");
        assertEquals(3, astral.caretIndex(2));
        assertEquals(2 * ADV, astral.metrics().width(), EPS);
    }

    @Test
    void uniformGeometryIsOneMultiplicationAndTheMarkCountComesFromTheStopTable() {
        TextMetrics vertical = new TextMetrics(999, 8, 2, 12);
        ShapedText mask = ShapedText.uniform("secret", FONT, ADV, vertical, 0);

        assertTrue(mask.isSimple());
        assertEquals(Direction.LTR, mask.baseDirection(), "a mask has no direction left to have");
        assertEquals(1, mask.runs().size());
        assertEquals("secret", mask.text(), "the index space is the real text, never the mask");

        // The width is the mark count times the advance, so the lineMetrics width is ignored and
        // the two cannot disagree.
        assertEquals(6 * ADV, mask.metrics().width(), EPS);
        assertEquals(8, mask.metrics().ascent(), EPS);
        assertEquals(2, mask.metrics().descent(), EPS);
        assertEquals(12, mask.metrics().lineHeight(), EPS);

        // No caller has to divide a width by an advance to recover a count: the number of marks to
        // paint is caretCount() - 1, and the i-th one is centred at (i + 0.5) * advance. The point
        // of stating the centre that way is that it falls between the i-th and (i+1)-th caret
        // stops without any second piece of arithmetic that could drift from the first.
        int marks = mask.caretCount() - 1;
        assertEquals(6, marks);
        for (int i = 0; i < marks; i++) {
            float centre = (i + 0.5f) * ADV;
            assertEquals(ADV * i, mask.advanceTo(i), EPS);
            assertEquals(ADV * i, mask.caretAt(i).strongX(), EPS);
            assertTrue(centre > mask.caretAt(i).strongX() && centre < mask.caretAt(i + 1).strongX(),
                    "mark " + i + " must sit between its own two caret stops");
            assertEquals(i, mask.hitTest(centre - 1).charIndex());
            assertEquals(i + 1, mask.hitTest(centre + 1).charIndex());
        }
        assertEquals(marks * ADV, mask.metrics().width(), EPS);
        assertEquals(new Position(0, Affinity.DOWNSTREAM), mask.hitTest(1));
        assertEquals(new Position(1, Affinity.UPSTREAM), mask.hitTest(9));
        List<Span> boxes = mask.selection(1, 4);
        assertEquals(1, boxes.size());
        assertEquals(10, boxes.get(0).x0(), EPS);
        assertEquals(40, boxes.get(0).x1(), EPS);

        // Nothing here is a glyph: the field paints circles, and its content never reaches a
        // shaper or the memo a shaper keeps.
        for (int g = 0; g < mask.glyphCount(); g++) {
            assertEquals(ShapedText.NO_GLYPH, mask.glyphId(g));
        }
    }

    @Test
    void uniformRejectsANonPositiveAdvanceAndNullArguments() {
        TextMetrics vertical = new TextMetrics(0, 8, 2, 12);

        assertThrows(IllegalArgumentException.class,
                () -> ShapedText.uniform("a", FONT, 0, vertical, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ShapedText.uniform("a", FONT, -3, vertical, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ShapedText.uniform("a", FONT, Float.NaN, vertical, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ShapedText.uniform("a", FONT, Float.POSITIVE_INFINITY, vertical, 0));

        assertThrows(NullPointerException.class,
                () -> ShapedText.uniform(null, FONT, ADV, vertical, 0));
        assertThrows(NullPointerException.class,
                () -> ShapedText.uniform("a", null, ADV, vertical, 0));
        assertThrows(NullPointerException.class,
                () -> ShapedText.uniform("a", FONT, ADV, null, 0));
    }

    @Test
    void uniformOfAnEmptyStringHasOneStopAndNoWidth() {
        ShapedText mask = ShapedText.uniform("", FONT, ADV, new TextMetrics(0, 8, 2, 12), 0);
        assertEquals(1, mask.caretCount());
        assertEquals(0, mask.caretIndex(0));
        assertEquals(0, mask.metrics().width(), EPS);
        assertEquals(0, mask.caretCount() - 1, "no marks to paint");
    }

    // ============================================================================================
    // Builder contract violations. The cluster-origin check is at glyph() and the tiling check is
    // at build(), so the exception names the call that made the mistake.
    // ============================================================================================

    /**
     * The run-origin mistake: a shaper reports clusters relative to the buffer it was handed, so a
     * caller that forgets to add the run's start back produces a caret that lands one character
     * away from every click, in every field, forever. Caught at the call that made it.
     */
    @Test
    void builderRejectsAClusterOutsideTheOpenRun() {
        ShapedText.Builder b = skeleton("abc" + HEB)
                .run(FACE, 0, 3, 0)
                .glyph(101, 0, ADV, 0, 0)
                .glyph(102, 1, ADV, 0, 0)
                .glyph(103, 2, ADV, 0, 0)
                .run(FACE, 3, 6, 1);

        // 0, 1 and 2 are what a shaper handed only the Hebrew substring would report.
        assertThrows(IllegalArgumentException.class, () -> b.glyph(201, 0, ADV, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> b.glyph(201, 2, ADV, 0, 0));
        // And one past the run's end is the off-by-one on the other side.
        assertThrows(IllegalArgumentException.class, () -> b.glyph(201, 6, ADV, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> b.glyph(201, -1, ADV, 0, 0));
    }

    @Test
    void builderRejectsAGlyphWithNoRunOpen() {
        ShapedText.Builder b = skeleton("ab");
        assertThrows(IllegalStateException.class, () -> b.glyph(101, 0, ADV, 0, 0));
    }

    @Test
    void builderRejectsRunsThatDoNotTileTheText() {
        // A gap between runs, caught where the second run is opened.
        ShapedText.Builder gap = skeleton("abcd").run(FACE, 0, 2, 0).glyph(101, 0, ADV, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> gap.run(FACE, 3, 4, 0));

        // An overlap is the same failure from the other side.
        ShapedText.Builder overlap = skeleton("abcd").run(FACE, 0, 2, 0).glyph(101, 0, ADV, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> overlap.run(FACE, 1, 4, 0));

        // A first run that does not start at zero.
        ShapedText.Builder late = skeleton("abcd");
        assertThrows(IllegalArgumentException.class, () -> late.run(FACE, 1, 4, 0));

        // An empty or reversed range is not a run.
        ShapedText.Builder degenerate = skeleton("abcd");
        assertThrows(IllegalArgumentException.class, () -> degenerate.run(FACE, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> degenerate.run(FACE, 2, 1, 0));

        // Out of bounds on the right.
        ShapedText.Builder past = skeleton("abcd");
        assertThrows(IllegalArgumentException.class, () -> past.run(FACE, 0, 5, 0));

        // And the last run stopping short of the string's length, caught at build().
        ShapedText.Builder truncated = skeleton("abcd").run(FACE, 0, 3, 0).glyph(101, 0, ADV, 0, 0);
        assertThrows(IllegalStateException.class, truncated::build);

        // No runs at all for a non-empty string is the same failure.
        ShapedText.Builder none = skeleton("abcd");
        assertThrows(IllegalStateException.class, none::build);
    }

    @Test
    void builderRejectsAnOutOfRangeEmbeddingLevel() {
        ShapedText.Builder b = skeleton("ab");
        assertThrows(IllegalArgumentException.class, () -> b.run(FACE, 0, 2, -1));
        assertThrows(IllegalArgumentException.class, () -> b.run(FACE, 0, 2, 126));

        // 0 to 125 is the Unicode Bidirectional Algorithm's own range, and both ends are legal.
        assertEquals(0, skeleton("ab").run(FACE, 0, 2, 0).glyph(101, 0, ADV, 0, 0).build()
                .runs().get(0).level());
        assertEquals(125, skeleton("ab").run(FACE, 0, 2, 125).glyph(101, 0, ADV, 0, 0).build()
                .runs().get(0).level());
    }

    @Test
    void builderRequiresVerticalMetricsBeforeBuilding() {
        ShapedText.Builder b = ShapedText.builder("ab", FONT, Direction.LTR, 2)
                .run(FACE, 0, 2, 0)
                .glyph(101, 0, ADV, 0, 0)
                .glyph(102, 1, ADV, 0, 0);
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void builderRejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> ShapedText.builder(null, FONT, Direction.LTR, 1));
        assertThrows(NullPointerException.class,
                () -> ShapedText.builder("a", null, Direction.LTR, 1));
        assertThrows(NullPointerException.class,
                () -> ShapedText.builder("a", FONT, null, 1));
    }

    @Test
    void builderAcceptsARunWithNoGlyphsAtAll() {
        // A run of control characters carries geometry and draws nothing.
        ShapedText line = skeleton("a\0b")
                .run(FACE, 0, 1, 0)
                .glyph(101, 0, ADV, 0, 0)
                .run(FACE, 1, 2, 0)
                .run(FACE, 2, 3, 0)
                .glyph(102, 2, ADV, 0, 0)
                .build();
        assertEquals(2, line.glyphCount());
        assertEquals(2 * ADV, line.metrics().width(), EPS);
    }

    @Test
    void aWrongGlyphCapacityIsAWastedAllocationAndNeverAWrongAnswer() {
        ShapedText tight = ShapedText.builder("abc", FONT, Direction.LTR, 3)
                .lineMetrics(8, 2, 12).run(FACE, 0, 3, 0)
                .glyph(101, 0, ADV, 0, 0).glyph(102, 1, ADV, 0, 0).glyph(103, 2, ADV, 0, 0)
                .build();
        ShapedText loose = ShapedText.builder("abc", FONT, Direction.LTR, 0)
                .lineMetrics(8, 2, 12).run(FACE, 0, 3, 0)
                .glyph(101, 0, ADV, 0, 0).glyph(102, 1, ADV, 0, 0).glyph(103, 2, ADV, 0, 0)
                .build();
        assertEquals(tight.glyphCount(), loose.glyphCount());
        assertEquals(tight.metrics().width(), loose.metrics().width(), EPS);
        assertEquals(tight.caretCount(), loose.caretCount());
    }

    // ============================================================================================
    // Epoch and matches(): the whole invalidation contract, and nothing else in it.
    // ============================================================================================

    @Test
    void matchesTestsTextFontAndEpochAndNothingElse() {
        ShapedText line = ShapedText.builder("abc", FONT, Direction.LTR, 3)
                .lineMetrics(8, 2, 12)
                .epoch(42)
                .run(FACE, 0, 3, 0)
                .glyph(101, 0, ADV, 0, 0)
                .glyph(102, 1, ADV, 0, 0)
                .glyph(103, 2, ADV, 0, 0)
                .build();
        assertEquals(42, line.epoch());

        assertTrue(line.matches("abc", FONT, rulerAt(42)));
        // Equal but not identical text still matches: identity is a fast path, not the test.
        assertTrue(line.matches(new String(new char[]{'a', 'b', 'c'}), FONT, rulerAt(42)));
        // Equal-by-value Font, which is what a control-size step or a theme change produces.
        assertTrue(line.matches("abc", Font.of(16), rulerAt(42)));

        assertFalse(line.matches("abd", FONT, rulerAt(42)), "the text changed");
        assertFalse(line.matches("ab", FONT, rulerAt(42)), "the text changed");
        assertFalse(line.matches("abc", OTHER_FONT, rulerAt(42)), "the font changed");
        assertFalse(line.matches("abc", FONT.bold(), rulerAt(42)), "the style changed");

        // The epoch is the part the caller cannot see: a face evicted and closed, a default family
        // switched, the shaping language changed - none of which move the text or the Font.
        assertFalse(line.matches("abc", FONT, rulerAt(43)), "the ruler moved on");
        assertFalse(line.matches("abc", FONT, TextRuler.NONE),
                "a ruler that never produced this value is not the ruler it is current under");
    }

    @Test
    void epochZeroIsCurrentUnderEveryRuler() {
        // A builder that stamps no epoch, which is right for a fake and for geometry that depends
        // on no ruler state.
        ShapedText fake = latin("abc");
        assertEquals(0, fake.epoch());
        assertTrue(fake.matches("abc", FONT, rulerAt(0)));
        assertTrue(fake.matches("abc", FONT, rulerAt(9999)));
        assertTrue(fake.matches("abc", FONT, TextRuler.NONE));

        // uniform takes the epoch as a parameter precisely so a masked field can pass a real one
        // and stay correct across a default-family change that leaves its Font equal to itself.
        TextMetrics vertical = new TextMetrics(0, 8, 2, 12);
        ShapedText stamped = ShapedText.uniform("secret", FONT, ADV, vertical, 5);
        assertEquals(5, stamped.epoch());
        assertTrue(stamped.matches("secret", FONT, rulerAt(5)));
        assertFalse(stamped.matches("secret", FONT, rulerAt(6)));

        ShapedText unstamped = ShapedText.uniform("secret", FONT, ADV, vertical, 0);
        assertTrue(unstamped.matches("secret", FONT, rulerAt(6)));
    }

    @Test
    void twoShapingsOfTheSameStringAreEqualAnswersAndNotEqualObjects() {
        // Equality is identity: the question a widget actually has is matches(), which is
        // different and cheaper, and folding an epoch into equals would produce a value whose
        // equality changed with time.
        ShapedText a = latin("abc");
        ShapedText b = latin("abc");
        assertNotEquals(a, b);
        assertEquals(a, a);
        assertEquals(a.metrics().width(), b.metrics().width(), EPS);
    }

    // ============================================================================================
    // Degenerate input.
    // ============================================================================================

    @Test
    void emptyLineHasOneCaretStopAndNoGeometry() {
        ShapedText line = empty();

        assertEquals("", line.text());
        assertEquals(FONT, line.font());
        assertEquals(1, line.caretCount(), "always at least one stop, exactly one when empty");
        assertEquals(0, line.caretIndex(0));
        assertEquals(0, line.caretOrdinal(0));
        assertEquals(0, line.metrics().width(), EPS);
        assertEquals(8, line.metrics().ascent(), EPS);
        assertEquals(0, line.glyphCount(), "zero glyphs is legal, not an error");
        assertTrue(line.runs().isEmpty());

        Caret caret = line.caretAt(0);
        assertEquals(0, caret.strongX(), EPS);
        assertEquals(0, caret.weakX(), EPS);
        assertFalse(caret.split());

        assertEquals(0, line.advanceTo(0), EPS);
        assertEquals(0, line.indexForAdvance(500));
        assertEquals(0, line.fitEnd(0, 500));
        assertTrue(line.selection(0, 0).isEmpty());
        assertEquals(0, line.selection(0, 0, new float[0]), "the bound for a runless line is zero");

        // A hit test on a moving pointer has to produce a position, not an exception.
        assertEquals(0, line.hitTest(0).charIndex());
        assertEquals(0, line.hitTest(-30).charIndex());
        assertEquals(0, line.hitTest(400).charIndex());
        assertEquals(0, line.indexAt(37));
        assertEquals(0, line.caretX(line.hitTest(37)), EPS);

        Position only = new Position(0, Affinity.DOWNSTREAM);
        assertEquals(0, line.caretLeft(only).charIndex());
        assertEquals(0, line.caretRight(only).charIndex());
    }

    @Test
    void singleCharacterLineHasTwoStopsAndOneStepInEachDirection() {
        ShapedText line = latin("a");

        assertTrue(line.isSimple());
        assertEquals(2, line.caretCount());
        assertEquals(ADV, line.metrics().width(), EPS);
        assertEquals(0, line.caretAt(0).strongX(), EPS);
        assertEquals(ADV, line.caretAt(1).strongX(), EPS);

        assertEquals(new Position(0, Affinity.DOWNSTREAM), line.hitTest(4));
        assertEquals(new Position(1, Affinity.UPSTREAM), line.hitTest(6));

        List<Span> whole = line.selection(0, 1);
        assertEquals(1, whole.size());
        assertEquals(0, whole.get(0).x0(), EPS);
        assertEquals(ADV, whole.get(0).x1(), EPS);
    }

    @Test
    void anAllNeutralStringTakesTheCallerSuppliedFallbackDirection() {
        // The one case the first-strong rule cannot answer, and the right answer is the direction
        // of the surrounding user interface, which nothing in this package owns yet.
        assertEquals(Direction.LTR, Direction.of("42", Direction.LTR));
        assertEquals(Direction.RTL, Direction.of("42", Direction.RTL));
        assertEquals(Direction.RTL, Direction.of("(...)", Direction.RTL));
        assertEquals(Direction.RTL, Direction.of("", Direction.RTL));
        assertEquals(Direction.LTR, Direction.of("", Direction.LTR));

        // And a neutral string still shapes, with the base direction it was given. LEVEL 2, not 0:
        // European digits in an RTL paragraph take an even level ABOVE the paragraph level, and
        // java.text.Bidi reports exactly [0,2)@L2 for this string. A level-0 run inside an RTL
        // paragraph is not something the UBA can produce, so a fixture built that way would pin
        // the strong-side rule on input no shaper can hand over.
        ShapedText line = ShapedText.builder("42", FONT, Direction.RTL, 2)
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, 2, 2)
                .glyph(101, 0, ADV, 0, 0)
                .glyph(102, 1, ADV, 0, 0)
                .build();
        assertEquals(Direction.RTL, line.baseDirection());
        assertEquals(2 * ADV, line.metrics().width(), EPS);

        // Both paragraph edges are split, and which side is STRONG is the question the base
        // direction answers. At index 0 there is no character upstream, so that side carries the
        // paragraph's own level (1, odd, the base parity) against the digits' level 2 (even): the
        // upstream side wins and the strong caret draws at the RIGHT edge. That is where an RTL
        // keystroke at logical position 0 of an RTL paragraph actually lands. The mirror holds at
        // the logical end.
        assertEquals(new Caret(2 * ADV, 0, false), line.caretAt(0));
        assertEquals(2 * ADV, line.caretAt(0).strongX(), EPS, "base-direction typing goes right");
        assertEquals(0, line.caretAt(0).weakX(), EPS);
        assertEquals(new Caret(2 * ADV, 0, true), line.caretAt(2));
        assertEquals(0, line.caretAt(2).strongX(), EPS);
    }

    @Test
    void firstStrongCharacterDecidesTheDirectionWhateverPrecedesIt() {
        assertEquals(Direction.LTR, Direction.of("abc", Direction.RTL));
        assertEquals(Direction.RTL, Direction.of(HEB, Direction.LTR));
        assertEquals(Direction.LTR, Direction.of("  (42) abc " + HEB, Direction.RTL),
                "digits and punctuation are not strong; the Latin a is");
        assertEquals(Direction.RTL, Direction.of("(42) " + HEB + " abc", Direction.LTR));
        assertEquals(Direction.LTR, Direction.of("Total: 42 " + HEB + " (SAR)", Direction.RTL));

        assertThrows(NullPointerException.class, () -> Direction.of(null, Direction.LTR));
        assertThrows(NullPointerException.class, () -> Direction.of("abc", null));
    }

    /**
     * The rule and the shortcut, held together: {@code Direction.of} stopped asking
     * {@code java.text.Bidi} for the strings a user interface actually draws, and this is what
     * makes that an optimization rather than a second rule.
     *
     * <p>It was worth doing. Every {@code shape(text, font)} resolves a direction, a shaping
     * ruler's {@code measure} is one of those, and a layout pass measures every caption on the
     * screen: two {@code Bidi} constructions per string were the dominant cost of a text frame at
     * some 318&nbsp;ns against the 12&nbsp;ns memo lookup behind them. What it costs is this
     * test &mdash; the hand scan is compared against the two-{@code Bidi} rule it replaced over
     * every shape of paragraph the algorithm distinguishes, and the scan is required to defer to
     * {@code Bidi} rather than answer wherever the rule is subtle.
     *
     * <p>The corpus is built by construction and not by imagination: one representative code point
     * per directionality class, plus both isolate families, plus the paragraph separator that ends
     * rule P1's first paragraph, plus astral strong characters on each side &mdash; taken as every
     * ordered pair and triple. A hand-written list of interesting strings is exactly the thing that
     * would have missed the paragraph separator, which is the one case a first draft of the scan
     * got wrong.
     *
     * <p><b>"One per directionality class" has a hole in it, and the two unassigned code points
     * below are that hole named.</b> A code point this JDK has no entry for belongs to no
     * directionality class, so a corpus built one-per-class structurally cannot contain one &mdash;
     * and unassigned is precisely where the two answers can differ, because Unicode gives an
     * unassigned code point the default of the range it sits in and {@code java.text.Bidi} carries
     * those range defaults while {@code Character.getDirectionality} reports
     * {@code DIRECTIONALITY_UNDEFINED}. One from a default-{@code R} range and one from a
     * default-{@code L} range are needed, because the second is what fails if the first is fixed by
     * calling every unknown code point right-to-left.
     */
    @Test
    void theHandScanAgreesWithJavaTextBidiOnEveryShapeOfParagraph() {
        int[] pool = {
            'a', 0x05D0 /* hebrew alef, R */, 0x0627 /* arabic alef, AL */,
            '0', 0x0660 /* arabic-indic digit, AN */, '+', ',', ' ', '(',
            0x0301 /* combining acute, NSM */, '\n' /* paragraph separator, B */,
            '\t' /* segment separator, S */, 0x2028 /* line separator, WS */,
            0x202A /* LRE */, 0x202B /* RLE */, 0x202C /* PDF */, 0x202D /* LRO */,
            0x202E /* RLO */, 0x2066 /* LRI */, 0x2067 /* RLI */, 0x2068 /* FSI */,
            0x2069 /* PDI */, 0x200E /* LRM */, 0x200F /* RLM */, 0x061C /* ALM */,
            0x10800 /* cypriot, R, astral */, 0x1EE00 /* arabic mathematical, AL, astral */,
            0x1D400 /* mathematical bold A, L, astral */, 0x1F600 /* emoji, ON, astral */,
            0x05EB /* unassigned, Hebrew block: DerivedBidiClass default R */,
            0x0378 /* unassigned, Greek block: DerivedBidiClass default L */,
        };
        // The two additions are only evidence while they stay unassigned. A JDK that assigns
        // either one turns them into ordinary members of a class the pool already covers, and the
        // hole they were added for silently reopens.
        assertFalse(Character.isDefined(0x05EB), "U+05EB is no longer the unassigned case");
        assertFalse(Character.isDefined(0x0378), "U+0378 is no longer the unassigned case");
        StringBuilder text = new StringBuilder();
        int checked = 0;
        for (int a : pool) {
            for (int b : pool) {
                for (int c : pool) {
                    text.setLength(0);
                    text.appendCodePoint(a).appendCodePoint(b).appendCodePoint(c);
                    String value = text.toString();
                    for (Direction neutral : Direction.values()) {
                        assertEquals(byBidi(value, neutral), Direction.of(value, neutral),
                                () -> "the scan and java.text.Bidi disagree about " + named(value));
                    }
                    checked++;
                }
            }
        }
        assertEquals(pool.length * pool.length * pool.length, checked);
    }

    /**
     * The rule as {@code java.text.Bidi} implements it, which is what {@code Direction.of} was
     * before the scan in front of it: defaulting one way and then the other separates "the text
     * said so" from "the default said so", and they agree exactly when a strong character decided.
     */
    private static Direction byBidi(String text, Direction whenNeutral) {
        if (text.isEmpty()) {
            return whenNeutral;
        }
        if (!new java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
                .baseIsLeftToRight()) {
            return Direction.RTL;
        }
        return new java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT)
                .baseIsLeftToRight() ? Direction.LTR : whenNeutral;
    }

    /** A failing fixture is made of invisible characters; print it as code points. */
    private static String named(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> out.append(String.format("U+%04X ", cp)));
        return out.toString().trim();
    }

    @Test
    void aLineThatIsOneRightToLeftRunReadsFromTheRightEdge() {
        ShapedText line = hebrew(HEB);

        assertFalse(line.isSimple());
        assertEquals(Direction.RTL, line.baseDirection());
        assertEquals(1, line.runs().size());
        assertTrue(line.runs().get(0).rtl());
        assertEquals(30, line.metrics().width(), EPS);

        // The run occupies [0, width] like every other run: right-to-left text fills the same box
        // from the other end and is never drawn at negative x.
        assertEquals(0, line.glyphX(0), EPS);
        assertEquals(2, line.glyphCluster(0), "the LAST character is the leftmost glyph");
        assertEquals(20, line.glyphX(2), EPS);
        assertEquals(0, line.glyphCluster(2), "the FIRST character is the rightmost glyph");

        // No stop on this line is split: there is no direction boundary inside it, and the
        // paragraph edges agree with the run's own edges.
        for (int i = 0; i <= 3; i++) {
            assertFalse(line.caretAt(i).split(), "stop " + i + " must not be split");
        }
        assertEquals(30, line.caretAt(0).strongX(), EPS);
        assertEquals(0, line.caretAt(3).strongX(), EPS);

        // Hit testing mirrors: the leading half of a right-to-left cluster is its RIGHT half.
        assertEquals(new Position(3, Affinity.UPSTREAM), line.hitTest(4));
        assertEquals(new Position(2, Affinity.DOWNSTREAM), line.hitTest(6));
        assertEquals(10, line.caretX(line.hitTest(6)), EPS);

        // Left and right arrows still mean left and right on the screen.
        Position p = new Position(3, Affinity.UPSTREAM);
        assertEquals(0, line.caretX(p), EPS);
        p = line.caretRight(p);
        assertEquals(new Position(2, Affinity.DOWNSTREAM), p);
        assertEquals(10, line.caretX(p), EPS);

        // A logical range is one visual box here, but it is the box at the other end of the line.
        List<Span> firstTwo = line.selection(0, 2);
        assertEquals(1, firstTwo.size());
        assertEquals(10, firstTwo.get(0).x0(), EPS);
        assertEquals(30, firstTwo.get(0).x1(), EPS);
    }

    @Test
    void trailingWhitespaceKeepsItsAdvanceAndItsSelectionBox() {
        // Today's measure includes a trailing space's advance and shape().metrics() preserves it,
        // so a widget that swaps one for the other sees the same number.
        ShapedText line = latin("ab ");

        assertEquals(3 * ADV, line.metrics().width(), EPS);
        assertEquals(3 * ADV, line.advanceTo(3), EPS);
        assertEquals(4, line.caretCount(), "the space is a cluster and carries a caret stop");
        assertEquals(3 * ADV, line.caretAt(3).strongX(), EPS);

        List<Span> space = line.selection(2, 3);
        assertEquals(1, space.size(), "a space has width, so it has a box");
        assertEquals(20, space.get(0).x0(), EPS);
        assertEquals(30, space.get(0).x1(), EPS);

        assertEquals(new Position(3, Affinity.UPSTREAM), line.hitTest(29));
    }

    // ============================================================================================
    // The value types' derived members: a field that could contradict another does not exist.
    // ============================================================================================

    @Test
    void caretDerivesSplitStrongAndWeakFromItsTwoPositions() {
        Caret off = new Caret(30, 30, true);
        assertFalse(off.split(), "the same float on both sides is an exact comparison");
        assertEquals(30, off.strongX(), EPS);
        assertEquals(30, off.weakX(), EPS);
        assertEquals(30, off.x(Affinity.UPSTREAM), EPS);
        assertEquals(30, off.x(Affinity.DOWNSTREAM), EPS);

        Caret upstreamStrong = new Caret(30, 60, false);
        assertTrue(upstreamStrong.split());
        assertEquals(30, upstreamStrong.strongX(), EPS);
        assertEquals(60, upstreamStrong.weakX(), EPS);
        assertEquals(30, upstreamStrong.x(Affinity.UPSTREAM), EPS);
        assertEquals(60, upstreamStrong.x(Affinity.DOWNSTREAM), EPS);

        Caret downstreamStrong = new Caret(30, 60, true);
        assertTrue(downstreamStrong.split());
        assertEquals(60, downstreamStrong.strongX(), EPS);
        assertEquals(30, downstreamStrong.weakX(), EPS);

        assertEquals(20, new Span(10, 30).width(), EPS);
        assertEquals(0, new Span(10, 10).width(), EPS);
    }

    @Test
    void runDerivesRtlFromTheParityOfItsLevel() {
        assertFalse(new Run(FACE, 0, 1, 0, 1, 0).rtl());
        assertTrue(new Run(FACE, 0, 1, 0, 1, 1).rtl());
        assertFalse(new Run(FACE, 0, 1, 0, 1, 2).rtl(), "an even level reads left to right");
        assertTrue(new Run(FACE, 0, 1, 0, 1, 3).rtl());
    }

    @Test
    void affinityAndDirectionHaveExactlyTheConstantsTheSpecFreezes() {
        // No third Direction meaning "decide later": by the time a value exists the question has
        // been answered, and a constant standing for the question is one a widget could store.
        assertEquals(2, Direction.values().length);
        assertEquals(2, Affinity.values().length);
        assertEquals(Direction.LTR, Direction.valueOf("LTR"));
        assertEquals(Direction.RTL, Direction.valueOf("RTL"));
        assertEquals(Affinity.UPSTREAM, Affinity.valueOf("UPSTREAM"));
        assertEquals(Affinity.DOWNSTREAM, Affinity.valueOf("DOWNSTREAM"));
    }
}

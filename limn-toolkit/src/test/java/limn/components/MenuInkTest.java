package limn.components;

import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the mnemonic rule lands, stated as <b>logical index in, expected visual span out</b>.
 *
 * <p>That form is the whole point of the file. The rule used to be placed from two measured
 * prefixes, which names the drawn character's position only while drawing walks the string left to
 * right in logical order &mdash; and once a {@code String} is shaped before it is drawn, it does
 * not. On a right-to-left title the first letter is painted at the <em>right</em> end of the run
 * while the prefix arithmetic puts the mark at the left end, which is a mark under a different
 * letter and a screenshot that looks like a mark under a letter.
 */
class MenuInkTest extends ComponentTestBase {

    /**
     * Alef, bet, gimel: three strong right-to-left characters, one char apiece, in one place so no
     * other source line in this file mixes directions and reorders under an editor.
     */
    private static final String HEB = "אבג";

    private static final Font FONT = Font.of(13);

    /** Where the title's left edge is; every expectation below is quoted from it. */
    private static final float TEXT_X = 100;

    private static final float EPS = 1e-3f;

    /**
     * {@link ComponentTestBase#RULER}'s geometry with the one thing it cannot express: a ligature.
     * {@code ffi} is one glyph covering three characters and a little narrower than the three, so
     * the caret stop table has no stop inside it &mdash; which is exactly the case where
     * {@code index + 1} is not the far edge of anything, because it snaps back onto the stop the
     * index is already on.
     */
    private static final TextRuler LIGATURE_RULER = new TextRuler() {
        @Override
        public TextMetrics measure(String text, Font font) {
            return new TextMetrics(shape(text, font).metrics().width(), 8, 2, 12);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            ShapedText.Builder builder = ShapedText.builder(text, font, base, text.length())
                    .lineMetrics(8, 2, 12);
            if (text.isEmpty()) {
                return builder.build();
            }
            builder.run(0, 0, text.length(), 0);
            for (int i = 0; i < text.length(); ) {
                int chars = text.startsWith("ffi", i) ? 3 : 1;
                builder.glyph(ShapedText.NO_GLYPH, i, chars == 3 ? 25 : 10, 0, 0);
                i += chars;
            }
            return builder.build();
        }
    };

    /** Records the rules a widget draws; {@link MenuInk} draws exactly one. */
    private static final class LineRecorder extends ComponentTestBase.FakeCanvas {

        private final List<float[]> lines = new ArrayList<>();

        LineRecorder() {
            super(200, 40);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float width, Paint paint) {
            lines.add(new float[] {x1, x2, y1});
        }
    }

    /**
     * The one rule {@code underlineMnemonic} draws, as {@code {x1, x2, y}}, for a line shaped the
     * way a caller with nothing to say about direction would shape it.
     */
    private static float[] rule(TextRuler ruler, String text, int index) {
        return rule(ruler.shape(text, FONT), index);
    }

    /** The same, for a line the caller shaped itself: what a widget actually hands the mark. */
    private static float[] rule(ShapedText line, int index) {
        LineRecorder canvas = new LineRecorder();
        MenuInk.underlineMnemonic(canvas, line, index, TEXT_X, 20, Color.WHITE);
        assertEquals(1, canvas.lines.size(), "underlineMnemonic draws one rule or none");
        return canvas.lines.get(0);
    }

    /**
     * The case that was wrong: on a right-to-left title the first logical letter is the rightmost
     * drawn one, so the mark for index 0 belongs at the right end of the run. The prefix rule put
     * it at the left end &mdash; a whole word away, under the last letter instead of the first.
     */
    @Test
    void theMarkForTheFirstLetterOfARightToLeftTitleIsAtTheRightEnd() {
        float[] first = rule(RULER, HEB, 0);
        assertEquals(TEXT_X + 20, first[0], EPS, "the first letter is drawn last, at x 20");
        assertEquals(TEXT_X + 30, first[1], EPS);

        // And the interior indices, which the prefix rule got wrong in width as well as position:
        // each letter is ten wide and they are drawn right to left.
        assertEquals(TEXT_X + 10, rule(RULER, HEB, 1)[0], EPS);
        assertEquals(TEXT_X + 20, rule(RULER, HEB, 1)[1], EPS);
        assertEquals(TEXT_X + 0, rule(RULER, HEB, 2)[0], EPS);
        assertEquals(TEXT_X + 10, rule(RULER, HEB, 2)[1], EPS);
    }

    /**
     * A rule is drawn left to right whatever the text does, because a stroke from a trailing edge
     * to a leading edge is a stroke with a negative length, and nothing downstream of here is
     * obliged to survive one.
     */
    @Test
    void theRuleIsNeverDrawnBackwards() {
        for (int i = 0; i < HEB.length(); i++) {
            float[] line = rule(RULER, HEB, i);
            assertTrue(line[0] < line[1], "rule " + i + " runs backwards");
        }
    }

    /** Latin is where the two rules agree, and it has to keep agreeing: nothing moved here. */
    @Test
    void aLeftToRightTitleIsMarkedExactlyWhereItWasBefore() {
        assertEquals(TEXT_X + 0, rule(RULER, "File", 0)[0], EPS);
        assertEquals(TEXT_X + 10, rule(RULER, "File", 0)[1], EPS);
        assertEquals(TEXT_X + 20, rule(RULER, "File", 2)[0], EPS);
        assertEquals(TEXT_X + 30, rule(RULER, "File", 2)[1], EPS);
    }

    /**
     * A mnemonic inside a ligature marks the glyph the letter is drawn in, because there is no
     * smaller thing on the line to mark. Taking the stop after the <em>index</em> rather than the
     * stop after the <em>cluster</em> would snap back onto the cluster's own stop and draw a
     * zero-length rule: an F mnemonic on an "Office" menu, marking nothing at all.
     */
    @Test
    void aMnemonicInsideALigatureMarksTheWholeGlyph() {
        float[] f = rule(LIGATURE_RULER, "Office", 1);
        assertEquals(TEXT_X + 10, f[0], EPS, "the ffi glyph starts where the O ends");
        assertEquals(TEXT_X + 35, f[1], EPS, "and is 25 wide, so the mark is 25 wide");

        // The characters outside the ligature are unaffected, which is what says the widening is
        // the cluster's and not a blanket one.
        assertEquals(TEXT_X + 0, rule(LIGATURE_RULER, "Office", 0)[0], EPS);
        assertEquals(TEXT_X + 10, rule(LIGATURE_RULER, "Office", 0)[1], EPS);
        assertEquals(TEXT_X + 35, rule(LIGATURE_RULER, "Office", 4)[0], EPS);
        assertEquals(TEXT_X + 45, rule(LIGATURE_RULER, "Office", 4)[1], EPS);
    }

    /**
     * No mnemonic, no mark. The index is handed over unchecked by both call sites, so the
     * out-of-range answers are part of the contract rather than a defensive habit.
     */
    @Test
    void thereIsNoMarkWithoutACharacterToMark() {
        LineRecorder canvas = new LineRecorder();
        MenuInk.underlineMnemonic(canvas, RULER.shape("File", FONT), -1, TEXT_X, 20, Color.WHITE);
        MenuInk.underlineMnemonic(canvas, null, 0, TEXT_X, 20, Color.WHITE);
        MenuInk.underlineMnemonic(canvas, RULER.shape("File", FONT), 4, TEXT_X, 20, Color.WHITE);
        assertTrue(canvas.lines.isEmpty(), "a rule was drawn for a character that is not there");
    }

    /**
     * The mark is placed on <b>the caller's line</b>, base direction included, which is what
     * taking a {@link ShapedText} rather than a ruler and a string is for.
     *
     * <p>{@code "-42"} is the case that shows it. Every character in it is neutral, so the
     * paragraph direction is the whole of what decides its layout: reading left to right the sign
     * leads, and reading right to left it is a neutral at the paragraph's edge, takes the
     * paragraph's own level and is drawn after both digits. A mark on the sign therefore belongs
     * at opposite ends of the same run. Re-shaping the string here could reproduce the glyphs and
     * could not reproduce the direction, because the direction is a fact about the widget.
     */
    @Test
    void theMarkFollowsTheBaseDirectionTheCallerShapedWith() {
        float[] ltr = rule(RULER.shape("-42", FONT, ShapedText.Direction.LTR), 0);
        assertEquals(TEXT_X, ltr[0], EPS, "reading left to right the sign leads the run");
        assertEquals(TEXT_X + 10, ltr[1], EPS);

        float[] rtl = rule(RULER.shape("-42", FONT, ShapedText.Direction.RTL), 0);
        assertEquals(TEXT_X + 20, rtl[0], EPS, "reading right to left it is drawn last");
        assertEquals(TEXT_X + 30, rtl[1], EPS);

        // The digits between them did not move: only the neutral at the edge changed run.
        assertEquals(rule(RULER.shape("-42", FONT, ShapedText.Direction.LTR), 2)[0] - 10,
                rule(RULER.shape("-42", FONT, ShapedText.Direction.RTL), 2)[0], EPS,
                "the last digit shifts by exactly the sign that left its side");
    }

    /** The index a mnemonic resolves to, which is where the geometry above starts. */
    @Test
    void theMnemonicIsFoundCaseInsensitivelyOrNotAtAll() {
        assertEquals(0, MenuInk.mnemonicIndex("File", 'F'));
        assertEquals(0, MenuInk.mnemonicIndex("file", 'F'));
        assertEquals(2, MenuInk.mnemonicIndex("File", 'L'));
        assertEquals(-1, MenuInk.mnemonicIndex("File", 'Z'));
        assertEquals(-1, MenuInk.mnemonicIndex("File", (char) 0));
        assertEquals(-1, MenuInk.mnemonicIndex(null, 'F'));
    }
}

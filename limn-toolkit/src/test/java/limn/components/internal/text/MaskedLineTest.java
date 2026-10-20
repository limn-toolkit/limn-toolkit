package limn.components.internal.text;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextRuler;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MaskedLine}: a line set group by group is the line shaped whole wherever the whole one
 * already kept the groups in order, glyph for glyph, and keeps them in order where it did not.
 */
class MaskedLineTest {

    private static final TextRuler RULER = TestRulers.FIXED;
    private static final Font FONT = Font.of(14);

    @Test
    void aLineWhoseGroupsAlreadyRanInOrderIsTheLineShapedWhole() {
        InputMask mask = InputMask.of("+1 (000) 000-0000");
        String text = "+1 (415) 555-0123";
        ShapedText grouped = MaskedLine.shape(RULER, text, FONT, mask.literals(text, 0));
        ShapedText whole = RULER.shape(text, FONT, ShapedText.Direction.LTR);
        assertEquals(text, grouped.text());
        assertEquals(whole.glyphCount(), grouped.glyphCount());
        assertEquals(whole.caretCount(), grouped.caretCount());
        assertEquals(whole.metrics().width(), grouped.metrics().width(), 1e-4f);
        for (int g = 0; g < whole.glyphCount(); g++) {
            assertEquals(whole.glyphCluster(g), grouped.glyphCluster(g));
            assertEquals(whole.glyphX(g), grouped.glyphX(g), 1e-4f, "glyph " + g);
            assertEquals(whole.glyphAdvance(g), grouped.glyphAdvance(g), 1e-4f);
        }
        for (int i = 0; i <= text.length(); i++) {
            assertEquals(whole.caretX(i, ShapedText.Affinity.DOWNSTREAM),
                    grouped.caretX(i, ShapedText.Affinity.DOWNSTREAM), 1e-4f, "caret " + i);
        }
    }

    @Test
    void arabicIndicGroupsShapedWholeComeOutReversedAndGroupedComeOutInOrder() {
        InputMask mask = InputMask.of("0000 0000");
        String text = "٤١١١ ٢٢٢٢";
        ShapedText whole = RULER.shape(text, FONT, ShapedText.Direction.LTR);
        assertTrue(whole.caretX(0, ShapedText.Affinity.DOWNSTREAM) > whole.caretX(5, ShapedText.Affinity.DOWNSTREAM),
                "shaped whole, the first group is to the right of the second: the defect");
        ShapedText grouped = MaskedLine.shape(RULER, text, FONT, mask.literals(text, 0));
        float previous = -1;
        for (int i = 0; i <= text.length(); i++) {
            float x = grouped.caretX(i, ShapedText.Affinity.DOWNSTREAM);
            assertTrue(x > previous, "in written order, at " + i);
            previous = x;
        }
    }

    @Test
    void aMarkOnTheFirstPositionAfterALiteralStaysInTheClusterItJoins() {
        InputMask mask = InputMask.of("**-**");
        String text = "ab-́c"; // the acute is the value's, drawn on the dash
        ShapedText grouped = MaskedLine.shape(RULER, text, FONT, mask.literals(text, 0));
        ShapedText whole = RULER.shape(text, FONT, ShapedText.Direction.LTR);
        assertEquals(whole.caretCount(), grouped.caretCount(), "no caret stop between the dash and its mark");
        assertEquals(whole.metrics().width(), grouped.metrics().width(), 1e-4f);
    }
}

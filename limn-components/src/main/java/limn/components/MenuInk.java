package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.TextRuler;

/**
 * The one drawing of a mnemonic underline, shared by the strip and the rows so a title and an
 * item cannot end up with two different marks. Not a widget and not a token table: it holds the
 * geometry that both callers would otherwise each derive from a measured prefix.
 */
final class MenuInk {

    private MenuInk() {
    }

    /**
     * Gap between the baseline and the rule, in points. Locked at every size step, like every
     * other weight-derived quantity: the rule is a hairline, and a hairline pushed proportionally
     * further from a 19&nbsp;pt baseline than from an 11&nbsp;pt one reads as a different mark
     * rather than as the same one, larger.
     */
    private static final float UNDERLINE_DROP = 1.5f;

    /**
     * @return the index in {@code text} of the first case-insensitive occurrence of
     *         {@code mnemonic}, or {@code -1} when there is no mnemonic, no text, or no occurrence
     */
    static int mnemonicIndex(String text, char mnemonic) {
        if (mnemonic == 0 || text == null) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.toUpperCase(text.charAt(i)) == mnemonic) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Draws the rule under character {@code index} of {@code text}, which was drawn with its left
     * edge at {@code textX} on {@code baseline}. A no-op for {@code index < 0}, so a caller may
     * hand it the result of {@link #mnemonicIndex} unchecked.
     */
    static void underlineMnemonic(Canvas canvas, TextRuler ruler, String text, int index,
                                  float textX, float baseline, Font font, Color ink) {
        if (index < 0 || text == null || index >= text.length()) {
            return;
        }
        // Measured as a prefix and a prefix-plus-one rather than as the glyph on its own: the
        // advance of a character in a run is not its advance in isolation, and a mark placed from
        // the isolated advance drifts off the letter it is meant to be under.
        float left = ruler.measure(text.substring(0, index), font).width();
        float right = ruler.measure(text.substring(0, index + 1), font).width();
        float y = baseline + UNDERLINE_DROP;
        canvas.drawLine(textX + left, y, textX + right, y, Strokes.HAIRLINE, ink);
    }
}

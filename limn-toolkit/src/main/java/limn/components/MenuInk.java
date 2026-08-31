package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.ShapedText;

/**
 * The one drawing of a mnemonic underline, shared by the strip and the rows so a title and an
 * item cannot end up with two different marks. Not a widget and not a token table: it holds the
 * geometry that both callers would otherwise each derive for themselves, and getting it from the
 * line's own shaping is the part neither caller should have to remember.
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
     * Draws the rule under the character at {@code index} of {@code line}, which was drawn with its
     * left edge at {@code textX} on {@code baseline}. A no-op for {@code index < 0} or a null
     * line, so a caller may hand it the result of {@link #mnemonicIndex} unchecked.
     *
     * <p><b>The line itself, not a ruler and a string to re-shape.</b> The mark's position comes
     * out of that line's shaping rather than out of arithmetic about it, so it has to be the very
     * line the caller painted — and the only way to guarantee that is to be handed it. Re-shaping
     * here could only ever reproduce it, and had no way to reproduce one thing: the paragraph
     * direction the caller resolved. A title or an item label with no strong character of its own
     * falls back to the direction of the interface around it, which is a fact about the widget and
     * not about the string, so a mark placed from a line shaped without it marks the right cluster
     * of the wrong paragraph.
     */
    static void underlineMnemonic(Canvas canvas, ShapedText line, int index,
                                  float textX, float baseline, Color ink) {
        if (index < 0 || line == null || index >= line.text().length()) {
            return;
        }
        // Placed from the shaping the line is PAINTED with, never from a prefix width. A prefix
        // width is the x of the k-th drawn character only while drawing walks the string left to
        // right in logical order, and it no longer does: drawing a String shapes it, so an Arabic
        // title paints its first letter at the RIGHT end of the run while the prefix rule puts the
        // mark at the left end — a whole word away, under a different letter. Width was wrong with
        // it, and for a second reason that survives even in a left-to-right line: a letter shaped
        // at the END of a prefix takes its final form, and the same letter inside the whole word
        // takes its medial one, so the two prefixes differ by an advance neither glyph has.
        //
        // Both edges come off the one shaped value the canvas drew, so the rule cannot drift from
        // the glyph it marks.
        // Widened to the caret stop AFTER the one the index lands on, rather than to index + 1: a
        // ligature is one cluster with one stop, so in "Office" the f at index 1 and the whole ffi
        // glyph share a stop, and index + 1 would snap back onto it and mark a zero-width nothing.
        // The unit that can be underlined is the cluster the character is drawn in.
        int stop = line.caretOrdinal(index);
        // The cluster's leading edge and its trailing edge, taken with the affinity that names each
        // — min and max because inside a right-to-left run the trailing edge is the LEFT one, and
        // a rule from leading to trailing would be drawn backwards there.
        float leading = line.caretAt(line.caretIndex(stop)).x(ShapedText.Affinity.DOWNSTREAM);
        float trailing = line.caretAt(line.caretIndex(stop + 1)).x(ShapedText.Affinity.UPSTREAM);
        float y = baseline + UNDERLINE_DROP;
        canvas.drawLine(textX + Math.min(leading, trailing), y,
                textX + Math.max(leading, trailing), y, Strokes.HAIRLINE, ink);
    }
}

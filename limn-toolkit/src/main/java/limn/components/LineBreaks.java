package limn.components;

import limn.graphics.ShapedText;

import java.text.BreakIterator;

/**
 * The greedy line-break step {@link Label}'s wrap and {@link TextArea}'s soft wrap share: where
 * one line of a paragraph ends, given a shaped paragraph, a break iterator and a width budget.
 *
 * <p>One copy, because the rule is subtle in three places at once &mdash; the budget is asked of
 * the <em>paragraph's</em> shaping ({@link ShapedText#fitEnd}/{@link ShapedText#advanceTo} are
 * budgets, not promises about substrings), the break opportunity is the locale's
 * ({@link BreakIterator}), and trailing whitespace hangs past the margin rather than spending the
 * budget &mdash; and a rule copied twice is a rule that will differ two ways within a year. What
 * is genuinely per-widget stays with the widget: {@code Label} skips a segment that trims to
 * nothing (prose paints no blank row), {@code TextArea} keeps it (every buffer index must live on
 * some row), and both decide for themselves what to re-shape and what to draw.
 */
final class LineBreaks {

    private LineBreaks() {
    }

    /**
     * Where the line starting at {@code start} ends: the first index of the <em>next</em> line,
     * untrimmed, so consecutive calls tile the paragraph. Returns the text length for the last
     * line.
     *
     * <p>The walk: {@link ShapedText#fitEnd} names the hard cut the budget allows; the last break
     * opportunity at or before it is always acceptable, because trimming can only make a candidate
     * narrower. Then walk <em>forward</em> while the <b>trimmed</b> candidate still fits: that is
     * the trailing whitespace hanging past the margin, and it is what lets a word whose only
     * overflow is the space after it stay on its line &mdash; and what lets an arbitrarily long
     * whitespace run hang on the line it follows instead of wrapping as blank lines. When not one
     * opportunity fits &mdash; a word longer than the line, or a script the locale has no rule for
     * &mdash; take as many clusters as fit, and at least one, so the walk cannot fail to advance.
     *
     * @param paragraph the whole line-less paragraph as one shaping; budgets are asked of it
     * @param breaks    a line-break iterator already {@linkplain BreakIterator#setText set} to
     *                  {@code paragraph.text()}
     * @param start     char index this line starts at, on a caret stop
     * @param maxWidth  room per line, in logical points; positive
     * @return the untrimmed end of this line, in {@code (start, text.length()]}
     */
    static int rowEnd(ShapedText paragraph, BreakIterator breaks, int start, float maxWidth) {
        String text = paragraph.text();
        int length = text.length();
        int fit = paragraph.fitEnd(start, maxWidth);   // the trailing space still counts here
        if (fit >= length) {
            return length;
        }
        int b = breaks.preceding(fit + 1);
        if (b <= start) {
            b = start;
        }
        for (int next = breaks.following(b); next != BreakIterator.DONE && next <= length;
                next = breaks.following(next)) {
            if (paragraph.advanceTo(trimEnd(text, start, next))
                    - paragraph.advanceTo(start) > maxWidth) {
                break;
            }
            b = next;
        }
        if (b <= start) {
            // Not one break opportunity fits. Take as many CLUSTERS as fit — one character per
            // line would be the other reading and it is not a line break, it is a column — and at
            // least one, so the walk cannot fail to advance.
            return Math.max(paragraph.fitEnd(start, maxWidth),
                    paragraph.caretIndex(paragraph.caretOrdinal(start) + 1));
        }
        return b;
    }

    /**
     * {@code end} with the run of whitespace immediately before it dropped, never below
     * {@code start}. {@link Character#isWhitespace} is exactly the right predicate and was
     * checked: it is false for the non-breaking spaces U+00A0, U+2007 and U+202F, which must
     * never be dropped, and true for U+3000 IDEOGRAPHIC SPACE, which is a break opportunity.
     */
    static int trimEnd(String text, int start, int end) {
        int cut = end;
        while (cut > start) {
            int cp = text.codePointBefore(cut);
            if (!Character.isWhitespace(cp)) {
                break;
            }
            cut -= Character.charCount(cp);
        }
        return cut;
    }
}

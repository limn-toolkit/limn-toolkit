package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.AccessibleNode;
import limn.accessibility.TextFacet;
import limn.accessibility.ValueFacet;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The string a node answers {@code org.a11y.atspi.Text} with, and the arithmetic every method of that
 * interface needs: offsets in characters, and the boundaries a granularity or a boundary type cuts
 * the string at.
 *
 * <p><b>Characters here, UTF-16 units everywhere else.</b> The model counts in UTF-16 code units
 * ({@link TextFacet}); AT-SPI counts characters (ADR 039 §2.3), so an offset crosses from one to the
 * other in {@link #charsOf} and {@link #unitsOf} and nowhere else, and a boundary is found on the
 * units and reported in characters.
 *
 * <p><b>Two kinds of node serve the interface</b> (settled linux-value-text): a node with a
 * {@code TextFacet}, whose caret and selection are its own, and a node with a {@code ValueFacet}
 * whose display form is not empty — a date segment's "15" or "empty", a spinner's "07:30" — which
 * is read-only text with no caret and no selection. A node with both answers from its text.
 *
 * <p><b>What a line is.</b> The facet carries no soft wrap, so a line and a paragraph are what a
 * line feed delimits; a wrapped line in a text area reads as its whole paragraph. The word and
 * sentence boundaries are {@link BreakIterator}'s under the node's own locale. Each boundary type
 * follows the shape the AT-SPI boundary names: {@code WORD_START} runs from the start of the word
 * at or before the offset to the start of the next word, {@code LINE_START} from a line's first
 * character through its line feed, and the {@code _END} types from one end to the next. A
 * granularity is answered as libatspi 2.60.6's own fallback reads it (CHAR as CHAR, WORD as
 * WORD_START, SENTENCE as SENTENCE_START, LINE as LINE_START;
 * readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt), and PARAGRAPH as LINE_START:
 * libatspi's fallback names no boundary for it, and on the Fedora KDE 44 guest GTK 4.22.4's text
 * view answers a paragraph as what a line feed delimits while GTK 3's ATK bridge answers
 * ('', -1, -1) (readings/fedora-gtk4-interface-replies.txt and fedora-gtk3-interface-replies.txt,
 * section 4, scripts/a11y/linux/read-gtk-interface-replies.py, 2026-09-15). GTK 4 leaves the line
 * feed out of a line and a paragraph; the ATK bridge's line keeps it, and so does this one.
 */
final class AtspiText {

    private AtspiText() {
    }

    /**
     * What a node's Text answers from.
     *
     * @param text           the whole string
     * @param caret          the caret, in UTF-16 units; 0 for text with no caret
     * @param selectionStart where the selection begins, in units
     * @param selectionEnd   where it ends; equal to the start when there is none
     * @param locale         the language the text is in, for words and sentences
     */
    record Source(String text, int caret, int selectionStart, int selectionEnd, Locale locale) {

        boolean hasSelection() {
            return selectionStart != selectionEnd;
        }

        int length() {
            return text.codePointCount(0, text.length());
        }
    }

    /**
     * The string a node serves Text from, or {@code null} when it serves none.
     *
     * @param node the node
     * @return its text source
     */
    static Source of(AccessibleNode node) {
        Locale locale = node.locale() == null ? Locale.ROOT : node.locale();
        TextFacet text = node.text();
        if (text != null) {
            return new Source(text.text(), clamp(text.text(), text.caretOffset()),
                    clamp(text.text(), text.selectionStart()), clamp(text.text(), text.selectionEnd()),
                    locale);
        }
        ValueFacet value = node.value();
        if (value != null && value.text() != null && !value.text().isEmpty()) {
            return new Source(value.text(), 0, 0, 0, locale);
        }
        return null;
    }

    /**
     * Whether a node serves Text at all: {@link #of} is not null for it. Allocates nothing.
     *
     * @param node the node
     * @return whether it has a text, or a value whose display form is not empty
     */
    static boolean serves(AccessibleNode node) {
        if (node.text() != null) {
            return true;
        }
        ValueFacet value = node.value();
        return value != null && value.text() != null && !value.text().isEmpty();
    }

    private static int clamp(String text, int units) {
        return Math.max(0, Math.min(text.length(), units));
    }

    /** A UTF-16 offset as a character offset, clamped into the text. */
    static int charsOf(String text, int units) {
        int at = clamp(text, units);
        return text.codePointCount(0, at);
    }

    /**
     * A character offset as a UTF-16 offset, clamped into the text; a negative offset is the start
     * and one past the last character is the end.
     */
    static int unitsOf(String text, int chars) {
        if (chars <= 0) {
            return 0;
        }
        int count = text.codePointCount(0, text.length());
        return chars >= count ? text.length() : text.offsetByCodePoints(0, chars);
    }

    /**
     * The boundary type a granularity is answered as.
     *
     * @return the {@code Atspi.TextBoundaryType}, or -1 for a granularity this platform does not
     *         name
     */
    static int boundaryOfGranularity(int granularity) {
        return switch (granularity) {
            case Atspi.TEXT_GRANULARITY_CHAR -> Atspi.TEXT_BOUNDARY_CHAR;
            case Atspi.TEXT_GRANULARITY_WORD -> Atspi.TEXT_BOUNDARY_WORD_START;
            case Atspi.TEXT_GRANULARITY_SENTENCE -> Atspi.TEXT_BOUNDARY_SENTENCE_START;
            case Atspi.TEXT_GRANULARITY_LINE, Atspi.TEXT_GRANULARITY_PARAGRAPH ->
                    Atspi.TEXT_BOUNDARY_LINE_START;
            default -> -1;
        };
    }

    /** Which of the three segments around an offset a method asks for. */
    enum Where { BEFORE, AT, AFTER }

    /**
     * The segment of {@code source} the boundary type cuts around a character offset.
     *
     * @param offset   the character offset asked about
     * @param boundary an {@code Atspi.TextBoundaryType}
     * @param where    the segment at the offset, or the one before or after it
     * @return {@code {start, end}} in characters; an empty range at the edge when there is no such
     *         segment, and {@code null} for a boundary type this platform does not name
     */
    static int[] segment(Source source, int offset, int boundary, Where where) {
        String text = source.text();
        int[] cuts = boundaries(text, boundary, source.locale());
        if (cuts == null) {
            return null;
        }
        int length = text.length();
        int at = unitsOf(text, offset);
        if (boundary == Atspi.TEXT_BOUNDARY_CHAR && where == Where.AT && at >= length) {
            return new int[] {charsOf(text, length), charsOf(text, length)};
        }
        // The segment [cuts[i], cuts[i+1]) holding the offset; an offset at the very end belongs
        // to the last segment, where a caret after the last character reads its line or word.
        int i = 0;
        while (i + 2 < cuts.length && cuts[i + 1] <= at) {
            i++;
        }
        int index = switch (where) {
            case BEFORE -> i - 1;
            case AT -> i;
            case AFTER -> i + 1;
        };
        if (index < 0) {
            return new int[] {0, 0};
        }
        if (index + 1 >= cuts.length) {
            int end = charsOf(text, length);
            return new int[] {end, end};
        }
        return new int[] {charsOf(text, cuts[index]), charsOf(text, cuts[index + 1])};
    }

    /**
     * Every cut a boundary type makes in the text, ascending, always holding 0 and the length.
     *
     * @return the cuts in UTF-16 units, or {@code null} for a boundary type this platform does not
     *         name
     */
    private static int[] boundaries(String text, int boundary, Locale locale) {
        int length = text.length();
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        cuts.add(length);
        switch (boundary) {
            case Atspi.TEXT_BOUNDARY_CHAR -> {
                for (int i = 0; i < length; i += Character.charCount(text.codePointAt(i))) {
                    cuts.add(i);
                }
            }
            case Atspi.TEXT_BOUNDARY_WORD_START, Atspi.TEXT_BOUNDARY_WORD_END -> {
                BreakIterator words = BreakIterator.getWordInstance(locale);
                words.setText(text);
                int start = words.first();
                for (int end = words.next(); end != BreakIterator.DONE;
                        start = end, end = words.next()) {
                    if (isWord(text, start, end)) {
                        cuts.add(boundary == Atspi.TEXT_BOUNDARY_WORD_START ? start : end);
                    }
                }
            }
            case Atspi.TEXT_BOUNDARY_SENTENCE_START, Atspi.TEXT_BOUNDARY_SENTENCE_END -> {
                BreakIterator sentences = BreakIterator.getSentenceInstance(locale);
                sentences.setText(text);
                for (int cut = sentences.first(); cut != BreakIterator.DONE; cut = sentences.next()) {
                    if (boundary == Atspi.TEXT_BOUNDARY_SENTENCE_START) {
                        cuts.add(cut);
                    } else {
                        int end = cut;
                        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                            end--;
                        }
                        cuts.add(end);
                    }
                }
            }
            case Atspi.TEXT_BOUNDARY_LINE_START, Atspi.TEXT_BOUNDARY_LINE_END -> {
                for (int i = 0; i < length; i++) {
                    if (text.charAt(i) == '\n') {
                        cuts.add(boundary == Atspi.TEXT_BOUNDARY_LINE_START ? i + 1 : i);
                    }
                }
            }
            default -> {
                return null;
            }
        }
        int[] out = new int[cuts.size()];
        int i = 0;
        for (int cut : cuts) {
            out[i++] = cut;
        }
        return out;
    }

    private static boolean isWord(String text, int start, int end) {
        for (int i = start; i < end; i += Character.charCount(text.codePointAt(i))) {
            if (Character.isLetterOrDigit(text.codePointAt(i))) {
                return true;
            }
        }
        return false;
    }
}

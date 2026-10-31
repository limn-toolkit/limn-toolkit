package limn.graphics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import org.jspecify.annotations.Nullable;

/**
 * Text in more than one style: paragraphs of pieces, each piece with the {@link TextStyle} it
 * differs by, each paragraph with its {@link ParagraphStyle}. A value, immutable, which a label
 * shows ({@code Label.setText(StyledText)}) and {@link StyledLayout} lays out for any canvas.
 *
 * <p><b>Built from pieces, never from offsets.</b> A style stored as "characters 7 to 18" is a
 * fact about one language's sentence, and a translation moves the words. So a styled piece is
 * either appended in its place, or is an argument of a translated pattern, which lands wherever
 * the translation put its placeholder:
 *
 * <pre>{@code
 * // "Total: {0}, due {1}" in English; the translation decides where the two land.
 * StyledText due = StyledText.of(Strings.TOTAL_DUE,
 *         StyledText.span(amount, TextStyle.of().weight(600)),
 *         StyledText.span(date, TextStyle.of().ink(Tone.WARNING)));
 *
 * StyledText terms = StyledText.builder()
 *         .paragraph(ParagraphStyle.of().text(TextStyle.of().bold().scale(1.2f)))
 *         .append("Before you sign")
 *         .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER))
 *         .append("Read the ").append("whole", TextStyle.of().italic()).append(" contract.")
 *         .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER))
 *         .append("Keep a copy.")
 *         .build();
 * }</pre>
 *
 * <p><b>It follows the language.</b> A piece or a pattern given as an {@link I18nString} is read
 * in the language in effect each time the text is laid out, as a label's plain text is; so are
 * the digits of a numbered list. A {@link Number} argument is written as {@link I18nString#format}
 * writes one. A line break ({@code \n}) inside a piece starts a new paragraph with the same style;
 * a line separator (U+2028) breaks the line and keeps the paragraph, as a hard line break in a
 * document does: no spacing, no first-line indent and no new list marker after it.
 *
 * <p>There is no markup: no tag in a string is read, so text from anywhere is shown as written.
 */
public final class StyledText {

    /** A piece of text and its style: an argument of a pattern, or a piece appended. */
    public static final class Span {
        private final Object text;
        private final TextStyle style;

        private Span(Object text, TextStyle style) {
            this.text = text;
            this.style = style;
        }

        /** @return the style the piece differs by */
        public TextStyle style() {
            return style;
        }

        /** @return the piece's text as it reads now */
        public String text() {
            return read(text);
        }

        /** The value a typed placeholder formats: a number as itself, a translated text as it reads now. */
        Object value() {
            return text instanceof I18nString i18n ? i18n.get() : text;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Span that && text.equals(that.text) && style.equals(that.style);
        }

        @Override
        public int hashCode() {
            return 31 * text.hashCode() + style.hashCode();
        }
    }

    /** A piece of a paragraph: a span, or a translated pattern with styled arguments. */
    private record Piece(@Nullable Span span, @Nullable I18nString pattern, List<Span> arguments,
                         TextStyle style) {
    }

    /** A paragraph as built: its style and pieces, before the language is read. */
    private record Block(ParagraphStyle style, List<Piece> pieces) {
    }

    /** The private-use characters that stand for a pattern's arguments while it is formatted. */
    private static final char LINE_SEPARATOR = '\u2028';
    private static final char ARGUMENT_MARK = '';
    private static final int MAX_ARGUMENTS = 256;

    private static final StyledText EMPTY = new StyledText(List.of(new Block(ParagraphStyle.of(), List.of())));

    private final List<Block> blocks;

    private StyledText(List<Block> blocks) {
        this.blocks = blocks;
    }

    // ------------------------------------------------------------------ making one

    /** @return text with nothing in it: one empty paragraph */
    public static StyledText empty() {
        return EMPTY;
    }

    /**
     * @param text text in the style of whatever shows it
     * @return one piece, unstyled
     */
    public static StyledText of(String text) {
        return builder().append(text).build();
    }

    /**
     * @param text  the text
     * @param style the style it differs by
     * @return one piece
     */
    public static StyledText of(String text, TextStyle style) {
        return builder().append(text, style).build();
    }

    /**
     * @param text text that follows the language
     * @return one piece, unstyled
     */
    public static StyledText of(I18nString text) {
        return builder().append(text).build();
    }

    /**
     * A translated pattern whose arguments are styled: the sentence is the translation's, and each
     * argument lands, in its style, wherever the translation put its {@code {n}}.
     *
     * @param pattern   a string with {@code {0}}-style placeholders, as {@link I18nString#format} reads them
     * @param arguments the styled arguments, by position
     * @return one paragraph, or more where the translation has line breaks
     */
    public static StyledText of(I18nString pattern, Span... arguments) {
        return builder().append(pattern, arguments).build();
    }

    /**
     * A styled piece, for an argument of {@link #of(I18nString, Span...)}.
     *
     * @param text  a {@code String}, an {@link I18nString}, a {@link Number}, or anything whose
     *              {@code toString} is its text
     * @param style the style it differs by
     * @return the span
     */
    public static Span span(Object text, TextStyle style) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        return new Span(text, style);
    }

    /** @return a builder with one empty paragraph open */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link StyledText} piece by piece and paragraph by paragraph. Not thread-safe. */
    public static final class Builder {
        private final List<Block> done = new ArrayList<>();
        private ParagraphStyle style = ParagraphStyle.of();
        private List<Piece> pieces = new ArrayList<>();

        private Builder() {
        }

        /**
         * Starts a new paragraph in {@code paragraphStyle}; the first call, before anything is
         * appended, styles the first paragraph instead.
         *
         * @param paragraphStyle the new paragraph's style
         * @return this builder
         */
        public Builder paragraph(ParagraphStyle paragraphStyle) {
            Objects.requireNonNull(paragraphStyle, "paragraphStyle");
            if (!done.isEmpty() || !pieces.isEmpty()) {
                done.add(new Block(style, List.copyOf(pieces)));
                pieces = new ArrayList<>();
            }
            style = paragraphStyle;
            return this;
        }

        /** @return this builder, a new paragraph started in the style the last one had */
        public Builder paragraph() {
            return paragraph(style);
        }

        /**
         * @param text text, in the paragraph's style
         * @return this builder
         */
        public Builder append(String text) {
            return append(text, TextStyle.of());
        }

        /**
         * @param text      the text
         * @param textStyle the style it differs by
         * @return this builder
         */
        public Builder append(String text, TextStyle textStyle) {
            pieces.add(new Piece(span(text, textStyle), null, List.of(), textStyle));
            return this;
        }

        /**
         * @param text text that follows the language, in the paragraph's style
         * @return this builder
         */
        public Builder append(I18nString text) {
            return append(text, TextStyle.of());
        }

        /**
         * @param text      text that follows the language
         * @param textStyle the style it differs by
         * @return this builder
         */
        public Builder append(I18nString text, TextStyle textStyle) {
            pieces.add(new Piece(span(text, textStyle), null, List.of(), textStyle));
            return this;
        }

        /**
         * A translated pattern with styled arguments, as {@link StyledText#of(I18nString, Span...)}.
         *
         * @param pattern   the pattern
         * @param arguments its styled arguments, by position
         * @return this builder
         */
        public Builder append(I18nString pattern, Span... arguments) {
            return append(pattern, TextStyle.of(), arguments);
        }

        /**
         * A translated pattern in {@code textStyle}, its arguments styled over it.
         *
         * @param pattern   the pattern
         * @param textStyle the style the pattern's own words differ by
         * @param arguments its styled arguments, by position
         * @return this builder
         */
        public Builder append(I18nString pattern, TextStyle textStyle, Span... arguments) {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(textStyle, "textStyle");
            Objects.requireNonNull(arguments, "arguments");
            if (arguments.length > MAX_ARGUMENTS) {
                throw new IllegalArgumentException("at most " + MAX_ARGUMENTS + " arguments");
            }
            for (Span argument : arguments) {
                Objects.requireNonNull(argument, "argument");
            }
            pieces.add(new Piece(null, pattern, List.of(arguments), textStyle));
            return this;
        }

        /**
         * Appends another styled text: its first paragraph continues this one in this one's
         * style, and its later paragraphs follow with their own.
         *
         * @param other the text to append
         * @return this builder
         */
        public Builder append(StyledText other) {
            Objects.requireNonNull(other, "other");
            for (int i = 0; i < other.blocks.size(); i++) {
                Block block = other.blocks.get(i);
                if (i > 0) {
                    paragraph(block.style());
                }
                pieces.addAll(block.pieces());
            }
            return this;
        }

        /** @return the text built so far */
        public StyledText build() {
            List<Block> all = new ArrayList<>(done);
            all.add(new Block(style, List.copyOf(pieces)));
            return new StyledText(List.copyOf(all));
        }
    }

    // ------------------------------------------------------------------ reading it

    /**
     * The plain text as it reads now, paragraphs joined by {@code \n}, without list markers: what
     * {@code Label.text()} answers for a styled label.
     */
    public String text() {
        StringBuilder out = new StringBuilder();
        List<Paragraph> paragraphs = paragraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(paragraphs.get(i).text().replace(LINE_SEPARATOR, '\n'));
        }
        return out.toString();
    }

    /**
     * The text a reader is given: each paragraph on its line, a list item led by its marker, so a
     * numbered step is heard with its number.
     */
    public String readerText() {
        StringBuilder out = new StringBuilder();
        List<Paragraph> paragraphs = paragraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            Paragraph paragraph = paragraphs.get(i);
            if (paragraph.marker() != null) {
                out.append(paragraph.marker()).append(' ');
            }
            out.append(paragraph.text().replace(LINE_SEPARATOR, '\n'));
        }
        return out.toString();
    }

    /** One paragraph as it reads now: its text, its style, its runs of style and its marker. */
    record Paragraph(ParagraphStyle style, String text, List<Run> runs, @Nullable String marker) {
    }

    /** A stretch {@code [start, end)} of a paragraph's text in one style, over the paragraph's. */
    record Run(int start, int end, TextStyle style) {
    }

    /**
     * The paragraphs in the language in effect now, line breaks split into paragraphs and list
     * markers counted; each run's style is the piece's over the paragraph's text style.
     */
    List<Paragraph> paragraphs() {
        List<Paragraph> out = new ArrayList<>();
        int[] counts = new int[ParagraphStyle.MAX_LEVEL + 1];
        ParagraphStyle.ListMarker[] kinds = new ParagraphStyle.ListMarker[ParagraphStyle.MAX_LEVEL + 1];
        for (Block block : blocks) {
            List<StringBuilder> texts = new ArrayList<>();
            List<List<Run>> runs = new ArrayList<>();
            texts.add(new StringBuilder());
            runs.add(new ArrayList<>());
            TextStyle base = block.style().textStyle();
            for (Piece piece : block.pieces()) {
                if (piece.span() != null) {
                    add(texts, runs, piece.span().text(), piece.span().style().over(base));
                } else {
                    addPattern(texts, runs, piece, base);
                }
            }
            for (int i = 0; i < texts.size(); i++) {
                // An empty line in a list, a trailing line break's, is no item: no marker, no count.
                String marker = texts.get(i).isEmpty() && block.style().listOrNull() != null ? null
                        : marker(block.style(), counts, kinds);
                out.add(new Paragraph(block.style(), texts.get(i).toString(), List.copyOf(runs.get(i)), marker));
            }
        }
        return out;
    }

    /**
     * Appends {@code text} in {@code style}, opening a paragraph at each line break; a line
     * separator (U+2028) stays in its paragraph, where the layout breaks the line at it.
     */
    private static void add(List<StringBuilder> texts, List<List<Run>> runs, String text, TextStyle style) {
        int from = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean end = i == text.length();
            char c = end ? 0 : text.charAt(i);
            if (end || c == '\n' || c == '\r' || c == ' ') {
                StringBuilder into = texts.get(texts.size() - 1);
                if (i > from) {
                    int start = into.length();
                    into.append(text, from, i);
                    List<Run> list = runs.get(runs.size() - 1);
                    Run last = list.isEmpty() ? null : list.get(list.size() - 1);
                    if (last != null && last.end() == start && last.style().equals(style)) {
                        list.set(list.size() - 1, new Run(last.start(), into.length(), style));
                    } else {
                        list.add(new Run(start, into.length(), style));
                    }
                }
                if (!end) {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    texts.add(new StringBuilder());
                    runs.add(new ArrayList<>());
                }
                from = i + 1;
            }
        }
    }

    /**
     * Formats a pattern with a private-use mark in each argument's place, then appends the
     * pattern's own words in the piece's style and each argument, where its mark landed, in its
     * style over that.
     */
    private static void addPattern(List<StringBuilder> texts, List<List<Run>> runs, Piece piece, TextStyle base) {
        List<Span> arguments = piece.arguments();
        String[] shown = new String[arguments.size()];
        String formatted = withMarks(piece.pattern(), arguments, shown);
        TextStyle words = piece.style().over(base);
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            int index = c - ARGUMENT_MARK;
            if (index >= 0 && index < arguments.size()) {
                if (!plain.isEmpty()) {
                    add(texts, runs, plain.toString(), words);
                    plain.setLength(0);
                }
                Span argument = arguments.get(index);
                add(texts, runs, shown[index], argument.style().over(words));
            } else {
                plain.append(c);
            }
        }
        if (!plain.isEmpty()) {
            add(texts, runs, plain.toString(), words);
        }
    }

    /**
     * {@code pattern} formatted as {@link I18nString#format} formats it, with a mark in each
     * argument's place and the argument's text, as its placeholder formats it, in {@code shown}:
     * a {@code {0,number,#,##0.00}} or a {@code {0,choice,…}} formats the argument's value, and
     * only its place in the sentence is marked. A translation that does not parse falls back to
     * the English, and an English that does not parse is shown as written.
     */
    private static String withMarks(I18nString pattern, List<Span> arguments, String[] shown) {
        for (int i = 0; i < shown.length; i++) {
            shown[i] = arguments.get(i).text();
        }
        if (arguments.isEmpty()) {
            return pattern.get();
        }
        String formatted = withMarks(pattern.get(), arguments, shown);
        if (formatted == null) {
            formatted = withMarks(pattern.english(), arguments, shown);
        }
        return formatted != null ? formatted : pattern.english();
    }

    /** One try of {@link #withMarks(I18nString, List, String[])}; {@code null} where it does not parse. */
    private static @Nullable String withMarks(String pattern, List<Span> arguments, String[] shown) {
        java.util.Locale numbers = limn.i18n.internal.NumberLocale.inEffect();
        try {
            java.text.MessageFormat format = new java.text.MessageFormat(pattern, numbers);
            java.text.Format[] own = format.getFormatsByArgumentIndex();
            Object[] values = new Object[arguments.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = arguments.get(i).value();
            }
            Object[] marks = new Object[arguments.size()];
            for (int i = 0; i < marks.length; i++) {
                marks[i] = String.valueOf((char) (ARGUMENT_MARK + i));
                java.text.Format typed = i < own.length ? own[i] : null;
                if (typed == null) {
                    shown[i] = arguments.get(i).text();
                    continue;
                }
                try {
                    String text = typed.format(values[i]);
                    // A choice's branch may hold placeholders of its own, as MessageFormat reads them.
                    if (typed instanceof java.text.ChoiceFormat && text.indexOf('{') >= 0) {
                        text = new java.text.MessageFormat(text, numbers).format(values);
                    }
                    shown[i] = text;
                } catch (IllegalArgumentException notThatKind) {
                    shown[i] = arguments.get(i).text(); // text where the placeholder wants a number
                }
                format.setFormatByArgumentIndex(i, null);
            }
            return format.format(marks);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** The marker of a paragraph in a list, counting it; {@code null} out of a list, which ends every count. */
    private static @Nullable String marker(ParagraphStyle style, int[] counts, ParagraphStyle.ListMarker[] kinds) {
        ParagraphStyle.ListMarker kind = style.listOrNull();
        if (kind == null) {
            Arrays.fill(counts, 0);
            Arrays.fill(kinds, null);
            return null;
        }
        int level = style.level();
        for (int deeper = level + 1; deeper < counts.length; deeper++) {
            counts[deeper] = 0;
            kinds[deeper] = null;
        }
        if (kinds[level] != kind) {
            counts[level] = 0;
            kinds[level] = kind;
        }
        counts[level] = style.listNumber() > 0 ? style.listNumber() : counts[level] + 1;
        return switch (kind) {
            case BULLET -> switch (level % 3) {
                case 0 -> "•";
                case 1 -> "◦";
                default -> "▪";
            };
            case NUMBER -> switch (level % 3) {
                case 0 -> NumberFormats.number(0).apply(counts[level]) + ".";
                case 1 -> letters(counts[level]) + ".";
                default -> roman(counts[level]) + ".";
            };
        };
    }

    /** 1 → a, 26 → z, 27 → aa: the letters of a second-level list. */
    static String letters(int n) {
        StringBuilder out = new StringBuilder();
        int value = n;
        while (value > 0) {
            value--;
            out.insert(0, (char) ('a' + value % 26));
            value /= 26;
        }
        return out.toString();
    }

    /** 1 → i, 4 → iv, 1990 → mcmxc: the numerals of a third-level list. */
    static String roman(int n) {
        if (n <= 0 || n >= 4000) {
            return Integer.toString(n);
        }
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        StringBuilder out = new StringBuilder();
        int value = n;
        for (int i = 0; i < values.length; i++) {
            while (value >= values[i]) {
                out.append(numerals[i]);
                value -= values[i];
            }
        }
        return out.toString();
    }

    private static String read(Object text) {
        if (text instanceof String s) {
            return s;
        }
        if (text instanceof I18nString i18n) {
            return i18n.get();
        }
        if (text instanceof Number number) {
            return I18nString.literal("{0}").format(number);
        }
        return String.valueOf(text);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof StyledText that && blocks.equals(that.blocks);
    }

    @Override
    public int hashCode() {
        return blocks.hashCode();
    }

    @Override
    public String toString() {
        return "StyledText[" + text() + "]";
    }
}

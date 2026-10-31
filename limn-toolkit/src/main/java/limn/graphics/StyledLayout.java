package limn.graphics;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import limn.i18n.I18n;
import org.jspecify.annotations.Nullable;

/**
 * {@link StyledText} laid out at a width: its paragraphs shaped, broken into lines and placed, ready
 * to paint on any {@link Canvas}. What a label holds for its styled text, and what an application
 * drawing on a canvas of its own lays out with.
 *
 * <p><b>A paragraph is shaped as one, split only where the font changes.</b> Bidi is resolved over
 * the whole paragraph; a shaping run is a stretch of one font (family, size, weight, slant, raised
 * or lowered) and one bidi level, shaped with the paragraph as its context
 * ({@link TextRuler#shapeRun}); an ink, a background, an underline or a strike-through splits no
 * run, so a colour changed inside a word leaves the word's glyphs exactly as they are without it,
 * and the range is painted in its own paint ({@link Canvas#drawText(ShapedText, int, int, float,
 * float, Paint)}). Lines are broken over the whole paragraph at the opportunities the language's
 * line breaker reports; a line is as tall as its tallest run, and every run on it sits on its
 * baseline.
 *
 * <p>Immutable once made. Lay out again when the text, the base, the width or the language
 * changes, or when {@link #isCurrent} says the ruler would shape differently.
 */
public final class StyledLayout {

    /**
     * What the text is laid out over: the font and ink a piece that sets neither is drawn in, the
     * alignment of a paragraph that sets none, and the direction of the interface around it, which
     * a paragraph with no strong character of its own reads in and the base alignment's start and
     * end are read in. A paragraph's own alignment is read in the paragraph's own direction.
     *
     * @param font        the base font
     * @param ink         the base ink
     * @param alignment   the base alignment
     * @param whenNeutral the direction a paragraph with no strong character reads
     */
    public record Base(Font font, Ink ink, ParagraphStyle.Alignment alignment,
                       ShapedText.Direction whenNeutral) {
        public Base {
            Objects.requireNonNull(font, "font");
            Objects.requireNonNull(ink, "ink");
            Objects.requireNonNull(alignment, "alignment");
            Objects.requireNonNull(whenNeutral, "whenNeutral");
        }
    }

    /** No limit on the width: each paragraph is one line. */
    public static final float UNBOUNDED = Float.POSITIVE_INFINITY;

    /** Unicode's line separator: a line break inside a paragraph. */
    private static final char LINE_SEPARATOR = '\u2028';

    /** The gap between a list marker and its paragraph's text, in ems of the marker's font. */
    private static final float MARKER_GAP_EMS = 0.4f;

    /**
     * The second and third levels' bullets, U+25E6 and U+25AA, are drawn and not set in type: no
     * face the toolkit bundles has either, so they came out as the missing glyph's box. Drawn to
     * the measures of the bundled face's own bullet, U+2022, which every Roboto face has: an
     * advance of 0.337 em, its ink 0.2 em across, centred 0.368 em above the baseline.
     */
    private static final float DRAWN_BULLET_ADVANCE_EMS = 0.337f;
    private static final float DRAWN_BULLET_CENTRE_EMS = 0.368f;
    private static final float DRAWN_BULLET_SIZE_EMS = 0.2f;
    /** The ring's line, and the square's side against the disc's diameter (a square looks bigger). */
    private static final float DRAWN_RING_STROKE_EMS = 0.045f;
    private static final float DRAWN_SQUARE_SIDE_EMS = 0.18f;

    private final Base base;
    private final List<ParagraphLayout> paragraphs;
    private final float width;
    private final float height;
    private final int lineCount;
    private final boolean truncated;
    private final long epoch;

    private StyledLayout(Base base, List<ParagraphLayout> paragraphs, float width, float height,
                         int lineCount, boolean truncated, long epoch) {
        this.base = base;
        this.paragraphs = paragraphs;
        this.width = width;
        this.height = height;
        this.lineCount = lineCount;
        this.truncated = truncated;
        this.epoch = epoch;
    }

    /**
     * Lays {@code text} out.
     *
     * @param text     the text, read in the language in effect now
     * @param base     the font, ink, alignment and direction it is laid out over
     * @param maxWidth the width lines break at, in logical points; {@link #UNBOUNDED} breaks none
     * @param maxLines the most lines, 1 or more; {@code Integer.MAX_VALUE} for no limit
     * @param ellipsis whether a cut last line, or a line wider than {@code maxWidth}, ends with an
     *                 ellipsis; otherwise it is left whole for a clip to cut
     * @param ruler    the ruler that shapes it
     * @return the layout
     */
    public static StyledLayout of(StyledText text, Base base, float maxWidth, int maxLines,
                                  boolean ellipsis, TextRuler ruler) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(ruler, "ruler");
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be 1 or more: " + maxLines);
        }
        float limit = maxWidth > 0 && !Float.isNaN(maxWidth) ? maxWidth : UNBOUNDED;
        List<StyledText.Paragraph> source = text.paragraphs();
        float[] markerRoom = markerRoom(source, base, ruler);
        List<ParagraphLayout> laid = new ArrayList<>(source.size());
        float y = 0;
        float widest = 0;
        int lines = 0;
        boolean cut = false;
        for (int i = 0; i < source.size() && lines < maxLines; i++) {
            ParagraphLayout paragraph = new ParagraphLayout(source.get(i), base, ruler, markerRoom[i]);
            if (i > 0) {
                y += paragraph.style.spaceBefore();
            }
            boolean moreAfter = i < source.size() - 1;
            y = paragraph.breakLines(limit, maxLines - lines, moreAfter, ellipsis, y, ruler);
            lines += paragraph.lines.size();
            widest = Math.max(widest, paragraph.naturalWidth());
            cut |= paragraph.ellipsized;
            laid.add(paragraph);
            if (moreAfter && lines < maxLines) {
                y += paragraph.style.spaceAfter();
            } else if (moreAfter) {
                cut = true;
            }
        }
        return new StyledLayout(base, List.copyOf(laid), widest, y, lines, cut, ruler.epoch());
    }

    /**
     * The room each paragraph's list indent grows by so that the widest marker of its list fits
     * between the box's edge and the text: a "10." or a "viii." wider than the indent hung past
     * the edge, where the box cut it. Every item of one list grows alike, so their texts still
     * start together; a list is a run of list paragraphs, as its count is.
     */
    private static float[] markerRoom(List<StyledText.Paragraph> source, Base base, TextRuler ruler) {
        float[] room = new float[source.size()];
        int from = 0;
        float need = 0;
        for (int i = 0; i <= source.size(); i++) {
            StyledText.Paragraph p = i < source.size() ? source.get(i) : null;
            if (p == null || p.style().listOrNull() == null) {
                Arrays.fill(room, from, i, need);
                from = i + 1;
                need = 0;
                continue;
            }
            if (p.marker() != null) {
                ParagraphStyle style = p.style();
                Font font = style.textStyle().fontOver(base.font());
                float width = isDrawnBullet(p.marker()) ? DRAWN_BULLET_ADVANCE_EMS * font.size()
                        : ruler.shape(p.marker(), font, ShapedText.Direction.LTR).metrics().width();
                float edge = style.startIndent() + (style.level() + 1) * ParagraphStyle.LIST_INDENT_EMS * font.size();
                need = Math.max(need, width + MARKER_GAP_EMS * font.size() - edge);
            }
        }
        return room;
    }

    /** @return whether a list marker is one of the bullets drawn rather than set in type */
    private static boolean isDrawnBullet(@Nullable String marker) {
        return marker != null && marker.length() == 1 && (marker.charAt(0) == '\u25E6' || marker.charAt(0) == '\u25AA');
    }

    /** Draws a ring (U+25E6) or a small square (U+25AA) centred on {@code (cx, cy)}. */
    private static void paintBullet(Canvas canvas, char bullet, float cx, float cy, float em, Paint paint) {
        if (bullet == '\u25E6') {
            float stroke = DRAWN_RING_STROKE_EMS * em;
            canvas.drawCircle(cx, cy, (DRAWN_BULLET_SIZE_EMS * em - stroke) / 2, stroke, paint);
        } else {
            float side = DRAWN_SQUARE_SIDE_EMS * em;
            canvas.fillRect(cx - side / 2, cy - side / 2, side, side, paint);
        }
    }

    /** @return the widest line with its indents, in logical points */
    public float width() {
        return width;
    }

    /** @return the height of every line and the space between paragraphs, in logical points */
    public float height() {
        return height;
    }

    /** @return how many lines were laid out */
    public int lineCount() {
        return lineCount;
    }

    /** @return whether text was left out or cut short by the line limit or the width */
    public boolean isTruncated() {
        return truncated;
    }

    /** @return the first line's baseline, from the top; 0 for no line */
    public float firstBaseline() {
        for (ParagraphLayout paragraph : paragraphs) {
            if (!paragraph.lines.isEmpty()) {
                return paragraph.lines.get(0).baseline;
            }
        }
        return 0;
    }

    /** @return where one line ends and the next begins, from the top: what a page may be cut at */
    public float[] lineCuts() {
        List<Float> cuts = new ArrayList<>();
        boolean first = true;
        for (ParagraphLayout paragraph : paragraphs) {
            for (Line line : paragraph.lines) {
                if (!first) {
                    cuts.add(line.top);
                }
                first = false;
            }
        }
        float[] out = new float[cuts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = cuts.get(i);
        }
        return out;
    }

    /**
     * @param ruler the ruler a paint would shape with
     * @return whether this layout was shaped as {@code ruler} shapes now; a face resolved
     *         differently since (a family loaded, the default switched) means lay out again
     */
    public boolean isCurrent(TextRuler ruler) {
        long now = ruler.epoch();
        return now == 0 || now == epoch;
    }

    /**
     * Paints the text with every ink asked for its paint now.
     *
     * @param canvas   where to paint
     * @param x        the box's left edge
     * @param y        the box's top edge
     * @param boxWidth the box's width, which the lines are aligned in
     */
    public void paint(Canvas canvas, float x, float y, float boxWidth) {
        paint(canvas, x, y, boxWidth, Ink::paint);
    }

    /**
     * Paints the text, each ink turned into a paint by {@code resolve}: how a widget answers an ink
     * that means a palette's colour with its own palette, or draws everything in its disabled ink.
     *
     * @param canvas   where to paint
     * @param x        the box's left edge
     * @param y        the box's top edge
     * @param boxWidth the box's width, which the lines are aligned in
     * @param resolve  the paint for an ink, the backgrounds' included
     */
    public void paint(Canvas canvas, float x, float y, float boxWidth, Function<Ink, Paint> resolve) {
        paint(canvas, x, y, boxWidth, resolve, resolve);
    }

    /**
     * Paints the text, the glyphs' and lines' inks turned into paints by {@code resolve} and the
     * backgrounds' by {@code resolveBackground}: a disabled widget draws its text in its disabled
     * text colour and a background in its disabled fill, which keeps one readable on the other.
     *
     * @param canvas            where to paint
     * @param x                 the box's left edge
     * @param y                 the box's top edge
     * @param boxWidth          the box's width, which the lines are aligned in
     * @param resolve           the paint for the ink of glyphs, markers and lines
     * @param resolveBackground the paint for the ink of a background
     */
    public void paint(Canvas canvas, float x, float y, float boxWidth, Function<Ink, Paint> resolve,
                      Function<Ink, Paint> resolveBackground) {
        Objects.requireNonNull(canvas, "canvas");
        Objects.requireNonNull(resolve, "resolve");
        Objects.requireNonNull(resolveBackground, "resolveBackground");
        for (ParagraphLayout paragraph : paragraphs) {
            for (int i = 0; i < paragraph.lines.size(); i++) {
                paragraph.paintLine(canvas, paragraph.lines.get(i), i == 0, x, y, boxWidth, resolve,
                        resolveBackground);
            }
        }
    }

    // ================================================================== one paragraph

    /** A stretch of one font and one bidi level, shaped in its paragraph's context. */
    private static final class Shaping {
        final int start;
        final int end;
        final byte level;
        final ShapedText shaped;
        final float raise;          // positive: up
        final float tracking;       // logical points after every cluster; 0 in a joining script
        final TextDecorations decorations;
        final float[] boxes;

        Shaping(int start, int end, byte level, ShapedText shaped, float raise, float tracking,
                TextDecorations decorations) {
            this.start = start;
            this.end = end;
            this.level = level;
            this.shaped = shaped;
            this.raise = raise;
            this.tracking = tracking;
            this.decorations = decorations;
            this.boxes = new float[2 * Math.max(1, shaped.runs().size())];
        }

        /** The width of {@code [from, to)} of the paragraph, both inside this shaping. */
        float width(int from, int to) {
            int a = from - start;
            int b = to - start;
            float w = shaped.advanceTo(b) - shaped.advanceTo(a);
            if (tracking != 0) {
                w += tracking * (shaped.caretOrdinal(b) - shaped.caretOrdinal(a));
            }
            return w;
        }

        /** The left edge, within the shaped line, of {@code [from, to)}'s glyphs. */
        float left(int from, int to) {
            int n = shaped.selection(from - start, to - start, boxes);
            return n == 0 ? shaped.caretX(from - start, ShapedText.Affinity.DOWNSTREAM) : boxes[0];
        }
    }

    /** One line: its range of the paragraph, its metrics, where it sits. */
    private static final class Line {
        int start;
        int end;                    // trimmed: no trailing white space
        float width;                // of [start, end), with an ellipsis if there is one
        float ascent;
        float descent;
        float top;
        float baseline;
        float startOffset;          // the indent before the line, at its start edge
        boolean last;               // the paragraph's last line
        boolean forced;             // ends at a line separator (U+2028): never justified
        @Nullable ShapedText ellipsis;
        @Nullable TextStyle ellipsisStyle;
        float ellipsisRaise;
    }

    /** A piece of a line to place: a stretch of one shaping, and the room added on each side of it. */
    private record Chunk(Shaping shaping, int start, int end, float extraBefore, float extraAfter) {
    }

    private static final class ParagraphLayout {
        final StyledText.Paragraph source;
        final ParagraphStyle style;
        final String text;
        final ShapedText.Direction direction;
        final Font font;            // the paragraph's own base font
        final Ink ink;
        final ParagraphStyle.Alignment alignment;
        final boolean alignedByParagraph;
        final boolean baseRtl;
        final List<Shaping> shapings = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
        final @Nullable ShapedText marker;
        /** U+25E6 or U+25AA, drawn in place of {@link #marker}; {@code 0} for none. */
        final char drawnBullet;
        final float listIndent;
        boolean ellipsized;

        ParagraphLayout(StyledText.Paragraph source, Base base, TextRuler ruler, float markerRoom) {
            this.source = source;
            this.style = source.style();
            this.text = source.text();
            this.direction = style.directionOrNull() != null ? style.directionOrNull()
                    : ShapedText.Direction.of(text, base.whenNeutral());
            TextStyle own = style.textStyle();
            this.font = own.fontOver(base.font());
            this.ink = own.inkOrNull() != null ? own.inkOrNull() : base.ink();
            this.alignment = style.alignmentOrNull() != null ? style.alignmentOrNull() : base.alignment();
            this.alignedByParagraph = style.alignmentOrNull() != null;
            this.baseRtl = base.whenNeutral() == ShapedText.Direction.RTL;
            String markerText = source.marker();
            this.drawnBullet = isDrawnBullet(markerText) ? markerText.charAt(0) : 0;
            this.marker = markerText == null || drawnBullet != 0 ? null : ruler.shape(markerText, font, direction);
            this.listIndent = style.listOrNull() == null ? 0
                    : (style.level() + 1) * ParagraphStyle.LIST_INDENT_EMS * font.size() + markerRoom;
            shape(base, ruler);
        }

        /** Splits the paragraph where the font or the bidi level changes, and shapes each stretch. */
        private void shape(Base base, TextRuler ruler) {
            if (text.isEmpty()) {
                return;
            }
            Bidi bidi = new Bidi(text, direction == ShapedText.Direction.RTL
                    ? Bidi.DIRECTION_RIGHT_TO_LEFT : Bidi.DIRECTION_LEFT_TO_RIGHT);
            for (StyledText.Run run : source.runs()) {
                FontChoice choice = FontChoice.of(run.style(), base.font(), ruler);
                int at = run.start();
                while (at < run.end()) {
                    byte level = (byte) bidi.getLevelAt(at);
                    int end = at + 1;
                    while (end < run.end() && bidi.getLevelAt(end) == level) {
                        end++;
                    }
                    // Merge with the stretch before when the font and level are the same: a colour
                    // change between two runs of one style splits nothing.
                    ShapedText.Direction runDirection = (level & 1) != 0
                            ? ShapedText.Direction.RTL : ShapedText.Direction.LTR;
                    int from = at;
                    if (!shapings.isEmpty()) {
                        Shaping previous = shapings.get(shapings.size() - 1);
                        if (previous.end == at && previous.level == level
                                && previous.shaped.font().equals(choice.font) && previous.raise == choice.raise
                                && previous.tracking == choice.tracking(text, at, end)) {
                            from = previous.start;
                            shapings.remove(shapings.size() - 1);
                        }
                    }
                    ShapedText shaped = ruler.shapeRun(text, from, end, choice.font, runDirection);
                    shapings.add(new Shaping(from, end, level, shaped, choice.raise,
                            choice.tracking(text, from, end), choice.decorations));
                    at = end;
                }
            }
        }

        /** The width of {@code [from, to)} of the paragraph. */
        float width(int from, int to) {
            float w = 0;
            for (Shaping s : shapings) {
                int a = Math.max(from, s.start);
                int b = Math.min(to, s.end);
                if (a < b) {
                    w += s.width(a, b);
                }
            }
            return w;
        }

        float indentBefore(boolean firstLine) {
            return style.startIndent() + listIndent + (firstLine ? style.firstLineIndent() : 0);
        }

        /**
         * Breaks the paragraph into at most {@code room} lines from {@code top}, and answers the
         * top of what follows it.
         */
        float breakLines(float maxWidth, int room, boolean moreAfter, boolean ellipsis, float top,
                         TextRuler ruler) {
            int length = text.length();
            BreakIterator breaks = limn.i18n.internal.LocaleServices.lineBreaks(I18n.locale());
            breaks.setText(text);
            BreakIterator clusters = BreakIterator.getCharacterInstance();
            clusters.setText(text);
            float y = top;
            int start = 0;
            do {
                boolean firstLine = lines.isEmpty();
                float available = maxWidth == UNBOUNDED ? UNBOUNDED
                        : Math.max(1, maxWidth - indentBefore(firstLine) - style.endIndent());
                Line line = new Line();
                line.start = start;
                line.startOffset = indentBefore(firstLine);
                // A line separator ends a line wherever it stands, inside the paragraph: the line
                // after it takes no spacing, no first-line indent and no marker.
                int separator = text.indexOf(LINE_SEPARATOR, start);
                int stretch = separator < 0 ? length : separator;
                int afterStretch = separator < 0 ? length : separator + 1;
                int next;
                if (lines.size() == room - 1) {
                    // The last line the limit allows: the rest of its stretch, cut as one line is,
                    // and anything after a separator left out.
                    int end = trimEnd(start, stretch);
                    line.end = end;
                    next = length;
                    boolean wider = available != UNBOUNDED && width(start, end) > available;
                    boolean leftOut = afterStretch < length;
                    if (ellipsis && (moreAfter || wider || leftOut)) {
                        ellipsize(line, available, clusters, ruler);
                    } else if (leftOut) {
                        ellipsized = true;
                    }
                } else if (available == UNBOUNDED
                        || width(start, trimEnd(start, stretch)) <= available) {
                    next = afterStretch;
                    line.end = trimEnd(start, stretch);
                    line.forced = separator >= 0;
                } else {
                    next = lineEnd(start, available, breaks, clusters);
                    line.end = trimEnd(start, next);
                    if (line.end == start && next > start && next < length) {
                        start = next; // a stretch of white space alone: no line for it
                        continue;
                    }
                }
                if (line.ellipsis == null) {
                    line.width = width(line.start, line.end);
                }
                y = place(line, y, ruler);
                lines.add(line);
                start = next;
            } while (start < length && lines.size() < room);
            if (lines.isEmpty()) {
                Line empty = new Line();
                empty.startOffset = indentBefore(true);
                y = place(empty, y, ruler);
                lines.add(empty);
            }
            lines.get(lines.size() - 1).last = true;
            if (start < length) {
                ellipsized = true; // the limit left the rest of this paragraph out
            }
            return y;
        }

        /** The greedy break: the last opportunity whose line fits, else as many clusters as fit. */
        private int lineEnd(int start, float available, BreakIterator breaks, BreakIterator clusters) {
            int best = -1;
            for (int b = breaks.following(start); b != BreakIterator.DONE; b = breaks.next()) {
                if (width(start, trimEnd(start, b)) <= available) {
                    best = b;
                } else {
                    break;
                }
            }
            if (best > start) {
                return best;
            }
            // One word wider than the line: cut it between clusters, one at least.
            int fit = clusters.following(start);
            for (int c = fit; c != BreakIterator.DONE; c = clusters.next()) {
                if (width(start, c) > available) {
                    break;
                }
                fit = c;
            }
            return fit == BreakIterator.DONE ? text.length() : fit;
        }

        /** Cuts {@code line} so that it and an ellipsis fit {@code available}. */
        private void ellipsize(Line line, float available, BreakIterator clusters, TextRuler ruler) {
            int at = Math.max(line.start, Math.min(line.end, text.length()) - 1);
            Shaping where = shapingAt(at);
            Font ellipsisFont = where != null ? where.shaped.font() : font;
            ShapedText mark = ruler.shape(TextRuler.ELLIPSIS, ellipsisFont, direction);
            float room = available == UNBOUNDED ? UNBOUNDED : available - mark.metrics().width();
            int cut = line.start;
            for (int c = clusters.following(line.start); c != BreakIterator.DONE && c <= line.end;
                    c = clusters.next()) {
                if (room != UNBOUNDED && width(line.start, c) > room) {
                    break;
                }
                cut = c;
            }
            line.end = trimEnd(line.start, cut);
            line.ellipsis = mark;
            line.ellipsisStyle = styleAt(Math.max(line.start, line.end - 1));
            line.ellipsisRaise = where != null ? where.raise : 0;
            line.width = width(line.start, line.end) + mark.metrics().width();
            ellipsized = true;
        }

        /** Sets the line's metrics from the runs on it, and places it at {@code top}. */
        private float place(Line line, float top, TextRuler ruler) {
            float ascent = 0;
            float descent = 0;
            float gap = 0;
            boolean any = false;
            for (Shaping s : shapings) {
                if (s.end > line.start && s.start < Math.max(line.end, line.start + 1)) {
                    TextMetrics m = s.shaped.metrics();
                    ascent = Math.max(ascent, m.ascent() + s.raise);
                    descent = Math.max(descent, m.descent() - s.raise);
                    gap = Math.max(gap, m.lineHeight() - m.ascent() - m.descent());
                    any = true;
                }
            }
            if (!any) {
                TextMetrics m = ruler.shape("", font, direction).metrics();
                ascent = m.ascent();
                descent = m.descent();
                gap = m.lineHeight() - m.ascent() - m.descent();
            }
            float natural = ascent + descent + gap;
            float height = natural * style.lineSpacingFactor();
            line.ascent = ascent;
            line.descent = descent;
            line.top = top;
            line.baseline = top + (height - natural) / 2 + ascent;
            return top + height;
        }

        float naturalWidth() {
            float widest = 0;
            for (Line line : lines) {
                widest = Math.max(widest, line.startOffset + line.width + style.endIndent());
            }
            return widest;
        }

        private @Nullable Shaping shapingAt(int index) {
            for (Shaping s : shapings) {
                if (index >= s.start && index < s.end) {
                    return s;
                }
            }
            return shapings.isEmpty() ? null : shapings.get(shapings.size() - 1);
        }

        private TextStyle styleAt(int index) {
            for (StyledText.Run run : source.runs()) {
                if (index >= run.start() && index < run.end()) {
                    return run.style();
                }
            }
            return style.textStyle();
        }

        private int trimEnd(int start, int end) {
            int at = end;
            while (at > start && Character.isWhitespace(text.charAt(at - 1))) {
                at--;
            }
            return at;
        }

        // -------------------------------------------------------------- painting a line

        void paintLine(Canvas canvas, Line line, boolean firstLine, float x, float y, float boxWidth,
                       Function<Ink, Paint> resolve, Function<Ink, Paint> resolveBackground) {
            float available = boxWidth - line.startOffset - style.endIndent();
            ParagraphStyle.Alignment align = alignment;
            if (align == ParagraphStyle.Alignment.JUSTIFY && (line.last || line.forced || line.ellipsis != null)) {
                align = ParagraphStyle.Alignment.START;
            }
            // The alignment is the paragraph's own where it sets one, read in its direction; else
            // the base's, read in the base's, which is how a label aligns a paragraph of Latin text
            // in a right-to-left interface against the right edge, as its plain text is. The start
            // indent and the list marker stand at the edge the lines are aligned to, so a marker
            // never hangs at one edge of the box with its item's text at the other.
            boolean rtl = direction == ShapedText.Direction.RTL;
            boolean alignRtl = alignedByParagraph ? rtl : baseRtl;
            float free = available - line.width;
            float extra = 0;
            if (align == ParagraphStyle.Alignment.JUSTIFY && free > 0) {
                int spaces = 0;
                for (int i = line.start; i < line.end; i++) {
                    if (text.charAt(i) == ' ') {
                        spaces++;
                    }
                }
                extra = spaces == 0 ? 0 : free / spaces;
            }
            float fromLeft = extra > 0 ? 0 : switch (align) {
                case START, JUSTIFY -> alignRtl ? free : 0;
                case CENTER -> free / 2;
                case END -> alignRtl ? 0 : free;
            };
            float left = x + (alignRtl ? style.endIndent() : line.startOffset) + fromLeft;
            float baseline = y + line.baseline;

            if (firstLine && (marker != null || drawnBullet != 0)) {
                float gap = MARKER_GAP_EMS * font.size();
                float edge = style.startIndent() + listIndent;
                float markerWidth = marker != null ? marker.metrics().width()
                        : DRAWN_BULLET_ADVANCE_EMS * font.size();
                float markerX = alignRtl ? x + boxWidth - edge + gap : x + edge - gap - markerWidth;
                if (marker != null) {
                    canvas.drawText(marker, markerX, baseline, resolve.apply(ink));
                } else {
                    paintBullet(canvas, drawnBullet, markerX + markerWidth / 2,
                            baseline - DRAWN_BULLET_CENTRE_EMS * font.size(), font.size(), resolve.apply(ink));
                }
            }
            List<Chunk> chunks = visualChunks(line, extra);
            float[] xs = new float[chunks.size()];
            float cursor = left;
            if (line.ellipsis != null && rtl) {
                cursor += line.ellipsis.metrics().width(); // the ellipsis ends a right-to-left line at its left
            }
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                cursor += chunk.extraBefore();
                xs[i] = cursor - chunk.shaping().left(chunk.start(), chunk.end());
                cursor += chunk.shaping().width(chunk.start(), chunk.end()) + chunk.extraAfter();
            }
            // Backgrounds under every glyph of the line, then the glyphs and their lines, those in
            // reading order: a page written from this keeps a right-to-left line's words in the
            // order they are read, whatever order they stand in.
            for (int i = 0; i < chunks.size(); i++) {
                paintChunk(canvas, chunks.get(i), xs[i], baseline, resolve, resolveBackground, true);
            }
            Integer[] reading = new Integer[chunks.size()];
            for (int i = 0; i < reading.length; i++) {
                reading[i] = i;
            }
            Arrays.sort(reading, java.util.Comparator.comparingInt(i -> chunks.get(i).start()));
            for (int i : reading) {
                paintChunk(canvas, chunks.get(i), xs[i], baseline, resolve, resolveBackground, false);
            }
            if (line.ellipsis != null) {
                float ex = rtl ? left : left + line.width - line.ellipsis.metrics().width();
                TextStyle s = line.ellipsisStyle != null ? line.ellipsisStyle : style.textStyle();
                Ink ellipsisInk = s.inkOrNull() != null ? s.inkOrNull() : ink;
                canvas.drawText(line.ellipsis, ex, baseline - line.ellipsisRaise, resolve.apply(ellipsisInk));
            }
        }

        /**
         * The line's stretches in the order they stand on the screen: one per shaping on it,
         * reordered by their bidi levels, each cut after every space when the line is justified
         * and after every cluster when its text is tracked.
         */
        private List<Chunk> visualChunks(Line line, float justifyExtra) {
            List<Chunk> logical = new ArrayList<>();
            List<Byte> levels = new ArrayList<>();
            for (Shaping s : shapings) {
                int a = Math.max(line.start, s.start);
                int b = Math.min(line.end, s.end);
                if (a < b) {
                    logical.add(new Chunk(s, a, b, 0, 0));
                    levels.add(s.level);
                }
            }
            int n = logical.size();
            Object[] order = logical.toArray();
            byte[] levelArray = new byte[n];
            for (int i = 0; i < n; i++) {
                levelArray[i] = levels.get(i);
            }
            if (n > 1) {
                Bidi.reorderVisually(levelArray, 0, order, 0, n);
            }
            List<Chunk> out = new ArrayList<>();
            for (Object o : order) {
                Chunk chunk = (Chunk) o;
                Shaping s = chunk.shaping();
                if (justifyExtra == 0 && s.tracking == 0) {
                    out.add(chunk);
                    continue;
                }
                // Cut into the pieces the extra room goes after, then lay them in the shaping's
                // own reading order on the screen.
                List<Chunk> pieces = new ArrayList<>();
                int from = chunk.start();
                for (int i = chunk.start(); i < chunk.end(); ) {
                    int next = s.tracking != 0 ? nextCluster(s, i) : i + 1;
                    boolean space = text.charAt(next - 1) == ' ';
                    if (s.tracking != 0 || space || next == chunk.end()) {
                        // The tracking is in the piece's own width already; only the
                        // justification's room is added, beside the piece's closing space: to its
                        // right in left-to-right text, to its left in right-to-left.
                        float room = space ? justifyExtra : 0;
                        boolean rightToLeft = (s.level & 1) != 0;
                        pieces.add(new Chunk(s, from, next, rightToLeft ? room : 0, rightToLeft ? 0 : room));
                        from = next;
                    }
                    i = next;
                }
                if ((s.level & 1) != 0) {
                    java.util.Collections.reverse(pieces);
                }
                out.addAll(pieces);
            }
            return out;
        }

        private static int nextCluster(Shaping s, int index) {
            int local = index - s.start;
            int ordinal = s.shaped.caretOrdinal(local);
            int next = s.shaped.caretIndex(Math.min(s.shaped.caretCount() - 1, ordinal + 1));
            return next <= local ? index + 1 : s.start + next;
        }

        /** Paints one chunk's backgrounds, or its glyphs and their lines, range by range of style. */
        private void paintChunk(Canvas canvas, Chunk chunk, float x, float baseline,
                                Function<Ink, Paint> resolve, Function<Ink, Paint> resolveBackground,
                                boolean backgrounds) {
            Shaping s = chunk.shaping();
            float y = baseline - s.raise;
            for (StyledText.Run run : source.runs()) {
                int a = Math.max(chunk.start(), run.start());
                int b = Math.min(chunk.end(), run.end());
                if (a >= b) {
                    continue;
                }
                TextStyle look = run.style();
                Ink textInk = look.inkOrNull() != null ? look.inkOrNull() : ink;
                if (backgrounds) {
                    Ink behind = look.backgroundOrNull();
                    if (behind != null) {
                        TextMetrics m = s.shaped.metrics();
                        int count = s.shaped.selection(a - s.start, b - s.start, s.boxes);
                        for (int k = 0; k < count; k++) {
                            canvas.fillRect(x + s.boxes[2 * k], y - m.ascent(),
                                    s.boxes[2 * k + 1] - s.boxes[2 * k], m.ascent() + m.descent(),
                                    resolveBackground.apply(behind));
                        }
                    }
                    continue;
                }
                Paint paint = resolve.apply(textInk);
                canvas.drawText(s.shaped, a - s.start, b - s.start, x, y, paint);
                TextStyle.Underline underline = look.underlineKind();
                boolean struck = look.isStruckThrough();
                if (underline == TextStyle.Underline.NONE && !struck) {
                    continue;
                }
                int count = s.shaped.selection(a - s.start, b - s.start, s.boxes);
                TextDecorations d = s.decorations;
                for (int k = 0; k < count; k++) {
                    float x0 = x + s.boxes[2 * k];
                    float x1 = x + s.boxes[2 * k + 1];
                    if (underline != TextStyle.Underline.NONE) {
                        Ink lineInk = look.underlineInkOrNull() != null ? look.underlineInkOrNull() : textInk;
                        drawUnderline(canvas, underline, x0, x1, y + d.underlineOffset(),
                                d.underlineThickness(), resolve.apply(lineInk));
                    }
                    if (struck) {
                        Ink lineInk = look.strikethroughInkOrNull() != null ? look.strikethroughInkOrNull() : textInk;
                        canvas.fillRect(x0, y - d.strikeoutOffset(), x1 - x0, d.strikeoutThickness(),
                                resolve.apply(lineInk));
                    }
                }
            }
        }

        private static void drawUnderline(Canvas canvas, TextStyle.Underline kind, float x0, float x1,
                                          float top, float thickness, Paint paint) {
            float t = Math.max(thickness, 0.75f);
            switch (kind) {
                case SINGLE -> canvas.fillRect(x0, top, x1 - x0, t, paint);
                case DOUBLE -> {
                    canvas.fillRect(x0, top, x1 - x0, t, paint);
                    canvas.fillRect(x0, top + 2 * t, x1 - x0, t, paint);
                }
                case DOTTED -> {
                    for (float at = x0; at < x1; at += 2 * t) {
                        canvas.fillRect(at, top, Math.min(t, x1 - at), t, paint);
                    }
                }
                case WAVY -> {
                    float amplitude = t * 1.25f;
                    float step = t * 2f;
                    float centre = top + amplitude;
                    float px = x0;
                    float py = centre;
                    boolean up = true;
                    for (float at = x0 + step; px < x1; at += step) {
                        float nx = Math.min(at, x1);
                        float ny = centre + (up ? -amplitude : amplitude) * ((nx - px) / step);
                        canvas.drawLine(px, py, nx, ny, t, paint);
                        px = nx;
                        py = ny;
                        up = !up;
                    }
                }
                default -> { }
            }
        }
    }

    /** The font a run of a style is shaped in, where it sits, and its decorations. */
    private record FontChoice(Font font, float raise, float trackingEms, TextDecorations decorations) {

        static FontChoice of(TextStyle style, Font base, TextRuler ruler) {
            Font font = style.fontOver(base);
            float raise = 0;
            switch (style.baselineKind()) {
                case SUPERSCRIPT -> {
                    TextDecorations face = ruler.decorations(font);
                    raise = face.superscriptOffset();
                    font = font.withSize(Math.max(1, face.superscriptSize()));
                }
                case SUBSCRIPT -> {
                    TextDecorations face = ruler.decorations(font);
                    raise = -face.subscriptOffset();
                    font = font.withSize(Math.max(1, face.subscriptSize()));
                }
                default -> { }
            }
            return new FontChoice(font, raise, style.trackingEms(), ruler.decorations(font));
        }

        /** The tracking in points over {@code [from, to)}, or 0 where the text's letters join. */
        float tracking(String text, int from, int to) {
            if (trackingEms == 0) {
                return 0;
            }
            for (int i = from; i < to; ) {
                int cp = text.codePointAt(i);
                if (joins(Character.UnicodeScript.of(cp))) {
                    return 0;
                }
                i += Character.charCount(cp);
            }
            return trackingEms * font.size();
        }

        private static boolean joins(Character.UnicodeScript script) {
            return switch (script) {
                case ARABIC, SYRIAC, NKO, MONGOLIAN, MANDAIC, ADLAM, HANIFI_ROHINGYA, PHAGS_PA,
                     MANICHAEAN, PSALTER_PAHLAVI, SOGDIAN, CHORASMIAN -> true;
                default -> false;
            };
        }
    }
}

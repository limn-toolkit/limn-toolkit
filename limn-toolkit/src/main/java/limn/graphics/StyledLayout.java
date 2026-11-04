package limn.graphics;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import limn.i18n.I18n;
import org.jspecify.annotations.Nullable;

/**
 * {@link StyledText} laid out at a width: its paragraphs shaped, broken into lines and placed, ready
 * to paint on any {@link Canvas}. What a label holds for its styled text, and what an application
 * that draws for itself (a chart's annotations, a diagram's notes, a report's page) measures and
 * draws styled text with: built once, asked its size, painted as often as it shows.
 *
 * <pre>{@code
 * StyledLayout note = StyledLayout.builder(text, Font.of(13), Color.BLACK)
 *         .maxWidth(240)
 *         .build(textRuler());                    // a widget's ruler; build() takes the installed one
 * canvas.drawText(note, x, y);                    // the box's top-left corner
 * float below = y + note.height();
 * }</pre>
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
 * <p>Immutable once made. Lay out again when the text, the font, the ink, the width or the
 * language changes, or when {@link #isCurrent} says the ruler would shape differently.
 */
public final class StyledLayout {

    /**
     * What the text is laid out over: the font and ink a piece that sets neither is drawn in, the
     * alignment of a paragraph that sets none, and the direction of the interface around it, which
     * a paragraph with no strong character of its own reads in and the base alignment's start and
     * end are read in. A paragraph's own alignment is read in the paragraph's own direction.
     */
    record Base(Font font, Ink ink, ParagraphStyle.Alignment alignment, ShapedText.Direction whenNeutral) {
        Base {
            Objects.requireNonNull(font, "font");
            Objects.requireNonNull(ink, "ink");
            Objects.requireNonNull(alignment, "alignment");
            Objects.requireNonNull(whenNeutral, "whenNeutral");
        }
    }

    /** No limit on the width: each paragraph is one line. */
    public static final float UNBOUNDED = Float.POSITIVE_INFINITY;

    /**
     * Starts laying {@code text} out. Everything but the text, the font and the ink has a default:
     * no width limit, no line limit, an ellipsis on a cut line, paragraphs aligned to their start
     * in a left-to-right interface.
     *
     * @param text the text, read in the language in effect when it is built
     * @param font the font a piece that sets none of its own is drawn in
     * @param ink  the ink a piece that sets none of its own is drawn in
     * @return the builder
     */
    public static Builder builder(StyledText text, Font font, Ink ink) {
        return new Builder(Objects.requireNonNull(text, "text"), Objects.requireNonNull(font, "font"),
                Objects.requireNonNull(ink, "ink"));
    }

    /** How a {@link StyledLayout} is made: the text, its base and its limits. Not thread-safe. */
    public static final class Builder {
        private final StyledText text;
        private final Font font;
        private final Ink ink;
        private ParagraphStyle.Alignment alignment = ParagraphStyle.Alignment.START;
        private ShapedText.Direction direction = ShapedText.Direction.LTR;
        private float maxWidth = UNBOUNDED;
        private int maxLines = Integer.MAX_VALUE;
        private boolean ellipsis = true;

        private Builder(StyledText text, Font font, Ink ink) {
            this.text = text;
            this.font = font;
            this.ink = ink;
        }

        /**
         * @param newAlignment the alignment of a paragraph that sets none of its own; {@code START}
         *                     by default
         * @return this builder
         */
        public Builder alignment(ParagraphStyle.Alignment newAlignment) {
            alignment = Objects.requireNonNull(newAlignment, "newAlignment");
            return this;
        }

        /**
         * The direction of the interface around the text: what a paragraph with no strong character
         * of its own reads in, and what the base alignment's start and end mean. Left to right by
         * default; a right-to-left interface passes {@code RTL}. A paragraph with a strong
         * character reads in its own direction whatever this says.
         *
         * @param newDirection the interface's direction
         * @return this builder
         */
        public Builder direction(ShapedText.Direction newDirection) {
            direction = Objects.requireNonNull(newDirection, "newDirection");
            return this;
        }

        /**
         * @param points the width lines break at, in logical points; {@link #UNBOUNDED}, the
         *               default, breaks none, and 0 breaks after every cluster
         * @return this builder
         * @throws IllegalArgumentException when {@code points} is negative or not a number
         */
        public Builder maxWidth(float points) {
            if (Float.isNaN(points) || points < 0) {
                throw new IllegalArgumentException("maxWidth must be 0 or more, or UNBOUNDED: " + points);
            }
            maxWidth = points;
            return this;
        }

        /**
         * @param lines the most lines, 1 or more; {@code Integer.MAX_VALUE}, the default, for no limit
         * @return this builder
         * @throws IllegalArgumentException when {@code lines} is below 1
         */
        public Builder maxLines(int lines) {
            if (lines < 1) {
                throw new IllegalArgumentException("maxLines must be 1 or more: " + lines);
            }
            maxLines = lines;
            return this;
        }

        /**
         * @param cut whether a cut last line, or a line wider than the width, ends with an ellipsis
         *            (the default); otherwise it is left whole for a clip to cut
         * @return this builder
         */
        public Builder ellipsis(boolean cut) {
            ellipsis = cut;
            return this;
        }

        /**
         * Lays the text out with {@code ruler}: a widget passes its own {@code textRuler()}, so the
         * text is measured as the rest of its scene is.
         *
         * @param ruler the ruler that shapes it
         * @return the layout
         */
        public StyledLayout build(TextRuler ruler) {
            return of(text, new Base(font, ink, alignment, direction), maxWidth, maxLines, ellipsis, ruler);
        }

        /**
         * Lays the text out with the installed ruler, {@link TextRulers#get()}: the one an application
         * drawing outside any scene measures with.
         *
         * @return the layout
         */
        public StyledLayout build() {
            return build(TextRulers.get());
        }
    }

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

    /**
     * How much wider than the room a line may measure and still fit. A label measures a paragraph
     * at its natural width, indent plus text, and lays it out at that width, where the room is the
     * width less the indent: in floating point {@code (indent + text) - indent} can come out one
     * unit in the last place short of the text, and the line that fitted at the measure was cut.
     */
    private static final float FIT_SLACK = 1e-3f;

    /** The most dots or waves one line of an underline draws: a bound on a line of any width. */
    private static final int MAX_UNDERLINE_STEPS = 100_000;

    /** The tallest line: far past any text, short of the infinity a product of two scales can reach. */
    private static final float MAX_LINE_HEIGHT = 1e6f;

    private final Base base;
    private final Shaped shaped;
    private final List<ParagraphLayout> paragraphs;
    private final float width;
    private final float boxWidth;
    private final float height;
    private final int lineCount;
    private final boolean truncated;
    private final long epoch;

    private StyledLayout(Shaped shaped, List<ParagraphLayout> paragraphs, float width, float boxWidth,
                         float height, int lineCount, boolean truncated, long epoch) {
        this.base = shaped.base;
        this.shaped = shaped;
        this.paragraphs = paragraphs;
        this.width = width;
        this.boxWidth = boxWidth;
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
     * @param maxWidth the width lines break at, in logical points; {@link #UNBOUNDED} breaks none,
     *                 and 0 breaks after every cluster
     * @param maxLines the most lines, 1 or more; {@code Integer.MAX_VALUE} for no limit
     * @param ellipsis whether a cut last line, or a line wider than {@code maxWidth}, ends with an
     *                 ellipsis; otherwise it is left whole for a clip to cut
     * @param ruler    the ruler that shapes it
     * @return the layout
     * @throws IllegalArgumentException when {@code maxLines} is below 1, or {@code maxWidth} is
     *                                  negative or not a number
     */
    static StyledLayout of(StyledText text, Base base, float maxWidth, int maxLines,
                                  boolean ellipsis, TextRuler ruler) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(ruler, "ruler");
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be 1 or more: " + maxLines);
        }
        if (Float.isNaN(maxWidth) || maxWidth < 0) {
            throw new IllegalArgumentException("maxWidth must be 0 or more, or UNBOUNDED: " + maxWidth);
        }
        return laidOut(new Shaped(text.paragraphs(), base, ruler), maxWidth, maxLines, ellipsis, ruler);
    }

    /**
     * Breaks the lines of {@code shaped}'s paragraphs at {@code maxWidth}: the shaping done once
     * for the text, the breaking for each width.
     */
    private static StyledLayout laidOut(Shaped shaped, float maxWidth, int maxLines, boolean ellipsis,
                                        TextRuler ruler) {
        float limit = maxWidth;
        int count = shaped.source.size();
        List<ParagraphLayout> laid = new ArrayList<>(count);
        float y = 0;
        float widest = 0;
        int lines = 0;
        boolean cut = false;
        for (int i = 0; i < count && lines < maxLines; i++) {
            ParagraphLayout paragraph = new ParagraphLayout(shaped.paragraph(i, ruler));
            if (i > 0) {
                y += paragraph.style.spaceBefore();
            }
            boolean moreAfter = i < count - 1;
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
        return new StyledLayout(shaped, List.copyOf(laid), widest, maxWidth == UNBOUNDED ? widest : maxWidth, y,
                lines, cut, shaped.epoch);
    }

    /**
     * The same text laid out again at another width or line limit, with the shaping this layout
     * already did: only the lines are broken again. Shaping, which does not depend on the width,
     * is most of a layout's cost, so a widget whose width changes, a label in a window being
     * resized, lays out with this. A ruler that has moved since ({@link #isCurrent}) shapes anew.
     *
     * @param maxWidth the width lines break at, in logical points; {@link #UNBOUNDED} breaks none
     * @param maxLines the most lines, 1 or more; {@code Integer.MAX_VALUE} for no limit
     * @param ellipsis whether a cut last line ends with an ellipsis
     * @param ruler    the ruler that shapes it
     * @return the layout
     * @throws IllegalArgumentException when {@code maxLines} is below 1, or {@code maxWidth} is
     *                                  negative or not a number
     */
    public StyledLayout relaid(float maxWidth, int maxLines, boolean ellipsis, TextRuler ruler) {
        Objects.requireNonNull(ruler, "ruler");
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be 1 or more: " + maxLines);
        }
        if (Float.isNaN(maxWidth) || maxWidth < 0) {
            throw new IllegalArgumentException("maxWidth must be 0 or more, or UNBOUNDED: " + maxWidth);
        }
        Shaped from = isCurrent(ruler) ? shaped : new Shaped(shaped.source, base, ruler);
        return laidOut(from, maxWidth, maxLines, ellipsis, ruler);
    }

    /**
     * A text's paragraphs shaped over one base, each the first time a layout reaches it: what every
     * width it is laid out at shares. Held by every layout made from it.
     */
    private static final class Shaped {
        final List<StyledText.Paragraph> source;
        final Base base;
        final long epoch;
        private final float[] markerRoom;
        private final Map<TextStyle, FontChoice> choices = new HashMap<>();
        private final ParagraphLayout[] paragraphs;

        Shaped(List<StyledText.Paragraph> source, Base base, TextRuler ruler) {
            this.source = source;
            this.base = base;
            this.epoch = ruler.epoch();
            this.markerRoom = markerRoom(source, base, ruler);
            this.paragraphs = new ParagraphLayout[source.size()];
        }

        /** Paragraph {@code i} shaped, its lines not broken; shaped now if no layout reached it yet. */
        synchronized ParagraphLayout paragraph(int i, TextRuler ruler) {
            ParagraphLayout p = paragraphs[i];
            if (p == null) {
                p = new ParagraphLayout(source.get(i), base, ruler, markerRoom[i], choices);
                paragraphs[i] = p;
            }
            return p;
        }
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
            if (p == null || p.style().list() == null) {
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

    /**
     * @return the width of the box {@link Canvas#drawText(StyledLayout, float, float)} aligns the
     *         lines in: the width limit the layout was made at, or the widest line when it had
     *         none. What the {@code paint} overloads that take a box width are given to draw as
     *         {@code drawText} draws.
     */
    public float boxWidth() {
        return boxWidth;
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
     * Paints the text with every ink asked for its paint now: a {@code Tone} in the application's
     * palette, {@code Theme.current()}. A widget with a palette of its own answers its inks with
     * the overload that takes a resolver, as a label does.
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
        // Only the lines the clip shows: a long block in a scroll view paints its window of lines
        // and not all of them. The clip is a promise of what will not show, so a line is skipped
        // only when it lies wholly outside it.
        Rect clip = canvas.clipBounds();
        float clipTop = clip == null ? Float.NEGATIVE_INFINITY : clip.y() - y;
        float clipBottom = clip == null ? Float.POSITIVE_INFINITY : clip.y() + clip.height() - y;
        for (ParagraphLayout paragraph : paragraphs) {
            for (int i = 0; i < paragraph.lines.size(); i++) {
                Line line = paragraph.lines.get(i);
                float lineBottom = i + 1 < paragraph.lines.size() ? paragraph.lines.get(i + 1).top : line.bottom;
                if (lineBottom < clipTop || line.top > clipBottom) {
                    continue;
                }
                paragraph.paintLine(canvas, line, i == 0, x, y, boxWidth, resolve, resolveBackground);
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
        float bottom;               // the line's box with its line spacing, not the paragraph's space after
        float baseline;
        float startOffset;          // the indent before the line, at its start edge
        boolean last;               // the paragraph's last line
        boolean forced;             // ends at a line separator (U+2028): never justified
        @Nullable ShapedText ellipsis;
        @Nullable TextStyle ellipsisStyle;
        float ellipsisRaise;
        // The line as painted at the room it was last painted in: computed once per room, since
        // the order and the justification only change with it.
        float paintedRoom = Float.NaN;
        @Nullable List<Chunk> chunks;
        float[] offsets = new float[0];     // each chunk's glyph origin, from the line's left
        float[] boxStarts = new float[0];   // each chunk's box with the room beside it, from the left
        float[] boxEnds = new float[0];
        int[] reading = new int[0];         // the chunks in reading order
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

        /** {@code shaped}'s paragraph again, its shaping shared and its lines yet to be broken. */
        ParagraphLayout(ParagraphLayout shaped) {
            this.source = shaped.source;
            this.style = shaped.style;
            this.text = shaped.text;
            this.direction = shaped.direction;
            this.font = shaped.font;
            this.ink = shaped.ink;
            this.alignment = shaped.alignment;
            this.alignedByParagraph = shaped.alignedByParagraph;
            this.baseRtl = shaped.baseRtl;
            this.marker = shaped.marker;
            this.drawnBullet = shaped.drawnBullet;
            this.listIndent = shaped.listIndent;
            this.shapings.addAll(shaped.shapings);
        }

        ParagraphLayout(StyledText.Paragraph source, Base base, TextRuler ruler, float markerRoom,
                        Map<TextStyle, FontChoice> choices) {
            this.source = source;
            this.style = source.style();
            this.text = source.text();
            this.direction = style.direction() != null ? style.direction()
                    : ShapedText.Direction.of(text, base.whenNeutral());
            TextStyle own = style.textStyle();
            this.font = own.fontOver(base.font());
            this.ink = own.ink() != null ? own.ink() : base.ink();
            this.alignment = style.alignment() != null ? style.alignment() : base.alignment();
            this.alignedByParagraph = style.alignment() != null;
            this.baseRtl = base.whenNeutral() == ShapedText.Direction.RTL;
            String markerText = source.marker();
            this.drawnBullet = isDrawnBullet(markerText) ? markerText.charAt(0) : 0;
            this.marker = markerText == null || drawnBullet != 0 ? null : ruler.shape(markerText, font, direction);
            this.listIndent = style.list() == null ? 0
                    : (style.level() + 1) * ParagraphStyle.LIST_INDENT_EMS * font.size() + markerRoom;
            shape(base, ruler, choices);
        }

        /**
         * Splits the paragraph where the font or the bidi level changes, and shapes each stretch
         * once. The stretches are gathered first and shaped after, so a line of code whose tokens
         * share a font and differ in colour shapes its text once, not once per token. A line
         * separator belongs to no stretch: it is never drawn, no face has a glyph for it, and a
         * face searched for one loads every fallback the application carries.
         */
        private void shape(Base base, TextRuler ruler, Map<TextStyle, FontChoice> choices) {
            if (text.isEmpty()) {
                return;
            }
            Bidi bidi = new Bidi(text, direction == ShapedText.Direction.RTL
                    ? Bidi.DIRECTION_RIGHT_TO_LEFT : Bidi.DIRECTION_LEFT_TO_RIGHT);
            List<int[]> spans = new ArrayList<>();          // start, end, level
            List<FontChoice> spanChoices = new ArrayList<>();
            List<Float> spanTracking = new ArrayList<>();
            for (StyledText.Run run : source.runs()) {
                FontChoice choice = choices.computeIfAbsent(run.style(),
                        style -> FontChoice.of(style, base.font(), ruler));
                int at = run.start();
                while (at < run.end()) {
                    if (text.charAt(at) == LINE_SEPARATOR) {
                        at++;
                        continue;
                    }
                    byte level = (byte) bidi.getLevelAt(at);
                    int end = at + 1;
                    while (end < run.end() && bidi.getLevelAt(end) == level
                            && text.charAt(end) != LINE_SEPARATOR) {
                        end++;
                    }
                    float tracking = choice.tracking(text, at, end);
                    // Merge with the stretch before when the font, level, raise and tracking are
                    // the same: a colour change between two runs of one style splits nothing.
                    int last = spans.size() - 1;
                    if (last >= 0 && spans.get(last)[1] == at && spans.get(last)[2] == level
                            && spanChoices.get(last).font.equals(choice.font)
                            && spanChoices.get(last).raise == choice.raise
                            && spanTracking.get(last) == tracking) {
                        spans.get(last)[1] = end;
                    } else {
                        spans.add(new int[] {at, end, level});
                        spanChoices.add(choice);
                        spanTracking.add(tracking);
                    }
                    at = end;
                }
            }
            for (int i = 0; i < spans.size(); i++) {
                int[] span = spans.get(i);
                FontChoice choice = spanChoices.get(i);
                ShapedText.Direction runDirection = (span[2] & 1) != 0
                        ? ShapedText.Direction.RTL : ShapedText.Direction.LTR;
                ShapedText shaped = ruler.shapeRun(text, span[0], span[1], choice.font, runDirection);
                shapings.add(new Shaping(span[0], span[1], (byte) span[2], shaped, choice.raise,
                        spanTracking.get(i), choice.decorations));
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
            // Made only when a line has to be broken: a block laid out at no width limit, as a
            // code block is, never asks for one.
            BreakIterator breaks = null;
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
                    boolean wider = available != UNBOUNDED && width(start, end) > available + FIT_SLACK;
                    boolean leftOut = afterStretch < length;
                    if (ellipsis && (moreAfter || wider || leftOut)) {
                        ellipsize(line, available, ruler);
                    } else if (leftOut) {
                        ellipsized = true;
                    }
                } else if (available == UNBOUNDED
                        || width(start, trimEnd(start, stretch)) <= available + FIT_SLACK) {
                    next = afterStretch;
                    line.end = trimEnd(start, stretch);
                    line.forced = separator >= 0;
                } else {
                    if (breaks == null) {
                        breaks = limn.i18n.internal.LocaleServices.lineBreaks(I18n.locale());
                        breaks.setText(text);
                    }
                    next = lineEnd(start, available, breaks);
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
        private int lineEnd(int start, float available, BreakIterator breaks) {
            int best = -1;
            for (int b = breaks.following(start); b != BreakIterator.DONE; b = breaks.next()) {
                if (width(start, trimEnd(start, b)) <= available + FIT_SLACK) {
                    best = b;
                } else {
                    break;
                }
            }
            if (best > start) {
                return best;
            }
            // One word wider than the line: cut it between clusters, one at least.
            int fit = nextClusterStop(start);
            for (int c = fit; c < text.length(); ) {
                c = nextClusterStop(c);
                if (width(start, c) > available + FIT_SLACK) {
                    break;
                }
                fit = c;
            }
            return Math.min(fit, text.length());
        }

        /**
         * The next place after {@code index} the text can be cut: the end of the cluster it is in,
         * as the shaper made the clusters. A grapheme boundary of the platform's is not one: a
         * conjunct the shaper draws as one glyph is two graphemes to it, and a cut between them
         * drew the whole glyph past the line's end.
         */
        private int nextClusterStop(int index) {
            for (Shaping s : shapings) {
                if (index >= s.start && index < s.end) {
                    return nextCluster(s, index);
                }
            }
            return index + 1;
        }

        /** Cuts {@code line} so that it and an ellipsis fit {@code available}. */
        private void ellipsize(Line line, float available, TextRuler ruler) {
            int at = Math.max(line.start, Math.min(line.end, text.length()) - 1);
            Shaping where = shapingAt(at);
            Font ellipsisFont = where != null ? where.shaped.font() : font;
            ShapedText mark = ruler.shape(TextRuler.ELLIPSIS, ellipsisFont, direction);
            float room = available == UNBOUNDED ? UNBOUNDED : available - mark.metrics().width();
            int cut = line.start;
            for (int c = line.start; c < line.end; ) {
                c = Math.min(nextClusterStop(c), line.end);
                if (room != UNBOUNDED && width(line.start, c) > room + FIT_SLACK) {
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
            // Bounded, so that a spacing and a size each allowed alone never make a line, and
            // every line after it, infinitely tall.
            float height = Math.min(natural * style.lineSpacing(), MAX_LINE_HEIGHT);
            line.ascent = ascent;
            line.descent = descent;
            line.top = top;
            line.bottom = top + height;
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
            boolean justified = align == ParagraphStyle.Alignment.JUSTIFY && free > 0;
            float fromLeft = justified ? 0 : switch (align) {
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
            if (!(line.paintedRoom == available) || line.chunks == null) {
                arrange(line, available, justified ? free : 0, rtl);
            }
            List<Chunk> chunks = line.chunks;
            // Backgrounds under every glyph of the line, then the glyphs and their lines, those in
            // reading order: a page written from this keeps a right-to-left line's words in the
            // order they are read, whatever order they stand in.
            for (int i = 0; i < chunks.size(); i++) {
                paintChunk(canvas, chunks.get(i), left + line.offsets[i], left + line.boxStarts[i],
                        left + line.boxEnds[i], baseline, resolve, resolveBackground, true);
            }
            for (int i : line.reading) {
                paintChunk(canvas, chunks.get(i), left + line.offsets[i], left + line.boxStarts[i],
                        left + line.boxEnds[i], baseline, resolve, resolveBackground, false);
            }
            if (line.ellipsis != null) {
                float ex = rtl ? left : left + line.width - line.ellipsis.metrics().width();
                TextStyle s = line.ellipsisStyle != null ? line.ellipsisStyle : style.textStyle();
                Ink ellipsisInk = s.ink() != null ? s.ink() : ink;
                canvas.drawText(line.ellipsis, ex, baseline - line.ellipsisRaise, resolve.apply(ellipsisInk));
            }
        }

        /**
         * Works out how the line stands in {@code room}: its chunks in screen order, where each
         * one's glyphs start and its box with the justification's room beside it, from the line's
         * left, and the order they are read in. Kept on the line until it is painted in another
         * room, since nothing else changes it.
         */
        private void arrange(Line line, float room, float free, boolean rtl) {
            float extra = 0;
            if (free > 0) {
                int spaces = 0;
                for (int i = line.start; i < line.end; i++) {
                    if (text.charAt(i) == ' ') {
                        spaces++;
                    }
                }
                extra = spaces == 0 ? 0 : free / spaces;
            }
            List<Chunk> chunks = visualChunks(line, extra);
            int n = chunks.size();
            float[] offsets = new float[n];
            float[] boxStarts = new float[n];
            float[] boxEnds = new float[n];
            float cursor = 0;
            if (line.ellipsis != null && rtl) {
                cursor += line.ellipsis.metrics().width(); // the ellipsis ends a right-to-left line at its left
            }
            for (int i = 0; i < n; i++) {
                Chunk chunk = chunks.get(i);
                boxStarts[i] = cursor;
                cursor += chunk.extraBefore();
                offsets[i] = cursor - chunk.shaping().left(chunk.start(), chunk.end());
                cursor += chunk.shaping().width(chunk.start(), chunk.end()) + chunk.extraAfter();
                boxEnds[i] = cursor;
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, java.util.Comparator.comparingInt(i -> chunks.get(i).start()));
            int[] reading = new int[n];
            for (int i = 0; i < n; i++) {
                reading[i] = order[i];
            }
            line.chunks = chunks;
            line.offsets = offsets;
            line.boxStarts = boxStarts;
            line.boxEnds = boxEnds;
            line.reading = reading;
            line.paintedRoom = room;
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

        /** The first of the paragraph's runs that ends after {@code index}: runs are in order. */
        private int firstRunAfter(int index) {
            List<StyledText.Run> runs = source.runs();
            int low = 0;
            int high = runs.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (runs.get(mid).end() <= index) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        /**
         * Paints one chunk's backgrounds, or its glyphs and their lines, range by range of style.
         * A style that covers the whole chunk draws its background and its lines across the
         * chunk's box, the room a justified space or a tracked letter took included, so they run
         * on unbroken; one that covers part of it draws them under its glyphs.
         */
        private void paintChunk(Canvas canvas, Chunk chunk, float x, float boxStart, float boxEnd,
                                float baseline, Function<Ink, Paint> resolve,
                                Function<Ink, Paint> resolveBackground, boolean backgrounds) {
            Shaping s = chunk.shaping();
            float y = baseline - s.raise;
            List<StyledText.Run> runs = source.runs();
            for (int r = firstRunAfter(chunk.start()); r < runs.size(); r++) {
                StyledText.Run run = runs.get(r);
                if (run.start() >= chunk.end()) {
                    break;
                }
                int a = Math.max(chunk.start(), run.start());
                int b = Math.min(chunk.end(), run.end());
                if (a >= b) {
                    continue;
                }
                boolean whole = a == chunk.start() && b == chunk.end();
                TextStyle look = run.style();
                Ink textInk = look.ink() != null ? look.ink() : ink;
                if (backgrounds) {
                    Ink behind = look.background();
                    if (behind != null) {
                        TextMetrics m = s.shaped.metrics();
                        Paint paint = resolveBackground.apply(behind);
                        if (whole) {
                            canvas.fillRect(boxStart, y - m.ascent(), boxEnd - boxStart,
                                    m.ascent() + m.descent(), paint);
                        } else {
                            int count = s.shaped.selection(a - s.start, b - s.start, s.boxes);
                            for (int k = 0; k < count; k++) {
                                canvas.fillRect(x + s.boxes[2 * k], y - m.ascent(),
                                        s.boxes[2 * k + 1] - s.boxes[2 * k], m.ascent() + m.descent(), paint);
                            }
                        }
                    }
                    continue;
                }
                Paint paint = resolve.apply(textInk);
                canvas.drawText(s.shaped, a - s.start, b - s.start, x, y, paint);
                TextStyle.Underline underline =
                        Objects.requireNonNullElse(look.underlineKind(), TextStyle.Underline.NONE);
                boolean struck = Boolean.TRUE.equals(look.isStruckThrough());
                if (underline == TextStyle.Underline.NONE && !struck) {
                    continue;
                }
                int count;
                if (whole) {
                    s.boxes[0] = boxStart - x;
                    s.boxes[1] = boxEnd - x;
                    count = 1;
                } else {
                    count = s.shaped.selection(a - s.start, b - s.start, s.boxes);
                }
                TextDecorations d = s.decorations;
                for (int k = 0; k < count; k++) {
                    float x0 = x + s.boxes[2 * k];
                    float x1 = x + s.boxes[2 * k + 1];
                    if (underline != TextStyle.Underline.NONE) {
                        Ink lineInk = look.underlineInk() != null ? look.underlineInk() : textInk;
                        drawUnderline(canvas, underline, x0, x1, y + d.underlineOffset(),
                                d.underlineThickness(), resolve.apply(lineInk));
                    }
                    if (struck) {
                        Ink lineInk = look.strikethroughInk() != null ? look.strikethroughInk() : textInk;
                        canvas.fillRect(x0, y - d.strikeoutOffset(), x1 - x0, d.strikeoutThickness(),
                                resolve.apply(lineInk));
                    }
                }
            }
        }

        /**
         * Draws a line under {@code [x0, x1)}. The dotted and wavy lines step by count, never by
         * adding to a position: far along an unbroken line a float stops moving when a step is
         * added to it, and a loop that waited for it to reach the end never ended.
         */
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
                    float step = 2 * t;
                    int dots = steps(x1 - x0, step);
                    for (int i = 0; i < dots; i++) {
                        float at = x0 + i * step;
                        canvas.fillRect(at, top, Math.min(t, x1 - at), t, paint);
                    }
                }
                case WAVY -> {
                    float amplitude = t * 1.25f;
                    float step = t * 2f;
                    float centre = top + amplitude;
                    int waves = steps(x1 - x0, step);
                    float px = x0;
                    float py = centre;
                    boolean up = true;
                    for (int i = 1; i <= waves; i++) {
                        float nx = Math.min(x0 + i * step, x1);
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

        /** How many steps of {@code step} cover {@code length}, bounded. */
        private static int steps(float length, float step) {
            if (!(length > 0)) {
                return 0;
            }
            return (int) Math.min(MAX_UNDERLINE_STEPS, Math.ceil(length / step));
        }
    }

    /** The font a run of a style is shaped in, where it sits, and its decorations. */
    private record FontChoice(Font font, float raise, float trackingEms, TextDecorations decorations) {

        static FontChoice of(TextStyle style, Font base, TextRuler ruler) {
            Font font = style.fontOver(base);
            float raise = 0;
            switch (Objects.requireNonNullElse(style.baseline(), TextStyle.Baseline.NORMAL)) {
                case SUPERSCRIPT -> {
                    TextDecorations face = ruler.decorations(font);
                    raise = within(face.superscriptOffset(), -font.size(), font.size(), 0);
                    font = font.withSize(within(face.superscriptSize(), 0.3f * font.size(), font.size(),
                            font.size()));
                }
                case SUBSCRIPT -> {
                    TextDecorations face = ruler.decorations(font);
                    raise = -within(face.subscriptOffset(), -font.size(), font.size(), 0);
                    font = font.withSize(within(face.subscriptSize(), 0.3f * font.size(), font.size(),
                            font.size()));
                }
                default -> { }
            }
            float tracking = Float.isNaN(style.tracking()) ? 0 : style.tracking();
            return new FontChoice(font, raise, tracking, ruler.decorations(font));
        }

        /**
         * A face's figure kept between {@code low} and {@code high}, and {@code otherwise} when it
         * is not a number: a malformed face's tables do not set text at a hundred ems, or throw.
         */
        private static float within(float value, float low, float high, float otherwise) {
            if (!Float.isFinite(value)) {
                return otherwise;
            }
            return Math.max(low, Math.min(high, value));
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

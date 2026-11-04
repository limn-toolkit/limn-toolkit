package limn.graphics;

import java.util.Objects;
import limn.internal.lang.Checks;
import org.jspecify.annotations.Nullable;

/**
 * How a piece of {@link StyledText} differs from the text around it: only what it changes. An
 * attribute a style does not set is the paragraph's, and a paragraph's is the base the text is
 * laid out with (a label's font and colour), so a style that says "bold" is bold in whatever
 * family, size and colour the label is, and keeps following a change of the default family or
 * of the palette.
 *
 * <p>Immutable; each method answers a new style with one more attribute set, starting from
 * {@link #of()}:
 *
 * <pre>{@code
 * TextStyle amount = TextStyle.of().weight(600);
 * TextStyle overdue = TextStyle.of().ink(Tone.DANGER).underline(TextStyle.Underline.WAVY);
 * }</pre>
 *
 * <p>What a style can say, and what it leaves to the face: a weight is drawn by the family's face
 * nearest it (see {@link Font}); an underline, a strike-through and raised or lowered text sit
 * where the face's own tables put them ({@link TextDecorations}).
 */
public final class TextStyle {

    /**
     * How a piece is underlined.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Underline {
        /** Not underlined; what a piece inside an underlined paragraph sets to take it off. */
        NONE,
        /** One line, at the face's underline position and thickness. */
        SINGLE,
        /** Two lines of the face's thickness, one thickness apart. */
        DOUBLE,
        /** A row of dots of the face's thickness. */
        DOTTED,
        /** A wave, as a spelling or a validation mark is drawn. */
        WAVY
    }

    /**
     * Where a piece sits against the line's baseline.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Baseline {
        /** On the baseline. */
        NORMAL,
        /** Raised and smaller, by the face's superscript offset and size. */
        SUPERSCRIPT,
        /** Lowered and smaller, by the face's subscript offset and size. */
        SUBSCRIPT
    }

    private static final TextStyle EMPTY = new TextStyle(null, Float.NaN, Float.NaN, 0, null, null,
            null, null, null, null, null, null, Float.NaN);

    private final @Nullable String family;
    private final float size;       // NaN: inherited
    private final float scale;      // NaN: inherited (1)
    private final int weight;       // 0: inherited; AT_LEAST_BOLD: bold() over what is inherited
    private final @Nullable Boolean italic;
    private final @Nullable Ink ink;
    private final @Nullable Ink background;
    private final @Nullable Underline underline;
    private final @Nullable Ink underlineInk;
    private final @Nullable Boolean strikethrough;
    private final @Nullable Ink strikethroughInk;
    private final @Nullable Baseline baseline;
    private final float tracking;   // NaN: inherited (0)
    /**
     * The last {@link #fontOver} answer and the base it was for, compared by identity: a label's
     * base font is one object across its paints, and the font store's memo is keyed by identity,
     * so a new font per layout missed it on every run. Racy and harmless: a lost write recomputes.
     */
    private transient @Nullable FontMemo fontMemo;

    private record FontMemo(Font base, Font font) {
    }

    /** {@link #bold()}'s weight: {@value Font#BOLD}, or the inherited weight where that is heavier. */
    private static final int AT_LEAST_BOLD = -1;

    /** The smallest and the largest em a style makes, whatever sizes and scales multiply to. */
    private static final float MIN_POINTS = 0.1f;
    private static final float MAX_POINTS = 100_000f;

    private TextStyle(@Nullable String family, float size, float scale, int weight,
                      @Nullable Boolean italic, @Nullable Ink ink, @Nullable Ink background,
                      @Nullable Underline underline, @Nullable Ink underlineInk,
                      @Nullable Boolean strikethrough, @Nullable Ink strikethroughInk,
                      @Nullable Baseline baseline, float tracking) {
        this.family = family;
        this.size = size;
        this.scale = scale;
        this.weight = weight;
        this.italic = italic;
        this.ink = ink;
        this.background = background;
        this.underline = underline;
        this.underlineInk = underlineInk;
        this.strikethrough = strikethrough;
        this.strikethroughInk = strikethroughInk;
        this.baseline = baseline;
        this.tracking = tracking;
    }

    /** @return the style that changes nothing */
    public static TextStyle of() {
        return EMPTY;
    }

    private TextStyle with(@Nullable String family, float size, float scale, int weight,
                           @Nullable Boolean italic, @Nullable Ink ink, @Nullable Ink background,
                           @Nullable Underline underline, @Nullable Ink underlineInk,
                           @Nullable Boolean strikethrough, @Nullable Ink strikethroughInk,
                           @Nullable Baseline baseline, float tracking) {
        return new TextStyle(family, size, scale, weight, italic, ink, background, underline,
                underlineInk, strikethrough, strikethroughInk, baseline, tracking);
    }

    // ------------------------------------------------------------------ the font

    /**
     * @param newFamily a family name, as {@link Font#family()} takes one
     * @return this style in another family
     */
    public TextStyle family(String newFamily) {
        Objects.requireNonNull(newFamily, "family");
        return with(newFamily, size, scale, weight, italic, ink, background, underline, underlineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    /**
     * An absolute size, which a {@link #scale} set on the same style multiplies.
     *
     * @param points the em size in logical points
     * @return this style at that size
     */
    public TextStyle size(float points) {
        Checks.positive(points, "size");
        return with(family, points, scale, weight, italic, ink, background, underline, underlineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    /**
     * A size relative to the size this style inherits: 1.25 is a quarter larger than the text
     * around it, whatever that is.
     *
     * @param factor greater than zero
     * @return this style at that scale
     */
    public TextStyle scale(float factor) {
        Checks.positive(factor, "scale");
        return with(family, size, factor, weight, italic, ink, background, underline, underlineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    /**
     * @param newWeight 1 to 1000, as {@link Font#weight()} takes one
     * @return this style at that weight
     */
    public TextStyle weight(int newWeight) {
        if (newWeight < 1 || newWeight > 1000) {
            throw new IllegalArgumentException("weight must be 1 to 1000: " + newWeight);
        }
        return with(family, size, scale, newWeight, italic, ink, background, underline, underlineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    /**
     * Bold over the text around it: {@value Font#BOLD}, or the weight it inherits where that is
     * heavier, as {@link Font#bold()} is, so a word made bold inside a heading set in Black stays
     * Black. {@link #weight(int)} sets a weight exactly.
     *
     * @return this style bold
     */
    public TextStyle bold() {
        return with(family, size, scale, AT_LEAST_BOLD, italic, ink, background, underline, underlineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    /** @return this style italic */
    public TextStyle italic() {
        return italic(true);
    }

    /**
     * @param newItalic whether italic; {@code false} takes an inherited italic off
     * @return this style with that slant
     */
    public TextStyle italic(boolean newItalic) {
        return with(family, size, scale, weight, newItalic, ink, background, underline, underlineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    // ------------------------------------------------------------------ the ink

    /**
     * What the text is drawn in: a {@link Color}, a tone, or an ink of the application's.
     *
     * @param newInk the ink
     * @return this style in that ink
     */
    public TextStyle ink(Ink newInk) {
        Objects.requireNonNull(newInk, "ink");
        return with(family, size, scale, weight, italic, newInk, background, underline, underlineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    /**
     * What is drawn behind the text, over the height of its own font's line; a highlight.
     *
     * @param newBackground the ink behind the text
     * @return this style with that background
     */
    public TextStyle background(Ink newBackground) {
        Objects.requireNonNull(newBackground, "background");
        return with(family, size, scale, weight, italic, ink, newBackground, underline, underlineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    // ------------------------------------------------------------------ the lines

    /**
     * @return this style underlined with one line, in the line's ink where one is set here or
     *         inherited, else the text's
     */
    public TextStyle underline() {
        return underline(Underline.SINGLE);
    }

    /**
     * @param kind how the text is underlined; {@link Underline#NONE} takes an inherited one off
     * @return this style underlined so, in the line's ink where one is set here or inherited, else
     *         the text's
     */
    public TextStyle underline(Underline kind) {
        Objects.requireNonNull(kind, "kind");
        return with(family, size, scale, weight, italic, ink, background, kind, underlineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    /**
     * @param kind    how the text is underlined
     * @param lineInk what the line is drawn in, apart from the text
     * @return this style underlined so, in that ink
     */
    public TextStyle underline(Underline kind, Ink lineInk) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lineInk, "lineInk");
        return with(family, size, scale, weight, italic, ink, background, kind, lineInk,
                strikethrough, strikethroughInk, baseline, tracking);
    }

    /**
     * @return this style struck through, in the line's ink where one is set here or inherited,
     *         else the text's
     */
    public TextStyle strikethrough() {
        return with(family, size, scale, weight, italic, ink, background, underline, underlineInk,
                true, strikethroughInk, baseline, tracking);
    }

    /**
     * @param lineInk what the line is drawn in, apart from the text
     * @return this style struck through in that ink
     */
    public TextStyle strikethrough(Ink lineInk) {
        Objects.requireNonNull(lineInk, "lineInk");
        return with(family, size, scale, weight, italic, ink, background, underline, underlineInk,
                true, lineInk, baseline, tracking);
    }

    /**
     * @param struck whether struck through; {@code false} takes an inherited line off
     * @return this style so
     */
    public TextStyle strikethrough(boolean struck) {
        return with(family, size, scale, weight, italic, ink, background, underline, underlineInk,
                struck, strikethroughInk, baseline, tracking);
    }

    // ------------------------------------------------------------------ placement

    /** @return this style raised as a superscript */
    public TextStyle superscript() {
        return baseline(Baseline.SUPERSCRIPT);
    }

    /** @return this style lowered as a subscript */
    public TextStyle subscript() {
        return baseline(Baseline.SUBSCRIPT);
    }

    /**
     * @param where where the text sits against the baseline
     * @return this style placed so
     */
    public TextStyle baseline(Baseline where) {
        Objects.requireNonNull(where, "where");
        return with(family, size, scale, weight, italic, ink, background, underline, underlineInk,
                strikethrough, strikethroughInk, where, tracking);
    }

    /**
     * Extra space after every character, in ems of the text's own size: 0.05 opens a heading up,
     * a negative value tightens it. Never applied inside a script whose letters join (Arabic, and
     * the others written that way), where a gap would break every word.
     *
     * @param ems the extra space, in ems
     * @return this style so spaced
     */
    public TextStyle tracking(float ems) {
        Checks.finite(ems, "tracking");
        return with(family, size, scale, weight, italic, ink, background, underline, underlineInk,
                strikethrough, strikethroughInk, baseline, ems);
    }

    // ------------------------------------------------------------------ inheritance

    /**
     * This style over {@code parent}: each attribute this style sets, else the parent's. A scale
     * set on both multiplies; a size set here is absolute, and the parent's scale does not reach it.
     *
     * @param parent the style this one inherits from
     * @return the combined style
     */
    public TextStyle over(TextStyle parent) {
        Objects.requireNonNull(parent, "parent");
        if (this == EMPTY) {
            return parent;
        }
        if (parent == EMPTY) {
            return this;
        }
        float combinedScale = !Float.isNaN(size) || Float.isNaN(parent.scale) ? scale
                : Float.isNaN(scale) ? parent.scale : scale * parent.scale;
        return with(family != null ? family : parent.family,
                Float.isNaN(size) ? parent.size : size,
                combinedScale,
                weight == 0 ? parent.weight
                        : weight != AT_LEAST_BOLD ? weight
                        : parent.weight == 0 || parent.weight == AT_LEAST_BOLD ? AT_LEAST_BOLD
                        : Math.max(parent.weight, Font.BOLD),
                italic != null ? italic : parent.italic,
                ink != null ? ink : parent.ink,
                background != null ? background : parent.background,
                underline != null ? underline : parent.underline,
                underlineInk != null ? underlineInk : parent.underlineInk,
                strikethrough != null ? strikethrough : parent.strikethrough,
                strikethroughInk != null ? strikethroughInk : parent.strikethroughInk,
                baseline != null ? baseline : parent.baseline,
                Float.isNaN(tracking) ? parent.tracking : tracking);
    }

    /**
     * The font this style draws in over {@code base}: its family, size and scale, weight and slant
     * where it sets them, {@code base}'s where it does not. Raised and lowered text is sized by
     * the layout, from the face, and not here.
     *
     * @param base the font the text around it is drawn in
     * @return the font
     */
    Font fontOver(Font base) {
        Objects.requireNonNull(base, "base");
        if (family == null && Float.isNaN(size) && Float.isNaN(scale) && weight == 0 && italic == null) {
            return base;
        }
        FontMemo memo = fontMemo;
        if (memo != null && memo.base() == base) {
            return memo.font();
        }
        // Kept between bounds: a size and a scale each allowed alone multiply to infinity or to
        // nothing, which no font can be.
        float points = (Float.isNaN(size) ? base.size() : size) * (Float.isNaN(scale) ? 1 : scale);
        points = Math.max(MIN_POINTS, Math.min(MAX_POINTS, points));
        Font font = new Font(family != null ? family : base.family(), points,
                weight == 0 ? base.weight()
                        : weight == AT_LEAST_BOLD ? Math.max(base.weight(), Font.BOLD) : weight,
                italic != null ? italic : base.isItalic());
        fontMemo = new FontMemo(base, font);
        return font;
    }

    /**
     * The family this style sets.
     *
     * @return the family, or {@code null} where the text keeps the one around it
     */
    public @Nullable String family() {
        return family;
    }

    /**
     * The size this style sets.
     *
     * @return the size in points, or {@link Float#NaN} where the text keeps the one around it
     */
    public float size() {
        return size;
    }

    /**
     * The factor this style scales the size by.
     *
     * @return the factor, or {@link Float#NaN} where the text keeps the one around it
     */
    public float scale() {
        return scale;
    }

    /**
     * The weight this style sets.
     *
     * @return the weight; {@value Font#BOLD} for {@link #bold()}, which is at least that (see
     *         {@link #isAtLeastBold()}); {@code 0} where the text keeps the one around it
     */
    public int weight() {
        return weight == AT_LEAST_BOLD ? Font.BOLD : weight;
    }

    /**
     * Whether this style is {@link #bold()}: bold, or the weight around it where that is heavier.
     *
     * @return whether it is
     */
    public boolean isAtLeastBold() {
        return weight == AT_LEAST_BOLD;
    }

    /**
     * Whether this style sets the text italic or upright.
     *
     * @return {@code true} for italic, {@code false} for upright, or {@code null} where the text
     *         keeps the slant around it
     */
    public @Nullable Boolean isItalic() {
        return italic;
    }

    /**
     * The ink this style draws its text in.
     *
     * @return the ink, or {@code null} where the text keeps the one around it
     */
    public @Nullable Ink ink() {
        return ink;
    }

    /**
     * The ink this style fills behind its text with.
     *
     * @return the ink, or {@code null} where the text keeps the one around it
     */
    public @Nullable Ink background() {
        return background;
    }

    /**
     * The underline this style sets; {@link Underline#NONE} takes away one the text around it has.
     *
     * @return the underline, or {@code null} where the text keeps the one around it
     */
    public @Nullable Underline underlineKind() {
        return underline;
    }

    /**
     * The underline's own ink.
     *
     * @return the ink, or {@code null} where the line keeps the one around it, at last the text's
     */
    public @Nullable Ink underlineInk() {
        return underlineInk;
    }

    /**
     * Whether this style strikes its text through.
     *
     * @return {@code true} for struck through, {@code false} for not, or {@code null} where the
     *         text keeps what the text around it does
     */
    public @Nullable Boolean isStruckThrough() {
        return strikethrough;
    }

    /**
     * The strike-through's own ink.
     *
     * @return the ink, or {@code null} where the line keeps the one around it, at last the text's
     */
    public @Nullable Ink strikethroughInk() {
        return strikethroughInk;
    }

    /**
     * Where this style sets its text against the line.
     *
     * @return where, or {@code null} where the text keeps the place around it
     */
    public @Nullable Baseline baseline() {
        return baseline;
    }

    /**
     * The space this style adds between letters.
     *
     * @return the tracking in ems, or {@link Float#NaN} where the text keeps the one around it
     */
    public float tracking() {
        return tracking;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TextStyle that)) {
            return false;
        }
        return Objects.equals(family, that.family)
                && Float.compare(size, that.size) == 0
                && Float.compare(scale, that.scale) == 0
                && weight == that.weight
                && Objects.equals(italic, that.italic)
                && Objects.equals(ink, that.ink)
                && Objects.equals(background, that.background)
                && underline == that.underline
                && Objects.equals(underlineInk, that.underlineInk)
                && Objects.equals(strikethrough, that.strikethrough)
                && Objects.equals(strikethroughInk, that.strikethroughInk)
                && baseline == that.baseline
                && Float.compare(tracking, that.tracking) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(family, size, scale, weight, italic, ink, background, underline,
                underlineInk, strikethrough, strikethroughInk, baseline, tracking);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("TextStyle[");
        if (family != null) {
            out.append("family=").append(family).append(' ');
        }
        if (!Float.isNaN(size)) {
            out.append("size=").append(size).append(' ');
        }
        if (!Float.isNaN(scale)) {
            out.append("scale=").append(scale).append(' ');
        }
        if (weight != 0) {
            out.append("weight=").append(weight == AT_LEAST_BOLD ? "bold" : String.valueOf(weight)).append(' ');
        }
        if (italic != null) {
            out.append("italic=").append(italic).append(' ');
        }
        if (ink != null) {
            out.append("ink=").append(ink).append(' ');
        }
        if (background != null) {
            out.append("background=").append(background).append(' ');
        }
        if (underline != null) {
            out.append("underline=").append(underline).append(' ');
        }
        if (underlineInk != null) {
            out.append("underlineInk=").append(underlineInk).append(' ');
        }
        if (strikethrough != null) {
            out.append("strikethrough=").append(strikethrough).append(' ');
        }
        if (strikethroughInk != null) {
            out.append("strikethroughInk=").append(strikethroughInk).append(' ');
        }
        if (baseline != null) {
            out.append("baseline=").append(baseline).append(' ');
        }
        if (!Float.isNaN(tracking)) {
            out.append("tracking=").append(tracking).append(' ');
        }
        return out.toString().strip() + "]";
    }
}

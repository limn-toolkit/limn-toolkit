package limn.graphics;

import java.util.Objects;
import limn.internal.lang.Checks;
import org.jspecify.annotations.Nullable;

/**
 * How one paragraph of {@link StyledText} is set: its alignment, indents, the space around it, its
 * line spacing, the list it belongs to, and the {@link TextStyle} its text inherits. Only what it
 * sets differs from the base the text is laid out with; immutable, built from {@link #of()}.
 *
 * <p><b>Start and end follow the alignment's direction.</b> A paragraph that sets its own
 * alignment is aligned, indented and given its marker in its own direction: one that reads right
 * to left starts at the right, its start indent and its marker on the right. One that sets none
 * takes the base's alignment, read in the base's direction, and its indent and marker stand at
 * that edge, so a paragraph of Latin text in a right-to-left interface starts at the right, as a
 * label's plain text does, and a marker never hangs at one edge with its item's text at the other.
 *
 * <p><b>A list is a marker and a level.</b> Consecutive list paragraphs number together: a
 * numbered paragraph counts on from the one before it at its level, a deeper level starts its own
 * count, and any paragraph that is not in a list ends every count. The markers are fixed by level,
 * bullets {@code •}, {@code ◦}, {@code ▪} and numbers {@code 1.}, {@code a.}, {@code i.}, with
 * the digits of the language the text is shown in, and each level is indented by
 * {@link #LIST_INDENT_EMS} ems with its marker hanging in that room; a list whose widest marker
 * needs more room indents all its items by as much more. A numbered item can say its own number
 * ({@link #listNumber}), and the items after it count on from it: a list a code block interrupts
 * goes on at "3." after it. The number is that paragraph's alone: a paragraph that repeats its
 * style, by {@code StyledText.Builder.paragraph()} or a line break in a piece, counts on.
 *
 * <p><b>A paragraph's direction is detected</b> from its first strong character, the base's
 * where it has none, unless it sets one ({@link #direction}): a line of code is read left to
 * right whatever its strings and comments hold.
 */
public final class ParagraphStyle {

    /**
     * How the lines of a paragraph sit across its width.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Alignment {
        /** Against the edge reading starts at. */
        START,
        /** Centred. */
        CENTER,
        /** Against the edge reading ends at. */
        END,
        /** Stretched to both edges at its spaces, its last line against the start. */
        JUSTIFY
    }

    /**
     * The kind of list a paragraph is an item of.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum ListMarker {
        /** A bullet: {@code •}, {@code ◦} and {@code ▪}, by level. */
        BULLET,
        /** A number: {@code 1.}, {@code a.} and {@code i.}, by level. */
        NUMBER
    }

    /** How far each list level indents its paragraph, in ems of the paragraph's text. */
    public static final float LIST_INDENT_EMS = 1.75f;

    /** The deepest list level, counting from 0. */
    public static final int MAX_LEVEL = 8;

    /** The largest number a list item says, given or counted: a count past it stays at it. */
    public static final int MAX_LIST_NUMBER = 999_999_999;

    private static final ParagraphStyle EMPTY = new ParagraphStyle(null, 0, 0, 0, 0, 0, Float.NaN,
            null, 0, 0, null, TextStyle.of());

    private final @Nullable Alignment alignment;
    private final float startIndent;
    private final float endIndent;
    private final float firstLineIndent;
    private final float spaceBefore;
    private final float spaceAfter;
    private final float lineSpacing; // NaN: 1
    private final @Nullable ListMarker list;
    private final int level;
    private final int number;  // 0: counted
    private final ShapedText.@Nullable Direction direction;
    private final TextStyle text;

    private ParagraphStyle(@Nullable Alignment alignment, float startIndent, float endIndent,
                           float firstLineIndent, float spaceBefore, float spaceAfter,
                           float lineSpacing, @Nullable ListMarker list, int level, int number,
                           ShapedText.@Nullable Direction direction, TextStyle text) {
        this.alignment = alignment;
        this.startIndent = startIndent;
        this.endIndent = endIndent;
        this.firstLineIndent = firstLineIndent;
        this.spaceBefore = spaceBefore;
        this.spaceAfter = spaceAfter;
        this.lineSpacing = lineSpacing;
        this.list = list;
        this.level = level;
        this.number = number;
        this.direction = direction;
        this.text = text;
    }

    /** @return the paragraph style that changes nothing */
    public static ParagraphStyle of() {
        return EMPTY;
    }

    /**
     * @param newAlignment how the lines sit; a paragraph that sets none takes the base's
     * @return this style so aligned
     */
    public ParagraphStyle alignment(Alignment newAlignment) {
        Objects.requireNonNull(newAlignment, "alignment");
        return new ParagraphStyle(newAlignment, startIndent, endIndent, firstLineIndent, spaceBefore,
                spaceAfter, lineSpacing, list, level, number, direction, text);
    }

    /**
     * @param start room before every line at the start edge, in logical points
     * @param end   room after every line at the end edge, in logical points
     * @return this style so indented
     */
    public ParagraphStyle indent(float start, float end) {
        return new ParagraphStyle(alignment, nonNegative(start, "start"), nonNegative(end, "end"),
                firstLineIndent, spaceBefore, spaceAfter, lineSpacing, list, level, number, direction, text);
    }

    /**
     * The first line's indent beyond the start indent; negative hangs the first line out into it.
     *
     * @param points the extra indent of the first line, in logical points
     * @return this style so indented
     */
    public ParagraphStyle firstLineIndent(float points) {
        Checks.finite(points, "firstLineIndent");
        return new ParagraphStyle(alignment, startIndent, endIndent, points, spaceBefore, spaceAfter,
                lineSpacing, list, level, number, direction, text);
    }

    /**
     * @param before room above the paragraph, in logical points; none above the first
     * @param after  room below the paragraph, in logical points; none below the last
     * @return this style so spaced
     */
    public ParagraphStyle space(float before, float after) {
        return new ParagraphStyle(alignment, startIndent, endIndent, firstLineIndent,
                nonNegative(before, "before"), nonNegative(after, "after"), lineSpacing, list, level,
                number, direction, text);
    }

    /**
     * @param factor each line's height as a multiple of its natural height: 1.5 is one and a half
     * @return this style so spaced
     */
    public ParagraphStyle lineSpacing(float factor) {
        Checks.positive(factor, "lineSpacing");
        return new ParagraphStyle(alignment, startIndent, endIndent, firstLineIndent, spaceBefore,
                spaceAfter, factor, list, level, number, direction, text);
    }

    /**
     * Makes the paragraph an item of a list, at the outermost level.
     *
     * @param marker the kind of list
     * @return this style in that list
     */
    public ParagraphStyle list(ListMarker marker) {
        return list(marker, 0);
    }

    /**
     * Makes the paragraph an item of a list at {@code newLevel}, 0 the outermost.
     *
     * @param marker   the kind of list
     * @param newLevel 0 to {@value #MAX_LEVEL}
     * @return this style in that list
     */
    public ParagraphStyle list(ListMarker marker, int newLevel) {
        Objects.requireNonNull(marker, "marker");
        if (newLevel < 0 || newLevel > MAX_LEVEL) {
            throw new IllegalArgumentException("list level must be 0 to " + MAX_LEVEL + ": " + newLevel);
        }
        return new ParagraphStyle(alignment, startIndent, endIndent, firstLineIndent, spaceBefore,
                spaceAfter, lineSpacing, marker, newLevel, number, direction, text);
    }

    /**
     * Gives a numbered item its own number; the items after it in the same list count on from it.
     * A bullet has no number and ignores it.
     *
     * @param n 1 to {@value #MAX_LIST_NUMBER}: "3." for 3 at the outermost level, "c." and "iii."
     *          below it; or 0, for an item counted on from the one before, which takes off a
     *          number this style was given
     * @return this style with that number
     * @throws IllegalArgumentException when {@code n} is negative or above {@value #MAX_LIST_NUMBER}
     */
    public ParagraphStyle listNumber(int n) {
        if (n < 0 || n > MAX_LIST_NUMBER) {
            throw new IllegalArgumentException("a list number must be 0 to " + MAX_LIST_NUMBER + ": " + n);
        }
        return new ParagraphStyle(alignment, startIndent, endIndent, firstLineIndent, spaceBefore,
                spaceAfter, lineSpacing, list, level, n, direction, text);
    }

    /**
     * Fixes the paragraph's direction instead of detecting it: code, which is read left to right
     * even where a comment or a string in it is not. An alignment it does not set is the base's,
     * read in the base's direction, as for any paragraph.
     *
     * @param newDirection the direction its text is read in
     * @return this style read in that direction
     */
    public ParagraphStyle direction(ShapedText.Direction newDirection) {
        Objects.requireNonNull(newDirection, "direction");
        return new ParagraphStyle(alignment, startIndent, endIndent, firstLineIndent, spaceBefore,
                spaceAfter, lineSpacing, list, level, number, newDirection, text);
    }

    /**
     * The style the paragraph's text inherits, under its pieces' own: a heading paragraph larger
     * and bold, say.
     *
     * @param style the style
     * @return this paragraph style with that text style
     */
    public ParagraphStyle text(TextStyle style) {
        Objects.requireNonNull(style, "style");
        return new ParagraphStyle(alignment, startIndent, endIndent, firstLineIndent, spaceBefore,
                spaceAfter, lineSpacing, list, level, number, direction, style);
    }

    /**
     * The alignment this style sets.
     *
     * @return the alignment, or {@code null} for the one the text is drawn with
     */
    public @Nullable Alignment alignment() {
        return alignment;
    }

    /**
     * The room before every line, on the side the paragraph's lines start.
     *
     * @return the indent in logical points
     */
    public float startIndent() {
        return startIndent;
    }

    /**
     * The room after every line, on the side the paragraph's lines end.
     *
     * @return the indent in logical points
     */
    public float endIndent() {
        return endIndent;
    }

    /**
     * The first line's indent, on top of {@link #startIndent()}.
     *
     * @return the indent in logical points
     */
    public float firstLineIndent() {
        return firstLineIndent;
    }

    /**
     * The room above the paragraph.
     *
     * @return the room in logical points
     */
    public float spaceBefore() {
        return spaceBefore;
    }

    /**
     * The room below the paragraph.
     *
     * @return the room in logical points
     */
    public float spaceAfter() {
        return spaceAfter;
    }

    /**
     * The factor the paragraph's line height is multiplied by.
     *
     * @return the factor; 1 where none is set
     */
    public float lineSpacing() {
        return Float.isNaN(lineSpacing) ? 1 : lineSpacing;
    }

    /**
     * The list the paragraph is an item of.
     *
     * @return the marker, or {@code null} for a paragraph in no list
     */
    public @Nullable ListMarker list() {
        return list;
    }

    /**
     * How deep in its list the paragraph is.
     *
     * @return the level, 0 the outermost
     */
    public int level() {
        return level;
    }

    /**
     * The item's own number.
     *
     * @return the number, or 0 where it is counted from the items before it
     */
    public int listNumber() {
        return number;
    }

    /**
     * The direction this style sets.
     *
     * @return the direction, or {@code null} where it is found from the paragraph's text
     */
    public ShapedText.@Nullable Direction direction() {
        return direction;
    }

    /**
     * The style the paragraph's text inherits, under its pieces' own.
     *
     * @return the style
     */
    public TextStyle textStyle() {
        return text;
    }

    private static float nonNegative(float value, String name) {
        Checks.finite(value, name);
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
        return value;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ParagraphStyle that)) {
            return false;
        }
        return alignment == that.alignment
                && Float.compare(startIndent, that.startIndent) == 0
                && Float.compare(endIndent, that.endIndent) == 0
                && Float.compare(firstLineIndent, that.firstLineIndent) == 0
                && Float.compare(spaceBefore, that.spaceBefore) == 0
                && Float.compare(spaceAfter, that.spaceAfter) == 0
                && Float.compare(lineSpacing, that.lineSpacing) == 0
                && list == that.list
                && level == that.level
                && number == that.number
                && direction == that.direction
                && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alignment, startIndent, endIndent, firstLineIndent, spaceBefore,
                spaceAfter, lineSpacing, list, level, number, direction, text);
    }

    @Override
    public String toString() {
        return "ParagraphStyle[alignment=" + alignment + ", indent=" + startIndent + "/" + endIndent
                + ", firstLine=" + firstLineIndent + ", space=" + spaceBefore + "/" + spaceAfter
                + ", lineSpacing=" + lineSpacing() + ", list=" + list + "@" + level
                + (number > 0 ? " #" + number : "") + ", direction=" + direction + ", text=" + text + "]";
    }
}

package limn.scene;

/**
 * Layout constraints handed down the tree (Flutter/Compose style): a widget's
 * measured size must fall inside {@code [minWidth..maxWidth] x
 * [minHeight..maxHeight]}. {@link #UNBOUNDED_LIMIT} marks an unbounded axis.
 */
public record Constraints(float minWidth, float maxWidth, float minHeight, float maxHeight) {

    /** Sentinel for "no upper bound" (scrollables measure children with it). */
    public static final float UNBOUNDED_LIMIT = Float.POSITIVE_INFINITY;

    public Constraints {
        if (minWidth < 0 || minHeight < 0 || maxWidth < minWidth || maxHeight < minHeight) {
            throw new IllegalArgumentException(
                    "invalid constraints: " + minWidth + ".." + maxWidth + " x " + minHeight + ".." + maxHeight);
        }
    }

    /** Exactly {@code width x height}. */
    public static Constraints tight(float width, float height) {
        return new Constraints(width, width, height, height);
    }

    /** Anything from zero up to {@code width x height}. */
    public static Constraints loose(float width, float height) {
        return new Constraints(0, width, 0, height);
    }

    /** Whether the maximum width is finite; unbounded means "take your intrinsic width". */
    public boolean hasBoundedWidth() {
        return maxWidth != UNBOUNDED_LIMIT;
    }

    /** Whether the maximum height is finite. */
    public boolean hasBoundedHeight() {
        return maxHeight != UNBOUNDED_LIMIT;
    }

    /** @return these constraints with minimums dropped to zero */
    public Constraints loosened() {
        return new Constraints(0, maxWidth, 0, maxHeight);
    }

    /** @return constraints shrunk by the given insets (never below zero) */
    public Constraints deflate(Insets insets) {
        float horizontal = insets.left() + insets.right();
        float vertical = insets.top() + insets.bottom();
        return new Constraints(
                Math.max(0, minWidth - horizontal),
                Math.max(0, maxWidth == UNBOUNDED_LIMIT ? UNBOUNDED_LIMIT : maxWidth - horizontal),
                Math.max(0, minHeight - vertical),
                Math.max(0, maxHeight == UNBOUNDED_LIMIT ? UNBOUNDED_LIMIT : maxHeight - vertical));
    }

    /** Clamps a width into {@code [minWidth, maxWidth]}. */
    public float constrainWidth(float width) {
        return Math.min(Math.max(width, minWidth), maxWidth);
    }

    /** Clamps a height into {@code [minHeight, maxHeight]}. */
    public float constrainHeight(float height) {
        return Math.min(Math.max(height, minHeight), maxHeight);
    }

    /** Clamps both axes, which is what {@code onMeasure} normally returns. */
    public Size constrain(float width, float height) {
        return new Size(constrainWidth(width), constrainHeight(height));
    }
}

package limn.graphics;

/**
 * Immutable rounded rectangle with independent corner radii, in logical
 * points. Radii larger than the sides allow are reduced proportionally (the
 * CSS border-radius rule) by {@link #normalized()}.
 *
 * @param x           left edge
 * @param y           top edge
 * @param width       width
 * @param height      height
 * @param topLeft     top-left corner radius
 * @param topRight    top-right corner radius
 * @param bottomRight bottom-right corner radius
 * @param bottomLeft  bottom-left corner radius
 */
public record RoundRect(float x, float y, float width, float height,
                        float topLeft, float topRight, float bottomRight, float bottomLeft) {

    public RoundRect {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("negative size: " + width + "x" + height);
        }
        topLeft = Math.max(0, topLeft);
        topRight = Math.max(0, topRight);
        bottomRight = Math.max(0, bottomRight);
        bottomLeft = Math.max(0, bottomLeft);
    }

    /** Rounded rect with the same radius on all four corners. */
    public static RoundRect of(float x, float y, float width, float height, float radius) {
        return new RoundRect(x, y, width, height, radius, radius, radius, radius);
    }

    /**
     * @return an equivalent RoundRect whose radii are guaranteed to fit the
     *         sides: if any two radii sharing a side exceed its length, all
     *         four are scaled down by the same factor (CSS rule). Note:
     *         renderers additionally cap each radius at half the smaller side
     *         (the per-quadrant SDF's validity domain), so a lone radius in
     *         {@code (side/2, side]} draws as {@code side/2}
     */
    public RoundRect normalized() {
        float f = 1f;
        f = Math.min(f, side(width, topLeft, topRight));
        f = Math.min(f, side(height, topRight, bottomRight));
        f = Math.min(f, side(width, bottomRight, bottomLeft));
        f = Math.min(f, side(height, bottomLeft, topLeft));
        if (f >= 1f) {
            return this;
        }
        return new RoundRect(x, y, width, height,
                topLeft * f, topRight * f, bottomRight * f, bottomLeft * f);
    }

    private static float side(float length, float r1, float r2) {
        float sum = r1 + r2;
        return sum <= length || sum == 0 ? 1f : length / sum;
    }
}

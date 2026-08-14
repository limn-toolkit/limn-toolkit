package limn.graphics;

/**
 * Immutable axis-aligned rectangle in logical points.
 *
 * @param x      left edge
 * @param y      top edge
 * @param width  width (may be 0, never negative)
 * @param height height (may be 0, never negative)
 */
public record Rect(float x, float y, float width, float height) {

    public Rect {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("negative size: " + width + "x" + height);
        }
    }

    /** {@code x + width}. */
    public float right() {
        return x + width;
    }

    /** {@code y + height}. */
    public float bottom() {
        return y + height;
    }

    /** Horizontal midpoint. */
    public float centerX() {
        return x + width / 2f;
    }

    /** Vertical midpoint. */
    public float centerY() {
        return y + height / 2f;
    }

    /** Whether the point is inside, taking the left and top edges and excluding right and bottom. */
    public boolean contains(float px, float py) {
        return px >= x && px < right() && py >= y && py < bottom();
    }

    /** @return the intersection with {@code other} (a zero-sized rect if disjoint) */
    public Rect intersect(Rect other) {
        float nx = Math.max(x, other.x);
        float ny = Math.max(y, other.y);
        float nr = Math.min(right(), other.right());
        float nb = Math.min(bottom(), other.bottom());
        return new Rect(nx, ny, Math.max(0, nr - nx), Math.max(0, nb - ny));
    }

    /** @return the smallest rect containing both this and {@code other} (zero-sized rects contribute their origin) */
    public Rect union(Rect other) {
        float nx = Math.min(x, other.x);
        float ny = Math.min(y, other.y);
        float nr = Math.max(right(), other.right());
        float nb = Math.max(bottom(), other.bottom());
        return new Rect(nx, ny, nr - nx, nb - ny);
    }

    /** @return this rect grown by {@code amount} on every side (shrunk if negative) */
    public Rect inflate(float amount) {
        float w = Math.max(0, width + 2 * amount);
        float h = Math.max(0, height + 2 * amount);
        return new Rect(x - amount, y - amount, w, h);
    }
}

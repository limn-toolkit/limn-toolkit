package limn.math;

/**
 * An immutable 2-component vector (single precision): texture coords, screen points.
 *
 * <p>The arithmetic is component-wise; {@link #normalize} is the only operation with an edge
 * case.
 */
public record Vec2(float x, float y) {

    /** Both components zero. */
    public static final Vec2 ZERO = new Vec2(0, 0);
    /** Both components one. */
    public static final Vec2 ONE = new Vec2(1, 1);

    /** @return the sum of this vector and {@code o} */
    public Vec2 add(Vec2 o) {
        return new Vec2(x + o.x, y + o.y);
    }

    /** @return this vector minus {@code o} */
    public Vec2 sub(Vec2 o) {
        return new Vec2(x - o.x, y - o.y);
    }

    /** @return this vector scaled by {@code s} */
    public Vec2 mul(float s) {
        return new Vec2(x * s, y * s);
    }

    /** @return the dot product of this vector and {@code o} */
    public float dot(Vec2 o) {
        return x * o.x + y * o.y;
    }

    /** @return the squared length, which needs no square root */
    public float lengthSquared() {
        return x * x + y * y;
    }

    /** @return the Euclidean length */
    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    /** Unit vector in the same direction; returns {@link #ZERO} for a zero-length input. */
    public Vec2 normalize() {
        float len = length();
        return len > 1e-8f ? new Vec2(x / len, y / len) : ZERO;
    }
}

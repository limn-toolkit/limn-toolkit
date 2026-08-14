package limn.math;

/**
 * An immutable 4-component vector: homogeneous positions ({@code w=1}),
 * directions ({@code w=0}), and RGBA colors. The workhorse for matrix transforms.
 */
public record Vec4(float x, float y, float z, float w) {

    public static final Vec4 ZERO = new Vec4(0, 0, 0, 0);

    /** Component-wise sum, w included. */
    public Vec4 add(Vec4 o) {
        return new Vec4(x + o.x, y + o.y, z + o.z, w + o.w);
    }

    /** Scales all four components, w included. */
    public Vec4 mul(float s) {
        return new Vec4(x * s, y * s, z * s, w * s);
    }

    /** Four-component dot product. */
    public float dot(Vec4 o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    /** Perspective divide → the xyz in normalized device / world space. Falls back to xyz when {@code w≈0}. */
    public Vec3 perspectiveDivide() {
        return Math.abs(w) > 1e-8f ? new Vec3(x / w, y / w, z / w) : new Vec3(x, y, z);
    }

    /** The first three components, dropping w without dividing by it. */
    public Vec3 xyz() {
        return new Vec3(x, y, z);
    }

    /**
     * Component by index, {@code 0} = x through {@code 3} = w.
     *
     * @throws IndexOutOfBoundsException for any other index
     */
    public float get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            case 3 -> w;
            default -> throw new IndexOutOfBoundsException("Vec4 index " + i);
        };
    }
}

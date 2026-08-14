package limn.math;

/**
 * An immutable 3-component vector (single precision), right-handed. Used for
 * positions, directions, normals and colors in the 3D subsystem. Operations
 * return new instances, cheap enough for UI-scale 3D (a handful of transforms
 * per frame, not a hot game loop); the allocation-free forms live in
 * {@code MutVec3} and the {@code *Into} methods elsewhere in this package.
 *
 * <p>The arithmetic is component-wise and unsurprising, so only the operations with
 * an edge case worth knowing carry documentation: {@link #div}, {@link #normalize},
 * {@link #lerp} and {@link #get}.
 */
public record Vec3(float x, float y, float z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 ONE = new Vec3(1, 1, 1);
    public static final Vec3 UNIT_X = new Vec3(1, 0, 0);
    public static final Vec3 UNIT_Y = new Vec3(0, 1, 0);
    public static final Vec3 UNIT_Z = new Vec3(0, 0, 1);

    /** The same value in all three components. */
    public static Vec3 of(float s) {
        return new Vec3(s, s, s);
    }

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 mul(float s) {
        return new Vec3(x * s, y * s, z * s);
    }

    /** Component-wise multiply (e.g. modulating a color). */
    public Vec3 mul(Vec3 o) {
        return new Vec3(x * o.x, y * o.y, z * o.z);
    }

    /**
     * Component-wise division. <b>Not guarded:</b> {@code s == 0} yields infinities or
     * NaN rather than throwing, so divide by a length only after checking it.
     */
    public Vec3 div(float s) {
        return new Vec3(x / s, y / s, z / s);
    }

    public Vec3 negate() {
        return new Vec3(-x, -y, -z);
    }

    public float dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public float distance(Vec3 o) {
        return sub(o).length();
    }

    /** Unit vector in the same direction; returns {@link #ZERO} for a zero-length input. */
    public Vec3 normalize() {
        float len = length();
        return len > 1e-8f ? new Vec3(x / len, y / len, z / len) : ZERO;
    }

    /**
     * Linear interpolation towards {@code o}. {@code t} is <b>not clamped</b>: values
     * outside {@code [0,1]} extrapolate, which is what makes this usable for easing
     * curves that overshoot.
     */
    public Vec3 lerp(Vec3 o, float t) {
        return new Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    public Vec3 min(Vec3 o) {
        return new Vec3(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    public Vec3 max(Vec3 o) {
        return new Vec3(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    /**
     * Component by index, {@code 0} = x, {@code 1} = y, {@code 2} = z.
     *
     * @throws IndexOutOfBoundsException for any other index
     */
    public float get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IndexOutOfBoundsException("Vec3 index " + i);
        };
    }

    /** This vector with an explicit w: {@code 1} for a position, {@code 0} for a direction. */
    public Vec4 toVec4(float w) {
        return new Vec4(x, y, z, w);
    }
}

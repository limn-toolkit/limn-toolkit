package limn.math;

/**
 * An immutable unit quaternion (x, y, z, w) for rotations: no gimbal lock,
 * cheap to compose and interpolate ({@link #slerp}).
 */
public record Quat(float x, float y, float z, float w) {

    public static final Quat IDENTITY = new Quat(0, 0, 0, 1);

    /** Rotation of {@code angleRadians} about {@code axis} (normalized internally). */
    public static Quat fromAxisAngle(Vec3 axis, float angleRadians) {
        Vec3 a = axis.normalize();
        float half = angleRadians * 0.5f;
        float s = (float) Math.sin(half);
        return new Quat(a.x() * s, a.y() * s, a.z() * s, (float) Math.cos(half));
    }

    /** Magnitude; {@code 1} for any rotation quaternion. */
    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z + w * w);
    }

    /** Unit quaternion in the same direction, the fix for drift after repeated multiplication. */
    public Quat normalize() {
        float len = length();
        return len > 1e-8f ? new Quat(x / len, y / len, z / len, w / len) : IDENTITY;
    }

    /** The conjugate, which is the inverse rotation for a unit quaternion. */
    public Quat conjugate() {
        return new Quat(-x, -y, -z, w);
    }

    /** Hamilton product {@code this · o} (apply {@code o} first, then {@code this}). */
    public Quat multiply(Quat o) {
        return new Quat(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }

    /** Rotates {@code v} by this quaternion (assumes unit length). */
    public Vec3 rotate(Vec3 v) {
        Vec3 u = new Vec3(x, y, z);
        Vec3 t = u.cross(v).mul(2f);
        return v.add(t.mul(w)).add(u.cross(t));
    }

    /**
     * Spherical interpolation along the <b>shortest</b> arc: {@code o} is negated first
     * when the two face opposite ways, so the result never takes the long way round.
     * Falls back to a normalized linear blend when the two are nearly parallel and the
     * angle is too small to divide by. {@code t} is not clamped.
     */
    public Quat slerp(Quat o, float t) {
        float dot = x * o.x + y * o.y + z * o.z + w * o.w;
        Quat end = o;
        if (dot < 0) { // shortest path
            end = new Quat(-o.x, -o.y, -o.z, -o.w);
            dot = -dot;
        }
        if (dot > 0.9995f) { // nearly parallel, so linear + renormalize
            return new Quat(
                    x + (end.x - x) * t, y + (end.y - y) * t,
                    z + (end.z - z) * t, w + (end.w - w) * t).normalize();
        }
        float theta0 = (float) Math.acos(dot);
        float theta = theta0 * t;
        float sin0 = (float) Math.sin(theta0);
        float s0 = (float) Math.sin(theta0 - theta) / sin0;
        float s1 = (float) Math.sin(theta) / sin0;
        return new Quat(
                x * s0 + end.x * s1, y * s0 + end.y * s1,
                z * s0 + end.z * s1, w * s0 + end.w * s1);
    }

    /** Column-major 3×3 rotation matrix ({@code m[col*3+row]}); assumes unit length. */
    public Mat3 toMat3() {
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;
        float[] m = new float[9];
        m[0] = 1 - 2 * (yy + zz);
        m[1] = 2 * (xy + wz);
        m[2] = 2 * (xz - wy);
        m[3] = 2 * (xy - wz);
        m[4] = 1 - 2 * (xx + zz);
        m[5] = 2 * (yz + wx);
        m[6] = 2 * (xz + wy);
        m[7] = 2 * (yz - wx);
        m[8] = 1 - 2 * (xx + yy);
        return new Mat3(m);
    }
}

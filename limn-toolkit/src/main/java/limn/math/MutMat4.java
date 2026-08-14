package limn.math;

/**
 * A mutable column-major 4×4 matrix ({@code m[col*4 + row]}, same conventions
 * as {@link Mat4}) for allocation-free transform composition in hot loops.
 *
 * <p>{@link Mat4} stays the API currency (immutable, safe to cache and share),
 * and its per-operation allocation is fine at UI scale. At game scale (recomposing
 * thousands of entity transforms at 60 fps) those temporaries become GC pressure;
 * a {@code MutMat4} is reused across frames and every operation writes in place:
 *
 * <pre>{@code
 * MutMat4 world = new MutMat4();            // once, outside the loop
 * world.set(parentWorld).mul(local);        // per entity, zero allocation
 * world.toArray(scratch);                   // upload, zero allocation
 * }</pre>
 *
 * <p>{@link #toMat4()} snapshots into an immutable {@code Mat4} (one allocation)
 * for hand-off to APIs that cache by instance identity. Not thread-safe; confine
 * an instance to one thread (in practice: the UI thread).
 */
public final class MutMat4 {

    private final float[] m = new float[16]; // column-major

    /** Starts at identity. */
    public MutMat4() {
        m[0] = m[5] = m[10] = m[15] = 1;
    }

    /** Element at (row, col). */
    public float get(int row, int col) {
        return m[col * 4 + row];
    }

    /** Resets to the identity matrix. */
    public MutMat4 setIdentity() {
        java.util.Arrays.fill(m, 0f);
        m[0] = m[5] = m[10] = m[15] = 1;
        return this;
    }

    /** Copies {@code source} into this matrix. */
    public MutMat4 set(Mat4 source) {
        source.toArray(m);
        return this;
    }

    /** Copies {@code source} into this matrix ({@code source == this} is a no-op). */
    public MutMat4 set(MutMat4 source) {
        if (source != this) {
            System.arraycopy(source.m, 0, m, 0, 16);
        }
        return this;
    }

    /**
     * Sets this matrix to Translate · Rotate · Scale, the allocation-free
     * equivalent of {@link Mat4#trs}. {@code rotation} must be unit length.
     */
    public MutMat4 setTrs(Vec3 translation, Quat rotation, Vec3 scale) {
        float qx = rotation.x(), qy = rotation.y(), qz = rotation.z(), qw = rotation.w();
        float xx = qx * qx, yy = qy * qy, zz = qz * qz;
        float xy = qx * qy, xz = qx * qz, yz = qy * qz;
        float wx = qw * qx, wy = qw * qy, wz = qw * qz;
        float sx = scale.x(), sy = scale.y(), sz = scale.z();
        // Columns 0..2 = rotation columns scaled by s; column 3 = translation.
        m[0] = (1 - 2 * (yy + zz)) * sx;
        m[1] = 2 * (xy + wz) * sx;
        m[2] = 2 * (xz - wy) * sx;
        m[3] = 0;
        m[4] = 2 * (xy - wz) * sy;
        m[5] = (1 - 2 * (xx + zz)) * sy;
        m[6] = 2 * (yz + wx) * sy;
        m[7] = 0;
        m[8] = 2 * (xz + wy) * sz;
        m[9] = 2 * (yz - wx) * sz;
        m[10] = (1 - 2 * (xx + yy)) * sz;
        m[11] = 0;
        m[12] = translation.x();
        m[13] = translation.y();
        m[14] = translation.z();
        m[15] = 1;
        return this;
    }

    /** {@code this = this · o} (o applied first); see {@link Mat4#multiply}. */
    public MutMat4 mul(Mat4 o) {
        return mulArray(o.array());
    }

    /** {@code this = this · o}; {@code o == this} squares the matrix. */
    public MutMat4 mul(MutMat4 o) {
        return mulArray(o.m);
    }

    /** {@code this = a · b}; either argument may be {@code this}. */
    public MutMat4 setMultiply(MutMat4 a, MutMat4 b) {
        multiplyArrays(a.m, b.m, m);
        return this;
    }

    /** {@code this = a · b}. */
    public MutMat4 setMultiply(Mat4 a, Mat4 b) {
        Mat4.multiplyInto(a, b, m);
        return this;
    }

    /** {@code this = a · b}. */
    public MutMat4 setMultiply(Mat4 a, MutMat4 b) {
        multiplyArrays(a.array(), b.m, m);
        return this;
    }

    private MutMat4 mulArray(float[] b) {
        multiplyArrays(m, b, m);
        return this;
    }

    // Reads both operands fully into locals before writing, so out may alias
    // either input, which is the aliasing safety the in-place ops rely on.
    private static void multiplyArrays(float[] a, float[] b, float[] out) {
        float a00 = a[0], a10 = a[1], a20 = a[2], a30 = a[3];
        float a01 = a[4], a11 = a[5], a21 = a[6], a31 = a[7];
        float a02 = a[8], a12 = a[9], a22 = a[10], a32 = a[11];
        float a03 = a[12], a13 = a[13], a23 = a[14], a33 = a[15];
        float b00 = b[0], b10 = b[1], b20 = b[2], b30 = b[3];
        float b01 = b[4], b11 = b[5], b21 = b[6], b31 = b[7];
        float b02 = b[8], b12 = b[9], b22 = b[10], b32 = b[11];
        float b03 = b[12], b13 = b[13], b23 = b[14], b33 = b[15];
        out[0] = a00 * b00 + a01 * b10 + a02 * b20 + a03 * b30;
        out[1] = a10 * b00 + a11 * b10 + a12 * b20 + a13 * b30;
        out[2] = a20 * b00 + a21 * b10 + a22 * b20 + a23 * b30;
        out[3] = a30 * b00 + a31 * b10 + a32 * b20 + a33 * b30;
        out[4] = a00 * b01 + a01 * b11 + a02 * b21 + a03 * b31;
        out[5] = a10 * b01 + a11 * b11 + a12 * b21 + a13 * b31;
        out[6] = a20 * b01 + a21 * b11 + a22 * b21 + a23 * b31;
        out[7] = a30 * b01 + a31 * b11 + a32 * b21 + a33 * b31;
        out[8] = a00 * b02 + a01 * b12 + a02 * b22 + a03 * b32;
        out[9] = a10 * b02 + a11 * b12 + a12 * b22 + a13 * b32;
        out[10] = a20 * b02 + a21 * b12 + a22 * b22 + a23 * b32;
        out[11] = a30 * b02 + a31 * b12 + a32 * b22 + a33 * b32;
        out[12] = a00 * b03 + a01 * b13 + a02 * b23 + a03 * b33;
        out[13] = a10 * b03 + a11 * b13 + a12 * b23 + a13 * b33;
        out[14] = a20 * b03 + a21 * b13 + a22 * b23 + a23 * b33;
        out[15] = a30 * b03 + a31 * b13 + a32 * b23 + a33 * b33;
    }

    /**
     * Transforms position ({@code x}, {@code y}, {@code z}, w=1), applies the
     * perspective divide and writes x/y/z into {@code out} (length ≥ 3), the
     * allocation-free equivalent of {@link Mat4#transformPoint}.
     */
    public void transformPoint(float x, float y, float z, float[] out) {
        float w = m[3] * x + m[7] * y + m[11] * z + m[15];
        // Same w≈0 fallback as Vec4.perspectiveDivide.
        float invW = Math.abs(w) > 1e-8f ? 1f / w : 1f;
        out[0] = (m[0] * x + m[4] * y + m[8] * z + m[12]) * invW;
        out[1] = (m[1] * x + m[5] * y + m[9] * z + m[13]) * invW;
        out[2] = (m[2] * x + m[6] * y + m[10] * z + m[14]) * invW;
    }

    /**
     * Transforms direction ({@code x}, {@code y}, {@code z}, w=0) into
     * {@code out} (length ≥ 3); no translation, no divide.
     */
    public void transformDirection(float x, float y, float z, float[] out) {
        out[0] = m[0] * x + m[4] * y + m[8] * z;
        out[1] = m[1] * x + m[5] * y + m[9] * z;
        out[2] = m[2] * x + m[6] * y + m[10] * z;
    }

    /** Immutable snapshot (allocates), for APIs that cache {@link Mat4} by identity. */
    public Mat4 toMat4() {
        return new Mat4(m.clone());
    }

    /** Column-major copy into {@code out} (length ≥ 16), the allocation-free upload path. */
    public void toArray(float[] out) {
        System.arraycopy(m, 0, out, 0, 16);
    }
}

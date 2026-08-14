package limn.math;

/**
 * An immutable column-major 4×4 matrix ({@code m[col*4 + row]}, OpenGL
 * convention), single precision. Composition is {@code a.multiply(b)} = apply
 * {@code b} first, then {@code a}. Right-handed; clip-space z in [-1, 1].
 */
public final class Mat4 {

    /** The identity matrix (immutable, so one shared instance serves everyone). */
    public static final Mat4 IDENTITY = new Mat4(identityArray());

    private final float[] m; // column-major, length 16

    Mat4(float[] m) {
        this.m = m;
    }

    /** The backing column-major array: same-package readers only, never mutated. */
    float[] array() {
        return m;
    }

    /** The identity transform. */
    public static Mat4 identity() {
        return IDENTITY;
    }

    /** A pure translation. */
    public static Mat4 translation(Vec3 t) {
        float[] r = identityArray();
        r[12] = t.x();
        r[13] = t.y();
        r[14] = t.z();
        return new Mat4(r);
    }

    /** A pure non-uniform scale about the origin. */
    public static Mat4 scale(Vec3 s) {
        float[] r = new float[16];
        r[0] = s.x();
        r[5] = s.y();
        r[10] = s.z();
        r[15] = 1;
        return new Mat4(r);
    }

    /** Rotation from a unit quaternion. */
    public static Mat4 rotation(Quat q) {
        Mat3 r3 = q.toMat3();
        float[] r = new float[16];
        for (int c = 0; c < 3; c++) {
            for (int row = 0; row < 3; row++) {
                r[c * 4 + row] = r3.get(row, c);
            }
        }
        r[15] = 1;
        return new Mat4(r);
    }

    /** Translate · Rotate · Scale, composed directly. */
    public static Mat4 trs(Vec3 t, Quat q, Vec3 s) {
        Mat3 r3 = q.toMat3();
        float[] m = new float[16];
        // Columns 0..2 = rotation columns scaled by s; column 3 = translation.
        m[0] = r3.get(0, 0) * s.x(); m[1] = r3.get(1, 0) * s.x(); m[2] = r3.get(2, 0) * s.x();
        m[4] = r3.get(0, 1) * s.y(); m[5] = r3.get(1, 1) * s.y(); m[6] = r3.get(2, 1) * s.y();
        m[8] = r3.get(0, 2) * s.z(); m[9] = r3.get(1, 2) * s.z(); m[10] = r3.get(2, 2) * s.z();
        m[12] = t.x(); m[13] = t.y(); m[14] = t.z(); m[15] = 1;
        return new Mat4(m);
    }

    /** Perspective projection; {@code fovyRadians} is the vertical field of view. */
    public static Mat4 perspective(float fovyRadians, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovyRadians / 2.0));
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1;
        m[14] = (2 * far * near) / (near - far);
        return new Mat4(m);
    }

    /**
     * An orthographic projection mapping the box to clip space with depth in
     * {@code [-1, 1]}: the GL convention, not the {@code [0, 1]} one.
     */
    public static Mat4 orthographic(float left, float right, float bottom, float top,
                                    float near, float far) {
        float[] m = new float[16];
        m[0] = 2 / (right - left);
        m[5] = 2 / (top - bottom);
        m[10] = -2 / (far - near);
        m[12] = -(right + left) / (right - left);
        m[13] = -(top + bottom) / (top - bottom);
        m[14] = -(far + near) / (far - near);
        m[15] = 1;
        return new Mat4(m);
    }

    /**
     * A right-handed world-to-view matrix looking from {@code eye} at {@code center}.
     * {@code up} must not be parallel to the view direction, which would leave the
     * basis degenerate.
     */
    public static Mat4 lookAt(Vec3 eye, Vec3 center, Vec3 up) {
        Vec3 f = center.sub(eye).normalize();
        Vec3 s = f.cross(up).normalize();
        Vec3 u = s.cross(f);
        float[] m = new float[16];
        m[0] = s.x(); m[1] = u.x(); m[2] = -f.x();
        m[4] = s.y(); m[5] = u.y(); m[6] = -f.y();
        m[8] = s.z(); m[9] = u.z(); m[10] = -f.z();
        m[12] = -s.dot(eye); m[13] = -u.dot(eye); m[14] = f.dot(eye); m[15] = 1;
        return new Mat4(m);
    }

    /** Element at (row, col). */
    public float get(int row, int col) {
        return m[col * 4 + row];
    }

    /** {@code this · o}. */
    public Mat4 multiply(Mat4 o) {
        float[] a = this.m;
        float[] b = o.m;
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                r[col * 4 + row] = sum;
            }
        }
        return new Mat4(r);
    }

    /** The transpose: the inverse rotation, for a matrix that is purely a rotation. */
    public Mat4 transpose() {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                r[row * 4 + c] = m[c * 4 + row];
            }
        }
        return new Mat4(r);
    }

    /** Transforms a homogeneous vector. No perspective divide: the caller does that. */
    public Vec4 transform(Vec4 v) {
        return new Vec4(
                m[0] * v.x() + m[4] * v.y() + m[8] * v.z() + m[12] * v.w(),
                m[1] * v.x() + m[5] * v.y() + m[9] * v.z() + m[13] * v.w(),
                m[2] * v.x() + m[6] * v.y() + m[10] * v.z() + m[14] * v.w(),
                m[3] * v.x() + m[7] * v.y() + m[11] * v.z() + m[15] * v.w());
    }

    /** Transforms a position (w=1) and applies the perspective divide. */
    public Vec3 transformPoint(Vec3 p) {
        return transform(p.toVec4(1)).perspectiveDivide();
    }

    /** Transforms a direction (w=0): no translation, no divide. */
    public Vec3 transformDirection(Vec3 d) {
        return new Vec3(
                m[0] * d.x() + m[4] * d.y() + m[8] * d.z(),
                m[1] * d.x() + m[5] * d.y() + m[9] * d.z(),
                m[2] * d.x() + m[6] * d.y() + m[10] * d.z());
    }

    /** Upper-left 3×3. */
    public Mat3 toMat3() {
        float[] r = new float[9];
        for (int c = 0; c < 3; c++) {
            for (int row = 0; row < 3; row++) {
                r[c * 3 + row] = m[c * 4 + row];
            }
        }
        return new Mat3(r);
    }

    /** Normal matrix: {@code transpose(inverse(upper-left 3×3))}, for transforming normals. */
    public Mat3 normalMatrix() {
        return toMat3().invert().transpose();
    }

    /** @throws ArithmeticException if the matrix is singular. Gauss–Jordan with partial pivot. */
    public Mat4 invert() {
        float[] a = m.clone();
        float[] inv = identityArray();
        for (int col = 0; col < 4; col++) {
            int pivot = col;
            float best = Math.abs(a[col * 4 + col]);
            for (int row = col + 1; row < 4; row++) {
                float v = Math.abs(a[col * 4 + row]);
                if (v > best) {
                    best = v;
                    pivot = row;
                }
            }
            if (best < 1e-12f) {
                throw new ArithmeticException("singular Mat4");
            }
            if (pivot != col) {
                swapRows(a, col, pivot);
                swapRows(inv, col, pivot);
            }
            float pv = a[col * 4 + col];
            scaleRow(a, col, 1f / pv);
            scaleRow(inv, col, 1f / pv);
            for (int row = 0; row < 4; row++) {
                if (row == col) {
                    continue;
                }
                float factor = a[col * 4 + row];
                if (factor != 0) {
                    addScaledRow(a, col, row, -factor);
                    addScaledRow(inv, col, row, -factor);
                }
            }
        }
        return new Mat4(inv);
    }

    /** Column-major copy (length 16), for GPU upload. */
    public float[] toArray() {
        return m.clone();
    }

    /** Column-major copy into {@code out} (length ≥ 16), the allocation-free upload path. */
    public void toArray(float[] out) {
        System.arraycopy(m, 0, out, 0, 16);
    }

    // ------------------------------------------------- allocation-free variants
    //
    // Per-draw hot paths (one call per mesh per frame with thousands of
    // entities) compose into caller-owned scratch arrays instead of allocating
    // a Mat4/Mat3 per operation. Results are column-major, ready for GPU upload.

    /** {@code a · b} written column-major into {@code out} (length ≥ 16). Allocation-free. */
    public static void multiplyInto(Mat4 a, Mat4 b, float[] out) {
        float[] am = a.m;
        float[] bm = b.m;
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += am[k * 4 + row] * bm[col * 4 + k];
                }
                out[col * 4 + row] = sum;
            }
        }
    }

    /**
     * The normal matrix of {@code model}, {@code transpose(inverse(upper-left 3×3))},
     * written column-major into {@code out} (length ≥ 9). Equals the cofactor
     * matrix divided by the determinant, computed directly. Allocation-free.
     *
     * @throws ArithmeticException if the upper-left 3×3 is singular
     */
    public static void normalMatrixInto(Mat4 model, float[] out) {
        float[] m = model.m;
        // Upper-left 3×3 in column-major: element (row, col) = m[col*4 + row].
        float a00 = m[0], a10 = m[1], a20 = m[2];
        float a01 = m[4], a11 = m[5], a21 = m[6];
        float a02 = m[8], a12 = m[9], a22 = m[10];
        float c00 = a11 * a22 - a21 * a12;
        float c10 = a20 * a12 - a10 * a22;
        float c20 = a10 * a21 - a20 * a11;
        float det = a00 * c00 + a01 * c10 + a02 * c20;
        if (Math.abs(det) < 1e-12f) {
            throw new ArithmeticException("singular Mat4 upper-left 3×3");
        }
        float inv = 1f / det;
        // normal = cofactor(M) / det, written column-major: out[col*3+row] = C[row][col].
        out[0] = c00 * inv;
        out[1] = (a02 * a21 - a01 * a22) * inv;
        out[2] = (a01 * a12 - a02 * a11) * inv;
        out[3] = c10 * inv;
        out[4] = (a00 * a22 - a02 * a20) * inv;
        out[5] = (a02 * a10 - a00 * a12) * inv;
        out[6] = c20 * inv;
        out[7] = (a01 * a20 - a00 * a21) * inv;
        out[8] = (a00 * a11 - a01 * a10) * inv;
    }

    // ------------------------------------------------------- row ops (col-major)

    private static float[] identityArray() {
        float[] r = new float[16];
        r[0] = r[5] = r[10] = r[15] = 1;
        return r;
    }

    private static void swapRows(float[] a, int r1, int r2) {
        for (int c = 0; c < 4; c++) {
            float t = a[c * 4 + r1];
            a[c * 4 + r1] = a[c * 4 + r2];
            a[c * 4 + r2] = t;
        }
    }

    private static void scaleRow(float[] a, int row, float s) {
        for (int c = 0; c < 4; c++) {
            a[c * 4 + row] *= s;
        }
    }

    private static void addScaledRow(float[] a, int srcRow, int dstRow, float factor) {
        for (int c = 0; c < 4; c++) {
            a[c * 4 + dstRow] += factor * a[c * 4 + srcRow];
        }
    }
}

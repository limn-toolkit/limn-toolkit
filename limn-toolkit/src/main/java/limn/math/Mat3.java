package limn.math;

/**
 * An immutable column-major 3×3 matrix ({@code m[col*3 + row]}). Mainly the
 * normal matrix (inverse-transpose of a model's upper-left 3×3) and the rotation
 * part of orientations.
 */
public final class Mat3 {

    private final float[] m; // column-major, length 9

    Mat3(float[] m) {
        this.m = m;
    }

    /** The identity matrix. */
    public static Mat3 identity() {
        float[] r = new float[9];
        r[0] = r[4] = r[8] = 1;
        return new Mat3(r);
    }

    /** Element at (row, col). */
    public float get(int row, int col) {
        return m[col * 3 + row];
    }

    /** The transpose. */
    public Mat3 transpose() {
        float[] r = new float[9];
        for (int c = 0; c < 3; c++) {
            for (int row = 0; row < 3; row++) {
                r[row * 3 + c] = m[c * 3 + row];
            }
        }
        return new Mat3(r);
    }

    /** The determinant; zero means the matrix is singular and has no inverse. */
    public float determinant() {
        return m[0] * (m[4] * m[8] - m[7] * m[5])
                - m[3] * (m[1] * m[8] - m[7] * m[2])
                + m[6] * (m[1] * m[5] - m[4] * m[2]);
    }

    /** @throws ArithmeticException if the matrix is singular */
    public Mat3 invert() {
        float det = determinant();
        if (Math.abs(det) < 1e-12f) {
            throw new ArithmeticException("singular Mat3");
        }
        float inv = 1f / det;
        // Adjugate (transpose of the cofactor matrix), then × 1/det.
        float[] r = new float[9];
        r[0] = (m[4] * m[8] - m[7] * m[5]) * inv;
        r[1] = (m[7] * m[2] - m[1] * m[8]) * inv;
        r[2] = (m[1] * m[5] - m[4] * m[2]) * inv;
        r[3] = (m[6] * m[5] - m[3] * m[8]) * inv;
        r[4] = (m[0] * m[8] - m[6] * m[2]) * inv;
        r[5] = (m[3] * m[2] - m[0] * m[5]) * inv;
        r[6] = (m[3] * m[7] - m[6] * m[4]) * inv;
        r[7] = (m[6] * m[1] - m[0] * m[7]) * inv;
        r[8] = (m[0] * m[4] - m[3] * m[1]) * inv;
        return new Mat3(r);
    }

    /** Transforms a direction; no translation exists in a 3x3. */
    public Vec3 transform(Vec3 v) {
        return new Vec3(
                m[0] * v.x() + m[3] * v.y() + m[6] * v.z(),
                m[1] * v.x() + m[4] * v.y() + m[7] * v.z(),
                m[2] * v.x() + m[5] * v.y() + m[8] * v.z());
    }

    /** Column-major copy (length 9), for GPU upload. */
    public float[] toArray() {
        return m.clone();
    }

    /** Column-major copy into {@code out} (length ≥ 9), the allocation-free upload path. */
    public void toArray(float[] out) {
        System.arraycopy(m, 0, out, 0, 9);
    }
}

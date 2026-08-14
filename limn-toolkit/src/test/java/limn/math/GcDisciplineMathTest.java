package limn.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity of the allocation-free math paths ({@link MutMat4}, the {@code *Into}
 * statics, Arvo AABB transform) with the immutable reference operations: the
 * hot-loop variants must be drop-in equal, including the awkward corners
 * (aliasing, non-uniform scale, empty boxes).
 */
class GcDisciplineMathTest {

    private static final float EPS = 1e-4f;

    /** A translate·rotate·scale with nothing symmetric about it. */
    private static Mat4 crooked() {
        return Mat4.translation(new Vec3(1.5f, -2, 3))
                .multiply(Mat4.rotation(Quat.fromAxisAngle(new Vec3(1, 2, 3), 0.7f)))
                .multiply(Mat4.scale(new Vec3(2, 0.5f, -1.5f)));
    }

    private static Mat4 crookedOther() {
        return Mat4.translation(new Vec3(-4, 0.5f, 2))
                .multiply(Mat4.rotation(Quat.fromAxisAngle(new Vec3(-2, 1, 0.5f), -1.2f)))
                .multiply(Mat4.scale(new Vec3(0.25f, 3, 1)));
    }

    private static void assertMatEquals(Mat4 expected, float[] actualColumnMajor) {
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(expected.get(r, c), actualColumnMajor[c * 4 + r], EPS,
                        "(" + r + "," + c + ")");
            }
        }
    }

    private static void assertMatEquals(Mat4 expected, MutMat4 actual) {
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(expected.get(r, c), actual.get(r, c), EPS, "(" + r + "," + c + ")");
            }
        }
    }

    // ------------------------------------------------------------ Mat4 statics

    @Test
    void multiplyIntoMatchesMultiply() {
        Mat4 a = crooked();
        Mat4 b = crookedOther();
        float[] out = new float[16];
        Mat4.multiplyInto(a, b, out);
        assertMatEquals(a.multiply(b), out);
    }

    @Test
    void normalMatrixIntoMatchesNormalMatrixUnderNonUniformScale() {
        // Non-uniform scale is the case where normal ≠ rotation part, the one
        // that catches a transposed cofactor layout.
        Mat4 model = crooked();
        float[] out = new float[9];
        Mat4.normalMatrixInto(model, out);
        Mat3 expected = model.normalMatrix();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(expected.get(r, c), out[c * 3 + r], EPS, "(" + r + "," + c + ")");
            }
        }
    }

    @Test
    void normalMatrixIntoRejectsSingularMatrices() {
        Mat4 flat = Mat4.scale(new Vec3(1, 0, 1));
        assertThrows(ArithmeticException.class, () -> Mat4.normalMatrixInto(flat, new float[9]));
    }

    @Test
    void identityIsShared() {
        assertSame(Mat4.identity(), Mat4.IDENTITY);
        assertEquals(1, Mat4.IDENTITY.get(0, 0), 0f);
        assertEquals(0, Mat4.IDENTITY.get(0, 3), 0f);
    }

    // ---------------------------------------------------------------- MutMat4

    @Test
    void setTrsMatchesMat4Trs() {
        Vec3 t = new Vec3(1, -2, 0.5f);
        Quat q = Quat.fromAxisAngle(new Vec3(0.3f, -1, 2), 2.1f);
        Vec3 s = new Vec3(2, 0.5f, -3);
        assertMatEquals(Mat4.trs(t, q, s), new MutMat4().setTrs(t, q, s));
    }

    @Test
    void mulMatchesMultiplyAndChains() {
        Mat4 a = crooked();
        Mat4 b = crookedOther();
        Mat4 c = Mat4.perspective(1.1f, 1.5f, 0.1f, 100f);
        MutMat4 m = new MutMat4().set(a).mul(b).mul(c);
        assertMatEquals(a.multiply(b).multiply(c), m);
    }

    @Test
    void setMultiplyAcceptsAliasedArguments() {
        Mat4 a = crooked();
        MutMat4 m = new MutMat4().set(a);
        m.setMultiply(m, m); // this = this · this
        assertMatEquals(a.multiply(a), m);

        MutMat4 n = new MutMat4().set(a);
        n.mul(n); // in-place square through the instance path
        assertMatEquals(a.multiply(a), n);
    }

    @Test
    void setIdentityResetsEverything() {
        MutMat4 m = new MutMat4().set(crooked()).setIdentity();
        assertMatEquals(Mat4.identity(), m);
    }

    @Test
    void toMat4SnapshotsAndDetaches() {
        MutMat4 m = new MutMat4().set(crooked());
        Mat4 snapshot = m.toMat4();
        m.setIdentity(); // mutating afterwards must not touch the snapshot
        assertMatEquals(snapshot, new MutMat4().set(crooked()));
    }

    @Test
    void transformPointMatchesMat4IncludingPerspectiveDivide() {
        Mat4 proj = Mat4.perspective(1.0f, 1.6f, 0.1f, 50f);
        MutMat4 m = new MutMat4().set(proj);
        float[] out = new float[3];
        m.transformPoint(0.3f, -0.7f, -5f, out);
        Vec3 expected = proj.transformPoint(new Vec3(0.3f, -0.7f, -5f));
        assertEquals(expected.x(), out[0], EPS);
        assertEquals(expected.y(), out[1], EPS);
        assertEquals(expected.z(), out[2], EPS);
    }

    @Test
    void transformDirectionIgnoresTranslation() {
        MutMat4 m = new MutMat4().set(Mat4.translation(new Vec3(5, 6, 7)));
        float[] out = new float[3];
        m.transformDirection(1, 0, 0, out);
        assertEquals(1, out[0], EPS);
        assertEquals(0, out[1], EPS);
        assertEquals(0, out[2], EPS);
    }

    // ----------------------------------------------------- Aabb.transformedBy

    /** The old corner-based reference: transform all 8 corners, fold into a union. */
    private static Aabb cornerTransformed(Aabb box, Mat4 m) {
        Aabb result = Aabb.EMPTY;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3(
                    (i & 1) == 0 ? box.min().x() : box.max().x(),
                    (i & 2) == 0 ? box.min().y() : box.max().y(),
                    (i & 4) == 0 ? box.min().z() : box.max().z());
            result = result.union(m.transformPoint(corner));
        }
        return result;
    }

    @Test
    void transformedByMatchesCornerMethodForAffineTransforms() {
        Aabb box = new Aabb(new Vec3(-1, -2, 0.5f), new Vec3(3, 0, 4));
        Mat4[] transforms = {Mat4.identity(), crooked(), crookedOther(),
                Mat4.lookAt(new Vec3(2, 3, 4), Vec3.ZERO, Vec3.UNIT_Y)};
        for (Mat4 m : transforms) {
            Aabb expected = cornerTransformed(box, m);
            Aabb actual = box.transformedBy(m);
            assertVecEquals(expected.min(), actual.min());
            assertVecEquals(expected.max(), actual.max());
        }
    }

    @Test
    void transformedByKeepsAnEmptyBoxEmpty() {
        Aabb moved = Aabb.EMPTY.transformedBy(crooked());
        assertTrue(moved.isEmpty());
    }

    // ------------------------------------------------------- Frustum culling

    @Test
    void frustumCullingStillSeparatesInsideFromOutside() {
        Mat4 vp = Mat4.perspective((float) Math.toRadians(60), 1f, 0.1f, 100f)
                .multiply(Mat4.lookAt(new Vec3(0, 0, 5), Vec3.ZERO, Vec3.UNIT_Y));
        Frustum frustum = Frustum.fromViewProjection(vp);
        assertTrue(frustum.intersects(new Aabb(new Vec3(-1, -1, -1), new Vec3(1, 1, 1))));
        assertFalse(frustum.intersects(new Aabb(new Vec3(50, 50, 50), new Vec3(51, 51, 51))));
        // Straddling a plane must be conservative-kept.
        assertTrue(frustum.intersects(new Aabb(new Vec3(-20, -1, -1), new Vec3(20, 1, 1))));
    }

    private static void assertVecEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPS, "x");
        assertEquals(expected.y(), actual.y(), EPS, "y");
        assertEquals(expected.z(), actual.z(), EPS, "z");
    }
}

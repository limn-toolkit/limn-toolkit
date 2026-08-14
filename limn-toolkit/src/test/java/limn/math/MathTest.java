package limn.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Correctness of the linear-algebra core: the part most prone to layout bugs. */
class MathTest {

    private static final float EPS = 1e-4f;

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPS, "x");
        assertEquals(expected.y(), actual.y(), EPS, "y");
        assertEquals(expected.z(), actual.z(), EPS, "z");
    }

    @Test
    void vec3CrossAndDot() {
        assertVec(Vec3.UNIT_Z, Vec3.UNIT_X.cross(Vec3.UNIT_Y));
        assertEquals(0, Vec3.UNIT_X.dot(Vec3.UNIT_Y), EPS);
        assertEquals(1, new Vec3(3, 4, 0).normalize().length(), EPS);
    }

    @Test
    void mat4MultiplyIdentityIsNoOp() {
        Mat4 m = Mat4.translation(new Vec3(1, 2, 3)).multiply(Mat4.scale(new Vec3(2, 3, 4)));
        Mat4 same = m.multiply(Mat4.identity());
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(m.get(r, c), same.get(r, c), EPS);
            }
        }
    }

    @Test
    void mat4TranslationMovesAPoint() {
        assertVec(new Vec3(1, 2, 3), Mat4.translation(new Vec3(1, 2, 3)).transformPoint(Vec3.ZERO));
        // A direction ignores translation.
        assertVec(Vec3.UNIT_X, Mat4.translation(new Vec3(1, 2, 3)).transformDirection(Vec3.UNIT_X));
    }

    @Test
    void mat4InverseRoundTrips() {
        Mat4 m = Mat4.translation(new Vec3(1, 2, 3))
                .multiply(Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_Y, 0.6f)))
                .multiply(Mat4.scale(new Vec3(2, 0.5f, 1.5f)));
        Mat4 product = m.multiply(m.invert());
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(r == c ? 1 : 0, product.get(r, c), EPS, "(" + r + "," + c + ")");
            }
        }
    }

    @Test
    void lookAtPlacesTheEyeAtTheOriginLookingDownMinusZ() {
        Mat4 view = Mat4.lookAt(new Vec3(0, 0, 5), Vec3.ZERO, Vec3.UNIT_Y);
        assertVec(Vec3.ZERO, view.transformPoint(new Vec3(0, 0, 5)));   // eye → origin
        assertVec(new Vec3(0, 0, -5), view.transformPoint(Vec3.ZERO));  // target is in front (−Z)
    }

    @Test
    void perspectiveMapsNearToMinus1AndFarToPlus1() {
        Mat4 p = Mat4.perspective((float) Math.toRadians(90), 1, 1, 10);
        assertEquals(-1, p.transformPoint(new Vec3(0, 0, -1)).z(), EPS);
        assertEquals(1, p.transformPoint(new Vec3(0, 0, -10)).z(), EPS);
    }

    @Test
    void quatRotationMatchesMatrixAndRotatesCorrectly() {
        Quat q = Quat.fromAxisAngle(Vec3.UNIT_Y, (float) Math.toRadians(90));
        // +X rotated +90° about +Y → −Z (right-handed).
        assertVec(new Vec3(0, 0, -1), q.rotate(Vec3.UNIT_X));
        // toMat3 must agree with rotate().
        assertVec(q.rotate(Vec3.UNIT_X), q.toMat3().transform(Vec3.UNIT_X));
    }

    @Test
    void transform3DComposesTrs() {
        Transform3D t = new Transform3D(
                new Vec3(10, 0, 0),
                Quat.fromAxisAngle(Vec3.UNIT_Z, (float) Math.toRadians(90)),
                new Vec3(2, 2, 2));
        // (1,0,0) scaled ×2 → (2,0,0), rotated +90° about Z → (0,2,0), translated → (10,2,0).
        assertVec(new Vec3(10, 2, 0), t.toMatrix().transformPoint(Vec3.UNIT_X));
    }

    @Test
    void normalMatrixOfRotationEqualsTheRotation() {
        Mat4 m = Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_X, 0.7f));
        Vec3 n = new Vec3(0, 1, 0);
        assertVec(m.transformDirection(n), m.normalMatrix().transform(n));
    }

    @Test
    void rayAabbIntersection() {
        Aabb box = Aabb.of(new Vec3(-1, -1, -1), new Vec3(1, 1, 1));
        assertEquals(4, box.intersect(Ray.of(new Vec3(0, 0, 5), new Vec3(0, 0, -1))), EPS);
        assertTrue(Float.isNaN(box.intersect(Ray.of(new Vec3(0, 0, 5), Vec3.UNIT_Y))), "miss");
        assertEquals(0, box.intersect(Ray.of(Vec3.ZERO, Vec3.UNIT_X)), EPS); // origin inside
    }

    @Test
    void rayTransformIntoLocalSpaceKeepsT() {
        Ray world = Ray.of(new Vec3(0, 0, 5), new Vec3(0, 0, -1));
        Ray local = world.transformedBy(Mat4.translation(new Vec3(0, 0, -5)));
        assertVec(Vec3.ZERO, local.origin());
        assertVec(new Vec3(0, 0, -1), local.direction());
    }

    @Test
    void aabbUnionAndTransform() {
        Aabb box = Aabb.EMPTY.union(new Vec3(-1, -1, -1)).union(new Vec3(1, 1, 1));
        assertVec(Vec3.ZERO, box.center());
        Aabb moved = box.transformedBy(Mat4.translation(new Vec3(10, 0, 0)));
        assertVec(new Vec3(10, 0, 0), moved.center());
    }
}

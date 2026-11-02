package limn.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Affine2DTest {

    private static final double EPS = 1e-9;

    private static void assertMaps(Affine2D m, double x, double y, double ex, double ey) {
        Vec2 p = m.transformPoint(new Vec2((float) x, (float) y));
        assertEquals(ex, p.x(), 1e-4, "x of " + x + "," + y + " under " + m);
        assertEquals(ey, p.y(), 1e-4, "y of " + x + "," + y + " under " + m);
    }

    private static void assertSame(Affine2D expected, Affine2D actual) {
        assertEquals(expected.a(), actual.a(), EPS, "a");
        assertEquals(expected.b(), actual.b(), EPS, "b");
        assertEquals(expected.c(), actual.c(), EPS, "c");
        assertEquals(expected.d(), actual.d(), EPS, "d");
        assertEquals(expected.e(), actual.e(), EPS, "e");
        assertEquals(expected.f(), actual.f(), EPS, "f");
    }

    @Test
    void theBuildingMethodsApplyTheirOperationFirstAsACanvasDoes() {
        // A canvas translated by (10, 0) and then turned a quarter: a point drawn at (1, 0) lands at
        // (10, 1), since y grows down and the turn is clockwise.
        Affine2D m = Affine2D.IDENTITY.translate(10, 0).rotate(Math.PI / 2);
        assertMaps(m, 1, 0, 10, 1);
        assertMaps(m, 0, 0, 10, 0);
        // concat is the same composition: the inner map first.
        assertSame(m, Affine2D.translation(10, 0).concat(Affine2D.rotation(Math.PI / 2)));
        assertMaps(Affine2D.scaling(2, 3).translate(1, 1), 1, 1, 4, 6);
    }

    @Test
    void aShearMovesXByYAndYByXAsThePlatformsShearDoes() {
        Affine2D lean = Affine2D.shearing(0.5, 0);
        assertMaps(lean, 0, 10, 5, 10);
        assertMaps(lean, 4, 0, 4, 0);
        assertMaps(Affine2D.shearing(0, 0.25), 8, 0, 8, 2);
        assertSame(lean, Affine2D.IDENTITY.shear(0.5, 0));
    }

    @Test
    void aTurnAboutAPointLeavesThatPointWhereItIs() {
        Affine2D m = Affine2D.rotation(Math.toRadians(37), 50, 20);
        assertMaps(m, 50, 20, 50, 20);
        assertMaps(Affine2D.rotation(Math.PI, 50, 20), 60, 20, 40, 20);
    }

    @Test
    void theInverseUndoesTheMapAndASingularMapHasNone() {
        Affine2D m = Affine2D.translation(12, -7).rotate(0.7).shear(0.3, -0.2).scale(2, 0.5);
        assertSame(Affine2D.IDENTITY, m.concat(m.invert()));
        assertSame(Affine2D.IDENTITY, m.invert().concat(m));
        Vec2 p = m.transformPoint(new Vec2(3, 4));
        Vec2 back = m.invert().transformPoint(p);
        assertEquals(3, back.x(), 1e-4);
        assertEquals(4, back.y(), 1e-4);
        assertThrows(ArithmeticException.class, () -> Affine2D.scaling(1, 0).invert());
        assertThrows(ArithmeticException.class, () -> new Affine2D(1, 2, 2, 4, 0, 0).invert());
    }

    @Test
    void aTurnByNothingIsTheIdentityNegativeZerosIncluded() {
        assertEquals(Affine2D.IDENTITY, Affine2D.rotation(0));
        assertTrue(Affine2D.rotation(0).isIdentity());
        assertEquals(Affine2D.IDENTITY.hashCode(), new Affine2D(1, -0.0, -0.0, 1, -0.0, 0).hashCode());
        assertFalse(Affine2D.translation(1, 0).isIdentity());
    }

    @Test
    void axisAlignedMeansNoTurnAndNoShear() {
        assertTrue(Affine2D.scaling(-1, 2).translate(3, 4).isAxisAligned());
        assertFalse(Affine2D.rotation(0.1).isAxisAligned());
        assertFalse(Affine2D.shearing(0.1, 0).isAxisAligned());
        assertEquals(-2, Affine2D.scaling(-1, 2).determinant(), EPS);
    }

    @Test
    void aDirectionIgnoresTheTranslation() {
        Vec2 v = Affine2D.translation(100, 100).scale(2, 2).transformDirection(new Vec2(1, 1));
        assertEquals(2, v.x(), 1e-6);
        assertEquals(2, v.y(), 1e-6);
    }

    @Test
    void theSameMapInFourByFourMapsThePlaneAlike() {
        Affine2D m = Affine2D.translation(5, 6).rotate(0.4).shear(0.2, 0);
        Vec3 p = m.toMat4().transformPoint(new Vec3(3, -2, 0));
        Vec2 q = m.transformPoint(new Vec2(3, -2));
        assertEquals(q.x(), p.x(), 1e-4);
        assertEquals(q.y(), p.y(), 1e-4);
    }

    @Test
    void aMatrixThatIsNotFiniteIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Affine2D(Double.NaN, 0, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Affine2D.translation(Double.POSITIVE_INFINITY, 0));
    }
}

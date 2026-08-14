package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Transform2DTest {

    private static final float EPS = 1e-4f;

    @Test
    void identityMapsPointsToThemselves() {
        Transform2D t = new Transform2D();
        assertEquals(3, t.x(3, 7), EPS);
        assertEquals(7, t.y(3, 7), EPS);
        assertTrue(t.isAxisAligned());
    }

    @Test
    void contentScaleThenTranslate() {
        Transform2D t = new Transform2D();
        t.setScale(2f);
        t.translate(10, 5);
        // translate is in user units: device offset is scaled.
        assertEquals(2 * (10 + 1), t.x(1, 1), EPS);
        assertEquals(2 * (5 + 1), t.y(1, 1), EPS);
        assertEquals(2f, t.approxScale(), EPS);
    }

    @Test
    void rotationIsClockwiseInYDownSpace() {
        Transform2D t = new Transform2D();
        t.rotate((float) Math.toRadians(90));
        // +x axis rotates to +y (down): clockwise on screen.
        assertEquals(0, t.x(1, 0), EPS);
        assertEquals(1, t.y(1, 0), EPS);
        assertFalse(t.isAxisAligned());
    }

    @Test
    void rotationComposesWithScaleForApproxScale() {
        Transform2D t = new Transform2D();
        t.setScale(2f);
        t.rotate(0.3f);
        t.scale(1.5f, 1.5f);
        assertEquals(3f, t.approxScale(), 1e-3);
    }

    @Test
    void fullTurnReturnsToAxisAligned() {
        Transform2D t = new Transform2D();
        t.setScale(1.25f);
        for (int i = 0; i < 4; i++) {
            t.rotate((float) Math.toRadians(90));
        }
        // Numerically ~identity rotation; axis alignment uses an epsilon.
        assertEquals(1.25f, t.x(1, 0), 1e-3);
        assertEquals(1.25f, t.y(0, 1), 1e-3);
    }

    @Test
    void negativeScaleIsNotSnappable() {
        Transform2D t = new Transform2D();
        t.scale(-1, 1);
        assertFalse(t.isAxisAligned());
    }

    @Test
    void copyFromReplicatesEverything() {
        Transform2D a = new Transform2D();
        a.setScale(1.5f);
        a.translate(3, 4);
        a.rotate(0.2f);
        Transform2D b = new Transform2D();
        b.copyFrom(a);
        assertEquals(a.x(7, 9), b.x(7, 9), EPS);
        assertEquals(a.y(7, 9), b.y(7, 9), EPS);
    }
}

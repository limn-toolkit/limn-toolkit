package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link Color}, {@link Rect} and {@link RoundRect}. */
class GeometryTest {

    private static final float EPS = 1e-5f;

    @Test
    void colorFromHexAndAlpha() {
        Color c = Color.rgb(0x4C8DFF);
        assertEquals(0x4C / 255f, c.r(), EPS);
        assertEquals(0x8D / 255f, c.g(), EPS);
        assertEquals(0xFF / 255f, c.b(), EPS);
        assertEquals(1f, c.a(), EPS);
        assertEquals(0.5f, Color.rgba(0x000000, 0.5f).a(), EPS);
        assertEquals(0.25f, c.withAlpha(0.25f).a(), EPS);
    }

    @Test
    void colorChannelsAreClamped() {
        Color c = new Color(2f, -1f, 0.5f, 3f);
        assertEquals(1f, c.r(), EPS);
        assertEquals(0f, c.g(), EPS);
        assertEquals(0.5f, c.b(), EPS);
        assertEquals(1f, c.a(), EPS);
    }

    @Test
    void colorLerpInterpolatesEveryChannel() {
        Color mid = Color.BLACK.lerp(Color.WHITE, 0.5f);
        assertEquals(0.5f, mid.r(), EPS);
        assertEquals(0.5f, mid.g(), EPS);
        assertEquals(0.5f, mid.b(), EPS);
    }

    @Test
    void rectIntersectionAndContainment() {
        Rect a = new Rect(0, 0, 100, 50);
        Rect b = new Rect(60, 20, 100, 100);
        Rect i = a.intersect(b);
        assertEquals(60, i.x(), EPS);
        assertEquals(20, i.y(), EPS);
        assertEquals(40, i.width(), EPS);
        assertEquals(30, i.height(), EPS);

        Rect disjoint = a.intersect(new Rect(500, 500, 10, 10));
        assertEquals(0, disjoint.width(), EPS);
        assertEquals(0, disjoint.height(), EPS);

        assertTrue(a.contains(0, 0));
        assertTrue(a.contains(99.9f, 49.9f));
        assertFalse(a.contains(100, 25));
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, -1, 5));
    }

    @Test
    void rectUnionIsSmallestEnclosingRect() {
        Rect a = new Rect(10, 10, 20, 20);
        Rect b = new Rect(50, 5, 10, 10);
        Rect u = a.union(b);
        assertEquals(10, u.x(), EPS);
        assertEquals(5, u.y(), EPS);
        assertEquals(50, u.width(), EPS);
        assertEquals(25, u.height(), EPS);

        Rect same = a.union(a);
        assertEquals(a, same);
    }

    @Test
    void roundRectNormalizationAppliesCssScalingRule() {
        // Radii sum to 200 on a 100-wide side: everything halves.
        RoundRect rr = new RoundRect(0, 0, 100, 300, 120, 80, 0, 0).normalized();
        assertEquals(60, rr.topLeft(), EPS);
        assertEquals(40, rr.topRight(), EPS);

        RoundRect untouched = RoundRect.of(0, 0, 100, 100, 20);
        assertSame(untouched, untouched.normalized());
    }

    @Test
    void roundRectNegativeRadiiClampToZero() {
        RoundRect rr = new RoundRect(0, 0, 10, 10, -5, 1, 1, 1);
        assertEquals(0, rr.topLeft(), EPS);
    }
}

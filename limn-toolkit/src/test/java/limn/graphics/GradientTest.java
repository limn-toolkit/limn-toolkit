package limn.graphics;

import limn.math.Affine2D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a {@link Gradient} and an {@link ImagePaint} take and refuse, and when two are equal. */
class GradientTest {

    private static final Color RED = Color.rgb(0xFF0000);
    private static final Color BLUE = Color.rgb(0x0000FF);

    @Test
    void colorsWithoutPositionsStandEvenlySpaced() {
        assertArrayEquals(new float[] {0, 0.5f, 1}, Gradient.linear(0, 0, 1, 0, RED, BLUE, RED).positions());
        assertArrayEquals(new float[] {0}, Gradient.radial(0, 0, 1, RED).positions(), "one colour stands at 0");
    }

    @Test
    void positionsOutOfOrderOrOfRangeOrMiscountedAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Gradient.linear(0, 0, 1, 0, new float[] {0.5f, 0.2f}, RED, BLUE));
        assertThrows(IllegalArgumentException.class, () -> Gradient.linear(0, 0, 1, 0, new float[] {0, 1.5f}, RED, BLUE));
        assertThrows(IllegalArgumentException.class, () -> Gradient.linear(0, 0, 1, 0, new float[] {0}, RED, BLUE));
        assertThrows(IllegalArgumentException.class, () -> Gradient.linear(0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Gradient.linear(3, 3, 3, 3, RED, BLUE), "one point");
        assertThrows(IllegalArgumentException.class, () -> Gradient.radial(0, 0, 0, RED, BLUE), "no radius");
        assertThrows(IllegalArgumentException.class,
                () -> Gradient.linear(0, 0, 1, 0, new Color[Gradient.MAX_STOPS + 1]), "too many");
        assertThrows(ArithmeticException.class,
                () -> Gradient.linear(0, 0, 1, 0, RED, BLUE).withTransform(Affine2D.scaling(0, 1)), "no inverse");
        assertThrows(IllegalStateException.class, () -> Gradient.linear(0, 0, 1, 0, RED, BLUE).withFocus(0, 0));
    }

    @Test
    void aFocusPastTheCircleIsMovedJustInsideIt() {
        float[] q = Gradient.radial(10, 10, 20, RED, BLUE).withFocus(110, 10).geometry();
        assertEquals(10 + 20 * 0.99f, q[3], 1e-3, "on the line from the centre, a hundredth inside");
        assertEquals(10, q[4], 1e-6);
        float[] inside = Gradient.radial(10, 10, 20, RED, BLUE).withFocus(15, 12).geometry();
        assertEquals(15, inside[3]);
        assertEquals(12, inside[4]);
    }

    @Test
    void equalWhenEveryPartIs() {
        Gradient a = Gradient.sweep(5, 5, 1, RED, BLUE).withSpread(Gradient.Spread.REFLECT);
        assertEquals(a, Gradient.sweep(5, 5, 1, RED, BLUE).withSpread(Gradient.Spread.REFLECT));
        assertEquals(a.hashCode(), Gradient.sweep(5, 5, 1, RED, BLUE).withSpread(Gradient.Spread.REFLECT).hashCode());
        assertNotEquals(a, Gradient.sweep(5, 5, 1, RED, BLUE));
        assertNotEquals(a, a.withTransform(Affine2D.translation(1, 0)));
        assertTrue(Gradient.linear(0, 0, 1, 0, RED, BLUE).isOpaque());
        assertTrue(!Gradient.linear(0, 0, 1, 0, RED, Color.TRANSPARENT).isOpaque());
    }

    @Test
    void anImagePaintRepeatsByDefaultAndRefusesASingularMatrix() {
        Image image = new Image(1, 1, new byte[4]);
        ImagePaint p = ImagePaint.of(image);
        assertEquals(ImagePaint.Tile.REPEAT, p.tileX());
        assertEquals(Sampling.SMOOTH, p.sampling());
        assertThrows(ArithmeticException.class, () -> p.withTransform(Affine2D.scaling(1, 0)));
    }
}

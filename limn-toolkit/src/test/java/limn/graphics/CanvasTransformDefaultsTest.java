package limn.graphics;

import limn.math.Affine2D;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The transform defaults of {@link Canvas}, for an application's canvas that has only
 * {@code translate}, {@code scale} and {@code rotate}: a concatenation, a shear and a turn about a
 * point are composed from them exactly, mirrors and singular matrices included, and the getter
 * answers that the canvas does not track its matrix.
 */
class CanvasTransformDefaultsTest {

    private static final double EPS = 1e-4;

    /** Keeps the affine map its three calls make, as an application's canvas would. */
    private static final class ThreeCallCanvas extends NoopCanvas {
        Affine2D m = Affine2D.IDENTITY;

        ThreeCallCanvas() {
            super(400, 300);
        }

        @Override
        public void translate(float dx, float dy) {
            m = m.translate(dx, dy);
        }

        @Override
        public void scale(float sx, float sy) {
            m = m.scale(sx, sy);
        }

        @Override
        public void rotate(float radians) {
            m = m.rotate(radians);
        }
    }

    private static void assertSame(Affine2D expected, Affine2D actual) {
        assertEquals(expected.a(), actual.a(), EPS, "a of " + actual);
        assertEquals(expected.b(), actual.b(), EPS, "b of " + actual);
        assertEquals(expected.c(), actual.c(), EPS, "c of " + actual);
        assertEquals(expected.d(), actual.d(), EPS, "d of " + actual);
        assertEquals(expected.e(), actual.e(), EPS, "e of " + actual);
        assertEquals(expected.f(), actual.f(), EPS, "f of " + actual);
    }

    @Test
    void aConcatenationIsComposedExactlyFromTheThreeCalls() {
        Affine2D[] matrices = {
                new Affine2D(1, 0.2, -0.7, 1.5, 12, -3),
                Affine2D.shearing(0.6, 0),
                Affine2D.shearing(0, -0.3),
                Affine2D.scaling(-1, 1).translate(5, 0),     // a mirror
                new Affine2D(2, 1, 4, 2, 1, 1),               // singular: onto a line
                Affine2D.rotation(2.5).scale(3, 0.5),
        };
        for (Affine2D matrix : matrices) {
            ThreeCallCanvas canvas = new ThreeCallCanvas();
            canvas.translate(7, 9);
            canvas.concat(matrix);
            assertSame(Affine2D.translation(7, 9).concat(matrix), canvas.m);
        }
    }

    @Test
    void aShearAndATurnAboutAPointGoThroughTheSameDefaults() {
        ThreeCallCanvas canvas = new ThreeCallCanvas();
        canvas.shear(0.5f, 0);
        assertSame(Affine2D.shearing(0.5, 0), canvas.m);
        ThreeCallCanvas turned = new ThreeCallCanvas();
        turned.rotate(0.9f, 40, 30);
        assertSame(Affine2D.rotation(0.9f, 40, 30), turned.m);
    }

    @Test
    void aCanvasThatDoesNotTrackItsMatrixAnswersNull() {
        assertNull(new NoopCanvas(10, 10).transform());
    }
}

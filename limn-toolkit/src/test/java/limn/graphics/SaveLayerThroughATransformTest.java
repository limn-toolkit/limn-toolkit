package limn.graphics;

import limn.math.Mat4;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Canvas#saveLayer(float, float, float, float, float, Mat4)}'s default, for a canvas that
 * cannot make layers: the matrix's flat part applied straight, by the three calls every canvas
 * has, so that the commands inside land where the picture would lay them. A skew, which no single
 * call expresses, comes out of two rotations about a scale.
 */
class SaveLayerThroughATransformTest {

    private static final float EPS = 1e-4f;

    /** Keeps the affine its translations, rotations and scales make: x' = a x + c y + e, y' = b x + d y + f. */
    private static final class AffineCanvas extends NoopCanvas {
        double a = 1;
        double b;
        double c;
        double d = 1;
        double e;
        double f;
        float opacity = 1;
        final List<double[]> saved = new ArrayList<>();

        AffineCanvas() {
            super(400, 300);
        }

        @Override
        public void save() {
            saved.add(new double[] {a, b, c, d, e, f, opacity});
            super.save();
        }

        @Override
        public void restore() {
            double[] s = saved.remove(saved.size() - 1);
            a = s[0];
            b = s[1];
            c = s[2];
            d = s[3];
            e = s[4];
            f = s[5];
            opacity = (float) s[6];
            super.restore();
        }

        @Override
        public void translate(float dx, float dy) {
            e += a * dx + c * dy;
            f += b * dx + d * dy;
        }

        @Override
        public void scale(float sx, float sy) {
            a *= sx;
            b *= sx;
            c *= sy;
            d *= sy;
        }

        @Override
        public void rotate(float radians) {
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double na = a * cos + c * sin;
            double nb = b * cos + d * sin;
            double nc = -a * sin + c * cos;
            double nd = -b * sin + d * cos;
            a = na;
            b = nb;
            c = nc;
            d = nd;
        }

        @Override
        public void setOpacity(float value) {
            opacity *= value;
        }

        @Override
        public float opacity() {
            return opacity;
        }
    }

    private static void assertApplied(Mat4 m) {
        AffineCanvas canvas = new AffineCanvas();
        canvas.translate(5, 7); // something already in force, which the layer's comes after
        canvas.saveLayer(0, 0, 10, 10, 0.5f, m);
        double w = m.get(3, 3);
        assertEquals(m.get(0, 0) / w, canvas.a, EPS, "a of " + m);
        assertEquals(m.get(1, 0) / w, canvas.b, EPS, "b");
        assertEquals(m.get(0, 1) / w, canvas.c, EPS, "c");
        assertEquals(m.get(1, 1) / w, canvas.d, EPS, "d");
        assertEquals(5 + m.get(0, 3) / w, canvas.e, EPS, "e, after the translation in force");
        assertEquals(7 + m.get(1, 3) / w, canvas.f, EPS, "f");
        assertEquals(0.5f, canvas.opacity(), EPS, "the opacity per draw");
        canvas.restore();
        assertEquals(1, canvas.a, EPS, "the restore puts it back");
        assertEquals(5, canvas.e, EPS);
    }

    @Test
    void theFlatPartOfAnyAffineMatrixIsAppliedStraight() {
        double r = Math.toRadians(33);
        float cos = (float) Math.cos(r);
        float sin = (float) Math.sin(r);
        assertApplied(Mat4.affine2D(cos, sin, -sin, cos, 12, -4));                 // a rotation
        assertApplied(Mat4.affine2D(1, 0, 0.6f, 1, 0, 0));                         // a skew along x
        assertApplied(Mat4.affine2D(1, -0.3f, 0, 1, 0, 0));                        // and along y
        assertApplied(Mat4.affine2D(2, 0, 0, 0.5f, 3, 4));                         // a scale on the axes
        assertApplied(Mat4.affine2D(-1, 0, 0, 1, 40, 0));                          // a mirror
        assertApplied(Mat4.affine2D(1.7f * cos, 0.4f * sin, -0.9f * sin, 1.2f * cos, 0, 0)); // all of it
        assertApplied(Mat4.affine2D(0, 0, 0, 0, 1, 1));                            // collapsed
    }
}

package limn.graphics;

import limn.math.Affine2D;
import limn.testing.NoopCanvas;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stroke overloads' defaults, for a canvas of an application's own: each fills the stroke's
 * outline under non-zero, of the shape the overload names, and draws nothing where there is
 * nothing to draw.
 */
class CanvasStrokeDefaultsTest {

    /** Keeps what is filled, and answers a transform when given one. */
    private static final class Filling extends NoopCanvas {
        final List<Path2D> filled = new ArrayList<>();
        private final @Nullable Affine2D matrix;
        private final float scale;

        Filling(float scale, @Nullable Affine2D matrix) {
            super(100, 100);
            this.scale = scale;
            this.matrix = matrix;
        }

        @Override
        public float contentScale() {
            return scale;
        }

        @Override
        public @Nullable Affine2D transform() {
            return matrix;
        }

        @Override
        public void fillPath(Path2D path, Paint paint) {
            filled.add(path.copy());
        }
    }

    @Test
    void aStrokedPathIsItsOutlineFilledUnderNonZero() {
        Filling canvas = new Filling(1, null);
        Path2D path = new Path2D().moveTo(10, 10).lineTo(50, 10).lineTo(50, 50);
        Stroke stroke = Stroke.of(4).withDash(6, 3);
        canvas.drawPath(path, stroke, Color.BLACK);
        assertEquals(1, canvas.filled.size());
        Path2D outline = canvas.filled.get(0);
        assertEquals(FillRule.NON_ZERO, outline.fillRule());
        assertTrue(outline.contains(12, 10), "in the first dash");
        assertFalse(outline.contains(17.5f, 10), "in the first gap");
        assertFalse(outline.contains(30, 30), "inside the corner, off the line");
    }

    @Test
    void eachShapeIsStrokedAlongItsOwnOutline() {
        Filling canvas = new Filling(1, null);
        Stroke stroke = Stroke.of(2);
        canvas.drawLine(0, 50, 100, 50, stroke, Color.BLACK);
        canvas.drawRect(10, 10, 80, 80, stroke, Color.BLACK);
        canvas.drawRoundRect(RoundRect.of(10, 10, 80, 80, 20), stroke, Color.BLACK);
        canvas.drawCircle(50, 50, 30, stroke, Color.BLACK);
        canvas.drawEllipse(50, 50, 40, 20, stroke, Color.BLACK);
        assertEquals(5, canvas.filled.size());
        assertTrue(canvas.filled.get(0).contains(50, 50.5f) && !canvas.filled.get(0).contains(50, 52));
        assertTrue(canvas.filled.get(1).contains(10, 50) && !canvas.filled.get(1).contains(50, 50), "a frame");
        assertFalse(canvas.filled.get(2).contains(11, 11), "the rounded corner cuts the square one");
        assertTrue(canvas.filled.get(3).contains(80, 50) && !canvas.filled.get(3).contains(50, 50), "a ring");
        assertTrue(canvas.filled.get(4).contains(90, 50) && canvas.filled.get(4).contains(50, 30));
    }

    @Test
    void nothingIsDrawnForNoWidthNoPathOrNoSize() {
        Filling canvas = new Filling(1, null);
        canvas.drawPath(new Path2D(), Stroke.of(2), Color.BLACK);
        canvas.drawPath(new Path2D().moveTo(0, 0).lineTo(10, 10), Stroke.of(0), Color.BLACK);
        canvas.drawRect(0, 0, 0, 10, Stroke.of(2), Color.BLACK);
        canvas.drawCircle(5, 5, 0, Stroke.of(2), Color.BLACK);
        canvas.drawEllipse(5, 5, 3, -1, Stroke.of(2), Color.BLACK);
        assertTrue(canvas.filled.isEmpty());
    }

    @Test
    void curvesAreFollowedToAQuarterOfADevicePixel() {
        Path2D circle = new Path2D().addCircle(0, 0, 1000);
        Filling coarse = new Filling(1, null);
        Filling fine = new Filling(2, Affine2D.scaling(4, 4));
        coarse.drawPath(circle, Stroke.of(1), Color.BLACK);
        fine.drawPath(circle, Stroke.of(1), Color.BLACK);
        int few = points(coarse.filled.get(0));
        int many = points(fine.filled.get(0));
        assertTrue(many > few * 1.5, "eight device pixels to the point follow the circle more closely than one: "
                + many + " points against " + few);
    }

    private static int points(Path2D path) {
        int[] n = {0};
        path.flatten(1e6f, new Path2D.Flattened() {
            @Override
            public void moveTo(float x, float y) {
                n[0]++;
            }

            @Override
            public void lineTo(float x, float y) {
                n[0]++;
            }

            @Override
            public void closePath() {
            }
        });
        return n[0];
    }
}

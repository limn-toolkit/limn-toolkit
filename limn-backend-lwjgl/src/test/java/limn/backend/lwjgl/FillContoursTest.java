package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which paths the OpenGL canvas may fill one contour at a time, by the ear clipper: only those
 * whose contours are simple polygons with boxes apart. Everything else goes to the stencil.
 */
class FillContoursTest {

    private final FillContours test = new FillContours();

    /** Contours given as x, y pairs. */
    private boolean separable(float[]... contours) {
        int total = 0;
        for (float[] c : contours) {
            total += c.length / 2;
        }
        float[] xs = new float[total];
        float[] ys = new float[total];
        int[] starts = new int[contours.length];
        int[] counts = new int[contours.length];
        int at = 0;
        for (int c = 0; c < contours.length; c++) {
            starts[c] = at;
            counts[c] = contours[c].length / 2;
            for (int i = 0; i < contours[c].length; i += 2) {
                xs[at] = contours[c][i];
                ys[at] = contours[c][i + 1];
                at++;
            }
        }
        return test.separable(xs, ys, starts, counts, contours.length);
    }

    private static float[] square(float x, float y, float size) {
        return new float[] {x, y, x + size, y, x + size, y + size, x, y + size};
    }

    @Test
    void aConvexOrConcaveSimplePolygonFillsOnItsOwn() {
        assertTrue(separable(square(0, 0, 10)));
        assertTrue(separable(new float[] {0, 0, 10, 0, 10, 4, 4, 4, 4, 10, 0, 10}), "an L");
        assertTrue(separable(new float[] {10, 0, 0, 0, 0, 10, 10, 10}), "either way round");
    }

    @Test
    void anOutlineCrossingOrTouchingItselfTakesTheStencil() {
        assertFalse(separable(new float[] {0, 0, 10, 10, 10, 0, 0, 10}), "a bow tie");
        assertFalse(separable(new float[] {0, 0, 10, 0, 10, 10, 5, 0, 0, 10}), "a vertex on another edge");
        assertFalse(separable(new float[] {0, 0, 10, 0, 10, 10, 10, 5, 10, 10, 0, 10}),
                "an edge doubling back along the one before");
    }

    @Test
    void contoursWhoseBoxesMeetTakeTheStencil() {
        assertFalse(separable(square(0, 0, 10), square(3, 3, 4)), "a hole, or a shape over another");
        assertFalse(separable(square(0, 0, 10), square(10, 0, 10)), "two squares sharing an edge");
        assertTrue(separable(square(0, 0, 10), square(20, 0, 10)), "two squares apart");
    }

    @Test
    void repeatedPointsAndContoursOfNoAreaAreLeftOut() {
        assertTrue(separable(new float[] {0, 0, 10, 0, 10, 0, 10, 10, 0, 10, 0, 0}),
                "a point repeated, and the start repeated at the end");
        assertTrue(separable(square(0, 0, 10), new float[] {3, 3, 4, 4}),
                "a contour of two points, which neither route fills");
    }

    @Test
    void aLongSimpleContourIsSeparableAndOneCrossingInItIsFound() {
        int n = 4000;
        float[] circle = new float[n * 2];
        for (int i = 0; i < n; i++) {
            circle[i * 2] = (float) (500 * Math.cos(2 * Math.PI * i / n));
            circle[i * 2 + 1] = (float) (500 * Math.sin(2 * Math.PI * i / n));
        }
        assertTrue(separable(circle));
        // A line chart's area: a zigzag along the top, back along a baseline it never reaches.
        float[] area = new float[(n + 2) * 2];
        for (int i = 0; i < n; i++) {
            area[i * 2] = i;
            area[i * 2 + 1] = 50 + (i % 2) * 40;
        }
        area[n * 2] = n - 1;
        area[n * 2 + 1] = 100;
        area[n * 2 + 2] = 0;
        area[n * 2 + 3] = 100;
        assertTrue(separable(area), "a zigzag above its baseline");
        area[n * 2 + 1] = 70;
        area[n * 2 + 3] = 70;
        assertFalse(separable(area), "the same zigzag across its baseline");
    }

    @Test
    void segmentsMeetAtACrossingAnEndOrAlongACommonLine() {
        assertTrue(FillContours.meet(0, 0, 10, 10, 0, 10, 10, 0), "crossing");
        assertTrue(FillContours.meet(0, 0, 10, 0, 10, 0, 10, 10), "sharing an end");
        assertTrue(FillContours.meet(0, 0, 10, 0, 5, 0, 15, 0), "overlapping along one line");
        assertFalse(FillContours.meet(0, 0, 10, 0, 11, 0, 15, 0), "on one line, apart");
        assertFalse(FillContours.meet(0, 0, 10, 0, 0, 1, 10, 1), "parallel");
        assertFalse(FillContours.meet(0, 0, 10, 10, 6, 5, 10, 5), "an end short of the other");
    }
}

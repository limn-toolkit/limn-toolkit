package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolygonTriangulatorTest {

    @Test
    void squareYieldsTwoTrianglesWithFullArea() {
        float[] xs = {0, 10, 10, 0};
        float[] ys = {0, 0, 10, 10};
        int[] tris = PolygonTriangulator.triangulate(xs, ys, 0, 4);
        assertEquals(6, tris.length);
        assertEquals(100, totalArea(xs, ys, tris), 1e-3);
    }

    @Test
    void counterClockwiseWindingIsHandled() {
        float[] xs = {0, 0, 10, 10};
        float[] ys = {0, 10, 10, 0};
        int[] tris = PolygonTriangulator.triangulate(xs, ys, 0, 4);
        assertEquals(100, totalArea(xs, ys, tris), 1e-3);
    }

    @Test
    void concaveLShapePreservesArea() {
        // L-shape: 10x10 square minus its 5x5 top-right quadrant = 75.
        float[] xs = {0, 5, 5, 10, 10, 0};
        float[] ys = {0, 0, 5, 5, 10, 10};
        int[] tris = PolygonTriangulator.triangulate(xs, ys, 0, 6);
        assertEquals(4 * 3, tris.length);
        assertEquals(75, totalArea(xs, ys, tris), 1e-3);
    }

    @Test
    void concaveStarPreservesArea() {
        int points = 5;
        float outer = 50;
        float inner = 20;
        float[] xs = new float[points * 2];
        float[] ys = new float[points * 2];
        for (int i = 0; i < points * 2; i++) {
            double angle = Math.PI * i / points - Math.PI / 2;
            float r = (i % 2 == 0) ? outer : inner;
            xs[i] = (float) (Math.cos(angle) * r);
            ys[i] = (float) (Math.sin(angle) * r);
        }
        int[] tris = PolygonTriangulator.triangulate(xs, ys, 0, points * 2);
        assertEquals((points * 2 - 2) * 3, tris.length);

        float expected = Math.abs(PolygonTriangulator.signedArea(xs, ys, 0, points * 2));
        assertEquals(expected, totalArea(xs, ys, tris), expected * 1e-4);
    }

    @Test
    void offsetVariantIndexesRelativeToOffset() {
        float[] xs = {99, 99, 0, 10, 10, 0};
        float[] ys = {99, 99, 0, 0, 10, 10};
        int[] tris = PolygonTriangulator.triangulate(xs, ys, 2, 4);
        assertEquals(6, tris.length);
        for (int index : tris) {
            assertEquals(true, index >= 0 && index < 4, "relative index out of range: " + index);
        }
        float area = 0;
        for (int i = 0; i < tris.length; i += 3) {
            area += triangleArea(xs[2 + tris[i]], ys[2 + tris[i]],
                    xs[2 + tris[i + 1]], ys[2 + tris[i + 1]],
                    xs[2 + tris[i + 2]], ys[2 + tris[i + 2]]);
        }
        assertEquals(100, area, 1e-3);
    }

    @Test
    void duplicatedConsecutiveVertexKeepsFullArea() {
        // Square with one duplicated vertex: the degenerate-ring fallback must
        // clip the CURRENT vertex (emitting its real-area triangle), not a
        // zero-area (p, n, n) sliver. Regression for the review finding.
        float[] xs = {0, 10, 10, 10, 0};
        float[] ys = {0, 0, 10, 10, 10};
        int[] tris = PolygonTriangulator.triangulate(xs, ys, 0, 5);
        assertEquals(100, totalArea(xs, ys, tris), 1e-3);
    }

    @Test
    void degenerateInputsDoNotExplode() {
        assertEquals(0, PolygonTriangulator.triangulate(new float[] {0, 1}, new float[] {0, 1}, 0, 2).length);
        // Collinear ring: must terminate (area 0), not loop forever.
        float[] xs = {0, 5, 10, 5};
        float[] ys = {0, 0, 0, 0};
        int[] tris = PolygonTriangulator.triangulate(xs, ys, 0, 4);
        assertEquals(0, totalArea(xs, ys, tris), 1e-3);
    }

    private static float totalArea(float[] xs, float[] ys, int[] tris) {
        float area = 0;
        for (int i = 0; i < tris.length; i += 3) {
            area += triangleArea(xs[tris[i]], ys[tris[i]],
                    xs[tris[i + 1]], ys[tris[i + 1]],
                    xs[tris[i + 2]], ys[tris[i + 2]]);
        }
        return area;
    }

    private static float triangleArea(float ax, float ay, float bx, float by, float cx, float cy) {
        return Math.abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) / 2f;
    }
}

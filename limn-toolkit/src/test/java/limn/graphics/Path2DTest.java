package limn.graphics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Path2DTest {

    /** Records the flattened stream for assertions. */
    private static final class Recorder implements Path2D.Flattened {
        final List<String> ops = new ArrayList<>();
        final List<float[]> points = new ArrayList<>();

        @Override
        public void moveTo(float x, float y) {
            ops.add("M");
            points.add(new float[] {x, y});
        }

        @Override
        public void lineTo(float x, float y) {
            ops.add("L");
            points.add(new float[] {x, y});
        }

        @Override
        public void closePath() {
            ops.add("Z");
        }
    }

    @Test
    void linesPassThroughUnchanged() {
        Recorder out = new Recorder();
        new Path2D().moveTo(0, 0).lineTo(10, 0).lineTo(10, 10).close().flatten(0.1f, out);
        assertEquals(List.of("M", "L", "L", "Z"), out.ops);
        assertEquals(10, out.points.get(2)[0], 1e-6);
        assertEquals(10, out.points.get(2)[1], 1e-6);
    }

    @Test
    void quadraticIsFlattenedWithinTolerance() {
        float tol = 0.1f;
        Recorder out = new Recorder();
        new Path2D().moveTo(0, 0).quadTo(50, 100, 100, 0).flatten(tol, out);

        assertTrue(out.points.size() > 4, "curve must be subdivided, got " + out.points.size());
        // Every flattened point must lie near the true curve.
        for (float[] p : out.points) {
            assertTrue(distanceToQuad(p[0], p[1], 0, 0, 50, 100, 100, 0) <= tol * 4,
                    "point too far from curve: " + p[0] + "," + p[1]);
        }
        float[] last = out.points.get(out.points.size() - 1);
        assertEquals(100, last[0], 1e-4);
        assertEquals(0, last[1], 1e-4);
    }

    @Test
    void cubicIsFlattenedWithinToleranceAndReachesEndpoint() {
        Recorder out = new Recorder();
        new Path2D().moveTo(0, 0).cubicTo(0, 80, 100, 80, 100, 0).flatten(0.05f, out);
        assertTrue(out.points.size() > 6);
        float[] last = out.points.get(out.points.size() - 1);
        assertEquals(100, last[0], 1e-4);
        assertEquals(0, last[1], 1e-4);
    }

    @Test
    void tighterToleranceProducesMoreSegments() {
        Recorder coarse = new Recorder();
        Recorder fine = new Recorder();
        new Path2D().moveTo(0, 0).cubicTo(0, 80, 100, 80, 100, 0).flatten(2f, coarse);
        new Path2D().moveTo(0, 0).cubicTo(0, 80, 100, 80, 100, 0).flatten(0.02f, fine);
        assertTrue(fine.points.size() > coarse.points.size());
    }

    @Test
    void multipleSubpathsReplayInOrder() {
        Recorder out = new Recorder();
        new Path2D().moveTo(0, 0).lineTo(1, 0).close().moveTo(5, 5).lineTo(6, 5).flatten(0.1f, out);
        assertEquals(List.of("M", "L", "Z", "M", "L"), out.ops);
    }

    @Test
    void segmentsBeforeMoveToAreRejected() {
        assertThrows(IllegalStateException.class, () -> new Path2D().lineTo(1, 1));
        assertThrows(IllegalStateException.class, () -> new Path2D().close());
    }

    /** Brute-force distance from a point to a quadratic Bézier (dense sampling). */
    private static float distanceToQuad(float px, float py,
                                        float x0, float y0, float cx, float cy, float x1, float y1) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i <= 400; i++) {
            float t = i / 400f;
            float mt = 1 - t;
            float x = mt * mt * x0 + 2 * mt * t * cx + t * t * x1;
            float y = mt * mt * y0 + 2 * mt * t * cy + t * t * y1;
            best = Math.min(best, (float) Math.hypot(px - x, py - y));
        }
        return best;
    }
}

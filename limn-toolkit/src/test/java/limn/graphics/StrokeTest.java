package limn.graphics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stroke's outline against what a stroke covers by definition. With round ends and corners that
 * is every point within half the width of the path, which a distance answers exactly, so random
 * polylines with sharp turns, reversals and segments shorter than the width are held against it
 * point by point; with butt ends and bevelled or mitred corners, it is the segments' rectangles and
 * the corners' wedges.
 */
class StrokeTest {

    /** How close to the true edge a sample may be and not be judged: the outline follows arcs. */
    private static final float MARGIN = 0.03f;
    private static final float TOLERANCE = 0.005f;

    // ------------------------------------------------------------------ the value

    @Test
    void ofIsSvgsDefaultsAndEachWithChangesOneThing() {
        Stroke s = Stroke.of(2);
        assertEquals(2, s.width());
        assertEquals(Stroke.Cap.BUTT, s.cap());
        assertEquals(Stroke.Join.MITER, s.join());
        assertEquals(4, s.miterLimit());
        assertFalse(s.isDashed());
        Stroke round = s.withCap(Stroke.Cap.ROUND).withJoin(Stroke.Join.ROUND);
        assertEquals(Stroke.Cap.ROUND, round.cap());
        assertEquals(2, round.width());
        assertNotEquals(s, round);
        assertEquals(round, Stroke.of(2).withJoin(Stroke.Join.ROUND).withCap(Stroke.Cap.ROUND));
        assertEquals(round.hashCode(), Stroke.of(2).withJoin(Stroke.Join.ROUND).withCap(Stroke.Cap.ROUND).hashCode());
    }

    @Test
    void anOddPatternIsRepeatedAndThePatternIsCopied() {
        Stroke s = Stroke.of(1).withDash(4);
        assertEquals(2, s.dashes().length);
        assertEquals(4, s.dashes()[1]);
        float[] given = {3, 1, 2};
        Stroke odd = Stroke.of(1).withDash(given);
        given[0] = 99;
        assertEquals(3, odd.dashes()[0]);
        assertEquals(6, odd.dashes().length);
        odd.dashes()[0] = 77;
        assertEquals(3, odd.dashes()[0]);
        assertFalse(odd.withDash().isDashed());
    }

    @Test
    void valuesThatDrawNothingSensibleAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Stroke.of(-1));
        assertThrows(IllegalArgumentException.class, () -> Stroke.of(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> Stroke.of(1).withMiterLimit(0.5f));
        assertThrows(IllegalArgumentException.class, () -> Stroke.of(1).withDash(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Stroke.of(1).withDash(2, -1));
        assertThrows(IllegalArgumentException.class, () -> Stroke.of(1).withDashPhase(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Stroke.of(1).outline(new Path2D(), 0));
    }

    // ------------------------------------------------------------------ ends

    @Test
    void theThreeEndsReachAsFarAsTheyShould() {
        Path2D line = new Path2D().moveTo(0, 0).lineTo(10, 0);
        Path2D butt = Stroke.of(4).outline(line, TOLERANCE);
        assertTrue(butt.contains(5, 1.9f));
        assertFalse(butt.contains(5, 2.1f));
        assertFalse(butt.contains(-0.1f, 0));
        assertTrue(butt.contains(0.1f, 0));
        Path2D square = Stroke.of(4).withCap(Stroke.Cap.SQUARE).outline(line, TOLERANCE);
        assertTrue(square.contains(-1.9f, 1.9f));
        assertFalse(square.contains(-2.1f, 0));
        assertTrue(square.contains(11.9f, -1.9f));
        Path2D round = Stroke.of(4).withCap(Stroke.Cap.ROUND).outline(line, TOLERANCE);
        assertTrue(round.contains(-1.9f, 0));
        assertFalse(round.contains(-1.5f, 1.5f), "2.12 from the end: outside the half disc");
        assertTrue(round.contains(11.3f, 1.3f));
        assertEquals(FillRule.NON_ZERO, round.fillRule());
    }

    @Test
    void aSubpathOfOnlyAMoveDrawsNothingAndOneThatGoesNowhereIsADot() {
        Stroke round = Stroke.of(4).withCap(Stroke.Cap.ROUND);
        assertTrue(round.outline(new Path2D().moveTo(5, 5)).isEmpty());
        Path2D dot = round.outline(new Path2D().moveTo(5, 5).lineTo(5, 5), TOLERANCE);
        assertTrue(dot.contains(6.9f, 5));
        assertFalse(dot.contains(6.5f, 6.5f));
        Path2D closedDot = Stroke.of(4).withCap(Stroke.Cap.SQUARE).outline(new Path2D().moveTo(5, 5).close(), TOLERANCE);
        assertTrue(closedDot.contains(6.9f, 6.9f), "a square dot, facing along x");
        assertTrue(Stroke.of(4).outline(new Path2D().moveTo(5, 5).lineTo(5, 5)).isEmpty(), "butt: nothing");
        assertTrue(Stroke.of(0).outline(new Path2D().moveTo(0, 0).lineTo(9, 9)).isEmpty());
    }

    // ------------------------------------------------------------------ corners

    @Test
    void aRightAngleIsMitredRoundedOrBevelled() {
        Path2D corner = new Path2D().moveTo(0, 0).lineTo(10, 0).lineTo(10, 10);
        Path2D miter = Stroke.of(2).outline(corner, TOLERANCE);
        Path2D round = Stroke.of(2).withJoin(Stroke.Join.ROUND).outline(corner, TOLERANCE);
        Path2D bevel = Stroke.of(2).withJoin(Stroke.Join.BEVEL).outline(corner, TOLERANCE);
        // The outer corner's tip is at (11, -1).
        assertTrue(miter.contains(10.9f, -0.9f));
        assertFalse(round.contains(10.9f, -0.9f));
        assertFalse(bevel.contains(10.9f, -0.9f));
        assertTrue(round.contains(10.6f, -0.6f), "0.85 from the corner: inside the arc");
        assertFalse(bevel.contains(10.6f, -0.6f), "past the bevel's edge, x - y = 11");
        assertTrue(bevel.contains(10.4f, -0.4f));
        // The inside of the corner is filled by all three.
        for (Path2D p : List.of(miter, round, bevel)) {
            assertTrue(p.contains(9.5f, 0.5f));
            assertFalse(p.contains(8.5f, 1.5f));
        }
    }

    @Test
    void aMiterPastItsLimitIsCutAsABevel() {
        // A turn back of all but 10 degrees: the miter would reach 11.5 half widths.
        double a = Math.toRadians(170);
        Path2D sharp = new Path2D().moveTo(0, 0).lineTo(20, 0)
                .lineTo((float) (20 + 20 * Math.cos(a)), (float) (20 * Math.sin(a)));
        Path2D cut = Stroke.of(2).outline(sharp, TOLERANCE);
        Path2D kept = Stroke.of(2).withMiterLimit(20).outline(sharp, TOLERANCE);
        assertFalse(cut.contains(25, -0.3f));
        assertTrue(kept.contains(25, -0.3f), "the tip reaches about 11.4 past the corner");
    }

    // ------------------------------------------------------------------ against the definition

    @Test
    void roundEndsAndCornersCoverExactlyThePointsWithinHalfTheWidth() {
        Random random = new Random(85);
        Stroke stroke = Stroke.of(0).withCap(Stroke.Cap.ROUND).withJoin(Stroke.Join.ROUND);
        for (int trial = 0; trial < 40; trial++) {
            float[] pts = randomPolyline(random, 3 + random.nextInt(6));
            float width = 0.5f + random.nextFloat() * 8;
            boolean closed = trial % 3 == 0;
            Path2D path = polyline(pts, closed);
            Path2D outline = stroke.withWidth(width).outline(path, TOLERANCE);
            assertMatches(outline, (x, y) -> distanceToPolyline(pts, closed, x, y) - width / 2,
                    "trial " + trial + " width " + width + (closed ? " closed" : ""));
        }
    }

    @Test
    void buttEndsAndBevelledOrMitredCornersCoverTheRectanglesAndTheWedges() {
        Random random = new Random(1085);
        for (Stroke.Join join : new Stroke.Join[] {Stroke.Join.BEVEL, Stroke.Join.MITER}) {
            for (int trial = 0; trial < 30; trial++) {
                float[] pts = randomPolyline(random, 3 + random.nextInt(5));
                float width = 0.5f + random.nextFloat() * 6;
                Stroke stroke = Stroke.of(width).withJoin(join).withMiterLimit(3);
                Path2D outline = stroke.outline(polyline(pts, false), TOLERANCE);
                assertMatches(outline, (x, y) -> straightDistance(pts, width / 2, join, 3, x, y),
                        join + " trial " + trial + " width " + width);
            }
        }
    }

    @Test
    void aClosedOutlineHasItsHoleAndACornerAtItsStart() {
        Path2D square = new Path2D().addRect(0, 0, 20, 20);
        Path2D ring = Stroke.of(2).outline(square, TOLERANCE);
        assertTrue(ring.contains(0, 10));
        assertFalse(ring.contains(10, 10), "the inside of a closed outline is not covered");
        assertTrue(ring.contains(-0.9f, -0.9f), "a mitred corner where the subpath starts and ends");
    }

    @Test
    void aCurveIsStrokedAtItsWidthAllAlongWithoutCornersOfItsOwn() {
        Path2D circle = new Path2D().addCircle(0, 0, 10);
        Path2D ring = Stroke.of(2).withJoin(Stroke.Join.BEVEL).outline(circle, TOLERANCE);
        for (int i = 0; i < 72; i++) {
            double a = i * Math.PI / 36 + 0.013;
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            assertTrue(ring.contains(10.95f * c, 10.95f * s), "inside at angle " + i);
            assertTrue(ring.contains(9.05f * c, 9.05f * s), "inside at angle " + i);
            assertFalse(ring.contains(11.05f * c, 11.05f * s), "outside at angle " + i);
            assertFalse(ring.contains(8.95f * c, 8.95f * s), "outside at angle " + i);
        }
    }

    // ------------------------------------------------------------------ dashes

    @Test
    void dashesAreDrawnAndSkippedInTurnFromThePhase() {
        Path2D line = new Path2D().moveTo(0, 0).lineTo(30, 0);
        Path2D dashed = Stroke.of(2).withDash(4, 2).outline(line, TOLERANCE);
        assertTrue(dashed.contains(1, 0));
        assertFalse(dashed.contains(5, 0));
        assertTrue(dashed.contains(7, 0));
        assertFalse(dashed.contains(11, 0));
        Path2D moved = Stroke.of(2).withDash(4, 2).withDashPhase(1).outline(line, TOLERANCE);
        assertTrue(moved.contains(2.9f, 0));
        assertFalse(moved.contains(3.1f, 0), "a phase of 1 ends the first dash at 3");
        Path2D back = Stroke.of(2).withDash(4, 2).withDashPhase(-1).outline(line, TOLERANCE);
        assertFalse(back.contains(0.5f, 0), "a negative phase starts in the gap before");
        assertTrue(back.contains(1.5f, 0));
    }

    @Test
    void aDashOfNoLengthIsADotUnderRoundEnds() {
        Path2D line = new Path2D().moveTo(0, 0).lineTo(20, 0);
        Path2D dots = Stroke.of(2).withCap(Stroke.Cap.ROUND).withDash(0, 4).outline(line, TOLERANCE);
        for (float x : new float[] {0, 4, 8, 12, 16, 20}) {
            assertTrue(dots.contains(x + 0.9f, 0), "dot at " + x);
        }
        assertFalse(dots.contains(2, 0));
        Path2D squares = Stroke.of(2).withCap(Stroke.Cap.SQUARE).withDash(0, 4)
                .outline(new Path2D().moveTo(0, 0).lineTo(0, 20), TOLERANCE);
        assertTrue(squares.contains(0.9f, 4.9f), "a square dot facing the line's direction");
        assertTrue(Stroke.of(2).withDash(0, 4).outline(line).isEmpty(), "butt: nothing");
    }

    @Test
    void roundDashesCoverExactlyThePointsWithinHalfTheWidthOfWhatIsDrawn() {
        Random random = new Random(2085);
        for (int trial = 0; trial < 25; trial++) {
            float[] pts = randomPolyline(random, 3 + random.nextInt(4));
            boolean closed = trial % 2 == 0;
            float width = 0.5f + random.nextFloat() * 3;
            float[] pattern = {random.nextFloat() * 8, 0.5f + random.nextFloat() * 6};
            float phase = (random.nextFloat() - 0.3f) * 20;
            Stroke stroke = Stroke.of(width).withCap(Stroke.Cap.ROUND).withJoin(Stroke.Join.ROUND)
                    .withDash(pattern).withDashPhase(phase);
            Path2D outline = stroke.outline(polyline(pts, closed), TOLERANCE);
            List<float[]> drawn = drawnPoints(pts, closed, pattern, phase);
            assertMatches(outline, (x, y) -> distanceToPoints(drawn, x, y) - width / 2,
                    "trial " + trial + (closed ? " closed" : ""));
        }
    }

    @Test
    void aDashAcrossAClosedSubpathsStartIsOneDashWithACorner() {
        // A square of side 20 with dashes of 30 and gaps of 10, phase 25: the first five units are
        // drawn, then a gap, and the last dash runs to the start and on into the first one.
        Path2D square = new Path2D().addRect(0, 0, 20, 20);
        Path2D outline = Stroke.of(2).withDash(30, 10).withDashPhase(25).outline(square, TOLERANCE);
        assertTrue(outline.contains(-0.9f, -0.9f), "the start's corner is mitred, not two butt ends");
        assertTrue(outline.contains(3, 0));
        assertFalse(outline.contains(10, 0), "the gap after the first five units");
    }

    @Test
    void aPatternFarFinerThanThePathIsDrawnSolid() {
        Path2D longLine = new Path2D().moveTo(0, 0).lineTo(1_000_000, 0);
        Path2D outline = Stroke.of(2).withDash(1, 1).outline(longLine);
        assertTrue(outline.contains(1.5f, 0), "solid where a dash would have skipped");
        assertTrue(points(outline) < 20, "a few points, not millions");
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface SignedDistance {
        /** Negative inside the stroke, positive outside, in the path's units. */
        double at(float x, float y);
    }

    /** Samples a grid over the outline's bounds and holds every clear sample to the definition. */
    private static void assertMatches(Path2D outline, SignedDistance truth, String what) {
        Rect b = outline.bounds();
        assertTrue(b != null, what + ": empty outline");
        int wrong = 0;
        String first = null;
        int n = 70;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n; j++) {
                float x = b.x() - 1 + (b.width() + 2) * i / n;
                float y = b.y() - 1 + (b.height() + 2) * j / n;
                double d = truth.at(x, y);
                if (Math.abs(d) < MARGIN) {
                    continue;
                }
                if (outline.contains(x, y) != (d < 0)) {
                    wrong++;
                    if (first == null) {
                        first = x + "," + y + " is " + (d < 0 ? "inside" : "outside") + " by " + Math.abs(d);
                    }
                }
            }
        }
        assertEquals(0, wrong, what + ": first wrong sample " + first);
    }

    private static float[] randomPolyline(Random random, int count) {
        float[] pts = new float[count * 2];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = random.nextFloat() * 30;
        }
        if (count > 3) {
            // A turn straight back and a segment shorter than the width, the hard cases.
            pts[4] = pts[0] + (pts[2] - pts[0]) * 0.3f;
            pts[5] = pts[1] + (pts[3] - pts[1]) * 0.3f;
            pts[6] = pts[4] + 0.2f;
            pts[7] = pts[5] - 0.1f;
        }
        return pts;
    }

    private static Path2D polyline(float[] pts, boolean closed) {
        Path2D path = new Path2D().moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) {
            path.lineTo(pts[i], pts[i + 1]);
        }
        return closed ? path.close() : path;
    }

    private static double distanceToSegment(float px, float py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static double distanceToPolyline(float[] pts, boolean closed, float x, float y) {
        int n = pts.length / 2;
        double best = Double.MAX_VALUE;
        int segments = closed ? n : n - 1;
        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % n;
            best = Math.min(best, distanceToSegment(x, y, pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1]));
        }
        return best;
    }

    /**
     * Inside a butt-ended stroke with bevelled or mitred corners: inside a segment's rectangle, or
     * inside a corner's wedge on its outer side. Answered as -1, +1, or 0 near an edge.
     */
    private static double straightDistance(float[] pts, float half, Stroke.Join join, float limit, float x, float y) {
        int n = pts.length / 2;
        List<double[]> segs = new ArrayList<>();
        for (int i = 0; i + 1 < n; i++) {
            double ax = pts[i * 2];
            double ay = pts[i * 2 + 1];
            double bx = pts[i * 2 + 2];
            double by = pts[i * 2 + 3];
            double len = Math.hypot(bx - ax, by - ay);
            if (len > 1e-6) {
                segs.add(new double[] {ax, ay, bx, by, (bx - ax) / len, (by - ay) / len, len});
            }
        }
        double best = Double.MAX_VALUE; // signed: negative inside
        for (double[] s : segs) {
            double along = (x - s[0]) * s[4] + (y - s[1]) * s[5];
            double across = -(x - s[0]) * s[5] + (y - s[1]) * s[4];
            double d = Math.max(Math.max(-along, along - s[6]), Math.abs(across) - half);
            best = Math.min(best, d);
        }
        for (int k = 0; k + 1 < segs.size(); k++) {
            double[] in = segs.get(k);
            double[] out = segs.get(k + 1);
            double cross = in[4] * out[5] - in[5] * out[4];
            double dot = in[4] * out[4] + in[5] * out[5];
            if (Math.abs(cross) < 1e-9) {
                continue;
            }
            double side = cross < 0 ? 1 : -1;
            double px = in[2];
            double py = in[3];
            double[] a = {px - in[5] * half * side, py + in[4] * half * side};
            double[] c = {px - out[5] * half * side, py + out[4] * half * side};
            List<double[]> wedge = new ArrayList<>(List.of(new double[] {px, py}, a));
            if (join == Stroke.Join.MITER && Math.sqrt(2 / (1 + dot)) <= limit) {
                double k2 = half / (1 + dot);
                wedge.add(new double[] {px + (-in[5] - out[5]) * k2 * side, py + (in[4] + out[4]) * k2 * side});
            }
            wedge.add(c);
            best = Math.min(best, polygonDistance(wedge, x, y));
        }
        return best;
    }

    /** Signed distance to a convex polygon: negative inside. */
    private static double polygonDistance(List<double[]> poly, float x, float y) {
        double area = 0;
        for (int i = 0; i < poly.size(); i++) {
            double[] a = poly.get(i);
            double[] b = poly.get((i + 1) % poly.size());
            area += a[0] * b[1] - b[0] * a[1];
        }
        double sign = area >= 0 ? 1 : -1;
        double inside = -Double.MAX_VALUE;
        double edge = Double.MAX_VALUE;
        for (int i = 0; i < poly.size(); i++) {
            double[] a = poly.get(i);
            double[] b = poly.get((i + 1) % poly.size());
            double ex = b[0] - a[0];
            double ey = b[1] - a[1];
            double len = Math.hypot(ex, ey);
            if (len < 1e-12) {
                continue;
            }
            // Outward distance from this edge's line.
            double out = -sign * (ex * (y - a[1]) - ey * (x - a[0])) / len;
            inside = Math.max(inside, out);
            edge = Math.min(edge, distanceToSegment(x, y, a[0], a[1], b[0], b[1]));
        }
        return inside <= 0 ? inside : edge;
    }

    /** Points every hundredth of a unit along the path where the pattern draws. */
    private static List<float[]> drawnPoints(float[] pts, boolean closed, float[] pattern, float phase) {
        double period = pattern[0] + pattern[1];
        int n = pts.length / 2;
        int segments = closed ? n : n - 1;
        List<float[]> out = new ArrayList<>();
        double travelled = 0;
        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % n;
            double ax = pts[i * 2];
            double ay = pts[i * 2 + 1];
            double bx = pts[j * 2];
            double by = pts[j * 2 + 1];
            double len = Math.hypot(bx - ax, by - ay);
            int steps = Math.max(1, (int) Math.ceil(len / 0.01));
            for (int k = 0; k <= steps; k++) {
                double s = travelled + len * k / steps;
                double into = ((s + phase) % period + period) % period;
                if (into <= pattern[0] + 1e-9) {
                    out.add(new float[] {(float) (ax + (bx - ax) * k / steps), (float) (ay + (by - ay) * k / steps)});
                }
            }
            travelled += len;
        }
        return out;
    }

    private static double distanceToPoints(List<float[]> pts, float x, float y) {
        double best = Double.MAX_VALUE;
        for (float[] p : pts) {
            best = Math.min(best, Math.hypot(p[0] - x, p[1] - y));
        }
        return best;
    }

    private static int points(Path2D path) {
        int[] count = {0};
        path.flatten(1, new Path2D.Flattened() {
            @Override
            public void moveTo(float x, float y) {
                count[0]++;
            }

            @Override
            public void lineTo(float x, float y) {
                count[0]++;
            }

            @Override
            public void closePath() {
            }
        });
        return count[0];
    }
}

package limn.graphics;

import limn.math.Affine2D;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a path answers in Java, with no canvas: its arcs and shapes, its bounds, which points it
 * holds under each fill rule, its copies moved by a matrix, and SVG path data read into it.
 */
class Path2DGeometryTest {

    /** Every point the flattened path passes through. */
    private static List<float[]> points(Path2D path) {
        List<float[]> out = new ArrayList<>();
        path.flatten(0.001f, new Path2D.Flattened() {
            @Override
            public void moveTo(float x, float y) {
                out.add(new float[] {x, y});
            }

            @Override
            public void lineTo(float x, float y) {
                out.add(new float[] {x, y});
            }

            @Override
            public void closePath() {
            }
        });
        return out;
    }

    /** The segments as they were built, one letter each. */
    private static String verbs(Path2D path) {
        StringBuilder sb = new StringBuilder();
        path.forEach(new Path2D.Visitor() {
            @Override
            public void moveTo(float x, float y) {
                sb.append('M');
            }

            @Override
            public void lineTo(float x, float y) {
                sb.append('L');
            }

            @Override
            public void quadTo(float cx, float cy, float x, float y) {
                sb.append('Q');
            }

            @Override
            public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
                sb.append('C');
            }

            @Override
            public void close() {
                sb.append('Z');
            }
        });
        return sb.toString();
    }

    private static float[] last(Path2D path) {
        List<float[]> p = points(path);
        return p.get(p.size() - 1);
    }

    // ------------------------------------------------------------------ arcs and shapes

    @Test
    void anArcStaysOnItsCircleWithinThreeTenThousandthsOfTheRadius() {
        Path2D path = new Path2D().arc(50, 40, 30, 30, 0.3f, 4.1f);
        double worst = 0;
        for (float[] p : points(path)) {
            worst = Math.max(worst, Math.abs(Math.hypot(p[0] - 50, p[1] - 40) - 30));
        }
        assertTrue(worst <= 30 * 3e-4, "strayed " + worst);
        float[] end = last(path);
        assertEquals(50 + 30 * Math.cos(4.4), end[0], 1e-3);
        assertEquals(40 + 30 * Math.sin(4.4), end[1], 1e-3);
    }

    @Test
    void anArcAfterAPointIsJoinedToItByALineAndAWholeTurnClosesOnItself() {
        Path2D path = new Path2D().moveTo(0, 0).arc(20, 0, 10, 10, (float) Math.PI, (float) Math.PI);
        assertEquals("MLCC", verbs(path));
        Path2D circle = new Path2D().arc(0, 0, 5, 5, 0, 20);
        assertEquals("MCCCC", verbs(circle), "clamped to one turn: four quarter curves");
        float[] end = last(circle);
        assertEquals(5, end[0], 1e-4);
        assertEquals(0, end[1], 1e-4);
    }

    @Test
    void aTangentArcRoundsTheCornerOfTwoLines() {
        // A right-angle corner at (10, 0), from the left and then down: the arc of radius 4 meets
        // the first side at (6, 0) and the second at (10, 4), about the centre (6, 4).
        Path2D path = new Path2D().moveTo(0, 0).arcTo(10, 0, 10, 10, 4).lineTo(10, 10);
        for (float[] p : points(path)) {
            if (p[0] > 6.001f && p[1] < 3.999f) {
                assertEquals(4, Math.hypot(p[0] - 6, p[1] - 4), 4 * 3e-4, "on the arc: " + p[0] + "," + p[1]);
            }
        }
        assertTrue(points(path).stream().anyMatch(p -> Math.abs(p[0] - 6) < 1e-4 && Math.abs(p[1]) < 1e-4));
        // Three points on one line, or a radius of zero: a straight line to the corner.
        assertEquals("MLL", verbs(new Path2D().moveTo(0, 0).arcTo(5, 0, 10, 0, 3).lineTo(10, 0)));
        assertEquals("ML", verbs(new Path2D().moveTo(0, 0).arcTo(5, 0, 5, 5, 0)));
        assertThrows(IllegalStateException.class, () -> new Path2D().arcTo(1, 1, 2, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new Path2D().moveTo(0, 0).arcTo(1, 0, 1, 1, -1));
    }

    @Test
    void anEllipticalArcFindsItsCentreAsSvgDoes() {
        // From (0, 0) to (20, 0) on a circle of radius 10, whose centre is (10, 0). With y growing
        // down, clockwise from the left end goes over the top, through (10, -10).
        Path2D clockwise = new Path2D().moveTo(0, 0).ellipticalArcTo(10, 10, 0, false, true, 20, 0);
        assertTrue(points(clockwise).stream().anyMatch(p -> Math.abs(p[0] - 10) < 0.05 && Math.abs(p[1] + 10) < 0.05),
                "clockwise from the left goes over the top");
        Path2D counter = new Path2D().moveTo(0, 0).ellipticalArcTo(10, 10, 0, false, false, 20, 0);
        assertTrue(points(counter).stream().anyMatch(p -> Math.abs(p[0] - 10) < 0.05 && Math.abs(p[1] - 10) < 0.05));
        float[] end = last(clockwise);
        assertEquals(20, end[0], 0);
        assertEquals(0, end[1], 0);
        // Radii too small to reach are scaled up: a radius of 1 between points 20 apart is 10.
        Path2D grown = new Path2D().moveTo(0, 0).ellipticalArcTo(1, 1, 0, false, true, 20, 0);
        for (float[] p : points(grown)) {
            assertEquals(10, Math.hypot(p[0] - 10, p[1]), 0.01);
        }
        // The large arc of a smaller circle goes the long way round.
        Rect small = new Path2D().moveTo(0, 0).ellipticalArcTo(15, 15, 0, false, true, 20, 0).bounds();
        Rect large = new Path2D().moveTo(0, 0).ellipticalArcTo(15, 15, 0, true, true, 20, 0).bounds();
        assertTrue(large.height() > 20 && small.height() < 10, small + " / " + large);
        // A radius of zero is a line; an end at the start adds nothing.
        assertEquals("ML", verbs(new Path2D().moveTo(0, 0).ellipticalArcTo(0, 5, 0, false, true, 9, 9)));
        assertEquals("M", verbs(new Path2D().moveTo(3, 3).ellipticalArcTo(5, 5, 0, false, true, 3, 3)));
    }

    @Test
    void aTurnedEllipticalArcReachesAsFarAsItsTurnedEllipse() {
        // Half of an ellipse 40 by 10 turned 90 degrees: it reaches 10 to the side, 40 tall.
        Path2D path = new Path2D().moveTo(0, -20).ellipticalArcTo(20, 5, (float) (Math.PI / 2), false, true, 0, 20);
        Rect b = path.bounds();
        assertEquals(40, b.height(), 1e-3);
        assertEquals(5, b.width(), 1e-2);
    }

    @Test
    void shapesAreClosedSubpathsTurningClockwise() {
        Path2D rect = new Path2D().addRect(10, 20, 30, 40);
        assertEquals("MLLLZ", verbs(rect));
        assertEquals(new Rect(10, 20, 30, 40), rect.bounds());
        Path2D ellipse = new Path2D().addEllipse(0, 0, 20, 10);
        assertEquals("MCCCCZ", verbs(ellipse));
        Rect eb = ellipse.bounds();
        assertEquals(-20, eb.x(), 1e-4);
        assertEquals(-10, eb.y(), 1e-4);
        assertEquals(40, eb.width(), 1e-4);
        assertEquals(20, eb.height(), 1e-4);
        assertTrue(signedArea(ellipse) > 0, "clockwise with y down: positive shoelace area");
        assertTrue(signedArea(rect) > 0);
        assertTrue(new Path2D().addCircle(5, 5, 5).contains(5, 1));
        assertFalse(new Path2D().addCircle(5, 5, 5).contains(1, 1));
    }

    @Test
    void aRoundedRectangleHasTheCornersACanvasDraws() {
        Path2D path = new Path2D().addRoundRect(0, 0, 100, 40, 10);
        assertEquals(new Rect(0, 0, 100, 40), path.bounds());
        assertFalse(path.contains(1, 1), "the corner is cut off");
        assertTrue(path.contains(4, 4), "inside the arc");
        assertTrue(path.contains(50, 1));
        // Radii past half the shorter side draw as half of it: a pill.
        Path2D pill = new Path2D().addRoundRect(0, 0, 100, 20, 50);
        assertFalse(pill.contains(2, 2));
        assertTrue(pill.contains(10, 10));
        assertEquals(new Rect(0, 0, 100, 20), pill.bounds());
        // Corners of their own, one of them square.
        Path2D own = new Path2D().addRoundRect(new RoundRect(0, 0, 50, 50, 20, 0, 20, 0));
        assertTrue(own.contains(49.5f, 0.5f), "the square corner is there");
        assertFalse(own.contains(1, 1));
    }

    // ------------------------------------------------------------------ bounds

    @Test
    void theBoundsHoldTheCurvesReachNotTheirControlPoints() {
        Path2D quad = new Path2D().moveTo(0, 0).quadTo(50, 100, 100, 0);
        Rect qb = quad.bounds();
        assertEquals(50, qb.height(), 1e-4, "a quadratic's peak is half its control point's height");
        Path2D cubic = new Path2D().moveTo(0, 0).cubicTo(0, 100, 100, 100, 100, 0);
        assertEquals(75, cubic.bounds().height(), 1e-4, "three quarters of the control points' height");
        Path2D sideways = new Path2D().moveTo(0, 0).cubicTo(-30, 10, 130, 20, 100, 30);
        Rect sb = sideways.bounds();
        assertTrue(sb.x() < 0 && sb.right() > 100, "both sideways bulges are held: " + sb);
        assertTrue(sb.x() > -30 && sb.right() < 130);
        assertNull(new Path2D().bounds());
        assertEquals(new Rect(3, 4, 0, 0), new Path2D().moveTo(3, 4).bounds());
    }

    // ------------------------------------------------------------------ containment and rules

    @Test
    void aSubpathTurningTheOtherWayIsAHoleUnderBothRules() {
        Path2D framed = new Path2D()
                .moveTo(0, 0).lineTo(30, 0).lineTo(30, 30).lineTo(0, 30).close()
                .moveTo(10, 10).lineTo(10, 20).lineTo(20, 20).lineTo(20, 10).close();
        assertTrue(framed.contains(5, 5));
        assertFalse(framed.contains(15, 15), "non-zero: the inner square turns the other way");
        assertFalse(framed.setFillRule(FillRule.EVEN_ODD).contains(15, 15));
        assertFalse(new Path2D().contains(0, 0));
    }

    @Test
    void aSubpathTurningTheSameWayIsAHoleOnlyUnderEvenOdd() {
        Path2D twice = new Path2D().addRect(0, 0, 30, 30).addRect(10, 10, 10, 10);
        assertTrue(twice.contains(15, 15), "non-zero: same direction adds up");
        assertFalse(twice.setFillRule(FillRule.EVEN_ODD).contains(15, 15));
        assertTrue(twice.contains(5, 5));
    }

    @Test
    void theMiddleOfAStarDrawnAsOneOutlineDependsOnTheRule() {
        Path2D star = new Path2D();
        for (int i = 0; i < 5; i++) {
            double a = -Math.PI / 2 + i * 4 * Math.PI / 5;
            float x = (float) (50 + 40 * Math.cos(a));
            float y = (float) (50 + 40 * Math.sin(a));
            if (i == 0) {
                star.moveTo(x, y);
            } else {
                star.lineTo(x, y);
            }
        }
        star.close();
        assertTrue(star.contains(50, 50), "non-zero: the middle is wound twice");
        assertTrue(star.contains(50, 16), "a point is wound once");
        assertFalse(star.setFillRule(FillRule.EVEN_ODD).contains(50, 50));
        assertTrue(star.contains(50, 16));
        assertFalse(star.contains(5, 5));
    }

    @Test
    void anOpenSubpathIsTakenAsClosedAndCurvesAreFollowed() {
        Path2D open = new Path2D().moveTo(0, 0).lineTo(20, 0).lineTo(20, 20);
        assertTrue(open.contains(15, 5));
        assertFalse(open.contains(5, 15));
        Path2D dome = new Path2D().moveTo(0, 0).cubicTo(0, -40, 40, -40, 40, 0).close();
        assertTrue(dome.contains(20, -29), "just inside the top of the curve, at 30");
        assertFalse(dome.contains(20, -31));
    }

    @Test
    void theFillRuleIsKeptByCopyResetAndTransformed() {
        Path2D path = new Path2D().setFillRule(FillRule.EVEN_ODD).addRect(0, 0, 1, 1);
        assertEquals(FillRule.EVEN_ODD, path.copy().fillRule());
        assertEquals(FillRule.EVEN_ODD, path.transformed(Affine2D.scaling(2, 2)).fillRule());
        path.reset();
        assertEquals(FillRule.EVEN_ODD, path.fillRule());
        assertEquals(FillRule.NON_ZERO, new Path2D().fillRule());
    }

    // ------------------------------------------------------------------ copies under a matrix

    @Test
    void aPathMovedByAMatrixKeepsItsCurvesAsCurves() {
        Path2D path = new Path2D().moveTo(0, 0).quadTo(10, 10, 20, 0).cubicTo(20, 5, 25, 5, 30, 0).close();
        Path2D moved = path.transformed(Affine2D.translation(100, 50).rotate(Math.PI / 2));
        assertEquals(verbs(path), verbs(moved));
        Rect b = moved.bounds();
        assertEquals(100 - 5, b.x(), 1e-3, "turned a quarter: the quadratic's peak at y 5 is now x -5");
        assertEquals(50, b.y(), 1e-3);
        assertEquals(30, b.height(), 1e-3);
    }

    @Test
    void addingAPathAppendsItsSubpathsAndAPathMayAddItself() {
        Path2D tile = new Path2D().addRect(0, 0, 10, 10);
        Path2D row = new Path2D();
        for (int i = 0; i < 3; i++) {
            row.addPath(tile, Affine2D.translation(i * 20, 0));
        }
        assertEquals("MLLLZMLLLZMLLLZ", verbs(row));
        assertTrue(row.contains(45, 5));
        assertFalse(row.contains(15, 5));
        row.addPath(row);
        assertEquals(30, verbs(row).length());
    }

    // ------------------------------------------------------------------ SVG path data

    @Test
    void pathDataReadsEveryCommandAbsoluteAndRelative() {
        Path2D abs = Path2D.parse("M10 10 L20 10 H30 V20 C30 25 25 30 20 30 S10 25 10 20 Q10 15 15 15 T20 10 "
                + "A5 5 0 0 1 30 10 Z");
        Path2D rel = Path2D.parse("m10 10 l10 0 h10 v10 c0 5 -5 10 -10 10 s-10 -5 -10 -10 q0 -5 5 -5 t5 -5 "
                + "a5 5 0 0 1 10 0 z");
        assertEquals("MLLLCCQQCCZ", verbs(abs));
        assertEquals(verbs(abs), verbs(rel));
        List<float[]> pa = points(abs);
        List<float[]> pr = points(rel);
        assertEquals(pa.size(), pr.size());
        for (int i = 0; i < pa.size(); i++) {
            assertEquals(pa.get(i)[0], pr.get(i)[0], 1e-3);
            assertEquals(pa.get(i)[1], pr.get(i)[1], 1e-3);
        }
    }

    @Test
    void theSmoothCurvesReflectThePreviousControlPoint() {
        // S after C reflects C's second control point about the current point; after anything else
        // the first control point is the current point.
        List<float[]> controls = new ArrayList<>();
        Path2D.parse("M0 0 C0 10 10 10 10 0 S20 -10 20 0").forEach(new Path2D.Visitor() {
            @Override
            public void moveTo(float x, float y) {
            }

            @Override
            public void lineTo(float x, float y) {
            }

            @Override
            public void quadTo(float cx, float cy, float x, float y) {
            }

            @Override
            public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
                controls.add(new float[] {c1x, c1y});
            }

            @Override
            public void close() {
            }
        });
        assertEquals(10, controls.get(1)[0], 0);
        assertEquals(-10, controls.get(1)[1], 0);
    }

    @Test
    void pathDataNeedsNoSeparatorWhereASignOrADotSeparatesAndArcFlagsMayTouch() {
        Path2D compact = Path2D.parse("M.5.5l10-5.5e1a5,5,0,0010,0z");
        Path2D spaced = Path2D.parse("M 0.5 0.5 l 10 -55 a 5 5 0 0 0 10 0 z");
        assertEquals("MLCCZ", verbs(spaced));
        assertEquals(verbs(spaced), verbs(compact));
        assertEquals(points(spaced).size(), points(compact).size());
        assertEquals(new Rect(0.5f, -54.5f, 10, 55), Path2D.parse("M.5.5l10-5.5e1").bounds());
        assertEquals("MLLZ", verbs(Path2D.parse("M0 0 10 0 10 10z")), "pairs after a move are lines");
        assertEquals("MLZML", verbs(Path2D.parse("M0 0 L5 5 Z L 9 9")),
                "a command after a close starts again where the closed subpath started");
        assertTrue(Path2D.parse("").isEmpty());
        assertTrue(Path2D.parse("  \n ").isEmpty());
    }

    @Test
    void pathDataThatIsNotSaysWhere() {
        IllegalArgumentException noMove = assertThrows(IllegalArgumentException.class, () -> Path2D.parse("L 1 1"));
        assertTrue(noMove.getMessage().contains("index 0"), noMove.getMessage());
        IllegalArgumentException cut = assertThrows(IllegalArgumentException.class, () -> Path2D.parse("M 1 1 L 2"));
        assertTrue(cut.getMessage().contains("index 9"), cut.getMessage());
        IllegalArgumentException flag = assertThrows(IllegalArgumentException.class,
                () -> Path2D.parse("M0 0 A5 5 0 2 0 9 9"));
        assertTrue(flag.getMessage().contains("arc flag"), flag.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Path2D.parse("M0 0 X 1 1"));
        assertThrows(IllegalArgumentException.class, () -> Path2D.parse("M0 0 Z 5"));
        assertThrows(IllegalArgumentException.class, () -> Path2D.parse("M 1e999 0"));
    }

    @Test
    void anIconsPathDataFillsWhereItsShapeIs() {
        // A plus sign drawn as one outline, as icon sets write it.
        Path2D plus = Path2D.parse("M9 2h6v7h7v6h-7v7H9v-7H2V9h7z");
        assertTrue(plus.contains(12, 12));
        assertTrue(plus.contains(3, 12));
        assertFalse(plus.contains(3, 3));
        assertEquals(new Rect(2, 2, 20, 20), plus.bounds());
    }

    private static double signedArea(Path2D path) {
        List<float[]> p = points(path);
        double area = 0;
        for (int i = 0; i < p.size(); i++) {
            float[] a = p.get(i);
            float[] b = p.get((i + 1) % p.size());
            area += a[0] * b[1] - b[0] * a[1];
        }
        return area / 2;
    }
}

package limn.graphics;

import java.util.Arrays;

/**
 * Turns a path and a {@link Stroke} into the stroke's outline, a path filled under non-zero.
 *
 * <p>The outline is built so that it is exact under that rule without computing where any of its
 * edges cross. It is the sum of pieces that all wind the same way: a quadrilateral per segment,
 * a wedge on the outside of each corner (mitred, round or bevelled) and a cap at each open end.
 * Chained, the edges the pieces share cancel, which leaves each side of the line as one run of
 * points: the outer side of a corner goes round the corner's wedge, and the inner side goes in to
 * the corner point and out again. Every point the line covers is then wound once or more, in the
 * same direction, and every other point not at all, however short the segments and sharp the
 * corners. Inside a flattened curve the corners are round whatever the stroke's join, since they
 * are the curve's own bend, not a corner of the path.
 *
 * <p>A dash pattern first cuts each flattened subpath into open pieces; the outline of each piece
 * is then built as above.
 */
final class Stroker {

    /** Points closer than this, in the path's units, are one point. */
    private static final float SAME_POINT = 1e-6f;

    private final float half;
    private final Stroke.Cap cap;
    private final Stroke.Join join;
    private final float miterLimit;
    private final float tolerance;
    private final float[] dashes;
    private final float dashPhase;
    private final Path2D out = new Path2D();

    /** The flattened subpath being collected, and whether each point lies inside a curve. */
    private final Polyline line = new Polyline();
    /** A dash being cut from {@link #line}. */
    private final Polyline dash = new Polyline();
    /** For a closed subpath, its first dash, kept to join the last one if that reaches the end. */
    private final Polyline firstDash = new Polyline();
    /** One side's run of points, built forward and emitted backward. */
    private float[] side = new float[64];
    private int sideCount;

    Stroker(Stroke stroke, float tolerance) {
        this.half = stroke.width() / 2;
        this.cap = stroke.cap();
        this.join = stroke.join();
        this.miterLimit = stroke.miterLimit();
        this.tolerance = tolerance;
        this.dashes = stroke.dashes();
        this.dashPhase = stroke.dashPhase();
    }

    Path2D stroke(Path2D path) {
        if (half == 0) {
            return out;
        }
        boolean dashed = dashes.length > 0 && countDashes(path) <= Stroke.MAX_DASHES;
        path.forEach(new Path2D.Visitor() {
            private float startX;
            private float startY;
            private float curX;
            private float curY;

            @Override
            public void moveTo(float x, float y) {
                finish(false, dashed);
                startX = x;
                startY = y;
                curX = x;
                curY = y;
                line.add(x, y, false);
            }

            @Override
            public void lineTo(float x, float y) {
                resume();
                line.add(x, y, false);
                curX = x;
                curY = y;
            }

            @Override
            public void quadTo(float cx, float cy, float x, float y) {
                resume();
                Path2D.flattenQuad(curX, curY, cx, cy, x, y, tolerance, 0, curve);
                line.endCurve();
                curX = x;
                curY = y;
            }

            @Override
            public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
                resume();
                Path2D.flattenCubic(curX, curY, c1x, c1y, c2x, c2y, x, y, tolerance, 0, curve);
                line.endCurve();
                curX = x;
                curY = y;
            }

            @Override
            public void close() {
                finish(true, dashed);
                curX = startX;
                curY = startY;
            }

            /** A segment after a close starts a subpath where the closed one started. */
            private void resume() {
                if (line.count == 0) {
                    line.add(startX, startY, false);
                }
            }

            private final Path2D.Flattened curve = new Path2D.Flattened() {
                @Override
                public void moveTo(float x, float y) {
                }

                @Override
                public void lineTo(float x, float y) {
                    line.add(x, y, true);
                }

                @Override
                public void closePath() {
                }
            };
        });
        finish(false, dashed);
        return out;
    }

    /**
     * Strokes the collected subpath, then clears it. A subpath that is only a move is not stroked;
     * one of segments that go nowhere is a dot, as SVG draws them.
     */
    private void finish(boolean closed, boolean dashed) {
        Polyline p = line;
        if (p.count == 0 || (p.count == 1 && !closed)) {
            p.count = 0;
            return;
        }
        if (closed) {
            p.dedupeClosed();
        } else {
            p.dedupe();
        }
        if (closed && p.count > 1) {
            if (dashed) {
                dashClosed(p);
            } else {
                strokeClosed(p);
            }
        } else if (dashed && p.count > 1) {
            dashOpen(p);
        } else {
            strokeOpen(p, 1, 0);
        }
        p.count = 0;
    }

    // ------------------------------------------------------------------ dashes

    /** How many dashes the pattern cuts the whole path into, to refuse a pattern far too fine. */
    private double countDashes(Path2D path) {
        double[] length = {0};
        path.flatten(tolerance, new Path2D.Flattened() {
            private float x;
            private float y;
            private float sx;
            private float sy;

            @Override
            public void moveTo(float px, float py) {
                x = px;
                y = py;
                sx = px;
                sy = py;
            }

            @Override
            public void lineTo(float px, float py) {
                length[0] += Math.hypot(px - x, py - y);
                x = px;
                y = py;
            }

            @Override
            public void closePath() {
                lineTo(sx, sy);
            }
        });
        double period = 0;
        for (float d : dashes) {
            period += d;
        }
        return length[0] / period * (dashes.length / 2.0);
    }

    private int dashIndex;
    private float dashLeft;
    private boolean dashOn;

    /** Puts the pattern where the phase says a subpath starts. */
    private void startPattern() {
        double period = 0;
        for (float d : dashes) {
            period += d;
        }
        double into = dashPhase % period;
        if (into < 0) {
            into += period;
        }
        // Past each length the phase covers; a dash of no length at the very start stays, a dot.
        dashIndex = 0;
        while (into > dashes[dashIndex] || (into == dashes[dashIndex] && into > 0)) {
            into -= dashes[dashIndex];
            dashIndex = (dashIndex + 1) % dashes.length;
        }
        dashLeft = (float) (dashes[dashIndex] - into);
        dashOn = dashIndex % 2 == 0;
    }

    /** Starts a dash at a point, facing the segment it lies on. */
    private void beginDash(float x, float y, float dx, float dy) {
        dash.count = 0;
        dash.add(x, y, false);
        float len = (float) Math.hypot(dx, dy);
        dash.dirX = len > 0 ? dx / len : 1;
        dash.dirY = len > 0 ? dy / len : 0;
    }

    private void dashOpen(Polyline p) {
        startPattern();
        dash.count = 0;
        if (dashOn) {
            beginDash(p.x[0], p.y[0], p.x[1] - p.x[0], p.y[1] - p.y[0]);
        }
        walk(p, p.count - 1, null);
        int n = p.count;
        if (dashOn && dash.count > 0) {
            emitDash(dash);
        } else if (!dashOn && dashLeft == 0) {
            // The pattern turns on exactly at the end: what of that dash lies on the path is a dot.
            beginDash(p.x[n - 1], p.y[n - 1], p.x[n - 1] - p.x[n - 2], p.y[n - 1] - p.y[n - 2]);
            emitDash(dash);
        }
    }

    private void dashClosed(Polyline p) {
        startPattern();
        boolean onAtStart = dashOn;
        dash.count = 0;
        firstDash.count = 0;
        if (dashOn) {
            beginDash(p.x[0], p.y[0], p.x[1] - p.x[0], p.y[1] - p.y[0]);
        }
        int cuts = walk(p, p.count, firstDash);
        boolean onAtEnd = dashOn && dash.count > 0;
        if (cuts == 0 && onAtStart) {
            strokeClosed(p); // drawn all the way round: no dash ends
            return;
        }
        if (onAtStart && onAtEnd) {
            // The last dash runs on into the first, round the subpath's start, with a corner there.
            for (int i = 1; i < firstDash.count; i++) {
                dash.add(firstDash.x[i], firstDash.y[i], firstDash.smooth[i]);
            }
            emitDash(dash);
            return;
        }
        if (firstDash.count > 0) {
            emitDash(firstDash);
        }
        if (onAtEnd) {
            emitDash(dash);
        }
    }

    /**
     * Walks {@code segments} segments of {@code p} (wrapping to its first point for a closed one),
     * cutting dashes where the pattern turns. The first dash of a closed subpath that starts drawn
     * goes to {@code first} instead of out, to be joined to the last.
     *
     * @return how many times the pattern turned
     */
    private int walk(Polyline p, int segments, Polyline first) {
        int cuts = 0;
        boolean holdFirst = first != null && dashOn;
        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % p.count;
            float x0 = p.x[i];
            float y0 = p.y[i];
            float x1 = p.x[j];
            float y1 = p.y[j];
            float dx = x1 - x0;
            float dy = y1 - y0;
            float length = (float) Math.hypot(dx, dy);
            float at = 0;
            while (length - at > dashLeft || (dashLeft == 0 && at <= length)) {
                at += dashLeft;
                float t = length == 0 ? 0 : at / length;
                float cx = x0 + dx * t;
                float cy = y0 + dy * t;
                if (dashOn) {
                    dash.add(cx, cy, false);
                    if (holdFirst) {
                        first.copyFrom(dash);
                        holdFirst = false;
                    } else {
                        emitDash(dash);
                    }
                    dash.count = 0;
                } else {
                    beginDash(cx, cy, dx, dy);
                }
                cuts++;
                dashIndex = (dashIndex + 1) % dashes.length;
                dashLeft = dashes[dashIndex];
                dashOn = !dashOn;
                if (cuts > 2 * Stroke.MAX_DASHES) {
                    return cuts; // countDashes said otherwise; never loop without end
                }
            }
            dashLeft -= length - at;
            if (dashOn) {
                dash.add(x1, y1, p.smooth[j] && j != 0);
            }
        }
        return cuts;
    }

    private void emitDash(Polyline d) {
        if (d.count == 1 || d.allNear()) {
            if (cap != Stroke.Cap.BUTT) {
                strokeOpen(d, d.dirX, d.dirY);
            }
            return;
        }
        strokeOpen(d, 1, 0);
    }

    // ------------------------------------------------------------------ outlines

    /**
     * The outline of an open polyline: one side forward, the end cap, the other side backward, the
     * start cap. A polyline of one point, or of points all in one place, is a dot facing
     * {@code (dirX, dirY)}: a disc or a square under those caps, nothing under butt.
     */
    private void strokeOpen(Polyline p, float dirX, float dirY) {
        p.dedupe();
        int n = p.count;
        if (n == 1) {
            if (cap == Stroke.Cap.BUTT) {
                return;
            }
            float len = (float) Math.hypot(dirX, dirY);
            float dx = len > 0 ? dirX / len : 1;
            float dy = len > 0 ? dirY / len : 0;
            float px = p.x[0];
            float py = p.y[0];
            float nx = -dy * half;
            float ny = dx * half;
            out.moveTo(px + nx, py + ny);
            cap(px, py, dx, dy, nx, ny);
            out.lineTo(px - nx, py - ny);
            cap(px, py, -dx, -dy, -nx, -ny);
            out.close();
            return;
        }
        // Side A, forward, straight into the path.
        float[] d0 = direction(p, 0, 1);
        out.moveTo(p.x[0] - d0[1] * half, p.y[0] + d0[0] * half);
        sideCount = 0;
        float[] prev = d0;
        for (int j = 1; j < n - 1; j++) {
            float[] next = direction(p, j, j + 1);
            corner(p, j, prev, next, j - 1, j);
            prev = next;
        }
        float[] dl = prev;
        float ex = p.x[n - 1];
        float ey = p.y[n - 1];
        float nx = -dl[1] * half;
        float ny = dl[0] * half;
        out.lineTo(ex + nx, ey + ny);
        cap(ex, ey, dl[0], dl[1], nx, ny);
        // Side B, built forward, emitted backward.
        sideCount = 0;
        prev = d0;
        for (int j = 1; j < n - 1; j++) {
            float[] next = direction(p, j, j + 1);
            sideCorner(p, j, prev, next, j - 1, j);
            prev = next;
        }
        out.lineTo(ex - nx, ey - ny);
        emitSideBackward();
        float sx = p.x[0];
        float sy = p.y[0];
        out.lineTo(sx + d0[1] * half, sy - d0[0] * half);
        cap(sx, sy, -d0[0], -d0[1], d0[1] * half, -d0[0] * half);
        out.close();
    }

    /** The outline of a closed polyline: one side as a loop forward, the other as a loop backward. */
    private void strokeClosed(Polyline p) {
        int n = p.count;
        if (n == 1) {
            strokeOpen(p, 1, 0);
            return;
        }
        // A corner at every point, the first included, between the segment into it and out of it.
        boolean started = false;
        for (int j = 0; j < n; j++) {
            float[] prev = direction(p, (j - 1 + n) % n, j);
            float[] next = direction(p, j, (j + 1) % n);
            if (!started) {
                out.moveTo(p.x[j] - prev[1] * half, p.y[j] + prev[0] * half);
                started = true;
            }
            corner(p, j, prev, next, (j - 1 + n) % n, j);
        }
        out.close();
        sideCount = 0;
        for (int j = 0; j < n; j++) {
            float[] prev = direction(p, (j - 1 + n) % n, j);
            float[] next = direction(p, j, (j + 1) % n);
            if (j == 0) {
                pushSide(p.x[0] + prev[1] * half, p.y[0] - prev[0] * half);
            }
            sideCorner(p, j, prev, next, (j - 1 + n) % n, j);
        }
        // Backward, as a closed loop of its own.
        out.moveTo(side[sideCount * 2 - 2], side[sideCount * 2 - 1]);
        for (int i = sideCount - 2; i >= 0; i--) {
            out.lineTo(side[i * 2], side[i * 2 + 1]);
        }
        out.close();
    }

    /**
     * Side A's corner at point {@code j}, written straight to the outline: where the segment in
     * ends on this side, the corner, and where the segment out starts.
     */
    private void corner(Polyline p, int j, float[] prev, float[] next, int segIn, int segOut) {
        float px = p.x[j];
        float py = p.y[j];
        float ax = -prev[1];
        float ay = prev[0];
        float bx = -next[1];
        float by = next[0];
        out.lineTo(px + ax * half, py + ay * half);
        float cross = prev[0] * next[1] - prev[1] * next[0];
        float dot = prev[0] * next[0] + prev[1] * next[1];
        boolean outer = cross < 0 || (cross == 0 && dot < 0);
        if (outer) {
            outerCorner(p, j, px, py, ax, ay, bx, by, prev, dot, true);
        } else if (!(Math.abs(cross) <= 1e-6f && dot > 0) && !shortcut(p, segIn, segOut)) {
            out.lineTo(px, py);
        }
        out.lineTo(px + bx * half, py + by * half);
    }

    /** Side B's corner at point {@code j}, pushed onto {@link #side} to be emitted backward. */
    private void sideCorner(Polyline p, int j, float[] prev, float[] next, int segIn, int segOut) {
        float px = p.x[j];
        float py = p.y[j];
        float ax = prev[1];
        float ay = -prev[0];
        float bx = next[1];
        float by = -next[0];
        pushSide(px + ax * half, py + ay * half);
        float cross = prev[0] * next[1] - prev[1] * next[0];
        float dot = prev[0] * next[0] + prev[1] * next[1];
        boolean outer = cross > 0;
        if (outer) {
            outerCorner(p, j, px, py, ax, ay, bx, by, prev, dot, false);
        } else if (!(Math.abs(cross) <= 1e-6f && dot > 0) && !shortcut(p, segIn, segOut)) {
            pushSide(px, py);
        }
        pushSide(px + bx * half, py + by * half);
    }

    /**
     * Whether the inner side of a corner may go straight from one segment's side to the next's
     * instead of in to the corner point: when both segments are at least half the width long, the
     * small triangle that cuts off is covered by both segments' pieces, so it stays wound.
     */
    private boolean shortcut(Polyline p, int segIn, int segOut) {
        return p.length(segIn) >= half && p.length(segOut) >= half;
    }

    /**
     * The outside of a corner, from the side point of the segment in ({@code a}) to that of the
     * segment out ({@code b}), both unit normals on the outer side: round inside a curve or for a
     * round join, else mitred or bevelled. Writes to the outline for side A, to {@link #side} for B.
     */
    private void outerCorner(Polyline p, int j, float px, float py, float ax, float ay, float bx, float by,
                             float[] prev, float dot, boolean toOutline) {
        if (p.smooth[j] || join == Stroke.Join.ROUND) {
            double from = Math.atan2(ay, ax);
            double sweep = Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
            if (dot < 0 && Math.abs(ax * by - ay * bx) <= 1e-6f) {
                // A turn straight back: the half disc in front of the corner.
                double front = Math.atan2(prev[1], prev[0]);
                double toward = front - from;
                toward = Math.atan2(Math.sin(toward), Math.cos(toward));
                sweep = toward >= 0 ? Math.PI : -Math.PI;
            }
            arcPoints(px, py, from, sweep, toOutline);
        } else if (join == Stroke.Join.MITER && 1 + dot > 1e-6f) {
            float ratio = (float) Math.sqrt(2 / (1 + dot));
            if (ratio <= miterLimit) {
                float k = half / (1 + dot);
                point(px + (ax + bx) * k, py + (ay + by) * k, toOutline);
            }
        }
    }

    /** The points strictly inside an arc of radius half about {@code (cx, cy)}. */
    private void arcPoints(float cx, float cy, double from, double sweep, boolean toOutline) {
        int steps = arcSteps(sweep);
        for (int i = 1; i < steps; i++) {
            double a = from + sweep * i / steps;
            point((float) (cx + Math.cos(a) * half), (float) (cy + Math.sin(a) * half), toOutline);
        }
    }

    private int arcSteps(double sweep) {
        double step = tolerance >= half ? Math.PI / 2 : 2 * Math.acos(1 - tolerance / half);
        return Math.max(1, (int) Math.ceil(Math.abs(sweep) / Math.max(step, 1e-3)));
    }

    /**
     * The cap at an end point {@code (px, py)} facing {@code (dx, dy)}, from the side point
     * {@code p + n} to {@code p - n}, which the caller writes; this writes what lies between.
     */
    private void cap(float px, float py, float dx, float dy, float nx, float ny) {
        switch (cap) {
            case BUTT -> {
            }
            case SQUARE -> {
                out.lineTo(px + nx + dx * half, py + ny + dy * half);
                out.lineTo(px - nx + dx * half, py - ny + dy * half);
            }
            case ROUND -> {
                int steps = arcSteps(Math.PI);
                for (int i = 1; i < steps; i++) {
                    double t = Math.PI * i / steps;
                    float c = (float) Math.cos(t);
                    float s = (float) Math.sin(t);
                    out.lineTo(px + nx * c + dx * half * s, py + ny * c + dy * half * s);
                }
            }
        }
    }

    private void point(float x, float y, boolean toOutline) {
        if (toOutline) {
            out.lineTo(x, y);
        } else {
            pushSide(x, y);
        }
    }

    private void pushSide(float x, float y) {
        if (sideCount * 2 + 2 > side.length) {
            side = Arrays.copyOf(side, side.length * 2);
        }
        side[sideCount * 2] = x;
        side[sideCount * 2 + 1] = y;
        sideCount++;
    }

    private void emitSideBackward() {
        for (int i = sideCount - 1; i >= 0; i--) {
            out.lineTo(side[i * 2], side[i * 2 + 1]);
        }
        sideCount = 0;
    }

    /** The unit direction from point {@code i} to point {@code j}, which differ after a dedupe. */
    private static float[] direction(Polyline p, int i, int j) {
        float dx = p.x[j] - p.x[i];
        float dy = p.y[j] - p.y[i];
        float len = (float) Math.hypot(dx, dy);
        return new float[] {dx / len, dy / len};
    }

    /** Points with a flag each: whether the point lies inside a flattened curve. */
    private static final class Polyline {
        float[] x = new float[32];
        float[] y = new float[32];
        boolean[] smooth = new boolean[32];
        int count;
        /** For a dash of no length: the direction of the path where it lies. */
        float dirX = 1;
        float dirY;

        void add(float px, float py, boolean inCurve) {
            if (count == x.length) {
                x = Arrays.copyOf(x, count * 2);
                y = Arrays.copyOf(y, count * 2);
                smooth = Arrays.copyOf(smooth, count * 2);
            }
            x[count] = px;
            y[count] = py;
            smooth[count] = inCurve;
            count++;
        }

        /** The last point a curve added is its end, a corner of the path, not a bend of the curve. */
        void endCurve() {
            if (count > 0) {
                smooth[count - 1] = false;
            }
        }

        boolean near(int i, int j) {
            return Math.abs(x[i] - x[j]) <= SAME_POINT && Math.abs(y[i] - y[j]) <= SAME_POINT;
        }

        boolean allNear() {
            for (int i = 1; i < count; i++) {
                if (!near(i, 0)) {
                    return false;
                }
            }
            return true;
        }

        float length(int segment) {
            int j = (segment + 1) % count;
            return (float) Math.hypot(x[j] - x[segment], y[j] - y[segment]);
        }

        /** Drops each point equal to the one before it; a corner flag wins over a bend flag. */
        void dedupe() {
            int w = 1;
            for (int i = 1; i < count; i++) {
                if (near(i, w - 1)) {
                    smooth[w - 1] &= smooth[i];
                    continue;
                }
                x[w] = x[i];
                y[w] = y[i];
                smooth[w] = smooth[i];
                w++;
            }
            count = Math.min(count, w);
        }

        /** As {@link #dedupe}, and the last point dropped too when it is the first. */
        void dedupeClosed() {
            dedupe();
            while (count > 1 && near(count - 1, 0)) {
                smooth[0] &= smooth[count - 1];
                count--;
            }
        }

        void copyFrom(Polyline o) {
            count = 0;
            for (int i = 0; i < o.count; i++) {
                add(o.x[i], o.y[i], o.smooth[i]);
            }
            dirX = o.dirX;
            dirY = o.dirY;
        }
    }
}

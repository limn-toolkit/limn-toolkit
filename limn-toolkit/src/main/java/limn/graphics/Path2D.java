package limn.graphics;

import java.util.Arrays;

/**
 * Mutable 2D path: move/line/quadratic/cubic segments and closed subpaths, in
 * logical points. Curves are consumed via {@link #flatten}, which subdivides
 * them adaptively into line segments within a given tolerance; backends never
 * see curves.
 *
 * <p>V1 limitations (documented, revisited with the widget set): filling uses
 * each closed subpath independently, with no even-odd/non-zero interaction
 * between subpaths, so paths with holes render as overlapping fills;
 * self-intersecting subpaths are unsupported.
 */
public final class Path2D {

    /** Receives the flattened (curve-free) form of a path. */
    public interface Flattened {
        void moveTo(float x, float y);

        void lineTo(float x, float y);

        void closePath();
    }

    private static final byte MOVE = 0;
    private static final byte LINE = 1;
    private static final byte QUAD = 2;
    private static final byte CUBIC = 3;
    private static final byte CLOSE = 4;

    private static final int MAX_RECURSION = 24;

    private byte[] verbs = new byte[16];
    private float[] coords = new float[64];
    private int verbCount;
    private int coordCount;
    private float lastX;
    private float lastY;
    private boolean hasCurrentPoint;

    /** Starts a new subpath at a point, in logical points. */
    public Path2D moveTo(float x, float y) {
        addVerb(MOVE);
        addCoords(x, y);
        lastX = x;
        lastY = y;
        hasCurrentPoint = true;
        return this;
    }

    /** Extends the current subpath; a no-op before the first {@link #moveTo}. */
    public Path2D lineTo(float x, float y) {
        requireCurrentPoint();
        addVerb(LINE);
        addCoords(x, y);
        lastX = x;
        lastY = y;
        return this;
    }

    /** Quadratic Bézier to {@code (x,y)} with control point {@code (cx,cy)}. */
    public Path2D quadTo(float cx, float cy, float x, float y) {
        requireCurrentPoint();
        addVerb(QUAD);
        addCoords(cx, cy);
        addCoords(x, y);
        lastX = x;
        lastY = y;
        return this;
    }

    /** Cubic Bézier to {@code (x,y)} with control points {@code (c1x,c1y)} and {@code (c2x,c2y)}. */
    public Path2D cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
        requireCurrentPoint();
        addVerb(CUBIC);
        addCoords(c1x, c1y);
        addCoords(c2x, c2y);
        addCoords(x, y);
        lastX = x;
        lastY = y;
        return this;
    }

    /** Closes the current subpath (straight line back to its starting point). */
    public Path2D close() {
        requireCurrentPoint();
        addVerb(CLOSE);
        return this;
    }

    /** Whether the path has nothing to draw. */
    public boolean isEmpty() {
        return verbCount == 0;
    }

    /** Clears every subpath, keeping the allocated capacity so the path can be reused per frame. */
    public void reset() {
        verbCount = 0;
        coordCount = 0;
        hasCurrentPoint = false;
    }

    /**
     * Replays this path with curves adaptively subdivided so that no flattened
     * segment deviates from the true curve by more than {@code tolerance}
     * (same units as the path coordinates).
     */
    public void flatten(float tolerance, Flattened out) {
        float tol = Math.max(tolerance, 1e-4f);
        int ci = 0;
        float curX = 0;
        float curY = 0;
        float startX = 0;
        float startY = 0;
        for (int vi = 0; vi < verbCount; vi++) {
            switch (verbs[vi]) {
                case MOVE -> {
                    curX = coords[ci++];
                    curY = coords[ci++];
                    startX = curX;
                    startY = curY;
                    out.moveTo(curX, curY);
                }
                case LINE -> {
                    curX = coords[ci++];
                    curY = coords[ci++];
                    out.lineTo(curX, curY);
                }
                case QUAD -> {
                    float cx = coords[ci++];
                    float cy = coords[ci++];
                    float x = coords[ci++];
                    float y = coords[ci++];
                    flattenQuad(curX, curY, cx, cy, x, y, tol, 0, out);
                    curX = x;
                    curY = y;
                }
                case CUBIC -> {
                    float c1x = coords[ci++];
                    float c1y = coords[ci++];
                    float c2x = coords[ci++];
                    float c2y = coords[ci++];
                    float x = coords[ci++];
                    float y = coords[ci++];
                    flattenCubic(curX, curY, c1x, c1y, c2x, c2y, x, y, tol, 0, out);
                    curX = x;
                    curY = y;
                }
                case CLOSE -> {
                    out.closePath();
                    curX = startX;
                    curY = startY;
                }
                default -> throw new AssertionError();
            }
        }
    }

    private static void flattenQuad(float x0, float y0, float cx, float cy, float x1, float y1,
                                    float tol, int depth, Flattened out) {
        // Flat enough when the control point is within tolerance of the chord.
        if (depth >= MAX_RECURSION || pointSegmentDistanceSq(cx, cy, x0, y0, x1, y1) <= tol * tol) {
            out.lineTo(x1, y1);
            return;
        }
        float abx = (x0 + cx) * 0.5f;
        float aby = (y0 + cy) * 0.5f;
        float bcx = (cx + x1) * 0.5f;
        float bcy = (cy + y1) * 0.5f;
        float mx = (abx + bcx) * 0.5f;
        float my = (aby + bcy) * 0.5f;
        flattenQuad(x0, y0, abx, aby, mx, my, tol, depth + 1, out);
        flattenQuad(mx, my, bcx, bcy, x1, y1, tol, depth + 1, out);
    }

    private static void flattenCubic(float x0, float y0, float c1x, float c1y,
                                     float c2x, float c2y, float x1, float y1,
                                     float tol, int depth, Flattened out) {
        float d1 = pointSegmentDistanceSq(c1x, c1y, x0, y0, x1, y1);
        float d2 = pointSegmentDistanceSq(c2x, c2y, x0, y0, x1, y1);
        if (depth >= MAX_RECURSION || Math.max(d1, d2) <= tol * tol) {
            out.lineTo(x1, y1);
            return;
        }
        float abx = (x0 + c1x) * 0.5f;
        float aby = (y0 + c1y) * 0.5f;
        float bcx = (c1x + c2x) * 0.5f;
        float bcy = (c1y + c2y) * 0.5f;
        float cdx = (c2x + x1) * 0.5f;
        float cdy = (c2y + y1) * 0.5f;
        float abcx = (abx + bcx) * 0.5f;
        float abcy = (aby + bcy) * 0.5f;
        float bcdx = (bcx + cdx) * 0.5f;
        float bcdy = (bcy + cdy) * 0.5f;
        float mx = (abcx + bcdx) * 0.5f;
        float my = (abcy + bcdy) * 0.5f;
        flattenCubic(x0, y0, abx, aby, abcx, abcy, mx, my, tol, depth + 1, out);
        flattenCubic(mx, my, bcdx, bcdy, cdx, cdy, x1, y1, tol, depth + 1, out);
    }

    private static float pointSegmentDistanceSq(float px, float py, float ax, float ay, float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float apx = px - ax;
        float apy = py - ay;
        float len2 = abx * abx + aby * aby;
        float t = len2 <= 1e-12f ? 0f : Math.min(1f, Math.max(0f, (apx * abx + apy * aby) / len2));
        float dx = apx - t * abx;
        float dy = apy - t * aby;
        return dx * dx + dy * dy;
    }

    private void requireCurrentPoint() {
        if (!hasCurrentPoint) {
            throw new IllegalStateException("path must start with moveTo(...)");
        }
    }

    private void addVerb(byte verb) {
        if (verbCount == verbs.length) {
            verbs = Arrays.copyOf(verbs, verbs.length * 2);
        }
        verbs[verbCount++] = verb;
    }

    private void addCoords(float x, float y) {
        if (coordCount + 2 > coords.length) {
            coords = Arrays.copyOf(coords, coords.length * 2);
        }
        coords[coordCount++] = x;
        coords[coordCount++] = y;
    }
}

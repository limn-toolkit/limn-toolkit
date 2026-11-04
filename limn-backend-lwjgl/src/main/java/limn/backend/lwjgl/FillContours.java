package limn.backend.lwjgl;

import java.util.Arrays;

/**
 * Whether a flattened path can be filled one contour at a time, by the ear clipper, and come out
 * as its fill rule says: true when every contour is a simple polygon, no edge of it crossing or
 * touching another and none doubling back along the one before, and no two contours' bounding
 * boxes meet. Then each pixel inside the path is inside exactly one contour, once, and the non-zero
 * and even-odd rules both give the union the ear clipper draws. Any other path, a hole, two shapes
 * overlapping, an outline crossing itself, is filled through the stencil.
 *
 * <p>The test is conservative: a contour that touches itself at a point, or two contours whose
 * boxes overlap without their outlines meeting, go to the stencil, which fills them exactly too,
 * in three draws instead of a share of one. The edges of a contour are swept in order of their
 * left ends, so a contour whose edges run mostly one way, as a chart's line does, is tested in
 * about the time it takes to sort them.
 *
 * <p>Holds its working arrays for reuse; single-threaded, as the canvas that owns it.
 */
final class FillContours {

    /** The contour's points with consecutive duplicates left out, as indices into the arrays. */
    private int[] points = new int[64];
    /** The contour's edges, each its left end's sortable bits above its index. */
    private long[] order = new long[64];
    /** Per contour, its box: left, top, right, bottom. */
    private float[] boxes = new float[32];

    /**
     * @param xs       the points' x, in any one coordinate space
     * @param ys       their y
     * @param starts   where each contour's points start
     * @param counts   how many points each has, the closing edge implicit; one of fewer than three
     *                 is filled by neither route and is left out of the test
     * @param contours how many contours there are
     * @return whether each contour may be filled on its own
     */
    boolean separable(float[] xs, float[] ys, int[] starts, int[] counts, int contours) {
        if (boxes.length < contours * 4) {
            boxes = new float[contours * 8];
        }
        int kept = 0;
        for (int c = 0; c < contours; c++) {
            if (counts[c] < 3) {
                continue;
            }
            if (!simple(xs, ys, starts[c], counts[c])) {
                return false;
            }
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (int i = starts[c]; i < starts[c] + counts[c]; i++) {
                minX = Math.min(minX, xs[i]);
                minY = Math.min(minY, ys[i]);
                maxX = Math.max(maxX, xs[i]);
                maxY = Math.max(maxY, ys[i]);
            }
            for (int other = 0; other < kept; other++) {
                int at = other * 4;
                if (minX <= boxes[at + 2] && boxes[at] <= maxX && minY <= boxes[at + 3] && boxes[at + 1] <= maxY) {
                    return false;
                }
            }
            int at = kept * 4;
            boxes[at] = minX;
            boxes[at + 1] = minY;
            boxes[at + 2] = maxX;
            boxes[at + 3] = maxY;
            kept++;
        }
        return true;
    }

    /** Whether one closed contour is a simple polygon, its consecutive duplicates aside. */
    private boolean simple(float[] xs, float[] ys, int start, int count) {
        if (points.length < count) {
            points = new int[count * 2];
            order = new long[count * 2];
        }
        int n = 0;
        for (int i = start; i < start + count; i++) {
            if (n == 0 || xs[i] != xs[points[n - 1]] || ys[i] != ys[points[n - 1]]) {
                points[n++] = i;
            }
        }
        while (n > 1 && xs[points[n - 1]] == xs[points[0]] && ys[points[n - 1]] == ys[points[0]]) {
            n--;
        }
        if (n < 3) {
            return false; // no area of its own: let the stencil decide what it covers
        }
        for (int e = 0; e < n; e++) {
            int a = points[e];
            int b = points[(e + 1) % n];
            if (doublesBack(xs, ys, a, b, points[(e + 2) % n])) {
                return false;
            }
            order[e] = ((long) sortable(Math.min(xs[a], xs[b])) << 32) | e;
        }
        Arrays.sort(order, 0, n);
        for (int i = 0; i < n; i++) {
            int e = (int) order[i];
            int a = points[e];
            int b = points[(e + 1) % n];
            float right = Math.max(xs[a], xs[b]);
            for (int j = i + 1; j < n; j++) {
                int f = (int) order[j];
                int c = points[f];
                int d = points[(f + 1) % n];
                if (Math.min(xs[c], xs[d]) > right) {
                    break; // this edge and every later one start right of e's end
                }
                int gap = Math.abs(e - f);
                if (gap == 1 || gap == n - 1) {
                    continue; // neighbours share a point, and a doubling back was caught above
                }
                if (meet(xs[a], ys[a], xs[b], ys[b], xs[c], ys[c], xs[d], ys[d])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the edge from b to c runs back along the edge from a to b. */
    private static boolean doublesBack(float[] xs, float[] ys, int a, int b, int c) {
        double ux = xs[b] - xs[a];
        double uy = ys[b] - ys[a];
        double vx = xs[c] - xs[b];
        double vy = ys[c] - ys[b];
        return ux * vy - uy * vx == 0 && ux * vx + uy * vy < 0;
    }

    /** Whether two closed segments share a point. */
    static boolean meet(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        if (Math.max(ax, bx) < Math.min(cx, dx) || Math.max(cx, dx) < Math.min(ax, bx)
                || Math.max(ay, by) < Math.min(cy, dy) || Math.max(cy, dy) < Math.min(ay, by)) {
            return false;
        }
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        // An end on the other segment, collinear runs included; the boxes already overlap.
        return (d1 == 0 && within(cx, cy, dx, dy, ax, ay)) || (d2 == 0 && within(cx, cy, dx, dy, bx, by))
                || (d3 == 0 && within(ax, ay, bx, by, cx, cy)) || (d4 == 0 && within(ax, ay, bx, by, dx, dy));
    }

    private static double cross(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /** Whether p, on the line through a and b, lies between them. */
    private static boolean within(double ax, double ay, double bx, double by, double px, double py) {
        return Math.min(ax, bx) <= px && px <= Math.max(ax, bx) && Math.min(ay, by) <= py && py <= Math.max(ay, by);
    }

    /** A float's bits as an int that orders as the float does. */
    private static int sortable(float value) {
        int bits = Float.floatToIntBits(value);
        return bits ^ ((bits >> 31) & 0x7fffffff);
    }
}

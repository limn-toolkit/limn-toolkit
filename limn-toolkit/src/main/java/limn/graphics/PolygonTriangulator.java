package limn.graphics;

/**
 * Ear-clipping triangulation of simple (non-self-intersecting) polygons,
 * convex or concave, any winding. Support code for backends (and tested
 * headlessly here); not meant as application API.
 */
public final class PolygonTriangulator {

    private PolygonTriangulator() {
    }

    /**
     * Triangulates the polygon {@code (xs[offset + i], ys[offset + i])} for
     * {@code i < count}.
     *
     * @return triangle vertex indices <em>relative to {@code offset}</em>,
     *         {@code 3 * (count - 2)} entries on success; degenerate inputs
     *         (collinear rings, count &lt; 3) may produce fewer triangles
     */
    public static int[] triangulate(float[] xs, float[] ys, int offset, int count) {
        if (count < 3) {
            return new int[0];
        }
        int[] next = new int[count];
        int[] prev = new int[count];
        for (int i = 0; i < count; i++) {
            next[i] = (i + 1) % count;
            prev[i] = (i - 1 + count) % count;
        }

        // Normalize winding: signed area > 0 means clockwise in y-down space.
        boolean clockwise = signedArea(xs, ys, offset, count) > 0;

        int[] triangles = new int[Math.max(0, (count - 2) * 3)];
        int triCount = 0;
        int remaining = count;
        int ear = 0;
        int sinceLastEar = 0;

        while (remaining > 3) {
            int p = prev[ear];
            int n = next[ear];
            if (isEar(xs, ys, offset, p, ear, n, next, remaining, clockwise)) {
                triangles[triCount++] = p;
                triangles[triCount++] = ear;
                triangles[triCount++] = n;
                next[p] = n;
                prev[n] = p;
                remaining--;
                ear = n;
                sinceLastEar = 0;
            } else if (++sinceLastEar > remaining) {
                // Degenerate ring (collinear/duplicated points): clip the
                // CURRENT vertex unconditionally rather than looping forever,
                // emitting (p, ear, n) before advancing, so its area survives.
                triangles[triCount++] = p;
                triangles[triCount++] = ear;
                triangles[triCount++] = n;
                next[p] = n;
                prev[n] = p;
                remaining--;
                ear = n;
                sinceLastEar = 0;
            } else {
                ear = n;
            }
        }
        triangles[triCount++] = prev[ear];
        triangles[triCount++] = ear;
        triangles[triCount++] = next[ear];

        if (triCount == triangles.length) {
            return triangles;
        }
        int[] trimmed = new int[triCount];
        System.arraycopy(triangles, 0, trimmed, 0, triCount);
        return trimmed;
    }

    /** Signed polygon area (positive = clockwise with y growing down). */
    public static float signedArea(float[] xs, float[] ys, int offset, int count) {
        float sum = 0;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            sum += (xs[offset + j] * ys[offset + i] - xs[offset + i] * ys[offset + j]);
        }
        return sum / 2f;
    }

    private static boolean isEar(float[] xs, float[] ys, int o, int p, int e, int n,
                                 int[] next, int remaining, boolean clockwise) {
        float cross = cross(xs[o + p], ys[o + p], xs[o + e], ys[o + e], xs[o + n], ys[o + n]);
        if (clockwise ? cross <= 1e-9f : cross >= -1e-9f) {
            return false; // reflex or collinear corner
        }
        // No other remaining vertex may lie inside the candidate triangle.
        int v = next[n];
        for (int k = 0; k < remaining - 3; k++, v = next[v]) {
            if (pointInTriangle(xs[o + v], ys[o + v],
                    xs[o + p], ys[o + p], xs[o + e], ys[o + e], xs[o + n], ys[o + n])) {
                return false;
            }
        }
        return true;
    }

    private static float cross(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static boolean pointInTriangle(float px, float py,
                                           float ax, float ay, float bx, float by, float cx, float cy) {
        float d1 = cross(ax, ay, bx, by, px, py);
        float d2 = cross(bx, by, cx, cy, px, py);
        float d3 = cross(cx, cy, ax, ay, px, py);
        boolean hasNeg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean hasPos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(hasNeg && hasPos);
    }
}

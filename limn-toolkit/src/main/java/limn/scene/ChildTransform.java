package limn.scene;

import limn.math.Mat4;

/**
 * The matrix a widget draws its children through, as what it does to their plane: a point at
 * {@code (x, y)} of the widget's own space, a child's position included, is drawn at the matrix
 * times {@code (x, y, 0, 1)}, divided by its {@code w}. The children lie in the plane
 * {@code z = 0}, so only the matrix's rows and columns for x, y and w matter, and they make a
 * three by three homography of the plane, which this holds with its inverse, in double precision,
 * computed once when the matrix is set: a conversion or a hit test asks it for every point and
 * must not allocate.
 *
 * <p>Three cases answer {@code NaN}, which every comparison fails, so a caller's own bounds test
 * counts the point outside: a point that would lie behind the eye ({@code w} not above
 * {@link #NEAR}); and, going back from the drawing to the plane, a plane seen edge-on or collapsed,
 * which covers less than {@link #COLLAPSED} of the area it had at the point asked about.
 *
 * <p>Immutable; the widget replaces it when the matrix changes.
 */
final class ChildTransform {

    /**
     * How far in front of the eye a point must lie to be drawn: its {@code w}, which is 1 on the
     * plane through the matrix's origin and shrinks toward the eye. Above zero by a margin, so a
     * point that would be magnified a million times is treated as at the eye.
     */
    static final double NEAR = 1e-6;

    /**
     * The fraction of its area below which a plane counts as collapsed: a turn of ninety degrees
     * leaves single precision a cosine of about 4e-8 rather than 0, and a scale of 0 is often
     * reached through a product, so exact zero is not the test. A millionth is a box of a thousand
     * points drawn under one.
     */
    static final double COLLAPSED = 1e-6;

    final Mat4 matrix;

    private final double h00;
    private final double h01;
    private final double h02;
    private final double h10;
    private final double h11;
    private final double h12;
    private final double h20;
    private final double h21;
    private final double h22;
    /** The adjugate of the homography: its inverse up to a factor, which a projection ignores. */
    private final double a00;
    private final double a01;
    private final double a02;
    private final double a10;
    private final double a11;
    private final double a12;
    private final double a20;
    private final double a21;
    private final double a22;
    private final double det;

    ChildTransform(Mat4 matrix) {
        this.matrix = matrix;
        h00 = matrix.get(0, 0);
        h01 = matrix.get(0, 1);
        h02 = matrix.get(0, 3);
        h10 = matrix.get(1, 0);
        h11 = matrix.get(1, 1);
        h12 = matrix.get(1, 3);
        h20 = matrix.get(3, 0);
        h21 = matrix.get(3, 1);
        h22 = matrix.get(3, 3);
        a00 = h11 * h22 - h12 * h21;
        a01 = h02 * h21 - h01 * h22;
        a02 = h01 * h12 - h02 * h11;
        a10 = h12 * h20 - h10 * h22;
        a11 = h00 * h22 - h02 * h20;
        a12 = h02 * h10 - h00 * h12;
        a20 = h10 * h21 - h11 * h20;
        a21 = h01 * h20 - h00 * h21;
        a22 = h00 * h11 - h01 * h10;
        det = h00 * a00 + h01 * a10 + h02 * a20;
    }

    /**
     * @param matrix a matrix
     * @return whether it is the identity, which draws the children where layout put them
     */
    static boolean isIdentity(Mat4 matrix) {
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                if (matrix.get(row, col) != (row == col ? 1f : 0f)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Where a point of the widget's space is drawn: into {@code out[0..1]}, or {@code NaN} behind
     * the eye.
     */
    void map(float x, float y, float[] out) {
        double w = h20 * x + h21 * y + h22;
        if (!(w > NEAR)) {
            out[0] = Float.NaN;
            out[1] = Float.NaN;
            return;
        }
        out[0] = (float) ((h00 * x + h01 * y + h02) / w);
        out[1] = (float) ((h10 * x + h11 * y + h12) / w);
    }

    /**
     * Which point of the widget's space is drawn at {@code (x, y)}: the ray from the eye through
     * it met with the plane, into {@code out[0..1]}, or {@code NaN} where it meets the plane
     * behind the eye or the plane is edge-on or collapsed there.
     */
    void unmap(float x, float y, float[] out) {
        out[0] = unmapX(x, y);
        out[1] = unmapY(x, y);
    }

    /** The x of {@link #unmap}, for a caller with no array to hand; {@code NaN} where it is. */
    float unmapX(float x, float y) {
        double q = a20 * x + a21 * y + a22;
        double px = (a00 * x + a01 * y + a02) / q;
        double py = (a10 * x + a11 * y + a12) / q;
        return holds(px, py) ? (float) px : Float.NaN;
    }

    /** The y of {@link #unmap}; see {@link #unmapX}. */
    float unmapY(float x, float y) {
        double q = a20 * x + a21 * y + a22;
        double px = (a00 * x + a01 * y + a02) / q;
        double py = (a10 * x + a11 * y + a12) / q;
        return holds(px, py) ? (float) py : Float.NaN;
    }

    /** Whether a point of the plane is drawn in front of the eye and with area left. */
    private boolean holds(double px, double py) {
        double w = h20 * px + h21 * py + h22;
        // The area a small patch of the plane keeps at this point, drawn: det / w³.
        return w > NEAR && Math.abs(det / (w * w * w)) > COLLAPSED;
    }

    /**
     * The bounds where the rectangle {@code (x0, y0)} to {@code (x1, y1)} of the widget's space is
     * drawn, into {@code out} as {@code x0, y0, x1, y1}: the four corners projected, with the part
     * of the rectangle behind the eye cut away first, the cut's own edge then counting as corners.
     * All four {@code NaN} when nothing is left.
     */
    void bounds(float x0, float y0, float x1, float y1, float[] out) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        // The polygon's edges in order, each clipped against w >= NEAR (one plane of
        // Sutherland-Hodgman), its kept points folded straight into the bounds.
        for (int i = 0; i < 4; i++) {
            float ax = i == 1 || i == 2 ? x1 : x0;
            float ay = i >= 2 ? y1 : y0;
            float bx = i == 0 || i == 1 ? x1 : x0;
            float by = i == 0 || i == 3 ? y0 : y1;
            double aw = h20 * ax + h21 * ay + h22;
            double bw = h20 * bx + h21 * by + h22;
            boolean aIn = aw >= NEAR;
            boolean bIn = bw >= NEAR;
            if (aIn) {
                double sx = (h00 * ax + h01 * ay + h02) / aw;
                double sy = (h10 * ax + h11 * ay + h12) / aw;
                minX = Math.min(minX, sx);
                minY = Math.min(minY, sy);
                maxX = Math.max(maxX, sx);
                maxY = Math.max(maxY, sy);
            }
            if (aIn != bIn) {
                double t = (NEAR - aw) / (bw - aw);
                double cx = ax + t * (bx - ax);
                double cy = ay + t * (by - ay);
                double sx = (h00 * cx + h01 * cy + h02) / NEAR;
                double sy = (h10 * cx + h11 * cy + h12) / NEAR;
                minX = Math.min(minX, sx);
                minY = Math.min(minY, sy);
                maxX = Math.max(maxX, sx);
                maxY = Math.max(maxY, sy);
            }
        }
        if (minX > maxX) {
            out[0] = Float.NaN;
            out[1] = Float.NaN;
            out[2] = Float.NaN;
            out[3] = Float.NaN;
            return;
        }
        out[0] = (float) minX;
        out[1] = (float) minY;
        out[2] = (float) maxX;
        out[3] = (float) maxY;
    }
}

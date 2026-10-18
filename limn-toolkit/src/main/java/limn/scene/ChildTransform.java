package limn.scene;

import limn.math.Mat4;
import limn.math.MutMat4;
import org.jspecify.annotations.Nullable;

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
 * which covers less than {@link #COLLAPSED} of the area it had at the point asked about, or one
 * that {@linkplain #drawsNothing draws nothing}: a back face turned to the viewer while the widget
 * hides back faces, or a plane edge-on everywhere.
 *
 * <p>Which face is turned to the viewer is read from the whole matrix and not from the plane's
 * homography alone, which cannot tell a plane turned over from one mirrored: a scale of −1 along x
 * mirrors the children with their face still toward the viewer, and a half turn about the vertical
 * axis draws the same picture with their back to it. The plane's image is the plane whose
 * coefficients are the third row of the matrix's inverse, and the viewer looks along z, so the
 * plane faces the viewer when that row's z coefficient is positive, as a web page's
 * {@code backface-visibility} reads it; the cosine between the image's normal and z says how far
 * it is from edge-on.
 *
 * <p>Replaced when a matrix is set as a {@link Mat4}, as the tests' turned container sets one; a
 * transform container's values are loaded into the one it holds instead, in place, so that a
 * frame of a motion allocates no matrix.
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

    /**
     * The cosine, between the plane's image and the line of sight, below which the plane is seen
     * edge-on and draws nothing: a turn of ninety degrees, which single precision leaves at about
     * 4e-8, and under a perspective a plane through the eye, which projects to a line. A millionth
     * is a turn some six hundred-thousandths of a degree from edge-on.
     */
    static final double EDGE_ON = 1e-6;

    /** The matrix this was made from, or null for one a transform container loads in place. */
    final @Nullable Mat4 matrix;

    /**
     * Whether the matrix keeps the plane's axes where they are: a translation and a scale, each
     * axis on its own, a mirror and a flip seen flat included. Such a matrix keeps every rectangle a
     * rectangle, so the canvas draws the children through it straight, with {@link #paintX},
     * {@link #paintY}, {@link #paintScaleX} and {@link #paintScaleY}, every clip inside staying
     * exact; any other is drawn through a picture of its own.
     */
    boolean axisAligned;
    /** Where the axis-aligned matrix puts the widget's origin, and how much it scales each axis. */
    float paintX;
    float paintY;
    float paintScaleX;
    float paintScaleY;
    /**
     * Whether the matrix, with no perspective, leaves the plane less than {@link #COLLAPSED} of its
     * area everywhere: a scale of 0 on either axis, or a flip seen exactly edge-on. A perspective's
     * area changes from point to point and is never counted here; {@link #edgeOn} is its case.
     */
    private boolean collapsed;
    /**
     * Whether the plane's image lies along the line of sight, within {@link #EDGE_ON}: a turn of
     * exactly ninety degrees out of the screen, with a perspective or without, or a plane
     * collapsed in a way the line of sight cannot see past.
     */
    private boolean edgeOn;
    /** Whether the plane shows the viewer its back: turned over, and not merely mirrored. */
    boolean facesAway;
    /** Whether a back face turned to the viewer is drawn and hit; see {@link #setBackfaceVisible}. */
    private boolean backfaceVisible = true;
    /**
     * Whether the children draw nothing and take no pointer, whatever the point: the plane
     * collapsed to a line or a point, seen edge-on, or showing its back while back faces are
     * hidden. The paint skips them, {@link #unmap} answers {@code NaN} everywhere, and every walk
     * that carries bounds through the matrix leaves them out.
     */
    boolean drawsNothing;

    /** The matrix's sixteen entries, row-major, kept to read which face is turned to the viewer. */
    private final double[] entries = new double[16];

    private double h00;
    private double h01;
    private double h02;
    private double h10;
    private double h11;
    private double h12;
    private double h20;
    private double h21;
    private double h22;
    /** The adjugate of the homography: its inverse up to a factor, which a projection ignores. */
    private double a00;
    private double a01;
    private double a02;
    private double a10;
    private double a11;
    private double a12;
    private double a20;
    private double a21;
    private double a22;
    private double det;

    ChildTransform(Mat4 matrix) {
        this.matrix = matrix;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                entries[row * 4 + col] = matrix.get(row, col);
            }
        }
        load();
    }

    /** One a transform container loads its values into, with {@link #set}. */
    ChildTransform() {
        this.matrix = null;
    }

    /** The matrix a container loads in place, kept to answer {@link #asMat4()}; null for one set whole. */
    private @Nullable MutMat4 loaded;
    /** {@link #loaded} as a value, taken the first time a paint asks after a load, or null. */
    private @Nullable Mat4 snapshot;

    /** Loads {@code m} in place, for a container that holds this one; allocates nothing. */
    void set(MutMat4 m) {
        loaded = m;
        snapshot = null;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                entries[row * 4 + col] = m.get(row, col);
            }
        }
        load();
    }

    /**
     * Whether a back face turned to the viewer is drawn and hit: {@code true} unless the widget
     * hides back faces. Kept here beside the face the matrix turns, so that {@link #drawsNothing}
     * is one field to read.
     *
     * @param visible whether a back face is drawn
     */
    void setBackfaceVisible(boolean visible) {
        backfaceVisible = visible;
        drawsNothing = collapsed || edgeOn || (facesAway && !visible);
    }

    /**
     * The plane's homography, its adjugate, which face it turns to the viewer, and what the paint
     * and the collapse test read of it, from {@link #entries}.
     */
    private void load() {
        double[] e = entries;
        h00 = e[0];
        h01 = e[1];
        h02 = e[3];
        h10 = e[4];
        h11 = e[5];
        h12 = e[7];
        h20 = e[12];
        h21 = e[13];
        h22 = e[15];
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
        boolean affine = h20 == 0 && h21 == 0 && h22 > 0;
        axisAligned = affine && h01 == 0 && h10 == 0;
        paintX = affine ? (float) (h02 / h22) : 0;
        paintY = affine ? (float) (h12 / h22) : 0;
        paintScaleX = affine ? (float) (h00 / h22) : 1;
        paintScaleY = affine ? (float) (h11 / h22) : 1;
        collapsed = affine && Math.abs((h00 * h11 - h01 * h10) / (h22 * h22)) <= COLLAPSED;
        // The third row of the inverse is the plane's image, up to the determinant: its entries
        // are the cofactors of the third column, of which the one for z is the homography's own
        // determinant. Each is a minor of the three other rows, over x, y and w.
        double c0 = minor(e, 1, 2, 3);
        double c1 = -minor(e, 0, 2, 3);
        double c2 = det;
        double c3 = -minor(e, 0, 1, 2);
        double whole = e[2] * c0 + e[6] * c1 + e[10] * c2 + e[14] * c3;
        double facing = c2 / Math.sqrt(c0 * c0 + c1 * c1 + c2 * c2);
        edgeOn = !(Math.abs(facing) > EDGE_ON);
        // A matrix that collapses z has no inverse and no back to show: the face is then the front.
        facesAway = !edgeOn && whole != 0 && (facing < 0) != (whole < 0);
        setBackfaceVisible(backfaceVisible);
    }

    /** The determinant of rows {@code r0}, {@code r1} and {@code r2} of {@code e}, over x, y and w. */
    private static double minor(double[] e, int r0, int r1, int r2) {
        double a0 = e[r0 * 4];
        double a1 = e[r0 * 4 + 1];
        double a3 = e[r0 * 4 + 3];
        double b0 = e[r1 * 4];
        double b1 = e[r1 * 4 + 1];
        double b3 = e[r1 * 4 + 3];
        double d0 = e[r2 * 4];
        double d1 = e[r2 * 4 + 1];
        double d3 = e[r2 * 4 + 3];
        return a0 * (b1 * d3 - b3 * d1) - a1 * (b0 * d3 - b3 * d0) + a3 * (b0 * d1 - b1 * d0);
    }

    /**
     * The matrix as a value, for a canvas that draws the children through a picture: the one set,
     * or a snapshot of the one loaded, taken once per load, so that a turned container repainted
     * while still allocates nothing and a frame of its motion one matrix.
     *
     * @return the matrix
     */
    Mat4 asMat4() {
        if (matrix != null) {
            return matrix;
        }
        Mat4 taken = snapshot;
        if (taken == null) {
            taken = java.util.Objects.requireNonNull(loaded, "loaded").toMat4();
            snapshot = taken;
        }
        return taken;
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

    /** The x of {@link #map}, for a caller with no array to hand; {@code NaN} behind the eye. */
    float mapX(float x, float y) {
        double w = h20 * x + h21 * y + h22;
        return w > NEAR ? (float) ((h00 * x + h01 * y + h02) / w) : Float.NaN;
    }

    /** The y of {@link #map}; see {@link #mapX}. */
    float mapY(float x, float y) {
        double w = h20 * x + h21 * y + h22;
        return w > NEAR ? (float) ((h10 * x + h11 * y + h12) / w) : Float.NaN;
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

    /** Whether a point of the plane is drawn in front of the eye, with area left, and seen. */
    private boolean holds(double px, double py) {
        if (drawsNothing) {
            return false;
        }
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

    /**
     * How much more the matrix magnifies the rectangle {@code (x0, y0)} to {@code (x1, y1)} of the
     * widget's space somewhere than it does elsewhere, or in another direction: the largest
     * singular value of its derivative over the smallest, each taken at the four corners, where
     * under a perspective the nearest and the farthest lie. A canvas draws a picture at the largest
     * and minifies it by this much where the smallest is; past two to one it reads the picture
     * through a mipmap chain. Infinite where a corner lies behind the eye, whose neighbourhood is
     * magnified without bound, or where the plane collapses.
     */
    double minification(float x0, float y0, float x1, float y1) {
        double most = 0;
        double least = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double px = i == 1 || i == 2 ? x1 : x0;
            double py = i >= 2 ? y1 : y0;
            double w = h20 * px + h21 * py + h22;
            if (!(w > NEAR)) {
                return Double.POSITIVE_INFINITY;
            }
            double sx = (h00 * px + h01 * py + h02) / w;
            double sy = (h10 * px + h11 * py + h12) / w;
            double a = (h00 - sx * h20) / w;
            double b = (h01 - sx * h21) / w;
            double c = (h10 - sy * h20) / w;
            double d = (h11 - sy * h21) / w;
            double sum = a * a + b * b + c * c + d * d;
            double root = Math.sqrt(Math.max(0, sum * sum - 4 * (a * d - b * c) * (a * d - b * c)));
            most = Math.max(most, Math.sqrt((sum + root) / 2));
            least = Math.min(least, Math.sqrt(Math.max(0, (sum - root) / 2)));
        }
        return least > 0 ? most / least : Double.POSITIVE_INFINITY;
    }
}

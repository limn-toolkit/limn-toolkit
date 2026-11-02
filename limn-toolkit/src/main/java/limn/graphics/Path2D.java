package limn.graphics;

import limn.math.Affine2D;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Mutable 2D path: move/line/quadratic/cubic segments and closed subpaths, in
 * logical points. Curves are consumed via {@link #flatten}, which subdivides
 * them adaptively into line segments within a given tolerance; backends never
 * see curves.
 *
 * <p>Arcs, ellipses and rounded rectangles are added as cubic curves, within three ten-thousandths
 * of their radius, so a renderer still sees only the five kinds of segment; {@link #parse} reads SVG
 * path data into the same. {@link #bounds()} and {@link #contains} answer about the path in Java,
 * with no canvas.
 *
 * <p>The {@linkplain #setFillRule fill rule} says which points the outline encloses, as
 * {@link #contains} answers. V1 limitation of the canvases: filling uses each closed subpath
 * independently, with no even-odd/non-zero interaction between subpaths, so paths with holes
 * render as overlapping fills; self-intersecting subpaths are unsupported.
 */
public final class Path2D {

    /** Receives the flattened (curve-free) form of a path. */
    public interface Flattened {
        void moveTo(float x, float y);

        void lineTo(float x, float y);

        void closePath();
    }

    /**
     * Receives a path as it was built: its moves, lines, curves and closes, in order and in the
     * path's own coordinates. What a writer that keeps curves needs (a PDF page draws a curve as a
     * curve), where {@link Flattened} hands out lines.
     */
    public interface Visitor {
        void moveTo(float x, float y);

        void lineTo(float x, float y);

        void quadTo(float cx, float cy, float x, float y);

        void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y);

        void close();
    }

    private static final byte MOVE = 0;
    private static final byte LINE = 1;
    private static final byte QUAD = 2;
    private static final byte CUBIC = 3;
    private static final byte CLOSE = 4;

    private static final int MAX_RECURSION = 24;
    /** How finely {@link #contains} follows a curve, in the path's units. */
    private static final float CONTAINS_TOLERANCE = 1e-3f;

    private byte[] verbs = new byte[16];
    private float[] coords = new float[64];
    private int verbCount;
    private int coordCount;
    private float lastX;
    private float lastY;
    private float startX;
    private float startY;
    private boolean hasCurrentPoint;
    private FillRule fillRule = FillRule.NON_ZERO;

    /** Starts a new subpath at a point, in logical points. */
    public Path2D moveTo(float x, float y) {
        addVerb(MOVE);
        addCoords(x, y);
        lastX = x;
        lastY = y;
        startX = x;
        startY = y;
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
        lastX = startX;
        lastY = startY;
        return this;
    }

    /**
     * Sets which points the outline encloses. Kept by {@link #reset()}, {@link #copy()} and
     * {@link #transformed}.
     *
     * @param rule the rule, {@link FillRule#NON_ZERO} until set
     * @return this path
     */
    public Path2D setFillRule(FillRule rule) {
        fillRule = Objects.requireNonNull(rule, "rule");
        return this;
    }

    /** @return which points the outline encloses */
    public FillRule fillRule() {
        return fillRule;
    }

    /**
     * Adds an arc of an ellipse whose axes are upright, from {@code startAngle} through
     * {@code sweepAngle}. With a current point, a straight line joins it to the arc's start;
     * without one, the arc starts a subpath. A sweep of a whole turn or more draws the whole
     * ellipse once.
     *
     * @param cx         the centre's x
     * @param cy         its y
     * @param rx         the radius along x
     * @param ry         the radius along y
     * @param startAngle where the arc starts, in radians from the positive x axis, clockwise with
     *                   y growing down
     * @param sweepAngle how far it goes, in radians; negative goes counter-clockwise
     * @return this path
     */
    public Path2D arc(float cx, float cy, float rx, float ry, float startAngle, float sweepAngle) {
        requireFinite(cx, cy, rx, ry, startAngle, sweepAngle);
        double sweep = Math.max(-2 * Math.PI, Math.min(2 * Math.PI, sweepAngle));
        float x = (float) (cx + rx * Math.cos(startAngle));
        float y = (float) (cy + ry * Math.sin(startAngle));
        if (!hasCurrentPoint) {
            moveTo(x, y);
        } else if (x != lastX || y != lastY) {
            lineTo(x, y);
        }
        appendArc(cx, cy, rx, ry, 0, startAngle, sweep, Float.NaN, Float.NaN);
        return this;
    }

    /**
     * Adds the arc of radius {@code radius} that is tangent to the line from the current point to
     * {@code (x1, y1)} and to the line from there to {@code (x2, y2)}, joined to the current point
     * by a straight line: a rounded corner of a polygon. Where the corner cannot be rounded (the
     * three points on one line, a radius of zero, or {@code (x1, y1)} equal to either end) it is a
     * straight line to {@code (x1, y1)}, as a web canvas draws.
     *
     * @param x1     the corner's x
     * @param y1     its y
     * @param x2     the x of a point the next side goes towards
     * @param y2     its y
     * @param radius the corner's radius, not negative
     * @return this path
     * @throws IllegalArgumentException if the radius is negative
     * @throws IllegalStateException    without a current point
     */
    public Path2D arcTo(float x1, float y1, float x2, float y2, float radius) {
        requireCurrentPoint();
        requireFinite(x1, y1, x2, y2, radius, 0);
        if (radius < 0) {
            throw new IllegalArgumentException("negative radius: " + radius);
        }
        double ux = lastX - x1;
        double uy = lastY - y1;
        double vx = x2 - x1;
        double vy = y2 - y1;
        double ul = Math.hypot(ux, uy);
        double vl = Math.hypot(vx, vy);
        double cross = ux * vy - uy * vx;
        if (radius == 0 || ul == 0 || vl == 0 || Math.abs(cross) <= 1e-9 * ul * vl) {
            return lineTo(x1, y1);
        }
        ux /= ul;
        uy /= ul;
        vx /= vl;
        vy /= vl;
        double half = Math.acos(Math.max(-1, Math.min(1, ux * vx + uy * vy))) / 2;
        double along = radius / Math.tan(half);
        double bx = ux + vx;
        double by = uy + vy;
        double bl = Math.hypot(bx, by);
        double centre = radius / Math.sin(half);
        double cx = x1 + bx / bl * centre;
        double cy = y1 + by / bl * centre;
        float t1x = (float) (x1 + ux * along);
        float t1y = (float) (y1 + uy * along);
        double t2x = x1 + vx * along;
        double t2y = y1 + vy * along;
        double a1 = Math.atan2(t1y - cy, t1x - cx);
        double sweep = Math.atan2(t2y - cy, t2x - cx) - a1;
        if (sweep > Math.PI) {
            sweep -= 2 * Math.PI;
        } else if (sweep < -Math.PI) {
            sweep += 2 * Math.PI;
        }
        if (t1x != lastX || t1y != lastY) {
            lineTo(t1x, t1y);
        }
        appendArc(cx, cy, radius, radius, 0, a1, sweep, (float) t2x, (float) t2y);
        return this;
    }

    /**
     * Adds an elliptical arc from the current point to {@code (x, y)}, as SVG's {@code A} command
     * does: of the four arcs of an ellipse of these radii, turned by {@code rotation}, through both
     * points, the large or the small one, going clockwise or not. Radii too small to reach are
     * scaled up until they do; a radius of zero is a straight line, and an end equal to the current
     * point adds nothing.
     *
     * @param rx       the radius along the ellipse's own x
     * @param ry       the radius along its y
     * @param rotation how far the ellipse's x axis is turned, in radians, clockwise
     * @param largeArc whether the arc is the one of more than half a turn
     * @param sweep    whether it goes clockwise, with y growing down
     * @param x        the end's x
     * @param y        its y
     * @return this path
     * @throws IllegalStateException without a current point
     */
    public Path2D ellipticalArcTo(float rx, float ry, float rotation, boolean largeArc, boolean sweep,
                                  float x, float y) {
        requireCurrentPoint();
        requireFinite(rx, ry, rotation, x, y, 0);
        float x0 = lastX;
        float y0 = lastY;
        if (x0 == x && y0 == y) {
            return this;
        }
        double prx = Math.abs(rx);
        double pry = Math.abs(ry);
        if (prx == 0 || pry == 0) {
            return lineTo(x, y);
        }
        // The endpoint to centre conversion of the SVG specification's implementation notes.
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        double hx = (x0 - x) / 2.0;
        double hy = (y0 - y) / 2.0;
        double px = cos * hx + sin * hy;
        double py = -sin * hx + cos * hy;
        double lambda = px * px / (prx * prx) + py * py / (pry * pry);
        if (lambda > 1) {
            double grow = Math.sqrt(lambda);
            prx *= grow;
            pry *= grow;
        }
        double rx2 = prx * prx;
        double ry2 = pry * pry;
        double den = rx2 * py * py + ry2 * px * px;
        double coef = Math.sqrt(Math.max(0, (rx2 * ry2 - den) / den)) * (largeArc == sweep ? -1 : 1);
        double qx = coef * prx * py / pry;
        double qy = -coef * pry * px / prx;
        double cx = cos * qx - sin * qy + (x0 + x) / 2.0;
        double cy = sin * qx + cos * qy + (y0 + y) / 2.0;
        double ux = (px - qx) / prx;
        double uy = (py - qy) / pry;
        double vx = (-px - qx) / prx;
        double vy = (-py - qy) / pry;
        double start = Math.atan2(uy, ux);
        double delta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweep && delta > 0) {
            delta -= 2 * Math.PI;
        } else if (sweep && delta < 0) {
            delta += 2 * Math.PI;
        }
        appendArc(cx, cy, prx, pry, rotation, start, delta, x, y);
        return this;
    }

    /**
     * Adds a rectangle as a closed subpath of its own, clockwise from its top-left corner.
     *
     * @return this path
     */
    public Path2D addRect(float x, float y, float width, float height) {
        return moveTo(x, y).lineTo(x + width, y).lineTo(x + width, y + height).lineTo(x, y + height).close();
    }

    /**
     * Adds a rounded rectangle as a closed subpath of its own, clockwise from the end of its
     * top-left corner, with the corners a canvas draws for {@code fillRoundRect}: the radii
     * {@linkplain RoundRect#normalized() normalized}, each at most half the shorter side.
     *
     * @return this path
     */
    public Path2D addRoundRect(RoundRect roundRect) {
        RoundRect r = roundRect.normalized();
        float cap = Math.min(r.width(), r.height()) / 2;
        float tl = Math.min(r.topLeft(), cap);
        float tr = Math.min(r.topRight(), cap);
        float br = Math.min(r.bottomRight(), cap);
        float bl = Math.min(r.bottomLeft(), cap);
        float x = r.x();
        float y = r.y();
        float right = x + r.width();
        float bottom = y + r.height();
        double quarter = Math.PI / 2;
        moveTo(x + tl, y);
        lineTo(right - tr, y);
        if (tr > 0) {
            appendArc(right - tr, y + tr, tr, tr, 0, -quarter, quarter, right, y + tr);
        }
        lineTo(right, bottom - br);
        if (br > 0) {
            appendArc(right - br, bottom - br, br, br, 0, 0, quarter, right - br, bottom);
        }
        lineTo(x + bl, bottom);
        if (bl > 0) {
            appendArc(x + bl, bottom - bl, bl, bl, 0, quarter, quarter, x, bottom - bl);
        }
        lineTo(x, y + tl);
        if (tl > 0) {
            appendArc(x + tl, y + tl, tl, tl, 0, Math.PI, quarter, x + tl, y);
        }
        return close();
    }

    /**
     * Adds a rounded rectangle with one radius at every corner (see {@link #addRoundRect(RoundRect)}).
     *
     * @return this path
     */
    public Path2D addRoundRect(float x, float y, float width, float height, float radius) {
        return addRoundRect(RoundRect.of(x, y, width, height, radius));
    }

    /**
     * Adds an ellipse with upright axes as a closed subpath of its own, clockwise from its rightmost
     * point.
     *
     * @return this path
     */
    public Path2D addEllipse(float cx, float cy, float rx, float ry) {
        requireFinite(cx, cy, rx, ry, 0, 0);
        moveTo(cx + rx, cy);
        appendArc(cx, cy, rx, ry, 0, 0, 2 * Math.PI, cx + rx, cy);
        return close();
    }

    /**
     * Adds a circle as a closed subpath of its own (see {@link #addEllipse}).
     *
     * @return this path
     */
    public Path2D addCircle(float cx, float cy, float radius) {
        return addEllipse(cx, cy, radius, radius);
    }

    /**
     * Adds every segment of another path, as its own subpaths. The other path's fill rule is not
     * taken: this path's rule applies to the whole.
     *
     * @param other the path to add, which may be this one
     * @return this path
     */
    public Path2D addPath(Path2D other) {
        return addPath(other, null);
    }

    /**
     * Adds every segment of another path mapped by a matrix, as its own subpaths: a shape drawn
     * once and placed many times, turned or scaled. A curve stays a curve, since an affine map
     * keeps a Bézier curve one.
     *
     * @param other     the path to add, which may be this one
     * @param transform the map applied to its points; null for none
     * @return this path
     */
    public Path2D addPath(Path2D other, @Nullable Affine2D transform) {
        Objects.requireNonNull(other, "other");
        // A copy of the other's arrays first, as adding to itself grows them.
        byte[] v = Arrays.copyOf(other.verbs, other.verbCount);
        float[] c = Arrays.copyOf(other.coords, other.coordCount);
        if (transform != null) {
            double a = transform.a();
            double b = transform.b();
            double cc = transform.c();
            double d = transform.d();
            double e = transform.e();
            double f = transform.f();
            for (int i = 0; i < c.length; i += 2) {
                double px = c[i];
                double py = c[i + 1];
                c[i] = (float) (a * px + cc * py + e);
                c[i + 1] = (float) (b * px + d * py + f);
            }
        }
        int ci = 0;
        for (byte verb : v) {
            switch (verb) {
                case MOVE -> moveTo(c[ci++], c[ci++]);
                case LINE -> lineTo(c[ci++], c[ci++]);
                case QUAD -> quadTo(c[ci++], c[ci++], c[ci++], c[ci++]);
                case CUBIC -> cubicTo(c[ci++], c[ci++], c[ci++], c[ci++], c[ci++], c[ci++]);
                case CLOSE -> close();
                default -> throw new AssertionError();
            }
        }
        return this;
    }

    /**
     * A new path of this one's segments mapped by a matrix, with the same fill rule.
     *
     * @param transform the map
     * @return the new path
     */
    public Path2D transformed(Affine2D transform) {
        Objects.requireNonNull(transform, "transform");
        return new Path2D().setFillRule(fillRule).addPath(this, transform);
    }

    /**
     * The smallest upright rectangle holding the path: its points and the farthest reach of its
     * curves, not their control points.
     *
     * @return the bounds, or {@code null} for an empty path
     */
    public @Nullable Rect bounds() {
        if (verbCount == 0) {
            return null;
        }
        float[] box = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.NEGATIVE_INFINITY};
        int ci = 0;
        float curX = 0;
        float curY = 0;
        float subX = 0;
        float subY = 0;
        for (int vi = 0; vi < verbCount; vi++) {
            switch (verbs[vi]) {
                case MOVE, LINE -> {
                    if (verbs[vi] == MOVE) {
                        subX = coords[ci];
                        subY = coords[ci + 1];
                    }
                    curX = coords[ci++];
                    curY = coords[ci++];
                    include(box, curX, curY);
                }
                case QUAD -> {
                    float cx = coords[ci++];
                    float cy = coords[ci++];
                    float x = coords[ci++];
                    float y = coords[ci++];
                    include(box, x, y);
                    quadExtreme(box, curX, curY, cx, cy, x, y);
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
                    include(box, x, y);
                    cubicExtremes(box, curX, curY, c1x, c1y, c2x, c2y, x, y);
                    curX = x;
                    curY = y;
                }
                case CLOSE -> {
                    curX = subX;
                    curY = subY;
                }
                default -> throw new AssertionError();
            }
        }
        return new Rect(box[0], box[1], box[2] - box[0], box[3] - box[1]);
    }

    /**
     * Whether a point is inside the path under its {@linkplain #fillRule() fill rule}, every subpath
     * taken as closed, as a fill draws it: what a hit test on a filled shape asks. A point exactly
     * on the outline may answer either way. A hit on a stroke is
     * {@code stroke.outline(path).contains(x, y)}.
     *
     * @param x the point's x, in the path's coordinates
     * @param y its y
     * @return whether it is inside
     */
    public boolean contains(float x, float y) {
        Winding winding = new Winding(x, y);
        flatten(CONTAINS_TOLERANCE, winding);
        winding.closePath();
        return fillRule == FillRule.NON_ZERO ? winding.count != 0 : (winding.count & 1) != 0;
    }

    /**
     * Reads SVG path data, the text of a {@code <path>}'s {@code d} attribute: the commands
     * {@code M}, {@code L}, {@code H}, {@code V}, {@code C}, {@code S}, {@code Q}, {@code T},
     * {@code A} and {@code Z}, in capitals for absolute coordinates and in small letters for
     * relative ones, with numbers separated by spaces, commas or nothing where a sign or a second
     * decimal point already separates them. It is how icon and diagram programs hand a shape over.
     *
     * @param data the path data; empty or blank gives an empty path
     * @return a new path, under {@link FillRule#NON_ZERO}
     * @throws IllegalArgumentException where the data is not path data, with the index of the first
     *                                  character that is not; SVG would draw up to it
     */
    public static Path2D parse(String data) {
        return PathData.parse(Objects.requireNonNull(data, "data"));
    }

    /** Whether the path has nothing to draw. */
    public boolean isEmpty() {
        return verbCount == 0;
    }

    /**
     * Clears every subpath, keeping the allocated capacity and the fill rule so the path can be
     * reused per frame.
     */
    public void reset() {
        verbCount = 0;
        coordCount = 0;
        hasCurrentPoint = false;
    }

    /**
     * Replays this path as it was built, curves as curves.
     *
     * @param out receives each segment in order
     */
    public void forEach(Visitor out) {
        int ci = 0;
        for (int vi = 0; vi < verbCount; vi++) {
            switch (verbs[vi]) {
                case MOVE -> out.moveTo(coords[ci++], coords[ci++]);
                case LINE -> out.lineTo(coords[ci++], coords[ci++]);
                case QUAD -> out.quadTo(coords[ci++], coords[ci++], coords[ci++], coords[ci++]);
                case CUBIC -> out.cubicTo(coords[ci++], coords[ci++], coords[ci++], coords[ci++],
                        coords[ci++], coords[ci++]);
                case CLOSE -> out.close();
                default -> throw new AssertionError();
            }
        }
    }

    /**
     * A path of its own with the same segments: what a caller that keeps a path past the call it
     * was handed in takes, since the one it was handed may be reset and reused for the next frame.
     *
     * @return the copy
     */
    public Path2D copy() {
        Path2D copy = new Path2D();
        copy.verbs = Arrays.copyOf(verbs, Math.max(16, verbCount));
        copy.coords = Arrays.copyOf(coords, Math.max(64, coordCount));
        copy.verbCount = verbCount;
        copy.coordCount = coordCount;
        copy.lastX = lastX;
        copy.lastY = lastY;
        copy.startX = startX;
        copy.startY = startY;
        copy.hasCurrentPoint = hasCurrentPoint;
        copy.fillRule = fillRule;
        return copy;
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

    static void flattenQuad(float x0, float y0, float cx, float cy, float x1, float y1,
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

    static void flattenCubic(float x0, float y0, float c1x, float c1y,
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

    /**
     * Appends an arc of the ellipse at {@code (cx, cy)} with radii {@code rx}, {@code ry} and its x
     * axis turned by {@code rotation}, from {@code start} through {@code sweep}, as cubic curves of
     * at most a quarter turn each, from the current point, which is the arc's start. The last curve
     * ends exactly at {@code (endX, endY)} when it is given, not at what the cosines round to.
     */
    private void appendArc(double cx, double cy, double rx, double ry, double rotation, double start,
                           double sweep, float endX, float endY) {
        if (sweep == 0) {
            return;
        }
        // A sweep given in float lands a little past a quarter turn: that is still one piece.
        int pieces = Math.max(1, (int) Math.ceil(Math.abs(sweep) / (Math.PI / 2) - 1e-6));
        double step = sweep / pieces;
        double k = 4.0 / 3.0 * Math.tan(step / 4);
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        double a0 = start;
        double c0 = Math.cos(a0);
        double s0 = Math.sin(a0);
        for (int i = 1; i <= pieces; i++) {
            double a1 = start + step * i;
            double c1 = Math.cos(a1);
            double s1 = Math.sin(a1);
            // The points and the tangents of the upright ellipse, then turned and moved.
            double p0x = rx * c0;
            double p0y = ry * s0;
            double p1x = rx * c1;
            double p1y = ry * s1;
            double q1x = p0x - k * rx * s0;
            double q1y = p0y + k * ry * c0;
            double q2x = p1x + k * rx * s1;
            double q2y = p1y - k * ry * c1;
            float x = (float) (cx + cos * p1x - sin * p1y);
            float y = (float) (cy + sin * p1x + cos * p1y);
            if (i == pieces && !Float.isNaN(endX)) {
                x = endX;
                y = endY;
            }
            cubicTo((float) (cx + cos * q1x - sin * q1y), (float) (cy + sin * q1x + cos * q1y),
                    (float) (cx + cos * q2x - sin * q2y), (float) (cy + sin * q2x + cos * q2y), x, y);
            c0 = c1;
            s0 = s1;
        }
    }

    private static void include(float[] box, float x, float y) {
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.max(box[2], x);
        box[3] = Math.max(box[3], y);
    }

    /** Includes where a quadratic curve turns back along either axis. */
    private static void quadExtreme(float[] box, float x0, float y0, float cx, float cy, float x1, float y1) {
        double dx = x0 - 2.0 * cx + x1;
        double dy = y0 - 2.0 * cy + y1;
        double tx = dx == 0 ? -1 : (x0 - cx) / dx;
        double ty = dy == 0 ? -1 : (y0 - cy) / dy;
        for (double t : new double[] {tx, ty}) {
            if (t > 0 && t < 1) {
                double u = 1 - t;
                include(box, (float) (u * u * x0 + 2 * u * t * cx + t * t * x1),
                        (float) (u * u * y0 + 2 * u * t * cy + t * t * y1));
            }
        }
    }

    /** Includes where a cubic curve turns back along either axis, the roots of its derivative. */
    private static void cubicExtremes(float[] box, float x0, float y0, float c1x, float c1y,
                                      float c2x, float c2y, float x1, float y1) {
        double[] roots = new double[4];
        int n = derivativeRoots(x0, c1x, c2x, x1, roots, 0);
        n = derivativeRoots(y0, c1y, c2y, y1, roots, n);
        for (int i = 0; i < n; i++) {
            double t = roots[i];
            double u = 1 - t;
            double w0 = u * u * u;
            double w1 = 3 * u * u * t;
            double w2 = 3 * u * t * t;
            double w3 = t * t * t;
            include(box, (float) (w0 * x0 + w1 * c1x + w2 * c2x + w3 * x1),
                    (float) (w0 * y0 + w1 * c1y + w2 * c2y + w3 * y1));
        }
    }

    /** Stores the roots in (0, 1) of a cubic's derivative along one axis; returns the new count. */
    private static int derivativeRoots(double p0, double p1, double p2, double p3, double[] out, int n) {
        double a = p3 - 3 * p2 + 3 * p1 - p0;
        double b = 2 * (p2 - 2 * p1 + p0);
        double c = p1 - p0;
        if (Math.abs(a) < 1e-12) {
            if (Math.abs(b) > 1e-12) {
                double t = -c / b;
                if (t > 0 && t < 1) {
                    out[n++] = t;
                }
            }
            return n;
        }
        double disc = b * b - 4 * a * c;
        if (disc < 0) {
            return n;
        }
        double root = Math.sqrt(disc);
        for (double t : new double[] {(-b + root) / (2 * a), (-b - root) / (2 * a)}) {
            if (t > 0 && t < 1) {
                out[n++] = t;
            }
        }
        return n;
    }

    /** Counts how many times the outline winds round a point, along a ray towards positive x. */
    private static final class Winding implements Flattened {
        private final float px;
        private final float py;
        int count;
        private float startX;
        private float startY;
        private float curX;
        private float curY;
        private boolean open;

        Winding(float px, float py) {
            this.px = px;
            this.py = py;
        }

        @Override
        public void moveTo(float x, float y) {
            closePath();
            startX = x;
            startY = y;
            curX = x;
            curY = y;
            open = true;
        }

        @Override
        public void lineTo(float x, float y) {
            if (!open) {
                // A line after a close continues from where the closed subpath started.
                open = true;
            }
            edge(curX, curY, x, y);
            curX = x;
            curY = y;
        }

        @Override
        public void closePath() {
            if (open) {
                edge(curX, curY, startX, startY);
                curX = startX;
                curY = startY;
                open = false;
            }
        }

        private void edge(float x0, float y0, float x1, float y1) {
            float side = (x1 - x0) * (py - y0) - (px - x0) * (y1 - y0);
            if (y0 <= py) {
                if (y1 > py && side > 0) {
                    count++;
                }
            } else if (y1 <= py && side < 0) {
                count--;
            }
        }
    }

    private static void requireFinite(float a, float b, float c, float d, float e, float f) {
        if (!Float.isFinite(a) || !Float.isFinite(b) || !Float.isFinite(c) || !Float.isFinite(d)
                || !Float.isFinite(e) || !Float.isFinite(f)) {
            throw new IllegalArgumentException("not finite: " + a + ", " + b + ", " + c + ", " + d + ", "
                    + e + ", " + f);
        }
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

package limn.math;

/**
 * An immutable affine map of the plane, {@code x' = a x + c y + e} and {@code y' = b x + d y + f}:
 * what a 2D canvas's transform is, written as a PDF page and {@link Mat4#affine2D} write one. In
 * double precision, so that a long chain of turns and moves composes without drifting.
 *
 * <p>The building methods mirror the canvas: {@code m.translate(10, 0).rotate(a)} is the matrix a
 * canvas holds after {@code canvas.concat(m); canvas.translate(10, 0); canvas.rotate(a)}. Each
 * applies its operation <em>first</em>, before {@code m}, which is what drawing inside a moved
 * and turned space means: {@code m.concat(inner)} maps a point by {@code inner}, then by
 * {@code m}. Positive angles turn clockwise, the y axis growing down.
 *
 * <p>A negative zero is stored as zero, so that a turn by nothing equals {@link #IDENTITY}.
 *
 * @param a how far x moves per unit of x
 * @param b how far y moves per unit of x
 * @param c how far x moves per unit of y
 * @param d how far y moves per unit of y
 * @param e the translation along x
 * @param f the translation along y
 */
public record Affine2D(double a, double b, double c, double d, double e, double f) {

    /** The map that leaves every point where it is. */
    public static final Affine2D IDENTITY = new Affine2D(1, 0, 0, 1, 0, 0);

    /**
     * @throws IllegalArgumentException if a component is not finite
     */
    public Affine2D {
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c) || !Double.isFinite(d)
                || !Double.isFinite(e) || !Double.isFinite(f)) {
            throw new IllegalArgumentException("not a finite matrix: " + a + ", " + b + ", " + c + ", " + d
                    + ", " + e + ", " + f);
        }
        a += 0.0;
        b += 0.0;
        c += 0.0;
        d += 0.0;
        e += 0.0;
        f += 0.0;
    }

    /**
     * @param dx the distance along x
     * @param dy the distance along y
     * @return a map that moves every point by {@code (dx, dy)}
     */
    public static Affine2D translation(double dx, double dy) {
        return new Affine2D(1, 0, 0, 1, dx, dy);
    }

    /**
     * @param sx the factor along x
     * @param sy the factor along y
     * @return a map that scales about the origin
     */
    public static Affine2D scaling(double sx, double sy) {
        return new Affine2D(sx, 0, 0, sy, 0, 0);
    }

    /**
     * @param radians the angle, positive clockwise with y growing down
     * @return a map that turns about the origin
     */
    public static Affine2D rotation(double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Affine2D(cos, sin, -sin, cos, 0, 0);
    }

    /**
     * @param radians the angle, positive clockwise with y growing down
     * @param cx      the x of the point that stays where it is
     * @param cy      its y
     * @return a map that turns about {@code (cx, cy)}
     */
    public static Affine2D rotation(double radians, double cx, double cy) {
        return translation(cx, cy).rotate(radians).translate(-cx, -cy);
    }

    /**
     * A shear: {@code x' = x + sx y} and {@code y' = sy x + y}, as the platform's
     * {@code AffineTransform.shear} is. A shear of {@code tan(20°)} along x leans every vertical
     * line twenty degrees, its foot staying where it is.
     *
     * @param sx how far x moves per unit of y
     * @param sy how far y moves per unit of x
     * @return the shear
     */
    public static Affine2D shearing(double sx, double sy) {
        return new Affine2D(1, sy, sx, 1, 0, 0);
    }

    /**
     * @param inner the map applied first
     * @return {@code inner}, then this map: what drawing through this map and then through
     *         {@code inner} inside it gives
     */
    public Affine2D concat(Affine2D inner) {
        return new Affine2D(
                a * inner.a + c * inner.b,
                b * inner.a + d * inner.b,
                a * inner.c + c * inner.d,
                b * inner.c + d * inner.d,
                a * inner.e + c * inner.f + e,
                b * inner.e + d * inner.f + f);
    }

    /** @return this map preceded by a translation, as {@code Canvas.translate} composes */
    public Affine2D translate(double dx, double dy) {
        return new Affine2D(a, b, c, d, a * dx + c * dy + e, b * dx + d * dy + f);
    }

    /** @return this map preceded by a scale about the origin, as {@code Canvas.scale} composes */
    public Affine2D scale(double sx, double sy) {
        return new Affine2D(a * sx, b * sx, c * sy, d * sy, e, f);
    }

    /** @return this map preceded by a turn about the origin, as {@code Canvas.rotate} composes */
    public Affine2D rotate(double radians) {
        return concat(rotation(radians));
    }

    /** @return this map preceded by a {@linkplain #shearing shear}, as {@code Canvas.shear} composes */
    public Affine2D shear(double sx, double sy) {
        return concat(shearing(sx, sy));
    }

    /**
     * @return the area factor: zero when the map flattens the plane onto a line or a point, negative
     *         when it mirrors it
     */
    public double determinant() {
        return a * d - b * c;
    }

    /**
     * @return the map that undoes this one, which is how a point a pointer gave in the canvas's
     *         coordinates is found in the coordinates a drawing used
     * @throws ArithmeticException if the map is singular ({@link #determinant()} is zero, or so near
     *                             it that the inverse would not be finite)
     */
    public Affine2D invert() {
        double det = determinant();
        double ia = d / det;
        double ib = -b / det;
        double ic = -c / det;
        double id = a / det;
        double ie = -(ia * e + ic * f);
        double jf = -(ib * e + id * f);
        if (det == 0 || !Double.isFinite(ia) || !Double.isFinite(ib) || !Double.isFinite(ic)
                || !Double.isFinite(id) || !Double.isFinite(ie) || !Double.isFinite(jf)) {
            throw new ArithmeticException("singular Affine2D: " + this);
        }
        return new Affine2D(ia, ib, ic, id, ie, jf);
    }

    /** @return whether this is the identity, exactly */
    public boolean isIdentity() {
        return equals(IDENTITY);
    }

    /**
     * @return whether the map keeps the axes, with no turn and no shear, so that a rectangle stays an
     *         upright rectangle; a mirror along an axis keeps them
     */
    public boolean isAxisAligned() {
        return b == 0 && c == 0;
    }

    /** @return where the point lands */
    public Vec2 transformPoint(Vec2 p) {
        return new Vec2((float) (a * p.x() + c * p.y() + e), (float) (b * p.x() + d * p.y() + f));
    }

    /** @return where the direction points after the map, which moves no direction */
    public Vec2 transformDirection(Vec2 v) {
        return new Vec2((float) (a * v.x() + c * v.y()), (float) (b * v.x() + d * v.y()));
    }

    /** @return the same map in the space of a 4×4 matrix, on the plane {@code z = 0} */
    public Mat4 toMat4() {
        return Mat4.affine2D((float) a, (float) b, (float) c, (float) d, (float) e, (float) f);
    }
}

package limn.graphics;

import limn.math.Affine2D;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A gradient of any number of colours: linear between two points, radial from a centre (and, if
 * given, a focus inside its circle) out to a radius, or a sweep turning once round a centre. Each
 * colour stands at a position from 0 to 1 along the gradient, and between two positions the colour
 * runs straight from one to the next, premultiplied by its alpha, as {@link LinearGradient} and
 * {@link RadialGradient}'s two colours do, so a fade to {@link Color#TRANSPARENT} keeps its colour as
 * it thins. Two colours at one position make a hard edge. Before the first position and after the
 * last, the {@link Spread} decides: the end colours held, the gradient repeated, or repeated
 * reflected.
 *
 * <p>The gradient's points are in the coordinates of the shape it paints, before the canvas's
 * transform, as every paint's are; {@link #withTransform} places the gradient under a matrix of
 * its own first, which is how a radial gradient becomes an ellipse.
 *
 * <pre>{@code
 * Paint dawn = Gradient.linear(0, 0, 0, 200,
 *         new float[] {0, 0.6f, 1}, Color.rgb(0x1B2A49), Color.rgb(0xE8835A), Color.rgb(0xFFE3A3));
 * Paint dial = Gradient.sweep(100, 100, 0, Color.RED, Color.YELLOW, Color.GREEN, Color.RED);
 * }</pre>
 *
 * <p>Immutable; equal when every part is.
 */
public final class Gradient implements Paint {

    /**
     * The shape of a gradient.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Kind {
        /** Along the line between two points, the same across it. */
        LINEAR,
        /** Out from a centre, or from a focus inside the circle, to the circle. */
        RADIAL,
        /** Round a centre, once, from a starting angle. */
        SWEEP
    }

    /**
     * What a gradient paints past its first and last positions.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Spread {
        /** The end colours, held. */
        PAD,
        /** The gradient again, from its start. */
        REPEAT,
        /** The gradient again, backwards, then forwards, and so on. */
        REFLECT
    }

    /** The most colours one gradient takes. */
    public static final int MAX_STOPS = 256;

    private final Kind kind;
    /** Linear: x0, y0, x1, y1. Radial: cx, cy, radius, fx, fy. Sweep: cx, cy, start angle. */
    private final float[] geometry;
    private final float[] positions;
    private final Color[] colors;
    private final Spread spread;
    private final Affine2D transform;
    /** {@link #hashCode()}, once made; 0 until then. A race makes it twice, alike. */
    private int hash;

    private Gradient(Kind kind, float[] geometry, float[] positions, Color[] colors, Spread spread,
                     Affine2D transform) {
        this.kind = kind;
        this.geometry = geometry;
        this.positions = positions;
        this.colors = colors;
        this.spread = spread;
        this.transform = transform;
    }

    /**
     * A linear gradient from {@code (x0, y0)}, position 0, to {@code (x1, y1)}, position 1.
     *
     * @param positions where each colour stands, from 0 to 1, never decreasing
     * @param colors    the colours, as many as positions, at most {@link #MAX_STOPS}
     * @throws IllegalArgumentException for no colours, a count unlike the positions', a position out
     *                                  of order or out of [0, 1], or the two points the same
     */
    public static Gradient linear(float x0, float y0, float x1, float y1, float[] positions, Color... colors) {
        if (x0 == x1 && y0 == y1) {
            throw new IllegalArgumentException("a linear gradient needs two different points");
        }
        return new Gradient(Kind.LINEAR, finite(x0, y0, x1, y1), checkPositions(positions, colors),
                colors.clone(), Spread.PAD, Affine2D.IDENTITY);
    }

    /** A linear gradient whose colours stand evenly spaced, the first at 0 and the last at 1. */
    public static Gradient linear(float x0, float y0, float x1, float y1, Color... colors) {
        return linear(x0, y0, x1, y1, even(colors), colors);
    }

    /**
     * A radial gradient from its centre, position 0, out to the circle of {@code radius}, position
     * 1; {@link #withFocus} moves where position 0 stands.
     *
     * @throws IllegalArgumentException as {@link #linear(float, float, float, float, float[], Color...)}
     *                                  does, and for a radius not above 0
     */
    public static Gradient radial(float cx, float cy, float radius, float[] positions, Color... colors) {
        if (!(radius > 0)) {
            throw new IllegalArgumentException("a radial gradient's radius must be above 0: " + radius);
        }
        return new Gradient(Kind.RADIAL, finite(cx, cy, radius, cx, cy), checkPositions(positions, colors),
                colors.clone(), Spread.PAD, Affine2D.IDENTITY);
    }

    /** A radial gradient whose colours stand evenly spaced. */
    public static Gradient radial(float cx, float cy, float radius, Color... colors) {
        return radial(cx, cy, radius, even(colors), colors);
    }

    /**
     * A sweep gradient round {@code (cx, cy)}: position 0 at {@code startAngle} and the positions
     * turning with the angle, clockwise as the canvas's y grows down (from the x axis toward the y
     * axis), to 1 a full turn later, where the sweep meets its start.
     *
     * @param startAngle in radians
     * @throws IllegalArgumentException as {@link #linear(float, float, float, float, float[], Color...)}
     *                                  does for the colours
     */
    public static Gradient sweep(float cx, float cy, float startAngle, float[] positions, Color... colors) {
        return new Gradient(Kind.SWEEP, finite(cx, cy, startAngle), checkPositions(positions, colors),
                colors.clone(), Spread.PAD, Affine2D.IDENTITY);
    }

    /** A sweep gradient whose colours stand evenly spaced round the turn. */
    public static Gradient sweep(float cx, float cy, float startAngle, Color... colors) {
        return sweep(cx, cy, startAngle, even(colors), colors);
    }

    /**
     * This radial gradient with position 0 at {@code (fx, fy)} instead of its centre: the colours
     * run from the focus out to the circle, as light from a point off the middle of a sphere. A focus
     * on or past the circle is moved in along the line from the centre to just inside it, as a
     * gradient whose focus lies outside its circle is a cone that paints nothing defined.
     *
     * @throws IllegalStateException for a gradient that is not radial
     */
    public Gradient withFocus(float fx, float fy) {
        if (kind != Kind.RADIAL) {
            throw new IllegalStateException("only a radial gradient has a focus: " + kind);
        }
        finite(fx, fy);
        float cx = geometry[0];
        float cy = geometry[1];
        float r = geometry[2];
        double dx = fx - cx;
        double dy = fy - cy;
        double distance = Math.hypot(dx, dy);
        double most = r * FOCUS_INSIDE;
        if (distance > most) {
            fx = (float) (cx + dx * most / distance);
            fy = (float) (cy + dy * most / distance);
        }
        return new Gradient(kind, new float[] {cx, cy, r, fx, fy}, positions, colors, spread, transform);
    }

    /** How far toward its circle a focus may stand, as a share of the radius. */
    static final float FOCUS_INSIDE = 0.99f;

    /** This gradient with {@code spread} past its ends. */
    public Gradient withSpread(Spread spread) {
        return new Gradient(kind, geometry, positions, colors, Objects.requireNonNull(spread, "spread"), transform);
    }

    /**
     * This gradient placed under {@code transform}: a point of the shape is painted with the
     * gradient's colour at that point mapped back through the matrix.
     *
     * @throws ArithmeticException for a matrix that has no inverse
     */
    public Gradient withTransform(Affine2D transform) {
        Objects.requireNonNull(transform, "transform");
        transform.invert(); // refused here, where it is given, and not where it is drawn
        return new Gradient(kind, geometry, positions, colors, spread, transform);
    }

    /** @return the gradient's shape */
    public Kind kind() {
        return kind;
    }

    /**
     * The gradient's geometry, by its kind: for {@link Kind#LINEAR} {@code x0, y0, x1, y1}; for
     * {@link Kind#RADIAL} {@code cx, cy, radius, fx, fy}, the focus being the centre unless moved;
     * for {@link Kind#SWEEP} {@code cx, cy, startAngle}.
     *
     * @return a copy
     */
    public float[] geometry() {
        return geometry.clone();
    }

    /** @return where each colour stands, a copy */
    public float[] positions() {
        return positions.clone();
    }

    /** @return the colours */
    public List<Color> colors() {
        return List.of(colors);
    }

    /** @return what is painted past the ends */
    public Spread spread() {
        return spread;
    }

    /** @return the matrix the gradient is placed under, {@link Affine2D#IDENTITY} unless given */
    public Affine2D transform() {
        return transform;
    }

    /** @return whether every colour is opaque */
    public boolean isOpaque() {
        for (Color c : colors) {
            if (c.a() < 1f) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Gradient g && kind == g.kind && spread == g.spread && Arrays.equals(geometry, g.geometry)
                && Arrays.equals(positions, g.positions) && Arrays.equals(colors, g.colors)
                && transform.equals(g.transform);
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0) {
            // Kept: a gradient is looked up by value for every primitive painted with it.
            h = Objects.hash(kind, spread, Arrays.hashCode(geometry), Arrays.hashCode(positions),
                    Arrays.hashCode(colors), transform);
            hash = h;
        }
        return h;
    }

    @Override
    public String toString() {
        return "Gradient[" + kind + " " + Arrays.toString(geometry) + ", " + colors.length + " colours, " + spread
                + (transform.equals(Affine2D.IDENTITY) ? "" : ", " + transform) + "]";
    }

    private static float[] checkPositions(float[] positions, Color[] colors) {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(colors, "colors");
        if (colors.length == 0) {
            throw new IllegalArgumentException("a gradient needs at least one colour");
        }
        if (colors.length > MAX_STOPS) {
            throw new IllegalArgumentException("a gradient takes at most " + MAX_STOPS + " colours: " + colors.length);
        }
        if (positions.length != colors.length) {
            throw new IllegalArgumentException(positions.length + " positions for " + colors.length + " colours");
        }
        float last = 0;
        for (int i = 0; i < positions.length; i++) {
            Objects.requireNonNull(colors[i], "colors[" + i + "]");
            float p = positions[i];
            if (!(p >= 0 && p <= 1)) {
                throw new IllegalArgumentException("position " + i + " is out of [0, 1]: " + p);
            }
            if (p < last) {
                throw new IllegalArgumentException("position " + i + " comes before the one ahead of it: " + p);
            }
            last = p;
        }
        return positions.clone();
    }

    private static float[] even(Color[] colors) {
        Objects.requireNonNull(colors, "colors");
        float[] positions = new float[colors.length];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = colors.length == 1 ? 0 : (float) i / (colors.length - 1);
        }
        return positions;
    }

    private static float[] finite(float... values) {
        for (float v : values) {
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException("a gradient's geometry must be finite: " + Arrays.toString(values));
            }
        }
        return values;
    }
}

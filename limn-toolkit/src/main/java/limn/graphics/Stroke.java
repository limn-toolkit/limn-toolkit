package limn.graphics;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * How a path's outline is drawn as a line: its width, the shape of its open ends, the shape of its
 * corners, how far a sharp corner may reach, and a dash pattern. Immutable, passed with each call
 * as a paint is, so a widget that strokes one way leaves no state behind for the next.
 *
 * <p>{@code Stroke.of(2)} is a solid line two units wide with butt ends and mitred corners cut off
 * past four times the width, which are SVG's defaults; each {@code with} method returns a stroke
 * that differs in one thing: {@code Stroke.of(2).withCap(Cap.ROUND).withDash(0, 6)} is a dotted
 * line of round dots six units apart.
 *
 * <p>{@link #outline} is the stroke as a shape: the region the line covers, as a path to be filled
 * under {@link FillRule#NON_ZERO}. It is what a renderer fills to draw the stroke, what a hit test
 * on a line asks ({@code stroke.outline(path).contains(x, y)}), and what the platform's
 * {@code createStrokedShape} returns.
 */
public final class Stroke {

    /**
     * The shape at each open end of a line, and at each end of a dash.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Cap {
        /** Square, ending exactly at the end point. */
        BUTT,
        /** A half disc of the line's width about the end point. */
        ROUND,
        /** Square, reaching half the line's width past the end point. */
        SQUARE
    }

    /**
     * The shape on the outside of each corner where two segments meet.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Join {
        /**
         * The two sides extended until they meet, in a point; cut off as {@link #BEVEL} where that
         * point would be farther than the miter limit times half the width from the corner.
         */
        MITER,
        /** An arc of the line's width about the corner. */
        ROUND,
        /** The two sides' corners joined by a straight edge. */
        BEVEL
    }

    /** The miter limit of {@link #of}: SVG's default, the ratio at which a corner of 29° is cut. */
    public static final float DEFAULT_MITER_LIMIT = 4;

    /**
     * How closely {@link #outline(Path2D)} follows curves and round shapes, in the path's units: a
     * tenth of a pixel at twice the scale.
     */
    public static final float DEFAULT_TOLERANCE = 0.05f;

    /**
     * The most dashes one outline cuts a path into. Past it, a pattern far finer than the path is
     * long is not followed and the line is drawn solid, rather than building an outline of millions
     * of pieces that no screen could show apart.
     */
    public static final int MAX_DASHES = 100_000;

    private static final float[] SOLID = new float[0];

    private final float width;
    private final Cap cap;
    private final Join join;
    private final float miterLimit;
    private final float[] dashes;
    private final float dashPhase;

    private Stroke(float width, Cap cap, Join join, float miterLimit, float[] dashes, float dashPhase) {
        this.width = width;
        this.cap = cap;
        this.join = join;
        this.miterLimit = miterLimit;
        this.dashes = dashes;
        this.dashPhase = dashPhase;
    }

    /**
     * A solid stroke with {@link Cap#BUTT} ends, {@link Join#MITER} corners and the
     * {@link #DEFAULT_MITER_LIMIT}.
     *
     * @param width the line's width, in the units of the path it strokes
     * @return the stroke
     * @throws IllegalArgumentException if the width is negative or not finite
     */
    public static Stroke of(float width) {
        return new Stroke(checkWidth(width), Cap.BUTT, Join.MITER, DEFAULT_MITER_LIMIT, SOLID, 0);
    }

    /** @return the line's width; zero covers nothing */
    public float width() {
        return width;
    }

    /** @return the shape of the open ends */
    public Cap cap() {
        return cap;
    }

    /** @return the shape of the corners */
    public Join join() {
        return join;
    }

    /** @return how far a mitred corner may reach, in half widths from the corner */
    public float miterLimit() {
        return miterLimit;
    }

    /** @return a copy of the dash pattern, lengths drawn and skipped in turn; empty when solid */
    public float[] dashes() {
        return dashes.clone();
    }

    /** @return how far into the pattern the path's start lies */
    public float dashPhase() {
        return dashPhase;
    }

    /** @return whether the stroke has a dash pattern */
    public boolean isDashed() {
        return dashes.length > 0;
    }

    /**
     * @param width the new width
     * @return this stroke with another width
     * @throws IllegalArgumentException if the width is negative or not finite
     */
    public Stroke withWidth(float width) {
        return new Stroke(checkWidth(width), cap, join, miterLimit, dashes, dashPhase);
    }

    /** @return this stroke with other ends */
    public Stroke withCap(Cap cap) {
        return new Stroke(width, Objects.requireNonNull(cap, "cap"), join, miterLimit, dashes, dashPhase);
    }

    /** @return this stroke with other corners */
    public Stroke withJoin(Join join) {
        return new Stroke(width, cap, Objects.requireNonNull(join, "join"), miterLimit, dashes, dashPhase);
    }

    /**
     * @param miterLimit the ratio, at least 1, of a mitred corner's reach to half the width
     * @return this stroke with another miter limit
     * @throws IllegalArgumentException if the limit is below 1 or not finite
     */
    public Stroke withMiterLimit(float miterLimit) {
        if (!(miterLimit >= 1) || Float.isInfinite(miterLimit)) {
            throw new IllegalArgumentException("miter limit must be at least 1 and finite: " + miterLimit);
        }
        return new Stroke(width, cap, join, miterLimit, dashes, dashPhase);
    }

    /**
     * Sets a dash pattern: the lengths drawn and skipped in turn, starting with one drawn, repeated
     * along the path and started again on each subpath. A pattern of an odd count is repeated once
     * to make it even, as SVG does, so {@code withDash(4)} is four drawn, four skipped. A length of
     * zero drawn is a dot under {@link Cap#ROUND} or {@link Cap#SQUARE} and nothing under
     * {@link Cap#BUTT}. Each dash has the stroke's caps; a closed subpath whose pattern is drawn
     * across its start keeps that dash whole, with a corner there.
     *
     * @param pattern the lengths; none at all makes the stroke solid
     * @return this stroke with the pattern
     * @throws IllegalArgumentException if a length is negative or not finite, or all are zero
     */
    public Stroke withDash(float... pattern) {
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.length == 0) {
            return new Stroke(width, cap, join, miterLimit, SOLID, dashPhase);
        }
        double sum = 0;
        for (float length : pattern) {
            if (!(length >= 0) || Float.isInfinite(length)) {
                throw new IllegalArgumentException("dash lengths must be finite and not negative: "
                        + Arrays.toString(pattern));
            }
            sum += length;
        }
        if (sum == 0) {
            throw new IllegalArgumentException("a dash pattern must have a length: " + Arrays.toString(pattern));
        }
        float[] even = pattern.length % 2 == 0 ? pattern.clone() : new float[pattern.length * 2];
        if (pattern.length % 2 != 0) {
            System.arraycopy(pattern, 0, even, 0, pattern.length);
            System.arraycopy(pattern, 0, even, pattern.length, pattern.length);
        }
        return new Stroke(width, cap, join, miterLimit, even, dashPhase);
    }

    /**
     * @param phase how far into the pattern the path's start lies; a positive phase moves the
     *              pattern back along the path, as SVG's {@code stroke-dashoffset} does
     * @return this stroke with the phase
     * @throws IllegalArgumentException if the phase is not finite
     */
    public Stroke withDashPhase(float phase) {
        if (!Float.isFinite(phase)) {
            throw new IllegalArgumentException("dash phase must be finite: " + phase);
        }
        return new Stroke(width, cap, join, miterLimit, dashes, phase);
    }

    /**
     * The region this stroke covers along a path, as a path to fill under {@link FillRule#NON_ZERO}
     * (the rule it carries), following curves within {@link #DEFAULT_TOLERANCE}.
     *
     * @param path the path to stroke, in its own units
     * @return a new path
     */
    public Path2D outline(Path2D path) {
        return outline(path, DEFAULT_TOLERANCE);
    }

    /**
     * The region this stroke covers along a path, following curves, round ends and round corners
     * within {@code tolerance}. The outline overlaps itself where the line does, at the inside of
     * each corner and where the path crosses itself; filled under non-zero, every part is covered
     * once.
     *
     * @param path      the path to stroke, in its own units
     * @param tolerance how far the outline may stray from the true curve, in the same units; a
     *                  renderer passes about a quarter of a device pixel
     * @return a new path, under {@link FillRule#NON_ZERO}
     * @throws IllegalArgumentException if the tolerance is not above 0 or not finite
     */
    public Path2D outline(Path2D path, float tolerance) {
        Objects.requireNonNull(path, "path");
        if (!(tolerance > 0) || Float.isInfinite(tolerance)) {
            throw new IllegalArgumentException("tolerance must be above 0 and finite: " + tolerance);
        }
        return new Stroker(this, tolerance).stroke(path);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Stroke s && Float.compare(width, s.width) == 0 && cap == s.cap && join == s.join
                && Float.compare(miterLimit, s.miterLimit) == 0 && Arrays.equals(dashes, s.dashes)
                && Float.compare(dashPhase, s.dashPhase) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, cap, join, miterLimit, Arrays.hashCode(dashes), dashPhase);
    }

    @Override
    public String toString() {
        return "Stroke[width=" + width + ", cap=" + cap + ", join=" + join + ", miterLimit=" + miterLimit
                + (dashes.length > 0 ? ", dashes=" + Arrays.toString(dashes) + ", dashPhase=" + dashPhase : "")
                + "]";
    }

    private static float checkWidth(float width) {
        if (!(width >= 0) || Float.isInfinite(width)) {
            throw new IllegalArgumentException("stroke width must be finite and not negative: " + width);
        }
        return width;
    }
}

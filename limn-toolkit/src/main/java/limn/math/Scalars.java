package limn.math;

/**
 * The handful of scalar helpers every module reaches for &mdash; clamp, lerp, smoothstep &mdash;
 * in one place, with one answer each.
 *
 * <p>They used to be private statics: six copies of {@code clamp}, three of {@code clamp01} with
 * three different bodies, and the three did not agree on the one input that matters, {@code NaN}.
 * {@code Math.min(1, Math.max(0, NaN))} is {@code NaN}, and a {@code NaN} that leaves a clamp
 * goes on to a gain, an alpha, a progress fraction, where it is not "out of range" but poison.
 * So {@link #clamp01(float)} answers {@code 0} for {@code NaN}, and that is the rule for all of
 * them: a clamp never lets {@code NaN} through.
 *
 * <p>The inline {@code Math.max(lo, Math.min(hi, v))} that fills widget code is not what this
 * replaces. It is idiomatic, it reads at a glance, and it is right. What this replaces is a
 * private method with a name, whose body a reader has to go and check.
 */
public final class Scalars {

    private Scalars() {
    }

    /**
     * {@code v} held to {@code [lo, hi]}. {@code NaN} answers {@code lo}. A range with
     * {@code hi < lo} answers {@code lo}: there is no value in an empty range, and the lower bound
     * is the one every caller that hits this case wanted.
     *
     * @param v  the value
     * @param lo the lower bound, inclusive
     * @param hi the upper bound, inclusive
     * @return the nearest value in the range
     */
    public static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v) || v < lo || hi < lo) {
            return lo;
        }
        return v > hi ? hi : v;
    }

    /**
     * {@code v} held to {@code [lo, hi]}. A range with {@code hi < lo} answers {@code lo}.
     *
     * @param v  the value
     * @param lo the lower bound, inclusive
     * @param hi the upper bound, inclusive
     * @return the nearest value in the range
     */
    public static int clamp(int v, int lo, int hi) {
        if (v < lo || hi < lo) {
            return lo;
        }
        return v > hi ? hi : v;
    }

    /**
     * {@code v} held to {@code [0, 1]}; {@code NaN} answers {@code 0}.
     *
     * @param v the value
     * @return the nearest value in the unit range
     */
    public static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    /**
     * {@code v} held to {@code [0, 1]}; {@code NaN} answers {@code 0}.
     *
     * @param v the value
     * @return the nearest value in the unit range
     */
    public static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0) {
            return 0;
        }
        return v > 1 ? 1 : v;
    }

    /**
     * Linear interpolation: {@code a} at {@code t = 0}, {@code b} at {@code t = 1}, and beyond
     * either for a {@code t} outside the unit range &mdash; this does not clamp, because an
     * animation that overshoots is asking for exactly that.
     *
     * @param a the value at zero
     * @param b the value at one
     * @param t the fraction
     * @return {@code a + (b - a) * t}
     */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * The smooth ease-in-out ramp {@code 3t² − 2t³}, zero velocity at both ends, with {@code t}
     * held to the unit range first so that the curve never leaves it.
     *
     * @param t the fraction
     * @return the eased fraction, in {@code [0, 1]}
     */
    public static float smoothstep(float t) {
        float x = clamp01(t);
        return x * x * (3f - 2f * x);
    }
}

package limn.animation;

/**
 * An easing curve: maps a normalized time {@code t} in {@code [0,1]} to an eased
 * progress (usually in {@code [0,1]}, though some curves may overshoot). The
 * {@link Transition} feeds it {@code t = elapsed / duration} and uses the result
 * to interpolate. Pick one to shape how an animation accelerates and decelerates,
 * mirroring the CSS {@code transition-timing-function}.
 */
@FunctionalInterface
public interface Easing {

    /** @param t normalized time in {@code [0,1]} @return the eased progress */
    float apply(float t);

    /** Constant speed: {@code linear}. */
    Easing LINEAR = t -> t;

    /** Slow start, fast end: {@code ease-in} (cubic). */
    Easing EASE_IN = t -> t * t * t;

    /** Fast start, slow end: {@code ease-out} (cubic). The most natural for UI reveals. */
    Easing EASE_OUT = t -> {
        float u = 1 - t;
        return 1 - u * u * u;
    };

    /** Slow at both ends: {@code ease-in-out} (cubic). */
    Easing EASE_IN_OUT = t -> {
        if (t < 0.5f) {
            return 4 * t * t * t;
        }
        float u = -2 * t + 2;
        return 1 - u * u * u / 2;
    };

    /** Overshoots past the end then settles: a springy "rubber band" (elastic-out). */
    Easing RUBBER = t -> {
        if (t <= 0) {
            return 0;
        }
        if (t >= 1) {
            return 1;
        }
        float period = (float) (2 * Math.PI / 3);
        return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75f) * period) + 1);
    };

    /**
     * Settles onto the end with a few decaying bounces (bounce-out): a ball
     * dropping onto a floor.
     */
    Easing BOUNCE = t -> {
        float n = 7.5625f;
        float d = 2.75f;
        float x = t;
        if (x < 1 / d) {
            return n * x * x;
        }
        if (x < 2 / d) {
            x -= 1.5f / d;
            return n * x * x + 0.75f;
        }
        if (x < 2.5f / d) {
            x -= 2.25f / d;
            return n * x * x + 0.9375f;
        }
        x -= 2.625f / d;
        return n * x * x + 0.984375f;
    };
}

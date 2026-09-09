package limn.lang;

/**
 * Argument checks with one vocabulary, so that every "must be" in the toolkit is spelled the
 * same way and answers with the same shape: what was checked, what it had to be, what it was.
 *
 * <p>{@code java.util.Objects.requireNonNull} is the model. These are its numeric siblings: a
 * value in a range, a value with a floor, a positive value, a finite one, a size with two sides.
 * Each returns the value so that it can be checked where it is assigned. Each throws
 * {@link IllegalArgumentException} with a message of the form
 * <pre>name must be in [lo..hi], got value</pre>
 * where the bounds are written as the shortest decimal that names them &mdash; {@code [0..1]},
 * {@code [0.25..4]} &mdash; and never as {@code 1.0}.
 *
 * <p>{@code NaN} fails every check, including the ones a plain comparison would let it pass:
 * {@code NaN < 0} is false, so a hand-written {@code if (v < 0) throw} accepts it, and three
 * loaders in this toolkit once did. A value that is {@code NaN} is never in range, never at least
 * anything, never positive.
 */
public final class Checks {

    private Checks() {
    }

    /**
     * @param value the value
     * @param lo    the lower bound, inclusive
     * @param hi    the upper bound, inclusive
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is outside {@code [lo, hi]}
     */
    public static int inRange(int value, int lo, int hi, String name) {
        if (value < lo || value > hi) {
            throw new IllegalArgumentException(
                    name + " must be in [" + lo + ".." + hi + "], got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param lo    the lower bound, inclusive
     * @param hi    the upper bound, inclusive
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is outside {@code [lo, hi]} or is {@code NaN}
     */
    public static float inRange(float value, float lo, float hi, String name) {
        if (!(value >= lo && value <= hi)) {
            throw new IllegalArgumentException(
                    name + " must be in [" + bound(lo) + ".." + bound(hi) + "], got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param min   the floor, inclusive
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is below {@code min}
     */
    public static int atLeast(int value, int min, String name) {
        if (value < min) {
            throw new IllegalArgumentException(name + " must be at least " + min + ", got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param min   the floor, inclusive
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is below {@code min} or is {@code NaN}
     */
    public static float atLeast(float value, float min, String name) {
        if (!(value >= min)) {
            throw new IllegalArgumentException(
                    name + " must be at least " + bound(min) + ", got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is negative
     */
    public static int notNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative, got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is negative
     */
    public static long notNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative, got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is negative, {@code NaN} or infinite
     */
    public static float notNegative(float value, String name) {
        if (!(value >= 0) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(
                    name + " must not be negative" + finiteClause(value) + ", got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is negative, {@code NaN} or infinite
     */
    public static double notNegative(double value, String name) {
        if (!(value >= 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(
                    name + " must not be negative" + (Double.isFinite(value) ? "" : " and must be finite")
                    + ", got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is zero or negative
     */
    public static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is zero or negative
     */
    public static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is zero, negative, {@code NaN} or infinite
     */
    public static float positive(float value, String name) {
        if (!(value > 0) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(
                    name + " must be positive" + finiteClause(value) + ", got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is zero, negative, {@code NaN} or infinite
     */
    public static double positive(double value, String name) {
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(
                    name + " must be positive" + (Double.isFinite(value) ? "" : " and must be finite")
                    + ", got " + value);
        }
        return value;
    }

    /**
     * @param value the value
     * @param name  what the value is, for the message
     * @return the value
     * @throws IllegalArgumentException if it is {@code NaN} or infinite
     */
    public static float finite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
        return value;
    }

    /**
     * A size with two sides, both of which must be positive.
     *
     * @param width  the width
     * @param height the height
     * @param name   what the size is, for the message: "window size", "image size"
     * @throws IllegalArgumentException if either side is zero or negative
     */
    public static void positiveSize(int width, int height, String name) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + width + "x" + height);
        }
    }

    /**
     * A size with two sides, neither of which may be negative: a framebuffer can be zero by zero
     * while a window is minimized.
     *
     * @param width  the width
     * @param height the height
     * @param name   what the size is, for the message
     * @throws IllegalArgumentException if either side is negative
     */
    public static void notNegativeSize(int width, int height, String name) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException(
                    name + " must not be negative, got " + width + "x" + height);
        }
    }

    /**
     * An object that is asked something after it was closed.
     *
     * @param closed whether it has been closed
     * @param what   the object, for the message: "this MediaPlayer", "the backend"
     * @throws IllegalStateException if it has
     */
    public static void notClosed(boolean closed, String what) {
        if (closed) {
            throw new IllegalStateException(what + " is closed");
        }
    }

    /**
     * An object that is asked something after it was disposed: the same refusal as
     * {@link #notClosed} for the things whose verb is dispose, a mesh, a surface, a target.
     *
     * @param disposed whether it has been disposed
     * @param what     the object, for the message
     * @throws IllegalStateException if it has
     */
    public static void notDisposed(boolean disposed, String what) {
        if (disposed) {
            throw new IllegalStateException(what + " has been disposed");
        }
    }

    /**
     * A handler slot being registered over: the one null policy every fluent {@code onX} in the
     * toolkit has. <b>{@code null} clears the slot, and a non-null handler registered over an
     * occupied one throws</b>, because a widget has one application and being operated has one
     * response -- a second party that wants to hear the change is observing, and observation has
     * its own channel, {@code observeChanges}. Silently replacing the first handler is how a test
     * fixture's listener was destroyed by one test method in this repository without anything
     * failing.
     *
     * <p>Re-binding a handler over its life is {@code w.onAction(null).onAction(next)}; a widget
     * whose handler must change often holds a mutable field and registers
     * {@code () -> current.run()}.
     *
     * @param current the handler the slot holds, or null
     * @param next    the handler being registered, or null to clear
     * @param method  the registrar, for the message: "Slider.onChange"
     * @param <H>     the handler type
     * @return {@code next}, to assign to the slot
     * @throws IllegalStateException if both are non-null
     */
    public static <H> H handlerSlot(H current, H next, String method) {
        if (current != null && next != null) {
            throw new IllegalStateException(method + " already has a handler: pass null to clear "
                    + "it first, or watch the widget with observeChanges instead");
        }
        return next;
    }

    /** The shortest decimal that names a bound: {@code 1} and not {@code 1.0}, {@code 0.25} as is. */
    private static String bound(float value) {
        return value == (long) value ? Long.toString((long) value) : Float.toString(value);
    }

    private static String finiteClause(float value) {
        return Float.isFinite(value) ? "" : " and must be finite";
    }
}

package limn.scene;

import limn.internal.lang.Checks;

/**
 * Edge insets in logical points (padding/margins).
 *
 * <p>Each side is a finite distance of zero or more. A side that is negative, {@code NaN} or
 * infinite is refused where the insets are made, by the side's name, because nothing downstream
 * would refuse it: layout subtracts the insets from its constraints and floors the result at
 * zero, and a {@code NaN} survives that floor, so a padding around a {@code NaN} side measured
 * {@code NaN} by {@code NaN} with no error anywhere, and an infinite side left the child no room
 * at all while the padding took all of its parent's.
 */
public record Insets(float top, float right, float bottom, float left) {

    public static final Insets NONE = new Insets(0, 0, 0, 0);

    /**
     * Insets of these four sides, in logical points.
     *
     * @throws IllegalArgumentException when a side is negative, {@code NaN} or infinite, naming
     *                                  the side and its value
     */
    public Insets {
        Checks.notNegative(top, "top inset");
        Checks.notNegative(right, "right inset");
        Checks.notNegative(bottom, "bottom inset");
        Checks.notNegative(left, "left inset");
    }

    /** The same inset on all four sides. */
    public static Insets all(float value) {
        return new Insets(value, value, value, value);
    }

    /** One inset for top and bottom, another for left and right. */
    public static Insets symmetric(float vertical, float horizontal) {
        return new Insets(vertical, horizontal, vertical, horizontal);
    }
}

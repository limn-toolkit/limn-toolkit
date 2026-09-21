package limn.components.a11y;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;

/**
 * The {@code VALUE} shape, written once (ADR 045 §3): a node that carries a number in a range —
 * a slider, a spin button, a scroll bar, a splitter, a progress bar — publishes the number, its
 * bounds and its step, offers {@code INCREMENT} and {@code DECREMENT} unless it is read-only,
 * and accepts {@code SET_VALUE} with a finite number, which is implied by the facet and never
 * listed (ADR 039 §1.9: a verb that takes an argument is not published as a verb).
 *
 * <p>The rules: a verb on a disabled node is refused; {@code INCREMENT} and {@code DECREMENT}
 * move by the widget's own step, which the widget clamps as its keys do; {@code SET_VALUE} with
 * an argument that is not a finite number is refused, and one that is goes through the same
 * clamp; every change is made in the name of the user, because a reader is one. A read-only
 * value offers and accepts nothing.
 */
public final class ValueAccessibility {

    /** The widget's own mechanisms over its number. */
    public interface Host {

        /** @return whether the widget takes input at all */
        boolean isEnabled();

        /** @return whether the number is shown and never set: a progress bar */
        default boolean isReadOnly() {
            return false;
        }

        /**
         * Moves the number by one step, as the arrow keys do, clamped by the widget.
         *
         * @param direction +1 or -1
         */
        void step(int direction);

        /**
         * Sets the number, clamped by the widget, as a drag or a typed value does.
         *
         * @param asked a finite number
         */
        void set(double asked);

        /**
         * Reads the number a {@code SET_VALUE} carries: a finite number, or NaN when the argument
         * is not one. A widget that accepts typed text (a spin button) parses it here.
         */
        default double numberOf(Accessible.Argument arg) {
            return Accessible.Argument.finiteValueOf(arg);
        }
    }

    private ValueAccessibility() {
    }

    /**
     * Publishes the number, its bounds and its step, and the two step verbs unless read-only.
     *
     * @param a        the builder, positioned on the node
     * @param value    the number
     * @param min      the least value
     * @param max      the greatest value
     * @param step     what one increment moves by
     * @param readOnly whether the number is shown and never set
     */
    public static void describe(Accessibility a, double value, double min, double max,
                                double step, boolean readOnly) {
        a.value(value, min, max, step, readOnly);
        if (!readOnly) {
            a.action(Action.INCREMENT, Action.DECREMENT);
        }
    }

    /**
     * Performs a verb a reader sent to the value, by the rules above.
     *
     * @param host the widget's mechanisms
     * @param verb the verb
     * @param arg  its argument; read only for {@code SET_VALUE}
     * @return whether it was performed
     */
    public static boolean perform(Host host, Action verb, Accessible.Argument arg) {
        if (!host.isEnabled() || host.isReadOnly()) {
            return false;
        }
        switch (verb) {
            case INCREMENT -> {
                host.step(1);
                return true;
            }
            case DECREMENT -> {
                host.step(-1);
                return true;
            }
            case SET_VALUE -> {
                double asked = host.numberOf(arg);
                if (Double.isNaN(asked)) {
                    return false;
                }
                host.set(asked);
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}

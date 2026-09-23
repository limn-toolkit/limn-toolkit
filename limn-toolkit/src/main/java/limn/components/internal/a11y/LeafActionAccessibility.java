package limn.components.internal.a11y;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible.Action;

/**
 * The {@code LEAF_ACTION} shape, written once (ADR 045 §3): a leaf that can be pressed and is
 * nothing else — a button, a spinner's arrow, a media control — offers {@code PRESS} exactly
 * while a press would be accepted, and performs it through the path a click and Space take, so
 * the application is notified the same way. A press is refused while the node is disabled or
 * while a press would not be accepted, because the answer has to be truthful; it never arms,
 * invalidates or takes focus, because a reader that wants focus has the focus verb.
 */
public final class LeafActionAccessibility {

    /** The widget's own mechanisms over its one gesture. */
    public interface Host {

        /** @return whether a press would be accepted now: enabled, and with something to do */
        boolean acceptsPress();

        /** Presses, as a click does, in the name of the user. */
        void press();
    }

    private LeafActionAccessibility() {
    }

    /**
     * Publishes the verb while a press would be accepted.
     *
     * @param a        the builder, positioned on the node
     * @param accepted whether a press would be accepted now
     */
    public static void describe(Accessibility a, boolean accepted) {
        if (accepted) {
            a.action(Action.PRESS);
        }
    }

    /**
     * Performs a verb a reader sent to the leaf.
     *
     * @param host the widget's mechanisms
     * @param verb the verb
     * @return whether it was performed: only {@code PRESS}, and only while accepted
     */
    public static boolean perform(Host host, Action verb) {
        if (verb != Action.PRESS || !host.acceptsPress()) {
            return false;
        }
        host.press();
        return true;
    }
}

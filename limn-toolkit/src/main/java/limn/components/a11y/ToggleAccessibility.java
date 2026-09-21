package limn.components.a11y;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible.Action;
import limn.accessibility.ToggleFacet;

/**
 * The {@code TOGGLE} shape, written once (ADR 045 §3): a node that is on or off — a check box, a
 * switch, a chart's series in its legend — publishes its state as the toggle facet, from which
 * the checked bit is derived so the two cannot disagree, and offers {@code TOGGLE} while it can
 * be flipped. The verb flips it through the path a click takes, so the application's handler
 * hears a user, and is refused while the node is disabled or the flip is not on offer.
 */
public final class ToggleAccessibility {

    /** The widget's own mechanisms over its flag. */
    public interface Host {

        /** @return whether the node takes input and the flip is on offer */
        boolean canToggle();

        /** Flips the flag as a click does, in the name of the user. */
        void toggle();
    }

    private ToggleAccessibility() {
    }

    /**
     * Publishes the state and, where it can be flipped, the verb.
     *
     * @param a        the builder, positioned on the node
     * @param on       whether the node is on
     * @param operable whether the flip is on offer
     */
    public static void describe(Accessibility a, boolean on, boolean operable) {
        a.toggle(on ? ToggleFacet.State.ON : ToggleFacet.State.OFF);
        if (operable) {
            a.action(Action.TOGGLE);
        }
    }

    /**
     * Performs a verb a reader sent to the toggle.
     *
     * @param host the widget's mechanisms
     * @param verb the verb
     * @return whether it was performed: only {@code TOGGLE}, and only while it can be
     */
    public static boolean perform(Host host, Action verb) {
        if (verb != Action.TOGGLE || !host.canToggle()) {
            return false;
        }
        host.toggle();
        return true;
    }
}

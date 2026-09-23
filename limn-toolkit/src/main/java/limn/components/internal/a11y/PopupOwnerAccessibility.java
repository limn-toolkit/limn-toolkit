package limn.components.internal.a11y;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;

/**
 * The {@code POPUP_OWNER} shape, written once: a node that opens something — a
 * combo box's list, a date field's calendar, a colour picker's panel, a context region's menu —
 * says so with {@code HAS_POPUP} before anything has happened, publishes whether it is open as
 * the expand facet, from which the expanded bit is derived so the two cannot disagree, and
 * offers one verb and not both: {@code EXPAND} while closed, {@code COLLAPSE} while open,
 * because the other is what the node is already doing. A region whose popup is a menu offers
 * {@code SHOW_MENU} instead and has no open state to publish. What the popup contains is
 * described where it lives and gated by the layer it opens in.
 */
public final class PopupOwnerAccessibility {

    /** The widget's own mechanisms over its popup. */
    public interface Host {

        /** @return whether the popup is open now */
        boolean isOpen();

        /** Opens the popup as the user's gesture does. */
        void open();

        /** Closes the popup as the user's gesture does. */
        void close();
    }

    private PopupOwnerAccessibility() {
    }

    /**
     * Publishes the popup bit, the open state and the one verb the state allows.
     *
     * @param a    the builder, positioned on the owner
     * @param open whether the popup is open
     */
    public static void describe(Accessibility a, boolean open) {
        a.state(Accessible.State.HAS_POPUP);
        a.expand(open);
        a.action(open ? Action.COLLAPSE : Action.EXPAND);
    }

    /**
     * Publishes the popup bit and {@code SHOW_MENU} for an owner whose popup is a menu and
     * which has no open state of its own.
     *
     * @param a the builder, positioned on the owner
     */
    public static void describeMenuOwner(Accessibility a) {
        a.state(Accessible.State.HAS_POPUP);
        a.action(Action.SHOW_MENU);
    }

    /**
     * Performs a verb a reader sent to the owner: {@code EXPAND} opens a closed popup,
     * {@code COLLAPSE} closes an open one, and each is refused in the other state, because
     * nothing was done and saying otherwise is a lie.
     *
     * @param host the widget's mechanisms
     * @param verb the verb
     * @return whether it was performed
     */
    public static boolean perform(Host host, Action verb) {
        switch (verb) {
            case EXPAND -> {
                if (host.isOpen()) {
                    return false;
                }
                host.open();
                return true;
            }
            case COLLAPSE -> {
                if (!host.isOpen()) {
                    return false;
                }
                host.close();
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}

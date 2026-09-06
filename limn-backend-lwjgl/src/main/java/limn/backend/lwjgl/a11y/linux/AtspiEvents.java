package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;

/**
 * Turns one of the toolkit's events into the signal a client on this platform is listening for.
 *
 * <p><b>A reader is told, and does not have to look.</b> Everything else this bridge does answers a
 * question a client thought to ask; these are the pushes it waits on. Orca in particular registers
 * for {@code object:state-changed:focused} and then calls nothing at all until one arrives, so a
 * bridge that publishes a perfect tree and emits nothing is a bridge that goes quiet exactly when
 * the interface is being used.
 *
 * <p>Every AT-SPI event has the same shape whatever it means: a detail string, two integers, a
 * value, and the application it came from. What varies is the interface and member it is sent as,
 * and the detail string, which is the part a client subscribes by.
 *
 * <p>Not every event the toolkit raises has somewhere to go here, and the ones that do not return
 * {@code null} rather than something approximate. An announcement is one: it is a message to the
 * user rather than a fact about a node, and this platform carries it through a different mechanism
 * than the object events, which is its own step.
 */
final class AtspiEvents {

    private AtspiEvents() {
    }

    /** Where object events are sent from and subscribed to. */
    static final String I_EVENT_OBJECT = "org.a11y.atspi.Event.Object";
    /** Window lifecycle, which a desktop shell watches rather than a screen reader. */
    static final String I_EVENT_WINDOW = "org.a11y.atspi.Event.Window";

    /** One signal: which member of which interface, and the four values it carries. */
    record Signal(String iface, String member, String detail, int detail1, int detail2,
                  DBus.Variant value) {
    }

    /**
     * The signal {@code event} is, or {@code null} when this platform has no push for it.
     *
     * @param event what the difference between two published trees found
     * @return the signal to send, or {@code null}
     */
    static Signal of(AccessibleEvent event) {
        return switch (event.type()) {
            // Nothing. Focus is a state change on this platform -- the dedicated Focus signal is
            // deprecated and Orca subscribes to object:state-changed:focused -- and the difference
            // ALSO raises STATE_CHANGED for the FOCUSED bit, on both the node gaining it and the
            // node losing it. Mapping this one too sent the arrival twice and the departure once,
            // so a reader announced the newly focused control and then announced it again.
            case FOCUS_CHANGED -> null;
            case STATE_CHANGED -> stateChanged(event);
            case NAME_CHANGED -> new Signal(I_EVENT_OBJECT, "PropertyChange", "accessible-name",
                    0, 0, new DBus.Variant("s", string(event.newValue())));
            case DESCRIPTION_CHANGED -> new Signal(I_EVENT_OBJECT, "PropertyChange",
                    "accessible-description", 0, 0,
                    new DBus.Variant("s", string(event.newValue())));
            case VALUE_CHANGED -> new Signal(I_EVENT_OBJECT, "PropertyChange", "accessible-value",
                    0, 0, new DBus.Variant("d", number(event.newValue())));
            case BOUNDS_CHANGED -> new Signal(I_EVENT_OBJECT, "BoundsChanged", "", 0, 0,
                    new DBus.Variant("i", 0));
            // Structure and destruction are both "the children of something moved" here: the
            // platform has no separate word for a node that ceased to exist, and a client answers
            // both by re-reading the subtree.
            case STRUCTURE_CHANGED -> new Signal(I_EVENT_OBJECT, "ChildrenChanged", "", 0, 0,
                    new DBus.Variant("i", 0));
            case NODE_DESTROYED -> new Signal(I_EVENT_OBJECT, "ChildrenChanged", "remove", 0, 0,
                    new DBus.Variant("i", 0));
            case SELECTION_CHANGED -> new Signal(I_EVENT_OBJECT, "SelectionChanged", "", 0, 0,
                    new DBus.Variant("i", 0));
            case ACTIVE_DESCENDANT_CHANGED -> new Signal(I_EVENT_OBJECT,
                    "ActiveDescendantChanged", "", 0, 0, new DBus.Variant("i", 0));
            case TEXT_CHANGED -> new Signal(I_EVENT_OBJECT, "TextChanged",
                    event.inserted() > 0 ? "insert" : "delete", event.offset(),
                    Math.max(event.inserted(), event.removed()),
                    new DBus.Variant("s", string(event.newValue())));
            case CARET_MOVED -> new Signal(I_EVENT_OBJECT, "TextCaretMoved", "", event.offset(), 0,
                    new DBus.Variant("i", 0));
            case TEXT_SELECTION_CHANGED -> new Signal(I_EVENT_OBJECT, "TextSelectionChanged", "",
                    0, 0, new DBus.Variant("i", 0));
            case WINDOW_OPENED -> new Signal(I_EVENT_WINDOW, "Create", "", 0, 0,
                    new DBus.Variant("s", ""));
            case WINDOW_CLOSED -> new Signal(I_EVENT_WINDOW, "Destroy", "", 0, 0,
                    new DBus.Variant("s", ""));
            case WINDOW_ACTIVATED -> new Signal(I_EVENT_WINDOW, "Activate", "", 0, 0,
                    new DBus.Variant("s", ""));
            case WINDOW_DEACTIVATED -> new Signal(I_EVENT_WINDOW, "Deactivate", "", 0, 0,
                    new DBus.Variant("s", ""));
            // Everything else: an announcement is a message rather than a node's fact, and the
            // remaining kinds are the toolkit's own bookkeeping. Nothing approximate is sent.
            default -> null;
        };
    }

    /**
     * A state change, whose detail is the platform's own name for the bit and whose first integer
     * says whether it went on or off.
     */
    private static Signal stateChanged(AccessibleEvent event) {
        Accessible.State state = event.state();
        if (state == null || AtspiStates.bitOf(state) == null) {
            return null;
        }
        boolean on = Boolean.TRUE.equals(event.newValue());
        return new Signal(I_EVENT_OBJECT, "StateChanged", detailOf(state), on ? 1 : 0, 0,
                new DBus.Variant("i", 0));
    }

    /**
     * The platform's subscription name for a state: lower case with words separated by hyphens,
     * which is what a client's {@code object:state-changed:<detail>} match string carries.
     */
    private static String detailOf(Accessible.State state) {
        return switch (state) {
            case READ_ONLY -> "read-only";
            case MULTI_LINE -> "multi-line";
            case HAS_POPUP -> "has-popup";
            case DEFAULT -> "is-default";
            case MIXED -> "indeterminate";
            case INVALID -> "invalid-entry";
            default -> state.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /**
     * The body every AT-SPI event carries: {@code (siiv(so))} -- detail, two integers, the value,
     * and the application it came from.
     *
     * @param signal what to send
     * @param sender the application object's reference
     * @return the marshalled arguments
     */
    static Object[] body(Signal signal, DBus.Ref sender) {
        return new Object[] {
                signal.detail(), signal.detail1(), signal.detail2(), signal.value(),
                sender.toStruct(),
        };
    }

    /**
     * The signature every one of these signals carries.
     *
     * <p>Five arguments and no more. A trailing property dictionary looks plausible -- other
     * things on this bus carry one -- and at-spi2 rejects the whole signal for it: the client
     * receives the message, refuses to decode it, and logs an invalid-signature warning that names
     * the interface and not the sender, so the application looks silent and correct at once.
     */
    static final String SIGNATURE = "siiv(so)";
}

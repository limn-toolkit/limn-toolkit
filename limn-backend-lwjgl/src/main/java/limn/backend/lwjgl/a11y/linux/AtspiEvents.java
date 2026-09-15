package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;

import java.util.List;

/**
 * Turns one of the toolkit's events into the signals a client on this platform is listening for.
 *
 * <p><b>A reader is told, and does not have to look.</b> Everything else this bridge does answers a
 * question a client thought to ask; these are the pushes it waits on. Orca in particular registers
 * for {@code object:state-changed:focused} and then calls nothing at all until one arrives, so a
 * bridge that publishes a perfect tree and emits nothing is a bridge that goes quiet exactly when
 * the interface is being used.
 *
 * <p>Every AT-SPI event has the same shape whatever it means: a detail string, two integers, a
 * value, and the application it came from, sent from the object it is about. What varies is the
 * interface and member it is sent as, the detail string, which is the part a client subscribes by,
 * and the value, which libatspi 2.60.6 turns into the event's {@code any_data} only when it is a
 * struct or a string ({@code _atspi_dbus_handle_event},
 * readings/upstream-at-spi2-core-2.60.6-libatspi.txt): an {@code i} arrives as nothing at all.
 *
 * <p>Not every event the toolkit raises has somewhere to go here, and the ones that do not map to
 * no signal rather than something approximate.
 */
final class AtspiEvents {

    private AtspiEvents() {
    }

    /** Where object events are sent from and subscribed to. */
    static final String I_EVENT_OBJECT = "org.a11y.atspi.Event.Object";
    /** Window lifecycle, which a desktop shell watches rather than a screen reader. */
    static final String I_EVENT_WINDOW = "org.a11y.atspi.Event.Window";

    /**
     * What a mapping reads besides the event: the tree the event was handed with, and how this
     * application names a node on the bus. The event carries identifiers; the reference, the index
     * a client inserts at and the text a client speaks are read off the tree, which on the
     * user-interface thread is the tree the event describes.
     */
    interface Context {
        /** @return the tree published by the window that raised the event, just before it */
        AccessibleTree tree();

        /** @return the application object's reference, which every event body carries */
        DBus.Ref application();

        /** @return the reference a node has on this bus, whichever window holds it */
        DBus.Ref refOf(long id);

        /** @return the null object's reference on this bus */
        DBus.Ref nullRef();

        /**
         * @return where a node stands among its parent's children, in whichever window holds it
         *         (a window's own node among the application's frames), or -1 when no window does
         */
        int indexInParent(long id);
    }

    /**
     * One signal as it goes on the wire: the object it is sent from, its interface and member, its
     * signature and body.
     */
    record Signal(String path, String iface, String member, String signature, Object[] body) {

        /** @return the detail string of an event signal */
        String detail() {
            return (String) body[0];
        }

        /** @return the first integer of an event signal */
        int detail1() {
            return (Integer) body[1];
        }

        /** @return the second integer of an event signal */
        int detail2() {
            return (Integer) body[2];
        }

        /** @return the value of an event signal, which a client reads as {@code any_data} */
        DBus.Variant value() {
            return (DBus.Variant) body[3];
        }
    }

    /**
     * An event signal: {@code (siiv(so))} from {@code path}.
     */
    static Signal event(Context context, String path, String iface, String member, String detail,
                        int detail1, int detail2, DBus.Variant value) {
        return new Signal(path, iface, member, SIGNATURE, new Object[] {
                detail, detail1, detail2, value, context.application().toStruct(),
        });
    }

    /**
     * The signals {@code event} is, in the order they are sent; empty when this platform has no
     * push for it.
     *
     * @param event   what the difference between two published trees found
     * @param context the tree it was handed with and the bus's names
     * @return the signals to send
     */
    static List<Signal> of(AccessibleEvent event, Context context) {
        long subject = subjectOf(event, context);
        String path = subject == 0 ? Atspi.PATH_ROOT : context.refOf(subject).path;
        Signal one = switch (event.type()) {
            // Nothing. Focus is a state change on this platform -- the dedicated Focus signal is
            // deprecated and Orca subscribes to object:state-changed:focused -- and the difference
            // ALSO raises STATE_CHANGED for the FOCUSED bit, on both the node gaining it and the
            // node losing it. Mapping this one too sent the arrival twice and the departure once,
            // so a reader announced the newly focused control and then announced it again.
            case FOCUS_CHANGED -> null;
            case STATE_CHANGED -> stateChanged(event, context, path);
            case NAME_CHANGED -> event(context, path, I_EVENT_OBJECT, "PropertyChange",
                    "accessible-name", 0, 0, new DBus.Variant("s", string(event.newValue())));
            case DESCRIPTION_CHANGED -> event(context, path, I_EVENT_OBJECT, "PropertyChange",
                    "accessible-description", 0, 0,
                    new DBus.Variant("s", string(event.newValue())));
            case VALUE_CHANGED -> event(context, path, I_EVENT_OBJECT, "PropertyChange",
                    "accessible-value", 0, 0, new DBus.Variant("d", number(event.newValue())));
            case BOUNDS_CHANGED -> boundsChanged(event, context, path);
            // Structure and destruction are both "the children of something moved" here: the
            // platform has no separate word for a node that ceased to exist, and a client answers
            // both by re-reading the subtree.
            case STRUCTURE_CHANGED -> event(context, path, I_EVENT_OBJECT, "ChildrenChanged", "",
                    0, 0, new DBus.Variant("i", 0));
            case NODE_DESTROYED -> event(context, path, I_EVENT_OBJECT, "ChildrenChanged",
                    "remove", 0, 0, new DBus.Variant("i", 0));
            case SELECTION_CHANGED -> event(context, path, I_EVENT_OBJECT, "SelectionChanged", "",
                    0, 0, new DBus.Variant("i", 0));
            case ACTIVE_DESCENDANT_CHANGED -> activeDescendantChanged(event, context, path);
            case TEXT_CHANGED -> event(context, path, I_EVENT_OBJECT, "TextChanged",
                    event.inserted() > 0 ? "insert" : "delete", event.offset(),
                    Math.max(event.inserted(), event.removed()),
                    new DBus.Variant("s", string(event.newValue())));
            case CARET_MOVED -> event(context, path, I_EVENT_OBJECT, "TextCaretMoved", "",
                    event.offset(), 0, new DBus.Variant("i", 0));
            case TEXT_SELECTION_CHANGED -> event(context, path, I_EVENT_OBJECT,
                    "TextSelectionChanged", "", 0, 0, new DBus.Variant("i", 0));
            case WINDOW_OPENED -> window(context, path, "Create", nameOf(event, context));
            case WINDOW_CLOSED -> window(context, path, "Destroy", nameOf(event, context));
            case WINDOW_ACTIVATED -> window(context, path, "Activate", nameOf(event, context));
            case WINDOW_DEACTIVATED -> window(context, path, "Deactivate", nameOf(event, context));
            // Everything else: an announcement is a message rather than a node's fact, and the
            // remaining kinds are the toolkit's own bookkeeping. Nothing approximate is sent.
            default -> null;
        };
        return one == null ? List.of() : List.of(one);
    }

    /**
     * The node an event is sent from: the one it names, or — for the window-level events the model
     * raises on node zero (a scroll that moved more boxes than it lists, a window stamp) — the
     * window's own node, which is its frame here.
     *
     * <p>Node zero used to be sent from the application object (LINUX-NEW-2), an object with no
     * geometry, no ACTIVE and no SHOWING, which no reader treats as a window.
     */
    private static long subjectOf(AccessibleEvent event, Context context) {
        if (event.nodeId() != 0) {
            return event.nodeId();
        }
        AccessibleTree tree = context.tree();
        return tree.nodeCount() > 0 ? tree.node(0).id() : 0;
    }

    /**
     * An {@code Event.Window} signal from the window's frame, with the window's name as the value:
     * the ATK bridge's {@code window_event_listener} (at-spi2-core 2.60.6) sends
     * {@code atk_object_get_name}; GTK 4.22.4 sends the string "0". A string either way, which is
     * what libatspi makes {@code any_data} of; the name is the one a client could use.
     */
    static Signal window(Context context, String path, String member, String name) {
        return event(context, path, I_EVENT_WINDOW, member, "", 0, 0, new DBus.Variant("s", name));
    }

    private static String nameOf(AccessibleEvent event, Context context) {
        limn.accessibility.AccessibleNode node = context.tree().find(subjectOf(event, context));
        return node == null ? "" : node.name();
    }

    /**
     * A box moved: {@code BoundsChanged} with the node's new extents as an {@code (iiii)} in
     * screen coordinates, the rectangle libatspi's {@code demarshal_rect} makes an
     * {@code AtspiRect} of (GTK 4.22.4's {@code emit_bounds_changed} sends the same shape). It was
     * an {@code i} 0, which arrives as nothing.
     */
    private static Signal boundsChanged(AccessibleEvent event, Context context, String path) {
        AccessibleTree tree = context.tree();
        limn.accessibility.AccessibleNode node = tree.find(subjectOf(event, context));
        int[] box = node == null ? new int[4]
                : AtspiTree.extentsOf(tree, node, Atspi.COORD_SCREEN);
        return event(context, path, I_EVENT_OBJECT, "BoundsChanged", "", 0, 0,
                new DBus.Variant("(iiii)", (Object) new Object[] {box[0], box[1], box[2], box[3]}));
    }

    /**
     * The focused node's cursor moved: sent from the focused node, with the new descendant's own
     * {@code (so)} as the value and its index in its parent as the first integer.
     *
     * <p>The reference is what makes the event mean anything. It was an {@code i} 0 until
     * 2026-09-15 (L1), which libatspi turns into no {@code any_data} at all, and Orca 50.2's
     * {@code _ignore_active_descendant_or_selection} drops an active-descendant change with none:
     * the 2026-09-14 baseline counted ten of them from the tree reader, every one ignored "No
     * any_data". The index is the ATK bridge's convention ({@code active_descendant_event_listener}
     * in at-spi2-core 2.60.6's {@code atk-adaptor/event.c}, which sends
     * {@code atk_object_get_index_in_parent (child)}; GTK 4.22.4 sends no such event), read in
     * readings/upstream-gtk-4.22.4-atk-adaptor-2.60.6-event-shapes.txt. The descendant may live in
     * another window of this process — a native popup's option, decision 5 — which is an ordinary
     * reference on this connection. A cursor that went away names the null object and no index.
     */
    private static Signal activeDescendantChanged(AccessibleEvent event, Context context,
                                                  String path) {
        long descendant = event.newValue() instanceof Number n ? n.longValue() : 0L;
        DBus.Ref ref = descendant == 0 ? context.nullRef() : context.refOf(descendant);
        int index = descendant == 0 ? -1 : context.indexInParent(descendant);
        return event(context, path, I_EVENT_OBJECT, "ActiveDescendantChanged", "", index, 0,
                new DBus.Variant("(so)", ref.toStruct()));
    }

    /**
     * A state change, whose detail is the platform's own name for the bit and whose first integer
     * says whether it went on or off.
     */
    private static Signal stateChanged(AccessibleEvent event, Context context, String path) {
        Accessible.State state = event.state();
        if (state == null || AtspiStates.bitOf(state) == null) {
            return null;
        }
        boolean on = Boolean.TRUE.equals(event.newValue());
        return event(context, path, I_EVENT_OBJECT, "StateChanged", detailOf(state), on ? 1 : 0, 0,
                new DBus.Variant("i", 0));
    }

    /**
     * The platform's subscription name for a state: lower case with words separated by hyphens,
     * which is what a client's {@code object:state-changed:<detail>} match string carries.
     */
    static String detailOf(Accessible.State state) {
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
     * The signature every one of these signals carries.
     *
     * <p>Five arguments and no more. A trailing property dictionary looks plausible -- other
     * things on this bus carry one -- and at-spi2 rejects the whole signal for it: the client
     * receives the message, refuses to decode it, and logs an invalid-signature warning that names
     * the interface and not the sender, so the application looks silent and correct at once.
     */
    static final String SIGNATURE = "siiv(so)";
}

package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
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

        /**
         * @return the tree that window published before that one, which a derived bit's former
         *         value is read from; {@link AccessibleTree#EMPTY} when there was none
         */
        AccessibleTree previousTree();

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

        /**
         * @return the node's {@code Cache} item as {@code GetItems} would list it now, or
         *         {@code null} when no window holds it
         */
        Object[] cacheItem(long id);
    }

    /** Where the {@code Cache} signals are sent from, and what a client matches them by. */
    static final String I_CACHE = Atspi.I_CACHE;

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
        if (event.type() == AccessibleEvent.Type.TEXT_CHANGED) {
            return textChanged(event, context, path);  // a replacement is two signals
        }
        if (event.type() == AccessibleEvent.Type.STRUCTURE_CHANGED) {
            return structureChanged(event, context, path);
        }
        if (event.type() == AccessibleEvent.Type.NODE_DESTROYED) {
            return destroyed(event, context, path);
        }
        if (event.type() == AccessibleEvent.Type.VALUE_CHANGED) {
            return valueChanged(event, context, path);
        }
        if (event.type() == AccessibleEvent.Type.STATE_CHANGED
                && (event.state() == Accessible.State.EXPANDED
                        || event.state() == Accessible.State.EXPANDABLE)) {
            return withInterfacesSaid(expandChanged(event, context, path), event, context);
        }
        if (event.type() == AccessibleEvent.Type.STATE_CHANGED) {
            Signal state = stateChanged(event, context, path);
            return withInterfacesSaid(state == null ? List.of() : List.of(state), event, context);
        }
        Signal one = switch (event.type()) {
            // Focus is a state change on this platform -- the dedicated Focus signal is deprecated
            // and Orca subscribes to object:state-changed:focused -- so this is the same signal
            // the FOCUSED bit's STATE_CHANGED makes. The difference raises that STATE_CHANGED for a
            // surviving node, and then this event in the tail as well; a node that ARRIVES focused
            // gets this event alone (a new node's states are read on discovery), and it mapped to
            // nothing until the review of linux-B, so a dialog's first field was never said
            // focused. The copy for a survivor is not sent twice: AtspiApplication#emit drops a
            // focus already said in the same publish.
            case FOCUS_CHANGED -> event(context, path, I_EVENT_OBJECT, "StateChanged",
                    detailOf(Accessible.State.FOCUSED), 1, 0, new DBus.Variant("i", 0));
            case NAME_CHANGED -> event(context, path, I_EVENT_OBJECT, "PropertyChange",
                    "accessible-name", 0, 0, new DBus.Variant("s", string(event.newValue())));
            case DESCRIPTION_CHANGED -> event(context, path, I_EVENT_OBJECT, "PropertyChange",
                    "accessible-description", 0, 0,
                    new DBus.Variant("s", string(event.newValue())));
            case BOUNDS_CHANGED -> boundsChanged(event, context, path);
            case SELECTION_CHANGED -> event(context, path, I_EVENT_OBJECT, "SelectionChanged", "",
                    0, 0, new DBus.Variant("i", 0));
            case ACTIVE_DESCENDANT_CHANGED -> activeDescendantChanged(event, context, path);
            case CARET_MOVED -> caretMoved(event, context, path);
            // GTK 4.22.4 and the ATK bridge both send an empty string here; an i arrives as nothing.
            case TEXT_SELECTION_CHANGED -> event(context, path, I_EVENT_OBJECT,
                    "TextSelectionChanged", "", 0, 0, new DBus.Variant("s", ""));
            case WINDOW_OPENED -> window(context, path, "Create", nameOf(event, context));
            case WINDOW_CLOSED -> window(context, path, "Destroy", nameOf(event, context));
            case WINDOW_ACTIVATED -> window(context, path, "Activate", nameOf(event, context));
            case WINDOW_DEACTIVATED -> window(context, path, "Deactivate", nameOf(event, context));
            case ANNOUNCEMENT -> announcement(event, context, path);
            // Everything else is the toolkit's own bookkeeping. Nothing approximate is sent.
            default -> null;
        };
        return one == null ? List.of() : List.of(one);
    }

    /**
     * A text edit: a {@code delete} carrying the removed text, then an {@code insert} carrying the
     * inserted text, each at its offset and length in characters.
     *
     * <p>It was one {@code insert} whenever anything was inserted, with the removed length when
     * that was longer, the offset in UTF-16 units and the whole new text as the value. GTK 4.22.4
     * sends {@code insert} or {@code delete} with the start, the length and the changed text itself
     * ({@code gtk_at_spi_context_update_text_contents}), the ATK bridge the same
     * ({@code text_insert_event_listener}, {@code text_remove_event_listener}), and Orca 50.2
     * speaks {@code any_data} as the inserted string ({@code inserted_text}) and drops an insertion
     * longer than 1000 ({@code _ignore_text_events}) — so the whole field was spoken for one typed
     * character, and a long field's typing was not spoken at all
     * (readings/upstream-gtk-4.22.4-atk-adaptor-2.60.6-event-shapes.txt,
     * readings/fedora-orca-event-consumers.txt).
     *
     * <p>Offsets are converted from UTF-16 to characters here and nowhere else. The model compares
     * the two strings unit by unit, so a replaced character outside the basic plane can leave its
     * range starting or ending between the two halves of a surrogate pair; the range is widened to
     * whole characters on both strings first, which keeps what is said a character and never half
     * of one.
     */
    private static List<Signal> textChanged(AccessibleEvent event, Context context, String path) {
        String before = string(event.oldValue());
        String after = string(event.newValue());
        int start = Math.min(event.offset(), Math.min(before.length(), after.length()));
        int removedEnd = Math.min(before.length(), start + event.removed());
        int insertedEnd = Math.min(after.length(), start + event.inserted());
        if (start > 0 && splitsAPair(before, start) || start > 0 && splitsAPair(after, start)) {
            start--;
        }
        if (splitsAPair(before, removedEnd) || splitsAPair(after, insertedEnd)) {
            removedEnd = Math.min(before.length(), removedEnd + 1);
            insertedEnd = Math.min(after.length(), insertedEnd + 1);
        }
        int at = before.codePointCount(0, start);
        List<Signal> out = new java.util.ArrayList<>(2);
        if (removedEnd > start) {
            String gone = before.substring(start, removedEnd);
            out.add(event(context, path, I_EVENT_OBJECT, "TextChanged", "delete", at,
                    gone.codePointCount(0, gone.length()), new DBus.Variant("s", gone)));
        }
        if (insertedEnd > start) {
            String added = after.substring(start, insertedEnd);
            out.add(event(context, path, I_EVENT_OBJECT, "TextChanged", "insert", at,
                    added.codePointCount(0, added.length()), new DBus.Variant("s", added)));
        }
        return out;
    }

    /**
     * A value moved: {@code PropertyChange accessible-value} with the new number, and — when only
     * the display form moved and the node serves that form as its {@code Text} — the text's
     * replacement as {@code TextChanged} {@code delete} and {@code insert}.
     *
     * <p>The difference raises {@code VALUE_CHANGED} when the number, the text or the emptiness
     * moved, carrying the two numbers. A date segment filled with the digit that is its minimum
     * moves only its text ("empty" to "1"): the number a client reads through {@code Value} did not
     * change, so the property change alone says nothing new, while the {@code Text} the node serves
     * ({@link AtspiText#of}) did. The texts are read off the node in the tree the event came with
     * and in the tree its window published before, whole string for whole string, as a replacement
     * of a field is sent. A node with a {@code TextFacet} raises its own {@code TEXT_CHANGED} and
     * is not doubled here, and a change that moved the number is not given a text echo on top of
     * the property change a reader already presents.
     */
    private static List<Signal> valueChanged(AccessibleEvent event, Context context, String path) {
        List<Signal> out = new java.util.ArrayList<>(4);
        out.add(event(context, path, I_EVENT_OBJECT, "PropertyChange", "accessible-value", 0, 0,
                new DBus.Variant("d", number(event.newValue()))));
        limn.accessibility.AccessibleNode now = context.tree().find(event.nodeId());
        limn.accessibility.AccessibleNode was = context.previousTree().find(event.nodeId());
        if (number(event.oldValue()) != number(event.newValue()) || now == null || was == null
                || now.text() != null || now.value() == null || was.value() == null) {
            return withInterfacesSaid(out, event, context);
        }
        String before = was.value().text() == null ? "" : was.value().text();
        String after = now.value().text() == null ? "" : now.value().text();
        if (before.equals(after)) {
            return out;
        }
        if (!before.isEmpty()) {
            out.add(event(context, path, I_EVENT_OBJECT, "TextChanged", "delete", 0,
                    before.codePointCount(0, before.length()), new DBus.Variant("s", before)));
        }
        // Between the two: a display form that emptied has said its deletion while the node still
        // listed Text, and one that appeared is listed before its insertion is said.
        Signal said = interfacesSaid(event, context);
        if (said != null) {
            out.add(said);
        }
        if (!after.isEmpty()) {
            out.add(event(context, path, I_EVENT_OBJECT, "TextChanged", "insert", 0,
                    after.codePointCount(0, after.length()), new DBus.Variant("s", after)));
        }
        return out;
    }

    /**
     * {@code signals}, followed by the node's {@code Cache.AddAccessible} when the interfaces it
     * serves moved with the change; see {@link #interfacesSaid}.
     */
    private static List<Signal> withInterfacesSaid(List<Signal> signals, AccessibleEvent event,
                                                   Context context) {
        Signal said = interfacesSaid(event, context);
        if (said == null) {
            return signals;
        }
        List<Signal> out = new java.util.ArrayList<>(signals.size() + 1);
        out.addAll(signals);
        out.add(said);
        return out;
    }

    /**
     * The node's {@code Cache.AddAccessible} when the interfaces it serves differ between the tree
     * its window published before and this one, or {@code null}.
     *
     * <p>A client keeps a node's interfaces from its cache item ({@code GetItems} or an
     * {@code AddAccessible}), and libatspi 2.60.6's {@code add_accessible_from_iter} overwrites
     * them — with the name, role, description and states — from the item a later
     * {@code AddAccessible} carries, for a node it already holds
     * (readings/upstream-at-spi2-core-2.60.6-libatspi.txt). Two facts that move without the node
     * leaving the tree change what it serves: a value's display form going from empty to a word and
     * back (Text), and the verbs and the editable bit a node gains or loses with {@code ENABLED},
     * {@code EDITABLE} or its expand state (Action, EditableText). Nothing said so, and a client
     * went on without Text on a node that served it, or without Action on a button enabled again. A
     * publish that moves several bits of one node sends the item after each; the item is the same.
     */
    private static Signal interfacesSaid(AccessibleEvent event, Context context) {
        limn.accessibility.AccessibleNode now = context.tree().find(event.nodeId());
        limn.accessibility.AccessibleNode was = context.previousTree().find(event.nodeId());
        if (now == null || was == null
                || AtspiTree.interfaceBitsOf(now) == AtspiTree.interfaceBitsOf(was)) {
            return null;
        }
        Object[] item = context.cacheItem(event.nodeId());
        return item == null ? null : new Signal(Atspi.PATH_CACHE, I_CACHE, "AddAccessible",
                Atspi.CACHE_ITEM, new Object[] {item});
    }

    /**
     * A node gone: {@code StateChanged defunct} from its own path, and, for a node that left inside
     * a subtree that left with it, {@code Cache.RemoveAccessible} too.
     *
     * <p>The subtree's root is taken out of the client's cache by its parent's
     * {@code ChildrenChanged remove} ({@link #structureChanged}); nothing named its descendants,
     * and libatspi kept each of them with its cached name. Orca 50.2 tells a dead object by
     * reading its name ({@code AXObject.is_dead}), so a list item that had left with its popup read
     * as alive and orphaned: when the in-scene combo list closed, the prior focus had no common
     * ancestor with the field and Orca spoke the window's name before the field
     * (readings/combo-fedora, fix-realkeys-combo-2, 2026-09-23). GTK 4.22.4 takes every context of a
     * closed popover out of the cache. A node whose parent survives is left to the parent's
     * structure change, which still names it.
     */
    private static List<Signal> destroyed(AccessibleEvent event, Context context, String path) {
        Signal defunct = event(context, path, I_EVENT_OBJECT, "StateChanged", "defunct",
                1, 0, new DBus.Variant("i", 0));
        AccessibleTree before = context.previousTree();
        int at = before.indexOf(event.nodeId());
        if (at < 0) {
            return List.of(defunct);
        }
        int parent = before.node(at).parent();
        if (parent == AccessibleNode.NONE || context.tree().indexOf(before.node(parent).id()) >= 0) {
            return List.of(defunct);
        }
        return List.of(defunct, new Signal(Atspi.PATH_CACHE, I_CACHE, "RemoveAccessible", "(so)",
                new Object[] {context.refOf(event.nodeId()).toStruct()}));
    }

    /**
     * A parent's children moved: per child, {@code ChildrenChanged} from
     * the parent with the child's index in {@code detail1} and its {@code (so)} as the value, plus
     * {@code Cache.RemoveAccessible} for a child that left the tree and {@code Cache.AddAccessible}
     * for one that arrived here.
     *
     * <p>It was one {@code ChildrenChanged} with an empty detail, no index and an {@code i} per new
     * node, and a {@code remove} from the destroyed node's own path — a path that answered nothing
     * by then. libatspi 2.60.6's {@code cache_process_children_changed} touches a client's cached
     * children only for {@code add} or {@code remove} with an accessible {@code any_data}
     * (readings/upstream-at-spi2-core-2.60.6-libatspi.txt), and Orca 50.2 crashed on the
     * {@code int} and ignores one from a dead source.
     *
     * <p>The order is libatspi's arithmetic, not taste. {@code remove} takes the child out by
     * reference, so removals go first, the highest former index first. {@code add} removes the
     * child and inserts it at {@code detail1}, so additions and reorders go next in ascending
     * index, each landing where the ones before it already stand. {@code AddAccessible} writes the
     * child into the parent's slot at its index — overwriting whatever stands there — so it follows
     * the {@code add} that put the child in that slot rather than preceding it, where it would
     * overwrite a sibling (GTK 4.22.4 sends its cache addition first; its cache was never read
     * through this path). {@code RemoveAccessible} disposes the client's object, so it follows the
     * {@code remove} that still names it. A child that moved between parents is removed from one
     * and added to the other; it is never removed from the cache.
     */
    private static List<Signal> structureChanged(AccessibleEvent event, Context context,
                                                 String path) {
        List<Signal> out = new java.util.ArrayList<>();
        List<AccessibleEvent.Child> removed = new java.util.ArrayList<>(event.removedChildren());
        removed.sort((a, b) -> Integer.compare(b.index(), a.index()));
        for (AccessibleEvent.Child child : removed) {
            DBus.Ref ref = context.refOf(child.id());
            out.add(event(context, path, I_EVENT_OBJECT, "ChildrenChanged", "remove",
                    child.index(), 0, new DBus.Variant("(so)", ref.toStruct())));
            if (child.otherParent() == 0) {
                out.add(new Signal(Atspi.PATH_CACHE, I_CACHE, "RemoveAccessible", "(so)",
                        new Object[] {ref.toStruct()}));
            }
        }
        List<AccessibleEvent.Child> arriving = new java.util.ArrayList<>(event.addedChildren());
        java.util.Set<Long> added = new java.util.HashSet<>();
        for (AccessibleEvent.Child child : arriving) {
            added.add(child.id());
        }
        arriving.addAll(event.reorderedChildren());
        arriving.sort(java.util.Comparator.comparingInt(AccessibleEvent.Child::index));
        for (AccessibleEvent.Child child : arriving) {
            out.add(event(context, path, I_EVENT_OBJECT, "ChildrenChanged", "add", child.index(),
                    0, new DBus.Variant("(so)", context.refOf(child.id()).toStruct())));
            Object[] item = added.contains(child.id()) ? context.cacheItem(child.id()) : null;
            if (item != null) {
                out.add(new Signal(Atspi.PATH_CACHE, I_CACHE, "AddAccessible", Atspi.CACHE_ITEM,
                        new Object[] {item}));
            }
        }
        return out;
    }

    /** Whether {@code index} falls between the high and low halves of one character. */
    private static boolean splitsAPair(String text, int index) {
        return index > 0 && index < text.length()
                && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index));
    }

    /**
     * The caret moved: {@code TextCaretMoved} with the caret's offset in characters in
     * {@code detail1}, read off the text the event's tree published.
     *
     * <p>It was always 0: the model raises the event without the offset, and the mapping read the
     * event's text offset, which a caret move never sets. Orca 50.2's {@code _on_caret_moved}
     * compares {@code detail1} with the last cursor position it saved, so a caret said to stand
     * at 0 every time was a caret that "did not move" (readings/fedora-orca-event-consumers.txt).
     * GTK 4.22.4 sends the caret position there with an {@code i} 0 value.
     */
    private static Signal caretMoved(AccessibleEvent event, Context context, String path) {
        limn.accessibility.AccessibleNode node = context.tree().find(event.nodeId());
        int caret = 0;
        if (node != null && node.text() != null) {
            String text = node.text().text();
            caret = text.codePointCount(0, Math.max(0, Math.min(text.length(),
                    node.text().caretOffset())));
        }
        return event(context, path, I_EVENT_OBJECT, "TextCaretMoved", "", caret, 0,
                new DBus.Variant("i", 0));
    }

    /**
     * The node an event is sent from: the one it names, or — for the window-level events the model
     * raises on node zero (a scroll that moved more boxes than it lists, a window stamp) — the
     * window's own node, which is its frame here.
     *
     * <p>Node zero used to be sent from the application object, an object with no
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
     * The application said something: {@code Announcement} with the text as a string value and the
     * politeness as {@code Atspi.Live} in {@code detail1}.
     *
     * <p>The shape is the installed interface's, {@code Announcement(s, i politeness, i, v, a{sv})}
     * (readings/fedora-dbus-Event.Object.xml), filled as GTK 4.22.4's
     * {@code gtk_at_spi_context_announce} fills it: an empty detail, the live value, 0, and the
     * message string. The string matters most: Orca 50.2's {@code _on_announcement} presents an
     * announcement only when {@code any_data} is a {@code str}. The toolkit raises it on no node;
     * it is sent from the frame of the window whose scene said it, which is where GTK's comes from
     * too (the widget's own context). Until 2026-09-15 this platform sent nothing at all.
     */
    private static Signal announcement(AccessibleEvent event, Context context, String path) {
        int live = event.politeness() == Accessible.Politeness.ASSERTIVE
                ? Atspi.LIVE_ASSERTIVE : Atspi.LIVE_POLITE;
        return event(context, path, I_EVENT_OBJECT, "Announcement", "", live, 0,
                new DBus.Variant("s", string(event.newValue())));
    }

    /**
     * The focused node's cursor moved: sent from the focused node, with the new descendant's own
     * {@code (so)} as the value and its index in its parent as the first integer.
     *
     * <p>The reference is what makes the event mean anything. It was an {@code i} 0
     * until 2026-09-15, which libatspi turns into no {@code any_data} at all, and Orca 50.2's
     * {@code _ignore_active_descendant_or_selection} drops an active-descendant change with none:
     * the 2026-09-14 baseline counted ten of them from the tree reader, every one ignored "No
     * any_data". The index is the ATK bridge's convention ({@code active_descendant_event_listener}
     * in at-spi2-core 2.60.6's {@code atk-adaptor/event.c}, which sends
     * {@code atk_object_get_index_in_parent (child)}; GTK 4.22.4 sends no such event), read in
     * readings/upstream-gtk-4.22.4-atk-adaptor-2.60.6-event-shapes.txt. The descendant may live in
     * another window of this process — a native popup's option — which is an ordinary reference on
     * this connection. A cursor that went away names the null object and no index.
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
     * {@code EXPANDED} or {@code EXPANDABLE} flipped: its own {@code StateChanged}, then
     * {@code collapsed} when the bit this platform derives from the two moved with it.
     *
     * <p>{@code COLLAPSED} is published in every state set as {@code EXPANDABLE} without
     * {@code EXPANDED} ({@link AtspiStates#setOf}), and nothing raised a change for it.
     * libatspi 2.60.6's {@code cache_process_state_changed} sets or clears only the bit an event
     * names, so a long-lived client that cached a closed branch and then heard {@code expanded} 1
     * held {@code expanded} and {@code collapsed} at once. GTK 4.22.4 sends {@code expandable} and
     * {@code expanded} only (whether its state set carries {@code COLLAPSED} was not read)
     * (readings/upstream-gtk-4.22.4-atk-adaptor-2.60.6-event-shapes.txt). After is read off the
     * node as published with the event; before, off the same node in the tree published before it,
     * and only when that tree does not hold it, off the node now with the event's own bit put back
     * — which is wrong for a publish that flipped both bits, and why the previous tree is kept. A
     * node the tree no longer holds says nothing more.
     */
    private static List<Signal> expandChanged(AccessibleEvent event, Context context,
                                              String path) {
        limn.accessibility.AccessibleNode node = context.tree().find(event.nodeId());
        if (node != null && AtspiMenuShape.silencesExpandChange(node)) {
            // Nothing at all, for the reason the state set carries none of these bits on a menu
            // row (decision 72 of 2026-09-16, built under decision 82; AtspiTree.statesOf): a
            // client told "expanded 1" about an object whose state set never says expandable
            // would hold a bit it can never see cleared. What a reader hears instead is the
            // selected flip on the open title and showing on its children, which is what a
            // native GTK 3 menu sends and travels through the ordinary paths.
            return List.of();
        }
        Signal own = stateChanged(event, context, path);
        List<Signal> out = new java.util.ArrayList<>(2);
        out.add(own);
        if (node == null) {
            return out;
        }
        boolean on = Boolean.TRUE.equals(event.newValue());
        boolean expandable = node.has(Accessible.State.EXPANDABLE);
        boolean expanded = node.has(Accessible.State.EXPANDED);
        boolean collapsedNow = expandable && !expanded;
        limn.accessibility.AccessibleNode was = context.previousTree().find(event.nodeId());
        boolean collapsedBefore;
        if (was != null) {
            collapsedBefore = was.has(Accessible.State.EXPANDABLE)
                    && !was.has(Accessible.State.EXPANDED);
        } else {
            boolean expandableBefore = event.state() == Accessible.State.EXPANDABLE ? !on
                    : expandable;
            boolean expandedBefore = event.state() == Accessible.State.EXPANDED ? !on : expanded;
            collapsedBefore = expandableBefore && !expandedBefore;
        }
        if (collapsedNow != collapsedBefore) {
            out.add(event(context, path, I_EVENT_OBJECT, "StateChanged", "collapsed",
                    collapsedNow ? 1 : 0, 0, new DBus.Variant("i", 0)));
        }
        return out;
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

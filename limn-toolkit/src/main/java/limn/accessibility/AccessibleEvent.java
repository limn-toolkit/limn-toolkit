package limn.accessibility;

import java.util.List;

/**
 * One thing an assistive technology has to be told: a property that moved, a node that appeared or
 * went away, a window that opened, or something the application said out loud.
 *
 * <p><b>An event carries its own values.</b> It is handed to a bridge on the user-interface thread
 * immediately after the tree it describes, and raised later, from a bounded queue on a thread of
 * the bridge's own: raising inline would spend the frame budget in cross-process calls, which is
 * exactly the stall that makes a window unreadable. By the time it is raised, the tree it came from
 * may be two publishes old, so a bridge that raised the event and then read "the current value"
 * would report the newest value under an older event. The values are therefore here.
 *
 * <p>The one thing an event may never carry is a widget, for the reason no node carries one.
 */
public final class AccessibleEvent {

    /**
     * What happened. Every constant here has a truthful mapping on at least one platform, and
     * nothing is invented to fill a gap: two of them are deliberately answered by silence on
     * platforms whose vocabulary has no equivalent.
     */
    public enum Type {
        /** The keyboard focus moved to this node. */
        FOCUS_CHANGED,
        /** The node a container's keyboard cursor is on changed. */
        ACTIVE_DESCENDANT_CHANGED,
        /** This node's children changed: one appeared, one went away, or the shape moved. */
        STRUCTURE_CHANGED,
        /** This node's name changed. */
        NAME_CHANGED,
        /** This node's description changed. */
        DESCRIPTION_CHANGED,
        /** One of this node's states changed; {@link #state()} says which. */
        STATE_CHANGED,
        /**
         * This node's value changed: its number, its displayed text, or whether it holds a
         * number at all ({@link ValueFacet#empty()}). The event carries the two numbers; the text
         * is read off the tree the event was handed with.
         */
        VALUE_CHANGED,
        /**
         * What this container has selected changed: one per container per publish, carrying
         * the members that entered and left its selection ({@link #addedMembers()},
         * {@link #removedMembers()}) and whether it selects more than one at once. The
         * container is the member's by semantics 1: the nearest ancestor with a
         * {@link SelectionFacet}, climbed to from the member's published parent through
         * synthetic ancestors only. A member that arrived selected in this publish and one that
         * left the tree selected are counted too.
         */
        SELECTION_CHANGED,
        /** This node's text changed; the offsets say where. */
        TEXT_CHANGED,
        /** This node's caret moved. */
        CARET_MOVED,
        /** This node's text selection changed. */
        TEXT_SELECTION_CHANGED,
        /** This node's box moved or resized. */
        BOUNDS_CHANGED,
        /** This node has left the tree, and anything a client holds for it is now stale. */
        NODE_DESTROYED,
        /** A window's tree became readable. Raised by a bridge as it takes its scene. */
        WINDOW_OPENED,
        /** A window's tree is going away. Raised by a bridge as it lets its scene go. */
        WINDOW_CLOSED,
        /** This window took the desktop's focus. */
        WINDOW_ACTIVATED,
        /** This window lost it. */
        WINDOW_DEACTIVATED,
        /** The application said something; {@link #politeness()} says whether it interrupts. */
        ANNOUNCEMENT,
        /**
         * An assistive technology pressed this node and the press succeeded. Not raised for a
         * press the user made with a pointer, which leaves no difference between two trees to
         * find.
         */
        INVOKED,
        /**
         * Everything about this window may have changed. The event a bounded queue collapses to
         * when a single difference is wider than it can carry; a bridge handling it reconciles
         * whatever it holds against the tree it was last handed, rather than replaying anything.
         */
        INVALIDATED
    }

    private final Type type;
    private final long nodeId;
    private final Accessible.State state;
    private final Accessible.Politeness politeness;
    private final Object oldValue;
    private final Object newValue;
    private final int offset;
    private final int removed;
    private final int inserted;
    private final List<Long> addedMembers;
    private final List<Long> removedMembers;
    private final boolean multiSelectable;

    private AccessibleEvent(Type type, long nodeId, Accessible.State state,
                            Accessible.Politeness politeness, Object oldValue, Object newValue,
                            int offset, int removed, int inserted) {
        this(type, nodeId, state, politeness, oldValue, newValue, offset, removed, inserted,
                List.of(), List.of(), false);
    }

    private AccessibleEvent(Type type, long nodeId, Accessible.State state,
                            Accessible.Politeness politeness, Object oldValue, Object newValue,
                            int offset, int removed, int inserted, List<Long> addedMembers,
                            List<Long> removedMembers, boolean multiSelectable) {
        this.type = type;
        this.nodeId = nodeId;
        this.state = state;
        this.politeness = politeness;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.offset = offset;
        this.removed = removed;
        this.inserted = inserted;
        this.addedMembers = addedMembers;
        this.removedMembers = removedMembers;
        this.multiSelectable = multiSelectable;
    }

    /**
     * An event that names a node and carries nothing else: a focus move, a structure change, a
     * destruction, a window opening.
     *
     * @param type   what happened
     * @param nodeId the node it happened to, or {@code 0} for a window-level event
     * @return the event
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static AccessibleEvent of(Type type, long nodeId) {
        java.util.Objects.requireNonNull(type, "type");
        return new AccessibleEvent(type, nodeId, null, null, null, null, 0, 0, 0);
    }

    /**
     * A property that moved, carrying what it was and what it is.
     *
     * @param type     which property; a name, a description, a value, a selection or a box
     * @param nodeId   the node it moved on
     * @param oldValue what it was, or {@code null} when there was nothing
     * @param newValue what it is now, or {@code null}
     * @return the event
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static AccessibleEvent property(Type type, long nodeId,
                                           Object oldValue, Object newValue) {
        java.util.Objects.requireNonNull(type, "type");
        return new AccessibleEvent(type, nodeId, null, null, oldValue, newValue, 0, 0, 0);
    }

    /**
     * One state bit that flipped.
     *
     * @param nodeId the node it flipped on
     * @param state  which bit
     * @param now    what it is now
     * @return the event
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public static AccessibleEvent state(long nodeId, Accessible.State state, boolean now) {
        java.util.Objects.requireNonNull(state, "state");
        return new AccessibleEvent(Type.STATE_CHANGED, nodeId, state, null, !now, now, 0, 0, 0);
    }

    /**
     * A text edit, with the range it replaced.
     *
     * <p>The offsets are computed by comparing the two published strings rather than reported by
     * the editing model, so two edits landing in one frame yield one contiguous replaced range
     * covering both &mdash; which every platform can carry and none can distinguish from the truth.
     *
     * @param nodeId   the node whose text changed
     * @param offset   where the replaced range begins, in UTF-16 code units
     * @param removed  how many units it removed
     * @param inserted how many it inserted
     * @param oldText  the text as it was
     * @param newText  the text as it is
     * @return the event
     */
    public static AccessibleEvent text(long nodeId, int offset, int removed, int inserted,
                                       String oldText, String newText) {
        return new AccessibleEvent(Type.TEXT_CHANGED, nodeId, null, null, oldText, newText,
                offset, removed, inserted);
    }

    /**
     * A container's selection moved (ADR 039 §1.10, amended 2026-09-14; decision 9).
     *
     * @param containerId     the container the members belong to, by semantics 1
     * @param multiSelectable whether it selects more than one member at once
     * @param added           the members that entered its selection in this publish, in reading
     *                        order; a member new in this publish included
     * @param removed         the members that left it, in the previous publish's reading order;
     *                        a member that left the tree included
     * @return the event
     * @throws NullPointerException if either array is {@code null}
     */
    public static AccessibleEvent selection(long containerId, boolean multiSelectable,
                                            long[] added, long[] removed) {
        return new AccessibleEvent(Type.SELECTION_CHANGED, containerId, null, null, null, null,
                0, 0, 0, boxed(added), boxed(removed), multiSelectable);
    }

    private static List<Long> boxed(long[] ids) {
        java.util.Objects.requireNonNull(ids, "ids");
        Long[] out = new Long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            out[i] = ids[i];
        }
        return List.of(out);
    }

    /**
     * Something the application said out loud.
     *
     * @param text       what to say, already resolved in the scene's language
     * @param politeness whether it waits for the assistive technology to finish, or interrupts it
     * @return the event
     * @throws NullPointerException if either argument is {@code null}
     */
    public static AccessibleEvent announcement(String text, Accessible.Politeness politeness) {
        java.util.Objects.requireNonNull(text, "text");
        java.util.Objects.requireNonNull(politeness, "politeness");
        return new AccessibleEvent(Type.ANNOUNCEMENT, 0, null, politeness, null, text, 0, 0, 0);
    }

    /** @return what happened; never {@code null} */
    public Type type() {
        return type;
    }

    /**
     * @return the node this happened to, or {@code 0} for an event whose subject is the window
     *         itself or the application speaking
     */
    public long nodeId() {
        return nodeId;
    }

    /**
     * @return which state flipped, for a {@link Type#STATE_CHANGED}; {@code null} for every other
     *         kind
     */
    public Accessible.State state() {
        return state;
    }

    /**
     * @return whether an announcement interrupts, for a {@link Type#ANNOUNCEMENT}; {@code null} for
     *         every other kind
     */
    public Accessible.Politeness politeness() {
        return politeness;
    }

    /**
     * @return what the property was before, or {@code null} when the event carries no before. A
     *         {@link Boolean} for a state, a {@link String} for a name, a description or a text, a
     *         {@link Double} for a value, a {@link limn.graphics.Rect} for a box.
     */
    public Object oldValue() {
        return oldValue;
    }

    /** @return what the property is now, in the same types {@link #oldValue()} lists */
    public Object newValue() {
        return newValue;
    }

    /** @return where a text change begins, in UTF-16 code units; {@code 0} for every other kind */
    public int offset() {
        return offset;
    }

    /** @return how many code units a text change removed */
    public int removed() {
        return removed;
    }

    /** @return how many it inserted */
    public int inserted() {
        return inserted;
    }

    /**
     * @return for a {@link Type#SELECTION_CHANGED}, the members that entered the container's
     *         selection in this publish, in reading order; empty for every other kind. Never
     *         modifiable.
     */
    public List<Long> addedMembers() {
        return addedMembers;
    }

    /**
     * @return for a {@link Type#SELECTION_CHANGED}, the members that left the container's
     *         selection in this publish, a member that left the tree included; empty for every
     *         other kind. Never modifiable.
     */
    public List<Long> removedMembers() {
        return removedMembers;
    }

    /**
     * @return for a {@link Type#SELECTION_CHANGED}, whether the container selects more than one
     *         member at once, which is what decides between a platform's single-selection and
     *         add-to/remove-from-selection events; {@code false} for every other kind
     */
    public boolean multiSelectable() {
        return multiSelectable;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("AccessibleEvent[").append(type);
        if (nodeId != 0) {
            out.append(" node=").append(nodeId);
        }
        if (state != null) {
            out.append(' ').append(state);
        }
        if (type == Type.TEXT_CHANGED) {
            out.append(" at=").append(offset).append(" -").append(removed).append(" +")
                    .append(inserted);
        }
        if (type == Type.SELECTION_CHANGED) {
            out.append(multiSelectable ? " multi" : " single")
                    .append(" +").append(addedMembers).append(" -").append(removedMembers);
        }
        if (oldValue != null || newValue != null) {
            out.append(' ').append(oldValue).append(" -> ").append(newValue);
        }
        return out.append(']').toString();
    }
}

package limn.components.internal.a11y;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;

/**
 * The {@code ROWS} shape, written once (ADR 045 §3): what a container that holds a selection and
 * the members of it publish, and what a reader's verb on a member does. A list, a tree, a table's
 * rows and a calendar's day cells all say the same things and obey the same rules; before this,
 * each said them in its own hooks, and a decision about rows (79, the batch-23 lane) had to be
 * written three times.
 *
 * <p><b>Two halves, because the describe hook runs on every damaged frame under the
 * zero-allocation rule and a verb does not.</b> {@link #describeRow} is a static function over
 * the facts of one row — primitives the widget already holds — and allocates nothing; the widget
 * says what its row <i>is</i> (selected, its position, whether it opens, whether it is the
 * cursor) and what its rows <i>can do</i> (be pressed, be the cursor, be revealed), and the
 * rules say which verbs that adds up to, by state. {@link #performOnRow} takes a {@link Host},
 * which the widget implements over the mechanisms it already has, and holds the rules of what a
 * verb does: which ones move the cursor, which ones are refused by state.
 *
 * <p><b>The rules, each with its decision:</b>
 * <ul>
 *   <li><b>10</b> — {@code SELECT} is a click: it replaces the selection. In a multiple selection an
 *       unselected row offers {@code ADD_TO_SELECTION} and a selected one {@code DESELECT}, and each
 *       is refused when the row's membership is not the one it names.</li>
 *   <li><b>11</b> — a row offers {@code FOCUS} only where the cursor is separate from the selection,
 *       so that focusing selects nothing; where the cursor is the selection the verb is refused.</li>
 *   <li><b>20</b> — the row verb set is {@code SELECT}, {@code ADD_TO_SELECTION}, {@code DESELECT},
 *       {@code EXPAND} or {@code COLLAPSE} by state, {@code SCROLL_INTO_VIEW}, {@code FOCUS} and
 *       {@code PRESS}; {@code EXPAND} and {@code COLLAPSE} act like the triangle and move nothing;
 *       {@code ADD_TO_SELECTION} and {@code DESELECT} move the cursor no more than they do.</li>
 *   <li><b>79</b> — a {@code SELECT} that arrives from a client selects the row addressed and leaves
 *       the cursor where it is, because a client write is not where the user is; the pointer and
 *       the keyboard go on moving it, through the widget's own paths.</li>
 *   <li><b>80</b> — a row that can be activated offers {@code PRESS} and performs it on itself,
 *       never on the cursor row; a container's own {@code PRESS} still activates the cursor.</li>
 *   <li><b>81</b> — a row offers {@code SCROLL_INTO_VIEW} and the verb reveals it, selecting
 *       nothing.</li>
 * </ul>
 * {@code ACTIVE} marks the cursor row only while the widget has the keyboard, so that an
 * unfocused widget publishes no cursor.
 *
 * <p><b>Two identities, one shape (ADR 041 §3, ADR 045 §7's first risk).</b> A list's or a tree's
 * row is a widget child, whose verbs are <i>delegated</i> to the container; a table's or a
 * calendar's row is a synthetic child the container describes, whose verbs it <i>owns</i>. The
 * difference is one call on the builder, which is why {@link Offer} is a parameter and nothing
 * here knows which widget is calling.
 */
public final class RowsAccessibility {

    /** What a container's selection allows. */
    public enum Selection {
        /** Nothing can be selected: rows offer no selection verb. */
        NONE,
        /** One row at a time. */
        SINGLE,
        /** Any number of rows: rows offer {@code ADD_TO_SELECTION} and {@code DESELECT} by state. */
        MULTI
    }

    /** How a row's verbs reach the builder. */
    public enum Offer {
        /** The row is a widget child; the container performs the verb on its behalf. */
        DELEGATED,
        /** The row is a synthetic child; the verb is the container's own. */
        OWNED
    }

    /**
     * The mechanisms a widget of the shape already has, over its own row handle {@code R} (a
     * tree's row, a model index, a date), which {@link #performOnRow} drives by the rules. Every
     * mutation is made in the name of the user, because a reader is one.
     *
     * @param <R> what identifies a row to the widget
     */
    public interface Host<R> {

        /** @return what the container's selection allows now */
        Selection selection();

        /**
         * @return true where the cursor is the selection (a list, a segmented control), so that
         *     {@code FOCUS} is refused (decision 11); false where a row can be the cursor without
         *     being selected (a tree, a table, a calendar)
         */
        boolean cursorIsTheSelection();

        /** @return whether rows have an activation of their own that {@code PRESS} performs */
        boolean rowsActivate();

        /** @return whether the row is in the selection */
        boolean isSelected(R row);

        /** @return whether the row opens; false for a leaf */
        default boolean isExpandable(R row) {
            return false;
        }

        /** @return whether the row is open; asked only when it opens */
        default boolean isExpanded(R row) {
            return false;
        }

        /** @return whether the cursor may land on the row; false for one the widget refuses */
        default boolean canBeCursor(R row) {
            return true;
        }

        /**
         * Makes the row the whole selection, revealing it, and moves the cursor onto it only when
         * told to, which a client's {@code SELECT} never does (decision 79).
         *
         * @return whether the widget accepted it; false for a row it refuses to select
         */
        boolean select(R row, boolean moveCursor);

        /**
         * Flips the row's membership of a multiple selection, moving the cursor only when told to.
         * Asked only when {@link #selection()} is {@link Selection#MULTI}.
         */
        default void toggleSelection(R row, boolean moveCursor) {
            throw new UnsupportedOperationException("no multiple selection");
        }

        /** Opens or closes the row, leaving the cursor where it is; asked only when it opens. */
        default void setExpanded(R row, boolean open) {
            throw new UnsupportedOperationException("rows do not open");
        }

        /** Takes the keyboard and moves the cursor onto the row, selecting nothing. */
        void moveCursor(R row);

        /** Scrolls so that the row is inside the box, changing nothing else. */
        void reveal(R row);

        /** Activates the row itself; asked only when {@link #rowsActivate()}. */
        default void activate(R row) {
            throw new UnsupportedOperationException("rows do not activate");
        }
    }

    private RowsAccessibility() {
    }

    /**
     * Publishes the container's selection facet and, where a row can be activated from the
     * container, its {@code PRESS}.
     *
     * @param a         the builder, positioned on the container
     * @param selection what the selection allows
     * @param required  whether the container never rests with nothing selected
     * @param canPress  whether there is a cursor row for the container's {@code PRESS} to activate
     */
    public static void describeContainer(Accessibility a, Selection selection, boolean required,
                                         boolean canPress) {
        a.selection(selection == Selection.MULTI, required);
        if (canPress) {
            a.action(Action.PRESS);
        }
    }

    /**
     * Publishes one row's membership, its open state, its cursor mark and its verbs, from the
     * facts alone. Allocates nothing.
     *
     * @param a          the builder, positioned on the row
     * @param offer      whether the verbs are delegated from a widget child or owned by a synthetic one
     * @param selection  what the container's selection allows
     * @param selected   whether the row is in the selection
     * @param position   the row's one-based position among the rows it is counted with
     * @param size       how many rows it is counted with
     * @param expandable whether the row opens
     * @param expanded   whether it is open; ignored when it does not open
     * @param cursor     whether the row is the cursor <i>and</i> the widget has the keyboard
     * @param press      whether the row has an activation of its own (decision 80)
     * @param focus      whether the cursor may be moved onto this row by a verb (decision 11: false
     *                   where the cursor is the selection; false for a child that takes the keyboard
     *                   itself and so carries the walk's free verb; false for a refused day)
     * @param reveal     whether the row may be scrolled into view by a verb (decision 81: false
     *                   for a child that takes the keyboard itself)
     */
    public static void describeRow(Accessibility a, Offer offer, Selection selection,
                                   boolean selected, int position, int size,
                                   boolean expandable, boolean expanded, boolean cursor,
                                   boolean press, boolean focus, boolean reveal) {
        a.selectionItem(selected, position, size);
        if (expandable) {
            a.expand(expanded);
        }
        if (cursor) {
            a.state(Accessible.State.ACTIVE);
        }
        if (selection != Selection.NONE) {
            offer(a, offer, Action.SELECT);
            if (selection == Selection.MULTI) {
                offer(a, offer, selected ? Action.DESELECT : Action.ADD_TO_SELECTION);
            }
        }
        if (expandable) {
            offer(a, offer, expanded ? Action.COLLAPSE : Action.EXPAND);
        }
        if (press) {
            offer(a, offer, Action.PRESS);
        }
        if (focus) {
            offer(a, offer, Action.FOCUS);
        }
        if (reveal) {
            offer(a, offer, Action.SCROLL_INTO_VIEW);
        }
    }

    /**
     * Publishes a member that has no container node of its own — a radio button, whose group is
     * a {@code ButtonGroup} and not a widget — with its membership and its selection verbs, by
     * the same rules as {@link #describeRow} (decision 107, 2026-09-22). The verbs are the
     * member's own, because the node is the widget's. No cursor mark, no {@code FOCUS} and no
     * {@code SCROLL_INTO_VIEW}: a member that is a focusable widget carries the walk's free verbs
     * and holds the keyboard itself, and no expand and no press, because no containerless member
     * opens or activates.
     *
     * @param a         the builder, positioned on the member
     * @param selection what the group's selection allows
     * @param selected  whether the member is selected
     * @param position  its one-based position in the group, or 0 with no group
     * @param size      the group's size, or 0 with no group
     */
    public static void describeContainerlessRow(Accessibility a, Selection selection,
                                                boolean selected, int position, int size) {
        a.containerlessSelectionItem(selected, position, size);
        if (selection != Selection.NONE) {
            a.action(Action.SELECT);
            if (selection == Selection.MULTI) {
                a.action(selected ? Action.DESELECT : Action.ADD_TO_SELECTION);
            }
        }
    }

    private static void offer(Accessibility a, Offer offer, Action verb) {
        if (offer == Offer.DELEGATED) {
            a.delegate(verb);
        } else {
            a.action(verb);
        }
    }

    /**
     * Performs a verb a reader sent to a row, by the rules above.
     *
     * @param host the widget's mechanisms
     * @param row  the row addressed
     * @param verb the verb
     * @return whether it was performed; false for a verb the row's state refuses
     */
    public static <R> boolean performOnRow(Host<R> host, R row, Action verb) {
        switch (verb) {
            case SELECT -> {
                if (host.selection() == Selection.NONE) {
                    return false;
                }
                return host.select(row, false);
            }
            case ADD_TO_SELECTION, DESELECT -> {
                if (host.selection() != Selection.MULTI
                        || host.isSelected(row) != (verb == Action.DESELECT)) {
                    return false;
                }
                host.toggleSelection(row, false);
                return true;
            }
            case EXPAND, COLLAPSE -> {
                boolean open = verb == Action.EXPAND;
                if (!host.isExpandable(row) || host.isExpanded(row) == open) {
                    return false;
                }
                host.setExpanded(row, open);
                return true;
            }
            case FOCUS -> {
                if (host.cursorIsTheSelection() || !host.canBeCursor(row)) {
                    return false;
                }
                host.moveCursor(row);
                return true;
            }
            case SCROLL_INTO_VIEW -> {
                host.reveal(row);
                return true;
            }
            case PRESS -> {
                if (!host.rowsActivate()) {
                    return false;
                }
                host.activate(row);
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}

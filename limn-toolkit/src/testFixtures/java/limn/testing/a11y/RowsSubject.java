package limn.testing.a11y;

import limn.scene.Widget;

import java.util.List;

/**
 * A widget of the {@code ROWS} shape (ADR 045 §1) as the rows contract sees it: something that
 * publishes a selection and members of it, which the contract can build fresh, read the
 * selection and the cursor of through the widget's own API, and ask three things about that the
 * published tree cannot say. Those three are the variants of the shape (§3): whether the cursor
 * is the selection or separate from it, how rows beyond the box are reached, and whether there
 * is a multiple selection to enter.
 *
 * <p>A subject builds its rows as plain cells that cannot take the keyboard, so that what the
 * contract reads on a row is the row's and not the walk's free verbs on a focusable child. The
 * rows are named, uniquely, in tree order; "row {@code i}" everywhere below is the {@code i}th
 * name of {@link #rowNames()}, which is also the {@code i}th member the tree publishes while
 * nothing has scrolled.
 */
public interface RowsSubject {

    /** How the widget reaches a row outside its box. */
    enum Scrolling {
        /** Every row always fits; there is nothing to reveal. */
        NONE,
        /** The widget scrolls its own rows. */
        ITSELF,
        /** The widget is laid out inside a scroll view that reveals a row for it. */
        IN_A_SCROLL_VIEW
    }

    /**
     * Builds a fresh widget with the rows {@link #rowNames()} names, nothing selected, and returns
     * the root to bind: the widget itself, or a box around it that makes the box smaller than the
     * rows when {@link #scrolling()} is not {@link Scrolling#NONE}. Called once per case.
     */
    Widget build();

    /** @return the widget {@link #build()} made last: the container whose rows these are */
    Widget widget();

    /** @return the rows' names, unique, in tree order */
    List<String> rowNames();

    /**
     * @return true where the cursor is the selection (a list, a segmented control, a tab strip);
     *     false where a row can be the cursor without being selected (a tree, a table, a calendar)
     */
    boolean cursorIsTheSelection();

    /** @return how a row beyond the box is reached */
    Scrolling scrolling();

    /**
     * Puts the widget into multiple selection through its API.
     *
     * @return false when the widget has no multiple selection, in which case nothing changed
     */
    boolean enterMultipleSelection();

    /** Selects row {@code row} through the widget's API, as an application would. */
    void select(int row);

    /** @return the rows the widget's API says are selected, ascending */
    List<Integer> selectedRows();

    /**
     * @return the row the widget's API says is the cursor, or -1 when there is none; where the
     *     cursor is the selection, the selected row
     */
    int cursorRow();

    /** @return the row last activated through the widget's own callback, or -1 */
    int lastActivated();
}

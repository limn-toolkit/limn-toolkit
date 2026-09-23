package limn.components;

/**
 * How many rows a list-shaped widget lets the user select: the one vocabulary of
 * {@link ListView}, {@link limn.components.table.Table} and {@link limn.components.tree.Tree}.
 *
 * <p>The gestures are the same in all three. A click selects one row; with {@code MULTI}, the
 * command modifier (Ctrl, or Cmd on macOS) toggles one, Shift extends a range from the last row
 * clicked, and Ctrl+A or Cmd+A takes every row. The date widgets choose days, not rows, and keep
 * their own {@code CalendarView.SelectionMode}.
 */
public enum SelectionMode {
    /** Nothing is ever selected; the keyboard cursor still moves. */
    NONE,
    /** One row. */
    SINGLE,
    /** Any number of rows: Shift for a range, the command modifier to toggle one. */
    MULTI
}

package limn.components.internal.a11y;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;
import limn.accessibility.CellFacet;

/**
 * The {@code GRID} shape's container half, written once: what a table and its cells publish beyond
 * what its rows do. A grid is rows whose members carry cells: the rows are
 * {@link RowsAccessibility}'s, and what this adds is the {@code TableFacet} with its counts, the
 * header cells at row −1 with the sort they carry, the data cells found by their facet, and the
 * summary cells at row −2. Until this, {@code Table} and {@code CalendarView} each wrote that half
 * in its own hook.
 *
 * <p><b>The rules:</b>
 * <ul>
 *   <li>the container is a {@code TABLE} carrying a {@code TableFacet} with the rows and
 *       columns it shows;</li>
 *   <li>a header cell is a {@code COLUMN_HEADER} at row −1 carrying the direction its column
 *       is sorted in, and offers {@code PRESS} exactly when the column sorts; the
 *       localized phrase a platform reads the direction from is the widget's (its strings);</li>
 *   <li>a data cell is a {@code CELL} at its row and column, offers {@code FOCUS} where the
 *       grid's cursor is a cell (a table's), and carries {@code ACTIVE} while it is that
 *       cursor and the widget has the keyboard; a calendar's day cell is a rows member too and
 *       takes its verbs from that shape;</li>
 *   <li>a summary cell is a {@code CELL} at row −2 and takes no verb.</li>
 * </ul>
 */
public final class GridAccessibility {

    /**
     * The mechanisms a grid already has over its column and cell indices, which
     * {@link #performOnHeader} and {@link #performOnCell} drive by the rules.
     */
    public interface Host {

        /** @return whether the column's header sorts the rows on a press */
        boolean isSortable(int column);

        /** Sorts by the column, as a click on its header does, remembering it for the header's cursor. */
        void sort(int column);

        /** Takes the keyboard and moves the cursor onto the cell, selecting nothing. */
        void focusCell(int row, int column);
    }

    private GridAccessibility() {
    }

    /**
     * Publishes the container: its role and the counts.
     *
     * @param a       the builder, positioned on the container
     * @param rows    how many rows it shows
     * @param columns how many columns it shows
     */
    public static void describeGrid(Accessibility a, int rows, int columns) {
        a.role(Accessible.Role.TABLE);
        a.table(rows, columns);
    }

    /**
     * Publishes a header cell: its role, its place at row −1, the sort it carries and, where the
     * column sorts, its {@code PRESS}. Allocates nothing.
     *
     * @param a        the builder, positioned on the header cell
     * @param column   the column it heads
     * @param sortable whether a press sorts the rows by this column
     * @param sort     the direction the rows are sorted in by this column, or {@code NONE}
     */
    public static void describeHeaderCell(Accessibility a, int column, boolean sortable,
                                          CellFacet.Sort sort) {
        a.role(Accessible.Role.COLUMN_HEADER);
        a.cell(-1, column, sort);
        if (sortable) {
            a.action(Action.PRESS);
        }
    }

    /**
     * Publishes a data cell: its role, its place, and where the grid's cursor is a cell, its
     * {@code FOCUS} and its cursor mark. Allocates nothing.
     *
     * @param a      the builder, positioned on the cell
     * @param row    its row
     * @param column its column
     * @param focus  whether the cursor may be moved onto it by a verb (a table's cell; false
     *               for a calendar's day, whose verbs are the rows shape's)
     * @param cursor whether it is the cursor <i>and</i> the widget has the keyboard
     */
    public static void describeCell(Accessibility a, int row, int column, boolean focus,
                                    boolean cursor) {
        a.role(Accessible.Role.CELL);
        a.cell(row, column);
        if (focus) {
            a.action(Action.FOCUS);
        }
        if (cursor) {
            a.state(Accessible.State.ACTIVE);
        }
    }

    /**
     * Publishes a summary cell: a {@code CELL} at row −2 with no verb.
     *
     * @param a      the builder, positioned on the cell
     * @param column its column
     */
    public static void describeSummaryCell(Accessibility a, int column) {
        a.role(Accessible.Role.CELL);
        a.cell(-2, column);
    }

    /**
     * Performs a verb a reader sent to a header cell.
     *
     * @param host   the widget's mechanisms
     * @param column the column headed
     * @param verb   the verb
     * @return whether it was performed; false for anything but a press on a column that sorts
     */
    public static boolean performOnHeader(Host host, int column, Action verb) {
        if (verb != Action.PRESS || !host.isSortable(column)) {
            return false;
        }
        host.sort(column);
        return true;
    }

    /**
     * Performs a verb a reader sent to a data cell.
     *
     * @param host   the widget's mechanisms
     * @param row    the cell's row
     * @param column its column
     * @param verb   the verb
     * @return whether it was performed; false for anything but a focus
     */
    public static boolean performOnCell(Host host, int row, int column, Action verb) {
        if (verb != Action.FOCUS) {
            return false;
        }
        host.focusCell(row, column);
        return true;
    }
}

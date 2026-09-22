package limn.testing.a11y;

import limn.accessibility.CellFacet;
import limn.scene.Widget;

import java.util.List;

/**
 * A widget of the {@code GRID} shape as {@link GridContract} drives it: a table or a calendar,
 * whose container carries a {@code TableFacet}, whose header cells sit at row −1 and whose data
 * cells carry their place. The rows are the rows contract's; this subject says what the columns
 * are, which of them sort, and where the cursor is.
 */
public interface GridSubject {

    /** Builds the widget in a fresh root and returns the root. */
    Widget build();

    /** The widget itself, which holds the keyboard when focused. */
    Widget widget();

    /** The header cells' names, in column order. */
    List<String> columnNames();

    /** Whether a press on the column's header sorts the rows by it. */
    boolean isSortable(int column);

    /** The direction the rows are sorted in by the column now, or {@code NONE}. */
    CellFacet.Sort sortOf(int column);

    /**
     * The cell the widget's cursor is on, as {@code {row, column}} in the published grid's
     * coordinates, or {@code null} while it is on no cell.
     */
    int[] cursor();
}

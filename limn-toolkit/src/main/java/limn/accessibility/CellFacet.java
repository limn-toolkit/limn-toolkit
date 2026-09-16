package limn.accessibility;

import java.util.Objects;

/**
 * One cell of a table: where it sits in the grid, and — on a header cell — which way the rows it
 * sorts are running.
 *
 * <p>The row is the <b>view</b> position — the row a user counting down the table sees — and
 * the column is the shown column's index, so a sorted table and a table with a hidden column both
 * report what is on screen. A cell outside the data rows carries a negative row: {@code -1} in the
 * header row, {@code -2} in the footer row, the summary a table pins under its rows. Spans are
 * always one; the toolkit's table has no merged cells.
 *
 * <p>A cell's column header is found by structure and not carried here: it is the child at this
 * cell's column index of the table node's header group, which is the table's first
 * {@link Accessible.Role#GROUP} child. Carrying its identifier would make a facet a bridge reads
 * on its own thread depend on a resolution that happens after the walk, which is what relations
 * are for and cells are too many to be; ADR 041 §7.
 *
 * @param row    the cell's row as shown, from zero; {@code -1} for a header cell, {@code -2}
 *               for a footer cell
 * @param column the cell's column as shown, from zero
 * @param sort   which way this column's rows are running, on a header cell; {@link Sort#NONE}
 *               everywhere else and on a header whose column is not the one sorted by
 */
public record CellFacet(int row, int column, Sort sort) {

    /**
     * Which way a sorted column's rows run (decision 36, settled 2026-09-15).
     *
     * <p>Three values and not four. Each platform has its own carrier and each carries exactly
     * these: Windows an {@code ItemStatus} phrase on the header and the same words as
     * {@code HelpText} (File Explorer's convention, read on the guest; NVDA 2024.4.2 has no
     * {@code ItemStatus} handler and speaks a description), Linux Orca's {@code sort} object
     * attribute, macOS {@code AXSortDirection}. Orca also understands {@code other}, for a column
     * sorted by something the three words cannot describe; nothing in the toolkit sorts that way,
     * and a value no widget can produce is a value no bridge could be tested against, so it is
     * left out until a widget needs it.
     */
    public enum Sort {

        /** Not sorted by this column, or not a header cell at all. */
        NONE,

        /** The rows run from the smallest value of this column to the largest. */
        ASCENDING,

        /** The rows run from the largest value of this column to the smallest. */
        DESCENDING
    }

    /**
     * @throws NullPointerException if {@code sort} is {@code null}; a facet is a value a bridge
     *                              reads on a platform's own thread, and a null there is a crash
     *                              inside a native callback rather than an exception anybody sees
     */
    public CellFacet {
        Objects.requireNonNull(sort, "sort");
    }

    /**
     * A cell that is not a sorted header: every data cell, every footer cell, and a header whose
     * column is not the one the table is sorted by.
     *
     * @param row    the cell's row as shown, from zero; {@code -1} for a header cell, {@code -2}
     *               for a footer cell
     * @param column the cell's column as shown, from zero
     */
    public CellFacet(int row, int column) {
        this(row, column, Sort.NONE);
    }
}

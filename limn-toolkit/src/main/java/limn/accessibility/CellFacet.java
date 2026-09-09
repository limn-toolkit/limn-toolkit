package limn.accessibility;

/**
 * One cell of a table: where it sits in the grid.
 *
 * <p>The row is the <b>view</b> position — the row a user counting down the table sees — and
 * the column is the shown column's index, so a sorted table and a table with a hidden column both
 * report what is on screen. A header cell carries a row of {@code -1}: it is in the header row,
 * which is not a data row. Spans are always one; the toolkit's table has no merged cells.
 *
 * <p>A cell's column header is found by structure and not carried here: it is the child at this
 * cell's column index of the table node's header group, which is the table's first
 * {@link Accessible.Role#GROUP} child. Carrying its identifier would make a facet a bridge reads
 * on its own thread depend on a resolution that happens after the walk, which is what relations
 * are for and cells are too many to be; ADR 041 §7.
 *
 * @param row    the cell's row as shown, from zero, or {@code -1} for a header cell
 * @param column the cell's column as shown, from zero
 */
public record CellFacet(int row, int column) {
}

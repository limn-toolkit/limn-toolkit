/**
 * A table: columns over a list the application owns, virtualized on both axes.
 *
 * <p>{@link limn.components.table.Table} is the widget; {@link limn.components.table.Column}
 * names a title, how a row becomes a cell value, how that value becomes text, how rows compare
 * on it and how wide it is. The application keeps its rows in a {@code List} the table holds by
 * reference and re-reads on {@link limn.components.table.Table#refresh()}; nothing is copied and
 * nothing is read until it is on screen, so a table over a million rows costs what one over
 * twenty does.
 *
 * <p>A cell is a value drawn as text until its column asks for a widget
 * ({@link limn.components.table.Column#widget}); those cells are real children, mounted and
 * recycled with their row, and they are the whole of in-row interaction: the table does not edit
 * cells in place, by decision, and a record is edited whole, in a dialog or a panel (ADR 041 §6).
 * Sorting keeps a permutation over the application's list and never reorders it, so the selection
 * &mdash; a set of model rows &mdash; survives a sort. ADR 041 is the record.
 */
package limn.components.table;

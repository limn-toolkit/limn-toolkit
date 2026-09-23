package limn.accessibility;

/**
 * A grid of rows and columns over data: what a table publishes about its own shape.
 *
 * <p>Both counts are the <b>model's</b> numbers and not the tree's, for the reason
 * {@link SelectionItemFacet} gives: a table publishes only the rows it has realized, and a user
 * asking how big it is wants the data's answer. A bridge turns this into {@code IGridProvider}'s
 * counts, {@code accessibilityRowCount} and {@code accessibilityColumnCount}, and the AT-SPI
 * {@code Table} interface's {@code NRows} and {@code NColumns}.
 *
 * @param rowCount    how many data rows the model holds; the header row is not one of them
 * @param columnCount how many columns are shown
 */
public record TableFacet(int rowCount, int columnCount) {
}

package limn.demo.site;

import limn.components.Label;
import limn.components.table.Column;
import limn.components.table.Table;

import java.util.List;

/**
 * The table of the three "coming from" pages, apart from {@link MigrationExample} because a
 * table's {@code Column} and a layout's {@code Column} share a name: here a table is a list of
 * the application's own objects and a column says how to read one, with no model class between.
 */
public final class MigrationTableExample {

    private MigrationTableExample() {
    }

    /** An order as the application already holds it. */
    public record Order(String number, String customer, double total) {
    }

    // #region guide:migrate-table
    static Table<Order> orders(List<Order> rows, Label status) {
        Table<Order> table = new Table<>(List.of(
                Column.text("Order", Order::number).width(90),
                Column.text("Customer", Order::customer).weight(1),
                Column.numeric("Total", Order::total).width(100)));
        table.setRows(rows);             // the list itself; sorting by a header is built in
        table.onActivate(row -> status.setText("Opened " + table.rows().get(row).number()));
        return table;
    }
    // #endregion
}

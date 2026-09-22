package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.Theme;
import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.scene.Change;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo of {@link Table}: a hundred thousand rows over five columns, one of them a widget
 * column, with multi-selection, header sorting and a status line. Only the rows in the
 * viewport are realized, so the size of the list costs nothing.
 */
final class TableScene {

    private TableScene() {
    }

    /** One row: a record, as most rows are. */
    record Order(int id, String customer, String status, double total, boolean flagged) {
    }

    private static final String[] CUSTOMERS = {"Alves", "Bergström", "Chen", "Dubois", "Eze",
            "Fujimoto", "García", "Haddad", "Ivanova", "Johansson", "Kowalski", "Lee", "Müller",
            "Nakamura", "Okafor", "Petrov", "Quinn", "Rossi", "Silva", "Tanaka"};
    private static final String[] STATUSES = {"Open", "Paid", "Shipped", "Returned"};

    private static List<Order> orders(int count) {
        // Seeded, so two runs and two captures show the same rows.
        java.util.Random random = new java.util.Random(20260908);
        List<Order> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Order(100_000 + i, CUSTOMERS[random.nextInt(CUSTOMERS.length)],
                    STATUSES[random.nextInt(STATUSES.length)],
                    Math.round(random.nextDouble() * 249_000 + 1_000) / 100.0,
                    random.nextInt(9) == 0));
        }
        return rows;
    }

    static Scene create() {
        Theme.setCurrent(Theme.dark());
        List<Order> rows = orders(100_000);
        Label status = new Label("No selection");

        Column<Order> id = Column.<Order>text("Order", order -> "#" + order.id()).width(100)
                .footerCount();
        Column<Order> customer = Column.text("Customer", Order::customer).width(140).weight(1);
        Column<Order> state = Column.text("Status", Order::status).width(100);
        Column<Order> total = Column.currency("Total", Order::total,
                java.util.Currency.getInstance("BRL")).width(180).footerSum();
        Column<Order> flagged = Column.<Order>widget("Flagged",
                order -> new Checkbox(Checkbox.Variant.SWITCH, "").setChecked(order.flagged()))
                .width(90).sortable(false);

        Table<Order> table = new Table<>(List.of(id, customer, state, total, flagged));
        table.setRows(rows);
        table.setSelectionMode(Table.SelectionMode.MULTI);
        table.setSort(total, SortOrder.DESCENDING);
        // The status line mirrors the selection, so it watches: it hears the starting selection
        // below and a refresh that drops a row, as well as every click.
        table.observeChanges((source, change) -> {
            if (change.aspect() != Change.Aspect.SELECTION) {
                return;
            }
            int[] selected = table.selectedRows();
            status.setText(selected.length == 0 ? "No selection"
                    : selected.length + " selected, lead order #"
                            + rows.get(table.selectedRow()).id());
        });
        table.onActivate(index -> status.setText("Opened order #" + rows.get(index).id()));
        // A selection to start from, named by model row: the three rows shown third, fourth and
        // sixth under the default sort.
        table.setSelectedRows(table.viewToModel(2), table.viewToModel(3), table.viewToModel(5));

        Row actions = new Row();
        actions.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        actions.add(new Button("Select all").onAction(table::selectAll));
        actions.add(new Button("Clear").onAction(table::clearSelection));
        actions.add(new Button("Model order").onAction(() -> table.setSort(null, SortOrder.NONE)));
        actions.add(Expanded.of(status));

        limn.scene.layout.Column page = new limn.scene.layout.Column();
        page.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(Expanded.of(table));
        page.add(actions);
        Widget root = new Padding(Insets.all(16), page);
        Scene scene = new Scene(root);
        scene.setBackground(Theme.current().background());
        stateFor(scene, table, customer);
        return scene;
    }

    /**
     * The state a render asks for through {@code LIMN_TABLE_DEMO}, so the owner can review a
     * keyboard state a capture cannot reach by itself: {@code focus} puts the keyboard in the
     * table on its lead row; {@code sorted} then sorts by customer, which moves that row far
     * down and lets the reveal show it (decision 40 of 2026-09-14); {@code header} moves the
     * keyboard on to the header's column cursor (decision 36); {@code strip} widens the
     * customer column past the window, reserves the bars' strips and scrolls to the last column
     * after the first layout, which is where the reserved vertical strip used to cover a strip's
     * width of that column (a60a2b1). Unset, the scene is as it was.
     */
    private static void stateFor(Scene scene, Table<Order> table, Column<Order> customer) {
        String state = System.getenv("LIMN_TABLE_DEMO");
        if (state == null || state.isEmpty()) {
            return;
        }
        scene.requestFocus(table);
        switch (state) {
            case "focus" -> {
            }
            case "sorted" -> table.setSort(customer, SortOrder.ASCENDING);
            case "header" -> {
                // Shift+Tab from the rows: the header is the stop before them (decision 36).
                scene.keyEvent(limn.input.Keys.TAB, true, false, limn.input.Keys.MOD_SHIFT);
                scene.inputBatchEnded();
            }
            case "strip" -> {
                customer.width(420).weight(0);
                table.setBarLayout(limn.components.ScrollGutters.Layout.RESERVED)
                        .setScrollbarPolicy(limn.components.ScrollBar.Policy.ALWAYS);
                // After the first layout, when the columns have widths to scroll across.
                limn.concurrent.Ui.post(() -> table.scrollBy(10_000, 0));
            }
            default -> System.err.println("LIMN_TABLE_DEMO: unknown state " + state);
        }
    }
}

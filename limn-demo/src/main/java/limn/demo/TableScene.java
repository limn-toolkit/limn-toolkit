package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.Menu;
import limn.components.PopupMenu;
import limn.components.ProgressBar;
import limn.components.SelectionMode;
import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.concurrent.Ui;
import limn.i18n.DateFormats;
import limn.i18n.I18nString;
import limn.icons.tabler.TablerText;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Wrap;

import java.text.Collator;
import java.time.LocalDate;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;

import static limn.testing.SceneDriver.drive;

/**
 * A stock list: a catalogue wider than the window, its first two columns kept in view while the
 * figures scroll under them, numbers aligned to the end, a summary row, and a strip of switches
 * over it for how the table looks and who sorts it. Only the rows in view are realized.
 */
final class TableScene {

    private TableScene() {
    }

    /** What the page says. */
    static final class Text {
        static final I18nString SKU = new I18nString("showcase.tables.sku", "SKU");
        static final I18nString PRODUCT = new I18nString("showcase.tables.product", "Product");
        static final I18nString CATEGORY = new I18nString("showcase.tables.category", "Category");
        static final I18nString WAREHOUSE = new I18nString("showcase.tables.warehouse", "Warehouse");
        static final I18nString ON_HAND = new I18nString("showcase.tables.onHand", "On hand");
        static final I18nString RESERVED = new I18nString("showcase.tables.reserved", "Reserved");
        static final I18nString AVAILABLE = new I18nString("showcase.tables.available", "Available");
        static final I18nString REORDER = new I18nString("showcase.tables.reorderAt", "Reorder at");
        static final I18nString UNIT_COST = new I18nString("showcase.tables.unitCost", "Unit cost");
        static final I18nString VALUE = new I18nString("showcase.tables.stockValue", "Stock value");
        static final I18nString COUNTED = new I18nString("showcase.tables.lastCounted", "Last counted");
        static final I18nString STOCK = new I18nString("showcase.tables.stock", "Stock");

        static final I18nString COLUMNS = new I18nString("showcase.tables.columns", "Columns");
        static final I18nString STRIPES = new I18nString("showcase.tables.stripes", "Stripes");
        static final I18nString HEADER = new I18nString("showcase.tables.header", "Header");
        static final I18nString MOVE = new I18nString("showcase.tables.moveColumns", "Move columns");
        static final I18nString KEEP = new I18nString("showcase.tables.keepProduct", "Keep product in view");
        static final I18nString SERVER = new I18nString("showcase.tables.serverSort", "Sort on the server");
        static final I18nString SUMMARY = new I18nString("showcase.tables.summary", "Summary");
        static final I18nString SUM = new I18nString("showcase.tables.sum", "Total");
        static final I18nString AVERAGE = new I18nString("showcase.tables.average", "Average");
        static final I18nString MINIMUM = new I18nString("showcase.tables.minimum", "Lowest");
        static final I18nString MAXIMUM = new I18nString("showcase.tables.maximum", "Highest");

        static final I18nString SORTING = new I18nString("showcase.tables.sorting", "Sorting on the server…");
        static final I18nString SORTED = new I18nString("showcase.tables.sorted", "Sorted by {0} on the server.");
        static final I18nString OPENED = new I18nString("showcase.tables.opened", "{0} opened.");
        static final limn.i18n.PluralString SELECTED = limn.i18n.PluralString.of(
                "showcase.tables.selected", "{0} product selected.", "{0} products selected.");

        private Text() {
        }
    }

    /** One stock line: a product, where it is kept, and how many there are. */
    record Item(String sku, String product, String category, String warehouse, int onHand, int reserved,
                int reorderAt, double unitCost, LocalDate counted) {
        int available() {
            return onHand - reserved;
        }

        double value() {
            return onHand * unitCost;
        }
    }

    private record Family(String prefix, String category, String name, double cost, String[] variants) {
    }

    private static final String[] COLOURS = {"black", "grey", "white", "oak"};
    private static final String[] MODELS = {"standard", "pro", "compact"};
    private static final String[] PACKS = {"pack of 10", "pack of 50", "box of 500"};
    private static final String[] SIZES = {"small", "medium", "large"};
    private static final Family[] FAMILIES = {
            new Family("CHR", "Furniture", "Office chair", 189, COLOURS),
            new Family("DSK", "Furniture", "Standing desk", 459, COLOURS),
            new Family("CAB", "Furniture", "Filing cabinet", 239, COLOURS),
            new Family("BKC", "Furniture", "Bookcase", 149, COLOURS),
            new Family("VCH", "Furniture", "Visitor chair", 99, COLOURS),
            new Family("MON", "Electronics", "Monitor, 27-inch", 289, MODELS),
            new Family("DCK", "Electronics", "USB-C dock", 129, MODELS),
            new Family("KBD", "Electronics", "Wireless keyboard", 59, MODELS),
            new Family("MSE", "Electronics", "Wireless mouse", 29, MODELS),
            new Family("HDS", "Electronics", "Headset", 79, MODELS),
            new Family("CAM", "Electronics", "Webcam", 69, MODELS),
            new Family("PRN", "Electronics", "Laser printer", 349, MODELS),
            new Family("LBL", "Electronics", "Label printer", 119, MODELS),
            new Family("PAP", "Office supplies", "Copy paper, A4", 4.9, PACKS),
            new Family("PEN", "Office supplies", "Ballpoint pens", 0.6, PACKS),
            new Family("NTE", "Office supplies", "Sticky notes", 1.2, PACKS),
            new Family("STP", "Office supplies", "Staples", 0.9, PACKS),
            new Family("CLP", "Office supplies", "Binder clips", 0.4, PACKS),
            new Family("BND", "Office supplies", "Ring binder", 2.8, PACKS),
            new Family("MRK", "Office supplies", "Whiteboard markers", 1.1, PACKS),
            new Family("NTB", "Office supplies", "Notebook, A5", 3.4, PACKS),
            new Family("ENV", "Office supplies", "Envelopes, C4", 0.3, PACKS),
            new Family("TNR", "Office supplies", "Toner cartridge", 64, MODELS),
            new Family("BOX", "Packaging", "Shipping box", 1.6, SIZES),
            new Family("TAP", "Packaging", "Packing tape", 2.2, SIZES),
            new Family("BWR", "Packaging", "Bubble wrap roll", 12.5, SIZES),
            new Family("PLW", "Packaging", "Pallet wrap", 18, SIZES),
            new Family("MAI", "Packaging", "Padded mailer", 0.7, SIZES),
    };
    private static final String[] WAREHOUSES = {"Northgate", "Riverside", "Eastfield"};

    /** The catalogue: one line per product, so no two rows say the same thing. */
    static List<Item> items() {
        // Seeded, so two runs and two captures show the same stock.
        Random random = new Random(20261015);
        LocalDate first = LocalDate.of(2026, 9, 1);
        List<Item> rows = new ArrayList<>();
        int number = 1001;
        for (Family family : FAMILIES) {
            for (int v = 0; v < family.variants().length; v++) {
                double multiplier = 1 + v * (family.variants() == PACKS ? 4.2 : 0.35);
                String warehouse = WAREHOUSES[(number + v) % WAREHOUSES.length];
                int onHand = random.nextInt(640);
                int reserved = Math.min(onHand, random.nextInt(90));
                rows.add(new Item(family.prefix() + "-" + number++, family.name() + ", " + family.variants()[v],
                        family.category(), warehouse, onHand, reserved, 20 + random.nextInt(11) * 10,
                        Math.round(family.cost() * multiplier * 100) / 100.0,
                        first.plusDays(random.nextInt(44))));
            }
        }
        return rows;
    }

    /** The page, and what {@link #stateFor} drives in it. */
    private record Parts(Widget<?> page, Table<Item> table, Column<Item> product) {
    }

    static Scene create() {
        Parts parts = parts();
        Scene scene = new Scene(new Padding(Insets.all(16), parts.page()));
        stateFor(scene, parts.table(), parts.product());
        return scene;
    }

    /** The page without a scene of its own, for the showcase's Table page. */
    static Widget<?> content() {
        return parts().page();
    }

    private static Parts parts() {
        List<Item> rows = items();
        List<Item> catalogue = List.copyOf(rows); // the order the server keeps them in
        Currency money = Currency.getInstance("USD");

        Column<Item> sku = Column.<Item>text(Text.SKU, Item::sku).width(110);
        Column<Item> product = Column.<Item>text(Text.PRODUCT, Item::product).width(250).footer(Text.SUM);
        Column<Item> category = Column.<Item>text(Text.CATEGORY, Item::category).width(144);
        Column<Item> warehouse = Column.<Item>text(Text.WAREHOUSE, Item::warehouse).width(116);
        Column<Item> onHand = Column.<Item>numeric(Text.ON_HAND, Item::onHand).width(112).footerSum();
        Column<Item> reserved = Column.<Item>numeric(Text.RESERVED, Item::reserved).width(112).footerSum();
        Column<Item> available = Column.<Item>numeric(Text.AVAILABLE, Item::available).width(112).footerSum();
        Column<Item> reorder = Column.<Item>numeric(Text.REORDER, Item::reorderAt).width(100).footerSum();
        Column<Item> cost = Column.<Item>currency(Text.UNIT_COST, Item::unitCost, money).width(112).footerSum();
        Column<Item> value = Column.<Item>currency(Text.VALUE, Item::value, money).width(140).footerSum();
        // A date reads at the end of its cell too, where the figures beside it end.
        Column<Item> counted = Column.<Item, LocalDate>of(Text.COUNTED, Item::counted,
                        DateFormats.date(FormatStyle.SHORT))
                .width(120).align(Column.Alignment.END).visible(false);
        List<Column<Item>> figures = List.of(onHand, reserved, available, reorder, cost, value);

        Table<Item> table = new Table<>(List.of(sku, product, category, warehouse, onHand, reserved, available,
                reorder, cost, value, counted));
        table.setAccessibleName(Text.STOCK);
        table.setRows(rows);
        table.setSelectionMode(SelectionMode.MULTI);
        table.setFrozenColumns(2);
        table.setSort(product, SortOrder.ASCENDING);
        table.onSelect(() -> Page.report(Text.SELECTED.format(table.selectedRows().length)));
        table.onActivate(index -> Page.report(Text.OPENED.format(rows.get(index).product())));

        // ---- what shows: the columns, the stripes, the header
        Button columns = new Button(Text.COLUMNS).setStyle(Button.Style.SECONDARY).setIcon(TablerText.COLUMNS.icon());
        columns.onAction(() -> {
            Menu menu = new Menu();
            for (Column<Item> column : List.of(category, warehouse, reserved, reorder, cost, counted)) {
                menu.addCheck(column.title(), column.isVisible(), shown -> {
                    column.visible(shown);
                    table.markNeedsLayout();
                });
            }
            new PopupMenu(menu).showAnchored(columns, 0, 0, columns.width(), columns.height());
        });
        Checkbox stripes = new Checkbox(Text.STRIPES).setChecked(true);
        stripes.onChange(table::setStriped);
        Checkbox header = new Checkbox(Text.HEADER).setChecked(true);
        header.onChange(table::setShowHeader);
        Checkbox move = new Checkbox(Text.MOVE).setChecked(true);
        move.onChange(table::setColumnReorderingAllowed);
        Checkbox keep = new Checkbox(Text.KEEP).setChecked(true);
        keep.onChange(on -> table.setFrozenColumns(on ? 2 : 0));

        // ---- the summary row: one aggregate over every figure, named under Product
        List<I18nString> kinds = List.of(Text.SUM, Text.AVERAGE, Text.MINIMUM, Text.MAXIMUM);
        ComboBox<I18nString> summary = ComboBox.localized(kinds);
        summary.setSelectedIndex(0);
        summary.setAccessibleName(Text.SUMMARY);
        summary.onSelect(() -> {
            int kind = Math.max(0, summary.selectedIndex());
            for (Column<Item> column : figures) {
                switch (kind) {
                    case 1 -> column.footerAverage();
                    case 2 -> column.footerMin();
                    case 3 -> column.footerMax();
                    default -> column.footerSum();
                }
            }
            product.footer(kinds.get(kind));
            table.refresh();
        });
        Label summaryLabel = new Label(Text.SUMMARY).setLabelFor(summary);

        // ---- who sorts: the table, or a server the header asks and waits for
        ProgressBar busy = new ProgressBar().setIndeterminate(true).setPreferredWidth(80);
        busy.setVisible(false);
        Map<Column<Item>, Comparator<Item>> orders = comparators(sku, product, category, warehouse, onHand,
                reserved, available, reorder, cost, value, counted);
        int[] asked = {0};
        BiConsumer<Column<Item>, SortOrder> server = (column, order) -> {
            int ticket = ++asked[0];
            busy.setVisible(true);
            Page.report(Text.SORTING.get());
            List<Item> snapshot = List.copyOf(catalogue);
            Comparator<Item> by = order == SortOrder.NONE || column == null ? null
                    : order == SortOrder.DESCENDING ? orders.get(column).reversed() : orders.get(column);
            Ui.async(() -> {
                try {
                    Thread.sleep(Long.getLong("limn.demo.serverSortMillis", 700));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                List<Item> sorted = new ArrayList<>(snapshot);
                if (by != null) {
                    sorted.sort(by);
                }
                return sorted;
            }).thenAccept(sorted -> {
                if (ticket != asked[0]) {
                    return; // a later click asked for another order
                }
                rows.clear();
                rows.addAll(sorted);
                table.refresh();
                busy.setVisible(false);
                if (column != null && order != SortOrder.NONE) {
                    Page.report(Text.SORTED.format(column.title().get()));
                }
            });
        };
        Checkbox onServer = new Checkbox(Text.SERVER);
        onServer.onChange(on -> {
            asked[0]++; // whatever was in flight answers nobody now
            busy.setVisible(false);
            if (on) {
                table.onSortRequest(server);
                // The table drops its own order at once, so the server is asked for the one the
                // header shows.
                server.accept(table.sortColumn(), table.sortOrder());
            } else {
                rows.clear();
                rows.addAll(catalogue);
                table.onSortRequest(null);
                table.refresh();
            }
        });

        Wrap tools = new Wrap();
        tools.gap(16).runGap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        tools.add(columns);
        tools.add(stripes);
        tools.add(header);
        tools.add(move);
        tools.add(keep);
        tools.add(onServer);
        tools.add(busy);
        tools.add(Page.row(8, summaryLabel, summary));

        limn.scene.layout.Column page = Page.column();
        page.add(tools);
        page.add(Expanded.of(table));
        return new Parts(page, table, product);
    }

    /** How the server orders the rows on each column: the same values the cells show. */
    private static Map<Column<Item>, Comparator<Item>> comparators(Column<Item> sku, Column<Item> product,
            Column<Item> category, Column<Item> warehouse, Column<Item> onHand, Column<Item> reserved,
            Column<Item> available, Column<Item> reorder, Column<Item> cost, Column<Item> value,
            Column<Item> counted) {
        Collator collator = Collator.getInstance(Locale.ROOT);
        Map<Column<Item>, Comparator<Item>> by = new HashMap<>();
        by.put(sku, Comparator.comparing(Item::sku, collator));
        by.put(product, Comparator.comparing(Item::product, collator));
        by.put(category, Comparator.comparing(Item::category, collator));
        by.put(warehouse, Comparator.comparing(Item::warehouse, collator));
        by.put(onHand, Comparator.comparingInt(Item::onHand));
        by.put(reserved, Comparator.comparingInt(Item::reserved));
        by.put(available, Comparator.comparingInt(Item::available));
        by.put(reorder, Comparator.comparingInt(Item::reorderAt));
        by.put(cost, Comparator.comparingDouble(Item::unitCost));
        by.put(value, Comparator.comparingDouble(Item::value));
        by.put(counted, Comparator.comparing(Item::counted));
        return by;
    }

    /**
     * The state a render asks for through {@code LIMN_TABLE_DEMO}, so the owner can review a
     * keyboard state a capture cannot reach by itself: {@code focus} puts the keyboard in the
     * table on its lead row; {@code sorted} then sorts by product, descending, which moves that
     * row far down and lets the reveal show it; {@code header} moves the keyboard on to the
     * header's column cursor; {@code strip} widens the product column past the window, reserves
     * the bars' strips and scrolls to the last column after the first layout, which is where the
     * reserved vertical strip used to cover a strip's width of that column (a60a2b1). Unset, the
     * scene is as it was.
     */
    private static void stateFor(Scene scene, Table<Item> table, Column<Item> product) {
        String state = System.getenv("LIMN_TABLE_DEMO");
        if (state == null || state.isEmpty()) {
            return;
        }
        table.setSelectedRows(table.viewToModel(2), table.viewToModel(3), table.viewToModel(5));
        scene.requestFocus(table);
        switch (state) {
            case "focus" -> {
            }
            case "sorted" -> table.setSort(product, SortOrder.DESCENDING);
            case "header" -> {
                // Shift+Tab from the rows: the header is the stop before them (decision 36).
                drive(scene).keyEvent(limn.input.Keys.TAB, true, false, limn.input.Keys.MOD_SHIFT);
                drive(scene).inputBatchEnded();
            }
            case "strip" -> {
                table.setFrozenColumns(0);
                product.width(900);
                table.setBarLayout(limn.components.ScrollGutters.Layout.RESERVED)
                        .setScrollbarPolicy(limn.components.ScrollBar.Policy.ALWAYS);
                // After the first layout, when the columns have widths to scroll across.
                Ui.post(() -> table.scrollBy(10_000, 0));
            }
            default -> System.err.println("LIMN_TABLE_DEMO: unknown state " + state);
        }
    }
}

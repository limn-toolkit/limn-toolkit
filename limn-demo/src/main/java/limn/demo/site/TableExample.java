package limn.demo.site;

import limn.components.SelectionMode;

import limn.components.Label;
import limn.components.Theme;
import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.scene.Change;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * The worked table the guide's Lists and scrolling page is built from. Every region marked
 * here becomes a code block on that page, and this file is compiled by {@code ./gradlew check},
 * so the sample a reader copies is a sample that builds.
 */
public final class TableExample {

    private TableExample() {
    }

    /** One row of the table: a record, as most rows are. */
    public record Release(String name, String platform, int downloads, double price,
                          double size) {
    }

    /** A few rows, deterministic so two capture runs produce the same pixels. */
    public static List<Release> releases() {
        List<Release> rows = new ArrayList<>();
        String[] names = {"Meridian", "Halcyon", "Corvid", "Lumen", "Tessera", "Orrery",
                "Vantage", "Kestrel", "Sable", "Aurum", "Quill", "Zephyr"};
        String[] platforms = {"Windows", "macOS", "Linux"};
        for (int i = 0; i < names.length; i++) {
            rows.add(new Release(names[i], platforms[i % 3], 1200 + i * 731, 4.99 + i * 3,
                    2.5 + i * 0.7));
        }
        return rows;
    }

    /**
     * The whole example: columns, rows, a default sort and a selection listener.
     *
     * @param status where the selected row is shown
     * @return the table, ready to add to a scene
     */
    // #region guide:table
    public static Table<Release> table(Label status) {
        Column<Release> name = Column.text("Name", Release::name).width(140).weight(1)
                .footer("Total");
        Column<Release> platform = Column.text("Platform", Release::platform).width(100)
                .footerCount();
        Column<Release> downloads = Column.numeric("Downloads", Release::downloads,
                NumberFormats.compact()).width(110).footerSum();
        Column<Release> price = Column.currency("Price", Release::price,
                Currency.getInstance("USD")).width(110).footerAverage();
        Column<Release> size = Column.<Release, Double>of("Size", Release::size,
                (mb, locale) -> String.format(locale, "%.1f MB", mb)).width(90)
                .align(Column.Alignment.END)
                .footer(rows -> rows.stream().mapToDouble(Release::size).sum());

        Table<Release> table = new Table<>(List.of(name, platform, downloads, price, size));
        table.setAccessibleName(new I18nString("guide.table.releases", "Releases"));
        table.setRows(releases());
        table.setSort(downloads, SortOrder.DESCENDING);
        table.setSelectionMode(SelectionMode.MULTI);
        table.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.SELECTION) {
                status.setText(table.selectedRows().length + " selected");
            }
        });
        table.onActivate(row -> status.setText("Opened " + table.rows().get(row).name()));
        return table;
    }
    // #endregion

    // #region guide:table-row-button
    /**
     * A column of buttons that act on their own row. The factory is given the row it builds a
     * cell for and runs again for every row that comes into view, so each button holds on to its
     * row; no widget is ever shown for another row.
     */
    public static Column<Release> removeColumn(java.util.function.Consumer<Release> remove) {
        return Column.widget("", release -> new limn.components.Button("Remove")
                .onAction(() -> remove.accept(release)));
    }
    // #endregion

    /**
     * The example as a scene, for the check that holds the guides' own examples to the same
     * accessibility invariants the gallery's entries are held to.
     *
     * @return the screen below, in a scene of its own
     */
    public static Scene scene() {
        Scene scene = new Scene(screen());
        scene.setBackground(Theme.current().background());
        return scene;
    }

    /** The example as a screen: the table filling the space, the status line under it. */
    public static Widget<?> screen() {
        Label status = new Label("Nothing selected");
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(Expanded.of(table(status)));
        column.add(status);
        return column;
    }
}

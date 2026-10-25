package limn.components.table;

import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A column changed after its table was made is taken by the table: its width at the next layout,
 * its footer and how it sorts at once. The setters only stored a field, so an application had to
 * know to ask the table for a layout or a refresh, and a footer changed without one showed the old
 * text.
 */
class ColumnOnALiveTableTest {

    private HeadlessUi ui;

    private Column<String> name;
    private Column<String> size;
    private Table<String> table;
    private Scene scene;

    @BeforeEach
    void build() {
        ui = new HeadlessUi();
        name = Column.<String>text("Name", s -> s).width(120);
        size = Column.<String>text("Size", s -> s).width(80);
        table = new Table<>(List.of(name, size)).setRows(List.of("a", "b", "c"));
        scene = new Scene(table);
        scene.setTextRuler(TestRulers.FIXED);
    }

    @AfterEach
    void close() {
        ui.close();
    }

    private void layout() {
        scene.layoutPass(600, 300);
    }

    @Test
    void aWidthSetOnALiveTableIsTakenByItsNextLayout() {
        layout();
        assertEquals(120, table.widthOf(name), 0.5f);
        name.width(200);
        layout(); // a pass lays out only what asked for it
        assertEquals(200, table.widthOf(name), 0.5f);
    }

    @Test
    void aFooterSetOnALiveTableShowsAtOnce() {
        layout();
        size.footerCount();
        assertEquals("3", table.footerTextOf(size), "computed again without a refresh");
        size.footer("Total");
        assertEquals("Total", table.footerTextOf(size));
    }

    @Test
    void aColumnHiddenOnALiveTableLeavesItsNextLayout() {
        layout();
        size.visible(false);
        layout();
        assertEquals(0, table.widthOf(size), 0.5f);
    }

    /** The rows each announced change says, as "aspect/origin". */
    private List<String> watch() {
        List<String> heard = new ArrayList<>();
        table.observeChanges((source, change) -> heard.add(change.aspect() + "/" + change.origin()));
        return heard;
    }

    /** The model rows in the order shown. */
    private List<Integer> shown() {
        List<Integer> order = new ArrayList<>();
        for (int v = 0; v < table.rowCount(); v++) {
            order.add(table.viewToModel(v));
        }
        return order;
    }

    /**
     * A comparator set on the column a live table is sorted on orders the rows again at once, as a
     * sort does: the focus cell goes with its record and the selection stays with its own. It took
     * effect at the next refresh or header click, so the rows went on in the old order.
     */
    @Test
    void aComparatorSetOnTheSortedColumnSortsTheRowsAgainAtOnce() {
        table.setRows(List.of("b", "c", "a"));
        table.setSort(name, SortOrder.ASCENDING);
        layout();
        assertEquals(List.of(2, 0, 1), shown(), "a, b, c");
        table.setSelectedRow(1); // "c", shown last
        assertEquals(2, table.focusRow());
        List<String> heard = watch();

        name.comparator(Comparator.reverseOrder());

        assertEquals(List.of(1, 0, 2), shown(), "c, b, a without a refresh");
        assertEquals(1, table.selectedRow(), "the selection is by record");
        assertEquals(0, table.focusRow(), "the focus cell went with its record");
        assertTrue(heard.contains("CHILDREN/CODE"), "announced as a sort is: " + heard);
        assertSame(name, table.sortColumn());
        assertEquals(SortOrder.ASCENDING, table.sortOrder());
    }

    /** A column that stops being sortable while the rows are sorted on it lets go of the sort at once. */
    @Test
    void aSortedColumnMadeUnsortableGoesBackToTheModelsOrderAtOnce() {
        table.setRows(List.of("b", "c", "a"));
        table.setSort(name, SortOrder.DESCENDING);
        layout();
        assertEquals(List.of(1, 0, 2), shown());
        table.setSelectedRow(2); // "a"
        List<String> heard = watch();

        name.sortable(false);

        assertNull(table.sortColumn(), "the header shows no order on a column that cannot sort");
        assertEquals(SortOrder.NONE, table.sortOrder());
        assertEquals(List.of(0, 1, 2), shown(), "the model's order");
        assertEquals(2, table.focusRow(), "the focus cell went with its record");
        assertTrue(heard.contains("CHILDREN/CODE"), heard.toString());
    }

    /** A column the rows are not sorted on changes no order and announces nothing. */
    @Test
    void aComparatorOnAnotherColumnLeavesTheOrderAlone() {
        table.setRows(List.of("b", "c", "a"));
        table.setSort(name, SortOrder.ASCENDING);
        layout();
        List<String> heard = watch();

        size.comparator(Comparator.reverseOrder());
        size.sortable(false);

        assertEquals(List.of(2, 0, 1), shown());
        assertSame(name, table.sortColumn());
        assertEquals(List.of(), heard);
    }

    /**
     * A table whose sort request handler orders the rows has no order of its own to redo, so a
     * comparator changes nothing; a column made unsortable still loses the header's arrow.
     */
    @Test
    void underASortRequestHandlerOnlyTheHeadersOrderIsDropped() {
        table.setRows(List.of("b", "c", "a"));
        table.onSortRequest((column, order) -> { });
        table.setSort(name, SortOrder.ASCENDING);
        layout();
        assertEquals(List.of(0, 1, 2), shown(), "the application's order");

        name.comparator(Comparator.reverseOrder());
        assertEquals(List.of(0, 1, 2), shown());
        assertSame(name, table.sortColumn());

        name.sortable(false);
        assertNull(table.sortColumn());
        assertEquals(List.of(0, 1, 2), shown());
    }
}

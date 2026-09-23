package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.lwjgl.a11y.ProbeWindow;
import limn.components.Checkbox;
import limn.components.date.CalendarView;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real {@link Table} and a real {@link CalendarView} read the way AppKit reads a table, through a
 * scene over the platform's bridge with the platform left out: a cell found by its column and row, a
 * row's index, and a cell's column header (MACOS-NEW-4, MACOS-NEW-9, MACOS-NEW-10; semantics 2 and 3).
 */
@ExtendWith(PlatformFreeBridges.class)
class AxTableSceneTest {

    record Chore(String name, boolean done) {
    }

    private HeadlessUi ui;
    private final AtomicLong nanos = new AtomicLong();
    private AxBridge bridge;
    private Scene scene;

    @BeforeEach
    void clock() {
        ui = new HeadlessUi(nanos::get);
    }

    @AfterEach
    void close() {
        ui.close();
    }

    private void bind(Widget<?> widget) {
        scene = new Scene(new SizedBox(420, 360, widget), nanos::get);
        ProbeWindow window = new ProbeWindow();
        bridge = PlatformFreeBridges.make();
        window.accessibility = bridge;
        scene.bind(window);
        scene.renderFrame(new NoopCanvas(480, 400));
    }

    private AccessibleNode first(Accessible.Role role) {
        AccessibleTree tree = bridge.tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) return tree.node(i);
        }
        throw new AssertionError("no " + role + " published");
    }

    private Table<Chore> choresTable() {
        List<Chore> chores = new ArrayList<>();
        for (int i = 0; i < 4; i++) chores.add(new Chore("Chore " + i, i % 2 == 0));
        Table<Chore> table = new Table<>(List.of(
                Column.text("Name", Chore::name).width(160).footer("Four chores"),
                Column.<Chore>widget("Done", chore -> new Checkbox(Checkbox.Variant.SWITCH, chore.name()))
                        .width(120)));
        table.setRows(chores);
        return table;
    }

    @Test
    void aWidgetColumnsControlIsTheCellAtItsColumnAndRowAndItsHeaderIsTheColumnsTitle() {
        bind(choresTable());
        AxGrid grid = new AxGrid(bridge);
        AccessibleTree tree = bridge.tree();
        AccessibleNode table = first(Accessible.Role.TABLE);
        int rows = 0;
        for (AccessibleNode row : tree.children(table)) {
            if (row.role() != Accessible.Role.ROW) continue;
            AccessibleNode control = null;
            for (AccessibleNode child : tree.children(row)) {
                if (child.role() == Accessible.Role.SWITCH || child.role() == Accessible.Role.CHECK_BOX) {
                    control = child;
                }
            }
            assertTrue(control != null && control.cell() != null,
                    "decision 3: the control hangs under its row, carrying its cell facet");
            int r = control.cell().row();
            assertEquals(r, grid.index(row), "the row's index is its cells' row");
            assertEquals(bridge.elementFor(control.id()), grid.cellAt(table, 1, r),
                    "MACOS-NEW-10: the widget cell is found at its column and row");
            long[] header = grid.columnHeaderElements(control);
            assertTrue(header != null && header.length == 1, "and it is told its column's header");
            assertEquals("Done", bridge.nodeFor(header[0]).name());
            rows++;
        }
        assertEquals(4, rows);

        // M4 (decision 34): the columns a native NSTableView vends, over the real table.
        long[] columns = grid.columns(table);
        assertEquals(2, columns.length, "one column element per shown column");
        long[] done = grid.columnCells(table, 1, false);
        assertEquals(4, done.length, "the Done column's AXRows are its four controls");
        for (int r = 0; r < 4; r++) {
            assertEquals(r, bridge.nodeFor(done[r]).cell().row(), "in row order");
        }
        assertEquals("Done", bridge.nodeFor(grid.columnHeader(table, 1)).name());
        assertEquals(table.id(), grid.tableOfColumn(columns[1]).id());
    }

    @Test
    void aTableWithItsHeaderHiddenAndAFooterShownAnswersNoHeaderAnywhere() {
        Table<Chore> table = choresTable();
        table.setShowHeader(false);
        bind(table);
        AxGrid grid = new AxGrid(bridge);
        AccessibleTree tree = bridge.tree();
        AccessibleNode tableNode = first(Accessible.Role.TABLE);
        boolean footer = false;
        for (AccessibleNode child : tree.children(tableNode)) {
            if (child.role() == Accessible.Role.GROUP) footer = true;
        }
        assertTrue(footer, "the footer group is published, and is the table's only group");
        assertEquals(0, grid.header(tableNode), "MACOS-NEW-9: a footer is never the header");
        assertFalse(AxGate.allows(grid, tableNode, "accessibilityHeader"),
                "so AXHeader is refused, as a native headerless table answers none");
        assertNull(grid.columnHeaderElements(tableNode));
        for (AccessibleNode row : tree.children(tableNode)) {
            if (row.role() != Accessible.Role.ROW) continue;
            for (AccessibleNode cell : tree.children(row)) {
                assertNull(grid.columnHeaderElements(cell),
                        "no data cell is told the footer's text as its column header");
                assertFalse(AxGate.allows(grid, cell, "accessibilityColumnHeaderUIElements"));
            }
        }
        assertNotEquals(0, grid.cellAt(tableNode, 0, 3), "and the cells are still found");
    }

    @Test
    void aCalendarsDayIsFoundAtItsColumnAndWeekAndEachWeekIsNumberedByItsDays() {
        CalendarView calendar = new CalendarView();
        calendar.setVisibleMonth(LocalDate.of(2026, 9, 9));
        bind(calendar);
        AxGrid grid = new AxGrid(bridge);
        AccessibleTree tree = bridge.tree();
        AccessibleNode table = first(Accessible.Role.TABLE);
        int weeks = 0;
        for (AccessibleNode week : tree.children(table)) {
            if (week.role() != Accessible.Role.ROW) continue;
            assertNull(week.selectionItem(), "a week carries no selection item, which is the case");
            List<AccessibleNode> days = tree.children(week);
            AccessibleNode day = days.get(days.size() - 1);
            int w = day.cell().row();
            assertEquals(w, grid.index(week), "MACOS-NEW-4: the week is numbered by its days");
            assertEquals(bridge.elementFor(day.id()), grid.cellAt(table, day.cell().column(), w),
                    "MACOS-NEW-4: a day is found at its column and week");
            long[] header = grid.columnHeaderElements(day);
            assertTrue(header != null && header.length == 1, "a day is told its weekday");
            assertEquals(Accessible.Role.COLUMN_HEADER, bridge.nodeFor(header[0]).role());
            assertEquals(day.cell().column(), bridge.nodeFor(header[0]).cell().column());
            weeks++;
        }
        assertEquals(6, weeks, "six weeks in the day view");
        assertEquals(7, grid.columns(table).length, "a column element per weekday");
        assertEquals(6, grid.columnCells(table, 3, false).length, "each holding its six days");
    }
}

package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A table's column elements (M4; decision 34): one per shown column, answering what a native
 * NSTableView's {@code AXColumn} answered on the macOS 26.6.2 guest (2026-09-15,
 * {@code scripts/a11y/macos/table-probe.swift}), and living by a node element's rules though they stand
 * for no node.
 */
@ExtendWith(PlatformFreeBridges.class)
class AxColumnsTest {

    /**
     * WINDOW > TABLE 1001 at (10, 20, 300, 200) with {@code columns} columns > [GROUP 1002 (header,
     * when {@code header}) > COLUMN_HEADER 1003+c at (10 + 100c, 20, 100, 30); ROW 1010 > CELL 1011+c
     * (0, c); ROW 1020 (not showing) > CELL 1021+c (1, c)].
     */
    private static AccessibleTree aTable(int columns, boolean header) {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        int table = open(a, 1001, 0, Accessible.Role.TABLE, 10, 20, 300, 200, true);
        a.table(2, columns);
        a.selection(false, false);
        if (header) {
            int group = open(a, 1002, table, Accessible.Role.GROUP, 10, 20, 300, 30, true);
            for (int c = 0; c < columns; c++) {
                open(a, 1003 + c, group, Accessible.Role.COLUMN_HEADER, 10 + 100 * c, 20, 100, 30, true);
                a.cell(-1, c);
                a.end();
            }
            a.end();
        }
        for (int r = 0; r < 2; r++) {
            long id = 1010 + 10L * r;
            int row = open(a, id, table, Accessible.Role.ROW, 10, 50 + 30 * r, 300, 30, r == 0);
            a.selectionItem(false, r + 1, 2);
            for (int c = 0; c < columns; c++) {
                open(a, id + 1 + c, row, Accessible.Role.CELL, 12 + 100 * c, 50 + 30 * r, 96, 30, r == 0);
                a.cell(r, c);
                a.end();
            }
            a.end();
        }
        a.end();
        open(a, 1050, 0, Accessible.Role.BUTTON, 0, 0, 40, 20, true);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    private static int open(Accessibility a, long id, int parent, Accessible.Role role,
                            float x, float y, float w, float h, boolean showing) {
        int index = a.begin(id, parent, Locale.ENGLISH, x, y, w, h);
        a.role(role);
        a.name(I18nString.literal(role + " " + id), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, showing, false, false);
        return index;
    }

    @Test
    void aTableVendsOneColumnPerShownColumnAnsweringItsIndexCellsAndHeader() {
        // M4, restated 2026-09-15 from AxGridTest.theCountsAreTheTableFacetsAndTheColumnsAreNoneToday,
        // which pinned an empty AXColumns beside an AXColumnCount of 3.
        AxBridge bridge = PlatformFreeBridges.make();
        AccessibleTree tree = aTable(3, true);
        bridge.publish(tree, false);
        AxGrid grid = new AxGrid(bridge);
        AccessibleNode table = tree.find(1001);
        long[] columns = grid.columns(table);
        assertEquals(3, columns.length, "one per shown column, as the column count says");
        assertEquals(3, grid.columnCount(table), "and the count and the columns agree");
        assertArrayEquals(columns, grid.columns(table), "the same objects on every ask (§1.3)");
        for (int c = 0; c < 3; c++) {
            assertEquals(c, grid.columnOf(columns[c]), "AXIndex, zero-based as the native column's");
            assertEquals(1001, grid.tableOfColumn(columns[c]).id(), "its parent is the table");
            assertEquals(bridge.elementFor(1003 + c), grid.columnHeader(table, c),
                    "AXHeader is the column's header cell, as the native column's is its header button");
            assertArrayEquals(new long[] {bridge.elementFor(1011 + c), bridge.elementFor(1021 + c)},
                    grid.columnCells(table, c, false), "AXRows is the column's cells in row order");
            assertArrayEquals(new long[] {bridge.elementFor(1011 + c)}, grid.columnCells(table, c, true),
                    "AXVisibleRows the showing ones");
        }
        assertArrayEquals(columns, grid.visibleColumns(table));
        assertArrayEquals(new long[0], grid.selectedColumns(table), "a table's selection is its rows");
        assertNull(grid.columns(tree.find(1050)), "a button has no columns");
        assertEquals(-1, grid.columnOf(bridge.elementFor(1050)), "and a node's element is no column");
        assertNull(grid.tableOfColumn(bridge.elementFor(1050)));
    }

    @Test
    void aColumnOfAHeaderlessTableHasNoHeaderAndIsOfferedNone() {
        AxBridge bridge = PlatformFreeBridges.make();
        AccessibleTree tree = aTable(2, false);
        bridge.publish(tree, false);
        AxGrid grid = new AxGrid(bridge);
        AccessibleNode table = tree.find(1001);
        long column = grid.columns(table)[1];
        assertEquals(0, grid.columnHeader(table, 1));
        assertFalse(AxGate.allowsOnColumn(grid, table, 1, "accessibilityHeader"),
                "a native headerless table's column answers no AXHeader");
        assertTrue(AxGate.allowsOnColumn(grid, table, 1, "accessibilityRows"));
        assertFalse(AxGate.allowsOnColumn(grid, table, 1, "setAccessibilityIndex:"),
                "a stored setter is settable to a client and read by nothing");
        assertArrayEquals(grid.columns(table), grid.visibleColumns(table),
                "with no header cell, a column is visible where one of its cells shows");
        assertEquals(column, grid.columns(table)[1]);
        assertTrue(AxGate.allows(grid, table, "accessibilityColumns"));
        assertFalse(AxGate.allows(grid, tree.find(1010), "accessibilityColumns"), "a row has none");
        assertFalse(AxGate.allows(grid, tree.find(1050), "accessibilityVisibleColumns"));
    }

    @Test
    void aColumnsBoxIsItsHeaderCellsSpanOverTheTablesHeightInTheTablesSpace() {
        AxBridge bridge = PlatformFreeBridges.make();
        AccessibleTree tree = aTable(3, true);
        bridge.publish(tree, false);
        long column = new AxGrid(bridge).columns(tree.find(1001))[2];
        // Header cell 2 at x 210, width 100, in a table at (10, 20, 300, 200): x 200 in the table,
        // over the whole 200 of its height.
        assertArrayEquals(new double[] {200, 0, 100, 200}, bridge.lastColumnFrameOf(column));
        AccessibleTree headerless = aTable(3, false);
        AxBridge other = PlatformFreeBridges.make();
        other.publish(headerless, false);
        long bare = new AxGrid(other).columns(headerless.find(1001))[1];
        assertArrayEquals(new double[] {102, 0, 96, 200}, other.lastColumnFrameOf(bare),
                "with no header cell, its first cell's span");
    }

    @Test
    void aColumnGoesWhenItsTableLeavesOrStopsShowingItAndNeverFromAReentrantPublish() {
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(aTable(3, true), false);
        AxGrid grid = new AxGrid(bridge);
        long[] columns = grid.columns(bridge.tree().find(1001));
        assertEquals(3, bridge.columnCount());

        bridge.publish(aTable(2, true), true);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.NODE_DESTROYED, 1005));
        assertEquals(3, bridge.columnCount(), "a reentrant publish releases nothing (§3.2)");
        assertNull(grid.tableOfColumn(columns[2]),
                "though a held column the table no longer shows already answers nothing");
        bridge.frameEnded();
        assertEquals(2, bridge.columnCount(), "the frame's end releases the column the table stopped showing");
        assertEquals(columns[0], grid.columns(bridge.tree().find(1001))[0], "and keeps the others");

        bridge.publish(AccessibleTree.EMPTY, false);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.NODE_DESTROYED, 1001));
        bridge.frameEnded();
        assertEquals(0, bridge.columnCount(), "and every column of a table that left the tree");
    }

    @Test
    void aDetachDemotesTheColumnsWithTheElementsBeforeTheClosuresGo() {
        AxBridge bridge = PlatformFreeBridges.make();
        AccessibleTree tree = aTable(2, true);
        bridge.publish(tree, false);
        new AxGrid(bridge).columns(tree.find(1001));
        bridge.detach();
        List<String> teardown = bridge.teardown();
        assertEquals(2, teardown.stream().filter("column demoted"::equals).count(), String.valueOf(teardown));
        assertTrue(teardown.lastIndexOf("column demoted") < teardown.indexOf("closures freed"),
                "a client holding a column lands on NSAccessibilityElement's answers, never a freed closure: "
                        + teardown);
        assertEquals(0, bridge.columnCount());
    }
}

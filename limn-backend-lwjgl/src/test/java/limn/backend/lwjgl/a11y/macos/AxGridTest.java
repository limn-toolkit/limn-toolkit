package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.CellFacet;
import limn.backend.lwjgl.a11y.ProbeWindow;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.i18n.I18nString;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the table, row and cell selectors answer, pinned as they stood when the answers were moved out
 * of {@link AxElementClass}'s closures into {@link AxGrid} (2026-09-15, behaviour-preserving).
 *
 * <p><b>These began as characterization cases, not a specification.</b> Several of them pinned answers
 * the audit found wrong, and said so by name, so that each fix turned its case red on purpose and
 * restated it: M2's (an outline and a list had no rows), MACOS-NEW-9's (the header was the table's
 * first group child whatever it held), MACOS-NEW-4's (a row was found by its selection position) were
 * restated when those fixes landed, 2026-09-15, and M4's (a table had no columns while its column count
 * said otherwise) moved to {@code AxColumnsTest} when columns were vended the same day.
 */
@ExtendWith(PlatformFreeBridges.class)
class AxGridTest {

    /** The platform-free bridge over one tree, and the grid reading through it. */
    private record Fixture(AxBridge bridge, AccessibleTree tree, AxGrid grid) {

        AccessibleNode node(long id) {
            AccessibleNode node = tree.find(id);
            assertTrue(node != null, "no node " + id);
            return node;
        }

        /** The element the bridge vends for a node: what AppKit would hold for it. */
        long element(long id) {
            AccessibleNode node = node(id);
            AccessibleNode parent = tree.node(node.parent());
            return bridge.childElementsOf(parent)[tree.indexInParent(node)];
        }

        long[] elements(long... ids) {
            long[] answer = new long[ids.length];
            for (int i = 0; i < ids.length; i++) answer[i] = element(ids[i]);
            return answer;
        }
    }

    private static Fixture over(AccessibleTree tree) {
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        return new Fixture(bridge, tree, new AxGrid(bridge));
    }

    /** A small builder, so each case says the shape it needs and nothing else. */
    private static final class Shape {
        final Accessibility a = new Accessibility();

        Shape() {
            a.beginWalk(480, 320, Locale.ENGLISH);
            a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
            a.role(Accessible.Role.WINDOW);
            a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
            a.inherited(true, true, true, false, false);
        }

        int open(long id, int parent, Accessible.Role role, boolean showing) {
            int index = a.begin(id, parent, Locale.ENGLISH, 0, 0, 40, 20);
            a.role(role);
            a.name(I18nString.literal(role + " " + id), Accessible.NameFrom.CONTENT);
            a.inherited(true, true, showing, false, false);
            return index;
        }

        AccessibleTree publish() {
            a.end();
            return a.publish(0, 0, 0, 1f, true);
        }
    }

    /**
     * WINDOW > TABLE 1001 (10 rows, 3 columns) > [GROUP 1002 header > COLUMN_HEADER 1003..1005;
     * ROW 1010 (1 of 10, selected) > CELL 1011..1013; ROW 1020 (2 of 10) > CELL 1021..1023;
     * ROW 1030 (5 of 10, not showing) > CELL 1031..1033; GROUP 1040 footer > CELL 1041 (−2, 0)];
     * and a BUTTON 1050 beside the table.
     */
    private static AccessibleTree aTable() {
        Shape s = new Shape();
        Accessibility a = s.a;
        int table = s.open(1001, 0, Accessible.Role.TABLE, true);
        a.table(10, 3);
        a.selection(false, false);
        int header = s.open(1002, table, Accessible.Role.GROUP, true);
        for (int c = 0; c < 3; c++) {
            s.open(1003 + c, header, Accessible.Role.COLUMN_HEADER, true);
            a.cell(-1, c);
            a.end();
        }
        a.end();
        int[][] rows = {{1010, 1, 0}, {1020, 2, 1}, {1030, 5, 4}};
        for (int[] r : rows) {
            boolean showing = r[1] != 5;
            int row = s.open(r[0], table, Accessible.Role.ROW, showing);
            a.selectionItem(r[1] == 1, r[1], 10);
            for (int c = 0; c < 3; c++) {
                s.open(r[0] + 1 + c, row, Accessible.Role.CELL, showing);
                a.cell(r[2], c);
                a.end();
            }
            a.end();
        }
        int footer = s.open(1040, table, Accessible.Role.GROUP, true);
        s.open(1041, footer, Accessible.Role.CELL, true);
        a.cell(-2, 0);
        a.end();
        a.end();
        a.end();
        s.open(1050, 0, Accessible.Role.BUTTON, true);
        a.end();
        return s.publish();
    }

    @Test
    void aTablesRowsAreItsRowChildrenInOrderAndTheVisibleAndSelectedAmongThem() {
        Fixture f = over(aTable());
        AccessibleNode table = f.node(1001);
        assertArrayEquals(f.elements(1010, 1020, 1030), f.grid().rows(table),
                "the header and footer groups are not rows");
        assertArrayEquals(f.elements(1010, 1020), f.grid().visibleRows(table));
        assertArrayEquals(f.elements(1010), f.grid().selectedRows(table));
    }

    @Test
    void aNodeWithNoTableFacetAnswersNothingAndZero() {
        Fixture f = over(aTable());
        for (long id : new long[] {1050, 1010, 1011, 1002}) {
            AccessibleNode node = f.node(id);
            assertNull(f.grid().rows(node), id + " rows");
            assertNull(f.grid().visibleRows(node), id + " visible rows");
            assertNull(f.grid().selectedRows(node), id + " selected rows");
            assertNull(f.grid().columns(node), id + " columns");
            assertEquals(0, f.grid().header(node), id + " header");
            assertEquals(0, f.grid().rowCount(node), id + " row count");
            assertEquals(0, f.grid().columnCount(node), id + " column count");
        }
        assertNull(f.grid().columnHeaderElements(f.node(1050)));
        assertEquals(0, f.grid().cellAt(f.node(1050), 0, 0));
    }

    @Test
    void theCountsAreTheTableFacets() {
        // The columns this case pinned as none beside a count of 3 (M4) are vended since 2026-09-15:
        // AxColumnsTest.aTableVendsOneColumnPerShownColumnAnsweringItsIndexCellsAndHeader restates it.
        Fixture f = over(aTable());
        AccessibleNode table = f.node(1001);
        assertEquals(10, f.grid().rowCount(table), "the model's count, not the realized rows'");
        assertEquals(3, f.grid().columnCount(table));
    }

    @Test
    void theHeaderIsTheGroupHoldingTheHeaderCellsAndItsHeaderCellsAreTheColumnHeaders() {
        Fixture f = over(aTable());
        AccessibleNode table = f.node(1001);
        assertEquals(f.element(1002), f.grid().header(table));
        assertArrayEquals(f.elements(1003, 1004, 1005), f.grid().columnHeaderElements(table));
    }

    @Test
    void aDataCellsColumnHeaderIsTheHeaderGroupsChildAtItsColumnAndOtherRowsHaveNone() {
        // Restated 2026-09-15 (semantics 3, settled after phase 3): a footer cell is answered its
        // column's header, where this bridge answered it for data cells alone. A footer cell is in a
        // column — it is the summary that column pins under its rows — and a reader that asks which
        // column it is in was told nothing. A header cell still answers none: it would answer itself.
        Fixture f = over(aTable());
        assertArrayEquals(f.elements(1004), f.grid().columnHeaderElements(f.node(1022)));
        assertArrayEquals(f.elements(1003), f.grid().columnHeaderElements(f.node(1031)),
                "a row that is not showing is still in the grid");
        assertNull(f.grid().columnHeaderElements(f.node(1004)), "a header cell (row -1) has none");
        assertArrayEquals(f.elements(1003), f.grid().columnHeaderElements(f.node(1041)),
                "the footer cell in column 0 is told column 0's header");
        assertNull(f.grid().columnHeaderElements(f.node(1020)), "a row is not a cell");
    }

    /**
     * A sorted column's direction (decision 36), as the {@code NSAccessibilitySortDirection} number
     * AppKit reads: 0 unknown, 1 ascending, 2 descending (the committed dump, lines 138-140). A
     * header cell of an unsorted column answers 0 rather than refusing, which is what a native sort
     * button does — every header of the probe's table listed {@code AXSortDirection} and the two
     * unsorted ones answered {@code AXUnknownSortDirection} (readings/macos-table-probe.txt lines
     * 275-291). The data cell and the footer cell here are given a direction their facet has no
     * business carrying — today's {@code Table} sets one on the header alone — so that the gate and
     * the getter, and not the model's restraint, are what keep a direction off them.
     */
    @Test
    void aSortedColumnsHeaderAnswersItsDirectionAndAnUnsortedOneAnswersUnknown() {
        Shape s = new Shape();
        Accessibility a = s.a;
        int table = s.open(1001, 0, Accessible.Role.TABLE, true);
        a.table(1, 3);
        int header = s.open(1002, table, Accessible.Role.GROUP, true);
        CellFacet.Sort[] directions = {CellFacet.Sort.NONE, CellFacet.Sort.ASCENDING,
                CellFacet.Sort.DESCENDING};
        for (int c = 0; c < 3; c++) {
            s.open(1003 + c, header, Accessible.Role.COLUMN_HEADER, true);
            a.cell(-1, c, directions[c]);
            a.end();
        }
        a.end();
        int row = s.open(1010, table, Accessible.Role.ROW, true);
        s.open(1011, row, Accessible.Role.CELL, true);
        a.cell(0, 0, CellFacet.Sort.ASCENDING);
        a.end();
        a.end();
        int footer = s.open(1020, table, Accessible.Role.GROUP, true);
        s.open(1021, footer, Accessible.Role.CELL, true);
        a.cell(-2, 0, CellFacet.Sort.DESCENDING);
        a.end();
        a.end();
        a.end();
        Fixture f = over(s.publish());

        assertEquals(0, f.grid().sortDirection(f.node(1003)), "an unsorted header: Unknown, not a refusal");
        assertEquals(1, f.grid().sortDirection(f.node(1004)), "NSAccessibilitySortDirectionAscending");
        assertEquals(2, f.grid().sortDirection(f.node(1005)), "NSAccessibilitySortDirectionDescending");
        assertEquals(0, f.grid().sortDirection(f.node(1011)),
                "a data cell is no header, whatever its facet holds");
        assertEquals(0, f.grid().sortDirection(f.node(1021)),
                "a footer cell (row -2) is no header, whatever its facet holds");
        assertEquals(0, f.grid().sortDirection(f.node(1001)), "a table is no header cell");
    }

    /**
     * Semantics 3, settled after phase 3: the table-level header list is the union over every direct
     * group child that carries a header cell, not the first such group alone — the shape a table
     * takes when it splits frozen columns from scrolling ones. Windows' GetColumnHeaders was already
     * the union; this bridge read one group.
     */
    @Test
    void aTablesColumnHeadersAreTheUnionOverEveryGroupThatHoldsOne() {
        Shape s = new Shape();
        Accessibility a = s.a;
        int table = s.open(1001, 0, Accessible.Role.TABLE, true);
        a.table(1, 3);
        int frozen = s.open(1002, table, Accessible.Role.GROUP, true);
        s.open(1003, frozen, Accessible.Role.COLUMN_HEADER, true);
        a.cell(-1, 0);
        a.end();
        a.end();
        int scrolling = s.open(1006, table, Accessible.Role.GROUP, true);
        for (int c : new int[] {1, 2}) {
            s.open(1006 + c, scrolling, Accessible.Role.COLUMN_HEADER, true);
            a.cell(-1, c);
            a.end();
        }
        a.end();
        int row = s.open(1010, table, Accessible.Role.ROW, true);
        for (int c = 0; c < 3; c++) {
            s.open(1011 + c, row, Accessible.Role.CELL, true);
            a.cell(0, c);
            a.end();
        }
        a.end();
        a.end();
        Fixture f = over(s.publish());
        assertArrayEquals(f.elements(1003, 1007, 1008), f.grid().columnHeaderElements(f.node(1001)),
                "every group's header cells, in the order the groups stand in");
        assertEquals(f.element(1002), f.grid().header(f.node(1001)),
                "accessibilityHeader still names one group, the first that holds a header cell");
        assertArrayEquals(f.elements(1008), f.grid().columnHeaderElements(f.node(1013)),
                "and a cell's own header is matched by column across the groups, as it already was");
    }

    /**
     * Semantics 2, settled after phase 3: a row's index is read off a cell whose nearest table is the
     * table the row is a row of. A row that carries a table facet of its own makes its children that
     * nested table's cells, and their row numbers say nothing about where this row stands in the
     * outer one — which is the rule {@code cellAt} already applied and {@code index} did not.
     */
    @Test
    void aRowWhoseCellsBelongToANestedTableHasNoIndexOfItsOwn() {
        Shape s = new Shape();
        Accessibility a = s.a;
        int table = s.open(1001, 0, Accessible.Role.TABLE, true);
        a.table(2, 1);
        int nested = s.open(1010, table, Accessible.Role.ROW, true);
        a.table(9, 1);                     // the row is a table in its own right
        s.open(1011, nested, Accessible.Role.CELL, true);
        a.cell(7, 0);                      // row 7 OF THE NESTED TABLE, not of the outer one
        a.end();
        a.end();
        int plain = s.open(1020, table, Accessible.Role.ROW, true);
        s.open(1021, plain, Accessible.Role.CELL, true);
        a.cell(1, 0);
        a.end();
        a.end();
        a.end();
        Fixture f = over(s.publish());
        assertEquals(AxGrid.NOT_FOUND[0], f.grid().index(f.node(1010)),
                "the nested table's row 7 is not this row's place in the table above it");
        assertEquals(1, f.grid().index(f.node(1020)), "and an ordinary row is numbered as before");
        assertEquals(0, f.grid().cellAt(f.node(1001), 0, 7),
                "cellAt read the same rule already: the outer table has no row 7");
    }

    @Test
    void aRowsIndexIsItsCellsRowAndAnythingElseAnswersMinusOne() {
        // Restated 2026-09-15 (semantics 2): the cells' row, where it was the selection position less
        // one; this fixture's rows carry both, and they agree.
        Fixture f = over(aTable());
        assertEquals(0, f.grid().index(f.node(1010)));
        assertEquals(1, f.grid().index(f.node(1020)));
        assertEquals(4, f.grid().index(f.node(1030)), "an unrealized row above it still counts");
        assertEquals(-1, f.grid().index(f.node(1011)), "a cell");
        assertEquals(-1, f.grid().index(f.node(1002)), "a group");
        assertEquals(-1, f.grid().index(f.node(1001)), "the table");
    }

    @Test
    void aDataCellsRangesAreOneWideAtItsRowAndColumnAndAHeaderOrFooterCellHasNone() {
        // Restated 2026-09-15: a header cell answered its column's range, and a footer cell its own;
        // a native NSTableView's header buttons answer no AXColumnIndexRange (read on the macOS
        // 26.6.2 guest, table-probe.swift), and a footer is in no data row.
        Fixture f = over(aTable());
        assertArrayEquals(new long[] {1, 1}, f.grid().rowIndexRange(f.node(1023)));
        assertArrayEquals(new long[] {2, 1}, f.grid().columnIndexRange(f.node(1023)));
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().rowIndexRange(f.node(1005)),
                "a header cell is in no data row");
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().columnIndexRange(f.node(1005)),
                "and answers no column range, as a native header button answers none");
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().rowIndexRange(f.node(1041)));
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().columnIndexRange(f.node(1041)));
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().rowIndexRange(f.node(1050)));
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().columnIndexRange(f.node(1050)));
        // NSNotFound is NSIntegerMax, which on a 64-bit NSInteger is Long.MAX_VALUE: read from the
        // running Foundation on the macOS 26.6.2 guest on 2026-09-15 (list-probe.swift), and read
        // back through the AX API from an NSAccessibilityElement that answers it for AXIndex.
        assertEquals(Long.MAX_VALUE, AxGrid.NOT_FOUND[0], "NSNotFound");
        assertEquals("0x7fffffffffffffff", "0x" + Long.toHexString(AxGrid.NOT_FOUND[0]),
                "the number an out-of-process client read for such an element's AXIndex");
    }

    @Test
    void aCellIsFoundByItsRowAndColumnInARealizedRow() {
        Fixture f = over(aTable());
        AccessibleNode table = f.node(1001);
        assertEquals(f.element(1023), f.grid().cellAt(table, 2, 1));
        assertEquals(f.element(1031), f.grid().cellAt(table, 0, 4));
        assertEquals(0, f.grid().cellAt(table, 0, 2), "row 3 of 10 was never realized");
        assertEquals(0, f.grid().cellAt(table, 3, 0), "no fourth column");
        assertEquals(0, f.grid().cellAt(f.node(1010), 0, 0), "a row is not a table");
    }

    @Test
    void aTableWithoutAHeaderAnswersNoHeaderEvenWithAFooter() {
        // MACOS-NEW-9, restated 2026-09-15 (semantics 3): this case was
        // withNoHeaderTheFooterGroupIsAnsweredAsTheHeaderToday, which pinned the footer group as the
        // header and the footer's only cell (column 1's) as column 0's header. TABLE > ROW > CELL
        // (0,0), CELL (0,1), then a footer GROUP > CELL (-2, 1).
        Shape s = new Shape();
        Accessibility a = s.a;
        int table = s.open(1001, 0, Accessible.Role.TABLE, true);
        a.table(1, 2);
        int row = s.open(1010, table, Accessible.Role.ROW, true);
        a.selectionItem(false, 1, 1);
        for (int c = 0; c < 2; c++) {
            s.open(1011 + c, row, Accessible.Role.CELL, true);
            a.cell(0, c);
            a.end();
        }
        a.end();
        int footer = s.open(1040, table, Accessible.Role.GROUP, true);
        s.open(1041, footer, Accessible.Role.CELL, true);
        a.cell(-2, 1);
        a.end();
        a.end();
        a.end();
        Fixture f = over(s.publish());
        assertEquals(0, f.grid().header(f.node(1001)), "a footer is not a header");
        assertTrue(!f.grid().hasHeader(f.node(1001)));
        assertNull(f.grid().columnHeaderElements(f.node(1001)));
        assertNull(f.grid().columnHeaderElements(f.node(1011)));
        assertNull(f.grid().columnHeaderElements(f.node(1012)),
                "the footer's cell in column 1 is never column 1's header");
    }

    @Test
    void aHeaderCellIsMatchedByItsColumnAndNeverByItsPlace() {
        // A header group whose cells are for columns 0 and 2 only: column 2's data cell is told the
        // second header cell, and column 1's has none, where a match by place told column 1 the
        // header of column 2 and column 2 nothing.
        Shape s = new Shape();
        Accessibility a = s.a;
        int table = s.open(1001, 0, Accessible.Role.TABLE, true);
        a.table(1, 3);
        int header = s.open(1002, table, Accessible.Role.GROUP, true);
        for (int c : new int[] {0, 2}) {
            s.open(1003 + c, header, Accessible.Role.COLUMN_HEADER, true);
            a.cell(-1, c);
            a.end();
        }
        a.end();
        int row = s.open(1010, table, Accessible.Role.ROW, true);
        for (int c = 0; c < 3; c++) {
            s.open(1011 + c, row, Accessible.Role.CELL, true);
            a.cell(0, c);
            a.end();
        }
        a.end();
        a.end();
        Fixture f = over(s.publish());
        assertArrayEquals(f.elements(1005), f.grid().columnHeaderElements(f.node(1013)));
        assertNull(f.grid().columnHeaderElements(f.node(1012)), "column 1 has no header cell");
        assertArrayEquals(f.elements(1003), f.grid().columnHeaderElements(f.node(1011)));
    }

    @Test
    void aCalendarWeekIsFoundAndNumberedByItsCellsThoughItCarriesNoSelectionItem() {
        // MACOS-NEW-4, restated 2026-09-15 (semantics 2): this case was
        // aRowWithNoSelectionItemHasNoIndexAndItsCellsCannotBeFoundToday, which pinned an index of -1
        // and no cell. A calendar week is a ROW with no selection item, and its cells carry their own
        // CellFacet.
        Shape s = new Shape();
        Accessibility a = s.a;
        int table = s.open(1001, 0, Accessible.Role.TABLE, true);
        a.table(6, 7);
        int row = s.open(1010, table, Accessible.Role.ROW, true);
        s.open(1013, row, Accessible.Role.CELL, true);
        a.cell(2, 3);
        a.end();
        a.end();
        a.end();
        Fixture f = over(s.publish());
        assertEquals(2, f.grid().index(f.node(1010)), "the week's cells say which row it is");
        assertEquals(f.element(1013), f.grid().cellAt(f.node(1001), 3, 2));
        assertArrayEquals(new long[] {2, 1}, f.grid().rowIndexRange(f.node(1013)));
    }

    @Test
    void aWidgetCellUnderItsRowIsFoundAndToldItsColumnsHeaderAndANestedTablesCellsAreNot() {
        // MACOS-NEW-10 (decision 3): a widget cell hangs under its synthetic ROW keeping its own role,
        // so it is found by its facet among the row's children; and a table inside a widget cell is
        // another table, whose cells are never this one's (the nearest table ancestor, semantics 2).
        Shape s = new Shape();
        Accessibility a = s.a;
        int table = s.open(1001, 0, Accessible.Role.TABLE, true);
        a.table(1, 2);
        int header = s.open(1002, table, Accessible.Role.GROUP, true);
        for (int c = 0; c < 2; c++) {
            s.open(1003 + c, header, Accessible.Role.COLUMN_HEADER, true);
            a.cell(-1, c);
            a.end();
        }
        a.end();
        int row = s.open(1010, table, Accessible.Role.ROW, true);
        s.open(1011, row, Accessible.Role.CELL, true);
        a.cell(0, 0);
        a.end();
        s.open(1012, row, Accessible.Role.CHECK_BOX, true);
        a.cell(0, 1);
        a.end();
        a.end();
        // A row that is a table of its own: its cells' nearest table is the row, not this table.
        int grid = s.open(1030, table, Accessible.Role.ROW, true);
        a.table(1, 1);
        s.open(1031, grid, Accessible.Role.CELL, true);
        a.cell(7, 0);
        a.end();
        a.end();
        int other = s.open(1020, 0, Accessible.Role.TABLE, true);
        a.table(1, 1);
        int otherRow = s.open(1021, other, Accessible.Role.ROW, true);
        s.open(1022, otherRow, Accessible.Role.CELL, true);
        a.cell(0, 0);
        a.end();
        a.end();
        a.end();
        Fixture f = over(s.publish());
        assertEquals(f.element(1012), f.grid().cellAt(f.node(1001), 1, 0), "the check box in column 1");
        assertArrayEquals(f.elements(1004), f.grid().columnHeaderElements(f.node(1012)));
        assertEquals(0, f.grid().index(f.node(1010)));
        assertEquals(f.element(1022), f.grid().cellAt(f.node(1020), 0, 0));
        assertEquals(f.element(1011), f.grid().cellAt(f.node(1001), 0, 0),
                "and each table finds only its own cell at (0, 0)");
        assertNull(f.grid().columnHeaderElements(f.node(1022)), "the other table has no header");
        assertEquals(0, f.grid().cellAt(f.node(1001), 0, 7),
                "a cell whose nearest table ancestor is not this table is not this table's (semantics 2)");
    }

    @Test
    void aTableRowWithNoDataCellHasNoNumber() {
        Shape s = new Shape();
        Accessibility a = s.a;
        int table = s.open(1001, 0, Accessible.Role.TABLE, true);
        a.table(3, 0);
        s.open(1010, table, Accessible.Role.ROW, true);
        a.selectionItem(false, 2, 3);
        a.end();
        a.end();
        Fixture f = over(s.publish());
        assertEquals(AxGrid.NOT_FOUND[0], f.grid().index(f.node(1010)),
                "NSNotFound, and never the selection position a calendar week would not have");
    }

    /**
     * WINDOW > TREE 1001 (selection) > [TREE_ITEM 1010 (row 4 of 9, level 1, selected), TREE_ITEM
     * 1011 (row 5, level 2, not showing), TREE_ITEM 1012 (row unknown), SCROLL_BAR 1013].
     */
    private static AccessibleTree anOutline() {
        Shape s = new Shape();
        Accessibility a = s.a;
        int tree = s.open(1001, 0, Accessible.Role.TREE, true);
        a.selection(false, false);
        int[][] rows = {{1010, 4, 1}, {1011, 5, 2}, {1012, 0, 0}};
        for (int[] r : rows) {
            s.open(r[0], tree, Accessible.Role.TREE_ITEM, r[0] != 1011);
            a.selectionItem(r[0] == 1010, 1, 1);
            if (r[1] != 0) a.hierarchy(r[2], r[1], 9);
            a.end();
        }
        s.open(1013, tree, Accessible.Role.SCROLL_BAR, true);
        a.end();
        a.end();
        return s.publish();
    }

    @Test
    void anOutlinesRowsAreItsItemsAndEachIndexIsItsFlatRowLessOne() {
        // M2, restated 2026-09-15: this case was anOutlineHasNoRowsAndItsItemsNoIndexToday, which
        // pinned null rows, a row count of zero and an index of -1 on every item.
        Fixture f = over(anOutline());
        AccessibleNode tree = f.node(1001);
        assertTrue(f.grid().isRowContainer(tree));
        assertArrayEquals(f.elements(1010, 1011, 1012), f.grid().rows(tree), "the bar is not a row");
        assertArrayEquals(f.elements(1010, 1012), f.grid().visibleRows(tree));
        assertArrayEquals(f.elements(1010), f.grid().selectedRows(tree));
        assertEquals(3, f.grid().index(f.node(1010)),
                "the flat row among every row the outline shows, zero-based as a native outline's, "
                        + "and never the place among siblings the selection item carries");
        assertEquals(4, f.grid().index(f.node(1011)));
        assertEquals(AxGrid.NOT_FOUND[0], f.grid().index(f.node(1012)), "no number is NSNotFound");
        assertEquals(-1, f.grid().index(f.node(1013)), "and the bar is no row at all");
        assertTrue(f.grid().isRow(f.node(1010)));
        assertTrue(!f.grid().isRow(f.node(1013)) && !f.grid().isRow(tree));
    }

    @Test
    void aListsRowsAreItsMembersWhateverRoleACellKeptAndTheIndexIsItsPositionLessOne() {
        // WINDOW > LIST 1001 (selection) > [LIST_ITEM 1010 (3 of 40), BUTTON 1011 (4 of 40,
        // selected: an application cell that kept its role), SCROLL_BAR 1012].
        Shape s = new Shape();
        Accessibility a = s.a;
        int list = s.open(1001, 0, Accessible.Role.LIST, true);
        a.selection(false, false);
        s.open(1010, list, Accessible.Role.LIST_ITEM, true);
        a.selectionItem(false, 3, 40);
        a.end();
        s.open(1011, list, Accessible.Role.BUTTON, true);
        a.selectionItem(true, 4, 40);
        a.end();
        s.open(1012, list, Accessible.Role.SCROLL_BAR, true);
        a.end();
        a.end();
        Fixture f = over(s.publish());
        AccessibleNode node = f.node(1001);
        assertArrayEquals(f.elements(1010, 1011), f.grid().rows(node));
        assertArrayEquals(f.elements(1011), f.grid().selectedRows(node));
        assertEquals(2, f.grid().index(f.node(1010)), "the model's position, not the realized place");
        assertEquals(3, f.grid().index(f.node(1011)));
        assertEquals(0, f.grid().rowCount(node), "a list has no table facet to count from");
    }

    @Test
    void aContainerWithNoSelectionIsNoTableOfRows() {
        Shape s = new Shape();
        Accessibility a = s.a;
        int list = s.open(1001, 0, Accessible.Role.LIST, true);
        s.open(1010, list, Accessible.Role.LIST_ITEM, true);
        a.end();
        a.end();
        Fixture f = over(s.publish());
        assertNull(f.grid().rows(f.node(1001)));
        assertTrue(!f.grid().isRow(f.node(1010)));
    }

    record Person(String name, int age) {
    }

    @Test
    void aRealTablesAnswersAgreeWithItsPublishedShape() {
        AtomicLong nanos = new AtomicLong();
        HeadlessUi ui = new HeadlessUi(nanos::get);
        try {
            List<Person> people = new ArrayList<>();
            for (int i = 0; i < 5; i++) people.add(new Person("Person " + i, 20 + i));
            Table<Person> table = new Table<>(List.of(
                    Column.text("Name", Person::name).width(120),
                    Column.numeric("Age", Person::age).width(60)));
            table.setRows(people);
            Scene scene = new Scene(table, nanos::get);
            ProbeWindow window = new ProbeWindow();
            AxBridge bridge = PlatformFreeBridges.make();
            window.accessibility = bridge;
            scene.bind(window);
            scene.renderFrame(new NoopCanvas(400, 300));
            AccessibleTree tree = bridge.tree();
            AccessibleNode tableNode = null;
            for (int i = 0; i < tree.nodeCount(); i++) {
                if (tree.node(i).role() == Accessible.Role.TABLE) tableNode = tree.node(i);
            }
            assertTrue(tableNode != null, "the table published no TABLE node");
            Fixture f = new Fixture(bridge, tree, new AxGrid(bridge));
            List<AccessibleNode> children = tree.children(tableNode);

            List<Long> rowIds = new ArrayList<>();
            AccessibleNode firstGroup = null;
            for (AccessibleNode child : children) {
                if (child.role() == Accessible.Role.ROW) rowIds.add(child.id());
                if (child.role() == Accessible.Role.GROUP && firstGroup == null) firstGroup = child;
            }
            assertEquals(5, rowIds.size(), "five people, all realized in a 300-high window");
            assertArrayEquals(f.elements(rowIds.stream().mapToLong(Long::longValue).toArray()),
                    f.grid().rows(tableNode));
            assertEquals(5, f.grid().rowCount(tableNode));
            assertEquals(2, f.grid().columnCount(tableNode));
            assertTrue(firstGroup != null, "the table published no header group");
            assertEquals(f.element(firstGroup.id()), f.grid().header(tableNode));

            AccessibleNode secondRow = tree.find(rowIds.get(1));
            assertEquals(1, f.grid().index(secondRow));
            AccessibleNode ageCell = null;
            for (AccessibleNode cell : tree.children(secondRow)) {
                if (cell.cell() != null && cell.cell().column() == 1) ageCell = cell;
            }
            assertTrue(ageCell != null, "the second row published no cell in column 1");
            assertEquals(f.element(ageCell.id()), f.grid().cellAt(tableNode, 1, 1));
            long[] header = f.grid().columnHeaderElements(ageCell);
            assertEquals(1, header.length);
            AccessibleNode headerCell = tree.children(firstGroup).get(1);
            assertEquals(f.element(headerCell.id()), header[0], "the Age header");
            assertNotEquals(0, header[0]);
        } finally {
            ui.close();
        }
    }
}

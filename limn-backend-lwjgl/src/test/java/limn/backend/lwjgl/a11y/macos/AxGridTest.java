package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.lwjgl.a11y.ProbeWindow;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.i18n.I18nString;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

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
 * <p><b>These are characterization cases, not a specification.</b> Several of them pin answers the
 * audit found wrong, and say so by name: the header is the table's first group child whatever it holds
 * (MACOS-NEW-9), a row is found by its selection position (MACOS-NEW-4), an outline and a list have no
 * rows (M2), a table has no columns while its column count says otherwise (M4). The fix for each is
 * meant to turn its case red on purpose and restate it; everything else here is meant to stay green.
 */
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
        AxBridge bridge = AxBridge.withoutThePlatform();
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
    void theCountsAreTheTableFacetsAndTheColumnsAreNoneToday() {
        // M4 pinned: AXColumns is an empty array beside AXColumnCount 3. Decision 34 changes this
        // after a native NSTableView is read on the guest.
        Fixture f = over(aTable());
        AccessibleNode table = f.node(1001);
        assertEquals(10, f.grid().rowCount(table), "the model's count, not the realized rows'");
        assertEquals(3, f.grid().columnCount(table));
        assertArrayEquals(new long[0], f.grid().columns(table));
    }

    @Test
    void theHeaderIsTheTablesFirstGroupChildAndItsChildrenAreTheColumnHeaders() {
        Fixture f = over(aTable());
        AccessibleNode table = f.node(1001);
        assertEquals(f.element(1002), f.grid().header(table));
        assertArrayEquals(f.elements(1003, 1004, 1005), f.grid().columnHeaderElements(table));
    }

    @Test
    void aDataCellsColumnHeaderIsTheHeaderGroupsChildAtItsColumnAndOtherRowsHaveNone() {
        Fixture f = over(aTable());
        assertArrayEquals(f.elements(1004), f.grid().columnHeaderElements(f.node(1022)));
        assertArrayEquals(f.elements(1003), f.grid().columnHeaderElements(f.node(1031)),
                "a row that is not showing is still in the grid");
        assertNull(f.grid().columnHeaderElements(f.node(1004)), "a header cell (row -1) has none");
        assertNull(f.grid().columnHeaderElements(f.node(1041)), "a footer cell (row -2) has none");
        assertNull(f.grid().columnHeaderElements(f.node(1020)), "a row is not a cell");
    }

    @Test
    void aRowsIndexIsItsPositionLessOneAndAnythingElseAnswersMinusOne() {
        Fixture f = over(aTable());
        assertEquals(0, f.grid().index(f.node(1010)));
        assertEquals(1, f.grid().index(f.node(1020)));
        assertEquals(4, f.grid().index(f.node(1030)), "an unrealized row above it still counts");
        assertEquals(-1, f.grid().index(f.node(1011)), "a cell");
        assertEquals(-1, f.grid().index(f.node(1002)), "a group");
        assertEquals(-1, f.grid().index(f.node(1001)), "the table");
    }

    @Test
    void aCellsRangesAreOneWideAtItsRowAndColumn() {
        Fixture f = over(aTable());
        assertArrayEquals(new long[] {1, 1}, f.grid().rowIndexRange(f.node(1023)));
        assertArrayEquals(new long[] {2, 1}, f.grid().columnIndexRange(f.node(1023)));
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().rowIndexRange(f.node(1005)),
                "a header cell is in no data row");
        assertArrayEquals(new long[] {2, 1}, f.grid().columnIndexRange(f.node(1005)),
                "but it is in its column");
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().rowIndexRange(f.node(1041)));
        assertArrayEquals(new long[] {0, 1}, f.grid().columnIndexRange(f.node(1041)));
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().rowIndexRange(f.node(1050)));
        assertArrayEquals(AxGrid.NOT_FOUND, f.grid().columnIndexRange(f.node(1050)));
        assertEquals(Long.MAX_VALUE, AxGrid.NOT_FOUND[0], "NSNotFound");
    }

    @Test
    void aCellIsFoundByTheRealizedRowAtThatPositionAndItsColumn() {
        Fixture f = over(aTable());
        AccessibleNode table = f.node(1001);
        assertEquals(f.element(1023), f.grid().cellAt(table, 2, 1));
        assertEquals(f.element(1031), f.grid().cellAt(table, 0, 4));
        assertEquals(0, f.grid().cellAt(table, 0, 2), "row 3 of 10 was never realized");
        assertEquals(0, f.grid().cellAt(table, 3, 0), "no fourth column");
        assertEquals(0, f.grid().cellAt(f.node(1010), 0, 0), "a row is not a table");
    }

    @Test
    void withNoHeaderTheFooterGroupIsAnsweredAsTheHeaderToday() {
        // MACOS-NEW-9 pinned (the settled header-group rule changes it): TABLE > ROW > CELL (0,0),
        // CELL (0,1), then a footer GROUP > CELL (−2, 1). The footer is the first group child, so
        // it is the header, and the data cell in column 0 is told the footer's only cell as its
        // column header, which is column 1's.
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
        assertEquals(f.element(1040), f.grid().header(f.node(1001)));
        assertArrayEquals(f.elements(1041), f.grid().columnHeaderElements(f.node(1011)));
        assertNull(f.grid().columnHeaderElements(f.node(1012)),
                "column 1 has no header: the footer group has only one child, at position 0");
    }

    @Test
    void aRowWithNoSelectionItemHasNoIndexAndItsCellsCannotBeFoundToday() {
        // MACOS-NEW-4 pinned (semantics 2 changes it): a calendar week is a ROW with no selection
        // item, and its cells carry their own CellFacet.
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
        assertEquals(-1, f.grid().index(f.node(1010)));
        assertEquals(0, f.grid().cellAt(f.node(1001), 3, 2));
        assertArrayEquals(new long[] {2, 1}, f.grid().rowIndexRange(f.node(1013)),
                "while the cell itself says where it is");
    }

    @Test
    void anOutlineHasNoRowsAndItsItemsNoIndexToday() {
        // M2 pinned: a TREE with a selection facet and TREE_ITEM children answers no rows.
        Shape s = new Shape();
        Accessibility a = s.a;
        int tree = s.open(1001, 0, Accessible.Role.TREE, true);
        a.selection(false, false);
        for (int i = 0; i < 3; i++) {
            s.open(1010 + i, tree, Accessible.Role.TREE_ITEM, true);
            a.selectionItem(i == 0, i + 1, 3);
            a.end();
        }
        a.end();
        Fixture f = over(s.publish());
        assertNull(f.grid().rows(f.node(1001)));
        assertEquals(0, f.grid().rowCount(f.node(1001)));
        assertEquals(-1, f.grid().index(f.node(1012)));
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
            AxBridge bridge = AxBridge.withoutThePlatform();
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

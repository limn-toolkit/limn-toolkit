package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.CellFacet;
import limn.accessibility.SelectionItemFacet;
import limn.accessibility.TableFacet;
import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.input.Keys;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a screen reader is told about a table; ADR 041 §7. */
class TableAccessibilityTest extends AccessibleComponentTestBase {

    record Person(String name, int age) {
    }

    private static List<Person> people(int count) {
        List<Person> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Person("Person " + i, 20 + i % 50));
        }
        return list;
    }

    private Table<Person> bindTable(int count) {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        table.setRows(people(count));
        bind(table);
        return table;
    }

    private AccessibleNode tableNode() {
        return node(Accessible.Role.TABLE);
    }

    private List<AccessibleNode> rowNodes() {
        List<AccessibleNode> rows = new ArrayList<>();
        for (AccessibleNode child : childrenOf(tableNode())) {
            if (child.role() == Accessible.Role.ROW) {
                rows.add(child);
            }
        }
        return rows;
    }

    private AccessibleNode headerGroup() {
        AccessibleNode first = childrenOf(tableNode()).get(0);
        assertEquals(Accessible.Role.GROUP, first.role(), "the header group comes first");
        return first;
    }

    @Test
    void aTableIsItsShapeItsHeadersAndTheRowsItRealized() {
        Table<Person> table = bindTable(1000);
        AccessibleNode node = tableNode();
        TableFacet facet = node.table();
        assertNotNull(facet);
        assertEquals(1000, facet.rowCount(), "the model's count, not the tree's");
        assertEquals(2, facet.columnCount());
        assertNotNull(node.selection());
        assertFalse(node.selection().multiSelectable());
        assertNotNull(node.scroll());
        assertTrue(node.scroll().verticallyScrollable());

        List<AccessibleNode> headers = childrenOf(headerGroup());
        assertEquals(2, headers.size());
        assertEquals(Accessible.Role.COLUMN_HEADER, headers.get(0).role());
        assertEquals("Name", headers.get(0).name());
        assertEquals(new CellFacet(-1, 0), headers.get(0).cell());
        assertEquals("Age", headers.get(1).name());
        assertEquals(new CellFacet(-1, 1), headers.get(1).cell());

        List<AccessibleNode> rows = rowNodes();
        assertTrue(rows.size() < 20 && rows.size() > 3, "only realized rows: " + rows.size());
        AccessibleNode second = rows.get(1);
        SelectionItemFacet item = second.selectionItem();
        assertNotNull(item);
        assertEquals(2, item.positionInSet());
        assertEquals(1000, item.sizeOfSet());
        List<AccessibleNode> cells = childrenOf(second);
        assertEquals(2, cells.size());
        assertEquals(Accessible.Role.CELL, cells.get(0).role());
        assertEquals("Person 1", cells.get(0).name());
        assertEquals(new CellFacet(1, 0), cells.get(0).cell());
        assertEquals(Accessible.NameFrom.CONTENT, cells.get(0).nameFrom());
        assertEquals(new CellFacet(1, 1), cells.get(1).cell());
        assertTrue(table.rowCount() == 1000);
    }

    @Test
    void theFocusCellIsTheActiveDescendantAndSelectionMarksTheRow() {
        Table<Person> table = bindTable(30);
        scene.requestFocus(table);
        table.setSelectedRow(3);
        frame();
        AccessibleNode row = rowNodes().get(3);
        assertTrue(row.has(Accessible.State.SELECTED));
        assertTrue(row.selectionItem().selected());
        AccessibleNode cell = childrenOf(row).get(0);
        assertTrue(cell.has(Accessible.State.ACTIVE), "the focus cell is the cursor");
        assertEquals(cell.id(), tableNode().selection().activeDescendant());
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        frame();
        AccessibleNode moved = childrenOf(rowNodes().get(3)).get(1);
        assertTrue(moved.has(Accessible.State.ACTIVE), "Right moves the cursor a column");
        assertEquals(1, nodesWith(Accessible.State.ACTIVE).size());
    }

    @Test
    void aRowKeepsItsIdentityAcrossAScrollAwayAndBack() {
        Table<Person> table = bindTable(500);
        long rowTwo = rowNodes().get(2).id();
        long cellTwo = childrenOf(rowNodes().get(2)).get(0).id();
        table.scrollBy(0, 4000);
        frame();
        assertTrue(rowNodes().get(0).selectionItem().positionInSet() > 50, "scrolled away");
        table.scrollBy(0, -4000);
        frame();
        assertEquals(rowTwo, rowNodes().get(2).id(), "row 2 is row 2 again");
        assertEquals(cellTwo, childrenOf(rowNodes().get(2)).get(0).id());
    }

    @Test
    void aSortRenumbersTheRowsAndKeepsEachRowsIdentity() {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        table.setRows(List.of(new Person("Carol", 3), new Person("Alice", 1),
                new Person("Bob", 2)));
        bind(table);
        long carol = rowNodes().get(0).id();
        assertEquals("Carol", childrenOf(rowNodes().get(0)).get(0).name());
        table.setSort(table.columns().get(0), SortOrder.ASCENDING);
        frame();
        assertEquals("Alice", childrenOf(rowNodes().get(0)).get(0).name());
        assertEquals(1, rowNodes().get(0).selectionItem().positionInSet());
        assertEquals(carol, rowNodes().get(2).id(), "Carol's row is Carol's row, third now");
        assertEquals(3, rowNodes().get(2).selectionItem().positionInSet());
        assertEquals(new CellFacet(2, 0), childrenOf(rowNodes().get(2)).get(0).cell());
    }

    @Test
    void aReaderCanSelectARowAndActivateTheTable() throws InterruptedException {
        Table<Person> table = bindTable(20);
        int[] activated = {-1};
        table.onActivate(index -> activated[0] = index);
        assertTrue(rowNodes().get(4).actions().actions().contains(Accessible.Action.SELECT));
        assertTrue(perform(rowNodes().get(4).id(), Accessible.Action.SELECT, null));
        assertEquals(4, table.selectedRow());
        frame();
        assertTrue(tableNode().actions().actions().contains(Accessible.Action.PRESS),
                "a press is offered once a row is selected");
        assertTrue(perform(tableNode().id(), Accessible.Action.PRESS, null));
        assertEquals(4, activated[0]);
    }

    @Test
    void aQuietTableAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        Table<Person> table = bindTable(200);
        table.setSelectedRow(3);
        frame();
        scene.requestFocus(table);
        frame();
        settleAnimations(table);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            table.invalidate();
            frame();
        }
        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            table.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            table.invalidate();
            frame();
        }, 60);
        bridge.listening = true;
        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        // Equal, and not zero: painting the focus ring builds one RoundRect per frame, as every
        // focused widget in this set does, and that is the paint's cost whether or not a reader is
        // attached. What must be zero is the difference, which is what the describe hook costs.
        assertEquals(cost[1], cost[0],
                "describing a table whose cells did not move costs no memory: every name is a "
                        + "string a slot already holds, handed over with the row's witness");
    }

    @Test
    void aFooterIsAGroupOfCellsBelowTheRows() {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120).footer("Total"),
                Column.numeric("Age", Person::age).width(60).footerSum()));
        table.setRows(people(3));
        bind(table);
        List<AccessibleNode> children = childrenOf(tableNode());
        AccessibleNode last = children.get(children.size() - 1);
        assertEquals(Accessible.Role.GROUP, last.role(), "the footer group comes last");
        List<AccessibleNode> cells = childrenOf(last);
        assertEquals(2, cells.size());
        assertEquals(Accessible.Role.CELL, cells.get(0).role());
        assertEquals("Total", cells.get(0).name());
        assertEquals(new CellFacet(-2, 0), cells.get(0).cell());
        assertEquals("63", cells.get(1).name(), "20 + 21 + 22, formatted as the column's cells");
        assertEquals(new CellFacet(-2, 1), cells.get(1).cell());
        assertTrue(last.y() > rowNodes().get(2).y(), "below the last row");
    }

    /**
     * A widget cell hangs under the {@code ROW} it sits in, in column order, keyed by its column
     * and identified through the row (decision 3 and decision 33 of 2026-09-13; ADR 039 §1.3 and
     * §7.1 amended 2026-09-14). Until then it was a child of the {@code TABLE}, after every row,
     * so every bridge's row-and-column lookup missed it and a reader walking the row skipped
     * its control; and its key packed row and column into one number because the table's node
     * scoped it.
     */
    @Test
    void aWidgetColumnCellIsAChildOfItsRowInColumnOrder() throws InterruptedException {
        List<String> pressed = new ArrayList<>();
        List<String> routedToTable = new ArrayList<>();
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.<Person>widget("Edit", person -> new Button("Edit " + person.name())
                        .onAction(() -> pressed.add(person.name()))).width(80),
                Column.numeric("Age", Person::age).width(60))) {
            @Override
            protected boolean onSyntheticAction(long key, Accessible.Action action,
                                                Accessible.Argument arg) {
                routedToTable.add(key + ":" + action);
                return super.onSyntheticAction(key, action, arg);
            }
        };
        table.setRows(people(40));
        bind(table);

        for (AccessibleNode child : childrenOf(tableNode())) {
            assertTrue(child.role() != Accessible.Role.BUTTON,
                    "no widget cell hangs under the table itself: " + describe(tree()));
        }
        List<AccessibleNode> rows = rowNodes();
        assertTrue(rows.size() > 3);
        AccessibleNode second = rows.get(1);
        List<AccessibleNode> cells = childrenOf(second);
        assertEquals(3, cells.size(), "three columns, three cells: " + describe(tree()));
        assertEquals(Accessible.Role.CELL, cells.get(0).role());
        assertEquals(new CellFacet(1, 0), cells.get(0).cell());
        assertEquals(Accessible.Role.BUTTON, cells.get(1).role(), "the control, in its column");
        assertEquals("Edit Person 1", cells.get(1).name());
        assertEquals(new CellFacet(1, 1), cells.get(1).cell());
        assertEquals(Accessible.Role.CELL, cells.get(2).role());
        assertEquals(new CellFacet(1, 2), cells.get(2).cell());
        assertEquals(second.id(), tree().node(cells.get(1).parent()).id(), "its parent is the row");

        // The button's verbs stay its own: a press from the platform reaches the widget's
        // handler through the scene, and never the table's synthetic hook, although the node
        // hangs under a synthetic row (ADR 039 §1.3, amended 2026-09-14).
        assertTrue(cells.get(1).actions().actions().contains(Accessible.Action.PRESS));
        long button = cells.get(1).id();
        long row = second.id();
        assertTrue(perform(button, Accessible.Action.PRESS, null));
        assertEquals(List.of("Person 1"), pressed, "the button's own handler ran");
        assertTrue(routedToTable.isEmpty(),
                "and the table's synthetic hook was not asked: " + routedToTable);
        table.scrollBy(0, 4000);
        frame();
        assertEquals(AccessibleNode.NONE, tree().indexOf(button), "row 1 scrolled away");
        assertTrue(rowNodes().get(0).selectionItem().positionInSet() > 20);
        table.scrollBy(0, -4000);
        frame();
        assertEquals(row, rowNodes().get(1).id());
        assertEquals(button, childrenOf(rowNodes().get(1)).get(1).id(),
                "the recycled control is row 1's element again");
    }

    /**
     * A wide table publishes a cell for every shown column of every realized row, off-screen
     * columns included, so nine rows over five hundred columns is more interned pairs than the
     * table started out holding; a quiet frame must still publish nothing (MODEL-NEW-7).
     */
    @Test
    void aTableWiderThanTheInternTablePublishesNothingOnAQuietFrame() {
        List<Column<Person>> columns = new ArrayList<>();
        columns.add(Column.text("Name", Person::name).width(120));
        for (int c = 0; c < 520; c++) {
            columns.add(Column.numeric("Age " + c, Person::age).width(60));
        }
        Table<Person> table = new Table<>(columns);
        table.setRows(people(200));
        bind(table);
        List<AccessibleNode> rows = rowNodes();
        assertTrue(rows.size() * 521 > 4096, "more cells than the table held: " + rows.size());
        int published = bridge.published.size();
        long firstCell = childrenOf(rows.get(0)).get(0).id();

        for (int i = 0; i < 3; i++) {
            table.invalidate();
            frame();
        }
        assertEquals(published, bridge.published.size(),
                "nothing moved, so no snapshot: " + bridge.events);
        assertEquals(firstCell, childrenOf(rowNodes().get(0)).get(0).id());
    }

    @Test
    void aMultiSelectTableSaysSo() {
        Table<Person> table = bindTable(5);
        table.setSelectionMode(Table.SelectionMode.MULTI);
        frame();
        assertTrue(tableNode().selection().multiSelectable());
        table.setSelectionMode(Table.SelectionMode.NONE);
        frame();
        assertTrue(rowNodes().get(0).actions() == null
                || !rowNodes().get(0).actions().actions().contains(Accessible.Action.SELECT),
                "nothing to select in NONE");
    }
}

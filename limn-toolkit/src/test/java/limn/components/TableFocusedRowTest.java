package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.input.Keys;
import limn.scene.Widget;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * The row the keyboard is in survives a scroll that moves it out of the viewport, in both the
 * shapes a table has (ADR 039 §13.29; ADR 041 §7; decision 22 of 2026-09-14).
 *
 * <p>A table realizes the rows in its viewport and releases the rest. Two rows are spared. A row
 * whose <em>widget cell</em> holds the keyboard focus, as a list's row is: releasing it would
 * move the focus up to the table and take a reader's cursor with it. And, since decision 22,
 * the <em>focus cell's</em> row while the table itself holds the keyboard: a reader's cursor
 * stands on that cell, and a wheel or a bar drag that recycled it left the reader on nothing
 * until the next arrow key (B8). Both are laid out where the scroll estimate puts them, outside
 * the rows' viewport, and published as the rows they are: not {@code SHOWING}, their cells not
 * {@code SHOWING} either (TABLE-NEW-9), the focus cell still {@code ACTIVE}. The first pass after
 * the focus leaves releases the cursor row; a refresh or a sort re-realizes it wherever its
 * record went.
 *
 * <p>The table also clips its widget cells to the rows' viewport (TABLE-NEW-10), so a switch
 * scrolled under the header is neither {@code isShowing()} nor published {@code SHOWING}, and the
 * accessors that say so cost a quiet frame nothing.
 *
 * <p>Every case drives the public API and the scene's own input on a bound scene and reads back
 * what the scene published. Nothing constructs a node.
 */
class TableFocusedRowTest extends AccessibleComponentTestBase {

    record Person(String name, int age) {
    }

    private static List<Person> people(int count) {
        List<Person> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Person("Person " + i, 20 + i % 50));
        }
        return list;
    }

    private static Table<Person> plain(List<Person> rows) {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        table.setRows(rows);
        return table;
    }

    private static Table<Person> withButtons(List<Person> rows) {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.<Person>widget("Open", p -> new Button(p.name())).width(100)));
        table.setRows(rows);
        return table;
    }

    private AccessibleNode tableNode() {
        return node(Accessible.Role.TABLE);
    }

    /** The published row at {@code position} (one-based, as spoken), or {@code null}. */
    private AccessibleNode rowAt(int position) {
        for (AccessibleNode child : childrenOf(tableNode())) {
            if (child.role() == Accessible.Role.ROW
                    && child.selectionItem().positionInSet() == position) {
                return child;
            }
        }
        return null;
    }

    private static Button buttonOf(Table<Person> table, String name) {
        for (Widget<?> child : table.children()) {
            if (child instanceof Button button && name.equals(button.text())) {
                return button;
            }
        }
        throw new AssertionError("no button " + name + " among " + table.children());
    }

    private void wheelDown(int detents) {
        drive(scene).scrolled(0, -detents, 200, 150);
        drive(scene).inputBatchEnded();
        frame();
    }

    // ------------------------------------------------------------ the focus cell's row (B8)

    @Test
    void theFocusCellsRowSurvivesAWheelScrollWhileTheTableHoldsTheKeyboard() {
        Table<Person> table = plain(people(300));
        bind(table);
        scene.requestFocus(table);
        table.setSelectedRow(2);
        frame();
        long cursor = tree().activeDescendant();
        assertTrue(cursor != 0);
        bridge.events.clear();

        wheelDown(30);

        assertTrue(table.firstVisibleRow() > 6, "scrolled away: " + table.firstVisibleRow());
        AccessibleNode row = rowAt(3);
        assertNotNull(row, "the cursor row is still published: " + describe(tree()));
        assertFalse(row.has(Accessible.State.SHOWING), "but it is not on screen");
        assertTrue(row.has(Accessible.State.SELECTED));
        for (AccessibleNode cell : childrenOf(row)) {
            assertFalse(cell.has(Accessible.State.SHOWING),
                    "nor are its cells (TABLE-NEW-9): " + describe(tree()));
        }
        AccessibleNode cell = childrenOf(row).get(0);
        assertTrue(cell.has(Accessible.State.ACTIVE), "the cursor still stands on its cell");
        assertEquals(cursor, cell.id(), "the same element");
        assertEquals(cursor, tree().activeDescendant());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "the cursor never moved, so nothing said it did: " + bridge.events);
        assertTrue(row.y() + row.height() <= tableNode().y()
                + Theme.current().tokensFor(table).controlHeight() + 0.5f,
                "published above the rows' viewport, where the layout put it: " + row.y());
    }

    @Test
    void theCursorRowIsReleasedByTheFirstPassAfterTheFocusLeaves() {
        Table<Person> table = plain(people(300));
        Button other = new Button("Elsewhere");
        limn.scene.layout.Column root = new limn.scene.layout.Column();
        root.add(new limn.scene.layout.SizedBox(400, 240, table));
        root.add(other);
        bind(root);
        scene.requestFocus(table);
        table.setSelectedRow(2);
        frame();
        wheelDown(30);
        assertNotNull(rowAt(3), "kept while the table holds the keyboard");

        scene.requestFocus(other);
        frame();
        assertNull(rowAt(3), "released once it does not: " + describe(tree()));
        assertTrue(nodesWith(Accessible.State.ACTIVE).isEmpty(), "and no cursor is published");

        scene.requestFocus(table);
        frame();
        AccessibleNode back = rowAt(3);
        assertNotNull(back, "realized again the moment the keyboard returns");
        assertTrue(childrenOf(back).get(0).has(Accessible.State.ACTIVE));
    }

    @Test
    void theCursorRowSurvivesARefreshAndASortWhereverItsRecordWent() {
        List<Person> rows = new ArrayList<>(people(300));
        Table<Person> table = plain(rows);
        bind(table);
        scene.requestFocus(table);
        table.setSelectedRow(2);
        frame();
        wheelDown(30);
        assertNotNull(rowAt(3));

        rows.add(0, new Person("Newcomer", 1));
        table.refresh();
        frame();
        AccessibleNode moved = rowAt(4);
        assertNotNull(moved, "Person 2 is fourth now, still realized: " + describe(tree()));
        assertFalse(moved.has(Accessible.State.SHOWING));
        assertEquals("Person 2", childrenOf(moved).get(0).name());
        assertTrue(childrenOf(moved).get(0).has(Accessible.State.ACTIVE));

        table.setSort(table.columns().get(0), SortOrder.DESCENDING);
        frame();
        frame();
        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), describe(tree()));
        assertEquals("Person 2", active.get(0).name(), "the cursor followed its record");
        assertTrue(active.get(0).has(Accessible.State.SHOWING),
                "and a sort reveals the focus row (decision 40), so it is in view again");
    }

    // ------------------------------------------------------- the row a widget cell focuses (B4)

    @Test
    void aRowWhoseWidgetCellHoldsTheKeyboardSurvivesPagingOutAndItsNodesAgree() {
        Table<Person> table = withButtons(people(300));
        bind(table);
        Button first = buttonOf(table, "Person 0");
        first.requestFocus();
        frame();
        drive(scene).keyEvent(Keys.PAGE_DOWN, true, false, 0);
        drive(scene).inputBatchEnded();
        drive(scene).keyEvent(Keys.PAGE_DOWN, true, false, 0);
        drive(scene).inputBatchEnded();
        frame();

        assertTrue(table.firstVisibleRow() > 3, "paged away: " + table.firstVisibleRow());
        assertSame(first, scene.focusedWidget(), "the keys bubbled; the focus stayed put");
        assertTrue(table.children().contains(first), "the same widget, still mounted");
        assertFalse(first.isShowing(), "outside the rows' viewport (TABLE-NEW-10)");
        AccessibleNode row = rowAt(1);
        assertNotNull(row, "published as the row it is: " + describe(tree()));
        assertFalse(row.has(Accessible.State.SHOWING));
        AccessibleNode button = childrenOf(row).get(1);
        assertEquals(Accessible.Role.BUTTON, button.role());
        assertTrue(button.has(Accessible.State.FOCUSED));
        assertFalse(button.has(Accessible.State.SHOWING), "and nor is its control");
        assertTrue(button.y() >= row.y() && button.y() + button.height() <= row.y() + row.height(),
                "the ROW and its cell agree on where they are: row " + row.y() + ".."
                        + (row.y() + row.height()) + ", button " + button.y());
        assertFalse(childrenOf(row).get(0).has(Accessible.State.SHOWING),
                "the value cell beside it is off screen too (TABLE-NEW-9)");
    }

    @Test
    void aWidgetCellUnderTheHeaderIsNotShowing() {
        Table<Person> table = withButtons(people(50));
        bind(table);
        Button first = buttonOf(table, "Person 0");
        Button second = buttonOf(table, "Person 1");
        float rowHeight = second.y() - first.y();
        assertTrue(rowHeight > 4, "two rows, one below the other: " + rowHeight);
        assertTrue(first.isShowing());

        table.scrollBy(0, rowHeight - 2); // two points of row 0 stay in view, under its padding
        frame();

        assertEquals(0, table.firstVisibleRow(), "row 0 is still the first, just cut");
        assertFalse(first.isShowing(), "its control lies wholly under the header band");
        assertTrue(second.isShowing());
        AccessibleNode row = rowAt(1);
        assertNotNull(row);
        assertTrue(row.has(Accessible.State.SHOWING), "two points of the row are on screen");
        assertFalse(childrenOf(row).get(1).has(Accessible.State.SHOWING),
                "and none of its control is: " + describe(tree()));
        float headerHeight = Theme.current().tokensFor(table).controlHeight();
        float probeY = Math.max(0, first.y() + first.height() - 1);
        assertTrue(probeY < headerHeight && probeY >= first.y(),
                "a point inside the control's box and inside the header band: " + probeY);
        assertSame(table, table.hitTest(first.x() + 2, probeY),
                "a point on the header is the table's, not the control's");
    }

    @Test
    void aQuietTableWithAWidgetColumnAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        Table<Person> table = withButtons(people(200));
        bind(table);
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
        assertEquals(published, bridge.published.size(), "nothing moved, so no snapshot");
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
        assertEquals(published, bridge.published.size());
        assertEquals(cost[1], cost[0],
                "the clip accessors run for every widget cell of every walk and allocate nothing");
    }
}

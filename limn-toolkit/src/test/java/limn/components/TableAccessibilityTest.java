package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.CellFacet;
import limn.accessibility.ScrollFacet;
import limn.accessibility.SelectionItemFacet;
import limn.accessibility.TableFacet;
import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.Change;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/** What a screen reader is told about a table; ADR 041 §7. */
class TableAccessibilityTest extends AccessibleComponentTestBase {

    record Person(String name, int age) {
    }

    private Locale before;

    @BeforeEach
    void pinTheLanguage() {
        // The sorted header's description is shipped in twenty-one languages and the process
        // language is whatever the machine running the build reports: on a pt-BR host this
        // class alone read "Ordenado em ordem crescente" and passed the full check only because
        // earlier classes had left English behind them. Pinned and given back, so neither this
        // class nor the next depends on the order the suite happens to run in.
        before = I18n.processLocale();
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void releaseTheLanguage() {
        I18n.setLocale(before);
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
        assertEquals(tableNode().id(), tree().focused());
        assertEquals(cell.id(), tree().activeDescendant(),
                "the tree's cursor is the first active node below the focused table");
        drive(scene).keyEvent(Keys.RIGHT, true, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        AccessibleNode moved = childrenOf(rowNodes().get(3)).get(1);
        assertTrue(moved.has(Accessible.State.ACTIVE), "Right moves the cursor a column");
        assertEquals(1, nodesWith(Accessible.State.ACTIVE).size());
        assertEquals(moved.id(), tree().activeDescendant());
    }

    /**
     * The settled active-state gate (ADR 039 §1.10, amended 2026-09-14): a table nobody is in
     * publishes no {@code ACTIVE} cell, so the widget the user is actually in is never handed a
     * table's cursor as its own.
     */
    @Test
    void anUnfocusedTableWithASelectedRowPublishesNoCursor() {
        Table<Person> table = bindTable(30);
        table.setSelectedRow(3);
        frame();

        assertTrue(rowNodes().get(3).selectionItem().selected(), describe(tree()));
        assertTrue(nodesWith(Accessible.State.ACTIVE).isEmpty(),
                "the selection stands, the cursor does not: " + describe(tree()));
        assertEquals(0, tree().activeDescendant());
        assertEquals(0, bridge.countOf(
                limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "an unfocused container announces nothing: " + bridge.events);
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

    /**
     * TABLE-NEW-2 (decision 23 of 2026-09-14): the cursor a reader stands on is the focus cell,
     * and a sort that moved Carol's row while the focus cell stayed at view position 0 left the
     * reader on Alice while Carol was selected. The cursor follows its record now.
     */
    @Test
    void aSortKeepsTheCursorOnTheRecordItWasOn() {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        table.setRows(List.of(new Person("Carol", 3), new Person("Alice", 1),
                new Person("Bob", 2)));
        bind(table);
        scene.requestFocus(table);
        table.setSelectedRow(0);
        frame();
        assertEquals("Carol", nodesWith(Accessible.State.ACTIVE).get(0).name());
        long cursor = tree().activeDescendant();
        table.setSort(table.columns().get(0), SortOrder.ASCENDING);
        frame();
        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), describe(tree()));
        assertEquals("Carol", active.get(0).name(), "the cursor is still on Carol's cell");
        assertEquals(new CellFacet(2, 0), active.get(0).cell(), "shown third now");
        assertEquals(cursor, tree().activeDescendant(), "the same element, moved");
        assertTrue(rowNodes().get(2).selectionItem().selected());
    }

    /**
     * Decision 23 of 2026-09-14 seen from the reader's side: after the application inserts a row
     * above and calls {@code refresh()}, the cursor is still on the record it was on, one row
     * further down, and that row is the selected one.
     */
    @Test
    void refreshKeepsTheCursorOnTheRecordItWasOn() {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        List<Person> rows = new ArrayList<>(people(30));
        table.setRows(rows);
        bind(table);
        scene.requestFocus(table);
        table.setSelectedRow(3);
        frame();
        assertEquals("Person 3", nodesWith(Accessible.State.ACTIVE).get(0).name());
        rows.add(0, new Person("Newcomer", 1));
        table.refresh();
        frame();
        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), describe(tree()));
        assertEquals("Person 3", active.get(0).name(), "the cursor followed its record");
        assertEquals(new CellFacet(4, 0), active.get(0).cell(), "one row further down");
        assertTrue(rowNodes().get(4).selectionItem().selected());
        assertFalse(rowNodes().get(3).selectionItem().selected(), "the newcomer is not");
    }

    /** The row node whose first cell reads {@code name}, or {@code null}. */
    private AccessibleNode rowNamed(String name) {
        for (AccessibleNode row : rowNodes()) {
            if (name.equals(childrenOf(row).get(0).name())) {
                return row;
            }
        }
        return null;
    }

    /**
     * Decision 23 of 2026-09-14, the node half (fix round of 2026-09-15): a row's node is its
     * record's, not its index's. Until then a row was keyed by its model index, so after an
     * insert above, "Person 3"'s node id named "Person 2", and a reader's verb sent from the
     * snapshot before the insert acted on whichever record sat at that index when it arrived.
     */
    @Test
    void aRowNodeFollowsItsRecordAndAVerbActsOnTheRecordItWasPublishedFor()
            throws InterruptedException {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        List<Person> rows = new ArrayList<>(people(200));
        table.setRows(rows);
        table.setSelectionMode(Table.SelectionMode.MULTI);
        bind(table);
        AccessibleNode three = rowNamed("Person 3");
        long threeId = three.id();
        long threeCell = childrenOf(three).get(1).id();
        long fiveId = rowNamed("Person 5").id();
        long sixCell = childrenOf(rowNamed("Person 6")).get(1).id();
        java.util.Set<Long> before = new java.util.HashSet<>();
        rowNodes().forEach(row -> before.add(row.id()));

        // The application inserts above and refreshes; the reader's verbs were sent from the
        // snapshot it had, which still names the rows as they were.
        rows.add(0, new Person("Newcomer", 1));
        table.refresh();
        assertTrue(perform(threeId, Accessible.Action.SELECT, null));
        assertEquals(4, table.selectedRow(), "the record the node was published for: Person 3, fifth now");
        assertTrue(perform(sixCell, Accessible.Action.FOCUS, null));
        assertEquals(7, table.focusRow(), "a cell's verb names its record too: Person 6, eighth now");
        assertEquals(1, table.focusColumn());

        table.scrollBy(0, -4000); // the two reveals scrolled; back to the top, where all of them are
        frame();
        assertNotNull(rowNamed("Person 3"), describe(tree()));
        assertEquals(threeId, rowNamed("Person 3").id(), "Person 3's row node kept its id");
        assertEquals(threeCell, childrenOf(rowNamed("Person 3")).get(1).id(), "and so did its cells");
        assertNotNull(rowNamed("Newcomer"));
        assertFalse(before.contains(rowNamed("Newcomer").id()),
                "the newcomer took no identity a published record had: " + describe(tree()));
        assertEquals(rowNodes().size(), rowNodes().stream().map(AccessibleNode::id).distinct().count(),
                "no two rows share a node");
        assertEquals(new CellFacet(4, 0), childrenOf(rowNamed("Person 3")).get(0).cell(),
                "the position is the index's; the identity is the record's");

        // A record that leaves the list takes its identity with it: a verb still addressed to it
        // is refused on the UI thread rather than landing on the row that took its place.
        rows.remove(6); // Person 5
        table.refresh();
        perform(fiveId, Accessible.Action.SELECT, null);
        assertEquals("[4]", java.util.Arrays.toString(table.selectedRows()),
                "nothing else was selected in Person 5's name");

        // And the identity survives a scroll away and back, as an index's did.
        frame();
        long again = rowNamed("Person 3").id();
        assertEquals(threeId, again);
        table.scrollBy(0, 4000);
        frame();
        assertEquals(null, rowNamed("Person 3"), "scrolled away");
        table.scrollBy(0, -4000);
        frame();
        assertEquals(threeId, rowNamed("Person 3").id(), "Person 3's row is Person 3's row again");
    }

    /**
     * Equal records are one identity per occurrence, as the selection tells them apart: the
     * second "Lee" row keeps its node when an unequal row is inserted above both.
     */
    @Test
    void equalRecordsKeepTheirNodesByOccurrence() {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        List<Person> rows = new ArrayList<>(List.of(new Person("Lee", 1), new Person("Ann", 2),
                new Person("Lee", 1)));
        table.setRows(rows);
        bind(table);
        long first = rowNodes().get(0).id();
        long second = rowNodes().get(2).id();
        rows.add(1, new Person("Bo", 3));
        table.refresh();
        frame();
        assertEquals(first, rowNodes().get(0).id(), "the first Lee is still first");
        assertEquals(second, rowNodes().get(3).id(), "the second Lee, fourth now, kept its node");
    }

    /** A record whose every instance hashes alike, so equal and unequal ones share a chain. */
    record Alike(String name) {
        @Override
        public int hashCode() {
            return 7;
        }
    }

    /**
     * The same, when unequal records share a hash: an occurrence counts the equal records before
     * a row, and a row between two equal ones that merely hashes like them is not one of them.
     */
    @Test
    void equalRecordsAreToldApartFromUnequalOnesThatHashAlike() {
        Table<Alike> table = new Table<>(List.of(Column.text("Name", Alike::name).width(160)));
        List<Alike> rows = new ArrayList<>(List.of(new Alike("Lee"), new Alike("Ann"),
                new Alike("Ann"), new Alike("Lee")));
        table.setRows(rows);
        bind(table);
        long first = rowNodes().get(0).id();
        long second = rowNodes().get(3).id();
        rows.add(1, new Alike("Bo"));
        table.refresh();
        frame();
        assertEquals(first, rowNodes().get(0).id(), "the first Lee is still first");
        assertEquals(second, rowNodes().get(4).id(), "the second Lee, fifth now, kept its node");
    }

    /**
     * The node half of decision 23 across two refreshes with no frame between them (review of
     * the fix round, 2026-09-15): the first refresh followed the published rows and forgot them,
     * so the second followed nothing and left "Person 3"'s identity on the row the first had
     * moved it to, which after a second insert above was "Person 2"'s.
     */
    @Test
    void aRowNodeFollowsItsRecordAcrossTwoRefreshesBeforeAFrame() throws InterruptedException {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        List<Person> rows = new ArrayList<>(people(200));
        table.setRows(rows);
        table.setSelectionMode(Table.SelectionMode.MULTI);
        bind(table);
        long threeId = rowNamed("Person 3").id();
        long fiveId = rowNamed("Person 5").id();

        rows.add(0, new Person("A", 1));
        rows.remove(6); // Person 5
        table.refresh();
        rows.add(0, new Person("B", 2));
        table.refresh();

        perform(fiveId, Accessible.Action.SELECT, null);
        assertEquals("[]", java.util.Arrays.toString(table.selectedRows()),
                "Person 5 left the list, and nothing is selected in its name");
        assertTrue(perform(threeId, Accessible.Action.SELECT, null));
        assertEquals(5, table.selectedRow(), "Person 3 is sixth after two inserts");
        frame();
        assertEquals(threeId, rowNamed("Person 3").id(), "and its row node kept its id");
    }

    /**
     * An identity once published names its record or nothing, never another record (review of
     * the fix round, 2026-09-15; semantics 8): a refresh that left the rows in view where they
     * were followed nothing else, so a record a reader had been shown before scrolling away,
     * moved by that refresh, left its identity on the row that took its place. A bridge that
     * caches an element by identity would have read one element as two records. Three ways to
     * be shown a row and then lose sight of it: scrolled away from its first identity, scrolled
     * away from one a refresh carried, and a carried one the next frame did not show at all.
     */
    @Test
    void anIdentityOncePublishedNeverNamesAnotherRecord() throws InterruptedException {
        for (String way : List.of("scrolled away", "carried, then scrolled away",
                "carried, never shown again")) {
            Table<Person> table = new Table<>(List.of(
                    Column.text("Name", Person::name).width(120),
                    Column.numeric("Age", Person::age).width(60)));
            List<Person> rows = new ArrayList<>(people(200));
            table.setRows(rows);
            bind(table);
            String name;
            long id;
            if (way.equals("carried, never shown again")) {
                // The last row in view; an insert above pushes it out of the next frame's view.
                List<AccessibleNode> shown = rowNodes();
                name = childrenOf(shown.get(shown.size() - 1)).get(0).name();
                id = shown.get(shown.size() - 1).id();
                rows.add(0, new Person("Newcomer", 1));
                table.refresh();
                frame();
                assertEquals(null, rowNamed(name), "pushed out of view");
                int at = 0;
                while (!rows.get(at).name().equals(name)) {
                    at++;
                }
                java.util.Collections.swap(rows, at, 30); // out of view, it moves
                table.refresh();
                frame();
                table.scrollBy(0, 96);
                frame();
            } else {
                if (way.startsWith("carried")) {
                    rows.add(0, new Person("Newcomer", 1));
                    table.refresh();
                    frame();
                }
                name = "Person 3";
                id = rowNamed(name).id();
                table.scrollBy(0, 100_000);
                frame();
                assertEquals(null, rowNamed(name), "scrolled away");
                java.util.Collections.reverse(rows.subList(1, 9)); // out of view, it moves
                table.refresh();
                frame();
                table.scrollBy(0, -100_000);
                frame();
            }

            String context = way + ": " + describe(tree());
            for (AccessibleNode row : rowNodes()) {
                if (row.id() == id) {
                    assertEquals(name, childrenOf(row).get(0).name(), context);
                }
            }
            perform(id, Accessible.Action.SELECT, null);
            int selected = table.selectedRow();
            assertTrue(selected < 0 || rows.get(selected).name().equals(name),
                    "a verb on " + name + "'s old node selected " + (selected < 0 ? "nothing"
                            : rows.get(selected).name()) + "; " + context);
        }
    }

    private static int keyHashes;

    /** A record that counts how often its hash is read, which is what finding its occurrence costs. */
    record Tally(String name) {
        @Override
        public int hashCode() {
            keyHashes++;
            return name.hashCode();
        }
    }

    /**
     * Placing a row among equal records without a {@code rowKey} reads the rows before it; a
     * reader scrolling down read them all again for every frame that realized a row (review of
     * the fix round, 2026-09-15), so a walk to the bottom of a long table cost the square of its
     * length. The rows are read once per list now, as far down as the reader has been shown.
     */
    @Test
    void aReaderScrollingToTheBottomReadsEachRowOnceForItsOccurrence() {
        int count = 5000;
        List<Tally> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Tally("Row " + i));
        }
        Table<Tally> table = new Table<>(List.of(Column.text("Name", Tally::name).width(160)));
        table.setRows(rows);
        bind(table);
        keyHashes = 0;
        int frames = 0;
        while (rowNodes().stream().noneMatch(row -> row.selectionItem().positionInSet() == count)) {
            table.scrollBy(0, 240);
            frame();
            assertTrue(++frames < 2000, "the walk reaches the bottom");
        }
        assertTrue(keyHashes <= count,
                frames + " frames to the bottom read " + keyHashes + " keys for " + count + " rows");
    }

    /**
     * MODEL-NEW-4 (ADR 039 §1.10, amended 2026-09-14): a sort keeps every row's identifier and
     * moves the rows, and a client holding the old order has to be told — one
     * {@code STRUCTURE_CHANGED} on the table, naming the rows that stand at another rank than
     * they did, with their index now; nothing added, nothing removed.
     */
    @Test
    void sortingTheTableRaisesOneStructureChangeOnItNamingTheRowsThatMoved() {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        table.setRows(List.of(new Person("Carol", 3), new Person("Alice", 1),
                new Person("Bob", 2)));
        bind(table);
        long carol = rowNodes().get(0).id();
        long alice = rowNodes().get(1).id();
        long bob = rowNodes().get(2).id();
        long tableId = tableNode().id();
        bridge.events.clear();

        table.setSort(table.columns().get(0), SortOrder.ASCENDING);
        frame();

        List<limn.accessibility.AccessibleEvent> moved =
                bridge.eventsOf(limn.accessibility.AccessibleEvent.Type.STRUCTURE_CHANGED);
        assertEquals(1, moved.size(), "one structure change, on the table: " + bridge.events);
        assertEquals(tableId, moved.get(0).nodeId());
        assertEquals(List.of(), moved.get(0).addedChildren(), "the same rows" + bridge.events);
        assertEquals(List.of(), moved.get(0).removedChildren(), bridge.events.toString());
        List<Long> reordered = moved.get(0).reorderedChildren().stream()
                .map(limn.accessibility.AccessibleEvent.Child::id).toList();
        assertEquals(List.of(alice, bob, carol), reordered,
                "every row stands at another rank, in the order they stand now: " + bridge.events);
        List<AccessibleNode> rows = rowNodes();
        for (limn.accessibility.AccessibleEvent.Child child : moved.get(0).reorderedChildren()) {
            AccessibleNode row = rows.stream().filter(r -> r.id() == child.id()).findFirst()
                    .orElseThrow();
            assertEquals(tree().indexInParent(row), child.index(),
                    "the index carried is the row's place among the table's children now");
        }
        assertEquals(0, bridge.countOf(limn.accessibility.AccessibleEvent.Type.NODE_DESTROYED),
                "no row was destroyed by a sort: " + bridge.events);
    }

    /**
     * A reader selects a row and opens it, and since decisions 79 and 80 of 2026-09-17 it opens
     * <b>the row it addressed</b>. The select no longer moves the focus cell, so the table's own
     * {@code PRESS} — which opens the cursor row — is no longer the route for it: the row carries
     * a press of its own. Without that second half the change would have swapped one defect for
     * another, a reader selecting row 5 and opening row 2.
     */
    @Test
    void aReaderCanSelectARowAndOpenTheRowItAddressed() throws InterruptedException {
        Table<Person> table = bindTable(20);
        int[] activated = {-1};
        table.onActivate(index -> activated[0] = index);
        assertTrue(rowNodes().get(4).actions().actions().contains(Accessible.Action.SELECT));
        assertTrue(perform(rowNodes().get(4).id(), Accessible.Action.SELECT, null));
        assertEquals(4, table.selectedRow());
        assertEquals(-1, table.focusRow(), "the select left the cursor out of the table");
        frame();
        assertTrue(rowNodes().get(4).actions().actions().contains(Accessible.Action.PRESS),
                "the row carries the press: " + describe(tree()));
        assertTrue(perform(rowNodes().get(4).id(), Accessible.Action.PRESS, null));
        assertEquals(4, activated[0], "and it opened the row addressed");

        // The table's own press is still the cursor row's, and is published once there is one.
        // A caller's setter moves the cursor, which a reader's SELECT no longer does.
        table.setSelectedRow(6);
        frame();
        assertTrue(tableNode().actions().actions().contains(Accessible.Action.PRESS),
                "a press on the table is offered once it has a cursor row: " + describe(tree()));
        assertTrue(perform(tableNode().id(), Accessible.Action.PRESS, null));
        assertEquals(table.focusRow(), activated[0], "which opens the cursor row, not the lead");
    }

    /**
     * Decisions 10 and 20 of 2026-09-14: a row publishes the verbs its state allows and no
     * other — {@code SELECT} wherever a row can be selected, {@code ADD_TO_SELECTION} on an
     * unselected row and {@code DESELECT} on a selected one only in {@code MULTI} — and each
     * goes through the seam the matching gesture takes, so the handler hears a user.
     */
    @Test
    void aRowOffersTheVerbsItsStateAllowsAndTheTablePerformsThem() throws InterruptedException {
        Table<Person> table = bindTable(20);
        table.setSelectionMode(Table.SelectionMode.MULTI);
        List<String> selects = new ArrayList<>();
        table.onSelect(() -> selects.add(java.util.Arrays.toString(table.selectedRows())));
        table.setSelectedRow(2);
        frame();
        java.util.Set<Accessible.Action> chosen = rowNodes().get(2).actions().actions();
        java.util.Set<Accessible.Action> other = rowNodes().get(4).actions().actions();
        assertTrue(chosen.containsAll(java.util.Set.of(Accessible.Action.SELECT,
                Accessible.Action.DESELECT, Accessible.Action.FOCUS)), chosen.toString());
        assertFalse(chosen.contains(Accessible.Action.ADD_TO_SELECTION),
                "a selected row is not added again: " + chosen);
        assertTrue(other.containsAll(java.util.Set.of(Accessible.Action.SELECT,
                Accessible.Action.ADD_TO_SELECTION, Accessible.Action.FOCUS)), other.toString());
        assertFalse(other.contains(Accessible.Action.DESELECT), other.toString());

        assertTrue(perform(rowNodes().get(4).id(), Accessible.Action.ADD_TO_SELECTION, null));
        assertEquals("[2, 4]", java.util.Arrays.toString(table.selectedRows()), "added, not replaced");
        assertEquals(4, table.selectedRow(), "the added row is the lead, as under a command-click");
        // Only SELECT and FOCUS move a cursor (decision 20, semantics 5): the add leaves the
        // focus cell and the range anchor on row 2, where the user put them.
        assertEquals(2, table.focusRow(), "the add left the cursor where it stood");
        assertTrue(perform(rowNodes().get(6).id(), Accessible.Action.ADD_TO_SELECTION, null));
        assertTrue(perform(rowNodes().get(4).id(), Accessible.Action.DESELECT, null));
        assertEquals("[2, 6]", java.util.Arrays.toString(table.selectedRows()));
        assertEquals(2, table.focusRow(), "and so did the deselect");
        scene.requestFocus(table);
        drive(scene).keyEvent(Keys.DOWN, true, false, Keys.MOD_SHIFT);
        drive(scene).inputBatchEnded();
        assertEquals("[2, 3]", java.util.Arrays.toString(table.selectedRows()),
                "a Shift range still extends from the anchor on row 2");
        assertTrue(perform(rowNodes().get(7).id(), Accessible.Action.SELECT, null));
        assertEquals("[7]", java.util.Arrays.toString(table.selectedRows()), "a select is the click");
        // ...in what it does to the selection, and not in what it does to the cursor: since
        // decision 79 of 2026-09-17 no verb a reader sends moves the focus cell except FOCUS.
        // Measured cause (P5M-1): on AppKit a selection write leaves the keyboard focus alone, so
        // VoiceOver mirrors its cursor into the selection and every mirror dragged ours with it.
        assertEquals(3, table.focusRow(),
                "and a select leaves the cursor where the Shift key put it, on row 3");
        assertEquals(List.of("[2, 4]", "[2, 4, 6]", "[2, 6]", "[2, 3]", "[7]"), selects,
                "each reached the handler as a user");

        table.setSelectionMode(Table.SelectionMode.SINGLE);
        frame();
        for (AccessibleNode row : rowNodes()) {
            java.util.Set<Accessible.Action> verbs = row.actions().actions();
            assertFalse(verbs.contains(Accessible.Action.ADD_TO_SELECTION)
                    || verbs.contains(Accessible.Action.DESELECT),
                    "one row at a time: nothing to add to or take from: " + verbs);
            assertTrue(verbs.contains(Accessible.Action.SELECT));
        }
        table.setSelectionMode(Table.SelectionMode.NONE);
        frame();
        // Nothing to select in NONE, so no selection verb; what stays is what does not depend on
        // a selection — the cursor move, the reveal (decision 81) and the row's own press
        // (decision 80), which in NONE opens the row a reader addressed exactly as Enter opens
        // the row the keyboard is on.
        assertEquals(java.util.Set.of(Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW,
                        Accessible.Action.PRESS),
                rowNodes().get(0).actions().actions(), "no selection verb in NONE");
    }

    /**
     * Decision 11 of 2026-09-14: {@code FOCUS} on a cell or a row moves the cursor there and
     * selects nothing, because in a table the cursor and the selection are separate things —
     * the one verb that lets a reader walk the cells without changing what the user chose.
     */
    @Test
    void focusOnACellOrARowMovesTheCursorAndSelectsNothing() throws InterruptedException {
        Table<Person> table = bindTable(20);
        scene.requestFocus(table);
        table.setSelectedRow(1);
        frame();
        bridge.events.clear();
        AccessibleNode cell = childrenOf(rowNodes().get(3)).get(1);
        assertTrue(cell.actions().actions().contains(Accessible.Action.FOCUS));
        assertTrue(perform(cell.id(), Accessible.Action.FOCUS, null));
        frame();
        assertEquals(3, table.focusRow());
        assertEquals(1, table.focusColumn());
        assertEquals(1, table.selectedRow(), "the selection stayed where the user put it");
        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size());
        assertEquals(new CellFacet(3, 1), active.get(0).cell(), "the cursor is the cell asked for");
        assertEquals(1, bridge.countOf(
                limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "one cursor move: " + bridge.events);
        assertTrue(perform(rowNodes().get(5).id(), Accessible.Action.FOCUS, null));
        frame();
        assertEquals(5, table.focusRow(), "a row's FOCUS moves the cursor to that row");
        assertEquals(1, table.focusColumn(), "in the column it was in");
        assertEquals(1, table.selectedRow());
    }

    /**
     * TABLE-NEW-13 (found by the verb ratchet on 2026-09-14): a cell was keyed by its column
     * alone, and the table read any key below the row count as a row index, so a reader's
     * select on cell (0, 1) selected row 1 and a header's selected row 0. A cell's key carries
     * its row now, a header cell's says it is one, and neither accepts a verb it does not
     * publish.
     */
    @Test
    void aCellAndAColumnHeaderRefuseTheSelectOnlyARowPublishes() throws InterruptedException {
        Table<Person> table = bindTable(20);
        AccessibleNode cell = childrenOf(rowNodes().get(0)).get(1);
        assertFalse(cell.actions() != null
                && cell.actions().actions().contains(Accessible.Action.SELECT));
        perform(cell.id(), Accessible.Action.SELECT, null);
        assertEquals(-1, table.selectedRow(), "cell (0, 1) selected no row");
        AccessibleNode header = childrenOf(headerGroup()).get(0);
        perform(header.id(), Accessible.Action.SELECT, null);
        assertEquals(-1, table.selectedRow(), "and neither did the first header");
        perform(header.id(), Accessible.Action.FOCUS, null);
        assertEquals(-1, table.focusRow(), "a header is no row for the cursor either");
    }

    /**
     * Decision 32 of 2026-09-14: {@code PRESS} on the table opens the cursor row — the row the
     * focus cell is in — exactly as Enter and a double click do, so a reader that moved the
     * cursor with {@code FOCUS} opens what it is on and not the lead it left behind; in
     * {@code NONE}, where nothing is ever selected, the cursor row is what there is to open.
     */
    @Test
    void aPressOnTheTableOpensTheCursorRow() throws InterruptedException {
        Table<Person> table = bindTable(20);
        table.setSelectionMode(Table.SelectionMode.MULTI);
        List<Integer> opened = new ArrayList<>();
        table.onActivate(opened::add);
        table.setSelectedRows(1, 3);
        frame();
        assertTrue(perform(rowNodes().get(1).id(), Accessible.Action.FOCUS, null));
        frame();
        assertEquals(3, table.selectedRow(), "the lead is still row 3");
        assertTrue(perform(tableNode().id(), Accessible.Action.PRESS, null));
        assertEquals(List.of(1), opened, "what opened is the cursor row");

        table.setSelectionMode(Table.SelectionMode.NONE);
        frame();
        assertTrue(perform(rowNodes().get(6).id(), Accessible.Action.FOCUS, null));
        frame();
        assertTrue(tableNode().actions().actions().contains(Accessible.Action.PRESS),
                "a press is offered whenever there is a cursor, selection or not");
        assertTrue(perform(tableNode().id(), Accessible.Action.PRESS, null));
        assertEquals(List.of(1, 6), opened);
    }

    /**
     * One bound for the table's {@code PRESS}, published and performed (review of phase 2,
     * 2026-09-15): a cursor row past the end of a list the application shortened and has not
     * refreshed yet is no row to open, so the verb is neither offered nor performed, and
     * {@code onActivate} never hears an index the list does not hold; the refresh brings the
     * cursor back inside and the verb with it.
     */
    @Test
    void aPressIsOfferedAndPerformedOnTheSameCursorRowBound() throws InterruptedException {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60)));
        List<Person> rows = new ArrayList<>(people(10));
        table.setRows(rows);
        bind(table);
        List<Integer> opened = new ArrayList<>();
        table.onActivate(opened::add);
        table.setSelectedRow(8);
        frame();
        assertTrue(tableNode().actions().actions().contains(Accessible.Action.PRESS));

        rows.subList(5, 10).clear(); // shortened, not yet refreshed
        frame();
        assertFalse(tableNode().actions() != null
                        && tableNode().actions().actions().contains(Accessible.Action.PRESS),
                "no row 8 to open in a list of five: " + describe(tree()));
        // The host answers from membership alone (semantics 5 puts the published-list refusal in
        // the bridges), so the call is taken; what matters is that the table performs nothing.
        perform(tableNode().id(), Accessible.Action.PRESS, null);
        assertEquals(List.of(), opened, "nothing opened past the end");

        table.refresh();
        frame();
        assertEquals(4, table.focusRow(), "the refresh clamped the cursor");
        assertTrue(tableNode().actions().actions().contains(Accessible.Action.PRESS));
        assertTrue(perform(tableNode().id(), Accessible.Action.PRESS, null));
        assertEquals(List.of(4), opened);
    }

    /**
     * Decision 36 of 2026-09-14 from the reader's side: while the header holds the keyboard the
     * table is still the focused node and its cursor is a header cell, so a reader that follows
     * the effective focus hears the column title; a sortable header publishes {@code PRESS},
     * which sorts as a click does, and the sorted header describes the direction the rows run.
     */
    @Test
    void theHeadersColumnCursorIsTheCursorWhileTheHeaderHoldsTheKeyboard()
            throws InterruptedException {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60),
                Column.<Person>text("Note", p -> "-").width(60).sortable(false)));
        table.setRows(people(20));
        bind(table);
        scene.requestFocus(table);
        table.setSelectedRow(2);
        frame();
        List<AccessibleNode> headers = childrenOf(headerGroup());
        assertTrue(headers.get(0).actions().actions().contains(Accessible.Action.PRESS));
        assertTrue(headers.get(2).actions() == null
                || !headers.get(2).actions().actions().contains(Accessible.Action.PRESS),
                "a column that cannot be sorted offers no press: " + describe(tree()));
        bridge.events.clear();

        drive(scene).keyEvent(Keys.TAB, true, false, Keys.MOD_SHIFT);
        drive(scene).inputBatchEnded();
        frame();
        assertTrue(table.isHeaderFocused());
        assertEquals(tableNode().id(), tree().focused(), "the table is still the focused node");
        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), "one cursor, the header's: " + describe(tree()));
        assertEquals(Accessible.Role.COLUMN_HEADER, active.get(0).role());
        assertEquals(new CellFacet(-1, 0), active.get(0).cell());
        assertEquals("Name", active.get(0).name());
        assertEquals(active.get(0).id(), tree().activeDescendant());
        assertEquals(1, bridge.countOf(
                limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "the cursor left the focus cell for the header, once: " + bridge.events);

        drive(scene).keyEvent(Keys.RIGHT, true, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals(new CellFacet(-1, 1), nodesWith(Accessible.State.ACTIVE).get(0).cell(),
                "Right moves the header's cursor a column");

        assertTrue(perform(childrenOf(headerGroup()).get(0).id(), Accessible.Action.PRESS, null));
        frame();
        assertEquals(SortOrder.ASCENDING, table.sortOrder(), "a press on a header sorts");
        headers = childrenOf(headerGroup());
        assertEquals("Sorted ascending", headers.get(0).description(),
                "the sorted header says which way: " + describe(tree()));
        assertTrue(headers.get(1).description() == null || headers.get(1).description().isEmpty(),
                "the others say nothing: " + describe(tree()));
        drive(scene).keyEvent(Keys.LEFT, true, false, 0);
        drive(scene).inputBatchEnded();
        drive(scene).keyEvent(Keys.SPACE, true, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals("Sorted descending", childrenOf(headerGroup()).get(0).description());

        drive(scene).keyEvent(Keys.TAB, true, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertFalse(table.isHeaderFocused());
        active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size());
        assertEquals(Accessible.Role.CELL, active.get(0).role(), "the focus cell is the cursor again");
        assertEquals("Person 2", active.get(0).name(), "on the record it was on, sorted away");

        // A reader's press on a header while the rows hold the keyboard sorts and, as the
        // pointer's click does, remembers the column: the next Shift+Tab into the header
        // starts on the title that was pressed, not on the one the cursor last stood on.
        assertTrue(perform(childrenOf(headerGroup()).get(1).id(), Accessible.Action.PRESS, null));
        frame();
        assertEquals("Age", table.sortColumn().title().english(), "the press sorted by the pressed column");
        assertFalse(table.isHeaderFocused(), "and left the keyboard in the rows");
        drive(scene).keyEvent(Keys.TAB, true, false, Keys.MOD_SHIFT);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals(new CellFacet(-1, 1, CellFacet.Sort.ASCENDING),
                nodesWith(Accessible.State.ACTIVE).get(0).cell(),
                "the header's cursor remembers the pressed column, and that header now carries "
                        + "the direction its press set: " + describe(tree()));
    }

    // ------------------------------------------------------------------ the sort direction (36)

    /**
     * Decision 36's carrier, settled 2026-09-15: the direction a sorted column's rows run is a
     * component of the header cell's {@code CellFacet}, so the two platforms whose carrier is an
     * enumeration read a fact rather than parse a phrase. The localized phrase stays in the
     * header's description beside it, for the one platform whose carrier is a phrase: a bridge
     * reads the snapshot where no locale scope is open and could not build one.
     */
    @Test
    void aSortedHeaderCarriesItsDirectionOnItsCellFacetAndKeepsThePhraseInItsDescription()
            throws InterruptedException {
        bindTable(30);

        for (AccessibleNode header : childrenOf(headerGroup())) {
            assertEquals(CellFacet.Sort.NONE, header.cell().sort(),
                    "nothing is sorted yet" + describe(tree()));
            assertEquals("", header.description(), describe(tree()));
        }

        assertTrue(perform(childrenOf(headerGroup()).get(0).id(), Accessible.Action.PRESS, null));
        frame();

        assertEquals(new CellFacet(-1, 0, CellFacet.Sort.ASCENDING),
                childrenOf(headerGroup()).get(0).cell(),
                "the pressed header is the sorted one" + describe(tree()));
        assertEquals("Sorted ascending", childrenOf(headerGroup()).get(0).description(),
                "and the phrase stands beside it, for the platform that carries words"
                        + describe(tree()));
        assertEquals(CellFacet.Sort.NONE, childrenOf(headerGroup()).get(1).cell().sort(),
                "a header of a column nobody sorted by says NONE, not nothing" + describe(tree()));

        assertTrue(perform(childrenOf(headerGroup()).get(0).id(), Accessible.Action.PRESS, null));
        frame();

        assertEquals(CellFacet.Sort.DESCENDING, childrenOf(headerGroup()).get(0).cell().sort(),
                "a second press turns the column round" + describe(tree()));
        assertEquals("Sorted descending", childrenOf(headerGroup()).get(0).description(),
                describe(tree()));

        for (AccessibleNode cell : nodesWith(Accessible.State.SHOWING)) {
            if (cell.cell() != null && cell.cell().row() >= 0) {
                assertEquals(CellFacet.Sort.NONE, cell.cell().sort(),
                        "a data cell is not a header and sorts nothing" + describe(tree()));
            }
        }
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
     * The shape the header-group rule rests on (settled as header-group-rule; ADR 041 §7's
     * amendment of 2026-09-14, TABLE-NEW-11): a table with no header and a footer publishes
     * exactly one {@code GROUP}, the footer, whose cells all carry row {@code -2}, and no
     * {@code COLUMN_HEADER} anywhere; a headerless table has no header group rather than an
     * empty one. The three bridges' header lookups move to that rule in phase 3 and read this.
     */
    @Test
    void aHeaderlessTableWithAFooterPublishesOnlyTheFooterGroup() {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120).footer("Total"),
                Column.numeric("Age", Person::age).width(60).footerSum()));
        table.setRows(people(3));
        table.setShowHeader(false);
        bind(table);
        List<AccessibleNode> groups = new ArrayList<>();
        for (AccessibleNode child : childrenOf(tableNode())) {
            if (child.role() == Accessible.Role.GROUP) {
                groups.add(child);
            }
        }
        assertEquals(1, groups.size(), "one group, the footer: " + describe(tree()));
        List<AccessibleNode> cells = childrenOf(groups.get(0));
        assertEquals(2, cells.size(), "a cell per shown column");
        for (int c = 0; c < cells.size(); c++) {
            assertEquals(new CellFacet(-2, c), cells.get(c).cell(), "a footer cell: " + cells.get(c));
        }
        for (int i = 0; i < tree().nodeCount(); i++) {
            assertFalse(tree().node(i).role() == Accessible.Role.COLUMN_HEADER,
                    "no column header anywhere: " + describe(tree()));
        }
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
                Column.numeric("Age", Person::age).width(60)));
        // Table is final (ADR 046 §2): what reaches the table is read off its watcher channel,
        // where any verb it performed on a row would be heard.
        table.observeChanges((widget, change) -> routedToTable.add(String.valueOf(change)));
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
        routedToTable.clear();
        assertTrue(perform(button, Accessible.Action.PRESS, null));
        assertEquals(List.of("Person 1"), pressed, "the button's own handler ran");
        assertTrue(routedToTable.isEmpty(),
                "and the table itself did nothing: " + routedToTable);
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
     * B1 (2026-09-14): the focus ring was drawn on a widget column's cell and the reader was
     * told nothing, so every Right into the "Flagged" column emptied the active descendant. The
     * control's own node is the cursor there, exactly as a value cell is; found by its facet,
     * not its position, and with the same one-event announcement.
     */
    @Test
    void aFocusCellInAWidgetColumnIsTheActiveDescendant() {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.<Person>widget("Open", p -> new Button(p.name())).width(80)));
        table.setRows(people(30));
        bind(table);
        scene.requestFocus(table);
        table.setSelectedRow(3);
        frame();
        bridge.events.clear();
        drive(scene).keyEvent(Keys.RIGHT, true, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), "one cursor: " + describe(tree()));
        assertEquals(Accessible.Role.BUTTON, active.get(0).role(), "the control is the cursor");
        assertEquals(new CellFacet(3, 1), active.get(0).cell());
        assertEquals(active.get(0).id(), tree().activeDescendant());
        assertEquals(1, bridge.countOf(
                limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "one cursor move, announced once: " + bridge.events);
        drive(scene).keyEvent(Keys.LEFT, true, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals(new CellFacet(3, 0), nodesWith(Accessible.State.ACTIVE).get(0).cell(),
                "and Left brings the cursor back to the value cell");
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

    /**
     * P5W-1 (2026-09-16), the model's, confirmed on all three platforms: a {@code ROW} published
     * an empty name, so NVDA 2024.4.2 spoke {@code 'item de dados', 'selecionado', '4 de 10'} —
     * role, position, no name — and Orca discarded the row outright, an unnamed row that is not
     * focusable, selectable or expandable being "believed to be layout only". A row is its record
     * (decision 23): the text of its shown cells, in column order, the way a composite
     * {@code TREE_ITEM} is named (TREE-ROW-NAME).
     */
    @Test
    void aRowIsNamedByTheTextOfItsCellsInColumnOrder() {
        Table<Person> table = bindTable(30);
        List<AccessibleNode> rows = rowNodes();
        assertTrue(rows.size() > 3, "realized rows: " + rows.size());
        AccessibleNode second = rows.get(1);
        List<AccessibleNode> cells = childrenOf(second);
        assertEquals(cells.get(0).name() + " " + cells.get(1).name(), second.name(),
                "the row reads as its cells: " + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, second.nameFrom());
        assertFalse(second.name().isEmpty(), "and is never the empty name P5W-1 measured");

        // A hidden column says nothing, exactly as it publishes no cell (B6).
        table.columns().get(1).visible(false);
        table.refresh();
        frame();
        AccessibleNode narrowed = rowNodes().get(1);
        assertEquals(1, childrenOf(narrowed).size(), "one shown column: " + describe(tree()));
        assertEquals(childrenOf(narrowed).get(0).name(), narrowed.name(),
                "so one word in the name: " + describe(tree()));
    }

    /**
     * The other half of P5W-1: a widget column holds no text of the table's, so the row reads the
     * labels inside the control the way {@code Tree} reads a composite cell's (TREE-ROW-NAME).
     * A control that paints its own text without a {@code Label} — a button — lends nothing and
     * is heard as its own cell instead, which is the same rule the tree has lived under.
     */
    @Test
    void aWidgetColumnLendsItsLabelsToTheRowsNameAndAButtonLendsNothing() {
        Table<Person> labelled = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.<Person>widget("Note", person -> new Label("note for " + person.name()))
                        .width(120)));
        labelled.setRows(people(30));
        bind(labelled);
        assertEquals("Person 1 note for Person 1", rowNodes().get(1).name(),
                "the label inside the cell is part of the record: " + describe(tree()));

        Table<Person> buttoned = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.<Person>widget("Edit", person -> new Button("Edit " + person.name()))
                        .width(80)));
        buttoned.setRows(people(30));
        bind(buttoned);
        assertEquals("Person 1", rowNodes().get(1).name(),
                "a button paints its own text and lends none: " + describe(tree()));
    }

    /**
     * B6 (2026-09-14): a hidden widget column's never-laid-out widget was published as a node
     * with a {@code CellFacet} column equal to the table's column count, which on Windows reached
     * the GridItem pattern as an out-of-range column. A hidden column, widget or value, is not
     * published at all, and the table's column count is the shown count.
     */
    @Test
    void aHiddenColumnPublishesNoCell() {
        Table<Person> table = new Table<>(List.of(
                Column.text("Name", Person::name).width(120),
                Column.numeric("Age", Person::age).width(60).visible(false),
                Column.<Person>widget("Open", p -> new Button(p.name())).width(80)
                        .visible(false)));
        table.setRows(people(30));
        bind(table);
        assertEquals(1, tableNode().table().columnCount(), "the shown count");
        assertEquals(1, childrenOf(headerGroup()).size(), "one header cell");
        int cells = 0;
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode n = tree().node(i);
            assertTrue(n.role() != Accessible.Role.BUTTON, "no widget, so no node: " + n);
            if (n.cell() != null) {
                cells++;
                assertTrue(n.cell().column() < 1, "a cell of a hidden column: " + n);
            }
        }
        assertTrue(cells > 3, "the shown column's cells are still there: " + cells);
    }

    /**
     * TABLE-NEW-5 (2026-09-14): hiding the column the focus cell stood on left {@code
     * focusColumn} pointing at a shown index no column matched, so no ring was drawn and no
     * cell was {@code ACTIVE} until a Left or Right re-clamped it; hiding a column before it
     * silently shifted the cursor onto the next column's cell. The focus cell now follows its
     * column: hidden, it moves to the nearest shown column and says so; a column hidden before
     * it shifts the index and the cell stays on its record and its column.
     */
    @Test
    void hidingTheFocusColumnKeepsACursorOnTheNearestShownColumn() {
        Column<Person> name = Column.text("Name", Person::name).width(120);
        Column<Person> age = Column.numeric("Age", Person::age).width(60);
        Column<Person> again = Column.text("Again", Person::name).width(100);
        Table<Person> table = new Table<>(List.of(name, age, again));
        table.setRows(people(30));
        bind(table);
        scene.requestFocus(table);
        table.setSelectedRow(2);
        frame();
        for (int i = 0; i < 2; i++) {
            drive(scene).keyEvent(Keys.RIGHT, true, false, 0);
            drive(scene).inputBatchEnded();
            frame();
        }
        assertEquals(2, table.focusColumn());
        assertEquals(new CellFacet(2, 2), nodesWith(Accessible.State.ACTIVE).get(0).cell());
        bridge.events.clear();
        List<Change> heard = new ArrayList<>();
        table.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.ACTIVE) {
                heard.add(change);
            }
        });

        again.visible(false);
        table.refresh();
        frame();
        assertEquals(1, table.focusColumn(), "clamped to the nearest shown column");
        assertEquals(1, heard.size(), "announced once to a watcher: " + heard);
        assertEquals(Change.Origin.ADJUSTMENT, heard.get(0).origin(),
                "as a consequence of the column going, not a gesture");
        heard.clear();
        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), "one cursor: " + describe(tree()));
        assertEquals(new CellFacet(2, 1), active.get(0).cell());
        assertEquals("22", active.get(0).name(), "Person 2's age, the column beside the hidden one");
        assertEquals(active.get(0).id(), tree().activeDescendant());
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "the cursor moved once, and a reader was told: " + bridge.events);
        long ageCell = active.get(0).id();
        bridge.events.clear();

        // A column before the cursor hidden: the index shifts, the cell does not move.
        name.visible(false);
        table.refresh();
        frame();
        assertEquals(0, table.focusColumn(), "the first shown column now");
        active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), "still one cursor: " + describe(tree()));
        assertEquals(ageCell, active.get(0).id(), "the same cell, on the same record");
        assertEquals(new CellFacet(2, 0), active.get(0).cell());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "the cursor did not move, so nothing was announced: " + bridge.events);
        assertEquals(List.of(), heard, "nor to a watcher");

        // Shown again: the cursor stays on its column, which is the second shown one again.
        name.visible(true);
        table.refresh();
        frame();
        assertEquals(1, table.focusColumn());
        assertEquals(ageCell, nodesWith(Accessible.State.ACTIVE).get(0).id());
    }

    /**
     * The header's column cursor (decision 36) under the rule the focus cell follows when a
     * column is hidden (review of table-B, 2026-09-14): it kept a plain index clamp, so hiding
     * its column moved it onto whatever column the index now named, and hiding one before it
     * slid it onto the next column, both unannounced. It follows its column now; and a layout
     * that takes the header's stop away while it holds the cursor says so, the cursor back on
     * the focus cell.
     */
    @Test
    void hidingTheHeadersColumnKeepsItsCursorOnTheNearestShownColumn() {
        Column<Person> name = Column.text("Name", Person::name).width(120);
        Column<Person> age = Column.numeric("Age", Person::age).width(60);
        Column<Person> again = Column.text("Again", Person::name).width(100);
        Column<Person> note = Column.<Person>text("Note", p -> "-").width(60).sortable(false);
        Table<Person> table = new Table<>(List.of(name, age, again, note));
        table.setRows(people(30));
        bind(table);
        scene.requestFocus(table);
        table.setSelectedRow(2);
        frame();
        drive(scene).keyEvent(Keys.TAB, true, false, Keys.MOD_SHIFT);
        drive(scene).inputBatchEnded();
        for (int i = 0; i < 2; i++) {
            drive(scene).keyEvent(Keys.RIGHT, true, false, 0);
            drive(scene).inputBatchEnded();
        }
        frame();
        assertTrue(table.isHeaderFocused());
        assertEquals(2, table.headerColumn());
        assertEquals(new CellFacet(-1, 2), nodesWith(Accessible.State.ACTIVE).get(0).cell());
        bridge.events.clear();
        List<Change> heard = new ArrayList<>();
        table.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.ACTIVE) {
                heard.add(change);
            }
        });

        again.visible(false);
        table.refresh();
        frame();
        assertTrue(table.isHeaderFocused(), "the header is still a stop, and still holds the keyboard");
        assertEquals(1, table.headerColumn(), "the nearest shown column, the one before on a tie");
        assertEquals(1, heard.size(), "announced once to a watcher: " + heard);
        assertEquals(Change.Origin.ADJUSTMENT, heard.get(0).origin());
        heard.clear();
        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), "one cursor: " + describe(tree()));
        assertEquals(Accessible.Role.COLUMN_HEADER, active.get(0).role());
        assertEquals(new CellFacet(-1, 1), active.get(0).cell());
        assertEquals("Age", active.get(0).name());
        assertEquals(active.get(0).id(), tree().activeDescendant());
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "the cursor moved once, and a reader was told: " + bridge.events);
        long ageHeader = active.get(0).id();
        bridge.events.clear();

        // A column before the cursor hidden: the index shifts, the header cell does not move.
        name.visible(false);
        table.refresh();
        frame();
        assertEquals(0, table.headerColumn(), "the first shown column now");
        active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), "still one cursor: " + describe(tree()));
        assertEquals(ageHeader, active.get(0).id(), "the same header cell");
        assertEquals(new CellFacet(-1, 0), active.get(0).cell());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "the cursor did not move, so nothing was announced: " + bridge.events);
        assertEquals(List.of(), heard, "nor to a watcher");

        // Shown again: the cursor stays on Age, the second shown column again.
        name.visible(true);
        table.refresh();
        frame();
        assertEquals(1, table.headerColumn());
        assertEquals(ageHeader, nodesWith(Accessible.State.ACTIVE).get(0).id());
        bridge.events.clear();

        // The header hidden under the cursor: no stop, the keyboard is in the rows. The same
        // layout path takes the stop away when the last sortable column is hidden; hiding a
        // column is not used here because the focus cell's own column moving would announce
        // it anyway and hide a silent header.
        table.setShowHeader(false);
        frame();
        assertFalse(table.isHeaderFocused(), "a header that is not shown is not a stop");
        assertEquals(1, heard.size(), "the cursor went back to the rows, announced once: " + heard);
        active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), "one cursor: " + describe(tree()));
        assertEquals(Accessible.Role.CELL, active.get(0).role(), "the focus cell, on the one shown column");
        assertEquals(active.get(0).id(), tree().activeDescendant());
    }

    private static List<Column<Person>> fiveColumns() {
        List<Column<Person>> columns = new ArrayList<>();
        for (int c = 0; c < 5; c++) {
            final int n = c;
            columns.add(Column.<Person>text("C" + c, p -> "P" + p.age() + "c" + n).width(120));
        }
        return columns;
    }

    /**
     * B3 (2026-09-14): every shown column of a realized row is published, in view or not, and
     * the ones outside the horizontal viewport are published without {@code SHOWING} — header
     * cells, cells and footer cells alike — with the horizontal scroll on the table node.
     */
    @Test
    void aColumnScrolledAwayIsPublishedOffScreen() {
        List<Column<Person>> columns = fiveColumns();
        columns.get(4).footerCount();
        Table<Person> table = new Table<>(columns);
        table.setRows(people(30));
        bind(table); // 400 points wide: three columns in view, the fourth cut, the fifth out
        ScrollFacet scroll = tableNode().scroll();
        assertTrue(scroll.horizontallyScrollable());
        assertEquals(0, scroll.horizontalPercent(), 1e-4);
        assertEquals(400.0 / 600, scroll.horizontalViewSize(), 1e-4);
        List<AccessibleNode> headers = childrenOf(headerGroup());
        assertEquals(5, headers.size(), "every shown column, in view or not");
        assertTrue(headers.get(3).has(Accessible.State.SHOWING), "the cut column is on screen");
        assertFalse(headers.get(4).has(Accessible.State.SHOWING), "the one past the edge is not");
        List<AccessibleNode> cells = childrenOf(rowNodes().get(0));
        assertEquals(5, cells.size());
        assertTrue(cells.get(0).has(Accessible.State.SHOWING));
        assertFalse(cells.get(4).has(Accessible.State.SHOWING), "a cell off the edge: " + cells.get(4));
        long lastCell = cells.get(4).id();
        List<AccessibleNode> footer = childrenOf(footerGroup());
        assertEquals(1, footer.size(), "the one column with a footer");
        assertEquals(new CellFacet(-2, 4), footer.get(0).cell());
        assertFalse(footer.get(0).has(Accessible.State.SHOWING), "the footer cell is off the edge too");

        table.scrollBy(200, 0);
        frame();
        assertEquals(1, tableNode().scroll().horizontalPercent(), 1e-4, "200 of a maximum of 200");
        headers = childrenOf(headerGroup());
        assertFalse(headers.get(0).has(Accessible.State.SHOWING), "scrolled out on the left");
        assertTrue(headers.get(4).has(Accessible.State.SHOWING));
        cells = childrenOf(rowNodes().get(0));
        assertFalse(cells.get(0).has(Accessible.State.SHOWING));
        assertTrue(cells.get(4).has(Accessible.State.SHOWING));
        assertEquals(lastCell, cells.get(4).id(), "the same cell, in view now");
        assertTrue(childrenOf(footerGroup()).get(0).has(Accessible.State.SHOWING));
    }

    /** The last {@code GROUP} child of the table: the footer's, after the rows (the bars follow). */
    private AccessibleNode footerGroup() {
        List<AccessibleNode> children = childrenOf(tableNode());
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).role() == Accessible.Role.GROUP) {
                assertTrue(i > 0, "the footer group comes after the header group");
                return children.get(i);
            }
        }
        throw new AssertionError("no footer group among " + children);
    }

    /**
     * The percent is published unflipped, the rule the scroll pane, the tab strip and the tree
     * settled (ADR 039): the offset is a distance from the edge reading starts from, and the
     * mirroring is in where the columns sit. A flipped percent would tell a reader that a table
     * resting on its first column is scrolled to the end.
     */
    @Test
    void theHorizontalPercentIsNotFlippedRightToLeft() {
        Table<Person> table = new Table<>(fiveColumns());
        table.setRows(people(30));
        table.setLayoutDirection(limn.scene.LayoutDirection.RTL);
        bind(table);
        assertTrue(table.isRightToLeft(), "the fixture really did mirror the table");
        List<AccessibleNode> headers = childrenOf(headerGroup());
        assertEquals(400, headers.get(0).x() + headers.get(0).width(), 1e-3,
                "the first column ends at the right edge: " + describe(tree()));
        assertEquals(0, tableNode().scroll().horizontalPercent(), 1e-4,
                "zero is the leading edge, which is the right edge here");
        assertFalse(headers.get(4).has(Accessible.State.SHOWING), "the last column hangs off the left");

        table.scrollBy(200, 0);
        frame();
        assertEquals(1, tableNode().scroll().horizontalPercent(), 1e-4,
                "the end is one, whichever side it is on");
        headers = childrenOf(headerGroup());
        assertFalse(headers.get(0).has(Accessible.State.SHOWING), "the first column went off the right");
        assertTrue(headers.get(4).has(Accessible.State.SHOWING), "and the last came in from the left");
        assertEquals(0, headers.get(4).x(), 1e-3);
    }
}

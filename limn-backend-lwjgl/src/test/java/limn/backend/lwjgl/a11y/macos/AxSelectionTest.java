package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container's selection as AppKit reads it and is told of it (MACOS-NEW-2; semantics 1; decision
 * 9): answered from the members whose selection container it is, off the attribute of its shape, and
 * a change of it posted on the container as that attribute's notification.
 */
@ExtendWith(PlatformFreeBridges.class)
class AxSelectionTest {

    /**
     * WINDOW > [TAB_LIST 1001 (selection) > TAB 1002, TAB 1003 (selected);
     * TABLE 1010 (6x7, selection: a calendar) > synthetic ROW "week" > synthetic CELL "day 1" (0,0),
     * CELL "day 2" (0,1, selected);
     * TREE 1020 (selection) > TREE_ITEM 1021 (selected), TREE_ITEM 1022;
     * TABLE 1030 (2x1, selection: rows) > ROW 1031 (selected) > CELL 1032; BUTTON 1040].
     */
    private static AccessibleTree aWindow() {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);

        int tabs = open(a, 1001, 0, Accessible.Role.TAB_LIST);
        a.selection(false, true);
        open(a, 1002, tabs, Accessible.Role.TAB);
        a.selectionItem(false, 1, 2);
        a.end();
        open(a, 1003, tabs, Accessible.Role.TAB);
        a.selectionItem(true, 2, 2);
        a.end();
        a.end();

        int calendar = open(a, 1010, 0, Accessible.Role.TABLE);
        a.table(6, 7);
        a.selection(false, false);
        // The week and its days are synthetic, as CalendarView's are: a member climbs to its container
        // only through synthetic ancestors (semantics 1).
        a.child(1);
        a.role(Accessible.Role.ROW);
        a.name(I18nString.literal("week"), Accessible.NameFrom.CONTENT);
        for (int day = 0; day < 2; day++) {
            a.child(10 + day);
            a.role(Accessible.Role.CELL);
            a.name(I18nString.literal("day " + (day + 1)), Accessible.NameFrom.CONTENT);
            a.cell(0, day);
            a.selectionItem(day == 1, day + 1, 30);
            a.endChild();
        }
        a.endChild();
        a.end();

        int outline = open(a, 1020, 0, Accessible.Role.TREE);
        a.selection(false, false);
        open(a, 1021, outline, Accessible.Role.TREE_ITEM);
        a.selectionItem(true, 1, 2);
        a.hierarchy(1, 1, 2);
        a.end();
        open(a, 1022, outline, Accessible.Role.TREE_ITEM);
        a.selectionItem(false, 2, 2);
        a.hierarchy(1, 2, 2);
        a.end();
        a.end();

        int table = open(a, 1030, 0, Accessible.Role.TABLE);
        a.table(2, 1);
        a.selection(false, false);
        int row = open(a, 1031, table, Accessible.Role.ROW);
        a.selectionItem(true, 1, 2);
        open(a, 1032, row, Accessible.Role.CELL);
        a.cell(0, 0);
        a.end();
        a.end();
        a.end();

        open(a, 1040, 0, Accessible.Role.BUTTON);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    private static long named(AccessibleTree tree, String name) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).name().equals(name)) return tree.node(i).id();
        }
        throw new AssertionError("no node named " + name);
    }

    private static int open(Accessibility a, long id, int parent, Accessible.Role role) {
        int index = a.begin(id, parent, Locale.ENGLISH, 0, 0, 40, 20);
        a.role(role);
        a.name(I18nString.literal(role + " " + id), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, false, false);
        return index;
    }

    @Test
    void aContainersSelectionIsItsSelectedMembersWhereverTheyHang() {
        AccessibleTree tree = aWindow();
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        AxGrid grid = new AxGrid(bridge);
        assertArrayEquals(new long[] {bridge.elementFor(1003)}, grid.selectedMembers(tree.find(1001)),
                "a tab strip's selected tab");
        assertArrayEquals(new long[] {bridge.elementFor(named(tree, "day 2"))},
                grid.selectedMembers(tree.find(1010)),
                "a calendar's selected day, which hangs under its week and not under the grid");
        assertNull(grid.selectedMembers(tree.find(1040)), "a button holds no selection");
        assertNull(grid.selectedMembers(tree.find(named(tree, "week"))),
                "nor does the week row a day hangs under");
    }

    @Test
    void eachContainerOffersTheSelectionAttributeOfItsShapeAndNoOther() {
        AccessibleTree tree = aWindow();
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        AxGrid grid = new AxGrid(bridge);
        assertEquals(AxGrid.SelectionShape.CHILDREN, grid.selectionShape(tree.find(1001)));
        assertEquals(AxGrid.SelectionShape.CELLS, grid.selectionShape(tree.find(1010)));
        assertEquals(AxGrid.SelectionShape.ROWS, grid.selectionShape(tree.find(1020)));
        assertEquals(AxGrid.SelectionShape.ROWS, grid.selectionShape(tree.find(1030)));

        assertTrue(AxGate.allows(grid, tree.find(1001), "accessibilitySelectedChildren"));
        assertFalse(AxGate.allows(grid, tree.find(1001), "accessibilitySelectedCells"));
        assertTrue(AxGate.allows(grid, tree.find(1010), "accessibilitySelectedCells"));
        assertFalse(AxGate.allows(grid, tree.find(1010), "accessibilitySelectedChildren"));
        for (long rows : new long[] {1020, 1030}) {
            assertFalse(AxGate.allows(grid, tree.find(rows), "accessibilitySelectedChildren"),
                    rows + ": a native outline answers AXSelectedRows and no AXSelectedChildren");
            assertFalse(AxGate.allows(grid, tree.find(rows), "accessibilitySelectedCells"), String.valueOf(rows));
        }
        for (String selector : new String[] {"accessibilitySelectedChildren", "accessibilitySelectedCells"}) {
            assertFalse(AxGate.allows(grid, tree.find(1040), selector), "a button: " + selector);
        }
    }

    @Test
    void aSelectionChangeIsPostedOnItsContainerAsItsShapesNotification() {
        AccessibleTree tree = aWindow();
        AxBridge bridge = PlatformFreeBridges.make();
        List<String> trace = new ArrayList<>();
        bridge.trace(trace::add);
        bridge.publish(tree, false);   // the root's children, every container here, are pushed and held
        long[][] cases = {{1020, 1021}, {1030, 1031}, {1010, named(tree, "day 2")}, {1001, 1003}};
        for (long[] c : cases) {
            bridge.emit(AccessibleEvent.selection(c[0], false, new long[] {c[1]}, new long[0]));
        }
        bridge.frameEnded();
        List<String> posted = trace.stream().filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
        assertEquals(List.of("NSAccessibilitySelectedRowsChangedNotification",
                        "NSAccessibilitySelectedRowsChangedNotification",
                        "NSAccessibilitySelectedCellsChangedNotification",
                        "NSAccessibilitySelectedChildrenChangedNotification"), posted,
                "an outline and a table of rows as a native outline posts on itself, a grid of days as "
                        + "cells, a tab strip as children; one each, on the container");
    }

    @Test
    void aMembersSelectedFlipIsToldOnlyByItsContainersChangeAndACursorFlipByNothing() {
        AccessibleTree tree = aWindow();
        AxBridge bridge = PlatformFreeBridges.make();
        List<String> trace = new ArrayList<>();
        bridge.trace(trace::add);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1020));   // the outline's rows are held, as a reader's walk holds them
        bridge.childElementsOf(tree.find(1001));   // and the tabs

        // One arrow in a tree: the selection and the cursor leave one row for the next.
        bridge.emit(AccessibleEvent.state(1021, Accessible.State.SELECTED, false));
        bridge.emit(AccessibleEvent.state(1022, Accessible.State.SELECTED, true));
        bridge.emit(AccessibleEvent.state(1021, Accessible.State.ACTIVE, false));
        bridge.emit(AccessibleEvent.state(1022, Accessible.State.ACTIVE, true));
        bridge.emit(AccessibleEvent.selection(1020, false, new long[] {1022}, new long[] {1021}));
        // A tab strip told nothing of its selection in this frame: its member's flip is still told.
        bridge.emit(AccessibleEvent.state(1002, Accessible.State.SELECTED, true));
        bridge.frameEnded();
        List<String> posted = trace.stream().filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
        assertEquals(List.of("NSAccessibilitySelectedRowsChangedNotification",
                        "NSAccessibilityValueChangedNotification"), posted,
                "the outline's rows change once, and neither row is told a value change for its "
                        + "selected or its active flip; the tab whose strip said nothing is still told");
    }

    /**
     * A LIST is a table of rows here, so its selection is posted as {@code AXSelectedRowsChanged}.
     * That was inferred from the outline reading until 2026-09-15 and is now read on a native
     * {@code NSTableView} used as a list ({@code scripts/a11y/macos/list-probe.swift},
     * `readings/macos-list-probe.txt`): writing {@code AXSelected} on one of its rows delivered
     * {@code AXSelectedRowsChanged} to an observer on the table and to one on the application, and
     * that view vends no {@code AXSelectedChildren} at all.
     */
    @Test
    void aListsSelectionIsPostedAsTheRowsNotificationAsANativeListPostsIt() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        int list = open(a, 1001, 0, Accessible.Role.LIST);
        a.selection(false, false);
        for (int i = 0; i < 2; i++) {
            open(a, 1002 + i, list, Accessible.Role.LIST_ITEM);
            a.selectionItem(i == 0, i + 1, 2);
            a.end();
        }
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);

        AxBridge bridge = PlatformFreeBridges.make();
        List<String> trace = new ArrayList<>();
        bridge.trace(trace::add);
        bridge.publish(tree, false);
        assertEquals(AxGrid.SelectionShape.ROWS, new AxGrid(bridge).selectionShape(tree.find(1001)),
                "a list holding a selection answers its rows, as an outline does");
        bridge.emit(AccessibleEvent.selection(1001, false, new long[] {1003}, new long[] {1002}));
        bridge.frameEnded();
        assertEquals(List.of("NSAccessibilitySelectedRowsChangedNotification"),
                trace.stream().filter(line -> line.startsWith("posted "))
                        .map(line -> line.substring("posted ".length())).toList(),
                "and is told of a change the way the native list is: the rows notification, not children");
    }

    @Test
    void theSelectionNotificationsAreNamedForTheConstantsTest() {
        assertTrue(AxNotifications.symbols().contains("NSAccessibilitySelectedRowsChangedNotification"));
        assertTrue(AxNotifications.symbols().contains("NSAccessibilitySelectedCellsChangedNotification"));
    }
}

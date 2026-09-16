package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which installed selectors each node offers: {@code isAccessibilitySelectorAllowed:}, which AppKit
 * honours for a getter as it does for an action (read on the guest, readings/macos-summary.md §6).
 */
@ExtendWith(PlatformFreeBridges.class)
class AxGateTest {

    /**
     * WINDOW > [TABLE 1001 (2x2) > ROW 1002 > CELL 1003; TREE 1010 > TREE_ITEM 1011; LIST 1020 >
     * LIST_ITEM 1021; BUTTON 1030 (PRESS); CHECK_BOX 1040 (TOGGLE)].
     */
    private static AccessibleTree aWindow() {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        int table = open(a, 1001, 0, Accessible.Role.TABLE);
        a.table(2, 2);
        a.selection(false, false);
        int row = open(a, 1002, table, Accessible.Role.ROW);
        a.selectionItem(false, 1, 2);
        open(a, 1003, row, Accessible.Role.CELL);
        a.cell(0, 0);
        a.end();
        a.end();
        a.end();
        int tree = open(a, 1010, 0, Accessible.Role.TREE);
        a.selection(false, false);
        open(a, 1011, tree, Accessible.Role.TREE_ITEM);
        a.selectionItem(false, 1, 1);
        a.hierarchy(1, 1, 1);
        a.end();
        a.end();
        int list = open(a, 1020, 0, Accessible.Role.LIST);
        a.selection(false, false);
        open(a, 1021, list, Accessible.Role.LIST_ITEM);
        a.selectionItem(false, 1, 1);
        a.end();
        a.end();
        open(a, 1030, 0, Accessible.Role.BUTTON);
        a.action(Accessible.Action.PRESS);
        a.end();
        open(a, 1040, 0, Accessible.Role.CHECK_BOX);
        a.action(Accessible.Action.TOGGLE);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    private static int open(Accessibility a, long id, int parent, Accessible.Role role) {
        int index = a.begin(id, parent, Locale.ENGLISH, 0, 0, 40, 20);
        a.role(role);
        a.name(I18nString.literal(role + " " + id), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, false, false);
        return index;
    }

    private record Gate(AccessibleTree tree, AxGrid grid) {
        boolean allows(long id, String selector) {
            return AxGate.allows(grid, tree.find(id), selector);
        }
    }

    private static Gate gate() {
        AccessibleTree tree = aWindow();
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        return new Gate(tree, new AxGrid(bridge));
    }

    @Test
    void theRowSelectorsAreOfferedByTablesOutlinesAndListsAndByNothingElse() {
        Gate gate = gate();
        for (String selector : new String[] {"accessibilityRows", "accessibilityVisibleRows",
                "accessibilitySelectedRows"}) {
            for (long container : new long[] {1001, 1010, 1020}) {
                assertTrue(gate.allows(container, selector), container + " " + selector);
            }
            for (long other : new long[] {1002, 1003, 1011, 1021, 1030}) {
                assertFalse(gate.allows(other, selector),
                        other + " " + selector + ": a button with an AXRows of nil is noise a reader reads");
            }
        }
    }

    @Test
    void anIndexIsOfferedByRowsOnlyAndACountByTablesOnly() {
        Gate gate = gate();
        for (long row : new long[] {1002, 1011, 1021}) {
            assertTrue(gate.allows(row, "accessibilityIndex"), row + " is a row");
        }
        for (long other : new long[] {1001, 1003, 1010, 1020, 1030}) {
            assertFalse(gate.allows(other, "accessibilityIndex"), other + " is no row: no -1 index");
        }
        assertTrue(gate.allows(1001, "accessibilityRowCount"));
        assertTrue(gate.allows(1001, "accessibilityColumnCount"));
        for (long other : new long[] {1010, 1020, 1011, 1030}) {
            assertFalse(gate.allows(other, "accessibilityRowCount"),
                    other + ": a native outline answers no AXRowCount, and a button's zero is a lie");
            assertFalse(gate.allows(other, "accessibilityColumnCount"), String.valueOf(other));
        }
    }

    /**
     * A header, a header to name, a cell lookup and a cell's ranges only where each has an answer
     * (semantics 3; MACOS-NEW-9): WINDOW > TABLE 1101 > [GROUP 1102 > COLUMN_HEADER 1103 (−1, 0);
     * ROW 1104 > CELL 1105 (0, 0), CELL 1106 (0, 1); GROUP 1107 > CELL 1108 (−2, 1)], beside the
     * headerless table 1001 of {@link #aWindow()}.
     */
    @Test
    void aHeaderACellLookupAndARangeAreOfferedOnlyWhereTheyHaveAnAnswer() {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        int table = open(a, 1101, 0, Accessible.Role.TABLE);
        a.table(1, 2);
        int header = open(a, 1102, table, Accessible.Role.GROUP);
        open(a, 1103, header, Accessible.Role.COLUMN_HEADER);
        a.cell(-1, 0);
        a.end();
        a.end();
        int row = open(a, 1104, table, Accessible.Role.ROW);
        for (int c = 0; c < 2; c++) {
            open(a, 1105 + c, row, Accessible.Role.CELL);
            a.cell(0, c);
            a.end();
        }
        a.end();
        int footer = open(a, 1107, table, Accessible.Role.GROUP);
        open(a, 1108, footer, Accessible.Role.CELL);
        a.cell(-2, 1);
        a.end();
        a.end();
        a.end();
        int bare = open(a, 1001, 0, Accessible.Role.TABLE);
        a.table(0, 1);
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        Gate gate = new Gate(tree, new AxGrid(bridge));

        assertTrue(gate.allows(1101, "accessibilityHeader"));
        assertFalse(gate.allows(1001, "accessibilityHeader"),
                "a table with no header cell answers no AXHeader, as a native headerless table does");
        assertFalse(gate.allows(1104, "accessibilityHeader"), "a row is no table");
        assertTrue(gate.allows(1101, "accessibilityColumnHeaderUIElements"));
        assertTrue(gate.allows(1105, "accessibilityColumnHeaderUIElements"), "column 0 has a header cell");
        assertFalse(gate.allows(1106, "accessibilityColumnHeaderUIElements"),
                "column 1 has none, and the footer's cell in column 1 is not one");
        assertFalse(gate.allows(1108, "accessibilityColumnHeaderUIElements"), "a footer cell is in no data row");
        assertFalse(gate.allows(1001, "accessibilityColumnHeaderUIElements"));
        assertTrue(gate.allows(1101, "accessibilityCellForColumn:row:"));
        assertFalse(gate.allows(1104, "accessibilityCellForColumn:row:"));
        for (String range : new String[] {"accessibilityRowIndexRange", "accessibilityColumnIndexRange"}) {
            assertTrue(gate.allows(1106, range), range + " on a data cell");
            assertFalse(gate.allows(1103, range),
                    range + ": a native table's header button answers neither");
            assertFalse(gate.allows(1108, range), range + " on a footer cell");
            assertFalse(gate.allows(1104, range), range + " on a row");
        }
        assertTrue(gate.allows(1103, "accessibilitySortDirection"),
                "a header cell answers AXSortDirection sorted or not, as a native sort button does");
        for (int other : new int[] {1101, 1104, 1105, 1106, 1108, 1001}) {
            assertFalse(gate.allows(other, "accessibilitySortDirection"),
                    other + ": the native table, its columns, its rows and its cells all answered "
                            + "AXError(-25205) for AXSortDirection");
        }
        for (String selector : new String[] {"accessibilityHeader", "accessibilityColumnHeaderUIElements",
                "accessibilityColumnIndexRange"}) {
            org.junit.jupiter.api.Assumptions.assumeTrue(limn.testing.AllocationProbe.isSupported());
            AccessibleNode cell = tree.find(1106);
            AccessibleNode tableNode = tree.find(1101);
            org.junit.jupiter.api.Assertions.assertEquals(0, limn.testing.AllocationProbe.leastAllocatedBy(
                    () -> AxGate.allows(gate.grid(), cell, selector), 60), selector + " on a cell");
            org.junit.jupiter.api.Assertions.assertEquals(0, limn.testing.AllocationProbe.leastAllocatedBy(
                    () -> AxGate.allows(gate.grid(), tableNode, selector), 60), selector + " on a table");
        }
    }

    /**
     * AppKit asks the gate before nearly every attribute a client reads, and asks it for selectors
     * outside the protocol too (read on the guest, 2026-09-13), so the ask VoiceOver drives hardest
     * allocates nothing for the selectors that are neither rows nor selections.
     */
    @Test
    void aGateAskForAnAttributeAnActionOrASetterAllocatesNothing() {
        org.junit.jupiter.api.Assumptions.assumeTrue(limn.testing.AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        Gate gate = gate();
        AccessibleNode button = gate.tree().find(1030);
        AccessibleNode row = gate.tree().find(1011);
        for (String selector : new String[] {"accessibilityLabel", "accessibilityPerformPress",
                "setAccessibilityFocused:", "setAccessibilityRole:", "isAccessibilityDisclosed",
                "accessibilityIndex"}) {
            Runnable ask = () -> AxGate.allows(gate.grid(), button, selector);
            Runnable askRow = () -> AxGate.allows(gate.grid(), row, selector);
            org.junit.jupiter.api.Assertions.assertEquals(0,
                    limn.testing.AllocationProbe.leastAllocatedBy(ask, 60), selector + " on a button");
            org.junit.jupiter.api.Assertions.assertEquals(0,
                    limn.testing.AllocationProbe.leastAllocatedBy(askRow, 60), selector + " on a row");
        }
    }

    @Test
    void anActionIsOfferedExactlyWhereAVerbIsBehindItAndEveryOtherAttributeEverywhere() {
        Gate gate = gate();
        assertTrue(gate.allows(1030, "accessibilityPerformPress"));
        assertTrue(gate.allows(1040, "accessibilityPerformPress"), "a check box is pressed by toggling");
        assertFalse(gate.allows(1030, "accessibilityPerformIncrement"));
        assertFalse(gate.allows(1003, "accessibilityPerformPress"));
        for (long id : new long[] {1001, 1003, 1030}) {
            assertTrue(gate.allows(id, "accessibilityLabel"), "a name is never hidden");
            assertTrue(gate.allows(id, "accessibilityContainerType"),
                    "a selector outside the ones this bridge answers is AppKit's to answer");
        }
    }
}

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

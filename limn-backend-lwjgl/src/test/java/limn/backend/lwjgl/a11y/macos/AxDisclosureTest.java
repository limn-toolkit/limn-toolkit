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
 * An outline row's disclosure, answered and posted as a native NSOutlineView's is (M1).
 *
 * <p>Every expected value here is what the native outline answered for the same row on the macOS
 * 26.6.2 guest, 2026-09-15 ({@code scripts/a11y/macos/outline-probe.swift}, over Documents open
 * with Reports open under it, Pictures closed, Readme a leaf): AXDisclosing 1, 1, 0, 0, 0, 0;
 * AXDisclosureLevel 0, 1, 2, 1, 0, 0; AXDisclosedByRow none, Documents, Reports, Documents, none,
 * none; AXDisclosedRows [Reports, Notes], [Q1], [], [], [], [].
 */
@ExtendWith(PlatformFreeBridges.class)
class AxDisclosureTest {

    private static final String[] NAMES = {"Documents", "Reports", "Q1", "Notes", "Pictures", "Readme"};
    /** level, flat row, expandable (0 no, 1 closed, 2 open), for each of {@link #NAMES}. */
    private static final int[][] ROWS = {{1, 1, 2}, {2, 2, 2}, {3, 3, 0}, {2, 4, 0}, {1, 5, 1}, {1, 6, 0}};

    /** The outline, realizing only the rows named by {@code realized} (indices into {@link #NAMES}). */
    private static AccessibleTree anOutline(int... realized) {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        int tree = a.begin(1001, 0, Locale.ENGLISH, 0, 0, 200, 300);
        a.role(Accessible.Role.TREE);
        a.name(I18nString.literal("Files"), Accessible.NameFrom.EXPLICIT);
        a.selection(false, false);
        a.inherited(true, true, true, true, false);
        for (int r : realized) {
            a.begin(1010 + r, tree, Locale.ENGLISH, 0, 24L * r, 200, 24);
            a.role(Accessible.Role.TREE_ITEM);
            a.name(I18nString.literal(NAMES[r]), Accessible.NameFrom.CONTENT);
            a.selectionItem(false, 1, 1);
            a.hierarchy(ROWS[r][0], ROWS[r][1], 6);
            if (ROWS[r][2] != 0) a.expand(ROWS[r][2] == 2);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        a.begin(1020, 0, Locale.ENGLISH, 220, 0, 100, 24);
        a.role(Accessible.Role.COMBO_BOX);
        a.name(I18nString.literal("Kind"), Accessible.NameFrom.EXPLICIT);
        a.expand(true);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    private record Fixture(AxBridge bridge, AccessibleTree tree, AxGrid grid) {
        AccessibleNode row(int r) {
            return tree.find(1010 + r);
        }

        long element(int r) {
            return bridge.elementFor(1010 + r);
        }
    }

    private static Fixture over(AccessibleTree tree) {
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        return new Fixture(bridge, tree, new AxGrid(bridge));
    }

    @Test
    void everyRowAnswersWhatTheNativeOutlineAnsweredForIt() {
        Fixture f = over(anOutline(0, 1, 2, 3, 4, 5));
        boolean[] disclosing = {true, true, false, false, false, false};
        long[] level = {0, 1, 2, 1, 0, 0};
        int[] byRow = {-1, 0, 1, 0, -1, -1};
        int[][] disclosed = {{1, 3}, {2}, {}, {}, {}, {}};
        for (int r = 0; r < NAMES.length; r++) {
            AccessibleNode row = f.row(r);
            assertEquals(disclosing[r], f.grid().disclosed(row), NAMES[r] + " AXDisclosing");
            assertEquals(level[r], f.grid().disclosureLevel(row),
                    NAMES[r] + " AXDisclosureLevel, zero-based as the native outline's");
            assertEquals(byRow[r] < 0 ? 0 : f.element(byRow[r]), f.grid().disclosedByRow(row),
                    NAMES[r] + " AXDisclosedByRow");
            long[] expected = new long[disclosed[r].length];
            for (int i = 0; i < expected.length; i++) expected[i] = f.element(disclosed[r][i]);
            assertArrayEquals(expected, f.grid().disclosedRows(row), NAMES[r] + " AXDisclosedRows");
        }
    }

    @Test
    void aParentThatWasNeverRealizedIsNoneAndNeverAGrandparent() {
        // Q1, Notes, Pictures and Readme realized (a scrolled outline); Documents and Reports are not.
        Fixture f = over(anOutline(2, 3, 4, 5));
        assertEquals(0, f.grid().disclosedByRow(f.row(2)), "Q1's parent, Reports, is not in the snapshot");
        assertEquals(0, f.grid().disclosedByRow(f.row(3)), "nor is Notes' parent, Documents");
        assertEquals(2, f.grid().disclosureLevel(f.row(2)), "while its own level is still its own");

        // Documents and Notes realized, Reports and Q1 between them not: Notes walks up into a gap.
        Fixture gap = over(anOutline(0, 3));
        assertEquals(0, gap.grid().disclosedByRow(gap.row(3)),
                "a gap above says nothing: the row in it could have been a root of Notes' own");
        assertArrayEquals(new long[0], gap.grid().disclosedRows(gap.row(0)),
                "and a gap below ends the rows an open row is known to have disclosed");
    }

    @Test
    void theDisclosureAttributesAreAnOutlineRowsAndExpandedIsEveryoneElses() {
        Fixture f = over(anOutline(0, 1, 2, 3, 4, 5));
        AccessibleNode combo = f.tree().find(1020);
        for (String selector : new String[] {"isAccessibilityDisclosed", "accessibilityDisclosureLevel",
                "accessibilityDisclosedByRow", "accessibilityDisclosedRows"}) {
            assertTrue(AxGate.allows(f.grid(), f.row(2), selector), "a leaf row lists " + selector + " too");
            assertFalse(AxGate.allows(f.grid(), combo, selector), "a combo box is no outline row: " + selector);
            assertFalse(AxGate.allows(f.grid(), f.tree().find(1001), selector), "nor the outline: " + selector);
        }
        assertFalse(AxGate.allows(f.grid(), f.row(0), "isAccessibilityExpanded"),
                "a native outline row answers no AXExpanded, only AXDisclosing");
        assertTrue(AxGate.allows(f.grid(), combo, "isAccessibilityExpanded"));
        assertFalse(AxGate.allows(f.grid(), f.tree().find(1001), "isAccessibilityExpanded"),
                "and a node with no expand facet has nothing to say");
        assertNull(f.grid().disclosedRows(combo));
    }

    @Test
    void aLevelOfZeroPublishesNoDisclosureLevel() {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int tree = a.begin(1001, 0, Locale.ENGLISH, 0, 0, 200, 300);
        a.role(Accessible.Role.TREE);
        a.selection(false, false);
        a.inherited(true, true, true, false, false);
        a.begin(1002, tree, Locale.ENGLISH, 0, 0, 200, 24);
        a.role(Accessible.Role.TREE_ITEM);
        a.selectionItem(false, 1, 1);
        a.hierarchy(0, 3, 9);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.end();
        Fixture f = over(a.publish(0, 0, 0, 1f, true));
        AccessibleNode row = f.tree().find(1002);
        assertFalse(AxGate.allows(f.grid(), row, "accessibilityDisclosureLevel"),
                "semantics 6: an unknown level is refused, never answered as a root's zero");
        assertTrue(AxGate.allows(f.grid(), row, "isAccessibilityDisclosed"));
    }

    @Test
    void anOutlineRowOpeningIsARowExpandedOnTheRowAndARowCountChangeOnTheOutline() {
        Fixture f = over(anOutline(0, 1, 2, 3, 4, 5));
        List<String> trace = new ArrayList<>();
        f.bridge().trace(trace::add);
        f.grid().rows(f.tree().find(1001));   // a client walked the rows: every row is held
        f.bridge().emit(AccessibleEvent.state(1014, Accessible.State.EXPANDED, true));
        f.bridge().emit(AccessibleEvent.state(1011, Accessible.State.EXPANDED, false));
        f.bridge().emit(AccessibleEvent.state(1020, Accessible.State.EXPANDED, false));
        f.bridge().frameEnded();
        List<String> posted = trace.stream().filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
        assertEquals(List.of("NSAccessibilityRowExpandedNotification",
                        "NSAccessibilityRowCollapsedNotification",
                        "NSAccessibilityRowCountChangedNotification"), posted,
                "each row on itself and the outline's count once for the frame; a combo box's open "
                        + "state is no value change since 2026-09-23, because AXValue does not carry "
                        + "it and VoiceOver read the post as typed text: " + trace);
    }
}

package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.Shape;
import limn.accessibility.ToggleFacet;
import limn.graphics.Rect;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One node per shape, built with the builder alone and no widget, and what the macOS bridge
 * offers for it (ADR 045 §5; decision 101, 2026-09-22): the shape it classifies as, and the
 * selectors {@link AxGate#allows} lets AppKit send its element, read over an {@link AxGrid}
 * built on the platform-free bridge as {@link AxColumnsTest} builds one. The gate is the
 * bridge's translation of each shape, so a widget of a known shape needs no test of its own
 * here, and a new shape shows up as a row this test does not have. The macOS adapter was
 * named and not split (§5): what is pinned is the gate, which is where the shape is read.
 */
@ExtendWith(PlatformFreeBridges.class)
class AxShapesTest {

    private AxBridge bridge;
    private AxGrid grid;

    @Test
    void aRowsContainerAnswersItsRowsAndAMemberItsIndex() {
        // A row is a row of its container: the bridge reads the membership through the
        // container, so the member is published under one, as a widget publishes it.
        AccessibleTree tree = publishList();
        AccessibleNode list = tree.node(tree.indexOf(1001));
        assertEquals(Shape.ROWS, Shape.of(list));
        assertTrue(allows(list, "accessibilityRows"), "a container whose items are rows");
        assertTrue(allows(list, "accessibilitySelectedRows"));
        assertFalse(allows(list, "accessibilityRowCount"), "and no count without a table facet");
        AccessibleNode row = tree.node(tree.indexOf(1002));
        assertEquals(Shape.ROWS, Shape.of(row));
        assertTrue(allows(row, "accessibilityIndex"), "a row answers AXIndex");
        assertTrue(settable(row, AxSetters.SELECTED), "and takes a selection write");
        assertFalse(settable(row, AxSetters.FOCUSED), "never a focus write: the view takes focus");
        assertFalse(allows(row, "isAccessibilityDisclosed"), "a flat row has no disclosure");
    }

    @Test
    void aGridAnswersItsCountsAndColumnsAndItsCellsTheirRanges() {
        AccessibleNode table = publish(Accessible.Role.TABLE, a -> {
            a.table(10, 4);
            a.selection(false, false);
        });
        assertEquals(Shape.GRID, Shape.of(table));
        assertTrue(allows(table, "accessibilityRowCount"));
        assertTrue(allows(table, "accessibilityColumnCount"));
        assertTrue(allows(table, "accessibilityColumns"), "columns standing for no node (M4)");
        assertTrue(allows(table, "accessibilityCellForColumn:row:"));
        AccessibleNode cell = publish(Accessible.Role.CELL, a -> a.cell(2, 1));
        assertEquals(Shape.GRID, Shape.of(cell));
        assertTrue(allows(cell, "accessibilityRowIndexRange"), "a data cell answers its ranges");
        assertTrue(allows(cell, "accessibilityColumnIndexRange"));
        assertFalse(allows(cell, "accessibilitySortDirection"), "and no sort: that is a header's");
        AccessibleNode header = publish(Accessible.Role.COLUMN_HEADER, a -> a.cell(-1, 1));
        assertEquals(Shape.GRID, Shape.of(header));
        assertTrue(allows(header, "accessibilitySortDirection"), "a header answers its direction");
        assertFalse(allows(header, "accessibilityRowIndexRange"), "and no range: it is in no data row");
    }

    @Test
    void aValueTakesAValueWriteAndAStepWhereItPublishesOne() {
        AccessibleNode slider = publish(Accessible.Role.SLIDER, a -> {
            a.value(40, 0, 100, 5);
            a.action(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
        });
        assertEquals(Shape.VALUE, Shape.of(slider));
        assertTrue(settable(slider, AxSetters.VALUE), "a writable value takes a write: the facet "
                + "implies SET_VALUE, which is never in an action list");
        assertTrue(allows(slider, "accessibilityPerformIncrement"));
        assertTrue(allows(slider, "accessibilityPerformDecrement"));
        assertFalse(allows(slider, "accessibilityPerformPress"), "and no press: nothing activates");
        AccessibleNode progress = publish(Accessible.Role.PROGRESS_BAR,
                a -> a.value(40, 0, 100, 0, true));
        assertEquals(Shape.VALUE, Shape.of(progress));
        assertFalse(settable(progress, AxSetters.VALUE), "a read-only value takes no write");
        assertFalse(allows(progress, "accessibilityPerformIncrement"), "and no step");
    }

    @Test
    void aTextTakesAValueWriteOnlyWhenEditable() {
        AccessibleNode field = publish(Accessible.Role.TEXT_FIELD, a -> {
            a.text("invoices", 0, 8, ShapedText.Affinity.UPSTREAM, 8, 8, 1,
                    new Rect(40, 2, 1, 12), false);
            a.state(Accessible.State.EDITABLE);
        });
        assertEquals(Shape.TEXT, Shape.of(field));
        assertTrue(settable(field, AxSetters.VALUE), "an editable text takes a value write: the "
                + "facet implies SET_TEXT, which is never in an action list");
        AccessibleNode label = publish(Accessible.Role.TEXT_FIELD, a -> a.text("invoices",
                0, 8, ShapedText.Affinity.UPSTREAM, 8, 8, 1, new Rect(40, 2, 1, 12), true));
        assertEquals(Shape.TEXT, Shape.of(label));
        assertFalse(settable(label, AxSetters.VALUE), "a read-only text takes none");
    }

    @Test
    void aToggleAndALeafArePressedAndAStaticIsNot() {
        AccessibleNode box = publish(Accessible.Role.CHECK_BOX, a -> {
            a.toggle(ToggleFacet.State.OFF);
            a.action(Accessible.Action.TOGGLE);
        });
        assertEquals(Shape.TOGGLE, Shape.of(box));
        assertTrue(allows(box, "accessibilityPerformPress"), "a press toggles: semantics 5");
        AccessibleNode button = publish(Accessible.Role.BUTTON, a -> a.action(Accessible.Action.PRESS));
        assertEquals(Shape.LEAF_ACTION, Shape.of(button));
        assertTrue(allows(button, "accessibilityPerformPress"));
        assertTrue(allows(button, "accessibilityPerformConfirm"), "and Return is the same activation");
        AccessibleNode label = publish(Accessible.Role.LABEL, a -> { });
        assertEquals(Shape.STATIC, Shape.of(label));
        assertFalse(allows(label, "accessibilityPerformPress"), "nothing to press");
        assertFalse(settable(label, AxSetters.VALUE), "nothing to write");
    }

    @Test
    void aPopupOwnerAnswersExpandedAndTakesAnExpandWriteWhileItOffersTheVerb() {
        AccessibleNode combo = publish(Accessible.Role.COMBO_BOX, a -> {
            a.state(Accessible.State.HAS_POPUP);
            a.expand(false);
            a.action(Accessible.Action.EXPAND);
        });
        assertEquals(Shape.POPUP_OWNER, Shape.of(combo));
        assertTrue(allows(combo, "isAccessibilityExpanded"), "an expand facet off an outline row");
        assertTrue(settable(combo, AxSetters.EXPANDED), "and an expand write while it opens");
        assertFalse(settable(combo, AxSetters.DISCLOSED), "disclosure is an outline row's alone");
        assertTrue(allows(combo, "accessibilityPerformPress"), "a press opens what is closed");
    }

    @Test
    void aMenuRowAnswersExpandedAndTakesShowMenuAndThisBridgeDeclaresNoMenuException() {
        AccessibleNode title = publish(Accessible.Role.MENU_ITEM, a -> {
            a.containerlessSelectionItem(false, 1, 2);
            a.expand(false);
            a.state(Accessible.State.HAS_POPUP);
            a.action(Accessible.Action.EXPAND, Accessible.Action.SHOW_MENU);
        });
        assertEquals(Shape.MENU, Shape.of(title));
        assertTrue(allows(title, "isAccessibilityExpanded"), "the axis stays on the bus here");
        assertTrue(allows(title, "accessibilityPerformShowMenu"));
        assertTrue(allows(title, "accessibilityPerformPress"), "and a press opens it, as a native title's does");
    }

    @Test
    void aScrollingSurfaceAnswersEveryPlainAttributeAndNoRowOrValueOne() {
        AccessibleNode pane = publish(Accessible.Role.SCROLL_PANE,
                a -> a.scrollFrom(0, 0, 1, 0, 40, 200, 100, 300));
        assertEquals(Shape.SURFACE, Shape.of(pane));
        assertTrue(allows(pane, "accessibilityLabel"), "a plain attribute every node answers");
        assertFalse(allows(pane, "accessibilityRows"), "no rows: nothing selects");
        assertFalse(allows(pane, "accessibilityRowCount"));
        assertFalse(settable(pane, AxSetters.VALUE));
        assertFalse(allows(pane, "accessibilityPerformPress"));
    }

    private boolean allows(AccessibleNode node, String selector) {
        return AxGate.allows(grid, node, selector);
    }

    private boolean settable(AccessibleNode node, String setter) {
        return AxGate.settable(grid, node, setter);
    }

    /** A list of three rows, the first selected, published through the bridge. */
    private AccessibleTree publishList() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int list = a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 90);
        a.role(Accessible.Role.LIST);
        a.name(I18nString.literal("A list"), Accessible.NameFrom.CONTENT);
        a.selection(false, false);
        a.inherited(true, true, true, true, false);
        for (int i = 0; i < 3; i++) {
            a.begin(1002 + i, list, Locale.ENGLISH, 10, 20 + 30 * i, 160, 30);
            a.role(Accessible.Role.LIST_ITEM);
            a.name(I18nString.literal("Row " + i), Accessible.NameFrom.CONTENT);
            a.selectionItem(i == 0, i + 1, 3);
            a.action(Accessible.Action.SELECT);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        grid = new AxGrid(bridge);
        return tree;
    }

    private AccessibleNode publish(Accessible.Role role, Consumer<Accessibility> facets) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(role);
        a.name(I18nString.literal("A control"), Accessible.NameFrom.CONTENT);
        facets.accept(a);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        grid = new AxGrid(bridge);
        return tree.node(tree.indexOf(1001));
    }
}

package limn.backend.lwjgl.a11y.windows;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One node per shape, built with the builder alone and no widget, and what the Windows bridge
 * vends for it (ADR 045 §5): the shape it classifies as, and the patterns {@link UiaPatterns}
 * offers, which are exactly the ones the shape's class serves. The tables pinned here are the
 * bridge's translation of each shape, so a widget of a known shape needs no test of its own here
 * and a new shape shows up as a row this test does not have.
 */
class UiaShapesTest {

    private static final int[] EVERY = {UiaIds.INVOKE_PATTERN, UiaIds.TOGGLE_PATTERN,
            UiaIds.VALUE_PATTERN, UiaIds.RANGE_VALUE_PATTERN, UiaIds.SELECTION_PATTERN,
            UiaIds.EXPAND_COLLAPSE_PATTERN, UiaIds.SCROLL_PATTERN, UiaIds.SELECTION_ITEM_PATTERN,
            UiaIds.SCROLL_ITEM_PATTERN, UiaIds.GRID_PATTERN, UiaIds.GRID_ITEM_PATTERN,
            UiaIds.TABLE_PATTERN, UiaIds.TABLE_ITEM_PATTERN};

    private AccessibleTree tree;

    @Test
    void aRowsContainerVendsSelectionAndItsMemberTheItemPatterns() {
        AccessibleNode list = publish(Accessible.Role.LIST, a -> a.selection(false, false));
        assertEquals(Shape.ROWS, Shape.of(list));
        assertEquals(List.of(UiaIds.SELECTION_PATTERN), patternsOf(list));
        AccessibleNode row = publish(Accessible.Role.LIST_ITEM, a -> {
            a.containerlessSelectionItem(false, 1, 3);
            a.action(Accessible.Action.SELECT, Accessible.Action.SCROLL_INTO_VIEW);
        });
        assertEquals(Shape.ROWS, Shape.of(row));
        assertEquals(List.of(UiaIds.SELECTION_ITEM_PATTERN, UiaIds.SCROLL_ITEM_PATTERN),
                patternsOf(row));
    }

    @Test
    void aGridVendsGridAndTableAndOnlyItsDataCellsTheItemPatterns() {
        AccessibleNode table = publish(Accessible.Role.TABLE, a -> {
            a.table(10, 4);
            a.selection(false, false);
        });
        assertEquals(Shape.GRID, Shape.of(table));
        assertEquals(List.of(UiaIds.SELECTION_PATTERN, UiaIds.GRID_PATTERN, UiaIds.TABLE_PATTERN),
                patternsOf(table));
        AccessibleNode cell = publish(Accessible.Role.CELL, a -> a.cell(2, 1));
        assertEquals(Shape.GRID, Shape.of(cell));
        assertEquals(List.of(UiaIds.GRID_ITEM_PATTERN, UiaIds.TABLE_ITEM_PATTERN), patternsOf(cell));
        AccessibleNode header = publish(Accessible.Role.COLUMN_HEADER, a -> a.cell(-1, 1));
        assertEquals(Shape.GRID, Shape.of(header));
        assertEquals(List.of(), patternsOf(header), "a header is not in the grid a client counts");
    }

    @Test
    void aValueVendsRangeValueAndOneWithASpokenFormValueToo() {
        AccessibleNode slider = publish(Accessible.Role.SLIDER, a -> {
            a.value(40, 0, 100, 5);
            a.action(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
        });
        assertEquals(Shape.VALUE, Shape.of(slider));
        assertEquals(List.of(UiaIds.RANGE_VALUE_PATTERN), patternsOf(slider));
        AccessibleNode spinner = publish(Accessible.Role.SPIN_BUTTON, a -> {
            a.value(450, 0, 1439, 60);
            a.valueText("07:30", 1);
            a.action(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
        });
        assertEquals(Shape.VALUE, Shape.of(spinner));
        assertEquals(List.of(UiaIds.VALUE_PATTERN, UiaIds.RANGE_VALUE_PATTERN), patternsOf(spinner));
    }

    @Test
    void aTextVendsValue() {
        AccessibleNode field = publish(Accessible.Role.TEXT_FIELD, a -> a.text("invoices", 0, 8,
                ShapedText.Affinity.UPSTREAM, 8, 8, 1, new Rect(40, 2, 1, 12), false));
        assertEquals(Shape.TEXT, Shape.of(field));
        assertEquals(List.of(UiaIds.VALUE_PATTERN), patternsOf(field));
    }

    @Test
    void aToggleVendsToggle() {
        AccessibleNode box = publish(Accessible.Role.CHECK_BOX, a -> {
            a.toggle(ToggleFacet.State.OFF);
            a.action(Accessible.Action.TOGGLE);
        });
        assertEquals(Shape.TOGGLE, Shape.of(box));
        assertEquals(List.of(UiaIds.TOGGLE_PATTERN), patternsOf(box));
    }

    @Test
    void aPopupOwnerVendsExpandCollapse() {
        AccessibleNode combo = publish(Accessible.Role.COMBO_BOX, a -> {
            a.state(Accessible.State.HAS_POPUP);
            a.expand(false);
            a.action(Accessible.Action.EXPAND);
        });
        assertEquals(Shape.POPUP_OWNER, Shape.of(combo));
        assertEquals(List.of(UiaIds.EXPAND_COLLAPSE_PATTERN), patternsOf(combo));
    }

    @Test
    void aLeafVendsInvokeAndAStaticNothing() {
        AccessibleNode button = publish(Accessible.Role.BUTTON, a -> a.action(Accessible.Action.PRESS));
        assertEquals(Shape.LEAF_ACTION, Shape.of(button));
        assertEquals(List.of(UiaIds.INVOKE_PATTERN), patternsOf(button));
        AccessibleNode label = publish(Accessible.Role.LABEL, a -> { });
        assertEquals(Shape.STATIC, Shape.of(label));
        assertEquals(List.of(), patternsOf(label));
    }

    @Test
    void aScrollingSurfaceVendsScroll() {
        AccessibleNode pane = publish(Accessible.Role.SCROLL_PANE,
                a -> a.scrollFrom(0, 0, 1, 0, 40, 200, 100, 300));
        assertEquals(Shape.SURFACE, Shape.of(pane));
        assertEquals(List.of(UiaIds.SCROLL_PATTERN), patternsOf(pane));
    }

    @Test
    void aMenuRowVendsWhatItsFacetsSayBecauseThisBridgeDeclaresNoMenuException() {
        AccessibleNode title = publish(Accessible.Role.MENU_ITEM, a -> {
            a.containerlessSelectionItem(false, 1, 2);
            a.expand(false);
            a.state(Accessible.State.HAS_POPUP);
            a.action(Accessible.Action.EXPAND, Accessible.Action.SHOW_MENU);
        });
        assertEquals(Shape.MENU, Shape.of(title));
        assertEquals(List.of(UiaIds.EXPAND_COLLAPSE_PATTERN, UiaIds.SELECTION_ITEM_PATTERN),
                patternsOf(title));
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
        tree = a.publish(0, 0, 0, 1f, true);
        return tree.node(tree.indexOf(1001));
    }

    private List<Integer> patternsOf(AccessibleNode node) {
        List<Integer> vended = new ArrayList<>();
        for (int id : EVERY) {
            if (UiaPatterns.supports(tree, node, id)) {
                vended.add(id);
            }
        }
        return vended;
    }
}

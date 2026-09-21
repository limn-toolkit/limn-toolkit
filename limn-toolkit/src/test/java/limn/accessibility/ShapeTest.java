package limn.accessibility;

import limn.graphics.Rect;
import limn.graphics.ShapedText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Shape#of} over nodes built with nothing but the builder: the classification is total
 * over the closed role enum, each shape is claimed by its facet or by the role family that implies
 * it, and where a node carries the facts of two shapes the earlier one in the declared order
 * wins. Each of the tie-breaks ADR 045 §1 names is a case here, with the node that made it
 * necessary in the gallery.
 */
class ShapeTest {

    @Test
    void theOrderOfTheConstantsIsTheRule() {
        assertEquals(List.of(Shape.MENU, Shape.GRID, Shape.ROWS, Shape.POPUP_OWNER, Shape.TOGGLE,
                        Shape.VALUE, Shape.TEXT, Shape.LEAF_ACTION, Shape.STATIC, Shape.SURFACE,
                        Shape.UNCLASSIFIED),
                List.of(Shape.values()),
                "the precedence is the declaration order, and ADR 045 §1 lists it; move both");
    }

    /** Every role, published bare, lands in the shape its family implies; only UNKNOWN in none. */
    @Test
    void aBareRoleClassifiesByItsFamily() {
        List<String> wrong = new ArrayList<>();
        for (Accessible.Role role : Accessible.Role.values()) {
            Shape expected = switch (role) {
                case MENU_BAR, MENU, MENU_ITEM, CHECK_MENU_ITEM, RADIO_MENU_ITEM -> Shape.MENU;
                case TABLE, COLUMN_HEADER, CELL, ROW -> Shape.GRID;
                case LIST, TREE, TAB_LIST, RADIO_GROUP, LIST_ITEM, TREE_ITEM, TAB, RADIO_BUTTON ->
                        Shape.ROWS;
                case COMBO_BOX -> Shape.POPUP_OWNER;
                case CHECK_BOX, SWITCH, TOGGLE_BUTTON, CHART_SERIES -> Shape.TOGGLE;
                case SLIDER, SPIN_BUTTON, PROGRESS_BAR, SCROLL_BAR, SPLITTER -> Shape.VALUE;
                case TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD, SEARCH_FIELD -> Shape.TEXT;
                case BUTTON -> Shape.LEAF_ACTION;
                case LABEL, HEADING, ALERT -> Shape.STATIC;
                case UNKNOWN -> Shape.UNCLASSIFIED;
                case WINDOW, DIALOG, GROUP, SCROLL_PANE, SPLIT_PANE, TOOL_BAR, SEPARATOR, IMAGE,
                     VIDEO, CANVAS, CHART, TAB_PANEL, COLOR_CHOOSER -> Shape.SURFACE;
            };
            Shape actual = Shape.of(node(a -> a.role(role)));
            if (actual != expected) {
                wrong.add(role + ": expected " + expected + ", was " + actual);
            }
        }
        assertTrue(wrong.isEmpty(), "bare roles landed in the wrong shape:\n  "
                + String.join("\n  ", wrong));
    }

    @Test
    void theMenuFamilyDecidesBeforeAnyFacet() {
        assertEquals(Shape.MENU, Shape.of(node(a -> {
            a.role(Accessible.Role.CHECK_MENU_ITEM);
            a.toggle(ToggleFacet.State.ON);
            a.action(Accessible.Action.TOGGLE);
        })), "a check menu item carries a toggle and is still a menu row");
        assertEquals(Shape.MENU, Shape.of(node(a -> {
            a.role(Accessible.Role.MENU_ITEM);
            a.containerlessSelectionItem(false, 1, 2);
            a.expand(false);
            a.state(Accessible.State.HAS_POPUP);
            a.action(Accessible.Action.EXPAND, Accessible.Action.SHOW_MENU);
        })), "a menu title carries a selection membership and a popup and is still a menu row");
    }

    @Test
    void aSelectableRowIsARowsMemberAndAWeekRowIsAGridRow() {
        AccessibleNode tableRow = member(a -> {
            a.role(Accessible.Role.TABLE);
            a.table(10, 4);
            a.selection(true, false);
        }, a -> {
            a.role(Accessible.Role.ROW);
            a.selectionItem(true, 1, 10);
            a.action(Accessible.Action.PRESS, Accessible.Action.SELECT);
        });
        assertEquals(Shape.ROWS, Shape.of(tableRow), "a table's row is a member of its selection");
        AccessibleNode weekRow = member(a -> {
            a.role(Accessible.Role.TABLE);
            a.table(6, 7);
            a.selection(false, true);
        }, a -> a.role(Accessible.Role.ROW));
        assertEquals(Shape.GRID, Shape.of(weekRow), "a calendar's week row is in no selection");
    }

    @Test
    void aWidgetHungUnderARowKeepsItsShapeAndACellIsAGridsOwn() {
        assertEquals(Shape.TOGGLE, Shape.of(node(a -> {
            a.role(Accessible.Role.SWITCH);
            a.toggle(ToggleFacet.State.ON);
            a.cell(3, 3);
            a.action(Accessible.Action.TOGGLE);
        })), "the cell facet a table writes onto a switch is its position, not its shape");
        assertEquals(Shape.GRID, Shape.of(node(a -> {
            a.role(Accessible.Role.CELL);
            a.cell(0, 0);
        })));
        assertEquals(Shape.GRID, Shape.of(node(a -> {
            a.role(Accessible.Role.COLUMN_HEADER);
            a.cell(-1, 0);
            a.action(Accessible.Action.PRESS);
        })), "a header sorts on press and is still a grid's");
        assertEquals(Shape.GRID, Shape.of(node(a -> {
            a.role(Accessible.Role.GROUP);
            a.table(2, 2);
        })), "a table facet makes a grid of whatever carries it");
    }

    @Test
    void thePopupIsDecidedAheadOfTheValueAndTheLeaf() {
        assertEquals(Shape.POPUP_OWNER, Shape.of(node(a -> {
            a.role(Accessible.Role.COMBO_BOX);
            a.value(1, 0, 3, 1);
            a.expand(false);
            a.state(Accessible.State.HAS_POPUP);
            a.action(Accessible.Action.EXPAND);
        })), "a combo box carries its index as a value and owns its list");
        assertEquals(Shape.POPUP_OWNER, Shape.of(node(a -> {
            a.role(Accessible.Role.GROUP);
            a.state(Accessible.State.HAS_POPUP);
            a.action(Accessible.Action.SHOW_MENU);
        })), "a context region");
        assertEquals(Shape.POPUP_OWNER, Shape.of(node(a -> {
            a.role(Accessible.Role.BUTTON);
            a.expand(false);
            a.action(Accessible.Action.PRESS, Accessible.Action.EXPAND);
        })), "a calendar's month title discloses its chooser in place");
        assertEquals(Shape.POPUP_OWNER, Shape.of(node(a -> {
            a.role(Accessible.Role.BUTTON);
            a.state(Accessible.State.HAS_POPUP);
            a.action(Accessible.Action.PRESS);
        })), "a colour picker button");
    }

    @Test
    void aTreeItemIsARowAheadOfItsDisclosure() {
        AccessibleNode item = member(a -> {
            a.role(Accessible.Role.TREE);
            a.selection(false, false);
        }, a -> {
            a.role(Accessible.Role.TREE_ITEM);
            a.selectionItem(false, 1, 3);
            a.expand(true);
            a.hierarchy(1, 1, 5);
            a.action(Accessible.Action.PRESS, Accessible.Action.COLLAPSE, Accessible.Action.SELECT);
        });
        assertEquals(Shape.ROWS, Shape.of(item));
    }

    @Test
    void aSearchFieldIsATextAheadOfItsPress() {
        assertEquals(Shape.TEXT, Shape.of(node(a -> {
            a.role(Accessible.Role.SEARCH_FIELD);
            a.text("invoices", 0, 8, ShapedText.Affinity.UPSTREAM, 8, 8, 1,
                    new Rect(40, 2, 1, 12), false);
            a.action(Accessible.Action.PRESS, Accessible.Action.SHOW_MENU);
        })));
    }

    @Test
    void aShapeDoesNotChangeWithState() {
        assertEquals(Shape.LEAF_ACTION, Shape.of(node(a -> a.role(Accessible.Role.BUTTON))),
                "a disabled button's verbs are withdrawn by the walk and it is still a button");
        assertEquals(Shape.LEAF_ACTION, Shape.of(node(a -> {
            a.role(Accessible.Role.BUTTON);
            a.action(Accessible.Action.PRESS, Accessible.Action.FOCUS,
                    Accessible.Action.SCROLL_INTO_VIEW);
        })), "the walk's free verbs beside the press change nothing");
        assertEquals(Shape.VALUE, Shape.of(node(a -> {
            a.role(Accessible.Role.PROGRESS_BAR);
            a.state(Accessible.State.BUSY);
        })), "an indeterminate progress bar has no value and is still a value");
        assertEquals(Shape.ROWS, Shape.of(node(a -> {
            a.role(Accessible.Role.RADIO_BUTTON);
            a.containerlessSelectionItem(true, 1, 3);
            a.action(Accessible.Action.SELECT);
        })), "a radio button is a member of its group's selection, container or not");
    }

    @Test
    void aPressableNodeOfNoOtherShapeIsALeaf() {
        assertEquals(Shape.LEAF_ACTION, Shape.of(node(a -> {
            a.role(Accessible.Role.GROUP);
            a.action(Accessible.Action.PRESS);
        })));
        assertEquals(Shape.SURFACE, Shape.of(node(a -> {
            a.role(Accessible.Role.GROUP);
            a.action(Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW);
        })), "the free verbs alone make no leaf");
    }

    // ------------------------------------------------------------------------------ building

    /** One root node described by {@code describe}, published and returned. */
    private static AccessibleNode node(Consumer<Accessibility> describe) {
        Accessibility a = new Accessibility();
        long owner = a.mint();
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        describe.accept(a);
        a.end();
        a.resolveRelations((kind, target) -> 0);
        return a.publish(0, 0, 0, 1, true).root();
    }

    /** A root described by {@code container} with one synthetic child described by {@code child}. */
    private static AccessibleNode member(Consumer<Accessibility> container,
                                         Consumer<Accessibility> child) {
        Accessibility a = new Accessibility();
        long owner = a.mint();
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        container.accept(a);
        a.child(1);
        child.accept(a);
        a.bounds(0, 0, 100, 20);
        a.endChild();
        a.end();
        a.resolveRelations((kind, target) -> 0);
        return a.publish(0, 0, 0, 1, true).node(1);
    }
}

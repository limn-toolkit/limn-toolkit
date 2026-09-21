package limn.backend.lwjgl.a11y.linux;

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

import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One node per shape, built with the builder alone and no widget, and what the AT-SPI bridge
 * serves for it (ADR 045 §5): the shape it classifies as and the interfaces it serves, as the
 * bits {@link AtspiTree#interfaceBitsOf} compares; and the one declared exception, the menu row
 * whose expand axis and expand verb never reach the bus ({@link AtspiMenuShape}).
 */
class AtspiShapesTest {

    private static final int ACTION = 1;
    private static final int TABLE = 1 << 1;
    private static final int CELL = 1 << 2;
    private static final int SELECTION = 1 << 3;
    private static final int VALUE = 1 << 4;
    private static final int TEXT = 1 << 5;
    private static final int EDITABLE_TEXT = 1 << 6;

    @Test
    void eachShapeServesTheInterfacesItsFacetsImply() {
        assertEquals(SELECTION, bitsOf(Accessible.Role.LIST, a -> a.selection(false, false)));
        assertEquals(ACTION, bitsOf(Accessible.Role.LIST_ITEM, a -> {
            a.containerlessSelectionItem(false, 1, 3);
            a.action(Accessible.Action.SELECT);
        }), "a member serves its verbs; its membership is the container's Selection");
        assertEquals(TABLE | SELECTION, bitsOf(Accessible.Role.TABLE, a -> {
            a.table(10, 4);
            a.selection(false, false);
        }));
        assertEquals(CELL, bitsOf(Accessible.Role.CELL, a -> a.cell(2, 1)));
        assertEquals(ACTION | VALUE, bitsOf(Accessible.Role.SLIDER, a -> {
            a.value(40, 0, 100, 5);
            a.action(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
        }));
        assertEquals(VALUE, bitsOf(Accessible.Role.PROGRESS_BAR, a -> a.value(40, 0, 100, 0, true)));
        assertEquals(TEXT, bitsOf(Accessible.Role.TEXT_FIELD, a -> a.text("invoices",
                0, 8, ShapedText.Affinity.UPSTREAM, 8, 8, 1, new Rect(40, 2, 1, 12), false)),
                "a text serves Text; EditableText needs the EDITABLE state the walk derives");
        assertEquals(TEXT | EDITABLE_TEXT, bitsOf(Accessible.Role.TEXT_FIELD, a -> {
            a.text("invoices", 0, 8, ShapedText.Affinity.UPSTREAM, 8, 8, 1,
                    new Rect(40, 2, 1, 12), false);
            a.state(Accessible.State.EDITABLE);
        }));
        assertEquals(ACTION, bitsOf(Accessible.Role.CHECK_BOX, a -> {
            a.toggle(ToggleFacet.State.OFF);
            a.action(Accessible.Action.TOGGLE);
        }));
        assertEquals(ACTION, bitsOf(Accessible.Role.COMBO_BOX, a -> {
            a.state(Accessible.State.HAS_POPUP);
            a.expand(false);
            a.action(Accessible.Action.EXPAND);
        }));
        assertEquals(ACTION, bitsOf(Accessible.Role.BUTTON, a -> a.action(Accessible.Action.PRESS)));
        assertEquals(0, bitsOf(Accessible.Role.LABEL, a -> { }));
        assertEquals(0, bitsOf(Accessible.Role.SCROLL_PANE,
                a -> a.scrollFrom(0, 0, 1, 0, 40, 200, 100, 300)));
    }

    @Test
    void aMenuRowKeepsItsExpandAxisAndItsExpandVerbOffTheBus() {
        AccessibleNode title = publish(Accessible.Role.MENU_ITEM, a -> {
            a.containerlessSelectionItem(false, 1, 2);
            a.expand(false);
            a.state(Accessible.State.HAS_POPUP);
            a.action(Accessible.Action.EXPAND, Accessible.Action.SHOW_MENU);
        });
        assertEquals(Shape.MENU, Shape.of(title));
        assertTrue(AtspiMenuShape.isMenuRow(title));
        assertTrue(title.has(Accessible.State.EXPANDABLE), "the model says so");
        long expandable = AtspiStates.setOf(s -> s == Accessible.State.EXPANDABLE);
        assertEquals(0, AtspiMenuShape.states(title) & expandable, "and the bus never hears it");
        assertTrue(AtspiMenuShape.dropsVerb(title, Accessible.Action.EXPAND));
        assertFalse(AtspiMenuShape.dropsVerb(title, Accessible.Action.SHOW_MENU),
                "the route stays open under the name a menu really uses");
        assertFalse(AtspiMenuShape.dropsVerb(title, Accessible.Action.COLLAPSE),
                "and an open title can still be closed");
        assertTrue(AtspiMenuShape.silencesExpandChange(title));

        AccessibleNode row = publish(Accessible.Role.TREE_ITEM, a -> {
            a.containerlessSelectionItem(false, 1, 2);
            a.expand(false);
            a.hierarchy(1, 1, 2);
            a.action(Accessible.Action.EXPAND);
        });
        assertEquals(Shape.ROWS, Shape.of(row));
        assertFalse(AtspiMenuShape.isMenuRow(row), "a tree row is not a menu and keeps the axis");
        assertFalse(AtspiMenuShape.dropsVerb(row, Accessible.Action.EXPAND));
    }

    private int bitsOf(Accessible.Role role, Consumer<Accessibility> facets) {
        return AtspiTree.interfaceBitsOf(publish(role, facets));
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
        return tree.node(tree.indexOf(1001));
    }
}

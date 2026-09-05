package limn.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which patterns a node vends, over trees built the way a scene builds one.
 *
 * <p>The stakes are higher here than for a property: a client takes silence from
 * {@code GetPatternProvider} as "this control cannot do that", so a facet that fails to become a
 * pattern is a control nobody can operate rather than one announced poorly.
 */
class UiaPatternsTest {

    private AccessibleTree tree;

    /** A window holding one control, whose facets the caller writes. */
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

    private boolean vends(AccessibleNode node, int patternId) {
        return UiaPatterns.supports(tree, node, patternId);
    }

    /** @return every pattern id the node vends, so a case can assert the whole list at once */
    private List<Integer> patternsOf(AccessibleNode node) {
        int[] every = {UiaIds.INVOKE_PATTERN, UiaIds.TOGGLE_PATTERN, UiaIds.RANGE_VALUE_PATTERN,
                UiaIds.VALUE_PATTERN, UiaIds.SELECTION_PATTERN, UiaIds.SELECTION_ITEM_PATTERN,
                UiaIds.EXPAND_COLLAPSE_PATTERN, UiaIds.SCROLL_PATTERN, UiaIds.SCROLL_ITEM_PATTERN,
                UiaIds.WINDOW_PATTERN, UiaIds.TRANSFORM_PATTERN, UiaIds.TEXT_PATTERN,
                UiaIds.GRID_PATTERN};
        List<Integer> vended = new ArrayList<>();
        for (int id : every) {
            if (vends(node, id)) {
                vended.add(id);
            }
        }
        return vended;
    }

    @Test
    void aButtonVendsInvokeAndNothingElse() {
        AccessibleNode button = publish(Accessible.Role.BUTTON,
                a -> a.action(Accessible.Action.PRESS));

        assertEquals(List.of(UiaIds.INVOKE_PATTERN), patternsOf(button));
    }

    /**
     * Invoke is the press and not "has any verb at all". A node offering only EXPAND has an
     * ExpandCollapse pattern, and a client's Invoke() on it would reach a widget that refuses it.
     */
    @Test
    void aNodeWithAVerbThatIsNotAPressDoesNotVendInvoke() {
        AccessibleNode expandable = publish(Accessible.Role.COMBO_BOX, a -> {
            a.action(Accessible.Action.EXPAND);
            a.expand(false);
        });

        assertFalse(vends(expandable, UiaIds.INVOKE_PATTERN));
        assertTrue(vends(expandable, UiaIds.EXPAND_COLLAPSE_PATTERN));
    }

    @Test
    void aCheckboxVendsToggle() {
        AccessibleNode box = publish(Accessible.Role.CHECK_BOX, a -> {
            a.action(Accessible.Action.TOGGLE);
            a.toggle(limn.accessibility.ToggleFacet.State.OFF);
        });

        assertTrue(vends(box, UiaIds.TOGGLE_PATTERN));
    }

    @Test
    void aSliderVendsBothValuePatternsBecauseItHasANumberAndAText() {
        AccessibleNode slider = publish(Accessible.Role.SLIDER,
                a -> a.value(42, 0, 100, 1, false));

        assertTrue(vends(slider, UiaIds.RANGE_VALUE_PATTERN), "a number with bounds");
        assertTrue(vends(slider, UiaIds.VALUE_PATTERN),
                "and the same facet is what a client reads the spoken form from");
    }

    /**
     * The row §2.1 spends a paragraph on. Without the text facet's contribution every text widget
     * in the toolkit vends nothing at all, and a reader finds a named element whose contents it
     * cannot read, cannot set and cannot report as read-only.
     */
    @Test
    void aTextFieldVendsValueEvenThoughItHasNoNumber() {
        AccessibleNode field = publish(Accessible.Role.TEXT_FIELD,
                a -> a.text("hello", 1L, 5, limn.graphics.ShapedText.Affinity.DOWNSTREAM,
                        5, 5, 1, null, false));

        assertTrue(vends(field, UiaIds.VALUE_PATTERN),
                "TextPattern is deferred and a text widget has never had a numeric value, so "
                        + "mapping Value from ValueFacet alone leaves this control with an empty "
                        + "pattern list on the one platform where the list is the behaviour");
        assertFalse(vends(field, UiaIds.RANGE_VALUE_PATTERN),
                "and it is not a range: there is no number to bound");
        assertFalse(vends(field, UiaIds.TEXT_PATTERN), "TextPattern is §11's, not this cut's");
    }

    @Test
    void aListVendsSelectionAndItsRowsVendSelectionItem() {
        AccessibleNode list = publish(Accessible.Role.LIST, a -> a.selection(false, false));
        assertTrue(vends(list, UiaIds.SELECTION_PATTERN));

        AccessibleNode row = publish(Accessible.Role.LIST_ITEM, a -> a.selectionItem(true, 1, 3));
        assertTrue(vends(row, UiaIds.SELECTION_ITEM_PATTERN));
        assertFalse(vends(row, UiaIds.SELECTION_PATTERN));
    }

    @Test
    void aScrollPaneVendsScroll() {
        AccessibleNode pane = publish(Accessible.Role.SCROLL_PANE,
                a -> a.scroll(0, 0.25, 1, 0.5, false, true));

        assertTrue(vends(pane, UiaIds.SCROLL_PATTERN));
    }

    /**
     * The one row that is not about this node: ScrollItem says "I can be scrolled into view", which
     * is a fact about an ancestor.
     */
    @Test
    void aNodeInsideAScrollPaneVendsScrollItemAndOneOutsideDoesNot() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);

        a.begin(2000, 0, Locale.ENGLISH, 0, 0, 200, 300);
        a.role(Accessible.Role.SCROLL_PANE);
        a.scroll(0, 0.25, 1, 0.5, false, true);
        a.inherited(true, true, true, false, false);
        a.begin(2001, 1, Locale.ENGLISH, 0, 0, 200, 40);
        a.role(Accessible.Role.GROUP);
        a.inherited(true, true, true, false, false);
        a.begin(2002, 2, Locale.ENGLISH, 0, 0, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Deep"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        a.end();

        a.begin(3000, 0, Locale.ENGLISH, 200, 0, 200, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Outside"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        tree = a.publish(0, 0, 0, 1f, true);

        AccessibleNode deep = tree.node(tree.indexOf(2002));
        AccessibleNode outside = tree.node(tree.indexOf(3000));

        assertTrue(vends(deep, UiaIds.SCROLL_ITEM_PATTERN),
                "two levels down from the pane, found by walking the parent links rather than by "
                        + "scanning anything");
        assertFalse(vends(outside, UiaIds.SCROLL_ITEM_PATTERN),
                "nobody can scroll this one into view, and saying otherwise offers a client a "
                        + "move that does nothing");
    }

    /**
     * An in-scene dialog is a container with no HWND: vending IWindowProvider from it would
     * advertise Close(), SetVisualState() and CanMaximize over an overlay that has none of them.
     */
    @Test
    void onlyARealWindowVendsWindowAndTransform() {
        AccessibleNode dialog = publish(Accessible.Role.DIALOG, a -> { });

        assertFalse(vends(dialog, UiaIds.WINDOW_PATTERN));
        assertFalse(vends(dialog, UiaIds.TRANSFORM_PATTERN));
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(dialog, UiaIds.IS_DIALOG),
                "what it says instead, and it is a property rather than a pattern");

        AccessibleNode window = publish(Accessible.Role.WINDOW,
                a -> a.window(false, true, true, limn.accessibility.WindowFacet.State.NORMAL));

        assertTrue(vends(window, UiaIds.WINDOW_PATTERN));
        assertTrue(vends(window, UiaIds.TRANSFORM_PATTERN),
                "moving and sizing are the same facet's, and the same HWND's");
    }

    @Test
    void aNodeWithNoFacetsVendsNothingRatherThanSomethingHarmless() {
        AccessibleNode label = publish(Accessible.Role.LABEL, a -> { });

        assertEquals(List.of(), patternsOf(label),
                "a client takes silence as 'this control cannot do that', which is true");
    }
}

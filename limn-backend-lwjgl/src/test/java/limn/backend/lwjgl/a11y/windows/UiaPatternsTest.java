package limn.backend.lwjgl.a11y.windows;

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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void aTableVendsGridAndTableAndItsDataCellsVendTheItemPatterns() {
        AccessibleNode table = publish(Accessible.Role.TABLE, a -> {
            a.table(100, 3);
            a.selection(false, false);
        });
        assertEquals(List.of(UiaIds.SELECTION_PATTERN, UiaIds.GRID_PATTERN), patternsOf(table));
        assertTrue(vends(table, UiaIds.TABLE_PATTERN));
        AccessibleNode cell = publish(Accessible.Role.CELL, a -> a.cell(4, 1));
        assertTrue(vends(cell, UiaIds.GRID_ITEM_PATTERN));
        assertTrue(vends(cell, UiaIds.TABLE_ITEM_PATTERN));
        AccessibleNode header = publish(Accessible.Role.COLUMN_HEADER, a -> a.cell(-1, 1));
        assertFalse(vends(header, UiaIds.GRID_ITEM_PATTERN), "a header is not in the grid");
        assertFalse(vends(header, UiaIds.TABLE_ITEM_PATTERN));
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

    /**
     * Measured, not reasoned: on the Windows guest a slider that vended both patterns was read by
     * NVDA as "slider" and nothing else, because NVDA prefers Value's string when both exist and
     * the string was "" -- while RangeValue answered 41, 42, 44, 45 correctly the whole time.
     */
    @Test
    void aSliderVendsRangeValueAloneBecauseItsValueHasNoSpokenForm() {
        AccessibleNode slider = publish(Accessible.Role.SLIDER,
                a -> a.value(42, 0, 100, 1, false));

        assertTrue(vends(slider, UiaIds.RANGE_VALUE_PATTERN), "a number with bounds");
        assertFalse(vends(slider, UiaIds.VALUE_PATTERN),
                "a Value pattern answering \"\" is what a reader speaks instead of the number");
    }

    @Test
    void aSpinnerVendsBothBecauseItsValueHasASpokenForm() {
        AccessibleNode spinner = publish(Accessible.Role.SPIN_BUTTON,
                a -> { a.value(450, 0, 1440, 1, false); a.valueText("07:30", 1); });

        assertTrue(vends(spinner, UiaIds.RANGE_VALUE_PATTERN), "the number");
        assertTrue(vends(spinner, UiaIds.VALUE_PATTERN), "and the form the user sees");
    }

    @Test
    void anEmptySpokenFormIsNoSpokenForm() {
        AccessibleNode node = publish(Accessible.Role.SLIDER,
                a -> { a.value(1, 0, 2, 1, false); a.valueText("", 1); });

        assertFalse(vends(node, UiaIds.VALUE_PATTERN));
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
     * ScrollItem is the node's own verb (semantics 5, WINDOWS-NEW-7): vended where the node
     * publishes SCROLL_INTO_VIEW, and not merely because an ancestor scrolls. Until 2026-09-15 this
     * case read "a node inside a scroll pane vends ScrollItem and one outside does not": a row under
     * a list whose widget refused the reveal was offered one.
     */
    @Test
    void scrollItemIsVendedWhereTheNodePublishesScrollIntoViewAndNowhereElse() {
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
        a.action(Accessible.Action.PRESS, Accessible.Action.SCROLL_INTO_VIEW);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(2003, 2, Locale.ENGLISH, 0, 0, 160, 40);
        a.role(Accessible.Role.LIST_ITEM);
        a.name(I18nString.literal("Refuses"), Accessible.NameFrom.CONTENT);
        a.selectionItem(false, 1, 1);
        a.inherited(true, true, true, false, false);
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
        AccessibleNode refuses = tree.node(tree.indexOf(2003));
        AccessibleNode outside = tree.node(tree.indexOf(3000));

        assertTrue(vends(deep, UiaIds.SCROLL_ITEM_PATTERN), "it publishes the verb");
        assertFalse(vends(refuses, UiaIds.SCROLL_ITEM_PATTERN),
                "under the same pane and publishing no reveal: offering one would be a move that "
                        + "does nothing while the client is told it was done");
        assertFalse(vends(outside, UiaIds.SCROLL_ITEM_PATTERN),
                "nobody can scroll this one into view, and saying otherwise offers a client a "
                        + "move that does nothing");
    }

    /**
     * No node vends Window or Transform (W1, 2026-09-15). An in-scene dialog is a container with no
     * HWND, so vending IWindowProvider from it would advertise Close(), SetVisualState() and
     * CanMaximize over an overlay that has none of them; and the real window's two patterns come
     * from the host provider UI Automation made for its HWND, which the root hands over, while this
     * bridge serves neither interface. Until 2026-09-15 the window node claimed both and a client's
     * GetPatternProvider got a null for each.
     */
    @Test
    void noNodeVendsWindowOrTransformBecauseTheHostProviderServesTheRealWindows() {
        AccessibleNode dialog = publish(Accessible.Role.DIALOG, a -> { });

        assertFalse(vends(dialog, UiaIds.WINDOW_PATTERN));
        assertFalse(vends(dialog, UiaIds.TRANSFORM_PATTERN));
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(dialog, UiaIds.IS_DIALOG),
                "what it says instead, and it is a property rather than a pattern");

        AccessibleNode window = publish(Accessible.Role.WINDOW,
                a -> a.window(false, true, true, limn.accessibility.WindowFacet.State.NORMAL));

        assertFalse(vends(window, UiaIds.WINDOW_PATTERN),
                "the HWND's host provider serves it, and this bridge has no IWindowProvider");
        assertFalse(vends(window, UiaIds.TRANSFORM_PATTERN), "nor an ITransformProvider");
        assertNull(UiaPatternProviders.interfaceFor(UiaIds.WINDOW_PATTERN));
        assertNull(UiaPatternProviders.interfaceFor(UiaIds.TRANSFORM_PATTERN));
    }

    /**
     * The ratchet W1 asked for: every pattern a node can be told it vends is one this bridge
     * serves an interface for, so a claim with nothing behind it (a client's GetPatternProvider
     * answered with a null) fails here. Asked of one node carrying every facet and every
     * parameterless verb, which is the most any node can claim. It found Selection and Scroll
     * claimed and unserved before 2026-09-15, and Window and Transform until the same day.
     */
    @Test
    void everyPatternANodeCanClaimIsOneThisBridgeServes() throws IllegalAccessException {
        AccessibleNode everything = publish(Accessible.Role.TABLE, a -> {
            a.toggle(limn.accessibility.ToggleFacet.State.ON);
            a.value(1, 0, 2, 1);
            a.valueText("one", 1);
            a.text("text", 1L, 0, limn.graphics.ShapedText.Affinity.DOWNSTREAM, 0, 0, 1, null, false);
            a.selection(true, false);
            a.selectionItem(true, 1, 1);
            a.expand(true);
            a.scroll(0.5, 0.5, 0.5, 0.5, true, true);
            a.window(true, true, true, limn.accessibility.WindowFacet.State.NORMAL);
            a.table(1, 1);
            a.cell(0, 0);
            java.util.List<Accessible.Action> verbs = new ArrayList<>();
            for (Accessible.Action action : Accessible.Action.values()) {
                if (action.isParameterless()) {
                    verbs.add(action);
                }
            }
            a.action(verbs.toArray(new Accessible.Action[0]));
        });
        List<String> claimedAndUnserved = new ArrayList<>();
        for (java.lang.reflect.Field field : UiaIds.class.getDeclaredFields()) {
            if (!field.getName().endsWith("_PATTERN") || field.getType() != int.class) {
                continue;
            }
            int patternId = field.getInt(null);
            if (vends(everything, patternId) && UiaPatternProviders.interfaceFor(patternId) == null) {
                claimedAndUnserved.add(field.getName());
            }
        }
        assertEquals(List.of(), claimedAndUnserved,
                "a pattern claimed with no interface behind it answers a client a null");
    }

    @Test
    void aNodeWithNoFacetsVendsNothingRatherThanSomethingHarmless() {
        AccessibleNode label = publish(Accessible.Role.LABEL, a -> { });

        assertEquals(List.of(), patternsOf(label),
                "a client takes silence as 'this control cannot do that', which is true");
    }
}

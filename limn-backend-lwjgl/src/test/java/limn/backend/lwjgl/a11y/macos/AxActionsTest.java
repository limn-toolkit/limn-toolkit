package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What each of AppKit's action selectors does on a node, and which of them a node offers at all. */
class AxActionsTest {

    private static AccessibleNode nodeWith(Accessible.Role role, Accessible.Action... verbs) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 40, 20);
        a.role(role);
        a.name(I18nString.literal("n"), Accessible.NameFrom.CONTENT);
        if (verbs.length > 0) a.action(verbs);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        return tree.find(1001);
    }

    @Test
    void aPressActivatesWhateverTheWidgetSaysActivationIs() {
        assertEquals(Accessible.Action.PRESS,
                AxActions.verbFor(nodeWith(Accessible.Role.BUTTON, Accessible.Action.PRESS),
                        "accessibilityPerformPress"));
        assertEquals(Accessible.Action.TOGGLE,
                AxActions.verbFor(nodeWith(Accessible.Role.CHECK_BOX, Accessible.Action.TOGGLE),
                        "accessibilityPerformPress"),
                "a check box has no PRESS, and pressing one is what a reader's user means");
        assertEquals(Accessible.Action.SELECT,
                AxActions.verbFor(nodeWith(Accessible.Role.RADIO_BUTTON, Accessible.Action.SELECT),
                        "accessibilityPerformPress"));
    }

    @Test
    void aNodeWhoseOnlyActivationIsOpeningOrClosingIsPressedOpenOrShut() {
        // MACOS-NEW-5 and semantics 5: a combo box publishes only EXPAND when closed and only COLLAPSE
        // when open, and had no press at all, so AXPress was withheld from it and a reader could not
        // open it. The list's order keeps PRESS, TOGGLE and SELECT first where a node has both.
        assertEquals(Accessible.Action.EXPAND,
                AxActions.verbFor(nodeWith(Accessible.Role.COMBO_BOX, Accessible.Action.EXPAND),
                        "accessibilityPerformPress"), "a closed combo box opens");
        assertEquals(Accessible.Action.COLLAPSE,
                AxActions.verbFor(nodeWith(Accessible.Role.COMBO_BOX, Accessible.Action.COLLAPSE),
                        "accessibilityPerformPress"), "an open one closes");
        assertEquals(Accessible.Action.EXPAND,
                AxActions.verbFor(nodeWith(Accessible.Role.MENU_ITEM, Accessible.Action.EXPAND,
                        Accessible.Action.SHOW_MENU), "accessibilityPerformConfirm"),
                "a closed menu title confirms open, as it presses open");
        assertEquals(Accessible.Action.SHOW_MENU,
                AxActions.verbFor(nodeWith(Accessible.Role.MENU_ITEM, Accessible.Action.EXPAND,
                        Accessible.Action.SHOW_MENU), "accessibilityPerformShowMenu"));
        assertEquals(Accessible.Action.SELECT,
                AxActions.verbFor(nodeWith(Accessible.Role.TREE_ITEM, Accessible.Action.SELECT,
                        Accessible.Action.EXPAND), "accessibilityPerformPress"),
                "a row that selects and opens is pressed as a click selects it; opening is AXDisclosing's");
    }

    @Test
    void aVerbThatIsNotPublishedIsNeverPressedWhateverFacetTheNodeHas() {
        // Semantics 5: no facet implies a parameterless verb. An expand facet with no verb published
        // (a menu title whose popup was refused) is not something a press may open.
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 40, 20);
        a.role(Accessible.Role.COMBO_BOX);
        a.expand(false);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        AccessibleNode combo = a.publish(0, 0, 0, 1f, true).find(1001);
        for (String selector : AxActions.selectors()) {
            assertNull(AxActions.verbFor(combo, selector), selector + " on a combo box that publishes no verb");
        }
    }

    @Test
    void theListedActionsAreEveryActionTheNodeOffersAndScrollToVisibleWhereItScrolls() {
        // Answering accessibilityActionNames replaces AppKit's derived list (read on the guest,
        // 2026-09-15), so it has to name every action the node offers, not only the new one.
        AccessibleNode slider = nodeWith(Accessible.Role.SLIDER, Accessible.Action.INCREMENT,
                Accessible.Action.DECREMENT, Accessible.Action.SCROLL_INTO_VIEW);
        assertEquals(java.util.List.of("NSAccessibilityIncrementAction", "NSAccessibilityDecrementAction",
                        AxActions.SCROLL_TO_VISIBLE_SYMBOL), AxActions.actionSymbolsFor(slider));
        AccessibleNode combo = nodeWith(Accessible.Role.COMBO_BOX, Accessible.Action.EXPAND);
        assertEquals(java.util.List.of("NSAccessibilityPressAction", "NSAccessibilityConfirmAction"),
                AxActions.actionSymbolsFor(combo), "a press that opens is still a press and a confirm");
        assertEquals(java.util.List.of(), AxActions.actionSymbolsFor(nodeWith(Accessible.Role.LABEL)));
    }

    @Test
    void aNamedActionPerformsTheVerbItsSelectorWouldAndScrollToVisibleScrolls() {
        AccessibleNode row = nodeWith(Accessible.Role.LIST_ITEM, Accessible.Action.SELECT,
                Accessible.Action.SCROLL_INTO_VIEW);
        assertEquals(Accessible.Action.SCROLL_INTO_VIEW,
                AxActions.verbForActionSymbol(row, AxActions.SCROLL_TO_VISIBLE_SYMBOL));
        assertEquals(Accessible.Action.SELECT, AxActions.verbForActionSymbol(row, "NSAccessibilityPressAction"));
        assertNull(AxActions.verbForActionSymbol(row, "NSAccessibilityIncrementAction"));
        assertNull(AxActions.verbForActionSymbol(nodeWith(Accessible.Role.LABEL), AxActions.SCROLL_TO_VISIBLE_SYMBOL),
                "a node that does not scroll into view is not scrolled");
        assertNull(AxActions.verbForActionSymbol(row, "NSAccessibilityPickAction"), "a name nobody lists");
    }

    @Test
    void aConfirmIsTheSameActivationAndNotASecondVerb() {
        AccessibleNode checkBox = nodeWith(Accessible.Role.CHECK_BOX, Accessible.Action.TOGGLE);
        assertEquals(AxActions.verbFor(checkBox, "accessibilityPerformPress"),
                AxActions.verbFor(checkBox, "accessibilityPerformConfirm"));
    }

    @Test
    void theTwoStepsAreTheirOwnVerbsAndNothingElses() {
        AccessibleNode slider = nodeWith(Accessible.Role.SLIDER,
                Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
        assertEquals(Accessible.Action.INCREMENT,
                AxActions.verbFor(slider, "accessibilityPerformIncrement"));
        assertEquals(Accessible.Action.DECREMENT,
                AxActions.verbFor(slider, "accessibilityPerformDecrement"));
        assertNull(AxActions.verbFor(slider, "accessibilityPerformPress"),
                "a slider is not activated, and offering a press that does nothing is worse than "
                        + "offering no press");
    }

    @Test
    void aNodeThatOffersNothingIsOfferedNothing() {
        AccessibleNode label = nodeWith(Accessible.Role.LABEL);
        for (String selector : AxActions.selectors()) {
            assertNull(AxActions.verbFor(label, selector), selector + " on a label");
        }
    }

    @Test
    void everySelectorInstalledIsOneWithAVerbBehindIt() {
        // The class installs exactly these, and AppKit derives what a client is offered from what
        // an object responds to -- so a selector here with nothing behind it is an action a reader
        // offers and a user finds does nothing.
        AccessibleNode everything = nodeWith(Accessible.Role.BUTTON,
                Accessible.Action.PRESS, Accessible.Action.TOGGLE, Accessible.Action.SELECT,
                Accessible.Action.EXPAND,
                Accessible.Action.INCREMENT, Accessible.Action.DECREMENT,
                Accessible.Action.SHOW_MENU, Accessible.Action.CANCEL);
        for (String selector : AxActions.selectors()) {
            assertNull(AxActions.verbFor(everything, selector) == null ? selector : null,
                    selector + " has no verb behind it");
        }
    }
}

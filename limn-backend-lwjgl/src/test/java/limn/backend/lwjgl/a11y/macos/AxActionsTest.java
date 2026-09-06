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
                Accessible.Action.INCREMENT, Accessible.Action.DECREMENT,
                Accessible.Action.SHOW_MENU, Accessible.Action.CANCEL);
        for (String selector : AxActions.selectors()) {
            assertNull(AxActions.verbFor(everything, selector) == null ? selector : null,
                    selector + " has no verb behind it");
        }
    }
}

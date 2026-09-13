package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one node answers to {@code GetPropertyValue}, over trees built the way a scene builds one.
 *
 * <p>Nothing here constructs a node by hand: the builder is the same one the walk uses, so a case
 * that passes is a case about what a real published node carries. And nothing needs COM — this is
 * the decision layer, and the {@code VARIANT} it is written into is the step above.
 */
class UiaPropertiesTest {

    /** A window holding one control, published the way a scene publishes one. */
    private static AccessibleTree publish(Accessible.Role role, String name, String description,
                                          Accessible.State... states) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(role);
        if (name != null) {
            a.name(I18nString.literal(name), Accessible.NameFrom.CONTENT);
        }
        if (description != null) {
            a.description(I18nString.literal(description));
        }
        boolean enabled = false;
        boolean focusable = false;
        boolean focused = false;
        boolean visible = true;
        boolean showing = true;
        for (Accessible.State state : states) {
            switch (state) {
                case ENABLED -> enabled = true;
                case FOCUSABLE -> focusable = true;
                case FOCUSED -> focused = true;
                case SHOWING -> showing = true;
                case VISIBLE -> visible = true;
                default -> a.state(state, true);
            }
        }
        a.inherited(enabled, visible, showing, focusable, focused);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    private static AccessibleNode control(AccessibleTree tree) {
        return tree.node(tree.indexOf(1001));
    }

    @Test
    void aControlAnswersItsTypeItsNameAndTheTwoViewFlags() {
        AccessibleNode node = control(publish(Accessible.Role.BUTTON, "Save", null,
                Accessible.State.ENABLED, Accessible.State.FOCUSABLE));

        assertEquals(UiaIds.CONTROL_BUTTON, UiaProperties.valueOf(node, UiaIds.CONTROL_TYPE));
        assertEquals("Save", UiaProperties.valueOf(node, UiaIds.NAME));
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(node, UiaIds.IS_CONTROL_ELEMENT),
                "how UI Automation builds its control view; a provider that leaves it empty asks "
                        + "every client to guess which elements are worth showing");
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(node, UiaIds.IS_CONTENT_ELEMENT));
        assertEquals("1001", UiaProperties.valueOf(node, UiaIds.AUTOMATION_ID),
                "the node's own identifier, which §1.3 keeps stable across republishes");
    }

    @Test
    void aNamelessNodeAnswersAnEmptyStringRatherThanNothing() {
        AccessibleNode node = control(publish(Accessible.Role.GROUP, null, null));

        assertEquals("", UiaProperties.valueOf(node, UiaIds.NAME),
                "a VT_EMPTY here reads to some clients as 'ask somewhere else' rather than as "
                        + "'this has no name'");
    }

    @Test
    void aDescriptionBecomesHelpTextAndAnAbsentOneIsEmpty() {
        assertEquals("What the button does",
                UiaProperties.valueOf(control(publish(Accessible.Role.BUTTON, "Save",
                        "What the button does")), UiaIds.HELP_TEXT));
        assertNull(UiaProperties.valueOf(control(publish(Accessible.Role.BUTTON, "Save", null)),
                UiaIds.HELP_TEXT));
    }

    @Test
    void theThreeStateFlagsComeFromTheStatesTheWalkPublished() {
        AccessibleNode on = control(publish(Accessible.Role.BUTTON, "Save", null,
                Accessible.State.ENABLED, Accessible.State.FOCUSABLE, Accessible.State.FOCUSED));

        assertEquals(Boolean.TRUE, UiaProperties.valueOf(on, UiaIds.IS_ENABLED));
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(on, UiaIds.IS_KEYBOARD_FOCUSABLE));
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(on, UiaIds.HAS_KEYBOARD_FOCUS));

        AccessibleNode off = control(publish(Accessible.Role.BUTTON, "Save", null));

        assertEquals(Boolean.FALSE, UiaProperties.valueOf(off, UiaIds.IS_ENABLED),
                "a disabled control is still published, and a client is told it cannot be used "
                        + "rather than left to find out by being refused");
        assertEquals(Boolean.FALSE, UiaProperties.valueOf(off, UiaIds.IS_KEYBOARD_FOCUSABLE));
        assertEquals(Boolean.FALSE, UiaProperties.valueOf(off, UiaIds.HAS_KEYBOARD_FOCUS));
    }

    /**
     * The inversion worth a case of its own: the platform's word is the negative of the toolkit's,
     * and answering it from {@code VISIBLE} instead would call every scrolled-away row on screen.
     */
    @Test
    void offscreenIsTheOppositeOfShowingAndNotOfVisible() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 900, 160, 40);
        a.role(Accessible.Role.LIST_ITEM);
        a.name(I18nString.literal("Row 40"), Accessible.NameFrom.CONTENT);
        // Visible by its own flag and every ancestor's, and scrolled out of the viewport: the two
        // facts the walk keeps apart.
        a.inherited(true, true, false, false, false);
        a.end();
        a.end();
        AccessibleNode row = control(a.publish(0, 0, 0, 1f, true));

        assertEquals(Boolean.TRUE, UiaProperties.valueOf(row, UiaIds.IS_OFFSCREEN),
                "scrolled out of the viewport is what UI Automation calls offscreen");
        assertTrue(row.has(Accessible.State.VISIBLE),
                "and the node is still visible, which is the fact that would have been read "
                        + "instead");
    }

    @Test
    void aPasswordFieldSaysSoByItsRoleAndByItsState() {
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(
                control(publish(Accessible.Role.PASSWORD_FIELD, "Password", null)),
                UiaIds.IS_PASSWORD));
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(
                control(publish(Accessible.Role.TEXT_FIELD, "Secret", null,
                        Accessible.State.PASSWORD)),
                UiaIds.IS_PASSWORD),
                "a field masking for a reason of its own is a password to a reader too");
        assertEquals(Boolean.FALSE, UiaProperties.valueOf(
                control(publish(Accessible.Role.TEXT_FIELD, "Name", null)), UiaIds.IS_PASSWORD));
    }

    @Test
    void aDialogAndAnAlertBothAnswerIsDialogBecauseThatIsThePlatformsWordForBoth() {
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(
                control(publish(Accessible.Role.DIALOG, "Unsaved changes", null)),
                UiaIds.IS_DIALOG),
                "the control type underneath is a neutral container: vending a window pattern "
                        + "from something with no HWND advertises Close() and CanMaximize over an "
                        + "overlay that has neither");
        assertEquals(Boolean.TRUE, UiaProperties.valueOf(
                control(publish(Accessible.Role.ALERT, "Saved", null)), UiaIds.IS_DIALOG));
        assertEquals(Boolean.FALSE, UiaProperties.valueOf(
                control(publish(Accessible.Role.GROUP, "A group", null)), UiaIds.IS_DIALOG));
    }

    /**
     * Anything unanswered is {@code VT_EMPTY}, which the spike watched UI Automation ask for and
     * carry on without complaint. The three named here are the ones this bridge owes a reading
     * rather than a decision, and each is recorded on {@link UiaProperties}.
     */
    @Test
    void theEightRolesUiAutomationCannotNameSpeakOurOwnPhrase() {
        // This used to be an assertNull with "and this module carries no bundle yet" beside it.
        // The bundle exists now, in the core, shared with the macOS bridge -- which needs a phrase
        // for every role because AppKit localizes against a bundle a JVM does not have, while UI
        // Automation names thirty-five of them correctly by itself and this answers only the rest.
        AccessibleNode aSwitch = control(publish(Accessible.Role.SWITCH, "Wrap lines", null,
                Accessible.State.ENABLED));
        assertEquals("switch", UiaProperties.valueOf(aSwitch, UiaIds.LOCALIZED_CONTROL_TYPE));

        AccessibleNode button = control(publish(Accessible.Role.BUTTON, "Save", null,
                Accessible.State.ENABLED));
        assertNull(UiaProperties.valueOf(button, UiaIds.LOCALIZED_CONTROL_TYPE),
                "the platform's own word is what makes a reader sound like every other application "
                        + "on the machine, and answering here would replace it with ours");
    }

    /**
     * UI Automation has no busy bit, so a busy item says so in its status string: a tree row whose
     * children are on their way (ADR 044 §2) is read as "busy" after its name. An item that is not
     * busy has no status at all, rather than one saying it is idle.
     */
    @Test
    void aBusyItemSaysSoInItsStatusAndAnIdleOneHasNone() {
        AccessibleNode loading = control(publish(Accessible.Role.TREE_ITEM, "Remote", null,
                Accessible.State.ENABLED, Accessible.State.BUSY));
        assertEquals("busy", UiaProperties.valueOf(loading, UiaIds.ITEM_STATUS),
                "the word the state is spoken as, in the node's own language");

        AccessibleNode idle = control(publish(Accessible.Role.TREE_ITEM, "Remote", null,
                Accessible.State.ENABLED));
        assertNull(UiaProperties.valueOf(idle, UiaIds.ITEM_STATUS));
    }

    /**
     * And the client is told when it starts and stops. Otherwise a client that cached the status
     * would go on reading a folder as loading after its children arrived. The change is raised as
     * the status string, not as the model's boolean: ItemStatus is text.
     */
    @Test
    void aBusyStateThatMovesIsRaisedAsTheStatusStringBeforeAndAfter() {
        AccessibleNode node = control(publish(Accessible.Role.TREE_ITEM, "Remote", null,
                Accessible.State.ENABLED));
        limn.accessibility.AccessibleEvent stopped =
                limn.accessibility.AccessibleEvent.state(node.id(), Accessible.State.BUSY, false);

        int property = UiaBridge.changedProperty(stopped, node);
        assertEquals(UiaIds.ITEM_STATUS, property);
        assertEquals("busy", UiaBridge.changedValue(property, Boolean.TRUE, node), "before");
        assertEquals("", UiaBridge.changedValue(property, Boolean.FALSE, node),
                "after: an empty status, which is the absence of one as a string");
        assertEquals(Boolean.TRUE, UiaBridge.changedValue(UiaIds.IS_ENABLED, Boolean.TRUE, node),
                "and a property that is a boolean keeps the model's boolean");
    }

    @Test
    void anUnansweredPropertyIsEmptyRatherThanAGuess() {
        AccessibleNode node = control(publish(Accessible.Role.SWITCH, "Wrap lines", null,
                Accessible.State.ENABLED));

        assertNull(UiaProperties.valueOf(node, UiaIds.HEADING_LEVEL),
                "the guest's interop assembly knows the property and not its enumerators, and "
                        + "§12.3 refuses a recalled constant");
        assertNull(UiaProperties.valueOf(node, UiaIds.CULTURE),
                "an LCID, and Java has no mapping to one; the guest can be asked for the table");
        assertNull(UiaProperties.valueOf(node, UiaIds.BOUNDING_RECTANGLE),
                "screen coordinates need the window's origin and scale, so the fragment answers "
                        + "it and not the node");
        assertNull(UiaProperties.valueOf(node, UiaIds.RUNTIME_ID),
                "prefixed with the host window's own, so the fragment answers it too");
        assertNull(UiaProperties.valueOf(node, 999_999));
    }

    @Test
    void everyRoleAnswersAControlTypeSoNoNodeIsClassifiedByTheClient() {
        for (Accessible.Role role : Accessible.Role.values()) {
            AccessibleNode node = control(publish(role, "A control", null));
            assertEquals(UiaRoles.of(role), UiaProperties.valueOf(node, UiaIds.CONTROL_TYPE),
                    role + " must answer the control type the table gives it");
        }
    }
}

package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.CellFacet;
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
        assertNull(UiaProperties.valueOf(on, UiaIds.HAS_KEYBOARD_FOCUS),
                "not a node's own state since 2026-09-15: the provider answers it from the tree's "
                        + "effective focus (UiaBridgeTest, UiaFragmentTest)");

        AccessibleNode off = control(publish(Accessible.Role.BUTTON, "Save", null));

        assertEquals(Boolean.FALSE, UiaProperties.valueOf(off, UiaIds.IS_ENABLED),
                "a disabled control is still published, and a client is told it cannot be used "
                        + "rather than left to find out by being refused");
        assertEquals(Boolean.FALSE, UiaProperties.valueOf(off, UiaIds.IS_KEYBOARD_FOCUSABLE));
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
     * A sorted column's header says which way its rows run in its status string too (decision 36),
     * because that is where the desktop puts one: File Explorer's own column header answers
     * {@code ItemStatus} "Classificado (Crescente)" (read on the guest 2026-09-15,
     * readings/windows-read-native-sort-direction.txt). The phrase is the model's — the description
     * a sorted header already carries — because this bridge reads the snapshot with no locale scope
     * open, and {@code HelpText} answers that same description, which is the settled list's "AND
     * HelpText" half and needs no code of its own.
     *
     * <p>A busy sorted header answers the busy word alone, which the code marks as a choice and not
     * a reading. And the header row is what makes a header: the data cell and the footer cell here
     * are each given a direction their facet has no business carrying <b>and</b> a description of
     * their own, so the header-row guard — and neither the model's restraint nor an empty
     * description — is what keeps a status off them.
     */
    @Test
    void aSortedHeaderSaysItsDirectionInItsStatusAndInItsHelpTextAndBusyWinsOverBoth() {
        AccessibleTree tree = aTableSortedOnItsSecondColumn(false);
        assertNull(UiaProperties.valueOf(tree.find(2101), UiaIds.ITEM_STATUS),
                "the unsorted column's header heads nothing sorted");
        assertEquals("Sorted ascending", UiaProperties.valueOf(tree.find(2102), UiaIds.ITEM_STATUS),
                "the model's phrase, resolved at publish where a locale scope was open");
        assertEquals("Sorted ascending", UiaProperties.valueOf(tree.find(2102), UiaIds.HELP_TEXT),
                "the same description, which is the settled list's other half and was already true");
        assertNull(UiaProperties.valueOf(tree.find(2201), UiaIds.ITEM_STATUS),
                "a data cell has a description of its own and heads no column");
        assertNull(UiaProperties.valueOf(tree.find(2301), UiaIds.ITEM_STATUS),
                "a footer cell is no header, whatever direction its facet holds");

        AccessibleTree busy = aTableSortedOnItsSecondColumn(true);
        assertEquals("busy", UiaProperties.valueOf(busy.find(2102), UiaIds.ITEM_STATUS),
                "a choice and not a reading: the busy word alone, never two phrases joined here");
        assertEquals("Sorted ascending", UiaProperties.valueOf(busy.find(2102), UiaIds.HELP_TEXT),
                "and the direction is still carried, which is why the choice costs a client nothing");
    }

    /**
     * A table of two columns sorted ascending on the second, published the way ADR 041 §7 says a
     * table publishes: a header group, a data row, and a footer whose cell is deliberately given a
     * direction. The sorted header carries the phrase in its description, as {@code Table} does.
     *
     * @param busy whether the sorted header is also busy
     */
    private static AccessibleTree aTableSortedOnItsSecondColumn(boolean busy) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        int table = a.begin(2000, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.TABLE);
        a.table(1, 2);
        a.inherited(true, true, true, false, false);
        int header = a.begin(2100, table, Locale.ENGLISH, 0, 0, 400, 30);
        a.role(Accessible.Role.GROUP);
        a.inherited(true, true, true, false, false);
        for (int c = 0; c < 2; c++) {
            a.begin(2101 + c, header, Locale.ENGLISH, c * 200, 0, 200, 30);
            a.role(Accessible.Role.COLUMN_HEADER);
            a.name(I18nString.literal(c == 0 ? "Name" : "Age"), Accessible.NameFrom.CONTENT);
            a.cell(-1, c, c == 1 ? CellFacet.Sort.ASCENDING : CellFacet.Sort.NONE);
            if (c == 1) {
                a.description(I18nString.literal("Sorted ascending"));
                if (busy) a.state(Accessible.State.BUSY, true);
            }
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        int row = a.begin(2200, table, Locale.ENGLISH, 0, 30, 400, 30);
        a.role(Accessible.Role.ROW);
        a.inherited(true, true, true, false, false);
        a.begin(2201, row, Locale.ENGLISH, 200, 30, 200, 30);
        a.role(Accessible.Role.CELL);
        a.name(I18nString.literal("42"), Accessible.NameFrom.CONTENT);
        a.description(I18nString.literal("Years since joining"));
        a.cell(0, 1, CellFacet.Sort.ASCENDING);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        int footer = a.begin(2300, table, Locale.ENGLISH, 0, 60, 400, 30);
        a.role(Accessible.Role.GROUP);
        a.inherited(true, true, true, false, false);
        a.begin(2301, footer, Locale.ENGLISH, 200, 60, 200, 30);
        a.role(Accessible.Role.CELL);
        a.name(I18nString.literal("Total 99"), Accessible.NameFrom.CONTENT);
        a.description(I18nString.literal("The column's total"));
        a.cell(-2, 1, CellFacet.Sort.DESCENDING);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
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

    /** A window holding one tree row with the given numbers. */
    private static AccessibleNode aRow(int position, int size, int level) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.TREE_ITEM);
        a.name(I18nString.literal("Reports"), Accessible.NameFrom.CONTENT);
        a.selectionItem(false, position, size);
        a.hierarchy(level, 3, 9);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        return control(a.publish(0, 0, 0, 1f, true));
    }

    /**
     * Semantics 6 (decision 4; W4, CRIT-6): PositionInSet and SizeOfSet are the selection item's
     * numbers and Level the hierarchy facet's, each as an integer and only when not zero; the level
     * passes through, the platform's base being one (read off a native tree 2026-09-15). Until
     * 2026-09-15 none of the three was answered.
     */
    @Test
    void anItemAnswersItsPositionItsSetSizeAndItsLevelAndNothingForAZero() {
        AccessibleNode row = aRow(2, 5, 3);
        assertEquals(2, UiaProperties.valueOf(row, UiaIds.POSITION_IN_SET));
        assertEquals(5, UiaProperties.valueOf(row, UiaIds.SIZE_OF_SET));
        assertEquals(3, UiaProperties.valueOf(row, UiaIds.LEVEL), "one-based, as the platform's");

        AccessibleNode unknown = aRow(0, 0, 0);
        assertNull(UiaProperties.valueOf(unknown, UiaIds.POSITION_IN_SET),
                "a zero is the model's no number, and VT_EMPTY is the platform's");
        assertNull(UiaProperties.valueOf(unknown, UiaIds.SIZE_OF_SET));
        assertNull(UiaProperties.valueOf(unknown, UiaIds.LEVEL));

        AccessibleNode plain = control(publish(Accessible.Role.BUTTON, "OK", null,
                Accessible.State.ENABLED));
        assertNull(UiaProperties.valueOf(plain, UiaIds.POSITION_IN_SET), "no selection item");
        assertNull(UiaProperties.valueOf(plain, UiaIds.LEVEL), "no hierarchy");
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

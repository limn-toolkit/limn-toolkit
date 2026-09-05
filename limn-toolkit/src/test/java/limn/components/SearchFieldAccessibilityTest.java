package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.TextFacet;
import limn.graphics.ShapedText;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link SearchField} becomes in the accessible tree: the one editable node
 * {@link TextField} publishes, said to be a {@code SEARCH_FIELD}, offering the press that stands
 * for Enter, named by the placeholder it ships with, and owning a trailing button the toolkit
 * itself has now named.
 *
 * <p>This class adds three facts to its base and its tests assert three things about them and
 * little else: that the role is the subclass's and not the super call's, that the verb reaches the
 * same body Enter reaches, and that every other verb still reaches the base. Everything a reader
 * hears beyond that — the contents with the caret and the selection, {@code EDITABLE}, the context
 * menu, the trailing button's node — is {@code TextField}'s and is pinned by
 * {@link TextFieldAccessibilityTest}; what the cases here own is that it all survived the role
 * change, which is the failure a subclass causes by forgetting to call {@code super}.
 *
 * <p>Four things ADR 039 §7's row is wrong or silent about, each pinned below. <b>"The clear
 * button" as this class's synthetic child names the wrong owner</b>: the button is the generic
 * trailing coupled region {@code TextField} paints and hit-tests for every field that has one, and
 * a child declared here would publish it twice. <b>"The clear button ... is named" was false in
 * the source</b> — the constructor called the unnamed overload, so the promised node was an
 * unnamed operable control, and no test would have caught it, because the gallery rule names only
 * focusable nodes and this button is not one. <b>"As {@code TextField}" hides the two places the
 * inheritance is not automatic</b>: the role has to be written after the super call, and every
 * verb but the press has to be handed back to it. And <b>the row is silent on the name and on what
 * performs the press</b>, which are this widget's two most particular facts: it is the only widget
 * in the toolkit that ships a placeholder of its own, and until this step there was no private
 * submit path for a hook to reach at all.
 *
 * <p>Every case drives the widget's public API on a bound scene, or calls the scene from where a
 * bridge stands. Nothing constructs a node.
 */
class SearchFieldAccessibilityTest extends AccessibleComponentTestBase {

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** The padding between the column and the field, so scene and local coordinates differ. */
    private static final float INSET = 20;

    private SearchField field;

    /** The column everything is bound in, so the field keeps its own measured box. */
    private Column root;

    private Locale before;

    @BeforeEach
    void pinTheLanguage() {
        // The names under test are shipped in twenty-one languages, and the process language is
        // whatever the machine running the build reports.
        before = I18n.processLocale();
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void releaseTheLanguage() {
        I18n.setLocale(before);
    }

    // ------------------------------------------------------------------------------ the fixture

    private void bindField() {
        field = new SearchField();
        root = new Column();
        root.add(Padding.all(INSET, field));
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
    }

    /** @return the field's node, the one and only search field in the tree */
    private AccessibleNode fieldNode() {
        return node(Accessible.Role.SEARCH_FIELD);
    }

    /** @return the clear button's node, the field's only child */
    private AccessibleNode clearNode() {
        List<AccessibleNode> children = childrenOf(fieldNode());
        assertEquals(1, children.size(),
                "one trailing button and nothing else" + describe(tree()));
        return children.get(0);
    }

    /**
     * @param role the role to count
     * @return how many nodes in the published tree carry it
     */
    private int countOf(Accessible.Role role) {
        AccessibleTree tree = tree();
        int found = 0;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                found++;
            }
        }
        return found;
    }

    // -------------------------------------------------------------------------------- the role

    @Test
    void theRoleIsSearchFieldAndTheFieldPublishesOneNode() {
        bindField();

        assertEquals(1, countOf(Accessible.Role.SEARCH_FIELD), describe(tree()));
        assertEquals(0, countOf(Accessible.Role.TEXT_FIELD),
                "the role is written after the super call, so the base class's TEXT_FIELD is "
                        + "overwritten rather than published beside it -- and it lands on this "
                        + "widget's own node rather than on the trailing button's, which is the "
                        + "base hook closing every synthetic child it opened" + describe(tree()));
        assertEquals(0, fieldNode().parent(), "the window's child" + describe(tree()));
        assertEquals(Accessible.Role.BUTTON, clearNode().role(),
                "and the child is still a button" + describe(tree()));
    }

    @Test
    void everythingTheBaseClassSaysSurvivesTheRoleChange() {
        bindField();

        AccessibleNode node = fieldNode();
        assertTrue(node.has(Accessible.State.EDITABLE),
                "inherited, and the case that fails first if super stops being called"
                        + describe(tree()));
        assertTrue(node.has(Accessible.State.HAS_POPUP), describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(node.has(Accessible.State.MULTI_LINE),
                "single-line, and the horizontal offset is a text window and not a scroll pane"
                        + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SHOW_MENU), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.PRESS),
                "the one verb this class adds" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS),
                "the two the walk adds for every focusable widget, which this hook must not "
                        + "restate" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
    }

    // -------------------------------------------------------------------------------- the name

    @Test
    void theShippedPlaceholderNamesItWithNothingFromTheApplication() {
        bindField();

        assertEquals(ComponentStrings.SEARCH_PLACEHOLDER.get(), fieldNode().name(),
                "the only widget in the toolkit that ships a placeholder of its own, so the only "
                        + "one whose node arrives named with no application involvement"
                        + describe(tree()));
        assertFalse(fieldNode().name().isEmpty(), describe(tree()));
        assertEquals(Accessible.NameFrom.PLACEHOLDER, fieldNode().nameFrom(), describe(tree()));
    }

    @Test
    void theNameStandsWhileThereIsTextInTheField() {
        bindField();
        String named = fieldNode().name();

        field.setText("boots");
        frame();

        assertEquals(named, fieldNode().name(),
                "the placeholder is painted only while the field is empty; the name is not, or "
                        + "the node would be renamed on the first keystroke and renamed back on "
                        + "the last backspace" + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
    }

    @Test
    void aTooltipBecomesTheDescriptionAndNeverTheName() {
        bindField();
        String named = fieldNode().name();

        field.setTooltip("Instant search");
        frame();

        assertEquals(named, fieldNode().name(),
                "a node that already has a name takes the walk's other branch, which is what the "
                        + "demo's own field does" + describe(tree()));
        assertEquals(Accessible.NameFrom.PLACEHOLDER, fieldNode().nameFrom(), describe(tree()));
        assertEquals("Instant search", fieldNode().description(), describe(tree()));
    }

    @Test
    void theApplicationsOwnPlaceholderRenamesTheNode() {
        bindField();
        long id = fieldNode().id();

        field.setPlaceholder("Find a city");
        frame();

        assertEquals("Find a city", fieldNode().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.PLACEHOLDER, fieldNode().nameFrom(), describe(tree()));
        assertEquals(id, fieldNode().id(), "a rename is not a rebuild" + describe(tree()));
        List<AccessibleEvent> renames = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.NAME_CHANGED) {
                renames.add(event);
            }
        }
        assertEquals(1, renames.size(),
                "one setter, one event, and no invalidateAccessible() call owed: setPlaceholder "
                        + "invalidates and the scene's damage marks the tree dirty"
                        + bridge.events);
        assertEquals(id, renames.get(0).nodeId(), describe(tree()));
    }

    @Test
    void bothOfTheToolkitsOwnStringsFollowTheSubtreesLanguage() {
        bindField();

        field.setLocale(BRAZILIAN);
        frame();

        assertEquals(BRAZILIAN, fieldNode().locale(),
                "the node says which language it is in" + describe(tree()));
        assertEquals("Pesquisar…", fieldNode().name(),
                "the placeholder is the held I18nString and not a string resolved in the hook"
                        + describe(tree()));
        assertEquals("Limpar", clearNode().name(),
                "and so is the clear button's name, which is what makes the shipped bundles the "
                        + "thing that carries it rather than the English in the constructor"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the press

    @Test
    void aPressFromAnAssistiveTechnologySubmitsTheCurrentQueryOnce() throws InterruptedException {
        bindField();
        List<String> submitted = new ArrayList<>();
        field.onSubmit(submitted::add);
        field.setText("boots");
        frame();
        bridge.events.clear();

        assertTrue(perform(fieldNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();

        assertEquals(List.of("boots"), submitted,
                "once, through the same body Enter reaches" + describe(tree()));
        assertEquals("boots", field.text(),
                "a search reads the field and never edits it" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void aPressOnAnEmptyFieldStillSubmits() throws InterruptedException {
        bindField();
        List<String> submitted = new ArrayList<>();
        field.onSubmit(submitted::add);

        assertTrue(perform(fieldNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();

        assertEquals(List.of(""), submitted,
                "Enter on an empty field fires with the empty query, so a guard here would make "
                        + "the two doors disagree about the same gesture" + describe(tree()));
    }

    @Test
    void aUsersOwnEnterRaisesNoInvoked() {
        bindField();
        List<String> submitted = new ArrayList<>();
        field.onSubmit(submitted::add);
        field.setText("boots");
        scene.requestFocus(field);
        frame();
        bridge.events.clear();

        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        frame();

        assertEquals(List.of("boots"), submitted, "the key path still runs" + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "§11's deliberate asymmetry: the dispatcher acknowledges an assistive "
                        + "technology's own invocation, and a press the user made leaves no "
                        + "difference between two snapshots to report: " + bridge.events);
    }

    @Test
    void aPressOnADisabledFieldDoesNothing() throws InterruptedException {
        bindField();
        List<String> submitted = new ArrayList<>();
        field.onSubmit(submitted::add);
        field.setEnabled(false);
        frame();

        perform(fieldNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(), submitted,
                "refused twice over: the dispatcher walks the ancestor chain for enabled, and "
                        + "the hook restates it because the key path's own guard is the scene's "
                        + "dispatch and the accessibility path does not travel it"
                        + describe(tree()));
        assertFalse(fieldNode().has(Accessible.State.ENABLED), describe(tree()));
    }

    @Test
    void aWholeValueSetStillReachesTheField() throws InterruptedException {
        bindField();
        List<String> submitted = new ArrayList<>();
        field.onSubmit(submitted::add);

        assertTrue(perform(fieldNode().id(), Accessible.Action.SET_TEXT,
                new Accessible.Argument.OfText("shoes")));
        frame();

        assertEquals("shoes", field.text(),
                "§11 promises every text control a whole-value set, and this is the case that "
                        + "fails the moment the action hook stops handing every verb but its own "
                        + "back to the base class -- which would make a search field the one text "
                        + "widget in the toolkit a reader cannot write into" + describe(tree()));
        assertEquals(List.of(), submitted, "and the two verbs are not crossed" + describe(tree()));
    }

    // --------------------------------------------------------------------- the clear button

    @Test
    void theClearButtonIsANamedOperablePressableChild() {
        bindField();

        AccessibleNode button = clearNode();
        assertEquals(Accessible.Role.BUTTON, button.role(), describe(tree()));
        assertEquals(ComponentStrings.SEARCH_CLEAR.get(), button.name(),
                "the constructor's named overload, and the assertion that regresses the moment "
                        + "it goes back to the two-argument one -- which nothing else in the "
                        + "suite would catch, because the gallery rule names only focusable nodes "
                        + "and this button is not one" + describe(tree()));
        assertFalse(button.name().isEmpty(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, button.nameFrom(),
                "the toolkit's own word for a control that paints a glyph, holds no text, has no "
                        + "tooltip and no accessor an application can reach" + describe(tree()));
        assertTrue(button.actions().has(Accessible.Action.PRESS), describe(tree()));
        assertFalse(button.has(Accessible.State.FOCUSABLE),
                "Tab steps over it and only the pointer reaches it" + describe(tree()));
        assertTrue(button.has(Accessible.State.ENABLED), describe(tree()));
    }

    @Test
    void theClearButtonSitsOnTheTrailingEdgeAndTurnsAroundRightToLeft() {
        bindField();

        AccessibleNode node = fieldNode();
        AccessibleNode button = clearNode();
        assertEquals(node.y(), button.y(), describe(tree()));
        assertEquals(node.height(), button.height(),
                "the whole height the paint and the hit test both use, which is the field's "
                        + "laid-out height and not a square built from a token" + describe(tree()));
        assertTrue(button.width() > 0, describe(tree()));
        assertEquals(node.x() + node.width() - button.width(), button.x(),
                "flush with the trailing edge reading left to right" + describe(tree()));
        assertNotEquals(node.x(), button.x(), describe(tree()));

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode mirrored = fieldNode();
        assertEquals(mirrored.x(), clearNode().x(),
                "and on the LEFT edge reading right to left, which is what a box derived from "
                        + "width() minus the trailing inset unconditionally gets wrong in Arabic "
                        + "and Hebrew" + describe(tree()));
        assertEquals(mirrored.y(), clearNode().y(), describe(tree()));
        assertEquals(mirrored.height(), clearNode().height(), describe(tree()));
    }

    @Test
    void pressingTheClearButtonEmptiesTheFieldAndNotifies() throws InterruptedException {
        bindField();
        List<String> changes = new ArrayList<>();
        List<String> submitted = new ArrayList<>();
        field.onChange(changes::add);
        field.onSubmit(submitted::add);
        field.setText("shoes");
        frame();
        long button = clearNode().id();

        assertTrue(perform(button, Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();

        assertEquals("", field.text(),
                "the press reaches the field's own trailing Runnable, which is this class's "
                        + "clear() -- and not a node hard-wired to clear(), which would lie for "
                        + "an application that called setTrailingButton on a search field"
                        + describe(tree()));
        assertEquals(List.of(""), changes, "and clear() fires, as the pointer press does");

        assertEquals(button, clearNode().id(),
                "the key is a constant, so a reader is still holding the same button");
        assertTrue(perform(button, Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();

        assertEquals(List.of(""), changes,
                "clear()'s own emptiness guard, the same one the pointer press obeys");
        assertEquals(List.of(), submitted,
                "the field's press and the button's press are two verbs on two nodes with two "
                        + "different meanings" + describe(tree()));
    }

    // ------------------------------------------------------------------ the contents, and cost

    @Test
    void theQueryReachesTheTreeAsTheNodesText() {
        bindField();

        field.setText("boots");
        frame();

        assertEquals(new TextFacet("boots", 5, ShapedText.Affinity.DOWNSTREAM, 5, 5, 1, null),
                fieldNode().text(),
                "one assertion and not a text-facet suite: the caret, the selection and the "
                        + "astral cases are the base class's own test's, and this exists so that "
                        + "a search field which stopped calling super is caught here rather than "
                        + "in a bridge" + describe(tree()));
    }

    @Test
    void aCaretBlinkOnASearchFieldPublishesNothing() {
        bindField();
        field.setText("boots");
        scene.requestFocus(field);
        frame();

        int published = bridge.published.size();
        bridge.events.clear();
        for (int i = 0; i < 10; i++) {
            field.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "ten damaged frames that changed no accessible fact, which is the blink. Both "
                        + "hooks here are constants over held strings, and the line that would "
                        + "break it is a name or a value formatted inside the describe hook"
                        + describe(tree()));
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }
}

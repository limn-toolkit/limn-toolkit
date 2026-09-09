package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.SelectionItemFacet;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.StringBundle;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link RadioButton} becomes in the accessible tree: one node with the radio role, named
 * by the caption it holds, a member of its group's selection with its position and the set's
 * size, offering a select that reaches the group's own swap and every listener on it.
 *
 * <p>The survey row in ADR 039 §7 has the role, the facet and the verb right and is wrong about
 * the grouping: it promises a {@code MEMBER_OF} relation to the {@link ButtonGroup}, and a
 * relation's target has to be a published node, which a group — a plain object with no box — can
 * never be; the walk resolves it to nothing and the publish step drops it. The grouping is the
 * position and the size of the set alone, which is what the cases below pin. The row is also
 * silent on the standalone radio, which the source treats as a mode of its own with no set, and
 * on the one membership change that paints nothing: adding a member changes every member's set
 * size, and the group has to say so itself.
 *
 * <p>Every case drives the public setters on a bound scene, or calls the scene from where a
 * bridge stands, and reads what the scene published; nothing here builds a tree.
 */
class RadioButtonAccessibilityTest extends AccessibleComponentTestBase {

    private static final I18nString SIZE_LARGE = new I18nString("size.large", "Large");

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** One bundle for one key: everything else falls through to the English. */
    private static final StringBundle CAPTIONS = (key, locale) ->
            "size.large".equals(key) && BRAZILIAN.equals(locale) ? "Grande" : null;

    /** The column the radios are bound in, for the cases that disable or re-read the container. */
    private Column root;

    /** The radios under test, in the order they were added to the column and the group. */
    private final List<RadioButton> radios = new ArrayList<>();

    private ButtonGroup group;

    /** Every notification the application's handlers were given, in order, as "label=value". */
    private final List<String> handled = new ArrayList<>();

    /** Every index the group's own listener was given, in order. */
    private final List<Integer> selectedIndices = new ArrayList<>();

    @AfterEach
    void resetLanguage() {
        I18n.removeBundle(CAPTIONS);
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One radio per caption in a column, every one wired to the recording handler, no group. */
    private void bindStandalone(I18nString... captions) {
        radios.clear();
        root = new Column();
        for (I18nString caption : captions) {
            RadioButton radio = new RadioButton(caption);
            radio.onChange(value -> handled.add(radio.text() + "=" + value));
            radios.add(radio);
            root.add(radio);
        }
        bind(root);
    }

    /** The same column, with every radio a member of one group whose listener also records. */
    private void bindGroup(String... captions) {
        radios.clear();
        group = new ButtonGroup().onSelect(selectedIndices::add);
        root = new Column();
        for (String caption : captions) {
            RadioButton radio = new RadioButton(caption);
            radio.onChange(value -> handled.add(radio.text() + "=" + value));
            radios.add(radio);
            root.add(radio);
            group.add(radio);
        }
        bind(root);
    }

    /** Moves the pointer to the centre of a radio's box, as a hover does. */
    private void hover(RadioButton radio) {
        scene.mouseMoved(radio.localToSceneX() + radio.width() / 2,
                radio.localToSceneY() + radio.height() / 2);
        scene.inputBatchEnded();
    }

    /** @return every selected-state event raised so far, in order */
    private List<AccessibleEvent> selectedEvents() {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED
                    && event.state() == Accessible.State.SELECTED) {
                found.add(event);
            }
        }
        return found;
    }

    /** @return every radio node in the tree, in tree order */
    private List<AccessibleNode> radioNodes() {
        List<AccessibleNode> found = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == Accessible.Role.RADIO_BUTTON) {
                found.add(tree.node(i));
            }
        }
        return found;
    }

    /** Every widget the scene's own Tab traversal reaches from nothing, by caption, in order. */
    private List<String> tabOrder() {
        List<String> order = new ArrayList<>();
        scene.requestFocus(null);
        for (int i = 0; i < 32; i++) {
            scene.focusTraverse(false);
            Widget focused = scene.focusedWidget();
            if (focused == null) {
                break;
            }
            String caption = ((RadioButton) focused).text();
            if (order.contains(caption)) {
                break; // wrapped
            }
            order.add(caption);
        }
        return order;
    }

    /** @return the captions of every node published focusable, in tree order */
    private List<String> publishedFocusOrder() {
        List<String> order = new ArrayList<>();
        for (AccessibleNode node : nodesWith(Accessible.State.FOCUSABLE)) {
            order.add(node.name());
        }
        return order;
    }

    // ------------------------------------------------------------------------------ what it is

    @Test
    void aRadioPublishesOneRadioNodeNamedByItsCaption() {
        bindGroup("Small", "Medium", "Large");

        AccessibleNode small = node("Small");
        assertEquals(Accessible.Role.RADIO_BUTTON, small.role(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, small.nameFrom(),
                "the name is the caption, which decides the attribute one platform writes it into"
                        + describe(tree()));
        assertTrue(small.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(small.has(Accessible.State.SELECTED),
                "nothing is selected from the constructor" + describe(tree()));
        assertFalse(small.has(Accessible.State.ACTIVE),
                "a radio is never a container's active descendant: the publish step takes the "
                        + "first active node in a container's subtree, and a radio in a list cell "
                        + "would hijack the list's" + describe(tree()));
        assertTrue(small.actions().has(Accessible.Action.SELECT),
                "the one verb the widget declares" + describe(tree()));
        assertFalse(small.actions().has(Accessible.Action.DESELECT),
                "a radio never toggles off, and the only route to no selection is the group's "
                        + "clearSelection from code" + describe(tree()));
        assertFalse(small.actions().has(Accessible.Action.PRESS),
                "a select is not a press" + describe(tree()));
        assertFalse(small.actions().has(Accessible.Action.TOGGLE), describe(tree()));
        assertNull(small.actions().keyBinding(),
                "Space and Enter are the platform's own convention, not an accelerator");
        assertNull(small.toggle(), "a radio is a selection member, not a toggle" + describe(tree()));
        assertNull(small.value(), describe(tree()));
        assertNull(small.text(), describe(tree()));
        assertNull(small.expand(), describe(tree()));
        assertEquals(List.of(), small.relations(),
                "§7's MEMBER_OF cannot be published: the group is not a widget, so it is not a "
                        + "node, and a relation to nothing is dropped" + describe(tree()));
        assertEquals(List.of(), childrenOf(small),
                "the ring, the dot and the caption are one control" + describe(tree()));
        assertEquals(3, radioNodes().size(), describe(tree()));
        for (int i = 0; i < tree().nodeCount(); i++) {
            Accessible.Role role = tree().node(i).role();
            assertFalse(role == Accessible.Role.RADIO_GROUP,
                    "there is no group node: the group has no box to publish, and synthesising "
                            + "one from the members' rectangles was rejected" + describe(tree()));
            assertFalse(role == Accessible.Role.LABEL,
                    "the caption is not a second node, or a reader would hear it twice"
                            + describe(tree()));
        }
    }

    @Test
    void theBoxIsTheWholeRowInBothDirections() {
        bindGroup("Small", "Medium");
        RadioButton small = radios.get(0);

        AccessibleNode node = node("Small");
        assertEquals(small.localToSceneX(), node.x(), describe(tree()));
        assertEquals(small.localToSceneY(), node.y(), describe(tree()));
        assertEquals(small.width(), node.width(), describe(tree()));
        assertEquals(small.height(), node.height(),
                "the box is the widget's own, which is exactly what a pointer hits; the focus "
                        + "circle painted outside it through paintOutset is damage and never a "
                        + "target" + describe(tree()));

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode mirrored = node("Small");
        assertEquals(small.localToSceneX(), mirrored.x(), describe(tree()));
        assertEquals(small.width(), mirrored.width(),
                "reading right to left moves the ring to the other end of the same row"
                        + describe(tree()));
        assertEquals(small.height(), mirrored.height(), describe(tree()));
    }

    // ------------------------------------------------------------------------------ the name

    @Test
    void aRelabelRepublishesTheNameAndAnI18nCaptionFollowsTheSubtreesLanguage() {
        bindGroup("Small", "Medium");
        long id = node("Small").id();

        radios.get(0).setText("Tiny");
        frame();

        AccessibleNode renamed = node("Tiny");
        assertEquals(id, renamed.id(), "same node" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, renamed.nameFrom(), describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "one name moved, so one event: " + bridge.events);
        AccessibleEvent event = bridge.events.stream()
                .filter(each -> each.type() == AccessibleEvent.Type.NAME_CHANGED)
                .findFirst().orElseThrow();
        assertEquals("Small", event.oldValue(), bridge.events.toString());
        assertEquals("Tiny", event.newValue(), bridge.events.toString());
        assertEquals(List.of(), selectedEvents(),
                "a rename says nothing about the state: " + bridge.events);

        I18n.addBundle(CAPTIONS);
        I18n.setLocale(Locale.ENGLISH);
        bindStandalone(SIZE_LARGE);
        long large = node("Large").id();

        root.setLocale(BRAZILIAN);
        frame();

        AccessibleNode translated = node("Grande");
        assertEquals(large, translated.id(), describe(tree()));
        assertEquals(BRAZILIAN, translated.locale(), describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "the name is the held source re-resolved under the subtree's language, which a "
                        + "string resolved inside the hook could not be: " + bridge.events);
    }

    @Test
    void aTooltipDescribesACaptionedRadioAndNamesABareOne() {
        bindGroup("Small", "Medium");
        radios.get(0).setTooltip("Fits in a pocket");
        frame();

        AccessibleNode captioned = node("Small");
        assertEquals(Accessible.NameFrom.CONTENT, captioned.nameFrom(),
                "the tooltip never overwrites the caption" + describe(tree()));
        assertEquals("Fits in a pocket", captioned.description(),
                "the walk's free default puts it in the description when a name exists; the hook "
                        + "never writes the description itself" + describe(tree()));

        bindStandalone(I18nString.literal(""));
        radios.get(0).setTooltip("Large");
        frame();

        AccessibleNode fromTooltip = node("Large");
        assertEquals(Accessible.Role.RADIO_BUTTON, fromTooltip.role(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, fromTooltip.nameFrom(),
                "an empty caption is handed over all the same, and the walk's default is aware "
                        + "that it is empty, which is what lets the tooltip name the node"
                        + describe(tree()));
        assertEquals("", fromTooltip.description(),
                "one string is not both the name and the description" + describe(tree()));
        assertTrue(fromTooltip.has(Accessible.State.FOCUSABLE),
                "a standalone radio is always a tab stop, so it is always a node"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the set

    @Test
    void membersReportTheirPositionAndTheSetsSizeAndAStandaloneRadioHasNoSet() {
        bindGroup("Small", "Medium", "Large");

        assertEquals(new SelectionItemFacet(false, 1, 3), node("Small").selectionItem(),
                describe(tree()));
        assertEquals(new SelectionItemFacet(false, 2, 3), node("Medium").selectionItem(),
                "one-based, in the order the members were added" + describe(tree()));
        assertEquals(new SelectionItemFacet(false, 3, 3), node("Large").selectionItem(),
                describe(tree()));

        bindStandalone(I18nString.literal("Other"));

        assertEquals(new SelectionItemFacet(false, 0, 0), node("Other").selectionItem(),
                "no group, no set: zero is the facet's own spelling of none" + describe(tree()));
    }

    @Test
    void selectingThroughTheGroupMovesTheSelectedBitAndRaisesOneEventPerNodeThatMoved() {
        bindGroup("Small", "Medium", "Large");
        long small = node("Small").id();
        long medium = node("Medium").id();

        group.setSelectedIndex(1);
        frame();

        assertTrue(node("Medium").has(Accessible.State.SELECTED), describe(tree()));
        assertTrue(node("Medium").selectionItem().selected(),
                "the builder derives the bit from the facet" + describe(tree()));
        assertFalse(node("Small").has(Accessible.State.SELECTED), describe(tree()));
        assertFalse(node("Large").has(Accessible.State.SELECTED), describe(tree()));
        List<AccessibleEvent> raised = selectedEvents();
        assertEquals(1, raised.size(), "one node moved, one event: " + bridge.events);
        assertEquals(medium, raised.get(0).nodeId(), bridge.events.toString());
        assertEquals(Boolean.TRUE, raised.get(0).newValue(), bridge.events.toString());
        assertEquals(List.of(), handled,
                "a set from code reaches the tree through the members' announcements and not the"
                        + " application's handlers, which answer the user");

        bridge.events.clear();
        group.setSelectedIndex(0);
        frame();

        assertTrue(node("Small").has(Accessible.State.SELECTED), describe(tree()));
        assertFalse(node("Medium").has(Accessible.State.SELECTED), describe(tree()));
        raised = selectedEvents();
        assertEquals(2, raised.size(), "two nodes moved, two events: " + bridge.events);
        for (AccessibleEvent event : raised) {
            if (event.nodeId() == small) {
                assertEquals(Boolean.TRUE, event.newValue(), bridge.events.toString());
            } else {
                assertEquals(medium, event.nodeId(), bridge.events.toString());
                assertEquals(Boolean.FALSE, event.newValue(), bridge.events.toString());
            }
        }

        bridge.events.clear();
        group.clearSelection();
        frame();

        assertEquals(List.of(), nodesWith(Accessible.State.SELECTED),
                "the reset a form needs, heard as the last member losing its bit"
                        + describe(tree()));
        raised = selectedEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(small, raised.get(0).nodeId(), bridge.events.toString());
        assertEquals(Boolean.FALSE, raised.get(0).newValue(), bridge.events.toString());
    }

    // ------------------------------------------------------------------------------ the focus

    /**
     * A group is one tab stop, and the tree may offer exactly the stop the keyboard reaches: the
     * selected member, else the first <em>enabled</em> one. Stated against the scene's own
     * traversal at every step rather than against a list of expected captions, because the
     * invariant is that the two agree and not that either has a particular value.
     */
    @Test
    void theOneFocusableMemberIsTheOneTheKeyboardReachesAtEveryStep() {
        // Disabled before it joins, because the group decides its holder when a member joins and
        // when the selection moves, and never on a member's own enabled flag changing.
        radios.clear();
        group = new ButtonGroup().onSelect(selectedIndices::add);
        root = new Column();
        for (String caption : List.of("Small", "Medium", "Large")) {
            RadioButton radio = new RadioButton(caption);
            radios.add(radio);
            root.add(radio);
        }
        radios.get(0).setEnabled(false);
        for (RadioButton radio : radios) {
            group.add(radio);
        }
        bind(root);

        assertEquals(List.of("Medium"), publishedFocusOrder(),
                "the holder is the first enabled member, not the first member" + describe(tree()));
        assertEquals(tabOrder(), publishedFocusOrder(), describe(tree()));

        AccessibleNode large = node("Large");
        assertTrue(large.has(Accessible.State.ENABLED),
                "a non-holder is not a tab stop and is still operable" + describe(tree()));
        assertFalse(large.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(large.actions().has(Accessible.Action.SELECT), describe(tree()));
        assertFalse(large.actions().has(Accessible.Action.FOCUS),
                "the walk adds the focus verbs to the holder alone" + describe(tree()));
        assertFalse(large.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertTrue(node("Medium").actions().has(Accessible.Action.FOCUS), describe(tree()));
        assertTrue(node("Medium").actions().has(Accessible.Action.SCROLL_INTO_VIEW),
                describe(tree()));

        group.setSelectedIndex(2);
        frame();

        assertEquals(List.of("Large"), publishedFocusOrder(),
                "the tab stop follows the selection" + describe(tree()));
        assertEquals(tabOrder(), publishedFocusOrder(), describe(tree()));

        group.clearSelection();
        frame();

        assertEquals(List.of("Medium"), publishedFocusOrder(),
                "and goes back to the first enabled member when there is none" + describe(tree()));
        assertEquals(tabOrder(), publishedFocusOrder(), describe(tree()));

        radios.get(1).setEnabled(false);
        radios.get(2).setEnabled(false);
        frame();

        assertEquals(List.of(), publishedFocusOrder(),
                "a group whose members are all disabled has no holder and no tab stop"
                        + describe(tree()));
        assertEquals(tabOrder(), publishedFocusOrder(), describe(tree()));

        // Re-enabling a non-holder after grouping is the case the group does not handle: the
        // holder is decided when a member joins and when the selection moves, and a member's own
        // enabled flag changing re-applies nothing, so this group has an enabled member and no
        // tab stop. That is a keyboard defect the tree surfaces rather than fixes, and it is
        // asserted here only as the invariant: whatever the keyboard reaches, the tree offers.
        radios.get(2).setEnabled(true);
        frame();

        assertTrue(node("Large").has(Accessible.State.ENABLED), describe(tree()));
        assertEquals(tabOrder(), publishedFocusOrder(),
                "the tree never offers a stop the keyboard cannot reach, nor hides one it can"
                        + describe(tree()));
    }

    // ----------------------------------------------------------------------------- membership

    /**
     * Adding a member paints nothing and changes what every member says about its set. The first
     * addition is the sharp case: a standalone radio is already focusable and becomes the holder,
     * so the roving-focus pass flips no flag and would invalidate nothing, and without the
     * group's own call the reader keeps hearing a radio with no set until an unrelated repaint.
     */
    @Test
    void addingAMemberIsNotSilent() {
        bindStandalone(I18nString.literal("Small"));
        assertEquals(new SelectionItemFacet(false, 0, 0), node("Small").selectionItem(),
                describe(tree()));
        int published = bridge.published.size();

        group = new ButtonGroup();
        group.add(radios.get(0));
        frame();

        assertEquals(published + 1, bridge.published.size(),
                "membership changed and nothing painted, so the group had to buy the publish");
        assertEquals(new SelectionItemFacet(false, 1, 1), node("Small").selectionItem(),
                describe(tree()));

        for (String caption : List.of("Medium", "Large", "Huge")) {
            RadioButton radio = new RadioButton(caption);
            radios.add(radio);
            root.add(radio);
        }
        frame();

        assertEquals(new SelectionItemFacet(false, 0, 0), node("Huge").selectionItem(),
                "in the column and not yet in the group: a standalone radio" + describe(tree()));
        assertEquals(new SelectionItemFacet(false, 1, 1), node("Small").selectionItem(),
                describe(tree()));

        for (int i = 1; i < radios.size(); i++) {
            group.add(radios.get(i));
        }
        frame();

        assertEquals(new SelectionItemFacet(false, 1, 4), node("Small").selectionItem(),
                "every member's set grew, the ones that were not touched included"
                        + describe(tree()));
        assertEquals(new SelectionItemFacet(false, 2, 4), node("Medium").selectionItem(),
                describe(tree()));
        assertEquals(new SelectionItemFacet(false, 3, 4), node("Large").selectionItem(),
                describe(tree()));
        assertEquals(new SelectionItemFacet(false, 4, 4), node("Huge").selectionItem(),
                describe(tree()));
        assertEquals(List.of("Small"), publishedFocusOrder(),
                "the first member stayed the holder throughout" + describe(tree()));
    }

    // ---------------------------------------------------------------------------- the action

    @Test
    void aSelectFromTheBridgeGoesThroughTheGroupAndTellsEveryListenerOnce() throws Exception {
        bindGroup("Small", "Medium", "Large");
        group.setSelectedIndex(0);
        frame();
        handled.clear();
        selectedIndices.clear();
        bridge.events.clear();
        long medium = node("Medium").id();

        assertTrue(perform(medium, Accessible.Action.SELECT, Accessible.Argument.NONE),
                "accepted, which is not the same as done");

        assertTrue(radios.get(1).isSelected());
        assertFalse(radios.get(0).isSelected());
        assertEquals(List.of("Small=false", "Medium=true"), handled,
                "the hook reaches select(), which is what a click and a Space press reach, and "
                        + "the group's swap is what tells the leaving member, the entering one "
                        + "and the group's own listener");
        assertEquals(List.of(1), selectedIndices);

        frame();

        assertEquals(2, selectedEvents().size(), "two nodes moved: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press and nothing else; for a select the state change "
                        + "is the acknowledgement on all three platforms: " + bridge.events);
        assertEquals(List.of("Medium"), publishedFocusOrder(),
                "the tab stop moved with the selection" + describe(tree()));
        assertNull(scene.focusedWidget(),
                "and focus did not: it follows the selection only out of a focused member");

        handled.clear();
        selectedIndices.clear();
        bridge.events.clear();
        assertTrue(perform(medium, Accessible.Action.SELECT, Accessible.Argument.NONE),
                "the state asked for holds, so the answer is yes");
        frame();

        assertEquals(List.of(), handled, "an already-selected radio fires nothing");
        assertEquals(List.of(), selectedIndices);
        assertEquals(List.of(), selectedEvents(), bridge.events.toString());
    }

    @Test
    void aSelectOnAStandaloneRadioSelectsItAndTellsItsOwnListener() throws Exception {
        bindStandalone(I18nString.literal("Other"));
        long id = node("Other").id();

        assertTrue(perform(id, Accessible.Action.SELECT, Accessible.Argument.NONE));
        frame();

        assertTrue(radios.get(0).isSelected());
        assertEquals(List.of("Other=true"), handled);
        assertTrue(node("Other").has(Accessible.State.SELECTED), describe(tree()));
        assertEquals(new SelectionItemFacet(true, 0, 0), node("Other").selectionItem(),
                describe(tree()));
        assertEquals(1, selectedEvents().size(), bridge.events.toString());
    }

    @Test
    void aDisabledRadioAndARadioInADisabledColumnBothRefuseTheSelect() throws Exception {
        bindGroup("Small", "Medium", "Large");
        radios.get(2).setEnabled(false);
        frame();
        bridge.events.clear();

        AccessibleNode large = node("Large");
        assertFalse(large.has(Accessible.State.ENABLED), describe(tree()));
        assertEquals(Accessible.Role.RADIO_BUTTON, large.role(),
                "a disabled radio is heard as disabled rather than vanishing" + describe(tree()));

        perform(large.id(), Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        assertFalse(radios.get(2).isSelected(), "a disabled control was operated");
        assertEquals(List.of(), handled);
        assertEquals(List.of(), selectedIndices);
        assertEquals(List.of(), selectedEvents(), bridge.events.toString());

        // The same member with its own flag true, inside a container that is not: the widget's
        // own guard passes and the scene's ancestor gate is what refuses, as the keyboard does.
        bindGroup("Small", "Medium", "Large");
        root.setEnabled(false);
        frame();

        assertTrue(radios.get(1).isEnabled(), "the fixture has to leave the radio's own flag alone");
        assertFalse(node("Medium").has(Accessible.State.ENABLED),
                "the bit is inherited down the walk" + describe(tree()));
        bridge.events.clear();

        perform(node("Medium").id(), Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        assertFalse(radios.get(1).isSelected(),
                "a control inside a disabled container is one the keyboard refuses too");
        assertEquals(List.of(), handled);
        assertEquals(List.of(), selectedEvents(), bridge.events.toString());

        // And the code path is not guarded, on purpose: a form restoring a disabled group's
        // state goes through the same select(), and a guard there would make that a silent no-op.
        group.setSelectedIndex(1);
        assertTrue(radios.get(1).isSelected(),
                "setSelectedIndex on a disabled form still restores its state");
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindGroup("Small", "Medium");
        group.setSelectedIndex(0);
        frame();
        handled.clear();
        selectedIndices.clear();
        bridge.events.clear();

        perform(node("Small").id(), Accessible.Action.DESELECT, Accessible.Argument.NONE);
        perform(node("Medium").id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(node("Medium").id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(node("Medium").id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1));
        frame();

        assertTrue(radios.get(0).isSelected(),
                "a deselect changes nothing: a radio never toggles off, and the hook answers "
                        + "false for everything but SELECT");
        assertFalse(radios.get(1).isSelected(), "a press is not the select this node offers");
        assertEquals(List.of(), handled);
        assertEquals(List.of(), selectedIndices);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertEquals(List.of(), selectedEvents(), bridge.events.toString());
    }

    // ------------------------------------------------------------------------- what it costs

    @Test
    void aQuietGroupAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindGroup("Small", "Medium", "Large");
        group.setSelectedIndex(1);
        frame();
        RadioButton medium = radios.get(1);
        scene.requestFocus(medium);
        frame();
        hover(medium);
        frame();

        // The dot, the hover and the focus fades are timed transitions that damage the row
        // on every frame they run for, and a measurement taken while one is mid-flight is a
        // measurement of the animation. Move time past all three before measuring.
        settleAnimations(medium);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            medium.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // A name resolved inside the hook, or the members() copy taken to count the set, is a
        // string or a list per damaged frame spent concluding that nothing moved, and every
        // frame of a hover fade is such a frame. This is the only place either is visible.
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            medium.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            medium.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a radio that did not move must cost no memory: the name is a held "
                        + "I18nString compared by reference, the set's numbers are read off the "
                        + "group's own list, the role is an enum and the verb is a bit");
    }
}

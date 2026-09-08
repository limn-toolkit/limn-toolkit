package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ToggleFacet;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.StringBundle;
import limn.scene.LayoutDirection;
import limn.scene.layout.Column;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Checkbox} becomes in the accessible tree: one node with the check-box or switch
 * role, named by the caption it holds, carrying a two-state toggle facet read from the model and
 * not from the animation, offering a toggle that reaches the application's own handler.
 *
 * <p>The survey row in ADR 039 §7 has the verdict, the role split and the facets right, and its
 * note is stale in both of its claims: the caption's getters and the enabled guard on {@code
 * toggle()} it says are missing were both added when the mechanism landed, so the only thing left
 * for this widget was the hook. What neither the row nor the lab says and the source settles is
 * pinned below: the toggle state is the {@code checked} field and never the eased progress, since
 * the handler has already fired when the slide begins; there is no third state; the caption is
 * not a child node, or it would be announced twice; and a programmatic {@code setChecked} is
 * silent to the application's listener but loud to the tree, because it invalidates.
 *
 * <p>Every case drives the public setters on a bound scene, or calls the scene from where a
 * bridge stands, and reads what the scene published; nothing here builds a tree.
 */
class CheckboxAccessibilityTest extends AccessibleComponentTestBase {

    private static final I18nString LOGGING = new I18nString("logging", "Enable logging");

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** One bundle for one key: everything else falls through to the English. */
    private static final StringBundle CAPTIONS = (key, locale) ->
            "logging".equals(key) && BRAZILIAN.equals(locale) ? "Ativar registo" : null;

    private Checkbox checkbox;

    /** The column the checkbox is bound in, for the cases that disable or re-read the container. */
    private Column root;

    /** Every value the application's handler was given, in order. */
    private final List<Boolean> handled = new ArrayList<>();

    /** How many times the application's handler ran. */
    private final AtomicInteger changes = new AtomicInteger();

    @AfterEach
    void resetLanguage() {
        I18n.removeBundle(CAPTIONS);
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One checkbox as the only control in a column, so it keeps its own measured box. */
    private void bindCheckbox(Checkbox under) {
        checkbox = under;
        checkbox.onChange(value -> {
            handled.add(value);
            changes.incrementAndGet();
        });
        root = new Column();
        root.add(checkbox);
        bind(root);
    }

    /** Moves the pointer to the centre of the checkbox's box, as a hover does. */
    private void hover() {
        scene.mouseMoved(checkbox.localToSceneX() + checkbox.width() / 2,
                checkbox.localToSceneY() + checkbox.height() / 2);
        scene.inputBatchEnded();
    }

    /** @return every checked-state event raised so far, in order */
    private List<AccessibleEvent> checkedEvents() {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED
                    && event.state() == Accessible.State.CHECKED) {
                found.add(event);
            }
        }
        return found;
    }

    /** @return whether any node in {@code tree} carries {@code role} */
    private static boolean anyNodeIs(AccessibleTree tree, Accessible.Role role) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                return true;
            }
        }
        return false;
    }

    /** Asserts everything about the node that is the same for both variants. */
    private void assertOneQuietToggle(AccessibleNode node) {
        assertEquals("Wrap lines", node.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(),
                "the name is the caption, which decides the attribute one platform writes it into"
                        + describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "a checkbox is focusable from its constructor" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertEquals(new ToggleFacet(ToggleFacet.State.OFF), node.toggle(),
                "unchecked from the constructor" + describe(tree()));
        assertFalse(node.has(Accessible.State.CHECKED), describe(tree()));
        assertFalse(node.has(Accessible.State.MIXED),
                "there is no third state and the facet never says there is" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.TOGGLE),
                "the one verb the widget declares" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS),
                "and the two the walk adds for every focusable widget" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.PRESS),
                "a toggle is not a press" + describe(tree()));
        assertNull(node.actions().keyBinding(),
                "Space and Enter are the platform's own convention for a toggle, not an accelerator");
        assertNull(node.value(), describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.expand(), describe(tree()));
        assertNull(node.selectionItem(), describe(tree()));
        assertEquals(List.of(), node.relations(), describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "the indicator and the caption are one control" + describe(tree()));
        assertFalse(anyNodeIs(tree(), Accessible.Role.LABEL),
                "the caption is not a second node, or a reader would hear it twice"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ what it is

    @Test
    void aBoxPublishesOneCheckBoxNodeAndASwitchOneSwitchNode() {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        AccessibleNode box = node(Accessible.Role.CHECK_BOX);
        assertOneQuietToggle(box);
        assertFalse(anyNodeIs(tree(), Accessible.Role.SWITCH), describe(tree()));

        bindCheckbox(new Checkbox(Checkbox.Variant.SWITCH, "Wrap lines"));
        AccessibleNode toggle = node(Accessible.Role.SWITCH);
        assertOneQuietToggle(toggle);
        assertFalse(anyNodeIs(tree(), Accessible.Role.CHECK_BOX),
                "the variant decides the role and nothing else about the node" + describe(tree()));
    }

    @Test
    void theBoxIsTheWholeRowInBothDirectionsAndTheIndicatorAloneWithoutACaption() {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        AccessibleNode node = node(Accessible.Role.CHECK_BOX);
        float labelledWidth = checkbox.width();

        assertEquals(checkbox.localToSceneX(), node.x(), describe(tree()));
        assertEquals(checkbox.localToSceneY(), node.y(), describe(tree()));
        assertEquals(checkbox.width(), node.width(), describe(tree()));
        assertEquals(checkbox.height(), node.height(),
                "the box is the widget's own, which is exactly what a pointer hits"
                        + describe(tree()));
        assertNotEquals(checkbox.height() + 2 * (Strokes.FOCUS_GAP_INDICATOR
                        + Strokes.FOCUS_RING_THIN / 2), node.height(),
                "paintOutset declares how far the focus ring's ink reaches for damage; it is not "
                        + "the operable rectangle and must never be folded into the bounds"
                        + describe(tree()));

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode mirrored = node(Accessible.Role.CHECK_BOX);
        assertEquals(checkbox.localToSceneX(), mirrored.x(), describe(tree()));
        assertEquals(checkbox.localToSceneY(), mirrored.y(), describe(tree()));
        assertEquals(checkbox.width(), mirrored.width(),
                "reading right to left translates the indicator inside the row; the row itself "
                        + "is the same box" + describe(tree()));
        assertEquals(checkbox.height(), mirrored.height(), describe(tree()));

        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, ""));
        AccessibleNode bare = node(Accessible.Role.CHECK_BOX);
        assertEquals(checkbox.width(), bare.width(),
                "with no caption the row is the indicator, and so is the node" + describe(tree()));
        assertTrue(bare.width() < labelledWidth,
                "the fixture has to actually lose the caption's column: " + bare.width()
                        + " against " + labelledWidth);
    }

    // ------------------------------------------------------------------------------ the state

    @Test
    void setCheckedIsSilentToTheApplicationAndLoudToTheTree() {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        long id = node(Accessible.Role.CHECK_BOX).id();

        checkbox.setChecked(true);
        frame();

        AccessibleNode on = node(Accessible.Role.CHECK_BOX);
        assertEquals(new ToggleFacet(ToggleFacet.State.ON), on.toggle(), describe(tree()));
        assertTrue(on.has(Accessible.State.CHECKED),
                "the builder derives the bit from the facet" + describe(tree()));
        List<AccessibleEvent> raised = checkedEvents();
        assertEquals(1, raised.size(), "one flip, one event: " + bridge.events);
        assertEquals(id, raised.get(0).nodeId(), bridge.events.toString());
        assertEquals(Boolean.TRUE, raised.get(0).newValue(), bridge.events.toString());
        assertEquals(0, changes.get(),
                "setChecked never notifies the application; a reader still hears the flip "
                        + "because the setter invalidates and the difference is what speaks");

        bridge.events.clear();
        checkbox.setChecked(false);
        frame();

        assertEquals(new ToggleFacet(ToggleFacet.State.OFF),
                node(Accessible.Role.CHECK_BOX).toggle(), describe(tree()));
        raised = checkedEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(Boolean.FALSE, raised.get(0).newValue(), bridge.events.toString());
        assertEquals(0, changes.get(), "still silent to the application");
    }

    @Test
    void theToggleStateIsTheModelAndNotTheAnimation() {
        bindCheckbox(new Checkbox(Checkbox.Variant.SWITCH, "Wrap lines"));

        checkbox.setChecked(true);
        frame();

        Assumptions.assumeTrue(checkbox.animationProgress() < 1,
                "the slide finished inside one frame, so this run cannot tell the two apart");
        assertEquals(new ToggleFacet(ToggleFacet.State.ON),
                node(Accessible.Role.SWITCH).toggle(),
                "the thumb is still sliding and the handler has already been given true: a hook "
                        + "that read the eased progress would publish OFF after the fact"
                        + describe(tree()));
    }

    @Test
    void aQuietFrameAndAHoverFadeSayNothing() {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        checkbox.setChecked(true);
        frame();
        AccessibleNode before = node(Accessible.Role.CHECK_BOX);
        int published = bridge.published.size();
        bridge.events.clear();

        frame();

        assertEquals(published, bridge.published.size(), "nothing moved, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and nothing was said: " + bridge.events);

        hover();
        for (int i = 0; i < 12; i++) {
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "the hover fade damages the row on every frame it runs for, and a damaged frame "
                        + "that changes nothing a reader hears publishes nothing");
        assertTrue(bridge.events.isEmpty(),
                "a name handed over resolved would move on every one of those frames: "
                        + bridge.events);
        AccessibleNode after = node(Accessible.Role.CHECK_BOX);
        assertEquals(before.name(), after.name(), describe(tree()));
        assertEquals(before.toggle(), after.toggle(), describe(tree()));
        assertEquals(before.id(), after.id(), describe(tree()));
    }

    // ---------------------------------------------------------------------------- the action

    @Test
    void aToggleFromTheBridgeFiresTheHandlerOnceAndIsHeardAsAStateChange() throws Exception {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        long id = node(Accessible.Role.CHECK_BOX).id();

        assertTrue(perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE),
                "accepted, which is not the same as done");

        assertEquals(List.of(true), handled,
                "the hook reaches toggle(), which is what a click and a Space press reach, and "
                        + "toggle() is what tells the application; setChecked would not have");
        assertTrue(checkbox.isChecked());

        frame();

        List<AccessibleEvent> raised = checkedEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(id, raised.get(0).nodeId(), bridge.events.toString());
        assertEquals(Boolean.TRUE, raised.get(0).newValue(), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press and nothing else; for a toggle the state change "
                        + "is the acknowledgement on all three platforms: " + bridge.events);

        bridge.events.clear();
        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(true, false), handled,
                "the application's own listener is still in the slot; nothing replaced it");
        assertFalse(checkbox.isChecked());
        raised = checkedEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(Boolean.FALSE, raised.get(0).newValue(), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void aDisabledCheckboxRefusesTheToggleAndStaysInTheTree() throws Exception {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        checkbox.setEnabled(false);
        frame();

        AccessibleNode node = node(Accessible.Role.CHECK_BOX);
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the keyboard does not reach a disabled control, and the tree agrees with it"
                        + describe(tree()));
        assertEquals(Accessible.Role.CHECK_BOX, node.role(),
                "a disabled checkbox is heard as disabled rather than vanishing" + describe(tree()));
        bridge.events.clear();

        perform(node.id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();

        assertEquals(0, changes.get(), "a disabled control was operated");
        assertFalse(checkbox.isChecked(), "and its state moved");
        assertEquals(List.of(), checkedEvents(), bridge.events.toString());

        // The same checkbox with its own flag true, inside a container that is not: the widget's
        // own guard passes and the scene's ancestor gate is what refuses, as the keyboard does.
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        root.setEnabled(false);
        frame();

        assertTrue(checkbox.isEnabled(), "the fixture has to leave the checkbox's own flag alone");
        assertFalse(node(Accessible.Role.CHECK_BOX).has(Accessible.State.ENABLED),
                "the bit is inherited down the walk" + describe(tree()));
        bridge.events.clear();

        perform(node(Accessible.Role.CHECK_BOX).id(), Accessible.Action.TOGGLE,
                Accessible.Argument.NONE);
        frame();

        assertEquals(0, changes.get(),
                "a control inside a disabled container is one the keyboard refuses too");
        assertFalse(checkbox.isChecked());
        assertEquals(List.of(), checkedEvents(), bridge.events.toString());
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        long id = node(Accessible.Role.CHECK_BOX).id();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1));
        perform(id, Accessible.Action.EXPAND, Accessible.Argument.NONE);
        frame();

        assertEquals(0, changes.get(),
                "the hook answers false for everything but TOGGLE, and false runs nothing");
        assertFalse(checkbox.isChecked());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertEquals(List.of(), checkedEvents(), bridge.events.toString());
    }

    // ------------------------------------------------------------------------------ the name

    @Test
    void theNameFollowsTheSubtreesLanguageAndNothingElseMoves() {
        I18n.addBundle(CAPTIONS);
        I18n.setLocale(Locale.ENGLISH);
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, LOGGING));
        long id = node(Accessible.Role.CHECK_BOX).id();
        assertEquals("Enable logging", node(Accessible.Role.CHECK_BOX).name(), describe(tree()));

        root.setLocale(BRAZILIAN);
        frame();

        AccessibleNode renamed = node(Accessible.Role.CHECK_BOX);
        assertEquals("Ativar registo", renamed.name(),
                "the name is the held source re-resolved under the subtree's language, which a "
                        + "string resolved inside the hook could not be" + describe(tree()));
        assertEquals(BRAZILIAN, renamed.locale(), describe(tree()));
        assertEquals(id, renamed.id(), "same node" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "one name moved, so one event: " + bridge.events);
        AccessibleEvent event = bridge.events.stream()
                .filter(each -> each.type() == AccessibleEvent.Type.NAME_CHANGED)
                .findFirst().orElseThrow();
        assertEquals("Enable logging", event.oldValue(), bridge.events.toString());
        assertEquals("Ativar registo", event.newValue(), bridge.events.toString());
        assertEquals(List.of(), checkedEvents(),
                "a rename says nothing about the state: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                bridge.events.toString());
    }

    @Test
    void aTooltipDescribesACaptionedCheckboxAndNamesABareOne() {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        checkbox.setTooltip("Break long lines at the window's edge");
        frame();

        AccessibleNode captioned = node(Accessible.Role.CHECK_BOX);
        assertEquals("Wrap lines", captioned.name(),
                "the tooltip never overwrites the caption" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, captioned.nameFrom(), describe(tree()));
        assertEquals("Break long lines at the window's edge", captioned.description(),
                "the walk's free default puts it in the description when a name exists; the hook "
                        + "never writes the description itself" + describe(tree()));

        Checkbox bare = new Checkbox(Checkbox.Variant.SWITCH, "");
        bare.setTooltip("Enable logging");
        bindCheckbox(bare);

        AccessibleNode fromTooltip = node(Accessible.Role.SWITCH);
        assertEquals("Enable logging", fromTooltip.name(),
                "an empty caption is handed over all the same, and the walk's default is aware "
                        + "that it is empty, which is what lets the tooltip name the node"
                        + describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, fromTooltip.nameFrom(), describe(tree()));
        assertEquals("", fromTooltip.description(),
                "one string is not both the name and the description" + describe(tree()));

        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, ""));

        AccessibleNode nameless = node(Accessible.Role.CHECK_BOX);
        assertEquals("", nameless.name(),
                "with neither a caption nor a tooltip the name is empty: the hook does not invent "
                        + "one, because a reader announcing \"check box\" for a control that "
                        + "means something is a defect the application has to fix with a tooltip "
                        + "or setAccessibleName, and a fabricated word would hide it"
                        + describe(tree()));
        assertTrue(nameless.has(Accessible.State.FOCUSABLE),
                "it is still a tab stop, so it is still a node" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ identity

    @Test
    void theNodeKeepsItsIdentifierThroughEveryStateItOwns() {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));
        long id = node(Accessible.Role.CHECK_BOX).id();

        checkbox.setChecked(true);
        frame();
        assertEquals(id, node(Accessible.Role.CHECK_BOX).id(), "checked" + describe(tree()));

        checkbox.setEnabled(false);
        frame();
        assertEquals(id, node(Accessible.Role.CHECK_BOX).id(), "disabled" + describe(tree()));

        checkbox.setEnabled(true);
        frame();
        assertEquals(id, node(Accessible.Role.CHECK_BOX).id(), "enabled again" + describe(tree()));

        scene.requestFocus(checkbox);
        frame();
        assertEquals(id, node(Accessible.Role.CHECK_BOX).id(), "focused" + describe(tree()));

        hover();
        frame();
        assertEquals(id, node(Accessible.Role.CHECK_BOX).id(), "hovered" + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "identity is minted over the widget and none of this is the widget: "
                        + bridge.events);
    }

    // ------------------------------------------------------------------------------ the focus

    @Test
    void aCheckboxIsExactlyOneTabStopAndFocusIsReported() {
        bindCheckbox(new Checkbox(Checkbox.Variant.BOX, "Wrap lines"));

        assertEquals(List.of(node(Accessible.Role.CHECK_BOX).id()),
                nodesWith(Accessible.State.FOCUSABLE).stream().map(AccessibleNode::id).toList(),
                "the checkbox is the only tab stop in the tree, once" + describe(tree()));
        assertFalse(node(Accessible.Role.CHECK_BOX).has(Accessible.State.FOCUSED),
                describe(tree()));

        scene.requestFocus(checkbox);
        frame();

        assertTrue(node(Accessible.Role.CHECK_BOX).has(Accessible.State.FOCUSED),
                "the walk carries focus down; the hook declares no state of its own"
                        + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.FOCUS_CHANGED),
                bridge.events.toString());
    }

    // ------------------------------------------------------------------------- what it costs

    @Test
    void aQuietCheckboxAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindCheckbox(new Checkbox(Checkbox.Variant.SWITCH, "Wrap lines"));
        checkbox.setChecked(true);
        frame();
        scene.requestFocus(checkbox);
        frame();
        hover();
        frame();

        // The slide, the hover and the focus fades are timed transitions that damage the row
        // on every frame they run for, and a measurement taken while one is mid-flight is a
        // measurement of the animation. Move time past all three before measuring.
        settleAnimations(checkbox);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            checkbox.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // A name resolved inside the hook, or the variable-argument action call, is a string or
        // an array per damaged frame spent concluding that nothing moved, and every frame of a
        // hover fade is such a frame. This is the only place either is visible.
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            checkbox.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            checkbox.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a checkbox that did not move must cost no memory: the name is a held "
                        + "I18nString compared by reference, the role and the facet are enums and "
                        + "the verb is a bit");
    }
}

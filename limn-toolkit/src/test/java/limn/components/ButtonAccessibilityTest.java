package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.layout.Column;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * What a {@link Button} becomes in the accessible tree: one node with the button role, named by
 * the caption it holds, offering a press that reaches the application's own action.
 *
 * <p>The survey row in ADR 039 §7 has the verdict right and is wrong about one state and silent
 * about another. It promises {@code DEFAULT} "when it is a dialog's default", and a button cannot
 * produce that bit: the dialog records that it has a default and which result it resolves, keeps
 * no reference to the button it made, and answers Enter without going through the button's action
 * at all, so the fact is the dialog's and belongs to the dialog's own step. And an implementer
 * reading §1.2's state list would publish {@code PRESSED} from the armed flag; the case below that
 * clicks with the pointer pins that it is never published, because no platform table maps it, a
 * press would cost two republished trees for a bit nobody reads, and a press from an assistive
 * technology never arms, so the bit would be true on one path and false on the other.
 *
 * <p>Every case drives the public setters on a bound scene, or calls the scene from where a
 * bridge stands, and reads what the scene published; nothing here builds a tree.
 */
class ButtonAccessibilityTest extends AccessibleComponentTestBase {

    /** An icon that draws nothing: the fixture for an icon-only button. */
    private static final Icon BLANK = new Icon() {
        @Override
        public Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException("never rasterized");
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark) {
            // Drawn by the frame the fixture renders, and drawing nothing is the point.
        }
    };

    private Button button;

    /** How many times the application's action ran. */
    private final AtomicInteger pressed = new AtomicInteger();

    // ------------------------------------------------------------------------------ the fixture

    /** One button as the only control in a column, so it keeps its own measured box. */
    private void bindButton(Button under) {
        button = under;
        button.onAction(pressed::incrementAndGet);
        Column root = new Column();
        root.add(button);
        bind(root);
    }

    /** @return the button's node, which is the one and only button in the tree */
    private AccessibleNode control() {
        return node(Accessible.Role.BUTTON);
    }

    /** Delivers one click at the centre of the button's box, as a pointer does, and frames it. */
    private void click() {
        float x = button.localToSceneX() + button.width() / 2;
        float y = button.localToSceneY() + button.height() / 2;
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
        frame();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        drive(scene).inputBatchEnded();
        frame(); // a frame with the button held, so a published PRESSED would be seen
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        drive(scene).inputBatchEnded();
        frame();
    }

    /** @return whether any tree published so far carried {@code state} on a button */
    private boolean everPublished(Accessible.State state) {
        for (AccessibleTree tree : bridge.published) {
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.role() == Accessible.Role.BUTTON && node.has(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------ what it is

    @Test
    void aButtonPublishesOneNamedOperableNode() {
        bindButton(new Button("OK"));

        AccessibleNode node = control();
        assertEquals("OK", node.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(),
                "the name is the caption, which decides the attribute one platform writes it into"
                        + describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "a button is focusable from its constructor" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.PRESS),
                "the one verb the widget declares" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS),
                "and the two the walk adds for every focusable widget" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertNull(node.actions().keyBinding(),
                "Enter and Space are the platform's generic activation, not an accelerator");
        assertNull(node.toggle(), "a button is not a toggle" + describe(tree()));
        assertNull(node.value(), describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.expand(), describe(tree()));
        assertNull(node.selectionItem(), describe(tree()));
        assertEquals(List.of(), node.relations(), describe(tree()));
        assertEquals(List.of(), childrenOf(node), "a button holds nothing" + describe(tree()));

        assertEquals(button.localToSceneX(), node.x(), describe(tree()));
        assertEquals(button.localToSceneY(), node.y(), describe(tree()));
        assertEquals(button.width(), node.width(), describe(tree()));
        assertEquals(button.height(), node.height(),
                "the box is the fill, which is what a user aims at" + describe(tree()));
        assertNotEquals(button.height() + 2 * Strokes.FOCUS_RING_OUTSET, node.height(),
                "paintOutset declares how far the focus ring's ink reaches for damage; it is not "
                        + "the operable rectangle and must never be folded into the bounds"
                        + describe(tree()));
    }

    // ---------------------------------------------------------------------------- the action

    @Test
    void aPressFromTheBridgeFiresTheActionExactlyOnceAndIsAcknowledged() throws Exception {
        bindButton(new Button("OK"));
        long id = control().id();

        assertTrue(perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE),
                "accepted, which is not the same as done");

        assertEquals(1, pressed.get(),
                "the hook reaches the same private slot a click and a Space release reach");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "and the scene acknowledges a press it performed: " + bridge.events);
        assertNull(scene.focusedWidget(),
                "a press does not pull focus: a reader that wants focus has the focus verb, and "
                        + "a tool-bar button that stole focus from a text field would be worse");

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertEquals(2, pressed.get(),
                "the application's own Runnable is still in the slot; nothing replaced it");
        assertEquals(2, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void aDisabledButtonRefusesThePressAndStaysInTheTree() throws Exception {
        bindButton(new Button("OK"));
        button.setEnabled(false);
        frame();

        AccessibleNode node = control();
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the keyboard does not reach a disabled control, and the tree agrees with it"
                        + describe(tree()));
        assertEquals(Accessible.Role.BUTTON, node.role(),
                "the predicate asks about declared facts and the role is one, so a disabled "
                        + "button is heard as disabled rather than vanishing" + describe(tree()));

        perform(node.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertEquals(0, pressed.get(), "a disabled control was operated");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());

        // The same button with its own flag true, inside a container that is not: the widget's
        // own guard passes and the scene's ancestor gate is what refuses, as the keyboard does.
        Column around = new Column();
        button = new Button("OK");
        button.onAction(pressed::incrementAndGet);
        around.add(button);
        bind(around);
        around.setEnabled(false);
        frame();

        assertTrue(button.isEnabled(), "the fixture has to leave the button's own flag alone");
        perform(control().id(), Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertEquals(0, pressed.get(),
                "a control inside a disabled container is one the keyboard refuses too");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindButton(new Button("OK"));
        long id = control().id();

        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.EXPAND, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1));

        assertEquals(0, pressed.get(),
                "the hook answers false for everything but PRESS, and false runs nothing");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    // ------------------------------------------------------------------------------ the name

    @Test
    void aChangedCaptionRenamesTheSameNodeOnceAndAnUnchangedOneSaysNothing() {
        bindButton(new Button("OK"));
        long id = control().id();

        button.setText("Save");
        frame();

        assertEquals("Save", control().name(), describe(tree()));
        assertEquals(id, control().id(), "the node keeps its identifier" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "one change, one event: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "a rename is not a reshaping: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());

        int published = bridge.published.size();
        bridge.events.clear();

        button.setText("Save");
        frame();
        button.setText(I18nString.literal("Save"));
        frame();

        assertEquals(published, bridge.published.size(),
                "setText short-circuits on an equal literal and on an equal value, so the "
                        + "reference the name is compared by never moves and nothing is "
                        + "republished: a caption re-set on every refresh is free");
        assertTrue(bridge.events.isEmpty(), "and nothing was said: " + bridge.events);
    }

    @Test
    void aTooltipDescribesACaptionedButtonAndNamesAnIconOnlyOne() {
        bindButton(new Button("OK"));
        button.setTooltip("Commit the form");
        frame();

        AccessibleNode captioned = control();
        assertEquals("OK", captioned.name(),
                "the tooltip never overwrites the caption" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, captioned.nameFrom(), describe(tree()));
        assertEquals("Commit the form", captioned.description(),
                "the walk's free default puts it in the description when a name exists; the "
                        + "hook never writes the description itself" + describe(tree()));

        Button iconOnly = new Button("");
        iconOnly.setIcon(BLANK);
        iconOnly.setTooltip("Search");
        bindButton(iconOnly);

        AccessibleNode fromTooltip = control();
        assertEquals("Search", fromTooltip.name(),
                "an empty caption is handed over all the same, and the walk's default is aware "
                        + "that it is empty, which is what lets the tooltip name the node"
                        + describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, fromTooltip.nameFrom(), describe(tree()));
        assertEquals("", fromTooltip.description(),
                "one string is not both the name and the description" + describe(tree()));
        assertEquals(List.of(), childrenOf(fromTooltip),
                "the icon has no name and no operation; it is paint, not a node"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the states

    @Test
    void aButtonIsExactlyOneTabStopAndFocusIsReported() {
        bindButton(new Button("OK"));

        assertEquals(List.of(control().id()),
                nodesWith(Accessible.State.FOCUSABLE).stream().map(AccessibleNode::id).toList(),
                "the button is the only tab stop in the tree, once" + describe(tree()));
        assertFalse(control().has(Accessible.State.FOCUSED), describe(tree()));

        scene.requestFocus(button);
        frame();

        assertTrue(control().has(Accessible.State.FOCUSED),
                "the walk carries focus down; the hook declares no state of its own"
                        + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.FOCUS_CHANGED), bridge.events.toString());
    }

    // ------------------------------------------------------------------------- what it costs

    @Test
    void aQuietButtonAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindButton(new Button("OK"));

        button.setSecondary(true);
        frame();
        button.setIcon(BLANK);
        frame();
        int beforeTheClick = bridge.published.size();

        click();

        assertEquals(1, pressed.get(), "the fixture has to actually click");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a press the user made is not acknowledged: it leaves no difference between two "
                        + "snapshots, and §11 says so: " + bridge.events);
        assertFalse(everPublished(Accessible.State.PRESSED),
                "the armed visual is never published: no platform maps it, it would republish "
                        + "the tree twice per click, and a press from a reader never arms");
        assertSame(button, scene.focusedWidget(), "click-to-focus is the scene's, not the button's");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.FOCUS_CHANGED),
                "the one thing the click changed for a reader: " + bridge.events);
        assertEquals(beforeTheClick + 1, bridge.published.size(),
                "one snapshot for the focus, and none for the hover, the arm or the release");

        // The hover and focus fades are timed transitions that damage the button on every
        // frame they run for, and a measurement taken while one is mid-flight is a measurement
        // of the animation. Move time past both before measuring.
        settleAnimations(button);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            button.invalidate();
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
            button.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            button.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a button that did not move must cost no memory: the name is a held "
                        + "I18nString compared by reference, the role is an enum and the verb is "
                        + "a bit");
    }
}

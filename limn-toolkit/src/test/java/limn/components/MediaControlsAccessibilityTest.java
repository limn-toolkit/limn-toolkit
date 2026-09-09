package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.input.Keys;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.AllocationProbe;
import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link MediaControls} becomes in the accessible tree: one horizontal {@code TOOL_BAR}
 * with no name of its own, whose children are the controls and never the row or the boxes
 * between them, and whose two icon buttons are plain {@code BUTTON}s named by the tooltips the
 * bar keeps in step with the state, pressed from a screen reader through the same activation a
 * click reaches.
 *
 * <p>The bar paints its backdrop, so a hook that stopped declaring the role would delete it as
 * scaffolding and the walk would name a toolkit class in an application's log; the log is
 * captured around every test and checked after each, because the walk warns once per class for
 * the life of the virtual machine and whichever test runs first is the one that would see it.
 *
 * <p>Three of the cases pin where ADR 039 §7's row was short. "Named by tooltips that already
 * flip with the play state" was true one frame late: the tooltips were written only inside
 * {@code refresh()}, which runs from the paint and the poll, and the publish step runs before the
 * paint, so the first tree carried a focusable play button with no name and a mute button that
 * was nameless until the sound cluster appeared. The names are seeded in the constructor now and
 * the first published tree is what is asserted; the mute button's name, which is the bar's own
 * state rather than the view's, is also written wherever that state changes, and
 * {@link MediaControlsMuteButtonAccessibilityTest} pins that it is exact in the frame that
 * publishes the change. The row also reads as if the owner handed its
 * buttons their role; it cannot, because its only child is the row and the walk asks the direct
 * parent, so the buttons describe themselves through their shared private base. And the row
 * omits the two sliders and the slots entirely; the slots are pinned here, and the sliders are
 * {@link Slider}'s own step.
 *
 * <p>The play button has cases of its own below, because it is the one control of the family that
 * an application is certain to ship: that it is a plain button with no facet, that its box is
 * the square it measured and not the ring it paints around it, that a container's disabled flag
 * refuses a press its own flag would allow, that it is a tab stop exactly once, and that a click
 * from a pointer is never acknowledged and never publishes the armed visual. The button is a
 * private class reached through the public child lists, so nothing here depends on its name.
 *
 * <p>TODO, now that Slider is described and this class's own step: an assistive technology's
 * {@code SET_VALUE} on the scrub bar with a seekable source must leave the thumb following the
 * playhead afterwards, which means the bar's commit fired and {@code dragging} cleared; Slider's
 * hook fires the commit after every accepted verb, so what is left is to pin it here through the
 * bar. And the published value must move no faster than the record's rounding allows, since
 * {@code refresh()} writes the bar unrounded at ten hertz with a resolution of a thousandth of
 * the length; the rounding belongs to the writer that advances the value, which is this bar's
 * poll and not Slider.
 *
 * <p>Every case drives the public API of {@link MediaControls}, {@link VideoView} and
 * {@code Widget} on a bound scene, or calls the scene from where a bridge stands, and reads back
 * what the scene published. Nothing constructs a node.
 */
class MediaControlsAccessibilityTest extends AccessibleComponentTestBase {

    private VideoView view;
    private MediaControls controls;

    /** Every record the walk logged while a test was running; see the class comment. */
    private final List<LogRecord> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger walkLogger;

    @BeforeEach
    void captureTheWalksLog() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    @AfterEach
    void theBarIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
            String message = String.valueOf(record.getMessage()) + java.util.Arrays.toString(
                    record.getParameters());
            assertFalse(message.contains("limn.components.MediaControls"),
                    "the bar paints and declares a role, and its buttons are focusable and declare "
                            + "one, so the walk must never name any of them: " + message);
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * The bar over a view holding a paused source, as the scene's root, with the first frame
     * rendered. A source rather than an empty view, because a view with nothing installed answers
     * {@code isPaused()} false and the play button then reads "Pause", disabled, beside the pause
     * glyph; that answer is the view's and is pinned as such below.
     */
    private void bindControls() {
        view = new VideoView().setSource(new FakeVideo());
        controls = new MediaControls(view);
        controls.setOnRefresh(() -> ticks++); // the heartbeat an injected control rides
        bind(controls);
    }

    /** @return whether any node in {@code tree} is a group with no name: a wrapper that survived */
    private static boolean anyUnnamedGroup(AccessibleTree tree) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.GROUP && node.name().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** @return the one tool-bar node in the current tree */
    private AccessibleNode toolbar() {
        return node(Accessible.Role.TOOL_BAR);
    }

    /** @return the toolbar's children's names, in tree order */
    private List<String> childNames() {
        List<String> names = new ArrayList<>();
        for (AccessibleNode child : childrenOf(toolbar())) {
            names.add(child.name());
        }
        return names;
    }

    /** @return the one node with {@code id} in {@code tree} */
    private static AccessibleNode byId(AccessibleTree tree, long id) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).id() == id) {
                return tree.node(i);
            }
        }
        throw new AssertionError("no node with id " + id + " in " + describe(tree));
    }

    /** @return every event of {@code type} on {@code nodeId} raised so far, in order */
    private List<AccessibleEvent> eventsOf(AccessibleEvent.Type type, long nodeId) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == type && event.nodeId() == nodeId) {
                found.add(event);
            }
        }
        return found;
    }

    /**
     * Damages the bar, renders, waits for the poll tick and renders again. The bar learns of the
     * view on its heartbeat, which is the poll alone -- a paint announces nothing, so it refreshes
     * nothing and only arms the poll -- and the tree is published before the paint of the same
     * frame; so the tick is where the bar catches up and the second frame is where the tree does.
     * The wait is the bar's own poll interval, at most, which is the latency the window pays too.
     */
    private void heartbeat() {
        controls.invalidate();
        frame();
        long before = ticks;
        ui.pumpUntil(() -> ticks > before);
        frame();
    }

    /** Poll ticks seen so far: the bar's guarded writes leave no other trace of an unchanged tick. */
    private long ticks;

    /**
     * @return the play button, reached through the public child lists: the bar's only child is
     *         the row and the row's first child is the play button, as {@code rebuild()} orders it
     */
    private Widget playButton() {
        return controls.children().get(0).children().get(0);
    }

    /** Delivers one click at the centre of {@code target}'s box, as a pointer does, and frames it. */
    private void click(Widget target) {
        float x = target.localToSceneX() + target.width() / 2;
        float y = target.localToSceneY() + target.height() / 2;
        scene.mouseMoved(x, y);
        scene.inputBatchEnded();
        frame();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.inputBatchEnded();
        frame(); // a frame with the button held, so a published PRESSED would be seen
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
        frame();
    }

    /** @return whether any tree published so far carried {@code state} on a button */
    private boolean everPublishedOnAButton(Accessible.State state) {
        for (AccessibleTree published : bridge.published) {
            for (int i = 0; i < published.nodeCount(); i++) {
                AccessibleNode node = published.node(i);
                if (node.role() == Accessible.Role.BUTTON && node.has(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------- the shape

    @Test
    void aFreshBarIsOneHorizontalUnnamedToolBarWhoseChildrenAreTheControlsAndNotTheBoxes() {
        bindControls();

        AccessibleNode bar = toolbar();
        assertEquals(0, bar.parent(), "the root's node hangs under the window" + describe(tree()));
        assertEquals("", bar.name(), "the bar holds nothing to name itself with" + describe(tree()));
        assertEquals("", bar.description(), describe(tree()));
        assertTrue(bar.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertFalse(bar.has(Accessible.State.VERTICAL), describe(tree()));
        assertFalse(bar.has(Accessible.State.FOCUSABLE),
                "the bar is not a tab stop; its controls are" + describe(tree()));
        assertTrue(bar.actions() == null || bar.actions().actions().isEmpty(),
                "no verb of its own" + describe(tree()));
        assertFalse(anyUnnamedGroup(tree()),
                "the row, the sized box and the expanded wrapper are scaffolding and hoist; a "
                        + "wrapper that survived would be a group with no name. The two sliders "
                        + "were never groups: before Slider described itself they were focusable "
                        + "widgets with no role, published UNKNOWN under their tooltip names with "
                        + "the walk's once-per-VM warning, which this class's log filter did not "
                        + "look for; they are SLIDER nodes now" + describe(tree()));

        List<AccessibleNode> children = childrenOf(bar);
        assertFalse(children.isEmpty(), describe(tree()));
        assertEquals(Accessible.Role.BUTTON, children.get(0).role(), describe(tree()));
        assertEquals("Play", children.get(0).name(), describe(tree()));
        AccessibleNode last = children.get(children.size() - 1);
        assertEquals(Accessible.Role.LABEL, last.role(),
                "the clock is the trailing edge of the bar" + describe(tree()));
        assertEquals("0:00", last.name(),
                "with no duration and nothing to seek, the clock is a constant" + describe(tree()));

        assertEquals(controls.localToSceneX(), bar.x(), describe(tree()));
        assertEquals(controls.localToSceneY(), bar.y(), describe(tree()));
        assertEquals(controls.width(), bar.width(), describe(tree()));
        assertEquals(controls.height(), bar.height(), describe(tree()));
        for (AccessibleNode child : children) {
            assertTrue(child.x() >= bar.x() - 0.01f && child.y() >= bar.y() - 0.01f
                            && child.x() + child.width() <= bar.x() + bar.width() + 0.01f
                            && child.y() + child.height() <= bar.y() + bar.height() + 0.01f,
                    "every control lies inside the bar: " + child + describe(tree()));
        }
    }

    @Test
    void theRoleDoesNotFollowTheBackdrop() {
        view = new VideoView().setSource(new FakeVideo());
        controls = new MediaControls(view).setBackdrop(false);
        bind(controls);

        AccessibleNode bar = toolbar();
        assertTrue(bar.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertEquals("Play", childrenOf(bar).get(0).name(),
                "with no panel of its own the bar is still the bar that holds the transport"
                        + describe(tree()));

        controls.setBackdrop(true);
        frame();
        assertEquals(bar.id(), toolbar().id(), "the panel is paint, not identity");
    }

    @Test
    void theRelationToTheViewIsDroppedUntilTheViewPublishesANode() {
        bindControls();

        assertEquals(List.of(), toolbar().relations(),
                "the bar declares CONTROLLER_FOR its view, and the walk resolves a target to the "
                        + "node it published or drops the relation; the view is not in this tree "
                        + "at all, and a relation onto the window node would say nothing. Where "
                        + "the bar is the view's own child it does resolve, onto the VIDEO node, "
                        + "which limn.components.VideoViewAccessibilityTest pins"
                        + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the names

    @Test
    void theButtonsAndTheClockAreThereInTheFirstTreeBeforeAnyPaint() {
        bindControls();

        AccessibleTree first = bridge.published.get(0);
        assertEquals(1, bridge.published.size(),
                "one tree so far, published before the first paint" + describe(first));
        List<String> buttons = new ArrayList<>();
        AccessibleNode play = null;
        AccessibleNode mute = null;
        String clock = null;
        for (int i = 0; i < first.nodeCount(); i++) {
            AccessibleNode node = first.node(i);
            if (node.role() == Accessible.Role.BUTTON) {
                buttons.add(node.name());
                if (node.name().equals("Play")) {
                    play = node;
                } else if (node.name().equals("Mute")) {
                    mute = node;
                }
            } else if (node.role() == Accessible.Role.LABEL) {
                clock = node.name();
            }
        }
        assertEquals(List.of("Play", "Mute"), buttons,
                "the tree is published before the paint that used to run the first refresh(), so "
                        + "the first heartbeat runs in the constructor; an unnamed focusable "
                        + "button is the defect the gallery rule exists for" + describe(first));
        assertEquals(Accessible.NameFrom.TOOLTIP, play.nameFrom(),
                "the walk's free default, compared by reference" + describe(first));
        assertEquals("", play.description(),
                "one string is not both the name and the description" + describe(first));
        assertTrue(play.has(Accessible.State.ENABLED),
                "a source is something to play, and the first tree already says so"
                        + describe(first));
        assertEquals(Accessible.NameFrom.TOOLTIP, mute.nameFrom(), describe(first));
        assertEquals("0:00", clock,
                "and the clock is not an empty label that widens one paint later" + describe(first));

        frame();
        assertEquals(1, bridge.published.size(),
                "the first paint found nothing the constructor had not already written");
    }

    @Test
    void withNothingInstalledTheButtonSaysWhatTheGlyphPaintsAndIsDisabled() {
        view = new VideoView();
        controls = new MediaControls(view);
        bind(controls);

        List<AccessibleNode> children = childrenOf(toolbar());
        AccessibleNode play = children.get(0);
        assertEquals(Accessible.Role.BUTTON, play.role(), describe(tree()));
        assertFalse(play.has(Accessible.State.ENABLED),
                "nothing to play, so the button is offered disabled from the first tree"
                        + describe(tree()));
        assertEquals(view.isPaused() ? "Play" : "Pause", play.name(),
                "the name follows the view's own answer exactly as the glyph does. A view with "
                        + "nothing installed answers not paused today, so this reads Pause beside "
                        + "the pause bars; §7's row assumed Play, and which is right is the view's "
                        + "step to settle, not this one's" + describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, play.nameFrom(), describe(tree()));
    }

    @Test
    void thePlayButtonsNameFollowsTheViewOnTheHeartbeat() {
        bindControls();
        long id = node("Play").id();

        view.setPaused(false);
        heartbeat();

        assertEquals("Pause", byId(tree(), id).name(), describe(tree()));
        List<AccessibleEvent> renamed = eventsOf(AccessibleEvent.Type.NAME_CHANGED, id);
        assertEquals(1, renamed.size(), bridge.events.toString());
        assertEquals("Play", renamed.get(0).oldValue());
        assertEquals("Pause", renamed.get(0).newValue());

        view.setPaused(true);
        heartbeat();

        assertEquals("Play", byId(tree(), id).name(), describe(tree()));
        assertEquals(2, eventsOf(AccessibleEvent.Type.NAME_CHANGED, id).size(),
                bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "a name is not a rebuild: " + bridge.events);

        int published = bridge.published.size();
        bridge.events.clear();

        view.setPaused(true);
        heartbeat();

        assertEquals(published, bridge.published.size(),
                "the same answer again re-sets nothing: refresh() guards the tooltip on the state "
                        + "it last showed, and the static I18nString compares by reference, so a "
                        + "poll at ten hertz over a paused picture is not a rename at ten hertz");
        assertTrue(bridge.events.isEmpty(), "and nothing was said: " + bridge.events);
    }

    // -------------------------------------------------------------------------- the play button

    @Test
    void thePlayButtonIsOnePlainButtonWithNoFacetInsideItsOwnMeasuredBox() {
        bindControls();
        Widget widget = playButton();
        AccessibleNode play = node("Play");

        assertEquals(Accessible.Role.BUTTON, play.role(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, play.nameFrom(), describe(tree()));
        assertEquals("", play.description(), describe(tree()));
        assertTrue(play.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(play.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(play.has(Accessible.State.PRESSED), describe(tree()));
        assertTrue(play.actions().has(Accessible.Action.PRESS),
                "the one verb the family declares" + describe(tree()));
        assertTrue(play.actions().has(Accessible.Action.FOCUS),
                "and the two the walk adds for every focusable widget" + describe(tree()));
        assertTrue(play.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertNull(play.actions().keyBinding(),
                "Space and Enter are the platform's generic activation, not an accelerator");
        assertNull(play.toggle(),
                "not a toggle: the state is spoken through the name that flips, and a pressed bit "
                        + "beside it would announce a running film as \"Pause, not pressed\""
                        + describe(tree()));
        assertNull(play.value(), describe(tree()));
        assertNull(play.text(), describe(tree()));
        assertNull(play.expand(), describe(tree()));
        assertNull(play.selectionItem(), describe(tree()));
        assertEquals(List.of(), play.relations(), describe(tree()));
        assertEquals(AccessibleNode.NONE, play.firstChild(),
                "the triangle and the bars are paint carrying nothing the name does not"
                        + describe(tree()));

        assertEquals(widget.localToSceneX(), play.x(), 0.001f, describe(tree()));
        assertEquals(widget.localToSceneY(), play.y(), 0.001f, describe(tree()));
        assertEquals(widget.width(), play.width(), 0.001f,
                "the box is the square the button measured; the focus ring's outset is damage, "
                        + "not bounds" + describe(tree()));
        assertEquals(widget.height(), play.height(), 0.001f, describe(tree()));
        assertEquals(widget.width(), widget.height(), 0.001f, "a square");
        assertTrue(widget.width() > 2 * Strokes.FOCUS_RING_OUTSET,
                "the fixture has to be able to tell a box from a box grown by the ring");
    }

    @Test
    void aPressInsideADisabledContainerIsRefusedWhileTheButtonsOwnFlagStaysTrue()
            throws Exception {
        view = new VideoView().setSource(new FakeVideo());
        controls = new MediaControls(view);
        Column around = new Column();
        around.add(controls);
        bind(around);
        around.setEnabled(false);
        frame();
        Widget widget = playButton();
        AccessibleNode play = node("Play");

        assertTrue(widget.isEnabled(), "the fixture has to leave the button's own flag alone");
        assertFalse(play.has(Accessible.State.ENABLED),
                "the walk carries the container's flag down" + describe(tree()));
        assertFalse(play.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(view.isPaused());

        perform(play.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertTrue(view.isPaused(),
                "the button's own guard passes and the scene's ancestor gate is what refuses, as "
                        + "the keyboard does");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
        assertEquals("Play", byId(tree(), play.id()).name(), describe(tree()));
    }

    @Test
    void thePlayButtonIsATabStopExactlyOnceAndFocusIsReported() {
        bindControls();
        Widget widget = playButton();
        long id = node("Play").id();

        List<Long> tabStops = nodesWith(Accessible.State.FOCUSABLE).stream()
                .map(AccessibleNode::id).toList();
        assertEquals(1, tabStops.stream().filter(stop -> stop == id).count(),
                "the play button is a tab stop, once" + describe(tree()));
        assertFalse(node("Play").has(Accessible.State.FOCUSED), describe(tree()));

        scene.requestFocus(widget);
        frame();

        assertTrue(byId(tree(), id).has(Accessible.State.FOCUSED),
                "the walk carries focus down; the hook declares no state of its own"
                        + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.FOCUS_CHANGED), bridge.events.toString());

        // The published tab stops are the scene's traversal order and nothing else: walking Tab
        // once around the ring visits every FOCUSABLE node exactly once and comes back.
        List<Long> visited = new ArrayList<>();
        for (int i = 0; i < tabStops.size(); i++) {
            scene.focusTraverse(false);
            frame();
            List<AccessibleNode> focused = nodesWith(Accessible.State.FOCUSED);
            assertEquals(1, focused.size(), "one focus at a time" + describe(tree()));
            visited.add(focused.get(0).id());
        }
        assertEquals(tabStops.stream().sorted().toList(), visited.stream().sorted().toList(),
                "what is published FOCUSABLE is what Tab reaches, no more and no less"
                        + describe(tree()));
        assertEquals(id, visited.get(visited.size() - 1),
                "and the ring closes on the play button, where it started" + describe(tree()));
    }

    @Test
    void aClickFromAPointerFiresIsNotAcknowledgedAndNeverPublishesPressed() {
        bindControls();
        Widget widget = playButton();
        long id = node("Play").id();
        assertTrue(view.isPaused());
        int beforeTheClick = bridge.published.size();

        click(widget);

        assertFalse(view.isPaused(), "the fixture has to actually click");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a press the user made is not acknowledged: it leaves no difference between two "
                        + "snapshots, and §11 says so: " + bridge.events);
        assertFalse(everPublishedOnAButton(Accessible.State.PRESSED),
                "the armed visual is never published: no platform maps it, it would republish "
                        + "the tree twice per click, and a press from a reader never arms");
        assertSame(widget, scene.focusedWidget(),
                "click-to-focus is the scene's, not the button's");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.FOCUS_CHANGED), bridge.events.toString());
        assertEquals(1, eventsOf(AccessibleEvent.Type.NAME_CHANGED, id).size(),
                "the one other thing the click changed for a reader is the name: "
                        + bridge.events);
        assertEquals("Pause", byId(tree(), id).name(), describe(tree()));
        assertEquals(beforeTheClick + 2, bridge.published.size(),
                "one snapshot for the focus and one for the name, none for the hover, the arm or "
                        + "the release");
    }

    // ------------------------------------------------------------------------------ the presses

    @Test
    void aPressFromTheBridgeIsTheClickAndIsAcknowledged() throws Exception {
        bindControls();
        long id = node("Play").id();
        assertTrue(byId(tree(), id).has(Accessible.State.ENABLED),
                "a source enables the play button" + describe(tree()));
        assertTrue(byId(tree(), id).actions().has(Accessible.Action.PRESS), describe(tree()));
        assertTrue(view.isPaused(), "a fresh source starts paused");
        bridge.events.clear();

        assertTrue(perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE),
                "accepted, which is not the same as done");

        assertFalse(view.isPaused(),
                "the hook reaches the same activate() a click and a Space release reach");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
        frame();
        assertEquals("Pause", byId(tree(), id).name(),
                "activate() refreshes synchronously, so the frame the press buys already carries "
                        + "the new name" + describe(tree()));
        assertEquals(1, eventsOf(AccessibleEvent.Type.NAME_CHANGED, id).size(),
                bridge.events.toString());

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertTrue(view.isPaused(), "pressed again, it pauses again");
        assertEquals("Play", byId(tree(), id).name(), describe(tree()));
        assertEquals(2, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
        assertNull(scene.focusedWidget(), "a press does not pull focus");
    }

    @Test
    void aPressWithNothingToPlayIsRefusedAndMovesNothing() throws Exception {
        view = new VideoView();
        controls = new MediaControls(view);
        bind(controls);
        AccessibleNode play = childrenOf(toolbar()).get(0);
        boolean pausedBefore = view.isPaused();
        assertFalse(play.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(play.has(Accessible.State.FOCUSABLE),
                "the keyboard does not reach a disabled control and the tree agrees"
                        + describe(tree()));

        perform(play.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(pausedBefore, view.isPaused(), "a disabled control was operated");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
        assertEquals(play.name(), byId(tree(), play.id()).name(), describe(tree()));
    }

    @Test
    void otherVerbsAreRefused() throws Exception {
        bindControls();
        long id = node("Play").id();
        bridge.events.clear();

        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SELECT, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1));

        assertTrue(view.isPaused(), "the hook answers false for everything but PRESS");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void aPressOnMuteIsTheClickAndTheSetterReachesTheSameName() throws Exception {
        bindControls();
        controls.setSound(MediaControls.Sound.ON);
        frame();
        long id = node("Mute").id();
        assertTrue(byId(tree(), id).has(Accessible.State.FOCUSABLE), describe(tree()));
        bridge.events.clear();

        assertTrue(perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE));

        assertTrue(controls.isMuted(), "the hook reaches setMuted(!muted), as the click does");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
        // One frame and not the heartbeat: the mute is the bar's own state and the setter writes
        // the tooltip, so the tree does not wait for a paint to learn the name. The play button
        // above needs the heartbeat because its state is the view's.
        frame();
        assertEquals("Unmute", byId(tree(), id).name(), describe(tree()));
        assertEquals(1, eventsOf(AccessibleEvent.Type.NAME_CHANGED, id).size(),
                bridge.events.toString());

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertFalse(controls.isMuted());
        assertEquals("Mute", byId(tree(), id).name(), describe(tree()));

        controls.setMuted(true);
        frame();

        assertEquals("Unmute", byId(tree(), id).name(),
                "the public setter and the press are one path, so they publish one name"
                        + describe(tree()));
        assertEquals(3, eventsOf(AccessibleEvent.Type.NAME_CHANGED, id).size(),
                bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- the sound cluster

    @Test
    void aHiddenMuteButtonIsPublishedNamedAndIsNotATabStop() {
        bindControls();

        AccessibleNode hidden = node("Mute");
        long id = hidden.id();
        assertEquals(Accessible.Role.BUTTON, hidden.role(), describe(tree()));
        assertFalse(hidden.has(Accessible.State.VISIBLE),
                "under AUTO with no player the cluster steps aside" + describe(tree()));
        assertFalse(hidden.has(Accessible.State.SHOWING), describe(tree()));
        assertFalse(hidden.has(Accessible.State.FOCUSABLE),
                "a hidden control is never announced as a tab stop" + describe(tree()));
        assertFalse(hidden.actions().has(Accessible.Action.FOCUS),
                "and offers no focus verb" + describe(tree()));

        controls.setSound(MediaControls.Sound.ON);
        frame();

        AccessibleNode shown = byId(tree(), id);
        assertTrue(shown.has(Accessible.State.VISIBLE), describe(tree()));
        assertTrue(shown.has(Accessible.State.SHOWING), describe(tree()));
        assertTrue(shown.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(shown.actions().has(Accessible.Action.FOCUS), describe(tree()));
        assertEquals("Mute", shown.name(),
                "named in the same tree that shows it, not one paint later" + describe(tree()));

        controls.setSound(MediaControls.Sound.OFF);
        frame();

        AccessibleNode gone = byId(tree(), id);
        assertFalse(gone.has(Accessible.State.VISIBLE), describe(tree()));
        assertFalse(gone.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "hidden is a state, not a different node: " + bridge.events);
    }

    // ------------------------------------------------------------------------------- what it costs

    @Test
    void aQuietBarOverAPausedViewAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindControls();
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            controls.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot: the tooltips are static "
                        + "strings compared by reference and the clock's literal is guarded");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            controls.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            controls.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a bar that did not move must cost no memory: a role, a bit, a "
                        + "relation into a grown array, and names handed over by reference");
    }

    // ------------------------------------------------------------------------ the slots and identity

    @Test
    void theButtonsKeepTheirIdentityThroughEveryRebuildAndTheSlotsLandInOrder() {
        bindControls();
        long playId = node("Play").id();
        long muteId = node("Mute").id();

        controls.addLeading(new Button("Subtitles"));
        frame();
        controls.addTrailing(new Button("Audio"));
        frame();

        assertEquals(playId, node("Play").id(),
                "rebuild() removes and re-adds every row child, and serials are minted per widget");
        assertEquals(muteId, node("Mute").id());
        List<String> names = childNames();
        int play = names.indexOf("Play");
        int mute = names.indexOf("Mute");
        int subtitles = names.indexOf("Subtitles");
        int audio = names.indexOf("Audio");
        int clock = names.indexOf("0:00");
        assertTrue(play >= 0 && mute > play && subtitles > mute && audio > subtitles
                        && clock > audio,
                "leading widgets follow the sound cluster, trailing ones follow the scrub bar and "
                        + "precede the clock: " + names + describe(tree()));
        assertEquals(names.size() - 1, clock, "the clock stays the trailing edge: " + names);

        controls.setShowPosition(false);
        frame();

        assertFalse(childNames().contains("0:00"), describe(tree()));
        for (AccessibleNode child : childrenOf(toolbar())) {
            assertNotEquals(Accessible.Role.LABEL, child.role(),
                    "no clock means no label under the bar" + describe(tree()));
        }
        assertEquals(playId, node("Play").id());

        controls.setShowPosition(true);
        frame();

        assertEquals("0:00", childNames().get(childNames().size() - 1), describe(tree()));
        assertEquals(playId, node("Play").id());
        assertEquals(muteId, node("Mute").id());
    }

    // ---------------------------------------------------------------------------------- geometry

    @Test
    void insideItsViewTheBarHangsOverThePicturesLowerEdge() {
        view = new VideoView();
        bind(view);
        view.setControlsVisible(true);
        frame();
        controls = view.controls();

        AccessibleNode bar = toolbar();
        float h = controls.height();
        assertTrue(h > 0, "shown controls take a real box");
        assertEquals(8f, bar.x(), 0.01f, describe(tree()));
        assertEquals(300f - h - 8f, bar.y(), 0.01f,
                "over the lower edge, standing the margin off it" + describe(tree()));
        assertEquals(400f - 16f, bar.width(), 0.01f, describe(tree()));
        assertEquals(h, bar.height(), 0.01f, describe(tree()));
        assertEquals(node(Accessible.Role.VIDEO).id(), tree().node(bar.parent()).id(),
                "and it hangs under the view's own node, which the view now publishes whatever it "
                        + "is holding" + describe(tree()));
    }

    /** A do-nothing video source: enough to give the play button something to play. */
    private static final class FakeVideo implements VideoStreamSource {
        @Override public int width() { return 16; }
        @Override public int height() { return 16; }
        @Override public PixelFormat pixelFormat() { return PixelFormat.I420; }
        @Override public VideoColor color() { return VideoColor.BT709_LIMITED; }
        @Override public int frameRateNum() { return 30; }
        @Override public int frameRateDen() { return 1; }
        @Override public Read readFrame() { return Read.PENDING; }
        @Override public VideoFrame frame() { return null; }
        @Override public void reset() { }
        @Override public void close() { }
    }
}

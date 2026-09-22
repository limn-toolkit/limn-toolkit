package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ValueFacet;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.AllocationProbe;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * What a {@link Slider} becomes in the accessible tree: one horizontal {@code SLIDER} node, a tab
 * stop, with no name of its own, carrying the model's value, bounds and step in a writable facet
 * and offering the two steps, operated from a screen reader through the same from-the-user path a
 * key press takes, commit included.
 *
 * <p>The widget is focusable and paints, so a hook that stopped declaring the role would publish
 * it as {@code UNKNOWN} with the walk's once-per-class warning naming a toolkit class; the log is
 * captured around every test and checked after each, because whichever test runs first is the one
 * that would see it.
 *
 * <p>The cases that pin where ADR 039 §7's row was short: the row says the hook "reaches the
 * private from-user path" as if that were one call, and it is two, the change and the commit, since
 * the source fires {@code onCommit} after every handled key; a hook that reached only the change
 * would leave the toolkit's own media transport with its scrub bar's drag flag set forever after a
 * reader's set. The row's {@code ValueFacet{min,max,step}} does not say which step, and on a
 * continuous slider the field and the keyboard's nudge differ: the facet carries the field, and the
 * verbs move by the nudge. The row is silent on orientation, which the class fixes as horizontal,
 * and on mirroring, which never touches the two verbs because they are a direction of the value and
 * not a side of the track.
 *
 * <p>Every case drives the slider's public API, and Widget's, on a bound scene, or calls the scene
 * from where a bridge stands, and reads back what the scene published. Nothing constructs a node.
 */
class SliderAccessibilityTest extends AccessibleComponentTestBase {

    private Slider slider;

    /** The column the slider sits in, the root of every fixture. */
    private Column root;

    /** Every value the application's change handler was given, in order. */
    private final List<Float> changed = new ArrayList<>();

    /** Every value the application's commit handler was given, in order. */
    private final List<Float> committed = new ArrayList<>();

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
    void theSliderIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
                        // The PARAMETER and not the message: the walk logs a parameterised record, so
            // getMessage() answers the unformatted "{0} paints its own content..." pattern and the
            // class name is in getParameters()[0]. Read the message here and the assertion passes
            // whatever the walk does, which is what it did until a verification read both sides.
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.contains("limn.components.Slider"),
                    "the slider is focusable and paints, and it declares a role, so the walk must "
                            + "never publish it UNKNOWN or say it paints and is deleted; a warning "
                            + "here names a toolkit class an application cannot correct: "
                            + subject + " " + record.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One slider as the only control in a column, so it keeps its own measured box. */
    private void bindSlider(Slider under) {
        slider = under;
        slider.onChange(changed::add);
        slider.onCommit(committed::add);
        root = new Column();
        root.add(slider);
        bind(root);
    }

    /** @return the one slider node in the current tree */
    private AccessibleNode sliderNode() {
        return node(Accessible.Role.SLIDER);
    }


    /** @return every value change raised so far, in order */
    private List<AccessibleEvent> valueEvents() {
        return bridge.eventsOf(AccessibleEvent.Type.VALUE_CHANGED);
    }

    /** Moves the pointer to the centre of the slider's box, as a hover does. */
    private void hover() {
        drive(scene).mouseMoved(slider.localToSceneX() + slider.width() / 2,
                slider.localToSceneY() + slider.height() / 2);
        drive(scene).inputBatchEnded();
    }

    /**
     * Moves the scene's clock on by {@code millis}, damaging the slider each time, which is what
     * lets a transition run its course before a measurement is taken. The clock is the test's:
     * a span of wall time settles a fade on a fast machine and lands in the middle of it on a
     * slow one, which is not a property of anything under test.
     */
    private void frameFor(long millis) {
        advanceTime(millis, slider);
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

    /** A writable facet over {@code 0..100}: what every fixture here publishes but the numbers. */
    private static ValueFacet facet(double value, double step) {
        return new ValueFacet(value, 0, 100, step, null, false);
    }

    // ------------------------------------------------------------------------------- the shape

    @Test
    void aFreshSliderIsOneHorizontalWritableNodeWithTwoStepsAndNoChildren() {
        bindSlider(new Slider(0, 100));

        assertEquals(2, tree().nodeCount(),
                "the window and the slider; the column is scaffolding" + describe(tree()));
        AccessibleNode node = sliderNode();
        assertEquals(0, node.parent(), describe(tree()));
        assertTrue(node.has(Accessible.State.HORIZONTAL),
                "the value axis is fixed by the class" + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "a slider is focusable from its constructor" + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY),
                "the value is settable, and the facet says so" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.INCREMENT), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.DECREMENT), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS),
                "the two the walk adds for every focusable widget" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.PRESS),
                "a slider is not pressed" + describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.TOGGLE), describe(tree()));
        assertEquals(facet(0, 0), node.value(),
                "starts at min, continuous, no text: the number is the whole of it"
                        + describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "the rail, the fill and the thumb are paint, not nodes" + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the name

    @Test
    void theSliderInventsNoNameAndTakesTheTooltipOrTheApplicationsOwn() {
        bindSlider(new Slider(0, 100));

        AccessibleNode nameless = sliderNode();
        assertEquals("", nameless.name(),
                "the widget holds no string, so it hands none over; a caption beside it is a "
                        + "relation the application declares, never a guess" + describe(tree()));
        assertEquals("", nameless.description(), describe(tree()));

        slider.setTooltip("Volume");
        frame();
        AccessibleNode byTooltip = sliderNode();
        assertEquals("Volume", byTooltip.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, byTooltip.nameFrom(),
                "the free default, which is what names the media transport's two sliders"
                        + describe(tree()));
        assertEquals("", byTooltip.description(), describe(tree()));

        slider.setAccessibleName("Master volume");
        frame();
        AccessibleNode explicit = sliderNode();
        assertEquals("Master volume", explicit.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, explicit.nameFrom(), describe(tree()));
        assertEquals("Volume", explicit.description(),
                "once something else named the node, the tooltip is what it describes"
                        + describe(tree()));
        assertEquals(nameless.id(), explicit.id(), "a name is not a rebuild");
    }

    // ------------------------------------------------------------------------------- the facet

    @Test
    void theFacetFollowsTheSettersThroughTheSnapAndTheClamp() {
        bindSlider(new Slider(0, 100));

        slider.setValue(30);
        frame();
        assertEquals(facet(30, 0), sliderNode().value(), describe(tree()));
        List<AccessibleEvent> changes = valueEvents();
        assertEquals(1, changes.size(), bridge.events.toString());
        assertEquals(sliderNode().id(), changes.get(0).nodeId());
        assertEquals(0.0, changes.get(0).oldValue());
        assertEquals(30.0, changes.get(0).newValue());

        slider.setStep(5);
        frame();
        assertEquals(facet(30, 5), sliderNode().value(),
                "the step field is the facet's step, the grid a set snaps onto" + describe(tree()));

        slider.setValue(37);
        frame();
        assertEquals(facet(35, 5), sliderNode().value(),
                "the snap reaches the facet, because the facet is the field" + describe(tree()));
        assertEquals(35f, slider.value());

        slider.setValue(1000);
        frame();
        assertEquals(facet(100, 5), sliderNode().value(), "and so does the clamp" + describe(tree()));

        slider.setStep(0);
        frame();
        assertEquals(facet(100, 0), sliderNode().value(),
                "continuous publishes no grid, not the keyboard's one percent" + describe(tree()));
    }

    @Test
    void setValueIsSilentToTheApplicationAndLoudToTheTree() {
        bindSlider(new Slider(0, 100));

        slider.setValue(40);
        frame();

        assertEquals(List.of(), changed, "setValue is not a user change");
        assertEquals(List.of(), committed, "and not a decision either");
        assertEquals(1, valueEvents().size(),
                "but the tree moved, so a reader is told: " + bridge.events);
        assertEquals(40.0, valueEvents().get(0).newValue());
    }

    // ------------------------------------------------------------------------------ the action

    @Test
    void aStepFromTheBridgeMovesByTheStepAndFiresChangeAndCommit() throws Exception {
        bindSlider(new Slider(0, 100).setStep(5).setValue(30));
        long id = sliderNode().id();

        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE),
                "accepted, which is not the same as done");
        assertEquals(35f, slider.value());
        assertEquals(List.of(35f), changed,
                "the hook reaches apply(raw, true), which is what a key reaches, and that is what "
                        + "tells the application; setValue would not have");
        assertEquals(List.of(35f), committed,
                "and then the commit, as every handled key does: a step from a reader is a whole "
                        + "gesture, and the transport's scrub bar clears its drag flag nowhere else");

        frame();

        List<AccessibleEvent> raised = valueEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(id, raised.get(0).nodeId());
        assertEquals(30.0, raised.get(0).oldValue());
        assertEquals(35.0, raised.get(0).newValue());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press and nothing else: " + bridge.events);

        bridge.events.clear();
        perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(30f, slider.value());
        assertEquals(List.of(35f, 30f), changed,
                "the application's own listener is still in the slot; nothing replaced it");
        assertEquals(List.of(35f, 30f), committed);
        raised = valueEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(35.0, raised.get(0).oldValue());
        assertEquals(30.0, raised.get(0).newValue());
    }

    @Test
    void aContinuousSliderStepsByOnePercentOfTheRangeAndPublishesNoGrid() throws Exception {
        bindSlider(new Slider(0, 100).setValue(30));
        long id = sliderNode().id();

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(31f, slider.value(), "the keyboard's nudge, not the facet's zero");
        assertEquals(facet(31, 0), sliderNode().value(),
                "the facet still says any value is accepted" + describe(tree()));
        assertEquals(List.of(31f), changed);
        assertEquals(List.of(31f), committed);
    }

    @Test
    void aStepAtTheEndCommitsTheValueHeldAndMovesNothing() throws Exception {
        bindSlider(new Slider(0, 100).setStep(5).setValue(100));
        long id = sliderNode().id();
        int published = bridge.published.size();

        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE),
                "accepted: the verb ran, and what it ran was a commit");
        frame();

        assertEquals(100f, slider.value());
        assertEquals(List.of(), changed, "nothing moved, so nothing changed");
        assertEquals(List.of(100f), committed,
                "but the user chose the end, exactly as End at max commits");
        assertEquals(List.of(), valueEvents(), bridge.events.toString());
        assertEquals(published, bridge.published.size(),
                "a value that did not move invalidates nothing and publishes nothing");
    }

    @Test
    void setValueFromTheBridgeSnapsClampsAndRefusesWhatIsNotANumber() throws Exception {
        bindSlider(new Slider(0, 100).setStep(5).setValue(30));
        long id = sliderNode().id();

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(72));
        frame();

        assertEquals(70f, slider.value(), "the same snap a drag reaches");
        assertEquals(List.of(70f), changed);
        assertEquals(List.of(70f), committed);
        assertEquals(1, valueEvents().size(), bridge.events.toString());
        assertEquals(70.0, valueEvents().get(0).newValue());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1000));
        frame();
        assertEquals(100f, slider.value(), "the clamp");
        assertEquals(facet(100, 5), sliderNode().value(), describe(tree()));

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(-5));
        frame();
        assertEquals(0f, slider.value());
        assertEquals(facet(0, 5), sliderNode().value(), describe(tree()));
        assertEquals(List.of(70f, 100f, 0f), changed);
        assertEquals(List.of(70f, 100f, 0f), committed);

        bridge.events.clear();
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(Double.NaN));
        perform(id, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.POSITIVE_INFINITY));
        perform(id, Accessible.Action.SET_VALUE, Accessible.Argument.NONE);
        frame();

        assertEquals(0f, slider.value(),
                "a value that is not a number would pass the clamp untouched and poison the field");
        assertEquals(facet(0, 5), sliderNode().value(), describe(tree()));
        assertEquals(List.of(70f, 100f, 0f), changed, "refused before any handler");
        assertEquals(List.of(70f, 100f, 0f), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindSlider(new Slider(0, 100).setValue(30));
        long id = sliderNode().id();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_TEXT, new Accessible.Argument.OfText("50"));
        frame();

        assertEquals(30f, slider.value());
        assertEquals(List.of(), changed, "the hook answers false for what it does not offer");
        assertEquals(List.of(), committed);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void theStepsDoNotMirrorAndTheBoxDoesNotMoveReadingRightToLeft() throws Exception {
        bindSlider(new Slider(0, 100).setStep(5).setValue(30));
        AccessibleNode leftToRight = sliderNode();

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        // The column puts its child at the leading edge, so reading right to left the slider
        // sits at the other end of the row; that move is the parent's. The slider's own box is
        // the same size, wherever it was put: the pad is reserved at both ends and only which
        // end min sits at flips, which is not a fact of the box.
        AccessibleNode mirrored = sliderNode();
        assertEquals(slider.localToSceneX(), mirrored.x(), describe(tree()));
        assertEquals(slider.localToSceneY(), mirrored.y(), describe(tree()));
        assertEquals(leftToRight.width(), mirrored.width(), describe(tree()));
        assertEquals(leftToRight.height(), mirrored.height(), describe(tree()));
        assertEquals(leftToRight.value(), mirrored.value(), describe(tree()));
        assertEquals(leftToRight.id(), mirrored.id());

        perform(mirrored.id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(35f, slider.value(),
                "an increment is a direction of the value, as Up is, and never a side of the track");
        assertEquals(List.of(35f), changed);
        assertEquals(List.of(35f), committed);
    }

    @Test
    void aDisabledSliderRefusesEveryVerbAndStaysInTheTree() throws Exception {
        bindSlider(new Slider(0, 100).setStep(5).setValue(30));
        slider.setEnabled(false);
        frame();

        AccessibleNode node = sliderNode();
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the keyboard does not reach a disabled control, and the tree agrees with it"
                        + describe(tree()));
        assertEquals(facet(30, 5), node.value(),
                "a disabled slider is heard as disabled rather than vanishing, its value as "
                        + "writable as it is (fix round 2e)" + describe(tree()));
        assertFalse(node.accepts(Accessible.Action.SET_VALUE),
                "and it accepts no SET_VALUE, because it is not ENABLED" + describe(tree()));
        assertNull(node.actions(), "no verb the scene refuses is published" + describe(tree()));
        bridge.events.clear();

        perform(node.id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(node.id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(80));
        frame();

        assertEquals(30f, slider.value(), "a disabled control was operated");
        assertEquals(List.of(), changed);
        assertEquals(List.of(), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        // The same slider with its own flag true, inside a container that is not: the widget's
        // own guard passes and the scene's ancestor gate is what refuses, as the keyboard does.
        bindSlider(new Slider(0, 100).setStep(5).setValue(30));
        root.setEnabled(false);
        frame();

        assertTrue(slider.isEnabled(), "the fixture has to leave the slider's own flag alone");
        assertFalse(sliderNode().has(Accessible.State.ENABLED),
                "the bit is inherited down the walk" + describe(tree()));
        assertNull(sliderNode().actions(),
                "and so is the withdrawal of its verbs" + describe(tree()));
        assertFalse(sliderNode().value().readOnly(), describe(tree()));
        assertFalse(sliderNode().accepts(Accessible.Action.SET_VALUE),
                "no SET_VALUE under a disabled ancestor either" + describe(tree()));
        bridge.events.clear();

        perform(sliderNode().id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(sliderNode().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(80));
        frame();

        assertEquals(30f, slider.value(),
                "a control inside a disabled container is one the keyboard refuses too");
        assertEquals(List.of(), changed);
        assertEquals(List.of(), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- identity

    @Test
    void theNodeKeepsItsIdentityThroughEveryMutationTheSliderHas() {
        bindSlider(new Slider(0, 100));
        long id = sliderNode().id();

        slider.setValue(60);
        frame();
        assertEquals(id, sliderNode().id());

        slider.setStep(10);
        frame();
        assertEquals(id, sliderNode().id());

        slider.setEnabled(false);
        frame();
        assertEquals(id, sliderNode().id());
        slider.setEnabled(true);
        frame();
        assertTrue(sliderNode().has(Accessible.State.ENABLED), describe(tree()));

        slider.setVisible(false);
        frame();
        AccessibleNode hidden = sliderNode();
        assertEquals(id, hidden.id());
        assertFalse(hidden.has(Accessible.State.VISIBLE), describe(tree()));
        assertFalse(hidden.has(Accessible.State.SHOWING), describe(tree()));
        assertEquals(facet(60, 10), hidden.value(),
                "hidden is a state, not a different node" + describe(tree()));
        slider.setVisible(true);
        frame();
        assertEquals(id, sliderNode().id());
        assertTrue(sliderNode().has(Accessible.State.SHOWING), describe(tree()));

        scene.requestFocus(slider);
        frame();
        assertEquals(id, sliderNode().id());

        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                bridge.events.toString());
    }

    // ------------------------------------------------------------------------------- focus

    @Test
    void aSliderIsExactlyOneTabStopAndFocusIsReported() {
        bindSlider(new Slider(0, 100));

        assertEquals(List.of(sliderNode().id()),
                nodesWith(Accessible.State.FOCUSABLE).stream().map(AccessibleNode::id).toList(),
                "the slider is the only tab stop in the tree, once" + describe(tree()));
        assertFalse(sliderNode().has(Accessible.State.FOCUSED), describe(tree()));

        scene.requestFocus(slider);
        frame();

        assertTrue(sliderNode().has(Accessible.State.FOCUSED),
                "the walk carries focus down; the hook declares no state of its own"
                        + describe(tree()));
        assertEquals(sliderNode().id(), tree().focused(), describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.FOCUS_CHANGED),
                bridge.events.toString());
    }

    @Test
    void aKeyPressProducesTheSameTreeTheBridgePathDoes() {
        bindSlider(new Slider(0, 100).setStep(5).setValue(30));
        scene.requestFocus(slider);
        frame();
        bridge.events.clear();

        drive(scene).keyEvent(Keys.END, true, false, 0);
        drive(scene).keyEvent(Keys.END, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();

        assertEquals(facet(100, 5), sliderNode().value(), describe(tree()));
        assertEquals(List.of(100f), changed);
        assertEquals(List.of(100f), committed, "every handled key commits, once");
        List<AccessibleEvent> raised = valueEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(30.0, raised.get(0).oldValue());
        assertEquals(100.0, raised.get(0).newValue());
    }

    // ---------------------------------------------------------------------------- geometry

    @Test
    void theBoxIsTheSlidersOwnAndASizedBoxAroundItIsNotANode() {
        slider = new Slider(0, 100);
        root = new Column();
        root.add(new SizedBox(300, SizedBox.UNSET, slider));
        bind(root);

        assertFalse(anyNodeIs(tree(), Accessible.Role.GROUP),
                "the sized box is scaffolding and hoists the slider" + describe(tree()));
        AccessibleNode node = sliderNode();
        assertEquals(300f, slider.width(), "the fixture really did widen the slider");
        assertEquals(slider.localToSceneX(), node.x(), describe(tree()));
        assertEquals(slider.localToSceneY(), node.y(), describe(tree()));
        assertEquals(slider.width(), node.width(), describe(tree()));
        assertEquals(slider.height(), node.height(),
                "the step's slider height; the hovered knob and the ring are inside it, so there "
                        + "is no paint outset to leave out" + describe(tree()));
        assertTrue(node.height() > 0, describe(tree()));
    }

    // ------------------------------------------------------------------------- what it costs

    @Test
    void aQuietSliderAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindSlider(new Slider(0, 100));
        slider.setValue(70);
        frame();
        scene.requestFocus(slider);
        frame();
        hover();
        frame();

        // The hover and focus fades are timed transitions that damage the slider on every
        // frame they run for, and a measurement taken while one is mid-flight is a measurement
        // of the animation.
        frameFor(400);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            slider.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // A string formatted inside the hook, or the variable-argument action call, would be one
        // allocation per damaged frame spent concluding that nothing moved. This is the only
        // place it would be visible.
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            slider.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            slider.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a slider that did not move must cost no memory: the role is an enum, "
                        + "the value is four doubles, the orientation is a bit and the two verbs "
                        + "are two bits");
    }
}

package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ValueFacet;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link SplitPane} becomes in the accessible tree, which is three widgets taking three
 * different answers.
 *
 * <p>The split itself is one node and nothing else: it declares a role so that it survives ADR 039
 * §1.6's predicate, because it paints nothing and would otherwise be deleted with no warning
 * logged anywhere, leaving a splitter among two panes' hoisted contents with nothing saying what
 * it divides. A pane is scaffolding and is deleted every frame. The divider is the control — it
 * carries the value, the orientation and the verbs, and it performs them itself.
 *
 * <p><b>The value is the first pane's extent in points, and not the ratio the survey's row names.</b>
 * {@code ratio()} is the share that was asked for before any minimum applies, so wherever a floor
 * binds it names a position the divider is not at; and ten points, which is what an arrow key
 * moves, is a different fraction after every resize. The first test below is the one that tells
 * those two apart, and a ratio-valued facet passes its first half and fails its second.
 *
 * <p><b>A widget that needs no code still owes tests</b>, which is what the pane's four are for.
 * The deletion of its node does not delete its <em>clip</em>: {@link Widget#isShowing()} walks the
 * widget tree and intersects a box against every ancestor that clips its children, so a control
 * inside a collapsed pane publishes as visible and not on screen with nothing written anywhere.
 * That answer is free, it is correct, and it stands entirely on the pane keeping
 * {@code clipsChildren()}.
 *
 * <p>Everything here drives the split's public API, or calls the scene from where a bridge stands;
 * nothing constructs a node.
 */
class SplitPaneAccessibilityTest extends AccessibleComponentTestBase {

    /** What one arrow press moves, and what the divider publishes as its step. */
    private static final float STEP = 10;

    /**
     * A leaf with a fixed preferred size and an application-supplied name, which is what an
     * ordinary control in a pane amounts to for this test: a widget that survives the predicate and
     * so has a node whose box and states can be read.
     */
    private static final class Box extends Widget {
        private final float prefWidth;
        private final float prefHeight;

        Box(String name, float prefWidth, float prefHeight) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
            setAccessibleName(name);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }
    }

    /**
     * A container that lays one child inside its own box and one far outside it: the case the pane
     * exists for, since without the clip the second child would paint into the other pane.
     */
    private static final class Overflow extends Widget {
        Overflow(Widget inside, Widget outside) {
            add(inside);
            add(outside);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(0, 0);
        }

        @Override
        protected void onLayout() {
            place(children().get(0), 0);
            place(children().get(1), 10_000);
        }

        private static void place(Widget child, float x) {
            child.measure(Constraints.tight(10, 10));
            child.layoutBox(x, 0, 10, 10);
        }
    }

    /**
     * A wrapper that fills what it is given and hands all of it to its child: an ancestor to
     * disable, and one that is itself scaffolding, so the tree below it is the same tree.
     */
    private static final class Frame extends Widget {
        Frame(Widget child) {
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            children().get(0).measure(constraints);
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            children().get(0).layoutBox(0, 0, width(), height());
        }
    }

    private SplitPane split;

    /** Every ratio the split reported as the user's, in order. */
    private final List<Float> reported = new ArrayList<>();

    /** Binds a horizontal split of two named boxes and settles the first frame. */
    private void bindSplit() {
        split = SplitPane.horizontal(new Box("first", 80, 40), new Box("second", 80, 40));
        split.onRatioChange(reported::add);
        bind(split);
    }

    /** The leading pane's extent along the divided axis, which is what the facet publishes. */
    private float firstExtent() {
        return split.children().get(0).width();
    }

    /** The trailing pane's extent along the same axis. */
    private float secondExtent() {
        return split.children().get(1).width();
    }

    /** The divider's node in the tree the bridge is holding. */
    private AccessibleNode splitter() {
        return node(Accessible.Role.SPLITTER);
    }

    /** Delivers one key press to whatever has focus, and renders the frame that publishes it. */
    private void press(int key, int modifiers) {
        scene.keyEvent(key, true, false, modifiers);
        scene.inputBatchEnded();
        frame();
    }

    // ------------------------------------------------------------------ the shape of the tree

    @Test
    void theSplitIsOneNodeAndItsPanesAreNone() {
        bindSplit();

        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            assertFalse(node.role() == Accessible.Role.GROUP && node.name().isEmpty(),
                    "a nameless group survived the predicate: " + describe(tree()));
        }
        assertEquals(5, tree().nodeCount(),
                "the window, the split, one node per content and the divider; both panes are "
                        + "scaffolding and are gone" + describe(tree()));
        AccessibleNode container = node(Accessible.Role.SPLIT_PANE);
        assertEquals(0, container.parent(), "the split hangs under the window node");
        assertEquals("", container.name(),
                "a split names nothing of its own; an application that wants it named names it");
        assertEquals(tree().indexOf(container.id()), node("first").parent(),
                "both contents hoist into the split's own place" + describe(tree()));
        assertEquals(node("first").parent(), node("second").parent());

        List<AccessibleNode> children = childrenOf(container);
        assertEquals(List.of("first", "second"),
                List.of(children.get(0).name(), children.get(1).name()),
                "tree order is the widget tree's order" + describe(tree()));
        assertEquals(Accessible.Role.SPLITTER, children.get(2).role(),
                "and the divider is last, because the constructor adds it last so that it wins the "
                        + "hit test where its grab band overlaps the panes");
        assertEquals(3, children.size(), describe(tree()));
    }

    @Test
    void theSplittersOrientationIsTheAxisItsValueRunsAlong() {
        bindSplit();

        assertTrue(splitter().has(Accessible.State.HORIZONTAL),
                "Accessible.State names the axis a node's value runs along, and this splitter's "
                        + "value is the first pane's width, which runs left to right"
                        + describe(tree()));
        assertFalse(splitter().has(Accessible.State.VERTICAL),
                "the vertical line onPaint draws between side-by-side panes is the picture and "
                        + "not the meaning; a platform that names the line inverts this in its "
                        + "own bridge" + describe(tree()));

        bind(SplitPane.vertical(new Box("top", 80, 40), new Box("bottom", 80, 40)));

        assertTrue(splitter().has(Accessible.State.VERTICAL),
                "stacked panes put the first pane's height on the value, and a height runs up "
                        + "and down" + describe(tree()));
        assertFalse(splitter().has(Accessible.State.HORIZONTAL), describe(tree()));
    }

    @Test
    void theSplitterBoxIsTheGrabBandAndItMirrors() {
        bindSplit();
        // Off centre, so that a mirrored band is somewhere else: an even split's divider is in the
        // middle either way, and a test that took the default would pass without mirroring.
        split.setRatio(0.25f);
        frame();
        Widget divider = split.divider();

        assertEquals(divider.localToSceneX(), splitter().x(), 0.01f, describe(tree()));
        assertEquals(divider.localToSceneY(), splitter().y(), 0.01f);
        assertEquals(divider.width(), splitter().width(), 0.01f);
        assertEquals(divider.height(), splitter().height(), 0.01f);
        assertEquals(Strokes.MIN_HIT_TARGET, splitter().width(), 0.01f,
                "the band a user aims at, not the hairline it paints" + describe(tree()));

        float wasX = splitter().x();
        double wasValue = splitter().value().value();
        split.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertNotEquals(wasX, splitter().x(), "the band moved to the other side");
        assertEquals(divider.localToSceneX(), splitter().x(), 0.01f,
                "and the box is still the divider's own" + describe(tree()));
        assertEquals(wasValue, splitter().value().value(), 0.01,
                "the value is a magnitude along the divided axis; it does not turn around");
    }

    // ------------------------------------------------------------------------------ the value

    @Test
    void theSplitterCarriesTheFirstPanesExtentAndNotTheRatio() {
        bindSplit();
        split.setMinimums(50, 80);
        split.setRatio(0.25f);
        frame();

        ValueFacet value = splitter().value();
        float total = firstExtent() + secondExtent();
        assertEquals(firstExtent(), value.value(), 0.01,
                "the value is where the divider is" + describe(tree()));
        assertEquals(50, value.min(), 0.01, "which is firstExtent's own lower clamp");
        assertEquals(total - 80, value.max(), 0.01, "and its upper one");
        assertEquals(STEP, value.step(), 0.01,
                "the step is the keyboard's own, in points: as a ratio it would be a different "
                        + "number after every resize");
        assertNull(value.text(),
                "the number is the whole of it, so nothing derived is published and no cached "
                        + "string is owed");

        split.setRatio(0.02f);
        frame();

        assertEquals(0.02f, split.ratio(), 1e-6f, "the ratio is what was asked for");
        assertEquals(50, firstExtent(), 0.01f, "and the floor is where the divider actually is");
        assertEquals(50, splitter().value().value(), 0.01,
                "a facet carrying the ratio names a position the divider is not at"
                        + describe(tree()));
    }

    @Test
    void aRatioChangeIsPublishedAsAValueChangeAndAQuietFrameIsNot() {
        bindSplit();
        long id = splitter().id();
        bridge.events.clear();

        split.setRatio(0.3f);
        frame();

        List<AccessibleEvent> changes = bridge.events.stream()
                .filter(event -> event.type() == AccessibleEvent.Type.VALUE_CHANGED).toList();
        assertEquals(1, changes.size(), "one value moved: " + bridge.events);
        assertEquals(id, changes.get(0).nodeId(), "and it is the divider's");
        assertEquals(splitter().value().value(), (Double) changes.get(0).newValue(), 0.01);

        int published = bridge.published.size();
        bridge.events.clear();
        frame();

        assertEquals(published, bridge.published.size(), "a second frame moved nothing, so it published nothing");
        assertTrue(bridge.events.isEmpty(), "and says nothing: " + bridge.events);
    }

    @Test
    void aHoverOverTheDividerPublishesNothingAndCostsNothingToDescribe() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindSplit();
        Widget divider = split.divider();
        scene.mouseMoved(divider.localToSceneX() + divider.width() / 2,
                divider.localToSceneY() + divider.height() / 2);
        scene.inputBatchEnded();
        frame();
        int published = bridge.published.size();
        bridge.events.clear();

        // The hover fade damages the divider on every frame it runs for, and each of those frames
        // walks the whole tree to conclude that nothing moved. A name built in the hook, a
        // formatted value text or an action(Action...) call are all invisible except here.
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            divider.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            divider.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // Against the same frame with nothing listening, and not against zero: a headless frame
        // has a floor of its own that has nothing to do with this widget, and a test written
        // against a constant would be measuring that floor instead. What is being claimed is the
        // difference -- that describing the split adds nothing to a frame that changed nothing.

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a splitter that did not move must cost no memory at all: the name is "
                        + "a constant compared by reference and the value is four numbers, so a "
                        + "hover costs a comparison and nothing else");
    }

    // ----------------------------------------------------------------------------- the actions

    @Test
    void settingTheValueFromAnAssistiveTechnologyNotifiesTheApplication() throws Exception {
        bindSplit();
        split.setRatio(0.4f);
        frame();
        assertTrue(reported.isEmpty(), "a programmatic set is not the user and reports nothing");

        assertTrue(perform(splitter().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(120)),
                "accepted, which is not the same as done");
        frame();

        assertEquals(120, firstExtent(), 0.5f, "the divider went where it was told");
        assertEquals(List.of(split.ratio()), reported,
                "and the application heard it exactly as it hears a drag");
        assertEquals(120, splitter().value().value(), 0.5,
                "the units the facet published are the units the setter takes");
    }

    @Test
    void aSetValueWithoutANumberIsRefused() throws Exception {
        bindSplit();
        float before = firstExtent();

        perform(splitter().id(), Accessible.Action.SET_VALUE, Accessible.Argument.NONE);
        frame();

        assertEquals(before, firstExtent(), 0.01f);
        assertTrue(reported.isEmpty(), "nothing was set, so nothing was reported");
    }

    @Test
    void incrementMovesByTheKeyboardsOwnStepAndDoesNotMirror() throws Exception {
        bindSplit();
        split.setDividerFocusable(true);
        split.divider().requestFocus();
        frame();
        float before = firstExtent();

        perform(splitter().id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(before + STEP, firstExtent(), 0.5f, "one increment is one arrow press");

        perform(splitter().id(), Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(before, firstExtent(), 0.5f);

        press(Keys.RIGHT, 0);
        assertEquals(before + STEP, firstExtent(), 0.5f, "which is the distance the arrow moves");

        split.setLayoutDirection(LayoutDirection.RTL);
        frame();
        float mirrored = firstExtent();

        // The arrows are screen directions and turn around; the value is a magnitude and does not.
        press(Keys.LEFT, 0);
        assertEquals(mirrored + STEP, firstExtent(), 0.5f,
                "reading right to left, Left grows the leading pane");
        perform(splitter().id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(mirrored + 2 * STEP, firstExtent(), 0.5f,
                "and INCREMENT grows it either way, so the value keeps agreeing with the facet");
    }

    @Test
    void theValueStopsWhereTheDragStops() throws Exception {
        bindSplit();
        split.setMinimums(120, 120);
        frame();
        float total = firstExtent() + secondExtent();

        perform(splitter().id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(0));
        frame();
        assertEquals(120, firstExtent(), 0.5f, "the floor stops the setter as it stops the drag");
        assertEquals(120 / total, reported.get(reported.size() - 1), 1e-5f,
                "and the application is told the ratio it settled on, never the raw one");

        perform(splitter().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(10_000));
        frame();
        assertEquals(120, secondExtent(), 0.5f, "and the other floor on the way back");

        split.setMinimums(300, 300);
        frame();
        ValueFacet stuck = splitter().value();
        assertEquals(stuck.min(), stuck.max(), 0.01,
                "two floors that cannot both be honoured leave the divider nowhere to go"
                        + describe(tree()));
        float parked = firstExtent();
        perform(splitter().id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(0));
        frame();
        assertEquals(parked, firstExtent(), 0.01f, "and the setter cannot move it");
        assertEquals(parked, splitter().value().value(), 0.01);
    }

    @Test
    void aDisabledSplitRefusesTheAction() throws Exception {
        bindSplit();
        float before = firstExtent();
        split.setEnabled(false);
        frame();

        perform(splitter().id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(120));
        perform(splitter().id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertFalse(splitter().has(Accessible.State.ENABLED), describe(tree()));
        assertEquals(before, firstExtent(), 0.01f, "a disabled control was operated");
        assertTrue(reported.isEmpty(), "and reported a change nobody made");
    }

    @Test
    void aSplitInsideADisabledContainerRefusesItToo() throws Exception {
        split = SplitPane.horizontal(new Box("first", 80, 40), new Box("second", 80, 40));
        split.onRatioChange(reported::add);
        Frame around = new Frame(split);
        bind(around);
        float before = firstExtent();
        around.setEnabled(false);
        frame();

        perform(splitter().id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(before, firstExtent(), 0.01f,
                "the keyboard refuses a control inside a disabled container and so must this");
        assertTrue(reported.isEmpty());
    }

    // ----------------------------------------------------------------- naming, focus, identity

    @Test
    void aDividerIsNamedWhetherOrNotItIsATabStop() {
        bindSplit();

        assertFalse(splitter().name().isEmpty(),
                "the divider has no text, no tooltip and no accessor an application could reach, "
                        + "so the toolkit is the only thing that can name it" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, splitter().nameFrom());
        assertFalse(splitter().has(Accessible.State.FOCUSABLE),
                "a divider is not a tab stop until the application asks");
        assertTrue(splitter().actions().has(Accessible.Action.INCREMENT),
                "and is operable with the pointer regardless, so the verbs are unconditional"
                        + describe(tree()));
        assertTrue(splitter().actions().has(Accessible.Action.DECREMENT));
        assertFalse(splitter().actions().has(Accessible.Action.SET_VALUE),
                "a parameterised setter is advertised by the facet, never in the action list");

        split.setDividerFocusable(true);
        frame();

        assertTrue(splitter().has(Accessible.State.FOCUSABLE), describe(tree()));
        assertEquals(List.of(splitter().id()),
                nodesWith(Accessible.State.FOCUSABLE).stream().map(AccessibleNode::id).toList(),
                "the divider is the split's only tab stop, and it is named, which is what the "
                        + "gallery rule refuses to be without" + describe(tree()));
        assertFalse(splitter().name().isEmpty());
    }

    @Test
    void theSplitterKeepsItsIdentity() {
        bindSplit();
        long id = splitter().id();

        split.setRatio(0.7f);
        frame();
        assertEquals(id, splitter().id(), "identity is minted over the widget tree");

        split.setMinimums(40, 40);
        frame();
        assertEquals(id, splitter().id());

        split.setDividerFocusable(true);
        frame();
        assertEquals(id, splitter().id(), "a tab stop is a state, not a different control");

        split.setDividerFocusable(false);
        split.setLayoutDirection(LayoutDirection.RTL);
        frame();
        assertEquals(id, splitter().id());

        split.setAccessibleName("Editor");
        frame();
        assertEquals(id, splitter().id(),
                "naming the container must not re-key what is under it" + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "nothing went away: " + bridge.events);
    }

    // ------------------------------------------------------------------ the panes, which are none

    @Test
    void aCollapsedPaneIsVisibleAndNotShowing() {
        bindSplit();
        split.setMinimums(0, 0);

        split.setRatio(0);
        frame();
        assertTrue(node("first").has(Accessible.State.VISIBLE),
                "nothing hid the collapsed pane's content; it has no room" + describe(tree()));
        assertFalse(node("first").has(Accessible.State.SHOWING),
                "the pane's clip reaches the tree even though the pane has no node"
                        + describe(tree()));
        assertTrue(node("second").has(Accessible.State.SHOWING));

        split.setRatio(1);
        frame();
        assertTrue(node("first").has(Accessible.State.SHOWING));
        assertTrue(node("second").has(Accessible.State.VISIBLE));
        assertFalse(node("second").has(Accessible.State.SHOWING),
                "and the mirror of it on the other side" + describe(tree()));
    }

    @Test
    void contentOverflowingAPaneIsNotShowing() {
        Box inside = new Box("inside", 10, 10);
        Box outside = new Box("outside", 10, 10);
        split = SplitPane.horizontal(new Overflow(inside, outside), new Box("second", 80, 40));
        bind(split);

        assertTrue(node("inside").has(Accessible.State.SHOWING));
        assertTrue(node("outside").has(Accessible.State.VISIBLE),
                "an overflowing child is still published; it is not on screen" + describe(tree()));
        assertFalse(node("outside").has(Accessible.State.SHOWING),
                "a child that overflows its pane stops at the gutter" + describe(tree()));
    }

    @Test
    void aRatioChangeMovesBoxesAndKeepsIdentity() {
        bindSplit();
        long firstId = node("first").id();
        long secondId = node("second").id();
        float wasX = node("second").x();

        split.setRatio(0.8f);
        frame();

        assertEquals(firstId, node("first").id(), "identity is minted over the widget tree");
        assertEquals(secondId, node("second").id());
        assertNotEquals(wasX, node("second").x(), "and the boxes moved");
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "a drag is a move, and a move is what a reader is told about");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed by moving the divider");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED));

        split.setMinimums(0, 0);
        split.setRatio(0);
        frame();

        assertEquals(firstId, node("first").id(),
                "a collapsed pane goes off screen; it does not go away" + describe(tree()));
        assertFalse(node("first").has(Accessible.State.SHOWING));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "destroying and rebuilding a subtree on every frame of a drag is what a screen "
                        + "reader experiences as everything it holds becoming invalid");
    }

    @Test
    void namingAPaneMaterialisesItAndReKeysNothing() {
        bindSplit();
        long firstId = node("first").id();

        split.children().get(0).setAccessibleName("Sidebar");
        frame();

        AccessibleNode sidebar = node("Sidebar");
        assertEquals(Accessible.Role.GROUP, sidebar.role(),
                "an application that wants a named region names the pane, with no code in Pane");
        assertEquals(tree().indexOf(sidebar.id()), node("first").parent(),
                "and the content hangs under it" + describe(tree()));
        assertEquals(firstId, node("first").id(),
                "a container that stops being scaffolding must not re-key what is under it");
    }
}

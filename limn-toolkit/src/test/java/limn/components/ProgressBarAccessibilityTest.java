package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ValueFacet;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ProgressBar} becomes in the accessible tree: one {@code PROGRESS_BAR} node, never
 * a tab stop, with no name of its own, carrying whole percent read from the model when it is
 * determinate and {@code BUSY} in place of any number when it is not.
 *
 * <p>The widget is a painting leaf that holds no string and takes no input, the shape ADR 039
 * §1.6's predicate deletes and then warns about once per class; the role is what takes it out of
 * that path, not the decoration seam, because a fill is information. So the absence of the walk's
 * warning is asserted after <em>every</em> test rather than in one of them: the walk names a class
 * at most once for the life of the virtual machine, and whichever test runs first is the one that
 * would catch a hook that stopped declaring the role.
 *
 * <p>Three of the cases exist to pin where the survey was wrong or the model was short. §7's row
 * asks for a read-only bit, and the builder dropped a declared one on the floor while the facet
 * had no way to carry it, so a bridge built from the record would have vended a writable range;
 * the facet carries it now, and the bit is derived from it.
 * It says the value is rounded "for the same reason" as a playing video's, and it is not: the
 * published value is the {@code progress} field, which moves only when the application sets it,
 * while what advances on its own here is the eased fill and the sweep, and neither is published.
 * And it writes the facet in the widget's public unit, {@code 0..1}, where what is published is
 * whole percent in {@code 0..100}. The source also settles what the row does not say: {@code BUSY}
 * replaces the facet rather than sitting beside it, and a determinate value arriving mid-sweep
 * clears it in the same publish.
 *
 * <p>Every case drives the bar's public API, and Widget's, on a bound scene, or calls the scene
 * from where a bridge stands, and reads back what the scene published. Nothing constructs a node.
 */
class ProgressBarAccessibilityTest extends AccessibleComponentTestBase {

    private ProgressBar bar;

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
                        // The PARAMETER and not the message: the walk logs a parameterised record, so
            // getMessage() answers the unformatted "{0} paints its own content..." pattern and the
            // class name is in getParameters()[0]. Read the message here and the assertion passes
            // whatever the walk does, which is what it did until a verification read both sides.
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.contains("limn.components.ProgressBar"),
                    "the bar paints, and it declares a role, so the walk must never say it paints "
                            + "and is deleted; a warning here names a toolkit class an application "
                            + "cannot correct: " + subject + " " + record.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One bar as the only control in a column, so it keeps its own measured box. */
    private void bindBar(ProgressBar under) {
        bar = under;
        Column column = new Column();
        column.add(bar);
        bind(column);
    }

    /** @return the one progress-bar node in the current tree */
    private AccessibleNode progressNode() {
        return node(Accessible.Role.PROGRESS_BAR);
    }

    /** @return every event of {@code type} raised so far, in order */
    private List<AccessibleEvent> eventsOf(AccessibleEvent.Type type) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == type) {
                found.add(event);
            }
        }
        return found;
    }

    /** @return every change of the busy bit raised so far, in order */
    private List<AccessibleEvent> busyEvents() {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : eventsOf(AccessibleEvent.Type.STATE_CHANGED)) {
            if (event.state() == Accessible.State.BUSY) {
                found.add(event);
            }
        }
        return found;
    }

    /**
     * Moves the scene's clock on by {@code millis}, damaging the bar each time, which is what
     * lets a transition run its course before a measurement is taken. The clock is the test's:
     * a span of wall time settles a fade on a fast machine and lands in the middle of it on a
     * slow one, which is not a property of anything under test.
     */
    private void frameFor(long millis) {
        advanceTime(millis, bar);
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

    // ------------------------------------------------------------------------------- the shape

    @Test
    void aFreshBarIsOneHorizontalProgressNodeWithNoVerbAndNoTabStop() {
        bindBar(new ProgressBar());

        assertEquals(2, tree().nodeCount(),
                "the window and the bar; the column is scaffolding" + describe(tree()));
        AccessibleNode node = progressNode();
        assertEquals(0, node.parent(), describe(tree()));
        assertTrue(node.has(Accessible.State.HORIZONTAL),
                "the long axis is fixed by the class" + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertFalse(node.has(Accessible.State.BUSY),
                "determinate from the constructor" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "a bar is never a tab stop" + describe(tree()));
        assertEquals(List.of(), nodesWith(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(node.actions() == null || node.actions().actions().isEmpty(),
                "no verb of its own, and no focus verbs because it is not focusable"
                        + describe(tree()));
        assertTrue(node.has(Accessible.State.READ_ONLY),
                "the facet's presence advertises a set on every platform, and this is the one "
                        + "thing that takes it back" + describe(tree()));
        assertEquals(new ValueFacet(0, 0, 100, 0, null, true), node.value(),
                "whole percent, nothing to step by, and no text: the number is the whole of it"
                        + describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "the track and the fill are paint, not nodes" + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the name

    @Test
    void theBarInventsNoNameAndTakesTheTooltipOrTheApplicationsOwn() {
        bindBar(new ProgressBar());

        AccessibleNode nameless = progressNode();
        assertEquals("", nameless.name(),
                "the widget holds no string, so it hands none over; a caption beside it is a "
                        + "relation the application declares, never a guess" + describe(tree()));
        assertEquals("", nameless.description(), describe(tree()));

        bar.setTooltip("Uploading");
        frame();
        AccessibleNode byTooltip = progressNode();
        assertEquals("Uploading", byTooltip.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, byTooltip.nameFrom(),
                "the free default reaches this class like any other" + describe(tree()));
        assertEquals("", byTooltip.description(),
                "one string is not both the name and the description" + describe(tree()));

        bar.setAccessibleName("Upload of report.pdf");
        frame();
        AccessibleNode explicit = progressNode();
        assertEquals("Upload of report.pdf", explicit.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, explicit.nameFrom(), describe(tree()));
        assertEquals("Uploading", explicit.description(),
                "once something else named the node, the tooltip is what it describes"
                        + describe(tree()));
        assertEquals(nameless.id(), explicit.id(), "a name is not a rebuild");
    }

    // ------------------------------------------------------------------------------- the value

    @Test
    void theValueIsTheModelAndNotTheEasedFill() {
        bindBar(new ProgressBar());

        bar.setProgress(0.3f);
        frame();

        assertEquals(new ValueFacet(30, 0, 100, 0, null, true), progressNode().value(),
                "one frame in, the fill is still easing; the model is already there"
                        + describe(tree()));
        List<AccessibleEvent> changes = eventsOf(AccessibleEvent.Type.VALUE_CHANGED);
        assertEquals(1, changes.size(), bridge.events.toString());
        assertEquals(progressNode().id(), changes.get(0).nodeId());
        assertEquals(0.0, changes.get(0).oldValue());
        assertEquals(30.0, changes.get(0).newValue());
    }

    @Test
    void theClampReachesTheFacet() {
        bindBar(new ProgressBar());

        bar.setProgress(3f);
        frame();
        assertEquals(100.0, progressNode().value().value(), describe(tree()));
        assertEquals(1f, bar.progress());

        bar.setProgress(-1f);
        frame();
        assertEquals(0.0, progressNode().value().value(), describe(tree()));
        assertEquals(0f, bar.progress());
    }

    @Test
    void aSubPercentMoveIsNotPublishedAndAWholePercentIs() {
        bindBar(new ProgressBar());
        bar.setProgress(0.301f);
        frame();
        int published = bridge.published.size();
        bridge.events.clear();

        bar.setProgress(0.304f);
        frame();

        assertEquals(published, bridge.published.size(),
                "thirty point one and thirty point four are both thirty to a reader, so no copy");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        bar.setProgress(0.306f);
        frame();

        assertEquals(published + 1, bridge.published.size());
        List<AccessibleEvent> changes = eventsOf(AccessibleEvent.Type.VALUE_CHANGED);
        assertEquals(1, changes.size(), bridge.events.toString());
        assertEquals(30.0, changes.get(0).oldValue());
        assertEquals(31.0, changes.get(0).newValue());
    }

    @Test
    void aByteCountingDownloadCostsOneCopyPerWholePercent() {
        bindBar(new ProgressBar());
        int published = bridge.published.size();

        for (int i = 1; i <= 100; i++) {
            bar.setProgress(i / 1000f);
            frame();
        }

        assertEquals(10.0, progressNode().value().value(), describe(tree()));
        assertTrue(bridge.published.size() - published <= 11,
                "a hundred sets that crossed ten whole percent may publish ten or eleven trees, "
                        + "not a hundred: " + (bridge.published.size() - published));
        assertEquals(bridge.published.size() - published,
                eventsOf(AccessibleEvent.Type.VALUE_CHANGED).size(),
                "and exactly one value event per copy: " + bridge.events);
    }

    // ------------------------------------------------------------------------- indeterminate

    @Test
    void indeterminateReplacesTheFacetWithBusyAndTheSweepIsNeverPublished() {
        bindBar(new ProgressBar());
        bar.setProgress(0.4f);
        frame();
        bridge.events.clear();

        bar.setIndeterminate(true);
        frame();

        AccessibleNode busy = progressNode();
        assertTrue(busy.has(Accessible.State.BUSY), describe(tree()));
        assertNull(busy.value(),
                "an indeterminate bar has no meaningful number, so it has no facet at all"
                        + describe(tree()));
        List<AccessibleEvent> events = busyEvents();
        assertEquals(1, events.size(), bridge.events.toString());
        assertEquals(Boolean.TRUE, events.get(0).newValue());
        assertEquals(busy.id(), events.get(0).nodeId());

        int published = bridge.published.size();
        bridge.events.clear();
        double phaseBefore = bar.sweepPhase();
        frameFor(60);

        assertNotEquals(phaseBefore, bar.sweepPhase(),
                "the sweep really did move while those frames ran");
        assertEquals(published, bridge.published.size(),
                "the sweep phase is paint, and a hook that read it would copy the tree per frame");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        bar.setIndeterminate(false);
        frame();

        AccessibleNode settled = progressNode();
        assertFalse(settled.has(Accessible.State.BUSY), describe(tree()));
        assertEquals(new ValueFacet(40, 0, 100, 0, null, true), settled.value(),
                "the facet returns carrying the progress the bar kept" + describe(tree()));
        assertEquals(1, busyEvents().size(), bridge.events.toString());
        assertEquals(Boolean.FALSE, busyEvents().get(0).newValue());
        assertEquals(busy.id(), settled.id());
    }

    @Test
    void aZeroArrivingAfterASweepClearsBusy() {
        bindBar(new ProgressBar());
        bar.setIndeterminate(true);
        frame();
        Assumptions.assumeTrue(progressNode().has(Accessible.State.BUSY));
        bridge.events.clear();

        // The natural sequence: connecting, then nothing of N bytes done yet. The fill's target
        // is already zero, so the transition is silent, and the sweep's ticker stops itself
        // without damaging anything; the setter has to say what changed.
        bar.setProgress(0f);
        frame();

        AccessibleNode node = progressNode();
        assertFalse(node.has(Accessible.State.BUSY),
                "the bar is determinate now and the tree must say so" + describe(tree()));
        assertEquals(new ValueFacet(0, 0, 100, 0, null, true), node.value(), describe(tree()));
        assertEquals(1, busyEvents().size(), bridge.events.toString());
        assertEquals(Boolean.FALSE, busyEvents().get(0).newValue());
    }

    @Test
    void aValueArrivingMidSweepClearsBusyInTheSamePublish() {
        bindBar(new ProgressBar());
        bar.setIndeterminate(true);
        frame();
        bridge.events.clear();
        int published = bridge.published.size();

        bar.setProgress(0.5f);
        frame();

        assertFalse(bar.isIndeterminate(), "the setter clears the flag, as the source says");
        assertEquals(published + 1, bridge.published.size(), "one publish carries both facts");
        AccessibleNode node = progressNode();
        assertFalse(node.has(Accessible.State.BUSY), describe(tree()));
        assertEquals(new ValueFacet(50, 0, 100, 0, null, true), node.value(), describe(tree()));
        assertEquals(1, busyEvents().size(), bridge.events.toString());
        assertEquals(Boolean.FALSE, busyEvents().get(0).newValue());
        assertTrue(eventsOf(AccessibleEvent.Type.VALUE_CHANGED).isEmpty(),
                "a facet that appears is not a value that moved: " + bridge.events);
    }

    // ---------------------------------------------------------------------------- identity

    @Test
    void theNodeKeepsItsIdentityThroughEveryMutationTheBarHas() {
        bindBar(new ProgressBar());
        long id = progressNode().id();

        bar.setProgress(0.6f);
        frame();
        assertEquals(id, progressNode().id());

        bar.setIndeterminate(true);
        frame();
        assertEquals(id, progressNode().id());
        bar.setIndeterminate(false);
        frame();
        assertEquals(id, progressNode().id());

        bar.setEnabled(false);
        frame();
        AccessibleNode disabled = progressNode();
        assertEquals(id, disabled.id());
        assertFalse(disabled.has(Accessible.State.ENABLED),
                "the walk's inherited bit, and nothing of the hook's" + describe(tree()));
        assertEquals(new ValueFacet(60, 0, 100, 0, null, true), disabled.value(), describe(tree()));
        bar.setEnabled(true);
        frame();
        assertTrue(progressNode().has(Accessible.State.ENABLED), describe(tree()));

        bar.setVisible(false);
        frame();
        AccessibleNode hidden = progressNode();
        assertEquals(id, hidden.id());
        assertFalse(hidden.has(Accessible.State.VISIBLE), describe(tree()));
        assertFalse(hidden.has(Accessible.State.SHOWING), describe(tree()));
        assertEquals(new ValueFacet(60, 0, 100, 0, null, true), hidden.value(),
                "hidden is a state, not a different node" + describe(tree()));
        bar.setVisible(true);
        frame();
        assertEquals(id, progressNode().id());
        assertTrue(progressNode().has(Accessible.State.SHOWING), describe(tree()));

        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- geometry

    @Test
    void theBoxIsTheBarsOwnAndASizedBoxAroundItIsNotANode() {
        bar = new ProgressBar();
        Column column = new Column();
        column.add(new SizedBox(360, SizedBox.UNSET, bar));
        bind(column);

        assertFalse(anyNodeIs(tree(), Accessible.Role.GROUP),
                "the sized box is scaffolding and hoists the bar" + describe(tree()));
        AccessibleNode node = progressNode();
        assertEquals(360f, bar.width(), "the fixture really did widen the bar");
        assertEquals(bar.localToSceneX(), node.x(), describe(tree()));
        assertEquals(bar.localToSceneY(), node.y(), describe(tree()));
        assertEquals(bar.width(), node.width(), describe(tree()));
        assertEquals(bar.height(), node.height(),
                "the step's progressThickness, and nothing outside it is painted"
                        + describe(tree()));
        assertTrue(node.height() > 0, describe(tree()));
    }

    // --------------------------------------------------------------------------- the refusal

    @Test
    void setValueIsRefusedAndNothingMoves() throws InterruptedException {
        bindBar(new ProgressBar());
        bar.setProgress(0.25f);
        frame();
        bridge.events.clear();
        long id = progressNode().id();

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(50));
        frame();

        assertEquals(0.25f, bar.progress(),
                "the facet advertises a setter, and the widget's honest answer is no");
        assertEquals(new ValueFacet(25, 0, 100, 0, null, true), progressNode().value(), describe(tree()));
        assertTrue(eventsOf(AccessibleEvent.Type.VALUE_CHANGED).isEmpty(), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    // ------------------------------------------------------------------------- what it costs

    @Test
    void aQuietBarAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindBar(new ProgressBar());
        bar.setProgress(0.7f);
        frame();

        // The fill is a timed transition that damages the bar on every frame it runs for,
        // and a measurement taken while it is mid-flight is a measurement of the animation.
        frameFor(400);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            bar.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // A string formatted inside the hook would be one allocation per damaged frame spent
        // concluding that nothing moved. This is the only place it would be visible.
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            bar.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            bar.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a bar that did not move must cost no memory: the role is an enum, "
                        + "the value is four doubles and the orientation is a bit");
    }
}

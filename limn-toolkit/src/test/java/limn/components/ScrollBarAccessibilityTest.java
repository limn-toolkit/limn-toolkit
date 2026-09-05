package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ValueFacet;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ScrollBar} becomes in the accessible tree: one {@code SCROLL_BAR} node, never a
 * tab stop, with no name, carrying the host model's offset, range and viewport in a writable facet
 * in the model's own points, paged and set from a screen reader through the same private path a
 * track press and a thumb drag take.
 *
 * <p>The widget paints and holds no string, the shape ADR 039 §1.6's predicate deletes and then
 * warns about once per class; the role is what takes it out of that path. So the absence of the
 * walk's warning is asserted after <em>every</em> test rather than in one of them: the walk names a
 * class at most once for the life of the virtual machine, and whichever test runs first is the one
 * that would catch a hook that stopped declaring the role.
 *
 * <p>The cases that pin where ADR 039 §7's row was short or wrong. The row lists no verb, and the
 * source has two pointer paths, a page toward the pointer and a drag, so the node carries the two
 * pages and accepts a set. The row's correction says the bar hides "under {@code ON_SCROLL}", when
 * the early return is on an opacity that is zero under three different absences: the policy
 * {@code HIDDEN}, content that fits, and the fade. The first two are structural and are the two
 * cases the node is ignored; the fade is a paint alpha and a hit-test gate, and the node stays,
 * because the scene's action gate never consults opacity and a reader's set reaches
 * {@code onScrolled()}, which puts the bar on screen exactly as the wheel does. And the row's
 * dilemma about a control "not on screen" is answered by that path, not by the tree.
 *
 * <p>Every case drives the bar's public API, or a host's, on a bound scene, or calls the scene from
 * where a bridge stands, and reads back what the scene published. Nothing constructs a node.
 */
class ScrollBarAccessibilityTest extends AccessibleComponentTestBase {

    /** The viewport every stub here shows, and therefore the bar's one step. */
    private static final float VIEWPORT = 200;

    /** The content behind it, so the maximum offset is 800. */
    private static final float CONTENT = 1000;

    /** A host's scroll state, recorded raw: the host would clamp, and this one does not. */
    private static final class Model implements ScrollBar.Model {
        float content = CONTENT;
        float viewport = VIEWPORT;
        float offset;

        @Override
        public float contentLength() {
            return content;
        }

        @Override
        public float viewportLength() {
            return viewport;
        }

        @Override
        public float offset() {
            return offset;
        }

        @Override
        public void setOffset(float value) {
            offset = value;
        }
    }

    private ScrollBar bar;

    private Model model;

    /** The column the bar's sized box sits in, the root of every stub fixture. */
    private Column root;

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
            assertFalse(subject.contains("limn.components.ScrollBar"),
                    "the bar paints, and it declares a role, so the walk must never say it paints "
                            + "and is deleted; a warning here names a toolkit class an application "
                            + "cannot correct, once per scene that scrolls: " + subject + " " + record.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * A bar over the stub, laid out at its own thickness by {@code VIEWPORT} along its axis, as a
     * host would lay it, inside a column, bound on the wall clock.
     */
    private void bindBar(ScrollBar.Orientation orientation, ScrollBar.Policy policy) {
        bind(fixture(orientation, policy, null));
    }

    /**
     * {@link #bindBar} on a clock the test owns, for the case that holds time still. The bar
     * takes the clock before its policy is set, because setting the policy is what stamps the
     * first-overflow flash, and a stamp taken off the wall clock would never expire on this one.
     */
    private void bindBarOnClock(ScrollBar.Orientation orientation, ScrollBar.Policy policy,
                                LongSupplier clock) {
        Widget under = fixture(orientation, policy, clock);
        bridge = new RecordingBridge();
        window = new StubWindow();
        window.accessibility = bridge;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(under, clock);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    private Widget fixture(ScrollBar.Orientation orientation, ScrollBar.Policy policy,
                           LongSupplier clock) {
        model = new Model();
        bar = new ScrollBar(orientation, model);
        if (clock != null) {
            bar.clock(clock);
        }
        bar.setPolicy(policy);
        boolean vertical = orientation == ScrollBar.Orientation.VERTICAL;
        root = new Column();
        root.add(new SizedBox(vertical ? ScrollBar.thickness() : VIEWPORT,
                vertical ? VIEWPORT : ScrollBar.thickness(), bar));
        return root;
    }

    /** @return the one scroll-bar node in the current tree */
    private AccessibleNode barNode() {
        return node(Accessible.Role.SCROLL_BAR);
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

    /** @return whether any node in {@code tree} carries {@code role} */
    private static boolean anyNodeIs(AccessibleTree tree, Accessible.Role role) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                return true;
            }
        }
        return false;
    }

    /** @return how many nodes in {@code tree} carry {@code role} */
    private static int countOf(AccessibleTree tree, Accessible.Role role) {
        int count = 0;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                count++;
            }
        }
        return count;
    }

    /** The writable facet in the model's points: what every stub fixture publishes but the offset. */
    private static ValueFacet facet(double offset) {
        return new ValueFacet(offset, 0, CONTENT - VIEWPORT, VIEWPORT, null, false);
    }

    /** Moves the stub's offset as a host's own scroll would, and damages the bar as a host does. */
    private void hostScrollsTo(float offset) {
        model.offset = offset;
        bar.refresh();
    }

    // ------------------------------------------------------------------------------- the shape

    @Test
    void aVerticalBarIsOneScrollBarNodeWithTwoPagesNoNameAndNoTabStop() {
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.ALWAYS);

        assertEquals(2, tree().nodeCount(),
                "the window and the bar; the column and the sized box are scaffolding"
                        + describe(tree()));
        AccessibleNode node = barNode();
        assertEquals(0, node.parent(), describe(tree()));
        assertTrue(node.has(Accessible.State.VERTICAL),
                "the axis the value runs along, which for a scroll bar is also its track"
                        + describe(tree()));
        assertFalse(node.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "a scroll bar is never a tab stop" + describe(tree()));
        assertEquals(List.of(), nodesWith(Accessible.State.FOCUSABLE), describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY),
                "the offset is settable, and the facet says so" + describe(tree()));
        assertEquals("", node.name(),
                "the widget holds no string, and every platform speaks the role and the axis; a "
                        + "toolkit string here would be heard beside them" + describe(tree()));
        assertEquals("", node.description(), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.INCREMENT), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.DECREMENT), describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.FOCUS),
                "the two the walk adds are for focusable widgets only" + describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.PRESS), describe(tree()));
        assertEquals(facet(0), node.value(),
                "the model's points: offset, zero, content less viewport, and one viewport as the "
                        + "step, because a track press pages and there is no line step"
                        + describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "the thumb has no identity apart from the value, and the track's two halves are "
                        + "the two verbs" + describe(tree()));
    }

    @Test
    void aHorizontalBarCarriesTheOtherBit() {
        bindBar(ScrollBar.Orientation.HORIZONTAL, ScrollBar.Policy.ALWAYS);

        AccessibleNode node = barNode();
        assertTrue(node.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertEquals(facet(0), node.value(),
                "the same facet: the axis is a bit and never a unit" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the facet

    @Test
    void theFacetIsTheModelInPointsAndPublishesAnOvershootClamped() {
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.ALWAYS);

        hostScrollsTo(300);
        frame();
        assertEquals(facet(300), barNode().value(),
                "a percent or a ratio here would make a set round-trip in the wrong unit"
                        + describe(tree()));

        hostScrollsTo(5000);
        frame();
        assertEquals(facet(800), barNode().value(),
                "the paint does not clamp and a host may hand back an unclamped number "
                        + "transiently; a bridge may refuse a value outside its own range"
                        + describe(tree()));
        assertEquals(5000f, model.offset, "and the model itself was left alone");

        hostScrollsTo(-40);
        frame();
        assertEquals(facet(0), barNode().value(), describe(tree()));
    }

    @Test
    void aScrollIsOneValueChangeOnTheSameNode() {
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.ALWAYS);
        long id = barNode().id();

        hostScrollsTo(300);
        frame();

        List<AccessibleEvent> changes = eventsOf(AccessibleEvent.Type.VALUE_CHANGED);
        assertEquals(1, changes.size(), bridge.events.toString());
        assertEquals(id, changes.get(0).nodeId());
        assertEquals(0.0, changes.get(0).oldValue());
        assertEquals(300.0, changes.get(0).newValue());
        assertEquals(id, barNode().id(), "an id that moved on a scroll would re-announce the bar");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "a scroll is not a change of shape: " + bridge.events);
    }

    // -------------------------------------------------------------------------------- the fade

    @Test
    void theFadeDoesNotRemoveTheNodeOrChangeItsShowing() {
        long[] now = {System.nanoTime()};
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.AUTO);
        bar.clock(() -> now[0]);
        assertTrue(bar.revealing(), "content that has just become scrollable flashes the bar");
        AccessibleNode shown = barNode();
        assertTrue(shown.has(Accessible.State.SHOWING), describe(tree()));
        int published = bridge.published.size();

        now[0] += 5_000_000_000L;
        bar.onHoldElapsed();
        frame();

        assertFalse(bar.revealing(), "the hold has ended and the bar is fading");
        AccessibleNode faded = barNode();
        assertEquals(shown.id(), faded.id(), "the same node" + describe(tree()));
        assertTrue(faded.has(Accessible.State.SHOWING),
                "faded is a paint alpha and a hit-test gate, not a state of the tree: the box is "
                        + "laid out, and a reader's set does not travel through the hit test"
                        + describe(tree()));
        assertTrue(faded.has(Accessible.State.VISIBLE), describe(tree()));
        assertEquals(facet(0), faded.value(), describe(tree()));
        assertEquals(published, bridge.published.size(),
                "the fade changed nothing a reader hears, so nothing was published");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "a hook that consulted the opacity would churn the tree a second after every "
                        + "scroll: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());

        bar.onHostActivity();
        frame();
        assertTrue(bar.revealing(), "the pointer over the host reveals it again");
        assertEquals(shown.id(), barNode().id());
        assertEquals(published, bridge.published.size(), "and the reveal is as silent as the fade");
    }

    // ------------------------------------------------------------------------- the two absences

    @Test
    void aHiddenPolicyIsNoNodeAndAnotherPolicyBringsItBack() {
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.HIDDEN);

        assertFalse(anyNodeIs(tree(), Accessible.Role.SCROLL_BAR),
                "the application asked for no bar, and nobody can operate it: the hit test "
                        + "refuses at every opacity and nothing paints" + describe(tree()));
        assertEquals(1, tree().nodeCount(), "the window alone" + describe(tree()));

        bar.setPolicy(ScrollBar.Policy.AUTO);
        frame();

        assertEquals(facet(0), barNode().value(), describe(tree()));
        assertTrue(bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED) > 0,
                "a policy change is structural, and this is the one place it is allowed to be: "
                        + bridge.events);
    }

    @Test
    void contentThatFitsIsNoNodeAndOverflowBringsItBack() {
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.ALWAYS);
        long id = barNode().id();

        model.content = 150;
        bar.refresh();
        frame();

        assertFalse(anyNodeIs(tree(), Accessible.Role.SCROLL_BAR),
                "a range with a maximum of zero that nothing can operate" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());
        assertEquals(id, eventsOf(AccessibleEvent.Type.NODE_DESTROYED).get(0).nodeId());

        model.content = CONTENT;
        bar.refresh();
        frame();

        assertEquals(facet(0), barNode().value(),
                "a content or size change is the only thing that moves this, never a scroll"
                        + describe(tree()));

        model.content = 200.4f;
        bar.refresh();
        frame();

        assertFalse(anyNodeIs(tree(), Accessible.Role.SCROLL_BAR),
                "half a point of overflow is the bar's own threshold for having any"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the action

    @Test
    void setValueFromTheBridgeReachesTheModelClampsRevealsAndRefusesWhatIsNotANumber()
            throws Exception {
        long[] now = {System.nanoTime()};
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.AUTO);
        bar.clock(() -> now[0]);
        now[0] += 5_000_000_000L;
        bar.onHoldElapsed();
        frame();
        assertFalse(bar.revealing(), "the flash has expired and the bar is faded");
        long id = barNode().id();
        bridge.events.clear();

        assertTrue(perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(300)),
                "accepted, which is not the same as done");
        assertEquals(300f, model.offset, "the model was told, as a drag tells it");
        assertTrue(bar.revealing(),
                "a faded bar accepts the set, and the set reveals it as a wheel does: the control "
                        + "puts itself on screen the moment it is used");
        frame();
        List<AccessibleEvent> changes = eventsOf(AccessibleEvent.Type.VALUE_CHANGED);
        assertEquals(1, changes.size(), bridge.events.toString());
        assertEquals(id, changes.get(0).nodeId());
        assertEquals(0.0, changes.get(0).oldValue());
        assertEquals(300.0, changes.get(0).newValue());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press and nothing else: " + bridge.events);

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(5000));
        assertEquals(800f, model.offset, "the clamp a drag reaches, before the host's own");

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(-5));
        assertEquals(0f, model.offset);

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(300));
        assertEquals(300f, model.offset);
        perform(id, Accessible.Action.SET_VALUE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfText("400"));
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(Double.NaN));
        perform(id, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.POSITIVE_INFINITY));
        assertEquals(300f, model.offset,
                "a set without a number, or with one the clamp would pass through untouched, is "
                        + "refused before the model hears of it");
    }

    @Test
    void theTwoPagesMoveByOneViewportAndDoNotMirror() throws Exception {
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.ALWAYS);
        long id = barNode().id();

        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE));
        assertEquals(VIEWPORT, model.offset, "one viewport, which is what a track press pages by");
        assertTrue(perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE));
        assertEquals(0f, model.offset);
        assertTrue(perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE));
        assertEquals(0f, model.offset, "a page past the start is a page to the start");
        hostScrollsTo(700);
        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE));
        assertEquals(800f, model.offset, "and a page past the end is a page to the end");

        // The same on a horizontal bar reading right to left, where the pointer's paging flips
        // its sign because the thumb walks the other way. The verbs are defined on the value,
        // which is a magnitude in both directions, so they do not.
        bindBar(ScrollBar.Orientation.HORIZONTAL, ScrollBar.Policy.ALWAYS);
        root.setLayoutDirection(LayoutDirection.RTL);
        frame();
        AccessibleNode mirrored = barNode();
        assertTrue(mirrored.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertEquals(facet(0), mirrored.value(), describe(tree()));

        assertTrue(perform(mirrored.id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE));
        assertEquals(VIEWPORT, model.offset,
                "an increment grows the offset in both layout directions; an action wired to the "
                        + "pointer arm's screen-direction sign would page backwards here");
        // The stub records the offset and damages nothing, where every host's setOffset
        // invalidates; the refresh stands in for the host's damage so the next frame walks.
        bar.refresh();
        frame();
        assertEquals(facet(VIEWPORT), barNode().value(), describe(tree()));
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.ALWAYS);
        long id = barNode().id();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_TEXT, new Accessible.Argument.OfText("300"));
        perform(id, Accessible.Action.FOCUS, Accessible.Argument.NONE);
        frame();

        assertEquals(0f, model.offset, "the hook answers false for what it does not offer");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // ------------------------------------------------------------------------------- the guards

    @Test
    void aDisabledBarOrADisabledContainerRefusesEveryVerbAndTheBarStaysInTheTree()
            throws Exception {
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.ALWAYS);
        bar.setEnabled(false);
        frame();

        AccessibleNode node = barNode();
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertEquals(facet(0), node.value(),
                "a disabled bar is heard as disabled rather than vanishing" + describe(tree()));
        bridge.events.clear();

        perform(node.id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(node.id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(300));
        frame();

        assertEquals(0f, model.offset, "a disabled control was operated");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        // The bar's own flag left alone, inside a container that is not enabled: the widget's
        // own guard passes and the scene's ancestor gate is what refuses, as the keyboard does.
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.ALWAYS);
        root.setEnabled(false);
        frame();

        assertTrue(bar.isEnabled(), "the fixture has to leave the bar's own flag alone");
        assertFalse(barNode().has(Accessible.State.ENABLED),
                "the bit is inherited down the walk" + describe(tree()));
        bridge.events.clear();

        perform(barNode().id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(barNode().id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(300));
        frame();

        assertEquals(0f, model.offset,
                "a control inside a disabled container is one the keyboard refuses too");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void anIdThatArrivesAfterTheBarLeftTheTreeIsRefusedByTheBarItself() throws Exception {
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.ALWAYS);
        long id = barNode().id();

        // No frame between the change and the request: the published tree still carries the id,
        // so the scene's own check passes it, the post lands, and the owner still resolves. The
        // scene's gate re-checks the ancestors, showing and modality, none of which moved; only
        // the bar knows it is one of its two absences now.
        bar.setPolicy(ScrollBar.Policy.HIDDEN);
        assertTrue(perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(300)),
                "accepted at the door, because the id is still in the published snapshot");
        assertEquals(0f, model.offset, "and refused on arrival by the bar's own guard");
        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE));
        assertEquals(0f, model.offset);

        bar.setPolicy(ScrollBar.Policy.ALWAYS);
        frame();
        assertEquals(id, barNode().id(), "the node never left, so it is the same node");
        model.content = 150;
        assertTrue(perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(100)));
        assertEquals(0f, model.offset,
                "content that fits is the other absence, and a set over it would hand the host a "
                        + "range with a maximum of zero");

        // And once a frame has published the absence, the door itself refuses the stale id.
        bar.refresh();
        frame();
        assertFalse(anyNodeIs(tree(), Accessible.Role.SCROLL_BAR), describe(tree()));
        assertFalse(perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(100)),
                "an id that is not in the published snapshot is the one refusal that is immediate");
        assertEquals(0f, model.offset);
    }

    // ---------------------------------------------------------------------------- identity

    @Test
    void theNodeKeepsItsIdentityThroughEverythingTheBarDoesShortOfLeaving() {
        long[] now = {System.nanoTime()};
        bindBar(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.AUTO);
        bar.clock(() -> now[0]);
        long id = barNode().id();

        hostScrollsTo(300);
        frame();
        assertEquals(id, barNode().id(), "a scroll");

        now[0] += 5_000_000_000L;
        bar.onHoldElapsed();
        frame();
        assertFalse(bar.revealing());
        assertEquals(id, barNode().id(), "the fade");

        bar.refresh();
        frame();
        assertEquals(id, barNode().id(), "a refresh that found nothing changed");

        bar.setEnabled(false);
        frame();
        assertEquals(id, barNode().id(), "disabling");
        bar.setEnabled(true);
        frame();

        bar.setVisible(false);
        frame();
        AccessibleNode hidden = barNode();
        assertEquals(id, hidden.id());
        assertFalse(hidden.has(Accessible.State.VISIBLE), describe(tree()));
        assertFalse(hidden.has(Accessible.State.SHOWING),
                "hidden by the application is what not showing means; the fade is not"
                        + describe(tree()));
        assertEquals(facet(300), hidden.value(), "hidden is a state, not a different node");
        bar.setVisible(true);
        frame();
        assertEquals(id, barNode().id());

        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- a real host

    @Test
    void inAScrollViewTheBoxIsTheWholeStripOnTheTrailingSideAndAPageScrollsTheView()
            throws Exception {
        ScrollView view = new ScrollView(new SizedBox(100, 1000));
        root = new Column();
        root.add(new SizedBox(300, 200, view));
        bind(root);

        assertFalse(anyNodeIs(tree(), Accessible.Role.GROUP),
                "the sized boxes and the view are scaffolding until the view's own step"
                        + describe(tree()));
        AccessibleNode node = barNode();
        assertEquals(300f, view.width(), "the fixture really did size the view");
        float t = ScrollBar.thickness();
        assertEquals(view.localToSceneX() + 300 - t, node.x(),
                "the strip on the trailing side" + describe(tree()));
        assertEquals(view.localToSceneY(), node.y(), describe(tree()));
        assertEquals(t, node.width(), describe(tree()));
        assertEquals(200f, node.height(),
                "the whole strip, which is the rectangle the hit test accepts while shown, and not "
                        + "the thumb painted inside it" + describe(tree()));
        assertEquals(new ValueFacet(0, 0, 800, 200, null, false), node.value(),
                "the view's own model: content less viewport, and the viewport as the step"
                        + describe(tree()));
        long id = node.id();

        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE));
        frame();

        assertEquals(200f, view.offsetY(), "a page from a reader scrolls the view");
        List<AccessibleEvent> changes = eventsOf(AccessibleEvent.Type.VALUE_CHANGED);
        assertEquals(1, changes.size(),
                "the host's scroll damages the bar, so the tree learns the offset with no help: "
                        + bridge.events);
        assertEquals(id, changes.get(0).nodeId());
        assertEquals(200.0, changes.get(0).newValue());

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(5000));
        frame();
        assertEquals(800f, view.offsetY(), "the host's own clamp runs underneath the bar's");
        assertEquals(new ValueFacet(800, 0, 800, 200, null, false), barNode().value(), describe(tree()));

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();
        AccessibleNode mirrored = barNode();
        assertEquals(id, mirrored.id());
        assertEquals(view.localToSceneX(), mirrored.x(),
                "reading right to left the host lays the strip on the left; a host that stopped "
                        + "doing so would put a reader's cursor over the content" + describe(tree()));
        assertEquals(t, mirrored.width(), describe(tree()));
        assertEquals(200f, mirrored.height(), describe(tree()));
        assertTrue(mirrored.has(Accessible.State.VERTICAL),
                "a vertical bar's travel never mirrors" + describe(tree()));
    }

    @Test
    void aScrollViewWithBothBarsPublishesTwoNodesEachOutsideTheCornerSquare() {
        ScrollView view = new ScrollView(new SizedBox(1000, 1000), true, true);
        root = new Column();
        root.add(new SizedBox(300, 200, view));
        bind(root);

        assertEquals(2, countOf(tree(), Accessible.Role.SCROLL_BAR), describe(tree()));
        float t = ScrollBar.thickness();
        AccessibleNode vertical = nodesWith(Accessible.State.VERTICAL).get(0);
        AccessibleNode horizontal = nodesWith(Accessible.State.HORIZONTAL).get(0);
        assertEquals(Accessible.Role.SCROLL_BAR, vertical.role(), describe(tree()));
        assertEquals(Accessible.Role.SCROLL_BAR, horizontal.role(), describe(tree()));
        assertEquals(1, nodesWith(Accessible.State.VERTICAL).size(), describe(tree()));
        assertEquals(1, nodesWith(Accessible.State.HORIZONTAL).size(), describe(tree()));

        float x = view.localToSceneX();
        float y = view.localToSceneY();
        assertEquals(x + 300 - t, vertical.x(), describe(tree()));
        assertEquals(y, vertical.y(), describe(tree()));
        assertEquals(t, vertical.width(), describe(tree()));
        assertEquals(200 - t, vertical.height(),
                "shortened by the corner square the two thumbs would otherwise share"
                        + describe(tree()));
        assertEquals(x, horizontal.x(), describe(tree()));
        assertEquals(y + 200 - t, horizontal.y(), describe(tree()));
        assertEquals(300 - t, horizontal.width(), describe(tree()));
        assertEquals(t, horizontal.height(), describe(tree()));
        assertEquals(new ValueFacet(0, 0, 700, 300, null, false), horizontal.value(),
                "each bar's range is its own axis's, and under the default overlay layout the "
                        + "viewport is the whole width: the strip lies over the content and the "
                        + "corner square shortens the track, never the range" + describe(tree()));
    }

    // ------------------------------------------------------------------------- what it costs

    @Test
    void aBarMidFadeDamagedEveryFrameAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        // On a clock this test owns, shared by the scene's transitions and the bar's hold: the
        // fade-in is let run, the hold is let expire and the fade-out is started, and then time
        // stops, so both windows below measure the same frame -- a bar whose opacity is mid-fade
        // and whose ticker is alive -- which is the frame the toolkit renders for a second after
        // every scroll and every pointer move over a scroll view.
        long[] now = {1_000_000_000L};
        bindBarOnClock(ScrollBar.Orientation.VERTICAL, ScrollBar.Policy.AUTO, () -> now[0]);
        hostScrollsTo(300);
        for (int i = 0; i < 12; i++) {
            now[0] += (long) (Scene.MAX_TICK_SECONDS * 1e9);
            bar.invalidate();
            frame();
        }
        bar.onHoldElapsed();
        assertFalse(bar.revealing(), "the hold has expired on the injected clock");
        frame();
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            bar.onHostActivity();
            bar.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage and pointer activity change nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // A string formatted inside the hook, or the variable-argument action call, would be one
        // allocation per damaged frame spent concluding that nothing moved, on the one widget
        // damaged for a second after every scroll in the toolkit. This is the only place it would
        // be visible.
        long withAReaderAttached = AllocationProbe.leastAllocatedBy(() -> {
            bar.onHostActivity();
            bar.invalidate();
            frame();
        }, 60);

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        bridge.listening = false;
        long withNobodyListening = AllocationProbe.leastAllocatedBy(() -> {
            bar.onHostActivity();
            bar.invalidate();
            frame();
        }, 60);

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a bar that did not move must cost no memory: the role is an enum, the "
                        + "value is four doubles read from five primitives, the axis is a bit and "
                        + "the two verbs are two bits");
    }
}

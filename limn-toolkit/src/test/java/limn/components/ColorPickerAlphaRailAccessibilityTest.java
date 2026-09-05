package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.ValueFacet;
import limn.graphics.Color;
import limn.scene.LayoutDirection;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ColorPicker}'s alpha rail becomes in the accessible tree: one horizontal
 * {@code SLIDER} node named by the letter beside it, carrying a writable value facet
 * <b>in whole percent</b> and offering the two steps, operated from a screen reader through the
 * same private mutator a drag and an arrow key reach, commit included.
 *
 * <p>The percent is what this file exists to pin. ADR 039 §7's row says only
 * {@code ValueFacet}, and the widget's model field is a float in {@code 0..1} while everything a
 * user can see or do with it counts in hundredths: the rail snaps to a hundredth, its arrow step
 * is a hundredth, and the stepper on the same line is a {@code Spinner(0, 100, 1)} the picker
 * writes {@code Math.round(alpha * 100)} into. Read literally the row would publish
 * {@code 0.33333334} for a colour that arrived through an eight-digit hex, and would advertise a
 * grid the rail refuses to be dragged onto.
 *
 * <p>Four more places the row is short, each with a case below. It groups the abstract {@code Rail}
 * with both of its subclasses, whose facets are in different units from different models, so the
 * hook is on this concrete class and {@code Rail} stays hookless. It omits {@code SET_VALUE},
 * which is advertised by the writable facet's presence and is the verb a reader actually reaches
 * for on a slider. It says the name is "declared by the picker" without saying how &mdash; the
 * walk offers a child only to its <em>direct</em> parent, and this rail's parent is an
 * {@code Expanded}, so the link is a standing one made in the constructor through
 * {@code Label#setLabelFor} and never a per-frame child hook. And it is silent on orientation and
 * on mirroring in the one widget whose documentation is about mirroring: the rail reflects its
 * sweep, its thumb, its press inversion and its Left and Right arms, and none of that touches the
 * facet or the two verbs.
 *
 * <p>Every case drives the picker's public API, and {@code Widget}'s, on a bound scene, or calls
 * the scene from where a bridge stands. Nothing constructs a node.
 */
class ColorPickerAlphaRailAccessibilityTest extends AccessibleComponentTestBase {

    /** The exact class an application must never find in its log because of this step. */
    private static final String THE_RAIL = "limn.components.ColorPicker$AlphaRail";

    /** The letter that names the alpha line, and therefore the rail. */
    private static final String A = "A";

    private ColorPicker picker;

    /** The column the picker sits in, the root of every fixture. */
    private Column root;

    /** Every alpha the application's change handler was given, in order. */
    private final List<Float> changed = new ArrayList<>();

    /** Every alpha the application's commit handler was given, in order. */
    private final List<Float> committed = new ArrayList<>();

    /** Every record the walk logged while a test was running; see {@link #theLogNamesTheRail}. */
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

    /**
     * The rail is focusable and paints, so a step that dropped its role would publish it as an
     * unknown control naming a toolkit class in an application's log.
     *
     * <p>Scoped to this one class rather than to the picker: the channel rails, the steppers, the
     * hex field, the three painted parts and the strip buttons are all still waiting for their own
     * steps, and every one of them warns under a name that starts with the picker's. Both warnings
     * are logged once per class for the whole process, so whichever case runs first is the one
     * that would see it, which is why the check runs after every one.
     */
    @AfterEach
    void theLogNamesTheRail() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
            Object[] parameters = record.getParameters();
            if (parameters == null) {
                continue;
            }
            for (Object parameter : parameters) {
                assertNotEquals(THE_RAIL, parameter,
                        "the alpha rail declares a role, so the walk must never publish it "
                                + "UNKNOWN nor say it paints and is deleted: " + record.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One picker at the width the gallery gives it, so the alpha rail has a line to itself. */
    private void bindPicker() {
        picker = new ColorPicker();
        picker.onChange(color -> changed.add(color.a()));
        picker.onCommit(color -> committed.add(color.a()));
        root = new Column();
        root.add(new SizedBox(380, SizedBox.UNSET, picker));
        bind(root);
    }

    /**
     * The alpha rail's node, found by role <em>and</em> by name.
     *
     * <p>Neither alone will do for long. The three channel rails take the same role in their own
     * step, so {@code node(SLIDER)} stops being unique then; and the caption beside this rail
     * holds the same letter it lends it, so {@code node("A")} finds the label first.
     *
     * @return the one slider on the alpha line
     */
    private AccessibleNode alphaNode() {
        AccessibleNode found = null;
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.role() == Accessible.Role.SLIDER && node.name().equals(A)) {
                if (found != null) {
                    throw new AssertionError("more than one alpha rail" + describe(tree()));
                }
                found = node;
            }
        }
        if (found == null) {
            throw new AssertionError("no alpha rail" + describe(tree()));
        }
        return found;
    }

    /**
     * @param name the accessible name to look for
     * @return every node carrying it, in tree order
     */
    private List<AccessibleNode> named(String name) {
        List<AccessibleNode> found = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).name().equals(name)) {
                found.add(tree().node(i));
            }
        }
        return found;
    }

    /**
     * @param nodeId the node to read the value changes of
     * @return every value change raised on it so far, in order
     */
    private List<AccessibleEvent> valueEventsOn(long nodeId) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.VALUE_CHANGED && event.nodeId() == nodeId) {
                found.add(event);
            }
        }
        return found;
    }

    /**
     * @param node the node to read
     * @param kind the relation to look for
     * @return the target identifier, or {@code 0} when there is no such relation
     */
    private static long relationTarget(AccessibleNode node, Accessible.Relation kind) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return relation.target();
            }
        }
        return 0;
    }

    /** A writable facet over whole percent: what every fixture here publishes but the number. */
    private static ValueFacet percent(double value) {
        return new ValueFacet(value, 0, 100, 1, null, false);
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void theAlphaRailIsOneHorizontalWritableSliderInWholePercent() {
        bindPicker();

        AccessibleNode node = alphaNode();
        assertTrue(node.has(Accessible.State.HORIZONTAL),
                "the class has one axis: every expression in it, the travel, the thumb's centre "
                        + "and the press inversion, is horizontal" + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "focusable from Rail's constructor" + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY),
                "the alpha is settable, and the facet says so" + describe(tree()));
        assertEquals(percent(100), node.value(),
                "an opaque picker is a hundred percent alpha, on the same grid as the stepper "
                        + "beside it" + describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertEquals(Set.of(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT,
                        Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW),
                node.actions().actions(),
                "the two steps and the walk's two. SET_VALUE is advertised by the writable "
                        + "facet's presence and is never in this list; PRESS and TOGGLE are not "
                        + "what a rail does" + describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "the checkerboard, the sweep and the thumb are paint, not nodes" + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the units

    @Test
    void theFacetIsWholePercentAndNeverTheModelsFraction() {
        bindPicker();

        picker.setInitialColor(Color.rgb(0x3366CC).withAlpha(0.5f));
        frame();
        assertEquals(percent(50), alphaNode().value(), describe(tree()));

        picker.setColor(Color.rgb(0x3366CC).withAlpha(0f));
        frame();
        assertEquals(percent(0), alphaNode().value(), describe(tree()));

        // An eight-digit hex is where a fraction that is not a whole percent comes from, and it
        // is the case the row read literally would publish as 0.33333334.
        picker.setColor(Color.rgb(0x3366CC).withAlpha(85 / 255f));
        frame();
        assertEquals(percent(33), alphaNode().value(),
                "the published number is where the thumb is, rounded to the only resolution any "
                        + "gesture on this rail can produce" + describe(tree()));
        assertEquals(Math.round(picker.color().a() * 100), alphaNode().value().value(),
                "which is the same expression the picker writes into the stepper beside it"
                        + describe(tree()));
        assertEquals(0, alphaNode().value().min(), describe(tree()));
        assertEquals(100, alphaNode().value().max(), describe(tree()));
        assertEquals(1, alphaNode().value().step(),
                "and the grid a set snaps onto is one percent, not the model's hundredth"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the action

    @Test
    void aStepFromTheBridgeMovesOnePercentAndTellsTheApplicationTwice() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x3366CC).withAlpha(0.5f));
        frame();
        long id = alphaNode().id();
        bridge.events.clear();

        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE),
                "accepted, which is not the same as done");
        frame();

        assertEquals(0.51f, picker.color().a(),
                "the hook reaches moveTo, which is what a drag and an arrow reach");
        assertEquals(List.of(0.51f), changed,
                "and moveTo calls changed(), which is what tells the application; setColor is the "
                        + "silent path and would have said nothing");
        assertEquals(List.of(0.51f), committed,
                "then the commit, as a whole gesture: a reader's step has no release to follow");
        List<AccessibleEvent> raised = valueEventsOn(id);
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(50.0, raised.get(0).oldValue());
        assertEquals(51.0, raised.get(0).newValue());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press and nothing else: " + bridge.events);

        bridge.events.clear();
        perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(0.5f, picker.color().a());
        assertEquals(List.of(0.51f, 0.5f), changed);
        assertEquals(List.of(0.51f, 0.5f), committed);
        raised = valueEventsOn(id);
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(51.0, raised.get(0).oldValue());
        assertEquals(50.0, raised.get(0).newValue());
    }

    @Test
    void aStepThroughAFractionOfAPercentStillMovesTheNumberByOne() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x3366CC).withAlpha(85 / 255f));
        frame();
        long id = alphaNode().id();

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(percent(34), alphaNode().value(),
                "a step is one percent of the published domain even when the model carries a "
                        + "fraction of one from a colour set in code" + describe(tree()));
        assertEquals(0.34f, picker.color().a());
    }

    @Test
    void aStepAtTheEndCommitsTheValueHeldAndPublishesNothing() throws Exception {
        bindPicker();
        long id = alphaNode().id();
        int published = bridge.published.size();

        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE),
                "accepted: the verb ran, and what it ran was a commit");
        frame();

        assertEquals(1f, picker.color().a());
        assertEquals(List.of(), changed, "nothing moved, so nothing changed");
        assertEquals(List.of(1f), committed,
                "but the user chose the end, exactly as End at max commits");
        assertEquals(List.of(), valueEventsOn(id), bridge.events.toString());
        assertEquals(published, bridge.published.size(),
                "a value that did not move invalidates nothing and publishes nothing");
    }

    @Test
    void setValueIsInThePublishedDomainAndRefusesWhatIsNotANumber() throws Exception {
        bindPicker();
        long id = alphaNode().id();

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(33.7));
        frame();
        assertEquals(percent(34), alphaNode().value(),
                "the widget's own whole-percent snap, reached through the same moveTo a drag does"
                        + describe(tree()));
        assertEquals(0.34f, picker.color().a());

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1000));
        frame();
        assertEquals(percent(100), alphaNode().value(), "the clamp" + describe(tree()));

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(-5));
        frame();
        assertEquals(percent(0), alphaNode().value(), describe(tree()));

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(0.5));
        frame();
        assertEquals(percent(1), alphaNode().value(),
                "a half is half a percent and rounds to one, not to half of the model's range: "
                        + "the argument arrives in the domain the facet published" + describe(tree()));
        assertEquals(List.of(0.34f, 1f, 0f, 0.01f), changed);
        assertEquals(List.of(0.34f, 1f, 0f, 0.01f), committed);

        bridge.events.clear();
        // The boolean means accepted and not done, so what a refusal looks like from here is the
        // colour, the handlers and the event list all standing still.
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(Double.NaN));
        perform(id, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.POSITIVE_INFINITY));
        perform(id, Accessible.Action.SET_VALUE, Accessible.Argument.NONE);
        frame();

        assertEquals(0.01f, picker.color().a(),
                "clamp01 passes NaN through and Math.round(Float.NaN) is zero, so an unguarded "
                        + "one would snap the colour to fully transparent and report it as a user "
                        + "change");
        assertEquals(percent(1), alphaNode().value(), describe(tree()));
        assertEquals(List.of(0.34f, 1f, 0f, 0.01f), changed, "refused before any handler");
        assertEquals(List.of(0.34f, 1f, 0f, 0.01f), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindPicker();
        long id = alphaNode().id();
        bridge.events.clear();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_TEXT, new Accessible.Argument.OfText("50"));
        frame();

        assertEquals(1f, picker.color().a());
        assertEquals(List.of(), changed, "the hook answers false for what it does not offer");
        assertEquals(List.of(), committed);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- the direction

    @Test
    void theStepsDoNotMirrorAlthoughEverythingElseAboutThisRailDoes() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x3366CC).withAlpha(0.5f));
        frame();
        AccessibleNode leftToRight = alphaNode();

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode mirrored = alphaNode();
        assertEquals(leftToRight.id(), mirrored.id(), "a direction is not a rebuild");
        assertEquals(leftToRight.value(), mirrored.value(),
                "the facet is the value, and a value has no side" + describe(tree()));
        assertEquals(picker.alphaRail().localToSceneX(), mirrored.x(), describe(tree()));
        assertEquals(picker.alphaRail().localToSceneY(), mirrored.y(), describe(tree()));

        perform(mirrored.id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(0.51f, picker.color().a(),
                "an increment is a direction of the value, as Up is, and never a side of the "
                        + "rail: the hook uses the two arms this class does not mirror, and a hook "
                        + "built from Left and Right would run backwards in Arabic and Hebrew");
        assertEquals(percent(51), alphaNode().value(), describe(tree()));
    }

    // -------------------------------------------------------------------------------- the modes

    @Test
    void turningAlphaOffLeavesTheSliderAndTakesTheOperation() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x3366CC).withAlpha(0.5f));
        frame();
        long id = alphaNode().id();

        picker.setAlphaEnabled(false);
        frame();

        AccessibleNode off = alphaNode();
        assertEquals(id, off.id(),
                "the line is hidden, not removed: a mode flip destroys no node" + describe(tree()));
        assertFalse(off.has(Accessible.State.VISIBLE), describe(tree()));
        assertFalse(off.has(Accessible.State.SHOWING), describe(tree()));
        assertFalse(off.has(Accessible.State.FOCUSABLE),
                "which is what the keyboard says about it too" + describe(tree()));
        assertTrue(off.actions().has(Accessible.Action.INCREMENT),
                "the verbs say what the control offers, and the missing SHOWING bit is what says "
                        + "it is not on screen; §1.9's gate is what refuses on arrival, and a hook "
                        + "that withheld them here would be a second answer to a question the "
                        + "scene has already answered" + describe(tree()));
        assertFalse(off.actions().has(Accessible.Action.FOCUS),
                "the walk's two go with the tab stop" + describe(tree()));
        assertEquals(1f, picker.color().a(), "and the colour is opaque while the mode is off");

        // Accepted, because the identifier is in the published tree, and dead on arrival: the
        // scene re-checks isShowing() on the thread that owns the widget and returns before the
        // hook is reached, which is the same wall the pointer and the keyboard hit.
        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE));
        assertTrue(perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(90)));
        frame();

        assertEquals(List.of(), changed);
        assertEquals(List.of(), committed);

        picker.setAlphaEnabled(true);
        frame();

        assertEquals(id, alphaNode().id(), describe(tree()));
        assertEquals(0.5f, picker.color().a(),
                "a reader could not set an alpha the pointer and the keyboard cannot reach");
        assertEquals(percent(50), alphaNode().value(), describe(tree()));
        assertTrue(alphaNode().has(Accessible.State.FOCUSABLE), describe(tree()));
    }

    @Test
    void aDisabledPickerRefusesEveryVerbAndKeepsTheSliderInTheTree() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x3366CC).withAlpha(0.5f));
        frame();
        long id = alphaNode().id();

        picker.setEnabled(false);
        frame();

        AccessibleNode node = alphaNode();
        assertEquals(id, node.id());
        assertFalse(node.has(Accessible.State.ENABLED),
                "the bit is inherited down the walk from the picker" + describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertEquals(percent(50), node.value(),
                "a disabled rail is heard as disabled rather than vanishing" + describe(tree()));
        bridge.events.clear();

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(90));
        frame();

        assertEquals(0.5f, picker.color().a(), "a disabled control was operated");
        assertEquals(List.of(), changed);
        assertEquals(List.of(), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // --------------------------------------------------------------------------------- the name

    @Test
    void theRailIsNamedByTheLetterBesideItAndCarriesTheRelationBothWays() {
        bindPicker();

        AccessibleNode rail = alphaNode();
        assertEquals(A, rail.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, rail.nameFrom(),
                "the rail paints no text, so a CONTENT name would be a lie, and a LABEL "
                        + "provenance without the relation would be a provenance the enum's own "
                        + "definition denies" + describe(tree()));

        AccessibleNode caption = null;
        for (AccessibleNode node : named(A)) {
            if (node.role() == Accessible.Role.LABEL) {
                caption = node;
            }
        }
        assertNotEquals(null, caption, "the letter is a Label of its own" + describe(tree()));
        assertEquals(caption.id(), relationTarget(rail, Accessible.Relation.LABELLED_BY),
                "so a reader that would rather walk to the caption can" + describe(tree()));
        assertEquals(rail.id(), relationTarget(caption, Accessible.Relation.LABEL_FOR),
                "and the caption says which control it names; the constructor's setLabelFor is "
                        + "what puts both halves there, because the walk offers a child only to "
                        + "its DIRECT parent and this rail's is an Expanded" + describe(tree()));

        picker.setAccessibleName("Fill colour");
        frame();

        assertEquals(A, alphaNode().name(),
                "an application naming the picker names the picker" + describe(tree()));
        assertEquals("Fill colour", node(Accessible.Role.COLOR_CHOOSER).name(), describe(tree()));
    }

    // ----------------------------------------------------------------------------- the geometry

    @Test
    void theBoxIsTheHitTargetAndNotThePaintedBand() {
        bindPicker();

        AccessibleNode node = alphaNode();
        assertEquals(picker.alphaRail().localToSceneX(), node.x(), describe(tree()));
        assertEquals(picker.alphaRail().localToSceneY(), node.y(), describe(tree()));
        assertEquals(picker.alphaRail().width(), node.width(),
                "the Expanded above it hands the rail (0, 0, width(), height()), so the rail's "
                        + "box is the share's box" + describe(tree()));
        assertEquals(picker.alphaRail().height(), node.height(), describe(tree()));

        SizeTokens tokens = Theme.current().tokensFor(picker.alphaRail());
        assertEquals(Math.max(Strokes.MIN_HIT_TARGET,
                        tokens.colorThumbH() + 2 * Strokes.FOCUS_GAP_SLIDER + Strokes.FOCUS_RING),
                node.height(),
                "the rail's own measure, floored at the reachable target" + describe(tree()));
        assertTrue(node.height() > tokens.colorRailH(),
                "and taller than the band it paints: a ten point rail is a thing to look at and a "
                        + "ten point drag target is a thing to miss, so a magnifier and a "
                        + "click-at-point client are given the target and not the picture"
                        + describe(tree()));
    }

    // ---------------------------------------------------------------------------- what it costs

    @Test
    void aQuietRailAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindPicker();
        picker.setColor(Color.rgb(0x3366CC).withAlpha(0.42f));
        frame();
        scene.requestFocus(picker.alphaRail());
        frame();

        // The rail's focus outline thickens on the scene's clock, and it damages this widget on every
        // frame it runs for; a measurement taken while it is mid-flight measures the animation.
        settleAnimations(picker);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            picker.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            picker.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            picker.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a rail that did not move must cost no memory: the role is an enum, "
                        + "the percent is an int widened to a double, the orientation is a bit and "
                        + "the two verbs are two bits. A \"42%\" formatted in the hook, or the "
                        + "variable-argument action call, would be one allocation per damaged "
                        + "frame — and every frame of a focus fade is such a frame");
    }
}

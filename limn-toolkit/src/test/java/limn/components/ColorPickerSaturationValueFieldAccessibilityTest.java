package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ValueFacet;
import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * What a {@link ColorPicker}'s saturation/value plane becomes in the accessible tree: one
 * {@code CANVAS} named "Saturation and value", over two synthetic {@code SLIDER} children — one
 * horizontal for saturation, one vertical for value — each carrying a writable value facet in
 * whole percent and offering the two steps, operated from a screen reader through the same
 * private mutator a drag and the picker's own arrows reach.
 *
 * <p>The case this file exists to fail on first is that the plane was <b>absent</b>. It is never
 * focusable and declared nothing, so the transparency predicate dropped it, and because it paints
 * its own content the walk named a toolkit class in an application's log for the picture that is
 * the colour space itself. The three fixes that warning recommends are all out of reach here — the
 * class is private and nothing the picker exposes hands an application the instance — so the
 * widget carries its own names or nobody can.
 *
 * <p>Where ADR 039 §7's row is wrong, and it is wrong in more than it is right. The role is
 * exactly right and the two dashes beside it are not. <b>The dash under actions</b> says the plane
 * offers nothing, and a press on it sets two channels at once and tells the application, which
 * §1.6 says may never be deleted with the box that carried it — and the picker's own landed hook
 * had already promised the plane's step verbs to "the node carrying that box", which is this one.
 * <b>The dash under facets</b> reads as a leaf, and the node is a small subtree: one node carries
 * one pair of steps, so two axes are two children. <b>"Its description names both channels"</b> is
 * wrong twice over: a description holding the numbers would have no source to compare against and
 * would cost one string per damaged frame, and a description merely naming the channels repeats
 * what the node's own name and its two children's names already say. And §4.1's "a {@code CANVAS}
 * with a described value" is right about the role and unbuildable as written, because a value text
 * handed over with no number beside it is dropped by the builder without a word.
 *
 * <p>Two more the row is silent on, each with a case below. <b>The guard is the picker's enabled
 * flag and not the plane's</b>: a widget's own flag is all {@code isEnabled()} answers and
 * {@code setEnabled} propagates to nothing, so the obvious guard written on this private inner
 * class would hold nothing back. And <b>the plane does not turn round</b>, in the one widget whose
 * documentation is about mirroring: saturation runs left to right in every language, its hook
 * reads no layout direction, and an increment raises the channel in both.
 *
 * <p>Every case drives the picker's public API, and {@code Widget}'s, on a bound scene, or calls
 * the scene from where a bridge stands. Nothing constructs a node.
 */
class ColorPickerSaturationValueFieldAccessibilityTest extends AccessibleComponentTestBase {

    /** The exact class an application must never find in its log because of this step. */
    private static final String THE_PLANE = "limn.components.ColorPicker$SaturationValueField";

    private ColorPicker picker;

    /** The column the picker sits in, the root of every fixture. */
    private Column root;

    /** Every colour the application's change handler was given, in order. */
    private final List<Color> changed = new ArrayList<>();

    /** Every colour the application's commit handler was given, in order. */
    private final List<Color> committed = new ArrayList<>();

    /** Every record the walk logged while a test was running; see {@link #theLogNamesThePlane}. */
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

    private Locale before;

    @BeforeEach
    void captureTheWalksLogAndPinTheLanguage() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
        before = limn.i18n.I18n.processLocale();
        // French is the one shipped language that translates these names, and one case below asks
        // for it on the picker's own subtree, so the rest are pinned to the language they assert
        // in.
        limn.i18n.I18n.setLocale(Locale.ENGLISH);
    }

    /**
     * The plane names itself, so the walk has nothing to say about it: it must never be dropped as
     * a thing that paints and says nothing, and never published {@code UNKNOWN}.
     *
     * <p>Scoped to this one class rather than to the picker, because the steppers and the hex
     * field are still waiting for their own steps and warn under names of their own. The warning
     * is logged once per class for the whole process, so whichever case runs first is the one that
     * would see it, which is why this runs after every one.
     */
    @AfterEach
    void theLogNamesThePlane() {
        walkLogger.removeHandler(capture);
        limn.i18n.I18n.setLocale(before);
        for (LogRecord record : logged) {
            Object[] parameters = record.getParameters();
            if (parameters == null) {
                continue;
            }
            for (Object parameter : parameters) {
                assertNotEquals(THE_PLANE, parameter,
                        "the plane names itself, so the walk must never say it paints and is "
                                + "deleted nor publish it UNKNOWN: " + record.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One picker at the width the gallery gives it, so every line has room. */
    private void bindPicker() {
        picker = new ColorPicker();
        picker.onChange(changed::add);
        picker.onCommit(committed::add);
        root = new Column();
        root.add(new SizedBox(380, SizedBox.UNSET, picker));
        bind(root);
    }

    /** @return the one canvas in the tree, which is the plane */
    private AccessibleNode plane() {
        return node(Accessible.Role.CANVAS);
    }

    /** @return the plane's first child, the axis saturation runs along */
    private AccessibleNode saturationAxis() {
        return childrenOf(plane()).get(0);
    }

    /** @return the plane's second child, the axis value runs along */
    private AccessibleNode valueAxis() {
        return childrenOf(plane()).get(1);
    }

    /** @return the picker's own node, the chooser everything here sits inside */
    private AccessibleNode chooser() {
        return node(Accessible.Role.COLOR_CHOOSER);
    }

    /**
     * @param letter the channel's letter in the language under test
     * @return the one rail of that letter currently on screen, or {@code null}
     */
    private AccessibleNode showingRail(String letter) {
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.role() == Accessible.Role.SLIDER && node.name().equals(letter)
                    && node.has(Accessible.State.SHOWING)) {
                return node;
            }
        }
        return null;
    }

    /**
     * @param letter the channel's letter
     * @return the rail of that letter whose notation is not showing, or {@code null}
     */
    private AccessibleNode hiddenRail(String letter) {
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.role() == Accessible.Role.SLIDER && node.name().equals(letter)
                    && !node.has(Accessible.State.SHOWING)) {
                return node;
            }
        }
        return null;
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

    /** @return every identifier carrying {@code FOCUSABLE}, in tree order */
    private List<Long> focusableIds() {
        List<Long> found = new ArrayList<>();
        for (AccessibleNode node : nodesWith(Accessible.State.FOCUSABLE)) {
            found.add(node.id());
        }
        return found;
    }

    /**
     * @param percent the whole percent the axis is showing
     * @return the writable facet an axis publishes, on the grid the HSV steppers count in
     */
    private static ValueFacet axis(double percent) {
        return new ValueFacet(percent, 0, 100, 1, null, false);
    }

    // ------------------------------------------------------------------------------- the driving

    /** Presses the left button at a scene point and lets the frame that follows publish. */
    private void pressAt(float x, float y) {
        scene.mouseMoved(x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.inputBatchEnded();
        frame();
    }

    /** Lets go, which is the only thing on this widget's pointer path that commits. */
    private void releaseAt(float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
        frame();
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void onePlaneNamedForBothChannels() {
        bindPicker();

        AccessibleNode node = plane();
        assertEquals("Saturation and value", node.name(),
                "the toolkit's own words: there is no painted text here, no tooltip, no caption "
                        + "pointed at it and no accessor an application could name it through"
                        + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(),
                "the control's own name rather than an application's or a caption's"
                        + describe(tree()));
        assertEquals("", node.description(),
                "the name already says which two channels this is and the two children say it "
                        + "again as nodes; a third copy is one fact published three times, and one "
                        + "carrying the numbers would allocate a string per damaged frame"
                        + describe(tree()));
        assertNull(node.value(),
                "no scalar on the plane itself: its value is two-dimensional, and choosing one "
                        + "axis for this node would be a lie about the other" + describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.selection(), describe(tree()));
        assertNull(node.actions(),
                "and no verb of its own: the axes carry them, because one node carries one pair "
                        + "of steps" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING),
                "the plane is on screen under every notation" + describe(tree()));
        assertFalse(node.has(Accessible.State.HORIZONTAL),
                "a colour space has no one axis" + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
    }

    @Test
    void twoAxesUnderIt() {
        bindPicker();
        picker.setColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();

        List<AccessibleNode> axes = childrenOf(plane());
        List<String> shape = new ArrayList<>();
        for (AccessibleNode child : axes) {
            shape.add(child.role() + " \"" + child.name() + "\"");
        }
        assertEquals(List.of("SLIDER \"Saturation\"", "SLIDER \"Value\""), shape,
                "two nodes and not one, in the order the plane's axes read: saturation across "
                        + "first" + describe(tree()));

        AccessibleNode saturation = axes.get(0);
        assertTrue(saturation.has(Accessible.State.HORIZONTAL),
                "the pair names the axis this node's VALUE runs along" + describe(tree()));
        assertFalse(saturation.has(Accessible.State.VERTICAL), describe(tree()));
        assertEquals(axis(40), saturation.value(), describe(tree()));

        AccessibleNode value = axes.get(1);
        assertTrue(value.has(Accessible.State.VERTICAL),
                "and value runs up the same box" + describe(tree()));
        assertFalse(value.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertEquals(axis(60), value.value(), describe(tree()));

        for (AccessibleNode child : axes) {
            assertEquals(Accessible.NameFrom.CONTENT, child.nameFrom(),
                    "the whole words and not the letters: S and V are the HSV rails' names, and "
                            + "the plane and those rails are on screen together" + describe(tree()));
            assertEquals("", child.description(), describe(tree()));
            assertNull(child.value().text(),
                    "no value text: the number is the whole of it, nothing on screen carries a "
                            + "unit, and a string built in the hook would have no source to "
                            + "compare against" + describe(tree()));
            assertFalse(child.has(Accessible.State.READ_ONLY),
                    "both are settable, and the facet's presence is what says so"
                            + describe(tree()));
            assertEquals(Set.of(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT),
                    child.actions().actions(),
                    "the two steps and nothing else. SET_VALUE is advertised by the writable "
                            + "facet's presence and is never in this list" + describe(tree()));
            assertEquals(List.of(), childrenOf(child), describe(tree()));
            assertTrue(child.has(Accessible.State.ENABLED),
                    "the owner's bits are copied onto every node it drew" + describe(tree()));
            assertTrue(child.has(Accessible.State.SHOWING), describe(tree()));
        }
    }

    @Test
    void itIsTheChoosersFirstChildAndTheRampFollowsIt() {
        bindPicker();

        List<String> shape = new ArrayList<>();
        for (AccessibleNode child : childrenOf(chooser())) {
            shape.add(child.role() + " \"" + child.name() + "\"");
        }
        assertEquals(List.of(
                        "CANVAS \"Saturation and value\"",
                        "SLIDER \"Hue\"",
                        "IMAGE \"Colour #FFFFFF, was #FFFFFF\"",
                        "LABEL \"#\"",
                        "TEXT_FIELD \"Hex\"",
                        "TAB_LIST \"\"",
                        "BUTTON \"Previous tabs\"",
                        "BUTTON \"Next tabs\"",
                        "BUTTON \"All tabs\"",
                        "TAB_PANEL \"RGB\"",
                        "TAB_PANEL \"HSV\"",
                        "TAB_PANEL \"CMYK\"",
                        "LABEL \"A\"",
                        "SLIDER \"A\"",
                        "SPIN_BUTTON \"A\""),
                shape,
                "the plane is the first thing the chooser holds, because the ramps row is the "
                        + "first thing in the column and the plane is the first thing in that row; "
                        + "the expanded share, the token box and the row itself are all deleted"
                        + describe(tree()));

        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            assertFalse(node.role() == Accessible.Role.GROUP && node.name().isEmpty(),
                    "a nameless group is scaffolding that survived the predicate" + describe(tree()));
        }
    }

    // ----------------------------------------------------------------------------- the geometry

    @Test
    void everyBoxIsTheRectangleAPressInverts() {
        bindPicker();

        Widget widget = picker.saturationValueField();
        for (AccessibleNode node : List.of(plane(), saturationAxis(), valueAxis())) {
            assertEquals(widget.localToSceneX(), node.x(), describe(tree()));
            assertEquals(widget.localToSceneY(), node.y(), describe(tree()));
            assertEquals(widget.width(), node.width(),
                    "both axes are operated over the whole plane, so a strip or a band for one of "
                            + "them would be a box no gesture respects" + describe(tree()));
            assertEquals(widget.height(), node.height(), describe(tree()));
        }

        AccessibleNode node = plane();
        float x = node.x() + node.width() / 2;
        float y = node.y() + node.height() / 2;
        pressAt(x, y);
        releaseAt(x, y);

        assertEquals(axis(50), saturationAxis().value(),
                "the published rectangle is the denominator of the hit test: half way across the "
                        + "box is half the saturation" + describe(tree()));
        assertEquals(axis(50), valueAxis().value(),
                "and half way down it is half the value, because the plane's y is inverted"
                        + describe(tree()));
    }

    @Test
    void thePlaneDoesNotTurnRoundWhenTheLayoutDoes() throws Exception {
        bindPicker();
        picker.setColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();
        AccessibleNode leftToRight = plane();

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        Widget widget = picker.saturationValueField();
        AccessibleNode mirrored = plane();
        assertEquals(leftToRight.id(), mirrored.id(), "a direction is not a rebuild");
        for (AccessibleNode node : List.of(mirrored, saturationAxis(), valueAxis())) {
            assertEquals(widget.localToSceneX(), node.x(),
                    "the plane changes sides with the row that places it, and the published x is "
                            + "where that fact lives" + describe(tree()));
            assertEquals(widget.width(), node.width(), describe(tree()));
        }
        assertEquals(axis(40), saturationAxis().value(), "a value has no side" + describe(tree()));
        assertTrue(saturationAxis().has(Accessible.State.HORIZONTAL),
                "the state names the axis of the value rather than a side of the page"
                        + describe(tree()));

        perform(saturationAxis().id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(axis(41), saturationAxis().value(),
                "an increment raises the channel in both directions: nothing in the hook reads the "
                        + "layout direction, because saturation runs left to right in every "
                        + "language" + describe(tree()));

        AccessibleNode node = plane();
        float x = node.x() + node.width() / 4;
        float y = node.y() + node.height() / 2;
        pressAt(x, y);
        releaseAt(x, y);

        assertEquals(axis(25), saturationAxis().value(),
                "and a quarter of the way across its own box is a quarter of the saturation in "
                        + "both, which is the press this node's box has to agree with"
                        + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the units

    @Test
    void theNumbersAreTheOnesTheNotationBesideThemShows() {
        bindPicker();
        picker.setFormat(ColorPicker.Format.HSV);
        picker.setColor(Color.hsv(200, 0.37f, 0.81f, 1f));
        frame();

        assertEquals(showingRail("S").value(), saturationAxis().value(),
                "whole percent, which is what the stepper on the S line is filled with: the two "
                        + "nodes that publish a saturation agree digit for digit by construction, "
                        + "and never on the [0,1] the paint travels on" + describe(tree()));
        assertEquals(showingRail("V").value(), valueAxis().value(), describe(tree()));
        assertEquals(axis(37), saturationAxis().value(), describe(tree()));
        assertEquals(axis(81), valueAxis().value(), describe(tree()));
    }

    @Test
    void theNumbersStandWhileTheNotationShowingHasNoSaturationInIt() {
        bindPicker();
        picker.setFormat(ColorPicker.Format.HSV);
        picker.setColor(Color.hsv(200, 0.37f, 0.81f, 1f));
        frame();
        assertEquals(37.0, showingRail("S").value().value(), describe(tree()));

        picker.setFormat(ColorPicker.Format.RGB);
        picker.setColor(Color.hsv(200, 0.5f, 0.62f, 1f));
        frame();

        assertEquals(axis(50), saturationAxis().value(),
                "the plane is on screen under every notation, so its numbers are the picker's own"
                        + describe(tree()));
        assertEquals(axis(62), valueAxis().value(), describe(tree()));
        assertEquals(37.0, hiddenRail("S").value().value(),
                "while the rail a reader would otherwise have to find still holds the saturation "
                        + "the picker abandoned two moves ago: the picker synchronises only the "
                        + "group whose notation is selected, which is why these two nodes are the "
                        + "whole of what carries these numbers here" + describe(tree()));
        assertEquals(81.0, hiddenRail("V").value().value(), describe(tree()));
    }

    @Test
    void aColourThatMovesLessThanHalfAPercentSaysNothing() {
        bindPicker();
        picker.setColor(Color.hsv(200, 0.37f, 0.81f, 1f));
        frame();
        long saturation = saturationAxis().id();
        long value = valueAxis().id();
        bridge.events.clear();

        picker.setColor(Color.hsv(200, 0.372f, 0.812f, 1f));
        frame();

        assertEquals(axis(37), saturationAxis().value(),
                "the raw float moves on every pixel of a drag; the percent moves a hundred times "
                        + "across the whole plane, which is what stops a drag copying the tree "
                        + "frame after frame" + describe(tree()));
        assertEquals(axis(81), valueAxis().value(), describe(tree()));
        assertEquals(List.of(), valueEventsOn(saturation), bridge.events.toString());
        assertEquals(List.of(), valueEventsOn(value), bridge.events.toString());
    }

    // ------------------------------------------------------------------------------- the verbs

    @Test
    void theTwoStepsMoveOnePercentOfTheirOwnAxisAlone() throws Exception {
        bindPicker();
        picker.setColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();
        long saturation = saturationAxis().id();
        long value = valueAxis().id();

        perform(saturation, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(axis(41), saturationAxis().value(),
                "one percent, which is what the facet's step says and what the stepper on the S "
                        + "line moves by — never a fraction of the plane" + describe(tree()));
        assertEquals(axis(60), valueAxis().value(),
                "and the other axis is passed through untouched" + describe(tree()));

        perform(saturation, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(axis(40), saturationAxis().value(), describe(tree()));

        perform(value, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(axis(61), valueAxis().value(), describe(tree()));
        assertEquals(axis(40), saturationAxis().value(),
                "the two axes do not cross" + describe(tree()));

        perform(value, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(axis(60), valueAxis().value(), describe(tree()));
    }

    @Test
    void aStepAfterADragStartsFromTheNumberTheReaderHeard() throws Exception {
        bindPicker();
        // A gesture leaves a fraction of a percent behind on both axes; this is the deterministic
        // way to put one there without depending on the plane's width.
        picker.setColor(Color.hsv(200, 0.3742f, 0.8123f, 1f));
        frame();
        assertEquals(axis(37), saturationAxis().value(),
                "the node rounds, so it agrees digit for digit with the S stepper, which is filled "
                        + "with the same rounding" + describe(tree()));

        perform(saturationAxis().id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(axis(38), saturationAxis().value(),
                "exactly one more than the number the reader heard" + describe(tree()));
        assertEquals(0.38f, picker.color().saturation(), 0.001f,
                "and the model is on the percent grid now, so the node and the model cannot drift "
                        + "apart");
        assertEquals(axis(81), valueAxis().value(),
                "while the other axis keeps the fraction the drag left on it" + describe(tree()));
    }

    @Test
    void aReadersSetMovesTheColourAndTellsTheApplication() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();
        long saturation = saturationAxis().id();
        changed.clear();
        committed.clear();
        bridge.events.clear();

        assertTrue(perform(saturation, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(37)), "accepted, which is not the same as done");
        frame();

        assertEquals(axis(37), saturationAxis().value(),
                "a set arrives in the domain that was published, zero to a hundred, so it lands "
                        + "where a reader was told it would" + describe(tree()));
        assertEquals(0.37f, picker.color().saturation(), 0.01f,
                "the hook reaches the same private mutator a drag reaches");
        assertEquals(1, changed.size(),
                "which calls the picker's own changed(), the one path that tells the application; "
                        + "setColor is silent and would have said nothing: " + changed);
        assertEquals(0.37f, changed.get(0).saturation(), 0.01f);
        assertEquals(1, committed.size(),
                "then the commit, as a whole gesture: a reader's request has no release to follow");
        List<AccessibleEvent> raised = valueEventsOn(saturation);
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(40.0, raised.get(0).oldValue());
        assertEquals(37.0, raised.get(0).newValue());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press and nothing else: " + bridge.events);
    }

    @Test
    void aStepAtTheEndOfTheRangeIsACommitOfTheValueHeld() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.hsv(0, 1f, 1f, 1f));
        frame();
        assertEquals(axis(100), saturationAxis().value(), describe(tree()));
        assertEquals(axis(100), valueAxis().value(), describe(tree()));
        changed.clear();
        committed.clear();

        assertTrue(perform(saturationAxis().id(), Accessible.Action.INCREMENT,
                Accessible.Argument.NONE), "accepted: the verb ran, and what it ran was a commit");
        frame();

        assertEquals(axis(100), saturationAxis().value(), "the clamp holds" + describe(tree()));
        assertEquals(1, committed.size(),
                "and the user chose the end, exactly as a drag against the edge does");
        assertEquals(1, changed.size(),
                "this widget's mutator has always told the application on every event of a drag, "
                        + "moved or not, and the verbs reach that mutator rather than a guarded "
                        + "copy of it: " + changed);
    }

    @Test
    void aNonFiniteSetIsRefused() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();
        long saturation = saturationAxis().id();
        long value = valueAxis().id();
        changed.clear();
        committed.clear();
        bridge.events.clear();

        // The boolean means accepted and not done, so what a refusal looks like from here is the
        // colour, the handlers and the event list all standing still.
        perform(saturation, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.NaN));
        perform(value, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.POSITIVE_INFINITY));
        perform(saturation, Accessible.Action.SET_VALUE, Accessible.Argument.NONE);
        perform(value, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(axis(40), saturationAxis().value(),
                "clamp01 passes NaN straight through and Math.round(Float.NaN) is zero, so an "
                        + "unguarded one would snap the plane into a corner and report it as a "
                        + "change the user made" + describe(tree()));
        assertEquals(axis(60), valueAxis().value(), describe(tree()));
        assertEquals(List.of(), changed, "refused before any handler");
        assertEquals(List.of(), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // ------------------------------------------------------------------------------- the guards

    @Test
    void aDisabledPickerRefusesEveryVerbThoughThePlanesOwnFlagStandsTrue() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();
        long saturation = saturationAxis().id();
        long value = valueAxis().id();
        changed.clear();
        committed.clear();

        picker.setEnabled(false);
        frame();

        assertTrue(picker.saturationValueField().isEnabled(),
                "the flag a guard written on this inner class would read is still true: "
                        + "Widget#isEnabled answers a widget's own flag and setEnabled propagates "
                        + "to nothing, which is why the hook asks the picker");
        assertEquals(saturation, saturationAxis().id(),
                "a disabled picker is not a rebuild" + describe(tree()));
        assertFalse(saturationAxis().has(Accessible.State.ENABLED),
                "the bit is inherited down the walk from the picker and copied onto both axes"
                        + describe(tree()));
        assertEquals(axis(40), saturationAxis().value(),
                "a disabled plane is heard as disabled rather than vanishing" + describe(tree()));
        bridge.events.clear();

        perform(saturation, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(saturation, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(90));
        perform(value, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        perform(value, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(10));
        frame();

        assertEquals(0.4f, picker.color().saturation(), 0.01f, "a disabled control was operated");
        assertEquals(0.6f, picker.color().value(), 0.01f);
        assertEquals(List.of(), changed);
        assertEquals(List.of(), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void aPickerThatIsNotOnScreenRefusesEveryVerb() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();
        long saturation = saturationAxis().id();
        changed.clear();
        committed.clear();

        picker.setVisible(false);
        frame();

        assertFalse(saturationAxis().has(Accessible.State.SHOWING), describe(tree()));
        assertTrue(saturationAxis().actions().has(Accessible.Action.INCREMENT),
                "the verb list says what the control offers, and the missing SHOWING bit is what "
                        + "says it is not on screen" + describe(tree()));

        perform(saturation, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(saturation, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(90));
        frame();

        assertEquals(0.4f, picker.color().saturation(), 0.01f,
                "the scene's own gate re-checks isShowing() on arrival, which is where an "
                        + "unreachable control is refused rather than in the hook");
        assertEquals(List.of(), changed);
        assertEquals(List.of(), committed);
    }

    // -------------------------------------------------------------------------------- the order

    @Test
    void itIsNotATabStopAndDoesNotBecomeOne() {
        bindPicker();

        for (AccessibleNode node : List.of(plane(), saturationAxis(), valueAxis())) {
            assertFalse(node.has(Accessible.State.FOCUSABLE),
                    "the widget is not focusable and this step may not make it one: reading order "
                            + "is defined to equal Tab order, and the arrows that walk this plane "
                            + "belong to the picker's own node" + describe(tree()));
            assertFalse(node.actions() != null
                            && node.actions().has(Accessible.Action.FOCUS),
                    "so the walk offers it neither of the two verbs it grants a tab stop"
                            + describe(tree()));
            assertFalse(node.actions() != null
                            && node.actions().has(Accessible.Action.SCROLL_INTO_VIEW),
                    describe(tree()));
        }

        List<Long> focusable = focusableIds();
        assertEquals(11, focusable.size(),
                "the chooser, the hex field, the one selected tab, three rails and three steppers, "
                        + "and the alpha pair — the same eleven as before these nodes existed"
                        + describe(tree()));
        assertFalse(focusable.contains(plane().id()), describe(tree()));
        assertFalse(focusable.contains(saturationAxis().id()), describe(tree()));
        assertFalse(focusable.contains(valueAxis().id()), describe(tree()));
    }

    // ----------------------------------------------------------------------------- the identity

    @Test
    void theAxesSurviveANotationSwitchAModeFlipAndAColourChange() {
        bindPicker();
        long canvas = plane().id();
        long saturation = saturationAxis().id();
        long value = valueAxis().id();
        bridge.events.clear();

        picker.setFormat(ColorPicker.Format.CMYK);
        frame();
        picker.setAlphaEnabled(false);
        frame();
        picker.setColor(Color.hsv(90, 0.5f, 0.5f, 1f));
        frame();

        assertEquals(canvas, plane().id(), describe(tree()));
        assertEquals(saturation, saturationAxis().id(),
                "the key is the axis and not the colour, so nothing here destroys and rebuilds two "
                        + "nodes under a reader mid-sentence" + describe(tree()));
        assertEquals(value, valueAxis().id(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- what it costs

    @Test
    void aFrameThatChangesNothingSaysNothing() {
        bindPicker();
        picker.setColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            picker.invalidate();
            frame();
        }
        // The same colour the picker is already holding, which is damage without a move.
        picker.setColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot: the hook publishes a role, "
                        + "three held strings, two bits and eight primitives, and a derived string "
                        + "or a per-frame flag among them — the drag flag, the layout direction — "
                        + "would republish the whole tree on every damaged frame");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);
    }

    // ---------------------------------------------------------------------------- the language

    @Test
    void theNamesFollowTheSubtreesLanguage() {
        bindPicker();
        picker.setFormat(ColorPicker.Format.HSV);
        picker.setColor(Color.hsv(200, 0.4f, 0.6f, 1f));
        frame();
        long canvas = plane().id();

        picker.setLocale(Locale.FRENCH);
        frame();

        assertEquals("Saturation et valeur", plane().name(),
                "handed over as the strings this widget holds rather than as anything resolved "
                        + "inside the hook" + describe(tree()));
        assertEquals(Locale.FRENCH, plane().locale(), describe(tree()));
        assertEquals(canvas, plane().id(), "a language is not a rebuild" + describe(tree()));
        assertEquals("Saturation", saturationAxis().name(),
                "French says the same word here and falls back to the English the component "
                        + "declares, as Hex does" + describe(tree()));
        assertEquals("Valeur", valueAxis().name(), describe(tree()));
        assertEquals(Locale.FRENCH, valueAxis().locale(),
                "and the axes record the language they were resolved under" + describe(tree()));
        assertEquals(axis(40), saturationAxis().value(),
                "a language moves no number" + describe(tree()));
        assertEquals("V", showingRail("V").name(),
                "while the rail on the V line keeps its letter, which is why these two may not be "
                        + "named after the letters: they are on screen together" + describe(tree()));
    }
}

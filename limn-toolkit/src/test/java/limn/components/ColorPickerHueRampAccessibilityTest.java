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
import static limn.testing.SceneDriver.drive;

/**
 * What a {@link ColorPicker}'s hue ramp becomes in the accessible tree: one <b>vertical</b>
 * {@code SLIDER} named "Hue", carrying a writable value facet in whole degrees and offering the
 * two steps, operated from a screen reader through the same private mutator a drag reaches.
 *
 * <p>The case this file exists to fail on first is that the ramp was <b>absent</b>. It is never
 * focusable and declared nothing, so the transparency predicate dropped it, and because it paints
 * its own content the walk named a toolkit class in an application's log for a picture that is
 * the picker's whole hue axis. All three one-line fixes that warning recommends are out of reach
 * here — the class is private and nothing the picker exposes hands an application the instance —
 * so the widget carries its own name or nobody can. That is also why the name is the toolkit's
 * own word rather than a letter: the letter belongs to the HSV notation's H rail, and the two
 * controls are on screen together.
 *
 * <p>Where ADR 039 §7's row is wrong, and it is the already-corrected row. It says the class
 * "has none of the rail machinery a {@code SLIDER} row assumes", which reads as a reason the role
 * is something else; the role is exactly right, and what is missing is an implementation rather
 * than a fact — one scalar, one range, one axis, one drag. One of those absences makes this node
 * easier to publish than a rail's: with no thumb held off the ends, the published rectangle is
 * precisely the span the press divides by.
 *
 * <p>Two more the row is silent on, each with a case below. <b>The numbers are the picker's own
 * hue and not the stepper beside the notation</b>, which is the opposite of what a channel rail
 * does and is the trap here: the picker synchronises only the group whose notation is selected, so
 * the HSV H stepper holds a hue the picker has abandoned whenever RGB or CMYK is showing, while
 * this ramp is on screen under all three. And <b>the ramp does not turn round</b>, in the one
 * widget whose documentation is about mirroring: it is vertical, its hook reads no layout
 * direction, and the side it sits on is the row's doing and is already said by the published x.
 *
 * <p>Every case drives the picker's public API, and {@code Widget}'s, on a bound scene, or calls
 * the scene from where a bridge stands. Nothing constructs a node.
 */
class ColorPickerHueRampAccessibilityTest extends AccessibleComponentTestBase {

    /** The exact class an application must never find in its log because of this step. */
    private static final String THE_RAMP = "limn.components.ColorPicker$HueRamp";

    private ColorPicker picker;

    /** The column the picker sits in, the root of every fixture. */
    private Column root;

    /** Every colour the application's change handler was given, in order. */
    private final List<Color> changed = new ArrayList<>();

    /** Every colour the application's commit handler was given, in order. */
    private final List<Color> committed = new ArrayList<>();

    /** Every record the walk logged while a test was running; see {@link #theLogNamesTheRamp}. */
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
        // French is the one shipped language that translates this name, and one case below asks
        // for it on the picker's own subtree, so the rest are pinned to the language they assert
        // in.
        limn.i18n.I18n.setLocale(Locale.ENGLISH);
    }

    /**
     * The ramp names itself, so the walk has nothing to say about it: it must never be dropped as
     * a thing that paints and says nothing, and never published {@code UNKNOWN}.
     *
     * <p>Scoped to this one class rather than to the picker: the walk logs a warning once per
     * class for the whole process, so whichever case runs first is the one that would see it,
     * which is why this runs after every one.
     */
    @AfterEach
    void theLogNamesTheRamp() {
        walkLogger.removeHandler(capture);
        limn.i18n.I18n.setLocale(before);
        for (LogRecord record : logged) {
            Object[] parameters = record.getParameters();
            if (parameters == null) {
                continue;
            }
            for (Object parameter : parameters) {
                assertNotEquals(THE_RAMP, parameter,
                        "the ramp names itself, so the walk must never say it paints and is "
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

    /**
     * The ramp's node.
     *
     * <p>Found by role <em>and provenance</em> rather than by name: every rail in this picker is
     * named by the caption on its line, and the ramp is the one slider the chooser holds that
     * carries a name of its own, which is what makes the scoping survive the language case below.
     * Scoped to the chooser's own children for the one other slider that names itself: the
     * saturation/value plane's two axes, which are a level further down, under the canvas.
     *
     * @return the one self-named slider the chooser holds directly
     */
    private AccessibleNode ramp() {
        AccessibleNode found = null;
        for (AccessibleNode node : childrenOf(chooser())) {
            if (node.role() == Accessible.Role.SLIDER
                    && node.nameFrom() == Accessible.NameFrom.CONTENT) {
                if (found != null) {
                    throw new AssertionError("two self-named sliders" + describe(tree()));
                }
                found = node;
            }
        }
        if (found == null) {
            throw new AssertionError("the hue ramp is not in the tree" + describe(tree()));
        }
        return found;
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

    /** @return every identifier carrying {@code FOCUSABLE}, in tree order */
    private List<Long> focusableIds() {
        List<Long> found = new ArrayList<>();
        for (AccessibleNode node : nodesWith(Accessible.State.FOCUSABLE)) {
            found.add(node.id());
        }
        return found;
    }

    /** @return the identifier of the node holding the keyboard, or {@code 0} */
    private long focusedId() {
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).has(Accessible.State.FOCUSED)) {
                return tree().node(i).id();
            }
        }
        return 0;
    }

    /**
     * @param degrees the hue the node is showing
     * @return the writable facet the ramp publishes, on the whole degrees the H stepper counts in
     */
    private static ValueFacet hue(double degrees) {
        return new ValueFacet(degrees, 0, 360, 1, null, false);
    }

    // ------------------------------------------------------------------------------- the driving

    /** Presses the left button at a scene point and lets the frame that follows publish. */
    private void pressAt(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        drive(scene).inputBatchEnded();
        frame();
    }

    /** Moves the pointer while a button is held, which the scene routes to the pressed widget. */
    private void dragTo(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
        frame();
    }

    /** Lets go, which is the only thing on this widget's pointer path that commits. */
    private void releaseAt(float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        drive(scene).inputBatchEnded();
        frame();
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void oneVerticalSliderNamedHue() {
        bindPicker();

        AccessibleNode node = ramp();
        assertEquals("Hue", node.name(),
                "the whole word and not the letter: H is the HSV notation's rail, and both "
                        + "controls are on screen at once" + describe(tree()));
        assertEquals(1, named("Hue").size(), "and one node says it" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(),
                "the name is the control's own rather than an application's or a caption's; there "
                        + "is no caption beside this ramp and no accessor to reach it with"
                        + describe(tree()));
        assertEquals("", node.description(),
                "a name and no second sentence: the number is the rest of it" + describe(tree()));
        assertTrue(node.has(Accessible.State.VERTICAL),
                "the pair names the axis the VALUE runs along, and hue runs top to bottom here"
                        + describe(tree()));
        assertFalse(node.has(Accessible.State.HORIZONTAL),
                "which is the opposite of the rails below it" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING),
                "the ramp is beside the plane under every notation" + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY),
                "the hue is settable, and the facet's presence is what says so" + describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.value().text(),
                "no value text: the number is the whole of it, nothing on screen carries a unit, "
                        + "and a string built in the hook would have no source to compare against"
                        + describe(tree()));
        assertEquals(Set.of(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT),
                node.actions().actions(),
                "the two steps and nothing else. SET_VALUE is advertised by the writable facet's "
                        + "presence and is never in this list, and the walk's focus and "
                        + "scroll-into-view are withheld from a node that is not a tab stop"
                        + describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "the marker is a mark on the value and not a control: it is clamped inside the "
                        + "box and has no geometry of its own to publish" + describe(tree()));
    }

    @Test
    void itFollowsThePlaneAndTheStripHasNotMoved() {
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
                "the ramp is the second thing the chooser holds, behind the plane it sits beside: "
                        + "the ramps row is the first thing in the column and the plane is the "
                        + "first thing in that row; the token box, the row and the share around "
                        + "them are all gone" + describe(tree()));

        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            assertFalse(node.role() == Accessible.Role.GROUP && node.name().isEmpty(),
                    "a nameless group is scaffolding that survived the predicate" + describe(tree()));
        }
    }

    // ----------------------------------------------------------------------------- the geometry

    @Test
    void itsBoxIsTheRectangleAPressInverts() {
        bindPicker();
        picker.setInitialColor(Color.hsv(0, 1, 1, 1));
        frame();

        AccessibleNode node = ramp();
        Widget widget = picker.hueRamp();
        assertEquals(widget.localToSceneX(), node.x(), describe(tree()));
        assertEquals(widget.localToSceneY(), node.y(), describe(tree()));
        assertEquals(widget.width(), node.width(),
                "the token box hands the ramp its whole rectangle at the origin" + describe(tree()));
        assertEquals(widget.height(), node.height(), describe(tree()));

        float x = node.x() + node.width() / 2;
        float middle = node.y() + node.height() / 2;
        pressAt(x, middle);
        releaseAt(x, middle);

        assertEquals(180f, picker.hue(), 0.01f,
                "the published rectangle is the denominator of the hit test, with no travel inset "
                        + "of the kind that holds a rail's thumb off its ends: half way down the "
                        + "box is half way round the circle");
        assertEquals(180f, picker.color().hue(), 0.5f);
        assertEquals(hue(180), ramp().value(), describe(tree()));
    }

    @Test
    void theTopOfTheRampIsZeroAndTheBottomIsThreeSixty() {
        bindPicker();
        picker.setInitialColor(Color.hsv(120, 1, 1, 1));
        frame();
        AccessibleNode node = ramp();
        float x = node.x() + node.width() / 2;

        pressAt(x, node.y());
        releaseAt(x, node.y());
        assertEquals(0f, picker.hue(), 0.01f, "the top of the box is the start of the circle");
        assertEquals(hue(0), ramp().value(), describe(tree()));

        float below = node.y() + node.height() + 40;
        pressAt(x, node.y() + node.height() / 2);
        // The scene routes a drag to the widget that was pressed, so the pointer can leave the box
        // and the position is clamped: this is the gesture that reaches the far end exactly.
        dragTo(x, below);
        releaseAt(x, below);

        assertEquals(360f, picker.hue(), 0.01f, "and the bottom is all the way round");
        assertEquals(hue(360), ramp().value(),
                "which is why the maximum is 360 and not 359: a drag genuinely produces it, and it "
                        + "is the same maximum the H stepper is constructed with" + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the units

    @Test
    void theValueIsDegreesAndNotAPaintFraction() {
        bindPicker();
        picker.setColor(Color.hsv(210, 1, 1, 1));
        frame();

        assertEquals(hue(210), ramp().value(),
                "degrees on the whole-number grid the H stepper counts in, and never the [0,1] "
                        + "the paint travels on, which would report a range of one to a reader "
                        + "while the notation beside it counts to 360" + describe(tree()));
    }

    @Test
    void theValueStandsWhileANotationThatHidesTheHueNumberIsShowing() {
        bindPicker();
        picker.setFormat(ColorPicker.Format.HSV);
        picker.setColor(Color.hsv(10, 1, 1, 1));
        frame();
        assertEquals(hue(10), ramp().value(), describe(tree()));
        assertEquals(10.0, showingRail("H").value().value(),
                "the two agree while the notation showing is the one that has a hue in it"
                        + describe(tree()));

        picker.setFormat(ColorPicker.Format.RGB);
        picker.setColor(Color.hsv(300, 1, 1, 1));
        frame();

        assertEquals(hue(300), ramp().value(),
                "the ramp is on screen under every notation, so its number is the picker's own "
                        + "hue" + describe(tree()));
        assertEquals(10.0, hiddenRail("H").value().value(),
                "and this is the trap: the picker synchronises only the group whose notation is "
                        + "selected, so the H stepper a channel rail would read still holds the "
                        + "hue the picker abandoned two moves ago" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the action

    @Test
    void aReadersSetMovesTheColourAndTellsTheApplication() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.hsv(10, 1, 1, 1));
        frame();
        long id = ramp().id();
        changed.clear();
        committed.clear();
        bridge.events.clear();

        assertTrue(perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(300)),
                "accepted, which is not the same as done");
        frame();

        assertEquals(hue(300), ramp().value(), describe(tree()));
        assertEquals(300f, picker.hue(), 0.01f,
                "the hook reaches the same private mutator a drag reaches");
        assertEquals(1, changed.size(),
                "which calls the picker's own changed(), the one path that tells the application; "
                        + "setColor is silent and would have said nothing: " + changed);
        assertEquals(300f, changed.get(0).hue(), 0.5f);
        assertEquals(1, committed.size(),
                "then the commit, as a whole gesture: a reader's request has no release to follow");
        List<AccessibleEvent> raised = valueEventsOn(id);
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(10.0, raised.get(0).oldValue());
        assertEquals(300.0, raised.get(0).newValue());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press and nothing else: " + bridge.events);
    }

    @Test
    void theTwoStepsMoveOneDegreeAndCommitAtTheEnds() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.hsv(210, 1, 1, 1));
        frame();
        long id = ramp().id();
        changed.clear();
        committed.clear();

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(hue(211), ramp().value(),
                "one degree, which is what the facet's step says and what the H stepper beside "
                        + "the notation moves by — never a fraction of the rail" + describe(tree()));

        perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(hue(210), ramp().value(), describe(tree()));
        assertEquals(2, committed.size());

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(360));
        frame();
        int commits = committed.size();
        int changes = changed.size();
        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE),
                "accepted: the verb ran, and what it ran was a commit");
        frame();

        assertEquals(hue(360), ramp().value(), "the clamp holds" + describe(tree()));
        assertEquals(commits + 1, committed.size(),
                "and the user chose the end, exactly as End at the maximum commits");
        assertEquals(changes + 1, changed.size(),
                "this widget's mutator has always told the application on every step of a gesture, "
                        + "moved or not — its drag loop does the same at the bottom edge — and the "
                        + "verbs deliberately reach that mutator rather than a guarded copy of it: "
                        + changed);

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(0));
        frame();
        commits = committed.size();
        perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(hue(0), ramp().value(), describe(tree()));
        assertEquals(commits + 1, committed.size());
    }

    @Test
    void aStepAfterADragStartsFromTheNumberTheReaderHeard() throws Exception {
        bindPicker();
        // The hue a gesture leaves behind carries a fraction the marker cannot draw; this is the
        // one deterministic way to put one there without depending on the ramp's height.
        picker.setInitialColor(Color.hsv(173.42f, 1, 1, 1));
        frame();
        assertTrue(picker.hue() != Math.round(picker.hue()),
                "the model holds a fraction, which is the whole premise: " + picker.hue());

        long id = ramp().id();
        assertEquals(hue(173), ramp().value(),
                "the node rounds, so it agrees digit for digit with the H stepper, which is filled "
                        + "with the same rounding" + describe(tree()));

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(hue(174), ramp().value(),
                "exactly one more than the number the reader heard, and not one more than the "
                        + "unrounded model, which would leave the two a fraction apart for good"
                        + describe(tree()));
        assertEquals(174f, picker.hue(), 0.0001f, "and the model is on the degree grid now");
    }

    @Test
    void aNonFiniteSetIsRefused() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.hsv(210, 1, 1, 1));
        frame();
        long id = ramp().id();
        changed.clear();
        committed.clear();
        bridge.events.clear();

        // The boolean means accepted and not done, so what a refusal looks like from here is the
        // colour, the handlers and the event list all standing still.
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(Double.NaN));
        perform(id, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.POSITIVE_INFINITY));
        perform(id, Accessible.Action.SET_VALUE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(hue(210), ramp().value(),
                "the clamp is a Math.max/Math.min pair and both propagate NaN, so an unguarded one "
                        + "would write it straight into the model and report it as a user change"
                        + describe(tree()));
        assertEquals(List.of(), changed, "refused before any handler");
        assertEquals(List.of(), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // -------------------------------------------------------------------------------- the order

    @Test
    void itIsNotATabStopAndDoesNotBecomeOne() {
        bindPicker();

        AccessibleNode node = ramp();
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the widget is not focusable and this step may not make it one: reading order is "
                        + "defined to equal Tab order" + describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.FOCUS),
                "so the walk offers it neither of the two verbs it grants a tab stop"
                        + describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));

        List<Long> focusable = focusableIds();
        assertEquals(11, focusable.size(),
                "the chooser, the hex field, the one selected tab, three rails and three steppers, "
                        + "and the alpha pair — the same eleven as before this node existed"
                        + describe(tree()));
        assertFalse(focusable.contains(node.id()), describe(tree()));

        List<Long> tabbed = new ArrayList<>();
        scene.requestFocus(null);
        for (int i = 0; i < focusable.size(); i++) {
            scene.focusTraverse(false);
            frame();
            tabbed.add(focusedId());
        }
        assertEquals(focusable, tabbed,
                "and Tab reaches them in the order the tree publishes them, with the ramp read on "
                        + "the way past and never landed on" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the guards

    @Test
    void aDisabledPickerRefusesEveryVerb() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.hsv(210, 1, 1, 1));
        frame();
        long id = ramp().id();
        changed.clear();
        committed.clear();

        picker.setEnabled(false);
        frame();

        AccessibleNode node = ramp();
        assertEquals(id, node.id(), "a disabled picker is not a rebuild" + describe(tree()));
        assertFalse(node.has(Accessible.State.ENABLED),
                "the bit is inherited down the walk from the picker" + describe(tree()));
        assertEquals(hue(210), node.value(),
                "a disabled ramp is heard as disabled rather than vanishing, its value as "
                        + "writable as it is (fix round 2e)" + describe(tree()));
        assertFalse(node.accepts(Accessible.Action.SET_VALUE),
                "and it accepts no SET_VALUE, because it is not ENABLED" + describe(tree()));
        assertNull(node.actions(), "no verb the scene refuses is published" + describe(tree()));
        bridge.events.clear();

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(90));
        frame();

        assertEquals(210f, picker.hue(), 0.01f, "a disabled control was operated");
        assertEquals(List.of(), changed);
        assertEquals(List.of(), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- the direction

    @Test
    void theRampDoesNotTurnRoundWhenTheLayoutDoes() {
        bindPicker();
        picker.setInitialColor(Color.hsv(300, 1, 1, 1));
        frame();
        AccessibleNode leftToRight = ramp();
        float quarter = leftToRight.height() / 4;

        pressAt(leftToRight.x() + leftToRight.width() / 2, leftToRight.y() + quarter);
        releaseAt(leftToRight.x() + leftToRight.width() / 2, leftToRight.y() + quarter);
        assertEquals(90f, picker.hue(), 0.01f, "a quarter of the way down is a quarter round");

        picker.setColor(Color.hsv(300, 1, 1, 1));
        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode mirrored = ramp();
        assertEquals(leftToRight.id(), mirrored.id(), "a direction is not a rebuild");
        assertNotEquals(leftToRight.x(), mirrored.x(),
                "the ramp changes sides, and that is the row placing it: the published x is where "
                        + "that fact lives" + describe(tree()));
        assertEquals(leftToRight.width(), mirrored.width(), describe(tree()));
        assertEquals(hue(300), mirrored.value(),
                "a value has no side" + describe(tree()));
        assertTrue(mirrored.has(Accessible.State.VERTICAL), describe(tree()));
        assertFalse(mirrored.has(Accessible.State.HORIZONTAL), describe(tree()));

        pressAt(mirrored.x() + mirrored.width() / 2, mirrored.y() + quarter);
        releaseAt(mirrored.x() + mirrored.width() / 2, mirrored.y() + quarter);

        assertEquals(90f, picker.hue(), 0.01f,
                "the same fraction of its own box is the same hue in both directions: nothing in "
                        + "the hook reads the layout direction, because the one part of this "
                        + "widget that must not turn round is this one");
    }

    // ---------------------------------------------------------------------------- what it costs

    @Test
    void aFrameThatChangesNothingSaysNothing() {
        bindPicker();
        picker.setColor(Color.hsv(210, 1, 1, 1));
        frame();
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            picker.invalidate();
            frame();
        }
        // The same colour the picker is already holding, which is damage without a move.
        picker.setColor(Color.hsv(210, 1, 1, 1));
        frame();

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot: the hook publishes a role, "
                        + "a held string, one bit, four primitives and two verbs, and a derived "
                        + "string or a per-frame flag among them would republish the whole tree on "
                        + "every damaged frame");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);
    }

    // --------------------------------------------------------------------------- the language

    @Test
    void theNameFollowsTheSubtreesLanguage() {
        bindPicker();
        picker.setFormat(ColorPicker.Format.HSV);
        picker.setColor(Color.hsv(210, 1, 1, 1));
        frame();
        long id = ramp().id();

        picker.setLocale(Locale.FRENCH);
        frame();

        assertEquals("Teinte", ramp().name(),
                "the ramp carries the channel's whole word, handed over as the string this widget "
                        + "holds rather than as anything resolved inside the hook" + describe(tree()));
        assertEquals(Locale.FRENCH, ramp().locale(), describe(tree()));
        assertEquals(id, ramp().id(), "a language is not a rebuild" + describe(tree()));
        assertEquals(hue(210), ramp().value(), "and it moves no number" + describe(tree()));
        assertEquals("T", showingRail("T").name(),
                "while the rail on the H line keeps the letter, which is why the ramp may not be "
                        + "named after it: the two are on screen together" + describe(tree()));
    }
}

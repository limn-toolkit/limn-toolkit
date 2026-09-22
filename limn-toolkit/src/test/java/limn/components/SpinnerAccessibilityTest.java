package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ValueFacet;
import limn.i18n.I18n;
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
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * What a {@link Spinner} becomes in the accessible tree: one {@code SPIN_BUTTON}, a tab stop with
 * no name of its own, carrying the value, its bounds, what one increment moves it by and the
 * display form the widget already painted, over the two halves of its stepper column as nameless
 * buttons a reader can press.
 *
 * <p>The widget is focusable and paints, so a hook that stopped declaring the role would publish it
 * {@code UNKNOWN} under the walk's once-per-class warning, and one that stopped declaring anything
 * would be deleted under the other; both name a toolkit class in an application's log, so the log
 * is captured around every case and read after each, because whichever case runs first is the one
 * that would see it.
 *
 * <p>Where ADR 039 §7's row was wrong, and each has a case below. Its reason for having no hour and
 * minute children — "{@code regionAt} answers exactly three things" — is false of the widget's
 * pointer handling, which classifies a click inside the value area into hours or minutes; the
 * answer survives for reasons the row never gives, chief among them that the classification exists
 * only while typing is off. Its {@code ValueFacet} does not say which of the widget's three
 * increments it means, and the published one is what an increment moves by, which is 60 while the
 * arrows are on the hours — a stated divergence from {@code Slider}'s snap-grid step rather than a
 * copy of it. And its two verbs are silent on which private path they reach, of which there are
 * two: the keyboard's step adopts a half-typed number and leaves the edit open, the arrow button's
 * press commits it first, and each of them is reached by the node whose box the gesture lands in.
 *
 * <p>Every case drives the spinner's public API, and {@code Widget}'s, on a bound scene, or calls
 * the scene from where a bridge stands. Nothing constructs a node.
 */
class SpinnerAccessibilityTest extends AccessibleComponentTestBase {

    /** The exact class an application must never find in its log because of this step. */
    private static final String THE_SPINNER = "limn.components.Spinner";

    /** Arabic-Indic digits, which is what a spinner under this tag paints and publishes. */
    // Egypt, and not a bare "ar": CLDR makes Latin the Arabic default and names the twenty-three
    // regions that write Arabic-Indic digits, so a region is what makes this an Arabic-digit
    // locale at all (the table was inverted until 2026-09-21).
    private static final Locale ARABIC = Locale.forLanguageTag("ar-EG");

    private Spinner spinner;

    /** The column the spinner sits in, the root of every fixture. */
    private Column root;

    /** Every value the application's change handler was given, in order. */
    private final List<Double> changed = new ArrayList<>();

    /** Every record the walk logged while a case was running; see the class comment. */
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
        before = I18n.locale();
        // One case asks for Arabic by name; the rest assert Latin digits, so the language they
        // assert in is pinned rather than inherited from whatever ran before.
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void theSpinnerIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        I18n.setLocale(before);
        for (LogRecord record : logged) {
            // The PARAMETER and not the message: the walk logs a parameterised record, so
            // getMessage() answers the unformatted pattern and the class name is in the
            // parameters. Reading the message instead makes the assertion pass whatever the walk
            // does, which is what it did until somebody read both sides.
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.contains(THE_SPINNER),
                    "a spinner is focusable and paints, and it declares a role, so the walk must "
                            + "never publish it UNKNOWN nor say it paints and is deleted; a "
                            + "warning here names a toolkit class an application cannot correct: "
                            + subject + " " + record.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One spinner as the only control in a column, so it keeps its own measured box. */
    private void bindSpinner(Spinner under) {
        spinner = under;
        spinner.onChange(changed::add);
        root = new Column();
        root.add(spinner);
        bind(root);
    }

    /** @return the one spinner node in the current tree */
    private AccessibleNode spinnerNode() {
        return node(Accessible.Role.SPIN_BUTTON);
    }

    /** @return the two halves of the stepper column, up first */
    private List<AccessibleNode> arrows() {
        List<AccessibleNode> found = childrenOf(spinnerNode());
        assertEquals(2, found.size(),
                "the stepper column is two separately hit-testable halves" + describe(tree()));
        return found;
    }

    /** @return every value change raised so far, in order */
    private List<AccessibleEvent> valueEvents() {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.VALUE_CHANGED) {
                found.add(event);
            }
        }
        return found;
    }

    /** Presses and releases {@code key} on the focused spinner, as a user does. */
    private void press(int key) {
        scene.requestFocus(spinner);
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).keyEvent(key, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
    }

    /** Types one character into the focused spinner, which is what opens an edit. */
    private void type(int codepoint) {
        scene.requestFocus(spinner);
        drive(scene).charTyped(codepoint);
        drive(scene).inputBatchEnded();
        frame();
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void aFreshSpinnerIsOneSpinButtonOverTwoNamedArrowButtons() {
        bindSpinner(new Spinner(0, 99, 1));

        assertEquals(4, tree().nodeCount(),
                "the window, the spinner and its two arrows; the column is scaffolding"
                        + describe(tree()));
        AccessibleNode node = spinnerNode();
        assertEquals(0, node.parent(), describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "a spinner is focusable from its constructor, which is why the role is not "
                        + "optional" + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY),
                "the value is settable, and the writable facet is what says so" + describe(tree()));
        assertFalse(node.has(Accessible.State.EDITABLE),
                "the editable flag gates the inline text editor alone, and the value stays "
                        + "settable and steppable when it is off, so the bit would contradict the "
                        + "facet on the same node" + describe(tree()));
        assertFalse(node.has(Accessible.State.HAS_POPUP),
                "unlike a text field, a spinner has no context menu at all" + describe(tree()));
        assertFalse(node.has(Accessible.State.HORIZONTAL),
                "the class fixes neither axis, and the value is not laid along a rail"
                        + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));

        assertTrue(node.actions().has(Accessible.Action.INCREMENT), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.DECREMENT), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS),
                "the two the walk adds for every focusable widget" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.PRESS),
                "the arrows are pressed; the spinner itself is stepped" + describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.SHOW_MENU),
                "the clipboard is reached from Ctrl/Cmd+C, X and V and from no menu, so a reader "
                        + "has no route to copy the value: a stated cost, not an oversight"
                        + describe(tree()));
        assertNull(node.text(), "no text facet in the first cut" + describe(tree()));
        assertNull(node.toggle(), describe(tree()));

        List<String> arrowNames = new ArrayList<>();
        for (AccessibleNode arrow : arrows()) {
            assertEquals(Accessible.Role.BUTTON, arrow.role(), describe(tree()));
            arrowNames.add(arrow.name());
            assertFalse(arrow.has(Accessible.State.FOCUSABLE),
                    "a thing a widget paints is not a tab stop; Tab reaches the spinner and the "
                            + "arrows belong to it" + describe(tree()));
            assertTrue(arrow.has(Accessible.State.SHOWING), describe(tree()));
            assertNull(arrow.value(), "the number is on the spinner, once" + describe(tree()));
        }
        // A fresh spinner sits at its minimum -- the constructor's `value = min` -- so this
        // fixture is a bounded one and the halves differ (decision 69): the upper one is the
        // owner's enabled bit written onto a node the owner drew, the lower one is narrowed
        // because pressing it would step below zero and move nothing. The both-alive shape is
        // asserted by bothHalvesAreOperableInsideTheRange, on a spinner set off its bound.
        assertTrue(arrows().get(0).has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(arrows().get(0).actions().has(Accessible.Action.PRESS), describe(tree()));
        assertFalse(arrows().get(1).has(Accessible.State.ENABLED),
                "a fresh spinner is at min, so its lower half cannot move the value"
                        + describe(tree()));
        assertNull(arrows().get(1).actions(),
                "and a node that is not ENABLED publishes no verb" + describe(tree()));
        assertEquals(List.of("Increase", "Decrease"), arrowNames,
                "named by the toolkit, because nothing else can reach them: a synthetic child is "
                        + "not a widget, so setAccessibleName, a bound caption and the tooltip "
                        + "default all land on the spinner's own node. Nameless, a reader hears "
                        + "\"button, button\" beside every number -- twenty-two times in a colour "
                        + "picker -- and no application has a fix" + describe(tree()));
        assertEquals(List.of(spinnerNode().id()),
                nodesWith(Accessible.State.FOCUSABLE).stream().map(AccessibleNode::id).toList(),
                "the spinner is the only tab stop in the tree, once" + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the facet

    @Test
    void theFacetIsTheValueTheBoundsAndTheStepAndTheTextIsWhatIsOnScreen() {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));

        assertEquals(new ValueFacet(7, 0, 99, 1, "7", false), spinnerNode().value(),
                "the number, the bounds the widget clamps to, what one increment moves it by, "
                        + "and the display form; writable, so SET_VALUE is advertised"
                        + describe(tree()));

        bindSpinner(new Spinner(0, 1, 0.25).setValue(0.5));
        assertEquals(new ValueFacet(0.5, 0, 1, 0.25, "0.50", false), spinnerNode().value(),
                "the decimals the step implies reach the text and never the number"
                        + describe(tree()));
    }

    @Test
    void aTimeSpinnerPublishesMinutesAndShowsTheClockFace() {
        bindSpinner(Spinner.time().setValue(7 * 60 + 30));

        assertEquals(new ValueFacet(450, 0, 24 * 60 - 1, 60, "07:30", false),
                spinnerNode().value(),
                "minutes since midnight is the number, 07:30 is the display form, and the step is "
                        + "60 because a fresh time spinner's arrows are on the hours"
                        + describe(tree()));
    }

    @Test
    void thePublishedStepFollowsTheFieldTheArrowsAreOn() {
        bindSpinner(Spinner.time().setValue(7 * 60 + 30));
        assertEquals(60.0, spinnerNode().value().step(), describe(tree()));

        press(Keys.RIGHT);

        assertEquals(1.0, spinnerNode().value().step(),
                "Right picks the minutes, and one increment is now the step field; publishing "
                        + "that field in both would tell a reader \"one minute\" while Up moves an "
                        + "hour" + describe(tree()));
        assertEquals(450.0, spinner.value(), "picking a field moves no value");

        press(Keys.LEFT);

        assertEquals(60.0, spinnerNode().value().step(), describe(tree()));
        assertEquals(450.0, spinner.value());
        assertEquals(List.of(), changed, "and tells the application nothing either");
        assertEquals(List.of(), valueEvents(),
                "the difference raises a value change only when the value moves, so a reader that "
                        + "re-reads hears the new step and one that only listens does not: the "
                        + "stated cost of carrying the field this way" + bridge.events);
    }

    @Test
    void theDigitsAndTheLanguageAreTheSubtreesOwn() {
        bindSpinner(new Spinner(0, 100, 1).setValue(42));
        assertEquals("42", spinnerNode().value().text(), describe(tree()));

        root.setLocale(ARABIC);
        frame();

        assertEquals("٤٢", spinnerNode().value().text(),
                "the hook runs inside the pushed scope the paint resolves in, so the memo key "
                        + "matches and the walk and the paint share one build" + describe(tree()));
        assertEquals(ARABIC, spinnerNode().locale(),
                "and the node says which language it is in" + describe(tree()));
        assertEquals(42.0, spinnerNode().value().value(),
                "the number never localizes" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the actions

    @Test
    void theTwoStepsReachTheFromTheUserPathAndSetValueDoesNot() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));
        long id = spinnerNode().id();

        spinner.setValue(20);
        frame();
        assertEquals(List.of(), changed,
                "the public setter is the silent path, and the tree still moved");
        assertEquals(1, valueEvents().size(), bridge.events.toString());
        bridge.events.clear();

        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE),
                "accepted, which is not the same as done");
        frame();

        assertEquals(21.0, spinner.value());
        assertEquals(List.of(21.0), changed,
                "the hook reaches the same nudge a key press reaches, and that is what tells the "
                        + "application; setValue would not have");
        List<AccessibleEvent> raised = valueEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(id, raised.get(0).nodeId());
        assertEquals(20.0, raised.get(0).oldValue());
        assertEquals(21.0, raised.get(0).newValue());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press and nothing else: " + bridge.events);

        perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(20.0, spinner.value());
        assertEquals(List.of(21.0, 20.0), changed);

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(42));
        frame();
        assertEquals(42.0, spinner.value());
        assertEquals(List.of(21.0, 20.0, 42.0), changed,
                "a reader's set is a change the user made, unlike the application's own");
    }

    @Test
    void aTimeSpinnersStepMovesByTheIncrementItPublished() throws Exception {
        bindSpinner(Spinner.time().setValue(7 * 60 + 30));
        long id = spinnerNode().id();

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(8 * 60 + 30.0, spinner.value(),
                "one increment on the hours field is an hour, which is the number published as "
                        + "the step");
        assertEquals("08:30", spinnerNode().value().text(), describe(tree()));
    }

    @Test
    void theStepsNeverMirrorWhereTheArrowKeysDo() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();
        long id = spinnerNode().id();

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(8.0, spinner.value(),
                "an increment is the Up sense — a direction of the value and not a side of the "
                        + "box — and Up is the one axis a mirrored layout leaves alone");

        perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();
        assertEquals(7.0, spinner.value());

        // The horizontal keys do mirror, which is what the verbs must not copy: reading right to
        // left the low end of the axis is on the side reading starts from, so Left raises.
        press(Keys.LEFT);
        assertEquals(8.0, spinner.value(),
                "a hook built out of onKeyEvent's mirrored rightStep would have run backwards");
        press(Keys.RIGHT);
        assertEquals(7.0, spinner.value());
    }

    @Test
    void aSetClampsSnapsAndDropsAnEditInProgress() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));
        long id = spinnerNode().id();

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(7.4));
        frame();
        assertEquals(7.0, spinner.value(), "the same snap a typed commit reaches");

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1e9));
        frame();
        assertEquals(99.0, spinner.value(), "and the same clamp");
        assertEquals(new ValueFacet(99, 0, 99, 1, "99", false), spinnerNode().value(),
                describe(tree()));

        bindSpinner(new Spinner(0, 99, 1).setSnapToStep(false).setValue(7));
        perform(spinnerNode().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(7.4));
        frame();
        assertEquals(7.4, spinner.value(),
                "a spinner that does not own its value is not tidied on a reader's set either");

        // An edit in progress is cancelled rather than left to be committed later over the top of
        // what the reader just chose, which is what setValue's own documentation asks for.
        bindSpinner(new Spinner(0, 99, 1).setValue(7));
        type('8');
        assertTrue(spinner.isEditing(), "the fixture really did open an edit");
        perform(spinnerNode().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(42));
        frame();
        assertFalse(spinner.isEditing(), "the half-typed text is no longer about this number");
        assertEquals(42.0, spinner.value());

        scene.requestFocus(null);
        frame();
        assertEquals(42.0, spinner.value(),
                "and focus leaving commits nothing, because there was nothing left to commit");
        assertEquals("42", spinnerNode().value().text(), describe(tree()));
    }

    /**
     * Decision 5 (CRIT-7's widget half): a set by <em>text</em> goes through the same reader a
     * typed commit does, because a platform's value pattern hands over the string a client spoke
     * or typed, and a spinner that could only be set by number could not be set by a reader at
     * all. What the reader cannot parse is refused as a non-number is, before the clamp.
     */
    @Test
    void aSetByTextIsReadAsATypedCommitIsAndWhatCannotBeReadIsRefused() throws Exception {
        bindSpinner(new Spinner(0, 99, 0.5).setValue(7));
        long id = spinnerNode().id();

        assertTrue(perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfText("42")));
        frame();
        assertEquals(42.0, spinner.value(), "the digits a client spoke");
        assertEquals(List.of(42.0), changed, "from the user, as a typed commit is");

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfText(" 1,5 "));
        frame();
        assertEquals(1.5, spinner.value(),
                "the comma most of the world types, and the padding a client leaves");

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfText("٣٤"));
        frame();
        assertEquals(34.0, spinner.value(), "any known digit set, as the editor accepts");

        bridge.events.clear();
        changed.clear();
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfText("forty"));
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfText(""));
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfText("1.2.3"));
        frame();
        assertEquals(34.0, spinner.value(), "what the reader cannot read is refused whole");
        assertEquals(List.of(), changed, "refused before any handler");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        bindSpinner(Spinner.time().setValue(7 * 60 + 30));
        perform(spinnerNode().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfText("18:45"));
        frame();
        assertEquals(18 * 60 + 45.0, spinner.value(),
                "a time spinner reads the clock face it shows, in the minutes it publishes");
        assertEquals("18:45", spinnerNode().value().text(), describe(tree()));
    }

    @Test
    void aValueThatIsNotANumberIsRefusedBeforeTheClamp() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));
        long id = spinnerNode().id();
        bridge.events.clear();

        // The host answers "accepted", never "done": the refusal is the hook's, and what proves it
        // is the value and the tree afterwards.
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(Double.NaN));
        perform(id, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.POSITIVE_INFINITY));
        perform(id, Accessible.Action.SET_VALUE, Accessible.Argument.NONE);
        frame();

        assertEquals(7.0, spinner.value(),
                "Math.max and Math.min pass NaN through and Math.rint keeps it, so an unguarded "
                        + "set would store it and publish it forever");
        assertEquals(new ValueFacet(7, 0, 99, 1, "7", false), spinnerNode().value(),
                describe(tree()));
        assertEquals(List.of(), changed, "refused before any handler");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void everyVerbIsRefusedWhileDisabled() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));
        long id = spinnerNode().id();
        long up = arrows().get(0).id();
        spinner.setEnabled(false);
        frame();
        bridge.events.clear();

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(50));
        perform(up, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(7.0, spinner.value(), "a disabled control was operated");
        assertEquals(List.of(), changed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
        AccessibleNode node = spinnerNode();
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertEquals(new ValueFacet(7, 0, 99, 1, "7", false), node.value(),
                "a disabled spinner is heard as disabled rather than vanishing, its value as "
                        + "writable as it is (fix round 2e)" + describe(tree()));
        assertFalse(node.accepts(Accessible.Action.SET_VALUE),
                "and it accepts no SET_VALUE, because it is not ENABLED" + describe(tree()));
        assertNull(node.actions(), "no verb the scene refuses is published" + describe(tree()));
        for (AccessibleNode arrow : arrows()) {
            assertNull(arrow.actions(), "nor on its arrows" + describe(tree()));
        }
    }

    // ------------------------------------------------------------------------- the bounds (d. 69)

    /**
     * Decision 69, 2026-09-16: the arrow that cannot move the value any further publishes without
     * {@code ENABLED} and carries no verb, so a reader says "unavailable" instead of offering a
     * press that does nothing. The spinner's own node is untouched by it — it is a value-bearing
     * node whose {@code INCREMENT} past the end of its range is the shape a scroll bar and a rail
     * have too — and so is everything else the two halves publish, which is why the name, the box
     * and the role are read back here as well.
     */
    @Test
    void theUpperHalfAtTheMaximumIsNotEnabledAndCarriesNoVerb() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(99));
        AccessibleNode up = arrows().get(0);
        AccessibleNode down = arrows().get(1);
        bridge.events.clear();

        assertFalse(up.has(Accessible.State.ENABLED),
                "at max the upper arrow cannot move the value, and it is drawn dimmed: the node "
                        + "says the same thing the pixels do" + describe(tree()));
        assertNull(up.actions(),
                "and a node that is not ENABLED publishes no verb at all -- no PRESS, and no "
                        + "INCREMENT either, which it never declared" + describe(tree()));
        assertFalse(up.accepts(Accessible.Action.PRESS), describe(tree()));
        assertEquals("Increase", up.name(),
                "it is still named and still boxed: unavailable, not gone" + describe(tree()));
        assertEquals(Accessible.Role.BUTTON, up.role(), describe(tree()));
        assertTrue(up.width() > 0 && up.height() > 0, describe(tree()));
        assertTrue(up.has(Accessible.State.SHOWING), describe(tree()));

        assertTrue(down.has(Accessible.State.ENABLED),
                "the other half still moves, so the narrowing is per arrow and not per widget"
                        + describe(tree()));
        assertTrue(down.actions().has(Accessible.Action.PRESS), describe(tree()));

        AccessibleNode node = spinnerNode();
        assertTrue(node.has(Accessible.State.ENABLED),
                "the owner is untouched: disabled() narrows the child alone" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.INCREMENT),
                "and the spinner's own INCREMENT at max stays published -- that is a value-bearing "
                        + "node stepping past the end of its own range, the scroll bar's shape, "
                        + "and not an arrow" + describe(tree()));

        // Sent anyway, the way a platform that read a stale snapshot would send it. Host#perform
        // answers true from the membership check alone and the UI-thread refusal stays silent
        // (semantics 5), so what is asserted is the refusal's effect and not its return value.
        perform(up.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(99.0, spinner.value(), "performing it moved nothing");
        assertEquals(List.of(), changed, "and reached no application handler");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void theLowerHalfAtTheMinimumIsNotEnabledAndCarriesNoVerb() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(0));
        AccessibleNode down = arrows().get(1);
        bridge.events.clear();

        assertFalse(down.has(Accessible.State.ENABLED), describe(tree()));
        assertNull(down.actions(), describe(tree()));
        assertEquals("Decrease", down.name(), describe(tree()));
        assertTrue(arrows().get(0).has(Accessible.State.ENABLED), describe(tree()));

        assertFalse(down.accepts(Accessible.Action.PRESS), describe(tree()));
        perform(down.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(0.0, spinner.value(), "performing it moved nothing");
        assertEquals(List.of(), changed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void bothHalvesAreOperableInsideTheRange() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(50));

        for (AccessibleNode arrow : arrows()) {
            assertTrue(arrow.has(Accessible.State.ENABLED), describe(tree()));
            assertTrue(arrow.actions().has(Accessible.Action.PRESS), describe(tree()));
            assertTrue(arrow.accepts(Accessible.Action.PRESS), describe(tree()));
        }
        assertTrue(perform(arrows().get(0).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE), describe(tree()));
        frame();
        assertEquals(51.0, spinner.value());
        assertTrue(perform(arrows().get(1).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE), describe(tree()));
        frame();
        assertEquals(50.0, spinner.value());
        assertEquals(List.of(51.0, 50.0), changed);
    }

    /**
     * The arrow comes back the moment the value leaves the bound, through the difference the
     * publish raises rather than through a rebuild: the narrowing is read off the value in the
     * hook, so nothing has to remember to clear it. A time spinner is used for the second half
     * because its increment is 60 while the arrows are on the hours, and a bound predicate written
     * against the increment rather than against the value would put the upper arrow out one hour
     * early.
     */
    @Test
    void anArrowComesBackWhenTheValueLeavesTheBound() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(99));
        assertFalse(arrows().get(0).has(Accessible.State.ENABLED), describe(tree()));

        spinner.setValue(98);
        frame();

        assertTrue(arrows().get(0).has(Accessible.State.ENABLED),
                "one step off the bound and the arrow is operable again" + describe(tree()));
        assertTrue(arrows().get(0).actions().has(Accessible.Action.PRESS), describe(tree()));

        bindSpinner(Spinner.time(0, 23 * 60 + 59, 1).setValue(23 * 60));
        assertEquals(60.0, spinnerNode().value().step(),
                "the fixture really is on the hours field" + describe(tree()));
        assertTrue(arrows().get(0).has(Accessible.State.ENABLED),
                "23:00 is not 23:59: the bound is the value against max, never the value plus one "
                        + "increment" + describe(tree()));

        spinner.setValue(23 * 60 + 59);
        frame();

        assertFalse(arrows().get(0).has(Accessible.State.ENABLED), describe(tree()));
        assertNull(arrows().get(0).actions(), describe(tree()));
    }

    /**
     * The case the narrowing's first round covered only by accident: with an <em>edit open</em>, a
     * press on the arrow at its bound used to move the value anyway and be acknowledged for it.
     * {@code Scene#performAccessibleAction} gates a synthetic press on the OWNER's enabled chain
     * and never reads the child's own withdrawn verb, so a stale snapshot's press reaches the hook;
     * the hook then committed the typed number before discovering it could not step, and
     * {@code value != before} answered true for the commit alone — an {@code INVOKED} spoken for a
     * dead arrow, and the application's handler called twice, on a spinner already at {@code max}.
     * The hook now reads the same two bounds the publish step reads, before any side effect at all.
     * The no-edit cases above cannot see this: there {@code commitEdit} has nothing to commit and
     * the clamp inside {@link Spinner#nudge} hides the missing refusal.
     */
    @Test
    void anArrowAtItsBoundRefusesEvenWithAnEditOpen() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(99));
        AccessibleNode up = arrows().get(0);
        type('5');
        assertEquals(99.0, spinner.value(),
                "typing does not commit, so the fixture really is an open edit over the bound");
        changed.clear();
        bridge.events.clear();

        assertFalse(up.accepts(Accessible.Action.PRESS), describe(tree()));
        perform(up.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(99.0, spinner.value(),
                "a press the node does not publish adopts no typed number and takes no step");
        assertEquals(List.of(), changed,
                "and reaches the application's handler not once, let alone twice: " + changed);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertEquals(List.of(), valueEvents(), bridge.events.toString());

        // The other half is live at max and keeps the commit-then-step it is documented for, so
        // what the refusal costs is exactly the dead arrow and nothing else.
        assertTrue(perform(arrows().get(1).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE), describe(tree()));
        frame();
        assertEquals(4.0, spinner.value(),
                "reaching for a live arrow commits the typed 5 and then steps it down");
        assertEquals(List.of(5.0, 4.0), changed, changed.toString());
    }

    @Test
    void aVerbTheSpinnerDoesNotOfferIsRefused() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));
        long id = spinnerNode().id();
        bridge.events.clear();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_TEXT, new Accessible.Argument.OfText("50"));
        perform(arrows().get(0).id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(7.0, spinner.value());
        assertEquals(List.of(), changed);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // ----------------------------------------------------------------------------- the geometry

    @Test
    void theStepperColumnIsTwoStackedBoxesAtTheTrailingEdgeAndTheyMirror() {
        bindSpinner(new Spinner(0, 99, 1));
        float columnW = SizeTokens.MEDIUM.spinnerButtonW();
        float w = spinner.width();
        float h = spinner.height();
        assertTrue(w > columnW, "the fixture has to leave room for a value area: " + w);

        AccessibleNode up = arrows().get(0);
        AccessibleNode down = arrows().get(1);
        assertEquals(columnW, up.width(), describe(tree()));
        assertEquals(columnW, down.width(), describe(tree()));
        assertEquals(spinner.localToSceneX() + w - columnW, up.x(),
                "the column sits on the side reading ends on" + describe(tree()));
        assertEquals(up.x(), down.x(), describe(tree()));
        assertEquals(spinner.localToSceneY(), up.y(), describe(tree()));
        assertEquals(up.y() + up.height(), down.y(),
                "the two halves tile the column with no seam and no gap" + describe(tree()));
        assertEquals(spinner.localToSceneY() + h, down.y() + down.height(),
                "and the lower one runs to the bottom, which is the open-ended half regionAt "
                        + "tests for" + describe(tree()));

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(spinner.localToSceneX(), arrows().get(0).x(),
                "reading right to left the column is the leading one, at the box's own edge"
                        + describe(tree()));
        assertEquals(columnW, arrows().get(0).width(), describe(tree()));
        assertEquals(arrows().get(0).x(), arrows().get(1).x(), describe(tree()));
    }

    @Test
    void thePublishedBoxIsWhereAClickWorks() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));
        AccessibleNode up = arrows().get(0);

        assertTrue(perform(up.id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(8.0, spinner.value());
        assertEquals(List.of(8.0), changed);

        // The same box, hit with a pointer: the node is tied to regionAt rather than to the paint,
        // so a rectangle taken from paintButtons' unclamped column or from the widget's whole box
        // would separate the two.
        clickCentreOf(up);

        assertEquals(9.0, spinner.value(), "a click at the centre of the published box steps too");
        assertEquals(List.of(8.0, 9.0), changed);

        AccessibleNode down = arrows().get(1);
        perform(down.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();
        clickCentreOf(down);

        assertEquals(7.0, spinner.value(), "and the lower half lowers, both ways");
    }

    /** Moves the pointer to one scene point and renders the frame that would republish. */
    private void hoverAt(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
        frame();
    }

    /** Presses and releases the left button at the centre of a published box. */
    private void clickCentreOf(AccessibleNode box) {
        float x = box.x() + box.width() / 2;
        float y = box.y() + box.height() / 2;
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        drive(scene).inputBatchEnded();
        frame();
    }

    @Test
    void aSpinnerSqueezedNarrowerThanItsColumnPublishesTheRegionThatIsLeft() {
        // onMeasure's spinnerWidth is a floor on a request, and a parent may undercut it; the
        // colour picker's unselected notation panels hold spinners that were never laid out at
        // all. The published boxes have to stay the hit regions through both.
        float columnW = SizeTokens.MEDIUM.spinnerButtonW();
        float squeezed = columnW / 2;
        spinner = new Spinner(0, 99, 1);
        spinner.onChange(changed::add);
        root = new Column();
        root.add(new SizedBox(squeezed, SizedBox.UNSET, spinner));
        bind(root);

        assertEquals(squeezed, spinner.width(), "the fixture really did undercut the floor");
        AccessibleNode up = arrows().get(0);
        assertEquals(squeezed, up.width(),
                "the whole of what is left is the stepper column, which is exactly what regionAt "
                        + "answers once the value area has been squeezed to nothing"
                        + describe(tree()));
        assertEquals(spinner.localToSceneX(), up.x(), describe(tree()));
        assertEquals(up.x(), arrows().get(1).x(), describe(tree()));

        // A click at the centre of that box still steps, which is the whole point of deriving the
        // rectangle from the hit test rather than from the paint: paintButtons' own column would
        // start at a negative x here and reach past the widget.
        clickCentreOf(up);
        assertEquals(1.0, spinner.value(), describe(tree()));
    }

    // ---------------------------------------------------------------------------- the two paths

    @Test
    void aReadersPressCommitsAnEditWhereAReadersStepStepsFromIt() throws Exception {
        // The arrow button's box is the box a click lands in, so it does what a click does:
        // reaching for the arrows is leaving the text.
        bindSpinner(new Spinner(0, 99, 1).setValue(3));
        type('.');
        assertTrue(spinner.isEditing());
        assertTrue(perform(arrows().get(0).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));
        frame();

        assertFalse(spinner.isEditing(), "the press ended the edit, as a click on it would");
        assertEquals(4.0, spinner.value(),
                "\".\" parses to nothing, so the commit keeps the value and the step moves it");

        // The spinner's own node takes the key path instead, which adopts what is typed and leaves
        // the edit open.
        bindSpinner(new Spinner(0, 99, 1).setValue(3));
        type('8');
        assertTrue(spinner.isEditing());
        changed.clear(); // the first half of this case moved a spinner of its own
        assertTrue(perform(spinnerNode().id(), Accessible.Action.INCREMENT,
                Accessible.Argument.NONE));
        frame();

        assertTrue(spinner.isEditing(),
                "Up mid-edit leaves the edit open, and an increment is Up: collapsing the two "
                        + "paths into one loses whichever behaviour the source gave that gesture");
        assertEquals(9.0, spinner.value(), "stepped from the number on screen, not from 3");
        assertEquals(List.of(9.0), changed, "the step is the one reported change; the adoption is "
                + "silent");
        assertEquals("9", spinnerNode().value().text(),
                "and the editor was re-seeded from the committed value, so the two agree"
                        + describe(tree()));
    }

    @Test
    void aSyntheticPressIsAcknowledgedOnce() throws Exception {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));
        long up = arrows().get(0).id();
        bridge.events.clear();

        perform(up, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene owes a press its acknowledgement, once: " + bridge.events);
        assertEquals(up, bridge.events.stream()
                        .filter(e -> e.type() == AccessibleEvent.Type.INVOKED)
                        .findFirst().orElseThrow().nodeId(),
                "on the node that was pressed" + bridge.events);
        assertEquals(1, valueEvents().size(),
                "and the value moved, which is the fact a reader actually wants: " + bridge.events);
    }

    // ------------------------------------------------------------------------------- the naming

    @Test
    void aBareSpinnerIsNamelessAndTakesWhateverNamesItFromOutside() {
        bindSpinner(new Spinner(0, 99, 1));

        AccessibleNode nameless = spinnerNode();
        assertEquals("", nameless.name(),
                "the widget holds no string of any kind, so it hands none over; a synthesised one "
                        + "would have no source to compare and would allocate per damaged frame"
                        + describe(tree()));
        assertEquals("", nameless.description(), describe(tree()));

        spinner.setTooltip("Quantity");
        frame();
        AccessibleNode byTooltip = spinnerNode();
        assertEquals("Quantity", byTooltip.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, byTooltip.nameFrom(), describe(tree()));

        Label caption = new Label("Copies");
        root.add(caption);
        caption.setLabelFor(spinner);
        frame();
        AccessibleNode byLabel = spinnerNode();
        assertEquals("Copies", byLabel.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, byLabel.nameFrom(),
                "which is the route the colour picker's eleven steppers already take"
                        + describe(tree()));
        assertEquals("Quantity", byLabel.description(),
                "once something else named the node, the tooltip describes it" + describe(tree()));
        assertEquals(node("Copies").id(), byLabel.id());
        assertNotNull(byLabel.relations(), describe(tree()));
        assertTrue(byLabel.relations().stream()
                        .anyMatch(r -> r.kind() == Accessible.Relation.LABELLED_BY),
                "and the relation back to the caption travels with the name" + describe(tree()));

        spinner.setAccessibleName("Number of copies");
        frame();
        assertEquals("Number of copies", spinnerNode().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, spinnerNode().nameFrom(), describe(tree()));
        assertEquals(nameless.id(), spinnerNode().id(), "a name is not a rebuild");
    }

    // -------------------------------------------------------------------------- what it costs

    @Test
    void hoverRepublishesNothing() {
        bindSpinner(new Spinner(0, 99, 1).setValue(7));
        frame();
        int published = bridge.published.size();
        bridge.events.clear();

        // Every region in turn, each entered at the centre of the box the tree published for it:
        // the value area, the upper half, the value area again, the lower half.
        AccessibleNode up = arrows().get(0);
        AccessibleNode down = arrows().get(1);
        float insideTheValue = spinner.localToSceneX() + 4;
        float middle = spinner.localToSceneY() + spinner.height() / 2;
        hoverAt(insideTheValue, middle);
        hoverAt(up.x() + up.width() / 2, up.y() + up.height() / 2);
        hoverAt(insideTheValue, middle);
        hoverAt(down.x() + down.width() / 2, down.y() + down.height() / 2);

        assertEquals(published, bridge.published.size(),
                "which half the pointer is over moves on every MOVE and says nothing a reader "
                        + "wants, so reading it here would re-copy the tree on a mouse crossing");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void aDamagedButUnchangedSpinnerPublishesNothingAndAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindSpinner(Spinner.time().setValue(7 * 60 + 30));
        scene.requestFocus(spinner);
        frame();
        type('0');
        assertTrue(spinner.isEditing(), "the caret blink is what damages this widget on its own");

        // The focus fade is a wall-clock transition; a measurement taken mid-flight measures it.
        for (int i = 0; i < 400; i++) {
            spinner.invalidate();
            frame();
        }
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            spinner.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            spinner.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            spinner.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a spinner that did not move must cost no memory: the display form is "
                        + "handed over from the widget's own memo with the counter it was filled "
                        + "against, and a format call in the hook — or the variable-argument "
                        + "action call — would be one string per damaged frame spent concluding "
                        + "that nothing had moved");
    }
}

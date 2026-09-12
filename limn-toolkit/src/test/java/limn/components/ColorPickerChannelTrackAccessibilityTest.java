package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.ValueFacet;
import limn.graphics.Color;
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
 * What a {@link ColorPicker}'s channel rails become in the accessible tree: one horizontal
 * {@code SLIDER} per channel, named by the letter beside it, carrying a writable value facet
 * <b>in the units of the stepper on the same line</b> and offering the two steps, operated from a
 * screen reader through the same private mutator a drag and an arrow key reach, commit included.
 *
 * <p>Two things this file exists to pin, both of which a reading of ADR 039 §7's row alone would
 * get wrong. The first is which numbers the facet carries: the widget offers two candidates and
 * only one of them is what the user sees. {@code fraction()} is a paint coordinate in
 * {@code [0,1]}, and publishing it would tell a reader "0.2 of 0 to 1" while the stepper beside
 * the rail says 51; the facet carries the spinner's own value, minimum and maximum, which differ
 * per channel <em>and</em> per notation — 255 for a red, 360 for a hue, 100 for an ink.
 *
 * <p>The second is that there are <b>ten</b> of these nodes at every moment and seven of them are
 * not on screen. The tabbed pane keeps every panel and lays out only the selected one, so R, G, B,
 * H, S, V, C, M, Y and K are all published, the unselected notations' rails carry neither
 * {@code VISIBLE} nor {@code SHOWING} nor {@code FOCUSABLE}, and §1.9's gate is what refuses a
 * verb on one of them. Describing a rail only while its tab is selected would destroy and rebuild
 * seven nodes on every tab click.
 *
 * <p>Three more places the row is short, each with a case below. It says the name is "declared by
 * the picker", which is impossible: the walk offers a child only to its <em>direct</em> parent,
 * and a rail's parent is an {@code Expanded}, so the link is a standing one made in the
 * constructor through {@code Label#setLabelFor}. It omits {@code SET_VALUE}, which the writable
 * facet advertises on every platform and which is the only verb here needing real work, since the
 * rail has no set-by-value path of its own. And it is silent on orientation and on mirroring in
 * the one widget whose documentation is about mirroring: the rail reflects its sweep, its thumb,
 * its press inversion and its Left and Right arms, and none of that touches the facet or the two
 * verbs.
 *
 * <p>Every case drives the picker's public API, and {@code Widget}'s, on a bound scene, or calls
 * the scene from where a bridge stands. Nothing constructs a node.
 */
class ColorPickerChannelTrackAccessibilityTest extends AccessibleComponentTestBase {

    /** The exact class an application must never find in its log because of this step. */
    private static final String THE_TRACK = "limn.components.ColorPicker$ChannelGroup$ChannelTrack";

    private ColorPicker picker;

    /** The column the picker sits in, the root of every fixture. */
    private Column root;

    /** Every colour the application's change handler was given, in order. */
    private final List<Color> changed = new ArrayList<>();

    /** Every colour the application's commit handler was given, in order. */
    private final List<Color> committed = new ArrayList<>();

    /** Every record the walk logged while a test was running; see {@link #theLogNamesTheTrack}. */
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
        // Every language but French falls back to the letters the component declares, and one case
        // below asks for French on the picker's own subtree, so the rest are pinned to the
        // language they assert in.
        limn.i18n.I18n.setLocale(Locale.ENGLISH);
    }

    /**
     * The rails are focusable and paint, so a step that dropped their role would publish ten
     * unknown tab stops and name a toolkit class in an application's log.
     *
     * <p>Scoped to this one class rather than to the picker: a warning about any part of the
     * chooser is logged under a name that starts with the picker's, and the walk logs each one
     * once per class for the whole process, so whichever case runs first is the one that would
     * see it, which is why the check runs after every one.
     */
    @AfterEach
    void theLogNamesTheTrack() {
        walkLogger.removeHandler(capture);
        limn.i18n.I18n.setLocale(before);
        for (LogRecord record : logged) {
            Object[] parameters = record.getParameters();
            if (parameters == null) {
                continue;
            }
            for (Object parameter : parameters) {
                assertNotEquals(THE_TRACK, parameter,
                        "a channel rail declares a role, so the walk must never publish it "
                                + "UNKNOWN nor say it paints and is deleted: " + record.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One picker at the width the gallery gives it, so every rail has a line to itself. */
    private void bindPicker() {
        picker = new ColorPicker();
        picker.onChange(changed::add);
        picker.onCommit(committed::add);
        root = new Column();
        root.add(new SizedBox(380, SizedBox.UNSET, picker));
        bind(root);
    }

    /**
     * The rail of one channel of the notation currently showing.
     *
     * <p>Scoped three ways, and none of them is fussiness. The role alone finds every rail of
     * every notation at once, the alpha rail under them and the hue ramp beside the plane. The
     * name alone finds the caption beside the rail first, which holds the same letter. And the two
     * together are still ambiguous in French, where {@code channel.g} is V for Vert and
     * {@code channel.v} is V for Valeur: only the notation on screen tells those two apart.
     *
     * @param letter the channel's letter in the language under test
     * @return the one rail of that letter a user can currently see
     */
    private AccessibleNode rail(String letter) {
        AccessibleNode found = null;
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.role() == Accessible.Role.SLIDER && node.name().equals(letter)
                    && node.has(Accessible.State.SHOWING)) {
                if (found != null) {
                    throw new AssertionError("two showing rails named " + letter + describe(tree()));
                }
                found = node;
            }
        }
        if (found == null) {
            throw new AssertionError("no showing rail named " + letter + describe(tree()));
        }
        return found;
    }

    /**
     * @return every channel rail in the tree, showing or not, in tree order; the two sliders that
     *         are not channels are left out — the alpha rail by its own letter, and the hue ramp
     *         by its provenance, since a channel is named by the caption on its line and the ramp
     *         is the one control here that carries a name of its own
     */
    private List<AccessibleNode> everyChannelRail() {
        List<AccessibleNode> found = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.role() == Accessible.Role.SLIDER
                    && node.nameFrom() == Accessible.NameFrom.LABEL
                    && !node.name().equals("A")) {
                found.add(node);
            }
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

    /**
     * @param value the number the stepper beside the rail is showing
     * @param max   the top of that channel's range
     * @return the writable facet a channel rail publishes, on the whole numbers a drag rounds to
     */
    private static ValueFacet channel(double value, double max) {
        return new ValueFacet(value, 0, max, 1, null, false);
    }

    /** @return every value the notation on screen is showing, in line order */
    private List<Double> row() {
        List<Double> values = new ArrayList<>();
        for (AccessibleNode node : everyChannelRail()) {
            if (node.has(Accessible.State.SHOWING)) {
                values.add(node.value().value());
            }
        }
        return values;
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void everyChannelIsOneHorizontalWritableSliderInItsOwnUnits() {
        bindPicker();

        AccessibleNode node = rail("R");
        assertTrue(node.has(Accessible.State.HORIZONTAL),
                "the class has one axis: the travel, the thumb's centre and the press inversion "
                        + "are all horizontal" + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "focusable from Rail's constructor, which is why a role is not optional here"
                        + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY),
                "the channel is settable, and the facet says so" + describe(tree()));
        assertFalse(node.has(Accessible.State.ACTIVE),
                "a rail is not a container's cursor" + describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertEquals(Set.of(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT,
                        Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW),
                node.actions().actions(),
                "the two steps and the walk's two. SET_VALUE is advertised by the writable "
                        + "facet's presence and is never in this list; PRESS and TOGGLE are not "
                        + "what a rail does" + describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "the sweep and the thumb are paint, not nodes: a mark on a track is not a control"
                        + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the units

    @Test
    void theFacetIsTheStepperNextToItAndNeverThePaintsFraction() {
        bindPicker();

        picker.setColor(Color.rgb(0x336699));
        frame();
        assertEquals(channel(51, 255), rail("R").value(),
                "the number the stepper on this line is showing, on the whole-number grid moveTo "
                        + "rounds every gesture onto — not the 0.2 of a range of one that the "
                        + "paint's fraction would have published" + describe(tree()));
        assertEquals(channel(102, 255), rail("G").value(), describe(tree()));
        assertEquals(channel(153, 255), rail("B").value(), describe(tree()));
        assertEquals(picker.channel(0).value(), rail("R").value().value(),
                "which is the same number, read off the same object that clamps a set"
                        + describe(tree()));

        picker.setFormat(ColorPicker.Format.HSV);
        frame();
        assertEquals(360, rail("H").value().max(),
                "a hue is degrees, and the range is what carries that: there is no unit in the "
                        + "number and none on screen either" + describe(tree()));
        assertEquals(100, rail("S").value().max(), describe(tree()));
        assertEquals(100, rail("V").value().max(), describe(tree()));
        assertEquals(picker.channel(0).value(), rail("H").value().value(), describe(tree()));
        assertEquals(0, rail("H").value().min(), describe(tree()));
        assertEquals(1, rail("H").value().step(),
                "and one degree is the grid, because that is what moveTo rounds a drag to"
                        + describe(tree()));

        picker.setFormat(ColorPicker.Format.CMYK);
        frame();
        assertEquals(4, row().size(),
                "four inks onto a three-axis colour: the notation decides how many rails there are"
                        + describe(tree()));
        for (String ink : List.of("C", "M", "Y", "K")) {
            assertEquals(100, rail(ink).value().max(), describe(tree()));
        }
    }

    // ------------------------------------------------------------------------------- the action

    @Test
    void aStepMovesOneUnitOfWhatTheNumberShowsAndTellsTheApplicationTwice() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x336699));
        frame();
        long id = rail("R").id();
        changed.clear();
        committed.clear();
        bridge.events.clear();

        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE),
                "accepted, which is not the same as done");
        frame();

        assertEquals(channel(52, 255), rail("R").value(),
                "one of 255 reds, the same unit the rail's own Up arrow moves by" + describe(tree()));
        assertEquals(52, Math.round(picker.color().r() * 255),
                "the hook reaches moveTo, which is what a drag and an arrow reach");
        assertEquals(1, changed.size(),
                "and moveTo calls write(), which is what tells the application; the spinner's own "
                        + "setter is silent and would have said nothing: " + changed);
        assertEquals(52, Math.round(changed.get(0).r() * 255));
        assertEquals(1, committed.size(),
                "then the commit, as a whole gesture: a reader's step has no release to follow");
        List<AccessibleEvent> raised = valueEventsOn(id);
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(51.0, raised.get(0).oldValue());
        assertEquals(52.0, raised.get(0).newValue());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press and nothing else: " + bridge.events);

        bridge.events.clear();
        perform(id, Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(channel(51, 255), rail("R").value(), describe(tree()));
        assertEquals(2, changed.size());
        assertEquals(2, committed.size());
        raised = valueEventsOn(id);
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(52.0, raised.get(0).oldValue());
        assertEquals(51.0, raised.get(0).newValue());
    }

    @Test
    void aStepAtTheEndCommitsTheValueHeldAndPublishesNothing() throws Exception {
        bindPicker();
        picker.setColor(Color.WHITE);
        frame();
        long id = rail("R").id();
        changed.clear();
        committed.clear();
        int published = bridge.published.size();

        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE),
                "accepted: the verb ran, and what it ran was a commit");
        frame();

        assertEquals(channel(255, 255), rail("R").value(), describe(tree()));
        assertEquals(List.of(), changed, "nothing moved, so nothing changed");
        assertEquals(1, committed.size(),
                "but the user chose the end, exactly as End at max commits");
        assertEquals(List.of(), valueEventsOn(id), bridge.events.toString());
        assertEquals(published, bridge.published.size(),
                "a value that did not move invalidates nothing and publishes nothing");
    }

    @Test
    void setValueLandsOnTheGridADragLandsOnAndRefusesWhatIsNotANumber() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x336699));
        frame();
        long id = rail("R").id();
        changed.clear();
        committed.clear();

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(200.4));
        frame();
        assertEquals(channel(200, 255), rail("R").value(),
                "the argument arrives in the domain the facet published and lands where a drag "
                        + "would, because both go through the same moveTo" + describe(tree()));
        assertEquals(200, Math.round(picker.color().r() * 255));
        assertEquals(1, changed.size());
        assertEquals(1, committed.size());

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1e9));
        frame();
        assertEquals(channel(255, 255), rail("R").value(), "the clamp" + describe(tree()));

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(-5));
        frame();
        assertEquals(channel(0, 255), rail("R").value(), describe(tree()));
        assertEquals(3, changed.size());
        assertEquals(3, committed.size());

        bridge.events.clear();
        // The boolean means accepted and not done, so what a refusal looks like from here is the
        // colour, the handlers and the event list all standing still.
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(Double.NaN));
        perform(id, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.POSITIVE_INFINITY));
        perform(id, Accessible.Action.SET_VALUE, Accessible.Argument.NONE);
        frame();

        assertEquals(channel(0, 255), rail("R").value(),
                "clamp01 passes NaN through and Math.round(Float.NaN) is zero, so an unguarded one "
                        + "would drive the channel to its minimum and report it as a user change"
                        + describe(tree()));
        assertEquals(3, changed.size(), "refused before any handler");
        assertEquals(3, committed.size());
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void aSetOnOneInkLeavesTheOtherThreeWhereTheUserPutThem() throws Exception {
        bindPicker();
        picker.setFormat(ColorPicker.Format.CMYK);
        picker.setColor(Color.rgb(0x336699));
        frame();
        List<Double> was = row();
        changed.clear();

        perform(rail("C").id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(80));
        frame();

        assertEquals(80.0, rail("C").value().value(), describe(tree()));
        assertEquals(was.subList(1, 4), row().subList(1, 4),
                "CMYK is four axes onto a three-axis colour, so a colour cannot say which "
                        + "quadruple of inks made it; the row the user dialled in stands because "
                        + "the hook goes through moveTo and write(), which is where the widget "
                        + "keeps its own separation" + describe(tree()));
        assertEquals(1, changed.size(), "and one edit is one change: " + changed);
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x336699));
        frame();
        long id = rail("R").id();
        changed.clear();
        committed.clear();
        bridge.events.clear();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_TEXT, new Accessible.Argument.OfText("200"));
        frame();

        assertEquals(channel(51, 255), rail("R").value(), describe(tree()));
        assertEquals(List.of(), changed, "the hook answers false for what it does not offer");
        assertEquals(List.of(), committed);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- the direction

    @Test
    void theStepsDoNotMirrorAlthoughTheArrowsBesideThemDo() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x336699));
        frame();
        AccessibleNode leftToRight = rail("R");

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode mirrored = rail("R");
        assertEquals(leftToRight.id(), mirrored.id(), "a direction is not a rebuild");
        assertEquals(leftToRight.value(), mirrored.value(),
                "the facet is the value, and a value has no side" + describe(tree()));

        perform(mirrored.id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(channel(52, 255), rail("R").value(),
                "an increment is a direction of the value, as Up is, and never a side of the "
                        + "rail: the hook uses the two arms this class does not mirror, and one "
                        + "built from Left and Right would run backwards in Arabic and Hebrew"
                        + describe(tree()));

        // The other half of the same fact, from the keyboard's side: Left names the end of the
        // range it physically points at, so it is the arm that does turn round.
        scene.requestFocus(picker.rail(0));
        frame();
        scene.keyEvent(Keys.LEFT, true, false, 0);
        scene.inputBatchEnded();
        frame();
        assertEquals(channel(53, 255), rail("R").value(),
                "reading right to left, Left is the way the value grows" + describe(tree()));

        root.setLayoutDirection(LayoutDirection.LTR);
        frame();
        scene.keyEvent(Keys.LEFT, true, false, 0);
        scene.inputBatchEnded();
        frame();
        assertEquals(channel(52, 255), rail("R").value(),
                "and left to right it is the way it shrinks, while the verb above did the same "
                        + "thing in both: mirroring the verbs to match the arrows would send a "
                        + "reader's increment the wrong way" + describe(tree()));
    }

    // --------------------------------------------------------------------------------- the name

    @Test
    void aRailIsNamedByTheLetterBesideItAndCarriesTheRelationBothWays() {
        bindPicker();

        AccessibleNode red = rail("R");
        assertEquals(Accessible.NameFrom.LABEL, red.nameFrom(),
                "the rail paints no text, so a CONTENT name would be a lie, and a LABEL provenance "
                        + "without the relation would be a provenance the enum's own definition "
                        + "denies" + describe(tree()));

        AccessibleNode caption = null;
        for (AccessibleNode node : named("R")) {
            if (node.role() == Accessible.Role.LABEL) {
                caption = node;
            }
        }
        assertNotEquals(null, caption, "the letter is a Label of its own" + describe(tree()));
        assertEquals(caption.id(), relationTarget(red, Accessible.Relation.LABELLED_BY),
                "so a reader that would rather walk to the caption can" + describe(tree()));
        assertEquals(red.id(), relationTarget(caption, Accessible.Relation.LABEL_FOR),
                "and the caption says which control it names; the constructor's setLabelFor is "
                        + "what puts both halves there, because the walk offers a child only to "
                        + "its DIRECT parent and this rail's is an Expanded" + describe(tree()));
    }

    @Test
    void theLettersFollowTheLanguageAndTwoOfThemCollideThere() {
        bindPicker();
        picker.setColor(Color.rgb(0x336699));
        frame();
        List<Long> ids = new ArrayList<>();
        for (AccessibleNode node : everyChannelRail()) {
            ids.add(node.id());
        }

        picker.setLocale(Locale.FRENCH);
        frame();

        assertEquals(List.of(51.0, 102.0, 153.0), row(),
                "a language moves no number" + describe(tree()));
        assertEquals("V", rail("V").name(),
                "green is Vert here, and the rail is named by the caption's own string rather "
                        + "than by anything resolved inside the hook" + describe(tree()));
        assertEquals(Locale.FRENCH, rail("V").locale(), describe(tree()));
        assertEquals(102.0, rail("V").value().value(),
                "and it is the green rail and not the value rail, which is named V too and is not "
                        + "on screen: the notation is what tells them apart" + describe(tree()));
        assertEquals(List.of(), named("G"), "there is no G in this language" + describe(tree()));

        List<Long> after = new ArrayList<>();
        for (AccessibleNode node : everyChannelRail()) {
            after.add(node.id());
        }
        assertEquals(ids, after, "a language is not a rebuild" + describe(tree()));

        picker.setFormat(ColorPicker.Format.HSV);
        frame();
        assertEquals("T", rail("T").name(), "Teinte" + describe(tree()));
        assertEquals(100.0, rail("V").value().max(),
                "and now the V on screen is Valeur, a percent rather than one of 255"
                        + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the modes

    @Test
    void everyNotationIsPublishedAndOnlyTheSelectedOneIsOnScreen() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x336699));
        frame();

        List<String> letters = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (AccessibleNode node : everyChannelRail()) {
            letters.add(node.name());
            ids.add(node.id());
        }
        assertEquals(List.of("R", "G", "B", "H", "S", "V", "C", "M", "Y", "K"), letters,
                "the pane keeps every panel, so all ten rails are in the tree at once"
                        + describe(tree()));

        for (AccessibleNode node : everyChannelRail()) {
            boolean selected = List.of("R", "G", "B").contains(node.name());
            assertEquals(selected, node.has(Accessible.State.SHOWING), describe(tree()));
            assertEquals(selected, node.has(Accessible.State.VISIBLE), describe(tree()));
            assertEquals(selected, node.has(Accessible.State.FOCUSABLE),
                    "an unselected notation's rails are no more reachable from a reader than from "
                            + "the keyboard, which is what keeps reading order equal to Tab order"
                            + describe(tree()));
            assertTrue(node.actions().has(Accessible.Action.INCREMENT),
                    "and every one of them keeps its verbs: a verb list says what a control "
                            + "offers, and the missing SHOWING bit is what says it is not on "
                            + "screen" + describe(tree()));
        }

        AccessibleNode hidden = null;
        for (AccessibleNode node : everyChannelRail()) {
            if (node.name().equals("C")) {
                hidden = node;
            }
        }
        changed.clear();
        String was = picker.color().toHex();
        // Accepted, because the identifier is in the published tree, and dead on arrival: the
        // scene re-checks isShowing() on the thread that owns the widget and returns before the
        // hook is reached, which is the same wall the pointer and the keyboard hit. The hook's own
        // isEnabled() guard does not cover this case and is not meant to.
        assertTrue(perform(hidden.id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(90)));
        frame();

        assertEquals(was, picker.color().toHex(),
                "a reader may not edit the colour through a notation that is not on screen");
        assertEquals(List.of(), changed);

        bridge.events.clear();
        picker.setFormat(ColorPicker.Format.CMYK);
        frame();

        List<Long> after = new ArrayList<>();
        for (AccessibleNode node : everyChannelRail()) {
            after.add(node.id());
        }
        assertEquals(ids, after,
                "a tab click moves which rails are on screen and destroys nothing: describing a "
                        + "rail only while its notation is selected would rebuild seven nodes on "
                        + "every click" + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());
        assertEquals(List.of("C", "M", "Y", "K"),
                List.of(rail("C").name(), rail("M").name(), rail("Y").name(), rail("K").name()),
                describe(tree()));
        assertTrue(perform(rail("C").id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE));
        frame();
        assertEquals(1, changed.size(),
                "and the same rail is operable the moment its notation is: " + changed);
    }

    @Test
    void aDisabledPickerRefusesEveryVerbAndKeepsTheSlidersInTheTree() throws Exception {
        bindPicker();
        picker.setColor(Color.rgb(0x336699));
        frame();
        long id = rail("R").id();
        changed.clear();
        committed.clear();

        picker.setEnabled(false);
        frame();

        AccessibleNode node = everyChannelRail().get(0);
        assertEquals(id, node.id());
        assertFalse(node.has(Accessible.State.ENABLED),
                "the bit is inherited down the walk from the picker" + describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertEquals(channel(51, 255), node.value(),
                "a disabled rail is heard as disabled rather than vanishing" + describe(tree()));
        bridge.events.clear();

        perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(200));
        frame();

        assertEquals(51, Math.round(picker.color().r() * 255), "a disabled control was operated");
        assertEquals(List.of(), changed);
        assertEquals(List.of(), committed);
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // ------------------------------------------------------------------------------- the colour

    @Test
    void theValueFollowsTheModelThroughEveryPublicPath() {
        bindPicker();
        picker.setColor(Color.rgb(0x336699));
        frame();
        bridge.events.clear();

        picker.setInitialColor(Color.rgb(0x33669A));
        frame();

        assertEquals(List.of(51.0, 102.0, 154.0), row(),
                "the rails follow the colour, whichever setter moved it" + describe(tree()));
        assertEquals(1, valueEventsOn(rail("B").id()).size(),
                "one value change on the one channel that moved: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "and no structure, because a colour is not a shape: " + bridge.events);

        bridge.events.clear();
        picker.setAlphaEnabled(false);
        frame();

        assertEquals(List.of(51.0, 102.0, 154.0), row(),
                "alpha is a line of its own and moves no channel" + describe(tree()));
        assertEquals(10, everyChannelRail().size(),
                "and the ten rails stand across it" + describe(tree()));
    }

    // ----------------------------------------------------------------------------- the geometry

    @Test
    void theBoxIsTheHitTargetHandedDownByTheShareAboveIt() {
        bindPicker();

        AccessibleNode node = rail("R");
        assertEquals(picker.rail(0).localToSceneX(), node.x(), describe(tree()));
        assertEquals(picker.rail(0).localToSceneY(), node.y(), describe(tree()));
        assertEquals(picker.rail(0).width(), node.width(),
                "the Expanded above it hands the rail (0, 0, width(), height()), so the rail's box "
                        + "is the share's box, and it is the rectangle Rail#pick inverts"
                        + describe(tree()));
        assertEquals(picker.rail(0).height(), node.height(), describe(tree()));

        SizeTokens tokens = Theme.current().tokensFor(picker.rail(0));
        assertEquals(Math.max(Strokes.MIN_HIT_TARGET,
                        tokens.colorThumbH() + 2 * Strokes.FOCUS_GAP_SLIDER + Strokes.FOCUS_RING),
                node.height(),
                "the rail's own measure, floored at the reachable target" + describe(tree()));
        assertTrue(node.height() > tokens.colorRailH(),
                "and taller than the band it paints: a magnifier and a click-at-point client are "
                        + "given the target and not the picture" + describe(tree()));
    }

    // ---------------------------------------------------------------------------- what it costs

    @Test
    void aQuietRailAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindPicker();
        picker.setColor(Color.rgb(0x336699));
        frame();
        scene.requestFocus(picker.rail(0));
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
                "describing ten rails that did not move must cost no memory: the role is an enum, "
                        + "the value is the spinner's own double, the orientation is a bit and the "
                        + "two verbs are two bits. A formatted number in the hook, a call to "
                        + "ColorPickerStrings.channels — a fresh array per lookup — or the "
                        + "variable-argument action call would each be one allocation per damaged "
                        + "frame, and every frame of a focus fade is such a frame");
    }
}

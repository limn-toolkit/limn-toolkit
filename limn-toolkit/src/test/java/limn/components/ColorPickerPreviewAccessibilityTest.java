package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.graphics.Color;
import limn.i18n.StringBundle;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ColorPicker}'s before/after swatch becomes in the accessible tree: one
 * {@code IMAGE} whose name is the comparison it paints &mdash; the colour showing now and the one
 * the picker opened on &mdash; and nothing else at all.
 *
 * <p>The decision this file exists to pin is <b>both colours in one name</b>, and ADR 039 §7's row
 * for this class gets it wrong in the shortest possible way: "name is the colour in hex",
 * singular, where the widget paints two halves. The current colour is published three or four
 * times over in this picker already, as each channel rail's number; the opening colour is written
 * only by {@link ColorPicker#setInitialColor}, read only by this widget's paint, and appears
 * nowhere else in the tree. A name holding only the current hex would therefore repeat a
 * neighbour and lose the one fact the swatch contributes, and a name holding only the opening one
 * would describe half the picture. The comparison is the content.
 *
 * <p>The row is also silent that its own verdict is not optional. This widget is never focusable
 * and declares nothing else, so §1.6's predicate deleted it and the walk named
 * {@code limn.components.ColorPicker$Preview} in an application's log &mdash; and all three
 * one-line fixes that warning recommends are unreachable, because the class is private and
 * nothing the picker exposes to an application reaches the instance. The widget carries its own
 * name or nobody can.
 *
 * <p>And the row's dash under actions is right for a reason it does not give: of the three parts
 * this picker draws, this is the only one that is not an input site. It has no mouse handler, no
 * key handling and no private user-equivalent path, so there is nothing for an action hook to
 * reach and it must not gain one.
 *
 * <p>Every case drives the picker's public API, and {@code Widget}'s, on a bound scene, or calls
 * the scene from where a bridge stands. Nothing constructs a node.
 */
class ColorPickerPreviewAccessibilityTest extends AccessibleComponentTestBase {

    /** The exact class an application must never find in its log because of this step. */
    private static final String THE_SWATCH = "limn.components.ColorPicker$Preview";

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /**
     * One bundle for one key, and it puts the opening colour first.
     *
     * <p>No shipped bundle translates this pattern &mdash; French renames the notations and their
     * letters and nothing else here &mdash; so a language switch alone leaves the sentence in the
     * English the component declares, and could not tell a memo that reads the language from one
     * that does not. This is what makes the case bite, and it is also the only place the pattern's
     * two slots are proved to be reorderable by whoever translates it.
     */
    private static final StringBundle TRANSLATED = (key, locale) ->
            "limn.colorPicker.swatch".equals(key) && BRAZILIAN.equals(locale)
                    ? "Antes {1}, agora {0}" : null;

    private ColorPicker picker;

    /** The column the picker sits in, the root of every fixture. */
    private Column root;

    /** Every record the walk logged while a test was running; see {@link #theLogNeverNamesTheSwatch}. */
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
        // The sentence falls back to the English the component declares in every shipped language,
        // and one case below asks for another language on the picker's own subtree, so the rest
        // are pinned to the one they assert in.
        limn.i18n.I18n.setLocale(Locale.ENGLISH);
    }

    /**
     * The swatch declares a name, so it survives the transparency predicate and the walk has
     * nothing to say about it.
     *
     * <p>Scoped to this one class rather than to the picker, because the steppers and the hex
     * field are still waiting for their own steps and warn under names of their own. The warning
     * is logged once per class for the whole process, so whichever case runs first is the one that
     * would see it, which is why this runs after every one.
     */
    @AfterEach
    void theLogNeverNamesTheSwatch() {
        walkLogger.removeHandler(capture);
        // Process-wide statics: a case that leaks its language or its bundle breaks every later one.
        limn.i18n.I18n.removeBundle(TRANSLATED);
        limn.i18n.I18n.setLocale(before);
        for (LogRecord record : logged) {
            Object[] parameters = record.getParameters();
            if (parameters == null) {
                continue;
            }
            for (Object parameter : parameters) {
                assertNotEquals(THE_SWATCH, parameter,
                        "the swatch names itself, so the walk must never say it paints and is "
                                + "deleted: " + record.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One picker at the width the gallery gives it, so the identity row has its full line. */
    private void bindPicker() {
        picker = new ColorPicker();
        root = new Column();
        root.add(new SizedBox(380, SizedBox.UNSET, picker));
        bind(root);
    }

    /** @return the one image in the tree, which is the swatch */
    private AccessibleNode swatch() {
        return node(Accessible.Role.IMAGE);
    }

    /** @return the picker's own node, the chooser everything here sits inside */
    private AccessibleNode chooser() {
        return node(Accessible.Role.COLOR_CHOOSER);
    }

    /**
     * @param hex the string to look for, as a substring
     * @return every place in the tree a reader could hear it: names, descriptions, value texts
     *         and text contents, one entry per node that carries it
     */
    private List<String> everywhereSaying(String hex) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.name().contains(hex)) {
                found.add("name of " + node.role() + " \"" + node.name() + "\"");
            }
            if (node.description().contains(hex)) {
                found.add("description of " + node.role());
            }
            if (node.value() != null && node.value().text() != null
                    && node.value().text().contains(hex)) {
                found.add("value text of " + node.role());
            }
            if (node.text() != null && node.text().text().contains(hex)) {
                found.add("text of " + node.role());
            }
        }
        return found;
    }

    /**
     * @param nodeId the node to read
     * @return every name change raised on it so far, in order
     */
    private List<AccessibleEvent> nameEventsOn(long nodeId) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.NAME_CHANGED && event.nodeId() == nodeId) {
                found.add(event);
            }
        }
        return found;
    }

    /** @return the node of the channel rail showing {@code letter}, the only showing one */
    private AccessibleNode rail(String letter) {
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.role() == Accessible.Role.SLIDER && node.name().equals(letter)
                    && node.has(Accessible.State.SHOWING)) {
                return node;
            }
        }
        throw new AssertionError("no showing rail named " + letter + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void theSwatchIsOneImageNamingBothColours() {
        bindPicker();

        picker.setInitialColor(Color.rgb(0xFFFFFF));
        frame();
        picker.setColor(Color.rgb(0x3366CC));
        frame();

        AccessibleNode node = swatch();
        assertEquals("Colour #3366CC, was #FFFFFF", node.name(),
                "one node, one sentence, and the sentence is the comparison the widget paints. "
                        + "ADR 039 §7's row says \"the colour in hex\", singular, and the widget "
                        + "paints two halves" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(),
                "the name is the widget's own, derived from what it drew rather than supplied by "
                        + "an application or a label" + describe(tree()));
        assertEquals("", node.description(),
                "everything a reader needs is in the name; splitting the pair across a name and a "
                        + "description would silence half of it wherever help text is not spoken"
                        + describe(tree()));
        assertNull(node.value(),
                "a colour has no honest scalar, and a value text with no number beside it is "
                        + "dropped by the builder without a word" + describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.selectionItem(), describe(tree()));
        assertNull(node.actions(),
                "not an input site: no mouse handler, no key handling, no private "
                        + "user-equivalent path — and not focusable, so the walk offers neither "
                        + "focus nor scroll-into-view either" + describe(tree()));
        assertEquals(List.of(), node.relations(),
                "no caption points at it: the \"#\" beside it names the hex field's punctuation "
                        + "and is already refused" + describe(tree()));
        assertEquals(Set.of(Accessible.State.ENABLED, Accessible.State.VISIBLE,
                        Accessible.State.SHOWING), node.states(),
                "the three the walk inherits down, and nothing declared here" + describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "the two halves are drawn and never instantiated, but a synthetic child is for "
                        + "something a reader operates, selects or hit-tests, and a half of a "
                        + "swatch has no verb. Two nodes would double the identity and difference "
                        + "cost of a widget every drag step damages, and would drag the paint's "
                        + "right-to-left swap into the tree" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the colour

    @Test
    void theOpeningColourIsPublishedHereAndNowhereElse() {
        bindPicker();

        picker.setInitialColor(Color.rgb(0xFFFFFF));
        frame();
        picker.setColor(Color.rgb(0x3366CC));
        frame();

        assertEquals(List.of("name of IMAGE \"Colour #3366CC, was #FFFFFF\""),
                everywhereSaying("#FFFFFF"),
                "the colour the picker opened on is written only by setInitialColor, read only by "
                        + "this widget's paint, and reaches a reader through this one name or not "
                        + "at all. A step that decided this node duplicates the hex field and "
                        + "deleted it, or that reached for paintsDecoration, would take it with "
                        + "them" + describe(tree()));

        assertEquals(List.of(51.0, 102.0, 204.0),
                List.of(rail("R").value().value(), rail("G").value().value(),
                        rail("B").value().value()),
                "while the current colour is separately readable as the channel numbers, which is "
                        + "why the current half of this sentence is a deliberate repetition and "
                        + "the opening half is not" + describe(tree()));
    }

    // -------------------------------------------------------------------------- what it costs

    @Test
    void aQuietSwatchAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindPicker();
        picker.setInitialColor(Color.rgb(0xAF7AFF));
        frame();

        assertEquals(0, allocatedByADamagedFrame(),
                "describing a swatch that did not move must cost no memory, and three ways to "
                        + "fail it are one careless line away in the hook: color() builds a Color "
                        + "record per call, toHex() runs two format calls, and a parameterized "
                        + "I18nString is documented as never cached. The walk runs over every "
                        + "widget whenever anything is damaged, and this widget is damaged on "
                        + "every step of every drag");
    }

    @Test
    void theNameFollowsTheSubtreeLanguageAndTheTranslationEpochSeparately() {
        bindPicker();
        picker.setInitialColor(Color.rgb(0xFFFFFF));
        picker.setColor(Color.rgb(0x3366CC));
        frame();
        long id = swatch().id();
        assertEquals("Colour #3366CC, was #FFFFFF", swatch().name(), describe(tree()));

        picker.setLocale(BRAZILIAN);
        frame();

        assertEquals(BRAZILIAN, swatch().locale(),
                "the node says which language it is in" + describe(tree()));
        assertEquals("Colour #3366CC, was #FFFFFF", swatch().name(),
                "nothing translates the pattern yet, so it is still the English the component "
                        + "declares" + describe(tree()));

        // The epoch alone now: the subtree's language has not moved, and a memo that could not see
        // a translation arriving would go on saying the sentence it built before this line.
        limn.i18n.I18n.addBundle(TRANSLATED);
        frame();

        assertEquals("Antes #FFFFFF, agora #3366CC", swatch().name(),
                "and the translator moved the two slots into the order this language wants, which "
                        + "is what the pattern carries them for: the painted order is the layout's "
                        + "answer and the spoken order is theirs" + describe(tree()));
        assertEquals(id, swatch().id(), "a translation is not a rebuild" + describe(tree()));

        if (AllocationProbe.isSupported()) {
            assertEquals(0, allocatedByADamagedFrame(),
                    "and the memo settles again under the new language rather than rebuilding the "
                            + "sentence on every damaged frame from here on");
        }

        // The language alone now: the epoch has not moved since the bundle arrived.
        picker.setLocale(null);
        frame();

        assertEquals(Locale.ENGLISH, swatch().locale(), describe(tree()));
        assertEquals("Colour #3366CC, was #FFFFFF", swatch().name(),
                "a subtree that gives its language back speaks the process's again, which a memo "
                        + "keyed on the colour alone could not have noticed" + describe(tree()));
        assertEquals(id, swatch().id(), describe(tree()));
    }

    // -------------------------------------------------------------------------------- the moves

    @Test
    void everyPublicPathThatMovesTheColourMovesTheNameAndNothingElseDoes() throws Exception {
        bindPicker();
        picker.setInitialColor(Color.rgb(0xFFFFFF));
        frame();
        long id = swatch().id();
        bridge.events.clear();

        picker.setColor(Color.rgb(0x3366CC));
        frame();
        assertEquals("Colour #3366CC, was #FFFFFF", swatch().name(), describe(tree()));
        assertEquals(1, nameEventsOn(id).size(),
                "one event for one move: " + bridge.events);

        bridge.events.clear();
        assertTrue(perform(rail("R").id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(255)), "accepted");
        frame();
        assertEquals("Colour #FF66CC, was #FFFFFF", swatch().name(),
                "a set from a reader on the rail beside it moves this sentence too, because it "
                        + "reaches the same private path a drag does" + describe(tree()));
        assertEquals(1, nameEventsOn(id).size(), bridge.events.toString());

        bridge.events.clear();
        scene.requestFocus(picker);
        frame();
        assertEquals(picker, scene.focusedWidget(), "the arrows are the picker's own");
        bridge.events.clear();
        scene.keyEvent(Keys.LEFT, true, false, 0);
        scene.keyEvent(Keys.LEFT, false, false, 0);
        scene.inputBatchEnded();
        frame();
        assertEquals("Colour #FF67CC, was #FFFFFF", swatch().name(),
                "the picker's own arrows walk the saturation/value plane by one of the 255 steps "
                        + "an RGB channel can express, and the swatch says so" + describe(tree()));
        assertEquals(1, nameEventsOn(id).size(), bridge.events.toString());

        String held = swatch().name();
        bridge.events.clear();
        picker.setFormat(ColorPicker.Format.CMYK);
        frame();
        assertEquals(held, swatch().name(),
                "a notation switch re-notates the numbers beside the swatch and does not move the "
                        + "colour, so the sentence is unchanged and a reader is told nothing"
                        + describe(tree()));
        assertEquals(List.of(), nameEventsOn(id), bridge.events.toString());

        bridge.events.clear();
        picker.setInitialColor(picker.color());
        frame();
        assertEquals("Colour #FF67CC, was #FF67CC", swatch().name(),
                "re-anchoring the comparison on the colour already showing moves the opening half "
                        + "and nothing else, and it is the one path that does: every other one "
                        + "moves a channel too. A memo that keyed on the five colour primitives "
                        + "and forgot the original would go on saying the colour this picker was "
                        + "opened on an hour ago" + describe(tree()));
        assertEquals(1, nameEventsOn(id).size(), bridge.events.toString());

        bridge.events.clear();
        picker.setInitialColor(Color.rgb(0x112233));
        frame();
        assertEquals("Colour #112233, was #112233", swatch().name(),
                "and setting the initial colour to a new one moves both halves at once, which is "
                        + "what opening the picker on a colour means" + describe(tree()));
        assertEquals(1, nameEventsOn(id).size(), bridge.events.toString());
    }

    @Test
    void turningAlphaOffShortensTheCurrentHexAndLeavesTheOriginalAlone() {
        bindPicker();

        picker.setInitialColor(Color.rgba(0x3366CC, 0.5f));
        frame();
        String translucent = swatch().name();
        assertTrue(translucent.endsWith("80") || translucent.endsWith("7F"),
                "eight digits while alpha is offered, on both halves: " + translucent);

        picker.setAlphaEnabled(false);
        frame();

        String opaque = swatch().name();
        assertEquals("Colour #3366CC, was " + translucent.substring(translucent.indexOf("was ") + 4),
                opaque,
                "color() clamps alpha to opaque while the line is off, so the current half loses "
                        + "its two alpha digits — and the opening half keeps its own, because the "
                        + "paint fills the original raw and this node says what is painted"
                        + describe(tree()));

        picker.setAlphaEnabled(true);
        frame();

        assertEquals(translucent, swatch().name(),
                "and the mode flip is in the memo's key, so turning it back on restores the "
                        + "sentence rather than leaving a stale one" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the geometry

    @Test
    void theBoxIsTheSwatchTheTokenBoxHandedDown() {
        bindPicker();

        AccessibleNode node = swatch();
        assertEquals(picker.preview().localToSceneX(), node.x(), describe(tree()));
        assertEquals(picker.preview().localToSceneY(), node.y(), describe(tree()));
        assertEquals(2 * node.height(), node.width(),
                "one control tall and two wide, which is the token box's own extent handed "
                        + "straight down: it lays the child out over its whole box, so the "
                        + "published rectangle is the painted swatch including its outline"
                        + describe(tree()));
        assertTrue(node.x() >= chooser().x() && node.y() >= chooser().y()
                        && node.x() + node.width() <= chooser().x() + chooser().width(),
                "and it is inside the chooser" + describe(tree()));

        float x = node.x();
        String said = node.name();
        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertNotEquals(x, swatch().x(),
                "the row reflects the box for free" + describe(tree()));
        assertEquals(said, swatch().name(),
                "and the sentence is character-for-character what it was: the painted order is "
                        + "the layout's answer and the spoken order is the translator's, so the "
                        + "hook reads layoutDirection() nowhere at all" + describe(tree()));
    }

    // -------------------------------------------------------------------------------- the guard

    @Test
    void aDisabledPickerKeepsTheSwatchAndGivesItNoVerbs() {
        bindPicker();
        picker.setInitialColor(Color.rgb(0x3366CC));
        frame();
        long id = swatch().id();

        picker.setEnabled(false);
        frame();

        assertEquals(id, swatch().id(),
                "the predicate reads a widget's own declarations and never the inherited bits, so "
                        + "disabling the picker neither deletes this node nor grows a skeleton "
                        + "around it" + describe(tree()));
        assertFalse(swatch().has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(swatch().has(Accessible.State.FOCUSABLE), describe(tree()));
        assertNull(swatch().actions(), describe(tree()));
        assertEquals("Colour #3366CC, was #3366CC", swatch().name(),
                "and it still says what it shows" + describe(tree()));

        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            assertFalse(node.role() == Accessible.Role.GROUP && node.name().isEmpty(),
                    "a nameless group is scaffolding that survived the predicate" + describe(tree()));
        }
    }

    // ------------------------------------------------------------------------------- the probe

    /**
     * Settles the picker's own animations, then measures a damaged frame that moves no colour.
     *
     * <p>The tab strip's indicator slides on a wall clock and damages the picker on every frame it
     * runs for, so a measurement taken while it is mid-flight is a measurement of the animation.
     *
     * @return how much the least expensive of sixty such frames allocated
     */
    private long allocatedByADamagedFrame() {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
        while (System.nanoTime() < until) {
            picker.invalidate();
            frame();
        }
        int published = bridge.published.size();
        bridge.events.clear();

        long withAReaderAttached = AllocationProbe.leastAllocatedBy(() -> {
            picker.invalidate();
            frame();
        }, 60);

        assertEquals(published, bridge.published.size(),
                "a damaged frame that moved no colour is no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        bridge.listening = false;
        long withNobodyListening = AllocationProbe.leastAllocatedBy(() -> {
            picker.invalidate();
            frame();
        }, 60);
        bridge.listening = true;
        return withAReaderAttached - withNobodyListening;
    }
}

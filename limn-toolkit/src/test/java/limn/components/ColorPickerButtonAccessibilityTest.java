package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.AccessibleTree;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.i18n.StringBundle;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.layout.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ColorPickerButton} becomes in the accessible tree: one button node carrying a
 * popup, named by the colour it shows unless a caption was set, and opening the same picker a
 * click opens.
 *
 * <p>ADR 039 §7's row has the role, the state, the verb and the absence of synthetic children
 * right against the source, and two of its five cells wrong. "Value text is the hex" is
 * unimplementable &mdash; a value text with no number beside it is dropped by the builder without
 * a word, and a colour has no honest scalar to pair one with, which §7.2 had already said one row
 * above about the picker &mdash; so the hex is the name here, and the description only where a
 * caption displaced it. And "{@code CONTROLLER_FOR} the dialog once it exists" is wrong in both
 * presentations: in scene the walk already publishes that relation off the overlay's inheritance
 * host, so declaring it would publish it twice, and in a window of its own the dialog is in
 * another tree and is not a widget at all. Both are pinned below.
 *
 * <p>The row is also silent about the two things that decide this widget's mapping: the name comes
 * from two places with different provenance mechanics, and the default one is a pair of format
 * calls. A hex built inside the describe hook would allocate one string per damaged frame in order
 * to conclude that nothing had moved, on a widget a hover fade, a focus fade and a picker drag
 * each damage on every frame. The last case is the one that would catch it.
 *
 * <p>Every case drives the public setters on a bound scene, or calls the scene from where a bridge
 * stands, and reads what the scene published; nothing here builds a tree.
 */
class ColorPickerButtonAccessibilityTest extends AccessibleComponentTestBase {

    /** The colour every fixture starts on, and the hex it writes itself as. */
    private static final Color AMBER = Color.rgb(0xF59E0B);
    private static final String AMBER_HEX = "#F59E0B";

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** A caption with a key, so that a set caption is a string a translator can move. */
    private static final I18nString CAPTION =
            new I18nString("limn.test.colorWell.caption", "Accent");

    /** The one translation, for the caption alone: nothing can translate a hex. */
    private static final StringBundle TRANSLATED = (key, locale) ->
            CAPTION.key().equals(key) && BRAZILIAN.equals(locale) ? "Destaque" : null;

    /** The clock the hover, focus and overlay fades run on, so a test can move time itself. */
    private final AtomicLong clock = new AtomicLong();

    /** Every colour the application's listener was handed, in order. */
    private final List<Color> reported = new ArrayList<>();

    /** How many times the application's listener ran. */
    private final AtomicInteger changes = new AtomicInteger();

    private ColorPickerButton well;

    private Locale before;

    @BeforeEach
    void pinTheLanguage() {
        before = limn.i18n.I18n.processLocale();
        limn.i18n.I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void releaseTheLanguage() {
        // Process-wide statics: a case that leaks its language or its bundle breaks every later one.
        limn.i18n.I18n.removeBundle(TRANSLATED);
        limn.i18n.I18n.setLocale(before);
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * One colour well as the only control in a column, on a clock this test owns.
     *
     * <p>{@code hosted} asks for the window that can host an in-scene dialog. It is what every
     * case that actually presses needs: {@link StubWindow#backend()} throws by design, and the
     * default {@link DisplayMode#NATIVE_WINDOW} presentation reaches it, so a press on a plain
     * stub would fail with an assertion about the harness rather than about this widget.
     */
    private void bindWell(boolean hosted) {
        well = new ColorPickerButton(AMBER);
        well.onChange(colour -> {
            reported.add(colour);
            changes.incrementAndGet();
        });
        Column root = new Column();
        root.add(well);

        bridge = new RecordingBridge();
        StubWindow host = hosted ? new OverlayHost() : new StubWindow();
        host.accessibility = bridge;
        window = host;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(root, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    /** The fixture every case that presses uses: hosted, and asking for the overlay presentation. */
    private void bindWellThatCanOpen() {
        bindWell(true);
        well.setPickerDisplayMode(DisplayMode.IN_SCENE);
        assertEquals(DisplayMode.IN_SCENE, well.pickerDisplayMode(), "the fixture asked for it");
    }

    /** @return the well's node, which is the tree's only button while no dialog is up */
    private AccessibleNode control() {
        return node(Accessible.Role.BUTTON);
    }

    /**
     * @param id the identifier to look up
     * @return the node carrying it, which is how the well is found once a dialog with buttons of
     *         its own is on screen
     */
    private AccessibleNode byId(long id) {
        AccessibleTree tree = tree();
        int at = tree.indexOf(id);
        assertNotEquals(AccessibleNode.NONE, at, "node " + id + " is gone" + describe(tree));
        return tree.node(at);
    }

    private void tick() {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
        frame();
    }

    /** Advances the clock until the picker's card has settled where it will stay. */
    private void settleTheDialog() {
        Dialog dialog = well.openDialog();
        assertNotNull(dialog, "nothing to settle");
        for (int i = 0; i < 100 && dialog.fadeLevel() < 1; i++) {
            tick();
        }
        assertEquals(1, dialog.fadeLevel(), "the fade-in did not settle");
        tick();
        tick();
    }

    /** Advances the clock past every chrome fade this widget runs, then damages nothing. */
    private void settleTheFades() {
        for (int i = 0; i < 40; i++) {
            tick();
        }
    }

    /** Delivers one click at the centre of the well, as a pointer does, framing while held. */
    private void click() {
        float x = well.localToSceneX() + well.width() / 2;
        float y = well.localToSceneY() + well.height() / 2;
        scene.mouseMoved(x, y);
        scene.inputBatchEnded();
        frame();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.inputBatchEnded();
        frame(); // a frame with the button held, so a published PRESSED would be seen
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
        frame();
    }

    /** @return whether any tree published so far carried {@code state} on the node with that id */
    private boolean everPublished(long id, Accessible.State state) {
        for (AccessibleTree tree : bridge.published) {
            int at = tree.indexOf(id);
            if (at != AccessibleNode.NONE && tree.node(at).has(state)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param node the node to read
     * @param kind the relation to collect
     * @return every relation of that kind on it, so that a duplicate is visible rather than hidden
     *         behind the first match
     */
    private static List<AccessibleRelation> relationsOf(AccessibleNode node,
                                                        Accessible.Relation kind) {
        return node.relations().stream().filter(relation -> relation.kind() == kind).toList();
    }

    // ------------------------------------------------------------------------------- what it is

    @Test
    void aColourWellPublishesOneNodeNamedByTheColourItShows() {
        bindWell(false);

        AccessibleNode node = control();
        assertEquals(Accessible.Role.BUTTON, node.role(),
                "the chooser is the ColorPicker the press raises, which has a node of its own; "
                        + "what this widget does is open a dialog" + describe(tree()));
        assertEquals(AMBER_HEX, node.name(),
                "the default caption is the colour's hex, and it is the whole of what this widget "
                        + "paints as text" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(), describe(tree()));
        assertEquals("", node.description(),
                "the hex is published once, and with no caption to displace it that is the name"
                        + describe(tree()));
        assertTrue(node.has(Accessible.State.HAS_POPUP),
                "unconditionally, because it is the bit that says this is not a plain button "
                        + "before anything has happened" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "focusable from its constructor" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.PRESS),
                "the one verb the widget declares" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS),
                "and the two the walk adds for every focusable widget" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.EXPAND),
                "a modal dialog is not a disclosure, and the collapse would have to reach the "
                        + "dialog's own resolution, which its card already offers" + describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.COLLAPSE), describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "the chip is paint inside one hit target, not a node" + describe(tree()));
        assertEquals(List.of(), node.relations(),
                "nothing is owed here while no dialog exists" + describe(tree()));

        assertEquals(well.localToSceneX(), node.x(), describe(tree()));
        assertEquals(well.localToSceneY(), node.y(), describe(tree()));
        assertEquals(well.width(), node.width(), describe(tree()));
        assertEquals(well.height(), node.height(), describe(tree()));
        assertNotEquals(well.height() + 2 * Strokes.FOCUS_RING_OUTSET, node.height(),
                "paintOutset declares how far the focus ring's ink reaches for damage; it is not "
                        + "the operable rectangle and must never be folded into the bounds"
                        + describe(tree()));
    }

    @Test
    void theHexIsANameAndNeverAValue() {
        bindWell(false);

        AccessibleNode node = control();
        assertNull(node.value(),
                "§7's row asks for the hex as a value text, and the builder writes a value text "
                        + "without a number into a node that publishes no facet at all: it would "
                        + "be dropped in silence on all three platforms, and there is no honest "
                        + "scalar to pair it with, because a colour has no ordering"
                        + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY),
                "and no read-only bit either, which is the facet's to derive" + describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.expand(), describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.selectionItem(), describe(tree()));
        assertNull(node.actions().keyBinding(),
                "Enter and Space are the platform's generic activation, not an accelerator");

        well.setColor(Color.rgb(0x3366CC));
        frame();

        assertEquals("#3366CC", control().name(), describe(tree()));
        assertNull(control().value(), "and it does not grow one when the colour moves");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.VALUE_CHANGED),
                "a value event off a node with no value is the silence the row would have bought: "
                        + bridge.events);
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "the colour is heard on the channel it is actually published on: " + bridge.events);
    }

    // --------------------------------------------------------------------------------- the name

    @Test
    void aCaptionDisplacesTheHexIntoTheDescriptionAndGivingItBackUndoesThat() {
        bindWell(false);
        long id = control().id();

        well.setText("Accent");
        frame();

        assertEquals("Accent", control().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, control().nameFrom(),
                "both captions are this widget's own painted text, and one provenance for both "
                        + "keeps the attribute one platform writes the name into from churning"
                        + describe(tree()));
        assertEquals(AMBER_HEX, control().description(),
                "the colour is published nowhere else in this widget's tree, so a captioned well "
                        + "whose hex went unsaid would never tell a reader the colour"
                        + describe(tree()));
        assertEquals(id, control().id(), "the node keeps its identifier" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.DESCRIPTION_CHANGED),
                bridge.events.toString());

        int published = bridge.published.size();
        bridge.events.clear();

        well.setText("Accent");
        frame();

        assertEquals(published, bridge.published.size(),
                "an equal caption re-set builds a new literal, so the reference the name is "
                        + "compared by does move; the resolved text does not, and the difference "
                        + "is taken on the text" + describe(tree()));
        assertTrue(bridge.events.isEmpty(), "and nothing was said: " + bridge.events);

        well.setTextFromColor();
        frame();

        assertEquals(AMBER_HEX, control().name(), describe(tree()));
        assertEquals("", control().description(),
                "the hex is published exactly once, wherever it lands" + describe(tree()));
        assertEquals(id, control().id(), describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.DESCRIPTION_CHANGED),
                bridge.events.toString());
    }

    @Test
    void anEmptyCaptionIsStillNamedByTheColour() {
        bindWell(false);

        well.setText("");
        frame();

        assertEquals("", well.text(), "the fixture has to actually empty the caption");
        assertEquals(AMBER_HEX, control().name(),
                "chrome and a chip is a supported configuration, the widget still paints the "
                        + "chip, and a focusable node with no name at all is the one thing the "
                        + "gallery check refuses" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, control().nameFrom(), describe(tree()));
        assertEquals("", control().description(), describe(tree()));
    }

    @Test
    void aTooltipDescribesAHexNamedWellAndIsLostToACaptionedOne() {
        bindWell(false);
        well.setTooltip("Accent colour");
        frame();

        assertEquals(AMBER_HEX, control().name(),
                "the colour is this widget's content and takes the name slot, so the walk's free "
                        + "default has the description to go to" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, control().nameFrom(), describe(tree()));
        assertEquals("Accent colour", control().description(),
                "and a reader gets both facts" + describe(tree()));

        well.setText("Accent");
        frame();

        assertEquals("Accent", control().name(), describe(tree()));
        assertEquals(AMBER_HEX, control().description(),
                "what the trade costs, said here rather than discovered: the walk gives a tooltip "
                        + "to the description only when nothing else took it, and the colour took "
                        + "it. A caption already identifies the control; the colour is published "
                        + "nowhere else. An application that disagrees calls "
                        + "setAccessibleDescription, which is applied after the hook"
                        + describe(tree()));
    }

    @Test
    void theColourMovesTheNameOnceAndASubQuantumMoveMovesNothing() {
        bindWell(false);
        long id = control().id();
        int published = bridge.published.size();

        well.setColor(Color.rgb(0xFF0000));
        frame();

        assertEquals("#FF0000", control().name(), describe(tree()));
        assertEquals(id, control().id(), describe(tree()));
        assertEquals(published + 1, bridge.published.size(),
                "one colour, one snapshot" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());

        published = bridge.published.size();
        bridge.events.clear();

        // Too small to move any of the three eight-bit channels the hex is written from.
        well.setColor(Color.rgb(0xFF0000).lerp(Color.WHITE, 0.001f));
        frame();

        assertNotEquals(Color.rgb(0xFF0000), well.color(), "the colour really did move");
        assertEquals("#FF0000", well.text(), "and the caption really did not");
        assertEquals(published, bridge.published.size(),
                "a drag step that changes no digit of the hex is a damaged frame that publishes "
                        + "nothing, which is what stops a colour drag copying the whole tree per "
                        + "frame" + describe(tree()));
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void noLanguageMoveRenamesAHexNamedWellAndACaptionedOneReResolvesOnce() {
        bindWell(false);
        long id = control().id();

        well.setLocale(BRAZILIAN);
        frame();
        limn.i18n.I18n.addBundle(TRANSLATED);
        frame();

        assertEquals(BRAZILIAN, control().locale(),
                "the node says which language it is in" + describe(tree()));
        assertEquals(AMBER_HEX, control().name(),
                "a hex passes through no bundle and no MessageFormat, and %02X is one of the "
                        + "conversions the formatter leaves unlocalized, so neither the language "
                        + "nor the translation epoch can move a digit of it" + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "and a reader is told nothing, because nothing was said differently: "
                        + bridge.events);

        bridge.events.clear();
        well.setText(CAPTION);
        frame();

        assertEquals("Destaque", control().name(),
                "a caption is an I18nString and does follow the language" + describe(tree()));
        assertEquals(id, control().id(), "a translation is not a rebuild" + describe(tree()));

        bridge.events.clear();
        well.setLocale(null);
        frame();

        assertEquals("Accent", control().name(),
                "and back again when the subtree gives its language up" + describe(tree()));
        assertEquals(AMBER_HEX, control().description(),
                "while the hex beneath it never moved" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.DESCRIPTION_CHANGED),
                bridge.events.toString());
    }

    // ------------------------------------------------------------------------------- the action

    @Test
    void aPressFromTheBridgeRaisesThePickerAndIsAcknowledgedOnce() throws Exception {
        bindWellThatCanOpen();
        long id = control().id();

        assertTrue(perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE),
                "accepted, which is not the same as done");

        assertTrue(well.isPickerOpen(),
                "the hook reaches the same openPicker() a click and an Enter release reach");
        assertNotNull(well.openDialog(), "and it is a real dialog");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "the scene acknowledges a press it performed: " + bridge.events);
        assertEquals(0, changes.get(),
                "raising the picker is not a change of colour; the application hears one when the "
                        + "picker moves and once more on the way back out: " + reported);

        settleTheDialog();

        assertEquals(ComponentStrings.COLOR_TITLE.get(), node(Accessible.Role.DIALOG).name(),
                "the dialog names itself, and this widget does not copy that title"
                        + describe(tree()));
        assertNotEquals(ComponentStrings.COLOR_TITLE.get(), byId(id).name(),
                "one fact, one home" + describe(tree()));
    }

    @Test
    void aPressWhileThePickerIsUpRaisesNoSecondDialogAndAcknowledgesNothing() throws Exception {
        bindWellThatCanOpen();
        long id = control().id();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        Dialog first = well.openDialog();
        settleTheDialog();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        tick();

        // Which of the two refusals fires is not the point, and both are correct: the scene's own
        // reachability gate refuses first, because the picker's overlay is the layer that owns
        // input and this button is behind it, and the hook's own guard stands behind that. What
        // may never happen is a second dialog over the first, each with its own idea of what the
        // colour was before, or an acknowledgement of a press that did nothing.
        assertSame(first, well.openDialog(), "a second picker was raised over the first");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "an invocation was published for a press that did nothing: " + bridge.events);
    }

    @Test
    void aDisabledWellRefusesThePressAndStaysInTheTree() throws Exception {
        bindWellThatCanOpen();
        well.setEnabled(false);
        frame();

        AccessibleNode node = control();
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the keyboard does not reach a disabled control, and the tree agrees with it"
                        + describe(tree()));
        assertEquals(Accessible.Role.BUTTON, node.role(),
                "heard as disabled rather than vanishing" + describe(tree()));
        assertTrue(node.has(Accessible.State.HAS_POPUP), describe(tree()));

        perform(node.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertFalse(well.isPickerOpen(),
                "the scene's gate walks this widget and every ancestor before the hook is called, "
                        + "which is the same gate the click path relies on: neither may be "
                        + "bypassed by a public activate() that re-derives its own guard");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());

        // The same well with its own flag true, inside a container that is not.
        bindWellThatCanOpen();
        well.parent().setEnabled(false);
        frame();

        assertTrue(well.isEnabled(), "the fixture has to leave the widget's own flag alone");
        perform(control().id(), Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertFalse(well.isPickerOpen(),
                "a control inside a disabled container is one the keyboard refuses too");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindWellThatCanOpen();
        long id = control().id();

        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.EXPAND, Accessible.Argument.NONE);
        perform(id, Accessible.Action.COLLAPSE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1));
        perform(id, Accessible.Action.SET_TEXT, new Accessible.Argument.OfText("#00FF00"));

        assertFalse(well.isPickerOpen(),
                "the hook answers false for everything but PRESS, and false runs nothing");
        assertEquals(AMBER, well.color(),
                "SET_TEXT is not offered even though the hex would parse: what advertises a "
                        + "parameterised setter is a facet, and this node publishes none");
        assertEquals(0, changes.get(), reported.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    // ------------------------------------------------------------------------------ the pointer

    @Test
    void thePressedVisualIsNeverPublishedAndAUserPressIsNotAcknowledged() {
        bindWellThatCanOpen();
        long id = control().id();

        click();

        assertTrue(well.isPickerOpen(), "the fixture has to actually click");
        assertFalse(everPublished(id, Accessible.State.PRESSED),
                "the armed visual is never published: no platform maps it, it would republish the "
                        + "tree twice per click, and a press from a reader never arms");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a press the user made leaves no difference between two snapshots, and §11 says "
                        + "so: " + bridge.events);
        assertTrue(scene.focusedWidget() instanceof ColorPicker,
                "click-to-focus is the scene's and the dialog the click raised moves the focus on "
                        + "into the picker it holds, so the well is not left holding it; neither "
                        + "half of that is this widget's doing, and the hook requests no focus "
                        + "of its own");
    }

    // ---------------------------------------------------------------------------- the relations

    @Test
    void theRelationToThePickerIsTheWalksAndThereIsExactlyOneOfIt() throws Exception {
        bindWellThatCanOpen();
        long id = control().id();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        settleTheDialog();

        AccessibleNode card = node(Accessible.Role.DIALOG);
        AccessibleNode layer = tree().node(card.parent());
        AccessibleNode button = byId(id);

        List<AccessibleRelation> controller =
                relationsOf(button, Accessible.Relation.CONTROLLER_FOR);
        assertEquals(1, controller.size(),
                "the walk publishes this off the overlay's inheritance host, which show(Widget) "
                        + "made this button, and Accessibility#relation does not de-duplicate: a "
                        + "hook that declared it too would publish two of the same relation on "
                        + "this node, which reads correctly in a picture of the tree and wrongly "
                        + "to a client that iterates" + describe(tree()));
        assertEquals(layer.id(), controller.get(0).target(),
                "and it names the layer the picker is mounted on" + describe(tree()));
        assertEquals(List.of(id),
                relationsOf(layer, Accessible.Relation.POPUP_FOR).stream()
                        .map(AccessibleRelation::target).toList(),
                "with the popup relation pointing back, once" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- what it costs

    @Test
    void aQuietColourWellAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindWell(false);
        settleTheFades();

        assertEquals(0, allocatedByADamagedFrame(),
                "describing a well that did not move must cost no memory, and the one careless "
                        + "line that would break it is a hex built inside the hook: Color#toHex "
                        + "is two format calls, the walk runs over every widget whenever anything "
                        + "at all is damaged, and this widget is damaged on every frame of a "
                        + "hover fade, a focus fade and a picker drag");

        well.setText(CAPTION);
        frame();
        settleTheFades();

        assertEquals(0, allocatedByADamagedFrame(),
                "and a captioned one, which reads its held I18nString through that string's own "
                        + "per-language memo for the name and takes the hex from the same cache "
                        + "for the description");
    }

    /**
     * @return what one damaged, unchanged frame costs with a reader attached over what the same
     *         frame costs with nobody listening, having first asserted that the frame really did
     *         publish nothing. Measured as a difference rather than against zero: a headless frame
     *         has a floor that has nothing to do with this widget.
     */
    private long allocatedByADamagedFrame() {
        int published = bridge.published.size();
        bridge.events.clear();

        bridge.listening = true;
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            well.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            well.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        bridge.listening = true;
        return withAReaderAttached - withNobodyListening;
    }
}

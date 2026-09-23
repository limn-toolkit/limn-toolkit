package limn.components;

import limn.testing.StubWindow;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.TextFacet;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.Rect;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import limn.scene.LayoutDirection;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * What a {@link TextField} becomes in the accessible tree: one editable {@code TEXT_FIELD} node
 * carrying the contents, the caret with its side, the selection and the caret's rectangle, named by
 * its placeholder, saying that it has a context menu, and owning one synthetic {@code BUTTON} when
 * a trailing button is set.
 *
 * <p>Six things ADR 039 §7's row is wrong or silent about, each pinned by a case below.
 * <b>{@code INVALID} is not "from {@code validation()}"</b>: the enum has five members and only
 * {@code ERROR} may set the bit, since {@code SUCCESS} is the state a valid field is put into and
 * announcing that as invalid inverts the fact. <b>The row is silent on the context menu</b>, which
 * this widget raises from the pointer and from the keyboard and which is the only route a reader
 * has to Cut, Copy, Paste and Select All. <b>Its note that "§8 adds an {@code I18nString} name to
 * the setter" is past tense</b> — the named overloads and a public {@code trailingButtonName()} are
 * already here. <b>The row is silent on the IME</b>, and the model's own counter is the wrong
 * witness for a composing field, which is the case that fails if anyone reaches for it. <b>It is
 * silent on where the trailing button's box comes from</b>, which is §7.2's open bounds question
 * asked here, and the box mirrors. And <b>§11's "the caret rectangle — which is free, because
 * {@code caretRect} already exists for the IME"</b> was not true of the method as it stood: it
 * produced a box, a caret position and a text measurement per call, on the one widget whose blink
 * guarantees a damaged frame twice a second.
 *
 * <p>One case here belongs to a subclass. The hook refuses to publish anything at all when
 * {@link TextField#allowClipboardCopy()} says the content may not leave the widget, and that gate
 * is the floor under a masked field: {@link PasswordField} publishes its own mask over the top of
 * it now, and what is pinned here is that a subclass which refuses the clipboard and describes
 * nothing of its own is told nothing rather than something it must not be told. What a
 * {@code PasswordField} actually becomes belongs to
 * {@link PasswordFieldAccessibilityTest}.
 *
 * <p>Every case drives the field's public API on a bound scene, or calls the scene from where a
 * bridge stands, and reads back what the scene published. Nothing constructs a node.
 */
class TextFieldAccessibilityTest extends AccessibleComponentTestBase {

    /** Exposes the protected caret rectangle, so a test can hold the IME's answer beside the tree's. */

    /** A glyph that draws nothing: enough of an icon for the trailing button to exist. */
    private static final Icon BLANK = new Icon() {
        @Override
        public Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException("never rasterized");
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark) {
            // Drawn by the frame the fixture renders, and drawing nothing is the point.
        }
    };

    private static final I18nString CLEAR = I18nString.literal("Clear");

    private TextField field;

    /** The column everything is bound in, so the field keeps its own measured box. */
    private Column root;

    /** Every record the walk logged while a test was running; see {@link #theFieldIsNeverWarnedAbout}. */
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
     * The field is focusable and paints, so a hook that stopped declaring the role would publish it
     * as {@code UNKNOWN} with a warning naming a toolkit class in an application's log, and a hook
     * that declared nothing at all would have it deleted with the other warning. Both are logged
     * once per class, so whichever case runs first is the one that would see it.
     */
    @AfterEach
    void theFieldIsNeverWarnedAbout() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
            // The parameter and not the message: the walk logs a parameterised record, so the
            // message is the unformatted pattern and the class name is the first parameter.
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.startsWith("limn.components.TextField"),
                    "a warning here names a toolkit class an application cannot correct: "
                            + subject + " " + record.getMessage());
        }
    }

    /** The padding between the column and the field, so scene and local coordinates differ. */
    private static final float INSET = 20;

    // ------------------------------------------------------------------------------ the fixture

    private void bindField() {
        bindField(new TextField());
    }

    /**
     * Binds {@code under} inside a padded column and measures it with the deterministic ruler, so
     * that the boxes in the tree are a laid-out field's and the caret's x is an exact integer.
     *
     * @param under the field under test
     */
    private void bindField(TextField under) {
        bindField(under, new StubWindow());
    }

    /**
     * @param under the field under test
     * @param over  the window to bind over; see {@link #theMenuVerbFocusesTheFieldAndRaisesIt}
     */
    private void bindField(TextField under, StubWindow over) {
        field = under;
        root = new Column();
        root.add(Padding.all(INSET, field));
        bind(root, over);
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
    }

    /** @return the field's node, the one and only text field in the tree */
    private AccessibleNode fieldNode() {
        return node(Accessible.Role.TEXT_FIELD);
    }

    /** @return the trailing button's node, the field's only child */
    private AccessibleNode trailingNode() {
        List<AccessibleNode> children = childrenOf(fieldNode());
        assertEquals(1, children.size(), "one trailing button and nothing else" + describe(tree()));
        return children.get(0);
    }


    /** Opens a composition of {@code text} with its caret at the end, as the platform does. */
    private void compose(String text) {
        drive(scene).preeditChanged(text, new int[] {text.length()}, 0, text.length());
        drive(scene).inputBatchEnded();
        frame();
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void aFreshFieldIsOneEditableTextFieldWithAMenuAndNoChildren() {
        bindField();

        AccessibleNode node = fieldNode();
        assertEquals(2, tree().nodeCount(),
                "the window and the field; the column and the padding are scaffolding"
                        + describe(tree()));
        assertEquals(0, node.parent(), describe(tree()));
        assertTrue(node.has(Accessible.State.EDITABLE),
                "always, because this class has no read-only mode" + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY), describe(tree()));
        assertFalse(node.has(Accessible.State.MULTI_LINE), describe(tree()));
        assertFalse(node.has(Accessible.State.INVALID), describe(tree()));
        assertFalse(node.has(Accessible.State.HAS_POPUP),
                "a context menu is a verb and not a state: HAS_POPUP says activating the control "
                        + "opens something, which is a combo box or a menu button and not a field "
                        + "that also has Cut and Paste on a right click" + describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "focusable from its constructor" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SHOW_MENU),
                "the Cut/Copy/Paste menu, which the row is silent about, and the only route a "
                        + "reader has to it" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS),
                "the two the walk adds for every focusable widget" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.PRESS),
                "pressing a text field is not an activation" + describe(tree()));
        assertEquals(List.of(), childrenOf(node),
                "no trailing button, so no children at all" + describe(tree()));
        assertEquals(new TextFacet("", 0, ShapedText.Affinity.DOWNSTREAM, 0, 0, 1, null),
                node.text(),
                "empty, caret at the start, nothing selected, one line, and no caret box because "
                        + "nothing is focused" + describe(tree()));
    }

    // --------------------------------------------------------------------------------- the name

    @Test
    void thePlaceholderNamesTheFieldAndEverythingElseBeatsIt() {
        bindField();

        assertEquals("", fieldNode().name(),
                "a field with no placeholder and no tooltip publishes no name at all; the "
                        + "gallery rule's future failure is a known state and not a surprise"
                        + describe(tree()));

        field.setPlaceholder("Your name");
        frame();
        AccessibleNode byPlaceholder = fieldNode();
        assertEquals("Your name", byPlaceholder.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.PLACEHOLDER, byPlaceholder.nameFrom(),
                "the field's own visible word for what goes in it" + describe(tree()));
        assertEquals("", byPlaceholder.description(), describe(tree()));

        field.setTooltip("The name on your card");
        frame();
        AccessibleNode both = fieldNode();
        assertEquals("Your name", both.name(),
                "the placeholder beats the tooltip, which leaves the tooltip somewhere useful"
                        + describe(tree()));
        assertEquals("The name on your card", both.description(), describe(tree()));

        Label caption = new Label("Full name");
        caption.setLabelFor(field);
        root.add(caption);
        frame();
        AccessibleNode byLabel = fieldNode();
        assertEquals("Full name", byLabel.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, byLabel.nameFrom(), describe(tree()));

        field.setAccessibleName("Name as printed");
        frame();
        AccessibleNode explicit = fieldNode();
        assertEquals("Name as printed", explicit.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, explicit.nameFrom(), describe(tree()));
        assertEquals(byPlaceholder.id(), explicit.id(), "a name is not a rebuild");
    }

    /**
     * A field with no placeholder has no name, and its provenance says so. The hook hands the
     * placeholder over whether or not one is set, because an unset one is the empty string and
     * names nothing; what it must not do is leave {@code PLACEHOLDER} on a node that was named
     * by nothing, which is what the kitchen sink's transcript read as {@code text field ""
     * (placeholder)} — a claim about where a name came from, on a node that has none. §1.7 says
     * the provenance decides which attribute one platform publishes the name in, and there is
     * nothing to publish.
     */
    @Test
    void anEmptyNameCarriesNoProvenance() {
        bindField();

        AccessibleNode unnamed = fieldNode();
        assertEquals("", unnamed.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, unnamed.nameFrom(),
                "no placeholder, so nothing was named by one" + describe(tree()));

        field.setPlaceholder("Your name");
        frame();
        assertEquals(Accessible.NameFrom.PLACEHOLDER, fieldNode().nameFrom(), describe(tree()));

        field.setPlaceholder("");
        frame();
        assertEquals("", fieldNode().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, fieldNode().nameFrom(),
                "cleared, the provenance goes with the name" + describe(tree()));
    }

    @Test
    void aTooltipAloneNamesTheFieldWithItsOwnProvenance() {
        bindField();

        field.setTooltip("Search the archive");
        frame();

        assertEquals("Search the archive", fieldNode().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, fieldNode().nameFrom(), describe(tree()));
        assertEquals("", fieldNode().description(),
                "the tooltip is the name here, so it is not also the description"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the states

    @Test
    void onlyErrorIsInvalidAndTheOtherFourAreNot() {
        bindField();

        for (TextField.Validation state : TextField.Validation.values()) {
            field.setValidation(state);
            frame();
            assertEquals(state == TextField.Validation.ERROR,
                    fieldNode().has(Accessible.State.INVALID),
                    state + " must " + (state == TextField.Validation.ERROR ? "" : "not ")
                            + "publish INVALID: the platform flag is a boolean, and SUCCESS is the "
                            + "state a field that just passed is put into" + describe(tree()));
        }

        field.setError(true);
        frame();
        assertTrue(fieldNode().has(Accessible.State.INVALID), describe(tree()));

        field.setError(false);
        frame();
        assertFalse(fieldNode().has(Accessible.State.INVALID), describe(tree()));
    }

    /**
     * A disabled field is an editable control that is disabled, never a read-only one, and it
     * accepts no text now.
     *
     * <p>§7's row: "never {@code READ_ONLY} from disabled", from §1.2's separation of the two bits.
     * Fix round 2d reversed it for a moment (the walk published {@code READ_ONLY} to withdraw
     * {@code SET_TEXT}); fix round 2e restored it (ADR 039 §1.2 and §1.5, amended 2026-09-15): a
     * text facet implies its setter only on an {@code ENABLED} node, so the missing bit is what
     * withdraws {@code SET_TEXT}, and the scene refuses one sent anyway.
     */
    @Test
    void aDisabledFieldIsStillEditableNeverReadOnlyAndAcceptsNoText() throws InterruptedException {
        bindField();
        field.setText("typed");
        field.setEnabled(false);
        frame();

        AccessibleNode node = fieldNode();
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the keyboard does not reach it, and the tree agrees" + describe(tree()));
        assertTrue(node.has(Accessible.State.EDITABLE),
                "a disabled field is an editable control that is disabled, which is not the same "
                        + "fact as a field whose text can never be typed into" + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY),
                "the row's own warning: never READ_ONLY from disabled" + describe(tree()));
        assertFalse(node.accepts(Accessible.Action.SET_TEXT),
                "and no SET_TEXT, because the node is not ENABLED (semantics 5)"
                        + describe(tree()));
        assertNull(node.actions(), "and no verb" + describe(tree()));
        assertEquals("typed", node.text().text(), describe(tree()));

        perform(node.id(), Accessible.Action.SET_TEXT, new Accessible.Argument.OfText("sent"));
        frame();
        assertEquals("typed", field.text(),
                "the scene refuses the SET_TEXT the node does not accept");

        field.setEnabled(true);
        frame();
        assertTrue(fieldNode().accepts(Accessible.Action.SET_TEXT),
                "enabled again, the field takes text again" + describe(tree()));
        assertFalse(fieldNode().has(Accessible.State.READ_ONLY), describe(tree()));
    }

    /**
     * The input-layer axis of the same rule: beneath an overlay of the scene the field is published
     * without {@code ENABLED}, keeps {@code EDITABLE} and its true writability, accepts no
     * {@code SET_TEXT}, and the scene refuses one sent anyway (ADR 039 §1.13, amended 2026-09-15,
     * fix round 2e).
     */
    @Test
    void aFieldBeneathAnInSceneOverlayIsNeverReadOnlyAndAcceptsNoText()
            throws InterruptedException {
        bindField();
        field.setText("typed");
        frame();
        assertTrue(fieldNode().accepts(Accessible.Action.SET_TEXT), describe(tree()));

        limn.scene.Widget<?> layer = new Column();
        scene.pushOverlay(layer);
        frame();

        AccessibleNode node = fieldNode();
        assertFalse(node.has(Accessible.State.ENABLED),
                "outside the layer that owns input" + describe(tree()));
        assertTrue(node.has(Accessible.State.EDITABLE), describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY),
                "a field under a dialog is not a read-only field" + describe(tree()));
        assertFalse(node.accepts(Accessible.Action.SET_TEXT), describe(tree()));
        assertNull(node.actions(), describe(tree()));

        perform(node.id(), Accessible.Action.SET_TEXT, new Accessible.Argument.OfText("sent"));
        frame();
        assertEquals("typed", field.text(), "the scene refuses it beneath the layer");

        scene.removeOverlay(layer);
        frame();
        assertTrue(fieldNode().accepts(Accessible.Action.SET_TEXT),
                "the layer gone, the field takes text again" + describe(tree()));
    }

    // -------------------------------------------------------------------------- the text facet

    @Test
    void setTextPublishesTheTextTheCaretAndOneChange() {
        bindField();

        field.setText("hello");
        frame();

        assertEquals(new TextFacet("hello", 5, ShapedText.Affinity.DOWNSTREAM, 5, 5, 1, null),
                fieldNode().text(),
                "the caret lands at the end, the selection collapses onto it, and a single-line "
                        + "model is one line" + describe(tree()));
        List<AccessibleEvent> changes = bridge.eventsOf(AccessibleEvent.Type.TEXT_CHANGED);
        assertEquals(1, changes.size(), bridge.events.toString());
        assertEquals(fieldNode().id(), changes.get(0).nodeId());
    }

    @Test
    void theSingleLineModelSanitizesWhatAReaderIsGivenBack() {
        bindField();

        field.setText("a\nb");
        frame();

        assertEquals("a b", fieldNode().text().text(),
                "the model turns every newline into a space, which is why §11 tells a bridge to "
                        + "re-read after a set rather than assume the round trip" + describe(tree()));
    }

    @Test
    void aSelectionIsPublishedFromTheModelAndNotFromThePaint() {
        bindField();
        field.setText("hello");
        scene.requestFocus(field);
        frame();
        bridge.events.clear();

        field.model().setCursor(1, false);
        field.model().setCursor(4, true);
        field.invalidate();
        frame();

        TextFacet facet = fieldNode().text();
        assertEquals(1, facet.selectionStart(), describe(tree()));
        assertEquals(4, facet.selectionEnd(), describe(tree()));
        assertTrue(facet.hasSelection(), describe(tree()));
        assertEquals(1, bridge.eventsOf(AccessibleEvent.Type.TEXT_SELECTION_CHANGED).size(),
                bridge.events.toString());
    }

    // ------------------------------------------------------------------------------- composing

    @Test
    void aCompositionIsPublishedSplicedInWithTheCaretInsideIt() {
        bindField();
        field.setText("ab");
        scene.requestFocus(field);
        field.model().setCursor(1, false);
        frame();
        bridge.events.clear();

        compose("konnichiwa");

        TextFacet composing = fieldNode().text();
        assertEquals("akonnichiwab", composing.text(),
                "the committed text with the preedit spliced in at the caret, which is what the "
                        + "screen shows and what the one shaping was built from" + describe(tree()));
        assertEquals(11, composing.caretOffset(),
                "inside the preedit, at its own caret" + describe(tree()));
        assertEquals(ShapedText.Affinity.UPSTREAM, composing.caretAffinity(),
                "the preedit caret trails what was just typed" + describe(tree()));
        assertFalse(composing.hasSelection(),
                "a composition paints no selection band" + describe(tree()));
        assertEquals(1, bridge.eventsOf(AccessibleEvent.Type.TEXT_CHANGED).size(),
                bridge.events.toString());

        // The case that fails outright if the witness is the model's own counter: the model does
        // not move when only the preedit does, so a second composition would publish the first
        // one for ever.
        bridge.events.clear();
        compose("konnichiwa2");
        assertEquals("akonnichiwa2b", fieldNode().text().text(),
                "a second preedit republishes; TextEditModel#textVersion() did not move for "
                        + "either of them" + describe(tree()));
        assertEquals(1, bridge.eventsOf(AccessibleEvent.Type.TEXT_CHANGED).size(),
                bridge.events.toString());

        bridge.events.clear();
        scene.requestFocus(null);
        frame();
        assertEquals("ab", fieldNode().text().text(),
                "focus loss drops the composition, and the facet is the committed text again"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------- the caret's box

    @Test
    void theCaretBoxIsTheOneTheImeIsGivenAndOnlyWhileFocused() {
        TextField exposed = new TextField();
        bindField(exposed);

        assertNull(fieldNode().text().caretRect(),
                "an unfocused field draws no caret, and the facet says so with null"
                        + describe(tree()));

        scene.requestFocus(exposed);
        frame();

        Rect published = fieldNode().text().caretRect();
        assertNotNull(published, describe(tree()));
        Rect ime = exposed.caretRect();
        assertEquals(new Rect(ime.x() - exposed.localToSceneX(), ime.y() - exposed.localToSceneY(),
                        ime.width(), ime.height()), published,
                "the reader's caret and the IME's are the same rectangle by construction: one is "
                        + "the other translated by the widget's own origin" + describe(tree()));
        assertEquals(12f, published.x(),
                "the field's own left pad at MEDIUM, with an empty line and no scroll"
                        + describe(tree()));
        assertEquals(INSET + 12f, ime.x(),
                "and the IME is given the same column in scene coordinates" + describe(tree()));
        assertEquals(1f, published.width(), "one caret stroke" + describe(tree()));

        exposed.setText("hello");
        frame();
        assertEquals(62f, fieldNode().text().caretRect().x(),
                "ten points per code point past the pad, with the caret at the end"
                        + describe(tree()));

        scene.requestFocus(null);
        frame();
        assertNull(fieldNode().text().caretRect(), describe(tree()));
    }

    // ------------------------------------------------------------------------------ what it costs

    @Test
    void aQuietFocusedFieldPublishesNothingAndAllocatesNothing() {
        bindField();
        scene.setTextRuler(new MemoizingRuler());
        field.setText("a name that is already in the field");
        scene.requestFocus(field);
        frame();

        int published = bridge.published.size();
        bridge.events.clear();
        for (int i = 0; i < 10; i++) {
            field.invalidate();
            frame();
        }
        assertEquals(published, bridge.published.size(),
                "ten damaged frames that changed no accessible fact, which is the blink"
                        + describe(tree()));
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bridge.listening = true;
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            field.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            field.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;
        bridge.listening = true;

        assertEquals(0, withAReaderAttached - withNobodyListening,
                "describing a field whose caret is blinking must cost no memory. The three lines "
                        + "that would break it are a text produced in the hook instead of taken "
                        + "from the widget's cache with its counter, a caret position minted to "
                        + "ask the shaped line for an x, and a caret rectangle built per frame "
                        + "rather than compared as four floats");
    }

    // ---------------------------------------------------------------------------- the actions

    @Test
    void setTextFromAReaderChangesTheTextAndTellsTheApplication() throws InterruptedException {
        bindField();
        List<String> changes = new ArrayList<>();
        field.onChange(changes::add);
        field.setText("before");
        frame();

        assertTrue(perform(fieldNode().id(), Accessible.Action.SET_TEXT,
                new Accessible.Argument.OfText("after")));
        frame();

        assertEquals("after", field.text(), describe(tree()));
        assertEquals(List.of("after"), changes,
                "through select-all-then-insert and never through the silent setText, which "
                        + "would change the text and tell the application nothing");
        assertEquals("after", fieldNode().text().text(), describe(tree()));
    }

    @Test
    void setTextOnADisabledFieldIsRefused() throws InterruptedException {
        bindField();
        field.setText("before");
        field.setEnabled(false);
        frame();

        perform(fieldNode().id(), Accessible.Action.SET_TEXT,
                new Accessible.Argument.OfText("after"));
        frame();

        assertEquals("before", field.text(),
                "the scene's own gate walks this field and every ancestor for enabled before the "
                        + "hook is reached, which is why the hook carries no guard of its own");
    }

    @Test
    void setCaretMovesTheCaretAndRefusesAnythingOutsideTheText() throws InterruptedException {
        bindField();
        field.setText("hello");
        scene.requestFocus(field);
        frame();
        bridge.events.clear();

        assertTrue(perform(fieldNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(2, 2)));
        frame();
        assertEquals(2, field.model().cursor(), describe(tree()));
        assertEquals(2, fieldNode().text().caretOffset(), describe(tree()));
        assertEquals(1, bridge.eventsOf(AccessibleEvent.Type.CARET_MOVED).size(),
                bridge.events.toString());

        perform(fieldNode().id(), Accessible.Action.SET_CARET, new Accessible.Argument.OfRange(9, 9));
        frame();
        assertEquals(2, field.model().cursor(),
                "an offset nothing answers to is refused, never clamped to a neighbour");

        perform(fieldNode().id(), Accessible.Action.SET_CARET, new Accessible.Argument.OfRange(1, 3));
        frame();
        assertEquals(2, field.model().cursor(),
                "a caret is a collapsed range; a real one is SET_SELECTION's verb");
    }

    /**
     * Both offset-carrying verbs are refused while an input method is composing, because the
     * offsets a client is holding are not offsets into the string this widget would place them in.
     *
     * <p>The facet publishes the composed line -- the committed text with the preedit spliced in at
     * the cursor -- and the model counts the committed buffer alone. The two agree up to the splice
     * and diverge after it, and the shorter buffer's own bounds check passes for offsets the
     * client meant differently: with "abcd", the cursor at 2 and "XY" composing, the client is
     * handed "abXYcd" and a caret asked for 4 would land after "d" rather than before "c". Since
     * the composed line is keyed on the cursor, the same call would then re-splice the preedit at
     * the new position and the text under composition would visibly jump to the end.
     */
    @Test
    void bothOffsetVerbsAreRefusedWhileAnInputMethodIsComposing() throws InterruptedException {
        bindField();
        field.setText("abcd");
        scene.requestFocus(field);
        field.model().setCursor(2, false);
        frame();
        drive(scene).preeditChanged("XY", new int[] {2}, 0, 2);
        drive(scene).inputBatchEnded();
        frame();

        assertEquals("abXYcd", fieldNode().text().text(),
                "the composed line is what a client is holding" + describe(tree()));

        // The identifier resolves, so the action is accepted and posted; what it does is the
        // widget's answer, and the widget's answer is no. Asserted as the neighbouring refusal
        // case asserts it: by the effect, because that is where a wrong one would show.
        perform(fieldNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(4, 4));
        perform(fieldNode().id(), Accessible.Action.SET_SELECTION,
                new Accessible.Argument.OfRange(0, 4));
        frame();

        assertEquals(2, field.model().cursor(),
                "nothing moved: the committed buffer's own bounds check would have passed the "
                        + "caret and placed it two characters from where it was asked for"
                        + describe(tree()));
        assertEquals(2, field.model().selectionStart(), describe(tree()));
        assertEquals(2, field.model().selectionEnd(), describe(tree()));
        assertEquals("abXYcd", fieldNode().text().text(),
                "and the text under composition did not jump" + describe(tree()));

        // Committed, the same verb is answered: the refusal is the composition's and not the
        // widget's, and it lasts exactly as long as the composition does.
        "XY".codePoints().forEach(drive(scene)::charTyped);
        drive(scene).inputBatchEnded();
        frame();
        perform(fieldNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(4, 4));
        frame();
        assertEquals(4, field.model().cursor(),
                "the refusal is the composition's and not the widget's, and it lasted exactly as "
                        + "long as the composition did" + describe(tree()));
    }

    @Test
    void setSelectionSetsBothEndsAndRefusesOneOutOfRange() throws InterruptedException {
        bindField();
        field.setText("hello");
        scene.requestFocus(field);
        frame();
        bridge.events.clear();

        assertTrue(perform(fieldNode().id(), Accessible.Action.SET_SELECTION,
                new Accessible.Argument.OfRange(1, 4)));
        frame();
        TextFacet facet = fieldNode().text();
        assertEquals(1, facet.selectionStart(), describe(tree()));
        assertEquals(4, facet.selectionEnd(), describe(tree()));
        assertEquals(1, bridge.eventsOf(AccessibleEvent.Type.TEXT_SELECTION_CHANGED).size(),
                bridge.events.toString());

        perform(fieldNode().id(), Accessible.Action.SET_SELECTION,
                new Accessible.Argument.OfRange(1, 40));
        frame();
        assertEquals(4, fieldNode().text().selectionEnd(),
                "both ends have to be inside the text, or nothing moves");
    }

    @Test
    void theMenuVerbRaisesTheMenu() throws InterruptedException {
        // A window that cannot place one of its own, which is Wayland: the toolkit's documented
        // fallback is an in-scene overlay, and it is the only presentation a headless test can
        // raise -- a native popup needs a backend, and StubWindow throws rather than pretend.
        bindField(new TextField(), new StubWindow(false));
        field.setText("hello");
        frame();
        assertEquals(List.of(), nodesWith(Accessible.State.MODAL), describe(tree()));

        assertTrue(perform(fieldNode().id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE));
        frame();

        assertEquals(1, nodesWith(Accessible.State.MODAL).size(),
                "the menu is up, as the layer that owns input, which is also why the field is no "
                        + "longer the focused widget by the time this frame is published"
                        + describe(tree()));
    }

    @Test
    void theMenuVerbFocusesTheFieldFirst() throws InterruptedException {
        // An empty field over an empty clipboard offers no usable row, so nothing opens and the
        // focus the verb took first is still on the field: the two halves of the branch, isolated.
        bindField(new TextField(), new StubWindow(false));

        assertTrue(perform(fieldNode().id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE));
        frame();

        assertEquals(List.of(), nodesWith(Accessible.State.MODAL),
                "four dead rows are not a menu" + describe(tree()));
        assertTrue(field.isFocused(),
                "focus first, exactly as the right-press branch does: the menu's Cut and Paste "
                        + "act on this field, and a field that was not focused when they run "
                        + "would edit while the caret lives elsewhere");
    }

    @Test
    void theMenuVerbIsRefusedBeforeTheFirstLayout() throws InterruptedException {
        TextField unlaid = new TextField();
        unlaid.setPreferredWidth(0);
        field = unlaid;
        root = new Column();
        root.add(unlaid);
        bind(root, new StubWindow(false));
        scene.setTextRuler(RULER);
        frame();

        assertEquals(0f, fieldNode().width(), describe(tree()));
        perform(fieldNode().id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(), nodesWith(Accessible.State.MODAL),
                "showContextMenuForFocus dereferences the caret rectangle, which is null until "
                        + "there is a box to put one in, so the verb refuses rather than throws"
                        + describe(tree()));
    }

    // --------------------------------------------------------------------- the trailing button

    @Test
    void aLeadingIconIsNeverANodeAndNeitherIsAnAbsentTrailingOne() {
        bindField();
        field.setLeadingIcon(BLANK);
        frame();

        assertEquals(List.of(), childrenOf(fieldNode()),
                "the leading icon takes an icon and a mirroring flag, has nothing to be named by "
                        + "and carries no operation, so it is decoration" + describe(tree()));
    }

    @Test
    void aNamedTrailingButtonIsOneOperableChildThatTheKeyboardCannotReach() {
        bindField();
        field.setTrailingButton(BLANK, CLEAR, () -> { });
        frame();

        AccessibleNode button = trailingNode();
        assertEquals(Accessible.Role.BUTTON, button.role(), describe(tree()));
        assertEquals("Clear", button.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, button.nameFrom(), describe(tree()));
        assertTrue(button.actions().has(Accessible.Action.PRESS), describe(tree()));
        assertFalse(button.actions().has(Accessible.Action.FOCUS),
                "Tab steps over it and only the pointer reaches it, and the tree says so rather "
                        + "than offering a tab stop that does not exist" + describe(tree()));
        assertFalse(button.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(button.has(Accessible.State.ENABLED),
                "the owner's bits, written onto everything the owner drew" + describe(tree()));
        assertTrue(button.has(Accessible.State.SHOWING), describe(tree()));
        assertNull(button.text(), "the facet is the field's, not the button's" + describe(tree()));
    }

    @Test
    void anUnnamedTrailingButtonIsPublishedUnnamedRatherThanDropped() {
        bindField();
        field.setTrailingButton(BLANK, () -> { });
        frame();

        AccessibleNode button = trailingNode();
        assertEquals(Accessible.Role.BUTTON, button.role(), describe(tree()));
        assertEquals("", button.name(),
                "an operable control is never ignored, so the omission is visible in the tree "
                        + "instead of being silent -- which is what an application that reaches "
                        + "for the two-argument overload publishes, and why SearchField calls "
                        + "the named one"
                        + describe(tree()));
        assertTrue(button.actions().has(Accessible.Action.PRESS), describe(tree()));
    }

    @Test
    void theTrailingButtonsBoxIsTheHitRegionAndItMirrors() {
        bindField();
        field.setTrailingButton(BLANK, CLEAR, () -> { });
        frame();

        AccessibleNode node = fieldNode();
        AccessibleNode button = trailingNode();
        assertEquals(node.y(), button.y(), describe(tree()));
        assertEquals(node.height(), button.height(),
                "the whole height, as the hit test and the paint both use" + describe(tree()));
        assertEquals(32f, button.width(),
                "fieldTrailing at MEDIUM, never the centred icon's smaller square"
                        + describe(tree()));
        assertEquals(node.x() + node.width() - 32f, button.x(),
                "flush with the field's trailing edge reading left to right" + describe(tree()));

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode mirrored = fieldNode();
        assertEquals(mirrored.x(), trailingNode().x(),
                "and flush with the leading edge reading right to left, which is the mirroring "
                        + "§7's row is silent about" + describe(tree()));
    }

    @Test
    void pressingTheTrailingButtonRunsItsActionOnceAndNothingLatches()
            throws InterruptedException {
        bindField();
        AtomicInteger presses = new AtomicInteger();
        field.setTrailingButton(BLANK, CLEAR, presses::incrementAndGet);
        frame();
        long button = trailingNode().id();

        assertTrue(perform(button, Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(1, presses.get());

        assertTrue(perform(button, Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(2, presses.get(),
                "twice for two presses: the pointer's armed shade is not set from here, and a "
                        + "press that latched it would leave the button drawn pressed for the "
                        + "rest of the session because only a RELEASE clears it");
        assertEquals(button, trailingNode().id(),
                "and the key is a constant, so the node a reader is holding is still that button");
    }

    @Test
    void pressingTheTrailingButtonOfADisabledFieldDoesNothing() throws InterruptedException {
        bindField();
        AtomicInteger presses = new AtomicInteger();
        field.setTrailingButton(BLANK, CLEAR, presses::incrementAndGet);
        frame();
        long button = trailingNode().id();
        field.setEnabled(false);
        frame();

        perform(button, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(0, presses.get(), "the pointer refuses it too, in the same one guard");
    }

    // ------------------------------------------- the gate under a subclass that hides its content


    @Test
    void aFieldThatRefusesTheClipboardPublishesItsTextNowhereAtAll() {
        // TextField is sealed (ADR 046 §2): the fields that refuse the clipboard are the toolkit's
        // own, and PasswordField is the one. It publishes its mask by a step of its own; what the
        // gate TextField holds for it guarantees is that the plain text never leaves the widget.
        PasswordField sealed = new PasswordField();
        bindField(sealed);
        sealed.setText("hunter2");
        scene.requestFocus(sealed);
        frame();

        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode any = tree().node(i);
            assertFalse(any.name().contains("hunter2"), describe(tree()));
            assertFalse(any.description().contains("hunter2"), describe(tree()));
            assertFalse(any.text() != null && any.text().text().contains("hunter2"),
                    "content it says may not leave the widget is published nowhere" + describe(tree()));
        }

        sealed.setAccessibleName("Sealed");
        frame();
        AccessibleNode field = node(Accessible.Role.PASSWORD_FIELD);
        assertFalse(field.text() != null && field.text().text().contains("hunter2"),
                "and it stays refused for as long as the predicate says so" + describe(tree()));
    }
}

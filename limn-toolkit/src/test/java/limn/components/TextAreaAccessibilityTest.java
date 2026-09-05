package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ScrollFacet;
import limn.accessibility.TextFacet;
import limn.graphics.Font;
import limn.graphics.Rect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
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

/**
 * What a {@link TextArea} becomes in the accessible tree: one editable, multi-line
 * {@code TEXT_AREA} node carrying the document, the caret with its side, the selection, the
 * caret's rectangle and both scroll axes, saying that it has a context menu, and growing the two
 * {@code SCROLL_BAR} children the walk finds under it only while its content overflows.
 *
 * <p><b>ADR 039 &sect;7's row for this widget is wrong in its one note and silent about six
 * things</b>, and each is pinned by a case below.
 *
 * <p><b>Wrong: "soft-wrap rows are reported as lines".</b> The line count is
 * {@link limn.components.text.TextEditModel#lineCount()} — hard lines — always, and four reasons
 * from the source make rows unpublishable. A count of visual rows is a number no client could
 * reconcile with the string handed over in the same facet, whose line structure is {@code \n} and
 * whose caret and selection offsets are indices into it. Rows are derived from the column width,
 * so a resize would publish a text change with the document untouched. Rows are broken under the
 * <em>process</em> locale, not the widget's, so the one fact in the node would be resolved under a
 * language the node does not claim. And rows move under a composition that has changed no text.
 *
 * <p><b>Silent: the context menu</b>, which this widget raises from the pointer and the keyboard
 * and which is a reader's only route to Cut, Copy, Paste and Select All. <b>Silent:
 * {@code INVALID}</b>, which is {@code ERROR} alone out of a five-member enum. <b>Silent: the
 * name</b> — there is none, because unlike a {@link TextField} this class holds no
 * {@code I18nString} at all and has no placeholder to fall back on, so a focusable node goes
 * unnamed unless the application or a tooltip names it. <b>Silent: the two scroll bars</b>, which
 * make the same widget a childless leaf or a two-child composite depending on its content.
 * <b>Silent: the IME</b>, where this widget has a trap a text field does not — its composed line
 * is only the caret's hard line, so publishing it would hand a reader one line under document
 * offsets. And <b>silent: the sticky goal x</b>, a piece of caret state no other text widget has,
 * which an action hook copied from {@code TextField} would clear nowhere.
 *
 * <p>Every case drives the area's public API on a bound scene, or calls the scene from where a
 * bridge stands, and reads back what the scene published. Nothing constructs a node.
 */
class TextAreaAccessibilityTest extends AccessibleComponentTestBase {

    /** Exposes the protected caret rectangle, so a test can hold the IME's answer beside the tree's. */
    private static final class ExposedArea extends TextArea {
        @Override
        public Rect caretRect() {
            return super.caretRect();
        }
    }

    /**
     * {@link ComponentTestBase#RULER} with the memo the shipped ruler has.
     *
     * <p>Only the allocation case installs it, for {@code TextFieldAccessibilityTest}'s reason:
     * the backend's ruler answers {@code measure} out of the memo it keeps for {@code shape}, so
     * the vertical band a caret rectangle is built from costs nothing there, while the fake is a
     * lambda that builds a {@link TextMetrics} per call. Measuring the walk against a ruler that
     * allocates by construction would be measuring the fake.
     */
    private static final class MemoizingRuler implements TextRuler {
        private String lastText;
        private Font lastFont;
        private TextMetrics last;

        @Override
        public TextMetrics measure(String text, Font font) {
            if (last == null || !text.equals(lastText) || !font.equals(lastFont)) {
                last = RULER.measure(text, font);
                lastText = text;
                lastFont = font;
            }
            return last;
        }
    }

    /** The padding between the column and the area, so scene and local coordinates differ. */
    private static final float INSET = 20;

    /** The horizontal inset the area's text column starts at, at the process default step. */
    private static final float PAD_X = SizeTokens.of(limn.scene.ControlSize.MEDIUM).fieldPadH();

    /** The vertical one, which is a different token; see {@link TextArea}'s class note. */
    private static final float PAD_Y = SizeTokens.of(limn.scene.ControlSize.MEDIUM).areaPad();

    /** Forty columns under {@link ComponentTestBase#RULER}: wider than the text column. */
    private static final String LONG_LINE = "0123456789".repeat(4);

    private TextArea area;
    private Column root;

    /** Every record the walk logged while a test was running; see {@link #theAreaIsNeverWarnedAbout}. */
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
    void resetLanguage() {
        I18n.setLocale(Locale.ENGLISH);
    }

    /**
     * The area is focusable and paints, so a hook that stopped declaring the role would publish it
     * as {@code UNKNOWN} with a warning naming a toolkit class in an application's log, and a hook
     * that declared nothing at all would have it deleted by &sect;1.6's predicate with the other
     * warning. Both are logged once per class, so whichever case runs first is the one that sees
     * it.
     */
    @AfterEach
    void theAreaIsNeverWarnedAbout() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
            // The parameter and not the message: the walk logs a parameterised record, so the
            // message is the unformatted pattern and the class name is the first parameter.
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.startsWith("limn.components.TextArea"),
                    "a warning here names a toolkit class an application cannot correct: "
                            + subject + " " + record.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    private void bindArea() {
        bindArea(new TextArea());
    }

    private void bindArea(TextArea under) {
        bindArea(under, new StubWindow());
    }

    /**
     * Binds {@code under} inside a padded column and measures it with the deterministic ruler, so
     * that the boxes in the tree are a laid-out area's and every content x is an exact integer.
     *
     * <p>The base's own {@code bind} is spelled out here rather than called, for one reason: it
     * renders its first frame before a test can install a ruler, and the process default in a
     * headless fixture measures every string as zero. An editor divides by its line height to
     * decide which rows to paint, so that first frame crashes inside {@code onPaint} and is
     * swallowed by the scene's frame guard — noise that would sit in this file's output for ever
     * and could hide a real one. The ruler goes on before the scene is bound instead.
     *
     * @param under the area under test
     * @param over  the window to bind over; a menu needs one that cannot place a popup of its own
     */
    private void bindArea(TextArea under, StubWindow over) {
        area = under;
        root = new Column();
        root.add(Padding.all(INSET, area));
        bridge = new RecordingBridge();
        window = over;
        window.accessibility = bridge;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    /** @return the area's node, the one and only text area in the tree */
    private AccessibleNode areaNode() {
        return node(Accessible.Role.TEXT_AREA);
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

    /** One press and release of {@code keyCode}, through the scene's own dispatch. */
    private void key(int keyCode) {
        scene.keyEvent(keyCode, true, false, 0);
        scene.keyEvent(keyCode, false, false, 0);
        scene.inputBatchEnded();
        frame();
    }

    /** Opens a composition of {@code text} with its caret at the end, as the platform does. */
    private void compose(String text) {
        scene.preeditChanged(text, new int[] {text.length()}, 0, text.length());
        scene.inputBatchEnded();
        frame();
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void aFreshAreaIsOneEditableMultiLineTextAreaWithAMenuAndNoChildren() {
        bindArea();

        AccessibleNode node = areaNode();
        assertEquals(2, tree().nodeCount(),
                "the window and the area; the column and the padding are scaffolding, and both "
                        + "scroll bars ignore themselves while the text fits" + describe(tree()));
        assertEquals(0, node.parent(), describe(tree()));
        assertEquals(List.of(), childrenOf(node), describe(tree()));
        assertEquals("", node.name(),
                "this class holds no I18nString of any kind and has no placeholder to fall back "
                        + "on, so an unnamed focusable node is a gap in the widget rather than in "
                        + "the tree" + describe(tree()));
        assertEquals("", node.description(),
                "setValidation holds a colour, not a string" + describe(tree()));
        assertTrue(node.has(Accessible.State.EDITABLE),
                "always, because this class has no read-only mode" + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY), describe(tree()));
        assertTrue(node.has(Accessible.State.MULTI_LINE),
                "a fact about the model this widget holds and about Enter, not about wrapping"
                        + describe(tree()));
        assertFalse(node.has(Accessible.State.INVALID), describe(tree()));
        assertTrue(node.has(Accessible.State.HAS_POPUP),
                "the Cut/Copy/Paste menu, which the row is silent about" + describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "focusable from its constructor" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SHOW_MENU), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS),
                "the two the walk adds for every focusable widget" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.PRESS),
                "pressing a text area is not an activation" + describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.INCREMENT),
                "the paging verbs belong to the ScrollBar children, which declare their own"
                        + describe(tree()));
        assertEquals(new TextFacet("", 0, ShapedText.Affinity.DOWNSTREAM, 0, 0, 1, null),
                node.text(),
                "empty, caret at the start, nothing selected, one hard line, and no caret box "
                        + "because nothing is focused" + describe(tree()));
    }

    @Test
    void aDisabledAreaIsStillEditableAndNeverReadOnly() {
        bindArea();
        area.setText("typed");
        area.setEnabled(false);
        frame();

        AccessibleNode node = areaNode();
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the keyboard does not reach it, and the tree agrees" + describe(tree()));
        assertTrue(node.has(Accessible.State.EDITABLE),
                "a disabled area is an editable control that is disabled, which is not the same "
                        + "fact as an area whose text can never be typed into" + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY), describe(tree()));
        assertEquals("typed", node.text().text(), describe(tree()));
    }

    @Test
    void onlyErrorIsInvalidAndTheOtherFourAreNot() {
        bindArea();

        for (TextField.Validation state : TextField.Validation.values()) {
            area.setValidation(state);
            frame();
            assertEquals(state == TextField.Validation.ERROR,
                    areaNode().has(Accessible.State.INVALID),
                    state + " must " + (state == TextField.Validation.ERROR ? "" : "not ")
                            + "publish INVALID: the platform flag is a boolean, and SUCCESS is the "
                            + "state an area that just passed is put into" + describe(tree()));
        }

        area.setError(true);
        frame();
        assertTrue(areaNode().has(Accessible.State.INVALID), describe(tree()));

        area.setError(false);
        frame();
        assertFalse(areaNode().has(Accessible.State.INVALID), describe(tree()));
    }

    // --------------------------------------------------------------------------- the line count

    /**
     * The survey correction, and the case whose absence would let the row back in. Nine hard lines
     * each wider than the column: wrapping doubles the rows, which the widget's own scroll
     * geometry proves, and the line count does not move by one.
     */
    @Test
    void hardLinesAreTheLineCountAndSoftWrapNeverChangesIt() {
        bindArea();
        area.setText((LONG_LINE + "\n").repeat(8) + LONG_LINE);
        frame();

        assertEquals(9, area.model().lineCount(), "nine hard lines");
        assertEquals(9, areaNode().text().lineCount(), describe(tree()));
        ScrollFacet unwrapped = areaNode().scroll();
        assertTrue(unwrapped.horizontallyScrollable(),
                "forty columns in a text column that holds twenty-nine" + describe(tree()));
        assertFalse(unwrapped.verticallyScrollable(),
                "nine rows of twelve points inside a viewport of a hundred and twenty-four"
                        + describe(tree()));

        area.setSoftWrap(true);
        frame();

        assertEquals(9, areaNode().text().lineCount(),
                "the same nine hard lines, and eighteen visual rows: a count of rows is a number "
                        + "no client could reconcile with the string in the same facet, whose "
                        + "offsets are indices into it" + describe(tree()));
        ScrollFacet wrapped = areaNode().scroll();
        assertFalse(wrapped.horizontallyScrollable(),
                "the wrap really happened: nothing overflows this axis any more"
                        + describe(tree()));
        assertTrue(wrapped.verticallyScrollable(),
                "and every line became two rows, which is how the geometry proves it"
                        + describe(tree()));

        bridge.events.clear();
        area.setPreferredSize(160, -1);
        frame();

        assertEquals(9, areaNode().text().lineCount(),
                "and halving the column re-wraps every line again without moving the count"
                        + describe(tree()));
        assertEquals(List.of(), eventsOf(AccessibleEvent.Type.TEXT_CHANGED),
                "a resize is not a text change. Rows-as-lines would publish one for every frame "
                        + "of a resize drag, with the document untouched" + bridge.events);
    }

    /**
     * The row map keys on {@code I18n.locale()} — the <em>process</em> language — and never on
     * {@link limn.scene.Widget#locale()}, so a node stamped one language would have taken its one
     * published fact from another's dictionary walk. Hard lines cannot: they move when the text
     * does and at no other time.
     */
    @Test
    void theLineCountDoesNotFollowTheProcessLocale() {
        bindArea();
        area.setSoftWrap(true);
        area.setText(LONG_LINE + "\n" + LONG_LINE);
        frame();
        assertEquals(2, areaNode().text().lineCount(), describe(tree()));
        bridge.events.clear();

        I18n.setLocale(Locale.forLanguageTag("th-TH"));
        frame();

        assertEquals(2, areaNode().text().lineCount(),
                "the break iterator the wrap uses is built from the process locale; the count a "
                        + "reader is told is not" + describe(tree()));
        assertEquals(List.of(), eventsOf(AccessibleEvent.Type.TEXT_CHANGED), bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- the text facet

    @Test
    void setTextPublishesTheDocumentWithItsNewlinesAndTheCaretAtTheEnd() {
        bindArea();

        area.setText("one\ntwo\nthree");
        frame();

        TextFacet facet = areaNode().text();
        assertEquals("one\ntwo\nthree", facet.text(), describe(tree()));
        assertEquals(13, facet.caretOffset(), "the caret lands at the end" + describe(tree()));
        assertEquals(13, facet.selectionStart(), describe(tree()));
        assertEquals(13, facet.selectionEnd(), describe(tree()));
        assertEquals(3, facet.lineCount(), describe(tree()));
        List<AccessibleEvent> changes = eventsOf(AccessibleEvent.Type.TEXT_CHANGED);
        assertEquals(1, changes.size(), bridge.events.toString());
        assertEquals(areaNode().id(), changes.get(0).nodeId());
    }

    /**
     * The contrast case with {@code TextFieldAccessibilityTest}'s own, and what stops ADR 039
     * &sect;11's "the model sanitizes a single-line value, every newline becomes a space" being
     * applied here: that sentence is about a text <em>field</em>'s model. This one is
     * {@code new TextEditModel(false)}, whose sanitize keeps every newline, so a whole-value set
     * round-trips.
     */
    @Test
    void theMultiLineModelKeepsTheNewlineThatASingleLineModelWouldLose()
            throws InterruptedException {
        bindArea();

        assertTrue(perform(areaNode().id(), Accessible.Action.SET_TEXT,
                new Accessible.Argument.OfText("a\nb")));
        frame();

        assertEquals("a\nb", area.text(), describe(tree()));
        assertEquals("a\nb", areaNode().text().text(),
                "a bridge told the single-line rule and applying it here would be re-reading for "
                        + "nothing" + describe(tree()));
        assertEquals(2, areaNode().text().lineCount(), describe(tree()));
    }

    @Test
    void aSelectionIsPublishedFromTheModelAndNotFromThePaint() {
        bindArea();
        area.setText("hello\nworld");
        frame();
        bridge.events.clear();

        assertFalse(area.isFocused(), "nothing is focused, so onPaint bands nothing");
        area.model().selectAll();
        area.invalidate();
        frame();

        TextFacet facet = areaNode().text();
        assertEquals(0, facet.selectionStart(), describe(tree()));
        assertEquals(11, facet.selectionEnd(), describe(tree()));
        assertTrue(facet.hasSelection(),
                "every operation on a selection reads the model, so the facet does too"
                        + describe(tree()));
        assertEquals(1, eventsOf(AccessibleEvent.Type.TEXT_SELECTION_CHANGED).size(),
                bridge.events.toString());
    }

    /**
     * The TextArea-specific IME trap. {@link TextField} publishes its composed line whole because
     * that line <em>is</em> its whole document; this widget's composed line is the caret's hard
     * line alone, so the analogue would hand a reader "cxyd" under offsets counted through
     * "ab\ncxyd\nef".
     */
    @Test
    void aCompositionIsSplicedIntoTheDocumentWithTheCaretInsideItAndTheLineCountUnmoved() {
        bindArea();
        area.setText("ab\ncd\nef");
        scene.requestFocus(area);
        area.model().setCursor(4, false); // between c and d, on the middle line
        frame();
        bridge.events.clear();

        compose("xy");

        TextFacet composing = areaNode().text();
        assertEquals("ab\ncxyd\nef", composing.text(),
                "the whole buffer with the preedit spliced in at the caret, the caret's line "
                        + "taken from the very shaping the pixels came from" + describe(tree()));
        assertEquals(6, composing.caretOffset(),
                "inside the preedit, at its own caret, counted through the document"
                        + describe(tree()));
        assertEquals(ShapedText.Affinity.UPSTREAM, composing.caretAffinity(),
                "the preedit caret trails what was just typed" + describe(tree()));
        assertFalse(composing.hasSelection(),
                "a composition paints no selection band" + describe(tree()));
        assertEquals(3, composing.lineCount(),
                "a composition adds no hard line" + describe(tree()));
        assertEquals(1, eventsOf(AccessibleEvent.Type.TEXT_CHANGED).size(),
                bridge.events.toString());

        // The case that fails outright if the witness is the model's own counter: the model does
        // not move when only the preedit does, so a second composition would publish the first
        // one for ever.
        bridge.events.clear();
        compose("xyz");
        assertEquals("ab\ncxyzd\nef", areaNode().text().text(),
                "a second preedit republishes; TextEditModel#textVersion() did not move for "
                        + "either of them" + describe(tree()));
        assertEquals(1, eventsOf(AccessibleEvent.Type.TEXT_CHANGED).size(),
                bridge.events.toString());

        bridge.events.clear();
        scene.requestFocus(null);
        frame();
        assertEquals("ab\ncd\nef", areaNode().text().text(),
                "focus loss drops the composition, and the facet is the committed text again"
                        + describe(tree()));
    }

    /**
     * {@link limn.components.text.TextEditModel#setText} bumps the model's counter for a value
     * equal to the one already there, so a revision bumped per rebuild rather than per change
     * would publish a whole snapshot while reporting no event.
     */
    @Test
    void theTextCounterMovesOnlyWhenTheStringDoes() {
        bindArea();
        area.setText("same");
        frame();
        int published = bridge.published.size();
        bridge.events.clear();

        area.setText("same");
        frame();

        assertEquals(published, bridge.published.size(),
                "nothing about this node moved, so nothing is published" + describe(tree()));
        assertEquals(List.of(), bridge.events, "and nothing is reported");
    }

    // -------------------------------------------------------------------------- the caret's box

    @Test
    void theCaretBoxIsTheOneTheImeIsGivenAndOnlyWhileFocused() {
        ExposedArea exposed = new ExposedArea();
        bindArea(exposed);
        exposed.setText(LONG_LINE + "\n" + LONG_LINE);
        frame();

        assertNull(areaNode().text().caretRect(),
                "an unfocused area draws no caret, and the facet says so with null"
                        + describe(tree()));

        scene.requestFocus(exposed);
        frame();

        assertSameRectangle(exposed);
        assertEquals(PAD_Y + 12f, areaNode().text().caretRect().y(),
                "the caret is on the second line, one line height down from the top pad"
                        + describe(tree()));
        assertEquals(12f, areaNode().text().caretRect().height(), describe(tree()));
        assertEquals(1f, areaNode().text().caretRect().width(),
                "one caret stroke" + describe(tree()));

        exposed.scrollBy(40, 0);
        frame();
        assertSameRectangle(exposed);

        exposed.setSoftWrap(true);
        frame();
        assertSameRectangle(exposed);

        scene.requestFocus(null);
        frame();
        assertNull(areaNode().text().caretRect(), describe(tree()));
    }

    /**
     * The invariant the caret seam exists to pin: the reader's caret and the IME's are the same
     * rectangle by construction, one being the other translated by the widget's own origin.
     */
    private void assertSameRectangle(ExposedArea exposed) {
        Rect published = areaNode().text().caretRect();
        assertNotNull(published, describe(tree()));
        Rect ime = exposed.caretRect();
        assertEquals(new Rect(ime.x() - exposed.localToSceneX(), ime.y() - exposed.localToSceneY(),
                        ime.width(), ime.height()), published,
                "a second caret expression would drift from the one the candidate window is "
                        + "placed under" + describe(tree()));
    }

    @Test
    void theCaretBoxIsClampedIntoTheColumnWhenTheCaretIsScrolledAway() {
        ExposedArea exposed = new ExposedArea();
        bindArea(exposed);
        exposed.setText((LONG_LINE + "\n").repeat(19) + LONG_LINE);
        scene.requestFocus(exposed);
        exposed.model().setCursor(0, false);
        frame();

        exposed.scrollBy(60, 90);
        frame();

        Rect box = areaNode().text().caretRect();
        assertEquals(PAD_X, box.x(),
                "clamped to the column's left edge, per axis, exactly as caretRect() clamps"
                        + describe(tree()));
        assertEquals(PAD_Y, box.y(), describe(tree()));
        assertSameRectangle(exposed);
    }

    // ------------------------------------------------------------------------------ what it costs

    /**
     * The case the whole allocation section exists for, in both wrap modes: the wrapped path adds
     * the row map and the row window to what the describe hook reaches.
     */
    @Test
    void aQuietFocusedAreaPublishesNothingAndAllocatesNothingUnwrapped() {
        assertQuietAndFree(false);
    }

    @Test
    void aQuietFocusedAreaPublishesNothingAndAllocatesNothingWrapped() {
        assertQuietAndFree(true);
    }

    private void assertQuietAndFree(boolean wrap) {
        bindArea();
        scene.setTextRuler(new MemoizingRuler());
        area.setSoftWrap(wrap);
        area.setText((LONG_LINE + "\n").repeat(9) + LONG_LINE);
        scene.requestFocus(area);
        frame();
        frame();

        int published = bridge.published.size();
        bridge.events.clear();
        for (int i = 0; i < 10; i++) {
            area.invalidate();
            frame();
        }
        assertEquals(published, bridge.published.size(),
                "ten damaged frames that changed no accessible fact, which is the blink"
                        + describe(tree()));
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bridge.listening = true;
        long withAReaderAttached = AllocationProbe.leastAllocatedBy(() -> {
            area.invalidate();
            frame();
        }, 60);
        bridge.listening = false;
        long withNobodyListening = AllocationProbe.leastAllocatedBy(() -> {
            area.invalidate();
            frame();
        }, 60);
        bridge.listening = true;

        assertEquals(0, withAReaderAttached - withNobodyListening,
                "describing an area whose caret is blinking must cost no memory. The three lines "
                        + "that would break it are a text produced in the hook instead of taken "
                        + "from the widget's cache with its counter, a caret position minted to "
                        + "ask the shaped row for an x, and a caret rectangle built per frame "
                        + "rather than compared as four floats");
    }

    // ---------------------------------------------------------------------------- the scroll facet

    @Test
    void anAreaWhoseTextFitsIsNotScrollableAndItsViewSizesAreExactlyOne() {
        bindArea();

        ScrollFacet empty = areaNode().scroll();
        assertNotNull(empty, describe(tree()));
        assertEquals(new ScrollFacet(0, 0, 1, 1, false, false), empty,
                "an axis that does not scroll reports zero and one, which is what every platform "
                        + "reads as all of it, nowhere to go. The guards are also what keeps a "
                        + "NaN -- which differs from itself and would copy the whole tree on "
                        + "every damaged frame -- out of an area described before its first "
                        + "layout" + describe(tree()));

        area.setText("two\nlines");
        frame();
        assertEquals(new ScrollFacet(0, 0, 1, 1, false, false), areaNode().scroll(),
                "and a short document still shows all of it" + describe(tree()));
    }

    @Test
    void scrollingMovesTheFacetAndNothingElse() {
        bindArea();
        area.setText((LONG_LINE + "\n").repeat(19) + LONG_LINE);
        frame();
        TextFacet before = areaNode().text();
        assertEquals(0d, areaNode().scroll().verticalPercent(), describe(tree()));
        bridge.events.clear();

        area.scrollBy(0, 12);
        frame();

        ScrollFacet scrolled = areaNode().scroll();
        assertTrue(scrolled.verticalPercent() > 0, describe(tree()));
        assertTrue(scrolled.verticallyScrollable(), describe(tree()));
        assertEquals(before, areaNode().text(),
                "the document did not move, so neither did the facet that carries it"
                        + describe(tree()));
        assertEquals(List.of(), eventsOf(AccessibleEvent.Type.TEXT_CHANGED), bridge.events.toString());
        assertEquals(List.of(), eventsOf(AccessibleEvent.Type.CARET_MOVED), bridge.events.toString());
    }

    @Test
    void softWrapPinsTheHorizontalAxisQuiet() {
        bindArea();
        area.setText(LONG_LINE);
        frame();

        assertTrue(areaNode().scroll().horizontallyScrollable(), describe(tree()));

        area.setSoftWrap(true);
        frame();

        ScrollFacet wrapped = areaNode().scroll();
        assertFalse(wrapped.horizontallyScrollable(),
                "wrapped, the content is exactly the column by construction" + describe(tree()));
        assertEquals(1d, wrapped.horizontalViewSize(), describe(tree()));
        assertEquals(0d, wrapped.horizontalPercent(), describe(tree()));
    }

    @Test
    void theScrollOffsetIsPublishedUnflippedRightToLeft() {
        bindArea();
        area.setText(LONG_LINE);
        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(0d, areaNode().scroll().horizontalPercent(),
                "zero is the leading edge in both directions, which is the right edge here"
                        + describe(tree()));
        assertTrue(areaNode().scroll().horizontallyScrollable(), describe(tree()));

        area.scrollBy(52, 0);
        frame();

        assertEquals(52f, area.scrollXOffset(), describe(tree()));
        assertEquals(0.5d, areaNode().scroll().horizontalPercent(), 1e-6,
                "scrollX is already a distance travelled from the leading edge rather than a "
                        + "coordinate, so the facet publishes it unflipped" + describe(tree()));
    }

    // ----------------------------------------------------------------------------------- the bars

    @Test
    void theBarsBecomeChildrenOnlyWhenTheContentOverflows() throws InterruptedException {
        bindArea();
        area.setText("short");
        frame();
        assertEquals(List.of(), childrenOf(areaNode()),
                "each bar ignores itself while its axis fits, so the same widget is a childless "
                        + "leaf here and a composite below" + describe(tree()));

        area.setText((LONG_LINE + "\n").repeat(19) + LONG_LINE);
        area.setSoftWrap(true); // quiets the horizontal axis, leaving exactly one bar
        frame();

        List<AccessibleNode> children = childrenOf(areaNode());
        assertEquals(1, children.size(), describe(tree()));
        AccessibleNode bar = children.get(0);
        assertEquals(Accessible.Role.SCROLL_BAR, bar.role(), describe(tree()));
        assertTrue(bar.has(Accessible.State.VERTICAL), describe(tree()));
        assertTrue(bar.actions().has(Accessible.Action.INCREMENT), describe(tree()));

        assertTrue(perform(bar.id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE));
        frame();

        assertTrue(area.scrollYOffset() > 0,
                "the child is dispatchable and not decoration: the verb reaches the bar's own "
                        + "model, which is this area's scroll offset" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the actions

    @Test
    void theMenuVerbFocusesTheAreaFirstAndRaisesTheMenuAtTheCaret() throws InterruptedException {
        // A window that cannot place a popup of its own, which is Wayland: the toolkit's
        // documented fallback is an in-scene overlay, and it is the only presentation a headless
        // test can raise -- a native popup needs a backend, and StubWindow throws rather than
        // pretend.
        bindArea(new TextArea(), new StubWindow(false));
        area.setText("hello\nworld");
        frame();
        assertEquals(List.of(), nodesWith(Accessible.State.MODAL), describe(tree()));

        assertTrue(perform(areaNode().id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE));
        frame();

        assertEquals(1, nodesWith(Accessible.State.MODAL).size(),
                "the menu is up, as the layer that owns input, which is also why the area is no "
                        + "longer the focused widget by the time this frame is published"
                        + describe(tree()));
    }

    @Test
    void theMenuVerbFocusesTheAreaEvenWhenNoRowIsUsable() throws InterruptedException {
        // An empty area over an empty clipboard offers no usable row, so nothing opens and the
        // focus the verb took first is still on the area: the two halves of the branch, isolated.
        bindArea(new TextArea(), new StubWindow(false));

        assertTrue(perform(areaNode().id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE));
        frame();

        assertEquals(List.of(), nodesWith(Accessible.State.MODAL),
                "four dead rows are not a menu" + describe(tree()));
        assertTrue(area.isFocused(),
                "focus first, exactly as the right-press branch does: the menu's Cut and Paste "
                        + "act on this area, and one that was not focused when they run would "
                        + "edit while the caret lives elsewhere");
    }

    /**
     * The menu verb opens with the same {@code width() <= 0} guard {@link TextField}'s does,
     * because {@code showContextMenuForFocus} dereferences a {@code caretRect()} that is null
     * until there is a box to put a caret in.
     *
     * <p>This case was written the other way round first, pinning a defect instead of the guard:
     * {@code onLayout} handed both bars a length with no floor, so an area laid out narrower than
     * a scroll bar threw out of the layout pass and the frame published nothing at all &mdash;
     * the guard was unreachable from a tree, and the case asserted the empty tree so nobody would
     * strike the guard out as dead code. The floor landed, and this is the case it was standing
     * in for.
     */
    @Test
    void theMenuVerbIsRefusedBeforeTheFirstLayout() throws InterruptedException {
        TextArea unlaid = new TextArea();
        unlaid.setPreferredSize(0, 140);
        area = unlaid;
        root = new Column();
        root.add(unlaid);
        bridge = new RecordingBridge();
        window = new StubWindow(false);
        window.accessibility = bridge;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.bind(window);

        frame();

        assertEquals(0f, areaNode().width(),
                "the area is published, at no width at all" + describe(tree()));
        perform(areaNode().id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(), nodesWith(Accessible.State.MODAL),
                "showContextMenuForFocus dereferences the caret rectangle, which is null until "
                        + "there is a box to put one in, so the verb refuses rather than throws"
                        + describe(tree()));
    }

    @Test
    void setTextFromAReaderChangesTheTextAndTellsTheApplication() throws InterruptedException {
        bindArea();
        List<String> changes = new ArrayList<>();
        area.onChange(changes::add);
        area.setText("before");
        frame();

        assertTrue(perform(areaNode().id(), Accessible.Action.SET_TEXT,
                new Accessible.Argument.OfText("after\nand after")));
        frame();

        assertEquals("after\nand after", area.text(), describe(tree()));
        assertEquals(List.of("after\nand after"), changes,
                "through select-all-then-insert and never through the silent setText, which "
                        + "would change the text and tell the application nothing");
        assertEquals("after\nand after", areaNode().text().text(), describe(tree()));
    }

    @Test
    void setTextOnADisabledAreaIsRefused() throws InterruptedException {
        bindArea();
        area.setText("before");
        area.setEnabled(false);
        frame();

        perform(areaNode().id(), Accessible.Action.SET_TEXT,
                new Accessible.Argument.OfText("after"));
        frame();

        assertEquals("before", area.text(),
                "the scene's own gate walks this area and every ancestor for enabled before the "
                        + "hook is reached, which is why the hook carries no guard of its own");
    }

    @Test
    void setCaretMovesTheCaretAndRefusesAnythingOutsideTheText() throws InterruptedException {
        bindArea();
        area.setText("ab\ncd");
        scene.requestFocus(area);
        frame();
        bridge.events.clear();

        assertTrue(perform(areaNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(2, 2)));
        frame();
        assertEquals(2, area.model().cursor(),
                "an offset exactly on a newline is a legal caret position in a multi-line buffer"
                        + describe(tree()));
        assertEquals(2, areaNode().text().caretOffset(), describe(tree()));
        assertEquals(1, eventsOf(AccessibleEvent.Type.CARET_MOVED).size(),
                bridge.events.toString());

        perform(areaNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(9, 9));
        frame();
        assertEquals(2, area.model().cursor(),
                "an offset nothing answers to is refused, never clamped to a neighbour");

        perform(areaNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(1, 3));
        frame();
        assertEquals(2, area.model().cursor(),
                "a caret is a collapsed range; a real one is SET_SELECTION's verb");
    }

    @Test
    void setSelectionSetsBothEndsAndRefusesOneOutOfRange() throws InterruptedException {
        bindArea();
        area.setText("hello\nworld");
        scene.requestFocus(area);
        frame();
        bridge.events.clear();

        assertTrue(perform(areaNode().id(), Accessible.Action.SET_SELECTION,
                new Accessible.Argument.OfRange(1, 8)));
        frame();
        TextFacet facet = areaNode().text();
        assertEquals(1, facet.selectionStart(), describe(tree()));
        assertEquals(8, facet.selectionEnd(), describe(tree()));
        assertEquals(1, eventsOf(AccessibleEvent.Type.TEXT_SELECTION_CHANGED).size(),
                bridge.events.toString());

        perform(areaNode().id(), Accessible.Action.SET_SELECTION,
                new Accessible.Argument.OfRange(1, 40));
        frame();
        assertEquals(8, areaNode().text().selectionEnd(),
                "both ends have to be inside the text, or nothing moves");
    }

    /**
     * Both offset-carrying verbs are refused while an input method is composing, because the
     * offsets a client is holding are not offsets into the string this widget would place them in:
     * the facet publishes the composed document and the model counts the committed buffer alone.
     */
    @Test
    void bothOffsetVerbsAreRefusedWhileAnInputMethodIsComposing() throws InterruptedException {
        bindArea();
        area.setText("ab\ncd");
        scene.requestFocus(area);
        area.model().setCursor(4, false);
        frame();
        compose("XY");

        assertEquals("ab\ncXYd", areaNode().text().text(),
                "the composed document is what a client is holding" + describe(tree()));

        // The identifier resolves, so the action is accepted and posted; what it does is the
        // widget's answer, and the widget's answer is no. Asserted by the effect, because that is
        // where a wrong one would show.
        perform(areaNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(6, 6));
        perform(areaNode().id(), Accessible.Action.SET_SELECTION,
                new Accessible.Argument.OfRange(0, 6));
        frame();

        assertEquals(4, area.model().cursor(),
                "nothing moved: the committed buffer's own bounds check would have passed the "
                        + "caret and placed it two characters from where it was asked for"
                        + describe(tree()));
        assertEquals(4, area.model().selectionStart(), describe(tree()));
        assertEquals(4, area.model().selectionEnd(), describe(tree()));
        assertEquals("ab\ncXYd", areaNode().text().text(),
                "and the text under composition did not jump" + describe(tree()));

        "XY".codePoints().forEach(scene::charTyped);
        scene.inputBatchEnded();
        frame();
        perform(areaNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(6, 6));
        frame();
        assertEquals(6, area.model().cursor(),
                "the refusal is the composition's and not the widget's, and it lasted exactly as "
                        + "long as the composition did" + describe(tree()));
    }

    /**
     * The TextArea-only invariant, and the one a case copied from {@code TextField} could not
     * have: that widget has no sticky goal x to clear.
     *
     * <p>{@link TextArea#setSoftWrap Wrapped}, Up and Down travel on a goal <em>x</em> taken from
     * the caret the first press of a run sees, and every one of the widget's own non-vertical
     * paths clears it — the click, the drag, an edit, and every key that is not a vertical step. A
     * caret placed by an assistive technology is exactly a click; left unset, the next Down after
     * one travels to the column the user's last arrow run was on, and the caret visibly jumps
     * sideways.
     */
    @Test
    void aCaretPlacedByAReaderClearsTheStickyGoalX() throws InterruptedException {
        bindArea();
        area.setSoftWrap(true);
        // Three rows whose widths differ sharply: two of twenty-five columns with a short one
        // between them, so a goal x taken on the first is far outside the second.
        area.setText("0123456789012345678901234\nabc\n0123456789012345678901234");
        scene.requestFocus(area);
        area.model().setCursor(20, false); // x = 200, twenty columns in
        frame();

        key(Keys.DOWN);
        assertEquals(29, area.model().cursor(),
                "the run's goal x is 200, which is past the end of \"abc\", so Down lands there");

        assertTrue(perform(areaNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(27, 27)));
        frame();
        assertEquals(27, area.model().cursor(), describe(tree()));

        key(Keys.DOWN);

        assertEquals(31, area.model().cursor(),
                "Down after a reader's caret placement travels from the NEW column, one in. "
                        + "Leaving the sticky goal x set would land it at 50 -- the column the "
                        + "user's own arrow run was on -- and under soft wrap that is a caret "
                        + "that jumps sideways for no reason the user can see" + describe(tree()));
    }

    /**
     * A standing scroll offset is re-clamped when the content shrinks under it, so the published
     * percentage stays inside the nought-to-one the facet promises.
     *
     * <p>{@code clampScroll} is reached from a wheel, a drag, a reveal and the two bar models, and
     * from none of them when a LAYOUT shrinks the extent. Turning soft wrap off on an area scrolled
     * to the bottom is the shipped case — the demo's own switch does exactly this — and it left an
     * offset larger than the new maximum: the facet published a vertical percentage above one, to a
     * client whose platform specifies nought to a hundred, and the scroll bar painted its thumb
     * past the end of its own track.
     */
    @Test
    void anOffsetLeftOverFromATallerDocumentIsClampedRatherThanPublishedPastTheEnd() {
        TextArea wrapped = new TextArea();
        StringBuilder document = new StringBuilder();
        for (int line = 0; line < 12; line++) {
            document.append("a line long enough to wrap several times when the column is narrow ")
                    .append(line).append('\n');
        }
        wrapped.setText(document.toString());
        wrapped.setSoftWrap(true);
        bindArea(wrapped);
        area.scrollBy(0, 100000);
        frame();

        ScrollFacet before = areaNode().scroll();
        assertTrue(before.verticalPercent() <= 1.0 + 1e-6,
                "the fixture must start inside the range: " + before + describe(tree()));

        area.setSoftWrap(false);
        frame();

        ScrollFacet after = areaNode().scroll();
        assertTrue(after.verticalPercent() >= 0 && after.verticalPercent() <= 1.0 + 1e-6,
                "unwrapped the document is shorter, so the offset it was holding is past the new "
                        + "end; the facet documents nought to one and a bridge hands it to a "
                        + "pattern specified nought to a hundred: " + after + describe(tree()));
    }
}

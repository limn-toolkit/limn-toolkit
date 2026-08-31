package limn.components;

import limn.backend.Clipboard;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TextField editing driven through the Scene with a mock clipboard. */
class TextFieldTest extends ComponentTestBase {

    /**
     * HEBREW LETTER ALEF, BET, GIMEL. As escapes, and in one constant, so no source line here
     * mixes directions and reorders under an editor's own bidi.
     */
    private static final String HEBREW = "\u05D0\u05D1\u05D2";

    /**
     * The bidi fixture, and every expected number below is stated against it. Base direction is
     * LTR (the first strong character is Latin) and {@link #RULER} makes every cluster 10pt, so
     * the line lays out as
     *
     * <pre>
     * visual:     a[0,10)  b[10,20)  c[20,30) | gimel[30,40)  bet[40,50)  alef[50,60)
     * charIndex:     0        1         2     |     5             4           3
     * </pre>
     *
     * It needs no native, no font file and no GPU: the degraded shaping path measures one grapheme
     * cluster at a time and reorders through {@code java.text.Bidi}, which is exactly why the bidi
     * caret is testable at all — logical order in, expected visual positions out, and not a
     * screenshot anywhere.
     */
    private static final String LATIN_THEN_HEBREW = "abc" + HEBREW;

    /** Left inset of the text run at MEDIUM: every expected x below is measured from it. */
    private static final float PAD = SizeTokens.of(ControlSize.MEDIUM).fieldPadH();

    static final class MockClipboard implements Clipboard {
        String value = "";

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String text) {
            value = text;
        }
    }

    private TextField field;
    private Scene scene;
    private MockClipboard clipboard;

    private void build() {
        field = new TextField();
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        clipboard = new MockClipboard();
        scene.setClipboard(clipboard);
        scene.layoutPass(240, 32);
        scene.requestFocus(field);
    }

    private void type(String text) {
        text.codePoints().forEach(scene::charTyped);
        scene.inputBatchEnded();
    }

    private void key(int keyCode, int mods) {
        scene.keyEvent(keyCode, true, false, mods);
        scene.keyEvent(keyCode, false, false, mods);
        scene.inputBatchEnded();
    }

    @Test
    void windowCloseClearsFocus() {
        // A widget left "focused" in a dead scene re-arms its blink chain on the
        // global UI queue forever; window close must run the focus-lost path.
        build();
        assertTrue(field.isFocused());
        scene.windowClosed();
        assertNull(scene.focusedWidget());
        assertFalse(field.isFocused());
    }

    @Test
    void maskedPasswordFieldDegradesWordJumpsToCharacterMoves() {
        // Word-wise jumps would let an observer count the words and lengths
        // inside the masked secret.
        PasswordField password = new PasswordField();
        Scene pwScene = new Scene(password);
        pwScene.setTextRuler(RULER);
        pwScene.setClipboard(new MockClipboard());
        pwScene.layoutPass(240, 32);
        pwScene.requestFocus(password);
        "top secret".codePoints().forEach(pwScene::charTyped);
        pwScene.inputBatchEnded();

        pwScene.keyEvent(Keys.LEFT, true, false, Keys.MOD_CONTROL);
        pwScene.keyEvent(Keys.LEFT, false, false, Keys.MOD_CONTROL);
        pwScene.inputBatchEnded();
        assertEquals(9, password.model().cursor(), "one character, not one word");

        password.setRevealed(true);
        pwScene.keyEvent(Keys.LEFT, true, false, Keys.MOD_CONTROL);
        pwScene.keyEvent(Keys.LEFT, false, false, Keys.MOD_CONTROL);
        pwScene.inputBatchEnded();
        assertEquals(4, password.model().cursor(), "revealed: word jumps come back");
    }

    @Test
    void altGrDoesNotTriggerCtrlShortcuts() {
        // Windows reports AltGr as Ctrl+Alt: AltGr+A must type 'ą', not
        // select-all (which the arriving char would then replace wholesale).
        build();
        type("abc");
        key(Keys.A, Keys.MOD_CONTROL | Keys.MOD_ALT);
        type("ą");
        assertEquals("abcą", field.text(), "AltGr combo must not run the Ctrl shortcut");
        key(Keys.A, Keys.MOD_CONTROL); // plain Ctrl+A still selects all
        type("X");
        assertEquals("X", field.text());
    }

    @Test
    void typingInsertsAtTheCursor() {
        build();
        type("abc");
        assertEquals("abc", field.text());
        key(Keys.LEFT, 0);
        type("X");
        assertEquals("abXc", field.text());
    }

    @Test
    void shiftArrowsSelectAndTypingReplaces() {
        build();
        type("hello");
        key(Keys.LEFT, Keys.MOD_SHIFT);
        key(Keys.LEFT, Keys.MOD_SHIFT);
        assertEquals("lo", field.model().selectedText());
        type("!");
        assertEquals("hel!", field.text());
    }

    @Test
    void homeEndJumpToTheEdges() {
        build();
        type("abcdef");
        key(Keys.HOME, 0);
        assertEquals(0, field.model().cursor());
        key(Keys.END, 0);
        assertEquals(6, field.model().cursor());
        key(Keys.HOME, Keys.MOD_SHIFT);
        assertEquals("abcdef", field.model().selectedText());
    }

    @Test
    void ctrlOrAltArrowsMoveByWord() {
        build();
        type("hello world");
        key(Keys.HOME, 0);
        key(Keys.RIGHT, Keys.MOD_CONTROL); // Windows/Linux word-right
        assertEquals(5, field.model().cursor(), "end of hello");
        key(Keys.RIGHT, Keys.MOD_ALT);     // macOS Option word-right
        assertEquals(11, field.model().cursor(), "end of world");
        key(Keys.LEFT, Keys.MOD_CONTROL);
        assertEquals(6, field.model().cursor(), "start of world");
    }

    @Test
    void ctrlBackspaceDeletesTheWord() {
        build();
        type("hello world");
        key(Keys.BACKSPACE, Keys.MOD_CONTROL);
        assertEquals("hello ", field.text());
    }

    @Test
    void clipboardCopyCutPasteThroughTheMock() {
        build();
        type("limn");
        key(Keys.A, Keys.MOD_CONTROL);
        key(Keys.C, Keys.MOD_CONTROL);
        assertEquals("limn", clipboard.value);

        key(Keys.END, 0);
        key(Keys.V, Keys.MOD_SUPER); // Cmd works too
        assertEquals("limnlimn", field.text());

        key(Keys.A, Keys.MOD_CONTROL);
        key(Keys.X, Keys.MOD_CONTROL);
        assertEquals("limnlimn", clipboard.value);
        assertEquals("", field.text());
    }

    @Test
    void mousePressPositionsTheCursorAndDragSelects() {
        build();
        type("0123456789");
        // fieldPadH at MEDIUM is 12; glyphs are 10pt wide → x=12+35 lands at index 3..4 boundary.
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 12 + 35, 16);
        scene.inputBatchEnded();
        int pressIndex = field.model().cursor();
        assertTrue(pressIndex == 3 || pressIndex == 4, "got " + pressIndex);

        scene.mouseMoved(12 + 75, 16); // drag to ~7.5 glyphs
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 12 + 75, 16);
        scene.inputBatchEnded();
        assertTrue(field.model().hasSelection());
        assertFalse(field.model().selectedText().isEmpty());
    }

    @Test
    void horizontalScrollFollowsTheCursor() {
        build();
        // 40 glyphs = 400pt in a 240pt field (inner ~216) → must scroll.
        type("0123456789".repeat(4));
        assertTrue(field.model().cursor() == 40);
        // cursor at the end: prefix width 400 > inner width → scrolled.
        // (validated indirectly: typing kept the cursor visible without exception
        // and END/HOME swing the scroll both ways)
        key(Keys.HOME, 0);
        key(Keys.END, 0);
        assertEquals(40, field.model().cursor());
    }

    @Test
    void changeListenerFiresOnEveryMutation() {
        build();
        int[] count = {0};
        field.onChange(text -> count[0]++);
        type("ab");           // 2
        key(Keys.BACKSPACE, 0); // 3
        key(Keys.A, Keys.MOD_CONTROL); // selection does not change the text
        key(Keys.X, Keys.MOD_CONTROL); // 4
        assertEquals(4, count[0]);
    }

    @Test
    void noopEditsAndEmptyPasteDoNotFireChangeNorClobberSelection() {
        // Regression (code review): backspace on an empty field / delete at end,
        // and pasting an empty clipboard, must be pure no-ops.
        build();
        int[] count = {0};
        field.onChange(text -> count[0]++);
        key(Keys.BACKSPACE, 0);  // empty: nothing deleted
        key(Keys.DELETE, 0);     // empty: nothing deleted
        assertEquals(0, count[0], "no-op edits must not fire onChange");

        type("abc");             // 3 changes
        field.model().selectAll();
        clipboard.value = "";
        key(Keys.V, Keys.MOD_CONTROL); // paste empty
        assertEquals("abc", field.text(), "empty paste must not delete the selection");
        assertTrue(field.model().hasSelection());
        assertEquals(3, count[0]);
    }

    @Test
    void undoShortcutRecoversSelectAllOverwrite() {
        // The classic disaster: Ctrl+A then one keystroke replaces everything.
        build();
        type("important text");
        key(Keys.A, Keys.MOD_CONTROL);
        type("x");
        assertEquals("x", field.text());

        key(Keys.Z, Keys.MOD_CONTROL);
        assertEquals("important text", field.text(), "Ctrl+Z brings the content back");

        key(Keys.Z, Keys.MOD_CONTROL | Keys.MOD_SHIFT);
        assertEquals("x", field.text(), "Shift+Ctrl+Z redoes");

        key(Keys.Z, Keys.MOD_CONTROL);
        key(Keys.Y, Keys.MOD_CONTROL);
        assertEquals("x", field.text(), "Ctrl+Y also redoes");
    }

    @Test
    void undoFiresChangeAndCoalescesTypingRuns() {
        build();
        type("abc"); // one coalesced run
        int[] count = {0};
        field.onChange(text -> count[0]++);
        key(Keys.Z, Keys.MOD_CONTROL);
        assertEquals("", field.text(), "one undo reverts the whole typed run");
        assertEquals(1, count[0], "undo fires onChange once");
    }

    // ------------------------------------------------------------ control sizes

    @Test
    void everyStepMeasuresItsControlHeightAndFieldWidth() {
        // Under SCALED_RULER, not RULER: RULER returns lineHeight 12 whatever the font, so it
        // would report 26 at every step and pin numbers that never render.
        TextField sized = new TextField();
        Scene sizedScene = new Scene(sized);
        sizedScene.setTextRuler(SCALED_RULER);

        float[] widths = {172, 204, 240, 300, 360};
        float[] heights = {24, 28, 32, 40, 50};
        ControlSize[] steps = ControlSize.values();
        for (int i = 0; i < steps.length; i++) {
            sized.setControlSize(steps[i]);
            Size size = sized.measure(Constraints.loose(10_000, 10_000));
            assertEquals(widths[i], size.width(), 0.001f, steps[i] + " width");
            // The floor binds at all five steps, so the height is the ramp exactly, MEDIUM
            // included, where it is 32 rather than the 32.40625 the font term used to produce.
            assertEquals(heights[i], size.height(), 0.001f, steps[i] + " height");
        }
    }

    /** Records the one text baseline and the one caret column a focused, unselected field paints. */
    private static final class AnchorCanvas extends FakeCanvas {
        float baselineY = Float.NaN;
        float caretX = Float.NaN;
        float caretTop = Float.NaN;
        float caretBottom = Float.NaN;

        AnchorCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            baselineY = y;
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
            caretX = x1;
            caretTop = y1;
            caretBottom = y2;
        }
    }

    @Test
    void theCaretAndTheBaselineShareOneInkAnchorAtEveryStep() {
        // The blocking defect this conversion fixes: the baseline was centred in the box while
        // the caret, the selection band and the preedit underline were placed from padV(). The
        // two agreed only where the font term happened to fill the box exactly, and elsewhere
        // the locked 1pt INK_BLEED silently became anything from 0.03 to 1.97.
        for (ControlSize step : ControlSize.values()) {
            TextField sized = new TextField();
            sized.setControlSize(step);
            Scene sizedScene = new Scene(sized);
            sizedScene.setTextRuler(SCALED_RULER);
            sizedScene.layoutPass(240, 64); // taller than every step: the box never matches the ink
            sizedScene.requestFocus(sized);
            sized.setText("Hg");

            AnchorCanvas canvas = new AnchorCanvas(240, 64);
            sizedScene.renderFrame(canvas);

            TextMetrics m = SCALED_RULER.measure("Hg", SizeTokens.of(step).body());
            float inkTop = (64 - m.height()) / 2;
            assertEquals(inkTop + m.ascent(), canvas.baselineY, 0.001f, step + " baseline");
            assertEquals(inkTop - Strokes.INK_BLEED, canvas.caretTop, 0.001f, step + " caret top");
            assertEquals(inkTop + m.height() + Strokes.INK_BLEED, canvas.caretBottom, 0.001f,
                    step + " caret bottom");
            // The identity that must survive every future edit: one anchor, two consumers.
            assertEquals(canvas.baselineY - m.ascent() - Strokes.INK_BLEED, canvas.caretTop,
                    0.001f, step + " caret and baseline must derive from the same anchor");
        }
    }

    /**
     * Tab into a field takes its contents; a click does not. Both halves matter: the first is the
     * Windows/GTK/macOS convention that lets a form be retyped without a Ctrl+A per field, and the
     * second is why it cannot simply be done on every focus gain: a click already chose a caret,
     * and selecting everything would throw that choice away.
     */
    @Test
    void tabbingIntoAFieldTakesItsContentsAndClickingDoesNot() {
        TextField first = new TextField();
        TextField second = new TextField();
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(first);
        column.add(second);
        Scene formScene = new Scene(column);
        formScene.setTextRuler(RULER);
        formScene.layoutPass(200, 100);
        second.setText("replace me");

        formScene.requestFocus(first);
        assertFalse(second.model().hasSelection(), "nothing has arrived at the second field yet");

        formScene.keyEvent(Keys.TAB, true, false, 0);
        formScene.keyEvent(Keys.TAB, false, false, 0);
        formScene.inputBatchEnded();

        assertTrue(second.isFocused());
        assertEquals("replace me", second.model().selectedText(),
                "Tab must take the whole contents");

        // Now leave and come back by clicking: the caret the click placed must survive.
        formScene.requestFocus(first);
        formScene.mouseMoved(5, second.y() + 5);
        formScene.mouseButton(Keys.MOUSE_LEFT, true, 0, 5, second.y() + 5);
        formScene.mouseButton(Keys.MOUSE_LEFT, false, 0, 5, second.y() + 5);
        formScene.inputBatchEnded();

        assertTrue(second.isFocused());
        assertFalse(second.model().hasSelection(),
                "a click chose a caret; selecting everything would discard that choice");
    }

    /** Focus moved from code is not traversal either: a dialog must not eat a caret. */
    @Test
    void programmaticFocusDoesNotSelectTheContents() {
        TextField field = new TextField();
        Scene fieldScene = new Scene(field);
        fieldScene.setTextRuler(RULER);
        fieldScene.layoutPass(200, 40);
        field.setText("keep me");

        fieldScene.requestFocus(field);
        assertFalse(field.model().hasSelection());
    }

    // ------------------------------------------------------------- bidi geometry

    private static final ShapedText.Affinity UP = ShapedText.Affinity.UPSTREAM;
    private static final ShapedText.Affinity DOWN = ShapedText.Affinity.DOWNSTREAM;

    private static ShapedText.Position caret(int index, ShapedText.Affinity side) {
        return new ShapedText.Position(index, side);
    }

    /** Presses and releases at a display-x measured from the left edge of the text run. */
    private void click(float displayX) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, PAD + displayX, 16);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, PAD + displayX, 16);
        scene.inputBatchEnded();
    }

    /** The x of the one vertical line a focused, unselected field draws: its caret. */
    private float paintedCaretX() {
        AnchorCanvas canvas = new AnchorCanvas(240, 32);
        scene.renderFrame(canvas);
        return canvas.caretX;
    }

    @Test
    void twoClicksTwoPixelsApartNameTwoInsertionPointsAtTheSameCaretX() {
        // x=29 falls in the trailing half of 'c' -- which reads left to right, so its trailing
        // half is its RIGHT half. x=31 falls in the trailing half of gimel, which reads right to
        // left, so its trailing half is its LEFT half. Both caret positions draw at x=30, and a
        // binary search over caret x values structurally cannot tell them apart: that is why hit
        // testing resolves through the cluster under the point and reports the side it chose.
        build();
        field.setText(LATIN_THEN_HEBREW);

        click(29);
        assertEquals(caret(3, UP), field.model().caret(), "the Latin side of the boundary");
        assertEquals(PAD + 30, paintedCaretX(), 0.001f, "the caret draws where the click landed");

        click(31);
        assertEquals(caret(6, UP), field.model().caret(), "the right-to-left side of it");
        assertEquals(PAD + 30, paintedCaretX(), 0.001f, "the same pixel, a different insertion point");
    }

    @Test
    void oneIndexIsTwoPointsAndTheStoredSideDecidesWhichIsDrawn() {
        // Index 3 sits between 'c' and alef, which are drawn 30 points apart. A caret stored as a
        // bare integer has to guess between them, and whichever it guesses is wrong half the time.
        build();
        field.setText(LATIN_THEN_HEBREW);

        field.model().setCursor(3, false); // programmatic placement leaves DOWNSTREAM
        assertEquals(PAD + 60, paintedCaretX(), 0.001f,
                "DOWNSTREAM is the leading edge of alef, which is drawn at the far RIGHT of the run");
        field.model().setCaret(caret(3, UP), false);
        assertEquals(PAD + 30, paintedCaretX(), 0.001f,
                "UPSTREAM is the trailing edge of 'c', 30 points away, for the same index");
    }

    @Test
    void theLeftArrowIsVisualAndTwoPressesInARowDoNotJumpTheLine() {
        build();
        field.setText(LATIN_THEN_HEBREW);
        field.model().setCursor(5, false); // inside the right-to-left run
        assertEquals(PAD + 40, paintedCaretX(), 0.001f, "between bet and gimel");

        key(Keys.LEFT, 0);
        assertEquals(caret(6, UP), field.model().caret());
        assertEquals(PAD + 30, paintedCaretX(), 0.001f, "one cluster left ON THE SCREEN");

        key(Keys.LEFT, 0);
        assertEquals(caret(2, DOWN), field.model().caret());
        assertEquals(PAD + 20, paintedCaretX(), 0.001f,
                "and left again. Handed the bare index 6 a step could only read that index's "
                        + "strong x, 60, and would have walked left, jumped to the far right of "
                        + "the line, then walked left again");

        key(Keys.RIGHT, 0);
        assertEquals(PAD + 30, paintedCaretX(), 0.001f);
        assertEquals(caret(3, UP), field.model().caret(),
                "x=30 again, and the OTHER of the two indices that share it: arriving from the "
                        + "left lands on the Latin side of the boundary");
        key(Keys.RIGHT, 0);
        assertEquals(caret(5, DOWN), field.model().caret());
        assertEquals(PAD + 40, paintedCaretX(), 0.001f, "the x sequence 20, 30, 40 round-trips");
    }

    @Test
    void aClickPastTheEndOfTheLineMeansTheLogicalEndAndNotTheNearestCluster() {
        // The cluster nearest the right edge of this line is alef, which is the FIRST character of
        // the right-to-left run, not the last character of the line. Clamping the click to it --
        // which is what a drag past the end of the line wants -- would put the caret at index 3.
        build();
        field.setText(LATIN_THEN_HEBREW);
        click(100); // well past the 60pt line
        assertEquals(caret(6, UP), field.model().caret());
    }

    /** Records the selection bands: the only {@code fillRect} a resting field paints. */
    private static final class BandCanvas extends FakeCanvas {
        final List<float[]> bands = new ArrayList<>();

        BandCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            bands.add(new float[]{x, w});
        }
    }

    @Test
    void aSelectionCrossingADirectionBoundaryPaintsTwoBandsWithUnselectedTextBetween() {
        // Chars 2..5 are 'c', alef and bet. On the line 'c' sits at [20,30) and alef and bet
        // together at [40,60) -- with gimel, which is NOT selected, drawn at [30,40) BETWEEN them.
        // One rectangle cannot express that, and the smallest one containing both halves would
        // paint over a character the user did not select.
        build();
        field.setText(LATIN_THEN_HEBREW);
        field.model().setCursor(2, false);
        field.model().setCursor(5, true);

        BandCanvas canvas = new BandCanvas(240, 32);
        scene.renderFrame(canvas);
        assertEquals(2, canvas.bands.size(), "two boxes, never one");
        assertEquals(PAD + 20, canvas.bands.get(0)[0], 0.001f, "first box starts at 'c'");
        assertEquals(10, canvas.bands.get(0)[1], 0.001f, "and is one cluster wide");
        assertEquals(PAD + 40, canvas.bands.get(1)[0], 0.001f, "second box starts at bet");
        assertEquals(20, canvas.bands.get(1)[1], 0.001f, "and covers bet and alef, merged");
    }

    // ------------------------------------------------------- shaping, held and once

    /** A ruler that records every line it is asked to <em>shape</em>. */
    private static final class ShapeRecorder implements TextRuler {
        final List<String> shaped = new ArrayList<>();

        @Override
        public TextMetrics measure(String text, Font font) {
            return RULER.measure(text, font);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            shaped.add(text);
            return TextRuler.super.shape(text, font, base);
        }

        long count(String line) {
            return shaped.stream().filter(line::equals).count();
        }
    }

    private ShapeRecorder buildRecording() {
        ShapeRecorder ruler = new ShapeRecorder();
        field = new TextField();
        scene = new Scene(field);
        scene.setTextRuler(ruler);
        scene.setClipboard(new MockClipboard());
        scene.layoutPass(240, 32);
        scene.requestFocus(field);
        return ruler;
    }

    @Test
    void theDisplayLineIsHeldAndReshapedOnlyWhenAnInputChanged() {
        // Shaping is the expensive half of drawing text, so the value is held and refreshed
        // against the model's text version, the font and the ruler's epoch -- never re-derived
        // per paint, and never for a keystroke that changed no text.
        ShapeRecorder ruler = buildRecording();
        field.setText("abc");
        scene.renderFrame(new FakeCanvas(240, 32));
        scene.renderFrame(new FakeCanvas(240, 32));
        assertEquals(1, ruler.count("abc"), "two paints, one shaping");

        key(Keys.LEFT, 0); // the caret steps back between 'b' and 'c'
        scene.renderFrame(new FakeCanvas(240, 32));
        assertEquals(1, ruler.count("abc"), "a caret move is not a text change");

        type("d");
        scene.renderFrame(new FakeCanvas(240, 32));
        assertEquals("abdc", field.text());
        assertEquals(1, ruler.count("abdc"), "an edit is, exactly once");
    }

    @Test
    void theComposedLineIsShapedWholeAndItsThreePiecesNeverSeparately() {
        // Under a shaper the committed prefix, the preedit and the committed suffix join across
        // the two seams the splice cuts -- Arabic and Indic do -- so measuring the three apart is
        // three wrong numbers that also disagree with what is drawn.
        ShapeRecorder ruler = buildRecording();
        field.setText("ab");
        field.model().setCursor(1, false);
        scene.preeditChanged("XY", new int[]{2}, 0, 2);
        scene.inputBatchEnded();
        scene.renderFrame(new FakeCanvas(240, 32));

        assertEquals(1, ruler.count("aXYb"), "the composed line, shaped whole and held");
        assertEquals(0, ruler.count("XY"), "the preedit is never shaped alone");
        assertEquals(0, ruler.count("a"), "nor the committed prefix");
        assertEquals(0, ruler.count("b"), "nor the committed suffix");
    }

    @Test
    void thePreeditUnderlineAndCaretComeFromTheComposedLine() {
        // The underline is the boxes `selection` gives for the preedit's own range of the composed
        // line, and the caret is that line's caretX: one shaping answers all three questions, so
        // the mark, the highlight and the caret cannot drift apart.
        build();
        field.setText("ab");
        field.model().setCursor(1, false);
        scene.preeditChanged("XY", new int[]{2}, 0, 2);
        scene.inputBatchEnded();

        AnchorCanvas canvas = new AnchorCanvas(240, 32);
        scene.renderFrame(canvas);
        // "aXYb" at 10pt a cluster: the preedit occupies [10,30) and its caret trails it, at 30.
        assertEquals(PAD + 30, canvas.caretX, 0.001f, "the caret trails the text just typed");
        // The field is the scene root at the origin, so scene x is local x. caretRect is what the
        // blink damages, and it has to name the column that was actually painted.
        assertEquals(PAD + 30, field.caretRect().x(), 0.001f,
                "and caretRect reports the same column, so the blink repaints the right one");
    }
}

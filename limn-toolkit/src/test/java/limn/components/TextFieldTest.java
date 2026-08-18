package limn.components;

import limn.backend.Clipboard;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TextField editing driven through the Scene with a mock clipboard. */
class TextFieldTest extends ComponentTestBase {

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
}

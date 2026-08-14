package limn.scene;

import limn.graphics.Rect;
import limn.scene.event.PreeditEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scene enables the platform IME only while a text-editing widget is
 * focused, forwards preedit to that widget, and pushes its caret rect to the
 * window. Driven headlessly through {@link RecordingWindow}.
 */
class ScenePreeditTest extends SceneTestBase {

    /** Focusable text input that records the last preedit it received. */
    static final class TextInput extends Widget {
        PreeditEvent lastPreedit;

        TextInput() {
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(100, 20);
        }

        @Override
        protected boolean acceptsTextInput() {
            return true;
        }

        @Override
        protected Rect caretRect() {
            return new Rect(localToSceneX() + 5, localToSceneY() + 2, 1, 16);
        }

        @Override
        protected void onPreedit(PreeditEvent event) {
            lastPreedit = event;
            event.consume();
        }
    }

    /** Focusable non-text widget (IME must be off while it holds focus). */
    static final class Button extends Widget {
        Button() {
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(100, 20);
        }
    }

    private RecordingWindow win;
    private Scene scene;
    private TextInput text;
    private Button button;

    private void build() {
        Widget root = new Widget() {
            @Override
            protected Size onMeasure(Constraints c) {
                return c.constrain(100, 40);
            }

            @Override
            protected void onLayout() {
                for (Widget child : children()) {
                    child.layoutBox(0, child == button ? 20 : 0, 100, 20);
                }
            }
        };
        text = new TextInput();
        button = new Button();
        root.add(text);
        root.add(button);
        win = new RecordingWindow();
        scene = new Scene(root);
        scene.bind(win);
        scene.layoutPass(100, 40);
    }

    @Test
    void focusingTextInputEnablesImeAndPushesCaret() {
        build();
        scene.requestFocus(text);
        assertTrue(win.imeEnabled, "IME on while a text widget is focused");
        assertEquals(new Rect(5, 2, 1, 16), win.lastCaretRect, "caret rect pushed on focus");
    }

    @Test
    void focusingNonTextWidgetDisablesIme() {
        build();
        scene.requestFocus(text);
        scene.requestFocus(button);
        assertFalse(win.imeEnabled, "IME off while a non-text widget is focused");
    }

    @Test
    void blurDisablesIme() {
        build();
        scene.requestFocus(text);
        scene.requestFocus(null);
        assertFalse(win.imeEnabled, "IME off with nothing focused");
    }

    @Test
    void preeditGoesToTheFocusedTextWidget() {
        build();
        scene.requestFocus(text);
        scene.preeditChanged("あ", new int[]{1}, 0, 1);
        scene.inputBatchEnded();
        assertEquals("あ", text.lastPreedit.text());
        assertEquals(1, text.lastPreedit.caret());
    }

    @Test
    void preeditWithNothingFocusedIsDropped() {
        build();
        text.lastPreedit = null;
        scene.preeditChanged("あ", new int[]{1}, 0, 1);
        scene.inputBatchEnded();
        assertNull(text.lastPreedit, "no focus → composition has nowhere to go");
    }

    @Test
    void focusLeavingATextWidgetCancelsTheOsComposition() {
        build();
        scene.requestFocus(text);
        scene.preeditChanged("にほん", new int[]{3}, 0, 3);
        scene.inputBatchEnded();
        scene.requestFocus(button);
        assertEquals(1, win.preeditResets,
                "composition owned by the old text widget must be cancelled on focus change");
        scene.requestFocus(text);
        assertEquals(1, win.preeditResets,
                "leaving a non-text widget has no composition to cancel");
        scene.requestFocus(null);
        assertEquals(2, win.preeditResets, "blur away from a text widget also cancels");
    }
}

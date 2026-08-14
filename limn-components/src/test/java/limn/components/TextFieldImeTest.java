package limn.components;

import limn.scene.Scene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IME composition (preedit) routing for {@link TextField}: the still-composing
 * text is shown but never enters the model until it is committed as ordinary
 * character input.
 */
class TextFieldImeTest extends ComponentTestBase {

    private TextField field;
    private Scene scene;

    @BeforeEach
    void setUp() {
        field = new TextField();
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(240, 32);
        scene.requestFocus(field);
    }

    private void preedit(String text, int[] blocks, int focusedBlock, int caret) {
        scene.preeditChanged(text, blocks, focusedBlock, caret);
        scene.inputBatchEnded();
    }

    private void commit(String text) {
        text.codePoints().forEach(scene::charTyped);
        scene.inputBatchEnded();
    }

    @Test
    void editableFieldAcceptsTextInput() {
        // The scene uses this to decide whether to enable the platform IME.
        assertTrue(field.acceptsTextInput());
    }

    @Test
    void passwordFieldRefusesComposition() {
        // Secure entry: the IME stays off, and even a preedit that arrives
        // anyway is dropped by the scene: the secret is never echoed.
        PasswordField password = new PasswordField();
        Scene passwordScene = new Scene(password);
        passwordScene.setTextRuler(RULER);
        passwordScene.layoutPass(240, 32);
        passwordScene.requestFocus(password);
        assertFalse(password.acceptsTextInput());

        passwordScene.preeditChanged("秘密", new int[]{2}, 0, 2);
        passwordScene.inputBatchEnded();
        assertEquals("", password.composingText(), "composition must never reach a password field");
        assertEquals("", password.text());
    }

    @Test
    void preeditIsShownButNotCommitted() {
        preedit("に", new int[]{1}, 0, 1);
        assertEquals("に", field.composingText());
        assertEquals("", field.text(), "composition must not enter the model");
    }

    @Test
    void preeditReplacesRatherThanAppends() {
        preedit("n", new int[]{1}, 0, 1);
        preedit("に", new int[]{1}, 0, 1);
        assertEquals("に", field.composingText());
        assertEquals("", field.text());
    }

    @Test
    void emptyPreeditClearsComposition() {
        preedit("に", new int[]{1}, 0, 1);
        preedit("", new int[]{}, -1, 0);
        assertEquals("", field.composingText());
        assertEquals("", field.text());
    }

    @Test
    void commitInsertsAndComposingClears() {
        // Compose, then commit: the IME sends the committed chars (char callback)
        // and a following empty preedit to clear the composition.
        preedit("にほn", new int[]{3}, 0, 3);
        commit("日本");
        preedit("", new int[]{}, -1, 0);
        assertEquals("日本", field.text());
        assertEquals("", field.composingText());
    }

    @Test
    void commitLandsAtTheCaretInExistingText() {
        field.setText("[]");
        field.model().setCursor(1, false); // between the brackets
        preedit("あ", new int[]{1}, 0, 1);
        commit("亜");
        preedit("", new int[]{}, -1, 0);
        assertEquals("[亜]", field.text());
    }

    @Test
    void losingFocusDropsComposition() {
        preedit("に", new int[]{1}, 0, 1);
        scene.requestFocus(null);
        assertEquals("", field.composingText());
        assertFalse(field.isFocused());
    }
}

package limn.components;

import limn.components.internal.text.TextEditModel;
import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A double click selects the word under the pointer and a triple click the line, in a text field
 * and a text area, and a drag after either extends by whole words or lines: what every platform's
 * text control does, and what Limn's did not before 2026-09-30 (a click only placed the caret).
 */
class MultiClickSelectionTest extends ComponentTestBase {

    @Test
    void theModelSelectsTheRunUnderTheIndex() {
        TextEditModel model = new TextEditModel(true);
        model.insert("São Paulo, 2026");
        model.selectWordAt(1);
        assertEquals("São", model.selectedText(), "letters with their accents");
        model.selectWordAt(9);
        assertEquals(",", model.selectedText(), "punctuation is a run of its own");
        model.selectWordAt(model.length());
        assertEquals("2026", model.selectedText(), "at the end, the word before it");
        model.selectWordAt(3);
        assertEquals(" ", model.selectedText(), "between words, the spaces");
        model.selectLineAt(5);
        assertEquals("São Paulo, 2026", model.selectedText());
    }

    @Test
    void aDragAfterADoubleClickExtendsByWholeWords() {
        TextEditModel model = new TextEditModel(true);
        model.insert("one two three four");
        model.selectWordAt(5);
        assertEquals("two", model.selectedText());
        assertTrue(model.extendUnitTo(10));
        assertEquals("two three", model.selectedText(), "forward, to the end of the word reached");
        assertTrue(model.extendUnitTo(1));
        assertEquals("one two", model.selectedText(), "backward, keeping the first word whole");
        model.setCursor(0, false);
        assertFalse(model.extendUnitTo(5), "a single click leaves the drag to the caret");
    }

    @Test
    void aTextFieldTakesTheWordOnADoubleClickAndAllOnATripleClick() {
        TextField field = new TextField();
        field.setText("Padaria São João");
        Scene scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 32);
        float x = field.width() * 0.4f;
        float y = 16;
        click(scene, x, y, 1);
        click(scene, x, y, 2);
        String word = field.model().selectedText();
        assertTrue(word.equals("Padaria") || word.equals("São") || word.equals("João")
                || word.equals(" "), "a whole run, got [" + word + "]");
        click(scene, x, y, 3);
        assertEquals("Padaria São João", field.model().selectedText());
    }

    private static void click(Scene scene, float x, float y, int count) {
        drive(scene).mouseMoved(x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y, count);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y, count);
        drive(scene).inputBatchEnded();
    }
}

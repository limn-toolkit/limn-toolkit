package limn.components;

import limn.accessibility.Accessible;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.input.Keys;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link TextField} with a mask (ADR 067 section 5): typing writes the literals, pasting with or
 * without them gives the same text, Backspace steps over a literal to the digit before it, one undo
 * takes an edit and its literals back together, the guide shows only while the field has the
 * keyboard, and a reader hears the pattern.
 */
class TextFieldMaskTest extends AccessibleComponentTestBase {

    private static final int COMMAND = Accelerator.commandModifier();

    private TextField field;
    private final List<String> heard = new ArrayList<>();

    private void bindField(String pattern) {
        field = new TextField().setPlaceholder("Document").setMask(pattern);
        field.onChange(heard::add);
        Column root = new Column();
        root.add(field);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
    }

    private void type(String text) {
        drive(scene).type(text);
        drive(scene).inputBatchEnded();
    }

    @Test
    void typingWritesTheLiteralsAndTheApplicationReadsBothForms() {
        bindField("000.000.000-00");
        field.requestFocus();
        type("123456");
        assertEquals("123.456", field.text());
        assertEquals("123456", field.unmaskedText());
        assertFalse(field.isMaskComplete());
        type("78900");
        assertEquals("123.456.789-00", field.text());
        assertTrue(field.isMaskComplete());
        type("5");
        assertEquals("123.456.789-00", field.text(), "nothing past the last position");
        assertEquals("123.456.789-00", heard.get(heard.size() - 1));
        assertEquals(field.text().length(), field.caretPosition());
    }

    @Test
    void lettersInADigitPositionAreNotTaken() {
        bindField("000-000");
        field.requestFocus();
        type("1a2b3c4");
        assertEquals("123-4", field.text());
    }

    @Test
    void codeSetsTheValueWithOrWithoutLiterals() {
        bindField("000.000.000-00");
        field.setText("12345678900");
        assertEquals("123.456.789-00", field.text());
        field.setText("123.456.789-00");
        assertEquals("12345678900", field.unmaskedText());
        assertTrue(heard.isEmpty(), "a caller's write reaches no handler");
    }

    @Test
    void textAlreadyThereIsFittedWhenTheMaskArrives() {
        TextField plain = new TextField().setText("12345");
        plain.setMask("00-000");
        assertEquals("12-345", plain.text());
        plain.setMask(null);
        assertNull(plain.mask());
        assertEquals("12-345", plain.unmaskedText(), "without a mask, the text");
        assertThrows(IllegalArgumentException.class, () -> plain.setMask("--"));
        assertThrows(IllegalArgumentException.class, () -> plain.setMask("'A-000"),
                "an unterminated quote");
        assertEquals("12-345", plain.text(), "a refused pattern leaves the field as it was");
    }

    @Test
    void aQuotedSlotCharacterIsALiteralTheFieldWrites() {
        bindField("'A'-000");
        field.requestFocus();
        type("123");
        assertEquals("A-123", field.text());
        assertEquals("123", field.unmaskedText());
        assertTrue(field.isMaskComplete());
        assertEquals("'A'-000", field.mask(), "the pattern as it was given");
    }

    @Test
    void backspaceAfterALiteralTakesTheDigitBeforeIt() {
        bindField("000.000");
        field.requestFocus();
        type("1234");
        field.setCaretPosition(4); // after the dot, before the 4
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("124", field.text(), "the 3 went, not the dot");
        assertEquals(2, field.caretPosition(), "where the 3 was");
    }

    @Test
    void backspaceAtTheEndShortensWithoutLeavingALiteral() {
        bindField("000.000");
        field.requestFocus();
        type("1234");
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("123", field.text());
    }

    @Test
    void aPatternThatEndsInALiteralShowsItOnceTheLastPositionIsFilled() {
        bindField("(00)");
        field.requestFocus();
        type("12");
        assertEquals("(12)", field.text(), "the text as shown is the pattern's");
        assertTrue(field.isMaskComplete());
        assertEquals("12", field.unmaskedText());
        assertEquals(4, field.caretPosition());
        scene.requestFocus(null);
        assertEquals("(12)", field.text(), "the same without the keyboard");
        field.requestFocus();
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("(1", field.text(), "Backspace at the end steps over the closing literal");
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("(12)", field.text(), "one undo puts the digit and its literal back");

        TextField weight = new TextField().setMask("00 kg");
        weight.setText("12");
        assertEquals("12 kg", weight.text(), "from code too");
    }

    @Test
    void deleteBeforeALiteralTakesTheDigitAfterIt() {
        bindField("000.000");
        field.requestFocus();
        type("123456");
        field.setCaretPosition(3); // before the dot
        drive(scene).press(Keys.DELETE);
        assertEquals("123.56", field.text(), "the 4 went, not the dot");
        assertEquals(3, field.caretPosition());
    }

    @Test
    void pastingOverASelectionThatHoldsALiteralKeepsEveryDigit() {
        bindField("000.000");
        scene.setClipboard(TextFieldTest.MockClipboard.holding("34"));
        field.requestFocus();
        type("123456");
        field.select(2, 5); // "3.4"
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("123.456", field.text(), "the 3 and the 4 came back, and the 5 stayed");
    }

    @Test
    void typingOverASelectionThatHoldsALiteralKeepsEveryDigit() {
        // The path an input method's commit takes too: characters that replace a selection.
        bindField("000.000");
        field.requestFocus();
        type("123456");
        field.select(3, 5); // ".4"
        type("4");
        assertEquals("123.456", field.text());
        assertEquals(5, field.caretPosition(), "after the 4 typed");
    }

    @Test
    void removingASelectedLiteralAloneTakesNoDigit() {
        bindField("000.000");
        scene.setClipboard(new TextFieldTest.MockClipboard());
        field.requestFocus();
        type("123456");
        field.select(3, 4); // "."
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("123.456", field.text(), "Backspace on the selected dot: no digit goes");
        field.select(3, 4);
        drive(scene).press(Keys.DELETE);
        assertEquals("123.456", field.text(), "Delete on the selected dot: the same");
        field.select(3, 4);
        drive(scene).press(Keys.X, COMMAND);
        assertEquals("123.456", field.text(), "Cut of the selected dot: the same");
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("", field.text(), "none of the three left an edit to undo: the typing goes");
    }

    @Test
    void oneUndoTakesAnEditAndItsLiteralsBackTogether() {
        bindField("000.000");
        field.requestFocus();
        type("1234");
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("", field.text());
        drive(scene).press(Keys.Z, COMMAND | Keys.MOD_SHIFT);
        assertEquals("123.4", field.text());
    }

    @Test
    void theGuideShowsOnlyWhileTheFieldHasTheKeyboard() {
        bindField("000-000");
        assertTrue(drawn().contains("Document"), "unfocused and empty: the placeholder");
        field.requestFocus();
        assertTrue(drawn().contains("___-___"), "focused and empty: the guide");
        type("12");
        List<String> texts = drawn();
        assertTrue(texts.contains("12") && texts.contains("_-___"), "the text, then the rest: " + texts);
        field.setCaretPosition(0);
        scene.requestFocus(null);
        assertFalse(String.join("|", drawn()).contains("_"), "no guide without the keyboard");
    }

    @Test
    void aReaderHearsThePattern() {
        bindField("000.000.000-00");
        frame();
        assertEquals("000.000.000-00", node(Accessible.Role.TEXT_FIELD).description());
    }

    /** Every string the next frame draws. */
    private List<String> drawn() {
        List<String> texts = new ArrayList<>();
        canvas = new FakeCanvas(400, 300) {
            @Override
            public void drawText(ShapedText text, float x, float y, Paint paint) {
                texts.add(text.text());
            }
        };
        field.invalidate();
        frame();
        return texts;
    }
}

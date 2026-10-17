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
 * keyboard, and a reader hears the text and the application's description but never the pattern.
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
    void codeSetsAValueWhoseDigitsMatchALiteralWithOrWithoutTheLiterals() {
        TextField phone = new TextField().setMask("+55 (00) 00000-0000");
        phone.setText("51987654321");
        assertEquals("+55 (51) 98765-4321", phone.text(), "area code 51 kept");
        assertEquals("51987654321", phone.unmaskedText());
        assertTrue(phone.isMaskComplete());
        phone.setText("+55 (51) 98765-4321");
        assertEquals("51987654321", phone.unmaskedText(), "the formatted value reads the same");
        phone.setText(phone.unmaskedText());
        assertEquals("+55 (51) 98765-4321", phone.text(), "and what the application read sets it back");
        phone.setText("5512345678");
        assertEquals("+55 (55) 12345-678", phone.text(), "area code 55, the literal's own digits");
        assertFalse(phone.isMaskComplete());

        TextField zero = new TextField().setMask("+'0' 000");
        zero.setText("012");
        assertEquals("+0 012", zero.text());
        assertEquals("012", zero.unmaskedText());
        assertTrue(zero.isMaskComplete());
        zero.setText("+0 012");
        assertEquals("012", zero.unmaskedText());
    }

    @Test
    void typingIntoAPatternWithDigitLiteralsFillsThePositions() {
        bindField("+55 (00) 00000-0000");
        field.requestFocus();
        type("5");
        assertEquals("+55 (5", field.text());
        type("1987654321");
        assertEquals("+55 (51) 98765-4321", field.text());
        assertEquals("51987654321", field.unmaskedText());

        bindField("+'0' 000");
        field.requestFocus();
        type("0");
        assertEquals("+0 0", field.text(), "a 0 typed first is the first digit");
    }

    @Test
    void typingTheLiteralsByHandOrPastingThemStepsOverThem() {
        bindField("(00) 000-0000");
        field.requestFocus();
        type("(12) 345-6789");
        assertEquals("(12) 345-6789", field.text());
        assertEquals("123456789", field.unmaskedText());

        bindField("+55 (00) 00000-0000");
        scene.setClipboard(TextFieldTest.MockClipboard.holding("+55 (51) 98765-4321"));
        field.requestFocus();
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("+55 (51) 98765-4321", field.text());
        assertEquals("51987654321", field.unmaskedText());
    }

    @Test
    void backspaceOverALiteralKeepsADigitThatMatchesALiteral() {
        bindField("+55 (00) 00000-0000");
        field.requestFocus();
        type("51987");
        field.setCaretPosition(9); // after "+55 (51) ", before the 9
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("+55 (59) 87", field.text(), "the 1 went, and the 5 stayed");
        assertEquals("5987", field.unmaskedText());

        // A run made only of digits: the characters left after the step are the value's alone,
        // and their leading 55 is not the literal's.
        bindField("'55'0-000");
        field.requestFocus();
        type("1552");
        assertEquals("551-552", field.text());
        field.setCaretPosition(4); // after the dash
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("555-52", field.text(), "the 1 went, and 552 stayed");
        assertEquals("552", field.unmaskedText());
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
    void backspaceAtTheEndTakesTheLiteralsBackOneAtATime() {
        bindField("000.000");
        field.requestFocus();
        type("1234");
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("123.", field.text(), "the 4 went; the dot the text ends in stays");
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("123", field.text(), "and goes with the next Backspace, not the 3 with it");
        assertEquals("123", field.unmaskedText());
    }

    // ------------------------------------------------- where the caret is drawn

    @Test
    void anEmptyFieldDrawsItsCaretAfterTheLiteralsTheGuideStartsWith() {
        assertCaretPastGuideLiterals("'DE'00 0000 0000 0000 0000 00", "DE");
        assertCaretPastGuideLiterals("+1 (000) 000-0000", "+1 (");
    }

    private void assertCaretPastGuideLiterals(String pattern, String lead) {
        bindField(pattern);
        float unfocused = field.caretRect().x(); // the leading edge, as the field computes it
        field.requestFocus();
        float advance = 10f * lead.length(); // the test ruler's advance
        assertEquals(unfocused + advance, field.caretRect().x(), 0.001f,
                pattern + ": the box the input method is handed, after " + lead);
        assertEquals(field.heldCaretBox().x(), paintedCaretX(), 0.001f,
                pattern + ": the caret drawn where the box says");
        assertEquals("", field.text(), "nothing is written by focusing");
    }

    @Test
    void afterATypedRunTheCaretStandsPastTheLiteralTheNextDigitBrings() {
        bindField("000.000.000-00");
        field.requestFocus();
        type("123");
        assertEquals("123", field.text(), "the dot is not written yet");
        float atEnd = field.caretRect().x();
        field.setCaretPosition(2);
        float beforeThe3 = field.caretRect().x();
        assertEquals(beforeThe3 + 20f, atEnd, 0.001f, "past the 3 and the guide's dot");
        field.setCaretPosition(3);
        assertEquals(field.heldCaretBox().x(), paintedCaretX(), 0.001f);
        type("4");
        assertEquals("123.4", field.text());
        assertEquals(beforeThe3 + 30f, field.caretRect().x(), 0.001f,
                "the 4 appeared where the caret stood, and the caret is after it");
    }

    @Test
    void aClickPastTheEndLandsWhereTheNextDigitWillAppear() {
        bindField("000.000.000-00");
        field.requestFocus();
        type("123");
        field.setCaretPosition(0);
        float start = field.caretRect().x();
        drive(scene).click(field.localToSceneX() + field.width() - 4,
                field.localToSceneY() + field.height() / 2);
        assertEquals(3, field.caretPosition(), "the end of the text");
        assertEquals(start + 40f, field.caretRect().x(), 0.001f, "drawn past the guide's dot");
    }

    @Test
    void aCaretInsideTheTextIsDrawnWhereItIs() {
        bindField("'DE'00 0000");
        field.requestFocus();
        type("12");
        assertEquals("DE12", field.text());
        float atEnd = field.caretRect().x(); // past the guide's space
        field.setCaretPosition(3);
        assertEquals(atEnd - 20f, field.caretRect().x(), 0.001f,
                "before the 2: back one cluster and the space, which is drawn only at the end");
        field.select(3, 4);
        field.setCaretPosition(4);
        assertEquals(atEnd, field.caretRect().x(), 0.001f, "and at the end again, past it");
    }

    // ------------------------------------------------- typing the literals by hand

    @Test
    void typingTheLiteralsOfARunShowsEachOne() {
        bindField("'DE'00 0000 0000 0000 0000 00");
        field.requestFocus();
        type("D");
        assertEquals("D", field.text(), "the keystroke shows");
        assertEquals(1, field.caretPosition());
        type("E");
        assertEquals("DE", field.text());
        type("8");
        assertEquals("DE8", field.text());
        type("9");
        assertEquals("DE89", field.text());
        assertEquals(4, field.caretPosition(), "the caret after it");
        assertEquals("89", field.unmaskedText());
    }

    @Test
    void typingTheRunAndTypingOnlyDigitsGiveTheSameValue() {
        String iban = "DE89 3704 0044 0532 0130 00";
        bindField("'DE'00 0000 0000 0000 0000 00");
        field.requestFocus();
        type(iban);
        assertEquals(iban, field.text());
        bindField("'DE'00 0000 0000 0000 0000 00");
        field.requestFocus();
        type("89370400440532013000");
        assertEquals(iban, field.text());
        assertEquals("89370400440532013000", field.unmaskedText());
        assertTrue(field.isMaskComplete());

        bindField("+1 (000) 000-0000");
        field.requestFocus();
        type("+1 (555) 123-4567");
        assertEquals("+1 (555) 123-4567", field.text(), "typed by hand, the 1 included");
        bindField("+1 (000) 000-0000");
        field.requestFocus();
        type("5551234567");
        assertEquals("+1 (555) 123-4567", field.text());
        bindField("+1 (000) 000-0000");
        field.requestFocus();
        type("1");
        assertEquals("+1 (1", field.text(), "a 1 alone is a digit: nothing shows it is the literal's");
    }

    @Test
    void pastingWithOrWithoutTheRunGivesTheSameValue() {
        for (String pasted : new String[]{"DE89 3704 0044 0532 0130 00", "89370400440532013000"}) {
            bindField("'DE'00 0000 0000 0000 0000 00");
            scene.setClipboard(TextFieldTest.MockClipboard.holding(pasted));
            field.requestFocus();
            drive(scene).press(Keys.V, COMMAND);
            assertEquals("DE89 3704 0044 0532 0130 00", field.text(), pasted);
        }
    }

    @Test
    void backspaceTakesAPartRunBackOneCharacterAtATime() {
        bindField("'DE'00 0000 0000 0000 0000 00");
        field.requestFocus();
        type("DE8");
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("DE", field.text());
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("D", field.text());
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("", field.text());
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
    void undoAndRedoTakeAStepOverBackAndForthWhole() {
        bindField("000.000");
        field.requestFocus();
        type("1234");
        field.setCaretPosition(4); // after the dot
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("124", field.text());
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("123.4", field.text(), "the 3 back, and the dot with it");
        drive(scene).press(Keys.Z, COMMAND | Keys.MOD_SHIFT);
        assertEquals("124", field.text(), "redo steps over again, and is not fitted as a new edit");
        assertEquals("124", field.unmaskedText());
    }

    @Test
    void aCompositionHidesTheGuideAndItsCommitIsFitted() {
        bindField("000-000");
        field.requestFocus();
        type("12");
        drive(scene).preeditChanged("３", 1, 0, 1); // a full-width 3, as an input method shows it
        drive(scene).inputBatchEnded();
        assertFalse(String.join("|", drawn()).contains("_"), "no guide under a composition");
        drive(scene).preeditChanged("", 0, 0, 0);
        type("３4");
        assertEquals("12３-4", field.text(), "the commit is typing: fitted, literal and all");
        assertEquals("12３4", field.unmaskedText(), "a digit as the user's script writes it");
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
    void aReaderHearsNoPatternSyntax() {
        bindField("AA-0000");
        frame();
        String description = node(Accessible.Role.TEXT_FIELD).description();
        assertFalse(description != null && description.contains("AA-0000"),
                "the pattern is syntax, not words: " + description);
    }

    @Test
    void aMaskLeavesTheTooltipAndTheApplicationToDescribeTheField() {
        bindField("AA-0000");
        field.setTooltip("Two letters and four digits");
        frame();
        assertEquals("Two letters and four digits", node(Accessible.Role.TEXT_FIELD).description(),
                "the tooltip describes the field, as on a field without a mask");

        field.setAccessibleDescription("Plate, as printed on the vehicle");
        frame();
        assertEquals("Plate, as printed on the vehicle", node(Accessible.Role.TEXT_FIELD).description());
    }

    /** The x of the vertical line the next frame draws for the caret. */
    private float paintedCaretX() {
        float[] x = {Float.NaN};
        canvas = new FakeCanvas(400, 300) {
            @Override
            public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
                if (x1 == x2 && y1 != y2) {
                    x[0] = x1;
                }
            }
        };
        field.invalidate();
        frame();
        return x[0];
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

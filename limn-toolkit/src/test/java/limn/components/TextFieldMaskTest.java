package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.input.Keys;
import limn.scene.Change;
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

    // ------------------------------------- edits before the end never read a literal as a value

    /** A field under {@code pattern} holding {@code text}, with the keyboard, the caret at {@code caret}. */
    private void holding(String pattern, String text, int caret) {
        bindField(pattern);
        field.setText(text);
        field.requestFocus();
        field.setCaretPosition(caret);
    }

    @Test
    void typingAtHomeBeforeALiteralThePositionWouldTakeKeepsTheLiteralAndEveryDigit() {
        holding("+1 (000) 000-0000", "+1 (555) 123-456", 0);
        type("9");
        assertEquals("+1 (955) 512-3456", field.text(), "the 1 of +1 stays a literal");
        assertEquals("9555123456", field.unmaskedText());
        assertTrue(field.isMaskComplete());
        assertEquals(5, field.caretPosition(), "after the 9");

        holding("+55 (00) 00000-0000", "+55 (51) 98765-432", 0);
        type("9");
        assertEquals("+55 (95) 19876-5432", field.text(), "the 55 of +55 stays a literal");
        assertEquals("95198765432", field.unmaskedText());
    }

    @Test
    void typingIntoAFullFieldBeforeItsEndIsRefusedAndNothingFallsOff() {
        holding("+1 (000) 000-0000", "+1 (555) 123-4567", 0);
        type("9");
        assertEquals("+1 (555) 123-4567", field.text(), "not +1 (915) 551-2345: no position is free");
        assertEquals("5551234567", field.unmaskedText(), "the 7 is still there");
        assertTrue(field.isMaskComplete());

        holding("+55 (00) 00000-0000", "+55 (51) 98765-4321", 0);
        type("9");
        assertEquals("+55 (51) 98765-4321", field.text());

        holding("AAA-0000-**", "ABC-1234-XY", 7); // after the 3
        type("5");
        assertEquals("ABC-1234-XY", field.text(), "not ABC-1235-4-: the dash never becomes a value");
        assertEquals("ABC1234XY", field.unmaskedText());

        holding("000 '9' 000", "123 9 456", 1);
        type("7");
        assertEquals("123 9 456", field.text(), "not 172 9 394");
        assertEquals("123456", field.unmaskedText());
    }

    @Test
    void typingBeforeAQuotedDigitLiteralKeepsItALiteral() {
        holding("000 '9' 000", "123 9 45", 1);
        type("7");
        assertEquals("172 9 345", field.text(), "the quoted 9 stays where the pattern has it");
        assertEquals("172345", field.unmaskedText());
        assertEquals(2, field.caretPosition());
    }

    @Test
    void typingBeforeALiteralAnAnyPositionWouldTakeKeepsTheLiteral() {
        holding("AAA-0000-**", "ABC-1234-X", 7); // after the 3
        type("5");
        assertEquals("ABC-1235-4X", field.text(), "the dash stays a literal; the X stays too");
        assertEquals("ABC12354X", field.unmaskedText());
        assertTrue(field.isMaskComplete());
    }

    @Test
    void backspaceOnAValueBeforeALiteralAnAnyPositionWouldTakeClosesUpTheValuesAlone() {
        holding("****-****", "ABCD-EFGH", 2); // after the B
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("ACDE-FGH", field.text(), "not ACD--EFGH");
        assertEquals("ACDEFGH", field.unmaskedText());
        assertFalse(field.isMaskComplete(), "one position is free again");
        assertEquals(1, field.caretPosition(), "where the B was");
    }

    @Test
    void backspaceAndDeleteBeforeAQuotedDigitLiteralKeepItALiteral() {
        holding("000 '9' 000", "123 9 456", 2); // after the 2
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("134 9 56", field.text());
        assertEquals("13456", field.unmaskedText());

        holding("000 '9' 000", "123 9 456", 0);
        drive(scene).press(Keys.DELETE);
        assertEquals("234 9 56", field.text());
        assertEquals("23456", field.unmaskedText());
        assertEquals(0, field.caretPosition());
    }

    @Test
    void typingOverASelectionBeforeALiteralAPositionWouldTakeKeepsTheLiteral() {
        holding("****-****", "ABCD-EFG", 1);
        field.select(1, 3); // "BC"
        type("x");
        assertEquals("AxCD-EFG", field.text(), "the B overwritten, nothing moved: not AxD--EFG");
        assertEquals("AxCDEFG", field.unmaskedText());
        assertEquals(2, field.selectionStart(), "the C, not reached, stays selected");
        assertEquals(3, field.selectionEnd());

        holding("+1 (000) 000-0000", "+1 (555) 123-4567", 0);
        field.select(0, 2); // "+1", literals alone
        type("9");
        assertEquals("+1 (555) 123-4567", field.text(), "full: refused, and the literals come back");
        assertEquals("5551234567", field.unmaskedText());
    }

    @Test
    void pastingAFragmentBeforeALiteralThePositionWouldTakeKeepsTheLiteral() {
        holding("000 '9' 000", "123 9 4", 1);
        field.insertText("78");
        assertEquals("178 9 234", field.text(), "not 178 2 394: the quoted 9 stays a literal");
        assertEquals("178234", field.unmaskedText());
        assertTrue(field.isMaskComplete());

        holding("000 '9' 000", "1", 1);
        field.insertText("23 9 4");
        assertEquals("123 9 4", field.text(), "a fragment with its literals reads as one");
        assertEquals("1234", field.unmaskedText());
    }

    @Test
    void typingTheLiteralUnderTheCaretMidTextStepsOverIt() {
        holding("000.000", "123.456", 3); // before the dot
        type(".");
        assertEquals("123.456", field.text());
        assertEquals(4, field.caretPosition(), "past the dot, as typing it at the end does");
        type("9");
        assertEquals("123.456", field.text(), "the field is full: refused");

        holding("+1 (000) 000-0000", "+1 (555) 123-4567", 1); // after the +
        type("1 (");
        assertEquals("+1 (555) 123-4567", field.text());
        assertEquals(4, field.caretPosition(), "the literals typed over, one by one");
    }

    @Test
    void undoAfterAnEditBeforeALiteralRunRestoresTheValueAsItWas() {
        holding("+1 (000) 000-0000", "+1 (555) 123-456", 0);
        type("9");
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("+1 (555) 123-456", field.text());
        assertEquals("555123456", field.unmaskedText());
        drive(scene).press(Keys.Z, COMMAND | Keys.MOD_SHIFT);
        assertEquals("+1 (955) 512-3456", field.text());
        assertEquals("9555123456", field.unmaskedText());

        holding("'55'00000", "5555123", 2); // the run's 55, then 55123 the value's
        drive(scene).press(Keys.DELETE);
        assertEquals("555123", field.text());
        assertEquals("5123", field.unmaskedText());
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("5555123", field.text());
        assertEquals("55123", field.unmaskedText(), "an undo reads the text as the field wrote it");
    }

    // ------------------------------------------------- an edit that comes to nothing says nothing

    @Test
    void typingIntoAFullFieldAnnouncesNothingAtHomeMidTextAndAtTheEnd() {
        holding("+1 (000) 000-0000", "+1 (555) 123-4567", 0);
        List<String> edits = new ArrayList<>();
        field.observeChanges((widget, change) -> edits.add(change.toString()));
        for (int caret : new int[]{0, 5, 17}) {
            field.setCaretPosition(caret);
            frame();
            heard.clear();
            edits.clear();
            bridge.events.clear();
            type("9");
            frame();
            assertEquals("+1 (555) 123-4567", field.text());
            assertEquals(caret, field.caretPosition(), "the caret did not move");
            assertEquals(List.of(), heard, "caret " + caret + ": no handler runs for a refused key");
            assertEquals(List.of(), edits, "caret " + caret + ": no change reaches a watcher");
            assertEquals(List.of(), bridge.eventsOf(AccessibleEvent.Type.CARET_MOVED),
                    "caret " + caret + ": " + bridge.events);
        }
    }

    @Test
    void steppingOverALiteralMovesOnlyTheCaret() {
        holding("+1 (000) 000-0000", "+1 (555) 123-4567", 7); // before the )
        List<Change> edits = new ArrayList<>();
        field.observeChanges((widget, change) -> edits.add(change));
        frame();
        heard.clear();
        bridge.events.clear();
        type(")");
        frame();
        assertEquals("+1 (555) 123-4567", field.text());
        assertEquals(8, field.caretPosition());
        assertEquals(List.of(), heard, "the text did not change");
        assertEquals(1, edits.size(), edits.toString());
        assertEquals(Change.Aspect.SELECTION, edits.get(0).aspect(), "the caret's move, and only that");
        assertEquals(1, bridge.eventsOf(AccessibleEvent.Type.CARET_MOVED).size(), bridge.events.toString());
    }

    @Test
    void aLiteralTypedOverTheSameSelectedLiteralLeavesNoUndoStep() {
        holding("000.000", "123", 3);
        type("456");
        field.select(3, 4); // the dot
        heard.clear();
        type(".");
        assertEquals("123.456", field.text());
        assertEquals(List.of(), heard);
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("123", field.text(), "one undo takes the typing back, not a step that did nothing");
    }

    @Test
    void aKeystrokeTheMaskRefusesKeepsRedo() {
        // A digit into a full field, a letter into a digit position, a literal stepped over, and
        // two refused keys in a row: each leaves the redo history as the undo left it.
        String[][] cases = {{"6", "1"}, {"6", "x"}, {"7", ")"}, {"6", "12"}};
        for (String[] keys : cases) {
            holding("+1 (000) 000-0000", "5551234567", 0);
            field.select(4, 5); // the first 5
            type("9");
            assertEquals("+1 (955) 123-4567", field.text());
            drive(scene).press(Keys.Z, COMMAND);
            assertEquals("+1 (555) 123-4567", field.text());
            field.setCaretPosition(Integer.parseInt(keys[0]));
            type(keys[1]);
            assertEquals("+1 (555) 123-4567", field.text(), keys[1] + " is refused");
            drive(scene).press(Keys.Z, COMMAND | Keys.MOD_SHIFT);
            assertEquals("+1 (955) 123-4567", field.text(), "redo after a refused " + keys[1]);
            drive(scene).press(Keys.Z, COMMAND);
            assertEquals("+1 (555) 123-4567", field.text(), "and undo again after " + keys[1]);
        }
    }

    // ------------------------------- a mixed pattern refuses an edit that would lose a character

    @Test
    void onAMixedPatternBackspaceInTheDigitsIsRefusedAndNothingIsHeard() {
        holding("AAA-0000-**", "ABC-1234-XY", 7); // after the 3
        List<Change> edits = new ArrayList<>();
        field.observeChanges((widget, change) -> edits.add(change));
        frame();
        heard.clear();
        bridge.events.clear();
        drive(scene).press(Keys.BACKSPACE);
        frame();
        assertEquals("ABC-1234-XY", field.text(), "not ABC-124: the X and the Y would be lost");
        assertEquals("ABC1234XY", field.unmaskedText());
        assertEquals(7, field.caretPosition(), "the caret did not move");
        assertEquals(List.of(), heard, "no handler runs");
        assertEquals(List.of(), edits, "no watcher hears of it");
        assertEquals(List.of(), bridge.eventsOf(AccessibleEvent.Type.CARET_MOVED), bridge.events.toString());
        assertEquals(List.of(), bridge.eventsOf(AccessibleEvent.Type.TEXT_CHANGED), bridge.events.toString());
    }

    @Test
    void onAMixedPatternALetterTypedBeforeOthersIsRefusedWhenOneWouldLandOnADigit() {
        holding("AAA-0000-**", "ABC-1234-X", 2); // after the B
        type("Q");
        assertEquals("ABC-1234-X", field.text(), "not ABQ-1234-X: the C would land on a digit and go");
        assertEquals(2, field.caretPosition());
        assertEquals(List.of(), heard);

        holding("AAA-0000-**", "ABC-1234-XY", 4); // after the dash: Backspace means the C
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("ABC-1234-XY", field.text(), "the 1 would land on a letter's place");
        drive(scene).press(Keys.DELETE);
        assertEquals("ABC-1234-XY", field.text(), "Delete takes the 1: the X would land on a digit");
    }

    @Test
    void onAMixedPatternAnEditWhoseCharactersStillFitIsMade() {
        holding("AAA-0000-**", "ABC-1234-X", 7); // after the 3
        type("5");
        assertEquals("ABC-1235-4X", field.text(), "the 4 moves into an any position, which takes it");
        assertEquals(List.of("ABC-1235-4X"), heard);

        holding("AAA-0000-**", "ABC-1234", 5); // after the 1
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("ABC-234", field.text(), "nothing after the digits: the 2, 3 and 4 stay digits");
        assertEquals(4, field.caretPosition());
    }

    @Test
    void aRefusedEditKeepsTheSelectionTheUndoHistoryAndRedo() {
        holding("AAA-0000-**", "ABC-1234-XY", 0);
        field.select(2, 5); // "C-1"
        drive(scene).press(Keys.DELETE);
        assertEquals("ABC-1234-XY", field.text(), "not AB2-34XY: the 2 would land on a letter");
        assertEquals(2, field.selectionStart(), "the selection is as it was");
        assertEquals(5, field.selectionEnd());
        assertEquals(List.of(), heard);

        holding("+1 (000) 000-0000", "+1 (555) 123-4567", 0);
        field.select(0, 2); // "+1", literals alone, in a full field
        type("9");
        assertEquals("+1 (555) 123-4567", field.text());
        assertEquals(0, field.selectionStart(), "a full field's refusal keeps the selection too");
        assertEquals(2, field.selectionEnd());

        // A refused key leaves no undo step of its own and keeps the redo the undo left.
        holding("AAA-0000-**", "ABC-1234-XY", 11);
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("ABC-1234-X", field.text(), "within the any run: made");
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("ABC-1234-XY", field.text());
        field.setCaretPosition(7);
        drive(scene).press(Keys.BACKSPACE);
        assertEquals("ABC-1234-XY", field.text(), "refused");
        drive(scene).press(Keys.Z, COMMAND | Keys.MOD_SHIFT);
        assertEquals("ABC-1234-X", field.text(), "redo after the refused Backspace");
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("ABC-1234-XY", field.text(), "one undo, as before: the refusal left no step");
    }

    // ------------------------------------------- typing over a selection overwrites its positions

    @Test
    void typingOverASelectionOverwritesItsPositionsOneByOne() {
        holding("AAA-0000-**", "ABC-1234-XY", 0);
        field.select(4, 6); // "12"
        type("5");
        assertEquals("ABC-5234-XY", field.text(), "the 1 overwritten, nothing moved");
        assertEquals(5, field.selectionStart(), "the 2 is what stays selected");
        assertEquals(6, field.selectionEnd());
        type("6");
        assertEquals("ABC-5634-XY", field.text());
        assertEquals(6, field.selectionStart(), "every selected position written: a caret after the last");
        assertEquals(6, field.selectionEnd());
        assertEquals(List.of("ABC-5234-XY", "ABC-5634-XY"), heard, "one change per keystroke");
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("ABC-5234-XY", field.text(), "undo takes one keystroke back");
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("ABC-1234-XY", field.text());
        drive(scene).press(Keys.Z, COMMAND | Keys.MOD_SHIFT);
        drive(scene).press(Keys.Z, COMMAND | Keys.MOD_SHIFT);
        assertEquals("ABC-5634-XY", field.text(), "and redo puts each back");
    }

    @Test
    void anOverwriteStoppedMidwayLeavesTheRestSelectedAndAcrossALiteralSkipsIt() {
        holding("AAA-0000-**", "ABC-1234-XY", 0);
        field.select(7, 10); // "4-X"
        type("9");
        assertEquals("ABC-1239-XY", field.text());
        assertEquals(9, field.selectionStart(), "the X, past the dash, stays selected");
        assertEquals(10, field.selectionEnd());
        drive(scene).press(Keys.RIGHT);
        assertEquals("ABC-1239-XY", field.text(), "stopping there leaves the X as it was");
        assertEquals(10, field.caretPosition());
    }

    @Test
    void aCharacterTheNextSelectedPositionRefusesIsRefusedAndTheSelectionStays() {
        holding("AAA-0000-**", "ABC-1234-XY", 0);
        field.select(4, 6);
        type("x");
        assertEquals("ABC-1234-XY", field.text(), "a letter on a digit");
        assertEquals(4, field.selectionStart());
        assertEquals(6, field.selectionEnd());
        assertEquals(List.of(), heard);
    }

    @Test
    void pastingOverASelectionFillsItsPositionsInOrderAndDropsTheRest() {
        TextFieldTest.MockClipboard clipboard = new TextFieldTest.MockClipboard();
        holding("AAA-0000-**", "ABC-1234-XY", 0);
        scene.setClipboard(clipboard);
        field.select(4, 8); // "1234"
        clipboard.value = "98";
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("ABC-9834-XY", field.text(), "two positions filled, the 3 and the 4 untouched");
        assertEquals(6, field.selectionStart(), "and still selected");
        assertEquals(8, field.selectionEnd());

        field.select(4, 6); // "98"
        clipboard.value = "123456";
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("ABC-1234-XY", field.text(), "what finds no selected position is dropped: nothing moves");
        assertEquals(6, field.caretPosition());

        field.select(0, field.text().length());
        clipboard.value = "KLM-4217-Z9";
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("KLM-4217-Z9", field.text(), "over the whole value, a new value, with its literals");
    }

    @Test
    void aKeyTypedOverASelectionThatBeginsOnALiteralGoesToTheFirstSelectedPosition() {
        holding("000 '9' 000", "123 9 456", 0);
        field.select(4, 9); // "9 456": the quoted 9, then the digits
        type("9");
        assertEquals("123 9 956", field.text(), "the 9 is the 4's place, not the literal over again");
        assertEquals(7, field.selectionStart());
        assertEquals(9, field.selectionEnd());

        holding("+1 (000) 000-0000", "+1 (415) 555-0123", 0);
        field.select(1, 5); // "1 (4"
        type("1");
        assertEquals("+1 (115) 555-0123", field.text(), "an area code that begins with 1");
        assertEquals(5, field.caretPosition());

        // A paste is still read from where the selection begins, literals and all.
        TextFieldTest.MockClipboard clipboard = TextFieldTest.MockClipboard.holding(") 9");
        holding("+1 (000) 000-0000", "+1 (415) 555-0123", 0);
        scene.setClipboard(clipboard);
        field.select(7, 10); // ") 5"
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("+1 (415) 955-0123", field.text(), "the pasted ) and space are the literals");
    }

    @Test
    void aPasteOverASelectionThatEndsTheValueGoesOnIntoTheFreePositions() {
        TextFieldTest.MockClipboard clipboard = TextFieldTest.MockClipboard.holding("9990123");
        holding("+1 (000) 000-0000", "4155", 0);
        scene.setClipboard(clipboard);
        field.select(9, 10); // the 5 just typed, the last of the value
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("+1 (415) 999-0123", field.text(), "not +1 (415) 9: nothing follows, nothing moves");
        assertEquals(17, field.caretPosition());

        clipboard.value = "345";
        holding("AAA-0000-**", "ABC12", 0);
        scene.setClipboard(clipboard);
        field.select(5, 6); // the 2
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("ABC-1345", field.text());

        // With a value after the selection, the paste still fills the selection alone.
        clipboard.value = "999";
        holding("0000 0000", "12345", 0);
        scene.setClipboard(clipboard);
        field.select(3, 4); // the 4, with the 5 after it
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("1239 5", field.text(), "the 5 stays where it is");
    }

    @Test
    void typingOrPastingOverTheWholeValueStartsANewOne() {
        holding("+1 (000) 000-0000", "+1 (555) 123-4567", 0);
        drive(scene).press(Keys.A, COMMAND);
        type("9");
        assertEquals("+1 (9", field.text(), "Ctrl+A, then a digit: a new value, as in any field");
        assertEquals(5, field.caretPosition());
        assertEquals(List.of("+1 (9"), heard);
        drive(scene).press(Keys.Z, COMMAND);
        assertEquals("+1 (555) 123-4567", field.text(), "one undo puts the old value back");

        TextFieldTest.MockClipboard clipboard = TextFieldTest.MockClipboard.holding("4155550123");
        scene.setClipboard(clipboard);
        drive(scene).press(Keys.A, COMMAND);
        drive(scene).press(Keys.V, COMMAND);
        assertEquals("+1 (415) 555-0123", field.text(), "Ctrl+A, then a paste");

        field.select(4, 17); // every digit, without the +1 ( before them
        type("7");
        assertEquals("+1 (7", field.text(), "every position selected is the whole value, literals or not");

        holding("00 kg", "12 kg", 0);
        field.select(0, 2); // the digits, not the kg after them
        type("3");
        assertEquals("3", field.text(), "the literals that end the pattern go with the old value");
    }

    @Test
    void overTheWholeValueACharacterNoPositionTakesIsRefused() {
        holding("+1 (000) 000-0000", "+1 (555) 123-4567", 0);
        drive(scene).press(Keys.A, COMMAND);
        type("x");
        assertEquals("+1 (555) 123-4567", field.text(), "not an emptied field");
        assertEquals(0, field.selectionStart(), "and the selection stays");
        assertEquals(17, field.selectionEnd());
        assertEquals(List.of(), heard);
    }

    @Test
    void aSelectionOfEveryPositionButOneIsOverwritten() {
        holding("+1 (000) 000-0000", "+1 (555) 123-4567", 0);
        field.select(0, 16); // all but the last 7
        type("9");
        assertEquals("+1 (955) 123-4567", field.text(), "the first position overwritten, nothing else");
        assertEquals(5, field.selectionStart(), "the rest of the selection stays");
        assertEquals(16, field.selectionEnd());
    }

    @Test
    void aRefusedCutLeavesTheClipboardAloneAndAMadeOneFillsIt() {
        TextFieldTest.MockClipboard clipboard = TextFieldTest.MockClipboard.holding("before");
        holding("AAA-0000-**", "ABC-1234-XY", 0);
        scene.setClipboard(clipboard);
        field.select(4, 6); // "12": the X would land on a digit
        drive(scene).press(Keys.X, COMMAND);
        assertEquals("ABC-1234-XY", field.text());
        assertEquals("before", clipboard.value, "a refused Cut is not a Copy");
        assertEquals(4, field.selectionStart());
        assertEquals(6, field.selectionEnd());

        field.select(9, 11); // "XY": nothing after it
        drive(scene).press(Keys.X, COMMAND);
        assertEquals("ABC-1234-", field.text(), "the dash before them stays, as after a Backspace");
        assertEquals("XY", clipboard.value, "a Cut made fills the clipboard");
    }

    @Test
    void steppingOverALiteralNeverLeavesTheCaretInsideACluster() {
        holding("**-**", "ab\u0301c", 2); // the combining acute lands on the hyphen: "ab-\u0301c"
        assertEquals("ab-\u0301c", field.text());
        type("-");
        assertEquals("ab-\u0301c", field.text());
        assertEquals(4, field.caretPosition(), "past the hyphen and the mark drawn on it");
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
        drive(scene).click(field.localToScene(0, 0).x() + field.width() - 4,
                field.localToScene(0, 0).y() + field.height() / 2);
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

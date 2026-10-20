package limn.components.internal.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputMask} (ADR 067 section 5): what a pattern makes of any text, with or without its
 * literals, and where the caret goes.
 */
class InputMaskTest {

    private static final InputMask CODE = InputMask.of("000.000.000-00");

    @Test
    void aValueWithItsLiteralsAndOneWithoutComeOutTheSame() {
        assertEquals("123.456.789-00", CODE.conform("12345678900", 0).text());
        assertEquals("123.456.789-00", CODE.conform("123.456.789-00", 0).text());
        assertEquals("12345678900", CODE.conform("123.456.789-00", 0).raw());
    }

    @Test
    void aLiteralArrivesWithTheNextPositionAndNeverDangles() {
        assertEquals("123", CODE.conform("123", 3).text());
        assertEquals("123.4", CODE.conform("1234", 4).text());
        assertEquals(5, CODE.conform("1234", 4).caret(), "after the fourth digit, past the dot");
    }

    @Test
    void theLiteralsThatEndThePatternArriveWithTheLastPosition() {
        InputMask area = InputMask.of("(00)");
        assertEquals("(1", area.conform("1", 1).text(), "not before the last position is filled");
        assertEquals("(12)", area.conform("12", 2).text());
        assertEquals(4, area.conform("12", 2).caret(), "typed at the end: the caret ends the text");
        assertEquals("(12)", area.conform("(12)", 4).text(), "and taken from the text when there");
        assertEquals("12", area.conform("(12)", 4).raw());
        assertTrue(area.isComplete("(12)"));
        InputMask weight = InputMask.of("00 kg");
        assertEquals("12 kg", weight.conform("12", 0).text());
        assertEquals(0, weight.conform("12", 0).caret());
        assertEquals("12 kg", weight.conform("12 kg", 5).text());
        assertEquals("1", weight.conform("1", 1).text());
    }

    @Test
    void whatAPositionDoesNotTakeIsPassedOverAndWhatDoesNotFitIsDropped() {
        assertEquals("123.4", CODE.conform("1a2 3-4", 7).text());
        assertEquals("123.456.789-01", CODE.conform("1234567890123", 13).text());
        InputMask plate = InputMask.of("AAA-0*00");
        assertEquals("ABC-1D23", plate.conform("abc1d23", 7).text().toUpperCase());
        assertEquals("ABC-1", plate.conform("ABC1", 4).text());
    }

    @Test
    void theCaretFollowsTheCharactersBeforeIt() {
        // A digit typed in the middle, at caret 1 of "123": the caret goes after it.
        assertEquals(2, CODE.conform("1923", 2).caret());
        assertEquals(0, CODE.conform("123", 0).caret());
    }

    @Test
    void theGuideMarksEachPositionToFill() {
        assertEquals("___.___.___-__", CODE.guide());
        assertTrue(CODE.isComplete("123.456.789-00"));
        assertFalse(CODE.isComplete("123.456.789-0"));
        assertEquals(11, CODE.slots());
    }

    @Test
    void aPatternWithNothingToFillIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> InputMask.of("(--)"));
        assertThrows(IllegalArgumentException.class, () -> InputMask.of("'000'"), "quoted: literal");
        assertThrows(IllegalArgumentException.class, () -> InputMask.of("''"));
    }

    @Test
    void textBetweenQuotesIsLiteralSoASlotCharacterCanBeOne() {
        InputMask model = InputMask.of("'A'-000");
        assertEquals(3, model.slots());
        assertEquals("A-___", model.guide());
        assertEquals("A-123", model.conform("123", 3).text());
        assertEquals("'A'-000", model.pattern(), "the pattern as it was given");
        InputMask phone = InputMask.of("+'0' 000");
        assertEquals("+0 ___", phone.guide());
        assertEquals("+0 123", phone.conform("+0 123", 6).text());
        assertEquals("123", phone.conform("+0 123", 6).raw());
        String typed = phone.conform("1", 1).text();
        assertEquals("+0 1", typed, "the quoted 0 is written, not filled");
        assertEquals("+0 1", phone.conform(typed, 4).text(), "and its own text conforms to itself");
        assertEquals("*-__", InputMask.of("'*'-AA").guide());
    }

    @Test
    void twoQuotesAreOneApostropheInsideQuotesAndOut() {
        assertEquals("'__", InputMask.of("''00").guide());
        assertEquals("'12", InputMask.of("''00").conform("12", 2).text());
        assertEquals("o'clock __", InputMask.of("'o''clock' 00").guide());
        assertEquals("__'__", InputMask.of("00''00").guide());
    }

    @Test
    void anUnterminatedQuoteIsRefusedNamingThePattern() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> InputMask.of("00-'AB"));
        assertTrue(refused.getMessage().contains("00-'AB"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> InputMask.of("'o''clock 00"));
    }

    private static final InputMask PHONE = InputMask.of("+55 (00) 00000-0000");

    @Test
    void aRawValueWhoseCharactersMatchALiteralKeepsEveryOne() {
        // Area code 51: its 5 is the literal's 5, and it is still the value's first digit.
        assertEquals("+55 (51) 98765-4321", PHONE.conform("51987654321", 11).text());
        assertEquals("51987654321", PHONE.conform("51987654321", 11).raw());
        // Area code 55: the literal's two characters exactly, and still the value's.
        assertEquals("+55 (55) 12345-678", PHONE.conform("5512345678", 10).text());
        assertEquals("5512345678", PHONE.conform("5512345678", 10).raw());
        InputMask zero = InputMask.of("+'0' 000");
        assertEquals("+0 012", zero.conform("012", 3).text());
        assertEquals("012", zero.conform("012", 3).raw());
        assertTrue(zero.isComplete("+0 012"));
    }

    @Test
    void aFormattedValueIsStillReadWithItsLiterals() {
        assertEquals("51987654321", PHONE.conform("+55 (51) 98765-4321", 0).raw());
        assertEquals("51987654321", PHONE.conform("+55(51)98765-4321", 0).raw(),
                "a value written without the spaces is still the formatted one");
        assertEquals("012", InputMask.of("+'0' 000").conform("+0 012", 0).raw());
        assertEquals("5512345678", PHONE.conform("+55 (55) 12345-678", 0).raw());
    }

    @Test
    void typingDigitByDigitFillsThePositions() {
        assertEquals("+55 (51) 98765-4321", typed(PHONE, "51987654321"));
        assertEquals("+0 012", typed(InputMask.of("+'0' 000"), "012"));
        assertEquals("+0 0", typed(InputMask.of("+'0' 000"), "0"), "a 0 typed first is a digit");
    }

    @Test
    void typingTheLiteralCharactersByHandStepsOverThem() {
        assertEquals("123.456", typed(CODE, "123.456"));
        assertEquals("(12) 345-6789", typed(InputMask.of("(00) 000-0000"), "(12) 345-6789"));
        assertEquals("+55 (51) 9", typed(PHONE, "51) 9"), "a closing literal typed by hand");
    }

    @Test
    void aRunOfDigitsAloneIsReadOnlyWholeAndNeverFromAValueKnownToBeRaw() {
        InputMask prefixed = InputMask.of("'55'0-000");
        assertEquals("551-552", prefixed.conform("551552", 6).text(), "carried whole: the literal");
        assertEquals("2", prefixed.conform("552", 3).raw(), "the same text, read as formatted");
        assertEquals("555-52", prefixed.conformRaw("552", 3).text(), "known to be the value's");
        assertEquals("552", prefixed.conformRaw("552", 3).raw());
        assertEquals("555-1", prefixed.conform("51", 2).text(), "a 5 alone is not the run");
    }

    private static final InputMask IBAN = InputMask.of("'DE'00 0000 0000 0000 0000 00");

    @Test
    void aRunTheTextEndsInIsWrittenAsFarAsTheTextShowsIt() {
        assertEquals("D", IBAN.conform("D", 1).text(), "a D typed shows");
        assertEquals(1, IBAN.conform("D", 1).caret());
        assertEquals("DE", IBAN.conform("DE", 2).text());
        assertEquals(2, IBAN.conform("DE", 2).caret());
        assertEquals("DE89", typed(IBAN, "DE89"));
        assertEquals("DE89 3704 0044 0532 0130 00", typed(IBAN, "DE89 3704 0044 0532 0130 00"));
        assertEquals("DE89 3704 0044 0532 0130 00", typed(IBAN, "89370400440532013000"));
        assertEquals("123.", CODE.conform("123.", 4).text(), "a dot the text ends in stays");
        InputMask phone = InputMask.of("+1 (000) 000-0000");
        assertEquals("+1", phone.conform("+1", 2).text(), "the + shows the 1 is the literal's");
        assertEquals("+1 (1", phone.conform("1", 1).text(), "a 1 alone is a digit");
        assertEquals("+1 (555) 123-4567", typed(phone, "+1 (555) 123-4567"));
        assertEquals("+1 (555) 123-4567", typed(phone, "5551234567"));
        assertEquals("555", InputMask.of("'55'00000").conform("5", 1).text(),
                "a run of digits alone is not written from a part of it");
    }

    @Test
    void theLiteralsBeforeTheNextPositionAreWhereTheCaretIsDrawn() {
        assertEquals("DE", IBAN.literalsAt(0));
        assertEquals("+1 (", InputMask.of("+1 (000) 000-0000").literalsAt(0));
        assertEquals(".", CODE.literalsAt(3), "after 123");
        assertEquals("", CODE.literalsAt(2), "between two positions");
        assertEquals("", IBAN.literalsAt(1), "the text ends in the run: the caret follows it");
        assertEquals("", CODE.literalsAt(4), "after 123. there is a position next");
        assertEquals("", InputMask.of("00").literalsAt(2), "complete");
    }

    /** What a field shows after {@code keys} are typed one by one at its end. */
    private static String typed(InputMask mask, String keys) {
        String text = "";
        for (int i = 0; i < keys.length(); i++) {
            String next = text + keys.charAt(i);
            text = mask.conform(next, next.length()).text();
        }
        return text;
    }

    @Test
    void removingAFilledCharacterClosesUp() {
        assertEquals("1245", InputMask.removeRaw("12345", 2));
    }

    // ------------------------------------------------------- an edit made to the value

    private static final InputMask US = InputMask.of("+1 (000) 000-0000");

    @Test
    void anEditBeforeARunThePositionWouldTakeNeverReadsTheRunAsValue() {
        InputMask.Conformed home = US.edit("+1 (555) 123-456", 0, 0, "9", 0);
        assertEquals("+1 (955) 512-3456", home.text());
        assertEquals("9555123456", home.raw());
        assertEquals(5, home.caret(), "after the 9");

        InputMask.Conformed back = InputMask.of("****-****").edit("ABCD-EFGH", 1, 2, "", -1);
        assertEquals("ACDE-FGH", back.text(), "the dash stays a literal");
        assertEquals(1, back.caret());

        InputMask.Conformed quoted = InputMask.of("000 '9' 000").edit("123 9 45", 1, 1, "7", 0);
        assertEquals("172 9 345", quoted.text());
        assertEquals("172345", quoted.raw());
    }

    @Test
    void anEditIntoAFieldWithNoPositionFreeGivesWayAndKeepsTheValue() {
        assertNull(US.edit("+1 (555) 123-4567", 0, 0, "9", 0), "refused: nothing pushed off the end");
        assertNull(US.edit("+1 (555) 123-4567", 0, 2, "9", 0), "over literals alone, refused too");

        InputMask.Conformed longer = US.edit("+1 (555) 123-45", 4, 4, "9876", 0);
        assertEquals("+1 (985) 551-2345", longer.text(), "the room there was, and no more");
        assertEquals(6, longer.caret(), "after the last character that found a position");
    }

    @Test
    void anInsertedFragmentIsReadFromTheEntryItLandsOn() {
        InputMask.Conformed literalUnderCaret = CODE.edit("123.456", 3, 3, ".", 0);
        assertEquals("123.456", literalUnderCaret.text());
        assertEquals(4, literalUnderCaret.caret(), "past the dot typed over");

        InputMask.Conformed afterPlus = US.edit("+1 (555) 123-4567", 1, 1, "1", 0);
        assertEquals(2, afterPlus.caret(), "after the +, the 1 is the literal's");
        assertEquals("5551234567", afterPlus.raw());

        InputMask.Conformed formatted = US.edit("+1 (5", 5, 5, "55) 123-4567", 0);
        assertEquals("+1 (555) 123-4567", formatted.text());
        assertEquals("5551234567", formatted.raw());
    }

    @Test
    void aBackspaceOrDeleteThatTookOnlyALiteralTakesTheCharacterBeyondIt() {
        assertEquals("124", CODE.edit("123.4", 3, 4, "", -1).text(), "Backspace after the dot");
        assertEquals(2, CODE.edit("123.4", 3, 4, "", -1).caret());
        assertEquals("123", CODE.edit("123.4", 3, 4, "", 1).text(), "Delete before the dot");
        assertEquals("123.4", CODE.edit("123.4", 3, 4, "", 0).text(), "a selected dot cut: it comes back");
        assertEquals("123", CODE.edit("123.", 3, 4, "", -1).text(), "a dot the text ended in goes alone");
        assertEquals("(1", InputMask.of("(00)").edit("(12)", 3, 4, "", -1).text());
        assertEquals("+1 ", US.edit("+1 (", 3, 4, "", -1).text(), "a run typed by hand, from its end");
        assertEquals("+1 (", US.edit("+1 (", 0, 1, "", 1).text(),
                "from its middle the run is put back: it is written in one piece");
    }

    @Test
    void onAMixedPatternAnEditThatWouldLoseACharacterIsRefused() {
        InputMask serial = InputMask.of("AAA-0000-**");
        assertNull(serial.edit("ABC-1234-XY", 6, 7, "", -1),
                "Backspace after the 3: the X would land on the fourth digit's place, not ABC-124");
        assertNull(serial.edit("ABC-1234-X", 2, 2, "Q", 0), "Q after the B: the C would land on a digit");
        assertNull(serial.edit("ABC-1234-XY", 1, 2, "", -1), "Backspace after the A: not BCX");
        assertNull(serial.edit("ABC-1234-XY", 5, 7, "9", 0), "23 typed over with a 9: not ABC-194");
        assertNull(serial.edit("ABC-1234-XY", 4, 4, "", -1),
                "Backspace after the dash takes the C, and the 1 would land on a letter");
        assertNull(serial.edit("ABC-123", 1, 2, "", -1), "a shorter value: the 1 would land on a letter");
        assertNull(serial.edit("ABC-1234-XY", 2, 5, "", 0), "C-1 cut: the 2 would land on a letter");
    }

    @Test
    void onAMixedPatternAnEditWhoseMovedCharactersLandWhereTheyAreTakenIsMade() {
        InputMask serial = InputMask.of("AAA-0000-**");
        InputMask.Conformed onIntoAny = serial.edit("ABC-1234-X", 7, 7, "5", 0);
        assertEquals("ABC-1235-4X", onIntoAny.text(), "the 4 moves on into an any position, and the dash stays");
        assertEquals("ABC12354X", onIntoAny.raw());
        assertEquals("ABC-134", serial.edit("ABC-1234", 5, 6, "", -1).text(),
                "Backspace within the digits, nothing after them: the 3 and the 4 stay digits");
        assertEquals("ABC-1234-Y", serial.edit("ABC-1234-XY", 9, 10, "", -1).text(), "within the any run");
        assertEquals("ABZ-9234-XY", serial.edit("ABC-1234-XY", 2, 5, "Z9", 0).text(),
                "as many put back as were taken, each of its kind: nothing moves");
        assertEquals("AC", serial.edit("ABC", 1, 2, "", -1).text(), "letters close up among letters");
        InputMask digitsThenAny = InputMask.of("00-**");
        assertEquals("23-4", digitsThenAny.edit("12-34", 0, 1, "", 1).text(),
                "the 3 moves back into a digit position, which takes it");
        assertNull(digitsThenAny.edit("12-ab", 0, 1, "", 1), "the a would land on a digit");
    }

    @Test
    void aTextTheMaskWroteReadsAsItStands() {
        InputMask prefixed = InputMask.of("'55'00000");
        assertEquals("5", prefixed.written("555", 3).raw(), "the run, then a 5 of the value's");
        assertEquals("55123", prefixed.written("5555123", 0).raw());
        assertEquals("123", CODE.written("123.", 4).raw(), "a literal written by hand");
        assertNull(CODE.written("1234", 4), "a literal out of place");
        assertNull(CODE.written("12a", 3), "a character its position does not take");
        assertNull(InputMask.of("(00)").written("(12", 3), "complete without its closing literal");
    }
}

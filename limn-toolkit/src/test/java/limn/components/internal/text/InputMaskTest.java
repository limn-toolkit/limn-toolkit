package limn.components.internal.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}

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
    }

    @Test
    void removingAFilledCharacterClosesUp() {
        assertEquals("1245", InputMask.removeRaw("12345", 2));
    }
}

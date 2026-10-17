package limn.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The customer form's three checks, on numbers made up or published as examples: a CPF of
 * sequential digits, the IBAN example banks print in their guides, and the card test number every
 * payment sandbox accepts.
 */
class DocumentsTest {

    @Test
    void aCpfIsValidWhenBothCheckDigitsMatch() {
        assertTrue(Documents.cpfValid("123.456.789-09"), "its first check digit is 0: the remainder was 1");
        assertTrue(Documents.cpfValid("12345678909"), "without its dots and dash");
        assertTrue(Documents.cpfValid("١٢٣٤٥٦٧٨٩٠٩"), "in Arabic-Indic digits, which a 0 position takes");
        assertFalse(Documents.cpfValid("123.456.789-00"), "both check digits wrong");
        assertFalse(Documents.cpfValid("123.456.789-08"), "the second check digit wrong");
        assertFalse(Documents.cpfValid("123.456.788-09"), "a body digit mistyped");
        assertFalse(Documents.cpfValid("123.456.789-0"), "ten digits");
        assertFalse(Documents.cpfValid("123.456.789-091"), "twelve digits");
        assertFalse(Documents.cpfValid("123.456.78A-09"), "a letter");
        assertFalse(Documents.cpfValid(""), "nothing");
    }

    @Test
    void aCpfOfOneRepeatedDigitIsRefusedThoughItsCheckDigitsMatch() {
        assertFalse(Documents.cpfValid("000.000.000-00"));
        assertFalse(Documents.cpfValid("111.111.111-11"), "the rule alone gives 1 and 1");
        assertFalse(Documents.cpfValid("999.999.999-99"));
    }

    @Test
    void anIbanIsValidWhenItLeavesOneByNinetySeven() {
        assertTrue(Documents.ibanValid("DE89 3704 0044 0532 0130 00"));
        assertTrue(Documents.ibanValid("DE89370400440532013000"), "without its spaces");
        assertTrue(Documents.ibanValid("de89 3704 0044 0532 0130 00"), "in lower case");
        assertFalse(Documents.ibanValid("DE88 3704 0044 0532 0130 00"), "a check digit wrong");
        assertFalse(Documents.ibanValid("DE89 3704 0044 0532 0130 01"), "an account digit wrong");
        assertFalse(Documents.ibanValid("DE89 3704 0044 0532 0103 00"), "two digits swapped");
        assertFalse(Documents.ibanValid("D989 3704 0044 0532 0130 00"), "a digit for a country letter");
        assertFalse(Documents.ibanValid("DEAB 3704 0044 0532 0130 00"), "letters for check digits");
        assertFalse(Documents.ibanValid("DE89 3704"), "shorter than any country's");
        assertFalse(Documents.ibanValid("DE89-3704-0044-0532-0130-00"), "a dash");
    }

    @Test
    void aCardNumberIsValidWhenItsLuhnSumIsAMultipleOfTen() {
        assertTrue(Documents.luhnValid("4111111111111111"));
        assertTrue(Documents.luhnValid("4111 1111 1111 1111"), "with its spaces");
        assertTrue(Documents.luhnValid("79927398713"), "the formula's own worked example");
        assertFalse(Documents.luhnValid("4111111111111112"), "the check digit wrong");
        assertFalse(Documents.luhnValid("4111111111111121"), "two digits swapped");
        assertFalse(Documents.luhnValid("4111 1111 1111 111X"), "a letter");
        assertFalse(Documents.luhnValid("0"), "one digit is no number with a check digit");
        assertFalse(Documents.luhnValid(""), "nothing");
    }
}

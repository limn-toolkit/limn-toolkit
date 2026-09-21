package limn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number formats under ADR 033. Java's own locale data already wrote Arabic-Indic digits
 * under {@code ar}, so what is asserted here is the part that was actually missing: a declared
 * numbering system wins over the platform's substitution (the fold), and a localized zero trims
 * exactly as an ASCII one does (the defect the measurement found riding along).
 */
class NumberFormatsDigitsTest {

    private Locale original;

    @BeforeEach
    void rememberLocale() {
        original = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void restore() {
        I18n.setNumberingSystem(null);
        I18n.setLocale(original);
    }

    @Test
    void theLocaleWritesItsOwnDigits() {
        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        assertEquals("٤٢", NumberFormats.number().apply(42));

        I18n.setLocale(Locale.ENGLISH);
        assertEquals("42", NumberFormats.number().apply(42), "and the Latin default is unchanged");
    }

    @Test
    void aDeclaredSystemWinsOverThePlatformsOwnSubstitution() {
        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        I18n.setNumberingSystem(NumberingSystem.LATN);
        assertEquals("42", NumberFormats.number().apply(42),
                "the platform wrote ٤٢ on its own; the override must fold it back");

        I18n.setLocale(Locale.ENGLISH);
        I18n.setNumberingSystem(NumberingSystem.DEVA);
        assertEquals("४२", NumberFormats.number().apply(42),
                "and the override localizes a platform that wrote ASCII");
    }

    @Test
    void aLocalizedZeroTrimsLikeAnAsciiOne() {
        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        assertEquals("٣٫٥", NumberFormats.number().apply(3.5),
                "the trailing localized zero is trimmed, not compared against ASCII '0'");
    }

    @Test
    void aFormatFollowsTheLocaleInScopeSeparatorsAndDigitsAlike() {
        // ADR 035: a chart inside an ar-locale subtree formats under that subtree, because
        // its passes hold the effective locale in scope and every format here reads
        // I18n.locale() at the moment it formats. Separators come with the language, not
        // only digits: the fold-then-localize pipeline runs on the platform's own output.
        Locale enclosing = I18n.pushScope(Locale.forLanguageTag("ar-EG"));
        try {
            assertEquals("٣٫٥", NumberFormats.number().apply(3.5));
        } finally {
            I18n.popScope(enclosing);
        }
        assertEquals("3.5", NumberFormats.number().apply(3.5),
                "outside the scope the process language formats as before");
    }

    @Test
    void aTwoCharacterCurrencySymbolStaysWholeUnderAnArabicAmount() {
        java.util.Currency real = java.util.Currency.getInstance("BRL");
        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        String arabic = NumberFormats.currency(real).apply(2499.97);
        assertTrue(arabic.contains("\u200ER$\u200E"),
                "the symbol is fenced by left-to-right marks so the bidi algorithm cannot split "
                        + "it into $R: " + arabic);
        assertTrue(arabic.contains("\u0662"), "and the digits are still Arabic-Indic: " + arabic);
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        String portuguese = NumberFormats.currency(real).apply(2499.97);
        assertEquals("R$\u00A02.499,97", portuguese, "a left-to-right text is returned as it came");
        assertEquals("R$ 5", NumberFormats.keepSymbolWhole("R$ 5", "R$"),
                "nothing right-to-left, nothing to fence");
        assertEquals("\u0662 \u200ER$\u200E", NumberFormats.keepSymbolWhole("\u0662 R$", "R$"));
        assertEquals("\u0662 \u20AC", NumberFormats.keepSymbolWhole("\u0662 \u20AC", "\u20AC"),
                "a one-sign symbol cannot be split and is left alone");
    }
}

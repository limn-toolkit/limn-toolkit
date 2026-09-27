package limn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number formats under ADR 033. Java's own locale data already wrote Arabic-Indic digits
 * under {@code ar-EG}, so what is asserted here is the part that was actually missing: a declared
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

    /**
     * Scientific notation in the language's own terms, which CLDR spells differently from one to
     * the next: the exponent sign is "E" in English and German, "×10^" in Swedish and "اس" in
     * Arabic, and Swedish writes a negative power with a true minus sign.
     */
    @Test
    void scientificNotationIsWrittenTheWayTheLanguageWritesIt() {
        I18n.setLocale(Locale.ENGLISH);
        var one = NumberFormats.scientific(1);
        assertEquals("1.2E25", one.apply(1.2e25));
        assertEquals("1E300", one.apply(1e300), "no trailing zero in the mantissa");
        assertEquals("1E6", one.apply(9.99e5), "a mantissa that rounds to ten moves the power");
        assertEquals("4.2E-4", one.apply(0.00042));
        assertEquals("-3.5E20", one.apply(-3.5e20));
        assertEquals("0", one.apply(0));
        assertEquals("-", one.apply(Double.NaN));
        assertEquals("1.797693E308", NumberFormats.scientific(6).apply(Double.MAX_VALUE));

        I18n.setLocale(Locale.GERMAN);
        assertEquals("1,2E25", one.apply(1.2e25));

        I18n.setLocale(Locale.forLanguageTag("sv"));
        assertEquals("1,2×10^25", one.apply(1.2e25));
        assertEquals("4,2×10^\u22124", one.apply(0.00042));

        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        assertEquals("١٫٢اس٢٥", one.apply(1.2e25), "the language's digits, separator and sign");
    }

    @Test
    void aDeclaredSystemWinsOverThePlatformsOwnSubstitution() {
        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        I18n.setNumberingSystem(NumberingSystem.LATN);
        assertEquals("42", NumberFormats.number().apply(42),
                "the platform writes ٤٢ on its own; the override must win over it");

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
    void aWiderPrecisionStillTrimsGroupsAndLocalizes() {
        assertEquals("0.105", NumberFormats.number(3).apply(0.105));
        assertEquals("0.11", NumberFormats.number(3).apply(0.11), "no trailing zero");
        assertEquals("0.115", NumberFormats.number(3).apply(0.1 + 0.005 * 3),
                "the arithmetic's noise past the precision is not written");
        assertEquals("1,234.5", NumberFormats.number(4).apply(1234.5));
        assertEquals("1,235", NumberFormats.number(0).apply(1234.5), "none at all rounds");
        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        assertEquals("٠٫٠٠١", NumberFormats.number(3).apply(0.001));
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

    /**
     * The separators follow the digits a number is shown in, not the platform's idea of the
     * language's digits. The JDK answers Arabic-Indic digits and the Arabic separators for a bare
     * {@code ar}, where the toolkit writes Latin digits, and formatting under the bare locale put
     * {@code ٫} and {@code ٬} between Latin digits.
     */
    @Test
    void aBareArabicWritesTheSeparatorsOfTheLatinDigitsItShows() {
        I18n.setLocale(Locale.forLanguageTag("ar"));
        assertEquals(NumberingSystem.LATN, I18n.numberingSystem(), "the premise: Latin digits");
        assertEquals("1,234.5", NumberFormats.number().apply(1234.5));
        assertEquals("-0.25", NumberFormats.number(2).apply(-0.25));
        assertEquals("0.105", NumberFormats.number(3).apply(0.105));
        assertEquals("1,234.50", NumberFormats.decimals(2).apply(1234.5));
        assertEquals("27.6k", NumberFormats.compact().apply(27600));
        assertEquals("1.2E25", NumberFormats.scientific(1).apply(1.2e25),
                "the exponent sign that goes with Latin digits, not the Arabic one");
        assertEquals("4.2E-4", NumberFormats.scientific(1).apply(0.00042));
        assertEquals("42.5%", NumberFormats.percent(1).apply(0.425));
    }

    /**
     * The other direction: in Afghanistan Uzbek is written in Extended Arabic-Indic digits, where
     * the JDK's data for {@code uz-AF} is Uzbek's Latin-script convention of a decimal comma and a
     * space between groups, which put a comma between Persian digits.
     */
    @Test
    void uzbekInAfghanistanWritesTheSeparatorsOfItsExtendedArabicDigits() {
        I18n.setLocale(Locale.forLanguageTag("uz-AF"));
        assertEquals(NumberingSystem.ARABEXT, I18n.numberingSystem(), "the premise");
        assertEquals("۱٬۲۳۴٫۵", NumberFormats.number().apply(1234.5));
        assertEquals("-۰٫۲۵", NumberFormats.number(2).apply(-0.25));
        assertEquals("۱٫۲×۱۰^۲۵", NumberFormats.scientific(1).apply(1.2e25));
        assertEquals("۴۲٫۵%", NumberFormats.percent(1).apply(0.425));
    }

    @Test
    void aDeclaredSystemBringsItsOwnSeparators() {
        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        I18n.setNumberingSystem(NumberingSystem.LATN);
        assertEquals("1,234.5", NumberFormats.number().apply(1234.5),
                "Latin digits declared over Egyptian Arabic come with the point and the comma");

        I18n.setLocale(Locale.ENGLISH);
        I18n.setNumberingSystem(NumberingSystem.ARAB);
        assertEquals("١٬٢٣٤٫٥", NumberFormats.number().apply(1234.5),
                "and Arabic-Indic digits declared over English with the Arabic separators");
    }

    /**
     * Bengali is a system the toolkit does not write yet, and the digit fold does not know it
     * either, so the platform's Bengali digits went through untouched while every other number
     * the toolkit writes under {@code bn}, a spinner's among them, is Latin.
     */
    @Test
    void aLanguageWhoseDigitsTheToolkitDoesNotWriteGetsLatinOnes() {
        I18n.setLocale(Locale.forLanguageTag("bn"));
        assertEquals("1,234.5", NumberFormats.number().apply(1234.5));
        assertEquals("42%", NumberFormats.percent(0).apply(0.42));
    }

    /**
     * Where the platform already writes the digits the toolkit shows, the locale is used as it
     * came. That is not only economy: the JDK answers the wrong separators for a few locales once
     * a numbering system is named on them, and Serbian in Bosnia is one, where naming the Latin
     * digits it already writes turns its decimal comma into a point.
     */
    @Test
    void whereThePlatformAlreadyWritesTheDigitsShownNothingMoves() {
        I18n.setLocale(Locale.forLanguageTag("sr-BA"));
        assertEquals("1.234,5", NumberFormats.number().apply(1234.5));
        assertEquals("1,2E25", NumberFormats.scientific(1).apply(1.2e25));

        I18n.setLocale(Locale.forLanguageTag("fa"));
        assertEquals("۱٬۲۳۴٫۵", NumberFormats.number().apply(1234.5));
        assertEquals("۴٫۲×۱۰^−۴", NumberFormats.scientific(1).apply(0.00042));

        I18n.setLocale(Locale.forLanguageTag("de"));
        assertEquals("1.234,5", NumberFormats.number().apply(1234.5));
        assertEquals("-0,25", NumberFormats.number(2).apply(-0.25));
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

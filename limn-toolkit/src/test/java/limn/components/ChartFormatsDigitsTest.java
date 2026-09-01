package limn.components;

import limn.components.chart.ChartFormats;
import limn.i18n.I18n;
import limn.i18n.NumberingSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The chart formats under ADR 033. Java's own locale data already wrote Arabic-Indic digits
 * under {@code ar}, so what is asserted here is the part that was actually missing: a declared
 * numbering system wins over the platform's substitution (the fold), and a localized zero trims
 * exactly as an ASCII one does (the defect the measurement found riding along).
 */
class ChartFormatsDigitsTest {

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
        I18n.setLocale(Locale.forLanguageTag("ar"));
        assertEquals("٤٢", ChartFormats.number().apply(42));

        I18n.setLocale(Locale.ENGLISH);
        assertEquals("42", ChartFormats.number().apply(42), "and the Latin default is unchanged");
    }

    @Test
    void aDeclaredSystemWinsOverThePlatformsOwnSubstitution() {
        I18n.setLocale(Locale.forLanguageTag("ar"));
        I18n.setNumberingSystem(NumberingSystem.LATN);
        assertEquals("42", ChartFormats.number().apply(42),
                "the platform wrote ٤٢ on its own; the override must fold it back");

        I18n.setLocale(Locale.ENGLISH);
        I18n.setNumberingSystem(NumberingSystem.DEVA);
        assertEquals("४२", ChartFormats.number().apply(42),
                "and the override localizes a platform that wrote ASCII");
    }

    @Test
    void aLocalizedZeroTrimsLikeAnAsciiOne() {
        I18n.setLocale(Locale.forLanguageTag("ar"));
        assertEquals("٣٫٥", ChartFormats.number().apply(3.5),
                "the trailing localized zero is trimmed, not compared against ASCII '0'");
    }

    @Test
    void aFormatFollowsTheLocaleInScopeSeparatorsAndDigitsAlike() {
        // ADR 035: a chart inside an ar-locale subtree formats under that subtree, because
        // its passes hold the effective locale in scope and every format here reads
        // I18n.locale() at the moment it formats. Separators come with the language, not
        // only digits: the fold-then-localize pipeline runs on the platform's own output.
        Locale enclosing = I18n.pushScope(Locale.forLanguageTag("ar"));
        try {
            assertEquals("٣٫٥", ChartFormats.number().apply(3.5));
        } finally {
            I18n.popScope(enclosing);
        }
        assertEquals("3.5", ChartFormats.number().apply(3.5),
                "outside the scope the process language formats as before");
    }
}

package limn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A counted sentence writes every number in it in the same digits: the count, which it formats
 * itself, and a number the caller passes, which {@code MessageFormat} formats.
 */
class PluralStringTest {

    private final List<StringBundle> registered = new ArrayList<>();
    private Locale original;

    @BeforeEach
    void rememberLocale() {
        original = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
    }

    /** Process-wide statics: a test that leaks its language breaks every later one. */
    @AfterEach
    void restore() {
        registered.forEach(I18n::removeBundle);
        registered.clear();
        I18n.setNumberingSystem(null);
        I18n.setLocale(original);
    }

    private void register(StringBundle bundle) {
        registered.add(bundle);
        I18n.addBundle(bundle);
    }

    /**
     * The caller's number is written in the digits the toolkit shows for the language, as the
     * count beside it is. The JDK answers Arabic-Indic digits for a bare {@code ar}, where the
     * toolkit writes Latin ones, and a declared system never reached {@code MessageFormat} at
     * all, so one sentence carried its two numbers in two systems.
     */
    @Test
    void aNumberTheCallerPassesIsWrittenInTheDigitsOfTheCount() {
        PluralString size = PluralString.of("test.plural.size",
                "{0,number} MB in {1} file", "{0,number} MB in {1} files");

        I18n.setLocale(Locale.forLanguageTag("ar"));
        assertEquals(NumberingSystem.LATN, I18n.numberingSystem(), "the premise: Latin digits");
        assertEquals("1,234.5 MB in 3 files", size.format(3, 1234.5));

        I18n.setLocale(Locale.ENGLISH);
        I18n.setNumberingSystem(NumberingSystem.ARAB);
        assertEquals("١٬٢٣٤٫٥ MB in ٣ files", size.format(3, 1234.5),
                "a declared system reaches the caller's number as it reaches the count");
    }

    /** The English a malformed translation falls back to writes the caller's number the same way. */
    @Test
    void theFallbackFormWritesTheCallersNumberInTheDigitsOfTheCount() {
        Locale arabic = Locale.forLanguageTag("ar");
        PluralString size = PluralString.of("test.plural.fallbackSize",
                "{0,number} MB in {1} file", "{0,number} MB in {1} files");
        register(PropertyBundle.of(arabic, Map.of(
                "test.plural.fallbackSize.few", "{0,number} ميغابايت في {1 ملفات")));
        I18n.setLocale(arabic);
        assertEquals("1,234.5 MB in 3 files", size.format(3, 1234.5));
    }
}

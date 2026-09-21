package limn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ADR 033's two halves at the unit level: which digits a locale writes, and that both string
 * scans return their argument untouched when there is nothing to do — the price the default
 * locale pays must be a comparison, not an allocation.
 */
class NumberingSystemTest {

    private Locale original;

    @BeforeEach
    void rememberLocale() {
        original = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
    }

    /** Process-wide statics: a test that leaks its digits breaks every later one. */
    @AfterEach
    void restore() {
        I18n.setNumberingSystem(null);
        I18n.setLocale(original);
    }

    @Test
    void theLocaleTableFollowsCldr() {
        // Corrected 2026-09-21, against the CLDR dump CldrLocaleFactsTest reads: this case
        // asserted that a bare "ar" writes Arabic-Indic digits and that the Maghreb is the
        // exception. CLDR says the opposite — Latin is the Arabic default and twenty-three
        // regions are the exception — so the table was inverted and this case was pinning it.
        assertEquals(NumberingSystem.LATN, NumberingSystem.forLocale(Locale.forLanguageTag("ar")),
                "a bare Arabic locale writes Latin digits, as every native application does");
        assertEquals(NumberingSystem.ARAB, NumberingSystem.forLocale(Locale.forLanguageTag("ar-EG")),
                "and Egypt is one of the twenty-three regions that do not");
        assertEquals(NumberingSystem.LATN, NumberingSystem.forLocale(Locale.forLanguageTag("ar-MA")),
                "the Maghreb writes Latin digits, along with the Gulf's AE and most of the world");
        assertEquals(NumberingSystem.LATN, NumberingSystem.forLocale(Locale.forLanguageTag("ar-AE")),
                "the United Arab Emirates too, which the Maghreb-shaped exception got wrong");
        assertEquals(NumberingSystem.ARABEXT, NumberingSystem.forLocale(Locale.forLanguageTag("fa")));
        assertEquals(NumberingSystem.ARABEXT, NumberingSystem.forLocale(Locale.forLanguageTag("ks")),
                "Kashmiri, which the first table did not name at all");
        assertEquals(NumberingSystem.ARAB, NumberingSystem.forLocale(Locale.forLanguageTag("sd")),
                "Sindhi writes Arabic-Indic digits at home");
        assertEquals(NumberingSystem.LATN, NumberingSystem.forLocale(Locale.forLanguageTag("sd-IN")),
                "and Latin ones in India, which is a fact no rule about scripts would predict");
        assertEquals(NumberingSystem.DEVA, NumberingSystem.forLocale(Locale.forLanguageTag("mr")),
                "Marathi really does default to Devanagari digits");
        assertEquals(NumberingSystem.LATN, NumberingSystem.forLocale(Locale.forLanguageTag("he")),
                "Hebrew is right-to-left and writes Latin digits: the axis and the digits are "
                        + "different facts");
        assertEquals(NumberingSystem.LATN, NumberingSystem.forLocale(Locale.forLanguageTag("hi")),
                "CLDR defaults Hindi to Latin digits, where its neighbour Marathi it does not");
        assertEquals(NumberingSystem.LATN, NumberingSystem.forLocale(Locale.forLanguageTag("bn")),
                "Bengali is a known gap: CLDR says beng and this enum does not carry it yet");
        assertEquals(NumberingSystem.LATN, NumberingSystem.forLocale(Locale.ENGLISH));
    }

    @Test
    void localizeRewritesDigitsAndOnlyDigits() {
        I18n.setLocale(Locale.forLanguageTag("ar-EG")); // a region that writes them
        assertEquals("٤٢.٥ ms", I18n.localizeDigits("42.5 ms"),
                "digits localize; the separator and the unit do not");
        I18n.setLocale(Locale.forLanguageTag("fa"));
        assertEquals("۴۲", I18n.localizeDigits("42"));
    }

    @Test
    void theDefaultLocalePaysNoAllocation() {
        String text = "42.5 ms";
        assertSame(text, I18n.localizeDigits(text), "LATN returns its argument");
        I18n.setLocale(Locale.forLanguageTag("ar-EG")); // a region that writes them
        String noDigits = "no digits here";
        assertSame(noDigits, I18n.localizeDigits(noDigits), "and so does a string with none");
        assertSame(noDigits, I18n.toAsciiDigits(noDigits));
    }

    @Test
    void everyKnownSystemFoldsBackToAscii() {
        assertEquals("42", I18n.toAsciiDigits("٤٢"));
        assertEquals("42", I18n.toAsciiDigits("۴۲"));
        assertEquals("42", I18n.toAsciiDigits("४२"));
        assertEquals("420", I18n.toAsciiDigits("٤2०"),
                "a string mixing systems folds too: a paste does not choose its digits");
        assertEquals("07:30", I18n.toAsciiDigits("٠٧:٣٠"));
    }

    @Test
    void anOverrideWinsAndBumpsTheEpoch() {
        long before = I18n.epoch();
        I18n.setNumberingSystem(NumberingSystem.DEVA);
        assertNotEquals(before, I18n.epoch(),
                "every formatted number on screen just changed, so the epoch must say so");
        assertEquals(NumberingSystem.DEVA, I18n.numberingSystem(),
                "the override wins over an English locale");
        assertEquals("४२", I18n.localizeDigits("42"));

        long declared = I18n.epoch();
        I18n.setNumberingSystem(null);
        assertNotEquals(declared, I18n.epoch(), "clearing is a change too");
        assertEquals(NumberingSystem.LATN, I18n.numberingSystem(), "and the locale decides again");
    }
}

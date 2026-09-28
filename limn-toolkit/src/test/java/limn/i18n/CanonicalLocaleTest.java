package limn.i18n;

import limn.i18n.internal.LanguageAliases;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link I18n#canonical} answers what the toolkit reads a locale as, so an application deciding by
 * language gets the toolkit's answer: the documented examples, the same instance for a locale
 * nothing replaces, and agreement with the toolkit's own reading on every alias and region row of
 * the dump that asked ICU for them.
 */
class CanonicalLocaleTest {

    @Test
    void anAliasIsTheLanguageAndRegionIcuReadsItAs() {
        assertEquals(Locale.forLanguageTag("ja"), canonical("jpn"));
        assertEquals(Locale.forLanguageTag("fr-CA"), canonical("fra-CA"));
        assertEquals(Locale.forLanguageTag("fa-AF"), canonical("prs"));
        assertEquals(Locale.forLanguageTag("fa-IR"), canonical("prs-IR"),
                "the locale's own region wins over the one the replacement carries");
        assertEquals(Locale.forLanguageTag("sr-Latn-RS"), canonical("sh-RS"));
        assertEquals(Locale.forLanguageTag("de-DE"), canonical("de-276"));
        assertEquals(Locale.forLanguageTag("uz-UZ"), canonical("uz-SU"));
        assertEquals(Locale.forLanguageTag("he"), canonical("iw"));
        assertEquals(Locale.forLanguageTag("fa-u-nu-latn"), canonical("pes-u-nu-latn"),
                "extensions are kept");
    }

    @Test
    void aLocaleNothingReplacesComesBackAsTheSameInstance() {
        for (String tag : List.of("ja", "en-US", "pt-BR", "zh-Hant-TW", "fr-CA-u-nu-latn",
                "es-419", "xx", "qaa-Qaaa-QM", "und")) {
            Locale locale = Locale.forLanguageTag(tag);
            assertSame(locale, I18n.canonical(locale), tag);
        }
        assertSame(Locale.ROOT, I18n.canonical(Locale.ROOT));
    }

    @Test
    void nothingIsAddedTheLocaleDoesNotImply() {
        Locale japanese = canonical("jpn");
        assertEquals("", japanese.getScript(), "no likely script is filled in");
        assertEquals("", japanese.getCountry(), "no likely region is filled in");
    }

    @Test
    void aReplacedLocaleIsCachedAndReadingItAgainChangesNothing() {
        Locale asked = Locale.forLanguageTag("prs-IR");
        Locale once = I18n.canonical(asked);
        assertSame(once, I18n.canonical(Locale.forLanguageTag("prs-IR")));
        assertSame(once, I18n.canonical(once));
    }

    /**
     * Every alias and region row, the code alone and in a tag, through the public method and the
     * toolkit's own reading: the two answers are equal, and the answer is canonical already.
     */
    @Test
    void theMethodAgreesWithTheToolkitOnEveryRow() throws Exception {
        List<String> wrong = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        for (String[] row : CldrAliasRows.read()) {
            tags.add(row[0]);
        }
        for (String[] row : CldrAliasRows.regions()) {
            tags.add(row[0].contains("-") ? row[0] : "und-" + row[0]);
        }
        for (String tag : tags) {
            Locale locale = Locale.forLanguageTag(tag);
            Locale ours = I18n.canonical(locale);
            if (!ours.equals(LanguageAliases.resolve(locale))) {
                wrong.add(tag + ": " + ours.toLanguageTag() + " where the toolkit reads "
                        + LanguageAliases.resolve(locale).toLanguageTag());
            }
            if (I18n.canonical(ours) != ours) {
                wrong.add(tag + ": " + ours.toLanguageTag() + " is itself replaced");
            }
        }
        assertEquals(List.of(), wrong);
    }

    private static Locale canonical(String tag) {
        return I18n.canonical(Locale.forLanguageTag(tag));
    }
}

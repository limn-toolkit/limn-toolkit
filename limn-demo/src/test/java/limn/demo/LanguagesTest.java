package limn.demo;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code --locale} the toolkit reads as a language the demo ships is that language here too: in
 * the language menu, and in whether a capture waits for the face that draws its script.
 *
 * <p>{@code jpn}, {@code heb} and {@code zho} are Japanese, Hebrew and Chinese to the toolkit,
 * which translates, formats and lays them out as {@code ja}, {@code he} and {@code zh}; the demo
 * matching on the code as typed selected English in the menu and photographed the
 * {@code .notdef} boxes that precede the CJK and Hebrew faces.
 */
class LanguagesTest {

    @Test
    void anAliasSelectsTheLanguageItStandsFor() {
        assertEquals(entry("ja"), Languages.indexOf(Locale.forLanguageTag("jpn")));
        assertEquals(entry("he"), Languages.indexOf(Locale.forLanguageTag("heb")));
        assertEquals(entry("zh-Hans"), Languages.indexOf(Locale.forLanguageTag("zho")));
        assertEquals(entry("zh-Hant"), Languages.indexOf(Locale.forLanguageTag("zho-Hant")),
                "the full tag is matched after the language is read, so the script still picks");
        assertEquals(entry("pt"), Languages.indexOf(Locale.forLanguageTag("por")),
                "por is pt, which is its own entry, not the first Portuguese one");
        assertEquals(entry("fr"), Languages.indexOf(Locale.forLanguageTag("fra-FR")));
    }

    @Test
    void theCurrentCodesStillSelectTheirEntries() {
        assertEquals(entry("ja"), Languages.indexOf(Locale.forLanguageTag("ja")));
        assertEquals(entry("pt-BR"), Languages.indexOf(Locale.forLanguageTag("pt-BR")));
        assertEquals(entry("pt"), Languages.indexOf(Locale.forLanguageTag("pt")));
        assertEquals(entry("zh-Hant"), Languages.indexOf(Locale.forLanguageTag("zh-Hant")));
        assertEquals(0, Languages.indexOf(Locale.forLanguageTag("sv")),
                "a language the demo has no translation for is English");
    }

    @Test
    void anAliasOfAScriptTheFallbackFacesDrawWaitsForThem() {
        assertTrue(Main.localeNeedsAFallbackFace(Locale.forLanguageTag("jpn")));
        assertTrue(Main.localeNeedsAFallbackFace(Locale.forLanguageTag("heb")));
        assertTrue(Main.localeNeedsAFallbackFace(Locale.forLanguageTag("zho")));
        assertTrue(Main.localeNeedsAFallbackFace(Locale.forLanguageTag("ja")));
        assertFalse(Main.localeNeedsAFallbackFace(Locale.forLanguageTag("fra")),
                "French is Latin, which the default face draws");
        assertFalse(Main.localeNeedsAFallbackFace(null));
    }

    private static int entry(String tag) {
        return Languages.LOCALES.indexOf(Locale.forLanguageTag(tag));
    }
}

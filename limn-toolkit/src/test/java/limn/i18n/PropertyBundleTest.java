package limn.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyBundleTest {

    private final PropertyBundle bundle = PropertyBundle.family("/limn/i18n/test-domain");

    @Test
    void aRegionalFileIsMergedOnTopOfItsLanguage() {
        Locale ptBr = Locale.forLanguageTag("pt-BR");
        bundle.prepare(ptBr);
        assertEquals("Guardar", bundle.lookup("test.shared", ptBr),
                "a key only the language file has still resolves");
        assertEquals("Trem", bundle.lookup("test.regional", ptBr),
                "and the regional file wins where they disagree");
    }

    @Test
    void theLanguageFileStandsAloneForItsOwnLocale() {
        Locale pt = Locale.forLanguageTag("pt");
        bundle.prepare(pt);
        assertEquals("Guardar", bundle.lookup("test.shared", pt));
        assertEquals("Comboio", bundle.lookup("test.regional", pt));
    }

    @Test
    void aLanguageWithNoFileIsNotAnError() {
        Locale swahili = Locale.forLanguageTag("sw");
        bundle.prepare(swahili);
        assertNull(bundle.lookup("test.shared", swahili),
                "no file means no answer, which is how the English fallback is reached");
    }

    @Test
    void nonAsciiSurvivesBecauseTheFileIsReadAsUtf8() {
        Locale japanese = Locale.JAPANESE;
        bundle.prepare(japanese);
        assertEquals("保存", bundle.lookup("test.shared", japanese));
    }

    @Test
    void aMachinesChineseLocaleFindsTheScriptItIsFiledUnder() {
        // zh-CN and zh-TW are what a machine reports; zh-Hans/zh-Hant is what a
        // translation is filed under, and they are not interchangeable.
        assertTrue(candidates("zh-CN").contains("limn/i18n/test-domain_zh-Hans.properties"));
        assertTrue(candidates("zh-SG").contains("limn/i18n/test-domain_zh-Hans.properties"));
        assertTrue(candidates("zh-TW").contains("limn/i18n/test-domain_zh-Hant.properties"));
        assertTrue(candidates("zh-HK").contains("limn/i18n/test-domain_zh-Hant.properties"));
        assertTrue(candidates("zh").contains("limn/i18n/test-domain_zh-Hans.properties"),
                "bare Chinese means the simplified form, which is what most users get");

        Locale zhCn = Locale.forLanguageTag("zh-CN");
        bundle.prepare(zhCn);
        assertEquals("保存", bundle.lookup("test.shared", zhCn),
                "so a zh-CN machine actually reads the zh-Hans file");
    }

    @Test
    void candidatesRunGeneralToSpecificAndAcceptBothSpellings() {
        List<String> candidates = candidates("pt-BR");
        assertEquals("limn/i18n/test-domain.properties", candidates.get(0));
        assertEquals("limn/i18n/test-domain_pt.properties", candidates.get(1));
        assertTrue(candidates.contains("limn/i18n/test-domain_pt-BR.properties"));
        assertTrue(candidates.contains("limn/i18n/test-domain_pt_BR.properties"),
                "the JDK spells the same locale with an underscore");
    }

    @Test
    void aDirectMapBundleAnswersOnlyForItsLanguage() {
        StringBundle map = PropertyBundle.of(Locale.GERMAN, java.util.Map.of("k", "Wert"));
        assertEquals("Wert", map.lookup("k", Locale.GERMAN));
        assertEquals("Wert", map.lookup("k", Locale.forLanguageTag("de-AT")));
        assertNull(map.lookup("k", Locale.FRENCH));
    }

    @Test
    void onlyTheLocaleInUseIsResident() {
        Locale pt = Locale.forLanguageTag("pt");
        Locale ja = Locale.JAPANESE;

        bundle.prepare(pt);
        assertEquals(pt, bundle.loadedLocale());
        int ptSize = bundle.residentSize();
        assertTrue(ptSize > 0);

        bundle.prepare(ja);
        assertEquals(ja, bundle.loadedLocale(), "the new language replaces the old one");
        assertEquals(1, bundle.residentSize(),
                "the Japanese fixture has one key; nothing of Portuguese is still held");

        bundle.prepare(pt);
        assertEquals(ptSize, bundle.residentSize(), "coming back re-reads the file");
    }

    @Test
    void preparingTheSameLocaleTwiceDoesNotReload() {
        Locale pt = Locale.forLanguageTag("pt");
        bundle.prepare(pt);
        Locale first = bundle.loadedLocale();
        bundle.prepare(pt);
        assertSame(first, bundle.loadedLocale());
    }

    private List<String> candidates(String tag) {
        return bundle.candidates(Locale.forLanguageTag(tag));
    }
}

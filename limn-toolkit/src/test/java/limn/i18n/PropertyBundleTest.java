package limn.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void onlyPreparedLocalesAreResidentAndReleaseDropsOne() {
        // Since ADR 035 two languages can genuinely be on screen at once, so preparing a
        // second locale keeps the first: the one-table-per-reader promise is now enforced
        // by I18n's retain ledger calling release, not by prepare evicting its neighbour.
        Locale pt = Locale.forLanguageTag("pt");
        Locale ja = Locale.JAPANESE;

        bundle.prepare(pt);
        assertTrue(bundle.resident(pt));
        int ptSize = bundle.residentSize(pt);
        assertTrue(ptSize > 0);

        bundle.prepare(ja);
        assertTrue(bundle.resident(pt), "a second language does not evict the first: a "
                + "subtree may still be reading it every frame");
        assertEquals(1, bundle.residentSize(ja), "the Japanese fixture has one key");
        assertEquals(2, bundle.residentLocales());

        bundle.release(pt);
        assertFalse(bundle.resident(pt), "released: nothing of Portuguese is still held");
        assertEquals(1, bundle.residentLocales());

        bundle.prepare(pt);
        assertEquals(ptSize, bundle.residentSize(pt), "coming back re-reads the file");
    }

    @Test
    void preparingTheSameLocaleTwiceDoesNotReload() {
        Locale pt = Locale.forLanguageTag("pt");
        bundle.prepare(pt);
        assertEquals(1, bundle.residentLocales());
        bundle.prepare(pt);
        assertEquals(1, bundle.residentLocales());
    }

    @Test
    void aLookupForAnUnpreparedLocaleLoadsOnceAndKeepsIt() {
        // The safety net under a caller that bypassed retain: loaded and KEPT, because the
        // ask will repeat, and a lookup that swapped the resident table would make two
        // languages alternating in one frame re-read both files per frame, forever.
        Locale pt = Locale.forLanguageTag("pt");
        assertFalse(bundle.resident(pt));
        assertEquals("Guardar", bundle.lookup("test.shared", pt));
        assertTrue(bundle.resident(pt), "the lazy load is cached, not thrown away");
    }

    private List<String> candidates(String tag) {
        return bundle.candidates(Locale.forLanguageTag(tag));
    }
}

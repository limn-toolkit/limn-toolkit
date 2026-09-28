package limn.i18n;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /**
     * Persian named by one of its other codes reads the Persian files: {@code pes}, the ISO
     * 639-3 code for Iranian Persian, is {@code fa} to ICU, and {@code prs}, Dari, is
     * {@code fa-AF}, which a translation for Afghanistan is filed under. Java's {@link Locale}
     * keeps both codes as they are, so a family that built its file names from the language
     * subtag looked for {@code _pes} and {@code _prs} files nobody writes, and the English stood.
     * The fixtures carry the word Iran and Afghanistan write differently, hospital, so the
     * regional file shows whether it was read.
     */
    @Test
    void persianNamedByAnotherCodeReadsThePersianFiles() {
        Locale pes = Locale.forLanguageTag("pes");
        assertEquals("ذخیره", bundle.lookup("test.shared", pes));
        assertEquals("بیمارستان", bundle.lookup("test.regional", pes));

        Locale prs = Locale.forLanguageTag("prs");
        assertEquals("ذخیره", bundle.lookup("test.shared", prs),
                "Dari reads the Persian file first");
        assertEquals("شفاخانه", bundle.lookup("test.regional", prs),
                "and Afghanistan's on top of it, since prs is fa-AF");

        Locale prsInIran = Locale.forLanguageTag("prs-IR");
        assertEquals("بیمارستان", bundle.lookup("test.regional", prsInIran),
                "a region the locale names wins over the one its code stands for: prs-IR is fa-IR");
    }

    /**
     * And every other code ICU reads as another language reads that language's files, in the
     * order the replacement would: {@code heb} finds the Hebrew catalogs this module ships,
     * {@code cmn} the Simplified Chinese ones, {@code sh} the Serbian in Latin script an
     * application files under {@code sr-Latn}. Asked of every {@code alias} row of
     * {@code cldr-locale-facts.txt}.
     */
    @Test
    void anAliasReadsItsReplacementsFiles() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String[] alias : CldrAliasRows.read()) {
            List<String> ours = candidates(alias[0]);
            List<String> replacement = candidates(alias[1]);
            if (!ours.equals(replacement)) {
                wrong.add(alias[0] + " is " + alias[1] + ", which reads " + replacement
                        + ", we read " + ours);
            }
        }
        assertEquals(List.of(), wrong, "an alias reads other files than its replacement");
    }

    @Test
    void aDirectMapBundleAnswersItsLanguageUnderAnyOfItsCodes() {
        StringBundle persian = PropertyBundle.of(Locale.forLanguageTag("fa"), Map.of("k", "ذخیره"));
        assertEquals("ذخیره", persian.lookup("k", Locale.forLanguageTag("pes")));
        assertEquals("ذخیره", persian.lookup("k", Locale.forLanguageTag("prs-AF")));

        StringBundle filedUnderAnAlias = PropertyBundle.of(Locale.forLanguageTag("fas"),
                Map.of("k", "ذخیره"));
        assertEquals("ذخیره", filedUnderAnAlias.lookup("k", Locale.forLanguageTag("fa-IR")),
                "a bundle built under an alias answers for the language it stands for");
        assertNull(filedUnderAnAlias.lookup("k", Locale.forLanguageTag("ar")));
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

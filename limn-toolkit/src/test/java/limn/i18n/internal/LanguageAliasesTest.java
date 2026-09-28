package limn.i18n.internal;

import limn.i18n.CldrAliasRows;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The codes the toolkit reads as another language, against the dump that asked ICU for them.
 *
 * <p>{@code language-aliases.txt} ships in the jar and {@code cldr-locale-facts.txt} does not, so
 * the rows are copied from one to the other, and a copy drifts unless something holds it to what
 * it copied: a dump regenerated under a newer ICU with a new alias, and a resource nobody
 * regenerated, would leave that code read as a language nobody listed.
 */
class LanguageAliasesTest {

    @Test
    void theShippedRowsAreTheDumpsAliasAndRegionRowsInItsOrder() throws Exception {
        List<String> dump = new ArrayList<>();
        for (String[] row : CldrAliasRows.read()) {
            dump.add("alias " + row[0] + " " + row[1]);
        }
        for (String[] row : CldrAliasRows.regions()) {
            dump.add("region " + row[0] + " " + row[1]);
        }
        List<String> shipped = new ArrayList<>();
        try (InputStream in = LanguageAliases.class.getResourceAsStream("language-aliases.txt")) {
            assertNotNull(in, "language-aliases.txt is not beside LanguageAliases");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    shipped.add(line);
                }
            }
        }
        assertEquals(dump, shipped);
    }

    /**
     * Every row, read both ways: the code alone resolves to its replacement, and its language
     * subtag to the replacement's. And nothing a code is replaced with is replaced again, so
     * reading a locale twice reads it as once.
     */
    @Test
    void everyCodeIsReadAsItsReplacement() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String[] row : CldrAliasRows.read()) {
            Locale replacement = Locale.forLanguageTag(row[1]);
            Locale ours = LanguageAliases.resolve(Locale.forLanguageTag(row[0]));
            if (!replacement.equals(ours)) {
                wrong.add(row[0] + " is " + row[1] + ", we read " + ours.toLanguageTag());
            }
            if (!replacement.getLanguage().equals(LanguageAliases.language(row[0]))) {
                wrong.add(row[0] + " is " + row[1] + ", we read its language as "
                        + LanguageAliases.language(row[0]));
            }
            if (LanguageAliases.resolve(replacement) != replacement) {
                wrong.add(row[1] + " is itself read as another language");
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** What the locale names wins; the replacement fills in only what it leaves out. */
    @Test
    void theLocalesOwnScriptAndRegionWinOverTheReplacements() {
        assertEquals(Locale.forLanguageTag("fa-IR"), resolve("prs-IR"));
        assertEquals(Locale.forLanguageTag("fa-AF"), resolve("prs"));
        assertEquals(Locale.forLanguageTag("sr-Cyrl"), resolve("sh-Cyrl"));
        assertEquals(Locale.forLanguageTag("sr-Latn-RS"), resolve("sh-RS"));
        assertEquals(Locale.forLanguageTag("sr-Latn-ME"), resolve("cnr-Latn"));
        assertEquals(Locale.forLanguageTag("fr-CA"), resolve("fra-CA"));
    }

    /**
     * A region named by its UN number is the two-letter region ICU reads the number as:
     * {@code az-364} is {@code az-IR}, {@code ar-818} is {@code ar-EG}. {@link Locale} keeps the
     * number as it is given, so a table keyed on the two-letter region, the toolkit's own or the
     * platform's, answered such a locale as a region nobody listed. A number that stood for a
     * country that split is the one of its replacements the language, in its script, is likely to
     * mean: {@code 810}, the Soviet Union, is {@code RU}, {@code uz-810} is {@code UZ}, and
     * {@code und-Armn-172} is {@code AM}. Asked of every {@code region} row.
     */
    @Test
    void aNumericRegionIsReadAsTheRegionIcuReadsItAs() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String[] row : CldrAliasRows.regions()) {
            Locale asked = Locale.forLanguageTag(row[0].contains("-") ? row[0] : "und-" + row[0]);
            Locale expected = new Locale.Builder().setLocale(asked).setRegion(row[1]).build();
            Locale ours = LanguageAliases.resolve(asked);
            if (!expected.equals(ours)) {
                wrong.add(asked.toLanguageTag() + " is " + expected.toLanguageTag() + ", we read "
                        + ours.toLanguageTag());
            }
        }
        assertEquals(List.of(), wrong);

        assertEquals(Locale.forLanguageTag("az-IR"), resolve("az-364"));
        assertEquals(Locale.forLanguageTag("ar-EG"), resolve("ar-818"));
        assertEquals(Locale.forLanguageTag("pa-PK"), resolve("pa-586"));
        assertEquals(Locale.forLanguageTag("uz-UZ"), resolve("uz-810"));
        assertEquals(Locale.forLanguageTag("uz-Arab-RU"), resolve("uz-Arab-810"),
                "Uzbek in Arabic script means Afghanistan, which did not replace the Soviet Union");
        assertEquals(Locale.forLanguageTag("qaa-Armn-RU"), resolve("qaa-Armn-172"),
                "a language ICU does not know takes the first region, whatever its script");
    }

    /**
     * A code ICU reads as another language picks among a number's regions as its replacement
     * does, since ICU replaces the language first: {@code hye-172}, Armenian by its ISO 639-2
     * code, is {@code hy-AM}, and {@code prs-364}, Dari in Iran, is {@code fa-IR}. Asked of every
     * alias in every number some row lets its replacement's language pick a region for.
     */
    @Test
    void anAliasPicksANumbersRegionAsItsReplacementDoes() throws Exception {
        Map<String, String> picked = new HashMap<>();
        for (String[] row : CldrAliasRows.regions()) {
            String[] parts = row[0].split("-");
            if (parts.length == 2) {
                picked.put(row[0], row[1]);
            }
        }
        List<String> wrong = new ArrayList<>();
        int asked = 0;
        for (String[] alias : CldrAliasRows.read()) {
            Locale replacement = Locale.forLanguageTag(alias[1]);
            if (!replacement.getScript().isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> row : picked.entrySet()) {
                String[] parts = row.getKey().split("-");
                if (!parts[0].equals(replacement.getLanguage())) {
                    continue;
                }
                asked++;
                Locale ours = resolve(alias[0] + "-" + parts[1]);
                Locale expected = new Locale.Builder().setLanguage(replacement.getLanguage())
                        .setRegion(row.getValue()).build();
                if (!expected.equals(ours)) {
                    wrong.add(alias[0] + "-" + parts[1] + " is " + expected.toLanguageTag()
                            + ", we read " + ours.toLanguageTag());
                }
            }
        }
        assertTrue(asked >= 20, "only " + asked + " aliased tags pick a number's region");
        assertEquals(List.of(), wrong);
        assertEquals(Locale.forLanguageTag("hy-AM"), resolve("hye-172"));
        assertEquals(Locale.forLanguageTag("fa-IR"), resolve("prs-364"));
    }

    @Test
    void variantsAndExtensionsAreKept() {
        assertEquals(Locale.forLanguageTag("fa-u-nu-latn"), resolve("pes-u-nu-latn"));
        assertEquals(Locale.forLanguageTag("de-DE-1996"), resolve("deu-DE-1996"));
        assertEquals(Locale.forLanguageTag("fr-x-test"), resolve("fra-x-test"));
    }

    /**
     * A variant the builder refuses, which only a {@code Locale} constructor can make, keeps the
     * language's replacement and the region; there is no script such a locale can carry.
     */
    @Test
    void aLegacyVariantKeepsTheReplacementAndItsRegion() {
        assertEquals(new Locale("fa", "AF", "x"), LanguageAliases.resolve(new Locale("prs", "", "x")));
        assertEquals(new Locale("sr", "RS", "x"), LanguageAliases.resolve(new Locale("sh", "RS", "x")));
    }

    @Test
    void aLanguageThatIsNoAliasIsReturnedAsItCame() {
        Locale french = Locale.forLanguageTag("fr-FR");
        assertSame(french, LanguageAliases.resolve(french));
        Locale latinAmerica = Locale.forLanguageTag("es-419");
        assertSame(latinAmerica, LanguageAliases.resolve(latinAmerica),
                "a number that names an area and no country is kept, as ICU keeps it");
        assertSame(Locale.ROOT, LanguageAliases.resolve(Locale.ROOT));
        assertEquals("fr", LanguageAliases.language("fr"));
        assertEquals("", LanguageAliases.language(""));
        assertEquals("qaa", LanguageAliases.language("qaa"), "a private-use code is its own");
    }

    /**
     * The spellings {@code Locale} answers for Hebrew, Yiddish and Indonesian on a JVM started
     * with {@code -Djava.locale.useOldISOCodes=true}, which no test here can start, read as the
     * current codes, so a table keyed through {@link LanguageAliases#language} holds only those.
     */
    @Test
    void theLegacySpellingsAreReadAsTheCurrentCodes() {
        assertEquals("he", LanguageAliases.language("iw"));
        assertEquals("yi", LanguageAliases.language("ji"));
        assertEquals("id", LanguageAliases.language("in"));
    }

    @Test
    void aLocaleResolvedTwiceIsTheSameInstance() {
        Locale pes = Locale.forLanguageTag("pes-IR");
        assertSame(LanguageAliases.resolve(pes), LanguageAliases.resolve(pes),
                "a formatter asks on every number it writes and must not build a locale each time");
    }

    private static Locale resolve(String tag) {
        return LanguageAliases.resolve(Locale.forLanguageTag(tag));
    }
}

package limn.i18n;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static limn.i18n.PluralRules.Category.FEW;
import static limn.i18n.PluralRules.Category.MANY;
import static limn.i18n.PluralRules.Category.ONE;
import static limn.i18n.PluralRules.Category.OTHER;
import static limn.i18n.PluralRules.Category.TWO;
import static limn.i18n.PluralRules.Category.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plural rules, at the numbers where a wrong rule shows.
 *
 * <p><b>This test is the only guard there is.</b> {@link PluralRules} transcribes CLDR's
 * cardinal rules by hand — the toolkit has no runtime dependency and the JDK exposes no plural
 * data, so nothing in this tree can check the rules against the source they came from. What can
 * be checked is that they answer what a speaker of each language would answer, at the numbers
 * where the families differ from one another: 1, 2, 5, 11, 21, 22, 25, 101 and 111.
 *
 * <p>Those numbers are not decoration. 21 is where Polish and Russian part company — "21
 * elementów" takes the many form and "21 элемент" takes the one form — 11 is where Russian's
 * "ends in 1" rule is overruled by its "11 to 14" rule, and 101 and 111 are where both of those
 * repeat two hundreds up. A rule written from the shape of the first ten integers passes none of
 * them.
 */
class PluralRulesTest {

    /** The numbers a rule has to get right, in the order the failure message reads best. */
    private static final List<Long> PROBES = List.of(0L, 1L, 2L, 3L, 5L, 10L, 11L, 12L, 14L, 15L,
            20L, 21L, 22L, 25L, 100L, 101L, 102L, 111L, 121L);

    @Test
    void aLanguageWithNoNumberInflectionAnswersOneFormForEverything() {
        for (String tag : List.of("ja", "ko", "vi", "zh-Hans", "zh-Hant", "id")) {
            Locale locale = Locale.forLanguageTag(tag);
            for (long n : PROBES) {
                assertEquals(OTHER, PluralRules.select(locale, n), tag + " at " + n);
            }
            assertEquals(EnumSet.of(OTHER), PluralRules.integerCategories(locale),
                    tag + " owes one form");
        }
    }

    @Test
    void englishAndItsFamilyInflectAtOneAlone() {
        for (String tag : List.of("en", "de", "es", "it", "nl", "tr")) {
            Locale locale = Locale.forLanguageTag(tag);
            assertEquals(OTHER, PluralRules.select(locale, 0), tag + " at 0");
            assertEquals(ONE, PluralRules.select(locale, 1), tag + " at 1");
            for (long n : List.of(2L, 5L, 11L, 21L, 101L)) {
                assertEquals(OTHER, PluralRules.select(locale, n), tag + " at " + n);
            }
            assertEquals(EnumSet.of(ONE, OTHER), PluralRules.integerCategories(locale), tag);
        }
    }

    /** French, Portuguese and Hindi read zero as singular: "0 élément", not "0 éléments". */
    @Test
    void frenchPortugueseAndHindiCountZeroAsOne() {
        for (String tag : List.of("fr", "pt", "pt-BR", "hi")) {
            Locale locale = Locale.forLanguageTag(tag);
            assertEquals(ONE, PluralRules.select(locale, 0), tag + " at 0");
            assertEquals(ONE, PluralRules.select(locale, 1), tag + " at 1");
            assertEquals(OTHER, PluralRules.select(locale, 2), tag + " at 2");
            assertEquals(OTHER, PluralRules.select(locale, 21), tag + " at 21");
        }
    }

    /** Hebrew has a dual, and nothing else above it. */
    @Test
    void hebrewHasADual() {
        for (Locale locale : List.of(Locale.forLanguageTag("he"), new Locale("iw"))) {
            assertEquals(ONE, PluralRules.select(locale, 1), "he at 1");
            assertEquals(TWO, PluralRules.select(locale, 2), "he at 2");
            assertEquals(OTHER, PluralRules.select(locale, 3), "he at 3");
            assertEquals(OTHER, PluralRules.select(locale, 20), "he at 20");
            assertEquals(EnumSet.of(ONE, TWO, OTHER), PluralRules.integerCategories(locale),
                    "he owes three forms, whichever spelling of its tag the JVM hands us");
        }
    }

    /** Czech: one, then 2 to 4, then everything else — by value, with no last-digit rule. */
    @Test
    void czechCountsTwoToFourApart() {
        Locale cs = Locale.forLanguageTag("cs");
        assertEquals(OTHER, PluralRules.select(cs, 0));
        assertEquals(ONE, PluralRules.select(cs, 1));
        assertEquals(FEW, PluralRules.select(cs, 2));
        assertEquals(FEW, PluralRules.select(cs, 4));
        assertEquals(OTHER, PluralRules.select(cs, 5));
        assertEquals(OTHER, PluralRules.select(cs, 22), "22 is not 2: Czech reads the whole number");
        assertEquals(EnumSet.of(ONE, FEW, OTHER), PluralRules.integerCategories(cs));
    }

    /**
     * Polish and Russian look alike and differ at 21, which is the single number that proves a
     * rule was written for the right language: "21 elementów" is the many form and "21 элемент"
     * is the one form.
     */
    @Test
    void polishAndRussianPartCompanyAtTwentyOne() {
        Locale pl = Locale.forLanguageTag("pl");
        Locale ru = Locale.forLanguageTag("ru");
        assertEquals(MANY, PluralRules.select(pl, 21), "21 elementów");
        assertEquals(ONE, PluralRules.select(ru, 21), "21 элемент");
        assertEquals(ONE, PluralRules.select(pl, 1));
        assertEquals(ONE, PluralRules.select(ru, 1));
        assertEquals(MANY, PluralRules.select(pl, 101), "and it repeats: 101 elementów");
        assertEquals(ONE, PluralRules.select(ru, 101), "101 элемент");
    }

    /** The last two digits rule the last one: 11 to 14 are many in both Slavic families. */
    @Test
    void elevenToFourteenAreNotOneToFour() {
        for (String tag : List.of("pl", "ru", "uk")) {
            Locale locale = Locale.forLanguageTag(tag);
            assertEquals(MANY, PluralRules.select(locale, 11), tag + " at 11");
            assertEquals(MANY, PluralRules.select(locale, 12), tag + " at 12");
            assertEquals(MANY, PluralRules.select(locale, 14), tag + " at 14");
            assertEquals(FEW, PluralRules.select(locale, 22), tag + " at 22");
            assertEquals(FEW, PluralRules.select(locale, 102), tag + " at 102");
            assertEquals(MANY, PluralRules.select(locale, 111), tag + " at 111");
            assertEquals(MANY, PluralRules.select(locale, 5), tag + " at 5");
            assertEquals(EnumSet.of(ONE, FEW, MANY), PluralRules.integerCategories(locale),
                    tag + " reaches no fourth form with a whole number");
        }
    }

    /** Arabic uses all six, and the last two digits decide the top three. */
    @Test
    void arabicUsesEveryForm() {
        Locale ar = Locale.forLanguageTag("ar");
        assertEquals(ZERO, PluralRules.select(ar, 0));
        assertEquals(ONE, PluralRules.select(ar, 1));
        assertEquals(TWO, PluralRules.select(ar, 2));
        assertEquals(FEW, PluralRules.select(ar, 3));
        assertEquals(FEW, PluralRules.select(ar, 10));
        assertEquals(MANY, PluralRules.select(ar, 11));
        assertEquals(MANY, PluralRules.select(ar, 99));
        assertEquals(OTHER, PluralRules.select(ar, 100));
        assertEquals(OTHER, PluralRules.select(ar, 101), "101 % 100 = 1, which is not the one form");
        assertEquals(FEW, PluralRules.select(ar, 103));
        assertEquals(EnumSet.allOf(PluralRules.Category.class),
                PluralRules.integerCategories(ar));
    }

    /**
     * A language the table does not name takes English's shape, and that is a guess by
     * construction — recorded here so that adding a language is known to be a decision and not a
     * matter of dropping a file in.
     */
    @Test
    void anUnknownLanguageTakesEnglishsShape() {
        Locale swahili = Locale.forLanguageTag("sw");
        assertEquals(ONE, PluralRules.select(swahili, 1));
        assertEquals(OTHER, PluralRules.select(swahili, 2));
        assertEquals(EnumSet.of(ONE, OTHER), PluralRules.integerCategories(swahili));
    }

    /** A count's sign is not grammar: -3 items reads as 3 do. */
    @Test
    void aNegativeCountIsReadByItsMagnitude() {
        Locale ru = Locale.forLanguageTag("ru");
        for (long n : PROBES) {
            assertEquals(PluralRules.select(ru, n), PluralRules.select(ru, -n), "at " + n);
        }
    }

    /** Whatever the rule, a language always reaches every form it claims to owe. */
    @Test
    void everyFormALanguageOwesIsReachable() {
        for (String tag : List.of("en", "de", "es", "fr", "it", "nl", "pl", "cs", "tr", "ru",
                "uk", "id", "vi", "ja", "ko", "zh-Hans", "zh-Hant", "hi", "ar", "he", "pt",
                "pt-BR")) {
            Locale locale = Locale.forLanguageTag(tag);
            Set<PluralRules.Category> reached = new TreeSet<>();
            for (long n = 0; n <= 1000; n++) {
                reached.add(PluralRules.select(locale, n));
            }
            assertEquals(new TreeSet<>(PluralRules.integerCategories(locale)), reached,
                    tag + ": the forms it owes and the forms it answers must be one set");
        }
    }

    @Test
    void aCategorysSuffixIsWhatACatalogKeyEndsIn() {
        assertEquals("other", OTHER.suffix());
        assertEquals("few", FEW.suffix());
        assertTrue(PluralRules.integerCategories(Locale.ENGLISH).contains(OTHER));
    }
}

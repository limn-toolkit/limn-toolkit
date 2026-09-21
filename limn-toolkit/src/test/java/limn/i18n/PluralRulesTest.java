package limn.i18n;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * The plural rules, at the numbers where a wrong rule shows, and against CLDR itself.
 *
 * <p>{@link PluralRules} transcribes CLDR's cardinal rules by hand, because the toolkit has no
 * runtime dependency and the JDK exposes no plural data. Two things check it.
 * {@link #theTranscriptionAgreesWithCldr} is the one that would catch anything: it compares
 * every answer with ICU's, through a dump this repository keeps. The cases written out by hand
 * below are the ones a reader of this file should be able to see without running anything — the
 * numbers where the families differ from one another: 1, 2, 5, 11, 21, 22, 25, 101 and 111.
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
        for (String tag : List.of("en", "de", "nl", "tr")) {
            Locale locale = Locale.forLanguageTag(tag);
            assertEquals(OTHER, PluralRules.select(locale, 0), tag + " at 0");
            assertEquals(ONE, PluralRules.select(locale, 1), tag + " at 1");
            for (long n : List.of(2L, 5L, 11L, 21L, 101L)) {
                assertEquals(OTHER, PluralRules.select(locale, n), tag + " at " + n);
            }
            assertEquals(EnumSet.of(ONE, OTHER), PluralRules.integerCategories(locale), tag);
        }
    }

    /**
     * The Romance languages have a form of their own for a round million, and it is the case the
     * first transcription of these rules got wrong in five languages at once (2026-09-18, caught
     * by {@link #theTranscriptionAgreesWithCldr} before it shipped).
     *
     * <p>It is not a curiosity: Spanish says "un millón <b>de</b> elementos" and French "un
     * million <b>d\u2019</b>éléments", where 999.999 takes no preposition at all, so a sentence
     * built from the ordinary plural reads as broken Spanish at exactly the round numbers a
     * search result is most likely to show. Only an exact multiple counts — 1.000.001 is back to
     * the plain plural.
     */
    @Test
    void theRomanceLanguagesHaveAFormForARoundMillion() {
        for (String tag : List.of("es", "it", "fr", "pt", "pt-BR")) {
            Locale locale = Locale.forLanguageTag(tag);
            assertEquals(ONE, PluralRules.select(locale, 1), tag + " at 1");
            assertEquals(OTHER, PluralRules.select(locale, 999_999), tag + " at 999999");
            assertEquals(MANY, PluralRules.select(locale, 1_000_000), tag + " at a million");
            assertEquals(OTHER, PluralRules.select(locale, 1_000_001), tag + " at a million and one");
            assertEquals(MANY, PluralRules.select(locale, 2_000_000), tag + " at two million");
            assertEquals(EnumSet.of(ONE, MANY, OTHER), PluralRules.integerCategories(locale),
                    tag + " owes three forms, and the third is the millions one");
        }
        assertEquals(OTHER, PluralRules.select(Locale.ENGLISH, 1_000_000),
                "English has no such form, which is why it is easy to forget");
        assertEquals(OTHER, PluralRules.select(Locale.forLanguageTag("hi"), 1_000_000),
                "and neither does Hindi, which otherwise shares French's treatment of zero");
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
            // Past the dense range, because the Romance millions form lives out here and a
            // sweep of the first thousand integers would call it unreachable.
            for (long n : List.of(1_000_000L, 2_000_000L, 1_000_001L)) {
                reached.add(PluralRules.select(locale, n));
            }
            assertEquals(new TreeSet<>(PluralRules.integerCategories(locale)), reached,
                    tag + ": the forms it owes and the forms it answers must be one set");
        }
    }

    /**
     * The transcription, against CLDR itself.
     *
     * <p>This is what closes the risk the rest of this class could only describe. Node's
     * {@code Intl.PluralRules} <b>is</b> the CLDR data, read through ICU, and
     * {@code scripts/i18n/dump-cldr-plurals.mjs} writes its answers to
     * {@code cldr-cardinal.txt} — every integer from 0 to 1000 for every language the toolkit
     * ships a catalog for, plus the four {@link PluralRules} names without one, plus spot checks
     * up past a hundred million. The file's header records which ICU produced it, so a diff
     * after a regeneration reads as "CLDR changed" and not as "someone edited a golden".
     *
     * <p>The check does not run Node: the dump is checked in, so the suite has no toolchain it
     * did not have before. Regenerate it when a language is added to the toolkit, which is
     * exactly when a hand-written rule is most likely to be wrong.
     *
     * <p>First run, 2026-09-18: node v26.7.0, ICU 78.3 — all 26 languages agreed at every one of
     * those numbers, Hebrew included, which was the rule this class's author was least sure of.
     */
    @Test
    void theTranscriptionAgreesWithCldr() throws Exception {
        Path dump = Path.of("src/test/resources/limn/i18n/cldr-cardinal.txt");
        assertTrue(Files.exists(dump), "the CLDR dump is missing: " + dump.toAbsolutePath());
        List<String> lines = Files.readAllLines(dump, StandardCharsets.UTF_8);

        List<Long> spots = new ArrayList<>();
        List<String> disagreements = new ArrayList<>();
        int languages = 0;
        for (String line : lines) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] parts = line.split(" ");
            switch (parts[0]) {
                case "spots" -> {
                    for (int i = 1; i < parts.length; i++) {
                        spots.add(Long.parseLong(parts[i]));
                    }
                }
                case "dense" -> {
                    languages++;
                    Locale locale = Locale.forLanguageTag(parts[1]);
                    String cldr = parts[2];
                    for (int n = 0; n < cldr.length(); n++) {
                        compare(disagreements, parts[1], locale, n, cldr.charAt(n));
                    }
                }
                case "spot" -> {
                    Locale locale = Locale.forLanguageTag(parts[1]);
                    String cldr = parts[2];
                    assertEquals(spots.size(), cldr.length(),
                            parts[1] + ": the spot line does not match the spots line");
                    for (int i = 0; i < cldr.length(); i++) {
                        compare(disagreements, parts[1], locale, spots.get(i), cldr.charAt(i));
                    }
                }
                default -> throw new AssertionError("unknown line in the dump: " + line);
            }
        }
        assertTrue(languages >= 20, "the dump covers only " + languages + " languages");
        assertEquals(List.of(), disagreements,
                "limn.i18n.PluralRules disagrees with the CLDR data in the dump");
    }

    private static void compare(List<String> out, String tag, Locale locale, long n, char cldr) {
        char mine = switch (PluralRules.select(locale, n)) {
            case ZERO -> 'z';
            case ONE -> '1';
            case TWO -> '2';
            case FEW -> 'f';
            case MANY -> 'm';
            case OTHER -> 'o';
        };
        if (mine != cldr && out.size() < 20) {
            out.add(tag + " at " + n + ": CLDR says " + cldr + ", we say " + mine);
        }
    }

    @Test
    void aCategorysSuffixIsWhatACatalogKeyEndsIn() {
        assertEquals("other", OTHER.suffix());
        assertEquals("few", FEW.suffix());
        assertTrue(PluralRules.integerCategories(Locale.ENGLISH).contains(OTHER));
    }
}

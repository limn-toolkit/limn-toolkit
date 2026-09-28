package limn.components.date;

import limn.i18n.CldrAliasRows;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The region a language named alone is read for, against CLDR itself.
 *
 * <p>{@link CalendarChronology#likelyRegion} is a copy of ICU's likely subtags, and the week of
 * every locale without a region is read through it. The same argument as
 * {@code CldrLocaleFactsTest}, whose dump this reads: a table copied into Java by a person drifts
 * unless something holds it to what it copied. Lives in this package because the method is not
 * public.
 *
 * <p>The table covered the two-letter languages alone until 2026-09-28, so Central Kurdish
 * ({@code ckb}), Najdi Arabic ({@code ars}), Filipino ({@code fil}), Cantonese ({@code yue}) and
 * every other language with only a three-letter code had the week of no region at all.
 */
class LikelyRegionsTest {

    private static final Path DUMP = CldrAliasRows.FACTS;

    /** The copy the toolkit ships, beside the class that reads it. */
    private static final String SHIPPED = "/limn/i18n/internal/likely-regions.txt";

    @Test
    void theTableIsCldrsAndHoldsNothingElse() throws Exception {
        Map<String, String> cldr = cldr();
        assertTrue(cldr.size() > 5000, "the dump carries only " + cldr.size() + " languages");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> row : cldr.entrySet()) {
            String ours = CalendarChronology.likelyRegion(row.getKey());
            if (!row.getValue().equals(ours)) {
                wrong.add(row.getKey() + ": CLDR says " + row.getValue() + ", we say " + ours);
            }
        }
        assertEquals(List.of(), wrong.size() > 40 ? wrong.subList(0, 40) : wrong,
                wrong.size() + " likely regions disagree with CLDR, the first 40 shown");

        // And nothing else, over every code the dump asked, as the dump asked them.
        List<String> languages = new ArrayList<>(List.of(Locale.getISOLanguages()));
        for (char a = 'a'; a <= 'z'; a++) {
            for (char b = 'a'; b <= 'z'; b++) {
                for (char c = 'a'; c <= 'z'; c++) {
                    languages.add(new String(new char[]{a, b, c}));
                }
            }
        }
        for (String language : languages) {
            if (!cldr.containsKey(language)) {
                assertNull(CalendarChronology.likelyRegion(language),
                        language + " has no likely region in CLDR and gets one here");
            }
        }
    }

    /**
     * The table ships as a resource, a copy of the dump's {@code likely} rows that the jar
     * carries and the dump does not, and a copy drifts unless something holds it to what it
     * copied.
     */
    @Test
    void theShippedRowsAreTheDumpsLikelyRowsInItsOrder() throws Exception {
        List<String> dump = new ArrayList<>();
        for (String line : Files.readAllLines(DUMP, StandardCharsets.UTF_8)) {
            if (line.startsWith("likely ")) {
                dump.add(line);
            }
        }
        List<String> shipped = new ArrayList<>();
        try (InputStream in = LikelyRegionsTest.class.getResourceAsStream(SHIPPED)) {
            assertNotNull(in, SHIPPED + " is not in the toolkit");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    shipped.add(line);
                }
            }
        }
        assertEquals(dump, shipped);
    }

    /**
     * A language with only a three-letter code has the week of the region it means too: Central
     * Kurdish means Iraq, whose week starts on a Saturday, and Filipino the Philippines, whose
     * starts on a Sunday.
     */
    @Test
    void aThreeLetterLanguageAloneHasTheWeekOfItsLikelyRegion() {
        assertEquals(WeekFields.of(Locale.forLanguageTag("ckb-IQ")),
                CalendarChronology.weekFields(Locale.forLanguageTag("ckb")));
        assertEquals(DayOfWeek.SATURDAY,
                CalendarChronology.weekFields(Locale.forLanguageTag("ckb")).getFirstDayOfWeek());
        assertEquals(WeekFields.of(Locale.forLanguageTag("ars-SA")),
                CalendarChronology.weekFields(Locale.forLanguageTag("ars")));
        assertEquals(WeekFields.of(Locale.forLanguageTag("fil-PH")),
                CalendarChronology.weekFields(Locale.forLanguageTag("fil")));
    }

    /**
     * A region named by its UN number keeps the week of the region the number stands for, as ICU
     * reads it: 276 is Germany, whose first week needs four days, 818 Egypt, whose week starts on
     * a Saturday, and 840 the United States. The JDK takes the number for a region it does not
     * know and answers Monday and a one-day first week for all three.
     */
    @Test
    void aNumberedRegionHasTheWeekOfTheRegionItStandsFor() {
        assertEquals(WeekFields.of(Locale.forLanguageTag("de-DE")),
                CalendarChronology.weekFields(Locale.forLanguageTag("de-276")));
        assertEquals(WeekFields.of(Locale.forLanguageTag("ar-EG")),
                CalendarChronology.weekFields(Locale.forLanguageTag("ar-818")));
        assertEquals(WeekFields.of(Locale.forLanguageTag("en-US")),
                CalendarChronology.weekFields(Locale.forLanguageTag("en-840")));
    }

    /**
     * What the table is for: a language named alone numbers its weeks as the region it means
     * does, for every language CLDR knows. Before the table the JDK answered the United States'
     * Sunday and one-day first week for all of them, which was wrong for 125.
     */
    @Test
    void aLanguageAloneHasTheWeekOfItsLikelyRegion() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> row : cldr().entrySet()) {
            WeekFields expected = WeekFields.of(new Locale.Builder()
                    .setLanguage(row.getKey()).setRegion(row.getValue()).build());
            WeekFields ours = CalendarChronology.weekFields(Locale.forLanguageTag(row.getKey()));
            if (!expected.equals(ours)) {
                wrong.add(row.getKey() + ": " + row.getValue() + " is " + expected + ", we say " + ours);
            }
        }
        assertEquals(List.of(), wrong);
    }

    /**
     * A code ICU reads as another language, named alone, has the week of the language it stands
     * for: {@code fra}, the ISO 639-2 code for French, starts on a Monday and numbers its weeks
     * the way France does, {@code pes}, Iranian Persian, starts on a Saturday as Iran does, and
     * {@code prs}, Dari, which ICU reads as {@code fa-AF}, has Afghanistan's week, the region its
     * code carries rather than Persian's likely one. Java's {@link Locale} keeps these codes as
     * they are and the likely-region table is keyed on the language, so every one of them fell to
     * the United States' Sunday and one-day first week. Asked of every {@code alias} row of
     * {@code cldr-locale-facts.txt}.
     */
    @Test
    void anAliasAloneHasItsReplacementsWeek() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String[] alias : CldrAliasRows.read()) {
            WeekFields expected = CalendarChronology.weekFields(Locale.forLanguageTag(alias[1]));
            WeekFields ours = CalendarChronology.weekFields(Locale.forLanguageTag(alias[0]));
            if (!expected.equals(ours)) {
                wrong.add(alias[0] + " is " + alias[1] + ", which is " + expected + ", we say " + ours);
            }
        }
        assertEquals(List.of(), wrong, "an alias alone has another week than its replacement");

        assertEquals(WeekFields.of(DayOfWeek.MONDAY, 4),
                CalendarChronology.weekFields(Locale.forLanguageTag("fra")));
        assertEquals(WeekFields.of(DayOfWeek.SATURDAY, 1),
                CalendarChronology.weekFields(Locale.forLanguageTag("pes")));
        assertEquals(WeekFields.of(Locale.forLanguageTag("fa-AF")),
                CalendarChronology.weekFields(Locale.forLanguageTag("prs")));
    }

    private static Map<String, String> cldr() throws Exception {
        assertTrue(Files.exists(DUMP), "the CLDR dump is missing: " + DUMP.toAbsolutePath());
        Map<String, String> regions = new TreeMap<>();
        for (String line : Files.readAllLines(DUMP, StandardCharsets.UTF_8)) {
            if (!line.startsWith("likely ")) {
                continue;
            }
            for (String pair : line.substring("likely ".length()).split(" ")) {
                String[] parts = pair.split("-");
                regions.put(parts[0], parts[1]);
            }
        }
        return regions;
    }
}

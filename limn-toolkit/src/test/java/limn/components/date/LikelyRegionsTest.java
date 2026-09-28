package limn.components.date;

import limn.i18n.CldrAliasRows;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The region a language named alone is read for, against CLDR itself.
 *
 * <p>{@link CalendarChronology#likelyRegion} is a copy of ICU's likely subtags, and the week of
 * every locale without a region is read through it. The same argument as
 * {@code CldrLocaleFactsTest}, whose dump this reads: a table copied into Java by a person drifts
 * unless something holds it to what it copied. Lives in this package because the table is not
 * public.
 */
class LikelyRegionsTest {

    private static final Path DUMP = Path.of("src/test/resources/limn/i18n/cldr-locale-facts.txt");

    @Test
    void theTableIsCldrsAndHoldsNothingElse() throws Exception {
        Map<String, String> cldr = cldr();
        assertTrue(cldr.size() > 150, "the dump carries only " + cldr.size() + " languages");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> row : cldr.entrySet()) {
            String ours = CalendarChronology.likelyRegion(row.getKey());
            if (!row.getValue().equals(ours)) {
                wrong.add(row.getKey() + ": CLDR says " + row.getValue() + ", we say " + ours);
            }
        }
        assertEquals(List.of(), wrong, "likely regions that disagree with CLDR");
        for (String language : Locale.getISOLanguages()) {
            if (!cldr.containsKey(language)) {
                assertNull(CalendarChronology.likelyRegion(language),
                        language + " has no likely region in CLDR and gets one here");
            }
        }
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

package limn.i18n;

import limn.scene.LayoutDirection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two tables the toolkit writes out about languages, against CLDR itself.
 *
 * <p>Same argument as {@code PluralRulesTest}: {@link NumberingSystem#forLocale} and
 * {@link LayoutDirection}'s sets are facts about languages, copied into Java by a person, and a
 * copy nobody checks drifts from what it copied. {@code scripts/i18n/dump-cldr-locale-facts.mjs}
 * asks Node's {@code Intl} — CLDR through ICU — and writes {@code cldr-locale-facts.txt}, which
 * is checked in with the ICU version that produced it. The suite reads the file and runs no Node.
 *
 * <p><b>Both tables were wrong when this was first run</b> (2026-09-21, ICU 78.3), and neither
 * error was of the kind more care would have prevented. The digits table had Arabic <em>inverted</em>
 * — it made Arabic-Indic the default and the Maghreb the exception, where CLDR makes Latin the
 * default and names twenty-three regions — and the direction table knew 15 right-to-left
 * languages where CLDR knows 286.
 *
 * <p>The script half of {@link LayoutDirection} is checked against a second source, and one that
 * cannot go stale — the JDK's own Unicode data — by {@code LayoutDirectionScriptsTest}, which
 * lives in that class's package because the table it reads is not public.
 *
 * <p>The dump's third table, the region a language named alone is read for when a calendar needs
 * its week, is checked by {@code LikelyRegionsTest} in the date package, for the same reason.
 */
class CldrLocaleFactsTest {

    private static final Path DUMP = Path.of("src/test/resources/limn/i18n/cldr-locale-facts.txt");

    /**
     * Languages CLDR gives a numbering system this enum does not carry. They answer
     * {@link NumberingSystem#LATN} and the class note says why: a system is a constant and a
     * table row away, and what gates it is the glyph coverage test, because digits the vendored
     * fonts cannot draw are worse than Latin ones.
     */
    private static final Set<String> KNOWN_GAPS = Set.of("as", "bn", "dz", "my");

    @Test
    void theDigitsTableIsCldrs() throws Exception {
        List<String> wrong = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int rows = 0;
        for (String line : lines()) {
            if (!line.startsWith("digits ")) {
                continue;
            }
            rows++;
            String[] parts = line.split(" ");
            Locale locale = Locale.forLanguageTag(parts[1]);
            String cldr = parts[2];
            NumberingSystem ours = NumberingSystem.forLocale(locale);
            if (KNOWN_GAPS.contains(locale.getLanguage())) {
                gaps.add(parts[1] + "=" + cldr);
                assertEquals(NumberingSystem.LATN, ours,
                        parts[1] + " is a known gap and must fall back to Latin digits, not to "
                                + "a system that is not the one CLDR names");
                continue;
            }
            if (!ours.name().equalsIgnoreCase(cldr)) {
                wrong.add(parts[1] + ": CLDR says " + cldr + ", we say " + ours);
            }
        }
        assertTrue(rows >= 30, "the dump carries only " + rows + " digit rows");
        assertEquals(List.of(), wrong, "NumberingSystem.forLocale disagrees with CLDR");
        assertEquals(4, gaps.size(), "the known gaps changed, so the class note has to: " + gaps);
    }

    /**
     * Everything the dump does <b>not</b> name writes Latin digits. Without this half the table
     * could answer {@code ARAB} for every language on earth and still pass the half above.
     */
    @Test
    void everyOtherLanguageWritesLatinDigits() throws Exception {
        Set<String> named = new TreeSet<>();
        for (String line : lines()) {
            if (line.startsWith("digits ")) {
                named.add(line.split(" ")[1].split("-")[0]);
            }
        }
        List<String> wrong = new ArrayList<>();
        for (String language : Locale.getISOLanguages()) {
            if (named.contains(language)) {
                continue;
            }
            NumberingSystem ours = NumberingSystem.forLocale(Locale.forLanguageTag(language));
            if (ours != NumberingSystem.LATN) {
                wrong.add(language + ": CLDR says latn, we say " + ours);
            }
        }
        assertEquals(List.of(), wrong, "a language CLDR writes in Latin digits gets others here");
    }

    @Test
    void theRightToLeftLanguagesAreCldrs() throws Exception {
        Set<String> cldr = new TreeSet<>();
        for (String line : lines()) {
            if (line.startsWith("rtl ")) {
                cldr.addAll(List.of(line.substring("rtl ".length()).split(" ")));
            }
        }
        assertTrue(cldr.size() > 200, "the dump carries only " + cldr.size() + " languages");

        List<String> wrong = new ArrayList<>();
        for (String language : cldr) {
            if (LayoutDirection.forLocale(Locale.forLanguageTag(language))
                    != LayoutDirection.RTL) {
                wrong.add(language + " is written right to left and we lay it out left to right");
            }
        }
        assertEquals(List.of(), wrong, "languages CLDR writes right to left");

        // And the converse, over the same space the dump searched: a table that answered RTL for
        // everything would pass the loop above.
        List<String> overreach = new ArrayList<>();
        for (String language : Locale.getISOLanguages()) {
            if (!cldr.contains(language) && !language.equals("iw") && !language.equals("ji")
                    && LayoutDirection.forLocale(Locale.forLanguageTag(language))
                            == LayoutDirection.RTL) {
                overreach.add(language + " is written left to right and we mirror it");
            }
        }
        assertEquals(List.of(), overreach, "languages we mirror and CLDR does not");
    }

    private static List<String> lines() throws Exception {
        assertTrue(Files.exists(DUMP), "the CLDR dump is missing: " + DUMP.toAbsolutePath());
        return Files.readAllLines(DUMP, StandardCharsets.UTF_8);
    }
}

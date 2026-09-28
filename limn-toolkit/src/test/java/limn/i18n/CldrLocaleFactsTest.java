package limn.i18n;

import limn.scene.LayoutDirection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 *
 * <p>Its fourth, the codes ICU reads as another language, is asked of the digits table here. The
 * direction search went through the same replacement, so a code whose replacement is written
 * right to left is itself a {@code rtl} row, and one whose replacement a region turns is itself a
 * {@code direction} row ({@code pan-PK} as {@code pa-PK}), and the direction tests already hold
 * both.
 *
 * <p><b>The direction table ignored the region until 2026-09-28</b> (ICU 78.3): 78 of the
 * {@code direction} rows are a language turned by its region, among them Punjabi in Pakistan,
 * Uzbek in Afghanistan, Azerbaijani, Kazakh and Kurdish in Iran, and Sindhi in India, and every
 * one of the 78 was laid out the way the language alone runs. Both tables then read a region
 * named by its UN number as a region nobody listed, {@code az-364} for Iran and {@code ar-818}
 * for Egypt, until the {@code region} rows taught the language resolver to read it as ICU does.
 */
class CldrLocaleFactsTest {

    private static final Path DUMP = CldrAliasRows.FACTS;

    /**
     * Rows whose system this enum does not carry, a language's or one script's of it. They
     * answer {@link NumberingSystem#LATN} and the class note says why: a system is a constant and
     * a table row away, and what gates it is the glyph coverage test, because digits the vendored
     * fonts cannot draw are worse than Latin ones.
     */
    private static final Set<String> KNOWN_GAPS =
            Set.of("as", "bn", "ccp", "dz", "ff-Adlm", "mni", "my", "nqo", "sat");

    @Test
    void theDigitsTableIsCldrs() throws Exception {
        List<String> wrong = new ArrayList<>();
        Set<String> gaps = new TreeSet<>();
        int rows = 0;
        for (String[] row : digitRows()) {
            rows++;
            Locale locale = Locale.forLanguageTag(row[0]);
            String cldr = row[1];
            NumberingSystem ours = NumberingSystem.forLocale(locale);
            if (KNOWN_GAPS.contains(row[0])) {
                gaps.add(row[0]);
                assertEquals(NumberingSystem.LATN, ours,
                        row[0] + " is a known gap and must fall back to Latin digits, not to "
                                + "a system that is not the one CLDR names");
                continue;
            }
            if (!ours.name().equalsIgnoreCase(cldr)) {
                wrong.add(row[0] + ": CLDR says " + cldr + ", we say " + ours);
            }
        }
        assertTrue(rows >= 50, "the dump carries only " + rows + " digit rows");
        assertEquals(List.of(), wrong, "NumberingSystem.forLocale disagrees with CLDR");
        assertEquals(new TreeSet<>(KNOWN_GAPS), gaps,
                "the known gaps changed, so the class note has to");
    }

    /**
     * Everything the dump does <b>not</b> name writes Latin digits. Without this half the table
     * could answer {@code ARAB} for every language on earth and still pass the half above. The
     * three-letter half is searched over every combination there is, as the dump searched it,
     * since the JDK lists only the two-letter codes.
     */
    @Test
    void everyOtherLanguageWritesLatinDigits() throws Exception {
        Set<String> named = new TreeSet<>();
        for (String[] row : digitRows()) {
            named.add(row[0].split("-")[0]);
        }
        // A code ICU reads as one of those languages writes that language's digits, which the
        // alias tests below hold it to.
        for (String[] alias : CldrAliasRows.read()) {
            if (named.contains(Locale.forLanguageTag(alias[1]).getLanguage())) {
                named.add(alias[0]);
            }
        }
        List<String> languages = new ArrayList<>(List.of(Locale.getISOLanguages()));
        for (char a = 'a'; a <= 'z'; a++) {
            for (char b = 'a'; b <= 'z'; b++) {
                for (char c = 'a'; c <= 'z'; c++) {
                    languages.add(new String(new char[]{a, b, c}));
                }
            }
        }
        List<String> wrong = new ArrayList<>();
        for (String language : languages) {
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

    /**
     * A script the dump does not name for a language changes nothing: the language in that script
     * writes what the language alone writes, which is what CLDR's inheritance gives a script it
     * holds no data for, and what it holds and the dump left out because it agrees. Without this
     * half a table that read every script as Latin would pass the rows above, which name only the
     * scripts that differ; asked over every script some row names, of every language some row
     * names.
     */
    @Test
    void aScriptTheDumpDoesNotNameChangesNothing() throws Exception {
        Set<String> tags = new TreeSet<>();
        Set<String> languages = new TreeSet<>();
        Set<String> scripts = new TreeSet<>();
        for (String[] row : digitRows()) {
            tags.add(row[0]);
            Locale locale = Locale.forLanguageTag(row[0]);
            languages.add(locale.getLanguage());
            if (!locale.getScript().isEmpty()) {
                scripts.add(locale.getScript());
            }
        }
        assertTrue(scripts.size() >= 4, "the dump names only the scripts " + scripts);

        List<String> wrong = new ArrayList<>();
        for (String language : languages) {
            NumberingSystem alone = NumberingSystem.forLocale(Locale.forLanguageTag(language));
            for (String script : scripts) {
                String tag = language + "-" + script;
                if (tags.contains(tag)) {
                    continue;
                }
                NumberingSystem ours = NumberingSystem.forLocale(Locale.forLanguageTag(tag));
                if (ours != alone) {
                    wrong.add(tag + ": " + language + " writes " + alone + ", we say " + ours);
                }
            }
        }
        assertEquals(List.of(), wrong, "a script the dump does not name moved a language's digits");
    }

    /**
     * A code ICU reads as another language writes that language's digits: {@code pes}, the ISO
     * 639-3 code for Iranian Persian, writes what {@code fa} writes, and {@code prs}, Dari, what
     * {@code fa-AF} writes. ICU replaces the code before it looks anything up, and Java's
     * {@link Locale} replaces only {@code iw}, {@code ji} and {@code in} itself, so a table keyed
     * on the language subtag answers every other alias as a language it never heard of. Asked of
     * every {@code alias} row, including the ones whose replacement writes Latin digits, so that
     * a table that answered an alias for a language it is not would be caught as well.
     */
    @Test
    void anAliasWritesItsReplacementsDigits() throws Exception {
        List<String[]> aliases = CldrAliasRows.read();
        assertTrue(aliases.size() > 400, "the dump carries only " + aliases.size() + " aliases");

        List<String> wrong = new ArrayList<>();
        for (String[] alias : aliases) {
            NumberingSystem ours = NumberingSystem.forLocale(Locale.forLanguageTag(alias[0]));
            NumberingSystem replacement = NumberingSystem.forLocale(Locale.forLanguageTag(alias[1]));
            if (ours != replacement) {
                wrong.add(alias[0] + " is " + alias[1] + ", which writes " + replacement
                        + ", we say " + ours);
            }
        }
        assertEquals(List.of(), wrong, "an alias writes other digits than its replacement");
    }

    /**
     * And it keeps the script and region it is named with. {@code arb}, Standard Arabic, writes
     * Latin digits alone as {@code ar} does, so the half above cannot tell a table that ignores it
     * from one that reads it; {@code arb-EG} writes Arabic-Indic ones as {@code ar-EG} does, and
     * that is where it can. Asked of every alias in the script and region of every {@code digits}
     * row of its replacement's language. The replacement's own script or region fills in only
     * what the tag leaves out, which is how ICU replaces it: {@code prs-IR} is {@code fa-IR}.
     */
    @Test
    void anAliasKeepsItsScriptAndRegion() throws Exception {
        List<String> wrong = new ArrayList<>();
        int asked = 0;
        for (String[] alias : CldrAliasRows.read()) {
            Locale replacement = Locale.forLanguageTag(alias[1]);
            for (String[] row : digitRows()) {
                Locale written = Locale.forLanguageTag(row[0]);
                String script = written.getScript();
                String region = written.getCountry();
                if (!written.getLanguage().equals(replacement.getLanguage())
                        || (script.isEmpty() && region.isEmpty())) {
                    continue;
                }
                asked++;
                Locale tag = new Locale.Builder().setLanguage(alias[0])
                        .setScript(script).setRegion(region).build();
                Locale canonical = new Locale.Builder().setLanguage(replacement.getLanguage())
                        .setScript(script.isEmpty() ? replacement.getScript() : script)
                        .setRegion(region.isEmpty() ? replacement.getCountry() : region)
                        .build();
                NumberingSystem ours = NumberingSystem.forLocale(tag);
                NumberingSystem theirs = NumberingSystem.forLocale(canonical);
                if (ours != theirs) {
                    wrong.add(tag.toLanguageTag() + " is " + canonical.toLanguageTag()
                            + ", which writes " + theirs + ", we say " + ours);
                }
            }
        }
        assertTrue(asked >= 50, "only " + asked + " aliased tags carry a script or region");
        assertEquals(List.of(), wrong, "an alias with a script or region writes other digits");
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

    /**
     * Where a region or a script turns a language the other way. CLDR reads a locale that names
     * no script for the script its language is likely written in <em>there</em>: {@code pa-PK},
     * Punjabi in Pakistan, is Punjabi in Arabic script and runs right to left where {@code pa}
     * runs left to right, and {@code sd-IN}, Sindhi in India, is Devanagari and runs left to
     * right where {@code sd} does not. A script, where one is named, decides over the region:
     * {@code pa-Guru-PK} runs left to right.
     */
    @Test
    void theDirectionRowsAreCldrs() throws Exception {
        List<String[]> rows = directionRows();
        assertTrue(rows.size() >= 100, "the dump carries only " + rows.size() + " direction rows");

        List<String> wrong = new ArrayList<>();
        for (String[] row : rows) {
            LayoutDirection cldr = LayoutDirection.valueOf(row[1].toUpperCase(Locale.ROOT));
            LayoutDirection ours = LayoutDirection.forLocale(Locale.forLanguageTag(row[0]));
            if (ours != cldr) {
                wrong.add(row[0] + ": CLDR says " + row[1] + ", we say " + ours);
            }
        }
        assertEquals(List.of(), wrong, "LayoutDirection.forLocale disagrees with CLDR");
    }

    /**
     * A region no row names leaves a language's direction where it was. Without this half a table
     * that turned Punjabi in every region, or every language in Pakistan, would pass the rows
     * above. Asked of every code the dump searched in every region some row names, and of every
     * language a row or the JDK names in every region there is, as the dump asked them.
     */
    @Test
    void aRegionTheDumpDoesNotNameLeavesADirectionAlone() throws Exception {
        Set<String> tags = new HashSet<>();
        Set<String> named = new TreeSet<>(List.of(Locale.getISOLanguages()));
        Set<String> regions = new TreeSet<>();
        for (String[] row : directionRows()) {
            tags.add(row[0]);
            named.add(row[0].split("-")[0]);
            String region = Locale.forLanguageTag(row[0]).getCountry();
            if (!region.isEmpty()) {
                regions.add(region);
            }
        }
        named.addAll(rightToLeftRows());
        assertTrue(regions.size() >= 30, "the rows name only the regions " + regions);

        List<String> everyRegion = new ArrayList<>();
        for (char a = 'A'; a <= 'Z'; a++) {
            for (char b = 'A'; b <= 'Z'; b++) {
                everyRegion.add(new String(new char[]{a, b}));
            }
        }
        Set<String> languages = new TreeSet<>(named);
        for (char a = 'a'; a <= 'z'; a++) {
            for (char b = 'a'; b <= 'z'; b++) {
                languages.add(new String(new char[]{a, b}));
                for (char c = 'a'; c <= 'z'; c++) {
                    languages.add(new String(new char[]{a, b, c}));
                }
            }
        }
        List<String> wrong = new ArrayList<>();
        for (String language : languages) {
            LayoutDirection alone = LayoutDirection.forLocale(Locale.forLanguageTag(language));
            for (String region : named.contains(language) ? everyRegion : regions) {
                String tag = language + "-" + region;
                if (tags.contains(tag)) {
                    continue;
                }
                LayoutDirection ours = LayoutDirection.forLocale(Locale.forLanguageTag(tag));
                if (ours != alone) {
                    wrong.add(tag + ": " + language + " runs " + alone + ", we say " + ours);
                }
            }
        }
        assertEquals(List.of(), wrong, "a region the dump does not name turned a language");
    }

    /**
     * A named script decides alone, whatever the language and the region: CLDR looks for the
     * script a language is likely written in only when the locale names none. That is where
     * direction parts from digits, whose script a language inherits when CLDR holds it in no
     * data of its own: {@code sd-Latn} has no row, since ICU holds Sindhi in Arabic and
     * Devanagari only, and runs left to right as every Latin tag does. So a script no row names
     * for a language leaves it where it was when the script runs its way, and turns it when the
     * script runs the other. Without this half a table that read the region first, or the
     * language, would pass wherever no row happens to name the pair; asked of every language a
     * row or the JDK names, in every script a row names, alone and in every region a row names.
     */
    @Test
    void aScriptDecidesADirectionAlone() throws Exception {
        Map<String, LayoutDirection> scripts = new TreeMap<>();
        List<String> torn = new ArrayList<>();
        Set<String> named = new TreeSet<>(List.of(Locale.getISOLanguages()));
        Set<String> regions = new TreeSet<>(Set.of(""));
        for (String[] row : directionRows()) {
            Locale locale = Locale.forLanguageTag(row[0]);
            named.add(row[0].split("-")[0]);
            regions.add(locale.getCountry());
            if (locale.getScript().isEmpty()) {
                continue;
            }
            LayoutDirection cldr = LayoutDirection.valueOf(row[1].toUpperCase(Locale.ROOT));
            LayoutDirection before = scripts.putIfAbsent(locale.getScript(), cldr);
            if (before != null && before != cldr) {
                torn.add(row[0] + " runs " + cldr + " where another row runs "
                        + locale.getScript() + " " + before);
            }
        }
        named.addAll(rightToLeftRows());
        assertEquals(List.of(), torn, "the dump runs one script both ways");
        assertTrue(scripts.size() >= 5, "the rows name only the scripts " + scripts.keySet());

        List<String> wrong = new ArrayList<>();
        for (String language : named) {
            for (Map.Entry<String, LayoutDirection> script : scripts.entrySet()) {
                for (String region : regions) {
                    Locale tag = new Locale.Builder().setLanguageTag(language)
                            .setScript(script.getKey()).setRegion(region).build();
                    LayoutDirection ours = LayoutDirection.forLocale(tag);
                    if (ours != script.getValue()) {
                        wrong.add(tag.toLanguageTag() + ": " + script.getKey() + " runs "
                                + script.getValue() + ", we say " + ours);
                    }
                }
            }
        }
        assertEquals(List.of(), wrong, "a named script lost to the language or the region");
    }

    /**
     * A region named by its UN number runs as the region ICU reads the number as: {@code az-364}
     * is Azerbaijani in Iran and runs right to left as {@code az-IR} does, {@code pa-586} is
     * Punjabi in Pakistan. {@link Locale} keeps the number as it is given, so a table keyed on
     * two-letter regions read every number as a region nobody listed. Asked of every language a
     * row or the JDK names, in every number ICU replaces, against the same language in the region
     * the {@code region} rows give it there.
     */
    @Test
    void aNumericRegionRunsAsTheRegionItStandsFor() throws Exception {
        Set<String> languages = new TreeSet<>(List.of(Locale.getISOLanguages()));
        languages.addAll(rightToLeftRows());
        for (String[] row : directionRows()) {
            languages.add(row[0].split("-")[0]);
        }
        List<String[]> regions = CldrAliasRows.regions();
        List<String> wrong = new ArrayList<>();
        int turned = 0;
        for (String language : languages) {
            for (Map.Entry<String, String> number : numbers(regions, language).entrySet()) {
                LayoutDirection cldr = LayoutDirection.forLocale(
                        Locale.forLanguageTag(language + "-" + number.getValue()));
                LayoutDirection ours = LayoutDirection.forLocale(
                        Locale.forLanguageTag(language + "-" + number.getKey()));
                if (cldr != LayoutDirection.forLocale(Locale.forLanguageTag(language))) {
                    turned++;
                }
                if (ours != cldr) {
                    wrong.add(language + "-" + number.getKey() + " is " + language + "-"
                            + number.getValue() + ", which runs " + cldr + ", we say " + ours);
                }
            }
        }
        assertTrue(turned >= 50, "only " + turned + " numbered regions turn a language");
        assertEquals(List.of(), wrong, "a numeric region runs otherwise than its region");
    }

    /**
     * And writes the digits of the region it stands for: {@code ar-818}, Arabic in Egypt by its
     * UN number, writes Arabic-Indic digits as {@code ar-EG} does. Asked of every language a
     * {@code digits} row names, in every number ICU replaces.
     */
    @Test
    void aNumericRegionWritesTheDigitsOfTheRegionItStandsFor() throws Exception {
        Set<String> languages = new TreeSet<>();
        for (String[] row : digitRows()) {
            languages.add(row[0].split("-")[0]);
        }
        List<String[]> regions = CldrAliasRows.regions();
        List<String> wrong = new ArrayList<>();
        int differ = 0;
        for (String language : languages) {
            NumberingSystem alone = NumberingSystem.forLocale(Locale.forLanguageTag(language));
            for (Map.Entry<String, String> number : numbers(regions, language).entrySet()) {
                NumberingSystem cldr = NumberingSystem.forLocale(
                        Locale.forLanguageTag(language + "-" + number.getValue()));
                NumberingSystem ours = NumberingSystem.forLocale(
                        Locale.forLanguageTag(language + "-" + number.getKey()));
                if (cldr != alone) {
                    differ++;
                }
                if (ours != cldr) {
                    wrong.add(language + "-" + number.getKey() + " is " + language + "-"
                            + number.getValue() + ", which writes " + cldr + ", we say " + ours);
                }
            }
        }
        assertTrue(differ >= 25, "only " + differ + " numbered regions change a language's digits");
        assertEquals(List.of(), wrong, "a numeric region writes other digits than its region");
    }

    /**
     * Every number ICU reads as a two-letter region, and the region it reads it as for
     * {@code language} named with no script: the language's own {@code region} row where it
     * picks among the regions that replaced a number, else the number's.
     */
    private static Map<String, String> numbers(List<String[]> regions, String language) {
        Map<String, String> numbers = new TreeMap<>();
        Map<String, String> picked = new TreeMap<>();
        for (String[] row : regions) {
            String[] parts = row[0].split("-");
            if (parts.length == 1) {
                numbers.put(parts[0], row[1]);
            } else if (parts.length == 2 && parts[0].equals(language)) {
                picked.put(parts[1], row[1]);
            }
        }
        numbers.putAll(picked);
        return numbers;
    }

    /** Each {@code direction} row as its tag and CLDR's {@code ltr} or {@code rtl} for it. */
    private static List<String[]> directionRows() throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : lines()) {
            if (line.startsWith("direction ")) {
                String[] parts = line.split(" ");
                rows.add(new String[]{parts[1], parts[2]});
            }
        }
        return rows;
    }

    /** Every language subtag the {@code rtl} rows name. */
    private static Set<String> rightToLeftRows() throws Exception {
        Set<String> languages = new TreeSet<>();
        for (String line : lines()) {
            if (line.startsWith("rtl ")) {
                languages.addAll(List.of(line.substring("rtl ".length()).split(" ")));
            }
        }
        return languages;
    }

    /** Each {@code digits} row as its tag and CLDR's system for it. */
    private static List<String[]> digitRows() throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : lines()) {
            if (line.startsWith("digits ")) {
                String[] parts = line.split(" ");
                rows.add(new String[]{parts[1], parts[2]});
            }
        }
        return rows;
    }

    private static List<String> lines() throws Exception {
        assertTrue(Files.exists(DUMP), "the CLDR dump is missing: " + DUMP.toAbsolutePath());
        return Files.readAllLines(DUMP, StandardCharsets.UTF_8);
    }
}

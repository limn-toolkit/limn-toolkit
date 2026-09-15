package limn.components.date;

import org.junit.jupiter.api.Test;

import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pattern parser, against the shapes real locales actually hand over.
 *
 * <p>The patterns asserted here were <b>recorded from this JDK</b> on 2026-09-09 rather than
 * imagined, which matters: the two that broke a naive parser are Arabic's, which carries two
 * {@code U+200F} marks between its fields, and Korean's, which ends in a literal after the last
 * field. Both are in the table below.
 *
 * <p>The tests that go through {@link DatePattern#shortDate} assert the <em>shape</em> the locale
 * produces and not the exact pattern string, because CLDR data moves between JDK releases and a
 * test that pinned {@code "dd/MM/y"} would fail on an upgrade that is not a defect. What must not
 * move is that Portuguese puts the day first and Japanese the year, and that is what is asserted.
 */
class DatePatternTest {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final Locale EN_US = Locale.forLanguageTag("en-US");
    private static final Locale JA = Locale.forLanguageTag("ja-JP");
    private static final Locale KO = Locale.forLanguageTag("ko-KR");
    private static final Locale AR = Locale.forLanguageTag("ar-EG");

    private static List<DatePattern.Field> fieldsOf(List<DatePattern.Part> parts) {
        return parts.stream()
                .filter(part -> part instanceof DatePattern.FieldPart)
                .map(part -> ((DatePattern.FieldPart) part).field())
                .toList();
    }

    private static List<String> literalsOf(List<DatePattern.Part> parts) {
        return parts.stream()
                .filter(part -> part instanceof DatePattern.Literal)
                .map(part -> ((DatePattern.Literal) part).text())
                .toList();
    }

    @Test
    void aRunOfPatternLettersIsOneFieldAndEverythingElseIsALiteral() {
        List<DatePattern.Part> parts = DatePattern.parse("dd/MM/y");
        assertEquals(5, parts.size());
        assertEquals(List.of(DatePattern.Field.DAY, DatePattern.Field.MONTH,
                DatePattern.Field.YEAR), fieldsOf(parts));
        assertEquals(List.of("/", "/"), literalsOf(parts));
        assertEquals(2, ((DatePattern.FieldPart) parts.get(0)).width(), "dd is two letters wide");
        assertEquals(1, ((DatePattern.FieldPart) parts.get(4)).width(), "y is one");
    }

    /**
     * The eight patterns ADR 042 &sect;12 says the probe recorded, each parsed verbatim into its own
     * order and its own separators. Until 2026-09-14 two of them ({@code dd.MM.yy} and
     * {@code d.M.y}) were named by the record and parsed by no test (DT3).
     */
    @Test
    void everyPatternTheProbeRecordedParsesIntoItsOwnOrderAndSeparators() {
        DatePattern.Field d = DatePattern.Field.DAY;
        DatePattern.Field m = DatePattern.Field.MONTH;
        DatePattern.Field y = DatePattern.Field.YEAR;
        DatePattern.Field g = DatePattern.Field.ERA;
        record Recorded(String pattern, List<DatePattern.Field> fields, List<String> literals) {
        }
        List<Recorded> recorded = List.of(
                new Recorded("M/d/yy", List.of(m, d, y), List.of("/", "/")),
                new Recorded("dd/MM/y", List.of(d, m, y), List.of("/", "/")),
                new Recorded("dd.MM.yy", List.of(d, m, y), List.of(".", ".")),
                new Recorded("y/MM/dd", List.of(y, m, d), List.of("/", "/")),
                new Recorded("yy. M. d.", List.of(y, m, d), List.of(". ", ". ", ".")),
                new Recorded("d.M.y", List.of(d, m, y), List.of(".", ".")),
                new Recorded("d\u200F/M\u200F/y", List.of(d, m, y), List.of("\u200F/", "\u200F/")),
                new Recorded("GGGGGy/M/d", List.of(g, y, m, d), List.of("/", "/")));
        for (Recorded r : recorded) {
            List<DatePattern.Part> parts = DatePattern.parse(r.pattern());
            assertEquals(r.fields(), fieldsOf(parts), r.pattern());
            assertEquals(r.literals(), literalsOf(parts), r.pattern());
        }
        List<DatePattern.Part> german = DatePattern.parse("dd.MM.yy");
        assertEquals(2, ((DatePattern.FieldPart) german.get(4)).width(), "yy is two letters wide");
    }

    @Test
    void adjacentLiteralCharactersMergeIntoOnePart() {
        // Korean: ". " twice, and a trailing "." after the last field. All three are one part each,
        // and the trailing one is real -- dropping it is what makes a Korean date look wrong.
        List<DatePattern.Part> parts = DatePattern.parse("y. M. d.");
        assertEquals(List.of(". ", ". ", "."), literalsOf(parts));
        assertInstanceOf(DatePattern.Literal.class, parts.get(parts.size() - 1),
                "the pattern ends in a literal and the parser keeps it");
    }

    @Test
    void quotedTextIsALiteralAndTwoQuotesAreOne() {
        // The Portuguese long pattern's particle: two pattern letters that would be read as a
        // day-of-year and a day-period if the quotes were dropped.
        assertEquals(List.of(" de ", " de "), literalsOf(DatePattern.parse("d 'de' MMMM 'de' y")));
        assertEquals(List.of("o'clock"), literalsOf(DatePattern.parse("'o''clock'")));
    }

    @Test
    void bidiControlCharactersSurviveAsLiterals() {
        // Arabic's short pattern, verbatim: the marks are what make the separators sit correctly
        // inside a right-to-left line, and a parser that filtered "invisible" characters would
        // produce a field that is subtly wrong in the one language it was localized for.
        String pattern = "d‏/M‏/y";
        List<String> literals = literalsOf(DatePattern.parse(pattern));
        assertEquals(List.of("‏/", "‏/"), literals);
    }

    @Test
    void anUnknownPatternLetterIsAFieldTheKeyboardSkips() {
        List<DatePattern.Part> parts = DatePattern.parse("EEEE, d MMM y");
        assertEquals(DatePattern.Field.OTHER, fieldsOf(parts).get(0));
        assertFalse(DatePattern.Field.OTHER.editable(), "a weekday name is drawn, not edited");
        assertFalse(DatePattern.Field.ERA.editable(), "and neither is an era (ADR 042 3)");
        assertTrue(DatePattern.Field.DAY.editable());
        assertTrue(DatePattern.Field.DAY.numeric());
        assertFalse(DatePattern.Field.DAY_PERIOD.numeric(), "AM/PM is edited, not typed as digits");
    }

    @Test
    void everyYearAndMonthSpellingLandsOnOneField() {
        assertEquals(DatePattern.Field.YEAR, DatePattern.Field.of('y'));
        assertEquals(DatePattern.Field.YEAR, DatePattern.Field.of('u'));
        assertEquals(DatePattern.Field.MONTH, DatePattern.Field.of('M'));
        assertEquals(DatePattern.Field.MONTH, DatePattern.Field.of('L'));
        assertEquals(DatePattern.Field.HOUR24, DatePattern.Field.of('H'));
        assertEquals(DatePattern.Field.HOUR12, DatePattern.Field.of('h'));
        // 'B' is the flexible day period several Chinese locales use in their time pattern; it is
        // a period like 'a' and not something to be dropped.
        assertEquals(DatePattern.Field.DAY_PERIOD, DatePattern.Field.of('B'));
    }

    @Test
    void theFieldOrderIsTheLanguagesOwn() {
        Chronology iso = IsoChronology.INSTANCE;
        assertEquals(List.of(DatePattern.Field.DAY, DatePattern.Field.MONTH,
                        DatePattern.Field.YEAR),
                fieldsOf(DatePattern.shortDate(iso, PT_BR)), "Portuguese writes the day first");
        assertEquals(List.of(DatePattern.Field.MONTH, DatePattern.Field.DAY,
                        DatePattern.Field.YEAR),
                fieldsOf(DatePattern.shortDate(iso, EN_US)), "American English the month");
        assertEquals(List.of(DatePattern.Field.YEAR, DatePattern.Field.MONTH,
                        DatePattern.Field.DAY),
                fieldsOf(DatePattern.shortDate(iso, JA)), "Japanese the year");
        assertEquals(List.of(DatePattern.Field.YEAR, DatePattern.Field.MONTH,
                        DatePattern.Field.DAY),
                fieldsOf(DatePattern.shortDate(iso, KO)));
        assertEquals(List.of(DatePattern.Field.DAY, DatePattern.Field.MONTH,
                        DatePattern.Field.YEAR),
                fieldsOf(DatePattern.shortDate(iso, AR)));
    }

    @Test
    void aTimePatternCarriesTheClockTheLanguageKeeps() {
        List<DatePattern.Field> brazilian = fieldsOf(DatePattern.time(false, PT_BR));
        assertEquals(List.of(DatePattern.Field.HOUR24, DatePattern.Field.MINUTE), brazilian,
                "Portuguese keeps a 24-hour clock and needs no day period");
        List<DatePattern.Field> american = fieldsOf(DatePattern.time(false, EN_US));
        assertTrue(american.contains(DatePattern.Field.HOUR12));
        assertTrue(american.contains(DatePattern.Field.DAY_PERIOD),
                "a 12-hour clock arrives with the half of the day");
    }

    @Test
    void secondsAreTheMediumStyleAndNotAnInvention() {
        assertFalse(fieldsOf(DatePattern.time(false, PT_BR)).contains(DatePattern.Field.SECOND));
        assertTrue(fieldsOf(DatePattern.time(true, PT_BR)).contains(DatePattern.Field.SECOND),
                "asking for seconds moves to the style that has them, rather than appending :ss");
    }

    @Test
    void aJapaneseCalendarPatternCarriesAnEra() {
        Chronology japanese = Chronology.of("Japanese");
        List<DatePattern.Field> fields = fieldsOf(DatePattern.shortDate(japanese, JA));
        assertEquals(DatePattern.Field.ERA, fields.get(0),
                "the imperial calendar names its era first, and the field is drawn and not edited");
        assertTrue(fields.contains(DatePattern.Field.YEAR));
    }
}

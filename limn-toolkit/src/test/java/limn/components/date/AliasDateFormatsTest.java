package limn.components.date;

import limn.i18n.CldrAliasRows;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.chrono.IsoChronology;
import java.time.chrono.JapaneseChronology;
import java.time.chrono.JapaneseDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A date written under a code ICU reads as another language is written as that language writes
 * it. The platform's date formats, day names and patterns choose by the language subtag, and
 * Java's {@link Locale} keeps a code such as {@code fra} or {@code deu} as it was written, so a
 * calendar under one of them named its days and months in the root locale's words and wrote the
 * root locale's {@code y-MM-dd}.
 */
class AliasDateFormatsTest {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);

    /**
     * Everything the calendar and the field ask the platform for, under one locale: the date a
     * reader hears, the month header, both weekday names, a Japanese era year and the short date
     * and time patterns.
     */
    private static String written(Locale locale) {
        return CalendarChronology.fullDate(IsoChronology.INSTANCE, MARCH_1, locale)
                + " | " + CalendarChronology.monthHeader(IsoChronology.INSTANCE, MARCH_1, locale)
                + " | " + CalendarChronology.narrowWeekday(DayOfWeek.MONDAY, locale)
                + " | " + CalendarChronology.fullWeekday(DayOfWeek.MONDAY, locale)
                + " | " + CalendarChronology.eraYear(JapaneseChronology.INSTANCE,
                        JapaneseDate.from(MARCH_1), locale, false)
                + " | " + DatePattern.shortDate(IsoChronology.INSTANCE, locale)
                + " | " + DatePattern.time(true, locale);
    }

    /** Asked of every {@code alias} row of {@code cldr-locale-facts.txt}. */
    @Test
    void anAliasWritesDatesAsItsReplacementDoes() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String[] alias : CldrAliasRows.read()) {
            String ours = written(Locale.forLanguageTag(alias[0]));
            String replacement = written(Locale.forLanguageTag(alias[1]));
            if (!ours.equals(replacement)) {
                wrong.add(alias[0] + " is " + alias[1] + ", which writes " + replacement
                        + ", we write " + ours);
            }
        }
        assertEquals(List.of(), wrong, "an alias writes dates otherwise than its replacement");

        assertEquals("1 mars 2026", CalendarChronology.fullDate(IsoChronology.INSTANCE, MARCH_1,
                Locale.forLanguageTag("fra")), "fra is French");
        assertEquals("Montag", CalendarChronology.fullWeekday(DayOfWeek.MONDAY,
                Locale.forLanguageTag("deu")), "deu is German");
    }
}

package limn.i18n;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.function.Function;
import java.time.temporal.TemporalAccessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dates and times written in the language in effect, in the digits the toolkit writes numbers
 * in. The platform writes a date in ASCII digits under every locale, so under {@code ar-EG} and
 * Persian a date beside an amount read in other digits than the amount; and the toolkit writes a
 * bare {@code ar} in Latin digits, which the platform's date already does.
 */
class DateFormatsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final LocalTime HOUR = LocalTime.of(14, 9, 30);

    /** {@code format} applied with {@code tag}'s language in effect. */
    private static String in(String tag, Function<TemporalAccessor, String> format, TemporalAccessor value) {
        Locale enclosing = I18n.pushScope(Locale.forLanguageTag(tag));
        try {
            return format.apply(value);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /** What the platform writes for {@code tag}, digits and all. */
    private static String platform(String tag, DateTimeFormatter format, TemporalAccessor value) {
        return format.withLocale(Locale.forLanguageTag(tag)).format(value);
    }

    @Test
    void englishIsThePlatformsOwnText() {
        assertEquals(platform("en", DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT), DAY),
                in("en", DateFormats.date(FormatStyle.SHORT), DAY));
        assertEquals("10/17/26", in("en", DateFormats.date(FormatStyle.SHORT), DAY));
        assertEquals("Oct 17, 2026", in("en", DateFormats.date(FormatStyle.MEDIUM), DAY));
        assertEquals(platform("en", DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT), HOUR),
                in("en", DateFormats.time(FormatStyle.SHORT), HOUR));
        assertEquals(platform("en", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM),
                        LocalDateTime.of(DAY, HOUR)),
                in("en", DateFormats.dateTime(FormatStyle.MEDIUM), LocalDateTime.of(DAY, HOUR)));
    }

    @Test
    void aBareArabicWritesLatinDigitsAsItsNumbersDo() {
        String date = in("ar", DateFormats.date(FormatStyle.SHORT), DAY);
        assertEquals(platform("ar", DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT), DAY), date);
        assertTrue(date.contains("17") && date.contains("2026"), date);
    }

    /**
     * The locale published for an application's own number formats: the language in effect with
     * the digits the toolkit writes named on it, where the bare locale would write others.
     */
    @Test
    void theNumberLocaleWritesTheToolkitsDigits() {
        assertEquals("1234.5", in("ar", "%.1f"), "a bare Arabic writes Latin digits");
        assertEquals("١٢٣٤٫٥", in("ar-EG", "%.1f"));
        assertEquals("۱۲۳۴٫۵", in("fa", "%.1f"));
        assertEquals("1234.5", in("en", "%.1f"));
        assertEquals("١٢٣٤٫٥", String.format(Locale.forLanguageTag("ar"), "%.1f", 1234.5),
                "where the bare locale writes Arabic-Indic ones");
    }

    private static String in(String tag, String pattern) {
        Locale enclosing = I18n.pushScope(Locale.forLanguageTag(tag));
        try {
            return String.format(I18n.numberLocale(), pattern, 1234.5);
        } finally {
            I18n.popScope(enclosing);
        }
    }

    @Test
    void egyptianArabicWritesArabicIndicDigits() {
        String date = in("ar-EG", DateFormats.date(FormatStyle.SHORT), DAY);
        String plain = platform("ar-EG", DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT), DAY);
        assertTrue(plain.contains("17"), "the platform writes ASCII digits: " + plain);
        assertEquals(plain.replace('0', '٠').replace('1', '١').replace('2', '٢').replace('6', '٦')
                .replace('7', '٧'), date, "the same text with the toolkit's digits");
        assertTrue(date.contains("١٧") && date.contains("٢٠٢٦"), date);
        String time = in("ar-EG", DateFormats.time(FormatStyle.SHORT), HOUR);
        assertTrue(time.contains("٠٩") && noAsciiDigit(time), time);
    }

    @Test
    void persianWritesItsExtendedDigits() {
        String date = in("fa", DateFormats.date(FormatStyle.SHORT), DAY);
        assertTrue(noAsciiDigit(date), date);
        assertTrue(date.contains("۱۷"), "seventeen in Extended Arabic-Indic digits: " + date);
    }

    @Test
    void aTimeWithAZoneIsRefusedWhenTheFormatIsMade() {
        assertThrows(IllegalArgumentException.class, () -> DateFormats.time(FormatStyle.LONG));
        assertThrows(IllegalArgumentException.class, () -> DateFormats.dateTime(FormatStyle.FULL));
        assertEquals("Saturday, October 17, 2026", in("en", DateFormats.date(FormatStyle.FULL), DAY),
                "a date has no zone, and any length writes it");
    }

    private static boolean noAsciiDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * A locale that names its calendar is written in it, as the date widgets draw it: the value is
     * the same day, the era, the Buddhist year, the Republic of China's year or the Hijri date is
     * how it is written. Without the extension the same language writes the ISO year, as before.
     */
    @Test
    void aLocaleThatNamesItsCalendarIsWrittenInIt() {
        LocalDate day = LocalDate.of(2025, 10, 25);
        Function<TemporalAccessor, String> shortDate = DateFormats.date(FormatStyle.SHORT);
        Function<TemporalAccessor, String> longDate = DateFormats.date(FormatStyle.LONG);
        assertEquals("R7/10/25", in("ja-JP-u-ca-japanese", shortDate, day));
        assertEquals("令和7年10月25日", in("ja-JP-u-ca-japanese", longDate, day));
        assertEquals("2025/10/25", in("ja-JP", shortDate, day), "no extension, the ISO year");
        assertEquals("25 ตุลาคม 2568", in("th-TH-u-ca-buddhist", longDate, day));
        assertEquals("民國114年10月25日", in("zh-TW-u-ca-roc", longDate, day));
        assertEquals("October 25, 2568 BE", in("en-US-u-ca-buddhist", longDate, day));
        assertTrue(I18n.toAsciiDigits(in("ar-SA-u-ca-islamic-umalqura", longDate, day)).contains("1447"),
                "the Hijri year, in the digits the toolkit writes for Saudi Arabia");
        assertEquals(in("en-US", shortDate, day), in("en-US-u-ca-unknowncal", shortDate, day),
                "a calendar the platform does not carry is read as none");
    }

    /**
     * A day the locale's calendar does not reach is written in the ISO calendar, as the date
     * widgets draw it, where the platform refuses it: Umm al-Qura covers 1882 to 2174, so the
     * {@code 9999-12-31} that business data writes for "no end date" and the {@code 0001-01-01} it
     * writes for "no start" threw under {@code ar-SA-u-ca-islamic-umalqura}, and the Japanese
     * calendar begins at Meiji 6, 1873. In a table's date column the throw crashed every frame.
     * Every style, the date of a date and time, and the time of day alone of one; a day inside the
     * calendar keeps it, and a value the format cannot write is still refused.
     */
    @Test
    void aDayTheLocalesCalendarDoesNotReachIsWrittenInTheIsoCalendar() {
        String hijri = "ar-SA-u-ca-islamic-umalqura";
        for (FormatStyle style : FormatStyle.values()) {
            Function<TemporalAccessor, String> date = DateFormats.date(style);
            for (LocalDate placeholder : new LocalDate[] {LocalDate.of(9999, 12, 31), LocalDate.of(1, 1, 1)}) {
                assertEquals(in("ar-SA", date, placeholder), in(hijri, date, placeholder), style + " " + placeholder);
            }
            assertEquals(in("ja-JP", date, LocalDate.of(1, 1, 1)), in("ja-JP-u-ca-japanese", date, LocalDate.of(1, 1, 1)),
                    style + ", before Meiji 6");
            assertEquals(in("ja-JP", date, LocalDate.of(1872, 12, 31)),
                    in("ja-JP-u-ca-japanese", date, LocalDate.of(1872, 12, 31)), style + ", the day before it");
        }
        assertEquals("M6/1/1", in("ja-JP-u-ca-japanese", DateFormats.date(FormatStyle.SHORT), LocalDate.of(1873, 1, 1)),
                "Meiji 6 itself is the calendar's");
        assertTrue(I18n.toAsciiDigits(in(hijri, DateFormats.date(FormatStyle.SHORT), LocalDate.of(2174, 11, 25)))
                .contains("1600"), "Umm al-Qura's last day is still the calendar's");
        LocalDateTime noEnd = LocalDateTime.of(9999, 12, 31, 23, 59);
        for (FormatStyle style : new FormatStyle[] {FormatStyle.SHORT, FormatStyle.MEDIUM}) {
            assertEquals(in("ar-SA", DateFormats.dateTime(style), noEnd), in(hijri, DateFormats.dateTime(style), noEnd),
                    style + " date and time");
            assertEquals(in("ar-SA", DateFormats.time(style), noEnd), in(hijri, DateFormats.time(style), noEnd),
                    style + " time of a date and time");
        }
        assertThrows(java.time.DateTimeException.class,
                () -> in("ja-JP-u-ca-japanese", DateFormats.date(FormatStyle.SHORT), HOUR),
                "a time of day has no date to write");
        assertThrows(java.time.DateTimeException.class,
                () -> in(hijri, DateFormats.dateTime(FormatStyle.SHORT), LocalDate.of(9999, 12, 31)),
                "a date outside the calendar still has no time to write");
    }
}

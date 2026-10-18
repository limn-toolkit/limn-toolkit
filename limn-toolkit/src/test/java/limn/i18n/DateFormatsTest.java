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
}

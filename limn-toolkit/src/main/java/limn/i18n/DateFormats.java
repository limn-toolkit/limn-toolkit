package limn.i18n;

import limn.i18n.internal.LocaleServices;

import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Ready-made date and time formats, beside {@link NumberFormats}: what a table puts in a date
 * column, and what any caption that shows a day or an hour can use.
 *
 * <p>Every format here follows the {@linkplain I18n#locale() language in effect where it
 * formats}, read at that moment, as {@link NumberFormats}' do: the order of day, month and year,
 * the separators and a month's name are the language's, and a widget inside a subtree that
 * declares its own locale formats in that subtree's language. <b>The digits are the toolkit's</b>,
 * the {@linkplain I18n#numberingSystem() numbering system's}, as every number it writes: the
 * platform writes a date in ASCII digits whatever the language, so under {@code ar-EG} a date
 * beside an amount read {@code 17/10/2026} next to {@code ١٬٢٣٤٫٥}, where these write
 * {@code ١٧/١٠/٢٠٢٦}.
 *
 * <pre>{@code
 * Column.of("Due", Order::due, DateFormats.date(FormatStyle.SHORT));  // "10/17/26", "17/10/2026"
 * label.setText(DateFormats.time(FormatStyle.SHORT).apply(at));       // "2:09 PM", "14:09"
 * }</pre>
 *
 * <p><b>The calendar is the locale's.</b> A locale that names a calendar system with the Unicode
 * extension {@code -u-ca-}, as a user's settings write a choice of calendar, is written in it, as
 * the date widgets draw it: {@code ja-JP-u-ca-japanese} writes the era ("R7/10/25"),
 * {@code th-TH-u-ca-buddhist} the Buddhist year (2568), {@code ar-SA-u-ca-islamic-umalqura} the
 * Hijri date. The value is the same {@code LocalDate} either way; only how it is written changes.
 * A calendar the platform does not carry is read as none, and a locale that names none is written
 * in the ISO calendar, which is what the platform answers for every language without one.
 *
 * <p>A format takes any temporal that holds the fields it writes: a {@code LocalDate} for a date,
 * a {@code LocalTime} for a time, a {@code LocalDateTime} for both, and a zoned or offset one for
 * any of them. One that lacks a field the format writes is refused by the platform with a
 * {@code DateTimeException}.
 */
public final class DateFormats {

    private DateFormats() {
    }

    /**
     * A date at {@code style}'s length: {@code SHORT} writes {@code 10/17/26} in English and
     * {@code 17/10/2026} in Brazilian Portuguese, {@code MEDIUM} writes {@code Oct 17, 2026}, and
     * {@code LONG} and {@code FULL} write the month, and the weekday, in words.
     *
     * @param style how long the date is written; never {@code null}
     * @return the format
     */
    public static Function<TemporalAccessor, String> date(FormatStyle style) {
        Objects.requireNonNull(style, "style");
        return written(style, LocaleServices::localizedDate);
    }

    /**
     * A time of day at {@code style}'s length: {@code SHORT} writes {@code 2:09 PM} in English and
     * {@code 14:09} in Brazilian Portuguese, {@code MEDIUM} adds the seconds.
     *
     * @param style {@code SHORT} or {@code MEDIUM}; never {@code null}
     * @return the format
     * @throws IllegalArgumentException for {@code LONG} or {@code FULL}, which write a time zone
     *         that a time of day does not have, and which the platform would refuse only when the
     *         first value was formatted
     */
    public static Function<TemporalAccessor, String> time(FormatStyle style) {
        return written(withoutZone(style), LocaleServices::localizedTime);
    }

    /**
     * A date and a time of day, both at {@code style}'s length: {@code SHORT} writes
     * {@code 10/17/26, 2:09 PM} in English and {@code 17/10/2026, 14:09} in Brazilian Portuguese.
     *
     * @param style {@code SHORT} or {@code MEDIUM}; never {@code null}
     * @return the format
     * @throws IllegalArgumentException for {@code LONG} or {@code FULL}, as {@link #time} does
     */
    public static Function<TemporalAccessor, String> dateTime(FormatStyle style) {
        return written(withoutZone(style), LocaleServices::localizedDateTime);
    }

    private static FormatStyle withoutZone(FormatStyle style) {
        Objects.requireNonNull(style, "style");
        if (style == FormatStyle.LONG || style == FormatStyle.FULL) {
            throw new IllegalArgumentException(style + " writes a time zone, which a time of day "
                    + "does not have; SHORT and MEDIUM write a time without one");
        }
        return style;
    }

    /**
     * The format of {@code style} in the language in effect when it is applied, with the
     * toolkit's digits. The formatter is asked of the language as ICU reads its code; whatever
     * digits it writes are folded to ASCII and then written in the active numbering system, as
     * {@link NumberFormats} does with its own.
     */
    private static Function<TemporalAccessor, String> written(FormatStyle style,
            BiFunction<FormatStyle, Locale, DateTimeFormatter> formatter) {
        return value -> {
            Locale locale = I18n.locale();
            DateTimeFormatter format = formatter.apply(style, locale);
            Chronology calendar = calendarOf(locale);
            if (calendar != IsoChronology.INSTANCE) {
                format = format.withChronology(calendar);
            }
            return I18n.localizeDigits(I18n.toAsciiDigits(format.format(Objects.requireNonNull(value, "value"))));
        };
    }

    /**
     * The calendar system {@code locale} names with {@code -u-ca-}, as the date widgets read it;
     * ISO for one it names none of or one the platform does not carry.
     */
    private static Chronology calendarOf(Locale locale) {
        if (locale.getUnicodeLocaleType("ca") == null) {
            return IsoChronology.INSTANCE;
        }
        try {
            return LocaleServices.chronology(locale);
        } catch (RuntimeException unknown) {
            return IsoChronology.INSTANCE;
        }
    }
}

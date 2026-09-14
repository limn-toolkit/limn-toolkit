package limn.components.date;

import limn.i18n.I18n;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place an ISO date becomes a date in the calendar system being drawn, and the one place
 * that conversion is allowed to fail quietly.
 *
 * <p>ADR 042 &sect;1: the application exchanges {@link LocalDate}, and which calendar the widget
 * draws is a display axis resolved from the locale. Nearly always that axis is ISO &mdash;
 * {@link Chronology#ofLocale} answers {@code IsoChronology} for Arabic, Thai, Hebrew, Persian and
 * Japanese alike, and answers anything else only when the locale carries a {@code u-ca} extension
 * &mdash; so the common path here converts nothing at all.
 *
 * <p><b>Why the failure has to be quiet.</b> A chronology has a range and it is not always wide:
 * {@link java.time.chrono.HijrahChronology} covers AH 1300&ndash;1600, which is about 1882 to 2174,
 * and asking it for a date outside that throws {@link java.time.DateTimeException}. Every call here
 * is on a measure, paint or accessibility path, and a widget that threw out of a frame because
 * somebody set a minimum date in 1850 would take the window down over a display detail. So a
 * conversion that cannot be made answers {@code null}, the caller draws that cell blank or falls
 * back to ISO for the whole grid, and the value the application holds is untouched either way.
 */
final class CalendarChronology {

    private CalendarChronology() {
    }

    /**
     * Formatters are built once per language and calendar and then reused: building one costs a
     * pattern parse and a resource lookup, and a calendar grid would otherwise pay that per cell
     * per frame. Bounded by the number of (locale, chronology, pattern) triples an application
     * actually uses, which is one or two.
     */
    private static final Map<String, DateTimeFormatter> FORMATTERS = new ConcurrentHashMap<>();

    /**
     * The calendar system to draw in.
     *
     * @param declared what the widget was told to use, or {@code null} to follow the language
     * @param locale   the widget's effective locale
     * @return the chronology; never {@code null}
     */
    static Chronology resolve(Chronology declared, Locale locale) {
        if (declared != null) {
            return declared;
        }
        try {
            return Chronology.ofLocale(locale);
        } catch (RuntimeException e) {
            // A locale naming a calendar the runtime does not carry. ISO is not a guess here: it
            // is what the same call answers for every locale that names nothing.
            return IsoChronology.INSTANCE;
        }
    }

    /**
     * The chronology a grid anchored at {@code anchor} can actually be drawn in.
     *
     * <p>Falls back to ISO as a whole rather than per cell, because a month header in one calendar
     * over day numbers in another is worse than being shown the ISO calendar in a year the reader's
     * own calendar does not reach.
     *
     * @param chronology what was resolved
     * @param anchor     a day the grid has to be able to show
     * @return {@code chronology}, or {@link IsoChronology} if it cannot represent {@code anchor}
     */
    static Chronology usableFor(Chronology chronology, LocalDate anchor) {
        return date(chronology, anchor) == null ? IsoChronology.INSTANCE : chronology;
    }

    /**
     * @param chronology the calendar to convert into
     * @param iso        the day, or {@code null}
     * @return the same day in {@code chronology}, or {@code null} if it has no date there
     */
    static ChronoLocalDate date(Chronology chronology, LocalDate iso) {
        if (iso == null) {
            return null;
        }
        if (chronology == IsoChronology.INSTANCE) {
            return iso; // LocalDate IS the ISO chronology's date: no conversion, no allocation
        }
        try {
            return chronology.date(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * @param date a date in any calendar, or {@code null}
     * @return the same day as an ISO {@link LocalDate}, or {@code null} if it has none
     */
    static LocalDate iso(ChronoLocalDate date) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.from(date);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code date.plus(amount, unit)}, or {@code null} where the calendar has no such date: a
     * Hijri month before AH 1300 or after AH 1600 throws {@code DateTimeException} out of the
     * JDK, and a grid standing at either end of the range asks for exactly that month -- for
     * the leading cells, for the paging arrows, for a chooser cell's period end. Every caller
     * has an ISO fallback for a null, the way {@link #date} and {@link #iso} already give them.
     */
    static ChronoLocalDate plus(ChronoLocalDate date, long amount, ChronoUnit unit) {
        try {
            return date.plus(amount, unit);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * The ISO date of the first day of the month, <b>in the calendar being drawn</b>, that holds a
     * day: the one answer to "which month is this" that is right in every chronology. A Hijri
     * month starts three weeks into an ISO one, so {@code day.withDayOfMonth(1)} names a day of
     * the <em>previous</em> Hijri month for most of the year; paging, the choosers and the cursor
     * all went wrong on exactly that until 2026-09-14 (DATES-NEW-1).
     *
     * @param chronology the calendar being drawn
     * @param day        any day
     * @return the ISO date of that month's first day; the ISO month's first when the chronology
     *         cannot hold the day, which is the same fallback the grid draws
     */
    static LocalDate firstOfMonth(Chronology chronology, LocalDate day) {
        ChronoLocalDate drawn = date(chronology, day);
        if (drawn != null) {
            LocalDate first = iso(drawn.with(java.time.temporal.ChronoField.DAY_OF_MONTH, 1));
            if (first != null) {
                return first;
            }
        }
        return day.withDayOfMonth(1);
    }

    /**
     * Whether the calendar's years are short enough that the era is part of what a year means:
     * Reiwa 8 and Minguo 115 say nothing without their era, where 2026, 2569 (Buddhist) and 1448
     * (Hijri) do. Read off the year of era <em>today</em> rather than off a list of chronologies,
     * so a calendar the runtime adds later is classified by the same rule.
     *
     * <p>This is the settled reading of ADR 042 &sect;3's widening rule (era-year-width,
     * 2026-09-14): the four-digit widening removes a two-digit year's ambiguity, and a year of era
     * that is one to three digits long inside a named era is not ambiguous.
     *
     * @param chronology the calendar being drawn
     * @param today      the widget's today
     * @return whether a year of this calendar is spoken and drawn with its era
     */
    static boolean yearsNameTheirEra(Chronology chronology, LocalDate today) {
        ChronoLocalDate drawn = date(chronology, today);
        return drawn != null && drawn.get(java.time.temporal.ChronoField.YEAR_OF_ERA) < 1000;
    }

    /**
     * The year with its era, the way the language writes the pair &mdash; "令和8", "民國115" &mdash;
     * for a calendar {@link #yearsNameTheirEra} says needs one.
     *
     * @param chronology the calendar being drawn
     * @param date       a day of the year
     * @param locale     the language in effect
     * @param narrow     whether to use the one-letter era ("R8") that fits a chooser cell, or the
     *                   full name a reader is told
     * @return the labelled year
     */
    static String eraYear(Chronology chronology, ChronoLocalDate date, Locale locale,
                          boolean narrow) {
        DateTimeFormatter formatter = FORMATTERS.computeIfAbsent(
                (narrow ? "eraN " : "era ") + locale.toLanguageTag() + ' ' + chronology.getId(),
                key -> DateTimeFormatter.ofPattern(narrow ? "GGGGGy" : "GGGGy", locale)
                        .withChronology(chronology));
        return I18n.localizeDigits(formatter.format(date));
    }

    /**
     * The first day of the week where this language is spoken: Monday in Germany, Sunday in Brazil
     * and the United States, Saturday in Egypt.
     *
     * @param locale the language in effect
     * @return the day a week starts on
     */
    static DayOfWeek firstDayOfWeek(Locale locale) {
        return WeekFields.of(locale).getFirstDayOfWeek();
    }

    /**
     * The week numbering this language uses, which is <b>not</b> ISO's everywhere: Germany counts a
     * week as belonging to the year that holds four of its days, the United States to the year that
     * holds its first day, and the two disagree about the turn of most years. A calendar that showed
     * one of them the other's number would be showing a number that is wrong where they work.
     *
     * @param locale the language in effect
     * @return its week fields
     */
    static WeekFields weekFields(Locale locale) {
        return WeekFields.of(locale);
    }

    /**
     * What goes above the grid: the month and the year, in the calendar being drawn, written the way
     * the language writes them.
     *
     * <p>Derived from the locale's own long date pattern with the day taken out, rather than from a
     * pattern written here. The Portuguese long pattern minus its day keeps the particle that joins
     * the month to the year; the Japanese one minus its day keeps the year first and the two ideographs
     * that mark the year and the month. A hand-written {@code "MMMM y"} would be wrong in every
     * language that puts the year first or joins the two with a word.
     *
     * @param chronology the calendar being drawn
     * @param month      any day in the month to name
     * @param locale     the language in effect
     * @return the header text, with the digits of the language's numbering system
     */
    static String monthHeader(Chronology chronology, ChronoLocalDate month, Locale locale) {
        DateTimeFormatter formatter = FORMATTERS.computeIfAbsent(
                "hdr " + locale.toLanguageTag() + ' ' + chronology.getId(),
                key -> DateTimeFormatter.ofPattern(monthYearPattern(chronology, locale), locale)
                        .withChronology(chronology));
        // ASCII out of the formatter, then the numbering system: ADR 033's one format-time seam.
        return I18n.localizeDigits(formatter.format(month));
    }

    /**
     * The whole date, spoken rather than looked at: what a screen reader is told a day cell is.
     *
     * <p>The long style and not the short one, for the reason ADR 042 &sect;8 gives: a cell heard on
     * its own has to say what it is, and a bare number is not a date.
     *
     * @param chronology the calendar being drawn
     * @param date       the day
     * @param locale     the language in effect
     * @return the full date
     */
    static String fullDate(Chronology chronology, ChronoLocalDate date, Locale locale) {
        DateTimeFormatter formatter = FORMATTERS.computeIfAbsent(
                "full " + locale.toLanguageTag() + ' ' + chronology.getId(),
                key -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                        .withLocale(locale).withChronology(chronology));
        return I18n.localizeDigits(formatter.format(date));
    }

    /**
     * The one-or-two letter weekday head of a column, standalone: a column head is not part of a
     * sentence, and several languages inflect the two differently.
     *
     * @param day    which weekday
     * @param locale the language in effect
     * @return its narrow name
     */
    static String narrowWeekday(DayOfWeek day, Locale locale) {
        return day.getDisplayName(TextStyle.NARROW_STANDALONE, locale);
    }

    /**
     * The whole weekday name, for what a reader is told a column head is: one narrow letter spoken
     * aloud is not a day of the week, and a reader crossing the header row would hear seven of them.
     *
     * @param day    which weekday
     * @param locale the language in effect
     * @return its full name
     */
    static String fullWeekday(DayOfWeek day, Locale locale) {
        return day.getDisplayName(TextStyle.FULL_STANDALONE, locale);
    }

    /**
     * Builds the month-and-year pattern by removing the day field from the locale's long date
     * pattern, together with <b>one</b> of the literals beside it: the one that follows it if there
     * is one, otherwise the one before.
     *
     * <p>That rule is the whole of it, and it is right in every pattern the probe of 2026-09-09
     * recorded. The English pattern drops its day and the comma-and-space after it; taking only the
     * field would leave a stranded comma between the month and the year. The Portuguese one drops
     * the day and the first of its two particles. The Japanese and Korean ones have no literal after
     * the day, so they drop the ideograph before it. The Hebrew one drops the particle glued to the
     * front of the month.
     *
     * <p>Literals are re-emitted quoted, because a literal may be letters: the Portuguese particle
     * is two pattern letters that would otherwise be read as a day-of-year and a day-period.
     */
    private static String monthYearPattern(Chronology chronology, Locale locale) {
        List<DatePattern.Part> parts = DatePattern.parse(
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                        FormatStyle.LONG, null, chronology, locale));
        int day = -1;
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) instanceof DatePattern.FieldPart f
                    && f.field() == DatePattern.Field.DAY) {
                day = i;
                break;
            }
        }
        if (day < 0) {
            return "MMMM y"; // no day to remove: a pattern shaped unlike anything measured
        }
        int companion = day + 1 < parts.size() && parts.get(day + 1) instanceof DatePattern.Literal
                ? day + 1
                : day > 0 && parts.get(day - 1) instanceof DatePattern.Literal ? day - 1 : -1;
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i == day || i == companion) {
                continue;
            }
            if (parts.get(i) instanceof DatePattern.FieldPart f) {
                pattern.append(f.pattern());
            } else if (parts.get(i) instanceof DatePattern.Literal l) {
                pattern.append('\'').append(l.text().replace("'", "''")).append('\'');
            }
        }
        return pattern.toString();
    }
}

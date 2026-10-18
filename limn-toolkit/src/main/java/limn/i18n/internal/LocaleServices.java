package limn.i18n.internal;

import java.text.BreakIterator;
import java.text.Collator;
import java.time.DayOfWeek;
import java.time.Month;
import java.time.chrono.Chronology;
import java.time.chrono.Era;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * The platform's services that choose by language, each asked in the language ICU reads the
 * locale's code as.
 *
 * <p>The platform knows a language by one code. Under {@code fra} or {@code deu}, the ISO 639-2
 * codes for French and German, it formats a date as the root locale does ("2026 Mar 1" where
 * French writes "1 mars 2026"), names Monday "Mon" where German says "Montag", orders and
 * case-maps as no language at all, and under {@code tha} it breaks Thai text without the Thai
 * dictionary, so a line of Thai has no break in it. Every call here reads the locale through
 * {@link LanguageAliases#resolve} first, and every place in the toolkit and its backend that asks
 * the platform one of these things asks it here; a test reads the sources to hold them to it.
 * A number goes through {@link NumberLocale}, which reads the locale the same way.
 */
public final class LocaleServices {

    private LocaleServices() {
    }

    /**
     * A formatter for {@code pattern} in {@code locale}'s language.
     *
     * @param pattern a {@link DateTimeFormatter} pattern
     * @param locale  the language in effect
     * @return the formatter
     */
    public static DateTimeFormatter formatter(String pattern, Locale locale) {
        return DateTimeFormatter.ofPattern(pattern, LanguageAliases.resolve(locale));
    }

    /**
     * The language's own date format of {@code style}: "1 mars 2026" for a long French date.
     *
     * @param style  how much of the date to write
     * @param locale the language in effect
     * @return the formatter
     */
    public static DateTimeFormatter localizedDate(FormatStyle style, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(style).withLocale(LanguageAliases.resolve(locale));
    }

    /**
     * The language's own time-of-day format of {@code style}: "14:09" for a short French time.
     *
     * @param style  how much of the time to write
     * @param locale the language in effect
     * @return the formatter
     */
    public static DateTimeFormatter localizedTime(FormatStyle style, Locale locale) {
        return DateTimeFormatter.ofLocalizedTime(style).withLocale(LanguageAliases.resolve(locale));
    }

    /**
     * The language's own format of a date and a time, both of {@code style}.
     *
     * @param style  how much of each to write
     * @param locale the language in effect
     * @return the formatter
     */
    public static DateTimeFormatter localizedDateTime(FormatStyle style, Locale locale) {
        return DateTimeFormatter.ofLocalizedDateTime(style).withLocale(LanguageAliases.resolve(locale));
    }

    /**
     * The pattern the language writes a date, a time or both in, for a calendar system.
     *
     * @param date       the date style, or {@code null} for a time alone
     * @param time       the time style, or {@code null} for a date alone
     * @param chronology the calendar system
     * @param locale     the language in effect
     * @return the pattern
     */
    public static String localizedPattern(FormatStyle date, FormatStyle time, Chronology chronology,
                                          Locale locale) {
        return DateTimeFormatterBuilder.getLocalizedDateTimePattern(date, time, chronology,
                LanguageAliases.resolve(locale));
    }

    /**
     * A weekday's name in the language.
     *
     * @param day    the weekday
     * @param style  how long a name
     * @param locale the language in effect
     * @return the name
     */
    public static String name(DayOfWeek day, TextStyle style, Locale locale) {
        return day.getDisplayName(style, LanguageAliases.resolve(locale));
    }

    /**
     * A month's name in the language.
     *
     * @param month  the month
     * @param style  how long a name
     * @param locale the language in effect
     * @return the name
     */
    public static String name(Month month, TextStyle style, Locale locale) {
        return month.getDisplayName(style, LanguageAliases.resolve(locale));
    }

    /**
     * An era's name in the language.
     *
     * @param era    the era
     * @param style  how long a name
     * @param locale the language in effect
     * @return the name
     */
    public static String name(Era era, TextStyle style, Locale locale) {
        return era.getDisplayName(style, LanguageAliases.resolve(locale));
    }

    /**
     * The calendar system a locale names in its {@code ca} keyword, ISO where it names none.
     *
     * @param locale the language in effect
     * @return the chronology
     * @throws java.time.DateTimeException when the locale names one the runtime does not carry
     */
    public static Chronology chronology(Locale locale) {
        return Chronology.ofLocale(LanguageAliases.resolve(locale));
    }

    /**
     * The week a locale's region keeps: its first day and how many days make a first week.
     *
     * @param locale a locale that names its region
     * @return the week fields
     */
    public static WeekFields weekFields(Locale locale) {
        return WeekFields.of(LanguageAliases.resolve(locale));
    }

    /**
     * Where a line of text in the language may break: the Thai dictionary's word ends under
     * {@code th} and {@code tha} alike.
     *
     * @param locale the language in effect
     * @return a fresh iterator, which carries its own position and must not be shared
     */
    public static BreakIterator lineBreaks(Locale locale) {
        return BreakIterator.getLineInstance(LanguageAliases.resolve(locale));
    }

    /**
     * Where the words of a text in the language begin and end.
     *
     * @param locale the language in effect
     * @return a fresh iterator
     */
    public static BreakIterator wordBreaks(Locale locale) {
        return BreakIterator.getWordInstance(LanguageAliases.resolve(locale));
    }

    /**
     * Where the sentences of a text in the language begin and end.
     *
     * @param locale the language in effect
     * @return a fresh iterator
     */
    public static BreakIterator sentenceBreaks(Locale locale) {
        return BreakIterator.getSentenceInstance(LanguageAliases.resolve(locale));
    }

    /**
     * A collator for the language: {@code swe} puts ä after z as Swedish does.
     *
     * @param locale the language the text is in
     * @return a fresh collator, which carries per-comparison state and must not be shared
     */
    public static Collator collator(Locale locale) {
        return Collator.getInstance(LanguageAliases.resolve(locale));
    }

    /**
     * {@code text} upper-cased as the language does it: {@code tur} maps i to a dotted İ.
     *
     * @param text   the text
     * @param locale the language the text is in
     * @return the upper-cased text
     */
    public static String upperCase(String text, Locale locale) {
        return text.toUpperCase(LanguageAliases.resolve(locale));
    }

    /**
     * {@code text} lower-cased as the language does it: {@code tur} maps I to a dotless ı.
     *
     * @param text   the text
     * @param locale the language the text is in
     * @return the lower-cased text
     */
    public static String lowerCase(String text, Locale locale) {
        return text.toLowerCase(LanguageAliases.resolve(locale));
    }

    /**
     * The tag to tell a platform, a screen reader above all, which language a text is in: the
     * language its code stands for, {@code fa} for {@code pes}, so the reader finds a voice.
     *
     * @param locale the language, or {@code null}
     * @return its tag, or the empty string for {@code null}
     */
    public static String languageTag(Locale locale) {
        return locale == null ? "" : LanguageAliases.resolve(locale).toLanguageTag();
    }
}

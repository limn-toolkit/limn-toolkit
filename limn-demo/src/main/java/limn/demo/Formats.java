package limn.demo;

import limn.i18n.DateFormats;
import limn.i18n.NumberFormats;

import java.time.LocalDate;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAccessor;

/**
 * How the Showcase writes a file's size, a date and a time of day, so that every page writes each
 * of them one way and in the language in effect.
 *
 * <p>Each answer is asked of the language when it is called, so a line that shows one follows a
 * change of language only when it asks again: through {@link Page#followLanguage} or
 * {@link Page#label}. A page that wrote its own once wrote it with the formatter at hand, and two
 * of them shipped that way: the menus page wrote {@code 1.3 MB} under Brazilian Portuguese, a few
 * pages from a tree that wrote {@code 2,8 MB}, and the clipboard page filled its form with
 * {@code 1,250.00} and {@code 2026-11-14}.
 */
final class Formats {

    private static final long KILOBYTE = 1024;

    private Formats() {
    }

    /**
     * A file's size as a file manager writes it: whole kilobytes under a megabyte and megabytes to
     * a tenth above, of 1024 bytes each, with the language's decimal separator ({@code 2.8 MB} in
     * English, {@code 2,8 MB} in Brazilian Portuguese) and its digits. Never under 1 KB, so a file
     * of a few bytes does not read as empty. The units are written as they are in every
     * language, as the tree's sizes always were.
     */
    static String size(long bytes) {
        double kilobytes = bytes / (double) KILOBYTE;
        // Compared once rounded, as it is written: a byte short of a megabyte is 1 MB, not 1,024 KB.
        return Math.round(kilobytes) < KILOBYTE
                ? NumberFormats.number(0).apply(Math.max(1, kilobytes)) + " KB"
                : NumberFormats.number(1).apply(kilobytes / KILOBYTE) + " MB";
    }

    /**
     * A date as the language writes it short: {@code 11/14/26} in English, {@code 14/11/2026} in
     * Brazilian Portuguese.
     */
    static String date(LocalDate date) {
        return date(date, FormatStyle.SHORT);
    }

    /**
     * A date at {@code style}'s length: {@code MEDIUM} reads {@code Oct 9, 2026} in English. In
     * the language's digits, as {@link DateFormats} writes every date: under {@code ar-EG} a date
     * beside an amount reads in the amount's digits.
     */
    static String date(LocalDate date, FormatStyle style) {
        return DateFormats.date(style).apply(date);
    }

    /**
     * A time of day as the language writes it short: {@code 2:09 PM} in English, {@code 14:09} in
     * Brazilian Portuguese.
     */
    static String time(TemporalAccessor time) {
        return DateFormats.time(FormatStyle.SHORT).apply(time);
    }
}

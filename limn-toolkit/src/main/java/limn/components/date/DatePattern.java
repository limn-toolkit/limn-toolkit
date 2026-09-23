package limn.components.date;

import java.time.chrono.Chronology;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A locale's date or time pattern, taken apart into the segments a field can edit and the literals
 * between them.
 *
 * <p>The pattern itself is the JDK's, from
 * {@link DateTimeFormatterBuilder#getLocalizedDateTimePattern}, which is CLDR's answer for that
 * locale and that calendar system. What this class adds is the split: {@code dd/MM/y} becomes day,
 * {@code "/"}, month, {@code "/"}, year, and {@code yy. M. d.} becomes year, {@code ". "}, month,
 * {@code ". "}, day, {@code "."} &mdash; including that last literal, which is real and is what
 * makes a Korean date look Korean.
 *
 * <p><b>Every literal is kept verbatim, control characters included.</b> The Arabic short pattern
 * is {@code d‏/M‏/y}: those two {@code U+200F} right-to-left marks are what make the
 * separators sit correctly when the run is read inside a bidirectional line, and a parser that
 * treated them as noise would produce a field that looks subtly wrong in exactly the language it
 * was localized for.
 *
 * <p><b>Date and time are parsed separately and never split out of one pattern.</b> Asking the JDK
 * for a date-only pattern and a time-only pattern and joining them gives two lists that contain
 * only the fields they should; carving the time fields out of a combined pattern would leave
 * orphaned literals behind ({@code " at "}, {@code "às"}) and the rule for which of them to drop
 * is a guess in every language nobody in the room reads.
 */
final class DatePattern {

    private DatePattern() {
    }

    /**
     * What one run of pattern letters names. {@link #ERA} and {@link #OTHER} are the two a field
     * shows and does not let anyone edit; see {@link #editable()}.
     */
    enum Field {
        /**
         * {@code G}: the era. Read-only: editing it, with the year renumbering across a
         * transition, is work no other chronology asks for.
         */
        ERA,
        /** {@code y}/{@code u}: the year of the era, in the chronology being drawn. */
        YEAR,
        /** {@code M}/{@code L}: the month of the year, numeric or named by its width. */
        MONTH,
        /** {@code d}: the day of the month. */
        DAY,
        /** {@code H}/{@code k}: the hour on a 24-hour clock. */
        HOUR24,
        /** {@code h}/{@code K}: the hour on a 12-hour clock, which arrives with a day period. */
        HOUR12,
        /** {@code m}: the minute. */
        MINUTE,
        /** {@code s}: the second. */
        SECOND,
        /** {@code a}/{@code b}/{@code B}: AM or PM, or the flexible period some locales use. */
        DAY_PERIOD,
        /**
         * A pattern letter this field does not edit: a weekday name, a quarter, a zone. Drawn from
         * the value and skipped by the keyboard, so a locale whose medium pattern carries one is
         * rendered faithfully rather than refused.
         */
        OTHER;

        /** Whether the keyboard stops on this segment at all. */
        boolean editable() {
            return this != ERA && this != OTHER;
        }

        /** Whether digits typed into this segment mean anything. */
        boolean numeric() {
            return editable() && this != DAY_PERIOD;
        }

        /**
         * Which pattern letter names this field, or {@code 0} for one that is not a field.
         *
         * <p>{@code u} and {@code y} are both the year here, which is a deliberate flattening: they
         * differ over how the proleptic year is numbered before the common era, and a field that
         * edits a date somebody typed is not where that distinction is decided.
         */
        static Field of(char letter) {
            return switch (letter) {
                case 'G' -> ERA;
                case 'y', 'u' -> YEAR;
                case 'M', 'L' -> MONTH;
                case 'd' -> DAY;
                case 'H', 'k' -> HOUR24;
                case 'h', 'K' -> HOUR12;
                case 'm' -> MINUTE;
                case 's' -> SECOND;
                case 'a', 'b', 'B' -> DAY_PERIOD;
                default -> OTHER;
            };
        }
    }

    /** One piece of a pattern: text that is drawn as it stands, or a field taken from the value. */
    sealed interface Part {
    }

    /**
     * Text between fields, drawn exactly as the pattern carries it.
     *
     * @param text the run, quotes already resolved and bidi control characters intact
     */
    record Literal(String text) implements Part {
    }

    /**
     * One run of pattern letters.
     *
     * @param field   what it names
     * @param width   how many letters long the run was: {@code MM} is width 2, and for a month it
     *                is also the difference between {@code 09} and {@code September}
     * @param pattern the run itself, kept so a read-only field can be formatted by handing this
     *                straight back to a {@link java.time.format.DateTimeFormatter}
     */
    record FieldPart(Field field, int width, String pattern) implements Part {
    }

    /**
     * The locale's short date pattern for a calendar system, taken apart.
     *
     * @param chronology what calendar the pattern is for; a Japanese one carries an era where an
     *                   ISO one does not
     * @param locale     whose convention to follow
     * @return the parts, in the order the locale writes them
     */
    static List<Part> shortDate(Chronology chronology, Locale locale) {
        return parse(DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, null, chronology, locale));
    }

    /**
     * The locale's time pattern, taken apart.
     *
     * @param seconds whether the seconds field is wanted; {@code SHORT} carries hours and minutes
     *                everywhere, and the medium style is the one that adds seconds
     * @param locale  whose convention to follow
     * @return the parts, in the order the locale writes them
     */
    static List<Part> time(boolean seconds, Locale locale) {
        return parse(DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                null, seconds ? FormatStyle.MEDIUM : FormatStyle.SHORT,
                java.time.chrono.IsoChronology.INSTANCE, locale));
    }

    /**
     * A pattern with one field cut out of it, together with the one literal that joined it to its
     * neighbour: the day and its separator go for a month field, the minute and its colon for an
     * hour field.
     *
     * <p>Which literal goes is the rule {@code CalendarChronology.monthYearPattern} already
     * applies to the long pattern, read the other way round: the one <em>before</em> the field
     * unless the field is the first part, in which case the one after it. So {@code dd/MM/y} loses
     * its leading day and the slash after it ({@code MM/y}), {@code M/d/yy} loses the middle day
     * and the slash before it ({@code M/yy}), and the Korean {@code yy. M. d.} loses the day and
     * the {@code ". "} before it while keeping the full stop that closes the date
     * ({@code yy. M.}), which is how Korean writes a year and a month.
     *
     * @param parts a pattern taken apart
     * @param field the field to cut
     * @return the same parts without that field and its joining literal; {@code parts} itself
     *         when the field is not there
     */
    static List<Part> without(List<Part> parts, Field field) {
        int at = -1;
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) instanceof FieldPart f && f.field() == field) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return parts;
        }
        int companion = at > 0 && parts.get(at - 1) instanceof Literal ? at - 1
                : at + 1 < parts.size() && parts.get(at + 1) instanceof Literal ? at + 1 : -1;
        List<Part> cut = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (i != at && i != companion) {
                cut.add(parts.get(i));
            }
        }
        return List.copyOf(cut);
    }

    /**
     * Splits a CLDR pattern into literals and field runs.
     *
     * <p>The three cases are the three the format has: a quoted run, where {@code ''} is one
     * apostrophe and anything else runs to the closing quote; a run of one repeated ASCII letter,
     * which is a field; and everything else, which is a literal. Adjacent literals are merged, so
     * {@code ". "} arrives as one part rather than as a full stop and a space.
     *
     * @param pattern a CLDR date or time pattern
     * @return its parts in order; never empty for a non-empty pattern
     */
    static List<Part> parse(String pattern) {
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                i++;
                if (i < pattern.length() && pattern.charAt(i) == '\'') {
                    literal.append('\''); // '' outside a quoted run is one apostrophe
                    i++;
                    continue;
                }
                while (i < pattern.length()) {
                    char quoted = pattern.charAt(i);
                    if (quoted != '\'') {
                        literal.append(quoted);
                        i++;
                        continue;
                    }
                    // A doubled quote INSIDE a quoted run is also one apostrophe, and reading it
                    // as a close followed by an open swallows it: 'o''clock' became "oclock".
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') {
                        literal.append('\'');
                        i += 2;
                        continue;
                    }
                    i++; // the closing quote
                    break;
                }
                continue;
            }
            if (isPatternLetter(c)) {
                if (!literal.isEmpty()) {
                    parts.add(new Literal(literal.toString()));
                    literal.setLength(0);
                }
                int start = i;
                while (i < pattern.length() && pattern.charAt(i) == c) {
                    i++;
                }
                String run = pattern.substring(start, i);
                parts.add(new FieldPart(Field.of(c), run.length(), run));
                continue;
            }
            literal.append(c);
            i++;
        }
        if (!literal.isEmpty()) {
            parts.add(new Literal(literal.toString()));
        }
        return List.copyOf(parts);
    }

    private static boolean isPatternLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}

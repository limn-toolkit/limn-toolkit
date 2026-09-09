package limn.components.date;

import limn.i18n.I18n;
import limn.i18n.PropertyBundle;
import limn.i18n.I18nString;

/**
 * The date widgets' own vocabulary: what each segment of a field is called, what the two paging
 * buttons are called, and what a field says when it is holding something it cannot accept.
 *
 * <p>None of this is a month name or a weekday: those come from the JDK's own CLDR data through
 * {@link CalendarChronology}, in every language it carries, and writing them here would be
 * translating a table that already exists and that is right in more languages than this file will
 * ever have. What is here is the chrome around them, which CLDR has no entry for.
 *
 * <p>Its own file family, registered when this class is first touched, exactly as
 * {@code ColorPickerStrings} and {@code ThemeStrings} do: a domain nobody uses costs nothing, and
 * touching one of its strings is what makes its translations available.
 */
final class DateStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/dates"));
    }

    /** Nothing: initialising this class is the whole of what a caller wants from it. */
    static void ensureRegistered() {
    }

    // The segments of a field, which is what a screen reader calls the spin button the caret is in.
    // A field publishes one of these per editable segment and nothing else can supply them: a
    // segment is not a widget, so an application's setAccessibleName lands on the field as a whole.

    static final I18nString SEGMENT_YEAR = new I18nString("limn.date.segment.year", "Year");

    static final I18nString SEGMENT_MONTH = new I18nString("limn.date.segment.month", "Month");

    static final I18nString SEGMENT_DAY = new I18nString("limn.date.segment.day", "Day");

    static final I18nString SEGMENT_HOUR = new I18nString("limn.date.segment.hour", "Hour");

    static final I18nString SEGMENT_MINUTE = new I18nString("limn.date.segment.minute", "Minute");

    static final I18nString SEGMENT_SECOND = new I18nString("limn.date.segment.second", "Second");

    /**
     * The AM/PM segment. Named for what it holds rather than for the two words it shows, because
     * several languages abbreviate those two and a reader that spoke only the abbreviation would be
     * saying two letters with no clue what they are answering.
     */
    static final I18nString SEGMENT_DAY_PERIOD =
            new I18nString("limn.date.segment.dayPeriod", "Before or after noon");

    /** The era, which is drawn and read and never edited (ADR 042 &sect;3). */
    static final I18nString SEGMENT_ERA = new I18nString("limn.date.segment.era", "Era");

    // The calendar's chrome.

    static final I18nString PREVIOUS_MONTH =
            new I18nString("limn.date.previousMonth", "Previous month");

    static final I18nString NEXT_MONTH = new I18nString("limn.date.nextMonth", "Next month");

    /** The head of the week-number column, which is a column head and so has to have a name. */
    static final I18nString WEEK_COLUMN = new I18nString("limn.date.weekColumn", "Week");

    /**
     * What a reader is told about today's cell, after the date itself. Today is drawn with a ring
     * around it and nothing else says so, which makes it exactly the kind of fact that reaches
     * everyone who looks and nobody who listens.
     */
    static final I18nString TODAY = new I18nString("limn.date.today", "today");

    // The picker.

    /** The trailing button that opens the grid; the picker's one painted affordance. */
    static final I18nString OPEN_CALENDAR = new I18nString("limn.date.openCalendar", "Open calendar");

    /** What the popup itself is, for a reader that lands in it rather than walking into it. */
    static final I18nString CALENDAR = new I18nString("limn.date.calendar", "Calendar");

    /** The two ends of a period, which are two fields and need telling apart. */
    static final I18nString RANGE_START = new I18nString("limn.date.rangeStart", "Start date");

    static final I18nString RANGE_END = new I18nString("limn.date.rangeEnd", "End date");

    // What a field says when what it holds is not acceptable. Published as the validity message,
    // so it is the sentence a reader hears after "invalid" rather than a colour on a border.

    static final I18nString INVALID_INCOMPLETE =
            new I18nString("limn.date.invalid.incomplete", "This date is incomplete");

    static final I18nString INVALID_OUT_OF_RANGE =
            new I18nString("limn.date.invalid.outOfRange", "This date is outside the allowed range");

    static final I18nString INVALID_UNAVAILABLE =
            new I18nString("limn.date.invalid.unavailable", "This date is not available");

    private DateStrings() {
    }
}

/**
 * Dates and times: a field that types one, a grid that shows a month, and the picker that is both.
 *
 * <p>{@link limn.components.date.DateField} is the segmented editor &mdash; its segments, their
 * order and the separators between them come from the language's own short pattern, so the same
 * code reads day/month/year in Portuguese and month/day/year in American English.
 * {@link limn.components.date.CalendarView} is a month grid a screen can hold on its own, with
 * bounds, a filter, week numbers and marks. {@link limn.components.date.DatePicker} composes them:
 * the field is a real child, the grid opens in a popup, and a period is two fields and one grid.
 *
 * <p><b>The value is always ISO.</b> Everything here hands out and takes in {@code LocalDate},
 * {@code LocalTime} and {@code LocalDateTime}. Which calendar system is <em>drawn</em> is a
 * separate axis resolved from the widget's language &mdash; Gregorian everywhere until a locale
 * carries a {@code u-ca} extension or an application names one &mdash; so a Thai user picks a day
 * out of a grid headed with a Buddhist year and the application stores the ISO date it asked for.
 *
 * <p>{@link limn.components.date.DateRange} is a period, both ends included and never inverted;
 * {@link limn.components.date.DayMark} is what is true of one day beyond its number, as a dot and
 * as a phrase a screen reader hears.
 */
package limn.components.date;

package limn.components.date;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A period between two days, both ends included.
 *
 * <p>Normalized on construction, so {@link #start()} is never after {@link #end()}: a range is a
 * span of days rather than a record of which end the user clicked first, and a widget that let the
 * two disagree would make every reader of a range write the same three-line swap.
 *
 * <p><b>A half-made range is not one.</b> While a person is picking a period the calendar holds an
 * anchor and a preview, and neither is a {@code DateRange};
 * {@link CalendarView#selectedRange()} answers {@code null} until the second end is chosen. That is
 * the whole reason both components here are non-null: a type whose {@code end} may be missing is a
 * type every caller has to null-check for a state that is never published.
 *
 * @param start the first day of the period, included
 * @param end   the last day of the period, included; never before {@code start}
 */
public record DateRange(LocalDate start, LocalDate end) {

    /**
     * @throws NullPointerException     if either end is {@code null}
     * @throws IllegalArgumentException if {@code end} is before {@code start}; use {@link #of} to
     *         build one from two days in either order
     */
    public DateRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end " + end + " is before start " + start
                    + "; use DateRange.of(a, b) to accept them in either order");
        }
    }

    /**
     * The period between two days, in whichever order they arrive. This is what a calendar builds
     * from an anchor and the day the second click landed on, and dragging a selection backwards is
     * ordinary rather than an error.
     *
     * @param a one end
     * @param b the other
     * @return the period covering both, ends included
     */
    public static DateRange of(LocalDate a, LocalDate b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return b.isBefore(a) ? new DateRange(b, a) : new DateRange(a, b);
    }

    /** A period of one day. */
    public static DateRange ofDay(LocalDate day) {
        return new DateRange(day, day);
    }

    /**
     * @param day a day, or {@code null}
     * @return whether it falls inside this period, both ends included; {@code false} for
     *         {@code null}, so a caller holding an unset value needs no guard of its own
     */
    public boolean contains(LocalDate day) {
        return day != null && !day.isBefore(start) && !day.isAfter(end);
    }

    /**
     * @return how many days the period covers, counting both ends; never less than 1
     */
    public long days() {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }
}

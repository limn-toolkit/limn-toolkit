package limn.components.date;

import limn.graphics.Color;
import limn.i18n.I18nString;

import java.util.Objects;

/**
 * What is true of one day beyond its number: a holiday, a deadline, a day with appointments.
 *
 * <p>A dot under the day's number and, optionally, a phrase that joins what a screen reader says
 * about that cell. Both halves matter and the second is the one that is usually forgotten: a
 * coloured dot is invisible to a reader and to anyone who cannot tell the colours apart, so a mark
 * that carries no description is a mark half the users of a form cannot perceive at all. It is
 * still allowed &mdash; a dot that only repeats what the surrounding screen already says is
 * decoration, and naming it would be noise &mdash; but it is the caller's decision rather than
 * the default.
 *
 * <p><b>The colour is the application's.</b> A holiday is red in a payroll screen and green in a
 * booking one, and the toolkit has no opinion about which; what it does have is
 * {@link limn.components.Theme}, so {@code Theme.current().danger} and its neighbours are the
 * colours a mark should normally be built from rather than a literal that will not follow a
 * palette.
 *
 * @param dot         the dot's colour; never {@code null}
 * @param description what a reader is told about the day, or {@code null} for a mark that is
 *                    decoration
 */
public record DayMark(Color dot, I18nString description) {

    public DayMark {
        Objects.requireNonNull(dot, "dot");
    }

    /**
     * A mark that is only a dot: nothing is added to what a reader hears.
     *
     * @param dot the dot's colour
     * @return the mark
     */
    public static DayMark of(Color dot) {
        return new DayMark(dot, null);
    }

    /**
     * A mark with a phrase a reader hears after the date.
     *
     * @param dot         the dot's colour
     * @param description what the day is; resolved in the calendar's own language, like every
     *                    other name in the accessible tree
     * @return the mark
     */
    public static DayMark of(Color dot, I18nString description) {
        return new DayMark(dot, Objects.requireNonNull(description, "description"));
    }
}

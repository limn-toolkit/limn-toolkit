package limn.demo.site;

import limn.components.Label;
import limn.components.Theme;
import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.components.date.DateRange;
import limn.components.date.DayMark;
import limn.i18n.I18nString;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * The worked example the guide's date section is built from: the four shapes a form asks for, a
 * period, and the grid on its own.
 *
 * <p>Every region marked here becomes a code block on that page, and this file is compiled by
 * {@code ./gradlew check}, so the sample a reader copies is a sample that builds and the
 * screenshot beside it is the same program. Each marker sits BELOW its Javadoc, for
 * {@code FormExample}'s reason: the site publishes the marked text verbatim, and a doc comment
 * with {@code @link} tags in it is documentation for a reader of this file rather than a sample
 * for a reader of the guide.
 *
 * <p>Deterministic on purpose: every date is fixed rather than taken from the clock, so two
 * capture runs produce the same pixels. The one thing that does move is the ring around today,
 * which is why nothing here is anchored on it.
 */
public final class DatesExample {

    private DatesExample() {
    }

    private static final Set<LocalDate> HOLIDAYS =
            Set.of(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 21));

    /**
     * The four shapes, which are three classes. A field types a date; a picker is that field with
     * a calendar behind a button; either of them carries a clock as well.
     */
    // #region guide:date-shapes
    static Widget<?> shapes() {
        Column column = new Column();
        column.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);

        DateField typed = new DateField();                 // a date
        typed.setDate(LocalDate.of(2026, 9, 9));

        DateField clock = DateField.ofTime();              // a time of day
        clock.setTime(LocalTime.of(14, 30));

        DatePicker picker = new DatePicker();              // a date, with a calendar
        picker.setDate(LocalDate.of(2026, 9, 9));

        DatePicker moment = new DatePicker()               // both, with a calendar
                .setGranularity(DateField.Granularity.MINUTE);
        moment.setDateTime(LocalDateTime.of(2026, 9, 9, 18, 0));

        column.add(field("Invoice date", typed));
        column.add(field("Reminder", clock));
        column.add(field("Delivery", picker));
        column.add(field("Appointment", moment));
        return column;
    }
    // #endregion

    /**
     * A period is one picker with two fields: it is typable end to end without the calendar ever
     * being opened, and the grid writes back into whichever end is being filled.
     */
    // #region guide:date-range
    static Widget<?> period() {
        DatePicker period = DatePicker.ofRange();
        period.setRange(new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 25)));
        period.onSelect(() -> System.out.println("period now " + period.range()));
        return field("Reporting period", period);
    }
    // #endregion

    /**
     * What a corporate form actually asks of the grid: a lower bound, a rule about which days may
     * be picked at all, and marks on the days that carry something.
     *
     * <p>The filter is called once per painted cell, so it must be cheap and it must be pure. The
     * mark carries a phrase as well as a colour, because a dot is invisible to a screen reader and
     * to anyone who cannot tell the two colours apart.
     */
    // #region guide:date-rules
    static CalendarView rules() {
        CalendarView calendar = new CalendarView();
        calendar.setVisibleMonth(LocalDate.of(2026, 9, 9));
        calendar.setMinDate(LocalDate.of(2026, 9, 2));
        calendar.setDateFilter(day -> day.getDayOfWeek() != DayOfWeek.SATURDAY
                && day.getDayOfWeek() != DayOfWeek.SUNDAY);
        calendar.setShowWeekNumbers(true);
        calendar.setDayMarks(day -> HOLIDAYS.contains(day)
                ? DayMark.of(Theme.current().danger(), I18nString.literal("holiday"))
                : null);
        calendar.onSelect(() -> System.out.println("picked " + calendar.selectedDate()));
        return calendar;
    }
    // #endregion

    /**
     * A caption over a control, with the caption declared as that control's name: the one line
     * that is not layout, and the reason a screen reader says "Delivery, group" rather than
     * "group".
     */
    private static Widget<?> field(String caption, Widget<?> control) {
        Column column = new Column();
        column.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        Label label = new Label(caption);
        label.setLabelFor(control);
        column.add(label);
        column.add(control);
        return column;
    }

    /**
     * The scene the guide's picture is taken from: centred in the window rather than pinned to a
     * corner, for {@code FormExample}'s reason -- a capture is a picture of a screen, and content
     * huddled against one edge of it reads as a layout that went wrong.
     */
    public static Scene scene() {
        Row row = new Row();
        row.gap(28).crossAlignment(Flex.CrossAlignment.START);
        Column left = new Column();
        left.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        left.add(shapes());
        left.add(period());
        row.add(new SizedBox(300, SizedBox.UNSET, left));
        row.add(field("Available days", rules()));
        Row centred = new Row();
        centred.mainAlignment(Flex.MainAlignment.CENTER)
                .crossAlignment(Flex.CrossAlignment.CENTER);
        centred.add(row);
        Scene scene = new Scene(new Padding(Insets.all(28), centred));
        scene.setBackground(Theme.current().background());
        return scene;
    }
}

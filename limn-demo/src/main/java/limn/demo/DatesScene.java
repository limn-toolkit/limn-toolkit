package limn.demo;

import limn.components.DisplayMode;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * Demo of the date widgets (ADR 042): the four shapes a form asks for, a period, and the grid on
 * its own with week numbers, bounds, a filter and marks.
 *
 * <p>Every date here is fixed rather than taken from the clock, so two runs and two captures show
 * the same month. The one exception is deliberate: nothing marks today, because a capture taken on
 * a different day would move the ring and the gallery would churn.
 */
final class DatesScene {

    private DatesScene() {
    }

    /** The month every part of this scene is anchored on, so the capture is stable. */
    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 9);

    /** A handful of days with something on them, to show what a mark is for. */
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 21));
    private static final Set<LocalDate> DEADLINES = Set.of(
            LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 30));

    /**
     * The scene and the one control a capture has to reach into.
     *
     * @param scene  the built scene
     * @param picker the plain date picker, so {@code --scene dates-popup} can open its calendar
     *               and photograph the overlay
     */
    record Built(Scene scene, DatePicker picker, CalendarView grid) {
    }

    /** Standalone {@code --scene dates}. */
    static Scene create(boolean light) {
        return build(light).scene();
    }

    /** {@code --scene dates-months} / {@code dates-years}: the two choosers the header climbs to. */
    static Scene create(boolean light, CalendarView.View view) {
        Built built = build(light);
        built.grid().setView(view);
        return built.scene();
    }

    /** The same, with the picker handed back for the popup capture. */
    static Built build(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        DatePicker picker = calendarPicker();
        CalendarView grid = grid();
        Scene scene = new Scene(new Padding(Insets.all(20), content(picker, grid)));
        scene.setBackground(Theme.current().background);
        return new Built(scene, picker, grid);
    }

    /** Reusable subtree, so the kitchen sink and the site gallery show the same thing. */
    static Widget content() {
        return content(calendarPicker(), grid());
    }

    private static Widget content(DatePicker picker, CalendarView grid) {
        Row row = new Row();
        row.gap(24).crossAlignment(Flex.CrossAlignment.START);
        row.add(fields(picker));
        row.add(gridColumn(grid));
        return row;
    }

    /** The picker whose calendar the popup capture opens. */
    private static DatePicker calendarPicker() {
        DatePicker picker = new DatePicker();
        picker.setDate(ANCHOR);
        // In-scene on purpose: a native popup is a window of its own and is absent from a capture
        // of this one, so the gallery would show a picker that never opens.
        picker.setDisplayMode(DisplayMode.IN_SCENE);
        picker.calendar().setShowWeekNumbers(true);
        return picker;
    }

    /** The four shapes, plus the period: what a form actually puts on a screen. */
    private static Widget fields(DatePicker picker) {
        Column column = new Column();
        column.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label("Fields and pickers").setRole(Label.Role.TITLE).setStrong(true));

        DateField plain = new DateField();
        plain.setDate(ANCHOR);
        column.add(Labelled.above("Date, typed (no calendar)", plain));

        DateField clock = DateField.ofTime();
        clock.setTime(LocalTime.of(14, 30));
        column.add(Labelled.above("Time of day", clock));

        DateField moment = DateField.ofDateTime();
        moment.setDateTime(LocalDateTime.of(ANCHOR, LocalTime.of(9, 5)));
        column.add(Labelled.above("Date and time, typed", moment));

        column.add(Labelled.above("Date, with a calendar", picker));

        DatePicker withTime = DatePicker.ofDateTime();
        withTime.setDateTime(LocalDateTime.of(ANCHOR, LocalTime.of(18, 0)));
        withTime.setDisplayMode(DisplayMode.IN_SCENE);
        column.add(Labelled.above("Date and time, with a calendar", withTime));

        DatePicker period = DatePicker.ofRange();
        period.setRange(new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 25)));
        period.setDisplayMode(DisplayMode.IN_SCENE);
        column.add(Labelled.above("Period", period));

        DateField bounded = new DateField();
        bounded.setMinDate(LocalDate.of(2026, 9, 1));
        bounded.setMaxDate(LocalDate.of(2026, 9, 30));
        // Held rather than snapped, and saying so is the point of showing it: the field keeps the
        // date somebody typed and marks itself invalid, instead of quietly moving it to the 30th.
        bounded.setDate(LocalDate.of(2026, 10, 6));
        column.add(Labelled.above("Out of range: held, not snapped", bounded));

        return column;
    }

    private static Widget gridColumn(CalendarView calendar) {
        Column column = new Column();
        column.gap(14).crossAlignment(Flex.CrossAlignment.START);
        column.add(new Label("The grid on its own").setRole(Label.Role.TITLE).setStrong(true));
        column.add(calendar);
        Label note = new Label("Click the title to climb: days, months, years.");
        note.setRole(Label.Role.LABEL).setMuted(true);
        column.add(note);
        return column;
    }

    /** The grid on its own, wearing everything a corporate form asks of it. */
    private static CalendarView grid() {
        CalendarView calendar = new CalendarView();
        calendar.setVisibleMonth(ANCHOR);
        calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
        calendar.setSelectedRange(new DateRange(LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 25)));
        calendar.setShowWeekNumbers(true);
        calendar.setMinDate(LocalDate.of(2026, 8, 20));
        calendar.setDateFilter(day -> day.getDayOfWeek() != DayOfWeek.SATURDAY
                && day.getDayOfWeek() != DayOfWeek.SUNDAY);
        calendar.setDayMarks(day -> {
            if (HOLIDAYS.contains(day)) {
                return DayMark.of(Theme.current().danger, I18nString.literal("holiday"));
            }
            if (DEADLINES.contains(day)) {
                return DayMark.of(Theme.current().warning, I18nString.literal("deadline"));
            }
            return null;
        });
        return calendar;
    }
}

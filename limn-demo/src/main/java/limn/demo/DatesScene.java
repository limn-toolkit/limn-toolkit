package limn.demo;

import limn.components.DisplayMode;
import limn.components.Label;
import limn.components.Theme;
import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.components.date.DateRange;
import limn.components.date.DayMark;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.icons.tabler.Tabler;
import limn.scene.Change;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.chrono.JapaneseChronology;
import java.util.Set;
import java.util.function.Function;

/**
 * The date widgets as an invoicing and scheduling form uses them: a date typed or picked, a month,
 * a year, a time to the minute or the second, periods of days and of months, a date out of its
 * range with the line that says so, and two calendars, one with marks and their legend.
 *
 * <p>Every date here is fixed rather than taken from the clock, so two runs and two captures show
 * the same month. Today is the one fact the widgets read from a clock, and the calendar rings it
 * when it falls in the month on show: run by hand that is the machine's today, and a
 * {@code --screenshot} capture pins it to the documentation day (2026-09-09, see
 * {@link #pinForCapture}) so a render taken on another day rings the same cell.
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
        return create(light, view, false);
    }

    /**
     * The same two, with {@code -cursor} giving the grid the keyboard: what a person sees the
     * instant Ctrl (or Cmd) and Up climb out of the days (2026-09-15). The cell on
     * show carries the cursor ring from that frame; until that date the chooser was drawn with no
     * ring at all until the first arrow, so this pair is the render of the change.
     *
     * @param light  which palette
     * @param view   the chooser to show
     * @param cursor whether the grid holds the keyboard, and so draws its cursor
     */
    static Scene create(boolean light, CalendarView.View view, boolean cursor) {
        Built built = build(light);
        built.grid().setView(view);
        if (cursor) {
            built.scene().requestFocus(built.grid());
        }
        return built.scene();
    }

    /**
     * {@code --scene dates-month-picker}, {@code dates-time-row} and {@code dates-month-range}
     * (each also {@code -light}): one picker per granularity the owner is shown a render of,
     * opened by the capture the way {@code dates-popup} is.
     *
     * @param light which palette
     * @param which {@code month-picker}, {@code time-row} or {@code month-range}
     */
    static Built granularity(boolean light, String which) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        DatePicker picker;
        String caption;
        switch (which) {
            case "month-picker" -> {
                picker = new DatePicker().setGranularity(DateField.Granularity.MONTH);
                picker.setDate(ANCHOR);
                caption = "Month";
            }
            case "time-row" -> {
                picker = new DatePicker().setGranularity(DateField.Granularity.MINUTE);
                picker.setDateTime(LocalDateTime.of(ANCHOR, LocalTime.of(14, 30)));
                caption = "Date and time, with a calendar";
            }
            default -> {
                picker = DatePicker.ofRange().setGranularity(DateField.Granularity.MONTH);
                picker.setRange(new DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 30)));
                caption = "Period of months";
            }
        }
        picker.setDisplayMode(DisplayMode.IN_SCENE);
        Column column = new Column();
        column.gap(14).crossAlignment(Flex.CrossAlignment.START);
        column.add(Labelled.above(caption, picker));
        Scene scene = new Scene(new Padding(Insets.all(20), column));
        return new Built(scene, picker, null);
    }

    /**
     * A capture's today is the documentation day (2026-09-09, {@link DocumentationDay}), so a
     * render taken on another day, or in another time zone, draws the
     * today ring on the same cell and a reviewer compares pixels and not calendars. Only for a
     * capture: the demo run by hand keeps the machine's today.
     */
    static void pinForCapture(boolean screenshot, Scene scene) {
        if (screenshot) {
            DocumentationDay.pin(scene.root());
        }
    }

    /**
     * {@code --scene dates-era} (and {@code -light}): a year of an era drawn at its own width,
     * "R8" and not "R0008", in the Japanese calendar as the Japanese language writes it and as
     * an English form that chose the calendar writes it.
     */
    static Scene era(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Column column = new Column();
        column.gap(14).crossAlignment(Flex.CrossAlignment.START);
        DateField japanese = new DateField();
        japanese.setLocale(java.util.Locale.forLanguageTag("ja-JP-u-ca-japanese"));
        japanese.setDate(ANCHOR);
        column.add(Labelled.above("ja-JP, Japanese calendar: 9 September 2026", japanese));
        DateField chosen = new DateField();
        chosen.setChronology(java.time.chrono.JapaneseChronology.INSTANCE);
        chosen.setDate(ANCHOR);
        column.add(Labelled.above("English, Japanese calendar chosen: 9 September 2026", chosen));
        DatePicker picker = new DatePicker();
        picker.setLocale(java.util.Locale.forLanguageTag("ja-JP-u-ca-japanese"));
        picker.setDate(LocalDate.of(2019, 5, 1));
        picker.setDisplayMode(DisplayMode.IN_SCENE);
        column.add(Labelled.above("ja-JP picker: 1 May 2019, the first day of Reiwa", picker));
        Scene scene = new Scene(new Padding(Insets.all(20), column));
        return scene;
    }

    /** The same, with the picker handed back for the popup capture. */
    static Built build(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        // In-scene on purpose: a native popup is a window of its own and is absent from a capture
        // of this one, so the capture would show a picker that never opens.
        DatePicker picker = dueDate(DisplayMode.IN_SCENE);
        CalendarView grid = grid();
        Scene scene = new Scene(new Padding(Insets.all(20), content(picker, grid)));
        return new Built(scene, picker, grid);
    }

    /**
     * Reusable subtree, the showcase's page. Its due date opens its calendar the default way, in a
     * window of its own that may reach past this one's edge, beside the delivery slot that opens
     * its own inside the window; the native presentation is also exercised headless by
     * limn-demo's DatePickerNativePopupTest.
     */
    static Widget<?> content() {
        return content(dueDate(DisplayMode.NATIVE_WINDOW), grid());
    }

    private static Widget<?> content(DatePicker due, CalendarView team) {
        Column page = Page.column();

        page.add(Page.firstSection(Text.INVOICE));
        DateField issued = new DateField();
        issued.setDate(LocalDate.of(2026, 8, 10));
        page.add(Page.columns(Page.field(Text.INVOICE_DATE, issued), Page.field(Text.DUE_DATE, due)));

        DatePicker month = new DatePicker().setGranularity(DateField.Granularity.MONTH);
        month.setDate(ANCHOR);
        report(month, Text.BILLING_MONTH);
        DateField year = new DateField().setGranularity(DateField.Granularity.YEAR);
        year.setDate(ANCHOR);
        page.add(Page.columns(Page.field(Text.BILLING_MONTH, month), Page.field(Text.FISCAL_YEAR, year)));

        DateField created = new DateField();
        created.setDate(LocalDate.of(2026, 8, 3));
        created.setEnabled(false); // stamped by the system
        DateField contract = new DateField();
        contract.setChronology(JapaneseChronology.INSTANCE);
        contract.setDate(ANCHOR);
        page.add(Page.columns(Page.field(Text.CREATED, created), Page.field(Text.CONTRACT, contract)));

        page.add(Page.section(Text.SCHEDULE));
        DatePicker slot = new DatePicker().setGranularity(DateField.Granularity.MINUTE);
        slot.setDateTime(LocalDateTime.of(ANCHOR, LocalTime.of(18, 0)));
        slot.setDisplayMode(DisplayMode.IN_SCENE);
        report(slot, Text.SLOT);
        DateField meeting = new DateField().setGranularity(DateField.Granularity.MINUTE);
        meeting.setDateTime(LocalDateTime.of(ANCHOR, LocalTime.of(9, 5)));
        page.add(Page.columns(Page.field(Text.SLOT, slot), Page.field(Text.MEETING, meeting)));

        DateField delivery = new DateField();
        delivery.setMinDate(LocalDate.of(2026, 9, 1));
        delivery.setMaxDate(LocalDate.of(2026, 9, 30));
        // Held rather than snapped: the field keeps the date somebody typed and marks itself
        // invalid, instead of quietly moving it to the 30th; the line under it says why.
        delivery.setDate(LocalDate.of(2026, 10, 6));
        Label deliveryLine = Page.message(I18nString.EMPTY, Theme::danger, null, delivery);
        Runnable explain = () -> {
            I18nString why = delivery.validationMessage();
            deliveryLine.setText(why == null ? I18nString.EMPTY : why);
            deliveryLine.setIcon(why == null ? null : Tabler.outline("alert-circle"));
        };
        explain.run();
        delivery.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALIDITY) {
                explain.run();
            }
        });
        DateField received = new DateField().setGranularity(DateField.Granularity.SECOND);
        received.setDateTime(LocalDateTime.of(ANCHOR, LocalTime.of(14, 30, 12)));
        page.add(Page.columns(
                Page.field(Text.DELIVERY_DATE, delivery, Page.withMessage(delivery, deliveryLine)),
                Page.field(Text.RECEIVED, received)));

        DateField checkIn = DateField.ofTime();
        checkIn.setTime(LocalTime.of(14, 30));
        page.add(Page.columns(Page.field(Text.CHECK_IN, checkIn), new SizedBox(0, 0)));

        page.add(Page.section(Text.PERIODS));
        DatePicker holiday = DatePicker.ofRange();
        holiday.setRange(new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 25)));
        report(holiday, Text.HOLIDAY_PERIOD);
        DatePicker quarter = DatePicker.ofRange().setGranularity(DateField.Granularity.MONTH);
        quarter.setRange(new DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30)));
        report(quarter, Text.REPORTING);
        page.add(Page.columns(Page.field(Text.HOLIDAY_PERIOD, holiday), Page.field(Text.REPORTING, quarter)));

        page.add(Page.section(Text.CALENDARS));
        page.add(Page.columns(calendar(Text.TEAM, team, legend()), calendar(Text.ON_CALL, onCall(), null)));
        page.add(Page.hint(Text.CLIMB_HINT));
        return Page.narrow(720, page);
    }

    /** The due date, the picker whose calendar the popup capture opens. */
    private static DatePicker dueDate(DisplayMode mode) {
        DatePicker picker = new DatePicker();
        picker.setDate(ANCHOR);
        picker.setDisplayMode(mode);
        picker.calendar().setShowWeekNumbers(true);
        report(picker, Text.DUE_DATE);
        return picker;
    }

    /** Says in the status line what a picker now holds, by the field's name. */
    private static void report(DatePicker picker, I18nString name) {
        picker.onSelect(() -> Page.report(Text.CHOSEN.format(name.get(), picker.field().text()
                + (picker.endField() == null ? "" : " – " + picker.endField().text()))));
    }

    /** A calendar under its caption, with what its marks mean under it. */
    private static Widget<?> calendar(I18nString caption, CalendarView calendar, Widget<?> legend) {
        Column column = new Column();
        column.gap(8).crossAlignment(Flex.CrossAlignment.START);
        column.add(calendar);
        if (legend != null) {
            column.add(legend);
        }
        return Page.field(caption, calendar, column);
    }

    /** What the dots under the days mean. */
    private static Widget<?> legend() {
        return Page.row(16, mark(Theme::danger, Text.HOLIDAY), mark(Theme::warning, Text.DEADLINE));
    }

    private static Widget<?> mark(Function<Theme, Color> ink, I18nString meaning) {
        return Page.row(6, Page.tint(new Label("●").setRole(Label.Role.LABEL), ink),
                new Label(meaning).setRole(Label.Role.LABEL).setMuted(true));
    }

    /** The team's calendar: a period chosen over working days, with the month's marks. */
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
                return DayMark.of(Theme.current().danger(), Text.HOLIDAY);
            }
            if (DEADLINES.contains(day)) {
                return DayMark.of(Theme.current().warning(), Text.DEADLINE);
            }
            return null;
        });
        calendar.onSelect(() -> Page.report(Text.CHOSEN.format(Text.TEAM.get(),
                calendar.selectedRange().start() + " – " + calendar.selectedRange().end())));
        return calendar;
    }

    /** One day picked on a grid whose weeks start on Monday, whatever the language's do. */
    private static CalendarView onCall() {
        CalendarView calendar = new CalendarView();
        calendar.setVisibleMonth(ANCHOR);
        calendar.setSelectionMode(CalendarView.SelectionMode.SINGLE);
        calendar.setFirstDayOfWeek(DayOfWeek.MONDAY);
        calendar.setSelectedDate(LocalDate.of(2026, 9, 18));
        return calendar;
    }

    /** What this page says, ready for translation. */
    static final class Text {
        private Text() {
        }

        static final I18nString INVOICE = new I18nString("showcase.dates.invoice", "Invoice");
        static final I18nString INVOICE_DATE = new I18nString("showcase.dates.invoiceDate", "Invoice date");
        static final I18nString DUE_DATE = new I18nString("showcase.dates.dueDate", "Due date");
        static final I18nString BILLING_MONTH = new I18nString("showcase.dates.billingMonth", "Billing month");
        static final I18nString FISCAL_YEAR = new I18nString("showcase.dates.fiscalYear", "Fiscal year");
        static final I18nString CREATED = new I18nString("showcase.dates.created", "Created on");
        static final I18nString CONTRACT = new I18nString("showcase.dates.contract",
                "Contract date, Japanese calendar");
        static final I18nString SCHEDULE = new I18nString("showcase.dates.schedule", "Schedule");
        static final I18nString SLOT = new I18nString("showcase.dates.slot", "Delivery slot");
        static final I18nString MEETING = new I18nString("showcase.dates.meeting", "Meeting");
        static final I18nString DELIVERY_DATE = new I18nString("showcase.dates.deliveryDate", "Delivery date");
        static final I18nString RECEIVED = new I18nString("showcase.dates.received", "Payment received");
        static final I18nString CHECK_IN = new I18nString("showcase.dates.checkIn", "Check-in time");
        static final I18nString PERIODS = new I18nString("showcase.dates.periods", "Periods");
        static final I18nString HOLIDAY_PERIOD = new I18nString("showcase.dates.holidayPeriod", "Holiday period");
        static final I18nString REPORTING = new I18nString("showcase.dates.reporting", "Reporting period");
        static final I18nString CALENDARS = new I18nString("showcase.dates.calendars", "Calendars");
        static final I18nString TEAM = new I18nString("showcase.dates.team", "Team leave");
        static final I18nString ON_CALL = new I18nString("showcase.dates.onCall", "On-call day");
        static final I18nString HOLIDAY = new I18nString("showcase.dates.holiday", "Holiday");
        static final I18nString DEADLINE = new I18nString("showcase.dates.deadline", "Deadline");
        static final I18nString CLIMB_HINT = new I18nString("showcase.dates.climbHint",
                "Click a calendar's month to choose among months and years.");
        static final I18nString CHOSEN = new I18nString("showcase.dates.chosen", "{0}: {1}");
    }
}

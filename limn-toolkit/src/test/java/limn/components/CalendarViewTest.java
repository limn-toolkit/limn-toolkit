package limn.components;

import limn.components.date.CalendarView;
import limn.components.date.DateRange;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.chrono.Chronology;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The month grid: paging, the cursor, selection, periods, bounds and the calendar it draws. */
class CalendarViewTest extends ComponentTestBase {

    /** A Wednesday, in a month that starts on a Tuesday: the awkward shape, on purpose. */
    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 9);

    private CalendarView calendar;
    private Scene scene;

    private void build() {
        build(Locale.forLanguageTag("pt-BR"));
    }

    private void build(Locale locale) {
        I18n.setLocale(locale);
        calendar = new CalendarView();
        calendar.setVisibleMonth(ANCHOR);
        scene = new Scene(calendar);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 400);
        scene.requestFocus(calendar);
    }

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    private void key(int keyCode) {
        key(keyCode, 0);
    }

    private void key(int keyCode, int modifiers) {
        scene.keyEvent(keyCode, true, false, modifiers);
        scene.keyEvent(keyCode, false, false, modifiers);
        scene.inputBatchEnded();
    }

    @Test
    void theVisibleMonthIsTheFirstOfWhateverMonthADayIsIn() {
        build();
        assertEquals(LocalDate.of(2026, 9, 1), calendar.visibleMonth());
        calendar.setVisibleMonth(LocalDate.of(2026, 12, 31));
        assertEquals(LocalDate.of(2026, 12, 1), calendar.visibleMonth());
    }

    @Test
    void theCursorMovesWithoutSelectingAndEnterCommits() {
        build();
        List<LocalDate> picked = new ArrayList<>();
        calendar.onSelect(picked::add);
        key(Keys.DOWN);
        key(Keys.DOWN);
        // Two weeks down from today's cursor, and nothing selected: a grid whose arrows selected
        // would fire a form's handler seven times crossing a week.
        assertTrue(picked.isEmpty(), "arrowing selects nothing");
        assertNull(calendar.selectedDate());
        assertNotNull(calendar.focusedDate(), "but the cursor is somewhere");
        LocalDate cursor = calendar.focusedDate();
        key(Keys.ENTER);
        assertEquals(List.of(cursor), picked, "Enter commits where the cursor is");
        assertEquals(cursor, calendar.selectedDate());
    }

    @Test
    void theArrowsMoveByADayAndAWeekAndThePageKeysByAMonth() {
        build();
        calendar.setSelectedDate(ANCHOR);
        assertEquals(ANCHOR, calendar.focusedDate());
        key(Keys.RIGHT);
        assertEquals(ANCHOR.plusDays(1), calendar.focusedDate());
        key(Keys.DOWN);
        assertEquals(ANCHOR.plusDays(8), calendar.focusedDate());
        key(Keys.LEFT);
        assertEquals(ANCHOR.plusDays(7), calendar.focusedDate());
        key(Keys.UP);
        assertEquals(ANCHOR, calendar.focusedDate());
        key(Keys.PAGE_DOWN);
        assertEquals(LocalDate.of(2026, 10, 9), calendar.focusedDate());
        assertEquals(LocalDate.of(2026, 10, 1), calendar.visibleMonth(),
                "the grid follows the cursor off the month");
        key(Keys.PAGE_UP);
        assertEquals(ANCHOR, calendar.focusedDate());
    }

    @Test
    void homeAndEndAreTheEndsOfTheWeekAndFollowTheLocalesFirstDay() {
        build(Locale.forLanguageTag("pt-BR")); // weeks start on Sunday
        calendar.setSelectedDate(ANCHOR);      // a Wednesday
        key(Keys.HOME);
        assertEquals(DayOfWeek.SUNDAY, calendar.focusedDate().getDayOfWeek());
        assertEquals(LocalDate.of(2026, 9, 6), calendar.focusedDate());
        key(Keys.END);
        assertEquals(LocalDate.of(2026, 9, 12), calendar.focusedDate());

        build(Locale.GERMANY); // and on Monday here
        calendar.setSelectedDate(ANCHOR);
        key(Keys.HOME);
        assertEquals(DayOfWeek.MONDAY, calendar.focusedDate().getDayOfWeek());
        assertEquals(LocalDate.of(2026, 9, 7), calendar.focusedDate());
    }

    @Test
    void aPeriodTakesTwoPicksAndTheHandlerOnlyRunsForTheSecond() {
        build();
        calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
        AtomicReference<DateRange> closed = new AtomicReference<>();
        calendar.onSelectRange(closed::set);
        calendar.setSelectedDate(null);

        calendar.setVisibleMonth(ANCHOR);
        scene.requestFocus(calendar);
        // First end.
        key(Keys.ENTER);
        assertNull(calendar.selectedRange(), "a period with one end is not a period");
        assertNull(closed.get(), "and reaches no handler");
        // Second end, five days on.
        for (int i = 0; i < 5; i++) {
            key(Keys.RIGHT);
        }
        key(Keys.ENTER);
        assertNotNull(calendar.selectedRange());
        assertEquals(5, calendar.selectedRange().days() - 1);
        assertEquals(calendar.selectedRange(), closed.get());
    }

    @Test
    void aPeriodPickedBackwardsIsStillOrdered() {
        build();
        calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
        calendar.setVisibleMonth(ANCHOR);
        scene.requestFocus(calendar);
        key(Keys.ENTER);
        LocalDate anchor = calendar.focusedDate();
        key(Keys.LEFT);
        key(Keys.LEFT);
        key(Keys.ENTER);
        DateRange range = calendar.selectedRange();
        assertNotNull(range);
        assertEquals(anchor.minusDays(2), range.start());
        assertEquals(anchor, range.end());
    }

    @Test
    void changingTheModeDropsWhatTheOtherModeHeld() {
        build();
        calendar.setSelectedDate(ANCHOR);
        calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
        assertNull(calendar.selectedDate(), "a day is not a period and is not carried across");
        calendar.setSelectedRange(new DateRange(ANCHOR, ANCHOR.plusDays(3)));
        calendar.setSelectionMode(CalendarView.SelectionMode.SINGLE);
        assertNull(calendar.selectedRange());
    }

    @Test
    void boundsAndTheFilterRefuseADayRatherThanMovingIt() {
        build();
        calendar.setMinDate(LocalDate.of(2026, 9, 5));
        calendar.setMaxDate(LocalDate.of(2026, 9, 20));
        calendar.setDateFilter(day -> day.getDayOfWeek() != DayOfWeek.SUNDAY);

        assertFalse(calendar.isSelectable(LocalDate.of(2026, 9, 4)), "before the minimum");
        assertFalse(calendar.isSelectable(LocalDate.of(2026, 9, 21)), "after the maximum");
        assertFalse(calendar.isSelectable(LocalDate.of(2026, 9, 6)), "a Sunday, filtered out");
        assertTrue(calendar.isSelectable(ANCHOR));

        List<LocalDate> picked = new ArrayList<>();
        calendar.onSelect(picked::add);
        calendar.setSelectedDate(LocalDate.of(2026, 9, 6)); // a caller may still write one
        scene.requestFocus(calendar);
        // The keyboard cannot commit it, though: the pick refuses, and nothing is announced.
        calendar.setSelectedDate(null);
        calendar.setVisibleMonth(LocalDate.of(2026, 9, 6));
        assertTrue(picked.isEmpty());
    }

    @Test
    void aFilteredDayIsNotSelectableInEitherMode() {
        build();
        calendar.setSelectionMode(CalendarView.SelectionMode.NONE);
        assertFalse(calendar.isSelectable(ANCHOR),
                "a calendar that is a display and not an input selects nothing");
    }

    @Test
    void theWeekStartsWhereTheLanguageStartsItUnlessTheApplicationSaysOtherwise() {
        build(Locale.forLanguageTag("pt-BR"));
        assertEquals(DayOfWeek.SUNDAY, calendar.firstDayOfWeek());
        build(Locale.GERMANY);
        assertEquals(DayOfWeek.MONDAY, calendar.firstDayOfWeek());
        calendar.setFirstDayOfWeek(DayOfWeek.SATURDAY);
        assertEquals(DayOfWeek.SATURDAY, calendar.firstDayOfWeek());
        calendar.setFirstDayOfWeek(null);
        assertEquals(DayOfWeek.MONDAY, calendar.firstDayOfWeek(), "null follows the language again");
    }

    @Test
    void theCalendarDrawnIsIsoUntilTheLocaleOrTheApplicationSaysOtherwise() {
        build(Locale.forLanguageTag("ar-SA"));
        assertEquals("ISO", calendar.chronology().getId(),
                "Arabic does not by itself ask for the Hijri calendar; only a u-ca extension does");
        build(Locale.forLanguageTag("ja-JP-u-ca-japanese"));
        assertEquals("Japanese", calendar.chronology().getId());
        calendar.setChronology(Chronology.of("ThaiBuddhist"));
        assertEquals("ThaiBuddhist", calendar.chronology().getId());
    }

    @Test
    void aChronologyThatCannotHoldTheMonthFallsBackRatherThanThrowing() {
        build();
        // Hijrah covers AH 1300-1600, roughly 1882 to 2174. A grid pointed at 1750 has no Hijri
        // month to draw, and the paint must not throw out of a frame over a display detail.
        calendar.setChronology(Chronology.of("Hijrah-umalqura"));
        calendar.setVisibleMonth(LocalDate.of(1750, 6, 1));
        scene.layoutPass(400, 400);
        scene.renderFrame(new FakeCanvas(400, 400));
        assertEquals(LocalDate.of(1750, 6, 1), calendar.visibleMonth(), "and the value stands");
    }

    @Test
    void everyAnnouncedAspectHasAnAccessorThatAnswersIt() {
        build();
        List<Change.Aspect> heard = new ArrayList<>();
        calendar.observeChanges((widget, change) -> heard.add(change.aspect()));
        calendar.setSelectedDate(ANCHOR.plusDays(1));
        assertTrue(heard.contains(Change.Aspect.SELECTION));
        assertEquals(ANCHOR.plusDays(1), calendar.selectedDate());

        heard.clear();
        calendar.setVisibleMonth(LocalDate.of(2027, 1, 5));
        assertTrue(heard.contains(Change.Aspect.VALUE));
        assertEquals(LocalDate.of(2027, 1, 1), calendar.visibleMonth());

        heard.clear();
        calendar.setMinDate(LocalDate.of(2020, 1, 1));
        assertTrue(heard.contains(Change.Aspect.RANGE));
        assertEquals(LocalDate.of(2020, 1, 1), calendar.minDate());

        heard.clear();
        scene.requestFocus(calendar);
        key(Keys.RIGHT);
        assertTrue(heard.contains(Change.Aspect.ACTIVE));
        assertNotNull(calendar.focusedDate());
    }

    @Test
    void theHeaderClimbsToMonthsThenYearsAndBackDown() {
        build();
        assertEquals(CalendarView.View.DAYS, calendar.view());
        calendar.setView(CalendarView.View.MONTHS);
        assertEquals(CalendarView.View.MONTHS, calendar.view());
        calendar.setView(CalendarView.View.YEARS);
        assertEquals(CalendarView.View.YEARS, calendar.view());
    }

    @Test
    void theArrowsPageAYearInTheMonthChooserAndABlockInTheYearChooser() {
        build();
        calendar.setView(CalendarView.View.MONTHS);
        scene.requestFocus(calendar);
        key(Keys.PAGE_DOWN);
        assertEquals(2027, calendar.visibleMonth().getYear(),
                "a month chooser pages by a year, not by a month");
        calendar.setView(CalendarView.View.YEARS);
        int before = calendar.visibleMonth().getYear();
        key(Keys.PAGE_DOWN);
        assertEquals(before + 24, calendar.visibleMonth().getYear(),
                "and a year chooser by its own block");
    }

    @Test
    void enterInAChooserNavigatesAndChoosesNothing() {
        build();
        calendar.setView(CalendarView.View.MONTHS);
        scene.requestFocus(calendar);
        key(Keys.HOME);      // the first month of the row the cursor starts on
        key(Keys.UP);
        key(Keys.UP);        // clamped to the first cell: January
        key(Keys.ENTER);
        assertEquals(LocalDate.of(2026, 1, 1), calendar.visibleMonth());
        assertEquals(CalendarView.View.DAYS, calendar.view(), "it comes back down");
        assertNull(calendar.selectedDate(), "navigating is not choosing");
    }

    @Test
    void aYearPickedOpensItsMonthsRatherThanItsDays() {
        build();
        calendar.setView(CalendarView.View.YEARS);
        scene.requestFocus(calendar);
        key(Keys.HOME);
        key(Keys.UP);
        key(Keys.UP);
        key(Keys.UP);
        key(Keys.UP);
        key(Keys.UP);
        key(Keys.UP);
        key(Keys.ENTER);
        assertEquals(CalendarView.View.MONTHS, calendar.view(),
                "a year opens its months: the descent is one step at a time");
        assertEquals(2016, calendar.visibleMonth().getYear(),
                "and lands on the first year of the block on show");
    }

    @Test
    void escapeInAChooserComesBackDownWithoutChoosing() {
        build();
        calendar.setView(CalendarView.View.YEARS);
        scene.requestFocus(calendar);
        key(Keys.ESCAPE);
        assertEquals(CalendarView.View.DAYS, calendar.view());
        assertNull(calendar.selectedDate());
    }

    @Test
    void aMonthOrYearWithNoSelectableDayInItIsNotOffered() {
        build();
        calendar.setMinDate(LocalDate.of(2026, 6, 1));
        calendar.setView(CalendarView.View.MONTHS);
        scene.requestFocus(calendar);
        // January 2026 is wholly before the minimum: Enter on it does nothing.
        key(Keys.HOME);
        for (int i = 0; i < 4; i++) {
            key(Keys.UP);
        }
        key(Keys.ENTER);
        assertEquals(CalendarView.View.MONTHS, calendar.view(),
                "a month that leads nowhere refuses, the way a day out of bounds does");
    }

    @Test
    void writingTheValueTheCalendarAlreadyHoldsAnnouncesNothing() {
        build();
        calendar.setSelectedDate(ANCHOR);
        List<Change.Aspect> heard = new ArrayList<>();
        calendar.observeChanges((widget, change) -> heard.add(change.aspect()));
        calendar.setSelectedDate(ANCHOR);
        assertTrue(heard.isEmpty(), "the early return two bound calendars rely on");
    }
}

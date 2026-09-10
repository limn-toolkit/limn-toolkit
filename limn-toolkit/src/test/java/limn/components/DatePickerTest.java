package limn.components;

import limn.components.date.CalendarView;
import limn.components.date.DatePicker;
import limn.components.date.DateRange;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picker as wiring: the field is a child that already works, the grid is a widget that already
 * works, and what is under test here is that the two stay in step and that the popup opens.
 *
 * <p>Headless, so the popup never becomes a window: the open/close state machine is the same one
 * either way, which is exactly what {@code ComboBoxTest} relies on.
 */
class DatePickerTest extends ComponentTestBase {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 9);

    private DatePicker picker;
    private Scene scene;
    /** The scene's clock, moved only by {@link #settle()}. */
    private final long[] nanos = {java.util.concurrent.TimeUnit.SECONDS.toNanos(1)};

    private void build(DatePicker built) {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        picker = built;
        scene = new Scene(picker, () -> nanos[0]);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 32);
        scene.requestFocus(picker.field());
    }

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    private void key(int keyCode, int modifiers) {
        scene.keyEvent(keyCode, true, false, modifiers);
        scene.keyEvent(keyCode, false, false, modifiers);
        scene.inputBatchEnded();
    }

    @Test
    void thePickerIsAFieldAndAGridAndOwnsNeithersBehaviour() {
        build(new DatePicker());
        assertNotNull(picker.field());
        assertNotNull(picker.calendar());
        assertNull(picker.endField(), "a picker for one date has one field");
        picker.setDate(ANCHOR);
        assertEquals(ANCHOR, picker.date());
        assertEquals(ANCHOR, picker.field().date(), "the value lives in the field");
        assertEquals(ANCHOR, picker.calendar().selectedDate(), "and the grid follows it");
    }

    @Test
    void openingAndClosingAnnouncesTheExpandedState() {
        build(new DatePicker());
        List<Change.Aspect> heard = new ArrayList<>();
        picker.observeChanges((widget, change) -> heard.add(change.aspect()));
        assertFalse(picker.isOpen());
        picker.open();
        assertTrue(picker.isOpen());
        assertTrue(heard.contains(Change.Aspect.EXPANDED));
        heard.clear();
        picker.close();
        assertFalse(picker.isOpen());
        assertTrue(heard.contains(Change.Aspect.EXPANDED));
    }

    @Test
    void altDownOpensTheGridAndEscapeClosesIt() {
        build(new DatePicker());
        picker.setDate(ANCHOR);
        key(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(picker.isOpen());
        key(Keys.ESCAPE, 0);
        assertFalse(picker.isOpen());
    }

    @Test
    void whileTheGridIsOpenTheNavigationKeysDriveItAndTheDigitsStillReachTheField() {
        build(new DatePicker());
        picker.setDate(ANCHOR);
        picker.open();
        key(Keys.DOWN, 0);
        assertEquals(ANCHOR.plusWeeks(1), picker.calendar().focusedDate(),
                "Down moved the grid's cursor and not the field's day segment");
        assertEquals(ANCHOR, picker.date(), "and the field is untouched until something commits");
        key(Keys.ENTER, 0);
        assertEquals(ANCHOR.plusWeeks(1), picker.date(), "Enter picks, and the field takes it");
        assertFalse(picker.isOpen(), "and the grid closes behind it");
    }

    @Test
    void aDateTypedIntoTheFieldMovesTheGridWithoutOpeningIt() {
        build(new DatePicker());
        picker.setDate(ANCHOR);
        picker.field().setDate(LocalDate.of(2027, 3, 4));
        assertEquals(LocalDate.of(2027, 3, 4), picker.calendar().selectedDate());
        assertEquals(LocalDate.of(2027, 3, 1), picker.calendar().visibleMonth());
        assertFalse(picker.isOpen());
    }

    @Test
    void theBoundsReachTheFieldAndTheGridAtOnce() {
        build(new DatePicker());
        LocalDate min = LocalDate.of(2026, 9, 1);
        LocalDate max = LocalDate.of(2026, 9, 30);
        picker.setMinDate(min);
        picker.setMaxDate(max);
        assertEquals(min, picker.field().minDate());
        assertEquals(min, picker.calendar().minDate());
        assertEquals(max, picker.field().maxDate());
        assertEquals(max, picker.calendar().maxDate());
        // Two enforcements of one rule, at two moments: the grid refuses the click, the field
        // holds what was typed and says it is not acceptable.
        assertFalse(picker.calendar().isSelectable(LocalDate.of(2026, 10, 6)));
        picker.setDate(LocalDate.of(2026, 10, 6));
        assertEquals(LocalDate.of(2026, 10, 6), picker.date(), "held, not snapped");
        assertFalse(picker.field().isValid());
    }

    @Test
    void aDateTimePickerTypesItsTimeAndPicksItsDate() {
        build(DatePicker.ofDateTime());
        picker.setDateTime(LocalDateTime.of(2026, 9, 9, 18, 30));
        assertEquals(LocalTime.of(18, 30), picker.time());
        assertEquals(ANCHOR, picker.date());
        assertEquals(LocalDateTime.of(2026, 9, 9, 18, 30), picker.dateTime());
        // The popup carries the grid alone; the clock is in the field (ADR 042 11).
        picker.open();
        assertEquals("SINGLE", picker.calendar().selectionMode().name(),
                "the popup carries the grid alone; the clock is in the field");
    }

    @Test
    void aRangePickerIsTwoFieldsAndOneGridInRangeMode() {
        build(DatePicker.ofRange());
        assertNotNull(picker.endField());
        assertEquals("RANGE", picker.calendar().selectionMode().name());
        assertNull(picker.range(), "neither end is filled yet");
        picker.setRange(new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 25)));
        assertEquals(LocalDate.of(2026, 9, 14), picker.field().date());
        assertEquals(LocalDate.of(2026, 9, 25), picker.endField().date());
        assertEquals(new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 25)),
                picker.range());
    }

    @Test
    void aPickerThatIsNotARangeRefusesARangeRatherThanIgnoringIt() {
        build(new DatePicker());
        assertThrows(IllegalStateException.class,
                () -> picker.setRange(new DateRange(ANCHOR, ANCHOR.plusDays(1))));
    }

    @Test
    void aHalfFilledPeriodIsNotAPeriod() {
        build(DatePicker.ofRange());
        picker.field().setDate(ANCHOR);
        assertNull(picker.range(), "one end is not a period");
        picker.endField().setDate(ANCHOR.plusDays(3));
        assertNotNull(picker.range());
    }

    @Test
    void theHandlerRunsForTheUserAndTheWatcherHearsEverything() {
        build(new DatePicker());
        List<LocalDate> handled = new ArrayList<>();
        List<Change.Aspect> watched = new ArrayList<>();
        picker.onSelect(handled::add);
        picker.observeChanges((widget, change) -> watched.add(change.aspect()));

        picker.setDate(ANCHOR);
        assertTrue(handled.isEmpty(), "a caller's write reaches no handler");
        assertTrue(watched.contains(Change.Aspect.VALUE), "and every watcher hears it");

        picker.open();
        key(Keys.DOWN, 0);
        key(Keys.ENTER, 0);
        assertFalse(handled.isEmpty(), "picking from the grid is the user operating the picker");
        assertEquals(picker.date(), handled.get(handled.size() - 1));
    }

    /**
     * The defect this test exists for: the grid is one widget, each presentation built a fresh
     * panel, and the second panel's {@code add} was refused because the first still held it. The
     * throw happened inside a click, the scene contained it, and the picker was left reporting
     * itself open with nothing drawn -- so nothing failed loudly and the popup simply stopped
     * working after the first use.
     *
     * <p>Over a window on purpose: headless the overlay is removed synchronously, and the bug
     * lives in the path where a fade-out defers that removal, which is every real application.
     */
    @Test
    void theCalendarOpensAgainAfterItHasBeenUsedOnce() {
        build(new DatePicker());
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        scene.bind(new StubWindow());
        scene.layoutPass(400, 320);
        scene.renderFrame(new FakeCanvas(400, 320));
        for (int round = 1; round <= 3; round++) {
            picker.open();
            assertTrue(picker.isOpen(), "round " + round + " did not open");
            assertNotNull(picker.calendar().parent(),
                    "round " + round + ": the grid is not in the popup that is showing");
            picker.close();
            settle();
            assertFalse(picker.isOpen());
        }
    }

    @Test
    void aPeriodCanBePickedFromTheGridMoreThanOnce() {
        build(DatePicker.ofRange());
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        scene.bind(new StubWindow());
        scene.layoutPass(400, 320);
        scene.renderFrame(new FakeCanvas(400, 320));
        for (int round = 1; round <= 2; round++) {
            picker.open();
            settle();
            picker.calendar().setSelectedRange(null);
            picker.calendar().setSelectedRange(new DateRange(
                    LocalDate.of(2026, 9, 1 + round), LocalDate.of(2026, 9, 10 + round)));
            picker.close();
            settle();
        }
        assertFalse(picker.isOpen());
    }

    /**
     * The other half of the same defect, and the one the fade-out's own release cannot cover:
     * closed and reopened before the old card has finished fading, which is what a person does
     * when they close a popup and immediately think better of it. The grid is taken back from the
     * card that is still on screen.
     */
    @Test
    void theCalendarOpensAgainEvenWhileTheOldCardIsStillFadingOut() {
        build(new DatePicker());
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        scene.bind(new StubWindow());
        scene.layoutPass(400, 320);
        scene.renderFrame(new FakeCanvas(400, 320));
        picker.open();
        settle();
        picker.close();
        // One frame only: the fade has started and is nowhere near done.
        nanos[0] += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(16);
        scene.renderFrame(new FakeCanvas(400, 320));
        picker.open();
        assertTrue(picker.isOpen());
        assertNotNull(picker.calendar().parent(), "the grid is in the new popup, not the old one");
    }

    /** Real time passes, so a fade-out finishes and the overlay is actually taken down. */
    private void settle() {
        for (int i = 0; i < 60; i++) {
            nanos[0] += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(16);
            scene.renderFrame(new FakeCanvas(400, 320));
        }
    }

    /**
     * The affordance is a tab stop and answers the keyboard, which is what makes it a control
     * rather than a picture of one. It was neither until a pair of eyes on the running program
     * said so: the function was reachable by Alt+Down from the field, and "reachable by another
     * route" is not the same as "this button works".
     */
    @Test
    void theCalendarButtonTakesFocusAndAnswersTheKeyboard() {
        build(new DatePicker());
        limn.scene.Widget affordance = picker.children().stream()
                .filter(child -> child != picker.field() && child.isFocusable())
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the trailing affordance is not a focusable child"));
        scene.requestFocus(affordance);
        assertTrue(affordance.isFocused());
        key(Keys.ENTER, 0);
        assertTrue(picker.isOpen(), "Enter on the button opens the calendar");
        // And the keyboard lands where it was going: the field, which is what forwards the
        // navigation keys to the grid while the popup has no focus of its own.
        assertTrue(picker.field().isFocused());
    }

    @Test
    void theKeyboardReachesTheMonthAndYearChoosers() {
        build(new DatePicker());
        picker.setDate(ANCHOR);
        picker.open();
        assertEquals(CalendarView.View.DAYS, picker.calendar().view());
        key(Keys.UP, Keys.MOD_CONTROL);
        assertEquals(CalendarView.View.MONTHS, picker.calendar().view(),
                "the choosers were reachable by pointer alone until this key existed");
        key(Keys.UP, Keys.MOD_CONTROL);
        assertEquals(CalendarView.View.YEARS, picker.calendar().view());
        key(Keys.DOWN, Keys.MOD_CONTROL);
        assertEquals(CalendarView.View.MONTHS, picker.calendar().view());
        key(Keys.ESCAPE, 0);
        assertEquals(CalendarView.View.DAYS, picker.calendar().view(),
                "Escape comes back down a level before it closes anything");
        assertTrue(picker.isOpen());
    }

    @Test
    void shiftTabLeavesTheHeaderForTheGridWithoutClosingThePopup() {
        build(new DatePicker());
        picker.setDate(ANCHOR);
        picker.open();
        key(Keys.TAB, 0);                    // grid -> the arrow that pages back
        key(Keys.TAB, Keys.MOD_SHIFT);       // and back to the grid
        assertTrue(picker.isOpen(), "coming out of the header is not leaving the popup");
        key(Keys.RIGHT, 0);
        assertEquals(ANCHOR.plusDays(1), picker.calendar().focusedDate(),
                "and the arrows move the day again, so the cursor really is in the grid");
    }

    @Test
    void comingDownFromTheHeaderLandsOnWhatIsShowingRatherThanJumping() {
        build(new DatePicker());
        picker.setDate(ANCHOR);              // September, which is cell 8 of the month chooser
        picker.open();
        picker.calendar().setView(CalendarView.View.MONTHS);
        key(Keys.TAB, 0);                    // into the header
        key(Keys.DOWN, 0);                   // back down into the chooser
        key(Keys.ENTER, 0);
        assertEquals(LocalDate.of(2026, 9, 1), picker.calendar().visibleMonth(),
                "the cursor came back onto the month on show, not four cells past it");
    }

    /**
     * The report this pins, in its own shape: Down out of the header showed no cursor at all, and
     * a second Down both created it and moved it, landing on December.
     */
    @Test
    void aSecondDownMovesTheCursorRatherThanCreatingIt() {
        build(new DatePicker());
        picker.setDate(ANCHOR);
        picker.open();
        picker.calendar().setView(CalendarView.View.MONTHS);
        key(Keys.TAB, 0);
        key(Keys.DOWN, 0);   // back into the chooser, cursor visible on September
        key(Keys.DOWN, 0);   // September is on the last row of four columns, so this stays put
        key(Keys.ENTER, 0);
        assertEquals(LocalDate.of(2026, 9, 1), picker.calendar().visibleMonth(),
                "an arrow off the bottom row stays where it is; it does not slide to December");
    }

    @Test
    void tabWrapsInsideThePopupRatherThanFallingOutOfIt() {
        build(new DatePicker());
        picker.setDate(ANCHOR);
        picker.open();
        for (int i = 0; i < 6; i++) {
            key(Keys.TAB, 0);   // right around the walk and past its end
        }
        assertTrue(picker.isOpen(),
                "a popup is somewhere you are until you leave it, and Escape is how you leave");
        key(Keys.TAB, Keys.MOD_SHIFT);
        assertTrue(picker.isOpen(), "and backwards is not an exit either");
        key(Keys.ESCAPE, 0);
        assertFalse(picker.isOpen());
    }

    @Test
    void theFieldComesFirstAndTheButtonSecond() {
        build(new DatePicker());
        List<limn.scene.Widget> stops = picker.children().stream()
                .filter(limn.scene.Widget::isFocusable)
                .toList();
        assertEquals(picker.field(), stops.get(0), "the text is what a person types into first");
        assertEquals(2, stops.size());
        assertNotEquals(picker.field(), stops.get(1), "and the button follows it");
    }

    /**
     * Picking a month with the keyboard puts you back in the grid, on a day, with a cursor you can
     * see. Without that the descent ends in a month with nothing focused and the next arrow moves
     * from somewhere the person cannot point at.
     */
    @Test
    void pickingAMonthReturnsToTheGridWithADayUnderTheCursor() {
        build(new DatePicker());
        picker.setDate(ANCHOR);
        picker.open();
        picker.calendar().setView(CalendarView.View.MONTHS);
        key(Keys.TAB, 0);
        key(Keys.DOWN, 0);      // into the chooser, on September
        key(Keys.LEFT, 0);      // August
        key(Keys.ENTER, 0);
        assertEquals(CalendarView.View.DAYS, picker.calendar().view());
        assertEquals(LocalDate.of(2026, 8, 1), picker.calendar().visibleMonth());
        assertNotNull(picker.calendar().focusedDate(), "the grid has a cursor on arrival");
        assertEquals(LocalDate.of(2026, 8, 1), picker.calendar().focusedDate(),
                "and it is on a day of the month just chosen, not one of the month left behind");
        // And the arrows move from there, rather than from wherever the cursor used to be.
        key(Keys.RIGHT, 0);
        assertEquals(LocalDate.of(2026, 8, 2), picker.calendar().focusedDate());
    }

    @Test
    void theCursorFollowsTheSelectionWhenItIsInTheMonthChosen() {
        build(new DatePicker());
        picker.setDate(LocalDate.of(2026, 8, 20));
        picker.open();
        picker.calendar().setView(CalendarView.View.MONTHS);
        picker.calendar().setVisibleMonth(LocalDate.of(2026, 9, 1));
        key(Keys.TAB, 0);
        key(Keys.DOWN, 0);
        key(Keys.LEFT, 0);      // back to August, where the selected day is
        key(Keys.ENTER, 0);
        assertEquals(LocalDate.of(2026, 8, 20), picker.calendar().focusedDate(),
                "somebody editing a date they already have lands on it");
    }

    @Test
    void aYearPickedLandsOnAMonthWithACursorToo() {
        build(new DatePicker());
        picker.setDate(ANCHOR);
        picker.open();
        picker.calendar().setView(CalendarView.View.YEARS);
        key(Keys.TAB, 0);
        key(Keys.DOWN, 0);
        key(Keys.ENTER, 0);
        assertEquals(CalendarView.View.MONTHS, picker.calendar().view());
        // The month chooser has a cursor on arrival: one Enter more takes a month rather than
        // needing an arrow first to make the cursor exist.
        key(Keys.ENTER, 0);
        assertEquals(CalendarView.View.DAYS, picker.calendar().view());
        assertNotNull(picker.calendar().focusedDate());
    }

    @Test
    void losingTheWindowsFocusClosesTheCalendar() {
        build(new DatePicker());
        picker.open();
        assertTrue(picker.isOpen());
        scene.windowFocusChanged(false);
        scene.inputBatchEnded();
        runtime.drain(); // the dismiss decision is deferred one loop turn
        assertFalse(picker.isOpen(), "a popup left floating over another application is stranded");
    }

    @Test
    void theFieldsHandlerSlotIsStillTheApplicationsToTake() {
        build(new DatePicker());
        // The picker follows its field through observeChanges precisely so that this call works:
        // a picker that had taken onChange for its own wiring would throw here.
        List<LocalDate> heard = new ArrayList<>();
        picker.field().onChange(heard::add);
        scene.requestFocus(picker.field());
        picker.setDate(ANCHOR);
        key(Keys.UP, 0);
        assertEquals(1, heard.size());
    }
}

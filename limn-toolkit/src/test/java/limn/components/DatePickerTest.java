package limn.components;

import limn.components.date.CalendarView;
import limn.components.date.DateField;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
        type("15");
        assertEquals(LocalDate.of(2026, 9, 15), picker.date(),
                "the digits went into the field's day segment, over the open calendar");
        assertTrue(picker.isOpen(), "and the calendar is still open");
        key(Keys.DOWN, 0);
        assertEquals(LocalDate.of(2026, 9, 22), picker.calendar().focusedDate(),
                "the grid followed the typed date and Down moved on from there");
        key(Keys.ENTER, 0);
        assertEquals(LocalDate.of(2026, 9, 22), picker.date(), "Enter picks, and the field takes it");
        assertFalse(picker.isOpen(), "and the grid closes behind it");
    }

    private void type(String text) {
        for (int i = 0; i < text.length(); i++) {
            scene.charTyped(text.charAt(i));
        }
        scene.inputBatchEnded();
    }

    /**
     * DATES-NEW-3: in the in-scene presentation the overlay holds the focus, so a digit that only
     * bubbled from the focused widget died at the overlay's root and the date never moved. The
     * overlay now hands every key and character it does not answer to the field whose caret is
     * showing, so the two presentations type alike.
     */
    @Test
    void whileTheInSceneCalendarIsOpenTypedDigitsAndBackspaceStillReachTheField() {
        build(new DatePicker());
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        picker.setDate(ANCHOR);
        picker.open();
        assertFalse(picker.field().isFocused(), "the overlay took the focus, as its contract says");
        type("15");
        assertEquals(LocalDate.of(2026, 9, 15), picker.date(), "the digits reached the day segment");
        assertTrue(picker.isOpen());
        key(Keys.BACKSPACE, 0);
        assertNull(picker.date(), "and so did Backspace: the segment the caret moved on to is empty");
        assertTrue(picker.isOpen());
        key(Keys.DOWN, 0);
        assertNotNull(picker.calendar().focusedDate(), "while the arrows still drive the grid");
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

    /**
     * Decisions 12 and 19: a picker down to the minute types its time in the field and, with the
     * popup open, in a time row under the grid that Tab reaches after the header. The row never
     * takes the focus; the keys and the digits are handed to it the way they are to the grid.
     */
    @Test
    void aPickerDownToTheMinuteCarriesATimeRowInItsPopupAndTabCyclesThroughIt() {
        build(new DatePicker().setGranularity(DateField.Granularity.MINUTE));
        picker.setDateTime(LocalDateTime.of(2026, 9, 9, 18, 30));
        assertEquals(LocalTime.of(18, 30), picker.time());
        assertEquals(ANCHOR, picker.date());
        assertEquals(LocalDateTime.of(2026, 9, 9, 18, 30), picker.dateTime());
        assertEquals("09/09/2026 18:30", picker.field().text());

        List<LocalDate> handled = new ArrayList<>();
        picker.onSelect(handled::add);
        picker.open();
        for (int i = 0; i < 4; i++) {
            key(Keys.TAB, 0);   // grid, previous, title, next, and on to the time row
        }
        assertTrue(picker.isOpen(), "the walk did not fall out of the popup");
        type("0905");
        assertEquals(LocalTime.of(9, 5), picker.time(), "the digits went into the time row");
        assertEquals(ANCHOR, picker.date(), "and the date is untouched");
        assertFalse(handled.isEmpty(), "typing into the row is the user operating the picker");
        assertEquals(ANCHOR, handled.get(handled.size() - 1));
        assertTrue(picker.isOpen());

        key(Keys.TAB, 0);       // round to the grid again
        key(Keys.RIGHT, 0);
        assertEquals(ANCHOR.plusDays(1), picker.calendar().focusedDate(),
                "Tab off the row lands on the grid, whose arrows move the day");
        key(Keys.TAB, Keys.MOD_SHIFT);   // back to the row, whose caret is still on the minute
        key(Keys.UP, 0);
        assertEquals(LocalTime.of(9, 6), picker.time(), "Up in the row steps the segment its caret is in");
        key(Keys.TAB, Keys.MOD_SHIFT);   // and back onto the header's last control
        key(Keys.ENTER, 0);
        assertEquals(LocalDate.of(2026, 10, 1), picker.calendar().visibleMonth(),
                "Shift+Tab off the row lands on the arrow that pages on");
        key(Keys.ESCAPE, 0);
        assertFalse(picker.isOpen());
        assertEquals(LocalDateTime.of(2026, 9, 9, 9, 6), picker.dateTime());
    }

    /**
     * The same cycle in the scene presentation, where the overlay holds the focus and hands
     * every key to the picker first (ADR 042 §11's amendment says "in both presentations", and
     * until 2026-09-14 only the windowless path pinned it): Tab reaches the row through the
     * overlay, the digits go into the row, Shift+Tab climbs back to the header, Escape closes.
     */
    @Test
    void theInSceneCalendarTabsThroughItsTimeRowTheSameWay() {
        build(new DatePicker().setGranularity(DateField.Granularity.MINUTE));
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        picker.setDateTime(LocalDateTime.of(2026, 9, 9, 18, 30));
        picker.open();
        assertFalse(picker.field().isFocused(), "the overlay took the focus");
        for (int i = 0; i < 4; i++) {
            key(Keys.TAB, 0);   // grid, previous, title, next, and on to the time row
        }
        assertTrue(picker.isOpen(), "the walk did not fall out of the popup");
        type("0905");
        assertEquals(LocalTime.of(9, 5), picker.time(), "the digits went into the time row");
        assertEquals(ANCHOR, picker.date(), "and the date is untouched");

        key(Keys.TAB, 0);       // round to the grid again
        key(Keys.RIGHT, 0);
        assertEquals(ANCHOR.plusDays(1), picker.calendar().focusedDate(),
                "Tab off the row lands on the grid, whose arrows move the day");
        type("15");
        assertEquals(LocalDate.of(2026, 9, 15), picker.date(),
                "and a digit typed there reaches the field again, not the row");
        assertEquals(LocalTime.of(9, 5), picker.time());
        key(Keys.TAB, Keys.MOD_SHIFT);   // back to the row
        key(Keys.UP, 0);
        assertEquals(LocalTime.of(9, 6), picker.time(), "Up in the row steps the segment its caret is in");
        key(Keys.TAB, Keys.MOD_SHIFT);   // and back onto the header's last control
        key(Keys.ENTER, 0);
        assertEquals(LocalDate.of(2026, 10, 1), picker.calendar().visibleMonth(),
                "Shift+Tab off the row lands on the arrow that pages on");
        key(Keys.ESCAPE, 0);
        assertFalse(picker.isOpen());
        assertEquals(LocalDateTime.of(2026, 9, 15, 9, 6), picker.dateTime());
    }

    /**
     * The card is built for the level it opened at: its time row is a child added when the card
     * was made. A level changed while the card is showing used to leave the old row on it (and
     * a new row off it) until the next open; the popup is now rebuilt around the change.
     */
    @Test
    void aLevelChangedWhileTheCalendarIsOpenRebuildsTheCardWithOrWithoutItsTimeRow() {
        build(new DatePicker().setGranularity(DateField.Granularity.MINUTE));
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        picker.setDateTime(LocalDateTime.of(2026, 9, 9, 18, 30));
        picker.open();
        limn.scene.Widget card = picker.calendar().parent();
        assertEquals(2, card.children().size(), "the grid and the time row");
        limn.scene.Widget row = card.children().get(1);

        picker.setGranularity(DateField.Granularity.DAY);
        assertTrue(picker.isOpen(), "still open");
        limn.scene.Widget rebuilt = picker.calendar().parent();
        assertNotSame(card, rebuilt, "a card built for the new level");
        assertEquals(List.of(picker.calendar()), rebuilt.children(), "the grid alone on it");
        assertNotSame(rebuilt, row.parent(), "and the old row is not on the card that is showing");
        assertEquals("09/09/2026", picker.field().text());

        picker.setGranularity(DateField.Granularity.MINUTE);
        assertTrue(picker.isOpen());
        assertEquals(2, picker.calendar().parent().children().size(),
                "a row again, on the card that is showing");
        for (int i = 0; i < 4; i++) {
            key(Keys.TAB, 0);
        }
        type("0905");
        assertEquals(LocalTime.of(9, 5), picker.time(), "and it is the row the keyboard reaches");
    }

    @Test
    void aMonthRangePickerAnswersWholeMonthsAtBothEnds() {
        build(DatePicker.ofRange().setGranularity(DateField.Granularity.MONTH));
        picker.field().setDate(LocalDate.of(2026, 3, 15));
        picker.endField().setDate(LocalDate.of(2026, 6, 15));
        assertEquals("03/2026", picker.field().text());
        assertEquals("06/2026", picker.endField().text());
        assertEquals(new DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 30)),
                picker.range(), "the first day of the first month to the last day of the last");
        picker.open();
        assertEquals(CalendarView.View.MONTHS, picker.calendar().view());
        assertEquals(picker.range(), picker.calendar().selectedRange(), "the grid follows the fields");
        // Picked from the grid: February to April, on the year on show. The cursor arrives on
        // March, the month the start field holds.
        key(Keys.LEFT, 0);        // February
        key(Keys.ENTER, 0);
        key(Keys.RIGHT, 0);
        key(Keys.RIGHT, 0);       // April
        key(Keys.ENTER, 0);
        assertFalse(picker.isOpen());
        assertEquals(new DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 30)),
                picker.range());
        assertEquals("02/2026", picker.field().text());
        assertEquals("04/2026", picker.endField().text());
    }

    @Test
    void anHourRangeRunsFromTheFirstMinuteOfItsStartToTheLastOfItsEnd() {
        build(DatePicker.ofRange().setGranularity(DateField.Granularity.HOUR));
        picker.field().setDateTime(LocalDateTime.of(2026, 3, 1, 0, 30));
        picker.endField().setDateTime(LocalDateTime.of(2026, 3, 1, 23, 30));
        assertEquals(LocalDateTime.of(2026, 3, 1, 0, 0), picker.field().dateTime(),
                "the start's hour begins at its first minute");
        assertEquals(LocalDateTime.of(2026, 3, 1, 23, 59, 59), picker.endField().dateTime(),
                "the end's hour runs to its last representable instant (decision 51)");
        assertEquals("01/03/2026 00", picker.field().text());
        assertEquals("01/03/2026 23", picker.endField().text());
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

    /**
     * DATES-NEW-3's second correction: over a window the in-scene card fades out and the
     * overlay is removed at the fade's end, so the focus request that followed a pick was made
     * while the overlay was still the top layer and refused; the removal then returned the
     * focus to what held it when the popup opened -- the button, when it was opened from
     * there -- and the field the pick had just filled was left unfocused.
     */
    @Test
    void aPickMadeOverAWindowHandsTheFocusToTheFieldOnceTheCardHasFaded() {
        build(new DatePicker());
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        picker.setDate(ANCHOR);
        scene.bind(new StubWindow());
        scene.layoutPass(400, 320);
        scene.renderFrame(new FakeCanvas(400, 320));
        limn.scene.Widget affordance = picker.children().stream()
                .filter(child -> child != picker.field() && child.isFocusable())
                .findFirst().orElseThrow();
        scene.requestFocus(affordance);
        key(Keys.ENTER, 0);
        assertTrue(picker.isOpen(), "opened from the button");
        assertFalse(picker.field().isFocused(), "the overlay holds the focus while it is open");
        settle();
        key(Keys.RIGHT, 0);
        key(Keys.ENTER, 0);
        assertFalse(picker.isOpen());
        assertEquals(ANCHOR.plusDays(1), picker.date(), "the pick reached the field");
        settle();       // the card fades out and the overlay is actually removed
        assertTrue(picker.field().isFocused(),
                "and the field has the focus, not the button the calendar was opened from");
        assertFalse(affordance.isFocused());
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
                "Escape comes straight back to the finest view before it closes anything");
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

    /**
     * Decision 57's typed half, on the one way out of the year that did not resolve it: a year
     * typed as two digits and left resolves through the window, or is left blank with the
     * guess off, however the caret leaves it. Opening the calendar in the scene is not a leave
     * (the digits still land in the field), but it reset the count of digits typed, so when the
     * calendar closed and the focus moved on the year stayed the year 26 -- valid, with the guess
     * on or off.
     */
    @Test
    void aTwoDigitYearTypedBeforeTheCalendarOpenedInTheSceneStillResolvesWhenTheFieldIsLeft() {
        java.time.Clock in2026 = java.time.Clock.fixed(java.time.Instant.parse("2026-09-09T12:00:00Z"),
                java.time.ZoneOffset.UTC);
        build(new DatePicker());
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        picker.setClock(in2026);
        picker.field().setTwoDigitYearWindow(DateField.REFUSE_TWO_DIGIT_YEARS);
        type("311226");
        assertEquals(LocalDate.of(26, 12, 31), picker.date(), "while the caret is still in the year");
        key(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(picker.isOpen());
        key(Keys.ESCAPE, 0);
        assertFalse(picker.isOpen());
        scene.requestFocus(null);
        assertNull(picker.date(), "with the guess off the typed two-digit year is left blank");
        assertFalse(picker.field().isValid(), "and the field is incomplete (decision 57)");

        build(new DatePicker());
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        picker.setClock(in2026);
        type("311226");
        key(Keys.DOWN, Keys.MOD_ALT);
        key(Keys.ESCAPE, 0);
        scene.requestFocus(null);
        assertEquals(LocalDate.of(2026, 12, 31), picker.date(),
                "and with the guess on it resolves into the window, as any other leave does");
    }
}

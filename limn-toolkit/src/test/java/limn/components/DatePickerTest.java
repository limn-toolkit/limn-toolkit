package limn.components;

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

    private void build(DatePicker built) {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        picker = built;
        scene = new Scene(picker);
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

package limn.components;

import limn.components.date.DatePicker;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.Clock;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.CellFacet;
import limn.accessibility.SelectionItemFacet;
import limn.accessibility.TableFacet;
import limn.components.date.CalendarView;
import limn.components.date.DateRange;
import limn.components.date.DayMark;
import limn.graphics.Color;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a screen reader is told about a month grid; ADR 042 §8.
 *
 * <p>The point of these assertions is that the answer is a <b>table</b>: the four roles ADR 041
 * mapped on all three platforms, and not one new one. A test that let a calendar drift into roles
 * of its own would be letting it drift out of every bridge at once.
 */
class CalendarViewAccessibilityTest extends AccessibleComponentTestBase {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 9);

    private CalendarView bindCalendar() {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        CalendarView calendar = new CalendarView();
        calendar.setVisibleMonth(ANCHOR);
        bind(calendar);
        return calendar;
    }

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    private AccessibleNode gridNode() {
        return node(Accessible.Role.TABLE);
    }

    private List<AccessibleNode> rowNodes() {
        List<AccessibleNode> rows = new ArrayList<>();
        for (AccessibleNode child : childrenOf(gridNode())) {
            if (child.role() == Accessible.Role.ROW) {
                rows.add(child);
            }
        }
        return rows;
    }

    /** Whether a node offers a verb. A node with none carries no action facet at all. */
    private static boolean offers(AccessibleNode node, Accessible.Action action) {
        return node.actions() != null && node.actions().actions().contains(action);
    }

    private List<AccessibleNode> dayNodes() {
        List<AccessibleNode> days = new ArrayList<>();
        for (AccessibleNode row : rowNodes()) {
            days.addAll(childrenOf(row));
        }
        return days;
    }

    private static final Clock MARCH_15 =
            Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    private List<String> daysCalledToday() {
        List<String> today = new ArrayList<>();
        for (AccessibleNode day : dayNodes()) {
            if (day.name().endsWith(", today")) {
                today.add(day.name());
            }
        }
        return today;
    }

    /**
     * Today is the clock's: the ring, what a reader hears on it, and the month a calendar opens on
     * while nobody has chosen one. It is what lets the site's gallery capture a calendar tomorrow
     * that matches the one captured today, where the wall clock moved the ring by a cell a day.
     */
    @Test
    void todayIsTheClocksAndSoIsTheMonthNobodyChose() {
        I18n.setLocale(Locale.US);
        CalendarView calendar = new CalendarView();
        calendar.setClock(MARCH_15);
        bind(calendar);
        assertEquals(LocalDate.of(2026, 3, 1), calendar.visibleMonth().withDayOfMonth(1),
                "a month nobody chose follows the clock");
        assertEquals(List.of("March 15, 2026, today"), daysCalledToday());
    }

    @Test
    void aMonthSomebodyChoseStaysWhenTheClockIsSet() {
        I18n.setLocale(Locale.US);
        CalendarView calendar = new CalendarView();
        calendar.setVisibleMonth(ANCHOR);
        calendar.setClock(MARCH_15);
        bind(calendar);
        assertEquals(LocalDate.of(2026, 9, 1), calendar.visibleMonth().withDayOfMonth(1),
                "September was chosen, so the clock moves only the ring");
        assertEquals(List.of(), daysCalledToday(), "March 15 is not in September's grid");
    }

    @Test
    void aPickerHandsItsClockToItsCalendar() {
        DatePicker picker = new DatePicker();
        picker.setClock(MARCH_15);
        assertEquals(LocalDate.of(2026, 3, 1), picker.calendar().visibleMonth().withDayOfMonth(1));
    }

    @Test
    void theGridIsATableOfSixWeeksBySevenDays() {
        bindCalendar();
        AccessibleNode grid = gridNode();
        assertNotNull(grid, "a calendar publishes a table");
        TableFacet table = grid.table();
        assertNotNull(table);
        assertEquals(6, table.rowCount(), "always six rows, whatever the month needs");
        assertEquals(7, table.columnCount());
        assertEquals(6, rowNodes().size());
        assertEquals(42, dayNodes().size());
    }

    @Test
    void theWeekNumberColumnAddsAColumnAndNotARow() {
        CalendarView calendar = bindCalendar();
        calendar.setShowWeekNumbers(true);
        frame();
        assertEquals(8, gridNode().table().columnCount());
        assertEquals(6, rowNodes().size());
        assertEquals(48, dayNodes().size(), "seven days and one week number per row");
    }

    @Test
    void theHeaderRowNamesEachColumnWithTheWholeWeekdayAndNotTheLetterDrawn() {
        bindCalendar();
        AccessibleNode header = childrenOf(gridNode()).stream()
                .filter(child -> child.role() == Accessible.Role.GROUP)
                .findFirst().orElseThrow();
        List<AccessibleNode> heads = childrenOf(header);
        assertEquals(7, heads.size());
        for (AccessibleNode head : heads) {
            assertEquals(Accessible.Role.COLUMN_HEADER, head.role());
            assertNotNull(head.cell());
            assertEquals(-1, head.cell().row(), "a header is not in a row");
            assertTrue(head.name().length() > 2,
                    "the whole weekday, not the narrow letter: " + head.name());
        }
        // pt-BR starts its week on Sunday, and the header says so.
        assertTrue(heads.get(0).name().toLowerCase(Locale.ROOT).startsWith("dom"),
                heads.get(0).name());
    }

    @Test
    void aDayIsNamedWithTheWholeDateBecauseACellHeardAloneHasToSayWhatItIs() {
        bindCalendar();
        AccessibleNode ninth = dayNodes().stream()
                .filter(day -> day.name().startsWith("9 de setembro"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no cell named for the ninth; got " + dayNodes().get(0).name()));
        assertEquals(Accessible.Role.CELL, ninth.role());
        CellFacet cell = ninth.cell();
        assertNotNull(cell);
        assertTrue(cell.row() >= 0 && cell.row() < 6);
        assertTrue(cell.column() >= 0 && cell.column() < 7);
    }

    @Test
    void aDayTheBoundsRefuseCarriesNoSelectVerb() {
        CalendarView calendar = bindCalendar();
        calendar.setMinDate(LocalDate.of(2026, 9, 10));
        frame();
        List<AccessibleNode> days = dayNodes();
        AccessibleNode before = days.stream()
                .filter(day -> day.name().startsWith("9 de setembro")).findFirst().orElseThrow();
        AccessibleNode after = days.stream()
                .filter(day -> day.name().startsWith("15 de setembro")).findFirst().orElseThrow();
        assertFalse(offers(before, Accessible.Action.SELECT),
                "a day before the minimum offers nothing to do");
        assertTrue(offers(after, Accessible.Action.SELECT));
    }

    @Test
    void aFilteredDayCarriesNoSelectVerbEither() {
        CalendarView calendar = bindCalendar();
        calendar.setDateFilter(day -> day.getDayOfWeek() != DayOfWeek.SUNDAY);
        frame();
        AccessibleNode sunday = dayNodes().stream()
                .filter(day -> day.name().startsWith("6 de setembro")).findFirst().orElseThrow();
        assertFalse(offers(sunday, Accessible.Action.SELECT));
    }

    @Test
    void aSelectedDayAndEveryDayInAPeriodSaysSo() {
        CalendarView calendar = bindCalendar();
        calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
        calendar.setSelectedRange(new DateRange(LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 16)));
        frame();
        List<AccessibleNode> inBand = dayNodes().stream()
                .filter(day -> day.selectionItem() != null && day.selectionItem().selected())
                .toList();
        assertEquals(3, inBand.size(), "the two ends and the day between them");
    }

    /**
     * D2 (ADR 039 §1.10, amended 2026-09-14; semantics 1): a day hangs under a synthetic week
     * row, but the row holds no selection — the grid does — so the selection change is the
     * grid's, carrying the day that entered and the day that left; a band in {@code RANGE} is
     * one event on the grid naming every day of it.
     */
    @Test
    void choosingADayTellsTheGridItsSelectionMovedAndNotTheWeekRow() {
        CalendarView calendar = bindCalendar();
        frame();
        bridge.events.clear();

        calendar.setSelectedDate(LocalDate.of(2026, 9, 15));
        frame();

        AccessibleNode fifteenth = dayNodes().stream()
                .filter(day -> day.name().startsWith("15 de setembro")).findFirst().orElseThrow();
        assertTrue(fifteenth.selectionContainer() != AccessibleNode.NONE,
                "the day belongs to a container: " + describe(tree()));
        assertEquals(gridNode().id(), tree().node(fifteenth.selectionContainer()).id(),
                "the day's container is the grid, climbed to through the synthetic row: "
                        + describe(tree()));
        List<AccessibleEvent> moved = bridge.eventsOf(AccessibleEvent.Type.SELECTION_CHANGED);
        assertEquals(1, moved.size(), "one selection change: " + bridge.events);
        assertEquals(gridNode().id(), moved.get(0).nodeId(),
                "on the grid, not the week row: " + bridge.events);
        assertEquals(List.of(fifteenth.id()), moved.get(0).addedMembers(), bridge.events.toString());
        assertEquals(List.of(), moved.get(0).removedMembers(), bridge.events.toString());
        assertFalse(moved.get(0).multiSelectable());

        calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
        calendar.setSelectedRange(new DateRange(LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 23)));
        bridge.events.clear();
        frame();

        moved = bridge.eventsOf(AccessibleEvent.Type.SELECTION_CHANGED);
        assertEquals(1, moved.size(), "a band is one selection change: " + bridge.events);
        assertEquals(gridNode().id(), moved.get(0).nodeId(), bridge.events.toString());
        assertTrue(moved.get(0).multiSelectable(), "the grid selects a band in RANGE");
        assertEquals(3, moved.get(0).addedMembers().size(),
                "the two ends and the day between them entered: " + bridge.events);
        assertEquals(List.of(fifteenth.id()), moved.get(0).removedMembers(),
                "and the day chosen before left: " + bridge.events);
    }

    @Test
    void whatAMarkSaysReachesTheReaderAndNotOnlyTheEye() {
        CalendarView calendar = bindCalendar();
        calendar.setDayMarks(day -> day.equals(LocalDate.of(2026, 9, 21))
                ? DayMark.of(Color.rgb(0xEF4444), I18nString.literal("feriado"))
                : null);
        frame();
        AccessibleNode marked = dayNodes().stream()
                .filter(day -> day.name().startsWith("21 de setembro")).findFirst().orElseThrow();
        assertTrue(marked.name().endsWith("feriado"),
                "a dot nobody can see is a fact nobody hears: " + marked.name());
    }

    @Test
    void thePagingButtonsAreNamedAndDoWhatTheySay() throws InterruptedException {
        CalendarView calendar = bindCalendar();
        List<AccessibleNode> buttons = childrenOf(gridNode()).stream()
                .filter(child -> child.role() == Accessible.Role.BUTTON)
                .toList();
        // Three: the two that page, and the title, which is how a reader reaches the choosers.
        assertEquals(3, buttons.size());
        for (AccessibleNode button : buttons) {
            assertFalse(button.name().isBlank(), "a button nobody can name is a button nobody uses");
            assertTrue(offers(button, Accessible.Action.PRESS));
        }
        assertTrue(perform(buttons.get(0).id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        assertEquals(LocalDate.of(2026, 8, 1), calendar.visibleMonth(),
                "the first button is the one that goes back");
    }

    /**
     * The cells sit centred on the widget in every view, like the title above them.
     *
     * <p>Measured rather than eyeballed, because the defect this pins was twelve points and looked
     * like nothing until somebody noticed it: the week-number gutter was reserved in every view, so
     * a chooser centred its four columns inside a box that included a column nothing is drawn in.
     * Read from the published boxes, which is the same geometry a click lands in.
     */
    @Test
    void theCellsAreCentredOnTheWidgetInEveryView() {
        CalendarView calendar = bindCalendar();
        calendar.setShowWeekNumbers(true);
        for (CalendarView.View view : CalendarView.View.values()) {
            calendar.setView(view);
            frame();
            float left = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            for (AccessibleNode cell : dayNodes()) {
                left = Math.min(left, cell.x());
                right = Math.max(right, cell.x() + cell.width());
            }
            assertEquals(calendar.width() / 2, (left + right) / 2, 0.01f,
                    view + ": the cells are off the widget's centre");
        }
    }

    @Test
    void theTitleIsAButtonThatClimbsToTheMonthAndYearChoosers() throws InterruptedException {
        CalendarView calendar = bindCalendar();
        AccessibleNode title = childrenOf(gridNode()).stream()
                .filter(child -> child.role() == Accessible.Role.BUTTON)
                .filter(child -> child.name().toLowerCase(Locale.ROOT).contains("setembro"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the header's title is not published as a button"));
        assertTrue(perform(title.id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        assertEquals(CalendarView.View.MONTHS, calendar.view());
        frame();
        assertEquals(4, gridNode().table().columnCount());
        assertEquals(3, gridNode().table().rowCount());
        List<AccessibleNode> cells = dayNodes();
        assertEquals(12, cells.size(), "the twelve months");
        assertTrue(cells.get(0).name().toLowerCase(Locale.ROOT).startsWith("jan"),
                cells.get(0).name());
    }

    @Test
    void aMonthPickedInTheChooserNavigatesAndSelectsNothing() throws InterruptedException {
        CalendarView calendar = bindCalendar();
        calendar.setView(CalendarView.View.MONTHS);
        frame();
        AccessibleNode march = dayNodes().get(2);
        assertTrue(perform(march.id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        assertEquals(LocalDate.of(2026, 3, 1), calendar.visibleMonth());
        assertEquals(CalendarView.View.DAYS, calendar.view(), "and it comes back down");
        assertNull(calendar.selectedDate(),
                "navigating to a month is not choosing a date, and must not look like one");
    }

    @Test
    void selectingACellFromOutsideReachesThePathAClickReaches() throws InterruptedException {
        CalendarView calendar = bindCalendar();
        AccessibleNode fifteenth = dayNodes().stream()
                .filter(day -> day.name().startsWith("15 de setembro")).findFirst().orElseThrow();
        assertTrue(perform(fifteenth.id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        assertEquals(LocalDate.of(2026, 9, 15), calendar.selectedDate());
    }

    @Test
    void selectingADayTheGridRefusesIsRefusedRatherThanReportedDone() throws InterruptedException {
        CalendarView calendar = bindCalendar();
        calendar.setMinDate(LocalDate.of(2026, 9, 20));
        frame();
        AccessibleNode ninth = dayNodes().stream()
                .filter(day -> day.name().startsWith("9 de setembro")).findFirst().orElseThrow();
        // Asserted on the outcome and not on what perform() answers: that boolean is the scene
        // accepting the request for dispatch, which is not the same question as whether the
        // widget did it.
        perform(ninth.id(), Accessible.Action.SELECT, Accessible.Argument.NONE);
        assertNull(calendar.selectedDate(),
                "a day the pointer cannot pick is not one an assistive technology can either");
    }
}

package limn.components;

import limn.accessibility.Accessible;
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

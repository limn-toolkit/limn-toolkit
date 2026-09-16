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
import limn.input.Keys;
import limn.scene.Change;
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
        return bindCalendar(Locale.forLanguageTag("pt-BR"));
    }

    private CalendarView bindCalendar(Locale locale) {
        I18n.setLocale(locale);
        CalendarView calendar = new CalendarView();
        calendar.setVisibleMonth(ANCHOR);
        bind(calendar);
        return calendar;
    }

    /** English, drawing the Umm al-Qura calendar: the month names are readable in an assertion. */
    private static final Locale HIJRI = Locale.forLanguageTag("en-US-u-ca-islamic-umalqura");
    private static final Locale JAPANESE = Locale.forLanguageTag("ja-JP-u-ca-japanese");

    /** The three header buttons in tree order: the arrow back, the arrow on, the title. */
    private List<AccessibleNode> headerButtons() {
        List<AccessibleNode> buttons = childrenOf(gridNode()).stream()
                .filter(child -> child.role() == Accessible.Role.BUTTON).toList();
        assertEquals(3, buttons.size(), describe(tree()));
        return buttons;
    }

    private AccessibleNode titleNode() {
        return headerButtons().get(2);
    }

    private AccessibleNode pagingButton(boolean next) {
        return headerButtons().get(next ? 1 : 0);
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
        // Decision 30: and it says why, as a disabled item -- the narrowing-only declaration the
        // model gained for exactly this -- so a reader whose cursor stops on it hears "unavailable".
        assertFalse(before.has(Accessible.State.ENABLED),
                "a refused day is published disabled: " + describe(tree()));
        assertTrue(after.has(Accessible.State.ENABLED));
        assertTrue(gridNode().has(Accessible.State.ENABLED), "while the grid itself is not");
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

    /**
     * Settled calendar-title-verbs (2026-09-14; DATES-NEW-4, WINDOWS-NEW-10's widget half): the
     * title advertised an expand state and refused the verbs a bridge vends from it. It now
     * publishes EXPAND on the finest view and COLLAPSE above it, one at a time, and accepts
     * exactly those; PRESS keeps climbing a step. The refusal was a claim by reading until this
     * test performed the verb.
     */
    @Test
    void theTitleExpandsAndCollapsesAsWellAsPressesAndRefusesTheVerbItDoesNotPublish()
            throws InterruptedException {
        CalendarView calendar = bindCalendar();
        AccessibleNode title = titleNode();
        assertTrue(offers(title, Accessible.Action.EXPAND), "the day view can be climbed out of");
        assertFalse(offers(title, Accessible.Action.COLLAPSE));
        assertFalse(title.has(Accessible.State.EXPANDED));
        assertTrue(perform(title.id(), Accessible.Action.EXPAND, Accessible.Argument.NONE));
        assertEquals(CalendarView.View.MONTHS, calendar.view(), "Expand climbs");
        frame();
        title = titleNode();
        assertTrue(title.has(Accessible.State.EXPANDED));
        assertTrue(offers(title, Accessible.Action.COLLAPSE));
        assertFalse(offers(title, Accessible.Action.EXPAND));
        perform(title.id(), Accessible.Action.EXPAND, Accessible.Argument.NONE);
        assertEquals(CalendarView.View.MONTHS, calendar.view(),
                "a verb the node does not publish changes nothing (ADR 039 §1.5)");
        perform(title.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        assertEquals(CalendarView.View.YEARS, calendar.view(), "Press still climbs a step");
        frame();
        perform(titleNode().id(), Accessible.Action.COLLAPSE, Accessible.Argument.NONE);
        assertEquals(CalendarView.View.DAYS, calendar.view(),
                "Collapse comes straight back to the finest view, as Escape does");
    }

    /**
     * GALLERY-NEW-2, 2026-09-15: after Ctrl (or Cmd) and Up climb out of the days, the month on
     * show is the cursor at once, and a reader hears it.
     *
     * <p>It was nothing at all until this date: the view change cleared the chooser's cursor and
     * only the first arrow placed it, so the tree published no {@code ACTIVE} cell in the grid a
     * person was now standing in. A reader following the active descendant was told the grid had
     * moved and not where it stood, and heard the first month only after an arrow that had already
     * stepped past it. The reader recipes had to say "the months are shown" where every other step
     * says where the cursor is.
     */
    @Test
    void aClimbToTheMonthsLandsTheCursorOnTheMonthOnShowAndAnnouncesIt() {
        CalendarView calendar = bindCalendar(Locale.US);
        scene.requestFocus(calendar);
        frame();
        List<Change> heard = new ArrayList<>();
        calendar.observeChanges((widget, change) -> heard.add(change));

        key(Keys.UP, Accelerator.commandModifier());
        frame();

        assertEquals(CalendarView.View.MONTHS, calendar.view(), describe(tree()));
        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(),
                "one cell of the chooser is the cursor: " + describe(tree()));
        assertEquals("Sep, on show", active.get(0).name(),
                "and it is the month the grid is drawn for, not cell zero and not nothing"
                        + describe(tree()));
        assertEquals(tree().indexOf(active.get(0).id()),
                tree().indexOf(tree().activeDescendant()),
                "so the active descendant a reader follows lands on it" + describe(tree()));
        assertTrue(heard.stream().anyMatch(change -> change.aspect() == Change.Aspect.ACTIVE),
                "and the arrival is announced, as every other cursor move is: " + heard);

        // Arriving is not moving: the first arrow steps one cell, it does not place the cursor.
        key(Keys.RIGHT, 0);
        frame();

        assertEquals("Oct", nodesWith(Accessible.State.ACTIVE).get(0).name(), describe(tree()));
    }

    /** And the same on the second climb, where the cell on show is a year of the block. */
    @Test
    void aClimbToTheYearsLandsTheCursorOnTheYearOnShow() {
        CalendarView calendar = bindCalendar(Locale.US);
        scene.requestFocus(calendar);
        frame();

        key(Keys.UP, Accelerator.commandModifier());
        key(Keys.UP, Accelerator.commandModifier());
        frame();

        assertEquals(CalendarView.View.YEARS, calendar.view(), describe(tree()));
        assertEquals("2026, on show", nodesWith(Accessible.State.ACTIVE).get(0).name(),
                "the year the calendar is showing, inside its block" + describe(tree()));
    }

    /**
     * The fallback is the calendar's and the field is the user's: a caller that moves what is on
     * show while the chooser stands moves the cursor with it, and an arrow that has placed the
     * cursor pins it where the user put it.
     */
    @Test
    void theCursorFollowsWhatIsOnShowUntilAnArrowHasMovedIt() {
        CalendarView calendar = bindCalendar(Locale.US);
        scene.requestFocus(calendar);
        key(Keys.UP, Accelerator.commandModifier());
        frame();
        assertEquals("Sep, on show", nodesWith(Accessible.State.ACTIVE).get(0).name(),
                describe(tree()));

        calendar.setVisibleMonth(LocalDate.of(2028, 2, 10));
        frame();

        assertEquals("Feb, on show", nodesWith(Accessible.State.ACTIVE).get(0).name(),
                "nobody has moved the cursor, so it is still whatever is on show" + describe(tree()));

        key(Keys.RIGHT, 0);
        calendar.setVisibleMonth(LocalDate.of(2028, 7, 10));
        frame();

        assertEquals("Mar", nodesWith(Accessible.State.ACTIVE).get(0).name(),
                "once an arrow has put the cursor somewhere, it stays there" + describe(tree()));
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

    // ------------------------------------------------ the drawn calendar's own months (DATES-NEW-1)

    /**
     * The visible month is a month of the calendar being drawn. Before 2026-09-14 it was the ISO
     * month holding the drawn month's first day, so "next" from a Hijri month computed a day whose
     * ISO first was the one already shown and did nothing, for 95 of the 132 ISO months of a
     * decade (the cluster's replay); this presses the real button and reads the real title.
     */
    @Test
    void aHijriGridPagesByHijriMonths() throws InterruptedException {
        CalendarView calendar = bindCalendar(HIJRI);
        assertEquals(LocalDate.of(2026, 8, 14), calendar.visibleMonth(),
                "September 9 is in Rabiʻ I 1448, which began on 14 August");
        assertTrue(titleNode().name().contains("1448"), titleNode().name());

        assertTrue(perform(pagingButton(true).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));
        frame();
        assertEquals(LocalDate.of(2026, 9, 12), calendar.visibleMonth(),
                "the next Hijri month, whose first day is 12 September, not the same month again");
        assertTrue(titleNode().name().startsWith("Rabiʻ II"), titleNode().name());
        assertTrue(dayNodes().stream().anyMatch(day -> day.name().startsWith("Rabiʻ II 1, 1448")),
                "the first of the month is on the grid: " + dayNodes().get(0).name());
    }

    @Test
    void aSelectedHijriDayIsOnTheGrid() {
        CalendarView calendar = bindCalendar(HIJRI);
        // 25 September is Rabiʻ II 14; the grid of the ISO month "September" ran out on the 19th.
        calendar.setSelectedDate(LocalDate.of(2026, 9, 25));
        frame();
        List<AccessibleNode> selected = dayNodes().stream()
                .filter(day -> day.selectionItem() != null && day.selectionItem().selected())
                .toList();
        assertEquals(1, selected.size(), "the selected day is a published cell: " + describe(tree()));
        assertTrue(selected.get(0).name().startsWith("Rabiʻ II 14, 1448"), selected.get(0).name());
    }

    @Test
    void aHijriMonthPickedInTheChooserIsTheMonthShown() throws InterruptedException {
        CalendarView calendar = bindCalendar(HIJRI);
        calendar.setView(CalendarView.View.MONTHS);
        frame();
        AccessibleNode fourth = dayNodes().get(3);
        assertTrue(fourth.name().startsWith("Rab"), "the fourth month, abbreviated: " + fourth.name());
        assertTrue(perform(fourth.id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        assertEquals(CalendarView.View.DAYS, calendar.view());
        assertEquals(LocalDate.of(2026, 9, 12), calendar.visibleMonth(),
                "the month picked is the month drawn; 117 of 120 picks landed elsewhere before");
    }

    /**
     * The year chooser blocks by the proleptic year and labels each cell with the year of era,
     * with its era where a block crosses one: blocking by the year of era put Reiwa 0 at the
     * head of a block and left 2012 to 2018 in no block at all.
     */
    @Test
    void aJapaneseYearChooserPagesWithoutSkippingYearsAndNamesTheEra() throws InterruptedException {
        CalendarView calendar = bindCalendar(JAPANESE);
        calendar.setView(CalendarView.View.YEARS);
        frame();
        assertEquals("平成28 – 令和21", titleNode().name(), "2016 to 2039, each in its own era");
        List<AccessibleNode> cells = dayNodes();
        assertEquals(24, cells.size());
        assertEquals("平成28", cells.get(0).name());
        assertEquals("平成31", cells.get(3).name(), "2019 opens in Heisei, the era of its first day");
        assertEquals("令和2", cells.get(4).name());

        assertTrue(perform(pagingButton(false).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));
        frame();
        assertEquals("平成4 – 平成27", titleNode().name(), "1992 to 2015: nothing skipped");
        assertEquals("平成27", dayNodes().get(23).name());

        assertTrue(perform(dayNodes().get(23).id(), Accessible.Action.SELECT,
                Accessible.Argument.NONE));
        assertEquals(CalendarView.View.MONTHS, calendar.view());
        assertEquals(LocalDate.of(2015, 1, 1), calendar.visibleMonth());
        frame();
        assertEquals("平成27", titleNode().name(), "the month chooser's title is the era's year too");
    }

    /**
     * Decision 48: the chooser a calendar picks in carries a real selection, because there the
     * month is the value; a chooser somebody is passing through still carries none.
     */
    @Test
    void aMonthPickersCellsCarryASelectionAndAPickMovesIt() throws InterruptedException {
        CalendarView calendar = bindCalendar();
        calendar.setGranularity(CalendarView.View.MONTHS);
        calendar.setSelectedDate(LocalDate.of(2026, 9, 15));
        frame();
        List<AccessibleNode> months = dayNodes();
        assertEquals(12, months.size());
        assertNotNull(months.get(8).selectionItem(), "September carries a selection item");
        assertTrue(months.get(8).selectionItem().selected(), "and is the one selected");
        assertFalse(months.get(2).selectionItem().selected());
        assertTrue(perform(months.get(2).id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        assertEquals(LocalDate.of(2026, 3, 1), calendar.selectedDate());
        assertEquals(CalendarView.View.MONTHS, calendar.view(), "a pick, not a descent");
        frame();
        assertTrue(dayNodes().get(2).selectionItem().selected());
        assertFalse(dayNodes().get(8).selectionItem().selected());

        calendar.setView(CalendarView.View.YEARS);
        frame();
        assertNull(dayNodes().get(0).selectionItem(),
                "the year chooser above a month picker is navigation and carries none");
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

    /**
     * Decision 48 (MODEL-NEW-1): the chooser cell holding the month or year on show is filled on
     * screen, and the only thing that told a reader was a CHECKED the builder dropped. The fact
     * now rides in the cell's name, after a comma like "today", in a chooser somebody is
     * passing through; the chooser a month picker picks in carries a real selection instead and
     * no word, because there the month is the value and "on show" would be a second, weaker
     * claim beside it.
     */
    @Test
    void theChooserNamesTheCellOnShowAndAMonthPickerSaysItWithASelectionInstead()
            throws InterruptedException {
        CalendarView calendar = bindCalendar(Locale.US);
        calendar.setView(CalendarView.View.MONTHS);
        frame();
        List<AccessibleNode> months = dayNodes();
        assertEquals(12, months.size());
        for (int i = 0; i < months.size(); i++) {
            assertEquals(i == 8, months.get(i).name().endsWith(", on show"),
                    "only September, which is the month on show: " + months.get(i).name());
        }
        assertEquals("Sep, on show", months.get(8).name());
        // The word follows the month on show: a page of a year moves nothing, a different month
        // shown moves it, and a client hears the two names change rather than reading them again.
        assertTrue(perform(pagingButton(true).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));
        frame();
        assertEquals("Sep, on show", dayNodes().get(8).name(), "September 2027 is on show now");
        bridge.events.clear();
        calendar.setVisibleMonth(LocalDate.of(2027, 3, 1));
        frame();
        assertEquals("Mar, on show", dayNodes().get(2).name());
        assertEquals("Sep", dayNodes().get(8).name());
        assertEquals(2, bridge.eventsOf(AccessibleEvent.Type.NAME_CHANGED).size(),
                "the word left one cell and reached another: " + bridge.events);

        calendar.setView(CalendarView.View.YEARS);
        frame();
        List<AccessibleNode> years = dayNodes();
        assertEquals(24, years.size());
        List<String> onShow = years.stream().map(AccessibleNode::name)
                .filter(name -> name.endsWith(", on show")).toList();
        assertEquals(List.of("2027, on show"), onShow, "the year on show, and no other");

        calendar.setGranularity(CalendarView.View.MONTHS);
        calendar.setView(CalendarView.View.MONTHS);
        calendar.setSelectedDate(LocalDate.of(2026, 9, 15));
        frame();
        months = dayNodes();
        for (AccessibleNode month : months) {
            assertFalse(month.name().endsWith(", on show"),
                    "a month picker's chooser says it with a selection: " + month.name());
        }
        assertTrue(months.get(8).selectionItem().selected());
    }

    /**
     * DATES-NEW-11: the two arrows page a month of days, a year of months and a block of
     * twenty-four years, and were named "Previous month" and "Next month" in all three views.
     * The chevrons carry no words, so only a reader was told the wrong unit.
     */
    @Test
    void thePagingButtonsSayWhatTheyPageInEveryView() throws InterruptedException {
        CalendarView calendar = bindCalendar(Locale.US);
        assertEquals("Previous month", pagingButton(false).name());
        assertEquals("Next month", pagingButton(true).name());

        calendar.setView(CalendarView.View.MONTHS);
        frame();
        assertEquals("Previous year", pagingButton(false).name());
        assertEquals("Next year", pagingButton(true).name());
        assertTrue(perform(pagingButton(true).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));
        assertEquals(LocalDate.of(2027, 9, 1), calendar.visibleMonth(), "and it does page a year");

        calendar.setView(CalendarView.View.YEARS);
        frame();
        assertEquals("Previous 24 years", pagingButton(false).name());
        assertEquals("Next 24 years", pagingButton(true).name());
        assertTrue(perform(pagingButton(false).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));
        assertEquals(LocalDate.of(2003, 9, 1), calendar.visibleMonth(),
                "and a block of twenty-four back");
        frame();
        assertEquals("Previous 24 years", pagingButton(false).name(),
                "the name is the view's, not the press's");
    }

    /**
     * DT2: a node's identity is its (parent, key) pair, so a chooser cell keyed like a day cell
     * WAS the day cell across a view change -- five of them, the ones whose row and index
     * coincided -- and a Windows element built for the month cell, which carries no selection
     * item, answered no SelectionItem for the day it later stood for. The chooser's rows and
     * cells now have keys of their own, and a view change destroys one set and mints the other.
     *
     * <p>The months and the years have keys apart from each other too: in a month picker the
     * months are the terminal chooser and carry a selection item, the years a person climbs to
     * carry none, and one shared chooser range made every month cell the year cell it replaced.
     */
    @Test
    void dayAndChooserCellsAreDifferentNodes() {
        CalendarView calendar = bindCalendar();
        java.util.Set<Long> days = new java.util.HashSet<>();
        for (AccessibleNode row : rowNodes()) {
            days.add(row.id());
            for (AccessibleNode cell : childrenOf(row)) {
                days.add(cell.id());
            }
        }
        assertEquals(6 + 42, days.size());

        calendar.setView(CalendarView.View.MONTHS);
        frame();
        java.util.Set<Long> months = new java.util.HashSet<>();
        for (AccessibleNode row : rowNodes()) {
            months.add(row.id());
            for (AccessibleNode cell : childrenOf(row)) {
                months.add(cell.id());
            }
        }
        assertEquals(3 + 12, months.size());
        assertTrue(java.util.Collections.disjoint(days, months),
                "a month cell is never the node a day cell was: " + describe(tree()));

        calendar.setView(CalendarView.View.YEARS);
        frame();
        java.util.Set<Long> years = new java.util.HashSet<>();
        for (AccessibleNode row : rowNodes()) {
            years.add(row.id());
            for (AccessibleNode cell : childrenOf(row)) {
                years.add(cell.id());
            }
        }
        assertEquals(6 + 24, years.size());
        assertTrue(java.util.Collections.disjoint(days, years));
        assertTrue(java.util.Collections.disjoint(months, years),
                "a year cell is never the node a month cell was");

        calendar.setGranularity(CalendarView.View.MONTHS);
        calendar.setView(CalendarView.View.YEARS);
        frame();
        java.util.Set<Long> climbed = chooserNodeIds(false);
        calendar.setView(CalendarView.View.MONTHS);
        frame();
        java.util.Set<Long> picked = chooserNodeIds(true);
        assertEquals(6 + 24, climbed.size());
        assertEquals(3 + 12, picked.size());
        assertTrue(java.util.Collections.disjoint(climbed, picked),
                "in a month picker the months, which carry a selection item, are never the"
                        + " year nodes, which carry none: " + describe(tree()));
    }

    /**
     * The ids of the rows and cells on show, each cell checked for whether it carries a
     * selection item: the interface set a Windows element is built with on first read.
     */
    private java.util.Set<Long> chooserNodeIds(boolean selectable) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (AccessibleNode row : rowNodes()) {
            ids.add(row.id());
            for (AccessibleNode cell : childrenOf(row)) {
                ids.add(cell.id());
                assertEquals(selectable, cell.selectionItem() != null, cell.name());
            }
        }
        return ids;
    }

    /**
     * Decision 37: a day cell is "15 of 30" -- its day of the month over the month's length, in
     * the calendar being drawn, each cell by its own date -- and not "item 17 of 42", which was
     * the grid's geometry read out as if it were a position in a set. A leading cell of the
     * month before is that month's last day over that month's length; a chooser cell keeps its
     * index over the chooser's count.
     */
    @Test
    void aDayCellIsNumberedByItsDayOfMonthOverTheMonthsLength() {
        CalendarView calendar = bindCalendar();
        List<AccessibleNode> days = dayNodes();
        // pt-BR starts the week on Sunday; September 2026 starts on a Tuesday, so the first row
        // opens with 30 and 31 August.
        assertPosition(days.get(0), 30, 31);
        assertPosition(days.get(1), 31, 31);
        assertPosition(days.get(2), 1, 30);
        assertPosition(days.get(16), 15, 30);
        assertPosition(days.get(31), 30, 30);
        assertPosition(days.get(32), 1, 31);
        assertPosition(days.get(41), 10, 31);

        calendar.setView(CalendarView.View.MONTHS);
        calendar.setGranularity(CalendarView.View.MONTHS);
        frame();
        assertPosition(dayNodes().get(2), 3, 12);

        CalendarView hijri = bindCalendar(HIJRI);
        hijri.setSelectedDate(LocalDate.of(2026, 9, 25));
        frame();
        AccessibleNode selected = dayNodes().stream()
                .filter(day -> day.selectionItem() != null && day.selectionItem().selected())
                .findFirst().orElseThrow();
        assertTrue(selected.name().startsWith("Rabiʻ II 14, 1448"), selected.name());
        java.time.chrono.ChronoLocalDate drawn =
                java.time.chrono.HijrahChronology.INSTANCE.date(LocalDate.of(2026, 9, 25));
        assertPosition(selected, 14, drawn.lengthOfMonth());
    }

    private static void assertPosition(AccessibleNode cell, int position, int size) {
        SelectionItemFacet item = cell.selectionItem();
        assertNotNull(item, cell.name());
        assertEquals(position + " of " + size, item.positionInSet() + " of " + item.sizeOfSet(),
                cell.name());
    }

    // ------------------------------------------------------------ DT3: pins on what was untested

    private static final Clock SEPTEMBER_9 =
            Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);

    private void key(int keyCode, int modifiers) {
        scene.keyEvent(keyCode, true, false, modifiers);
        scene.keyEvent(keyCode, false, false, modifiers);
        scene.inputBatchEnded();
    }

    /** The centre of a published cell, which is where a click on it lands. */
    private void pointerTo(AccessibleNode cell, boolean click) {
        float x = cell.x() + cell.width() / 2;
        float y = cell.y() + cell.height() / 2;
        scene.mouseMoved(x, y);
        if (click) {
            scene.mouseButton(limn.input.Keys.MOUSE_LEFT, true, 0, x, y);
            scene.mouseButton(limn.input.Keys.MOUSE_LEFT, false, 0, x, y);
        }
        scene.inputBatchEnded();
    }

    private List<AccessibleNode> selectedDays() {
        return dayNodes().stream()
                .filter(day -> day.selectionItem() != null && day.selectionItem().selected())
                .toList();
    }

    /**
     * ADR 042 §6: Shift with an arrow extends the band from the anchor without closing the
     * period, and the days it covers say so to a reader while the application still holds no
     * range; Enter closes it. Shift with PageDown extends it by a year the same way, which ADR
     * 042 §4 did not record until this pinned it.
     */
    @Test
    void shiftWithAnArrowPreviewsTheBandFromTheAnchorWithoutClosingIt() {
        CalendarView calendar = bindCalendar();
        calendar.setClock(SEPTEMBER_9);
        calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
        scene.requestFocus(calendar);
        key(limn.input.Keys.ENTER, 0); // the anchor, on today's cell
        key(limn.input.Keys.RIGHT, limn.input.Keys.MOD_SHIFT);
        key(limn.input.Keys.RIGHT, limn.input.Keys.MOD_SHIFT);
        key(limn.input.Keys.RIGHT, limn.input.Keys.MOD_SHIFT);
        frame();
        assertNull(calendar.selectedRange(), "a half-made period is not a period");
        assertEquals(4, selectedDays().size(), "the anchor and the three days the band reached");
        assertEquals(LocalDate.of(2026, 9, 12), calendar.focusedDate());
        key(limn.input.Keys.ENTER, 0);
        assertEquals(new DateRange(ANCHOR, LocalDate.of(2026, 9, 12)), calendar.selectedRange());
        frame();
        assertEquals(4, selectedDays().size(), "and the closed period says the same");

        key(limn.input.Keys.ENTER, 0); // a new anchor, on the 12th
        key(limn.input.Keys.PAGE_DOWN, limn.input.Keys.MOD_SHIFT);
        assertEquals(LocalDate.of(2027, 9, 12), calendar.focusedDate(), "Shift+PageDown is a year");
        assertNull(calendar.selectedRange());
        frame();
        assertTrue(dayNodes().get(0).selectionItem().selected(),
                "and the band it previews runs the whole year back to the anchor");
    }

    /** ADR 042 §6: moving the pointer before the second click previews the band. */
    @Test
    void aPointerMovePreviewsTheBand() {
        CalendarView calendar = bindCalendar();
        calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
        List<AccessibleNode> days = dayNodes();
        pointerTo(days.get(10), true); // 9 September: the first click is the anchor
        assertNull(calendar.selectedRange());
        pointerTo(days.get(12), false); // the pointer over the 11th, nothing pressed
        frame();
        assertEquals(3, selectedDays().size(), "the 9th, 10th and 11th are the band it would make");
        assertNull(calendar.selectedRange(), "and it is still only a preview");
        pointerTo(dayNodes().get(12), true);
        assertEquals(new DateRange(ANCHOR, LocalDate.of(2026, 9, 11)), calendar.selectedRange());
    }

    /** ADR 042 §4: picking a leading or trailing day pages the grid to that day's month. */
    @Test
    void pickingALeadingOrTrailingDayPagesTheGrid() throws InterruptedException {
        CalendarView calendar = bindCalendar();
        AccessibleNode october1 = dayNodes().get(32);
        assertTrue(october1.name().startsWith("1 de outubro de 2026"), october1.name());
        assertTrue(perform(october1.id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        assertEquals(LocalDate.of(2026, 10, 1), calendar.selectedDate());
        assertEquals(LocalDate.of(2026, 10, 1), calendar.visibleMonth(), "the grid paged with it");
        frame();
        assertTrue(dayNodes().get(0).name().startsWith("27 de setembro de 2026"),
                "October's grid, with September's last days leading it: " + dayNodes().get(0).name());
        assertEquals(1, selectedDays().size());
    }

    /**
     * ADR 042 §7: the week number is the language's own convention and not ISO's. The first
     * week of 2027 is week 1 in the United States (Sunday start, one day is enough) and week 53
     * of 2026 in Germany (Monday start, four days needed).
     */
    @Test
    void theWeekNumberIsTheLanguagesOwn() {
        CalendarView calendar = bindCalendar(Locale.US);
        calendar.setShowWeekNumbers(true);
        calendar.setVisibleMonth(LocalDate.of(2027, 1, 5));
        frame();
        assertEquals("1", childrenOf(rowNodes().get(0)).get(0).name());

        calendar = bindCalendar(Locale.GERMANY);
        calendar.setShowWeekNumbers(true);
        calendar.setVisibleMonth(LocalDate.of(2027, 1, 5));
        frame();
        assertEquals("53", childrenOf(rowNodes().get(0)).get(0).name());
    }

    /**
     * The Minguo calendar, which ADR 042 §12 named as verified and no test exercised: the year
     * of the Republic with its era, in the title as in the field.
     */
    @Test
    void aMinguoCalendarNamesTheRepublicsYear() {
        CalendarView calendar = bindCalendar(Locale.forLanguageTag("zh-TW-u-ca-roc"));
        assertEquals("Minguo", calendar.chronology().getId());
        assertTrue(titleNode().name().contains("115"), titleNode().name());
        calendar.setView(CalendarView.View.MONTHS);
        frame();
        assertEquals("民國115", titleNode().name(), "the year of the Republic, with its era");
        calendar.setView(CalendarView.View.YEARS);
        frame();
        assertTrue(dayNodes().stream().map(AccessibleNode::name)
                .anyMatch(name -> name.startsWith("民國115,")),
                "the year on show, with its era and the (Traditional Chinese) word: " + describe(tree()));
    }

    /**
     * The Hijri range's two ends, which ADR 042 §12 named and the fallback test in
     * {@code CalendarViewTest} did not reach (it pointed the grid at 1750, well outside): the
     * first and last months the chronology holds are drawn and named in it, with the leading
     * cells before AH 1300 and the trailing cells past AH 1600 named from their ISO dates, and
     * the month just past either end falls back to the ISO calendar rather than throwing.
     *
     * <p>The week starts on Monday here on purpose: 1 Muharram 1300 is a Sunday, so in the
     * locale's own Sunday-first week it opens the grid and there is no leading cell to check.
     */
    @Test
    void aHijriGridAtTheEndsOfItsRange() {
        CalendarView calendar = bindCalendar(HIJRI);
        java.time.chrono.HijrahChronology hijrah = java.time.chrono.HijrahChronology.INSTANCE;
        LocalDate first = LocalDate.from(hijrah.date(1300, 1, 1));
        LocalDate last = LocalDate.from(hijrah.date(1600, 12, 1));

        assertEquals(java.time.DayOfWeek.SUNDAY, first.getDayOfWeek());
        calendar.setFirstDayOfWeek(java.time.DayOfWeek.MONDAY);
        calendar.setVisibleMonth(first);
        frame();
        assertTrue(titleNode().name().contains("1300"), titleNode().name());
        assertEquals(first, calendar.visibleMonth());
        List<AccessibleNode> days = dayNodes();
        int firstCell = -1;
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i).name().startsWith("Muharram 1, 1300")) {
                firstCell = i;
            }
        }
        assertEquals(6, firstCell, "Monday to Sunday: six leading cells before AH 1300 begins: "
                + describe(tree()));
        AccessibleNode eve = days.get(firstCell - 1);
        assertEquals("1882-11-11", eve.name(),
                "a day the chronology cannot hold is named from its ISO date");
        assertPosition(eve, 11, 30); // and numbered in its ISO month, November's thirty days
        assertEquals("1882-11-06", days.get(0).name());
        assertPosition(days.get(0), 6, 30);
        assertPosition(days.get(firstCell), 1, hijrah.date(1300, 1, 1).lengthOfMonth());

        calendar.setVisibleMonth(last);
        frame();
        assertTrue(titleNode().name().contains("1600"), titleNode().name());
        AccessibleNode trailing = dayNodes().get(41);
        assertTrue(trailing.name().startsWith(LocalDate.from(hijrah.date(1600, 12, 30))
                .plusDays(1).getYear() + "-"),
                "a trailing cell past the range is named from its ISO date: " + trailing.name());

        for (LocalDate outside : List.of(first.minusDays(1), last.plusDays(40))) {
            calendar.setVisibleMonth(outside);
            frame();
            String title = titleNode().name();
            assertTrue(title.contains(String.valueOf(outside.getYear())),
                    "no Hijri month to draw, so the ISO one is: " + title);
        }

        // Paging off either end is ISO's step and lands on the ISO month, rather than a step
        // the chronology refuses.
        calendar.setVisibleMonth(first);
        frame();
        scene.requestFocus(calendar);
        key(limn.input.Keys.PAGE_UP, 0);
        frame();
        assertEquals(LocalDate.of(1882, 10, 1), calendar.visibleMonth(), titleNode().name());
        key(limn.input.Keys.PAGE_DOWN, 0);
        frame();
        assertEquals(first, calendar.visibleMonth(), "and back into the range: " + titleNode().name());
    }

    // ------------------------------------------------ FOCUS on an item (decision 11, positive half)

    private AccessibleNode dayNamed(String prefix) {
        return dayNodes().stream().filter(day -> day.name().startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no cell named " + prefix + " in "
                        + describe(tree())));
    }

    /**
     * Decision 11 (2026-09-13), its positive half: in a calendar the cursor and the selection are
     * two things, so a day publishes {@code FOCUS}, and performing it moves the cursor there and
     * selects nothing -- the calendar takes the focus, the cursor is the day, the day is the
     * tree's active descendant, and no selection or handler moved. A day the bounds refuse
     * carries no verb at all, {@code FOCUS} included (decision 30).
     */
    @Test
    void focusOnADayMovesTheCursorThereAndSelectsNothing() throws InterruptedException {
        CalendarView calendar = bindCalendar();
        calendar.setClock(SEPTEMBER_9);
        calendar.setMinDate(LocalDate.of(2026, 9, 5));
        List<LocalDate> picked = new ArrayList<>();
        calendar.onSelect(picked::add);
        frame();
        AccessibleNode fifteenth = dayNamed("15 de setembro");
        assertTrue(offers(fifteenth, Accessible.Action.FOCUS), describe(tree()));
        assertFalse(offers(dayNamed("3 de setembro"), Accessible.Action.FOCUS),
                "a refused day carries no verb, FOCUS included (decision 30)");

        assertTrue(perform(fifteenth.id(), Accessible.Action.FOCUS, Accessible.Argument.NONE));
        assertEquals(LocalDate.of(2026, 9, 15), calendar.focusedDate(), "the cursor is on the 15th");
        assertTrue(calendar.isFocused(), "and the calendar holds the keyboard it moves with");
        assertNull(calendar.selectedDate(), "FOCUS selects nothing");
        assertTrue(picked.isEmpty(), "and runs no handler");
        frame();
        assertEquals(dayNamed("15 de setembro").id(), tree().activeDescendant(),
                "the day is the focused grid's active descendant: " + describe(tree()));

        // A second one, from a cursor already in the grid, is the same move an arrow makes.
        AccessibleNode twentieth = dayNamed("20 de setembro");
        assertTrue(perform(twentieth.id(), Accessible.Action.FOCUS, Accessible.Argument.NONE));
        assertEquals(LocalDate.of(2026, 9, 20), calendar.focusedDate());
        assertNull(calendar.selectedDate());
        // A refused day's FOCUS, sent anyway, moves nothing: it was never published.
        perform(dayNamed("3 de setembro").id(), Accessible.Action.FOCUS, Accessible.Argument.NONE);
        assertEquals(LocalDate.of(2026, 9, 20), calendar.focusedDate(),
                "the cursor stays where the last published FOCUS put it");
    }

    /**
     * The same verb on a chooser cell moves the chooser's cursor and neither descends nor, in
     * the chooser a month picker picks in, selects: descending and picking are {@code SELECT}.
     */
    @Test
    void focusOnAChooserCellMovesItsCursorWithoutDescendingOrPicking() throws InterruptedException {
        CalendarView calendar = bindCalendar();
        calendar.setView(CalendarView.View.MONTHS);
        frame();
        AccessibleNode march = dayNodes().get(2);
        assertTrue(offers(march, Accessible.Action.FOCUS), describe(tree()));
        assertTrue(perform(march.id(), Accessible.Action.FOCUS, Accessible.Argument.NONE));
        assertEquals(CalendarView.View.MONTHS, calendar.view(), "no descent");
        assertEquals(LocalDate.of(2026, 9, 1), calendar.visibleMonth(), "and no page turned");
        assertTrue(calendar.isFocused());
        frame();
        assertEquals(dayNodes().get(2).id(), tree().activeDescendant(),
                "March holds the chooser's cursor: " + describe(tree()));

        calendar.setGranularity(CalendarView.View.MONTHS);
        calendar.setSelectedDate(LocalDate.of(2026, 9, 15));
        frame();
        LocalDate september = calendar.selectedDate();
        AccessibleNode june = dayNodes().get(5);
        assertTrue(offers(june, Accessible.Action.FOCUS));
        assertTrue(perform(june.id(), Accessible.Action.FOCUS, Accessible.Argument.NONE));
        assertEquals(september, calendar.selectedDate(), "a month picker's selection stays September");
        frame();
        assertEquals(dayNodes().get(5).id(), tree().activeDescendant(), describe(tree()));
        assertTrue(dayNodes().get(8).selectionItem().selected());
        assertFalse(dayNodes().get(5).selectionItem().selected());
    }
}

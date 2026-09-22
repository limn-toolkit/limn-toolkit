package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * The date widgets read right to left; ADR 042 §9.
 *
 * <p>Two opposite answers are asserted here and the pair is the point. <b>The grid mirrors</b>: it
 * is a table of columns in reading order, so the first day of the week is drawn at the edge reading
 * starts from and Left and Right swap with it. <b>The field does not</b>: a date is a run of
 * numbers, and a run of numbers keeps its own left-to-right order inside a right-to-left line,
 * which is the bidirectional algorithm's rule and not a convention this toolkit is free to choose.
 * A sweep over the paint code that "fixed" the second to match the first would be introducing a
 * defect that looks like consistency.
 *
 * <p>Geometry is read from the accessible tree rather than from a screenshot, because the tree's
 * boxes come from the same {@code cellLeft} the paint uses: a cell a reader is given is the cell a
 * click lands in, and asserting one asserts the other.
 */
class DateMirroringTest extends AccessibleComponentTestBase {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 9);

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    private CalendarView bindCalendar(LayoutDirection direction) {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        CalendarView calendar = new CalendarView();
        calendar.setVisibleMonth(ANCHOR);
        calendar.setShowWeekNumbers(true);
        calendar.setLayoutDirection(direction);
        bind(calendar);
        // The deterministic ruler, installed after the bind and before anything is measured: the
        // accessibility base binds a scene without one, and a field whose segments all shape to
        // zero width publishes seven boxes at the same x, which is not a mirroring answer at all.
        scene.setTextRuler(RULER);
        calendar.markNeedsLayout();
        frame();
        return calendar;
    }

    private List<AccessibleNode> columnHeads() {
        AccessibleNode grid = node(Accessible.Role.TABLE);
        AccessibleNode header = childrenOf(grid).stream()
                .filter(child -> child.role() == Accessible.Role.GROUP)
                .findFirst().orElseThrow();
        return childrenOf(header);
    }

    @Test
    void theColumnsRunFromTheEdgeReadingStartsAt() {
        bindCalendar(LayoutDirection.LTR);
        List<AccessibleNode> ltr = new ArrayList<>(columnHeads());
        // Head 0 is the week column, then the seven days in reading order.
        for (int i = 2; i < ltr.size(); i++) {
            assertTrue(ltr.get(i).x() > ltr.get(i - 1).x(),
                    "reading left to right, each column is to the right of the last");
        }

        bindCalendar(LayoutDirection.RTL);
        List<AccessibleNode> rtl = new ArrayList<>(columnHeads());
        for (int i = 2; i < rtl.size(); i++) {
            assertTrue(rtl.get(i).x() < rtl.get(i - 1).x(),
                    "and right to left, to the left of it");
        }
    }

    @Test
    void theWeekColumnMovesToTheEdgeTheWeekStartsAt() {
        bindCalendar(LayoutDirection.LTR);
        List<AccessibleNode> ltr = columnHeads();
        assertTrue(ltr.get(0).x() < ltr.get(1).x(),
                "the week number leads the row reading left to right");

        bindCalendar(LayoutDirection.RTL);
        List<AccessibleNode> rtl = columnHeads();
        assertTrue(rtl.get(0).x() > rtl.get(1).x(), "and trails it on the screen reading the other way");
        assertTrue(rtl.get(0).x() > rtl.get(rtl.size() - 1).x(),
                "which puts it past the last day column");
    }

    @Test
    void theTreeOrderStaysTheReadingOrderInBothDirections() {
        bindCalendar(LayoutDirection.RTL);
        List<AccessibleNode> heads = columnHeads();
        // Sunday first in pt-BR, whichever way the columns are drawn: only the coordinates mirror.
        assertTrue(heads.get(1).name().toLowerCase(Locale.ROOT).startsWith("dom"),
                heads.get(1).name());
    }

    @Test
    void leftAndRightMirrorBecauseTheyNameASideOfTheRow() {
        CalendarView calendar = bindCalendar(LayoutDirection.RTL);
        calendar.setSelectedDate(ANCHOR);
        scene.requestFocus(calendar);
        key(Keys.LEFT);
        assertEquals(ANCHOR.plusDays(1), calendar.focusedDate(),
                "the cell to the left is the NEXT day when the row runs the other way");
        key(Keys.RIGHT);
        assertEquals(ANCHOR, calendar.focusedDate());
    }

    @Test
    void upAndDownAndHomeAndEndDoNotMirror() {
        CalendarView calendar = bindCalendar(LayoutDirection.RTL);
        calendar.setSelectedDate(ANCHOR);
        scene.requestFocus(calendar);
        key(Keys.DOWN);
        assertEquals(ANCHOR.plusWeeks(1), calendar.focusedDate(),
                "a week is below a week in every language");
        key(Keys.UP);
        key(Keys.HOME);
        assertEquals(LocalDate.of(2026, 9, 6), calendar.focusedDate(),
                "Home is the first day of the week and not a side of the row");
    }

    @Test
    void theFieldsSegmentsKeepTheirOrderAndOnlyTheRunMoves() {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        DateField ltr = new DateField();
        ltr.setDate(ANCHOR);
        ltr.setLayoutDirection(LayoutDirection.LTR);
        bind(ltr);
        scene.setTextRuler(RULER);
        ltr.markNeedsLayout();
        frame();
        List<AccessibleNode> left = childrenOf(node(Accessible.Role.GROUP));
        float leftRunStart = left.get(0).x();
        for (int i = 1; i < left.size(); i++) {
            assertTrue(left.get(i).x() > left.get(i - 1).x());
        }

        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        DateField rtl = new DateField();
        rtl.setDate(ANCHOR);
        rtl.setLayoutDirection(LayoutDirection.RTL);
        bind(rtl);
        scene.setTextRuler(RULER);
        rtl.markNeedsLayout();
        frame();
        List<AccessibleNode> right = childrenOf(node(Accessible.Role.GROUP));
        for (int i = 1; i < right.size(); i++) {
            assertTrue(right.get(i).x() > right.get(i - 1).x(),
                    "the day is still left of the month, which is still left of the year: a run of "
                            + "numbers does not turn over inside a right-to-left line");
        }
        assertTrue(right.get(0).x() > leftRunStart,
                "what moves is the run's origin, which sits against the trailing edge");
    }

    private void key(int keyCode) {
        drive(scene).keyEvent(keyCode, true, false, 0);
        drive(scene).keyEvent(keyCode, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
    }

    /**
     * ADR 042 §9: the paging buttons swap ends reading right to left, so "previous" is on the
     * side the reader comes from. Read off the published boxes, which are the paint's.
     */
    @Test
    void thePagingButtonsSwapEndsReadingRightToLeft() {
        bindCalendar(LayoutDirection.LTR);
        List<AccessibleNode> ltr = pagingButtons();
        assertTrue(ltr.get(0).x() < ltr.get(1).x(), "previous leads next, left to right");

        bindCalendar(LayoutDirection.RTL);
        List<AccessibleNode> rtl = pagingButtons();
        assertTrue(rtl.get(0).x() > rtl.get(1).x(), "and trails it on the screen the other way");
    }

    /** The two arrows in tree order, which is reading order: back, then on. */
    private List<AccessibleNode> pagingButtons() {
        List<AccessibleNode> buttons = childrenOf(node(Accessible.Role.TABLE)).stream()
                .filter(child -> child.role() == Accessible.Role.BUTTON).toList();
        assertEquals(3, buttons.size());
        return buttons.subList(0, 2);
    }
}

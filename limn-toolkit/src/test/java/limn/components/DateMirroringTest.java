package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    /**
     * Today, a week after the anchor: in the month on show and not the day the tests select, so
     * the cursor's moves are counted from the selection and not from a today that sits under it.
     */
    private static final Clock SEPTEMBER_16 =
            Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    private CalendarView bindCalendar(LayoutDirection direction) {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        CalendarView calendar = new CalendarView().setClock(SEPTEMBER_16);
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
        DateField ltr = new DateField().setClock(SEPTEMBER_16);
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
        DateField rtl = new DateField().setClock(SEPTEMBER_16);
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

    /**
     * A field bound on its own reading right to left, holding 09/09/2026 under the deterministic
     * ruler, with the caret where a fresh field has it, on the day.
     */
    private DateField bindRightToLeftField() {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        DateField field = new DateField().setClock(SEPTEMBER_16);
        field.setDate(ANCHOR);
        field.setLayoutDirection(LayoutDirection.RTL);
        bind(field);
        scene.setTextRuler(RULER);
        field.markNeedsLayout();
        frame();
        return field;
    }

    /**
     * The month's box in scene coordinates, as its left edge and its width, worked out from the
     * field's own trailing edge and not read off anything the field computed: reading right to
     * left the run ends at the trailing pad, and at the ruler's ten points a code point the year
     * ("2026") and the slash before it are the last fifty points of the run and the month the
     * twenty before those. Where the tree, a click and the paint put the month is each checked
     * against this, so a run placed from the wrong edge moves all of them off it.
     */
    private static float[] monthFromTheTrailingEdge(DateField field) {
        float runEnd = field.localToScene(0, 0).x() + field.width()
                - Theme.current().tokensFor(field).fieldPadH();
        return new float[] {runEnd - 50 - 20, 20};
    }

    /**
     * The click, the third reader of where the run starts: a click on the month of a field read
     * right to left puts the caret on the month. The point is the month's box worked out from the
     * trailing edge, so a click that walked the run from the leading pad lands past its end and
     * takes the last segment, the year.
     */
    @Test
    void aClickOnTheMonthReadingRightToLeftPutsTheCaretOnTheMonth() {
        DateField field = bindRightToLeftField();
        assertEquals(0, field.focusedSegment(), "the caret starts on the day");
        float[] month = monthFromTheTrailingEdge(field);

        drive(scene).click(month[0] + month[1] / 2, field.localToScene(0, 0).y() + field.height() / 2);
        frame();

        assertEquals(1, field.focusedSegment(),
                "a click on the month at " + month[0] + " puts the caret on the month");
        AccessibleNode published = childrenOf(node(Accessible.Role.GROUP)).get(1);
        assertEquals(month[0], published.x(), EPS, "the tree gives the month that same box");
        assertEquals(month[1], published.width(), EPS);
    }

    /**
     * The paint, the fourth: the band behind the caret's segment is painted over the month's box,
     * read off a canvas that records its fills in scene coordinates, reading right to left. It
     * reaches the segment's own padding past each side and no further, so it is the month's band
     * and not one drawn from the leading pad over the day's neighbour.
     */
    @Test
    void theCaretsBandIsPaintedOverTheMonthReadingRightToLeft() {
        DateField field = bindRightToLeftField();
        scene.requestFocus(field);
        key(Keys.RIGHT);
        assertEquals(1, field.focusedSegment(), "Right puts the caret on the month");
        settleAnimations(field); // the focus fade at its end, so the band is at full strength

        FillCanvas painted = new FillCanvas(400, 300);
        field.invalidate();
        scene.renderFrame(painted);
        Color bandInk = Theme.current().primary().withAlpha(0.20f);
        List<RoundRect> bands = painted.fillsIn(bandInk);
        assertEquals(1, bands.size(), "one band, behind the caret's segment: " + painted.fills);
        RoundRect band = bands.get(0);

        SizeTokens t = Theme.current().tokensFor(field);
        float[] month = monthFromTheTrailingEdge(field);
        assertEquals(month[0] - t.spinnerFieldPadX(), band.x(), EPS,
                "the band starts the segment's padding before the month at " + month[0]);
        assertEquals(month[1] + 2 * t.spinnerFieldPadX(), band.width(), EPS,
                "and is the month's width and that padding on each side");
        AccessibleNode published = childrenOf(node(Accessible.Role.GROUP)).get(1);
        assertTrue(band.x() <= published.x()
                        && band.x() + band.width() >= published.x() + published.width(),
                "over the box the tree gives the month, " + published.x() + " wide "
                        + published.width() + ": " + band);
    }

    private static final float EPS = 1e-3f;

    /** Records every rounded fill and its paint in scene coordinates, following the transforms. */
    private static final class FillCanvas extends FakeCanvas {

        record Fill(RoundRect shape, Paint paint) {
        }

        final List<Fill> fills = new ArrayList<>();
        private final Deque<float[]> saved = new ArrayDeque<>();
        private float tx;
        private float ty;

        FillCanvas(float width, float height) {
            super(width, height);
        }

        List<RoundRect> fillsIn(Paint paint) {
            return fills.stream().filter(fill -> fill.paint().equals(paint))
                    .map(Fill::shape).toList();
        }

        @Override
        public void save() {
            super.save();
            saved.push(new float[] {tx, ty});
        }

        @Override
        public void restore() {
            if (!saved.isEmpty()) {
                float[] outer = saved.pop();
                tx = outer[0];
                ty = outer[1];
            }
            super.restore();
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > count && !saved.isEmpty()) {
                restore();
            }
            super.restoreToCount(count);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void fillRoundRect(RoundRect r, Paint paint) {
            fills.add(new Fill(new RoundRect(tx + r.x(), ty + r.y(), r.width(), r.height(),
                    r.topLeft(), r.topRight(), r.bottomRight(), r.bottomLeft()), paint));
        }
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

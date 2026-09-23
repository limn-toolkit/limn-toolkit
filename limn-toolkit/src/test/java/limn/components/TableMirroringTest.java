package limn.components;

import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * {@link Table} read right to left: which edge the first column starts at, where numbers,
 * chevrons, dividers, widget cells and the bars go once the direction has flipped, which keys
 * move the focus cell and the header's cursor which way, and the sites that stay where they are
 * (ADR 041 §5, §10; ADR 032). Every expectation is arithmetic against the box and the tokens,
 * read off a canvas that records where text and lines were drawn, because a screenshot of a
 * mirrored table shows nothing of a divider on the wrong edge.
 *
 * <p>B2 of the 2026-09-13 pass: until 2026-09-14 the only right-to-left case was a header
 * click, and everything below was mirroring code no test had read.
 */
class TableMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float BOX = 300;
    private static final float HEIGHT = 200;
    private static final float COL = 100;
    private static final float STRIP = ScrollBar.thickness();

    record Person(String name, int age) {
    }

    private static List<Person> people(int count) {
        List<Person> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Person("P" + i, 20 + i)); // two characters: nothing ellipsizes
        }
        return list;
    }

    private Table<Person> table;
    private Scene scene;
    private TablePaintCanvas canvas;

    /** Name (start-aligned text), Age (end-aligned number), Open (a 20 by 10 widget). */
    private void buildThree(LayoutDirection direction, int rows) {
        build(direction, rows, List.of(
                Column.text("Name", Person::name).width(COL),
                Column.numeric("Age", Person::age).width(COL),
                Column.<Person>widget("Open", p -> new SizedBox(20, 10)).width(COL)));
    }

    /** A widget column then four text columns, 120 points each: 600 of columns in a 300 box. */
    private void buildWide(LayoutDirection direction, int rows) {
        List<Column<Person>> columns = new ArrayList<>();
        columns.add(Column.<Person>widget("Open", p -> new SizedBox(20, 10)).width(120));
        for (int c = 1; c < 5; c++) {
            columns.add(Column.text("C" + c, Person::name).width(120));
        }
        build(direction, rows, columns);
    }

    private void build(LayoutDirection direction, int rows, List<Column<Person>> columns) {
        table = new Table<>(columns);
        table.setRows(people(rows));
        table.setLayoutDirection(direction);
        scene = new Scene(table);
        scene.setTextRuler(RULER);
        canvas = new TablePaintCanvas(BOX, HEIGHT);
        frame();
    }

    private void frame() {
        canvas.reset();
        scene.renderFrame(canvas);
    }

    private SizeTokens tokens() {
        return Theme.current().tokensFor(table);
    }

    private float headerHeight() {
        return tokens().controlHeight();
    }

    /** The x of the first widget cell: every row's sits at the same x. */
    private float widgetX() {
        for (Widget<?> child : table.children()) {
            if (child instanceof SizedBox) {
                return child.x();
            }
        }
        throw new AssertionError("no widget cell mounted");
    }

    private ScrollBar bar(boolean vertical) {
        for (Widget<?> child : table.children()) {
            if (child instanceof ScrollBar found && (found.height() > found.width()) == vertical) {
                return found;
            }
        }
        throw new AssertionError("no such bar");
    }

    private static void key(Scene scene, int key, int modifiers) {
        drive(scene).keyEvent(key, true, false, modifiers);
        drive(scene).inputBatchEnded();
    }

    private void drag(float fromX, float toX) {
        float y = headerHeight() / 2;
        drive(scene).mouseMoved(fromX, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, fromX, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseMoved(toX, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, toX, y);
        drive(scene).inputBatchEnded();
    }

    // ------------------------------------------------------------- where the columns sit

    @Test
    void theFirstColumnIsAtTheRightEdgeAndAWidgetCellAtItsColumnsRightPadding() {
        buildThree(LayoutDirection.RTL, 5);
        float padH = tokens().padH();
        assertEquals(BOX - padH - 40, canvas.text("Name").x(), EPS,
                "the first header ends at the box's right padding: " + canvas.texts());
        assertEquals(BOX - padH - 20, canvas.text("P0").x(), EPS,
                "and so does the first column's text");
        assertEquals(COL - padH - 20, widgetX(), EPS,
                "the third column is leftmost, and its widget sits at that cell's right padding");

        buildThree(LayoutDirection.LTR, 5);
        assertEquals(padH, canvas.text("Name").x(), EPS, "the default must not have moved");
        assertEquals(2 * COL + padH, widgetX(), EPS);
    }

    @Test
    void numbersAlignToTheEndWhichIsTheLeftReadingRightToLeft() {
        buildThree(LayoutDirection.RTL, 5);
        float padH = tokens().padH();
        assertEquals(COL + padH, canvas.text("20").x(), EPS,
                "the end of the second column is its left padding: " + canvas.texts());
        assertEquals(COL + padH, canvas.text("Age").x(), EPS, "and its header follows the cells");

        buildThree(LayoutDirection.LTR, 5);
        assertEquals(2 * COL - padH - 20, canvas.text("20").x(), EPS,
                "reading left to right the end is the right padding");
    }

    // --------------------------------------------------------------- the horizontal axis

    @Test
    void scrollingTowardTheLaterColumnsBringsThemInFromTheLeft() {
        buildWide(LayoutDirection.RTL, 5);
        float padH = tokens().padH();
        float at = widgetX();
        assertEquals(BOX - padH - 20, at, EPS, "the widget column is first, at the right edge");
        assertFalse(canvas.drew("C4"), "the last column hangs off the left: " + canvas.texts());

        table.scrollBy(100, 0);
        frame();
        assertEquals(at + 100, widgetX(), EPS, "the columns moved right, off the edge they start at");

        table.scrollBy(1000, 0);
        frame();
        assertEquals(120 - padH - 20, canvas.text("C4").x(), EPS,
                "clamped at the end, the last column is at the left edge with its title at the "
                        + "cell's right padding: " + canvas.texts());

        // revealRect: a rectangle hanging off the left asks for the later columns.
        table.scrollBy(-1000, 0);
        frame();
        at = widgetX();
        table.revealRect(-50, headerHeight() + 5, 40, 10);
        frame();
        assertEquals(at + 50, widgetX(), EPS, "revealed by scrolling toward the later columns");
    }

    @Test
    void aSidewaysWheelIsNotFlipped() {
        // The gesture is the device's and the axis is logical: a leftward swipe reaches the
        // later columns in both directions, as it does in a scroll view. Pinned per axis, and
        // not as "the same as ScrollView": the vertical half is a separate detent.
        buildWide(LayoutDirection.LTR, 5);
        float at = widgetX();
        drive(scene).scrolled(-1, 0, 50, 100);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals(at - Strokes.WHEEL_STEP, widgetX(), EPS, "left to right: the first column moves left");

        buildWide(LayoutDirection.RTL, 5);
        at = widgetX();
        drive(scene).scrolled(-1, 0, 50, 100);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals(at + Strokes.WHEEL_STEP, widgetX(), EPS,
                "right to left the same detent moves the first column right: later columns either way");
    }

    // ------------------------------------------------------------------------- the keys

    @Test
    void leftRaisesTheFocusColumnAndRightLowersItReadingRightToLeft() {
        buildThree(LayoutDirection.RTL, 5);
        scene.requestFocus(table);
        table.setSelectedRow(0);
        key(scene, Keys.LEFT, 0);
        assertEquals(1, table.focusColumn(), "Left walks toward the later columns, which are leftward");
        key(scene, Keys.LEFT, 0);
        assertEquals(2, table.focusColumn());
        key(scene, Keys.RIGHT, 0);
        assertEquals(1, table.focusColumn(), "and Right walks back");

        // The header's column cursor mirrors the same way (decision 36).
        key(scene, Keys.TAB, Keys.MOD_SHIFT);
        assertTrue(table.isHeaderFocused(), "Shift+Tab from the rows enters the header");
        key(scene, Keys.LEFT, 0);
        assertEquals(1, table.headerColumn());
        key(scene, Keys.RIGHT, 0);
        assertEquals(0, table.headerColumn());

        buildThree(LayoutDirection.LTR, 5);
        scene.requestFocus(table);
        table.setSelectedRow(0);
        key(scene, Keys.RIGHT, 0);
        assertEquals(1, table.focusColumn(), "the default must not have moved");
    }

    // ------------------------------------------------------------- the header's marks

    @Test
    void theHeaderChevronIsOnTheLeftAndTheTitleAfterItReadingRightToLeft() {
        buildThree(LayoutDirection.RTL, 5);
        Column<Person> name = table.columns().get(0);
        table.setSort(name, SortOrder.ASCENDING);
        frame();
        SizeTokens t = tokens();
        float half = t.chevronHalfW();
        float left = BOX - COL;
        float cx = left + t.padH() + half;
        List<TablePaintCanvas.Line> chevron = new ArrayList<>();
        for (TablePaintCanvas.Line line : canvas.lines()) {
            if (line.width() == Strokes.ARROW_PEN) {
                chevron.add(line);
            }
        }
        assertEquals(2, chevron.size(), "two strokes of one chevron: " + canvas.lines());
        for (TablePaintCanvas.Line line : chevron) {
            assertTrue(line.x1() >= cx - half - EPS && line.x2() <= cx + half + EPS
                    && line.x2() >= cx - half - EPS && line.x1() <= cx + half + EPS,
                    "the chevron sits at the column's left padding: " + line);
        }
        float title = canvas.text("Name").x();
        assertTrue(title >= cx + half, "and the title reads after it, to its right: " + title);
        assertEquals(BOX - t.padH() - 40, title, EPS, "at the cell's right padding");

        buildThree(LayoutDirection.LTR, 5);
        table.setSort(table.columns().get(0), SortOrder.ASCENDING);
        frame();
        float cxLtr = COL - t.padH() - half;
        for (TablePaintCanvas.Line line : canvas.lines()) {
            if (line.width() == Strokes.ARROW_PEN) {
                assertTrue(line.x1() >= cxLtr - half - EPS && line.x2() <= cxLtr + half + EPS,
                        "left to right the chevron is at the right padding: " + line);
            }
        }
        assertEquals(t.padH(), canvas.text("Name").x(), EPS, "and the title before it");
    }

    @Test
    void theDividerIsAtTheColumnsLeftEdgeAndALeftwardDragWidensItReadingRightToLeft() {
        buildThree(LayoutDirection.RTL, 5);
        List<Float> edges = new ArrayList<>();
        for (TablePaintCanvas.Line line : canvas.verticalLines()) {
            if (line.width() == Strokes.HAIRLINE) {
                edges.add(line.x1());
            }
        }
        assertEquals(List.of(2 * COL, COL, 0f), edges,
                "each column's divider is at its left edge, first column first: " + canvas.lines());

        Column<Person> name = table.columns().get(0);
        drag(2 * COL, 2 * COL - 40); // the first column's divider, dragged 40 points leftward
        assertEquals(COL + 40, name.width(), EPS, "a leftward drag widens the column");
        frame();
        assertEquals(COL + 40, table.widthOf(name), EPS);

        buildThree(LayoutDirection.LTR, 5);
        edges.clear();
        for (TablePaintCanvas.Line line : canvas.verticalLines()) {
            if (line.width() == Strokes.HAIRLINE) {
                edges.add(line.x1());
            }
        }
        assertEquals(List.of(COL, 2 * COL, 3 * COL), edges, "left to right, at the right edges");
        drag(COL, COL + 40);
        assertEquals(COL + 40, table.columns().get(0).width(), EPS, "a rightward drag widens it");
    }

    // ------------------------------------------------------------------------- the bars

    @Test
    void theBarsAreOnTheLeftAndNoRowOverlapsTheReservedStrip() {
        buildWide(LayoutDirection.RTL, 40);
        table.setBarLayout(ScrollGutters.Layout.RESERVED)
                .setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
        frame();
        ScrollBar vertical = bar(true);
        ScrollBar horizontal = bar(false);
        assertEquals(0, vertical.x(), EPS, "the vertical bar is on the trailing side, the left");
        assertEquals(STRIP, vertical.width(), EPS);
        assertEquals(STRIP, horizontal.x(), EPS, "the horizontal bar starts past the strip");
        assertEquals(BOX - STRIP, horizontal.width(), EPS);
        assertEquals(HEIGHT - STRIP, horizontal.y(), EPS);
        float padH = tokens().padH();
        assertEquals(BOX - padH - 20, widgetX(), EPS, "the first column still ends at the right edge");
        assertTrue(widgetX() >= STRIP, "and no widget cell lies under the strip");
        float title = canvas.text("C1").x();
        assertEquals(BOX - 120 - padH - 20, title, EPS,
                "the second column's title ends at its cell's right padding: " + canvas.texts());

        // At the end of the sideways scroll the last column's left edge is the strip's edge,
        // not the box's: the one place where a paint, a hit test or a clip that forgot the
        // strip would put a cell under it. Every assertion above holds either way, because the
        // columns start at the right edge, away from the strip (review of table-B, 2026-09-14).
        table.scrollBy(1000, 0);
        frame();
        assertEquals(STRIP + 120 - padH - 20, canvas.text("C4").x(), EPS,
                "scrolled to the end, the last title sits at its cell's right padding, the cell "
                        + "starting past the strip: " + canvas.texts());
        List<Float> edges = new ArrayList<>();
        for (TablePaintCanvas.Line line : canvas.verticalLines()) {
            if (line.width() == Strokes.HAIRLINE) {
                edges.add(line.x1());
                assertTrue(line.x1() >= STRIP - EPS, "no divider under the strip: " + line);
            }
        }
        assertTrue(edges.contains(STRIP), "the last column's divider is at the strip's edge: " + edges);
        float rowY = headerHeight() + 2;
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, STRIP + 118, rowY);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, STRIP + 118, rowY);
        drive(scene).inputBatchEnded();
        assertEquals(4, table.focusColumn(),
                "a click just inside the last column's right edge lands on the last column");

        buildWide(LayoutDirection.LTR, 40);
        table.setBarLayout(ScrollGutters.Layout.RESERVED)
                .setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
        frame();
        assertEquals(BOX - STRIP, bar(true).x(), EPS, "the default must not have moved");
        assertEquals(0, bar(false).x(), EPS);
        table.scrollBy(1000, 0);
        frame();
        assertEquals(BOX - STRIP - 120 + tokens().padH(), canvas.text("C4").x(), EPS,
                "left to right the end of the scroll puts the last column's right edge at the "
                        + "strip: " + canvas.texts());
        for (TablePaintCanvas.Line line : canvas.verticalLines()) {
            if (line.width() == Strokes.HAIRLINE) {
                assertTrue(line.x1() <= BOX - STRIP + EPS, "no divider under the strip: " + line);
            }
        }
    }
}

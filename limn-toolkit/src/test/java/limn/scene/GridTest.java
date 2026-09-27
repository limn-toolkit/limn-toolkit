package limn.scene;

import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Grid;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What a {@link Grid} does with the children it is given: which cell each one lands in, how wide
 * a column and how tall a row is, where the leftover width goes, and how a cell is placed within
 * its column and its row. The direction's half is in {@code ContainerMirroringTest}, the measure
 * count in {@code GridMeasureCountTest}, and what a reader is handed in
 * {@code GridAccessibilityTest}.
 */
class GridTest extends SceneTestBase {

    private static final float EPS = 1e-3f;

    /** A leaf whose text baseline sits where it is told, the way a control carrying text does. */
    private static final class Ascent extends Widget<Ascent> {
        private final float prefWidth;
        private final float prefHeight;
        private final float baseline;

        Ascent(float prefWidth, float prefHeight, float baseline) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
            this.baseline = baseline;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }

        @Override
        protected float baselineOffset() {
            return baseline;
        }
    }

    /**
     * A leaf that is taller the narrower it is given, the way a wrapped paragraph is: it wants its
     * whole text on one line, and every line it has to break into is one more line tall.
     */
    private static final class Flow extends Widget<Flow> {
        private final float length;
        private final float line;

        Flow(float length, float line) {
            this.length = length;
            this.line = line;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            float width = constraints.constrainWidth(length);
            float lines = width <= 0 ? 1 : (float) Math.ceil(length / width);
            return constraints.constrain(width, lines * line);
        }
    }

    private static Grid grid(int columns, Widget<?>... children) {
        Grid grid = new Grid(columns);
        for (Widget<?> child : children) {
            grid.add(child);
        }
        return grid;
    }

    /** Measures under a loose bound and lays out at the answer, as a column's START child is. */
    private static Size layoutLoose(Widget<?> widget, float width, float height) {
        Size size = widget.measure(Constraints.loose(width, height));
        widget.layoutBox(0, 0, size.width(), size.height());
        return size;
    }

    private static void layoutTight(Widget<?> widget, float width, float height) {
        widget.measure(Constraints.tight(width, height));
        widget.layoutBox(0, 0, width, height);
    }

    private static Constraints unbounded() {
        return new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0, Constraints.UNBOUNDED_LIMIT);
    }

    // ------------------------------------------------------------------ cells, columns and rows

    /**
     * Seven children in three columns: columns of 50, 60 and 70 from the widest cell of each,
     * which is a different row's cell in every column, and rows of 20, 30 and 8 from the tallest
     * cell of each.
     */
    @Test
    void childrenFillTheRowsInTheOrderTheyWereAddedAndEachTrackIsItsLargestCell() {
        FixedBox[] boxes = {
                new FixedBox(40, 20), new FixedBox(60, 10), new FixedBox(30, 15),
                new FixedBox(50, 12), new FixedBox(20, 30), new FixedBox(70, 10),
                new FixedBox(10, 8)};
        Grid grid = grid(3, boxes).gap(10).rowGap(5).crossAlignment(Flex.CrossAlignment.START);

        Size size = layoutLoose(grid, 300, 300);

        float[][] expected = {{0, 0}, {60, 0}, {130, 0}, {0, 25}, {60, 25}, {130, 25}, {0, 60}};
        for (int i = 0; i < boxes.length; i++) {
            assertEquals(expected[i][0], boxes[i].x(), EPS, "child " + i + "'s column");
            assertEquals(expected[i][1], boxes[i].y(), EPS, "child " + i + "'s row");
        }
        assertEquals(40, boxes[0].width(), EPS, "a cell keeps its own width inside a wider column");
        assertEquals(50 + 10 + 60 + 10 + 70, size.width(), EPS, "the columns and two gaps, not "
                + "three columns of the widest cell");
        assertEquals(20 + 5 + 30 + 5 + 8, size.height(), EPS, "the rows and two row gaps");
    }

    @Test
    void withNoBoundOnTheWidthTheGridIsItsColumnsAndGapsEvenWithAnExpandedColumn() {
        Grid grid = grid(2, new FixedBox(50, 20), new FixedBox(120, 30), new FixedBox(80, 10),
                new FixedBox(90, 10)).gap(12).rowGap(4);
        assertEquals(new Size(80 + 12 + 120, 30 + 4 + 10), grid.measure(unbounded()));

        grid.expandedColumn(1).columnAlignment(1, Flex.CrossAlignment.STRETCH);
        assertEquals(new Size(80 + 12 + 120, 30 + 4 + 10), grid.measure(unbounded()),
                "with nothing to fill, the expanded column is as wide as its widest cell");
    }

    @Test
    void aChildWiderThanTheGridIsGivenAtMostItsWidth() {
        FixedBox wide = new FixedBox(300, 20);
        Grid grid = grid(1, wide, new FixedBox(40, 20));
        Size size = layoutLoose(grid, 200, 100);
        assertEquals(200, wide.width(), EPS, "measured against the grid's width");
        assertEquals(200, size.width(), EPS);
    }

    @Test
    void insertingAChildMovesEveryLaterOneACellOn() {
        FixedBox a = new FixedBox(40, 20);
        FixedBox b = new FixedBox(40, 20);
        FixedBox c = new FixedBox(40, 20);
        Grid grid = grid(2, a, b, c).gap(10);
        FixedBox first = new FixedBox(40, 20);
        grid.add(0, first);

        layoutLoose(grid, 200, 100);

        assertEquals(0, first.x(), EPS);
        assertEquals(50, a.x(), EPS, "the first child is now the second cell");
        assertEquals(0, b.x(), EPS);
        assertEquals(20, b.y(), EPS, "and the second opens the next row");
        assertEquals(50, c.x(), EPS);
    }

    /**
     * A child's cell is its index: hiding one leaves its cell empty and moves nothing after it
     * into the wrong column, and a row or a column with nothing visible in it is gone, gaps and
     * all.
     */
    @Test
    void aHiddenChildKeepsItsCellAndAnEmptyRowOrColumnTakesNoRoom() {
        FixedBox[] cells = new FixedBox[9];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new FixedBox(30 + 10 * (i % 3), 20);
        }
        Grid grid = grid(3, cells).gap(10).rowGap(5);
        layoutLoose(grid, 300, 300);
        assertEquals(140, grid.width(), EPS, "30, 40 and 50 with two gaps");

        cells[3].setVisible(false);
        Size size = layoutLoose(grid, 300, 300);
        assertEquals(40, cells[4].x(), EPS, "the cell after a hidden one stays in its column");
        assertEquals(25, cells[4].y(), EPS, "and in its row");
        assertEquals(20 + 5 + 20 + 5 + 20, size.height(), EPS);

        cells[4].setVisible(false);
        cells[5].setVisible(false);
        size = layoutLoose(grid, 300, 300);
        assertEquals(25, cells[6].y(), EPS,
                "a row with nothing visible takes no height and no gap");
        assertEquals(20 + 5 + 20, size.height(), EPS);

        cells[1].setVisible(false);
        cells[7].setVisible(false);
        size = layoutLoose(grid, 300, 300);
        assertEquals(40, cells[8].x(), EPS, "a column with nothing visible takes no width and one "
                + "gap, not two");
        assertEquals(30 + 10 + 50, size.width(), EPS);
    }

    @Test
    void aGridWithNothingVisibleIsEmpty() {
        FixedBox only = new FixedBox(80, 20);
        Grid grid = grid(2, only).gap(10).rowGap(8).expandedColumn(0);
        only.setVisible(false);
        assertEquals(Size.ZERO, grid.measure(Constraints.loose(200, 100)));
        assertEquals(Size.ZERO, new Grid(3).measure(Constraints.loose(200, 100)));
    }

    // ------------------------------------------------------------------ the expanded column

    /**
     * A caption of 50 and a field of 80 with a gap of 10: given 300, the field's column is the
     * 240 the caption and the gap leave; given 100 it is 40 and the field is squeezed into it;
     * given 50 it is nothing, and the caption's column has still not given up a point.
     */
    @Test
    void theExpandedColumnIsWhatTheOthersAndTheGapsLeaveDownToNothing() {
        FixedBox caption = new FixedBox(50, 20);
        FixedBox field = new FixedBox(80, 20);
        Grid grid = grid(2, caption, field).gap(10).expandedColumn(1);

        Size size = layoutLoose(grid, 300, 100);
        assertEquals(300, size.width(), EPS,
                "given a width, a grid with an expanded column fills it");
        assertEquals(60, field.x(), EPS);
        assertEquals(80, field.width(), EPS, "START leaves the field its own width in the column");

        grid.columnAlignment(1, Flex.CrossAlignment.STRETCH);
        layoutLoose(grid, 300, 100);
        assertEquals(240, field.width(), EPS, "and STRETCH makes it the column's");

        grid.columnAlignment(1, Flex.CrossAlignment.START);
        layoutTight(grid, 100, 20);
        assertEquals(40, field.width(), EPS, "narrower than its cell: the field is given at most "
                + "the column");
        assertEquals(50, caption.width(), EPS);

        layoutTight(grid, 50, 20);
        assertEquals(0, field.width(), EPS, "the expanded column gives way first, down to nothing");
        assertEquals(60, field.x(), EPS);
        assertEquals(50, caption.width(), EPS, "and no other column shrinks");
    }

    @Test
    void theExpandedColumnsCellsAreAskedAtItsWidthSoTheirRowIsAsTallAsTheyAreThere() {
        Flow paragraph = new Flow(300, 10);
        Grid grid = grid(2, new FixedBox(40, 10), paragraph).gap(10).expandedColumn(1)
                .columnAlignment(1, Flex.CrossAlignment.STRETCH);

        Size size = layoutLoose(grid, 150, 200);

        assertEquals(100, paragraph.width(), EPS);
        assertEquals(30, paragraph.height(), EPS, "300 of text in 100 is three lines");
        assertEquals(30, size.height(), EPS, "and the row is as tall as the three lines");
    }

    @Test
    void anExpandedColumnWithNothingVisibleLeavesTheGridItsColumnsWidth() {
        FixedBox field = new FixedBox(80, 20);
        Grid grid = grid(2, new FixedBox(50, 20), field).gap(10).expandedColumn(1);
        field.setVisible(false);
        assertEquals(50, layoutLoose(grid, 300, 100).width(), EPS);

        field.setVisible(true);
        grid.expandedColumn(-1);
        assertEquals(140, layoutLoose(grid, 300, 100).width(), EPS, "-1 is no expanded column");
    }

    /**
     * The natural width is the columns summed, and a grid laid out at exactly that width must find
     * the columns it summed. These widths are chosen because {@code (0.1 + 7.1) + 0.3}, less
     * {@code 0.1 + 7.1} again, is not {@code 0.3} in floats, so a grid that sized its expanded
     * column by subtracting at layout would make that column one float wider than its widest
     * cell, and stretch the cell to it.
     */
    @Test
    void aGridLaidOutAtItsNaturalWidthFindsTheColumnsItMeasured() {
        FixedBox caption = new FixedBox(0.1f, 20);
        FixedBox field = new FixedBox(0.3f, 20);
        Grid grid = grid(2, caption, field).gap(7.1f).expandedColumn(1)
                .columnAlignment(1, Flex.CrossAlignment.STRETCH);
        Row row = new Row(); // a row asks a child that is not flexible with no bound on the width
        row.add(grid);

        Size natural = row.measure(unbounded());
        row.layoutBox(0, 0, natural.width(), natural.height());

        assertEquals(natural.width(), grid.width(), "laid out at exactly its natural width");
        assertEquals(0.3f, field.width(), "the expanded column is its widest cell, to the bit");
    }

    // ------------------------------------------------------------------ placement within a column

    @Test
    void columnAlignmentPlacesACellWithinItsColumnsWidth() {
        FixedBox narrow = new FixedBox(40, 20);
        FixedBox wide = new FixedBox(100, 20);
        Grid grid = grid(1, narrow, wide);

        layoutLoose(grid, 300, 100);
        assertEquals(0, narrow.x(), EPS, "START by default");
        assertEquals(40, narrow.width(), EPS);

        grid.columnAlignment(0, Flex.CrossAlignment.CENTER);
        layoutLoose(grid, 300, 100);
        assertEquals(30, narrow.x(), EPS);

        grid.columnAlignment(0, Flex.CrossAlignment.END);
        layoutLoose(grid, 300, 100);
        assertEquals(60, narrow.x(), EPS);

        grid.columnAlignment(0, Flex.CrossAlignment.STRETCH);
        layoutLoose(grid, 300, 100);
        assertEquals(0, narrow.x(), EPS);
        assertEquals(100, narrow.width(), EPS, "as wide as the column, and no wider");
        assertEquals(100, grid.width(), EPS, "stretching changes no column's width");

        grid.columnAlignment(0, Flex.CrossAlignment.BASELINE);
        layoutLoose(grid, 300, 100);
        assertEquals(0, narrow.x(), EPS, "BASELINE has no meaning across and places as START");
        assertEquals(40, narrow.width(), EPS);
    }

    @Test
    void eachColumnHasItsOwnAlignment() {
        FixedBox caption = new FixedBox(30, 20);
        FixedBox longCaption = new FixedBox(70, 20);
        FixedBox field = new FixedBox(50, 20);
        FixedBox longField = new FixedBox(90, 20);
        Grid grid = grid(2, caption, field, longCaption, longField).gap(10)
                .columnAlignment(0, Flex.CrossAlignment.END)
                .columnAlignment(1, Flex.CrossAlignment.STRETCH);

        layoutLoose(grid, 300, 100);

        assertEquals(40, caption.x(), EPS,
                "captions against the fields, the way a sheet sets them");
        assertEquals(80, field.x(), EPS);
        assertEquals(90, field.width(), EPS, "fields as wide as the widest of them");
    }

    // ------------------------------------------------------------------ placement within a row

    /**
     * A caption whose text sits 13 down a 17-point box beside a field whose text sits 20 down a
     * 32-point box: with no alignment asked for, the caption moves 7 down onto the field's line,
     * where CENTER would put it at 7.5 and START at 0.
     */
    @Test
    void baselineIsTheDefaultAndLinesUpTheTextOfEachRowInsideIt() {
        Ascent caption = new Ascent(60, 17, 13);
        Ascent field = new Ascent(120, 32, 20);
        FixedBox swatch = new FixedBox(30, 10);
        Ascent box = new Ascent(40, 20, 20);
        Ascent low = new Ascent(40, 20, 18);
        Ascent high = new Ascent(40, 20, 2);
        Grid grid = grid(2, caption, field, swatch, box, low, high).gap(10).rowGap(4);

        layoutLoose(grid, 300, 300);

        assertEquals(7, caption.y(), EPS, "moved down onto the field's baseline, 20 - 13");
        assertEquals(0, field.y(), EPS);
        assertEquals(36 + 10, swatch.y(), EPS, "a cell with no text lines up its bottom edge, "
                + "20 - 10, in a row that starts below the first row's 32 and the gap");
        assertEquals(36, box.y(), EPS);
        // The third row is 20 tall and starts at 36 + 20 + 4. Its deepest baseline is low's 18;
        // high wants 16 down and has no room inside its row.
        assertEquals(60, low.y(), EPS);
        assertEquals(60, high.y(), EPS, "clamped inside its row instead of into the next");
    }

    /** Two rows, of 30 and 16 with a row gap of 4: each cell is placed within its own row. */
    @Test
    void crossAlignmentPlacesACellWithinItsRowsHeight() {
        FixedBox shortOne = new FixedBox(40, 20);
        FixedBox tall = new FixedBox(40, 30);
        FixedBox next = new FixedBox(40, 10);
        FixedBox beside = new FixedBox(40, 16);
        Grid grid = grid(2, shortOne, tall, next, beside).gap(10).rowGap(4);

        grid.crossAlignment(Flex.CrossAlignment.START);
        layoutLoose(grid, 200, 200);
        assertEquals(0, shortOne.y(), EPS);
        assertEquals(34, next.y(), EPS, "the second row starts below the first's 30 and the gap");

        grid.crossAlignment(Flex.CrossAlignment.CENTER);
        layoutLoose(grid, 200, 200);
        assertEquals(5, shortOne.y(), EPS, "centred in the 30 of its row");
        assertEquals(34 + 3, next.y(), EPS, "and in the 16 of the second, not the grid's 50");

        grid.crossAlignment(Flex.CrossAlignment.END);
        layoutLoose(grid, 200, 200);
        assertEquals(10, shortOne.y(), EPS);
        assertEquals(34 + 6, next.y(), EPS);

        grid.crossAlignment(Flex.CrossAlignment.STRETCH);
        Size size = layoutLoose(grid, 200, 200);
        assertEquals(0, shortOne.y(), EPS);
        assertEquals(30, shortOne.height(), EPS, "as tall as the row");
        assertEquals(40, shortOne.width(), EPS, "and no wider than it was");
        assertEquals(16, next.height(), EPS, "as tall as its own row, not the first");
        assertEquals(30 + 4 + 16, size.height(), EPS, "stretching changes no row's height");
    }

    // ------------------------------------------------------------------ what it refuses

    @Test
    void anExpandedIsRefusedWhenItIsAdded() {
        Grid grid = new Grid(2);
        assertThrows(IllegalArgumentException.class,
                () -> grid.add(Expanded.of(new FixedBox(10, 10))));
        assertThrows(IllegalArgumentException.class, () -> grid.add(Expanded.spacer(1)));
        assertThrows(IllegalArgumentException.class,
                () -> grid.add(0, Expanded.of(new FixedBox(10, 10))));
        assertEquals(0, grid.children().size(), "and nothing was added");
        grid.add(new FixedBox(10, 10));
        grid.add(0, new SizedBox(0, 0));
        assertEquals(2, grid.children().size(), "anything else is, an empty cell included");
    }

    @Test
    void theSettersClampANegativeGapAndRefuseWhatIsNotANumberOrNotAColumn() {
        assertThrows(IllegalArgumentException.class, () -> new Grid(0));

        FixedBox a = new FixedBox(80, 20);
        FixedBox b = new FixedBox(80, 20);
        FixedBox c = new FixedBox(80, 20);
        Grid grid = grid(2, a, b, c).gap(-5).rowGap(-3);
        layoutLoose(grid, 300, 100);
        assertEquals(80, b.x(), EPS, "a negative gap is none");
        assertEquals(20, c.y(), EPS, "and so is a negative row gap");

        assertThrows(IllegalArgumentException.class, () -> grid.gap(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> grid.rowGap(Float.POSITIVE_INFINITY));
        assertThrows(NullPointerException.class, () -> grid.crossAlignment(null));
        assertThrows(NullPointerException.class, () -> grid.columnAlignment(0, null));
        assertThrows(IndexOutOfBoundsException.class,
                () -> grid.columnAlignment(2, Flex.CrossAlignment.END));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.expandedColumn(2));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.expandedColumn(-2));
        layoutLoose(grid, 300, 100);
        assertEquals(80, b.x(), EPS, "a refused value changed nothing");
        assertEquals(160, grid.width(), EPS, "no column was expanded by a refused index");
    }

    /** A setter given the value the grid already holds asks for no layout, so no measure runs. */
    @Test
    void aSetterGivenTheValueItHoldsAsksForNoLayout() {
        int[] measures = {0};
        Grid grid = new Grid(2) {
            @Override
            protected Size onMeasure(Constraints constraints) {
                measures[0]++;
                return super.onMeasure(constraints);
            }
        };
        grid.add(new FixedBox(40, 20));
        grid.add(new FixedBox(40, 20));
        grid.gap(8).rowGap(4).crossAlignment(Flex.CrossAlignment.END)
                .columnAlignment(1, Flex.CrossAlignment.STRETCH).expandedColumn(1);
        Constraints loose = Constraints.loose(200, 100);
        grid.measure(loose);
        assertEquals(1, measures[0]);

        grid.gap(8).rowGap(4).crossAlignment(Flex.CrossAlignment.END)
                .columnAlignment(1, Flex.CrossAlignment.STRETCH).expandedColumn(1);
        grid.measure(loose);
        assertEquals(1, measures[0], "nothing changed, so the grid answered from its cache");

        for (Runnable change : new Runnable[] {
                () -> grid.gap(9), () -> grid.rowGap(5),
                () -> grid.crossAlignment(Flex.CrossAlignment.START),
                () -> grid.columnAlignment(1, Flex.CrossAlignment.END),
                () -> grid.expandedColumn(0), () -> grid.expandedColumn(-1)}) {
            int before = measures[0];
            change.run();
            grid.measure(loose);
            assertEquals(before + 1, measures[0], "and each real change measures again");
        }
    }
}

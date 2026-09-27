package limn.scene.layout;

import limn.concurrent.Ui;
import limn.internal.lang.Checks;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

import java.util.Arrays;
import java.util.Objects;

/**
 * Places children in a fixed number of columns, row by row in the order they were added, so that
 * they line up on both axes: the layout for a form of captions beside fields, a sheet of names
 * beside values, or any set of rows whose cells should share their columns' edges.
 *
 * <p>The first {@code columns} children fill the first row, the next {@code columns} the second,
 * and the last row may be short. A child's cell is decided by its index and by nothing else, so a
 * hidden child leaves its cell empty rather than moving every later child one cell back into the
 * wrong column; a row or a column whose every cell is hidden takes no room and no gap. An empty
 * cell on purpose, such as the caption's cell beside a checkbox that carries its own text, is a
 * {@link SizedBox} of no size.
 *
 * <p><b>Columns.</b> Each column is as wide as the widest of its cells, and {@link #gap} separates
 * one column from the next. One column may take the width the others leave: the
 * {@link #expandedColumn}, which is given whatever the other columns and the gaps leave of the
 * grid's width, more than its cells ask for when the grid has more and less when it has less,
 * down to nothing, the way an {@link Expanded} child is given what a row leaves over. The other
 * columns never shrink, so a grid narrower than them runs past its edge, as a row of children
 * that are not flexible does. {@link #columnAlignment} places a cell within its column's width,
 * with the words a {@link Column} uses across: at the edge reading starts from by default, and
 * {@code STRETCH} for a column of fields that should all be as wide as the column. A column of a
 * fixed width is a {@link SizedBox} around one of its cells.
 *
 * <p><b>Rows.</b> Each row is as tall as the tallest of its cells, and {@link #rowGap} separates
 * one row from the next. {@link #crossAlignment} places a cell within its row's height with the
 * words a {@link Row} uses, and its default is {@code BASELINE}, not a row's {@code START},
 * because a grid is where a caption stands beside the field it names and the two should share
 * the line their text sits on.
 *
 * <p>In a right-to-left subtree ({@link limn.scene.LayoutDirection}) the first column is at the
 * right edge and the columns follow leftwards, each cell placed within its column from the
 * column's right edge; the rows still go down.
 *
 * <p><b>No child is flexible here.</b> What a grid leaves over belongs to a column and not to a
 * cell, so an {@link Expanded} is refused when it is added, rather than accepted and laid out as
 * the widget it wraps: the column it would have stretched is named with {@link #expandedColumn}.
 *
 * <p>A grid draws nothing, clips nothing and describes nothing to an assistive technology, so it
 * publishes no node of its own and its children take its place. Their reading order, and the
 * order Tab visits them in, is the order they were added in, which is the order the rows are read
 * in, row by row and cell by cell in reading order, in both directions: a right-to-left subtree
 * moves the boxes and not the order.
 */
public class Grid extends Container<Grid> {

    private final int columns;
    private float gap;
    private float rowGap;
    private Flex.CrossAlignment crossAlignment = Flex.CrossAlignment.BASELINE;
    private final Flex.CrossAlignment[] columnAlignments;
    private int expandedColumn = -1;

    /**
     * An empty grid of {@code columns} columns, with no gap on either axis, no expanded column,
     * every cell at the edge reading starts from within its column and on its row's baseline.
     *
     * @param columns how many cells make a row, fixed for the life of the grid: the children are
     *                added in rows of that many, and a different count would pair every one of
     *                them differently
     * @throws IllegalArgumentException when {@code columns} is less than {@code 1}
     */
    public Grid(int columns) {
        this.columns = Checks.atLeast(columns, 1, "columns");
        columnAlignments = new Flex.CrossAlignment[columns];
        Arrays.fill(columnAlignments, Flex.CrossAlignment.START);
        columnWidths = new float[columns];
        columnStarts = new float[columns];
        columnShown = new boolean[columns];
        measuredColumnWidths = new float[columns];
    }

    /**
     * Space between one column and the next, in logical points: the gutter a {@link Row}'s
     * {@link Flex#gap} is between neighbours, never before the first column or after the last,
     * and never beside a column whose every cell is hidden. Negative values clamp to {@code 0}.
     * UI thread only.
     *
     * @param newGap the gutter between columns
     * @return this grid
     * @throws IllegalArgumentException when {@code NaN} or infinite
     */
    public Grid gap(float newGap) {
        Ui.checkUiThread();
        float value = Math.max(0, Checks.finite(newGap, "gap"));
        if (gap != value) {
            gap = value;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Space between one row and the next, in logical points, never above the first row or below
     * the last, and never beside a row whose every cell is hidden. Negative values clamp to
     * {@code 0}. UI thread only.
     *
     * @param newGap the gutter between rows
     * @return this grid
     * @throws IllegalArgumentException when {@code NaN} or infinite
     */
    public Grid rowGap(float newGap) {
        Ui.checkUiThread();
        float value = Math.max(0, Checks.finite(newGap, "row gap"));
        if (rowGap != value) {
            rowGap = value;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Where a cell sits within the height of its row, which is the height of the row's tallest
     * cell. {@code BASELINE}, the default, lines up the text of a row's cells, shifting each one
     * down inside its row and never out of it, the clamp a {@link Row} applies inside its own
     * height; a cell with no text lines up its bottom edge. {@code START}, {@code CENTER} and
     * {@code END} place the box, and do not turn around in a right-to-left subtree because the
     * axis is vertical. {@code STRETCH} makes every cell of a row as tall as the row, and costs a
     * second measure in every pass of each cell shorter than its row, because the height it is
     * stretched to is known only once the row is formed. UI thread only.
     *
     * @param alignment the placement of a cell within its row
     * @return this grid
     */
    public Grid crossAlignment(Flex.CrossAlignment alignment) {
        Ui.checkUiThread();
        // Validated before anything is stored: a stored null only explodes at the next layout
        // pass, far from the call that put it there.
        Flex.CrossAlignment value = Objects.requireNonNull(alignment, "alignment");
        if (crossAlignment != value) {
            crossAlignment = value;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Where the cells of one column sit within its width, which is the width of its widest cell
     * or, for the {@link #expandedColumn}, whatever the grid gives it. The words are the ones a
     * {@link Column} places its children across with: {@code START}, the default, and {@code END}
     * are the edges reading starts from and ends on, so they turn around in a right-to-left
     * subtree; {@code CENTER} centres; {@code STRETCH} makes every cell as wide as the column,
     * which is what a column of fields wants, and costs a second measure in every pass of each
     * cell narrower than the column, because the column's width is known only once all of its
     * cells have answered. {@code BASELINE} has no meaning across a column and places a cell as
     * {@code START} does, as it does on a column. UI thread only.
     *
     * @param column    the column, in {@code [0, columns)}
     * @param alignment the placement of that column's cells within its width
     * @return this grid
     * @throws IndexOutOfBoundsException when {@code column} is not a column of this grid
     */
    public Grid columnAlignment(int column, Flex.CrossAlignment alignment) {
        Ui.checkUiThread();
        Objects.checkIndex(column, columns);
        Flex.CrossAlignment value = Objects.requireNonNull(alignment, "alignment");
        if (columnAlignments[column] != value) {
            columnAlignments[column] = value;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Makes one column take the width the others leave, the way an {@link Expanded} child takes
     * the room a row leaves: given a width, the grid fills it, and this column is whatever the
     * other columns and the gaps leave of it, wider than its cells need or narrower, down to
     * nothing when the others alone fill the grid. With no bound on the width, which is what
     * {@link limn.scene.Scene#pack()} and a horizontally scrolling parent give, it is as wide as
     * its widest cell, like every other column. Its cells are placed within it by its
     * {@link #columnAlignment}, so a column of fields that should grow with the window is this and
     * {@code STRETCH}. There is at most one; the default is none, and the grid is then as wide as
     * its columns. UI thread only.
     *
     * @param column the column that takes the leftover, in {@code [0, columns)}, or {@code -1} for
     *               none
     * @return this grid
     * @throws IndexOutOfBoundsException when {@code column} is neither a column of this grid nor
     *                                   {@code -1}
     */
    public Grid expandedColumn(int column) {
        Ui.checkUiThread();
        if (column != -1) {
            Objects.checkIndex(column, columns);
        }
        if (expandedColumn != column) {
            expandedColumn = column;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Appends a child to the next cell, which starts a new row after every {@code columns}
     * children (UI thread only).
     *
     * @param child the child to append
     * @throws IllegalArgumentException when {@code child} is an {@link Expanded}: what a grid
     *                                  leaves over belongs to a column, named with
     *                                  {@link #expandedColumn}
     */
    @Override
    public void add(Widget<?> child) {
        Ui.checkUiThread();
        super.add(refuseFlexible(child));
    }

    /**
     * Inserts a child at a position in {@link #children()}, which moves every later child one
     * cell on (UI thread only).
     *
     * @param index where the child goes, in {@code [0, children().size()]}
     * @param child the child to insert; never {@code null}
     * @throws IllegalArgumentException when {@code child} is an {@link Expanded}: what a grid
     *                                  leaves over belongs to a column, named with
     *                                  {@link #expandedColumn}
     */
    @Override
    public void add(int index, Widget<?> child) {
        Ui.checkUiThread();
        super.add(index, refuseFlexible(child));
    }

    private static Widget<?> refuseFlexible(Widget<?> child) {
        if (child instanceof Expanded) {
            throw new IllegalArgumentException("a Grid shares its leftover width with a column, "
                    + "not with a cell, so an Expanded would be laid out as the widget it wraps. "
                    + "Add the child itself and name its column with expandedColumn.");
        }
        return child;
    }

    // ---------------------------------------------------------------- measure and layout

    /**
     * Each visible child's answer to the open question, by child index: the size its column is
     * sized from. Written by {@link #onMeasure}, and by {@link #onLayout} only when it cannot use
     * the measure's answers, which it then forgets (see {@link #measuredWith}).
     */
    private float[] naturalWidths = new float[8];
    private float[] naturalHeights = new float[8];

    /** The size each visible child is laid out at, by child index, for the pass in progress. */
    private float[] cellWidths = new float[8];
    private float[] cellHeights = new float[8];

    /** Each row's height, and whether any of its cells is visible, for the pass in progress. */
    private float[] rowHeights = new float[8];
    private boolean[] rowShown = new boolean[8];

    /**
     * Each column's width, its distance from the edge reading starts from, and whether any of its
     * cells is visible, for the pass in progress.
     */
    private final float[] columnWidths;
    private final float[] columnStarts;
    private final boolean[] columnShown;

    /**
     * The constraints the last {@link #onMeasure} ran under, with the widest answer it was given,
     * the column widths it resolved and their total with the gaps, so that {@link #onLayout} can
     * ask each child the question it already answered: the one-answer cache in
     * {@code Widget.measure} then hands back the same size and no child is measured again in the
     * pass. {@link Flex} and {@link Wrap} keep the same record for the same reason. The answers are
     * reused only while they still fit: the box no larger than the bound they were given against,
     * and no narrower than the widest of them; otherwise layout asks against the box.
     *
     * <p>The column widths are kept as well as the answers because layout at the measured width
     * must find the columns measure found, to the last bit. A grid measured with no bound on the
     * width sizes its expanded column from its cells and is then laid out at the total, and
     * subtracting the other columns back out of that total can come back one float away from the
     * width it was summed from, which would squeeze the widest cell of the column by that much and
     * ask it a question it has not answered.
     */
    private Constraints measuredWith;
    private float measuredWidest;
    private float measuredTotal;
    private final float[] measuredColumnWidths;

    private void ensureCapacity(int childCount) {
        if (naturalWidths.length < childCount) {
            int size = childCount * 2;
            naturalWidths = new float[size];
            naturalHeights = new float[size];
            cellWidths = new float[size];
            cellHeights = new float[size];
            rowHeights = new float[size];
            rowShown = new boolean[size];
        }
    }

    /**
     * The column of the child at {@code index}: the grid's order, row by row, is written down in
     * this method, {@link #rowOf} and {@link #indexOf} and nowhere else.
     */
    private int columnOf(int index) {
        return index % columns;
    }

    /** The row of the child at {@code index}; see {@link #columnOf}. */
    private int rowOf(int index) {
        return index / columns;
    }

    /** The index of the child in a cell, which may be past the last one: see {@link #columnOf}. */
    private int indexOf(int row, int column) {
        return row * columns + column;
    }

    /** How many rows the children make, a short last one and rows of hidden children included. */
    private int rowCount() {
        return (children().size() + columns - 1) / columns;
    }

    /**
     * What every cell is asked first: at most the width the grid may take and at most its height,
     * with no minimum. The whole width and never a share of it, so a cell's answer does not depend
     * on its neighbours and the columns can be sized from the answers in one sweep.
     */
    private static Constraints childConstraints(Constraints incoming) {
        return new Constraints(0, incoming.maxWidth(), 0, incoming.maxHeight());
    }

    /**
     * Whether the cells of the expanded column skip the open question under {@code ask}. With a
     * bound on the width the column's width is what the others leave, which its own cells have no
     * say in, so they are asked once, at that width, as an {@link Expanded} child is asked only at
     * its share; with no bound it is sized from its cells like any other column.
     */
    private boolean expandedSkipsNatural(Constraints ask) {
        return expandedColumn >= 0 && ask.hasBoundedWidth();
    }

    /**
     * Asks every visible child that sizes its column the open question, into
     * {@link #naturalWidths} and {@link #naturalHeights}.
     *
     * @return the widest of the answers
     */
    private float measureNatural(Constraints ask) {
        boolean skipExpanded = expandedSkipsNatural(ask);
        float widest = 0;
        int childCount = children().size();
        for (int i = 0; i < childCount; i++) {
            Widget<?> child = children().get(i);
            if (!child.isVisible() || (skipExpanded && columnOf(i) == expandedColumn)) {
                continue;
            }
            Size size = child.measure(ask);
            naturalWidths[i] = size.width();
            naturalHeights[i] = size.height();
            widest = Math.max(widest, size.width());
        }
        return widest;
    }

    /**
     * Sizes every column into {@link #columnWidths} for {@code available} width: each one as wide
     * as its widest visible cell, and the expanded one, given a finite width, whatever the others
     * and the gaps leave of it and never less than nothing. A column with no visible cell is
     * {@code 0} wide and takes no gap.
     *
     * <p><b>The single copy on purpose</b>, for {@link Flex}'s reason: measure and layout both size
     * the columns, and a grid that measured one set of widths and laid out another would report a
     * height its cells, measured at the other widths, do not have.
     *
     * @return the width of the columns and the gaps between them
     */
    private float resolveColumns(float available, boolean skipExpanded) {
        Arrays.fill(columnWidths, 0);
        Arrays.fill(columnShown, false);
        int childCount = children().size();
        for (int i = 0; i < childCount; i++) {
            if (!children().get(i).isVisible()) {
                continue;
            }
            int column = columnOf(i);
            columnShown[column] = true;
            if (!(skipExpanded && column == expandedColumn)) {
                columnWidths[column] = Math.max(columnWidths[column], naturalWidths[i]);
            }
        }
        float fixed = 0;
        int shown = 0;
        for (int column = 0; column < columns; column++) {
            if (columnShown[column]) {
                float width = column == expandedColumn ? 0 : columnWidths[column];
                fixed += (shown++ > 0 ? gap : 0) + width;
            }
        }
        boolean expands = expandedColumn >= 0 && columnShown[expandedColumn]
                && available != Constraints.UNBOUNDED_LIMIT;
        if (expands) {
            columnWidths[expandedColumn] = Math.max(0, available - fixed);
        }
        return expandedColumn >= 0 && columnShown[expandedColumn]
                ? fixed + columnWidths[expandedColumn]
                : fixed;
    }

    /**
     * Gives every visible child its size at its column's width, into {@link #cellWidths} and
     * {@link #cellHeights}, and every row the height of its tallest cell, into
     * {@link #rowHeights}.
     *
     * <p>A child whose open answer already fits its cell keeps it and is not asked again: one
     * that is narrower than its column and not stretched, or exactly as wide as a stretched one.
     * The ones asked again are a stretched cell narrower than its column, which is given the
     * column's width, a cell of the expanded column wider than what the column was left, which is
     * given at most that, and, under a bound, every cell of the expanded column, which was never
     * asked the open question. The height stays open in every one of those questions, because it
     * is the height at the new width that the row is sized from.
     *
     * @return how many rows there are, the empty ones included
     */
    private int sizeCells(Constraints ask) {
        boolean skipExpanded = expandedSkipsNatural(ask);
        int childCount = children().size();
        int rows = rowCount();
        Arrays.fill(rowHeights, 0, rows, 0);
        Arrays.fill(rowShown, 0, rows, false);
        for (int i = 0; i < childCount; i++) {
            Widget<?> child = children().get(i);
            if (!child.isVisible()) {
                continue;
            }
            int column = columnOf(i);
            float columnWidth = columnWidths[column];
            boolean stretch = columnAlignments[column] == Flex.CrossAlignment.STRETCH;
            boolean answered = !(skipExpanded && column == expandedColumn);
            float width = naturalWidths[i];
            float height = naturalHeights[i];
            if (!answered || (stretch ? width != columnWidth : width > columnWidth)) {
                Size size = child.measure(new Constraints(stretch ? columnWidth : 0, columnWidth,
                        0, ask.maxHeight()));
                width = size.width();
                height = size.height();
            }
            cellWidths[i] = width;
            cellHeights[i] = height;
            int row = rowOf(i);
            rowHeights[row] = Math.max(rowHeights[row], height);
            rowShown[row] = true;
        }
        return rows;
    }

    /** The height of the visible rows and the gaps between them. */
    private float rowsHeight(int rows) {
        float height = 0;
        int shown = 0;
        for (int row = 0; row < rows; row++) {
            if (rowShown[row]) {
                height += (shown++ > 0 ? rowGap : 0) + rowHeights[row];
            }
        }
        return height;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        ensureCapacity(children().size());
        Constraints ask = childConstraints(constraints);
        measuredWidest = measureNatural(ask);
        measuredTotal = resolveColumns(constraints.maxWidth(), expandedSkipsNatural(ask));
        System.arraycopy(columnWidths, 0, measuredColumnWidths, 0, columns);
        int rows = sizeCells(ask);
        measuredWith = constraints;
        return constraints.constrain(measuredTotal, rowsHeight(rows));
    }

    @Override
    protected void onLayout() {
        ensureCapacity(children().size());
        float boxWidth = width();
        // The measure's own question wherever its answers still fit the box, so each child's
        // cached answer comes back (see measuredWith); otherwise the box, which is what a parent
        // that laid the grid out narrower than it measured, or without measuring it, has given.
        boolean reuse = measuredWith != null
                && boxWidth <= measuredWith.maxWidth()
                && boxWidth >= measuredWidest
                && height() <= measuredWith.maxHeight();
        Constraints ask;
        if (reuse) {
            ask = childConstraints(measuredWith);
        } else {
            ask = Constraints.loose(boxWidth, height());
            measureNatural(ask);
            measuredWith = null; // the answers the measure left are gone now
        }
        resolveColumns(boxWidth, expandedSkipsNatural(ask));
        if (reuse && boxWidth == measuredTotal) {
            // Laid out at exactly the width measure summed: the columns are the measure's, to the
            // last bit (see measuredWith for the float that would otherwise come back).
            System.arraycopy(measuredColumnWidths, 0, columnWidths, 0, columns);
        }
        int rows = sizeCells(ask);

        // Resolved once for the whole pass: two resolutions that disagreed inside one layout would
        // put one row's first column at the left edge and the next row's at the right.
        boolean rtl = isRightToLeft();
        // The column starts are LOGICAL distances from the edge reading starts from, which the
        // placement below reflects, exactly as in Flex: only the final coordinate turns around,
        // so the gap and every alignment are one piece of arithmetic in both directions.
        float cursor = 0;
        int shown = 0;
        for (int column = 0; column < columns; column++) {
            if (columnShown[column] && shown++ > 0) {
                cursor += gap;
            }
            columnStarts[column] = cursor;
            if (columnShown[column]) {
                cursor += columnWidths[column];
            }
        }

        float top = 0;
        int rowsShown = 0;
        for (int row = 0; row < rows; row++) {
            if (!rowShown[row]) {
                continue;
            }
            if (rowsShown++ > 0) {
                top += rowGap;
            }
            float rowHeight = rowHeights[row];
            for (int column = 0; column < columns; column++) {
                Widget<?> child = cellChild(row, column);
                if (child == null) {
                    continue;
                }
                int i = indexOf(row, column);
                float width = cellWidths[i];
                float height = cellHeights[i];
                if (crossAlignment == Flex.CrossAlignment.STRETCH && height < rowHeight) {
                    // The one question layout adds, and only of a cell shorter than its row: the
                    // row's height is known only now. The width is pinned to the one the cell
                    // already has, so its column still holds it.
                    Size stretched = child.measure(Constraints.tight(width, rowHeight));
                    width = stretched.width();
                    height = stretched.height();
                }
                float x = columnStarts[column] + switch (columnAlignments[column]) {
                    case START, STRETCH, BASELINE -> 0;
                    case CENTER -> (columnWidths[column] - width) / 2;
                    case END -> columnWidths[column] - width;
                };
                float y = top + switch (crossAlignment) {
                    case START, STRETCH, BASELINE -> 0; // BASELINE moves them in a second sweep
                    case CENTER -> (rowHeight - height) / 2;
                    case END -> rowHeight - height;
                };
                child.layoutBox(rtl ? boxWidth - x - width : x, y, width, height);
            }
            if (crossAlignment == Flex.CrossAlignment.BASELINE) {
                alignBaselines(row, top, rowHeight);
            }
            top += rowHeight;
        }
    }

    /**
     * The {@link Flex.CrossAlignment#BASELINE} sweep over one row, once every cell of it has a
     * box: each cell moves down by the difference between the row's deepest baseline and its own,
     * clamped so that it stays inside the row. The clamp is the one {@link Row} applies, for the
     * same reason: a row is as tall as its tallest cell and no taller, which can be less than a
     * baseline-aligned line needs, and a cell shifted out of its row would overlap the next one.
     * The shift uses {@link #moveChild}, so no cell is laid out twice.
     */
    private void alignBaselines(int row, float top, float rowHeight) {
        float deepest = 0;
        for (int column = 0; column < columns; column++) {
            Widget<?> child = cellChild(row, column);
            if (child != null) {
                deepest = Math.max(deepest, baselineOffsetOf(child));
            }
        }
        for (int column = 0; column < columns; column++) {
            Widget<?> child = cellChild(row, column);
            if (child != null) {
                float wanted = deepest - baselineOffsetOf(child);
                float room = rowHeight - child.height();
                moveChild(child, child.x(), top + Math.max(0, Math.min(wanted, room)));
            }
        }
    }

    /** The visible child in a cell, or {@code null} for a cell past the last child or hidden. */
    private Widget<?> cellChild(int row, int column) {
        int index = indexOf(row, column);
        if (index >= children().size()) {
            return null;
        }
        Widget<?> child = children().get(index);
        return child.isVisible() ? child : null;
    }
}

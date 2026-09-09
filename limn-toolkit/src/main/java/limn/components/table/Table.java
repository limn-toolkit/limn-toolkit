package limn.components.table;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.backend.Cursor;
import limn.components.Accelerator;
import limn.components.ScrollBar;
import limn.components.ScrollGutters;
import limn.components.SizeTokens;
import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.i18n.LanguageWitness;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.text.Collator;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * A table: {@link Column}s over a list the application owns, virtualized on both axes.
 *
 * <p>The rows are a {@code List<T>} held by reference and re-read on {@link #refresh()};
 * nothing is copied and nothing is read until it is on screen. Vertically the table keeps the
 * anchor-and-walk {@code ListView} keeps &mdash; which row sits at which y, a walk from there
 * until the viewport is full, a mean height as the scroll estimate &mdash; over <b>row slots</b>
 * rather than widgets: a value cell is its formatted text, shaped once and held (ADR 031), and
 * only a {@link Column#widget} cell is a real child, mounted and recycled with its row.
 * Horizontally the columns are a band: widths are resolved once per layout, and only the
 * columns that intersect the viewport are shaped and painted. The header is pinned.
 *
 * <p><b>Selection</b> is by row and by <b>model</b> index, in one of three
 * {@linkplain SelectionMode modes}, and it survives a sort because a sort is a
 * {@linkplain #setSort permutation} over the application's list and never a reordering of it.
 * Separately, the arrow keys move a <b>focus cell</b>, which is what a screen reader's cursor
 * stands on and where a future editor opens. Enter and a double click
 * {@linkplain #onActivate activate} the lead row.
 *
 * <p><b>Sorting</b>: a click on a sortable header cycles ascending, descending and the model's
 * order. The table sorts by default; {@link #onSortRequest} hands the click to the application
 * instead, for rows a server orders.
 *
 * <p><b>The footer</b> is a summary row pinned under the rows, as the header is pinned over
 * them, and it appears as soon as one column has something for it ({@link Column#footer},
 * {@link Column#footerSum()} and the other aggregates). It is computed on {@link #setRows} and
 * {@link #refresh()} and never per frame.
 *
 * <p>ADR 041 is the record.
 *
 * @param <T> the row type
 */
public class Table<T> extends Widget implements Scrollable {

    /** How many rows may be selected at once. */
    public enum SelectionMode {
        /** None: the focus cell still moves, and nothing is ever selected. */
        NONE,
        /** One row. */
        SINGLE,
        /** Any number of rows: Shift for a range, the command modifier to toggle one. */
        MULTI
    }

    /** Rows of intrinsic height when the height axis is unbounded; a count, not a length. */
    private static final int VISIBLE_ROWS_HINT = 8;
    /** How far either side of a header divider a press starts a resize, in points. */
    private static final float RESIZE_BAND = 4;
    /** Two presses on one row closer than this are a double click. */
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    /** The synthetic key of the header row's group node. */
    private static final long HEADER_KEY = -1;
    /** The synthetic key of the footer row's group node. */
    private static final long FOOTER_KEY = -2;
    /** The bit that keeps a widget cell's identity key apart from a row's. */
    private static final long WIDGET_KEY = 1L << 40;

    private final List<Column<T>> columns;
    private List<T> rows = List.of();
    private final ScrollBar vBar;
    private final ScrollBar hBar;
    private final ScrollGutters gutters = new ScrollGutters();

    // Sort: a permutation from view position to model index, or null for the model's order.
    private int[] view;
    private int[] inverse;
    private Column<T> sortColumn;
    private SortOrder sortOrder = SortOrder.NONE;
    private BiConsumer<Column<T>, SortOrder> onSortRequest;

    // Selection, in model indices; the lead is the row the user last acted on.
    private SelectionMode selectionMode = SelectionMode.SINGLE;
    private final BitSet selected = new BitSet();
    private int lead = -1;
    private int rangeAnchor = -1;   // view index a Shift range extends from
    private Runnable onSelect = () -> { };
    private IntConsumer onActivate = index -> { };

    // The focus cell: a view row and a shown column, or -1 before anything was focused.
    private int focusRow = -1;
    private int focusColumn;

    // Columns as shown: which, and where, resolved per layout.
    private int shownCount;
    private int[] shownIndex = new int[0];
    private float[] colX = new float[0];
    private float[] colW = new float[0];
    private float contentWidth;
    private float lastViewportWidth;

    // Realized rows in data (view) order, the run the last layout placed, and the anchor.
    private int[] mountedRows = new int[16];
    private Slot[] mountedSlots = new Slot[16];
    private int mountedCount;
    private int placedFrom;
    private int placedTo;
    private int anchorIndex;
    private float anchorTop;
    private float measuredRowHeight;
    private float offsetX;
    private int pendingEnsureVisible = -1;

    // Looks.
    private boolean striped = true;
    private boolean showHeader = true;
    private ShapedText[] headerShaped = new ShapedText[0];
    private final LanguageWitness language = new LanguageWitness();
    private long textEpoch;
    // The footer: one text per column, null where the column has none, computed on setRows and
    // refresh; shown while any shown column has one.
    private String[] footerTexts = new String[0];
    private ShapedText[] footerShaped = new ShapedText[0];
    private boolean footerShown;
    private Font headerBase;
    private Font headerDerived;
    /**
     * The two tints derived from the theme, memoised on the theme's identity: a colour derived
     * inside paint is an allocation per striped or selected row per frame, and a quiet frame
     * allocates nothing.
     */
    private Theme tintTheme;
    private limn.graphics.Color selectionTint;
    private limn.graphics.Color stripeTint;

    // Pointer state: a header drag, the divider under the pointer, the last press.
    private int dragColumn = -1;
    private float dragStartX;
    private float dragStartWidth;
    private int hoverDivider = -1;
    private long lastPressNanos;
    private int lastPressRow = -1;

    /** One realized row: its text per column, shaped and fitted lazily, and its widget cells. */
    private static final class Slot {
        int row;
        float height;
        final String[] texts;
        final ShapedText[] shaped;
        final ShapedText[] fitted;
        final float[] fittedWidth;
        final Widget[] widgets;
        int widgetCount;

        Slot(int columns) {
            texts = new String[columns];
            shaped = new ShapedText[columns];
            fitted = new ShapedText[columns];
            fittedWidth = new float[columns];
            widgets = new Widget[columns];
        }
    }

    /**
     * A table over {@code columns}, with no rows until {@link #setRows}.
     *
     * @param columns the columns, in reading order; at least one
     * @throws IllegalArgumentException if {@code columns} is empty
     */
    public Table(List<Column<T>> columns) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("a table needs at least one column");
        }
        this.columns = List.copyOf(columns);
        setFocusable(true);
        vBar = new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return estimatedContentHeight(tokens());
            }

            @Override
            public float viewportLength() {
                return rowsViewportHeight();
            }

            @Override
            public float offset() {
                return estimatedOffset(tokens());
            }

            @Override
            public void setOffset(float value) {
                scrollToOffset(value, tokens());
            }
        });
        hBar = new ScrollBar(ScrollBar.Orientation.HORIZONTAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return contentWidth;
            }

            @Override
            public float viewportLength() {
                return gutters.viewportWidth(width());
            }

            @Override
            public float offset() {
                return offsetX;
            }

            @Override
            public void setOffset(float value) {
                scrollTo(value);
            }
        });
        add(vBar);
        add(hBar);
    }

    /**
     * A table over {@code columns}.
     *
     * @param columns the columns, in reading order; at least one
     * @return the table
     * @param <T> the row type
     */
    @SafeVarargs
    public static <T> Table<T> of(Column<T>... columns) {
        return new Table<>(List.of(columns));
    }

    // ------------------------------------------------------------------ rows and columns

    /**
     * Hands the table the application's rows, held by reference and never copied. The selection
     * is dropped, the sort is re-applied, and the view starts at the top. UI thread only.
     *
     * @param rows the rows; the table reads them on demand, so a change to the list's contents
     *             is invisible until {@link #refresh()}
     * @return this table
     */
    public Table<T> setRows(List<T> rows) {
        Ui.checkUiThread();
        this.rows = Objects.requireNonNull(rows, "rows");
        boolean had = !selected.isEmpty() || lead >= 0;
        selected.clear();
        lead = -1;
        rangeAnchor = -1;
        focusRow = -1;
        anchorIndex = 0;
        anchorTop = 0;
        resort();
        unmountAll();
        recomputeFooter();
        markNeedsLayout();
        invalidate();
        if (had) {
            onSelect.run();
        }
        return this;
    }

    /** @return the rows, as handed to {@link #setRows} */
    public List<T> rows() {
        return rows;
    }

    /** @return how many rows the model holds */
    public int rowCount() {
        return rows.size();
    }

    /** @return the columns, in reading order; hidden ones included */
    public List<Column<T>> columns() {
        return columns;
    }

    /**
     * @param column one of this table's columns
     * @return the text its footer cell shows, as computed by the last {@link #setRows} or
     *         {@link #refresh()}; {@code null} when the column puts nothing in the footer
     */
    public String footerTextOf(Column<T> column) {
        int c = columns.indexOf(column);
        return c < 0 || c >= footerTexts.length ? null : footerTexts[c];
    }

    /**
     * @param column one of this table's columns
     * @return the width the last layout gave it, weight included; {@code 0} while it is hidden or
     *         before the first layout
     */
    public float widthOf(Column<T> column) {
        for (int s = 0; s < shownCount; s++) {
            if (columns.get(shownIndex[s]) == column) {
                return colW[s];
            }
        }
        return 0;
    }

    /**
     * Re-reads the rows and re-lays out: call after the list's contents change, or after a
     * column's width, visibility or alignment does. The sort is re-applied, a selected row the
     * list no longer has is dropped, and the scroll position is kept, clamped. UI thread only.
     */
    public void refresh() {
        Ui.checkUiThread();
        int count = rows.size();
        boolean moved = false;
        int dropped = selected.nextSetBit(count);
        if (dropped >= 0) {
            selected.clear(count, Integer.MAX_VALUE);
            moved = true;
        }
        if (lead >= count) {
            lead = selected.isEmpty() ? -1 : selected.length() - 1;
            moved = true;
        }
        if (focusRow >= count) {
            focusRow = count - 1;
        }
        rangeAnchor = Math.min(rangeAnchor, count - 1);
        resort();
        anchorIndex = Math.max(0, Math.min(anchorIndex, Math.max(0, count - 1)));
        unmountAll();
        textEpoch++;
        recomputeFooter();
        markNeedsLayout();
        invalidate();
        if (moved) {
            onSelect.run();
        }
    }

    // ------------------------------------------------------------------------ selection

    /**
     * Sets how many rows may be selected (default {@link SelectionMode#SINGLE}). Narrowing the
     * mode trims the selection to the lead row, or to nothing. UI thread only.
     *
     * @param mode the mode; never {@code null}
     * @return this table
     */
    public Table<T> setSelectionMode(SelectionMode mode) {
        Ui.checkUiThread();
        this.selectionMode = Objects.requireNonNull(mode, "mode");
        boolean moved = false;
        if (mode == SelectionMode.NONE && (!selected.isEmpty() || lead >= 0)) {
            selected.clear();
            lead = -1;
            moved = true;
        } else if (mode == SelectionMode.SINGLE && selected.cardinality() > 1) {
            selected.clear();
            selected.set(lead);
            moved = true;
        }
        invalidate();
        if (moved) {
            onSelect.run();
        }
        return this;
    }

    /** @return how many rows may be selected */
    public SelectionMode selectionMode() {
        return selectionMode;
    }

    /**
     * Selects one row alone, moves the focus cell to it, scrolls it into view and fires
     * {@link #onSelect}. Selecting the row that is already the only selected one changes nothing
     * and fires nothing, which is the recursion guard two bound controls rest on. UI thread only.
     *
     * @param modelIndex a row in {@code [0, rowCount())}
     * @return this table
     * @throws IndexOutOfBoundsException if the index is outside that range
     * @throws IllegalStateException     if the mode is {@link SelectionMode#NONE}
     */
    public Table<T> setSelectedRow(int modelIndex) {
        Ui.checkUiThread();
        Objects.checkIndex(modelIndex, rows.size());
        if (selectionMode == SelectionMode.NONE) {
            throw new IllegalStateException("selection mode is NONE");
        }
        selectOnly(modelIndex, viewOf(modelIndex), true);
        return this;
    }

    /**
     * Replaces the selection with exactly these rows, the last one the lead, and scrolls the lead
     * into view: what an application restoring a saved selection calls. In
     * {@link SelectionMode#SINGLE} only one row may be named. Fires {@link #onSelect} once when
     * the set changed. UI thread only.
     *
     * @param modelIndices rows in {@code [0, rowCount())}; none clears the selection
     * @return this table
     * @throws IndexOutOfBoundsException if an index is outside that range
     * @throws IllegalStateException     if the mode is {@link SelectionMode#NONE}, or SINGLE and
     *                                   more than one row is named
     */
    public Table<T> setSelectedRows(int... modelIndices) {
        Ui.checkUiThread();
        if (modelIndices.length == 0) {
            return clearSelection();
        }
        if (selectionMode == SelectionMode.NONE) {
            throw new IllegalStateException("selection mode is NONE");
        }
        if (selectionMode == SelectionMode.SINGLE && modelIndices.length > 1) {
            throw new IllegalStateException("selection mode is SINGLE");
        }
        for (int index : modelIndices) {
            Objects.checkIndex(index, rows.size());
        }
        BitSet next = new BitSet();
        for (int index : modelIndices) {
            next.set(index);
        }
        int last = modelIndices[modelIndices.length - 1];
        boolean same = next.equals(selected) && lead == last;
        selected.clear();
        selected.or(next);
        lead = last;
        focusRow = viewOf(last);
        rangeAnchor = focusRow;
        ensureVisible(focusRow);
        invalidate();
        if (!same) {
            onSelect.run();
        }
        return this;
    }

    /**
     * Drops the selection and fires {@link #onSelect}; nothing happens when nothing is selected.
     * The focus cell stays where it is. UI thread only.
     *
     * @return this table
     */
    public Table<T> clearSelection() {
        Ui.checkUiThread();
        if (selected.isEmpty() && lead < 0) {
            return this;
        }
        selected.clear();
        lead = -1;
        invalidate();
        onSelect.run();
        return this;
    }

    /**
     * Selects every row, in {@link SelectionMode#MULTI}; the lead row is kept, or becomes the
     * first row. UI thread only.
     *
     * @return this table
     */
    public Table<T> selectAll() {
        Ui.checkUiThread();
        if (selectionMode != SelectionMode.MULTI || rows.isEmpty()) {
            return this;
        }
        if (selected.cardinality() == rows.size()) {
            return this;
        }
        selected.set(0, rows.size());
        if (lead < 0) {
            lead = modelOf(0);
        }
        invalidate();
        onSelect.run();
        return this;
    }

    /** @return the lead row's model index, or {@code -1} when nothing is selected */
    public int selectedRow() {
        return lead;
    }

    /** @return the selected rows' model indices, ascending; empty when nothing is selected */
    public int[] selectedRows() {
        return selected.stream().toArray();
    }

    /**
     * @param modelIndex a row
     * @return whether it is selected
     */
    public boolean isSelected(int modelIndex) {
        return modelIndex >= 0 && selected.get(modelIndex);
    }

    /**
     * Called once per change of the selection, by click, keyboard or code.
     *
     * @param handler what to run; never {@code null}
     * @return this table
     */
    public Table<T> onSelect(Runnable handler) {
        Ui.checkUiThread();
        this.onSelect = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Called with the lead row's model index on Enter or a double click, the "open this" gesture.
     *
     * @param handler what to run; never {@code null}
     * @return this table
     */
    public Table<T> onActivate(IntConsumer handler) {
        Ui.checkUiThread();
        this.onActivate = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /** Fires {@link #onActivate} for the lead row, as Enter does; nothing without one. */
    public void activate() {
        Ui.checkUiThread();
        if (lead >= 0) {
            onActivate.accept(lead);
        }
    }

    /** @return the focus cell's row as shown, or {@code -1} before the keyboard was in the table */
    public int focusRow() {
        return focusRow;
    }

    /** @return the focus cell's column, as an index among the shown columns */
    public int focusColumn() {
        return focusColumn;
    }

    // ------------------------------------------------------------------------------ sort

    /**
     * Orders the rows on a column, or restores the model's order with {@link SortOrder#NONE}. The
     * application's list is untouched: the table keeps a permutation, and the selection, which
     * is by model row, is unchanged. When {@link #onSortRequest} is set this only records the
     * order shown in the header; the application is expected to have ordered the list. UI thread
     * only.
     *
     * @param column the column, one of this table's; ignored when the order is none
     * @param order  the order; never {@code null}
     * @return this table
     * @throws IllegalArgumentException if the column is not this table's or not sortable
     */
    public Table<T> setSort(Column<T> column, SortOrder order) {
        Ui.checkUiThread();
        Objects.requireNonNull(order, "order");
        if (order == SortOrder.NONE) {
            sortColumn = null;
            sortOrder = SortOrder.NONE;
        } else {
            if (!columns.contains(column)) {
                throw new IllegalArgumentException("not one of this table's columns");
            }
            if (!column.isSortable()) {
                throw new IllegalArgumentException("the column is not sortable");
            }
            sortColumn = column;
            sortOrder = order;
        }
        resort();
        unmountAll();
        markNeedsLayout();
        invalidate();
        return this;
    }

    /** @return the column the rows are ordered on, or {@code null} in the model's order */
    public Column<T> sortColumn() {
        return sortColumn;
    }

    /** @return how the rows are ordered */
    public SortOrder sortOrder() {
        return sortOrder;
    }

    /**
     * Hands header clicks to the application instead of sorting: the handler is told the column
     * and the order the click asks for, orders the list itself and calls {@link #refresh()}. The
     * header shows the order once {@link #setSort} records it. {@code null} restores the table's
     * own sort.
     *
     * @param handler what to tell, or {@code null}
     * @return this table
     */
    public Table<T> onSortRequest(BiConsumer<Column<T>, SortOrder> handler) {
        Ui.checkUiThread();
        this.onSortRequest = handler;
        return this;
    }

    /**
     * @param viewIndex a row as shown, in {@code [0, rowCount())}
     * @return the model row shown there
     */
    public int viewToModel(int viewIndex) {
        Objects.checkIndex(viewIndex, rows.size());
        return modelOf(viewIndex);
    }

    /**
     * @param modelIndex a model row, in {@code [0, rowCount())}
     * @return where it is shown
     */
    public int modelToView(int modelIndex) {
        Objects.checkIndex(modelIndex, rows.size());
        return viewOf(modelIndex);
    }

    /** @return the first row shown, as a view index; for tests and inspection */
    public int firstVisibleRow() {
        return anchorIndex;
    }

    // ----------------------------------------------------------------------------- looks

    /**
     * Sets whether every second row is tinted (default on).
     *
     * @param striped whether rows alternate
     * @return this table
     */
    public Table<T> setStriped(boolean striped) {
        Ui.checkUiThread();
        this.striped = striped;
        invalidate();
        return this;
    }

    /**
     * Sets whether the header row is shown (default on). Without it nothing sorts or resizes
     * by pointer.
     *
     * @param show whether the header is shown
     * @return this table
     */
    public Table<T> setShowHeader(boolean show) {
        Ui.checkUiThread();
        this.showHeader = show;
        markNeedsLayout();
        return this;
    }

    /**
     * Sets when the scroll bars are shown (default {@link ScrollBar.Policy#AUTO}).
     *
     * @param policy the policy
     * @return this table
     */
    public Table<T> setScrollbarPolicy(ScrollBar.Policy policy) {
        vBar.setPolicy(policy);
        hBar.setPolicy(policy);
        return this;
    }

    /**
     * Sets whether the bars float over the rows or reserve strips of their own (default
     * {@link ScrollGutters.Layout#OVERLAY}).
     *
     * @param layout the layout
     * @return this table
     */
    public Table<T> setBarLayout(ScrollGutters.Layout layout) {
        Ui.checkUiThread();
        gutters.setLayout(layout);
        markNeedsLayout();
        return this;
    }

    // ------------------------------------------------------------------------- scrolling

    /**
     * Scrolls by a delta in logical points: positive {@code dy} toward the last row, positive
     * {@code dx} toward the last column. UI thread only.
     *
     * @param dx how far along the columns
     * @param dy how far along the rows
     */
    public void scrollBy(float dx, float dy) {
        Ui.checkUiThread();
        boolean moved = false;
        if (dy != 0) {
            SizeTokens t = tokens();
            float offset = estimatedOffset(t);
            float max = Math.max(0, estimatedContentHeight(t) - rowsViewportHeight());
            float applied = Math.min(Math.max(0, offset + dy), max) - offset;
            if (applied != 0) {
                anchorTop -= applied;
                for (int i = 0; i < mountedCount; i++) {
                    Slot slot = mountedSlots[i];
                    for (Widget w : slot.widgets) {
                        if (w != null) {
                            moveChild(w, w.x(), w.y() - applied);
                        }
                    }
                }
                vBar.onScrolled();
                moved = true;
            }
        }
        if (dx != 0) {
            float max = Math.max(0, contentWidth - gutters.viewportWidth(width()));
            float next = Math.min(Math.max(0, offsetX + dx), max);
            if (next != offsetX) {
                float applied = next - offsetX;
                offsetX = next;
                float sign = isRightToLeft() ? 1 : -1;
                for (int i = 0; i < mountedCount; i++) {
                    Slot slot = mountedSlots[i];
                    for (Widget w : slot.widgets) {
                        if (w != null) {
                            moveChild(w, w.x() + sign * applied, w.y());
                        }
                    }
                }
                hBar.onScrolled();
                moved = true;
            }
        }
        if (moved) {
            markNeedsContainedLayout();
            invalidate();
        }
    }

    private void scrollTo(float newOffsetX) {
        scrollBy(newOffsetX - offsetX, 0);
    }

    @Override
    public void revealRect(float x, float y, float rectWidth, float rectHeight) {
        Ui.checkUiThread();
        float top = rowsTop();
        float bottom = top + rowsViewportHeight();
        float dy = 0;
        if (y < top) {
            dy = y - top;
        } else if (y + rectHeight > bottom) {
            dy = Math.min(y - top, y + rectHeight - bottom);
        }
        float left = rowsLeft();
        float right = left + gutters.viewportWidth(width());
        float dx = 0;
        if (x < left) {
            dx = x - left;
        } else if (x + rectWidth > right) {
            dx = Math.min(x - left, x + rectWidth - right);
        }
        if (isRightToLeft()) {
            dx = -dx;
        }
        scrollBy(dx, dy);
    }

    // --------------------------------------------------------------------------- estimates

    private SizeTokens tokens() {
        return Theme.current().tokensFor(this);
    }

    private float avgRowHeight(SizeTokens t) {
        return measuredRowHeight > 0 ? measuredRowHeight : t.listRowSeed();
    }

    private float estimatedContentHeight(SizeTokens t) {
        return rows.size() * avgRowHeight(t);
    }

    private float estimatedOffset(SizeTokens t) {
        float avg = avgRowHeight(t);
        float max = Math.max(0, estimatedContentHeight(t) - rowsViewportHeight());
        return Math.max(0, Math.min(anchorIndex * avg - anchorTop, max));
    }

    private void scrollToOffset(float offset, SizeTokens t) {
        float clamped = Math.max(0, offset);
        float avg = avgRowHeight(t);
        anchorIndex = avg > 0 ? (int) (clamped / avg) : 0;
        anchorIndex = Math.max(0, Math.min(anchorIndex, Math.max(0, rows.size() - 1)));
        anchorTop = anchorIndex * avg - clamped;
        markNeedsLayout();
        invalidate();
        vBar.onScrolled();
    }

    private float headerHeight(SizeTokens t) {
        return showHeader ? t.controlHeight() : 0;
    }

    private float footerHeight(SizeTokens t) {
        return footerShown ? t.controlHeight() : 0;
    }

    /** The top of the footer row in this widget's coordinates, for this pass. */
    private float footerTop() {
        return gutters.viewportHeight(height()) - footerHeight(tokens());
    }

    /**
     * Reads every column's footer cell from the rows: once per {@link #setRows} and
     * {@link #refresh}, and once more when the language moves, never per frame.
     */
    private void recomputeFooter() {
        if (footerTexts.length < columns.size()) {
            footerTexts = new String[columns.size()];
            footerShaped = new ShapedText[columns.size()];
        }
        Locale locale = locale();
        boolean shown = false;
        // Under the table's own locale, as a pass would be: a format that reads I18n.locale()
        // when it formats has to see this table's language, not the thread's, and a refresh is
        // called from application code outside any pass.
        Locale enclosing = I18n.pushScope(locale);
        try {
            for (int c = 0; c < columns.size(); c++) {
                Column<T> column = columns.get(c);
                footerTexts[c] = column.footerText(rows, locale);
                footerShaped[c] = null;
                shown |= footerTexts[c] != null && column.isVisible();
            }
        } finally {
            I18n.popScope(enclosing);
        }
        if (shown != footerShown) {
            footerShown = shown;
            markNeedsLayout();
        }
        textEpoch++;
    }

    private float rowsTop() {
        return headerHeight(tokens());
    }

    private float rowsLeft() {
        return isRightToLeft() ? width() - gutters.viewportWidth(width()) : 0;
    }

    private float rowsViewportHeight() {
        SizeTokens t = tokens();
        return Math.max(0, gutters.viewportHeight(height()) - headerHeight(t) - footerHeight(t));
    }

    // ------------------------------------------------------------------------------ layout

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = tokens();
        float preferred = 0;
        for (Column<T> column : columns) {
            if (column.isVisible()) {
                preferred += column.width();
            }
        }
        float w = constraints.hasBoundedWidth() ? constraints.maxWidth() : preferred;
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight()
                : headerHeight(t) + footerHeight(t) + VISIBLE_ROWS_HINT * avgRowHeight(t);
        return constraints.constrain(w, h);
    }

    /**
     * Resolves which columns are shown and how wide each is for a viewport of {@code viewW}:
     * every column its own width, then the leftover shared by weight; ADR 041 §5.
     */
    private void resolveColumns(float viewW) {
        lastViewportWidth = viewW;
        int n = 0;
        for (Column<T> column : columns) {
            if (column.isVisible()) {
                n++;
            }
        }
        if (shownIndex.length < n) {
            shownIndex = new int[n];
            colX = new float[n];
            colW = new float[n];
        }
        if (headerShaped.length < columns.size()) {
            headerShaped = Arrays.copyOf(headerShaped, columns.size());
        }
        shownCount = n;
        float total = 0;
        float weights = 0;
        int at = 0;
        for (int i = 0; i < columns.size(); i++) {
            Column<T> column = columns.get(i);
            if (!column.isVisible()) {
                continue;
            }
            shownIndex[at] = i;
            colW[at] = column.width();
            total += colW[at];
            weights += column.weight();
            at++;
        }
        float leftover = viewW - total;
        if (leftover > 0 && weights > 0) {
            for (int s = 0; s < n; s++) {
                float weight = columns.get(shownIndex[s]).weight();
                if (weight > 0) {
                    colW[s] += leftover * weight / weights;
                }
            }
            total = viewW;
        }
        float x = 0;
        for (int s = 0; s < n; s++) {
            colX[s] = x;
            x += colW[s];
        }
        contentWidth = total;
        offsetX = Math.max(0, Math.min(offsetX, Math.max(0, contentWidth - viewW)));
    }

    /** The left edge of shown column {@code s} in this widget's coordinates, for this pass. */
    private float columnLeft(int s, float rowX, float viewW, boolean rtl) {
        return rtl ? rowX + viewW - (colX[s] + colW[s]) + offsetX
                : rowX + colX[s] - offsetX;
    }

    @Override
    protected void onLayout() {
        float box = width();
        float boxH = height();
        if (box <= 0 || boxH <= 0) {
            return;
        }
        boolean rtl = isRightToLeft();
        SizeTokens t = tokens();
        if (language.moved()) {
            textEpoch++;
            for (int i = 0; i < mountedCount; i++) {
                rebind(mountedSlots[i]);
            }
            recomputeFooter();
        }
        float headerH = headerHeight(t);
        float footerH = footerHeight(t);
        gutters.resolve(box, boxH, vBar, hBar, (viewW, viewH) -> {
            resolveColumns(viewW);
            return new Size(contentWidth, headerH + footerH + estimatedContentHeight(tokens()));
        });
        float w = gutters.viewportWidth(box);
        float viewH = Math.max(0, gutters.viewportHeight(boxH) - headerH - footerH);
        resolveColumns(w);
        float barT = ScrollBar.thickness();
        vBar.measure(Constraints.tight(barT, viewH));
        vBar.layoutBox(rtl ? 0 : box - barT, headerH, barT, viewH);
        hBar.measure(Constraints.tight(w, barT));
        hBar.layoutBox(rtl ? box - w : 0, boxH - barT, w, barT);
        float rowX = rtl ? box - w : 0;

        int count = rows.size();
        if (count == 0) {
            unmountAll();
            anchorIndex = 0;
            anchorTop = 0;
            vBar.refresh();
            hBar.refresh();
            return;
        }
        anchorIndex = Math.min(anchorIndex, count - 1);
        normalizeUp(t);
        normalizeDown(count, t);
        float bottom = placeDown(count, rowX, w, viewH, headerH, rtl, t);
        if (bottom < viewH && !(anchorIndex == 0 && anchorTop >= 0)) {
            anchorTop += viewH - bottom;
            normalizeUp(t);
            normalizeDown(count, t);
            bottom = placeDown(count, rowX, w, viewH, headerH, rtl, t);
        }
        recycleExcept(placedFrom, placedTo, count);
        placeKeptOutside(rowX, w, headerH, bottom, rtl, t);
        updateAverageHeight();
        vBar.refresh();
        hBar.refresh();
        if (pendingEnsureVisible >= 0) {
            int pending = Math.min(pendingEnsureVisible, count - 1);
            pendingEnsureVisible = -1;
            ensureVisible(pending);
        }
    }

    private void normalizeUp(SizeTokens t) {
        while (anchorTop > 0 && anchorIndex > 0) {
            anchorTop -= measuredHeight(anchorIndex - 1, t);
            anchorIndex--;
        }
        if (anchorIndex == 0 && anchorTop > 0) {
            anchorTop = 0;
        }
    }

    private void normalizeDown(int count, SizeTokens t) {
        while (anchorIndex < count - 1) {
            float h = measuredHeight(anchorIndex, t);
            if (anchorTop + h <= 0) {
                anchorTop += h;
                anchorIndex++;
            } else {
                break;
            }
        }
    }

    private float placeDown(int count, float rowX, float w, float viewH, float headerH,
                            boolean rtl, SizeTokens t) {
        float y = anchorTop;
        int i = anchorIndex;
        while (i < count && y < viewH) {
            float h = measuredHeight(i, t);
            placeRow(slotFor(i), headerH + y, h, rowX, w, rtl, t);
            y += h;
            i++;
        }
        placedFrom = anchorIndex;
        placedTo = i;
        return y;
    }

    /** Lays out a row's widget cells into their column boxes; a value cell has no layout. */
    private void placeRow(Slot slot, float rowY, float rowH, float rowX, float w, boolean rtl,
                          SizeTokens t) {
        slot.height = rowH;
        for (int s = 0; s < shownCount; s++) {
            int c = shownIndex[s];
            Widget widget = slot.widgets[c];
            if (widget == null) {
                continue;
            }
            float inner = Math.max(0, colW[s] - 2 * t.padH());
            Size size = widget.measure(new Constraints(0, inner, 0, Constraints.UNBOUNDED_LIMIT));
            float wx = columnLeft(s, rowX, w, rtl) + t.padH();
            if (rtl) {
                wx = columnLeft(s, rowX, w, rtl) + colW[s] - t.padH() - size.width();
            }
            widget.layoutBox(wx, rowY + (rowH - size.height()) / 2, size.width(), size.height());
        }
    }

    /** The height row {@code index} takes, realizing it if it is not yet. */
    private float measuredHeight(int index, SizeTokens t) {
        Slot slot = slotFor(index);
        if (slot == null) {
            slot = mount(index);
        }
        float lineBox = textRuler().measure("Hg", t.body()).height();
        float h = Math.max(t.controlHeight(), lineBox + 2 * t.padV());
        for (int s = 0; s < shownCount; s++) {
            Widget widget = slot.widgets[shownIndex[s]];
            if (widget != null) {
                float inner = Math.max(0, colW[s] - 2 * t.padH());
                Size size = widget.measure(
                        new Constraints(0, inner, 0, Constraints.UNBOUNDED_LIMIT));
                h = Math.max(h, size.height() + 2 * t.padV());
            }
        }
        slot.height = h;
        return h;
    }

    // ------------------------------------------------------------------------- mounting

    private Slot slotFor(int index) {
        for (int i = 0; i < mountedCount; i++) {
            if (mountedRows[i] == index) {
                return mountedSlots[i];
            }
        }
        return null;
    }

    /** Where {@code widget} sits: the mounted position of its row, or {@code -1}. */
    private int mountedPositionOf(Widget widget) {
        for (int i = 0; i < mountedCount; i++) {
            for (Widget w : mountedSlots[i].widgets) {
                if (w == widget) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Realizes row {@code index} into the mounted run, keeping the run and the children in data order. */
    private Slot mount(int index) {
        int at = 0;
        while (at < mountedCount && mountedRows[at] < index) {
            at++;
        }
        if (mountedCount == mountedRows.length) {
            mountedRows = Arrays.copyOf(mountedRows, mountedCount * 2);
            mountedSlots = Arrays.copyOf(mountedSlots, mountedCount * 2);
        }
        System.arraycopy(mountedRows, at, mountedRows, at + 1, mountedCount - at);
        System.arraycopy(mountedSlots, at, mountedSlots, at + 1, mountedCount - at);
        Slot slot = new Slot(columns.size());
        slot.row = index;
        mountedRows[at] = index;
        mountedSlots[at] = slot;
        mountedCount++;
        int insertAt = 2; // the two bars come first
        for (int i = 0; i < at; i++) {
            insertAt += mountedSlots[i].widgetCount;
        }
        T row = rows.get(modelOf(index));
        Locale locale = locale();
        for (int c = 0; c < columns.size(); c++) {
            Column<T> column = columns.get(c);
            if (column.isWidgetColumn()) {
                Widget widget = column.widgetFor(row);
                slot.widgets[c] = widget;
                add(insertAt + slot.widgetCount, widget);
                slot.widgetCount++;
            } else {
                slot.texts[c] = column.text(row, locale);
            }
        }
        return slot;
    }

    /** Re-reads a mounted row's texts after the language moved; widgets follow it themselves. */
    private void rebind(Slot slot) {
        T row = rows.get(modelOf(slot.row));
        Locale locale = locale();
        for (int c = 0; c < columns.size(); c++) {
            Column<T> column = columns.get(c);
            if (!column.isWidgetColumn()) {
                slot.texts[c] = column.text(row, locale);
                slot.shaped[c] = null;
                slot.fitted[c] = null;
            }
        }
    }

    private void unmountAll() {
        recycleExcept(0, 0, 0);
    }

    /**
     * Releases every mounted row outside {@code [from, toExclusive)} except the one holding the
     * keyboard focus, which stays mounted while its index is still below {@code count}; the
     * reason is {@code ListView}'s (ADR 039 §13.29).
     */
    private void recycleExcept(int from, int toExclusive, int count) {
        int kept = 0;
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            Slot slot = mountedSlots[i];
            boolean inRun = row >= from && row < toExclusive;
            boolean hasFocus = !inRun && containsFocus(slot);
            if (inRun || (hasFocus && row < count)) {
                mountedRows[kept] = row;
                mountedSlots[kept] = slot;
                kept++;
                continue;
            }
            for (int c = 0; c < columns.size(); c++) {
                if (slot.widgets[c] != null) {
                    remove(slot.widgets[c]);
                    slot.widgets[c] = null;
                }
            }
            slot.widgetCount = 0;
            if (hasFocus) {
                requestFocus();
            }
        }
        for (int i = kept; i < mountedCount; i++) {
            mountedSlots[i] = null;
        }
        mountedCount = kept;
    }

    private void placeKeptOutside(float rowX, float w, float headerH, float bottom, boolean rtl,
                                  SizeTokens t) {
        if (mountedCount == placedTo - placedFrom) {
            return;
        }
        float avg = avgRowHeight(t);
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            if (row >= placedFrom && row < placedTo) {
                continue;
            }
            float h = measuredHeight(row, t);
            float y = row < placedFrom
                    ? anchorTop - (placedFrom - 1 - row) * avg - h
                    : bottom + (row - placedTo) * avg;
            placeRow(mountedSlots[i], headerH + y, h, rowX, w, rtl, t);
        }
    }

    private boolean isPlaced(int index) {
        return index >= placedFrom && index < placedTo;
    }

    private void updateAverageHeight() {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < mountedCount; i++) {
            float h = mountedSlots[i].height;
            if (h > 0) {
                sum += h;
                n++;
            }
        }
        if (n > 0) {
            measuredRowHeight = sum / n;
        }
    }

    private boolean containsFocus(Slot slot) {
        Widget focused = scene() != null ? scene().focusedWidget() : null;
        for (Widget w = focused; w != null; w = w.parent()) {
            for (int c = 0; c < columns.size(); c++) {
                if (slot.widgets[c] == w) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The top of row {@code index}'s box in this widget's coordinates, or NaN when unrealized. */
    private float rowTop(int index) {
        float y = anchorTop + rowsTop();
        for (int i = anchorIndex; i < placedTo; i++) {
            Slot slot = slotFor(i);
            if (slot == null) {
                return Float.NaN;
            }
            if (i == index) {
                return y;
            }
            y += slot.height;
        }
        return Float.NaN;
    }

    private void ensureVisible(int index) {
        if (height() <= 0) {
            pendingEnsureVisible = index;
            return;
        }
        float top = isPlaced(index) ? rowTop(index) : Float.NaN;
        if (!Float.isNaN(top)) {
            float viewTop = rowsTop();
            float viewBottom = viewTop + rowsViewportHeight();
            float bottom = top + slotFor(index).height;
            if (top < viewTop) {
                anchorTop -= top - viewTop;
            } else if (bottom > viewBottom) {
                anchorTop -= bottom - viewBottom;
            } else {
                return;
            }
        } else {
            anchorIndex = index;
            anchorTop = 0;
        }
        markNeedsLayout();
    }

    private void ensureColumnVisible(int s) {
        if (s < 0 || s >= shownCount) {
            return;
        }
        float viewW = gutters.viewportWidth(width());
        float left = colX[s] - offsetX;
        float right = left + colW[s];
        if (left < 0) {
            scrollBy(left, 0);
        } else if (right > viewW) {
            scrollBy(Math.min(left, right - viewW), 0);
        }
    }

    // ------------------------------------------------------------------- sort and view

    private int modelOf(int viewIndex) {
        return view == null ? viewIndex : view[viewIndex];
    }

    private int viewOf(int modelIndex) {
        if (view == null) {
            return modelIndex;
        }
        if (inverse == null) {
            inverse = new int[view.length];
            for (int v = 0; v < view.length; v++) {
                inverse[view[v]] = v;
            }
        }
        return inverse[modelIndex];
    }

    /** Rebuilds the permutation from the sort, or drops it in the model's order. */
    private void resort() {
        inverse = null;
        if (sortColumn == null || sortOrder == SortOrder.NONE || onSortRequest != null
                || rows.size() < 2) {
            view = null;
            return;
        }
        int n = rows.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Locale locale = locale();
        Locale enclosing = I18n.pushScope(locale);
        try {
            Collator collator = I18n.collator();
            Column<T> column = sortColumn;
            int sign = sortOrder == SortOrder.DESCENDING ? -1 : 1;
            List<T> data = rows;
            Arrays.sort(order, (a, b) -> {
                int c = column.compare(data.get(a), data.get(b), collator, locale);
                return c != 0 ? sign * c : Integer.compare(a, b);
            });
        } finally {
            I18n.popScope(enclosing);
        }
        view = new int[n];
        for (int i = 0; i < n; i++) {
            view[i] = order[i];
        }
    }

    private void headerClicked(int s) {
        Column<T> column = columns.get(shownIndex[s]);
        if (!column.isSortable()) {
            return;
        }
        SortOrder next = column == sortColumn ? sortOrder.next() : SortOrder.ASCENDING;
        if (onSortRequest != null) {
            sortColumn = next == SortOrder.NONE ? null : column;
            sortOrder = next;
            invalidate();
            onSortRequest.accept(column, next);
            return;
        }
        setSort(column, next);
    }

    // ---------------------------------------------------------------------- selection core

    /** Selects one model row alone and moves the lead and the focus cell to it. */
    private void selectOnly(int modelIndex, int viewIndex, boolean reveal) {
        focusRow = viewIndex;
        rangeAnchor = viewIndex;
        if (reveal) {
            ensureVisible(viewIndex);
        }
        if (selectionMode == SelectionMode.NONE) {
            invalidate();
            return;
        }
        if (lead == modelIndex && selected.cardinality() == 1 && selected.get(modelIndex)) {
            invalidate();
            return;
        }
        selected.clear();
        selected.set(modelIndex);
        lead = modelIndex;
        invalidate();
        onSelect.run();
    }

    /** Extends the selection from the range anchor to {@code viewIndex}, in MULTI. */
    private void selectRange(int viewIndex) {
        int from = rangeAnchor < 0 ? viewIndex : rangeAnchor;
        selected.clear();
        for (int v = Math.min(from, viewIndex); v <= Math.max(from, viewIndex); v++) {
            selected.set(modelOf(v));
        }
        lead = modelOf(viewIndex);
        focusRow = viewIndex;
        ensureVisible(viewIndex);
        invalidate();
        onSelect.run();
    }

    /** Toggles one row's membership, in MULTI. */
    private void toggle(int viewIndex) {
        int model = modelOf(viewIndex);
        selected.flip(model);
        lead = selected.get(model) ? model : (selected.isEmpty() ? -1 : lead == model
                ? selected.length() - 1 : lead);
        focusRow = viewIndex;
        rangeAnchor = viewIndex;
        ensureVisible(viewIndex);
        invalidate();
        onSelect.run();
    }

    /** What every key and click goes through; an index past an end lands on the end. */
    private void moveFocusRow(int viewIndex, int modifiers) {
        int count = rows.size();
        if (count == 0) {
            return;
        }
        int v = Math.min(Math.max(0, viewIndex), count - 1);
        boolean shift = (modifiers & Keys.MOD_SHIFT) != 0;
        if (selectionMode == SelectionMode.MULTI && shift) {
            selectRange(v);
        } else {
            selectOnly(modelOf(v), v, true);
        }
    }

    private int rowsPerPage(SizeTokens t) {
        return Math.max(1, (int) (rowsViewportHeight() / Math.max(1, avgRowHeight(t))));
    }

    // ------------------------------------------------------------------------------- paint

    @Override
    protected boolean clipsChildren() {
        return true;
    }

    /** The header's font, {@code ==}-stable across passes so the glyph cache hits. */
    private Font headerFont(SizeTokens t) {
        Font base = t.label();
        if (base != headerBase) {
            headerBase = base;
            headerDerived = base.bold();
        }
        return headerDerived;
    }

    private ShapedText shapedCell(Slot slot, int c, TextRuler ruler, Font font) {
        String text = slot.texts[c];
        ShapedText.Direction base = ShapedText.Direction.of(text, neutralBase());
        ShapedText held = slot.shaped[c];
        if (held == null || !held.matches(text, font, base, ruler)) {
            held = ruler.shape(text, font, base);
            slot.shaped[c] = held;
            slot.fitted[c] = null;
        }
        return held;
    }

    private ShapedText fittedCell(Slot slot, int c, ShapedText shaped, float available,
                                  TextRuler ruler) {
        if (shaped.metrics().width() <= available) {
            return shaped;
        }
        ShapedText fitted = slot.fitted[c];
        if (fitted == null || slot.fittedWidth[c] != available) {
            fitted = ruler.ellipsize(shaped, available);
            slot.fitted[c] = fitted;
            slot.fittedWidth[c] = available;
        }
        return fitted;
    }

    private ShapedText shapedFooter(int c, String text, TextRuler ruler, Font font) {
        ShapedText.Direction base = ShapedText.Direction.of(text, neutralBase());
        ShapedText held = footerShaped[c];
        if (held == null || !held.matches(text, font, base, ruler)) {
            held = ruler.shape(text, font, base);
            footerShaped[c] = held;
        }
        return held;
    }

    private ShapedText shapedHeader(int c, TextRuler ruler, Font font) {
        String text = columns.get(c).title().get();
        ShapedText.Direction base = ShapedText.Direction.of(text, neutralBase());
        ShapedText held = headerShaped[c];
        if (held == null || !held.matches(text, font, base, ruler)) {
            held = ruler.shape(text, font, base);
            headerShaped[c] = held;
        }
        return held;
    }

    /** Where a line of {@code textWidth} starts across a cell of {@code cellW} at {@code left}. */
    private float textX(Column.Alignment alignment, float left, float cellW, float textWidth,
                        float padH, boolean rtl) {
        boolean atLeft = (alignment == Column.Alignment.START) != rtl;
        return switch (alignment) {
            case CENTER -> left + (cellW - textWidth) / 2;
            case START, END -> atLeft ? left + padH : left + cellW - padH - textWidth;
        };
    }

    private void tintsFor(Theme theme) {
        if (theme != tintTheme) {
            tintTheme = theme;
            selectionTint = theme.primary.withAlpha(0.18f);
            // The text colour, faintly, and not the raised surface: the text always contrasts
            // with the surface it sits on, in every palette, while a raised surface sits one or
            // two greys away from the flat one in the light palettes and half of that is nothing.
            stripeTint = theme.text.withAlpha(0.045f);
        }
    }

    @Override
    protected void paintChildren(Canvas canvas) {
        Theme theme = Theme.current();
        tintsFor(theme);
        SizeTokens t = theme.tokensFor(this);
        TextRuler ruler = textRuler();
        boolean rtl = isRightToLeft();
        float box = width();
        float w = gutters.viewportWidth(box);
        float rowX = rowsLeft();
        float headerH = headerHeight(t);
        float viewH = rowsViewportHeight();
        float padH = t.padH();
        Font body = t.body();

        canvas.save();
        try {
            canvas.clipRect(rowX, headerH, w, viewH);
            // Rows in data order: stripe, selection, then each shown column's text or widget.
            for (int i = 0; i < mountedCount; i++) {
                int row = mountedRows[i];
                Slot slot = mountedSlots[i];
                float top = rowTop(row);
                if (Float.isNaN(top) || top >= headerH + viewH || top + slot.height <= headerH) {
                    continue; // the focused row a scroll spared, kept outside the viewport
                }
                int model = modelOf(row);
                if (selected.get(model)) {
                    canvas.fillRect(rowX, top, w, slot.height, selectionTint);
                } else if (striped && (row & 1) == 1) {
                    canvas.fillRect(rowX, top, w, slot.height, stripeTint);
                }
                for (int s = 0; s < shownCount; s++) {
                    float left = columnLeft(s, rowX, w, rtl);
                    if (left >= rowX + w || left + colW[s] <= rowX) {
                        continue; // out of the horizontal viewport
                    }
                    int c = shownIndex[s];
                    Widget widget = slot.widgets[c];
                    if (widget != null) {
                        canvas.save();
                        try {
                            canvas.translate(widget.x(), widget.y());
                            widget.paintWidget(canvas);
                        } finally {
                            canvas.restore();
                        }
                        continue;
                    }
                    if (slot.texts[c].isEmpty()) {
                        continue;
                    }
                    ShapedText shaped = shapedCell(slot, c, ruler, body);
                    float available = Math.max(0, colW[s] - 2 * padH);
                    ShapedText line = fittedCell(slot, c, shaped, available, ruler);
                    float tw = line.metrics().width();
                    float x = textX(columns.get(c).alignment(), left, colW[s], tw, padH, rtl);
                    float baseline = top + (slot.height - line.metrics().height()) / 2
                            + line.metrics().ascent();
                    canvas.drawText(line, x, baseline, theme.text);
                }
                if (row == focusRow && isFocused() && focusColumn < shownCount) {
                    float left = columnLeft(focusColumn, rowX, w, rtl);
                    float inset = Strokes.FOCUS_RING_THIN;
                    canvas.drawRoundRect(left + inset, top + inset,
                            colW[focusColumn] - 2 * inset, slot.height - 2 * inset,
                            t.radiusSmall(), Strokes.FOCUS_RING_THIN, theme.focusRing);
                }
            }
        } finally {
            canvas.restore();
        }

        if (footerShown) {
            float footerH = footerHeight(t);
            float top = footerTop();
            canvas.save();
            try {
                canvas.clipRect(rowX, top, w, footerH);
                canvas.fillRect(rowX, top, w, footerH, theme.surfaceRaised);
                canvas.drawLine(rowX, top + Strokes.HALF_PIXEL_INSET, rowX + w,
                        top + Strokes.HALF_PIXEL_INSET, Strokes.HAIRLINE, theme.outline);
                Font font = headerFont(t);
                for (int s = 0; s < shownCount; s++) {
                    float left = columnLeft(s, rowX, w, rtl);
                    int c = shownIndex[s];
                    String text = footerTexts.length > c ? footerTexts[c] : null;
                    if (text == null || text.isEmpty() || left >= rowX + w
                            || left + colW[s] <= rowX) {
                        continue;
                    }
                    ShapedText shaped = shapedFooter(c, text, ruler, font);
                    float available = Math.max(0, colW[s] - 2 * padH);
                    ShapedText line = shaped.metrics().width() > available
                            ? ruler.ellipsize(shaped, available) : shaped;
                    float tw = line.metrics().width();
                    float x = textX(columns.get(c).alignment(), left, colW[s], tw, padH, rtl);
                    float baseline = top + (footerH - line.metrics().height()) / 2
                            + line.metrics().ascent();
                    canvas.drawText(line, x, baseline, theme.text);
                }
            } finally {
                canvas.restore();
            }
        }

        if (showHeader) {
            canvas.save();
            try {
                canvas.clipRect(rowX, 0, w, headerH);
                canvas.fillRect(rowX, 0, w, headerH, theme.surfaceRaised);
                Font font = headerFont(t);
                for (int s = 0; s < shownCount; s++) {
                    float left = columnLeft(s, rowX, w, rtl);
                    if (left >= rowX + w || left + colW[s] <= rowX) {
                        continue;
                    }
                    int c = shownIndex[s];
                    Column<T> column = columns.get(c);
                    ShapedText shaped = shapedHeader(c, ruler, font);
                    float indicator = column == sortColumn && sortOrder != SortOrder.NONE
                            ? t.gapIcon() + t.chevronHalfW() * 2 : 0;
                    float available = Math.max(0, colW[s] - 2 * padH - indicator);
                    ShapedText line = shaped.metrics().width() > available
                            ? ruler.ellipsize(shaped, available) : shaped;
                    float tw = line.metrics().width();
                    float x = textX(column.alignment(), left, colW[s] - indicator, tw, padH, rtl);
                    if (rtl) {
                        x += indicator;
                    }
                    float baseline = (headerH - line.metrics().height()) / 2
                            + line.metrics().ascent();
                    canvas.drawText(line, x, baseline, theme.textMuted);
                    if (indicator > 0) {
                        // A chevron after the title on the reading side: up for ascending.
                        float half = t.chevronHalfW();
                        float cx = rtl ? left + padH + half : left + colW[s] - padH - half;
                        float cy = headerH / 2;
                        float dy = sortOrder == SortOrder.ASCENDING ? half / 2 : -half / 2;
                        canvas.drawLine(cx - half, cy + dy, cx, cy - dy, Strokes.ARROW_PEN,
                                theme.textMuted);
                        canvas.drawLine(cx, cy - dy, cx + half, cy + dy, Strokes.ARROW_PEN,
                                theme.textMuted);
                    }
                    // The divider at the column's trailing edge, which is where a drag resizes it.
                    float edge = rtl ? left : left + colW[s];
                    canvas.drawLine(edge, t.padV(), edge, headerH - t.padV(), Strokes.HAIRLINE,
                            hoverDivider == s || dragColumn == s ? theme.focusRing : theme.outline);
                }
                canvas.drawLine(rowX, headerH - Strokes.HALF_PIXEL_INSET, rowX + w,
                        headerH - Strokes.HALF_PIXEL_INSET, Strokes.HAIRLINE, theme.outline);
            } finally {
                canvas.restore();
            }
        }

        paintBar(canvas, vBar);
        paintBar(canvas, hBar);
    }

    private static void paintBar(Canvas canvas, ScrollBar bar) {
        canvas.save();
        try {
            canvas.translate(bar.x(), bar.y());
            bar.paintWidget(canvas);
        } finally {
            canvas.restore();
        }
    }

    @Override
    public Widget hitTest(float localX, float localY) {
        if (!isVisible() || !isEnabled()
                || localX < 0 || localY < 0 || localX >= width() || localY >= height()) {
            return null;
        }
        Widget hit = vBar.hitTest(localX - vBar.x(), localY - vBar.y());
        if (hit != null) {
            return hit;
        }
        hit = hBar.hitTest(localX - hBar.x(), localY - hBar.y());
        if (hit != null) {
            return hit;
        }
        if (localY >= rowsTop() && localY < rowsTop() + rowsViewportHeight()) {
            for (Widget child : children()) {
                if (child == vBar || child == hBar) {
                    continue;
                }
                if (child.y() + child.height() <= rowsTop()
                        || child.y() >= rowsTop() + rowsViewportHeight()) {
                    continue;
                }
                Widget inner = child.hitTest(localX - child.x(), localY - child.y());
                if (inner != null) {
                    return inner;
                }
            }
        }
        return this;
    }

    // ------------------------------------------------------------------------------- input

    /** The shown column under local {@code x}, or {@code -1}. */
    private int columnAt(float x) {
        boolean rtl = isRightToLeft();
        float w = gutters.viewportWidth(width());
        float rowX = rowsLeft();
        for (int s = 0; s < shownCount; s++) {
            float left = columnLeft(s, rowX, w, rtl);
            if (x >= left && x < left + colW[s]) {
                return s;
            }
        }
        return -1;
    }

    /** The shown column whose trailing divider lies within the band around local {@code x}. */
    private int dividerAt(float x) {
        boolean rtl = isRightToLeft();
        float w = gutters.viewportWidth(width());
        float rowX = rowsLeft();
        for (int s = 0; s < shownCount; s++) {
            float left = columnLeft(s, rowX, w, rtl);
            float edge = rtl ? left : left + colW[s];
            if (Math.abs(x - edge) <= RESIZE_BAND) {
                return s;
            }
        }
        return -1;
    }

    /** The view row whose box holds local {@code y}, or {@code -1}. */
    private int rowAt(float y) {
        if (y < rowsTop() || y >= rowsTop() + rowsViewportHeight()) {
            return -1;
        }
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            float top = rowTop(row);
            if (!Float.isNaN(top) && y >= top && y < top + mountedSlots[i].height) {
                return row;
            }
        }
        return -1;
    }

    @Override
    public Cursor cursor() {
        return hoverDivider >= 0 || dragColumn >= 0 ? Cursor.RESIZE_EW : super.cursor();
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        float x = sceneToLocalX(event.x());
        float y = sceneToLocalY(event.y());
        switch (event.type()) {
            case WHEEL -> {
                boolean sideways = event.scrollX() != 0
                        || (event.modifiers() & Keys.MOD_SHIFT) != 0;
                float dx = sideways ? -(event.scrollX() != 0 ? event.scrollX() : event.scrollY())
                        * Strokes.WHEEL_STEP : 0;
                float dy = sideways ? 0 : -event.scrollY() * Strokes.WHEEL_STEP;
                boolean canY = estimatedContentHeight(tokens()) > rowsViewportHeight();
                boolean canX = contentWidth > gutters.viewportWidth(width());
                if ((dy != 0 && canY) || (dx != 0 && canX)) {
                    scrollBy(canX ? dx : 0, canY ? dy : 0);
                    event.consume();
                }
            }
            case MOVE -> {
                vBar.onHostActivity();
                hBar.onHostActivity();
                int divider = showHeader && y < rowsTop() ? dividerAt(x) : -1;
                if (divider != hoverDivider) {
                    hoverDivider = divider;
                    invalidate();
                }
            }
            case DRAG -> {
                vBar.onHostActivity();
                hBar.onHostActivity();
                if (dragColumn >= 0) {
                    float delta = x - dragStartX;
                    if (isRightToLeft()) {
                        delta = -delta;
                    }
                    columns.get(shownIndex[dragColumn]).dragTo(dragStartWidth + delta);
                    markNeedsLayout();
                    invalidate();
                    event.consume();
                }
            }
            case PRESS -> {
                if (event.button() != Keys.MOUSE_LEFT) {
                    return;
                }
                requestFocus();
                if (showHeader && y < rowsTop()) {
                    int divider = dividerAt(x);
                    if (divider >= 0) {
                        dragColumn = divider;
                        dragStartX = x;
                        dragStartWidth = colW[divider];
                    } else {
                        int s = columnAt(x);
                        if (s >= 0) {
                            headerClicked(s);
                        }
                    }
                    event.consume();
                    return;
                }
                int row = rowAt(y);
                if (row < 0) {
                    event.consume();
                    return;
                }
                int s = columnAt(x);
                if (s >= 0) {
                    focusColumn = s;
                }
                int mods = event.modifiers();
                boolean command = (mods & Accelerator.commandModifier()) != 0;
                boolean shift = (mods & Keys.MOD_SHIFT) != 0;
                long now = sceneNanos();
                boolean second = row == lastPressRow && now - lastPressNanos < DOUBLE_CLICK_NANOS;
                lastPressRow = row;
                lastPressNanos = second ? 0 : now;
                if (selectionMode == SelectionMode.MULTI && command) {
                    toggle(row);
                } else if (selectionMode == SelectionMode.MULTI && shift) {
                    selectRange(row);
                } else {
                    selectOnly(modelOf(row), row, false);
                }
                if (second && !command && !shift) {
                    activate();
                }
                event.consume();
            }
            case RELEASE -> {
                if (dragColumn >= 0) {
                    dragColumn = -1;
                    invalidate();
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        int mods = event.modifiers();
        boolean rtl = isRightToLeft();
        switch (event.key()) {
            case Keys.DOWN -> consumeAnd(event, () -> moveFocusRow(
                    focusRow < 0 ? anchorIndex : focusRow + 1, mods));
            case Keys.UP -> consumeAnd(event, () -> moveFocusRow(
                    focusRow < 0 ? anchorIndex : focusRow - 1, mods));
            case Keys.PAGE_DOWN -> consumeAnd(event, () -> moveFocusRow(
                    (focusRow < 0 ? anchorIndex : focusRow) + rowsPerPage(tokens()), mods));
            case Keys.PAGE_UP -> consumeAnd(event, () -> moveFocusRow(
                    (focusRow < 0 ? anchorIndex : focusRow) - rowsPerPage(tokens()), mods));
            case Keys.HOME -> consumeAnd(event, () -> moveFocusRow(0, mods));
            case Keys.END -> consumeAnd(event, () -> moveFocusRow(rows.size() - 1, mods));
            case Keys.LEFT -> consumeAnd(event, () -> moveFocusColumn(rtl ? 1 : -1));
            case Keys.RIGHT -> consumeAnd(event, () -> moveFocusColumn(rtl ? -1 : 1));
            case Keys.SPACE -> {
                if (selectionMode == SelectionMode.MULTI && focusRow >= 0) {
                    consumeAnd(event, () -> toggle(focusRow));
                }
            }
            case Keys.A -> {
                if ((mods & Accelerator.commandModifier()) != 0
                        && selectionMode == SelectionMode.MULTI) {
                    consumeAnd(event, this::selectAll);
                }
            }
            case Keys.ENTER -> {
                if (lead >= 0) {
                    consumeAnd(event, this::activate);
                }
            }
            default -> {
            }
        }
    }

    private static void consumeAnd(KeyEvent event, Runnable action) {
        event.consume();
        action.run();
    }

    private void moveFocusColumn(int delta) {
        if (shownCount == 0) {
            return;
        }
        int next = Math.min(Math.max(0, focusColumn + delta), shownCount - 1);
        if (next == focusColumn) {
            return;
        }
        focusColumn = next;
        ensureColumnVisible(next);
        invalidate();
    }

    @Override
    protected void onFocusGained() {
        // The focus cell is not placed until a key asks for one: the first Down then lands on
        // the top row the way it does in ListView, rather than on the row below it.
        invalidate();
    }

    @Override
    protected void onFocusLost() {
        invalidate();
    }

    // ---------------------------------------------------------------------- accessibility

    /** The row count the rows below are numbered against, read once at the top of a publish. */
    private int describedRowCount;

    /**
     * One table node: its shape, its selection and its scroll, then a header group with a
     * header cell per shown column, then one row per realized data row with a cell per shown
     * column; ADR 041 §7. Nothing is formatted here: every name is a string a slot already
     * holds, handed over with the row's witness, or the column's own {@code I18nString}.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        describedRowCount = rows.size();
        SizeTokens t = tokens();
        boolean rtl = isRightToLeft();
        float w = gutters.viewportWidth(width());
        float rowX = rowsLeft();
        float headerH = headerHeight(t);
        float viewH = rowsViewportHeight();
        float contentH = estimatedContentHeight(t);

        a.role(Accessible.Role.TABLE);
        a.table(describedRowCount, shownCount);
        a.selection(selectionMode == SelectionMode.MULTI, false);
        a.scrollFrom(offsetX, Math.max(0, contentWidth - w), w, contentWidth,
                estimatedOffset(t), Math.max(0, contentH - viewH), viewH, contentH);
        if (lead >= 0) {
            a.action(Accessible.Action.PRESS);
        }

        if (showHeader) {
            a.child(HEADER_KEY);
            a.bounds(rowX, 0, w, headerH);
            a.role(Accessible.Role.GROUP);
            for (int s = 0; s < shownCount; s++) {
                int c = shownIndex[s];
                float left = columnLeft(s, rowX, w, rtl);
                a.child(c);
                // In this widget's coordinates, as every synthetic box is, nested or not.
                a.bounds(left, 0, colW[s], headerH);
                a.role(Accessible.Role.COLUMN_HEADER);
                a.name(columns.get(c).title(), Accessible.NameFrom.CONTENT);
                a.cell(-1, s);
                if (left + colW[s] <= rowX || left >= rowX + w) {
                    a.offScreen();
                }
                a.endChild();
            }
            a.endChild();
        }

        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            Slot slot = mountedSlots[i];
            int model = modelOf(row);
            float top = rowTop(row);
            boolean shown = !Float.isNaN(top);
            if (!shown) {
                // The focused row a scroll spared: published where the estimate puts it.
                top = row < placedFrom ? headerH - slot.height : headerH + viewH;
            }
            a.child(model);
            a.bounds(rowX, top, w, slot.height);
            a.role(Accessible.Role.ROW);
            a.selectionItem(selected.get(model), row + 1, describedRowCount);
            if (selectionMode != SelectionMode.NONE) {
                a.action(Accessible.Action.SELECT);
            }
            if (!shown || top + slot.height <= headerH || top >= headerH + viewH) {
                a.offScreen();
            }
            for (int s = 0; s < shownCount; s++) {
                int c = shownIndex[s];
                if (slot.widgets[c] != null) {
                    continue; // a real child, described in onAccessibilityChild
                }
                float left = columnLeft(s, rowX, w, rtl);
                a.child(c);
                a.bounds(left, top, colW[s], slot.height);
                a.role(Accessible.Role.CELL);
                a.name(slot.texts[c], textEpoch, Accessible.NameFrom.CONTENT);
                a.cell(row, s);
                if (row == focusRow && s == focusColumn) {
                    a.state(Accessible.State.ACTIVE);
                }
                if (left + colW[s] <= rowX || left >= rowX + w) {
                    a.offScreen();
                }
                a.endChild();
            }
            a.endChild();
        }

        if (footerShown) {
            float footerH = footerHeight(t);
            float top = footerTop();
            a.child(FOOTER_KEY);
            a.bounds(rowX, top, w, footerH);
            a.role(Accessible.Role.GROUP);
            for (int s = 0; s < shownCount; s++) {
                int c = shownIndex[s];
                if (footerTexts[c] == null) {
                    continue;
                }
                float left = columnLeft(s, rowX, w, rtl);
                a.child(c);
                a.bounds(left, top, colW[s], footerH);
                a.role(Accessible.Role.CELL);
                a.name(footerTexts[c], textEpoch, Accessible.NameFrom.CONTENT);
                a.cell(-2, s);
                if (left + colW[s] <= rowX || left >= rowX + w) {
                    a.offScreen();
                }
                a.endChild();
            }
            a.endChild();
        }
    }

    @Override
    protected void onAccessibilityChild(Widget child, Accessibility a) {
        if (child == vBar || child == hBar) {
            return;
        }
        int at = mountedPositionOf(child);
        if (at < 0) {
            return;
        }
        Slot slot = mountedSlots[at];
        int c = 0;
        while (slot.widgets[c] != child) {
            c++;
        }
        int s = 0;
        while (s < shownCount && shownIndex[s] != c) {
            s++;
        }
        int model = modelOf(slot.row);
        a.key(WIDGET_KEY | ((long) model << 16) | c);
        a.cell(slot.row, s);
    }

    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (action == Accessible.Action.PRESS && lead >= 0) {
            activate();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onSyntheticAction(long key, Accessible.Action action,
                                        Accessible.Argument arg) {
        if (action == Accessible.Action.SELECT && selectionMode != SelectionMode.NONE
                && key >= 0 && key < rows.size()) {
            selectOnly((int) key, viewOf((int) key), true);
            return true;
        }
        return false;
    }
}

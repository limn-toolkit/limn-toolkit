package limn.components.table;

import limn.components.SelectionMode;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.CellFacet;
import limn.backend.Cursor;
import limn.components.internal.a11y.GridAccessibility;
import limn.components.internal.Rows;
import limn.components.internal.a11y.RowsAccessibility;
import limn.components.Accelerator;
import limn.components.ScrollBar;
import limn.components.ScrollGutters;
import limn.components.SizeTokens;
import limn.components.Strokes;
import limn.components.Theme;
import limn.components.Label;
import limn.components.internal.RowsPlaceholder;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.LanguageWitness;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.math.Vec2;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.transfer.Transfer;
import limn.transfer.Offer;
import limn.transfer.DropAction;
import limn.transfer.Drag;
import limn.transfer.DataFormat;
import limn.scene.event.DropEvent;
import limn.scene.event.DragStartEvent;
import limn.scene.event.DragEvent;
import limn.scene.event.MouseEvent;
import limn.scene.internal.SceneAccess;
import org.jspecify.annotations.Nullable;

import java.text.Collator;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * A table: {@link Column}s over a list the application owns, virtualized on both axes.
 *
 * <p>The rows are a {@code List<T>} held by reference and re-read on {@link #refresh()}; nothing is
 * copied and nothing is read until it is on screen. Vertically the table keeps the anchor-and-walk
 * {@code ListView} keeps &mdash; which row sits at which y, a walk from there until the viewport is
 * full, a mean height as the scroll estimate &mdash; over <b>row slots</b> rather than widgets: a
 * value cell is its formatted text, shaped once and held, and only a {@link Column#widget} cell is
 * a real child, mounted and recycled with its row. Horizontally the columns are a band: widths are
 * resolved once per layout, and only the columns that intersect the viewport are shaped and
 * painted. The header is pinned.
 *
 * <p><b>Selection</b> is by row and by <b>model</b> index, in one of three
 * {@linkplain SelectionMode modes}, and it survives a sort because a sort is a
 * {@linkplain #setSort permutation} over the application's list and never a reordering of it.
 * A row is its record: across a {@link #refresh()} the selection follows the records it named,
 * wherever the list holds them now, by {@code equals} or by a {@linkplain #rowKey key}.
 * Separately, the arrow keys move a <b>focus cell</b>, which is what a screen reader's cursor
 * stands on. Enter and a double click {@linkplain #onActivate activate} the cursor row — the
 * focus cell's row, which is the lead in {@code SINGLE} and may differ from it in {@code MULTI}.
 *
 * <p><b>Cells are not edited in place, and will not be.</b> In-place editing is a spreadsheet's
 * interaction and reads as one everywhere else: a field that appears where a value was, a save on a
 * keystroke the user did not mean as one, an error with nowhere to stand. A table is for reading,
 * comparing, sorting and choosing; editing a record wants the record whole. Open a {@code Dialog}
 * or a panel with the record as a form from {@link #onActivate}, keep a form beside the table
 * following its selection through {@link #observeChanges} for master-and-detail, put a switch or a
 * button in a {@link Column#widget} cell for the one-gesture cases, or act on the whole
 * {@linkplain SelectionMode#MULTI selection} at once; then change the list and {@link #refresh()}.
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
 * <p><b>Inside a scroller</b> the table scrolls itself first and hands the wheel on at either
 * end: a detent that moves neither offset — the rows already at the top or the bottom, the
 * columns at either edge, or a table that fits — is left unconsumed and reaches the scroll
 * pane that holds it. Under an unbounded height the table prefers {@link #setVisibleRows} rows
 * of the step's seed height, plus its header and footer.
 *
 * @param <T> the row type
 */
public final class Table<T> extends Widget<Table<T>> implements Scrollable {

    /**
     * Rows of intrinsic height when the height axis is unbounded, until {@link #setVisibleRows}
     * says otherwise; a count, not a length: it multiplies the step's seed row height.
     */
    private static final int VISIBLE_ROWS_HINT = 8;

    /**
     * How many seed rows tall the rows' viewport prefers to be under an unbounded height.
     * Multiplied by the token's seed and never by the realized average: the average moves as rows
     * of other heights scroll in, and a preference that moved with it re-laid out the parent on
     * every such scroll and made a table inside a scroll pane jitter.
     */
    private int visibleRows = VISIBLE_ROWS_HINT;
    /** How far either side of a header divider a press starts a resize, in points. */
    private static final float RESIZE_BAND = 4;
    /** The synthetic key of the header row's group node. */
    private static final long HEADER_KEY = -1;
    /** The synthetic key of the footer row's group node. */
    private static final long FOOTER_KEY = -2;
    /**
     * The synthetic keys a reader's verb arrives with, told apart by a bit each: a data row is its
     * record's <b>row identity</b> ({@link #rowIdOf}); a cell is
     * {@code CELL_KEY | identity << COLUMN_BITS | column}, so a verb on a cell names its row as
     * well as its column; a header cell is {@code HEADER_CELL_KEY | column}, a footer cell
     * {@code FOOTER_CELL_KEY | column}. Until 2026-09-14 a cell and a header cell were keyed by
     * their column alone, which a verb could not tell from a row's index: a select on cell (0, 1)
     * selected row 1. Until 2026-09-15 a row was keyed by its model index, so an insert above it
     * gave its node to another record and a verb sent before the insert acted on that record.
     */
    private static final int COLUMN_BITS = 20;
    private static final long COLUMN_MASK = (1L << COLUMN_BITS) - 1;
    /** Row identities are below this: forty bits between the column and the three kind bits. */
    private static final long ROW_ID_LIMIT = 1L << 40;
    private static final long CELL_KEY = 1L << 60;
    private static final long HEADER_CELL_KEY = 1L << 61;
    private static final long FOOTER_CELL_KEY = 1L << 62;

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
    private Runnable onSelect;
    private IntConsumer onActivate;
    // A row is its record (decision 23 of 2026-09-14): the selection, the lead, the focus row and
    // the anchor are addressed by model index between two refreshes and followed by record
    // across one. The records are held as keys, taken when a row enters one of the four, because
    // the list is the application's and has already changed when refresh() is called.
    private Function<? super T, ?> rowKey;
    private final Records records = new Records();
    // A row's accessible identity follows its record (decision 23 of 2026-09-14, the node half,
    // done 2026-09-15): model row m is published as rowIdBase + m unless an override names it.
    // A refresh that moved a followed record, or that cannot vouch for every identity published
    // since the last one, gives each followed record its identity where the record is now, as an
    // override, and issues every other row a fresh range past every identity handed out, so an
    // identity once published names its record or nothing, never another (semantics 8).
    private long rowIdBase;
    private long rowIdHighWater;
    private int[] overrideModels = new int[0];
    private long[] overrideIds = new long[0];
    private int overrideCount;
    // The followed rows: what a reader may still hold a node of, and what a refresh follows by
    // record so those nodes stay on their records. The rows the last describe published, or,
    // until the next describe, the rows the last refresh followed and found, at the rows they
    // stand at now, so a second refresh before a frame follows them again.
    private int publishedCount;
    private int[] publishedModels = new int[16];
    private long[] publishedIds = new long[16];
    private Object[] publishedKeys = new Object[16];
    private int[] publishedOrdinals = new int[16];
    // The followed rows are a refresh's, not a describe's: the next describe checks that it
    // publishes each of them, since a reader may hold one it leaves out.
    private boolean publishedByRefresh;
    // An identity published since the identities were last settled is no longer followed: a
    // published row a scroll released, or a followed row a describe left out. The next refresh
    // cannot tell where its record went, so it retires every identity it does not follow.
    private boolean publishedLeftBehind;
    // Each row's occurrence among the equal rows before it, for a table without a rowKey, over the
    // list as it stands since the last setRows, refresh or rowKey: rows [0, occurrencesRead) read
    // once each and chained by the hash of their key. Until 2026-09-15 every describe that realized
    // a row read every row above it again, so a reader scrolling to the bottom of a long table cost
    // the square of its length.
    private int occurrencesRead;
    private int[] occurrence = new int[0];
    private int[] occurrenceHash = new int[0];
    // The nearest row before with the same hash, or -1; and by hash, 1 + the last row read with
    // it, or 0 for none.
    private int[] occurrencePrevious = new int[0];
    private int[] occurrenceTable = new int[0];
    // The header the last click asked to sort by, and the order it asked for: what onSortRequest
    // is told, read back here because a request for the model's order leaves sortColumn null.
    private Column<T> sortRequestColumn;
    private SortOrder sortRequestOrder = SortOrder.NONE;
    // While the handler runs: a refresh() it calls is a sort, and reveals the focus row as one.
    private boolean answeringSortRequest;

    // The focus cell: a view row and a shown column, or -1 before anything was focused.
    private int focusRow = -1;
    private int focusColumn;
    // The column the focus cell is on, by identity (its index among columns(), hidden ones
    // included), or -1 before a layout resolved one: a shown index alone went stale when a
    // column was hidden and the cursor sat on no shown column at all. resolveColumns brings the
    // two back in line.
    private int focusColumnOf = -1;
    // The header's own focus stop (decision 36 of 2026-09-14): while the table holds the keyboard
    // it is either in the rows or in the header, whose column cursor is a shown column. The
    // header is a stop only while it is shown and a shown column can be sorted.
    private boolean headerFocused;
    private int headerColumn;
    // The column the header's cursor is on, by identity, as focusColumnOf is for the focus cell: a
    // shown index alone slid onto the next column when one before it was hidden, and onto some
    // other column, unannounced, when its own was.
    private int headerColumnOf = -1;

    // Columns as shown: which, and where, resolved per layout; and the set the previous layout
    // resolved, so a column hidden or shown between two layouts is noticed by the next one: a
    // hidden widget column's widgets are released and a shown one's built by re-mounting the rows.
    private int shownCount;
    private int[] shownIndex = new int[0];
    private int[] shownBefore = new int[0];
    private int shownBeforeCount = -1;
    private float[] colX = new float[0];
    private float[] colW = new float[0];
    private float contentWidth;
    private float lastViewportWidth;

    /**
     * The rows as they are scrolled and realized: the anchor, the slots in data (view) order, the
     * one kept for the keyboard, the reveal. The widget cells of the realized rows are
     * {@code children()} after the two bars, row by row in that order.
     */
    private final Rows<Slot> engine = new Rows<>(new RowsEngineHost());

    static {
        // A printed document cuts a table between rows, a page's worth realized at a time.
        limn.components.internal.PrintRows.register(Table.class, widget -> ((Table<?>) widget).new PrintView());
    }

    /** What a printed page asks of this table's rows: how many, how tall, and where the box begins. */
    private final class PrintView implements limn.components.internal.PrintRows {
        @Override
        public int rowCount() {
            return rows.size();
        }

        @Override
        public float rowHeight(int index) {
            return engine.realize(index);
        }

        @Override
        public float chromeHeight() {
            return Math.max(0, height() - rowsViewportHeight());
        }

        @Override
        public void beginAt(int index) {
            engine.moveViewTo(index, 0);
            markNeedsLayout();
        }
    }
    private float offsetX;
    /** What the pass in progress resolved once and every row is placed by. */
    private float passRowX;
    private float passRowW;
    private float passHeaderH;
    private boolean passRtl;
    /** Set while a scroll on both axes is in hand, which repaints and tells its watchers once. */
    private boolean scrollInHand;

    private final class RowsEngineHost implements Rows.Host<Slot> {
        @Override
        public int count() {
            return rows.size();
        }

        @Override
        public Slot mount(int index, int position) {
            Slot slot = new Slot(columns.size());
            slot.row = index;
            int insertAt = 2; // the two bars come first
            for (int i = 0; i < position; i++) {
                insertAt += engine.mountedAt(i).widgetCount;
            }
            int model = modelOf(index);
            T row = rows.get(model);
            slot.id = rowIdOf(model);
            slot.key = keyOf(row);
            RowRecord<T> current = new RowRecord<>(row);
            slot.record = current;
            Locale locale = locale();
            for (int c = 0; c < columns.size(); c++) {
                Column<T> column = columns.get(c);
                if (column.isWidgetColumn()) {
                    // A hidden widget column builds nothing: its widget was never laid out, but
                    // it was a child, and so a Tab stop and a published node. The next layout to
                    // show the column re-mounts the rows and builds it then.
                    if (column.isVisible()) {
                        Widget<?> widget = column.widgetFor(row, current);
                        slot.widgets[c] = widget;
                        add(insertAt + slot.widgetCount, widget);
                        slot.widgetCount++;
                    }
                } else {
                    slot.texts[c] = column.text(row, locale);
                }
            }
            return slot;
        }

        @Override
        public void unmount(Slot slot, boolean heldFocus) {
            // Or due back in it: a field whose own menu is open, which closes as the row leaves.
            boolean keyboard = heldFocus || (!engine.returningKeyboard() && holdsKeyboard(slot));
            for (int c = 0; c < columns.size(); c++) {
                if (slot.widgets[c] != null) {
                    remove(slot.widgets[c]);
                    slot.widgets[c] = null;
                }
            }
            slot.widgetCount = 0;
            if (slot.published) {
                publishedLeftBehind = true; // a reader may hold its node; see followPublishedRows
            }
            if (keyboard) {
                requestFocus(); // a row that leaves does not take the keyboard with it
            }
        }

        @Override
        public float measure(Slot slot, int index) {
            SizeTokens t = tokens();
            float lineBox = textRuler().measure("Hg", t.body()).height();
            float h = Math.max(t.controlHeight(), lineBox + 2 * t.padV());
            for (int s = 0; s < shownCount; s++) {
                Widget<?> widget = slot.widgets[shownIndex[s]];
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

        @Override
        public void place(Slot slot, int index, float y, float height) {
            // The rows' box begins under the header band.
            placeRow(slot, passHeaderH + y, height, passRowX, passRowW, passRtl, tokens());
        }

        @Override
        public void shift(Slot slot, float dy) {
            for (Widget<?> w : slot.widgets) {
                if (w != null) {
                    moveChild(w, w.x(), w.y() + dy);
                }
            }
        }

        @Override
        public boolean holdsFocus(Slot slot) {
            return containsFocus(slot);
        }

        @Override
        public boolean holdsPopup(Slot slot) {
            return holdsHungPopup(slot);
        }

        @Override
        public Widget<?> widget() {
            return Table.this;
        }

        @Override
        public void rowsFreed() {
            // Nothing is kept stale: a row kept for a popup is re-bound to its record.
        }

        @Override
        public int cursorIndex() {
            // The focus cell's row is realized wherever the viewport is while the table holds
            // the keyboard (decision 22 of 2026-09-14): a reader's cursor stands on that cell
            // whether or not it is in view.
            return isFocused() ? focusRow : -1;
        }

        /** The column whose widget cell holds the keyboard, then the path inside it. */
        @Override
        public @Nullable Object keyboardPlace(Slot row) {
            for (int c = 0; c < row.widgets.length; c++) {
                if (row.widgets[c] != null) {
                    int[] path = limn.components.internal.RowKeyboard.placeIn(row.widgets[c]);
                    if (path != null) {
                        int[] place = new int[path.length + 1];
                        place[0] = c;
                        System.arraycopy(path, 0, place, 1, path.length);
                        return place;
                    }
                }
            }
            return null;
        }

        @Override
        public void keyboardBack(Slot row, Object place) {
            int[] at = (int[]) place;
            Widget<?> cell = at[0] < row.widgets.length ? row.widgets[at[0]] : null;
            if (cell == null) {
                requestFocus();
                return;
            }
            limn.components.internal.RowKeyboard.giveBack(cell, java.util.Arrays.copyOfRange(at, 1, at.length),
                    Table.this);
        }

        @Override
        public float viewportHeight() {
            return rowsViewportHeight();
        }

        @Override
        public float seedHeight() {
            return tokens().listRowSeed();
        }

        @Override
        public void scrolled(Change.Origin origin) {
            vBar.onScrolled();
            if (!scrollInHand) {
                // Contained: a scroll changes which rows are realized and where they sit, inside
                // a box this widget clips and whose own size a scroll cannot move.
                markNeedsContainedLayout();
                invalidate();
                notifyChange(Change.of(Change.Aspect.VALUE, origin));
            }
        }
    }

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
    // Phase 2: the columns a user may move, the ones kept in place, and a header drag in flight.
    private boolean columnReordering = true;
    private int frozenColumns;
    private int headerPressColumn = -1;
    // A column being moved, by the pointer on its header or by the keyboard's drag: the shown
    // column, the shown place it would take, where the pointer is and where in the column it
    // holds it (NaN for the keyboard, which has no pointer), and where every shown column is
    // drawn meanwhile, the others having made room.
    private int moveSlot = -1;
    private int moveTo = -1;
    private float movePointerX = Float.NaN;
    private float moveGrab;
    private float headerPressX;
    private float[] moveX = new float[0];
    // How far each column is still drawn from where it belongs, by its index among columns(): a
    // column that was made to change place slides there, and this is what is left of the slide.
    private float[] slide = new float[0];
    private boolean sliding;
    private @Nullable Runnable onColumnsChange;
    private float dragStartX;
    private float dragStartWidth;
    private int hoverDivider = -1;
    private int lastPressRow = -1;

    /** Reused per walk to assemble a row's composite name before comparing it with the kept one. */
    private final StringBuilder nameBuilder = new StringBuilder();

    /** One realized row: its text per column, shaped and fitted lazily, and its widget cells. */
    private static final class Slot {
        int row;
        float height;
        /** The top the last layout placed it at, in this widget's coordinates; NaN before one. */
        float top = Float.NaN;
        final String[] texts;
        final ShapedText[] shaped;
        final ShapedText[] fitted;
        final float[] fittedWidth;
        final Widget<?>[] widgets;
        int widgetCount;
        /** The row's accessible identity: the synthetic key its {@code ROW} is published under. */
        long id;
        /** The key its record is followed by, taken when it was mounted. */
        Object key;
        /**
         * The record the table holds for it now, which its widget cells' supplier answers: the
         * object it was mounted with, until a refresh or a {@code setRows} keeps it over another
         * object for the same record.
         */
        RowRecord<?> record;
        /** Its occurrence among the equal keys before it; read the first time it is described. */
        int ordinal;
        boolean ordinalKnown;
        /** Published since the identities were last settled: releasing it leaves one behind. */
        boolean published;
        /**
         * The composite name the row was last published under &mdash; the text of its shown
         * cells, in column order &mdash; kept so a quiet frame compares and allocates nothing,
         * and so the string's identity is the witness the builder wants; {@code null} when the
         * row's cells hold no text at all.
         */
        String name;

        Slot(int columns) {
            texts = new String[columns];
            shaped = new ShapedText[columns];
            fitted = new ShapedText[columns];
            fittedWidth = new float[columns];
            widgets = new Widget<?>[columns];
        }
    }

    /**
     * A realized row's record as the table holds it now: the supplier a widget cell made by
     * {@link Column#widget(I18nString, java.util.function.BiFunction)} reads its record from.
     * One per mounted row, shared by its widget cells, and re-pointed when a refresh or a
     * {@link #setRows} keeps the row over another object for the same record. Read and written on
     * the UI thread.
     */
    private static final class RowRecord<T> implements java.util.function.Supplier<T> {
        private T record;

        RowRecord(T record) {
            this.record = record;
        }

        @Override
        public T get() {
            return record;
        }
    }

    /** @return {@code slot}'s record supplier, as this table's row type */
    @SuppressWarnings("unchecked") // every slot's supplier is made by mount over this table's rows
    private RowRecord<T> recordOf(Slot slot) {
        return (RowRecord<T>) slot.record;
    }

    /**
     * The records the table follows across {@link #refresh()}: one entry per model row that is
     * selected, the lead, the focus row or the anchor, in model order. Each holds the row's key
     * and its <b>ordinal</b> among the rows before it with an equal key, which is what tells
     * two equal records apart when the list comes back reordered: the third "Lee" stays the
     * third "Lee". Parallel arrays and a spare set to merge into, so a keyboard walk that moves
     * the selection one row at a time allocates nothing once they are sized.
     */
    private static final class Records {
        int[] models = new int[8];
        Object[] keys = new Object[8];
        int[] ordinals = new int[8];
        int size;
        int[] spareModels = new int[8];
        Object[] spareKeys = new Object[8];
        int[] spareOrdinals = new int[8];
        int[] added = new int[8];
        int addedCount;
        final int[] extras = new int[3];

        void clear() {
            Arrays.fill(keys, 0, size, null);
            size = 0;
        }

        void ensureSpare(int n) {
            if (spareModels.length < n) {
                int grown = Math.max(n, spareModels.length * 2);
                spareModels = new int[grown];
                spareKeys = new Object[grown];
                spareOrdinals = new int[grown];
            }
        }

        void swapInSpare(int n) {
            int[] m = models;
            Object[] k = keys;
            int[] o = ordinals;
            models = spareModels;
            keys = spareKeys;
            ordinals = spareOrdinals;
            spareModels = m;
            spareKeys = k;
            spareOrdinals = o;
            Arrays.fill(spareKeys, 0, size, null);
            size = n;
        }

        void noteAdded(int model) {
            if (addedCount == added.length) {
                added = Arrays.copyOf(added, added.length * 2);
            }
            added[addedCount++] = model;
        }
    }

    /** @return the key {@code row} is followed by: the record itself unless {@link #rowKey} says */
    private Object keyOf(T row) {
        return rowKey == null ? row : rowKey.apply(row);
    }

    /**
     * A model row the four tracked positions name, or {@code -1}; view positions are converted.
     * Read against the positions the rows had when they were tracked and not the list's size now:
     * an application that removed rows before calling {@link #refresh()} left the cursor past the
     * new end, and it is still a record to find (the tracked keys say whether it survived). Cut at
     * the new size, the cursor on I after A–E were removed was taken for gone and landed on J.
     */
    private int trackedModel(int viewIndex) {
        if (viewIndex < 0) {
            return -1;
        }
        if (view == null) {
            return viewIndex;
        }
        return viewIndex < view.length ? view[viewIndex] : -1;
    }

    /**
     * Brings the tracked records in line with the selection, the lead, the focus row and the
     * anchor after a seam moved one of them: rows that left are forgotten, rows that arrived are
     * read once for their key and their ordinal. The four sources are walked in model order
     * against the entries held, so the merge is one pass and allocates nothing once the arrays
     * fit.
     */
    private void syncRecords() {
        int count = rows.size();
        int a = trackedModel(focusRow);
        int b = trackedModel(rangeAnchor);
        a = a < count ? a : -1; // read below by model index: only rows the list holds now
        b = b < count ? b : -1;
        int c = lead >= 0 && lead < count ? lead : -1;
        // The three extras, sorted and deduplicated, merged with the selection's set bits.
        int e0 = Math.min(a, Math.min(b, c));
        int e2 = Math.max(a, Math.max(b, c));
        int e1 = a + b + c - e0 - e2;
        int wanted = 0;
        int selectedBit = selected.nextSetBit(0);
        int extra = 0;
        int[] extras = records.extras;
        extras[0] = e0;
        extras[1] = e1;
        extras[2] = e2;
        int t = 0;
        records.addedCount = 0;
        records.ensureSpare(selected.cardinality() + 3);
        while (true) {
            while (extra < 3 && (extras[extra] < 0 || (wanted > 0
                    && extras[extra] == records.spareModels[wanted - 1]))) {
                extra++;
            }
            int next;
            if (selectedBit >= 0 && selectedBit < count
                    && (extra >= 3 || selectedBit <= extras[extra])) {
                next = selectedBit;
                if (extra < 3 && extras[extra] == selectedBit) {
                    extra++;
                }
                selectedBit = selected.nextSetBit(selectedBit + 1);
            } else if (extra < 3) {
                next = extras[extra++];
            } else {
                break;
            }
            while (t < records.size && records.models[t] < next) {
                t++; // left the four: forgotten
            }
            if (t < records.size && records.models[t] == next) {
                records.spareModels[wanted] = next;
                records.spareKeys[wanted] = records.keys[t];
                records.spareOrdinals[wanted] = records.ordinals[t];
                t++;
            } else {
                records.spareModels[wanted] = next;
                records.spareKeys[wanted] = keyOf(rows.get(next));
                records.spareOrdinals[wanted] = 0;
                records.noteAdded(wanted);
            }
            wanted++;
        }
        records.swapInSpare(wanted);
        if (rowKey == null && records.addedCount > 0) {
            ordinalsOfAdded();
        }
    }

    /**
     * The ordinal of every entry that just arrived: how many rows before it carry an equal key,
     * which is how a record is told apart from an equal one when no {@link #rowKey} promises they
     * differ. Read from the occurrence cache the accessible describe keeps, so each row above is
     * read once until the list changes, not once per keypress: an arrow near the end of a
     * hundred thousand unkeyed rows used to cost a pass over all the rows above it each time.
     */
    private void ordinalsOfAdded() {
        int n = records.addedCount;
        int deepest = -1;
        for (int i = 0; i < n; i++) {
            deepest = Math.max(deepest, records.models[records.added[i]]);
        }
        if (deepest >= occurrencesRead) {
            readOccurrences(deepest + 1);
        }
        for (int i = 0; i < n; i++) {
            int at = records.added[i];
            records.ordinals[at] = occurrence[records.models[at]];
        }
    }

    /**
     * Finds every tracked record in the list as it is now: one pass over the rows, matching
     * each key's entries in ordinal order, so equal records are told apart by occurrence and a
     * record the list no longer holds is reported as gone. The pass stops once the last entry
     * has been found.
     *
     * @return the model row each entry stands at now, or {@code -1} for one that vanished, by
     *         entry
     */
    private int[] rediscover() {
        return rediscover(records.keys, records.ordinals, records.size);
    }

    /**
     * {@link #rediscover()} over any entries: {@code keys} and {@code ordinals} by entry, the
     * entries of one key in ordinal order.
     */
    private int[] rediscover(Object[] keys, int[] ordinals, int size) {
        int[] now = new int[size];
        Arrays.fill(now, -1);
        if (size == 0) {
            return now;
        }
        // Each key's entries chained in model order, which is ordinal order: {first, last, seen}.
        java.util.HashMap<Object, int[]> groups = new java.util.HashMap<>(size * 2);
        int[] next = new int[size];
        Arrays.fill(next, -1);
        for (int t = 0; t < size; t++) {
            int[] group = groups.get(keys[t]);
            if (group == null) {
                groups.put(keys[t], new int[] {t, t, 0});
            } else {
                next[group[1]] = t;
                group[1] = t;
            }
        }
        int count = rows.size();
        int found = 0;
        for (int m = 0; m < count && found < size; m++) {
            int[] group = groups.get(keyOf(rows.get(m)));
            if (group == null) {
                continue;
            }
            int occurrence = group[2]++;
            int cursor = group[0];
            while (cursor >= 0 && ordinals[cursor] < occurrence) {
                cursor = next[cursor]; // an equal record before this one is gone
            }
            if (cursor >= 0 && ordinals[cursor] == occurrence) {
                now[cursor] = m;
                found++;
                cursor = next[cursor];
            }
            group[0] = cursor;
        }
        return now;
    }

    // ----------------------------------------------------------------- row identity

    /**
     * The accessible identity model row {@code model} is published under: the key of its
     * {@code ROW}, the row part of its cells' keys, and the row a widget cell hangs under. It
     * follows the record and not the index: stable across a scroll away and back and across a sort,
     * which moves no model index, and carried by {@link #refresh()} to wherever a published record
     * went. Allocates nothing.
     */
    private long rowIdOf(int model) {
        int lo = 0;
        int hi = overrideCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int m = overrideModels[mid];
            if (m < model) {
                lo = mid + 1;
            } else if (m > model) {
                hi = mid - 1;
            } else {
                return overrideIds[mid];
            }
        }
        long id = rowIdBase + model;
        if (id >= rowIdHighWater) {
            rowIdHighWater = id + 1;
        }
        return id;
    }

    /**
     * The model row a row identity stands for now, or {@code -1} when no row carries it any more:
     * how a reader's verb, named by the node it was published on, finds its record after a
     * refresh moved it — or finds that the record is gone, and is refused.
     */
    private int modelOfRowId(long id) {
        for (int i = 0; i < overrideCount; i++) {
            if (overrideIds[i] == id) {
                return overrideModels[i] < rows.size() ? overrideModels[i] : -1;
            }
        }
        long m = id - rowIdBase;
        if (m < 0 || m >= rows.size()) {
            return -1;
        }
        return rowIdOf((int) m) == id ? (int) m : -1; // an override took that row's place
    }

    /** New rows, new identities: every row is issued a fresh one past all issued before. */
    private void resetRowIds() {
        rowIdBase = freshRowIdBase(rows.size(), 0);
        overrideCount = 0;
        clearPublished();
        settleMountedRows();
        forgetOccurrences();
    }

    private void clearPublished() {
        Arrays.fill(publishedKeys, 0, publishedCount, null);
        publishedCount = 0;
        publishedByRefresh = false;
    }

    /**
     * The identities are settled: every one published so far is followed or retired, so the
     * mounted rows leave nothing behind when they are released.
     */
    private void settleMountedRows() {
        publishedLeftBehind = false;
        for (int i = 0; i < engine.mountedCount(); i++) {
            engine.mountedAt(i).published = false;
        }
    }

    /**
     * A base for a range of {@code count} identities that no identity handed out so far and none
     * of the first {@code keep} overrides lies in: past the high-water mark while that fits below
     * {@link #ROW_ID_LIMIT}, which keeps a verb sent for a vanished record from naming a row
     * that took its identity; else the lowest gap the kept overrides leave. The high-water mark
     * then moves with what {@link #rowIdOf} hands out, not with the whole range, so a refresh
     * retires the identities of the rows a reader was shown and not a list's length of them.
     */
    private long freshRowIdBase(int count, int keep) {
        long base = rowIdHighWater;
        if (base + count <= ROW_ID_LIMIT) {
            return base;
        }
        long[] kept = Arrays.copyOf(overrideIds, keep);
        Arrays.sort(kept);
        base = 0;
        for (long id : kept) {
            if (id >= base + count) {
                break;
            }
            base = Math.max(base, id + 1);
        }
        rowIdHighWater = base + count;
        return base;
    }

    /**
     * Follows the followed rows to where the list holds their records now, for
     * {@link #refresh()}. When each is where it was and no published identity was left behind,
     * nothing changes. Otherwise each found record keeps its identity as an override at its new
     * row and stays followed there, so a second refresh before the next frame follows it again;
     * a record the list no longer holds takes its identity with it, so a verb still addressed to
     * it is refused; and every other row takes a fresh range, which retires the identities of
     * rows a reader was shown before a scroll released them, wherever their records went. One
     * read of the rows, stopping at the last found; nothing when nothing was published (no
     * reader).
     */
    private void followPublishedRows() {
        int n = publishedCount;
        if (n == 0 && !publishedLeftBehind) {
            return;
        }
        // Entries in model order, which is the ordinal order rediscover needs within a key.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(publishedModels[a], publishedModels[b]));
        Object[] keys = new Object[n];
        int[] ordinals = new int[n];
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = publishedKeys[order[i]];
            ordinals[i] = publishedOrdinals[order[i]];
            ids[i] = publishedIds[order[i]];
        }
        int[] now = rediscover(keys, ordinals, n);
        boolean stayed = !publishedLeftBehind;
        for (int i = 0; i < n && stayed; i++) {
            stayed = now[i] == publishedModels[order[i]];
        }
        if (!stayed) {
            // The found entries, by the row they stand at now: rowIdOf's binary search reads the
            // overrides in that order, and now[] follows the old model order, not the new.
            Integer[] byModel = new Integer[n];
            int found = 0;
            for (int i = 0; i < n; i++) {
                if (now[i] >= 0) {
                    byModel[found++] = i;
                }
            }
            Arrays.sort(byModel, 0, found, (a, b) -> Integer.compare(now[a], now[b]));
            if (overrideModels.length < found) {
                overrideModels = new int[found];
                overrideIds = new long[found];
            }
            for (int f = 0; f < found; f++) {
                int i = byModel[f];
                overrideModels[f] = now[i];
                overrideIds[f] = ids[i];
                publishedModels[f] = now[i];
                publishedIds[f] = ids[i];
                publishedKeys[f] = keys[i];
                publishedOrdinals[f] = ordinals[i]; // matched at that occurrence, so still it
            }
            Arrays.fill(publishedKeys, found, n, null);
            publishedCount = found;
            overrideCount = found;
            rowIdBase = freshRowIdBase(rows.size(), found);
        }
        publishedByRefresh = publishedCount > 0;
        settleMountedRows();
    }

    /**
     * Records what this describe publishes, as the followed rows, with the occurrence of every
     * row described for the first time. The rows the last refresh followed are checked first:
     * one this describe leaves out is an identity a reader may hold that the next refresh would
     * no longer follow. A quiet frame finds every occurrence known and allocates nothing.
     */
    private void notePublishedRows() {
        if (publishedByRefresh) {
            for (int p = 0; p < publishedCount && !publishedLeftBehind; p++) {
                boolean shown = false;
                for (int i = 0; i < engine.mountedCount() && !shown; i++) {
                    shown = engine.mountedAt(i).id == publishedIds[p];
                }
                publishedLeftBehind = !shown;
            }
            publishedByRefresh = false;
        }
        if (rowKey == null) {
            int deepest = -1;
            for (int i = 0; i < engine.mountedCount(); i++) {
                if (!engine.mountedAt(i).ordinalKnown) {
                    deepest = Math.max(deepest, modelOf(engine.mountedAt(i).row));
                }
            }
            if (deepest >= occurrencesRead) {
                readOccurrences(deepest + 1);
            }
        }
        if (publishedModels.length < engine.mountedCount()) {
            int grown = Math.max(engine.mountedCount(), publishedModels.length * 2);
            publishedModels = new int[grown];
            publishedIds = new long[grown];
            publishedKeys = new Object[grown];
            publishedOrdinals = new int[grown];
        }
        int n = 0;
        for (int i = 0; i < engine.mountedCount(); i++) {
            Slot slot = engine.mountedAt(i);
            int model = modelOf(slot.row);
            if (!slot.ordinalKnown) {
                // Keys are unique by the contract rowKey states; else the row's occurrence.
                slot.ordinal = rowKey != null ? 0 : occurrence[model];
                slot.ordinalKnown = true;
            }
            slot.published = true;
            publishedModels[n] = model;
            publishedIds[n] = slot.id;
            publishedKeys[n] = slot.key;
            publishedOrdinals[n] = slot.ordinal;
            n++;
        }
        for (int i = n; i < publishedCount; i++) {
            publishedKeys[i] = null;
        }
        publishedCount = n;
    }

    /**
     * Reads rows {@code [occurrencesRead, end)} for their occurrence: each row's key is hashed
     * once and chained to the nearest row before it with the same hash, and its occurrence is
     * one more than the nearest such row whose key is equal, or {@code 0}. A row no other row
     * equals costs one hash and one probe, however deep it stands.
     */
    private void readOccurrences(int end) {
        if (occurrence.length < end) {
            int grown = Math.max(end, occurrence.length * 2);
            occurrence = Arrays.copyOf(occurrence, grown);
            occurrenceHash = Arrays.copyOf(occurrenceHash, grown);
            occurrencePrevious = Arrays.copyOf(occurrencePrevious, grown);
        }
        if (occurrenceTable.length < 2 * end) {
            occurrenceTable = new int[Integer.highestOneBit(Math.max(16, 2 * end - 1)) << 1];
            for (int m = 0; m < occurrencesRead; m++) {
                occurrenceTable[occurrenceSlot(occurrenceHash[m])] = m + 1;
            }
        }
        for (int m = occurrencesRead; m < end; m++) {
            Object key = keyOf(rows.get(m));
            int hash = Objects.hashCode(key);
            int slot = occurrenceSlot(hash);
            int previous = occurrenceTable[slot] - 1;
            occurrenceHash[m] = hash;
            occurrencePrevious[m] = previous;
            occurrenceTable[slot] = m + 1;
            int ordinal = 0;
            for (int j = previous; j >= 0; j = occurrencePrevious[j]) {
                if (Objects.equals(keyOf(rows.get(j)), key)) {
                    ordinal = occurrence[j] + 1;
                    break;
                }
            }
            occurrence[m] = ordinal;
        }
        occurrencesRead = end;
    }

    /** The slot of {@code hash} in the occurrence table: the one holding it, else a free one. */
    private int occurrenceSlot(int hash) {
        int mask = occurrenceTable.length - 1;
        int slot = (hash ^ (hash >>> 16)) & mask;
        while (occurrenceTable[slot] != 0 && occurrenceHash[occurrenceTable[slot] - 1] != hash) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    /** The list changed, or what makes a row its record did: every occurrence is read again. */
    private void forgetOccurrences() {
        if (occurrencesRead > 0) {
            Arrays.fill(occurrenceTable, 0);
            occurrencesRead = 0;
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
        if (columns.size() > COLUMN_MASK) {
            throw new IllegalArgumentException("a table takes at most " + COLUMN_MASK + " columns");
        }
        this.columns = new ArrayList<>(List.copyOf(columns));
        for (Column<T> column : this.columns) {
            column.joined(this);
        }
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
                engine.scrollTo(value, Change.Origin.USER);
            }

            @Override
            public void scrollBy(float delta) {
                // Walked from where the rows are: setOffset lands by the mean row height.
                Table.this.scrollBy(0, delta, Change.Origin.USER);
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
     * is dropped, the sort is re-applied, and the view starts at the top. A row a popup hangs
     * from by one of its widget cells whose record the new rows hold is kept, as
     * {@link #refresh()} keeps it: its texts show the record found again, and its widget cells
     * stay those built for the record as it was handed then, the popup open and the keyboard
     * where it was. After new objects equal to the old, the record the table holds is the new
     * object: a cell made by {@link Column#widget(I18nString, java.util.function.BiFunction)}
     * reads it from its supplier, so a choice made in a combo box whose list stayed open through
     * the push lands there, while a cell made by {@link Column#widget(I18nString, Function)}
     * still holds the old object, and a menu on it that can outlive a push acts through the
     * record's key. A row whose field has the keyboard, or has it due back as its own menu
     * closes, is not kept: the field would stay bound to the old object and an edit made in it
     * would go there, so it goes, its menu with it, and the keyboard falls back to the table. UI
     * thread only.
     *
     * @param rows the rows; the table reads them on demand, so a change to the list's contents
     *             is invisible until {@link #refresh()}
     * @return this table
     */
    public Table<T> setRows(List<T> rows) {
        Ui.checkUiThread();
        this.rows = Objects.requireNonNull(rows, "rows");
        // A row a popup hangs from, by one of its widget cells, whose record the new rows hold.
        java.util.IdentityHashMap<Slot, Integer> hung = slotsKept(null, false);
        boolean had = !selected.isEmpty() || lead >= 0;
        selected.clear();
        lead = -1;
        rangeAnchor = -1;
        focusRow = -1;
        records.clear();
        resetRowIds();
        resort();
        // Back to the start, and a reveal still waiting was asked of the old rows.
        unmountAllKeeping(hung, true);
        recomputeFooter();
        // Contained: the rows change inside a box whose measure does not read them (the seed
        // and the columns size it), so a filter that empties and refills the rows repaints
        // the widget and never the window, its empty content coming and going included.
        markNeedsContainedLayout();
        invalidate();
        if (had) {
            // The selection went with the rows it named: a consequence, announced first.
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
        notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE));
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

    /** @return the columns, in reading order as they stand now; hidden ones included */
    public List<Column<T>> columns() {
        return Collections.unmodifiableList(columns);
    }

    /**
     * Moves a column to another place among {@link #columns()}. The rows are laid out again in
     * the new order, and the cell the keyboard is on stays with its column. A caller's write:
     * {@link #onColumnsChange} is not told.
     *
     * @param column one of this table's columns
     * @param index  the place it takes among {@link #columns()}, from 0
     * @return this table
     * @throws IllegalArgumentException  if the column is not this table's
     * @throws IndexOutOfBoundsException if there is no such place
     */
    public Table<T> moveColumn(Column<T> column, int index) {
        Ui.checkUiThread();
        int from = columns.indexOf(Objects.requireNonNull(column, "column"));
        if (from < 0) {
            throw new IllegalArgumentException("not a column of this table");
        }
        Objects.checkIndex(index, columns.size());
        moveColumn(from, index);
        return this;
    }

    /** Takes the column at {@code from} out and puts it in at {@code to}; everything kept by index follows. */
    private void moveColumn(int from, int to) {
        if (from == to) {
            return;
        }
        Column<T> focusOn = focusColumnOf >= 0 && focusColumnOf < columns.size()
                ? columns.get(focusColumnOf) : null;
        Column<T> headerOn = headerColumnOf >= 0 && headerColumnOf < columns.size()
                ? columns.get(headerColumnOf) : null;
        boolean heldFocus = !isFocused() && scene() != null && focusedWidgetSlot() != null;
        // The rows hold their texts, their shaped lines and their widgets by column index: they
        // are let go and realized again in the new order, as a refresh does.
        unmountAll();
        columns.add(to, columns.remove(from));
        if (sliding && from < slide.length && to < slide.length) {
            float moved = slide[from];
            System.arraycopy(slide, from < to ? from + 1 : to, slide, from < to ? from : to + 1,
                    Math.abs(to - from));
            slide[to] = moved;
        }
        Arrays.fill(headerShaped, null);
        recomputeFooter();
        focusColumnOf = focusOn == null ? -1 : columns.indexOf(focusOn);
        headerColumnOf = headerOn == null ? -1 : columns.indexOf(headerOn);
        shownBeforeCount = Math.min(shownBeforeCount, 0); // resolved again, and the cursors with it
        if (heldFocus) {
            requestFocus(); // the cell widget that had the keyboard is gone with its row
        }
        markNeedsLayout();
        invalidate();
        invalidateAccessible();
    }

    /**
     * Sets whether the user may move a column by dragging its header or with Alt+Shift+Left and
     * Right (default on, as resizing is).
     *
     * @param allowed whether columns can be moved by the user
     * @return this table
     */
    public Table<T> setColumnReorderingAllowed(boolean allowed) {
        Ui.checkUiThread();
        columnReordering = allowed;
        return this;
    }

    /** @return whether the user may move columns */
    public boolean isColumnReorderingAllowed() {
        return columnReordering;
    }

    /**
     * Keeps the first shown columns in place while the others scroll under them: the name
     * beside a year of monthly figures. The user moves a column only within its group, the kept
     * ones or the others. Where the kept columns would leave no room for the rest, none is kept.
     *
     * @param count how many leading shown columns stay; 0 for none
     * @return this table
     * @throws IllegalArgumentException if {@code count} is negative
     */
    public Table<T> setFrozenColumns(int count) {
        Ui.checkUiThread();
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative: " + count);
        }
        if (frozenColumns != count) {
            frozenColumns = count;
            markNeedsLayout();
            invalidate();
        }
        return this;
    }

    /** @return how many leading shown columns are asked to stay in place */
    public int frozenColumns() {
        return frozenColumns;
    }

    /**
     * The application's response to the user rearranging the columns: one moved, by a drag of
     * its header or by the keyboard, or one resized. It is when an application saves
     * {@link #columns()}' order and widths. Never for {@link #moveColumn} or a width set in code.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this table
     * @throws IllegalStateException if a handler is already registered
     */
    public Table<T> onColumnsChange(@Nullable Runnable handler) {
        Ui.checkUiThread();
        this.onColumnsChange = Checks.handlerSlot(onColumnsChange, handler, "Table.onColumnsChange");
        return this;
    }

    private void columnsChangedByUser() {
        Runnable handler = onColumnsChange;
        if (handler != null) {
            handler.run();
        }
    }

    /** How many leading shown columns stay in place in this layout. */
    private int frozen() {
        int count = Math.min(frozenColumns, shownCount);
        if (count <= 0) {
            return 0;
        }
        float kept = count < shownCount ? colX[count] : contentWidth;
        return count < shownCount && kept < lastViewportWidth ? count : 0;
    }

    /** The width the kept columns take off the scrolling ones' viewport. */
    private float frozenWidth() {
        int count = frozen();
        return count == 0 ? 0 : colX[count];
    }

    /** Where shown column {@code s} stands when no column is being moved. */
    private float restingLeft(int s, float rowX, float viewW, boolean rtl) {
        float offset = s < frozen() ? 0 : offsetX;
        return rtl ? rowX + viewW - (colX[s] + colW[s]) + offset : rowX + colX[s] - offset;
    }

    /** The moving column's left edge: under the pointer where it was taken, within its group. */
    private float movingLeft(float rowX, float viewW, boolean rtl) {
        int first = groupStart(moveSlot);
        int last = groupEnd(moveSlot) - 1;
        float a = restingLeft(rtl ? last : first, rowX, viewW, rtl);
        float b = restingLeft(rtl ? first : last, rowX, viewW, rtl) + colW[rtl ? first : last];
        float low = Math.max(a, rowX);
        float high = Math.min(b, rowX + viewW) - colW[moveSlot];
        return Math.max(low, Math.min(Math.max(low, high), movePointerX - moveGrab));
    }

    /** How long a column takes to cover most of the way to its new place, in seconds. */
    private static final double SLIDE_SECONDS = 0.07;

    /** Where every column is drawn now, by its index among {@code columns()}; NaN for a hidden one. */
    private float[] drawnLefts() {
        float[] lefts = new float[columns.size()];
        Arrays.fill(lefts, Float.NaN);
        float rowX = rowsLeft();
        float w = gutters.viewportWidth(width());
        boolean rtl = isRightToLeft();
        for (int s = 0; s < shownCount; s++) {
            lefts[shownIndex[s]] = columnLeft(s, rowX, w, rtl);
        }
        return lefts;
    }

    /**
     * Makes every column that is no longer where it was drawn slide to where it now belongs:
     * called after the places changed, with where the columns were drawn before, by column.
     */
    private void slideFrom(List<Column<T>> before, float[] drawnBefore) {
        limn.scene.Scene scene = scene();
        if (scene == null || limn.animation.Motion.reduced()) {
            sliding = false;
            return; // nothing to animate on, or the user asked for less motion: the columns jump
        }
        if (slide.length < columns.size()) {
            slide = Arrays.copyOf(slide, columns.size());
        }
        if (!sliding) {
            Arrays.fill(slide, 0);
        }
        float rowX = rowsLeft();
        float w = gutters.viewportWidth(width());
        boolean rtl = isRightToLeft();
        boolean any = false;
        for (int s = 0; s < shownCount; s++) {
            int c = shownIndex[s];
            int was = before.indexOf(columns.get(c));
            if (s == moveSlot && !Float.isNaN(movePointerX)) {
                slide[c] = 0;
            } else if (was >= 0 && was < drawnBefore.length && !Float.isNaN(drawnBefore[was])) {
                slide[c] = drawnBefore[was] - settledLeft(s, rowX, w, rtl);
                any |= Math.abs(slide[c]) >= 0.5f;
            }
        }
        if (any && !sliding) {
            sliding = true;
            scene.addRealTimeTicker(dt -> {
                double keep = Math.exp(-dt / SLIDE_SECONDS);
                boolean moving = false;
                for (int c = 0; c < slide.length; c++) {
                    slide[c] *= (float) keep;
                    if (Math.abs(slide[c]) < 0.5f) {
                        slide[c] = 0;
                    } else {
                        moving = true;
                    }
                }
                sliding = moving;
                markNeedsLayout(); // the cell widgets slide with their columns
                invalidate();
                return moving;
            });
        } else if (!any && !sliding) {
            Arrays.fill(slide, 0);
        }
    }

    /** Lays the shown columns out as they would stand with the moving one at {@code moveTo}. */
    private void placeAroundMove() {
        if (moveX.length < shownCount) {
            moveX = new float[shownCount];
        }
        float x = 0;
        int placed = 0;
        for (int s = 0; s <= shownCount; s++) {
            if (placed == moveTo) {
                moveX[moveSlot] = x;
                x += colW[moveSlot];
                placed++;
            }
            if (s < shownCount && s != moveSlot) {
                moveX[s] = x;
                x += colW[s];
                placed++;
            }
        }
    }

    /** Begins, or goes on with, moving shown column {@code s} toward shown place {@code to}. */
    private void movingColumn(int s, int to, float pointerX) {
        int target = Math.max(groupStart(s), Math.min(groupEnd(s) - 1, to));
        boolean placesChange = moveSlot != s || moveTo != target;
        List<Column<T>> before = placesChange ? List.copyOf(columns) : null;
        float[] drawn = placesChange ? drawnLefts() : null;
        moveSlot = s;
        moveTo = target;
        movePointerX = pointerX;
        placeAroundMove();
        if (before != null) {
            slideFrom(before, drawn); // the neighbours make room by sliding
        }
        markNeedsLayout(); // the cell widgets go with their columns
        invalidate();
    }

    /** Ends a move without moving anything: the columns slide back to where they were. */
    private void endMove() {
        if (moveSlot >= 0) {
            List<Column<T>> before = List.copyOf(columns);
            float[] drawn = drawnLefts();
            moveSlot = -1;
            moveTo = -1;
            movePointerX = Float.NaN;
            slideFrom(before, drawn);
            markNeedsLayout();
            invalidate();
        }
    }

    /** Ends a move by dropping the column at {@code to}: it and its neighbours slide into place. */
    private void dropMove(int from, int to) {
        List<Column<T>> before = List.copyOf(columns);
        float[] drawn = drawnLefts();
        moveSlot = -1;
        moveTo = -1;
        movePointerX = Float.NaN;
        if (to != from) {
            userMovesColumn(from, to);
        }
        slideFrom(before, drawn);
        markNeedsLayout();
        invalidate();
    }

    /**
     * The shown place the pointer's column would take. A neighbour gives way when the edge of the
     * moving column that leads toward it passes its middle. By the moving column's own middle a
     * wide one, held at the end of its group, never passed a narrow neighbour's and could not be
     * made the first or the last (the owner's report: Total, and Customer).
     */
    private int placeUnderPointer(float rowX, float viewW, boolean rtl) {
        float left = movingLeft(rowX, viewW, rtl);
        float right = left + colW[moveSlot];
        int before = 0;
        for (int s = groupStart(moveSlot); s < groupEnd(moveSlot); s++) {
            if (s == moveSlot) {
                continue;
            }
            float middle = restingLeft(s, rowX, viewW, rtl) + colW[s] / 2;
            // A neighbour that stood before the moving column stays before it until the moving
            // column's leading edge, the one nearer the start, has gone past its middle; one
            // that stood after comes before once the trailing edge has.
            boolean stoodBefore = s < moveSlot;
            boolean staysBefore = rtl
                    ? (stoodBefore ? right <= middle : left < middle)
                    : (stoodBefore ? left >= middle : right > middle);
            if (staysBefore) {
                before++;
            }
        }
        return groupStart(moveSlot) + before;
    }

    /** The shown column painted at step {@code k} of a pass: the scrolling ones, the kept ones, then the one being moved. */
    private int paintOrder(int k, int kept) {
        if (moveSlot < 0) {
            return k < shownCount - kept ? kept + k : k - (shownCount - kept);
        }
        if (k == shownCount - 1) {
            return moveSlot;
        }
        int at = -1;
        for (int i = 0; i < shownCount; i++) {
            int s = i < shownCount - kept ? kept + i : i - (shownCount - kept);
            if (s != moveSlot && ++at == k) {
                return s;
            }
        }
        return moveSlot;
    }

    /** Whether local {@code x} is over the kept columns, which cover what scrolls under them. */
    private boolean overFrozen(float x, float rowX, float viewW, boolean rtl) {
        float kept = frozenWidth();
        return kept > 0 && (rtl ? x >= rowX + viewW - kept : x < rowX + kept);
    }

    /**
     * Clips to where the scrolling columns show, when some are kept. The three painting loops
     * draw the scrolling columns first, inside this clip, and the kept ones after it is lifted.
     *
     * @return whether a clip was pushed, to be restored when the first kept column is reached
     */
    private boolean clipToScrolling(Canvas canvas, int kept, float rowX, float viewW, boolean rtl) {
        if (kept == 0) {
            return false;
        }
        float width = colX[kept];
        canvas.save();
        canvas.clipRect(rtl ? rowX : rowX + width, 0, viewW - width, height());
        return true;
    }

    /**
     * @param column one of this table's columns
     * @return the text its footer cell shows, as computed by the last {@link #setRows} or
     *         {@link #refresh()}; {@code null} when the column puts nothing in the footer
     */
    public @Nullable String footerTextOf(Column<T> column) {
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
     * Re-reads the rows and re-lays out: call after the list's contents change. A column's
     * setters need no call: the table takes a change to its width, visibility, alignment,
     * footer or sorting by itself. The sort is re-applied and the scroll
     * position is kept, clamped.
     *
     * <p><b>A row is its record</b>: the selection, the lead, the focus cell and the range anchor
     * follow their records to wherever the list holds them now, after an insert, a remove, a
     * reorder or the application's own sort ({@link #onSortRequest}). A record is found again by
     * its {@linkplain #rowKey key} — the record itself, by {@code equals}, unless one is set — and
     * records with equal keys are told apart by occurrence: the third equal record stays the third.
     * Finding them is one read of the rows, stopping at the last one found; a table with nothing
     * selected and no focus cell reads nothing. A selected record the list no longer holds leaves
     * the selection, announced as {@code SELECTION}/{@code ADJUSTMENT}; a vanished lead makes the
     * last selected row the lead; a vanished focus row or anchor keeps its position, clamped. A
     * focus row that moved is announced as {@code ACTIVE}/{@code ADJUSTMENT}; when the refresh
     * answers a {@linkplain #onSortRequest sort request} it is also revealed with the least scroll,
     * as the table's own sort does, and otherwise the scroll position is kept. Then
     * {@code CHILDREN}/ {@code CODE}; nothing reaches a handler. A row's accessible node follows
     * its record the same way: the rows the last publish described keep their nodes wherever their
     * records went, across as many refreshes as come before the next frame, and a reader's verb
     * sent before them acts on the record it named, or is refused when that record is gone. A row
     * published earlier and scrolled away since loses its node on the next refresh: the node names
     * that record or nothing, never another.
     *
     * <p>A row from one of whose widget cells a popup hangs (a context menu opened on it, a
     * popover) follows its record as the row of a focused widget cell does: its widgets stay, so
     * the popup stays open through a refresh a timer or a server push makes, acting on the record
     * its widget cell was built for; when the record is gone they are let go of and the popup
     * closes with them. Where the list now holds another object for that record, a cell made by
     * {@link Column#widget(I18nString, java.util.function.BiFunction)} reads that object from its
     * supplier, as after {@link #setRows}. UI thread only.
     */
    public void refresh() {
        Ui.checkUiThread();
        // The nodes a reader holds first, before anything below re-mounts the rows under them.
        followPublishedRows();
        forgetOccurrences();
        // The row whose widget cell holds the keyboard, and those a popup hangs from (a menu
        // opened at a widget cell), found again before anything re-mounts: letting go of the
        // latter would close the menu under the pointer on every refresh a timer makes.
        java.util.IdentityHashMap<Slot, Integer> keep = slotsKept(focusedWidgetSlot(), true);
        int count = rows.size();
        boolean moved = false;
        int wasFocusRow = focusRow;
        if (records.size > 0) {
            int focusModel = trackedModel(focusRow);
            int anchorModel = trackedModel(rangeAnchor);
            int[] now = rediscover();
            int newLead = -1;
            int newFocus = -1;
            int newAnchor = -1;
            boolean leadVanished = false;
            BitSet was = (BitSet) selected.clone();
            selected.clear();
            for (int t = 0; t < records.size; t++) {
                int old = records.models[t];
                int at = now[t];
                if (was.get(old)) {
                    if (at >= 0) {
                        selected.set(at);
                    } else {
                        moved = true;
                    }
                }
                if (old == lead) {
                    newLead = at;
                    leadVanished = at < 0;
                }
                if (old == focusModel) {
                    newFocus = at;
                }
                if (old == anchorModel) {
                    newAnchor = at;
                }
            }
            if (leadVanished) {
                moved = true;
            }
            lead = newLead >= 0 ? newLead : (selected.isEmpty() ? -1 : selected.length() - 1);
            resort();
            focusRow = newFocus >= 0 ? viewOf(newFocus) : Math.min(focusRow, count - 1);
            rangeAnchor = newAnchor >= 0 ? viewOf(newAnchor) : Math.min(rangeAnchor, count - 1);
            records.clear();
            syncRecords();
        } else {
            if (focusRow >= count) {
                focusRow = count - 1;
            }
            rangeAnchor = Math.min(rangeAnchor, count - 1);
            resort();
        }
        unmountAllKeeping(keep, false);
        textEpoch++;
        recomputeFooter();
        // Contained: the rows change inside a box whose measure does not read them (the seed
        // and the columns size it), so a filter that empties and refills the rows repaints
        // the widget and never the window, its empty content coming and going included.
        markNeedsContainedLayout();
        invalidate();
        if (answeringSortRequest && focusRow >= 0 && focusRow != wasFocusRow) {
            // A refresh that answers a sort request is the application's sort, and reveals the
            // focus row as the table's own does (decision 40); any other refresh keeps the
            // scroll position it promised to keep.
            ensureVisible(focusRow);
        }
        if (moved) {
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
        announceFocusCell(wasFocusRow, Change.Origin.ADJUSTMENT);
        notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE));
    }

    /**
     * Names what makes a row the record it is, for {@link #refresh()} to follow the selection,
     * the lead, the focus cell and the anchor across a change to the list: {@code Order::id} for
     * rows that are records with an identity, or nothing, in which case the record itself is the
     * key and equal records are told apart by occurrence. A key is taken when a row enters one
     * of the four and compared by {@code equals}; keys are expected to be unique, and when two
     * rows share one the first found wins. Setting it re-reads the keys of every row the table
     * is following. UI thread only.
     *
     * @param key the function from a row to its key, or {@code null} for the record itself
     * @return this table
     */
    public Table<T> rowKey(@Nullable Function<? super T, ?> key) {
        Ui.checkUiThread();
        this.rowKey = key;
        // Before the records are read again: their ordinals come from the occurrences, which
        // depend on the key.
        forgetOccurrences();
        records.clear();
        syncRecords();
        for (int i = 0; i < engine.mountedCount(); i++) {
            Slot slot = engine.mountedAt(i);
            slot.key = keyOf(rows.get(modelOf(slot.row)));
            slot.ordinalKnown = false;
        }
        // Taken under the old key: the next describe publishes them again, and the identities
        // published under it are left for the next refresh to retire.
        if (publishedCount > 0 || overrideCount > 0) {
            publishedLeftBehind = true;
        }
        clearPublished();
        forgetOccurrences();
        return this;
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
        if (moved) {
            syncRecords();
        }
        invalidate();
        if (moved) {
            // The mode has no aspect of its own; what a watcher can act on is the selection it
            // collapsed, which moved as a consequence.
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
        return this;
    }

    /** @return how many rows may be selected */
    public SelectionMode selectionMode() {
        return selectionMode;
    }

    /**
     * Selects one row alone, moves the focus cell to it and scrolls it into view: a caller's
     * write, so it announces {@code SELECTION}/{@code CODE} and reaches no handler. Selecting the
     * row that is already the only selected one changes nothing and announces nothing. UI thread
     * only.
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
        selectOnly(modelIndex, viewOf(modelIndex), true, Change.Origin.CODE);
        return this;
    }

    /**
     * Replaces the selection with exactly these rows, the last one the lead, and scrolls the lead
     * into view: what an application restoring a saved selection calls. In
     * {@link SelectionMode#SINGLE} only one row may be named. Announces {@code SELECTION}/{@code
     * CODE} once when the set changed. UI thread only.
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
        int wasFocusRow = focusRow;
        selected.clear();
        selected.or(next);
        lead = last;
        focusRow = viewOf(last);
        rangeAnchor = focusRow;
        syncRecords();
        ensureVisible(focusRow);
        invalidate();
        announceFocusCell(wasFocusRow, Change.Origin.CODE);
        if (!same) {
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.CODE));
        }
        return this;
    }

    /**
     * Drops the selection, announced as {@code SELECTION}/{@code CODE}; nothing happens when
     * nothing is selected. The focus cell stays where it is. UI thread only.
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
        syncRecords();
        invalidate();
        notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.CODE));
        return this;
    }

    /**
     * Selects every row, in {@link SelectionMode#MULTI}; the lead row is kept, or becomes the
     * first row. A caller's write, announced as {@code SELECTION}/{@code CODE}; Ctrl+A enters the
     * same seam as the user's. UI thread only.
     *
     * @return this table
     */
    public Table<T> selectAll() {
        Ui.checkUiThread();
        selectAll(Change.Origin.CODE);
        return this;
    }

    private void selectAll(Change.Origin origin) {
        if (selectionMode != SelectionMode.MULTI || rows.isEmpty()) {
            return;
        }
        if (selected.cardinality() == rows.size()) {
            return;
        }
        selected.set(0, rows.size());
        if (lead < 0) {
            lead = modelOf(0);
        }
        // Every row is followed now, so every row is read once for its key: the price of a
        // selection that survives the list changing under it (decision 23 of 2026-09-14).
        syncRecords();
        invalidate();
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
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
     * The application's response to the user changing the selection: a click, a key, an
     * assistive technology's select. Never for {@link #setSelectedRow}, {@link #setSelectedRows},
     * {@link #clearSelection()}, {@link #selectAll()} or a {@link #setRows}, {@link #refresh()}
     * or {@link #setSelectionMode} that moved it, which are the caller's or the table's own; to
     * hear every change whatever caused it, {@linkplain #observeChanges watch} the table.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this table
     * @throws IllegalStateException if a handler is already registered
     */
    public Table<T> onSelect(@Nullable Runnable handler) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, handler, "Table.onSelect");
        return this;
    }

    /**
     * The application's response to the user opening the cursor row (the focus cell's row):
     * Enter, a double click, an assistive technology's press. Never for {@link #activate()},
     * which is a caller's verb.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this table
     * @throws IllegalStateException if a handler is already registered
     */
    public Table<T> onActivate(@Nullable IntConsumer handler) {
        Ui.checkUiThread();
        this.onActivate = Checks.handlerSlot(onActivate, handler, "Table.onActivate");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        switch (aspect) {
            case SELECTION -> {
                if (onSelect != null) {
                    onSelect.run();
                }
            }
            case INVOKED -> {
                if (onActivate != null) {
                    onActivate.accept(activated);
                }
            }
            case CHILDREN -> {
                // The one user gesture that reorders the rows is a header click, and the
                // request it made is read back from the fields headerClicked wrote first.
                if (onSortRequest != null && sortRequestColumn != null) {
                    Column<T> column = sortRequestColumn;
                    SortOrder order = sortRequestOrder;
                    sortRequestColumn = null;
                    answeringSortRequest = true;
                    try {
                        onSortRequest.accept(column, order);
                    } finally {
                        answeringSortRequest = false;
                    }
                }
            }
            default -> super.handleUserChange(aspect);
        }
    }

    /**
     * Announces that the cursor row — the focus cell's row — was opened, as {@code INVOKED}/
     * {@code CODE}: a caller's verb, which reaches a watcher and <b>not</b> {@link #onActivate},
     * the way Enter does. Nothing before the keyboard has been in the table. UI thread only.
     */
    public void activate() {
        Ui.checkUiThread();
        activate(Change.Origin.CODE);
    }

    /** The model row the last activation opened, read by {@link #handleUserChange}. */
    private int activated = -1;

    /**
     * The seam Enter, a double click and an assistive technology's press enter at {@code USER}.
     * What opens is the <b>cursor row</b>: the row the focus cell is in, which in {@code SINGLE} is
     * the lead, in {@code MULTI} may differ from it after a toggle or a Shift range, and in
     * {@code NONE} is the only row there is.
     */
    private void activate(Change.Origin origin) {
        if (hasCursorRow()) {
            activateRow(modelOf(focusRow), origin);
        }
    }

    /**
     * The same seam, naming the row that was opened rather than the one the focus cell is in: a
     * reader's {@code PRESS} arrives addressed to a row, and its {@code SELECT} does not drag the
     * focus cell there, so the two can differ.
     *
     * @param model the model row opened; {@code -1} opens nothing
     */
    private void activateRow(int model, Change.Origin origin) {
        if (model >= 0) {
            activated = model;
            notifyChange(Change.of(Change.Aspect.INVOKED, origin));
        }
    }

    /**
     * Whether there is a cursor row to open: the one bound the table's {@code PRESS} is published
     * on, performed on, and that Enter and a double click open through (until 2026-09-15 the
     * publish read the row count of the describe and the perform the list's size then, two bounds
     * for one verb).
     */
    private boolean hasCursorRow() {
        return focusRow >= 0 && focusRow < rows.size();
    }

    /** The focus cell moved with a selection or a key: {@code ACTIVE}, the active descendant. */
    private void announceFocusCell(int wasFocusRow, Change.Origin origin) {
        if (focusRow != wasFocusRow) {
            notifyChange(Change.of(Change.Aspect.ACTIVE, origin));
        }
    }

    /** @return the focus cell's row as shown, or {@code -1} before the keyboard was in the table */
    public int focusRow() {
        return focusRow;
    }

    /**
     * @return the focus cell's column, as an index among the shown columns; when the column it
     *         stood on is hidden the cell moves to the nearest shown column, and when a column
     *         before it is hidden the index shifts and the cell stays on its column
     */
    public int focusColumn() {
        return focusColumn;
    }

    /**
     * @return whether the keyboard, while in this table, is on the header rather than in the
     *         rows: Tab enters at the header when a shown column can be sorted, Left and Right
     *         move its column cursor and Space sorts the column under it
     */
    public boolean isHeaderFocused() {
        return headerFocused;
    }

    /** @return the header's column cursor, as an index among the shown columns */
    public int headerColumn() {
        return headerColumn;
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
        if (order != SortOrder.NONE) {
            if (!columns.contains(column)) {
                throw new IllegalArgumentException("not one of this table's columns");
            }
            if (!column.isSortable()) {
                throw new IllegalArgumentException("the column is not sortable");
            }
        }
        applySort(column, order, Change.Origin.CODE);
        return this;
    }

    /**
     * The one seam a sort goes through: the public setter passes {@code CODE}, a header click
     * {@code USER}. A sort reorders the rows the table shows, so it is announced as
     * {@code CHILDREN} -- the enum has no aspect for an order, and the accessible tree publishes
     * none, so what a watcher re-reads is the rows.
     *
     * <p>The focus cell and the range anchor go with their records: both are view positions, and a
     * permutation that left them where they stood put the cursor and the next Shift range on
     * whatever record the sort moved there. The focus row is then revealed with the least scroll
     * that shows it, as every other write that moves the focus cell does, and its move is announced
     * as {@code ACTIVE}/ {@code ADJUSTMENT} before the rows are: a consequence of the sort, not a
     * gesture of its own, and one the cursor's reader hears first.
     */
    private void applySort(Column<T> column, SortOrder order, Change.Origin origin) {
        if (order == SortOrder.NONE) {
            sortColumn = null;
            sortOrder = SortOrder.NONE;
        } else {
            sortColumn = column;
            sortOrder = order;
        }
        permute(origin);
    }

    /**
     * Rebuilds the permutation from the sort the header shows — or drops it, while a sort request
     * handler is set — and carries the focus cell and the range anchor to their records, reveals
     * the focus row and announces the move and then the rows, as {@link #applySort} does. The seam
     * a change to {@link #onSortRequest} re-runs as well: setting the handler drops the table's
     * permutation at once, clearing it re-applies the table's own sort on the column the header
     * shows.
     */
    private void permute(Change.Origin origin) {
        int wasFocusRow = reorder();
        // Contained: a sort moves rows inside a box whose size it cannot change. A full layout
        // here made every sort — a header click, Space on the header, setSort — a whole-window
        // repaint (found putting decision 36's Space under the damage contract, 2026-09-15).
        markNeedsContainedLayout();
        invalidate();
        announceFocusCell(wasFocusRow, Change.Origin.ADJUSTMENT);
        notifyChange(Change.of(Change.Aspect.CHILDREN, origin));
    }

    /**
     * Rebuilds the permutation and carries the focus cell, the range anchor and a focused widget
     * cell to their records, leaving the rows to the next placement and the focus row to be
     * revealed by it.
     *
     * @return the focus row before, for the announcement of its move
     */
    private int reorder() {
        int count = rows.size();
        int focusModel = focusRow >= 0 && focusRow < count ? modelOf(focusRow) : -1;
        int anchorModel = rangeAnchor >= 0 && rangeAnchor < count ? modelOf(rangeAnchor) : -1;
        java.util.IdentityHashMap<Slot, Integer> keep = new java.util.IdentityHashMap<>();
        for (int p = 0; p < engine.mountedCount(); p++) {
            Slot slot = engine.mountedAt(p);
            if (slot.widgetCount > 0 && (containsFocus(slot) || holdsHungPopup(slot))) {
                keep.put(slot, modelOf(slot.row));
            }
        }
        resort();
        int wasFocusRow = focusRow;
        if (focusModel >= 0) {
            focusRow = viewOf(focusModel);
        }
        if (anchorModel >= 0) {
            rangeAnchor = viewOf(anchorModel);
        }
        unmountAllKeeping(keep, false);
        if (focusRow >= 0) {
            // Deferred to the layout that re-places the rows: nothing is realized now, so the
            // reveal could only put the row at the top, and the least scroll needs the run.
            ensureVisible(focusRow);
        }
        return wasFocusRow;
    }

    /** @return the column the rows are ordered on, or {@code null} in the model's order */
    public @Nullable Column<T> sortColumn() {
        return sortColumn;
    }

    /** @return how the rows are ordered */
    public SortOrder sortOrder() {
        return sortOrder;
    }

    /**
     * Hands header clicks to the application instead of sorting: the handler is told the column and
     * the order the click asks for, orders the list itself and calls {@link #refresh()}, which
     * carries the selection and the focus cell to where their records are now. The header shows the
     * order the click asked for from the click itself. A handler, so it answers the user's click
     * and never {@link #setSort}; one slot, as every {@code onX} is: {@code null} clears it, a
     * second handler over the first throws.
     *
     * <p>The slot changing hands re-sorts at once when the header shows an order: setting a
     * handler drops the table's permutation, so the rows show in the application's order
     * (the application is expected to have ordered them, or to order them and
     * {@link #refresh()}); clearing it restores the table's own sort on the column the header
     * shows. Both carry the focus cell with its record and are announced as a sort is,
     * {@code CHILDREN}/{@code CODE}, reaching no handler. UI thread only.
     *
     * @param handler what to tell, or {@code null} to clear the slot
     * @return this table
     * @throws IllegalStateException if a handler is already registered
     */
    public Table<T> onSortRequest(@Nullable BiConsumer<Column<T>, SortOrder> handler) {
        Ui.checkUiThread();
        boolean had = onSortRequest != null;
        this.onSortRequest = Checks.handlerSlot(onSortRequest, handler, "Table.onSortRequest");
        if (had != (handler != null) && sortColumn != null && sortOrder != SortOrder.NONE) {
            permute(Change.Origin.CODE);
        }
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
        return engine.anchorIndex();
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

    /** @return whether every second row is tinted */
    public boolean isStriped() {
        return striped;
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

    /** @return whether the header row is shown */
    public boolean isShowHeader() {
        return showHeader;
    }

    /**
     * Sets when the scroll bars are shown (default {@link ScrollBar.Policy#AUTO}).
     *
     * @param policy the policy
     * @return this table
     */
    public Table<T> setScrollbarPolicy(ScrollBar.Policy policy) {
        Ui.checkUiThread();
        vBar.setPolicy(policy);
        hBar.setPolicy(policy);
        return this;
    }

    /** @return when the scroll bars are shown */
    public ScrollBar.Policy scrollbarPolicy() {
        return vBar.policy();
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

    /** Whether the scroll bars overlay the rows or reserve strips beside them. */
    public ScrollGutters.Layout barLayout() {
        return gutters.layout();
    }

    /**
     * Sets how many rows tall the rows' viewport prefers to be when the parent gives the table no
     * height — a table inside a {@link limn.components.ScrollView} or an unconstrained column — as
     * a count of the step's seed rows (default 8); the header and the footer add their own strips.
     * A bounded height from the parent always wins; this is the free-axis fallback only. The
     * preference is the seed's and not the realized rows' on purpose: a preference that followed
     * the measured average moved every time a row of another height scrolled in, and re-laid out
     * the parent with it. UI thread only.
     *
     * @param rows a row count of at least one
     * @return this table
     * @throws IllegalArgumentException if {@code rows} is below one
     */
    public Table<T> setVisibleRows(int rows) {
        Ui.checkUiThread();
        if (rows < 1) {
            throw new IllegalArgumentException("visible rows must be at least 1, not " + rows);
        }
        if (rows != visibleRows) {
            visibleRows = rows;
            markNeedsLayout();
        }
        return this;
    }

    /** How many seed rows tall the rows' viewport prefers to be under an unbounded height. */
    public int visibleRows() {
        return visibleRows;
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
        scrollBy(dx, dy, Change.Origin.CODE);
    }

    /**
     * The one seam the offsets move through, announced as {@code VALUE} with {@code origin}
     * when either moved: the public method and a reveal pass {@code CODE}, the wheel and the
     * bars pass {@code USER}, and a column brought into view for the focus cell passes
     * {@code ADJUSTMENT}. Each axis is clamped on its own.
     *
     * @return whether either offset moved
     */
    private boolean scrollBy(float dx, float dy, Change.Origin origin) {
        boolean moved = false;
        if (dy != 0) {
            scrollInHand = true;
            try {
                moved = engine.scrollBy(dy, origin);
            } finally {
                scrollInHand = false;
            }
        }
        if (dx != 0) {
            float max = Math.max(0, contentWidth - gutters.viewportWidth(width()));
            float next = Math.min(Math.max(0, offsetX + dx), max);
            if (next != offsetX) {
                float applied = next - offsetX;
                offsetX = next;
                float sign = isRightToLeft() ? 1 : -1;
                for (int i = 0; i < engine.mountedCount(); i++) {
                    Slot slot = engine.mountedAt(i);
                    for (Widget<?> w : slot.widgets) {
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
            notifyChange(Change.of(Change.Aspect.VALUE, origin));
        }
        return moved;
    }

    /** The horizontal bar's model writing the offset: the user dragging or paging the bar. */
    private void scrollTo(float newOffsetX) {
        scrollBy(newOffsetX - offsetX, 0, Change.Origin.USER);
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
        return Theme.of(this).tokensFor(this);
    }

    private float avgRowHeight(SizeTokens t) {
        return engine.meanHeight();
    }

    private float estimatedContentHeight(SizeTokens t) {
        return engine.contentHeight();
    }

    private float estimatedOffset(SizeTokens t) {
        return engine.offset();
    }

    private float headerHeight(SizeTokens t) {
        return showHeader ? t.controlHeight() : 0;
    }

    private float footerHeight(SizeTokens t) {
        return footerShown() ? t.controlHeight() : 0;
    }

    /**
     * Whether the footer strip shows: while any shown column has a footer text. Read from the
     * columns each time rather than kept, because a column is hidden or shown with no call to the
     * table but a layout, and a kept flag left the strip reserved and empty after its only column
     * was hidden.
     */
    private boolean footerShown() {
        int count = Math.min(columns.size(), footerTexts.length);
        for (int c = 0; c < count; c++) {
            if (footerTexts[c] != null && columns.get(c).isVisible()) {
                return true;
            }
        }
        return false;
    }

    /** The top of the footer row in this widget's coordinates, for this pass. */
    private float footerTop() {
        return gutters.viewportHeight(height()) - footerHeight(tokens());
    }

    /**
     * A column of this table changed through one of its setters, after the table was made: its
     * width, share, alignment or visibility is taken by the next layout and paint, its footer is
     * computed again at once when that is what changed, and a change to how it sorts reaches
     * {@link #columnSortChanged} too. A setter did nothing on a live table before, and an
     * application had to know to ask for the layout or a refresh.
     */
    void columnChanged(boolean footer) {
        if (footer) {
            recomputeFooter();
        }
        markNeedsLayout();
        invalidate();
    }

    /**
     * How a column of this table sorts changed, through {@link Column#comparator} or
     * {@link Column#sortable}. The column the rows are ordered on is ordered again at once, or its
     * order is dropped when it can no longer sort, through the seam {@link #setSort} takes, so the
     * focus cell, the range anchor and a widget cell holding the keyboard go with their records
     * and the selection, by model row, stays with its own; announced as a sort is,
     * {@code CHILDREN}/{@code CODE}. Until 2026-10-25 a comparator set on a live table took effect
     * at the next refresh or header click, and a column made unsortable kept its order and its
     * header's arrow. A table whose {@link #onSortRequest} orders the rows holds no order of its
     * own to redo, and only drops the header's arrow from a column that can no longer sort.
     */
    void columnSortChanged(Column<T> column) {
        // The header cell's PRESS and its sort direction are published from isSortable().
        invalidateAccessible();
        if (column != sortColumn || sortOrder == SortOrder.NONE) {
            return;
        }
        if (!column.isSortable()) {
            applySort(column, SortOrder.NONE, Change.Origin.CODE);
        } else if (onSortRequest == null) {
            permute(Change.Origin.CODE);
        }
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
        boolean wasShown = footerShown();
        // Under the table's own locale, as a pass would be: a format that reads I18n.locale()
        // when it formats has to see this table's language, not the thread's, and a refresh is
        // called from application code outside any pass.
        Locale enclosing = I18n.pushScope(locale);
        try {
            for (int c = 0; c < columns.size(); c++) {
                Column<T> column = columns.get(c);
                footerTexts[c] = column.footerText(rows, locale);
                footerShaped[c] = null;
            }
        } finally {
            I18n.popScope(enclosing);
        }
        if (footerShown() != wasShown) {
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
        // The free-axis height is the SEED's and not avgRowHeight's (decision 44, 2026-09-14):
        // the measured mean moves as rows of other heights scroll in, and a measured size that
        // moved under a contained layout re-laid out the parent on every such scroll
        // (Widget.markNeedsContainedLayout's contract), so a table in a scroll pane jittered.
        // The seed is a token and stands still.
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight()
                : headerHeight(t) + footerHeight(t) + visibleRows * t.listRowSeed();
        return constraints.constrain(w, h);
    }

    /**
     * Resolves which columns are shown and how wide each is for a viewport of {@code viewW}:
     * every column its own width, then the leftover shared by weight among the columns whose
     * width was not dragged.
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
            weights += column.leftoverWeight();
            at++;
        }
        float leftover = viewW - total;
        if (leftover > 0 && weights > 0) {
            for (int s = 0; s < n; s++) {
                float weight = columns.get(shownIndex[s]).leftoverWeight();
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
        boolean cursorMoved = false;
        if (headerFocused && !headerStopAvailable()) {
            headerFocused = false; // the header stopped being a stop: the rows have the keyboard
            cursorMoved = isFocused();
            invalidate();
        }
        cursorMoved |= shownSetResolved(n);
        if (cursorMoved) {
            // One announcement for the layout, however many of the cursors it moved.
            notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.ADJUSTMENT));
        }
    }

    /**
     * The shown set was just resolved: when it differs from the previous layout's, the rows are
     * re-mounted so that a hidden widget column's widgets are released and a newly shown one's are
     * built (until 2026-09-14 a hidden widget column built a widget per row that was a Tab stop and
     * a published node with a column past the table's), and the focus column is resolved again from
     * the column it stands on: the same column if it is still shown, else the nearest shown one,
     * announced as {@code ACTIVE}/{@code ADJUSTMENT} when the cell moved (until then the cursor
     * kept a shown index no column matched, so no ring was drawn and no cell was {@code ACTIVE}
     * until a Left or Right re-clamped it). The header's column cursor follows its column by the
     * same rule (until 2026-09-14 it kept a plain index clamp, so it slid onto another column).
     *
     * @return whether a cursor a reader stands on moved to another column, for the caller to
     *         announce as {@code ACTIVE}/{@code ADJUSTMENT}
     */
    private boolean shownSetResolved(int n) {
        boolean changed = n != shownBeforeCount;
        for (int s = 0; !changed && s < n; s++) {
            changed = shownIndex[s] != shownBefore[s];
        }
        if (!changed) {
            return false;
        }
        boolean first = shownBeforeCount < 0;
        if (shownBefore.length < n) {
            shownBefore = new int[n];
        }
        System.arraycopy(shownIndex, 0, shownBefore, 0, n);
        shownBeforeCount = n;
        if (!first) {
            remountWidgetColumns();
            invalidate(); // the columns moved, whatever the cursor did
        }
        if (n == 0) {
            focusColumn = 0;
            headerColumn = 0;
            return false;
        }
        int wasOf = focusColumnOf;
        focusColumn = focusColumnOf < 0 ? Math.min(Math.max(0, focusColumn), n - 1)
                : nearestShown(focusColumnOf);
        focusColumnOf = shownIndex[focusColumn];
        int wasHeaderOf = headerColumnOf;
        headerColumn = headerColumnOf < 0 ? Math.min(Math.max(0, headerColumn), n - 1)
                : nearestShown(headerColumnOf);
        headerColumnOf = shownIndex[headerColumn];
        // Announced only when the cell a reader stands on changed, which is when its column
        // did: a column hidden before the cursor shifts its shown index and moves nothing, and
        // while the header holds the cursor the focus cell is not where the reader is.
        boolean header = headerHoldsCursor();
        return !first && (header ? headerColumnOf != wasHeaderOf
                : focusRow >= 0 && focusColumnOf != wasOf);
    }

    /** Puts the header's column cursor on shown column {@code s}, clamped, and remembers its column. */
    private void setHeaderColumn(int s) {
        headerColumn = Math.min(Math.max(0, s), Math.max(0, shownCount - 1));
        if (shownCount > 0) {
            headerColumnOf = shownIndex[headerColumn];
        }
    }

    /**
     * Brings the realized rows' widget cells in line with the shown set: a hidden widget column's
     * widgets are released and a newly shown one's built, and every other widget cell stays where
     * it is, the one holding the keyboard included (until 2026-09-14 this re-mounted every row, so
     * hiding a value column took the keyboard off the switch a user was on and rebuilt every widget
     * cell). A released widget that held the keyboard hands it to the table, as a recycled row's
     * does.
     */
    private void remountWidgetColumns() {
        boolean handBack = false;
        int childAt = 2; // the two bars come first
        int count = rows.size();
        for (int i = 0; i < engine.mountedCount(); i++) {
            Slot slot = engine.mountedAt(i);
            for (int c = 0; c < columns.size(); c++) {
                Column<T> column = columns.get(c);
                Widget<?> widget = slot.widgets[c];
                if (!column.isWidgetColumn()) {
                    continue;
                }
                if (column.isVisible() && widget == null && slot.row < count
                        && (view == null || slot.row < view.length)) {
                    widget = column.widgetFor(rows.get(modelOf(slot.row)), recordOf(slot));
                    slot.widgets[c] = widget;
                    add(childAt, widget);
                    slot.widgetCount++;
                    childAt++;
                } else if (!column.isVisible() && widget != null) {
                    handBack |= holdsFocus(widget);
                    remove(widget);
                    slot.widgets[c] = null;
                    slot.widgetCount--;
                } else if (widget != null) {
                    childAt++;
                }
            }
        }
        if (handBack) {
            requestFocus();
        }
    }

    /**
     * @return the shown index of column {@code c}, or of the shown column nearest to it by
     *         position when it is hidden — the one before it on a tie; {@code shownCount} is
     *         at least one
     */
    private int nearestShown(int c) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int s = 0; s < shownCount; s++) {
            int distance = Math.abs(shownIndex[s] - c);
            if (distance < bestDistance) {
                best = s;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The left edge of shown column {@code s} in this widget's coordinates, for this pass. */
    private float columnLeft(int s, float rowX, float viewW, boolean rtl) {
        if (s == moveSlot && !Float.isNaN(movePointerX)) {
            return movingLeft(rowX, viewW, rtl); // under the pointer, not sliding anywhere
        }
        int c = shownIndex[s];
        return settledLeft(s, rowX, viewW, rtl) + (sliding && c < slide.length ? slide[c] : 0);
    }

    /** Where shown column {@code s} belongs in this pass, a move in flight counted, a slide not. */
    private float settledLeft(int s, float rowX, float viewW, boolean rtl) {
        float offset = s < frozen() ? 0 : offsetX; // a kept column does not scroll
        // While a column moves, the others stand where they would were it dropped now, and the
        // moving one, under the keyboard, in the place it would take.
        float x = moveSlot < 0 ? colX[s] : moveX[s];
        return rtl ? rowX + viewW - (x + colW[s]) + offset : rowX + x - offset;
    }

    // ---------------------------------------------------------------- empty and loading

    /**
     * What shows in the rows' viewport while there are no rows (a table with no data rows), and while the
     * table loads with none: the application's content, centred, at most the viewport's width less
     * {@code spacingLarge} on each side. The header and the footer row stay, as
     * they are.
     */
    private final RowsPlaceholder placeholder = new RowsPlaceholder(new RowsPlaceholder.Host() {
        @Override
        public Widget<?> widget() {
            return Table.this;
        }

        @Override
        public void attach(Widget<?> content) {
            add(content); // last: after the rows, for a reader and for the Tab key
        }

        @Override
        public void detach(Widget<?> content) {
            remove(content);
        }

        @Override
        public int rowCount() {
            return rows.size();
        }
    });

    /**
     * Shows {@code content} in the rows' viewport whenever the table has no rows (a table with no data rows), and
     * takes it away when one arrives: the table knows its count, so the application never says
     * when. It gives the words, "No orders" or "No orders match", which only it knows.
     *
     * <p>The content is the table's last child while it shows, so its own controls (a "Clear
     * filter" button) follow the table in the Tab order and a reader finds them after its (no)
     * rows; they belong to no selection. It is this table's own: one already in another
     * container is refused, and one given to two widgets shows in one only. A content passed this
     * way describes nothing to a reader: pass its message with
     * {@link #setEmptyContent(Widget, Label)}.
     *
     * @param content the content, or {@code null} for none
     * @return this table
     * @throws IllegalArgumentException when {@code content} is already in another container
     *                                  (another widget showing it included), or holds this table
     */
    public Table<T> setEmptyContent(@Nullable Widget<?> content) {
        Ui.checkUiThread();
        return setEmptyContent(content, null);
    }

    /**
     * Shows {@code content} while there are no rows, as {@link #setEmptyContent(Widget)} does, and
     * describes the table by {@code message} while it shows, so a reader landing on it hears
     * "table, 0 rows" and then the message.
     *
     * @param content the content, or {@code null} for none
     * @param message the label inside it that says why there are no rows, or {@code null}
     * @return this table
     * @throws IllegalArgumentException when {@code content} is already in another container
     *                                  (another widget showing it included), or holds this table
     */
    public Table<T> setEmptyContent(@Nullable Widget<?> content, @Nullable Label message) {
        Ui.checkUiThread();
        placeholder.setEmpty(content, message);
        return this;
    }

    /**
     * Shows one muted, wrapped, centred line while there are no rows, which also describes the
     * table to a reader: the empty content for words alone.
     *
     * @param message the words, or {@code null} for no empty content
     * @return this table
     */
    public Table<T> setEmptyMessage(@Nullable I18nString message) {
        Ui.checkUiThread();
        placeholder.setEmptyMessage(message);
        return this;
    }

    /**
     * Shows one fixed line while there are no rows; see {@link #setEmptyMessage(I18nString)}.
     *
     * @param message the words, or {@code null} for no empty content
     * @return this table
     */
    public Table<T> setEmptyMessage(@Nullable String message) {
        Ui.checkUiThread();
        return setEmptyMessage(message == null ? null : I18nString.literal(message));
    }

    /**
     * Takes the message shown with no rows away, as {@code setEmptyMessage(null)} would: that call does not compile, because
     * {@code null} fits more than one of the overloads.
     *
     * @return this table
     */
    public Table<T> clearEmptyMessage() {
        return setEmptyMessage((I18nString) null);
    }

    /** @return the content shown while there are no rows, or {@code null} */
    public @Nullable Widget<?> emptyContent() {
        return placeholder.emptyContent();
    }

    /**
     * @return whether the empty content shows now, as the last layout placed it: there is one, no
     *         rows and no load running, and no other widget holds it
     */
    public boolean isShowingEmptyContent() {
        return placeholder.isShowingEmpty();
    }

    /**
     * Says the table is loading its rows, or has finished. While it loads with no rows, the
     * loading content shows in the viewport in place of the empty one; over rows already shown,
     * they stay. Either way the table is published busy, and a reader is told both ends: "Loading
     * Orders" at the start, with the name a reader hears the table by (given by
     * {@code setAccessibleName}, or by a caption bound with {@link Label#setLabelFor}), and at the
     * end how many rows arrived, or the empty message when none did. A table in a hidden container,
     * on a tab page not chosen or on a face a transform does not draw is busy and says nothing of
     * its load. Set the rows first, then say the load ended, so the count said is the count shown.
     *
     * @param loading whether a load is running
     * @return this table
     */
    public Table<T> setLoading(boolean loading) {
        Ui.checkUiThread();
        placeholder.setLoading(loading);
        return this;
    }

    /** @return whether a load is running, as {@link #setLoading} last said */
    public boolean isLoading() {
        return placeholder.isLoading();
    }

    /**
     * Replaces what shows while the table loads with no rows; {@code null} brings back the
     * default, a moving bar over a muted "Loading…" in the table's language.
     *
     * @param content the content, or {@code null} for the default
     * @return this table
     * @throws IllegalArgumentException when {@code content} is already in another container
     *                                  (another widget showing it included), or holds this table
     */
    public Table<T> setLoadingContent(@Nullable Widget<?> content) {
        Ui.checkUiThread();
        return setLoadingContent(content, null);
    }

    /**
     * Replaces what shows while the table loads with no rows, and describes the table by
     * {@code message} while it shows.
     *
     * @param content the content, or {@code null} for the default
     * @param message the label inside it that says what is loading, or {@code null}
     * @return this table
     * @throws IllegalArgumentException when {@code content} is already in another container
     *                                  (another widget showing it included), or holds this table
     */
    public Table<T> setLoadingContent(@Nullable Widget<?> content, @Nullable Label message) {
        Ui.checkUiThread();
        placeholder.setLoadingContent(content, message);
        return this;
    }

    /**
     * @return whether a loading content, the application's or the default, shows now, as the
     *         last layout placed it
     */
    public boolean isShowingLoadingContent() {
        return placeholder.isShowingLoading();
    }

    /** Leaving the window, the empty or loading content shown goes back to being nobody's child. */
    @Override
    protected void onDetached() {
        super.onDetached();
        placeholder.hostDetached();
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
            if (onSortRequest == null && sortColumn != null && sortOrder != SortOrder.NONE) {
                // A text sort is the language's order: German puts "ä" before "z", Swedish
                // after it. Placed by this same pass, so announced but not laid out again.
                int[] was = view;
                int wasFocusRow = reorder();
                if (!Arrays.equals(was, view)) {
                    announceFocusCell(wasFocusRow, Change.Origin.ADJUSTMENT);
                    notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.ADJUSTMENT));
                }
            }
            for (int i = 0; i < engine.mountedCount(); i++) {
                rebind(engine.mountedAt(i));
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
        // Clamped against the viewport the strips leave, and only here: the gutters measure
        // the content first against the whole box, and a clamp in resolveColumns took that
        // probe's width for the viewport, so a table scrolled to its last column lost a
        // vertical strip's width of it for good (under RESERVED, the last column's left edge
        // sat under the strip right to left, and its right edge under it left to right).
        offsetX = Math.max(0, Math.min(offsetX, Math.max(0, contentWidth - w)));
        float barT = ScrollBar.thickness();
        vBar.measure(Constraints.tight(barT, viewH));
        vBar.layoutBox(rtl ? 0 : box - barT, headerH, barT, viewH);
        hBar.measure(Constraints.tight(w, barT));
        hBar.layoutBox(rtl ? box - w : 0, boxH - barT, w, barT);
        float rowX = rtl ? box - w : 0;

        passRowX = rowX;
        passRowW = w;
        passHeaderH = headerH;
        passRtl = rtl;
        engine.layout();
        vBar.refresh();
        hBar.refresh();
        placeholder.layout(rowX, headerH, w, viewH, t.spacingLarge());
    }

    /** Lays out a row's widget cells into their column boxes; a value cell has no layout. */
    private void placeRow(Slot slot, float rowY, float rowH, float rowX, float w, boolean rtl,
                          SizeTokens t) {
        slot.height = rowH;
        slot.top = rowY;
        for (int s = 0; s < shownCount; s++) {
            int c = shownIndex[s];
            Widget<?> widget = slot.widgets[c];
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

    // ------------------------------------------------------------------------- mounting

    private @Nullable Slot slotFor(int index) {
        return engine.mounted(index);
    }

    /** Where {@code widget} sits: the mounted position of its row, or {@code -1}. */
    private int mountedPositionOf(Widget<?> widget) {
        for (int i = 0; i < engine.mountedCount(); i++) {
            for (Widget<?> w : engine.mountedAt(i).widgets) {
                if (w == widget) {
                    return i;
                }
            }
        }
        return -1;
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
        engine.refresh(null, -1);
    }

    /** The mounted row one of whose widget cells holds the keyboard, or {@code null}. */
    private Slot focusedWidgetSlot() {
        for (int i = 0; i < engine.mountedCount(); i++) {
            if (engine.mountedAt(i).widgetCount > 0 && containsFocus(engine.mountedAt(i))) {
                return engine.mountedAt(i);
            }
        }
        return null;
    }

    /**
     * Where the list holds {@code slot}'s record now, or {@code -1}: by its key and its ordinal
     * when the ordinal is known (a {@link #rowKey}, or a row a reader was told of), else at the
     * occurrence of its key nearest the row it stood at, which is exact for a record no other
     * row equals. One read of the rows at most.
     */
    private int findAgain(Slot slot) {
        int was = modelOf(slot.row);
        if (rowKey != null || slot.ordinalKnown) {
            return rediscover(new Object[] {slot.key},
                    new int[] {rowKey != null ? 0 : slot.ordinal}, 1)[0];
        }
        int best = -1;
        int count = rows.size();
        for (int m = 0; m < count; m++) {
            if (best >= 0 && m - was > Math.abs(was - best)) {
                break; // every row from here is further than the one found
            }
            if (Objects.equals(keyOf(rows.get(m)), slot.key)
                    && (best < 0 || Math.abs(m - was) < Math.abs(best - was))) {
                best = m;
            }
        }
        return best;
    }

    /**
     * The mounted rows to carry through a refresh, found again among the rows: {@code focused},
     * whose widget cell holds the keyboard, and every row a popup hangs from by a widget cell.
     *
     * @param focused      the row whose widget cell holds the keyboard, or null
     * @param sameRecords  whether the rows are the records the widget cells were built for, as in
     *                     a {@link #refresh()}; after {@link #setRows} they may be new objects
     *                     equal to the old, and a row the keyboard is in or due back to is then
     *                     not kept, since its widget cells would edit the old object
     * @return each one's record's model index now; those whose record is gone are left out
     */
    private java.util.IdentityHashMap<Slot, Integer> slotsKept(@Nullable Slot focused, boolean sameRecords) {
        java.util.IdentityHashMap<Slot, Integer> kept = new java.util.IdentityHashMap<>();
        for (int p = 0; p < engine.mountedCount(); p++) {
            Slot slot = engine.mountedAt(p);
            if (slot.widgetCount > 0 && (slot == focused || holdsHungPopup(slot))
                    && (sameRecords || !holdsTextKeyboard(slot))) {
                int model = findAgain(slot);
                if (model >= 0 && !kept.containsValue(model)) {
                    kept.put(slot, model);
                }
            }
        }
        return kept;
    }

    /** @return whether a text-taking widget of one of {@code slot}'s cells holds the keyboard or has it due back */
    private boolean holdsTextKeyboard(Slot slot) {
        for (int c = 0; c < slot.widgets.length; c++) {
            if (slot.widgets[c] != null && SceneAccess.holdsTextKeyboard(slot.widgets[c])) {
                return true;
            }
        }
        return false;
    }

    /** @return whether the keyboard is in one of {@code slot}'s widget cells, or due back to one */
    private boolean holdsKeyboard(Slot slot) {
        for (int c = 0; c < slot.widgets.length; c++) {
            if (slot.widgets[c] != null && SceneAccess.holdsKeyboard(slot.widgets[c])) {
                return true;
            }
        }
        return false;
    }

    /** @return whether a popup hangs from one of {@code slot}'s widget cells */
    private boolean holdsHungPopup(Slot slot) {
        for (int c = 0; c < slot.widgets.length; c++) {
            if (slot.widgets[c] != null && SceneAccess.holdsHungPopup(slot.widgets[c])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Releases every mounted row but those in {@code keep}, each of whose record stands at the
     * model index it maps to now: one whose widget cell holds the keyboard, or from whose widget
     * cell a popup hangs. Each stays mounted, its widgets children, the focus where it is and the
     * popup open, re-bound to the row that shows its record, and its widget cells' supplier
     * re-pointed to the object the list holds for that record now. Until 2026-09-15 a refresh or
     * a sort released the focused one and handed the keyboard to the table, which is
     * {@code ListView}'s rule for rows bound to data the list may no longer hold; a table follows
     * its records, and a record found again is the one the widget was built for, or an object
     * equal to it that its supplier answers from then on. With no row to keep this is
     * {@link #unmountAll()}.
     *
     * @param keep  the rows to keep, with their records' model indices now
     * @param reset whether the view goes back to the start, for new rows altogether
     */
    private void unmountAllKeeping(java.util.IdentityHashMap<Slot, Integer> keep, boolean reset) {
        java.util.IdentityHashMap<Slot, Integer> views = new java.util.IdentityHashMap<>();
        keep.forEach((slot, model) -> {
            if (model >= 0 && model < rows.size()) {
                views.put(slot, viewOf(model));
            }
        });
        // Every other row is let go of, and these are the only realized rows, at their new indices.
        java.util.function.ToIntFunction<Slot> keepAt = slot -> views.getOrDefault(slot, -1);
        if (reset) {
            engine.reset(keepAt, false);
        } else if (views.isEmpty()) {
            unmountAll();
            return;
        } else {
            engine.refresh(keepAt, false);
        }
        for (int p = 0; p < engine.mountedCount(); p++) {
            Slot slot = engine.mountedAt(p);
            int model = keep.get(slot);
            T record = rows.get(model);
            slot.row = engine.rowAt(p);
            slot.top = Float.NaN;
            slot.id = rowIdOf(model);
            slot.key = keyOf(record);
            // The object the list holds now, which after a push of new objects equal to the old is
            // not the one the widget cells were built with: a cell that reads its record from the
            // supplier writes to this one (R17-1), where one holding the old object wrote to a
            // record the table no longer held and the row showed the old value when built again.
            recordOf(slot).record = record;
            slot.ordinalKnown = false;
            rebind(slot);
        }
    }

    private boolean isPlaced(int index) {
        return engine.isPlaced(index);
    }

    private boolean containsFocus(Slot slot) {
        Widget<?> focused = scene() != null ? scene().focusedWidget() : null;
        for (Widget<?> w = focused; w != null; w = w.parent()) {
            for (int c = 0; c < columns.size(); c++) {
                if (slot.widgets[c] == w) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return whether the keyboard focus is on {@code cell} or inside it */
    private boolean holdsFocus(Widget<?> cell) {
        Widget<?> focused = scene() != null ? scene().focusedWidget() : null;
        for (Widget<?> w = focused; w != null; w = w.parent()) {
            if (w == cell) {
                return true;
            }
        }
        return false;
    }

    /** A placed row's top in the table's own box, under the header; NaN for any other row. */
    private float rowTop(int index) {
        float y = engine.topOf(index);
        return Float.isNaN(y) ? Float.NaN : y + rowsTop();
    }

    /**
     * Has a row in sight after the next pass, which brings it in by the least scroll from the
     * edge it lies beyond, by the heights it measures, and moves nothing for a row already in
     * the box.
     */
    private void ensureVisible(int index) {
        float top = isPlaced(index) ? rowTop(index) : Float.NaN;
        Slot slot = slotFor(index);
        if (!Float.isNaN(top) && slot != null && top >= rowsTop()
                && top + slot.height <= rowsTop() + rowsViewportHeight()) {
            // In the box already: no pass is asked for, which would repaint the table for an
            // arrow that moved between two rows in sight; and a reveal asked before is forgotten.
            engine.cancelReveal();
            return;
        }
        engine.reveal(() -> Math.min(index, rows.size() - 1));
        // Contained: a reveal changes which rows are realized and where they sit, inside a box
        // this widget clips and whose own size a reveal cannot move.
        markNeedsContainedLayout();
    }

    private void ensureColumnVisible(int s) {
        if (s < 0 || s >= shownCount) {
            return;
        }
        if (s < frozen()) {
            return; // a kept column is always in view
        }
        float viewW = gutters.viewportWidth(width());
        float kept = frozenWidth();
        float left = colX[s] - offsetX - kept; // from where the scrolling columns begin to show
        float right = colX[s] - offsetX + colW[s];
        if (left < 0) {
            scrollBy(left, 0, Change.Origin.ADJUSTMENT);
        } else if (right > viewW) {
            scrollBy(Math.min(left, right - viewW), 0, Change.Origin.ADJUSTMENT);
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
        Locale locale = locale();
        Locale enclosing = I18n.pushScope(locale);
        try {
            Collator collator = I18n.collator();
            Column<T> column = sortColumn;
            int sign = sortOrder == SortOrder.DESCENDING ? -1 : 1;
            List<T> data = rows;
            int[] sorted = new int[n];
            if (column.sortsByComparator()) {
                // The application's own comparator decides, so it is called as it always was.
                Integer[] order = new Integer[n];
                for (int i = 0; i < n; i++) {
                    order[i] = i;
                }
                Arrays.sort(order, (a, b) -> {
                    int c = column.compare(data.get(a), data.get(b), collator, locale);
                    return c != 0 ? sign * c : Integer.compare(a, b);
                });
                for (int i = 0; i < n; i++) {
                    sorted[i] = order[i];
                }
            } else {
                // Each row's key once, then the sort compares keys (decision 114).
                Column.SortKey[] keys = new Column.SortKey[n];
                for (int i = 0; i < n; i++) {
                    keys[i] = column.sortKey(data.get(i), i, collator);
                }
                Arrays.sort(keys, (a, b) -> {
                    int c = column.compareKeys(a, b, collator, locale);
                    return c != 0 ? sign * c : Integer.compare(a.index, b.index);
                });
                for (int i = 0; i < n; i++) {
                    sorted[i] = keys[i].index;
                }
            }
            view = sorted;
        } finally {
            I18n.popScope(enclosing);
        }
    }

    private void headerClicked(int s) {
        Column<T> column = columns.get(shownIndex[s]);
        if (!column.isSortable()) {
            return;
        }
        SortOrder next = column == sortColumn ? sortOrder.next() : SortOrder.ASCENDING;
        if (onSortRequest != null) {
            // Recorded before the announcement, so the handler reads the request the click made
            // rather than the order the table holds, which for the model's order is none.
            sortColumn = next == SortOrder.NONE ? null : column;
            sortOrder = next;
            sortRequestColumn = column;
            sortRequestOrder = next;
            invalidate();
            notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.USER));
            return;
        }
        applySort(column, next, Change.Origin.USER);
    }

    // ---------------------------------------------------------------------- selection core

    /**
     * Selects one model row alone and moves the lead and the focus cell to it: the one seam a
     * single selection goes through, with the origin of whoever asked. The focus cell is
     * announced first as {@code ACTIVE} when it moved, and the selection last.
     */
    private void selectOnly(int modelIndex, int viewIndex, boolean reveal, Change.Origin origin) {
        selectOnly(modelIndex, viewIndex, reveal, true, origin);
    }

    /**
     * The same, with the focus cell held back.
     *
     * @param moveCursor whether the focus cell and the range anchor land on the row: a gesture's
     *                   answer is yes, because the pointer and the key are where the user is; a
     *                   reader's {@code SELECT} is no, because a client write is not (measured on
     *                   macOS: a selection write leaves the keyboard focus alone on AppKit, and
     *                   VoiceOver's cursor sync dragged this one back with its own stale row). The
     *                   reveal is not the cursor and still happens.
     */
    private void selectOnly(int modelIndex, int viewIndex, boolean reveal, boolean moveCursor,
                            Change.Origin origin) {
        int wasFocusRow = focusRow;
        BitSet before = (BitSet) selected.clone();
        if (moveCursor) {
            focusRow = viewIndex;
            rangeAnchor = viewIndex;
        }
        if (reveal) {
            // Damages the table itself when it scrolls, which is right then: a scroll re-mounts
            // every row, so a pair of bands would be a lie.
            ensureVisible(viewIndex);
        }
        if (selectionMode == SelectionMode.NONE) {
            syncRecords();
            damageSelectionChange(before, wasFocusRow);
            announceFocusCell(wasFocusRow, origin);
            return;
        }
        if (lead == modelIndex && selected.cardinality() == 1 && selected.get(modelIndex)) {
            syncRecords();
            damageSelectionChange(before, wasFocusRow);
            announceFocusCell(wasFocusRow, origin);
            return;
        }
        selected.clear();
        selected.set(modelIndex);
        lead = modelIndex;
        syncRecords();
        damageSelectionChange(before, wasFocusRow);
        announceFocusCell(wasFocusRow, origin);
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /** Extends the selection from the range anchor to {@code viewIndex}, in MULTI: a gesture. */
    private void selectRange(int viewIndex) {
        int wasFocusRow = focusRow;
        BitSet before = (BitSet) selected.clone();
        int from = rangeAnchor < 0 ? viewIndex : rangeAnchor;
        selected.clear();
        for (int v = Math.min(from, viewIndex); v <= Math.max(from, viewIndex); v++) {
            selected.set(modelOf(v));
        }
        lead = modelOf(viewIndex);
        focusRow = viewIndex;
        syncRecords();
        ensureVisible(viewIndex);
        damageSelectionChange(before, wasFocusRow);
        announceFocusCell(wasFocusRow, Change.Origin.USER);
        notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.USER));
    }

    /** Toggles one row's membership, in MULTI, and moves the cursor to it: a gesture. */
    private void toggle(int viewIndex) {
        toggle(viewIndex, true);
    }

    /**
     * Toggles one row's membership, in MULTI, as a user. The command-click and Space move the focus
     * cell and the range anchor to the row ({@code moveCursor}); a reader's
     * {@code ADD_TO_SELECTION} and {@code DESELECT} leave both where they stand and scroll nothing,
     * because only {@code SELECT} and {@code FOCUS} move a cursor: a reader that adds a row it
     * reached by its own navigation has not asked the user's cursor to follow it.
     */
    private void toggle(int viewIndex, boolean moveCursor) {
        int wasFocusRow = focusRow;
        BitSet before = (BitSet) selected.clone();
        int model = modelOf(viewIndex);
        selected.flip(model);
        lead = selected.get(model) ? model : (selected.isEmpty() ? -1 : lead == model
                ? selected.length() - 1 : lead);
        if (moveCursor) {
            focusRow = viewIndex;
            rangeAnchor = viewIndex;
        }
        syncRecords();
        if (moveCursor) {
            ensureVisible(viewIndex);
        }
        damageSelectionChange(before, wasFocusRow);
        announceFocusCell(wasFocusRow, Change.Origin.USER);
        notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.USER));
    }

    /**
     * Page Down and Page Up: the cursor moves {@code delta} rows and, when it was on screen, the
     * view moves by the same rows, so the cursor keeps its place in the view. The least-scroll
     * reveal alone, which is what this did until 2026-09-15, put the first Page Down's row at the
     * foot of the view and scrolled by one row; a cursor that was off screen is revealed as any
     * other move reveals it.
     */
    private void pageFocusRow(int delta, int modifiers) {
        int count = rows.size();
        if (count == 0) {
            return;
        }
        int from = focusRow < 0 ? engine.anchorIndex() : Math.min(focusRow, count - 1);
        int target = Math.min(Math.max(0, from + delta), count - 1);
        moveFocusRow(target, modifiers);
        // The view goes as far as the cursor did, so the cursor keeps its place in the box,
        // when the row it left was wholly in sight.
        if (engine.pageWith(from, target)) {
            markNeedsContainedLayout(); // the layout clamps the view at the last row
            invalidate();
        }
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
            selectOnly(modelOf(v), v, true, Change.Origin.USER);
        }
    }

    /**
     * How many changed rows are still worth damaging one at a time before the whole table is
     * cheaper. The damage list holds eight rectangles and merges what it must; past a handful of
     * bands the union is the table anyway, and a select-all should not walk five hundred rows to
     * discover that.
     */
    private static final int MAX_DAMAGED_ROWS = 6;

    /**
     * Damages one row's band, or nothing when that row is not on screen.
     *
     * <p>The band is the row across the rows' viewport, and it needs no outset: the stripe and the
     * selection tint fill exactly it, and the focus cell's ring is drawn <em>inset</em> within one
     * column of it. A row outside the placed run has no box to damage, and whatever put it out of
     * view damaged the table on its own.
     *
     * @param viewIndex a row in view order, or any negative for no row
     */
    private void damageRow(int viewIndex) {
        if (viewIndex < 0 || !isPlaced(viewIndex)) {
            return;
        }
        Slot slot = slotFor(viewIndex);
        float rowY = rowTop(viewIndex);
        if (slot == null || Float.isNaN(rowY)) {
            return;
        }
        // Clamped to the rows' viewport, because nothing else will: damage is clipped by every
        // ANCESTOR that clips its children, and this widget is not its own ancestor. The paint
        // clips to exactly this rectangle, so a band outside it would repaint pixels this table
        // never draws -- the header above, or whatever sits below the table.
        float viewTop = rowsTop();
        float viewBottom = viewTop + rowsViewportHeight();
        float top = Math.max(viewTop, rowY);
        float bottom = Math.min(viewBottom, rowY + slot.height);
        if (bottom > top) {
            invalidate(rowsLeft(), top, gutters.viewportWidth(width()), bottom - top);
        }
    }

    /**
     * Damages what a selection move actually changed: every row that entered or left the
     * selection, and the row the focus cell came from and went to.
     *
     * <p>Taken as the difference between a snapshot and the result rather than reasoned about per
     * call site, because the four seams that mutate the selection &mdash; select one, extend a
     * range, toggle, select all &mdash; change between one row and every row, and only the
     * difference knows which. An arrow key comes out as two bands; a select-all comes out over
     * the ceiling and takes the table, which is the honest answer for it.
     *
     * @param before      the selection as it was, cloned before the mutation
     * @param wasFocusRow the focus row as it was, in view order
     */
    private void damageSelectionChange(BitSet before, int wasFocusRow) {
        BitSet changed = (BitSet) before.clone();
        changed.xor(selected);
        if (changed.cardinality() > MAX_DAMAGED_ROWS) {
            invalidate();
            return;
        }
        for (int m = changed.nextSetBit(0); m >= 0; m = changed.nextSetBit(m + 1)) {
            damageRow(viewOf(m));
        }
        damageRow(wasFocusRow);
        damageRow(focusRow);
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
            selectionTint = theme.primary().withAlpha(0.18f);
            // The text colour, faintly, and not the raised surface: the text always contrasts
            // with the surface it sits on, in every palette, while a raised surface sits one or
            // two greys away from the flat one in the light palettes and half of that is nothing.
            stripeTint = theme.text().withAlpha(0.045f);
        }
    }

    @Override
    protected void paintChildren(Canvas canvas) {
        Theme theme = Theme.of(this);
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
            for (int i = 0; i < engine.mountedCount(); i++) {
                int row = engine.rowAt(i);
                Slot slot = engine.mountedAt(i);
                float top = rowTop(row);
                if (Float.isNaN(top) || top >= headerH + viewH || top + slot.height <= headerH) {
                    continue; // the focused row a scroll spared, kept outside the viewport
                }
                int model = modelOf(row);
                if (selected.get(model)) {
                    canvas.fillRect(rowX, top, w, slot.height, selectionTint);
                    // The tint alone is 1.2:1 against the rows beside it; the bar is 3:1.
                    limn.components.internal.SelectionMark.paint(canvas, rowX, top, w, slot.height, rtl,
                            isEnabled() ? theme.primary() : theme.disabledText());
                } else if (striped && (row & 1) == 1) {
                    canvas.fillRect(rowX, top, w, slot.height, stripeTint);
                }
                int kept = frozen();
                boolean scrollingClip = clipToScrolling(canvas, kept, rowX, w, rtl);
                for (int k = 0; k < shownCount; k++) {
                    int s = paintOrder(k, kept);
                    if (scrollingClip && (s < kept || (s == moveSlot && k == shownCount - 1))) {
                        canvas.restore(); // the scrolling columns are drawn: the kept ones go over them
                        scrollingClip = false;
                    }
                    float left = columnLeft(s, rowX, w, rtl);
                    if (s == moveSlot) {
                        // The moving column is whole and opaque: a ground of its own, then the row's tint.
                        canvas.fillRect(left, top, colW[s], slot.height, theme.surfaceRaised());
                        if (selected.get(model)) {
                            canvas.fillRect(left, top, colW[s], slot.height, selectionTint);
                        } else if (striped && (row & 1) == 1) {
                            canvas.fillRect(left, top, colW[s], slot.height, stripeTint);
                        }
                    }
                    if (left >= rowX + w || left + colW[s] <= rowX) {
                        continue; // out of the horizontal viewport
                    }
                    int c = shownIndex[s];
                    Widget<?> widget = slot.widgets[c];
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
                    if (slot.texts[c] == null || slot.texts[c].isEmpty()) {
                        continue; // a widget column has no text; its widget was painted above
                    }
                    ShapedText shaped = shapedCell(slot, c, ruler, body);
                    float available = Math.max(0, colW[s] - 2 * padH);
                    ShapedText line = fittedCell(slot, c, shaped, available, ruler);
                    float tw = line.metrics().width();
                    float x = textX(columns.get(c).alignment(), left, colW[s], tw, padH, rtl);
                    float baseline = top + ruler.centredBaseline(body, slot.height);
                    canvas.drawText(line, x, baseline, theme.text());
                }
                if (row == focusRow && isFocused() && !headerFocused
                        && focusColumn < shownCount) {
                    float left = columnLeft(focusColumn, rowX, w, rtl);
                    float inset = Strokes.FOCUS_RING_THIN;
                    // The ring of a cell that scrolls goes under the kept columns with its cell:
                    // drawn after them and unclipped, it stood on top of the column it was under.
                    boolean under = focusColumn >= kept && clipToScrolling(canvas, kept, rowX, w, rtl);
                    canvas.drawRoundRect(left + inset, top + inset,
                            colW[focusColumn] - 2 * inset, slot.height - 2 * inset,
                            t.radiusSmall(), Strokes.FOCUS_RING_THIN, theme.focusRing());
                    if (under) {
                        canvas.restore();
                    }
                }
            }
            // The empty or loading content, between the header and the footer.
            placeholder.paint(canvas);
        } finally {
            canvas.restore();
        }

        if (footerShown()) {
            float footerH = footerHeight(t);
            float top = footerTop();
            canvas.save();
            try {
                canvas.clipRect(rowX, top, w, footerH);
                canvas.fillRect(rowX, top, w, footerH, theme.surfaceRaised());
                canvas.drawLine(rowX, top + Strokes.HALF_PIXEL_INSET, rowX + w,
                        top + Strokes.HALF_PIXEL_INSET, Strokes.HAIRLINE, theme.outline());
                Font font = headerFont(t);
                int kept = frozen();
                boolean scrollingClip = clipToScrolling(canvas, kept, rowX, w, rtl);
                for (int k = 0; k < shownCount; k++) {
                    int s = paintOrder(k, kept);
                    if (scrollingClip && (s < kept || (s == moveSlot && k == shownCount - 1))) {
                        canvas.restore(); // the scrolling columns are drawn: the kept ones go over them
                        scrollingClip = false;
                    }
                    float left = columnLeft(s, rowX, w, rtl);
                    if (s == moveSlot) {
                        canvas.fillRect(left, top, colW[s], footerH, theme.surfaceRaised());
                    }
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
                    float baseline = top + ruler.centredBaseline(font, footerH);
                    canvas.drawText(line, x, baseline, theme.text());
                }
            } finally {
                canvas.restore();
            }
        }

        if (showHeader) {
            canvas.save();
            try {
                canvas.clipRect(rowX, 0, w, headerH);
                canvas.fillRect(rowX, 0, w, headerH, theme.surfaceRaised());
                Font font = headerFont(t);
                int kept = frozen();
                boolean scrollingClip = clipToScrolling(canvas, kept, rowX, w, rtl);
                for (int k = 0; k < shownCount; k++) {
                    int s = paintOrder(k, kept);
                    if (scrollingClip && (s < kept || (s == moveSlot && k == shownCount - 1))) {
                        canvas.restore(); // the scrolling columns are drawn: the kept ones go over them
                        scrollingClip = false;
                    }
                    float left = columnLeft(s, rowX, w, rtl);
                    if (s == moveSlot) {
                        canvas.fillRect(left, 0, colW[s], headerH, theme.surfaceRaised());
                    }
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
                    float baseline = ruler.centredBaseline(font, headerH);
                    canvas.drawText(line, x, baseline, theme.textMuted());
                    if (indicator > 0) {
                        // A chevron after the title on the reading side: up for ascending.
                        float half = t.chevronHalfW();
                        float cx = rtl ? left + padH + half : left + colW[s] - padH - half;
                        float cy = headerH / 2;
                        float dy = sortOrder == SortOrder.ASCENDING ? half / 2 : -half / 2;
                        canvas.drawLine(cx - half, cy + dy, cx, cy - dy, Strokes.ARROW_PEN,
                                theme.textMuted());
                        canvas.drawLine(cx, cy - dy, cx + half, cy + dy, Strokes.ARROW_PEN,
                                theme.textMuted());
                    }
                    // The divider at the column's trailing edge, which is where a drag resizes it.
                    float edge = rtl ? left : left + colW[s];
                    canvas.drawLine(edge, t.padV(), edge, headerH - t.padV(), Strokes.HAIRLINE,
                            hoverDivider == s || dragColumn == s ? theme.focusRing() : theme.outline());
                }
                canvas.drawLine(rowX, headerH - Strokes.HALF_PIXEL_INSET, rowX + w,
                        headerH - Strokes.HALF_PIXEL_INSET, Strokes.HAIRLINE, theme.outline());
                if (headerHoldsCursor() && headerColumn < shownCount) {
                    // The header's column cursor: the same thin ring the focus cell wears,
                    // inset in the header cell, so one mark means "the keyboard is here" in
                    // both stops (decision 36 of 2026-09-14; renders reviewed by the owner).
                    float left = columnLeft(headerColumn, rowX, w, rtl);
                    float inset = Strokes.FOCUS_RING_THIN;
                    int keptNow = frozen();
                    boolean under = headerColumn >= keptNow && clipToScrolling(canvas, keptNow, rowX, w, rtl);
                    canvas.drawRoundRect(left + inset, inset, colW[headerColumn] - 2 * inset,
                            headerH - 2 * inset, t.radiusSmall(), Strokes.FOCUS_RING_THIN,
                            theme.focusRing());
                    if (under) {
                        canvas.restore();
                    }
                }
            } finally {
                canvas.restore();
            }
        }

        int kept = frozen();
        if (kept > 0) {
            // Where the kept columns end, from the header to the footer.
            float edge = rtl ? rowX + w - colX[kept] : rowX + colX[kept];
            float bottom = footerShown() ? footerTop() + footerHeight(t) : headerH + viewH;
            canvas.drawLine(edge, 0, edge, bottom, Strokes.HAIRLINE * 2, theme.outline());
        }
        if (moveSlot >= 0 && moveSlot < shownCount) {
            // The moving column's two edges, from the header down, so that it reads as lifted.
            float left = columnLeft(moveSlot, rowX, w, rtl);
            float bottom = footerShown() ? footerTop() + footerHeight(t) : headerH + viewH;
            canvas.fillRect(left, 0, 1, bottom, theme.focusRing());
            canvas.fillRect(left + colW[moveSlot] - 1, 0, 1, bottom, theme.focusRing());
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
    protected @Nullable Widget<?> onHitTest(float localX, float localY) {
        Widget<?> hit = hitTestChild(vBar, localX, localY);
        if (hit != null) {
            return hit;
        }
        hit = hitTestChild(hBar, localX, localY);
        if (hit != null) {
            return hit;
        }
        // A cell widget is hit only inside the rows' viewport, past the kept columns' band for a
        // cell that scrolls: hitTestChild cuts each child at the clip rectangle this table answers
        // for it, which is where the paint cuts it, so a cell scrolled under the header, the
        // footer, a reserved gutter or a kept column takes no press there.
        for (Widget<?> child : children()) {
            if (child == vBar || child == hBar) {
                continue;
            }
            Widget<?> inner = hitTestChild(child, localX, localY);
            if (inner != null) {
                return inner;
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
        int kept = frozen();
        boolean under = overFrozen(x, rowX, w, rtl);
        for (int s = 0; s < shownCount; s++) {
            if (s >= kept && under) {
                break; // a scrolling column is not there to be hit under a kept one
            }
            float left = settledLeft(s, rowX, w, rtl); // not where a slide is passing
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
        int kept = frozen();
        boolean under = overFrozen(x, rowX, w, rtl);
        for (int s = 0; s < shownCount; s++) {
            if (s >= kept && under) {
                break;
            }
            float left = settledLeft(s, rowX, w, rtl); // not where a slide is passing
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
        for (int i = 0; i < engine.mountedCount(); i++) {
            int row = engine.rowAt(i);
            float top = rowTop(row);
            if (!Float.isNaN(top) && y >= top && y < top + engine.mountedAt(i).height) {
                return row;
            }
        }
        return -1;
    }

    @Override
    public @Nullable Cursor cursor() {
        return hoverDivider >= 0 || dragColumn >= 0 ? Cursor.RESIZE_EW : super.cursor();
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        Vec2 local = sceneToLocal(event.x(), event.y());
        float x = local.x();
        float y = local.y();
        switch (event.type()) {
            case WHEEL -> {
                // A detent is a device unit: the same flick travels the same distance in a dense
                // table and a roomy one, so the step is locked, not tabled. The two axes are taken
                // independently, as ScrollView takes them: until 2026-09-14 any scrollX made the
                // event sideways and dropped scrollY, so a trackpad swipe that was not perfectly
                // vertical scrolled nothing on a table whose columns fit. Shift turns a plain
                // vertical wheel into a horizontal one for a mouse with one wheel; taken only when
                // the event carries no scrollX, so a tilt wheel and Shift cannot drive the same
                // axis in one event.
                float sx = event.scrollX();
                float sy = event.scrollY();
                if (sx == 0 && (event.modifiers() & Keys.MOD_SHIFT) != 0) {
                    sx = sy;
                    sy = 0;
                }
                // Consumed only when an offset moved (decision 44, 2026-09-14): a detent that
                // finds the table at either end of an axis, or a table that fits, is left for
                // the scroller that holds it. Until then the table was a wall inside a scroll
                // pane once it overflowed.
                if (scrollBy(-sx * Strokes.WHEEL_STEP, -sy * Strokes.WHEEL_STEP,
                        Change.Origin.USER)) {
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
                } else if (headerPressColumn >= 0 && columnReordering && shownCount > 1
                        && (moveSlot >= 0 || Math.abs(x - headerPressX) >= 4)) {
                    // The column follows the pointer along the header and nowhere else: where the
                    // pointer is up or down the window does not matter, the press has the mouse.
                    boolean rtl = isRightToLeft();
                    float rowX = rowsLeft();
                    float w = gutters.viewportWidth(width());
                    if (moveSlot < 0) {
                        // Held where it belongs, as it was hit: a slide still passing is not
                        // where the press found it.
                        moveGrab = headerPressX - settledLeft(headerPressColumn, rowX, w, rtl);
                        movingColumn(headerPressColumn, headerPressColumn, x);
                    }
                    movePointerX = x;
                    movingColumn(moveSlot, placeUnderPointer(rowX, w, rtl), x);
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
                            // The pointer sorts and the keyboard stays where it was, in the
                            // rows; the header's cursor remembers the column, so a Tab into the
                            // header continues from where the pointer was.
                            setHeaderColumn(s);
                            damageHeader();
                        }
                        // The sort waits for the click: a press that travels moves the column.
                        headerPressColumn = s;
                        headerPressX = x;
                    }
                    event.consume();
                    return;
                }
                leaveHeader(Change.Origin.USER);
                int row = rowAt(y);
                if (row < 0) {
                    event.consume();
                    return;
                }
                int s = columnAt(x);
                if (s >= 0) {
                    focusColumn = s;
                    focusColumnOf = shownIndex[s];
                }
                int mods = event.modifiers();
                boolean command = (mods & Accelerator.commandModifier()) != 0;
                boolean shift = (mods & Keys.MOD_SHIFT) != 0;
                // The platform's double click, counted by the backend with the user's own interval
                // (ADR 046 §5); even counts pair up, so a triple click activates once.
                boolean second = row == lastPressRow && event.clickCount() % 2 == 0;
                lastPressRow = row;
                if (selectionMode == SelectionMode.MULTI && command) {
                    toggle(row);
                } else if (selectionMode == SelectionMode.MULTI && shift) {
                    selectRange(row);
                } else {
                    selectOnly(modelOf(row), row, false, Change.Origin.USER);
                }
                if (second && !command && !shift) {
                    activate(Change.Origin.USER);
                }
                event.consume();
            }
            case RELEASE -> {
                if (dragColumn >= 0) {
                    dragColumn = -1;
                    invalidate();
                    event.consume();
                    columnsChangedByUser(); // a column was resized
                } else if (moveSlot >= 0 && !Float.isNaN(movePointerX)) {
                    headerPressColumn = -1; // a move is not a click: nothing sorts
                    dropMove(moveSlot, moveTo);
                    event.consume();
                }
            }
            case CLICK -> {
                int pressedOn = headerPressColumn;
                headerPressColumn = -1;
                if (pressedOn >= 0 && showHeader && y < rowsTop() && columnAt(x) == pressedOn) {
                    headerClicked(pressedOn);
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected boolean onCanEdit(limn.scene.EditCommand command) {
        return command == limn.scene.EditCommand.SELECT_ALL && selectionMode == SelectionMode.MULTI;
    }

    @Override
    protected boolean onEdit(limn.scene.EditCommand command) {
        return limn.components.internal.EditKeys.send(command, this::onKeyEvent);
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        int mods = event.modifiers();
        boolean rtl = isRightToLeft();
        if (event.key() == Keys.ESCAPE && moveSlot >= 0 && !Float.isNaN(movePointerX)) {
            headerPressColumn = -1;
            consumeAnd(event, this::endMove); // the column goes back where it was
            return;
        }
        if ((event.key() == Keys.LEFT || event.key() == Keys.RIGHT) && columnReordering
                && (mods & (Keys.MOD_ALT | Keys.MOD_SHIFT | Keys.MOD_CONTROL | Keys.MOD_SUPER))
                        == (Keys.MOD_ALT | Keys.MOD_SHIFT)) {
            // Alt+Shift+Left and Right move the column the keyboard is on, as they move a
            // paragraph in a word processor's outline.
            int toward = (event.key() == Keys.LEFT) == rtl ? 1 : -1;
            consumeAnd(event, () -> moveCursorColumn(toward));
            return;
        }
        if (event.key() == Keys.TAB && isFocused()) {
            // The header is a focus stop of its own, before the rows (decision 36 of
            // 2026-09-14): Tab walks header, rows, then out; Shift+Tab the reverse. A Tab the
            // table does not consume traverses on, as it always did.
            boolean backward = (mods & Keys.MOD_SHIFT) != 0;
            if (!backward && headerFocused) {
                consumeAnd(event, () -> leaveHeader(Change.Origin.USER));
            } else if (backward && !headerFocused && headerStopAvailable()) {
                consumeAnd(event, () -> enterHeader(headerColumn, Change.Origin.USER));
            }
            return;
        }
        if (headerFocused && isFocused()) {
            onHeaderKeyEvent(event, mods, rtl);
            return;
        }
        switch (event.shortcutKey()) {
            case Keys.DOWN -> consumeAnd(event, () -> moveFocusRow(
                    focusRow < 0 ? engine.anchorIndex() : focusRow + 1, mods));
            case Keys.UP -> consumeAnd(event, () -> moveFocusRow(
                    focusRow < 0 ? engine.anchorIndex() : focusRow - 1, mods));
            case Keys.PAGE_DOWN -> consumeAnd(event, () -> pageFocusRow(rowsPerPage(tokens()), mods));
            case Keys.PAGE_UP -> consumeAnd(event, () -> pageFocusRow(-rowsPerPage(tokens()), mods));
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
                    consumeAnd(event, () -> selectAll(Change.Origin.USER));
                }
            }
            case Keys.ENTER -> {
                if (focusRow >= 0) {
                    consumeAnd(event, () -> activate(Change.Origin.USER));
                }
            }
            default -> {
            }
        }
    }

    /** The format a header carries while it is dragged: the column itself, within this window. */
    @SuppressWarnings("unchecked")
    private static final DataFormat<Column<?>> MOVED_COLUMN = DataFormat.inProcess(
            "application/x-limn-table-column", (Class<Column<?>>) (Class<?>) Column.class);

    /** The first and one past the last shown place of the group shown column {@code s} is in. */
    private int groupStart(int s) {
        int kept = frozen();
        return s < kept ? 0 : kept;
    }

    private int groupEnd(int s) {
        int kept = frozen();
        return s < kept ? kept : shownCount;
    }

    /** Moves the column the keyboard is on one shown place, within its group. */
    private void moveCursorColumn(int delta) {
        if (shownCount == 0) {
            return;
        }
        boolean header = headerHoldsCursor();
        int s = header ? headerColumn : focusColumn;
        int to = s + delta;
        if (s < 0 || s >= shownCount || to < groupStart(s) || to >= groupEnd(s)) {
            return;
        }
        userMovesColumn(s, to);
    }

    /** The user puts shown column {@code s} at shown place {@code to}: moved, revealed, said, told. */
    private void userMovesColumn(int s, int to) {
        Column<T> column = columns.get(shownIndex[s]);
        moveColumn(shownIndex[s], shownIndex[to]);
        resolveColumns(lastViewportWidth);
        int now = shownIndexOf(columns.indexOf(column));
        if (now >= 0) {
            ensureColumnVisible(now);
        }
        limn.scene.Scene scene = scene();
        if (scene != null) {
            scene.announce(TableStrings.COLUMN_MOVED.format(column.title().get(), now + 1, shownCount),
                    Accessible.Politeness.ASSERTIVE);
        }
        columnsChangedByUser();
    }

    @Override
    protected @Nullable Drag onDragStart(DragStartEvent event) {
        // The pointer moves a column by itself (the header's own drag, above): the scene's drag
        // is how the keyboard does it, from the header.
        if (!event.isKeyboard() || !columnReordering || !showHeader || shownCount < 2
                || !headerHoldsCursor()) {
            return null;
        }
        Column<T> column = columns.get(shownIndex[headerColumn]);
        return Drag.of(Transfer.of(MOVED_COLUMN, column))
                .allow(DropAction.MOVE)
                .withinWindow()
                .noPreview()
                .describedAs(column.title().get());
    }

    @Override
    protected void onDragOver(DragEvent event) {
        if (event.source() != this || !event.isKeyboard() || !event.offer().has(MOVED_COLUMN)) {
            return;
        }
        Column<?> column = moved(event.offer());
        int s = moveSlot >= 0 ? moveSlot : column == null ? -1 : shownIndexOf(columns.indexOf(column));
        if (s < 0) {
            return;
        }
        // Each arrow moves the place one column, and the others make room as it goes.
        movingColumn(s, (moveTo < 0 ? s : moveTo) + event.stepX(), Float.NaN);
        ensureColumnVisible(moveTo);
        event.describePlace(TableStrings.COLUMN_MOVED.format(columns.get(shownIndex[s]).title().get(),
                moveTo + 1, shownCount));
        event.accept(DropAction.MOVE);
    }

    @Override
    protected void onDragExit(DragEvent event) {
        endMove();
    }

    @Override
    protected void onDrop(DropEvent event) {
        int from = moveSlot;
        int to = moveTo;
        if (from < 0 || to == from) {
            endMove();
            event.reject(); // dropped where it was: nothing moved
            return;
        }
        dropMove(from, to);
    }

    private static @Nullable Column<?> moved(Offer offer) {
        // An in-process value: read at once.
        return offer.read(MOVED_COLUMN).toCompletableFuture().getNow(null);
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
        focusColumnOf = shownIndex[next];
        // Before the damage: a horizontal scroll invalidates the table itself, and a single row
        // band would then be short of it.
        ensureColumnVisible(next);
        damageRow(focusRow);
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
    }

    // ------------------------------------------------------------------ the header's stop

    /** @return whether the header is a focus stop: shown, with a shown column that can be sorted */
    private boolean headerStopAvailable() {
        if (!showHeader) {
            return false;
        }
        // Read off the columns and not the last layout's shown set, so a Tab that arrives
        // before the first layout (a scene focused as it is built) finds the stop too.
        for (int c = 0; c < columns.size(); c++) {
            Column<T> column = columns.get(c);
            if (column.isVisible() && column.isSortable()) {
                return true;
            }
        }
        return false;
    }

    /** @return whether the header's column cursor is the cursor a reader stands on right now */
    private boolean headerHoldsCursor() {
        return headerFocused && isFocused();
    }

    /** Damages the header band, which is where the header's cursor is drawn. */
    private void damageHeader() {
        if (showHeader) {
            invalidate(rowsLeft(), 0, gutters.viewportWidth(width()), headerHeight(tokens()));
        }
    }

    /**
     * Puts the keyboard on the header, its column cursor on shown column {@code s}: the cursor a
     * reader stands on moves from the focus cell to a header cell, announced as {@code ACTIVE}.
     */
    private void enterHeader(int s, Change.Origin origin) {
        if (!headerStopAvailable()) {
            return;
        }
        headerFocused = true;
        setHeaderColumn(s);
        ensureColumnVisible(headerColumn);
        damageHeader();
        damageRow(focusRow);
        notifyChange(Change.of(Change.Aspect.ACTIVE, origin));
    }

    /** Hands the keyboard back to the rows; the cursor is the focus cell again. */
    private void leaveHeader(Change.Origin origin) {
        if (!headerFocused) {
            return;
        }
        headerFocused = false;
        damageHeader();
        damageRow(focusRow);
        notifyChange(Change.of(Change.Aspect.ACTIVE, origin));
    }

    /**
     * The keys while the header holds the keyboard: Left and Right move the column cursor, Space
     * sorts the column under it, cycling ascending, descending and the model's order as a click
     * does; Down hands the keyboard to the rows. The other row keys are consumed and do nothing, so
     * the rows do not move under a cursor that is not in them.
     */
    private void onHeaderKeyEvent(KeyEvent event, int mods, boolean rtl) {
        switch (event.key()) {
            case Keys.LEFT -> consumeAnd(event, () -> moveHeaderColumn(rtl ? 1 : -1));
            case Keys.RIGHT -> consumeAnd(event, () -> moveHeaderColumn(rtl ? -1 : 1));
            case Keys.HOME -> consumeAnd(event, () -> moveHeaderColumn(-shownCount));
            case Keys.END -> consumeAnd(event, () -> moveHeaderColumn(shownCount));
            case Keys.SPACE -> consumeAnd(event, () -> headerClicked(headerColumn));
            case Keys.DOWN -> consumeAnd(event, () -> leaveHeader(Change.Origin.USER));
            case Keys.UP, Keys.PAGE_UP, Keys.PAGE_DOWN, Keys.ENTER -> event.consume();
            default -> {
            }
        }
    }

    private void moveHeaderColumn(int delta) {
        if (shownCount == 0) {
            return;
        }
        int next = Math.min(Math.max(0, headerColumn + delta), shownCount - 1);
        if (next == headerColumn) {
            return;
        }
        setHeaderColumn(next);
        ensureColumnVisible(next);
        damageHeader();
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
    }

    @Override
    protected void onFocusGained() {
        // The focus cell is not placed until a key asks for one: the first Down then lands on
        // the top row the way it does in ListView, rather than on the row below it. So what
        // appears is one ring in one row, and that is all this damages.
        // Tab enters at the header, the first stop; Shift+Tab, a click and code at the rows.
        headerFocused = focusArrivedByTraversal() && !focusArrivedBackward()
                && headerStopAvailable();
        setHeaderColumn(headerColumn);
        damageHeader();
        damageRow(focusRow);
        if (focusRow >= 0 && slotFor(focusRow) == null) {
            markNeedsContainedLayout(); // the cursor row is realized while the keyboard is here
        }
    }

    @Override
    protected void onFocusLost() {
        damageHeader();
        damageRow(focusRow);
        if (focusRow >= 0 && !isPlaced(focusRow)) {
            markNeedsContainedLayout(); // and released by the first pass after it leaves
        }
    }

    // The rows' viewport is what the rows and their widget cells are clipped to, and the bars
    // are clipped to the box (2026-09-14): until this the default answered the
    // whole box for every child, so a switch scrolled under the header or the footer, or lying
    // in a reserved gutter, was isShowing() and published SHOWING in a rectangle this table
    // never paints it in, a reader could toggle it, and a point on the header resolved to it.
    // The four run for every node of every accessible walk and allocate nothing.

    @Override
    protected float clipX(Widget<?> child) {
        if (child == vBar || child == hBar) {
            return 0;
        }
        return rowsLeft() + (isRightToLeft() ? 0 : keptBefore(child));
    }

    /** The width kept columns take off the view of this cell widget: none for a kept column's own. */
    private float keptBefore(Widget<?> child) {
        int kept = frozen();
        if (kept == 0) {
            return 0;
        }
        int at = mountedPositionOf(child);
        if (at < 0) {
            return 0;
        }
        Widget<?>[] widgets = engine.mountedAt(at).widgets;
        for (int c = 0; c < widgets.length; c++) {
            if (widgets[c] == child) {
                int shown = shownIndexOf(c);
                return shown >= 0 && shown < kept ? 0 : colX[kept];
            }
        }
        return 0;
    }

    @Override
    protected float clipY(Widget<?> child) {
        return child == vBar || child == hBar ? 0 : rowsTop();
    }

    @Override
    protected float clipWidth(Widget<?> child) {
        return child == vBar || child == hBar ? width()
                : gutters.viewportWidth(width()) - keptBefore(child);
    }

    @Override
    protected float clipHeight(Widget<?> child) {
        return child == vBar || child == hBar ? height() : rowsViewportHeight();
    }

    // ---------------------------------------------------------------------- accessibility

    /** The row count the rows below are numbered against, read once at the top of a publish. */
    private int describedRowCount;

    /**
     * One table node: its shape, its selection and its scroll, then a header group with a header
     * cell per shown column, then one row per realized data row with a cell per shown column.
     * Nothing is formatted here: every name is a string a slot already holds, handed over with the
     * row's witness, or the column's own {@code I18nString}.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        describedRowCount = rows.size();
        notePublishedRows();
        SizeTokens t = tokens();
        boolean rtl = isRightToLeft();
        float w = gutters.viewportWidth(width());
        float rowX = rowsLeft();
        float headerH = headerHeight(t);
        float viewH = rowsViewportHeight();
        float contentH = estimatedContentHeight(t);

        // The GRID shape's container half, written once (ADR 045 §3; decision 99), then the
        // ROWS shape's, written once as well: the selection, and
        // whenever there is a cursor, in every mode, the table's own PRESS, which opens the
        // cursor row as Enter and a double click do (decision 32 of 2026-09-14).
        GridAccessibility.describeGrid(a, describedRowCount, shownCount);
        RowsAccessibility.describeContainer(a, rowsSelection(), false, hasCursorRow());
        a.scrollFrom(offsetX, Math.max(0, contentWidth - w), w, contentWidth,
                estimatedOffset(t), Math.max(0, contentH - viewH), viewH, contentH);
        // Busy while a load runs, and described by the empty or the loading message while one
        // shows: "table, 0 rows, no orders match". On the table's own node, before its
        // synthetic children open.
        placeholder.describe(a);

        if (showHeader) {
            a.child(HEADER_KEY);
            a.bounds(rowX, 0, w, headerH);
            a.role(Accessible.Role.GROUP);
            for (int s = 0; s < shownCount; s++) {
                int c = shownIndex[s];
                float left = columnLeft(s, rowX, w, rtl);
                Column<T> column = columns.get(c);
                a.child(HEADER_CELL_KEY | c);
                // In this widget's coordinates, as every synthetic box is, nested or not.
                a.bounds(left, 0, colW[s], headerH);
                // A header cell by the rules of the GRID shape (GridAccessibility.describeHeaderCell,
                // ADR 045 §3): the direction this column's rows run, on the cell that sorts them
                // (decision 36, carrier settled 2026-09-15), as the facet for the two platforms
                // whose carrier is an enumeration (AT-SPI's `sort` attribute, AX's
                // AXSortDirection), and PRESS where the column sorts, as a click does. The
                // localized phrase in the description below is for the one platform whose carrier
                // is a phrase (UIA's ItemStatus and HelpText, File Explorer's convention), and it
                // stays here: a bridge reads the snapshot on a platform's own thread where no
                // locale scope is open, so a phrase has to be resolved in the widget that holds
                // the strings.
                boolean sorted = column.isSortable() && column == sortColumn
                        && sortOrder != SortOrder.NONE;
                GridAccessibility.describeHeaderCell(a, s, column.isSortable(), !sorted
                        ? CellFacet.Sort.NONE : sortOrder == SortOrder.ASCENDING
                        ? CellFacet.Sort.ASCENDING : CellFacet.Sort.DESCENDING);
                a.name(column.title(), Accessible.NameFrom.CONTENT);
                if (sorted) {
                    a.description(sortOrder == SortOrder.ASCENDING
                            ? TableStrings.SORTED_ASCENDING : TableStrings.SORTED_DESCENDING);
                }
                if (headerHoldsCursor() && s == headerColumn) {
                    a.state(Accessible.State.ACTIVE); // the header's column cursor
                }
                if (left + colW[s] <= rowX || left >= rowX + w) {
                    a.offScreen();
                }
                a.endChild();
            }
            a.endChild();
        }

        for (int i = 0; i < engine.mountedCount(); i++) {
            int row = engine.rowAt(i);
            Slot slot = engine.mountedAt(i);
            int model = modelOf(row);
            float top = rowTop(row);
            boolean shown = !Float.isNaN(top);
            if (!shown) {
                // The kept row a scroll spared: published where the layout put it, outside the
                // rows' viewport, so the ROW and a widget cell inside it agree on a box; before a
                // layout has placed it, where the estimate would.
                top = Float.isNaN(slot.top)
                        ? (row < engine.placedFrom() ? headerH - slot.height : headerH + viewH)
                        : slot.top;
            }
            boolean rowOffScreen = !shown || top + slot.height <= headerH
                    || top >= headerH + viewH;
            a.child(slot.id);
            a.bounds(rowX, top, w, slot.height);
            a.role(Accessible.Role.ROW);
            // A row is its record, and says so (decision 23 of 2026-09-14): the text of its
            // shown cells in column order, which is the treatment a TREE_ITEM whose cell is a
            // composite already gets.
            String rowName = derivedName(slot);
            if (rowName != null) {
                a.name(rowName, System.identityHashCode(rowName), Accessible.NameFrom.CONTENT);
            }
            // The ROWS shape, written once (ADR 045 §3), read against this widget: the
            // membership over the model's count; no cursor mark on the row, because a table's
            // cursor is a cell (below); the verbs by state (decisions 10, 11 and 20 of
            // 2026-09-14), FOCUS because the cursor and the selection are separate things in a
            // table, SCROLL_INTO_VIEW so that a reader can bring the widest row in the toolkit
            // into view — until 2026-09-17 the row stopped at FOCUS and a Windows client found
            // no ScrollItem on it (decision 81) — and PRESS opening this row and not the
            // cursor's, because a reader's SELECT stopped moving the cursor the same day
            // (decisions 79 and 80); the table keeps its own PRESS for Enter and for a client
            // that addresses the container. The verbs are the table's own, because a row is a
            // synthetic child it describes and performs for (ADR 041 §3).
            RowsAccessibility.describeRow(a, RowsAccessibility.Offer.OWNED, rowsSelection(),
                    selected.get(model), row + 1, describedRowCount, false, false, false,
                    true, true, true);
            if (rowOffScreen) {
                a.offScreen();
            }
            for (int s = 0; s < shownCount; s++) {
                int c = shownIndex[s];
                if (slot.widgets[c] != null || slot.texts[c] == null) {
                    continue; // a real child, described in onAccessibilityChild
                }
                float left = columnLeft(s, rowX, w, rtl);
                a.child(CELL_KEY | (slot.id << COLUMN_BITS) | c);
                a.bounds(left, top, colW[s], slot.height);
                // A data cell by the rules of the GRID shape (GridAccessibility.describeCell): its
                // place, FOCUS because a table's cursor is a cell, and the cursor mark only while
                // the table holds the keyboard (ADR 039 §1.10, amended 2026-09-14) in its rows:
                // the cursor is the focused node's, one at a time, and while the header holds it
                // the cursor is a header cell.
                GridAccessibility.describeCell(a, row, s, true,
                        row == focusRow && s == focusColumn && isFocused() && !headerFocused);
                a.name(slot.texts[c], textEpoch, Accessible.NameFrom.CONTENT);
                // Off screen with its row as well as with its column: the bit is per node, and
                // nothing is inherited from a synthetic parent, so a kept row's cells were
                // published SHOWING over the header band.
                if (rowOffScreen || left + colW[s] <= rowX || left >= rowX + w) {
                    a.offScreen();
                }
                a.endChild();
            }
            a.endChild();
        }

        if (footerShown()) {
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
                a.child(FOOTER_CELL_KEY | c);
                a.bounds(left, top, colW[s], footerH);
                GridAccessibility.describeSummaryCell(a, s);
                a.name(footerTexts[c], textEpoch, Accessible.NameFrom.CONTENT);
                if (left + colW[s] <= rowX || left >= rowX + w) {
                    a.offScreen();
                }
                a.endChild();
            }
            a.endChild();
        }
    }

    /**
     * The name a {@code ROW} is published under: the text of its shown cells, in column order,
     * separated by a space &mdash; a record read as one phrase, which is what "a row is its
     * record" means and what {@code Tree} already does for a composite row.
     *
     * <p><b>Why a row needs one at all.</b> A {@code ROW} published with an empty name was
     * measured on all three platforms on 2026-09-16: NVDA 2024.4.2 spoke {@code 'item de dados',
     * 'selecionado', '4 de 10'} &mdash; role, position, no name &mdash; and Orca discarded the row
     * outright, an unnamed row that is not focusable, selectable or expandable being "believed to
     * be layout only". The cells carried the text and the row carried none, so the one node a
     * reader announces when the cursor changes row said nothing.
     *
     * <p>A widget column contributes the text of its cell's labels rather than nothing, by the
     * same walk {@code Tree} uses: a switch or a button cell has no text of its own and a row
     * ending in one would otherwise be named by its other columns alone.
     *
     * <p>Assembled into one reused builder and compared with the string kept on the slot, so a
     * quiet frame allocates nothing; a new string is built only when the row's text moved, and
     * the string's identity is then the witness the builder wants.
     *
     * @return the name, or {@code null} when no shown cell of the row holds text
     */
    private String derivedName(Slot slot) {
        nameBuilder.setLength(0);
        for (int s = 0; s < shownCount; s++) {
            int c = shownIndex[s];
            if (slot.widgets[c] != null) {
                appendLabelText(slot.widgets[c]);
            } else {
                appendText(slot.texts[c]);
            }
        }
        String kept = slot.name;
        if (nameBuilder.length() == 0) {
            slot.name = null;
            return null;
        }
        if (kept != null && kept.contentEquals(nameBuilder)) {
            return kept;
        }
        String fresh = nameBuilder.toString();
        slot.name = fresh;
        return fresh;
    }

    private void appendText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (nameBuilder.length() > 0) {
            nameBuilder.append(' ');
        }
        nameBuilder.append(text);
    }

    private void appendLabelText(Widget<?> widget) {
        if (!widget.isVisible()) {
            return;
        }
        if (widget instanceof limn.components.Label label) {
            appendText(label.text());
            return;
        }
        if (widget instanceof limn.components.Badge badge) {
            // A status in a cell says its word, or its count as shown, in the row's name: the
            // badge is read through the row, as anything inside it is.
            appendText(badge.text());
            return;
        }
        List<Widget<?>> children = widget.children();
        for (int i = 0; i < children.size(); i++) { // indexed: an iterator is an allocation
            appendLabelText(children.get(i));
        }
    }

    /**
     * A widget cell hangs under the synthetic {@code ROW} of the record it shows and is keyed by
     * its column within that row: a reader walking the row finds the control among its cells, in
     * column order, and the cell's identity follows the record the row is keyed by, so a control
     * recycled to another row carries nothing of the old one. The key is the column alone, because
     * the row's node scopes it; no packing of row and column into one number.
     */
    @Override
    protected void onAccessibilityChildIdentity(Widget<?> child, Accessibility a) {
        if (child == vBar || child == hBar) {
            return;
        }
        int at = mountedPositionOf(child);
        if (at < 0) {
            return;
        }
        Slot slot = engine.mountedAt(at);
        int c = 0;
        while (slot.widgets[c] != child) {
            c++;
        }
        a.under(slot.id);
        a.key(c);
    }

    @Override
    protected void onAccessibilityChild(Widget<?> child, Accessibility a) {
        if (child == vBar || child == hBar) {
            return;
        }
        int at = mountedPositionOf(child);
        if (at < 0) {
            return;
        }
        Slot slot = engine.mountedAt(at);
        int c = 0;
        while (slot.widgets[c] != child) {
            c++;
        }
        int s = 0;
        while (s < shownCount && shownIndex[s] != c) {
            s++;
        }
        if (s == shownCount) {
            return; // the column was hidden since the last layout; the next one releases it
        }
        a.cell(slot.row, s);
        if (slot.row == focusRow && s == focusColumn && isFocused() && !headerFocused) {
            // The focus cell in a widget column is the cursor exactly as a value cell is: until
            // 2026-09-14 the ring was drawn on it and the reader was told nothing, so the active
            // descendant fell to nothing on every Right into a switch column.
            a.state(Accessible.State.ACTIVE);
        }
    }

    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (action == Accessible.Action.PRESS && hasCursorRow()) {
            activate(Change.Origin.USER);
            return true;
        }
        return false;
    }

    /**
     * A reader's verb on a row or a cell, decoded from the key the node was published with: a row's
     * key is its record's row identity, a cell's carries that and its column (until 2026-09-14 a
     * cell was keyed by its column alone and a select on it selected the row of that number). The
     * verbs are the published ones and no other — a cell accepts {@code FOCUS} alone — and each
     * goes through the seam the matching gesture takes at {@code USER}: {@code SELECT} is the
     * click, {@code ADD_TO_SELECTION} and {@code DESELECT} the command-click's toggle without its
     * cursor move (only {@code SELECT} and {@code FOCUS} move the cursor), {@code FOCUS} a cursor
     * move that selects nothing. A row is named by the identity of the record the snapshot
     * published it for: a verb sent before a {@link #refresh()} that inserted a row above acts on
     * that same record where it stands now, and one whose record left the list is refused. Until
     * 2026-09-15 it acted on whatever record stood at the published model index.
     */
    @Override
    protected boolean onSyntheticAction(long key, Accessible.Action action,
                                        Accessible.Argument arg) {
        if ((key & CELL_KEY) != 0) {
            int model = modelOfRowId((key & ~CELL_KEY) >>> COLUMN_BITS);
            int s = shownIndexOf((int) (key & COLUMN_MASK));
            if (model < 0 || s < 0) {
                return false;
            }
            // By the rules of the GRID shape (GridAccessibility.performOnCell, ADR 045 §3).
            return GridAccessibility.performOnCell(gridHost, viewOf(model), s, action);
        }
        if ((key & HEADER_CELL_KEY) != 0) {
            int s = shownIndexOf((int) (key & COLUMN_MASK));
            if (s < 0) {
                return false;
            }
            // By the rules of the GRID shape (GridAccessibility.performOnHeader).
            return GridAccessibility.performOnHeader(gridHost, s, action);
        }
        if ((key & FOOTER_CELL_KEY) != 0 || key < 0) {
            return false;
        }
        int model = modelOfRowId(key);
        if (model < 0) {
            return false; // the record this row was published for is gone from the list
        }
        return RowsAccessibility.performOnRow(rowsHost, model, action);
    }

    /**
     * The table's mechanisms as the grid shape drives them, over shown column and view row
     * indices. A press sorts as a click does, and as the click does remembers the column for the
     * header's cursor, so a Shift+Tab into the header after a reader's press on the Age title
     * starts on Age; while the header holds the keyboard the cursor moves with the press, which
     * the sort's own publish announces.
     */
    private final GridAccessibility.Host gridHost = new GridAccessibility.Host() {
        @Override
        public boolean isSortable(int column) {
            return columns.get(shownIndex[column]).isSortable();
        }

        @Override
        public void sort(int column) {
            setHeaderColumn(column);
            headerClicked(column);
        }

        @Override
        public void focusCell(int row, int column) {
            Table.this.focusCell(row, column, Change.Origin.USER);
        }
    };

    /** The table's selection mode as the rows shape names it. */
    private RowsAccessibility.Selection rowsSelection() {
        return switch (selectionMode) {
            case NONE -> RowsAccessibility.Selection.NONE;
            case SINGLE -> RowsAccessibility.Selection.SINGLE;
            case MULTI -> RowsAccessibility.Selection.MULTI;
        };
    }

    /**
     * The table's mechanisms as the rows shape drives them, over a row's model index: a client's
     * {@code SELECT} is {@link #selectOnly} without the cursor move, {@code ADD_TO_SELECTION} and
     * {@code DESELECT} are {@link #toggle} without it, {@code FOCUS} moves the cursor onto the row
     * in the cursor's column, {@code SCROLL_INTO_VIEW} reveals the row, and {@code PRESS} opens the
     * row itself.
     */
    private final class RowsHost implements RowsAccessibility.Host<Integer> {
        @Override
        public RowsAccessibility.Selection selection() {
            return rowsSelection();
        }

        @Override
        public boolean cursorIsTheSelection() {
            return false;
        }

        @Override
        public boolean rowsActivate() {
            return true;
        }

        @Override
        public boolean isSelected(Integer model) {
            return selected.get(model);
        }

        @Override
        public boolean select(Integer model, boolean moveCursor) {
            selectOnly(model, viewOf(model), true, moveCursor, Change.Origin.USER);
            return true;
        }

        @Override
        public void toggleSelection(Integer model, boolean moveCursor) {
            toggle(viewOf(model), moveCursor);
        }

        @Override
        public void moveCursor(Integer model) {
            focusCell(viewOf(model), focusColumn, Change.Origin.USER);
        }

        @Override
        public void reveal(Integer model) {
            ensureVisible(viewOf(model));
        }

        @Override
        public void activate(Integer model) {
            activateRow(model, Change.Origin.USER);
        }
    }

    private final RowsHost rowsHost = new RowsHost();

    /** The shown index of column {@code c}, or {@code -1} while it is hidden. */
    private int shownIndexOf(int c) {
        for (int s = 0; s < shownCount; s++) {
            if (shownIndex[s] == c) {
                return s;
            }
        }
        return -1;
    }

    /**
     * Moves the focus cell without touching the selection: what a reader's {@code FOCUS} on a row
     * or a cell asks for. The range anchor moves with it, as it does under a toggle, so the next
     * Shift range extends from where the cursor is; the row is revealed and the move announced as
     * {@code ACTIVE}.
     */
    private void focusCell(int viewIndex, int shownColumn, Change.Origin origin) {
        int count = rows.size();
        if (count == 0 || shownCount == 0) {
            return;
        }
        int wasFocusRow = focusRow;
        int wasColumn = focusColumn;
        focusRow = Math.min(Math.max(0, viewIndex), count - 1);
        focusColumn = Math.min(Math.max(0, shownColumn), shownCount - 1);
        focusColumnOf = shownIndex[focusColumn];
        rangeAnchor = focusRow;
        syncRecords();
        ensureVisible(focusRow);
        ensureColumnVisible(focusColumn);
        damageRow(wasFocusRow);
        damageRow(focusRow);
        if (focusRow != wasFocusRow || focusColumn != wasColumn) {
            notifyChange(Change.of(Change.Aspect.ACTIVE, origin));
        }
    }
}

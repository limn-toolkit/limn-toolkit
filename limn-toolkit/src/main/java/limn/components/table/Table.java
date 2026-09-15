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
import limn.lang.Checks;
import limn.scene.Change;
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
import java.util.function.Function;
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
 * A row is its record: across a {@link #refresh()} the selection follows the records it named,
 * wherever the list holds them now, by {@code equals} or by a {@linkplain #rowKey key}.
 * Separately, the arrow keys move a <b>focus cell</b>, which is what a screen reader's cursor
 * stands on. Enter and a double click {@linkplain #onActivate activate} the cursor row — the
 * focus cell's row, which is the lead in {@code SINGLE} and may differ from it in {@code MULTI}.
 *
 * <p><b>Cells are not edited in place, and will not be.</b> In-place editing is a spreadsheet's
 * interaction and reads as one everywhere else: a field that appears where a value was, a save
 * on a keystroke the user did not mean as one, an error with nowhere to stand. A table is for
 * reading, comparing, sorting and choosing; editing a record wants the record whole. Open a
 * {@code Dialog} or a panel with the record as a form from {@link #onActivate}, keep a form beside
 * the table following its selection through {@link #observeChanges} for master-and-detail, put a
 * switch or a button in a {@link Column#widget} cell for the one-gesture cases, or act on the
 * whole {@linkplain SelectionMode#MULTI selection} at once; then change the list and
 * {@link #refresh()}. ADR 041 §6 is the reasoning.
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

    /**
     * Rows of intrinsic height when the height axis is unbounded, until {@link #setVisibleRows}
     * says otherwise; a count, not a length: it multiplies the step's seed row height.
     */
    private static final int VISIBLE_ROWS_HINT = 8;

    /**
     * How many seed rows tall the rows' viewport prefers to be under an unbounded height
     * (decision 44 of 2026-09-14). Multiplied by the token's seed and never by the realized
     * average: the average moves as rows of other heights scroll in, and a preference that
     * moved with it re-laid out the parent on every such scroll and made a table inside a
     * scroll pane jitter.
     */
    private int visibleRows = VISIBLE_ROWS_HINT;
    /** How far either side of a header divider a press starts a resize, in points. */
    private static final float RESIZE_BAND = 4;
    /** Two presses on one row closer than this are a double click. */
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    /** The synthetic key of the header row's group node. */
    private static final long HEADER_KEY = -1;
    /** The synthetic key of the footer row's group node. */
    private static final long FOOTER_KEY = -2;
    /**
     * The synthetic keys a reader's verb arrives with, told apart by a bit each: a data row is
     * its record's <b>row identity</b> ({@link #rowIdOf}); a cell is {@code CELL_KEY | identity
     * << COLUMN_BITS | column}, so a verb on a cell names its row as well as its column; a
     * header cell is {@code HEADER_CELL_KEY | column}, a footer cell {@code FOOTER_CELL_KEY |
     * column}. Until 2026-09-14 a cell and a header cell were keyed by their column alone, which
     * a verb could not tell from a row's index: a select on cell (0, 1) selected row 1
     * (TABLE-NEW-13). Until 2026-09-15 a row was keyed by its model index, so an insert above it
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
    // done 2026-09-15): model row m is published as rowIdBase + m unless an override names it,
    // and a refresh that moved a published record gives its old identity to where the record is
    // now, as an override, and issues the other rows a fresh range past every identity issued.
    private long rowIdBase;
    private long rowIdHighWater;
    private int[] overrideModels = new int[0];
    private long[] overrideIds = new long[0];
    private int overrideCount;
    // The rows the last describe published, in the order it walked them: what a reader may still
    // hold a node of, and what a refresh follows by record so those nodes stay on their records.
    private int publishedCount;
    private int[] publishedModels = new int[16];
    private long[] publishedIds = new long[16];
    private Object[] publishedKeys = new Object[16];
    private int[] publishedOrdinals = new int[16];
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
    // column was hidden and the cursor sat on no shown column at all (TABLE-NEW-5,
    // 2026-09-14). resolveColumns brings the two back in line.
    private int focusColumnOf = -1;
    // The header's own focus stop (decision 36 of 2026-09-14): while the table holds the keyboard
    // it is either in the rows or in the header, whose column cursor is a shown column. The
    // header is a stop only while it is shown and a shown column can be sorted.
    private boolean headerFocused;
    private int headerColumn;
    // The column the header's cursor is on, by identity, as focusColumnOf is for the focus
    // cell: a shown index alone slid onto the next column when one before it was hidden, and
    // onto some other column, unannounced, when its own was (review of table-B, 2026-09-14).
    private int headerColumnOf = -1;

    // Columns as shown: which, and where, resolved per layout; and the set the previous
    // layout resolved, so a column hidden or shown between two layouts is noticed by the next
    // one (B6, 2026-09-14): a hidden widget column's widgets are released and a shown one's
    // built by re-mounting the rows.
    private int shownCount;
    private int[] shownIndex = new int[0];
    private int[] shownBefore = new int[0];
    private int shownBeforeCount = -1;
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
        /** The top the last layout placed it at, in this widget's coordinates; NaN before one. */
        float top = Float.NaN;
        final String[] texts;
        final ShapedText[] shaped;
        final ShapedText[] fitted;
        final float[] fittedWidth;
        final Widget[] widgets;
        int widgetCount;
        /** The row's accessible identity: the synthetic key its {@code ROW} is published under. */
        long id;
        /** The key its record is followed by, taken when it was mounted. */
        Object key;
        /** Its occurrence among the equal keys before it; read the first time it is described. */
        int ordinal;
        boolean ordinalKnown;

        Slot(int columns) {
            texts = new String[columns];
            shaped = new ShapedText[columns];
            fitted = new ShapedText[columns];
            fittedWidth = new float[columns];
            widgets = new Widget[columns];
        }
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

    /** A model row the four tracked positions name, or {@code -1}; view positions are converted. */
    private int trackedModel(int viewIndex) {
        return viewIndex >= 0 && viewIndex < rows.size() ? modelOf(viewIndex) : -1;
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
     * The ordinal of every entry that just arrived: how many rows before it carry an equal key.
     * A read of the rows before each, which is the price of telling equal records apart when no
     * {@link #rowKey} promises they differ; a handful of arrivals scan for themselves, and many
     * (a range, a select-all) share one pass over the rows with a map of their keys.
     */
    private void ordinalsOfAdded() {
        int n = records.addedCount;
        if (n <= 16) {
            for (int i = 0; i < n; i++) {
                int at = records.added[i];
                Object key = records.keys[at];
                int model = records.models[at];
                int ordinal = 0;
                for (int m = 0; m < model; m++) {
                    if (Objects.equals(keyOf(rows.get(m)), key)) {
                        ordinal++;
                    }
                }
                records.ordinals[at] = ordinal;
            }
            return;
        }
        java.util.HashMap<Object, int[]> seen = new java.util.HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            seen.putIfAbsent(records.keys[records.added[i]], new int[1]);
        }
        int last = records.models[records.added[n - 1]];
        int nextAdded = 0;
        for (int m = 0; m <= last; m++) {
            Object key = keyOf(rows.get(m));
            int[] counter = seen.get(key);
            if (counter == null) {
                continue;
            }
            if (records.models[records.added[nextAdded]] == m) {
                records.ordinals[records.added[nextAdded]] = counter[0];
                nextAdded++;
            }
            counter[0]++;
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
     * follows the record and not the index (decision 23 of 2026-09-14): stable across a scroll
     * away and back and across a sort, which moves no model index, and carried by
     * {@link #refresh()} to wherever a published record went. Allocates nothing.
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
    }

    private void clearPublished() {
        Arrays.fill(publishedKeys, 0, publishedCount, null);
        publishedCount = 0;
    }

    /**
     * A base for a range of {@code count} identities that no identity issued so far and none of
     * the first {@code keep} overrides lies in: past the high-water mark while that fits below
     * {@link #ROW_ID_LIMIT}, which keeps a verb sent for a vanished record from naming a row
     * that took its identity; else the lowest gap the kept overrides leave.
     */
    private long freshRowIdBase(int count, int keep) {
        long base = rowIdHighWater;
        if (base + count <= ROW_ID_LIMIT) {
            rowIdHighWater = base + count;
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
     * Follows the rows the last describe published to where the list holds their records now,
     * for {@link #refresh()}: a record found where it was keeps everything as it is; otherwise
     * each found record keeps its identity as an override at its new row, the rest of the rows
     * take a fresh range, and a record the list no longer holds takes its identity with it, so a
     * verb still addressed to it is refused. One read of the rows, stopping at the last found;
     * nothing when nothing was published (no reader).
     */
    private void followPublishedRows() {
        int n = publishedCount;
        int count = rows.size();
        if (n == 0) {
            dropOverridesFrom(count);
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
        for (int i = 0; i < n; i++) {
            keys[i] = publishedKeys[order[i]];
            ordinals[i] = publishedOrdinals[order[i]];
        }
        int[] now = rediscover(keys, ordinals, n);
        boolean stayed = true;
        for (int i = 0; i < n && stayed; i++) {
            stayed = now[i] == publishedModels[order[i]];
        }
        if (stayed) {
            dropOverridesFrom(count);
            return;
        }
        int found = 0;
        int[] models = new int[n];
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            if (now[i] >= 0) {
                models[found] = now[i];
                ids[found] = publishedIds[order[i]];
                found++;
            }
        }
        // By model, for rowIdOf's binary search; now[] follows the old model order, not the new.
        Integer[] byModel = new Integer[found];
        for (int i = 0; i < found; i++) {
            byModel[i] = i;
        }
        Arrays.sort(byModel, (a, b) -> Integer.compare(models[a], models[b]));
        if (overrideModels.length < found) {
            overrideModels = new int[found];
            overrideIds = new long[found];
        }
        for (int i = 0; i < found; i++) {
            overrideModels[i] = models[byModel[i]];
            overrideIds[i] = ids[byModel[i]];
        }
        overrideCount = found;
        rowIdBase = freshRowIdBase(count, found);
        clearPublished();
    }

    /** Forgets the overrides of rows a shorter list no longer has. */
    private void dropOverridesFrom(int count) {
        while (overrideCount > 0 && overrideModels[overrideCount - 1] >= count) {
            overrideCount--;
        }
    }

    /**
     * Reads the ordinal of every mounted row about to be published for the first time, and
     * records what this describe publishes for {@link #followPublishedRows}. Without a
     * {@link #rowKey} a row's ordinal is how many rows before it hold an equal record, which is
     * a read of those rows; the rows that need one share a single pass. A quiet frame finds
     * every ordinal known, and allocates nothing.
     */
    private void notePublishedRows() {
        int lacking = 0;
        int deepest = -1;
        for (int i = 0; i < mountedCount; i++) {
            Slot slot = mountedSlots[i];
            if (slot.ordinalKnown) {
                continue;
            }
            if (rowKey != null) {
                slot.ordinal = 0; // keys are unique by the contract rowKey states
                slot.ordinalKnown = true;
                continue;
            }
            slot.ordinal = 0;
            lacking++;
            deepest = Math.max(deepest, modelOf(slot.row));
        }
        if (lacking > 0) {
            // The rows that need one, by model, and a counter per key among them.
            java.util.HashMap<Object, int[]> seen = new java.util.HashMap<>(lacking * 2);
            Slot[] waiting = new Slot[lacking];
            int w = 0;
            for (int i = 0; i < mountedCount; i++) {
                Slot slot = mountedSlots[i];
                if (!slot.ordinalKnown) {
                    seen.putIfAbsent(slot.key, new int[1]);
                    waiting[w++] = slot;
                }
            }
            Arrays.sort(waiting, (a, b) -> Integer.compare(modelOf(a.row), modelOf(b.row)));
            int next = 0;
            for (int m = 0; m <= deepest; m++) {
                int[] counter = seen.get(keyOf(rows.get(m)));
                if (next < lacking && modelOf(waiting[next].row) == m) {
                    waiting[next].ordinal = counter == null ? 0 : counter[0];
                    waiting[next].ordinalKnown = true;
                    next++;
                }
                if (counter != null) {
                    counter[0]++;
                }
            }
        }
        if (publishedModels.length < mountedCount) {
            int grown = Math.max(mountedCount, publishedModels.length * 2);
            publishedModels = new int[grown];
            publishedIds = new long[grown];
            publishedKeys = new Object[grown];
            publishedOrdinals = new int[grown];
        }
        int n = 0;
        for (int i = 0; i < mountedCount; i++) {
            Slot slot = mountedSlots[i];
            publishedModels[n] = modelOf(slot.row);
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
        records.clear();
        resetRowIds();
        anchorIndex = 0;
        anchorTop = 0;
        resort();
        unmountAll();
        recomputeFooter();
        markNeedsLayout();
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
     * column's width, visibility or alignment does. The sort is re-applied and the scroll
     * position is kept, clamped.
     *
     * <p><b>A row is its record</b> (decision 23 of 2026-09-14; ADR 041 §3 amended): the
     * selection, the lead, the focus cell and the range anchor follow their records to wherever
     * the list holds them now, after an insert, a remove, a reorder or the application's own
     * sort ({@link #onSortRequest}). A record is found again by its {@linkplain #rowKey key} —
     * the record itself, by {@code equals}, unless one is set — and records with equal keys are
     * told apart by occurrence: the third equal record stays the third. Finding them is one
     * read of the rows, stopping at the last one found; a table with nothing selected and no
     * focus cell reads nothing. A selected record the list no longer holds leaves the selection,
     * announced as {@code SELECTION}/{@code ADJUSTMENT}; a vanished lead makes the last selected
     * row the lead; a vanished focus row or anchor keeps its position, clamped. A focus row that
     * moved is announced as {@code ACTIVE}/{@code ADJUSTMENT}; when the refresh answers a
     * {@linkplain #onSortRequest sort request} it is also revealed with the least scroll, as the
     * table's own sort does, and otherwise the scroll position is kept. Then {@code CHILDREN}/
     * {@code CODE}; nothing reaches a handler. A row's accessible node follows its record the
     * same way: the rows the last publish described keep their nodes wherever their records went,
     * and a reader's verb sent before the refresh acts on the record it named, or is refused when
     * that record is gone (the node half of decision 23, 2026-09-15). UI thread only.
     */
    public void refresh() {
        Ui.checkUiThread();
        // The nodes a reader holds first, before anything below re-mounts the rows under them.
        followPublishedRows();
        // The row whose widget cell holds the keyboard, found again before anything re-mounts.
        Slot keep = focusedWidgetSlot();
        int keepModel = keep == null ? -1 : findAgain(keep);
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
        anchorIndex = Math.max(0, Math.min(anchorIndex, Math.max(0, count - 1)));
        unmountAllKeeping(keepModel >= 0 ? keep : null, keepModel);
        textEpoch++;
        recomputeFooter();
        markNeedsLayout();
        invalidate();
        if (answeringSortRequest && focusRow >= 0 && focusRow != wasFocusRow) {
            // A refresh that answers a sort request is the application's sort, and reveals the
            // focus row as the table's own does (decision 40); any other refresh keeps the
            // scroll position it promised to keep.
            pendingEnsureVisible = focusRow;
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
    public Table<T> rowKey(Function<? super T, ?> key) {
        Ui.checkUiThread();
        this.rowKey = key;
        records.clear();
        syncRecords();
        for (int i = 0; i < mountedCount; i++) {
            Slot slot = mountedSlots[i];
            slot.key = keyOf(rows.get(modelOf(slot.row)));
            slot.ordinalKnown = false;
        }
        clearPublished(); // taken under the old key; the next describe publishes them again
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
    public Table<T> onSelect(Runnable handler) {
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
    public Table<T> onActivate(IntConsumer handler) {
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
     * What opens is the <b>cursor row</b> (decision 32 of 2026-09-14): the row the focus cell is
     * in, which in {@code SINGLE} is the lead, in {@code MULTI} may differ from it after a toggle
     * or a Shift range, and in {@code NONE} is the only row there is.
     */
    private void activate(Change.Origin origin) {
        if (focusRow >= 0 && focusRow < rows.size()) {
            activated = modelOf(focusRow);
            notifyChange(Change.of(Change.Aspect.INVOKED, origin));
        }
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
     * <p>The focus cell and the range anchor go with their records (decision 23 of 2026-09-14;
     * ADR 041 §3 amended): both are view positions, and a permutation that left them where they
     * stood put the cursor and the next Shift range on whatever record the sort moved there. The
     * focus row is then revealed with the least scroll that shows it (decision 40), as every
     * other write that moves the focus cell does, and its move is announced as {@code ACTIVE}/
     * {@code ADJUSTMENT} before the rows are: a consequence of the sort, not a gesture of its
     * own, and one the cursor's reader hears first.
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
     * Rebuilds the permutation from the sort the header shows — or drops it, while a sort
     * request handler is set — and carries the focus cell and the range anchor to their
     * records, reveals the focus row and announces the move and then the rows, as
     * {@link #applySort} does. The seam a change to {@link #onSortRequest} re-runs as well
     * (TABLE-NEW-4, 2026-09-14): setting the handler drops the table's permutation at once,
     * clearing it re-applies the table's own sort on the column the header shows.
     */
    private void permute(Change.Origin origin) {
        int count = rows.size();
        int focusModel = focusRow >= 0 && focusRow < count ? modelOf(focusRow) : -1;
        int anchorModel = rangeAnchor >= 0 && rangeAnchor < count ? modelOf(rangeAnchor) : -1;
        Slot keep = focusedWidgetSlot();
        int keepModel = keep == null ? -1 : modelOf(keep.row);
        resort();
        int wasFocusRow = focusRow;
        if (focusModel >= 0) {
            focusRow = viewOf(focusModel);
        }
        if (anchorModel >= 0) {
            rangeAnchor = viewOf(anchorModel);
        }
        unmountAllKeeping(keep, keepModel);
        markNeedsLayout();
        invalidate();
        if (focusRow >= 0) {
            // Deferred to the layout that re-places the rows: nothing is realized now, so the
            // reveal could only put the row at the top, and the least scroll needs the run.
            pendingEnsureVisible = focusRow;
        }
        announceFocusCell(wasFocusRow, Change.Origin.ADJUSTMENT);
        notifyChange(Change.of(Change.Aspect.CHILDREN, origin));
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
     * and the order the click asks for, orders the list itself and calls {@link #refresh()},
     * which carries the selection and the focus cell to where their records are now. The
     * header shows the order the click asked for from the click itself. A handler, so it
     * answers the user's click and never {@link #setSort}; one slot, as every {@code onX} is
     * (ADR 040 §1): {@code null} clears it, a second handler over the first throws.
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
    public Table<T> onSortRequest(BiConsumer<Column<T>, SortOrder> handler) {
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

    /** Whether the scroll bars overlay the rows or reserve strips beside them. */
    public ScrollGutters.Layout barLayout() {
        return gutters.layout();
    }

    /**
     * Sets how many rows tall the rows' viewport prefers to be when the parent gives the table
     * no height — a table inside a {@link limn.components.ScrollView} or an unconstrained
     * column — as a count of the step's seed rows (default 8); the header and the footer add
     * their own strips. A bounded height from the parent always wins; this is the free-axis
     * fallback only. The preference is the seed's and not the realized rows' on purpose
     * (decision 44 of 2026-09-14): a preference that followed the measured average moved every
     * time a row of another height scrolled in, and re-laid out the parent with it. UI thread
     * only.
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

    /** The vertical bar's model writing the offset: the user dragging or paging the bar. */
    private void scrollToOffset(float offset, SizeTokens t) {
        float clamped = Math.max(0, offset);
        float avg = avgRowHeight(t);
        int wasAnchor = anchorIndex;
        float wasTop = anchorTop;
        anchorIndex = avg > 0 ? (int) (clamped / avg) : 0;
        anchorIndex = Math.max(0, Math.min(anchorIndex, Math.max(0, rows.size() - 1)));
        anchorTop = anchorIndex * avg - clamped;
        markNeedsContainedLayout(); // a drag of the bar is a scroll; see the wheel and ensureVisible
        invalidate();
        vBar.onScrolled();
        if (anchorIndex != wasAnchor || anchorTop != wasTop) {
            notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
        }
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
     * re-mounted so that a hidden widget column's widgets are released and a newly shown one's
     * are built (B6: until 2026-09-14 a hidden widget column built a widget per row that was a
     * Tab stop and a published node with a column past the table's), and the focus column is
     * resolved again from the column it stands on: the same column if it is still shown, else
     * the nearest shown one, announced as {@code ACTIVE}/{@code ADJUSTMENT} when the cell moved
     * (TABLE-NEW-5: until this date the cursor kept a shown index no column matched, so no ring
     * was drawn and no cell was {@code ACTIVE} until a Left or Right re-clamped it). The header's
     * column cursor follows its column by the same rule (decision 36's cursor; review of
     * table-B, 2026-09-14: it kept a plain index clamp, so it slid onto another column).
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
     * Brings the realized rows' widget cells in line with the shown set: a hidden widget
     * column's widgets are released and a newly shown one's built, and every other widget cell
     * stays where it is, the one holding the keyboard included (review of table-B, 2026-09-14:
     * this re-mounted every row, so hiding a value column took the keyboard off the switch a
     * user was on and rebuilt every widget cell). A released widget that held the keyboard hands
     * it to the table, as a recycled row's does.
     */
    private void remountWidgetColumns() {
        boolean handBack = false;
        int childAt = 2; // the two bars come first
        int count = rows.size();
        for (int i = 0; i < mountedCount; i++) {
            Slot slot = mountedSlots[i];
            for (int c = 0; c < columns.size(); c++) {
                Column<T> column = columns.get(c);
                Widget widget = slot.widgets[c];
                if (!column.isWidgetColumn()) {
                    continue;
                }
                if (column.isVisible() && widget == null && slot.row < count
                        && (view == null || slot.row < view.length)) {
                    widget = column.widgetFor(rows.get(modelOf(slot.row)));
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
        if (isFocused() && focusRow >= 0 && focusRow < count && slotFor(focusRow) == null) {
            // The focus cell's row is realized wherever the viewport is while the table holds the
            // keyboard (decision 22 of 2026-09-14): a refresh or a sort unmounted everything, and
            // a reader's cursor stands on that cell whether or not it is in view.
            mount(focusRow);
        }
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
        slot.top = rowY;
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
        int model = modelOf(index);
        T row = rows.get(model);
        slot.id = rowIdOf(model);
        slot.key = keyOf(row);
        Locale locale = locale();
        for (int c = 0; c < columns.size(); c++) {
            Column<T> column = columns.get(c);
            if (column.isWidgetColumn()) {
                // A hidden widget column builds nothing (B6, 2026-09-14): its widget was never
                // laid out, but it was a child, and so a Tab stop and a published node. The
                // next layout to show the column re-mounts the rows and builds it then.
                if (column.isVisible()) {
                    Widget widget = column.widgetFor(row);
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

    /** The mounted row one of whose widget cells holds the keyboard, or {@code null}. */
    private Slot focusedWidgetSlot() {
        for (int i = 0; i < mountedCount; i++) {
            if (mountedSlots[i].widgetCount > 0 && containsFocus(mountedSlots[i])) {
                return mountedSlots[i];
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
     * Releases every mounted row but {@code keep}, whose widget cell holds the keyboard and whose
     * record stands at {@code model} now: it stays mounted, its widgets children and the focus
     * where it is, re-bound to the row that shows its record (decision 22 of 2026-09-14, the
     * widget-cell half, 2026-09-15). Until then a refresh or a sort released it and handed the
     * keyboard to the table, which is {@code ListView}'s rule for rows bound to data the list may
     * no longer hold; a table follows its records (decision 23), and a record found again is the
     * one the widget was built for. With no row to keep this is {@link #unmountAll()}.
     */
    private void unmountAllKeeping(Slot keep, int model) {
        if (keep == null || model < 0 || model >= rows.size()) {
            unmountAll();
            return;
        }
        int at = 0;
        while (mountedSlots[at] != keep) {
            at++;
        }
        // Out of the run for the release, then back as the only mounted row.
        System.arraycopy(mountedRows, at + 1, mountedRows, at, mountedCount - at - 1);
        System.arraycopy(mountedSlots, at + 1, mountedSlots, at, mountedCount - at - 1);
        mountedCount--;
        unmountAll();
        int view = viewOf(model);
        keep.row = view;
        keep.top = Float.NaN;
        keep.id = rowIdOf(model);
        keep.key = keyOf(rows.get(model));
        keep.ordinalKnown = false;
        rebind(keep);
        mountedRows[0] = view;
        mountedSlots[0] = keep;
        mountedCount = 1;
    }

    /**
     * Releases every mounted row outside {@code [from, toExclusive)} except two: the one holding
     * the keyboard focus in a widget cell, which stays mounted while its index is still below
     * {@code count} — the reason is {@code ListView}'s (ADR 039 §13.29) — and, while the table
     * itself holds the keyboard, the focus cell's row (decision 22 of 2026-09-14): a reader's
     * cursor stands on that cell, and a wheel that recycled it left the reader on nothing until
     * the next arrow key. Released by the first pass after the focus leaves.
     */
    private void recycleExcept(int from, int toExclusive, int count) {
        int kept = 0;
        boolean cursorKept = isFocused();
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            Slot slot = mountedSlots[i];
            boolean inRun = row >= from && row < toExclusive;
            boolean hasFocus = !inRun && containsFocus(slot);
            boolean isCursor = !inRun && cursorKept && row == focusRow;
            if (inRun || ((hasFocus || isCursor) && row < count)) {
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

    /** @return whether the keyboard focus is on {@code cell} or inside it */
    private boolean holdsFocus(Widget cell) {
        Widget focused = scene() != null ? scene().focusedWidget() : null;
        for (Widget w = focused; w != null; w = w.parent()) {
            if (w == cell) {
                return true;
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
        } else if (placedTo > placedFrom && index >= placedTo) {
            // Below the run: the least scroll that shows it puts it last, so the anchor is the
            // row itself, set to end at the viewport's foot; the layout walks the rows above it
            // up from there (decision 40 of 2026-09-14).
            anchorIndex = index;
            anchorTop = rowsViewportHeight() - measuredHeight(index, tokens());
        } else {
            anchorIndex = index;
            anchorTop = 0;
        }
        // Contained, for the reason the wheel path already gives: a reveal changes which rows are
        // mounted and where they sit, and both are inside a box this widget clips and whose own
        // size a reveal cannot move. A full layout here made every Page key and every keyboard
        // walk off the edge a whole-window repaint.
        markNeedsContainedLayout();
    }

    private void ensureColumnVisible(int s) {
        if (s < 0 || s >= shownCount) {
            return;
        }
        float viewW = gutters.viewportWidth(width());
        float left = colX[s] - offsetX;
        float right = left + colW[s];
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
        int wasFocusRow = focusRow;
        BitSet before = (BitSet) selected.clone();
        focusRow = viewIndex;
        rangeAnchor = viewIndex;
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
     * Toggles one row's membership, in MULTI, as a user. The command-click and Space move the
     * focus cell and the range anchor to the row ({@code moveCursor}); a reader's
     * {@code ADD_TO_SELECTION} and {@code DESELECT} leave both where they stand and scroll
     * nothing, because only {@code SELECT} and {@code FOCUS} move a cursor (decision 20 and
     * semantics 5 of 2026-09-13/14): a reader that adds a row it reached by its own navigation
     * has not asked the user's cursor to follow it.
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
     * <p>ADR 043 &sect;9.2. The band is the row across the rows' viewport, and it needs no
     * outset: the stripe and the selection tint fill exactly it, and the focus cell's ring is
     * drawn <em>inset</em> within one column of it. A row outside the placed run has no box to
     * damage, and whatever put it out of view damaged the table on its own.
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
                    if (slot.texts[c] == null || slot.texts[c].isEmpty()) {
                        continue; // a widget column has no text; its widget was painted above
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
                if (row == focusRow && isFocused() && !headerFocused
                        && focusColumn < shownCount) {
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
                if (headerHoldsCursor() && headerColumn < shownCount) {
                    // The header's column cursor: the same thin ring the focus cell wears,
                    // inset in the header cell, so one mark means "the keyboard is here" in
                    // both stops (decision 36 of 2026-09-14; renders reviewed by the owner).
                    float left = columnLeft(headerColumn, rowX, w, rtl);
                    float inset = Strokes.FOCUS_RING_THIN;
                    canvas.drawRoundRect(left + inset, inset, colW[headerColumn] - 2 * inset,
                            headerH - 2 * inset, t.radiusSmall(), Strokes.FOCUS_RING_THIN,
                            theme.focusRing);
                }
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
                // A detent is a device unit: the same flick travels the same distance in a
                // dense table and a roomy one, so the step is locked, not tabled. The two axes
                // are taken independently, as ScrollView takes them (TABLE-NEW-12, 2026-09-14):
                // until then any scrollX made the event sideways and dropped scrollY, so a
                // trackpad swipe that was not perfectly vertical scrolled nothing on a table
                // whose columns fit. Shift turns a plain vertical wheel into a horizontal one
                // for a mouse with one wheel; taken only when the event carries no scrollX, so
                // a tilt wheel and Shift cannot drive the same axis in one event.
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
                            headerClicked(s);
                        }
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
                long now = sceneNanos();
                boolean second = row == lastPressRow && now - lastPressNanos < DOUBLE_CLICK_NANOS;
                lastPressRow = row;
                lastPressNanos = second ? 0 : now;
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
     * The keys while the header holds the keyboard (decision 36 of 2026-09-14): Left and Right
     * move the column cursor, Space sorts the column under it, cycling ascending, descending and
     * the model's order as a click does; Down hands the keyboard to the rows. The other row keys
     * are consumed and do nothing, so the rows do not move under a cursor that is not in them.
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
    // are clipped to the box (TABLE-NEW-10, 2026-09-14): until this the default answered the
    // whole box for every child, so a switch scrolled under the header or the footer, or lying
    // in a reserved gutter, was isShowing() and published SHOWING in a rectangle this table
    // never paints it in, a reader could toggle it, and a point on the header resolved to it.
    // The four run for every node of every accessible walk and allocate nothing.

    @Override
    protected float clipX(Widget child) {
        return child == vBar || child == hBar ? 0 : rowsLeft();
    }

    @Override
    protected float clipY(Widget child) {
        return child == vBar || child == hBar ? 0 : rowsTop();
    }

    @Override
    protected float clipWidth(Widget child) {
        return child == vBar || child == hBar ? width() : gutters.viewportWidth(width());
    }

    @Override
    protected float clipHeight(Widget child) {
        return child == vBar || child == hBar ? height() : rowsViewportHeight();
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
        notePublishedRows();
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
        if (focusRow >= 0 && focusRow < describedRowCount) {
            // Whenever there is a cursor, in every mode: a press opens the cursor row, as Enter
            // and a double click do (decision 32 of 2026-09-14).
            a.action(Accessible.Action.PRESS);
        }

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
                a.role(Accessible.Role.COLUMN_HEADER);
                a.name(column.title(), Accessible.NameFrom.CONTENT);
                a.cell(-1, s);
                if (column.isSortable()) {
                    // A press sorts, as a click does (decision 36 of 2026-09-14). The direction
                    // the rows run is the sorted header's description until the platforms'
                    // carriers of a sort direction have been read (phase 3).
                    a.action(Accessible.Action.PRESS);
                    if (column == sortColumn && sortOrder != SortOrder.NONE) {
                        a.description(sortOrder == SortOrder.ASCENDING
                                ? TableStrings.SORTED_ASCENDING : TableStrings.SORTED_DESCENDING);
                    }
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

        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            Slot slot = mountedSlots[i];
            int model = modelOf(row);
            float top = rowTop(row);
            boolean shown = !Float.isNaN(top);
            if (!shown) {
                // The kept row a scroll spared: published where the layout put it, outside the
                // rows' viewport, so the ROW and a widget cell inside it agree on a box
                // (TABLE-NEW-9, 2026-09-14); before a layout has placed it, where the estimate
                // would.
                top = Float.isNaN(slot.top)
                        ? (row < placedFrom ? headerH - slot.height : headerH + viewH)
                        : slot.top;
            }
            boolean rowOffScreen = !shown || top + slot.height <= headerH
                    || top >= headerH + viewH;
            a.child(slot.id);
            a.bounds(rowX, top, w, slot.height);
            a.role(Accessible.Role.ROW);
            boolean isSelected = selected.get(model);
            a.selectionItem(isSelected, row + 1, describedRowCount);
            // The verbs a row accepts, by its state (decisions 10, 11 and 20 of 2026-09-14):
            // SELECT is the click; ADD_TO_SELECTION on an unselected row and DESELECT on a
            // selected one only where the mode allows more than one; FOCUS moves the cursor
            // here without selecting, and is published because the cursor and the selection are
            // separate things in a table.
            if (selectionMode != SelectionMode.NONE) {
                a.action(Accessible.Action.SELECT);
                if (selectionMode == SelectionMode.MULTI) {
                    a.action(isSelected ? Accessible.Action.DESELECT
                            : Accessible.Action.ADD_TO_SELECTION);
                }
            }
            a.action(Accessible.Action.FOCUS);
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
                a.role(Accessible.Role.CELL);
                a.name(slot.texts[c], textEpoch, Accessible.NameFrom.CONTENT);
                a.cell(row, s);
                a.action(Accessible.Action.FOCUS);
                if (row == focusRow && s == focusColumn && isFocused() && !headerFocused) {
                    // Only while the table holds the keyboard (ADR 039 §1.10, amended
                    // 2026-09-14) in its rows: the cursor is the focused node's, one at a time,
                    // and while the header holds it the cursor is a header cell.
                    a.state(Accessible.State.ACTIVE);
                }
                // Off screen with its row as well as with its column: the bit is per node, and
                // nothing is inherited from a synthetic parent, so a kept row's cells were
                // published SHOWING over the header band (TABLE-NEW-9, 2026-09-14).
                if (rowOffScreen || left + colW[s] <= rowX || left >= rowX + w) {
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
                a.child(FOOTER_CELL_KEY | c);
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

    /**
     * A widget cell hangs under the synthetic {@code ROW} of the record it shows and is keyed by
     * its column within that row (decision 3 of 2026-09-13; ADR 039 §1.3 and §7.1 amended
     * 2026-09-14): a reader walking the row finds the control among its cells, in column order,
     * and the cell's identity follows the record the row is keyed by, so a control recycled to
     * another row carries nothing of the old one. The key is the column alone, because the row's
     * node scopes it; no packing of row and column into one number.
     */
    @Override
    protected void onAccessibilityChildIdentity(Widget child, Accessibility a) {
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
        a.under(slot.id);
        a.key(c);
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
        if (s == shownCount) {
            return; // the column was hidden since the last layout; the next one releases it
        }
        a.cell(slot.row, s);
        if (slot.row == focusRow && s == focusColumn && isFocused() && !headerFocused) {
            // The focus cell in a widget column is the cursor exactly as a value cell is (B1,
            // 2026-09-14): the ring was drawn on it and the reader was told nothing, so the
            // active descendant fell to nothing on every Right into a switch column.
            a.state(Accessible.State.ACTIVE);
        }
    }

    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (action == Accessible.Action.PRESS && focusRow >= 0 && focusRow < rows.size()) {
            activate(Change.Origin.USER);
            return true;
        }
        return false;
    }

    /**
     * A reader's verb on a row or a cell, decoded from the key the node was published with:
     * a row's key is its record's row identity, a cell's carries that and its column (TABLE-NEW-13:
     * until 2026-09-14 a cell was keyed by its column alone and a select on it selected the
     * row of that number). The verbs are the published ones and no other — a cell accepts
     * {@code FOCUS} alone — and each goes through the seam the matching gesture takes at
     * {@code USER}: {@code SELECT} is the click, {@code ADD_TO_SELECTION} and {@code DESELECT}
     * the command-click's toggle without its cursor move (only {@code SELECT} and {@code FOCUS}
     * move the cursor, decision 20), {@code FOCUS} a cursor move that selects nothing. A row is
     * named by the identity of the record the snapshot published it for (decision 23, the node
     * half, 2026-09-15): a verb sent before a {@link #refresh()} that inserted a row above acts
     * on that same record where it stands now, and one whose record left the list is refused.
     * Until then it acted on whatever record stood at the published model index.
     */
    @Override
    protected boolean onSyntheticAction(long key, Accessible.Action action,
                                        Accessible.Argument arg) {
        if ((key & CELL_KEY) != 0) {
            int model = modelOfRowId((key & ~CELL_KEY) >>> COLUMN_BITS);
            int c = (int) (key & COLUMN_MASK);
            int s = shownIndexOf(c);
            if (action == Accessible.Action.FOCUS && model >= 0 && s >= 0) {
                focusCell(viewOf(model), s, Change.Origin.USER);
                return true;
            }
            return false;
        }
        if ((key & HEADER_CELL_KEY) != 0) {
            int s = shownIndexOf((int) (key & COLUMN_MASK));
            if (action == Accessible.Action.PRESS && s >= 0
                    && columns.get(shownIndex[s]).isSortable()) {
                // Sorts as a click does, and as the click does remembers the column for the
                // header's cursor, so a Shift+Tab into the header after a reader's press on
                // the Age title starts on Age; while the header holds the keyboard the cursor
                // moves with the press, which the sort's own publish announces.
                setHeaderColumn(s);
                headerClicked(s);
                return true;
            }
            return false;
        }
        if ((key & FOOTER_CELL_KEY) != 0 || key < 0) {
            return false;
        }
        int model = modelOfRowId(key);
        if (model < 0) {
            return false; // the record this row was published for is gone from the list
        }
        int view = viewOf(model);
        switch (action) {
            case SELECT -> {
                if (selectionMode == SelectionMode.NONE) {
                    return false;
                }
                selectOnly(model, view, true, Change.Origin.USER);
                return true;
            }
            case ADD_TO_SELECTION -> {
                if (selectionMode != SelectionMode.MULTI || selected.get(model)) {
                    return false;
                }
                toggle(view, false);
                return true;
            }
            case DESELECT -> {
                if (selectionMode != SelectionMode.MULTI || !selected.get(model)) {
                    return false;
                }
                toggle(view, false);
                return true;
            }
            case FOCUS -> {
                focusCell(view, focusColumn, Change.Origin.USER);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

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
     * Moves the focus cell without touching the selection: what a reader's {@code FOCUS} on a
     * row or a cell asks for (decision 11 of 2026-09-14). The range anchor moves with it, as it
     * does under a toggle, so the next Shift range extends from where the cursor is; the row is
     * revealed and the move announced as {@code ACTIVE}.
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

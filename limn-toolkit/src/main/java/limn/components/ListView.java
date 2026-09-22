package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Transition;
import limn.components.a11y.RowsAccessibility;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.lang.Checks;
import limn.scene.Constraints;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Change;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * A vertically scrolling list that <b>virtualizes</b> its rows like a
 * {@code JTable}/{@code RecyclerView}: only the rows in the viewport get a
 * widget, so a list of a million rows costs the same as one of twenty (layout
 * and paint are {@code O(visible)}).
 *
 * <p>Rows may have <b>different heights</b>: e.g. small grouping headers among
 * normal cards. There is no global height cache and no binary search: the list
 * keeps an <b>anchor</b> (which row sits where) and walks a handful of rows from
 * it each frame, measuring each on demand. Scroll-thumb size/position are
 * estimated from the average measured height (good enough for a scroll
 * indicator).
 *
 * <p><b>You</b> supply and cache the widgets through an {@link Adapter}: the
 * list asks {@link Adapter#rowAt} for the (already-populated) widget of a row,
 * and hands it back via {@link Adapter#recycle} when it scrolls out, so the
 * adapter can pool per row type and rebind, or just create fresh (simplest, no
 * pooling). The list owns only the tree/positioning; the caching policy is
 * yours.
 *
 * <p>Interaction: the wheel scrolls (with the shared {@link ScrollBar}); the
 * list is focusable and, while focused, Up/Down/Home/End/PageUp/PageDown move a
 * highlighted selection (auto-scrolling to reveal it) and Enter activates it;
 * clicking a row selects it (clicks on a row's own buttons reach those buttons).
 *
 * <p><b>{@link #children()} is the bar and then the realized rows in data order</b>, whichever
 * direction the scroll realized them in — which is what makes a Tab through those row buttons, and
 * the reading order an assistive technology is given, agree with the list on screen.
 *
 * <p><b>The row holding the keyboard focus is realized for as long as it holds it</b>, wherever a
 * scroll has taken the viewport: it stays mounted in data order, laid out outside the viewport,
 * unpainted and unreachable by the pointer, and published to an assistive technology as the list
 * item it is, not showing. A screen reader whose cursor follows the focus onto a row is otherwise
 * left standing on a node a page scroll deleted. Every other row outside the viewport goes back
 * to the adapter, and so does that one the moment the focus leaves it or the data is refreshed.
 * <b>And so is the selected row while the list itself holds the keyboard</b> (decision 22,
 * 2026-09-14): the selection is the reader's cursor here, and a wheel or a bar drag that scrolled
 * it away used to recycle it, leaving the reader's cursor on nothing until the next arrow key.
 * It is kept the same way — mounted, outside the viewport, published not showing and still the
 * cursor — across a refresh too, and released by the first pass after the keyboard leaves.
 *
 * <p><b>Size steps propagate rather than being imposed.</b> Rows are adapter-supplied
 * widgets in this list's subtree, so they resolve the {@link limn.scene.ControlSize}
 * themselves and {@code list.setControlSize(SMALL)} shortens them because <em>they</em>
 * re-measure. Only three metrics are the list's own: the row-height seed, used for every
 * scroll estimate before anything has been measured and for the intrinsic height under an
 * unbounded constraint always ({@link #setVisibleRows} rows of it), the intrinsic width under
 * an unbounded constraint, and the selection ring's corner radius.
 *
 * <p><b>Inside a scroller</b> the list scrolls itself first and hands the wheel on at either
 * end: a detent that finds this list already at the top or the bottom is left unconsumed and
 * reaches the scroll pane that holds it (decision 44).
 *
 * <p><b>The scroll bar does not take part in the size axis</b> ({@link ScrollBar#thickness()}
 * is 15 pt at every step), and it overlays the rows rather than insetting them, so at a
 * compact step it covers a larger fraction of a shorter row. An accepted cost of one
 * scrollbar geometry process-wide.
 */
public final class ListView extends Widget implements Scrollable {

    /** Supplies and (optionally) caches the row widgets of a {@link ListView}. */
    public interface Adapter {
        /** @return the number of rows */
        int rowCount();

        /**
         * @return the widget for {@code index}, populated and ready to show. May
         *         be a reused instance you kept from {@link #recycle}.
         */
        Widget rowAt(int index);

        /** The list scrolled {@code widget} out of view; pool it for reuse if you like. */
        default void recycle(Widget widget) {
        }

        /**
         * What to call row {@code index} for an assistive technology.
         *
         * <p>A list publishes its true row count and describes only the rows it has actually
         * realized, because handing a screen reader thousands of anonymous items would be worse
         * for its user rather than better. This is asked for <b>two</b> of them. Once per realized
         * row whose own cell widget said nothing about itself — which is the common case, because
         * a cell is an application's widget and a cell that paints its own text usually declares
         * nothing, and the {@code LIST_ITEM} role the list writes onto it suppresses the warning
         * that would otherwise have been the application's only notice. And once more for a
         * selected row that is <em>not</em> realized, which has no widget to carry a name and is
         * announced from the list's own node instead.
         *
         * <p><b>Hand back a string this adapter holds.</b> The tree carries a name over from the
         * previous walk when the source is the same object under the same locale and translation
         * epoch, at no cost; a string built inside this call is never the same object, so it is
         * allocated and resolved again — once per realized row per walk that describes this list,
         * which is every damaged frame — and that is the zero-allocation promise this widget
         * otherwise keeps, broken by the application. It does <em>not</em> republish the tree or
         * raise an event: the difference compares the resolved text, and equal text is no change
         * (corrected 2026-09-14; the earlier text of this paragraph said it republished every
         * frame). A field, a constant, or an entry in the adapter's own data is what belongs here.
         *
         * @param index a row in {@code [0, rowCount)}
         * @return the row's name, or {@code null} when the adapter has none to give
         */
        default I18nString rowName(int index) {
            return null;
        }
    }

    /**
     * Rows of intrinsic height when the height axis is unbounded, until {@link #setVisibleRows}
     * says otherwise. A row <b>count</b>, not a length: it multiplies the step's seed row height,
     * so it must not move with the step.
     */
    private static final int VISIBLE_ROWS_HINT = 6;

    /**
     * How many seed rows tall this list prefers to be under an unbounded height (decision 44,
     * 2026-09-14). Multiplied by the token's seed and never by the realized average: the average
     * moves as rows of other heights scroll in, and a preference that moved with it re-laid out
     * the parent on every such scroll and made a list inside a scroll pane jitter.
     */
    private int visibleRows = VISIBLE_ROWS_HINT;

    private final Adapter adapter;
    private final ScrollBar vBar;
    private final ScrollGutters gutters = new ScrollGutters();

    /**
     * The realized rows, in <b>data order</b>: {@code mountedRows[i]} is the index
     * {@code mountedCells[i]} is bound to, ascending, and {@code children()} holds the bar and
     * then exactly these cells in exactly this order.
     *
     * <p>Two parallel arrays and not a {@code Map<Integer, Widget>}, for two reasons that arrived
     * together. Data order is <b>reading order and Tab order</b>, and rows are realized in neither
     * — an upward scroll mounts the rows above the anchor from the anchor downwards — so a
     * container that appended each one as it arrived would hand a screen reader "4 of 5000, 5 of
     * 5000, 3 of 5000" and walk a Tab through the rows' own buttons in the same wrong order. And
     * the describe hooks run on every damaged frame under a rule that they allocate nothing, which
     * a map keyed by {@code Integer} cannot meet: a get boxes its key for any row above 127, and
     * an iteration over its entries or values allocates an iterator per call. Grown once by
     * doubling and then reused.
     */
    private int[] mountedRows = new int[16];
    private Widget[] mountedCells = new Widget[16];
    private int mountedCount;

    /**
     * The half-open run of rows {@link #placeDown} last laid out, which is what
     * {@link #recycleExcept} keeps. A range and not a set of indices: the walk starts at the
     * anchor and steps down one row at a time, so what it places is contiguous by construction.
     */
    private int placedFrom;
    private int placedTo;

    // Anchor scroll state: the top edge of row `anchorIndex` sits at y = anchorTop.
    private int anchorIndex;
    private float anchorTop;
    /**
     * Mean measured row height, or 0 until layout has measured at least one row; the step's
     * {@code listRowSeed} stands in until then, resolved lazily by
     * {@link #avgRowHeight(SizeTokens)}. Seeding the field at construction is what the size
     * axis forbids: a widget has no parent while it is being constructed, so the seed would be
     * the process default's forever, with no path to recovery.
     */
    private float measuredRowHeight;

    private int selectedIndex = -1;
    private IntConsumer onSelect;
    private IntConsumer onActivate;
    /**
     * Fades the selected-row highlight between the resting outline and the focus ring.
     * The {@link Theme} reads here are <b>animation durations</b>, which are palette- and
     * step-independent; a size step read this way would be captured before this widget has a
     * parent and could never be corrected. Do not copy the pattern for metrics.
     */
    private final Transition focusFade =
            new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing)
                    // The fade draws one row's outline, so that is what each of its frames
                    // repaints. Without this the list repainted itself whole eleven times over
                    // for a Tab to land in it -- see Transition.damages.
                    .damages(() -> damageRow(selectedIndex));

    /** A list driven by {@code adapter}, which supplies and recycles the row widgets. */
    public ListView(Adapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        setFocusable(true);
        vBar = new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return estimatedContentHeight(tokens());
            }

            @Override
            public float viewportLength() {
                return gutters.viewportHeight(height());
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
        add(vBar);
    }

    /**
     * Sets whether the bar floats over the rows or reserves a strip of its own
     * (default {@link ScrollGutters.Layout#OVERLAY}). Reserved is what a list of
     * records usually wants: the trailing edge of a row is where a count, a date or
     * a status chip goes, and a thumb over it is a defect. Trailing and not right:
     * the bar and the row's last column are both on the left of a list that reads
     * right to left, and they move there together.
     */
    public ListView setBarLayout(ScrollGutters.Layout layout) {
        Ui.checkUiThread();
        gutters.setLayout(layout);
        markNeedsLayout();
        return this;
    }

    /** Whether the scrollbar overlays the rows or reserves a gutter. */
    public ScrollGutters.Layout barLayout() {
        return gutters.layout();
    }

    /** Sets when the vertical scrollbar is shown (default {@link ScrollBar.Policy#AUTO}). */
    public ListView setScrollbarPolicy(ScrollBar.Policy policy) {
        vBar.setPolicy(policy);
        return this;
    }

    /**
     * Sets how many rows tall this list prefers to be when its parent gives it no height — a
     * list inside a {@link ScrollView} or an unconstrained column — as a count of the step's
     * seed rows (default 6). A bounded height from the parent always wins; this is the free-axis
     * fallback only. The preference is the seed's and not the realized rows' on purpose
     * (decision 44): a preference that followed the measured average moved every time a row of
     * another height scrolled in, and re-laid out the parent with it. UI thread only.
     *
     * @param rows a row count of at least one
     * @return this list
     * @throws IllegalArgumentException if {@code rows} is below one
     */
    public ListView setVisibleRows(int rows) {
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

    /** How many seed rows tall this list prefers to be under an unbounded height. */
    public int visibleRows() {
        return visibleRows;
    }

    /**
     * The application's response to the user moving the selection: a click or a key. Never for
     * {@link #setSelectedIndex}, {@link #clearSelection()} or a {@link #refresh()} that
     * collapsed the selection, which are the caller's or the list's own; to hear every move
     * whatever caused it, {@linkplain #observeChanges watch} the list instead.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this list
     * @throws IllegalStateException if a handler is already registered
     */
    public ListView onSelect(IntConsumer handler) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, handler, "ListView.onSelect");
        return this;
    }

    /**
     * The application's response to the user opening the selected row: Enter, or an assistive
     * technology's press. Never for {@link #activate()}, which is a caller's verb.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this list
     * @throws IllegalStateException if a handler is already registered
     */
    public ListView onActivate(IntConsumer handler) {
        Ui.checkUiThread();
        this.onActivate = Checks.handlerSlot(onActivate, handler, "ListView.onActivate");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        switch (aspect) {
            case SELECTION -> {
                if (onSelect != null) {
                    onSelect.accept(selectedIndex);
                }
            }
            case INVOKED -> {
                if (onActivate != null) {
                    onActivate.accept(selectedIndex);
                }
            }
            default -> super.handleUserChange(aspect);
        }
    }

    /**
     * The selected row, or {@code -1} when nothing is selected. A list is one of the two widgets
     * in this set that genuinely has no-selection as a state: {@link #clearSelection()} reaches
     * it, and a fresh list is in it.
     */
    public int selectedIndex() {
        return selectedIndex;
    }

    /** Row count as the adapter currently reports it. */
    public int rowCount() {
        return adapter.rowCount();
    }

    /** @return the first fully-or-partly visible row index (tests/inspection) */
    public int firstVisibleIndex() {
        return anchorIndex;
    }

    /**
     * Re-reads the adapter and re-lays out (call after the data changes). Announces
     * {@code CHILDREN}/{@code CODE} after the rows are unmounted, and, when the adapter shrank
     * past the selected row, first moves the selection to the last row (or drops it when the
     * list is now empty) and announces that as {@code SELECTION}/{@code ADJUSTMENT}: a watcher
     * showing the selected record hears the list move by itself, with an origin that says so,
     * where a handler -- the user's response -- is not run. UI thread only.
     */
    public void refresh() {
        Ui.checkUiThread();
        int count = adapter.rowCount();
        anchorIndex = Math.max(0, Math.min(anchorIndex, Math.max(0, count - 1)));
        // Unmount every row: a mounted cell is bound to the OLD datum at its
        // index and layout reuses mounted cells without consulting the adapter,
        // so without this the visible viewport is exactly what never refreshes.
        // Every row, the one holding the keyboard focus included: it is bound to a datum
        // that may no longer exist, and the focus falls back to the list as it always did.
        recycleExcept(0, 0, 0);
        markNeedsLayout();
        invalidate();
        if (selectedIndex >= count) {
            // Without a reveal: a watcher runs on a list whose rows are already unmounted, and
            // revealing here would jump the anchor the clamp above just settled.
            select(count == 0 ? -1 : count - 1, false, Change.Origin.ADJUSTMENT);
        }
        notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE));
    }

    /**
     * Selects a row and scrolls it into view: a caller's write, so it announces
     * {@code SELECTION}/{@code CODE} and reaches no handler. Selecting the row that is already
     * selected changes nothing, reveals nothing and announces nothing. UI thread only.
     *
     * @param index a row in {@code [0, rowCount())}. {@code -1} is not an argument even though it
     *              is what {@link #selectedIndex()} reports for an empty selection:
     *              {@link #clearSelection()} is how that state is reached.
     * @throws IndexOutOfBoundsException if {@code index} is outside that range, an empty list
     *         included, where every index is. An index that came from a search which found
     *         nothing, or from state saved against longer data, is a caller's bug here, exactly as
     *         it is for {@code List.get}.
     */
    public ListView setSelectedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, adapter.rowCount());
        select(index, true, Change.Origin.CODE);
        return this;
    }

    /**
     * Drops the selection: {@link #selectedIndex()} becomes {@code -1}, announced as
     * {@code SELECTION}/{@code CODE}. No-op when nothing is selected. UI thread only.
     */
    public ListView clearSelection() {
        Ui.checkUiThread();
        select(-1, false, Change.Origin.CODE);
        return this;
    }

    /**
     * The one place the selection moves, and the one seam it announces from: the public setter
     * and {@code clearSelection} pass {@code CODE}, a refresh that collapsed it passes
     * {@code ADJUSTMENT}, and every key and click passes {@code USER}. {@code index} is already
     * valid or {@code -1}; announces only when it moved, after the reveal.
     */
    private void select(int index, boolean reveal, Change.Origin origin) {
        if (index == selectedIndex) {
            return;
        }
        int from = selectedIndex;
        selectedIndex = index;
        if (reveal && selectedIndex >= 0) {
            // Damages the list itself when it scrolls, which is the right answer then: a scroll
            // re-mounts every row, so two bands would be a lie.
            ensureVisible(selectedIndex);
        }
        damageRow(from);
        damageRow(selectedIndex);
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /**
     * What every key and click goes through. Arrowing past an end lands on the end and Page keys
     * overshoot by design, so the public setter's out-of-range throw is deliberately not the
     * contract of the widget's own traversal: a dead-ended arrow key is not a programming error.
     */
    private void selectClamped(int index) {
        int count = adapter.rowCount();
        if (count == 0) {
            return;
        }
        select(Math.min(Math.max(0, index), count - 1), true, Change.Origin.USER);
    }

    /**
     * Announces that the selected row was opened, as {@code INVOKED}/{@code CODE}: a caller's
     * verb, which reaches a watcher and <b>not</b> {@link #onActivate}, the way Enter does. An
     * application that wants its own open-the-row code run calls that code. Nothing without a
     * selection. UI thread only.
     */
    public void activate() {
        Ui.checkUiThread();
        activate(Change.Origin.CODE);
    }

    /** The seam Enter and an assistive technology's press enter at {@code USER}. */
    private void activate(Change.Origin origin) {
        if (selectedIndex >= 0) {
            notifyChange(Change.of(Change.Aspect.INVOKED, origin));
        }
    }

    /** Scrolls by a delta in logical points (positive = toward the end). UI thread only. */
    public void scrollBy(float dy) {
        Ui.checkUiThread();
        SizeTokens t = tokens(); // one resolution: the clamp and the estimate must agree
        float offset = estimatedOffset(t);
        float max = Math.max(0, estimatedContentHeight(t) - height());
        float applied = Math.min(Math.max(0, offset + dy), max) - offset;
        if (applied == 0) {
            return;
        }
        anchorTop -= applied;
        // Move the mounted rows NOW: revealInView re-reads coordinates between
        // nested scrollables in one pass (the Scrollable contract); the next
        // layout renormalizes the anchor and mounts/recycles as usual.
        //
        // moveChild, not layoutBox: a row's own layout does not depend on where the
        // row sits, so re-running it here computes the same answer it already had
        // (once per mounted row, per wheel detent, per drag frame), and the pass this
        // schedules re-runs it again anyway. ScrollView's scroll path is the same
        // shape for the same reason.
        for (int i = 0; i < mountedCount; i++) {
            Widget cell = mountedCells[i];
            moveChild(cell, cell.x(), cell.y() - applied);
        }
        // Contained, not global: a scroll changes which rows are mounted and where they sit, and
        // both are inside a box this widget clips and whose own size a scroll cannot move. Asking
        // for a full layout here made every wheel detent a full-window repaint, the work the
        // damage machinery exists to avoid, on the most common heavy interaction there is.
        markNeedsContainedLayout();
        invalidate();
        vBar.onScrolled();
    }

    /** Scrolls the minimum so the rect (in viewport coordinates) becomes visible. */
    @Override
    public void revealRect(float x, float y, float rectWidth, float rectHeight) {
        Ui.checkUiThread();
        if (y < 0) {
            scrollBy(y); // above the viewport: scroll back
        } else if (y + rectHeight > height()) {
            scrollBy(Math.min(y, y + rectHeight - height())); // oversize rows align their top
        }
    }

    // ------------------------------------------------------------- estimates

    /**
     * This list's token row. Called once at the top of a pass or a gesture, and from the
     * {@link ScrollBar.Model} callbacks, which are entered from the scroll bar's own pass and
     * so cannot be handed one. Everything reached from more than one of those takes the row as
     * a parameter: two resolutions inside one gesture would let the scroll estimate and the
     * clamp disagree, which is a scroll that sticks near the ends.
     */
    private SizeTokens tokens() {
        return Theme.of(this).tokensFor(this);
    }

    /**
     * The row-height estimate every scroll number is built from: the measured mean once layout
     * has produced one, else the step's seed. All estimates, no exception: a raw 0 here would
     * make an empty list's intrinsic height 0 and its page size 1.
     */
    private float avgRowHeight(SizeTokens t) {
        return measuredRowHeight > 0 ? measuredRowHeight : t.listRowSeed();
    }

    private float estimatedContentHeight(SizeTokens t) {
        return adapter.rowCount() * avgRowHeight(t);
    }

    private float estimatedOffset(SizeTokens t) {
        float avg = avgRowHeight(t);
        float max = Math.max(0, estimatedContentHeight(t) - height());
        return Math.max(0, Math.min(anchorIndex * avg - anchorTop, max));
    }

    private void scrollToOffset(float offset, SizeTokens t) {
        float clamped = Math.max(0, offset);
        float avg = avgRowHeight(t);
        anchorIndex = avg > 0 ? (int) (clamped / avg) : 0;
        anchorIndex = Math.max(0, Math.min(anchorIndex, Math.max(0, adapter.rowCount() - 1)));
        anchorTop = anchorIndex * avg - clamped;
        markNeedsContainedLayout(); // a drag of the bar is a scroll; see scrollBy and ensureVisible
        invalidate();
        vBar.onScrolled();
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = tokens();
        // Both are free-axis fallbacks a real parent overrides; they only bind when the list is
        // measured unbounded. The height is the SEED's and not avgRowHeight's (decision 44,
        // 2026-09-14): the measured mean moves as rows of other heights scroll in, and a
        // measured size that moved under a contained layout re-laid out the parent on every
        // such scroll (Widget.markNeedsContainedLayout's contract), so a list in a scroll pane
        // jittered. The seed is a token and stands still.
        float w = constraints.hasBoundedWidth() ? constraints.maxWidth() : t.listWidth();
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight()
                : visibleRows * t.listRowSeed();
        return constraints.constrain(w, h);
    }

    @Override
    protected void onLayout() {
        float box = width();
        float h = height();
        if (box <= 0 || h <= 0) {
            return;
        }
        // One resolution for the whole pass, as the axis requires: placeDown runs twice below
        // whenever the over-scroll retry fires, and the bar's side and the row origin are two
        // halves of one answer. Two resolutions that disagreed would put the rows under the bar.
        boolean rtl = isRightToLeft();
        // Settle the strip first: every row below is measured and placed into what
        // it leaves, so a reserved bar narrows the rows instead of covering them.
        // The estimate is what the bar itself reports, and it does not move with
        // the width, so the second pass finds the same answer and stops.
        gutters.resolve(box, h, vBar, null,
                (viewW, viewH) -> new Size(viewW, estimatedContentHeight(tokens())));
        float w = gutters.viewportWidth(box);
        vBar.measure(Constraints.tight(ScrollBar.thickness(), h));
        // The bar sits on the trailing side, which reading right to left is the left one.
        vBar.layoutBox(rtl ? 0 : box - ScrollBar.thickness(), 0, ScrollBar.thickness(), h);
        // Where a row's left edge goes: the viewport's leading edge, which is 0 reading left to
        // right and the far side of the reserved strip reading right to left. `box - w` is the
        // strip's own width, so it is 0 under OVERLAY and the rows keep the whole box in both
        // directions. It is not ScrollView's `viewportWidth - childWidth + offsetX`: a row is
        // measured at exactly `w`, so that expression would collapse to 0 and every row would
        // paint under the bar the line above just moved. The strip is known only once
        // `gutters.resolve` has settled it, so this cannot be hoisted above `w`.
        float rowX = rtl ? box - w : 0;

        int count = adapter.rowCount();
        if (count == 0) {
            recycleExcept(0, 0, 0);
            anchorIndex = 0;
            anchorTop = 0;
            return;
        }
        anchorIndex = Math.min(anchorIndex, count - 1);

        normalizeUp(w);
        normalizeDown(count, w);
        float bottom = placeDown(count, rowX, w, h);
        // Over-scrolled past the end: close the gap at the bottom, unless the
        // content is shorter than the viewport (then it stays top-aligned).
        if (bottom < h && !(anchorIndex == 0 && anchorTop >= 0)) {
            anchorTop += h - bottom;
            normalizeUp(w);
            normalizeDown(count, w);
            bottom = placeDown(count, rowX, w, h);
        }
        recycleExcept(placedFrom, placedTo, count);
        keepCursorRow(count, w);
        placeKeptOutside(rowX, w, bottom);
        updateAverageHeight();
        vBar.refresh();
        if (pendingEnsureVisible >= 0) {
            int pending = Math.min(pendingEnsureVisible, count - 1);
            pendingEnsureVisible = -1;
            ensureVisible(pending); // real geometry now; marks another pass if it moved
        }
    }

    private void normalizeUp(float w) {
        while (anchorTop > 0 && anchorIndex > 0) {
            anchorTop -= measuredHeight(anchorIndex - 1, w);
            anchorIndex--;
        }
        if (anchorIndex == 0 && anchorTop > 0) {
            anchorTop = 0;
        }
    }

    private void normalizeDown(int count, float w) {
        while (anchorIndex < count - 1) {
            float h = measuredHeight(anchorIndex, w);
            if (anchorTop + h <= 0) {
                anchorTop += h;
                anchorIndex++;
            } else {
                break;
            }
        }
    }

    /**
     * Places the rows from the anchor down, and returns the y the walk ended at.
     *
     * <p>{@code rowX} is the row origin the caller resolved for this pass. It is a parameter and
     * not a read of its own because the walk runs up to twice per layout, and because the
     * direction belongs to the pass rather than to the loop: the cursor is the {@code y}
     * arithmetic below, which is untouched.
     */
    private float placeDown(int count, float rowX, float w, float viewport) {
        float y = anchorTop;
        int i = anchorIndex;
        while (i < count && y < viewport) {
            float h = measuredHeight(i, w);
            cellFor(i).layoutBox(rowX, y, w, h);
            y += h;
            i++;
        }
        placedFrom = anchorIndex;
        placedTo = i;
        return y;
    }

    private float measuredHeight(int index, float w) {
        Widget cell = cellFor(index);
        if (cell == null) {
            cell = Objects.requireNonNull(adapter.rowAt(index), "adapter.rowAt returned null");
            mount(index, cell);
            cell.setVisible(true);
        }
        return cell.measure(new Constraints(w, w, 0, Constraints.UNBOUNDED_LIMIT)).height();
    }

    /**
     * Mounts {@code cell} as row {@code index}, into the position that keeps both the mounted run
     * and {@code children()} in data order. Rows arrive in neither order: {@link #normalizeUp}
     * walks upward from the anchor and realizes the rows above it in descending order, and a
     * container that appended them would leave a reader and the Tab key walking the list in the
     * order the scroll happened to realize it.
     */
    private void mount(int index, Widget cell) {
        int at = 0;
        while (at < mountedCount && mountedRows[at] < index) {
            at++;
        }
        // The bar is children() zero, added by the constructor before any row, and every cell goes
        // after it: the run below is the whole of the rest, in the same order.
        add(at + 1, cell);
        if (mountedCount == mountedRows.length) {
            mountedRows = Arrays.copyOf(mountedRows, mountedCount * 2);
            mountedCells = Arrays.copyOf(mountedCells, mountedCount * 2);
        }
        System.arraycopy(mountedRows, at, mountedRows, at + 1, mountedCount - at);
        System.arraycopy(mountedCells, at, mountedCells, at + 1, mountedCount - at);
        mountedRows[at] = index;
        mountedCells[at] = cell;
        mountedCount++;
    }

    /**
     * Recycles every mounted row outside {@code [from, toExclusive)}, keeping the rest in order —
     * except the one row that holds the keyboard focus, and the selected row while this list
     * itself does, which stay mounted while their index is still below {@code count}.
     *
     * <p>A scroll used to release that row with the others and move the focus up to the list,
     * and the keyboard user never noticed: the arrows move by selection, and the selected row is
     * always realized. A screen reader user did. A reader whose cursor follows the keyboard focus
     * onto a focusable row — VoiceOver's does — was standing on that row when a Page Down released
     * it, and with the node gone from the tree the reader fell back to "you are currently in a
     * window" (ADR 039 §13.29). So the row the keyboard is in is kept alive, in data order, with
     * its widget and its focus untouched; {@link #placeKeptOutside} puts it where it is, which is
     * outside the viewport. It is released by the first pass that finds it outside the run and no
     * longer holding the focus, or by any pass that releases everything.
     *
     * <p>The selected row is the same story one step up (decision 22, 2026-09-14; ADR 039 §1.10's
     * cursor amendment): while the list holds the keyboard the selected row is the reader's
     * cursor — the one node below the focused list published {@code ACTIVE} — and a wheel or a
     * bar drag that scrolled it away recycled it, so the cursor resolved to nothing until the
     * next arrow key. A row that is the cursor is kept exactly as a row holding the focus is,
     * and released by the first pass after the keyboard leaves the list.
     *
     * <p>{@code count} is the adapter's row count as the caller read it, and {@code 0} means
     * spare nothing: {@link #refresh} unmounts every cell because each is bound to a datum the
     * adapter may have replaced, and a row whose index the adapter no longer has is not a row.
     * The cursor row comes back on the next pass, through {@link #keepCursorRow}, bound afresh.
     *
     * @param from        the first row to keep
     * @param toExclusive one past the last row to keep
     * @param count       the row count a spared row's index must be below
     */
    private void recycleExcept(int from, int toExclusive, int count) {
        int kept = 0;
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            Widget cell = mountedCells[i];
            boolean inRun = row >= from && row < toExclusive;
            boolean hasFocus = !inRun && containsFocus(cell);
            boolean cursor = !inRun && row == selectedIndex && isFocused();
            if (inRun || ((hasFocus || cursor) && row < count)) {
                mountedRows[kept] = row;
                mountedCells[kept] = cell;
                kept++;
                continue;
            }
            remove(cell);
            adapter.recycle(cell);
            if (hasFocus) {
                requestFocus();
            }
        }
        for (int i = kept; i < mountedCount; i++) {
            mountedCells[i] = null; // the adapter owns it now; holding a reference would pin it
        }
        mountedCount = kept;
    }

    /**
     * Realizes the selected row when this list holds the keyboard and the pass left it
     * unrealized: after a {@link #refresh}, which releases everything, or on the first pass after
     * the list took the focus with its selection already scrolled away. {@link #recycleExcept}
     * keeps a cursor row that is mounted; this is what mounts one that is not, so the two
     * together are decision 22's "kept while focused, across refresh too".
     *
     * <p>Nothing is placed here: {@link #placeKeptOutside} runs next and puts every mounted row
     * outside the run where the scroll estimate says it is, this one included.
     *
     * @param count the adapter's row count as this pass read it
     * @param w     the row width this pass resolved
     */
    private void keepCursorRow(int count, float w) {
        if (selectedIndex < 0 || selectedIndex >= count || !isFocused()
                || isPlaced(selectedIndex) || cellFor(selectedIndex) != null) {
            return;
        }
        measuredHeight(selectedIndex, w); // mounts it, in data order
    }

    /**
     * Lays out every mounted row outside the placed run — the focused row a scroll spared, or
     * the cursor row kept while the list holds the keyboard — wholly outside the viewport, on the
     * side of the run its index lies, at the distance the scroll estimate puts it.
     *
     * <p>It has to be placed, not left: {@link #ensureVisible}'s far jump moves the anchor and
     * not the cells, so a spared row left at its last box could sit inside the viewport on top of
     * the row now bound there, be painted, and take the click. The estimate is the one every
     * scroll number is built from, so the row is where the thumb says it is. Above the run the
     * row's <b>bottom</b> edge is placed, at or above the anchor's top (which is at or above
     * zero once normalized); below it the row's top edge, at or below where the walk ended (which
     * is at or below the viewport's bottom whenever a row is left below it). Both put the whole
     * box outside {@code [0, height)}, whatever the row's own height, which is what keeps
     * {@link #paintChildren} from painting a feather of it, {@link #hitTest} from reaching it,
     * and {@code isShowing()} answering no.
     *
     * @param rowX   the row origin this pass resolved
     * @param w      the row width this pass resolved
     * @param bottom the y {@link #placeDown} ended at
     */
    private void placeKeptOutside(float rowX, float w, float bottom) {
        if (mountedCount == placedTo - placedFrom) {
            return; // the common frame: nothing is mounted outside the run
        }
        float avg = avgRowHeight(tokens());
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            if (row >= placedFrom && row < placedTo) {
                continue;
            }
            float h = measuredHeight(row, w);
            float y = row < placedFrom
                    ? anchorTop - (placedFrom - 1 - row) * avg - h
                    : bottom + (row - placedTo) * avg;
            mountedCells[i].layoutBox(rowX, y, w, h);
        }
    }

    /** Whether {@code index} is in the run the last layout placed, which is the viewport's. */
    private boolean isPlaced(int index) {
        return index >= placedFrom && index < placedTo;
    }

    /** The cell currently bound to a row, or {@code null} when that row is not realized. */
    private Widget cellFor(int index) {
        for (int i = 0; i < mountedCount; i++) {
            if (mountedRows[i] == index) {
                return mountedCells[i];
            }
        }
        return null;
    }

    /** The row a cell is currently bound to, or {@code -1} when it is not one of this list's. */
    private int indexOfCell(Widget cell) {
        for (int i = 0; i < mountedCount; i++) {
            if (mountedCells[i] == cell) {
                return mountedRows[i];
            }
        }
        return -1;
    }

    private void updateAverageHeight() {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < mountedCount; i++) {
            float cellHeight = mountedCells[i].height();
            if (cellHeight > 0) {
                sum += cellHeight;
                n++;
            }
        }
        if (n > 0) {
            measuredRowHeight = sum / n;
        }
    }

    /** Reveal awaiting the first layout (selection set before the list had a size). */
    private int pendingEnsureVisible = -1;

    private void ensureVisible(int index) {
        if (height() <= 0) {
            // Not laid out yet: dropping the reveal would open the list with
            // the selection off-screen; consume it after the first layout.
            pendingEnsureVisible = index;
            return;
        }
        // A spared focused row outside the run is mounted at an estimated box, and a scroll by
        // that estimate can stop short of it under uneven heights; it takes the far jump below,
        // which is exact, and the layout that follows finds its cell already mounted.
        Widget cell = isPlaced(index) ? cellFor(index) : null;
        if (cell != null) {
            float top = cell.y();
            float bottom = top + cell.height();
            if (top < 0) {
                anchorTop -= top;
            } else if (bottom > height()) {
                anchorTop -= bottom - height();
            } else {
                return;
            }
        } else {
            // Far away: jump so the row starts at the top; layout clamps the rest.
            anchorIndex = index;
            anchorTop = 0;
        }
        // Contained, for the reason scrollBy already gives: a reveal changes which rows are
        // mounted and where they sit, and both are inside a box this widget clips and whose own
        // size a reveal cannot move. Asking for a full layout here made every Page key, every
        // End, and every arrow that ran off the edge a whole-window repaint -- the keyboard walk
        // was paying what the wheel had already stopped paying.
        markNeedsContainedLayout();
    }

    /**
     * Damages one row's band, rather than the list, when what changed is that row's highlight.
     *
     * <p>ADR 043 &sect;9.2. An arrow key used to answer with {@code invalidate()} and repaint the
     * whole list &mdash; correct and out of all proportion, which is the failure mode partial
     * rendering has. Measured at the full widget for one keystroke; two bands after this.
     *
     * <p>Full width and no outset, because the highlight is a rounded rect drawn across the list
     * and <em>inset</em> inside the row's own box: it reaches nothing this rectangle does not
     * already hold. A row that is not mounted has nothing on screen to damage, and the scroll
     * that would bring it on screen damages the list on its own.
     *
     * @param index a row index, or any negative for no row
     */
    private void damageRow(int index) {
        if (index < 0) {
            return;
        }
        Widget cell = cellFor(index);
        if (cell == null) {
            return;
        }
        // Clamped to the list's own box, because nothing else will: damage is clipped by every
        // ANCESTOR that clips its children, and this widget is not its own ancestor. A row that a
        // reveal has just pushed out of the viewport still has its pre-layout box, and unclamped
        // that band lands on whatever sits below the list.
        float top = Math.max(0, cell.y());
        float bottom = Math.min(height(), cell.y() + cell.height());
        if (bottom > top) {
            invalidate(0, top, width(), bottom - top);
        }
    }

    private boolean containsFocus(Widget cell) {
        Widget focused = scene() != null ? scene().focusedWidget() : null;
        for (Widget w = focused; w != null; w = w.parent()) {
            if (w == cell) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- paint

    @Override
    protected boolean clipsChildren() {
        return true; // partial rendering: scrolled-out damage clamps to the viewport
    }

    @Override
    protected void paintChildren(Canvas canvas) {
        // Rows, clipped to the viewport; the scrollbar overlays on top after.
        // In a finally throughout: a row is built by the application's adapter, so the code
        // painting inside these clips is foreign, and a throw in it unwinds through here. A clip
        // left pushed ends the frame unbalanced and the warning names nobody.
        canvas.save();
        try {
            canvas.clipRect(0, 0, width(), height());
            for (Widget child : children()) {
                if (child == vBar) {
                    continue;
                }
                if (child.y() >= height() || child.y() + child.height() <= 0) {
                    // A row wholly outside the viewport: the focused row a scroll spared. Its
                    // own clip test would let it paint a feather's worth, and a focused button
                    // draws its ring outside its box, which is a ring on the row now at the edge.
                    continue;
                }
                canvas.save();
                try {
                    canvas.translate(child.x(), child.y());
                    child.paintWidget(canvas);
                } finally {
                    canvas.restore();
                }
            }
            if (selectedIndex >= 0) {
                Widget cell = cellFor(selectedIndex);
                if (cell != null) {
                    Theme theme = Theme.of(this);
                    SizeTokens t = theme.tokensFor(this);
                    float f = focusFade.value();
                    // The textbook locked case: the ring animates 1.5 -> 2 pt as focus fades in,
                    // so the weight is an interpolation of two locked weights and NOT a ternary
                    // (which would delete the animation). The inset is the resting weight and the
                    // shrink is twice it: half-stroke consequences, locked with it. Only the
                    // corner moves.
                    float inset = Strokes.FOCUS_RING_THIN;
                    canvas.drawRoundRect(inset, cell.y() + inset,
                            width() - 2 * inset, cell.height() - 2 * inset, t.radiusMedium(),
                            Strokes.FOCUS_RING_THIN
                                    + (Strokes.FOCUS_RING - Strokes.FOCUS_RING_THIN) * f,
                            theme.outline().lerp(theme.focusRing(), f));
                }
            }
        } finally {
            canvas.restore();
        }
        // Scrollbar on top of the rows (still within the list bounds).
        canvas.save();
        try {
            canvas.translate(vBar.x(), vBar.y());
            vBar.paintWidget(canvas);
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
        // The scrollbar overlays on top, so it wins the hit when shown.
        Widget barHit = vBar.hitTest(localX - vBar.x(), localY - vBar.y());
        if (barHit != null) {
            return barHit;
        }
        for (Widget child : children()) {
            if (child == vBar) {
                continue;
            }
            Widget hit = child.hitTest(localX - child.x(), localY - child.y());
            if (hit != null) {
                return hit;
            }
        }
        return this;
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void onMouseEvent(MouseEvent event) {
        switch (event.type()) {
            case WHEEL -> {
                // A detent is a device unit: the same flick travels the same distance in a
                // dense list and a roomy one, so the step is locked, not tabled.
                if (event.scrollY() != 0) {
                    SizeTokens t = tokens(); // one resolution: the test and the scroll must agree
                    float dy = -event.scrollY() * Strokes.WHEEL_STEP;
                    float offset = estimatedOffset(t);
                    float max = Math.max(0, estimatedContentHeight(t) - height());
                    // Consumed only where this list can still move that way (decision 44,
                    // 2026-09-14): at either end the detent is left for the scroller that holds
                    // the list, as a list whose content fits already left every detent. Without
                    // this a list inside a scroll pane was a wall the wheel could not get past.
                    if (dy < 0 ? offset > 0 : offset < max) {
                        scrollBy(dy);
                        event.consume();
                    }
                }
            }
            case MOVE, DRAG -> vBar.onHostActivity();
            case PRESS -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    int index = rowAtLocalY(sceneToLocalY(event.y()));
                    if (index >= 0) {
                        selectClamped(index);
                    }
                    requestFocus(Change.Origin.USER); // a click landed here
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    private int rowAtLocalY(float localY) {
        for (int i = 0; i < mountedCount; i++) {
            Widget cell = mountedCells[i];
            if (localY >= cell.y() && localY < cell.y() + cell.height()) {
                return mountedRows[i];
            }
        }
        return -1;
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        switch (event.key()) {
            case Keys.DOWN -> consumeAnd(event, () -> moveSelection(1));
            case Keys.UP -> consumeAnd(event, () -> moveSelection(-1));
            // The page size is resolved inside the branch that needs it: the other keys never
            // touch the token row, and one resolution per key press is one answer per press.
            case Keys.PAGE_DOWN -> consumeAnd(event, () -> moveSelection(rowsPerPage(tokens())));
            case Keys.PAGE_UP -> consumeAnd(event, () -> moveSelection(-rowsPerPage(tokens())));
            case Keys.HOME -> consumeAnd(event, () -> selectClamped(0));
            case Keys.END -> consumeAnd(event, () -> selectClamped(adapter.rowCount() - 1));
            case Keys.ENTER -> {
                if (selectedIndex >= 0) {
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

    private void moveSelection(int delta) {
        int count = adapter.rowCount();
        if (count == 0) {
            return;
        }
        if (selectedIndex < 0) {
            selectClamped(anchorIndex);
        } else {
            selectClamped(selectedIndex + delta);
        }
    }

    /** A page is a viewport of rows: a count derived from the current estimate, not a token. */
    private int rowsPerPage(SizeTokens t) {
        return Math.max(1, (int) (height() / Math.max(1, avgRowHeight(t))));
    }

    // -------------------------------------------------------- accessibility

    /**
     * The row count the rows below are numbered against, read once at the top of a publish.
     *
     * <p>A field and not a per-row call, because {@link Adapter#rowCount()} is application code:
     * one read per publish instead of one per realized row, and — since the walk always runs a
     * widget's own description before it walks that widget's children — the list's own facts and
     * every row's size of set come out of the same read. An adapter whose count moved between two
     * calls would otherwise publish rows numbered against two different sets in one tree.
     */
    private int describedRowCount;

    /**
     * One list node over the rows it has realized, carrying where the viewport sits and which row
     * the cursor is on.
     *
     * <p>Three facts decide the shape. The selection is <b>not</b> required: this class documents
     * no-selection as a genuine resting state — a fresh list is in it, {@link #clearSelection()}
     * reaches it, and {@link #refresh()} returns to it on an emptied adapter — unlike the combo,
     * which refuses an empty item list, and unlike the tabbed pane, which is always selected while
     * it has tabs. The active descendant is not declared here and cannot be: it is resolved in the
     * copy from the first node in this subtree published {@link Accessible.State#ACTIVE}, which is
     * the selected row. And the scroll facet is published unconditionally, as the scroll pane's
     * and the tab strip's are, so that resizing past the fitting point moves two booleans rather
     * than making a facet appear and disappear — but with <b>no half-point slop</b>, unlike the
     * tab strip's: this widget's wheel gate and {@link #scrollBy}'s clamp both use the bare
     * subtraction and will move a list that overflows by a third of a point, so a slop-gated
     * boolean would advertise a refusal the widget does not make.
     *
     * <p>The verb is on this node and not on a row, which is where the record's own survey was
     * wrong: the walk records a published node's owner as the widget it came from, and the scene
     * dispatches strictly to that owner, so a verb written onto a row would be sent to the
     * application's own cell widget and refused there. It is offered only while something is
     * selected, because {@link #activate()} on an empty selection is a no-op and a verb that can
     * only fail is worse than an absent one.
     *
     * <p>Nothing is formatted here. The one string this hook can hand over is an
     * {@link I18nString} the adapter holds, compared by reference, so a frame that damaged the
     * list and moved nothing costs no memory at all.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        describedRowCount = adapter.rowCount();
        // One resolution for the whole description, as every other pass in this class requires:
        // two inside one description would let the offset and the maximum disagree, and the
        // percent would then leave [0,1]. The viewport is height() and not
        // gutters.viewportHeight(height()), which is the same number here — nothing horizontal
        // scrolls, so the horizontal strip is always zero — and is the number scrollBy's own
        // clamp uses.
        SizeTokens t = tokens();
        float viewport = height();
        float content = estimatedContentHeight(t);
        float max = Math.max(0, content - viewport);

        a.role(Accessible.Role.LIST);
        // No name is derived: the widget holds no title, caption or placeholder, so the walk's
        // tooltip default and the application's own setters are the whole of it. No orientation
        // either: a list is read as vertical by default on all three platforms, its value has no
        // axis to run along, and the nearest widget in the toolkit — the combo's popup panel,
        // also a vertical LIST with a scroll facet — declares none.
        // The container half of the ROWS shape, written once (ADR 045 §3): a single selection
        // that genuinely rests with nothing selected, and the list's own PRESS while a row is
        // selected. FOCUS and SCROLL_INTO_VIEW arrive free from the walk, and the two scroll
        // verbs live on the bar's own node, which is the toolkit's settled shape for a scrolling
        // container.
        RowsAccessibility.describeContainer(a, RowsAccessibility.Selection.SINGLE, false,
                selectedIndex >= 0);
        a.scrollFrom(0, 0, 1, 0, estimatedOffset(t), max, viewport, content);
        if (selectedIndex >= 0 && cellFor(selectedIndex) == null) {
            // The selected row scrolled out of the viewport has no widget and therefore no
            // node, so the only place its name can be said is here. A description and not a
            // value text, which is written and then dropped without a value facet, and not a
            // synthetic phantom row, which is declared before the widget children and would
            // put a selection below the viewport ahead of every realized row. Set only while
            // that row is unrealized: a mounted one carries its own name and its own SELECTED,
            // and a second copy here is the same name spoken twice. Since decision 22 (the
            // cursor row is kept while the list holds the keyboard) this is reached only on
            // an UNFOCUSED list, where the row is genuinely gone and nothing else can name
            // it, so it duplicates nothing. The known cost is that while it stands, the
            // walk's tooltip-as-description default has nowhere to go on a list that has
            // both an application name and a tooltip.
            I18nString name = adapter.rowName(selectedIndex);
            if (name != null) {
                a.description(name);
            }
        }
    }

    /**
     * A mounted cell's identity is its data index (ADR 039 §1.3), answered before the cell
     * describes itself so that its name is carried over under that identity and whatever the
     * cell holds inside it follows the row when the cell is recycled.
     */
    @Override
    protected void onAccessibilityChildIdentity(Widget child, Accessibility a) {
        int index = child == vBar ? -1 : indexOfCell(child);
        if (index >= 0) {
            a.key(index);
        }
    }

    /**
     * What only the list knows about a mounted row: which row it is, that it is one, where it sits
     * in the data, and whether the cursor is on it.
     *
     * <p><b>The identity key is the line the whole recycling story rests on</b>, and it is
     * answered by {@link #onAccessibilityChildIdentity} before this hook runs. A cell is pooled
     * and rebound, so the widget object is the wrong key the moment it is reused: keyed by the
     * data index, a cell that carried row three and is recycled onto row nine is minted row nine's
     * identifier, and a cell coming back to row three gets row three's identifier back out of the
     * intern table. A screen reader holding row three therefore still holds row three.
     *
     * <p>The role and the name are conditional and the reason is the same for both: a cell is an
     * application's widget and may already have said what it is. A {@code Row}, a {@code Column}
     * or a padded box says nothing and takes {@code LIST_ITEM}, which is also what keeps it from
     * being deleted as scaffolding; a cell that is a button or a scroll pane keeps what it
     * declared, because eliding a scroll pane would take its scroll facet with it. The name is the
     * widening this step made to {@link Adapter#rowName}: rows commonly paint their own text and
     * declare nothing, and the role written here suppresses the paints-and-says-nothing warning
     * that would have been the application's only notice, so without asking the adapter the
     * ordinary case publishes thousands of nameless list items and nothing anywhere says so. An
     * application's own {@code setAccessibleName} still wins, because it is applied after this.
     *
     * <p>The position and the size of the set are the <b>model's</b> numbers and never the
     * published tree's: a list that realizes twenty of five thousand rows still tells a reader
     * which of five thousand it is on. And the selected row is marked active as well as selected,
     * because here the selection <em>is</em> the cursor — there is no second highlight, unlike the
     * combo's — and the keyboard stays on the list while it moves, which is exactly what an active
     * descendant is for: without the bit a reader can enumerate the rows and never learn which one
     * the user is on. <b>Only while this list holds the keyboard</b> (ADR 039 §1.10, amended
     * 2026-09-14; the settled active-state gate): the cursor is resolved from the focused node
     * down, so an unfocused list that marked its selected row active would hand the widget the
     * user is actually in a cursor it does not have — a list nested inside another list's row
     * cell would have handed the outer list its selected row as the outer list's own cursor,
     * which is the cost the earlier text of this paragraph accepted and this gate removes.
     *
     * <p>The verbs are <em>delegated</em> rather than written: a verb written onto a row would
     * be dispatched to the application's own cell widget, whose hook answers false, which is why
     * the record's survey was wrong to ask for a row verb and why §11 recorded per-row actuation
     * as absent. A delegated verb is published on the row and routed to
     * {@link #onAccessibilityChildAction} (ADR 039 §1.5, amended 2026-09-14), so a reader's
     * "select this row" lands on the row it addressed and the list performs it. The row verb set
     * of decision 20, read against this widget: {@code SELECT} always, because a list has no
     * selection mode and is never {@code NONE}; {@code SCROLL_INTO_VIEW} on a cell that cannot
     * take the keyboard, because on one that can the walk already grants it free and a second
     * performer is refused; never {@code ADD_TO_SELECTION} or {@code DESELECT}, because this
     * list selects one row and has no multi-select to add to; and never {@code FOCUS}
     * (decision 11), because the selection is the cursor here and a focus that selected would be
     * {@code SELECT} under another name. {@code PRESS} stays on the list — see
     * {@link #onAccessibility}.
     *
     * @param child the child being described, which is the bar or one mounted cell
     * @param a     the child's node
     */
    @Override
    protected void onAccessibilityChild(Widget child, Accessibility a) {
        if (child == vBar) {
            return; // the bar describes itself, and ignores itself when the content fits
        }
        int index = indexOfCell(child);
        if (index < 0) {
            return; // not one of this list's rows: whatever it is, it keeps its own verdict
        }
        if (!a.hasRole()) {
            a.role(Accessible.Role.LIST_ITEM);
        }
        if (!a.hasName()) {
            I18nString name = adapter.rowName(index);
            if (name != null) {
                a.name(name, Accessible.NameFrom.CONTENT);
            }
        }
        // The rest is the ROWS shape, written once (ADR 045 §3), read against this widget: the
        // membership over the model's numbers and never the published tree's; the cursor mark,
        // because here the selection is the cursor, and only while the list holds the keyboard;
        // SELECT always, because a list has no selection mode and is never NONE; never
        // ADD_TO_SELECTION or DESELECT, because one row is selected and there is no multi-select
        // to add to; never FOCUS (decision 11), because a focus that selected would be SELECT
        // under another name; never PRESS, which stays on the list (see onAccessibility); and
        // SCROLL_INTO_VIEW on a cell that cannot take the keyboard, by the widget's own flag and
        // not the walk's enabled-and-visible reading of it: a focusable cell that is disabled
        // today is granted the free verb the moment it is enabled, and a delegation standing on
        // it then would be the two-performer conflict the walk refuses loudly. Delegated rather
        // than written, so that a reader's "select this row" lands on the row it addressed and
        // the list performs it through onAccessibilityChildAction (ADR 039 §1.5).
        RowsAccessibility.describeRow(a, RowsAccessibility.Offer.DELEGATED,
                RowsAccessibility.Selection.SINGLE, index == selectedIndex, index + 1,
                describedRowCount, false, false, index == selectedIndex && isFocused(),
                false, false, !child.isFocusable());
    }

    /**
     * A verb the list claimed on a row's cell, performed by the rules of the ROWS shape
     * ({@link RowsAccessibility#performOnRow}, ADR 045 §3) over {@link RowsHost}: {@code SELECT}
     * makes that row the selection, as a click on it does, through the same {@code USER} seam and
     * with the same reveal; {@code SCROLL_INTO_VIEW} reveals the row where it is, as the walk's
     * free verb reveals a focusable one, and moves neither the selection nor the cursor
     * (decision 20).
     */
    @Override
    protected boolean onAccessibilityChildAction(Widget child, long key, Accessible.Action action,
                                                 Accessible.Argument arg) {
        int index = indexOfCell(child);
        if (index < 0) {
            return false;
        }
        return RowsAccessibility.performOnRow(rowsHost, index, action);
    }

    /**
     * The list's mechanisms as the rows shape drives them. The cursor is the selection here, so
     * {@code FOCUS} is refused before it could reach {@link #moveCursor} and rows have no
     * activation of their own: the list's {@code PRESS} opens the selected row.
     */
    private final class RowsHost implements RowsAccessibility.Host<Integer> {
        @Override
        public RowsAccessibility.Selection selection() {
            return RowsAccessibility.Selection.SINGLE;
        }

        @Override
        public boolean cursorIsTheSelection() {
            return true;
        }

        @Override
        public boolean rowsActivate() {
            return false;
        }

        @Override
        public boolean isSelected(Integer row) {
            return row == selectedIndex;
        }

        @Override
        public boolean select(Integer row, boolean moveCursor) {
            if (row == selectedIndex) {
                // A click on the row already selected lands on it where it is: the reveal
                // select() skips when nothing moves, which is what a reader's SELECT on the
                // kept cursor row scrolled out of the box asks for (decision 22), as the
                // tree's selectOnly already reveals an unchanged selection.
                ensureVisible(row);
                invalidate();
                return true;
            }
            ListView.this.select(row, true, Change.Origin.USER);
            return true;
        }

        @Override
        public void moveCursor(Integer row) {
            throw new UnsupportedOperationException("the cursor is the selection: FOCUS is refused");
        }

        @Override
        public void reveal(Integer row) {
            ensureVisible(row);
            invalidate();
        }
    }

    private final RowsHost rowsHost = new RowsHost();

    /**
     * Opens the selected row, through the same {@code USER} seam Enter reaches -- and not through
     * the public {@link #activate()}, which is a caller's verb and reaches no handler.
     *
     * <p>No enabled guard of its own: the node acted on here is this widget, so the scene's gate
     * has already walked this list and every ancestor for {@code isEnabled()}, checked that it is
     * showing, that the window is not modal-blocked and that it is inside the layer that owns
     * input. The seam re-checks the selection for itself, so the application is told exactly as
     * Enter tells it and nothing here is a second entry point.
     *
     * @param action what was asked
     * @param arg    unused; the one verb offered here is parameterless
     * @return whether this list did it
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (action != Accessible.Action.PRESS || selectedIndex < 0) {
            return false;
        }
        activate(Change.Origin.USER);
        return true;
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
        // The cursor row is kept only while the list holds the keyboard, so the keyboard arriving
        // and leaving are the two moments a pass has to run: to realize a selection already
        // scrolled away, and to release one. Contained, for scrollBy's reason: what moves is
        // which rows are mounted, inside a box this widget clips and whose size a focus change
        // cannot move.
        markNeedsContainedLayout();
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
        markNeedsContainedLayout();
    }
}

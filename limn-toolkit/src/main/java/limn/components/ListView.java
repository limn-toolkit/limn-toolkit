package limn.components;

import limn.animation.Transition;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <p><b>Size steps propagate rather than being imposed.</b> Rows are adapter-supplied
 * widgets in this list's subtree, so they resolve the {@link limn.scene.ControlSize}
 * themselves and {@code list.setControlSize(SMALL)} shortens them because <em>they</em>
 * re-measure. Only three metrics are the list's own: the frame-0 row-height seed used
 * before anything has been measured, the intrinsic width under an unbounded constraint,
 * and the selection ring's corner radius.
 *
 * <p><b>The scroll bar does not take part in the size axis</b> ({@link ScrollBar#thickness()}
 * is 15 pt at every step), and it overlays the rows rather than insetting them, so at a
 * compact step it covers a larger fraction of a shorter row. An accepted cost of one
 * scrollbar geometry process-wide.
 */
public class ListView extends Widget implements Scrollable {

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
    }

    /**
     * Rows of intrinsic height when the height axis is unbounded. A row <b>count</b>, not a
     * length: it multiplies whatever a row currently measures, so it must not move with the
     * step.
     */
    private static final int VISIBLE_ROWS_HINT = 6;

    private final Adapter adapter;
    private final ScrollBar vBar;
    private final ScrollGutters gutters = new ScrollGutters();
    private final Map<Integer, Widget> mounted = new HashMap<>(); // index -> mounted child
    private final Set<Integer> keepScratch = new HashSet<>();

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
    private IntConsumer onSelect = index -> { };
    private IntConsumer onActivate = index -> { };
    /**
     * Fades the selected-row highlight between the resting outline and the focus ring.
     * The {@link Theme} reads here are <b>animation durations</b>, which are palette- and
     * step-independent; a size step read this way would be captured before this widget has a
     * parent and could never be corrected. Do not copy the pattern for metrics.
     */
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);

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
     * records usually wants: the right edge of a row is where a count, a date or a
     * status chip goes, and a thumb over it is a defect.
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
     * Called with the row index when the selection moves, by click or keyboard.
     *
     * @throws NullPointerException if {@code handler} is null, as everywhere else in this set
     */
    public ListView onSelect(IntConsumer handler) {
        Ui.checkUiThread();
        this.onSelect = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Called with the row index on Enter or a double activation, the "open this" gesture.
     *
     * @throws NullPointerException if {@code handler} is null
     */
    public ListView onActivate(IntConsumer handler) {
        Ui.checkUiThread();
        this.onActivate = Objects.requireNonNull(handler, "handler");
        return this;
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
     * Re-reads the adapter and re-lays out (call after the data changes). If the adapter shrank
     * past the selected row the selection moves to the last row (or is dropped when the list is
     * now empty) and {@link #onSelect} is told, because a listener showing the selected record
     * would otherwise still be showing a deleted one. UI thread only.
     */
    public void refresh() {
        Ui.checkUiThread();
        int count = adapter.rowCount();
        anchorIndex = Math.max(0, Math.min(anchorIndex, Math.max(0, count - 1)));
        // Unmount every row: a mounted cell is bound to the OLD datum at its
        // index and layout reuses mounted cells without consulting the adapter,
        // so without this the visible viewport is exactly what never refreshes.
        recycleExcept(java.util.Set.of());
        markNeedsLayout();
        invalidate();
        if (selectedIndex >= count) {
            // Last, and without a reveal: the listener runs on a list whose rows are already
            // unmounted, and revealing here would jump the anchor the clamp above just settled.
            select(count == 0 ? -1 : count - 1, false);
        }
    }

    /**
     * Selects a row, scrolls it into view and fires {@link #onSelect}; code and a click take the
     * same path, so a listener sees every change either way. Selecting the row that is already
     * selected changes nothing, reveals nothing and fires nothing; that early return is what keeps
     * two controls bound to each other from recursing, so do not remove it. UI thread only.
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
        select(index, true);
        return this;
    }

    /**
     * Drops the selection: {@link #selectedIndex()} becomes {@code -1} and {@link #onSelect} is
     * fired with it. No-op when nothing is selected. UI thread only.
     */
    public ListView clearSelection() {
        Ui.checkUiThread();
        select(-1, false);
        return this;
    }

    /**
     * The one place the selection moves. {@code index} is already valid or {@code -1}; the early
     * return is the recursion guard the public setter's contract rests on.
     */
    private void select(int index, boolean reveal) {
        if (index == selectedIndex) {
            return;
        }
        selectedIndex = index;
        if (reveal && selectedIndex >= 0) {
            ensureVisible(selectedIndex);
        }
        invalidate();
        onSelect.accept(selectedIndex);
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
        select(Math.min(Math.max(0, index), count - 1), true);
    }

    /** Fires {@link #onActivate} for the selected row, as Enter does. */
    public void activate() {
        Ui.checkUiThread();
        if (selectedIndex >= 0) {
            onActivate.accept(selectedIndex);
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
        for (Widget cell : mounted.values()) {
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
        return Theme.current().tokensFor(this);
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
        markNeedsLayout();
        invalidate();
        vBar.onScrolled();
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = tokens();
        // Both are free-axis fallbacks a real parent overrides; they only bind when the list is
        // measured unbounded, which is also the only time the row-height estimate is a seed.
        float w = constraints.hasBoundedWidth() ? constraints.maxWidth() : t.listWidth();
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight()
                : VISIBLE_ROWS_HINT * avgRowHeight(t);
        return constraints.constrain(w, h);
    }

    @Override
    protected void onLayout() {
        float box = width();
        float h = height();
        if (box <= 0 || h <= 0) {
            return;
        }
        // Settle the strip first: every row below is measured and placed into what
        // it leaves, so a reserved bar narrows the rows instead of covering them.
        // The estimate is what the bar itself reports, and it does not move with
        // the width, so the second pass finds the same answer and stops.
        gutters.resolve(box, h, vBar, null,
                (viewW, viewH) -> new Size(viewW, estimatedContentHeight(tokens())));
        float w = gutters.viewportWidth(box);
        vBar.measure(Constraints.tight(ScrollBar.thickness(), h));
        vBar.layoutBox(box - ScrollBar.thickness(), 0, ScrollBar.thickness(), h);

        int count = adapter.rowCount();
        Set<Integer> keep = keepScratch;
        if (count == 0) {
            recycleExcept(Set.of());
            anchorIndex = 0;
            anchorTop = 0;
            return;
        }
        anchorIndex = Math.min(anchorIndex, count - 1);

        normalizeUp(w);
        normalizeDown(count, w);
        float bottom = placeDown(count, w, h, keep);
        // Over-scrolled past the end: close the gap at the bottom, unless the
        // content is shorter than the viewport (then it stays top-aligned).
        if (bottom < h && !(anchorIndex == 0 && anchorTop >= 0)) {
            anchorTop += h - bottom;
            normalizeUp(w);
            normalizeDown(count, w);
            placeDown(count, w, h, keep);
        }
        recycleExcept(keep);
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

    private float placeDown(int count, float w, float viewport, Set<Integer> keep) {
        keep.clear();
        float y = anchorTop;
        int i = anchorIndex;
        while (i < count && y < viewport) {
            float h = measuredHeight(i, w);
            mounted.get(i).layoutBox(0, y, w, h);
            keep.add(i);
            y += h;
            i++;
        }
        return y;
    }

    private float measuredHeight(int index, float w) {
        Widget cell = mounted.get(index);
        if (cell == null) {
            cell = Objects.requireNonNull(adapter.rowAt(index), "adapter.rowAt returned null");
            add(cell);
            cell.setVisible(true);
            mounted.put(index, cell);
        }
        return cell.measure(new Constraints(w, w, 0, Constraints.UNBOUNDED_LIMIT)).height();
    }

    private void recycleExcept(Set<Integer> keep) {
        Iterator<Map.Entry<Integer, Widget>> it = mounted.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Widget> entry = it.next();
            if (!keep.contains(entry.getKey())) {
                Widget cell = entry.getValue();
                boolean hadFocus = containsFocus(cell);
                remove(cell);
                adapter.recycle(cell);
                it.remove();
                if (hadFocus) {
                    requestFocus();
                }
            }
        }
    }

    private void updateAverageHeight() {
        float sum = 0;
        int n = 0;
        for (Widget cell : mounted.values()) {
            if (cell != vBar && cell.height() > 0) {
                sum += cell.height();
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
        Widget cell = mounted.get(index);
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
        markNeedsLayout();
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
                canvas.save();
                try {
                    canvas.translate(child.x(), child.y());
                    child.paintWidget(canvas);
                } finally {
                    canvas.restore();
                }
            }
            if (selectedIndex >= 0) {
                Widget cell = mounted.get(selectedIndex);
                if (cell != null) {
                    Theme theme = Theme.current();
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
                            theme.outline.lerp(theme.focusRing, f));
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
                if (event.scrollY() != 0 && estimatedContentHeight(tokens()) > height()) {
                    scrollBy(-event.scrollY() * Strokes.WHEEL_STEP);
                    event.consume();
                }
            }
            case MOVE, DRAG -> vBar.onHostActivity();
            case PRESS -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    int index = rowAtLocalY(sceneToLocalY(event.y()));
                    if (index >= 0) {
                        selectClamped(index);
                    }
                    requestFocus();
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    private int rowAtLocalY(float localY) {
        for (Map.Entry<Integer, Widget> entry : mounted.entrySet()) {
            Widget cell = entry.getValue();
            if (cell != vBar && localY >= cell.y() && localY < cell.y() + cell.height()) {
                return entry.getKey();
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

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
    }
}

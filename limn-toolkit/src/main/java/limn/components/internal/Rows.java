package limn.components.internal;

import limn.scene.Change;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * A scrolling run of rows of uneven height, of which only the ones in sight are realized: what
 * the list, the table and the tree each kept a copy of. It holds where the run is scrolled to (a
 * row and how far above the box's top that row begins), which rows are realized and what each is
 * realized as, and the mean height the scroll bar's numbers are built from; and it does the
 * walk: placing rows from the anchor to the foot of the box, closing the gap a short end leaves,
 * letting go of the rows that left, keeping the one that holds the keyboard, settling a reveal.
 *
 * <p>What a row is, how tall, and where its box goes on the other axis are the widget's, behind
 * {@link Host}. So is the selection: the walk needs to know one thing of it, which row the
 * keyboard is on.
 *
 * <p><b>UI thread only</b>, as the widgets that hold one are. Not API: see the package.
 *
 * @param <E> what a widget realizes a row as: a cell, or a record of several
 */
public final class Rows<E> {

    /**
     * How far a scroll goes, in viewports, before it lands by the estimate instead of walking:
     * past it the next pass would measure every row on the way, seconds for a hundred thousand.
     */
    private static final float JUMP_VIEWPORTS = 2;

    /**
     * What a widget answers about its own rows.
     *
     * @param <E> what it realizes a row as
     */
    public interface Host<E> {

        /** @return how many rows there are now */
        int count();

        /**
         * Realizes a row: builds or rebinds what shows it and makes it a child where the order of
         * the realized rows says.
         *
         * @param index    the row
         * @param position how many realized rows come before it
         * @return what it is realized as
         */
        E mount(int index, int position);

        /**
         * Lets go of a row no longer needed; the widget pools or drops it as it likes.
         *
         * @param row       what it was realized as
         * @param heldFocus whether the keyboard was inside it, which the widget then takes back
         */
        void unmount(E row, boolean heldFocus);

        /**
         * @param row   a realized row
         * @param index its index
         * @return its height at the width the widget lays it out at
         */
        float measure(E row, int index);

        /**
         * Puts a row at {@code y} in the rows' own box, at the height measured; where it goes on
         * the other axis is the widget's.
         *
         * @param row    a realized row
         * @param index  its index
         * @param y      its top, from the top of the rows' box; outside it for a row kept out of sight
         * @param height its height
         */
        void place(E row, int index, float y, float height);

        /**
         * Moves a realized row by {@code dy} at once, without laying it out again: a scroll moves
         * what is on screen now and leaves the walk to the next pass.
         *
         * @param row a realized row
         * @param dy  how far down
         */
        void shift(E row, float dy);

        /**
         * @param row a realized row
         * @return whether a control inside it holds the keyboard
         */
        boolean holdsFocus(E row);

        /** @return the row the keyboard is on while the widget itself holds it, or -1 */
        int cursorIndex();

        /** @return the height of the rows' own box, strips and bands taken out */
        float viewportHeight();

        /** @return a row's height before any has been measured */
        float seedHeight();

        /**
         * The run moved: the widget asks for its pass and its repaint, tells its bar, and tells
         * whoever watches it.
         *
         * @param origin who moved it
         */
        void scrolled(Change.Origin origin);
    }

    private final Host<E> host;
    private int[] rows = new int[16];
    private Object[] mounted = new Object[16];
    private float[] heights = new float[16];
    private int mountedCount;
    /** Rows whose index is gone since the last pass, let go of at the next one. */
    private final List<E> orphans = new ArrayList<>();

    private int anchorIndex;
    private float anchorTop;
    private int placedFrom;
    private int placedTo;
    private float bottom;
    private float mean;
    private @Nullable IntSupplier revealPending;
    private float overscan;

    /**
     * @param host the widget's answers
     */
    public Rows(Host<E> host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    // ------------------------------------------------------------------ the pass

    /**
     * Walks the rows: from the anchor to the foot of the box, after settling a reveal that was
     * asked for; lets go of the rows that left, but for the one that holds the keyboard; and
     * takes the mean height of the run it placed. Called from the widget's own layout, once the
     * rows' box is known.
     */
    public void layout() {
        releaseOrphans(); // inside the pass, where the scene absorbs the removal
        int count = host.count();
        if (count == 0) {
            recycleExcept(0, 0, 0);
            anchorIndex = 0;
            anchorTop = 0;
            placedFrom = 0;
            placedTo = 0;
            bottom = 0;
            revealPending = null;
            return;
        }
        float viewH = host.viewportHeight();
        anchorIndex = Math.min(anchorIndex, count - 1);
        normalizeUp();
        normalizeDown(count);
        if (revealPending != null) {
            settleReveal(count, viewH);
        }
        bottom = placeDown(count, viewH);
        if (bottom < viewH && !(anchorIndex == 0 && anchorTop >= 0)) {
            // Scrolled past the end: close the gap at the foot, unless the rows are shorter than
            // the box, which then stay at its top.
            anchorTop += viewH - bottom;
            normalizeUp();
            normalizeDown(count);
            bottom = placeDown(count, viewH);
        }
        recycleExcept(placedFrom, placedTo, count);
        keepCursorRow(count);
        placeKeptOutside(viewH);
        updateMean();
    }

    private void normalizeUp() {
        while (anchorTop > 0 && anchorIndex > 0) {
            anchorTop -= height(anchorIndex - 1);
            anchorIndex--;
        }
        if (anchorIndex == 0 && anchorTop > 0) {
            anchorTop = 0;
        }
    }

    private void normalizeDown(int count) {
        while (anchorIndex < count - 1) {
            float h = height(anchorIndex);
            if (anchorTop + h <= 0) {
                anchorTop += h;
                anchorIndex++;
            } else {
                break;
            }
        }
    }

    /**
     * Brings the row a reveal was asked for into the box by the least scroll, from measured
     * heights: a row above the box, or cut by its top, goes with its top on the box's top; one
     * below it, or cut by its foot, with its foot on the box's foot, or its top on the top when
     * it is taller than the box. The anchor is set to the row itself, so the distance is exact
     * whatever the rows between measure. A row no longer there is forgotten, and one already in
     * the box moves nothing.
     */
    private void settleReveal(int count, float viewH) {
        IntSupplier asked = Objects.requireNonNull(revealPending);
        revealPending = null;
        int index = asked.getAsInt();
        if (index < 0 || index >= count) {
            return;
        }
        if (index < anchorIndex || (index == anchorIndex && anchorTop < 0)) {
            anchorIndex = index;
            anchorTop = 0;
            return;
        }
        float top = anchorTop;
        for (int i = anchorIndex; i < index && top < viewH; i++) {
            top += height(i);
        }
        float rowH = height(index);
        if (top < viewH && top + rowH <= viewH) {
            return;
        }
        anchorIndex = index;
        anchorTop = Math.max(0, viewH - rowH);
        normalizeUp();
    }

    private float placeDown(int count, float viewH) {
        float y = anchorTop;
        int i = anchorIndex;
        while (i < count && y < viewH + overscan) {
            float h = height(i);
            host.place(at(indexOfRow(i)), i, y, h);
            y += h;
            i++;
        }
        placedFrom = anchorIndex;
        placedTo = i;
        return y;
    }

    /**
     * Lets go of every realized row outside {@code [from, to)}, sparing the one a control of
     * which holds the keyboard, and the cursor's while the widget holds it: a reader whose cursor
     * follows the focus loses its place when the row it stands on is taken away. Both only while
     * the row still exists.
     */
    private void recycleExcept(int from, int to, int count) {
        int cursor = host.cursorIndex();
        int kept = 0;
        for (int i = 0; i < mountedCount; i++) {
            int row = rows[i];
            E e = at(i);
            boolean inRun = row >= from && row < to;
            boolean hasFocus = !inRun && host.holdsFocus(e);
            if (inRun || (row < count && (hasFocus || row == cursor))) {
                rows[kept] = row;
                mounted[kept] = e;
                heights[kept] = heights[i];
                kept++;
                continue;
            }
            host.unmount(e, hasFocus);
        }
        Arrays.fill(mounted, kept, mountedCount, null);
        mountedCount = kept;
    }

    /** Realizes the cursor's row when the pass left it out: a refresh lets go of every row. */
    private void keepCursorRow(int count) {
        int cursor = host.cursorIndex();
        if (cursor >= 0 && cursor < count && indexOfRow(cursor) < 0) {
            height(cursor);
        }
    }

    /**
     * Puts a row kept outside the placed run wholly outside the box, against the edge it lies
     * beyond and at the height it measures: it is then not painted, not hit and not showing,
     * whatever its height.
     */
    private void placeKeptOutside(float viewH) {
        if (mountedCount == placedTo - placedFrom) {
            return; // the common pass: nothing is realized outside the run
        }
        for (int i = 0; i < mountedCount; i++) {
            int row = rows[i];
            if (row >= placedFrom && row < placedTo) {
                continue;
            }
            float h = height(row);
            int at = indexOfRow(row);
            float y = row < placedFrom ? Math.min(anchorTop, 0) - h : Math.max(bottom, viewH);
            host.place(at(at), row, y, h);
        }
    }

    /**
     * The mean height of the placed run alone. A row kept outside it, of another height, skewed
     * the estimate the scroll is clamped by: at the real end it sat short of its maximum or past
     * it, and the wheel was walled in or stopped short of the last row.
     */
    private void updateMean() {
        float total = 0;
        int counted = 0;
        for (int i = 0; i < mountedCount; i++) {
            if (rows[i] >= placedFrom && rows[i] < placedTo) {
                total += heights[i];
                counted++;
            }
        }
        if (counted > 0) {
            mean = total / counted;
        }
    }

    // ------------------------------------------------------------- realized rows

    /** The height of a row, realizing it if it is not. */
    private float height(int index) {
        int at = indexOfRow(index);
        if (at < 0) {
            at = -at - 1;
            E e = Objects.requireNonNull(host.mount(index, at), "Host.mount returned null");
            if (mountedCount == rows.length) {
                rows = Arrays.copyOf(rows, mountedCount * 2);
                mounted = Arrays.copyOf(mounted, mountedCount * 2);
                heights = Arrays.copyOf(heights, mountedCount * 2);
            }
            System.arraycopy(rows, at, rows, at + 1, mountedCount - at);
            System.arraycopy(mounted, at, mounted, at + 1, mountedCount - at);
            System.arraycopy(heights, at, heights, at + 1, mountedCount - at);
            rows[at] = index;
            mounted[at] = e;
            mountedCount++;
        }
        float h = host.measure(at(at), index);
        heights[at] = h;
        return h;
    }

    /** Where a row is among the realized ones, or {@code -(insertion point) - 1}. */
    private int indexOfRow(int index) {
        return Arrays.binarySearch(rows, 0, mountedCount, index);
    }

    @SuppressWarnings("unchecked")
    private E at(int position) {
        return (E) mounted[position];
    }

    private void releaseOrphans() {
        for (E e : orphans) {
            host.unmount(e, host.holdsFocus(e));
        }
        orphans.clear();
    }

    /** @return how many rows are realized */
    public int mountedCount() {
        return mountedCount;
    }

    /**
     * @param position among the realized rows, in row order
     * @return the row's index
     */
    public int rowAt(int position) {
        Objects.checkIndex(position, mountedCount);
        return rows[position];
    }

    /**
     * @param position among the realized rows, in row order
     * @return what the row is realized as
     */
    public E mountedAt(int position) {
        Objects.checkIndex(position, mountedCount);
        return at(position);
    }

    /**
     * @param position among the realized rows, in row order
     * @return the height the row last measured
     */
    public float heightAt(int position) {
        Objects.checkIndex(position, mountedCount);
        return heights[position];
    }

    /**
     * @param index a row
     * @return what it is realized as, or null when it is not
     */
    public @Nullable E mounted(int index) {
        int at = indexOfRow(index);
        return at < 0 ? null : at(at);
    }

    /**
     * Realizes a row outside a pass and measures it, for a widget that needs its box before the
     * pass that would place it.
     *
     * @param index a row
     * @return its height
     */
    public float realize(int index) {
        Objects.checkIndex(index, host.count());
        return height(index);
    }

    // ------------------------------------------------------------------ where things are

    /** @return the first row in the box, or cut by its top */
    public int anchorIndex() {
        return anchorIndex;
    }

    /** @return where the anchor row's top is, from the top of the box: zero or above it */
    public float anchorTop() {
        return anchorTop;
    }

    /** @return the first row the last pass placed */
    public int placedFrom() {
        return placedFrom;
    }

    /** @return one past the last row the last pass placed */
    public int placedTo() {
        return placedTo;
    }

    /** @return where the last placed row ended, from the top of the box */
    public float placedBottom() {
        return bottom;
    }

    /**
     * @param index a row
     * @return whether the last pass placed it in the run from the anchor to the foot of the box
     */
    public boolean isPlaced(int index) {
        return index >= placedFrom && index < placedTo;
    }

    /**
     * @param index a row
     * @return its top from the top of the box, by the heights last measured; NaN for a row the
     *         last pass did not place, a kept one included
     */
    public float topOf(int index) {
        if (!isPlaced(index)) {
            return Float.NaN;
        }
        int from = indexOfRow(placedFrom);
        int to = indexOfRow(index);
        if (from < 0 || to < 0) {
            return Float.NaN;
        }
        float y = anchorTop;
        for (int i = from; i < to; i++) {
            y += heights[i];
        }
        return y;
    }

    /**
     * Has the walk place rows this far past the foot of the box: a drag that lifted rows out lets
     * the ones below close up, and those that come into sight have to be there to slide in.
     *
     * @param extra how far past the foot, zero for none
     */
    public void setOverscan(float extra) {
        overscan = Math.max(0, extra);
    }

    // ------------------------------------------------------------ what the bar is told

    /** @return a row's height as far as is known: the mean of the last placed run, or the seed */
    public float meanHeight() {
        return mean > 0 ? mean : host.seedHeight();
    }

    /** @return how tall all the rows are, by the mean */
    public float contentHeight() {
        return host.count() * meanHeight();
    }

    /** @return how far the rows are scrolled, by the mean, between zero and the most they can be */
    public float offset() {
        return Math.max(0, Math.min(anchorIndex * meanHeight() - anchorTop, maxOffset()));
    }

    private float maxOffset() {
        return Math.max(0, contentHeight() - host.viewportHeight());
    }

    /**
     * @param dy a direction: negative toward the start
     * @return whether a scroll that way would move anything
     */
    public boolean canScrollBy(float dy) {
        float offset = offset();
        return dy < 0 ? offset > 0 : offset < maxOffset();
    }

    // ------------------------------------------------------------------ moving

    /**
     * Scrolls by {@code dy}, as far as the ends allow. The realized rows move at once; the walk
     * is left to the next pass. A scroll of more than two boxes lands by the estimate, as a drag
     * of the bar does. A reveal asked for and not yet settled is dropped: the scroll moves from
     * where things are.
     *
     * @param dy     how far toward the end
     * @param origin who scrolls
     * @return whether anything moved
     */
    public boolean scrollBy(float dy, Change.Origin origin) {
        float offset = offset();
        float applied = Math.min(Math.max(0, offset + dy), maxOffset()) - offset;
        if (applied == 0) {
            return false;
        }
        if (Math.abs(applied) > JUMP_VIEWPORTS * host.viewportHeight()) {
            scrollTo(offset + applied, origin);
            return true;
        }
        revealPending = null;
        anchorTop -= applied;
        for (int i = 0; i < mountedCount; i++) {
            host.shift(at(i), -applied);
        }
        host.scrolled(origin);
        return true;
    }

    /**
     * Jumps to the row the estimate puts at {@code offset}, building no row on the way: the bar's
     * thumb dragged, or a scroll too far to walk.
     *
     * @param offset how far from the start, by the mean
     * @param origin who scrolls
     */
    public void scrollTo(float offset, Change.Origin origin) {
        revealPending = null;
        float avg = meanHeight();
        float clamped = Math.max(0, Math.min(offset, maxOffset()));
        int index = avg > 0 ? (int) (clamped / avg) : 0;
        int before = anchorIndex;
        float beforeTop = anchorTop;
        anchorIndex = Math.max(0, Math.min(index, Math.max(0, host.count() - 1)));
        anchorTop = anchorIndex * avg - clamped;
        if (anchorIndex != before || anchorTop != beforeTop) {
            host.scrolled(origin);
        }
    }

    /**
     * Asks that a row be in sight after the next pass, which brings it in by the least scroll
     * from the edge it lies beyond. The later of two asked before a pass is the one that lands.
     *
     * @param index answers the row when the pass comes, since rows may be renumbered before it;
     *              a negative answer is a row no longer there
     */
    public void reveal(IntSupplier index) {
        revealPending = Objects.requireNonNull(index, "index");
    }

    /** @return whether a reveal is waiting for the next pass */
    public boolean isRevealPending() {
        return revealPending != null;
    }

    /** Forgets a reveal that was asked for and not yet settled. */
    public void cancelReveal() {
        revealPending = null;
    }

    /**
     * Moves the view as far as the cursor went, so that the cursor keeps its place in the box:
     * what Page Up and Page Down do. Only when the row it left was wholly in sight; from a row
     * out of sight there is no place to keep, and the reveal the widget asked for stands.
     *
     * @param from the row the cursor left
     * @param to   the row it went to
     * @return whether the view was moved
     */
    public boolean pageWith(int from, int to) {
        int count = host.count();
        float top = topOf(from);
        int at = indexOfRow(from);
        if (to == from || count == 0 || Float.isNaN(top) || at < 0 || top < 0
                || top + heights[at] > host.viewportHeight()) {
            return false;
        }
        int anchor = anchorIndex + (to - from);
        if (anchor <= 0) {
            anchorIndex = 0;
            anchorTop = 0;
        } else {
            anchorIndex = Math.min(anchor, count - 1); // the pass closes the gap at the last row
        }
        revealPending = null;
        return true;
    }

    // -------------------------------------------------------- the data changed under it

    /** New rows altogether: every realized row is let go of, and the run is at its start. */
    public void reset() {
        refresh(null, -1);
        anchorIndex = 0;
        anchorTop = 0;
        revealPending = null; // a reveal still waiting was asked of the old rows
    }

    /**
     * The rows changed in place: every realized row is let go of, each being bound to what may no
     * longer be there, but for one the widget carries over to the index its row now has.
     *
     * @param keep      a realized row to carry over, or null for none
     * @param keepIndex the index its row has now
     */
    public void refresh(@Nullable E keep, int keepIndex) {
        releaseOrphans();
        int keptHeight = -1;
        for (int i = 0; i < mountedCount; i++) {
            E e = at(i);
            if (e == keep) {
                keptHeight = i;
                continue;
            }
            host.unmount(e, host.holdsFocus(e));
        }
        float h = keptHeight < 0 ? 0 : heights[keptHeight];
        Arrays.fill(mounted, 0, mountedCount, null);
        mountedCount = 0;
        if (keep != null && keptHeight >= 0 && keepIndex >= 0) {
            rows[0] = keepIndex;
            mounted[0] = keep;
            heights[0] = h;
            mountedCount = 1;
        }
        anchorIndex = Math.max(0, Math.min(anchorIndex, Math.max(0, host.count() - 1)));
    }

    /**
     * Rows were put in or taken out among the others: each realized row is asked the index its
     * row has now, and follows it. One whose row is gone, or that would no longer come after the
     * realized row before it, is set aside and let go of at the next pass, where the scene takes
     * the removal in.
     *
     * @param newIndex answers a realized row's index now, or a negative for a row that is gone
     */
    public void renumber(ToIntFunction<? super E> newIndex) {
        int kept = 0;
        int last = -1;
        for (int i = 0; i < mountedCount; i++) {
            E e = at(i);
            int index = newIndex.applyAsInt(e);
            if (index < 0 || index <= last) {
                orphans.add(e);
                continue;
            }
            rows[kept] = index;
            mounted[kept] = e;
            heights[kept] = heights[i];
            kept++;
            last = index;
        }
        Arrays.fill(mounted, kept, mountedCount, null);
        mountedCount = kept;
    }

    /** @return the rows set aside by {@link #renumber}, until the next pass lets go of them */
    public List<E> orphans() {
        return java.util.Collections.unmodifiableList(orphans);
    }
}

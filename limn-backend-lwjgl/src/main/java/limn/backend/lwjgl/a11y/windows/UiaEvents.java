package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.AccessibleEvent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the user-interface thread has handed the Windows bridge and the drain thread has not yet
 * raised, and the one policy that decides what may wait.
 *
 * <p>This is the queue ADR&nbsp;039 &sect;1.10 asks of every bridge and the Windows bridge went
 * without until 2026-09-07, on the belief that {@code UiaRaise*} returns without waiting for a
 * client. It does not: with NVDA attached a property-changed raise returned after the reader's
 * handler had run and had called back into the provider &mdash; 2.5&nbsp;ms median, one of
 * 50&nbsp;ms &mdash; on the thread that was supposed to be drawing the next frame (&sect;13.28).
 * So the user-interface thread <b>offers</b> here and never blocks, and a thread of the bridge's
 * own <b>takes</b> and raises, which is what &sect;3.4's table said the Windows bridge does.
 *
 * <p><b>One producer, one consumer, and a collapse rather than a wait or a silent drop.</b> The
 * queue is bounded; on overflow it is emptied and a single marker is left in it, which the drain
 * thread reads as "sweep the registry against the published tree and tell the client to re-read
 * the window" (&sect;1.10's collapse). Everything offered while the marker is still waiting is
 * dropped, because it is already covered by it. The overflow is logged once per collapse as a
 * WARNING, which is the signal &sect;13.19 names for a capacity set wrong.
 *
 * <p><b>The frame's end is never dropped without a collapse taking its place</b>
 * ({@link #endFrame}). It is not an event and covers none: it is the publish boundary the drain
 * thread flushes an owed re-announcement at (&sect;2.4, semantics 4), and a boundary a collapse
 * could swallow <em>silently</em> would leave that debt with nothing to flush it on a window whose
 * scene then goes still. So the marker ignores the collapsed flag, and when there is no room for it
 * the queue collapses first &mdash; which is the honest answer for a queue that is over capacity
 * anyway &mdash; and the marker goes in behind the collapse's own.
 *
 * <p><b>What a collapse does take is a marker already waiting</b>, because {@link #collapse} clears
 * the queue, and that is not a debt left unpaid (the precise claim, 2026-09-16): a collapse can
 * only happen while a frame is handing events over, and the bridge marks every frame in which an
 * offer was refused, so the frame whose collapse cleared an earlier frame's marker owes and offers
 * one of its own behind that collapse. The earlier marker belonged to a frame that is over; what a
 * later marker flushes is every debt still owed, not one frame's. So the count of markers a drain
 * sees can fall, and the number of debts left unflushed cannot rise above zero.
 */
final class UiaEvents {

    /**
     * How many events may wait to be raised.
     *
     * <p>The same number the macOS queue took from the guest count, for the same arithmetic: a
     * five-row page of a list is 21 events on both platforms (&sect;13.19, 2026-09-07), so 256 is
     * a sixty-row page. A drain of that many at the measured 2.5&nbsp;ms a raise is over half a
     * second, and that is fine here, because nobody is waiting for it: the drain thread is what
     * spends it, and a reader that is that far behind is told to re-read when the next collapse
     * comes rather than being fed events about rows that have already scrolled away.
     */
    static final int CAPACITY = 256;

    /** The marker a collapse leaves; never offered by a caller and never raised as itself. */
    static final AccessibleEvent COLLAPSE = AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, -1);

    /**
     * The marker {@link #endFrame} leaves: the frame's accessibility step is over and every event
     * of that frame is already behind it in this queue. Never raised as itself; read by the drain
     * thread as the publish boundary an owed re-announcement is flushed at.
     */
    static final AccessibleEvent FRAME_END = AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, -2);

    private static final System.Logger LOG = System.getLogger(UiaEvents.class.getName());

    private final BlockingQueue<AccessibleEvent> queue = new ArrayBlockingQueue<>(CAPACITY + 1);
    private final AtomicBoolean collapsed = new AtomicBoolean();
    private final AtomicInteger collapses = new AtomicInteger();

    /**
     * Hands over one event, from the user-interface thread. Never blocks.
     *
     * @param event what happened
     * @return whether it was queued; {@code false} means the queue collapsed, either now or
     *         before this offer and not yet drained, and the marker covers it
     */
    boolean offer(AccessibleEvent event) {
        if (collapsed.get()) {
            return false;
        }
        if (queue.size() < CAPACITY && queue.offer(event)) {
            return true;
        }
        collapse();
        return false;
    }

    /**
     * Marks the end of a frame's accessibility step, from the user-interface thread, after every
     * event of that frame has been offered. Never blocks and <b>never leaves without putting a
     * marker in</b>.
     *
     * <p>Unlike an event it ignores the collapsed flag: a collapse covers the events it swallowed,
     * and it raises the very debt this marker exists to flush, so a marker the collapse swallowed
     * would be a debt nothing pays. When the queue has no room left the queue collapses first and
     * the marker goes in behind the collapse's own, which keeps both in order and costs a
     * re-read no over-capacity queue was going to avoid.
     *
     * <p>A marker <em>already waiting</em> is a different matter: {@link #collapse} clears the
     * queue, so this frame's collapse can take the previous frame's end away with it. That frame is
     * over and its debt, if it had one, is still owed — nothing clears a debt but the raise that
     * pays it — and the frame that collapsed marks its own end for the same reason it collapsed,
     * so the drain reaches a marker behind the collapse and flushes there. Fewer markers, never a
     * debt left with none.
     */
    void endFrame() {
        if (queue.offer(FRAME_END)) {
            return;
        }
        collapse();
        queue.offer(FRAME_END);
    }

    /**
     * Empties the queue and leaves the collapse marker in it. The marker goes in AFTER the flag is
     * up, so a drain thread that takes it knows every event offered since is covered; and the queue
     * is cleared first, so the marker is what it finds next rather than a page of events about the
     * same rows.
     *
     * <p>The clear takes an earlier frame's end with it where one was still waiting, which is the
     * one way this queue loses a marker; {@link #endFrame} says why that leaves no debt unflushed.
     */
    private void collapse() {
        collapsed.set(true);
        queue.clear();
        queue.offer(COLLAPSE);
        int n = collapses.incrementAndGet();
        LOG.log(System.Logger.Level.WARNING, "accessibility event queue collapsed: more than "
                + CAPACITY + " events were waiting to be raised (collapse #" + n
                + "); the registry is being swept and the window re-read whole instead");
    }

    /**
     * The next event to raise, from the drain thread. Blocks until there is one.
     *
     * @return an event, or {@link #COLLAPSE} once per collapse, after which offers are accepted
     *         again
     * @throws InterruptedException if the drain thread is being stopped
     */
    AccessibleEvent take() throws InterruptedException {
        AccessibleEvent next = queue.take();
        if (next == COLLAPSE) {
            collapsed.set(false);
        }
        return next;
    }

    /** @return how many events are waiting; the marker counts as one. */
    int size() {
        return queue.size();
    }

    /** @return how many times this queue has collapsed, ever: the number &sect;13.19 reads. */
    int collapses() {
        return collapses.get();
    }
}

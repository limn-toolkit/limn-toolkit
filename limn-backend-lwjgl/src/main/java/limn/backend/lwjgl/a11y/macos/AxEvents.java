package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.AccessibleEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The queue between {@code emit} and the frame that posts.
 *
 * <p><b>Enqueue, never raise.</b> A difference between two frames of a scrolling list or a dragged
 * slider can be hundreds of nodes wide, and every post is a cross-process call: a bridge that posted
 * inside {@code emit} would spend the frame budget inside the platform, which is precisely the stall
 * that makes a window unreadable to the person it is being read to.
 *
 * <p><b>It is bounded, and overflow is a reconciliation rather than a loss.</b> When more events
 * arrive than the queue can carry, everything in it is replaced by one
 * {@link AccessibleEvent.Type#INVALIDATED} — "everything about this window may have changed" — which
 * is the honest thing to say and the cheap one. Dropping the oldest instead would leave a client
 * acting on a prefix of a change it can no longer complete, and growing without bound would move the
 * stall from the platform to memory.
 *
 * <p>The collapse has a consequence this class cannot discharge and its caller must: the per-node
 * destructions in the dropped burst are gone with it, so a collapse is followed by a sweep of the
 * element registry against the tree (§13.9). That is the path most likely to be written and never
 * exercised, because reaching it needs a difference wider than the queue.
 */
final class AxEvents {

    /**
     * How many events one frame's difference may carry before it is collapsed.
     *
     * <p>Set from §13.19's two measurements rather than from the 8 ms rule alone, which is what it
     * was until they existed. Taken on the macOS 26.6.2 guest (2026-09-07, the
     * software-GL window, VoiceOver attached and reading), with the live probe's
     * {@code -Dprobe.timing=true -Dprobe.cycle=scroll} and {@code drag} at a 100 ms tick:
     *
     * <ul>
     * <li><b>The count.</b> Paging a list by its five-row viewport is <b>21 events a frame</b>, every
     * frame, forty frames running: one {@code VALUE_CHANGED} for the list, and per row shown one
     * {@code STRUCTURE_CHANGED} on the list, one on the row, and a {@code NODE_DESTROYED} each for
     * the row that left and its label — four per row plus one. Eleven of the 21 become a
     * notification (the value and the ten layout changes; destructions post nothing here). A
     * dragged slider is one event a frame, one notification.</li>
     * <li><b>The cost.</b> That frame's drain — the whole of the bridge's cross-process work —
     * with VoiceOver's cursor on the list being paged: 164 µs at p50, 416 µs at p90, 1.67 ms at
     * the worst of the 21-event frames; per event 8 µs, 20 µs and 80 µs. With the cursor elsewhere
     * in the window it was 130 µs at the median. One {@code NSAccessibilityPostNotification} by itself is
     * 375 ns at p50 and 459 ns at p99 with VoiceOver on, 83 ns and 125 ns with it off (10,000
     * timed posts each, AxProbe), so the drain's cost is the bookkeeping around the post and what
     * the reader does with it, and not the post.</li>
     * </ul>
     *
     * <p>Sixty-four was therefore a page of fifteen rows, and a fifteen-row viewport is an ordinary
     * list: at 64 every page of it collapsed, and a collapse re-reads the whole window. 256 is a
     * page of sixty rows — a tall window's worth — and its worst drain at a pessimistic 30 µs an
     * event (above the p90 measured with the reader on the list) is 7.7 ms, inside the 8 ms
     * budget the rule was written against. The warning below remains the signal that a difference
     * wider than this exists in some scene, and the number to revisit when it fires.
     */
    static final int CAPACITY = 256;

    private static final System.Logger LOG = System.getLogger(AxEvents.class.getName());

    private final Deque<AccessibleEvent> queued = new ArrayDeque<>();
    private boolean collapsed;
    /** How many times the queue has collapsed since it was made: the count §13.19's signal is. */
    private int collapses;

    /**
     * @param event what happened
     */
    void add(AccessibleEvent event) {
        if (collapsed) return;   // everything is already being said at once
        if (queued.size() >= CAPACITY) {
            queued.clear();
            collapsed = true;
            collapses++;
            // The signal the record asks for, and the only one there is: a collapse is correct in
            // shape at any capacity, so nothing else about it says the number was set wrong. A
            // WARNING and not a trace, because a bridge whose every frame collapses is telling a
            // reader "re-read everything" sixty times a second and nobody would otherwise know.
            LOG.log(System.Logger.Level.WARNING, "accessibility event queue collapsed: one frame's"
                    + " difference carried more than " + CAPACITY + " events (collapse #"
                    + collapses + "); the window is being re-read whole instead");
            return;
        }
        queued.add(event);
    }

    /**
     * Takes everything queued, leaving the queue empty.
     *
     * @return the events to post, in order; one {@code INVALIDATED} when the queue collapsed
     */
    List<AccessibleEvent> drain() {
        List<AccessibleEvent> events;
        if (collapsed) {
            events = List.of(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, 0));
        } else {
            events = new ArrayList<>(queued);
        }
        queued.clear();
        collapsed = false;
        return events;
    }

    /**
     * @return whether the last drain will be a collapse, which is what tells the caller to sweep
     *         its registry rather than trusting the per-node destructions it was told about
     */
    boolean willCollapse() {
        return collapsed;
    }

    /** @return how many events are waiting; a collapsed queue holds none and means all of them. */
    int size() {
        return queued.size();
    }

    /**
     * @return how many times this queue has collapsed, ever — the live run reads it at the end to
     *         say whether the capacity held, which is the measurement §13.19 asks for
     */
    int collapses() {
        return collapses;
    }
}

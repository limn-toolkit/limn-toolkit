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
     * <p>A policy, not a measurement, and §13.19 says so: the number that would justify itself is
     * a count of what a real difference produces while a reader is attached, taken on the guest. It
     * is set so that a full queue's drain fits inside the frame budget at a pessimistic cost per
     * post, and the warning below is the signal that it was set wrong.
     */
    static final int CAPACITY = 64;

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

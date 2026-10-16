package limn.backend.lwjgl.a11y.linux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What is waiting to be written to the connection, and the one policy that decides what may wait.
 *
 * <p>Separate from the connection because the policy is where the hazard is and a socket is not
 * needed to state it. The threading rule has three sides and this is the third: the reader thread
 * must never write, the writer thread must never read, and <b>whoever offers must never block</b>.
 * A bounded queue that made the offering thread wait would park the user-interface thread on a
 * client that has stopped draining, which is the same stall reached from a different direction.
 *
 * <p>So the queue is unbounded as a structure and bounded as a policy, and the bound applies to
 * signals alone. A reply is owed to a client parked waiting for it, and dropping one hangs that
 * client rather than costing it an event; a signal is an event, and a reader that missed one is
 * told about the next. Refusing a signal at the door is the only outcome that costs neither
 * correctness nor a thread.
 */
final class Outbound {

    /**
     * How many signals may stand in the queue at once.
     *
     * <p>A screen reader that has stopped reading its socket is the case this exists for, and the
     * number is a backlog rather than a rate: past it the events are already stale, and what the
     * client needs when it returns is the current tree, which the next publish gives it.
     */
    static final int SIGNAL_BOUND = 256;

    /**
     * How many signals of the reserved tail may stand in the queue at once, counted apart from
     * {@link #SIGNAL_BOUND}.
     *
     * <p>The tail is what a reader is directed by — where the focus and the cursor went, what the
     * structure and the selection did, whether the window is active — and the model keeps it
     * outside its own event budget for that reason. A backlog of ordinary signals must not cost it:
     * that is exactly the moment the reader most needs to hear where the user is ("Outbound carries
     * a kind flag so it never drops the tail"). It is still bounded, because a queue that grows for
     * ever behind a writer that never writes is a leak, not a policy: sixteen times the ordinary
     * bound is tails of many publishes, which a connection whose writer is moving at all never
     * accumulates. Policy, not a platform constant.
     */
    static final int TAIL_BOUND = SIGNAL_BOUND * 16;

    /**
     * How many ordinary signals one publish may keep back while it is held, counted apart from
     * {@link #SIGNAL_BOUND}.
     *
     * <p>A held signal is not in the writer's backlog, and counting it there made the backlog's
     * bound a cap on each publish: a form of seventy fields turned editable at once is 140 state
     * changes, each a signal and a cache item, and everything past the 256th was refused however
     * fast the reader drained (44 of 300 in a probe). A publish is at most the model's event
     * budget before it collapses into one re-announcement, and one event is a few signals (the
     * change, a cache item, a children-changed beside an arrival), so four signals for each event
     * of the budget holds a whole publish. It is still a bound, so a publish that runs away is
     * not a leak.
     */
    static final int HELD_BOUND = limn.accessibility.Accessibility.EVENT_BUDGET * 4;

    /** What an entry is, which decides the slot it holds while it waits. */
    enum Kind {
        /** Owed to a caller; never refused. */
        REPLY,
        /** An ordinary event; refused past {@link #SIGNAL_BOUND}. */
        SIGNAL,
        /** An event of the reserved tail; refused only past {@link #TAIL_BOUND}. */
        TAIL
    }

    private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger queuedSignals = new AtomicInteger();
    private final AtomicInteger queuedTail = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private volatile Kind lastKind = Kind.REPLY;
    /** Guards {@link #holding} and {@link #held}, which any offering thread reads. */
    private final Object holdLock = new Object();
    /** Whether signals are being kept back until {@link #release()}. */
    private boolean holding;
    /** The signals kept back, in the order they were accepted. */
    private final List<Entry> held = new ArrayList<>();
    /** How many of {@link #held} are ordinary signals, which {@link #HELD_BOUND} bounds. */
    private int heldSignals;

    private record Entry(byte[] bytes, Kind kind) {
    }

    /**
     * Keeps the signals offered from now on from the writer until {@link #release()}, so the
     * signals of one publish reach a client together. Replies are never kept back: a client is
     * parked waiting on each one.
     *
     * <p>A publish's signals are computed one event at a time on the user-interface thread, and the
     * structure of a subtree that arrived — its cache items — takes milliseconds to compute; the
     * writer sent each signal as it was offered, so a client could handle the first of them before
     * the rest existed. Orca 50.3 did: a combo box opening its list in the scene sends its own
     * {@code focused} 0 and {@code expanded} 1 and then the list's arrival and its {@code focused}
     * 1, and with the first two alone in its queue Orca read "expanded" on the combo it still stood
     * on, before the list (2026-10-16, .claude/pending/2026-10-16/readers-adr-068/fedora/, 3 runs
     * of 5 at 3 to 6 ms between the two halves). The bound is decided at the door as before, so a
     * refusal is still known to the caller at once.
     */
    void hold() {
        synchronized (holdLock) {
            holding = true;
        }
    }

    /**
     * Hands every signal kept back since {@link #hold()} to the writer, in order, and stops. The
     * ordinary ones join the writer's backlog, which may then stand past {@link #SIGNAL_BOUND}
     * for as long as the writer takes to drain them: they were accepted, and the next offer is
     * refused until it has.
     */
    void release() {
        synchronized (holdLock) {
            holding = false;
            queuedSignals.addAndGet(heldSignals);
            heldSignals = 0;
            queue.addAll(held);
            held.clear();
        }
    }

    private void enqueueSignal(Entry entry) {
        synchronized (holdLock) {
            if (holding) {
                held.add(entry);
            } else {
                queue.add(entry);
            }
        }
    }

    /**
     * Queues a reply, always. Never blocks and never refuses.
     *
     * @param bytes the marshalled message
     */
    void offerReply(byte[] bytes) {
        queue.add(new Entry(bytes, Kind.REPLY));
    }

    /**
     * Queues a signal of the reserved tail unless {@link #TAIL_BOUND} of them already wait. The
     * ordinary backlog does not count against it.
     *
     * @param bytes the marshalled message
     * @return whether it was accepted; a refusal is counted and nothing else happens
     */
    boolean offerTailSignal(byte[] bytes) {
        if (queuedTail.get() >= TAIL_BOUND) {
            dropped.incrementAndGet();
            return false;
        }
        queuedTail.incrementAndGet();
        enqueueSignal(new Entry(bytes, Kind.TAIL));
        return true;
    }

    /**
     * Queues a signal if the writer's backlog is under {@link #SIGNAL_BOUND}, and, while held,
     * if the publish has kept back fewer than {@link #HELD_BOUND}. A reader that keeps up leaves
     * the backlog empty and hears a whole publish; one that has stopped reading leaves it full,
     * and the signals of the next publishes are refused at the door as before.
     *
     * @param bytes the marshalled message
     * @return whether it was accepted; a refusal is counted and nothing else happens
     */
    boolean offerSignal(byte[] bytes) {
        synchronized (holdLock) {
            if (queuedSignals.get() >= SIGNAL_BOUND || holding && heldSignals >= HELD_BOUND) {
                dropped.incrementAndGet();
                return false;
            }
            Entry entry = new Entry(bytes, Kind.SIGNAL);
            if (holding) {
                heldSignals++; // in the writer's backlog only once released
                held.add(entry);
            } else {
                queuedSignals.incrementAndGet();
                queue.add(entry);
            }
            return true;
        }
    }

    /**
     * Takes the next message to write, blocking until there is one.
     *
     * @return the marshalled bytes
     * @throws InterruptedException if the writer thread is interrupted while parked
     */
    byte[] take() throws InterruptedException {
        Entry e = queue.take();
        lastKind = e.kind();
        return e.bytes();
    }

    /** Releases the slot the message from the last {@link #take()} held, written or not. */
    void written() {
        switch (lastKind) {
            case SIGNAL -> queuedSignals.decrementAndGet();
            case TAIL -> queuedTail.decrementAndGet();
            case REPLY -> {
            }
        }
        lastKind = Kind.REPLY;
    }

    /** @return how many signals have been refused for want of room */
    int dropped() {
        return dropped.get();
    }

    /** @return how many messages are waiting, the ones kept back included */
    int size() {
        synchronized (holdLock) {
            return queue.size() + held.size();
        }
    }
}

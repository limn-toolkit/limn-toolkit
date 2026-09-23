package limn.backend.lwjgl.a11y.linux;

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

    private record Entry(byte[] bytes, Kind kind) {
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
        queue.add(new Entry(bytes, Kind.TAIL));
        return true;
    }

    /**
     * Queues a signal if the backlog is under {@link #SIGNAL_BOUND}.
     *
     * @param bytes the marshalled message
     * @return whether it was accepted; a refusal is counted and nothing else happens
     */
    boolean offerSignal(byte[] bytes) {
        if (queuedSignals.get() >= SIGNAL_BOUND) {
            dropped.incrementAndGet();
            return false;
        }
        queuedSignals.incrementAndGet();
        queue.add(new Entry(bytes, Kind.SIGNAL));
        return true;
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

    /** @return how many messages are waiting */
    int size() {
        return queue.size();
    }
}

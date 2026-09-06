package limn.backend.lwjgl.a11y.linux;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What is waiting to be written to the connection, and the one policy that decides what may wait.
 *
 * <p>Separate from the connection because the policy is where the hazard is and a socket is not
 * needed to state it. §3.3's rule has three sides and this is the third: the reader thread must
 * never write, the writer thread must never read, and <b>whoever offers must never block</b>. A
 * bounded queue that made the offering thread wait would park the user-interface thread on a
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

    private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger queuedSignals = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private volatile boolean lastWasSignal;

    private record Entry(byte[] bytes, boolean signal) {
    }

    /**
     * Queues a reply, always. Never blocks and never refuses.
     *
     * @param bytes the marshalled message
     */
    void offerReply(byte[] bytes) {
        queue.add(new Entry(bytes, false));
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
        queue.add(new Entry(bytes, true));
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
        lastWasSignal = e.signal();
        return e.bytes();
    }

    /** Releases the slot the message from the last {@link #take()} held, written or not. */
    void written() {
        if (lastWasSignal) {
            queuedSignals.decrementAndGet();
            lastWasSignal = false;
        }
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

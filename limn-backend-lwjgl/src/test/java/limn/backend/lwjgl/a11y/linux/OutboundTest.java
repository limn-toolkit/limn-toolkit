package limn.backend.lwjgl.a11y.linux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that a client which stopped reading its socket costs events and never a thread.
 *
 * <p>This is §3.3's third side. The first two are threads and are visible in the connection: the
 * reader never writes, the writer never reads. The third is here, because the hazard is the policy
 * rather than the socket: a bounded queue that blocked its offerer would park the user-interface
 * thread on a peer that has stopped draining, which is the same stall the thread split exists to
 * prevent, reached from the side nobody watches.
 */
class OutboundTest {

    private static byte[] message(int n) {
        return new byte[] {(byte) n};
    }

    @Test
    void aSignalBacklogIsRefusedAtTheDoorAndCountedRatherThanBlocking() {
        Outbound out = new Outbound();
        for (int i = 0; i < Outbound.SIGNAL_BOUND; i++) {
            assertTrue(out.offerSignal(message(i)), "under the bound, signal " + i);
        }
        assertEquals(0, out.dropped(), "nothing refused while there was room");

        assertFalse(out.offerSignal(message(0)), "the one past the bound is refused");
        assertFalse(out.offerSignal(message(0)));
        assertEquals(2, out.dropped(), "and counted, so a bridge can say it lost events");
        assertEquals(Outbound.SIGNAL_BOUND, out.size(),
                "the refusal did not grow the queue, which is the whole point");
    }

    @Test
    void aReplyIsNeverRefusedHoweverFarBehindTheSignalsAre() throws Exception {
        Outbound out = new Outbound();
        for (int i = 0; i < Outbound.SIGNAL_BOUND * 4; i++) {
            out.offerSignal(message(i));
        }
        assertTrue(out.dropped() > 0, "the fixture needs the bound to have been reached");

        out.offerReply(message(99));

        assertEquals(Outbound.SIGNAL_BOUND + 1, out.size(),
                "a reply is owed to a client parked on it: dropping one hangs that client, where a "
                        + "dropped signal only costs it an event it will be told about again");
    }

    /**
     * The reserved tail is never refused because the ordinary backlog is full (decision 28:
     * "Outbound carries a kind flag so it never drops the tail"), and has a bound of its own so a
     * writer that never writes is still not a leak.
     */
    @Test
    void aTailSignalIsAcceptedWhateverTheOrdinaryBacklogAndHasABoundOfItsOwn() throws Exception {
        Outbound out = new Outbound();
        for (int i = 0; i < Outbound.SIGNAL_BOUND; i++) {
            out.offerSignal(message(i));
        }
        assertFalse(out.offerSignal(message(0)), "the ordinary backlog is full");
        assertTrue(out.offerTailSignal(message(1)),
                "and the focus the reader must hear still goes in");

        for (int i = 1; i < Outbound.TAIL_BOUND; i++) {
            assertTrue(out.offerTailSignal(message(i)), "under the tail's own bound, " + i);
        }
        assertFalse(out.offerTailSignal(message(0)), "past it, refused");
        assertEquals(2, out.dropped());

        // Drain the ordinary signals first: they were queued first.
        for (int i = 0; i < Outbound.SIGNAL_BOUND; i++) {
            out.take();
            out.written();
        }
        assertFalse(out.offerTailSignal(message(0)),
                "an ordinary signal written releases no tail slot");
        out.take();
        out.written();
        assertTrue(out.offerTailSignal(message(0)), "a tail signal written releases one");
    }

    @Test
    void writingASignalReleasesItsSlotAndWritingAReplyReleasesNothing() throws Exception {
        Outbound out = new Outbound();
        for (int i = 0; i < Outbound.SIGNAL_BOUND; i++) {
            out.offerSignal(message(i));
        }
        assertFalse(out.offerSignal(message(0)), "full");

        out.take();
        out.written();

        assertTrue(out.offerSignal(message(0)),
                "the slot came back when the writer drained one, not when it was queued");

        Outbound replies = new Outbound();
        replies.offerReply(message(1));
        replies.take();
        replies.written();
        for (int i = 0; i < Outbound.SIGNAL_BOUND; i++) {
            assertTrue(replies.offerSignal(message(i)),
                    "a written reply released no signal slot, because it held none: " + i);
        }
    }

    /**
     * The signals of one publish reach the writer together: a held signal is not given to the
     * writer until the release, and then in the order it was offered, while a reply goes at once,
     * since a client is parked on it. The bound is still decided at the door.
     */
    @Test
    void heldSignalsWaitForTheReleaseInOrderAndAReplyDoesNot() throws Exception {
        Outbound out = new Outbound();
        out.offerSignal(message(1));
        out.hold();
        assertTrue(out.offerSignal(message(2)), "accepted at the door while held");
        assertTrue(out.offerTailSignal(message(3)));
        out.offerReply(message(9));
        assertEquals(4, out.size(), "the held ones are waiting too");

        assertEquals(1, out.take()[0], "what was queued before the hold goes");
        out.written();
        assertEquals(9, out.take()[0], "and a reply is never kept back");
        out.written();
        assertEquals(2, out.size(), "the held signals have not reached the writer");

        out.release();
        assertEquals(2, out.take()[0], "released in the order they were offered");
        out.written();
        assertEquals(3, out.take()[0]);
        out.written();
        assertEquals(0, out.size());

        out.offerSignal(message(4));
        assertEquals(4, out.take()[0], "and after the release nothing is kept back");
    }

    /**
     * A held publish is not capped by the writer's backlog bound: its signals are not in that
     * backlog until the release. Counted there, a form of seventy fields made editable in one
     * publish lost everything past the 256th signal however fast the reader drained (300 offered,
     * 44 refused).
     */
    @Test
    void aHeldPublishLargerThanTheBacklogBoundIsAcceptedWhole() throws Exception {
        Outbound out = new Outbound();
        out.hold();
        int offered = Outbound.SIGNAL_BOUND + 44;
        for (int i = 0; i < offered; i++) {
            assertTrue(out.offerSignal(message(i)), "held signal " + i);
        }
        assertEquals(0, out.dropped());
        out.release();
        for (int i = 0; i < offered; i++) {
            assertEquals((byte) i, out.take()[0], "in the order offered");
            out.written();
        }
        assertEquals(0, out.size());
        assertTrue(out.offerSignal(message(0)), "and every slot came back as the writer drained");
    }

    /** One held publish has a ceiling of its own, so a publish that runs away is not a leak. */
    @Test
    void aHeldPublishHasABoundOfItsOwn() {
        Outbound out = new Outbound();
        out.hold();
        for (int i = 0; i < Outbound.HELD_BOUND; i++) {
            assertTrue(out.offerSignal(message(i)), "under the held bound, " + i);
        }
        assertFalse(out.offerSignal(message(0)), "past it, refused");
        assertEquals(1, out.dropped());
    }

    /**
     * A reader that has stopped reading still costs the next publishes their signals: the writer's
     * backlog, full, refuses them at the door though they would only be held.
     */
    @Test
    void aFullBacklogRefusesAHeldPublishToo() {
        Outbound out = new Outbound();
        out.hold();
        for (int i = 0; i < Outbound.SIGNAL_BOUND; i++) {
            out.offerSignal(message(i));
        }
        out.release(); // nobody writes: the backlog stands full
        out.hold();
        assertFalse(out.offerSignal(message(0)), "the stalled reader's backlog is full");
        assertEquals(1, out.dropped());
        assertEquals(Outbound.SIGNAL_BOUND, out.size(), "and the queue did not grow");
    }
}

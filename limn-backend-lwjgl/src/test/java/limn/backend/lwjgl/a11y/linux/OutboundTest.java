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
}

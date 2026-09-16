package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.AccessibleEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queue between the user-interface thread and the drain thread, and its one policy. */
class UiaEventsTest {

    private static AccessibleEvent focus(long node) {
        return AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, node);
    }

    @Test
    void whatIsOfferedIsTakenInOrder() throws InterruptedException {
        UiaEvents events = new UiaEvents();
        assertTrue(events.offer(focus(1)));
        assertTrue(events.offer(focus(2)));
        assertEquals(1, events.take().nodeId());
        assertEquals(2, events.take().nodeId());
        assertEquals(0, events.size());
        assertEquals(0, events.collapses());
    }

    @Test
    void overflowingCollapsesToOneMarkerAndDropsWhatFollowsUntilItIsTaken()
            throws InterruptedException {
        UiaEvents events = new UiaEvents();
        for (int i = 0; i < UiaEvents.CAPACITY; i++) {
            assertTrue(events.offer(focus(i)), "event " + i + " fits");
        }
        assertFalse(events.offer(focus(UiaEvents.CAPACITY)), "the one past the bound collapses");
        assertEquals(1, events.collapses());
        assertEquals(1, events.size(), "everything waiting was replaced by the marker");
        assertFalse(events.offer(focus(9_999)), "and offers are covered by it until it is taken");
        assertEquals(1, events.collapses(), "a covered offer is not a second collapse");

        assertSame(UiaEvents.COLLAPSE, events.take());
        assertTrue(events.offer(focus(7)), "after the marker, the queue accepts again");
        assertEquals(7, events.take().nodeId());
    }

    @Test
    void aCollapseIsSaidOnceAsAWarning() {
        Logger logger = Logger.getLogger(UiaEvents.class.getName());
        List<LogRecord> said = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { said.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            UiaEvents events = new UiaEvents();
            for (int i = 0; i <= UiaEvents.CAPACITY + 5; i++) {
                events.offer(focus(i));
            }
            long warnings = said.stream()
                    .filter(r -> r.getLevel() == java.util.logging.Level.WARNING).count();
            assertEquals(1, warnings, "said exactly once per collapse: " + said);
        } finally {
            logger.removeHandler(handler);
        }
    }

    /**
     * The frame's end arrives behind the frame's events, which is the whole reason it is a marker
     * in this queue and not a flag beside it: the drain flushes an owed re-announcement on it
     * (§2.4, semantics 4), and a flag could be read before the events it is meant to follow.
     */
    @Test
    void theFramesEndIsTakenBehindTheEventsOfThatFrame() throws InterruptedException {
        UiaEvents events = new UiaEvents();
        events.offer(focus(1));
        events.endFrame();
        events.offer(focus(2));
        events.endFrame();
        assertEquals(0, events.collapses(), "two frames of one event each collapse nothing");
        assertEquals(4, events.size(), "two events and one marker each");
        List<String> taken = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            AccessibleEvent next = events.take();
            taken.add(next == UiaEvents.FRAME_END ? "the frame's end" : "node " + next.nodeId());
        }
        assertEquals(List.of("node 1", "the frame's end", "node 2", "the frame's end"), taken);
    }

    /**
     * A collapse covers the events it swallowed, but not the frame's end: that marker is what
     * flushes the re-announcement the collapse itself leaves owed, so a collapse that swallowed it
     * would leave the debt with nothing to pay it on a window whose scene then goes still.
     */
    @Test
    void aCollapsedQueueStillTakesTheFramesEnd() throws InterruptedException {
        UiaEvents events = new UiaEvents();
        for (int i = 0; i <= UiaEvents.CAPACITY; i++) {
            events.offer(focus(i));
        }
        assertEquals(1, events.collapses());
        assertFalse(events.offer(focus(9_999)), "an event offered now is covered by the marker");
        events.endFrame();
        assertEquals(2, events.size(), "the frame's end is not covered by it");
        assertSame(UiaEvents.COLLAPSE, events.take());
        assertSame(UiaEvents.FRAME_END, events.take(), "and is taken behind the collapse");
    }

    /**
     * The other way the marker could be dropped: no room. A queue that full is over capacity and a
     * collapse is what it already owes the client, so it collapses and the marker goes in behind
     * the collapse's own rather than being lost.
     */
    @Test
    void aFullQueueCollapsesRatherThanDropTheFramesEnd() throws InterruptedException {
        UiaEvents events = new UiaEvents();
        for (int i = 0; i < UiaEvents.CAPACITY; i++) {
            assertTrue(events.offer(focus(i)), "event " + i + " fits");
        }
        events.endFrame();
        assertEquals(0, events.collapses(), "the last slot is the frame's end, and it fitted");
        events.endFrame();
        assertEquals(1, events.collapses(), "the second had nowhere to go, so the queue collapsed");
        assertEquals(2, events.size());
        assertSame(UiaEvents.COLLAPSE, events.take());
        assertSame(UiaEvents.FRAME_END, events.take());
    }

    @Test
    void takeWaitsForAnOfferFromAnotherThread() throws Exception {
        UiaEvents events = new UiaEvents();
        Thread offerer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                return;
            }
            events.offer(focus(42));
        });
        offerer.start();
        assertEquals(42, events.take().nodeId());
        offerer.join();
    }
}

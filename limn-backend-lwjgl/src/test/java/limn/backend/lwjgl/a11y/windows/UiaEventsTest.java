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

package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.AccessibleEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bound, and what happens at it. */
class AxEventsTest {

    private static AccessibleEvent focus(long nodeId) {
        return AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, nodeId);
    }

    @Test
    void aQuietFrameDrainsNothing() {
        assertTrue(new AxEvents().drain().isEmpty());
    }

    @Test
    void eventsComeOutInTheOrderTheyWentIn() {
        AxEvents events = new AxEvents();
        events.add(focus(1));
        events.add(focus(2));
        events.add(focus(3));
        List<AccessibleEvent> drained = events.drain();
        assertEquals(List.of(1L, 2L, 3L), drained.stream().map(AccessibleEvent::nodeId).toList());
        assertTrue(events.drain().isEmpty(), "and a drain empties the queue");
    }

    @Test
    void aDifferenceWiderThanTheQueueBecomesOneInvalidation() {
        AxEvents events = new AxEvents();
        for (int i = 0; i <= AxEvents.CAPACITY; i++) events.add(focus(i));
        assertTrue(events.willCollapse());
        List<AccessibleEvent> drained = events.drain();
        assertEquals(1, drained.size(),
                "a client is told everything may have changed, not a prefix it cannot complete");
        assertEquals(AccessibleEvent.Type.INVALIDATED, drained.get(0).type());
    }

    @Test
    void aCollapsedQueueStopsAccumulatingRatherThanGrowing() {
        AxEvents events = new AxEvents();
        for (int i = 0; i < AxEvents.CAPACITY * 4; i++) events.add(focus(i));
        assertEquals(0, events.size(), "the point of the bound is that memory stops growing too");
    }

    @Test
    void drainingResetsTheCollapseSoTheNextFrameStartsCleanly() {
        AxEvents events = new AxEvents();
        for (int i = 0; i <= AxEvents.CAPACITY; i++) events.add(focus(i));
        events.drain();
        assertFalse(events.willCollapse());
        events.add(focus(99));
        List<AccessibleEvent> drained = events.drain();
        assertEquals(1, drained.size());
        assertEquals(AccessibleEvent.Type.FOCUS_CHANGED, drained.get(0).type(),
                "a collapse is one frame's verdict, not a state the bridge stays in");
    }

    @Test
    void exactlyTheCapacityFitsWithoutCollapsing() {
        AxEvents events = new AxEvents();
        for (int i = 0; i < AxEvents.CAPACITY; i++) events.add(focus(i));
        assertFalse(events.willCollapse(), "the bound is what it says it is");
        assertEquals(AxEvents.CAPACITY, events.drain().size());
    }

    @Test
    void aCollapseIsCountedAndWarnedAboutBecauseItIsTheOnlySignalTheCapacityWasSetWrong() {
        List<LogRecord> logged = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override public void flush() {
            }

            @Override public void close() {
            }
        };
        Logger logger = Logger.getLogger(AxEvents.class.getName());
        logger.addHandler(capture);
        try {
            AxEvents events = new AxEvents();
            for (int i = 0; i < AxEvents.CAPACITY; i++) events.add(focus(i));
            assertEquals(0, events.collapses(), "a full queue is not yet a collapsed one");
            assertTrue(logged.isEmpty(), "and nothing is said about a queue that held");
            events.add(focus(AxEvents.CAPACITY));
            events.add(focus(AxEvents.CAPACITY + 1));
            assertEquals(1, events.collapses(),
                    "one burst is one collapse, however many events arrived after the edge");
            assertEquals(1, logged.size(), "and it is said exactly once per collapse");
            assertEquals(Level.WARNING, logged.get(0).getLevel(),
                    "as a WARNING: a bridge re-reading the window every frame is a defect, not a trace");
            assertTrue(logged.get(0).getMessage().contains(String.valueOf(AxEvents.CAPACITY)),
                    "naming the number that was set wrong: " + logged.get(0).getMessage());
            events.drain();
            for (int i = 0; i <= AxEvents.CAPACITY; i++) events.add(focus(i));
            assertEquals(2, events.collapses(), "the count survives the drain: it is a run's total");
        } finally {
            logger.removeHandler(capture);
        }
    }
    @Test
    void theCapacityIsThePageTheGuestCountedInsideTheBudgetTheGuestTimed() {
        // §13.19's macOS half, measured 2026-09-07 on the guest with VoiceOver reading the list:
        // paging a five-row viewport is 21 events a frame, four per row plus one, so the queue
        // must carry a page of a tall window's list without collapsing...
        assertTrue(AxEvents.CAPACITY >= 60 * 4 + 1,
                "a page of sixty rows at four events a row plus one is " + (60 * 4 + 1)
                        + " events, and 64 collapsed on every page of an ordinary fifteen-row list");
        // ...and its full drain must still fit the frame budget at a per-event cost well above
        // the 20 us measured at p90 with the reader on the list.
        assertTrue(AxEvents.CAPACITY * 30_000L <= 8_000_000L,
                "a full queue at a pessimistic 30 us an event must drain inside 8 ms");
    }
}

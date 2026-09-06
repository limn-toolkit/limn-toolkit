package limn.a11y.macos;

import limn.accessibility.AccessibleEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}

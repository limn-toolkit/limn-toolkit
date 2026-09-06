package limn.a11y.macos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four ways an element goes away, and the one thread it may go away on.
 *
 * <p>None of this needs AppKit, which is the point: the lifetime rule is bookkeeping, and the two
 * defects it exists to prevent — an object released twice, and an object never released at all —
 * are both visible in a counter.
 */
class AxElementsTest {

    /** Records what was made and what was released, so double releases and leaks are both countable. */
    private static final class RecordingFactory implements AxElements.Factory {
        private long next = 0x1000;
        final List<Long> made = new ArrayList<>();
        final List<Long> released = new ArrayList<>();

        @Override public long newElement(long nodeId) {
            long element = next += 0x10;
            made.add(element);
            return element;
        }

        @Override public void release(long element) {
            assertFalse(released.contains(element),
                    "0x" + Long.toHexString(element) + " released twice; the second one is a message "
                            + "to freed memory");
            released.add(element);
        }
    }

    private static AxElements registry(RecordingFactory factory) {
        return new AxElements(Thread.currentThread(), factory);
    }

    @Test
    void anElementIsMintedOnceAndIsTheSameObjectEveryTimeAfter() {
        RecordingFactory factory = new RecordingFactory();
        AxElements elements = registry(factory);
        long first = elements.elementFor(7);
        assertEquals(first, elements.elementFor(7),
                "a client that kept a reference must find it still means the same node (§1.3)");
        assertEquals(1, factory.made.size());
        assertNotEquals(first, elements.elementFor(8));
    }

    @Test
    void nothingIsMintedForANodeNobodyAskedAbout() {
        RecordingFactory factory = new RecordingFactory();
        AxElements elements = registry(factory);
        assertFalse(elements.holds(7));
        assertEquals(0, factory.made.size(), "allocation is lazy; a walked tree is not an allocated one");
    }

    @Test
    void forgettingReleasesExactlyOnceAndOnlyIfThereWasOne() {
        RecordingFactory factory = new RecordingFactory();
        AxElements elements = registry(factory);
        long element = elements.elementFor(7);
        assertTrue(elements.forget(7));
        assertEquals(List.of(element), factory.released);
        assertFalse(elements.forget(7), "a second destruction of the same node must find nothing");
        assertEquals(1, factory.released.size());
    }

    @Test
    void aForgottenNodeIsMintedAfreshRatherThanResurrected() {
        RecordingFactory factory = new RecordingFactory();
        AxElements elements = registry(factory);
        long first = elements.elementFor(7);
        elements.forget(7);
        // The old pointer is dangling. Handing it out again would be a message to freed memory,
        // which is the crash §13.9 calls the obvious vector.
        assertNotEquals(first, elements.elementFor(7));
    }

    @Test
    void reconcileReleasesExactlyTheNodesNoLongerInTheTree() {
        RecordingFactory factory = new RecordingFactory();
        AxElements elements = registry(factory);
        long kept = elements.elementFor(1);
        long gone = elements.elementFor(2);
        long alsoGone = elements.elementFor(3);
        assertEquals(2, elements.reconcile(Set.of(1L, 4L)));
        assertEquals(Set.of(gone, alsoGone), Set.copyOf(factory.released));
        assertTrue(elements.holds(1));
        assertFalse(elements.holds(2));
        assertEquals(kept, elements.elementFor(1), "the surviving node keeps its object");
    }

    @Test
    void reconcileAgainstTheSameTreeReleasesNothing() {
        RecordingFactory factory = new RecordingFactory();
        AxElements elements = registry(factory);
        elements.elementFor(1);
        elements.elementFor(2);
        assertEquals(0, elements.reconcile(Set.of(1L, 2L, 3L)));
        assertTrue(factory.released.isEmpty());
    }

    @Test
    void emptyingReleasesEverythingAndLeavesNothingBehind() {
        RecordingFactory factory = new RecordingFactory();
        AxElements elements = registry(factory);
        elements.elementFor(1);
        elements.elementFor(2);
        elements.elementFor(3);
        assertEquals(3, elements.empty());
        assertEquals(3, factory.released.size());
        assertEquals(0, elements.size());
        assertEquals(0, elements.empty(), "a second empty has nothing to do and must not double-release");
    }

    @Test
    void everyEntryPointRefusesAThreadThatIsNotTheOwner() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        AxElements elements = registry(factory);
        elements.elementFor(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread other = new Thread(() -> {
            // Each of these would be a silent data race rather than an exception if the map were
            // merely unsynchronised, which is the whole reason the assertion is here.
            thrown.set(assertThrows(IllegalStateException.class, () -> elements.elementFor(2)));
            assertThrows(IllegalStateException.class, () -> elements.forget(1));
            assertThrows(IllegalStateException.class, () -> elements.reconcile(Set.of()));
            assertThrows(IllegalStateException.class, elements::empty);
            assertThrows(IllegalStateException.class, () -> elements.holds(1));
            assertThrows(IllegalStateException.class, elements::size);
        }, "not-the-ui-thread");
        other.start();
        other.join();
        assertTrue(thrown.get().getMessage().contains("not-the-ui-thread"),
                "the message must name the offending thread, because this failure is otherwise a "
                        + "puzzle: " + thrown.get().getMessage());
        assertEquals(1, elements.size(), "the refused calls must not have changed anything");
        assertTrue(factory.released.isEmpty());
    }
}

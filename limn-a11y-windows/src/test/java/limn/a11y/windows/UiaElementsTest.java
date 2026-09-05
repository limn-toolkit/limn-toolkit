package limn.a11y.windows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules ADR 039 §3.4 assigns the Windows registry, asserted where they can be: one element per
 * identifier however many threads ask at once, one release per element however many callers remove
 * it, and a pointer that stops resolving the moment its node's element is gone.
 *
 * <p>None of this needs COM. What the registry owns is identity and lifetime, and both are rules
 * about maps and threads — which is the whole reason the element is an interface here rather than
 * a pointer with methods hung off it.
 */
class UiaElementsTest {

    /** What a real element will be, minus the COM object: an id, a pointer, and one release. */
    private static final class FakeElement implements UiaElement {
        private final long nodeId;
        private final long pointer;
        final AtomicInteger releases = new AtomicInteger();

        FakeElement(long nodeId, long pointer) {
            this.nodeId = nodeId;
            this.pointer = pointer;
        }

        @Override
        public long nodeId() {
            return nodeId;
        }

        @Override
        public long pointer() {
            return pointer;
        }

        @Override
        public void release() {
            releases.incrementAndGet();
        }
    }

    private final AtomicLong nextPointer = new AtomicLong(0x1000);
    private final AtomicInteger minted = new AtomicInteger();

    private UiaElement mint(long nodeId) {
        minted.incrementAndGet();
        return new FakeElement(nodeId, nextPointer.getAndAdd(0x40));
    }

    /**
     * The same, taking the moment that making a COM object takes.
     *
     * <p>Without it the race below is a coin toss: a registry that reads, mints and writes as three
     * steps still passes most runs, because eight threads released at once mostly do not interleave
     * inside three field accesses. Real minting is not free, and this is what makes the case fail
     * every time on a registry that has the defect rather than two runs in three.
     */
    private UiaElement slowMint(long nodeId) {
        UiaElement made = mint(nodeId);
        try {
            Thread.sleep(5);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return made;
    }

    @Test
    void oneNodeGetsOneElementAndItIsReachableBothWays() {
        UiaElements registry = new UiaElements();

        UiaElement first = registry.forNode(7, this::mint);
        UiaElement again = registry.forNode(7, this::mint);

        assertSame(first, again, "a second navigation to the same node is the same element");
        assertEquals(1, minted.get(), "and the second ask does not build one to throw away");
        assertSame(first, registry.byPointer(first.pointer()),
                "a call arrives on the pointer and has to find the object behind it");
        assertSame(first, registry.peek(7));
        assertEquals(1, registry.size());
    }

    /**
     * The race §3.4 calls unsurvivable: the provider is entered from up to three RPC threads at
     * once with no lock taken on our behalf, and an element is made the first time a client
     * navigates to a node. Two threads reaching one unvisited node must not produce two objects —
     * each client would then hold a different element for the same node.
     */
    @Test
    void threadsRacingToTheSameUnvisitedNodeGetOneElement() throws Exception {
        UiaElements registry = new UiaElements();
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<UiaElement> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> racers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread racer = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                seen.add(registry.forNode(42, this::slowMint));
            }, "rpc-" + i);
            racers.add(racer);
            racer.start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "the racers did not start");
        go.countDown();
        for (Thread racer : racers) {
            racer.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(racer.isAlive(), "a racer is still parked in the registry");
        }

        assertEquals(threads, seen.size());
        for (UiaElement element : seen) {
            assertSame(seen.get(0), element,
                    "two objects for one node hand two clients different elements for the same "
                            + "thing, and neither of them is wrong to hold on to it");
        }
        assertEquals(1, minted.get(), "computeIfAbsent parks the losers rather than racing them");
        assertEquals(1, registry.size());
    }

    /**
     * The whole of the ownership protocol: whoever gets the non-null value from {@code remove}
     * releases, so a double removal is a removal and no ordering argument has to be re-derived
     * where it happens.
     */
    @Test
    void removalReleasesOnceAndSaysWhoDidIt() {
        UiaElements registry = new UiaElements();
        FakeElement element = (FakeElement) registry.forNode(3, this::mint);

        assertTrue(registry.remove(3), "the first caller is the one that released it");
        assertFalse(registry.remove(3), "and the second is told it was not");

        assertEquals(1, element.releases.get(), "released exactly once");
        assertEquals(0, registry.size());
    }

    @Test
    void aRemovedElementsPointerStopsResolving() {
        UiaElements registry = new UiaElements();
        UiaElement element = registry.forNode(3, this::mint);
        long pointer = element.pointer();

        registry.remove(3);

        assertNull(registry.byPointer(pointer),
                "a call arriving on a stale pointer has to be recognisable as one: the element "
                        + "answers UIA_E_ELEMENTNOTAVAILABLE until the client's own reference "
                        + "drops, and it cannot answer that if the bridge still thinks it is live");
        assertNull(registry.peek(3));
    }

    @Test
    void theWholeRegistryEmptyReleasesEveryElementExactlyOnce() {
        UiaElements registry = new UiaElements();
        List<FakeElement> made = new ArrayList<>();
        for (long id = 0; id < 50; id++) {
            made.add((FakeElement) registry.forNode(id, this::mint));
        }

        registry.empty();

        assertEquals(0, registry.size());
        for (FakeElement element : made) {
            assertEquals(1, element.releases.get(),
                    "the empty runs on the user-interface thread with the drain stopped, so it is "
                            + "the only remover and every element is its to release");
            assertNull(registry.byPointer(element.pointer()));
        }
    }

    /**
     * An empty over a registry the drain has already been through: the two removers are kept apart
     * by stopping the drain first, but a node it removed on its way out must not be released twice
     * when the empty walks the rest.
     */
    @Test
    void anEmptyAfterTheDrainHasRemovedSomeReleasesNeitherTwice() {
        UiaElements registry = new UiaElements();
        FakeElement announced = (FakeElement) registry.forNode(1, this::mint);
        FakeElement leftBehind = (FakeElement) registry.forNode(2, this::mint);

        assertTrue(registry.remove(1));
        registry.empty();

        assertEquals(1, announced.releases.get());
        assertEquals(1, leftBehind.releases.get());
        assertEquals(0, registry.size());
    }

    @Test
    void anEmptyRegistryAnswersNothingRatherThanThrowing() {
        UiaElements registry = new UiaElements();

        assertNull(registry.peek(1));
        assertNull(registry.byPointer(0x1000));
        assertFalse(registry.remove(1));
        registry.empty();

        assertEquals(0, registry.size());
    }

    @Test
    void mintingAfterAnEmptyStartsCleanRatherThanFindingTheOldElement() {
        UiaElements registry = new UiaElements();
        UiaElement before = registry.forNode(9, this::mint);
        registry.empty();

        UiaElement after = registry.forNode(9, this::mint);

        assertNotNull(after);
        assertEquals(2, minted.get(), "the node was navigated to again, so it is made again");
        assertSame(after, registry.byPointer(after.pointer()));
        assertNull(registry.byPointer(before.pointer()),
                "and the old pointer stays stale, because a client may still be holding it");
    }
}

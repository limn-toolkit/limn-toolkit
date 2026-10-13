package limn.concurrent.internal;

import limn.concurrent.UiRuntime;

import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shared-load rule on its own, apart from the images and sounds that use it: one future per
 * key while it is in flight or landed, a failure forgotten so the next ask retries,
 * {@code clear()} forgetting the rest, and what landed kept within a budget: the longest unasked
 * let go first, a path before a resource, a pinned one never, and nothing that somebody still
 * holds ever loaded a second time.
 */
class SharedLoadsTest {

    private HeadlessUi ui;
    private UiRuntime runtime;

    @BeforeEach
    void setUp() {
        ui = new HeadlessUi();
        runtime = ui.runtime();
    }

    @AfterEach
    void tearDown() {
        ui.close();
    }

    private void pumpUntil(BooleanSupplier done) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.getAsBoolean()) {
            runtime.drain();
            if (System.nanoTime() > deadline) {
                fail("timed out pumping the UI queue");
            }
        }
    }

    @Test
    void oneKeyIsOneFutureAndOneRunOfTheLoader() {
        SharedLoads<String> loads = new SharedLoads<>(String::length, Long.MAX_VALUE);
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<String> first = loads.load("a", SharedLoads.Source.FILE, false, () -> "v" + runs.incrementAndGet());
        CompletableFuture<String> second = loads.load("a", SharedLoads.Source.FILE, false, () -> "v" + runs.incrementAndGet());
        assertSame(first, second, "the second ask joins the first");
        pumpUntil(first::isDone);
        assertEquals("v1", first.join());
        assertEquals(1, runs.get());
        assertSame(first, loads.load("a", SharedLoads.Source.FILE, false, () -> "never"), "landed, and still shared");
        assertNotSame(first, loads.load("b", SharedLoads.Source.FILE, false, () -> "other"), "a different key is a different load");
    }

    @Test
    void aFailureIsForgottenSoTheNextAskRetries() {
        SharedLoads<String> loads = new SharedLoads<>(String::length, Long.MAX_VALUE);
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<String> failed = loads.load("a", SharedLoads.Source.FILE, false, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom (expected in this test)");
        });
        pumpUntil(failed::isDone);
        assertTrue(failed.isCompletedExceptionally());
        CompletableFuture<String> retried = loads.load("a", SharedLoads.Source.FILE, false, () -> "v" + runs.incrementAndGet());
        assertNotSame(failed, retried, "the failed future is not what the next ask gets");
        pumpUntil(retried::isDone);
        assertEquals("v2", retried.join());
    }

    @Test
    void clearForgetsTheSuccessfulOnesToo() {
        SharedLoads<String> loads = new SharedLoads<>(String::length, Long.MAX_VALUE);
        CompletableFuture<String> first = loads.load("a", SharedLoads.Source.FILE, false, () -> "v1");
        pumpUntil(first::isDone);
        loads.clear();
        CompletableFuture<String> again = loads.load("a", SharedLoads.Source.FILE, false, () -> "v2");
        assertNotSame(first, again);
        pumpUntil(again::isDone);
        assertEquals("v2", again.join());
    }

    /**
     * A load still in flight when its backend shuts down completes on a queue nothing drains any
     * more. The cache outlived the backend, so the next backend asking for the same key was handed
     * that future and waited on it forever.
     */
    @Test
    void aLoadInFlightWhenTheBackendStopsIsNotHandedToTheNextOne() throws InterruptedException {
        SharedLoads<String> loads = new SharedLoads<>(String::length, Long.MAX_VALUE);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<String> stranded = loads.load("a", SharedLoads.Source.FILE, false, () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "v1";
        });
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        release.countDown();
        ui.close(); // the worker finishes, but the completion is posted to a queue now dead

        ui = new HeadlessUi();
        runtime = ui.runtime();
        CompletableFuture<String> fresh = loads.load("a", SharedLoads.Source.FILE, false, () -> "v2");
        assertNotSame(stranded, fresh, "the new backend starts its own load");
        pumpUntil(fresh::isDone);
        assertEquals("v2", fresh.join());
    }

    /** A result of a size the test names, and nothing equal to it but itself. */
    private static final class Sized {
        final long bytes;

        Sized(long bytes) {
            this.bytes = bytes;
        }
    }

    private Sized land(SharedLoads<Sized> loads, String key, SharedLoads.Source source, boolean pinned,
            AtomicInteger runs) {
        CompletableFuture<Sized> future = loads.load(key, source, pinned, () -> {
            runs.incrementAndGet();
            return new Sized(100);
        });
        pumpUntil(future::isDone);
        return future.join();
    }

    private static void collect(java.lang.ref.WeakReference<?> gone) {
        for (int i = 0; i < 50 && gone.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void pastTheBudgetTheLongestUnaskedIsLetGoAndReadAgainOnlyWhenNobodyHoldsIt() {
        SharedLoads<Sized> loads = new SharedLoads<>(sized -> sized.bytes, 250);
        AtomicInteger runs = new AtomicInteger();
        Sized a = land(loads, "a", SharedLoads.Source.FILE, false, runs);
        Sized b = land(loads, "b", SharedLoads.Source.FILE, false, runs);
        assertEquals(200, loads.heldBytes());
        assertSame(a, land(loads, "a", SharedLoads.Source.FILE, false, runs), "asked again: b is now the older");
        land(loads, "c", SharedLoads.Source.FILE, false, runs);
        assertEquals(200, loads.heldBytes(), "three do not fit, so one was let go");
        assertEquals(3, runs.get());

        assertSame(b, land(loads, "b", SharedLoads.Source.FILE, false, runs),
                "let go and still held here: the same object, and nothing read");
        assertEquals(3, runs.get());

        // Now a is the oldest and was let go for b; with no holder left it is gone, and read again.
        java.lang.ref.WeakReference<Sized> gone = new java.lang.ref.WeakReference<>(a);
        a = null;
        collect(gone);
        assertEquals(null, gone.get(), "what the budget let go and nobody holds is collected");
        land(loads, "a", SharedLoads.Source.FILE, false, runs);
        assertEquals(4, runs.get());
    }

    @Test
    void aResourceOutlastsEveryPathHoweverLongAgoItWasAsked() {
        SharedLoads<Sized> loads = new SharedLoads<>(sized -> sized.bytes, 250);
        AtomicInteger runs = new AtomicInteger();
        land(loads, "icon", SharedLoads.Source.RESOURCE, false, runs);
        land(loads, "photo-1", SharedLoads.Source.FILE, false, runs);
        land(loads, "photo-2", SharedLoads.Source.FILE, false, runs);
        land(loads, "photo-3", SharedLoads.Source.FILE, false, runs);
        assertEquals(200, loads.heldBytes());
        int before = runs.get();
        land(loads, "icon", SharedLoads.Source.RESOURCE, false, runs);
        assertEquals(before, runs.get(), "the resource, the oldest of all, was not the one let go");

        loads.setBudget(50);
        assertEquals(0, loads.heldBytes(), "with no path left to let go, a resource goes");
    }

    @Test
    void aPinnedLoadIsKeptWhateverTheBudgetAndCountsNothingUntilUnpinned() {
        SharedLoads<Sized> loads = new SharedLoads<>(sized -> sized.bytes, 0);
        AtomicInteger runs = new AtomicInteger();
        java.lang.ref.WeakReference<Sized> pinned =
                new java.lang.ref.WeakReference<>(land(loads, "logo", SharedLoads.Source.FILE, true, runs));
        java.lang.ref.WeakReference<Sized> shared =
                new java.lang.ref.WeakReference<>(land(loads, "photo", SharedLoads.Source.FILE, false, runs));
        assertEquals(0, loads.heldBytes());
        collect(shared);
        assertEquals(null, shared.get(), "a budget of nothing keeps nothing");
        assertTrue(pinned.get() != null, "but the pinned one stays");
        land(loads, "logo", SharedLoads.Source.FILE, false, runs);
        assertEquals(2, runs.get(), "and is not read again, asked for pinned or not");

        loads.unpin("logo");
        collect(pinned);
        assertEquals(null, pinned.get(), "unpinned, it answers to the budget like any other");
    }

    @Test
    void aLoadAlreadySharedIsPinnedFromTheAskThatPinsIt() {
        SharedLoads<Sized> loads = new SharedLoads<>(sized -> sized.bytes, 150);
        AtomicInteger runs = new AtomicInteger();
        Sized first = land(loads, "a", SharedLoads.Source.FILE, false, runs);
        assertEquals(100, loads.heldBytes());
        assertSame(first, land(loads, "a", SharedLoads.Source.FILE, true, runs));
        assertEquals(0, loads.heldBytes(), "pinned, it is outside the budget");
        land(loads, "b", SharedLoads.Source.FILE, false, runs);
        land(loads, "c", SharedLoads.Source.FILE, false, runs);
        assertSame(first, land(loads, "a", SharedLoads.Source.FILE, false, runs));
        assertEquals(3, runs.get());
    }
}

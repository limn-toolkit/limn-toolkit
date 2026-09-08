package limn.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shared-load rule on its own, apart from the images and sounds that use it: one future per
 * key while it is in flight or landed, a failure forgotten so the next ask retries, and
 * {@code clear()} forgetting the rest.
 */
class SharedLoadsTest {

    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    @AfterEach
    void tearDown() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
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
        SharedLoads<String> loads = new SharedLoads<>();
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<String> first = loads.load("a", () -> "v" + runs.incrementAndGet());
        CompletableFuture<String> second = loads.load("a", () -> "v" + runs.incrementAndGet());
        assertSame(first, second, "the second ask joins the first");
        pumpUntil(first::isDone);
        assertEquals("v1", first.join());
        assertEquals(1, runs.get());
        assertSame(first, loads.load("a", () -> "never"), "landed, and still shared");
        assertNotSame(first, loads.load("b", () -> "other"), "a different key is a different load");
    }

    @Test
    void aFailureIsForgottenSoTheNextAskRetries() {
        SharedLoads<String> loads = new SharedLoads<>();
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<String> failed = loads.load("a", () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom (expected in this test)");
        });
        pumpUntil(failed::isDone);
        assertTrue(failed.isCompletedExceptionally());
        CompletableFuture<String> retried = loads.load("a", () -> "v" + runs.incrementAndGet());
        assertNotSame(failed, retried, "the failed future is not what the next ask gets");
        pumpUntil(retried::isDone);
        assertEquals("v2", retried.join());
    }

    @Test
    void clearForgetsTheSuccessfulOnesToo() {
        SharedLoads<String> loads = new SharedLoads<>();
        CompletableFuture<String> first = loads.load("a", () -> "v1");
        pumpUntil(first::isDone);
        loads.clear();
        CompletableFuture<String> again = loads.load("a", () -> "v2");
        assertNotSame(first, again);
        pumpUntil(again::isDone);
        assertEquals("v2", again.join());
    }
}

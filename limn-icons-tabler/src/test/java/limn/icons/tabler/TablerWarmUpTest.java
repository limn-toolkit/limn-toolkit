package limn.icons.tabler;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The pack's asynchronous form. The JUnit thread plays the UI thread and pumps frames by hand,
 * the way the toolkit's own concurrency tests do.
 *
 * <p>What cannot be asserted here is that the warm-up is what did the reading: the index and the
 * blob are process-wide static state, and any test that ran before this one may have loaded them.
 * So these pin the contract a caller depends on (unstarted until {@code start()}, delivered on
 * the UI thread, pack readable afterwards) rather than counting reads.
 */
class TablerWarmUpTest {

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

    @Test
    void warmingUpDeliversOnTheUiThreadAndLeavesThePackReadable() {
        AtomicReference<Thread> deliveredOn = new AtomicReference<>();
        AtomicReference<Double> lastProgress = new AtomicReference<>(0.0);

        Tabler.warmUpAsync()
                .onProgress(lastProgress::set)
                .onSuccess(nothing -> deliveredOn.set(Thread.currentThread()))
                .onFailure(error -> fail("warming the pack must not fail: " + error))
                .start();

        pumpUntil(() -> deliveredOn.get() != null);
        assertSame(Thread.currentThread(), deliveredOn.get(),
                "onSuccess must land on the UI thread");
        assertTrue(lastProgress.get() > 0, "progress must reach the handler before onSuccess");
        assertNotNull(Tabler.outline("trash"), "the pack must be readable after a warm-up");
    }

    /**
     * The silent half of the {@code Async} contract: a description that is built and dropped does
     * nothing. Pinned here as well as in the toolkit because this facade is the one an application
     * calls at startup, where a warm-up that quietly never ran looks exactly like one that did.
     */
    @Test
    void warmUpIsADescriptionUntilItIsStarted() {
        AtomicBoolean delivered = new AtomicBoolean();
        Tabler.warmUpAsync().onSuccess(nothing -> delivered.set(true));

        for (int frame = 0; frame < 20; frame++) {
            runtime.drain();
        }
        assertFalse(delivered.get(), "an unstarted Work must deliver nothing");
    }

    private void pumpUntil(BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out pumping the UI queue");
            }
            runtime.drain();
            Thread.onSpinWait();
        }
        runtime.drain();
    }
}

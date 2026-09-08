package limn.testing;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * A {@link UiRuntime} bound to the calling thread for the life of a test, so that the thread
 * confinement every widget asserts holds on the JUnit thread, and the worker pool and queue
 * behind {@link Ui} exist to be drained.
 *
 * <p>Open it in {@code @BeforeEach}, close it in {@code @AfterEach}. Twenty-five test classes
 * wrote the four lines this constructor is and the two lines {@link #close()} is, most of them
 * beside a base class that already had them; this is the pair once, with the clock as the one
 * thing a test chooses.
 */
public final class HeadlessUi implements AutoCloseable {

    private final ExecutorService workers;
    private final UiRuntime runtime;

    /** A runtime on the wall clock. */
    public HeadlessUi() {
        this(System::nanoTime);
    }

    /**
     * A runtime on a clock the test controls.
     *
     * @param nanos what the runtime reads as now
     */
    public HeadlessUi(LongSupplier nanos) {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(nanos, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    /** @return the runtime installed as {@link Ui}'s */
    public UiRuntime runtime() {
        return runtime;
    }

    /** @return the worker pool behind {@link Ui#async} */
    public ExecutorService workers() {
        return workers;
    }

    /**
     * Spins the UI queue, the way a backend's loop does, until the condition holds.
     *
     * @param done what to wait for
     * @param timeoutNanos how long to keep spinning
     * @throws AssertionError if the condition does not hold in time
     */
    public void pumpUntil(BooleanSupplier done, long timeoutNanos) {
        long deadline = System.nanoTime() + timeoutNanos;
        while (!done.getAsBoolean()) {
            runtime.drain();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out pumping the UI queue");
            }
        }
    }

    /** {@link #pumpUntil(BooleanSupplier, long)} with a five-second limit. */
    public void pumpUntil(BooleanSupplier done) {
        pumpUntil(done, 5_000_000_000L);
    }

    /** Uninstalls the runtime and stops the workers. */
    @Override
    public void close() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }
}

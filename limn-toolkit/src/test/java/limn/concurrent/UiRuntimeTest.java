package limn.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests of Limn's threading contract. The JUnit thread plays the role of
 * the UI thread; a manual clock drives the delayed queue deterministically.
 */
class UiRuntimeTest {

    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger wakeUps = new AtomicInteger();
    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(2);
        runtime = new UiRuntime(clock::get, wakeUps::incrementAndGet, workers);
        runtime.bindToCurrentThread();
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    // ---------------------------------------------------------------- post

    @Test
    void postRunsInFifoOrderOnDrain() {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int value = i;
            runtime.post(() -> order.add(value));
        }
        assertEquals(5, runtime.drain());
        assertEquals(List.of(0, 1, 2, 3, 4), order);
    }

    @Test
    void drainRunsOnlyTheSnapshotSoRepostingTasksCannotLiveLock() {
        AtomicInteger executions = new AtomicInteger();
        Runnable reposting = new Runnable() {
            @Override
            public void run() {
                executions.incrementAndGet();
                runtime.post(this);
            }
        };
        runtime.post(reposting);

        assertEquals(1, runtime.drain());
        assertEquals(1, executions.get());
        assertTrue(runtime.hasPendingTasks(), "the repost must wait for the next frame");
        assertEquals(1, runtime.drain());
        assertEquals(2, executions.get());
    }

    @Test
    void postFromManyThreadsKeepsPerThreadOrderAndRunsEverythingOnUiThread() throws Exception {
        int threadCount = 8;
        int tasksPerThread = 200;
        Thread uiThread = Thread.currentThread();
        List<int[]> executed = new ArrayList<>(); // [threadIndex, sequence]
        List<Thread> executionThreads = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            int threadIndex = t;
            Thread producer = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int sequence = 0; sequence < tasksPerThread; sequence++) {
                    int seq = sequence;
                    runtime.post(() -> {
                        executionThreads.add(Thread.currentThread());
                        executed.add(new int[] {threadIndex, seq});
                    });
                }
            }, "producer-" + t);
            producers.add(producer);
            producer.start();
        }
        start.countDown();
        for (Thread producer : producers) {
            producer.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(producer.isAlive(), "producer did not finish");
        }

        int totalRan = 0;
        while (runtime.hasPendingTasks()) {
            totalRan += runtime.drain();
        }
        assertEquals(threadCount * tasksPerThread, totalRan);

        Map<Integer, Integer> lastSequenceByThread = new ConcurrentHashMap<>();
        for (int[] entry : executed) {
            int previous = lastSequenceByThread.getOrDefault(entry[0], -1);
            assertTrue(entry[1] > previous,
                    "per-thread FIFO violated for producer " + entry[0] + ": " + entry[1] + " after " + previous);
            lastSequenceByThread.put(entry[0], entry[1]);
        }
        for (Thread executionThread : executionThreads) {
            assertSame(uiThread, executionThread, "task ran off the UI thread");
        }
    }

    @Test
    void postFromBackgroundThreadWakesTheLoopButUiThreadPostDoesNot() throws Exception {
        runtime.post(() -> { });
        assertEquals(0, wakeUps.get(), "UI-thread post must not need a wake-up");

        Thread background = new Thread(() -> runtime.post(() -> { }), "background-poster");
        background.start();
        background.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(wakeUps.get() >= 1, "background post must wake the native loop");
    }

    @Test
    void taskExceptionsAreContainedAndDoNotAbortTheDrain() {
        List<String> ran = new ArrayList<>();
        runtime.post(() -> {
            throw new RuntimeException("boom (expected, must be logged not thrown)");
        });
        runtime.post(() -> ran.add("after"));

        assertEquals(2, runtime.drain());
        assertEquals(List.of("after"), ran);
    }

    @Test
    void taskExceptionsAreReportedToTheCrashHandler() {
        List<limn.backend.CrashPhase> phases = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        limn.backend.CrashHandler handler = (phase, error) -> {
            phases.add(phase);
            errors.add(error);
            return true;
        };
        limn.backend.Crashes.install(handler);
        try {
            RuntimeException boom = new RuntimeException("boom (expected, must be logged not thrown)");
            runtime.post(() -> {
                throw boom;
            });
            runtime.drain();
            assertEquals(List.of(limn.backend.CrashPhase.TASK), phases);
            assertEquals(List.of(boom), errors);
        } finally {
            limn.backend.Crashes.uninstall(handler);
        }
    }

    // --------------------------------------------------------- postDelayed

    @Test
    void postDelayedRunsOnlyAfterItsDeadline() {
        List<String> ran = new ArrayList<>();
        runtime.postDelayed(() -> ran.add("delayed"), 10);

        assertEquals(0, runtime.drain(), "before the deadline nothing runs");
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), runtime.nanosUntilNextDeadline());

        clock.set(TimeUnit.MILLISECONDS.toNanos(10) - 1);
        assertEquals(0, runtime.drain(), "one nanosecond early is still early");

        clock.set(TimeUnit.MILLISECONDS.toNanos(10));
        assertEquals(1, runtime.drain());
        assertEquals(List.of("delayed"), ran);
    }

    @Test
    void delayedTasksRunInDeadlineOrderWithFifoTieBreak() {
        List<String> ran = new ArrayList<>();
        runtime.postDelayed(() -> ran.add("late"), 20);
        runtime.postDelayed(() -> ran.add("early"), 10);
        runtime.postDelayed(() -> ran.add("late-second"), 20);

        clock.set(TimeUnit.MILLISECONDS.toNanos(20));
        assertEquals(3, runtime.drain());
        assertEquals(List.of("early", "late", "late-second"), ran);
    }

    @Test
    void aDelayTooLargeToAddToTheClockDoesNotStrandTheQueueBehindIt() {
        // nanoTime's origin is arbitrary and routinely far from zero, and toNanos saturates at
        // Long.MAX_VALUE above ~292 years, so postDelayed(action, Long.MAX_VALUE), the idiomatic
        // "never", used to wrap its deadline negative. The queue orders by the deadline itself, so
        // that task sorted ahead of every real one, and the drain stops at the first task that is
        // not due: one "never" and no delayed task in this runtime ran again.
        clock.set(TimeUnit.HOURS.toNanos(1));
        List<String> ran = new ArrayList<>();
        runtime.postDelayed(() -> ran.add("never"), Long.MAX_VALUE);
        runtime.postDelayed(() -> ran.add("soon"), 10);

        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), runtime.nanosUntilNextDeadline(),
                "the loop sleeps until the deadline that exists, not until the one that wrapped");

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        assertEquals(1, runtime.drain());
        assertEquals(List.of("soon"), ran, "and 'never' means never, not now");
    }

    @Test
    void nanosUntilNextDeadlineReflectsQueueState() {
        assertEquals(-1, runtime.nanosUntilNextDeadline(), "idle loop may sleep forever");

        runtime.postDelayed(() -> { }, 5);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(5), runtime.nanosUntilNextDeadline());

        runtime.post(() -> { });
        assertEquals(0, runtime.nanosUntilNextDeadline(), "immediate work forbids sleeping");
    }

    // --------------------------------------------------------------- async

    @Test
    void asyncRunsOnWorkerAndCompletesCallbacksOnUiThread() {
        Thread uiThread = Thread.currentThread();
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        CompletableFuture<String> stage = runtime.async(() -> {
            workerThread.set(Thread.currentThread());
            return "value";
        });
        stage.thenAccept(value -> {
            assertEquals("value", value);
            callbackThread.set(Thread.currentThread());
        });

        pumpUntil(() -> callbackThread.get() != null);
        assertNotNull(workerThread.get());
        assertNotEquals(uiThread, workerThread.get(), "supplier must run on the worker pool");
        assertSame(uiThread, callbackThread.get(), "callback must land on the UI thread");
    }

    @Test
    void asyncFailureArrivesOnUiThreadWithOriginalCause() {
        Thread uiThread = Thread.currentThread();
        IllegalStateException boom = new IllegalStateException("boom");
        AtomicReference<Throwable> seen = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        runtime.async(() -> {
            throw boom;
        }).whenComplete((value, error) -> {
            seen.set(error);
            callbackThread.set(Thread.currentThread());
        });

        pumpUntil(() -> callbackThread.get() != null);
        assertSame(uiThread, callbackThread.get());
        assertSame(boom, unwrap(seen.get()), "original exception must be preserved");
    }

    @Test
    void asyncDefaultAsyncExecutorIsTheUiThread() {
        Thread uiThread = Thread.currentThread();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        runtime.async(() -> 42).thenAcceptAsync(value -> callbackThread.set(Thread.currentThread()));

        pumpUntil(() -> callbackThread.get() != null);
        assertSame(uiThread, callbackThread.get(),
                "thenAcceptAsync without executor must default to the UI executor");
    }

    @Test
    void uiExecutorRunsSubmissionsOnTheUiThread() {
        Thread uiThread = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        runtime.uiExecutor().execute(() -> ranOn.set(Thread.currentThread()));
        runtime.drain();
        assertSame(uiThread, ranOn.get());
    }

    // ----------------------------------------------------- thread confinement

    @Test
    void checkUiThreadPassesOnTheUiThread() {
        runtime.checkUiThread();
        assertTrue(runtime.isUiThread());
    }

    @Test
    void checkUiThreadThrowsWithClearMessageFromOtherThreads() throws Exception {
        IllegalStateException error = workers.submit(() ->
                assertThrows(IllegalStateException.class, runtime::checkUiThread)
        ).get(10, TimeUnit.SECONDS);

        assertTrue(error.getMessage().contains("UI thread"), error.getMessage());
        assertTrue(error.getMessage().contains("Ui.post"),
                "message should point to the fix: " + error.getMessage());

        assertFalse(workers.submit(runtime::isUiThread).get(10, TimeUnit.SECONDS));
    }

    @Test
    void drainIsUiThreadOnly() throws Exception {
        workers.submit(() -> assertThrows(IllegalStateException.class, runtime::drain))
                .get(10, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------- helpers

    /** Simulates the backend loop: drain until the condition holds (5s cap). */
    private void pumpUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not reached within 5s");
            }
            runtime.drain();
            Thread.onSpinWait();
        }
    }

    private static Throwable unwrap(Throwable error) {
        assertNotNull(error);
        if (error instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return error;
    }
}

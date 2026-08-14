package limn.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests of {@link Work}'s contract. The JUnit thread plays the role of the UI
 * thread and pumps frames by hand, so every ordering assertion here is about
 * what a real frame loop would see rather than about how long something took.
 */
class WorkTest {

    private final AtomicLong clock = new AtomicLong();
    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(2);
        runtime = new UiRuntime(clock::get, () -> { }, workers);
        runtime.bindToCurrentThread();
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    // --------------------------------------- 1. which thread runs what

    @Test
    void bodyRunsOnTheWorkerPoolAndEveryDeliveryOnTheUiThread() {
        Thread uiThread = Thread.currentThread();
        AtomicReference<Thread> bodyThread = new AtomicReference<>();
        AtomicReference<Thread> progressThread = new AtomicReference<>();
        AtomicReference<Thread> successThread = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();

        runtime.work(progress -> {
            bodyThread.set(Thread.currentThread());
            progress.report(0.5);
            return "value";
        }).onProgress(fraction -> progressThread.set(Thread.currentThread()))
                .onSuccess(value -> {
                    received.set(value);
                    successThread.set(Thread.currentThread());
                })
                .start();

        pumpUntil(() -> successThread.get() != null);
        assertEquals("value", received.get());
        assertNotNull(bodyThread.get());
        assertNotSame(uiThread, bodyThread.get(), "the body must not run on the UI thread");
        assertSame(uiThread, successThread.get(), "onSuccess must land on the UI thread");
        assertSame(uiThread, progressThread.get(), "onProgress must land on the UI thread");
    }

    // ------------------------------- 2. cancellation is cooperative

    @Test
    void aBodyCancelledBeforeThePoolReachesItNeverRuns() throws Exception {
        ExecutorService single = Executors.newFixedThreadPool(1);
        UiRuntime own = new UiRuntime(clock::get, () -> { }, single);
        own.bindToCurrentThread();
        try {
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            single.execute(() -> {
                blocked.countDown();
                await(release);
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS), "the pool's only thread never took the blocker");

            AtomicBoolean bodyRan = new AtomicBoolean();
            Job job = own.work(progress -> {
                bodyRan.set(true);
                return "value";
            }).onSuccess(value -> fail("a cancelled job must deliver nothing")).start();

            job.cancel();
            release.countDown();

            // One FIFO thread: this barrier cannot run before the cancelled job's
            // runnable has been taken off the queue and decided what to do.
            single.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertFalse(bodyRan.get(), "a job cancelled before the pool reached it must not run at all");
            assertTrue(job.isDone(), "a job skipped because it was cancelled is still done");
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void cancellingARunningBodyNeitherInterruptsItNorHidesTheCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean sawCancelled = new AtomicBoolean();
        AtomicBoolean sawInterrupt = new AtomicBoolean();

        Job job = runtime.work(progress -> {
            entered.countDown();
            await(cancelled);
            sawInterrupt.set(Thread.currentThread().isInterrupted());
            sawCancelled.set(progress.isCancelled());
            finished.countDown();
            return "value";
        }).start();

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        job.cancel();
        cancelled.countDown();

        assertTrue(finished.await(5, TimeUnit.SECONDS), "the body must keep running after cancel()");
        assertFalse(sawInterrupt.get(), "cancellation must never interrupt the worker thread");
        assertTrue(sawCancelled.get(), "the body must be able to see the cancellation it was not interrupted by");
    }

    // ------------------------- 3. a cancelled job delivers nothing

    @Test
    void aCancelledJobDeliversNothingNotEvenProgressAlreadyPosted() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch discarded = new CountDownLatch(1);
        List<String> events = new ArrayList<>();

        Job job = runtime.work(progress -> {
            progress.report(0.5);
            reported.countDown();
            await(release);
            return "value";
        }).onProgress(fraction -> events.add("progress"))
                .onSuccess(value -> events.add("success"))
                .onFailure(error -> events.add("failure"))
                .onDiscarded(value -> discarded.countDown())
                .start();

        assertTrue(reported.await(5, TimeUnit.SECONDS));
        assertTrue(runtime.hasPendingTasks(), "the progress delivery is queued and has not been drained yet");

        job.cancel();
        release.countDown();

        assertTrue(discarded.await(5, TimeUnit.SECONDS), "the undelivered value must reach onDiscarded");
        pumpFrames(5);
        assertEquals(List.of(), events,
                "a cancelled job delivers nothing: a queued delivery must re-check the flag when it runs");
        assertTrue(job.isCancelled());
        assertTrue(job.isDone());
    }

    // ------------------------------------------------------ 4. deliverIf

    @Test
    void deliverIfFalseDropsEveryDeliveryAndIsAskedOnTheUiThread() {
        Thread uiThread = Thread.currentThread();
        AtomicReference<Thread> askedOn = new AtomicReference<>();
        AtomicInteger asked = new AtomicInteger();
        CountDownLatch discarded = new CountDownLatch(1);
        List<String> events = new ArrayList<>();

        Job job = runtime.work(progress -> {
            progress.report(1);
            return "value";
        }).onProgress(fraction -> events.add("progress"))
                .onSuccess(value -> events.add("success"))
                .onDiscarded(value -> discarded.countDown())
                .deliverIf(() -> {
                    askedOn.set(Thread.currentThread());
                    asked.incrementAndGet();
                    return false;
                })
                .start();

        pumpUntilLatch(discarded);
        pumpFrames(5);
        assertEquals(List.of(), events, "deliverIf false must drop the progress and the result alike");
        assertEquals(2, asked.get(), "deliverIf is asked once per delivery: the progress and the result");
        assertSame(uiThread, askedOn.get(), "deliverIf must be asked on the UI thread");
        assertFalse(job.isCancelled(), "declining a delivery is not cancellation");
    }

    @Test
    void deliverIfTrueDelivers() {
        List<String> events = new ArrayList<>();
        runtime.work(progress -> "value")
                .onSuccess(events::add)
                .deliverIf(() -> true)
                .start();

        pumpUntil(() -> !events.isEmpty());
        assertEquals(List.of("value"), events);
    }

    // ---------------------------------------------------- 5. onDiscarded

    @Test
    void onDiscardedReceivesTheDroppedValueOffTheUiThread() {
        Thread uiThread = Thread.currentThread();
        AtomicReference<Thread> disposalThread = new AtomicReference<>();
        AtomicReference<String> disposed = new AtomicReference<>();
        CountDownLatch discarded = new CountDownLatch(1);

        runtime.work(progress -> "open-stream")
                .onSuccess(value -> fail("deliverIf said the requester is gone"))
                .deliverIf(() -> false)
                .onDiscarded(value -> {
                    disposed.set(value);
                    disposalThread.set(Thread.currentThread());
                    discarded.countDown();
                })
                .start();

        pumpUntilLatch(discarded);
        assertEquals("open-stream", disposed.get(), "the value nobody received must reach the disposer");
        assertNotSame(uiThread, disposalThread.get(),
                "disposal may block, so it must not run on the UI thread");
    }

    @Test
    void aWithdrawnResultIsDisposedEvenWhenNoSuccessHandlerWasRegistered() {
        // What lets a facade attach the disposer on the caller's behalf: Videos.openAsync
        // closes a withdrawn container, and it cannot know whether its caller registered
        // an onSuccess. If disposal were conditional on one, that guard would be a guard
        // only for callers who happened not to need it.
        AtomicReference<String> disposed = new AtomicReference<>();
        CountDownLatch discarded = new CountDownLatch(1);

        runtime.work(progress -> "open-container")
                .deliverIf(() -> false)
                .onDiscarded(value -> {
                    disposed.set(value);
                    discarded.countDown();
                })
                .start();

        pumpUntilLatch(discarded);
        assertEquals("open-container", disposed.get(),
                "a refused delivery must dispose whether or not anyone asked for the value");
    }

    @Test
    void aFailureHasNoValueToDiscard() {
        AtomicBoolean discardedAnything = new AtomicBoolean();
        List<Throwable> failures = new ArrayList<>();

        runtime.work(progress -> {
            throw new IllegalStateException("boom (expected, must reach onFailure)");
        }).onFailure(failures::add)
                .onDiscarded(value -> discardedAnything.set(true))
                .start();

        pumpUntil(() -> !failures.isEmpty());
        pumpFrames(5);
        assertFalse(discardedAnything.get(), "there is no value to dispose of when the body threw");
    }

    // ------------------------------------------------------- 6. progress

    @Test
    void progressIsCoalescedToTheNewestUndeliveredValue() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Double> seen = new ArrayList<>();

        runtime.work(progress -> {
            progress.report(0.1);
            progress.report(0.2);
            progress.report(0.3);
            reported.countDown();
            await(release);
            return "value";
        }).onProgress(seen::add).onSuccess(value -> { }).start();

        assertTrue(reported.await(5, TimeUnit.SECONDS));
        runtime.drain();
        assertEquals(List.of(0.3), seen, "three reports between two frames deliver once, carrying the newest");

        release.countDown();
        pumpFrames(5);
        assertEquals(List.of(0.3), seen, "a value already delivered is not delivered again");
    }

    @Test
    void progressArrivesInOrderAndTheLastValuePrecedesSuccess() {
        List<String> events = new ArrayList<>();

        runtime.work(progress -> {
            for (int step = 1; step <= 50; step++) {
                progress.report(step / 50.0);
            }
            return "value";
        }).onProgress(fraction -> events.add("progress " + fraction))
                .onSuccess(value -> events.add("success"))
                .start();

        pumpUntil(() -> events.contains("success"));
        assertTrue(events.size() >= 2, "at least one progress value must have been delivered: " + events);
        assertEquals("success", events.get(events.size() - 1), "nothing may follow the result");
        assertEquals("progress 1.0", events.get(events.size() - 2),
                "the last value reported before the body returned must arrive, and arrive before onSuccess");

        double previous = -1;
        for (String event : events) {
            if (event.startsWith("progress ")) {
                double value = Double.parseDouble(event.substring("progress ".length()));
                assertTrue(value > previous, "progress went backwards: " + value + " after " + previous);
                previous = value;
            }
        }
    }

    @Test
    void progressIsClampedAndNaNReportsZero() {
        assertEquals(0.0, deliveredProgressFor(-5));
        assertEquals(1.0, deliveredProgressFor(42));
        assertEquals(0.0, deliveredProgressFor(Double.NaN));
        assertEquals(0.25, deliveredProgressFor(0.25));
    }

    // -------------------------------------------------------- 7. failure

    @Test
    void aCheckedExceptionReachesOnFailureAndNotOnSuccess() {
        IOException boom = new IOException("boom (expected, must reach onFailure)");
        AtomicReference<Throwable> seen = new AtomicReference<>();
        AtomicReference<Thread> failureThread = new AtomicReference<>();
        AtomicBoolean succeeded = new AtomicBoolean();

        runtime.work(progress -> {
            throw boom;
        }).onSuccess(value -> succeeded.set(true))
                .onFailure(error -> {
                    seen.set(error);
                    failureThread.set(Thread.currentThread());
                })
                .start();

        pumpUntil(() -> seen.get() != null);
        assertSame(boom, seen.get(), "the throwable must arrive unwrapped");
        assertSame(Thread.currentThread(), failureThread.get(), "onFailure must land on the UI thread");
        pumpFrames(5);
        assertFalse(succeeded.get(), "a body that threw must not reach the success path");
    }

    @Test
    void aFailureWithNoHandlerIsReportedAsATaskCrash() {
        List<limn.backend.CrashPhase> phases = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        limn.backend.CrashHandler handler = (phase, error) -> {
            phases.add(phase);
            errors.add(error);
            return true;
        };
        limn.backend.Crashes.install(handler);
        try {
            RuntimeException boom = new RuntimeException("boom (expected, must be reported not swallowed)");
            runtime.work(progress -> {
                throw boom;
            }).start();

            pumpUntil(() -> !phases.isEmpty());
            assertEquals(List.of(limn.backend.CrashPhase.TASK), phases);
            assertEquals(List.of(boom), errors);
        } finally {
            limn.backend.Crashes.uninstall(handler);
        }
    }

    /**
     * The class contract's "at most one terminal callback, never both and never twice", from the
     * success side (the failure side is covered by the checked-exception test above). Frames are
     * pumped well past the delivery so a second one would have somewhere to land.
     */
    @Test
    void aSuccessfulJobRunsOnSuccessExactlyOnceAndNeverOnFailure() {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        runtime.work(progress -> {
            progress.report(0.5);
            return "value";
        }).onProgress(fraction -> { })
                .onSuccess(value -> successes.incrementAndGet())
                .onFailure(error -> failures.incrementAndGet())
                .start();

        pumpUntil(() -> successes.get() > 0);
        pumpFrames(10);
        assertEquals(1, successes.get(), "onSuccess runs exactly once");
        assertEquals(0, failures.get(), "a job that succeeded must never also fail");
    }

    // --------------------------------------------------------- 8. isDone

    @Test
    void isDoneBecomesTrueEvenWhenNothingIsDelivered() {
        Job job = runtime.work(progress -> "value")
                .deliverIf(() -> false)
                .start();

        pumpUntil(job::isDone);
        assertTrue(job.isDone());
        assertFalse(job.isCancelled(), "finishing is not cancelling");
    }

    // ------------------------------------------------------- the builder

    @Test
    void startTwiceThrows() {
        Work<String> work = runtime.work(progress -> "value");
        work.start();
        assertThrows(IllegalStateException.class, work::start);
    }

    @Test
    void aSetterReplacesItsHandlerRatherThanAddingToIt() {
        List<String> events = new ArrayList<>();
        runtime.work(progress -> "value")
                .onSuccess(value -> events.add("first"))
                .onSuccess(value -> events.add("second"))
                .start();

        pumpUntil(() -> !events.isEmpty());
        pumpFrames(5);
        assertEquals(List.of("second"), events);
    }

    @Test
    void startIsCallableFromAnyThread() throws Exception {
        List<String> events = new ArrayList<>();
        Work<String> work = runtime.work(progress -> "value").onSuccess(events::add);

        Thread starter = new Thread(work::start, "off-ui-starter");
        starter.start();
        starter.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(starter.isAlive(), "start() must not block");

        pumpUntil(() -> !events.isEmpty());
        assertEquals(List.of("value"), events);
    }

    /**
     * The forgotten {@code start()}: a description built, configured and dropped. The warning
     * itself has nowhere to be observed from ({@code System.Logger} offers no capture point), so
     * the counter beside it stands in for the message.
     *
     * <p>Driven by garbage collection, so it is a loop with a deadline rather than one
     * {@code System.gc()}: the collection is a request, and the cleaner reports on its own thread
     * some time after the reference is enqueued.
     */
    @Test
    void aDescriptionDroppedWithoutStartReportsItself() {
        long before = Work.neverStartedCount.get();

        // In a method of its own so the local cannot stay live on this frame's stack.
        buildAndAbandon();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Work.neverStartedCount.get() == before) {
            if (System.nanoTime() > deadline) {
                fail("a Work collected without start() must report itself");
            }
            System.gc();
            Thread.onSpinWait();
        }
    }

    private void buildAndAbandon() {
        runtime.work(progress -> "value").onSuccess(value -> fail("nothing was started"));
    }

    /** A started description must never be reported, however long it lives after the job ran. */
    @Test
    void aStartedDescriptionIsNeverReported() {
        long before = Work.neverStartedCount.get();
        List<String> events = new ArrayList<>();

        runtime.work(progress -> "value").onSuccess(events::add).start();
        pumpUntil(() -> !events.isEmpty());

        for (int attempt = 0; attempt < 20; attempt++) {
            System.gc();
            Thread.onSpinWait();
        }
        assertEquals(before, Work.neverStartedCount.get(),
                "a description that ran must not be reported as never started");
    }

    // ------------------------------------------------------------ helpers

    /** Runs one job that reports {@code raw} exactly once and answers what the handler saw. */
    private double deliveredProgressFor(double raw) {
        List<Double> seen = new ArrayList<>();
        runtime.work(progress -> {
            progress.report(raw);
            return "value";
        }).onProgress(seen::add).onSuccess(value -> { }).start();

        pumpUntil(() -> !seen.isEmpty());
        return seen.get(seen.size() - 1);
    }

    /** Simulates the backend loop: drain until the condition holds (5s cap). */
    private void pumpUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not reached within 5s");
            }
            runtime.drain();
            Thread.onSpinWait();
        }
        runtime.drain();
    }

    /** Pumps frames until {@code latch} fires, for effects that land on a worker thread. */
    private void pumpUntilLatch(CountDownLatch latch) {
        pumpUntil(() -> {
            try {
                return latch.await(2, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            }
        });
    }

    private void pumpFrames(int frames) {
        for (int frame = 0; frame < frames; frame++) {
            runtime.drain();
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released within 5s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", interrupted);
        }
    }
}

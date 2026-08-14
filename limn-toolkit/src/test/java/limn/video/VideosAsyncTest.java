package limn.video;

import limn.concurrent.Job;
import limn.concurrent.Progress;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link Videos#openAsync} and {@link Videos#warmUpAsync}: the open runs on a worker, the source
 * arrives on the UI thread, every way an open can fail arrives there too rather than as a stack
 * trace on a worker, and an open nobody takes is closed instead of leaked.
 *
 * <p>The JUnit thread plays the UI thread and pumps frames by hand, so the assertions are about
 * what a frame loop would see rather than about how long anything took.
 */
class VideosAsyncTest {

    private static final Path CLIP = Path.of("clip.mkv");

    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void setUp() {
        Videos.uninstallAllDecoders();
        workers = Executors.newFixedThreadPool(2);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    @AfterEach
    void tearDown() {
        Videos.uninstallAllDecoders();
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    // ------------------------------------------------- which thread does what

    @Test
    void opensOnAWorkerAndDeliversTheSourceOnTheUiThread() {
        FakeDecoder decoder = new FakeDecoder("a", true);
        Videos.installDecoder(decoder);

        AtomicReference<VideoStreamSource> delivered = new AtomicReference<>();
        AtomicReference<Thread> deliveryThread = new AtomicReference<>();
        Videos.openAsync(CLIP)
                .onSuccess(source -> {
                    delivered.set(source);
                    deliveryThread.set(Thread.currentThread());
                })
                .onFailure(error -> fail("the open must not fail: " + error))
                .start();

        pumpUntil(() -> delivered.get() != null);
        assertSame(decoder.source, delivered.get());
        assertSame(Thread.currentThread(), deliveryThread.get(), "onSuccess must land on the UI thread");
        assertNotNull(decoder.openThread);
        assertNotSame(Thread.currentThread(), decoder.openThread, "the open must not run on the UI thread");
        assertNotSame(Thread.currentThread(), decoder.supportsThread,
                "the probe belongs inside the body: a first supports() may link a native library");
        assertFalse(decoder.source.closed, "a delivered source belongs to the caller and must stay open");
    }

    @Test
    void nothingIsProbedUntilStart() {
        FakeDecoder decoder = new FakeDecoder("a", true);
        Videos.installDecoder(decoder);

        Videos.openAsync(CLIP).onSuccess(source -> fail("an unstarted description must not run"));
        for (int frame = 0; frame < 3; frame++) {
            runtime.drain();
        }

        assertEquals(0, decoder.supportsCalls.get(), "supports() must not run at the call site");
        assertEquals(0, decoder.oneArgOpens.get() + decoder.twoArgOpens.get());
    }

    // ------------------------------------------- every failure is a delivery

    @Test
    void noDecoderInstalledArrivesThroughOnFailure() {
        assertFalse(Videos.isDecoderInstalled());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> failureThread = new AtomicReference<>();
        Videos.openAsync(CLIP)
                .onSuccess(source -> fail("there is nothing to open with"))
                .onFailure(error -> {
                    failure.set(error);
                    failureThread.set(Thread.currentThread());
                })
                .start();

        pumpUntil(() -> failure.get() != null);
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(failure.get().getMessage().contains("No VideoDecoder installed"),
                failure.get().getMessage());
        assertSame(Thread.currentThread(), failureThread.get(), "onFailure must land on the UI thread");
    }

    @Test
    void noneAcceptingArrivesThroughOnFailureAndStillNamesEveryDecoderAsked() {
        Videos.installDecoder(new FakeDecoder("a", false));
        Videos.installDecoder(new FakeDecoder("b", false));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Videos.openAsync(CLIP)
                .onSuccess(source -> fail("nothing claimed the input"))
                .onFailure(failure::set)
                .start();

        pumpUntil(() -> failure.get() != null);
        assertInstanceOf(UnsupportedOperationException.class, failure.get());
        assertTrue(failure.get().getMessage().contains("(tried, in order: a, b)"),
                failure.get().getMessage());
        assertTrue(failure.get().getMessage().contains(CLIP.toString()), failure.get().getMessage());
    }

    @Test
    void whatTheAcceptingDecoderThrewArrivesThroughOnFailureUnwrapped() {
        FakeDecoder accepting = new FakeDecoder("a", true);
        accepting.failure = new IllegalArgumentException("frame 0 is not a keyframe");
        FakeDecoder behind = new FakeDecoder("b", true);
        Videos.installDecoder(accepting);
        Videos.installDecoder(behind);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> failureThread = new AtomicReference<>();
        Videos.openAsync(CLIP)
                .onSuccess(source -> fail("the open threw"))
                .onFailure(error -> {
                    failure.set(error);
                    failureThread.set(Thread.currentThread());
                })
                .start();

        pumpUntil(() -> failure.get() != null);
        assertSame(accepting.failure, failure.get(),
                "the decoder's own throwable, not a wrapper around it");
        assertSame(Thread.currentThread(), failureThread.get(),
                "a failed open is a delivery, not a stack trace on a worker");
        assertEquals(0, behind.supportsCalls.get(), "no later decoder is tried after one accepts");
    }

    // ------------------------------------------------ nothing is left open

    @Test
    void aCancelledOpenClosesTheSourceItProduced() throws Exception {
        HeldDecoder decoder = new HeldDecoder("a");
        Videos.installDecoder(decoder);

        Job job = Videos.openAsync(CLIP)
                .onSuccess(source -> fail("a cancelled job delivers nothing"))
                .onFailure(error -> fail("a cancelled job reports nothing: " + error))
                .start();

        assertTrue(decoder.entered.await(5, TimeUnit.SECONDS), "the body never reached the open");
        job.cancel();
        decoder.release.countDown();

        pumpUntilLatch(decoder.source.closedLatch);
        assertTrue(decoder.source.closed, "the source the cancelled open produced must be closed");
        assertNotSame(Thread.currentThread(), decoder.source.closeThread,
                "closing can block, so it happens on a worker and not on the UI thread");
        for (int frame = 0; frame < 3; frame++) {
            runtime.drain();
        }
    }

    @Test
    void aWithdrawnDeliveryClosesTheSourceItProduced() {
        FakeDecoder decoder = new FakeDecoder("a", true);
        Videos.installDecoder(decoder);

        Videos.openAsync(CLIP)
                .onSuccess(source -> fail("the requester was gone"))
                .deliverIf(() -> false)
                .start();

        pumpUntilLatch(decoder.source.closedLatch);
        assertTrue(decoder.source.closed,
                "a source refused on the UI thread crosses back to the pool to be closed");
    }

    // ------------------------------------------------------ the SPI overload

    @Test
    void theTwoArgumentOverloadIsTheOneCalledAndCarriesTheJobsProgress() {
        OverridingDecoder decoder = new OverridingDecoder("a");
        Videos.installDecoder(decoder);

        AtomicReference<VideoStreamSource> delivered = new AtomicReference<>();
        Videos.openAsync(CLIP).onSuccess(delivered::set).start();

        pumpUntil(() -> delivered.get() != null);
        assertEquals(1, decoder.twoArgOpens.get(), "openAsync must call the overload");
        assertEquals(0, decoder.oneArgOpens.get(), "an override must not be bypassed");
        assertNotNull(decoder.seenProgress, "the overload is handed the job's own Progress");
        assertFalse(decoder.seenCancelled, "a job nobody cancelled is not cancelled");
    }

    @Test
    void aDecoderThatIgnoresTheOverloadStillOpens() {
        FakeDecoder legacy = new FakeDecoder("a", true); // implements openStream(Path) only
        Videos.installDecoder(legacy);

        AtomicReference<VideoStreamSource> delivered = new AtomicReference<>();
        Videos.openAsync(CLIP).onSuccess(delivered::set).start();

        pumpUntil(() -> delivered.get() != null);
        assertSame(legacy.source, delivered.get());
        assertEquals(1, legacy.oneArgOpens.get(),
                "the default overload delegates, so a decoder written before it keeps working");
    }

    @Test
    void aDecoderThatAbandonsWhileCancelledSaysNothingEitherWay() throws Exception {
        HeldDecoder decoder = new HeldDecoder("a");
        decoder.abandonWhenCancelled = true;
        Videos.installDecoder(decoder);

        Job job = Videos.openAsync(CLIP)
                .onSuccess(source -> fail("nothing was opened"))
                .onFailure(error -> fail("abandoning is not a failure: " + error))
                .start();

        assertTrue(decoder.entered.await(5, TimeUnit.SECONDS));
        job.cancel();
        decoder.release.countDown();

        pumpUntil(job::isDone);
        for (int frame = 0; frame < 3; frame++) {
            runtime.drain();
        }
        assertFalse(decoder.source.closed, "an abandoned open opened nothing to close");
    }

    @Test
    void aDecoderThatReturnsNothingWithoutBeingCancelledIsReportedAsTheDefectItIs() {
        OverridingDecoder decoder = new OverridingDecoder("a");
        decoder.returnNull = true;
        Videos.installDecoder(decoder);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Videos.openAsync(CLIP)
                .onSuccess(source -> fail("null must never reach a success handler"))
                .onFailure(failure::set)
                .start();

        pumpUntil(() -> failure.get() != null);
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(failure.get().getMessage().contains("a"), failure.get().getMessage());
        assertTrue(failure.get().getMessage().contains("not cancelled"), failure.get().getMessage());
    }

    // ------------------------------------------------------------ warming up

    @Test
    void warmUpAsyncPreparesEveryInstalledDecoderOffTheUiThread() {
        FakeDecoder first = new FakeDecoder("a", true);
        FakeDecoder second = new FakeDecoder("b", true);
        Videos.installDecoder(first);
        Videos.installDecoder(second);

        List<Double> fractions = new ArrayList<>();
        AtomicInteger finished = new AtomicInteger();
        Videos.warmUpAsync()
                .onProgress(fractions::add)
                .onSuccess(ignored -> finished.incrementAndGet())
                .onFailure(error -> fail("a warm-up reports no failure: " + error))
                .start();

        pumpUntil(() -> finished.get() == 1);
        assertEquals(1, first.warmUps.get());
        assertEquals(1, second.warmUps.get());
        assertNotSame(Thread.currentThread(), first.warmUpThread,
                "warming links libraries, so it must not run on the UI thread");
        assertFalse(fractions.isEmpty(), "progress runs across the installed decoders");
        assertEquals(1.0, fractions.get(fractions.size() - 1), 1e-9,
                "the last value reported before the body returns is always delivered");
    }

    @Test
    void aWarmUpThatThrowsNeitherStopsTheOthersNorFailsTheJob() {
        FakeDecoder broken = new FakeDecoder("a", true);
        broken.warmUpFailure = new UnsatisfiedLinkError("no build for this platform");
        FakeDecoder behind = new FakeDecoder("b", true);
        Videos.installDecoder(broken);
        Videos.installDecoder(behind);

        AtomicInteger finished = new AtomicInteger();
        Videos.warmUpAsync()
                .onSuccess(ignored -> finished.incrementAndGet())
                .onFailure(error -> fail("a decoder that cannot prepare itself is not a job failure: " + error))
                .start();

        pumpUntil(() -> finished.get() == 1);
        assertEquals(1, behind.warmUps.get(),
                "a decoder behind a broken one is still warmed");
    }

    @Test
    void nullFileThrowsAtTheCallSiteRatherThanOnAWorker() {
        assertThrows(NullPointerException.class, () -> Videos.openAsync(null));
    }

    // ------------------------------------------------------------- machinery

    /** Spins the UI queue the way the backend loop does, until the condition holds. */
    private void pumpUntil(BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out pumping the UI queue");
            }
            runtime.drain();
            Thread.onSpinWait();
        }
        runtime.drain();
    }

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

    /**
     * A decoder that claims whatever it was built to claim and implements the one-argument open
     * only, which is what every decoder written before the overload existed looks like.
     */
    private static class FakeDecoder implements VideoDecoder {

        final String name;
        final boolean claims;
        final ClosingSource source = new ClosingSource();
        final AtomicInteger supportsCalls = new AtomicInteger();
        final AtomicInteger oneArgOpens = new AtomicInteger();
        final AtomicInteger twoArgOpens = new AtomicInteger();
        final AtomicInteger warmUps = new AtomicInteger();

        volatile Thread supportsThread;
        volatile Thread openThread;
        volatile Thread warmUpThread;
        volatile RuntimeException failure;
        volatile Error warmUpFailure;

        FakeDecoder(String name, boolean claims) {
            this.name = name;
            this.claims = claims;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean supports(Path file) {
            supportsCalls.incrementAndGet();
            supportsThread = Thread.currentThread();
            return claims;
        }

        @Override
        public VideoStreamSource openStream(Path file) {
            oneArgOpens.incrementAndGet();
            openThread = Thread.currentThread();
            if (failure != null) {
                throw failure;
            }
            return source;
        }

        @Override
        public void warmUp() {
            warmUps.incrementAndGet();
            warmUpThread = Thread.currentThread();
            if (warmUpFailure != null) {
                throw warmUpFailure;
            }
        }
    }

    /** Overrides the overload, and records the {@link Progress} the facade handed it. */
    private static final class OverridingDecoder extends FakeDecoder {

        volatile Progress seenProgress;
        volatile boolean seenCancelled;
        volatile boolean returnNull;

        OverridingDecoder(String name) {
            super(name, true);
        }

        @Override
        public VideoStreamSource openStream(Path file, Progress progress) {
            twoArgOpens.incrementAndGet();
            openThread = Thread.currentThread();
            seenProgress = progress;
            seenCancelled = progress.isCancelled();
            progress.report(0.5);
            return returnNull ? null : source;
        }
    }

    /** Held inside the open until the test releases it: an open that cannot be interrupted. */
    private static final class HeldDecoder extends FakeDecoder {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean abandonWhenCancelled;

        HeldDecoder(String name) {
            super(name, true);
        }

        @Override
        public VideoStreamSource openStream(Path file, Progress progress) {
            twoArgOpens.incrementAndGet();
            openThread = Thread.currentThread();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the open");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the open was interrupted, which nothing may do");
            }
            if (abandonWhenCancelled && progress.isCancelled()) {
                return null;
            }
            return source;
        }
    }

    /** Enough of a source to be produced, handed over and closed; it decodes nothing. */
    private static final class ClosingSource implements VideoStreamSource {

        final CountDownLatch closedLatch = new CountDownLatch(1);
        volatile boolean closed;
        volatile Thread closeThread;

        @Override
        public int width() {
            return 16;
        }

        @Override
        public int height() {
            return 16;
        }

        @Override
        public PixelFormat pixelFormat() {
            return PixelFormat.I420;
        }

        @Override
        public VideoColor color() {
            return VideoColor.unspecified();
        }

        @Override
        public int frameRateNum() {
            return 0;
        }

        @Override
        public int frameRateDen() {
            return 1;
        }

        @Override
        public Read readFrame() {
            return Read.END;
        }

        @Override
        public VideoFrame frame() {
            return null;
        }

        @Override
        public void reset() {
        }

        @Override
        public void close() {
            closed = true;
            closeThread = Thread.currentThread();
            closedLatch.countDown();
        }
    }
}

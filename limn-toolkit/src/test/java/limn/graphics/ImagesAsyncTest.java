package limn.graphics;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Background image loading: decode happens on a worker, the done-observer runs
 * on the UI thread, loads deduplicate per source, and failures are observable
 * and retryable.
 */
class ImagesAsyncTest {

    /** Counts decodes and records the thread they ran on. */
    private static final class CountingDecoder implements ImageDecoder {
        final AtomicInteger decodes = new AtomicInteger();
        volatile Thread lastThread;
        volatile boolean failNext;

        @Override
        public Image decode(byte[] fileBytes) {
            decodes.incrementAndGet();
            lastThread = Thread.currentThread();
            if (failNext) {
                failNext = false;
                throw new RuntimeException("decode boom (expected in this test)");
            }
            return new Image(1, 1, new byte[4]);
        }
    }

    private ExecutorService workers;
    private UiRuntime runtime;
    private final CountingDecoder decoder = new CountingDecoder();

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Images.installDecoder(decoder);
        Images.clearSharedCache(); // static state: isolate from other tests
    }

    @AfterEach
    void tearDown() {
        Images.clearSharedCache();
        Images.uninstallDecoder(decoder);
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    /** Spins the UI queue (like the backend loop) until the condition holds. */
    private void pumpUntil(BooleanSupplier done) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.getAsBoolean()) {
            runtime.drain();
            if (System.nanoTime() > deadline) {
                fail("timed out pumping the UI queue");
            }
        }
    }

    private Path tempImage() throws Exception {
        Path file = Files.createTempFile("limn-async", ".png");
        Files.write(file, new byte[]{1, 2, 3});
        file.toFile().deleteOnExit();
        return file;
    }

    @Test
    void decodesOffThreadAndObserverRunsOnUiThread() throws Exception {
        AtomicReference<Thread> observerThread = new AtomicReference<>();
        AtomicReference<Image> delivered = new AtomicReference<>();
        Images.loadShared(tempImage()).thenAccept(image -> {
            observerThread.set(Thread.currentThread());
            delivered.set(image);
        });

        pumpUntil(() -> delivered.get() != null);
        assertSame(Thread.currentThread(), observerThread.get(),
                "the done-observer must run on the UI thread");
        assertNotSame(Thread.currentThread(), decoder.lastThread,
                "the decode must run on a worker, not the UI thread");
    }

    @Test
    void sameSourceLoadsOnceAndSharesTheFutureAndImage() throws Exception {
        Path file = tempImage();
        CompletableFuture<Image> first = Images.loadShared(file);
        CompletableFuture<Image> second = Images.loadShared(file);
        assertSame(first, second, "in-flight loads of the same source must share the future");

        pumpUntil(first::isDone);
        assertSame(first, Images.loadShared(file), "completed loads stay cached");
        assertEquals(1, decoder.decodes.get());
        assertSame(first.join(), Images.loadShared(file).join(),
                "every caller shares the same Image instance (texture-cache identity)");
    }

    @Test
    void failuresReachTheObserverAndAreRetryable() throws Exception {
        Path file = tempImage();
        decoder.failNext = true;
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CompletableFuture<Image> failed = Images.loadShared(file);
        failed.whenComplete((image, error) -> observed.set(error));

        pumpUntil(() -> observed.get() != null);
        assertTrue(observed.get().getCause() instanceof RuntimeException
                        || observed.get() instanceof RuntimeException,
                "the failure must reach the observer");

        // The failed entry was evicted: a new call retries and succeeds.
        CompletableFuture<Image> retry = Images.loadShared(file);
        assertNotSame(failed, retry);
        pumpUntil(retry::isDone);
        assertEquals(2, decoder.decodes.get());
        assertTrue(!retry.isCompletedExceptionally());
    }

    @Test
    void clearSharedCacheForcesAFreshLoad() throws Exception {
        Path file = tempImage();
        CompletableFuture<Image> first = Images.loadShared(file);
        pumpUntil(first::isDone);

        Images.clearSharedCache();
        CompletableFuture<Image> reloaded = Images.loadShared(file);
        assertNotSame(first, reloaded);
        pumpUntil(reloaded::isDone);
        assertEquals(2, decoder.decodes.get());
    }

    @Test
    void decodeAsyncIsUncached() {
        byte[] bytes = {9, 9, 9};
        AtomicReference<Image> first = new AtomicReference<>();
        AtomicReference<Image> second = new AtomicReference<>();
        Images.decodeAsync(bytes).onSuccess(first::set).start();
        Images.decodeAsync(bytes).onSuccess(second::set).start();

        pumpUntil(() -> first.get() != null && second.get() != null);
        assertNotSame(first.get(), second.get(),
                "bytes have no name to de-duplicate by: two calls are two pictures");
        assertEquals(2, decoder.decodes.get());
    }

    /**
     * The suffix rule this facade documents, from the caller's side: a name ending in
     * {@code Async} hands back a description that does nothing until it is started. The wrong edit
     * this guards is turning one of them back into a job that runs on call, which no existing
     * assertion would notice: every other test here starts what it builds.
     */
    @Test
    void anAsyncFormDoesNothingUntilItIsStarted() {
        AtomicReference<Image> delivered = new AtomicReference<>();
        Images.decodeAsync(new byte[]{4, 4, 4}).onSuccess(delivered::set);

        for (int frame = 0; frame < 20; frame++) {
            runtime.drain();
        }
        assertEquals(0, decoder.decodes.get(), "an unstarted Work must not have run its body");
        assertNull(delivered.get(), "an unstarted Work must deliver nothing");
    }
}

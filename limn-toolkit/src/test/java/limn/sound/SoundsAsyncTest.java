package limn.sound;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Background audio loading: decode on a worker, done-observer on the UI
 * thread, per-source dedup, retryable failures: the audio twin of
 * {@code ImagesAsyncTest}.
 */
class SoundsAsyncTest {

    private static final class CountingDecoder implements AudioDecoder {
        final AtomicInteger decodes = new AtomicInteger();
        volatile Thread lastThread;

        @Override
        public AudioClip decode(byte[] fileBytes) {
            decodes.incrementAndGet();
            lastThread = Thread.currentThread();
            return AudioClip.tone(440, 0.01f, 0.5f);
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
        Sounds.installDecoder(decoder);
        Sounds.clearSharedCache();
    }

    @AfterEach
    void tearDown() {
        Sounds.clearSharedCache();
        Sounds.uninstallDecoder(decoder);
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

    private Path tempClip() throws Exception {
        Path file = Files.createTempFile("limn-async", ".wav");
        Files.write(file, new byte[]{1, 2, 3});
        file.toFile().deleteOnExit();
        return file;
    }

    @Test
    void decodesOffThreadAndObserverRunsOnUiThread() throws Exception {
        AtomicReference<Thread> observerThread = new AtomicReference<>();
        AtomicReference<AudioClip> delivered = new AtomicReference<>();
        Sounds.loadShared(tempClip()).thenAccept(clip -> {
            observerThread.set(Thread.currentThread());
            delivered.set(clip);
        });

        pumpUntil(() -> delivered.get() != null);
        assertSame(Thread.currentThread(), observerThread.get());
        assertNotSame(Thread.currentThread(), decoder.lastThread);
    }

    @Test
    void sameSourceLoadsOnceAndSharesTheClip() throws Exception {
        Path file = tempClip();
        CompletableFuture<AudioClip> first = Sounds.loadShared(file);
        assertSame(first, Sounds.loadShared(file));

        pumpUntil(first::isDone);
        assertSame(first.join(), Sounds.loadShared(file).join());
        assertEquals(1, decoder.decodes.get());
    }

    /** An engine whose availability check is the expensive first call, counted here. */
    private static final class LazyEngine implements AudioEngine {
        final AtomicInteger checks = new AtomicInteger();
        volatile Thread lastThread;

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            return Playback.NONE;
        }

        @Override
        public boolean isAvailable() {
            checks.incrementAndGet();
            lastThread = Thread.currentThread();
            return true;
        }
    }

    @Test
    void warmUpOpensTheDeviceOffTheUiThreadAndAnswersOnIt() {
        LazyEngine engine = new LazyEngine();
        Sounds.installEngine(engine);
        try {
            AtomicReference<Thread> answeredOn = new AtomicReference<>();
            AtomicReference<Boolean> answer = new AtomicReference<>();
            Sounds.warmUpAsync().onSuccess(available -> {
                answeredOn.set(Thread.currentThread());
                answer.set(available);
            }).start();

            pumpUntil(() -> answer.get() != null);
            assertEquals(Boolean.TRUE, answer.get());
            assertSame(Thread.currentThread(), answeredOn.get());
            assertNotSame(Thread.currentThread(), engine.lastThread,
                    "the device open is what warming up moves off the UI thread");
        } finally {
            Sounds.uninstallEngine(engine);
        }
    }

    /**
     * The one shape rule, asserted rather than described: like every other asynchronous form here,
     * a warm-up that is never started does nothing, which is what makes {@code warmUpAsync();} on
     * a line of its own a mistake and not a shorthand.
     */
    @Test
    void warmUpDoesNothingUntilItIsStarted() {
        LazyEngine engine = new LazyEngine();
        Sounds.installEngine(engine);
        try {
            Sounds.warmUpAsync().onSuccess(available -> fail("an unstarted description must not run"));

            for (int frame = 0; frame < 5; frame++) {
                runtime.drain();
                Thread.onSpinWait();
            }
            assertEquals(0, engine.checks.get(), "nothing may reach the engine before start()");
        } finally {
            Sounds.uninstallEngine(engine);
        }
    }

    @Test
    void warmUpWithoutAnEngineAnswersFalseRatherThanThrowing() {
        AtomicReference<Boolean> answer = new AtomicReference<>();
        Sounds.warmUpAsync().onSuccess(answer::set).start();

        pumpUntil(() -> answer.get() != null);
        assertEquals(Boolean.FALSE, answer.get());
    }

    @Test
    void clearSharedCacheForcesAFreshLoad() throws Exception {
        Path file = tempClip();
        CompletableFuture<AudioClip> first = Sounds.loadShared(file);
        pumpUntil(first::isDone);

        Sounds.clearSharedCache();
        CompletableFuture<AudioClip> reloaded = Sounds.loadShared(file);
        assertNotSame(first, reloaded);
        pumpUntil(reloaded::isDone);
        assertEquals(2, decoder.decodes.get());
    }
}

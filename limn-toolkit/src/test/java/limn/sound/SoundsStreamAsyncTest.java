package limn.sound;

import limn.concurrent.Job;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * {@link Sounds#streamAsync}: the file opens on a worker, the handle arrives on the UI thread, and
 * a caller who withdraws never ends up with music nobody can stop or a file nobody closes. The
 * JUnit thread plays the UI thread and pumps frames by hand.
 */
class SoundsStreamAsyncTest {

    /** Counts its own closes: the assertion behind every withdrawal case here. */
    private static final class CountingSource implements AudioStreamSource {
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public int channels() {
            return 2;
        }

        @Override
        public int sampleRate() {
            return 44_100;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            return maxFrames;
        }

        @Override
        public void reset() {
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    /** Takes the source and keeps it, closing it on stop the way a real engine does. */
    private static final class StreamingEngine implements AudioEngine {
        volatile boolean available = true;
        final AtomicInteger stops = new AtomicInteger();
        volatile AudioStreamSource taken;

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            return Playback.NONE;
        }

        @Override
        public Playback playStream(AudioStreamSource source, PlayOptions options) {
            taken = source;
            return new Playback() {
                @Override
                public void stop() {
                    stops.incrementAndGet();
                    source.close();
                }

                @Override
                public boolean isPlaying() {
                    return true;
                }

                @Override
                public void setGain(float gain) {
                }
            };
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

    /** Opens sources, optionally blocking inside the open so a cancel can land mid-body. */
    private static final class OpeningDecoder implements AudioDecoder {
        final AtomicInteger opens = new AtomicInteger();
        final List<CountingSource> sources = Collections.synchronizedList(new ArrayList<>());
        volatile Thread lastThread;
        volatile CountDownLatch gate;
        volatile CountDownLatch entered;

        @Override
        public AudioClip decode(byte[] fileBytes) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public AudioStreamSource openStream(Path file) {
            lastThread = Thread.currentThread();
            opens.incrementAndGet();
            if (entered != null) {
                entered.countDown();
            }
            CountDownLatch waitFor = gate;
            if (waitFor != null) {
                try {
                    if (!waitFor.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate never opened");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            CountingSource source = new CountingSource();
            sources.add(source);
            return source;
        }
    }

    private static final Path TRACK = Path.of("music.ogg");

    private ExecutorService workers;
    private UiRuntime runtime;
    private final OpeningDecoder decoder = new OpeningDecoder();
    private final StreamingEngine engine = new StreamingEngine();

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(2);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Sounds.installDecoder(decoder);
        Sounds.installEngine(engine);
    }

    @AfterEach
    void tearDown() {
        Sounds.uninstallEngine(engine);
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

    @Test
    void opensOnAWorkerAndDeliversTheHandleOnTheUiThread() {
        AtomicReference<Thread> deliveredOn = new AtomicReference<>();
        AtomicReference<Playback> handle = new AtomicReference<>();

        Sounds.streamAsync(TRACK, PlayOptions.DEFAULTS)
                .onSuccess(playback -> {
                    deliveredOn.set(Thread.currentThread());
                    handle.set(playback);
                })
                .start();

        pumpUntil(() -> handle.get() != null);
        assertSame(Thread.currentThread(), deliveredOn.get(), "delivery is the UI thread");
        assertNotSame(Thread.currentThread(), decoder.lastThread, "the open is not");
        assertSame(decoder.sources.get(0), engine.taken, "the opened source reached the engine");
        assertEquals(0, decoder.sources.get(0).closes.get(), "a delivered stream stays open");
    }

    @Test
    void nothingIsOpenedUntilTheWorkIsStarted() {
        Sounds.streamAsync(TRACK, PlayOptions.DEFAULTS).onSuccess(playback -> { });
        runtime.drain();
        assertEquals(0, decoder.opens.get(), "an unstarted Work must not touch the file");
    }

    @Test
    void cancelDuringTheOpenClosesTheSourceAndAdmitsNothing() throws Exception {
        decoder.entered = new CountDownLatch(1);
        decoder.gate = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();

        Job job = Sounds.streamAsync(TRACK, PlayOptions.DEFAULTS)
                .onSuccess(playback -> delivered.incrementAndGet())
                .start();

        assertTrue(decoder.entered.await(5, TimeUnit.SECONDS), "the body reached the open");
        job.cancel();
        decoder.gate.countDown();

        pumpUntil(job::isDone);
        pumpUntil(() -> !decoder.sources.isEmpty() && decoder.sources.get(0).closes.get() == 1);
        assertNull(engine.taken, "a cancelled open must not start music");
        assertEquals(0, delivered.get(), "a cancelled job delivers nothing");
    }

    @Test
    void aRefusedDeliveryStopsTheStreamItStarted() {
        AtomicInteger delivered = new AtomicInteger();

        Sounds.streamAsync(TRACK, PlayOptions.DEFAULTS)
                .onSuccess(playback -> delivered.incrementAndGet())
                .deliverIf(() -> false)
                .start();

        // The disposer runs on the pool, so the assertion is a pumped wait rather than a drain.
        pumpUntil(() -> engine.stops.get() == 1);
        assertEquals(0, delivered.get(), "a refused delivery does not reach onSuccess");
        assertEquals(1, decoder.sources.get(0).closes.get(), "stopping the stream closed the file");
    }

    @Test
    void withNoAudioDeviceNothingIsOpenedAndTheHandleIsNone() {
        engine.available = false;
        AtomicReference<Playback> handle = new AtomicReference<>();

        Sounds.streamAsync(TRACK, PlayOptions.DEFAULTS).onSuccess(handle::set).start();

        pumpUntil(() -> handle.get() != null);
        assertSame(Playback.NONE, handle.get());
        assertEquals(0, decoder.opens.get(), "nothing was opened, so there is nothing to close");
    }
}

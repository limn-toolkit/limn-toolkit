package limn.backend.lwjgl;

import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the service thread may touch without the monitor, and what nobody may take from under
 * it.
 *
 * <p>The refill decode runs with the engine's monitor released, on purpose: a slow disk must not
 * stall the mixer calls on the UI thread. That only holds if everything the decode touches is the
 * service thread's alone. Two things were not. The decode landed in one engine-wide scratch that
 * a caller priming a new track wrote at the same time, so two tracks started a moment apart traded
 * samples; and {@code close()} closed every decoder under the monitor before joining the thread,
 * so a Vorbis handle could be freed inside a native read on the way out.
 */
class OpenAlStreamIsolationTest {

    @Test
    void primingATrackWhileAnotherIsRefillingDecodesIntoADifferentBuffer()
            throws InterruptedException {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device");
            BlockingSource playing = new BlockingSource();
            Playback first = audio.playStream(playing, PlayOptions.DEFAULTS);
            assumeTrue(first != Playback.NONE, "the device would not take a streaming track");

            // Hold the service thread inside a refill decode of the first track, so the array it
            // is writing is pinned for the duration.
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            playing.entered = entered;
            playing.release = release;
            assertTrue(entered.await(10, TimeUnit.SECONDS),
                    "the service thread to be inside a refill decode");
            playing.entered = null;
            short[] refillTarget = playing.readingInto.get();
            assertNotNull(refillTarget);

            // Priming happens on the caller's thread, under the monitor, while that decode is
            // still in flight without it. The only thing that makes this safe is that the two
            // never share a destination.
            BlockingSource primed = new BlockingSource();
            Playback second = audio.playStream(primed, PlayOptions.DEFAULTS);
            assumeTrue(second != Playback.NONE, "a second streaming track");
            assertNotSame(refillTarget, primed.firstReadInto.get(),
                    "the track being primed decodes into its own array, not into the one the "
                            + "service thread is writing for the other track");

            playing.release = null;
            release.countDown();
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    @Test
    void closingWithADecodeInFlightLetsItReturnBeforeClosingTheDecoder()
            throws InterruptedException {
        OpenAlAudio audio = new OpenAlAudio();
        BlockingSource source = new BlockingSource();
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device");
            Playback track = audio.playStream(source, PlayOptions.DEFAULTS);
            assumeTrue(track != Playback.NONE, "the device would not take a streaming track");

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            source.entered = entered;
            source.release = release;
            assertTrue(entered.await(10, TimeUnit.SECONDS),
                    "the service thread to be inside a refill decode");

            // Shut down with the read still blocked. The interrupt close() sends is what lets
            // the read return (a real codec would simply finish its chunk); the decoder must be
            // closed after that return, never during it.
            audio.close();

            assertFalse(source.closedWhileReading.get(),
                    "the decoder was not closed while a read was inside it");
            assertTrue(source.closed.get(),
                    "and it was closed once the service thread had left, not leaked");
        } finally {
            // Whatever the assumptions said: a device left open when the test JVM exits is a
            // segfault inside OpenAL Soft's own teardown on Linux, reported against no test.
            assertDoesNotThrow(audio::close);
        }
    }

    /** Silence that can be held open mid-read, remembering which array each read was given. */
    private static final class BlockingSource implements AudioStreamSource {
        final AtomicReference<short[]> firstReadInto = new AtomicReference<>();
        final AtomicReference<short[]> readingInto = new AtomicReference<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean closedWhileReading = new AtomicBoolean();
        private final AtomicBoolean reading = new AtomicBoolean();
        /** Counted down as a read begins, before it blocks on {@link #release}. */
        volatile CountDownLatch entered;
        /** Held open while a read must block; null lets every read through. */
        volatile CountDownLatch release;

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
            firstReadInto.compareAndSet(null, out);
            readingInto.set(out);
            reading.set(true);
            try {
                CountDownLatch begun = entered;
                if (begun != null) {
                    begun.countDown();
                }
                CountDownLatch held = release;
                if (held != null) {
                    try {
                        held.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return 0;
                    }
                }
                java.util.Arrays.fill(out, 0, Math.min(out.length, maxFrames * 2), (short) 0);
                return maxFrames;
            } finally {
                reading.set(false);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public void close() {
            if (reading.get()) {
                closedWhileReading.set(true);
            }
            closed.set(true);
        }
    }
}

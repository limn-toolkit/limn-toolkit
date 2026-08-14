package limn.backend.lwjgl;

import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The engine's half of a seek: discarding what the device already holds, and reporting the new
 * position at once rather than when the refill arrives.
 *
 * <p>Nothing here asserts a step size or a timing. The suite runs against OpenAL Soft's null
 * backend by default, whose mixer period is its own and measurably coarser than the hardware
 * beside it, so what is asserted is what the bookkeeping promises: the position after a seek, that
 * the track is still alive, and that a source which refuses is not driven.
 */
class OpenAlSeekTest {

    @Test
    void aSeekedTrackReportsTheTargetImmediately() {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device");
            CountingSource source = new CountingSource(true);
            Playback track = audio.playStream(source, PlayOptions.DEFAULTS);
            assumeTrue(track != Playback.NONE, "the device would not take a streaming track");
            assertTrue(track.canSeek());

            track.seek(5_000_000);

            // Rebased under the caller's own monitor, before the service thread has decoded a
            // sample. A position that caught up a moment later would be read by a video clock as
            // the track having run away, and would cost the video its master.
            assertEquals(5.0, track.positionSeconds(), 0.05,
                    "the position is the target from the moment the call returns");
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    @Test
    void aSeekedTrackIsNotReapedForHavingAnEmptyQueue() throws InterruptedException {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device");
            CountingSource source = new CountingSource(true);
            Playback track = audio.playStream(source, PlayOptions.DEFAULTS);
            assumeTrue(track != Playback.NONE, "the device would not take a streaming track");

            int before = source.seeks.get();
            track.seek(2_000_000);

            // A flushed stream has exactly the shape a drained one has (no buffers queued, source
            // stopped), and the service loop reaps that. Without the guard the first seek on any
            // track silently ends it, which is what this waits to see not happen.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (source.seeks.get() == before && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(before + 1, source.seeks.get(),
                    "the service thread repositioned the decoder, so the track was still alive");
            assertEquals(2_000_000L, source.seekedTo.get());
            assertTrue(source.readsAfterSeek.get() > 0, "and refilled the device from there");
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    /**
     * The window both seek guards exist for. The service thread decodes refills <em>outside</em>
     * the engine's monitor, so a seek can land after it has unqueued a stream's buffers and before
     * it services that stream's state, where the stream has an empty queue and a stopped source,
     * which is exactly the shape a drained stream has.
     */
    @Test
    void aSeekLandingInsideARefillNeitherReapsTheTrackNorQueuesWhatThatRefillDecoded()
            throws InterruptedException {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device");
            CountingSource source = new CountingSource(true);
            Playback track = audio.playStream(source, PlayOptions.DEFAULTS);
            assumeTrue(track != Playback.NONE, "the device would not take a streaming track");

            // Hold the next refill decode open, and seek while it is held.
            java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            source.entered = entered;
            source.release = release;
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "the service thread to be inside a refill decode");
            source.entered = null;

            track.seek(4_000_000);
            source.release = null;
            release.countDown();

            long deadline = System.nanoTime() + 10_000_000_000L;
            while (source.seeks.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(1, source.seeks.get(),
                    "the track survived a seek that landed with its queue empty and its source "
                            + "stopped: the shape the service loop otherwise reaps");
            assertEquals(4.0, track.positionSeconds(), 1.0,
                    "and the audio queued after it is from the target, not from before it");
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    @Test
    void aSourceThatCannotSeekIsNotDriven() throws InterruptedException {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device");
            CountingSource source = new CountingSource(false);
            Playback track = audio.playStream(source, PlayOptions.DEFAULTS);
            assumeTrue(track != Playback.NONE, "the device would not take a streaming track");

            assertFalse(track.canSeek(), "the handle reports what the source can do");
            track.seek(3_000_000);
            Thread.sleep(120); // longer than a service period
            assertEquals(0, source.seeks.get(), "a refusal is a no-op, not an exception on a "
                    + "service thread nobody is catching on");
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    @Test
    void seekingAClipVoiceMovesItsOffset() {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device");
            Playback voice = audio.play(limn.sound.AudioClip.tone(440f, 2f, 0.2f), 0.5f, true);
            assumeTrue(voice != Playback.NONE, "the device would not take a clip");
            assertTrue(voice.canSeek(), "a clip is one buffer the device already holds");
            assertDoesNotThrow(() -> voice.seek(1_000_000));
            assertDoesNotThrow(() -> voice.seek(-5)); // clamped, not refused
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    /** Silence, endlessly, counting what the engine asks of it. */
    private static final class CountingSource implements AudioStreamSource {

        private final boolean seekable;
        final AtomicInteger seeks = new AtomicInteger();
        final AtomicLong seekedTo = new AtomicLong(-1);
        final AtomicInteger readsAfterSeek = new AtomicInteger();
        /** Counted down as a read begins, before it blocks on {@link #release}. */
        volatile java.util.concurrent.CountDownLatch entered;
        /** Held open while a read must block; null lets every read through. */
        volatile java.util.concurrent.CountDownLatch release;

        CountingSource(boolean seekable) {
            this.seekable = seekable;
        }

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
            java.util.concurrent.CountDownLatch begun = entered;
            if (begun != null) {
                begun.countDown();
            }
            java.util.concurrent.CountDownLatch held = release;
            if (held != null) {
                try {
                    held.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
            if (seeks.get() > 0) {
                readsAfterSeek.incrementAndGet();
            }
            java.util.Arrays.fill(out, 0, Math.min(out.length, maxFrames * 2), (short) 0);
            return maxFrames;
        }

        @Override
        public void reset() {
        }

        @Override
        public boolean canSeek() {
            return seekable;
        }

        @Override
        public void seek(long micros) {
            if (!seekable) {
                throw new UnsupportedOperationException("this source cannot seek");
            }
            seekedTo.set(micros);
            seeks.incrementAndGet();
        }

        @Override
        public void close() {
        }
    }
}

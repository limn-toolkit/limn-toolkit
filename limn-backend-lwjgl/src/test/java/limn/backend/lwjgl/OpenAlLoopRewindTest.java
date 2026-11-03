package limn.backend.lwjgl;

import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A looping stream whose source reads nothing for a while after its end, as the soundtrack of a
 * video does: the pictures rewind the container, a few tenths of a second after the sound ran
 * out, and until then the track has nothing to give.
 *
 * <p>Every empty refill used to delete the buffer it was for, and nothing made one again. A
 * video's soundtrack lost one or two of its three at every rewind and played the rest of its
 * passes with a gap after each chunk; a wait as long as the whole queue ended the track.
 */
class OpenAlLoopRewindTest {

    /** Longer than the three chunks in flight take to play, so the old code lost all of them. */
    private static final long WAIT_MILLIS = 700;

    @Test
    void aLoopingSourceThatReadsNothingUntilItRewindsKeepsPlaying() throws InterruptedException {
        OpenAlAudio audio = new OpenAlAudio();
        RewindsLate source = new RewindsLate(WAIT_MILLIS);
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device");
            Playback track = audio.playStream(source, PlayOptions.DEFAULTS.withLoop(true).withGain(0f));
            assumeTrue(track != Playback.NONE, "the device would not take a streaming track");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (source.passes.get() < 4 && !source.closed.get() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(source.closed.get(), "the stream was not ended while its source rewound");
            assertTrue(source.passes.get() >= 4,
                    "three rewinds were each waited for and played: " + source.passes.get() + " passes");
            track.stop();
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    @Test
    void aLoopingSourceThatNeverRewindsEnds() throws InterruptedException {
        OpenAlAudio audio = new OpenAlAudio();
        RewindsLate source = new RewindsLate(Long.MAX_VALUE);
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device");
            Playback track = audio.playStream(source, PlayOptions.DEFAULTS.withLoop(true).withGain(0f));
            assumeTrue(track != Playback.NONE, "the device would not take a streaming track");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!source.closed.get() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(source.closed.get(),
                    "a source still empty after the wait is an empty source, and its stream ends");
            assertFalse(track.isPlaying());
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    /**
     * Two chunks of silence, then nothing until {@code waitMillis} after the first empty read, then
     * the same again from the start: a rewind somebody else performs, late.
     */
    private static final class RewindsLate implements AudioStreamSource {
        private static final int FRAMES = 16_384;
        final AtomicInteger passes = new AtomicInteger(1);
        final AtomicBoolean closed = new AtomicBoolean();
        private final long waitNanos;
        private int position;
        private long emptySince;

        RewindsLate(long waitMillis) {
            this.waitNanos = waitMillis == Long.MAX_VALUE ? Long.MAX_VALUE
                    : TimeUnit.MILLISECONDS.toNanos(waitMillis);
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public int sampleRate() {
            return 48_000;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            if (position == FRAMES) {
                long now = System.nanoTime();
                if (emptySince == 0) {
                    emptySince = now;
                }
                if (now - emptySince < waitNanos) {
                    return 0;
                }
                position = 0;
                emptySince = 0;
                passes.incrementAndGet();
            }
            int frames = Math.min(Math.min(maxFrames, out.length), FRAMES - position);
            java.util.Arrays.fill(out, 0, frames, (short) 0);
            position += frames;
            return frames;
        }

        @Override
        public void reset() {
            // the rewind is somebody else's, as a video's soundtrack's is
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}

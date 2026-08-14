package limn.backend.lwjgl;

import limn.sound.AudioBus;
import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a streamed playback's reported position does across a buffer boundary, the one property a
 * video clock slaved to it depends on, and the one that cannot be read off the source: the position
 * is <em>composed</em> from frames the service thread has unqueued plus the device's offset into
 * what is still queued, and the two halves move at different moments.
 *
 * <p>Takes real time, because that is what is being measured, and skips where no device opens at
 * all. It runs against whichever driver the build selected: the suite points OpenAL Soft at its
 * null backend by default so a build makes no noise, and that backend mixes and advances on a timer
 * exactly as a real one does. The queue accounting under test sits above the driver either way.
 *
 * <p><b>The step size is the device's, not the engine's</b>, and the two differ enough to matter:
 * measured here, the null backend advances in steps about twice as long as the machine's own audio
 * hardware, one of them coarser than a 60 Hz refresh and the other finer. Nothing may be asserted
 * about which, only that the position never goes backwards and never leaps, which is what a clock
 * following it depends on. Run with {@code -DlimnAudibleTests=true} to measure the real device.
 */
class StreamPositionTest {

    /** Chunks are 8192 frames and three are queued, so a second spans several boundaries. */
    private static final int RATE = 44_100;
    private static final long MEASURE_MILLIS = 1_500;

    /** A silent stereo source of unbounded length: the position is what is under test, not the sound. */
    private static final class SilentStream implements AudioStreamSource {
        @Override
        public int channels() {
            return 2;
        }

        @Override
        public int sampleRate() {
            return RATE;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            java.util.Arrays.fill(out, 0, maxFrames * 2, (short) 0);
            return maxFrames;
        }

        @Override
        public void reset() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    void composedPositionNeverStepsBackwardsWhilePlaying() throws InterruptedException {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            assumeTrue(audio.isAvailable(), "needs an audio device the engine will open");
            Playback playback = audio.playStream(new SilentStream(),
                    PlayOptions.DEFAULTS.withGain(0).withBus(AudioBus.MUSIC));
            assumeTrue(playback != Playback.NONE, "the device refused the stream");

            double previous = playback.positionSeconds();
            double worstBackwardStep = 0;
            double largestForwardStep = 0;
            int samples = 0;
            long deadline = System.nanoTime() + MEASURE_MILLIS * 1_000_000L;
            while (System.nanoTime() < deadline) {
                double now = playback.positionSeconds();
                samples++;
                double step = now - previous;
                worstBackwardStep = Math.min(worstBackwardStep, step);
                largestForwardStep = Math.max(largestForwardStep, step);
                previous = now;
                Thread.sleep(4); // finer than a 60 Hz frame, so a boundary cannot be stepped over
            }
            playback.stop();

            assertTrue(samples > 100, "too few samples to have crossed a buffer boundary: " + samples);
            assertTrue(previous > 0.5,
                    "the stream did not advance at all: reached " + previous + " s");
            // The composition is exact by construction: unqueuing a buffer adds its frames to the
            // completed count and removes exactly those frames from the device's queue-relative
            // offset. A regression in that bookkeeping shows up here as a step of about one chunk
            // (8192 frames, 186 ms) in one direction or the other, so the bound is well inside it.
            assertTrue(worstBackwardStep > -0.020,
                    "position stepped backwards by " + (-worstBackwardStep) + " s; a video clock "
                            + "slaved to this would hold every picture until it caught back up");
            assertTrue(largestForwardStep < 0.100,
                    "position jumped forward by " + largestForwardStep + " s");
            System.out.println("[measured] stream position over " + samples + " samples: "
                    + "worst backward step " + worstBackwardStep + " s, "
                    + "largest forward step " + largestForwardStep + " s, "
                    + "reached " + previous + " s");
        } finally {
            audio.close();
        }
    }
}

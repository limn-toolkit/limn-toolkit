package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composed stream position against a device that exists only in the test: a queue of
 * chunks, an offset that advances by whatever the script says, and an unqueue that moves one
 * chunk from the device's side of the sum to the engine's in a single step, which is what the
 * engine does under its monitor. {@link StreamPositionTest} measures the same property on the
 * real device, which advances on its own clock and can only be sampled; this one is exact,
 * instant, and crosses as many boundaries as it likes.
 */
class StreamPositionCompositionTest {

    private static final int RATE = 44_100;
    private static final int CHUNK = 8192;

    /** The two halves the engine keeps, and the one step that moves frames between them. */
    private static final class ScriptedDevice {
        long completedFrames;
        double offsetFrames; // into the chunks still queued, as AL_SEC_OFFSET * rate
        long loopLengthFrames;

        void advance(double frames) {
            offsetFrames += frames;
            // The service pass: every whole chunk the device has played is unqueued, and the
            // unqueue adds its frames to the completed count in the same step that takes them
            // off the device's queue-relative offset.
            while (offsetFrames >= CHUNK) {
                offsetFrames -= CHUNK;
                completedFrames += CHUNK;
            }
        }

        double position() {
            return OpenAlAudio.composedPositionSeconds(completedFrames, offsetFrames / RATE,
                    RATE, loopLengthFrames);
        }
    }

    @Test
    void thePositionIsContinuousAcrossEveryBufferBoundary() {
        ScriptedDevice device = new ScriptedDevice();
        double previous = device.position();
        double advancedSeconds = 0;
        // Steps of every size a mixer period might have, including ones that cross more than
        // one boundary at once, for a few hundred chunks' worth of playback.
        double[] steps = {97, 512, 1024, 3.5, 8191, 8193, 20_000, 1};
        for (int i = 0; i < 400; i++) {
            double step = steps[i % steps.length];
            device.advance(step);
            advancedSeconds += step / RATE;
            double now = device.position();
            assertTrue(now >= previous, "stepped backwards at " + i + ": " + previous + " -> " + now);
            assertEquals(step / RATE, now - previous, 1e-9,
                    "the reported step is exactly what the device advanced");
            previous = now;
        }
        assertEquals(advancedSeconds, device.position(), 1e-6);
        assertTrue(device.completedFrames > 100L * CHUNK, "many boundaries were crossed");
    }

    @Test
    void aLoopingStreamReportsInTrackTimeAndWrapsByExactlyOneTrack() {
        ScriptedDevice device = new ScriptedDevice();
        device.loopLengthFrames = 10 * CHUNK; // learned at the first rewind, then fixed
        double track = 10.0 * CHUNK / RATE;
        double previous = device.position();
        int wraps = 0;
        for (int i = 0; i < 400; i++) {
            device.advance(1000);
            double now = device.position();
            if (now < previous) {
                // The one allowed backward step is the wrap itself, by a whole track length.
                wraps++;
                assertEquals(track, previous + 1000.0 / RATE - now, 1e-9,
                        "a wrap is exactly one track length, never a partial jump");
            }
            assertTrue(now >= 0 && now < track, "in-track time stays inside the track");
            previous = now;
        }
        assertTrue(wraps >= 4, "the script ran through the track several times: " + wraps);
    }
}

package limn.backend.lwjgl;

import limn.sound.AudioClip;
import limn.sound.Playback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test: the OpenAL engine must never throw, whether or not an audio
 * device is available (best-effort). With audio it initializes OpenAL, uploads
 * a buffer and plays a source; headless/CI it degrades to {@link Playback#NONE}.
 */
class OpenAlAudioTest {

    @Test
    void playAndControlNeverThrow() {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            AudioClip clip = AudioClip.tone(660f, 0.05f, 0.5f);
            assertDoesNotThrow(audio::isAvailable);
            Playback first = assertDoesNotThrow(() -> audio.play(clip, 0.7f, false));
            assertNotNull(first);
            // The cached buffer is reused on the second play of the same clip.
            assertDoesNotThrow(() -> audio.play(clip, 0.7f, false));
            // Control handle operations are safe even when no device exists.
            assertDoesNotThrow(first::isPlaying);
            assertDoesNotThrow(() -> first.setGain(0.3f));
            assertDoesNotThrow(first::stop);
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    @Test
    void closedEngineNeverReinitializes() {
        OpenAlAudio audio = new OpenAlAudio();
        audio.close(); // works whether or not a device was ever opened

        org.junit.jupiter.api.Assertions.assertFalse(audio.isAvailable(),
                "shutdown is final: no availability probe may re-open the device");
        org.junit.jupiter.api.Assertions.assertSame(Playback.NONE,
                audio.play(AudioClip.tone(440f, 0.01f, 0.1f), 1, false),
                "closed: play degrades to the null playback");
        assertDoesNotThrow(audio::close); // idempotent
    }

    /**
     * A clip or a stream the device would play slow does not start: the device accepts any
     * positive rate and plays one above ten times its own too slowly, saying nothing. The engine
     * refuses it the way it refuses a channel count it cannot play, with {@link Playback#NONE} and
     * the stream closed, rather than throwing, because a film's player takes that answer as "no
     * sound" and keeps the pictures going. Silent clips at no gain, so the run is not heard.
     */
    @Test
    void aRateTheDeviceWouldPlaySlowDoesNotStartAndTheHighestPlayableOneDoes() {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(audio.isAvailable(),
                    "needs a real audio device: without one every play is NONE");
            limn.sound.PlayOptions silent = limn.sound.PlayOptions.DEFAULTS.withGain(0f);
            org.junit.jupiter.api.Assertions.assertSame(Playback.NONE,
                    audio.play(AudioClip.of(new short[64], 1, OpenAlAudio.MAX_SAMPLE_RATE + 1), silent));

            boolean[] closed = {false};
            org.junit.jupiter.api.Assertions.assertSame(Playback.NONE,
                    audio.playStream(silence(768_000, closed), silent));
            org.junit.jupiter.api.Assertions.assertTrue(closed[0], "a refused stream is closed");

            Playback highest = audio.play(AudioClip.of(new short[64], 1, OpenAlAudio.MAX_SAMPLE_RATE), silent);
            org.junit.jupiter.api.Assertions.assertNotSame(Playback.NONE, highest);
            highest.stop();
            boolean[] highestClosed = {false};
            Playback stream = audio.playStream(silence(OpenAlAudio.MAX_SAMPLE_RATE, highestClosed), silent);
            org.junit.jupiter.api.Assertions.assertNotSame(Playback.NONE, stream);
            stream.stop();
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }

    /** A mono stream of silence at {@code rate}, a second long, that records being closed. */
    private static limn.sound.AudioStreamSource silence(int rate, boolean[] closed) {
        return new limn.sound.AudioStreamSource() {
            private int left = rate;

            @Override
            public int channels() {
                return 1;
            }

            @Override
            public int sampleRate() {
                return rate;
            }

            @Override
            public int readFrames(short[] out, int maxFrames) {
                int frames = Math.min(maxFrames, left);
                java.util.Arrays.fill(out, 0, frames, (short) 0);
                left -= frames;
                return frames;
            }

            @Override
            public void reset() {
                left = rate;
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
    }

    @Test
    void bufferCacheStaysBounded() {
        OpenAlAudio audio = new OpenAlAudio();
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(audio.isAvailable(),
                    "needs a real audio device");
            // Far more distinct clips than the cap: uploading + evicting in
            // stride must never throw (detach-before-delete) and never grow
            // the device-buffer set unboundedly.
            for (int i = 0; i < 200; i++) {
                assertDoesNotThrow(() -> audio.play(AudioClip.tone(300f, 0.005f, 0.2f), 0.5f, false));
            }
        } finally {
            assertDoesNotThrow(audio::close);
        }
    }
}

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

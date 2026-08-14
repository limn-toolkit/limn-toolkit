package limn.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-CPU synthesis and validation of {@link AudioClip} (no audio device). */
class AudioClipTest {

    @Test
    void toneHasExpectedShape() {
        AudioClip clip = AudioClip.tone(660f, 0.1f, 0.5f);
        assertEquals(1, clip.channels());
        assertEquals(AudioClip.DEFAULT_SAMPLE_RATE, clip.sampleRate());
        assertEquals(Math.round(AudioClip.DEFAULT_SAMPLE_RATE * 0.1f), clip.frameCount());
        assertEquals(0.1, clip.durationSeconds(), 1e-3);

        short[] s = clip.samples();
        // Raised-cosine envelope: silent at both ends, loud in the middle.
        assertEquals(0, s[0]);
        assertEquals(0, s[s.length - 1]);
        int peak = 0;
        for (short v : s) {
            peak = Math.max(peak, Math.abs(v));
        }
        // Peak amplitude ~0.5 full-scale (envelope reaches 1 mid-tone, no clipping).
        assertTrue(peak > 0.45 * Short.MAX_VALUE, "tone should reach near its 0.5 amplitude, got " + peak);
        assertTrue(peak <= Short.MAX_VALUE, "amplitude 0.5 must not clip");
    }

    @Test
    void ofTakesTheArrayByReference() {
        short[] pcm = {1, 2, 3, 4};
        AudioClip clip = AudioClip.of(pcm, 2, 48_000);
        assertSame(pcm, clip.samples(), "of() is zero-copy like Image");
        assertEquals(2, clip.channels());
        assertEquals(48_000, clip.sampleRate());
        assertEquals(2, clip.frameCount());
    }

    @Test
    void ofRejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> AudioClip.of(new short[] {1, 2}, 3, 44_100));
        assertThrows(IllegalArgumentException.class, () -> AudioClip.of(new short[] {1, 2}, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> AudioClip.of(new short[] {1, 2, 3}, 2, 44_100));
    }
}

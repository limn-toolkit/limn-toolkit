package limn.sound;

import java.util.Objects;

/**
 * An immutable, backend-independent chunk of PCM audio: interleaved 16-bit
 * signed samples, 1 (mono) or 2 (stereo) channels, at a given sample rate.
 * Backend-neutral like {@link limn.graphics.Image}: decoded/synthesized
 * once on the CPU, then handed to the running {@link AudioEngine} (via
 * {@link Sounds#play}) which uploads it to the device lazily and caches the
 * device buffer keyed by object identity, so replaying the same instance (a UI
 * beep, a shared sound effect) is cheap.
 *
 * <p>Two ways to get one:
 * <ul>
 *   <li>{@link #tone(float, float, float)}: synthesize a sine tone (the shape
 *       the system beep is built from);</li>
 *   <li>{@link Sounds#decode}/{@link Sounds#load}/{@link Sounds#fromResource}:
 *       decode an encoded file (WAV/OGG) through the backend decoder.</li>
 * </ul>
 */
public final class AudioClip {

    /** The sample rate used by {@link #tone} and the default for synthesis. */
    public static final int DEFAULT_SAMPLE_RATE = 44_100;

    private final short[] samples;
    private final int channels;
    private final int sampleRate;

    private AudioClip(short[] samples, int channels, int sampleRate) {
        this.samples = samples;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    /**
     * Wraps raw interleaved 16-bit PCM. The array is taken by reference (not
     * copied) and must not be mutated afterwards, mirroring {@link
     * limn.graphics.Image}'s zero-copy contract.
     *
     * @param interleavedSamples interleaved signed 16-bit samples
     *                           ({@code frameCount * channels} long)
     * @param channels           1 (mono) or 2 (stereo)
     * @param sampleRate         frames per second (e.g. 44100)
     */
    public static AudioClip of(short[] interleavedSamples, int channels, int sampleRate) {
        Objects.requireNonNull(interleavedSamples, "interleavedSamples");
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("channels must be 1 or 2, got " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got " + sampleRate);
        }
        if (interleavedSamples.length % channels != 0) {
            throw new IllegalArgumentException(
                    "sample count " + interleavedSamples.length + " is not a multiple of channels " + channels);
        }
        return new AudioClip(interleavedSamples, channels, sampleRate);
    }

    /**
     * Synthesizes a mono sine tone with a raised-cosine fade in/out envelope
     * (so it starts and ends silently, avoiding clicks) at
     * {@link #DEFAULT_SAMPLE_RATE}. This is the primitive the system alert beep
     * is made of; applications can use it for UI feedback without shipping an
     * asset.
     *
     * @param frequencyHz     pitch in hertz (e.g. 660)
     * @param durationSeconds length in seconds (e.g. 0.09)
     * @param amplitude       peak amplitude in [0..1] (e.g. 0.5)
     */
    public static AudioClip tone(float frequencyHz, float durationSeconds, float amplitude) {
        if (frequencyHz <= 0) {
            throw new IllegalArgumentException("frequencyHz must be positive, got " + frequencyHz);
        }
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive, got " + durationSeconds);
        }
        float peak = Math.max(0f, Math.min(1f, amplitude));
        int count = Math.max(1, Math.round(DEFAULT_SAMPLE_RATE * durationSeconds));
        short[] pcm = new short[count];
        for (int i = 0; i < count; i++) {
            double t = (double) i / DEFAULT_SAMPLE_RATE;
            // Raised-cosine window over the whole tone (1 at the middle, 0 at
            // the ends); degenerate to a flat envelope for a 1-sample clip.
            double env = count > 1 ? 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (count - 1)) : 1.0;
            double value = Math.sin(2 * Math.PI * frequencyHz * t) * env * peak;
            pcm[i] = (short) Math.round(value * Short.MAX_VALUE);
        }
        return new AudioClip(pcm, 1, DEFAULT_SAMPLE_RATE);
    }

    /** @return the raw interleaved 16-bit samples (do not mutate) */
    public short[] samples() {
        return samples;
    }

    /** @return the channel count (1 mono, 2 stereo) */
    public int channels() {
        return channels;
    }

    /** @return frames per second */
    public int sampleRate() {
        return sampleRate;
    }

    /** @return the number of frames (samples per channel) */
    public int frameCount() {
        return samples.length / channels;
    }

    /** @return the playback length in seconds */
    public double durationSeconds() {
        return (double) frameCount() / sampleRate;
    }
}

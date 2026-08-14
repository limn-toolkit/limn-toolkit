package limn.sound;

import limn.math.Vec3;

/**
 * How to play a sound: volume, pitch, stereo pan or 3D position, looping,
 * mixer {@link AudioBus bus} and voice-steal {@link Priority}. Immutable;
 * start from {@link #DEFAULTS} and derive with the {@code with*} methods
 * ({@link limn.math.Transform3D}-style):
 *
 * <pre>{@code
 * Sounds.play(step, PlayOptions.DEFAULTS.withPitch(0.9f + random * 0.2f));
 * Sounds.play(song, PlayOptions.DEFAULTS.withBus(AudioBus.MUSIC)
 *         .withPriority(PlayOptions.Priority.HIGH).withLoop(true));
 * }</pre>
 *
 * @param gain     volume in [0..1], multiplied by the bus and master gains
 * @param pitch    playback-rate multiplier in [0.25..4], where 1 is natural; the
 *                 standard de-repetition trick is a small random variation
 * @param pan      stereo position in [-1..1] (left..right, 0 = center).
 *                 Realized for MONO clips; stereo clips ignore it
 * @param loop     repeat until stopped
 * @param bus      the mixer bus this playback belongs to
 * @param priority voice-steal class: when every voice is busy, lower-priority
 *                 sounds are stolen first and a sound never steals a
 *                 higher-priority voice; mark music/dialog {@link Priority#HIGH}
 *                 so an effects burst cannot silence it
 * @param position world position for 3D attenuation/panning, or {@code null}
 *                 for plain 2D playback. Mono clips only (OpenAL constraint);
 *                 pair with {@link Sounds#setListener}. Overrides {@code pan}
 */
public record PlayOptions(float gain, float pitch, float pan, boolean loop,
                          AudioBus bus, Priority priority, Vec3 position) {

    /** Voice-steal class; see {@link PlayOptions#priority}. */
    public enum Priority { LOW, NORMAL, HIGH }

    /** Gain 1, pitch 1, centered, no loop, {@link AudioBus#SFX}, NORMAL priority, 2D. */
    public static final PlayOptions DEFAULTS =
            new PlayOptions(1f, 1f, 0f, false, AudioBus.SFX, Priority.NORMAL, null);

    public PlayOptions {
        if (gain < 0 || gain > 1 || Float.isNaN(gain)) {
            throw new IllegalArgumentException("gain must be in [0..1], got " + gain);
        }
        if (pitch < 0.25f || pitch > 4f || Float.isNaN(pitch)) {
            throw new IllegalArgumentException("pitch must be in [0.25..4], got " + pitch);
        }
        if (pan < -1 || pan > 1 || Float.isNaN(pan)) {
            throw new IllegalArgumentException("pan must be in [-1..1], got " + pan);
        }
        if (bus == null) {
            throw new IllegalArgumentException("bus is null");
        }
        if (priority == null) {
            throw new IllegalArgumentException("priority is null");
        }
    }

    /** A copy at a different volume, {@code [0..1]}. */
    public PlayOptions withGain(float newGain) {
        return new PlayOptions(newGain, pitch, pan, loop, bus, priority, position);
    }

    /** A copy at a different playback rate, {@code [0.25..4]}, where 1 is natural. */
    public PlayOptions withPitch(float newPitch) {
        return new PlayOptions(gain, newPitch, pan, loop, bus, priority, position);
    }

    /** A copy at a different stereo position, {@code [-1..1]}. Ignored for stereo clips. */
    public PlayOptions withPan(float newPan) {
        return new PlayOptions(gain, pitch, newPan, loop, bus, priority, position);
    }

    /** A copy that repeats until stopped. */
    public PlayOptions withLoop(boolean newLoop) {
        return new PlayOptions(gain, pitch, pan, newLoop, bus, priority, position);
    }

    /** A copy routed through a different mixer bus. */
    public PlayOptions withBus(AudioBus newBus) {
        return new PlayOptions(gain, pitch, pan, loop, newBus, priority, position);
    }

    /** A copy in a different voice-steal class. */
    public PlayOptions withPriority(Priority newPriority) {
        return new PlayOptions(gain, pitch, pan, loop, bus, newPriority, position);
    }

    /** Positional 3D playback at ({@code x}, {@code y}, {@code z}): mono clips only. */
    public PlayOptions at(float x, float y, float z) {
        return new PlayOptions(gain, pitch, pan, loop, bus, priority, new Vec3(x, y, z));
    }
}

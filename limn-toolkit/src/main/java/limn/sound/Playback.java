package limn.sound;

/**
 * A handle to a sound that has started playing on the {@link AudioEngine}. It
 * lets the caller stop it, adjust its volume, or query whether it is still
 * sounding. Handles become inert once the underlying voice is recycled for
 * another clip, so operations on a finished playback are harmless no-ops.
 */
public interface Playback {

    /** A no-op handle returned when no audio device is available (headless/CI). */
    Playback NONE = new Playback() {
        @Override
        public void stop() {
        }

        @Override
        public boolean isPlaying() {
            return false;
        }

        @Override
        public void setGain(float gain) {
        }
    };

    /** Stops this sound immediately (no-op if it already finished). */
    void stop();

    /** @return whether this sound is still playing ({@code false} while paused) */
    boolean isPlaying();

    /** Sets this sound's volume in [0..1] (no-op if it already finished). */
    void setGain(float gain);

    /**
     * Pauses this sound, keeping its position; {@link #resume()} continues
     * exactly where it left off (the game-pause primitive; {@code stop()}
     * loses the position). Default no-op for engines without support.
     */
    default void pause() {
    }

    /** Resumes a {@link #pause() paused} sound from where it stopped. */
    default void resume() {
    }

    /** Sets the playback-rate multiplier in [0.25..4] (1 = natural speed/pitch). */
    default void setPitch(float pitch) {
    }

    /** Sets the stereo pan in [-1..1] (mono clips only; see {@link PlayOptions#pan}). */
    default void setPan(float pan) {
    }

    /**
     * Moves a positional playback to ({@code x}, {@code y}, {@code z}), for
     * emitters that travel with a game object. Only affects playbacks started
     * with {@link PlayOptions#at}; no-op otherwise.
     */
    default void setPosition(float x, float y, float z) {
    }

    /** @return seconds into the clip/stream, or 0 when finished/unsupported */
    default double positionSeconds() {
        return 0;
    }

    /**
     * Moves this sound to {@code micros} and <b>discards whatever is already queued on the
     * device</b>: both halves, because either alone is a seek that sounds wrong. Repositioning the
     * source without discarding plays the old position for the depth of the queue and then jumps;
     * discarding without repositioning is a gap.
     *
     * <p><b>{@link #positionSeconds()} reports the target from the moment this returns</b>, before
     * a sample from the new position has been decoded. That is not a convenience: video slaved to
     * this position is re-anchored by the same caller in the same breath, and a position that
     * lagged the request would be read as the track having run away and would cost the video its
     * audio master.
     *
     * <p><b>The sound is silent for a moment.</b> Refilling the device happens on the engine's own
     * thread, so a seek costs one service period plus one decode before anything is heard. A
     * caller scrubbing hears gaps, which is what scrubbing sounds like.
     *
     * <p>Default no-op, like every other optional operation here, including on a finished
     * playback and on the handle a machine with no audio device yields. Ask {@link #canSeek()}
     * first if the difference matters.
     *
     * @param micros where to move to, in microseconds from the start; negative is clamped to the
     *               start rather than refused, because a transport control computing a position
     *               from a pixel will produce one
     */
    default void seek(long micros) {
    }

    /**
     * @return whether {@link #seek(long)} does anything. False for a finished playback, for a
     *         streamed source that {@linkplain AudioStreamSource#canSeek() cannot be repositioned},
     *         and when there is no audio device at all
     */
    default boolean canSeek() {
        return false;
    }
}

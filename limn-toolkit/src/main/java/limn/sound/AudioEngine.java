package limn.sound;

/**
 * Service-provider interface for audio output, the sound counterpart of the
 * {@link limn.backend.Backend} rendering SPI. The only shipped implementation
 * is OpenAL (in {@code limn-backend-lwjgl}); the abstraction keeps the toolkit
 * free of any audio library. Installed once at backend startup (see
 * {@link Sounds#installEngine}).
 *
 * <p>Best-effort: on a machine with no audio device the engine reports
 * {@link #isAvailable() unavailable} and {@link #play} returns
 * {@link Playback#NONE} rather than throwing, so UI feedback sounds never
 * become a hard dependency.
 */
public interface AudioEngine {

    /**
     * Plays {@code clip} once (or looping) at the given volume. The clip's
     * device buffer is created on first use and cached by object identity, so
     * replaying a shared clip is cheap. Multiple clips may sound at once
     * (mixed by the device). Safe to call from any thread.
     *
     * @param clip the audio to play
     * @param gain volume in [0..1]
     * @param loop whether to repeat until {@link Playback#stop() stopped}
     * @return a handle to the started sound, or {@link Playback#NONE} if no
     *         audio device is available
     */
    Playback play(AudioClip clip, float gain, boolean loop);

    /**
     * Plays {@code clip} with the full {@link PlayOptions}: pitch, pan or 3D
     * position, bus and steal priority. The default honors only gain/loop, so
     * simple engines and test fakes keep working; the shipped backend honors
     * everything. Safe to call from any thread.
     */
    default Playback play(AudioClip clip, PlayOptions options) {
        return play(clip, options.gain(), options.loop());
    }

    /**
     * Streams {@code source}, decoding incrementally on the engine's
     * streaming thread instead of uploading a whole clip. The engine takes
     * ownership of the source (closing it when playback ends or is stopped).
     * {@link PlayOptions#loop() Looping} rewinds via
     * {@link AudioStreamSource#reset()}. Default: closes the source and
     * reports {@link Playback#NONE} (no streaming support).
     *
     * <p>Runs on the calling thread and is allowed to block there: an implementation that queues
     * device buffers ahead of the play decodes the first of them here, before the sound starts.
     * That is a decode of the first fraction of a second, so this is not a call to make on the UI
     * thread with a source whose format is expensive.
     */
    default Playback playStream(AudioStreamSource source, PlayOptions options) {
        source.close();
        return Playback.NONE;
    }

    /** Sets the global volume multiplier in [0..1], applied live to everything. */
    default void setMasterGain(float gain) {
    }

    /** Sets {@code bus}'s volume multiplier in [0..1], applied live to its playbacks. */
    default void setBusGain(AudioBus bus, float gain) {
    }

    /**
     * Positions the 3D listener for {@link PlayOptions#at positional}
     * playbacks: where the "ears" are and which way they face.
     *
     * @param position the listener's world position
     * @param forward  unit vector the listener faces
     * @param up       unit up vector (perpendicular to {@code forward})
     */
    default void setListener(limn.math.Vec3 position,
                             limn.math.Vec3 forward, limn.math.Vec3 up) {
    }

    /**
     * Whether an audio device is available and initialized.
     *
     * <p><b>An implementation may open the device here, on the first call.</b> Loading the platform
     * audio library and waking the default output is tens to hundreds of milliseconds (more when
     * the output is asleep or on a Bluetooth link), and it blocks whichever thread asks. That is
     * allowed, and it is why {@link Sounds#warmUpAsync()} exists: the facade needs one call it can
     * make on a worker to get the cost over with. Calls after the first must be cheap: the facade
     * asks this before it decides whether to open a track at all, so it sits on paths that run
     * often.
     *
     * <p>Failure is not an exception: a machine with no device answers {@code false} for the rest
     * of the process rather than retrying the open on every call.
     *
     * @return whether something played now would be heard
     */
    boolean isAvailable();
}

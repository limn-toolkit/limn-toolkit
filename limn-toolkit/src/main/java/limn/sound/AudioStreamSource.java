package limn.sound;

/**
 * A pull source of PCM frames for streamed playback: music and long ambience
 * decoded incrementally instead of {@link AudioClip}-style all-at-once (a four
 * minute track fully decoded is ~40 MB of heap; streamed it is a few hundred
 * KB of ring buffers). Obtained from {@link AudioDecoder#openStream} and
 * consumed by {@link AudioEngine#playStream}.
 *
 * <p>Threading: after {@code playStream} takes ownership, calls come from the
 * thread that starts the stream (initial priming reads) and from the engine's
 * streaming thread (refills, {@link #seek}, the final {@link #close()}), never
 * concurrently, serialized by the engine. Implementations need no
 * synchronization but must not assume any particular thread.
 */
public interface AudioStreamSource extends AutoCloseable {

    /** @return 1 (mono) or 2 (stereo, interleaved) */
    int channels();

    /** @return frames per second (e.g. 44100) */
    int sampleRate();

    /**
     * Decodes up to {@code maxFrames} frames into {@code out} (interleaved,
     * {@code frames × channels} shorts from index 0).
     *
     * @return the number of frames written; {@code 0} means end of stream
     */
    int readFrames(short[] out, int maxFrames);

    /** Rewinds to the first frame: how the engine loops a stream seamlessly. */
    void reset();

    /**
     * Moves to {@code micros} so that the next {@link #readFrames} returns audio from there:
     * how a track follows a video that has been seeked, and how a transport control moves a
     * long piece of music without restarting it.
     *
     * <p><b>Repositioning the source is only half of a seek.</b> The engine has already handed
     * whole buffers to the device, and those play out before anything read after this does, so a
     * caller that moves the source and nothing else hears the old position for the depth of the
     * queue and then a jump. {@link Playback#seek(long)} is the operation that does both, and it is
     * what a caller wants; this is what the engine calls underneath it.
     *
     * <p>Accuracy is the implementation's: a decoder that can only reach a packet boundary lands on
     * one. A target beyond the end leaves the track at its end, where {@link #readFrames} reports
     * zero; a target at or below zero is the beginning.
     *
     * <p>Called on the thread {@link #readFrames} is called on and never concurrently with it.
     *
     * @param micros where to move to, in microseconds from the start of the track; not negative
     * @throws UnsupportedOperationException if {@link #canSeek()} is false
     * @throws IllegalArgumentException      if {@code micros} is negative
     */
    default void seek(long micros) {
        throw new UnsupportedOperationException(
                getClass().getName() + " cannot seek; ask canSeek() first");
    }

    /**
     * @return whether {@link #seek(long)} works, which defaults to false so that a source written
     *         before seeking existed keeps telling the truth. Independent of {@link #reset()},
     *         which every source supports: rewinding to the start is not the same capability as
     *         reaching the middle.
     */
    default boolean canSeek() {
        return false;
    }

    /** Releases decoder/file resources. Idempotent. */
    @Override
    void close();
}

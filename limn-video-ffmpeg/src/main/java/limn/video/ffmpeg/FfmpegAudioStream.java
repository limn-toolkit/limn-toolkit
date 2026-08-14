package limn.video.ffmpeg;

import limn.sound.AudioStreamSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * The soundtrack of an open container, in the shape the audio engine wants rather than the shape
 * the codec produces.
 *
 * <p>Those are not the same shape, and the gap is the whole reason this class exists. AAC decodes
 * to <b>planar float</b> (one array of {@code float} per channel) and
 * {@link AudioStreamSource#readFrames} wants <b>interleaved signed 16-bit</b>. The conversion is
 * arithmetic, done in the shim while the samples are already in hand, and it deliberately does not
 * go through a resampler: nothing is being resampled. {@link #sampleRate()} reports whatever the
 * file declared and the engine takes it, so pulling in libswresample would add the largest single
 * item to the native payload in order to multiply some numbers.
 *
 * <p><b>More than two channels are folded to stereo.</b> The audio engine refuses any other count
 * at admission, so a 5.1 track has three possible answers (refuse the file, play it silent, or
 * fold it), and folding is the only one that plays the film. {@link #channels()} therefore reports
 * what this delivers, which is 1 or 2; {@code FfmpegMedia.audioSourceChannels()} reports what the
 * file holds.
 *
 * <p><b>Closing this does not close the container.</b> The audio engine takes ownership of a
 * source it is given and closes it on every path (including the failures), so if closing the
 * soundtrack closed the container, a track ending would pull the decoder out from under the
 * pictures. What it does instead is tell the demultiplexer that nobody is reading this track, and
 * its packets are discarded as they are met rather than queued.
 *
 * <p><b>A container has one soundtrack open at a time, and this may not be it.</b> Selecting
 * another track through {@code FfmpegMedia.audio(int)} <em>supersedes</em> this one: from that
 * moment {@link #readFrames} reports 0, which is what the end of a track means to the engine, and
 * everything else here does nothing. That is answered rather than punished for the same reason a
 * call after the container is closed is: the engine's streaming thread can genuinely be inside a
 * refill when a viewer picks another language, and the alternative is a lock held across a whole
 * decode. The check is on the far side, under the lock that guards the codec, so there is no
 * window in which a superseded consumer reads the new track's samples.
 *
 * <p>Threading is the engine's: the priming reads come from the thread that starts the stream and
 * the refills from the engine's streaming thread, never concurrently. Both may run while a
 * player's decode thread is pulling pictures out of the same container, which is why the two
 * tracks have separate locks on the far side.
 */
final class FfmpegAudioStream implements AudioStreamSource {

    private final FfmpegMedia media;
    private final int channels;
    private final int sampleRate;
    private final int sourceChannels;
    /**
     * Which selection this source belongs to. Presented on every call, so that a call from a track
     * that has since been replaced is recognised on the far side rather than acted on.
     */
    private final long generation;

    /** Where the shim writes. Allocated on the first read and grown only if a refill is bigger. */
    private ByteBuffer scratch;
    private ShortBuffer samples;
    private boolean closed;

    FfmpegAudioStream(FfmpegMedia media, int channels, int sampleRate, int sourceChannels,
                      long generation) {
        this.media = media;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.sourceChannels = sourceChannels;
        this.generation = generation;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    /** @return channels in the file, before the fold to what the engine admits */
    int sourceChannels() {
        return sourceChannels;
    }

    @Override
    public int readFrames(short[] out, int maxFrames) {
        if (closed || maxFrames <= 0) {
            return 0;
        }
        int wanted = Math.min(maxFrames, out.length / channels);
        if (wanted <= 0) {
            return 0;
        }
        ensureCapacity(wanted);
        int frames = media.readAudio(scratch, wanted, generation);
        if (frames <= 0) {
            return 0;
        }
        samples.position(0);
        samples.get(out, 0, frames * channels);
        return frames;
    }

    private void ensureCapacity(int frames) {
        int shorts = frames * channels;
        if (scratch != null && samples.capacity() >= shorts) {
            return;
        }
        // Native order, because the shim writes host-endian 16-bit integers straight into it.
        scratch = ByteBuffer.allocateDirect(shorts * 2).order(ByteOrder.nativeOrder());
        samples = scratch.asShortBuffer();
    }

    /**
     * Rewinds nothing on its own. The position of both tracks belongs to the demultiplexer and the
     * pictures are what move it, so a soundtrack cannot rewind without the video rewinding with
     * it; what this does is drop whatever was queued for this track, so that a rewind driven from
     * the video side does not then deliver sound from before it.
     */
    @Override
    public void reset() {
        if (!closed) {
            media.resetAudio(generation);
        }
    }

    /**
     * @return true. The container was opened from a file libavformat had already indexed, so the
     *         position is reachable; a seek that fails anyway throws rather than being reported here
     *         as a possibility.
     */
    @Override
    public boolean canSeek() {
        return true;
    }

    /**
     * Moves this track to {@code micros}, and with it, because one demultiplexer serves both, the
     * pictures. That coupling is not a leak: a seek on either track of a container is a seek on the
     * container, and the shim makes a target both tracks ask for cost one move rather than two.
     *
     * <p>Sample-accurate: the container lands on an independently decodable picture at or before the
     * target and the samples before it are dropped rather than delivered, so what the engine is
     * handed starts at {@code micros} and not at whatever the video's structure allowed.
     */
    @Override
    public void seek(long micros) {
        if (micros < 0) {
            throw new IllegalArgumentException("seek target must not be negative, got " + micros);
        }
        if (!closed) {
            media.seekAudio(micros, generation);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            media.releaseAudio(generation);
        }
    }

    /** @return whether this source has been closed, which is what makes it no longer handable */
    boolean isClosed() {
        return closed;
    }
}

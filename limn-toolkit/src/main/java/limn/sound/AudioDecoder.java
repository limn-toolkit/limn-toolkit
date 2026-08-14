package limn.sound;

/**
 * Decodes encoded audio bytes into an {@link AudioClip}. Exactly one is installed at a time (the
 * backend's at startup, or a fake in a test), and which formats work is that implementation's to
 * document; anything it does not recognise throws rather than returning silence.
 *
 * <p><b>Both methods run on the caller's thread and are allowed to be slow.</b> An implementation
 * may read a whole file, allocate for the whole of it and decode; none of that has to be deferred,
 * split or made cancellable here. That freedom is deliberate and is paid for one level up: the
 * {@link Sounds} facade owns the asynchronous forms, so the only thing an implementation must not
 * do is assume which thread it is on; in particular it must not touch widgets, and it must not
 * assume the UI thread is available to it.
 */
@FunctionalInterface
public interface AudioDecoder {

    /**
     * Decodes a whole clip, on the calling thread. Cost scales with the decoded length, not the
     * file length: a four-minute track is tens of megabytes of PCM and hundreds of milliseconds of
     * work, which is why streaming exists.
     *
     * @param fileBytes the full encoded file
     * @return the decoded PCM clip
     * @throws RuntimeException if the bytes are not a supported format
     */
    AudioClip decode(byte[] fileBytes);

    /**
     * Opens {@code file} for incremental decoding, the streamed-music path
     * (see {@link AudioStreamSource}). Which formats stream is a smaller set than which decode, and
     * is the implementation's to document. Default: unsupported.
     *
     * <p>On the calling thread, and <b>open is not required to be cheap</b>: sniffing the format
     * needs a read, and a container that has to be seekable in memory may be read whole and a first
     * frame decoded to learn the channel count and sample rate before this returns. What streaming
     * saves is the decoded audio, not the encoded file.
     *
     * @throws RuntimeException if the format cannot be streamed (decode fully
     *                          with {@link #decode} instead)
     */
    default AudioStreamSource openStream(java.nio.file.Path file) {
        throw new UnsupportedOperationException("this decoder cannot stream " + file);
    }
}

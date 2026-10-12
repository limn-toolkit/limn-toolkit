package limn.backend.lwjgl;

import limn.io.MalformedContentException;

import limn.sound.AudioClip;
import limn.sound.AudioDecoder;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import static org.lwjgl.stb.STBVorbis.stb_vorbis_close;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_get_info;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_get_samples_short_interleaved;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_open_memory;

/**
 * Decodes encoded audio into a 16-bit {@link AudioClip}. Supports what LWJGL's
 * bundled natives can decode without extra dependencies:
 * <ul>
 *   <li><b>WAV</b> (uncompressed RIFF/PCM: 8/16/24-bit integer or 32-bit float,
 *       mono/stereo), parsed here;</li>
 *   <li><b>Ogg Vorbis</b>, decoded with stb_vorbis;</li>
 *   <li><b>MP3</b>, decoded with JLayer (pure Java, no natives; LGPL jar).</li>
 * </ul>
 * MP4/AAC are rejected with a clear message: no bundled decoder handles them.
 *
 * <p>A WAV or Ogg Vorbis at a sample rate above {@link OpenAlAudio#MAX_SAMPLE_RATE}
 * is refused with {@link UnsupportedOperationException}, whole or streamed:
 * a well-formed file the device would play slow. A rate of zero or less is a
 * broken header and is refused as malformed. MP3 needs no check, because the
 * format has no rate above 48 kHz.
 */
final class AudioFileDecoder implements AudioDecoder {

    static {
        // jlayer, the MP3 decoder, has no module name and so sits on the class path, which a named
        // module does not read unless it says so. Said here, before any line that names a jlayer
        // class is linked; on the class path this module is unnamed and reads everything already.
        Module self = AudioFileDecoder.class.getModule();
        ClassLoader loader = AudioFileDecoder.class.getClassLoader();
        if (self.isNamed() && loader != null) {
            self.addReads(loader.getUnnamedModule());
        }
    }

    /**
     * Decodes {@code fileBytes} fully into 16-bit PCM on the calling thread:
     * no I/O, but the whole clip is decoded before this returns, and the result
     * is roughly ten times the encoded size for a compressed format. Long
     * enough to drop frames for anything past a short sound effect, so the
     * asynchronous form belongs on the facade that hands this call to a worker;
     * this is the backend's decoder SPI and has none of its own.
     *
     * @param fileBytes the encoded file; at least 12 bytes, enough to identify
     * @throws limn.io.MalformedContentException if the bytes are too few or malformed
     * @throws UnsupportedOperationException if no bundled decoder handles them
     */
    @Override
    public AudioClip decode(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 12) {
            throw new MalformedContentException("audio", "not enough bytes to identify an audio format");
        }
        if (isRiffWave(fileBytes)) {
            return decodeWav(fileBytes);
        }
        if (tagAt(fileBytes, 0, "OggS")) {
            return decodeOgg(fileBytes);
        }
        if (isMp3(fileBytes)) {
            return decodeMp3(fileBytes);
        }
        throw new UnsupportedOperationException(
                "unsupported audio format (" + sniff(fileBytes) + "); this backend decodes "
                        + "WAV, Ogg Vorbis and MP3; MP4/AAC need an added native decoder");
    }

    // ------------------------------------------------------------------ WAV

    private static AudioClip decodeWav(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int formatCode = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int dataOffset = -1;
        int dataLength = 0;

        // Walk the RIFF chunk list (each chunk is word-aligned). Chunk sizes are unsigned 32-bit
        // and the arithmetic is done in long, as the streaming reader does: a declared size just
        // under 2^31 added to an int offset wrapped negative, escaped the clamp, and sent the walk
        // to a negative position, where reading a tag threw instead of tolerating the truncation.
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = tag(bytes, pos);
            long declared = bb.getInt(pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            int size = (int) Math.min(declared, bytes.length - body); // tolerate a truncated final chunk
            if ("fmt ".equals(id) && size >= 16) {
                formatCode = bb.getShort(body) & 0xFFFF;
                channels = bb.getShort(body + 2) & 0xFFFF;
                sampleRate = bb.getInt(body + 4);
                bits = bb.getShort(body + 14) & 0xFFFF;
                // WAVE_FORMAT_EXTENSIBLE: the real format is the sub-format GUID.
                if (formatCode == 0xFFFE && size >= 40) {
                    formatCode = bb.getShort(body + 24) & 0xFFFF;
                }
            } else if ("data".equals(id)) {
                dataOffset = body;
                dataLength = size;
            }
            pos = body + size + (size & 1);
        }

        // Malformed before unsupported: a file with no format, no channels or no data is broken,
        // and UnsupportedOperationException is for a well-formed file this decoder cannot play.
        if (formatCode < 0 || channels == 0 || dataOffset < 0) {
            throw new MalformedContentException("audio", "malformed WAV (missing fmt/data)");
        }
        playableRate(sampleRate, "WAV");
        if (channels != 1 && channels != 2) {
            throw new UnsupportedOperationException("WAV must be mono or stereo, got " + channels + " channels");
        }
        short[] samples = toPcm16(bb, dataOffset, dataLength, formatCode, bits);
        if (samples.length % channels != 0) {
            samples = java.util.Arrays.copyOf(samples, samples.length - samples.length % channels);
        }
        return AudioClip.of(samples, channels, sampleRate);
    }

    /** Converts a WAV data chunk to interleaved signed 16-bit PCM. */
    private static short[] toPcm16(ByteBuffer bb, int offset, int length, int formatCode, int bits) {
        if (formatCode == 1 && bits == 16) {
            short[] out = new short[length / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = bb.getShort(offset + i * 2);
            }
            return out;
        }
        if (formatCode == 1 && bits == 8) {
            // 8-bit WAV is unsigned; center at 0 and scale to 16-bit.
            short[] out = new short[length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (short) (((bb.get(offset + i) & 0xFF) - 128) << 8);
            }
            return out;
        }
        if (formatCode == 1 && bits == 24) {
            int frames = length / 3;
            short[] out = new short[frames];
            for (int i = 0; i < frames; i++) {
                int b0 = bb.get(offset + i * 3) & 0xFF;
                int b1 = bb.get(offset + i * 3 + 1) & 0xFF;
                int b2 = bb.get(offset + i * 3 + 2); // top byte carries the sign
                int sample = (b2 << 16) | (b1 << 8) | b0;
                out[i] = (short) (sample >> 8); // 24-bit -> 16-bit
            }
            return out;
        }
        if (formatCode == 3 && bits == 32) {
            short[] out = new short[length / 4];
            for (int i = 0; i < out.length; i++) {
                float f = bb.getFloat(offset + i * 4);
                float clamped = Math.max(-1f, Math.min(1f, f));
                out[i] = (short) Math.round(clamped * Short.MAX_VALUE);
            }
            return out;
        }
        throw new UnsupportedOperationException(
                "unsupported WAV encoding (format " + formatCode + ", " + bits + "-bit)");
    }

    /**
     * The sample rate a WAV or Ogg header states, refused unless the device can play it at its
     * speed. Called once the rest of the header is known to be there, so that a missing chunk is
     * still reported as that, and before any other unsupported feature is: malformed before
     * unsupported, as everywhere in this class.
     *
     * <p>A rate of zero or less is a broken header: both formats store it unsigned in 32 bits, and a
     * value at 2<sup>31</sup> or above reads as a negative {@code int} here and in stb_vorbis's
     * info, so the message prints it unsigned, as the file wrote it. A rate above
     * {@link OpenAlAudio#MAX_SAMPLE_RATE} is a real file the device would play slow, which is
     * what {@link UnsupportedOperationException} is kept for.
     *
     * @param rate   the rate as read, a signed view of the unsigned field
     * @param format the format's name, which starts the message
     * @throws limn.io.MalformedContentException if {@code rate} is zero or reads negative
     * @throws UnsupportedOperationException if {@code rate} is above the highest this plays
     */
    private static void playableRate(int rate, String format) {
        if (rate <= 0) {
            throw new MalformedContentException("audio", "malformed " + format + ": sample rate "
                    + Integer.toUnsignedString(rate) + " Hz");
        }
        if (rate > OpenAlAudio.MAX_SAMPLE_RATE) {
            throw new UnsupportedOperationException(format + " at " + rate
                    + " Hz: this backend plays sample rates up to " + OpenAlAudio.MAX_SAMPLE_RATE + " Hz");
        }
    }

    // ------------------------------------------------------------------ OGG

    /** Frames an Ogg is decoded in at a time: about 190 ms at 22050 Hz. */
    private static final int OGG_CHUNK_FRAMES = 4096;

    private static AudioClip decodeOgg(byte[] bytes) {
        ByteBuffer encoded = OffHeap.copyOf(bytes);
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.IntBuffer error = stack.mallocInt(1);
                long decoder = stb_vorbis_open_memory(encoded, error, null);
                if (decoder == 0L) {
                    throw new MalformedContentException("audio", "stb_vorbis could not open Ogg (error " + error.get(0) + ")");
                }
                try {
                    STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                    stb_vorbis_get_info(decoder, info);
                    int channels = info.channels();
                    int sampleRate = info.sample_rate();
                    playableRate(sampleRate, "Ogg");
                    if (channels != 1 && channels != 2) {
                        throw new UnsupportedOperationException(
                                "Ogg must be mono or stereo, got " + channels + " channels");
                    }
                    // Decoded in chunks until the stream ends, never sized from its stated
                    // length: that is the last page's granule position, a number the file
                    // chooses, and a quarter second claiming 2^30 stereo frames overflowed the
                    // allocation while a smaller lie asked for gigabytes.
                    ShortBuffer chunk = MemoryUtil.memAllocShort(OGG_CHUNK_FRAMES * channels);
                    try {
                        short[] out = new short[OGG_CHUNK_FRAMES * channels];
                        int used = 0;
                        while (true) {
                            chunk.clear();
                            int frames = stb_vorbis_get_samples_short_interleaved(
                                    decoder, channels, chunk);
                            if (frames <= 0) {
                                break;
                            }
                            int length = frames * channels;
                            if (used + length > out.length) {
                                out = java.util.Arrays.copyOf(out, grown(out.length, used + length, "Ogg"));
                            }
                            chunk.get(0, out, used, length); // absolute: never moves the position
                            used += length;
                        }
                        return AudioClip.of(java.util.Arrays.copyOf(out, used), channels, sampleRate);
                    } finally {
                        MemoryUtil.memFree(chunk);
                    }
                } finally {
                    stb_vorbis_close(decoder);
                }
            }
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    // -------------------------------------------------------------- streaming

    /**
     * Opens {@code file} for streaming playback, sniffing its first 12 bytes to
     * choose the reader. <b>Blocks on the calling thread, and how long depends
     * entirely on the format</b>, so a facade wrapping this must assume the
     * worst of the three:
     * <ul>
     *   <li><b>WAV</b>: opens the file and walks its RIFF chunk list with
     *       positional reads. A handful of small reads; nothing is held in
     *       memory but the open channel, and {@code readFrames} reads on
     *       demand.</li>
     *   <li><b>Ogg Vorbis</b>: reads the <em>whole encoded file</em> into the
     *       heap and copies it into native memory, which stb_vorbis then reads
     *       from for the life of the source. A music track is a few megabytes
     *       compressed, and that is what streaming buys: the decoded form would
     *       be an order of magnitude larger.</li>
     *   <li><b>MP3</b>: reads the whole encoded file and decodes its first
     *       frame to learn the channel count and sample rate.</li>
     * </ul>
     * So for two of the three formats this is a full file read plus a decode
     * before it returns, which is a frozen window if it happens on the UI
     * thread. There is no asynchronous form here on purpose: this is the
     * backend's decoder SPI, and the asynchronous form belongs on the facade
     * that applications call, which can hand this whole call to a worker.
     *
     * <p>The returned source is not thread-confined by this class, but it is
     * not safe for concurrent use: one owner, one thread at a time.
     *
     * @param file the audio file; must exist and be readable
     * @return a pull-mode source positioned at the start
     * @throws java.io.UncheckedIOException      if the file cannot be read
     * @throws limn.io.MalformedContentException     if the file is malformed
     * @throws UnsupportedOperationException     if no bundled reader handles it, or its
     *                                           sample rate is above the highest this plays
     */
    @Override
    public limn.sound.AudioStreamSource openStream(java.nio.file.Path file) {
        byte[] head;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            head = in.readNBytes(12);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException("opening audio stream " + file, error);
        }
        if (head.length >= 12 && isRiffWave(head)) {
            return new WavStreamSource(file);
        }
        if (tagAt(head, 0, "OggS")) {
            return new OggStreamSource(file);
        }
        if (isMp3(head)) {
            return new Mp3StreamSource(file);
        }
        throw new UnsupportedOperationException(
                "cannot stream " + file + " (" + sniff(head) + "); streaming supports "
                        + "Ogg Vorbis, MP3 and 16-bit PCM WAV");
    }

    /**
     * Chunked reader over a 16-bit PCM WAV file: pure Java, ~one page of
     * buffered file I/O per {@link #readFrames} call. Other WAV encodings
     * throw at open: decode those fully with {@link #decode} instead.
     */
    static final class WavStreamSource implements limn.sound.AudioStreamSource {
        private final java.nio.channels.FileChannel channel;
        private final int channels;
        private final int sampleRate;
        private final long dataStart;
        private final long dataEnd;
        private long position;

        WavStreamSource(java.nio.file.Path file) {
            java.nio.channels.FileChannel open = null;
            try {
                open = java.nio.channels.FileChannel.open(
                        file, java.nio.file.StandardOpenOption.READ);
                // Walk the RIFF chunk list on disk (each chunk word-aligned).
                // Chunk sizes are UNSIGNED 32-bit: read via long so >2 GiB
                // data chunks (or 0xFFFFFFFF placeholders) can't go negative.
                ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                long pos = 12;
                int formatCode = -1;
                int foundChannels = 0;
                int foundRate = 0;
                int bits = 0;
                long foundDataStart = -1;
                long foundDataLen = 0;
                long size = open.size();
                while (pos + 8 <= size) {
                    header.clear();
                    if (open.read(header, pos) < 8) {
                        break;
                    }
                    header.flip();
                    byte[] id = new byte[4];
                    header.get(id);
                    long chunkSize = header.getInt() & 0xFFFFFFFFL;
                    long body = pos + 8;
                    if (body + chunkSize > size) {
                        chunkSize = Math.max(0, size - body); // truncated/bogus final chunk
                    }
                    String tag = new String(id, java.nio.charset.StandardCharsets.US_ASCII);
                    if ("fmt ".equals(tag) && chunkSize >= 16) {
                        ByteBuffer fmt = ByteBuffer.allocate((int) Math.min(chunkSize, 40))
                                .order(ByteOrder.LITTLE_ENDIAN);
                        open.read(fmt, body);
                        formatCode = fmt.getShort(0) & 0xFFFF;
                        foundChannels = fmt.getShort(2) & 0xFFFF;
                        foundRate = fmt.getInt(4);
                        bits = fmt.getShort(14) & 0xFFFF;
                        if (formatCode == 0xFFFE && chunkSize >= 40) {
                            formatCode = fmt.getShort(24) & 0xFFFF;
                        }
                    } else if ("data".equals(tag)) {
                        foundDataStart = body;
                        foundDataLen = chunkSize;
                    }
                    pos = body + chunkSize + (chunkSize & 1);
                }
                // Malformed before unsupported, as the whole-file reader does.
                if (formatCode < 0 || foundChannels == 0 || foundDataStart < 0) {
                    throw new MalformedContentException("audio", "malformed WAV: " + file);
                }
                playableRate(foundRate, "WAV");
                if (formatCode != 1 || bits != 16) {
                    throw new UnsupportedOperationException(
                            "WAV streaming supports 16-bit PCM only (format " + formatCode
                                    + ", " + bits + "-bit); use Sounds.load for the rest");
                }
                if (foundChannels != 1 && foundChannels != 2) {
                    throw new UnsupportedOperationException(
                            "WAV must be mono or stereo, got " + foundChannels + " channels");
                }
                channels = foundChannels;
                sampleRate = foundRate;
                dataStart = foundDataStart;
                // Whole frames only: a stray trailing byte must not shift channels.
                long usable = foundDataLen - foundDataLen % (channels * 2L);
                dataEnd = foundDataStart + usable;
                position = dataStart;
                channel = open;
                open = null; // constructed successfully: ownership transferred
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException("opening WAV stream " + file, error);
            } finally {
                if (open != null) {
                    try {
                        open.close(); // any construction failure releases the file
                    } catch (java.io.IOException ignored) {
                        // nothing actionable while already failing
                    }
                }
            }
        }

        @Override
        public int channels() {
            return channels;
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            int frameBytes = channels * 2;
            long remaining = dataEnd - position;
            int frames = (int) Math.min(maxFrames, remaining / frameBytes);
            if (frames <= 0) {
                return 0;
            }
            ByteBuffer bytes = ByteBuffer.allocate(frames * frameBytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
            try {
                int read = channel.read(bytes, position);
                frames = Math.max(0, read) / frameBytes;
            } catch (java.io.IOException error) {
                return 0; // treat I/O failure as end of stream (best-effort audio)
            }
            position += (long) frames * frameBytes;
            bytes.flip();
            bytes.asShortBuffer().get(out, 0, frames * channels);
            return frames;
        }

        @Override
        public void reset() {
            position = dataStart;
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (java.io.IOException ignored) {
                // releasing on shutdown: nothing actionable
            }
        }
    }

    /**
     * stb_vorbis pull-mode reader over an Ogg file. The ENCODED file is held
     * in one native buffer (a music track is a few MB compressed; streaming
     * saves the ~40 MB decoded form, which is the point) and opened with
     * {@code stb_vorbis_open_memory}: file access stays in Java, so non-ASCII
     * paths work on every platform ({@code stb_vorbis_open_filename} funnels
     * the path through ASCII and mangles accented/CJK characters).
     */
    static final class OggStreamSource implements limn.sound.AudioStreamSource {
        private long handle;
        private ByteBuffer encoded;
        private final int channels;
        private final int sampleRate;
        private ShortBuffer scratch;

        OggStreamSource(java.nio.file.Path file) {
            byte[] bytes = limn.io.Resources.bytes(file, "Ogg stream");
            encoded = OffHeap.copyOf(bytes);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.IntBuffer error = stack.mallocInt(1);
                handle = stb_vorbis_open_memory(encoded, error, null);
                if (handle == 0L) {
                    close();
                    throw new MalformedContentException("audio", 
                            "stb_vorbis could not open " + file + " (error " + error.get(0) + ")");
                }
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                stb_vorbis_get_info(handle, info);
                channels = info.channels();
                sampleRate = info.sample_rate();
                try {
                    playableRate(sampleRate, "Ogg");
                    if (channels != 1 && channels != 2) {
                        throw new UnsupportedOperationException(
                                "Ogg must be mono or stereo, got " + channels + " channels");
                    }
                } catch (RuntimeException refused) {
                    close(); // a refused file releases its decoder and the native copy of its bytes
                    throw refused;
                }
            }
        }

        @Override
        public int channels() {
            return channels;
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            if (handle == 0L) {
                return 0;
            }
            int shorts = maxFrames * channels;
            if (scratch == null || scratch.capacity() < shorts) {
                if (scratch != null) {
                    MemoryUtil.memFree(scratch);
                }
                scratch = MemoryUtil.memAllocShort(shorts);
            }
            scratch.clear().limit(shorts);
            int frames = stb_vorbis_get_samples_short_interleaved(handle, channels, scratch);
            if (frames > 0) {
                scratch.get(0, out, 0, frames * channels);
            }
            return Math.max(0, frames);
        }

        @Override
        public void reset() {
            if (handle != 0L) {
                org.lwjgl.stb.STBVorbis.stb_vorbis_seek_start(handle);
            }
        }

        @Override
        public void close() {
            if (handle != 0L) {
                stb_vorbis_close(handle);
                handle = 0L;
            }
            if (encoded != null) {
                MemoryUtil.memFree(encoded);
                encoded = null;
            }
            if (scratch != null) {
                MemoryUtil.memFree(scratch);
                scratch = null;
            }
        }
    }

    // ------------------------------------------------------------------ MP3
    // Decoded with JLayer (javazoom): pure Java, no natives, LGPL jar. The
    // decoder is frame-based (~26 ms / 1152 samples per MPEG frame), which
    // maps directly onto both the full decode and the streaming source.

    /** ID3v2 tag or a bare MPEG audio sync word. */
    private static boolean isMp3(byte[] b) {
        return tagAt(b, 0, "ID3")
                || (b.length > 1 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0);
    }

    /**
     * Where the MPEG audio starts: past an ID3v2 tag when the file opens with one, else at 0. The
     * decoder is handed the bytes from here, because JLayer reads a leading tag by allocating the
     * size its header states, before reading any of it: sixteen bytes could ask for 256 MB, and
     * a looping stream asked again on every loop.
     *
     * @throws limn.io.MalformedContentException if the tag's size is malformed or runs past the file
     */
    static int audioStart(byte[] b) {
        if (b.length < 10 || !tagAt(b, 0, "ID3")) {
            return 0;
        }
        int size = 0;
        for (int i = 6; i < 10; i++) {
            int digit = b[i] & 0xFF;
            if (digit >= 0x80) {
                throw new MalformedContentException("audio", "malformed MP3: the ID3v2 tag's size is not "
                        + "synchsafe");
            }
            size = size << 7 | digit;
        }
        long end = 10L + size + ((b[5] & 0x10) != 0 ? 10 : 0);
        if (end > b.length) {
            throw new MalformedContentException("audio", "malformed MP3: the ID3v2 tag claims " + size
                    + " bytes and the file holds " + (b.length - 10) + " after its header");
        }
        return (int) end;
    }

    private static java.io.InputStream audioAfterTag(byte[] bytes) {
        int start = audioStart(bytes);
        return new java.io.ByteArrayInputStream(bytes, start, bytes.length - start);
    }

    /**
     * The most samples a file decoded whole may come to: 2<sup>28</sup>, half a gigabyte, some
     * fifty minutes of stereo at 44.1 kHz. A compressed file says nothing true about its length
     * before it is decoded, and one that never ends would otherwise be decoded until the heap
     * did; a longer one is for {@link #openStream}.
     */
    static final int MAX_DECODED_SAMPLES = 1 << 28;

    /** The next size of a growing sample array, refused past the ceiling. */
    private static int grown(int current, int needed, String format) {
        if (needed > MAX_DECODED_SAMPLES) {
            throw new UnsupportedOperationException(format + " decodes to more than " + MAX_DECODED_SAMPLES
                    + " samples, too long to decode whole: stream it");
        }
        return (int) Math.min(MAX_DECODED_SAMPLES, Math.max((long) current * 2, needed));
    }

    private static AudioClip decodeMp3(byte[] bytes) {
        javazoom.jl.decoder.Bitstream bitstream =
                new javazoom.jl.decoder.Bitstream(audioAfterTag(bytes));
        javazoom.jl.decoder.Decoder decoder = new javazoom.jl.decoder.Decoder();
        short[] pcm = new short[1 << 16];
        int used = 0;
        int channels = 0;
        int sampleRate = 0;
        try {
            while (true) {
                javazoom.jl.decoder.Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }
                javazoom.jl.decoder.SampleBuffer frame =
                        (javazoom.jl.decoder.SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (channels == 0) {
                    channels = frame.getChannelCount();
                    sampleRate = frame.getSampleFrequency();
                }
                int length = frame.getBufferLength();
                if (used + length > pcm.length) {
                    pcm = java.util.Arrays.copyOf(pcm, grown(pcm.length, used + length, "MP3"));
                }
                // The decoder REUSES its output buffer; copy before the next frame.
                System.arraycopy(frame.getBuffer(), 0, pcm, used, length);
                used += length;
                bitstream.closeFrame();
            }
            bitstream.close();
        } catch (javazoom.jl.decoder.JavaLayerException | RuntimeException error) {
            // The decoder is a library's: on damaged frames it also throws what it never
            // declared, an index past one of its own tables, and that is the file's fault too.
            if (error instanceof UnsupportedOperationException tooLong) {
                throw tooLong;
            }
            throw new MalformedContentException("audio", "malformed MP3", error);
        }
        if (channels == 0 || used == 0) {
            throw new MalformedContentException("audio", "MP3 contains no decodable audio frames");
        }
        return AudioClip.of(java.util.Arrays.copyOf(pcm, used - used % channels),
                channels, sampleRate);
    }

    /**
     * JLayer frame-by-frame reader; the ENCODED bytes stay in memory (a few MB)
     * and decoding happens per MPEG frame with a small carry buffer. Reset
     * reopens the bitstream over the same bytes.
     */
    static final class Mp3StreamSource implements limn.sound.AudioStreamSource {
        private final byte[] encoded;
        private javazoom.jl.decoder.Bitstream bitstream;
        private javazoom.jl.decoder.Decoder decoder;
        private final int channels;
        private final int sampleRate;
        private short[] carry = new short[2304]; // one MPEG frame, stereo
        private int carryLen;
        private int carryPos;
        private boolean ended;

        Mp3StreamSource(java.nio.file.Path file) {
            encoded = limn.io.Resources.bytes(file, "MP3 stream");
            openBitstream();
            // Prime the first frame: validates the file and learns the format
            // before playStream asks for channels()/sampleRate().
            if (!decodeIntoCarry()) {
                close();
                throw new MalformedContentException("audio", "MP3 contains no decodable audio frames: " + file);
            }
            channels = primedChannels;
            sampleRate = primedRate;
        }

        private int primedChannels;
        private int primedRate;

        private void openBitstream() {
            bitstream = new javazoom.jl.decoder.Bitstream(audioAfterTag(encoded));
            decoder = new javazoom.jl.decoder.Decoder();
            ended = false;
        }

        /** Decodes ONE MPEG frame into the carry buffer. @return false at EOF. */
        private boolean decodeIntoCarry() {
            if (ended || bitstream == null) {
                return false;
            }
            try {
                javazoom.jl.decoder.Header header = bitstream.readFrame();
                if (header == null) {
                    ended = true;
                    return false;
                }
                javazoom.jl.decoder.SampleBuffer frame =
                        (javazoom.jl.decoder.SampleBuffer) decoder.decodeFrame(header, bitstream);
                primedChannels = frame.getChannelCount();
                primedRate = frame.getSampleFrequency();
                int length = frame.getBufferLength();
                if (carry.length < length) {
                    carry = new short[length];
                }
                System.arraycopy(frame.getBuffer(), 0, carry, 0, length);
                carryLen = length;
                carryPos = 0;
                bitstream.closeFrame();
                return length > 0;
            } catch (javazoom.jl.decoder.JavaLayerException | RuntimeException error) {
                ended = true; // best-effort: a corrupt tail ends the stream, whatever the decoder threw
                return false;
            }
        }

        @Override
        public int channels() {
            return channels;
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            int wanted = maxFrames * channels;
            int written = 0;
            while (written < wanted) {
                if (carryPos >= carryLen && !decodeIntoCarry()) {
                    break;
                }
                int chunk = Math.min(wanted - written, carryLen - carryPos);
                System.arraycopy(carry, carryPos, out, written, chunk);
                carryPos += chunk;
                written += chunk;
            }
            return written / channels;
        }

        @Override
        public void reset() {
            closeBitstream();
            openBitstream();
            carryLen = 0;
            carryPos = 0;
        }

        @Override
        public void close() {
            closeBitstream();
        }

        private void closeBitstream() {
            if (bitstream != null) {
                try {
                    bitstream.close();
                } catch (javazoom.jl.decoder.JavaLayerException ignored) {
                    // releasing on shutdown: nothing actionable
                }
                bitstream = null;
                decoder = null;
            }
        }
    }

    // -------------------------------------------------------------- sniffing

    private static boolean isRiffWave(byte[] b) {
        return tagAt(b, 0, "RIFF") && tagAt(b, 8, "WAVE");
    }

    private static String sniff(byte[] b) {
        if (tagAt(b, 0, "ID3") || (b.length > 1 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0)) {
            return "MP3";
        }
        if (b.length >= 8 && tagAt(b, 4, "ftyp")) {
            return "MP4/M4A";
        }
        return "unknown";
    }

    private static boolean tagAt(byte[] b, int offset, String ascii) {
        if (offset + ascii.length() > b.length) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if (b[offset + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String tag(byte[] b, int offset) {
        return new String(b, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }
}

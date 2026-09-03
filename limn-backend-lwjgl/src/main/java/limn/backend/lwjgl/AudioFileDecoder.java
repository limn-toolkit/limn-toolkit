package limn.backend.lwjgl;

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
import static org.lwjgl.stb.STBVorbis.stb_vorbis_stream_length_in_samples;

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
 */
final class AudioFileDecoder implements AudioDecoder {

    /**
     * Decodes {@code fileBytes} fully into 16-bit PCM on the calling thread:
     * no I/O, but the whole clip is decoded before this returns, and the result
     * is roughly ten times the encoded size for a compressed format. Long
     * enough to drop frames for anything past a short sound effect, so the
     * asynchronous form belongs on the facade that hands this call to a worker;
     * this is the backend's decoder SPI and has none of its own.
     *
     * @param fileBytes the encoded file; at least 12 bytes, enough to identify
     * @throws IllegalArgumentException      if the bytes are too few or malformed
     * @throws UnsupportedOperationException if no bundled decoder handles them
     */
    @Override
    public AudioClip decode(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 12) {
            throw new IllegalArgumentException("not enough bytes to identify an audio format");
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

        if (channels != 1 && channels != 2) {
            throw new UnsupportedOperationException("WAV must be mono or stereo, got " + channels + " channels");
        }
        if (sampleRate <= 0 || dataOffset < 0) {
            throw new IllegalArgumentException("malformed WAV (missing fmt/data)");
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

    // ------------------------------------------------------------------ OGG

    private static AudioClip decodeOgg(byte[] bytes) {
        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length);
        try {
            encoded.put(bytes);
            encoded.flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.IntBuffer error = stack.mallocInt(1);
                long decoder = stb_vorbis_open_memory(encoded, error, null);
                if (decoder == 0L) {
                    throw new IllegalArgumentException("stb_vorbis could not open Ogg (error " + error.get(0) + ")");
                }
                try {
                    STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                    stb_vorbis_get_info(decoder, info);
                    int channels = info.channels();
                    int sampleRate = info.sample_rate();
                    if (channels != 1 && channels != 2) {
                        throw new UnsupportedOperationException(
                                "Ogg must be mono or stereo, got " + channels + " channels");
                    }
                    int frames = stb_vorbis_stream_length_in_samples(decoder);
                    ShortBuffer pcm = MemoryUtil.memAllocShort(frames * channels);
                    try {
                        int decoded = stb_vorbis_get_samples_short_interleaved(decoder, channels, pcm);
                        short[] out = new short[decoded * channels];
                        pcm.get(0, out); // absolute bulk get: never advances/frees by position
                        return AudioClip.of(out, channels, sampleRate);
                    } finally {
                        MemoryUtil.memFree(pcm);
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
     * @throws UnsupportedOperationException     if no bundled reader handles it
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
                if (formatCode != 1 || bits != 16) {
                    throw new UnsupportedOperationException(
                            "WAV streaming supports 16-bit PCM only (format " + formatCode
                                    + ", " + bits + "-bit); use Sounds.load for the rest");
                }
                if ((foundChannels != 1 && foundChannels != 2)
                        || foundRate <= 0 || foundDataStart < 0) {
                    throw new IllegalArgumentException("malformed WAV: " + file);
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
            byte[] bytes;
            try {
                bytes = java.nio.file.Files.readAllBytes(file);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException("reading Ogg stream " + file, error);
            }
            encoded = MemoryUtil.memAlloc(bytes.length);
            encoded.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.IntBuffer error = stack.mallocInt(1);
                handle = stb_vorbis_open_memory(encoded, error, null);
                if (handle == 0L) {
                    close();
                    throw new IllegalArgumentException(
                            "stb_vorbis could not open " + file + " (error " + error.get(0) + ")");
                }
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                stb_vorbis_get_info(handle, info);
                channels = info.channels();
                sampleRate = info.sample_rate();
                if (channels != 1 && channels != 2) {
                    close();
                    throw new UnsupportedOperationException(
                            "Ogg must be mono or stereo, got " + channels + " channels");
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

    private static AudioClip decodeMp3(byte[] bytes) {
        javazoom.jl.decoder.Bitstream bitstream =
                new javazoom.jl.decoder.Bitstream(new java.io.ByteArrayInputStream(bytes));
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
                    pcm = java.util.Arrays.copyOf(pcm,
                            Math.max(pcm.length * 2, used + length));
                }
                // The decoder REUSES its output buffer; copy before the next frame.
                System.arraycopy(frame.getBuffer(), 0, pcm, used, length);
                used += length;
                bitstream.closeFrame();
            }
            bitstream.close();
        } catch (javazoom.jl.decoder.JavaLayerException error) {
            throw new IllegalArgumentException("malformed MP3", error);
        }
        if (channels == 0 || used == 0) {
            throw new IllegalArgumentException("MP3 contains no decodable audio frames");
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
            try {
                encoded = java.nio.file.Files.readAllBytes(file);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException("reading MP3 stream " + file, error);
            }
            openBitstream();
            // Prime the first frame: validates the file and learns the format
            // before playStream asks for channels()/sampleRate().
            if (!decodeIntoCarry()) {
                close();
                throw new IllegalArgumentException("MP3 contains no decodable audio frames: " + file);
            }
            channels = primedChannels;
            sampleRate = primedRate;
        }

        private int primedChannels;
        private int primedRate;

        private void openBitstream() {
            bitstream = new javazoom.jl.decoder.Bitstream(
                    new java.io.ByteArrayInputStream(encoded));
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
            } catch (javazoom.jl.decoder.JavaLayerException error) {
                ended = true; // best-effort: a corrupt tail ends the stream
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

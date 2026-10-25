package limn.backend.lwjgl;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;
import limn.io.MalformedContentException;
import limn.sound.AudioClip;
import limn.sound.AudioStreamSource;

/**
 * MP3, decoded with JLayer ({@code javazoom:jlayer}): pure Java, no natives, an LGPL jar of its
 * own. Every line of the backend that names a JLayer class is in this class, and
 * {@link AudioFileDecoder} reaches it only for a file it has recognised as MP3, once it has found
 * JLayer there. An application may exclude that jar, and then loses MP3 and nothing else: this
 * class is never loaded, which matters because it could not be, the verifier loading every
 * exception type a method catches before the class links, and WAV and Ogg Vorbis decode as they
 * did.
 *
 * <p>The decoder is frame-based (~26 ms / 1152 samples per MPEG frame), which maps directly onto
 * both the full decode and the streaming source.
 */
final class Mp3Decoder {

    private Mp3Decoder() {
    }

    /**
     * Decodes a whole MP3, as {@link AudioFileDecoder#decode} documents.
     *
     * @throws MalformedContentException if no frame decodes, or the decoder fails on one
     * @throws UnsupportedOperationException if the audio is too long to decode whole
     */
    static AudioClip decode(byte[] bytes) {
        Bitstream bitstream = new Bitstream(AudioFileDecoder.audioAfterTag(bytes));
        Decoder decoder = new Decoder();
        short[] pcm = new short[1 << 16];
        int used = 0;
        int channels = 0;
        int sampleRate = 0;
        try {
            while (true) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }
                SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (channels == 0) {
                    channels = frame.getChannelCount();
                    sampleRate = frame.getSampleFrequency();
                }
                int length = frame.getBufferLength();
                if (used + length > pcm.length) {
                    pcm = java.util.Arrays.copyOf(pcm, AudioFileDecoder.grown(pcm.length, used + length, "MP3"));
                }
                // The decoder REUSES its output buffer; copy before the next frame.
                System.arraycopy(frame.getBuffer(), 0, pcm, used, length);
                used += length;
                bitstream.closeFrame();
            }
            bitstream.close();
        } catch (JavaLayerException | RuntimeException error) {
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
    static final class StreamSource implements AudioStreamSource {
        private final byte[] encoded;
        private Bitstream bitstream;
        private Decoder decoder;
        private final int channels;
        private final int sampleRate;
        private short[] carry = new short[2304]; // one MPEG frame, stereo
        private int carryLen;
        private int carryPos;
        private boolean ended;

        StreamSource(java.nio.file.Path file) {
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
            bitstream = new Bitstream(AudioFileDecoder.audioAfterTag(encoded));
            decoder = new Decoder();
            ended = false;
        }

        /** Decodes ONE MPEG frame into the carry buffer. @return false at EOF. */
        private boolean decodeIntoCarry() {
            if (ended || bitstream == null) {
                return false;
            }
            try {
                Header header = bitstream.readFrame();
                if (header == null) {
                    ended = true;
                    return false;
                }
                SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, bitstream);
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
            } catch (JavaLayerException | RuntimeException error) {
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
                } catch (JavaLayerException ignored) {
                    // releasing on shutdown: nothing actionable
                }
                bitstream = null;
                decoder = null;
            }
        }
    }
}

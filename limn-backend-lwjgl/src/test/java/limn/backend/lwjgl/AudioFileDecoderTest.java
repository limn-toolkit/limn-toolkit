package limn.backend.lwjgl;

import limn.sound.AudioClip;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WAV parsing and format sniffing of {@link AudioFileDecoder}, driven with
 * in-memory files, so it needs no audio device (the Ogg path is exercised
 * manually/at runtime, since it needs the stb_vorbis native).
 */
class AudioFileDecoderTest {

    private final AudioFileDecoder decoder = new AudioFileDecoder();

    @Test
    void decodesMono16BitWav() {
        short[] pcm = {0, 1000, -1000, 32000, -32000, 0};
        AudioClip clip = decoder.decode(wav(pcm, 1, 22_050, 16));
        assertEquals(1, clip.channels());
        assertEquals(22_050, clip.sampleRate());
        assertEquals(6, clip.frameCount());
        assertArrayEquals(pcm, clip.samples());
    }

    @Test
    void decodesStereo16BitWav() {
        short[] pcm = {10, -10, 20, -20, 30, -30, 40, -40};
        AudioClip clip = decoder.decode(wav(pcm, 2, 44_100, 16));
        assertEquals(2, clip.channels());
        assertEquals(44_100, clip.sampleRate());
        assertEquals(4, clip.frameCount());
        assertArrayEquals(pcm, clip.samples());
    }

    @Test
    void decodesUnsigned8BitWav() {
        // 8-bit WAV is unsigned, centered at 128; the decoder recenters to 0.
        byte[] file = wav8(new int[] {128, 255, 0, 192});
        AudioClip clip = decoder.decode(file);
        assertEquals(1, clip.channels());
        short[] s = clip.samples();
        assertEquals(0, s[0]);                 // 128 -> 0
        assertEquals((255 - 128) << 8, s[1]);  // full positive
        assertEquals((0 - 128) << 8, s[2]);    // full negative
    }

    @Test
    void malformedMp3ThrowsClearMessage() {
        // MP3 is now DECODED (JLayer); an ID3 header with no audio frames is
        // recognized as MP3 but fails with a clear malformed-file error.
        byte[] id3 = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 0, 0};
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> decoder.decode(id3));
        assertEquals(true, ex.getMessage().contains("MP3"));
    }

    @Test
    void rejectsMp4WithClearMessage() {
        byte[] ftyp = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
        UnsupportedOperationException ex =
                assertThrows(UnsupportedOperationException.class, () -> decoder.decode(ftyp));
        assertEquals(true, ex.getMessage().contains("MP4"));
    }

    @Test
    void rejectsTooShortInput() {
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[] {'R', 'I', 'F', 'F'}));
    }

    // ------------------------------------------------------------- streaming

    @Test
    void streamsStereo16BitWavInChunksWithResetAndEof() throws Exception {
        // 6 frames of stereo, values encode (frame, channel) for traceability.
        short[] pcm = new short[12];
        for (int f = 0; f < 6; f++) {
            pcm[f * 2] = (short) (f * 10);
            pcm[f * 2 + 1] = (short) (f * 10 + 1);
        }
        java.nio.file.Path file = java.nio.file.Files.createTempFile("limn-stream", ".wav");
        try {
            java.nio.file.Files.write(file, wav(pcm, 2, 44_100, 16));
            try (limn.sound.AudioStreamSource stream = decoder.openStream(file)) {
                assertEquals(2, stream.channels());
                assertEquals(44_100, stream.sampleRate());
                short[] chunk = new short[4]; // 2 frames per read
                assertEquals(2, stream.readFrames(chunk, 2));
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                        new short[] {0, 1, 10, 11}, chunk);
                assertEquals(2, stream.readFrames(chunk, 2));
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                        new short[] {20, 21, 30, 31}, chunk);
                assertEquals(2, stream.readFrames(chunk, 2));
                assertEquals(0, stream.readFrames(chunk, 2), "end of data");
                stream.reset();
                assertEquals(2, stream.readFrames(chunk, 2));
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                        new short[] {0, 1, 10, 11}, chunk, "reset rewinds to frame 0");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void wavStreamingRejectsNonPcm16WithClearMessage() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("limn-stream8", ".wav");
        try {
            java.nio.file.Files.write(file, wav8(new int[] {128, 255, 0, 192}));
            assertThrows(UnsupportedOperationException.class, () -> decoder.openStream(file));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void streamingRejectsUnknownFormats() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("limn-stream", ".m4a");
        try {
            java.nio.file.Files.write(file,
                    new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '});
            assertThrows(UnsupportedOperationException.class, () -> decoder.openStream(file));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void streamingMalformedMp3ThrowsClearMessage() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("limn-stream", ".mp3");
        try {
            java.nio.file.Files.write(file, new byte[] {'I', 'D', '3', 0, 0, 0, 0, 0, 0, 0, 0, 0});
            assertThrows(IllegalArgumentException.class, () -> decoder.openStream(file));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    // ------------------------------------------------------------ WAV writers

    private static byte[] wav(short[] samples, int channels, int rate, int bits) {
        ByteBuffer body = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) {
            body.putShort(s);
        }
        return riff(body.array(), channels, rate, bits, 1);
    }

    private static byte[] wav8(int[] unsignedBytes) {
        byte[] data = new byte[unsignedBytes.length];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) unsignedBytes[i];
        }
        return riff(data, 1, 8_000, 8, 1);
    }

    private static byte[] riff(byte[] data, int channels, int rate, int bits, int formatCode) {
        int byteRate = rate * channels * bits / 8;
        int blockAlign = channels * bits / 8;
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes());
        h.putInt(36 + data.length);
        h.put("WAVE".getBytes());
        h.put("fmt ".getBytes());
        h.putInt(16);
        h.putShort((short) formatCode);
        h.putShort((short) channels);
        h.putInt(rate);
        h.putInt(byteRate);
        h.putShort((short) blockAlign);
        h.putShort((short) bits);
        h.put("data".getBytes());
        h.putInt(data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h.array());
        out.writeBytes(data);
        return out.toByteArray();
    }
}

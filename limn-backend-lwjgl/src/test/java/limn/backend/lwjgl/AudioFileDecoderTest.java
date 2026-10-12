package limn.backend.lwjgl;

import limn.sound.AudioClip;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAV parsing and format sniffing of {@link AudioFileDecoder}, driven with
 * in-memory files, so it needs no audio device; the Ogg path reads the two small
 * fixtures under {@code audio/} through the stb_vorbis native, which needs none
 * either.
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
        limn.io.MalformedContentException ex =
                assertThrows(limn.io.MalformedContentException.class, () -> decoder.decode(id3));
        assertEquals(true, ex.getMessage().contains("MP3"));
    }

    /**
     * An ID3v2 tag's size is the file's to state, and the MP3 decoder allocated whatever it said
     * before reading a byte of it: sixteen bytes claiming the largest size asked for 256 MB, and a
     * stream asked again on every loop. The size is now checked against the file first.
     */
    @Test
    void anId3TagLargerThanItsFileIsRefusedWithoutAllocatingIt() throws Exception {
        byte[] lying = {'I', 'D', '3', 4, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F, 0, 0, 0, 0, 0, 0};
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        limn.io.MalformedContentException refused =
                assertThrows(limn.io.MalformedContentException.class, () -> decoder.decode(lying));
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 8 << 20, "allocated " + allocated + " bytes for a 16-byte file");
        assertTrue(refused.getMessage().contains("ID3"), refused.getMessage());

        java.nio.file.Path file = java.nio.file.Files.createTempFile("limn-id3", ".mp3");
        try {
            java.nio.file.Files.write(file, lying);
            long streamBefore = threads.getCurrentThreadAllocatedBytes();
            assertThrows(limn.io.MalformedContentException.class, () -> decoder.openStream(file));
            long streamAllocated = threads.getCurrentThreadAllocatedBytes() - streamBefore;
            assertTrue(streamAllocated < 8 << 20, "the stream allocated " + streamAllocated);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /** Where the audio starts: after the tag's header, its size and, when flagged, its footer. */
    @Test
    void theAudioStartsWhereTheId3TagSaysItEnds() {
        byte[] file = new byte[400];
        byte[] head = {'I', 'D', '3', 4, 0, 0, 0, 0, 0x01, 0x05};   // synchsafe 133
        System.arraycopy(head, 0, file, 0, head.length);
        assertEquals(10 + 133, AudioFileDecoder.audioStart(file));
        file[5] = 0x10;                                               // a footer follows
        assertEquals(10 + 133 + 10, AudioFileDecoder.audioStart(file));
        file[9] = (byte) 0x85;                                        // not synchsafe
        assertThrows(limn.io.MalformedContentException.class, () -> AudioFileDecoder.audioStart(file));
        assertEquals(0, AudioFileDecoder.audioStart(new byte[] {(byte) 0xFF, (byte) 0xFB, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0}), "no tag, no offset");
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
        assertThrows(limn.io.MalformedContentException.class, () -> decoder.decode(new byte[] {'R', 'I', 'F', 'F'}));
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
            assertThrows(limn.io.MalformedContentException.class, () -> decoder.openStream(file));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void aChunkSizeJustUnderTwoGigabytesIsATruncatedChunkNotACrash() {
        // Sizes above 2^31 already read negative and were clamped; the band just below
        // 2^31 wrapped the offset arithmetic instead and escaped the clamp.
        short[] pcm = {100, -100, 200, -200};
        byte[] file = wav(pcm, 1, 22_050, 16);
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 0x7FFFFFF0);
        AudioClip clip = new AudioFileDecoder().decode(file);
        assertEquals(4, clip.samples().length, "the data chunk is read to the end of the file");
    }

    /**
     * An Ogg's length is the granule position on its last page, a number the file states, and the
     * decoder allocated that many frames before decoding: a quarter second claiming 2^30 stereo
     * frames overflowed the count and failed, and one claiming less than that asked for gigabytes.
     * The audio is what decodes, whatever the last page says. The last granule is also what trims
     * the final packet's padding, so a file that lies about it keeps that padding: at most one
     * packet more than the honest file, never the length it claims.
     */
    @Test
    void anOggDecodesWhatItHoldsWhateverLengthItsLastPageClaims() throws Exception {
        AudioClip honest = decoder.decode(resource("tone.ogg"));
        assertEquals(2, honest.channels());
        assertEquals(22_050, honest.sampleRate());
        assertEquals(5512, honest.frameCount());

        AudioClip lying = decoder.decode(resource("tone-lying-length.ogg"));
        int extra = lying.frameCount() - honest.frameCount();
        assertTrue(extra >= 0 && extra <= 4096,
                "the frames the file holds and its last packet's padding, not 2^30: " + extra);
        assertArrayEquals(honest.samples(),
                java.util.Arrays.copyOf(lying.samples(), honest.samples().length));
    }

    private static byte[] resource(String name) throws java.io.IOException {
        try (java.io.InputStream in = AudioFileDecoderTest.class.getResourceAsStream("audio/" + name)) {
            return in.readAllBytes();
        }
    }

    /**
     * A WAV with no format chunk, or one naming no channels, is a malformed file, not an unsupported
     * one: it threw UnsupportedOperationException ("got 0 channels", "format -1"), which the
     * decoder's contract keeps for a well-formed file it cannot play. Three channels still is one.
     */
    @Test
    void aWavWithNoFormatOrNoChannelsIsMalformedNotUnsupported() throws Exception {
        byte[] pcm = {1, 0, 2, 0, 3, 0, 4, 0};
        ByteBuffer noFmt = ByteBuffer.allocate(20 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        noFmt.put("RIFF".getBytes()).putInt(12 + pcm.length).put("WAVE".getBytes());
        noFmt.put("data".getBytes()).putInt(pcm.length).put(pcm);
        byte[] noChannels = riff(pcm, 0, 44_100, 16, 1);
        for (byte[] file : new byte[][] {noFmt.array(), noChannels}) {
            limn.io.MalformedContentException refused =
                    assertThrows(limn.io.MalformedContentException.class, () -> decoder.decode(file));
            assertTrue(refused.getMessage().contains("malformed WAV"), refused.getMessage());
            java.nio.file.Path onDisk = java.nio.file.Files.createTempFile("limn-malformed", ".wav");
            try {
                java.nio.file.Files.write(onDisk, file);
                assertThrows(limn.io.MalformedContentException.class, () -> decoder.openStream(onDisk));
            } finally {
                java.nio.file.Files.deleteIfExists(onDisk);
            }
        }
        assertThrows(UnsupportedOperationException.class,
                () -> decoder.decode(riff(new byte[12], 3, 44_100, 16, 1)),
                "three channels is a real file this decoder does not play");
    }

    // ------------------------------------------------------------ sample rates

    /**
     * The device resamples a buffer by stepping through it at the ratio of the two rates, and
     * OpenAL Soft caps that step at ten, so on a 48 kHz device a WAV at 705.6 kHz played at 0.68
     * of its speed and one at 768 kHz at 0.63, with no error anywhere. The decoder now refuses a
     * rate above 384 kHz as a real file it cannot play, whole or streamed, and still reads one at
     * 384 kHz; the owner chose that bound on 2026-09-27.
     */
    @Test
    void aWavAboveTheHighestPlayableRateIsUnsupportedWholeAndStreamed() throws Exception {
        short[] pcm = {1, -1, 2, -2};
        AudioClip highest = decoder.decode(wav(pcm, 2, OpenAlAudio.MAX_SAMPLE_RATE, 16));
        assertEquals(384_000, highest.sampleRate());
        for (int rate : new int[] {384_001, 768_000, Integer.MAX_VALUE}) {
            byte[] file = wav(pcm, 2, rate, 16);
            UnsupportedOperationException refused =
                    assertThrows(UnsupportedOperationException.class, () -> decoder.decode(file));
            assertEquals("WAV at " + rate + " Hz: this backend plays sample rates up to 384000 Hz",
                    refused.getMessage());
            assertThrows(UnsupportedOperationException.class, () -> openOnDisk(file, ".wav"));
        }
        try (limn.sound.AudioStreamSource stream = openOnDisk(wav(pcm, 2, 384_000, 16), ".wav")) {
            assertEquals(384_000, stream.sampleRate());
        }
    }

    /**
     * A WAV's rate field is unsigned, and one at 2^31 or above reads negative: that is a broken
     * header, refused as malformed, and the message says the rate the file wrote rather than
     * blaming a missing chunk.
     */
    @Test
    void aWavRateThatReadsNegativeIsMalformedAndSaysSo() throws Exception {
        byte[] file = wav(new short[] {1, -1}, 1, Integer.MIN_VALUE, 16);
        limn.io.MalformedContentException refused =
                assertThrows(limn.io.MalformedContentException.class, () -> decoder.decode(file));
        assertEquals("malformed WAV: sample rate 2147483648 Hz", refused.getMessage());
        assertThrows(limn.io.MalformedContentException.class, () -> openOnDisk(file, ".wav"));
    }

    /**
     * stb_vorbis refuses a rate of zero and nothing else: 768 kHz reached the engine, and a rate at
     * 2^31 or above reached the streaming path as a negative number that nothing checked. The
     * whole-file path only failed on it by accident, when the clip's own constructor refused it.
     */
    @Test
    void anOggAboveTheHighestPlayableRateIsUnsupportedAndOneThatReadsNegativeIsMalformed()
            throws Exception {
        byte[] highest = oggAtRate(384_000);
        assertEquals(384_000, decoder.decode(highest).sampleRate());
        try (limn.sound.AudioStreamSource stream = openOnDisk(highest, ".ogg")) {
            assertEquals(384_000, stream.sampleRate());
        }

        byte[] fast = oggAtRate(768_000);
        UnsupportedOperationException unsupported =
                assertThrows(UnsupportedOperationException.class, () -> decoder.decode(fast));
        assertEquals("Ogg at 768000 Hz: this backend plays sample rates up to 384000 Hz",
                unsupported.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> openOnDisk(fast, ".ogg"));

        byte[] negative = oggAtRate(0x8000_0000L);
        limn.io.MalformedContentException malformed =
                assertThrows(limn.io.MalformedContentException.class, () -> decoder.decode(negative));
        assertEquals("malformed Ogg: sample rate 2147483648 Hz", malformed.getMessage());
        assertThrows(limn.io.MalformedContentException.class, () -> openOnDisk(negative, ".ogg"));
    }

    /** Opens {@code file} as a stream from a temporary file, which is deleted when this returns. */
    private limn.sound.AudioStreamSource openOnDisk(byte[] file, String suffix) throws Exception {
        java.nio.file.Path onDisk = java.nio.file.Files.createTempFile("limn-rate", suffix);
        try {
            java.nio.file.Files.write(onDisk, file);
            return decoder.openStream(onDisk);
        } finally {
            java.nio.file.Files.deleteIfExists(onDisk);
        }
    }

    /**
     * {@code tone.ogg} with the sample rate in its identification header set to {@code rate}, and
     * the first page's checksum made again: the header is the first page's only packet, and the
     * rate is the 32-bit little-endian field after the packet type, "vorbis", the version and the
     * channel count.
     */
    private static byte[] oggAtRate(long rate) throws java.io.IOException {
        byte[] ogg = resource("tone.ogg");
        ByteBuffer page = ByteBuffer.wrap(ogg).order(ByteOrder.LITTLE_ENDIAN);
        int segments = ogg[26] & 0xFF;
        int packet = 27 + segments;
        int pageLength = packet;
        for (int i = 0; i < segments; i++) {
            pageLength += ogg[27 + i] & 0xFF;
        }
        assertEquals(1, ogg[packet], "the identification header");
        assertEquals("vorbis", new String(ogg, packet + 1, 6, java.nio.charset.StandardCharsets.US_ASCII));
        int stored = page.getInt(22);
        page.putInt(22, 0);
        assertEquals(stored, oggChecksum(ogg, pageLength), "the checksum as the fixture has it");
        page.putInt(packet + 12, (int) rate);
        page.putInt(22, oggChecksum(ogg, pageLength));
        return ogg;
    }

    /**
     * Ogg's page checksum: CRC-32 with polynomial 0x04C11DB7, not reflected, starting from zero,
     * over the page with its checksum field zeroed.
     */
    private static int oggChecksum(byte[] bytes, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc ^= (bytes[i] & 0xFF) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = crc < 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
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

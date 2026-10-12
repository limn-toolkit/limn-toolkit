package limn.backend.lwjgl;

import limn.io.MalformedContentException;
import limn.testfixtures.MutationFuzz;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sound file that is damaged is refused with a {@link MalformedContentException}, or with an
 * {@link UnsupportedOperationException} where it is whole and of a kind the decoder does not
 * play, and with nothing else. The two formats read in Java are fuzzed: WAV, which is the
 * toolkit's own reader, and MP3, whose decoder is a library that throws what it never declared
 * on damaged frames. Ogg is decoded by a native library and is not run here.
 */
class AudioFileDecoderFuzzTest {

    private final AudioFileDecoder decoder = new AudioFileDecoder();

    private static byte[] wav(int channels, int rate, int bits, int frames) {
        int blockAlign = channels * bits / 8;
        byte[] data = new byte[frames * blockAlign];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data.length)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII)).put("fmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate)
                .putInt(rate * blockAlign).putShort((short) blockAlign).putShort((short) bits)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h.array());
        out.writeBytes(data);
        return out.toByteArray();
    }

    /**
     * Frames of silence: an MPEG-1 Layer III header at 128 kbit/s and 44.1 kHz, stereo, and a
     * frame's length of zeros behind it, which is side information that says the frame holds no
     * data. With or without a tag before them.
     */
    private static byte[] mp3(int frames, boolean tagged) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (tagged) {
            out.writeBytes(new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 10});
            out.writeBytes(new byte[10]);
        }
        for (int f = 0; f < frames; f++) {
            byte[] frame = new byte[417];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB;
            frame[2] = (byte) 0x90;
            frame[3] = 0x00;
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    private void run(String what, List<byte[]> seeds, int iterations) {
        MutationFuzz.Result result = MutationFuzz.run(42, iterations, seeds, decoder::decode,
                thrown -> thrown.getClass() == MalformedContentException.class
                        || thrown.getClass() == UnsupportedOperationException.class, "limn.backend.lwjgl.");
        assertEquals(Map.of(), result.escapes(), "a damaged " + what + " escaped the decoder");
        assertTrue(result.read() > 0 && result.refused() > 0, "the mutations test nothing: "
                + result.read() + " read, " + result.refused() + " refused");
    }

    @Test
    void aDamagedWavIsRefusedAsDocumented() {
        run("WAV", List.of(wav(1, 22_050, 16, 64), wav(2, 44_100, 16, 32), wav(1, 8_000, 8, 40)), 20000);
    }

    @Test
    void aDamagedMp3IsRefusedAsDocumented() {
        assertTrue(decoder.decode(mp3(4, false)).frameCount() > 0, "the seed is an MP3 the decoder plays");
        run("MP3", List.of(mp3(4, false), mp3(3, true)), 3000);
    }
}

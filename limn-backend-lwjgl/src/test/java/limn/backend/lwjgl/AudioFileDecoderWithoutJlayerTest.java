package limn.backend.lwjgl;

import limn.sound.AudioClip;
import limn.sound.AudioDecoder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JLayer, the MP3 decoder, is an LGPL jar that an application may exclude, as NOTICE and the
 * licence page say, losing MP3 and nothing else. The decoder caught a JLayer exception type, which
 * the verifier loads before the class links, so without the jar {@code new AudioFileDecoder()}
 * threw NoClassDefFoundError and the backend, which makes one as it is constructed, did not start.
 *
 * <p>The decoder is loaded here again, from its own class files, by a loader that refuses every
 * {@code javazoom} class, as the class path of an application that excluded the jar does; the
 * toolkit's types and LWJGL, natives included, are the test's own. WAV and Ogg Vorbis decode, and
 * an MP3, whole or streamed, is refused as a format the decoder does not read, naming the jar.
 */
class AudioFileDecoderWithoutJlayerTest {

    @Test
    void withoutJlayerWavAndOggDecodeAndAnMp3IsUnsupported() throws Exception {
        AudioDecoder withJlayer = new AudioFileDecoder();
        assertTrue(withJlayer.decode(mp3()).frameCount() > 0, "the MP3 is one JLayer decodes");

        AudioDecoder decoder = withoutJlayer();
        assertNotSame(AudioFileDecoder.class, decoder.getClass(), "the decoder is the one loaded again");

        short[] pcm = {0, 1000, -1000, 32000, -32000, 0};
        AudioClip wav = decoder.decode(wav(pcm));
        assertEquals(22_050, wav.sampleRate());
        assertArrayEquals(pcm, wav.samples());

        byte[] ogg = resource("tone.ogg");
        AudioClip tone = decoder.decode(ogg);
        assertEquals(2, tone.channels());
        assertEquals(5512, tone.frameCount());
        assertArrayEquals(withJlayer.decode(ogg).samples(), tone.samples());

        UnsupportedOperationException whole =
                assertThrows(UnsupportedOperationException.class, () -> decoder.decode(mp3()));
        assertTrue(whole.getMessage().contains("javazoom:jlayer"), whole.getMessage());

        Path file = Files.createTempFile("limn-no-jlayer", ".mp3");
        try {
            Files.write(file, mp3());
            UnsupportedOperationException streamed =
                    assertThrows(UnsupportedOperationException.class, () -> decoder.openStream(file));
            assertTrue(streamed.getMessage().contains("javazoom:jlayer"), streamed.getMessage());
            Files.write(file, wav(pcm));
            try (limn.sound.AudioStreamSource stream = decoder.openStream(file)) {
                short[] out = new short[pcm.length];
                assertEquals(pcm.length, stream.readFrames(out, pcm.length));
                assertArrayEquals(pcm, out);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** An {@link AudioFileDecoder} of a class loaded again by {@link WithoutJlayer}. */
    private static AudioDecoder withoutJlayer() throws ReflectiveOperationException {
        Class<?> type = new WithoutJlayer().loadClass(AudioFileDecoder.class.getName());
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (AudioDecoder) constructor.newInstance();
    }

    /**
     * Defines this package's classes again from the class files the test's own loader reads, and
     * refuses JLayer's; every other class is the test loader's, so the decoder made from it is an
     * {@link AudioDecoder} this test can call, and LWJGL's natives are the ones already loaded,
     * which a second loader could not load again.
     */
    private static final class WithoutJlayer extends ClassLoader {
        private static final String PACKAGE = AudioFileDecoder.class.getPackageName() + ".";

        WithoutJlayer() {
            super("without-jlayer", AudioFileDecoderWithoutJlayerTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("javazoom.")) {
                throw new ClassNotFoundException(name + ": JLayer is left out");
            }
            if (!name.startsWith(PACKAGE) || name.startsWith(AudioFileDecoderWithoutJlayerTest.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    byte[] bytes;
                    try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (in == null) {
                            throw new ClassNotFoundException(name);
                        }
                        bytes = in.readAllBytes();
                    } catch (IOException error) {
                        throw new ClassNotFoundException(name, error);
                    }
                    type = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }
    }

    /**
     * Four frames of silence: an MPEG-1 Layer III header at 128 kbit/s and 44.1 kHz, stereo, with a
     * frame's length of zeros behind it, side information saying the frame holds no data.
     */
    private static byte[] mp3() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int f = 0; f < 4; f++) {
            byte[] frame = new byte[417];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB;
            frame[2] = (byte) 0x90;
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    /** A mono 16-bit PCM WAV at 22050 Hz. */
    private static byte[] wav(short[] samples) {
        ByteBuffer file = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        file.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples.length * 2)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII)).put("fmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(22_050).putInt(44_100)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length * 2);
        for (short sample : samples) {
            file.putShort(sample);
        }
        return file.array();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = AudioFileDecoderWithoutJlayerTest.class.getResourceAsStream("audio/" + name)) {
            return in.readAllBytes();
        }
    }
}

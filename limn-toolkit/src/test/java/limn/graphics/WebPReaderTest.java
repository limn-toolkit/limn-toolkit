package limn.graphics;

import limn.io.MalformedContentException;
import limn.testfixtures.MutationFuzz;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebP read through the decoder the toolkit carries, against files of the format's reference
 * encoder: a picture with loss and without, transparency both ways, and animations. What each is
 * expected to be comes from the reference decoder for the still pictures, with its simple chroma
 * upsampling, which is the one this decoder does, and for the animations from the pictures their
 * frames were made of, which an animation without loss gives back exactly.
 */
class WebPReaderTest {

    private static final List<String> FIXTURES = List.of("small-lossy", "small-lossless", "alpha-lossy",
            "alpha-lossless", "anim-mini", "anim-times");

    private static byte[] resource(String name) {
        try (InputStream in = WebPReaderTest.class.getResourceAsStream("/limn/graphics/webp/" + name)) {
            return in.readAllBytes();
        } catch (IOException | NullPointerException missing) {
            throw new IllegalStateException("no fixture " + name, missing);
        }
    }

    @Test
    void everyFrameOfEveryFixtureIsWhatTheReferenceMakesOfIt() throws IOException {
        for (String name : FIXTURES) {
            Image image = Images.decode(resource(name + ".webp"));
            assertAsExpected(name, image);
        }
        // And decoded as it plays, a frame at a time, which a budget of nothing makes of every animation.
        long budget = Images.animationBudget();
        Images.setAnimationBudget(0);
        try {
            for (String name : FIXTURES) {
                assertAsExpected(name, Images.decode(resource(name + ".webp")));
            }
        } finally {
            Images.setAnimationBudget(budget);
        }
    }

    private static void assertAsExpected(String name, Image image) throws IOException {
        {
            DataInputStream expected = new DataInputStream(new ByteArrayInputStream(resource(name + ".expected")));
            int frames = expected.readInt();
            int turns = expected.readInt();
            assertEquals(expected.readInt(), image.width(), name);
            assertEquals(expected.readInt(), image.height(), name);
            assertEquals(frames, image.frameCount(), name);
            assertEquals(frames > 1, image.isAnimated(), name);
            assertEquals(turns, image.loopCount(), name + ": the turns the file asks for");
            for (int f = 0; f < frames; f++) {
                int millis = expected.readInt();
                byte[] pixels = expected.readNBytes(image.width() * image.height() * 4);
                assertEquals(millis, image.frameMillis(f), name + ", the time of frame " + f);
                assertArrayEquals(whereThere(pixels), whereThere(image.frame(f).pixels()), name + ", frame " + f);
            }
        }
    }

    /** A pixel that is not there has no colour to compare. */
    private static byte[] whereThere(byte[] rgba) {
        byte[] out = rgba.clone();
        for (int o = 0; o < out.length; o += 4) {
            if (out[o + 3] == 0) {
                out[o] = 0;
                out[o + 1] = 0;
                out[o + 2] = 0;
            }
        }
        return out;
    }

    @Test
    void aFileThatIsNotWhatItSaysIsRefusedBeforeTheDecoderSeesIt() {
        byte[] whole = resource("anim-mini.webp");
        assertThrows(MalformedContentException.class, () -> Images.decode(java.util.Arrays.copyOf(whole, 16)));
        // A chunk that says it is longer than the file: what the decoder would allocate for.
        byte[] lying = whole.clone();
        lying[16] = (byte) 0xFF;
        lying[17] = (byte) 0xFF;
        lying[18] = (byte) 0xFF;
        lying[19] = (byte) 0x7F;
        assertThrows(MalformedContentException.class, () -> Images.decode(lying));
        // A canvas of sixteen thousand on a side, in the header of an animation.
        byte[] huge = whole.clone();
        for (int i = 24; i < 30; i++) {
            huge[i] = (byte) 0xFF;
        }
        assertThrows(MalformedContentException.class, () -> Images.decode(huge));
        assertFalse(WebPReader.isWebP("RIFF....WAVE".getBytes()));
    }

    /** Damaged files are read or refused with the one exception: nothing of the decoder's own leaves, and no memory is asked for that a file's lie named. */
    @Test
    void aDamagedFileIsReadOrRefusedAndNothingElse() {
        List<byte[]> seeds = new ArrayList<>();
        for (String name : FIXTURES) {
            seeds.add(resource(name + ".webp"));
        }
        MutationFuzz.Result result = MutationFuzz.run(42, 12000, seeds, data -> {
            if (!WebPReader.isWebP(data)) {
                return; // no longer a WebP by its first bytes: another decoder's to refuse
            }
            try (WebPReader reader = new WebPReader(data)) {
                if ((long) reader.width() * reader.height() > 1 << 16) {
                    return; // a canvas no fixture had: under the ceiling, and megabytes prove nothing more
                }
                for (int i = 0; i < 8 && reader.nextFrame() != null; i++) {
                    // every frame the file still has
                }
            }
        }, thrown -> thrown instanceof MalformedContentException, "limn.graphics");
        assertTrue(result.escapes().isEmpty(), () -> "escaped: " + result.escapes());
        assertTrue(result.read() > 500 && result.refused() > 500,
                () -> "read " + result.read() + ", refused " + result.refused());
    }
}

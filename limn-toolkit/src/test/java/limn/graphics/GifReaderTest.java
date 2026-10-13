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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GIF reader against files it did not write and frames it did not make: each fixture was
 * written by the platform's own encoder, and what each of its frames looks like, whole, was read
 * off the same file by the WebP project's converter and a WebP decoder, which share no code with
 * this reader. The fixtures between them hold every way a frame is disposed of, frames smaller
 * than the canvas and off its corner, holes, an interlaced picture, times too short to be meant
 * and a file of one frame.
 */
class GifReaderTest {

    private static final List<String> FIXTURES = List.of("disposal-none", "disposal-background",
            "disposal-previous", "interlaced", "fast", "single");

    private static byte[] resource(String name) {
        try (InputStream in = GifReaderTest.class.getResourceAsStream("/limn/graphics/gif/" + name)) {
            return in.readAllBytes();
        } catch (IOException | NullPointerException missing) {
            throw new IllegalStateException("no fixture " + name, missing);
        }
    }

    @Test
    void everyFrameOfEveryFixtureIsWhatAnIndependentReaderMadeOfIt() throws IOException {
        for (String name : FIXTURES) {
            Image image = Images.decode(resource(name + ".gif"));
            DataInputStream expected = new DataInputStream(new ByteArrayInputStream(resource(name + ".expected")));
            int frames = expected.readInt();
            int turns = expected.readInt();
            assertEquals(expected.readInt(), image.width(), name);
            assertEquals(expected.readInt(), image.height(), name);
            assertEquals(frames, image.frameCount(), name);
            assertEquals(frames > 1, image.isAnimated(), name);
            if (frames > 1) {
                assertEquals(turns, image.loopCount(), name + ": the turns the file asks for");
            }
            for (int f = 0; f < frames; f++) {
                int millis = expected.readInt();
                byte[] pixels = expected.readNBytes(image.width() * image.height() * 4);
                if (frames > 1) {
                    assertEquals(millis, image.frameMillis(f), name + ", the time of frame " + f);
                }
                assertArrayEquals(pixels, clearOfColourWhereNotThere(image.frame(f).pixels()), name + ", frame " + f);
            }
            assertArrayEquals(image.frame(0).pixels(), image.pixels(), name + ": an image's own pixels are its first frame's");
        }
    }

    /** A pixel that is not there has no colour to compare. */
    private static byte[] clearOfColourWhereNotThere(byte[] rgba) {
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
    void aFileOfOneFrameIsAStillImage() {
        Image single = Images.decode(resource("single.gif"));
        assertFalse(single.isAnimated());
        assertEquals(1, single.frameCount());
        assertSame(single, single.frame(0));
        assertEquals(0, single.durationMillis());
    }

    @Test
    void aFileCutShortHasTheFramesThatCouldBeRead() {
        byte[] whole = resource("disposal-none.gif");
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length - 60);
        Image image = Images.decode(cut);
        assertTrue(image.frameCount() >= 1 && image.frameCount() <= 3);
        assertThrows(MalformedContentException.class, () -> Images.decode(java.util.Arrays.copyOf(whole, 10)));
        assertThrows(MalformedContentException.class, () -> Images.decode("GIF89a".getBytes()));
    }

    /**
     * Damaged files are read or refused with the one exception, and nothing else: no index out of
     * an array, no size that is not one, no loop that does not end, no canvas bigger than is read.
     */
    @Test
    void aDamagedFileIsReadOrRefusedAndNothingElse() {
        List<byte[]> seeds = new ArrayList<>();
        for (String name : FIXTURES) {
            seeds.add(resource(name + ".gif"));
        }
        MutationFuzz.Result result = MutationFuzz.run(42, 30000, seeds, data -> {
            if (!GifReader.isGif(data)) {
                return; // no longer a GIF by its first bytes: another decoder's to refuse
            }
            GifReader reader = new GifReader(data);
            if ((long) reader.width() * reader.height() > 1 << 16) {
                return; // a canvas the fixture never had: the ceiling took it, and decoding megabytes proves nothing more
            }
            for (int i = 0; i < 64 && reader.nextFrame() != null; i++) {
                // every frame the file still has
            }
        }, thrown -> thrown instanceof MalformedContentException, "limn.graphics");
        assertTrue(result.escapes().isEmpty(), () -> "escaped: " + result.escapes());
        assertTrue(result.read() > 1000 && result.refused() > 1000,
                () -> "read " + result.read() + ", refused " + result.refused());
    }
}

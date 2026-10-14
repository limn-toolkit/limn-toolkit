package limn.graphics;

import limn.io.MalformedContentException;
import limn.testfixtures.MutationFuzz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Animated PNG read here and composed here, each frame's picture by the installed decoder,
 * against what other decoders make of the same files: every disposal and blend, alpha between, a
 * picture that is not a frame, a palette with transparency, gray interlaced, and a file of
 * another encoder.
 *
 * <p>The installed decoder is played by {@link TestPngDecoder}, whose reading of each frame's
 * picture is held against the other decoders' with the rest. The frames of all but the last were
 * written by the JDK's PNG encoder and moved into an animation by a program of the test's; the last was written by the system encoder of the
 * machine the fixtures were made on. What each is expected to be is that system decoder's, which
 * is exact, alpha between included. For the file with a picture that is not a frame it composes
 * the frame after into each one, so its expectation is that decoder's of the same frames without
 * the picture, which the format says is the same animation. A browser's decoder agreed on every
 * frame's place, time and turns, and on every pixel to within one where a frame is laid over
 * another, the step of its premultiplied arithmetic.
 */
class ApngReaderTest {

    private static final List<String> FIXTURES = List.of("full-frames", "dispose-blend", "hidden-default",
            "palette", "gray-interlaced", "system-written");

    /** The installed decoder of a backend, played here by a decoder of the tests'. */
    private static final ImageDecoder TEST_PNG = TestPngDecoder::decode;

    @BeforeEach
    void install() {
        Images.installDecoder(TEST_PNG);
    }

    @AfterEach
    void uninstall() {
        Images.uninstallDecoder(TEST_PNG);
    }

    private static byte[] resource(String name) {
        try (InputStream in = ApngReaderTest.class.getResourceAsStream("/limn/graphics/apng/" + name)) {
            return in.readAllBytes();
        } catch (IOException | NullPointerException missing) {
            throw new IllegalStateException("no fixture " + name, missing);
        }
    }

    @Test
    void everyFrameOfEveryFixtureIsWhatAnotherDecoderMakesOfIt() throws IOException {
        for (String name : FIXTURES) {
            assertAsExpected(name, Images.decode(resource(name + ".png")));
        }
        long budget = Images.animationBudget();
        Images.setAnimationBudget(0);
        try {
            for (String name : FIXTURES) {
                assertAsExpected(name, Images.decode(resource(name + ".png")));
            }
        } finally {
            Images.setAnimationBudget(budget);
        }
    }

    private static void assertAsExpected(String name, Image image) throws IOException {
        DataInputStream expected = new DataInputStream(new ByteArrayInputStream(resource(name + ".expected")));
        int frames = expected.readInt();
        int turns = expected.readInt();
        assertEquals(expected.readInt(), image.width(), name);
        assertEquals(expected.readInt(), image.height(), name);
        assertEquals(frames, image.frameCount(), name);
        assertTrue(image.isAnimated(), name);
        assertEquals(turns, image.loopCount(), name + ": the plays the file asks for");
        for (int f = 0; f < frames; f++) {
            int millis = expected.readInt();
            byte[] pixels = expected.readNBytes(image.width() * image.height() * 4);
            assertEquals(millis, image.frameMillis(f), name + ", the time of frame " + f);
            assertArrayEquals(whereThere(pixels), whereThere(image.frame(f).pixels()), name + ", frame " + f);
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

    /**
     * A first frame smaller than the canvas, after a picture that is not a frame: drawn on a
     * clear canvas where its place says. Neither decoder at hand reads this case as the format
     * says, so it is checked by construction.
     */
    @Test
    void aFirstFrameSmallerThanTheCanvasIsDrawnWhereItsPlaceSays() {
        byte[] hidden = solid(4, 4, (byte) 0x11);
        byte[] small = solid(2, 2, (byte) 0x77);
        byte[] file = apng(4, 4, hidden, small, 1, 2);
        Image image = Images.decode(file);
        assertEquals(1, image.frameCount());
        assertFalse(image.isAnimated(), "one frame is a still picture");
        byte[] pixels = image.pixels();
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                boolean inside = x >= 1 && x < 3 && y >= 2;
                assertEquals(inside ? 0x77 : 0, pixels[(y * 4 + x) * 4] & 0xFF, "red at " + x + ", " + y);
                assertEquals(inside ? 0xFF : 0, pixels[(y * 4 + x) * 4 + 3] & 0xFF, "alpha at " + x + ", " + y);
            }
        }
    }

    private static byte[] solid(int w, int h, byte red) {
        byte[] rgba = new byte[w * h * 4];
        for (int o = 0; o < rgba.length; o += 4) {
            rgba[o] = red;
            rgba[o + 3] = (byte) 0xFF;
        }
        return Images.encode(new Image(w, h, rgba), ImageFormat.PNG);
    }

    /** An animated PNG of a picture that is not a frame and one frame of the size of {@code frame}, at x, y. */
    private static byte[] apng(int w, int h, byte[] hidden, byte[] frame, int x, int y) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(java.util.Arrays.copyOf(hidden, 8 + 25)); // signature and header
        byte[] actl = new byte[8];
        actl[3] = 1; // one frame
        chunk(out, "acTL", actl);
        out.writeBytes(java.util.Arrays.copyOfRange(hidden, nth(hidden, "IDAT", 1), nth(hidden, "IEND", 1)));
        int frameIdat = nth(frame, "IDAT", 1);
        byte[] fctl = new byte[26];
        fctl[7] = frame[19]; // the frame picture's width and height, from its header
        fctl[11] = frame[23];
        fctl[15] = (byte) x;
        fctl[19] = (byte) y;
        fctl[21] = 1;
        fctl[23] = 10;
        chunk(out, "fcTL", fctl);
        // Every IDAT of the frame's file, which may be several however small it is.
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        data.writeBytes(new byte[] {0, 0, 0, 1}); // the sequence number
        for (int at = frameIdat; at < nth(frame, "IEND", 1); ) {
            int length = ((frame[at] & 0xFF) << 24) | ((frame[at + 1] & 0xFF) << 16)
                    | ((frame[at + 2] & 0xFF) << 8) | (frame[at + 3] & 0xFF);
            data.write(frame, at + 8, length);
            at += 12 + length;
        }
        byte[] fdat = data.toByteArray();
        chunk(out, "fdAT", fdat);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(java.io.ByteArrayOutputStream out, String type, byte[] data) {
        byte[] head = new byte[8];
        head[0] = (byte) (data.length >>> 24);
        head[1] = (byte) (data.length >>> 16);
        head[2] = (byte) (data.length >>> 8);
        head[3] = (byte) data.length;
        for (int i = 0; i < 4; i++) {
            head[4 + i] = (byte) type.charAt(i);
        }
        out.writeBytes(head);
        out.writeBytes(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(head, 4, 4);
        crc.update(data);
        long value = crc.getValue();
        out.writeBytes(new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }

    /**
     * The toolkit's own encoder writes an animated image as an animated PNG, and it reads back
     * pixel for pixel, alpha between included, with its times and its turns: frames that change
     * in part, in whole, and not at all.
     */
    @Test
    void anAnimationWrittenAsPngComesBackPixelForPixelWithItsTimesAndTurns() {
        int w = 11;
        int h = 7;
        List<Image> frames = new ArrayList<>();
        java.util.Random random = new java.util.Random(65);
        byte[] last = new byte[w * h * 4];
        random.nextBytes(last);
        for (int f = 0; f < 5; f++) {
            byte[] rgba = last.clone();
            if (f == 1) {
                rgba[(3 * w + 4) * 4 + 1] ^= 0x55; // one pixel
            } else if (f == 2) {
                for (int y = 1; y < 5; y++) {
                    for (int x = 2; x < 9; x++) {
                        rgba[(y * w + x) * 4 + 3] = (byte) (x * 30); // a block, alpha between
                    }
                }
            } else if (f == 4) {
                random.nextBytes(rgba); // all of it
            }
            frames.add(new Image(w, h, rgba));
            last = rgba;
        }
        int[] millis = {100, 40, 250, 70_000, 1000};
        Image animation = Images.animation(frames, millis, 3);
        byte[] file = Images.encode(animation, ImageFormat.PNG);
        assertTrue(ApngReader.isApng(file));
        Image back = Images.decode(file);
        assertEquals(5, back.frameCount());
        assertEquals(3, back.loopCount());
        for (int f = 0; f < 5; f++) {
            assertEquals(millis[f], back.frameMillis(f), "the time of frame " + f);
            assertArrayEquals(frames.get(f).pixels(), back.frame(f).pixels(), "frame " + f);
        }
        assertArrayEquals(file, Images.encode(animation, ImageFormat.PNG), "the same image, the same bytes");
        assertFalse(ApngReader.isApng(Images.encode(frames.get(0), ImageFormat.PNG)), "a still image stays a still PNG");
    }

    @Test
    void aStillPngIsNotOneAndIsLeftToTheInstalledDecoder() {
        byte[] still = Images.encode(new Image(3, 2, new byte[24]), ImageFormat.PNG);
        assertFalse(ApngReader.isApng(still));
        assertFalse(Images.decode(still).isAnimated());
    }

    @Test
    void aFileThatIsNotWhatItSaysIsRefusedBeforeAPixelIsDecoded() {
        byte[] whole = resource("dispose-blend.png");
        // A canvas of sixteen thousand on a side.
        byte[] huge = whole.clone();
        huge[16] = 0;
        huge[17] = 0;
        huge[18] = (byte) 0x40;
        huge[19] = 0;
        huge[20] = 0;
        huge[21] = 0;
        huge[22] = (byte) 0x40;
        huge[23] = 0;
        assertThrows(MalformedContentException.class, () -> Images.decode(huge));
        // A frame that goes past the canvas: the second frame control's x moved far right.
        byte[] outside = whole.clone();
        int second = nth(outside, "fcTL", 2);
        outside[second + 8 + 12 + 3] = (byte) 200;
        assertThrows(MalformedContentException.class, () -> Images.decode(outside));
    }

    private static int nth(byte[] png, String type, int n) {
        for (int at = 8; at < png.length; ) {
            int length = ((png[at] & 0xFF) << 24) | ((png[at + 1] & 0xFF) << 16) | ((png[at + 2] & 0xFF) << 8)
                    | (png[at + 3] & 0xFF);
            if (new String(png, at + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1).equals(type) && --n == 0) {
                return at;
            }
            at += 12 + length;
        }
        throw new IllegalArgumentException("no " + type);
    }

    /** Damaged files are read or refused with the one exception, whatever the installed decoder makes of a frame. */
    @Test
    void aDamagedFileIsReadOrRefusedAndNothingElse() {
        List<byte[]> seeds = new ArrayList<>();
        for (String name : FIXTURES) {
            seeds.add(resource(name + ".png"));
        }
        MutationFuzz.Result result = MutationFuzz.run(65, 12000, seeds, data -> {
            if (!ApngReader.isApng(data)) {
                return; // no longer an animated PNG by its first chunks: the installed decoder's to refuse
            }
            ApngReader reader = new ApngReader(data, TestPngDecoder::decode);
            if ((long) reader.width() * reader.height() > 1 << 16) {
                return; // a canvas no fixture had: under the ceiling, and megabytes prove nothing more
            }
            for (int i = 0; i < 8 && reader.nextFrame() != null; i++) {
                // every frame the file still has
            }
        }, thrown -> thrown instanceof MalformedContentException, "limn.graphics");
        assertTrue(result.escapes().isEmpty(), () -> "escaped: " + result.escapes());
        assertTrue(result.read() > 500 && result.refused() > 500,
                () -> "read " + result.read() + ", refused " + result.refused());
    }
}

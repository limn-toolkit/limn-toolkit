package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader against bytes, including every way a real file goes wrong. No media is committed: each
 * test writes the exact input it is about into a temporary directory first, which is also the only
 * way a test can be about a header that lies.
 */
class Y4mReaderTest {

    @TempDir
    Path directory;

    @Test
    void readsPicturesTheHeaderDescribes() throws IOException {
        // 4x2 in 4:2:0: four luma bytes a row, two chroma samples a row, one chroma row.
        byte[] first = planes(new int[]{10, 11, 12, 13, 14, 15, 16, 17}, new int[]{60, 61},
                new int[]{200, 201});
        byte[] second = planes(new int[]{20, 21, 22, 23, 24, 25, 26, 27}, new int[]{70, 71},
                new int[]{210, 211});
        Path file = write("clip.y4m", "YUV4MPEG2 W4 H2 F25:1 Ip A1:1 C420\n", first, second);

        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            assertEquals(4, source.width());
            assertEquals(2, source.height());
            assertEquals(25, source.frameRateNum());
            assertEquals(1, source.frameRateDen());
            assertEquals(PixelFormat.I420, source.pixelFormat());
            assertSame(VideoColor.unspecified(), source.color(),
                    "the container says nothing about the matrix or the range");
            assertEquals(VideoStreamSource.DURATION_UNKNOWN, source.durationMicros());
            assertTrue(source.canReset());

            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            VideoFrame frame = source.frame();
            assertEquals(0L, frame.ptsMicros());
            assertEquals(10, frame.plane(0).get(0) & 0xFF);
            assertEquals(13, frame.plane(0).get(3) & 0xFF);
            assertEquals(14, frame.plane(0).get(frame.stride(0)) & 0xFF, "the second row");
            assertEquals(60, frame.plane(1).get(0) & 0xFF);
            assertEquals(201, frame.plane(2).get(1) & 0xFF);
            frame.release();

            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            assertEquals(40_000L, source.frame().ptsMicros(), "one picture at 25 per second");
            assertEquals(20, source.frame().plane(0).get(0) & 0xFF);
            source.frame().release();

            assertEquals(VideoStreamSource.Read.END, source.readFrame());
            assertEquals(VideoStreamSource.Read.END, source.readFrame(), "and it keeps ending");

            source.reset();
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            assertEquals(10, source.frame().plane(0).get(0) & 0xFF, "back at the first picture");
            assertEquals(0L, source.frame().ptsMicros());
            source.frame().release();
        }
    }

    @Test
    void unknownTagsAreSkippedAndAMissingCTagIs420() throws IOException {
        Path file = write("odd.y4m", "YUV4MPEG2 W2 H2 F30:1 Zsomething XYSCSS=420MPEG2 Iw A0:0\n",
                planes(new int[]{1, 2, 3, 4}, new int[]{5}, new int[]{6}));
        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            assertEquals(PixelFormat.I420, source.pixelFormat(), "4:2:0 by the format's convention");
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            source.frame().release();
        }
    }

    @Test
    void everyFourTwoZeroSpellingIsTheSameLayout() throws IOException {
        for (String tag : new String[]{"420", "420jpeg", "420paldv", "420mpeg2"}) {
            Path file = write("c" + tag + ".y4m", "YUV4MPEG2 W2 H2 F30:1 C" + tag + "\n",
                    planes(new int[]{1, 2, 3, 4}, new int[]{5}, new int[]{6}));
            try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
                assertEquals(PixelFormat.I420, source.pixelFormat(), tag);
            }
        }
    }

    @Test
    void fourFourFourReadsAsThreeFullPlanes() throws IOException {
        Path file = write("full.y4m", "YUV4MPEG2 W2 H2 F30:1 C444\n",
                planes(new int[]{1, 2, 3, 4}, new int[]{5, 6, 7, 8}, new int[]{9, 10, 11, 12}));
        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            assertEquals(PixelFormat.I444, source.pixelFormat());
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            VideoFrame frame = source.frame();
            assertEquals(8, frame.plane(1).get(frame.stride(1) + 1) & 0xFF);
            assertEquals(12, frame.plane(2).get(frame.stride(2) + 1) & 0xFF);
            frame.release();
        }
    }

    @Test
    void layoutsWithNoPixelFormatAreDeclinedByName() throws IOException {
        for (String tag : new String[]{"422", "422p10", "444p12", "420p16", "mono"}) {
            Path file = write("c" + tag + ".y4m", "YUV4MPEG2 W2 H2 F30:1 C" + tag + "\n");
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                    () -> new Y4mDecoder().openStream(file), tag);
            assertTrue(error.getMessage().contains("C" + tag), error.getMessage());
        }
    }

    @Test
    void theRangeExtensionIsHonouredWhenItIsThere() throws IOException {
        Path full = write("full-range.y4m", "YUV4MPEG2 W2 H2 F30:1 C420 XCOLORRANGE=FULL\n");
        try (VideoStreamSource source = new Y4mDecoder().openStream(full)) {
            assertSame(VideoColor.BT709_FULL, source.color());
            assertTrue(source.color().isSpecified());
        }
        Path limited = write("limited-range.y4m", "YUV4MPEG2 W2 H2 F30:1 C420 XCOLORRANGE=LIMITED\n");
        try (VideoStreamSource source = new Y4mDecoder().openStream(limited)) {
            assertSame(VideoColor.BT709_LIMITED, source.color());
        }
    }

    @Test
    void anOverridingDecoderWinsOverTheHeader() throws IOException {
        // Standard-definition content is BT.601 and nothing in the container can say so.
        Path file = write("sd.y4m", "YUV4MPEG2 W2 H2 F30:1 C420 XCOLORRANGE=FULL\n");
        try (VideoStreamSource source =
                     new Y4mDecoder(VideoColor.BT601_LIMITED).openStream(file)) {
            assertSame(VideoColor.BT601_LIMITED, source.color());
        }
    }

    @Test
    void anEmptyFileIsNotAStream() throws IOException {
        Path file = write("empty.y4m", "");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new Y4mDecoder().openStream(file));
        assertTrue(error.getMessage().contains("empty"), error.getMessage());
    }

    @Test
    void aFileWithoutTheSignatureIsNotAStream() throws IOException {
        Path file = write("wrong.y4m", "RIFF....WEBPVP8 \n");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new Y4mDecoder().openStream(file));
        assertTrue(error.getMessage().contains("YUV4MPEG2"), error.getMessage());
    }

    @Test
    void aHeaderMissingASizeIsRefused() throws IOException {
        Path noHeight = write("no-h.y4m", "YUV4MPEG2 W4 F30:1 C420\n");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new Y4mDecoder().openStream(noHeight)).getMessage().contains("H"));

        Path zeroWidth = write("zero-w.y4m", "YUV4MPEG2 W0 H4 F30:1 C420\n");
        assertThrows(IllegalStateException.class, () -> new Y4mDecoder().openStream(zeroWidth));

        Path notANumber = write("nan.y4m", "YUV4MPEG2 Wmany H4 F30:1 C420\n");
        assertThrows(IllegalStateException.class, () -> new Y4mDecoder().openStream(notANumber));

        Path badRate = write("bad-rate.y4m", "YUV4MPEG2 W4 H4 F30 C420\n");
        assertThrows(IllegalStateException.class, () -> new Y4mDecoder().openStream(badRate));
    }

    @Test
    void aHeaderThatLiesAboutItsSizeFailsOnTheFirstPicture() throws IOException {
        // Opening still succeeds; the header is well formed and every metadata accessor answers.
        // It is the read that finds out, and it says how far short the input ran.
        Path file = write("liar.y4m", "YUV4MPEG2 W64 H64 F30:1 C420\n",
                planes(new int[]{1, 2, 3, 4}, new int[]{5}, new int[]{6}));
        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            assertEquals(64, source.width());
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, source::readFrame);
            assertTrue(error.getMessage().contains("ends inside a picture"), error.getMessage());
        }
    }

    @Test
    void aHeaderAskingForGigabytesIsRefusedAtOpen() throws IOException {
        // Well formed, in range per axis, and 4.5 GiB of pictures for thirty bytes of input.
        Path file = write("huge.y4m", "YUV4MPEG2 W32768 H32768 F30:1 C420\n");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new Y4mDecoder().openStream(file));
        assertTrue(error.getMessage().contains("MiB"), error.getMessage());
    }

    @Test
    void aRegularFileShorterThanOnePictureReservesNothingBeforeItFails() throws IOException {
        // Under the ceiling (3 x 96 MiB) and still far more than four bytes of input deserve.
        Path file = write("short.y4m", "YUV4MPEG2 W8000 H8000 F30:1 C420\n", new byte[]{1, 2, 3, 4});
        long before = FramePoolTest.directMemoryUsed();
        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            assertEquals(8000, source.width(), "open still answers from the header");
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, source::readFrame);
            assertTrue(error.getMessage().contains("ends inside a picture"), error.getMessage());
        }
        assertTrue(FramePoolTest.directMemoryUsed() - before < 16L << 20,
                "the pictures the header described were never reserved");
    }

    @Test
    void aHeaderWithNothingAfterItIsAStreamOfNoPictures() throws IOException {
        Path file = write("none.y4m", "YUV4MPEG2 W2 H2 F30:1 C420\n");
        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            assertEquals(VideoStreamSource.Read.END, source.readFrame());
        }
    }

    @Test
    void aTruncatedPictureIsNotAClearEnd() throws IOException {
        byte[] whole = planes(new int[]{1, 2, 3, 4}, new int[]{5}, new int[]{6});
        Path file = write("cut.y4m", "YUV4MPEG2 W2 H2 F30:1 C420\n", whole);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 2));

        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            assertThrows(IllegalStateException.class, source::readFrame,
                    "a picture that stops halfway is a broken file, not the end of a stream");
        }
    }

    @Test
    void somethingOtherThanFrameWhereAPictureBeginsIsRefused() throws IOException {
        Path file = write("junk.y4m", "YUV4MPEG2 W2 H2 F30:1 C420\nJUNK\n");
        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, source::readFrame);
            assertTrue(error.getMessage().contains("JUNK"), error.getMessage());
        }
    }

    @Test
    void frameLinesMayCarryParameters() throws IOException {
        // The per-picture line is FRAME plus anything; interlacing and timecodes ride there.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("YUV4MPEG2 W2 H2 F30:1 C420\nFRAME Xsomething Ib\n"
                .getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(planes(new int[]{9, 8, 7, 6}, new int[]{5}, new int[]{4}));
        Path file = directory.resolve("params.y4m");
        Files.write(file, body.toByteArray());

        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            assertEquals(9, source.frame().plane(0).get(0) & 0xFF);
            source.frame().release();
        }
    }

    @Test
    void poolExhaustionIsPendingHereToo() throws IOException {
        Path file = write("many.y4m", "YUV4MPEG2 W2 H2 F30:1 C420\n",
                planes(new int[]{1, 1, 1, 1}, new int[]{1}, new int[]{1}),
                planes(new int[]{2, 2, 2, 2}, new int[]{2}, new int[]{2}),
                planes(new int[]{3, 3, 3, 3}, new int[]{3}, new int[]{3}),
                planes(new int[]{4, 4, 4, 4}, new int[]{4}, new int[]{4}));
        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            VideoFrame[] held = new VideoFrame[3];
            for (int i = 0; i < 3; i++) {
                assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
                held[i] = source.frame();
            }
            assertEquals(VideoStreamSource.Read.PENDING, source.readFrame());
            held[0].release();
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            assertEquals(4, source.frame().plane(0).get(0) & 0xFF);
            source.frame().release();
            held[1].release();
            held[2].release();
        }
    }

    @Test
    void readingPastTheEndDoesNotLoseSlots() throws IOException {
        // Reaching the end takes a slot and finds no picture for it. Twice over a two-slot pool
        // would leave nothing to decode into after a reset if that slot were not given back.
        Path file = write("short.y4m", "YUV4MPEG2 W2 H2 F30:1 C420\n",
                planes(new int[]{1, 1, 1, 1}, new int[]{1}, new int[]{1}));
        try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
            for (int round = 0; round < 6; round++) {
                assertEquals(VideoStreamSource.Read.FRAME, source.readFrame(), "round " + round);
                source.frame().release();
                assertEquals(VideoStreamSource.Read.END, source.readFrame());
                assertEquals(VideoStreamSource.Read.END, source.readFrame());
                source.reset();
            }
        }
    }

    @Test
    void supportsIsHonestAndNeverThrows() throws IOException {
        Y4mDecoder decoder = new Y4mDecoder();
        assertFalse(decoder.supports(null));
        assertTrue(decoder.supports(Path.of("nothing-here.y4m")), "the name is enough to claim it");
        assertTrue(decoder.supports(Path.of("SHOUTING.Y4M")), "case does not matter");
        assertFalse(decoder.supports(Path.of("does-not-exist.mp4")));
        assertFalse(decoder.supports(directory), "a directory is not a stream");

        Path unnamed = write("stream.bin", "YUV4MPEG2 W2 H2 F30:1 C420\n");
        assertTrue(decoder.supports(unnamed), "the signature claims it whatever it is called");

        Path other = write("other.bin", "not a video at all\n");
        assertFalse(decoder.supports(other));
    }

    @Test
    void openingAFileThatIsNotThereFails() {
        assertThrows(UncheckedIOException.class,
                () -> new Y4mDecoder().openStream(directory.resolve("absent.y4m")));
    }

    @Test
    void closingEndsTheStream() throws IOException {
        Path file = write("close.y4m", "YUV4MPEG2 W2 H2 F30:1 C420\n",
                planes(new int[]{1, 1, 1, 1}, new int[]{1}, new int[]{1}));
        VideoStreamSource source = new Y4mDecoder().openStream(file);
        assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
        source.frame().release();
        source.close();
        assertNull(source.frame());
        assertEquals(VideoStreamSource.Read.END, source.readFrame());
        source.close(); // idempotent
    }

    // --------------------------------------------------------------- fixtures

    /** Luma, then Cb, then Cr, tight and in that order (which is what the container is). */
    private static byte[] planes(int[] luma, int[] cb, int[] cr) {
        byte[] bytes = new byte[luma.length + cb.length + cr.length];
        int at = 0;
        for (int value : luma) {
            bytes[at++] = (byte) value;
        }
        for (int value : cb) {
            bytes[at++] = (byte) value;
        }
        for (int value : cr) {
            bytes[at++] = (byte) value;
        }
        return bytes;
    }

    private Path write(String name, String header, byte[]... pictures) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        for (byte[] picture : pictures) {
            body.writeBytes("FRAME\n".getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(picture);
        }
        Path file = directory.resolve(name);
        Files.write(file, body.toByteArray());
        return file;
    }
}

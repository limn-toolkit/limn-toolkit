package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream whose every sample is known in advance: what lands in the planes is what
 * {@link SyntheticPattern}'s arithmetic says, for every layout, every colour and the odd sizes where
 * chroma rounding decides who is right.
 */
class SyntheticSourceTest {

    @Test
    void metadataAnswersBeforeThePictureExists() {
        SyntheticSpec spec = SyntheticSpec.of(640, 360)
                .withFormat(PixelFormat.NV12)
                .withColor(VideoColor.BT2020_LIMITED)
                .withRate(30000, 1001)
                .withFrameCount(90);
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            // Nothing has been decoded: a view is laid out before a picture exists.
            assertNull(source.frame());
            assertEquals(640, source.width());
            assertEquals(360, source.height());
            assertEquals(PixelFormat.NV12, source.pixelFormat());
            assertSame(VideoColor.BT2020_LIMITED, source.color());
            assertEquals(30000, source.frameRateNum());
            assertEquals(1001, source.frameRateDen());
            assertEquals(3_003_000L, source.durationMicros(), "90 pictures at 30000/1001");
            assertTrue(source.canReset());
        }
    }

    @Test
    void anEndlessStreamHasNoDuration() {
        try (VideoStreamSource source = SyntheticVideoDecoder.open(SyntheticSpec.of(16, 16))) {
            assertEquals(VideoStreamSource.DURATION_UNKNOWN, source.durationMicros());
            for (int i = 0; i < 100; i++) {
                assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
                source.frame().release();
            }
        }
    }

    @Test
    void everyLayoutAndSizeMatchesThePatternArithmetic() {
        int[][] sizes = {{5, 3}, {7, 5}, {16, 16}, {33, 17}};
        for (PixelFormat format : PixelFormat.values()) {
            for (SyntheticPattern pattern : SyntheticPattern.values()) {
                for (int[] size : sizes) {
                    SyntheticSpec spec = SyntheticSpec.of(size[0], size[1])
                            .withFormat(format)
                            .withPattern(pattern);
                    try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
                        for (int index = 0; index < 3; index++) {
                            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
                            VideoFrame frame = source.frame();
                            assertSamplesMatch(frame, pattern, index);
                            frame.release();
                        }
                    }
                }
            }
        }
    }

    @Test
    void theBarsCarryTheStudioCodeTableTheyAreNamedFor() {
        // Eight pixels wide is one pixel per bar, so the luma row IS the code table, the anchor
        // that would catch a reordered or rescaled table, which the arithmetic comparison above
        // cannot because it reads the same table.
        SyntheticSpec spec = SyntheticSpec.of(8, 2).withFormat(PixelFormat.I444);
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            VideoFrame frame = source.frame();
            int[] expectedLuma = {235, 219, 188, 173, 78, 63, 32, 16};
            int[] expectedCb = {128, 16, 154, 42, 214, 102, 240, 128};
            int[] expectedCr = {128, 138, 16, 26, 230, 240, 118, 128};
            for (int x = 0; x < 8; x++) {
                assertEquals(expectedLuma[x], frame.plane(0).get(x) & 0xFF, "luma of bar " + x);
                assertEquals(expectedCb[x], frame.plane(1).get(x) & 0xFF, "Cb of bar " + x);
                assertEquals(expectedCr[x], frame.plane(2).get(x) & 0xFF, "Cr of bar " + x);
            }
            frame.release();
        }
    }

    @Test
    void theGradientMovesOneCodePerPicture() {
        SyntheticSpec spec = SyntheticSpec.of(16, 16).withPattern(SyntheticPattern.GRADIENT);
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            int previous = -1;
            for (int index = 0; index < 5; index++) {
                assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
                VideoFrame frame = source.frame();
                int corner = frame.plane(0).get(0) & 0xFF;
                assertEquals(index, corner, "the top-left luma is the picture index");
                assertFalse(corner == previous, "two pictures in a row are never identical");
                previous = corner;
                frame.release();
            }
        }
    }

    @Test
    void theCounterInksTheMiddleOfTheBars() {
        SyntheticSpec spec = SyntheticSpec.of(64, 48).withPattern(SyntheticPattern.COUNTER);
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            source.readFrame();
            VideoFrame zero = source.frame();
            int inked = countInk(zero);
            zero.release();
            for (int skip = 0; skip < 7; skip++) {
                source.readFrame();
                source.frame().release();
            }
            source.readFrame();
            VideoFrame eight = source.frame();
            assertTrue(countInk(eight) > inked,
                    "an 8 lights every segment and a 0 does not, so it inks strictly more pixels");
            eight.release();
        }
    }

    @Test
    void presentationTimesAreExactAtAFractionalRate() {
        SyntheticSpec spec = SyntheticSpec.of(8, 8).withRate(30000, 1001);
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            long[] expected = {0, 33366, 66733, 100100};
            for (long want : expected) {
                assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
                assertEquals(want, source.frame().ptsMicros());
                source.frame().release();
            }
        }
    }

    @Test
    void aFiniteStreamEndsAndKeepsEnding() {
        SyntheticSpec spec = SyntheticSpec.of(8, 8).withFrameCount(3);
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            for (int i = 0; i < 3; i++) {
                assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
                source.frame().release();
            }
            assertEquals(VideoStreamSource.Read.END, source.readFrame());
            assertEquals(VideoStreamSource.Read.END, source.readFrame());
            assertNotNull(source.frame(), "the last picture stays available after the end");

            source.reset();
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            assertEquals(0L, source.frame().ptsMicros(), "reset goes back to the first picture");
            source.frame().release();
        }
    }

    @Test
    void exhaustionIsPendingAndAReleaseClearsIt() {
        // Phase 1 could describe this and not assert it: it needs a producer that actually runs out.
        SyntheticSpec spec = SyntheticSpec.of(16, 16).withSlots(2);
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            VideoFrame first = source.frame();
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            VideoFrame second = source.frame();

            assertEquals(VideoStreamSource.Read.PENDING, source.readFrame(),
                    "both pictures are held, so there is nowhere to decode into");
            assertEquals(VideoStreamSource.Read.PENDING, source.readFrame(),
                    "asking again is cheap and answers the same");
            assertSame(second, source.frame(), "and it did not disturb the picture on loan");

            first.release();
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            source.frame().release();
            second.release();
        }
    }

    @Test
    void aDecodeThatReleasesEveryPictureNeverRunsOut() {
        // Exactly-once, over a real decode: one slot and two hundred pictures only works if every
        // release returns exactly one, and a release that ran twice is refused loudly below.
        SyntheticSpec spec = SyntheticSpec.of(32, 24).withSlots(1)
                .withPattern(SyntheticPattern.GRADIENT);
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            for (int index = 0; index < 200; index++) {
                assertEquals(VideoStreamSource.Read.FRAME, source.readFrame(),
                        "picture " + index + " had nowhere to go");
                VideoFrame frame = source.frame();
                assertEquals(index & 0xFF, frame.plane(0).get(0) & 0xFF);
                frame.release();
                assertThrows(IllegalStateException.class, frame::release,
                        "the second release of picture " + index);
            }
        }
    }

    @Test
    void aPictureNeverReleasedStallsTheStream() {
        // The failure mode the release contract exists to make loud: a video that plays for a
        // moment and then freezes with no error is a consumer that forgot to hand a picture back.
        SyntheticSpec spec = SyntheticSpec.of(16, 16).withSlots(1);
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            for (int i = 0; i < 10; i++) {
                assertEquals(VideoStreamSource.Read.PENDING, source.readFrame());
            }
        }
    }

    @Test
    void closingEndsTheStreamAndTakesThePictureBack() {
        VideoStreamSource source = SyntheticVideoDecoder.open(SyntheticSpec.of(8, 8));
        assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
        source.frame().release();
        source.close();
        assertNull(source.frame());
        assertEquals(VideoStreamSource.Read.END, source.readFrame());
        source.close(); // idempotent
    }

    @Test
    void theSteadyStateDecodeAllocatesNothing() {
        if (!AllocationProbe.isSupported()) {
            return; // this virtual machine cannot count, so there is nothing to measure
        }
        VideoStreamSource source = SyntheticVideoDecoder.open(
                SyntheticSpec.of(64, 48).withSlots(2).withPattern(SyntheticPattern.GRADIENT));
        Runnable decodeThirty = () -> {
            for (int i = 0; i < 30; i++) {
                if (source.readFrame() == VideoStreamSource.Read.FRAME) {
                    source.frame().release();
                }
            }
        };
        assertEquals(0, AllocationProbe.leastAllocatedBy(decodeThirty, 20),
                "publishing a picture writes two primitives into memory that already exists");
        source.close();
    }

    /** Every sample of every plane, against the pattern's own arithmetic at a luma coordinate. */
    private static void assertSamplesMatch(VideoFrame frame, SyntheticPattern pattern, int index) {
        PixelFormat format = frame.format();
        int width = frame.width();
        int height = frame.height();
        String where = format + " " + width + "x" + height + " " + pattern + " picture " + index;

        int depth = format.bitDepth();
        int lumaStep = format.bytesPerSample(0);
        ByteBuffer luma = frame.plane(0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(pattern.luma(x, y, width, height, index, depth),
                        format.componentAt(luma, y * frame.stride(0) + x * lumaStep),
                        where + " luma at " + x + "," + y);
            }
        }

        boolean interleaved = format.planeCount() == 2;
        ByteBuffer cbPlane = frame.plane(1);
        ByteBuffer crPlane = interleaved ? cbPlane : frame.plane(2);
        int cbStride = frame.stride(1);
        int crStride = interleaved ? cbStride : frame.stride(2);
        int step = format.bytesPerSample(1);
        int crOffset = interleaved ? step / format.componentsPerSample(1) : 0;
        for (int cy = 0; cy < format.planeHeight(1, height); cy++) {
            int y = cy << format.chromaShiftY();
            for (int cx = 0; cx < format.planeWidth(1, width); cx++) {
                int x = cx << format.chromaShiftX();
                assertEquals(pattern.cb(x, y, width, height, index, depth),
                        format.componentAt(cbPlane, cy * cbStride + cx * step),
                        where + " Cb at " + cx + "," + cy);
                assertEquals(pattern.cr(x, y, width, height, index, depth),
                        format.componentAt(crPlane, cy * crStride + crOffset + cx * step),
                        where + " Cr at " + cx + "," + cy);
            }
        }
    }

    private static int countInk(VideoFrame frame) {
        int ink = 0;
        ByteBuffer luma = frame.plane(0);
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                if ((luma.get(y * frame.stride(0) + x) & 0xFF) == 16
                        && SyntheticPattern.BARS.luma(x, y, frame.width(), frame.height(), 0, 8) != 16) {
                    ink++;
                }
            }
        }
        return ink;
    }
}

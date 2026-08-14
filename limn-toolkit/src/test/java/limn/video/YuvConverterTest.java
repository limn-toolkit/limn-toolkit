package limn.video;

import limn.video.VideoColor.Matrix;
import limn.video.VideoColor.Range;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The colour arithmetic of {@link YuvConverter}, pinned to exact integer output: the neutral
 * column, the studio footroom and headroom clamps, the primary and secondary anchors of all six
 * matrix/range combinations, and the nearest-neighbour chroma replication a device sampler has to
 * reproduce.
 */
class YuvConverterTest {

    /** Y codes the neutral column is pinned at, from footroom through headroom. */
    private static final int[] NEUTRAL_LUMA = {0, 16, 128, 235, 255};

    /** Decoded grey for {@link #NEUTRAL_LUMA}, studio range: identical under every matrix. */
    private static final int[] NEUTRAL_LIMITED = {0, 0, 130, 255, 255};

    /** Decoded grey for {@link #NEUTRAL_LUMA}, full range: the identity. */
    private static final int[] NEUTRAL_FULL = {0, 16, 128, 235, 255};

    private static final Anchor[] ANCHORS = {
        new Anchor(VideoColor.BT601_LIMITED, "red", 81, 90, 240, 254, 0, 0),
        new Anchor(VideoColor.BT601_LIMITED, "green", 145, 54, 34, 0, 255, 1),
        new Anchor(VideoColor.BT601_LIMITED, "blue", 41, 240, 110, 0, 0, 255),
        new Anchor(VideoColor.BT601_LIMITED, "cyan", 170, 166, 16, 1, 255, 255),
        new Anchor(VideoColor.BT601_LIMITED, "magenta", 106, 202, 222, 255, 0, 254),
        new Anchor(VideoColor.BT601_LIMITED, "yellow", 210, 16, 146, 255, 255, 0),

        new Anchor(VideoColor.BT601_FULL, "red", 76, 85, 255, 254, 0, 0),
        new Anchor(VideoColor.BT601_FULL, "green", 150, 44, 21, 0, 255, 1),
        new Anchor(VideoColor.BT601_FULL, "blue", 29, 255, 107, 0, 0, 254),
        new Anchor(VideoColor.BT601_FULL, "cyan", 179, 171, 1, 1, 255, 255),
        new Anchor(VideoColor.BT601_FULL, "magenta", 105, 212, 235, 255, 0, 254),
        new Anchor(VideoColor.BT601_FULL, "yellow", 226, 1, 149, 255, 255, 1),

        new Anchor(VideoColor.BT709_LIMITED, "red", 63, 102, 240, 255, 1, 0),
        new Anchor(VideoColor.BT709_LIMITED, "green", 173, 42, 26, 0, 255, 1),
        new Anchor(VideoColor.BT709_LIMITED, "blue", 32, 240, 118, 1, 0, 255),
        new Anchor(VideoColor.BT709_LIMITED, "cyan", 188, 154, 16, 0, 254, 255),
        new Anchor(VideoColor.BT709_LIMITED, "magenta", 78, 214, 230, 255, 0, 254),
        new Anchor(VideoColor.BT709_LIMITED, "yellow", 219, 16, 138, 254, 255, 0),

        new Anchor(VideoColor.BT709_FULL, "red", 54, 99, 255, 254, 0, 0),
        new Anchor(VideoColor.BT709_FULL, "green", 182, 30, 12, 0, 255, 0),
        new Anchor(VideoColor.BT709_FULL, "blue", 18, 255, 116, 0, 0, 254),
        new Anchor(VideoColor.BT709_FULL, "cyan", 201, 157, 1, 1, 255, 255),
        new Anchor(VideoColor.BT709_FULL, "magenta", 73, 226, 244, 255, 0, 255),
        new Anchor(VideoColor.BT709_FULL, "yellow", 237, 1, 140, 255, 255, 1),

        new Anchor(VideoColor.BT2020_LIMITED, "red", 74, 97, 240, 255, 0, 1),
        new Anchor(VideoColor.BT2020_LIMITED, "green", 164, 47, 25, 0, 254, 0),
        new Anchor(VideoColor.BT2020_LIMITED, "blue", 29, 240, 119, 0, 0, 255),
        new Anchor(VideoColor.BT2020_LIMITED, "cyan", 177, 159, 16, 0, 255, 254),
        new Anchor(VideoColor.BT2020_LIMITED, "magenta", 87, 209, 231, 255, 1, 255),
        new Anchor(VideoColor.BT2020_LIMITED, "yellow", 222, 16, 137, 255, 255, 0),

        new Anchor(VideoColor.BT2020_FULL, "red", 67, 92, 255, 254, 0, 0),
        new Anchor(VideoColor.BT2020_FULL, "green", 173, 36, 11, 0, 255, 0),
        new Anchor(VideoColor.BT2020_FULL, "blue", 15, 255, 118, 0, 0, 254),
        new Anchor(VideoColor.BT2020_FULL, "cyan", 188, 164, 1, 1, 255, 255),
        new Anchor(VideoColor.BT2020_FULL, "magenta", 82, 220, 245, 255, 0, 255),
        new Anchor(VideoColor.BT2020_FULL, "yellow", 240, 1, 138, 255, 255, 1),
    };

    @Test
    void neutralColumnIsExactForEveryCombination() {
        for (VideoColor color : specified()) {
            int[] expected = color.range() == Range.LIMITED ? NEUTRAL_LIMITED : NEUTRAL_FULL;
            for (int index = 0; index < NEUTRAL_LUMA.length; index++) {
                int grey = expected[index];
                VideoFrame frame = uniform(2, 2, PixelFormat.I420, color, NEUTRAL_LUMA[index], 128, 128);
                assertUniformPixels(rgba(frame), 4, grey, grey, grey,
                        color + " neutral Y " + NEUTRAL_LUMA[index]);
                frame.release();
            }
        }
    }

    @Test
    void neutralColumnIsMatrixIndependent() {
        for (Range range : Range.values()) {
            for (int luma : NEUTRAL_LUMA) {
                byte[] reference = null;
                for (Matrix matrix : Matrix.values()) {
                    VideoFrame frame = uniform(2, 2, PixelFormat.I420, VideoColor.of(matrix, range),
                            luma, 128, 128);
                    byte[] converted = rgba(frame);
                    frame.release();
                    if (reference == null) {
                        reference = converted;
                    } else {
                        assertArrayEquals(reference, converted,
                                "neutral Y " + luma + " in " + range + " must not depend on " + matrix);
                    }
                }
            }
        }
    }

    @Test
    void footroomAndHeadroomClamp() {
        for (Matrix matrix : Matrix.values()) {
            VideoColor color = VideoColor.of(matrix, Range.LIMITED);

            VideoFrame footroom = uniform(2, 2, PixelFormat.I420, color, 0, 128, 128);
            assertUniformPixels(rgba(footroom), 4, 0, 0, 0,
                    color + " studio Y 0 decodes to -18.63 and must clamp to black, not wrap");
            footroom.release();

            VideoFrame headroom = uniform(2, 2, PixelFormat.I420, color, 255, 128, 128);
            assertUniformPixels(rgba(headroom), 4, 255, 255, 255,
                    color + " studio Y 255 decodes to 278.29 and must clamp to white, not wrap");
            headroom.release();
        }
    }

    @Test
    void primariesAndSecondariesAreExactForEveryCombination() {
        for (Anchor anchor : ANCHORS) {
            VideoFrame frame = uniform(2, 2, PixelFormat.I420, anchor.color,
                    anchor.y, anchor.cb, anchor.cr);
            assertUniformPixels(rgba(frame), 4, anchor.red, anchor.green, anchor.blue, anchor.toString());
            frame.release();
        }
        assertEquals(36, ANCHORS.length, "three matrices, two ranges, six colours");
    }

    @Test
    void fullRangeIsTheIdentityOnGrey() {
        int[] lumas = {0, 16, 64, 128, 192, 235, 255};
        for (Matrix matrix : Matrix.values()) {
            VideoColor color = VideoColor.of(matrix, Range.FULL);
            for (int luma : lumas) {
                VideoFrame frame = uniform(2, 2, PixelFormat.I420, color, luma, 128, 128);
                assertUniformPixels(rgba(frame), 4, luma, luma, luma, color + " grey " + luma);
                frame.release();
            }
        }
    }

    @Test
    void alphaIsAlwaysOpaque() {
        for (Anchor anchor : ANCHORS) {
            VideoFrame frame = uniform(2, 2, PixelFormat.I420, anchor.color,
                    anchor.y, anchor.cb, anchor.cr);
            byte[] converted = rgba(frame);
            frame.release();
            for (int pixel = 0; pixel < 4; pixel++) {
                assertEquals(255, converted[pixel * 4 + 3] & 0xFF, anchor + " alpha of pixel " + pixel);
            }
        }
    }

    @Test
    void nv12AndI420AgreeOnTheSamePicture() {
        int[] luma = new int[16];
        for (int index = 0; index < luma.length; index++) {
            luma[index] = 16 + index * 13;
        }
        int[] cb = {40, 200, 128, 90};
        int[] cr = {210, 60, 128, 175};

        VideoFrame planar = frameOf(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED, luma, cb, cr);
        byte[] fromI420 = rgba(planar);
        planar.release();

        VideoFrame interleaved = frameOf(4, 4, PixelFormat.NV12, VideoColor.BT709_LIMITED, luma, cb, cr);
        byte[] fromNv12 = rgba(interleaved);
        interleaved.release();

        assertArrayEquals(fromI420, fromNv12,
                "NV12 sample n is the byte pair (Cb, Cr); reversing it is invisible only on grey");
    }

    @Test
    void swappingCbAndCrChangesTheResult() {
        VideoFrame red = uniform(2, 2, PixelFormat.I420, VideoColor.BT709_LIMITED, 63, 102, 240);
        assertUniformPixels(rgba(red), 4, 255, 1, 0, "BT.709 studio red");
        red.release();

        VideoFrame swapped = uniform(2, 2, PixelFormat.I420, VideoColor.BT709_LIMITED, 63, 240, 102);
        byte[] converted = rgba(swapped);
        swapped.release();
        assertFalse((converted[0] & 0xFF) == 255 && (converted[1] & 0xFF) == 1 && (converted[2] & 0xFF) == 0,
                "exchanging the chroma planes must not decode to the same colour");
    }

    @Test
    void i444NeedsNoUpsampling() {
        int[] luma = {60, 120, 180, 240};
        int[] cb = {30, 90, 150, 210};
        int[] cr = {200, 140, 80, 20};
        VideoFrame frame = frameOf(2, 2, PixelFormat.I444, VideoColor.BT601_FULL, luma, cb, cr);
        byte[] converted = rgba(frame);
        frame.release();

        int[] expected = new int[4];
        for (int pixel = 0; pixel < 4; pixel++) {
            YuvConverter.convertPixel(VideoColor.BT601_FULL, 8, luma[pixel], cb[pixel], cr[pixel], expected);
            assertPixel(converted, pixel, expected[0], expected[1], expected[2],
                    "4:4:4 pixel " + pixel + " carries its own chroma");
        }
    }

    @Test
    void chromaIsReplicatedNotInterpolated() {
        int[] luma = new int[8];
        Arrays.fill(luma, 128);
        int[] cb = {16, 240};
        int[] cr = {240, 16};
        VideoFrame frame = frameOf(4, 2, PixelFormat.I420, VideoColor.BT709_LIMITED, luma, cb, cr);
        byte[] converted = rgba(frame);
        frame.release();

        int[] left = new int[4];
        int[] right = new int[4];
        YuvConverter.convertPixel(VideoColor.BT709_LIMITED, 8, 128, cb[0], cr[0], left);
        YuvConverter.convertPixel(VideoColor.BT709_LIMITED, 8, 128, cb[1], cr[1], right);

        assertPixel(converted, 0, left[0], left[1], left[2], "pixel 0 takes chroma sample 0");
        assertPixel(converted, 1, left[0], left[1], left[2], "pixel 1 replicates chroma sample 0");
        assertPixel(converted, 2, right[0], right[1], right[2], "pixel 2 steps to chroma sample 1");
        assertPixel(converted, 3, right[0], right[1], right[2], "pixel 3 replicates chroma sample 1");
        assertFalse(left[0] == right[0] && left[1] == right[1] && left[2] == right[2],
                "the two chroma samples must decode differently for this test to mean anything");
    }

    @Test
    void oddSizesReuseTheLastChromaSample() {
        int[] luma = new int[9];
        Arrays.fill(luma, 128);
        int[] cb = {16, 240, 128, 200};
        int[] cr = {240, 16, 60, 128};
        VideoFrame frame = frameOf(3, 3, PixelFormat.I420, VideoColor.BT709_LIMITED, luma, cb, cr);
        byte[] converted = rgba(frame);
        frame.release();

        int[] expected = new int[4];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int chroma = (row >> 1) * 2 + (column >> 1);
                YuvConverter.convertPixel(VideoColor.BT709_LIMITED, 8, 128, cb[chroma], cr[chroma], expected);
                assertPixel(converted, row * 3 + column, expected[0], expected[1], expected[2],
                        "pixel " + column + "," + row + " reuses chroma sample " + chroma);
            }
        }
    }

    @Test
    void destinationStrideAndOffsetAreHonoured() {
        VideoFrame frame = uniform(2, 2, PixelFormat.I420, VideoColor.BT709_FULL, 128, 128, 128);
        int offset = 7;
        int stride = 2 * 4 + 5;
        byte[] dst = new byte[offset + stride * 2 + 11];
        Arrays.fill(dst, (byte) 0x5A);
        YuvConverter.toRgba8(frame, dst, offset, stride);
        frame.release();

        for (int index = 0; index < offset; index++) {
            assertEquals(0x5A, dst[index] & 0xFF, "byte " + index + " precedes row 0");
        }
        for (int row = 0; row < 2; row++) {
            int rowStart = offset + row * stride;
            for (int index = rowStart + 2 * 4; index < rowStart + stride; index++) {
                assertEquals(0x5A, dst[index] & 0xFF, "byte " + index + " is destination row padding");
            }
            for (int pixel = 0; pixel < 2; pixel++) {
                int at = rowStart + pixel * 4;
                assertEquals(128, dst[at] & 0xFF, "red of row " + row + " pixel " + pixel);
                assertEquals(128, dst[at + 1] & 0xFF, "green of row " + row + " pixel " + pixel);
                assertEquals(128, dst[at + 2] & 0xFF, "blue of row " + row + " pixel " + pixel);
                assertEquals(255, dst[at + 3] & 0xFF, "alpha of row " + row + " pixel " + pixel);
            }
        }
        for (int index = offset + stride + 2 * 4; index < dst.length; index++) {
            assertEquals(0x5A, dst[index] & 0xFF, "byte " + index + " follows the last row");
        }
    }

    @Test
    void undersizedDestinationThrows() {
        VideoFrame frame = uniform(4, 3, PixelFormat.I420, VideoColor.BT709_LIMITED, 128, 128, 128);
        int stride = 4 * 4;
        int needed = stride * 2 + 4 * 4;
        byte[] short1 = new byte[needed - 1];
        assertThrows(IllegalArgumentException.class, () -> YuvConverter.toRgba8(frame, short1, 0, stride));

        byte[] enough = new byte[needed];
        assertThrows(IllegalArgumentException.class,
                () -> YuvConverter.toRgba8(frame, enough, 0, 4 * 4 - 1));
        assertThrows(IllegalArgumentException.class,
                () -> YuvConverter.toRgba8(frame, enough, 1, stride));
        YuvConverter.toRgba8(frame, enough, 0, stride);
        frame.release();
    }

    @Test
    void convertingAReleasedFrameThrows() {
        VideoFrame frame = uniform(2, 2, PixelFormat.I420, VideoColor.BT709_LIMITED, 128, 128, 128);
        byte[] dst = new byte[2 * 2 * 4];
        frame.release();
        assertThrows(IllegalStateException.class, () -> YuvConverter.toRgba8(frame, dst, 0, 2 * 4));
    }

    @Test
    void conversionAllocatesNothingPerCall() {
        assumeTrue(AllocationProbe.isSupported(), "this virtual machine does not count thread allocation");

        VideoFrame frame = uniform(16, 16, PixelFormat.I420, VideoColor.BT709_LIMITED, 200, 90, 150);
        byte[] dst = new byte[16 * 16 * 4];
        Runnable passes = () -> {
            for (int pass = 0; pass < 10_000; pass++) {
                YuvConverter.toRgba8(frame, dst, 0, 16 * 4);
            }
        };

        long allocated = AllocationProbe.leastAllocatedBy(passes, 3);

        frame.release();
        assertEquals(0L, allocated,
                "ten thousand conversions allocated " + allocated + " bytes; a per-row temporary is"
                        + " a megabyte a second of garbage at 30 pictures a second");
        int[] expected = new int[4];
        YuvConverter.convertPixel(VideoColor.BT709_LIMITED, 8, 200, 90, 150, expected);
        assertPixel(dst, 0, expected[0], expected[1], expected[2], "ten thousand passes leave the same result");
    }

    @Test
    void roundingIsHalfUpNotHalfEven() {
        // These codes decode to exactly 222.5 and 224.5, which is where the rounding rule is the
        // whole answer: half-even gives 222 and 224, half-up gives 223 and 225. Anchors cannot catch
        // this, because a tie is what they are all chosen to avoid.
        int[] out = new int[4];

        YuvConverter.convertPixel(VideoColor.BT601_FULL, 8, 1, 253, 128, out);
        assertEquals(223, out[2], "222.5 rounds up, not to even");

        YuvConverter.convertPixel(VideoColor.BT601_FULL, 8, 3, 253, 128, out);
        assertEquals(225, out[2], "224.5 rounds up, not to even");

        VideoFrame frame = uniform(2, 2, PixelFormat.I420, VideoColor.BT601_FULL, 1, 253, 128);
        assertUniformPixels(rgba(frame), 4, 1, 0, 223, "the frame path rounds the same way");
        frame.release();
    }

    @Test
    void greensChromaTermsAreSummedBeforeTheLumaIsAdded() {
        // Green decodes to exactly 28.5 here, which half-up takes to 29. Adding the two chroma terms
        // to the luma one at a time instead re-associates the sum to 28.499999999999996 and the pixel
        // comes out 28: the single place in the whole code cube where the two entry points can
        // disagree, and the reference a device shader is pinned against must not be the one that is
        // wrong.
        int[] out = new int[4];
        YuvConverter.convertPixel(VideoColor.BT601_FULL, 8, 47, 78, 178, out);
        assertEquals(29, out[1], "the single-pixel reference");

        VideoFrame frame = uniform(2, 2, PixelFormat.I420, VideoColor.BT601_FULL, 47, 78, 178);
        byte[] converted = rgba(frame);
        frame.release();
        assertPixel(converted, 0, out[0], out[1], out[2], "the bulk path, which must agree with it");
    }

    @Test
    void directAndHeapPlanesConvertToTheSameBytes() {
        VideoFrame heap = uniform(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED, 200, 90, 150);
        byte[] fromHeap = rgba(heap);
        heap.release();

        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED);
        writer.setPlane(0, direct(4 * 4, 200), 4);
        writer.setPlane(1, direct(2 * 2, 90), 2);
        writer.setPlane(2, direct(2 * 2, 150), 2);
        VideoFrame frame = writer.publish();
        assertTrue(frame.plane(0).isDirect(), "the binding must reach the converter as the producer made it");
        byte[] fromDirect = rgba(frame);
        frame.release();

        assertArrayEquals(fromHeap, fromDirect,
                "a device-facing producer hands over direct memory, and it must decode identically");
    }

    @Test
    void invalidPixelCodesThrow() {
        int[] out = new int[4];
        VideoColor color = VideoColor.BT709_LIMITED;

        assertThrows(IllegalArgumentException.class, () -> YuvConverter.convertPixel(color, 8, -1, 128, 128, out),
                "a byte read without a mask is -1 for code 255, and decodes to a plausible dark pixel");
        assertThrows(IllegalArgumentException.class, () -> YuvConverter.convertPixel(color, 8, 256, 128, 128, out));
        assertThrows(IllegalArgumentException.class, () -> YuvConverter.convertPixel(color, 8, 128, -1, 128, out));
        assertThrows(IllegalArgumentException.class, () -> YuvConverter.convertPixel(color, 8, 128, 128, 256, out));
        assertThrows(IllegalArgumentException.class,
                () -> YuvConverter.convertPixel(color, 8, 128, 128, 128, new int[3]),
                "alpha needs a fourth element");
        assertThrows(NullPointerException.class, () -> YuvConverter.convertPixel(null, 8, 128, 128, 128, out));
        assertThrows(NullPointerException.class, () -> YuvConverter.convertPixel(color, 8, 128, 128, 128, null));
    }

    @Test
    void aNegativeDestinationOffsetThrows() {
        VideoFrame frame = uniform(2, 2, PixelFormat.I420, VideoColor.BT709_LIMITED, 128, 128, 128);
        byte[] dst = new byte[2 * 2 * 4];

        assertThrows(IllegalArgumentException.class, () -> YuvConverter.toRgba8(frame, dst, -1, 2 * 4));

        frame.release();
    }

    @Test
    void roundTripOfTheEightCornersIsWithinOneCode() {
        int[][] corners = {
            {0, 0, 0}, {255, 0, 0}, {0, 255, 0}, {0, 0, 255},
            {255, 255, 0}, {0, 255, 255}, {255, 0, 255}, {255, 255, 255},
        };
        int[] decoded = new int[4];
        for (VideoColor color : specified()) {
            for (int[] corner : corners) {
                int[] codes = encode(color, corner[0], corner[1], corner[2]);
                YuvConverter.convertPixel(color, 8, codes[0], codes[1], codes[2], decoded);
                for (int channel = 0; channel < 3; channel++) {
                    int error = Math.abs(decoded[channel] - corner[channel]);
                    assertTrue(error <= 1, color + " corner " + corner[0] + "," + corner[1] + "," + corner[2]
                            + " channel " + channel + " came back " + decoded[channel] + ", off by " + error);
                }
            }
        }
    }

    // ---------------------------------------------------------------- ten bits

    /**
     * The test that a widening of both sides cannot pass by moving them the same wrong way: it
     * compares the ten-bit decode against the <em>eight-bit</em> one, which is pinned to literal
     * outputs by every test above and was not touched by this change.
     *
     * <p>A ten-bit code that is an eight-bit code shifted up by two is the same colour, so the two
     * must decode to the same RGB. They cannot be identical to the last unit (940 of 1023 is not
     * exactly 235 of 255), so the tolerance is one code, which is far tighter than any of the ways
     * this can go wrong: using the eight-bit table is out by a factor of four, normalizing by 65535
     * is out by a factor of 64, and forgetting to shift studio black is out by 48 codes of lift.
     */
    @Test
    void aTenBitPictureDecodesAsTheEightBitPictureItIsAShiftOf() {
        int[] lumaCodes = {0, 16, 40, 63, 128, 173, 219, 235, 255};
        int[] chromaCodes = {16, 42, 90, 128, 154, 214, 240};
        int[] eight = new int[4];
        int[] ten = new int[4];
        for (VideoColor color : specified()) {
            // Studio levels are defined as {@code level << (n-8)}, so a shifted studio code is
            // exactly the same colour and the two outputs differ by the ratio of the full scales.
            // Full range has no such definition (a shifted full-range code lands three parts in a
            // thousand short), so there the factor is exactly four. Using one where the other
            // belongs is a one-code error and would make this test lie about which side moved.
            double factor = color.range() == Range.LIMITED ? 1023.0 / 255.0 : 4.0;
            for (int y : lumaCodes) {
                for (int cb : chromaCodes) {
                    for (int cr : chromaCodes) {
                        YuvConverter.convertPixel(color, 8, y, cb, cr, eight);
                        YuvConverter.convertPixel(color, 10, y << 2, cb << 2, cr << 2, ten);
                        for (int channel = 0; channel < 3; channel++) {
                            int scaled = (int) Math.round(ten[channel] / factor);
                            assertTrue(Math.abs(scaled - eight[channel]) <= 1,
                                    color + " Y" + y + " Cb" + cb + " Cr" + cr + " channel "
                                            + channel + ": eight bits gave " + eight[channel]
                                            + ", ten bits gave " + ten[channel] + " (" + scaled
                                            + " scaled back)");
                        }
                    }
                }
            }
            assertEquals(1023, ten[3], "alpha is opaque in the picture's own code space");
        }
    }

    @Test
    void aTenBitFrameDecodesTheSameWayTheEightBitOneDoes() {
        int[] luma = {60, 120, 180, 240, 16, 235, 128, 200, 90};
        int[] cb = {30, 90, 150, 210, 128, 16, 240, 100, 60};
        int[] cr = {200, 140, 80, 20, 128, 240, 16, 70, 190};

        for (VideoColor color : specified()) {
            VideoFrame eightBit = frameOf(3, 3, PixelFormat.I444, color, luma, cb, cr);
            byte[] fromEight = rgba(eightBit);
            eightBit.release();

            VideoFrame tenBit = frameOf(3, 3, PixelFormat.I444_10LE, color,
                    shifted(luma), shifted(cb), shifted(cr));
            byte[] fromTen = rgba(tenBit);
            tenBit.release();

            for (int index = 0; index < fromEight.length; index++) {
                assertTrue(Math.abs((fromEight[index] & 0xFF) - (fromTen[index] & 0xFF)) <= 1,
                        color + " byte " + index + ": " + (fromEight[index] & 0xFF) + " vs "
                                + (fromTen[index] & 0xFF));
            }
        }
    }

    @Test
    void tenBitSubsamplingReplicatesChromaTheSameWay() {
        int[] luma = new int[9];
        Arrays.fill(luma, 512);
        int[] cb = {64, 960, 512, 800};
        int[] cr = {960, 64, 240, 512};
        VideoFrame frame = frameOf(3, 3, PixelFormat.I420_10LE, VideoColor.BT709_LIMITED,
                luma, cb, cr);
        byte[] converted = rgba(frame);
        frame.release();

        // Which chroma sample a pixel takes, asserted as an exact equality between pixels rather
        // than against a recomputed colour: the frame path rounds once, at eight bits, and a
        // reference that rounded in the ten-bit code space and again on the way down would differ
        // by a code for reasons that have nothing to do with replication.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int chroma = (row >> 1) * 2 + (column >> 1);
                int first = firstPixelOfQuadrant(chroma);
                assertPixel(converted, row * 3 + column,
                        converted[first * 4] & 0xFF, converted[first * 4 + 1] & 0xFF,
                        converted[first * 4 + 2] & 0xFF,
                        "ten-bit pixel " + column + "," + row + " reuses chroma sample " + chroma);
            }
        }
        // And that the four samples decode to four different colours, or the equalities above
        // would hold for a converter that ignored chroma entirely.
        for (int a = 0; a < 4; a++) {
            for (int b = a + 1; b < 4; b++) {
                int first = firstPixelOfQuadrant(a) * 4;
                int second = firstPixelOfQuadrant(b) * 4;
                assertFalse(converted[first] == converted[second]
                                && converted[first + 1] == converted[second + 1]
                                && converted[first + 2] == converted[second + 2],
                        "chroma samples " + a + " and " + b + " must decode differently");
            }
        }
    }

    /** The top-left pixel of the 2×2 block a chroma sample of a 3×3 4:2:0 picture covers. */
    private static int firstPixelOfQuadrant(int chroma) {
        return (chroma / 2 * 2) * 3 + (chroma % 2) * 2;
    }

    /**
     * The low two bits of a ten-bit code are not decoration: a converter that read only the high
     * byte, or that assembled the sample big-endian, would answer the same for every code in a
     * group of four and this is where that shows.
     */
    @Test
    void theBottomTwoBitsOfATenBitCodeReachTheOutput() {
        int[] out = new int[4];
        int previous = -1;
        for (int step = 0; step < 4; step++) {
            YuvConverter.convertPixel(VideoColor.BT709_FULL, 10, 400 + step, 512, 512, out);
            assertNotEquals(previous, out[0],
                    "ten-bit luma " + (400 + step) + " must differ from " + (399 + step));
            previous = out[0];
        }

        // And through the frame path, where the byte order lives.
        VideoFrame frame = frameOf(2, 1, PixelFormat.I444_10LE, VideoColor.BT709_FULL,
                new int[] {0x003, 0x300}, new int[] {512, 512}, new int[] {512, 512});
        byte[] converted = rgba(frame);
        frame.release();
        assertTrue((converted[0] & 0xFF) < (converted[4] & 0xFF),
                "code 3 must decode darker than code 768; reading the sample big-endian swaps them");
    }

    @Test
    void tenBitRoundTripOfTheEightCornersIsWithinOneCode() {
        int[][] corners = {
            {0, 0, 0}, {1023, 0, 0}, {0, 1023, 0}, {0, 0, 1023},
            {1023, 1023, 0}, {0, 1023, 1023}, {1023, 0, 1023}, {1023, 1023, 1023},
        };
        int[] decoded = new int[4];
        for (VideoColor color : specified()) {
            for (int[] corner : corners) {
                int[] codes = encode(color, 10, corner[0], corner[1], corner[2]);
                YuvConverter.convertPixel(color, 10, codes[0], codes[1], codes[2], decoded);
                for (int channel = 0; channel < 3; channel++) {
                    int error = Math.abs(decoded[channel] - corner[channel]);
                    assertTrue(error <= 1, color + " ten-bit corner " + Arrays.toString(corner)
                            + " channel " + channel + " came back " + decoded[channel]
                            + ", off by " + error);
                }
            }
        }
    }

    private static int[] shifted(int[] codes) {
        int[] out = new int[codes.length];
        for (int index = 0; index < codes.length; index++) {
            out[index] = codes[index] << 2;
        }
        return out;
    }

    /** Forward encode, so the round trip is measured against arithmetic the converter never runs. */
    private static int[] encode(VideoColor color, int red, int green, int blue) {
        return encode(color, 8, red, green, blue);
    }

    /**
     * The recommendations' forward encode at any depth, from the published luma weights and the
     * studio spans, never from {@link VideoColor}'s decode table, which is the thing being checked.
     */
    private static int[] encode(VideoColor color, int bitDepth, int red, int green, int blue) {
        Matrix matrix = color.matrix();
        int maxCode = (1 << bitDepth) - 1;
        int shift = bitDepth - 8;
        double redNorm = red / (double) maxCode;
        double greenNorm = green / (double) maxCode;
        double blueNorm = blue / (double) maxCode;
        double luma = matrix.kr() * redNorm + matrix.kg() * greenNorm + matrix.kb() * blueNorm;
        double pb = (blueNorm - luma) / (2 * (1 - matrix.kb()));
        double pr = (redNorm - luma) / (2 * (1 - matrix.kr()));
        boolean limited = color.range() == Range.LIMITED;
        int black = 16 << shift;
        double lumaSpan = limited ? 219 << shift : maxCode;
        double chromaSpan = limited ? 224 << shift : maxCode;
        int neutral = 1 << (bitDepth - 1);
        return new int[] {
            clampCode((limited ? black : 0) + lumaSpan * luma, maxCode),
            clampCode(neutral + chromaSpan * pb, maxCode),
            clampCode(neutral + chromaSpan * pr, maxCode),
        };
    }

    private static int clampCode(double value, int maxCode) {
        long rounded = Math.round(value);
        return (int) Math.max(0, Math.min(maxCode, rounded));
    }

    private static VideoColor[] specified() {
        return new VideoColor[] {
            VideoColor.BT601_LIMITED, VideoColor.BT601_FULL,
            VideoColor.BT709_LIMITED, VideoColor.BT709_FULL,
            VideoColor.BT2020_LIMITED, VideoColor.BT2020_FULL,
        };
    }

    private static byte[] rgba(VideoFrame frame) {
        byte[] dst = new byte[frame.width() * frame.height() * 4];
        YuvConverter.toRgba8(frame, dst, 0, frame.width() * 4);
        return dst;
    }

    private static void assertUniformPixels(byte[] rgba, int pixels, int red, int green, int blue,
                                            String where) {
        for (int pixel = 0; pixel < pixels; pixel++) {
            assertPixel(rgba, pixel, red, green, blue, where + " pixel " + pixel);
        }
    }

    private static void assertPixel(byte[] rgba, int pixel, int red, int green, int blue, String where) {
        assertEquals(red, rgba[pixel * 4] & 0xFF, where + " red");
        assertEquals(green, rgba[pixel * 4 + 1] & 0xFF, where + " green");
        assertEquals(blue, rgba[pixel * 4 + 2] & 0xFF, where + " blue");
        assertEquals(255, rgba[pixel * 4 + 3] & 0xFF, where + " alpha");
    }

    private static VideoFrame uniform(int width, int height, PixelFormat format, VideoColor color,
                                      int luma, int cb, int cr) {
        int[] lumaSamples = new int[width * height];
        Arrays.fill(lumaSamples, luma);
        int chromaSamples = format.planeWidth(1, width) * format.planeHeight(1, height);
        int[] cbSamples = new int[chromaSamples];
        int[] crSamples = new int[chromaSamples];
        Arrays.fill(cbSamples, cb);
        Arrays.fill(crSamples, cr);
        return frameOf(width, height, format, color, lumaSamples, cbSamples, crSamples);
    }

    /**
     * Builds a published frame from sample values by role, with tight strides. The chroma arrays are
     * in the chroma plane's own grid, so a caller never rounds a chroma size itself.
     */
    private static VideoFrame frameOf(int width, int height, PixelFormat format, VideoColor color,
                                      int[] luma, int[] cb, int[] cr) {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(width, height, format, color);

        int lumaStep = format.bytesPerSample(0);
        int lumaStride = format.planeByteWidth(0, width);
        ByteBuffer lumaBytes = ByteBuffer.allocate(lumaStride * height);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                format.putComponent(lumaBytes, row * lumaStride + column * lumaStep,
                        luma[row * width + column]);
            }
        }
        writer.setPlane(0, lumaBytes, lumaStride);

        int chromaWidth = format.planeWidth(1, width);
        int chromaHeight = format.planeHeight(1, height);
        int chromaStep = format.bytesPerSample(1);
        int stride = format.planeByteWidth(1, width);
        if (format.planeCount() == 2) {
            int componentBytes = chromaStep / format.componentsPerSample(1);
            ByteBuffer bytes = ByteBuffer.allocate(stride * chromaHeight);
            for (int row = 0; row < chromaHeight; row++) {
                for (int column = 0; column < chromaWidth; column++) {
                    int at = row * stride + column * chromaStep;
                    format.putComponent(bytes, at, cb[row * chromaWidth + column]);
                    format.putComponent(bytes, at + componentBytes, cr[row * chromaWidth + column]);
                }
            }
            writer.setPlane(1, bytes, stride);
        } else {
            writer.setPlane(1, chromaPlane(format, cb, chromaWidth, chromaHeight), stride);
            writer.setPlane(2, chromaPlane(format, cr, chromaWidth, chromaHeight), stride);
        }
        return writer.publish();
    }

    /** A direct plane of one repeated sample, the shape a device-facing producer hands over. */
    private static ByteBuffer direct(int bytes, int sample) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes);
        for (int index = 0; index < bytes; index++) {
            buffer.put(index, (byte) sample);
        }
        return buffer;
    }

    private static ByteBuffer chromaPlane(PixelFormat format, int[] samples, int width, int height) {
        int step = format.bytesPerSample(1);
        ByteBuffer bytes = ByteBuffer.allocate(width * height * step);
        for (int index = 0; index < samples.length; index++) {
            format.putComponent(bytes, index * step, samples[index]);
        }
        return bytes;
    }

    /** One row of the primary/secondary anchor table: the encoder's codes and the exact output. */
    private static final class Anchor {

        private final VideoColor color;
        private final String name;
        private final int y;
        private final int cb;
        private final int cr;
        private final int red;
        private final int green;
        private final int blue;

        Anchor(VideoColor color, String name, int y, int cb, int cr, int red, int green, int blue) {
            this.color = color;
            this.name = name;
            this.y = y;
            this.cb = cb;
            this.cr = cr;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        @Override
        public String toString() {
            return color + " " + name + " (" + y + "," + cb + "," + cr + ")";
        }
    }
}

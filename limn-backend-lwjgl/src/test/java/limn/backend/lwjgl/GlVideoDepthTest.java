package limn.backend.lwjgl;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ten-bit half of the device conversion, held to the same oracle the eight-bit half is: the
 * two-byte plane upload, the sixteen-bit sampler normalization, and the RGB10_A2 target that is
 * the only reason any of it is worth doing.
 *
 * <p>Three failures this catches that a colour comparison at eight bits could not. Normalizing a
 * 10-bit sample by 65535 instead of scaling it to its code space is a picture 64 times too dark;
 * uploading the plane as {@code GL_UNSIGNED_BYTE} reads every second byte as a sample and gives
 * noise; and converting into an RGBA8 target quantizes the result back to eight bits, which looks
 * <em>right</em> and makes the whole exercise decorative; {@link #theTargetKeepsCodesEightBitsCannotHold}
 * is the one that says so.
 *
 * <p>Skipped where no GL context can be created; see {@link HeadlessGl}.
 */
class GlVideoDepthTest extends GlVideoTestBase {

    private static final VideoColor[] COLORS = {
        VideoColor.BT601_LIMITED, VideoColor.BT709_LIMITED, VideoColor.BT709_FULL,
        VideoColor.BT2020_LIMITED, VideoColor.BT2020_FULL,
    };

    private static final PixelFormat[] DEEP = {PixelFormat.I420_10LE, PixelFormat.I444_10LE};

    @Test
    void everyMatrixAndRangeReproducesTheReferenceConverterAtTenBits() {
        for (PixelFormat format : DEEP) {
            for (VideoColor color : COLORS) {
                assertPictureMatches(format, 16, 10, 0, true, false, color);
            }
        }
    }

    @Test
    void oddSizesAndPaddedRowsSurviveATwoByteSample() {
        // A 10-bit plane's stride is a byte count and its row length is a sample count, and they
        // now differ by a factor of two: a row length computed from the stride without dividing is
        // twice as wide as the picture and skews it a whole row per row.
        for (PixelFormat format : DEEP) {
            for (int[] size : new int[][] {{3, 3}, {5, 3}, {7, 5}, {1, 1}, {9, 5}}) {
                assertPictureMatches(format, size[0], size[1], 0, true, false,
                        VideoColor.BT709_LIMITED);
            }
            for (int extra : new int[] {2, 4, 8}) {
                assertPictureMatches(format, 9, 5, extra, true, false, VideoColor.BT709_LIMITED);
            }
        }
    }

    @Test
    void anOddStrideStagesAndDecodesIdentically() {
        // An odd byte stride is not a whole number of two-byte samples, so it cannot be expressed
        // as an unpack row length at all and the plane has to be staged tight. That is the same
        // branch NV12 uses, reached here for a different reason.
        for (PixelFormat format : DEEP) {
            assertPictureMatches(format, 9, 5, 1, true, false, VideoColor.BT709_LIMITED);
            assertPictureMatches(format, 9, 5, 3, false, false, VideoColor.BT709_LIMITED);
            assertPictureMatches(format, 9, 5, 2, true, true, VideoColor.BT709_LIMITED);
        }
    }

    @Test
    void theTargetKeepsCodesEightBitsCannotHold() {
        // Four full-range greys one ten-bit code apart. They decode to four distinct values in a
        // RGB10_A2 target and to one value in an RGBA8 one, so this fails (and only this fails)
        // if the converted picture is quantized back to eight bits on the way out.
        int[] codes = {600, 601, 602, 603};
        int[] luma = new int[4];
        System.arraycopy(codes, 0, luma, 0, 4);
        int[] neutral = TestPictures.filled(4, 512);
        VideoFrame frame = TestPictures.frame(4, 1, PixelFormat.I444_10LE, VideoColor.BT709_FULL,
                luma, neutral, neutral, 0, true, false);
        GlVideoSurface surface = upload(frame);
        int[] picture = picture(surface);
        frame.release();
        surface.dispose();

        for (int pixel = 0; pixel < 4; pixel++) {
            assertEquals(codes[pixel], picture[pixel * 4],
                    "full-range grey decodes to its own luma code, and ten bits of it must survive"
                            + " the conversion target");
        }
    }

    @Test
    void aTenBitPictureDecodesAsTheEightBitPictureItIsAShiftOf() {
        // The independent check on the widening: the eight-bit device path is pinned to literal
        // outputs elsewhere and was not touched, so a ten-bit picture whose codes are those codes
        // shifted up by two must land on the same colour. Both sides moving the same wrong way
        // cannot satisfy this, because only one of them moved.
        int[] luma = TestPictures.pseudoRandom(12 * 8, 21);
        int[] cb = TestPictures.pseudoRandom(12 * 8, 22);
        int[] cr = TestPictures.pseudoRandom(12 * 8, 23);

        for (VideoColor color : COLORS) {
            VideoFrame eight = TestPictures.frame(12, 8, PixelFormat.I444, color,
                    luma, cb, cr, 0, true, false);
            GlVideoSurface eightSurface = upload(eight);
            int[] fromEight = picture(eightSurface);
            eight.release();
            eightSurface.dispose();

            VideoFrame ten = TestPictures.frame(12, 8, PixelFormat.I444_10LE, color,
                    shifted(luma), shifted(cb), shifted(cr), 0, true, false);
            GlVideoSurface tenSurface = upload(ten);
            int[] fromTen = picture(tenSurface);
            ten.release();
            tenSurface.dispose();

            // The factor between the two output spaces is the range's, not a constant, and this is
            // the one place in the subsystem where that shows. Studio levels are defined as
            // {@code level << (n-8)}, so shifting a studio code up by two is exactly the same
            // colour and the outputs differ by the ratio of the two full scales, 1023/255. Full
            // range has no such definition: shifting a full-range code up by two lands three parts
            // in a thousand short of the same fraction, so its outputs differ by exactly four.
            double factor = color.range() == VideoColor.Range.LIMITED ? 1023.0 / 255.0 : 4.0;
            for (int index = 0; index < fromEight.length; index++) {
                if (index % 4 == 3) {
                    continue; // alpha is opaque in each target's own terms
                }
                double scaled = fromTen[index] / factor;
                assertTrue(Math.abs(scaled - fromEight[index]) <= 1.0,
                        color + " value " + index + ": eight bits gave " + fromEight[index]
                                + ", ten bits gave " + fromTen[index] + " (" + scaled + " scaled)");
            }
        }
    }

    @Test
    void aTenBitPlaneIsUploadedAsSixteenBitTexels() {
        // Read straight off the device rather than inferred from a colour: an R8 texture bound
        // where an R16 belongs still decodes to plausible-looking colours, because the top byte of
        // every sample is nearly constant across a smooth picture.
        VideoFrame frame = TestPictures.uniform(8, 8, PixelFormat.I420_10LE,
                VideoColor.BT709_LIMITED, 512, 512, 512);
        GlVideoSurface surface = upload(frame);
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, surface.planeTexture(0));
        assertEquals(GL33C.GL_R16, GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, 0,
                GL33C.GL_TEXTURE_INTERNAL_FORMAT), "a 10-bit luma plane is a 16-bit texture");
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, surface.colorTexture());
        assertEquals(GL33C.GL_RGB10_A2, GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, 0,
                GL33C.GL_TEXTURE_INTERNAL_FORMAT), "and the converted picture holds ten bits too");
        assertNoGlError("querying the plane formats");
        frame.release();
        surface.dispose();
    }

    @Test
    void anEightBitPictureIsStillEightBitsOnBothSides() {
        // The other half of the previous test, and the one that keeps every existing capture
        // byte-identical: nothing about the eight-bit path changed.
        VideoFrame frame = TestPictures.uniform(8, 8, PixelFormat.I420, VideoColor.BT709_LIMITED,
                128, 128, 128);
        GlVideoSurface surface = upload(frame);
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, surface.planeTexture(0));
        assertEquals(GL33C.GL_R8, GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, 0,
                GL33C.GL_TEXTURE_INTERNAL_FORMAT));
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, surface.colorTexture());
        assertEquals(GL33C.GL_RGBA8, GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, 0,
                GL33C.GL_TEXTURE_INTERNAL_FORMAT));
        frame.release();
        surface.dispose();
    }

    @Test
    void switchingDepthMidStreamRebuildsEverything() {
        // The reallocation guard tests the format, and these two need a different texture WIDTH in
        // bytes at the same picture size. Reusing an R8 plane texture for a 10-bit picture reads
        // half the plane.
        GlVideoSurface surface = canvas.glVideo().createSurface();
        int[] eightLuma = TestPictures.pseudoRandom(8 * 8, 31);
        int[] eightChroma = TestPictures.pseudoRandom(8 * 8, 32);

        VideoFrame eight = TestPictures.frame(8, 8, PixelFormat.I444, VideoColor.BT709_LIMITED,
                eightLuma, eightChroma, eightChroma, 0, true, false);
        surface.upload(eight);
        assertMatchesReference(picture(surface), eight, eightLuma, eightChroma, eightChroma,
                "I444 first");
        eight.release();

        int[] tenLuma = TestPictures.pseudoRandom(8 * 8, 33, 10);
        int[] tenChroma = TestPictures.pseudoRandom(8 * 8, 34, 10);
        VideoFrame ten = TestPictures.frame(8, 8, PixelFormat.I444_10LE, VideoColor.BT709_LIMITED,
                tenLuma, tenChroma, tenChroma, 0, true, false);
        surface.upload(ten);
        assertMatchesReference(picture(surface), ten, tenLuma, tenChroma, tenChroma,
                "I444_10LE at the same size as the I444 before it");
        ten.release();

        surface.upload(eight = TestPictures.frame(8, 8, PixelFormat.I444, VideoColor.BT709_LIMITED,
                eightLuma, eightChroma, eightChroma, 0, true, false));
        assertMatchesReference(picture(surface), eight, eightLuma, eightChroma, eightChroma,
                "and back to eight bits");
        eight.release();
        surface.dispose();
    }

    @Test
    void aPictureThatIsNotDisplayReferredIsRefusedRatherThanShown() {
        // Nothing on this path inverts a transfer function, so a PQ picture run through the matrix
        // arrives milky and low-contrast, which reads as a shader bug. The refusal names the
        // curve instead.
        GlVideoSurface surface = canvas.glVideo().createSurface();
        VideoFrame frame = TestPictures.uniform(8, 8, PixelFormat.I420_10LE,
                VideoColor.of(VideoColor.Matrix.BT2020, VideoColor.Range.LIMITED,
                        VideoColor.Transfer.PQ),
                512, 512, 512);
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> surface.upload(frame));
        assertTrue(error.getMessage().contains("PQ"), error.getMessage());
        frame.release();
        surface.dispose();
    }

    private void assertPictureMatches(PixelFormat format, int width, int height, int extraStride,
                                      boolean direct, boolean exactCapacity, VideoColor color) {
        int chromaSamples = format.planeWidth(1, width) * format.planeHeight(1, height);
        int depth = format.bitDepth();
        int[] luma = TestPictures.pseudoRandom(width * height, width * 31 + height, depth);
        int[] cb = TestPictures.pseudoRandom(chromaSamples, width + 7, depth);
        int[] cr = TestPictures.pseudoRandom(chromaSamples, height + 19, depth);
        VideoFrame frame = TestPictures.frame(width, height, format, color,
                luma, cb, cr, extraStride, direct, exactCapacity);
        GlVideoSurface surface = upload(frame);
        assertMatchesReference(picture(surface), frame, luma, cb, cr,
                format + " " + width + "x" + height + " stride+" + extraStride
                        + (direct ? " direct" : " heap") + (exactCapacity ? " tight" : "")
                        + " " + color);
        frame.release();
        surface.dispose();
    }

    private static int[] shifted(int[] codes) {
        int[] out = new int[codes.length];
        for (int index = 0; index < codes.length; index++) {
            out[index] = codes[index] << 2;
        }
        return out;
    }
}

package limn.backend.lwjgl;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The device conversion, held to {@link limn.video.YuvConverter} on real
 * silicon: every matrix and range, every layout, odd sizes, padded rows, planes
 * the device cannot address, and the memory and GL state the upload is not
 * allowed to disturb.
 *
 * <p>Skipped where no GL context can be created; see {@link HeadlessGl}.
 */
class GlVideoSurfaceTest extends GlVideoTestBase {

    private static final VideoColor[] COLORS = {
        VideoColor.BT601_LIMITED, VideoColor.BT601_FULL,
        VideoColor.BT709_LIMITED, VideoColor.BT709_FULL,
        VideoColor.BT2020_LIMITED, VideoColor.BT2020_FULL,
    };

    private static final PixelFormat[] FORMATS = {
        PixelFormat.I420, PixelFormat.NV12, PixelFormat.I444,
    };

    @Test
    void everyMatrixAndRangeReproducesTheReferenceConverter() {
        GlVideoSurface surface = canvas.glVideo().createSurface();
        for (VideoColor color : COLORS) {
            for (int[] codes : probeCodes(color)) {
                VideoFrame frame = TestPictures.uniform(2, 2, PixelFormat.I420, color,
                        codes[0], codes[1], codes[2]);
                surface.upload(frame);
                assertNoGlError("upload");
                assertMatchesReference(picture(surface), frame,
                        TestPictures.filled(4, codes[0]), TestPictures.filled(1, codes[1]),
                        TestPictures.filled(1, codes[2]),
                        color + " Y" + codes[0] + " Cb" + codes[1] + " Cr" + codes[2]
                                + " on " + HeadlessGl.describe());
                frame.release();
            }
        }
        surface.dispose();
    }

    @Test
    void theInterleavedLayoutDecodesTheSameColoursAsThePlanarOne() {
        GlVideoSurface surface = canvas.glVideo().createSurface();
        for (VideoColor color : COLORS) {
            for (int[] codes : probeCodes(color)) {
                VideoFrame frame = TestPictures.uniform(2, 2, PixelFormat.NV12, color,
                        codes[0], codes[1], codes[2]);
                surface.upload(frame);
                assertMatchesReference(picture(surface), frame,
                        TestPictures.filled(4, codes[0]), TestPictures.filled(1, codes[1]),
                        TestPictures.filled(1, codes[2]),
                        "NV12 " + color + " Y" + codes[0] + " Cb" + codes[1] + " Cr" + codes[2]);
                frame.release();
            }
        }
        surface.dispose();
    }

    @Test
    void aWholePictureMatchesTheReferenceInEveryLayout() {
        for (PixelFormat format : FORMATS) {
            assertPictureMatches(format, 32, 18, 0, true, false, VideoColor.BT709_LIMITED);
        }
    }

    @Test
    void oddSizesReplicateTheLastChromaSample() {
        // The failure this catches is a chroma coordinate derived by scaling a
        // normalized UV: at an odd width the sample it picks drifts by one
        // towards the right edge, which is a coloured stripe down that edge and
        // nowhere else.
        for (PixelFormat format : FORMATS) {
            for (int[] size : new int[][] {{3, 3}, {5, 3}, {7, 5}, {1, 1}, {2, 1}, {1, 4}}) {
                assertPictureMatches(format, size[0], size[1], 0, true, false,
                        VideoColor.BT601_LIMITED);
            }
        }
    }

    @Test
    void paddedRowsUploadWithoutSkew() {
        // An odd amount of padding also gives the interleaved chroma plane a
        // stride that is not a whole number of samples, which a row length in
        // samples cannot express at all.
        for (PixelFormat format : FORMATS) {
            for (int extra : new int[] {1, 4, 7}) {
                assertPictureMatches(format, 9, 5, extra, true, false, VideoColor.BT709_FULL);
            }
        }
    }

    @Test
    void aPlaneEndingAtItsLastSampleUploads() {
        // minPlaneBytes deliberately excludes the last row's padding, so a
        // producer may hand over a buffer that stops at the final sample. An
        // upload of every row at the plane's row length would read past it.
        for (PixelFormat format : FORMATS) {
            assertPictureMatches(format, 9, 5, 5, true, true, VideoColor.BT709_LIMITED);
            assertPictureMatches(format, 4, 4, 3, true, true, VideoColor.BT2020_FULL);
        }
    }

    @Test
    void heapPlanesUploadThroughStaging() {
        // A read-only heap plane has no address to hand a device: it is staged
        // through a buffer the surface owns, and must decode identically.
        for (PixelFormat format : FORMATS) {
            assertPictureMatches(format, 9, 5, 0, false, false, VideoColor.BT709_LIMITED);
            assertPictureMatches(format, 9, 5, 3, false, false, VideoColor.BT709_LIMITED);
            assertPictureMatches(format, 9, 5, 3, false, true, VideoColor.BT709_LIMITED);
        }
    }

    @Test
    void directAndHeapPlanesProduceIdenticalPictures() {
        int[] luma = TestPictures.pseudoRandom(9 * 5, 11);
        int[] cb = TestPictures.pseudoRandom(5 * 3, 12);
        int[] cr = TestPictures.pseudoRandom(5 * 3, 13);

        VideoFrame direct = TestPictures.frame(9, 5, PixelFormat.I420, VideoColor.BT709_LIMITED,
                luma, cb, cr, 6, true, false);
        GlVideoSurface fromDirect = upload(direct);
        int[] directPixels = picture(fromDirect);
        direct.release();
        fromDirect.dispose();

        VideoFrame heap = TestPictures.frame(9, 5, PixelFormat.I420, VideoColor.BT709_LIMITED,
                luma, cb, cr, 6, false, false);
        GlVideoSurface fromHeap = upload(heap);
        int[] heapPixels = picture(fromHeap);
        heap.release();
        fromHeap.dispose();

        assertArrayEqualsUnsigned(directPixels, heapPixels,
                "staging a heap plane must not change a single code");
    }

    @Test
    void pictureRowZeroLandsAtTheTopOfTheTarget() {
        // Read raw, without the row reversal every other test applies: GL hands
        // back the BOTTOM row first, so the picture's first row must be last.
        int width = 4;
        int height = 4;
        int[] luma = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                luma[row * width + column] = 40 + row * 50; // rows differ, columns do not
            }
        }
        VideoFrame frame = TestPictures.frame(width, height, PixelFormat.I444, VideoColor.BT601_FULL,
                luma, TestPictures.filled(width * height, 128), TestPictures.filled(width * height, 128),
                0, true, false);
        GlVideoSurface surface = upload(frame);
        byte[] bottomUp = readBottomUp(surface.colorTexture(), width, height);
        frame.release();
        surface.dispose();

        for (int row = 0; row < height; row++) {
            int expected = 40 + row * 50; // full-range grey decodes to the luma code
            int readbackRow = height - 1 - row;
            assertEquals(expected, bottomUp[readbackRow * width * 4] & 0xFF,
                    "picture row " + row + " must be readback row " + readbackRow
                            + ": a surface is bottom-up, which is what drawSurface flips");
        }
    }

    @Test
    void theSurfaceFollowsThePictureGeometry() {
        GlVideoSurface surface = canvas.glVideo().createSurface();
        assertFalse(surface.hasPicture(), "a new surface has nothing to draw");
        assertEquals(0, surface.widthPx());
        assertEquals(0, surface.heightPx());
        assertEquals(0, surface.colorTexture(), "nothing is allocated before the first picture");

        VideoFrame small = TestPictures.uniform(8, 6, PixelFormat.I420, VideoColor.BT709_LIMITED,
                128, 128, 128);
        surface.upload(small);
        small.release();
        assertTrue(surface.hasPicture());
        assertEquals(8, surface.widthPx());
        assertEquals(6, surface.heightPx());
        int firstTexture = surface.colorTexture();
        assertNotEquals(0, firstTexture);

        // A resolution change mid-stream, and a layout change with it.
        VideoFrame larger = TestPictures.uniform(12, 10, PixelFormat.NV12, VideoColor.BT601_FULL,
                200, 90, 150);
        surface.upload(larger);
        assertEquals(12, surface.widthPx());
        assertEquals(10, surface.heightPx());
        assertMatchesReference(picture(surface), larger,
                TestPictures.filled(120, 200), TestPictures.filled(30, 90), TestPictures.filled(30, 150),
                "after a resolution and layout change");
        larger.release();

        surface.resize(100, 100);
        assertEquals(12, surface.widthPx(), "resize does not scale a video surface");
        assertEquals(10, surface.heightPx(), "resize does not scale a video surface");

        surface.dispose();
        assertFalse(surface.hasPicture(), "a disposed surface has nothing to draw");
        assertEquals(0, surface.widthPx());
        surface.dispose(); // idempotent
        VideoFrame after = TestPictures.uniform(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED,
                128, 128, 128);
        assertThrows(IllegalStateException.class, () -> surface.upload(after),
                "a disposed surface must not quietly come back to life");
        after.release();
    }

    @Test
    void uploadingAReleasedFrameThrows() {
        GlVideoSurface surface = canvas.glVideo().createSurface();
        VideoFrame frame = TestPictures.uniform(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED,
                128, 128, 128);
        frame.release();
        assertThrows(IllegalStateException.class, () -> surface.upload(frame));
        surface.dispose();
    }

    @Test
    void uploadingOutsideItsOwnFrameThrows() {
        GlVideoSurface surface = canvas.glVideo().createSurface();
        VideoFrame frame = TestPictures.uniform(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED,
                128, 128, 128);
        canvas.endFrame();
        assertThrows(IllegalStateException.class, () -> surface.upload(frame),
                "outside a frame the window's GL context need not even be current");
        frame.release();
        surface.dispose();
    }

    @Test
    void uploadingOffTheUiThreadThrows() throws InterruptedException {
        GlVideoSurface surface = canvas.glVideo().createSurface();
        VideoFrame frame = TestPictures.uniform(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED,
                128, 128, 128);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                surface.upload(frame);
            } catch (Throwable error) {
                thrown.set(error);
            }
        }, "not-the-ui-thread");
        other.start();
        other.join();

        // The type alone proves nothing: a GL call on a foreign thread throws
        // IllegalStateException too, from LWJGL, AFTER the upload has begun.
        // What is being asserted is that the thread check refused it first.
        assertTrue(thrown.get() instanceof IllegalStateException,
                "a decode thread must be refused: " + thrown.get());
        assertTrue(thrown.get().getMessage().contains("UI thread"),
                "it must be the UI-thread check that refuses, before anything touches the device: "
                        + thrown.get().getMessage());
        frame.release();
        surface.dispose();
    }

    @Test
    void aLayoutChangeAtAnUnchangedSizeIsStillARebuild() {
        // The reallocation guard tests the format as well as the dimensions, and
        // the two layouts below need a different NUMBER of textures at the same
        // picture size: reusing three single-channel textures for a two-plane
        // picture leaves the chroma plane reading a stale texture from the
        // layout before it.
        GlVideoSurface surface = canvas.glVideo().createSurface();
        int[] luma = TestPictures.pseudoRandom(8 * 8, 61);
        int[] cb = TestPictures.pseudoRandom(4 * 4, 62);
        int[] cr = TestPictures.pseudoRandom(4 * 4, 63);

        VideoFrame planar = TestPictures.frame(8, 8, PixelFormat.I420, VideoColor.BT709_LIMITED,
                luma, cb, cr, 0, true, false);
        surface.upload(planar);
        assertMatchesReference(picture(surface), planar, luma, cb, cr, "I420 first");
        planar.release();

        VideoFrame interleaved = TestPictures.frame(8, 8, PixelFormat.NV12, VideoColor.BT709_LIMITED,
                luma, cb, cr, 0, true, false);
        surface.upload(interleaved);
        assertMatchesReference(picture(surface), interleaved, luma, cb, cr,
                "NV12 at the same size as the I420 before it");
        interleaved.release();

        int[] fullCb = TestPictures.pseudoRandom(8 * 8, 64);
        int[] fullCr = TestPictures.pseudoRandom(8 * 8, 65);
        VideoFrame full = TestPictures.frame(8, 8, PixelFormat.I444, VideoColor.BT709_LIMITED,
                luma, fullCb, fullCr, 0, true, false);
        surface.upload(full);
        assertMatchesReference(picture(surface), full, luma, fullCb, fullCr,
                "I444 at the same size again");
        assertEquals(8, surface.widthPx());
        full.release();
        surface.dispose();
    }

    @Test
    void oneSurfaceStagesPictureAfterPicture() {
        // Staging memory is reused, and the bug that shape invites shows up on
        // the SECOND picture, never the first: a buffer left narrowed to the
        // last plane staged (chroma, a quarter of the size) has no room for
        // the next picture's luma. Every other test here uploads to a fresh
        // surface and would never see it.
        GlVideoSurface surface = canvas.glVideo().createSurface();
        // Growing sizes as well as repeats: staging survives a geometry change,
        // so a bigger picture has to grow it rather than find it already large
        // enough by luck.
        for (int round = 0; round < 4; round++) {
            int width = 8 + round * 6;
            int height = 6 + round * 4;
            int[] luma = TestPictures.pseudoRandom(width * height, round + 1);
            int chromaSamples = PixelFormat.I420.planeWidth(1, width)
                    * PixelFormat.I420.planeHeight(1, height);
            int[] cb = TestPictures.pseudoRandom(chromaSamples, round + 41);
            int[] cr = TestPictures.pseudoRandom(chromaSamples, round + 83);
            VideoFrame frame = TestPictures.frame(width, height, PixelFormat.I420,
                    VideoColor.BT709_LIMITED, luma, cb, cr, 3, false, false);
            surface.upload(frame);
            assertMatchesReference(picture(surface), frame, luma, cb, cr, "heap picture " + round);
            frame.release();
        }
        surface.dispose();
    }

    @Test
    void aSteadyStreamOfPicturesAllocatesNothing() {
        assumeTrue(AllocationProbe.isSupported(), "this virtual machine does not count allocation");
        GlVideoSurface surface = canvas.glVideo().createSurface();
        VideoFrame direct = TestPictures.uniform(64, 48, PixelFormat.I420,
                VideoColor.BT709_LIMITED, 128, 100, 150);
        VideoFrame heap = TestPictures.frame(64, 48, PixelFormat.I420, VideoColor.BT709_LIMITED,
                TestPictures.filled(64 * 48, 128), TestPictures.filled(32 * 24, 100),
                TestPictures.filled(32 * 24, 150), 0, false, false);
        // Both kinds first, so the textures and the staging buffer exist: what
        // is being measured is the steady state, not the first picture of a
        // stream, which builds them.
        surface.upload(direct);
        surface.upload(heap);

        long directBytes = AllocationProbe.leastAllocatedBy(() -> {
            for (int picture = 0; picture < 100; picture++) {
                surface.upload(direct);
            }
        }, 3);
        long heapBytes = AllocationProbe.leastAllocatedBy(() -> {
            for (int picture = 0; picture < 100; picture++) {
                surface.upload(heap);
            }
        }, 3);

        direct.release();
        heap.release();
        surface.dispose();
        assertEquals(0L, directBytes,
                "a hundred uploads allocated " + directBytes + " bytes; at sixty pictures a"
                        + " second even a few dozen per picture is a collection nobody asked for");
        assertEquals(0L, heapBytes,
                "a hundred staged uploads allocated " + heapBytes + " bytes");
    }

    @Test
    void theUploadLeavesTheUnpackStateItFound() {
        // Row length is global unpack state. Left non-zero it skews the next
        // glyph the atlas uploads, in a different class, with no clue why.
        assertEquals(0, GL33C.glGetInteger(GL33C.GL_UNPACK_ROW_LENGTH), "before");
        int alignmentBefore = GL33C.glGetInteger(GL33C.GL_UNPACK_ALIGNMENT);
        VideoFrame frame = TestPictures.frame(9, 5, PixelFormat.I420, VideoColor.BT709_LIMITED,
                TestPictures.pseudoRandom(45, 3), TestPictures.pseudoRandom(15, 4),
                TestPictures.pseudoRandom(15, 5), 7, true, false);
        GlVideoSurface surface = upload(frame);
        frame.release();
        assertEquals(0, GL33C.glGetInteger(GL33C.GL_UNPACK_ROW_LENGTH), "after");
        // Alignment is the other half of the same global state, and this one is
        // deliberately left at 1: every uploader in this backend sets its own
        // before it uploads, and 1 is what each of them sets it to.
        assertEquals(1, GL33C.glGetInteger(GL33C.GL_UNPACK_ALIGNMENT),
                "the upload set the alignment to 1 and left it there, from " + alignmentBefore);
        surface.dispose();
    }

    @Test
    void theConversionRestoresTheFramebufferViewportAndTextureUnit() {
        int fboBefore = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] viewportBefore = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, viewportBefore);

        VideoFrame frame = TestPictures.uniform(16, 9, PixelFormat.I420, VideoColor.BT709_LIMITED,
                180, 100, 140);
        GlVideoSurface surface = upload(frame);
        frame.release();

        assertEquals(fboBefore, GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING),
                "the 2D pipeline keeps drawing into whatever it was drawing into");
        int[] viewportAfter = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, viewportAfter);
        assertEquals(viewportBefore[2], viewportAfter[2], "viewport width");
        assertEquals(viewportBefore[3], viewportAfter[3], "viewport height");
        assertEquals(GL33C.GL_TEXTURE0, GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE),
                "the atlas and the image cache bind without selecting a unit");
        surface.dispose();
    }

    @Test
    void surfacesDieWithTheirContext() {
        VideoFrame frame = TestPictures.uniform(8, 8, PixelFormat.I420, VideoColor.BT709_LIMITED,
                128, 128, 128);
        GlVideoSurface surface = upload(frame);
        frame.release();
        assertTrue(surface.hasPicture());

        canvas.endFrame();
        canvas.dispose();
        canvas = null; // the fixture must not dispose it twice

        assertFalse(surface.hasPicture(),
                "a context that goes away takes its textures with it; nothing may outlive it");
        assertEquals(0, surface.colorTexture());
    }

    /** Uploads a pseudo-random picture of this shape and holds it to the converter. */
    private void assertPictureMatches(PixelFormat format, int width, int height, int extraStride,
                                      boolean direct, boolean exactCapacity, VideoColor color) {
        int chromaSamples = format.planeWidth(1, width) * format.planeHeight(1, height);
        int[] luma = TestPictures.pseudoRandom(width * height, width * 31 + height);
        int[] cb = TestPictures.pseudoRandom(chromaSamples, width + 7);
        int[] cr = TestPictures.pseudoRandom(chromaSamples, height + 19);
        VideoFrame frame = TestPictures.frame(width, height, format, color,
                luma, cb, cr, extraStride, direct, exactCapacity);
        GlVideoSurface surface = upload(frame);
        assertMatchesReference(picture(surface), frame, luma, cb, cr,
                format + " " + width + "x" + height + " stride+" + extraStride
                        + (direct ? " direct" : " heap") + (exactCapacity ? " tight" : ""));
        frame.release();
        surface.dispose();
    }

    private static void assertArrayEqualsUnsigned(int[] expected, int[] actual, String where) {
        assertEquals(expected.length, actual.length, where + " length");
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual[index], where + " at value " + index);
        }
    }

    /**
     * Sample codes worth pinning for one colour: the six primaries and
     * secondaries an encoder would produce, both achromatic corners, the
     * neutral column, and the studio footroom and headroom that decode outside
     * the output range.
     */
    private static List<int[]> probeCodes(VideoColor color) {
        List<int[]> codes = new ArrayList<>();
        int[][] corners = {
            {255, 0, 0}, {0, 255, 0}, {0, 0, 255},
            {0, 255, 255}, {255, 0, 255}, {255, 255, 0},
            {0, 0, 0}, {255, 255, 255},
        };
        for (int[] rgb : corners) {
            codes.add(encode(color, rgb[0], rgb[1], rgb[2]));
        }
        for (int luma : new int[] {0, 16, 128, 235, 255}) {
            codes.add(new int[] {luma, 128, 128});
        }
        return codes;
    }

    /**
     * The forward encode, so the decode has a colour to hit that was not
     * produced by the code under test. Derived from the matrix's own published
     * weights; this is not a second copy of the decode table.
     */
    private static int[] encode(VideoColor color, int red, int green, int blue) {
        VideoColor.Matrix matrix = color.matrix();
        double redNorm = red / 255.0;
        double greenNorm = green / 255.0;
        double blueNorm = blue / 255.0;
        double luma = matrix.kr() * redNorm + matrix.kg() * greenNorm + matrix.kb() * blueNorm;
        double pb = (blueNorm - luma) / (2 * (1 - matrix.kb()));
        double pr = (redNorm - luma) / (2 * (1 - matrix.kr()));
        boolean limited = color.range() == VideoColor.Range.LIMITED;
        return new int[] {
            clampCode(limited ? 16 + 219 * luma : 255 * luma),
            clampCode(128 + (limited ? 224 : 255) * pb),
            clampCode(128 + (limited ? 224 : 255) * pr),
        };
    }

    private static int clampCode(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }
}

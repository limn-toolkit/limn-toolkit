package limn.backend.lwjgl;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.YuvConverter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The zero-copy path: a picture bound from a decoder's IOSurface instead of uploaded from bytes.
 *
 * <p>Everything here is held to the same oracle the uploaded path is ({@link YuvConverter}, under
 * {@link GlVideoTestBase}'s tie rule), because a second way of getting samples onto the device
 * that produced slightly different colour would be worse than no second way at all. What is
 * genuinely new is checked on top of that: the rectangle sampler addresses texel (0,0) as the
 * surface's row 0, P010's left-justified codes normalise correctly, and (the one that matters)
 * the device has finished reading the decoder's memory before the frame is released.
 *
 * <p>No hardware decoder is involved and that is deliberate: this repository can encode neither
 * H.264 nor HEVC, so there is no clip to decode. {@link TestIoSurfaces} writes the same two
 * layouts VideoToolbox produces, which makes the binding, the sampler, the normalisation and the
 * lifetime testable on any Mac, with or without FFmpeg.
 *
 * <p>Skipped where no GL context can be created, and off macOS.
 */
class GlIoSurfaceTest extends GlVideoTestBase {

    @Test
    void anNv12SurfaceDecodesExactlyAsAnUploadedOneDoes() {
        assumeInterop();
        int width = 16;
        int height = 8;
        int[] luma = TestPictures.pseudoRandom(width * height, 5);
        int[] cb = TestPictures.pseudoRandom(width * height / 4, 6);
        int[] cr = TestPictures.pseudoRandom(width * height / 4, 7);

        for (VideoColor color : new VideoColor[] {
            VideoColor.BT601_LIMITED, VideoColor.BT709_LIMITED, VideoColor.BT709_FULL,
            VideoColor.BT2020_LIMITED,
        }) {
            TestIoSurfaces.Surface surface =
                    TestIoSurfaces.create(TestIoSurfaces.NV12, width, height, luma, cb, cr);
            try {
                VideoFrame frame = handleFrame(surface, PixelFormat.NV12, color);
                GlVideoSurface target = upload(frame);
                int[] device = picture(target);
                frame.release();
                target.dispose();

                assertMatchesReference(device, frameDescription(width, height, PixelFormat.NV12,
                        color), luma, cb, cr, "NV12 IOSurface " + color);
            } finally {
                TestIoSurfaces.release(surface);
            }
        }
    }

    @Test
    void aP010SurfaceNormalisesItsLeftJustifiedCodes() {
        assumeInterop();
        // The whole of P010's difference from a ten-bit picture this repository produces is where
        // the code sits in the word. Sampling it with the right-justified scale is a picture 64
        // times too bright, which clamps to white everywhere and would pass any test that only
        // looked for "a picture". Comparing against the converter per pixel is what catches it.
        int width = 16;
        int height = 8;
        int[] luma = TestPictures.pseudoRandom(width * height, 11, 10);
        int[] cb = TestPictures.pseudoRandom(width * height / 4, 12, 10);
        int[] cr = TestPictures.pseudoRandom(width * height / 4, 13, 10);

        for (VideoColor color : new VideoColor[] {
            VideoColor.BT709_LIMITED, VideoColor.BT2020_LIMITED, VideoColor.BT709_FULL,
        }) {
            TestIoSurfaces.Surface surface =
                    TestIoSurfaces.create(TestIoSurfaces.P010, width, height, luma, cb, cr);
            try {
                VideoFrame frame = handleFrame(surface, PixelFormat.P010, color);
                GlVideoSurface target = upload(frame);
                assertEquals(PixelFormat.P010, target.pictureFormat(),
                        "a ten-bit picture converts into an RGB10_A2 target, whatever bound it");
                int[] device = picture(target);
                frame.release();
                target.dispose();

                assertMatchesReference(device, frameDescription(width, height, PixelFormat.P010,
                        color), luma, cb, cr, "P010 IOSurface " + color);
            } finally {
                TestIoSurfaces.release(surface);
            }
        }
    }

    @Test
    void texelRowZeroIsTheSurfacesRowZero() {
        assumeInterop();
        // A rectangle texture bound to an IOSurface could plausibly have arrived bottom-up, and the
        // conversion's own u_height - 1 - y flip would then cancel out invisibly in any picture
        // that is the same all the way down. So the picture is white on the top row and black
        // everywhere else, and the assertion is about which end of the target it lands at.
        int width = 8;
        int height = 8;
        int[] luma = new int[width * height];
        java.util.Arrays.fill(luma, 16);
        java.util.Arrays.fill(luma, 0, width, 235);
        int[] neutral = TestPictures.filled(width * height / 4, 128);

        TestIoSurfaces.Surface surface =
                TestIoSurfaces.create(TestIoSurfaces.NV12, width, height, luma, neutral, neutral);
        try {
            VideoFrame frame = handleFrame(surface, PixelFormat.NV12, VideoColor.BT709_LIMITED);
            GlVideoSurface target = upload(frame);
            int[] device = picture(target);  // rows top-down
            frame.release();
            target.dispose();

            assertEquals(255, device[0], "the surface's row 0 is the picture's top row");
            assertEquals(0, device[(height - 1) * width * 4], "and its last row is the bottom");
        } finally {
            TestIoSurfaces.release(surface);
        }
    }

    /**
     * The fourth lifetime, asserted.
     *
     * <p>With zero copy the conversion samples the decoder's own memory, and {@code glDrawArrays}
     * only <em>queues</em> that read. Releasing the frame hands the buffer back to the decoder's
     * pool, which refills it, so unless the read has actually completed, the conversion reads the
     * next picture and the wrong one appears, frames later, with nothing near the release to blame.
     *
     * <p>The pipe is primed first so this is not a matter of luck. Without
     * {@code GlVideoContext.awaitDeviceRead} the conversion sits behind that work when the overwrite
     * lands; with it, {@code upload} does not return until the read is done.
     */
    @Test
    void aPictureIsReadBeforeItsBufferGoesBackToTheDecoder() {
        assumeInterop();
        int width = 1920;
        int height = 1080;
        int chroma = (width / 2) * (height / 2);
        int[] first = TestPictures.filled(width * height, 235);
        int[] second = TestPictures.filled(width * height, 16);
        int[] neutral = TestPictures.filled(chroma, 128);

        TestIoSurfaces.Surface surface =
                TestIoSurfaces.create(TestIoSurfaces.NV12, width, height, first, neutral, neutral);
        GlVideoSurface target = null;
        try {
            VideoFrame frame = handleFrame(surface, PixelFormat.NV12, VideoColor.BT709_LIMITED);
            queueEnoughWorkThatNothingHasRunYet(width, height);

            target = upload(frame);

            // Exactly what the decoder does the instant the slot comes free, and the reason the
            // order here is upload / overwrite / release rather than upload / release / overwrite:
            // the overwrite is the dangerous event, and doing it before the release only makes the
            // window narrower than a real one.
            TestIoSurfaces.write(surface, second, neutral, neutral);
            frame.release();

            int[] device = picture(target);
            int white = device[0];
            assertEquals(255, white, "the first picture is studio white");
            for (int pixel = 0; pixel < width * height; pixel += 977) {
                assertEquals(255, device[pixel * 4],
                        "pixel " + pixel + " shows the picture that was bound, not the one the"
                                + " decoder wrote over it afterwards");
            }
        } finally {
            if (target != null) {
                target.dispose();
            }
            TestIoSurfaces.release(surface);
        }
    }

    @Test
    void aSurfaceRebuildsItsTexturesWhenAStreamChangesShape() {
        assumeInterop();
        // Hardware decode failing over to software mid-stream, which is a real thing a driver does.
        // A rectangle texture bound to an IOSurface cannot be uploaded into, and a 2D texture
        // cannot be bound to one, so the surface has to notice: a plain size-and-format comparison
        // would not, because neither the size nor the format changes.
        int width = 16;
        int height = 8;
        int[] luma = TestPictures.pseudoRandom(width * height, 31);
        int[] cb = TestPictures.pseudoRandom(width * height / 4, 32);
        int[] cr = TestPictures.pseudoRandom(width * height / 4, 33);
        VideoColor color = VideoColor.BT709_LIMITED;

        TestIoSurfaces.Surface surface =
                TestIoSurfaces.create(TestIoSurfaces.NV12, width, height, luma, cb, cr);
        try {
            VideoFrame bound = handleFrame(surface, PixelFormat.NV12, color);
            GlVideoSurface target = upload(bound);
            int[] fromHandle = picture(target);
            bound.release();

            VideoFrame uploaded = TestPictures.frame(width, height, PixelFormat.NV12, color,
                    luma, cb, cr, 0, true, false);
            target.upload(uploaded);
            assertNoGlError("uploading planar samples into a surface that had bound an IOSurface");
            int[] fromPlanes = picture(target);
            uploaded.release();
            target.dispose();

            assertEquals(fromHandle.length, fromPlanes.length);
            for (int index = 0; index < fromHandle.length; index++) {
                assertEquals(fromHandle[index], fromPlanes[index],
                        "the same samples decode the same way however they reached the device"
                                + " (index " + index + ")");
            }
        } finally {
            TestIoSurfaces.release(surface);
        }
    }

    @Test
    void aReleasedHandleIsNotBoundToAnything() {
        assumeInterop();
        // The liveness gate, which for a handle-backed picture is the whole of the safety: a
        // released frame's surface has gone back to the decoder's pool, and binding it would point
        // a texture at whatever the decoder writes next. Asking for the handle is what says so, and
        // it must happen before this surface throws away the picture it already holds.
        int[] flat = TestPictures.filled(64, 128);
        int[] chroma = TestPictures.filled(16, 128);
        TestIoSurfaces.Surface surface =
                TestIoSurfaces.create(TestIoSurfaces.NV12, 8, 8, flat, chroma, chroma);
        try {
            VideoFrame frame = handleFrame(surface, PixelFormat.NV12, VideoColor.BT709_LIMITED);
            GlVideoSurface target = upload(frame);
            frame.release();

            assertThrows(IllegalStateException.class, () -> target.upload(frame));
            assertTrue(target.hasPicture(), "and the picture it had is still there");
            target.dispose();
        } finally {
            TestIoSurfaces.release(surface);
        }
    }

    @Test
    void aHandleBackedPictureIsStillRefusedWhenItIsNotDisplayReferred() {
        assumeInterop();
        // Hardware decode does not change which pictures this toolkit can show. A PQ picture needs
        // its curve inverted whether its samples arrived as bytes or as a surface, and the surface
        // is the second line of that refusal (FfmpegMedia.open is the first).
        int[] flat = TestPictures.filled(64, 512);
        int[] chroma = TestPictures.filled(16, 512);
        TestIoSurfaces.Surface surface =
                TestIoSurfaces.create(TestIoSurfaces.P010, 8, 8, flat, chroma, chroma);
        try {
            VideoFrame frame = handleFrame(surface, PixelFormat.P010, VideoColor.of(
                    VideoColor.Matrix.BT2020, VideoColor.Range.LIMITED, VideoColor.Transfer.PQ));
            GlVideoSurface target = canvas.glVideo().createSurface();
            UnsupportedOperationException refused =
                    assertThrows(UnsupportedOperationException.class, () -> target.upload(frame));
            assertTrue(refused.getMessage().contains("PQ"), refused.getMessage());
            frame.release();
            target.dispose();
        } finally {
            TestIoSurfaces.release(surface);
        }
    }

    @Test
    void theTwoLayoutsAreNotTheSamePicture() {
        assumeInterop();
        // A guard on this test file rather than on the code: NV12 and P010 differ by their depth
        // and their justification, and if TestIoSurfaces wrote them the same way every assertion
        // above about P010 would be an assertion about NV12.
        int[] codes = TestPictures.filled(64, 600);
        int[] chroma = TestPictures.filled(16, 512);
        TestIoSurfaces.Surface deep =
                TestIoSurfaces.create(TestIoSurfaces.P010, 8, 8, codes, chroma, chroma);
        try {
            VideoFrame frame = handleFrame(deep, PixelFormat.P010, VideoColor.BT709_FULL);
            GlVideoSurface target = upload(frame);
            int[] device = picture(target);
            frame.release();
            target.dispose();
            assertEquals(600, device[0], "full-range grey decodes to its own luma code");
            assertNotEquals(255, device[0], "and it is a ten-bit code, not an eight-bit one");
        } finally {
            TestIoSurfaces.release(deep);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void assumeInterop() {
        HeadlessGl.assumeAvailable();
        Assumptions.assumeTrue(TestIoSurfaces.isAvailable(),
                "IOSurface interop is not reachable here");
    }

    /** A published picture that carries the surface and no samples at all. */
    private static VideoFrame handleFrame(TestIoSurfaces.Surface surface, PixelFormat format,
                                          VideoColor color) {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(surface.width(), surface.height(), format, color);
        writer.setHandle(VideoFrame.Kind.IO_SURFACE, surface.ioSurface());
        return writer.publish();
    }

    /**
     * A released stand-in carrying only the geometry, because
     * {@link GlVideoTestBase#assertMatchesReference} reads the picture's description and never its
     * planes, and the real frame has none to read.
     */
    private static VideoFrame frameDescription(int width, int height, PixelFormat format,
                                               VideoColor color) {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(width, height, format, color);
        writer.setHandle(VideoFrame.Kind.IO_SURFACE, 1L);
        return writer.publish();
    }

    /**
     * Fills the command queue with real conversions, so that a draw issued after this has certainly
     * not run by the time the next few Java statements do. Without it the lifetime assertion above
     * would be a coin toss on a fast driver rather than a test.
     */
    private void queueEnoughWorkThatNothingHasRunYet(int width, int height) {
        int chroma = ((width + 1) / 2) * ((height + 1) / 2);
        VideoFrame filler = TestPictures.frame(width, height, PixelFormat.NV12,
                VideoColor.BT709_LIMITED, TestPictures.filled(width * height, 128),
                TestPictures.filled(chroma, 128), TestPictures.filled(chroma, 128),
                0, true, false);
        GlVideoSurface[] surfaces = new GlVideoSurface[8];
        for (int index = 0; index < surfaces.length; index++) {
            surfaces[index] = canvas.glVideo().createSurface();
            for (int repeat = 0; repeat < 4; repeat++) {
                surfaces[index].upload(filler);
            }
        }
        filler.release();
        for (GlVideoSurface surface : surfaces) {
            surface.dispose();
        }
    }
}

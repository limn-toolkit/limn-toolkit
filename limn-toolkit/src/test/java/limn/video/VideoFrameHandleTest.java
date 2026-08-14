package limn.video;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second shape a picture can have: a device handle instead of samples.
 *
 * <p>What is actually being asserted here is a <em>refusal</em>. A handle-backed frame that
 * answered {@link VideoFrame#plane(int)} with the buffers of whatever the slot held last would be
 * indistinguishable from a working one right up to the moment a wrong picture reached the screen,
 * and every consumer in this repository reads planes. So the two shapes are held apart by the type
 * rather than by care, and the download that bridges them is explicit.
 */
class VideoFrameHandleTest {

    private static final long SURFACE = 0x1234_5678L;

    @Test
    void aHandleBackedPictureRefusesItsPlanesRatherThanInventingThem() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 2, PixelFormat.NV12, VideoColor.BT709_LIMITED);
        writer.setHandle(VideoFrame.Kind.IO_SURFACE, SURFACE);
        VideoFrame frame = writer.publish();

        assertEquals(VideoFrame.Kind.IO_SURFACE, frame.kind());
        assertEquals(SURFACE, frame.handle());
        UnsupportedOperationException refused =
                assertThrows(UnsupportedOperationException.class, () -> frame.plane(0));
        assertTrue(refused.getMessage().contains("IO_SURFACE"), refused.getMessage());
        // The geometry still describes the picture: it is what a consumer binds or converts by.
        assertEquals(4, frame.width());
        assertEquals(PixelFormat.NV12, frame.format());

        frame.release();
    }

    @Test
    void aPlanarPictureRefusesAHandleForTheSameReason() {
        VideoFrame frame = planar();
        assertEquals(VideoFrame.Kind.PLANAR, frame.kind());
        assertNotNull(frame.plane(0));
        assertThrows(UnsupportedOperationException.class, frame::handle);
        frame.release();
    }

    @Test
    void aReleasedHandleIsNotReadableEither() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 2, PixelFormat.NV12, VideoColor.BT709_LIMITED);
        writer.setHandle(VideoFrame.Kind.IO_SURFACE, SURFACE);
        VideoFrame frame = writer.publish();
        frame.release();

        // The allocation went back to the producer's pool with the slot; the number is still in the
        // field and answering with it would hand out a buffer the decoder is refilling.
        assertThrows(IllegalStateException.class, frame::handle);
        assertEquals(VideoFrame.Kind.IO_SURFACE, frame.kind(), "descriptive accessors still answer");
    }

    @Test
    void aConsumerWithoutADeviceAsksForTheDownload() {
        AtomicInteger downloads = new AtomicInteger();
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 2, PixelFormat.NV12, VideoColor.BT709_LIMITED);
        writer.setDownloader(downloaded -> {
            downloads.incrementAndGet();
            bindPlanes(writer, 4, 2, PixelFormat.NV12, 0x21);
            writer.downloaded();
        });
        writer.setHandle(VideoFrame.Kind.IO_SURFACE, SURFACE);
        VideoFrame frame = writer.publish();

        frame.toPlanar();

        assertEquals(1, downloads.get());
        assertEquals(VideoFrame.Kind.PLANAR, frame.kind());
        assertEquals(0x21, frame.plane(0).get(0) & 0xFF);
        assertThrows(UnsupportedOperationException.class, frame::handle,
                "once read back it is a planar picture and nothing else");

        // Idempotent, and it does not ask the producer twice: a picture already in memory is one a
        // second download would only copy on top of itself.
        frame.toPlanar();
        assertEquals(1, downloads.get());

        frame.release();
    }

    @Test
    void aProducerWithNoDownloadRefusesInsteadOfPretending() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 2, PixelFormat.NV12, VideoColor.BT709_LIMITED);
        writer.setHandle(VideoFrame.Kind.IO_SURFACE, SURFACE);
        VideoFrame frame = writer.publish();

        assertThrows(UnsupportedOperationException.class, frame::toPlanar);

        frame.release();
    }

    @Test
    void aDownloaderThatBindsNothingIsAnErrorAndNotASilentlyEmptyPicture() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 2, PixelFormat.NV12, VideoColor.BT709_LIMITED);
        writer.setDownloader(frame -> { });
        writer.setHandle(VideoFrame.Kind.IO_SURFACE, SURFACE);
        VideoFrame frame = writer.publish();

        assertThrows(IllegalStateException.class, frame::toPlanar);

        frame.release();
    }

    @Test
    void onlyADownloadMayRepointAHeldFrame() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 2, PixelFormat.NV12, VideoColor.BT709_LIMITED);
        bindPlanes(writer, 4, 2, PixelFormat.NV12, 0x11);
        VideoFrame frame = writer.publish();

        // The publication flag is what stops a producer writing over a picture a consumer is
        // reading; the download exemption must not have opened that up generally.
        assertThrows(IllegalStateException.class,
                () -> bindPlanes(writer, 4, 2, PixelFormat.NV12, 0x22));
        assertThrows(IllegalStateException.class, writer::downloaded);
        assertThrows(IllegalStateException.class,
                () -> writer.setHandle(VideoFrame.Kind.IO_SURFACE, SURFACE));

        frame.release();
    }

    @Test
    void aRecycledSlotComesBackAsWhateverTheNextPictureIs() {
        // One slot, two pictures of different shapes, which is what a stream that falls back from
        // hardware to software mid-flight actually looks like.
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 2, PixelFormat.NV12, VideoColor.BT709_LIMITED);
        writer.setHandle(VideoFrame.Kind.IO_SURFACE, SURFACE);
        VideoFrame first = writer.publish();
        assertEquals(VideoFrame.Kind.IO_SURFACE, first.kind());
        first.release();

        bindPlanes(writer, 4, 2, PixelFormat.NV12, 0x33);
        VideoFrame second = writer.publish();
        assertSame(first, second, "a pool lends the same instance");
        assertEquals(VideoFrame.Kind.PLANAR, second.kind());
        assertEquals(0x33, second.plane(0).get(0) & 0xFF);
        second.release();
    }

    private static VideoFrame planar() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 2, PixelFormat.NV12, VideoColor.BT709_LIMITED);
        bindPlanes(writer, 4, 2, PixelFormat.NV12, 0x11);
        return writer.publish();
    }

    private static void bindPlanes(VideoFrame.Writer writer, int width, int height,
                                   PixelFormat format, int fill) {
        for (int plane = 0; plane < format.planeCount(); plane++) {
            int stride = format.planeByteWidth(plane, width);
            int rows = format.planeHeight(plane, height);
            ByteBuffer buffer = ByteBuffer.allocateDirect(stride * rows);
            for (int index = 0; index < stride * rows; index++) {
                buffer.put(index, (byte) fill);
            }
            writer.setPlane(plane, buffer, stride);
        }
    }
}

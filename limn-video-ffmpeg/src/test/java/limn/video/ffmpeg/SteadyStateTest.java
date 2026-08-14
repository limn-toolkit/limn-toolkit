package limn.video.ffmpeg;

import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What decoding a picture costs once the stream is running.
 *
 * <p>Thirty pictures a second per stream is thirty times whatever this is. A wrapper object, a
 * {@code ByteBuffer}, a {@code String} for a diagnostic: any of them turns a decoder into a
 * collection every few seconds, and none of them is visible in a profile that looks at the decode
 * itself. So it is measured.
 *
 * <p>The two things that make it zero are worth naming, because both are easy to undo: the
 * per-picture call fills a {@code long[]} the stream allocated once, and the plane bindings are
 * rebound only when the shim reports that a slot's memory moved; {@code setPlane} stores a
 * read-only view, so rebinding every picture would be an allocation per plane per picture.
 */
class SteadyStateTest {

    @TempDir
    Path directory;

    @Test
    void decodingAPictureAllocatesNothing() throws IOException {
        FfmpegTests.requireWriter();
        assumeTrue(AllocationProbe.isSupported(), "this virtual machine does not count allocation");

        Path clip = FfmpegTests.clip(directory, 160, 120, 400, 0);
        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4)) {
            VideoStreamSource video = media.video();

            // Warm up past the point where the decoder's own buffer pool is still growing. While
            // it grows, each new address is a ByteBuffer the shim has to create and the stream has
            // to bind, which is exactly the allocation this measures, so measuring during warm-up
            // would measure the wrong thing and passing it would prove nothing.
            for (int i = 0; i < 60; i++) {
                assertEquals(VideoStreamSource.Read.FRAME, RoundTripTest.readNext(video));
                video.frame().release();
            }

            Runnable onePicture = () -> {
                if (video.readFrame() == VideoStreamSource.Read.FRAME) {
                    video.frame().release();
                }
            };
            long least = AllocationProbe.leastAllocatedBy(onePicture, 200);
            assertEquals(0L, least,
                    "a picture in steady state allocated " + least + " bytes on the decode thread");
        }
    }

    /**
     * The same, with three audio tracks in the container, because the demultiplexer now decides
     * per packet whether a stream is the selected one, and the pictures pay for every packet of the
     * other two being met and freed. That work is the shim's and must stay there: a decision made
     * on the Java side, or a diagnostic built for a packet that is thrown away, would be an
     * allocation per packet rather than per picture, which is worse than the thing this file exists
     * to prevent.
     */
    @Test
    void decodingAPictureAllocatesNothingWithSeveralSoundtracksBesideIt() throws IOException {
        FfmpegTests.requireWriter();
        assumeTrue(AllocationProbe.isSupported(), "this virtual machine does not count allocation");

        Path clip = FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MJPEG, 160, 120, 400,
                java.util.List.of(new FfmpegMedia.ClipAudioTrack(2, "eng"),
                        new FfmpegMedia.ClipAudioTrack(2, "fra"),
                        new FfmpegMedia.ClipAudioTrack(1, "deu")));
        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            VideoStreamSource video = media.video();
            for (int i = 0; i < 60; i++) {
                assertEquals(VideoStreamSource.Read.FRAME, RoundTripTest.readNext(video));
                video.frame().release();
            }
            Runnable onePicture = () -> {
                if (video.readFrame() == VideoStreamSource.Read.FRAME) {
                    video.frame().release();
                }
            };
            long least = AllocationProbe.leastAllocatedBy(onePicture, 200);
            assertEquals(0L, least,
                    "a picture beside three soundtracks allocated " + least + " bytes");
        }
    }

    @Test
    void thePlanesAreLibavcodecsOwnMemoryRatherThanACopy() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 320, 240, 16, 0);

        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4)) {
            VideoStreamSource video = media.video();
            assertEquals(VideoStreamSource.Read.FRAME, RoundTripTest.readNext(video));
            var frame = video.frame();

            // Direct, because that is what makes the upload path copy-free: a heap plane has no
            // address to hand a device and would be staged through a buffer the surface owns.
            for (int plane = 0; plane < frame.format().planeCount(); plane++) {
                assertTrue(frame.plane(plane).isDirect(),
                        "plane " + plane + " must be direct memory the device can be given");
                assertTrue(frame.plane(plane).isReadOnly(),
                        "plane " + plane + " is the producer's memory, lent out");
            }

            // The plane must reach its last sample and no further. A buffer sized stride * rows
            // would name bytes past the end of a plane that ends at its final sample, which is
            // what a tight producer hands over.
            int stride = frame.stride(0);
            int lastByte = stride * (frame.height() - 1) + frame.width();
            assertTrue(frame.plane(0).limit() >= lastByte,
                    "the luma plane covers its last row");
            frame.release();
        }
    }
}

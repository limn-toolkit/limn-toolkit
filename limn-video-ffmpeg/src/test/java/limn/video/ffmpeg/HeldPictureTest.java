package limn.video.ffmpeg;

import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A container closed while a consumer still holds its pictures, which is what a view showing a
 * stream does when the application closes the stream under it: the view keeps the picture on
 * screen and the one it read ahead, and the next paint uploads one of them. Those pictures are
 * libavcodec's memory, and closing the container used to free it there and then, so the upload
 * read freed planes, or bound a released IOSurface and died in {@code IOSurfaceClientGetID}.
 *
 * <p>Read from the committed H.264 clip, which the published player payload decodes, so this runs
 * wherever the decoder does and needs no encoder to make its input.
 */
class HeldPictureTest {

    @Test
    void aPictureHeldAcrossCloseKeepsItsSamplesUntilTheLastOneIsReleased() throws Exception {
        FfmpegTests.requireLibrary();
        FfmpegMedia media = FfmpegMedia.open(clip(), false, FfmpegMedia.DEFAULT_SLOTS,
                FfmpegMedia.Hardware.OFF);
        VideoStreamSource video = media.video();
        VideoFrame shown = first(video);
        VideoFrame ahead = first(video); // a view holds two: the picture and the one after it
        byte[] luma = luma(shown);

        video.close();

        assertEquals(VideoStreamSource.Read.END, video.readFrame(), "closed, to a reader");
        assertFalse(media.isOpen(), "and to anyone asking");
        // Asked first, so that on a decoder freed under the pictures the next line is not the one
        // to find out by reading freed memory.
        assertTrue(decoderAlive(media),
                "the decoder was freed under two pictures a consumer still holds");
        assertArrayEquals(luma, luma(shown), "the held picture is still the picture it was");

        shown.release();
        assertTrue(decoderAlive(media), "one picture is still out, and its memory with it");
        ahead.release();
        assertFalse(decoderAlive(media), "the last release frees what the close put off");
        media.close(); // idempotent, and nothing is left to free
    }

    @Test
    void aHardwarePictureHeldAcrossCloseCanStillBeReadBack() throws Exception {
        FfmpegTests.requireLibrary();
        FfmpegMedia media = FfmpegMedia.open(clip(), false, FfmpegMedia.DEFAULT_SLOTS,
                FfmpegMedia.Hardware.PREFER);
        try {
            assumeTrue(media.isHardwareDecoding(), "no accelerator took this clip here");
            VideoFrame held = first(media.video());
            assertEquals(VideoFrame.Kind.IO_SURFACE, held.kind());

            media.close();

            // The surface a view would bind on its next paint is still the decoder's to lend.
            held.toPlanar();
            assertEquals(VideoFrame.Kind.PLANAR, held.kind());
            assertTrue(held.plane(0).remaining() > 0);
            held.release();
            assertFalse(decoderAlive(media), "released, so nothing holds the decoder open");
        } finally {
            media.close();
        }
    }

    /** The smallest film in the committed corpus, which is the 360p one. */
    private static Path clip() throws IOException {
        return FfmpegTests.realClips("to hold a picture of a real film across a close").stream()
                .filter(file -> file.getFileName().toString().endsWith(".mp4"))
                .min(Comparator.comparingLong(HeldPictureTest::size))
                .orElseThrow(() -> new AssertionError("the corpus holds no .mp4"));
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static VideoFrame first(VideoStreamSource video) {
        for (int attempt = 0; attempt < 256; attempt++) {
            VideoStreamSource.Read read = video.readFrame();
            if (read == VideoStreamSource.Read.FRAME) {
                return video.frame();
            }
            if (read == VideoStreamSource.Read.END) {
                break;
            }
        }
        throw new AssertionError("no picture arrived");
    }

    private static byte[] luma(VideoFrame frame) {
        ByteBuffer plane = frame.plane(0).duplicate();
        byte[] copy = new byte[plane.remaining()];
        plane.get(copy);
        return copy;
    }

    /**
     * Whether the native decoder still exists, read off the container's handle. The one fact here
     * no public method can report without reading the memory in question.
     */
    private static boolean decoderAlive(FfmpegMedia media) throws ReflectiveOperationException {
        Field handle = FfmpegMedia.class.getDeclaredField("handle");
        handle.setAccessible(true);
        return handle.getLong(media) != 0;
    }
}

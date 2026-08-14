package limn.video.ffmpeg;

import limn.video.PixelFormat;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real MP4 written and read back: the demux, the packet-to-frame path, the planar handoff, the
 * pool and the release discipline, all against a file rather than a mock.
 *
 * <p>What this does <b>not</b> prove is libavcodec's H.264 decoder, and it is worth being plain
 * about that: FFmpeg's H.264 encoder is x264 and x264 is GPL, so an LGPL build cannot produce H.264
 * to read back and the clip here is MPEG-4 Part 2. The decoder itself is FFmpeg's, is covered by
 * FFmpeg's own suite, and is not this repository's to re-test. What is this repository's is
 * everything between the container and {@code VideoFrame}, and that is the same code for both.
 */
class RoundTripTest {

    @TempDir
    Path directory;

    @Test
    void aWrittenClipReadsBackWithTheGeometryItWasWrittenWith() throws IOException {
        FfmpegTests.requireWriter();
        Path file = FfmpegTests.clip(directory, 160, 120, 24, 0);

        try (FfmpegMedia media = FfmpegMedia.open(file)) {
            VideoStreamSource video = media.video();
            assertEquals(160, video.width());
            assertEquals(120, video.height());
            assertEquals(PixelFormat.I420, video.pixelFormat(),
                    "an MPEG-4 Part 2 decode is planar 4:2:0");
            assertEquals(30, video.frameRateNum());
            assertEquals(1, video.frameRateDen());
            assertTrue(video.durationMicros() > 0, "an MP4 states its duration");
            assertNull(video.frame(), "no picture has been read yet");
        }
    }

    @Test
    void everyPictureArrivesAndCarriesItsSamples() throws IOException {
        FfmpegTests.requireWriter();
        int frames = 24;
        Path file = FfmpegTests.clip(directory, 160, 120, frames, 0);

        try (FfmpegMedia media = FfmpegMedia.open(file)) {
            VideoStreamSource video = media.video();
            int decoded = 0;
            long previous = Long.MIN_VALUE;
            for (int attempt = 0; attempt < frames * 8; attempt++) {
                VideoStreamSource.Read read = video.readFrame();
                if (read == VideoStreamSource.Read.END) {
                    break;
                }
                if (read == VideoStreamSource.Read.PENDING) {
                    continue;
                }
                VideoFrame frame = video.frame();
                assertNotNull(frame);
                assertEquals(160, frame.width());
                assertEquals(120, frame.height());
                assertEquals(PixelFormat.I420, frame.format());

                // The luma plane is readable across its whole extent, at the stride the frame
                // reports. Touching the last byte of the last row is what catches a plane sized
                // as stride*rows against a buffer that ends at its final sample.
                ByteBuffer luma = frame.plane(0);
                int stride = frame.stride(0);
                int last = stride * (frame.height() - 1) + frame.width() - 1;
                assertTrue(last < luma.limit(),
                        "the luma plane must reach its last sample: " + last + " vs " + luma.limit());
                int value = luma.get(last) & 0xFF;
                assertTrue(value >= 0 && value <= 255);

                if (decoded > 0) {
                    assertTrue(frame.ptsMicros() >= previous,
                            "presentation times are non-decreasing: " + frame.ptsMicros()
                                    + " after " + previous);
                }
                previous = frame.ptsMicros();
                frame.release();
                decoded++;
            }
            assertEquals(frames, decoded, "every picture written comes back");
        }
    }

    /**
     * Eight flat bars, shifting one bar per picture. A lossy encoder moves every sample, so this
     * asserts a block's mean rather than a sample, but a swapped chroma pair, a plane read at the
     * wrong stride or a picture delivered from the wrong slot all move that mean far more than
     * the codec does.
     */
    @Test
    void theBarsComeBackWhereTheyWereWritten() throws IOException {
        FfmpegTests.requireWriter();
        Path file = FfmpegTests.clip(directory, 320, 240, 8, 0);

        try (FfmpegMedia media = FfmpegMedia.open(file)) {
            VideoStreamSource video = media.video();
            assertEquals(VideoStreamSource.Read.FRAME, readNext(video));
            VideoFrame frame = video.frame();

            // Picture 0: bar b covers x in [b*W/8, (b+1)*W/8) and carries BAR_Y[b], which
            // descends from 235 to 16. So the left half is brighter than the right half, by a
            // margin nothing lossy can close.
            double left = meanLuma(frame, 0, frame.width() / 2);
            double right = meanLuma(frame, frame.width() / 2, frame.width());
            assertTrue(left > right + 40,
                    "the bars descend in luma: left " + left + " vs right " + right);

            // The first bar is nominally 235 and the last nominally 16.
            double first = meanLuma(frame, 4, frame.width() / 8 - 4);
            double last = meanLuma(frame, frame.width() * 7 / 8 + 4, frame.width() - 4);
            assertTrue(Math.abs(first - 235) < 12, "the first bar decodes near 235, got " + first);
            assertTrue(Math.abs(last - 16) < 12, "the last bar decodes near 16, got " + last);

            frame.release();
        }
    }

    private static double meanLuma(VideoFrame frame, int fromX, int toX) {
        ByteBuffer luma = frame.plane(0);
        int stride = frame.stride(0);
        long total = 0;
        int count = 0;
        for (int y = frame.height() / 4; y < frame.height() * 3 / 4; y++) {
            for (int x = fromX; x < toX; x++) {
                total += luma.get(y * stride + x) & 0xFF;
                count++;
            }
        }
        return count == 0 ? 0 : (double) total / count;
    }

    @Test
    void everySlotIsHandedBackAndTheStreamKeepsGoing() throws IOException {
        FfmpegTests.requireWriter();
        Path file = FfmpegTests.clip(directory, 160, 120, 40, 0);

        // One slot: the decoder can produce nothing at all until the consumer releases, so this
        // is the configuration in which a missing release stops the stream dead rather than
        // merely slowing it.
        try (FfmpegMedia media = FfmpegMedia.open(file, false, 1)) {
            VideoStreamSource video = media.video();
            int decoded = 0;
            for (int attempt = 0; attempt < 400 && decoded < 40; attempt++) {
                if (video.readFrame() == VideoStreamSource.Read.FRAME) {
                    video.frame().release();
                    decoded++;
                }
            }
            assertEquals(40, decoded, "a single slot recycled all the way through");
        }
    }

    @Test
    void holdingEverySlotIsPendingAndNotAnEnd() throws IOException {
        FfmpegTests.requireWriter();
        Path file = FfmpegTests.clip(directory, 160, 120, 40, 0);

        try (FfmpegMedia media = FfmpegMedia.open(file, false, 2)) {
            VideoStreamSource video = media.video();
            VideoFrame[] held = new VideoFrame[2];
            for (int i = 0; i < 2; i++) {
                assertEquals(VideoStreamSource.Read.FRAME, readNext(video));
                held[i] = video.frame();
            }
            // Both slots are out with this test. The next read has nowhere to decode into, and
            // that is PENDING, not an end, not an error, and not a copy taken so it can pretend
            // otherwise.
            assertEquals(VideoStreamSource.Read.PENDING, video.readFrame());
            assertEquals(VideoStreamSource.Read.PENDING, video.readFrame(),
                    "asking again while still holding everything says the same thing");

            held[0].release();
            assertEquals(VideoStreamSource.Read.FRAME, readNext(video));
            video.frame().release();
            held[1].release();
        }
    }

    @Test
    void aPictureIsReleasedExactlyOnceAcrossTheBoundary() throws IOException {
        FfmpegTests.requireWriter();
        Path file = FfmpegTests.clip(directory, 160, 120, 8, 0);

        try (FfmpegMedia media = FfmpegMedia.open(file, false, 2)) {
            VideoStreamSource video = media.video();
            assertEquals(VideoStreamSource.Read.FRAME, readNext(video));
            VideoFrame frame = video.frame();
            long generation = frame.generation();
            assertEquals(1L, generation & 1L, "a delivered picture is held");

            frame.release();
            assertEquals(generation + 1, frame.generation());
            // A second release would return one slot to the producer twice, and the picture
            // tearing that causes appears nowhere near the call that caused it. It is loud here
            // instead, and it never reaches the shim.
            assertThrows(IllegalStateException.class, frame::release);
            assertThrows(IllegalStateException.class, () -> frame.plane(0),
                    "a released picture's planes belong to the producer again");
        }
    }

    @Test
    void rewindingStartsTheSamePicturesAgain() throws IOException {
        FfmpegTests.requireWriter();
        Path file = FfmpegTests.clip(directory, 160, 120, 16, 0);

        try (FfmpegMedia media = FfmpegMedia.open(file)) {
            VideoStreamSource video = media.video();
            assertTrue(video.canReset());

            assertEquals(VideoStreamSource.Read.FRAME, readNext(video));
            long firstPts = video.frame().ptsMicros();
            double firstMean = meanLuma(video.frame(), 0, video.frame().width());
            video.frame().release();

            int decoded = 1;
            while (decoded < 8 && readNext(video) == VideoStreamSource.Read.FRAME) {
                video.frame().release();
                decoded++;
            }

            video.reset();
            assertNull(video.frame(), "a rewound stream has delivered nothing yet");
            assertEquals(VideoStreamSource.Read.FRAME, readNext(video));
            assertEquals(firstPts, video.frame().ptsMicros(),
                    "the first picture after a rewind is the first picture");
            assertEquals(firstMean, meanLuma(video.frame(), 0, video.frame().width()), 1.0,
                    "and it carries the same samples");
            video.frame().release();
        }
    }

    @Test
    void aClosedContainerReportsTheEndRatherThanReachingIntoFreedMemory() throws IOException {
        FfmpegTests.requireWriter();
        Path file = FfmpegTests.clip(directory, 160, 120, 8, 2);

        FfmpegMedia media = FfmpegMedia.open(file);
        VideoStreamSource video = media.video();
        var audio = media.audio();
        assertNotNull(audio);
        assertEquals(VideoStreamSource.Read.FRAME, readNext(video));
        video.frame().release();

        media.close();
        media.close(); // idempotent

        // The soundtrack can outlive the player, so the engine's streaming thread may genuinely
        // still be reading after an application closes the container. It must be answered, and
        // zero frames is what the end of a track means to the engine.
        assertEquals(0, audio.readFrames(new short[256], 128));
        assertEquals(VideoStreamSource.Read.END, video.readFrame());
        assertTrue(!media.isOpen());
    }

    @Test
    void closingTheVideoTrackClosesTheContainer() throws IOException {
        FfmpegTests.requireWriter();
        Path file = FfmpegTests.clip(directory, 160, 120, 8, 0);

        FfmpegMedia media = FfmpegMedia.open(file);
        // A caller that came through Videos.open never sees the container, so closing what it was
        // handed has to be enough.
        media.video().close();
        assertTrue(!media.isOpen());
    }

    @Test
    void theSameStreamInstanceIsHandedOutEveryTime() throws IOException {
        FfmpegTests.requireWriter();
        Path file = FfmpegTests.clip(directory, 160, 120, 4, 2);
        try (FfmpegMedia media = FfmpegMedia.open(file)) {
            assertSame(media.video(), media.video());
            assertSame(media.audio(), media.audio());
        }
    }

    /** Reads past any PENDING, so a test can assert what the stream produced rather than when. */
    static VideoStreamSource.Read readNext(VideoStreamSource video) {
        for (int attempt = 0; attempt < 256; attempt++) {
            VideoStreamSource.Read read = video.readFrame();
            if (read != VideoStreamSource.Read.PENDING) {
                return read;
            }
        }
        return VideoStreamSource.Read.PENDING;
    }
}

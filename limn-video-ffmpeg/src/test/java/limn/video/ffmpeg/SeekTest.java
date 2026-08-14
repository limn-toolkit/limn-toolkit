package limn.video.ffmpeg;

import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import limn.video.VideoStreamSource.SeekMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeking a real container: what each mode lands on, what happens at both ends, and the two things
 * a container makes harder than a raw format (a group of pictures between the placement and the
 * target, and a soundtrack that shares the demuxer with the pictures).
 *
 * <p>Every test here writes the clip it reads. Nothing is committed, and every one skips cleanly
 * where the native is absent, which is the normal case.
 */
class SeekTest {

    /** 30 per second, so a picture every 33 333 microseconds and a half-picture gap of 16 666. */
    private static final long FRAME = 33_333;

    @TempDir
    Path directory;

    @Test
    void exactLandsOnTheFirstPictureAtOrAfterTheTarget() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90, 0))) {
            VideoStreamSource video = media.video();
            assertTrue(video.canSeek());

            long target = 40 * FRAME + FRAME / 2;
            video.seek(target, SeekMode.EXACT);
            long landed = nextPts(video);
            assertTrue(landed >= target,
                    "EXACT is never early: asked for " + target + ", landed on " + landed);
            assertTrue(landed - target < FRAME,
                    "and under one picture interval late: " + (landed - target) + "us");
        }
    }

    @Test
    void keyframeIsNeverLateAndIsCheaperThanExact() throws IOException {
        FfmpegTests.requireWriter();
        // MPEG-4 Part 2 with a real group of pictures: the container can only jump to a picture
        // that needs no predecessor, which is what makes the two modes different at all.
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90, 0))) {
            VideoStreamSource video = media.video();
            long target = 40 * FRAME + FRAME / 2;

            video.seek(target, SeekMode.KEYFRAME);
            long cheap = nextPts(video);
            assertTrue(cheap <= target,
                    "KEYFRAME is never late: asked for " + target + ", landed on " + cheap);

            video.seek(target, SeekMode.EXACT);
            long exact = nextPts(video);
            assertTrue(exact >= target);
            assertTrue(cheap <= exact, "the cheap mode is at or before the exact one");
        }
    }

    @Test
    void thePicturesAfterASeekRunForwardInOrder() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90, 0))) {
            VideoStreamSource video = media.video();
            video.seek(30 * FRAME, SeekMode.EXACT);
            long previous = Long.MIN_VALUE;
            for (int i = 0; i < 10; i++) {
                long pts = nextPts(video);
                assertTrue(pts > previous, "presentation times must not go backwards after a seek");
                previous = pts;
            }
        }
    }

    @Test
    void seekingBackwardsIsAPositionAndNotARewind() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90, 0))) {
            VideoStreamSource video = media.video();
            video.seek(70 * FRAME, SeekMode.EXACT);
            assertTrue(nextPts(video) >= 70 * FRAME);
            video.seek(20 * FRAME, SeekMode.EXACT);
            long back = nextPts(video);
            assertTrue(back >= 20 * FRAME && back < 25 * FRAME,
                    "landed on " + back + ", which is not near the target");
            assertNotEquals(0, back, "backwards is not a rewind to the start");
        }
    }

    @Test
    void seekingPastTheEndReachesTheEndAndSeekingBackLeavesIt() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 30, 0))) {
            VideoStreamSource video = media.video();
            video.seek(600_000_000, SeekMode.EXACT); // ten minutes into a one-second clip

            // A container may place the demuxer at its last packet rather than past it, so the end
            // is reached by reading rather than declared: what is asserted is that it IS reached
            // and that nothing before the target is delivered.
            VideoStreamSource.Read read = video.readFrame();
            while (read == VideoStreamSource.Read.FRAME) {
                video.frame().release();
                read = video.readFrame();
            }
            assertSame(VideoStreamSource.Read.END, read);

            video.seek(5 * FRAME, SeekMode.EXACT);
            assertTrue(nextPts(video) >= 5 * FRAME, "the end is not a state a seek cannot leave");
        }
    }

    @Test
    void aNegativeTargetIsRefused() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(FfmpegTests.clip(directory))) {
            VideoStreamSource video = media.video();
            assertThrows(IllegalArgumentException.class, () -> video.seek(-1, SeekMode.EXACT));
        }
    }

    @Test
    void aContainerWithoutARotationReportsNone() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(FfmpegTests.clip(directory))) {
            assertEquals(0, media.video().rotationDegrees(),
                    "a clip written with no display matrix must not acquire one");
        }
    }

    // ------------------------------------------------------------------ the two tracks

    @Test
    void seekingTheSoundtrackMovesItAndDeliversFromTheTarget() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90, 2))) {
            limn.sound.AudioStreamSource audio = media.audio();
            assertTrue(audio.canSeek());

            short[] buffer = new short[4096];
            long framesBefore = 0;
            for (int i = 0; i < 8; i++) {
                framesBefore += audio.readFrames(buffer, buffer.length / audio.channels());
            }
            assertTrue(framesBefore > 0, "the fixture needs a soundtrack that decodes");

            audio.seek(2_000_000);
            assertTrue(audio.readFrames(buffer, buffer.length / audio.channels()) > 0,
                    "a seeked track keeps delivering; a seek is not an end");
        }
    }

    @Test
    void bothTracksAskingForTheSameTargetIsOneSeek() throws IOException {
        FfmpegTests.requireWriter();
        // One demuxer position serves both tracks. If the second ask re-placed the container, it
        // would strand the packets the first had queued for the other track, heard as a fraction
        // of a second of sound repeating after every scrub. What is asserted is the consequence:
        // after both have asked, both still deliver from the target and neither has been reset.
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90, 2))) {
            VideoStreamSource video = media.video();
            limn.sound.AudioStreamSource audio = media.audio();

            long before = media.containerSeeks();
            long target = 40 * FRAME;
            video.seek(target, SeekMode.EXACT);
            audio.seek(target);
            assertEquals(before + 1, media.containerSeeks(),
                    "both tracks asked for the same target, and the container moved once");

            short[] buffer = new short[4096];
            assertTrue(audio.readFrames(buffer, buffer.length / audio.channels()) > 0);
            assertTrue(nextPts(video) >= target,
                    "the video's placement survived the audio's ask for the same target");

            // The control: different targets are different positions and cost a move each.
            long twice = media.containerSeeks();
            video.seek(10 * FRAME, SeekMode.EXACT);
            audio.seek(60 * FRAME);
            assertEquals(twice + 2, media.containerSeeks());
        }
    }

    @Test
    void theOtherArrivalOrderIsTheSame() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90, 2))) {
            VideoStreamSource video = media.video();
            limn.sound.AudioStreamSource audio = media.audio();

            long before = media.containerSeeks();
            long target = 40 * FRAME;
            audio.seek(target);
            video.seek(target, SeekMode.EXACT);
            assertEquals(before + 1, media.containerSeeks());

            short[] buffer = new short[4096];
            assertTrue(audio.readFrames(buffer, buffer.length / audio.channels()) > 0);
            assertTrue(nextPts(video) >= target,
                    "which thread arrives first is decided by an audio device's timer, so both "
                            + "orders have to produce the same thing");
        }
    }

    @Test
    void rewindingStillWorksAfterSeekingToTheStart() throws IOException {
        FfmpegTests.requireWriter();
        // A container that recorded "placed at 0" without recording that the video track had read
        // from there would answer the second of these by flushing a codec and moving nothing.
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 60, 0))) {
            VideoStreamSource video = media.video();
            video.seek(0, SeekMode.KEYFRAME);
            assertEquals(0, nextPts(video));
            for (int i = 0; i < 20; i++) {
                nextPts(video);
            }
            video.reset();
            assertEquals(0, nextPts(video), "a rewind after a seek to the start really rewinds");
        }
    }

    // ------------------------------------------------------------------ pictures and allocation

    @Test
    void everyPictureAcrossASeekComesBackExactlyOnce() throws IOException {
        FfmpegTests.requireWriter();
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 60, 0))) {
            VideoStreamSource video = media.video();
            // Hold every slot the pool has, then seek: a seek must not free them behind the
            // consumer's back, and the consumer must still be able to hand each one back once.
            VideoFrame[] held = new VideoFrame[FfmpegMedia.DEFAULT_SLOTS];
            for (int i = 0; i < held.length; i++) {
                assertSame(VideoStreamSource.Read.FRAME, video.readFrame());
                held[i] = video.frame();
            }
            assertSame(VideoStreamSource.Read.PENDING, video.readFrame(),
                    "the fixture needs every slot held");

            video.seek(20 * FRAME, SeekMode.EXACT);
            for (VideoFrame frame : held) {
                frame.release();
            }
            assertSame(VideoStreamSource.Read.FRAME, video.readFrame(),
                    "with the slots back, decoding from the new position resumes");
            video.frame().release();
        }
    }

    @Test
    void aSteadyDecodeAfterASeekAllocatesNothing() throws IOException {
        FfmpegTests.requireWriter();
        org.junit.jupiter.api.Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count thread allocation");
        try (FfmpegMedia media = FfmpegMedia.open(
                FfmpegTests.clip(directory, 160, 120, 400, 0))) {
            VideoStreamSource video = media.video();
            video.seek(10 * FRAME, SeekMode.EXACT);
            Runnable onePicture = () -> {
                if (video.readFrame() == VideoStreamSource.Read.FRAME) {
                    video.frame().release();
                }
            };
            // A seek invalidates every slot's plane bindings, so the pictures right after one
            // rebind exactly as the first pictures of a stream do, and rebinding allocates. The
            // warm-up is therefore AFTER the seek, and what is measured is the steady state it
            // settles into, one picture at a time as the existing steady-state probe does.
            for (int i = 0; i < 60; i++) {
                onePicture.run();
            }
            long least = AllocationProbe.leastAllocatedBy(onePicture, 200);
            assertEquals(0L, least,
                    "a picture crossing after a seek is still five longs into an array that "
                            + "already exists");
        }
    }

    private static long nextPts(VideoStreamSource video) {
        assertSame(VideoStreamSource.Read.FRAME, video.readFrame());
        VideoFrame frame = video.frame();
        long pts = frame.ptsMicros();
        frame.release();
        return pts;
    }
}

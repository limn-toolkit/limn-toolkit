package limn.video.decode;

import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import limn.video.VideoStreamSource.SeekMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What "seek to t" is worth, against clips whose timestamps are known because they were written
 * here: the two modes' promises, both ends of the input, and the pictures that come after.
 *
 * <p>Targets are deliberately placed <em>between</em> two pictures rather than on one. A target on a
 * picture's own presentation time would pass under a rule that is off by one picture in either
 * direction, and the whole difference between the modes is which side of the gap they land on.
 */
class SeekPrecisionTest {

    /** 25 per second: a picture every 40 000 microseconds, exactly, in both decoders. */
    private static final int RATE = 25;
    private static final long FRAME = 1_000_000L / RATE;

    @TempDir
    Path directory;

    @Test
    void exactLandsOnTheFirstPictureAtOrAfterTheTarget() {
        try (VideoStreamSource stream = clip(20)) {
            stream.seek(4 * FRAME + FRAME / 2, SeekMode.EXACT);
            assertEquals(5 * FRAME, nextPts(stream));
        }
    }

    @Test
    void keyframeLandsOnThePictureAtOrBeforeTheTarget() {
        try (VideoStreamSource stream = clip(20)) {
            stream.seek(4 * FRAME + FRAME / 2, SeekMode.KEYFRAME);
            assertEquals(4 * FRAME, nextPts(stream),
                    "every picture here is independently decodable, so the cheap mode lands on the "
                            + "one that would be on screen at that instant");
        }
    }

    @Test
    void aTargetOnAPicturesOwnTimeIsThatPictureInBothModes() {
        try (VideoStreamSource stream = clip(20)) {
            stream.seek(7 * FRAME, SeekMode.EXACT);
            assertEquals(7 * FRAME, nextPts(stream));
            stream.seek(7 * FRAME, SeekMode.KEYFRAME);
            assertEquals(7 * FRAME, nextPts(stream));
        }
    }

    @Test
    void thePicturesAfterASeekFollowInOrder() {
        try (VideoStreamSource stream = clip(20)) {
            stream.seek(10 * FRAME, SeekMode.EXACT);
            for (int index = 10; index < 14; index++) {
                assertEquals(index * FRAME, nextPts(stream), "picture " + index);
            }
        }
    }

    @Test
    void seekingBackwardsWorksAndIsNotARewind() {
        try (VideoStreamSource stream = clip(20)) {
            stream.seek(15 * FRAME, SeekMode.EXACT);
            assertEquals(15 * FRAME, nextPts(stream));
            stream.seek(3 * FRAME, SeekMode.EXACT);
            assertEquals(3 * FRAME, nextPts(stream),
                    "backwards is a position, not a rewind to the start");
        }
    }

    @Test
    void seekingBeforeTheStartIsTheStart() {
        try (VideoStreamSource stream = clip(20)) {
            stream.seek(9 * FRAME, SeekMode.EXACT);
            nextPts(stream);
            stream.seek(0, SeekMode.KEYFRAME);
            assertEquals(0, nextPts(stream));
        }
    }

    @Test
    void seekingPastTheEndReachesTheEnd() {
        try (VideoStreamSource stream = clip(8)) {
            stream.seek(1_000 * FRAME, SeekMode.EXACT);
            assertSame(VideoStreamSource.Read.END, stream.readFrame(),
                    "a target beyond the last picture is a position, and the position is the end");
        }
    }

    @Test
    void seekingBackFromPastTheEndPlaysAgain() {
        try (VideoStreamSource stream = clip(8)) {
            stream.seek(1_000 * FRAME, SeekMode.EXACT);
            assertSame(VideoStreamSource.Read.END, stream.readFrame());
            stream.seek(2 * FRAME, SeekMode.EXACT);
            assertEquals(2 * FRAME, nextPts(stream), "the end is not a state a seek cannot leave");
        }
    }

    @Test
    void aNegativeTargetIsRefused() {
        try (VideoStreamSource stream = clip(4)) {
            assertThrows(IllegalArgumentException.class, () -> stream.seek(-1, SeekMode.EXACT));
        }
    }

    @Test
    void theGeneratorSeeksTheSameWay() {
        SyntheticSpec spec = SyntheticSpec.of(16, 16).withRate(RATE, 1).withFrameCount(20);
        try (VideoStreamSource stream = SyntheticVideoDecoder.open(spec)) {
            assertTrue(stream.canSeek());
            stream.seek(4 * FRAME + FRAME / 2, SeekMode.EXACT);
            assertEquals(5 * FRAME, nextPts(stream));
            stream.seek(4 * FRAME + FRAME / 2, SeekMode.KEYFRAME);
            assertEquals(4 * FRAME, nextPts(stream));
            stream.seek(1_000 * FRAME, SeekMode.EXACT);
            assertSame(VideoStreamSource.Read.END, stream.readFrame());
        }
    }

    @Test
    void aClipWithNoDeclaredRateCannotBeSeeked() {
        // No rate means no presentation times, so there is no timeline for a target to name. The
        // stream still rewinds; the two capabilities are separate on purpose.
        SyntheticSpec spec = SyntheticSpec.of(16, 16).withRate(RATE, 1).withFrameCount(3);
        Path file = directory.resolve("rateless.y4m");
        try (VideoStreamSource written = SyntheticVideoDecoder.open(spec)) {
            Y4mWriter.write(file, written, 100);
        }
        stripRateTag(file);
        try (VideoStreamSource stream = new Y4mDecoder().openStream(file)) {
            assertEquals(0, stream.frameRateNum(), "the fixture needs a header with no F tag");
            assertFalse(stream.canSeek());
            assertTrue(stream.canReset(), "rewinding is a different capability");
            assertThrows(UnsupportedOperationException.class,
                    () -> stream.seek(0, SeekMode.EXACT));
        }
    }

    @Test
    void seekingDoesNotDisturbAPictureAlreadyLentOut() {
        try (VideoStreamSource stream = clip(20)) {
            assertSame(VideoStreamSource.Read.FRAME, stream.readFrame());
            VideoFrame held = stream.frame();
            assertEquals(0, held.ptsMicros());

            stream.seek(6 * FRAME, SeekMode.EXACT);
            assertEquals(0, held.ptsMicros(), "a borrowed picture is not invalidated by a seek");
            held.release(); // and exactly once, whether or not a seek happened in between

            assertEquals(6 * FRAME, nextPts(stream));
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** A YUV4MPEG2 file of {@code pictures} pictures at 25 per second, written for this test. */
    private VideoStreamSource clip(int pictures) {
        SyntheticSpec spec = SyntheticSpec.of(24, 16).withRate(RATE, 1).withFrameCount(pictures);
        Path file = directory.resolve("clip-" + pictures + ".y4m");
        try (VideoStreamSource written = SyntheticVideoDecoder.open(spec)) {
            assertEquals(pictures, Y4mWriter.write(file, written, pictures + 8));
        }
        VideoStreamSource stream = new Y4mDecoder().openStream(file);
        assertTrue(stream.canSeek(), "a file-backed Y4M with a declared rate seeks");
        return stream;
    }

    private static long nextPts(VideoStreamSource stream) {
        assertSame(VideoStreamSource.Read.FRAME, stream.readFrame());
        VideoFrame frame = stream.frame();
        long pts = frame.ptsMicros();
        frame.release();
        return pts;
    }

    /** Rewrites the header without its {@code F} tag, keeping every byte after it. */
    private static void stripRateTag(Path file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file);
            int newline = 0;
            while (bytes[newline] != '\n') {
                newline++;
            }
            String header = new String(bytes, 0, newline, java.nio.charset.StandardCharsets.US_ASCII);
            StringBuilder kept = new StringBuilder();
            for (String token : header.split(" ")) {
                if (!token.startsWith("F")) {
                    kept.append(kept.length() == 0 ? "" : " ").append(token);
                }
            }
            byte[] head = (kept + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] out = new byte[head.length + bytes.length - newline - 1];
            System.arraycopy(head, 0, out, 0, head.length);
            System.arraycopy(bytes, newline + 1, out, head.length, bytes.length - newline - 1);
            java.nio.file.Files.write(file, out);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}

package limn.video.ffmpeg;

import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Container ticks to microseconds: the conversion libavformat exists to make possible and the one
 * it introduces.
 *
 * <p>A packet's timestamp is in its stream's own time base, a rational the container chooses; an
 * MP4 written here comes back on a 15360-per-second clock, not on 30. {@code VideoFrame} publishes
 * microseconds, and phase 1 chose them because a 90 kHz container tick is 11.11 of them. The
 * rescale is integer arithmetic for that reason: the same conversion through {@code double} drifts,
 * and drift is exactly the failure {@code VideoClock}'s rational rate exists to avoid.
 */
class TimestampTest {

    @TempDir
    Path directory;

    @Test
    void anExactRateLandsOnTheExactMicrosecond() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = write(directory.resolve("r30.mp4"), 60, 30, 1);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            VideoStreamSource video = media.video();
            assertEquals(30, video.frameRateNum());
            assertEquals(1, video.frameRateDen());

            List<Long> times = collect(video);
            assertEquals(60, times.size());
            for (int i = 0; i < times.size(); i++) {
                long expected = Math.round(i * 1_000_000.0 / 30.0);
                assertTrue(Math.abs(times.get(i) - expected) <= 1,
                        "picture " + i + " should be near " + expected + "us, was " + times.get(i));
            }
        }
    }

    /**
     * 30000/1001 is the rate the rational arithmetic is for. Held as a fraction of a second it is
     * 33366.666… microseconds a picture, and a converter that rounded per picture and accumulated
     * would be a whole picture out after a few thousand of them. Here the drift is measured
     * against the exact rational over the whole clip rather than picture to picture, because that
     * is where an accumulating error shows and a per-picture rounding does not.
     */
    @Test
    void aNonIntegerRateDoesNotDriftAcrossTheClip() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = write(directory.resolve("ntsc.mp4"), 90, 30000, 1001);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            VideoStreamSource video = media.video();
            assertEquals(30000, video.frameRateNum());
            assertEquals(1001, video.frameRateDen());

            List<Long> times = collect(video);
            assertEquals(90, times.size());
            for (int i = 0; i < times.size(); i++) {
                // The exact value, as a rational: i * 1001 * 1e6 / 30000.
                long expected = (i * 1001L * 1_000_000L) / 30000L;
                assertTrue(Math.abs(times.get(i) - expected) <= 1,
                        "picture " + i + " should be near " + expected + "us, was " + times.get(i));
            }
            long last = times.get(times.size() - 1);
            long exactLast = (89L * 1001L * 1_000_000L) / 30000L;
            assertTrue(Math.abs(last - exactLast) <= 1,
                    "no accumulated drift by the last picture: " + last + " vs " + exactLast);
        }
    }

    @Test
    void timesAreNonDecreasingAndStartAtTheStartOfTheStream() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = write(directory.resolve("order.mp4"), 40, 30, 1);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            List<Long> times = collect(media.video());
            assertEquals(0L, times.get(0),
                    "the container's own start time is subtracted, so the first picture is at 0");
            for (int i = 1; i < times.size(); i++) {
                assertTrue(times.get(i) >= times.get(i - 1),
                        "not non-decreasing at " + i + ": " + times);
            }
        }
    }

    /**
     * Both tracks are put on ONE timeline by subtracting the container's start time from each,
     * rather than each stream's own, which would zero them independently and hide a real offset
     * between picture and sound. That offset is the entire quantity {@code AudioMasterClock}
     * judges, so a decoder that removed it would make every clip look perfectly in sync while
     * being out by however much the container said.
     */
    @Test
    void bothTracksAreOnTheSameTimeline() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 30, 2);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            List<Long> times = collect(media.video());
            assertEquals(0L, times.get(0));
            // The soundtrack was written from sample 0 of the same timeline, so a track that
            // yields about a second of frames alongside a second of pictures is on it.
            short[] out = new short[1024 * 2];
            long frames = 0;
            for (int attempt = 0; attempt < 10_000; attempt++) {
                int read = media.audio().readFrames(out, 1024);
                if (read == 0) {
                    break;
                }
                frames += read;
            }
            double soundSeconds = frames / 44_100.0;
            double pictureSeconds = (times.get(times.size() - 1) + 1_000_000.0 / 30) / 1_000_000.0;
            assertTrue(Math.abs(soundSeconds - pictureSeconds) < 0.15,
                    "the two tracks cover the same span: " + soundSeconds + "s of sound against "
                            + pictureSeconds + "s of pictures");
        }
    }

    private static Path write(Path path, int frames, int rateNum, int rateDen) throws IOException {
        Files.deleteIfExists(path);
        FfmpegMedia.writeClip(path, FfmpegMedia.ClipCodec.MJPEG, 160, 120, frames,
                rateNum, rateDen, 0, 44_100);
        return path;
    }

    private static List<Long> collect(VideoStreamSource video) {
        List<Long> times = new ArrayList<>();
        while (RoundTripTest.readNext(video) == VideoStreamSource.Read.FRAME) {
            times.add(video.frame().ptsMicros());
            video.frame().release();
        }
        return times;
    }
}

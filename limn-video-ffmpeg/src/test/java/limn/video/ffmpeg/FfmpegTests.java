package limn.video.ffmpeg;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What every test here needs: a reason to skip, and a clip to read.
 *
 * <p><b>Skipping is the normal outcome, not a failure.</b> The native library is not committed and
 * Gradle does not build it, so it is absent on every machine that has not run
 * {@code scripts/build-ffmpeg.sh}, which includes a fresh clone and will include the CI machines
 * until phase 6b gives them a build step. These tests therefore skip cleanly there, exactly as the
 * GL-backed tests in the backend's suite skip where no context can be created, and the ones that
 * do not need the library at all keep running.
 */
final class FfmpegTests {

    private FfmpegTests() {
    }

    /** Skips the calling test unless the native library loaded. */
    static void requireLibrary() {
        Assumptions.assumeTrue(FfmpegLibrary.isAvailable(),
                () -> "no FFmpeg native for " + FfmpegLibrary.platform() + ": "
                        + FfmpegLibrary.failure());
    }

    /**
     * Skips the calling test unless this build can also write a clip (that is, unless it is the
     * {@code full} profile). The shipped {@code player} profile holds no encoder, so a test that
     * needs a file to read cannot run against it, and saying so is better than committing one.
     */
    static void requireWriter() {
        requireLibrary();
        Assumptions.assumeTrue(FfmpegMedia.canWriteClip(),
                "this FFmpeg build has no encoder (build with --profile full)");
    }

    /** A short clip with a soundtrack: 32 pictures of colour bars at 30/1, stereo 44100. */
    static Path clip(Path directory) throws IOException {
        return clip(directory, 160, 120, 32, 2);
    }

    static Path clip(Path directory, int width, int height, int frames, int channels)
            throws IOException {
        return clip(directory, FfmpegMedia.ClipCodec.MJPEG, width, height, frames, channels);
    }

    static Path clip(Path directory, FfmpegMedia.ClipCodec codec, int width, int height,
                     int frames, int channels) throws IOException {
        Path file = Files.createTempFile(directory, "limn-", ".mp4");
        Files.deleteIfExists(file);
        FfmpegMedia.writeClip(file, codec, width, height, frames, 30, 1, channels, 44_100);
        return file;
    }

    /**
     * The opt-in directory of real media, or a skip.
     *
     * @param why what the caller would do with them, for the message a developer reads when the
     *            property is not set, which is most of the time, since no media is committed here
     */
    static List<Path> realClips(String why) throws IOException {
        String property = System.getProperty("limn.video.test.clips");
        Assumptions.assumeTrue(property != null && !property.isBlank(),
                "set -Dlimn.video.test.clips to a directory of media " + why);
        Path directory = Path.of(property);
        Assumptions.assumeTrue(Files.isDirectory(directory), property + " is not a directory");
        try (var entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /**
     * A clip with a soundtrack per entry of {@code audio}, each carrying a tone and a language of
     * its own, which is what makes "am I hearing the track I asked for" a question with an answer.
     */
    static Path clip(Path directory, FfmpegMedia.ClipCodec codec, int width, int height,
                     int frames, List<FfmpegMedia.ClipAudioTrack> audio) throws IOException {
        Path file = Files.createTempFile(directory, "limn-", ".mp4");
        Files.deleteIfExists(file);
        FfmpegMedia.writeClip(file, codec, width, height, frames, 30, 1, audio, 44_100);
        return file;
    }

    /**
     * A clip with subtitle tracks as well as soundtracks: the only way a subtitle track exists to
     * be read, since no media is committed.
     */
    static Path clip(Path directory, FfmpegMedia.ClipCodec codec, int width, int height,
                     int frames, List<FfmpegMedia.ClipAudioTrack> audio,
                     List<String> subtitles) throws IOException {
        Path file = Files.createTempFile(directory, "limn-", ".mp4");
        Files.deleteIfExists(file);
        FfmpegMedia.writeClip(file, codec, width, height, frames, 30, 1, audio, 44_100, subtitles);
        return file;
    }

    /**
     * What the clip writer's cue {@code cue} of track {@code track} says. Stated here rather than
     * computed the writer's way, so that a test agreeing with the writer is not the same thing as
     * both of them being wrong.
     */
    static String cueTextOf(int track, int cue) {
        return cue == 0 ? "T" + track + " C0\nsecond line" : "T" + track + " C" + cue;
    }

    /**
     * When cue {@code index} of a written clip begins, in microseconds, for a clip at 30/1.
     *
     * <p><b>The cues are timed in whole milliseconds</b>, which is the unit a tx3g sample is timed
     * in, so ten pictures at 30 per second is 333 ms and not 333⅓, and the boundaries drift
     * behind exact thirds of a second by a millisecond per cue. A test that multiplied a nominal
     * cue length instead lands in the wrong cue about six cues in, which reads as a subtitle
     * off-by-one rather than as its own arithmetic.
     */
    static long cueStartMicros(int index) {
        return index * 10L * 1000L / 30L * 1000L;
    }

    /** A time comfortably inside cue {@code index}, for asking "what is on screen now". */
    static long insideCue(int index) {
        return (cueStartMicros(index) + cueStartMicros(index + 1)) / 2;
    }

    /**
     * Reads the video forward, releasing every picture, which is what makes the container
     * demultiplex, and therefore the only thing that brings subtitle packets in. Cues follow the
     * pictures and are pulled by nothing else.
     *
     * @return how many pictures were read before the end
     */
    static int runVideo(limn.video.VideoStreamSource video, int limit) {
        int seen = 0;
        for (int i = 0; i < limit; i++) {
            limn.video.VideoStreamSource.Read read = video.readFrame();
            if (read == limn.video.VideoStreamSource.Read.END) {
                break;
            }
            if (read == limn.video.VideoStreamSource.Read.FRAME) {
                video.frame().release();
                seen++;
            }
        }
        return seen;
    }

    /**
     * The frequency the clip writer gives track {@code track}, channel {@code channel}: 440 Hz for
     * the first channel of the first track, an octave up per channel and an odd multiple per track.
     * Stated here as well as in the writer because a test that computed it the writer's way would
     * agree with the writer even when both were wrong.
     */
    static double toneOf(int track, int channel) {
        return 440.0 * (2 * track + 1) * (1 << (channel % 4));
    }

    /**
     * How much of {@code frames} is at {@code frequency}: a Goertzel filter over one channel of an
     * interleaved buffer, normalised by the number of samples so that two runs of different lengths
     * are comparable.
     *
     * <p>It is here rather than a peak search because what a test asks is "is THIS tone present",
     * and a decoded lossy tone has skirts either side of it that a peak search reports as their
     * own answer.
     */
    static double energyAt(short[] frames, int count, int channels, int channel, int sampleRate,
                           double frequency) {
        double w = 2.0 * Math.PI * frequency / sampleRate;
        double coefficient = 2.0 * Math.cos(w);
        double s1 = 0;
        double s2 = 0;
        for (int i = 0; i < count; i++) {
            double s = frames[i * channels + channel] / 32768.0 + coefficient * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        double power = s1 * s1 + s2 * s2 - coefficient * s1 * s2;
        return count == 0 ? 0 : Math.sqrt(Math.max(0, power)) / count;
    }
}

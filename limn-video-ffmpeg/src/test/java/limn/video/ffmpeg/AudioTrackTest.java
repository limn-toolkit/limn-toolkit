package limn.video.ffmpeg;

import limn.sound.AudioStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The soundtrack, in the shape {@code AudioStreamSource} wants rather than the shape AAC decodes
 * to (planar float in, interleaved signed 16-bit out), and what happens to a track with more
 * channels than the audio engine will admit.
 */
class AudioTrackTest {

    @TempDir
    Path directory;

    @Test
    void aStereoTrackArrivesInterleavedAtTheRateTheContainerDeclared() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 30, 2);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            assertTrue(media.hasAudio());
            AudioStreamSource audio = media.audio();
            assertNotNull(audio);

            // Whatever the file says. Nothing is resampled: there is no resampler in this build
            // and none is needed, because the engine takes the rate the source reports.
            assertEquals(44_100, audio.sampleRate());
            assertEquals(2, audio.channels());
            assertEquals(2, media.audioSourceChannels());

            short[] out = new short[2048 * 2];
            int frames = audio.readFrames(out, 2048);
            assertTrue(frames > 0, "the track has samples in it");
            assertTrue(frames <= 2048);

            // Interleaved means frame f's channels are adjacent: out[2f] and out[2f+1]. The two
            // channels were written as different tones, so a planar buffer handed over unchanged
            // (the mistake this conversion exists to prevent) would put a whole block of one
            // channel where the interleaving should alternate, and the two halves would differ.
            long leftEnergy = 0;
            long rightEnergy = 0;
            for (int f = 0; f < frames; f++) {
                leftEnergy += Math.abs(out[f * 2]);
                rightEnergy += Math.abs(out[f * 2 + 1]);
            }
            assertTrue(leftEnergy > 0, "the left channel is not silent");
            assertTrue(rightEnergy > 0, "the right channel is not silent");
            assertTrue(leftEnergy != rightEnergy,
                    "the two channels carry different tones, so interleaved samples differ");
        }
    }

    @Test
    void theWholeTrackCanBeDrainedAndThenReportsItsEnd() throws IOException {
        FfmpegTests.requireWriter();
        // One second of pictures, so about one second of sound.
        Path clip = FfmpegTests.clip(directory, 160, 120, 30, 2);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            AudioStreamSource audio = media.audio();
            short[] out = new short[1024 * 2];
            long total = 0;
            for (int attempt = 0; attempt < 10_000; attempt++) {
                int frames = audio.readFrames(out, 1024);
                if (frames == 0) {
                    break;
                }
                total += frames;
            }
            // AAC codes in blocks and pads the last one, so this is "about a second" rather than
            // exactly 44100 frames. What matters is that it is the length of the clip and not a
            // buffer's worth.
            assertTrue(total > 40_000 && total < 50_000,
                    "roughly a second of sound at 44100, got " + total);
            assertEquals(0, audio.readFrames(out, 1024), "asking again keeps reporting the end");
        }
    }

    @Test
    void aTrackWithMoreChannelsThanTheEngineAdmitsIsFoldedToStereo() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MJPEG, 160, 120, 30, 6);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            AudioStreamSource audio = media.audio();
            assertNotNull(audio);

            // OpenAlAudio refuses any channel count but 1 and 2 at admission. The three possible
            // answers were to refuse the file, to play it silent, or to fold it; folding is the
            // only one that plays the film, so channels() reports what is delivered and
            // audioSourceChannels() reports what the file holds.
            assertEquals(6, media.audioSourceChannels(), "the file really is 5.1");
            assertEquals(2, audio.channels(), "and what arrives is stereo");
            assertEquals(44_100, audio.sampleRate());

            short[] out = new short[1024 * 2];
            int frames = audio.readFrames(out, 1024);
            assertTrue(frames > 0);

            long left = 0;
            long right = 0;
            int clipped = 0;
            for (int f = 0; f < frames; f++) {
                left += Math.abs(out[f * 2]);
                right += Math.abs(out[f * 2 + 1]);
                if (out[f * 2] == Short.MAX_VALUE || out[f * 2] == Short.MIN_VALUE) {
                    clipped++;
                }
            }
            assertTrue(left > 0 && right > 0, "the fold is not silent");
            assertTrue(left != right,
                    "front left and front right carry different tones, so the fold differs by side");
            // The coefficients are normalised where they would sum above one, which is what stops
            // six channels at a quarter scale each from running into the end of the range.
            assertEquals(0, clipped, "a normalised downmix does not clip");
        }
    }

    @Test
    void aContainerWithNoSoundtrackSaysSoRatherThanInventingOne() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 8, 0);
        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            assertNull(media.audio());
            assertTrue(!media.hasAudio());
            assertEquals(0, media.audioSourceChannels());
        }
    }

    @Test
    void theDecoderPathTakesNoSoundtrackAtAll() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 8, 2);
        // Videos.open goes through VideoDecoder, whose signature carries video and only video, so
        // the audio track is never claimed and its packets are discarded as they are met.
        try (var video = new FfmpegVideoDecoder().openStream(clip)) {
            assertEquals(160, video.width());
        }
    }

    @Test
    void closingTheTrackLeavesThePicturesRunning() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 16, 2);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            // What the audio engine does when a stream ends: it closes the source it was given.
            // That must not close the container, or a soundtrack finishing would pull the decoder
            // out from under the pictures.
            media.audio().close();
            media.audio().close(); // idempotent

            assertTrue(media.isOpen());
            int decoded = 0;
            while (RoundTripTest.readNext(media.video()) == limn.video.VideoStreamSource.Read.FRAME) {
                media.video().frame().release();
                decoded++;
            }
            assertEquals(16, decoded, "every picture still arrives after the track was closed");
        }
    }
}

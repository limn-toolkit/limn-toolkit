package limn.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The excerpt the video screen plays when the payload cannot encode is copied into the jar by the
 * build, with its licence beside it. A build that stopped copying it would leave the MP4 entry
 * opening on nothing again, for every copy of this program run from its coordinate -- and would
 * fail the gallery's transport entry, which films the same clip.
 */
class BundledSampleClipTest {

    @Test
    void theExcerptAndItsLicenceAreOnTheClasspath() throws IOException {
        try (InputStream clip = BundledClip.class.getResourceAsStream(BundledClip.RESOURCE)) {
            assertNotNull(clip, BundledClip.RESOURCE + " is not on the classpath");
            byte[] head = clip.readNBytes(12);
            // An ISO base media file opens with a box whose type is "ftyp".
            assertEquals("ftyp", new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
            long size = 12 + clip.transferTo(java.io.OutputStream.nullOutputStream());
            assertTrue(size > 1_900_000 && size < 2_200_000, "the excerpt is about 2 MB: " + size);
        }
        try (InputStream licence = BundledClip.class.getResourceAsStream(
                "/limn/demo/media/LICENSE-CC-BY-3.0.txt")) {
            assertNotNull(licence, "the CC BY text travels beside the excerpt");
        }
    }

    @Test
    void theShippedDecoderOpensTheExcerptAndReadsAPicture() {
        // What the MP4 entry does on a machine running the payload that ships: no encoder, so
        // the bundled excerpt is unpacked and opened. Skips where the native cannot load at
        // all (no payload for this platform), which is the one case the entry still reports.
        assumeTrue(limn.video.ffmpeg.FfmpegLibrary.isAvailable(), "needs the FFmpeg native");
        try (limn.video.ffmpeg.FfmpegMedia media =
                     limn.video.ffmpeg.FfmpegMedia.open(BundledClip.path())) {
            limn.video.VideoStreamSource video = media.video();
            assertEquals(640, video.width());
            // 359 and not 360: the original the excerpt is cut from is a row short of its own
            // name, and nothing was encoded again, so the excerpt is too.
            assertEquals(359, video.height());
            limn.video.VideoStreamSource.Read read = video.readFrame();
            for (int i = 0; i < 100 && read == limn.video.VideoStreamSource.Read.PENDING; i++) {
                read = video.readFrame();
            }
            assertEquals(limn.video.VideoStreamSource.Read.FRAME, read, "the first picture decodes");
            // Picture and sound: a sample that validates one and not the other showed a player
            // with a soundtrack switch and nothing to switch.
            assertEquals(1, media.audioTracks().size(), "the excerpt carries its soundtrack");
        }
    }
}

package limn.video.decode;

import limn.video.VideoDecoder;
import limn.video.VideoStreamSource;
import limn.video.Videos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both decoders reached the way an application reaches them: installed into the facade and opened by
 * path, including the one whose path names a description rather than a file.
 *
 * <p>The registry is process-wide, so this class puts it back exactly as it found it.
 */
class DecoderRegistrationTest {

    @TempDir
    Path directory;

    private List<VideoDecoder> saved;

    @BeforeEach
    void takeTheRegistry() {
        saved = Videos.installedDecoders();
        Videos.uninstallAllDecoders();
    }

    @AfterEach
    void giveItBack() {
        Videos.uninstallAllDecoders();
        saved.forEach(Videos::installDecoder);
    }

    @Test
    void aSyntheticStreamOpensWithNoFileBehindIt() {
        Videos.installDecoder(new SyntheticVideoDecoder());
        SyntheticSpec spec = SyntheticSpec.of(64, 48)
                .withPattern(SyntheticPattern.COUNTER)
                .withFrameCount(2);

        assertTrue(Videos.canOpen(spec.path()));
        assertFalse(Files.exists(spec.path()), "nothing was created, and nothing needs to be");
        try (VideoStreamSource source = Videos.open(spec.path())) {
            assertEquals(64, source.width());
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            source.frame().release();
        }
    }

    @Test
    void aMalformedSpecIsClaimedAndThenRefusedWithAReason() {
        // The name ends correctly, so this decoder is the one that answers for it, which is the
        // whole point of claiming on the extension: the message names the key that is wrong instead
        // of being the facade's generic "nothing accepts this".
        Videos.installDecoder(new SyntheticVideoDecoder());
        Path path = Path.of("size=64,pattern=counter" + SyntheticSpec.EXTENSION);
        assertTrue(Videos.canOpen(path));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Videos.open(path))
                .getMessage().contains("size"));
    }

    @Test
    void aY4mFileOpensThroughTheSameFacade() throws Exception {
        Videos.installDecoder(new Y4mDecoder());
        Videos.installDecoder(new SyntheticVideoDecoder());
        Path file = directory.resolve("clip.y4m");
        try (VideoStreamSource source = SyntheticVideoDecoder.open(
                SyntheticSpec.of(24, 16).withFrameCount(3))) {
            Y4mWriter.write(file, source, 3);
        }

        assertTrue(Videos.canOpen(file));
        try (VideoStreamSource source = Videos.open(file)) {
            assertEquals(24, source.width());
            assertEquals(VideoStreamSource.Read.FRAME, source.readFrame());
            source.frame().release();
        }
    }

    @Test
    void theProbeOrderIsTheInstallOrderAndTheRefusalNamesEveryone() {
        Videos.installDecoder(new Y4mDecoder());
        Videos.installDecoder(new SyntheticVideoDecoder());
        assertEquals(List.of("y4m", "synthetic"),
                Videos.installedDecoders().stream().map(VideoDecoder::name).toList());

        Path unclaimed = directory.resolve("movie.mp4");
        assertFalse(Videos.canOpen(unclaimed));
        String message = assertThrows(UnsupportedOperationException.class,
                () -> Videos.open(unclaimed)).getMessage();
        assertTrue(message.contains("y4m") && message.contains("synthetic"), message);
    }

    /**
     * The message has to name the call that fixes it. It used to say "start a Backend", which is
     * advice a reader can follow to the letter and fail again identically: a backend installs an
     * image decoder and an audio engine and no video decoder at all. A reader holding only the
     * Javadoc jar has nothing else to check it against, which is why the wording is asserted here
     * rather than left to review.
     */
    @Test
    void withNothingInstalledOpeningNamesTheCallThatFixesIt() {
        assertFalse(Videos.isDecoderInstalled());
        String message = assertThrows(IllegalStateException.class,
                () -> Videos.open(directory.resolve("anything.y4m"))).getMessage();

        assertTrue(message.contains("installDecoder"),
                "the message must name the call that fixes it: " + message);
        assertFalse(message.contains("start a Backend"),
                "starting a backend installs no video decoder: " + message);
    }
}

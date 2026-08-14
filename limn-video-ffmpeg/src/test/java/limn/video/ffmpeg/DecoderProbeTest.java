package limn.video.ffmpeg;

import limn.video.VideoDecoder;
import limn.video.VideoStreamSource;
import limn.video.Videos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code supports} (which must be cheap, must never throw, and must not parse a container), and
 * what happens when a file it claimed turns out to be rubbish.
 */
class DecoderProbeTest {

    @TempDir
    Path directory;

    private final FfmpegVideoDecoder decoder = new FfmpegVideoDecoder();

    @AfterEach
    void uninstall() {
        Videos.uninstallDecoder(decoder);
    }

    @Test
    void theNameIsStableAndUsable() {
        assertEquals("ffmpeg", decoder.name());
        assertFalse(decoder.name().isBlank());
    }

    /**
     * Every one of these is a real thing a file chooser or a configuration file can produce, and
     * every one of them must be {@code false} rather than an exception: one decoder throwing here
     * makes the probe unusable for every decoder installed behind it.
     */
    @Test
    void nothingAtAllMakesTheProbeThrow() throws IOException {
        Path missing = directory.resolve("nope.mp4");
        Path emptyFile = Files.createFile(directory.resolve("empty.mp4"));
        Path tiny = Files.write(directory.resolve("tiny.mp4"), new byte[] {1, 2, 3});
        Path folder = Files.createDirectory(directory.resolve("folder.mp4"));
        Path text = Files.writeString(directory.resolve("notes.mp4"),
                "this is not a container", StandardCharsets.UTF_8);
        Path noExtension = Files.write(directory.resolve("data"), new byte[64]);
        Path wrongExtension = Files.write(directory.resolve("clip.y4m"), new byte[64]);

        for (Path candidate : new Path[] {missing, emptyFile, tiny, folder, text, noExtension,
                wrongExtension, Path.of(""), Path.of("/")}) {
            assertDoesNotThrow(() -> decoder.supports(candidate), "supports(" + candidate + ")");
            assertFalse(decoder.supports(candidate), "supports(" + candidate + ")");
        }
        assertDoesNotThrow(() -> decoder.supports(null));
        assertFalse(decoder.supports(null));
    }

    /**
     * The extension alone must not be enough. A file named {@code .mp4} that is not one has to
     * fall through to whatever else is installed, because {@code openStream} throwing is final and
     * no later decoder is tried, so claiming it would turn "some other decoder reads this" into
     * "nothing reads this".
     */
    @Test
    void anExtensionWithoutTheBytesIsNotClaimed() throws IOException {
        Path impostor = Files.writeString(directory.resolve("impostor.mp4"),
                "RIFF....WAVEfmt this is a wave file with the wrong name");
        assertFalse(decoder.supports(impostor));
    }

    /**
     * The probe reads twelve bytes and stops. This file has a valid ISO base media header and
     * nothing behind it that can be demultiplexed, so a probe that looked no further claims it,
     * and a probe that had run {@code avformat_find_stream_info} would have declined it.
     *
     * <p>That is the assertion: {@code supports} says yes here. It is not a timing measurement,
     * which would be flaky; it is the observable difference between reading a header and reading
     * the file.
     */
    @Test
    void theProbeDoesNotReadFarEnoughToKnowWhetherItCanDecode() throws IOException {
        FfmpegTests.requireLibrary();
        Path headerOnly = writeHeaderOnly(directory.resolve("header.mp4"));
        assertTrue(decoder.supports(headerOnly),
                "a valid ISO base media header is claimed on the strength of the header");
    }

    /**
     * And the corollary: opening it fails, and it fails as a Java exception on the calling thread.
     * A native decoder that dereferenced what a malformed header claimed would take the whole
     * virtual machine with it, and {@code CrashPhase.DECODE} contains an exception, not a signal.
     */
    @Test
    void aMalformedFileArrivesAsAnExceptionAndNotAsACrash() {
        // Only the library, not the writer: a header with nothing behind it can be built by hand,
        // so this runs against the shipped decode-only build too, which is the build where a
        // malformed file most needs to reach Java as an exception.
        FfmpegTests.requireLibrary();
        Path headerOnly = assertDoesNotThrow(() -> writeHeaderOnly(directory.resolve("header.mp4")));
        FfmpegException failure = assertThrows(FfmpegException.class,
                () -> decoder.openStream(headerOnly));
        assertNotNull(failure.getMessage());
    }

    @Test
    void aDamagedRealContainerArrivesAsAnExceptionAndNotAsACrash() throws IOException {
        FfmpegTests.requireWriter();
        // Truncated in the middle of a real container: the header parses, the index does not.
        Path clip = FfmpegTests.clip(directory, 160, 120, 24, 0);
        byte[] whole = Files.readAllBytes(clip);
        Path truncated = Files.write(directory.resolve("cut.mp4"),
                java.util.Arrays.copyOf(whole, whole.length / 3));
        assertTrue(decoder.supports(truncated), "it still looks like a container");
        assertThrows(FfmpegException.class, () -> decoder.openStream(truncated));

        // Real header, real length, random contents.
        byte[] corrupt = whole.clone();
        java.util.Random random = new java.util.Random(7);
        for (int i = 32; i < corrupt.length; i++) {
            corrupt[i] = (byte) random.nextInt();
        }
        Path scrambled = Files.write(directory.resolve("scrambled.mp4"), corrupt);
        assertDoesNotThrow(() -> decoder.supports(scrambled));
        // It either refuses to open or opens and fails while decoding. Both are exceptions on the
        // calling thread, and which one it is depends on how much of the index survived, so the
        // assertion is that the process is still here to make it.
        try (var ignored = openOrNull(scrambled)) {
            assertTrue(true);
        } catch (FfmpegException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    void aClaimedFileGoesThroughTheFacadeInInstallOrder() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 8, 0);
        Videos.installDecoder(decoder);
        assertTrue(Videos.canOpen(clip));
        try (VideoStreamSource source = Videos.open(clip)) {
            assertEquals(160, source.width());
        }
    }

    @Test
    void anAbsentNativeMakesTheDecoderDeclineRatherThanFail() throws IOException {
        // The state every machine that has not run the build script is in. It cannot be produced
        // here when the library HAS loaded, so what is asserted is the invariant that holds in
        // both states: supports() agrees with availability, and neither throws.
        Path clip = Files.write(directory.resolve("x.mp4"), isoHeader());
        boolean available = FfmpegVideoDecoder.isAvailable();
        assertEquals(available, decoder.supports(clip));
        if (!available) {
            assertNotNull(FfmpegVideoDecoder.unavailableReason(),
                    "an unavailable decoder must be able to say why");
        }
    }

    private VideoStreamSource openOrNull(Path file) {
        return decoder.openStream(file);
    }

    private static Path writeHeaderOnly(Path path) throws IOException {
        return Files.write(path, isoHeader());
    }

    /** A minimal, well-formed {@code ftyp} box and nothing else behind it. */
    private static byte[] isoHeader() {
        byte[] header = new byte[24];
        header[3] = 24;
        System.arraycopy("ftypisom".getBytes(StandardCharsets.US_ASCII), 0, header, 4, 8);
        System.arraycopy("isomiso2mp41".getBytes(StandardCharsets.US_ASCII), 0, header, 12, 12);
        return header;
    }
}

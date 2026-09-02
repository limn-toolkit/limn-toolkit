package limn.video.ffmpeg;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What this build can actually open, and the three different kinds of evidence there are for it.
 *
 * <p><b>Why three.</b> Of the codecs here, only MPEG-4 Part 2 and MJPEG can also be
 * <em>encoded</em>: FFmpeg's H.264 encoder is x264 and is GPL, and it has no native HEVC, VP9 or
 * AV1 encoder at all. So there is no way to write an HEVC clip in this repository and read it
 * back, and no media file may be committed. The three tiers below are what is left, in decreasing
 * strength and increasing coverage, and each says plainly what it does not cover:
 *
 * <ol>
 *   <li><b>A real round trip</b>: {@code RoundTripTest} writes an MPEG-4 clip and reads it back,
 *       which exercises every line of this repository's decode path: demux, packet to picture, the
 *       planar handoff, the pool, the release discipline. That path is byte-identical C for every
 *       codec, so what a new codec adds that this does not already prove is libavcodec's own
 *       decoder, which is FFmpeg's to test and not this project's.
 *   <li><b>Linkage, read out of the library</b>: below. A configure flag is a claim; a symbol in
 *       the linked library is a fact. This is what catches a decoder that was dropped in an edit,
 *       or that configure silently refused because a dependency was switched off, and which would
 *       otherwise be discovered by a user opening a file.
 *   <li><b>Real files, when the machine has some</b>: {@link #everyClipInTheOptInDirectoryDecodes}.
 *       Point {@code -Dlimn.video.test.clips} at a directory and every file in it is opened and
 *       decoded; skipped when it is not set, which is every machine that has not been given clips.
 *       This is the only tier that exercises HEVC and VP9 end to end, and it is opt-in because the
 *       alternative is committing media.
 * </ol>
 *
 * <p>The tier that was weighed and rejected: hand-authoring a conformant bitstream for each codec.
 * A VP9 keyframe needs its bool coder and an HEVC one needs CABAC plus a parameter-set triple, both
 * are days of work per codec, and (the part that decides it) the only reference available to
 * check the result against is the decoder being tested, which makes the exercise circular.
 */
class CodecBreadthTest {

    /**
     * What this module advertises, in {@code FfmpegVideoDecoder}'s own documentation and in the
     * demo's file filter. Every entry is asserted against the linked library below, so the two
     * cannot drift: adding a name here without the configure flag fails, and dropping the flag
     * while the name stays fails too.
     */
    private static final List<String> VIDEO_DECODERS = List.of("h264", "hevc", "vp9", "vp8");

    private static final List<String> AUDIO_DECODERS = List.of("aac", "opus", "vorbis");

    private static final List<String> DEMUXERS = List.of("mov", "matroska");

    /**
     * One text subtitle decoder per container this build reads: {@code mov_text} is what an MP4
     * carries, and the other three are what a Matroska or WebM carries. All four together were
     * measured at 896 bytes, so there is no payload argument for dropping any of them, and
     * dropping one silently would leave a track that lists itself as decodable and then is not.
     *
     * <p>The names are libavcodec's own spelling, which for tx3g is {@code mov_text} even though
     * the configure flag that turns it on is {@code --enable-decoder=movtext}. That asymmetry is
     * exactly why this reads the linked library instead of the build script.
     */
    private static final List<String> SUBTITLE_DECODERS =
            List.of("mov_text", "subrip", "ass", "webvtt");

    /**
     * Codecs that must stay out, each for a stated reason. AV1's is the interesting one and it is
     * not licensing: FFmpeg's {@code av1} decoder refuses to decode without a hardware accelerator,
     * and {@code --disable-everything} leaves none, so enabling it would produce a build that
     * advertises AV1 and fails on the first file. Software AV1 needs libdav1d.
     *
     * <p>The three bitmap subtitle decoders are here for a different reason and it is not payload:
     * this SPI carries text cues and has no vocabulary for a paletted rectangle, so a build that
     * grew one would open a track it could hand nobody anything to draw. The refusal is by name in
     * {@code FfmpegMedia.selectSubtitles} whether or not the decoder is linked; this keeps the
     * decoder out as well, so the two cannot disagree about what happened.
     */
    private static final List<String> NOT_LINKED = List.of("av1", "libdav1d", "libvpx-vp9",
            "libx264", "libx265", "prores", "theora", "pgssub", "dvdsub", "dvbsub");

    @Test
    void everyAdvertisedDecoderIsInTheLinkedLibrary() {
        FfmpegTests.requireLibrary();
        Set<String> components = components();
        for (String codec : VIDEO_DECODERS) {
            assertTrue(components.contains("decoder:" + codec),
                    codec + " is advertised by this module and is not in the linked library; the "
                            + "configure line in limn-ffmpeg-natives' scripts/build-ffmpeg.sh is what puts it there");
        }
        for (String codec : AUDIO_DECODERS) {
            assertTrue(components.contains("decoder:" + codec),
                    codec + " is what a container's soundtrack is in; without it a file that opens "
                            + "plays silent, which is worse than not opening it");
        }
        for (String codec : SUBTITLE_DECODERS) {
            assertTrue(components.contains("decoder:" + codec),
                    codec + " is missing, so every subtitle track in that format lists itself as "
                            + "decodable and then refuses to open, which is the shape of the Opus "
                            + "failure this tier was written for");
        }
        for (String demuxer : DEMUXERS) {
            assertTrue(components.contains("demuxer:" + demuxer),
                    demuxer + " demuxer is missing, so the containers it reads cannot be opened");
        }
    }

    @Test
    void nothingIsLinkedThatWasDeliberatelyLeftOut() {
        FfmpegTests.requireLibrary();
        Set<String> components = components();
        for (String codec : NOT_LINKED) {
            assertFalse(components.contains("decoder:" + codec),
                    codec + " is linked, and every one of these was left out on purpose; check "
                            + "why it came back before accepting it");
            assertFalse(components.contains("encoder:" + codec),
                    codec + " encoder is linked, which is a licence question as well as a payload "
                            + "one");
        }
    }

    @Test
    void theBuildStaysNarrowerThanItsOwnDefaults() {
        FfmpegTests.requireLibrary();
        long decoders = registered("decoder");
        long demuxers = registered("demuxer");

        // A build that lost --disable-everything still passes every assertion above, because it
        // would contain the codecs this one does and several hundred more. The count is what says
        // the trimming is still happening at all; the bound is generous enough that adding one
        // codec deliberately does not trip it.
        assertTrue(decoders <= 24, "a trimmed build holds a handful of decoders, this one holds "
                + decoders + ": " + sorted(components(), "decoder:"));
        assertTrue(demuxers <= 4, "a trimmed build holds a couple of demuxers, this one holds "
                + demuxers + ": " + sorted(components(), "demuxer:"));
    }

    @Test
    void theContainersTheDecoderClaimsAreTheOnesItCanDemultiplex() throws IOException {
        FfmpegTests.requireLibrary();
        FfmpegVideoDecoder decoder = new FfmpegVideoDecoder();
        Path directory = Files.createTempDirectory("limn-supports-");
        try {
            // The magic number decides, never the extension: a file named .webm holding an MP4 is
            // claimed by neither, because openStream throwing is final and no decoder behind this
            // one would ever be tried.
            assertTrue(decoder.supports(write(directory, "real.mp4", ISO_HEAD)));
            assertTrue(decoder.supports(write(directory, "real.mkv", EBML_HEAD)));
            assertTrue(decoder.supports(write(directory, "real.webm", EBML_HEAD)));
            assertFalse(decoder.supports(write(directory, "liar.webm", ISO_HEAD)));
            assertFalse(decoder.supports(write(directory, "liar.mp4", EBML_HEAD)));
            assertFalse(decoder.supports(write(directory, "picture.y4m", EBML_HEAD)));
            assertFalse(decoder.supports(directory.resolve("absent.mp4")));
        } finally {
            deleteRecursively(directory);
        }
    }

    /**
     * The only end-to-end evidence there is for a codec this repository cannot encode. Point
     * {@code -Dlimn.video.test.clips} at a directory of media and every file in it is opened and
     * decoded; without it there is nothing to run, and that is stated rather than passed silently.
     */
    @Test
    void everyClipInTheOptInDirectoryDecodes() throws IOException {
        FfmpegTests.requireLibrary();
        String property = System.getProperty("limn.video.test.clips");
        assumeTrue(property != null && !property.isBlank(),
                "set -Dlimn.video.test.clips to a directory of media to decode real files here");
        Path directory = Path.of(property);
        assertTrue(Files.isDirectory(directory), property + " is not a directory");

        List<Path> clips;
        try (var entries = Files.list(directory)) {
            clips = entries.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        if (clips.isEmpty()) {
            abort(directory + " holds no files");
        }
        FfmpegVideoDecoder decoder = new FfmpegVideoDecoder();
        int decoded = 0;
        for (Path clip : clips) {
            if (!decoder.supports(clip)) {
                continue;
            }
            try (VideoStreamSource source = decoder.openStream(clip)) {
                assertTrue(source.width() > 0 && source.height() > 0, clip + " has no picture size");
                assertNotNull(source.pixelFormat(), clip + " has no layout");
                assertNotNull(source.color(), clip + " has no colour");
                assertTrue(source.color().isDisplayReferred(),
                        clip + " is not display-referred, and such a file is refused at open, so "
                                + "reaching here means the refusal was not applied");

                VideoFrame frame = readFirst(source, clip);
                assertEquals(source.width(), frame.width(), clip + " first picture width");
                assertEquals(source.height(), frame.height(), clip + " first picture height");
                assertEquals(source.pixelFormat(), frame.format(), clip + " first picture layout");
                // This is the tier that meets a hardware decoder, because H.264 and HEVC are
                // exactly the codecs that have one and exactly the codecs nothing here can encode.
                // Such a picture has no planes at all until it is read back, which is what every
                // consumer without a device does, so the check below does it rather than being
                // written as though a picture were always samples.
                frame.toPlanar();
                assertEquals(VideoFrame.Kind.PLANAR, frame.kind(),
                        clip + " is still a device handle after being read back");
                for (int plane = 0; plane < frame.format().planeCount(); plane++) {
                    assertTrue(frame.stride(plane)
                                    >= frame.format().planeByteWidth(plane, frame.width()),
                            clip + " plane " + plane + " stride is below its byte width");
                }
                frame.release();
                decoded++;
            }
        }
        assertTrue(decoded > 0, directory + " holds no file this decoder claims");
    }

    private static VideoFrame readFirst(VideoStreamSource source, Path clip) {
        for (int attempt = 0; attempt < 4096; attempt++) {
            VideoStreamSource.Read read = source.readFrame();
            if (read == VideoStreamSource.Read.FRAME) {
                return source.frame();
            }
            if (read == VideoStreamSource.Read.END) {
                break;
            }
        }
        throw new AssertionError(clip + " produced no picture");
    }

    private static final byte[] ISO_HEAD = {
        0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
    };

    private static final byte[] EBML_HEAD = {
        0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0,
    };

    private static Path write(Path directory, String name, byte[] head) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, head);
        return file;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.collect(Collectors.toList())) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(directory);
    }

    /**
     * The linked components, one name per entry. A container in libavformat is registered under
     * <em>all</em> the names it answers to at once (the ISO demuxer's name is the whole string
     * {@code "mov,mp4,m4a,3gp,3g2,mj2"}), so the names are split here rather than matched by
     * prefix, which would let {@code demuxer:mov} be satisfied by a demuxer called {@code movie}.
     */
    private static Set<String> components() {
        Set<String> names = new LinkedHashSet<>();
        for (String line : FfmpegMedia.components().split("\n")) {
            String entry = line.trim().toLowerCase(Locale.ROOT);
            int colon = entry.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String kind = entry.substring(0, colon);
            for (String name : entry.substring(colon + 1).split(",")) {
                if (!name.isBlank()) {
                    names.add(kind + ":" + name.trim());
                }
            }
        }
        return names;
    }

    /** Registered components of one kind, counted before their alternative names are split out. */
    private static long registered(String kind) {
        return Arrays.stream(FfmpegMedia.components().split("\n"))
                .map(line -> line.trim().toLowerCase(Locale.ROOT))
                .filter(line -> line.startsWith(kind + ":"))
                .count();
    }

    private static String sorted(Set<String> components, String prefix) {
        return components.stream().filter(name -> name.startsWith(prefix)).sorted()
                .collect(Collectors.joining(", "));
    }
}

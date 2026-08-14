package limn.video.ffmpeg;

import limn.video.PixelFormat;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decoding on the platform accelerator, and the two things that are genuinely different about it:
 * a picture is a handle rather than samples, and a consumer that cannot use a handle has to ask
 * for it back.
 *
 * <p><b>How this is tested at all, given that nothing here can encode H.264.</b> The two
 * accelerators the shipped build carries (h264 and hevc) can never be pointed at a clip this
 * repository produced, so their evidence is linkage and opt-in real files, exactly as ADR 015 §3
 * says for the codecs behind them. But VideoToolbox also decodes MPEG-4 Part 2, which the
 * {@code full} profile <em>can</em> encode, and that profile enables that one accelerator for no
 * other reason. So the round trip that proves the software seam proves this one too: write a clip,
 * decode it on the accelerator, and hold the result against the same clip decoded in software.
 *
 * <p><b>On an Apple Silicon Mac that round trip skips, and it is worth knowing why before
 * concluding the path is untested.</b> Those machines' VideoToolbox decodes H.264, HEVC and ProRes
 * and has no MPEG-4 Part 2 decoder at all (measured here, as {@code "VideoToolbox decoder for this
 * format not found"} followed by the accelerator declining the stream). So the tests that need a
 * real hardware picture skip, and what runs everywhere instead is
 * {@link #whatOpenReportsIsWhatThePicturesActuallyAre}, which asserts the invariant that failure
 * produced: open reports what the pictures <em>are</em> and never what it asked for. The zero-copy
 * half (the binding, the rectangle sampler, P010's normalisation and the release discipline) is
 * tested in the backend's own suite against IOSurfaces written there, and needs no decoder at all.
 */
class HardwareDecodeTest {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final int FRAMES = 12;

    @TempDir
    Path directory;

    @Test
    void theAcceleratorsTheConfigureLineNamesAreTheOnesTheLibraryHolds() {
        FfmpegTests.requireLibrary();
        assumeTrue(System.getProperty("os.name", "").startsWith("Mac"),
                "VideoToolbox is an Apple framework; Windows and Linux acceleration is phase 6b");

        // Tier 2, and it exists because --enable-videotoolbox switches on the FRAMEWORK and not one
        // accelerator: a build with the framework and no hwaccel attaches a device, decodes in
        // software and looks exactly like a working one from the outside. A configure flag is a
        // claim; a hardware configuration on a linked decoder is a fact.
        Set<String> components = components();
        assertTrue(components.contains("hwaccel:h264:videotoolbox"),
                "the h264 accelerator is not in the library: " + accelerators(components));
        assertTrue(components.contains("hwaccel:hevc:videotoolbox"),
                "the hevc accelerator is not in the library: " + accelerators(components));
    }

    @Test
    void nothingWasPickedUpFromTheBuildMachine() {
        FfmpegTests.requireLibrary();
        // --disable-autodetect is what stops a build acquiring whatever -dev packages happen to be
        // installed, and an accelerator is exactly the sort of thing it would acquire. Every
        // hardware configuration in this library must be VideoToolbox's.
        for (String entry : accelerators(components()).split(", ")) {
            if (entry.isBlank()) {
                continue;
            }
            assertTrue(entry.endsWith(":videotoolbox"),
                    entry + " was linked, and nothing but VideoToolbox was asked for");
        }
    }

    @Test
    void hardwareOffDecodesInSoftwareAndProducesSamples() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = clip();
        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4, FfmpegMedia.Hardware.OFF)) {
            assertFalse(media.isHardwareDecoding(), "OFF means off, whatever the machine has");
            assertEquals(PixelFormat.I420, media.video().pixelFormat());
            VideoFrame frame = first(media.video());
            assertEquals(VideoFrame.Kind.PLANAR, frame.kind());
            assertNotEquals(0, frame.plane(0).capacity());
            assertThrows(UnsupportedOperationException.class, frame::handle);
            frame.release();
        }
    }

    @Test
    void whatOpenReportsIsWhatThePicturesActuallyAre() throws IOException {
        FfmpegTests.requireWriter();
        // The invariant that holds on every machine, including the ones where the accelerator
        // declines, which is most of them for this codec, and is why it is worth asserting.
        //
        // An accelerator is attached before the first picture is decoded and chooses whether to
        // take the stream only when it meets one. If open() reported what it ASKED for rather than
        // what it GOT, the SPI's layout would be a promise the first picture breaks: NV12
        // advertised, I420 delivered, and every read refused with a message about the stream
        // changing format when nothing changed. So the shim decodes one picture at open and this
        // is the check that the two answers cannot disagree.
        Path clip = clip();
        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4, FfmpegMedia.Hardware.PREFER)) {
            VideoFrame frame = first(media.video());
            if (media.isHardwareDecoding()) {
                assertEquals(PixelFormat.NV12, media.video().pixelFormat());
                assertEquals(VideoFrame.Kind.IO_SURFACE, frame.kind());
            } else {
                assertEquals(PixelFormat.I420, media.video().pixelFormat());
                assertEquals(VideoFrame.Kind.PLANAR, frame.kind());
                assertNotEquals(0, frame.plane(0).capacity(),
                        "a software fallback is an ordinary decode and nothing about it is half-done");
            }
            assertEquals(media.video().pixelFormat(), frame.format());
            frame.release();
        }
    }

    @Test
    void aHardwarePictureIsAHandleAndRefusesItsPlanes() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = clip();
        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4, FfmpegMedia.Hardware.PREFER)) {
            assumeHardware(media);
            // The layout is the accelerator's and not the container's: MPEG-4 codes 4:2:0 planar
            // and VideoToolbox hands back NV12, which is why the SPI's layout is decided after the
            // accelerator has been confirmed rather than from the coded format.
            assertEquals(PixelFormat.NV12, media.video().pixelFormat(),
                    "an 8-bit VideoToolbox picture is NV12");

            VideoFrame frame = first(media.video());
            assertEquals(VideoFrame.Kind.IO_SURFACE, frame.kind());
            assertNotEquals(0L, frame.handle());
            UnsupportedOperationException refused =
                    assertThrows(UnsupportedOperationException.class, () -> frame.plane(0));
            assertTrue(refused.getMessage().contains("toPlanar"), refused.getMessage());
            frame.release();
        }
    }

    @Test
    void aPictureReadBackIsTheSamePictureTheSoftwareDecoderProduces() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = clip();

        int[] software = new int[WIDTH * HEIGHT];
        int[] softwareCb = new int[WIDTH * HEIGHT / 4];
        int[] softwareCr = new int[WIDTH * HEIGHT / 4];
        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4, FfmpegMedia.Hardware.OFF)) {
            VideoFrame frame = first(media.video());
            readPlanar(frame, software, softwareCb, softwareCr);
            frame.release();
        }

        int[] hardware = new int[WIDTH * HEIGHT];
        int[] hardwareCb = new int[WIDTH * HEIGHT / 4];
        int[] hardwareCr = new int[WIDTH * HEIGHT / 4];
        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4, FfmpegMedia.Hardware.PREFER)) {
            assumeHardware(media);
            VideoFrame frame = first(media.video());
            // Route A, which is what every consumer without a device does. It is also the only way
            // to look at a hardware picture from Java at all, which is why the oracle comparison
            // runs through it.
            frame.toPlanar();
            assertEquals(VideoFrame.Kind.PLANAR, frame.kind());
            readPlanar(frame, hardware, hardwareCb, hardwareCr);
            frame.release();
        }

        // Two different decoders, so this is not bit-exactness: MPEG-4's inverse transform is
        // specified to a tolerance and Apple's is not FFmpeg's. The bound is tight enough that
        // every way this path goes wrong fails it: a swapped chroma pair moves Cb and Cr to each
        // other's values, a stride read as a row length skews the picture by a row per row, and a
        // plane read at the wrong offset is not the picture at all. What it is deliberately loose
        // about is the last code or two of a lossy decode.
        assertClose(software, hardware, "luma");
        assertClose(softwareCb, hardwareCb, "Cb");
        assertClose(softwareCr, hardwareCr, "Cr");
    }

    @Test
    void readingBackTwiceIsIdempotentAndTheSlotStillComesHome() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = clip();
        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 2, FfmpegMedia.Hardware.PREFER)) {
            assumeHardware(media);
            VideoFrame frame = first(media.video());
            frame.toPlanar();
            int firstByte = frame.plane(0).get(0) & 0xFF;
            frame.toPlanar();
            assertEquals(firstByte, frame.plane(0).get(0) & 0xFF,
                    "a second read-back must not move the picture");
            frame.release();

            // Two slots, so a slot that did not come home would stall the stream inside a dozen
            // pictures. This is also the check that the download's own copy is released with it:
            // a leak there is invisible until the pool is exhausted or the process is.
            int decoded = 1;
            for (int attempt = 0; attempt < FRAMES * 8 && decoded < FRAMES; attempt++) {
                VideoStreamSource.Read read = media.video().readFrame();
                if (read == VideoStreamSource.Read.END) {
                    break;
                }
                if (read == VideoStreamSource.Read.FRAME) {
                    VideoFrame next = media.video().frame();
                    if (decoded % 2 == 0) {
                        next.toPlanar();
                    }
                    next.release();
                    decoded++;
                }
            }
            assertEquals(FRAMES, decoded, "every picture arrived and every slot came back");
        }
    }

    @Test
    void aSeekOnAHardwareStreamStillDeliversHandles() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = clip();
        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4, FfmpegMedia.Hardware.PREFER)) {
            assumeHardware(media);
            VideoFrame frame = first(media.video());
            long firstHandle = frame.handle();
            frame.release();

            media.video().seek(200_000, VideoStreamSource.SeekMode.EXACT);
            VideoFrame afterSeek = first(media.video());
            assertEquals(VideoFrame.Kind.IO_SURFACE, afterSeek.kind(),
                    "flushing the decoder does not turn a hardware stream into a software one");
            assertNotEquals(0L, afterSeek.handle());
            // Not asserted to differ from firstHandle: a pool is entitled to hand back the same
            // surface once the first picture has been released, and it usually does.
            assertTrue(firstHandle != 0L);
            afterSeek.release();
        }
    }

    /**
     * Tier 3 for the accelerators that actually ship. H.264 and HEVC are exactly the codecs that
     * have one here and exactly the codecs nothing in this repository can encode, so a real file is
     * the only way their path is ever walked end to end: the shim's `get_format`, the pixel-format
     * check, the IOSurface unwrap and the read-back, against a picture a decoder really produced.
     *
     * <p>Point {@code -Dlimn.video.test.clips} at a directory; skipped, loudly, when it is not set.
     * Every clip that opens is checked, and a clip whose codec has no accelerator is not a failure;
     * it is a software decode, and the invariant asserted is the one that holds either way.
     */
    @Test
    void everyRealClipAgreesWithItsOwnDecodePath() throws IOException {
        FfmpegTests.requireLibrary();
        String property = System.getProperty("limn.video.test.clips");
        assumeTrue(property != null && !property.isBlank(),
                "set -Dlimn.video.test.clips to a directory of media to exercise a real accelerator");
        Path directory = Path.of(property);
        assertTrue(Files.isDirectory(directory), property + " is not a directory");

        List<Path> clips;
        try (var entries = Files.list(directory)) {
            clips = entries.filter(Files::isRegularFile).sorted().toList();
        }
        FfmpegVideoDecoder decoder = new FfmpegVideoDecoder();
        int checked = 0;
        int accelerated = 0;
        for (Path clip : clips) {
            if (!decoder.supports(clip)) {
                continue;
            }
            try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4, FfmpegMedia.Hardware.PREFER)) {
                VideoFrame frame = first(media.video());
                if (media.isHardwareDecoding()) {
                    accelerated++;
                    assertEquals(VideoFrame.Kind.IO_SURFACE, frame.kind(), clip + " kind");
                    assertNotEquals(0L, frame.handle(), clip + " handle");
                    assertThrows(UnsupportedOperationException.class, () -> frame.plane(0),
                            clip + " must refuse its planes until they exist");
                    // The read-back is the only way to see a hardware picture from Java, and a
                    // decoder that produced a surface this SPI mis-described would show up here as
                    // a layout mismatch rather than as a wrong colour on a screen.
                    frame.toPlanar();
                    assertEquals(VideoFrame.Kind.PLANAR, frame.kind(), clip + " after read-back");
                } else {
                    assertEquals(VideoFrame.Kind.PLANAR, frame.kind(), clip + " kind");
                }
                assertEquals(media.video().pixelFormat(), frame.format(), clip + " layout");
                assertEquals(media.video().width(), frame.width(), clip + " width");
                for (int plane = 0; plane < frame.format().planeCount(); plane++) {
                    assertTrue(frame.stride(plane)
                                    >= frame.format().planeByteWidth(plane, frame.width()),
                            clip + " plane " + plane + " stride is below its byte width");
                }
                frame.release();
                checked++;
            }
        }
        assertTrue(checked > 0, directory + " holds no file this decoder claims");
        System.out.println("real clips checked: " + checked + ", of which accelerated: "
                + accelerated);
    }

    // ------------------------------------------------------------------ helpers

    private Path clip() throws IOException {
        return FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, WIDTH, HEIGHT, FRAMES, 0);
    }

    /**
     * Skips when this machine's VideoToolbox declined the stream: a real outcome (an accelerator
     * has a table of what it does and MPEG-4 Part 2 is old enough to be dropped from it), and the
     * reason the shim probes at open rather than promising what it asked for.
     */
    private static void assumeHardware(FfmpegMedia media) {
        assumeTrue(media.isHardwareDecoding(),
                "this machine's VideoToolbox will not decode MPEG-4 Part 2, so there is no"
                        + " hardware picture here to test");
    }

    private static VideoFrame first(VideoStreamSource video) {
        for (int attempt = 0; attempt < 256; attempt++) {
            VideoStreamSource.Read read = video.readFrame();
            if (read == VideoStreamSource.Read.FRAME) {
                return video.frame();
            }
            if (read == VideoStreamSource.Read.END) {
                break;
            }
        }
        throw new AssertionError("no picture arrived");
    }

    /** Reads a picture's codes into three arrays, whichever of the two planar layouts it is. */
    private static void readPlanar(VideoFrame frame, int[] luma, int[] cb, int[] cr) {
        PixelFormat format = frame.format();
        ByteBuffer lumaPlane = frame.plane(0);
        int lumaStride = frame.stride(0);
        for (int row = 0; row < HEIGHT; row++) {
            for (int column = 0; column < WIDTH; column++) {
                luma[row * WIDTH + column] = format.componentAt(lumaPlane,
                        row * lumaStride + column * format.bytesPerSample(0));
            }
        }
        int chromaWidth = WIDTH / 2;
        int chromaHeight = HEIGHT / 2;
        boolean interleaved = format.planeCount() == 2;
        ByteBuffer cbPlane = frame.plane(1);
        ByteBuffer crPlane = interleaved ? cbPlane : frame.plane(2);
        int cbStride = frame.stride(1);
        int crStride = interleaved ? cbStride : frame.stride(2);
        int step = format.bytesPerSample(1);
        int componentBytes = step / format.componentsPerSample(1);
        for (int row = 0; row < chromaHeight; row++) {
            for (int column = 0; column < chromaWidth; column++) {
                int index = row * chromaWidth + column;
                cb[index] = format.componentAt(cbPlane, row * cbStride + column * step);
                cr[index] = format.componentAt(crPlane, row * crStride + column * step
                        + (interleaved ? componentBytes : 0));
            }
        }
    }

    private static void assertClose(int[] expected, int[] actual, String what) {
        long total = 0;
        int worst = 0;
        int worstAt = -1;
        for (int index = 0; index < expected.length; index++) {
            int difference = Math.abs(expected[index] - actual[index]);
            total += difference;
            if (difference > worst) {
                worst = difference;
                worstAt = index;
            }
        }
        double mean = (double) total / expected.length;
        assertTrue(mean <= 3.0, what + ": the two decoders' pictures differ by " + mean
                + " codes on average, which is a different picture and not a different IDCT");
        assertTrue(worst <= 24, what + ": sample " + worstAt + " differs by " + worst
                + " codes (" + expected[worstAt] + " against " + actual[worstAt] + ")");
    }

    /** Every component the linked libraries hold, one name per entry. */
    private static Set<String> components() {
        return Arrays.stream(FfmpegMedia.components().split("\n"))
                .map(line -> line.trim().toLowerCase(Locale.ROOT))
                .filter(line -> !line.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String accelerators(Set<String> components) {
        return components.stream().filter(entry -> entry.startsWith("hwaccel:")).sorted()
                .collect(Collectors.joining(", "));
    }
}

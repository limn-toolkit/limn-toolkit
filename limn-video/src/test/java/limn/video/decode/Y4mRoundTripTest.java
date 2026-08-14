package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stream written to a file and read back. The writer exists so that the reader can be tested
 * against real bytes without a media file ever being committed, and the two together say exactly
 * what the container does and does not carry.
 */
class Y4mRoundTripTest {

    @TempDir
    Path directory;

    @Test
    void everySampleSurvivesTheTripForEveryWritableLayout() {
        // Odd sizes on purpose: this is where the two sides could round a chroma plane differently,
        // and where the last row of a plane ends at its last sample rather than at its stride.
        int[][] sizes = {{5, 3}, {7, 5}, {32, 18}};
        for (PixelFormat format : new PixelFormat[]{PixelFormat.I420, PixelFormat.I444,
            PixelFormat.I420_10LE, PixelFormat.I444_10LE}) {
            for (int[] size : sizes) {
                SyntheticSpec spec = SyntheticSpec.of(size[0], size[1])
                        .withFormat(format)
                        .withPattern(SyntheticPattern.GRADIENT)
                        .withRate(25, 1)
                        .withFrameCount(4);
                Path file = directory.resolve(format + "-" + size[0] + "x" + size[1] + ".y4m");
                try (VideoStreamSource written = SyntheticVideoDecoder.open(spec)) {
                    assertEquals(4, Y4mWriter.write(file, written, 100));
                }

                try (VideoStreamSource read = new Y4mDecoder().openStream(file)) {
                    assertEquals(size[0], read.width(), file.toString());
                    assertEquals(size[1], read.height(), file.toString());
                    assertEquals(format, read.pixelFormat(), file.toString());
                    assertEquals(25, read.frameRateNum());
                    for (int index = 0; index < 4; index++) {
                        assertEquals(VideoStreamSource.Read.FRAME, read.readFrame());
                        VideoFrame frame = read.frame();
                        assertEquals(index * 40_000L, frame.ptsMicros());
                        assertSamples(frame, spec.pattern(), index, file.toString());
                        frame.release();
                    }
                    assertEquals(VideoStreamSource.Read.END, read.readFrame());
                }
            }
        }
    }

    @Test
    void theRangeSurvivesAndTheMatrixDoesNot() {
        // Not a defect to fix later: YUV4MPEG2 has no field for the matrix, and inventing one would
        // make a file this project writes unreadable to everything else.
        SyntheticSpec spec = SyntheticSpec.of(16, 16)
                .withColor(VideoColor.BT601_FULL)
                .withFrameCount(1);
        Path file = directory.resolve("bt601-full.y4m");
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            Y4mWriter.write(file, source, 1);
        }
        try (VideoStreamSource read = new Y4mDecoder().openStream(file)) {
            assertEquals(VideoColor.Range.FULL, read.color().range(), "carried by XCOLORRANGE");
            assertEquals(VideoColor.Matrix.BT709, read.color().matrix(),
                    "the matrix is gone, and what is reported is the default it decodes as");
        }
        try (VideoStreamSource read = new Y4mDecoder(VideoColor.BT601_FULL).openStream(file)) {
            assertSame(VideoColor.BT601_FULL, read.color(),
                    "which is why a caller that knows can say so");
        }
    }

    @Test
    void anUnsignalledStreamIsWrittenWithoutARangeTag() {
        SyntheticSpec spec = SyntheticSpec.of(8, 8)
                .withColor(VideoColor.unspecified())
                .withFrameCount(1);
        Path file = directory.resolve("unsaid.y4m");
        try (VideoStreamSource source = SyntheticVideoDecoder.open(spec)) {
            Y4mWriter.write(file, source, 1);
        }
        try (VideoStreamSource read = new Y4mDecoder().openStream(file)) {
            assertFalse(read.color().isSpecified(),
                    "a stream that said nothing is not written as though it had");
        }
    }

    @Test
    void theWriterStopsAtItsOwnLimitOnAnEndlessStream() {
        Path file = directory.resolve("endless.y4m");
        try (VideoStreamSource source = SyntheticVideoDecoder.open(
                SyntheticSpec.of(8, 8).withSlots(1))) {
            assertEquals(5, Y4mWriter.write(file, source, 5));
        }
        try (VideoStreamSource read = new Y4mDecoder().openStream(file)) {
            int seen = 0;
            while (read.readFrame() == VideoStreamSource.Read.FRAME) {
                read.frame().release();
                seen++;
            }
            assertEquals(5, seen);
        }
    }

    @Test
    void aTwoPlaneLayoutHasNoTagAndIsRefused() {
        Path file = directory.resolve("nv12.y4m");
        try (VideoStreamSource source = SyntheticVideoDecoder.open(
                SyntheticSpec.of(16, 16).withFormat(PixelFormat.NV12))) {
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                    () -> Y4mWriter.write(file, source, 1));
            assertTrue(error.getMessage().contains("NV12"), error.getMessage());
        }
    }

    @Test
    void theWriterRefusesANonsensicalLimit() {
        try (VideoStreamSource source = SyntheticVideoDecoder.open(SyntheticSpec.of(8, 8))) {
            assertThrows(IllegalArgumentException.class,
                    () -> Y4mWriter.write(directory.resolve("none.y4m"), source, 0));
        }
    }

    private static void assertSamples(VideoFrame frame, SyntheticPattern pattern, int index,
                                      String where) {
        PixelFormat format = frame.format();
        int width = frame.width();
        int height = frame.height();
        int depth = format.bitDepth();
        int lumaStep = format.bytesPerSample(0);
        int chromaStep = format.bytesPerSample(1);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(pattern.luma(x, y, width, height, index, depth),
                        format.componentAt(frame.plane(0), y * frame.stride(0) + x * lumaStep),
                        where + " luma at " + x + "," + y + " of picture " + index);
            }
        }
        for (int cy = 0; cy < format.planeHeight(1, height); cy++) {
            int y = cy << format.chromaShiftY();
            for (int cx = 0; cx < format.planeWidth(1, width); cx++) {
                int x = cx << format.chromaShiftX();
                assertEquals(pattern.cb(x, y, width, height, index, depth),
                        format.componentAt(frame.plane(1), cy * frame.stride(1) + cx * chromaStep),
                        where + " Cb at " + cx + "," + cy);
                assertEquals(pattern.cr(x, y, width, height, index, depth),
                        format.componentAt(frame.plane(2), cy * frame.stride(2) + cx * chromaStep),
                        where + " Cr at " + cx + "," + cy);
            }
        }
    }
}

package limn.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plane geometry of {@link PixelFormat}: the declared layout of each constant, the worked size
 * table, and the odd-size rounding that must round up once and identically for every caller.
 */
class PixelFormatTest {

    /** Upper bound of the size sweep: past 4096, and odd, so the last size rounds up. */
    private static final int SWEEP = 4097;

    @Test
    void planeCountsAndComponentsAreTheDeclaredOnes() {
        assertEquals(3, PixelFormat.I420.planeCount());
        assertEquals(2, PixelFormat.NV12.planeCount());
        assertEquals(3, PixelFormat.I444.planeCount());

        assertEquals(1, PixelFormat.I420.componentsPerSample(0));
        assertEquals(1, PixelFormat.I420.componentsPerSample(1));
        assertEquals(1, PixelFormat.I420.componentsPerSample(2));
        assertEquals(1, PixelFormat.NV12.componentsPerSample(0));
        assertEquals(2, PixelFormat.NV12.componentsPerSample(1), "NV12 chroma is Cb and Cr in one sample");
        assertEquals(1, PixelFormat.I444.componentsPerSample(0));
        assertEquals(1, PixelFormat.I444.componentsPerSample(1));
        assertEquals(1, PixelFormat.I444.componentsPerSample(2));

        assertEquals(2, PixelFormat.NV12.bytesPerSample(1));
        assertEquals(1, PixelFormat.I420.bytesPerSample(1));
    }

    @Test
    void bitDepthAndMaxCodeAgreeAndDriveTheByteWidth() {
        for (PixelFormat format : PixelFormat.values()) {
            int depth = format.bitDepth();
            assertTrue(depth == 8 || depth == 10, format + " bit depth is 8 or 10, got " + depth);
            assertEquals((1 << depth) - 1, format.maxCode(), format + " max code");
            for (int plane = 0; plane < format.planeCount(); plane++) {
                assertEquals(format.componentsPerSample(plane) * ((depth + 7) / 8),
                        format.bytesPerSample(plane),
                        format + " plane " + plane + " bytes follow the depth, not the channels");
            }
        }
        assertEquals(2, PixelFormat.I420_10LE.bytesPerSample(0),
                "a 10-bit sample is two bytes even though it is one channel");
        assertEquals(1023, PixelFormat.I420_10LE.maxCode());
    }

    @Test
    void aWideComponentIsStoredLittleEndianAndRightJustified() {
        // The whole difference between a code in [0..1023] and a normalized 16-bit value, and the
        // difference between reading it and reading it backwards. Both are silent.
        java.nio.ByteBuffer plane = java.nio.ByteBuffer.allocate(4);
        PixelFormat.I420_10LE.putComponent(plane, 0, 1023);
        assertEquals(0xFF, plane.get(0) & 0xFF, "low byte first");
        assertEquals(0x03, plane.get(1) & 0xFF, "and the top six bits are zero");
        assertEquals(1023, PixelFormat.I420_10LE.componentAt(plane, 0));

        PixelFormat.I420_10LE.putComponent(plane, 2, 0x123);
        assertEquals(0x23, plane.get(2) & 0xFF);
        assertEquals(0x01, plane.get(3) & 0xFF);
        assertEquals(0x123, PixelFormat.I420_10LE.componentAt(plane, 2));

        java.nio.ByteBuffer narrow = java.nio.ByteBuffer.allocate(2);
        PixelFormat.I420.putComponent(narrow, 1, 200);
        assertEquals(0, narrow.get(0), "an 8-bit component occupies one byte and no more");
        assertEquals(200, PixelFormat.I420.componentAt(narrow, 1));
    }

    @Test
    void planeGeometryMatchesTheWorkedTable() {
        assertPlane(PixelFormat.I420, 0, 1, 1, 1, 1);
        assertPlane(PixelFormat.I420, 1, 1, 1, 1, 1);
        assertPlane(PixelFormat.I420, 2, 1, 1, 1, 1);

        assertPlane(PixelFormat.I420, 0, 3, 3, 3, 3);
        assertPlane(PixelFormat.I420, 1, 3, 3, 2, 2);
        assertPlane(PixelFormat.I420, 2, 3, 3, 2, 2);

        assertPlane(PixelFormat.I420, 0, 5, 7, 5, 7);
        assertPlane(PixelFormat.I420, 1, 5, 7, 3, 4);
        assertPlane(PixelFormat.I420, 2, 5, 7, 3, 4);

        assertPlane(PixelFormat.I420, 0, 1920, 1080, 1920, 1080);
        assertPlane(PixelFormat.I420, 1, 1920, 1080, 960, 540);
        assertPlane(PixelFormat.I420, 2, 1920, 1080, 960, 540);

        assertPlane(PixelFormat.I420, 0, 1919, 1079, 1919, 1079);
        assertPlane(PixelFormat.I420, 1, 1919, 1079, 960, 540);
        assertPlane(PixelFormat.I420, 2, 1919, 1079, 960, 540);

        assertPlane(PixelFormat.NV12, 0, 5, 7, 5, 7);
        assertEquals(5, PixelFormat.NV12.planeByteWidth(0, 5));
        assertPlane(PixelFormat.NV12, 1, 5, 7, 3, 4);
        assertEquals(6, PixelFormat.NV12.planeByteWidth(1, 5),
                "three interleaved chroma samples of two bytes each");

        assertPlane(PixelFormat.I444, 0, 5, 7, 5, 7);
        assertPlane(PixelFormat.I444, 1, 5, 7, 5, 7);
        assertPlane(PixelFormat.I444, 2, 5, 7, 5, 7);
        assertEquals(5, PixelFormat.I444.planeByteWidth(0, 5));
        assertEquals(5, PixelFormat.I444.planeByteWidth(1, 5));
        assertEquals(5, PixelFormat.I444.planeByteWidth(2, 5));
    }

    @Test
    void chromaRoundsUpForEveryOddSize() {
        for (int width = 1; width <= SWEEP; width++) {
            int height = SWEEP + 1 - width;
            int expectedChromaWidth = (width + 1) / 2;
            int expectedChromaHeight = (height + 1) / 2;

            assertEquals(expectedChromaWidth, PixelFormat.I420.planeWidth(1, width), "I420 chroma width " + width);
            assertEquals(expectedChromaHeight, PixelFormat.I420.planeHeight(2, height), "I420 chroma height " + height);
            assertEquals(expectedChromaWidth, PixelFormat.NV12.planeWidth(1, width), "NV12 chroma width " + width);
            assertEquals(expectedChromaHeight, PixelFormat.NV12.planeHeight(1, height), "NV12 chroma height " + height);
            assertEquals(width, PixelFormat.I444.planeWidth(1, width), "I444 never subsamples");
            assertEquals(height, PixelFormat.I444.planeHeight(2, height), "I444 never subsamples");
            assertEquals(width, PixelFormat.I420.planeWidth(0, width), "luma is never subsampled");
            assertEquals(height, PixelFormat.I420.planeHeight(0, height), "luma is never subsampled");
        }

        for (PixelFormat format : PixelFormat.values()) {
            for (int plane = 0; plane < format.planeCount(); plane++) {
                assertTrue(format.planeWidth(plane, 1) >= 1, format + " plane " + plane + " width at 1x1");
                assertTrue(format.planeHeight(plane, 1) >= 1, format + " plane " + plane + " height at 1x1");
            }
        }
    }

    @Test
    void nv12ChromaRowIsWiderThanLumaExactlyForOddWidths() {
        for (int width = 1; width <= SWEEP; width++) {
            int lumaBytes = PixelFormat.NV12.planeByteWidth(0, width);
            int chromaBytes = PixelFormat.NV12.planeByteWidth(1, width);
            assertEquals(width + (width & 1), chromaBytes, "NV12 chroma byte width at " + width);
            assertEquals(width % 2 == 1, chromaBytes > lumaBytes,
                    "NV12 chroma row exceeds luma exactly for odd widths, at " + width);
        }
    }

    @Test
    void i420AndNv12PackToTheSameTotal() {
        for (int width = 1; width <= SWEEP; width++) {
            int height = SWEEP + 1 - width;
            assertEquals(tightlyPacked(PixelFormat.I420, width, height),
                    tightlyPacked(PixelFormat.NV12, width, height),
                    "the same 4:2:0 samples in two and in three planes, at " + width + "x" + height);
            assertEquals(3L * width * height, tightlyPacked(PixelFormat.I444, width, height),
                    "4:4:4 is three full planes, at " + width + "x" + height);
        }
    }

    @Test
    void minPlaneBytesExcludesTrailingPadding() {
        int byteWidth = PixelFormat.I420.planeByteWidth(0, 5);
        int rows = PixelFormat.I420.planeHeight(0, 7);
        long minimum = PixelFormat.I420.minPlaneBytes(0, 5, 7, 8);
        assertEquals(8L * (rows - 1) + byteWidth, minimum);
        assertTrue(minimum < 8L * rows, "the last row needs only its byte width");
        assertEquals((long) byteWidth * rows, PixelFormat.I420.minPlaneBytes(0, 5, 7, byteWidth),
                "a tight stride packs exactly");
    }

    @Test
    void minPlaneBytesStaysPositiveBeyondIntRange() {
        int side = PixelFormat.MAX_DIMENSION;
        long minimum = PixelFormat.I444.minPlaneBytes(0, side, side, side);
        assertEquals(4_294_836_225L, minimum);
        assertTrue(minimum > Integer.MAX_VALUE, "a full plane at the maximum size exceeds an int");
    }

    @Test
    void invalidGeometryArgumentsThrow() {
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.I420.planeWidth(0, 0));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.I420.planeWidth(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> PixelFormat.I420.planeWidth(0, PixelFormat.MAX_DIMENSION + 1));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.I420.planeHeight(0, 0));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.I420.planeHeight(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> PixelFormat.I420.planeHeight(0, PixelFormat.MAX_DIMENSION + 1));

        assertThrows(IndexOutOfBoundsException.class, () -> PixelFormat.I420.planeWidth(3, 16));
        assertThrows(IndexOutOfBoundsException.class, () -> PixelFormat.I420.planeWidth(-1, 16));
        assertThrows(IndexOutOfBoundsException.class, () -> PixelFormat.NV12.planeWidth(2, 16));
        assertThrows(IndexOutOfBoundsException.class, () -> PixelFormat.NV12.componentsPerSample(2));

        assertThrows(IllegalArgumentException.class, () -> PixelFormat.I420.minPlaneBytes(0, 5, 7, 4));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.NV12.minPlaneBytes(1, 5, 7, 5));
    }

    @Test
    void alignedStrideRoundsUpToThePowerOfTwo() {
        assertEquals(1920, PixelFormat.I420.alignedStride(0, 1919, 64));
        assertEquals(1919, PixelFormat.I420.alignedStride(0, 1919, 1));
        assertEquals(1920, PixelFormat.I420.alignedStride(0, 1920, 64), "an aligned width is left alone");
        assertEquals(4096, PixelFormat.I420.alignedStride(0, 4001, 4096));

        assertThrows(IllegalArgumentException.class, () -> PixelFormat.I420.alignedStride(0, 1919, 3));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.I420.alignedStride(0, 1919, 0));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.I420.alignedStride(0, 1919, -64));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.I420.alignedStride(0, 1919, 8192));
    }

    @Test
    void everyConstantAnswersEveryAccessor() {
        int[] sizes = {1, 2, 3, 16, 17, 1919, 1920, PixelFormat.MAX_DIMENSION};
        for (PixelFormat format : PixelFormat.values()) {
            assertTrue(format.planeCount() >= 1, format + " has at least a luma plane");
            assertTrue(format.planeCount() <= 3,
                    format + " has more planes than a frame can bind, and the excess would fail as a"
                            + " bare array index rather than as the documented plane check");
            assertTrue(format.chromaShiftX() >= 0 && format.chromaShiftX() <= 1, format + " horizontal shift");
            assertTrue(format.chromaShiftY() >= 0 && format.chromaShiftY() <= 1, format + " vertical shift");
            for (int plane = 0; plane < format.planeCount(); plane++) {
                assertTrue(format.componentsPerSample(plane) >= 1, format + " components of plane " + plane);
                assertTrue(format.bytesPerSample(plane) >= 1, format + " bytes per sample of plane " + plane);
                for (int size : sizes) {
                    int width = format.planeWidth(plane, size);
                    int height = format.planeHeight(plane, size);
                    int byteWidth = format.planeByteWidth(plane, size);
                    assertTrue(width >= 1 && width <= size, format + " plane " + plane + " width at " + size);
                    assertTrue(height >= 1 && height <= size, format + " plane " + plane + " height at " + size);
                    assertEquals(width * format.bytesPerSample(plane), byteWidth,
                            format + " plane " + plane + " byte width at " + size);
                    assertEquals(byteWidth, format.alignedStride(plane, size, 1),
                            format + " plane " + plane + " stride at alignment 1, size " + size);
                    assertTrue(format.minPlaneBytes(plane, size, size, byteWidth) >= byteWidth,
                            format + " plane " + plane + " minimum bytes at " + size);
                }
            }
        }
    }

    private static void assertPlane(PixelFormat format, int plane, int frameWidth, int frameHeight,
                                    int expectedWidth, int expectedHeight) {
        String where = format + " plane " + plane + " of " + frameWidth + "x" + frameHeight;
        assertEquals(expectedWidth, format.planeWidth(plane, frameWidth), where + " width");
        assertEquals(expectedHeight, format.planeHeight(plane, frameHeight), where + " height");
    }

    private static long tightlyPacked(PixelFormat format, int width, int height) {
        long total = 0;
        for (int plane = 0; plane < format.planeCount(); plane++) {
            total += format.minPlaneBytes(plane, width, height, format.planeByteWidth(plane, width));
        }
        return total;
    }
}

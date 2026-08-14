package limn.video;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P010, which is the one layout here whose samples are not right-justified, and therefore the one
 * whose correctness nothing structural guards.
 *
 * <p>Every other 10-bit failure announces itself in the geometry: a wrong plane count throws, a
 * wrong stride skews the picture, a wrong byte width runs off the end of the buffer. P010's
 * geometry is NV12's with two-byte samples and is right whichever way the code is justified, so a
 * reader that treats its words as {@link PixelFormat#I420_10LE}'s produces a picture that is
 * uniformly 64 times too bright with every dimension, stride and plane count correct. This test is
 * what says so; there is nothing else.
 */
class P010LayoutTest {

    @Test
    void theCodeLivesInTheTopTenBitsOfTheWord() {
        // 0x2AB is 683: it has bits set in both bytes and in the bottom six, so a shift that is
        // missing, doubled or applied the wrong way round all land somewhere different.
        int code = 0x2AB;
        ByteBuffer deep = ByteBuffer.allocate(2);
        ByteBuffer shallow = ByteBuffer.allocate(2);
        PixelFormat.P010.putComponent(deep, 0, code);
        PixelFormat.I420_10LE.putComponent(shallow, 0, code);

        assertEquals(code << 6, wordAt(deep), "P010 stores the code at the top of the word");
        assertEquals(code, wordAt(shallow), "I420_10LE stores it at the bottom");
        assertNotEquals(wordAt(deep), wordAt(shallow),
                "the two layouts must not agree, or nothing here is being tested");

        assertEquals(code, PixelFormat.P010.componentAt(deep, 0));
        assertEquals(code, PixelFormat.I420_10LE.componentAt(shallow, 0));
        // The failure this catches, spelled out: the same bytes read through the other layout.
        assertEquals(code << 6, PixelFormat.I420_10LE.componentAt(deep, 0),
                "read as a right-justified layout, a P010 word is 64 times its own code, and it is"
                        + " NOT masked back into range, because a masked one would look plausible");
    }

    @Test
    void everyCodeSurvivesTheRoundTripAndNothingLeaksOutOfIt() {
        ByteBuffer word = ByteBuffer.allocate(2);
        for (int code = 0; code <= PixelFormat.P010.maxCode(); code++) {
            PixelFormat.P010.putComponent(word, 0, code);
            assertEquals(code, PixelFormat.P010.componentAt(word, 0), "code " + code);
            assertEquals(0, wordAt(word) & 0x3F, "the bottom six bits of a P010 word are zero");
        }
    }

    @Test
    void theShiftIsCarriedOnTheLayoutAndNotOnTheDepth() {
        // A consumer deriving the justification from the bit depth would get P010 right by accident
        // and I420_10LE wrong, or the reverse. The depth says how many bits; the layout says where.
        assertEquals(6, PixelFormat.P010.codeShift());
        for (PixelFormat format : PixelFormat.values()) {
            if (format != PixelFormat.P010) {
                assertEquals(0, format.codeShift(), format + " is right-justified");
            }
            assertTrue(format.codeShift() + format.bitDepth() <= 8 * ((format.bitDepth() + 7) / 8),
                    format + " would not fit its own storage word");
        }
    }

    @Test
    void theGeometryIsNv12sWithATwoByteSample() {
        assertEquals(PixelFormat.NV12.planeCount(), PixelFormat.P010.planeCount());
        assertEquals(PixelFormat.NV12.chromaShiftX(), PixelFormat.P010.chromaShiftX());
        assertEquals(PixelFormat.NV12.chromaShiftY(), PixelFormat.P010.chromaShiftY());
        for (int plane = 0; plane < 2; plane++) {
            assertEquals(PixelFormat.NV12.componentsPerSample(plane),
                    PixelFormat.P010.componentsPerSample(plane), "plane " + plane + " components");
            assertEquals(2 * PixelFormat.NV12.bytesPerSample(plane),
                    PixelFormat.P010.bytesPerSample(plane), "plane " + plane + " bytes");
            // Odd widths included: NV12's chroma plane is one byte WIDER than its luma plane at an
            // odd width, and doubling the sample must not lose that.
            for (int width : new int[] {1, 2, 3, 5, 9, 1920}) {
                assertEquals(PixelFormat.NV12.planeWidth(plane, width),
                        PixelFormat.P010.planeWidth(plane, width), "plane " + plane + " at " + width);
                assertEquals(2 * PixelFormat.NV12.planeByteWidth(plane, width),
                        PixelFormat.P010.planeByteWidth(plane, width),
                        "plane " + plane + " byte width at " + width);
            }
        }
    }

    @Test
    void aP010PictureDecodesAsTheI420_10lePictureItIsTheInterleavingOf() {
        // The end-to-end guard. The same codes in the two layouts are the same picture, and the
        // reference converter is where "the same picture" is decided for every consumer that has no
        // device, so if the justification were dropped anywhere between the buffer and the matrix,
        // these two would part company by a factor of 64.
        //
        // The P010 side is written with the shift SPELLED HERE rather than through putComponent, so
        // that a layout and a writer moving the same wrong way cannot satisfy it. That is ADR 016
        // §8's rule about widening an oracle and a shader together, one layout later.
        int width = 6;
        int height = 4;
        int chroma = 3 * 2;
        int[] luma = ramp(width * height, 7, 1023);
        int[] cb = ramp(chroma, 401, 1023);
        int[] cr = ramp(chroma, 907, 1023);

        for (VideoColor color : new VideoColor[] {
            VideoColor.BT709_LIMITED, VideoColor.BT709_FULL, VideoColor.BT2020_LIMITED,
        }) {
            VideoFrame planar = frame(width, height, PixelFormat.I420_10LE, color, luma, cb, cr);
            VideoFrame interleaved = p010Frame(width, height, color, luma, cb, cr);
            byte[] fromPlanar = new byte[width * height * 4];
            byte[] fromP010 = new byte[width * height * 4];
            YuvConverter.toRgba8(planar, fromPlanar, 0, width * 4);
            YuvConverter.toRgba8(interleaved, fromP010, 0, width * 4);
            planar.release();
            interleaved.release();

            assertArrayEquals(fromPlanar, fromP010,
                    color + ": P010 and I420_10LE carry the same codes and must decode alike");
        }
    }

    /**
     * A P010 picture whose bytes this test writes itself: two-byte little-endian words holding
     * {@code code << 6}, exactly as VideoToolbox lays them out. Deliberately not
     * {@link PixelFormat#putComponent}: the point is to check the layout against a second opinion,
     * and asking the layout to write its own input would only check that it is self-consistent.
     */
    private static VideoFrame p010Frame(int width, int height, VideoColor color,
                                        int[] luma, int[] cb, int[] cr) {
        PixelFormat format = PixelFormat.P010;
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(width, height, format, color);
        for (int plane = 0; plane < 2; plane++) {
            int columns = format.planeWidth(plane, width);
            int rows = format.planeHeight(plane, height);
            int stride = format.planeByteWidth(plane, width);
            ByteBuffer buffer = ByteBuffer.allocateDirect(stride * rows);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int sample = row * columns + column;
                    int at = row * stride + column * (plane == 0 ? 2 : 4);
                    if (plane == 0) {
                        putLittleEndian(buffer, at, luma[sample] << 6);
                    } else {
                        putLittleEndian(buffer, at, cb[sample] << 6);
                        putLittleEndian(buffer, at + 2, cr[sample] << 6);
                    }
                }
            }
            writer.setPlane(plane, buffer, stride);
        }
        return writer.publish();
    }

    private static void putLittleEndian(ByteBuffer buffer, int at, int word) {
        buffer.put(at, (byte) word);
        buffer.put(at + 1, (byte) (word >> 8));
    }

    private static int wordAt(ByteBuffer word) {
        return (word.get(0) & 0xFF) | ((word.get(1) & 0xFF) << 8);
    }

    /** Deterministic codes over the whole ten-bit range, including the bottom six bits. */
    private static int[] ramp(int count, int seed, int maxCode) {
        int[] codes = new int[count];
        int state = seed;
        for (int index = 0; index < count; index++) {
            state = state * 1_103_515_245 + 12_345;
            codes[index] = (state >>> 16) & maxCode;
        }
        return codes;
    }

    /** A published picture, planes tight, from codes in each plane's own grid. */
    private static VideoFrame frame(int width, int height, PixelFormat format, VideoColor color,
                                    int[] luma, int[] cb, int[] cr) {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(width, height, format, color);
        for (int plane = 0; plane < format.planeCount(); plane++) {
            int columns = format.planeWidth(plane, width);
            int rows = format.planeHeight(plane, height);
            int stride = format.planeByteWidth(plane, width);
            ByteBuffer buffer = ByteBuffer.allocateDirect(stride * rows);
            int step = format.bytesPerSample(plane);
            int componentBytes = step / format.componentsPerSample(plane);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int at = row * stride + column * step;
                    int sample = row * columns + column;
                    if (plane == 0) {
                        format.putComponent(buffer, at, luma[sample]);
                    } else if (format.planeCount() == 2) {
                        format.putComponent(buffer, at, cb[sample]);
                        format.putComponent(buffer, at + componentBytes, cr[sample]);
                    } else {
                        format.putComponent(buffer, at, plane == 1 ? cb[sample] : cr[sample]);
                    }
                }
            }
            writer.setPlane(plane, buffer, stride);
        }
        return writer.publish();
    }
}

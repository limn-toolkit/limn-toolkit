package limn.backend.lwjgl;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;

import java.nio.ByteBuffer;

/**
 * Builds published {@link VideoFrame}s whose memory shape is part of the test:
 * padded or tight rows, direct or heap buffers, and a buffer that ends at the
 * last sample rather than at the end of its last row. Row padding is filled
 * with a value no sample here ever takes, so an upload that reads it lands
 * somewhere visible instead of blending in.
 */
final class TestPictures {

    /** Row padding, and every byte before the samples are written. */
    static final int POISON = 0xA5;

    private TestPictures() {
    }

    /**
     * @param samplesPerPlane luma, Cb and Cr in each plane's own sample grid
     *                        (Cr is ignored for a two-plane format's third slot)
     * @param extraStride     bytes of padding after each row of every plane; an
     *                        odd value gives an interleaved chroma plane a
     *                        stride that is not a whole number of samples
     * @param direct          whether the planes are device-addressable memory
     * @param exactCapacity   whether each plane holds exactly
     *                        {@link PixelFormat#minPlaneBytes}, no padding
     *                        after the final sample
     */
    static VideoFrame frame(int width, int height, PixelFormat format, VideoColor color,
                            int[] luma, int[] cb, int[] cr,
                            int extraStride, boolean direct, boolean exactCapacity) {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(width, height, format, color);
        for (int plane = 0; plane < format.planeCount(); plane++) {
            int columns = format.planeWidth(plane, width);
            int rows = format.planeHeight(plane, height);
            int byteWidth = format.planeByteWidth(plane, width);
            int stride = byteWidth + extraStride;
            long minimum = format.minPlaneBytes(plane, width, height, stride);
            int capacity = (int) (exactCapacity ? minimum : (long) stride * rows);
            ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
            for (int index = 0; index < capacity; index++) {
                buffer.put(index, (byte) POISON);
            }
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int bytesPerSample = format.bytesPerSample(plane);
                    int at = row * stride + column * bytesPerSample;
                    int sample = row * columns + column;
                    if (plane == 0) {
                        format.putComponent(buffer, at, luma[sample]);
                    } else if (format.planeCount() == 2) {
                        int componentBytes = bytesPerSample / format.componentsPerSample(plane);
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

    /** A picture with one repeated code per role: the shape the anchor table pins. */
    static VideoFrame uniform(int width, int height, PixelFormat format, VideoColor color,
                              int luma, int cb, int cr) {
        int chromaSamples = format.planeWidth(1, width) * format.planeHeight(1, height);
        return frame(width, height, format, color,
                filled(width * height, luma), filled(chromaSamples, cb), filled(chromaSamples, cr),
                0, true, false);
    }

    static int[] filled(int count, int value) {
        int[] samples = new int[count];
        java.util.Arrays.fill(samples, value);
        return samples;
    }

    /**
     * Deterministic sample codes spanning the whole 8-bit range, including the
     * studio footroom and headroom that decode outside the output range. A fixed
     * multiplicative generator, not a random one: a colour bug that reproduces
     * only on some runs is a bug nobody fixes.
     */
    static int[] pseudoRandom(int count, int seed) {
        return pseudoRandom(count, seed, 8);
    }

    /**
     * The same generator at any depth. The low bits matter here and are not padding: codes that
     * were all multiples of four would decode identically whether the sampler read ten bits or
     * eight of them, and the whole 10-bit path would pass a test that proved nothing.
     */
    static int[] pseudoRandom(int count, int seed, int bitDepth) {
        int mask = (1 << bitDepth) - 1;
        int[] samples = new int[count];
        int state = seed * 2 + 1;
        for (int index = 0; index < count; index++) {
            state = state * 1_103_515_245 + 12_345;
            samples[index] = (state >>> 16) & mask;
        }
        return samples;
    }
}

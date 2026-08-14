package limn.video;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Converts a decoded picture to 8-bit RGBA on the CPU: the reference implementation of the
 * colour arithmetic, and the path used where no device conversion is available.
 *
 * <p><b>Any bit depth in, eight bits out.</b> The matrix is worked in the picture's own code space,
 * which is what {@link VideoColor}'s depth-taking accessors describe, and the result is scaled to
 * {@code [0..255]} at the very end, so a 10-bit picture is decoded at 10 bits and quantized once,
 * rather than being quantized to eight and then decoded. The output is deliberately eight bits
 * because this path exists to produce an ordinary RGBA image; a consumer that wants the extra
 * precision uploads the planes to a device instead.
 *
 * <p>Allocation-free: the destination is supplied by the caller and reused, and the conversion
 * itself creates nothing. A 1080p picture is about 8 MB of output, so allocating one per picture
 * is roughly 250 MB a second at 30 per second.
 *
 * <p>Chroma is upsampled by <b>replication</b>: the chroma sample for a pixel is the one at that
 * pixel's coordinates shifted down by the format's subsampling, with no interpolation. That is
 * exact for odd sizes by construction, because a chroma plane's dimensions are the frame's rounded
 * up. It is also reproducible elsewhere: a device sampler in nearest-neighbour mode produces
 * identical results, whereas interpolation weights differ between implementations and could not be
 * matched exactly. Interpolating additionally requires knowing where chroma samples sit relative to
 * luma, which a stream signals and which nothing here carries.
 *
 * <p>Output is straight (non-premultiplied) RGBA, row-major, row 0 at the top, alpha a constant
 * 255. Each channel is rounded half-up and then clamped to {@code [0..255]}; the clamp is not
 * defensive, because legal studio-range codes decode outside that interval by design and a cast
 * without it wraps a highlight to a dark speckle.
 */
public final class YuvConverter {

    private YuvConverter() {
    }

    /**
     * Converts the whole of {@code frame} into {@code dst}.
     *
     * <p>Sample codes are read unsigned; the frame's own layout supplies the plane geometry, so a
     * caller never re-derives a chroma size and cannot round it differently from the producer that
     * filled it. The planes are read by absolute index, so their position and limit are left exactly
     * as they were and a caller may convert a frame whose planes it is also reading relatively.
     *
     * @param dst       destination, at least {@code dstOffset + dstStride * (height - 1) +
     *                  width * 4} bytes; never allocated here and never resized
     * @param dstOffset index in {@code dst} of the first byte of row 0, at least 0
     * @param dstStride bytes between the starts of consecutive destination rows, at least
     *                  {@code width * 4}
     * @throws IllegalStateException    if the frame has been released
     * @throws IllegalArgumentException if the stride is below the row's byte width, the offset is
     *                                  negative, or the destination is too small
     * @throws NullPointerException     if {@code frame} or {@code dst} is null
     */
    public static void toRgba8(VideoFrame frame, byte[] dst, int dstOffset, int dstStride) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(dst, "dst");

        PixelFormat format = frame.format();
        VideoColor color = frame.color();
        int width = frame.width();
        int height = frame.height();

        // The interleaved chroma plane of a two-plane format carries Cb then Cr in one sample, so
        // both roles read the same buffer at the same step with Cr one byte along. Addressing the
        // planes by role here is what makes the Cb/Cr swap a layout question and not an arithmetic
        // one.
        boolean interleaved = format.planeCount() == 2;
        ByteBuffer lumaPlane = frame.plane(0);
        ByteBuffer cbPlane = frame.plane(1);
        ByteBuffer crPlane = interleaved ? cbPlane : frame.plane(2);
        int lumaStride = frame.stride(0);
        int cbStride = frame.stride(1);
        int crStride = interleaved ? cbStride : frame.stride(2);
        // Everything below counts BYTES, because a component is one byte at eight bits and two at
        // ten. The two are different distances and conflating them is what turns a 10-bit picture
        // into diagonal noise: chromaStep is the gap between consecutive chroma SAMPLES, while
        // crByteOffset is the gap from a sample's Cb to its Cr inside an interleaved one.
        int lumaStep = format.bytesPerSample(0);
        int chromaStep = format.bytesPerSample(1);
        int componentBytes = chromaStep / format.componentsPerSample(1);
        int crByteOffset = interleaved ? componentBytes : 0;

        if (dstOffset < 0) {
            throw new IllegalArgumentException("dstOffset must be at least 0, got " + dstOffset);
        }
        if (dstStride < width * 4) {
            throw new IllegalArgumentException(
                    "dstStride " + dstStride + " is below the row's byte width " + (width * 4));
        }
        long needed = (long) dstOffset + (long) dstStride * (height - 1) + (long) width * 4;
        if (needed > dst.length) {
            throw new IllegalArgumentException(
                    "destination needs " + needed + " bytes, holds " + dst.length);
        }

        int shiftX = format.chromaShiftX();
        int shiftY = format.chromaShiftY();
        int bitDepth = format.bitDepth();
        int maxCode = format.maxCode();
        double yScale = color.yScale(bitDepth);
        int yOffset = color.yOffset(bitDepth);
        int neutral = color.chromaNeutral(bitDepth);
        double crToR = color.crToR(bitDepth);
        double cbToG = color.cbToG(bitDepth);
        double crToG = color.crToG(bitDepth);
        double cbToB = color.cbToB(bitDepth);
        // The whole matrix runs in the picture's code space and the result is brought to eight bits
        // here, at the end. Quantizing the samples first instead would throw the extra bits away
        // before the arithmetic that needs them.
        double outputScale = 255.0 / maxCode;

        for (int row = 0; row < height; row++) {
            int lumaRow = row * lumaStride;
            int chromaRow = row >> shiftY;
            int cbRow = chromaRow * cbStride;
            int crRow = chromaRow * crStride + crByteOffset;
            int out = dstOffset + row * dstStride;
            int lastChromaColumn = -1;
            double redChroma = 0;
            double greenChroma = 0;
            double blueChroma = 0;
            for (int column = 0; column < width; column++) {
                int chromaColumn = column >> shiftX;
                if (chromaColumn != lastChromaColumn) {
                    lastChromaColumn = chromaColumn;
                    int sample = chromaColumn * chromaStep;
                    int cb = format.componentAt(cbPlane, cbRow + sample) - neutral;
                    int cr = format.componentAt(crPlane, crRow + sample) - neutral;
                    redChroma = crToR * cr;
                    greenChroma = cbToG * cb + crToG * cr;
                    blueChroma = cbToB * cb;
                }
                double luma = yScale
                        * (format.componentAt(lumaPlane, lumaRow + column * lumaStep) - yOffset);
                dst[out] = (byte) clampRound((luma + redChroma) * outputScale, 255);
                dst[out + 1] = (byte) clampRound((luma + greenChroma) * outputScale, 255);
                dst[out + 2] = (byte) clampRound((luma + blueChroma) * outputScale, 255);
                dst[out + 3] = (byte) 255;
                out += 4;
            }
        }
    }

    /**
     * Converts one YCbCr triple, for a test or a single-pixel probe. Codes are taken as unsigned
     * values in {@code [0..(1 << bitDepth) - 1]}; results outside that range are clamped, so
     * footroom and headroom codes give clamped black and white rather than wrapping.
     *
     * <p>The output stays in the <em>picture's</em> code space rather than being brought to eight
     * bits: this is the matrix alone, which is what makes it the reference
     * {@link #toRgba8(VideoFrame, byte[], int, int)} is asserted against; that method is this
     * arithmetic followed by one scale to eight bits.
     *
     * @param bitDepth valid bits per component, in {@code [8..16]} ({@link PixelFormat#bitDepth()})
     * @param out      a four-element array receiving red, green, blue and an opaque alpha, each in
     *                 {@code [0..(1 << bitDepth) - 1]}
     * @throws IllegalArgumentException if any code is outside the depth's range, {@code bitDepth}
     *                                  is outside {@code [8..16]}, or {@code out} is shorter than
     *                                  four
     * @throws NullPointerException     if {@code color} or {@code out} is null
     */
    public static void convertPixel(VideoColor color, int bitDepth, int y, int cb, int cr,
                                    int[] out) {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(out, "out");
        if (out.length < 4) {
            throw new IllegalArgumentException("out must hold at least 4 values, holds " + out.length);
        }
        int neutral = color.chromaNeutral(bitDepth); // also validates the depth
        int maxCode = (1 << bitDepth) - 1;
        checkCode(y, "y", maxCode);
        checkCode(cb, "cb", maxCode);
        checkCode(cr, "cr", maxCode);

        int cbDelta = cb - neutral;
        int crDelta = cr - neutral;
        double luma = color.yScale(bitDepth) * (y - color.yOffset(bitDepth));
        // The two chroma terms are summed before the luma is added, which is the grouping the bulk
        // path uses because it computes them once per chroma sample. Adding them one at a time
        // instead re-associates the arithmetic and lands on the other side of a rounding tie for
        // some codes, and this method is the reference the bulk path is asserted against.
        out[0] = clampRound(luma + color.crToR(bitDepth) * crDelta, maxCode);
        out[1] = clampRound(luma + (color.cbToG(bitDepth) * cbDelta + color.crToG(bitDepth) * crDelta),
                maxCode);
        out[2] = clampRound(luma + color.cbToB(bitDepth) * cbDelta, maxCode);
        out[3] = maxCode;
    }

    private static void checkCode(int code, String name, int maxCode) {
        if (code < 0 || code > maxCode) {
            throw new IllegalArgumentException(
                    name + " must be in [0.." + maxCode + "], got " + code);
        }
    }

    /**
     * Rounds half-up and then clamps. Half-up rather than half-even because an integer or vector
     * rewrite adds a half and shifts, and half-even would stop matching it; the clamp comes after
     * the whole matrix because studio footroom and headroom decode outside the output range by
     * design, and a bare cast wraps a highlight to a dark speckle.
     */
    private static int clampRound(double value, int maxCode) {
        long rounded = Math.round(value);
        return rounded < 0 ? 0 : rounded > maxCode ? maxCode : (int) rounded;
    }
}

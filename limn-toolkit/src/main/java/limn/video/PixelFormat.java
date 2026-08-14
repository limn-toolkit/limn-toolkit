package limn.video;

/**
 * The memory layout of a decoded {@link VideoFrame}: how many planes it has, what each one
 * holds, and how each plane's sample grid is derived from the frame size. Every producer and
 * every consumer computes plane geometry from here, so an odd frame size cannot be rounded one
 * way by the code that fills a plane and another way by the code that reads it; that mismatch
 * renders as a coloured stripe down one edge, nowhere near whichever side rounded wrong.
 *
 * <p>Layout only. Nothing here says what the sample values <em>mean</em>: matrix and range live
 * on {@link VideoColor}, and the same I420 buffer is BT.601 or BT.709 depending on it.
 *
 * <p>Plane 0 is always full-resolution luma. Plane sizes are counted in samples of that plane's
 * own grid; bytes are a separate question, because NV12 stores two components per chroma sample
 * and a 10-bit sample occupies two bytes whatever the layout.
 *
 * <p><b>A code and its storage word are not the same number.</b> Most layouts store a code
 * right-justified, so the two coincide; {@link #P010} stores it shifted up to the top of a 16-bit
 * word, which is what a hardware decoder produces. {@link #componentAt} and {@link #putComponent}
 * are the only place that difference is spelled, so a consumer that reads samples through them is
 * right for both and one that reads the bytes itself is right for one of them; see
 * {@link #codeShift()}.
 *
 * <p><b>Bit depth is not a detail of the samples, it is a property of the arithmetic.</b> Every
 * size here is derived through {@link #bytesPerSample(int)}, so a consumer that comes through these
 * methods is depth-correct and one that multiplies a width by a component count is not. The
 * decode matrix is depth-dependent too, which is why {@link VideoColor}'s coefficient accessors
 * take a bit depth rather than answering for eight bits and hoping.
 */
public enum PixelFormat {

    /**
     * 8-bit 4:2:0 in three planes: Y, then Cb, then Cr, each planar, each chroma plane half
     * resolution in both directions. What a software H.264 / VP9 / AV1 decode hands back.
     */
    I420(1, 1, 8, 0, 1, 1, 1),

    /**
     * 8-bit 4:2:0 in two planes: Y, then Cb and Cr <em>interleaved</em> in a single plane whose
     * sample <i>n</i> is the byte pair (Cb, Cr), in that order. That plane is 2 bytes per sample,
     * so its byte width is the frame width rounded up to an even number: for an odd width it is
     * one byte <em>wider</em> than the luma plane's, which is the arithmetic that defeats code
     * assuming "chroma rows are smaller". What hardware decoders and capture devices produce.
     */
    NV12(1, 1, 8, 0, 1, 2),

    /**
     * 8-bit 4:4:4 in three planes: Y, Cb, Cr, all full resolution. Every plane's geometry equals
     * the frame's, so chroma subsampling never applies. Screen capture and lossless intermediates.
     */
    I444(0, 0, 8, 0, 1, 1, 1),

    /**
     * 10-bit 4:2:0 in three planes, laid out exactly as {@link #I420} but with a sample occupying
     * two bytes. What HEVC Main 10, VP9 Profile 2 and AV1 Main 10 hand back, and by a wide margin
     * the commonest way a file carries more than eight bits.
     *
     * <p><b>A sample is a code in {@code [0..1023]} stored right-justified in a little-endian
     * 16-bit word</b>: low byte first, and the top six bits zero. It is not a normalized 16-bit
     * value, so reading one as though the plane were 16-bit content gives a picture 64 times too
     * dark, and normalizing by 65535 instead of by {@link #maxCode()} gives one 64 times too
     * bright. Both look like a broken shader rather than like an off-by-a-factor.
     */
    I420_10LE(1, 1, 10, 0, 1, 1, 1),

    /**
     * 10-bit 4:4:4 in three planes: {@link #I444}'s geometry with {@link #I420_10LE}'s samples.
     * High-end intermediates and screen capture that keep both the chroma resolution and the depth.
     */
    I444_10LE(0, 0, 10, 0, 1, 1, 1),

    /**
     * 10-bit 4:2:0 in two planes: {@link #NV12}'s geometry with ten bits per component. What every
     * <em>hardware</em> decoder produces for 10-bit content, and the only layout here whose samples
     * a producer other than this repository's own decoders creates.
     *
     * <p><b>Its codes are left-justified</b>, which is the whole reason it is a layout of its own
     * rather than a wider NV12: the ten bits live in the <em>top</em> ten of the 16-bit word and the
     * bottom six are zero, where {@link #I420_10LE} and {@link #I444_10LE} put them in the bottom
     * ten. So the same 16-bit word means two different codes depending on the layout, and reading a
     * P010 word as though it were an I420_10LE one is a picture 64 times too bright. Nothing in the
     * geometry says so (the sizes, the strides and the plane count are NV12's doubled), which is
     * why the justification is carried on {@link #codeShift()} and applied by
     * {@link #componentAt} and {@link #putComponent} rather than left to each reader.
     */
    P010(1, 1, 10, 6, 1, 2);

    /**
     * Largest frame width or height any geometry method accepts, in pixels. Above every coded size
     * in use, and low enough that the rounding-up arithmetic inside those methods cannot wrap an
     * {@code int}, which is the reason this is enforced rather than assumed.
     */
    public static final int MAX_DIMENSION = 65_535;

    private final int chromaShiftX;
    private final int chromaShiftY;
    private final int bitDepth;
    private final int codeShift;
    private final int[] components;

    PixelFormat(int chromaShiftX, int chromaShiftY, int bitDepth, int codeShift, int... components) {
        this.chromaShiftX = chromaShiftX;
        this.chromaShiftY = chromaShiftY;
        this.bitDepth = bitDepth;
        this.codeShift = codeShift;
        this.components = components;
    }

    /** @return separately addressed planes: 3 for I420 and I444, 2 for NV12 */
    public int planeCount() {
        return components.length;
    }

    /** @return valid bits per component: 8 or 10 */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * The largest sample code this layout can carry: 255 at eight bits, 1023 at ten. It is the
     * divisor that turns a code into a normalized value, and it is <em>not</em> the largest value
     * the sample's storage can hold: a 10-bit sample lives in a 16-bit word whose upper six bits
     * are always zero, so normalizing by 65535 is the mistake this accessor exists to prevent.
     *
     * @return {@code (1 << bitDepth()) - 1}
     */
    public int maxCode() {
        return (1 << bitDepth) - 1;
    }

    /**
     * How far a code sits above the bottom of its storage word: 0 for every right-justified layout
     * and 6 for {@link #P010}, whose ten bits occupy the top of a 16-bit word.
     *
     * <p>Two consumers need it and no third one should. {@link #componentAt} and
     * {@link #putComponent} apply it, so every reader that comes through them works in codes and
     * never meets the shift at all. A device sampler does not come through them: it normalizes by
     * the <em>texel's</em> width, so a sampled value must be scaled by
     * {@code ((1 << storageBits) - 1) >> codeShift()} rather than by the storage maximum, and using
     * the storage maximum is a picture 64 times too dark.
     *
     * @return bits the code is shifted left inside its storage word; 0 unless the layout says
     *         otherwise
     */
    public int codeShift() {
        return codeShift;
    }

    /** @return log2 of horizontal chroma subsampling: 1 for 4:2:0, 0 for 4:4:4; luma is never subsampled */
    public int chromaShiftX() {
        return chromaShiftX;
    }

    /** @return log2 of vertical chroma subsampling: 1 for 4:2:0, 0 for 4:4:4 */
    public int chromaShiftY() {
        return chromaShiftY;
    }

    /**
     * @return components stored per sample of {@code plane}: 1 everywhere except NV12's plane 1,
     *         which is 2 (Cb then Cr, adjacent)
     * @throws IndexOutOfBoundsException if {@code plane} is negative or at least {@link #planeCount()}
     */
    public int componentsPerSample(int plane) {
        checkPlane(plane);
        return components[plane];
    }

    /**
     * Bytes occupied by one sample of {@code plane}. The only place bit depth turns into bytes, and
     * the reason to come through it is that the two things it multiplies together stopped agreeing
     * once a sample could be ten bits wide: a 10-bit luma sample is two bytes and one component,
     * where NV12's chroma sample is two bytes and two components. Anything that derives a channel
     * count from this number is right for one of those and wrong for the other.
     *
     * @return {@link #componentsPerSample(int)} times the bytes one component occupies
     * @throws IndexOutOfBoundsException if {@code plane} is not a plane of this format
     */
    public int bytesPerSample(int plane) {
        return componentsPerSample(plane) * ((bitDepth + 7) / 8);
    }

    /**
     * Reads one component out of a plane's bytes, at an absolute byte index and without moving the
     * buffer's position. The only place this project spells the byte order of a wide sample: a
     * 10-bit component is little-endian, so a reader that assembles it the other way round turns a
     * dark grey into a bright noise pattern, everywhere at once, in a way that looks like the plane
     * pointer being wrong. It is also the only place {@link #codeShift()} is applied, so what comes
     * back is a code and never a storage word.
     *
     * @param plane     the plane's bytes, at least {@code byteIndex + bytesPerSample} long
     * @param byteIndex byte offset of the component within {@code plane}
     * @return the code, in {@code [0..maxCode()]}
     * @throws IndexOutOfBoundsException if the component does not lie inside the buffer
     * @throws NullPointerException      if {@code plane} is null
     */
    public int componentAt(java.nio.ByteBuffer plane, int byteIndex) {
        int low = plane.get(byteIndex) & 0xFF;
        if (bitDepth <= 8) {
            return low;
        }
        // No mask beyond the shift: a 16-bit word shifted down by P010's six bits is already ten
        // bits wide, and a right-justified layout's word is returned exactly as it lies. Masking
        // here would turn a plane read at the wrong justification into a plausible picture instead
        // of an obviously wrong one, which is the failure this whole accessor exists to make loud.
        int word = low | ((plane.get(byteIndex + 1) & 0xFF) << 8);
        return word >>> codeShift;
    }

    /**
     * Writes one component into a plane's bytes, at an absolute byte index and without moving the
     * buffer's position: the inverse of {@link #componentAt}, and the only place a producer needs
     * to know how wide a sample is.
     *
     * @param code a value in {@code [0..maxCode()]}; higher bits are dropped rather than checked,
     *             because this runs once per sample of every picture
     * @throws IndexOutOfBoundsException if the component does not lie inside the buffer
     * @throws java.nio.ReadOnlyBufferException if {@code plane} is a read-only view
     * @throws NullPointerException      if {@code plane} is null
     */
    public void putComponent(java.nio.ByteBuffer plane, int byteIndex, int code) {
        int stored = (code & maxCode()) << codeShift;
        plane.put(byteIndex, (byte) stored);
        if (bitDepth > 8) {
            plane.put(byteIndex + 1, (byte) (stored >> 8));
        }
    }

    /**
     * Samples across one row of {@code plane} for a frame {@code frameWidth} pixels wide. Chroma
     * rounds <em>up</em>: a 5-pixel-wide 4:2:0 frame has 3 chroma samples per row, not 2, because
     * the last luma column still needs a chroma sample to pair with. This is a sample count, not a
     * byte count.
     *
     * @throws IllegalArgumentException  if {@code frameWidth} is outside {@code [1..MAX_DIMENSION]}
     * @throws IndexOutOfBoundsException if {@code plane} is not a plane of this format
     */
    public int planeWidth(int plane, int frameWidth) {
        checkPlane(plane);
        checkDimension(frameWidth, "frameWidth");
        int shift = plane == 0 ? 0 : chromaShiftX;
        return (frameWidth + (1 << shift) - 1) >> shift;
    }

    /**
     * @return rows in {@code plane} for a frame {@code frameHeight} pixels tall, chroma rounding up
     * @throws IllegalArgumentException  if {@code frameHeight} is outside {@code [1..MAX_DIMENSION]}
     * @throws IndexOutOfBoundsException if {@code plane} is not a plane of this format
     */
    public int planeHeight(int plane, int frameHeight) {
        checkPlane(plane);
        checkDimension(frameHeight, "frameHeight");
        int shift = plane == 0 ? 0 : chromaShiftY;
        return (frameHeight + (1 << shift) - 1) >> shift;
    }

    /**
     * Bytes of picture in one row of {@code plane}: the part of a row a consumer may read. A row
     * occupies a stride of bytes, of which only these carry samples; the rest is padding whose
     * contents are undefined and which must never be uploaded, compared or hashed.
     *
     * @return {@link #planeWidth(int, int)} times {@link #bytesPerSample(int)}
     * @throws IllegalArgumentException  if {@code frameWidth} is outside {@code [1..MAX_DIMENSION]}
     * @throws IndexOutOfBoundsException if {@code plane} is not a plane of this format
     */
    public int planeByteWidth(int plane, int frameWidth) {
        return planeWidth(plane, frameWidth) * bytesPerSample(plane);
    }

    /**
     * Smallest buffer that can hold {@code plane} at this size and stride: every row but the last
     * occupies a full {@code strideBytes}, and the last row needs only its byte width. Trailing
     * padding after the final sample is deliberately <em>not</em> required; a producer whose plane
     * ends exactly at the end of its last row is handing over a valid buffer, and demanding
     * {@code stride × rows} would force a copy on the one path that exists to avoid copies.
     *
     * <p>The consequence, which is the wrong edit this text exists to prevent: a plane may not be
     * bulk-copied as one {@code stride × rows} block. Copy row by row.
     *
     * <p>Returned as a {@code long} because at the maximum dimension a single plane exceeds the
     * range of an {@code int}, and a size check that overflowed to a negative number would accept
     * every buffer handed to it.
     *
     * @param strideBytes distance in bytes between the starts of consecutive rows
     * @throws IllegalArgumentException  if {@code strideBytes} is below the plane's byte width, or
     *                                   a dimension is outside {@code [1..MAX_DIMENSION]}
     * @throws IndexOutOfBoundsException if {@code plane} is not a plane of this format
     */
    public long minPlaneBytes(int plane, int frameWidth, int frameHeight, int strideBytes) {
        int byteWidth = planeByteWidth(plane, frameWidth);
        int rows = planeHeight(plane, frameHeight);
        if (strideBytes < byteWidth) {
            throw new IllegalArgumentException(
                    "strideBytes " + strideBytes + " is below plane " + plane
                            + "'s byte width " + byteWidth + " for " + this + " at width " + frameWidth);
        }
        return (long) strideBytes * (rows - 1) + byteWidth;
    }

    /**
     * The plane's byte width rounded up to a multiple of {@code alignBytes}: what a producer that
     * allocates its own planes should use as its stride, so rows start where wide loads and GPU
     * uploads want them.
     *
     * @param alignBytes a power of two in {@code [1..4096]}
     * @throws IllegalArgumentException  if {@code alignBytes} is not a power of two in that range,
     *                                   or {@code frameWidth} is outside {@code [1..MAX_DIMENSION]}
     * @throws IndexOutOfBoundsException if {@code plane} is not a plane of this format
     */
    public int alignedStride(int plane, int frameWidth, int alignBytes) {
        int byteWidth = planeByteWidth(plane, frameWidth);
        if (alignBytes < 1 || alignBytes > 4096 || Integer.bitCount(alignBytes) != 1) {
            throw new IllegalArgumentException(
                    "alignBytes must be a power of two in [1..4096], got " + alignBytes);
        }
        return (byteWidth + alignBytes - 1) & -alignBytes;
    }

    private void checkPlane(int plane) {
        if (plane < 0 || plane >= components.length) {
            throw new IndexOutOfBoundsException(
                    this + " has planes 0.." + (components.length - 1) + ", got " + plane);
        }
    }

    private static void checkDimension(int value, String name) {
        if (value < 1 || value > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                    name + " must be in [1.." + MAX_DIMENSION + "], got " + value);
        }
    }
}

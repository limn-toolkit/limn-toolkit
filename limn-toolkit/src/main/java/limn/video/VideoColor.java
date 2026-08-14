package limn.video;

import java.util.Objects;

/**
 * How the samples of a {@link VideoFrame} are to be turned into colour: which luma/chroma matrix
 * was used to encode them, whether the codes cover the full range or the studio range, and what
 * transfer function the encoded values carry. Layout is a separate question and lives on
 * {@link PixelFormat}: the same I420 buffer is BT.601 or BT.709 depending only on this.
 *
 * <p>Immutable and interned: one instance per matrix, range and transfer, plus
 * {@link #unspecified()}, and nothing else can be constructed. Reference comparison is therefore
 * exact and cheap, and the coefficient accessors are the one place the decode matrix exists; a CPU
 * converter and a GPU shader that both read them cannot drift apart.
 *
 * <p>The coefficient accessors give the folded, ready-to-use form for a picture of a given bit
 * depth: codes in {@code [0..maxCode]} in, RGB in {@code [0..maxCode]} out, as
 * <pre>{@code
 * int neutral = chromaNeutral(depth);
 * double y = yScale(depth) * (Y - yOffset(depth));
 * double r = y + crToR(depth) * (Cr - neutral);
 * double g = y + (cbToG(depth) * (Cb - neutral) + crToG(depth) * (Cr - neutral));
 * double b = y + cbToB(depth) * (Cb - neutral);
 * }</pre>
 * Green's two chroma terms are summed before the luma is added, as bracketed: adding them one at a
 * time re-associates the arithmetic and rounds some codes to a different integer, so a consumer
 * that regroups them stops matching every other consumer of this table.
 *
 * <p><b>Every accessor takes the bit depth and none of them assumes eight.</b> Studio black, the
 * chroma neutral and the studio gain all move with the depth, so a 10-bit picture decoded through
 * the 8-bit table is not slightly wrong; it is four times too bright with black four times too
 * high. The argument exists so that omitting it is a compile error rather than a picture.
 *
 * <p>The chroma neutral is an integer code, never a normalized 0.5; sampled as a normalized value
 * it is {@code 128/255} at eight bits, and using {@code 0.5} instead tints neutral grey by about a
 * code.
 *
 * <p>Any thread: every instance is immutable and every accessor is a field read or one multiply.
 */
public final class VideoColor {

    /** The luma/chroma matrix a stream was encoded with. */
    public enum Matrix {

        /** Rec. ITU-R BT.601: Kr 0.299, Kb 0.114. Standard-definition and motion-JPEG content. */
        BT601(0.299, 0.114),

        /** Rec. ITU-R BT.709: Kr 0.2126, Kb 0.0722. High-definition content, and the common default. */
        BT709(0.2126, 0.0722),

        /** Rec. ITU-R BT.2020 non-constant-luminance: Kr 0.2627, Kb 0.0593. Ultra-high-definition content. */
        BT2020(0.2627, 0.0593);

        private final double kr;
        private final double kb;

        Matrix(double kr, double kb) {
            this.kr = kr;
            this.kb = kb;
        }

        /** @return the red luma weight, exactly as the recommendation defines it */
        public double kr() {
            return kr;
        }

        /** @return the blue luma weight, exactly as the recommendation defines it */
        public double kb() {
            return kb;
        }

        /** @return the green luma weight, which is {@code 1 - kr() - kb()} and is never stored separately */
        public double kg() {
            return 1.0 - kr - kb;
        }
    }

    /**
     * Which codes a stream's samples occupy. The intervals below are quoted at eight bits; at any
     * other depth a studio level is that level shifted left by {@code bitDepth - 8}, so studio white
     * is 235 at eight bits and 940 at ten, while <em>full</em> range always occupies the whole code
     * space, which is why the two do not scale by the same factor.
     */
    public enum Range {

        /**
         * Studio range: luma in {@code [16..235]}, chroma in {@code [16..240]}. Codes outside those
         * intervals are legal footroom and headroom and must decode to clamped black and white
         * rather than being clamped on input, so that a range mix-up stays visible instead of being
         * silently absorbed.
         */
        LIMITED,

        /** Full range: luma and chroma over the whole code space, with the midpoint neutral. */
        FULL
    }

    /**
     * What the decoded values <em>are</em>, once the matrix has been undone: numbers a display can
     * show, or a curve that has to be inverted before they mean light.
     *
     * <p>This is the distinction that decides which composite a picture belongs in, and it is not
     * the same question as bit depth. A 10-bit BT.709 recording is ordinary display-referred video
     * that happens to carry more codes; a BT.2020 PQ one holds absolute luminance behind a curve
     * and is not viewable at all until that curve is inverted.
     */
    public enum Transfer {

        /**
         * The conventional standard-dynamic-range display curve: BT.709's camera curve viewed on a
         * BT.1886 display, and near enough sRGB that nothing here separates them. Values are
         * <b>display-referred</b>: they are already encoded for a screen, and anything that
         * tonemaps or re-encodes them applies a transform a second time.
         *
         * <p>Every transfer characteristic a stream can signal that is not PQ or HLG lands here,
         * including gamma 2.2, gamma 2.8, BT.601, sRGB and "unspecified". They differ from each
         * other by less than the difference between two displays, and none of them changes what a
         * consumer must <em>do</em>, which is nothing.
         */
        SDR,

        /**
         * SMPTE ST 2084, perceptual quantizer. The decoded value is a non-linear encoding of
         * absolute luminance up to 10000 cd/m², so it is scene-referred after its inverse and
         * belongs on a linear compositing path.
         */
        PQ,

        /**
         * ARIB STD-B67, hybrid log-gamma. Relative rather than absolute, and its inverse is the
         * SDR curve over the bottom half of the range and logarithmic above it, which is why an
         * HLG picture shown as if it were SDR looks nearly right in the shadows and washed out
         * everywhere else, the hardest of these to spot by eye.
         */
        HLG
    }

    /**
     * Luma gain for studio range: the 255-code output span divided by the 219-code input span. The
     * divisor is the interval length {@code 235 - 16}, not a level count; 220 would decode white
     * to 254, invisible by eye and fatal to any exact comparison.
     */
    private static final double LIMITED_Y_SCALE = 1.16438356164383561;

    private static final double FULL_Y_SCALE = 1.0;

    private static final int LIMITED_Y_OFFSET = 16;

    private static final int FULL_Y_OFFSET = 0;

    /** The chroma code carrying no colour difference at eight bits, in every range. */
    private static final int CHROMA_NEUTRAL = 128;

    /** Lowest and highest bit depth the folded table is defined for. */
    private static final int MIN_BIT_DEPTH = 8;
    private static final int MAX_BIT_DEPTH = 16;

    /** BT.601 with studio range. Standard-definition broadcast content. */
    public static final VideoColor BT601_LIMITED = new VideoColor(Matrix.BT601, Range.LIMITED, true,
            1.59602678571428580, -0.39176229009491359, -0.81296764723777071, 2.01723214285714292);

    /** BT.601 with full range. The motion-JPEG convention. */
    public static final VideoColor BT601_FULL = new VideoColor(Matrix.BT601, Range.FULL, true,
            1.40199999999999991, -0.34413628620102216, -0.71413628620102210, 1.77200000000000002);

    /** BT.709 with studio range. High-definition broadcast content. */
    public static final VideoColor BT709_LIMITED = new VideoColor(Matrix.BT709, Range.LIMITED, true,
            1.79274107142857142, -0.21324861427372963, -0.53290932855944395, 2.11240178571428583);

    /** BT.709 with full range. High-definition content encoded over the whole code span. */
    public static final VideoColor BT709_FULL = new VideoColor(Matrix.BT709, Range.FULL, true,
            1.57479999999999998, -0.18732427293064877, -0.46812427293064879, 1.85559999999999992);

    /** BT.2020 with studio range. Ultra-high-definition broadcast content. */
    public static final VideoColor BT2020_LIMITED = new VideoColor(Matrix.BT2020, Range.LIMITED, true,
            1.67867410714285725, -0.18732610421934259, -0.65042431850505689, 2.14177232142857132);

    /** BT.2020 with full range. */
    public static final VideoColor BT2020_FULL = new VideoColor(Matrix.BT2020, Range.FULL, true,
            1.47459999999999991, -0.16455312684365780, -0.57135312684365780, 1.88139999999999996);

    /**
     * Every signalled combination, indexed by {@link #index}. The six named constants above are the
     * {@link Transfer#SDR} entries, and the PQ and HLG ones are built from them by copying rather
     * than by retyping the coefficients: a transfer function changes what the decoded values mean
     * and changes not one number of the matrix that produces them.
     */
    private static final VideoColor[] INTERNED = intern();

    private static final VideoColor UNSPECIFIED = new VideoColor(BT709_LIMITED, Transfer.SDR, false);

    private final Matrix matrix;
    private final Range range;
    private final Transfer transfer;
    private final boolean specified;
    private final double yScale;
    private final int yOffset;
    private final double crToR;
    private final double cbToG;
    private final double crToG;
    private final double cbToB;

    private VideoColor(Matrix matrix, Range range, boolean specified,
                       double crToR, double cbToG, double crToG, double cbToB) {
        this.matrix = matrix;
        this.range = range;
        this.transfer = Transfer.SDR;
        this.specified = specified;
        this.yScale = range == Range.LIMITED ? LIMITED_Y_SCALE : FULL_Y_SCALE;
        this.yOffset = range == Range.LIMITED ? LIMITED_Y_OFFSET : FULL_Y_OFFSET;
        this.crToR = crToR;
        this.cbToG = cbToG;
        this.crToG = crToG;
        this.cbToB = cbToB;
    }

    /**
     * Copies every decode number from {@code source} and changes only what this constructor is for,
     * so neither the unspecified instance nor a PQ variant can drift from the constant it is
     * defined to decode as. Retyping those literals would make that drift possible and invisible.
     */
    private VideoColor(VideoColor source, Transfer transfer, boolean specified) {
        this.matrix = source.matrix;
        this.range = source.range;
        this.transfer = transfer;
        this.specified = specified;
        this.yScale = source.yScale;
        this.yOffset = source.yOffset;
        this.crToR = source.crToR;
        this.cbToG = source.cbToG;
        this.crToG = source.crToG;
        this.cbToB = source.cbToB;
    }

    private static VideoColor[] intern() {
        VideoColor[] sdr = {
            BT601_LIMITED, BT601_FULL, BT709_LIMITED, BT709_FULL, BT2020_LIMITED, BT2020_FULL,
        };
        VideoColor[] all = new VideoColor[sdr.length * Transfer.values().length];
        for (Matrix matrix : Matrix.values()) {
            for (Range range : Range.values()) {
                VideoColor base = sdr[matrix.ordinal() * Range.values().length + range.ordinal()];
                for (Transfer transfer : Transfer.values()) {
                    all[index(matrix, range, transfer)] = transfer == Transfer.SDR
                            ? base
                            : new VideoColor(base, transfer, true);
                }
            }
        }
        return all;
    }

    private static int index(Matrix matrix, Range range, Transfer transfer) {
        return (matrix.ordinal() * Range.values().length + range.ordinal())
                * Transfer.values().length + transfer.ordinal();
    }

    /**
     * @return the interned instance for {@code matrix} and {@code range} with a
     *         {@link Transfer#SDR} transfer; never a new object
     * @throws NullPointerException if either argument is null
     */
    public static VideoColor of(Matrix matrix, Range range) {
        return of(matrix, range, Transfer.SDR);
    }

    /**
     * @return the interned instance for {@code matrix}, {@code range} and {@code transfer}; never a
     *         new object
     * @throws NullPointerException if any argument is null
     */
    public static VideoColor of(Matrix matrix, Range range, Transfer transfer) {
        Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(transfer, "transfer");
        return INTERNED[index(matrix, range, transfer)];
    }

    /**
     * @return the interned instance with this matrix and range and {@code transfer}'s curve, or
     *         {@code this} when the transfer already matches. An unsignalled interpretation given a
     *         transfer becomes a signalled one, because a stream that said PQ said something.
     * @throws NullPointerException if {@code transfer} is null
     */
    public VideoColor withTransfer(Transfer transfer) {
        Objects.requireNonNull(transfer, "transfer");
        return transfer == this.transfer ? this : of(matrix, range, transfer);
    }

    /**
     * The interpretation for a stream that carried no colour information at all. Decodes exactly as
     * {@link #BT709_LIMITED} under {@link Transfer#SDR}, so a caller that ignores the distinction
     * still gets the common case, but reports {@code false} from {@link #isSpecified()} so a caller
     * that can do better may. A distinct instance, so reference comparison separates "unsignalled"
     * from "signalled as BT.709 limited"; a frame always has a decodable interpretation and this
     * is never null.
     *
     * <p><b>An unsignalled transfer means SDR, and that is a choice rather than an absence.</b>
     * Nearly every file that signals nothing is ordinary display-referred video, and a picture
     * assumed SDR that is really PQ is visibly wrong the instant it is shown; the opposite
     * assumption would make every untagged file wrong instead, all the time. So the fallback is the
     * one that is right almost always and loud when it is not.
     *
     * @return the interned unspecified instance
     */
    public static VideoColor unspecified() {
        return UNSPECIFIED;
    }

    /** @return the encoding matrix; {@link Matrix#BT709} for {@link #unspecified()} */
    public Matrix matrix() {
        return matrix;
    }

    /** @return the code range; {@link Range#LIMITED} for {@link #unspecified()} */
    public Range range() {
        return range;
    }

    /** @return the transfer function the decoded values carry; {@link Transfer#SDR} unless signalled */
    public Transfer transfer() {
        return transfer;
    }

    /**
     * Whether the decoded values are already numbers a display can show, which is the question that
     * decides whether a consumer may composite them as an ordinary picture or owes them the inverse
     * of a curve first.
     *
     * @return true exactly when {@link #transfer()} is {@link Transfer#SDR}
     */
    public boolean isDisplayReferred() {
        return transfer == Transfer.SDR;
    }

    /**
     * @return whether a stream actually signalled this interpretation, as opposed to it being the
     *         fallback a stream that signalled nothing decodes as
     */
    public boolean isSpecified() {
        return specified;
    }

    /**
     * @param bitDepth valid bits per component, in {@code [8..16]} ({@link PixelFormat#bitDepth()})
     * @return the luma code that decodes to 0: {@code 16 << (bitDepth - 8)} for studio range, 0 for
     *         full range. Studio black is 16 at eight bits and 64 at ten, not 16 at both.
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public int yOffset(int bitDepth) {
        return yOffset << shift(bitDepth);
    }

    /**
     * @param bitDepth valid bits per component, in {@code [8..16]}
     * @return the chroma code carrying no colour difference: {@code 1 << (bitDepth - 1)}, so 128 at
     *         eight bits and 512 at ten, in every range
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public int chromaNeutral(int bitDepth) {
        return CHROMA_NEUTRAL << shift(bitDepth);
    }

    /**
     * @param bitDepth valid bits per component, in {@code [8..16]}
     * @return luma gain, code to {@code [0..maxCode]}: exactly 1 for full range, and for studio
     *         range the output span over the input span, {@code 255/219} at eight bits and
     *         {@code 1023/876} at ten, which are <em>not</em> the same number
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public double yScale(int bitDepth) {
        return yScale * codeScale(bitDepth);
    }

    /**
     * @param bitDepth valid bits per component, in {@code [8..16]}
     * @return the Cr contribution to red, per unit of {@code Cr - chromaNeutral(bitDepth)}
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public double crToR(int bitDepth) {
        return crToR * codeScale(bitDepth);
    }

    /**
     * @param bitDepth valid bits per component, in {@code [8..16]}
     * @return the Cb contribution to green, per unit of {@code Cb - chromaNeutral(bitDepth)};
     *         always negative
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public double cbToG(int bitDepth) {
        return cbToG * codeScale(bitDepth);
    }

    /**
     * @param bitDepth valid bits per component, in {@code [8..16]}
     * @return the Cr contribution to green, per unit of {@code Cr - chromaNeutral(bitDepth)};
     *         always negative
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public double crToG(int bitDepth) {
        return crToG * codeScale(bitDepth);
    }

    /**
     * @param bitDepth valid bits per component, in {@code [8..16]}
     * @return the Cb contribution to blue, per unit of {@code Cb - chromaNeutral(bitDepth)}
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public double cbToB(int bitDepth) {
        return cbToB * codeScale(bitDepth);
    }

    /**
     * What carries the eight-bit folded table to another depth, and the reason there is still one
     * table rather than one per depth.
     *
     * <p>Full range's spans are the whole code space at every depth, so nothing moves. Studio
     * range's are {@code 219 << (n-8)} and {@code 224 << (n-8)} while the <em>output</em> span is
     * {@code (1 << n) - 1}, and those two do not scale together: four times 255 is 1020 and the
     * ten-bit maximum is 1023. That three-code difference is the whole of this factor, it is
     * exactly 1 at eight bits (so every eight-bit number is bit-identical to the literal above),
     * and leaving it out decodes ten-bit white to 1020 instead of 1023.
     */
    private double codeScale(int bitDepth) {
        int shift = shift(bitDepth);
        if (range == Range.FULL) {
            return 1.0;
        }
        return ((1 << bitDepth) - 1) / (double) (255 << shift);
    }

    private static int shift(int bitDepth) {
        if (bitDepth < MIN_BIT_DEPTH || bitDepth > MAX_BIT_DEPTH) {
            throw new IllegalArgumentException(
                    "bitDepth must be in [" + MIN_BIT_DEPTH + ".." + MAX_BIT_DEPTH + "], got "
                            + bitDepth);
        }
        return bitDepth - MIN_BIT_DEPTH;
    }

    /**
     * Never throws: {@code VideoColor[BT709 LIMITED]}, {@code VideoColor[BT2020 LIMITED PQ]}, or
     * {@code VideoColor[unspecified]}. The transfer is printed only when it is not the SDR one, so
     * the common case reads as it always has.
     */
    @Override
    public String toString() {
        if (!specified) {
            return "VideoColor[unspecified]";
        }
        return "VideoColor[" + matrix + " " + range
                + (transfer == Transfer.SDR ? "" : " " + transfer) + "]";
    }
}

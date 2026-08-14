package limn.video.decode;

/**
 * What a {@linkplain SyntheticVideoDecoder synthetic} stream draws. Every pattern is a pure function
 * of a pixel's coordinates, the picture size and the picture's index in the stream, so any sample of
 * any picture can be worked out in advance and a rendered frame can be compared against arithmetic
 * instead of against a reference image.
 *
 * <p>All three accessors take a <b>luma</b> coordinate, including the two chroma ones. A chroma
 * sample of a subsampled layout is the value at the luma pixel it covers the top-left of (the
 * pixel a nearest-neighbour upsampler reads back), so the same three functions describe I420, NV12
 * and I444 without a layout ever entering the arithmetic. The visible consequence at 4:2:0 is a
 * one-pixel colour fringe wherever a pattern's colour changes on an odd column, which is what real
 * subsampled video does and is deliberately not smoothed away.
 *
 * <p>The values are <em>codes</em>, not colours, and every accessor takes the bit depth they are to
 * be expressed in. The bars carry the studio-range BT.709 code table shifted up to that depth (235
 * at eight bits is 940 at ten, which is what studio white actually is there), so they read as the
 * familiar bar sequence under a limited-range interpretation and as something visibly wrong under a
 * full-range one, at either depth. That is the point of being able to ask for any
 * {@link limn.video.VideoColor}: a matrix or range regression shows up as the wrong bars rather
 * than as nothing at all.
 *
 * <p>{@link #GRADIENT} is the one pattern whose <em>shape</em> depends on the depth, deliberately:
 * it wraps at the end of the code space, so the same picture size shows several ramps at eight bits
 * and fewer at ten. That is the only way to make four times as many codes visible on a screen that
 * has eight bits of its own.
 */
public enum SyntheticPattern {

    /**
     * Eight vertical colour bars (white, yellow, cyan, green, magenta, red, blue, black) that do
     * not move. Bar <i>b</i> covers the columns where {@code x * 8 / width} is <i>b</i>, so at a
     * width that is not a multiple of eight the bars differ in width by a pixel rather than the last
     * one being short.
     */
    BARS,

    /**
     * A gradient that moves one code per picture, in every channel and in a different direction in
     * each: luma along both axes, Cb across, Cr down and backwards. It wraps at the end of the code
     * space rather than clamping, so every code including the studio footroom and headroom is
     * exercised, and a picture differs from its neighbour everywhere; a stream that shows the same
     * picture twice or steps by the wrong number is visible in a single still.
     *
     * <p>Because it wraps at the code space and steps one code per column, <b>the number of ramps
     * across a picture is the picture's width divided by the code count</b>: 640 columns give two
     * and a half sawtooths at eight bits and a single unfinished ramp at ten. That is what makes a
     * depth change something a reader can see and name on an ordinary eight-bit display, where the
     * extra precision itself is by definition invisible.
     */
    GRADIENT,

    /**
     * The bars with the picture's index over them, in black, as seven-segment digits. The only
     * pattern a human can read a frame number off, which is what makes it the one to put in front of
     * someone checking that a stream is stepping at all.
     */
    COUNTER;

    /** Luma, then Cb, then Cr, for the eight bars in order. */
    private static final int[][] BAR_CODES = {
            {235, 128, 128}, // white
            {219, 16, 138},  // yellow
            {188, 154, 16},  // cyan
            {173, 42, 26},   // green
            {78, 214, 230},  // magenta
            {63, 102, 240},  // red
            {32, 240, 118},  // blue
            {16, 128, 128},  // black
    };

    /** Segments A(top) B(top-right) C(bottom-right) D(bottom) E(bottom-left) F(top-left) G(middle). */
    private static final boolean[][] SEGMENTS = {
            {true, true, true, true, true, true, false},      // 0
            {false, true, true, false, false, false, false},  // 1
            {true, true, false, true, true, false, true},     // 2
            {true, true, true, true, false, false, true},     // 3
            {false, true, true, false, false, true, true},    // 4
            {true, false, true, true, false, true, true},     // 5
            {true, false, true, true, true, true, true},      // 6
            {true, true, true, false, false, false, false},   // 7
            {true, true, true, true, true, true, true},       // 8
            {true, true, true, true, false, true, true},      // 9
    };

    /** The luma code of the digit ink drawn over {@link #COUNTER}'s bars, at eight bits: studio black. */
    private static final int INK = 16;

    /**
     * The luma code at pixel {@code (x, y)} of picture {@code frameIndex}.
     *
     * @param x          column, in {@code [0..width)}
     * @param y          row, in {@code [0..height)}
     * @param frameIndex the picture's index in the stream, from 0
     * @param bitDepth   bits per component the code is wanted in, in {@code [8..16]}
     * @return a code in {@code [0..(1 << bitDepth) - 1]}
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public int luma(int x, int y, int width, int height, int frameIndex, int bitDepth) {
        int shift = shift(bitDepth);
        return switch (this) {
            case BARS -> BAR_CODES[bar(x, width)][0] << shift;
            case GRADIENT -> (x + y + frameIndex) & maxCode(bitDepth);
            case COUNTER -> isInk(x, y, width, height, frameIndex)
                    ? INK << shift
                    : BAR_CODES[bar(x, width)][0] << shift;
        };
    }

    /**
     * The Cb code the pattern assigns at <b>luma</b> pixel {@code (x, y)}: the value a chroma
     * sample takes when it covers that pixel, whatever the layout's subsampling.
     *
     * @param bitDepth bits per component the code is wanted in, in {@code [8..16]}
     * @return a code in {@code [0..(1 << bitDepth) - 1]}
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public int cb(int x, int y, int width, int height, int frameIndex, int bitDepth) {
        return switch (this) {
            case BARS, COUNTER -> BAR_CODES[bar(x, width)][1] << shift(bitDepth);
            case GRADIENT -> (x + frameIndex) & maxCode(bitDepth);
        };
    }

    /**
     * The Cr code the pattern assigns at <b>luma</b> pixel {@code (x, y)}, as {@link #cb}.
     *
     * @param bitDepth bits per component the code is wanted in, in {@code [8..16]}
     * @return a code in {@code [0..(1 << bitDepth) - 1]}
     * @throws IllegalArgumentException if {@code bitDepth} is outside {@code [8..16]}
     */
    public int cr(int x, int y, int width, int height, int frameIndex, int bitDepth) {
        return switch (this) {
            case BARS, COUNTER -> BAR_CODES[bar(x, width)][2] << shift(bitDepth);
            case GRADIENT -> (y - frameIndex) & maxCode(bitDepth);
        };
    }

    private static int maxCode(int bitDepth) {
        return (1 << (shift(bitDepth) + 8)) - 1;
    }

    private static int shift(int bitDepth) {
        if (bitDepth < 8 || bitDepth > 16) {
            throw new IllegalArgumentException("bitDepth must be in [8..16], got " + bitDepth);
        }
        return bitDepth - 8;
    }

    /** Which of the eight bars column {@code x} falls in; the multiply comes first so it is exact. */
    private static int bar(int x, int width) {
        int index = x * BAR_CODES.length / width;
        return index < 0 ? 0 : Math.min(index, BAR_CODES.length - 1);
    }

    /**
     * Whether the seven-segment rendering of {@code frameIndex} covers pixel {@code (x, y)}. The
     * digits are sized from the picture and centred in it, so the counter is legible at any size
     * and stays put as the number of digits grows.
     */
    private static boolean isInk(int x, int y, int width, int height, int frameIndex) {
        String digits = Integer.toString(Math.abs(frameIndex));
        int digitHeight = Math.max(6, height / 3);
        int digitWidth = Math.max(4, digitHeight / 2);
        int gap = Math.max(2, digitWidth / 4);
        int total = digits.length() * digitWidth + (digits.length() - 1) * gap;
        int left = (width - total) / 2;
        int top = (height - digitHeight) / 2;
        if (y < top || y >= top + digitHeight || x < left || x >= left + total) {
            return false;
        }
        int cell = digitWidth + gap;
        int index = (x - left) / cell;
        int withinDigit = (x - left) - index * cell;
        if (index >= digits.length() || withinDigit >= digitWidth) {
            return false; // the gap between two digits
        }
        return segmentCovers(digits.charAt(index) - '0', withinDigit, y - top, digitWidth,
                digitHeight);
    }

    /** Whether any lit segment of {@code digit} covers {@code (x, y)} within a digit's own box. */
    private static boolean segmentCovers(int digit, int x, int y, int width, int height) {
        boolean[] lit = SEGMENTS[digit];
        int thickness = Math.max(1, width / 4);
        int half = height / 2;
        return (lit[0] && y < thickness)
                || (lit[1] && x >= width - thickness && y < half)
                || (lit[2] && x >= width - thickness && y >= half)
                || (lit[3] && y >= height - thickness)
                || (lit[4] && x < thickness && y >= half)
                || (lit[5] && x < thickness && y < half)
                || (lit[6] && y >= half - thickness / 2 && y < half - thickness / 2 + thickness);
    }
}

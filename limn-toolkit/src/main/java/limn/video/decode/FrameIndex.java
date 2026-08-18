package limn.video.decode;

/**
 * Turns a time back into a picture index, for the two decoders here whose pictures are evenly
 * spaced and whose presentation times are therefore {@code index × 1e6 × den / num} truncated to
 * whole microseconds.
 *
 * <p>It exists as its own class because inverting a truncating division is the kind of arithmetic
 * that looks obviously right in two places and is subtly different in one of them. The identity it
 * rests on is that for a whole number {@code m}, {@code floor(x) >= m} exactly when {@code x >= m},
 * so the first index at or after a time is a ceiling division and no rounding of the presentation
 * times themselves is involved.
 */
final class FrameIndex {

    private FrameIndex() {
    }

    /**
     * @return the smallest index whose presentation time is at or after {@code micros}, clamped
     *         into {@code [0..Integer.MAX_VALUE]}; a target far enough out to overflow the
     *         arithmetic is past the end of anything that can be indexed, and the end is where a
     *         seek past the end belongs
     */
    static int atOrAfter(long micros, int rateNum, int rateDen) {
        if (micros <= 0) {
            return 0;
        }
        long scaled = scale(micros, rateNum);
        if (scaled < 0) {
            return Integer.MAX_VALUE;
        }
        long divisor = 1_000_000L * rateDen;
        return clamp(-Math.floorDiv(-scaled, divisor));
    }

    /**
     * @return the largest index whose presentation time is at or before {@code micros}, clamped the
     *         same way and never below 0
     */
    static int atOrBefore(long micros, int rateNum, int rateDen) {
        if (micros <= 0) {
            return 0;
        }
        long scaled = scale(micros, rateNum);
        if (scaled < 0) {
            return Integer.MAX_VALUE;
        }
        return clamp(scaled / (1_000_000L * rateDen));
    }

    /** @return {@code micros × rateNum}, or -1 when that does not fit in a long */
    private static long scale(long micros, int rateNum) {
        try {
            return Math.multiplyExact(micros, (long) rateNum);
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }

    private static int clamp(long index) {
        return index > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) index;
    }
}

package limn.video;

import limn.video.VideoColor.Matrix;
import limn.video.VideoColor.Range;
import limn.video.VideoColor.Transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colorimetry table of {@link VideoColor}: the six folded decode coefficients of every
 * matrix/range combination, the identities that make the table self-checking, and the interning
 * that lets a caller compare interpretations by reference.
 */
class VideoColorTest {

    /**
     * For the identities and the range inversion, which are recomputed here from the luma weights and
     * so land a few units in the last place away. The table itself is asserted exactly: a delta as
     * loose as this one admits a cbToG that shifts a decoded pixel to the other side of a rounding
     * tie, which is the one error the table exists to catch.
     */
    private static final double COEFFICIENT_DELTA = 1e-12;

    @Test
    void foldedCoefficientsAreTheDerivedConstants() {
        assertCoefficients(VideoColor.BT601_LIMITED, 1.16438356164383561,
                1.59602678571428580, -0.39176229009491359, -0.81296764723777071, 2.01723214285714292);
        assertCoefficients(VideoColor.BT601_FULL, 1.00000000000000000,
                1.40199999999999991, -0.34413628620102216, -0.71413628620102210, 1.77200000000000002);
        assertCoefficients(VideoColor.BT709_LIMITED, 1.16438356164383561,
                1.79274107142857142, -0.21324861427372963, -0.53290932855944395, 2.11240178571428583);
        assertCoefficients(VideoColor.BT709_FULL, 1.00000000000000000,
                1.57479999999999998, -0.18732427293064877, -0.46812427293064879, 1.85559999999999992);
        assertCoefficients(VideoColor.BT2020_LIMITED, 1.16438356164383561,
                1.67867410714285725, -0.18732610421934259, -0.65042431850505689, 2.14177232142857132);
        assertCoefficients(VideoColor.BT2020_FULL, 1.00000000000000000,
                1.47459999999999991, -0.16455312684365780, -0.57135312684365780, 1.88139999999999996);

        // The two nearest cells in the whole table: 1.83e-6 apart, which shifts a decoded pixel by
        // at most 2e-4 of a code. Copying one row's cell into the other is invisible everywhere
        // except right here.
        assertTrue(Math.abs(VideoColor.BT2020_LIMITED.cbToG(8) - VideoColor.BT709_FULL.cbToG(8)) > 1e-6,
                "BT2020 studio and BT709 full cbToG must not be the same number");
    }

    @Test
    void theTwoCoefficientIdentitiesHold() {
        for (VideoColor color : specified()) {
            Matrix matrix = color.matrix();
            assertEquals(color.crToR(8) * (-matrix.kr() / matrix.kg()), color.crToG(8), COEFFICIENT_DELTA,
                    color + " crToG follows crToR");
            assertEquals(color.cbToB(8) * (-matrix.kb() / matrix.kg()), color.cbToG(8), COEFFICIENT_DELTA,
                    color + " cbToG follows cbToB");
        }
    }

    @Test
    void lumaScaleInvertsTheRange() {
        for (VideoColor color : specified()) {
            if (color.range() == Range.LIMITED) {
                assertEquals(255.0, color.yScale(8) * (235 - 16), COEFFICIENT_DELTA,
                        color + " studio luma spans 219 codes");
            } else {
                assertEquals(1.0, color.yScale(8), COEFFICIENT_DELTA, color + " full range is unscaled");
            }
        }
    }

    @Test
    void lumaWeightsAreTheRecommendedOnes() {
        // Asserted as literals rather than as a sum: kg() is defined as 1 - kr - kb, so any pair of
        // weights sums to one and a test of the sum would pass with the wrong recommendation's
        // numbers in it.
        assertEquals(0.299, Matrix.BT601.kr(), 0.0);
        assertEquals(0.114, Matrix.BT601.kb(), 0.0);
        assertEquals(0.587, Matrix.BT601.kg(), 1e-15);

        assertEquals(0.2126, Matrix.BT709.kr(), 0.0);
        assertEquals(0.0722, Matrix.BT709.kb(), 0.0);
        assertEquals(0.7152, Matrix.BT709.kg(), 1e-15);

        assertEquals(0.2627, Matrix.BT2020.kr(), 0.0);
        assertEquals(0.0593, Matrix.BT2020.kb(), 0.0);
        assertEquals(0.6780, Matrix.BT2020.kg(), 1e-15);
    }

    @Test
    void offsetsAreTheCodeConventions() {
        for (VideoColor color : specified()) {
            assertEquals(color.range() == Range.LIMITED ? 16 : 0, color.yOffset(8), color + " luma offset");
            assertEquals(128, color.chromaNeutral(8), color + " chroma neutral");
        }
        assertEquals(16, VideoColor.unspecified().yOffset(8));
        assertEquals(128, VideoColor.unspecified().chromaNeutral(8));
    }

    @Test
    void instancesAreInterned() {
        assertSame(VideoColor.BT601_LIMITED, VideoColor.of(Matrix.BT601, Range.LIMITED));
        assertSame(VideoColor.BT601_FULL, VideoColor.of(Matrix.BT601, Range.FULL));
        assertSame(VideoColor.BT709_LIMITED, VideoColor.of(Matrix.BT709, Range.LIMITED));
        assertSame(VideoColor.BT709_FULL, VideoColor.of(Matrix.BT709, Range.FULL));
        assertSame(VideoColor.BT2020_LIMITED, VideoColor.of(Matrix.BT2020, Range.LIMITED));
        assertSame(VideoColor.BT2020_FULL, VideoColor.of(Matrix.BT2020, Range.FULL));
        assertSame(VideoColor.of(Matrix.BT709, Range.LIMITED), VideoColor.of(Matrix.BT709, Range.LIMITED));
        assertSame(VideoColor.unspecified(), VideoColor.unspecified());

        VideoColor[] all = specified();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertNotSame(all[i], all[j], all[i] + " and " + all[j] + " are different combinations");
            }
        }
    }

    @Test
    void unspecifiedDecodesAsBt709LimitedButIsDistinct() {
        VideoColor unspecified = VideoColor.unspecified();
        assertNotSame(VideoColor.BT709_LIMITED, unspecified,
                "reference comparison must separate unsignalled from signalled BT.709 studio");
        assertFalse(unspecified.isSpecified());
        assertTrue(VideoColor.BT709_LIMITED.isSpecified());
        assertSame(Matrix.BT709, unspecified.matrix());
        assertSame(Range.LIMITED, unspecified.range());
        assertEquals(VideoColor.BT709_LIMITED.yScale(8), unspecified.yScale(8), 0.0);
        assertEquals(VideoColor.BT709_LIMITED.yOffset(8), unspecified.yOffset(8));
        assertEquals(VideoColor.BT709_LIMITED.crToR(8), unspecified.crToR(8), 0.0);
        assertEquals(VideoColor.BT709_LIMITED.cbToG(8), unspecified.cbToG(8), 0.0);
        assertEquals(VideoColor.BT709_LIMITED.crToG(8), unspecified.crToG(8), 0.0);
        assertEquals(VideoColor.BT709_LIMITED.cbToB(8), unspecified.cbToB(8), 0.0);
    }

    /**
     * Exactly, with no delta at all: every literal above is the correctly rounded double of the
     * derived rational, and a coefficient one unit in the last place away already moves a pixel that
     * decodes to a tie to the other integer.
     */
    @Test
    void toStringNamesTheCombinationOrSaysThereWasNone() {
        assertEquals("VideoColor[BT709 LIMITED]", VideoColor.BT709_LIMITED.toString());
        assertEquals("VideoColor[BT601 FULL]", VideoColor.BT601_FULL.toString());
        assertEquals("VideoColor[BT2020 LIMITED]", VideoColor.BT2020_LIMITED.toString());
        assertEquals("VideoColor[unspecified]", VideoColor.unspecified().toString(),
                "the unsignalled instance must not describe itself as the one it decodes as");
    }

    @Test
    void nullArgumentsThrow() {
        assertThrows(NullPointerException.class, () -> VideoColor.of(null, Range.LIMITED));
        assertThrows(NullPointerException.class, () -> VideoColor.of(Matrix.BT709, null));
        assertThrows(NullPointerException.class,
                () -> VideoColor.of(Matrix.BT709, Range.LIMITED, null));
        assertThrows(NullPointerException.class, () -> VideoColor.BT709_LIMITED.withTransfer(null));
    }

    /**
     * The ten-bit table, derived here from the recommendations' own spans rather than from the
     * eight-bit numbers above, which is the whole point. Scaling the eight-bit table by four is
     * the plausible wrong answer, and it is wrong: studio white is 940 of 1023, not 940 of 1020, so
     * every studio-range coefficient is three parts in a thousand larger than four times its
     * eight-bit self and every full-range one is unchanged. A conversion that used the eight-bit
     * table on a ten-bit picture, or that scaled it the plausible way, fails here.
     */
    @Test
    void theTenBitTableIsTheRecommendationsAndNotTheEightBitOneScaled() {
        for (VideoColor color : specified()) {
            Matrix matrix = color.matrix();
            boolean limited = color.range() == Range.LIMITED;
            int maxCode = 1023;
            double lumaSpan = limited ? 219 << 2 : maxCode;
            double chromaSpan = limited ? 224 << 2 : maxCode;
            double scale = maxCode / chromaSpan;

            assertEquals(limited ? 64 : 0, color.yOffset(10), color + " ten-bit studio black");
            assertEquals(512, color.chromaNeutral(10), color + " ten-bit chroma neutral");
            assertEquals(maxCode / lumaSpan, color.yScale(10), COEFFICIENT_DELTA,
                    color + " ten-bit luma gain");
            assertEquals(2 * (1 - matrix.kr()) * scale, color.crToR(10), COEFFICIENT_DELTA,
                    color + " ten-bit crToR");
            assertEquals(2 * (1 - matrix.kb()) * scale, color.cbToB(10), COEFFICIENT_DELTA,
                    color + " ten-bit cbToB");
            assertEquals(-2 * (1 - matrix.kr()) * matrix.kr() / matrix.kg() * scale,
                    color.crToG(10), COEFFICIENT_DELTA, color + " ten-bit crToG");
            assertEquals(-2 * (1 - matrix.kb()) * matrix.kb() / matrix.kg() * scale,
                    color.cbToG(10), COEFFICIENT_DELTA, color + " ten-bit cbToG");

            if (limited) {
                assertNotEquals(4 * color.crToR(8), color.crToR(10), 1e-9,
                        color + " ten-bit studio coefficients are not the eight-bit ones times four");
            } else {
                assertEquals(color.crToR(8), color.crToR(10), 0.0,
                        color + " full range is depth-independent");
            }
        }
    }

    @Test
    void aDepthOutsideTheTableIsRefusedRatherThanExtrapolated() {
        for (int bad : new int[] {0, 7, 17, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> VideoColor.BT709_LIMITED.yScale(bad), "bitDepth " + bad);
            assertThrows(IllegalArgumentException.class,
                    () -> VideoColor.BT709_LIMITED.chromaNeutral(bad), "bitDepth " + bad);
        }
    }

    @Test
    void aTransferIsCarriedWithoutTouchingTheMatrix() {
        VideoColor pq = VideoColor.of(Matrix.BT2020, Range.LIMITED, Transfer.PQ);
        assertSame(Transfer.PQ, pq.transfer());
        assertSame(Matrix.BT2020, pq.matrix());
        assertSame(Range.LIMITED, pq.range());
        assertTrue(pq.isSpecified());
        assertFalse(pq.isDisplayReferred());

        // Same numbers, different meaning: a transfer function says what the decoded values ARE,
        // and changes nothing about the arithmetic that produces them.
        for (int depth : new int[] {8, 10}) {
            assertEquals(VideoColor.BT2020_LIMITED.yScale(depth), pq.yScale(depth), 0.0);
            assertEquals(VideoColor.BT2020_LIMITED.crToR(depth), pq.crToR(depth), 0.0);
            assertEquals(VideoColor.BT2020_LIMITED.cbToG(depth), pq.cbToG(depth), 0.0);
            assertEquals(VideoColor.BT2020_LIMITED.crToG(depth), pq.crToG(depth), 0.0);
            assertEquals(VideoColor.BT2020_LIMITED.cbToB(depth), pq.cbToB(depth), 0.0);
            assertEquals(VideoColor.BT2020_LIMITED.yOffset(depth), pq.yOffset(depth));
        }
    }

    @Test
    void everyTransferCombinationIsInternedAndSdrIsTheOnesThatAlwaysExisted() {
        for (Matrix matrix : Matrix.values()) {
            for (Range range : Range.values()) {
                assertSame(VideoColor.of(matrix, range),
                        VideoColor.of(matrix, range, Transfer.SDR),
                        "the two-argument form must mean SDR, not a seventh instance");
                for (Transfer transfer : Transfer.values()) {
                    VideoColor color = VideoColor.of(matrix, range, transfer);
                    assertSame(color, VideoColor.of(matrix, range, transfer));
                    assertSame(color, color.withTransfer(transfer));
                    assertSame(color, VideoColor.of(matrix, range).withTransfer(transfer));
                }
            }
        }
    }

    @Test
    void anUnsignalledInterpretationMeansSdrAndStaysUnsignalled() {
        VideoColor unspecified = VideoColor.unspecified();
        assertSame(Transfer.SDR, unspecified.transfer());
        assertTrue(unspecified.isDisplayReferred());
        assertSame(unspecified, unspecified.withTransfer(Transfer.SDR),
                "SDR is what unsignalled already means, so asking for it changes nothing");

        // A file may signal PQ and no matrix at all, and reporting that as "unsignalled" would lose
        // the one fact about it that stops it being shown wrong.
        VideoColor pq = unspecified.withTransfer(Transfer.PQ);
        assertSame(Transfer.PQ, pq.transfer());
        assertTrue(pq.isSpecified());
    }

    @Test
    void toStringNamesATransferOnlyWhenThereIsOneToName() {
        assertEquals("VideoColor[BT2020 LIMITED PQ]",
                VideoColor.of(Matrix.BT2020, Range.LIMITED, Transfer.PQ).toString());
        assertEquals("VideoColor[BT709 FULL HLG]",
                VideoColor.of(Matrix.BT709, Range.FULL, Transfer.HLG).toString());
    }

    /**
     * Exactly, with no delta at all: every literal above is the correctly rounded double of the
     * derived rational, and a coefficient one unit in the last place away already moves a pixel that
     * decodes to a tie to the other integer.
     */
    private static void assertCoefficients(VideoColor color, double yScale,
                                           double crToR, double cbToG, double crToG, double cbToB) {
        assertEquals(yScale, color.yScale(8), 0.0, color + " yScale");
        assertEquals(crToR, color.crToR(8), 0.0, color + " crToR");
        assertEquals(cbToG, color.cbToG(8), 0.0, color + " cbToG");
        assertEquals(crToG, color.crToG(8), 0.0, color + " crToG");
        assertEquals(cbToB, color.cbToB(8), 0.0, color + " cbToB");
    }

    private static VideoColor[] specified() {
        return new VideoColor[] {
            VideoColor.BT601_LIMITED, VideoColor.BT601_FULL,
            VideoColor.BT709_LIMITED, VideoColor.BT709_FULL,
            VideoColor.BT2020_LIMITED, VideoColor.BT2020_FULL,
        };
    }
}

package limn.lang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The one vocabulary of argument checks: what passes, what fails, and what the message says. */
class ChecksTest {

    private static String failing(Runnable check) {
        return assertThrows(IllegalArgumentException.class, check::run).getMessage();
    }

    @Test
    void aValueInRangeComesBackAndOneOutsideNamesTheRange() {
        assertEquals(5, Checks.inRange(5, 1, 10, "slots"));
        assertEquals(1, Checks.inRange(1, 1, 10, "slots"));
        assertEquals(10, Checks.inRange(10, 1, 10, "slots"));
        assertEquals("slots must be in [1..10], got 0", failing(() -> Checks.inRange(0, 1, 10, "slots")));
        assertEquals("slots must be in [1..10], got 11", failing(() -> Checks.inRange(11, 1, 10, "slots")));
        assertEquals(0.5f, Checks.inRange(0.5f, 0f, 1f, "gain"));
        assertEquals("gain must be in [0..1], got 1.5", failing(() -> Checks.inRange(1.5f, 0f, 1f, "gain")));
        assertEquals("pitch must be in [0.25..4], got 0.1",
                failing(() -> Checks.inRange(0.1f, 0.25f, 4f, "pitch")));
        assertEquals("pan must be in [-1..1], got -2.0", failing(() -> Checks.inRange(-2f, -1f, 1f, "pan")));
    }

    @Test
    void nanFailsEveryCheckAPlainComparisonWouldLetItPass() {
        // NaN < 0 is false, so `if (v < 0) throw` accepts NaN. None of these do.
        assertEquals("gain must be in [0..1], got NaN", failing(() -> Checks.inRange(Float.NaN, 0f, 1f, "gain")));
        assertEquals("cell must be at least 1, got NaN", failing(() -> Checks.atLeast(Float.NaN, 1f, "cell")));
        assertEquals("radius must not be negative and must be finite, got NaN",
                failing(() -> Checks.notNegative(Float.NaN, "radius")));
        assertEquals("time scale must not be negative and must be finite, got NaN",
                failing(() -> Checks.notNegative(Double.NaN, "time scale")));
        assertEquals("font size must be positive and must be finite, got NaN",
                failing(() -> Checks.positive(Float.NaN, "font size")));
        assertEquals("cornerScale must be finite, got NaN", failing(() -> Checks.finite(Float.NaN, "cornerScale")));
    }

    @Test
    void infinityIsNotAValueASizeOrARadiusCanTake() {
        assertEquals("font size must be positive and must be finite, got Infinity",
                failing(() -> Checks.positive(Float.POSITIVE_INFINITY, "font size")));
        assertEquals("radius must not be negative and must be finite, got Infinity",
                failing(() -> Checks.notNegative(Float.POSITIVE_INFINITY, "radius")));
        assertEquals("cornerScale must be finite, got -Infinity",
                failing(() -> Checks.finite(Float.NEGATIVE_INFINITY, "cornerScale")));
    }

    @Test
    void floorsAndSigns() {
        assertEquals(1, Checks.atLeast(1, 1, "frameRateNum"));
        assertEquals("frameRateNum must be at least 1, got 0", failing(() -> Checks.atLeast(0, 1, "frameRateNum")));
        assertEquals(1.5f, Checks.atLeast(1.5f, 1f, "cell"));
        assertEquals("cell must be at least 1, got 0.5", failing(() -> Checks.atLeast(0.5f, 1f, "cell")));
        assertEquals(0, Checks.notNegative(0, "slot"));
        assertEquals(7L, Checks.notNegative(7L, "seek target"));
        assertEquals("seek target must not be negative, got -1", failing(() -> Checks.notNegative(-1L, "seek target")));
        assertEquals(0f, Checks.notNegative(0f, "thickness"));
        assertEquals("thickness must not be negative, got -0.5",
                failing(() -> Checks.notNegative(-0.5f, "thickness")));
        assertEquals(44_100, Checks.positive(44_100, "sampleRate"));
        assertEquals("sampleRate must be positive, got 0", failing(() -> Checks.positive(0, "sampleRate")));
        assertEquals(3L, Checks.positive(3L, "count"));
        assertEquals(12f, Checks.positive(12f, "font size"));
        assertEquals("font size must be positive, got 0.0", failing(() -> Checks.positive(0f, "font size")));
        assertEquals(2f, Checks.finite(2f, "cornerScale"));
    }

    @Test
    void aSizeIsCheckedOnBothSidesAndNamedAsOne() {
        Checks.positiveSize(1, 1, "window size");
        assertEquals("window size must be positive, got 0x600", failing(() -> Checks.positiveSize(0, 600, "window size")));
        assertEquals("image size must be positive, got 800x-1", failing(() -> Checks.positiveSize(800, -1, "image size")));
        Checks.notNegativeSize(0, 0, "framebuffer size");
        assertEquals("framebuffer size must not be negative, got -1x0",
                failing(() -> Checks.notNegativeSize(-1, 0, "framebuffer size")));
    }
}

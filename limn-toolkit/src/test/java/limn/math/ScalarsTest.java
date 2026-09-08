package limn.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The scalar helpers, and above all what each does with {@code NaN} and an empty range. */
class ScalarsTest {

    private static final float EPS = 1e-6f;

    @Test
    void clampHoldsAValueToItsRange() {
        assertEquals(3f, Scalars.clamp(3f, 1f, 5f), EPS);
        assertEquals(1f, Scalars.clamp(-2f, 1f, 5f), EPS);
        assertEquals(5f, Scalars.clamp(9f, 1f, 5f), EPS);
        assertEquals(1f, Scalars.clamp(1f, 1f, 5f), EPS);
        assertEquals(5f, Scalars.clamp(5f, 1f, 5f), EPS);
        assertEquals(3, Scalars.clamp(3, 1, 5));
        assertEquals(1, Scalars.clamp(-2, 1, 5));
        assertEquals(5, Scalars.clamp(9, 1, 5));
    }

    @Test
    void aClampNeverLetsNaNThrough() {
        // Math.min(1, Math.max(0, NaN)) is NaN, and three of the private copies this replaced
        // answered exactly that. A NaN that leaves a clamp reaches a gain, an alpha, a fraction.
        assertEquals(1f, Scalars.clamp(Float.NaN, 1f, 5f), EPS);
        assertEquals(0f, Scalars.clamp01(Float.NaN), EPS);
        assertEquals(0d, Scalars.clamp01(Double.NaN), 1e-12);
        assertEquals(0f, Scalars.smoothstep(Float.NaN), EPS);
    }

    @Test
    void anEmptyRangeAnswersItsLowerBound() {
        // PopupMenu's copy guarded hi < lo for a zero-sized bounds; now every caller has it.
        assertEquals(4f, Scalars.clamp(2f, 4f, 1f), EPS);
        assertEquals(4f, Scalars.clamp(9f, 4f, 1f), EPS);
        assertEquals(4, Scalars.clamp(2, 4, 1));
    }

    @Test
    void clamp01IsTheUnitRange() {
        assertEquals(0f, Scalars.clamp01(-0.5f), EPS);
        assertEquals(0.25f, Scalars.clamp01(0.25f), EPS);
        assertEquals(1f, Scalars.clamp01(7f), EPS);
        assertEquals(0.75d, Scalars.clamp01(0.75d), 1e-12);
        assertEquals(1d, Scalars.clamp01(2d), 1e-12);
    }

    @Test
    void lerpIsExactAtBothEndsAndDoesNotClamp() {
        assertEquals(10f, Scalars.lerp(10f, 20f, 0f), EPS);
        assertEquals(20f, Scalars.lerp(10f, 20f, 1f), EPS);
        assertEquals(15f, Scalars.lerp(10f, 20f, 0.5f), EPS);
        assertEquals(25f, Scalars.lerp(10f, 20f, 1.5f), EPS, "an overshoot is asked for");
    }

    @Test
    void smoothstepIsZeroVelocityAtBothEndsAndStaysInTheUnitRange() {
        assertEquals(0f, Scalars.smoothstep(0f), EPS);
        assertEquals(1f, Scalars.smoothstep(1f), EPS);
        assertEquals(0.5f, Scalars.smoothstep(0.5f), EPS);
        assertEquals(0f, Scalars.smoothstep(-3f), EPS);
        assertEquals(1f, Scalars.smoothstep(3f), EPS);
        // Symmetric about the middle: f(t) + f(1 - t) == 1.
        assertEquals(1f, Scalars.smoothstep(0.2f) + Scalars.smoothstep(0.8f), EPS);
    }
}

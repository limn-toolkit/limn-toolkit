package limn.backend.lwjgl;

import limn.math.Mat4;
import limn.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two testable halves of bloom (ADR 005 §6 step 1): the off path (a pass
 * that never calls {@code bloom()} must decide "don't run", so the backend
 * allocates no half-res targets and issues no fullscreen pass) and the
 * radius→iterations mapping with both of its clamps (the blocky-glow floor
 * below which the blur refuses to run, and the cost ceiling above which a
 * wider ask stops buying passes). The GL side is the Kitchen Sink's job.
 */
class BloomMathTest {

    @Test
    void bloomIsOffByDefaultSoTheBranchNeverRuns() {
        // "Free when off" hinges on this: a pass that never asked for bloom
        // reports zero intensity, and shouldRun (the single gate in front of
        // allocation and passes) says no at any display scale.
        GlRenderPass pass = new GlRenderPass(null, Mat4.identity(), Vec3.ZERO);
        assertEquals(0f, pass.bloomIntensity(), 0f);
        assertFalse(BloomMath.shouldRun(pass.bloomIntensity(), pass.bloomRadius(), 1f));
        assertFalse(BloomMath.shouldRun(pass.bloomIntensity(), pass.bloomRadius(), 2f));
    }

    @Test
    void bloomStoresWhatTheBackendReads() {
        GlRenderPass pass = new GlRenderPass(null, Mat4.identity(), Vec3.ZERO);
        assertSame(pass, pass.bloom(1.2f, 0.7f, 6f)); // chains like exposure()
        assertEquals(1.2f, pass.bloomThreshold(), 0f);
        assertEquals(0.7f, pass.bloomIntensity(), 0f);
        assertEquals(6f, pass.bloomRadius(), 0f);
        assertTrue(BloomMath.shouldRun(pass.bloomIntensity(), pass.bloomRadius(), 2f));
    }

    @Test
    void zeroOrNegativeIntensityNeverRuns() {
        assertFalse(BloomMath.shouldRun(0f, 8f, 2f));
        assertFalse(BloomMath.shouldRun(-1f, 8f, 2f));
    }

    @Test
    void radiusIsInPointsSoTexelsScaleWithTheDisplay() {
        // The same authored radius must cover twice the texels at 2×; that is
        // the whole reason the unit is points and the backend converts.
        assertEquals(4f, BloomMath.sigmaTexels(4f, 2f), 1e-6f);
        assertEquals(2f, BloomMath.sigmaTexels(4f, 1f), 1e-6f);
    }

    @Test
    void tinyRadiiRefuseToRunRatherThanRenderSquares() {
        // ADR 005 §5: under the floor a half-res blur is blocky, and the clamp
        // refuses outright instead of degrading. The floor itself is eye-picked
        // (step 3); this pins that a floor exists and is honoured.
        float justUnder = (BloomMath.MIN_SIGMA_TEXELS - 0.01f) * 2f; // points at 1×
        float justOver = (BloomMath.MIN_SIGMA_TEXELS + 0.01f) * 2f;
        assertFalse(BloomMath.shouldRun(1f, justUnder, 1f));
        assertTrue(BloomMath.shouldRun(1f, justOver, 1f));
        // The floor is in texels, not points: the same radius that is too small
        // at 1× clears the floor at 2×.
        assertTrue(BloomMath.shouldRun(1f, justUnder, 2f));
    }

    @Test
    void blurPairsGrowWithSigmaAndClampAtTheCostCeiling() {
        assertEquals(1, BloomMath.blurPairs(0.5f)); // at least one pair once running
        assertEquals(1, BloomMath.blurPairs(BloomMath.accumulatedSigma(1)));
        assertEquals(2, BloomMath.blurPairs(BloomMath.accumulatedSigma(1) + 0.01f));
        assertEquals(3, BloomMath.blurPairs(BloomMath.accumulatedSigma(3)));
        assertEquals(BloomMath.MAX_PAIRS, BloomMath.blurPairs(1000f)); // ceiling, not unbounded cost
    }

    @Test
    void sigmasComposeInQuadratureAcrossPairs() {
        // Pair j samples at spacing j+1: N pairs reach σ0·√(1²+…+N²). This is
        // the number the demo's radius knob is calibrated against.
        assertEquals(BloomMath.SIGMA_PER_PAIR, BloomMath.accumulatedSigma(1), 1e-5f);
        assertEquals(BloomMath.SIGMA_PER_PAIR * (float) Math.sqrt(5), BloomMath.accumulatedSigma(2), 1e-5f);
        assertEquals(BloomMath.SIGMA_PER_PAIR * (float) Math.sqrt(14), BloomMath.accumulatedSigma(3), 1e-5f);
    }
}

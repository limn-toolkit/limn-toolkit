package limn.backend.lwjgl;

/**
 * The pure arithmetic of the bloom pass (ADR 005): how a caller's radius in
 * points becomes half-res blur iterations, and when bloom refuses to run at
 * all. Kept free of GL so the mapping and both of its clamps are testable; the
 * off path and this mapping are the two halves of bloom a unit test can defend.
 */
final class BloomMath {

    /**
     * The 9-tap Gaussian's sigma at unit tap spacing, in half-res texels.
     * Pair {@code j} samples at spacing {@code j + 1}, so its sigma is
     * {@code SIGMA_PER_PAIR * (j + 1)} and sigmas accumulate in quadrature.
     */
    static final float SIGMA_PER_PAIR = 2f;

    /** Cost ceiling: never more than 2×MAX_PAIRS + 2 fullscreen passes at quarter pixel count. */
    static final int MAX_PAIRS = 6;

    /**
     * Below this target sigma (half-res texels) the blur refuses to run.
     * Settled by eye (ADR 005 §5, step 3, radii 1–4 pt at 1×): the feared
     * half-res squares never appear (one pair already blurs by
     * {@link #SIGMA_PER_PAIR} texels, which covers the half-res grid even under
     * a 1× linear upsample), but that same minimum means every request under
     * 2 texels gets the identical, wider-than-asked glow. The floor is where
     * the chain stops over-delivering by more than 2×: refuse under 1 texel
     * (radius 2 pt at 1×, 1 pt at 2×) rather than answer a tiny radius with a
     * glow that ignores it.
     */
    static final float MIN_SIGMA_TEXELS = 1f;

    private BloomMath() {
    }

    /**
     * The blur's target sigma in half-res texels: the caller's radius is in
     * points (so a glow keeps its size across display scales), the chain runs
     * at half the target's pixel resolution.
     */
    static float sigmaTexels(float radiusPoints, float pixelsPerPoint) {
        return radiusPoints * pixelsPerPoint / 2f;
    }

    /**
     * Whether the pass runs at all: a positive intensity and a radius wide
     * enough for the half-res chain to honour. False means bloom costs
     * nothing: no targets are allocated and no pass is issued.
     */
    static boolean shouldRun(float intensity, float radiusPoints, float pixelsPerPoint) {
        return intensity > 0f && sigmaTexels(radiusPoints, pixelsPerPoint) >= MIN_SIGMA_TEXELS;
    }

    /**
     * Blur pairs for a target sigma: the smallest N whose accumulated sigma
     * reaches it, clamped to {@link #MAX_PAIRS}; a caller asking for an
     * enormous glow gets the widest blur the chain does, not unbounded cost.
     */
    static int blurPairs(float sigmaTexels) {
        for (int pairs = 1; pairs < MAX_PAIRS; pairs++) {
            if (accumulatedSigma(pairs) >= sigmaTexels) {
                return pairs;
            }
        }
        return MAX_PAIRS;
    }

    /**
     * Sigma reached after N pairs with growing tap spacing: independent
     * Gaussians compose in quadrature, so with per-pair sigmas of
     * {@code SIGMA_PER_PAIR × (1, 2, …, N)} the total is
     * {@code SIGMA_PER_PAIR × sqrt(1² + 2² + … + N²)}.
     */
    static float accumulatedSigma(int pairs) {
        return SIGMA_PER_PAIR * (float) Math.sqrt(pairs * (pairs + 1) * (2L * pairs + 1) / 6.0);
    }
}

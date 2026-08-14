package limn.render3d;

import limn.math.Vec3;

/**
 * Order-2 (9-coefficient) spherical-harmonic irradiance baked from an
 * {@link Environment}, the diffuse half of image-based lighting (Ramamoorthi &amp;
 * Hanrahan 2001). The environment radiance is projected onto the SH basis over a
 * deterministic Fibonacci-sphere sampling and convolved with the cosine lobe, so
 * {@link #evaluate}{@code (n)} gives the average irradiance reaching a surface with
 * normal {@code n} (multiply by albedo for diffuse). Deterministic: same input →
 * identical coefficients, so it is unit-testable headless.
 */
public final class IrradianceSh {

    private static final float A0 = (float) Math.PI;           // cosine-lobe convolution, band 0
    private static final float A1 = (float) (2 * Math.PI / 3); // band 1
    private static final float A2 = (float) (Math.PI / 4);     // band 2
    private static final float[] BAND_A = {A0, A1, A1, A1, A2, A2, A2, A2, A2};

    private final Vec3[] coefficients; // 9, folded so evaluate() needs no extra 1/π

    private IrradianceSh(Vec3[] coefficients) {
        this.coefficients = coefficients;
    }

    /** The 9 SH coefficients (for uploading to a shader as {@code vec3[9]}). */
    public Vec3[] coefficients() {
        return coefficients;
    }

    /** Projects an environment into spherical harmonics: done once, not per frame. */
    public static IrradianceSh bake(Environment environment) {
        return bake(environment, 4096);
    }

    /** Bakes with {@code samples} deterministic directions (higher = smoother). */
    public static IrradianceSh bake(Environment environment, int samples) {
        Vec3[] sh = new Vec3[9];
        for (int k = 0; k < 9; k++) {
            sh[k] = Vec3.ZERO;
        }
        double golden = Math.PI * (3 - Math.sqrt(5)); // Fibonacci sphere, no randomness
        for (int i = 0; i < samples; i++) {
            float y = (float) (1 - 2 * (i + 0.5) / samples);
            float r = (float) Math.sqrt(Math.max(0, 1 - y * y));
            float phi = (float) (i * golden);
            float x = r * (float) Math.cos(phi);
            float z = r * (float) Math.sin(phi);
            Vec3 radiance = environment.radiance(new Vec3(x, y, z));
            float[] basis = shBasis(x, y, z);
            for (int k = 0; k < 9; k++) {
                // 4π/N (Monte-Carlo) · A_l (cosine lobe) · 1/π (fold, so evaluate×albedo = diffuse).
                float weight = 4f * BAND_A[k] / samples;
                sh[k] = sh[k].add(radiance.mul(basis[k] * weight));
            }
        }
        return new IrradianceSh(sh);
    }

    /** Irradiance for a surface normal (clamped non-negative). */
    public Vec3 evaluate(Vec3 normal) {
        Vec3 n = normal.normalize();
        float[] basis = shBasis(n.x(), n.y(), n.z());
        Vec3 sum = Vec3.ZERO;
        for (int k = 0; k < 9; k++) {
            sum = sum.add(coefficients[k].mul(basis[k]));
        }
        return sum.max(Vec3.ZERO);
    }

    /** The 9 real SH basis functions (same order used by the shader). */
    private static float[] shBasis(float x, float y, float z) {
        return new float[]{
                0.282095f,
                0.488603f * y,
                0.488603f * z,
                0.488603f * x,
                1.092548f * x * y,
                1.092548f * y * z,
                0.315392f * (3 * z * z - 1),
                1.092548f * x * z,
                0.546274f * (x * x - y * y),
        };
    }
}

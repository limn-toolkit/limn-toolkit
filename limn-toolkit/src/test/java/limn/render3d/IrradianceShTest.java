package limn.render3d;

import limn.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The spherical-harmonic irradiance baker: determinism and physical sanity. */
class IrradianceShTest {

    @Test
    void bakingIsDeterministic() {
        Environment env = Environment.gradient(
                new Vec3(0.4f, 0.6f, 1f), new Vec3(0.7f, 0.7f, 0.7f), new Vec3(0.2f, 0.18f, 0.15f));
        IrradianceSh a = IrradianceSh.bake(env, 1024);
        IrradianceSh b = IrradianceSh.bake(env, 1024);
        for (int i = 0; i < 9; i++) {
            assertEquals(a.coefficients()[i], b.coefficients()[i], "coefficient " + i + " must be reproducible");
        }
    }

    @Test
    void aUniformSkyIrradiatesEveryNormalWithItsColor() {
        Vec3 color = new Vec3(0.5f, 0.6f, 0.7f);
        IrradianceSh sh = IrradianceSh.bake(Environment.gradient(color, color, color), 4096);

        Vec3 up = sh.evaluate(Vec3.UNIT_Y);
        assertEquals(0.5f, up.x(), 0.02f);
        assertEquals(0.6f, up.y(), 0.02f);
        assertEquals(0.7f, up.z(), 0.02f);
        // A uniform environment is direction-independent.
        Vec3 down = sh.evaluate(Vec3.UNIT_Y.negate());
        assertEquals(up.x(), down.x(), 0.02f);
        assertEquals(up.z(), down.z(), 0.02f);
    }

    @Test
    void aBrightSkyLightsUpwardNormalsMoreThanDownward() {
        IrradianceSh sh = IrradianceSh.bake(Environment.gradient(
                new Vec3(1, 1, 1), new Vec3(0.5f, 0.5f, 0.5f), new Vec3(0.05f, 0.05f, 0.05f)), 4096);
        assertTrue(sh.evaluate(Vec3.UNIT_Y).y() > sh.evaluate(Vec3.UNIT_Y.negate()).y(),
                "an up-facing normal catches the bright sky; a down-facing one catches the dark ground");
    }
}

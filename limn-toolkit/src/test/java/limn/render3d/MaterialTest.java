package limn.render3d;

import limn.graphics.BlendMode;
import limn.math.Vec3;
import limn.math.Vec4;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The material value types: opacity, and the withers that have to carry every
 * other component along. A wither that drops a field is the classic record bug:
 * it compiles, it reads fine, and one property silently reverts to a default.
 */
class MaterialTest {

    private static final float EPS = 1e-6f;

    @Test
    void materialsAreOpaqueUntilToldOtherwise() {
        assertTrue(Material.Unlit.of(1, 0, 0).isOpaque());
        assertTrue(Material.SimpleLit.of(1, 0, 0).isOpaque());
        assertTrue(Material.Pbr.of(1, 0, 0).isOpaque());
        assertTrue(new Material.Raw("", "").isOpaque());
        assertNull(Material.Pbr.of(1, 0, 0).blend());
    }

    @Test
    void blendMakesASurfaceTransparentAndNullTakesItBack() {
        Material.Pbr additive = Material.Pbr.of(1, 0.5f, 0.2f).blend(BlendMode.ADDITIVE);
        assertFalse(additive.isOpaque());
        assertEquals(BlendMode.ADDITIVE, additive.blend());
        assertTrue(additive.blend(null).isOpaque());

        Material.Unlit glow = Material.Unlit.of(1, 1, 1).blend(BlendMode.ADDITIVE);
        assertFalse(glow.isOpaque(), "an unlit surface composites too: a self-lit sprite");
    }

    @Test
    void everyPbrWitherPreservesEveryOtherComponent() {
        Material.Pbr full = Material.Pbr.of(0.2f, 0.4f, 0.6f)
                .metallic(0.75f)
                .roughness(0.25f)
                .emissive(new Vec3(1, 2, 3))
                .blend(BlendMode.NORMAL);

        // Each wither in turn: change one thing, and nothing else may move.
        assertPreserved(full, full.metallic(0.1f), "metallic");
        assertEquals(0.1f, full.metallic(0.1f).metallic(), EPS);
        assertPreserved(full, full.roughness(0.9f), "roughness");
        assertPreserved(full, full.baseColor(new Vec4(1, 1, 1, 0.5f)), "baseColor");
        assertPreserved(full, full.emissive(Vec3.ZERO), "emissive");
        assertPreserved(full, full.blend(BlendMode.ADDITIVE), "blend");
    }

    /** Everything the two materials should agree on, whichever single wither ran. */
    private static void assertPreserved(Material.Pbr before, Material.Pbr after, String changed) {
        int agreements = 0;
        agreements += before.metallic() == after.metallic() ? 1 : 0;
        agreements += before.roughness() == after.roughness() ? 1 : 0;
        agreements += before.baseColor().equals(after.baseColor()) ? 1 : 0;
        agreements += before.emissive().equals(after.emissive()) ? 1 : 0;
        agreements += before.blend() == after.blend() ? 1 : 0;
        assertEquals(4, agreements, "changing " + changed + " moved something else");
    }
}

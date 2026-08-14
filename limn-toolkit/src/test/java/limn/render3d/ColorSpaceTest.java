package limn.render3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CPU reference for the sRGB transfer function the PBR shader mirrors. */
class ColorSpaceTest {

    @Test
    void roundTripsAcrossTheRange() {
        for (int i = 0; i <= 100; i++) {
            float srgb = i / 100f;
            float back = ColorSpace.linearToSrgb(ColorSpace.srgbToLinear(srgb));
            assertEquals(srgb, back, 1e-4f, "round trip at " + srgb);
        }
    }

    @Test
    void endpointsAreExact() {
        assertEquals(0f, ColorSpace.srgbToLinear(0f), 1e-6f);
        assertEquals(1f, ColorSpace.srgbToLinear(1f), 1e-6f);
        assertEquals(0f, ColorSpace.linearToSrgb(0f), 1e-6f);
        assertEquals(1f, ColorSpace.linearToSrgb(1f), 1e-6f);
    }

    @Test
    void matchesKnownMidtoneValues() {
        // Well-known anchors: sRGB 0.5 decodes to ≈0.2140 linear, and back.
        assertEquals(0.2140f, ColorSpace.srgbToLinear(0.5f), 1e-3f);
        assertEquals(0.7354f, ColorSpace.linearToSrgb(0.5f), 1e-3f);
    }

    @Test
    void decodingDarkensMidtones() {
        // Gamma > 1: linearizing an sRGB midtone always lowers it.
        assertTrue(ColorSpace.srgbToLinear(0.5f) < 0.5f);
        assertTrue(ColorSpace.srgbToLinear(0.25f) < 0.25f);
    }
}

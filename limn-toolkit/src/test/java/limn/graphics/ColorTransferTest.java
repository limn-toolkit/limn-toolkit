package limn.graphics;

import limn.render3d.ColorSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one sRGB transfer function: the curve the luminance, the 3D pipeline's CPU reference and
 * the shaders all use, with the one threshold.
 */
class ColorTransferTest {

    @Test
    void theCurveIsContinuousAtTheStandardsThreshold() {
        // IEC 61966-2-1 joins the linear toe to the power curve at 0.04045. The two pieces
        // agree there to well within a code value; WCAG's older 0.03928 sat a third of a code
        // value away and was the other threshold this toolkit used to carry.
        double toe = 0.04045 / 12.92;
        double curve = Math.pow((0.04045 + 0.055) / 1.055, 2.4);
        assertEquals(toe, curve, 1e-5);
        assertEquals(toe, Color.srgbToLinear(0.04045), 1e-12);
    }

    @Test
    void decodeAndEncodeAreInverses() {
        for (int code = 0; code <= 255; code++) {
            double encoded = code / 255.0;
            assertEquals(encoded, Color.linearToSrgb(Color.srgbToLinear(encoded)), 1e-9,
                    "code " + code);
        }
    }

    @Test
    void theEndsAreFixedAndEncodingClampsPastThem() {
        assertEquals(0, Color.srgbToLinear(0), 0);
        assertEquals(1, Color.srgbToLinear(1), 1e-12);
        assertEquals(0, Color.linearToSrgb(0), 0);
        assertEquals(1, Color.linearToSrgb(1), 1e-12);
        assertEquals(1, Color.linearToSrgb(4.5), 1e-12, "past white encodes as white");
        assertEquals(0, Color.linearToSrgb(-0.5), 1e-12, "below black encodes as black");
    }

    @Test
    void colorSpaceIsTheSameCurveInSinglePrecision() {
        for (int code = 0; code <= 255; code += 5) {
            float c = code / 255f;
            assertEquals((float) Color.srgbToLinear(c), ColorSpace.srgbToLinear(c), 1e-7f);
            assertEquals((float) Color.linearToSrgb(c), ColorSpace.linearToSrgb(c), 1e-7f);
        }
    }

    @Test
    void relativeLuminanceIsTheWeightedLinearLight() {
        assertEquals(0, Color.BLACK.relativeLuminance(), 0);
        assertEquals(1, Color.WHITE.relativeLuminance(), 1e-12);
        // Middle grey: the encoded 0.5 is about 0.214 in linear light, and every channel
        // carries the same value, so the weights sum to it.
        assertEquals(Color.srgbToLinear(0.5), new Color(0.5f, 0.5f, 0.5f, 1f).relativeLuminance(), 1e-6);
        assertTrue(Color.rgb(0x00FF00).relativeLuminance() > Color.rgb(0xFF0000).relativeLuminance(),
                "green weighs more than red");
    }

    @Test
    void toByteClampsScalesAndRounds() {
        assertEquals(0, Color.toByte(0f));
        assertEquals(255, Color.toByte(1f));
        assertEquals(128, Color.toByte(0.5f));
        assertEquals(0, Color.toByte(-3f));
        assertEquals(255, Color.toByte(7f));
        assertEquals(0, Color.toByte(Float.NaN), "a NaN channel is black, never a stray byte");
        for (int code = 0; code <= 255; code++) {
            assertEquals(code, Color.toByte(code / 255f), "round trip of code " + code);
        }
    }
}

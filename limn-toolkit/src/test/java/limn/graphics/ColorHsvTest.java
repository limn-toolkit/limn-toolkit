package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The colour maths a picker is built on. Pure value conversion, no widgets.
 *
 * <p>The load-bearing one is {@link #hsvRoundTripsForEverySaturatedColour()}: the
 * picker holds hue/saturation/value and hands out RGB, so a conversion that drifts
 * moves the cursor a little every time a colour is read back.
 */
class ColorHsvTest {

    private static final float EPS = 1e-4f;

    @Test
    void hsvProducesThePrimaries() {
        assertColor(1, 0, 0, Color.hsv(0, 1, 1, 1));
        assertColor(1, 1, 0, Color.hsv(60, 1, 1, 1));
        assertColor(0, 1, 0, Color.hsv(120, 1, 1, 1));
        assertColor(0, 1, 1, Color.hsv(180, 1, 1, 1));
        assertColor(0, 0, 1, Color.hsv(240, 1, 1, 1));
        assertColor(1, 0, 1, Color.hsv(300, 1, 1, 1));
    }

    @Test
    void hueWrapsInBothDirections() {
        assertColor(1, 0, 0, Color.hsv(360, 1, 1, 1));
        assertColor(1, 0, 0, Color.hsv(720, 1, 1, 1));
        // A picker dragging past the end of the ramp, or a hue nudged below zero.
        assertColor(0, 1, 0, Color.hsv(-240, 1, 1, 1));
    }

    @Test
    void saturationAndValueReachGreyAndBlack() {
        assertColor(1, 1, 1, Color.hsv(210, 0, 1, 1));
        assertColor(0, 0, 0, Color.hsv(210, 1, 0, 1));
        assertColor(0.5f, 0.5f, 0.5f, Color.hsv(210, 0, 0.5f, 1));
    }

    @Test
    void hsvRoundTripsForEverySaturatedColour() {
        for (int h = 0; h < 360; h += 7) {
            for (int s = 1; s <= 10; s++) {
                for (int v = 1; v <= 10; v++) {
                    Color color = Color.hsv(h, s / 10f, v / 10f, 1);
                    String at = "h=" + h + " s=" + s / 10f + " v=" + v / 10f;
                    assertEquals(h, color.hue(), 0.05f, at);
                    assertEquals(s / 10f, color.saturation(), EPS, at);
                    assertEquals(v / 10f, color.value(), EPS, at);
                }
            }
        }
    }

    @Test
    void greyHasNoHueAndBlackHasNoSaturation() {
        // Why the picker keeps its own hue: neither of these can say where the
        // cursor was, so re-deriving would swing the field to red on the way
        // through black.
        assertEquals(0, Color.rgb(0x808080).hue());
        assertEquals(0, Color.rgb(0x808080).saturation(), EPS);
        assertEquals(0, Color.BLACK.saturation(), EPS);
        assertEquals(0, Color.BLACK.value(), EPS);
    }

    @Test
    void hexOmitsAlphaWhenOpaqueAndKeepsItOtherwise() {
        assertEquals("#FF8000", new Color(1f, 0.5019608f, 0f, 1f).toHex());
        assertEquals("#FF800080", new Color(1f, 0.5019608f, 0f, 0.5019608f).toHex());
        assertEquals("#000000", Color.BLACK.toHex());
    }

    @Test
    void hexParsesEveryCommonForm() {
        assertColor(1, 0, 0, Color.fromHex("#FF0000"));
        assertColor(1, 0, 0, Color.fromHex("ff0000"), "the hash is optional");
        assertColor(1, 0, 0, Color.fromHex("  #F00  "), "shorthand, and trimmed");
        assertEquals(0.5019608f, Color.fromHex("#00000080").a(), EPS);
        // #abc means #aabbcc, not #a0b0c0: the doubling rule, which is the one
        // thing shorthand parsers get wrong.
        assertColor(0.6666667f, 0.8f, 0.93333334f, Color.fromHex("#ACE"));
    }

    @Test
    void hexRejectsRatherThanThrows() {
        // A field being typed into is half-written most of the time; an exception
        // per keystroke is not a parser.
        assertNull(Color.fromHex("#FF"));
        assertNull(Color.fromHex("#FFFFF"));
        assertNull(Color.fromHex("#GGGGGG"));
        assertNull(Color.fromHex(""));
        assertNull(Color.fromHex(null));
    }

    @Test
    void hexRoundTripsThroughTheParser() {
        for (int rgb = 0; rgb < 0xFFFFFF; rgb += 7919) { // a prime, to hit odd values
            Color color = Color.rgb(rgb);
            Color back = Color.fromHex(color.toHex());
            assertEquals(color, back, color.toHex());
        }
    }

    private static void assertColor(float r, float g, float b, Color actual) {
        assertColor(r, g, b, actual, "color");
    }

    private static void assertColor(float r, float g, float b, Color actual, String message) {
        assertEquals(r, actual.r(), EPS, message + " red");
        assertEquals(g, actual.g(), EPS, message + " green");
        assertEquals(b, actual.b(), EPS, message + " blue");
    }
}

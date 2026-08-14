package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ink conversion, on its own. Naive CMYK, no profile; see {@link Color#cmyk}.
 *
 * <p>The one to keep is {@link #everySeparationPutsTheGreyInTheKey()}: it pins the
 * property that makes CMYK unlike the other two models the picker offers. Four
 * numbers describe a three-axis colour, so a colour has infinitely many
 * separations and {@link Color#toCmyk} picks exactly one of them, the one with
 * all the grey moved into the key. Anything that re-derives a row from a colour
 * therefore <em>cannot</em> return the row a user dialled in, which is why
 * {@code ColorPicker} remembers theirs instead of asking for it back.
 */
class ColorCmykTest {

    private static final float EPS = 1e-4f;

    @Test
    void theInksMakeTheColoursTheyAreNamedFor() {
        assertColor(0, 1, 1, Color.cmyk(1, 0, 0, 0, 1), "cyan");
        assertColor(1, 0, 1, Color.cmyk(0, 1, 0, 0, 1), "magenta");
        assertColor(1, 1, 0, Color.cmyk(0, 0, 1, 0, 1), "yellow");
        assertColor(1, 1, 1, Color.cmyk(0, 0, 0, 0, 1), "no ink at all");
    }

    @Test
    void theKeyOverridesEveryOtherInk() {
        // Full key is black however the other three are set, the one place the
        // separation is genuinely lossy, and the reason a picker on black used to
        // sit there ignoring every press on cyan.
        assertColor(0, 0, 0, Color.cmyk(0, 0, 0, 1, 1), "key alone");
        assertColor(0, 0, 0, Color.cmyk(1, 0.5f, 0.25f, 1, 1), "key over ink");
    }

    @Test
    void everySeparationPutsTheGreyInTheKey() {
        float[] out = new float[4];
        for (int r = 0; r <= 255; r += 5) {
            for (int g = 0; g <= 255; g += 5) {
                for (int b = 0; b <= 255; b += 5) {
                    Color color = new Color(r / 255f, g / 255f, b / 255f, 1);
                    color.toCmyk(out);
                    float leastInk = Math.min(out[0], Math.min(out[1], out[2]));
                    assertTrue(leastInk < EPS,
                            "a derived separation always empties one of C/M/Y into the key, "
                                    + "but " + color.toHex() + " left " + leastInk);
                }
            }
        }
    }

    @Test
    void aSeparationRebuildsTheColourItCameFrom() {
        float[] out = new float[4];
        for (int rgb : new int[]{0x000000, 0xFFFFFF, 0x808080, 0xE6661A, 0x3366CC, 0x00FF00}) {
            Color color = Color.rgb(rgb);
            color.toCmyk(out);
            assertColor(color.r(), color.g(), color.b(),
                    Color.cmyk(out[0], out[1], out[2], out[3], 1), color.toHex());
        }
    }

    @Test
    void inkOutsideTheRangeIsClamped() {
        assertColor(0, 1, 1, Color.cmyk(2, -1, -1, 0, 1), "over and under");
        assertColor(0, 0, 0, Color.cmyk(0, 0, 0, 2, 1), "key past full");
    }

    @Test
    void alphaPassesThroughUntouched() {
        assertEquals(0.25f, Color.cmyk(0.5f, 0, 0, 0, 0.25f).a(), EPS);
    }

    private static void assertColor(float r, float g, float b, Color actual, String message) {
        assertEquals(r, actual.r(), EPS, message + " red");
        assertEquals(g, actual.g(), EPS, message + " green");
        assertEquals(b, actual.b(), EPS, message + " blue");
    }
}

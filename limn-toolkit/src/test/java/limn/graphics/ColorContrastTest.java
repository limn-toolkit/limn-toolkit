package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legibility maths a palette is judged against. Pure value conversion, no widgets.
 *
 * <p>The anchors are the ones WCAG&nbsp;2.1 states outright (black on white is 21:1,
 * a colour on itself is 1:1, white is L*&nbsp;100), so a drifting implementation fails
 * here rather than in a palette review months later.
 */
class ColorContrastTest {

    @Test
    void blackOnWhiteIsTheMaximumTheScaleDefines() {
        assertEquals(21, Color.contrastRatio(Color.BLACK, Color.WHITE), 0.01);
    }

    @Test
    void aColourAgainstItselfIsOne() {
        assertEquals(1, Color.contrastRatio(Color.rgb(0x3B82F6), Color.rgb(0x3B82F6)), 1e-9);
    }

    @Test
    void theRatioDoesNotCareWhichArgumentIsTheInk() {
        Color ink = Color.rgb(0x1B2333);
        Color paper = Color.rgb(0xF4F6FA);
        assertEquals(Color.contrastRatio(ink, paper), Color.contrastRatio(paper, ink), 1e-12);
    }

    @Test
    void luminanceSpansTheWholeRangeAndIsWeightedForTheEye() {
        assertEquals(0, Color.BLACK.relativeLuminance(), 1e-12);
        assertEquals(1, Color.WHITE.relativeLuminance(), 1e-12);
        // The three primaries carry the sRGB weights, which is why a pure blue reads as
        // dark and a pure green as light at identical channel values.
        assertEquals(0.2126, Color.rgb(0xFF0000).relativeLuminance(), 1e-4);
        assertEquals(0.7152, Color.rgb(0x00FF00).relativeLuminance(), 1e-4);
        assertEquals(0.0722, Color.rgb(0x0000FF).relativeLuminance(), 1e-4);
    }

    /**
     * The reason {@code relativeLuminance} ignores alpha rather than pre-multiplying:
     * a half-transparent black is not a dark grey until something is behind it, and
     * which grey depends entirely on what that is.
     */
    @Test
    void alphaIsIgnoredAndCompositingIsTheCallersJob() {
        Color ghost = Color.BLACK.withAlpha(0.5f);
        assertEquals(Color.BLACK.relativeLuminance(), ghost.relativeLuminance(), 1e-12);

        Color overWhite = Color.WHITE.lerp(Color.BLACK, 0.5f);
        Color overBlue = Color.rgb(0x0000FF).lerp(Color.BLACK, 0.5f);
        assertTrue(overWhite.relativeLuminance() > overBlue.relativeLuminance(),
                "the same translucent ink must resolve differently over different backdrops");
    }

    @Test
    void lightnessIsPerceptualAndRunsZeroToOneHundred() {
        assertEquals(0, Color.BLACK.lightness(), 1e-9);
        assertEquals(100, Color.WHITE.lightness(), 1e-9);
        // Mid grey sits near the middle of the perceptual scale while its luminance is
        // nowhere near the middle of the linear one, the whole point of using L* for
        // elevation steps.
        Color midGrey = Color.rgb(0x808080);
        assertEquals(53.6, midGrey.lightness(), 0.5);
        assertTrue(midGrey.relativeLuminance() < 0.25);
    }

    /**
     * The step from a light canvas to the card on it is a real, visible step that the
     * contrast ratio describes as almost nothing, which is why elevation is asserted
     * in L* throughout this repository.
     */
    @Test
    void elevationIsVisibleInLightnessAndInvisibleInTheRatio() {
        Color canvas = Color.rgb(0xF9F8FC);
        Color card = Color.rgb(0xEAE3F3);
        assertTrue(Color.contrastRatio(canvas, card) < 1.2,
                "the ratio cannot express a light-palette elevation step");
        assertTrue(Math.abs(canvas.lightness() - card.lightness()) > 6,
                "L* can");
    }
}

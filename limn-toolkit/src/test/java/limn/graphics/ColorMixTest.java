package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A mix of two colours weighs each channel by its alpha, as the renderer's gradients do, and a
 * translucent colour over a backdrop is the source-over composite.
 */
class ColorMixTest {

    private static final float EPS = 1e-6f;

    private static void assertColor(float r, float g, float b, float a, Color actual) {
        assertEquals(r, actual.r(), EPS, "r of " + actual);
        assertEquals(g, actual.g(), EPS, "g of " + actual);
        assertEquals(b, actual.b(), EPS, "b of " + actual);
        assertEquals(a, actual.a(), EPS, "a of " + actual);
    }

    /** A fade to TRANSPARENT thins the colour: it went grey midway, TRANSPARENT being a clear black. */
    @Test
    void aFadeToTransparentKeepsItsColourAsItThins() {
        assertColor(1, 1, 1, 0.5f, Color.WHITE.lerp(Color.TRANSPARENT, 0.5f));
        assertColor(1, 1, 1, 0.5f, Color.TRANSPARENT.lerp(Color.WHITE, 0.5f));
        Color red = Color.rgba(0xFF0000, 1f);
        assertColor(1, 0, 0, 0.25f, red.lerp(Color.TRANSPARENT, 0.75f));
    }

    /** Between opaque colours nothing changes: the plain mix of the channels. */
    @Test
    void betweenOpaqueColoursItIsThePlainMix() {
        Color a = Color.rgb(0x204060);
        Color b = Color.rgb(0xA0C0E0);
        Color mixed = a.lerp(b, 0.3f);
        assertColor(a.r() + (b.r() - a.r()) * 0.3f, a.g() + (b.g() - a.g()) * 0.3f,
                a.b() + (b.b() - a.b()) * 0.3f, 1f, mixed);
        assertSame(a, a.lerp(b, 0f));
    }

    @Test
    void twoClearColoursMixToAClearOne() {
        assertColor(0.5f, 0.5f, 0.5f, 0f, Color.TRANSPARENT.lerp(Color.rgba(0xFFFFFF, 0f), 0.5f));
    }

    /** Source-over: opaque over an opaque backdrop, and the same colour when it is already opaque. */
    @Test
    void aTranslucentColourOverABackdropIsItsComposite() {
        Color veil = Color.rgba(0x000000, 0.4f);
        assertColor(0.6f, 0.6f, 0.6f, 1f, veil.compositeOver(Color.WHITE));
        Color ink = Color.rgb(0x336699);
        assertSame(ink, ink.compositeOver(Color.WHITE));
        assertColor(0, 0, 0, 0.64f, veil.compositeOver(Color.rgba(0x000000, 0.4f)));
        assertSame(Color.TRANSPARENT, Color.TRANSPARENT.compositeOver(Color.TRANSPARENT));
    }
}

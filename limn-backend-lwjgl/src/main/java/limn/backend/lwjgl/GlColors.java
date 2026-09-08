package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.render3d.ColorSpace;

import static org.lwjgl.opengl.GL33C.glClearColor;

/**
 * The two ways a clear colour reaches {@code glClearColor}, named, because the expression
 * {@code r * a, g * a, b * a, a} used to be written out at four sites and one of the four
 * linearized first, and nothing about the inline arithmetic said that the difference was
 * deliberate.
 *
 * <p>It is. A 2D framebuffer is sRGB-encoded and premultiplied (the blend the canvas draws with
 * is {@code ONE, ONE_MINUS_SRC_ALPHA}), so a clear writes the authored colour, premultiplied.
 * A 3D render target is linear (ADR 004) and premultiplied, so the same authored colour is
 * decoded on the way in, exactly as every 3D program decodes it. Same alpha rule, different
 * encoding; two methods so that a caller says which target it is clearing.
 */
final class GlColors {

    private GlColors() {
    }

    /**
     * Clears an sRGB-encoded, premultiplied target to an authored colour with straight alpha.
     *
     * @param r the red channel, sRGB-encoded
     * @param g the green channel, sRGB-encoded
     * @param b the blue channel, sRGB-encoded
     * @param a the straight alpha
     */
    static void clearPremultiplied(float r, float g, float b, float a) {
        glClearColor(r * a, g * a, b * a, a);
    }

    /** {@link #clearPremultiplied(float, float, float, float)} for a {@link Color}. */
    static void clearPremultiplied(Color color) {
        clearPremultiplied(color.r(), color.g(), color.b(), color.a());
    }

    /**
     * Clears a linear, premultiplied target to an authored sRGB colour with straight alpha:
     * each channel is decoded to linear light and then premultiplied.
     *
     * @param r the red channel, sRGB-encoded
     * @param g the green channel, sRGB-encoded
     * @param b the blue channel, sRGB-encoded
     * @param a the straight alpha
     */
    static void clearLinearPremultiplied(float r, float g, float b, float a) {
        glClearColor(ColorSpace.srgbToLinear(r) * a, ColorSpace.srgbToLinear(g) * a,
                ColorSpace.srgbToLinear(b) * a, a);
    }
}

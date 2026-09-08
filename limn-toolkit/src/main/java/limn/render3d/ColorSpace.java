package limn.render3d;

import limn.graphics.Color;

/**
 * How the channel values of a color or texture are encoded. Authoring happens in
 * {@link #SRGB} (what image editors and hex colors give you); lighting must run in
 * {@link #LINEAR}. The 3D pipeline decodes sRGB inputs to linear <em>in the shader</em>
 * and lights in linear; the render target stays linear, scene-referred, and
 * the re-encode to sRGB happens once in the 2D <em>composite</em>, as the tail of the
 * display transform (exposure, ACES, encode), when the target is drawn. It never
 * relies on GL sRGB texture formats, kept portable to GLSL ES.
 *
 * <p>The static helpers are the CPU reference for the same piecewise transfer
 * function the shaders implement (IEC 61966-2-1): the decode in the 3D pass, the
 * encode in the composite; they exist so the conversion can be unit-tested headless
 * and reused where a color must be linearized on the CPU.
 */
public enum ColorSpace {
    /** Gamma-encoded (perceptual). Base-color textures and authored colors live here. */
    SRGB,
    /** Physically linear. Lighting math and render targets accumulate here. */
    LINEAR;

    /** Decodes one sRGB-encoded channel in [0,1] to linear light; {@link Color#srgbToLinear}. */
    public static float srgbToLinear(float c) {
        return (float) Color.srgbToLinear(c);
    }

    /** Encodes one linear-light channel back to sRGB (the display OETF); {@link Color#linearToSrgb}. */
    public static float linearToSrgb(float c) {
        return (float) Color.linearToSrgb(c);
    }

    /**
     * The ACES filmic curve (Narkowicz's fit), clamped to [0,1], the shoulder that maps
     * unbounded scene-referred light onto a display's range. Input is linear light with
     * exposure already applied.
     */
    public static float tonemapAces(float x) {
        float mapped = (x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f);
        return Math.min(1f, Math.max(0f, mapped));
    }

    /**
     * The whole display transform for one channel: exposure, then {@link #tonemapAces}, then
     * {@link #linearToSrgb}. Scene-referred linear light in, display-referred sRGB out.
     *
     * <p>This is the CPU mirror of the transform the 2D composite applies when a render target is
     * drawn, and it exists so that reading a target back produces the picture that was on screen
     * rather than an approximation of it. The two implementations must agree: changing the curve
     * on one side and not the other makes an exported image differ from the window it came from,
     * with nothing to point at.
     *
     * @param linear   one linear-light channel, straight (not premultiplied); may exceed 1
     * @param exposure the exposure the pass was given
     */
    public static float displayTransform(float linear, float exposure) {
        return linearToSrgb(tonemapAces(linear * exposure));
    }
}

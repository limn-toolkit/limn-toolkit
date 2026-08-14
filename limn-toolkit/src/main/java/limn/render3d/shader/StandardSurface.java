package limn.render3d.shader;

import java.util.List;

/**
 * Builds the {@link SurfaceOutputs} for the built-in metallic-roughness material,
 * the same surface the hand-written reference shader computes, expressed as
 * neutral IR so one code generator can target multiple shading languages. The
 * expressions reference the engine's provided inputs by name (the framework
 * declares {@code u_baseColor}, {@code u_mr}, {@code u_emissiveHasTex},
 * {@code u_baseColorTex}, {@code u_normalTex}, {@code v_uv}, {@code v_color}).
 *
 * <p>Every optional input has a neutral default bound for it (a 1×1 white
 * texture, a 1×1 flat normal map, a per-vertex colour of opaque white, a normal
 * scale of zero), so this one surface covers every material without variants.
 * One surface means one compiled program, and that is worth more than the few
 * multiplies it costs: the pass's uniforms are program state, so a second program
 * would have to re-issue all of them or silently sample the wrong texture unit.
 */
public final class StandardSurface {

    private StandardSurface() {
    }

    /**
     * Base color = sRGB-decoded factor × sRGB-decoded base-color texture ×
     * per-vertex colour, with alpha the product of all three.
     *
     * <p>Three things about that sentence are load-bearing.
     *
     * <p><b>The texture's alpha is honoured.</b> It used to be dropped in favour of
     * the factor's, which makes a soft-edged sprite unreachable: the falloff that
     * turns a quad into a puff of smoke lives in exactly that channel.
     *
     * <p><b>The per-vertex colour is neither decoded nor clamped.</b> It is already
     * linear (glTF {@code COLOR_0} semantics), so passing it through
     * {@code srgbToLinear} would darken it; and a component above 1 is a legitimate
     * over-range tint, so clamping it would flatten the brightest thing in the
     * frame. A mesh without the attribute reads opaque white and nothing changes.
     *
     * <p><b>The per-vertex colour also modulates emissive</b>, which is what makes
     * an over-range tint mean anything. Base colour is reflectance: 1.6 there is
     * not "bright", it is unphysical, and with no light it is black. Emissive is
     * added after the BRDF, so it reaches the tonemap as light. This is a
     * deliberate step past glTF, where {@code COLOR_0} touches base colour only.
     * It is also the only way a per-vertex value can drive emission at all, since
     * the emissive factor is per material.
     */
    public static SurfaceOutputs metallicRoughness() {
        Expr.Ref baseColor = new Expr.Ref("u_baseColor", ShaderType.VEC4);
        Expr.Ref mr = new Expr.Ref("u_mr", ShaderType.VEC4);
        Expr.Ref emissiveHasTex = new Expr.Ref("u_emissiveHasTex", ShaderType.VEC4);
        Expr.Ref sampler = new Expr.Ref("u_baseColorTex", ShaderType.SAMPLER2D);
        Expr.Ref normalSampler = new Expr.Ref("u_normalTex", ShaderType.SAMPLER2D);
        Expr.Ref uv = new Expr.Ref("v_uv", ShaderType.VEC2);
        Expr.Ref vertexColor = new Expr.Ref("v_color", ShaderType.VEC4);

        Expr texel = new Expr.Sample(sampler, uv);
        Expr factorRgb = srgb(swizzle(baseColor, "rgb"));
        Expr textureRgb = srgb(swizzle(texel, "rgb"));
        Expr albedo = mul(mul(factorRgb, textureRgb), swizzle(vertexColor, "rgb"));
        Expr alpha = mul(mul(swizzle(baseColor, "a"), swizzle(texel, "a")),
                swizzle(vertexColor, "a"));
        Expr baseColorLinear = new Expr.Construct(ShaderType.VEC4, List.of(albedo, alpha));

        // Tangent-space sample, decoded from [0,1] to [-1,1]. The framework helper
        // applies the scale (u_mr.z) and builds the frame; a scale of 0 flattens the
        // sample to (0,0,1), which the helper maps back to the geometric normal
        // exactly: that is how "no normal map" is spelled, with no branch and no
        // second program.
        Expr tangentNormal = sub(mul(swizzle(new Expr.Sample(normalSampler, uv), "xyz"),
                Expr.Lit.of(2)), Expr.Lit.of(1));
        Expr normal = new Expr.Call("normalMapToWorld", ShaderType.VEC3,
                List.of(tangentNormal, swizzle(mr, "z")));

        return new SurfaceOutputs(
                baseColorLinear,
                swizzle(mr, "x"),
                swizzle(mr, "y"),
                mul(swizzle(emissiveHasTex, "xyz"), swizzle(vertexColor, "rgb")),
                normal);
    }

    private static Expr srgb(Expr rgb) {
        return new Expr.Call("srgbToLinear", ShaderType.VEC3, List.of(rgb));
    }

    private static Expr mul(Expr left, Expr right) {
        return new Expr.Binary(Expr.Op.MUL, left, right);
    }

    private static Expr sub(Expr left, Expr right) {
        return new Expr.Binary(Expr.Op.SUB, left, right);
    }

    private static Expr swizzle(Expr source, String pattern) {
        return new Expr.Swizzle(source, pattern);
    }
}

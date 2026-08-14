package limn.backend.lwjgl;

import limn.render3d.VertexAttribute;
import limn.render3d.shader.Expr;
import limn.render3d.shader.ShaderType;
import limn.render3d.shader.StandardSurface;
import limn.render3d.shader.SurfaceOutputs;
import limn.render3d.shader.TargetProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the GLSL the shader-IR generates for the standard metallic-roughness
 * surface: the portability core. A golden resource pins the whole
 * fragment/vertex (matching the hand-written reference shader), and targeted checks
 * cover the IR-driven surface expressions, the GLSL 330 ↔ ES 3.00 header swap,
 * determinism and the raw-escape profile guard. Pure string generation; no GL.
 */
class GlslCodegenTest {

    private static GlslCodegen.Generated standard(TargetProfile profile) {
        return GlslCodegen.generate(StandardSurface.metallicRoughness(), profile);
    }

    @Test
    void reproducesTheGoldenFragmentAndVertex() {
        GlslCodegen.Generated g = standard(TargetProfile.GLSL_330);
        assertEquals(golden("mesh_pbr.frag"), g.fragmentSource(), "generated fragment drifted from golden");
        assertEquals(golden("mesh_pbr.vert"), g.vertexSource(), "generated vertex drifted from golden");
    }

    @Test
    void emitsTheStandardSurfaceExpressions() {
        String frag = standard(TargetProfile.GLSL_330).fragmentSource();
        // The five surface outputs are what the IR DAG actually produces.
        assertTrue(frag.contains("vec4 baseColor = vec4(((srgbToLinear(u_baseColor.rgb) "
                + "* srgbToLinear(texture(u_baseColorTex, v_uv).rgb)) * v_color.rgb), "
                + "((u_baseColor.a * texture(u_baseColorTex, v_uv).a) * v_color.a));"), frag);
        assertTrue(frag.contains("float metallic = clamp(u_mr.x, 0.0, 1.0);"), frag);
        assertTrue(frag.contains("float roughness = clamp(u_mr.y, 0.04, 1.0);"), frag);
        assertTrue(frag.contains("vec3 emissive = (u_emissiveHasTex.xyz * v_color.rgb);"), frag);
        assertTrue(frag.contains("vec3 N = normalMapToWorld("
                + "((texture(u_normalTex, v_uv).xyz * 2.0) - 1.0), u_mr.z);"), frag);
        // The engine framework the DAG references is present.
        assertTrue(frag.contains("layout(std140) uniform Frame"), frag);
        assertTrue(frag.contains("layout(std140) uniform Lights"), frag);
        assertTrue(frag.contains("layout(std140) uniform Material"), frag);
        // Phase 5a shadow mapping (PCF) folded into the engine framework.
        assertTrue(frag.contains("float shadowFactor(vec3 worldPos, float NdotL)"), frag);
        assertTrue(frag.contains("radiance * NdotL * shadow"), frag);
        // Phase 5b image-based lighting (SH diffuse + analytic specular).
        assertTrue(frag.contains("vec3 shIrradiance(vec3 n)"), frag);
        assertTrue(frag.contains("vec3 skyRadiance(vec3 dir)"), frag);
        assertTrue(frag.contains("if (u_iblEnabled > 0.5)"), frag);
        // Phase 5c normal mapping: a per-pixel cotangent frame, no TANGENT attribute.
        assertTrue(frag.contains("vec3 normalMapToWorld(vec3 tangentNormal, float scale)"), frag);
        assertTrue(frag.contains("inversesqrt(max(dot(T, T), max(dot(B, B), 1e-12)))"), frag);
    }

    @Test
    void theFragmentWritesLinearSceneReferredLight() {
        // ADR 004: the render target is linear, premultiplied, scene-referred, and
        // the display transform (exposure → ACES → sRGB encode) runs ONCE, in the
        // 2D composite. A tonemap or encode reintroduced here would apply twice,
        // and the pass no longer reads exposure either (it rides on the target).
        String frag = standard(TargetProfile.GLSL_330).fragmentSource();
        assertFalse(frag.contains("tonemapACES"), frag);
        assertFalse(frag.contains("linearToSrgb"), frag);
        assertFalse(frag.contains("u_cameraPos.w;"),
                "exposure belongs to the composite's display transform, not to the pass");
        // The positive statement: linear light, premultiplied, written as-is.
        assertTrue(frag.contains("o_color = vec4(color * alpha, alpha);"), frag);
        // Texture decode stays the pass's job: inputs are still authored sRGB.
        assertTrue(frag.contains("srgbToLinear"), frag);
    }

    @Test
    void theCotangentFrameNegatesTheBitangentSoGreenPointsUp() {
        // The convention this toolkit follows is glTF's: a normal map's green channel
        // is +Y and points toward the TOP of the image. The derivative-built bitangent
        // runs along increasing v, which is DOWN an image, so it has to enter negated.
        //
        // Pinned as a string because the sign has no other observable in a unit test,
        // and it is worth pinning, because unnegated is not a crash, a warning or a
        // visibly broken texture. It is every surface lit from the wrong vertical
        // direction, which reads as the lights being in the wrong place.
        String frag = standard(TargetProfile.GLSL_330).fragmentSource();
        assertTrue(frag.contains("mat3(T * invMax, -B * invMax, Ng)"), frag);
        assertFalse(frag.contains("mat3(T * invMax, B * invMax, Ng)"),
                "an unnegated bitangent is the DirectX convention, not glTF's");
    }

    @Test
    void theNormalMapIsSampledAsDataNotAsColour() {
        // srgbToLinear on a normal map tilts every surface subtly and consistently
        // wrong, and it reads as a lighting bug rather than an authoring one.
        String frag = standard(TargetProfile.GLSL_330).fragmentSource();
        assertFalse(frag.contains("srgbToLinear(texture(u_normalTex"), frag);
        assertFalse(frag.contains("srgbToLinear(texture(u_normalTex, v_uv).xyz)"), frag);
    }

    @Test
    void thePerVertexTintIsNeitherDecodedNorClamped() {
        // v_color is already linear (glTF COLOR_0), so decoding it would darken it;
        // and an over-range component is a legitimate HDR tint, so clamping it would
        // flatten the brightest thing in the frame.
        String frag = standard(TargetProfile.GLSL_330).fragmentSource();
        assertFalse(frag.contains("srgbToLinear(v_color"), frag);
        assertFalse(frag.contains("clamp(v_color"), frag);
        // And it must reach emissive, not only albedo: base colour is reflectance,
        // where 1.6 is unphysical and unlit is black.
        assertTrue(frag.contains("vec3 emissive = (u_emissiveHasTex.xyz * v_color.rgb);"), frag);
    }

    @Test
    void everyReferenceTheSurfaceReadsIsDeclaredByTheFramework() {
        // A surface naming an input the framework does not declare compiles here and
        // fails as a GLSL link error on a device. Walking the DAG turns that into a
        // unit failure: the shaders are never compiled anywhere in this suite.
        String frag = standard(TargetProfile.GLSL_330).fragmentSource();
        SurfaceOutputs surface = StandardSurface.metallicRoughness();
        Set<String> refs = new TreeSet<>();
        for (Expr root : List.of(surface.baseColor(), surface.metallic(),
                surface.roughness(), surface.emissive(), surface.normal())) {
            collectRefs(root, refs);
        }
        assertEquals(Set.of("u_baseColor", "u_baseColorTex", "u_emissiveHasTex", "u_mr",
                "u_normalTex", "v_color", "v_uv"), refs, "the standard surface's inputs");
        for (String ref : refs) {
            assertTrue(frag.contains(" " + ref + ";") || frag.contains(" " + ref + "\n"),
                    "the framework never declares " + ref);
        }
    }

    // --------------------------------------------- application-supplied surfaces

    @Test
    void anApplicationSurfaceGetsItsOwnDeclarationsAndTheSharedFramework() {
        // A Material.Surface names its own samplers and vec4s; the backend declares
        // them after the framework's, and the surface's expressions are spliced into
        // the same main() the built-in material uses, which is what makes it lit.
        SurfaceOutputs custom = new SurfaceOutputs(
                new Expr.Sample(new Expr.Ref("u_sheet", ShaderType.SAMPLER2D),
                        new Expr.Ref("v_uv1", ShaderType.VEC2)),
                Expr.Lit.of(0f),
                new Expr.Swizzle(new Expr.Ref("u_knobs", ShaderType.VEC4), "x"),
                new Expr.Swizzle(new Expr.Ref("v_params", ShaderType.VEC4), "yzw"),
                null);
        String frag = GlslCodegen.generate(custom,
                "\nuniform sampler2D u_sheet;\nuniform vec4 u_knobs;\n",
                TargetProfile.GLSL_330).fragmentSource();

        assertTrue(frag.contains("uniform sampler2D u_sheet;"), frag);
        assertTrue(frag.contains("uniform vec4 u_knobs;"), frag);
        assertTrue(frag.contains("vec4 baseColor = texture(u_sheet, v_uv1);"), frag);
        assertTrue(frag.contains("float roughness = clamp(u_knobs.x, 0.04, 1.0);"), frag);
        assertTrue(frag.contains("vec3 emissive = v_params.yzw;"), frag);
        // It reaches the lights, the shadow map and the IBL: the whole reason this
        // exists rather than an application writing raw GLSL.
        assertTrue(frag.contains("layout(std140) uniform Lights"), frag);
        assertTrue(frag.contains("float shadowFactor(vec3 worldPos, float NdotL)"), frag);
        // No normal expression means the geometric one, exactly as for a built-in.
        assertTrue(frag.contains("vec3 N = normalize(v_normal);"), frag);
    }

    @Test
    void theExtraPerVertexChannelsAreDeclaredForEverySurface() {
        // Declared unconditionally, so there is one vertex program for every surface.
        // A mesh that carries neither reads the context's generic values, which the
        // backend sets to zero, hence the rule that zero should be a surface's
        // identity for them.
        GlslCodegen.Generated g = standard(TargetProfile.GLSL_330);
        assertTrue(g.vertexSource().contains("layout(location = 4) in vec2 a_uv1;"));
        assertTrue(g.vertexSource().contains("layout(location = 5) in vec4 a_params;"));
        assertTrue(g.vertexSource().contains("layout(location = 6) in vec4 a_params1;"));
        assertTrue(g.vertexSource().contains("v_uv1 = a_uv1;"));
        assertTrue(g.vertexSource().contains("v_params = a_params;"));
        assertTrue(g.vertexSource().contains("v_params1 = a_params1;"));
        assertTrue(g.fragmentSource().contains("in vec2 v_uv1;"));
        assertTrue(g.fragmentSource().contains("in vec4 v_params;"));
        assertTrue(g.fragmentSource().contains("in vec4 v_params1;"));
        // The locations are the enum's, not this file's idea of them: a shader input
        // bound to the wrong slot reads another attribute's bytes and shades plausibly.
        assertEquals(4, VertexAttribute.UV1.location);
        assertEquals(5, VertexAttribute.PARAMS.location);
        assertEquals(6, VertexAttribute.PARAMS1.location);
        // And the built-in surface still does not read them.
        assertFalse(g.fragmentSource().contains("vec3 emissive = (u_emissiveHasTex.xyz * v_params"),
                g.fragmentSource());
    }

    // ------------------------------------------------- application diffuse response

    /** A surface that scales its diffuse lobe by a light-direction-dependent term. */
    private static SurfaceOutputs withResponse(Expr response) {
        return SurfaceOutputs.of(Expr.Lit.of(1f, 1f, 1f, 1f), Expr.Lit.of(0f), Expr.Lit.of(1f),
                Expr.Lit.of(0f, 0f, 0f)).withDiffuseResponse(response);
    }

    @Test
    void noDiffuseResponseLeavesTheAccumulateExactlyAsItWas() {
        // The claim this test exists for is not "it still compiles", it is that the
        // generated text is unchanged, because the distributed form below is equal to
        // this one in real arithmetic and NOT in IEEE, so a surface that opted in by
        // accident would shift the last bits of every lit pixel in every existing app.
        String frag = standard(TargetProfile.GLSL_330).fragmentSource();
        assertTrue(frag.contains(
                "        Lo += (kd * albedo / PI + specular) * radiance * NdotL * shadow;"), frag);
        assertFalse(frag.contains("diffuseResponse"), frag);
    }

    @Test
    void aDiffuseResponseDistributesTheCosineAcrossTheTwoLobes() {
        String frag = GlslCodegen.generate(withResponse(
                new Expr.Swizzle(new Expr.Ref("v_params", ShaderType.VEC4), "x")),
                "", TargetProfile.GLSL_330).fragmentSource();
        assertTrue(frag.contains("        float diffuseResponse = v_params.x;"), frag);
        // The diffuse half is scaled by the response and the specular half keeps NdotL:
        // a response says how light is SCATTERED, never where it reflects.
        assertTrue(frag.contains("        Lo += (kd * albedo / PI * diffuseResponse "
                + "+ specular * NdotL) * radiance * shadow;"), frag);
        assertFalse(frag.contains("(kd * albedo / PI + specular) * radiance * NdotL"), frag);
    }

    @Test
    void aDiffuseResponseCanReadTheLightDirectionAndTheTermItReplaces() {
        // Both are locals of the light loop, so this only compiles if the expression is
        // spliced INSIDE it. Splicing it above main()'s loop would put the whole feature
        // one light behind, and would read as a shading bug, not a placement one.
        String frag = GlslCodegen.generate(withResponse(new Expr.Call("max", ShaderType.FLOAT,
                List.of(new Expr.Swizzle(new Expr.Call("worldToTangent", ShaderType.VEC3,
                                List.of(new Expr.Ref("L", ShaderType.VEC3))), "z"),
                        new Expr.Ref("NdotL", ShaderType.FLOAT)))),
                "", TargetProfile.GLSL_330).fragmentSource();
        int loop = frag.indexOf("for (int i = 0; i < MAX_LIGHTS; i++)");
        int response = frag.indexOf("float diffuseResponse =");
        int close = frag.indexOf("vec3 ambientLight;");
        assertTrue(loop > 0 && response > loop && response < close,
                "the response must be evaluated inside the light loop");
        assertTrue(frag.contains("max(worldToTangent(L).z, NdotL)"), frag);
        // L is declared before the response, NdotL too; otherwise this is a compile
        // error nobody sees until a real GL context links the program.
        assertTrue(frag.indexOf("float NdotL = max(dot(N, L), 0.0);") < response, frag);
    }

    @Test
    void theTangentFrameHelperNormalizesItsAxes() {
        // worldToTangent and normalMapToWorld build the SAME frame from the same
        // derivatives and scale it differently on purpose: the normal map stretches
        // with the geometry, a direction does not. If these two ever converge, a
        // stretched quad starts reporting a light as swinging while it stands still.
        String frag = standard(TargetProfile.GLSL_330).fragmentSource();
        assertTrue(frag.contains("vec3 worldToTangent(vec3 v) {"), frag);
        assertTrue(frag.contains("return vec3(dot(v, T / tl), dot(v, -B / bl), dot(v, Ng));"), frag);
        // The bitangent is negated in BOTH, which is the green-up convention. Getting
        // it right in one and not the other lights a surface from one vertical
        // direction and places its lights in the other.
        assertTrue(frag.contains("mat3(T * invMax, -B * invMax, Ng)"), frag);
        assertTrue(frag.contains("inversesqrt(max(dot(T, T), max(dot(B, B), 1e-12)))"), frag);
    }

    @Test
    void aDiffuseResponseStaysInsideTheEs300Subset() {
        SurfaceOutputs custom = withResponse(new Expr.Swizzle(
                new Expr.Call("worldToTangent", ShaderType.VEC3,
                        List.of(new Expr.Ref("L", ShaderType.VEC3))), "y"));
        String body330 = GlslCodegen.generate(custom, "", TargetProfile.GLSL_330)
                .fragmentSource().substring(TargetProfile.GLSL_330.header.length());
        String bodyEs = GlslCodegen.generate(custom, "", TargetProfile.GLSL_ES_300)
                .fragmentSource().substring(TargetProfile.GLSL_ES_300.header.length());
        assertEquals(body330, bodyEs, "a diffuse response is portable or it is not portable");
    }

    @Test
    void anApplicationSurfaceStaysInsideTheEs300Subset() {
        SurfaceOutputs custom = SurfaceOutputs.of(
                new Expr.Call("mix", ShaderType.VEC4, List.of(
                        new Expr.Sample(new Expr.Ref("u_a", ShaderType.SAMPLER2D),
                                new Expr.Ref("v_uv", ShaderType.VEC2)),
                        new Expr.Sample(new Expr.Ref("u_a", ShaderType.SAMPLER2D),
                                new Expr.Ref("v_uv1", ShaderType.VEC2)),
                        new Expr.Swizzle(new Expr.Ref("v_params", ShaderType.VEC4), "x"))),
                Expr.Lit.of(0f), Expr.Lit.of(1f), Expr.Lit.of(0f, 0f, 0f));
        String declarations = "\nuniform sampler2D u_a;\n";
        String body330 = GlslCodegen.generate(custom, declarations, TargetProfile.GLSL_330)
                .fragmentSource().substring(TargetProfile.GLSL_330.header.length());
        String bodyEs = GlslCodegen.generate(custom, declarations, TargetProfile.GLSL_ES_300)
                .fragmentSource().substring(TargetProfile.GLSL_ES_300.header.length());
        assertEquals(body330, bodyEs, "an application surface is portable or it is not portable");
    }

    /** Every {@link Expr.Ref} name reachable from {@code node}, samplers included. */
    private static void collectRefs(Expr node, Set<String> out) {
        if (node instanceof Expr.Ref ref) {
            out.add(ref.name());
        } else if (node instanceof Expr.Sample sample) {
            collectRefs(sample.sampler(), out);
            collectRefs(sample.uv(), out);
        } else if (node instanceof Expr.Swizzle swizzle) {
            collectRefs(swizzle.source(), out);
        } else if (node instanceof Expr.Binary binary) {
            collectRefs(binary.left(), out);
            collectRefs(binary.right(), out);
        } else if (node instanceof Expr.Call call) {
            call.args().forEach(a -> collectRefs(a, out));
        } else if (node instanceof Expr.Construct construct) {
            construct.args().forEach(a -> collectRefs(a, out));
        }
    }

    @Test
    void es300SwapsOnlyTheHeader() {
        GlslCodegen.Generated glsl330 = standard(TargetProfile.GLSL_330);
        GlslCodegen.Generated es300 = standard(TargetProfile.GLSL_ES_300);

        assertTrue(es300.fragmentSource().startsWith("#version 300 es\nprecision highp float;"),
                es300.fragmentSource().substring(0, 60));
        assertTrue(glsl330.fragmentSource().startsWith("#version 330 core\n"));

        // Everything after the profile header is byte-identical between profiles.
        String bodyEs = es300.fragmentSource().substring(TargetProfile.GLSL_ES_300.header.length());
        String body330 = glsl330.fragmentSource().substring(TargetProfile.GLSL_330.header.length());
        assertEquals(body330, bodyEs, "profiles must share one body");
    }

    @Test
    void isDeterministic() {
        assertEquals(standard(TargetProfile.GLSL_330).fragmentSource(),
                standard(TargetProfile.GLSL_330).fragmentSource());
    }

    @Test
    void rejectsARawNodeTargetingAnotherProfile() {
        // A surface whose base color is raw GLSL written for GLSL 330 only.
        SurfaceOutputs raw = SurfaceOutputs.of(
                new Expr.Raw(TargetProfile.GLSL_330, "vec4(1.0)", ShaderType.VEC4),
                new Expr.Lit(new float[]{0f}),
                new Expr.Lit(new float[]{0.5f}),
                new Expr.Construct(ShaderType.VEC3, List.of(new Expr.Lit(new float[]{0f}))));

        assertDoesNotThrow(() -> GlslCodegen.generate(raw, TargetProfile.GLSL_330),
                "matching profile should compile");
        assertThrows(IllegalStateException.class,
                () -> GlslCodegen.generate(raw, TargetProfile.GLSL_ES_300),
                "raw node for GLSL_330 must be rejected when targeting ES 3.00");
    }

    private static String golden(String name) {
        try (InputStream in = GlslCodegenTest.class.getResourceAsStream(
                "/limn/backend/lwjgl/golden/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing golden resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

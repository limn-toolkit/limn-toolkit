package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the color-space contract of ADR 004 onto the hand-written shader
 * sources: the 3D target is linear, premultiplied, scene-referred, so every
 * program writing into it decodes its authored-sRGB colors, and the 2D
 * composite (alone) applies the display transform. Resource reads plus the
 * sources embedded in {@link Gl3DContext} (skybox, debug lines and the three
 * bloom programs), no GL:
 * the defect this defends against is a shader edit that quietly puts an encode
 * back in a pass (or drops the decode), which no unit test would otherwise see
 * and which reads as a lighting bug on screen.
 */
class ShaderColorSpaceTest {

    @Test
    void unmanagedMeshShadersDecodeTheirAuthoredColors() {
        // Unlit and simple-lit take authored-sRGB uniforms; writing them raw into
        // a linear target is the finding-2 defect (an Unlit red and a PBR surface
        // lit to the same color landing on different pixel values).
        for (String name : new String[]{"mesh_unlit.frag", "mesh_lit.frag", "cube.frag"}) {
            String source = shader(name);
            assertTrue(source.contains("srgbToLinear("), name + " must decode sRGB to linear");
        }
    }

    @Test
    void unlitPremultipliesItsOutput() {
        // The target is premultiplied; a straight-alpha unlit surface would
        // blend wrongly under the shared premultiplied factors (GlBlend).
        String source = shader("mesh_unlit.frag");
        assertTrue(source.contains("srgbToLinear(u_color.rgb) * u_color.a"), source);
    }

    @Test
    void theCompositeAppliesTheDisplayTransformExactlyOnce() {
        String canvas = shader("canvas.frag");
        // The HDR-surface branch (kind 7): exposure → ACES → sRGB encode, behind
        // a function so a selectable transform later swaps the body, not the
        // dispatch (ADR 004 §8).
        assertTrue(canvas.contains("vec3 displayTransform(vec3"), canvas);
        assertTrue(canvas.contains("u_exposure"), canvas);
        assertTrue(canvas.contains("tonemapACES"), canvas);
        assertTrue(canvas.contains("linearToSrgb"), canvas);
        assertTrue(canvas.contains("kind >= 6.5"), "the HDR branch dispatches at kind 7");
        // The un-premultiply is not optional: ACES is non-linear, so tonemapping
        // alpha-scaled RGB would shade a translucent bright surface differently
        // from an opaque one of the same color (ADR 004 §3.3).
        assertTrue(canvas.contains("texel.rgb / max(texel.a,"), canvas);
    }

    @Test
    void noPassShaderEncodesForDisplay() {
        // linearToSrgb in a 3D pass shader means display-referred output: the
        // transform would then run twice (once more in the composite). The skybox
        // is one of the two programs ADR 004 §3.2 deleted the tonemap from (the
        // other, PBR, is pinned by GlslCodegenTest and the golden).
        for (String name : new String[]{"mesh_unlit.frag", "mesh_lit.frag", "cube.frag"}) {
            String source = shader(name);
            assertFalse(source.contains("linearToSrgb"), name + " must write linear, not encode");
            assertFalse(source.contains("tonemapACES"), name + " must not tonemap");
        }
        assertFalse(Gl3DContext.SKYBOX_FRAG.contains("linearToSrgb"),
                "the skybox must write linear radiance, not encode");
        assertFalse(Gl3DContext.SKYBOX_FRAG.contains("tonemapACES"),
                "the skybox must not tonemap");
        assertTrue(Gl3DContext.SKYBOX_FRAG.contains("skyRadiance(dir)"),
                Gl3DContext.SKYBOX_FRAG);
    }

    @Test
    void debugLinesDecodeRgbButKeepStraightAlpha() {
        // Debug colors are authored sRGB (decode) but STRAIGHT alpha:
        // drawDebugLines sets its own SRC_ALPHA blend, so premultiplying here
        // would fade every translucent gizmo twice (ADR 004 §3.2).
        assertTrue(Gl3DContext.LINE_FRAG.contains(
                "fragColor = vec4(srgbToLinear(v_color.rgb), v_color.a);"),
                Gl3DContext.LINE_FRAG);
        assertFalse(Gl3DContext.LINE_FRAG.contains("linearToSrgb"),
                "debug lines must write linear, not encode");
    }

    @Test
    void bloomChainStaysLinearAndPremultiplied() {
        // The whole chain runs between the resolve and the composite (ADR 005),
        // in linear premultiplied scene-referred light: an encode or tonemap in
        // any of its three programs would run the display transform twice.
        for (String source : new String[]{Gl3DContext.BLOOM_BRIGHT_FRAG,
                Gl3DContext.BLOOM_BLUR_FRAG, Gl3DContext.BLOOM_COMBINE_FRAG}) {
            assertFalse(source.contains("linearToSrgb"), "bloom must write linear, not encode");
            assertFalse(source.contains("tonemapACES"), "bloom must not tonemap");
            assertFalse(source.contains("srgbToLinear"), "bloom reads linear input: nothing to decode");
        }
        // The bright pass thresholds in premultiplied space (rgb is already
        // alpha-scaled) and its alpha tracks surviving light, the two halves
        // of finding 3's edge behavior.
        assertTrue(Gl3DContext.BLOOM_BRIGHT_FRAG.contains("u_threshold * texel.a"),
                Gl3DContext.BLOOM_BRIGHT_FRAG);
        assertTrue(Gl3DContext.BLOOM_BRIGHT_FRAG.contains("texel.a * min(kept / had, 1.0)"),
                Gl3DContext.BLOOM_BRIGHT_FRAG);
        // The coverage cap: additive blending deposits rgb at alpha 0, which
        // the composite shows as nothing; without the cap that invisible
        // light passes every threshold (t × 0) and haloes covered neighbours.
        assertTrue(Gl3DContext.BLOOM_BRIGHT_FRAG.contains("min(bright, vec3(16.0) * texel.a)"),
                Gl3DContext.BLOOM_BRIGHT_FRAG);
        // The combine carries alpha and clamps it: an additive alpha on a
        // float target would not saturate, and past 1 the composite's (1 − a)
        // goes negative under the UI.
        assertTrue(Gl3DContext.BLOOM_COMBINE_FRAG.contains(
                "clamp(glow.a * u_intensity, 0.0, 1.0)"), Gl3DContext.BLOOM_COMBINE_FRAG);
    }

    private static String shader(String name) {
        try (InputStream in = ShaderColorSpaceTest.class.getResourceAsStream(
                "/limn/backend/lwjgl/shaders/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing shader resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

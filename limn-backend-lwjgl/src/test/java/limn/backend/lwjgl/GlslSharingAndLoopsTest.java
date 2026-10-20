package limn.backend.lwjgl;

import limn.render3d.shader.Expr;
import limn.render3d.shader.ShaderType;
import limn.render3d.shader.StandardSurface;
import limn.render3d.shader.SurfaceOutputs;
import limn.render3d.shader.TargetProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static limn.backend.lwjgl.IrSurfaces.add;
import static limn.backend.lwjgl.IrSurfaces.call;
import static limn.backend.lwjgl.IrSurfaces.mul;
import static limn.backend.lwjgl.IrSurfaces.ref;
import static limn.backend.lwjgl.IrSurfaces.sub;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the generator writes for a surface that uses a node twice and for one that loops (ADR
 * 077): a shared node written once, an iteration that grows with its steps and not as a power of
 * them, a loop that is a bounded {@code for} with its exit, and all of it inside the subset GLSL
 * 3.30 and GLSL ES 3.00 share. Pure string generation; the device tests are {@link SurfaceLoopGlTest}.
 */
class GlslSharingAndLoopsTest {

    private static String fragment(SurfaceOutputs surface) {
        return GlslCodegen.generate(surface, "\nuniform sampler2D u_sheet;\nuniform sampler2D u_mask;\n"
                + "uniform vec4 u_knobs;\n", TargetProfile.GLSL_330).fragmentSource();
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void aNodeUsedTwiceIsWrittenOnce() {
        Expr texel = new Expr.Sample(new Expr.Ref("u_sheet", ShaderType.SAMPLER2D), ref("v_uv", ShaderType.VEC2));
        // Two equal nodes built apart, as an application builds them: shared by value, not by identity.
        Expr again = new Expr.Sample(new Expr.Ref("u_sheet", ShaderType.SAMPLER2D), ref("v_uv", ShaderType.VEC2));
        String frag = fragment(SurfaceOutputs.of(texel, Expr.Lit.of(0f), Expr.Lit.of(1f),
                new Expr.Swizzle(again, "rgb")));
        assertEquals(1, count(frag, "texture(u_sheet, v_uv)"), frag);
        assertTrue(frag.contains("    vec4 ir_t0 = texture(u_sheet, v_uv);\n"), frag);
        assertTrue(frag.contains("    vec4 baseColor = ir_t0;\n"), frag);
        assertTrue(frag.contains("    vec3 emissive = ir_t0.rgb;\n"), frag);
        assertTrue(frag.indexOf("vec4 ir_t0") < frag.indexOf("vec4 baseColor"), "declared before it is read");
    }

    @Test
    void anUnrolledIterationGrowsWithItsStepsNotAsAPowerOfThem() {
        // Nine steps of z ↦ z² + c read z 4^9 = 262,144 times written as a tree: megabytes of
        // source for a driver to parse. Shared, each step is written once.
        String frag = fragment(IrSurfaces.unrolled(9));
        String framework = fragment(IrSurfaces.emitting(Expr.Lit.of(0f, 0f, 0f)));
        assertTrue(frag.length() - framework.length() < 4_000,
                "nine steps cost " + (frag.length() - framework.length()) + " characters");
    }

    @Test
    void theStandardSurfaceSamplesItsBaseColourOnce() {
        String frag = GlslCodegen.generate(StandardSurface.metallicRoughness(), TargetProfile.GLSL_330)
                .fragmentSource();
        assertEquals(1, count(frag, "texture(u_baseColorTex, v_uv)"), frag);
        assertEquals(1, count(frag, "texture(u_normalTex, v_uv)"), frag);
    }

    @Test
    void writtenInPlaceAsBeforeTheSameSurfaceReadsItsNodesWhereTheyAre() {
        // The switch the pixel test holds the two forms with: off, nothing is shared.
        boolean was = GlslCodegen.shareSubexpressions;
        GlslCodegen.shareSubexpressions = false;
        try {
            String frag = GlslCodegen.generate(StandardSurface.metallicRoughness(), TargetProfile.GLSL_330)
                    .fragmentSource();
            assertEquals(2, count(frag, "texture(u_baseColorTex, v_uv)"), frag);
            assertFalse(frag.contains("ir_t"), frag);
        } finally {
            GlslCodegen.shareSubexpressions = was;
        }
    }

    @Test
    void aLoopIsABoundedForThatAsksItsExitBeforeEachTurn() {
        String frag = fragment(IrSurfaces.mandelbrot(256));
        assertTrue(frag.contains("    vec4 ir_s0 = vec4(0.0, 0.0, 0.0, 0.0);\n"), frag);
        assertTrue(frag.contains("    for (int ir_i0 = 0; ir_i0 < 256; ++ir_i0) {\n"), frag);
        int loop = frag.indexOf("for (int ir_i0");
        int exit = frag.indexOf("        if (", loop);
        int brk = frag.indexOf("            break;\n", loop);
        int assign = frag.indexOf("        ir_s0 = ", loop);
        assertTrue(exit > loop && brk > exit && assign > brk, "ask, break, then step: " + frag.substring(loop));
        assertTrue(frag.substring(exit, brk).contains(" - 256.0) > 0.0) {"), frag.substring(exit, brk));
        // The loop is read after it by its variable, twice, never written again.
        assertEquals(1, count(frag, "for (int ir_i0"), frag);
        assertTrue(frag.contains("ir_s0.z"), frag);
        assertTrue(frag.contains("ir_s0.xy"), frag);
    }

    @Test
    void whatATurnDoesNotChangeIsComputedBeforeTheLoop() {
        String frag = fragment(IrSurfaces.mandelbrot(256));
        int loop = frag.indexOf("for (int ir_i0");
        int c = frag.indexOf("((v_uv - vec2(0.6, 0.5)) * vec2(3.0, 2.4))");
        assertTrue(c >= 0 && c < loop, "c depends on the pixel, not on the turn");
        assertEquals(1, count(frag, "(v_uv - vec2(0.6, 0.5))"), frag);
    }

    @Test
    void aLoopWithoutAnExitRunsEveryTurnAndMaySample() {
        String frag = fragment(IrSurfaces.blurLoop());
        assertTrue(frag.contains("for (int ir_i0 = 0; ir_i0 < 8; ++ir_i0) {"), frag);
        assertFalse(frag.contains("break;\n        }\n        ir_s0"), frag);
        int loop = frag.indexOf("for (int ir_i0");
        assertTrue(frag.indexOf("texture(u_sheet", loop) > loop, "the sample is a turn's work");
    }

    @Test
    void twoLoopsInOneSurfaceEachGetTheirOwnVariables() {
        Expr.Ref z = new Expr.Ref("z", ShaderType.FLOAT);
        Expr.Loop half = new Expr.Loop("z", Expr.Lit.of(0f), 5, add(z, Expr.Lit.of(0.1f)), null);
        Expr.Loop third = new Expr.Loop("z", Expr.Lit.of(0f), 3, add(z, Expr.Lit.of(0.1f)), null);
        String frag = fragment(IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3, List.of(half, third,
                Expr.Lit.of(0f)))));
        assertTrue(frag.contains("float ir_s0 = 0.0;"), frag);
        assertTrue(frag.contains("float ir_s1 = 0.0;"), frag);
        assertTrue(frag.contains("vec3 emissive = vec3(ir_s0, ir_s1, 0.0);"), frag);
    }

    @Test
    void aResponseToTheLightKeepsItsOwnLocalsInsideTheLightLoop() {
        Expr tangent = call("worldToTangent", ShaderType.VEC3, ref("L", ShaderType.VEC3));
        Expr response = call("max", ShaderType.FLOAT, new Expr.Swizzle(tangent, "z"),
                new Expr.Swizzle(tangent, "y"));
        String frag = fragment(IrSurfaces.emitting(Expr.Lit.of(0f, 0f, 0f)).withDiffuseResponse(response));
        int lights = frag.indexOf("for (int i = 0; i < MAX_LIGHTS; i++)");
        int local = frag.indexOf("vec3 ir_t0 = worldToTangent(L);");
        int read = frag.indexOf("float diffuseResponse = max(ir_t0.z, ir_t0.y);");
        assertTrue(lights > 0 && local > lights && read > local, frag.substring(lights));
    }

    @Test
    void aFrameworkHelperThatTakesDerivativesIsRefusedInALoopThatCanEndEarly() {
        Expr.Ref s = new Expr.Ref("s", ShaderType.VEC3);
        for (String helper : List.of("worldToTangent", "normalMapToWorld")) {
            Expr next = helper.equals("worldToTangent")
                    ? call(helper, ShaderType.VEC3, s)
                    : call(helper, ShaderType.VEC3, s, Expr.Lit.of(1f));
            Expr.Loop loop = new Expr.Loop("s", Expr.Lit.of(0f, 0f, 1f), 4, next,
                    IrSurfaces.sub(new Expr.Swizzle(s, "x"), Expr.Lit.of(0.5f)));
            assertThrows(IllegalArgumentException.class, () -> fragment(IrSurfaces.emitting(loop)), helper);
        }
        Expr.Ref f = new Expr.Ref("f", ShaderType.FLOAT);
        Expr.Loop raw = new Expr.Loop("f", Expr.Lit.of(0f), 4,
                add(f, new Expr.Raw(TargetProfile.GLSL_330, "dFdx(v_uv.x)", ShaderType.FLOAT)),
                IrSurfaces.sub(f, Expr.Lit.of(1f)));
        assertThrows(IllegalArgumentException.class,
                () -> fragment(IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3, List.of(raw)))),
                "raw text is read by nobody, so it is refused where a derivative would be undefined");
    }

    @Test
    void sharingAndLoopsStayInsideTheEs300Subset() {
        for (SurfaceOutputs surface : List.of(IrSurfaces.mandelbrot(256), IrSurfaces.blurLoop(),
                IrSurfaces.unrolled(9), IrSurfaces.dissolveLike(), IrSurfaces.moltenLike(),
                StandardSurface.metallicRoughness())) {
            String body330 = GlslCodegen.generate(surface, "", TargetProfile.GLSL_330)
                    .fragmentSource().substring(TargetProfile.GLSL_330.header.length());
            String bodyEs = GlslCodegen.generate(surface, "", TargetProfile.GLSL_ES_300)
                    .fragmentSource().substring(TargetProfile.GLSL_ES_300.header.length());
            assertEquals(body330, bodyEs, "a surface is portable or it is not portable");
            // ES 3.00 converts no int to a float: the counter is the only int, and meets none.
            assertFalse(body330.matches("(?s).*ir_i\\d+\\s*[*/+-].*"), "the counter takes part in no arithmetic");
        }
    }

    @Test
    void generationIsDeterministic() {
        assertEquals(fragment(IrSurfaces.mandelbrot(64)), fragment(IrSurfaces.mandelbrot(64)));
        assertEquals(fragment(IrSurfaces.moltenLike()), fragment(IrSurfaces.moltenLike()));
    }

    /** The text of the first loop, from its for to the brace that closes it. */
    private static String firstLoop(String frag) {
        int start = frag.indexOf("    for (int ir_i0");
        return frag.substring(start, frag.indexOf("\n    }\n", start) + 7);
    }

    @Test
    void aLoopThatCanEndEarlyOverADeepSharedTurnIsWrittenInMilliseconds() {
        // Each step of z ↦ z² + c reads the step before six times through its swizzles: as a tree,
        // twenty steps are 6^20 paths. The check of what an early-ending loop holds walked them so,
        // 43 s at twelve steps in the frame that first drew the surface; walked once a node, every
        // depth here takes milliseconds.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int steps = 12; steps <= 20; steps++) {
                Expr.Ref s = new Expr.Ref("s", ShaderType.VEC2);
                Expr z = s;
                for (int i = 0; i < steps; i++) {
                    z = IrSurfaces.square(z, Expr.Lit.of(0.25f, 0.1f));
                }
                Expr.Loop loop = new Expr.Loop("s", Expr.Lit.of(0f, 0f), 8, z,
                        sub(call("dot", ShaderType.FLOAT, s, s), Expr.Lit.of(4f)));
                String frag = fragment(IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3,
                        List.of(loop, Expr.Lit.of(0f)))));
                assertTrue(frag.contains("for (int ir_i0 = 0; ir_i0 < 8; ++ir_i0) {"), "steps " + steps);
            }
        });
    }

    @Test
    void aChainOfSquaresSharedByEveryStepIsTypedAndWrittenInMilliseconds() {
        // z ↦ z·z forty times, each product read twice: every one is a local, declared by its type,
        // and a type asked of each child as a tree is 2^40 questions.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Expr z = ref("v_uv", ShaderType.VEC2);
            for (int i = 0; i < 40; i++) {
                z = mul(z, z);
            }
            Expr.Ref s = new Expr.Ref("s", ShaderType.VEC2);
            Expr.Loop loop = new Expr.Loop("s", Expr.Lit.of(0f, 0f), 4, add(s, z),
                    sub(new Expr.Swizzle(s, "x"), Expr.Lit.of(1f)));
            String frag = fragment(IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3,
                    List.of(loop, Expr.Lit.of(0f)))));
            assertEquals(40, count(frag, "    vec2 ir_t"), "one local a step: " + frag);
        });
    }

    @Test
    void aLoopsStateNamedLikeAnInputIsTheStateInsideTheLoopWhicheverIsWrittenFirst() {
        // A Value "z" doubled by the base colour, and a loop whose state is also called z, doubled
        // three times. The base colour is written first and its z * 2 becomes a local over the
        // uniform; inside the loop z is the state, and that local is not what z * 2 means there.
        Expr.Ref z = new Expr.Ref("z", ShaderType.VEC4);
        Expr twice = mul(z, Expr.Lit.of(2f));
        Expr.Loop loop = new Expr.Loop("z", Expr.Lit.of(1f, 1f, 1f, 1f), 3, twice, null);
        String declared = "\nuniform vec4 z;\n";
        String outsideFirst = GlslCodegen.generate(SurfaceOutputs.of(twice, Expr.Lit.of(0f), Expr.Lit.of(1f),
                new Expr.Swizzle(loop, "xyz")), declared, TargetProfile.GLSL_330).fragmentSource();
        assertTrue(outsideFirst.contains("    vec4 baseColor = ir_t0;\n"), outsideFirst);
        assertTrue(outsideFirst.contains("    vec4 ir_t0 = (z * 2.0);\n"), outsideFirst);
        String body = firstLoop(outsideFirst);
        assertFalse(body.contains("ir_t0"), body);
        assertTrue(body.contains("(ir_s0 * 2.0)"), body);

        // Inside first: the loop's local is in its body, and the base colour reads the uniform.
        String insideFirst = GlslCodegen.generate(SurfaceOutputs.of(loop, Expr.Lit.of(0f), Expr.Lit.of(1f),
                new Expr.Swizzle(twice, "xyz")), declared, TargetProfile.GLSL_330).fragmentSource();
        assertTrue(firstLoop(insideFirst).contains("(ir_s0 * 2.0)"), insideFirst);
        assertTrue(insideFirst.contains("(z * 2.0)"), insideFirst);

        // The same with a varying of the framework's own for the name.
        Expr uv = ref("v_uv", ShaderType.VEC2);
        Expr cells = call("fract", ShaderType.VEC2, mul(uv, Expr.Lit.of(3f)));
        Expr.Loop folded = new Expr.Loop("v_uv", Expr.Lit.of(0.5f, 0.5f), 4, cells, null);
        String varying = fragment(SurfaceOutputs.of(new Expr.Construct(ShaderType.VEC4,
                        List.of(cells, Expr.Lit.of(0f), Expr.Lit.of(1f))), Expr.Lit.of(0f), Expr.Lit.of(1f),
                new Expr.Construct(ShaderType.VEC3, List.of(folded, Expr.Lit.of(0f)))));
        assertTrue(varying.contains("fract((v_uv * 3.0))"), varying);
        String foldedBody = firstLoop(varying);
        assertTrue(foldedBody.contains("fract((ir_s0 * 3.0))"), foldedBody);
        assertFalse(foldedBody.contains("ir_t0"), foldedBody);
    }

    @Test
    void aLoopThatCanEndEarlyCallsOnlyWhatIsDefinedWherePixelsTakeDifferentTurns() {
        Expr.Ref s = new Expr.Ref("s", ShaderType.FLOAT);
        Expr until = sub(s, Expr.Lit.of(1f));
        // The framework's shadow helper samples its map choosing the level itself.
        Expr shadow = call("shadowFactor", ShaderType.FLOAT, ref("v_worldPos", ShaderType.VEC3), s);
        // A name the generator does not know is no promise either.
        Expr unknown = call("wobble", ShaderType.FLOAT, s);
        for (Expr call : List.of(shadow, unknown)) {
            Expr.Loop loop = new Expr.Loop("s", Expr.Lit.of(0f), 4, add(s, call), until);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> fragment(IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3, List.of(loop)))));
            assertTrue(refused.getMessage().contains(((Expr.Call) call).function()), refused.getMessage());
            // Every pixel takes every turn of a loop without an early end: there they are defined.
            Expr.Loop uniform = new Expr.Loop("s", Expr.Lit.of(0f), 4, add(s, call), null);
            assertDoesNotThrow(() -> fragment(IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3,
                    List.of(uniform)))));
        }
        // The built-ins defined anywhere, and the framework's helpers that neither sample nor take a
        // derivative, are accepted.
        Expr ok = add(add(call("sin", ShaderType.FLOAT, s), call("dot", ShaderType.FLOAT, s, s)),
                add(call("mix", ShaderType.FLOAT, s, Expr.Lit.of(1f), Expr.Lit.of(0.5f)),
                        new Expr.Swizzle(call("srgbToLinear", ShaderType.VEC3,
                                new Expr.Construct(ShaderType.VEC3, List.of(s))), "x")));
        Expr texel = new Expr.Swizzle(call("textureLod", ShaderType.VEC4, ref("u_sheet", ShaderType.SAMPLER2D),
                ref("v_uv", ShaderType.VEC2), Expr.Lit.of(0f)), "x");
        Expr.Loop allowed = new Expr.Loop("s", Expr.Lit.of(0f), 4, add(add(s, ok), texel), until);
        assertDoesNotThrow(() -> fragment(IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3,
                List.of(allowed)))));
    }

    @Test
    void aSamplerIsNeverALocal() {
        // GLSL declares a sampler only as a uniform or a parameter: a local of one does not compile.
        String twice = fragment(IrSurfaces.samplerThroughRawText());
        assertFalse(twice.contains("sampler2D ir_"), twice);
        assertTrue(twice.contains("    vec4 baseColor = texture((u_sheet), v_uv);\n"), twice);
        // Read in a loop's turn, raw text reads no name of the loop and would be computed before it.
        Expr sheet = new Expr.Raw(TargetProfile.GLSL_330, "u_sheet", ShaderType.SAMPLER2D);
        Expr uv = ref("v_uv", ShaderType.VEC2);
        Expr.Ref acc = new Expr.Ref("acc", ShaderType.VEC4);
        Expr.Loop blur = new Expr.Loop("acc", Expr.Lit.of(0f, 0f, 0f, 0f), 4, add(acc,
                call("texture", ShaderType.VEC4, sheet, add(uv, new Expr.Swizzle(acc, "xy")))), null);
        String looped = fragment(IrSurfaces.emitting(new Expr.Swizzle(blur, "rgb")));
        assertFalse(looped.contains("sampler2D ir_"), looped);
        assertTrue(firstLoop(looped).contains("texture((u_sheet), (v_uv + ir_s0.xy))"), looped);
    }

    @Test
    void aDiffuseResponseThatLoopsIsHeldToTheCapOnceForEachLightTheGeneratorShades() {
        // 200 turns a light fit one light, and the light loop runs the response for eight: 1,600.
        Expr.Ref f = new Expr.Ref("f", ShaderType.FLOAT);
        Expr.Loop response = new Expr.Loop("f", Expr.Lit.of(0f), 200, add(f, Expr.Lit.of(0.001f)), null);
        SurfaceOutputs surface = IrSurfaces.emitting(Expr.Lit.of(0f, 0f, 0f)).withDiffuseResponse(response);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> fragment(surface));
        assertTrue(refused.getMessage().contains("1600"), refused.getMessage());
        Expr.Loop fits = new Expr.Loop("f", Expr.Lit.of(0f), 128, add(f, Expr.Lit.of(0.001f)), null);
        assertDoesNotThrow(() -> fragment(IrSurfaces.emitting(Expr.Lit.of(0f, 0f, 0f)).withDiffuseResponse(fits)));
    }
}

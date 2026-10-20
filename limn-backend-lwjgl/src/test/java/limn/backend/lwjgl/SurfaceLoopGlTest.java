package limn.backend.lwjgl;

import limn.graphics.ScenePixels;
import limn.math.Mat4;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.Camera;
import limn.render3d.ColorSpace;
import limn.render3d.GpuMesh;
import limn.render3d.GpuTexture;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.MeshUsage;
import limn.render3d.Primitives;
import limn.render3d.Sampler;
import limn.render3d.TextureData;
import limn.render3d.shader.Expr;
import limn.render3d.shader.ShaderType;
import limn.render3d.shader.StandardSurface;
import limn.render3d.shader.SurfaceOutputs;
import limn.render3d.shader.TargetProfile;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generator's sharing and loops on a real device (ADR 077): every surface it writes compiles
 * and links on the GPU and on Apple's software renderer, a loop's value reaches the pixel, and
 * sharing a node draws exactly the pixels writing it in place drew.
 */
class SurfaceLoopGlTest {

    private static final int SIZE = 32;

    private HeadlessUi ui;

    @BeforeEach
    void open() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
    }

    @AfterEach
    void close() {
        if (ui != null) {
            ui.close();
        }
    }

    /** The surfaces the generator is held to, by name. */
    private static Map<String, SurfaceOutputs> surfaces() {
        Map<String, SurfaceOutputs> all = new LinkedHashMap<>();
        all.put("standard", StandardSurface.metallicRoughness());
        all.put("dissolve", IrSurfaces.dissolveLike());
        all.put("molten", IrSurfaces.moltenLike());
        all.put("unrolled", IrSurfaces.unrolled(9));
        all.put("mandelbrot", IrSurfaces.mandelbrot(256));
        all.put("blur loop", IrSurfaces.blurLoop());
        all.put("sampler through raw text", IrSurfaces.samplerThroughRawText());
        all.put("volume march", IrSurfaces.volumeSurface());
        all.put("swap", IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3,
                List.of(IrSurfaces.swap(), IrSurfaces.swap().value(1), Expr.Lit.of(0f)))));
        return all;
    }

    private static final String DECLARATIONS = "\nuniform sampler2D u_sheet;\nuniform sampler2D u_mask;\n"
            + "uniform vec4 u_knobs;\n";

    private static List<String> compileAll() {
        List<String> refused = new ArrayList<>();
        surfaces().forEach((name, surface) -> {
            GlslCodegen.Generated g = GlslCodegen.generate(surface, DECLARATIONS, TargetProfile.GLSL_330);
            try {
                ShaderProgram.fromSources(g.vertexSource(), g.fragmentSource()).close();
            } catch (IllegalStateException e) {
                refused.add(name + ": " + e.getMessage());
            }
        });
        return refused;
    }

    @Test
    void everySurfaceTheGeneratorWritesCompilesOnTheGpu() {
        assertEquals(List.of(), compileAll(), HeadlessGl.describe());
    }

    @Test
    void everySurfaceTheGeneratorWritesCompilesOnTheSoftwareRenderer() {
        List<List<String>> refused = new ArrayList<>();
        HeadlessGl.onSoftwareRenderer(() -> refused.add(compileAll()));
        assertEquals(List.of(List.of()), refused);
    }

    // ------------------------------------------------------------------- pixels

    /** Draws {@code material} over the whole target from above and reads it back. */
    private static ScenePixels draw(Function<Gl3DContext, Material> material, boolean lit) {
        Gl3DContext context = new Gl3DContext();
        try {
            GlRenderTarget target = (GlRenderTarget) context.createTarget(SIZE, SIZE, 1);
            GpuMesh plane = context.upload(Primitives.plane(4f, 4f), MeshUsage.STATIC);
            Material chosen = material.apply(context);
            Camera camera = new Camera().eye(new Vec3(0, 3f, 0)).target(Vec3.ZERO).up(new Vec3(0, 0, -1));
            context.render(target, camera, pass -> {
                pass.clear(0f, 0f, 0f, 1f);
                pass.ambient(lit ? new Vec3(0.1f, 0.1f, 0.12f) : Vec3.ZERO);
                if (lit) {
                    pass.addLight(new Light.Directional(new Vec3(0.3f, 1f, 0.2f), new Vec3(1f, 0.95f, 0.9f), 2f));
                    pass.addLight(new Light.Point(new Vec3(0.5f, 1f, 0.5f), new Vec3(0.4f, 0.6f, 1f), 3f, 4f));
                }
                pass.draw(plane, chosen, Mat4.identity());
            }, 1f);
            ScenePixels pixels = target.readSceneReferred();
            target.dispose();
            return pixels;
        } finally {
            context.dispose();
        }
    }

    private static float centre(ScenePixels pixels, int channel) {
        return pixels.channel(SIZE / 2, SIZE / 2, channel);
    }

    @Test
    void aLoopsValueReachesThePixelWhetherItRunsOutOrEndsEarly() {
        Expr.Loop fifty = Expr.Loop.of(Expr.Lit.of(0f), 50, s -> IrSurfaces.add(s, Expr.Lit.of(0.01f)));
        // Asked before each turn: 0, 0.1, 0.2, 0.3 are below 0.35 and step on; 0.4 is not. Up to
        // 974 turns, what the fifty leave of the surface's 1,024.
        Expr.Loop early = Expr.Loop.of(Expr.Lit.of(0f), 974, s -> IrSurfaces.add(s, Expr.Lit.of(0.1f)),
                s -> IrSurfaces.sub(s, Expr.Lit.of(0.35f)));
        SurfaceOutputs both = IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3,
                List.of(fifty, early, Expr.Lit.of(0f))));
        ScenePixels pixels = draw(context -> Material.Surface.of("test.loops", both), false);
        assertEquals(0.5f, centre(pixels, 0), 2e-3f, "fifty turns of a hundredth");
        assertEquals(0.4f, centre(pixels, 1), 2e-3f, "four turns, then the exit");
    }

    /**
     * Holds the march at pixel (x, y) to the same march in Java, along the ray the pixel's uv,
     * read back from {@code uv}, says it took; returns that ray's distance from the sphere's axis.
     */
    private static float assertMarch(ScenePixels pixels, ScenePixels uv, int x, int y, String where) {
        float rx = (uv.channel(x, y, 0) - 0.5f) * IrSurfaces.MARCH_SPREAD;
        float ry = (uv.channel(x, y, 1) - 0.5f) * IrSurfaces.MARCH_SPREAD;
        float[] expected = IrSurfaces.marchOnCpu(rx, ry);
        assertEquals(expected[0], pixels.channel(x, y, 0), 2e-3f, where + ": alpha gathered");
        assertEquals(expected[1] * 0.25f, pixels.channel(x, y, 1), 2e-3f, where + ": distance travelled");
        assertEquals(expected[2], pixels.channel(x, y, 2), 2e-3f, where + ": colour gathered");
        return (float) Math.hypot(rx, ry);
    }

    /** The march, then the uv, drawn on the context current. */
    private static List<ScenePixels> drawMarch() {
        return List.of(draw(context -> Material.Surface.of("test.volume", IrSurfaces.volumeSurface()), false),
                draw(context -> Material.Surface.of("test.uv", IrSurfaces.uvAsLight()), false));
    }

    private static void assertMarches(List<ScenePixels> drawn) {
        // The centre ray crosses the sphere's middle and ends early, all but opaque.
        assertTrue(assertMarch(drawn.get(0), drawn.get(1), SIZE / 2, SIZE / 2, "centre") < 0.2f);
        assertTrue(drawn.get(0).channel(SIZE / 2, SIZE / 2, 0) > 0.99f, "the centre is all but opaque");
        assertTrue(drawn.get(0).channel(SIZE / 2, SIZE / 2, 1) < 0.25f * 1.5f, "and stopped early");
        // A corner ray misses it, gathers nothing and takes every turn.
        assertTrue(assertMarch(drawn.get(0), drawn.get(1), 0, 0, "corner") > 1.1f, "the corner misses");
        assertEquals(0.25f * IrSurfaces.MARCH_TURNS * IrSurfaces.MARCH_STEP, drawn.get(0).channel(0, 0, 1), 2e-3f);
    }

    @Test
    void aMarchThroughAVolumeGathersWhatItCrossesAndStopsOnceOpaque() {
        assertMarches(drawMarch());
    }

    @Test
    void aMarchThroughAVolumeDrawsTheSameOnTheSoftwareRenderer() {
        List<List<ScenePixels>> drawn = new ArrayList<>();
        HeadlessGl.onSoftwareRenderer(() -> drawn.add(drawMarch()));
        assertMarches(drawn.get(0));
    }

    @Test
    void theValuesOfALoopStepTogetherFromTheStateBeforeTheTurn() {
        SurfaceOutputs swapped = IrSurfaces.emitting(new Expr.Construct(ShaderType.VEC3,
                List.of(IrSurfaces.swap(), IrSurfaces.swap().value(1), Expr.Lit.of(0f))));
        for (boolean software : new boolean[]{false, true}) {
            List<ScenePixels> drawn = new ArrayList<>();
            Runnable drawIt = () -> drawn.add(draw(context -> Material.Surface.of("test.swap", swapped), false));
            if (software) {
                HeadlessGl.onSoftwareRenderer(drawIt);
            } else {
                drawIt.run();
            }
            assertEquals(0.2f, centre(drawn.get(0), 0), 2e-3f, "three swaps of (0.1, 0.2)");
            assertEquals(0.1f, centre(drawn.get(0), 1), 2e-3f, "three swaps of (0.1, 0.2)");
        }
    }

    @Test
    void anEscapeTimeFractalIsWhiteInsideTheSetAndDarkFarOutsideIt() {
        ScenePixels pixels = draw(context -> Material.Surface.of("test.mandelbrot", IrSurfaces.mandelbrot(256)), false);
        // The plane's uv runs across the target; at its middle c = -0.3, inside the set: every turn
        // taken, the smooth count at its ceiling. Near the edge of u, c = 1.0, which leaves at once.
        float inside = centre(pixels, 0);
        float outside = pixels.channel(SIZE - 2, SIZE / 2, 0);
        float outside2 = pixels.channel(1, SIZE / 2, 0);
        assertEquals(1f, inside, 2e-3f, "inside the set");
        assertTrue(Math.min(outside, outside2) < 0.1f, "outside: " + outside + ", " + outside2);
    }

    @Test
    void sharingANodeDrawsExactlyThePixelsWritingItInPlaceDrew() {
        Map<String, Function<Gl3DContext, Material>> materials = new LinkedHashMap<>();
        materials.put("standard, textured", context -> Material.Pbr.of(0.8f, 0.6f, 0.4f).roughness(0.4f)
                .textured(context.upload(checker(), Sampler.smooth())));
        materials.put("dissolve", context -> new Material.Surface("test.dissolve", IrSurfaces.dissolveLike(),
                List.of(new Material.Surface.Texture("u_sheet", context.upload(checker(), Sampler.smooth())),
                        new Material.Surface.Texture("u_mask", context.upload(gradient(), Sampler.smooth()))),
                List.of(new Material.Surface.Value("u_knobs", new Vec4(0.3f, 0.2f, 0, 0))), null));
        materials.put("molten", context -> Material.Surface.of("test.molten", IrSurfaces.moltenLike()));
        // A loop hiding the state of the loop around it, a node over that name written in the outer
        // turn first: inside the inner turn the name is the inner state, shared or not.
        materials.put("a loop hiding an enclosing state", context -> Material.Surface.of("test.shadowing",
                IrSurfaces.emitting(new Expr.Swizzle(IrSurfaces.shadowingLoops(), "xyz"))));
        boolean was = GlslCodegen.shareSubexpressions;
        try {
            // The comparison means something only where the two forms differ: the standard surface
            // shares its texel, the molten one its noise's cells and fractions.
            for (SurfaceOutputs surface : List.of(StandardSurface.metallicRoughness(), IrSurfaces.moltenLike())) {
                GlslCodegen.shareSubexpressions = true;
                String shared = GlslCodegen.generate(surface, TargetProfile.GLSL_330).fragmentSource();
                GlslCodegen.shareSubexpressions = false;
                String inPlace = GlslCodegen.generate(surface, TargetProfile.GLSL_330).fragmentSource();
                assertTrue(shared.contains("ir_t0") && !inPlace.contains("ir_t0"), "the two forms differ");
            }
            materials.forEach((name, material) -> {
                GlslCodegen.shareSubexpressions = true;
                float[] shared = draw(material, true).channels();
                GlslCodegen.shareSubexpressions = false;
                float[] inPlace = draw(material, true).channels();
                float worst = 0;
                for (int i = 0; i < shared.length; i++) {
                    worst = Math.max(worst, Math.abs(shared[i] - inPlace[i]));
                }
                assertEquals(0f, worst, 0f, name + ": the largest difference in a channel");
                float lit = 0;
                for (float channel : shared) {
                    lit += channel;
                }
                assertTrue(lit > 1f, name + " drew something");
            });
        } finally {
            GlslCodegen.shareSubexpressions = was;
        }
    }

    private static TextureData checker() {
        byte[] rgba = new byte[8 * 8 * 4];
        for (int i = 0; i < 64; i++) {
            boolean on = ((i % 8) + (i / 8)) % 2 == 0;
            rgba[i * 4] = (byte) (on ? 230 : 40);
            rgba[i * 4 + 1] = (byte) (on ? 180 : 90);
            rgba[i * 4 + 2] = (byte) (on ? 60 : 200);
            rgba[i * 4 + 3] = (byte) 255;
        }
        return TextureData.rgba(8, 8, rgba, ColorSpace.SRGB);
    }

    private static TextureData gradient() {
        byte[] rgba = new byte[16 * 4];
        for (int i = 0; i < 16; i++) {
            byte level = (byte) (i * 16);
            rgba[i * 4] = level;
            rgba[i * 4 + 1] = level;
            rgba[i * 4 + 2] = level;
            rgba[i * 4 + 3] = (byte) 255;
        }
        return new TextureData(16, 1, rgba, ColorSpace.LINEAR);
    }
}

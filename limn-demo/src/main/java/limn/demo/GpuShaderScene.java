package limn.demo;

import limn.animation.Motion;
import limn.components.Label;
import limn.components.SegmentedControl;
import limn.components.Slider;
import limn.components.Viewport3D;
import limn.i18n.I18nString;
import limn.math.Transform3D;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.MeshData;
import limn.render3d.OrbitController;
import limn.render3d.Primitives;
import limn.render3d.Graphics3D;
import limn.render3d.scene.LightNode;
import limn.render3d.scene.MeshNode;
import limn.render3d.scene.Scene3D;
import limn.render3d.shader.Expr;
import limn.render3d.shader.ShaderType;
import limn.render3d.shader.SurfaceOutputs;
import limn.scene.SystemPreferences;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;

import java.util.List;

/**
 * Computation on the GPU, shown: molten rock whose every pixel is worked out by the fragment
 * shader, as fractal noise moving with time, on a sphere over a lake of the same.
 *
 * <p>It is an application's {@link Material.Surface}, written in the toolkit's portable shader
 * IR, and the engine still lights it: the crust takes the scene's light and the sky, and the
 * glow is the surface's emissive. Nothing here is a texture; the noise is four octaves of
 * three-dimensional value noise, about thirty hashes a pixel, evaluated every frame.
 *
 * <p><b>One program, whatever moves.</b> Time and the palette reach the shader as named
 * {@code vec4} values. Each frame hands the mesh a copy of one surface made by
 * {@link Material.Surface#with}, which keeps the surface's key, and the key is what the
 * program is compiled and cached under: the frame changes four uniforms and links nothing.
 *
 * <p>It honours the system's wish for less motion by holding still: the view stops repainting
 * and the shader's clock stops with it. A hidden view does not render, so the clock also stands
 * still while another scene, or another page, is showing.
 */
final class GpuShaderScene {

    /** What the scene's controls say, ready for translation. */
    static final class Text {
        static final I18nString SHADER = new I18nString("showcase.shader.scene", "Shader");
        static final I18nString SPEED = new I18nString("showcase.shader.speed", "Speed");
        static final I18nString PALETTE = new I18nString("showcase.shader.palette", "Palette");
        static final I18nString LAVA = new I18nString("showcase.shader.lava", "Lava");
        static final I18nString PLASMA = new I18nString("showcase.shader.plasma", "Plasma");
        static final I18nString ICE = new I18nString("showcase.shader.ice", "Ice");
        static final I18nString PAUSED =
                new I18nString("showcase.shader.paused", "Paused: the system asks for less motion");

        private Text() {
        }
    }

    /** The program's cache key: one surface, one program, however many frames. */
    static final String KEY = "demo.molten";

    /**
     * The palettes, by the {@code u_heat}, {@code u_tint} and {@code u_crust} values each hands
     * the shader: per-channel exponents of the heat and the glow's strength, the glow's tint, and
     * the cooled crust's colour (linear). The exponents are what make a palette: red rising
     * first and blue last is a body glowing as it heats, and the reverse is ice.
     */
    private static final Vec4[][] PALETTES = {
            {new Vec4(1.0f, 2.3f, 5.5f, 7f), new Vec4(1f, 0.92f, 0.85f, 0), new Vec4(0.05f, 0.04f, 0.035f, 0)},
            {new Vec4(1.4f, 4.5f, 1.1f, 6f), new Vec4(1f, 0.8f, 1f, 0), new Vec4(0.04f, 0.035f, 0.06f, 0)},
            {new Vec4(5.0f, 1.9f, 1.0f, 5f), new Vec4(0.85f, 0.95f, 1f, 0), new Vec4(0.06f, 0.07f, 0.09f, 0)},
    };

    private GpuShaderScene() {
    }

    // ------------------------------------------------------------------ the clock

    /**
     * The shader's own clock, fed by the view's: it moves by what the view's time moved, times
     * the speed, and not at all while motion is reduced. The view's time stands still while the
     * view is hidden, so a scene switched back to resumes where it was rather than jumping.
     */
    static final class Clock {
        private double last = Double.NaN;
        private double time;
        private float speed = 1f;

        /** @param viewTime the time the view's renderer was handed; @return the shader's time */
        double advance(double viewTime) {
            if (!Double.isNaN(last) && !Motion.reduced()) {
                time += Math.max(0, viewTime - last) * speed;
            }
            last = viewTime;
            return time;
        }

        void speed(float value) {
            speed = Math.max(0, value);
        }

        double time() {
            return time;
        }
    }

    // --------------------------------------------------------------- the surface

    /** The surface's expressions, built once: a frame changes its values, never these. */
    private static final SurfaceOutputs OUTPUTS = outputs();

    /**
     * The surface at {@code time}, in palette {@code palette}: always the same key and the same
     * expressions, so the same program; only the four values differ.
     */
    static Material.Surface surface(double time, int palette) {
        return surface(time, palette, 0f);
    }

    /**
     * The same, {@code hotter} by that much: added to the noise before the thresholds, so a mesh
     * drawn with more of it shows more of its surface molten. One program still: it is a value.
     */
    static Material.Surface surface(double time, int palette, float hotter) {
        Vec4[] p = PALETTES[palette];
        return BASE.with(List.of(), List.of(
                new Material.Surface.Value("u_time", new Vec4((float) time, 0, hotter, 0)),
                new Material.Surface.Value("u_heat", p[0]),
                new Material.Surface.Value("u_tint", p[1]),
                new Material.Surface.Value("u_crust", p[2])));
    }

    private static final Material.Surface BASE = new Material.Surface(KEY, OUTPUTS, List.of(),
            List.of(new Material.Surface.Value("u_time", Vec4.ZERO),
                    new Material.Surface.Value("u_heat", PALETTES[0][0]),
                    new Material.Surface.Value("u_tint", PALETTES[0][1]),
                    new Material.Surface.Value("u_crust", PALETTES[0][2])),
            null);

    /**
     * Molten rock, in the IR.
     *
     * <p>The heat is fractal noise over the surface's world position, so a sphere has no seam and
     * the lake under it is the same rock. The domain does not slide with time, which would carry
     * the coordinates far enough for single precision to coarsen the hash; it drifts on a bounded
     * offset and is warped by a wave, so it churns in place, as a crust over convection does.
     *
     * <p>The glow is the heat raised to a power per channel, times a strength: with red's power
     * lowest it reddens first and whitens last, the order a body glows in as it heats. That reads
     * the heat once, which matters here: the IR is a tree, written out whole wherever a node is
     * used, so an expression that used the noise twice would compute it twice.
     */
    private static SurfaceOutputs outputs() {
        Expr time = new Expr.Swizzle(ref("u_time", ShaderType.VEC4), "x");
        Expr heatKnobs = ref("u_heat", ShaderType.VEC4);
        Expr p = mul(ref("v_worldPos", ShaderType.VEC3), Expr.Lit.of(1.35f));
        // A slow drift, bounded: three incommensurate periods, so it never visibly repeats.
        Expr drift = new Expr.Construct(ShaderType.VEC3, List.of(
                mul(call("sin", ShaderType.FLOAT, mul(time, Expr.Lit.of(0.13f))), Expr.Lit.of(2.5f)),
                mul(call("cos", ShaderType.FLOAT, mul(time, Expr.Lit.of(0.11f))), Expr.Lit.of(2.5f)),
                mul(call("sin", ShaderType.FLOAT, mul(time, Expr.Lit.of(0.07f))), Expr.Lit.of(2.5f))));
        // The churn: each axis pushed by a wave along another one, moving with time.
        Expr wave = call("sin", ShaderType.VEC3, add(mul(new Expr.Swizzle(p, "yzx"), Expr.Lit.of(1.7f)),
                mul(time, Expr.Lit.of(0.45f, 0.6f, 0.5f))));
        Expr q = add(add(p, drift), mul(wave, Expr.Lit.of(0.55f)));

        Expr fbm = Expr.Lit.of(0f);
        float amplitude = 0.5f;
        float frequency = 1f;
        float[][] offsets = {{0, 0, 0}, {5.2f, 1.3f, 2.8f}, {1.7f, 9.2f, 4.1f}, {8.3f, 2.8f, 7.6f}};
        for (float[] offset : offsets) {
            Expr at = add(mul(q, Expr.Lit.of(frequency)), Expr.Lit.of(offset));
            fbm = add(fbm, mul(noise(at), Expr.Lit.of(amplitude)));
            amplitude *= 0.5f;
            frequency *= 2.03f;
        }
        // Molten where the noise runs high, a third of the surface or so, with the hottest cores
        // a few percent of it: the thresholds sit at the noise's 65th and 98th percentiles.
        Expr hotter = new Expr.Swizzle(ref("u_time", ShaderType.VEC4), "z");
        Expr heat = call("smoothstep", ShaderType.FLOAT, Expr.Lit.of(0.51f), Expr.Lit.of(0.72f), add(fbm, hotter));
        Expr glow = mul(mul(call("pow", ShaderType.VEC3,
                        new Expr.Construct(ShaderType.VEC3, List.of(heat)),
                        new Expr.Swizzle(heatKnobs, "xyz")),
                new Expr.Swizzle(heatKnobs, "w")), new Expr.Swizzle(ref("u_tint", ShaderType.VEC4), "xyz"));
        Expr crust = new Expr.Construct(ShaderType.VEC4, List.of(
                new Expr.Swizzle(ref("u_crust", ShaderType.VEC4), "xyz"), Expr.Lit.of(1f)));
        return SurfaceOutputs.of(crust, Expr.Lit.of(0f), Expr.Lit.of(0.82f), glow);
    }

    /**
     * Three-dimensional value noise at {@code x}, in [0, 1]: a hash at the eight corners of the
     * cell, blended by a smoothed position inside it.
     */
    private static Expr noise(Expr x) {
        Expr cell = call("floor", ShaderType.VEC3, x);
        Expr u = call("smoothstep", ShaderType.VEC3, Expr.Lit.of(0, 0, 0), Expr.Lit.of(1, 1, 1),
                call("fract", ShaderType.VEC3, x));
        Expr ux = new Expr.Swizzle(u, "x");
        Expr uy = new Expr.Swizzle(u, "y");
        Expr uz = new Expr.Swizzle(u, "z");
        Expr[] edge = new Expr[4];
        for (int yz = 0; yz < 4; yz++) {
            float y = yz & 1;
            float z = yz >> 1;
            edge[yz] = mix(hash(add(cell, Expr.Lit.of(0, y, z))), hash(add(cell, Expr.Lit.of(1, y, z))), ux);
        }
        return mix(mix(edge[0], edge[1], uy), mix(edge[2], edge[3], uy), uz);
    }

    /** A pseudo-random number in [0, 1) for an integer lattice point. */
    private static Expr hash(Expr point) {
        Expr dot = call("dot", ShaderType.FLOAT, point, Expr.Lit.of(127.1f, 311.7f, 74.7f));
        return call("fract", ShaderType.FLOAT, mul(call("sin", ShaderType.FLOAT, dot), Expr.Lit.of(43758.547f)));
    }

    private static Expr ref(String name, ShaderType type) {
        return new Expr.Ref(name, type);
    }

    private static Expr add(Expr a, Expr b) {
        return new Expr.Binary(Expr.Op.ADD, a, b);
    }

    private static Expr mul(Expr a, Expr b) {
        return new Expr.Binary(Expr.Op.MUL, a, b);
    }

    private static Expr mix(Expr a, Expr b, Expr t) {
        return call("mix", a.type(), a, b, t);
    }

    private static Expr call(String function, ShaderType type, Expr... args) {
        return new Expr.Call(function, type, List.of(args));
    }

    // ------------------------------------------------------------------- the view

    /**
     * The scene for the showcase's page: the view, and under it the speed and the palette.
     *
     * @param views receives the view, for the page's render scale
     */
    static Widget<?> content(java.util.function.Consumer<Viewport3D> views) {
        Clock clock = new Clock();
        int[] palette = {0};
        MeshData sphereData = Primitives.sphere(1.25f, 64, 96);
        MeshData lakeData = Primitives.plane(80, 80);
        Scene3D[] scene = {null};
        MeshNode[] molten = new MeshNode[2];

        Viewport3D viewport = new Viewport3D().setPreferredSize(560, 320);
        viewport.camera().eye(new Vec3(0f, 2.2f, 5.4f)).target(new Vec3(0, -0.35f, 0));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                Scene3D built = new Scene3D()
                        .background(new Vec4(0.02f, 0.02f, 0.025f, 1f))
                        .ambient(new Vec3(0.03f, 0.03f, 0.035f))
                        .exposure(1.0f);
                molten[0] = built.root().add(new MeshNode(Graphics3D.upload(sphereData), surface(0, 0))
                        .transform(Transform3D.at(new Vec3(0, 0.35f, 0))));
                molten[1] = built.root().add(new MeshNode(Graphics3D.upload(lakeData), surface(0, 0))
                        .transform(Transform3D.at(new Vec3(0, -1.25f, 0))));
                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.3f, 1f, 0.4f), new Vec3(0.75f, 0.8f, 1f), 1.6f)));
                scene[0] = built;
            }
            double now = clock.advance(t);
            // The sphere more molten than the lake: the same program, one value apart.
            molten[0].material(surface(now, palette[0], 0.07f));
            molten[1].material(surface(now, palette[0], 0f));
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
        });
        viewport.observeDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null; // the renderer above rebuilds lazily on re-attach
            }
        });
        viewport.setAccessibleName(Text.SHADER);
        views.accept(viewport);

        Slider speed = new Slider(0f, 2f).setStep(0.1f).setValue(1f);
        speed.setAccessibleName(Text.SPEED);
        speed.onChange(clock::speed);

        SegmentedControl palettes = new SegmentedControl(Text.LAVA, Text.PLASMA, Text.ICE);
        palettes.setAccessibleName(Text.PALETTE);
        palettes.onSelect(index -> {
            palette[0] = index;
            viewport.invalidate(); // a still view, under less motion, shows the new palette too
        });

        Label paused = Page.hint(Text.PAUSED);
        Runnable followMotion = () -> {
            boolean still = Motion.reduced();
            viewport.setAnimated(!still);
            paused.setVisible(still);
        };
        followMotion.run();
        SystemPreferences.observeChanges(followMotion); // for the life of the page, as Page's are

        Row controls = Page.row(12, Labelled.caption(Text.SPEED, speed).setMuted(true),
                new limn.scene.layout.SizedBox(180, limn.scene.layout.SizedBox.UNSET, speed),
                Labelled.caption(Text.PALETTE, palettes).setMuted(true), palettes);
        controls.add(Expanded.spacer(1));
        controls.add(paused);

        Column body = new Column();
        body.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.STRETCH);
        body.add(Page.aspect(16f / 9f, viewport));
        body.add(controls);
        return body;
    }
}

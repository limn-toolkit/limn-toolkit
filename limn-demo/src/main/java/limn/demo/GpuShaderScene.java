package limn.demo;

import limn.animation.Motion;
import limn.components.Label;
import limn.components.SegmentedControl;
import limn.components.Slider;
import limn.components.Viewport3D;
import limn.i18n.I18nString;
import limn.math.Quat;
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
 * Computation on the GPU, shown, in three patterns: molten rock whose every pixel is worked out by
 * the fragment shader, as fractal noise moving with time, on a sphere over a lake of the same;
 * the Mandelbrot set on a panel, up to 256 turns of z ↦ z² + c a pixel through a loop in the
 * shader IR, smooth-coloured and zooming slowly into the valley between its two largest parts;
 * and a glowing cloud on the same panel, a ray marched through drifting noise a pixel, gathering
 * colour until it is opaque.
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
        static final I18nString PATTERN = new I18nString("showcase.shader.pattern", "Pattern");
        static final I18nString MOLTEN = new I18nString("showcase.shader.molten", "Molten rock");
        static final I18nString FRACTAL = new I18nString("showcase.shader.fractal", "Fractal");
        static final I18nString CLOUD = new I18nString("showcase.shader.cloud", "Cloud");
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
     * lowest it reddens first and whitens last, the order a body glows in as it heats. The noise
     * builds each cell's corner hashes from one {@code floor} and blends them by one smoothed
     * fraction: the generator writes a node used more than once once, so neither is computed
     * twice.
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

    // --------------------------------------------------------------- the fractal

    /** The fractal's program key: one program, whatever the zoom, the colour or the frame. */
    static final String FRACTAL_KEY = "demo.fractal";
    /** The most turns a pixel takes: a quarter of what a loop may, and enough at this zoom. */
    static final int FRACTAL_TURNS = 256;
    /** Where the zoom goes: the valley of seahorses, between the main cardioid and its largest bulb. */
    private static final double CENTRE_X = -0.743643887;
    private static final double CENTRE_Y = 0.131825904;
    /** How wide the view is at its widest, the whole set, and at its deepest, a thousandth of that. */
    static final double WIDEST = 3.0;
    static final double DEEPEST = 0.003;
    /** A zoom in and out again takes this long, at speed 1. */
    private static final double ZOOM_SECONDS = 60;
    /** The panel's width over its height, which the mapping from its uv to the plane keeps. */
    private static final float PANEL_ASPECT = 16f / 9f;

    /**
     * How wide the view of the set is at {@code time}: from {@link #WIDEST} to {@link #DEEPEST}
     * and back, evenly in the logarithm, so each doubling of the zoom takes as long as the last.
     * Worked out here in double precision and handed to the shader as a value.
     */
    static double scaleAt(double time) {
        double phase = 0.5 - 0.5 * Math.cos(2 * Math.PI * time / ZOOM_SECONDS);
        return Math.exp(Math.log(WIDEST) + (Math.log(DEEPEST) - Math.log(WIDEST)) * phase);
    }

    private static final Material.Surface FRACTAL = new Material.Surface(FRACTAL_KEY, fractalOutputs(), List.of(),
            List.of(new Material.Surface.Value("u_time", Vec4.ZERO),
                    new Material.Surface.Value("u_view", new Vec4((float) CENTRE_X, (float) CENTRE_Y, (float) WIDEST, 0)),
                    new Material.Surface.Value("u_heat", PALETTES[0][0]),
                    new Material.Surface.Value("u_tint", PALETTES[0][1]),
                    new Material.Surface.Value("u_crust", PALETTES[0][2])),
            null);

    /** The fractal at {@code time} in palette {@code palette}: the same key and program, new values. */
    static Material.Surface fractal(double time, int palette) {
        Vec4[] p = PALETTES[palette];
        return FRACTAL.with(List.of(), List.of(
                new Material.Surface.Value("u_time", new Vec4((float) time, 0, 0, 0)),
                new Material.Surface.Value("u_view", new Vec4((float) CENTRE_X, (float) CENTRE_Y,
                        (float) scaleAt(time), 0)),
                new Material.Surface.Value("u_heat", p[0]),
                new Material.Surface.Value("u_tint", p[1]),
                new Material.Surface.Value("u_crust", p[2])));
    }

    /**
     * The Mandelbrot set, in the IR: c is the panel's point on the complex plane, and a loop
     * carries (z, turns) through z ↦ z² + c until |z|² passes 256 or the turns run out.
     *
     * <p>The loop can end early, a different turn in each pixel, so it holds no texture sample and
     * no derivative, which are undefined there; it needs neither. c reads only the pixel, so it is
     * computed once before the loop. The colour is the smooth count, the turns plus the fraction
     * of a turn the escape had left, which removes the bands a whole count draws; it is dark where
     * a pixel leaves at once and runs through a cosine that drifts with time, so the colours move outward through the bands even where the
     * zoom holds still. Inside the set, where every turn was taken, the panel is its crust.
     */
    private static SurfaceOutputs fractalOutputs() {
        Expr view = ref("u_view", ShaderType.VEC4);
        Expr heatKnobs = ref("u_heat", ShaderType.VEC4);
        Expr offset = mul(new Expr.Binary(Expr.Op.SUB, ref("v_uv", ShaderType.VEC2), Expr.Lit.of(0.5f, 0.5f)),
                Expr.Lit.of(PANEL_ASPECT, -1f));
        Expr c = add(new Expr.Swizzle(view, "xy"), mul(offset, new Expr.Swizzle(view, "z")));

        // The state is (z.x, z.y, turns, 0); the loop hands each turn the state as it stands.
        Expr.Loop escape = Expr.Loop.of(Expr.Lit.of(0f, 0f, 0f, 0f), FRACTAL_TURNS, s -> {
            Expr zx = new Expr.Swizzle(s, "x");
            Expr zy = new Expr.Swizzle(s, "y");
            Expr squared = new Expr.Construct(ShaderType.VEC2, List.of(
                    new Expr.Binary(Expr.Op.SUB, mul(zx, zx), mul(zy, zy)),
                    mul(mul(Expr.Lit.of(2f), zx), zy)));
            return new Expr.Construct(ShaderType.VEC4, List.of(add(squared, c),
                    add(new Expr.Swizzle(s, "z"), Expr.Lit.of(1f)), Expr.Lit.of(0f)));
        }, s -> new Expr.Binary(Expr.Op.SUB,
                call("dot", ShaderType.FLOAT, new Expr.Swizzle(s, "xy"), new Expr.Swizzle(s, "xy")),
                Expr.Lit.of(256f)));

        Expr turns = new Expr.Swizzle(escape, "z");
        Expr radius = call("max", ShaderType.FLOAT, call("length", ShaderType.FLOAT, new Expr.Swizzle(escape, "xy")),
                Expr.Lit.of(1.0001f));
        Expr smooth = new Expr.Binary(Expr.Op.SUB, add(turns, Expr.Lit.of(1f)),
                call("log2", ShaderType.FLOAT, call("log2", ShaderType.FLOAT, radius)));
        Expr outside = new Expr.Binary(Expr.Op.SUB, Expr.Lit.of(1f),
                call("step", ShaderType.FLOAT, Expr.Lit.of(FRACTAL_TURNS - 0.5f), turns));
        Expr time = new Expr.Swizzle(ref("u_time", ShaderType.VEC4), "x");
        Expr wave = call("cos", ShaderType.FLOAT, add(mul(smooth, Expr.Lit.of(0.21f)), mul(time, Expr.Lit.of(-0.5f))));
        // Dark far from the set, where a pixel leaves in a turn or two, and lit toward its edge.
        Expr nearness = call("smoothstep", ShaderType.FLOAT, Expr.Lit.of(1f), Expr.Lit.of(26f), smooth);
        Expr heat = mul(mul(new Expr.Binary(Expr.Op.SUB, Expr.Lit.of(0.55f), mul(wave, Expr.Lit.of(0.45f))),
                nearness), outside);
        Expr glow = mul(mul(call("pow", ShaderType.VEC3,
                        new Expr.Construct(ShaderType.VEC3, List.of(heat)), new Expr.Swizzle(heatKnobs, "xyz")),
                mul(new Expr.Swizzle(heatKnobs, "w"), Expr.Lit.of(0.45f))),
                new Expr.Swizzle(ref("u_tint", ShaderType.VEC4), "xyz"));
        Expr crust = new Expr.Construct(ShaderType.VEC4, List.of(
                new Expr.Swizzle(ref("u_crust", ShaderType.VEC4), "xyz"), Expr.Lit.of(1f)));
        return SurfaceOutputs.of(crust, Expr.Lit.of(0f), Expr.Lit.of(0.6f), glow);
    }

    // ------------------------------------------------------------------ the cloud

    /** The cloud's program key: one program, whatever the drift or the palette. */
    static final String CLOUD_KEY = "demo.cloud";
    /** The most steps a ray takes through the cloud, front to back. */
    static final int CLOUD_TURNS = 48;
    /** The cloud's ball: its radius, and how far a step goes to cross it in the turns there are. */
    private static final float CLOUD_RADIUS = 1.2f;
    private static final float CLOUD_STEP = 2f * CLOUD_RADIUS / CLOUD_TURNS;
    /** How much light a unit of the cloud's density stops over a unit of distance. */
    private static final float CLOUD_DENSITY = 6f;

    private static final Material.Surface CLOUD = new Material.Surface(CLOUD_KEY, cloudOutputs(), List.of(),
            List.of(new Material.Surface.Value("u_time", Vec4.ZERO),
                    new Material.Surface.Value("u_heat", PALETTES[0][0]),
                    new Material.Surface.Value("u_tint", PALETTES[0][1]),
                    new Material.Surface.Value("u_crust", PALETTES[0][2])),
            null);

    /** The cloud at {@code time} in palette {@code palette}: the same key and program, new values. */
    static Material.Surface cloud(double time, int palette) {
        Vec4[] p = PALETTES[palette];
        return CLOUD.with(List.of(), List.of(
                new Material.Surface.Value("u_time", new Vec4((float) time, 0, 0, 0)),
                new Material.Surface.Value("u_heat", p[0]),
                new Material.Surface.Value("u_tint", p[1]),
                new Material.Surface.Value("u_crust", p[2])));
    }

    /**
     * A glowing cloud, in the IR: each pixel of the panel marches a ray front to back through a
     * ball of drifting noise, a loop of two values, the colour and alpha gathered so far and the
     * distance gone, which is where along the ray the next step looks.
     *
     * <p>The density is the noise, thinned toward the ball's edge; each step adds the light of
     * what it crosses, in the palette's colours by how dense it is, behind what is already in
     * front of it. The loop ends once the gathered alpha passes 0.99, or at once for a ray that
     * passes beside the ball, a different step in each pixel, so it may hold no texture lookup and
     * no derivative; the noise is worked out, and needs neither. What the ray does not gather is
     * the crust behind it.
     */
    private static SurfaceOutputs cloudOutputs() {
        Expr time = new Expr.Swizzle(ref("u_time", ShaderType.VEC4), "x");
        Expr heatKnobs = ref("u_heat", ShaderType.VEC4);
        Expr across = mul(new Expr.Binary(Expr.Op.SUB, ref("v_uv", ShaderType.VEC2), Expr.Lit.of(0.5f, 0.5f)),
                Expr.Lit.of(PANEL_ASPECT * 2.6f, -2.6f));
        Expr drift = new Expr.Construct(ShaderType.VEC3, List.of(Expr.Lit.of(0f),
                mul(time, Expr.Lit.of(-0.35f)), mul(time, Expr.Lit.of(0.12f))));
        Expr.Loop march = Expr.Loop.of(List.of(Expr.Lit.of(0f, 0f, 0f, 0f), Expr.Lit.of(0f)), CLOUD_TURNS, s -> {
            Expr gathered = s.get(0);
            Expr travelled = s.get(1);
            Expr point = new Expr.Construct(ShaderType.VEC3, List.of(across, add(Expr.Lit.of(-CLOUD_RADIUS), travelled)));
            Expr edge = call("clamp", ShaderType.FLOAT, new Expr.Binary(Expr.Op.SUB, Expr.Lit.of(1f),
                    mul(call("length", ShaderType.FLOAT, point), Expr.Lit.of(1f / CLOUD_RADIUS))),
                    Expr.Lit.of(0f), Expr.Lit.of(1f));
            Expr density = mul(call("smoothstep", ShaderType.FLOAT, Expr.Lit.of(0.3f), Expr.Lit.of(0.75f),
                    noise(add(mul(point, Expr.Lit.of(1.7f)), drift))), mul(edge, Expr.Lit.of(1.6f)));
            Expr taken = mul(mul(call("min", ShaderType.FLOAT, density, Expr.Lit.of(1f)),
                    Expr.Lit.of(CLOUD_DENSITY * CLOUD_STEP)),
                    new Expr.Binary(Expr.Op.SUB, Expr.Lit.of(1f), new Expr.Swizzle(gathered, "w")));
            Expr heat = call("clamp", ShaderType.FLOAT, mul(density, Expr.Lit.of(1.4f)), Expr.Lit.of(0f), Expr.Lit.of(1f));
            Expr glow = mul(mul(call("pow", ShaderType.VEC3, new Expr.Construct(ShaderType.VEC3, List.of(heat)),
                    new Expr.Swizzle(heatKnobs, "xyz")), new Expr.Swizzle(ref("u_tint", ShaderType.VEC4), "xyz")),
                    mul(new Expr.Swizzle(heatKnobs, "w"), Expr.Lit.of(0.6f)));
            return List.of(add(gathered, mul(new Expr.Construct(ShaderType.VEC4, List.of(glow, Expr.Lit.of(1f))), taken)),
                    add(travelled, Expr.Lit.of(CLOUD_STEP)));
        }, s -> call("max", ShaderType.FLOAT,
                new Expr.Binary(Expr.Op.SUB, new Expr.Swizzle(s.get(0), "w"), Expr.Lit.of(0.99f)),
                // A ray that passes beside the ball gathers nothing: it stops before its first step.
                new Expr.Binary(Expr.Op.SUB, call("length", ShaderType.FLOAT, across), Expr.Lit.of(CLOUD_RADIUS))));
        Expr crust = new Expr.Construct(ShaderType.VEC4, List.of(
                mul(new Expr.Swizzle(ref("u_crust", ShaderType.VEC4), "xyz"),
                        new Expr.Binary(Expr.Op.SUB, Expr.Lit.of(1f), new Expr.Swizzle(march, "w"))),
                Expr.Lit.of(1f)));
        return SurfaceOutputs.of(crust, Expr.Lit.of(0f), Expr.Lit.of(0.82f), new Expr.Swizzle(march, "xyz"));
    }

    // ------------------------------------------------------------------- the view

    private static final Vec3 LAVA_EYE = new Vec3(0f, 2.2f, 5.4f);
    private static final Vec3 LAVA_TARGET = new Vec3(0, -0.35f, 0);
    private static final Vec3 PANEL_EYE = new Vec3(0f, 0f, 4.9f);

    /**
     * The scene for the showcase's page: the view, and under it the speed and the palette.
     *
     * @param views receives the view, for the page's render scale
     */
    static Widget<?> content(java.util.function.Consumer<Viewport3D> views) {
        Clock clock = new Clock();
        int[] palette = {0};
        int[] pattern = {0};
        MeshData sphereData = Primitives.sphere(1.25f, 64, 96);
        MeshData lakeData = Primitives.plane(80, 80);
        MeshData panelData = Primitives.plane(6.4f, 6.4f / PANEL_ASPECT);
        Scene3D[] scene = {null, null};
        MeshNode[] molten = new MeshNode[2];
        MeshNode[] panel = {null};

        Viewport3D viewport = new Viewport3D().setPreferredSize(560, 320);
        viewport.camera().eye(LAVA_EYE).target(LAVA_TARGET);
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
                // The panel stands up facing the camera: the plane turned a quarter about x.
                Scene3D framed = new Scene3D()
                        .background(new Vec4(0.02f, 0.02f, 0.025f, 1f))
                        .ambient(new Vec3(0.03f, 0.03f, 0.035f))
                        .exposure(1.0f);
                panel[0] = framed.root().add(new MeshNode(Graphics3D.upload(panelData), fractal(0, 0))
                        .transform(new Transform3D(Vec3.ZERO,
                                Quat.fromAxisAngle(Vec3.UNIT_X, (float) Math.PI / 2), Vec3.ONE)));
                framed.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.3f, 0.6f, 1f), new Vec3(0.75f, 0.8f, 1f), 1.2f)));
                scene[1] = framed;
            }
            double now = clock.advance(t);
            if (pattern[0] == 0) {
                // The sphere more molten than the lake: the same program, one value apart.
                molten[0].material(surface(now, palette[0], 0.07f));
                molten[1].material(surface(now, palette[0], 0f));
            } else {
                // The fractal and the cloud share the panel, each its own program.
                panel[0].material(pattern[0] == 1 ? fractal(now, palette[0]) : cloud(now, palette[0]));
            }
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[pattern[0] == 0 ? 0 : 1].render(pass, viewport.camera(), aspect);
        });
        viewport.observeDispose(() -> {
            for (int i = 0; i < scene.length; i++) {
                if (scene[i] != null) {
                    scene[i].dispose();
                    scene[i] = null; // the renderer above rebuilds lazily on re-attach
                }
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

        SegmentedControl patterns = new SegmentedControl(Text.MOLTEN, Text.FRACTAL, Text.CLOUD);
        patterns.setAccessibleName(Text.PATTERN);
        java.util.function.IntConsumer choosePattern = index -> {
            pattern[0] = index;
            // Each pattern from where it is best seen; the orbit starts again from there.
            viewport.camera().eye(index == 0 ? LAVA_EYE : PANEL_EYE).target(index == 0 ? LAVA_TARGET : Vec3.ZERO);
            viewport.setController(new OrbitController(viewport.camera()));
            viewport.invalidate();
        };
        patterns.onSelect(choosePattern);
        // A lab switch for a film of the page: -Dlimn.demo.shader.pattern=Fractal (or Cloud) opens
        // on that pattern, by its English name.
        String asked = String.valueOf(System.getProperty("limn.demo.shader.pattern"));
        List<I18nString> named = List.of(Text.MOLTEN, Text.FRACTAL, Text.CLOUD);
        for (int i = 1; i < named.size(); i++) {
            if (named.get(i).english().equalsIgnoreCase(asked)) {
                patterns.setSelectedIndex(i);
                choosePattern.accept(i);
            }
        }

        Label paused = Page.hint(Text.PAUSED);
        Runnable followMotion = () -> {
            boolean still = Motion.reduced();
            viewport.setAnimated(!still);
            paused.setVisible(still);
        };
        followMotion.run();
        SystemPreferences.observeChanges(followMotion); // for the life of the page, as Page's are

        Row controls = Page.row(12, Labelled.caption(Text.PATTERN, patterns).setMuted(true), patterns,
                Labelled.caption(Text.SPEED, speed).setMuted(true),
                new limn.scene.layout.SizedBox(140, limn.scene.layout.SizedBox.UNSET, speed),
                Labelled.caption(Text.PALETTE, palettes).setMuted(true), palettes);
        controls.add(Expanded.spacer(1));

        Column body = new Column();
        body.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.STRETCH);
        body.add(Page.aspect(16f / 9f, viewport));
        body.add(controls);
        body.add(paused); // a line of its own: the row of controls is full in a long language
        return body;
    }
}

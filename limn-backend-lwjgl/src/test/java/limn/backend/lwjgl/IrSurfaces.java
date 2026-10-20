package limn.backend.lwjgl;

import limn.render3d.shader.Expr;
import limn.render3d.shader.ShaderType;
import limn.render3d.shader.SurfaceOutputs;
import limn.render3d.shader.TargetProfile;

import java.util.List;

/**
 * Surfaces in the shader IR of the shapes applications write, for the generator's tests: the
 * shapes that share nodes, unroll an iteration, or loop.
 */
final class IrSurfaces {

    private IrSurfaces() {
    }

    static Expr ref(String name, ShaderType type) {
        return new Expr.Ref(name, type);
    }

    static Expr add(Expr a, Expr b) {
        return new Expr.Binary(Expr.Op.ADD, a, b);
    }

    static Expr sub(Expr a, Expr b) {
        return new Expr.Binary(Expr.Op.SUB, a, b);
    }

    static Expr mul(Expr a, Expr b) {
        return new Expr.Binary(Expr.Op.MUL, a, b);
    }

    static Expr call(String function, ShaderType type, Expr... args) {
        return new Expr.Call(function, type, List.of(args));
    }

    static Expr x(Expr e) {
        return new Expr.Swizzle(e, "x");
    }

    static Expr y(Expr e) {
        return new Expr.Swizzle(e, "y");
    }

    /** A surface whose only light is {@code emissive}: black, rough, not metal. */
    static SurfaceOutputs emitting(Expr emissive) {
        return SurfaceOutputs.of(Expr.Lit.of(0f, 0f, 0f, 1f), Expr.Lit.of(0f), Expr.Lit.of(1f), emissive);
    }

    /** One step of z ↦ z² + c, written out: it reads z four times. */
    static Expr square(Expr z, Expr c) {
        return add(new Expr.Construct(ShaderType.VEC2, List.of(
                sub(mul(x(z), x(z)), mul(y(z), y(z))),
                mul(mul(Expr.Lit.of(2f), x(z)), y(z)))), c);
    }

    /** {@code steps} steps of z ↦ z² + c unrolled, from z = c: what a surface had to write before a loop. */
    static SurfaceOutputs unrolled(int steps) {
        Expr c = mul(sub(ref("v_uv", ShaderType.VEC2), Expr.Lit.of(0.5f, 0.5f)), Expr.Lit.of(0.2f));
        Expr z = c;
        for (int i = 0; i < steps; i++) {
            z = square(z, c);
        }
        Expr r = call("length", ShaderType.FLOAT, z);
        return emitting(new Expr.Construct(ShaderType.VEC3, List.of(r)));
    }

    /**
     * An escape-time Mandelbrot with smooth colouring, through a loop: the state is z and the
     * number of turns taken, and the loop ends when |z|² passes 256.
     */
    static SurfaceOutputs mandelbrot(int turns) {
        Expr c = mul(sub(ref("v_uv", ShaderType.VEC2), Expr.Lit.of(0.6f, 0.5f)), Expr.Lit.of(3f, 2.4f));
        Expr.Loop loop = Expr.Loop.of(Expr.Lit.of(0f, 0f, 0f, 0f), turns,
                s -> new Expr.Construct(ShaderType.VEC4, List.of(square(new Expr.Swizzle(s, "xy"), c),
                        add(new Expr.Swizzle(s, "z"), Expr.Lit.of(1f)), Expr.Lit.of(0f))),
                s -> sub(call("dot", ShaderType.FLOAT, new Expr.Swizzle(s, "xy"), new Expr.Swizzle(s, "xy")),
                        Expr.Lit.of(256f)));
        Expr escaped = call("max", ShaderType.FLOAT, call("length", ShaderType.FLOAT, new Expr.Swizzle(loop, "xy")),
                Expr.Lit.of(1.0001f));
        Expr smooth = sub(add(new Expr.Swizzle(loop, "z"), Expr.Lit.of(1f)),
                call("log2", ShaderType.FLOAT, call("log2", ShaderType.FLOAT, escaped)));
        Expr t = call("clamp", ShaderType.FLOAT, mul(smooth, Expr.Lit.of(1f / turns)), Expr.Lit.of(0f), Expr.Lit.of(1f));
        return emitting(new Expr.Construct(ShaderType.VEC3, List.of(t)));
    }

    /**
     * The dissolve's shape: one sheet sampled twice, a band of a mask by a knob read twice, and
     * the band's complement as the glow. Shares nodes the way application surfaces do.
     */
    static SurfaceOutputs dissolveLike() {
        Expr sheet = ref("u_sheet", ShaderType.SAMPLER2D);
        Expr mask = ref("u_mask", ShaderType.SAMPLER2D);
        Expr knobs = ref("u_knobs", ShaderType.VEC4);
        Expr uv = ref("v_uv", ShaderType.VEC2);
        Expr blended = call("mix", ShaderType.VEC4, new Expr.Sample((Expr.Ref) sheet, uv),
                new Expr.Sample((Expr.Ref) sheet, mul(uv, Expr.Lit.of(0.5f))), x(knobs));
        Expr band = call("smoothstep", ShaderType.FLOAT, x(knobs), add(x(knobs), y(knobs)),
                x(new Expr.Sample((Expr.Ref) mask, uv)));
        Expr albedo = call("srgbToLinear", ShaderType.VEC3, new Expr.Swizzle(blended, "rgb"));
        return SurfaceOutputs.of(new Expr.Construct(ShaderType.VEC4, List.of(albedo, Expr.Lit.of(1f))),
                Expr.Lit.of(0f), Expr.Lit.of(0.55f),
                mul(Expr.Lit.of(2.4f, 0.8f, 0.2f), sub(Expr.Lit.of(1f), band)));
    }

    /** Value noise at a 2D point: four hashed corners blended by a smoothed fraction. */
    static Expr noise(Expr p) {
        Expr cell = call("floor", ShaderType.VEC2, p);
        Expr u = call("smoothstep", ShaderType.VEC2, Expr.Lit.of(0f, 0f), Expr.Lit.of(1f, 1f),
                call("fract", ShaderType.VEC2, p));
        Expr a = hash(cell);
        Expr b = hash(add(cell, Expr.Lit.of(1f, 0f)));
        Expr c = hash(add(cell, Expr.Lit.of(0f, 1f)));
        Expr d = hash(add(cell, Expr.Lit.of(1f, 1f)));
        return call("mix", ShaderType.FLOAT, call("mix", ShaderType.FLOAT, a, b, x(u)),
                call("mix", ShaderType.FLOAT, c, d, x(u)), y(u));
    }

    static Expr hash(Expr point) {
        return call("fract", ShaderType.FLOAT, mul(call("sin", ShaderType.FLOAT,
                call("dot", ShaderType.FLOAT, point, Expr.Lit.of(127.1f, 311.7f))), Expr.Lit.of(43758.547f)));
    }

    /** The molten rock's shape: three octaves of noise, a heat, a glow raised per channel. */
    static SurfaceOutputs moltenLike() {
        Expr p = mul(ref("v_uv", ShaderType.VEC2), Expr.Lit.of(6f));
        Expr fbm = Expr.Lit.of(0f);
        float amplitude = 0.5f;
        float frequency = 1f;
        for (int octave = 0; octave < 3; octave++) {
            fbm = add(fbm, mul(noise(add(mul(p, Expr.Lit.of(frequency)), Expr.Lit.of(octave * 5.2f, octave * 1.3f))),
                    Expr.Lit.of(amplitude)));
            amplitude *= 0.5f;
            frequency *= 2.03f;
        }
        Expr heat = call("smoothstep", ShaderType.FLOAT, Expr.Lit.of(0.3f), Expr.Lit.of(0.7f), fbm);
        Expr glow = mul(call("pow", ShaderType.VEC3, new Expr.Construct(ShaderType.VEC3, List.of(heat)),
                Expr.Lit.of(1f, 2.3f, 5.5f)), Expr.Lit.of(3f));
        return SurfaceOutputs.of(Expr.Lit.of(0.05f, 0.04f, 0.035f, 1f), Expr.Lit.of(0f), Expr.Lit.of(0.8f), glow);
    }

    /**
     * A loop that runs every turn and samples in each: a box blur along u. Uniform control flow,
     * so the sample is defined in it.
     */
    static SurfaceOutputs blurLoop() {
        Expr uv = ref("v_uv", ShaderType.VEC2);
        Expr.Loop loop = Expr.Loop.of(Expr.Lit.of(0f, 0f, 0f, 0f), 8, acc -> {
            Expr offset = new Expr.Construct(ShaderType.VEC2, List.of(
                    mul(new Expr.Swizzle(acc, "w"), Expr.Lit.of(0.01f)), Expr.Lit.of(0f)));
            Expr texel = new Expr.Swizzle(new Expr.Sample((Expr.Ref) ref("u_sheet", ShaderType.SAMPLER2D),
                    add(uv, offset)), "rgb");
            return new Expr.Construct(ShaderType.VEC4, List.of(add(new Expr.Swizzle(acc, "rgb"), texel),
                    add(new Expr.Swizzle(acc, "w"), Expr.Lit.of(1f))));
        });
        return emitting(mul(new Expr.Swizzle(loop, "rgb"), Expr.Lit.of(1f / 8)));
    }

    /**
     * A sampler reached through raw text, read twice: the shape of a surface that names a sampler
     * the IR has no reference for. It linked before nodes were shared, and must go on linking.
     */
    static SurfaceOutputs samplerThroughRawText() {
        Expr sheet = new Expr.Raw(TargetProfile.GLSL_330, "u_sheet", ShaderType.SAMPLER2D);
        Expr uv = ref("v_uv", ShaderType.VEC2);
        return SurfaceOutputs.of(call("texture", ShaderType.VEC4, sheet, uv), Expr.Lit.of(0f), Expr.Lit.of(1f),
                new Expr.Swizzle(call("texture", ShaderType.VEC4, sheet, mul(uv, Expr.Lit.of(2f))), "rgb"));
    }

    /**
     * Two loops whose states have one name, built by hand as a loop built apart and set in another's
     * turn would be: the outer state s becomes s·2 + (s doubled twice by the inner loop), 6s,
     * three times from (0.001, 0.002, 0.003, 1/216). The inner loop starts from the outer state and
     * its own turn doubles the inner state, under the same name.
     */
    static Expr.Loop shadowingLoops() {
        String name = Expr.Loop.of(Expr.Lit.of(0f), 1, s -> s).state();
        Expr.Ref s = new Expr.Ref(name, ShaderType.VEC4);
        Expr twice = mul(s, Expr.Lit.of(2f));
        Expr.Loop inner = new Expr.Loop(name, s, 2, twice, null);
        return new Expr.Loop(name, Expr.Lit.of(0.001f, 0.002f, 0.003f, 1f / 216), 3, add(twice, inner), null);
    }
}

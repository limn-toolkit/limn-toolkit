package limn.render3d.shader;

import java.util.List;

/**
 * A node in the neutral, typed shader-expression DAG. A material contributes a
 * {@link SurfaceOutputs} built from these; a backend code generator walks the DAG
 * to emit target GLSL (or, later, SPIR-V/MSL). Every node exposes its
 * {@link ShaderType} so the generator can type-check and construct correctly.
 *
 * <p>The set is deliberately small (enough for the standard metallic-roughness
 * surface and simple custom surfaces), with {@link Raw} as a per-target escape.
 */
public sealed interface Expr
        permits Expr.Lit, Expr.Ref, Expr.Sample, Expr.Swizzle, Expr.Binary, Expr.Call,
        Expr.Construct, Expr.Raw {

    ShaderType type();

    /** Arithmetic operators. */
    enum Op {
        ADD("+"), SUB("-"), MUL("*"), DIV("/");

        public final String glsl;

        Op(String glsl) {
            this.glsl = glsl;
        }
    }

    /** A numeric literal, scalar or vector by value count. */
    record Lit(float[] values) implements Expr {
        @Override
        public ShaderType type() {
            return ShaderType.ofComponents(values.length);
        }

        /** A literal of one to four components, matching the count given. */
    public static Lit of(float... values) {
            return new Lit(values);
        }
    }

    /** A named input the framework declares (a UBO member, varying, or sampler). */
    record Ref(String name, ShaderType type) implements Expr {
    }

    /** Samples {@code sampler} at {@code uv} → vec4. */
    record Sample(Ref sampler, Expr uv) implements Expr {
        @Override
        public ShaderType type() {
            return ShaderType.VEC4;
        }
    }

    /** Component selection, e.g. {@code .rgb} (→vec3) or {@code .x} (→float). */
    record Swizzle(Expr source, String pattern) implements Expr {
        @Override
        public ShaderType type() {
            return ShaderType.ofComponents(pattern.length());
        }
    }

    /** Binary arithmetic; result type follows GLSL scalar/vector promotion. */
    record Binary(Op op, Expr left, Expr right) implements Expr {
        @Override
        public ShaderType type() {
            return left.type().components >= right.type().components ? left.type() : right.type();
        }
    }

    /** A built-in or engine helper call with a declared result type. */
    record Call(String function, ShaderType type, List<Expr> args) implements Expr {
    }

    /** A constructor like {@code vec4(rgb, a)} or {@code vec3(x)}. */
    record Construct(ShaderType type, List<Expr> args) implements Expr {
    }

    /**
     * Non-portable inline GLSL, valid only for {@code target}. Codegen for any other
     * {@link TargetProfile} rejects it: the material opted out of portability.
     */
    record Raw(TargetProfile target, String glsl, ShaderType type) implements Expr {
    }
}

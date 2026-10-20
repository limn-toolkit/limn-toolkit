package limn.render3d.shader;

import java.util.List;

/**
 * A node in the neutral, typed shader-expression DAG. A material contributes a
 * {@link SurfaceOutputs} built from these; a backend code generator walks the DAG
 * to emit target GLSL (or, later, SPIR-V/MSL). Every node exposes its
 * {@link ShaderType} so the generator can type-check and construct correctly.
 *
 * <p>The set is deliberately small (enough for the standard metallic-roughness
 * surface and custom surfaces, iterative ones included through {@link Loop}), with
 * {@link Raw} as a per-target escape.
 *
 * <p><b>A node used twice is computed once.</b> The IR is a value: two nodes are the same
 * computation when their kinds, fields and children are, whether they are one Java object or two
 * built apart, and {@code equals} and {@code hashCode} say so. The generator writes such a node
 * once and reads it wherever it is used, so a Java method that builds a sub-expression is as
 * cheap to call twice as once, and a variable reused in a long chain does not multiply the
 * shader's size. Comparing and hashing visit a node shared by many parents once; a node's
 * {@code toString} writes it out as a tree, as long as every path through it.
 *
 * <p>A 1.x minor release may add node types: a {@code switch} over an {@code Expr} needs a
 * {@code default} branch.
 */
public sealed interface Expr
        permits Expr.Lit, Expr.Ref, Expr.Sample, Expr.Swizzle, Expr.Binary, Expr.Call,
        Expr.Construct, Expr.Raw, Expr.Loop {

    ShaderType type();

    /**
     * Arithmetic operators.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    enum Op {
        ADD("+"), SUB("-"), MUL("*"), DIV("/");

        public final String glsl;

        Op(String glsl) {
            this.glsl = glsl;
        }
    }

    /**
     * A numeric literal, scalar or vector by value count. It keeps a copy of the values it is
     * given and hands out a copy, and two literals of the same values are equal.
     */
    record Lit(float[] values) implements Expr {
        public Lit {
            values = values.clone();
        }

        /** A literal of one to four components, matching the count given. */
        public static Lit of(float... values) {
            return new Lit(values);
        }

        @Override
        public ShaderType type() {
            return ShaderType.ofComponents(values.length);
        }

        /** A copy of the values, so the literal stays what it was built as. */
        @Override
        public float[] values() {
            return values.clone();
        }

        @Override
        public boolean equals(@org.jspecify.annotations.Nullable Object other) {
            return other instanceof Lit lit && java.util.Arrays.equals(values, lit.values);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return "Lit[values=" + java.util.Arrays.toString(values) + "]";
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

        @Override
        public boolean equals(@org.jspecify.annotations.Nullable Object other) {
            return Values.equal(this, other);
        }

        @Override
        public int hashCode() {
            return Values.hash(this);
        }
    }

    /** Component selection, e.g. {@code .rgb} (→vec3) or {@code .x} (→float). */
    record Swizzle(Expr source, String pattern) implements Expr {
        @Override
        public ShaderType type() {
            return ShaderType.ofComponents(pattern.length());
        }

        @Override
        public boolean equals(@org.jspecify.annotations.Nullable Object other) {
            return Values.equal(this, other);
        }

        @Override
        public int hashCode() {
            return Values.hash(this);
        }
    }

    /** Binary arithmetic; result type follows GLSL scalar/vector promotion. */
    record Binary(Op op, Expr left, Expr right) implements Expr {
        @Override
        public ShaderType type() {
            return Values.type(this);
        }

        @Override
        public boolean equals(@org.jspecify.annotations.Nullable Object other) {
            return Values.equal(this, other);
        }

        @Override
        public int hashCode() {
            return Values.hash(this);
        }
    }

    /** A built-in or engine helper call with a declared result type. */
    record Call(String function, ShaderType type, List<Expr> args) implements Expr {
        public Call {
            args = List.copyOf(args);
        }

        @Override
        public boolean equals(@org.jspecify.annotations.Nullable Object other) {
            return Values.equal(this, other);
        }

        @Override
        public int hashCode() {
            return Values.hash(this);
        }
    }

    /** A constructor like {@code vec4(rgb, a)} or {@code vec3(x)}. */
    record Construct(ShaderType type, List<Expr> args) implements Expr {
        public Construct {
            args = List.copyOf(args);
        }

        @Override
        public boolean equals(@org.jspecify.annotations.Nullable Object other) {
            return Values.equal(this, other);
        }

        @Override
        public int hashCode() {
            return Values.hash(this);
        }
    }

    /**
     * Non-portable inline GLSL, valid only for {@code target}. Codegen for any other
     * {@link TargetProfile} rejects it: the material opted out of portability.
     */
    record Raw(TargetProfile target, String glsl, ShaderType type) implements Expr {
    }

    /**
     * A value computed by a bounded loop: a named state, its initial value, and the state after
     * one turn, taken at most {@code maxIterations} times.
     *
     * <p>Each turn first asks {@code until}, when there is one, of the state as it stands: above
     * zero ends the loop; otherwise the state becomes {@code next}. The loop's value is the state
     * when it ends. Inside {@code next} and {@code until} the state is read as
     * {@code new Expr.Ref(state, initial.type())}, and there the name is the state even where an
     * input of the surface or a varying of the framework has the same one; outside them the name
     * means nothing. A part of {@code next} or {@code until} that does not read the state is
     * computed once, before the loop.
     *
     * <pre>{@code
     * // An escape-time fractal: z ↦ z² + c, counting turns, until |z|² passes 256.
     * Expr.Ref s = new Expr.Ref("s", ShaderType.VEC4);           // (z.x, z.y, turns, 0)
     * Expr next = ...;                                           // (z² + c, turns + 1, 0)
     * Expr until = minus(dot(s.xy, s.xy), 256);                  // above zero: escaped
     * Expr.Loop loop = new Expr.Loop("s", Expr.Lit.of(0, 0, 0, 0), 256, next, until);
     * }</pre>
     *
     * <p><b>Bounded across a whole surface.</b> The count is a constant from 1 to
     * {@link #MAX_ITERATIONS}, and so is every turn a pixel takes in all the loops of one surface.
     * Loops side by side, and a loop in another's initial value, add their turns; a loop in
     * another's turn multiplies by that loop's count: a loop of 32 turns may hold one of 32, not
     * one of 64, nor two of 32 side by side. A loop read twice, or two built alike, is computed
     * once and counted once. A loop holds itself and what it holds to the cap, and
     * {@link SurfaceOutputs} holds the whole surface, counting the turns of its diffuse response
     * once for each light a pass shades with (once when it is built; a generator, which knows how
     * many lights it shades with, holds the surface to that count). So no surface asks a pixel for
     * more than {@value #MAX_ITERATIONS} turns, in any profile. The cap bounds turns, not what a
     * turn costs, which is the surface's as it is without a loop. {@code until} is a float rather
     * than a boolean, so a threshold is a subtraction away; an iteration count, when a surface
     * wants one, is part of its state.
     *
     * <p><b>An early end is non-uniform control flow.</b> A loop with an {@code until} ends at
     * different turns in neighbouring pixels, where the shading languages leave a derivative and
     * a texture lookup that picks its own level of detail undefined, so its {@code next} and
     * {@code until} may hold no {@link Sample}, no call to {@code dFdx}, {@code dFdy} or
     * {@code fwidth}, and no call to {@code texture}, {@code textureProj}, {@code textureOffset} or
     * {@code textureProjOffset}; {@code textureLod}, {@code textureGrad}, their variants,
     * {@code texelFetch} and {@code textureSize} are defined there. A generator also refuses there
     * any {@link Raw} text and every call it does not know to be defined, its own helpers that
     * sample or take derivatives among them. A loop without an {@code until} runs every turn in
     * every pixel and may hold them all.
     *
     * <p>The state's name is an identifier every profile accepts and none of the generator's:
     * ASCII letters, digits and {@code _}, a letter first, no {@code __}, and neither
     * {@code gl_} nor {@code ir_} at its start. A loop inside another may not reuse an enclosing
     * loop's name; two loops side by side may.
     *
     * @param state         the state's name, read in {@code next} and {@code until}
     * @param initial       the state before the first turn; a float or vector type
     * @param maxIterations how many turns at most, 1 to {@link #MAX_ITERATIONS}
     * @param next          the state after one turn, of the initial value's type
     * @param until         a float that ends the loop where it is above zero, or {@code null}
     *                      to take every turn
     */
    record Loop(String state, Expr initial, int maxIterations, Expr next,
                @org.jspecify.annotations.Nullable Expr until) implements Expr {

        /** The most turns a pixel may take across every loop of a surface. */
        public static final int MAX_ITERATIONS = 1024;

        /**
         * Checks the loop as the class describes.
         *
         * @throws IllegalArgumentException if the count, the turns with the loops it holds, a type or
         *                                  the name is out of these bounds, or a loop that can end
         *                                  early samples or takes a derivative
         */
        public Loop {
            java.util.Objects.requireNonNull(state, "state");
            java.util.Objects.requireNonNull(initial, "initial");
            java.util.Objects.requireNonNull(next, "next");
            if (!state.matches("[A-Za-z][A-Za-z0-9_]*") || state.contains("__")
                    || state.startsWith("gl_") || state.startsWith("ir_")) {
                throw new IllegalArgumentException("not a name a loop's state can take: \"" + state + "\"");
            }
            ShaderType type = initial.type();
            if (type.components == 0) {
                throw new IllegalArgumentException("a loop carries a float or a vector, not " + type);
            }
            if (next.type() != type) {
                throw new IllegalArgumentException("the next state is a " + next.type()
                        + " where the state is a " + type);
            }
            if (until != null && until.type() != ShaderType.FLOAT) {
                throw new IllegalArgumentException("until is a float, above zero to end, not a " + until.type());
            }
            if (maxIterations < 1 || maxIterations > MAX_ITERATIONS) {
                throw new IllegalArgumentException("a loop takes 1 to " + MAX_ITERATIONS + " turns, not "
                        + maxIterations);
            }
            Loops.refuseReusedNames(next, state);
            if (until != null) {
                Loops.refuseReusedNames(until, state);
            }
            long turns = Loops.turns(initial, maxIterations, until == null ? List.of(next) : List.of(next, until));
            if (turns > MAX_ITERATIONS) {
                throw new IllegalArgumentException("this loop takes up to " + turns + " turns a pixel, with"
                        + " the loops it holds, more than " + MAX_ITERATIONS);
            }
            if (until != null) {
                String undefined = Loops.undefinedEarly(next);
                if (undefined == null) {
                    undefined = Loops.undefinedEarly(until);
                }
                if (undefined != null) {
                    throw new IllegalArgumentException("a loop that can end early cannot hold " + undefined
                            + ": it is undefined where neighbouring pixels take different turns");
                }
            }
        }

        @Override
        public ShaderType type() {
            return initial.type();
        }

        @Override
        public boolean equals(@org.jspecify.annotations.Nullable Object other) {
            return Values.equal(this, other);
        }

        @Override
        public int hashCode() {
            return Values.hash(this);
        }
    }
}

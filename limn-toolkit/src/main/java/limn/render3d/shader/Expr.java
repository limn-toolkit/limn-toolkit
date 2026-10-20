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
 * shader's size. Comparing, hashing and {@code toString} visit a node shared by many parents
 * once; {@code toString} writes such a node the first time as {@code #1=…} and then as {@code #1}.
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

    /**
     * A named value: an input the framework or the surface declares (a UBO member, a varying, a
     * sampler, a {@code Material.Surface} input), or the state of the {@link Loop} it is read in,
     * which {@link Loop#of} hands the loop's turn.
     */
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

        @Override
        public String toString() {
            return Values.text(this);
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

        @Override
        public String toString() {
            return Values.text(this);
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

        @Override
        public String toString() {
            return Values.text(this);
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

        @Override
        public String toString() {
            return Values.text(this);
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

        @Override
        public String toString() {
            return Values.text(this);
        }
    }

    /**
     * Non-portable inline GLSL, valid only for {@code target}. Codegen for any other
     * {@link TargetProfile} rejects it: the material opted out of portability.
     */
    record Raw(TargetProfile target, String glsl, ShaderType type) implements Expr {
    }

    /**
     * A value computed by a bounded loop: a state, its initial value, and the state after one
     * turn, taken at most {@code maxIterations} times. Build one with {@link #of}, which hands the
     * turn its state as a node.
     *
     * <p>Each turn first asks {@code until}, when there is one, of the state as it stands: above
     * zero ends the loop; otherwise the state becomes {@code next}. The loop's value is the state
     * when it ends. A part of {@code next} or {@code until} that does not read the state is
     * computed once, before the loop.
     *
     * <pre>{@code
     * // An escape-time fractal: z ↦ z² + c, counting turns, until |z|² passes 256.
     * // The state is (z.x, z.y, turns, 0).
     * Expr.Loop escape = Expr.Loop.of(Expr.Lit.of(0, 0, 0, 0), 256,
     *         s -> vec4(plus(square(xy(s)), c), plus(z(s), 1), 0),   // the next state
     *         s -> minus(dot(xy(s), xy(s)), 256));                   // above zero: escaped
     * }</pre>
     *
     * <p><b>The state is a node, not a name.</b> {@link #of} gives {@code next} and {@code until}
     * the state as an {@link Ref} of the initial value's type, under a name it chooses, which a
     * surface's inputs cannot take (they may not start {@code ir_}). That is the record's
     * {@code state}: a record keeps its components, and the IR has to say which references a loop
     * binds, but nothing else names it. A loop built inside another's turn gets a name of its own,
     * so it may read the outer state; one built apart may sit in another's turn, its state hiding
     * the outer one, which it could not have read. The state means nothing outside its loop:
     * {@link SurfaceOutputs} refuses a surface that reads it there.
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
     * @param state         the state's name, as {@link #of} gives it: {@code ir_state} and a number
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

        /** How many loops are being built around the one being built now, on this thread. */
        private static final ThreadLocal<int[]> BUILDING = ThreadLocal.withInitial(() -> new int[1]);

        /**
         * A loop that takes every turn, in every pixel.
         *
         * @param initial       the state before the first turn; a float or vector type
         * @param maxIterations how many turns, 1 to {@link #MAX_ITERATIONS}
         * @param next          the state after one turn, given the state
         * @return the loop, whose value is the state after the last turn
         * @throws IllegalArgumentException as the constructor
         */
        public static Loop of(Expr initial, int maxIterations, java.util.function.UnaryOperator<Expr> next) {
            return build(initial, maxIterations, next, null);
        }

        /**
         * A loop that may end early: before each turn, {@code until} of the state above zero ends it.
         *
         * @param initial       the state before the first turn; a float or vector type
         * @param maxIterations how many turns at most, 1 to {@link #MAX_ITERATIONS}
         * @param next          the state after one turn, given the state
         * @param until         a float above zero where the loop ends, given the state
         * @return the loop, whose value is the state when it ends
         * @throws IllegalArgumentException as the constructor
         */
        public static Loop of(Expr initial, int maxIterations, java.util.function.UnaryOperator<Expr> next,
                              java.util.function.UnaryOperator<Expr> until) {
            return build(initial, maxIterations, next, java.util.Objects.requireNonNull(until, "until"));
        }

        /**
         * Names the state by how deep the loop is built, so a loop built inside another's turn reads
         * the outer state under another name, and a method that builds a loop builds an equal one
         * each time it is called at the same depth, which the generator then computes once.
         */
        private static Loop build(Expr initial, int maxIterations, java.util.function.UnaryOperator<Expr> next,
                                  java.util.function.@org.jspecify.annotations.Nullable UnaryOperator<Expr> until) {
            java.util.Objects.requireNonNull(initial, "initial");
            java.util.Objects.requireNonNull(next, "next");
            int[] depth = BUILDING.get();
            String name = Loops.STATE_PREFIX + depth[0];
            Expr state = new Ref(name, initial.type());
            Expr after;
            Expr end;
            depth[0]++;
            try {
                after = next.apply(state);
                end = until == null ? null : until.apply(state);
            } finally {
                depth[0]--;
            }
            return new Loop(name, initial, maxIterations, after, end);
        }

        /**
         * Checks the loop as the class describes.
         *
         * @throws IllegalArgumentException if the count, the turns with the loops it holds, a type
         *                                  (the state's wherever it is read included) or the name is
         *                                  out of these bounds, or a loop that can end early samples
         *                                  or takes a derivative
         */
        public Loop {
            java.util.Objects.requireNonNull(state, "state");
            java.util.Objects.requireNonNull(initial, "initial");
            java.util.Objects.requireNonNull(next, "next");
            if (!Loops.isStateName(state)) {
                throw new IllegalArgumentException("a loop's state is named by Loop.of, " + Loops.STATE_PREFIX
                        + " and a number, not \"" + state + "\"");
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
            ShaderType misread = Loops.misread(next, state, type);
            if (misread == null && until != null) {
                misread = Loops.misread(until, state, type);
            }
            if (misread != null) {
                throw new IllegalArgumentException("the state is a " + type + ", read here as a " + misread);
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

        @Override
        public String toString() {
            return Values.text(this);
        }
    }
}

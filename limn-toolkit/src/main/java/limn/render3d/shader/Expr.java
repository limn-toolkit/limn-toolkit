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
     * A value computed by a bounded loop: a state of one value or several, their initial values,
     * and the state after one turn, taken at most {@code maxIterations} times. Build one with
     * {@link #of}, which hands the turn its state as nodes.
     *
     * <p>Each turn first asks {@code until}, when there is one, of the state as it stands: above
     * zero ends the loop; otherwise every value of the state becomes its {@code next} at once, each
     * computed from the state before the turn. The loop's values are the state when it ends. The
     * node is the first of them, and {@link #value(int)} reads the others: a loop read for two of
     * its values is still one loop, computed once. A part of {@code next} or {@code until} that
     * does not read the state is computed once, before the loop.
     *
     * <pre>{@code
     * // An escape-time fractal: z ↦ z² + c, counting turns, until |z|² passes 256.
     * // The state is (z.x, z.y, turns, 0).
     * Expr.Loop escape = Expr.Loop.of(Expr.Lit.of(0, 0, 0, 0), 256,
     *         s -> vec4(plus(square(xy(s)), c), plus(z(s), 1), 0),   // the next state
     *         s -> minus(dot(xy(s), xy(s)), 256));                   // above zero: escaped
     *
     * // A ray marched through a volume: the colour gathered and the distance travelled.
     * Expr.Loop march = Expr.Loop.of(List.of(Expr.Lit.of(0, 0, 0, 0), Expr.Lit.of(0)), 64,
     *         s -> List.of(plus(s.get(0), gathered(s.get(0), s.get(1))), plus(s.get(1), step)),
     *         s -> minus(alpha(s.get(0)), 0.99f));                   // all but opaque: stop
     * Expr colour = march;                                         // the first value
     * Expr travelled = march.value(1);                             // the second
     * }</pre>
     *
     * <p><b>The state is nodes, not names.</b> {@link #of} gives {@code next} and {@code until}
     * each value of the state as a {@link Ref} of its initial value's type, under names it
     * chooses, which a surface's inputs cannot take (they may not start {@code ir_}). Those are the
     * record's {@code states}: a record keeps its components, and the IR has to say which
     * references a loop binds, but nothing else names them. A loop is named by how many loops nest
     * in its turn, one more than any loop in it holds, so a loop in another's turn, built there or
     * apart, on any thread, has names of its own and may read the outer state. The state means
     * nothing outside its loop: {@link SurfaceOutputs} refuses a surface that reads it there.
     *
     * <p><b>Bounded across a whole surface.</b> The count is a constant from 1 to
     * {@link #MAX_ITERATIONS}, and so is every turn a pixel takes in all the loops of one surface.
     * The count is what the generated shader runs: a loop runs once for each turn of the innermost
     * loop whose state it reads, however deep it was built, and once for the surface when it reads
     * none; loops side by side, and a loop in another's initial value, add their turns; a loop
     * read twice, for one value or several, or two built alike, is computed and counted once where
     * it is first written. So a loop of 32 turns may hold one of 32 that reads its state, not one
     * of 64 nor two of 32 side by side, and it may hold a loop of 512 that reads none. A loop holds
     * itself and what it holds to the cap, and {@link SurfaceOutputs} holds the whole surface,
     * counting the turns of its diffuse response once for each of the eight lights a pass shades
     * with at most. So no surface asks a
     * pixel for more than {@value #MAX_ITERATIONS} turns, in any profile. The cap bounds turns,
     * not what a turn costs, which is the surface's as it is without a loop. {@code until} is a
     * float rather than a boolean, so a threshold is a subtraction away; an iteration count, when
     * a surface wants one, is a value of its state.
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
     * @param states        the names of the state's values, as {@link #of} gives them:
     *                      {@code ir_state}, how many loops nest in its turn, {@code _} and the
     *                      value's place
     * @param initial       each value before the first turn; a float or vector type
     * @param maxIterations how many turns at most, 1 to {@link #MAX_ITERATIONS}
     * @param next          each value after one turn, of its initial value's type
     * @param until         a float that ends the loop where it is above zero, or {@code null}
     *                      to take every turn
     * @param index         which value of the state this node is, from 0
     */
    record Loop(List<String> states, List<Expr> initial, int maxIterations, List<Expr> next,
                @org.jspecify.annotations.Nullable Expr until, int index) implements Expr {

        /** The most turns a pixel may take across every loop of a surface. */
        public static final int MAX_ITERATIONS = 1024;

        /** Numbers the loops being built, for the names their turns read until they are named. */
        private static final java.util.concurrent.atomic.AtomicLong BUILDING = new java.util.concurrent.atomic.AtomicLong();

        /**
         * A loop of one value that takes every turn, in every pixel.
         *
         * @param initial       the state before the first turn; a float or vector type
         * @param maxIterations how many turns, 1 to {@link #MAX_ITERATIONS}
         * @param next          the state after one turn, given the state
         * @return the loop, whose value is the state after the last turn
         * @throws IllegalArgumentException as the constructor
         */
        public static Loop of(Expr initial, int maxIterations,
                              java.util.function.Function<? super Expr, ? extends Expr> next) {
            java.util.Objects.requireNonNull(next, "next");
            return build(List.of(initial), maxIterations, state -> List.of(next.apply(state.get(0))), null);
        }

        /**
         * A loop of one value that may end early: before each turn, {@code until} of the state
         * above zero ends it.
         *
         * @param initial       the state before the first turn; a float or vector type
         * @param maxIterations how many turns at most, 1 to {@link #MAX_ITERATIONS}
         * @param next          the state after one turn, given the state
         * @param until         a float above zero where the loop ends, given the state
         * @return the loop, whose value is the state when it ends
         * @throws IllegalArgumentException as the constructor
         */
        public static Loop of(Expr initial, int maxIterations,
                              java.util.function.Function<? super Expr, ? extends Expr> next,
                              java.util.function.Function<? super Expr, ? extends Expr> until) {
            java.util.Objects.requireNonNull(next, "next");
            java.util.Objects.requireNonNull(until, "until");
            return build(List.of(initial), maxIterations, state -> List.of(next.apply(state.get(0))),
                    state -> until.apply(state.get(0)));
        }

        /**
         * A loop of several values that takes every turn, in every pixel.
         *
         * @param initial       each value before the first turn; floats or vectors, one or more
         * @param maxIterations how many turns, 1 to {@link #MAX_ITERATIONS}
         * @param next          the values after one turn, in the same order, given the state's
         * @return the loop, whose first value it is; {@link #value(int)} reads the others
         * @throws IllegalArgumentException as the constructor
         */
        public static Loop of(List<? extends Expr> initial, int maxIterations,
                              java.util.function.Function<? super List<Expr>, ? extends List<? extends Expr>> next) {
            return build(initial, maxIterations, next, null);
        }

        /**
         * A loop of several values that may end early: before each turn, {@code until} of the
         * state above zero ends it.
         *
         * @param initial       each value before the first turn; floats or vectors, one or more
         * @param maxIterations how many turns at most, 1 to {@link #MAX_ITERATIONS}
         * @param next          the values after one turn, in the same order, given the state's
         * @param until         a float above zero where the loop ends, given the state's values
         * @return the loop, whose first value it is; {@link #value(int)} reads the others
         * @throws IllegalArgumentException as the constructor
         */
        public static Loop of(List<? extends Expr> initial, int maxIterations,
                              java.util.function.Function<? super List<Expr>, ? extends List<? extends Expr>> next,
                              java.util.function.Function<? super List<Expr>, ? extends Expr> until) {
            return build(initial, maxIterations, next, java.util.Objects.requireNonNull(until, "until"));
        }

        /**
         * Hands the turn the state under names no other loop has, then names it for good by how
         * many loops nest in the turn: one more than the most any loop in it holds. A loop in
         * another's turn so has names of its own, whatever thread or order the two were built in,
         * and a method that builds a loop builds an equal one each time, which the generator then
         * computes once.
         */
        private static Loop build(List<? extends Expr> initial, int maxIterations,
                                  java.util.function.Function<? super List<Expr>, ? extends List<? extends Expr>> next,
                                  java.util.function.@org.jspecify.annotations.Nullable Function<? super List<Expr>, ? extends Expr> until) {
            java.util.Objects.requireNonNull(next, "next");
            List<Expr> start = List.copyOf(initial);
            String building = Loops.BUILDING_PREFIX + BUILDING.getAndIncrement() + "_";
            List<Expr> state = new java.util.ArrayList<>();
            for (int i = 0; i < start.size(); i++) {
                state.add(new Ref(building + i, start.get(i).type()));
            }
            List<Expr> handed = List.copyOf(state);
            List<? extends Expr> returned = next.apply(handed);
            if (returned == null) {
                throw new IllegalArgumentException("next gave no state");
            }
            List<Expr> after = List.copyOf(returned);
            Expr end = until == null ? null : until.apply(handed);
            List<Expr> turn = new java.util.ArrayList<>(after);
            if (end != null) {
                turn.add(end);
            }
            int level = Loops.level(turn);
            List<String> names = new java.util.ArrayList<>();
            java.util.Map<String, String> renamed = new java.util.HashMap<>();
            for (int i = 0; i < start.size(); i++) {
                names.add(Loops.stateName(level, i));
                renamed.put(building + i, names.get(i));
            }
            java.util.Map<Expr, Expr> done = new java.util.IdentityHashMap<>();
            List<Expr> stepped = new java.util.ArrayList<>();
            for (Expr value : after) {
                stepped.add(Loops.rename(value, renamed, done));
            }
            return new Loop(names, start, maxIterations, stepped, end == null ? null : Loops.rename(end, renamed, done), 0);
        }

        /**
         * Checks the loop as the class describes.
         *
         * @throws IllegalArgumentException if the count, the turns with the loops it holds, a type
         *                                  (each value's wherever it is read included), the number
         *                                  of values, a name or the index is out of these bounds,
         *                                  or a loop that can end early samples or takes a
         *                                  derivative
         */
        public Loop {
            states = List.copyOf(states);
            initial = List.copyOf(initial);
            next = List.copyOf(next);
            if (states.isEmpty()) {
                throw new IllegalArgumentException("a loop carries one value or more");
            }
            if (initial.size() != states.size() || next.size() != states.size()) {
                throw new IllegalArgumentException("a loop of " + states.size() + " values starts from "
                        + initial.size() + " and turns into " + next.size());
            }
            if (index < 0 || index >= states.size()) {
                throw new IllegalArgumentException("a loop of " + states.size() + " values has no value " + index);
            }
            if (new java.util.HashSet<>(states).size() != states.size()) {
                throw new IllegalArgumentException("two values of one loop are named alike: " + states);
            }
            java.util.Map<String, ShaderType> types = new java.util.HashMap<>();
            for (int i = 0; i < states.size(); i++) {
                String state = states.get(i);
                if (!Loops.isStateName(state)) {
                    throw new IllegalArgumentException("a loop's state is named by Loop.of, " + Loops.STATE_PREFIX
                            + ", its depth, _ and its place, not \"" + state + "\"");
                }
                ShaderType type = initial.get(i).type();
                if (type.components == 0) {
                    throw new IllegalArgumentException("a loop carries floats and vectors, not " + type);
                }
                if (next.get(i).type() != type) {
                    throw new IllegalArgumentException("value " + i + " of the next state is a " + next.get(i).type()
                            + " where the state's is a " + type);
                }
                types.put(state, type);
            }
            if (until != null && until.type() != ShaderType.FLOAT) {
                throw new IllegalArgumentException("until is a float, above zero to end, not a " + until.type());
            }
            if (maxIterations < 1 || maxIterations > MAX_ITERATIONS) {
                throw new IllegalArgumentException("a loop takes 1 to " + MAX_ITERATIONS + " turns, not "
                        + maxIterations);
            }
            // A loop rebuilt with a free name renamed passed these when it was built.
            if (!Loops.RENAMING.get()) {
                checkNest(states, types, initial, maxIterations, next, until);
            }
        }

        /** The checks that walk what the loop holds: types read, turns, and what an early end allows. */
        private static void checkNest(List<String> states, java.util.Map<String, ShaderType> types, List<Expr> initial,
                                      int maxIterations, List<Expr> next,
                                      @org.jspecify.annotations.Nullable Expr until) {
            List<Expr> turn = new java.util.ArrayList<>(next);
            if (until != null) {
                turn.add(until);
            }
            String misread = Loops.misread(turn, types);
            if (misread != null) {
                throw new IllegalArgumentException(misread);
            }
            long turns = Loops.turns(initial, maxIterations, turn, states);
            if (turns > MAX_ITERATIONS) {
                throw new IllegalArgumentException("this loop takes up to " + turns + " turns a pixel, with"
                        + " the loops it holds, more than " + MAX_ITERATIONS);
            }
            if (until != null) {
                for (Expr part : turn) {
                    String undefined = Loops.undefinedEarly(part);
                    if (undefined != null) {
                        throw new IllegalArgumentException("a loop that can end early cannot hold " + undefined
                                + ": it is undefined where neighbouring pixels take different turns");
                    }
                }
            }
        }

        /**
         * Value {@code index} of this loop's state when it ends: the same loop, computed once
         * however many of its values are read.
         *
         * @param index the value's place in the state, from 0
         * @return the node that reads it
         * @throws IllegalArgumentException if the loop has no such value
         */
        public Loop value(int index) {
            return index == this.index ? this : new Loop(states, initial, maxIterations, next, until, index);
        }

        @Override
        public ShaderType type() {
            return initial.get(index).type();
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

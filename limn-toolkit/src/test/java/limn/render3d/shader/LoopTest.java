package limn.render3d.shader;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A loop in the shader IR is bounded: with the loops it holds it may not ask a fragment for more
 * than {@link Expr.Loop#MAX_ITERATIONS} turns, however it nests them ({@link SurfaceBudgetTest}
 * holds a whole surface to the same cap). Its state is a node {@link Expr.Loop#of} hands its turn,
 * of the initial value's type wherever it is read. And a loop that can end early runs in
 * non-uniform control flow, where neither GLSL 3.30 nor GLSL ES 3.00 defines a derivative or an
 * implicit-LOD texture lookup.
 */
class LoopTest {

    private static final Expr.Lit ORIGIN = Expr.Lit.of(0f, 0f);

    private static Expr add(Expr a, Expr b) {
        return new Expr.Binary(Expr.Op.ADD, a, b);
    }

    /** z + 1: a turn that reads the state. */
    private static Expr step(Expr state) {
        return add(state, Expr.Lit.of(1f, 1f));
    }

    /** z.x - 4: ends the loop once z.x passes 4. */
    private static Expr past4(Expr state) {
        return new Expr.Binary(Expr.Op.SUB, new Expr.Swizzle(state, "x"), Expr.Lit.of(4f));
    }

    private static Expr.Loop loop(int turns, UnaryOperator<Expr> next) {
        return Expr.Loop.of(ORIGIN, turns, next);
    }

    @Test
    void aLoopIsAValueOfItsStatesType() {
        Expr.Loop loop = loop(8, LoopTest::step);
        assertEquals(ShaderType.VEC2, loop.type());
    }

    @Test
    void theTurnIsHandedItsStateAsANodeOfTheInitialValuesType() {
        Expr[] handed = new Expr[2];
        Expr.Loop loop = Expr.Loop.of(Expr.Lit.of(0f, 0f, 0f), 8, s -> {
            handed[0] = s;
            return s;
        }, s -> {
            handed[1] = s;
            return new Expr.Swizzle(s, "x");
        });
        assertEquals(ShaderType.VEC3, handed[0].type());
        assertEquals(handed[0], handed[1], "next and until read the same state");
        // In the loop, what the turn returned reads the state under the loop's own name.
        assertEquals(new Expr.Ref(loop.states().get(0), ShaderType.VEC3), loop.next().get(0));
        assertEquals(new Expr.Swizzle(loop.next().get(0), "x"), loop.until());
        assertTrue(loop.states().get(0).startsWith("ir_"), "a name no input may take: " + loop.states());
    }

    @Test
    void aLoopBuiltTwiceAlikeIsOneValue() {
        // A Java method that builds a loop builds an equal one each time, which the generator
        // computes once.
        assertEquals(loop(8, LoopTest::step), loop(8, LoopTest::step));
    }

    @Test
    void aLoopBuiltInsideAnothersTurnReadsBothStates() {
        Expr.Loop nested = loop(4, z -> add(z, loop(4, w -> add(w, z))));
        Expr.Loop inner = (Expr.Loop) ((Expr.Binary) nested.next().get(0)).right();
        assertNotEquals(nested.states(), inner.states(), "the inner state has a name of its own");
        assertEquals(new Expr.Ref(nested.states().get(0), ShaderType.VEC2),
                ((Expr.Binary) inner.next().get(0)).right(),
                "and the inner turn reads the outer state by the outer name");
    }

    @Test
    void theCapIsAThousandAndTwentyFourTurns() {
        assertEquals(1024, Expr.Loop.MAX_ITERATIONS);
        assertDoesNotThrow(() -> loop(1, LoopTest::step));
        assertDoesNotThrow(() -> loop(1024, LoopTest::step));
        assertThrows(IllegalArgumentException.class, () -> loop(0, LoopTest::step));
        assertThrows(IllegalArgumentException.class, () -> loop(1025, LoopTest::step));
    }

    @Test
    void aNestingIsCappedByTheProductOfItsCounts() {
        // Inner loops that start from the outer state: a turn's work.
        assertDoesNotThrow(() -> loop(32, z -> add(z, Expr.Loop.of(z, 32, LoopTest::step))));
        assertThrows(IllegalArgumentException.class, () -> loop(32, z -> add(z, Expr.Loop.of(z, 64, LoopTest::step))),
                "32 turns of 64 turns is 2048 turns a fragment");
        // A loop in the initial value runs once, before the outer one: it does not multiply.
        assertDoesNotThrow(() -> Expr.Loop.of(loop(64, LoopTest::step), 16, LoopTest::step));
        // A loop in until, a turn's work like next, does multiply.
        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(ORIGIN, 32, LoopTest::step,
                z -> new Expr.Swizzle(Expr.Loop.of(z, 64, LoopTest::step), "x")));
    }

    @Test
    void theNextStateIsOfTheStatesTypeAndUntilIsAFloat() {
        assertThrows(IllegalArgumentException.class,
                () -> loop(8, z -> Expr.Lit.of(1f, 1f, 1f)), "a vec2 state cannot become a vec3");
        assertThrows(IllegalArgumentException.class,
                () -> Expr.Loop.of(ORIGIN, 8, LoopTest::step, z -> Expr.Lit.of(1f, 1f)));
        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(
                new Expr.Ref("u_tex", ShaderType.SAMPLER2D), 8, s -> s), "a sampler is not a value a loop can carry");
    }

    @Test
    void theStateIsReadAsItsOwnTypeWhereverItIsRead() {
        // A reference of the state's name with another type, built by hand: the generator would
        // write vec4((ir_s0 + vec2(1.0)), ...) where ir_s0 is a vec4, which no profile compiles.
        Expr.Loop four = Expr.Loop.of(Expr.Lit.of(0f, 0f, 0f, 0f), 4, s -> s);
        Expr.Ref misread = new Expr.Ref(four.states().get(0), ShaderType.VEC2);
        Expr next = new Expr.Construct(ShaderType.VEC4, List.of(step(misread), ORIGIN));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new Expr.Loop(four.states(), four.initial(), 4, List.of(next), null, 0));
        assertTrue(refused.getMessage().contains("VEC2"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Expr.Loop(four.states(), four.initial(), 4,
                four.initial(), new Expr.Swizzle(misread, "x"), 0), "in until too");
    }

    @Test
    void theStatesNameIsTheOneTheFactoryGives() {
        for (String bad : List.of("z", "s", "v_uv", "ir_z", "ir_state", "ir_state0", "ir_state01_0",
                "ir_state0_01", "gl_z")) {
            assertThrows(IllegalArgumentException.class, () -> new Expr.Loop(List.of(bad), List.of(ORIGIN), 8,
                    List.of(step(new Expr.Ref(bad, ShaderType.VEC2))), null, 0), bad);
        }
        assertDoesNotThrow(() -> new Expr.Loop(List.of("ir_state3_0"), List.of(ORIGIN), 8,
                List.of(step(new Expr.Ref("ir_state3_0", ShaderType.VEC2))), null, 0));
    }

    @Test
    void aLoopBuiltApartAndSetInAnothersTurnHasNamesOfItsOwn() {
        // A loop's names follow how many loops it holds, not where or when it was built: the outer
        // one holds one more than any loop in its turn.
        Expr.Loop apart = loop(4, LoopTest::step);
        Expr.Loop outer = assertDoesNotThrow(() -> loop(8, z -> add(z, apart)));
        assertNotEquals(apart.states(), outer.states());
    }

    @Test
    void aLoopBuiltForAnothersTurnOnAnotherThreadStillReadsTheOuterState() throws Exception {
        // Built inside the outer turn but on a worker thread: t + s must stay t + s, not become t + t.
        Expr.Loop outer = Expr.Loop.of(Expr.Lit.of(1f), 3, s -> add(s,
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> Expr.Loop.of(Expr.Lit.of(0f), 2, t -> add(t, s))).join()));
        Expr.Loop inner = (Expr.Loop) ((Expr.Binary) outer.next().get(0)).right();
        assertNotEquals(outer.states(), inner.states());
        Expr.Binary turn = (Expr.Binary) inner.next().get(0);
        assertEquals(new Expr.Ref(inner.states().get(0), ShaderType.FLOAT), turn.left());
        assertEquals(new Expr.Ref(outer.states().get(0), ShaderType.FLOAT), turn.right(),
                "the inner turn reads the outer state");
        // The same loops built on one thread are the same value.
        Expr.Loop here = Expr.Loop.of(Expr.Lit.of(1f), 3, s -> add(s, Expr.Loop.of(Expr.Lit.of(0f), 2, t -> add(t, s))));
        assertEquals(here, outer);
    }

    @Test
    void aLoopThatCanEndEarlyTakesNoSampleAndNoDerivative() {
        UnaryOperator<Expr> sampled = z -> add(z, new Expr.Swizzle(new Expr.Sample(
                new Expr.Ref("u_tex", ShaderType.SAMPLER2D), z), "xy"));
        UnaryOperator<Expr> derivative = z -> add(z, new Expr.Call("dFdx", ShaderType.VEC2, List.of(z)));

        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(ORIGIN, 8, sampled, LoopTest::past4));
        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(ORIGIN, 8, derivative, LoopTest::past4));
        for (String name : List.of("dFdy", "fwidth")) {
            assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(ORIGIN, 8,
                    z -> add(z, new Expr.Call(name, ShaderType.VEC2, List.of(z))), LoopTest::past4));
        }
        // In until too, which is read on the same turns.
        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(ORIGIN, 8, LoopTest::step,
                z -> new Expr.Swizzle(new Expr.Sample(new Expr.Ref("u_tex", ShaderType.SAMPLER2D), z), "x")));
        // Every fragment runs every turn of a loop without until: uniform, so allowed.
        assertDoesNotThrow(() -> Expr.Loop.of(ORIGIN, 8, sampled));
        assertDoesNotThrow(() -> Expr.Loop.of(ORIGIN, 8, derivative));
    }

    @Test
    void aNodeSharedByEveryStepOfAChainIsTypedOnce() {
        // z ↦ z·z forty times, each product reading the one before twice: a type asked of each
        // child as a tree is 2^40 questions, and the loop's constructor asks its next state's type.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Expr z = new Expr.Ref("u_z", ShaderType.VEC2);
            for (int i = 0; i < 40; i++) {
                z = new Expr.Binary(Expr.Op.MUL, z, z);
            }
            assertEquals(ShaderType.VEC2, z.type());
            Expr chain = z;
            assertDoesNotThrow(() -> Expr.Loop.of(ORIGIN, 4, s -> add(s, chain),
                    s -> new Expr.Binary(Expr.Op.SUB, new Expr.Swizzle(chain, "x"), Expr.Lit.of(1f))));
        });
    }

    @Test
    void aLoopThatCanEndEarlyTakesNoLookupThatPicksItsOwnLevelOfDetail() {
        // The lookups that choose a level of detail from the pixel's neighbours are undefined where
        // the neighbours took other turns, whether the IR says Sample or names the function itself.
        Expr sheet = new Expr.Ref("u_tex", ShaderType.SAMPLER2D);
        for (String name : List.of("texture", "textureProj", "textureOffset", "textureProjOffset")) {
            UnaryOperator<Expr> lookup = z -> add(z, new Expr.Swizzle(
                    new Expr.Call(name, ShaderType.VEC4, List.of(sheet, z)), "xy"));
            assertThrows(IllegalArgumentException.class,
                    () -> Expr.Loop.of(ORIGIN, 8, lookup, LoopTest::past4), name);
            assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(ORIGIN, 8, LoopTest::step,
                    z -> new Expr.Swizzle(new Expr.Call(name, ShaderType.VEC4, List.of(sheet, z)), "x")),
                    name + " in until");
            assertDoesNotThrow(() -> Expr.Loop.of(ORIGIN, 8, lookup),
                    name + " where every pixel takes every turn");
        }
        // A lookup told its level, or reading one texel, needs no neighbour.
        for (String name : List.of("textureLod", "textureLodOffset", "textureProjLod", "textureGrad",
                "textureGradOffset", "textureProjGrad", "texelFetch", "textureSize")) {
            UnaryOperator<Expr> lookup = z -> add(z, new Expr.Swizzle(
                    new Expr.Call(name, ShaderType.VEC4, List.of(sheet, z)), "xy"));
            assertDoesNotThrow(() -> Expr.Loop.of(ORIGIN, 8, lookup, LoopTest::past4), name);
        }
    }

    @Test
    void aStateReadOutsideItsLoopIsRefusedByTheSurface() {
        // Kept from the turn it was handed to, the state is a name no loop binds where it is read.
        Expr[] kept = new Expr[1];
        Expr.Loop loop = loop(4, z -> {
            kept[0] = z;
            return step(z);
        });
        Expr outside = new Expr.Construct(ShaderType.VEC3, List.of(kept[0], Expr.Lit.of(0f)));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SurfaceOutputs.of(Expr.Lit.of(0f, 0f, 0f, 1f), Expr.Lit.of(0f), Expr.Lit.of(1f), outside));
        assertTrue(refused.getMessage().contains("outside its loop"), refused.getMessage());
        // In another loop's initial value it is outside its own loop too.
        Expr.Loop later = Expr.Loop.of(kept[0], 4, LoopTest::step);
        assertThrows(IllegalArgumentException.class, () -> SurfaceOutputs.of(Expr.Lit.of(0f, 0f, 0f, 1f),
                Expr.Lit.of(0f), Expr.Lit.of(1f), new Expr.Construct(ShaderType.VEC3, List.of(later, Expr.Lit.of(0f)))));
        // Read where its loop binds it, it is the state.
        assertDoesNotThrow(() -> SurfaceOutputs.of(Expr.Lit.of(0f, 0f, 0f, 1f), Expr.Lit.of(0f), Expr.Lit.of(1f),
                new Expr.Construct(ShaderType.VEC3, List.of(loop, Expr.Lit.of(0f)))));
    }

    // ---------------------------------------------------------------- several values

    /** A colour gathered and a distance travelled: the shape of a ray marched through a volume. */
    private static Expr.Loop march(int turns) {
        return Expr.Loop.of(List.of(Expr.Lit.of(0f, 0f, 0f, 0f), Expr.Lit.of(0f)), turns,
                s -> List.of(add(s.get(0), Expr.Lit.of(0.1f, 0.1f, 0.1f, 0.1f)), add(s.get(1), Expr.Lit.of(0.05f))),
                s -> new Expr.Binary(Expr.Op.SUB, new Expr.Swizzle(s.get(0), "w"), Expr.Lit.of(0.99f)));
    }

    @Test
    void aLoopCarriesSeveralValuesEachHandedAsANodeOfItsType() {
        List<List<Expr>> handed = new java.util.ArrayList<>();
        Expr.Loop loop = Expr.Loop.of(List.of(Expr.Lit.of(0f, 0f, 0f, 0f), Expr.Lit.of(0f)), 8, s -> {
            handed.add(s);
            return s;
        });
        assertEquals(List.of(ShaderType.VEC4, ShaderType.FLOAT), handed.get(0).stream().map(Expr::type).toList());
        assertEquals(List.of(new Expr.Ref(loop.states().get(0), ShaderType.VEC4),
                new Expr.Ref(loop.states().get(1), ShaderType.FLOAT)), loop.next());
        assertNotEquals(loop.states().get(0), loop.states().get(1));
        // The node is the first value; value(1) reads the second, of the same loop.
        assertEquals(ShaderType.VEC4, loop.type());
        assertEquals(ShaderType.FLOAT, loop.value(1).type());
        assertEquals(1, loop.value(1).index());
        assertEquals(loop, loop.value(0));
        assertNotEquals(loop, loop.value(1), "two values of one loop are two values");
        assertEquals(march(8).value(1), march(8).value(1), "built twice alike, one value");
        assertThrows(IllegalArgumentException.class, () -> loop.value(2));
    }

    @Test
    void eachValueOfTheNextStateIsOfItsOwnValuesType() {
        List<Expr> start = List.of(Expr.Lit.of(0f, 0f, 0f, 0f), Expr.Lit.of(0f));
        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(start, 8, s -> List.of(s.get(0))),
                "two values in, one out");
        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(start, 8, s -> List.of(s.get(1), s.get(0))),
                "the colour cannot become the distance");
        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(List.of(), 8, s -> s), "no value at all");
        // A reference to the second value with the first one's type, built by hand.
        Expr.Loop loop = march(8);
        Expr.Ref misread = new Expr.Ref(loop.states().get(1), ShaderType.VEC4);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> new Expr.Loop(
                loop.states(), loop.initial(), 8, List.of(misread, loop.next().get(1)), null, 0));
        assertTrue(refused.getMessage().contains(loop.states().get(1)), refused.getMessage());
        // An early end is checked for every value's next.
        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(start, 8,
                s -> List.of(s.get(0), add(s.get(1), new Expr.Call("fwidth", ShaderType.FLOAT, List.of(s.get(1))))),
                s -> new Expr.Swizzle(s.get(0), "w")));
    }

    @Test
    void aSecondValueReadOutsideItsLoopIsRefusedByTheSurface() {
        List<Expr> kept = new java.util.ArrayList<>();
        Expr.Loop loop = Expr.Loop.of(List.of(Expr.Lit.of(0f, 0f, 0f, 0f), Expr.Lit.of(0f)), 4, s -> {
            kept.addAll(s);
            return s;
        });
        assertThrows(IllegalArgumentException.class, () -> SurfaceOutputs.of(Expr.Lit.of(0f, 0f, 0f, 1f),
                kept.get(1), Expr.Lit.of(1f), new Expr.Swizzle(loop, "xyz")));
        assertDoesNotThrow(() -> SurfaceOutputs.of(Expr.Lit.of(0f, 0f, 0f, 1f), loop.value(1), Expr.Lit.of(1f),
                new Expr.Swizzle(loop, "xyz")));
    }

    @Test
    void aGeneratorNameReadWhereNoLoopBindsItIsRefusedByTheSurface() {
        // ir_s0 is the variable the generator gives the first loop's state: read bare beside a
        // loop it would silently be that loop's value.
        Expr.Loop loop = loop(4, LoopTest::step);
        for (String name : List.of("ir_s0", "ir_t3", "ir_i0", "ir_anything")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> SurfaceOutputs.of(Expr.Lit.of(0f, 0f, 0f, 1f), new Expr.Ref(name, ShaderType.FLOAT),
                            Expr.Lit.of(1f), new Expr.Construct(ShaderType.VEC3, List.of(loop, Expr.Lit.of(0f)))));
            assertTrue(refused.getMessage().contains(name), refused.getMessage());
        }
        assertDoesNotThrow(() -> SurfaceOutputs.of(Expr.Lit.of(0f, 0f, 0f, 1f), new Expr.Ref("u_ir", ShaderType.FLOAT),
                Expr.Lit.of(1f), Expr.Lit.of(0f, 0f, 0f)));
    }
}

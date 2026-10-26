package limn.render3d.shader;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Expr.Loop#MAX_ITERATIONS} bounds every turn a pixel takes across a whole surface: loops
 * side by side or chained through an initial value add, a loop in another's turn multiplies, and a
 * loop computed once is counted once.
 */
class SurfaceBudgetTest {

    private static Expr add(Expr a, Expr b) {
        return new Expr.Binary(Expr.Op.ADD, a, b);
    }

    /** A loop of {@code turns} that adds {@code step} to a float each turn, from {@code initial}. */
    private static Expr.Loop counting(Expr initial, int turns, float step) {
        return Expr.Loop.of(initial, turns, f -> add(f, Expr.Lit.of(step)));
    }

    private static SurfaceOutputs emitting(Expr... floats) {
        List<Expr> parts = new ArrayList<>(List.of(floats));
        while (parts.size() < 3) {
            parts.add(Expr.Lit.of(0f));
        }
        return SurfaceOutputs.of(Expr.Lit.of(0f, 0f, 0f, 1f), Expr.Lit.of(0f), Expr.Lit.of(1f),
                new Expr.Construct(ShaderType.VEC3, parts));
    }

    @Test
    void loopsChainedThroughTheirInitialValuesAddTheirTurns() {
        // 64 loops of 1024, each starting where the last ended: 65,536 turns a pixel.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> {
            Expr chained = Expr.Lit.of(0f);
            for (int i = 0; i < 64; i++) {
                chained = counting(chained, 1024, 0.001f);
            }
        });
        assertTrue(refused.getMessage().contains("2048"), refused.getMessage());
        // Two of 512 fit exactly.
        assertDoesNotThrow(() -> counting(counting(Expr.Lit.of(0f), 512, 0.001f), 512, 0.001f));
    }

    @Test
    void loopsSideBySideInOneTurnAddAndTheTurnMultipliesThem() {
        // An outer loop of 8 whose turn holds 8 loops of 128: 8 × 8 × 128 = 8,192 turns a pixel.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Expr.Loop.of(Expr.Lit.of(0f), 8, g -> eightLoops(g, 128)));
        assertTrue(refused.getMessage().contains("8192"), refused.getMessage());
        // Eight of 16 in a turn of 8 are 1,024: the cap, accepted.
        assertDoesNotThrow(() -> Expr.Loop.of(Expr.Lit.of(0f), 8, g -> eightLoops(g, 16)));
    }

    /** {@code g} plus eight loops side by side, each of {@code turns} from {@code g}. */
    private static Expr eightLoops(Expr g, int turns) {
        Expr sum = g;
        for (int i = 0; i < 8; i++) {
            sum = add(sum, counting(g, turns, i + 1f));
        }
        return sum;
    }

    @Test
    void theOutputsOfASurfaceAddTheirLoops() {
        Expr.Loop one = counting(Expr.Lit.of(0f), 600, 0.001f);
        Expr.Loop other = counting(Expr.Lit.of(0f), 600, 0.002f);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> emitting(one, other));
        assertTrue(refused.getMessage().contains("1200"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SurfaceOutputs.of(
                new Expr.Construct(ShaderType.VEC4, List.of(one, one, one, one)), other, Expr.Lit.of(1f),
                Expr.Lit.of(0f, 0f, 0f)), "a loop in one output and another in the next");
    }

    @Test
    void aLoopComputedOnceIsCountedOnce() {
        Expr.Loop shared = counting(Expr.Lit.of(0f), 600, 0.001f);
        // Read by two outputs and twice in one: the generator writes it once.
        // Counted four times it would be 2,400.
        assertDoesNotThrow(() -> SurfaceOutputs.of(
                new Expr.Construct(ShaderType.VEC4, List.of(shared, shared, Expr.Lit.of(0f), Expr.Lit.of(1f))),
                shared, Expr.Lit.of(1f), new Expr.Construct(ShaderType.VEC3, List.of(shared))));
        // Built twice alike, it is the same computation, also written once.
        assertDoesNotThrow(() -> emitting(shared, counting(Expr.Lit.of(0f), 600, 0.001f)));
        // With one more loop of 600 beside it, it is still counted once: 1,200.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> emitting(shared, shared, counting(Expr.Lit.of(0f), 600, 0.002f)));
        assertTrue(refused.getMessage().contains("1200"), refused.getMessage());
    }

    @Test
    void anEscapeTimeFractalOf256TurnsFits() {
        Expr.Loop loop = Expr.Loop.of(Expr.Lit.of(0f, 0f, 0f, 0f), 256,
                s -> new Expr.Construct(ShaderType.VEC4, List.of(new Expr.Swizzle(s, "xy"),
                        add(new Expr.Swizzle(s, "z"), Expr.Lit.of(1f)), Expr.Lit.of(0f))),
                s -> new Expr.Binary(Expr.Op.SUB, new Expr.Call("dot", ShaderType.FLOAT,
                        List.of(new Expr.Swizzle(s, "xy"), new Expr.Swizzle(s, "xy"))), Expr.Lit.of(256f)));
        assertDoesNotThrow(() -> emitting(new Expr.Swizzle(loop, "z")));
    }

    @Test
    void theDiffuseResponseTakesItsTurnsOnceForEachOfTheEightLightsAPassShades() {
        // A pass shades with up to eight lights, and the response runs for each: 200 + 8 × 100.
        Expr.Loop response = counting(Expr.Lit.of(0f), 100, 0.01f);
        assertDoesNotThrow(() -> emitting(counting(Expr.Lit.of(0f), 200, 0.001f)).withDiffuseResponse(response));
        assertDoesNotThrow(() -> emitting().withDiffuseResponse(counting(Expr.Lit.of(0f), 128, 0.01f)));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> emitting().withDiffuseResponse(counting(Expr.Lit.of(0f), 129, 0.01f)));
        assertTrue(refused.getMessage().contains("1032"), refused.getMessage());
        // Read by an output too, it is computed once, before the lights: 600, not 600 + 8 × 600.
        Expr.Loop shared = counting(Expr.Lit.of(0f), 600, 0.01f);
        assertDoesNotThrow(() -> emitting(shared).withDiffuseResponse(shared));
    }

    @Test
    void aLoopReadForSeveralOfItsValuesIsCountedOnce() {
        Expr.Loop two = Expr.Loop.of(List.of(Expr.Lit.of(0f), Expr.Lit.of(1f)), 600,
                s -> List.of(add(s.get(0), Expr.Lit.of(0.001f)), add(s.get(1), Expr.Lit.of(0.002f))));
        // Its first value in one output and its second in another: one loop of 600.
        assertDoesNotThrow(() -> emitting(two, two.value(1)));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> emitting(two, two.value(1), counting(Expr.Lit.of(0f), 600, 0.003f)));
        assertTrue(refused.getMessage().contains("1200"), refused.getMessage());
    }

    @Test
    void eachOfAThousandLoopsChainedThroughTheirInitialValuesWalksTheChainBelowItAFixedNumberOfTimes() {
        // Each constructor counted the whole chain below it afresh and hashed every loop in it as a
        // tree: the i-th loop walked some 4·i² nodes, cubic in the chain, 2.7 s at 400 and some 40 s
        // at a thousand. Numbered once a count, it walks twelve nodes for each link below it,
        // 12·i + 1, and the surface over the thousand walks 14,015. Nodes walked are the same number
        // on every machine, where the two seconds this was held to failed on a loaded one (ADR 062);
        // the old count fails at the fourth loop.
        Expr chain = Expr.Lit.of(0f);
        for (int i = 1; i <= 1000; i++) {
            Expr below = chain;
            Expr[] link = new Expr[1];
            long walked = Loops.walked(() -> link[0] = counting(below, 1, 1f));
            assertTrue(walked <= 14L * i, "loop " + i + " of the chain walked " + walked + " nodes, more than 14 for"
                    + " each link it ends");
            chain = link[0];
        }
        Expr built = chain;
        long surface = Loops.walked(() -> assertDoesNotThrow(() -> emitting(built)));
        assertTrue(surface <= 16_000, "the surface walked " + surface + " nodes over the thousand");
        // The thousand fit the budget exactly.
        assertThrows(IllegalArgumentException.class, () -> emitting(built, counting(Expr.Lit.of(0f), 25, 1f)),
                "1,025 turns");
    }

    @Test
    void aLoopInATurnThatDoesNotReadTheStateIsCountedOnceAsItIsComputedOnce() {
        // Reading nothing of the outer state, the inner loop is computed once, before the outer
        // one: 512 + 4, not 4 × 512.
        Expr.Loop invariant = counting(Expr.Lit.of(0f), 512, 1f);
        assertDoesNotThrow(() -> Expr.Loop.of(Expr.Lit.of(0f), 4, s -> add(s, invariant)));
        // An equal loop in the initial value and in until is written once: 300 + 3.
        Expr.Loop x = counting(Expr.Lit.of(0f), 300, 1f);
        assertDoesNotThrow(() -> Expr.Loop.of(x, 3, s -> add(s, Expr.Lit.of(1f)),
                s -> new Expr.Binary(Expr.Op.SUB, s, x)));
        // Reading the state, it is a turn's work: 4 × 512 is refused.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Expr.Loop.of(Expr.Lit.of(0f), 4, s -> add(s, counting(s, 512, 1f))));
        assertTrue(refused.getMessage().contains("2048"), refused.getMessage());
        // Through a loop nested deeper that reads it, too.
        assertThrows(IllegalArgumentException.class, () -> Expr.Loop.of(Expr.Lit.of(0f), 4,
                s -> add(s, Expr.Loop.of(Expr.Lit.of(0f), 2, t -> add(t, counting(s, 256, 1f))))));
    }

    @Test
    void theBudgetCountsALoopWhereTheGeneratorPlacesItAcrossSeveralLevels() {
        Expr.Loop w = counting(Expr.Lit.of(0f), 512, 1f);
        // W reads nothing of V or O: written once, before O; V runs 4 × 4. 512 + 16.
        Expr.Loop d1 = assertDoesNotThrow(() -> Expr.Loop.of(Expr.Lit.of(0f), 4, s -> add(s,
                Expr.Loop.of(Expr.Lit.of(0f), 4, v -> add(add(v, s), w)))));
        assertEquals(528, Loops.surfaceTurns(List.of(d1), null, 8));
        // W as V's initial value: the same.
        Expr.Loop d2 = assertDoesNotThrow(() -> Expr.Loop.of(Expr.Lit.of(0f), 4, s -> add(s, Expr.Loop.of(w, 4, v -> add(v, s)))));
        assertEquals(528, Loops.surfaceTurns(List.of(d2), null, 8));
        // W an output, read again in a response loop: written once at the top, read by the light
        // loop. 120 + 8 × 4.
        Expr.Loop w120 = counting(Expr.Lit.of(0f), 120, 1f);
        Expr.Loop response = Expr.Loop.of(Expr.Lit.of(0f), 4, s -> add(s, w120));
        assertEquals(152, Loops.surfaceTurns(List.of(w120), response, 8));
        assertDoesNotThrow(() -> emitting(w120).withDiffuseResponse(response));
        // X read directly and as A's initial value: one loop of 600 and A's 2.
        Expr.Loop x = counting(Expr.Lit.of(0f), 600, 1f);
        Expr.Loop a = Expr.Loop.of(x, 2, s -> add(s, Expr.Lit.of(1f)));
        assertEquals(602, Loops.surfaceTurns(List.of(add(x, a)), null, 8));
        assertDoesNotThrow(() -> emitting(add(x, a)));
        // A loop that reads the state of the loop around it still runs once a turn of it.
        Expr.Loop d3 = Expr.Loop.of(Expr.Lit.of(0f), 4, s -> add(s, Expr.Loop.of(Expr.Lit.of(0f), 4,
                v -> add(v, counting(s, 60, 1f)))));
        assertEquals(4 * (60 + 4), Loops.surfaceTurns(List.of(d3), null, 8));
    }

    @Test
    void anEqualLoopInTheTurnsOfTwoSiblingLoopsIsCountedInEach() {
        // One helper called in two turns: the inner loops are equal values, yet each reads its own
        // loop's state, so each is written and run in its own turn. 4 × 8 twice.
        java.util.function.Function<Expr, Expr.Loop> helper = s -> counting(s, 8, 1f);
        Expr.Loop first = Expr.Loop.of(Expr.Lit.of(0f), 4, s -> add(s, helper.apply(s)));
        Expr.Loop second = Expr.Loop.of(Expr.Lit.of(5f), 4, s -> add(s, helper.apply(s)));
        assertEquals(64, Loops.surfaceTurns(List.of(add(first, second)), null, 8));
        // One of them in the diffuse response: 4 × 8, and 8 lights × 4 × 8.
        assertEquals(288, Loops.surfaceTurns(List.of(first), second, 8));
    }

    /**
     * {@code k} loops of one turn, each built in the turn of the one around it, the innermost
     * reading every state of a nest {@code depth} deep, each held as it is built to sixteen nodes
     * walked for each loop under it and each state the innermost adds up.
     */
    private static Expr.Loop nest(int k, List<Expr> outer, int depth) {
        long[] under = {0};
        Expr.Loop[] loop = new Expr.Loop[1];
        long walked = Loops.walked(() -> loop[0] = Expr.Loop.of(Expr.Lit.of(0f), 1, s -> {
            List<Expr> all = new ArrayList<>(outer);
            all.add(s);
            if (k == 1) {
                Expr sum = s;
                for (Expr o : outer) {
                    sum = add(sum, o);
                }
                return sum;
            }
            Expr.Loop[] inner = new Expr.Loop[1];
            under[0] = Loops.walked(() -> inner[0] = nest(k - 1, all, depth));
            return add(s, inner[0]);
        }));
        long own = walked - under[0];
        assertTrue(own <= 16L * (k - 1 + depth), "the loop " + k + " from the innermost walked " + own
                + " nodes of its own, over " + (k - 1) + " loops and " + depth + " states");
        return loop[0];
    }

    @Test
    void eachLoopOfANestTwoHundredDeepWalksWhatItHoldsAFixedNumberOfTimes() {
        // Naming each loop rebuilt every loop under it through the constructor, which checked each
        // again: the k-th from the innermost walked some 6·k·(k + depth) nodes, the whole nest five
        // times the cube of its depth, 11.8 s at 200. Rebuilt without the checks it passed, a loop
        // walks some 10·depth + 17·k, at most 13.5 nodes for each loop under it and each state the
        // innermost adds up, and the surface 3,804. Counted, not timed (ADR 062); the old count
        // fails at the third loop.
        Expr.Loop deep = nest(200, List.of(), 200);
        assertEquals(1, Loops.surfaceTurns(List.of(deep), null, 8));
        long surface = Loops.walked(() -> assertDoesNotThrow(() -> emitting(deep)));
        assertTrue(surface <= 4_350, "the surface walked " + surface + " nodes over the nest");
    }
}

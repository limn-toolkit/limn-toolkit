package limn.render3d.shader;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
    void aThousandLoopsOfOneTurnChainedThroughTheirInitialValuesAreCountedQuickly() {
        // Each constructor counted the whole chain below it afresh and hashed every loop in it as a
        // tree: cubic in the chain, 2.7 s at 400, some 40 s at a thousand. Numbered once a count,
        // the thousand take about a third of a second; the bound leaves room for a loaded machine.
        // The thousand fit the budget exactly.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Expr chain = Expr.Lit.of(0f);
            for (int i = 0; i < 1000; i++) {
                chain = counting(chain, 1, 1f);
            }
            Expr built = chain;
            assertDoesNotThrow(() -> emitting(built));
            assertThrows(IllegalArgumentException.class, () -> emitting(built, counting(Expr.Lit.of(0f), 25, 1f)),
                    "1,025 turns");
        });
    }
}

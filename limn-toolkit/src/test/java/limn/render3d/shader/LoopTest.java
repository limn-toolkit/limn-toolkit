package limn.render3d.shader;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * A loop in the shader IR is bounded: with the loops it holds it may not ask a fragment for more
 * than {@link Expr.Loop#MAX_ITERATIONS} turns, however it nests them ({@link SurfaceBudgetTest}
 * holds a whole surface to the same cap). And a
 * loop that can end early runs in non-uniform control flow, where neither GLSL 3.30 nor GLSL ES
 * 3.00 defines a derivative or an implicit-LOD texture lookup.
 */
class LoopTest {

    private static final Expr.Ref Z = new Expr.Ref("z", ShaderType.VEC2);

    /** z + 1: a turn that reads the state. */
    private static Expr step(Expr.Ref state) {
        return new Expr.Binary(Expr.Op.ADD, state, Expr.Lit.of(1f, 1f));
    }

    private static Expr.Loop loop(String name, int turns, Expr next) {
        return new Expr.Loop(name, Expr.Lit.of(0f, 0f), turns, next, null);
    }

    @Test
    void aLoopIsAValueOfItsStatesType() {
        Expr.Loop loop = loop("z", 8, step(Z));
        assertEquals(ShaderType.VEC2, loop.type());
    }

    @Test
    void theCapIsAThousandAndTwentyFourTurns() {
        assertEquals(1024, Expr.Loop.MAX_ITERATIONS);
        assertDoesNotThrow(() -> loop("z", 1, step(Z)));
        assertDoesNotThrow(() -> loop("z", 1024, step(Z)));
        assertThrows(IllegalArgumentException.class, () -> loop("z", 0, step(Z)));
        assertThrows(IllegalArgumentException.class, () -> loop("z", 1025, step(Z)));
    }

    @Test
    void aNestingIsCappedByTheProductOfItsCounts() {
        Expr.Ref w = new Expr.Ref("w", ShaderType.VEC2);
        Expr.Loop inner32 = loop("w", 32, step(w));
        assertDoesNotThrow(() -> loop("z", 32, new Expr.Binary(Expr.Op.ADD, Z, inner32)));
        Expr.Loop inner64 = loop("w", 64, step(w));
        assertThrows(IllegalArgumentException.class,
                () -> loop("z", 32, new Expr.Binary(Expr.Op.ADD, Z, inner64)),
                "32 turns of 64 turns is 2048 turns a fragment");
        // A loop in the initial value runs once, before the outer one: it does not multiply.
        assertDoesNotThrow(() -> new Expr.Loop("z", inner64, 16, step(Z), null));
        // A loop in until, a turn's work like next, does multiply.
        Expr.Ref v = new Expr.Ref("v", ShaderType.FLOAT);
        Expr.Loop innerUntil = new Expr.Loop("v", Expr.Lit.of(0f), 64,
                new Expr.Binary(Expr.Op.ADD, v, Expr.Lit.of(1f)), null);
        assertThrows(IllegalArgumentException.class,
                () -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 32, step(Z), innerUntil));
    }

    @Test
    void theNextStateIsOfTheStatesTypeAndUntilIsAFloat() {
        assertThrows(IllegalArgumentException.class,
                () -> loop("z", 8, Expr.Lit.of(1f, 1f, 1f)), "a vec2 state cannot become a vec3");
        assertThrows(IllegalArgumentException.class,
                () -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, step(Z), Expr.Lit.of(1f, 1f)));
        assertThrows(IllegalArgumentException.class, () -> new Expr.Loop("s",
                new Expr.Ref("u_tex", ShaderType.SAMPLER2D), 8, new Expr.Ref("s", ShaderType.SAMPLER2D), null),
                "a sampler is not a value a loop can carry");
    }

    @Test
    void theStatesNameIsAnIdentifierEveryProfileAcceptsAndNoneOfTheGenerators() {
        for (String bad : List.of("", "2z", "z-1", "gl_z", "ir_z", "a__b", "z w")) {
            assertThrows(IllegalArgumentException.class, () -> loop(bad, 8, step(Z)), bad);
        }
        assertDoesNotThrow(() -> loop("zOld_2", 8, step(Z)));
    }

    @Test
    void aNestedLoopMayNotReuseAnEnclosingName() {
        Expr.Loop inner = loop("z", 4, step(Z));
        assertThrows(IllegalArgumentException.class,
                () -> loop("z", 4, new Expr.Binary(Expr.Op.ADD, Z, inner)),
                "one Ref(\"z\") would mean two values");
    }

    @Test
    void aLoopThatCanEndEarlyTakesNoSampleAndNoDerivative() {
        Expr until = new Expr.Binary(Expr.Op.SUB, new Expr.Swizzle(Z, "x"), Expr.Lit.of(4f));
        Expr sampled = new Expr.Binary(Expr.Op.ADD, Z, new Expr.Swizzle(new Expr.Sample(
                new Expr.Ref("u_tex", ShaderType.SAMPLER2D), Z), "xy"));
        Expr derivative = new Expr.Binary(Expr.Op.ADD, Z,
                new Expr.Call("dFdx", ShaderType.VEC2, List.of(Z)));

        assertThrows(IllegalArgumentException.class, () -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, sampled, until));
        assertThrows(IllegalArgumentException.class,
                () -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, derivative, until));
        for (String name : List.of("dFdy", "fwidth")) {
            Expr call = new Expr.Binary(Expr.Op.ADD, Z, new Expr.Call(name, ShaderType.VEC2, List.of(Z)));
            assertThrows(IllegalArgumentException.class, () -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, call, until));
        }
        // In until too, which is read on the same turns.
        Expr sampledUntil = new Expr.Swizzle(new Expr.Sample(
                new Expr.Ref("u_tex", ShaderType.SAMPLER2D), Z), "x");
        assertThrows(IllegalArgumentException.class,
                () -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, step(Z), sampledUntil));
        // Every fragment runs every turn of a loop without until: uniform, so allowed.
        assertDoesNotThrow(() -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, sampled, null));
        assertDoesNotThrow(() -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, derivative, null));
    }

    @Test
    void aNodeSharedByEveryStepOfAChainIsTypedOnce() {
        // z ↦ z·z forty times, each product reading the one before twice: a type asked of each
        // child as a tree is 2^40 questions, and the loop's constructor asks its next state's type.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Expr z = Z;
            for (int i = 0; i < 40; i++) {
                z = new Expr.Binary(Expr.Op.MUL, z, z);
            }
            assertEquals(ShaderType.VEC2, z.type());
            Expr chain = z;
            assertDoesNotThrow(() -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 4,
                    new Expr.Binary(Expr.Op.ADD, Z, chain),
                    new Expr.Binary(Expr.Op.SUB, new Expr.Swizzle(chain, "x"), Expr.Lit.of(1f))));
        });
    }

    @Test
    void aLoopThatCanEndEarlyTakesNoLookupThatPicksItsOwnLevelOfDetail() {
        // The lookups that choose a level of detail from the pixel's neighbours are undefined where
        // the neighbours took other turns, whether the IR says Sample or names the function itself.
        Expr until = new Expr.Binary(Expr.Op.SUB, new Expr.Swizzle(Z, "x"), Expr.Lit.of(4f));
        Expr sheet = new Expr.Ref("u_tex", ShaderType.SAMPLER2D);
        for (String name : List.of("texture", "textureProj", "textureOffset", "textureProjOffset")) {
            Expr lookup = new Expr.Binary(Expr.Op.ADD, Z, new Expr.Swizzle(
                    new Expr.Call(name, ShaderType.VEC4, List.of(sheet, Z)), "xy"));
            assertThrows(IllegalArgumentException.class,
                    () -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, lookup, until), name);
            assertThrows(IllegalArgumentException.class, () -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8,
                    step(Z), new Expr.Swizzle(new Expr.Call(name, ShaderType.VEC4, List.of(sheet, Z)), "x")),
                    name + " in until");
            assertDoesNotThrow(() -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, lookup, null),
                    name + " where every pixel takes every turn");
        }
        // A lookup told its level, or reading one texel, needs no neighbour.
        for (String name : List.of("textureLod", "textureLodOffset", "textureProjLod", "textureGrad",
                "textureGradOffset", "textureProjGrad", "texelFetch", "textureSize")) {
            Expr lookup = new Expr.Binary(Expr.Op.ADD, Z, new Expr.Swizzle(
                    new Expr.Call(name, ShaderType.VEC4, List.of(sheet, Z)), "xy"));
            assertDoesNotThrow(() -> new Expr.Loop("z", Expr.Lit.of(0f, 0f), 8, lookup, until), name);
        }
    }
}

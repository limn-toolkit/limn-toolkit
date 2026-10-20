package limn.render3d.shader;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shader IR is a value: two nodes built apart from equal parts are equal and hash alike, a
 * literal included, and a node shared by every step of a long chain is compared and hashed once.
 */
class ExprValueTest {

    private static Expr.Ref ref(String name) {
        return new Expr.Ref(name, ShaderType.VEC2);
    }

    /** z ↦ z·z {@code steps} times from {@code z}: each product reads the one before twice. */
    private static Expr squares(Expr z, int steps) {
        for (int i = 0; i < steps; i++) {
            z = new Expr.Binary(Expr.Op.MUL, z, z);
        }
        return z;
    }

    @Test
    void twoLiteralsOfTheSameValuesAreEqual() {
        assertEquals(Expr.Lit.of(1f, 2f), Expr.Lit.of(1f, 2f));
        assertEquals(Expr.Lit.of(1f, 2f).hashCode(), Expr.Lit.of(1f, 2f).hashCode());
        assertNotEquals(Expr.Lit.of(1f, 2f), Expr.Lit.of(1f, 3f));
        assertNotEquals(Expr.Lit.of(1f, 0f), Expr.Lit.of(1f, 0f, 0f), "a vec2 is not a vec3");
        assertTrue(Expr.Lit.of(1f, 2f).toString().contains("[1.0, 2.0]"), Expr.Lit.of(1f, 2f).toString());
    }

    @Test
    void nodesBuiltApartFromEqualPartsAreEqual() {
        // What a Java method that builds a sub-expression returns, called twice.
        Expr a = new Expr.Binary(Expr.Op.ADD, ref("v_uv"), Expr.Lit.of(0.5f, 0.5f));
        Expr b = new Expr.Binary(Expr.Op.ADD, ref("v_uv"), Expr.Lit.of(0.5f, 0.5f));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(new Expr.Call("fract", ShaderType.VEC2, List.of(a)),
                new Expr.Call("fract", ShaderType.VEC2, List.of(b)));
        assertEquals(Expr.Loop.of(Expr.Lit.of(0f, 0f), 8, s -> new Expr.Binary(Expr.Op.ADD, s, a)),
                Expr.Loop.of(Expr.Lit.of(0f, 0f), 8, s -> new Expr.Binary(Expr.Op.ADD, s, b)));
        assertNotEquals(Expr.Loop.of(Expr.Lit.of(0f, 0f), 8, s -> a), Expr.Loop.of(Expr.Lit.of(0f, 0f), 9, s -> a));
        assertNotEquals(Expr.Loop.of(Expr.Lit.of(0f, 0f), 8, s -> a),
                Expr.Loop.of(Expr.Lit.of(0f, 0f), 8, s -> a, s -> new Expr.Swizzle(s, "x")));
        assertNotEquals(a, new Expr.Binary(Expr.Op.SUB, ref("v_uv"), Expr.Lit.of(0.5f, 0.5f)));
    }

    @Test
    void aLiteralKeepsItsValuesWhateverHappensToAnArray() {
        float[] given = {1f, 2f};
        Expr.Lit lit = new Expr.Lit(given);
        given[0] = 5f;
        assertEquals(1f, lit.values()[0], "the array given was copied in");
        lit.values()[1] = 9f;
        assertEquals(2f, lit.values()[1], "the array read is a copy");
        assertEquals(Expr.Lit.of(1f, 2f), lit);
    }

    @Test
    void aChainSharingEveryStepIsComparedAndHashedOncePerNode() {
        // Forty squarings are 2^40 paths from the top: a walk that does not remember a node it
        // has been through never ends.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Expr one = squares(ref("u"), 40);
            Expr twin = squares(ref("u"), 40);
            assertEquals(one, twin);
            assertEquals(one.hashCode(), twin.hashCode());
            assertNotEquals(one, squares(ref("w"), 40));
            assertNotEquals(one, squares(ref("u"), 39));
        });
    }
}

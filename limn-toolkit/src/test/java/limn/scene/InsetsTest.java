package limn.scene;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Insets take a finite, non-negative number of points on each side, and a side that is not one is
 * refused where the insets are made, by its name, rather than wherever layout first subtracts it.
 */
class InsetsTest {

    /** One side set to the value and the other three to zero, with that side's name. */
    private record Side(String name, Function<Float, Insets> make) {
    }

    private static final List<Side> SIDES = List.of(
            new Side("top", v -> new Insets(v, 0, 0, 0)),
            new Side("right", v -> new Insets(0, v, 0, 0)),
            new Side("bottom", v -> new Insets(0, 0, v, 0)),
            new Side("left", v -> new Insets(0, 0, 0, v)));

    private static String failing(Runnable make) {
        return assertThrows(IllegalArgumentException.class, make::run).getMessage();
    }

    /**
     * {@code NaN < 0} is false, so the check for a negative side let a {@code NaN} through, and
     * layout carried it on: {@code Constraints.deflate} subtracts the insets and floors the result
     * with {@code Math.max(0, ...)}, which answers {@code NaN} for a {@code NaN}, and a
     * {@code Constraints} with a {@code NaN} bound is accepted too, so a padding of
     * {@code Insets.all(Float.NaN)} in a 100 by 100 box measured {@code NaN} by {@code NaN}.
     */
    @Test
    void aSideThatIsNotANumberIsRefusedByItsName() {
        for (Side side : SIDES) {
            assertEquals(side.name() + " inset must not be negative and must be finite, got NaN",
                    failing(() -> side.make().apply(Float.NaN)));
        }
    }

    /**
     * An infinite side is not a distance: a padding of {@code Insets.all(Float.POSITIVE_INFINITY)}
     * in a 100 by 100 box handed its child 0 by 0 and measured itself 100 by 100.
     */
    @Test
    void anInfiniteSideIsRefusedByItsName() {
        for (Side side : SIDES) {
            assertEquals(side.name() + " inset must not be negative and must be finite, got Infinity",
                    failing(() -> side.make().apply(Float.POSITIVE_INFINITY)));
            assertEquals(side.name() + " inset must not be negative and must be finite, got -Infinity",
                    failing(() -> side.make().apply(Float.NEGATIVE_INFINITY)));
        }
    }

    @Test
    void theFactoriesAreHeldToTheSameRule() {
        assertEquals("top inset must not be negative and must be finite, got NaN",
                failing(() -> Insets.all(Float.NaN)));
        assertEquals("right inset must not be negative and must be finite, got Infinity",
                failing(() -> Insets.symmetric(0, Float.POSITIVE_INFINITY)));
    }

    @Test
    void aNegativeSideIsStillRefusedAndZeroAndFiniteSidesAreKept() {
        for (Side side : SIDES) {
            assertEquals(side.name() + " inset must not be negative, got -1.0",
                    failing(() -> side.make().apply(-1f)));
        }
        assertEquals(new Insets(0, 0, 0, 0), Insets.NONE);
        Insets insets = new Insets(1.5f, 2, 3, 4);
        assertEquals(1.5f, insets.top());
        assertEquals(2, insets.right());
        assertEquals(3, insets.bottom());
        assertEquals(4, insets.left());
        assertEquals(new Insets(8, 12, 8, 12), Insets.symmetric(8, 12));
    }
}

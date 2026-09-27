package limn.scene;

import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A size box's dimensions are a finite number of points or free, and a dimension that is neither
 * is refused where the box is made, by its name, rather than read as whatever the comparison in
 * measure happens to make of it.
 */
class SizedBoxSizeTest {

    private static final List<Float> NOT_FINITE =
            List.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);

    /** Adding a child to the box is a tree change, which the UI thread makes. */
    private HeadlessUi ui;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
    }

    private static String failing(Runnable make) {
        return assertThrows(IllegalArgumentException.class, make::run).getMessage();
    }

    /**
     * Before, measure asked only {@code size >= 0}: a {@code NaN} failed it and left the axis free
     * without a word, so {@code new SizedBox(Float.NaN, 10, child)} measured its 30-point child's
     * 30; positive infinity passed it and was clamped to whatever the parent allowed, 100 in a
     * loose 100 by 100 box and an infinite width under an unbounded one; negative infinity was
     * free, as any negative is.
     */
    @Test
    void aDimensionThatIsNotFiniteIsRefusedByItsNameInBothConstructors() {
        for (float value : NOT_FINITE) {
            String hint = "; SizedBox.UNSET leaves a dimension free";
            assertEquals("width must be finite, got " + value + hint,
                    failing(() -> new SizedBox(value, 10)));
            assertEquals("height must be finite, got " + value + hint,
                    failing(() -> new SizedBox(10, value)));
            assertEquals("width must be finite, got " + value + hint,
                    failing(() -> new SizedBox(value, SizedBox.UNSET, new SizedBox(30, 10))));
            assertEquals("height must be finite, got " + value + hint,
                    failing(() -> new SizedBox(SizedBox.UNSET, value, new SizedBox(30, 10))));
        }
    }

    @Test
    void aRefusedBoxLeavesItsChildWithoutAParent() {
        SizedBox child = new SizedBox(30, 10);
        failing(() -> new SizedBox(Float.POSITIVE_INFINITY, 10, child));
        assertNull(child.parent(), "the check runs before the child is adopted");
    }

    /** Any negative still leaves its axis free, as {@link SizedBox#UNSET} does. */
    @Test
    void aNegativeDimensionIsStillFreeAndAFiniteOneStillFixed() {
        Constraints loose = Constraints.loose(100, 100);
        assertEquals(new Size(30, 10),
                new SizedBox(SizedBox.UNSET, 10, new SizedBox(30, 10)).measure(loose));
        assertEquals(new Size(30, 10), new SizedBox(-5, 10, new SizedBox(30, 10)).measure(loose));
        assertEquals(new Size(30, 12), new SizedBox(30, -0.5f, new SizedBox(20, 12)).measure(loose));
        assertEquals(new Size(40, 20), new SizedBox(40, 20).measure(loose));
        assertEquals(new Size(100, 20), new SizedBox(400, 20).measure(loose), "clamped to the parent");
        assertEquals(new Size(0, 0), new SizedBox(SizedBox.UNSET, SizedBox.UNSET).measure(loose),
                "a spacer with no child and nothing fixed collapses");
    }
}

package limn.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window's size limits are part of its configuration: a minimum and a maximum set where the window
 * is made, each wither keeping the other bound, and a pair that no size could satisfy refused.
 */
class WindowSizeLimitsTest {

    @Test
    void aConfigurationCarriesItsMinimumAndMaximum() {
        WindowConfig plain = WindowConfig.of("Tools", 480, 360);
        assertEquals(SizeLimits.NONE, plain.sizeLimits(), "no bound unless one is asked for");

        WindowConfig bounded = plain.minSize(320, 240).maxSize(1280, 960);
        assertEquals(new SizeLimits(320, 240, 1280, 960), bounded.sizeLimits());
        assertEquals(new SizeLimits(320, 240, 1280, 960), bounded.resizable(true).at(10, 10).sizeLimits(),
                "and every other wither keeps it");
        assertEquals(new SizeLimits(100, 240, 1280, 960), bounded.minSize(100, 240).sizeLimits(),
                "a new minimum keeps the maximum");
        assertNotEquals(plain, bounded);
        assertEquals(bounded, plain.minSize(320, 240).maxSize(1280, 960));
        assertEquals(bounded.hashCode(), plain.minSize(320, 240).maxSize(1280, 960).hashCode());
        assertTrue(bounded.toString().contains("SizeLimits"), bounded.toString());
    }

    @Test
    void aMaximumBelowItsMinimumIsRefusedAndANegativeBoundIsOpen() {
        WindowConfig atLeast = WindowConfig.of("w", 480, 360).minSize(400, 300);
        assertThrows(IllegalArgumentException.class, () -> atLeast.maxSize(399, 0));
        assertThrows(IllegalArgumentException.class, () -> atLeast.maxSize(0, 299));
        assertEquals(new SizeLimits(400, 300, 400, 300), atLeast.maxSize(400, 300).sizeLimits(),
                "equal bounds fix the size");
        assertEquals(SizeLimits.NONE, new SizeLimits(-1, -1, -1, -1),
                "as setSizeLimits has always read a negative bound");
    }

    @Test
    void aSizeIsClampedIntoTheBoundsThatAreSet() {
        SizeLimits limits = new SizeLimits(200, 0, 800, 600);
        assertEquals(200, limits.clampWidth(50));
        assertEquals(800, limits.clampWidth(5000));
        assertEquals(10, limits.clampHeight(10), "no minimum height");
        assertEquals(600, limits.clampHeight(700));
        assertEquals(12345, SizeLimits.NONE.clampWidth(12345));
    }
}

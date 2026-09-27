package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The texture budget is the application's to move, for every window in the process, and 256 MiB
 * until it does; what a window does with it is the backend's, and its tests hold it to that. The
 * owner chose a settable budget over a fixed constant, and that figure, on 2026-09-27.
 */
class ImagesTextureBudgetTest {

    @Test
    void theDefaultIs256MebibytesAndTheBudgetStartsThere() {
        assertEquals(256L * 1024 * 1024, Images.DEFAULT_TEXTURE_BUDGET);
        assertEquals(Images.DEFAULT_TEXTURE_BUDGET, Images.textureBudget());
    }

    @Test
    void aBudgetMovesAndANegativeOneIsRefusedLeavingTheBudgetAlone() {
        try {
            Images.setTextureBudget(64L << 20);
            assertEquals(64L << 20, Images.textureBudget());
            Images.setTextureBudget(0);
            assertEquals(0, Images.textureBudget(), "0 keeps only what the last frame drew");
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> Images.setTextureBudget(-1));
            assertEquals("a texture budget is at least 0 bytes, got -1", refused.getMessage());
            assertEquals(0, Images.textureBudget(), "a refused setting leaves the budget alone");
            Images.setTextureBudget(Long.MAX_VALUE);
            assertEquals(Long.MAX_VALUE, Images.textureBudget(), "no budget at all is a budget");
        } finally {
            Images.setTextureBudget(Images.DEFAULT_TEXTURE_BUDGET);
        }
    }
}

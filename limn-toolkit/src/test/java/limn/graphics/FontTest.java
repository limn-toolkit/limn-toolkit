package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FontTest {

    @Test
    void defaultFactoryUsesDefaultFamily() {
        Font font = Font.of(16);
        assertEquals(Font.DEFAULT_FAMILY, font.family());
        assertEquals(16, font.size(), 1e-6);
    }

    @Test
    void withSizeKeepsFamily() {
        Font font = new Font("Roboto", 14).withSize(22);
        assertEquals("Roboto", font.family());
        assertEquals(22, font.size(), 1e-6);
    }

    @Test
    void invalidSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Font.of(0));
        assertThrows(IllegalArgumentException.class, () -> Font.of(-4));
        assertThrows(IllegalArgumentException.class, () -> Font.of(Float.NaN));
        assertThrows(NullPointerException.class, () -> new Font(null, 12));
    }

    @Test
    void textMetricsHeightIsAscentPlusDescent() {
        TextMetrics m = new TextMetrics(100, 12, 4, 19);
        assertEquals(16, m.height(), 1e-6);
    }
}

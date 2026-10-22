package limn.print;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The paper sizes, in points, and the region's paper. */
class PaperTest {

    @Test
    void theSizesAreThePointsTheStandardsSay() {
        assertEquals(595.28f, Paper.A4.width(), 0.01f);
        assertEquals(841.89f, Paper.A4.height(), 0.01f);
        assertEquals(612f, Paper.LETTER.width(), 0f);
        assertEquals(792f, Paper.LETTER.height(), 0f);
        assertEquals(210f, Paper.A4.widthMillimetres(), 0.01f);
    }

    @Test
    void landscapeTurnsAndPortraitTurnsBack() {
        Paper turned = Paper.A4.landscape();
        assertTrue(turned.isLandscape());
        assertEquals(Paper.A4.height(), turned.width(), 0f);
        assertEquals(Paper.A4, turned.portrait());
        assertSame(turned, turned.landscape());
    }

    @Test
    void theRegionPicksLetterOrA4() {
        assertSame(Paper.LETTER, Paper.forLocale(Locale.US));
        assertSame(Paper.LETTER, Paper.forLocale(Locale.CANADA));
        assertSame(Paper.A4, Paper.forLocale(Locale.forLanguageTag("pt-BR")));
        assertSame(Paper.A4, Paper.forLocale(Locale.GERMANY));
        assertSame(Paper.A4, Paper.forLocale(Locale.ENGLISH), "a locale with no region");
    }

    @Test
    void aPaperHasASize() {
        assertThrows(IllegalArgumentException.class, () -> Paper.of("Nothing", 0, 100));
        assertThrows(IllegalArgumentException.class, () -> Paper.of(" ", 10, 100));
    }
}

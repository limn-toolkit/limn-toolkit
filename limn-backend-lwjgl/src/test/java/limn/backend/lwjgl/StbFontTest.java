package limn.backend.lwjgl;

import limn.graphics.TextMetrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the embedded Roboto through stb_truetype: pure CPU, no GL
 * context, so it runs headlessly in the regular test task.
 */
class StbFontTest {

    private static StbFont font;

    @BeforeAll
    static void load() {
        font = StbFont.loadResource("/limn/backend/lwjgl/fonts/Roboto-Regular.ttf", "Roboto");
    }

    @AfterAll
    static void unload() {
        font.close();
    }

    @Test
    void verticalMetricsAreSane() {
        TextMetrics m = font.measure("Hg", 16);
        assertTrue(m.ascent() > 8 && m.ascent() < 20, "ascent: " + m.ascent());
        assertTrue(m.descent() > 0 && m.descent() < 10, "descent: " + m.descent());
        assertTrue(m.lineHeight() >= m.ascent() + m.descent(), "lineHeight: " + m.lineHeight());
    }

    @Test
    void widthGrowsWithContentAndIsAdditiveWithoutKerning() {
        float a = font.measure("A", 20).width();
        float aa = font.measure("AA", 20).width();
        assertTrue(a > 0);
        // "AA" has no kerning in practice: additivity within float noise.
        assertEquals(2 * a, aa, 0.01f);
        assertTrue(font.measure("AAA", 20).width() > aa);
        assertEquals(0, font.measure("", 20).width(), 1e-6f);
    }

    @Test
    void measurementScalesLinearlyWithSize() {
        // Unquantized metrics: 32px must be exactly 2x the 16px measurement,
        // the property that keeps layout consistent across HiDPI scales.
        TextMetrics at16 = font.measure("Limn UI 123", 16);
        TextMetrics at32 = font.measure("Limn UI 123", 32);
        assertEquals(at16.width() * 2, at32.width(), 0.01f);
        assertEquals(at16.ascent() * 2, at32.ascent(), 0.01f);
        assertEquals(at16.lineHeight() * 2, at32.lineHeight(), 0.01f);
    }

    @Test
    void controlCharactersAreSkipped() {
        float ab = font.measure("A", 18).width() + font.measure("B", 18).width();
        assertEquals(ab, font.measure("A\nB", 18).width(), 0.01f);
        assertEquals(ab, font.measure("A\tB", 18).width(), 0.01f);
    }

    @Test
    void surrogatePairsAreIteratedAsCodePoints() {
        // U+1D538 (𝔸) is outside the BMP: must not crash nor split the pair.
        TextMetrics m = font.measure("A𝔸B", 18);
        assertTrue(m.width() >= font.measure("AB", 18).width(),
                "notdef advance should not be negative");
    }

    @Test
    void glyphCoverageMatchesRoboto() {
        assertTrue(font.hasGlyph('A'));
        assertTrue(font.hasGlyph('ç'));
        assertTrue(font.hasGlyph('Ж'));  // Cyrillic
        assertTrue(font.hasGlyph('λ'));  // Greek
        assertFalse(font.hasGlyph(0x4E00), "Roboto has no CJK; fallback is out of v1 scope");
    }

    @Test
    void rasterizationProducesTightBitmapsAndAdvances() {
        StbFont.RasterizedGlyph glyph = font.rasterize('A', 32);
        try {
            assertNotNull(glyph.bitmap());
            assertTrue(glyph.width() > 4 && glyph.width() < 40, "width: " + glyph.width());
            assertTrue(glyph.height() > 10 && glyph.height() < 40, "height: " + glyph.height());
            assertTrue(glyph.bearingY() < 0, "cap letter sits above the baseline");
            assertTrue(glyph.advance() > 0);
            // Bitmap must contain fully-inked pixels (it is coverage, not noise).
            int max = 0;
            for (int i = 0; i < glyph.width() * glyph.height(); i++) {
                max = Math.max(max, glyph.bitmap().get(i) & 0xFF);
            }
            assertEquals(255, max, "a 32px 'A' must reach full coverage somewhere");
        } finally {
            if (glyph.bitmap() != null) {
                MemoryUtil.memFree(glyph.bitmap());
            }
        }
    }

    @Test
    void whitespaceRasterizesToNoBitmapButKeepsAdvance() {
        StbFont.RasterizedGlyph space = font.rasterize(' ', 24);
        assertNull(space.bitmap());
        assertTrue(space.advance() > 0);
    }

    @Test
    void doubledDeviceSizeDoublesGlyphBitmap() {
        // The HiDPI cornerstone: 16px at scale 2 rasterizes a genuinely bigger
        // bitmap (32px), never a scaled-up 16px one.
        StbFont.RasterizedGlyph small = font.rasterize('H', 16);
        StbFont.RasterizedGlyph big = font.rasterize('H', 32);
        try {
            assertEquals(small.height() * 2, big.height(), 2, "raster height must track device size");
            assertEquals(small.advance() * 2, big.advance(), 0.01f);
        } finally {
            MemoryUtil.memFree(small.bitmap());
            MemoryUtil.memFree(big.bitmap());
        }
    }
}

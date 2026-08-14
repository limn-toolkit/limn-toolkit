package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The primitive open-addressing {@code long → Glyph} map behind the glyph atlas
 * (no boxing on the per-glyph hot path): probing, growth and clear.
 */
class GlyphMapTest {

    private static GlyphAtlas.Glyph glyph(int id) {
        return new GlyphAtlas.Glyph(id, 0, 0, 0, 0, id, id, 0, 0, id);
    }

    @Test
    void missingKeyReturnsNull() {
        GlyphAtlas.GlyphMap map = new GlyphAtlas.GlyphMap();
        assertNull(map.get(0L));
        assertNull(map.get(1234567890123L));
        assertEquals(0, map.size());
    }

    @Test
    void glyphKeyFieldsNeverCollide() {
        // 256 faces used to wrap an 8-bit face field back onto face 0.
        long face0 = GlyphAtlas.glyphKey(0, 128, 'A');
        long face256 = GlyphAtlas.glyphKey(256, 128, 'A');
        org.junit.jupiter.api.Assertions.assertNotEquals(face0, face256);
        // Max codepoint and a big quantized size stay in their own fields.
        long max = GlyphAtlas.glyphKey(1, 0xFFFF, 0x10FFFF);
        org.junit.jupiter.api.Assertions.assertNotEquals(GlyphAtlas.glyphKey(2, 0, 0), max);
        org.junit.jupiter.api.Assertions.assertNotEquals(GlyphAtlas.glyphKey(1, 0xFFFF, 0), max);
    }

    @Test
    void keyZeroIsAValidEntry() {
        GlyphAtlas.GlyphMap map = new GlyphAtlas.GlyphMap();
        GlyphAtlas.Glyph g = glyph(7);
        map.put(0L, g);
        assertSame(g, map.get(0L), "no key sentinel: 0 is a real key");
        assertEquals(1, map.size());
    }

    @Test
    void storesAndRetrievesAcrossGrowthWithoutLosingEntries() {
        GlyphAtlas.GlyphMap map = new GlyphAtlas.GlyphMap();
        Map<Long, GlyphAtlas.Glyph> reference = new HashMap<>();
        // Force several growth doublings (initial capacity 128) with keys shaped
        // like real packed atlas keys (face << 37 | size << 21 | codepoint).
        for (int i = 0; i < 2000; i++) {
            long key = ((long) (i % 3) << 37) | ((long) (100 + i) << 21) | (0x41 + i);
            GlyphAtlas.Glyph g = glyph(i);
            map.put(key, g);
            reference.put(key, g);
        }
        assertEquals(reference.size(), map.size());
        for (Map.Entry<Long, GlyphAtlas.Glyph> e : reference.entrySet()) {
            assertSame(e.getValue(), map.get(e.getKey()), "entry survived growth/probing");
        }
    }

    @Test
    void reinsertingAKeyOverwritesWithoutGrowingSize() {
        GlyphAtlas.GlyphMap map = new GlyphAtlas.GlyphMap();
        long key = (42L << 56) | (256L << 32) | 0x263A;
        map.put(key, glyph(1));
        GlyphAtlas.Glyph second = glyph(2);
        map.put(key, second);
        assertEquals(1, map.size(), "overwrite must not inflate size");
        assertSame(second, map.get(key));
    }

    @Test
    void removeIfDropsMatchesAndKeepsTheRestReachable() {
        GlyphAtlas.GlyphMap map = new GlyphAtlas.GlyphMap();
        for (int i = 1; i <= 500; i++) {
            map.put(((long) i << 32) | i, glyph(i)); // texture id == i
        }
        map.removeIf(g -> g.texture() % 2 == 0); // evict "even pages"
        assertEquals(250, map.size());
        for (int i = 1; i <= 500; i++) {
            long key = ((long) i << 32) | i;
            if (i % 2 == 0) {
                assertNull(map.get(key), "evicted glyph must be gone: " + i);
            } else {
                assertSame(map.get(key), map.get(key), "survivor must stay reachable: " + i);
                assertEquals(i, map.get(key).texture());
            }
        }
    }

    @Test
    void clearEmptiesTheMap() {
        GlyphAtlas.GlyphMap map = new GlyphAtlas.GlyphMap();
        for (int i = 0; i < 300; i++) {
            map.put(((long) i << 32) | i, glyph(i));
        }
        map.clear();
        assertEquals(0, map.size());
        assertNull(map.get(0L));
        // Reusable after clear.
        map.put(99L, glyph(99));
        assertEquals(1, map.size());
        assertSame(map.get(99L), map.get(99L));
    }
}

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
        StbFont.RasterizedGlyph glyph = font.rasterizeGlyph(font.glyphIndex('A'), 32);
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
        StbFont.RasterizedGlyph space = font.rasterizeGlyph(font.glyphIndex(' '), 24);
        assertNull(space.bitmap());
        assertTrue(space.advance() > 0);
    }

    @Test
    void doubledDeviceSizeDoublesGlyphBitmap() {
        // The HiDPI cornerstone: 16px at scale 2 rasterizes a genuinely bigger
        // bitmap (32px), never a scaled-up 16px one.
        StbFont.RasterizedGlyph small = font.rasterizeGlyph(font.glyphIndex('H'), 16);
        StbFont.RasterizedGlyph big = font.rasterizeGlyph(font.glyphIndex('H'), 32);
        try {
            assertEquals(small.height() * 2, big.height(), 2, "raster height must track device size");
            assertEquals(small.advance() * 2, big.advance(), 0.01f);
        } finally {
            MemoryUtil.memFree(small.bitmap());
            MemoryUtil.memFree(big.bitmap());
        }
    }

    /** Characters chosen to cover both id spaces: covered, uncovered, astral, format. */
    private static final int[] CORPUS = {
        'A', 'B', 'W', 'a', 'g', ' ', '0', '.', 'ç', 'Ж', 'λ',
        0x00A0,   // NBSP: a distinct glyph in Roboto, not a synonym for space
        0x200D,   // ZWJ: mapped to a real glyph here, which is why the filters stay above the cmap
        0xFE0F,   // variation selector: not mapped, so .notdef
        0x4E00,   // CJK: not mapped
        0x1D538,  // astral: not mapped
        0,        // NUL maps to a real glyph in Roboto; nothing may assume otherwise
    };

    /**
     * The one proof that re-keying from code point to glyph index changed no
     * number. Every stb entry point that was swapped is compared against the code
     * point form it replaced, on the same face, for characters this face has and
     * characters it does not.
     *
     * <p>stb implements each code point form as its glyph form behind a cmap
     * lookup, so this cannot drift — but that is a property of vendored C, and
     * nothing else in the suite would notice a systematic off-by-one in the
     * mapping. Exact equality, not a delta: both sides multiply the same integer
     * by the same float.
     */
    @Test
    void theGlyphFormsAgreeWithTheCodePointFormsTheyReplaced() {
        byte[] bytes = readRoboto();
        java.nio.ByteBuffer data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        org.lwjgl.stb.STBTTFontinfo info = org.lwjgl.stb.STBTTFontinfo.malloc();
        try {
            assertTrue(org.lwjgl.stb.STBTruetype.stbtt_InitFont(info, data,
                    org.lwjgl.stb.STBTruetype.stbtt_GetFontOffsetForIndex(data, 0)));
            float size = 16f;
            float scale = font.scaleForSize(size);
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                for (int cp : CORPUS) {
                    String what = String.format("U+%04X", cp);
                    int glyph = font.glyphIndex(cp);
                    assertEquals(org.lwjgl.stb.STBTruetype.stbtt_FindGlyphIndex(info, cp), glyph,
                            what + ": the memo must return what the cmap does");

                    java.nio.IntBuffer advance = stack.mallocInt(1);
                    java.nio.IntBuffer bearing = stack.mallocInt(1);
                    org.lwjgl.stb.STBTruetype.stbtt_GetCodepointHMetrics(info, cp, advance, bearing);
                    assertEquals(advance.get(0) * scale, font.glyphAdvance(glyph, size),
                            what + ": advance moved");

                    java.nio.IntBuffer[] box = new java.nio.IntBuffer[4];
                    for (int k = 0; k < 4; k++) {
                        box[k] = stack.mallocInt(1);
                    }
                    org.lwjgl.stb.STBTruetype.stbtt_GetCodepointBitmapBox(info, cp,
                            font.scaleForSize(32f), font.scaleForSize(32f),
                            box[0], box[1], box[2], box[3]);
                    StbFont.RasterizedGlyph raster = font.rasterizeGlyph(glyph, 32f);
                    try {
                        assertEquals(box[2].get(0) - box[0].get(0),
                                raster.bitmap() == null ? 0 : raster.width(), what + ": raster width moved");
                        assertEquals(box[3].get(0) - box[1].get(0),
                                raster.bitmap() == null ? 0 : raster.height(), what + ": raster height moved");
                        if (raster.bitmap() != null) {
                            assertEquals(box[0].get(0), raster.bearingX(), what + ": bearingX moved");
                            assertEquals(box[1].get(0), raster.bearingY(), what + ": bearingY moved");
                        }
                    } finally {
                        if (raster.bitmap() != null) {
                            MemoryUtil.memFree(raster.bitmap());
                        }
                    }
                }
                // Kerning, including the pair of .notdefs that two adjacent
                // uncovered characters produce.
                int[][] pairs = {{'A', 'V'}, {'W', 'a'}, {'T', 'o'}, {'V', 'A'}, {'f', 'i'},
                                 {'A', 'A'}, {0x4E00, 0x4E01}, {'A', 0x4E00}};
                for (int[] pair : pairs) {
                    int expected = org.lwjgl.stb.STBTruetype.stbtt_GetCodepointKernAdvance(
                            info, pair[0], pair[1]);
                    assertEquals(expected * scale,
                            font.glyphKerning(font.glyphIndex(pair[0]), font.glyphIndex(pair[1]), size),
                            String.format("kern U+%04X/U+%04X moved", pair[0], pair[1]));
                }
                // A kern that is actually non-zero, so the comparison above is not
                // two zeros agreeing. Roboto ships no 'kern' table, only GPOS.
                assertTrue(font.glyphKerning(font.glyphIndex('A'), font.glyphIndex('V'), size) < 0,
                        "A/V must kern, or stb stopped reading GPOS and this test proves nothing");
            }
            // And the whole measure loop, accumulated in its own order.
            for (String text : new String[]{"Waltz, bad nymph", "Théâtre", "Привет", "AVATAR",
                                            "A一B", "  spaced  out  "}) {
                assertEquals(referenceWidth(info, text, size), font.measure(text, size).width(),
                        "measured width moved for: " + text);
            }
        } finally {
            info.free();
            MemoryUtil.memFree(data);
        }
    }

    /** {@code StbFont.measure}'s exact arithmetic, over the code point entry points. */
    private static float referenceWidth(org.lwjgl.stb.STBTTFontinfo info, String text, float size) {
        float scale = font.scaleForSize(size);
        float width = 0;
        int previous = -1;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isISOControl(cp)) {
                    previous = -1;
                    continue;
                }
                java.nio.IntBuffer advance = stack.mallocInt(1);
                java.nio.IntBuffer bearing = stack.mallocInt(1);
                org.lwjgl.stb.STBTruetype.stbtt_GetCodepointHMetrics(info, cp, advance, bearing);
                width += advance.get(0) * scale;
                if (previous >= 0) {
                    width += org.lwjgl.stb.STBTruetype.stbtt_GetCodepointKernAdvance(
                            info, previous, cp) * scale;
                }
                previous = cp;
            }
        }
        return width;
    }

    /**
     * A glyph index is a row number in this face, not a character, and .notdef is
     * one of the rows. Pins the domain the cache key bias and the atlas key field
     * both rest on.
     */
    @Test
    void glyphIndicesAreThisFacesRowNumbersAndNotdefIsOneOfThem() {
        int a = font.glyphIndex('A');
        assertTrue(a > 0 && a <= 0xFFFF, "a glyph index is a uint16: " + a);
        assertEquals(a, font.glyphIndex('A'), "the memo must not leak a sentinel on the second call");
        org.junit.jupiter.api.Assertions.assertNotEquals(a, font.glyphIndex('B'));
        org.junit.jupiter.api.Assertions.assertNotEquals('A', a,
                "if the index happened to equal the code point this whole suite would prove nothing");
        assertEquals(0, font.glyphIndex(0x4E00), "Roboto has no CJK, so .notdef");
        assertFalse(font.hasGlyph(0x4E00));
        assertTrue(font.hasGlyph('A'));
    }

    /**
     * Index 0 is a legal, cacheable key, not the metric caches' empty slot. Those
     * caches use key 0 as their sentinel, so a key that is not biased away from it
     * stays <em>correct</em> while never caching — which is why the assertion that
     * catches it is repetition, not a value.
     */
    @Test
    void notdefIsACacheableKeyAndNotAnEmptySlot() {
        float notdef = font.glyphAdvance(0, 16f);
        assertTrue(notdef > 0, ".notdef carries a real advance: " + notdef);
        assertEquals(notdef, font.glyphAdvance(0, 16f), "second call must agree with the first");
        assertEquals(0f, font.glyphKerning(0, 0, 16f), "two adjacent .notdefs are a reachable pair");
        assertEquals(notdef, font.glyphAdvance(0, 16f), "the (0,0) kern must not have evicted it");
        // 'A' still measures as 'A' after all that traffic through key 0.
        assertTrue(font.glyphAdvance(font.glyphIndex('A'), 16f) > 0);
        // 200 uncovered characters: every advance and every kern pair is
        // (.notdef, .notdef), so an unbiased cache would re-cross JNI for all of
        // them and a rotted one would still return this same width.
        String uncovered = "一".repeat(200);
        assertEquals(font.measure(uncovered, 16f).width(), font.measure(uncovered, 16f).width());
        assertEquals(200 * notdef, font.measure(uncovered, 16f).width(), 0.01f);
    }

    /**
     * Why every metric-cache key is biased by one, demonstrated rather than
     * asserted in a comment — and it is not the harmless thing it looks like.
     * Key 0 is the map's empty slot, and because {@code get} compares the key
     * before it checks for emptiness, an empty slot answers key 0 as a <em>hit</em>
     * carrying 0. So key 0 never misses, never reaches the caller's sentinel, and
     * never asks stb: it invents a zero. Under glyph-index keys the value that
     * lands there is {@code .notdef}, the index of every character a face lacks,
     * so the unbiased version of this cache would report every uncovered
     * character as zero-width.
     */
    @Test
    void keyZeroReadsBackAFabricatedZeroWhichIsWhatTheBiasBuys() {
        StbFont.LongIntMap map = new StbFont.LongIntMap();
        int missing = Integer.MIN_VALUE;

        // Nothing stored at all, and the lookup still reports a value.
        assertEquals(0, map.get(0L, missing),
                "an empty slot answers key 0 as a hit, so a .notdef advance would read as 0");
        org.junit.jupiter.api.Assertions.assertNotEquals(missing, map.get(0L, missing));
        // A biased key behaves: it misses when it is absent.
        assertEquals(missing, map.get(1L, missing));

        // Storing it does not fix it either: the slot's key stays 0, so every
        // re-put counts a new entry and skews the growth trigger for good.
        map.put(0L, 111);
        map.put(0L, 222);
        map.put(0L, 333);
        assertEquals(3, map.count(), "three puts of one key counted as three entries");

        // And a rebuild drops it, back to the invented zero.
        for (int i = 1; i <= 500; i++) {
            map.put(i, i);
        }
        assertEquals(0, map.get(0L, missing), "the unbiased entry did not survive a rebuild");
        for (int i = 1; i <= 500; i++) {
            assertEquals(i, map.get(i, missing), "every biased key survived: " + i);
        }
    }

    private static byte[] readRoboto() {
        try (java.io.InputStream in = StbFontTest.class.getResourceAsStream(
                "/limn/backend/lwjgl/fonts/Roboto-Regular.ttf")) {
            assertNotNull(in, "the bundled Roboto is not optional for this module's tests");
            return in.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}

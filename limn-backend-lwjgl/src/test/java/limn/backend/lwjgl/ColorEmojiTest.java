package limn.backend.lwjgl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CBDT/CBLC color-bitmap reader used for color emoji. stb_truetype cannot open
 * Noto Color Emoji (bitmap-only, no outlines), so {@link ColorBitmaps} parses the
 * tables directly. Verified against the real font (skipped when it isn't bundled,
 * since it is optional; run {@code scripts/fetch-fonts.sh}).
 */
class ColorEmojiTest {

    @Test
    void corruptFontDegradesToNullInsteadOfCrashing() throws Exception {
        byte[] full;
        try (InputStream in = ColorEmojiTest.class.getResourceAsStream(
                "/limn/fonts/NotoColorEmoji.ttf")) {
            Assumptions.assumeTrue(in != null, "Noto Color Emoji is optional");
            full = in.readAllBytes();
        }
        // Truncations at various points (mid-directory, mid-tables): each must
        // degrade to null per the IfPresent contract, never crash startup.
        for (int keep : new int[]{4, 64, 1024, full.length / 2}) {
            byte[] truncated = java.util.Arrays.copyOf(full, keep);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
                ColorEmojiFont font = ColorEmojiFont.fromBytes(truncated, "truncated@" + keep);
                if (font != null) {
                    font.close(); // parsed shallowly enough to open: still no crash
                }
            }, "truncation at " + keep + " bytes");
        }
    }

    @Test
    void decodedBitmapCacheIsBoundedAndMissesArePinned() {
        ColorEmojiFont font = ColorEmojiFont.loadResourceIfPresent(
                "/limn/fonts/NotoColorEmoji.ttf");
        Assumptions.assumeTrue(font != null, "Noto Color Emoji is optional");
        java.util.concurrent.atomic.AtomicInteger decodes = new java.util.concurrent.atomic.AtomicInteger();
        limn.graphics.ImageDecoder decoder = bytes -> {
            decodes.incrementAndGet();
            return new limn.graphics.Image(1, 1, new byte[4]);
        };
        limn.graphics.Images.installDecoder(decoder);
        try {
            // Touch more distinct emoji than the cap holds.
            java.util.List<Integer> touched = new java.util.ArrayList<>();
            for (int cp = 0x1F300; cp <= 0x1F9FF
                    && touched.size() <= ColorEmojiFont.MAX_CACHED_BITMAPS; cp++) {
                if (font.image(cp) != null) {
                    touched.add(cp);
                }
            }
            Assumptions.assumeTrue(touched.size() > ColorEmojiFont.MAX_CACHED_BITMAPS,
                    "font covers enough emoji to overflow the cache");
            int before = decodes.get();
            assertNotNull(font.image(touched.get(0)), "first emoji was LRU-evicted…");
            assertTrue(decodes.get() > before, "…so it re-decodes instead of pinning forever");

            font.image('A'); // no color glyph: pinned as absent, never re-parsed
            int miss = decodes.get();
            font.image('A');
            assertTrue(decodes.get() == miss, "absent answers stay cached");
        } finally {
            limn.graphics.Images.uninstallDecoder(decoder);
            font.close();
        }
    }

    @Test
    void extractsPngForAColorGlyph() throws Exception {
        byte[] bytes;
        try (InputStream in = ColorEmojiTest.class.getResourceAsStream(
                "/limn/fonts/NotoColorEmoji.ttf")) {
            Assumptions.assumeTrue(in != null,
                    "Noto Color Emoji is optional; run scripts/fetch-fonts.sh to enable color emoji");
            bytes = in.readAllBytes();
        }
        ByteBuffer data = ByteBuffer.wrap(bytes);
        assertTrue(ColorBitmaps.present(data, 0), "the font carries CBDT/CBLC tables");
        // Glyph 4 is the first glyph the color strike covers (indexSubTable firstGlyph=4).
        ColorBitmaps.Glyph glyph = ColorBitmaps.glyph(data, 0, 4);
        assertNotNull(glyph, "a color bitmap must be extracted");
        byte[] png = glyph.png();
        assertTrue(png.length > 100, "the PNG has real payload: " + png.length + " bytes");
        // The placement travels with the picture: a strike authored at one size is unusable
        // without the size it was authored at.
        assertTrue(glyph.ppem() > 0, "the strike reports its ppem");
        assertTrue(glyph.width() > 0 && glyph.height() > 0, "the bitmap reports its own size");
        // PNG signature: 89 'P' 'N' 'G'
        assertTrue((png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G',
                "the extracted bytes are a PNG image");
    }

    /**
     * A colour glyph is drawn in the box the strike gives it, and that box has to fit the line
     * the run was measured into: the text engine sizes a line from the primary face's metrics
     * and never asks the emoji how tall it is, so an emoji drawn taller than the ascent is cut by
     * the first thing that clips, which for a Label is its own bounds.
     *
     * <p>The regression this pins drew the glyph as a square whose side was the ADVANCE: 6% too
     * tall, and lifted a further 9% of the size above the baseline by a hardcoded fraction.
     *
     * <p>Asserted on the metrics rather than through {@link ColorEmojiFont}, which decodes the
     * PNG and so needs a running backend for its image decoder. The arithmetic is the same one
     * that class does, and the numbers it divides are these.
     */
    @Test
    void aColourGlyphFitsTheLineItIsMeasuredInto() throws Exception {
        byte[] bytes;
        try (InputStream in = ColorEmojiTest.class.getResourceAsStream(
                "/limn/fonts/NotoColorEmoji.ttf")) {
            Assumptions.assumeTrue(in != null, "Noto Color Emoji is optional");
            bytes = in.readAllBytes();
        }
        StbFont roboto = StbFont.loadResourceIfPresent(
                "/limn/fonts/Roboto-Regular.ttf", "Roboto");
        Assumptions.assumeTrue(roboto != null, "Roboto is bundled");
        // Measured at size 1, so every number below is in ems and the bars hold at any size.
        limn.graphics.TextMetrics em = roboto.measure("", 1f);
        float ascent = em.ascent();
        float descent = em.descent();
        ByteBuffer data = ByteBuffer.wrap(bytes);

        int checked = 0;
        for (int gid : new int[]{4, 100, 500, 883}) {
            ColorBitmaps.Glyph glyph = ColorBitmaps.glyph(data, 0, gid);
            if (glyph == null) {
                continue;
            }
            checked++;
            float ppem = glyph.ppem();
            float top = glyph.bearingY() / ppem;
            float bottom = (glyph.height() - glyph.bearingY()) / ppem;
            String what = "glyph " + gid;
            assertTrue(top <= ascent + 0.005f, what + ": drawn " + top
                    + " em above the baseline, past the " + ascent + " em the line box allows");
            assertTrue(bottom <= descent + 0.005f, what + ": drawn " + bottom
                    + " em below the baseline, past the descender at " + descent);
            // The picture is not square. A square box is the defect itself (it means the side
            // came from the advance), so the assertion is that the two differ, not that either
            // has a particular value.
            assertTrue(glyph.width() != glyph.height(),
                    what + ": the box is square, so it came from the advance, not the bitmap");
        }
        assertTrue(checked > 0, "no colour glyph was found to check");
        roboto.close();
    }
}

package limn.backend.lwjgl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads embedded color-bitmap glyphs (CBDT/CBLC) straight from raw font bytes.
 * stb_truetype cannot: color-emoji fonts (Noto Color Emoji) are bitmap-only, with
 * no {@code glyf}/{@code CFF} outlines, so {@code stbtt_InitFont} rejects them.
 * This returns a glyph's raw PNG, decoded/cached by the caller.
 *
 * <p>Only the PNG-carrying glyph formats (17/18) and offset index formats (1/3)
 * are handled, which is what Noto Color Emoji (CBLC v3) uses. Font tables are
 * big-endian; MemoryUtil buffers are native-order, so parse via a big-endian view.
 */
final class ColorBitmaps {

    private static final int TAG_CBLC = 0x43424C43;
    private static final int TAG_CBDT = 0x43424454;

    private ColorBitmaps() {
    }

    /** @return whether {@code data} carries CBDT/CBLC color-bitmap tables */
    static boolean present(ByteBuffer data, int sfntOffset) {
        ByteBuffer be = data.duplicate().order(ByteOrder.BIG_ENDIAN);
        return tableOffset(be, sfntOffset, TAG_CBLC) >= 0 && tableOffset(be, sfntOffset, TAG_CBDT) >= 0;
    }

    /**
     * A colour glyph: its picture, and where that picture sits relative to the baseline.
     *
     * <p>All four numbers are in the strike's own pixels, which is why {@link #ppem} travels with
     * them: a bitmap strike is authored at one size and every consumer has to scale from it.
     * <b>The placement is not derivable from the advance.</b> Noto Color Emoji's glyphs are 136
     * wide and 128 tall at ppem 109, which is neither square nor as tall as the advance; drawing
     * one as an advance-sized square stretches it and lifts it above the line box it was measured
     * into, and whatever clips that box then cuts the top off.
     *
     * @param bearingY how far the top of the picture sits ABOVE the baseline
     */
    record Glyph(byte[] png, int width, int height, int bearingY, int ppem) {
    }

    /** @return the colour glyph for {@code glyphId}, or {@code null} when there is none */
    static Glyph glyph(ByteBuffer data, int sfntOffset, int glyphId) {
        if (glyphId <= 0) {
            return null;
        }
        try {
            ByteBuffer be = data.duplicate().order(ByteOrder.BIG_ENDIAN);
            int cblc = tableOffset(be, sfntOffset, TAG_CBLC);
            int cbdt = tableOffset(be, sfntOffset, TAG_CBDT);
            if (cblc < 0 || cbdt < 0) {
                return null;
            }
            int numSizes = be.getInt(cblc + 4);
            for (int s = 0; s < numSizes; s++) {
                int sizeTable = cblc + 8 + s * 48; // bitmapSizeTable is 48 bytes
                int indexArrayOffset = be.getInt(sizeTable);
                int numIndexSubTables = be.getInt(sizeTable + 8);
                int ppem = be.get(sizeTable + 45) & 0xFF; // ppemY
                int arrayBase = cblc + indexArrayOffset;
                for (int i = 0; i < numIndexSubTables; i++) {
                    int entry = arrayBase + i * 8; // {firstGlyph u16, lastGlyph u16, addlOffset u32}
                    int firstGlyph = be.getShort(entry) & 0xFFFF;
                    int lastGlyph = be.getShort(entry + 2) & 0xFFFF;
                    if (glyphId < firstGlyph || glyphId > lastGlyph) {
                        continue;
                    }
                    int subTable = arrayBase + be.getInt(entry + 4);
                    Glyph glyph = extract(be, data, cbdt, subTable, glyphId, firstGlyph, ppem);
                    if (glyph != null) {
                        return glyph;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Out-of-range/malformed offsets: no color glyph rather than a crash.
        }
        return null;
    }

    private static Glyph extract(ByteBuffer be, ByteBuffer data, int cbdt, int subTable,
                                 int glyphId, int firstGlyph, int ppem) {
        int indexFormat = be.getShort(subTable) & 0xFFFF;
        int imageFormat = be.getShort(subTable + 2) & 0xFFFF;
        int imageDataOffset = be.getInt(subTable + 4);
        if (imageFormat != 17 && imageFormat != 18) {
            return null; // only the PNG-carrying glyph formats
        }
        int local = glyphId - firstGlyph;
        int glyphOffset;
        int nextOffset;
        if (indexFormat == 1) {          // u32 offset array
            int array = subTable + 8;
            glyphOffset = be.getInt(array + local * 4);
            nextOffset = be.getInt(array + (local + 1) * 4);
        } else if (indexFormat == 3) {   // u16 offset array
            int array = subTable + 8;
            glyphOffset = be.getShort(array + local * 2) & 0xFFFF;
            nextOffset = be.getShort(array + (local + 1) * 2) & 0xFFFF;
        } else {
            return null;
        }
        if (nextOffset <= glyphOffset) {
            return null; // empty glyph
        }
        // CBDT glyph: format 17 = smallGlyphMetrics(5) + dataLen(u32) + PNG;
        //             format 18 = bigGlyphMetrics(8)   + dataLen(u32) + PNG.
        int glyphData = cbdt + imageDataOffset + glyphOffset;
        int metricsLength = imageFormat == 17 ? 5 : 8;
        int dataLength = be.getInt(glyphData + metricsLength);
        if (dataLength <= 0 || dataLength > nextOffset - glyphOffset) {
            return null;
        }
        // Both metric layouts open with height, width, bearingX, bearingY as bytes; the big form
        // only adds vertical members after them, which nothing here draws with.
        int height = be.get(glyphData) & 0xFF;
        int width = be.get(glyphData + 1) & 0xFF;
        int bearingY = be.get(glyphData + 3);
        byte[] png = new byte[dataLength];
        ByteBuffer src = data.duplicate();
        src.position(glyphData + metricsLength + 4);
        src.get(png, 0, dataLength);
        return new Glyph(png, width, height, bearingY, ppem);
    }

    /** @return the byte offset of table {@code tag} within the sfnt, or -1 */
    private static int tableOffset(ByteBuffer be, int sfntOffset, int tag) {
        int numTables = be.getShort(sfntOffset + 4) & 0xFFFF;
        int record = sfntOffset + 12;
        for (int i = 0; i < numTables; i++) {
            if (be.getInt(record) == tag) {
                return be.getInt(record + 8);
            }
            record += 16;
        }
        return -1;
    }
}

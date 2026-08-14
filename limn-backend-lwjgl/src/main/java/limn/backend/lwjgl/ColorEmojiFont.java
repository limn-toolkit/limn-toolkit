package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.Images;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A color-emoji font (CBDT/CBLC bitmap strikes, e.g. Noto Color Emoji), loaded
 * outside stb_truetype, which can't open it, since it has no {@code glyf}/{@code CFF}
 * outlines. Maps a code point to its embedded PNG via a minimal {@code cmap}
 * reader (formats 12 and 4) plus {@link ColorBitmaps}, then decodes it once to an
 * {@link Image} (cached per code point) for the normal image-draw path.
 *
 * <p>The monochrome fallback face still supplies each emoji's advance width, so
 * measurement is unaffected; this only replaces the drawn pixels with color.
 * Single code points only; ZWJ/flag/skin-tone sequences (which need GSUB shaping)
 * fall back to the monochrome face.
 */
final class ColorEmojiFont implements AutoCloseable {

    private final ByteBuffer data; // resident native copy (CBDT extraction reads it lazily)
    private final int cmapSubtable; // absolute offset of the chosen cmap subtable, or -1
    private final int cmapFormat;
    private final int unitsPerEm;
    private final int numberOfHMetrics;
    private final int hmtxOffset; // -1 if unavailable
    // code point -> decoded emoji image (null cached = no color glyph for it).
    /** Decoded strikes are ~68 KB of RGBA each: cap ≈ 17 MiB, LRU beyond it. */
    static final int MAX_CACHED_BITMAPS = 256;

    private final Map<Integer, Emoji> images = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Emoji> eldest) {
            return size() > MAX_CACHED_BITMAPS;
        }
    };
    // "No color glyph here" answers are pinned separately: they carry no pixels,
    // and every codepoint the primary face lacks (all of CJK on the fallback
    // path) asks; evicting them would re-parse the cmap each frame.
    private final Set<Integer> absent = new HashSet<>();

    private ColorEmojiFont(ByteBuffer data, int cmapSubtable, int cmapFormat,
                           int unitsPerEm, int numberOfHMetrics, int hmtxOffset) {
        this.data = data;
        this.cmapSubtable = cmapSubtable;
        this.cmapFormat = cmapFormat;
        this.unitsPerEm = unitsPerEm;
        this.numberOfHMetrics = numberOfHMetrics;
        this.hmtxOffset = hmtxOffset;
    }

    /**
     * Loads the color-emoji font from the classpath, or {@code null} if it is
     * absent or unusable. Blocks on the calling thread for the whole resource,
     * which for a full emoji font is tens of megabytes of bitmap strikes; call
     * it from a worker thread. The returned font owns native memory: whoever
     * does not install it must {@link #close} it.
     */
    static ColorEmojiFont loadResourceIfPresent(String resource) {
        byte[] bytes;
        try (InputStream in = ColorEmojiFont.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            bytes = in.readAllBytes();
        } catch (IOException error) {
            throw new UncheckedIOException("reading font " + resource, error);
        }
        return fromBytes(bytes, resource);
    }

    /** Parses {@code bytes} as a color-bitmap font, or {@code null} if unusable. */
    static ColorEmojiFont fromBytes(byte[] bytes, String source) {
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try {
            if (!ColorBitmaps.present(data, 0)) {
                MemoryUtil.memFree(data);
                return null; // not a color-bitmap font
            }
            long[] chosen = chooseCmap(data);
            if (chosen == null) {
                MemoryUtil.memFree(data);
                return null;
            }
            ByteBuffer be = data.duplicate().order(ByteOrder.BIG_ENDIAN);
            int head = tableOffset(be, 0x68656164); // 'head'
            int hhea = tableOffset(be, 0x68686561); // 'hhea'
            int hmtx = tableOffset(be, 0x686D7478); // 'hmtx'
            int unitsPerEm = head >= 0 ? be.getShort(head + 18) & 0xFFFF : 1000;
            int numHMetrics = hhea >= 0 ? be.getShort(hhea + 34) & 0xFFFF : 0;
            return new ColorEmojiFont(data, (int) chosen[0], (int) chosen[1],
                    unitsPerEm <= 0 ? 1000 : unitsPerEm, numHMetrics, hmtx);
        } catch (RuntimeException error) {
            // A truncated/corrupt font must degrade to "no color emoji", never
            // fail backend startup: the "IfPresent" contract is null-if-unusable.
            MemoryUtil.memFree(data);
            System.getLogger(ColorEmojiFont.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "unusable color-emoji font " + source + "; continuing without color emoji",
                    error);
            return null;
        }
    }

    /**
     * The color image for {@code cp}, extracted and decoded on first use.
     * Called from the draw path, so on the thread that is painting.
     *
     * <p>A miss extracts the code point's embedded strike and PNG-decodes it:
     * one strike, a glyph-sized RGBA bitmap, once per code point until the
     * {@link #MAX_CACHED_BITMAPS} cache drops it. <b>Deliberately synchronous.</b>
     * Deferring it to a worker would mean drawing {@code .notdef} for the first
     * frame of every new emoji and then healing, and healing needs a repaint
     * that nothing on this path can ask for: a font arriving late re-installs
     * the family catalog, which relayouts every scene, and doing that per emoji
     * code point would cost far more than the decode it saved.
     *
     * @return the decoded image, or {@code null} when this font has no color
     *         glyph for {@code cp}; the caller then draws the monochrome
     *         fallback face's glyph, whose advance already matched
     */
    Image image(int cp) {
        Emoji emoji = emoji(cp);
        return emoji == null ? null : emoji.image();
    }

    /**
     * A colour glyph and the box it is drawn in, the box measured in ems of the text size;
     * multiply by the font size to get user units.
     *
     * <p><b>Not a square, and not the advance.</b> The picture has its own width, height and
     * height above the baseline in the strike, and the three are unrelated to the advance the pen
     * moves by. Substituting the advance for any of them stretches the glyph and lifts it out of
     * the line box the text was measured into, where the first thing that clips cuts it.
     *
     * @param top how far above the baseline the top edge sits, in ems
     */
    record Emoji(Image image, float width, float height, float top) {
    }

    /** @return the glyph and its box, or {@code null} when this font has no colour glyph for it */
    Emoji emoji(int cp) {
        if (absent.contains(cp)) {
            return null;
        }
        Emoji cached = images.get(cp); // access-order touch
        if (cached != null) {
            return cached;
        }
        Emoji built = null;
        int glyph = glyphId(cp);
        if (glyph > 0) {
            ColorBitmaps.Glyph bitmap = ColorBitmaps.glyph(data, 0, glyph);
            if (bitmap != null && bitmap.ppem() > 0) {
                Image image = Images.decode(bitmap.png());
                if (image != null) {
                    float ppem = bitmap.ppem();
                    built = new Emoji(image, bitmap.width() / ppem, bitmap.height() / ppem,
                            bitmap.bearingY() / ppem);
                }
            }
        }
        if (built == null) {
            absent.add(cp);
        } else {
            images.put(cp, built);
        }
        return built;
    }

    /**
     * @return whether this font has a color glyph for {@code cp}, answered by
     *         decoding it, so a miss costs a PNG decode. Anything asking during
     *         measure or layout wants {@link #covers} instead, which reads the
     *         cmap and decodes nothing.
     */
    boolean has(int cp) {
        return image(cp) != null;
    }

    /** Cheap coverage check (cmap only, no bitmap decode), for measure and the draw trigger. */
    boolean covers(int cp) {
        return glyphId(cp) > 0;
    }

    /** Advance width of {@code cp}'s emoji at {@code sizePx}, from the font's hmtx (≈ 1 em square). */
    double advance(int cp, float sizePx) {
        int glyph = glyphId(cp);
        if (glyph <= 0 || hmtxOffset < 0 || numberOfHMetrics <= 0) {
            return sizePx; // full-em square fallback
        }
        ByteBuffer be = data.duplicate().order(ByteOrder.BIG_ENDIAN);
        int index = Math.min(glyph, numberOfHMetrics - 1);
        int advanceWidth = be.getShort(hmtxOffset + index * 4) & 0xFFFF;
        return (double) advanceWidth / unitsPerEm * sizePx;
    }

    // ------------------------------------------------------------------- cmap

    private int glyphId(int cp) {
        if (cmapSubtable < 0) {
            return 0;
        }
        ByteBuffer be = data.duplicate().order(ByteOrder.BIG_ENDIAN);
        return cmapFormat == 12 ? glyphIdFormat12(be, cp) : glyphIdFormat4(be, cp);
    }

    private int glyphIdFormat12(ByteBuffer be, int cp) {
        int numGroups = be.getInt(cmapSubtable + 12);
        int lo = 0;
        int hi = numGroups - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int group = cmapSubtable + 16 + mid * 12;
            long start = be.getInt(group) & 0xFFFFFFFFL;
            long end = be.getInt(group + 4) & 0xFFFFFFFFL;
            if (cp < start) {
                hi = mid - 1;
            } else if (cp > end) {
                lo = mid + 1;
            } else {
                int startGlyph = be.getInt(group + 8);
                return (int) (startGlyph + (cp - start));
            }
        }
        return 0;
    }

    private int glyphIdFormat4(ByteBuffer be, int cp) {
        if (cp > 0xFFFF) {
            return 0;
        }
        int segX2 = be.getShort(cmapSubtable + 6) & 0xFFFF;
        int segCount = segX2 / 2;
        int endCodes = cmapSubtable + 14;
        int startCodes = endCodes + segX2 + 2; // + reservedPad(2)
        int idDeltas = startCodes + segX2;
        int idRangeOffsets = idDeltas + segX2;
        for (int i = 0; i < segCount; i++) {
            int end = be.getShort(endCodes + i * 2) & 0xFFFF;
            if (cp > end) {
                continue;
            }
            int start = be.getShort(startCodes + i * 2) & 0xFFFF;
            if (cp < start) {
                return 0;
            }
            int idDelta = be.getShort(idDeltas + i * 2);
            int idRangeOffset = be.getShort(idRangeOffsets + i * 2) & 0xFFFF;
            if (idRangeOffset == 0) {
                return (cp + idDelta) & 0xFFFF;
            }
            int glyphIndexAddr = idRangeOffsets + i * 2 + idRangeOffset + (cp - start) * 2;
            int glyph = be.getShort(glyphIndexAddr) & 0xFFFF;
            return glyph == 0 ? 0 : (glyph + idDelta) & 0xFFFF;
        }
        return 0;
    }

    /** @return {@code [subtableOffset, format]} for the best Unicode cmap subtable, or {@code null} */
    private static long[] chooseCmap(ByteBuffer data) {
        ByteBuffer be = data.duplicate().order(ByteOrder.BIG_ENDIAN);
        int cmap = tableOffset(be, 0x636D6170); // 'cmap'
        if (cmap < 0) {
            return null;
        }
        int numTables = be.getShort(cmap + 2) & 0xFFFF;
        int best = -1;
        int bestFormat = 0;
        int bestScore = -1;
        for (int i = 0; i < numTables; i++) {
            int record = cmap + 4 + i * 8;
            int platform = be.getShort(record) & 0xFFFF;
            int encoding = be.getShort(record + 2) & 0xFFFF;
            int offset = cmap + be.getInt(record + 4);
            int format = be.getShort(offset) & 0xFFFF;
            if (format != 12 && format != 4) {
                continue;
            }
            boolean unicode = platform == 0
                    || (platform == 3 && (encoding == 1 || encoding == 10));
            if (!unicode) {
                continue;
            }
            int score = (format == 12 ? 10 : 0) + encoding; // prefer full-Unicode format 12
            if (score > bestScore) {
                bestScore = score;
                best = offset;
                bestFormat = format;
            }
        }
        return best < 0 ? null : new long[] {best, bestFormat};
    }

    private static int tableOffset(ByteBuffer be, int tag) {
        int numTables = be.getShort(4) & 0xFFFF;
        int record = 12;
        for (int i = 0; i < numTables; i++) {
            if (be.getInt(record) == tag) {
                return be.getInt(record + 8);
            }
            record += 16;
        }
        return -1;
    }

    @Override
    public void close() {
        MemoryUtil.memFree(data);
        images.clear();
        absent.clear();
    }
}

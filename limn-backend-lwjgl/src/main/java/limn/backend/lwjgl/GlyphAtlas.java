package limn.backend.lwjgl;

import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33C.GL_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_R8;
import static org.lwjgl.opengl.GL33C.GL_RED;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL33C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.glBindTexture;
import static org.lwjgl.opengl.GL33C.glDeleteTextures;
import static org.lwjgl.opengl.GL33C.glGenTextures;
import static org.lwjgl.opengl.GL33C.glPixelStorei;
import static org.lwjgl.opengl.GL33C.glTexImage2D;
import static org.lwjgl.opengl.GL33C.glTexParameteri;
import static org.lwjgl.opengl.GL33C.glTexSubImage2D;

/**
 * Dynamic glyph atlas: single-channel (GL_R8) texture pages filled by a
 * {@link ShelfPacker}, with glyphs cached by (face, quantized physical pixel
 * size, glyph index). Keying on the glyph rather than on the character is what
 * lets a shaper address this cache at all, and it collapses the characters a
 * face draws with one glyph into one entry instead of one each — strictly
 * fewer entries and fewer rasterizations, for identical pixels.
 * The physical-size key is the HiDPI cornerstone: 16pt at
 * scale 1.5 caches a 24px rasterization, distinct from 16px at 1.0; bitmaps
 * are NEVER scaled. Sampling is GL_LINEAR over zero-initialized pages: on the
 * snapped 1:1 path quads align to texel centers (bitwise-identical to
 * NEAREST, still crisp); rotated/scaled quads get smooth filtering instead of
 * blocky edges. Per-window, like every GL resource (contexts are not shared).
 *
 * <p>Eviction: when more than {@link #MAX_PAGES} pages accumulate (e.g. a
 * continuous zoom rasterizing many sizes), pages <em>not touched by the frame
 * that just ended</em> are dropped at the next frame boundary. A steady working
 * set larger than the cap stays resident; over budget beats re-rasterizing the
 * whole atlas every frame.
 */
final class GlyphAtlas implements AutoCloseable {

    static final int PAGE_SIZE = 1024;
    static final int MAX_PAGES = 4;
    /** Empty border around every glyph so LINEAR sampling never bleeds ink. */
    private static final int PADDING = 1;
    /** Physical font sizes quantize to 1/8 px; atlas keys must be discrete. */
    private static final float SIZE_QUANTUM = 8f;

    private static final System.Logger LOG = System.getLogger(GlyphAtlas.class.getName());

    /** One cached glyph: atlas page + texel rect + placement metrics (device px). */
    record Glyph(int texture, float u0, float v0, float u1, float v1,
                 int width, int height, int bearingX, int bearingY, float advance) {
    }

    private static final class Page {
        final int texture;
        final ShelfPacker packer = new ShelfPacker(PAGE_SIZE, PAGE_SIZE);
        int lastUsedFrame;

        Page() {
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            // Zero-initialize: padding texels are sampled by LINEAR filtering
            // and must be transparent, not driver garbage.
            java.nio.ByteBuffer zeros = MemoryUtil.memCalloc(PAGE_SIZE * PAGE_SIZE);
            try {
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, PAGE_SIZE, PAGE_SIZE, 0, GL_RED, GL_UNSIGNED_BYTE, zeros);
            } finally {
                MemoryUtil.memFree(zeros);
            }
        }
    }

    // Primitive open-addressing long→Glyph map: the cache-hit lookup is on the
    // per-glyph, per-frame hot path, so it must not box the packed long key.
    private final GlyphMap glyphs = new GlyphMap();
    private final List<Page> pages = new ArrayList<>();
    private int frame;
    private Page lastTouched; // memo: consecutive glyphs almost always share a page

    /** Quantizes a device font size to the atlas key grid. */
    static int quantizeSize(float deviceSize) {
        return Math.round(deviceSize * SIZE_QUANTUM);
    }

    static float dequantizeSize(int quantized) {
        return quantized / SIZE_QUANTUM;
    }

    /**
     * Key layout sized by the fields whose domains actually bind: quantized size
     * 16 bits, faceId the next 27, glyph index the low 21. System-font loading
     * can easily pass 256 faces, which an 8-bit face field would silently
     * collide back onto face 0 (wrong glyphs, not a crash).
     *
     * <p>A glyph index needs only 16 of those 21 bits: {@code maxp.numGlyphs} is
     * a uint16, and the broadest face here sits at exactly 65535. The field is 21
     * because 21 is what was <em>left over</em> — the two fields above it are
     * already no wider than they need to be — not because 21 bits were budgeted
     * for it. The five spare bits are slack, not a reserved namespace, and
     * narrowing the field to reclaim them would re-cut every shift in the
     * expression to free bits that no field wants.
     *
     * <p>{@code glyphIndex} is <b>face-relative</b> and must be an index in
     * {@code faceId}'s own face. Pairing one face's id with another's index is
     * the same failure class as the face-field collision above: a plausible
     * wrong glyph, drawn without complaint.
     */
    static long glyphKey(int faceId, int quantizedSize, int glyphIndex) {
        return ((long) faceId << 37) | ((long) (quantizedSize & 0xFFFF) << 21)
                // Not redundant, however sure "glyph indices are 16 bits" sounds.
                // A code point arrived bounded by the JDK; an index comes out of
                // the font's own cmap, and a malformed format-12 subtable computes
                // startGlyphID + delta as an unclamped uint32. This mask is the
                // only thing keeping such a value inside its field, and past it it
                // aliases onto another glyph of the same face. (stb rejects an
                // index past numGlyphs when it rasterizes, so the damage is a wrong
                // cache hit, not an out-of-bounds read.)
                | (glyphIndex & 0x1FFFFFL);
    }

    /**
     * The cached glyph, rasterizing and uploading it on first use.
     * {@code glyphIndex} must have been resolved through {@code font} itself and
     * {@code faceId} must be that font's id: this method takes all three
     * separately and can check none of them against each other.
     *
     * <p>Requires this window's GL context to be current, so it runs on the
     * render thread and has no asynchronous form: a miss reads no file and loads
     * no library; it rasterizes one glyph from a face already in memory and
     * uploads it with {@code glTexSubImage2D}, and the upload could not leave
     * this thread anyway. The face behind it is the loader, and it is warmed
     * elsewhere.
     */
    Glyph glyph(StbFont font, int faceId, int quantizedSize, int glyphIndex) {
        long key = glyphKey(faceId, quantizedSize, glyphIndex);
        Glyph cached = glyphs.get(key);
        if (cached != null) {
            touchPage(cached.texture());
            return cached;
        }
        Glyph fresh = rasterizeAndUpload(font, dequantizeSize(quantizedSize), glyphIndex);
        glyphs.put(key, fresh);
        touchPage(fresh.texture());
        return fresh;
    }

    /** Stamps the glyph's page as used this frame (eviction keeps hot pages). */
    private void touchPage(int texture) {
        if (texture == 0) {
            return;
        }
        Page memo = lastTouched;
        if (memo != null && memo.texture == texture) {
            memo.lastUsedFrame = frame;
            return;
        }
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (page.texture == texture) {
                page.lastUsedFrame = frame;
                lastTouched = page;
                return;
            }
        }
    }

    /**
     * Advances the frame counter and, when over the page cap, deletes only the
     * pages <em>not</em> touched by the frame that just ended (plus their cached
     * glyphs). When every page is hot (a steady working set larger than the
     * cap), nothing is dropped: staying over budget beats re-rasterizing the
     * whole atlas every frame.
     *
     * @return whether pages were deleted; the batch must then reset its
     *         bound-texture tracking, since GL recycles deleted ids
     */
    boolean beginFrameAndEvict() {
        frame++;
        if (pages.size() <= MAX_PAGES) {
            return false;
        }
        int before = pages.size();
        boolean evicted = pages.removeIf(page -> {
            if (page.lastUsedFrame >= frame - 1) {
                return false; // hot: used by the frame that just ended
            }
            glDeleteTextures(page.texture);
            return true;
        });
        if (!evicted) {
            return false;
        }
        lastTouched = null;
        int[] liveTextures = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            liveTextures[i] = pages.get(i).texture;
        }
        glyphs.removeIf(glyph -> glyph.texture() != 0 && !contains(liveTextures, glyph.texture()));
        LOG.log(System.Logger.Level.INFO, "glyph atlas evicted {0} cold page(s), {1} resident",
                before - pages.size(), pages.size());
        return true;
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    /** @return texture pages resident and their bytes (single-channel R8: 1 byte/texel). */
    limn.backend.RenderStats stats() {
        return new limn.backend.RenderStats(pages.size(), (long) pages.size() * PAGE_SIZE * PAGE_SIZE);
    }

    private Glyph rasterizeAndUpload(StbFont font, float deviceSize, int glyphIndex) {
        StbFont.RasterizedGlyph raster = font.rasterizeGlyph(glyphIndex, deviceSize);
        if (raster.bitmap() == null) {
            return new Glyph(0, 0, 0, 0, 0, 0, 0, 0, 0, raster.advance());
        }
        try {
            int w = raster.width();
            int h = raster.height();
            if (w + PADDING > PAGE_SIZE || h + PADDING > PAGE_SIZE) {
                // Degrade gracefully: absurd sizes skip the bitmap but keep
                // advancing, instead of aborting the frame mid-draw.
                // Not "U+..." any more, and the face is not decoration: a glyph
                // index printed as a code point names an unrelated character, and
                // an index without its face names nothing at all.
                LOG.log(System.Logger.Level.WARNING,
                        "glyph {0} of {1} at {2}px exceeds the {3}px atlas page; skipped",
                        glyphIndex, font.name(), deviceSize, PAGE_SIZE);
                return new Glyph(0, 0, 0, 0, 0, 0, 0, 0, 0, raster.advance());
            }
            Page page = null;
            long position = -1;
            for (Page candidate : pages) {
                position = candidate.packer.pack(w + PADDING, h + PADDING);
                if (position >= 0) {
                    page = candidate;
                    break;
                }
            }
            if (page == null) {
                page = new Page();
                pages.add(page);
                position = page.packer.pack(w + PADDING, h + PADDING);
            }
            int x = ShelfPacker.x(position);
            int y = ShelfPacker.y(position);
            glBindTexture(GL_TEXTURE_2D, page.texture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RED, GL_UNSIGNED_BYTE, raster.bitmap());
            float inv = 1f / PAGE_SIZE;
            return new Glyph(page.texture,
                    x * inv, y * inv, (x + w) * inv, (y + h) * inv,
                    w, h, raster.bearingX(), raster.bearingY(), raster.advance());
        } finally {
            MemoryUtil.memFree(raster.bitmap());
        }
    }

    @Override
    public void close() {
        for (Page page : pages) {
            glDeleteTextures(page.texture);
        }
        pages.clear();
        glyphs.clear();
    }

    /**
     * Minimal open-addressing {@code long → Glyph} map (linear probing), so the
     * per-glyph cache-hit lookup never boxes the key. A slot is empty iff its
     * value is {@code null} (glyph values are never null), so no key sentinel is
     * needed: key {@code 0} is a valid entry. Capacity stays a power of two.
     * Package-private for unit-testing the probing/growth.
     */
    static final class GlyphMap {
        private long[] keys = new long[128];
        private Glyph[] values = new Glyph[128];
        private int size;

        Glyph get(long key) {
            int mask = keys.length - 1;
            int i = (int) (mix(key) & mask);
            Glyph value;
            while ((value = values[i]) != null) {
                if (keys[i] == key) {
                    return value;
                }
                i = (i + 1) & mask;
            }
            return null;
        }

        void put(long key, Glyph value) {
            if ((size + 1) * 4 >= keys.length * 3) { // grow past a 0.75 load factor
                grow();
            }
            if (insert(keys, values, key, value)) {
                size++;
            }
        }

        int size() {
            return size;
        }

        void clear() {
            java.util.Arrays.fill(values, null);
            size = 0;
        }

        /** Drops every glyph matching {@code condemn} (a rebuild, since eviction is rare). */
        void removeIf(java.util.function.Predicate<Glyph> condemn) {
            long[] oldKeys = keys;
            Glyph[] oldValues = values;
            keys = new long[oldKeys.length];
            values = new Glyph[oldValues.length];
            size = 0;
            for (int i = 0; i < oldValues.length; i++) {
                if (oldValues[i] != null && !condemn.test(oldValues[i])
                        && insert(keys, values, oldKeys[i], oldValues[i])) {
                    size++;
                }
            }
        }

        private void grow() {
            long[] oldKeys = keys;
            Glyph[] oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new Glyph[oldValues.length * 2];
            for (int i = 0; i < oldValues.length; i++) {
                if (oldValues[i] != null) {
                    insert(keys, values, oldKeys[i], oldValues[i]);
                }
            }
        }

        /** @return true if a new slot was filled; false if it overwrote a match. */
        private static boolean insert(long[] keys, Glyph[] values, long key, Glyph value) {
            int mask = keys.length - 1;
            int i = (int) (mix(key) & mask);
            while (values[i] != null) {
                if (keys[i] == key) {
                    values[i] = value;
                    return false;
                }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            values[i] = value;
            return true;
        }

        /** Bit-mix so the packed (face|size|glyph) key spreads across buckets. */
        private static long mix(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return key;
        }
    }
}

package limn.backend.lwjgl;

import limn.graphics.TextMetrics;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.stb.STBTruetype.stbtt_FindGlyphIndex;
import static org.lwjgl.stb.STBTruetype.stbtt_GetFontOffsetForIndex;
import static org.lwjgl.stb.STBTruetype.stbtt_GetGlyphBitmapBox;
import static org.lwjgl.stb.STBTruetype.stbtt_GetGlyphHMetrics;
import static org.lwjgl.stb.STBTruetype.stbtt_GetGlyphKernAdvance;
import static org.lwjgl.stb.STBTruetype.stbtt_GetFontVMetrics;
import static org.lwjgl.stb.STBTruetype.stbtt_InitFont;
import static org.lwjgl.stb.STBTruetype.stbtt_MakeGlyphBitmap;
import static org.lwjgl.stb.STBTruetype.stbtt_ScaleForMappingEmToPixels;

/**
 * One loaded TrueType face (stb_truetype). Pure CPU: rasterization returns
 * bitmaps; texture upload belongs to {@link GlyphAtlas}. The font ByteBuffer
 * is native memory that must outlive the {@code STBTTFontinfo}; both are
 * freed in {@link #close()}.
 *
 * <p>Metric/advance values are unquantized floats in the requested pixel
 * size, so measurements scale linearly and are HiDPI-independent.
 *
 * <p><b>Two numbering spaces meet here.</b> A <em>code point</em> is a Unicode
 * character and means the same thing in every face; a <em>glyph index</em> is
 * this face's own row number and means nothing in any other. {@link #glyphIndex}
 * is the only crossing between them. Coverage ({@link #hasGlyph}) and the
 * measure entry points take code points because face selection is a question
 * about a character; metrics, kerning and rasterization take glyph indices,
 * because that is what a shaper will hand them. Index {@code 0} is
 * {@code .notdef} — a real, drawable glyph with a real advance, not an error
 * code and not an absent value.
 *
 * <p>Not thread-safe: every metric, coverage and raster query fills a per-face
 * cache, so a face is confined to whichever thread owns it (the UI/render
 * thread once it is registered). Parsing a face is thread-free; using one is
 * not.
 */
final class StbFont implements AutoCloseable {

    /** Result of rasterizing one glyph: tight bitmap + placement metrics (device px). */
    record RasterizedGlyph(ByteBuffer bitmap, int width, int height,
                           int bearingX, int bearingY, float advance) {
    }

    private final String name;
    private final ByteBuffer data;
    private final STBTTFontinfo info;
    // Which face of a .ttc these bytes were initialized as. Kept, not discarded with the offset it
    // resolved to, because the shaper needs the INDEX: stb addresses a collection's faces by byte
    // offset and HarfBuzz addresses them by index, so the number has to survive the conversion or
    // the two open different faces of the same file.
    private final int faceIndex;
    private final int ascentUnits;
    private final int descentUnits;
    private final int lineGapUnits;

    // Unscaled metric caches (font units are size-independent): text layout and
    // drawText used to pay 1-2 JNI crossings per glyph pair per frame just for
    // advances/kerning that never change. The cmap walk joins them because it is
    // now the first step of every one of those queries, and of every coverage
    // probe the fallback chain makes.
    //
    // EVERY key here is biased by +1, and none of the three may stop being. Key 0
    // is LongIntMap's empty slot and reads back as a hit carrying 0 rather than as
    // a miss (see there), and all three of these are keyed by values that reach 0
    // legitimately: glyph index 0 is .notdef, the index of every character the
    // face lacks. Unbiased, an uncovered character would take its advance from
    // that fabricated 0 and measure as zero-width, on the single hottest key the
    // fallback path has. (The old excuse was "code point 0 is an ISO control and
    // never arrives" — untrue of glyph 0 in any face, and this face maps code
    // point 0 to a real glyph anyway.)
    private static final int MISSING = Integer.MIN_VALUE;
    private final LongIntMap glyphIndexCache = new LongIntMap();
    private final LongIntMap advanceCache = new LongIntMap();
    private final LongIntMap kernCache = new LongIntMap();
    // stbtt_ScaleForMappingEmToPixels memo: strings are drawn/measured at one
    // size at a time, so a single-entry memo removes the per-call JNI hop.
    private float lastSizePx = Float.NaN;
    private float lastScale;
    private boolean closed;

    // The shaper's view of this same face, built on the first run shaped WITH it and destroyed in
    // close(). Lazy because most faces in a fallback chain are probed for coverage and never
    // shaped, and building this parses the sfnt a second time; `shaperTried` is what keeps a face
    // HarfBuzz refuses from re-parsing on every string.
    private HarfBuzzShaper.Handle shaper;
    private boolean shaperTried;

    private StbFont(String name, ByteBuffer data, STBTTFontinfo info, int faceIndex,
                    int ascentUnits, int descentUnits, int lineGapUnits) {
        this.name = name;
        this.data = data;
        this.info = info;
        this.faceIndex = faceIndex;
        this.ascentUnits = ascentUnits;
        this.descentUnits = descentUnits;
        this.lineGapUnits = lineGapUnits;
    }

    /**
     * Loads a bundled face from the classpath. Blocks on the calling thread,
     * and deliberately has no asynchronous form: the bundled Roboto variants
     * are a few hundred kilobytes each and {@code stbtt_InitFont} only walks
     * the sfnt table directory, so a load costs far less than a frame, and
     * the first measure of the first frame needs <em>some</em> face, so there
     * is no moment earlier than backend startup to move it to.
     *
     * @throws IllegalStateException if the resource is not on the classpath
     */
    static StbFont loadResource(String resource, String name) {
        StbFont face = loadResourceIfPresent(resource, name);
        if (face == null) {
            throw new IllegalStateException("font resource missing: " + resource);
        }
        return face;
    }

    /**
     * Loads a face from the classpath, or returns {@code null} if the resource
     * is absent (optional fonts). Blocks on the calling thread for the whole
     * resource, which is small for a bundled UI face and tens of megabytes for
     * a broad-coverage fallback, so the thread is the caller's choice, and a
     * caller reading anything but a small bundled face should be on a worker.
     * The returned face owns native memory: whoever does not install it must
     * {@link #close} it.
     */
    static StbFont loadResourceIfPresent(String resource, String name) {
        byte[] bytes;
        try (InputStream in = StbFont.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            bytes = in.readAllBytes();
        } catch (IOException error) {
            throw new UncheckedIOException("reading font " + resource, error);
        }
        return fromBytes(bytes, 0, name, resource);
    }

    /**
     * Loads a face from a filesystem font file, the on-demand path for a
     * selected system font. {@code index} selects a face inside a {@code .ttc}
     * collection (0 for a plain .ttf/.otf). The whole file is read into native
     * memory and kept resident, so callers must bound how many they keep open.
     *
     * <p><b>Blocks on the calling thread for the length of the file, which is
     * an OS font and therefore unbounded</b>: a macOS {@code .ttc} collection
     * is tens of megabytes, and on a cold page cache or a network home
     * directory the read is far longer than a frame. Call it from a worker
     * thread, never from a measure or a paint.
     *
     * <p>The returned face is plain CPU state and may be created on any thread,
     * but it must be handed to the UI thread before it is registered anywhere:
     * the registry that holds faces is unsynchronized and UI-thread confined.
     * A face that is never registered owns native memory until {@link #close}.
     *
     * @throws java.io.UncheckedIOException if the file cannot be read
     * @throws IllegalStateException        if it holds no face {@code index}
     */
    static StbFont loadFile(Path path, int index, String name) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException error) {
            throw new UncheckedIOException("reading font " + path, error);
        }
        return fromBytes(bytes, index, name, path.toString());
    }

    /** Uploads {@code bytes} to native memory and initializes face {@code index}. */
    private static StbFont fromBytes(byte[] bytes, int index, String name, String source) {
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        int face = Math.max(0, index);
        // The offset lookup is required even for face 0: in a .ttc collection
        // byte 0 holds the 'ttcf' header, not the face (plain .ttf returns 0).
        int offset = stbtt_GetFontOffsetForIndex(data, face);
        if (offset < 0) {
            MemoryUtil.memFree(data);
            throw new IllegalStateException("no face " + index + " in " + source);
        }
        STBTTFontinfo info = STBTTFontinfo.malloc();
        if (!stbtt_InitFont(info, data, offset)) {
            info.free();
            MemoryUtil.memFree(data);
            throw new IllegalStateException("stbtt_InitFont failed for " + source);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ascent = stack.mallocInt(1);
            IntBuffer descent = stack.mallocInt(1);
            IntBuffer lineGap = stack.mallocInt(1);
            stbtt_GetFontVMetrics(info, ascent, descent, lineGap);
            return new StbFont(name, data, info, face,
                    ascent.get(0), descent.get(0), lineGap.get(0));
        }
    }

    String name() {
        return name;
    }

    /** Scale factor mapping font units to a given em size in pixels (CSS-like sizing). */
    float scaleForSize(float sizePx) {
        if (sizePx != lastSizePx) {
            lastSizePx = sizePx;
            lastScale = stbtt_ScaleForMappingEmToPixels(info, sizePx);
        }
        return lastScale;
    }

    /** Measures one line at {@code sizePx} (same units as the result). */
    TextMetrics measure(String text, float sizePx) {
        float scale = scaleForSize(sizePx);
        float width = 0;
        // -1, never 0: 0 is .notdef, a legitimate first glyph. Collapsing the
        // sentinel onto it would drop the kern pair after every character the
        // face lacks.
        int previousGlyph = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            // Above the cmap, and it has to stay there: a control is a property of
            // the character, and this face maps several of them to real glyphs.
            if (Character.isISOControl(cp)) {
                previousGlyph = -1;
                continue;
            }
            int glyph = glyphIndex(cp);
            width += advanceUnits(glyph) * scale;
            if (previousGlyph >= 0) {
                width += kernUnits(previousGlyph, glyph) * scale;
            }
            previousGlyph = glyph;
        }
        return new TextMetrics(width, ascentUnits * scale, -descentUnits * scale,
                (ascentUnits - descentUnits + lineGapUnits) * scale);
    }

    /**
     * Measures one line where each code point may be drawn by a different face
     * ({@code faceFor} picks it: the CJK/emoji fallback). Widths come from the
     * resolved face; kerning applies only within a run of the same face; the
     * line's vertical metrics stay this (primary) face's, so line height is
     * stable regardless of which glyphs fall back.
     */
    TextMetrics measureWithFallback(String text, float sizePx,
            java.util.function.IntFunction<StbFont> faceFor,
            java.util.function.IntToDoubleFunction colorAdvance) {
        float width = 0;
        int previousGlyph = -1; // -1, never 0: see measure
        StbFont previousFace = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            // Both filters ask about the CHARACTER and so must stay above the cmap.
            // Roboto maps ZWJ to a real glyph and every CJK character to .notdef;
            // filtering below the lookup could not tell the two apart, and would
            // either draw a box for a joiner or swallow everything the face lacks.
            if (Character.isISOControl(cp) || isZeroWidthFormat(cp)) {
                previousGlyph = -1;
                previousFace = null;
                continue;
            }
            double colored = colorAdvance.applyAsDouble(cp);
            if (!Double.isNaN(colored)) { // a color-emoji glyph carries its own advance
                width += colored;
                previousGlyph = -1;
                previousFace = null;
                continue;
            }
            StbFont face = faceFor.apply(cp);
            // face.glyphIndex, never this.glyphIndex: an index is a row number in
            // the face that issued it, so the conversion has to happen AFTER the
            // fallback picks one. Both spellings compile and both return an int;
            // the wrong one measures whatever glyph the primary keeps at that row.
            int glyph = face.glyphIndex(cp);
            float scale = face.scaleForSize(sizePx);
            width += face.advanceUnits(glyph) * scale; // private, but same class across instances
            if (previousFace == face && previousGlyph >= 0) {
                width += face.kernUnits(previousGlyph, glyph) * scale;
            }
            previousGlyph = glyph;
            previousFace = face;
        }
        float scale = scaleForSize(sizePx);
        return new TextMetrics(width, ascentUnits * scale, -descentUnits * scale,
                (ascentUnits - descentUnits + lineGapUnits) * scale);
    }

    /** ZWJ / variation selectors / emoji tag characters: non-spacing, never drawn. */
    static boolean isZeroWidthFormat(int cp) {
        return cp == 0x200D
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0020 && cp <= 0xE007F);
    }

    /**
     * Kerning adjustment between two glyphs <em>of this face</em> at
     * {@code sizePx}, 0 when the face pairs them at no adjustment. Both indices
     * must come from {@link #glyphIndex} on this same face.
     *
     * <p>stb_truetype reads GPOS pair positioning as well as the legacy 'kern'
     * table, which is what makes this useful at all on a modern face: neither
     * bundled face ships a 'kern' table, and Roboto still kerns A/V.
     */
    float glyphKerning(int previousGlyph, int glyph, float sizePx) {
        return kernUnits(previousGlyph, glyph) * scaleForSize(sizePx);
    }

    /** Advance width of one glyph of this face at {@code sizePx}. */
    float glyphAdvance(int glyph, float sizePx) {
        return advanceUnits(glyph) * scaleForSize(sizePx);
    }

    private int advanceUnits(int glyph) {
        int cached = advanceCache.get(glyph + 1L, MISSING);
        if (cached != MISSING) {
            return cached;
        }
        int units;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer advance = stack.mallocInt(1);
            IntBuffer leftBearing = stack.mallocInt(1);
            stbtt_GetGlyphHMetrics(info, glyph, advance, leftBearing);
            units = advance.get(0);
        }
        advanceCache.put(glyph + 1L, units); // +1: see the cache fields
        return units;
    }

    // 17 is 16 plus one bit of headroom, and the headroom is the point. A
    // conforming face fits in 16: maxp.numGlyphs is a uint16, so the highest
    // index is numGlyphs-1 = 0xFFFE and the biased value tops out at exactly
    // 0xFFFF. But the value being packed is font-controlled and stb does not
    // clamp it — a malformed cmap format 12 computes startGlyphID + delta as an
    // unclamped uint32 — and a field sized to the conforming maximum turns such
    // an index into another pair's cached kern rather than into a bad lookup.
    private static final int KERN_PREVIOUS_SHIFT = 17;

    private int kernUnits(int previousGlyph, int glyph) {
        // Both halves biased, which is also what keeps the packed key off
        // LongIntMap's empty slot: an unbiased pack is exactly 0 when both
        // glyphs are .notdef, i.e. any two adjacent characters this face lacks.
        long key = ((previousGlyph + 1L) << KERN_PREVIOUS_SHIFT) | (glyph + 1L);
        int cached = kernCache.get(key, MISSING);
        if (cached != MISSING) {
            return cached;
        }
        int units = stbtt_GetGlyphKernAdvance(info, previousGlyph, glyph);
        kernCache.put(key, units);
        return units;
    }

    /**
     * Minimal open-addressing long→int map: no boxing, no removal (metric caches).
     *
     * <p>Key 0 is the empty slot, so <b>every caller must bias its key away from
     * 0</b>, and the reason is sharper than "that entry would not be cached".
     * {@code get} tests {@code k == key} before it tests {@code k == 0}, so for
     * key 0 an <em>empty</em> slot is indistinguishable from a hit: it returns
     * that slot's value, which is 0. Key 0 therefore never misses and never
     * reaches the caller's {@code missing} sentinel — it answers 0, a perfectly
     * plausible integer, without ever asking stb. In an advance cache that is a
     * glyph silently reported as zero-width. {@code put} cannot rescue it either:
     * the slot's key stays 0, so a re-put counts a fresh entry every time
     * (permanently skewing the growth trigger) and {@code grow} rebuilds only
     * {@code oldKeys[i] != 0} and drops it.
     *
     * <p>Package-private so that failure is unit-testable, which is the only way
     * it is observable at all: nothing about it throws, and the wrong value it
     * invents is in range.
     */
    static final class LongIntMap {

        private long[] keys = new long[512];
        private int[] values = new int[512];
        private int count;

        /** Entries believed stored; the bias is what keeps this honest. */
        int count() {
            return count;
        }

        int get(long key, int missing) {
            int mask = keys.length - 1;
            int slot = slot(key, mask);
            while (true) {
                long k = keys[slot];
                if (k == key) {
                    return values[slot];
                }
                if (k == 0) {
                    return missing;
                }
                slot = (slot + 1) & mask;
            }
        }

        void put(long key, int value) {
            if ((count + 1) * 4 > keys.length * 3) {
                grow();
            }
            int mask = keys.length - 1;
            int slot = slot(key, mask);
            while (keys[slot] != 0 && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            if (keys[slot] == 0) {
                count++;
            }
            keys[slot] = key;
            values[slot] = value;
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new int[oldValues.length * 2];
            count = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != 0) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }

        private static int slot(long key, int mask) {
            long mixed = key * 0x9E3779B97F4A7C15L;
            return (int) (mixed >>> 40) & mask;
        }
    }

    /**
     * This face's glyph index for {@code cp}, or {@code 0} when it has none.
     *
     * <p>{@code 0} is {@code .notdef}: a legal, drawable, cacheable index with a
     * real advance and (in most faces) real ink, and the answer for every
     * character the face lacks. Callers distinguish "absent" from "present" by
     * comparing to 0; nothing downstream may treat 0 as an error.
     *
     * <p>The value is meaningful <b>only against this face</b>. An index carried
     * to another face is a row number in a table it does not belong to, which
     * draws some other real glyph rather than failing.
     *
     * <p>Memoized, so it mutates: see the class note on thread confinement.
     */
    int glyphIndex(int cp) {
        int cached = glyphIndexCache.get(cp + 1L, MISSING);
        if (cached != MISSING) {
            return cached;
        }
        int index = stbtt_FindGlyphIndex(info, cp);
        // Misses are cached on purpose: face selection asks every face in the
        // fallback chain about every character the ones before it lacked, so 0 is
        // the most repeated answer this map has, not a value worth skipping.
        glyphIndexCache.put(cp + 1L, index); // +1: see the cache fields
        return index;
    }

    /** Whether this face can draw {@code cp} itself (a question about the character). */
    boolean hasGlyph(int cp) {
        return glyphIndex(cp) != 0;
    }

    /**
     * Rasterizes one glyph of this face at {@code sizePx}; {@code glyph} must
     * come from {@link #glyphIndex} on this face. The returned bitmap is
     * {@code memAlloc}'d; the caller frees it after upload. Whitespace and
     * empty glyphs return a null bitmap with a valid advance.
     */
    RasterizedGlyph rasterizeGlyph(int glyph, float sizePx) {
        float scale = scaleForSize(sizePx);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x0 = stack.mallocInt(1);
            IntBuffer y0 = stack.mallocInt(1);
            IntBuffer x1 = stack.mallocInt(1);
            IntBuffer y1 = stack.mallocInt(1);
            stbtt_GetGlyphBitmapBox(info, glyph, scale, scale, x0, y0, x1, y1);
            int width = x1.get(0) - x0.get(0);
            int height = y1.get(0) - y0.get(0);
            float advancePx = glyphAdvance(glyph, sizePx);
            if (width <= 0 || height <= 0) {
                return new RasterizedGlyph(null, 0, 0, 0, 0, advancePx);
            }
            ByteBuffer bitmap = MemoryUtil.memAlloc(width * height);
            // The box and the fill are independent calls that never cross-check:
            // hand them different glyphs and the result is a silently clipped or
            // garbage bitmap, which the empty-box return above would then present
            // as a legitimately blank glyph carrying a plausible advance.
            stbtt_MakeGlyphBitmap(info, bitmap, width, height, width, scale, scale, glyph);
            return new RasterizedGlyph(bitmap, width, height, x0.get(0), y0.get(0), advancePx);
        }
    }

    /**
     * This face as the shaper sees it, built on first use, or {@code null} when there is no
     * shaper or this is a face it will not open.
     *
     * <p>The handle points into {@link #data} rather than copying it, which is what keeps a
     * shaped face the same resident cost as an unshaped one — and is why {@link #close} destroys
     * it before freeing that buffer, and why nothing else may hold it past this face's life.
     *
     * <p>It is built over {@link #faceIndex}, the same face of the same collection this object
     * measures and rasterizes. Shaping one face and drawing another is not a degraded result, it
     * is a wrong one, and it looks like a font that loaded rather than like a bug.
     */
    HarfBuzzShaper.Handle shaper() {
        if (!shaperTried) {
            shaperTried = true; // set FIRST: a face HarfBuzz rejects must not be retried per string
            if (!closed) {
                shaper = HarfBuzzShaper.createFont(data, faceIndex);
            }
        }
        return shaper;
    }

    /** @return whether {@link #close} has already run; the native buffer is gone if it has */
    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            // Idempotent, because the second free of a native buffer is not an exception: it is a
            // corrupted heap in whichever unrelated allocation later lands on that address.
            return;
        }
        closed = true;
        // Before memFree(data), and that order is the whole contract: the shaper's blob wraps this
        // buffer READONLY without owning it, so freeing the bytes first would leave HarfBuzz
        // reading whatever the allocator hands out next.
        if (shaper != null) {
            shaper.close();
            shaper = null;
        }
        info.free();
        MemoryUtil.memFree(data);
    }
}

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
import static org.lwjgl.stb.STBTruetype.stbtt_GetCodepointBitmapBox;
import static org.lwjgl.stb.STBTruetype.stbtt_GetCodepointHMetrics;
import static org.lwjgl.stb.STBTruetype.stbtt_GetCodepointKernAdvance;
import static org.lwjgl.stb.STBTruetype.stbtt_GetFontVMetrics;
import static org.lwjgl.stb.STBTruetype.stbtt_InitFont;
import static org.lwjgl.stb.STBTruetype.stbtt_MakeCodepointBitmap;
import static org.lwjgl.stb.STBTruetype.stbtt_ScaleForMappingEmToPixels;

/**
 * One loaded TrueType face (stb_truetype). Pure CPU: rasterization returns
 * bitmaps; texture upload belongs to {@link GlyphAtlas}. The font ByteBuffer
 * is native memory that must outlive the {@code STBTTFontinfo}; both are
 * freed in {@link #close()}.
 *
 * <p>Metric/advance values are unquantized floats in the requested pixel
 * size, so measurements scale linearly and are HiDPI-independent. Kerning
 * uses the legacy 'kern' table only (stb_truetype does not read GPOS),
 * documented v1 behavior.
 */
final class StbFont implements AutoCloseable {

    /** Result of rasterizing one glyph: tight bitmap + placement metrics (device px). */
    record RasterizedGlyph(ByteBuffer bitmap, int width, int height,
                           int bearingX, int bearingY, float advance) {
    }

    private final String name;
    private final ByteBuffer data;
    private final STBTTFontinfo info;
    private final int ascentUnits;
    private final int descentUnits;
    private final int lineGapUnits;

    // Unscaled metric caches (font units are size-independent): text layout and
    // drawText used to pay 1-2 JNI crossings per glyph pair per frame just for
    // advances/kerning that never change. Key 0 stays free as the empty slot:
    // code point 0 is an ISO control and never reaches these.
    private static final int MISSING = Integer.MIN_VALUE;
    private final LongIntMap advanceCache = new LongIntMap();
    private final LongIntMap kernCache = new LongIntMap();
    // stbtt_ScaleForMappingEmToPixels memo: strings are drawn/measured at one
    // size at a time, so a single-entry memo removes the per-call JNI hop.
    private float lastSizePx = Float.NaN;
    private float lastScale;
    private boolean closed;

    private StbFont(String name, ByteBuffer data, STBTTFontinfo info,
                    int ascentUnits, int descentUnits, int lineGapUnits) {
        this.name = name;
        this.data = data;
        this.info = info;
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
        // The offset lookup is required even for face 0: in a .ttc collection
        // byte 0 holds the 'ttcf' header, not the face (plain .ttf returns 0).
        int offset = stbtt_GetFontOffsetForIndex(data, Math.max(0, index));
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
            return new StbFont(name, data, info, ascent.get(0), descent.get(0), lineGap.get(0));
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
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp)) {
                previous = -1;
                continue;
            }
            width += advanceUnits(cp) * scale;
            if (previous >= 0) {
                width += kernUnits(previous, cp) * scale;
            }
            previous = cp;
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
        int previous = -1;
        StbFont previousFace = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || isZeroWidthFormat(cp)) {
                previous = -1;
                previousFace = null;
                continue;
            }
            double colored = colorAdvance.applyAsDouble(cp);
            if (!Double.isNaN(colored)) { // a color-emoji glyph carries its own advance
                width += colored;
                previous = -1;
                previousFace = null;
                continue;
            }
            StbFont face = faceFor.apply(cp);
            float scale = face.scaleForSize(sizePx);
            width += face.advanceUnits(cp) * scale; // private, but same class across instances
            if (previousFace == face && previous >= 0) {
                width += face.kernUnits(previous, cp) * scale;
            }
            previous = cp;
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

    /** Kerning adjustment between two code points at {@code sizePx} (0 without a 'kern' table). */
    float kerning(int previousCp, int cp, float sizePx) {
        return kernUnits(previousCp, cp) * scaleForSize(sizePx);
    }

    /** Advance width of one code point at {@code sizePx}. */
    float advance(int cp, float sizePx) {
        return advanceUnits(cp) * scaleForSize(sizePx);
    }

    private int advanceUnits(int cp) {
        int cached = advanceCache.get(cp, MISSING);
        if (cached != MISSING) {
            return cached;
        }
        int units;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer advance = stack.mallocInt(1);
            IntBuffer leftBearing = stack.mallocInt(1);
            stbtt_GetCodepointHMetrics(info, cp, advance, leftBearing);
            units = advance.get(0);
        }
        if (cp != 0) {
            advanceCache.put(cp, units);
        }
        return units;
    }

    private int kernUnits(int previousCp, int cp) {
        long key = ((long) previousCp << 21) | cp; // code points fit in 21 bits
        int cached = kernCache.get(key, MISSING);
        if (cached != MISSING) {
            return cached;
        }
        int units = stbtt_GetCodepointKernAdvance(info, previousCp, cp);
        if (key != 0) {
            kernCache.put(key, units);
        }
        return units;
    }

    /** Minimal open-addressing long→int map: no boxing, no removal (metric caches). */
    private static final class LongIntMap {

        private long[] keys = new long[512];
        private int[] values = new int[512];
        private int count;

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

    boolean hasGlyph(int cp) {
        return stbtt_FindGlyphIndex(info, cp) != 0;
    }

    /**
     * Rasterizes one code point at {@code sizePx}. The returned bitmap is
     * {@code memAlloc}'d; the caller frees it after upload. Whitespace and
     * empty glyphs return a null bitmap with a valid advance.
     */
    RasterizedGlyph rasterize(int cp, float sizePx) {
        float scale = scaleForSize(sizePx);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x0 = stack.mallocInt(1);
            IntBuffer y0 = stack.mallocInt(1);
            IntBuffer x1 = stack.mallocInt(1);
            IntBuffer y1 = stack.mallocInt(1);
            stbtt_GetCodepointBitmapBox(info, cp, scale, scale, x0, y0, x1, y1);
            int width = x1.get(0) - x0.get(0);
            int height = y1.get(0) - y0.get(0);
            float advancePx = advance(cp, sizePx);
            if (width <= 0 || height <= 0) {
                return new RasterizedGlyph(null, 0, 0, 0, 0, advancePx);
            }
            ByteBuffer bitmap = MemoryUtil.memAlloc(width * height);
            stbtt_MakeCodepointBitmap(info, bitmap, width, height, width, scale, scale, cp);
            return new RasterizedGlyph(bitmap, width, height, x0.get(0), y0.get(0), advancePx);
        }
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
        info.free();
        MemoryUtil.memFree(data);
    }
}

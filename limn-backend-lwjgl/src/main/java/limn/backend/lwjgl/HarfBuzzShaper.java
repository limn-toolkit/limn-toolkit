package limn.backend.lwjgl;

import org.lwjgl.util.harfbuzz.hb_glyph_info_t;
import org.lwjgl.util.harfbuzz.hb_glyph_position_t;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_DIRECTION_LTR;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_DIRECTION_RTL;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_MEMORY_MODE_READONLY;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_ARABIC;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_ARMENIAN;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_BENGALI;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_CYRILLIC;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_DEVANAGARI;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_ETHIOPIC;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_GEORGIAN;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_GREEK;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_GUJARATI;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_GURMUKHI;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_HAN;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_HANGUL;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_HEBREW;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_HIRAGANA;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_KANNADA;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_KATAKANA;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_KHMER;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_LAO;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_LATIN;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_MALAYALAM;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_MYANMAR;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_ORIYA;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_SINHALA;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_SYRIAC;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_TAMIL;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_TELUGU;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_THAANA;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_THAI;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_TIBETAN;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_SCRIPT_UNKNOWN;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_blob_create;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_blob_destroy;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_add_utf16;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_create;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_destroy;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_get_glyph_infos;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_get_glyph_positions;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_get_length;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_set_direction;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_set_script;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_face_create;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_face_destroy;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_face_get_upem;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_font_create;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_font_destroy;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_font_set_scale;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_shape;

/**
 * The one place in this repository that knows what HarfBuzz is. Everything above the backend
 * speaks {@link limn.graphics.ShapedText}; everything else in the backend speaks {@link Handle}
 * and {@link Output}, neither of which names a HarfBuzz type.
 *
 * <p><b>What it decides, and what it does not.</b> HarfBuzz chooses <em>which</em> glyph indices a
 * run of characters draws as and <em>where</em> each one sits; {@link StbFont} still rasterizes
 * them. That split is why adding a shaper threw nothing away: stb's raster entry points already
 * take a glyph index.
 *
 * <p><b>Font units, never pixels.</b> A {@link Handle}'s scale is set to the face's own upem, so
 * every advance and offset comes back in the same unscaled space {@link StbFont}'s metric caches
 * live in, and one multiply by {@link StbFont#scaleForSize} converts a whole run. Asking the
 * shaper for pixels instead would make positions depend on the device size, and
 * {@code ShapedText} promises the opposite: a window dragged to a 2&times; display re-rasterizes
 * and re-shapes nothing.
 *
 * <p><b>Absent, not fatal.</b> {@link #isAvailable()} answers once, for the life of the process,
 * and every caller is expected to have a path that works without it: a missing native narrows
 * what the toolkit can draw and never stops it.
 */
final class HarfBuzzShaper {

    private static final System.Logger LOG = System.getLogger(HarfBuzzShaper.class.getName());

    private HarfBuzzShaper() {
    }

    // ------------------------------------------------------------------ availability

    // Three states, and the third is why this is a Boolean and not a boolean: not yet asked, yes,
    // and no. Detection runs once because it is the expensive one (it dlopens a library), and
    // because a native that failed to load will fail identically every time — retrying per shape
    // call would pay the failure over and over inside frames.
    private static Boolean available;

    /**
     * Whether the HarfBuzz native loaded, probed once and logged once.
     *
     * <p>Probing is a real call rather than a class-presence test: the Java jar is on the
     * classpath in every build, and what actually goes missing is the platform-specific native
     * beside it. Only calling something proves the difference.
     */
    static synchronized boolean isAvailable() {
        if (available != null) {
            return available;
        }
        try {
            // Cheapest object HarfBuzz will make. Creating and destroying it forces class
            // initialization, which is what loads the shared library, and proves a round trip.
            long probe = hb_buffer_create();
            if (probe == 0) {
                throw new IllegalStateException("hb_buffer_create returned NULL");
            }
            hb_buffer_destroy(probe);
            available = true;
        } catch (Throwable failure) {
            // Throwable, not Exception: a missing native arrives as UnsatisfiedLinkError, and a
            // static initializer that died on the way arrives as ExceptionInInitializerError or
            // NoClassDefFoundError. Catching only Exception would let all three past.
            available = false;
            LOG.log(System.Logger.Level.WARNING,
                    "HarfBuzz native unavailable; text shaping is degraded to a per-cluster walk. "
                            + "Latin, Greek, Cyrillic and CJK are unaffected; scripts needing "
                            + "contextual forms, ligatures or reordering (Arabic, Hebrew, "
                            + "Devanagari, Thai) render as they did before shaping was added. "
                            + "Reason: " + failure, failure);
        }
        return available;
    }

    // ------------------------------------------------------------------ per-face handle

    /**
     * The blob/face/font trio HarfBuzz needs for one {@link StbFont}, created lazily on that
     * face's first shaping call and destroyed with it.
     *
     * <p><b>The blob does not own the bytes and must not outlive them.</b> It is created
     * {@code HB_MEMORY_MODE_READONLY} over the very {@code ByteBuffer} {@code StbFont} already
     * holds, so there is no second copy of the font file in memory — and so the only safe owner
     * of this object is the face whose buffer it points into. That is why it is closed from
     * {@link StbFont#close()} and nowhere else: freeing the buffer first would leave HarfBuzz
     * reading whatever later lands on that address.
     */
    static final class Handle implements AutoCloseable {

        private final long blob;
        private final long face;
        private final long font;
        private boolean closed;

        private Handle(long blob, long face, long font) {
            this.blob = blob;
            this.face = face;
            this.font = font;
        }

        @Override
        public void close() {
            if (closed) {
                // Idempotent for the same reason StbFont.close is: a second hb_*_destroy is not an
                // exception, it is a corrupted heap somewhere unrelated and much later.
                return;
            }
            closed = true;
            // Reverse creation order. Each holds a reference on the one before it, so this is
            // merely tidy rather than required — but a leak here is a whole font file's worth of
            // native memory per face, which is exactly the size nobody notices until it repeats.
            hb_font_destroy(font);
            hb_face_destroy(face);
            hb_blob_destroy(blob);
        }
    }

    /**
     * Builds the HarfBuzz side of a face over {@code data}, or returns {@code null} when the
     * native is absent or the face is one HarfBuzz will not open.
     *
     * @param data      the font file bytes; the returned handle points into them and must be
     *                  closed before they are freed
     * @param faceIndex which face of a {@code .ttc} collection these bytes are being read as; the
     *                  same index {@link StbFont} resolved to a byte offset, and {@code 0} for a
     *                  plain {@code .ttf}/{@code .otf}
     */
    static Handle createFont(ByteBuffer data, int faceIndex) {
        if (!isAvailable()) {
            return null;
        }
        long blob = 0;
        long face = 0;
        long font = 0;
        try {
            blob = hb_blob_create(data, HB_MEMORY_MODE_READONLY, 0, null);
            if (blob == 0) {
                return null;
            }
            // THE SAME INDEX STB WAS GIVEN, never 0. A .ttc reaches StbFont as a whole file plus
            // an index, and stb resolves that index to a byte offset while HarfBuzz takes the
            // index itself against the whole blob — two spellings of one number, and hard-coding
            // either end to 0 makes the shaper open a DIFFERENT face than the one that measures
            // and rasterizes. That is not a missing feature, it is wrong output: the ids
            // HarfBuzz returns are row numbers in face 0's table and stb draws whatever sits at
            // those rows in face n, so every letter comes out a different letter. Worse, an id
            // past face n's glyph count reaches stbtt_GetGlyphHMetrics, which does not bound-check
            // hmtx and answers from beyond the table. Half of a macOS font catalog is
            // collections, so this is the common path, not an exotic one.
            face = hb_face_create(blob, faceIndex);
            if (face == 0) {
                return null;
            }
            font = hb_font_create(face);
            if (font == 0) {
                return null;
            }
            // Font units in, font units out. hb_face_get_upem is the face's own em, so setting the
            // scale to it makes hb_position_t a font unit — the space StbFont's advance and kern
            // caches already use, so one multiply converts a run and nothing is quantized.
            int upem = hb_face_get_upem(face);
            hb_font_set_scale(font, upem, upem);
            Handle handle = new Handle(blob, face, font);
            blob = 0;
            face = 0;
            font = 0;
            return handle;
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.WARNING, "HarfBuzz could not open a face; it will be "
                    + "shaped by the degraded path: " + failure, failure);
            return null;
        } finally {
            // Non-zero here means we bailed before handing ownership over, so whatever was built
            // has no owner and would leak. After a successful return all three are 0.
            if (font != 0) {
                hb_font_destroy(font);
            }
            if (face != 0) {
                hb_face_destroy(face);
            }
            if (blob != 0) {
                hb_blob_destroy(blob);
            }
        }
    }

    // ------------------------------------------------------------------ shaping

    /**
     * Where one shaped run lands: parallel arrays, grown in place and reused across runs and
     * across calls, because a shaper that allocated per run would allocate per string per frame
     * for the callers that cannot hold their value.
     *
     * <p>Positions are already in logical points and offsets are already
     * {@linkplain limn.graphics.Canvas positive-down}, so the fields hand straight to
     * {@code ShapedText.Builder.glyph} with no arithmetic between.
     */
    static final class Output {

        int[] glyphIds = new int[64];
        /** Char offsets into the <b>whole string</b>, never into the run. */
        int[] clusters = new int[64];
        float[] advances = new float[64];
        float[] xOffsets = new float[64];
        float[] yOffsets = new float[64];
        int count;

        private void ensure(int needed) {
            if (glyphIds.length >= needed) {
                return;
            }
            int size = Math.max(needed, glyphIds.length * 2);
            glyphIds = java.util.Arrays.copyOf(glyphIds, size);
            clusters = java.util.Arrays.copyOf(clusters, size);
            advances = java.util.Arrays.copyOf(advances, size);
            xOffsets = java.util.Arrays.copyOf(xOffsets, size);
            yOffsets = java.util.Arrays.copyOf(yOffsets, size);
        }
    }

    /**
     * Shapes {@code text[start, end)} through {@code handle} and fills {@code out}.
     *
     * <p><b>The whole string goes in as context; only the run is shaped.</b> That is what
     * {@code item_offset}/{@code item_length} are for, and it buys two things that are not
     * optional. An Arabic run keeps its joining forms across a boundary the itemizer drew for a
     * font change, because HarfBuzz can see the characters on either side. And <b>the cluster
     * values come back as offsets into the whole string</b> rather than into the run, which is
     * the entire off-by-one class this seam is famous for, removed by construction instead of by
     * an addition somebody has to remember. {@code ShapedText.Builder.glyph} demands whole-string
     * offsets and rejects anything else, so the two ends agree by contract.
     *
     * @param handle the face to shape with; its glyph ids mean nothing in any other face
     * @param text   the whole line, passed as context
     * @param start  first char of the run to shape
     * @param end    one past the last
     * @param script the HarfBuzz script tag from {@link #scriptTag}
     * @param rtl    whether this run reads right to left
     * @param scale  font units to logical points, from {@link StbFont#scaleForSize}
     * @param out    filled with {@code out.count} glyphs in the run's own visual order
     * @return whether shaping produced an answer; {@code false} leaves {@code out} untouched and
     *         asks the caller for its degraded path
     */
    static boolean shapeRun(Handle handle, String text, int start, int end, int script,
                            boolean rtl, float scale, Output out) {
        if (handle == null || handle.closed) {
            return false;
        }
        long buffer = 0;
        try {
            buffer = hb_buffer_create();
            if (buffer == 0) {
                return false;
            }
            // text is the CONTEXT and (start, end - start) is the item: see the method note. The
            // CharSequence overload copies to native memory itself, so no manual encode.
            hb_buffer_add_utf16(buffer, text, start, end - start);
            hb_buffer_set_direction(buffer, rtl ? HB_DIRECTION_RTL : HB_DIRECTION_LTR);
            // Set, never guessed. hb_buffer_guess_segment_properties would re-derive the script
            // from the first strong character of the item, which disagrees with the itemizer for
            // exactly the runs that were split BY script — and disagreeing quietly is how a run
            // reaches the generic shaper.
            hb_buffer_set_script(buffer, script);
            hb_shape(handle.font, buffer, null);

            int count = hb_buffer_get_length(buffer);
            out.ensure(count);
            out.count = count;
            if (count == 0) {
                return true;
            }
            hb_glyph_info_t.Buffer infos = hb_buffer_get_glyph_infos(buffer);
            hb_glyph_position_t.Buffer positions = hb_buffer_get_glyph_positions(buffer);
            for (int i = 0; i < count; i++) {
                hb_glyph_info_t info = infos.get(i);
                hb_glyph_position_t position = positions.get(i);
                // codepoint() is a GLYPH INDEX here, not a character: the field is reused across
                // the buffer's life and after hb_shape it holds the id of the glyph the face
                // draws. Reading it as a character produces plausible-looking nonsense.
                out.glyphIds[i] = info.codepoint();
                out.clusters[i] = info.cluster();
                out.advances[i] = position.x_advance() * scale;
                out.xOffsets[i] = position.x_offset() * scale;
                // Negated: HarfBuzz measures mark attachment positive-UP from the baseline and
                // every y on a limn Canvas is positive-DOWN. Without the sign flip an accent is
                // placed under the letter it belongs over, which looks like a font bug.
                out.yOffsets[i] = -position.y_offset() * scale;
            }
            return true;
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.WARNING,
                    "HarfBuzz failed to shape a run; it falls back to the degraded path: "
                            + failure, failure);
            return false;
        } finally {
            if (buffer != 0) {
                hb_buffer_destroy(buffer);
            }
        }
    }

    // ------------------------------------------------------------------ script tags

    /**
     * The HarfBuzz tag for a Unicode script, or {@link #HB_SCRIPT_UNKNOWN} for one this table does
     * not name and for {@code null}, which is a run that has no script at all.
     *
     * <p><b>Every value here comes from an {@code HB_SCRIPT_*} constant and none is spelled by
     * hand.</b> A HarfBuzz script tag is the ISO 15924 code with its <em>first letter
     * capitalised</em> — {@code Deva}, not {@code deva} — and the two differ by one bit that
     * nothing checks: a lowercase tag is not a registered script, so HarfBuzz silently selects
     * the generic shaper, and Devanagari comes back unreordered and unligated with no error
     * anywhere. Measured, on this project, before this table was written.
     *
     * @param script the run's script, or {@code null} for a run made entirely of characters that
     *               have none of their own
     */
    static int scriptTag(Character.UnicodeScript script) {
        // Total in `script`, and it has to be: the itemizer says "no script" with null, because
        // COMMON and INHERITED characters take the script of their neighbours rather than opening
        // a run — and a run that never meets a neighbour with one is every string made only of
        // digits, punctuation, spaces or symbols. "42", "100%", "12:30", a lone separator, the
        // digits between two Hebrew words. HB_SCRIPT_UNKNOWN is the generic shaper, which is
        // exactly right for characters with no script-specific behaviour to apply.
        //
        // The guard stands IN FRONT of the lookup and cannot be folded into it: BY_SCRIPT is a
        // Map.ofEntries, and an immutable map throws NullPointerException on a null key from
        // every read path — get and getOrDefault alike — rather than answering "absent". The
        // `tag != null` test below never gets a chance to run.
        if (script == null) {
            return Tags.UNKNOWN;
        }
        Integer tag = Tags.BY_SCRIPT.get(script);
        return tag != null ? tag : Tags.UNKNOWN;
    }

    /**
     * The table, in a holder class <b>so that naming it does not load the native</b>.
     *
     * <p>{@code HB_SCRIPT_*} are computed at class-initialization time rather than being
     * compile-time constants, so a field of the outer class holding one would drag HarfBuzz's
     * static initializer into {@code HarfBuzzShaper}'s. On a machine with no native that turns
     * every entry point here — {@link #isAvailable()} included — into an
     * {@code ExceptionInInitializerError} thrown before the {@code try} that exists to catch it,
     * and the degraded path would be unreachable precisely when it is the only path. A nested
     * class initializes on first touch instead, and nothing touches this until
     * {@link #isAvailable()} has already said yes.
     */
    private static final class Tags {

        static final int UNKNOWN = HB_SCRIPT_UNKNOWN;

        // The scripts a UI plausibly draws, which is not the same as the scripts HarfBuzz knows.
        // Anything absent falls to HB_SCRIPT_UNKNOWN, which is the generic shaper: correct for a
        // script with no contextual behaviour, and merely unimproved for one that has some.
        static final Map<Character.UnicodeScript, Integer> BY_SCRIPT = Map.ofEntries(
                Map.entry(Character.UnicodeScript.LATIN, HB_SCRIPT_LATIN),
                Map.entry(Character.UnicodeScript.GREEK, HB_SCRIPT_GREEK),
                Map.entry(Character.UnicodeScript.CYRILLIC, HB_SCRIPT_CYRILLIC),
                Map.entry(Character.UnicodeScript.ARMENIAN, HB_SCRIPT_ARMENIAN),
                Map.entry(Character.UnicodeScript.GEORGIAN, HB_SCRIPT_GEORGIAN),
                Map.entry(Character.UnicodeScript.HEBREW, HB_SCRIPT_HEBREW),
                Map.entry(Character.UnicodeScript.ARABIC, HB_SCRIPT_ARABIC),
                Map.entry(Character.UnicodeScript.SYRIAC, HB_SCRIPT_SYRIAC),
                Map.entry(Character.UnicodeScript.THAANA, HB_SCRIPT_THAANA),
                Map.entry(Character.UnicodeScript.DEVANAGARI, HB_SCRIPT_DEVANAGARI),
                Map.entry(Character.UnicodeScript.BENGALI, HB_SCRIPT_BENGALI),
                Map.entry(Character.UnicodeScript.GURMUKHI, HB_SCRIPT_GURMUKHI),
                Map.entry(Character.UnicodeScript.GUJARATI, HB_SCRIPT_GUJARATI),
                Map.entry(Character.UnicodeScript.ORIYA, HB_SCRIPT_ORIYA),
                Map.entry(Character.UnicodeScript.TAMIL, HB_SCRIPT_TAMIL),
                Map.entry(Character.UnicodeScript.TELUGU, HB_SCRIPT_TELUGU),
                Map.entry(Character.UnicodeScript.KANNADA, HB_SCRIPT_KANNADA),
                Map.entry(Character.UnicodeScript.MALAYALAM, HB_SCRIPT_MALAYALAM),
                Map.entry(Character.UnicodeScript.SINHALA, HB_SCRIPT_SINHALA),
                Map.entry(Character.UnicodeScript.THAI, HB_SCRIPT_THAI),
                Map.entry(Character.UnicodeScript.LAO, HB_SCRIPT_LAO),
                Map.entry(Character.UnicodeScript.TIBETAN, HB_SCRIPT_TIBETAN),
                Map.entry(Character.UnicodeScript.MYANMAR, HB_SCRIPT_MYANMAR),
                Map.entry(Character.UnicodeScript.KHMER, HB_SCRIPT_KHMER),
                Map.entry(Character.UnicodeScript.ETHIOPIC, HB_SCRIPT_ETHIOPIC),
                Map.entry(Character.UnicodeScript.HAN, HB_SCRIPT_HAN),
                Map.entry(Character.UnicodeScript.HIRAGANA, HB_SCRIPT_HIRAGANA),
                Map.entry(Character.UnicodeScript.KATAKANA, HB_SCRIPT_KATAKANA),
                Map.entry(Character.UnicodeScript.HANGUL, HB_SCRIPT_HANGUL));

        private Tags() {
        }
    }
}

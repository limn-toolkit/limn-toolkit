package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The backend's {@link TextRuler}: shapes through HarfBuzz, and measures by shaping.
 *
 * <p><b>There is one answer, not two.</b> {@link #measure} is {@code shape(…).metrics()}, so the
 * width a layout pass is built from is the width the paint pass walks — the same floats, out of the
 * same run of arithmetic. The two used to be computed independently and were allowed to disagree,
 * which is a bug generator rather than a saving: the disagreement is invisible on Latin and grows
 * linearly with the word count on a script whose faces differ about a space.
 *
 * <p><b>Itemization, in the order the pieces depend on each other.</b> {@code java.text.Bidi} over
 * the whole string gives an embedding level per character and the runs of equal level. Each of
 * those is split by script, with {@code COMMON} and {@code INHERITED} characters extending the run
 * they follow rather than opening one — a comma between two Arabic words belongs to the Arabic
 * run. Each script run then resolves a face <em>once</em> and extends while that face has
 * coverage, which is the step that cannot be reordered: shaping needs to know the face before any
 * glyph exists, and a run split mid-word by a per-character fallback decision shapes as two words.
 * The resulting runs go to {@link ShapedText.Builder} in logical order, and it does rule L2.
 *
 * <p><b>Both paths come through one door.</b> Whether a run is shaped by HarfBuzz or measured by
 * the degraded walk, it reaches the same builder with the same kind of run and the same absolute
 * cluster offsets, so the two cannot disagree about where a caret goes. What differs is only what
 * fills a run: real glyph ids and GPOS placement, or one {@link ShapedText#NO_GLYPH} per grapheme
 * cluster carrying the advance the per-code-point walk would have given it.
 *
 * <p>UI-thread confined, like everything that reaches {@link FontStore}: the memo and the reusable
 * shaping buffers are unsynchronized.
 */
final class ShapingRuler implements TextRuler {

    /**
     * Entries in the shape memo.
     *
     * <p>It was 64 while this memo served only the callers that <em>cannot</em> hold a value — a
     * chart rebuilding its axis labels every frame — and a widget holding its own
     * {@code ShapedText} never reached it twice. {@link #measure} routing through {@link #shape}
     * changed what it is for: every layout pass of every widget now arrives here, twice per string
     * per frame (measure, then paint), and a screen's worth of captions is hundreds of distinct
     * strings, not tens. At 64 a kitchen-sink screen evicted its own text between the measure and
     * the paint and re-shaped both times; the working set has to fit or the memo is a counter of
     * misses.
     *
     * <p>512 is that working set with room over it, and it is bounded by what it holds rather than
     * chosen round: a short shaped line is a few hundred bytes of arrays, so the whole memo is
     * well under a megabyte against the 27 MB of faces this backend already carries.
     *
     * <p><b>It is not a document cache, and no capacity would make it one.</b> A scan over every
     * line of a long document walks its keys cyclically, so it misses every entry every time the
     * document is longer than the memo — and, because the memo is shared, it evicts the screen's
     * own strings on the way past, which turns one widget's scan into every other widget's cold
     * repaint. That is a cliff and not a slope, and raising the number only moves it. So a scan of
     * that kind does not come here at all: {@link #scanWidth} is the width taken without shaping
     * and without remembering, and it is what a caller scanning like that is required to ask.
     */
    private static final int MEMO_ENTRIES = 512;

    private final FontStore fonts;

    /**
     * Keyed by text, font and direction, and <b>not</b> by device size.
     *
     * <p>The {@link Font} carries family, size, weight and slant, so it subsumes both the face
     * this resolves to and the size the positions are in. Leaving the <em>device</em> size out is
     * the content-scale contract, not an oversight: shaped positions are unquantized logical
     * points in font units, so a window crossing a monitor boundary re-rasterizes glyph bitmaps
     * and must re-shape nothing — and a quantized device size in this key would miss the memo for
     * every string in the process at exactly that moment.
     */
    private record Key(String text, Font font, ShapedText.Direction base) {
    }

    // Access-ordered, so the eldest ENTRY is the least recently used one rather than the oldest
    // inserted: an axis label redrawn every frame must not age out behind a label drawn once.
    private final LinkedHashMap<Key, ShapedText> memo =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, ShapedText> eldest) {
                    return size() > MEMO_ENTRIES;
                }
            };

    // The epoch the memo's contents were shaped under. Comparing it on the way IN is what lets a
    // resolution change cost one counter increment instead of a walk: nothing is cleared when the
    // change happens, and a stale entry still can never be handed out, because the first lookup
    // afterwards throws the whole generation away.
    private long memoEpoch;

    // Reused across runs and across calls: one shaped run's worth of glyphs, grown in place. A
    // per-run allocation here would be a per-string allocation per frame for exactly the callers
    // the memo above exists to protect.
    private final HarfBuzzShaper.Output shaped = new HarfBuzzShaper.Output();

    // How a face becomes a shaper. StbFont::shaper in production; the seam exists because the one
    // thing this class must never get wrong is what it does when there is NO shaper, and that
    // branch is otherwise reachable only on a machine where the native is missing — which is to
    // say, never on the machine anybody runs the tests on. Same shape as FontStore's loaders.
    private final java.util.function.Function<StbFont, HarfBuzzShaper.Handle> shaperFor;

    ShapingRuler(FontStore fonts) {
        this(fonts, StbFont::shaper);
    }

    /**
     * Package-private with the shaper lookup passed in, for the test that exercises the degraded
     * path: handing back {@code null} for every face is exactly what a missing native does, one
     * branch further in.
     */
    ShapingRuler(FontStore fonts,
                 java.util.function.Function<StbFont, HarfBuzzShaper.Handle> shaperFor) {
        this.fonts = fonts;
        this.shaperFor = shaperFor;
    }

    /**
     * The <b>shaped</b> width, so that layout and paint cannot disagree.
     *
     * <p>This was the per-code-point sum beside {@code shape} rather than through it, and the two
     * genuinely differed: measuring resolves a face per character, shaping resolves one per run and
     * lets a neutral character keep the company it is in, so the space between two Hebrew words was
     * measured in the Latin primary and shaped in the Hebrew face. That gap is per word seam, it
     * accumulates down a line, and it cost a real bug — a scroll extent that stopped short of where
     * the shaping put the caret, worked around in {@code TextArea} rather than cured. Answering
     * from {@code shape} cures it at the root: the number layout is built from is the number paint
     * walks, because it is the same number.
     *
     * <p>The direction is resolved from the text, not stated, because that is what
     * {@code drawText(String, …)} does with the same string one pass later; a measurement taken
     * under one base direction and a painting under another is the divergence this method exists to
     * remove, in a subtler form.
     *
     * <p>Cost is the memo's problem, and it is why {@link #MEMO_ENTRIES} is what it is: the first
     * measurement of a string shapes it, and the paint that follows finds it there.
     */
    @Override
    public TextMetrics measure(String text, Font font) {
        // Null still measures as the empty line, as it did when this went straight to the store.
        // shape() rejects it — a null paragraph has no direction to resolve — so the tolerance has
        // to be restated here rather than inherited, and dropping it would turn a widget with no
        // text yet from a zero-width line into a NullPointerException mid-layout.
        return shape(text == null ? "" : text, font).metrics();
    }

    /**
     * The per-code-point sum, straight from the store: the one width this class answers without
     * shaping, and the one it answers without remembering.
     *
     * <p>It is not a relapse into the two-answers world {@link #measure} was moved out of. The
     * difference is who is asking. {@code measure} is asked about strings a frame is about to
     * paint, and shaping them is how layout and paint come out equal — the memo pays for itself
     * twice per string per frame. This is asked about text nobody is drawing, once per line of a
     * whole document, by a scroll extent; routing that through the memo re-shapes the document on
     * every keystroke and evicts the screen while doing it, because an access-ordered cache walked
     * cyclically over more keys than it holds is a miss counter. A width that agrees exactly with
     * the shaping is precisely the width that costs a shaping, and the caller for this one has
     * said it would rather be a little wrong and cheap. {@code TextArea} reconciles the difference
     * against the lines it has actually shaped; see {@link TextRuler#scanWidth}.
     *
     * <p>UI-thread checked like {@link #shape}, because it reaches the same store: the check is
     * restated here rather than inherited, since this is the one entry point that does not go
     * through {@code shape} to get it.
     */
    @Override
    public float scanWidth(String text, Font font) {
        java.util.Objects.requireNonNull(font, "font");
        limn.concurrent.Ui.checkUiThread();
        return fonts.measure(font, text).width();
    }

    @Override
    public long epoch() {
        return fonts.epoch();
    }

    @Override
    public ShapedText shape(String text, Font font, ShapedText.Direction base) {
        java.util.Objects.requireNonNull(font, "font");
        java.util.Objects.requireNonNull(base, "base");
        String value = text == null ? "" : text;
        limn.concurrent.Ui.checkUiThread();

        // Resolve BEFORE reading the epoch, because resolving is one of the things that moves it:
        // the first use of a bundled style variant parses that face, and a value stamped with the
        // number read a line earlier would be born stale — it would report itself out of date on
        // the very next frame, and re-shape forever.
        fonts.resolve(font);
        long now = fonts.epoch();
        if (memoEpoch != now) {
            // A face was registered, evicted, or the default family changed. Everything in here
            // was shaped against the old answer; none of it may be handed out again.
            memo.clear();
            memoEpoch = now;
        }
        Key key = new Key(value, font, base);
        ShapedText hit = memo.get(key);
        if (hit != null) {
            return hit;
        }
        ShapedText line = shapeUncached(value, font, base, now);
        memo.put(key, line);
        return line;
    }

    private ShapedText shapeUncached(String text, Font font, ShapedText.Direction base,
                                     long epoch) {
        StbFont primary = fonts.resolve(font);
        float size = font.size();
        // Vertical metrics are the PRIMARY face's whatever falls back, so a line of Latin with one
        // CJK glyph in it keeps the line height the rest of the paragraph has. Measuring the empty
        // string is how those three numbers are read without walking the text twice.
        TextMetrics vertical = primary.measure("", size);
        // A shaper may emit more glyphs than the string has characters (a decomposed vowel sign,
        // a mark that was not there before), so this is an estimate and not a bound; getting it
        // wrong costs one array copy and can never cost a wrong answer.
        ShapedText.Builder builder = ShapedText.builder(text, font, base, text.length() + 8)
                .lineMetrics(vertical.ascent(), vertical.descent(), vertical.lineHeight())
                .epoch(epoch);
        if (text.isEmpty()) {
            return builder.build();
        }

        Bidi bidi = new Bidi(text, base == ShapedText.Direction.RTL
                ? Bidi.DIRECTION_RIGHT_TO_LEFT
                : Bidi.DIRECTION_LEFT_TO_RIGHT);
        int bidiRuns = bidi.getRunCount();
        for (int r = 0; r < Math.max(1, bidiRuns); r++) {
            // Bidi reports its runs in LOGICAL order, which is the order the builder wants; it is
            // the builder that reorders them for the screen. The zero-run branch is a guard, not a
            // case Bidi produces for a non-empty paragraph.
            int runStart = bidiRuns == 0 ? 0 : bidi.getRunStart(r);
            int runEnd = bidiRuns == 0 ? text.length() : bidi.getRunLimit(r);
            int level = bidiRuns == 0
                    ? (base == ShapedText.Direction.RTL ? 1 : 0)
                    : bidi.getRunLevel(r);
            itemizeBidiRun(builder, text, runStart, runEnd, level, primary, font, size);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ itemization

    /**
     * Splits one bidi run into (script, face) runs and hands each to the shaper or the degraded
     * walk. Everything here is in logical order; visual order is the builder's business, except
     * <em>within</em> a run, where the glyphs of a right-to-left run are emitted right to left.
     */
    private void itemizeBidiRun(ShapedText.Builder builder, String text, int runStart, int runEnd,
                                int level, StbFont primary, Font font, float size) {
        boolean rtl = (level & 1) != 0;
        int at = runStart;
        while (at < runEnd) {
            int cp = text.codePointAt(at);
            if (isDrawnWithoutAGlyph(primary, cp)) {
                // A stretch of characters this pipeline never asks a shaper about: control
                // characters, which carry no advance and no ink, and colour-emoji strikes, which
                // are bitmaps with their own advance and always were. They share one run because
                // they share one answer — NO_GLYPH — and a run per emoji would inflate the bound
                // that sizes a caller's selection buffer for no gain.
                int end = at;
                while (end < runEnd) {
                    int c = text.codePointAt(end);
                    if (!isDrawnWithoutAGlyph(primary, c)) {
                        break;
                    }
                    end += Character.charCount(c);
                }
                builder.run(fonts.faceId(primary), at, end, level);
                emitGlyphlessRun(builder, text, at, end, primary, size, rtl);
                at = end;
                continue;
            }

            // One face for the whole run, resolved from its first character and kept while it
            // covers what follows. Resolving per character instead is what splits a word in two
            // and shapes the halves independently.
            StbFont face = fonts.faceForCodepoint(primary, cp);
            Character.UnicodeScript script = strongScript(cp);
            int end = at;
            while (end < runEnd) {
                int c = text.codePointAt(end);
                if (isDrawnWithoutAGlyph(primary, c)) {
                    break;
                }
                Character.UnicodeScript s = strongScript(c);
                if (s != null) {
                    if (script == null) {
                        // The run opened on COMMON or INHERITED characters; the first script to
                        // show up adopts them rather than being made to start a new run.
                        script = s;
                    } else if (s != script) {
                        break;
                    }
                }
                if (!staysWithFace(face, primary, c)) {
                    break;
                }
                end += Character.charCount(c);
            }
            builder.run(fonts.faceId(face), at, end, level);
            shapeOrDegrade(builder, text, at, end, face, script, rtl, size);
            at = end;
        }
    }

    /**
     * The script a character contributes to run splitting, or {@code null} for one that extends
     * whatever run it lands in.
     *
     * <p>{@code COMMON} (spaces, digits, most punctuation) and {@code INHERITED} (combining marks)
     * have no script of their own: they take the script of their neighbours. Letting either open
     * a run would cut every Arabic sentence at its spaces and detach every mark from the letter it
     * belongs to, and a shaper handed the two halves separately produces the isolated forms.
     */
    private static Character.UnicodeScript strongScript(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.COMMON
                || script == Character.UnicodeScript.INHERITED
                || script == Character.UnicodeScript.UNKNOWN
                ? null
                : script;
    }

    /** Whether {@code cp} belongs in a run already resolved to {@code face}. */
    private boolean staysWithFace(StbFont face, StbFont primary, int cp) {
        // The second test is what keeps a stretch of characters NO face can draw in one run
        // instead of one run each: faceForCodepoint answers the primary as a last resort, and a
        // row of .notdef boxes is still one run of one face.
        return face.hasGlyph(cp) || fonts.faceForCodepoint(primary, cp) == face;
    }

    /**
     * Whether this pipeline draws {@code cp} by some means other than a glyph from a face, and so
     * must keep it away from the shaper.
     */
    private boolean isDrawnWithoutAGlyph(StbFont primary, int cp) {
        // Every question here is about the CHARACTER and stays above the cmap, exactly as the
        // per-code-point path has them: this face maps several controls to real glyphs, so asking
        // below the lookup could not tell a control from a character it happens to draw.
        if (Character.isISOControl(cp)) {
            return true;
        }
        // A zero-width format character is drawn by nobody and charged by nobody: the measure walk
        // and both paint loops skip it above the cmap. So it must not pick up a colour-emoji
        // strike's advance here either — the bundled emoji font DOES cover U+200D, and a primary
        // face that lacks the joiner would otherwise charge it the width of an emoji on a line
        // that measure() gives no width to at all.
        //
        // Returning false sends it onward to the shaper, which is the point and not an accident:
        // ZWJ, ZWNJ and the variation selectors are what tell HarfBuzz whether to join, so they
        // have to reach it, and it hides default ignorables itself at zero advance. The rule is
        // that it may reach a shaper and may never reach a path that charges for it — this branch,
        // or emitMeasuredRun, which drops it for the same reason.
        if (StbFont.isZeroWidthFormat(cp)) {
            return false;
        }
        return !primary.hasGlyph(cp) && fonts.colorEmojiGlyph(cp) != null;
    }

    // ------------------------------------------------------------------ the two run fillers

    /** Shapes one run, or falls back to the degraded walk for it and only it. */
    private void shapeOrDegrade(ShapedText.Builder builder, String text, int from, int to,
                                StbFont face, Character.UnicodeScript script, boolean rtl,
                                float size) {
        HarfBuzzShaper.Handle handle = shaperFor.apply(face);
        if (handle != null) {
            // Tag from the HB_SCRIPT_* constants, never spelled by hand: a lowercase tag is not a
            // registered script and selects the generic shaper in silence.
            int tag = HarfBuzzShaper.scriptTag(script);
            float scale = face.scaleForSize(size);
            if (HarfBuzzShaper.shapeRun(handle, text, from, to, tag, rtl, scale, shaped)) {
                for (int g = 0; g < shaped.count; g++) {
                    // Clusters are already offsets into the WHOLE string, because the whole string
                    // went in as context: nothing is added back here, and nothing may be. The
                    // builder rejects an offset outside the open run, so a regression that
                    // reintroduced the shift would fail at this call rather than move every caret.
                    builder.glyph(shaped.glyphIds[g], shaped.clusters[g], shaped.advances[g],
                            shaped.xOffsets[g], shaped.yOffsets[g]);
                }
                return;
            }
        }
        emitMeasuredRun(builder, text, from, to, face, size, rtl);
    }

    /**
     * The degraded filler: one {@link ShapedText#NO_GLYPH} per grapheme cluster, carrying the
     * advance the per-code-point walk would have measured for it, kerning included.
     *
     * <p>This is what the whole line becomes when the native is absent, and what one run becomes
     * when a face is one HarfBuzz will not open. It keeps kerning rather than inheriting the
     * interface's own per-cluster default, so a Latin line degrades to <em>exactly</em> the width
     * it has today rather than to one a hair wider at every kerned pair.
     */
    private void emitMeasuredRun(ShapedText.Builder builder, String text, int from, int to,
                                 StbFont face, float size, boolean rtl) {
        int[] bounds = clusterBounds(text, from, to);
        int cells = bounds.length - 1;
        float[] advances = new float[cells];
        int previousGlyph = -1; // -1, never 0: 0 is .notdef and kerns legitimately
        for (int cell = 0; cell < cells; cell++) {
            float advance = 0;
            for (int i = bounds[cell]; i < bounds[cell + 1]; ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                // The one filter StbFont.measureWithFallback applies that this walk would
                // otherwise not, and the two have to agree character for character or the whole
                // claim of this method is false. A variation selector or a joiner the face has no
                // glyph for answers .notdef from the cmap — a real, drawable glyph with a real
                // advance — and charging that box for a character nothing ever draws makes the
                // degraded line 0.44 em wider per selector than measure() says, then paints a hole
                // there, because the painter skips exactly these. HarfBuzz hides default
                // ignorables itself, so the shaped path needs no such filter; that is precisely
                // why this one is easy to leave out and impossible to notice on Latin.
                //
                // isISOControl is absent on purpose rather than by oversight: a control never
                // reaches this walk, because isDrawnWithoutAGlyph routes it to emitGlyphlessRun.
                if (StbFont.isZeroWidthFormat(cp)) {
                    previousGlyph = -1; // no glyph, so no kern pair across it — as the walk has it
                    continue;
                }
                int glyph = face.glyphIndex(cp);
                // The kern at a cluster seam is charged to the cluster that FOLLOWS it, so the pen
                // stays continuous and the boxes still tile: the line's total is the same number
                // the per-code-point walk produces, wherever the boundaries fall.
                if (previousGlyph >= 0) {
                    advance += face.glyphKerning(previousGlyph, glyph, size);
                }
                advance += face.glyphAdvance(glyph, size);
                previousGlyph = glyph;
            }
            advances[cell] = advance;
        }
        for (int step = 0; step < cells; step++) {
            // Emitted in the run's own VISUAL order, which for an odd level is its clusters
            // backwards. That is the order the builder's pen expects and the order a shaper would
            // have handed over, so the degraded path and the shaped one place a run identically.
            int cell = rtl ? cells - 1 - step : step;
            builder.glyph(ShapedText.NO_GLYPH, bounds[cell], advances[cell], 0, 0);
        }
    }

    /**
     * The filler for characters no face draws: a control at zero advance, a colour-emoji strike at
     * the advance its own font declares. One glyph per code point, because each is its own cluster
     * and each has its own answer.
     */
    private void emitGlyphlessRun(ShapedText.Builder builder, String text, int from, int to,
                                  StbFont primary, float size, boolean rtl) {
        int[] starts = new int[to - from];
        float[] advances = new float[to - from];
        int count = 0;
        for (int i = from; i < to; ) {
            int cp = text.codePointAt(i);
            starts[count] = i;
            advances[count] = Character.isISOControl(cp)
                    ? 0
                    : (float) fonts.colorEmojiAdvance(cp, size);
            count++;
            i += Character.charCount(cp);
        }
        for (int step = 0; step < count; step++) {
            int cell = rtl ? count - 1 - step : step;
            builder.glyph(ShapedText.NO_GLYPH, starts[cell], advances[cell], 0, 0);
        }
    }

    /**
     * Grapheme cluster boundaries within {@code [from, to)}, as offsets into the whole string,
     * always beginning at {@code from} and ending at {@code to}.
     *
     * <p>The iterator is given the <em>whole</em> string and the results clipped, rather than a
     * substring: a break iterator asked about a fragment cannot see the character before it and
     * will break where the full text does not.
     */
    private static int[] clusterBounds(String text, int from, int to) {
        BreakIterator clusters = BreakIterator.getCharacterInstance();
        clusters.setText(text);
        int[] bounds = new int[to - from + 1];
        int count = 0;
        bounds[count++] = from;
        for (int at = clusters.following(from);
                at != BreakIterator.DONE && at < to;
                at = clusters.next()) {
            bounds[count++] = at;
        }
        bounds[count++] = to;
        return java.util.Arrays.copyOf(bounds, count);
    }
}

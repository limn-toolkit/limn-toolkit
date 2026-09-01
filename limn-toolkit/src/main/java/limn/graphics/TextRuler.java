package limn.graphics;

import java.text.Bidi;
import java.text.BreakIterator;

/**
 * Measures text without a frame in flight: what widget layout uses (the
 * layout pass runs before painting, when no {@link Canvas} exists). Metrics
 * are logical points, identical to {@link Canvas#measureText}. Backends
 * install their ruler in {@link TextRulers} at startup; tests inject
 * deterministic fakes.
 */
@FunctionalInterface
public interface TextRuler {

    /** A ruler that measures everything as zero (detached widgets, tests). */
    TextRuler NONE = (text, font) -> new TextMetrics(0, 0, 0, 0);

    /**
     * Measures {@code text} in {@code font}, in logical points, <b>on the UI thread</b>. An
     * implementation may assume that confinement and keep unsynchronized caches; layout, which is
     * the main caller, runs there anyway.
     *
     * <p>There is deliberately no asynchronous form, and one consequence has to be stated rather
     * than discovered. An implementation is allowed to resolve a face on first use, so the first
     * measurement in a family it has not yet loaded may read and parse a font file (tens of
     * megabytes for a CJK face) on this thread, inside whatever frame asked. Since measurement
     * cannot leave the UI thread, that cost cannot be moved off it either; the only lever a caller
     * has is <em>when</em> a new family is first measured, which is why
     * {@link Fonts#setDefaultFamily} belongs on a settings action and not in an animation.
     *
     * <p><b>An implementation that shapes should answer here what {@link #shape} answers</b>, and
     * the backend's does &mdash; its {@code measure} <em>is</em> {@code shape(…).metrics()}. A
     * ruler that computes the two independently is allowed to, and will drift: measuring per code
     * point resolves a face per character while shaping resolves one per run, so the two disagree
     * about a space between two Hebrew words, per seam, accumulating down the line. That is not a
     * hairline, it is a caret painted outside the clip. This is a "should" rather than a contract
     * because the {@code shape} default below cannot honour it: it measures one grapheme cluster at
     * a time and so loses the kern at every cluster seam. A caller that must not be caught by the
     * gap &mdash; sizing a scroll extent from {@code measure} and painting from {@code shape} &mdash;
     * has to reconcile the two itself, as {@code TextArea} does.
     *
     * @param text the string to measure; empty is legal and measures as zero width
     * @param font the face and size to measure in
     */
    TextMetrics measure(String text, Font font);

    /**
     * The width of {@code text} in {@code font} for a caller measuring <em>far more strings than it
     * will draw</em>: cheap, allowed to be approximate, and required to leave no trace in whatever
     * the ruler remembers.
     *
     * <p><b>Why this is not {@link #measure}.</b> A shaping ruler answers {@code measure} by
     * shaping and memoizing, which is right for the strings a frame is about to paint: the layout
     * pass warms the memo and the paint pass finds them in it. It is exactly wrong for a scan over
     * text nobody is drawing — a scroll extent that has to look at every line of a document. That
     * scan walks its key set cyclically, so past the memo's depth it misses every entry every time
     * and re-shapes the whole document; and because the memo is process-wide, it also evicts the
     * strings that <em>are</em> on the screen, so the damage lands on widgets that did nothing. A
     * bounded cache in front of an unbounded scan is not a cache, and the fix cannot live in the
     * cache. So the scan asks a different question, and this is it.
     *
     * <p><b>What is given up, stated so a caller cannot be surprised by it.</b> This may disagree
     * with {@link #shape}'s width, in either direction and by more than a rounding: an
     * implementation is free to sum per-character advances, which misses a ligature (narrower when
     * shaped) and misses a run's face resolution (wider when shaped, per word seam, accumulating).
     * A caller that sizes something a caret must stay inside cannot treat this as an upper bound and
     * has to reconcile it with widths it has actually shaped, as {@code TextArea} does. A caller
     * that would rather be exact than cheap wants {@code measure(text, font).width()} and should
     * call it.
     *
     * <p>Same thread and the same first-use costs as {@link #measure}. The default is
     * {@code measure(text, font).width()}, which is always <em>correct</em> and is the right answer
     * for a ruler with nothing cheaper to offer &mdash; including every fake, which is why this is a
     * default and this interface is still one a lambda can satisfy.
     *
     * @param text the string to measure; empty is legal and scans as zero width
     * @param font the face and size to measure in
     */
    default float scanWidth(String text, Font font) {
        return measure(text, font).width();
    }

    // --------------------------------------------------------------------- shaping

    /**
     * Shapes one line of {@code text} in {@code font}, resolving the paragraph direction from the
     * text itself: the form nearly every caller wants, because nearly every caller is drawing a
     * string it did not write.
     *
     * <p>The direction comes from the first strong character, defaulting to
     * {@link ShapedText.Direction#LTR} for a string that has none; the rule is
     * {@link ShapedText.Direction#of}. <b>Declining to state a direction is this overload</b>, not a
     * third value of an enum &mdash; which is what keeps "not yet decided" out of every field that
     * could hold a direction.
     *
     * <p><b>A widget is the caller that can do better</b>, and should decline to decline: it knows
     * which way it reads, which is the one thing an all-neutral string &mdash; a count, a year, a
     * clock face &mdash; cannot say for itself. The widget layer's {@code Widget.shapeText} passes
     * that answer as the neutral base; a widget shaping through this overload instead gets a
     * left-to-right paragraph for exactly those strings, silently.
     *
     * <p>Same thread, same first-use costs and the same reasons as {@link #measure}, plus one more:
     * shaping is the expensive half of drawing text. Call it when the text or the font changes and
     * <em>hold</em> the result; {@link ShapedText#matches} is the check, and {@link ShapedText} has
     * the idiom. An implementation is expected to memoize as well, because the callers that cannot
     * hold a value &mdash; a chart rebuilding its axis labels every frame &mdash; would otherwise
     * re-shape at frame rate; what it hands back is immutable and may outlive the cache entry.
     *
     * @param text the line to shape; empty is legal and shapes to a zero-width line
     * @param font the face and size to shape in
     */
    default ShapedText shape(String text, Font font) {
        return shape(text, font, ShapedText.Direction.of(text, ShapedText.Direction.LTR));
    }

    /**
     * Shapes one line of {@code text} in {@code font} for a paragraph that reads {@code base}: the
     * glyphs, their positions, and the geometry every caret, hit test, selection and line break is
     * then asked for. For the callers that know something the string does not say &mdash; a field
     * whose content is a phone number in an Arabic form reads right to left however many Latin
     * digits it starts with, and the first-strong rule cannot know that.
     *
     * <p><b>The default implementation is the degraded path</b>, and it is a default rather than an
     * abstract method on purpose: a ruler with no shaper &mdash; a test fake, or a backend whose
     * native did not load &mdash; inherits a correct value built from {@link #measure} alone, and
     * this interface stays a {@code @FunctionalInterface} that a lambda can still satisfy. It
     * measures one grapheme cluster at a time, reorders by {@code java.text.Bidi}, and reports every
     * glyph as {@link ShapedText#NO_GLYPH} so that painting falls back to the per-code-point path
     * that produced those positions. What is lost is what a shaper does: no contextual forms, no
     * ligatures, no mark attachment, and marks occupying their own advance instead of attaching. A
     * missing native narrows what the toolkit can draw and never stops it.
     *
     * <p>Two consequences of measuring per cluster rather than per prefix, both deliberate.
     * Reordering is done even here, so bidi caret and selection geometry &mdash; the part that looks
     * right in a screenshot while being wrong &mdash; is testable against known cases with a fake
     * ruler, no native and no font file. And kerning across a cluster seam is lost, so this default's
     * {@code metrics().width()} can differ slightly from {@link #measure} of the same string: a ruler
     * that kerns should override this rather than inherit it, which the backend does on both its
     * shaping path and its degraded one.
     *
     * @param text the line to shape; empty is legal
     * @param font the face and size to shape in
     * @param base the paragraph direction to impose
     */
    default ShapedText shape(String text, Font font, ShapedText.Direction base) {
        TextMetrics line = measure(text, font);
        int length = text.length();
        // One glyph per grapheme cluster, and a cluster is at least one char: the length is an
        // exact upper bound, so the arrays are sized once and never grown.
        ShapedText.Builder builder = ShapedText.builder(text, font, base, length)
                .lineMetrics(line.ascent(), line.descent(), line.lineHeight())
                .epoch(epoch());
        if (length == 0) {
            return builder.build();
        }

        // Grapheme cluster boundaries over the WHOLE string, taken once. Doing this per run would
        // ask the iterator to break text it cannot see the context of.
        int[] boundaries = new int[length + 1];
        int boundaryCount = 0;
        BreakIterator clusters = BreakIterator.getCharacterInstance();
        clusters.setText(text);
        for (int at = clusters.first(); at != BreakIterator.DONE; at = clusters.next()) {
            boundaries[boundaryCount++] = at;
        }

        Bidi bidi = new Bidi(text, base == ShapedText.Direction.RTL
                ? Bidi.DIRECTION_RIGHT_TO_LEFT
                : Bidi.DIRECTION_LEFT_TO_RIGHT);
        int runCount = bidi.getRunCount();
        int[] cuts = new int[boundaryCount + 2];
        for (int r = 0; r < Math.max(1, runCount); r++) {
            // Bidi reports at least one run for a non-empty paragraph; the zero case is only
            // guarded so that a hypothetical implementation returning none still tiles the text.
            int runStart = runCount == 0 ? 0 : bidi.getRunStart(r);
            int runEnd = runCount == 0 ? length : bidi.getRunLimit(r);
            int level = runCount == 0
                    ? (base == ShapedText.Direction.RTL ? 1 : 0)
                    : bidi.getRunLevel(r);
            builder.run(0, runStart, runEnd, level);

            // The run's own cluster boundaries, clamped to it: a bidi run boundary that falls
            // inside a grapheme cluster still has to start a cluster here, because a glyph cannot
            // belong to two runs.
            int cutCount = 0;
            cuts[cutCount++] = runStart;
            for (int b = 0; b < boundaryCount; b++) {
                if (boundaries[b] > runStart && boundaries[b] < runEnd) {
                    cuts[cutCount++] = boundaries[b];
                }
            }
            cuts[cutCount++] = runEnd;

            // Emitted in the run's VISUAL order, which for an odd level is its clusters backwards:
            // that is what the builder's pen expects, and it is the same order a shaper would have
            // handed over.
            boolean rtl = (level & 1) != 0;
            for (int step = 0; step < cutCount - 1; step++) {
                int i = rtl ? cutCount - 2 - step : step;
                int from = cuts[i];
                int to = cuts[i + 1];
                float advance = measure(text.substring(from, to), font).width();
                builder.glyph(ShapedText.NO_GLYPH, from, advance, 0, 0);
            }
        }
        return builder.build();
    }

    /**
     * A counter that moves whenever this ruler would shape the same string differently: the one
     * input to shaping that a caller holding a {@link ShapedText} cannot see for itself.
     *
     * <p>It must move when the resolution of a family to a face changes &mdash; a family registered,
     * the font catalog replaced as system enumeration finishes, the default family switched &mdash;
     * when the set of resident faces changes, because a ruler that evicts and closes faces leaves
     * held values holding ids for faces that are gone, and when the shaping language changes,
     * because the same characters take different forms in different languages. It must <b>not</b>
     * move for a content-scale change: {@link ShapedText} positions are unquantized and
     * scale-independent, and bumping here would re-shape every string in the process every time a
     * window crossed a monitor boundary.
     *
     * <p>It lives on the ruler rather than on {@link Fonts} because the ruler is the only thing that
     * knows all of it: face residency is the shaping seam's business, and a facade that never loaded
     * a face cannot report one being evicted.
     *
     * <p><b>Epochs must be unique across rulers, not merely monotone within one.</b> Draw them from
     * one process-wide counter, because {@link ShapedText#matches} compares a held value's stamp
     * against whatever ruler it is handed: two rulers numbering independently would eventually both
     * answer the same number, and a value shaped by one would then report itself current under the
     * other. Installing a backend replaces the ruler outright, so this is the only thing standing
     * between a held value and a ruler that never produced it.
     *
     * <p>Compared through {@link ShapedText#matches}. A counter rather than a listener because the
     * re-shape rides the relayout these changes already cause, so nothing has to subscribe and
     * nothing can leak &mdash; the same reason an {@code I18nString} memoizes against an epoch
     * instead of watching one.
     *
     * @return the current epoch; a ruler whose answers never change may return a constant, and the
     *         default returns {@code 0}, which {@link ShapedText#matches} treats as always current
     */
    default long epoch() {
        return 0;
    }
}

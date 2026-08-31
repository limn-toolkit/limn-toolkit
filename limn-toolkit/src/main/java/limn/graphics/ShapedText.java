package limn.graphics;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One line of text after shaping: the glyphs a face draws for it, where each one sits, and every
 * geometric question a widget then asks about that line. Produced by
 * {@link TextRuler#shape(String, Font)} and <b>held by the widget</b>, because shaping is the
 * expensive half of drawing text and the answer is needed twice &mdash; once to lay out, once to
 * paint &mdash; and those two have to agree.
 *
 * <p>Holding it is the point, and it is the move {@code I18nString} makes for a translation applied
 * to the text's geometry: a value recomputed at the point of use is recomputed once per frame
 * forever. A widget keeps one field and refreshes it against its inputs:
 *
 * <pre>{@code
 * private ShapedText shaped;
 * ...
 * TextRuler ruler = textRuler();
 * if (shaped == null || !shaped.matches(text, font, ruler)) {
 *     shaped = ruler.shape(text, font);
 * }
 * canvas.drawText(shaped, x, baseline, ink);
 * }</pre>
 *
 * <p><b>Why a value and not a measurement.</b> With a shaper in the pipeline the width of the first
 * {@code n} characters of a string is not the width of those characters measured alone: inside
 * their line they join, ligate, kern and reorder differently than in isolation. Prefix measurement
 * is therefore not a slower way to place a caret, it is a wrong one. Everything a widget would have
 * asked a prefix for is asked of the whole line instead &mdash; {@linkplain #caretAt where an index
 * draws}, {@linkplain #hitTest which index is under this x}, {@linkplain #selection(int, int) which
 * boxes cover this range}, {@linkplain #fitEnd where to cut it} &mdash; and asking is a lookup or a
 * binary search against work already done.
 *
 * <p><b>Two axes, and they must never be substituted for one another.</b> {@link #caretAt},
 * {@link #caretX}, {@link #hitTest}, {@link #indexAt}, {@link #selection(int, int)},
 * {@link #caretLeft} and {@link #caretRight} are <em>visual</em>: they speak in x positions on the
 * screen. {@link #advanceTo}, {@link #indexForAdvance} and {@link #fitEnd} are <em>logical</em>:
 * they speak in advance consumed by a range of the string, which is what a wrap budget or an
 * ellipsis budget is made of and which is order-independent, so it still means something on a
 * reordered line. Where {@link #isSimple()} holds the two coincide; where it does not, using one
 * for the other is the bug that makes a selection band cover the wrong half of a line.
 *
 * <p><b>Coordinates.</b> One line, no {@code \n}: the widget splits paragraphs and shapes each
 * line. {@code x} is in logical points and grows <em>rightwards</em> from the origin the run is
 * drawn at, whatever the base direction, so the run always occupies
 * {@code [0, metrics().width()]} and right-to-left text is not drawn at negative x &mdash; it fills
 * the same box from the other end, and right-aligning it is a matter of choosing the origin.
 * {@code y} is an offset from the baseline, positive <b>down</b>, matching {@link Canvas}. Vertical
 * extent is the caller's: {@link #metrics()} gives the band, and the caret, the selection box and
 * the underline are all drawn against the same ascent and descent, so there is only one answer to
 * keep in agreement.
 *
 * <p><b>Indices</b> are {@code char} offsets into {@link #text()} in <em>logical</em> order, the
 * same index space the edit model, the clipboard and the IME speak. Every index accepted here is
 * clamped into {@code [0, text().length()]} and snapped to a cluster boundary rather than rejected:
 * a hit test on a moving pointer and a caret restored from a stale model both have to produce a
 * position, not an exception. Caret stops are the cluster boundaries the shaper reported; where one
 * disagrees with an extended grapheme cluster &mdash; a Devanagari conjunct is the case that bites
 * &mdash; the shaper's cluster wins, because a caret cannot be placed inside a glyph.
 *
 * <p><b>What makes a held value stale</b> is exactly the three things {@link #matches} tests, and
 * nothing else: the text, the {@link Font} (a value, so a control-size step or a theme change
 * produces a different one and is caught by the same comparison), and the ruler's
 * {@linkplain TextRuler#epoch() epoch}, which covers every input the caller cannot see &mdash;
 * which file a family resolves to, which faces are registered and still resident, the shaping
 * language. <b>The monitor content scale is not on that list.</b> Positions here are unquantized
 * logical points measured in font units, so a window dragged to a 2&times; display re-rasterizes
 * glyph bitmaps and re-shapes nothing. That is a promise this type makes to the backend, not an
 * accident: a shaper asked for hinted positions would break it, and every value in the process
 * would have to be re-shaped whenever a window crossed a monitor boundary.
 *
 * <p><b>Two allocation classes, deliberately visible in the shape of the API.</b> Runs are few
 * &mdash; one for the overwhelming majority of strings &mdash; so {@link #runs()} is a list of
 * records built once when the value is built. Glyphs are many and are read once per glyph per
 * frame, so they live in parallel primitive arrays behind {@link #glyphId(int)} and its neighbours;
 * no per-glyph object exists at any point, and no array is handed out to be mutated.
 * {@link #selection(int, int)} allocates because it is called at most once per paint;
 * {@link #selection(int, int, float[])} is the same answer into a buffer the caller keeps, for the
 * drag that repaints at frame rate.
 *
 * <p><b>No accessor on this type returns anything but an {@code int}, a {@code float}, a
 * {@code String}, a {@link Font}, a {@link TextMetrics} or a record of those.</b> A face is an
 * {@code int} that the ruler which produced the value assigns and only that ruler interprets. That
 * is structural rather than a rule someone has to remember: it is what lets this type live in a
 * module that cannot import a graphics library, and it is what makes holding one across frames safe
 * &mdash; keeping a value alive cannot keep a font file mapped.
 *
 * <p><b>Immutable, and therefore free to travel.</b> Producing one is UI-thread only, for the
 * reasons {@link TextRuler#measure} gives; reading one is not, so layout precomputed off the UI
 * thread can hand back the value rather than the string. Equality is identity: two shapings of the
 * same string are the same answer but not the same object, and the question a widget actually has
 * is {@link #matches}, which is different and cheaper.
 */
public final class ShapedText {

    /**
     * The {@linkplain #glyphId(int) glyph id} of a cluster this face draws by other means: the
     * backend paints it from {@link #text()} over the cluster's characters instead of from the
     * glyph atlas.
     *
     * <p>This one sentinel keeps two unrelated things working with one branch in the paint loop. A
     * colour-emoji cluster is a bitmap strike with its own advance, not an outline, and always was.
     * And a ruler with no shaper &mdash; a test fake, or a backend whose native did not load
     * &mdash; reports every cluster this way, so its positions are exact and its painting falls
     * back to the per-code-point path that produced them. A whole-value "is this shaped" flag
     * cannot express the mixed case at all.
     */
    public static final int NO_GLYPH = -1;

    private final String text;
    private final Font font;
    private final TextMetrics metrics;
    private final Direction baseDirection;
    private final boolean simple;
    private final long epoch;
    private final List<Run> runs;

    // The glyph payload, in VISUAL order: five parallel arrays and no per-glyph object, because
    // this is read once per glyph per frame in the hottest loop the toolkit has.
    private final int[] glyphIds;
    private final int[] glyphClusters;
    private final float[] glyphXs;
    private final float[] glyphYs;
    private final float[] glyphAdvances;

    // The caret-stop table, in LOGICAL order and ascending: stopChars[0] == 0 and the last entry is
    // text.length(). Everything geometric here is a search over these.
    private final int[] stopChars;
    private final float[] stopAdvances;
    private final float[] stopUpstreamX;
    private final float[] stopDownstreamX;
    private final long[] stopStrong;

    // Clusters, of which there are exactly stopChars.length - 1. A cluster's box is DERIVED from
    // the two caret stops that bound it (see clusterX0/clusterX1) rather than stored beside them:
    // a second copy of the same geometry is a second copy that can disagree, and the whole point of
    // resolving a hit test through the cluster under the point is that the caret it produces lands
    // on the edge the click was measured against.
    private final int[] visualOrder;
    private final long[] clusterRtl;

    private ShapedText(String text, Font font, TextMetrics metrics, Direction baseDirection,
                       boolean simple, long epoch, List<Run> runs, int[] glyphIds,
                       int[] glyphClusters, float[] glyphXs, float[] glyphYs, float[] glyphAdvances,
                       int[] stopChars, float[] stopAdvances, float[] stopUpstreamX,
                       float[] stopDownstreamX, long[] stopStrong, int[] visualOrder,
                       long[] clusterRtl) {
        // The only producers are uniform(...) and Builder.build().
        this.text = text;
        this.font = font;
        this.metrics = metrics;
        this.baseDirection = baseDirection;
        this.simple = simple;
        this.epoch = epoch;
        this.runs = runs;
        this.glyphIds = glyphIds;
        this.glyphClusters = glyphClusters;
        this.glyphXs = glyphXs;
        this.glyphYs = glyphYs;
        this.glyphAdvances = glyphAdvances;
        this.stopChars = stopChars;
        this.stopAdvances = stopAdvances;
        this.stopUpstreamX = stopUpstreamX;
        this.stopDownstreamX = stopDownstreamX;
        this.stopStrong = stopStrong;
        this.visualOrder = visualOrder;
        this.clusterRtl = clusterRtl;
    }

    // ------------------------------------------------------------------ identity

    /** The line this was shaped from: the index space of every query here. */
    public String text() {
        return text;
    }

    /** The family, size and style it was shaped for. */
    public Font font() {
        return font;
    }

    /**
     * Extents of the line, in logical points, on the same terms and with the same unquantized
     * meaning as {@link TextRuler#measure}. {@code width()} is the sum of the glyph advances, so it
     * is {@code advanceTo(text().length())} by construction and the two cannot be made to disagree
     * however the runs were assembled.
     */
    public TextMetrics metrics() {
        return metrics;
    }

    /**
     * The paragraph direction this line resolved to: what a caller aligns against, and which of a
     * {@linkplain Caret split caret}'s two positions is the strong one. Never absent &mdash; by the
     * time a value exists the question has been answered, which is why {@link Direction} has no
     * third constant.
     */
    public Direction baseDirection() {
        return baseDirection;
    }

    /**
     * Whether this line is one left-to-right run in one face with no reordering: character order
     * and screen order agree, so caret x is monotone in the char index and every geometry query is
     * a binary search over the glyphs rather than a walk over the runs.
     *
     * <p><b>Derived, never asserted.</b> {@link Builder#build()} computes this from the runs and
     * glyphs it was actually fed, so itemization cannot change and leave the flag behind &mdash;
     * which is the failure that would make every fast path in the toolkit silently wrong for the
     * one string that needed the slow one. Ligatures and kerning do not break it: they change how
     * wide characters are, not what order they are in. A second face, a right-to-left run, or a
     * reordered matra does.
     *
     * <p>Callers use it to decide whether an operation that is only defined on monotone text is
     * legal at all. It is never needed to make {@link #caretAt}, {@link #hitTest} or
     * {@link #selection(int, int)} correct; those handle both.
     */
    public boolean isSimple() {
        return simple;
    }

    /**
     * The {@linkplain TextRuler#epoch() ruler epoch} this was shaped under, or {@code 0} for a value
     * that depends on no ruler state.
     *
     * <p>Compare through {@link #matches} rather than reading this: the comparison that matters is
     * against a ruler's <em>current</em> epoch, and a caller holding a bare number is a caller that
     * can compare it against the wrong one. It is exposed because a renderer that is also the
     * producing ruler can use it to decide, in one integer comparison, whether the face ids in the
     * glyph payload are still worth trusting.
     */
    public long epoch() {
        return epoch;
    }

    /**
     * Whether this value is still the right answer for {@code text} and {@code font} under
     * {@code ruler}: the whole invalidation test in one call, so no caller has to remember that
     * there are three parts to it.
     *
     * <p>{@code text} is compared by identity first, so a widget holding the {@code String} an
     * {@code I18nString} memoized never pays a character scan. The {@code font} comparison is
     * {@link Font}'s own, which is why a control-size step or a theme change needs nothing extra
     * here. The third part is the reason this is a method and not two field comparisons at the call
     * site: a {@link Font} naming {@link Font#DEFAULT_FAMILY} is equal to itself across a
     * {@link Fonts#setDefaultFamily} call, and a face this was shaped against can have been evicted
     * and closed since, so a widget checking only text and font would keep drawing glyph ids that
     * name a face that is gone.
     *
     * <p>A value carrying epoch {@code 0} is current under every ruler, which is right for a fake
     * and for geometry that no ruler produced.
     *
     * @param text  the string the caller is about to draw
     * @param font  the font it will be drawn in
     * @param ruler the ruler that would re-shape it; its epoch is read, never stored
     */
    public boolean matches(String text, Font font, TextRuler ruler) {
        if (this.text != text && !this.text.equals(text)) {
            return false;
        }
        if (!this.font.equals(font)) {
            return false;
        }
        // Read the ruler last and only when this value claims to depend on one: epoch 0 means
        // "depends on no ruler state", and a fake handed in by a test must not be asked anything.
        return epoch == 0 || epoch == ruler.epoch();
    }

    // -------------------------------------------------------------- caret stops

    /**
     * How many caret stops this line has: one per cluster boundary, so always at least one, and
     * exactly {@code 1} for an empty line.
     *
     * <p>The stop table is what every geometry query here searches, and enumerating it is how a
     * test pins bidi caret behaviour over known cases &mdash; logical order in, expected visual
     * positions out &mdash; instead of probing x values and hoping to hit one. It is also how a
     * widget that draws a mark per cluster gets its count without a second rule for finding cluster
     * boundaries, which is the drift that puts the caret between two dots.
     */
    public int caretCount() {
        return stopChars.length;
    }

    /**
     * The {@code char} offset of one caret stop, in <b>logical</b> order: ascending, {@code 0} at
     * ordinal {@code 0} and {@code text().length()} at {@code caretCount() - 1}. Visual order is a
     * different question, and {@link #caretLeft}/{@link #caretRight} are how it is asked.
     *
     * @param ordinal in {@code [0, caretCount())}, clamped
     */
    public int caretIndex(int ordinal) {
        int o = Math.min(Math.max(ordinal, 0), stopChars.length - 1);
        return stopChars[o];
    }

    /**
     * The ordinal of the caret stop at or before {@code charIndex}: the inverse of
     * {@link #caretIndex}, and the third and last piece of the stop table.
     *
     * <p>With it the snapping rule every index-taking method here obeys is expressible in this
     * type's own vocabulary &mdash; an index {@code i} is treated as
     * {@code caretIndex(caretOrdinal(i))} &mdash; and the two things a caller cannot otherwise get
     * become one call each: the stop after an index is
     * {@code caretIndex(caretOrdinal(i) + 1)}, which is what a line breaker takes when a word is
     * too wide for its line and not even one cluster fits, and the stop before it is
     * {@code caretIndex(caretOrdinal(i) - 1)}. Both are <em>logical</em> neighbours;
     * {@link #caretLeft} and {@link #caretRight} are the visual ones, and they are not the same
     * question.
     *
     * @param charIndex a char index into {@link #text()}, clamped into range
     */
    public int caretOrdinal(int charIndex) {
        return floorStop(stopChars, Math.min(Math.max(charIndex, 0), text.length()));
    }

    // ------------------------------------------------------------ visual geometry

    /**
     * Both places a caret may sit for {@code charIndex}, for a caller that stores an index and no
     * side.
     *
     * <p>An index on a direction boundary has <em>two</em> visual positions, because the character
     * before it and the character after it are drawn nowhere near each other, and which one the
     * next typed character lands at depends on the direction of what is typed. A caret drawn at
     * only one of them tells the user something false about their own cursor. Off a boundary the
     * two are the same point; see {@link Caret}.
     *
     * @param charIndex a char index into {@link #text()}, clamped and snapped to a caret stop
     */
    public Caret caretAt(int charIndex) {
        int k = caretOrdinal(charIndex);
        return new Caret(stopUpstreamX[k], stopDownstreamX[k], strongIsDownstream(k));
    }

    /**
     * The one x a caller with a stored side means: {@code caretAt(p.charIndex()).x(p.affinity())}.
     * This is what {@link #hitTest} hands back, so a click round-trips to the pixel it landed on.
     *
     * @param position where the caret is, index and side
     */
    public float caretX(Position position) {
        int k = caretOrdinal(position.charIndex());
        // Spelled out rather than via caretAt().x(): the cost contract promises this one allocates
        // nothing, and it is called per frame by a scroll clamp that follows the caret.
        return position.affinity() == Affinity.UPSTREAM ? stopUpstreamX[k] : stopDownstreamX[k];
    }

    /**
     * The caret position a click at {@code x} asks for, side included.
     *
     * <p>Resolved through the <em>cluster under the point</em>, never by searching caret x values:
     * the cluster whose visual box contains {@code x} is found, and the caret goes to that
     * cluster's leading edge with {@link Affinity#DOWNSTREAM} when {@code x} fell in its leading
     * half and to its trailing edge with {@link Affinity#UPSTREAM} otherwise. Halves are visual, so
     * the leading half of a right-to-left cluster is its right half. This is what makes a click
     * either side of a direction boundary land on the two different insertion points that share
     * that point on the line, which a search over caret x values structurally cannot do because
     * both of them sit at the same x.
     *
     * <p>Cluster boxes are half-open on the right, so an {@code x} landing exactly on the seam
     * between two clusters belongs to the one on its right. That is arbitrary and it is fixed here
     * anyway: a click on a seam has to resolve the same way every time, and a rule chosen at the
     * call site is a rule two call sites will choose differently.
     *
     * <p>{@code x} outside {@code [0, metrics().width()]} clamps to the nearest cluster, which is
     * what a drag past the end of the line wants. It is <em>not</em> what a click in the empty
     * space to the right of a line wants, which is the logical end of the line: a caller that
     * offers that space has to compare against {@code metrics().width()} first, because on a line
     * that ends in the direction opposite the paragraph's the nearest cluster to the right edge is
     * not the last character.
     *
     * @param x logical points from the run origin, growing rightwards
     */
    public Position hitTest(float x) {
        if (visualOrder.length == 0) {
            return new Position(0, Affinity.DOWNSTREAM);
        }
        int v = visualClusterAt(x);
        int j = visualOrder[v];
        float x0 = clusterX0(j);
        float x1 = clusterX1(j);
        float middle = (x0 + x1) * 0.5f;
        // The leading half is the half the caret's DOWNSTREAM edge sits on, which for a
        // right-to-left cluster is the RIGHT half. Getting this backwards is invisible in a
        // screenshot and puts every click one character out in mixed text.
        boolean leading = rtlCluster(j) ? x >= middle : x < middle;
        return leading
                ? new Position(stopChars[j], Affinity.DOWNSTREAM)
                : new Position(stopChars[j + 1], Affinity.UPSTREAM);
    }

    /**
     * The char index a click at {@code x} asks for: {@code hitTest(x).charIndex()}, for a caller
     * that places a caret and keeps no side. Correct as far as it goes, and it goes exactly as far
     * as one keystroke: a caret stored without its side is a caret that jumps the next time it
     * moves across a direction boundary.
     *
     * @param x logical points from the run origin, growing rightwards
     */
    public int indexAt(float x) {
        return hitTest(x).charIndex();
    }

    /**
     * The boxes covering the characters in {@code [start, end)} &mdash; <b>N of them, never one.</b>
     *
     * <p>A range that is contiguous in the string stops being contiguous on the line the moment it
     * crosses a direction boundary: selecting across the seam of Latin and Hebrew highlights the
     * Latin part and the Hebrew part with untouched text between them, because the characters
     * between them in the string are drawn outside the visual range. A single rectangle cannot
     * express that, and rounding it up to one would highlight text that is not selected, so this
     * returns every box and a caller that wants one rectangle is asking a question with no true
     * answer. The same call draws an IME preedit underline and the highlight under the block being
     * converted, which is this question asked of a sub-range.
     *
     * <p>Boxes come back in ascending {@code x}, never overlap, and are merged
     * where they touch &mdash; so a whole-line selection is one box however many runs the line has,
     * and a translucent fill never double-blends along a seam. An empty or inverted range returns an
     * empty list: a caret is not a zero-width selection, and a caller that wants a mark for one is
     * asking {@link #caretAt}. No box is ever zero-width, so a cluster that consumes no advance
     * contributes none rather than a band the fill cannot show. There is no vertical extent here on purpose; the band is the widget's
     * own ink box, which it already computes for the caret and the underline. The returned list is
     * unmodifiable.
     *
     * @param start first char index of the range, clamped and snapped to a caret stop
     * @param end   char index one past the range, clamped and snapped to a caret stop
     */
    public List<Span> selection(int start, int end) {
        if (runs.isEmpty()) {
            return List.of();
        }
        float[] boxes = new float[2 * runs.size()];
        int n = fillSpans(start, end, boxes);
        Span[] out = new Span[n];
        for (int i = 0; i < n; i++) {
            out[i] = new Span(boxes[i * 2], boxes[i * 2 + 1]);
        }
        return List.of(out);
    }

    /**
     * {@link #selection(int, int)} into a buffer the caller owns: writes {@code x0, x1} pairs from
     * index {@code 0} and returns how many boxes were written.
     *
     * <p>{@code out} must hold {@code 2 * runs().size()} floats, which is the exact upper bound
     * &mdash; a logical range maps to a contiguous visual stretch within any one run, so a selection
     * cannot produce more boxes than the line has runs, and merging only reduces the count. A
     * selection drag repaints at frame rate, and this is the form that does not put a list and N
     * records on the floor each time.
     *
     * <p>It throws on a short buffer rather than writing what fits, because writing fewer boxes than
     * the selection has paints a band over some of the user's own text and leaves the rest
     * unhighlighted &mdash; which reads as a rendering glitch rather than as the sizing bug it is.
     *
     * @param start first char index of the range, clamped and snapped to a caret stop
     * @param end   char index one past the range, clamped and snapped to a caret stop
     * @param out   destination, at least {@code 2 * runs().size()} floats long
     * @return how many boxes were written; {@code 2 *} this many floats were touched
     * @throws IllegalArgumentException if {@code out} is shorter than {@code 2 * runs().size()}
     */
    public int selection(int start, int end, float[] out) {
        int need = 2 * runs.size();
        if (out.length < need) {
            throw new IllegalArgumentException(
                    "selection buffer holds " + out.length + " floats, needs " + need
                            + " (2 per run, and a selection cannot have more boxes than runs)");
        }
        return fillSpans(start, end, out);
    }

    // ---------------------------------------------------------------- navigation

    /**
     * The caret stop one step to the <b>left on the line</b> &mdash; the Left arrow key, whatever
     * direction the text under it runs. Returns {@code from} unchanged when there is no stop further
     * left, which is how a multi-line caller knows to move to the previous line.
     *
     * <p>The rule is stated in clusters, not in indices, because that is the only form of it that is
     * well defined: take the cluster whose visual box abuts {@code caretX(from)} on the left, and
     * return the position on that cluster's far (left) edge &mdash; its leading edge with
     * {@link Affinity#DOWNSTREAM} if it reads left to right, its trailing edge with
     * {@link Affinity#UPSTREAM} if it reads right to left.
     *
     * <p>It takes and returns a {@link Position} rather than an index because visual movement is not
     * a function of the index alone. An index on a direction boundary occupies two points on the
     * line, and two presses in a row have to leave from the point the first press arrived at; an
     * index does not say which of the two that was, so an index-taking form has to guess, and
     * whichever side it guesses, the caret walks left out of one run and then jumps to the far end
     * of the line on the next press. That is non-determinism across two keystrokes, not imprecision.
     *
     * <p>Visual movement only. Logical movement &mdash; the next grapheme cluster in the string,
     * which is what a delete, an undo or a text-range API works in &mdash; stays the editing model's
     * job and does not come from here.
     *
     * @param from where the caret is now, index and side
     */
    public Position caretLeft(Position from) {
        float x = caretX(from);
        // The last cluster that starts strictly left of the caret. Strictly, so a zero-advance
        // cluster sitting exactly on the caret is not "abutting" it: a box with no extent is not a
        // place the caret can move to, and stepping onto one would eat a keystroke.
        int lo = 0;
        int hi = visualOrder.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (visualEdge(mid) < x) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0) {
            return from;
        }
        int j = visualOrder[found];
        return rtlCluster(j)
                ? new Position(stopChars[j + 1], Affinity.UPSTREAM)
                : new Position(stopChars[j], Affinity.DOWNSTREAM);
    }

    /**
     * The caret stop one step to the <b>right on the line</b>: the Right arrow key, and the mirror
     * of {@link #caretLeft} in every respect, including why it speaks {@link Position} and not
     * indices. Returns {@code from} unchanged at the right end of the line.
     *
     * @param from where the caret is now, index and side
     */
    public Position caretRight(Position from) {
        float x = caretX(from);
        // The first cluster that ends strictly right of the caret; the mirror of caretLeft, and the
        // reason the two are exact inverses at every stop rather than only away from a boundary.
        int lo = 0;
        int hi = visualOrder.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (visualEdge(mid + 1) > x) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        if (found < 0) {
            return from;
        }
        int j = visualOrder[found];
        return rtlCluster(j)
                ? new Position(stopChars[j], Affinity.DOWNSTREAM)
                : new Position(stopChars[j + 1], Affinity.UPSTREAM);
    }

    // ------------------------------------------------------------ logical measure

    /**
     * Advance consumed by the characters before {@code charIndex}: the sum of the advances of every
     * cluster logically preceding it. Monotone non-decreasing, {@code 0} at {@code 0} and
     * {@code metrics().width()} at {@code text().length()}, whatever the direction &mdash; a
     * reordered line still has a total, and so does every prefix of it, because a sum does not care
     * what order it was added in.
     *
     * <p>This is a <b>budget, not a promise about a substring</b>. It says how much of the line's
     * width the first {@code charIndex} characters account for <em>within this shaping</em>. It does
     * not say how wide that prefix would be if it were shaped on its own, and under a shaper those
     * two differ: joining forms change at the cut, a ligature that spanned it disappears, and the
     * kerning at the seam is gone. A caller that cuts a line at an index has to re-shape both pieces
     * before painting them, and may then find the cut piece a hair wider or narrower than the budget
     * promised.
     *
     * @param charIndex a char index into {@link #text()}, clamped and snapped to a caret stop
     */
    public float advanceTo(int charIndex) {
        return stopAdvances[caretOrdinal(charIndex)];
    }

    /**
     * The largest caret stop whose {@link #advanceTo} does not exceed {@code advance}: the inverse
     * of the budget.
     *
     * @param advance a width in logical points; below zero yields {@code 0}, and at or above
     *                {@code metrics().width()} yields {@code text().length()}
     */
    public int indexForAdvance(float advance) {
        if (!(advance > 0)) {
            return 0;                                   // also the answer for NaN
        }
        if (advance >= metrics.width()) {
            return text.length();
        }
        int lo = 0;
        int hi = stopAdvances.length - 1;
        int found = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (stopAdvances[mid] <= advance) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return stopChars[found];
    }

    /**
     * Where to cut: the end of the longest run of clusters starting at {@code from}, in
     * <em>logical</em> order, whose advances fit within {@code available}. Exactly
     * {@code indexForAdvance(advanceTo(from) + available)}, named because the {@code from}
     * anchoring is the part a caller gets wrong.
     *
     * <p>An ellipsis passes {@code from = 0}. A greedy line breaker shapes the paragraph once and
     * then walks it with this, one line per call, asking {@code java.text.BreakIterator} for the
     * last break opportunity at or before each answer; without the anchor it would have to re-shape
     * the remainder to ask again, which is quadratic in the length of the paragraph. Used alone it
     * cuts mid-word, and in a script without spaces it cuts where no break is allowed, so the
     * break iterator is not optional.
     *
     * <p>The result is a caret stop in {@code [from, text().length()]}, and equals {@code from} when
     * not even one cluster fits &mdash; which a caller breaking a word too wide for its line has to
     * handle by taking one cluster anyway. Everything {@link #advanceTo} says about re-shaping what
     * is cut applies here.
     *
     * @param from      char index to start from, clamped and snapped to a caret stop
     * @param available width budget in logical points; a non-positive budget returns {@code from}
     */
    public int fitEnd(int from, float available) {
        int ordinal = caretOrdinal(from);
        int anchor = stopChars[ordinal];
        if (!(available > 0)) {
            return anchor;
        }
        // Never behind the anchor: a budget cannot make a line shorter than where it starts, and a
        // caller that got a smaller number back would loop forever on the word it cannot break.
        return Math.max(anchor, indexForAdvance(stopAdvances[ordinal] + available));
    }

    // ----------------------------------------------------------------- paint seam

    /**
     * The runs, in <b>visual order, left to right</b>: what the backend iterates to paint, the unit
     * at which a face is resolved and a draw is batched, and one entry for almost every string the
     * toolkit draws.
     *
     * <p>Immutable. The runs' {@linkplain Run#glyphStart() glyph ranges} tile
     * {@code [0, glyphCount())} in order, and their {@linkplain Run#charStart() character ranges}
     * tile the text &mdash; but those character ranges are <em>not</em> in ascending order once
     * anything reorders, which is the whole reason this list exists in visual order and the builder
     * is fed in logical order.
     */
    public List<Run> runs() {
        return runs;
    }

    /**
     * How many glyphs this line draws. Unrelated to {@code text().length()} once anything ligates,
     * and zero is legal rather than an error: an empty string and a line of control characters both
     * carry geometry and no glyphs.
     */
    public int glyphCount() {
        return glyphIds.length;
    }

    /**
     * The glyph index within the face of the {@linkplain Run run} that contains it, or
     * {@link #NO_GLYPH}. An id from one face means nothing in another, which is why a run carries
     * its {@linkplain Run#faceId() face} and a glyph does not, and why both are meaningful only to
     * the {@link TextRuler} that produced them.
     *
     * @param glyphIndex in {@code [0, glyphCount())}
     */
    public int glyphId(int glyphIndex) {
        return glyphIds[glyphIndex];
    }

    /**
     * The glyph's origin along the baseline, in logical points from the left edge of the line: the
     * pen position plus whatever the shaper offset it by. Already in visual order and already
     * reordered, so the paint loop is a walk with no arithmetic of its own and no pen to carry.
     *
     * @param glyphIndex in {@code [0, glyphCount())}
     */
    public float glyphX(int glyphIndex) {
        return glyphXs[glyphIndex];
    }

    /**
     * The glyph's offset from the baseline, positive <b>down</b>, in logical points: zero for
     * everything except a mark the shaper attached. A shaper that reports mark attachment
     * positive-up has to negate on the way in.
     *
     * @param glyphIndex in {@code [0, glyphCount())}
     */
    public float glyphY(int glyphIndex) {
        return glyphYs[glyphIndex];
    }

    /**
     * How far the pen moves after this glyph, in logical points. Zero for an attached mark, which is
     * what keeps a cluster's box the width of its base instead of the width of the base plus its
     * accents.
     *
     * @param glyphIndex in {@code [0, glyphCount())}
     */
    public float glyphAdvance(int glyphIndex) {
        return glyphAdvances[glyphIndex];
    }

    /**
     * The {@code char} offset into {@link #text()} that this glyph came from: the start of its
     * cluster, so a ligature's several characters all report the offset of the first, and several
     * marks on one base all report the offset of the base.
     *
     * <p><b>This mapping is the contract the caret rests on.</b> A shaper reports clusters as
     * offsets into the buffer it was handed, and every run boundary moves that origin; an off-by-one
     * here is a caret that lands one character away from every click, in every field, forever. It is
     * why {@link Builder#glyph} demands whole-string offsets and rejects anything else at the call
     * that supplied it.
     *
     * @param glyphIndex in {@code [0, glyphCount())}
     */
    public int glyphCluster(int glyphIndex) {
        return glyphClusters[glyphIndex];
    }

    // --------------------------------------------------------------- construction

    /**
     * A line whose every cluster has the same advance and no glyph: the drawn form of a masked
     * field, and of anything else that paints marks of its own instead of text.
     *
     * <p>It exists so that a password field is not shaped. Its dots are not glyphs, its content must
     * never reach a shaper or the memo a shaper keeps, and every geometric question it asks has a
     * closed form in one multiplication. What it buys is that the caret, the selection band, the hit
     * test and the painted marks all come from one piece of arithmetic instead of two that must be
     * kept in agreement: the number of marks to paint is {@code caretCount() - 1} and the i-th one
     * is centred at {@code (i + 0.5f) * advance}, so no caller has to divide a width by an advance
     * to recover a count.
     *
     * <p>Clusters here are extended grapheme clusters, so one mark stands for one user-perceived
     * character. The result is {@linkplain #isSimple() simple} and left-to-right: a mask has no
     * direction, because it has no characters left to have one.
     *
     * @param text        the content being measured, which is never drawn and never shaped
     * @param font        the font whose {@code lineMetrics} these are
     * @param advance     the width of one mark in logical points; must be positive and finite
     * @param lineMetrics ascent, descent and line height; its width is ignored, because the width
     *                    here is the mark count times the advance and the two must not be able to
     *                    disagree
     * @param epoch       the {@linkplain TextRuler#epoch() ruler epoch} {@code lineMetrics} was
     *                    measured under, so that {@link #matches} still notices a default-family
     *                    change that leaves {@code font} equal to itself; {@code 0} for geometry
     *                    that depends on no ruler state
     * @throws IllegalArgumentException if {@code advance} is not positive and finite
     * @throws NullPointerException     if {@code text}, {@code font} or {@code lineMetrics} is null
     */
    public static ShapedText uniform(String text, Font font, float advance, TextMetrics lineMetrics,
                                     long epoch) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(lineMetrics, "lineMetrics");
        if (!(advance > 0) || !Float.isFinite(advance)) {
            throw new IllegalArgumentException(
                    "mark advance must be positive and finite, got " + advance);
        }
        int length = text.length();
        // Through the Builder like every other producer, so the mask's caret table is assembled by
        // the same code as a shaped line's and the two cannot drift.
        Builder builder = builder(text, font, Direction.LTR, length)
                .lineMetrics(lineMetrics.ascent(), lineMetrics.descent(), lineMetrics.lineHeight())
                .epoch(epoch);
        if (length > 0) {
            builder.run(0, 0, length, 0);
            BreakIterator clusters = BreakIterator.getCharacterInstance();
            clusters.setText(text);
            int start = clusters.first();
            for (int end = clusters.next(); end != BreakIterator.DONE; end = clusters.next()) {
                builder.glyph(NO_GLYPH, start, advance, 0, 0);
                start = end;
            }
        }
        return builder.build();
    }

    /**
     * Starts building a shaped line: what a {@link TextRuler} implementation calls, and the only
     * other way one is made.
     *
     * <p>{@code glyphCapacity} sizes the arrays exactly once. A shaper knows its glyph count before
     * it copies anything out, so passing the real number means the value is built with no growth and
     * no copy; passing a wrong one is a wasted allocation and never a wrong answer.
     *
     * @param text          the string being shaped; may be empty
     * @param font          the font it is being shaped for
     * @param baseDirection the resolved paragraph direction; see {@link Direction} for why there is
     *                      no third value to pass here
     * @param glyphCapacity the exact glyph count when it is known, otherwise an estimate
     * @throws NullPointerException if any reference argument is null
     */
    public static Builder builder(String text, Font font, Direction baseDirection,
                                  int glyphCapacity) {
        return new Builder(Objects.requireNonNull(text, "text"),
                Objects.requireNonNull(font, "font"),
                Objects.requireNonNull(baseDirection, "baseDirection"),
                Math.max(0, glyphCapacity));
    }

    // ------------------------------------------------------------------ internals

    /** Whether cluster {@code j} reads right to left: the parity of the run that produced it. */
    private boolean rtlCluster(int j) {
        return (clusterRtl[j >>> 6] & (1L << (j & 63))) != 0;
    }

    /** Whether the downstream side is the strong one at caret stop {@code k}. */
    private boolean strongIsDownstream(int k) {
        return (stopStrong[k >>> 6] & (1L << (k & 63))) != 0;
    }

    // A cluster's box is the pair of caret-stop edges that bound it, read back rather than stored
    // beside them. Cluster j lies between stops j and j + 1, so stopDownstreamX[j] is its LEADING
    // edge and stopUpstreamX[j + 1] its TRAILING edge; which of those is left depends on the
    // cluster's own direction, not the paragraph's.

    private float clusterX0(int j) {
        return rtlCluster(j) ? stopUpstreamX[j + 1] : stopDownstreamX[j];
    }

    private float clusterX1(int j) {
        return rtlCluster(j) ? stopDownstreamX[j] : stopUpstreamX[j + 1];
    }

    /**
     * The left edge of the {@code v}-th cluster in visual order, and the line's right edge at
     * {@code v == clusterCount}: the tiling the two navigation searches binary-search over.
     */
    private float visualEdge(int v) {
        return v == visualOrder.length ? metrics.width() : clusterX0(visualOrder[v]);
    }

    /** The visual cluster whose half-open box {@code [x0, x1)} contains {@code x}, clamped. */
    private int visualClusterAt(float x) {
        int lo = 0;
        int hi = visualOrder.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (visualEdge(mid + 1) > x) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /**
     * One walk shared by both {@link #selection} forms, so the list and the buffer cannot disagree
     * about which boxes a range covers.
     */
    private int fillSpans(int start, int end, float[] out) {
        int first = caretOrdinal(start);
        int last = caretOrdinal(end);
        if (last <= first) {
            return 0;                                   // empty or inverted: not a zero-width box
        }
        if (simple) {
            // Visual order is logical order, so the range is one box and there is nothing to merge.
            float x0 = stopAdvances[first];
            float x1 = stopAdvances[last];
            if (x0 == x1) {
                return 0;
            }
            out[0] = x0;
            out[1] = x1;
            return 1;
        }
        int written = 0;
        boolean open = false;
        float x0 = 0;
        float x1 = 0;
        for (int v = 0; v < visualOrder.length; v++) {
            int j = visualOrder[v];
            if (j >= first && j < last) {
                float bx0 = clusterX0(j);
                float bx1 = clusterX1(j);
                if (open && bx0 == x1) {
                    x1 = bx1;                           // abuts what is open: one box, not two
                } else {
                    if (open && x1 > x0) {
                        out[written * 2] = x0;
                        out[written * 2 + 1] = x1;
                        written++;
                    }
                    x0 = bx0;
                    x1 = bx1;
                    open = true;
                }
            } else if (open) {
                if (x1 > x0) {
                    out[written * 2] = x0;
                    out[written * 2 + 1] = x1;
                    written++;
                }
                open = false;
            }
        }
        if (open && x1 > x0) {
            out[written * 2] = x0;
            out[written * 2 + 1] = x1;
            written++;
        }
        return written;
    }

    /** The largest index of {@code stops} whose value is at or below {@code value}. */
    private static int floorStop(int[] stops, int value) {
        int lo = 0;
        int hi = stops.length - 1;
        int found = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (stops[mid] <= value) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /**
     * Assembles a {@link ShapedText} run by run. Backend-facing: a widget never touches one. Not
     * thread safe, single use, and meant to be filled and dropped inside one
     * {@link TextRuler#shape(String, Font, Direction)} call.
     *
     * <p><b>Runs go in logically and come out visually.</b> A caller supplies the runs in the order
     * the characters appear in the string, each with its bidi embedding level, and the builder does
     * the reordering (Unicode Bidirectional Algorithm rule L2), lays out each run's glyphs from the
     * advances it was given, and derives the caret stops, the split positions and the cumulative
     * advances. That places the highest-risk arithmetic in a module with no natives in it, where it
     * can be unit-tested with no window, no GPU and no font file &mdash; and it means the shaping
     * path and the degraded path come through one door, so the two cannot disagree about where a
     * caret goes.
     *
     * <p><b>Cluster offsets are absolute.</b> Every index this builder is given is an offset into
     * the whole string, never into the buffer a shaper was handed for one run. Shapers report
     * clusters relative to what they were given and every run boundary shifts that origin; making
     * the seam demand absolute offsets and check them turns that entire class of mistake from a
     * caret that lands one character from the click into an exception at the call that made it.
     */
    public static final class Builder {

        private final String text;
        private final Font font;
        private final Direction baseDirection;

        private int[] glyphIds;
        private int[] glyphClusters;
        private float[] glyphAdvances;
        private float[] glyphXOffsets;
        private float[] glyphYOffsets;
        private int glyphCount;

        private int[] runFaces = new int[4];
        private int[] runCharStarts = new int[4];
        private int[] runCharEnds = new int[4];
        private int[] runLevels = new int[4];
        private int[] runGlyphStarts = new int[4];
        private int[] runGlyphEnds = new int[4];
        private int runCount;

        private float ascent;
        private float descent;
        private float lineHeight;
        private boolean hasLineMetrics;
        private long epoch;

        private Builder(String text, Font font, Direction baseDirection, int glyphCapacity) {
            this.text = text;
            this.font = font;
            this.baseDirection = baseDirection;
            this.glyphIds = new int[glyphCapacity];
            this.glyphClusters = new int[glyphCapacity];
            this.glyphAdvances = new float[glyphCapacity];
            this.glyphXOffsets = new float[glyphCapacity];
            this.glyphYOffsets = new float[glyphCapacity];
        }

        /**
         * The line's vertical extents, in logical points, as {@link TextMetrics} defines them.
         * Required before {@link #build()}. Width is not among them: it is the extent of the glyphs,
         * so it is derived rather than stated.
         *
         * @param ascent     baseline to ascender, positive up
         * @param descent    baseline to descender, positive down
         * @param lineHeight recommended baseline-to-baseline distance
         */
        public Builder lineMetrics(float ascent, float descent, float lineHeight) {
            this.ascent = ascent;
            this.descent = descent;
            this.lineHeight = lineHeight;
            this.hasLineMetrics = true;
            return this;
        }

        /**
         * Stamps the {@linkplain TextRuler#epoch() epoch} the shaping was done under. A ruler that
         * does not set one produces a value {@link ShapedText#matches} treats as always current,
         * which is right for a fake and wrong for anything that resolves a face.
         *
         * @param epoch the producing ruler's epoch at the moment of shaping
         */
        public Builder epoch(long epoch) {
            this.epoch = epoch;
            return this;
        }

        /**
         * Opens a run and implicitly closes the previous one: one face, one direction, one shaping
         * call, covering the characters {@code [charStart, charEnd)}.
         *
         * <p>Runs are supplied in <b>logical</b> order and must tile the string exactly &mdash; no
         * gap, no overlap, the first starting at zero and the last ending at the string's length.
         * A run with no glyphs at all is legal and is what a run of control characters is.
         *
         * <p>{@code level} is the bidi embedding level, with even levels reading left to right and
         * odd levels right to left, as the Unicode Bidirectional Algorithm numbers them and as
         * {@code java.text.Bidi} reports them. It is a level and not a boolean because rule L2
         * reorders by level and cannot be driven by parity alone once anything nests.
         *
         * @param faceId    the producing ruler's own identifier for the face this run's glyph ids
         *                  belong to; opaque here, and only ever compared for equality
         * @param charStart first char offset this run covers
         * @param charEnd   one past the last, greater than {@code charStart}
         * @param level     the bidi embedding level, {@code 0} to {@code 125}
         * @throws IllegalArgumentException if the range is empty, reversed, out of bounds, does not
         *                                  begin where the previous run ended, or the level is out
         *                                  of range
         */
        public Builder run(int faceId, int charStart, int charEnd, int level) {
            if (charEnd <= charStart) {
                throw new IllegalArgumentException(
                        "run [" + charStart + ", " + charEnd + ") is empty or reversed");
            }
            if (charStart < 0 || charEnd > text.length()) {
                throw new IllegalArgumentException("run [" + charStart + ", " + charEnd
                        + ") is outside the text, which is " + text.length() + " chars");
            }
            int expected = runCount == 0 ? 0 : runCharEnds[runCount - 1];
            if (charStart != expected) {
                throw new IllegalArgumentException("run starts at " + charStart
                        + " but the previous one ended at " + expected
                        + "; runs are supplied in logical order and must tile the text");
            }
            if (level < 0 || level > 125) {
                throw new IllegalArgumentException(
                        "bidi embedding level must be 0 to 125, got " + level);
            }
            if (runCount > 0) {
                runGlyphEnds[runCount - 1] = glyphCount;
            }
            if (runCount == runFaces.length) {
                int capacity = runCount * 2;
                runFaces = Arrays.copyOf(runFaces, capacity);
                runCharStarts = Arrays.copyOf(runCharStarts, capacity);
                runCharEnds = Arrays.copyOf(runCharEnds, capacity);
                runLevels = Arrays.copyOf(runLevels, capacity);
                runGlyphStarts = Arrays.copyOf(runGlyphStarts, capacity);
                runGlyphEnds = Arrays.copyOf(runGlyphEnds, capacity);
            }
            runFaces[runCount] = faceId;
            runCharStarts[runCount] = charStart;
            runCharEnds[runCount] = charEnd;
            runLevels[runCount] = level;
            runGlyphStarts[runCount] = glyphCount;
            runGlyphEnds[runCount] = glyphCount;
            runCount++;
            return this;
        }

        /**
         * Adds one glyph to the open run, in the order the shaper emitted it &mdash; which for a
         * right-to-left run is already that run's visual order.
         *
         * <p>Position is not an argument. The builder places the run and runs the pen, so a caller
         * cannot put a run at the wrong x, and {@code metrics().width()} cannot disagree with where
         * the last glyph was drawn.
         *
         * @param glyphId the glyph index within the run's face, or {@link ShapedText#NO_GLYPH}; not
         *                a code point, and for a ligature not derivable from one
         * @param cluster the char offset into the <em>whole string</em> this glyph belongs to; a
         *                shaper reports these relative to the buffer it was handed, so the run's
         *                start has to be added back exactly once
         * @param advance how far the pen moves after this glyph, in logical points; zero for an
         *                attached mark
         * @param xOffset horizontal placement relative to the pen, in logical points
         * @param yOffset vertical placement relative to the baseline, in logical points, positive
         *                down as everywhere else on a {@link Canvas}
         * @throws IllegalStateException    if no run is open
         * @throws IllegalArgumentException if {@code cluster} falls outside the open run, which is
         *                                  the run-origin mistake caught at the call that made it
         */
        public Builder glyph(int glyphId, int cluster, float advance, float xOffset,
                             float yOffset) {
            if (runCount == 0) {
                throw new IllegalStateException("no run is open: call run(...) before glyph(...)");
            }
            int start = runCharStarts[runCount - 1];
            int end = runCharEnds[runCount - 1];
            if (cluster < start || cluster >= end) {
                // The one mistake ADR 031 names as fatal to the caret: a shaper reports clusters
                // relative to the buffer it was handed, so a run that forgets to add its own start
                // back reports offsets from the wrong origin. Caught here, at the call that made
                // it, rather than at build(), which could only name the assembler.
                throw new IllegalArgumentException("glyph cluster " + cluster
                        + " is outside the open run [" + start + ", " + end
                        + "); clusters are offsets into the whole string, not into the run");
            }
            if (glyphCount == glyphIds.length) {
                int capacity = Math.max(8, glyphCount * 2);
                glyphIds = Arrays.copyOf(glyphIds, capacity);
                glyphClusters = Arrays.copyOf(glyphClusters, capacity);
                glyphAdvances = Arrays.copyOf(glyphAdvances, capacity);
                glyphXOffsets = Arrays.copyOf(glyphXOffsets, capacity);
                glyphYOffsets = Arrays.copyOf(glyphYOffsets, capacity);
            }
            glyphIds[glyphCount] = glyphId;
            glyphClusters[glyphCount] = cluster;
            glyphAdvances[glyphCount] = advance;
            glyphXOffsets[glyphCount] = xOffset;
            glyphYOffsets[glyphCount] = yOffset;
            glyphCount++;
            return this;
        }

        /**
         * Freezes the value: reorders the runs by level, resolves the caret stops and their strong
         * and weak positions, sums the advances into the width and the cumulative table, and derives
         * {@link ShapedText#isSimple()}.
         *
         * @throws IllegalStateException if no {@linkplain #lineMetrics vertical metrics} were
         *                               supplied, or if the runs do not tile the text exactly
         */
        public ShapedText build() {
            if (!hasLineMetrics) {
                throw new IllegalStateException("lineMetrics(...) is required before build()");
            }
            int length = text.length();
            if (runCount > 0) {
                runGlyphEnds[runCount - 1] = glyphCount;
            }
            int covered = runCount == 0 ? 0 : runCharEnds[runCount - 1];
            if (covered != length) {
                throw new IllegalStateException("runs cover " + covered + " chars but the text is "
                        + length + "; runs must tile it exactly");
            }

            int[] stops = caretStops(length);
            int stopCount = stops.length;
            int clusterCount = stopCount - 1;

            // Cluster advances first, from every glyph regardless of order, so the pen below can be
            // run over CLUSTERS rather than glyphs: a cluster's box then cannot depend on the order
            // its own marks arrived in, and the total is the same sum either way.
            float[] clusterAdvance = new float[clusterCount];
            for (int g = 0; g < glyphCount; g++) {
                clusterAdvance[floorStop(stops, glyphClusters[g])] += glyphAdvances[g];
            }

            int[] order = reorderL2();

            // The glyph payload, permuted into visual order one whole run at a time. Glyphs inside
            // a run are already in that run's visual order, so this is a block move and never a
            // sort.
            int[] outIds = new int[glyphCount];
            int[] outClusters = new int[glyphCount];
            float[] outX = new float[glyphCount];
            float[] outY = new float[glyphCount];
            float[] outAdvance = new float[glyphCount];
            int[] visualGlyphStart = new int[runCount];
            int[] visualGlyphEnd = new int[runCount];
            int cursor = 0;
            for (int v = 0; v < runCount; v++) {
                int r = order[v];
                visualGlyphStart[v] = cursor;
                for (int g = runGlyphStarts[r]; g < runGlyphEnds[r]; g++, cursor++) {
                    outIds[cursor] = glyphIds[g];
                    outClusters[cursor] = glyphClusters[g];
                    outX[cursor] = glyphXOffsets[g];
                    outY[cursor] = glyphYOffsets[g];
                    outAdvance[cursor] = glyphAdvances[g];
                }
                visualGlyphEnd[v] = cursor;
            }

            // The pen, run by run in visual order and cluster by cluster within each run. An
            // right-to-left run's clusters are walked backwards, which is what turns logical order
            // into the order they are drawn in.
            float[] clusterX0 = new float[clusterCount];
            int[] clusterLevel = new int[clusterCount];
            long[] clusterRtl = new long[(clusterCount + 63) / 64];
            int[] visualOrder = new int[clusterCount];
            float pen = 0;
            int placed = 0;
            for (int v = 0; v < runCount; v++) {
                int r = order[v];
                boolean rtl = (runLevels[r] & 1) != 0;
                int first = floorStop(stops, runCharStarts[r]);
                int last = floorStop(stops, runCharEnds[r]) - 1;
                for (int step = 0; step <= last - first; step++) {
                    int j = rtl ? last - step : first + step;
                    clusterX0[j] = pen;
                    pen += clusterAdvance[j];
                    clusterLevel[j] = runLevels[r];
                    if (rtl) {
                        clusterRtl[j >>> 6] |= 1L << (j & 63);
                    }
                    visualOrder[placed++] = j;
                }
            }
            float width = pen;

            // Glyph x, from each cluster's own pen. Marks inside a cluster advance it in the order
            // they were emitted, which is that cluster's visual order.
            float[] clusterPen = clusterX0.clone();
            for (int g = 0; g < glyphCount; g++) {
                int j = floorStop(stops, outClusters[g]);
                outX[g] += clusterPen[j];
                clusterPen[j] += outAdvance[g];
            }

            int baseLevel = baseDirection == Direction.RTL ? 1 : 0;
            float startEdge = baseDirection == Direction.LTR ? 0 : width;
            float endEdge = baseDirection == Direction.LTR ? width : 0;
            float[] stopAdvances = new float[stopCount];
            float[] upstreamX = new float[stopCount];
            float[] downstreamX = new float[stopCount];
            long[] stopStrong = new long[(stopCount + 63) / 64];
            for (int k = 0; k < stopCount; k++) {
                if (k > 0) {
                    stopAdvances[k] = stopAdvances[k - 1] + clusterAdvance[k - 1];
                }
                boolean beforeRtl = k > 0 && (clusterRtl[(k - 1) >>> 6] & (1L << ((k - 1) & 63))) != 0;
                boolean afterRtl = k < clusterCount && (clusterRtl[k >>> 6] & (1L << (k & 63))) != 0;
                // Upstream is the TRAILING edge of the cluster before the stop; downstream is the
                // LEADING edge of the cluster after it. Where there is no such cluster the caret
                // sits on the paragraph's own edge, which is what makes the caret at index 0 of an
                // RTL line draw on the right.
                upstreamX[k] = k == 0
                        ? startEdge
                        : (beforeRtl ? clusterX0[k - 1] : clusterX0[k - 1] + clusterAdvance[k - 1]);
                downstreamX[k] = k == stopCount - 1
                        ? endEdge
                        : (afterRtl ? clusterX0[k] + clusterAdvance[k] : clusterX0[k]);
                int levelBefore = k == 0 ? baseLevel : clusterLevel[k - 1];
                int levelAfter = k == stopCount - 1 ? baseLevel : clusterLevel[k];
                boolean beforeMatchesBase = ((levelBefore ^ baseLevel) & 1) == 0;
                boolean afterMatchesBase = ((levelAfter ^ baseLevel) & 1) == 0;
                boolean downstreamStrong = beforeMatchesBase != afterMatchesBase
                        ? afterMatchesBase
                        : levelAfter <= levelBefore;
                if (downstreamStrong) {
                    stopStrong[k >>> 6] |= 1L << (k & 63);
                }
            }

            // isSimple is computed from what was actually supplied and never accepted from a
            // caller: itemization can change, and a flag that outlives the itemization that set it
            // sends every fast path in the toolkit down the wrong route for the one string that
            // needed the slow one.
            boolean simple = baseDirection == Direction.LTR
                    && runCount <= 1
                    && (runCount == 0 || (runLevels[0] & 1) == 0);
            for (int g = 1; simple && g < glyphCount; g++) {
                simple = outClusters[g] >= outClusters[g - 1];
            }

            Run[] visualRuns = new Run[runCount];
            for (int v = 0; v < runCount; v++) {
                int r = order[v];
                visualRuns[v] = new Run(runFaces[r], runCharStarts[r], runCharEnds[r],
                        visualGlyphStart[v], visualGlyphEnd[v], runLevels[r]);
            }

            return new ShapedText(text, font, new TextMetrics(width, ascent, descent, lineHeight),
                    baseDirection, simple, epoch, List.of(visualRuns), outIds, outClusters, outX,
                    outY, outAdvance, stops, stopAdvances, upstreamX, downstreamX, stopStrong,
                    visualOrder, clusterRtl);
        }

        /**
         * The caret stops: every run boundary and every cluster the shaper reported, plus the two
         * ends. A run boundary is necessarily a cluster boundary because a run is shaped alone, and
         * including it is what gives a glyphless run of control characters a stop of its own.
         */
        private int[] caretStops(int length) {
            int[] raw = new int[2 + 2 * runCount + glyphCount];
            int n = 0;
            raw[n++] = 0;
            raw[n++] = length;
            for (int r = 0; r < runCount; r++) {
                raw[n++] = runCharStarts[r];
                raw[n++] = runCharEnds[r];
            }
            for (int g = 0; g < glyphCount; g++) {
                raw[n++] = glyphClusters[g];
            }
            Arrays.sort(raw, 0, n);
            int[] stops = new int[n];
            int count = 0;
            for (int i = 0; i < n; i++) {
                if (count == 0 || stops[count - 1] != raw[i]) {
                    stops[count++] = raw[i];
                }
            }
            return Arrays.copyOf(stops, count);
        }

        /**
         * Unicode Bidirectional Algorithm rule L2 over the runs: from the highest level present
         * down to the lowest odd one, reverse every contiguous stretch at that level or above.
         * Driven by levels rather than parity because nesting is what parity cannot express.
         */
        private int[] reorderL2() {
            int[] order = new int[runCount];
            for (int i = 0; i < runCount; i++) {
                order[i] = i;
            }
            int highest = 0;
            int lowestOdd = Integer.MAX_VALUE;
            for (int r = 0; r < runCount; r++) {
                highest = Math.max(highest, runLevels[r]);
                if ((runLevels[r] & 1) != 0) {
                    lowestOdd = Math.min(lowestOdd, runLevels[r]);
                }
            }
            for (int level = highest; level >= lowestOdd; level--) {
                int i = 0;
                while (i < runCount) {
                    if (runLevels[order[i]] < level) {
                        i++;
                        continue;
                    }
                    int j = i;
                    while (j + 1 < runCount && runLevels[order[j + 1]] >= level) {
                        j++;
                    }
                    for (int a = i, b = j; a < b; a++, b--) {
                        int swap = order[a];
                        order[a] = order[b];
                        order[b] = swap;
                    }
                    i = j + 1;
                }
            }
            return order;
        }
    }

    // ---------------------------------------------------------------- value types

    /**
     * One horizontal box of a selection, in logical points from the left edge of the line.
     * Horizontal only: the band is the caller's, drawn from {@link ShapedText#metrics()}, so one box
     * serves a selection fill, an IME underline and a composition highlight alike.
     *
     * @param x0 left edge
     * @param x1 right edge, never less than {@code x0}
     */
    public record Span(float x0, float x1) {

        /** {@code x1 - x0}. */
        public float width() {
            return x1 - x0;
        }
    }

    /**
     * Which side of a char index a caret sits on, and so which of the two points that index can
     * occupy is meant.
     *
     * <p>A widget that stores this alongside the index is a widget whose caret survives a click on
     * either side of a direction boundary and two arrow presses in a row. One that does not should
     * draw {@linkplain Caret#split() both} positions rather than pick one.
     */
    public enum Affinity {

        /**
         * The caret belongs to the character <em>before</em> the index and draws at its trailing
         * edge. This is the side a caret takes after typing: the text just inserted is what the
         * caret trails, so the next character of the same script appears where the caret is.
         */
        UPSTREAM,

        /**
         * The caret belongs to the character <em>at</em> the index and draws at its leading edge.
         * The side to take when there is nothing better to go on &mdash; a caret placed
         * programmatically, or restored with the text.
         */
        DOWNSTREAM
    }

    /**
     * A caret position: where text is inserted, plus which side of that index the caret is on.
     *
     * <p>The index alone is the insertion point, and is what the edit model, the clipboard and the
     * IME need. The pair is what the <em>line</em> needs: on a direction boundary one index is two
     * points, so {@link ShapedText#hitTest} hands back a pair and {@link ShapedText#caretLeft} moves
     * between pairs. Off a boundary the side changes nothing.
     *
     * @param charIndex char index into the line's text, in logical order
     * @param affinity  which side of it the caret is on
     */
    public record Position(int charIndex, Affinity affinity) {
    }

    /**
     * The two places a caret may sit for one char index, for a caller that stores no side.
     *
     * <p>Both are true, and that is the point. At the end of Latin text followed by Hebrew, a
     * left-to-right keystroke appears where the Latin run ends and a right-to-left keystroke appears
     * where the Hebrew run ends; those are different points on the line, and a caret drawn at one of
     * them alone tells the user the wrong thing about where the next character will land. Drawing
     * {@link #strongX()} full height and {@link #weakX()} as a smaller mark is the usual answer;
     * drawing only the strong one is a decision to lie in the mixed case.
     *
     * <p>Off a boundary the two are the same point, and they are the <em>same float</em>, copied
     * from one caret stop &mdash; so {@link #split()} is an exact comparison rather than a float
     * equality test standing in for one, and a boolean field that could contradict the two positions
     * does not exist.
     *
     * @param upstreamX          x of the trailing edge of the character before the index; at index
     *                           {@code 0} there is none, and this is the paragraph's own start edge
     *                           in the base direction
     * @param downstreamX        x of the leading edge of the character at the index; at the end of
     *                           the text there is none, and this is the paragraph's own end edge in
     *                           the base direction
     * @param downstreamIsStrong whether {@code downstreamX} is the strong side: the one whose bidi
     *                           level has the base direction's parity, and the lower level of the
     *                           two when both do
     */
    public record Caret(float upstreamX, float downstreamX, boolean downstreamIsStrong) {

        /** Whether the two positions differ, and a second mark therefore has to be drawn. */
        public boolean split() {
            return upstreamX != downstreamX;
        }

        /** Where a keystroke in the base direction lands: the caret to draw at full height. */
        public float strongX() {
            return downstreamIsStrong ? downstreamX : upstreamX;
        }

        /**
         * Where a keystroke in the other direction lands; equal to {@link #strongX()} off a
         * boundary.
         */
        public float weakX() {
            return downstreamIsStrong ? upstreamX : downstreamX;
        }

        /**
         * The one position a caller with a stored side means.
         *
         * @param affinity which side of the index the caret is on
         */
        public float x(Affinity affinity) {
            return affinity == Affinity.UPSTREAM ? upstreamX : downstreamX;
        }
    }

    /**
     * One shaped stretch: a single face, a single embedding level, a single shaping call, and the
     * unit the backend resolves a face at and batches a draw at.
     *
     * @param faceId     the producing ruler's identifier for the face; opaque above that ruler
     * @param charStart  first char offset of the text this run covers
     * @param charEnd    one past the last
     * @param glyphStart first glyph index of this run
     * @param glyphEnd   one past the last
     * @param level      the bidi embedding level; even reads left to right
     */
    public record Run(int faceId, int charStart, int charEnd, int glyphStart, int glyphEnd,
                      int level) {

        /**
         * Whether the characters run right to left inside this run's own box: the level's parity,
         * derived rather than carried, so no field can contradict another.
         */
        public boolean rtl() {
            return (level & 1) != 0;
        }
    }

    /**
     * Which way a paragraph reads.
     *
     * <p><b>There is deliberately no third constant meaning "decide later".</b> Direction is
     * resolved from the text before anything is shaped, and by the time a {@code ShapedText} exists
     * the answer is known &mdash; so a constant standing for the question would be a value a widget
     * could store in a field, hand back through {@link ShapedText#baseDirection()}, and compare
     * against. Stating a direction and declining to state one are different acts, and they are
     * spelled as different {@code shape} overloads on {@link TextRuler} rather than as two values of
     * one type.
     *
     * <p>This is the direction of a <em>run of text</em>, which is why it lives here. Direction as a
     * layout axis &mdash; leading and trailing insets, mirrored ink, arrow semantics, popup
     * anchoring &mdash; is a separate decision that has not been taken, and pre-empting it with a
     * type in the drawing package would put a text-shaping concern in every layout signature it
     * touches.
     */
    public enum Direction {

        /**
         * Left to right: Latin, Greek, Cyrillic, the CJK scripts as this toolkit sets them, and the
         * answer for a string with no strong character at all.
         */
        LTR,

        /** Right to left: Arabic, Hebrew. */
        RTL;

        /**
         * The base direction of {@code text} under the Unicode Bidirectional Algorithm's
         * first-strong-character rule, as {@code java.text.Bidi} implements it.
         *
         * <p>The rule lives here, in one place with no backend under it, so that every ruler
         * resolves a paragraph the same way and a test can pin it with nothing installed.
         *
         * <p>{@code whenNeutral} is returned for text with no strong character at all
         * ({@code "42"}, {@code "(...)"}, the empty string), which is the one case the rule cannot
         * answer. It is a parameter rather than a constant because the right answer there is the
         * direction of the surrounding user interface, which nothing in this package owns yet;
         * passing {@link #LTR} reproduces the behaviour of a toolkit with no direction axis, and
         * this parameter is the seam where that gets fixed.
         *
         * @param text        the paragraph to inspect
         * @param whenNeutral what to return when {@code text} contains no strong character
         * @throws NullPointerException if either argument is null
         */
        public static Direction of(String text, Direction whenNeutral) {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(whenNeutral, "whenNeutral");
            if (text.isEmpty()) {
                return whenNeutral;
            }
            switch (firstStrongWithoutBidi(text)) {
                case SCAN_LTR:
                    return LTR;
                case SCAN_RTL:
                    return RTL;
                case SCAN_NEUTRAL:
                    // Rule P2 found nothing and there is no formatting for it to have skipped, so
                    // P3 falls through to the default. Two whole analyses of a string already known
                    // to say nothing is what this avoids.
                    return whenNeutral;
                default:
                    break;
            }
            // Bidi is asked twice rather than scanned by hand because the first-strong rule skips
            // whatever sits between an isolate initiator and its matching PDI, and that rule is
            // already implemented here. Defaulting one way and then the other separates "the text
            // said so" from "the default said so": they agree exactly when a strong character
            // decided it.
            if (!new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()) {
                return RTL;
            }
            return new Bidi(text, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT).baseIsLeftToRight()
                    ? LTR
                    : whenNeutral;
        }

        private static final int SCAN_LTR = 0;
        private static final int SCAN_RTL = 1;
        /** Scanned to the end: no strong character, and no formatting that could have hidden one. */
        private static final int SCAN_NEUTRAL = 2;
        /** Met an embedding, override or isolate: the rule has a subtlety here, so ask Bidi. */
        private static final int SCAN_DEFER = 3;

        /**
         * Rule P2 by hand for the strings where P2 has no subtlety in it, and {@link #SCAN_DEFER}
         * for the ones where it does.
         *
         * <p>This exists because of what it costs not to have it. Every {@code shape(text, font)}
         * resolves a direction, every {@code measure} on a shaping ruler is one of those, and a
         * layout pass measures every caption on the screen: two {@code java.text.Bidi}
         * constructions had become the dominant cost of a text frame, at some 318 ns per string
         * against 12 ns for the memo lookup they were standing in front of. A {@code Bidi} is a
         * full analysis of a whole paragraph; this question is answered by one character.
         *
         * <p>The scan stops at the first character that decides anything, which is rule P2 as
         * written: {@code L} makes the paragraph left to right, {@code R} or {@code AL} makes it
         * right to left, and by construction nothing before it was strong. Everything else —
         * digits, punctuation, spaces, combining marks — is skipped, because P2 skips it.
         *
         * <p>It gives up the moment it meets an embedding, an override or an isolate initiator,
         * and that is the whole reason the {@code Bidi} pair is still below: P2 skips the
         * characters between an isolate initiator and its matching PDI, and reproducing that here
         * would be reimplementing the interesting half of the algorithm to save a construction.
         * Those characters do not occur in the text a user interface draws, so deferring costs
         * nothing on the path this method was written for.
         *
         * <p>It gives up for a second reason, and that one is about this JVM rather than about the
         * rule: a code point {@link Character#getDirectionality} answers
         * {@link Character#DIRECTIONALITY_UNDEFINED} for is one whose class this switch cannot
         * see, not one that has none. Skipping it would silently substitute "unassigned in JDK
         * {@code n}" for "not strong", which are different questions with different answers in
         * every default-right-to-left range.
         */
        private static int firstStrongWithoutBidi(String text) {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                // The three isolate initiators and the PDI are tested by code point rather than by
                // directionality class: this JDK's Character.DIRECTIONALITY vocabulary predates
                // them and reports them as ordinary neutrals, so a switch alone would walk straight
                // past the one construct this method must not try to answer.
                if (cp >= 0x2066 && cp <= 0x2069) {
                    return SCAN_DEFER;
                }
                switch (Character.getDirectionality(cp)) {
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT:
                        return SCAN_LTR;
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
                        return SCAN_RTL;
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING:
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE:
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING:
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE:
                    case Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT:
                        return SCAN_DEFER;
                    case Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR:
                        // Rule P1 first: a newline ends the paragraph, and P2 runs over the FIRST
                        // one only. Nothing after this can decide the direction, so the answer is
                        // already "no strong character" — and a scan that read past it would call
                        // a line starting "42\nabc" left-to-right where the rule calls it neutral.
                        return SCAN_NEUTRAL;
                    case Character.DIRECTIONALITY_UNDEFINED:
                        // A code point THIS JVM has no class for, which is not the same as a code
                        // point with no class. Unicode gives an unassigned code point the default
                        // of the range it sits in, so every hole in the Hebrew, Arabic, Thaana,
                        // Adlam or Garay ranges is R or AL to the algorithm; java.text.Bidi
                        // carries those range defaults and this switch does not, so skipping here
                        // is not "nothing decided", it is deciding by a table that is one Unicode
                        // version behind the text. Garay is the live case rather than a
                        // hypothetical: an RTL script added in Unicode 16, unassigned in this
                        // JDK's tables, and a line of it would resolve LTR — caret at the wrong
                        // edge, Home and End on the wrong side. Deferring is the rule this scan
                        // is an optimization OF: it may answer only where it knows what Bidi
                        // knows.
                        return SCAN_DEFER;
                    default:
                        break;
                }
            }
            return SCAN_NEUTRAL;
        }
    }
}

package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.text.TextEditModel;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Rect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.event.PreeditEvent;

import java.text.BreakIterator;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Multiline text editor: hard line breaks (Enter), arrow navigation across
 * lines with a sticky column, mouse/Shift+arrow selection spanning lines,
 * clipboard shortcuts, blinking cursor, and both scrollbars: draggable
 * thumbs and wheel-responsive. Long lines scroll horizontally by default;
 * {@link #setSoftWrap} breaks them at the text column's edge instead, at the
 * opportunities the UI language's line-break rule finds, and the horizontal
 * axis goes quiet.
 *
 * <p>Colors come from the {@link Theme}, metrics from the {@link SizeTokens} row of the
 * {@link limn.scene.ControlSize} step resolved on this widget, and every stroke from
 * {@link Strokes}.
 *
 * <p><b>The content inset is anisotropic, and deliberately so.</b> Horizontally it is
 * {@code fieldPadH}, the same token {@link TextField} insets its text by, so a field and an
 * area stacked in a form put their first character on the same column. Vertically it is
 * {@code areaPad}: a text margin rather than a vertical-centring pad, which is why it is
 * its own token and nothing outside this widget has to agree with it.
 *
 * <p>The preferred box is derived per step to hold a roughly constant amount of visible
 * <em>content</em> rather than a constant box: an editor is where character count matters
 * most.
 *
 * <p><b>Every geometric question is asked of a shaped line, one per visible line.</b> Caret x, hit
 * testing and the selection band all come from the {@link ShapedText} of the line they concern
 * ({@link #shapedRow}) rather than from the measured width of a prefix of it, because with a
 * shaper in the pipeline the width of the first {@code n} characters of a line is not the width of
 * those characters inside it: they join, ligate, kern and <em>reorder</em> differently. The
 * consequences a user sees are that a click lands on the character under the pointer in mixed text,
 * that a selection crossing a direction boundary paints as the two or more boxes it really covers,
 * and that Left and Right move the caret by what is next <em>on the screen</em>.
 *
 * <p><b>Left and Right are visual; everything else is logical.</b> Home, End, word movement,
 * Backspace, Delete, Page and Up/Down all step through the <em>string</em>, because each has to
 * name a contiguous range: {@code Shift+Home} makes a selection and a selection is one range of the
 * buffer. So in right-to-left text Left and Ctrl+Left move the caret in opposite directions, which
 * is what Windows and GTK do and the lesser of the two evils.
 *
 * <p><b>Soft wrap is a row map, never a second copy of the document.</b> Wrapping, each hard
 * line holds the char offsets its rows start at (ints), a prefix sum turns a global row into a
 * line and back, and every geometry question &mdash; caret, click, selection band, scroll extent
 * &mdash; is asked of the row under it, through the same shaped-line window the unwrapped mode
 * paints from (unwrapped, a line simply <em>is</em> its one row). Building the map costs one
 * shaping per line and is paid when the text is replaced or the column width, font, ruler epoch
 * or locale moves; an <b>edit re-wraps only the lines it touched</b>, told apart by
 * {@link TextEditModel#lineDamage()}. That seam exists because re-deriving per-line state from
 * {@code textVersion()} alone means re-shaping the document per keystroke &mdash; the cliff
 * ADR&nbsp;031&nbsp;&sect;8.2 measured at 22&nbsp;ms per character typed &mdash; and a soft wrap
 * that reintroduced it would be a regression dressed as a feature.
 *
 * <p><b>The scrollbars do not follow the step</b>, so the overlay bars stay 15&nbsp;pt wide
 * while the pads shrink at the small steps and the bars float over more live text there. An
 * accepted cost: at XSMALL, prefer a trailing right margin in the surrounding layout if the
 * last column matters.
 */
public class TextArea extends Widget {

    private static final double BLINK_SECONDS = 0.5;
    /**
     * The vertical-metrics probe. Ascender + descender in one string, so the line box it
     * reports is the tallest the face can produce and does not depend on the content.
     */
    private static final String PROBE = "Hg";

    private final TextEditModel model = new TextEditModel(false);
    private final ScrollBar vBar;
    private final ScrollBar hBar;
    private final ScrollGutters gutters = new ScrollGutters();
    private Consumer<String> onChange = text -> {
    };
    /**
     * {@code < 0} means "unset": {@link #onMeasure} falls back to the resolved step's
     * {@code areaWidth}/{@code areaHeight}. A step cannot be read in a field initializer:
     * the widget has no parent yet and would latch the process default forever.
     */
    private float preferredWidth = -1;
    private float preferredHeight = -1;
    private float scrollX;
    private float scrollY;
    private boolean cursorVisible = true;
    private int blinkGeneration;
    // Active IME composition ("preedit"): shown inline at the cursor on its line,
    // underlined, NOT part of the model; the commit arrives as a CharEvent.
    private String preedit = "";
    private int preeditCaret;      // caret within the preedit, in chars
    private int preeditFocusStart; // char range of the focused (converting) block
    private int preeditFocusEnd;
    /** Widest measured line, cached; {@code < 0} = dirty. Recomputed only after edits. */
    private float cachedContentWidth = -1;
    /** The font the width cache was built under, half of its validity key. */
    private Font cachedWidthFont;
    /** Probe metrics under that font, the other half. See {@link #contentWidth}. */
    private TextMetrics cachedWidthProbe;
    /** Focus-ring fade: morphs the border between outline and focusRing. */
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);
    private TextField.Validation validation = TextField.Validation.NONE;
    /** Whether long lines wrap at the text column's edge instead of scrolling; see {@link #setSoftWrap}. */
    private boolean softWrap;
    /**
     * Sticky goal x for wrap-mode Up/Down/Page runs, in content space; {@code NaN} = unset. The
     * widget's own, beside the model's sticky goal <em>column</em>: a column is a count of chars
     * within a hard line, which is the right invariant while a line is one row and meaningless
     * once it is several. Cleared by everything that is not a vertical step, exactly as the
     * model clears its column.
     */
    private float goalX = Float.NaN;

    /** An empty editor. */
    public TextArea() {
        setFocusable(true);
        setCursor(Cursor.TEXT); // I-beam over editable text
        // The shared scrollbar component: same look/feel as every other scroller.
        // Each callback resolves the step itself: the ScrollBar children invoke these from
        // their OWN event handling, where there is no enclosing measure/paint pass to thread
        // a token record down from (the same reason caretRect() resolves).
        vBar = new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return contentHeight(tokens());
            }

            @Override
            public float viewportLength() {
                return viewHeight(tokens());
            }

            @Override
            public float offset() {
                return scrollY;
            }

            @Override
            public void setOffset(float value) {
                scrollY = value;
                clampScroll(tokens());
                invalidate();
            }
        }).setPolicy(ScrollBar.Policy.ALWAYS);
        hBar = new ScrollBar(ScrollBar.Orientation.HORIZONTAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return contentWidth(tokens());
            }

            @Override
            public float viewportLength() {
                return viewWidth(tokens());
            }

            @Override
            public float offset() {
                return scrollX;
            }

            @Override
            public void setOffset(float value) {
                scrollX = value;
                clampScroll(tokens());
                invalidate();
            }
        }).setPolicy(ScrollBar.Policy.ALWAYS);
        add(vBar);
        add(hBar);
    }

    // ------------------------------------------------------------------- API

    /** The full contents, lines joined by {@code \n}. */
    public String text() {
        return model.text();
    }

    /** Replaces the contents, clearing the selection and undo history. UI thread only. */
    public TextArea setText(String text) {
        Ui.checkUiThread();
        model.setText(text);
        scrollX = 0;
        scrollY = 0;
        goalX = Float.NaN;
        invalidateContentWidth();
        invalidate();
        return this;
    }

    /**
     * Turns soft wrap on or off (default off: long lines scroll). Wrapping, a line wider than
     * the text column breaks at the opportunities {@link BreakIterator} finds under the
     * {@linkplain I18n#locale() UI language} &mdash; the same walk {@link Label} wraps with, so a
     * paragraph breaks in an editor exactly where it breaks in the prose beside it, unspaced CJK
     * and dictionary-segmented Thai included &mdash; and the horizontal axis goes quiet: nothing
     * overflows it, {@link #scrollXOffset()} pins at {@code 0}, and the horizontal bar shows no
     * thumb. Reading right to left every row is flush against the edge reading starts from, the
     * right one, exactly as unwrapped lines are.
     *
     * <p>Wrapped, Up, Down and Page move by <b>visual row</b> on a sticky goal <em>x</em>, where
     * unwrapped they move by hard line on the model's sticky goal column: a column is the right
     * invariant while a line is one row and meaningless once it is several. Left and Right step
     * onto the neighbouring row when the current one runs out, exactly as they already step onto
     * the neighbouring line. Home, End and Shift+Home stay hard-line and logical, as everywhere
     * else in the toolkit, so a selection is always one range of the buffer.
     *
     * <p>A caret whose index sits exactly on a soft break is two places on screen &mdash; the end
     * of one row and the start of the next &mdash; and the caret's
     * {@linkplain ShapedText.Affinity side} says which, the same side that already disambiguates
     * a direction boundary. The whitespace a break drops is not deleted: it hangs past the
     * margin, undrawn, and a caret inside it shows at the row's drawn end.
     *
     * <p>Toggling resets the horizontal scroll, because the axis changes meaning; the vertical
     * offset keeps its value and re-clamps against the new extent. UI thread only.
     */
    public TextArea setSoftWrap(boolean wrap) {
        Ui.checkUiThread();
        if (softWrap == wrap) {
            return this;
        }
        softWrap = wrap;
        scrollX = 0;
        goalX = Float.NaN;
        rowStartsByLine = null;
        rowOffsets = null;
        rowMapVersion = -1;
        rowMapOverlayLine = -1;
        rowMapGeneration++; // drops the shaped-row window: its slots were the other mode's rows
        invalidateContentWidth();
        markNeedsLayout();
        return this;
    }

    /** Whether long lines wrap at the text column's edge instead of scrolling. */
    public boolean softWrap() {
        return softWrap;
    }

    /** Called with the full text after every edit, typed or programmatic. */
    public TextArea onChange(Consumer<String> listener) {
        Ui.checkUiThread();
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Inserts {@code text} at the cursor (replacing any selection), as if typed. UI thread. */
    public TextArea insertText(String text) {
        Ui.checkUiThread();
        if (text == null || text.isEmpty()) {
            return this;
        }
        model.insert(text);
        fireChange();
        ensureCursorVisible();
        resetBlink();
        invalidate();
        return this;
    }

    /** Sets the validation state; colors the border danger/warning/success. */
    public TextArea setValidation(TextField.Validation state) {
        Ui.checkUiThread();
        this.validation = Objects.requireNonNull(state, "state");
        invalidate();
        return this;
    }

    /** Convenience: {@code ERROR} when true, {@code NONE} when false. */
    public TextArea setError(boolean error) {
        return setValidation(error ? TextField.Validation.ERROR : TextField.Validation.NONE);
    }

    /**
     * Overrides the step's {@code areaWidth}/{@code areaHeight}. A negative value on either
     * axis restores that axis's token, so {@code setPreferredSize(-1, 150)} pins the height
     * and lets the width follow the step.
     */
    public TextArea setPreferredSize(float width, float height) {
        Ui.checkUiThread();
        this.preferredWidth = width;
        this.preferredHeight = height;
        markNeedsLayout();
        return this;
    }

    /** The editing model: caret, selection and undo. Mutating it directly bypasses {@link #onChange}. */
    public TextEditModel model() {
        return model;
    }

    /**
     * Horizontal scroll offset in logical points, {@code 0} at the <b>leading</b> edge: the left
     * edge in a left-to-right subtree and the right edge in a right-to-left one. The range is
     * {@code [0, maxScrollX]} in both, so "scrolled to the start" is {@code 0} either way and a
     * reset on a content change needs no branch. Under {@linkplain #setSoftWrap soft wrap}
     * nothing overflows this axis and the offset stays {@code 0}.
     */
    public float scrollXOffset() {
        return scrollX;
    }

    /** Vertical scroll offset in logical points, {@code 0} at the first line. */
    public float scrollYOffset() {
        return scrollY;
    }

    /** Scrolls by a delta in logical points (clamped to the content). UI thread only. */
    public TextArea scrollBy(float dx, float dy) {
        Ui.checkUiThread();
        scrollX += dx;
        scrollY += dy;
        clampScroll(tokens());
        invalidate();
        return this;
    }

    // -------------------------------------------------------------- metrics

    /**
     * Entry-point resolve: one call, into a local, for the paths the toolkit enters from
     * outside a measure/paint pass (the scrollbar model callbacks, the async blink chain,
     * the public scroll API). Everything reached from measure, paint or an event handler
     * takes the record as a parameter instead; two resolutions that disagree inside one
     * component put the click on a different line from the one that was drawn.
     */
    private SizeTokens tokens() {
        return Theme.current().tokensFor(this);
    }

    private float lineHeight(SizeTokens t) {
        return textRuler().measure(PROBE, t.body()).lineHeight();
    }

    // Overlay scrollbars: the content uses the full padded area; the thin bars
    // float over the edge (in the padding gutter) instead of reserving a strip
    // that would clip the content on the right and bottom. See the class note on
    // what that costs at XSMALL, where the bar is wider than 2x the pad.
    //
    // The two axes read DIFFERENT tokens: fieldPadH across, areaPad down. Every
    // consumer of the horizontal inset (the clip, the translate, the caret clamp and the
    // press mapping) goes through these two or through the same pair of locals in the
    // paint/event pass; splitting the pad without moving all of them together lands a click
    // on a different character from the one under the pointer.
    /**
     * The text column: the box less its padding, and less any strip a reserved bar
     * has taken. Everything downstream (the clip, the caret's reveal, the two
     * bars' own models) is measured from here, so reserving a strip narrows the
     * text rather than letting a thumb sit on the end of a line.
     */
    private float viewWidth(SizeTokens t) {
        return Math.max(0, width() - 2 * t.fieldPadH() - gutters.verticalStrip());
    }

    /** Whether this area reads right to left. Resolve it once per pass. */
    private boolean isRtl() {
        return layoutDirection() == LayoutDirection.RTL;
    }

    /**
     * Physical left edge of the text column: the pad, plus the vertical bar's strip when that
     * bar is on this side. Reading right to left the bar takes the side reading ends on, which
     * is the left, so the column starts after it. {@link ScrollGutters} answers how much a strip
     * takes and never which side takes it, so the side is resolved here.
     */
    private float columnLeft(SizeTokens t) {
        return t.fieldPadH() + (isRtl() ? gutters.verticalStrip() : 0);
    }

    /**
     * Where content space puts its <b>left</b> edge in this widget's own coordinates: the paint
     * translate, and the one conversion every click, caret and clamp goes through.
     *
     * <p>Reading left to right the content starts at the pad and {@code scrollX} pulls it back.
     * Reading right to left it is pushed out until its right edge meets the viewport's right
     * edge, and {@code scrollX} pushes it further, which is the same convention a
     * {@link ScrollView} uses: zero is the leading edge and the offset is a distance travelled.
     */
    private float contentOriginX(SizeTokens t) {
        return isRtl()
                ? columnLeft(t) + viewWidth(t) - contentWidth(t) + scrollX
                : columnLeft(t) - scrollX;
    }

    /**
     * Where the visible window starts <b>in content coordinates</b>: what {@code scrollX} means
     * once the direction has been applied. It grows with {@code scrollX} one way round and
     * shrinks with it the other, which is all {@link #ensureCursorVisible} has to know.
     */
    private float viewStartX(SizeTokens t) {
        return columnLeft(t) - contentOriginX(t);
    }

    /**
     * Where one row puts its left edge inside content space (unwrapped, a line is its one row).
     * Reading left to right every row starts at zero; reading right to left each is flush
     * against the content's <em>right</em> edge, so a short row and a long one share the edge
     * reading starts from rather than the one it ends on. Wrapped, the content is exactly the
     * text column, so that edge is the column's own right edge.
     */
    private float rowOriginX(ShapedText row, SizeTokens t) {
        return isRtl() ? contentWidth(t) - row.metrics().width() : 0;
    }

    private float viewHeight(SizeTokens t) {
        return Math.max(0, height() - 2 * t.areaPad() - gutters.horizontalStrip());
    }

    /**
     * Sets whether the bars float over the text or reserve strips of their own
     * (default {@link ScrollGutters.Layout#OVERLAY}, which is what prose wants).
     */
    public TextArea setBarLayout(ScrollGutters.Layout layout) {
        Ui.checkUiThread();
        gutters.setLayout(layout);
        markNeedsLayout();
        return this;
    }

    /** Whether the scrollbars overlay the text or reserve a gutter. */
    public ScrollGutters.Layout barLayout() {
        return gutters.layout();
    }

    /**
     * Widest line. Cached because measuring every line is O(lines) string scans plus a measure
     * per line, and it is consulted several times per paint/scroll event.
     *
     * <p><b>The cache is keyed on the metrics that produced it, not on a dirty flag alone</b>
     *. The flag is cleared by {@link #invalidateContentWidth}, which only the text-changing paths
     * call, so nothing clears it on the metrics path and the cache was <em>already</em>
     * stale after a runtime {@code Fonts.setDefaultFamily} switch: {@code Scene.relayout} only
     * marks measure caches dirty and never reaches a widget's private state. The size axis adds
     * a second such path, since a step change swaps the body font underneath the same text.
     * Validating against a probe measurement closes both without a cross-file hook, and it has
     * to be the <em>measurement</em> rather than the {@link Font}: a family re-bound underneath
     * {@code Font.DEFAULT_FAMILY} leaves the record identical and only its metrics move.
     *
     * <p><b>This scans the document, so it must not <em>hold</em> the document &mdash; and it must
     * not make the ruler hold it either.</b> One {@link ShapedText} kept per <em>painted</em> line
     * is the budget; one kept per line of the buffer is a second copy of a long document, and the
     * scrollbar's extent is the one question that has to look at every line. So the scan asks
     * {@link TextRuler#scanWidth} and keeps nothing, at either end.
     *
     * <p><b>{@code scanWidth} rather than {@code measure}, and the difference is the whole cost of
     * typing in a long document.</b> A shaping ruler answers {@code measure} by shaping into a
     * bounded memo, which is right for the strings a frame is about to paint and wrong for this
     * loop in every way a loop can be wrong for a cache: it touches every line, in the same cyclic
     * order, so past the memo's depth it misses on every line every time; it re-runs on every
     * keystroke, because {@link #invalidateContentWidth} drops the cache and the
     * {@link #ensureCursorVisible} that follows the edit reads it straight back; and the memo is
     * process-wide, so on its way through it evicts the captions belonging to widgets that did
     * nothing, which then repaint cold. Measured on a 1000-line document, that is a whole
     * re-shaping of the document per character typed. A scan over text nobody is drawing must not
     * be what decides which strings live in a shared shape memo.
     *
     * <p><b>A scanned width is not guaranteed to be an upper bound on a shaped one, and where the
     * two differ the difference is not a hairline.</b> {@code scanWidth} is allowed to be the cheap
     * answer, and the backend's is: a sum of per-code-point advances, which resolves a face per
     * character where shaping resolves one per run. A space between two Hebrew words is scanned in
     * the Latin primary that covers it and shaped in the Hebrew face the run resolved to, and the
     * two faces disagree about how wide a space is. That is a per-seam difference in the
     * <em>wider</em> direction and it accumulates, so a 200-character Hebrew line can shape some ten
     * points wider than it scans. An extent short by ten points is not a rounding error &mdash;
     * {@link #ensureCursorVisible} asks for a scroll the clamp refuses, and the caret is painted
     * outside the clip and simply vanishes.
     *
     * <p>So the gap is now deliberate rather than a ruler's inconsistency, and
     * {@link #shapedWidthFloor} is load-bearing rather than defensive. It would be needed anyway:
     * {@link TextRuler} does not <em>promise</em> that any of its widths agree, and cannot &mdash;
     * the interface's own default {@code shape} measures a cluster at a time and loses the kern at
     * every cluster seam, so a ruler that inherits it disagrees with its own {@code measure} by
     * construction. A widget does not know which kind of ruler it has, and the failure mode of
     * assuming is a caret that vanishes rather than a line that is a hair short.
     *
     * <p>So the extent is the larger of the two answers this widget has: the measured maximum over
     * the whole document, and {@link #shapedWidthFloor}, the widest line it has actually shaped.
     * Those two together are exactly enough, and the reason is that <b>reaching a line is what
     * shapes it</b>: the caret's line goes through {@link #shapedRow} before the clamp reads this,
     * and a line whose ink is on screen was shaped to paint it. A line the user has never been near
     * contributes its measured width, which can only make the extent too small for text nobody can
     * see yet &mdash; and the moment they scroll to it, it is shaped and the extent is right.
     */
    private float contentWidth(SizeTokens t) {
        if (softWrap) {
            // Wrapped, the content is exactly as wide as the column it wraps to: nothing can
            // overflow the horizontal axis, the clamp pins scrollX at 0, and the bar has no
            // thumb to show. The scan below, the floor and their whole reconciliation problem
            // exist only where a line is allowed to be wider than the viewport.
            return viewWidth(t);
        }
        Font f = t.body();
        TextMetrics probe = textRuler().measure(PROBE, f);
        if (cachedContentWidth < 0 || !f.equals(cachedWidthFont) || !probe.equals(cachedWidthProbe)) {
            TextRuler ruler = textRuler();
            float widest = 0;
            for (int line = 0; line < model.lineCount(); line++) {
                widest = Math.max(widest, ruler.scanWidth(model.lineText(line), f));
            }
            cachedContentWidth = widest;
            cachedWidthFont = f;
            cachedWidthProbe = probe;
        }
        return Math.max(cachedContentWidth, shapedWidthFloor);
    }

    /**
     * The widest line this widget has shaped since the last time its text, font or ruler moved:
     * the half of {@link #contentWidth} that a scan of measured widths cannot supply.
     *
     * <p>Fed on <em>every</em> call that hands out a shaping, not only on the calls that build one,
     * so that a reset re-fills from the values already held rather than waiting for them to be
     * rebuilt. Dropped wherever a held shaping is dropped, because a width from a line that has
     * since been edited away is scroll range over nothing.
     */
    private float shapedWidthFloor;

    /** Folds one shaped line into {@link #shapedWidthFloor} and hands it straight back. */
    private ShapedText noteShaped(ShapedText line) {
        shapedWidthFloor = Math.max(shapedWidthFloor, line.metrics().width());
        return line;
    }

    private float contentHeight(SizeTokens t) {
        return totalRows(t) * lineHeight(t);
    }

    private float maxScrollX(SizeTokens t) {
        return Math.max(0, contentWidth(t) - viewWidth(t));
    }

    private float maxScrollY(SizeTokens t) {
        return Math.max(0, contentHeight(t) - viewHeight(t));
    }

    /**
     * Both offsets into their ranges. The horizontal clamp keeps its form in both directions:
     * {@code scrollX} is a distance travelled from the leading edge, never a coordinate, so only
     * the translation that consumes it knows which way that is.
     */
    private void clampScroll(SizeTokens t) {
        scrollX = Math.max(0, Math.min(scrollX, maxScrollX(t)));
        scrollY = Math.max(0, Math.min(scrollY, maxScrollY(t)));
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        return constraints.constrain(
                preferredWidth >= 0 ? preferredWidth : t.areaWidth(),
                preferredHeight >= 0 ? preferredHeight : t.areaHeight());
    }

    /**
     * The first line's baseline, the very expression {@link #onPaint} draws line 0 with, taken
     * at {@code scrollY == 0}. Deliberately <em>not</em> scroll-dependent: the scroll offset is
     * view state, and a BASELINE row that re-aligned itself as the user scrolled would jitter.
     *
     * <p>No empty-text guard here, unlike the single-line controls. An editor always paints a
     * line box (the caret sits in it) whether or not the model holds text, so falling back to
     * {@code super.baselineOffset()} (the bottom edge) would make typing the first character
     * jump a whole ~140 pt row.
     */
    @Override
    protected float baselineOffset() {
        SizeTokens t = Theme.current().tokensFor(this);
        // areaPad, not fieldPadH: this is the VERTICAL inset, and only the horizontal one is
        // shared with TextField.
        return t.areaPad() + textRuler().measure(PROBE, t.body()).ascent();
    }

    @Override
    protected void onLayout() {
        // ScrollBar does NOT participate in the size axis: thickness() is
        // static and stays 15 pt at every step, so this reserves 15 at every step too.
        float t = ScrollBar.thickness();
        // Settle the strips first. Unwrapped the content does not move with the viewport (this
        // area scrolls long lines), so the sizes handed over are the same on either pass.
        // Wrapped, the height follows the width: contentWidth/contentHeight re-derive the row
        // map under each candidate viewport, and the second pass ScrollGutters may take is
        // exactly what settles a vertical bar whose strip re-wrapped the text a hair taller.
        SizeTokens tokens = tokens();
        gutters.resolve(width(), height(), vBar, hBar,
                (viewW, viewH) -> new Size(contentWidth(tokens), contentHeight(tokens)));
        boolean reserved = gutters.layout() == ScrollGutters.Layout.RESERVED;
        // Leave the corner square clear so the two bars never overlap and each thumb
        // reaches its own end (shortened by the other bar's thickness). Reserved, the
        // strips already are that square, and only on an axis that overflows.
        float vLen = reserved ? height() - gutters.horizontalStrip() : height() - t;
        float hLen = reserved ? width() - gutters.verticalStrip() : width() - t;
        // The vertical bar sits on the side reading ends on, and the horizontal one starts
        // after whatever strip that leaves, so the clear corner square is on the bar's own side.
        boolean rtl = isRtl();
        vBar.measure(Constraints.tight(t, vLen));
        vBar.layoutBox(rtl ? 0 : width() - t, 0, t, vLen);
        hBar.measure(Constraints.tight(hLen, t));
        hBar.layoutBox(rtl ? width() - hLen : 0, height() - t, hLen, t);
    }

    // ---------------------------------------------------- cursor geometry

    /**
     * Top of line {@code line}'s box in content space: the one vertical anchor the baseline,
     * the selection band, the caret and the IME underline all derive from. Before this existed
     * the underline was placed from the <em>baseline</em> by a literal {@code +2} while
     * everything else was placed from the line top, two expressions that only look alike at
     * 14&nbsp;pt: at 19&nbsp;pt the underline landed 2.6&nbsp;pt above the descender's ink edge
     * and cut it.
     *
     * <p>The ink box is <em>top-aligned</em> in the line box rather than centred in it, which is
     * what keeps the painted baseline byte-identical: the two differ only by the face's line
     * gap, and every face the toolkit ships has {@code lineGap == 0}
     * ({@code lineHeight == ascent + descent}).
     */
    private static float lineTop(int line, float lineHeight) {
        return line * lineHeight;
    }

    /**
     * The caret in {@code line}'s own index space: {@link TextEditModel#caret()} rebased onto the
     * line and clamped into it. The side travels unchanged, because it is a property of the caret
     * and not of which line the caret is on.
     *
     * <p>Clamping rather than rejecting is the same rule {@link ShapedText} states for every index
     * it takes: a caret restored from a stale view, or read while the model has moved on, has to
     * produce a position rather than an exception.
     */
    private static ShapedText.Position lineLocal(ShapedText.Position caret, int lineStart,
                                                 ShapedText line) {
        int local = Math.max(0, Math.min(caret.charIndex() - lineStart, line.text().length()));
        return new ShapedText.Position(local, caret.affinity());
    }

    /**
     * X of the caret within its row, including any in-progress composition up to the preedit
     * caret. The one expression the scroll clamp, the candidate window and the painted caret all
     * read, so those three cannot disagree about where the caret is.
     */
    private float caretContentX(SizeTokens t) {
        int line = model.lineOf(model.cursor());
        if (!preedit.isEmpty()) {
            // The composed line, not the committed one plus a measured preedit: Arabic and Indic
            // join across the seam the caret sits on, so three measurements are three wrong
            // numbers.
            if (softWrap) {
                syncRowMap(t);
                int[] starts = rowStartsByLine[line];
                ShapedText.Position caret = composedCaret(cursorInLine(line));
                int r = rowInLine(starts, caret.charIndex(), caret.affinity());
                ShapedText rowShaped = composedRowAt(r, t);
                return rowOriginX(rowShaped, t)
                        + rowShaped.caretX(lineLocal(caret, starts[r], rowShaped));
            }
            ShapedText composedForLine = composedLine(t);
            return rowOriginX(composedForLine, t)
                    + composedForLine.caretX(composedCaret(cursorInLine(line)));
        }
        int row = caretRow(t);
        ShapedText shaped = shapedRow(row, t);
        return rowOriginX(shaped, t) + shaped.caretX(lineLocal(model.caret(),
                model.lineStartOfLine(line) + rowStartInLine(row, line), shaped));
    }

    /**
     * Where the caret sits on the composed line: inside the preedit, at the preedit's own caret.
     *
     * <p>One expression, because the scroll clamp reads it through {@link #caretContentX} and
     * {@link #paintComposingRow} draws it, and a second copy is a caret painted somewhere the
     * scroll does not know it is.
     *
     * @param cursorAt the cursor's char offset within the committed line, which is where the
     *                 preedit begins inside the composed one
     */
    private ShapedText.Position composedCaret(int cursorAt) {
        // UPSTREAM is not arbitrary: the preedit caret TRAILS the text just typed, so the next
        // character of the same script appears where the caret is drawn.
        return new ShapedText.Position(cursorAt + Math.min(preeditCaret, preedit.length()),
                ShapedText.Affinity.UPSTREAM);
    }

    /**
     * The cursor's char offset within line {@code line}, clamped into that line's own text. From
     * the line-start index rather than from {@code lineText(line).length()}: this runs once per
     * paint of a composing line, and the substring would be built only to be measured.
     */
    private int cursorInLine(int line) {
        int start = model.lineStartOfLine(line);
        return Math.max(0, Math.min(model.cursor() - start, model.lineEnd(start) - start));
    }

    /**
     * The caret a click at a content-space point (already scroll- and inset-adjusted) asks for:
     * buffer index <em>and side</em>, because on a direction boundary the index alone names two
     * points on the line and the next arrow press has to leave from the one that was clicked.
     */
    private ShapedText.Position caretAtContent(float px, float py, SizeTokens t) {
        int row = Math.max(0, Math.min((int) (py / lineHeight(t)), totalRows(t) - 1));
        int line = lineOfRow(row);
        ShapedText shaped = shapedRow(row, t);
        // Out of content space and into this row's own: the two differ by where the row was
        // placed, which reading right to left is its flush-right offset and not zero.
        px -= rowOriginX(shaped, t);
        // Empty space to the right of the row means the LOGICAL end of the row: the hard end of
        // the line on its last row, the drawn end — before the whitespace hanging at the soft
        // break — on any other. On a row that ends in the direction opposite the paragraph's,
        // the nearest cluster to the right edge is not the last character, so hitTest's
        // clamp-to-nearest is wrong exactly here.
        ShapedText.Position hit = px > shaped.metrics().width()
                ? new ShapedText.Position(shaped.text().length(), ShapedText.Affinity.UPSTREAM)
                : shaped.hitTest(px);
        // No alignToGrapheme: hitTest already lands on a caret stop, and where the shaper's
        // clusters disagree with the grapheme rule — a Devanagari conjunct is the case that bites
        // — the shaper's win, because a caret cannot be placed inside a glyph.
        int local = rowStartInLine(row, line) + hit.charIndex();
        if (softWrap && !preedit.isEmpty() && line == rowMapOverlayLine) {
            // The map holds the composed rows for this line; the model's cursor lives in the
            // committed text, so a click inside the preedit lands at the composition point.
            local = composedToCommitted(local, line);
        }
        return new ShapedText.Position(model.lineStartOfLine(line) + local, hit.affinity());
    }

    /** A char offset in the composed line mapped back to the committed buffer's line. */
    private int composedToCommitted(int composedLocal, int line) {
        int at = cursorInLine(line);
        if (composedLocal <= at) {
            return composedLocal;
        }
        return composedLocal >= at + preedit.length() ? composedLocal - preedit.length() : at;
    }

    // ------------------------------------------------------- shaped row cache

    /**
     * The shaped form of one row &mdash; the whole line unwrapped, its slice of the line under
     * soft wrap &mdash; held rather than recomputed: shaping is the expensive half of drawing
     * text, and the answer is needed twice per frame &mdash; once to place the caret and the
     * selection, once to paint &mdash; and those two have to agree.
     *
     * <p><b>Bounded by the viewport, never by the document.</b> The window array covers the rows
     * {@link #onPaint} is about to draw; anything outside it (the caret's row while it is still
     * scrolled away, the row under a drag that has left the viewport) goes through the one-slot
     * spill, so a query off screen costs one shaping and never grows the cache to the size of the
     * buffer. Holding every row a long document has would be a second copy of the text, and the
     * viewport is the only part any repaint touches.
     *
     * <p>Slots fill lazily, which is what makes a keystroke cost one shaping rather than two: the
     * edit invalidates the window, {@link #ensureCursorVisible} shapes the caret's row into its
     * slot, and the paint that follows finds it there.
     *
     * <p>While a composition is up, the composing line's rows come from {@link #composedRowAt}
     * rather than from a slot: they are shapings of the <em>composed</em> text, keyed on a
     * preedit the window cannot see.
     */
    private ShapedText shapedRow(int row, SizeTokens t) {
        Font f = t.body();
        TextRuler ruler = textRuler();
        syncRowMap(t);
        syncRowWindow(f, ruler);
        int line = lineOfRow(row);
        if (softWrap && !preedit.isEmpty() && line == rowMapOverlayLine) {
            return noteShaped(composedRowAt(row - rowOffsets[line], t));
        }
        int slot = row - cachedFirstRow;
        if (cachedRows != null && slot >= 0 && slot < cachedRows.length) {
            ShapedText held = cachedRows[slot];
            if (held == null) {
                held = shapeRow(ruler, line, row, f);
                cachedRows[slot] = held;
            }
            return noteShaped(held);
        }
        if (spilledRow == null || spilledRowIndex != row) {
            spilledRow = shapeRow(ruler, line, row, f);
            spilledRowIndex = row;
        }
        return noteShaped(spilledRow);
    }

    /** One row's shaped form, built fresh: the whole line unwrapped, a trimmed slice wrapped. */
    private ShapedText shapeRow(TextRuler ruler, int line, int row, Font f) {
        String lineText = model.lineText(line);
        if (!softWrap) {
            return shapeOneLine(ruler, lineText, f);
        }
        // Every row is shaped at the LINE's base direction, never its own first-strong
        // resolution: the line decided where it broke, and a row of digits and punctuation
        // inside a right-to-left line would come out left to right — disagreeing with the line
        // that decided — if it re-derived. The same rule, for the same reason, as Label.wrapText.
        ShapedText.Direction base = ShapedText.Direction.of(lineText, neutralBase());
        return ruler.shape(rowText(lineText, rowStartsByLine[line], row - rowOffsets[line]),
                f, base);
    }

    /**
     * The text one row draws: its slice of the line, with the whitespace hanging at a soft break
     * dropped. Only a soft cut trims &mdash; the last row runs to the hard end of its line, where
     * a trailing space is content the caret sits after, exactly as it is unwrapped. The hung
     * whitespace is not deleted, merely undrawn: its indices stay on this row, and a caret in
     * them clamps to the drawn end.
     */
    private static String rowText(String lineText, int[] starts, int r) {
        int start = starts[r];
        if (r + 1 == starts.length) {
            return start == 0 ? lineText : lineText.substring(start);
        }
        return lineText.substring(start, LineBreaks.trimEnd(lineText, start, starts[r + 1]));
    }

    /**
     * Drops every held shaping whose inputs have moved. One guard for the window and the spill
     * alike: a stale value surviving in one because the other happened to be consulted is the
     * shape of bug that puts the caret a run away from the click, one keystroke later.
     *
     * <p>Keyed on {@link TextEditModel#textVersion()} rather than through
     * {@link ShapedText#matches}: {@code model.lineText} builds a fresh {@code String} every call,
     * so {@code matches} would miss its identity fast path and pay a character scan per line per
     * paint. The font and the ruler's {@linkplain TextRuler#epoch() epoch} are the other two thirds
     * of that same test, and the epoch is the part that catches what the widget cannot see &mdash;
     * a family re-bound underneath {@code Font.DEFAULT_FAMILY}, a face evicted and closed. The
     * row-map generation is the wrap-mode third: a slot names a global <em>row</em>, and a map
     * that re-wrapped has renamed every row after the edit.
     */
    private void syncRowWindow(Font f, TextRuler ruler) {
        long version = model.textVersion();
        long epoch = ruler.epoch();
        ShapedText.Direction base = neutralBase();
        if (version == cachedTextVersion && f.equals(cachedRowFont) && epoch == cachedRowEpoch
                && base == cachedRowBase && rowMapGeneration == cachedRowGeneration) {
            return;
        }
        if (cachedRows != null) {
            Arrays.fill(cachedRows, null);
        }
        spilledRow = null;
        spilledRowIndex = -1;
        // Every width in the floor came from a value being dropped on the two lines above, so it
        // goes with them. It re-fills from the next shaping, which is the same pass that needed it.
        shapedWidthFloor = 0;
        cachedTextVersion = version;
        cachedRowFont = f;
        cachedRowEpoch = epoch;
        cachedRowBase = base;
        cachedRowGeneration = rowMapGeneration;
    }

    /**
     * One line, shaped for the direction the surrounding interface reads. The first-strong rule
     * still decides everything a strong character can decide; the fallback is what this widget
     * knows and the string does not.
     */
    private ShapedText shapeOneLine(TextRuler ruler, String text, Font f) {
        return ruler.shape(text, f, ShapedText.Direction.of(text, neutralBase()));
    }

    /** Points the window at the rows about to be painted, dropping slots that now name others. */
    private void setRowWindow(int firstRow, int count) {
        if (cachedRows == null || cachedRows.length != count) {
            cachedRows = new ShapedText[count];
        } else if (cachedFirstRow != firstRow) {
            Arrays.fill(cachedRows, null);
        }
        cachedFirstRow = firstRow;
    }

    /**
     * {@link ShapedText#selection(int, int, float[])} into {@link #spans}, grown to the exact
     * bound the value states. The buffer form and not the list: a selection drag repaints at frame
     * rate, and the list form puts a list and a record per box on the floor each time.
     *
     * @return how many boxes were written; box {@code i} is {@code spans[2i] .. spans[2i + 1]}
     */
    private int fillSpans(ShapedText line, int from, int to) {
        int need = 2 * line.runs().size();
        if (spans.length < need) {
            spans = new float[need];
        }
        return line.selection(from, to, spans);
    }

    /** The shaped viewport window; slot {@code i} is row {@code cachedFirstRow + i}. */
    private ShapedText[] cachedRows;
    private int cachedFirstRow = -1;
    private long cachedTextVersion = -1;
    /** The font the window and the spill were shaped under, part of their validity key. */
    private Font cachedRowFont;
    /** The ruler epoch they were shaped under, another part. See {@link #syncRowWindow}. */
    private long cachedRowEpoch = -1;
    /**
     * The neutral fallback they were shaped under. Part of the key because this cache is
     * hand-written and never asks {@link ShapedText#matches}: a direction change is invisible to
     * the version, the font and the epoch alike, and the lines it produced are wrong by a
     * fraction of a point in every geometry query asked of them.
     */
    private ShapedText.Direction cachedRowBase;
    /** The row-map generation the slots were filled under; see {@link #syncRowWindow}. */
    private int cachedRowGeneration = -1;
    /** One row outside the window, shaped on demand; see {@link #shapedRow}. */
    private ShapedText spilledRow;
    private int spilledRowIndex = -1;
    /** Selection boxes, reused across paints; see {@link #fillSpans}. */
    private float[] spans = new float[8];

    // ------------------------------------------------------------- soft-wrap row map

    /** One shared answer for the overwhelmingly common line that fits: a single row at 0. */
    private static final int[] SINGLE_ROW = {0};
    /** Per line, the char offsets its rows start at; {@code null} until soft wrap builds it. */
    private int[][] rowStartsByLine;
    /** Prefix sums of rows per line: {@code rowOffsets[i]} is the global row line {@code i} starts at. */
    private int[] rowOffsets;
    private long rowMapVersion = -1;
    private Font rowMapFont;
    private long rowMapEpoch = -1;
    private ShapedText.Direction rowMapBase;
    private float rowMapBudget = -1;
    private Locale rowMapLocale;
    /** The preedit (by identity, like {@link #composedPreedit}) the map was built with. */
    private String rowMapPreedit = "";
    /** The line whose entry holds COMPOSED row starts while a composition is up; -1 = none. */
    private int rowMapOverlayLine = -1;
    /** Bumped whenever the map is rebuilt or spliced; the row window and the composed rows key on it. */
    private int rowMapGeneration;
    /** Shaped composed rows while composing under wrap, lazily filled; see {@link #composedRowAt}. */
    private ShapedText[] composedRowShapes;
    private ShapedText composedRowShapesFor;
    private int composedRowShapesGeneration = -1;

    /**
     * Brings the row map up to date with everything it is derived from: the text, the column
     * width, the font, the ruler epoch, the neutral base, the break locale, and the composition.
     *
     * <p><b>A full pass shapes every line once</b>, and is paid exactly when one of the
     * whole-document inputs moves: the text replaced, the column resized, the font or locale
     * switched, the epoch bumped. That is the honest price of wrapping &mdash; a break position is
     * a fact about shaped advances, and a map built from anything cheaper puts rows a few points
     * past the column and the caret outside the clip. What must <em>not</em> pay it is the
     * keystroke: an edit re-wraps only the lines {@link TextEditModel#lineDamage()} names, spliced
     * into the map it already has, and the prefix sums are rebuilt from ints alone. A composition
     * is the same splice in miniature: the composing line's entry holds the <em>composed</em>
     * text's rows while the preedit is up, so the rows below it move honestly when the
     * composition grows a row, and the entry is re-derived from the committed text the moment it
     * ends.
     */
    private void syncRowMap(SizeTokens t) {
        if (!softWrap) {
            return;
        }
        Font f = t.body();
        TextRuler ruler = textRuler();
        float budget = viewWidth(t);
        long epoch = ruler.epoch();
        ShapedText.Direction base = neutralBase();
        Locale locale = I18n.locale();
        long version = model.textVersion();
        int overlayLine = preedit.isEmpty() ? -1 : model.lineOf(model.cursor());
        boolean frameMatches = rowStartsByLine != null && f.equals(rowMapFont)
                && epoch == rowMapEpoch && base == rowMapBase && budget == rowMapBudget
                && locale.equals(rowMapLocale);
        boolean textMatches = frameMatches && version == rowMapVersion;
        if (textMatches && preedit == rowMapPreedit && overlayLine == rowMapOverlayLine) {
            return;
        }
        BreakIterator breaks = BreakIterator.getLineInstance(locale);
        TextEditModel.LineDamage damage = model.lineDamage();
        int lineCount = model.lineCount();
        if (textMatches) {
            // Only the composition moved: re-derive the line it left and the line it covers.
            // The committed text under both is unchanged, so nothing else can have.
            if (rowMapOverlayLine >= 0 && rowMapOverlayLine != overlayLine
                    && rowMapOverlayLine < lineCount) {
                rowStartsByLine[rowMapOverlayLine] =
                        lineRowStarts(rowMapOverlayLine, -1, t, ruler, f, breaks, budget);
            }
            if (overlayLine >= 0) {
                rowStartsByLine[overlayLine] =
                        lineRowStarts(overlayLine, overlayLine, t, ruler, f, breaks, budget);
            }
        } else if (frameMatches && damage != null
                && rowStartsByLine.length - 1 - damage.oldLastLine()
                        == lineCount - 1 - damage.newLastLine()
                // An entry holding composed rows may only survive a splice re-derived, so the
                // damage has to cover it; a composition running through an edit it did not cause
                // is the rare case, and a full pass there is correct rather than clever.
                && (rowMapOverlayLine < 0 || (rowMapOverlayLine >= damage.firstLine()
                        && rowMapOverlayLine <= damage.oldLastLine()))) {
            spliceRowMap(damage, overlayLine, t, ruler, f, breaks, budget, lineCount);
        } else {
            rowStartsByLine = new int[lineCount][];
            for (int line = 0; line < lineCount; line++) {
                rowStartsByLine[line] =
                        lineRowStarts(line, overlayLine, t, ruler, f, breaks, budget);
            }
        }
        rebuildRowOffsets();
        rowMapGeneration++;
        rowMapVersion = version;
        rowMapFont = f;
        rowMapEpoch = epoch;
        rowMapBase = base;
        rowMapBudget = budget;
        rowMapLocale = locale;
        rowMapPreedit = preedit;
        rowMapOverlayLine = overlayLine;
        model.clearLineDamage();
    }

    /**
     * Re-derives the damaged lines and keeps everything the edit did not touch: the prefix by
     * position, the suffix shifted to follow the new last damaged line. The counts agree by
     * {@link TextEditModel.LineDamage}'s contract, which {@link #syncRowMap} has already checked.
     */
    private void spliceRowMap(TextEditModel.LineDamage damage, int overlayLine, SizeTokens t,
                              TextRuler ruler, Font f, BreakIterator breaks, float budget,
                              int lineCount) {
        if (lineCount == rowStartsByLine.length) {
            // The common keystroke: no line added or removed, so the damaged entries are
            // replaced in place and nothing per-line is allocated.
            for (int line = damage.firstLine(); line <= damage.newLastLine(); line++) {
                rowStartsByLine[line] = lineRowStarts(line, overlayLine, t, ruler, f, breaks,
                        budget);
            }
            return;
        }
        int[][] old = rowStartsByLine;
        int[][] next = new int[lineCount][];
        System.arraycopy(old, 0, next, 0, damage.firstLine());
        for (int line = damage.firstLine(); line <= damage.newLastLine(); line++) {
            next[line] = lineRowStarts(line, overlayLine, t, ruler, f, breaks, budget);
        }
        int suffix = lineCount - 1 - damage.newLastLine();
        System.arraycopy(old, damage.oldLastLine() + 1, next, damage.newLastLine() + 1, suffix);
        rowStartsByLine = next;
    }

    /** Row starts for one line as it will be painted: the composed text on the composing line. */
    private int[] lineRowStarts(int line, int overlayLine, SizeTokens t, TextRuler ruler, Font f,
                                BreakIterator breaks, float budget) {
        if (line == overlayLine) {
            return computeRowStarts(composedLine(t), budget, breaks);
        }
        String text = model.lineText(line);
        if (text.isEmpty()) {
            return SINGLE_ROW;
        }
        return computeRowStarts(shapeOneLine(ruler, text, f), budget, breaks);
    }

    /**
     * Where one line's rows start, by the shared break walk ({@link LineBreaks#rowEnd}). Unlike
     * {@link Label#wrapText}, <b>every segment becomes a row</b>, a whitespace-only one included:
     * rows must tile the line, because every buffer index has to live on some row for the caret
     * to have somewhere to be. What {@code Label} handles by skipping, this handles by trimming
     * at draw time ({@link #rowText}).
     */
    private static int[] computeRowStarts(ShapedText paragraph, float budget,
                                          BreakIterator breaks) {
        String text = paragraph.text();
        if (budget <= 0 || paragraph.metrics().width() <= budget) {
            return SINGLE_ROW;
        }
        breaks.setText(text);
        int[] starts = new int[4];
        int count = 1;
        int start = 0;
        while (true) {
            int end = LineBreaks.rowEnd(paragraph, breaks, start, budget);
            if (end >= text.length()) {
                break;
            }
            if (count == starts.length) {
                starts = Arrays.copyOf(starts, count * 2);
            }
            starts[count++] = end;
            start = end;
        }
        return count == 1 ? SINGLE_ROW : Arrays.copyOf(starts, count);
    }

    private void rebuildRowOffsets() {
        int lines = rowStartsByLine.length;
        if (rowOffsets == null || rowOffsets.length != lines + 1) {
            rowOffsets = new int[lines + 1];
        }
        int rows = 0;
        for (int line = 0; line < lines; line++) {
            rowOffsets[line] = rows;
            rows += rowStartsByLine[line].length;
        }
        rowOffsets[lines] = rows;
    }

    /** Rows in the whole document; the line count while soft wrap is off. Syncs the map. */
    private int totalRows(SizeTokens t) {
        if (!softWrap) {
            return model.lineCount();
        }
        syncRowMap(t);
        return rowOffsets[rowStartsByLine.length];
    }

    /**
     * The line global row {@code row} belongs to; the row itself while soft wrap is off. Wrapped
     * callers reach this behind a sync ({@link #totalRows}, {@link #shapedRow},
     * {@link #caretRow}), so the map is current when the search runs.
     */
    private int lineOfRow(int row) {
        if (!softWrap) {
            return row;
        }
        int lo = 0;
        int hi = rowStartsByLine.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (rowOffsets[mid] <= row) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** The char offset within line {@code line} at which global row {@code row} starts; 0 unwrapped. */
    private int rowStartInLine(int row, int line) {
        return softWrap ? rowStartsByLine[line][row - rowOffsets[line]] : 0;
    }

    /**
     * Which of a line's rows a caret at {@code local} sits on. An index exactly on a soft break
     * is two places on the screen &mdash; the end of one row and the start of the next &mdash;
     * and the caret's side says which: {@code UPSTREAM} trails what precedes the break, so it
     * stays on the earlier row. The same {@link ShapedText.Affinity} that disambiguates a
     * direction boundary, doing the same job one level up, which is why no second flag exists.
     */
    private static int rowInLine(int[] starts, int local, ShapedText.Affinity affinity) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= local) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        if (lo > 0 && starts[lo] == local && affinity == ShapedText.Affinity.UPSTREAM) {
            lo--;
        }
        return lo;
    }

    /**
     * The caret's global row: its line while soft wrap is off; wrapped, the row its index and
     * side name ({@link #rowInLine}). While a composition is up the map holds the composed rows
     * for the caret's line, so the caret is located in the composed text, at the preedit's own
     * caret.
     */
    private int caretRow(SizeTokens t) {
        int line = model.lineOf(model.cursor());
        if (!softWrap) {
            return line;
        }
        syncRowMap(t);
        int[] starts = rowStartsByLine[line];
        if (starts.length == 1) {
            return rowOffsets[line];
        }
        int local;
        ShapedText.Affinity affinity;
        if (!preedit.isEmpty()) {
            ShapedText.Position caret = composedCaret(cursorInLine(line));
            local = caret.charIndex();
            affinity = caret.affinity();
        } else {
            local = Math.max(0, model.cursor() - model.lineStartOfLine(line));
            affinity = model.caret().affinity();
        }
        return rowOffsets[line] + rowInLine(starts, local, affinity);
    }

    /**
     * The composed line's rows, shaped on demand while a composition is up: what the composing
     * line paints and measures instead of window slots, because these are shapings of the
     * <em>composed</em> text, keyed on a preedit the window's key cannot see. Dropped with the
     * generation, which moves whenever the map (the row boundaries these slice by) does.
     */
    private ShapedText composedRowAt(int r, SizeTokens t) {
        ShapedText line = composedLine(t);
        int[] starts = rowStartsByLine[rowMapOverlayLine];
        if (composedRowShapes == null || composedRowShapesFor != line
                || composedRowShapesGeneration != rowMapGeneration) {
            composedRowShapes = new ShapedText[starts.length];
            composedRowShapesFor = line;
            composedRowShapesGeneration = rowMapGeneration;
        }
        ShapedText held = composedRowShapes[r];
        if (held == null) {
            held = textRuler().shape(rowText(line.text(), starts, r), t.body(),
                    line.baseDirection());
            composedRowShapes[r] = held;
        }
        return held;
    }

    /**
     * One Left or Right press: a step <b>on the screen</b>, and the row change when there is no
     * step left to take on this row. Unwrapped a row is a hard line; wrapped it is one visual
     * row, so the same {@code false} that used to change line now also crosses a soft break,
     * with no second rule.
     *
     * <p>The model answers the step over the row's own shaping and reports whether anything
     * moved; {@code false} means the caret was already at that visual edge, and the row change is
     * this widget's because the model holds no rows. The caret enters the neighbouring row at
     * <em>its</em> opposite edge, and the two edge positions are {@code hitTest(0)} and
     * {@code hitTest(width)} rather than index {@code 0} and index {@code length}: on a row whose
     * last cluster reads against the paragraph, the last character is not the one at the right
     * edge, and entering at the wrong one puts the caret a run away from where the key pointed.
     *
     * <p>A {@code false} return is the <em>only</em> thing that changes row. In particular the
     * model returns {@code true} when it merely collapses a selection, which is what keeps
     * Left-with-a-selection from collapsing and hopping a row in one keystroke.
     *
     * <p><b>Up and Down do not come through here.</b> Unwrapped they keep the model's sticky goal
     * <em>column</em> &mdash; a char offset within the line, the invariant a line-per-row editor
     * has always kept &mdash; and wrapped they go through {@link #moveCaretVertically}, which
     * keeps a goal <em>x</em> instead, because one column is several places once a line is
     * several rows. The widget can answer the x question the model cannot: it holds the shaped
     * rows.
     *
     * @param left  whether this is the Left arrow rather than the Right
     * @param shift whether the press extends the selection
     */
    private void moveCaretVisually(boolean left, boolean shift) {
        SizeTokens t = tokens();
        if (softWrap && !preedit.isEmpty()) {
            // The map holds the composed rows while a composition is up, and the model's cursor
            // lives in the committed text; step logically rather than mix the two index spaces.
            if (left) {
                model.moveLeft(shift);
            } else {
                model.moveRight(shift);
            }
            return;
        }
        int row = caretRow(t);
        int line = lineOfRow(row);
        int rowStart = model.lineStartOfLine(line) + rowStartInLine(row, line);
        ShapedText shaped = shapedRow(row, t);
        boolean moved = left
                ? model.moveVisualLeft(shaped, rowStart, shift)
                : model.moveVisualRight(shaped, rowStart, shift);
        if (moved) {
            return;
        }
        int target = left ? row - 1 : row + 1;
        if (target < 0 || target >= totalRows(t)) {
            return; // the document's own edge: the key does nothing, as it always has
        }
        int targetLine = lineOfRow(target);
        ShapedText neighbour = shapedRow(target, t);
        ShapedText.Position edge = left
                ? neighbour.hitTest(neighbour.metrics().width())
                : neighbour.hitTest(0);
        model.setCaret(new ShapedText.Position(
                model.lineStartOfLine(targetLine) + rowStartInLine(target, targetLine)
                        + edge.charIndex(),
                edge.affinity()), shift);
    }

    /**
     * Wrap-mode Up, Down and Page: the vertical unit is the <b>row</b>, and the horizontal
     * anchor is a sticky goal <em>x</em> ({@link #goalX}), taken from the caret the first press
     * of a run sees and held until something that is not a vertical step clears it &mdash; the
     * same lifetime the model gives its goal column. The target position is the row's own
     * {@code hitTest} at that x, so a run of presses through a short row comes back to the
     * column it left, and on a bidi row the caret lands side included.
     *
     * <p>At the document's edges this mirrors the model's logical moves: Up with no row above
     * lands at index {@code 0} on the paragraph's own start side, Down with no row below at the
     * end. Both keep {@code shift} extending the selection.
     *
     * @param rowDelta rows to move by: -1/+1 for the arrows, a viewport's worth for Page
     * @param shift    whether the press extends the selection
     */
    private void moveCaretVertically(int rowDelta, boolean shift) {
        SizeTokens t = tokens();
        int row = caretRow(t);
        if (Float.isNaN(goalX)) {
            goalX = caretContentX(t);
        }
        int target = row + rowDelta;
        if (target < 0) {
            model.setCaret(new ShapedText.Position(0, ShapedText.Affinity.UPSTREAM), shift);
            return;
        }
        if (target >= totalRows(t)) {
            model.setCaret(new ShapedText.Position(model.length(),
                    ShapedText.Affinity.DOWNSTREAM), shift);
            return;
        }
        int line = lineOfRow(target);
        ShapedText shaped = shapedRow(target, t);
        float px = goalX - rowOriginX(shaped, t);
        // Same empty-space rule as a click past the row: the row's logical end, not the cluster
        // nearest the edge; see caretAtContent.
        ShapedText.Position hit = px > shaped.metrics().width()
                ? new ShapedText.Position(shaped.text().length(), ShapedText.Affinity.UPSTREAM)
                : shaped.hitTest(px);
        model.setCaret(new ShapedText.Position(
                model.lineStartOfLine(line) + rowStartInLine(target, line) + hit.charIndex(),
                hit.affinity()), shift);
    }

    /**
     * Moves the caret one viewport of lines, the way every multi-line editor on every desktop
     * does. {@code shift} extends the selection, because Shift+Page is how a keyboard user takes a
     * screenful.
     *
     * <p>At least one line, whatever the viewport measures: a control laid out shorter than a line
     * (or not laid out at all, which is where a key can arrive from a test) would otherwise make
     * Page a key that does nothing, and a dead key reads as a broken widget rather than a small
     * one. The caret's own clamping at the document edges is the model's.
     *
     * @param direction -1 for Page Up, 1 for Page Down
     */
    private void movePage(int direction, boolean shift) {
        SizeTokens t = tokens();
        float lineHeight = lineHeight(t);
        int lines = lineHeight <= 0 ? 1 : Math.max(1, (int) (viewHeight(t) / lineHeight));
        if (softWrap && preedit.isEmpty()) {
            // Wrapped, a screenful is a screenful of ROWS, through the same goal-x step the
            // arrows take — one hit test, not a loop of them.
            moveCaretVertically(direction * lines, shift);
            return;
        }
        for (int step = 0; step < lines; step++) {
            if (direction < 0) {
                model.moveUp(shift);
            } else {
                model.moveDown(shift);
            }
        }
    }

    private void ensureCursorVisible() {
        ensureCursorVisible(tokens());
    }

    private void ensureCursorVisible(SizeTokens t) {
        float lineHeight = lineHeight(t);
        float cx = caretContentX(t); // includes any in-progress composition
        // The caret's ROW, which is its line unwrapped: wrapped, the reveal has to reach the
        // visual row the caret is on, not the top of the hard line that may be a screen tall.
        float cy = caretRow(t) * lineHeight;
        // Where the window onto content space starts, and where it would have to start for the
        // caret to be inside it. Both are content coordinates, so the arithmetic is the one this
        // always had; only turning the answer back into a scroll offset knows a direction.
        float view = viewStartX(t);
        float wanted = view;
        // The two CLIP_CLEARANCE terms must stay identical or the caret oscillates per keystroke.
        if (cx - wanted > viewWidth(t) - Strokes.CLIP_CLEARANCE) {
            wanted = cx - viewWidth(t) + Strokes.CLIP_CLEARANCE;
        }
        if (cx < wanted) {
            wanted = cx;
        }
        if (wanted != view) {
            scrollX = isRtl() ? contentWidth(t) - viewWidth(t) - wanted : wanted;
        }
        if (cy + lineHeight - scrollY > viewHeight(t)) {
            scrollY = cy + lineHeight - viewHeight(t);
        }
        if (cy < scrollY) {
            scrollY = cy;
        }
        clampScroll(t);
    }

    // ---------------------------------------------------------------- paint

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        Font f = t.body();
        TextRuler ruler = textRuler();
        TextMetrics metrics = ruler.measure(PROBE, f);
        float lineHeight = metrics.lineHeight();
        // The content inset is anisotropic: fieldPadH across (shared with TextField so a
        // stacked field and area agree on the text's x column), areaPad down.
        float padX = t.fieldPadH();
        float padY = t.areaPad();

        canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(),
                isEnabled() ? theme.surface : theme.disabledFill);
        float focus = focusFade.value();
        Color border = switch (validation) {
            case NONE -> theme.outline.lerp(theme.focusRing, focus);
            case ERROR -> theme.danger;
            case WARNING -> theme.warning;
            case SUCCESS -> theme.success;
            case INFO -> theme.info;
        };
        // ONE stroke that thickens continuously 1 -> 2 as the fade runs. A ternary here
        // (focus > 0 ? FOCUS_RING : BORDER) deletes the animation outright.
        canvas.drawRoundRect(Strokes.HALF_PIXEL_INSET, Strokes.HALF_PIXEL_INSET,
                width() - 2 * Strokes.HALF_PIXEL_INSET, height() - 2 * Strokes.HALF_PIXEL_INSET,
                t.radiusMedium(),
                Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus, border);

        canvas.save();
        // The horizontal clip carries TextField's AA allowance, the same -AA_BLEED /
        // +2*AA_BLEED expression. Clipping tight to the pad cut the antialiasing fringe off the
        // first and last glyph on every line, a hard vertical edge through the ink that reads
        // as a lighter stem, and the one place the area visibly differed from a field showing
        // the same string. The bleed is an absolute device effect, so it does not scale.
        //
        // The VERTICAL clip stays tight: it is a scroll boundary against the border, not an
        // inset text run. Bleeding it by 2 pt would let a half-scrolled line's ink cross the
        // pad and sit on the rounded border.
        canvas.clipRect(columnLeft(t) - Strokes.AA_BLEED, padY,
                viewWidth(t) + 2 * Strokes.AA_BLEED, viewHeight(t));
        canvas.translate(contentOriginX(t), padY - scrollY);

        int firstRow = Math.max(0, (int) (scrollY / lineHeight));
        int lastRow = Math.min(totalRows(t) - 1,
                (int) ((scrollY + viewHeight(t)) / lineHeight) + 1);
        Color ink = isEnabled() ? theme.text : theme.disabledText;
        int selStart = model.selectionStart();
        int selEnd = model.selectionEnd();
        boolean selection = model.hasSelection() && isFocused();
        boolean composing = !preedit.isEmpty();
        int composingLine = composing ? model.lineOf(model.cursor()) : -1;

        Color selectionFill = theme.primary.withAlpha(0.35f);
        setRowWindow(firstRow, lastRow - firstRow + 1);
        for (int row = firstRow; row <= lastRow; row++) {
            int line = lineOfRow(row);
            float top = lineTop(row, lineHeight);
            if (composing && line == composingLine) {
                // The composed line's own rows, underline and highlight rebased into each; the
                // caret is drawn by the row rowInLine puts it on, so it cannot appear twice.
                if (softWrap) {
                    int r = row - rowOffsets[line];
                    int[] starts = rowStartsByLine[line];
                    ShapedText.Position caret = composedCaret(cursorInLine(line));
                    boolean caretHere = rowInLine(starts, caret.charIndex(), caret.affinity()) == r;
                    ShapedText rowShaped = composedRowAt(r, t);
                    paintComposingRow(canvas, theme, rowShaped, starts[r], cursorInLine(line),
                            top, metrics, lineHeight, ink, rowOriginX(rowShaped, t), caretHere);
                } else {
                    ShapedText composedForLine = composedLine(t);
                    paintComposingRow(canvas, theme, composedForLine, 0, cursorInLine(line), top,
                            metrics, lineHeight, ink, rowOriginX(composedForLine, t), true);
                }
                continue; // composition suppresses selection painting on this line
            }
            ShapedText shaped = shapedRow(row, t);
            // Where this row sits inside content space: zero reading left to right, and flush
            // against the content's right edge reading right to left. Threaded through every x
            // below rather than applied as a transform, so one row's placement can never leak
            // into the next one's.
            float ox = rowOriginX(shaped, t);
            if (selection) {
                int lineStart = model.lineStartOfLine(line);
                int rowStart = lineStart + rowStartInLine(row, line);
                // The row's UNTRIMMED range: the whitespace hanging at a soft break belongs to
                // this row's indices even though it draws no ink, and the shaped row clamps
                // whatever falls past its drawn end.
                boolean lastRowOfLine = !softWrap
                        || row - rowOffsets[line] == rowStartsByLine[line].length - 1;
                int rowEnd = lastRowOfLine
                        ? model.lineEnd(lineStart)
                        : lineStart + rowStartsByLine[line][row - rowOffsets[line] + 1];
                int from = Math.max(selStart, rowStart);
                int to = Math.min(selEnd, rowEnd);
                boolean breakSelected = lastRowOfLine && selStart <= rowEnd && selEnd > rowEnd;
                if (from < to || breakSelected) {
                    // N boxes, never one: a range that is contiguous in the string stops being
                    // contiguous on the line the moment it crosses a direction boundary, and the
                    // smallest rectangle covering both halves would highlight the untouched text
                    // drawn between them.
                    int boxes = fillSpans(shaped, from - rowStart, to - rowStart);
                    for (int i = 0; i < boxes; i++) {
                        float x0 = spans[i * 2];
                        canvas.fillRect(ox + x0, top, spans[i * 2 + 1] - x0, lineHeight,
                                selectionFill);
                    }
                    if (breakSelected) {
                        // An optical gap next to the type it trails, so it rides the em-tuned gap
                        // ramp: hints that the newline is part of the selection. Its own rect at
                        // the line's LOGICAL end edge, extending in the reading direction — for a
                        // right-to-left line that is to the LEFT of x=0, which is where the next
                        // line continues from and is what "the break is selected" means there.
                        // Only a HARD break earns it: a soft break is this widget's artifact, not
                        // a character the selection can contain.
                        float endEdge = shaped.caretX(new ShapedText.Position(
                                shaped.text().length(), ShapedText.Affinity.DOWNSTREAM));
                        float hintX = shaped.baseDirection() == ShapedText.Direction.LTR
                                ? endEdge
                                : endEdge - t.newlineHint();
                        canvas.fillRect(ox + hintX, top, t.newlineHint(), lineHeight,
                                selectionFill);
                    } else if (boxes == 0) {
                        // selection(i, i) is an empty list — a caret is not a zero-width selection
                        // — and a range of zero-advance clusters yields none either. The band still
                        // has to show, so the widget draws it at the row's START edge: 2 pt is the
                        // minimum that survives AA at ANY size, so the floor is locked.
                        float startEdge = shaped.caretX(
                                new ShapedText.Position(0, ShapedText.Affinity.UPSTREAM));
                        float sliverX = shaped.baseDirection() == ShapedText.Direction.LTR
                                ? startEdge
                                : startEdge - Strokes.MIN_SELECTION_SLIVER;
                        canvas.fillRect(ox + sliverX, top, Strokes.MIN_SELECTION_SLIVER,
                                lineHeight, selectionFill);
                    }
                }
            }
            canvas.drawText(shaped, ox, top + metrics.ascent(), ink);
        }

        // Normal caret (the composing line draws its own caret inside the preedit). Inset by
        // the locked ink bleed at both ends so a 1 pt pen brackets the glyphs without touching
        // the neighbouring line boxes.
        //
        // ONE mark, not two: the model stores the caret's side, so there is no ambiguity left to
        // show, and caretRect() describes one column — a second mark elsewhere on the line would
        // not be repainted by a blink and would leave an artifact behind. The side also names the
        // row when the index sits exactly on a soft break, which is the same rule caretRow reads.
        if (isFocused() && cursorVisible && !model.hasSelection() && !composing) {
            int caretRowIdx = caretRow(t);
            int caretLine = lineOfRow(caretRowIdx);
            ShapedText shaped = shapedRow(caretRowIdx, t);
            float cx = rowOriginX(shaped, t) + shaped.caretX(lineLocal(model.caret(),
                    model.lineStartOfLine(caretLine) + rowStartInLine(caretRowIdx, caretLine),
                    shaped));
            float cy = lineTop(caretRowIdx, lineHeight);
            canvas.drawLine(cx, cy + Strokes.INK_BLEED, cx, cy + lineHeight - Strokes.INK_BLEED,
                    Strokes.CARET, theme.text);
        }
        canvas.restore();
    }

    /**
     * Draws one row of the cursor's line with the IME composition injected at the caret: the
     * composed line shaped <b>once</b> with the preedit already in it, the preedit underlined,
     * the block being converted highlighted, and the caret inside the preedit. Unwrapped the one
     * row is the whole composed line; wrapped each of its rows comes through here with its own
     * start offset, and the decoration ranges rebase into it &mdash; the shaped row clamps
     * whatever falls outside, so a block split by a soft break is highlighted and underlined in
     * as many pieces as it is drawn in.
     *
     * <p>One shaping and not three is a bug fix rather than a port. Measuring the committed
     * prefix, the preedit and the committed suffix separately is three answers to a question that
     * only has one, because Arabic and Indic <em>join across the seams those three measurements
     * cut</em>: a preedit typed into the middle of a word changes the forms on both sides of
     * itself, and the underline drawn from separate measurements lands somewhere the ink is not.
     * Shaping the composed line and asking it for sub-ranges cannot drift from what it draws.
     *
     * @param row       the composed row: the whole composed line, or one wrapped slice of it
     * @param rowStart  char offset of {@code row} within the composed line; {@code 0} unwrapped
     * @param cursorAt  the cursor's char offset within the committed line, which is where the
     *                  preedit begins inside the composed one
     * @param originX   where this row sits inside content space; zero reading left to right
     * @param caretHere whether the caret's row is this one; the caller decides by the same
     *                  {@link #rowInLine} rule the scroll clamp reads, so the two cannot disagree
     */
    private void paintComposingRow(Canvas canvas, Theme theme, ShapedText row, int rowStart,
                                   int cursorAt, float top, TextMetrics metrics, float lineHeight,
                                   Color ink, float originX, boolean caretHere) {
        float baseline = top + metrics.ascent();
        // Asked once and read twice, and only when there IS a converting block: an empty range
        // still allocates a scratch box array inside selection(), on a path that runs per blink.
        boolean converting = preeditFocusEnd > preeditFocusStart;
        List<ShapedText.Span> focusBoxes = converting
                ? row.selection(cursorAt + preeditFocusStart - rowStart,
                        cursorAt + preeditFocusEnd - rowStart)
                : List.of();

        // Highlight first, so it sits behind the ink rather than over it.
        for (ShapedText.Span s : focusBoxes) {
            canvas.fillRect(originX + s.x0(), top, s.width(), lineHeight,
                    theme.primary.withAlpha(0.18f));
        }
        canvas.drawText(row, originX, baseline, ink);
        // The BOTTOM OF THE INK BOX, from the line's own anchor, not "baseline + 2". A fixed
        // 2 pt drop is inside the descender at every step and cuts it outright at 19 pt, since
        // the descender runs 3.42 pt below the baseline at MEDIUM and 4.64 at XLARGE. TextField
        // has always anchored here; this is the same expression.
        float underlineY = top + metrics.height();
        // The 1-vs-2 contrast is what says "this block is converting"; scaling either erases it.
        // One stroke per box, because a preedit that spans a direction boundary is underlined in
        // as many pieces as it is drawn in.
        for (ShapedText.Span s : row.selection(cursorAt - rowStart,
                cursorAt + preedit.length() - rowStart)) {
            canvas.drawLine(originX + s.x0(), underlineY, originX + s.x1(), underlineY,
                    Strokes.IME_UNDERLINE, theme.textMuted);
        }
        for (ShapedText.Span s : focusBoxes) {
            canvas.drawLine(originX + s.x0(), underlineY, originX + s.x1(), underlineY,
                    Strokes.IME_UNDERLINE_ACTIVE, theme.primary);
        }

        if (caretHere && isFocused() && cursorVisible) {
            // The same x the scroll clamp reads, which composes it the same way.
            float cx = originX
                    + row.caretX(lineLocal(composedCaret(cursorAt), rowStart, row));
            canvas.drawLine(cx, top + Strokes.INK_BLEED, cx, top + lineHeight - Strokes.INK_BLEED,
                    Strokes.CARET, theme.text);
        }
    }

    /**
     * The cursor's line with the composition spliced in at the caret, shaped once and held while
     * the composition lasts. What {@link #paintComposingRow} draws, and what the scroll clamp and
     * {@link #caretRect()} read while composing, so all three speak of the same geometry.
     *
     * <p>Keyed on the five things it is built from. The preedit is compared by <b>identity</b>
     * rather than by {@code equals}: this widget is the only writer of that field and it writes
     * the {@code String} the event carried, so identity is exact here and a recomposition that
     * happens to produce the same characters still rebuilds &mdash; which is right, because it is
     * the cheap direction to be wrong in.
     */
    private ShapedText composedLine(SizeTokens t) {
        Font f = t.body();
        TextRuler ruler = textRuler();
        long version = model.textVersion();
        long epoch = ruler.epoch();
        int cursor = model.cursor();
        ShapedText.Direction base = neutralBase();
        if (composed == null || composedVersion != version || composedCursor != cursor
                || composedPreedit != preedit || !f.equals(composedFont) || composedEpoch != epoch
                || composedBase != base) {
            int line = model.lineOf(cursor);
            String lineText = model.lineText(line);
            int at = cursorInLine(line);
            composed = shapeOneLine(ruler,
                    lineText.substring(0, at) + preedit + lineText.substring(at), f);
            composedVersion = version;
            composedCursor = cursor;
            composedPreedit = preedit;
            composedFont = f;
            composedEpoch = epoch;
            composedBase = base;
        }
        // Into the extent like any other shaped line, and for the same reason: while a composition
        // is up this IS the caret's line, so it is what the scroll clamp has to be able to reach.
        // The committed line it stands in for is narrower, so leaving it out would clamp the caret
        // out of the clip for exactly the composition that grew past the right edge.
        return noteShaped(composed);
    }

    /** The composed line while a preedit is up; see {@link #composedLine}. */
    private ShapedText composed;
    private ShapedText.Direction composedBase;
    private long composedVersion = -1;
    private int composedCursor = -1;
    private String composedPreedit;
    private Font composedFont;
    private long composedEpoch = -1;

    // The scrollbars are child widgets (see the constructor), painted on top by
    // the default children pass, the same component every scroller uses.

    // ---------------------------------------------------------------- input

    @Override
    protected void onMouseEvent(MouseEvent event) {
        // Scrollbar drags never reach here; the ScrollBar children consume them.
        SizeTokens t = Theme.current().tokensFor(this);
        // The same two pads onPaint translates by, per axis. A press maps to the character
        // that was drawn under it only while these two expressions stay identical.
        float padX = t.fieldPadH();
        float padY = t.areaPad();
        float lx = sceneToLocalX(event.x());
        float ly = sceneToLocalY(event.y());
        switch (event.type()) {
            case PRESS -> {
                if (ContextMenus.isRequest(event)) {
                    // Focus first: the menu's Cut and Paste act on this area, and one that was
                    // not focused when they run would edit while the caret lives elsewhere.
                    requestFocus();
                    showContextMenu(event.x(), event.y());
                    event.consume();
                    return;
                }
                if (event.button() != Keys.MOUSE_LEFT) {
                    return;
                }
                model.setCaret(
                        caretAtContent(lx - contentOriginX(t), ly - padY + scrollY, t),
                        (event.modifiers() & Keys.MOD_SHIFT) != 0);
                goalX = Float.NaN; // a click ends a vertical run, exactly as it ends the model's
                resetBlink();
                event.consume();
            }
            case DRAG -> {
                model.setCaret(
                        caretAtContent(lx - contentOriginX(t), ly - padY + scrollY, t), true);
                goalX = Float.NaN;
                ensureCursorVisible(t);
                resetBlink();
                invalidate();
                event.consume();
            }
            case MOVE -> {
                vBar.onHostActivity();
                hBar.onHostActivity();
            }
            case WHEEL -> {
                // Per axis: only steal the delta this area can actually use, so
                // an orthogonal scroll still reaches an outer scroller.
                boolean useY = maxScrollY(t) > 0 && event.scrollY() != 0;
                boolean useX = maxScrollX(t) > 0 && event.scrollX() != 0;
                if (useY || useX) {
                    // A wheel detent is a DEVICE unit: the same physical flick must move the
                    // same physical distance in a dense editor and a roomy one.
                    if (useY) {
                        scrollY -= event.scrollY() * Strokes.WHEEL_STEP;
                        vBar.onScrolled();
                    }
                    if (useX) {
                        scrollX -= event.scrollX() * Strokes.WHEEL_STEP;
                        hBar.onScrolled();
                    }
                    clampScroll(t);
                    invalidate();
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (ContextMenus.isRequest(event)) {
            event.consume();
            showContextMenuForFocus();
            return;
        }
        if (!event.isPressed()) {
            return;
        }
        int mods = event.modifiers();
        boolean shift = (mods & Keys.MOD_SHIFT) != 0;
        boolean ctrl = (mods & Keys.MOD_CONTROL) != 0;
        // Word-wise with Ctrl (Windows/Linux) or Alt/Option (macOS). Cmd (macOS)
        // jumps to the line edge (←/→) or the document edge (↑/↓); Ctrl+Home/End
        // jump to the document edge on Windows/Linux.
        boolean word = ctrl || (mods & Keys.MOD_ALT) != 0;
        boolean cmd = (mods & Keys.MOD_SUPER) != 0;
        // Ctrl+Alt together is AltGr (Windows synthesizes LCtrl+RAlt for it), so a
        // printable AltGr combo must fall through to the char callback instead of
        // firing the Ctrl letter shortcuts.
        boolean altGr = ctrl && (mods & Keys.MOD_ALT) != 0;
        boolean shortcut = cmd || (ctrl && !altGr);
        boolean handled = true;
        switch (event.key()) {
            // Left/Right are VISUAL and Ctrl/Alt+Left/Right are LOGICAL, so in right-to-left text
            // the two move the caret in OPPOSITE directions. That is deliberate, it is what
            // Windows and GTK do, and it is forced: deleteWordBackward has to remove a contiguous
            // range of the string, so the boundary word movement lands on has to be the end of
            // one. Cmd+Left/Right are Home/End and logical for the same reason.
            case Keys.LEFT -> {
                if (word) {
                    model.moveWordLeft(shift);
                } else if (cmd) {
                    model.moveHome(shift);
                } else {
                    moveCaretVisually(true, shift);
                }
            }
            case Keys.RIGHT -> {
                if (word) {
                    model.moveWordRight(shift);
                } else if (cmd) {
                    model.moveEnd(shift);
                } else {
                    moveCaretVisually(false, shift);
                }
            }
            // Up/Down stay logical, on the model's sticky goal COLUMN in chars — except under
            // soft wrap, where a column is meaningless (one line is several rows) and they step
            // by visual row on a sticky goal x instead; see moveCaretVertically.
            case Keys.UP -> {
                if (cmd) {
                    model.moveDocumentStart(shift);
                } else if (softWrap && preedit.isEmpty()) {
                    moveCaretVertically(-1, shift);
                } else {
                    model.moveUp(shift);
                }
            }
            case Keys.DOWN -> {
                if (cmd) {
                    model.moveDocumentEnd(shift);
                } else if (softWrap && preedit.isEmpty()) {
                    moveCaretVertically(1, shift);
                } else {
                    model.moveDown(shift);
                }
            }
            case Keys.PAGE_UP -> movePage(-1, shift);
            case Keys.PAGE_DOWN -> movePage(1, shift);
            case Keys.HOME -> {
                if (ctrl) {
                    model.moveDocumentStart(shift);
                } else {
                    model.moveHome(shift);
                }
            }
            case Keys.END -> {
                if (ctrl) {
                    model.moveDocumentEnd(shift);
                } else {
                    model.moveEnd(shift);
                }
            }
            case Keys.ENTER -> fireIfChanged(() -> model.insert("\n"));
            case Keys.BACKSPACE -> fireIfChanged(word ? model::deleteWordBackward : model::backspace);
            case Keys.DELETE -> fireIfChanged(word ? model::deleteWordForward : model::deleteForward);
            case Keys.A -> {
                if (shortcut) {
                    model.selectAll();
                } else {
                    handled = false;
                }
            }
            case Keys.C -> handled = shortcut && copySelection(false);
            case Keys.X -> handled = shortcut && copySelection(true);
            case Keys.V -> {
                if (shortcut) {
                    String pasted = clipboard().get();
                    if (!pasted.isEmpty()) {
                        fireIfChanged(() -> model.insert(pasted));
                    }
                } else {
                    handled = false;
                }
            }
            case Keys.Z -> {
                if (shortcut) {
                    fireIfChanged(shift ? () -> model.redo() : () -> model.undo());
                } else {
                    handled = false;
                }
            }
            case Keys.Y -> {
                if (shortcut) {
                    fireIfChanged(() -> model.redo());
                } else {
                    handled = false;
                }
            }
            default -> handled = false;
        }
        if (handled) {
            // The sticky goal x survives exactly a run of vertical steps, the same lifetime the
            // model gives its goal column; Cmd+Up/Down are document jumps, not steps in a run.
            boolean vertical = (!cmd && (event.key() == Keys.UP || event.key() == Keys.DOWN))
                    || event.key() == Keys.PAGE_UP || event.key() == Keys.PAGE_DOWN;
            if (!vertical) {
                goalX = Float.NaN;
            }
            ensureCursorVisible();
            resetBlink();
            invalidate();
            event.consume();
        }
    }

    /**
     * Raises the Cut/Copy/Paste/Select All menu at a point in this widget's own coordinates: the
     * same menu, and the same enabled rules, as {@link TextField}'s.
     */
    protected void showContextMenu(float localX, float localY) {
        TextContextMenu.showAt(this, contextMenuHost(), localX, localY);
    }

    /**
     * Raises the same menu for a request that carries no point: the Menu key or Shift+F10,
     * where the caret is the only place the user can have meant. Protected for the same
     * reason as its pointer twin, and a subclass suppressing one has to suppress both.
     */
    protected void showContextMenuForFocus() {
        // At the caret, not at a corner of the box: on a wide field the two are far apart, and
        // the caret is the only place the request can have meant.
        Rect caret = caretRect();
        showContextMenu(caret.x() - localToSceneX(),
                caret.y() + caret.height() - localToSceneY());
    }

    private TextContextMenu.Host contextMenuHost() {
        return new TextContextMenu.Host() {
            @Override
            public boolean hasSelection() {
                return model.hasSelection();
            }

            @Override
            public boolean isEditable() {
                return isEnabled();
            }

            @Override
            public boolean allowsCopy() {
                return true;
            }

            @Override
            public boolean isEmpty() {
                return model.text().isEmpty();
            }

            @Override
            public String clipboardText() {
                return clipboard().get();
            }

            @Override
            public void cut() {
                copySelection(true);
                ensureCursorVisible();
                invalidate();
            }

            @Override
            public void copy() {
                copySelection(false);
            }

            @Override
            public void paste() {
                String pasted = clipboard().get();
                if (!pasted.isEmpty()) {
                    fireIfChanged(() -> model.insert(pasted));
                    ensureCursorVisible();
                    invalidate();
                }
            }

            @Override
            public void selectAll() {
                model.selectAll();
                invalidate();
            }
        };
    }

    private boolean copySelection(boolean cut) {
        if (!model.hasSelection()) {
            return false;
        }
        clipboard().set(model.selectedText());
        if (cut) {
            model.deleteSelection();
            fireChange();
        }
        return true;
    }

    @Override
    protected void onCharTyped(CharEvent event) {
        int cp = event.codepoint();
        if (cp < 0x20 || cp == 0x7F) {
            return;
        }
        model.insertCodePoint(cp);
        fireChange();
        ensureCursorVisible();
        resetBlink();
        invalidate();
        event.consume();
    }

    @Override
    protected boolean acceptsTextInput() {
        return true;
    }

    /** @return the text currently being composed by the IME (empty when not composing) */
    public String composingText() {
        return preedit;
    }

    @Override
    protected void onPreedit(PreeditEvent event) {
        // The composed line about to be replaced contributed a width to the extent; a composition
        // that shrank or was cancelled must not leave that width behind. The clamp two lines down
        // re-shapes the caret's line and puts back what is still true.
        shapedWidthFloor = 0;
        goalX = Float.NaN;
        preedit = event.text();
        preeditCaret = codePointToChar(preedit, event.caret());
        computeFocusedBlock(event);
        ensureCursorVisible();
        resetBlink();
        invalidate();
        event.consume();
    }

    /** Resolves the focused block's code-point range to a char range within the preedit. */
    private void computeFocusedBlock(PreeditEvent event) {
        preeditFocusStart = 0;
        preeditFocusEnd = 0;
        int[] blocks = event.blockSizes();
        int focused = event.focusedBlock();
        if (focused < 0 || focused >= blocks.length) {
            return;
        }
        int cpStart = 0;
        for (int i = 0; i < focused; i++) {
            cpStart += blocks[i];
        }
        preeditFocusStart = codePointToChar(preedit, cpStart);
        preeditFocusEnd = codePointToChar(preedit, cpStart + blocks[focused]);
    }

    /** @return the char offset in {@code text} of code-point index {@code cpIndex} */
    private static int codePointToChar(String text, int cpIndex) {
        if (cpIndex <= 0) {
            return 0;
        }
        int total = text.codePointCount(0, text.length());
        return cpIndex >= total ? text.length() : text.offsetByCodePoints(0, cpIndex);
    }

    @Override
    protected Rect caretRect() {
        if (width() <= 0) {
            return null; // not laid out yet
        }
        // Its own resolve: the scene also calls this from the async blink chain, where there
        // is no enclosing measure/paint pass to thread tokens down from.
        SizeTokens t = tokens();
        float padX = t.fieldPadH();
        float padY = t.areaPad();
        float lh = lineHeight(t);
        float cxContent = caretContentX(t);
        float cyContent = lineTop(caretRow(t), lh);
        // Content space → local (the paint translate is padX/padY - scroll), clamped so
        // the candidate window stays anchored inside the visible padded viewport. The clamp
        // is per axis for the same reason the translate is.
        float left = columnLeft(t);
        float localX = Math.max(left, Math.min(contentOriginX(t) + cxContent, left + viewWidth(t)));
        float localY = Math.max(padY, Math.min(padY - scrollY + cyContent, height() - padY));
        return new Rect(localToSceneX() + localX, localToSceneY() + localY, Strokes.CARET, lh);
    }

    private void fireChange() {
        invalidateContentWidth(); // text changed: widest line may differ
        goalX = Float.NaN; // an edit ends a vertical run, exactly as it resets the model's column
        onChange.accept(model.text());
    }

    /**
     * Drops both halves of the horizontal extent because the text they were taken over has moved.
     * Together, and never one without the other: a floor kept past an edit is scroll range over a
     * line that is no longer there, and a scan dropped without it would still be floored by one.
     */
    private void invalidateContentWidth() {
        cachedContentWidth = -1;
        shapedWidthFloor = 0;
    }

    /** Runs {@code edit}; fires onChange (and dirties the width cache) if the text changed. */
    private void fireIfChanged(Runnable edit) {
        String before = model.text();
        edit.run();
        if (!before.equals(model.text())) {
            fireChange();
        }
    }

    // Cursor blink via self-rescheduling Ui.postDelayed (see TextField): a
    // focused area lets the loop sleep between blinks.
    private void resetBlink() {
        cursorVisible = true;
        invalidateCaret();
        scheduleBlink(++blinkGeneration);
    }

    private void scheduleBlink(int generation) {
        Ui.postDelayed(() -> {
            if (generation != blinkGeneration || !isFocused()) {
                return;
            }
            cursorVisible = !cursorVisible;
            invalidateCaret();
            scheduleBlink(generation);
        }, Math.round(BLINK_SECONDS * 1000));
    }

    /** Damages just the caret column: a blink repaints ~1×line-height, not the whole area. */
    private void invalidateCaret() {
        Rect caret = caretRect(); // scene coordinates, clamped inside the viewport
        if (caret != null && scene() != null) {
            // Local-coords invalidate: the damage then clamps against clipping
            // ancestors: a caret scrolled out of a viewport damages nothing.
            // Margin for AA + hairline snap. Locked: growing it per step inflates per-blink
            // damage and defeats the partial rendering this is here to enable.
            invalidate(caret.x() - localToSceneX() - Strokes.DAMAGE_MARGIN,
                    caret.y() - localToSceneY() - Strokes.DAMAGE_MARGIN,
                    caret.width() + 2 * Strokes.DAMAGE_MARGIN,
                    caret.height() + 2 * Strokes.DAMAGE_MARGIN);
        } else {
            invalidate();
        }
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
        resetBlink();
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
        blinkGeneration++;
        cursorVisible = true;
        model.clearSelection();
        preedit = ""; // drop any in-progress composition
        composed = null; // and the shaping of it, which nothing can ask for again
        composedRowShapes = null; // and its wrapped rows, sliced from that shaping
        shapedWidthFloor = 0; // and the scroll range it was holding open
        preeditCaret = 0;
        preeditFocusStart = 0;
        preeditFocusEnd = 0;
        invalidate();
    }
}

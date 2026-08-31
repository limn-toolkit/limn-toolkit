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
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.event.PreeditEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Multiline text editor: hard line breaks (Enter), arrow navigation across
 * lines with a sticky column, mouse/Shift+arrow selection spanning lines,
 * clipboard shortcuts, blinking cursor, and both scrollbars: draggable
 * thumbs and wheel-responsive (long lines scroll horizontally; no soft wrap
 * in v1, matching the horizontal scrollbar requirement).
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
 * ({@link #shapedLine}) rather than from the measured width of a prefix of it, because with a
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
        invalidateContentWidth();
        invalidate();
        return this;
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
     * reset on a content change needs no branch.
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
     * What a line with no strong character of its own falls back to: this area's own resolved
     * layout direction. Never read in a constructor, and read once per pass by every caller.
     */
    private ShapedText.Direction neutralBase() {
        return isRtl() ? ShapedText.Direction.RTL : ShapedText.Direction.LTR;
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
        float padX = t.fieldPadH();
        return isRtl()
                ? padX + viewWidth(t) - contentWidth(t) + scrollX
                : padX - scrollX;
    }

    /**
     * Where the visible window starts <b>in content coordinates</b>: what {@code scrollX} means
     * once the direction has been applied. It grows with {@code scrollX} one way round and
     * shrinks with it the other, which is all {@link #ensureCursorVisible} has to know.
     */
    private float viewStartX(SizeTokens t) {
        return t.fieldPadH() - contentOriginX(t);
    }

    /**
     * Where one line puts its left edge inside content space. Reading left to right every line
     * starts at zero; reading right to left each is flush against the content's <em>right</em>
     * edge, so a short line and a long one share the edge reading starts from rather than the one
     * it ends on.
     */
    private float lineOriginX(ShapedText line, SizeTokens t) {
        return isRtl() ? contentWidth(t) - line.metrics().width() : 0;
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
     * shapes it</b>: the caret's line goes through {@link #shapedLine} before the clamp reads this,
     * and a line whose ink is on screen was shaped to paint it. A line the user has never been near
     * contributes its measured width, which can only make the extent too small for text nobody can
     * see yet &mdash; and the moment they scroll to it, it is shaped and the extent is right.
     */
    private float contentWidth(SizeTokens t) {
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
        return model.lineCount() * lineHeight(t);
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
        // Settle the strips first. The content does not move with the viewport here,
        // since this area scrolls long lines rather than wrapping them (see the class
        // comment), so the sizes handed over are the same on either pass.
        SizeTokens tokens = tokens();
        gutters.resolve(width(), height(), vBar, hBar,
                (viewW, viewH) -> new Size(contentWidth(tokens), contentHeight(tokens)));
        boolean reserved = gutters.layout() == ScrollGutters.Layout.RESERVED;
        // Leave the corner square clear so the two bars never overlap and each thumb
        // reaches its own end (shortened by the other bar's thickness). Reserved, the
        // strips already are that square, and only on an axis that overflows.
        float vLen = reserved ? height() - gutters.horizontalStrip() : height() - t;
        float hLen = reserved ? width() - gutters.verticalStrip() : width() - t;
        vBar.measure(Constraints.tight(t, vLen));
        vBar.layoutBox(width() - t, 0, t, vLen);
        hBar.measure(Constraints.tight(hLen, t));
        hBar.layoutBox(0, height() - t, hLen, t);
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
     * X of the caret within its line, including any in-progress composition up to the preedit
     * caret. The one expression the scroll clamp, the candidate window and the painted caret all
     * read, so those three cannot disagree about where the caret is.
     */
    private float caretContentX(SizeTokens t) {
        int line = model.lineOf(model.cursor());
        if (!preedit.isEmpty()) {
            // The composed line, not the committed one plus a measured preedit: Arabic and Indic
            // join across the seam the caret sits on, so three measurements are three wrong
            // numbers.
            ShapedText composedForLine = composedLine(t);
            return lineOriginX(composedForLine, t)
                    + composedForLine.caretX(composedCaret(cursorInLine(line)));
        }
        ShapedText shaped = shapedLine(line, t);
        return lineOriginX(shaped, t)
                + shaped.caretX(lineLocal(model.caret(), model.lineStartOfLine(line), shaped));
    }

    /**
     * Where the caret sits on the composed line: inside the preedit, at the preedit's own caret.
     *
     * <p>One expression, because the scroll clamp reads it through {@link #caretContentX} and
     * {@link #paintComposingLine} draws it, and a second copy is a caret painted somewhere the
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
        int line = Math.max(0, Math.min((int) (py / lineHeight(t)), model.lineCount() - 1));
        ShapedText shaped = shapedLine(line, t);
        // Out of content space and into this line's own: the two differ by where the line was
        // placed, which reading right to left is its flush-right offset and not zero.
        px -= lineOriginX(shaped, t);
        // Empty space to the right of the line means the LOGICAL end of the line. On a line that
        // ends in the direction opposite the paragraph's, the nearest cluster to the right edge is
        // not the last character, so hitTest's clamp-to-nearest is wrong exactly here.
        ShapedText.Position hit = px > shaped.metrics().width()
                ? new ShapedText.Position(shaped.text().length(), ShapedText.Affinity.UPSTREAM)
                : shaped.hitTest(px);
        // No alignToGrapheme: hitTest already lands on a caret stop, and where the shaper's
        // clusters disagree with the grapheme rule — a Devanagari conjunct is the case that bites
        // — the shaper's win, because a caret cannot be placed inside a glyph.
        return new ShapedText.Position(model.lineStartOfLine(line) + hit.charIndex(),
                hit.affinity());
    }

    // ------------------------------------------------------- shaped line cache

    /**
     * The shaped form of one line, held rather than recomputed: shaping is the expensive half of
     * drawing text, and the answer is needed twice per frame &mdash; once to place the caret and
     * the selection, once to paint &mdash; and those two have to agree.
     *
     * <p><b>Bounded by the viewport, never by the document.</b> The window array covers the lines
     * {@link #onPaint} is about to draw; anything outside it (the caret's line while it is still
     * scrolled away, the line under a drag that has left the viewport) goes through the one-slot
     * spill, so a query off screen costs one shaping and never grows the cache to the size of the
     * buffer. Holding every line a long document has would be a second copy of the text, and the
     * viewport is the only part any repaint touches.
     *
     * <p>Slots fill lazily, which is what makes a keystroke cost one shaping rather than two: the
     * edit invalidates the window, {@link #ensureCursorVisible} shapes the caret's line into its
     * slot, and the paint that follows finds it there.
     */
    private ShapedText shapedLine(int line, SizeTokens t) {
        Font f = t.body();
        TextRuler ruler = textRuler();
        syncLineCache(f, ruler);
        int slot = line - cachedFirstLine;
        if (cachedLines != null && slot >= 0 && slot < cachedLines.length) {
            ShapedText held = cachedLines[slot];
            if (held == null) {
                held = shapeOneLine(ruler, model.lineText(line), f);
                cachedLines[slot] = held;
            }
            return noteShaped(held);
        }
        if (spilledLine == null || spilledLineIndex != line) {
            spilledLine = shapeOneLine(ruler, model.lineText(line), f);
            spilledLineIndex = line;
        }
        return noteShaped(spilledLine);
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
     * a family re-bound underneath {@code Font.DEFAULT_FAMILY}, a face evicted and closed.
     */
    private void syncLineCache(Font f, TextRuler ruler) {
        long version = model.textVersion();
        long epoch = ruler.epoch();
        ShapedText.Direction base = neutralBase();
        if (version == cachedTextVersion && f.equals(cachedLineFont) && epoch == cachedLineEpoch
                && base == cachedLineBase) {
            return;
        }
        if (cachedLines != null) {
            Arrays.fill(cachedLines, null);
        }
        spilledLine = null;
        spilledLineIndex = -1;
        // Every width in the floor came from a value being dropped on the two lines above, so it
        // goes with them. It re-fills from the next shaping, which is the same pass that needed it.
        shapedWidthFloor = 0;
        cachedTextVersion = version;
        cachedLineFont = f;
        cachedLineEpoch = epoch;
        cachedLineBase = base;
    }

    /**
     * One line, shaped for the direction the surrounding interface reads. The first-strong rule
     * still decides everything a strong character can decide; the fallback is what this widget
     * knows and the string does not.
     */
    private ShapedText shapeOneLine(TextRuler ruler, String text, Font f) {
        return ruler.shape(text, f, ShapedText.Direction.of(text, neutralBase()));
    }

    /** Points the window at the lines about to be painted, dropping slots that now name others. */
    private void setLineWindow(int firstLine, int count) {
        if (cachedLines == null || cachedLines.length != count) {
            cachedLines = new ShapedText[count];
        } else if (cachedFirstLine != firstLine) {
            Arrays.fill(cachedLines, null);
        }
        cachedFirstLine = firstLine;
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

    /** The shaped viewport window; slot {@code i} is line {@code cachedFirstLine + i}. */
    private ShapedText[] cachedLines;
    private int cachedFirstLine = -1;
    private long cachedTextVersion = -1;
    /** The font the window and the spill were shaped under, half of their validity key. */
    private Font cachedLineFont;
    /** The ruler epoch they were shaped under, the other half. See {@link #syncLineCache}. */
    private long cachedLineEpoch = -1;
    /**
     * The neutral fallback they were shaped under. Part of the key because this cache is
     * hand-written and never asks {@link ShapedText#matches}: a direction change is invisible to
     * the version, the font and the epoch alike, and the lines it produced are wrong by a
     * fraction of a point in every geometry query asked of them.
     */
    private ShapedText.Direction cachedLineBase;
    /** One line outside the window, shaped on demand; see {@link #shapedLine}. */
    private ShapedText spilledLine;
    private int spilledLineIndex = -1;
    /** Selection boxes, reused across paints; see {@link #fillSpans}. */
    private float[] spans = new float[8];

    /**
     * One Left or Right press: a step <b>on the screen</b>, and the line change when there is no
     * step left to take on this line.
     *
     * <p>The model answers the step over the line's own shaping and reports whether anything
     * moved; {@code false} means the caret was already at that visual edge, and the line change is
     * this widget's because the model holds no lines. The caret enters the neighbouring line at
     * <em>its</em> opposite edge, and the two edge positions are {@code hitTest(0)} and
     * {@code hitTest(width)} rather than index {@code 0} and index {@code length}: on a line whose
     * last cluster reads against the paragraph, the last character is not the one at the right
     * edge, and entering at the wrong one puts the caret a run away from where the key pointed.
     *
     * <p>A {@code false} return is the <em>only</em> thing that changes line. In particular the
     * model returns {@code true} when it merely collapses a selection, which is what keeps
     * Left-with-a-selection from collapsing and hopping a line in one keystroke.
     *
     * <p><b>Up and Down deliberately do not come through here.</b> They keep a sticky goal
     * <em>column</em> &mdash; a char offset within the line, a logical count &mdash; and not a
     * goal x. Under a proportional face that column is already the wrong x, and under bidi it is
     * not even wrong so much as meaningless, since one column is two places on the line. A goal x
     * is the right answer and it is not this change: it needs the model to turn an x into an index
     * on a line it does not hold, which is a new API with its own tests, and folding a vertical
     * navigation change into the bidi caret work is exactly the coupling that makes a later bisect
     * useless. Nothing here makes Up/Down worse than they were.
     *
     * @param left  whether this is the Left arrow rather than the Right
     * @param shift whether the press extends the selection
     */
    private void moveCaretVisually(boolean left, boolean shift) {
        SizeTokens t = tokens();
        int lineIndex = model.lineOf(model.cursor());
        int lineStart = model.lineStartOfLine(lineIndex);
        ShapedText line = shapedLine(lineIndex, t);
        boolean moved = left
                ? model.moveVisualLeft(line, lineStart, shift)
                : model.moveVisualRight(line, lineStart, shift);
        if (moved) {
            return;
        }
        int target = left ? lineIndex - 1 : lineIndex + 1;
        if (target < 0 || target >= model.lineCount()) {
            return; // the document's own edge: the key does nothing, as it always has
        }
        ShapedText neighbour = shapedLine(target, t);
        ShapedText.Position edge = left
                ? neighbour.hitTest(neighbour.metrics().width())
                : neighbour.hitTest(0);
        model.setCaret(new ShapedText.Position(model.lineStartOfLine(target) + edge.charIndex(),
                edge.affinity()), shift);
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
        int cursor = model.cursor();
        float lineHeight = lineHeight(t);
        float cx = caretContentX(t); // includes any in-progress composition
        float cy = model.lineOf(cursor) * lineHeight;
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
        canvas.clipRect(padX - Strokes.AA_BLEED, padY,
                viewWidth(t) + 2 * Strokes.AA_BLEED, viewHeight(t));
        canvas.translate(contentOriginX(t), padY - scrollY);

        int firstLine = Math.max(0, (int) (scrollY / lineHeight));
        int lastLine = Math.min(model.lineCount() - 1,
                (int) ((scrollY + viewHeight(t)) / lineHeight) + 1);
        Color ink = isEnabled() ? theme.text : theme.disabledText;
        int selStart = model.selectionStart();
        int selEnd = model.selectionEnd();
        boolean selection = model.hasSelection() && isFocused();
        boolean composing = !preedit.isEmpty();
        int composingLine = composing ? model.lineOf(model.cursor()) : -1;

        Color selectionFill = theme.primary.withAlpha(0.35f);
        setLineWindow(firstLine, lastLine - firstLine + 1);
        for (int line = firstLine; line <= lastLine; line++) {
            float top = lineTop(line, lineHeight);
            if (composing && line == composingLine) {
                ShapedText composedForLine = composedLine(t);
                paintComposingLine(canvas, theme, composedForLine, cursorInLine(line), top,
                        metrics, lineHeight, ink, lineOriginX(composedForLine, t));
                continue; // composition suppresses selection painting on this line
            }
            ShapedText shaped = shapedLine(line, t);
            // Where this line sits inside content space: zero reading left to right, and flush
            // against the content's right edge reading right to left. Threaded through every x
            // below rather than applied as a transform, so one line's placement can never leak
            // into the next one's.
            float ox = lineOriginX(shaped, t);
            if (selection) {
                int lineStart = model.lineStartOfLine(line);
                int lineEnd = lineStart + shaped.text().length();
                int from = Math.max(selStart, lineStart);
                int to = Math.min(selEnd, lineEnd);
                boolean breakSelected = selStart <= lineEnd && selEnd > lineEnd;
                if (from < to || breakSelected) {
                    // N boxes, never one: a range that is contiguous in the string stops being
                    // contiguous on the line the moment it crosses a direction boundary, and the
                    // smallest rectangle covering both halves would highlight the untouched text
                    // drawn between them.
                    int boxes = fillSpans(shaped, from - lineStart, to - lineStart);
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
                        // has to show, so the widget draws it at the line's START edge: 2 pt is the
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
        // not be repainted by a blink and would leave an artifact behind.
        if (isFocused() && cursorVisible && !model.hasSelection() && !composing) {
            int caretLine = model.lineOf(model.cursor());
            ShapedText shaped = shapedLine(caretLine, t);
            float cx = lineOriginX(shaped, t) + shaped.caretX(
                    lineLocal(model.caret(), model.lineStartOfLine(caretLine), shaped));
            float cy = lineTop(caretLine, lineHeight);
            canvas.drawLine(cx, cy + Strokes.INK_BLEED, cx, cy + lineHeight - Strokes.INK_BLEED,
                    Strokes.CARET, theme.text);
        }
        canvas.restore();
    }

    /**
     * Draws the cursor's line with the IME composition injected at the caret: the whole line
     * shaped <b>once</b> with the preedit already in it, the preedit underlined, the block being
     * converted highlighted, and the caret inside the preedit.
     *
     * <p>One shaping and not three is a bug fix rather than a port. Measuring the committed
     * prefix, the preedit and the committed suffix separately is three answers to a question that
     * only has one, because Arabic and Indic <em>join across the seams those three measurements
     * cut</em>: a preedit typed into the middle of a word changes the forms on both sides of
     * itself, and the underline drawn from separate measurements lands somewhere the ink is not.
     * Shaping the composed line and asking it for sub-ranges cannot drift from what it draws.
     *
     * @param line     the composed line: committed text with the preedit spliced in at the caret
     * @param cursorAt the cursor's char offset within the committed line, which is where the
     *                 preedit begins inside {@code line}
     * @param originX  where this line sits inside content space; zero reading left to right
     */
    private void paintComposingLine(Canvas canvas, Theme theme, ShapedText line, int cursorAt,
                                    float top, TextMetrics metrics, float lineHeight, Color ink,
                                    float originX) {
        float baseline = top + metrics.ascent();
        // Asked once and read twice, and only when there IS a converting block: an empty range
        // still allocates a scratch box array inside selection(), on a path that runs per blink.
        boolean converting = preeditFocusEnd > preeditFocusStart;
        List<ShapedText.Span> focusBoxes = converting
                ? line.selection(cursorAt + preeditFocusStart, cursorAt + preeditFocusEnd)
                : List.of();

        // Highlight first, so it sits behind the ink rather than over it.
        for (ShapedText.Span s : focusBoxes) {
            canvas.fillRect(originX + s.x0(), top, s.width(), lineHeight,
                    theme.primary.withAlpha(0.18f));
        }
        canvas.drawText(line, originX, baseline, ink);
        // The BOTTOM OF THE INK BOX, from the line's own anchor, not "baseline + 2". A fixed
        // 2 pt drop is inside the descender at every step and cuts it outright at 19 pt, since
        // the descender runs 3.42 pt below the baseline at MEDIUM and 4.64 at XLARGE. TextField
        // has always anchored here; this is the same expression.
        float underlineY = top + metrics.height();
        // The 1-vs-2 contrast is what says "this block is converting"; scaling either erases it.
        // One stroke per box, because a preedit that spans a direction boundary is underlined in
        // as many pieces as it is drawn in.
        for (ShapedText.Span s : line.selection(cursorAt, cursorAt + preedit.length())) {
            canvas.drawLine(originX + s.x0(), underlineY, originX + s.x1(), underlineY,
                    Strokes.IME_UNDERLINE, theme.textMuted);
        }
        for (ShapedText.Span s : focusBoxes) {
            canvas.drawLine(originX + s.x0(), underlineY, originX + s.x1(), underlineY,
                    Strokes.IME_UNDERLINE_ACTIVE, theme.primary);
        }

        if (isFocused() && cursorVisible) {
            // The same x the scroll clamp reads, which composes it the same way.
            float cx = originX + line.caretX(composedCaret(cursorAt));
            canvas.drawLine(cx, top + Strokes.INK_BLEED, cx, top + lineHeight - Strokes.INK_BLEED,
                    Strokes.CARET, theme.text);
        }
    }

    /**
     * The cursor's line with the composition spliced in at the caret, shaped once and held while
     * the composition lasts. What {@link #paintComposingLine} draws, and what the scroll clamp and
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
                resetBlink();
                event.consume();
            }
            case DRAG -> {
                model.setCaret(
                        caretAtContent(lx - contentOriginX(t), ly - padY + scrollY, t), true);
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
            // Up/Down stay logical, on a sticky goal COLUMN in chars; see moveCaretVisually.
            case Keys.UP -> {
                if (cmd) {
                    model.moveDocumentStart(shift);
                } else {
                    model.moveUp(shift);
                }
            }
            case Keys.DOWN -> {
                if (cmd) {
                    model.moveDocumentEnd(shift);
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
        float cyContent = lineTop(model.lineOf(model.cursor()), lh);
        // Content space → local (the paint translate is padX/padY - scroll), clamped so
        // the candidate window stays anchored inside the visible padded viewport. The clamp
        // is per axis for the same reason the translate is.
        float localX = Math.max(padX,
                Math.min(contentOriginX(t) + cxContent, padX + viewWidth(t)));
        float localY = Math.max(padY, Math.min(padY - scrollY + cyContent, height() - padY));
        return new Rect(localToSceneX() + localX, localToSceneY() + localY, Strokes.CARET, lh);
    }

    private void fireChange() {
        invalidateContentWidth(); // text changed: widest line may differ
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
        shapedWidthFloor = 0; // and the scroll range it was holding open
        preeditCaret = 0;
        preeditFocusStart = 0;
        preeditFocusEnd = 0;
        invalidate();
    }
}

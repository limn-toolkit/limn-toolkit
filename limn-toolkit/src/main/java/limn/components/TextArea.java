package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.text.TextEditModel;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Rect;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.event.PreeditEvent;

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
        cachedContentWidth = -1;
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

    /** Horizontal scroll offset in logical points, {@code 0} at the left edge. */
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
     *. The flag is cleared at exactly two sites ({@link #setText} and
     * {@link #fireChange}), and neither is on the metrics path, so the cache was <em>already</em>
     * stale after a runtime {@code Fonts.setDefaultFamily} switch: {@code Scene.relayout} only
     * marks measure caches dirty and never reaches a widget's private state. The size axis adds
     * a second such path, since a step change swaps the body font underneath the same text.
     * Validating against a probe measurement closes both without a cross-file hook, and it has
     * to be the <em>measurement</em> rather than the {@link Font}: a family re-bound underneath
     * {@code Font.DEFAULT_FAMILY} leaves the record identical and only its metrics move.
     */
    private float contentWidth(SizeTokens t) {
        Font f = t.body();
        TextMetrics probe = textRuler().measure(PROBE, f);
        if (cachedContentWidth < 0 || !f.equals(cachedWidthFont) || !probe.equals(cachedWidthProbe)) {
            TextRuler ruler = textRuler();
            float widest = 0;
            for (int line = 0; line < model.lineCount(); line++) {
                widest = Math.max(widest, ruler.measure(model.lineText(line), f).width());
            }
            cachedContentWidth = widest;
            cachedWidthFont = f;
            cachedWidthProbe = probe;
        }
        return cachedContentWidth;
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

    private float cursorLineX(int charIndex, SizeTokens t) {
        int start = model.lineStart(charIndex);
        return textRuler().measure(model.textRange(start, charIndex), t.body()).width();
    }

    /** X of the caret within its line, including any in-progress composition up to the preedit caret. */
    private float caretContentX(SizeTokens t) {
        float x = cursorLineX(model.cursor(), t);
        if (!preedit.isEmpty()) {
            int c = Math.min(preeditCaret, preedit.length());
            x += textRuler().measure(preedit.substring(0, c), t.body()).width();
        }
        return x;
    }

    /** Model index for a content-space point (already scroll-adjusted). */
    private int indexAtContent(float px, float py, SizeTokens t) {
        int line = Math.max(0, Math.min((int) (py / lineHeight(t)), model.lineCount() - 1));
        String lineText = model.lineText(line);
        int lineStartIndex = model.lineStartOfLine(line);
        if (lineText.isEmpty()) {
            return lineStartIndex;
        }
        TextRuler ruler = textRuler();
        Font f = t.body();
        // Binary search over monotone prefix widths (see TextField.indexAt).
        int[] bounds = TextField.codePointBoundaries(lineText);
        int count = bounds.length - 1;
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            float widthAt = ruler.measure(lineText.substring(0, bounds[mid]), f).width();
            float widthNext = ruler.measure(lineText.substring(0, bounds[mid + 1]), f).width();
            if (px < (widthAt + widthNext) / 2) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return model.alignToGrapheme(lineStartIndex + bounds[lo]);
    }

    /** Entry-point form: resolves the step once for callers that have no tokens in hand. */
    /**
     * The visible lines, materialized once per (text, viewport) rather than once per paint.
     *
     * <p>{@code model.lineText} hands back a substring, so a screenful of lines was a screenful
     * of fresh Strings on every repaint, and a focused idle editor repaints twice a second
     * forever, because the caret blink invalidates a column and the paint pass runs whole. A
     * selection drag paid it at frame rate.
     *
     * <p>Bounded by the viewport, not by the document: caching every line the model has ever been
     * asked for would be a second copy of the text for a long one, and the viewport is the only
     * part any repaint touches. Keyed on {@link TextEditModel#textVersion()}, which moves only
     * when the buffer does: a caret move or a selection change reuses the array, which is
     * exactly the case this exists for.
     */
    private String[] visibleLines(int firstLine, int lastLine) {
        int count = lastLine - firstLine + 1;
        long version = model.textVersion();
        if (cachedLines != null && cachedLines.length == count
                && cachedFirstLine == firstLine && cachedTextVersion == version) {
            return cachedLines;
        }
        String[] lines = cachedLines != null && cachedLines.length == count
                ? cachedLines
                : new String[count];
        for (int i = 0; i < count; i++) {
            lines[i] = model.lineText(firstLine + i);
        }
        cachedLines = lines;
        cachedFirstLine = firstLine;
        cachedTextVersion = version;
        return lines;
    }

    private String[] cachedLines;
    private int cachedFirstLine = -1;
    private long cachedTextVersion = -1;

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
        // The two CLIP_CLEARANCE terms must stay identical or the caret oscillates per keystroke.
        if (cx - scrollX > viewWidth(t) - Strokes.CLIP_CLEARANCE) {
            scrollX = cx - viewWidth(t) + Strokes.CLIP_CLEARANCE;
        }
        if (cx < scrollX) {
            scrollX = cx;
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
        canvas.translate(padX - scrollX, padY - scrollY);

        int firstLine = Math.max(0, (int) (scrollY / lineHeight));
        int lastLine = Math.min(model.lineCount() - 1,
                (int) ((scrollY + viewHeight(t)) / lineHeight) + 1);
        Color ink = isEnabled() ? theme.text : theme.disabledText;
        int selStart = model.selectionStart();
        int selEnd = model.selectionEnd();
        boolean selection = model.hasSelection() && isFocused();
        boolean composing = !preedit.isEmpty();
        int composingLine = composing ? model.lineOf(model.cursor()) : -1;

        String[] lines = visibleLines(firstLine, lastLine);
        for (int line = firstLine; line <= lastLine; line++) {
            String lineText = lines[line - firstLine];
            float top = lineTop(line, lineHeight);
            if (composing && line == composingLine) {
                paintComposingLine(canvas, theme, ruler, f, metrics, lineText, line, top,
                        lineHeight, ink);
                continue; // composition suppresses selection painting on this line
            }
            if (selection) {
                int lineStart = model.lineStartOfLine(line);
                int lineEnd = lineStart + lineText.length();
                int from = Math.max(selStart, lineStart);
                int to = Math.min(selEnd, lineEnd);
                if (from < to || (selStart <= lineEnd && selEnd > lineEnd)) {
                    float x0 = ruler.measure(model.textRange(lineStart, Math.max(from, lineStart)), f).width();
                    float x1 = to > from
                            ? ruler.measure(model.textRange(lineStart, to), f).width()
                            : x0;
                    if (selEnd > lineEnd && selStart <= lineEnd) {
                        // An optical gap next to the type it trails, so it rides the em-tuned
                        // gap ramp: hints that the newline is part of the selection.
                        x1 += t.newlineHint();
                    }
                    // A zero-width selection on an empty line still has to show: 2 pt is the
                    // minimum that survives AA at ANY size, so the floor is locked.
                    canvas.fillRect(x0, top, Math.max(Strokes.MIN_SELECTION_SLIVER, x1 - x0),
                            lineHeight, theme.primary.withAlpha(0.35f));
                }
            }
            canvas.drawText(lineText, 0, top + metrics.ascent(), f, ink);
        }

        // Normal caret (the composing line draws its own caret inside the preedit). Inset by
        // the locked ink bleed at both ends so a 1 pt pen brackets the glyphs without touching
        // the neighbouring line boxes.
        if (isFocused() && cursorVisible && !model.hasSelection() && !composing) {
            float cx = cursorLineX(model.cursor(), t);
            float cy = lineTop(model.lineOf(model.cursor()), lineHeight);
            canvas.drawLine(cx, cy + Strokes.INK_BLEED, cx, cy + lineHeight - Strokes.INK_BLEED,
                    Strokes.CARET, theme.text);
        }
        canvas.restore();
    }

    /**
     * Draws the cursor's line with the IME composition injected at the caret:
     * committed prefix, then the underlined preedit (block being converted
     * highlighted), then the committed suffix, with the caret inside the preedit.
     */
    private void paintComposingLine(Canvas canvas, Theme theme, TextRuler ruler, Font f,
                                    TextMetrics metrics, String lineText, int line, float top,
                                    float lineHeight, Color ink) {
        int lineStart = model.lineStartOfLine(line);
        int cursorInLine = Math.max(0, Math.min(model.cursor() - lineStart, lineText.length()));
        float baseline = top + metrics.ascent();

        String prefix = lineText.substring(0, cursorInLine);
        canvas.drawText(prefix, 0, baseline, f, ink);
        float preStart = ruler.measure(prefix, f).width();

        float focus0 = preStart + ruler.measure(preedit.substring(0, preeditFocusStart), f).width();
        float focus1 = preStart + ruler.measure(preedit.substring(0, preeditFocusEnd), f).width();
        if (preeditFocusEnd > preeditFocusStart) {
            canvas.fillRect(focus0, top, focus1 - focus0, lineHeight, theme.primary.withAlpha(0.18f));
        }
        canvas.drawText(preedit, preStart, baseline, f, ink);
        float preW = ruler.measure(preedit, f).width();
        // The BOTTOM OF THE INK BOX, from the line's own anchor, not "baseline + 2". A fixed
        // 2 pt drop is inside the descender at every step and cuts it outright at 19 pt, since
        // the descender runs 3.42 pt below the baseline at MEDIUM and 4.64 at XLARGE. TextField
        // has always anchored here; this is the same expression.
        float underlineY = top + metrics.height();
        // The 1-vs-2 contrast is what says "this block is converting"; scaling either erases it.
        canvas.drawLine(preStart, underlineY, preStart + preW, underlineY,
                Strokes.IME_UNDERLINE, theme.textMuted);
        if (preeditFocusEnd > preeditFocusStart) {
            canvas.drawLine(focus0, underlineY, focus1, underlineY,
                    Strokes.IME_UNDERLINE_ACTIVE, theme.primary);
        }
        canvas.drawText(lineText.substring(cursorInLine), preStart + preW, baseline, f, ink);

        if (isFocused() && cursorVisible) {
            int cc = Math.min(preeditCaret, preedit.length());
            float cx = preStart + ruler.measure(preedit.substring(0, cc), f).width();
            canvas.drawLine(cx, top + Strokes.INK_BLEED, cx, top + lineHeight - Strokes.INK_BLEED,
                    Strokes.CARET, theme.text);
        }
    }

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
                model.setCursor(indexAtContent(lx - padX + scrollX, ly - padY + scrollY, t),
                        (event.modifiers() & Keys.MOD_SHIFT) != 0);
                resetBlink();
                event.consume();
            }
            case DRAG -> {
                model.setCursor(indexAtContent(lx - padX + scrollX, ly - padY + scrollY, t), true);
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
            case Keys.LEFT -> {
                if (word) {
                    model.moveWordLeft(shift);
                } else if (cmd) {
                    model.moveHome(shift);
                } else {
                    model.moveLeft(shift);
                }
            }
            case Keys.RIGHT -> {
                if (word) {
                    model.moveWordRight(shift);
                } else if (cmd) {
                    model.moveEnd(shift);
                } else {
                    model.moveRight(shift);
                }
            }
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
        float localX = Math.max(padX, Math.min(padX - scrollX + cxContent, width() - padX));
        float localY = Math.max(padY, Math.min(padY - scrollY + cyContent, height() - padY));
        return new Rect(localToSceneX() + localX, localToSceneY() + localY, Strokes.CARET, lh);
    }

    private void fireChange() {
        cachedContentWidth = -1; // text changed: widest line may differ
        onChange.accept(model.text());
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
        preeditCaret = 0;
        preeditFocusStart = 0;
        preeditFocusEnd = 0;
        invalidate();
    }
}

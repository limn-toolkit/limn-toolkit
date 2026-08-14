package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.text.TextEditModel;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.i18n.I18nString;
import limn.graphics.Icon;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
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
 * Single-line text input: blinking cursor (frame-clock ticker), mouse and
 * Shift+arrow selection, Home/End, Ctrl/Cmd+A/C/V/X through the system
 * clipboard, placeholder, and horizontal scrolling that keeps the cursor
 * visible when the text overflows. Colors come from the {@link Theme}, metrics from the
 * {@link SizeTokens} row of the step resolved on this widget, and every stroke from
 * {@link Strokes}, which is why a focus ring is the same weight at XSMALL and XLARGE.
 *
 * <p>The reference implementation for the text cluster: {@link PasswordField} and
 * {@link SearchField} inherit all of this geometry and declare none of their own.
 */
public class TextField extends Widget {

    /** Validation state; colors the border (and a caller-supplied message). */
    public enum Validation { NONE, ERROR, WARNING, SUCCESS, INFO }

    private static final double BLINK_SECONDS = 0.5;

    protected final TextEditModel model = new TextEditModel(true);
    private I18nString placeholder = I18nString.EMPTY;
    private Consumer<String> onChange = text -> {
    };
    /**
     * {@code < 0} means "unset": {@link #onMeasure} falls back to the resolved step's
     * {@code fieldWidth}. A step cannot be read in a field initializer: the widget has no
     * parent yet and would latch the process default forever.
     */
    private float preferredWidth = -1;
    private float scrollX;
    private boolean cursorVisible = true;
    // Optional in-field adornments (icons rasterize/select lazily at paint).
    private Icon leadingIcon;
    private Icon trailingIcon;
    private Runnable onTrailing = () -> {
    };
    private boolean trailingHover;
    private boolean trailingArmed; // trailing button pressed, draws the press feedback
    private Validation validation = Validation.NONE;
    // Active IME composition ("preedit"): shown inline at the caret, underlined,
    // NOT part of the model; the commit later arrives as an ordinary CharEvent.
    private String preedit = "";
    private int preeditCaret;      // caret within the preedit, in chars
    private int preeditFocusStart; // char range of the focused (converting) block
    private int preeditFocusEnd;
    /** Bumped whenever the blink phase restarts; stale scheduled toggles no-op. */
    private int blinkGeneration;
    /** Focus-ring fade: morphs the border between outline and focusRing. */
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);

    /** An empty single-line field. */
    public TextField() {
        setFocusable(true);
        setCursor(Cursor.TEXT); // I-beam over editable text
    }

    // ------------------------------------------------------------------- API

    /** The current contents. */
    public String text() {
        return model.text();
    }

    /** Replaces the contents, clearing the selection and undo history. UI thread only. */
    public TextField setText(String text) {
        Ui.checkUiThread();
        model.setText(text);
        // Show the head of the text; onPaint re-clamps against the real width
        // (which may still be 0 here: set before the first layout pass).
        scrollX = 0;
        invalidate();
        return this;
    }

    /** Sets a fixed placeholder, shown only while the field is empty. */
    public TextField setPlaceholder(String newPlaceholder) {
        return setPlaceholder(I18nString.literal(
                Objects.requireNonNull(newPlaceholder, "newPlaceholder")));
    }

    /**
     * Sets a placeholder that follows the UI language. A subclass shipping a default
     * one (see {@code SearchField}) simply calls this in its constructor: an
     * application's own {@code setPlaceholder} replaces the value, so there is no
     * "did the app override it" state to track.
     */
    public TextField setPlaceholder(I18nString newPlaceholder) {
        Ui.checkUiThread();
        this.placeholder = Objects.requireNonNull(newPlaceholder, "newPlaceholder");
        invalidate();
        return this;
    }

    /** The placeholder as it currently reads. */
    public String placeholder() {
        return placeholder.get();
    }

    /** Called with the full text after every edit, typed or programmatic. */
    public TextField onChange(Consumer<String> listener) {
        Ui.checkUiThread();
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Inserts {@code text} at the cursor (replacing any selection), as if typed. UI thread. */
    public TextField insertText(String text) {
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

    /** Overrides the step's {@code fieldWidth}; a negative value restores it. */
    public TextField setPreferredWidth(float width) {
        Ui.checkUiThread();
        this.preferredWidth = width;
        markNeedsLayout();
        return this;
    }

    /** A leading icon inside the field, tinted to the muted text color ({@code null} clears). */
    public TextField setLeadingIcon(Icon icon) {
        Ui.checkUiThread();
        this.leadingIcon = icon;
        markNeedsLayout();
        return this;
    }

    /**
     * A trailing coupled button (icon + action) inside the field, the caret/arrow
     * region idiom of {@link ComboBox}. {@code icon == null} removes it.
     */
    public TextField setTrailingButton(Icon icon, Runnable action) {
        Ui.checkUiThread();
        this.trailingIcon = icon;
        this.onTrailing = action != null ? action : () -> {
        };
        markNeedsLayout();
        return this;
    }

    /** Sets the validation state; colors the border danger/warning/success. */
    public TextField setValidation(Validation state) {
        Ui.checkUiThread();
        this.validation = Objects.requireNonNull(state, "state");
        invalidate();
        return this;
    }

    /** Convenience: {@code ERROR} when true, {@code NONE} when false. */
    public TextField setError(boolean error) {
        return setValidation(error ? Validation.ERROR : Validation.NONE);
    }

    /** The current validation state, which drives the border and helper colours. */
    public Validation validation() {
        return validation;
    }

    /** @return the editing model (cursor/selection state), for tests and subclasses */
    public TextEditModel model() {
        return model;
    }

    // -------------------------------------------------------- display hooks

    /** What is painted/measured; {@code PasswordField} masks here. */
    protected String displayText() {
        return model.text();
    }

    /** Display form of the text up to {@code charIndex} of the model. */
    protected String displayPrefix(int charIndex) {
        return model.text().substring(0, Math.max(0, Math.min(charIndex, model.length())));
    }

    /** Whether copy/cut may export the content (password fields say no). */
    protected boolean allowClipboardCopy() {
        return true;
    }

    /**
     * Advance of a display string: the <b>only</b> place the display form's extent is decided.
     *
     * <p>Every horizontal coordinate in this component (caret x, selection band, the click
     * mapping's binary search, the scroll clamp) is a difference of two of these, so a subclass
     * that overrides this and {@link #paintDisplayText} together stays self-consistent by
     * construction: measure and paint cannot disagree about where the n-th mark sits. It exists
     * because {@link PasswordField}'s marks are not glyphs; see its {@code DOT_ADVANCE}.
     *
     * <p>The {@code display} argument is always a value returned by {@link #displayText()} or
     * {@link #displayPrefix(int)}; the preedit is never routed through here (a composition is
     * never masked, and IME is off in the one subclass that masks).
     */
    protected float displayWidth(String display, SizeTokens t) {
        return textRuler().measure(display, t.body()).width();
    }

    /**
     * Draws a display string with its left edge at {@code x}, the paint-side twin of
     * {@link #displayWidth}. {@code metrics} is passed rather than only the baseline so an
     * override can place ink against the same band the caret and the selection fill use: the ink
     * box starts at {@code baseline - metrics.ascent()} and is {@code metrics.height()} tall.
     */
    protected void paintDisplayText(Canvas canvas, String display, float x, float baseline,
                                    TextMetrics metrics, SizeTokens t, Color ink) {
        canvas.drawText(display, x, baseline, t.body(), ink);
    }

    // ----------------------------------------------------------- measurement

    /**
     * Top of the text <em>ink</em> box: the single vertical anchor for the baseline, the
     * caret, the selection band, the preedit underlines and {@link #caretRect()}. Before this
     * existed the baseline was centred while everything else was placed from {@code padV},
     * two expressions that only agreed at MEDIUM: at the steps where the height floor binds
     * the locked 1&nbsp;pt {@code INK_BLEED} silently became 0.03–1.97&nbsp;pt and the preedit
     * underline landed inside the descender ink.
     */
    private float textTop(TextMetrics metrics) {
        return (height() - metrics.height()) / 2;
    }

    /** Left edge of the text area (after the pad and any leading icon). */
    private float leadingInset(SizeTokens t) {
        return t.fieldPadH() + (leadingIcon == null ? 0 : t.fieldIcon() + t.gapIcon());
    }

    /**
     * The coupled trailing button's extent: one expression for the painted region <em>and</em>
     * the hit region, so the two can never drift apart.
     *
     * <p>No {@code MIN_HIT_TARGET} clamp here, deliberately: {@code fieldTrailing} is the
     * control height at every step, so it is already 24 at its smallest. {@link Strokes} and
     * {@link limn.scene.ControlSize} both enumerate the surviving clamp sites exhaustively
     * ({@code MenuBar.titleWidth} and {@code SegmentedControl}'s segment width, and nothing
     * else), and a third one here, however harmless, would make those statements false. That
     * enforcement is meant to be structural rather than editorial.
     */
    private float trailingWidth(SizeTokens t) {
        return t.fieldTrailing();
    }

    /** Space reserved on the right (pad + any trailing button region). */
    private float trailingInset(SizeTokens t) {
        return trailingIcon == null ? t.fieldPadH() : trailingWidth(t);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure("Hg", t.body());
        return constraints.constrain(preferredWidth >= 0 ? preferredWidth : t.fieldWidth(),
                t.resolvedHeight(metrics.lineHeight()));
    }

    @Override
    protected float baselineOffset() {
        TextMetrics metrics = textRuler().measure("Hg", Theme.current().tokensFor(this).body());
        return textTop(metrics) + metrics.ascent();
    }

    private float innerWidth(SizeTokens t) {
        return Math.max(0, width() - leadingInset(t) - trailingInset(t));
    }

    private float prefixWidth(int charIndex, SizeTokens t) {
        return displayWidth(displayPrefix(charIndex), t);
    }

    /** Model char index whose boundary is closest to display-x {@code px}. */
    private int indexAt(float px, SizeTokens t) {
        String text = model.text();
        if (text.isEmpty()) {
            return 0;
        }
        // Code-point boundaries (cheap, no measuring), then a binary search over
        // the monotone prefix widths: O(log n) prefix measures instead of one
        // per code point: drag-selection stays smooth on long content.
        int[] bounds = codePointBoundaries(text);
        int count = bounds.length - 1; // boundaries 0..count
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            float widthAt = prefixWidth(bounds[mid], t);
            float widthNext = prefixWidth(bounds[mid + 1], t);
            if (px < (widthAt + widthNext) / 2) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        // Never drop the caret inside a combining run / ZWJ emoji.
        return model.alignToGrapheme(bounds[lo]);
    }

    /** All code-point boundaries of {@code text}: [0, …, text.length()]. */
    static int[] codePointBoundaries(String text) {
        int count = text.codePointCount(0, text.length());
        int[] bounds = new int[count + 1];
        int index = 0;
        for (int i = 0; i <= count; i++) {
            bounds[i] = index;
            if (i < count) {
                index = text.offsetByCodePoints(index, 1);
            }
        }
        return bounds;
    }

    /** Entry-point form: resolves the step once for callers that have no tokens in hand. */
    private void ensureCursorVisible() {
        ensureCursorVisible(Theme.current().tokensFor(this));
    }

    private void ensureCursorVisible(SizeTokens t) {
        if (width() <= 0) {
            return; // not laid out yet; onPaint clamps once real bounds exist
        }
        float cursorX = caretDisplayX(t); // includes any in-progress composition
        float inner = innerWidth(t);
        // The two CLIP_CLEARANCE terms must stay identical or the caret oscillates per keystroke.
        if (cursorX - scrollX > inner - Strokes.CLIP_CLEARANCE) {
            scrollX = cursorX - inner + Strokes.CLIP_CLEARANCE;
        }
        if (cursorX < scrollX) {
            scrollX = cursorX;
        }
        clampScrollX(t);
    }

    /** Keeps scrollX within [0, overflow] against the CURRENT width. */
    private void clampScrollX(SizeTokens t) {
        float content = displayWidth(displayText(), t) + preeditWidth(t);
        float overflow = Math.max(0, content - innerWidth(t));
        scrollX = Math.max(0, Math.min(scrollX, overflow));
    }

    private float preeditWidth(SizeTokens t) {
        return preedit.isEmpty() ? 0 : textRuler().measure(preedit, t.body()).width();
    }

    /** Display-x of the caret: committed text up to the cursor, plus the preedit up to its own caret. */
    private float caretDisplayX(SizeTokens t) {
        float x = prefixWidth(model.cursor(), t);
        if (!preedit.isEmpty()) {
            int c = Math.min(preeditCaret, preedit.length());
            x += textRuler().measure(preedit.substring(0, c), t.body()).width();
        }
        return x;
    }

    // ---------------------------------------------------------------- paint

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        Font f = t.body();
        TextMetrics metrics = textRuler().measure("Hg", f);

        Color fill = isEnabled() ? theme.surface : theme.disabledFill;
        canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(), fill);
        float focus = focusFade.value();

        // Leading icon, tinted to the muted ink, inside the left pad.
        if (leadingIcon != null) {
            float ico = t.fieldIcon();
            leadingIcon.paint(canvas, t.fieldPadH(), (height() - ico) / 2, ico,
                    isEnabled() ? theme.textMuted : theme.disabledText, theme.dark);
        }
        // Trailing coupled button (ComboBox-caret idiom): a themed background that
        // lights up on hover and deepens on press (click feedback), then the divider
        // and the icon on top. The background fills the whole region after the
        // divider (square on the divider side, the field's own radius on the outer
        // side), so it reads as one coupled button, not a floating pill.
        if (trailingIcon != null) {
            float regionW = trailingWidth(t);
            float regionX = width() - regionW;
            if (isEnabled() && (trailingHover || trailingArmed)) {
                Color bg = trailingArmed
                        ? theme.surfaceRaised.lerp(theme.text, 0.10f)
                        : theme.surfaceRaised;
                float r = t.radiusMedium();
                canvas.fillRoundRect(new RoundRect(regionX, 0, regionW, height(),
                        0, r, r, 0), bg);
            }
            // Its own inset token: the divider is a paint coordinate, and padV is not one.
            canvas.drawLine(regionX, t.fieldDividerInset(), regionX,
                    height() - t.fieldDividerInset(), Strokes.BORDER, theme.outline);
            float ico = t.fieldIcon();
            Color tint = !isEnabled() ? theme.disabledText
                    : (trailingHover || trailingArmed) ? theme.primary : theme.textMuted;
            trailingIcon.paint(canvas, regionX + (regionW - ico) / 2,
                    (height() - ico) / 2, ico, tint, theme.dark);
        }

        // Bounds may have changed since the last edit (resize, or setText
        // before the first layout): keep the horizontal scroll valid so the
        // text is never clipped fully out of view.
        clampScrollX(t);

        float left = leadingInset(t);
        canvas.save();
        canvas.clipRect(left - Strokes.AA_BLEED, 0,
                innerWidth(t) + 2 * Strokes.AA_BLEED, height());
        float inkTop = textTop(metrics);
        float baseline = inkTop + metrics.ascent();
        float originX = left - scrollX;

        String shown = displayText();
        boolean composing = !preedit.isEmpty();
        String hint = placeholder.get();
        if (shown.isEmpty() && !composing && !hint.isEmpty()) {
            // Keep the hint visible even while focused (only the caret joins it).
            canvas.drawText(hint, left, baseline, f, theme.textMuted);
            if (isFocused() && cursorVisible) {
                canvas.drawLine(left, inkTop - Strokes.INK_BLEED,
                        left, inkTop + metrics.height() + Strokes.INK_BLEED,
                        Strokes.CARET, theme.text);
            }
        } else if (composing) {
            paintComposing(canvas, theme, t, f, metrics, originX, inkTop, baseline);
        } else {
            if (model.hasSelection() && isFocused()) {
                float x0 = originX + prefixWidth(model.selectionStart(), t);
                float x1 = originX + prefixWidth(model.selectionEnd(), t);
                canvas.fillRect(x0, inkTop - Strokes.INK_BLEED, x1 - x0,
                        metrics.height() + 2 * Strokes.INK_BLEED,
                        theme.primary.withAlpha(0.35f));
            }
            Color ink = isEnabled() ? theme.text : theme.disabledText;
            paintDisplayText(canvas, shown, originX, baseline, metrics, t, ink);
            if (isFocused() && cursorVisible && !model.hasSelection()) {
                float cx = originX + prefixWidth(model.cursor(), t);
                canvas.drawLine(cx, inkTop - Strokes.INK_BLEED,
                        cx, inkTop + metrics.height() + Strokes.INK_BLEED,
                        Strokes.CARET, theme.text);
            }
        }
        canvas.restore();

        // Focus/validation border LAST: nothing (not even the trailing-button
        // background) paints above the focus ring. ONE stroke that thickens
        // continuously as the fade runs; a ternary here deletes the animation.
        Color border = borderColor(theme, focus);
        canvas.drawRoundRect(Strokes.HALF_PIXEL_INSET, Strokes.HALF_PIXEL_INSET,
                width() - 2 * Strokes.HALF_PIXEL_INSET, height() - 2 * Strokes.HALF_PIXEL_INSET,
                t.radiusMedium(),
                Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus, border);
    }

    private Color borderColor(Theme theme, float focus) {
        return switch (validation) {
            case NONE -> theme.outline.lerp(theme.focusRing, focus);
            case ERROR -> theme.danger;
            case WARNING -> theme.warning;
            case SUCCESS -> theme.success;
            case INFO -> theme.info;
        };
    }

    /**
     * Draws the committed prefix, then the underlined preedit (with the block
     * being converted highlighted), then the committed suffix, with the caret
     * placed inside the composition.
     */
    private void paintComposing(Canvas canvas, Theme theme, SizeTokens t, Font f,
                                TextMetrics metrics, float originX, float inkTop, float baseline) {
        String committed = displayText();
        int c = Math.min(model.cursor(), committed.length());
        Color ink = isEnabled() ? theme.text : theme.disabledText;
        float bandTop = inkTop - Strokes.INK_BLEED;
        float bandH = metrics.height() + 2 * Strokes.INK_BLEED;
        float underlineY = inkTop + metrics.height();

        // The committed halves go through the display hooks (the preedit itself never does:
        // a composition is never masked). Unreachable while masked (the one subclass that
        // masks refuses text input), but routing it keeps one answer for "how wide is this".
        String prefix = committed.substring(0, c);
        paintDisplayText(canvas, prefix, originX, baseline, metrics, t, ink);
        float preStart = originX + displayWidth(prefix, t);

        float focus0 = preStart + textRuler().measure(preedit.substring(0, preeditFocusStart), f).width();
        float focus1 = preStart + textRuler().measure(preedit.substring(0, preeditFocusEnd), f).width();
        if (preeditFocusEnd > preeditFocusStart) {
            canvas.fillRect(focus0, bandTop, focus1 - focus0, bandH, theme.primary.withAlpha(0.18f));
        }
        canvas.drawText(preedit, preStart, baseline, f, ink);
        float preW = textRuler().measure(preedit, f).width();
        // The 1-vs-2 contrast is what says "this block is converting"; scaling either erases it.
        canvas.drawLine(preStart, underlineY, preStart + preW, underlineY,
                Strokes.IME_UNDERLINE, theme.textMuted);
        if (preeditFocusEnd > preeditFocusStart) {
            canvas.drawLine(focus0, underlineY, focus1, underlineY,
                    Strokes.IME_UNDERLINE_ACTIVE, theme.primary);
        }

        paintDisplayText(canvas, committed.substring(c), preStart + preW, baseline, metrics, t, ink);

        if (isFocused() && cursorVisible) {
            int cc = Math.min(preeditCaret, preedit.length());
            float cx = preStart + textRuler().measure(preedit.substring(0, cc), f).width();
            canvas.drawLine(cx, bandTop, cx, bandTop + bandH, Strokes.CARET, theme.text);
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void onMouseEvent(MouseEvent event) {
        SizeTokens t = Theme.current().tokensFor(this);
        float lx = sceneToLocalX(event.x());
        float ly = sceneToLocalY(event.y());
        // Bounded on both axes: a DRAG that leaves the field vertically used to keep
        // reporting "over the trailing button" because Y was ignored entirely.
        boolean overTrailing = trailingIcon != null && lx >= width() - trailingWidth(t)
                && ly >= 0 && ly < height();
        switch (event.type()) {
            case MOVE, ENTER -> {
                if (overTrailing != trailingHover) {
                    trailingHover = overTrailing;
                    setCursor(overTrailing ? Cursor.POINTER : Cursor.TEXT);
                    invalidate();
                }
            }
            case EXIT -> {
                if (trailingHover || trailingArmed) {
                    trailingHover = false;
                    trailingArmed = false;
                    setCursor(Cursor.TEXT);
                    invalidate();
                }
            }
            case RELEASE -> {
                if (trailingArmed) {
                    trailingArmed = false;
                    invalidate();
                }
            }
            case PRESS -> {
                if (ContextMenus.isRequest(event)) {
                    // Focus first: the menu's Cut and Paste act on this field, and a field that
                    // was not focused when they run would edit while the caret lives elsewhere.
                    requestFocus();
                    showContextMenu(event.x(), event.y());
                    event.consume();
                } else if (event.button() == Keys.MOUSE_LEFT) {
                    if (overTrailing) {
                        if (isEnabled()) {
                            trailingArmed = true; // press feedback until release
                            invalidate();
                            onTrailing.run(); // the coupled action (e.g. clear/submit)
                        }
                    } else {
                        float px = lx - leadingInset(t) + scrollX;
                        model.setCursor(indexAt(px, t), (event.modifiers() & Keys.MOD_SHIFT) != 0);
                        resetBlink();
                    }
                    event.consume();
                }
            }
            case DRAG -> {
                if (!overTrailing) {
                    float px = lx - leadingInset(t) + scrollX;
                    model.setCursor(indexAt(px, t), true);
                    ensureCursorVisible(t);
                    resetBlink();
                }
                event.consume();
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
        // Word-wise with Ctrl (Windows/Linux) or Alt/Option (macOS); Cmd jumps to the
        // line edge (macOS). Ctrl/Cmd still trigger the letter shortcuts below.
        boolean word = allowsWordJumps() && (mods & (Keys.MOD_CONTROL | Keys.MOD_ALT)) != 0;
        boolean lineEdge = (mods & Keys.MOD_SUPER) != 0;
        // Ctrl+Alt together is AltGr (Windows synthesizes LCtrl+RAlt for it), so a
        // printable AltGr combo must fall through to the char callback instead of
        // firing the Ctrl letter shortcuts; else typing 'ą' (AltGr+A) select-alls
        // and the arriving char replaces the whole field.
        boolean altGr = (mods & Keys.MOD_CONTROL) != 0 && (mods & Keys.MOD_ALT) != 0;
        boolean shortcut = (mods & Keys.MOD_SUPER) != 0
                || (!altGr && (mods & Keys.MOD_CONTROL) != 0);
        boolean handled = true;
        switch (event.key()) {
            case Keys.LEFT -> {
                if (word) {
                    model.moveWordLeft(shift);
                } else if (lineEdge) {
                    model.moveHome(shift);
                } else {
                    model.moveLeft(shift);
                }
            }
            case Keys.RIGHT -> {
                if (word) {
                    model.moveWordRight(shift);
                } else if (lineEdge) {
                    model.moveEnd(shift);
                } else {
                    model.moveRight(shift);
                }
            }
            case Keys.HOME -> model.moveHome(shift);
            case Keys.END -> model.moveEnd(shift);
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
                    // Pasting an empty clipboard must not destroy the selection.
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

    private boolean copySelection(boolean cut) {
        if (!model.hasSelection() || !allowClipboardCopy()) {
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

    /** Whether modifier+arrow/delete may move word-wise (see {@link PasswordField}). */
    protected boolean allowsWordJumps() {
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
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure("Hg", t.body());
        float left = leadingInset(t);
        float localX = left - scrollX + caretDisplayX(t);
        localX = Math.max(left, Math.min(localX, width() - trailingInset(t)));
        float localY = textTop(metrics) - Strokes.INK_BLEED;
        return new Rect(localToSceneX() + localX, localToSceneY() + localY,
                Strokes.CARET, metrics.height() + 2 * Strokes.INK_BLEED);
    }

    /**
     * Raises the Cut/Copy/Paste/Select All menu at a point in this widget's own coordinates.
     *
     * <p>Protected so a subclass with different clipboard rules can suppress or replace it;
     * {@link PasswordField} does not need to, because {@link #allowClipboardCopy()} already greys
     * the two rows that would let a secret out.
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
                return allowClipboardCopy();
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
                ensureCursorVisible(Theme.current().tokensFor(TextField.this));
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
                    ensureCursorVisible(Theme.current().tokensFor(TextField.this));
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

    /** Notifies the change listener with the current text; for subclasses that edit the model directly. */
    protected void fireChange() {
        onChange.accept(model.text());
    }

    /** Runs {@code edit} and fires onChange only if the text actually changed. */
    private void fireIfChanged(Runnable edit) {
        String before = model.text();
        edit.run();
        if (!before.equals(model.text())) {
            fireChange();
        }
    }

    // ---------------------------------------------------------------- blink

    /**
     * Cursor blink via a self-rescheduling {@link Ui#postDelayed} rather than a
     * per-frame ticker, so a focused field lets the event loop sleep between
     * blinks instead of pinning it at the frame rate.
     */
    private void resetBlink() {
        cursorVisible = true;
        invalidateCaret();
        scheduleBlink(++blinkGeneration);
    }

    private void scheduleBlink(int generation) {
        Ui.postDelayed(() -> {
            if (generation != blinkGeneration || !isFocused()) {
                return; // superseded by a newer phase, or focus was lost
            }
            cursorVisible = !cursorVisible;
            invalidateCaret();
            scheduleBlink(generation);
        }, Math.round(BLINK_SECONDS * 1000));
    }

    /** Damages just the caret column: a blink repaints ~1×line-height, not the whole field. */
    private void invalidateCaret() {
        Rect caret = caretRect(); // scene coordinates, clamped inside the field
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
        // Tabbed into: take the contents, so the first keystroke replaces them and a copy needs
        // no extra gesture. Clicked into: leave them, because the click already chose a caret.
        // The neighbouring Spinner has made the same distinction for typing since it shipped.
        if (focusArrivedByTraversal()) {
            model.selectAll();
        }
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
        blinkGeneration++; // stop the pending blink
        cursorVisible = true;
        model.clearSelection();
        preedit = ""; // drop any in-progress composition
        preeditCaret = 0;
        preeditFocusStart = 0;
        preeditFocusEnd = 0;
        invalidate();
    }
}

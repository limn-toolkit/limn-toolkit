package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.Objects;

/**
 * Push button with the full state set (normal, hover, pressed, disabled,
 * focused) and an {@link #onAction(Runnable)} fired by click or by
 * Enter/Space while focused. All visuals come from the {@link Theme}. Keep
 * actions short (the slow-handler instrumentation will flag violations);
 * heavy work goes through {@code Ui.async}.
 *
 * <p>Sizes follow the {@link ControlSize} resolved on this widget: every metric comes from
 * the {@link SizeTokens} row, every weight from {@link Strokes}. Tokens are resolved once per
 * pass into a local and threaded down; resolving twice inside one pass is how measure and
 * paint end up disagreeing about where the label sits.
 *
 * <p>The icon-and-caption block is centred, so a text-only button draws the same coordinates
 * whichever way its subtree reads. What the direction decides is the <em>order</em> of the two
 * things inside that block: the icon is {@linkplain #setIcon(Icon) leading}, which is the block's
 * left end reading left to right and its right end reading right to left. Whether the glyph
 * drawn inside that square turns around as well is the caller's to say &mdash; see
 * {@link Icon.Mirroring}.
 */
public class Button extends Widget {

    private I18nString text;
    private Icon icon;
    // Whether the icon's own glyph turns around when the interface does, which is a different
    // question from which end of the block its square takes. NEVER by default, which is what
    // keeps every existing setIcon call meaning exactly what it meant.
    private Icon.Mirroring iconMirroring = Icon.Mirroring.NEVER;
    // ONE shaping of text.get(), held so the measure pass and the paint pass that follows it
    // shape the caption once between them and cannot disagree about its width. Refreshed
    // through ShapedText.matches, whose identity fast path on the string costs nothing here
    // because I18nString memoizes it.
    private ShapedText caption;
    private boolean secondary;
    private Runnable action = () -> {
    };
    // Hover and focus-ring fades, animated through the shared Transition.
    private final Transition hover =
            new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);
    private boolean armed;    // mouse press in progress
    private boolean keyArmed; // Space/Enter held: separate from the mouse so a
                              // key-up whose key-down never reached us can't fire

    /** A button with a fixed caption; see the {@link I18nString} constructor for localized text. */
    public Button(String text) {
        this(I18nString.literal(Objects.requireNonNull(text, "text")));
    }

    /** A button whose caption follows the UI language; see {@link I18nString}. */
    public Button(I18nString text) {
        this.text = Objects.requireNonNull(text, "text");
        setFocusable(true);
        setCursor(Cursor.POINTER); // click hint; disabled buttons aren't hit-tested → arrow
    }

    /**
     * Sets a leading icon, drawn tinted to the label color. {@code null} clears it. Leading is
     * the end of the caption block reading starts from, so the square trades ends with the
     * caption when the button reads right to left; the glyph inside it is drawn as authored.
     * Use {@link #setIcon(Icon, Icon.Mirroring)} for an icon that means a direction.
     */
    public Button setIcon(Icon newIcon) {
        return setIcon(newIcon, Icon.Mirroring.NEVER);
    }

    /**
     * A leading icon that says whether its glyph turns around when the interface does. Only the
     * code that placed an icon knows whether its arrow means "back" or "download", which is why
     * this is a flag here and never a classification inside the toolkit.
     *
     * @param mirroring {@link Icon.Mirroring#NEVER} unless this glyph is directional; the
     *                  square's own end of the block moves either way
     */
    public Button setIcon(Icon newIcon, Icon.Mirroring mirroring) {
        Ui.checkUiThread();
        Objects.requireNonNull(mirroring, "mirroring");
        this.icon = newIcon;
        this.iconMirroring = mirroring;
        markNeedsLayout();
        return this;
    }

    /** Secondary style: a surface-filled, outlined button instead of the filled primary. */
    public Button setSecondary(boolean value) {
        Ui.checkUiThread();
        this.secondary = value;
        invalidate();
        return this;
    }

    /** Chaining form of {@link #setControlSize}; {@code setControlSize} is {@code void}. */
    public Button withControlSize(ControlSize size) {
        setControlSize(size);
        return this;
    }

    /**
     * Horizontal room the icon claims, its gap to the label included: one expression so the
     * measure and paint sides cannot drift apart and silently mis-centre the content.
     *
     * <p>{@code iconBox} is integral at every step, which is what keeps the icon
     * rasterization cache's key count bounded: a fractional box would key a new
     * bitmap per step and per content scale.
     */
    private float iconAdvance(SizeTokens t) {
        if (icon == null) {
            return 0;
        }
        return t.iconBox() + (text.get().isEmpty() ? 0 : t.gapIcon());
    }

    /** Called on click, or on Space/Enter while focused. Not called while disabled. */
    public Button onAction(Runnable newAction) {
        Ui.checkUiThread();
        this.action = Objects.requireNonNull(newAction, "newAction");
        return this;
    }

    /** The caption as it currently reads; see {@link #textSource()} for the key behind it. */
    public String text() {
        return text.get();
    }

    /** The localizable value this button holds, which a language change re-resolves. */
    public I18nString textSource() {
        return text;
    }

    /** Replaces the caption with a fixed string. UI thread only. */
    public Button setText(String newText) {
        Objects.requireNonNull(newText, "newText");
        if (text.isLiteral() && text.get().equals(newText)) {
            return this;
        }
        return setText(I18nString.literal(newText));
    }

    /** Replaces the caption with a value that follows the UI language. UI thread only. */
    public Button setText(I18nString newText) {
        Ui.checkUiThread();
        Objects.requireNonNull(newText, "newText");
        if (text.equals(newText)) {
            return this; // unchanged: no layout pass (= full-window damage)
        }
        this.text = newText;
        markNeedsLayout();
        return this;
    }

    /** @return whether the button currently renders in its pressed state */
    boolean isArmed() {
        return armed || keyArmed;
    }

    /**
     * The caption as one shaped line: the value the measure pass, the baseline and the paint all
     * ask, so the three cannot answer differently. Re-shaped only when {@link ShapedText#matches}
     * says the held one is no longer the answer, which is the whole invalidation test &mdash; the
     * text, the {@link Font} (a value, so a control-size step or a theme change is caught by the
     * same comparison), the paragraph direction, and the ruler epoch that moves when a face is
     * evicted or the default family changes.
     *
     * <p>Shaped rather than measured, and that is the point of holding it: a caption whose base
     * direction the button decides cannot be asked of a measurement that has nowhere to put one.
     * The base is resolved here, once, and handed to both halves of the test above: asking
     * whether the held value is current and shaping a replacement have to be asking about the
     * same direction, or the check passes for a value the shape call would not have produced.
     */
    private ShapedText caption(TextRuler ruler, Font f) {
        String value = text.get();
        ShapedText.Direction base = ShapedText.Direction.of(value, neutralBase());
        if (caption == null || !caption.matches(value, f, base, ruler)) {
            caption = ruler.shape(value, f, base);
        }
        return caption;
    }

    /** Whether this button reads right to left. Resolve it once per pass, into a local. */
    private boolean isRtl() {
        return layoutDirection() == LayoutDirection.RTL;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        // A size, not an x: the caption claims the same width from either end and so does the
        // icon, so this box is the same in both directions and a container laying a button out
        // never sees the direction at all.
        TextMetrics metrics = caption(textRuler(), t.body()).metrics();
        // resolvedHeight is max(controlHeight, lineHeight + 2*padV): the box binds at every
        // step, which is what absorbed the undocumented +4 this used to carry.
        return constraints.constrain(
                metrics.width() + iconAdvance(t) + 2 * t.padH(),
                t.resolvedHeight(metrics.lineHeight()));
    }

    /** The baseline BASELINE rows align on, the very expression {@link #onPaint} draws with. */
    @Override
    protected float baselineOffset() {
        SizeTokens t = Theme.current().tokensFor(this);
        // The same shaped value the paint draws, for exactly the reason this method exists: two
        // opinions on one band are two numbers a BASELINE row can be aligned to. Nothing read off
        // it here is horizontal, so this expression is the same in both directions.
        TextMetrics metrics = caption(textRuler(), t.body()).metrics();
        return (height() - metrics.height()) / 2 + metrics.ascent();
    }

    /**
     * The focus ring is drawn OUTSIDE the box: centred {@link Strokes#FOCUS_GAP_BUTTON} out
     * with a {@link Strokes#FOCUS_RING} pen, so its outer ink reaches
     * {@link Strokes#FOCUS_RING_OUTSET}, and damage has to know that. {@code Scene} inflates a
     * widget's damage rect by {@code 1 + paintOutset()}, so without this override the fade-out
     * of a ring on the toolkit's most-used widget sheds stale pixels under partial rendering.
     *
     * <p>Declares reach only; it moves no ink. Checkbox, RadioButton and Label carry the same
     * kind of override for the same reason.
     */
    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_RING_OUTSET;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        float h = hover.value();
        Color fill;
        Color ink;
        if (secondary) {
            fill = !isEnabled() ? theme.disabledFill
                    : isArmed() ? theme.outline
                    : theme.surface.lerp(theme.surfaceRaised, h);
            ink = isEnabled() ? theme.text : theme.disabledText;
        } else {
            fill = !isEnabled() ? theme.disabledFill
                    : isArmed() ? theme.primaryPressed
                    : theme.primary.lerp(theme.primaryHover, h);
            ink = isEnabled() ? theme.onPrimary : theme.disabledText;
        }
        canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(), fill);
        if (secondary) {
            float inset = Strokes.HALF_PIXEL_INSET; // lands the 1pt stroke on one device pixel
            canvas.drawRoundRect(inset, inset, width() - 2 * inset, height() - 2 * inset,
                    t.radiusMedium(), Strokes.BORDER, theme.outline);
        }
        float focus = focusFade.value();
        if (focus > 0.001f) {
            // Focus ring drawn OUTSIDE the button with a 1px gap, never over the fill (a 2px
            // stroke centered 2px out leaves 1px clear). Both weights are pixel-locked, and the
            // radius stays DERIVED from the fill's so the ring is concentric at every step.
            // Gated on the fade value (not isFocused) so the fade-out keeps rendering.
            float gapOut = Strokes.FOCUS_GAP_BUTTON;
            canvas.drawRoundRect(-gapOut, -gapOut, width() + 2 * gapOut, height() + 2 * gapOut,
                    t.radiusMedium() + gapOut, Strokes.FOCUS_RING, theme.focusRing.withAlpha(focus));
        }
        Font font = t.body();
        ShapedText line = caption(textRuler(), font);
        TextMetrics metrics = line.metrics();

        // Resolved ONCE for the whole pass. Which end of the block the icon takes and which end
        // the caption takes are two answers to one question, and two resolutions that disagreed
        // inside one paint would draw the caption over the icon.
        boolean rtl = isRtl();

        float advance = iconAdvance(t);
        float block = advance + metrics.width();
        // The block is CENTRED and stays exactly where it is under mirroring: a centre is the
        // same number in both directions, which is what makes a text-only button — the
        // overwhelming majority — render identically either way. Only the order of the two
        // things inside the block changes, and that is the two placements below.
        float blockX = (width() - block) / 2;
        if (icon != null) {
            float iconSize = t.iconBox();
            // Leading, written as a coordinate: the block's own left edge reading left to right,
            // and its far end less the square reading right to left.
            float iconX = rtl ? blockX + block - iconSize : blockX;
            // The flag says what the icon MEANS and the axis says which way this button reads;
            // neither alone is the answer, and the flip itself is Icon.paint's, about the square
            // it was just handed.
            icon.paint(canvas, iconX, (height() - iconSize) / 2, iconSize, ink, theme.dark,
                    rtl && iconMirroring == Icon.Mirroring.IN_RTL);
        }
        // The caption takes the rest of the block: past the gutter reading left to right, and
        // from the block's own left edge reading right to left, where the gutter is at the far
        // end. With no icon the advance is zero and the two arms are the same number.
        float textX = rtl ? blockX : blockX + advance;
        canvas.drawText(line, textX, (height() - metrics.height()) / 2 + metrics.ascent(), ink);
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        switch (event.type()) {
            case ENTER -> hover.to(1);
            case EXIT -> {
                hover.to(0);
                armed = false;
                invalidate();
            }
            case PRESS -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    armed = true;
                    invalidate();
                    event.consume();
                }
            }
            case RELEASE -> {
                armed = false;
                invalidate();
                event.consume();
            }
            case CLICK -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    event.consume();
                    action.run();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (event.key() != Keys.ENTER && event.key() != Keys.SPACE) {
            return;
        }
        if (event.isPressed() && !event.isRepeat()) {
            keyArmed = true;
            invalidate();
            event.consume();
        } else if (!event.isPressed()) {
            boolean fire = keyArmed;
            keyArmed = false;
            invalidate();
            event.consume();
            if (fire) {
                action.run();
            }
        }
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
        armed = false;
        keyArmed = false;
        invalidate();
    }
}

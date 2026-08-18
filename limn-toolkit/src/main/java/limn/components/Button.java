package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.i18n.I18nString;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
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
 */
public class Button extends Widget {

    private I18nString text;
    private Icon icon;
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

    /** Sets a leading icon, drawn tinted to the label color. {@code null} clears it. */
    public Button setIcon(Icon newIcon) {
        Ui.checkUiThread();
        this.icon = newIcon;
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

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure(text.get(), t.body());
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
        TextMetrics metrics = textRuler().measure(text.get(), t.body());
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
        TextMetrics metrics = textRuler().measure(text.get(), font);

        float advance = iconAdvance(t);
        float cursorX = (width() - (advance + metrics.width())) / 2;
        if (icon != null) {
            float iconSize = t.iconBox();
            icon.paint(canvas, cursorX, (height() - iconSize) / 2, iconSize, ink, theme.dark);
            cursorX += advance;
        }
        canvas.drawText(text.get(), cursorX,
                (height() - metrics.height()) / 2 + metrics.ascent(), font, ink);
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

package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.function.Consumer;

/**
 * Horizontal slider selecting a value in {@code [min, max]}. Drag the thumb or
 * click the track to jump; keyboard arrows nudge by the {@linkplain #setStep
 * step} (Home/End jump to the ends, PageUp/PageDown by ten steps). A continuous
 * slider ({@code step == 0}) nudges by 1% of the range. The filled portion,
 * thumb and focus ring all come from the {@link Theme}; the thumb grows on hover
 * and the focus ring fades in through the shared {@link Transition} animator.
 *
 * <pre>{@code
 * Slider volume = new Slider(0, 100).setStep(5).setValue(30);
 * volume.onChange(v -> audio.setGain(v / 100f));
 * }</pre>
 *
 * <p>Sizes follow the {@link ControlSize} resolved on this widget. <b>This control's height
 * derives from a focus-ring constant</b>, {@code 2 * (knobHover + FOCUS_GAP_SLIDER + border)},
 * rather than from a font or a design height, so it must not be rounded onto the shared
 * control-height ramp. It is also the one place {@link Strokes#MIN_HIT_TARGET} binds: at the
 * densest step the natural height falls below the floor, because the ring needs absolute room
 * the knob ramp does not predict.
 *
 * <p>The preferred <em>length</em> is free: 220 at every step, equal to
 * {@code ProgressBar}'s so the two stack in a settings panel without a ragged column. Only
 * the thickness moves with the step.
 */
public class Slider extends Widget {

    /** Free axis: identical at every step, and deliberately equal to {@code ProgressBar}'s. */
    private static final float PREFERRED_WIDTH = 220;

    private final float min;
    private final float max;
    private float value;
    private float step; // 0 = continuous
    private Consumer<Float> onChange = v -> {
    };
    private Consumer<Float> onCommit = v -> {
    };

    private final Transition hover =
            new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);
    private boolean dragging;
    private boolean pointerInside;

    /** A slider over {@code [min, max]}, starting at {@code min}. */
    public Slider(float min, float max) {
        if (!(max > min)) {
            throw new IllegalArgumentException("max (" + max + ") must be > min (" + min + ")");
        }
        this.min = min;
        this.max = max;
        this.value = min;
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    // ------------------------------------------------------------------- API

    /** Sets the value programmatically (clamped + snapped to the step); does not fire {@link #onChange}. */
    public Slider setValue(float newValue) {
        Ui.checkUiThread();
        apply(newValue, false);
        return this;
    }

    /** The current value, always within {@code [min, max]}. */
    public float value() {
        return value;
    }

    /** Lower bound, inclusive. */
    public float min() {
        return min;
    }

    /** Upper bound, inclusive. */
    public float max() {
        return max;
    }

    /** Sets the discrete increment ({@code 0} = continuous). Re-snaps the current value. */
    public Slider setStep(float newStep) {
        Ui.checkUiThread();
        this.step = Math.max(0, newStep);
        apply(value, false);
        return this;
    }

    /** Called with the new value on user changes only, not on {@link #setValue}. */
    public Slider onChange(Consumer<Float> listener) {
        Ui.checkUiThread();
        this.onChange = java.util.Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Called with the value the user settled on: once when a drag ends, and after each change made
     * with the keyboard. {@link #onChange} fires continuously while a drag is in progress and this
     * fires once at the end of it, which is the difference between a preview and a decision.
     *
     * <p>For anything a value change starts that should not be started per pixel: a decode, a
     * request, a recomputation. A control bound only to {@code onChange} does that work for every
     * position the thumb passes through; one bound to both can show something cheap on the way and
     * do the real work here.
     *
     * <p>Fires on a drag that ends where it began, because the user still chose that value, and
     * does not fire for {@link #setValue}, which is not a user change at all.
     */
    public Slider onCommit(Consumer<Float> listener) {
        Ui.checkUiThread();
        this.onCommit = java.util.Objects.requireNonNull(listener, "listener");
        return this;
    }

    // --------------------------------------------------------------- geometry

    // Every geometry helper takes the resolved row instead of resolving its own. Measure, paint
    // and the pointer path all run through trackLeft/trackWidth, so two resolutions that
    // disagreed inside one component would map a click to a different value than the frame it
    // was aimed at: invisible at MEDIUM, wrong everywhere else.

    /**
     * Horizontal room reserved at each end so the hovered knob and its ring stay inside the box:
     * {@code knobHover + FOCUS_GAP_SLIDER + the ring's own stroke} (the stroke counts at
     * MEDIUM too). This is
     * deliberately <b>not</b> {@code height() / 2}: at XSMALL and SMALL the 24 pt hit floor
     * makes the box taller than the knob needs, and insetting by half the height there would
     * shorten the track for no reason.
     */
    private float trackLeft(SizeTokens t) {
        return t.sliderPad();
    }

    private float trackWidth(SizeTokens t) {
        return Math.max(1, width() - 2 * t.sliderPad());
    }

    private float fraction() {
        return (value - min) / (max - min);
    }

    private float thumbX(SizeTokens t) {
        return trackLeft(t) + fraction() * trackWidth(t);
    }

    /** Clamps and snaps a raw value; applies + fires {@link #onChange} (when {@code fromUser}) only if it changed. */
    private void apply(float raw, boolean fromUser) {
        float clamped = Math.max(min, Math.min(max, raw));
        float snapped = clamped;
        if (step > 0) {
            snapped = Math.max(min, Math.min(max, min + Math.round((clamped - min) / step) * step));
            // The exact ends are always valid stops even when the step grid does
            // not divide the range: Home/End and drag-to-edge must reach them.
            if (clamped >= max) {
                snapped = max;
            } else if (clamped <= min) {
                snapped = min;
            }
        }
        if (snapped == value) {
            return;
        }
        value = snapped;
        invalidate();
        if (fromUser) {
            onChange.accept(value);
        }
    }

    private void applyFromX(SizeTokens t, float localX, boolean fromUser) {
        float frac = Math.max(0, Math.min(1, (localX - trackLeft(t)) / trackWidth(t)));
        // Pass the exact bounds at the extremes so dragging to an edge reaches min/max.
        float raw = frac >= 1f ? max : frac <= 0f ? min : min + frac * (max - min);
        apply(raw, fromUser);
    }

    private float keyStep() {
        return step > 0 ? step : (max - min) / 100f;
    }

    private float pageStep() {
        return step > 0 ? step * 10 : (max - min) / 10f;
    }

    // ------------------------------------------------------------------ paint

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        // sliderHeight() is max(MIN_HIT_TARGET, 2 * sliderPad()); the clamp genuinely binds at
        // XSMALL (21 -> 24). No baselineOffset() override: a Slider carries no text, so the base
        // class's "align on the bottom edge" is the correct BASELINE reference.
        return constraints.constrain(PREFERRED_WIDTH, t.sliderHeight());
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        boolean enabled = isEnabled();
        float cy = height() / 2;
        float left = trackLeft(t);
        float tw = trackWidth(t);
        float thumbX = thumbX(t);
        // Parity rule: the rail is centred, so (height - rail) must be EVEN or its
        // two long edges land on half-pixels and the toolkit's longest straight run renders as
        // two rows of ~50% grey: fills are not pixel-snapped, only strokes are (backend
        // Snapping). The token row is chosen to hold that at all five steps: 24-4, 24-4, 28-6,
        // 33-7, 38-8. The knob escapes the parity rule: a perimeter is antialiased
        // regardless, so there is no grid for a circle to land on.
        float rail = t.sliderRail();
        float trackTop = cy - rail / 2;

        canvas.fillRoundRect(left, trackTop, tw, rail, rail / 2, theme.surfaceRaised);
        Color fillColor = enabled ? theme.primary : theme.disabledFill;
        if (thumbX > left) {
            canvas.fillRoundRect(left, trackTop, thumbX - left, rail, rail / 2, fillColor);
        }

        float focus = focusFade.value();
        // Both interpolation endpoints come from the SAME resolved row, read here every frame:
        // hoisting either into a field would freeze it at whatever step the widget had when it
        // was constructed, which is the process default, since a widget has no parent then.
        float rest = t.sliderKnob();
        float radius = rest + (t.sliderKnobHover() - rest) * hover.value();
        if (focus > 0.001f) {
            // Crisp ring in the theme's focus color (alpha fades it in): same
            // treatment as Button/Checkbox, not a washed-out halo. The gap and the weight are
            // pixel-locked: a focus indicator is specified in absolute thickness.
            canvas.drawCircle(thumbX, cy, radius + Strokes.FOCUS_GAP_SLIDER, Strokes.FOCUS_RING,
                    theme.focusRing.withAlpha(focus));
        }
        Color knob = enabled ? theme.primary : theme.disabledFill;
        canvas.fillCircle(thumbX, cy, radius, knob);
        canvas.drawCircle(thumbX, cy, radius, Strokes.INDICATOR_BORDER,
                enabled ? theme.primaryPressed : theme.disabledText);
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void onMouseEvent(MouseEvent event) {
        // One resolution for the whole event, threaded into applyFromX. The pointer path must
        // agree with the frame the user aimed at, and that only holds if both read one row.
        SizeTokens t = Theme.current().tokensFor(this);
        switch (event.type()) {
            case ENTER -> {
                pointerInside = true;
                hover.to(1);
            }
            case EXIT -> {
                pointerInside = false;
                if (!dragging) {
                    hover.to(0);
                }
            }
            case PRESS -> {
                if (event.button() == Keys.MOUSE_LEFT && isEnabled()) {
                    dragging = true;
                    hover.to(1);
                    applyFromX(t, sceneToLocalX(event.x()), true);
                    event.consume();
                }
            }
            case DRAG -> {
                if (dragging) {
                    applyFromX(t, sceneToLocalX(event.x()), true);
                    event.consume();
                }
            }
            case RELEASE -> {
                if (dragging) {
                    dragging = false;
                    if (!pointerInside) {
                        hover.to(0);
                    }
                    // Whatever the value is now, including unchanged: the user let go here, so
                    // here is what they chose.
                    onCommit.accept(value);
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed() || !isEnabled()) {
            return;
        }
        boolean handled = true;
        switch (event.key()) {
            case Keys.LEFT, Keys.DOWN -> apply(value - keyStep(), true);
            case Keys.RIGHT, Keys.UP -> apply(value + keyStep(), true);
            case Keys.PAGE_DOWN -> apply(value - pageStep(), true);
            case Keys.PAGE_UP -> apply(value + pageStep(), true);
            case Keys.HOME -> apply(min, true);
            case Keys.END -> apply(max, true);
            default -> handled = false;
        }
        if (handled) {
            // A key press is a whole gesture: there is no drag to end, so the change and the
            // decision are the same moment.
            onCommit.accept(value);
            event.consume();
        }
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
    }
}

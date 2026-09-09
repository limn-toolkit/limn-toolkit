package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.lang.Checks;
import limn.scene.Change;
import limn.scene.FloatConsumer;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;


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
 * volume.onChange(v -> audio.setGain(v / 100f));          // the user moved it
 * volume.observeChanges((w, c) -> readout.setText(...));  // anything moved it
 * }</pre>
 *
 * <p>{@link #onChange} and {@link #onCommit} are the application's response to the <em>user</em>
 * operating the slider; {@link #setValue} and {@link #setStep} reach neither. Every change,
 * whatever moved it, reaches a {@linkplain #observeChanges watcher} as {@code VALUE}, and a
 * drag's release or a key press as {@code COMMITTED}.
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
 *
 * <p>The value axis follows the {@linkplain Widget#layoutDirection() resolved layout direction}: reading right to left, {@code min} is at the right end of the track, the fill grows
 * leftwards from it, and Left/Right nudge the value the way the thumb visibly moves. Up/Down are a
 * vertical pair and keep their meaning in both directions, and Home/End/PageUp/PageDown name a
 * value rather than a side, so they never mirror either. The track rectangle itself does not move:
 * the pad is reserved at both ends, so only the meaning of each end changes.
 */
public class Slider extends Widget {

    /** Free axis: identical at every step, and deliberately equal to {@code ProgressBar}'s. */
    private static final float PREFERRED_WIDTH = 220;

    private final float min;
    private final float max;
    private float value;
    private float step; // 0 = continuous
    private FloatConsumer onChange;
    private FloatConsumer onCommit;

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

    /**
     * Sets the value (clamped + snapped to the step). Announces {@code VALUE}/{@code CODE} when
     * it moved, and reaches no handler: a caller's write is not the user operating the slider.
     */
    public Slider setValue(float newValue) {
        Ui.checkUiThread();
        apply(newValue, Change.Origin.CODE);
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

    /**
     * Sets the discrete increment ({@code 0} = continuous). Re-snaps the current value, and says
     * so: when the re-snap moves it, {@code VALUE}/{@code ADJUSTMENT} is announced first, and
     * then the step's own {@code RANGE}/{@code CODE} last, as the aspect the call names.
     *
     * <p>The step is published to an assistive technology as the grid a set is snapped onto, so
     * a change to it is an accessible fact even when the value already sits on the new grid and
     * nothing repaints; that case is flagged to the tree directly rather than through damage the
     * picture does not have.
     */
    public Slider setStep(float newStep) {
        Ui.checkUiThread();
        float clamped = Math.max(0, newStep);
        if (clamped == step) {
            return this;
        }
        step = clamped;
        invalidateAccessible();
        apply(value, Change.Origin.ADJUSTMENT);
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
        return this;
    }

    /**
     * The application's response to the user moving the slider: called with the new value on a
     * drag, a key or an assistive technology's step, and never for {@link #setValue}. To hear
     * every move whatever caused it, {@linkplain #observeChanges watch} the slider instead.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this slider
     * @throws IllegalStateException if a handler is already registered
     */
    public Slider onChange(FloatConsumer listener) {
        Ui.checkUiThread();
        this.onChange = Checks.handlerSlot(onChange, listener, "Slider.onChange");
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
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this slider
     * @throws IllegalStateException if a handler is already registered
     */
    public Slider onCommit(FloatConsumer listener) {
        Ui.checkUiThread();
        this.onCommit = Checks.handlerSlot(onCommit, listener, "Slider.onCommit");
        return this;
    }

    /**
     * The handler half of the channel: the base class calls this after every watcher, for a
     * {@code USER} origin only, and the slot is read here so the ordering stays in the base.
     */
    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        switch (aspect) {
            case VALUE -> {
                if (onChange != null) {
                    onChange.accept(value);
                }
            }
            case COMMITTED -> {
                if (onCommit != null) {
                    onCommit.accept(value);
                }
            }
            default -> super.handleUserChange(aspect);
        }
    }

    // --------------------------------------------------------------- geometry

    // Every geometry helper takes the resolved row instead of resolving its own. Measure, paint
    // and the pointer path all run through trackLeft/trackWidth, so two resolutions that
    // disagreed inside one component would map a click to a different value than the frame it
    // was aimed at: invisible at MEDIUM, wrong everywhere else.
    //
    // The resolved direction is threaded the same way and for the same reason: thumbX and
    // applyFromX are inverses of one another, so each pass resolves it once and hands it to both
    // rather than letting either read it for itself.

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

    /**
     * The x of the thumb's centre, measured from the track's leading end: the end {@code min}
     * sits at, which is the left one reading left to right and the right one reading right to
     * left. The track rectangle is the same either way; only which end fraction 0 means flips.
     */
    private float thumbX(SizeTokens t, boolean rtl) {
        float along = rtl ? 1 - fraction() : fraction();
        return trackLeft(t) + along * trackWidth(t);
    }

    /**
     * The one seam every value change goes through: clamps and snaps a raw value, and announces
     * {@code VALUE} with {@code origin} only if it moved. A drag, a key and an assistive
     * technology's step pass {@code USER}; {@link #setValue} passes {@code CODE}; the re-snap
     * inside {@link #setStep} passes {@code ADJUSTMENT}.
     */
    private void apply(float raw, Change.Origin origin) {
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
        notifyChange(Change.of(Change.Aspect.VALUE, origin));
    }

    /**
     * The user let go, or a key press ended: whatever the value is now, including unchanged,
     * here is what they chose. Announces {@code COMMITTED}/{@code USER} once per gesture.
     */
    private void commit() {
        notifyChange(Change.of(Change.Aspect.COMMITTED, Change.Origin.USER));
    }

    /**
     * Maps a physical widget-local x onto the value: the exact inverse of {@link #thumbX}, and
     * the only place the pointer's side of the box becomes a fraction of the range. The caller
     * hands over the untouched pointer coordinate; reflecting it there as well would flip twice
     * and land on the value the user aimed away from.
     */
    private void applyFromX(SizeTokens t, boolean rtl, float localX) {
        float along = rtl ? trackLeft(t) + trackWidth(t) - localX : localX - trackLeft(t);
        float frac = Math.max(0, Math.min(1, along / trackWidth(t)));
        // Pass the exact bounds at the extremes so dragging to an edge reaches min/max.
        float raw = frac >= 1f ? max : frac <= 0f ? min : min + frac * (max - min);
        apply(raw, Change.Origin.USER);
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
        // One resolution for the whole frame, exactly as the token row is resolved once here.
        boolean rtl = isRightToLeft();
        float cy = height() / 2;
        float left = trackLeft(t);
        float tw = trackWidth(t);
        float thumbX = thumbX(t, rtl);
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
        // The fill is anchored at the end min sits at and stops under the thumb, so reading right
        // to left it starts at the thumb and runs to the track's right edge. The rail underneath
        // is the whole symmetric track and does not move.
        float fillX = rtl ? thumbX : left;
        float fillWidth = rtl ? left + tw - thumbX : thumbX - left;
        if (fillWidth > 0) {
            canvas.fillRoundRect(fillX, trackTop, fillWidth, rail, rail / 2, fillColor);
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
        // One resolution of each for the whole event, threaded into applyFromX. The pointer path
        // must agree with the frame the user aimed at, and that only holds if both read one row
        // and one direction.
        SizeTokens t = Theme.current().tokensFor(this);
        boolean rtl = isRightToLeft();
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
                    applyFromX(t, rtl, sceneToLocalX(event.x()));
                    event.consume();
                }
            }
            case DRAG -> {
                if (dragging) {
                    applyFromX(t, rtl, sceneToLocalX(event.x()));
                    event.consume();
                }
            }
            case RELEASE -> {
                if (dragging) {
                    dragging = false;
                    if (!pointerInside) {
                        hover.to(0);
                    }
                    commit();
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
        // Resolved once for this key press, like the token row in the pointer path.
        boolean rtl = isRightToLeft();
        boolean handled = true;
        // Left and Right name a side of the track, so they follow the direction; Up and Down name
        // a vertical side, which mirroring does not touch, and they shared an arm with the
        // horizontal pair only because the two agreed reading left to right. Home, End, PageUp
        // and PageDown name a value rather than a side and are untouched: Home is min in both
        // directions, and a page is a magnitude with no side at all.
        float arrow = rtl ? -keyStep() : keyStep();
        switch (event.key()) {
            case Keys.LEFT -> apply(value - arrow, Change.Origin.USER);
            case Keys.RIGHT -> apply(value + arrow, Change.Origin.USER);
            case Keys.DOWN -> apply(value - keyStep(), Change.Origin.USER);
            case Keys.UP -> apply(value + keyStep(), Change.Origin.USER);
            case Keys.PAGE_DOWN -> apply(value - pageStep(), Change.Origin.USER);
            case Keys.PAGE_UP -> apply(value + pageStep(), Change.Origin.USER);
            case Keys.HOME -> apply(min, Change.Origin.USER);
            case Keys.END -> apply(max, Change.Origin.USER);
            default -> handled = false;
        }
        if (handled) {
            // A key press is a whole gesture: there is no drag to end, so the change and the
            // decision are the same moment.
            commit();
            event.consume();
        }
    }

    // ---------------------------------------------------------------- accessibility

    /**
     * What this slider is to an assistive technology: one {@link Accessible.Role#SLIDER} node,
     * horizontal because the class has no other axis, carrying a writable value facet read from
     * the model and offering {@link Accessible.Action#INCREMENT} and
     * {@link Accessible.Action#DECREMENT}, and nothing else.
     *
     * <p>No name is declared, on purpose. The widget holds no string and formats none, so it has
     * nothing to hand over: a tooltip names it through the walk's free default, an application's
     * {@code setAccessibleName} wins over that and turns the tooltip into the description, and a
     * caption beside the track is a relation the application declares rather than one guessed
     * here. There is no value text either, and so no cache and no witness: the number is the
     * whole of it, and a read-out is the application's to render beside the control.
     *
     * <p>The facet is the {@code value} field, already clamped and snapped, with the bounds and
     * the {@code step} field as set: {@code 0} when the slider is continuous, because the facet's
     * step is the grid a set is snapped onto and a continuous slider accepts any value in range.
     * The amount the keyboard nudges by is not the same number on a continuous slider and is not
     * published; it is what the two verbs move by. The facet is writable, so
     * {@code SET_VALUE} is advertised by its presence and never appears in the action list.
     *
     * <p>Neither fade, the drag flag nor the pointer's whereabouts is read here. The hover and
     * focus transitions damage this widget on every frame they run for, and a hook that published
     * either would make the difference find a change on each of those frames; publishing the
     * model means they walk, bounded and without allocating, and publish nothing. A drag moves
     * the model and republishes once per frame, which is what a drag is. The box, the focus and
     * scroll-into-view verbs and the enabled, visible, showing, focusable and focused states are
     * the walk's.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.slider(Accessible.State.HORIZONTAL, value, min, max, step);
    }

    /**
     * Performs a change an assistive technology asked for exactly as a key press does: through
     * the value seam at {@code USER}, so {@link #onChange} fires when the value moves, and then
     * a commit whether it moved or not, because a request from a reader is a whole gesture the
     * way a key press is and the change and the decision are the same moment. Never through
     * {@link #setValue}, which is a caller's write: an application that starts something on
     * commit, as the toolkit's own media transport does, would otherwise never hear that the
     * user chose a value.
     *
     * <p>{@code INCREMENT} and {@code DECREMENT} move by the step, or by one percent of the range
     * when the slider is continuous, in the sense Up and Down have: a direction of the value and
     * not a side of the track, so they do not mirror when the layout reads right to left, where
     * Left and Right do. {@code SET_VALUE} takes an {@link Accessible.Argument.OfValue} and hands
     * it to the same clamp and snap a drag reaches; a value that is not a finite number is
     * refused before it gets there, because the clamp would pass it through untouched. Any other
     * verb is refused, and so is every verb while this widget is disabled; the scene has already
     * re-checked the ancestors, showing and modality before the call arrives, and this guard is
     * the one the key path keeps for itself.
     *
     * <p>What a reader hears is the value change the next publish's difference produces on this
     * node. No invocation event is raised for a value verb, on any platform.
     *
     * @param action what was asked
     * @param arg    the value for {@code SET_VALUE}; ignored by the two steps
     * @return whether the request ran, which at the ends of the range can be a commit of the
     *         value already held
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (!isEnabled()) {
            return false;
        }
        switch (action) {
            case INCREMENT -> apply(value + keyStep(), Change.Origin.USER);
            case DECREMENT -> apply(value - keyStep(), Change.Origin.USER);
            case SET_VALUE -> {
                double asked = Accessible.Argument.finiteValueOf(arg);
                if (Double.isNaN(asked)) {
                    return false;
                }
                apply((float) asked, Change.Origin.USER);
            }
            default -> {
                return false;
            }
        }
        commit();
        return true;
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

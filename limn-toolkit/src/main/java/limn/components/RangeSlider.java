package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.internal.a11y.ValueAccessibility;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.jspecify.annotations.Nullable;

/**
 * A horizontal slider of two thumbs that selects a range in {@code [min, max]}: a lower value and
 * an upper one over the same track, the span between them filled. Built as {@link Slider} is: the
 * same track, knobs, steps, hover and focus ring, and the same direction rules reading right to
 * left.
 *
 * <pre>{@code
 * RangeSlider price = new RangeSlider(0, 1000).setStep(10).setMinimumGap(50).setValues(200, 600);
 * price.onChange((low, high) -> filter.setPrice(low, high));
 * }</pre>
 *
 * <p><b>A thumb stops at the other,</b> or at the {@linkplain #setMinimumGap minimum gap} the
 * application sets: the lower can never pass above {@code upper - gap}, nor the upper below
 * {@code lower + gap}. A press on the track moves the nearer thumb there and drags it; with the two
 * on one spot, the one the pointer goes towards.
 *
 * <p><b>Each thumb is a control of its own</b> for the keyboard and for an assistive technology:
 * two Tab stops, the lower first, each moved by the arrows, Page Up and Page Down, Home and End
 * within what the other leaves it, and each a slider node named "Minimum" or "Maximum" in the
 * interface's language, whose bounds are the range it may reach now.
 *
 * <p>{@link #onChange} and {@link #onCommit} are the application's response to the user;
 * {@link #setValues} reaches neither. Every change, whatever moved it, reaches a
 * {@linkplain #observeChanges watcher} as {@code VALUE}, and the end of a drag or a key press as
 * {@code COMMITTED}.
 */
public final class RangeSlider extends Widget<RangeSlider> {

    /** What hears the range the user set. */
    @FunctionalInterface
    public interface RangeListener {
        /**
         * @param lower the lower value
         * @param upper the upper value
         */
        void accept(float lower, float upper);
    }

    /** The same length {@link Slider} takes, so the two stack in a settings panel. */
    private static final float PREFERRED_WIDTH = 220;

    private final float min;
    private final float max;
    private float lower;
    private float upper;
    private float step;
    private float gap;
    private @Nullable RangeListener onChange;
    private @Nullable RangeListener onCommit;

    private final Thumb lowerThumb = new Thumb(true);
    private final Thumb upperThumb = new Thumb(false);
    /** The thumb a pointer press took, until it is released; null otherwise. */
    private @Nullable Thumb dragged;
    /** A press on both thumbs at one spot, waiting for the pointer to move to say which. */
    private float undecidedX = Float.NaN;

    /**
     * A slider over {@code [min, max]}, its range the whole of it.
     *
     * @throws IllegalArgumentException if {@code max} is not above {@code min}
     */
    public RangeSlider(float min, float max) {
        if (!(max > min) || Float.isInfinite(max - min)) {
            throw new IllegalArgumentException("max (" + max + ") must be > min (" + min + ")");
        }
        this.min = min;
        this.max = max;
        this.lower = min;
        this.upper = max;
        setCursor(Cursor.POINTER);
        add(lowerThumb);
        add(upperThumb);
    }

    // ------------------------------------------------------------------- API

    /**
     * Sets both values, clamped to the bounds, snapped to the step and held the minimum gap apart.
     * Values closer than the gap move apart, the upper up first and the lower down when the upper
     * meets the maximum, each to the next step past the gap, so a value is always one the steps
     * reach. Announces {@code VALUE}/{@code CODE} when either moved and reaches no handler.
     *
     * @throws IllegalArgumentException if {@code newLower} is above {@code newUpper}, or either
     *         is not a number
     */
    public RangeSlider setValues(float newLower, float newUpper) {
        Ui.checkUiThread();
        if (Float.isNaN(newLower) || Float.isNaN(newUpper)) {
            throw new IllegalArgumentException("a value is a number: " + newLower + ", " + newUpper);
        }
        if (newLower > newUpper) {
            throw new IllegalArgumentException("lower (" + newLower + ") is above upper (" + newUpper + ")");
        }
        moveApart(snap(newLower), snap(newUpper), Change.Origin.CODE);
        return this;
    }

    /** @return the lower value */
    public float lower() {
        return lower;
    }

    /** @return the upper value */
    public float upper() {
        return upper;
    }

    /** @return the lower bound, inclusive */
    public float min() {
        return min;
    }

    /** @return the upper bound, inclusive */
    public float max() {
        return max;
    }

    /**
     * Sets the discrete increment ({@code 0} = continuous) and snaps both values onto it.
     *
     * @throws IllegalArgumentException if it is negative or not a number
     */
    public RangeSlider setStep(float newStep) {
        Ui.checkUiThread();
        if (!(newStep >= 0) || Float.isInfinite(newStep)) {
            throw new IllegalArgumentException("a step is 0 or more: " + newStep);
        }
        step = newStep;
        invalidateAccessible();
        moveApart(snap(lower), snap(upper), Change.Origin.ADJUSTMENT);
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
        return this;
    }

    /** @return the grid the values are snapped onto, or {@code 0} for a continuous slider */
    public float step() {
        return step;
    }

    /**
     * Sets the least distance between the two values, 0 unless set: a range of at least a day, a
     * price band of at least ten. The values are moved apart if they are closer.
     *
     * @throws IllegalArgumentException if it is negative, not a number, or larger than the bounds
     */
    public RangeSlider setMinimumGap(float newGap) {
        Ui.checkUiThread();
        if (!(newGap >= 0) || newGap > max - min) {
            throw new IllegalArgumentException("a gap is 0 to " + (max - min) + ": " + newGap);
        }
        gap = newGap;
        invalidateAccessible();
        moveApart(lower, upper, Change.Origin.ADJUSTMENT);
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
        return this;
    }

    /** @return the least distance between the two values */
    public float minimumGap() {
        return gap;
    }

    /**
     * The application's response to the user moving either thumb, with both values: on a drag, a
     * key or an assistive technology's step, and never for {@link #setValues}.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this slider
     * @throws IllegalStateException if a handler is already registered
     */
    public RangeSlider onChange(@Nullable RangeListener listener) {
        Ui.checkUiThread();
        this.onChange = Checks.handlerSlot(onChange, listener, "RangeSlider.onChange");
        return this;
    }

    /**
     * Called with the range the user settled on: once when a drag ends, and after each key press.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this slider
     * @throws IllegalStateException if a handler is already registered
     */
    public RangeSlider onCommit(@Nullable RangeListener listener) {
        Ui.checkUiThread();
        this.onCommit = Checks.handlerSlot(onCommit, listener, "RangeSlider.onCommit");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        switch (aspect) {
            case VALUE -> {
                if (onChange != null) {
                    onChange.accept(lower, upper);
                }
            }
            case COMMITTED -> {
                if (onCommit != null) {
                    onCommit.accept(lower, upper);
                }
            }
            default -> super.handleUserChange(aspect);
        }
    }

    // --------------------------------------------------------------- model

    private float snap(float raw) {
        float clamped = Math.max(min, Math.min(max, raw));
        if (step <= 0 || clamped >= max || clamped <= min) {
            return clamped;
        }
        return Math.max(min, Math.min(max, min + Math.round((clamped - min) / step) * step));
    }

    /**
     * The largest value on the step's grid at or below {@code raw}, within the bounds: where a
     * thumb the gap holds below the other stops, so it never lands between two steps. Within a
     * hair of a step counts as on it, so a float sum that came out a little past one stays there.
     */
    private float snapDown(float raw) {
        float clamped = Math.max(min, Math.min(max, raw));
        if (step <= 0 || clamped >= max || clamped <= min) {
            return clamped;
        }
        return Math.max(min, min + (float) Math.floor((clamped - min) / step + 1e-4f) * step);
    }

    /** The smallest value on the grid at or above {@code raw}, within the bounds: see {@link #snapDown}. */
    private float snapUp(float raw) {
        float clamped = Math.max(min, Math.min(max, raw));
        if (step <= 0 || clamped >= max || clamped <= min) {
            return clamped;
        }
        return Math.min(max, min + (float) Math.ceil((clamped - min) / step - 1e-4f) * step);
    }

    /**
     * Moves to two snapped values held the minimum gap apart: the upper goes up to the next step
     * past the gap, and the lower down from it when the upper met the maximum.
     */
    private void moveApart(float l, float u, Change.Origin origin) {
        if (u - l < gap) {
            u = snapUp(l + gap);
            if (u - l < gap) {
                l = snapDown(u - gap);
            }
        }
        move(l, u, origin);
    }

    /**
     * The one seam every change goes through; announces {@code VALUE} with {@code origin} when either
     * moved.
     *
     * @return whether either moved
     */
    private boolean move(float newLower, float newUpper, Change.Origin origin) {
        if (newLower == lower && newUpper == upper) {
            return false;
        }
        lower = newLower;
        upper = newUpper;
        // The thumbs are moved where they now belong, and only this slider repaints: a layout
        // pass would repaint the window for what a drag changes in one strip of it.
        if (width() > 0) {
            placeThumbs();
        }
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, origin));
        return true;
    }

    /**
     * Moves one thumb towards {@code raw}, stopping at the other less the gap, on a step.
     *
     * @return whether it moved
     */
    private boolean moveThumb(boolean isLower, float raw, Change.Origin origin) {
        float value = snap(raw);
        if (isLower) {
            return move(Math.min(value, snapDown(upper - gap)), upper, origin);
        }
        return move(lower, Math.max(value, snapUp(lower + gap)), origin);
    }

    private void commit() {
        notifyChange(Change.of(Change.Aspect.COMMITTED, Change.Origin.USER));
    }

    private float keyStep() {
        return step > 0 ? step : (max - min) / 100f;
    }

    private float pageStep() {
        return step > 0 ? step * 10 : (max - min) / 10f;
    }

    // --------------------------------------------------------------- geometry

    private float trackLeft(SizeTokens t) {
        return t.sliderPad();
    }

    private float trackWidth(SizeTokens t) {
        return Math.max(1, width() - 2 * t.sliderPad());
    }

    /** The x of a value's thumb centre, its end following the direction as {@link Slider}'s does. */
    private float xOf(float value, SizeTokens t, boolean rtl) {
        float fraction = (value - min) / (max - min);
        return trackLeft(t) + (rtl ? 1 - fraction : fraction) * trackWidth(t);
    }

    private float valueAt(float localX, SizeTokens t, boolean rtl) {
        float along = rtl ? trackLeft(t) + trackWidth(t) - localX : localX - trackLeft(t);
        float fraction = Math.max(0, Math.min(1, along / trackWidth(t)));
        return fraction >= 1f ? max : fraction <= 0f ? min : min + fraction * (max - min);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        return constraints.constrain(PREFERRED_WIDTH, t.sliderHeight());
    }

    @Override
    protected void onLayout() {
        placeThumbs();
    }

    private void placeThumbs() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        boolean rtl = isRightToLeft();
        // Each thumb a square of the slider's height centred on its value: the box its knob, its
        // hover growth and its focus ring are drawn in, and the box a reader is told it occupies.
        float side = height();
        for (Thumb thumb : new Thumb[] {lowerThumb, upperThumb}) {
            float x = xOf(thumb.value(), t, rtl) - side / 2;
            thumb.measure(Constraints.tight(side, side));
            thumb.layoutBox(x, 0, side, side);
        }
    }

    @Override
    protected float paintOutset() {
        return height() / 2; // a thumb's box overhangs the ends by half its side
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        SizeTokens t = theme.tokensFor(this);
        boolean rtl = isRightToLeft();
        float cy = height() / 2;
        float rail = t.sliderRail();
        float top = cy - rail / 2;
        canvas.fillRoundRect(trackLeft(t), top, trackWidth(t), rail, rail / 2, theme.track());
        float a = xOf(lower, t, rtl);
        float b = xOf(upper, t, rtl);
        float from = Math.min(a, b);
        float span = Math.abs(b - a);
        if (span > 0) {
            canvas.fillRoundRect(from, top, span, rail, rail / 2, isEnabled() ? theme.primary() : theme.disabledText());
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void onMouseEvent(MouseEvent event) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        boolean rtl = isRightToLeft();
        float x = sceneToLocalX(event.x());
        switch (event.type()) {
            case ENTER, MOVE -> {
                if (dragged == null) {
                    // The thumb under the pointer grows, as Slider's does; the thumbs take no
                    // pointer of their own.
                    float reach = t.sliderKnobHover();
                    boolean onLower = Math.abs(x - xOf(lower, t, rtl)) <= reach;
                    boolean onUpper = !onLower && Math.abs(x - xOf(upper, t, rtl)) <= reach;
                    lowerThumb.pressed(onLower);
                    upperThumb.pressed(onUpper);
                }
            }
            case EXIT -> {
                if (dragged == null) {
                    lowerThumb.pressed(false);
                    upperThumb.pressed(false);
                }
            }
            case PRESS -> {
                if (event.button() != Keys.MOUSE_LEFT || !isEnabled()) {
                    return;
                }
                float dl = Math.abs(x - xOf(lower, t, rtl));
                float du = Math.abs(x - xOf(upper, t, rtl));
                if (dl == du && lower == upper) {
                    undecidedX = x; // both under the pointer: the move decides
                    dragged = null;
                } else {
                    dragged = dl <= du ? lowerThumb : upperThumb;
                    if (dl == du) {
                        // Equally far from two thumbs apart: the one on the side pressed.
                        dragged = valueAt(x, t, rtl) < lower ? lowerThumb : upperThumb;
                    }
                    dragged.pressed(true);
                    moveThumb(dragged.isLower, valueAt(x, t, rtl), Change.Origin.USER);
                }
                event.consume();
            }
            case DRAG -> {
                if (dragged == null && !Float.isNaN(undecidedX)) {
                    if (Math.abs(x - undecidedX) < 1) {
                        return;
                    }
                    boolean towardsMin = rtl ? x > undecidedX : x < undecidedX;
                    dragged = towardsMin ? lowerThumb : upperThumb;
                    dragged.pressed(true);
                    undecidedX = Float.NaN;
                }
                if (dragged != null) {
                    moveThumb(dragged.isLower, valueAt(x, t, rtl), Change.Origin.USER);
                    event.consume();
                }
            }
            case RELEASE -> {
                Thumb released = dragged;
                if (released != null || !Float.isNaN(undecidedX)) {
                    if (released != null) {
                        released.pressed(false);
                        released.requestFocus();
                    } else {
                        lowerThumb.requestFocus();
                    }
                    dragged = null;
                    undecidedX = Float.NaN;
                    commit();
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onAccessibility(Accessibility a) {
        // A group holding the two slider nodes, each a control of its own.
        a.role(Accessible.Role.GROUP);
    }

    @Override
    protected boolean paintsDecoration() {
        return true; // the track and its fill say nothing a reader needs: the thumbs say it all
    }

    // ------------------------------------------------------------------ thumbs

    /** One thumb: a Tab stop, a slider node, and the knob drawn where its value is. */
    private final class Thumb extends Widget<Thumb> {

        final boolean isLower;
        private final Transition hover =
                new Transition(this).duration(Theme.of(this).animHover).easing(Theme.of(this).animEasing);
        private final Transition focusFade =
                new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);

        Thumb(boolean isLower) {
            this.isLower = isLower;
            setFocusable(true);
        }

        float value() {
            return isLower ? lower : upper;
        }

        /** What the other thumb leaves it: its least and its greatest value now. */
        float least() {
            return isLower ? min : snapUp(lower + gap);
        }

        float greatest() {
            return isLower ? snapDown(upper - gap) : max;
        }

        void pressed(boolean down) {
            hover.to(down ? 1 : 0);
        }

        /** Transparent to the pointer: the slider takes every press, to give it to the nearer thumb. */
        @Override
        public @Nullable Widget<?> hitTest(float localX, float localY) {
            return null;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(this);
            boolean enabled = RangeSlider.this.isEnabled();
            float cx = width() / 2;
            float cy = height() / 2;
            float rest = t.sliderKnob();
            float radius = rest + (t.sliderKnobHover() - rest) * hover.value();
            float focus = focusFade.value();
            if (focus > 0.001f) {
                canvas.drawCircle(cx, cy, radius + Strokes.FOCUS_GAP_SLIDER, Strokes.FOCUS_RING,
                        theme.focusRing().withAlpha(focus));
            }
            Color knob = enabled ? theme.primary() : theme.disabledFill();
            canvas.fillCircle(cx, cy, radius, knob);
            canvas.drawCircle(cx, cy, radius, Strokes.INDICATOR_BORDER,
                    enabled ? theme.primaryPressed() : theme.disabledText());
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (!event.isPressed() || !RangeSlider.this.isEnabled()) {
                return;
            }
            boolean rtl = isRightToLeft();
            float arrow = rtl ? -keyStep() : keyStep();
            float now = value();
            Float target = switch (event.key()) {
                case Keys.LEFT -> now - arrow;
                case Keys.RIGHT -> now + arrow;
                case Keys.DOWN -> now - keyStep();
                case Keys.UP -> now + keyStep();
                case Keys.PAGE_DOWN -> now - pageStep();
                case Keys.PAGE_UP -> now + pageStep();
                case Keys.HOME -> least();
                case Keys.END -> greatest();
                default -> null;
            };
            if (target != null) {
                // A key at the end of the thumb's travel is still the slider's, and settles
                // nothing new: only a key that moved it commits.
                if (moveThumb(isLower, target, Change.Origin.USER)) {
                    commit();
                }
                event.consume();
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.SLIDER);
            a.name(isLower ? ComponentStrings.RANGE_LOWER : ComponentStrings.RANGE_UPPER, Accessible.NameFrom.CONTENT);
            a.state(Accessible.State.HORIZONTAL);
            ValueAccessibility.describe(a, value(), least(), greatest(), step, false);
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
            float was = value();
            if (!ValueAccessibility.perform(host, action, arg)) {
                return false;
            }
            if (value() != was) {
                commit();
            }
            return true;
        }

        private final ValueAccessibility.Host host = new ValueAccessibility.Host() {
            @Override
            public boolean isEnabled() {
                return RangeSlider.this.isEnabled();
            }

            @Override
            public void step(int direction) {
                moveThumb(isLower, value() + direction * keyStep(), Change.Origin.USER);
            }

            @Override
            public void set(double asked) {
                moveThumb(isLower, (float) asked, Change.Origin.USER);
            }
        };

        @Override
        protected void onFocusGained() {
            focusFade.to(1);
        }

        @Override
        protected void onFocusLost() {
            focusFade.to(0);
        }
    }
}

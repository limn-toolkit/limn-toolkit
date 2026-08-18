package limn.components;

import limn.animation.Transition;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Size;
import limn.scene.Widget;

/**
 * Simple horizontal progress bar. Determinate by default
 * ({@link #setProgress} in {@code [0..1]}); {@link #setIndeterminate} switches
 * to an animated sweeping pill (frame-clock ticker) for unknown-duration work.
 * The determinate fill eases from the previous value to the new one through a
 * shared {@link Transition}. A rounded track and fill, both from the {@link Theme}.
 *
 * <p>The bar follows the {@link ControlSize} resolved on it through exactly <b>one</b> token,
 * {@code progressThickness} (4 / 6 / 8 / 10 / 12): every other quantity it paints already
 * derives from {@link #height()}: the pill radius, the minimum determinate fill, the
 * indeterminate pill length and its travel. The long axis is <em>free</em>: 220 pt at
 * every step, deliberately equal to {@code Slider}'s so a bar and a slider
 * stacked in a form line up, and a parent normally overrides it anyway.
 *
 * <p>{@link #setThickness} and {@link #setPreferredWidth} <b>latch</b>: an explicit value beats
 * the step and survives every later step change, and a negative value ({@link #UNSET}) hands
 * the dimension back to the step.
 */
public class ProgressBar extends Widget {

    /**
     * Marks a dimension as "follow the step", the {@code SizedBox.UNSET} idiom. Both
     * dimensions start here, so a bar that was never explicitly sized tracks its
     * {@link ControlSize}; anything {@code >= 0} is an author's pin and wins.
     */
    public static final float UNSET = -1;

    /**
     * Free axis, not a size token: identical at every step and kept equal to
     * {@code Slider.PREFERRED_WIDTH}. See the class note.
     */
    private static final float DEFAULT_WIDTH = 220;
    /** Fraction of the track the indeterminate pill occupies. */
    private static final float SWEEP_FRACTION = 0.35f;
    private static final double SWEEP_SECONDS = 1.1;

    private float progress;
    private boolean indeterminate;
    private boolean animating;
    private int sweepGeneration; // invalidates a stale ticker after detach (blink-generation idiom)
    private double sweepPhase;
    private float preferredWidth = UNSET;
    private float thickness = UNSET;
    /** The drawn fill fraction, eased toward {@link #progress}. */
    private final Transition fill =
            new Transition(this).duration(Theme.current().animFade).easing(Theme.current().animEasing);

    /** Sets the determinate progress, clamped to {@code [0..1]}; the fill eases to it. */
    public ProgressBar setProgress(float value) {
        Ui.checkUiThread();
        this.progress = Math.max(0, Math.min(1, value));
        if (indeterminate) {
            indeterminate = false;
        }
        fill.to(progress); // eases from the previous value (or snaps when detached)
        return this;
    }

    /** Completion in {@code [0,1]}; meaningless while {@link #isIndeterminate()}. */
    public float progress() {
        return progress;
    }

    /** Whether the bar animates instead of showing a fraction. */
    public boolean isIndeterminate() {
        return indeterminate;
    }

    /** Switches to (or from) the animated indeterminate sweep. */
    public ProgressBar setIndeterminate(boolean value) {
        Ui.checkUiThread();
        if (this.indeterminate == value) {
            return this;
        }
        this.indeterminate = value;
        if (value) {
            startSweep();
        }
        invalidate();
        return this;
    }

    /** Overrides the 220 pt free axis; {@link #UNSET} (any negative value) restores it. */
    public ProgressBar setPreferredWidth(float width) {
        Ui.checkUiThread();
        this.preferredWidth = width;
        markNeedsLayout();
        return this;
    }

    /**
     * Pins the bar's thickness, overriding the step's {@code progressThickness};
     * {@link #UNSET} (any negative value) hands it back to the step. A pinned value latches:
     * a later {@code setControlSize} does not disturb it.
     */
    public ProgressBar setThickness(float value) {
        Ui.checkUiThread();
        this.thickness = value;
        markNeedsLayout();
        return this;
    }

    /** Chaining form of {@link #setControlSize}; {@code setControlSize} is {@code void}. */
    public ProgressBar withControlSize(ControlSize size) {
        setControlSize(size);
        return this;
    }

    /** Package-private: tests assert the sweep advances/pauses without pixels. */
    double sweepPhase() {
        return sweepPhase;
    }

    private void startSweep() {
        // isShowing keeps the ticker from running for a bar in a hidden tab;
        // it re-arms from onPaint when the bar becomes visible again.
        if (animating || scene() == null || !isShowing()) {
            return;
        }
        animating = true;
        int generation = ++sweepGeneration;
        // Wall time: the indeterminate sweep says "work is still happening", and the work it
        // stands for (a load, a request) does not stop because the app paused its scene time.
        scene().addRealTimeTicker(dt -> {
            if (generation != sweepGeneration) {
                return false; // superseded (detached/re-attached): a newer ticker owns the sweep
            }
            if (!indeterminate || !isShowing()) {
                animating = false; // re-armed by onAttached/onPaint when relevant again
                return false;
            }
            sweepPhase = (sweepPhase + dt / SWEEP_SECONDS) % 1.0;
            invalidate();
            return true;
        });
    }

    @Override
    protected void onAttached() {
        // The natural configure-then-add order (`setIndeterminate(true)` before
        // the widget joins a scene) must still animate: arm on attach.
        if (indeterminate) {
            startSweep();
        }
    }

    @Override
    protected void onDetached() {
        sweepGeneration++; // the old scene's ticker is stale now
        animating = false;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        // The bar's entire size axis is this one token; onPaint reads none, because every
        // coordinate it draws is a function of the laid-out box.
        SizeTokens t = Theme.current().tokensFor(this);
        return constraints.constrain(
                preferredWidth >= 0 ? preferredWidth : DEFAULT_WIDTH,
                thickness >= 0 ? thickness : t.progressThickness());
    }

    @Override
    protected void onPaint(Canvas canvas) {
        if (indeterminate) {
            startSweep(); // re-arm after being hidden (ticker paused itself)
        }
        Theme theme = Theme.current();
        float radius = height() / 2;
        canvas.fillRoundRect(0, 0, width(), height(), radius, theme.surfaceRaised);
        var fillColor = isEnabled() ? theme.primary : theme.disabledFill;

        if (indeterminate) {
            float pillWidth = width() * SWEEP_FRACTION;
            float travel = width() + pillWidth;
            // Ease the pill across and off both ends.
            float x = (float) (sweepPhase * travel) - pillWidth;
            float x0 = Math.max(0, x);
            float x1 = Math.min(width(), x + pillWidth);
            if (x1 > x0) {
                canvas.fillRoundRect(x0, 0, x1 - x0, height(), radius, fillColor);
            }
        } else {
            float shown = fill.value();
            if (shown > 0) {
                float fillWidth = Math.max(height(), width() * shown);
                canvas.fillRoundRect(0, 0, fillWidth, height(), radius, fillColor);
            }
        }
    }
}

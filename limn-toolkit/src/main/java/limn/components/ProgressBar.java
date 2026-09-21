package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.a11y.ValueAccessibility;
import limn.animation.Transition;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Size;
import limn.scene.Change;
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

    /**
     * Sets the determinate progress, clamped to {@code [0..1]}; the fill eases to it. A bar that
     * was indeterminate becomes determinate, and says so: the sweep's ticker stops itself without
     * damaging anything, and the fill's transition is silent when its target has not moved, so a
     * value that arrives after a sweep and equals the one the fill was already at &mdash; the
     * fresh bar that connects and then reports nothing done yet &mdash; would otherwise leave the
     * last sweep pill on screen and the busy state in the accessible tree until unrelated damage.
     */
    public ProgressBar setProgress(float value) {
        Ui.checkUiThread();
        float clamped = Math.max(0, Math.min(1, value));
        boolean moved = clamped != progress;
        this.progress = clamped;
        if (indeterminate) {
            indeterminate = false;
            invalidate();
            // The sweep ending is a consequence of the value arriving, so it is announced first
            // and as an adjustment; the value the call names comes last.
            notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.ADJUSTMENT));
        }
        fill.to(progress); // eases from the previous value (or snaps when detached)
        if (moved) {
            notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
        }
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
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
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

    /**
     * Describes the bar as one {@code PROGRESS_BAR} node whose value is the model and never the
     * picture.
     *
     * <p>The role is unconditional, and it is the reason the widget describes itself at all: the
     * bar paints, holds no string and takes no input, which is the shape the walk deletes and then
     * warns about once per class, and what it draws is information rather than decoration, so the
     * decoration seam is not the answer. No name is declared, on purpose. The widget holds nothing
     * to hand over: a tooltip names it through the walk's free default, an application's
     * {@code setAccessibleName} wins over that and turns the tooltip into the description, and a
     * caption beside the bar is a relation the application declares rather than one guessed here.
     *
     * <p>Determinate, the node carries a value facet in whole percent, {@code 0..100}, with no
     * step and no text: nothing increments it, the number is the whole of it, and there is no
     * formatted string anywhere in this class to hand over, so no cache and no witness exist. The
     * rounding is what keeps an application that drives {@link #setProgress} every frame with
     * sub-percent deltas from copying the tree every frame; the comparison sees what it is given,
     * and one percent is the resolution a reader speaks. Indeterminate, the facet is absent and
     * {@code BUSY} stands in its place, because the number means nothing then and no platform
     * carries a range on a bar that has none.
     *
     * <p>Neither the eased fill nor the sweep phase is read here. The fill eases toward the model
     * for the length of a fade after every set and the sweep moves on every frame; publishing
     * either would make the difference find a change on each of those frames. Publishing the
     * model means those frames walk, bounded and without allocating, and publish nothing.
     * {@code READ_ONLY} is not written because the builder derives that bit from a text facet and
     * drops it here; that the value cannot be set is said by the role together with the inherited
     * refusal of {@code SET_VALUE}. {@code HORIZONTAL} is fixed by the class, since the long axis
     * has no seam. An enum, four doubles and two bits, and no string, so a damaged frame that
     * changed nothing costs no memory.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.PROGRESS_BAR);
        a.state(Accessible.State.HORIZONTAL);
        if (indeterminate) {
            a.state(Accessible.State.BUSY);
        } else {
            // Read-only said on the facet, because the facet's presence is what advertises a set
            // on every platform and the role alone takes nothing back: without it a bridge reports
            // a writable range whose set lands in the inherited hook, refused in silence. The
            // VALUE shape, written once (ADR 045 §3), offers no step verb for a read-only value.
            ValueAccessibility.describe(a, Math.round(progress * 100f), 0, 100, 0, true);
        }
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

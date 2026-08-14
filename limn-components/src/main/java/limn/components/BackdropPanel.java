package limn.components;

import limn.concurrent.Ui;
import limn.graphics.BackdropEffect;
import limn.graphics.Canvas;
import limn.scene.Insets;
import limn.scene.Widget;
import limn.scene.layout.Padding;

import java.util.Objects;

/**
 * A panel whose background is the content behind it, put through a {@link BackdropEffect}: a
 * glass control bar over video, a washed header over a picture, a redacted field. Wraps one child
 * with insets, like the {@link Padding} it extends, and paints the effect under it.
 *
 * <p><b>Put it where it will be painted after what it sits over.</b> The effect samples what the
 * frame has already drawn, so a panel is a sibling <em>after</em> the content: in a stack, the
 * layer above; in a column, nothing sits behind it and the effect shows the window's own
 * background, which is a legitimate but very dull result.
 *
 * <p>Costs a batch break and a copy of its own bounds per frame it paints. That is a control bar's
 * worth of work, not a list row's: a panel per row is the wrong shape for this widget, and a
 * hundred of them will show up in the frame time.
 *
 * <p>Degrades to a flat panel in the effect's {@link BackdropEffect#tint() tint} where the renderer
 * has no backdrop support, with the same size, position and corner radius.
 *
 * <p><b>Size axis:</b> the corner radius follows the resolved {@link limn.scene.ControlSize} row
 * like every other component's chrome, unless {@link #setCornerRadius} pins it. The effect's own
 * dimensions (a rim width, a cell size) are authored in points and do not scale with the step:
 * they are optical properties of the material, not of the control.
 */
public class BackdropPanel extends Padding {

    /** {@link #setCornerRadius} takes this to mean "follow the resolved size row". */
    public static final float RADIUS_FROM_TOKENS = -1;

    private java.util.List<BackdropEffect> effects;
    private float cornerRadius = RADIUS_FROM_TOKENS;

    /** A panel with 12pt of padding around {@code child}. */
    public BackdropPanel(BackdropEffect effect, Widget child) {
        this(effect, Insets.all(12), child);
    }

    public BackdropPanel(BackdropEffect effect, Insets insets, Widget child) {
        super(insets, child);
        this.effects = java.util.List.of(Objects.requireNonNull(effect, "effect"));
    }

    /** The first effect painted behind the child; see {@link #effects()} for the whole stack. */
    public final BackdropEffect effect() {
        return effects.get(0);
    }

    /** Every effect painted behind the child, in the order they are drawn. */
    public final java.util.List<BackdropEffect> effects() {
        return effects;
    }

    /**
     * Replaces the effect with a <b>stack</b>, drawn in order over the same shape.
     *
     * <p>This is how the effects compose, and it needs nothing from the renderer: each one reads
     * the framebuffer and writes it, so the second samples what the first left. It is what makes
     * a frosted pane out of the pieces rather than out of a variant with four parameters, and
     * what makes {@link BackdropEffect.Blur} affordable, since a separable blur IS two passes.
     *
     * <p>Each pass costs one batch flush and one copy of the panel's own bounds, so a stack of
     * three costs three of each. Over a small panel that is cheap and over a full-window one it
     * is not; the cost is proportional to the shape, not to the window.
     *
     * @throws IllegalArgumentException if the stack is empty
     */
    public BackdropPanel setEffects(BackdropEffect... stack) {
        Ui.checkUiThread();
        if (stack.length == 0) {
            throw new IllegalArgumentException("a backdrop panel needs at least one effect");
        }
        this.effects = java.util.List.of(stack);
        invalidate();
        return this;
    }

    /** Replaces the effect. UI thread only. */
    public BackdropPanel setEffect(BackdropEffect newEffect) {
        Ui.checkUiThread();
        this.effects = java.util.List.of(Objects.requireNonNull(newEffect, "newEffect"));
        invalidate();
        return this;
    }

    /**
     * Pins the corner radius in points, or {@link #RADIUS_FROM_TOKENS} to follow the resolved size
     * row (the default). UI thread only.
     */
    public BackdropPanel setCornerRadius(float radius) {
        Ui.checkUiThread();
        this.cornerRadius = radius;
        invalidate();
        return this;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        float radius = cornerRadius >= 0
                ? cornerRadius
                : Theme.current().tokensFor(this).radiusLarge();
        // In order: each pass reads what the one before it wrote.
        for (BackdropEffect each : effects) {
            canvas.fillBackdropRoundRect(0, 0, width(), height(), radius, each);
        }
    }
}

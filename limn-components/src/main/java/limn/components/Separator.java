package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Size;
import limn.scene.Widget;

/**
 * A thin divider line drawn in the theme's {@link Theme#outline} color. A
 * horizontal separator stretches across the available width (with a thin height);
 * a vertical one stretches down the available height (thin width). Use it to
 * divide groups in a Column/Row or inside a {@link ToolBar}.
 *
 * <h2>Size axis</h2>
 * Separator <b>participates by inheritance only</b> and exposes no size setter: what a reader
 * perceives is a 1&nbsp;pt hairline, pixel-locked at every {@link ControlSize} step. Only the
 * box around the line moves. A "large separator" is either the same line with more air
 * (the container's job) or a thicker rule, which is a different visual element.
 *
 * <p>The box is <b>odd at every step on purpose</b>: a 1&nbsp;pt line can only sit exactly
 * centred inside an odd box, and the snap relies on that parity. {@link #setInset} is the one
 * dimension a caller controls.
 */
public final class Separator extends Widget {

    public enum Orientation { HORIZONTAL, VERTICAL }

    /**
     * Long-axis size when that axis is unbounded (no stretch parent). <b>Not a size token</b>:
     * a "do not collapse to zero" guard, identical at every step, which is exactly why
     * Separator's long axis is exempt from the ramp's strict-monotonicity rule.
     */
    private static final float FALLBACK_LENGTH = 24;

    private final Orientation orientation;
    private float inset; // margin trimmed off both ends of the line

    private Separator(Orientation orientation) {
        this.orientation = orientation;
    }

    /** A rule stretching across the available width. */
    public static Separator horizontal() {
        return new Separator(Orientation.HORIZONTAL);
    }

    /** A rule stretching down the available height. */
    public static Separator vertical() {
        return new Separator(Orientation.VERTICAL);
    }

    /** Trims {@code margin} points off both ends of the line. */
    public Separator setInset(float margin) {
        Ui.checkUiThread();
        setInsetInternal(margin);
        return this;
    }

    /**
     * Assigns the inset with no thread check and an equality guard, the form a container uses
     * from its own measure path. {@link Widget#measure} has never been thread- or
     * runtime-checked, and {@link Ui#checkUiThread()} throws when no runtime is installed at
     * all, so routing a container's measure through the public setter would put a new
     * precondition on a pure geometry call: a ToolBar holding a bar-built divider would throw
     * from {@code measure(...)} off the UI thread, or in an embedder that measures before a
     * backend exists. The caller here is already inside a UI-thread layout pass.
     *
     * <p>The guard is what makes it safe to call every pass: without it, a container pushing an
     * unchanged inset would request a layout from inside {@code onMeasure} forever.
     */
    void setInsetInternal(float margin) {
        float clamped = Math.max(0, margin);
        if (this.inset == clamped) {
            return;
        }
        this.inset = clamped;
        markNeedsLayout();
    }

    /** @return the inset applied to both ends of the line. */
    float inset() {
        return inset;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        // Resolved once per pass, as everywhere: the step is inherited, so this is the only
        // place the box thickness may be read from.
        SizeTokens t = Theme.current().tokensFor(this);
        float box = t.separatorBox();
        if (orientation == Orientation.HORIZONTAL) {
            float w = constraints.hasBoundedWidth() ? constraints.maxWidth() : FALLBACK_LENGTH;
            return constraints.constrain(w, box);
        }
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight() : FALLBACK_LENGTH;
        return constraints.constrain(box, h);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        // No SizeTokens read here: the line's weight is locked and its position derives from the
        // laid-out box, so paint cannot disagree with measure about which step it is on.
        Color color = Theme.current().outline;
        if (orientation == Orientation.HORIZONTAL) {
            float y = lineCenter(height());
            canvas.drawLine(inset, y, width() - inset, y, Strokes.HAIRLINE, color);
        } else {
            float x = lineCenter(width());
            canvas.drawLine(x, inset, x, height() - inset, Strokes.HAIRLINE, color);
        }
    }

    /**
     * Where the 1&nbsp;pt line's centre goes inside a {@code box}-thick cross axis: <b>floor</b>
     * plus half a pixel, never {@code Math.round}.
     *
     * <p>{@code Math.round} is {@code floor(x + 0.5)}, so on an odd box it returned
     * {@code box/2 + 1}, putting the ink a full point <em>below</em> centre: at box 5 the split
     * was 3&nbsp;:&nbsp;1 and the divider visually belonged to the item beneath it. Flooring puts
     * the centre at exactly {@code box/2} on every odd box (box 9 &rarr; 4.5, covering 4.0–5.0,
     * 4 above and 4 below) while still landing the stroke on a whole device pixel, which is what
     * the odd-box parity was for all along.
     */
    private static float lineCenter(float box) {
        return (int) (box / 2) + Strokes.HALF_PIXEL_INSET;
    }
}

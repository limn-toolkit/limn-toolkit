package limn.scene.layout;

import limn.concurrent.Ui;

import limn.internal.lang.Checks;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.LayoutOverflow;

import java.util.Objects;

/**
 * Wraps one child with edge insets.
 *
 * <p>Not final: a container that derives its insets from resolved
 * {@link limn.scene.ControlSize} tokens subclasses this and pushes them from its own measure
 * path. Without that, a size step would compact the controls and leave the page padding
 * around them at whatever literal the app typed, and the spacing ramp is the widest of the
 * three.
 *
 * <p>Like every layout, it declares how far its child may paint past it; see
 * {@link #paintOverflow(float)}. By default that is the reach of the theme's focus ring less the
 * narrowest of the four insets, and never below zero: a ring that fits inside the insets reaches
 * nothing past the padding, so a padded panel declares nothing and its repaints stay inside it,
 * while a padding of nothing declares the whole reach, as any other wrapper does.
 */
public class Padding extends Widget<Padding> {

    /** What {@link #paintOverflow} holds while the reach follows the theme: the default. */
    private static final float FROM_THEME = -1;

    private Insets insets;
    private final Widget<?> child;
    /** The reach {@link #paintOverflow(float)} declared, or {@link #FROM_THEME}. */
    private float paintOverflow = FROM_THEME;

    /** Insets {@code child} on each side; the padding is inside this widget's own box. */
    public Padding(Insets insets, Widget<?> child) {
        this.insets = Objects.requireNonNull(insets, "insets");
        this.child = child;
        add(child);
    }

    /** The same inset on all four sides. */
    public static Padding all(float value, Widget<?> child) {
        return new Padding(Insets.all(value), child);
    }

    /** The current insets. */
    public final Insets insets() {
        return insets;
    }

    /**
     * Replaces the insets, with an equality guard. Without the guard, a token-driven subclass
     * that re-applies its insets on every measure pass calls {@link #markNeedsLayout()} from
     * inside {@code onMeasure}, which dirties the path to the root and schedules another
     * pass: a layout loop that never settles.
     */
    public final void setInsets(Insets newInsets) {
        Ui.checkUiThread();
        Objects.requireNonNull(newInsets, "newInsets");
        if (insets.equals(newInsets)) {
            return;
        }
        insets = newInsets;
        markNeedsLayout();
    }

    /**
     * Assigns the insets <b>without</b> requesting a layout pass: the measure-path form, for
     * a subclass that derives them from resolved size tokens inside its own
     * {@code onMeasure}. Safe there and only there: the caller is already inside the pass that
     * will consume the new value, so requesting another one is at best wasteful and at worst
     * non-terminating. Everything else uses {@link #setInsets}.
     */
    protected final void setInsetsSilently(Insets newInsets) {
        insets = Objects.requireNonNull(newInsets, "newInsets");
    }

    /**
     * Declares how far the child may paint past this padding's box, in logical points, in place of
     * the default, which is the reach of the theme's focus ring less the narrowest inset. It is
     * what {@link Container#paintOverflow(float)} is to a container, for the one child here: the
     * public setter of {@link #paintOutset()}, taken as given whatever the insets are, and a new
     * declaration repaints this padding and its band while one already in force repaints nothing.
     * UI thread only.
     *
     * @param points how far the child may paint past each side of this box, in logical points
     * @return this padding
     * @throws IllegalArgumentException when negative, {@code NaN} or infinite
     */
    public Padding paintOverflow(float points) {
        Ui.checkUiThread();
        float reach = Checks.notNegative(points, "paint overflow");
        if (paintOverflow != reach) {
            paintOverflow = reach;
            invalidate();
        }
        return this;
    }

    /**
     * Goes back to declaring what is left of the theme's focus ring reach past the insets, which is
     * the default; see {@link #paintOverflow(float)}. UI thread only.
     *
     * @return this padding
     */
    public Padding paintOverflowFromTheme() {
        Ui.checkUiThread();
        if (paintOverflow != FROM_THEME) {
            paintOverflow = FROM_THEME;
            invalidate();
        }
        return this;
    }

    /**
     * What {@link #paintOverflow(float)} declared; else nothing when a subclass clips the child,
     * whose clip bounds it; else the reach of the focus ring in the theme in effect for this
     * padding, less the narrowest inset, never below zero. The child's box is this box less the
     * insets, so that is what of the reach is left past this box on its closest side. O(1) and
     * allocation-free.
     */
    @Override
    protected float paintOutset() {
        if (paintOverflow >= 0) {
            return paintOverflow;
        }
        if (clipsChildren()) {
            return 0;
        }
        float narrowest = Math.min(Math.min(insets.left(), insets.right()),
                Math.min(insets.top(), insets.bottom()));
        return Math.max(0, LayoutOverflow.of(this) - narrowest);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Size inner = child.measure(constraints.deflate(insets));
        return constraints.constrain(
                inner.width() + insets.left() + insets.right(),
                inner.height() + insets.top() + insets.bottom());
    }

    @Override
    protected void onLayout() {
        child.layoutBox(leadingInset(), insets.top(),
                Math.max(0, width() - insets.left() - insets.right()),
                Math.max(0, height() - insets.top() - insets.bottom()));
    }

    /**
     * The inset on the side the content starts from: {@code left} in a left-to-right subtree and
     * {@code right} in a right-to-left one.
     *
     * <p>Resolved here rather than by giving {@link Insets} a leading/trailing shape of its own.
     * {@code Insets} stays physical because it is read in very few places and a second inset type
     * would cost every application a decision it does not have; this is the one container that
     * has to take it. {@link #onMeasure} needs nothing, because it sums the two.
     */
    private float leadingInset() {
        return isRightToLeft() ? insets.right() : insets.left();
    }
}

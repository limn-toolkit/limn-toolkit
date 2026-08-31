package limn.scene.layout;

import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Size;
import limn.scene.Widget;

import java.util.Objects;

/**
 * Wraps one child with edge insets.
 *
 * <p>Not final: a container that derives its insets from resolved
 * {@link limn.scene.ControlSize} tokens subclasses this and pushes them from its own measure
 * path. Without that, a size step would compact the controls and leave the page padding
 * around them at whatever literal the app typed, and the spacing ramp is the widest of the
 * three.
 */
public class Padding extends Widget {

    private Insets insets;
    private final Widget child;

    /** Insets {@code child} on each side; the padding is inside this widget's own box. */
    public Padding(Insets insets, Widget child) {
        this.insets = Objects.requireNonNull(insets, "insets");
        this.child = child;
        add(child);
    }

    /** The same inset on all four sides. */
    public static Padding all(float value, Widget child) {
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
        return layoutDirection() == limn.scene.LayoutDirection.RTL ? insets.right() : insets.left();
    }
}

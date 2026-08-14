package limn.scene.layout;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

/**
 * Flex wrapper: inside a {@link Column}/{@link Row} it receives a share of
 * the leftover main-axis space proportional to {@code flex}. Without a child
 * it is a {@link #spacer(int)}: pure flexible whitespace.
 *
 * <p><b>The share is a tight constraint, so it overrides what the child asked for.</b>
 * A flex child is measured with its main axis pinned to the share (minimum and maximum
 * both equal to it), and the size the child returns from its own {@code onMeasure} is
 * discarded on that axis. A control that measured itself at 116&nbsp;pt because that is
 * what its chrome and its widest value need will be handed 35&nbsp;pt in a crowded row
 * and will paint at 35, one part on top of another. That is the container doing as it
 * was told: nothing in the layout protocol lets a widget refuse.
 *
 * <p>{@link #atLeast} is how a call site refuses on the child's behalf. It is opt-in per
 * call site rather than a default, because the right floor is not a property of the
 * widget class: a caption that ellipsises wants to be squeezed (that is what the
 * ellipsis is for) and a stepper with a fixed chrome width does not, and the same
 * widget can be either one depending on the row it is in.
 */
public final class Expanded extends Widget {

    private final int flex;
    private final Widget child;
    private float minMain;

    private Expanded(Widget child, int flex) {
        if (flex <= 0) {
            throw new IllegalArgumentException("flex must be >= 1, got " + flex);
        }
        this.flex = flex;
        this.child = child;
        if (child != null) {
            add(child);
        }
    }

    /** Gives {@code child} a share of the leftover space proportional to {@code flex}. */
    public static Expanded of(Widget child, int flex) {
        return new Expanded(child, flex);
    }

    /** An equal share of the leftover space: {@code flex} of 1. */
    public static Expanded of(Widget child) {
        return new Expanded(child, 1);
    }

    /** Flexible empty space. */
    public static Expanded spacer(int flex) {
        return new Expanded(null, flex);
    }

    /** This child's share weight. */
    public int flex() {
        return flex;
    }

    /**
     * Declares a main-axis floor, in logical points: this child is never given less
     * than {@code points}, however little the weighted split would leave it. Chains
     * after {@link #of}. The default is {@code 0}: no floor, and the distribution is
     * the plain weighted one.
     *
     * <p>The container satisfies floors before weight. A child whose share would fall
     * under its floor is frozen at the floor and drops out of the split; whatever is
     * left is re-divided among the others, which can push another one under its own
     * floor, so this repeats until nothing is under. Siblings without floors absorb the
     * whole loss, which is the point: a floored stepper beside an ellipsising label
     * keeps its chrome and the label gives up the points.
     *
     * <p><b>Floors that do not fit overflow the container.</b> When they add up to more
     * than there is room for, every child still gets its floor and the row is
     * over-subscribed; children paint past the container's edge. This is the same
     * outcome a row of too-wide fixed children has today: a {@link Flex} does not clip
     * and does not wrap, so an over-subscribed row is visibly over-subscribed rather
     * than silently truncated. Declare floors that fit, or give the container more room.
     *
     * <p>Negative values clamp to {@code 0}. UI thread only.
     */
    public Expanded atLeast(float points) {
        float floor = Math.max(0, points);
        if (this.minMain != floor) {
            this.minMain = floor;
            markNeedsLayout();
        }
        return this;
    }

    /** @return the declared main-axis floor in points, {@code 0} when none was set */
    public float minMain() {
        return minMain;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        if (child == null) {
            return constraints.constrain(0, 0);
        }
        Size size = child.measure(constraints);
        return constraints.constrain(size.width(), size.height());
    }

    @Override
    protected void onLayout() {
        if (child != null) {
            child.layoutBox(0, 0, width(), height());
        }
    }
}

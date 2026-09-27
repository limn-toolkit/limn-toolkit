package limn.scene.layout;

import limn.concurrent.Ui;
import limn.internal.lang.Checks;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.LayoutOverflow;

/**
 * Forces a fixed width and/or height (unset dimensions pass through to the
 * child, or collapse to zero without one). Also handy as a rigid spacer.
 */
public final class SizedBox extends Widget<SizedBox> {

    /** Marks a dimension as "not fixed". */
    public static final float UNSET = -1;

    private final float fixedWidth;
    private final float fixedHeight;
    private final Widget<?> child;

    /** What {@link #paintOverflow} holds while the reach follows the theme: the default. */
    private static final float FROM_THEME = -1;
    /** The reach {@link #paintOverflow(float)} declared, or {@link #FROM_THEME}. */
    private float paintOverflow = FROM_THEME;

    /** Forces {@code child} to an exact size; {@link #UNSET} on an axis leaves it free. */
    public SizedBox(float width, float height, Widget<?> child) {
        this.fixedWidth = width;
        this.fixedHeight = height;
        this.child = child;
        if (child != null) {
            add(child);
        }
    }

    /** An empty box of a fixed size: a spacer. */
    public SizedBox(float width, float height) {
        this(width, height, null);
    }

    /**
     * Declares how far the child may paint past this box, in logical points, in place of
     * the reach of the theme's focus ring, which is the default. It is what
     * {@link Container#paintOverflow(float)} is to a container, for the one child here: the public
     * setter of {@link #paintOutset()}, and a new declaration repaints this box and its band
     * while one already in force repaints nothing. UI thread only.
     *
     * @param points how far the child may paint past each side of this box, in logical points
     * @return this box
     * @throws IllegalArgumentException when negative, {@code NaN} or infinite
     */
    public SizedBox paintOverflow(float points) {
        Ui.checkUiThread();
        float reach = Checks.notNegative(points, "paint overflow");
        if (paintOverflow != reach) {
            paintOverflow = reach;
            invalidate();
        }
        return this;
    }

    /**
     * Goes back to declaring the reach of the theme's focus ring for the child, which is the
     * default; see {@link #paintOverflow(float)}. UI thread only.
     *
     * @return this box
     */
    public SizedBox paintOverflowFromTheme() {
        Ui.checkUiThread();
        if (paintOverflow != FROM_THEME) {
            paintOverflow = FROM_THEME;
            invalidate();
        }
        return this;
    }

    /**
     * What {@link #paintOverflow(float)} declared; else nothing for a spacer, which has no child
     * to paint past it; else the reach of the focus ring in the theme in effect for this box:
     * the child is laid out over the whole box, so a ring at its edge is a ring at this box's
     * edge. O(1) and allocation-free.
     */
    @Override
    protected float paintOutset() {
        if (paintOverflow >= 0) {
            return paintOverflow;
        }
        return child == null ? 0 : LayoutOverflow.of(this);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        float maxW = fixedWidth >= 0 ? constraints.constrainWidth(fixedWidth) : constraints.maxWidth();
        float maxH = fixedHeight >= 0 ? constraints.constrainHeight(fixedHeight) : constraints.maxHeight();
        float width = fixedWidth >= 0 ? maxW : 0;
        float height = fixedHeight >= 0 ? maxH : 0;
        if (child != null) {
            Size inner = child.measure(new Constraints(
                    fixedWidth >= 0 ? maxW : 0, maxW,
                    fixedHeight >= 0 ? maxH : 0, maxH));
            width = fixedWidth >= 0 ? maxW : inner.width();
            height = fixedHeight >= 0 ? maxH : inner.height();
        }
        return constraints.constrain(width, height);
    }

    @Override
    protected void onLayout() {
        if (child != null) {
            child.layoutBox(0, 0, width(), height());
        }
    }
}

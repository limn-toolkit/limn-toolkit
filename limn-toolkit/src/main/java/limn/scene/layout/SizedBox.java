package limn.scene.layout;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

/**
 * Forces a fixed width and/or height (unset dimensions pass through to the
 * child, or collapse to zero without one). Also handy as a rigid spacer.
 */
public final class SizedBox extends Widget {

    /** Marks a dimension as "not fixed". */
    public static final float UNSET = -1;

    private final float fixedWidth;
    private final float fixedHeight;
    private final Widget child;

    /** Forces {@code child} to an exact size; {@link #UNSET} on an axis leaves it free. */
    public SizedBox(float width, float height, Widget child) {
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

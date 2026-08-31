package limn.scene.layout;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

/**
 * Overlays children on top of each other (later children on top; the base
 * for overlays and modal dialogs). Sizes to the biggest child; each child is
 * placed by this stack's {@link Alignment}.
 */
public class Stack extends Widget {

    /**
     * Where a child sits in the stack's box, on two vocabularies that coexist on purpose.
     *
     * <p>The nine <b>physical</b> constants name a side of the box and keep naming it whatever
     * the subtree reads: {@code TOP_LEFT} is the top left corner in a right-to-left interface
     * too. They are the escape hatch for the placement that is about the box and not about the
     * reading order — a resize grip, a watermark, a debug overlay — and {@code TOP_LEFT} stays
     * the default, so no existing stack moves.
     *
     * <p>The six <b>logical</b> constants name the side the content starts or ends on, and
     * follow {@link limn.scene.LayoutDirection}: {@code TOP_START} is the top left corner left to
     * right and the top right corner right to left. Reach for these when the placement means
     * "where reading begins" — a badge on a leading edge, a close button on a trailing one.
     *
     * <p>The nine are not renamed. Renaming them would be a source-breaking change to a published
     * enum in order to fix the handful of places that name one, and adding is free.
     */
    public enum Alignment {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,

        /** Top, on the side the content starts from. */
        TOP_START,
        /** Vertically centred, on the side the content starts from. */
        CENTER_START,
        /** Bottom, on the side the content starts from. */
        BOTTOM_START,
        /** Top, on the side the content ends on. */
        TOP_END,
        /** Vertically centred, on the side the content ends on. */
        CENTER_END,
        /** Bottom, on the side the content ends on. */
        BOTTOM_END
    }

    private Alignment alignment = Alignment.TOP_LEFT;

    /** Where children sit within the stack's box; they all get the full box to measure against. */
    public Stack alignment(Alignment newAlignment) {
        this.alignment = newAlignment;
        markNeedsLayout();
        return this;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Constraints loose = constraints.loosened();
        float maxWidth = 0;
        float maxHeight = 0;
        for (Widget child : children()) {
            if (!child.isVisible()) {
                continue;
            }
            Size size = child.measure(loose);
            maxWidth = Math.max(maxWidth, size.width());
            maxHeight = Math.max(maxHeight, size.height());
        }
        return constraints.constrain(maxWidth, maxHeight);
    }

    @Override
    protected void onLayout() {
        Constraints loose = Constraints.loose(width(), height());
        // Resolved once for the whole pass, and only the horizontal switch consults it: the
        // vertical one has no reading order to follow.
        boolean rtl = layoutDirection() == limn.scene.LayoutDirection.RTL;
        for (Widget child : children()) {
            if (!child.isVisible()) {
                continue;
            }
            Size size = child.measure(loose);
            float far = width() - size.width();
            float cx = switch (alignment) {
                case TOP_LEFT, CENTER_LEFT, BOTTOM_LEFT -> 0;
                case TOP_CENTER, CENTER, BOTTOM_CENTER -> far / 2;
                case TOP_RIGHT, CENTER_RIGHT, BOTTOM_RIGHT -> far;
                case TOP_START, CENTER_START, BOTTOM_START -> rtl ? far : 0;
                case TOP_END, CENTER_END, BOTTOM_END -> rtl ? 0 : far;
            };
            float cy = switch (alignment) {
                case TOP_LEFT, TOP_CENTER, TOP_RIGHT, TOP_START, TOP_END -> 0;
                case CENTER_LEFT, CENTER, CENTER_RIGHT, CENTER_START, CENTER_END ->
                        (height() - size.height()) / 2;
                case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT, BOTTOM_START, BOTTOM_END ->
                        height() - size.height();
            };
            child.layoutBox(cx, cy, size.width(), size.height());
        }
    }
}

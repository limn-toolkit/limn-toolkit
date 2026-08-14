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

    public enum Alignment {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
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
        for (Widget child : children()) {
            if (!child.isVisible()) {
                continue;
            }
            Size size = child.measure(loose);
            float cx = switch (alignment) {
                case TOP_LEFT, CENTER_LEFT, BOTTOM_LEFT -> 0;
                case TOP_CENTER, CENTER, BOTTOM_CENTER -> (width() - size.width()) / 2;
                case TOP_RIGHT, CENTER_RIGHT, BOTTOM_RIGHT -> width() - size.width();
            };
            float cy = switch (alignment) {
                case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> 0;
                case CENTER_LEFT, CENTER, CENTER_RIGHT -> (height() - size.height()) / 2;
                case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> height() - size.height();
            };
            child.layoutBox(cx, cy, size.width(), size.height());
        }
    }
}

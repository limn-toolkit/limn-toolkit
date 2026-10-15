package limn.demo;

import limn.components.Theme;
import limn.graphics.Canvas;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Size;
import limn.scene.Widget;

/**
 * A list or a tree set in a hairline frame on the raised surface, so where it ends is seen and not
 * guessed. It takes the size its parent gives, a whole height included, or its content's own.
 *
 * <p>The frame is drawn by a decorative child behind the content, left out of the accessible
 * tree, so the frame itself is scaffolding a reader never meets and the content is read as if
 * nothing held it.
 */
final class HairlineFrame extends Widget<HairlineFrame> {

    private final Widget<?> backing = new Backing();
    private final Widget<?> content;

    HairlineFrame(Widget<?> content) {
        this.content = content;
        add(backing);
        add(content);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Size size = content.measure(constraints.deflate(Insets.all(1)));
        return constraints.constrain(size.width() + 2, size.height() + 2);
    }

    @Override
    protected void onLayout() {
        float w = Math.max(0, width() - 2);
        float h = Math.max(0, height() - 2);
        backing.measure(Constraints.tight(width(), height()));
        backing.layoutBox(0, 0, width(), height());
        content.measure(Constraints.tight(w, h));
        content.layoutBox(1, 1, w, h);
    }

    /** The frame's drawing: a fill and a hairline, nothing a reader needs. */
    private static final class Backing extends Widget<Backing> {
        Backing() {
            setAccessibleIgnored(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(),
                    theme.surfaceRaised(), 1, theme.outline());
        }
    }
}

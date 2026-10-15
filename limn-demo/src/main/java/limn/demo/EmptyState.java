package limn.demo;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

/**
 * A view that says when it has nothing to show: the content keeps its whole box, a table its
 * header and its footer, and while it is empty a message sits centred over it. The message is
 * the caller's, so it can name what was searched for and offer the way back.
 */
final class EmptyState extends Widget<EmptyState> {

    private final Widget<?> content;
    private final Widget<?> message;
    private boolean empty;

    EmptyState(Widget<?> content, Widget<?> message) {
        this.content = content;
        this.message = message;
        add(content);
        add(message);
        message.setVisible(false);
    }

    /** Shows the message over the content, or takes it away. */
    void setEmpty(boolean value) {
        if (empty == value) {
            return;
        }
        empty = value;
        message.setVisible(value);
        markNeedsLayout();
        invalidate();
    }

    boolean isEmpty() {
        return empty;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Size size = content.measure(constraints);
        return constraints.constrain(size.width(), size.height());
    }

    @Override
    protected void onLayout() {
        content.measure(Constraints.tight(width(), height()));
        content.layoutBox(0, 0, width(), height());
        if (message.isVisible()) {
            Size size = message.measure(Constraints.loose(Math.max(0, width() - 32), height()));
            message.layoutBox((width() - size.width()) / 2, (height() - size.height()) / 2,
                    size.width(), size.height());
        }
    }
}

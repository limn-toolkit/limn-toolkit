package limn.demo;

import limn.components.ScrollView;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;

/**
 * Interactive scene: flex rows with weighted {@code Expanded} children, a
 * {@code Stack} overlay, and a wheel-scrollable {@code ScrollView}, built
 * from {@link DemoBox} widgets that react to hover/press/focus (click or Tab
 * to focus). Everything repaints event-driven; the loop sleeps when idle.
 */
final class WidgetsScene {

    /** Focusable colored box: hover lightens, press darkens, focus draws a ring. */
    static final class DemoBox extends Widget {
        private final float prefWidth;
        private final float prefHeight;
        private final Color color;
        private final String label;
        private boolean hover;
        private boolean pressed;

        DemoBox(String label, Color color, float prefWidth, float prefHeight) {
            this.label = label;
            this.color = color;
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Color base = pressed ? color.lerp(Color.BLACK, 0.3f)
                    : hover ? color.lerp(Color.WHITE, 0.18f)
                    : color;
            canvas.fillRoundRect(0, 0, width(), height(), 10, base);
            if (isFocused()) {
                canvas.drawRoundRect(1, 1, width() - 2, height() - 2, 9, 2, Color.rgb(0xE8ECF2));
            }
            if (!label.isEmpty()) {
                canvas.drawText(label, 10, height() / 2 + 5, Font.of(13),
                        Color.rgba(0x0B1220, 0.85f));
            }
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case ENTER -> {
                    hover = true;
                    invalidate();
                }
                case EXIT -> {
                    hover = false;
                    pressed = false;
                    invalidate();
                }
                case PRESS -> {
                    pressed = true;
                    invalidate();
                    event.consume();
                }
                case RELEASE, CLICK -> {
                    pressed = false;
                    invalidate();
                    event.consume();
                }
                default -> {
                }
            }
        }
    }

    private WidgetsScene() {
    }

    static Scene create() {
        Row header = new Row();
        header.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        header.add(new DemoBox("fixed 140", Color.rgb(0x4C8DFF), 140, 0));
        header.add(Expanded.of(new DemoBox("flex 1", Color.rgb(0x34D399), 10, 0), 1));
        header.add(Expanded.of(new DemoBox("flex 2", Color.rgb(0xFFB454), 10, 0), 2));

        Stack stack = new Stack();
        stack.alignment(Stack.Alignment.BOTTOM_RIGHT);
        stack.add(new DemoBox("stack: background", Color.rgb(0x334155), 4000, 4000));
        stack.add(new DemoBox("overlay", Color.rgb(0xF472B6), 130, 40));

        Column feed = new Column();
        feed.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        Color[] palette = {
                Color.rgb(0x475569), Color.rgb(0x3B82F6), Color.rgb(0x22C55E),
                Color.rgb(0xF59E0B), Color.rgb(0xEF4444), Color.rgb(0x8B5CF6),
        };
        for (int i = 0; i < 18; i++) {
            feed.add(new DemoBox("item " + (i + 1) + ": scroll the list", palette[i % palette.length], 10, 40));
        }

        Column content = new Column();
        content.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        content.add(new SizedBox(SizedBox.UNSET, 64, header));
        content.add(new SizedBox(SizedBox.UNSET, 96, stack));
        content.add(Expanded.of(new ScrollView(feed), 1));

        return new Scene(Padding.all(16, content));
    }
}

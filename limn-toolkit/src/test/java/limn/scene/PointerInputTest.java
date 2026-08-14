package limn.scene;

import limn.backend.Cursor;
import limn.backend.ImageCursor;
import limn.graphics.Image;
import limn.scene.event.MouseEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Relative-capture MOTION delivery ({@code WindowInput.mouseDelta} → focused
 * widget) and custom {@link ImageCursor} hover resolution, driven headlessly
 * through {@link RecordingWindow}.
 */
class PointerInputTest extends SceneTestBase {

    /** Focusable box that records the MOTION events it receives. */
    static final class MotionBox extends FixedBox {
        final List<MouseEvent> motions = new ArrayList<>();

        MotionBox() {
            super(100, 100);
            setFocusable(true);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.MOTION) {
                motions.add(event);
                event.consume();
            }
        }
    }

    private static Image tinyImage() {
        return new Image(4, 4, new byte[4 * 4 * 4]);
    }

    @Test
    void deltasCoalesceBySumAndReachTheFocusedWidget() {
        MotionBox box = new MotionBox();
        Column root = new Column();
        root.add(box);
        Scene scene = new Scene(root);
        scene.bind(new RecordingWindow());
        scene.layoutPass(200, 200);
        box.requestFocus();

        scene.mouseDelta(2f, 3f);
        scene.mouseDelta(1f, -1f);
        scene.mouseDelta(0.5f, 0f);
        scene.inputBatchEnded();

        assertEquals(1, box.motions.size(), "one coalesced MOTION per batch");
        MouseEvent motion = box.motions.get(0);
        assertEquals(MouseEvent.Type.MOTION, motion.type());
        assertEquals(3.5f, motion.deltaX(), 1e-6);
        assertEquals(2f, motion.deltaY(), 1e-6);
        assertEquals(-1, motion.button());
    }

    @Test
    void interveningEventBreaksDeltaCoalescing() {
        MotionBox box = new MotionBox();
        Column root = new Column();
        root.add(box);
        Scene scene = new Scene(root);
        scene.bind(new RecordingWindow());
        scene.layoutPass(200, 200);
        box.requestFocus();

        scene.mouseDelta(1f, 0f);
        scene.keyEvent(limn.input.Keys.SPACE, true, false, 0);
        scene.mouseDelta(2f, 0f);
        scene.inputBatchEnded();

        // Key between the deltas: order is preserved, so two MOTION events.
        assertEquals(2, box.motions.size());
        assertEquals(1f, box.motions.get(0).deltaX(), 1e-6);
        assertEquals(2f, box.motions.get(1).deltaX(), 1e-6);
    }

    @Test
    void deltasWithNothingFocusedBubbleFromTheRoot() {
        List<MouseEvent> rootMotions = new ArrayList<>();
        Widget root = new FixedBox(200, 200) {
            @Override
            protected void onMouseEvent(MouseEvent event) {
                if (event.type() == MouseEvent.Type.MOTION) {
                    rootMotions.add(event);
                }
            }
        };
        Scene scene = new Scene(root);
        scene.bind(new RecordingWindow());
        scene.layoutPass(200, 200);

        scene.mouseDelta(4f, 5f);
        scene.inputBatchEnded();

        assertEquals(1, rootMotions.size());
        assertEquals(4f, rootMotions.get(0).deltaX(), 1e-6);
        assertEquals(5f, rootMotions.get(0).deltaY(), 1e-6);
    }

    @Test
    void hoverAppliesImageCursorAndClearsItOffWidget() {
        ImageCursor crosshair = new ImageCursor(tinyImage(), 2, 2);
        FixedBox custom = new FixedBox(100, 40);
        custom.setImageCursor(crosshair);
        FixedBox plain = new FixedBox(100, 40);
        Column root = new Column();
        root.add(custom);
        root.add(plain);

        RecordingWindow win = new RecordingWindow();
        Scene scene = new Scene(root);
        scene.bind(win);
        scene.layoutPass(200, 200);

        scene.mouseMoved(50, 20); // over 'custom'
        scene.inputBatchEnded();
        assertSame(crosshair, win.imageCursor);

        scene.mouseMoved(50, 60); // over 'plain'
        scene.inputBatchEnded();
        assertNull(win.imageCursor, "image cursor clears off the widget");
        assertEquals(Cursor.DEFAULT, win.cursor);
    }

    @Test
    void imageCursorWinsOverShapeOnTheSameWidgetAndInheritsDown() {
        ImageCursor brush = new ImageCursor(tinyImage(), 0, 0);
        Column group = new Column();
        group.setCursor(Cursor.POINTER);
        group.setImageCursor(brush); // image wins over the shape on the same widget
        FixedBox child = new FixedBox(100, 40); // inherits from the group
        group.add(child);

        RecordingWindow win = new RecordingWindow();
        Scene scene = new Scene(group);
        scene.bind(win);
        scene.layoutPass(200, 200);

        scene.mouseMoved(50, 20); // over the child
        scene.inputBatchEnded();
        assertSame(brush, win.imageCursor);
    }

    @Test
    void liveImageCursorChangeUnderThePointerAppliesImmediately() {
        ImageCursor first = new ImageCursor(tinyImage(), 0, 0);
        FixedBox box = new FixedBox(100, 40);
        Column root = new Column();
        root.add(box);
        RecordingWindow win = new RecordingWindow();
        Scene scene = new Scene(root);
        scene.bind(win);
        scene.layoutPass(200, 200);

        scene.mouseMoved(50, 20);
        scene.inputBatchEnded();
        assertNull(win.imageCursor);

        box.setImageCursor(first); // while hovered: no extra mouse move needed
        assertSame(first, win.imageCursor);
        box.setImageCursor(null);
        assertNull(win.imageCursor);
    }

    @Test
    void imageCursorValidatesHotspotInsideTheImage() {
        assertThrows(IllegalArgumentException.class,
                () -> new ImageCursor(tinyImage(), 4, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageCursor(tinyImage(), 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageCursor(null, 0, 0));
    }

    /** Trivial vertical stack for the tests above. */
    static final class Column extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            float h = 0;
            float w = 0;
            for (Widget child : children()) {
                Size s = child.measure(Constraints.loose(c.maxWidth(), c.maxHeight()));
                h += s.height();
                w = Math.max(w, s.width());
            }
            return c.constrain(w, h);
        }

        @Override
        protected void onLayout() {
            float y = 0;
            for (Widget child : children()) {
                Size s = child.measure(Constraints.loose(width(), height()));
                child.layoutBox(0, y, s.width(), s.height());
                y += s.height();
            }
        }
    }
}

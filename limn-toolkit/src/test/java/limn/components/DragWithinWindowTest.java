package limn.components;

import limn.graphics.Image;
import limn.scene.Scene;
import limn.scene.event.DragStartEvent;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import limn.transfer.Drag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ready-made widget told to keep its drags in its window answers a drag that says so, which is
 * what keeps the scene from handing it to the system: the list, the image view.
 */
class DragWithinWindowTest {

    private static Scene show(limn.scene.Widget<?> root) {
        Scene scene = new Scene(root);
        scene.setTextRuler(TestRulers.FIXED);
        scene.layoutPass(300, 200);
        scene.renderFrame(new NoopCanvas(300, 200));
        return scene;
    }

    private static DragStartEvent at(limn.scene.Widget<?> widget) {
        return new DragStartEvent(widget.localToScene(0, 0).x() + 8, widget.localToScene(0, 0).y() + 8, 0, false);
    }

    @Test
    void aListsDragStaysInItsWindowOnceToldTo() {
        try (HeadlessUi ui = new HeadlessUi()) {
            ListView<String> list = new ListView<String>(Label::new).setItems(List.of("a", "b"));
            list.setDragEnabled(true);
            show(list);
            Drag free = list.onDragStart(at(list));
            assertNotNull(free);
            assertFalse(free.isWithinWindow());

            list.setDragWithinWindow(true);
            Drag kept = list.onDragStart(at(list));
            assertNotNull(kept);
            assertTrue(kept.isWithinWindow());
        }
    }

    @Test
    void aPicturesDragStaysInItsWindowOnceToldTo() {
        try (HeadlessUi ui = new HeadlessUi()) {
            ImageView view = new ImageView(new Image(4, 4, new byte[64])).setPreferredSize(40, 40);
            view.setDragEnabled(true);
            show(view);
            Drag free = view.onDragStart(at(view));
            assertNotNull(free);
            assertFalse(free.isWithinWindow());

            view.setDragWithinWindow(true);
            Drag kept = view.onDragStart(at(view));
            assertNotNull(kept);
            assertTrue(kept.isWithinWindow());
        }
    }
}

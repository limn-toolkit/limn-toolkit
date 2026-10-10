package limn.demo.site;

import limn.backend.Clipboard;
import limn.components.ListView;
import limn.components.tree.Tree;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import limn.transfer.DataFormat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the drag-and-drop guide says its examples do: a row moved by the keyboard is moved in the
 * application's list, a node is taken out of its folder, a card dragged to a lane arrives there
 * and leaves where it was, and a copy is read back in the form asked for.
 */
class DragExampleTest {

    private static final int MOVE = Keys.MOD_ALT | Keys.MOD_SHIFT;

    /** Its children side by side, each at the size it asks for. */
    private static final class Side extends Widget<Side> {
        Side(Widget<?>... children) {
            for (Widget<?> child : children) {
                add(child);
            }
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            float x = 0;
            for (Widget<?> child : children()) {
                Size size = child.measure(Constraints.loose(width(), height()));
                child.layoutBox(x, 0, size.width(), size.height());
                x += size.width();
            }
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
        }
    }

    private static Scene show(Widget<?> root) {
        Scene scene = new Scene(root);
        scene.setTextRuler(TestRulers.FIXED);
        scene.layoutPass(600, 300);
        scene.renderFrame(new NoopCanvas(600, 300));
        return scene;
    }

    private static void key(Scene scene, int key, int modifiers) {
        drive(scene).keyEvent(key, 0, true, false, modifiers);
        drive(scene).keyEvent(key, 0, false, false, modifiers);
        drive(scene).inputBatchEnded();
    }

    @Test
    void altShiftWithAnArrowMovesTheRowInTheApplicationsOwnList() {
        try (HeadlessUi ui = new HeadlessUi()) {
            List<DragExample.Field> fields = DragExample.available();
            DragExample.Field first = fields.get(0);
            ListView<DragExample.Field> list = DragExample.reorderable(fields);
            Scene scene = show(list);
            scene.requestFocus(list);
            list.setSelectedIndex(0);

            key(scene, Keys.DOWN, MOVE);
            key(scene, Keys.DOWN, MOVE);
            assertEquals(2, fields.indexOf(first));
            assertEquals(first, list.selectedItem(), "the row moved stays the row selected");
        }
    }

    @Test
    void altShiftLeftTakesANodeOutOfItsFolder() {
        try (HeadlessUi ui = new HeadlessUi()) {
            DragExample.Entry leaf = new DragExample.Entry("Open invoices");
            DragExample.Entry folder = new DragExample.Entry("Reports", leaf);
            DragExample.Entry root = new DragExample.Entry("", folder);
            Tree<DragExample.Entry> tree = DragExample.outline(root);
            Scene scene = show(tree);
            tree.expand(folder);
            tree.setSelected(leaf);
            scene.layoutPass(600, 300);
            scene.requestFocus(tree);

            key(scene, Keys.LEFT, MOVE);
            assertEquals(List.of(folder, leaf), root.children, "beside the folder it was in, after it");
            assertTrue(folder.children.isEmpty());
        }
    }

    @Test
    void aCardDraggedToALaneArrivesThereAndLeavesWhereItWas() {
        try (HeadlessUi ui = new HeadlessUi()) {
            DragExample.Order order = new DragExample.Order(42, "Meridian");
            List<DragExample.Order> board = new ArrayList<>(List.of(order));
            List<DragExample.Order> shipped = new ArrayList<>();
            DragExample.OrderCard card = new DragExample.OrderCard(order, board::remove);
            DragExample.Lane lane = new DragExample.Lane("Shipped", shipped);
            Scene scene = show(new Side(card, lane));

            float toX = card.width() + lane.width() / 2;
            drive(scene).mouseMoved(20, 20);
            drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 20, 20, 1);
            drive(scene).inputBatchEnded();
            drive(scene).mouseMoved(60, 30);
            drive(scene).inputBatchEnded();
            assertTrue(scene.isDragging());
            drive(scene).mouseMoved(toX, 40);
            drive(scene).inputBatchEnded();
            drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, toX, 40, 1);
            drive(scene).inputBatchEnded();

            assertEquals(List.of(order), shipped);
            assertTrue(board.isEmpty(), "a move: the source removes its own");
        }
    }

    @Test
    void aCopyIsReadBackInTheFormAskedFor() {
        try (HeadlessUi ui = new HeadlessUi()) {
            Clipboard clipboard = Clipboard.inMemory();
            int[] rendered = {0};
            limn.graphics.Image chart = new limn.graphics.Image(1, 1, new byte[4]);
            DragExample.copyLazily(clipboard, "Sales", () -> {
                rendered[0]++;
                return chart;
            });
            assertEquals(0, rendered[0], "nobody has asked for the picture yet");
            assertEquals("Sales", clipboard.text());

            List<Object> pasted = new ArrayList<>();
            DragExample.paste(clipboard, pasted::add, pasted::add);
            assertEquals(List.of(chart), pasted, "the picture first, where there is one");
            assertEquals(1, rendered[0]);
            assertTrue(clipboard.content().has(DataFormat.TEXT));
        }
    }
}

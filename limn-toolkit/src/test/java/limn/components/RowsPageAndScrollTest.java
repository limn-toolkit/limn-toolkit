package limn.components;

import limn.components.tree.Tree;
import limn.graphics.Canvas;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the list and the tree took from the table when the three came to walk their rows with one
 * engine: Page Down and Page Up move the view with the cursor, which keeps its place in the box,
 * and a watcher is told of a scroll and who made it.
 */
class RowsPageAndScrollTest {

    private static final float ROW = 20;

    /** A row a fixed height tall. */
    private static final class Cell extends Widget<Cell> {
        int number = -1;

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), ROW);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }
    }

    private static Scene show(Widget<?> widget) {
        Scene scene = new Scene(widget);
        scene.setTextRuler(TestRulers.FIXED);
        scene.layoutPass(200, 100); // five rows
        scene.renderFrame(new NoopCanvas(200, 100));
        return scene;
    }

    private static void key(Scene scene, int key) {
        drive(scene).keyEvent(key, 0, true, false, 0);
        drive(scene).keyEvent(key, 0, false, false, 0);
        drive(scene).inputBatchEnded();
        scene.layoutPass(200, 100);
    }

    private static List<Integer> hundred() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            items.add(i);
        }
        return items;
    }

    @Test
    void pagingAListMovesTheViewWithTheCursor() {
        try (HeadlessUi ui = new HeadlessUi()) {
            ListView<Integer> list = new ListView<Integer>(item -> new Cell()).setItems(hundred());
            Scene scene = show(list);
            scene.requestFocus(list);
            list.setSelectedIndex(2);

            key(scene, Keys.PAGE_DOWN);
            assertEquals(7, list.selectedIndex());
            assertEquals(5, list.firstVisibleIndex(), "the cursor is still the third row of the box");

            key(scene, Keys.PAGE_DOWN);
            key(scene, Keys.PAGE_UP);
            assertEquals(7, list.selectedIndex());
            assertEquals(5, list.firstVisibleIndex(), "and a page back is where it was");

            key(scene, Keys.PAGE_UP);
            key(scene, Keys.PAGE_UP);
            assertEquals(0, list.selectedIndex());
            assertEquals(0, list.firstVisibleIndex(), "the view stops at the first row");
        }
    }

    @Test
    void aWatcherOfAListIsToldOfAScrollAndWhoMadeIt() {
        try (HeadlessUi ui = new HeadlessUi()) {
            ListView<Integer> list = new ListView<Integer>(item -> new Cell()).setItems(hundred());
            Scene scene = show(list);
            List<String> heard = new ArrayList<>();
            list.observeChanges((widget, change) -> {
                if (change.aspect() == Change.Aspect.VALUE) {
                    heard.add(change.origin().toString());
                }
            });

            list.scrollBy(30);
            drive(scene).scrolled(0, -1, 50, 50);
            drive(scene).inputBatchEnded();
            assertEquals(List.of("CODE", "USER"), heard);

            heard.clear();
            list.scrollBy(-10_000);
            list.scrollBy(-10);
            assertEquals(List.of("CODE"), heard, "at the start nothing moved and nothing is told");
        }
    }

    /** A node of a flat outline of a hundred leaves under no folder. */
    private record Leaf(int number) {
    }

    @Test
    void pagingATreeMovesTheViewWithTheCursorAndAWatcherHearsAScroll() {
        try (HeadlessUi ui = new HeadlessUi()) {
            List<Leaf> leaves = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                leaves.add(new Leaf(i));
            }
            Tree<Leaf> tree = new Tree<>(new Tree.Model<Leaf>() {
                @Override
                public List<Leaf> roots() {
                    return leaves;
                }

                @Override
                public List<Leaf> children(Leaf node) {
                    return List.of();
                }

                @Override
                public Widget<?> cellFor(Leaf node) {
                    Cell cell = new Cell();
                    cell.number = node.number();
                    return cell;
                }
            });
            Scene scene = show(tree);
            scene.requestFocus(tree);
            tree.setSelected(leaves.get(2));
            scene.layoutPass(200, 100);
            List<String> heard = new ArrayList<>();
            tree.observeChanges((widget, change) -> {
                if (change.aspect() == Change.Aspect.VALUE) {
                    heard.add(change.origin().toString());
                }
            });

            key(scene, Keys.PAGE_DOWN);
            assertEquals(List.of(leaves.get(7)), tree.selectedNodes());
            Cell seven = null;
            for (Widget<?> child : tree.children()) {
                if (child instanceof Cell cell && cell.number == 7) {
                    seven = cell;
                }
            }
            assertEquals(2 * ROW, seven.y(), "the cursor is still the third row of the box");

            tree.scrollBy(0, 30);
            assertEquals(List.of("CODE"), heard, "told of the scroll, and who made it");
        }
    }
}

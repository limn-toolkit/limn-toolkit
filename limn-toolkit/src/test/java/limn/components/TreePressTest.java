package limn.components;

import limn.components.tree.Tree;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A press on a tree that does not sit at the scene's origin, which is every tree in a window.
 *
 * <p>A pointer event arrives in scene coordinates, and a tree that reads them as its own lands a
 * press on the row that many points further down and misses every triangle by its own offset.
 * {@code TreeTest} mounts the tree as the scene's root, where the two coordinate systems are the
 * same one, so nothing there could tell; here the tree is inset on both axes by more than a row.
 */
class TreePressTest extends ComponentTestBase {

    private static final float ROW_HEIGHT = 40;
    /** More than a row on each axis, so a press read in the wrong coordinates finds another row. */
    private static final float INSET = 60;
    private static final float TREE_W = 220;
    private static final float TREE_H = 200;

    private record Node(String name, List<Node> children) {
        static Node leaf(String name) {
            return new Node(name, List.of());
        }
    }

    /** A row of fixed height that paints nothing, so a row's index is arithmetic on its y. */
    private static final class Cell extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), ROW_HEIGHT);
        }
    }

    private Scene scene;

    private Tree<Node> inset(List<Node> roots) {
        Tree<Node> tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return roots;
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public Widget cellFor(Node node) {
                return new Cell();
            }
        });
        scene = new Scene(new Padding(Insets.all(INSET), tree));
        scene.setTextRuler(RULER);
        scene.layoutPass(TREE_W + 2 * INSET, TREE_H + 2 * INSET);
        scene.renderFrame(new FakeCanvas(TREE_W + 2 * INSET, TREE_H + 2 * INSET));
        assertEquals(INSET, tree.localToSceneX(), 1e-3f, "the fixture has to offset the tree");
        assertEquals(INSET, tree.localToSceneY(), 1e-3f);
        return tree;
    }

    private void pressAtLocal(Tree<Node> tree, float localX, float localY) {
        float x = tree.localToSceneX() + localX;
        float y = tree.localToSceneY() + localY;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(TREE_W + 2 * INSET, TREE_H + 2 * INSET);
    }

    @Test
    void aPressSelectsTheRowUnderThePointerWhereverTheTreeSits() {
        Node one = Node.leaf("one");
        Node two = Node.leaf("two");
        Tree<Node> tree = inset(List.of(one, two, Node.leaf("three"), Node.leaf("four")));

        pressAtLocal(tree, TREE_W / 2, ROW_HEIGHT + ROW_HEIGHT / 2);

        assertEquals(List.of(two), tree.selectedNodes(),
                "the second row is under the pointer; a press read in scene coordinates lands "
                        + INSET + " points further down");
    }

    @Test
    void aPressOnATriangleOpensItsRowWhereverTheTreeSits() {
        Node folder = new Node("folder", List.of(Node.leaf("inside")));
        Tree<Node> tree = inset(List.of(folder, Node.leaf("after"), Node.leaf("last")));
        Widget cell = null;
        for (Widget child : tree.children()) {
            if (child instanceof Cell found) {
                cell = found;
                break;
            }
        }
        assertTrue(cell != null && cell.x() > 0, "a root's cell starts after its triangle band");

        // The band is everything before the cell at depth zero, so its middle is the triangle.
        pressAtLocal(tree, cell.x() / 2, ROW_HEIGHT / 2);

        assertTrue(tree.isExpanded(folder),
                "the press was on the triangle; read in scene coordinates it misses the band by "
                        + INSET + " points and selects a row further down instead");
    }
}

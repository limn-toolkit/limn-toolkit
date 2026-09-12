package limn.components;

import limn.components.tree.Tree;
import limn.concurrent.Ui;
import limn.concurrent.Work;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three things a tree does that a list cannot: an order that is a traversal, a row that opens,
 * and a row that promises children before it can name them (ADR 044).
 *
 * <p>Every case reads the widget's own answers — which rows are visible, what is selected, what
 * the model was asked — rather than pixels: what is drawn at a depth is the indent arithmetic,
 * and what a reader hears is {@code TreeAccessibilityTest}'s.
 */
class TreeTest extends ComponentTestBase {

    /** A node of the test forest: a name and the children the model will admit to. */
    private record Node(String name, List<Node> children) {
        static Node leaf(String name) {
            return new Node(name, List.of());
        }

        static Node of(String name, Node... kids) {
            return new Node(name, List.of(kids));
        }
    }

    /** Counts what the tree asked for, so a case can assert the model is not re-walked per frame. */
    private static final class CountingModel implements Tree.Model<Node> {
        final List<Node> roots;
        final List<String> cellsBuilt = new ArrayList<>();
        /** Nodes whose children this model refuses to answer until {@link #loads} is consulted. */
        final Map<String, List<Node>> loads;
        int loadCalls;

        CountingModel(List<Node> roots) {
            this(roots, Map.of());
        }

        CountingModel(List<Node> roots, Map<String, List<Node>> loads) {
            this.roots = roots;
            this.loads = loads;
        }

        @Override
        public List<Node> roots() {
            return roots;
        }

        @Override
        public List<Node> children(Node node) {
            // A node named in `loads` says "not known yet", which is what makes it non-leaf and
            // sends the tree to load().
            return loads.containsKey(node.name()) ? null : node.children();
        }

        @Override
        public Work<List<Node>> load(Node node) {
            loadCalls++;
            List<Node> kids = loads.get(node.name());
            return Ui.work(progress -> kids);
        }

        @Override
        public Widget cellFor(Node node) {
            cellsBuilt.add(node.name());
            Label cell = new Label(node.name());
            return cell;
        }
    }

    private Scene scene;
    private RecordingTestCanvas canvas;

    private Tree<Node> mount(Tree.Model<Node> model) {
        Tree<Node> tree = new Tree<>(model);
        scene = new Scene(tree);
        scene.setTextRuler(RULER);
        scene.layoutPass(220, 200);
        canvas = new RecordingTestCanvas(220, 200);
        scene.renderFrame(canvas);
        return tree;
    }

    /** The names of the rows the tree is showing, top to bottom. */
    private static List<String> visible(Tree<Node> tree, CountingModel model) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tree.visibleRowCount(); i++) {
            names.add(model.cellsBuilt.isEmpty() ? "?" : "");
        }
        return names;
    }

    private static Node forest() {
        return Node.of("root",
                Node.of("docs", Node.leaf("a.md"), Node.leaf("b.md")),
                Node.leaf("readme"));
    }

    @Test
    void aClosedTreeShowsItsRootsAndNothingUnderThem() {
        CountingModel model = new CountingModel(List.of(forest()));
        Tree<Node> tree = mount(model);

        assertEquals(1, tree.visibleRowCount(), "only the root is visible while it is closed");
        assertFalse(tree.isExpanded(forest()), "nothing opens itself");
    }

    @Test
    void openingARowInsertsItsChildrenInTraversalOrder() {
        Node root = forest();
        CountingModel model = new CountingModel(List.of(root));
        Tree<Node> tree = mount(model);

        tree.expand(root);
        scene.layoutPass(220, 200);

        assertEquals(3, tree.visibleRowCount(),
                "the root and its two children, in the order the model gave them");
        assertTrue(tree.isExpanded(root));

        tree.expand(root.children().get(0)); // docs
        scene.layoutPass(220, 200);
        assertEquals(5, tree.visibleRowCount(),
                "a grandchild is a row of the same list: the order is a traversal, not a level");

        tree.collapse(root);
        scene.layoutPass(220, 200);
        assertEquals(1, tree.visibleRowCount(),
                "closing an ancestor takes every descendant with it, whatever their own state");
    }

    @Test
    void aRowWhoseChildrenAreNotKnownIsNotALeafAndLoadsWhenItOpens() {
        Node lazy = new Node("remote", List.of());
        CountingModel model = new CountingModel(List.of(lazy),
                Map.of("remote", List.of(Node.leaf("one"), Node.leaf("two"))));
        Tree<Node> tree = mount(model);

        assertEquals(1, tree.visibleRowCount());
        assertEquals(0, model.loadCalls, "nothing is fetched until the row is opened");

        tree.expand(lazy);
        ui.pumpUntil(() -> tree.visibleRowCount() == 3);
        scene.layoutPass(220, 200);

        assertEquals(1, model.loadCalls, "opened once, fetched once");
        assertEquals(3, tree.visibleRowCount(), "the children arrived and became rows");

        tree.collapse(lazy);
        tree.expand(lazy);
        ui.pumpUntil(() -> tree.visibleRowCount() == 3);
        assertEquals(1, model.loadCalls,
                "what a load produced is kept: a second opening costs nothing");
    }

    @Test
    void theArrowsOpenStepInCloseAndStepOut() {
        Node root = forest();
        Node docs = root.children().get(0);
        CountingModel model = new CountingModel(List.of(root));
        Tree<Node> tree = mount(model);
        scene.requestFocus(tree);

        press(Keys.DOWN);   // onto the root
        assertEquals(root, tree.leadNode(), "the first arrow lands on the first row");

        press(Keys.RIGHT);  // opens it
        assertTrue(tree.isExpanded(root), "Right opens a closed row");
        assertEquals(root, tree.leadNode(), "and stays on it");

        press(Keys.RIGHT);  // steps into it
        assertEquals(docs, tree.leadNode(), "Right again steps into an open row");

        press(Keys.LEFT);   // docs is closed, so this goes to the parent
        assertEquals(root, tree.leadNode(), "Left on a closed row goes to its parent");

        press(Keys.LEFT);   // closes the root
        assertFalse(tree.isExpanded(root), "Left on an open row closes it");
    }

    @Test
    void aSelectionSurvivesTheRowBeingHiddenByACollapse() {
        Node root = forest();
        Node readme = root.children().get(1);
        CountingModel model = new CountingModel(List.of(root));
        Tree<Node> tree = mount(model);

        tree.expand(root);
        scene.layoutPass(220, 200);
        tree.setSelected(readme);
        assertEquals(List.of(readme), tree.selectedNodes());

        tree.collapse(root);
        scene.layoutPass(220, 200);
        assertEquals(List.of(readme), tree.selectedNodes(),
                "a row hidden by a collapse is still in the tree; re-opening finds it selected");

        tree.expand(root);
        scene.layoutPass(220, 200);
        assertEquals(List.of(readme), tree.selectedNodes());
    }

    @Test
    void aRefreshDropsWhatTheModelNoLongerHas() {
        Node keep = Node.leaf("keep");
        Node drop = Node.leaf("drop");
        List<Node> roots = new ArrayList<>(List.of(keep, drop));
        CountingModel model = new CountingModel(roots);
        Tree<Node> tree = mount(model);

        tree.setSelected(drop);
        assertEquals(List.of(drop), tree.selectedNodes());

        roots.remove(drop);
        tree.refresh();
        scene.layoutPass(220, 200);

        assertEquals(1, tree.visibleRowCount());
        assertTrue(tree.selectedNodes().isEmpty(),
                "a node the model no longer has is not a selection");
        assertNull(tree.leadNode(), "and the cursor does not stand on it either");
    }

    /**
     * The two gestures the damage ratchet drives, asserted here first: a key with the focus on
     * the tree and nothing else, and a click at the middle of the box. Both must reach a row —
     * a gesture that reaches nothing repaints nothing, and a ceiling over it asserts nothing.
     */
    @Test
    void aKeyAndAClickWithNothingSelectedBothReachARow() {
        Node root = forest();
        CountingModel model = new CountingModel(List.of(root, Node.leaf("two"),
                Node.leaf("three"), Node.leaf("four"), Node.leaf("five"), Node.leaf("six")));
        Tree<Node> tree = mount(model);
        scene.requestFocus(tree);

        press(Keys.DOWN);
        assertEquals(root, tree.leadNode(), "the first arrow has to land somewhere");

        press(Keys.RIGHT);
        assertTrue(tree.isExpanded(root), "Right on a closed, expandable row opens it");

        float midX = tree.width() / 2;
        float midY = tree.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0,
                tree.localToSceneX() + midX, tree.localToSceneY() + midY);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0,
                tree.localToSceneX() + midX, tree.localToSceneY() + midY);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);

        assertFalse(tree.selectedNodes().isEmpty(),
                "a click at the middle of the box has to land on a row: rows are "
                        + tree.visibleRowCount() + " over " + tree.height() + " points");
    }

    /**
     * A row is whatever widget the model hands back, and the tree stretches it to the width the
     * indent leaves: an icon before the text and a badge against the trailing edge are the
     * application's own composition, not a feature this widget has to grow.
     *
     * <p>The two things the tree owes such a cell, and the two this pins: the cell is laid out at
     * exactly the remaining width, so an {@code Expanded} in the middle really does push a badge
     * to the edge; and it starts after the indent, so a child's badge is not where its parent's
     * is.
     */
    @Test
    void aRowIsWhateverWidgetTheModelBuildsAndGetsTheWidthTheIndentLeaves() {
        Node root = Node.of("root", Node.leaf("child"));
        List<Label> badges = new ArrayList<>();
        Tree.Model<Node> composed = new Tree.Model<>() {
            @Override
            public List<Node> roots() {
                return List.of(root);
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public Widget cellFor(Node node) {
                Label text = new Label(node.name());
                text.setIcon(null); // an icon would rasterize, and this harness has no rasterizer
                Label badge = new Label("3");
                badges.add(badge);
                limn.scene.layout.Row row = new limn.scene.layout.Row();
                row.gap(6).crossAlignment(limn.scene.layout.Flex.CrossAlignment.CENTER);
                row.add(limn.scene.layout.Expanded.of(text));
                row.add(badge);
                return row;
            }
        };

        Tree<Node> tree = new Tree<>(composed);
        scene = new Scene(tree);
        scene.setTextRuler(RULER);
        scene.layoutPass(220, 200);
        canvas = new RecordingTestCanvas(220, 200);
        scene.renderFrame(canvas);
        tree.expand(root);
        scene.layoutPass(220, 200);

        assertEquals(2, tree.visibleRowCount());
        assertEquals(2, badges.size(), "one cell per realized row, built by the model");

        float rowRight = badges.get(0).localToSceneX() + badges.get(0).width();
        float childRight = badges.get(1).localToSceneX() + badges.get(1).width();
        assertEquals(rowRight, childRight, 0.5f,
                "both badges sit against the same trailing edge: the cell is stretched to the "
                        + "width the indent leaves, whatever the row's depth");

        float parentLeft = badges.get(0).parent().localToSceneX();
        float childLeft = badges.get(1).parent().localToSceneX();
        assertTrue(childLeft > parentLeft,
                "and a child's cell starts further in than its parent's: " + childLeft
                        + " against " + parentLeft);
    }

    /**
     * The wheel scrolls the tree, and a tree with nothing to scroll lets the notch through.
     *
     * <p>It is the gesture every other scrolling widget in this toolkit answers to, and this one
     * did not: the bar was there and worked under a drag, and a flick over the rows did nothing.
     * Found by him asking whether the scroll was native, which is the question a widget cannot
     * answer about itself.
     */
    @Test
    void theWheelScrollsAndAShortTreeDoesNotSwallowTheNotch() {
        List<Node> many = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            many.add(Node.leaf("row " + i));
        }
        Map<String, Widget> cells = new java.util.HashMap<>();
        Tree.Model<Node> model = new Tree.Model<>() {
            @Override
            public List<Node> roots() {
                return many;
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public Widget cellFor(Node node) {
                Label cell = new Label(node.name());
                cells.put(node.name(), cell);
                return cell;
            }
        };

        Tree<Node> tree = new Tree<>(model);
        scene = new Scene(tree);
        scene.setTextRuler(RULER);
        scene.layoutPass(220, 120);
        canvas = new RecordingTestCanvas(220, 120);
        scene.renderFrame(canvas);

        float before = cells.get("row 1").y();
        wheel(tree, -3);
        scene.layoutPass(220, 120);
        Widget first = cells.get("row 1");
        assertTrue(first.y() < before,
                "the wheel has to move the rows: row 1 sat at " + before + " and is at "
                        + first.y());

        // A tree shorter than its viewport keeps its hands off the notch, so a scroll view
        // holding one still scrolls.
        Tree<Node> shortTree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(Node.leaf("only"));
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public Widget cellFor(Node node) {
                return new Label(node.name());
            }
        });
        Scene small = new Scene(shortTree);
        small.setTextRuler(RULER);
        small.layoutPass(220, 200);
        small.renderFrame(new RecordingTestCanvas(220, 200));
        small.scrolled(0, -3, shortTree.localToSceneX() + 10, shortTree.localToSceneY() + 10);
        small.inputBatchEnded();
        assertEquals(1, shortTree.visibleRowCount(), "nothing moved, and nothing was consumed");
    }

    /** A chain of {@code levels} nodes, one child each: the shape that runs out of width. */
    private static Node chain(int levels) {
        Node node = Node.leaf("level-" + levels);
        for (int i = levels - 1; i >= 1; i--) {
            node = Node.of("level-" + i, node);
        }
        return node;
    }

    /** The chain, open to the bottom, in a scene 220 wide; the cells are collected by name. */
    private Tree<Node> openedChain(int levels, Map<String, Widget> cells) {
        Node top = chain(levels);
        Tree<Node> tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(top);
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public Widget cellFor(Node node) {
                Label cell = new Label(node.name());
                cells.put(node.name(), cell);
                return cell;
            }
        });
        scene = new Scene(tree);
        scene.setTextRuler(RULER);
        for (Node node = top; node != null;
                node = node.children().isEmpty() ? null : node.children().get(0)) {
            tree.expand(node);
        }
        scene.layoutPass(220, 200);
        canvas = new RecordingTestCanvas(220, 200);
        scene.renderFrame(canvas);
        return tree;
    }

    /**
     * Depth is the thing a list never has to scroll for: every level charges an indent and
     * nothing gives it back, so the outline outgrows its box sideways while still fitting in it
     * vertically.
     */
    @Test
    void aDeepBranchOutgrowsTheBoxSidewaysAndTheWheelWalksIt() {
        Map<String, Widget> cells = new java.util.HashMap<>();
        Tree<Node> tree = openedChain(14, cells);

        Widget deepest = cells.get("level-14");
        float before = deepest.x();
        wheelSideways(tree, -1);
        scene.layoutPass(220, 200);
        assertTrue(deepest.x() < before,
                "a sideways notch has to walk the outline: the deepest cell sat at " + before
                        + " and is at " + deepest.x());

        // And it stops at the end rather than scrolling into nothing.
        for (int i = 0; i < 40; i++) {
            wheelSideways(tree, -1);
        }
        scene.layoutPass(220, 200);
        float atEnd = deepest.x();
        wheelSideways(tree, -1);
        scene.layoutPass(220, 200);
        assertEquals(atEnd, deepest.x(), 0.01f, "the offset is clamped to the content's end");
    }

    /**
     * The shallow case is the one every existing capture and every existing cell width depends
     * on: with nothing deep the content is exactly the box, so there is nothing to scroll and a
     * cell is measured at the width it always was — which is what keeps its ellipsis on the box's
     * edge (ADR 044 §1, amended).
     */
    @Test
    void aShallowTreeIsExactlyItsBoxSoNothingMovesSideways() {
        Map<String, Widget> cells = new java.util.HashMap<>();
        Tree<Node> tree = openedChain(2, cells);

        Widget cell = cells.get("level-1");
        float before = cell.x();
        float width = cell.width();
        wheelSideways(tree, -3);
        scene.layoutPass(220, 200);
        assertEquals(before, cell.x(), 0.01f, "a shallow tree has nowhere to go sideways");
        assertEquals(width, cell.width(), 0.01f, "and its cells keep the width they had");
    }

    /**
     * A triangle painted where the hit test does not look is a control that cannot be pressed,
     * and a sideways offset is exactly the kind of change that separates the two. The band sits
     * immediately before the cell, so the press is aimed from the cell's own position rather than
     * by re-deriving the indent here.
     */
    /** Where each stroked path was painted, which for these fixtures is only the triangles. */
    private static final class TwistyCanvas extends ComponentTestBase.FakeCanvas {

        /** The leading point of one painted path, in the tree's own coordinates. */
        record Painted(float x, float y) {
        }

        final List<Painted> twisties = new ArrayList<>();

        TwistyCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawPath(Path2D path, float strokeWidth, Paint paint) {
            float[] leading = {Float.MAX_VALUE, 0};
            path.flatten(0.05f, new Path2D.Flattened() {
                @Override
                public void moveTo(float x, float y) {
                    at(x, y);
                }

                @Override
                public void lineTo(float x, float y) {
                    at(x, y);
                }

                @Override
                public void closePath() {
                }

                private void at(float x, float y) {
                    if (x < leading[0]) {
                        leading[0] = x;
                        leading[1] = y;
                    }
                }
            });
            twisties.add(new Painted(leading[0], leading[1]));
        }
    }

    /**
     * A triangle painted where the press is not looked for is a control nobody can open, and a
     * sideways offset applied to one and not the other is exactly how that happens — it did
     * happen here, and a still caught it after a test aimed from the cell's own position had
     * passed. So this one aims the press at the <b>painted</b> triangle.
     */
    @Test
    void theTriangleIsPaintedWhereThePressIsLookedForAfterAScrollSideways() {
        Map<String, Widget> cells = new java.util.HashMap<>();
        Tree<Node> tree = openedChain(14, cells);

        Widget cell = cells.get("level-5");
        wheelSideways(tree, -1);
        scene.layoutPass(220, 200);

        TwistyCanvas painted = new TwistyCanvas(220, 200);
        scene.requestRender(); // a settled frame damages nothing, and would record nothing
        scene.renderFrame(painted);

        float rowMid = cell.y() + cell.height() / 2;
        float triangleX = Float.NaN;
        for (TwistyCanvas.Painted mark : painted.twisties) {
            if (Math.abs(mark.y() - rowMid) <= cell.height() / 2) {
                triangleX = mark.x();
                break;
            }
        }
        assertTrue(!Float.isNaN(triangleX), "the row's triangle has to be painted at all");

        float x = tree.localToSceneX() + triangleX + 2;
        float y = tree.localToSceneY() + rowMid;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);

        assertTrue(tree.visibleRowCount() < 14,
                "pressing the triangle where it is drawn has to close the row: "
                        + tree.visibleRowCount() + " rows are still visible");
    }

    private void wheelSideways(Tree<Node> tree, float notches) {
        float x = tree.localToSceneX() + tree.width() / 2;
        float y = tree.localToSceneY() + tree.height() / 2;
        scene.mouseMoved(x, y);
        scene.scrolled(notches, 0, x, y);
        scene.inputBatchEnded();
    }

    private void wheel(Tree<Node> tree, float notches) {
        float x = tree.localToSceneX() + tree.width() / 2;
        float y = tree.localToSceneY() + tree.height() / 2;
        scene.mouseMoved(x, y);
        scene.scrolled(0, notches, x, y);
        scene.inputBatchEnded();
    }

    private void press(int key) {
        scene.keyEvent(key, true, false, 0);
        scene.keyEvent(key, false, false, 0);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);
    }
}

package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ScrollFacet;
import limn.components.tree.Tree;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Tree} becomes in the accessible tree: one {@code TREE} node over the rows it
 * realized, each a {@code TREE_ITEM} numbered in traversal order and carrying whether it is open,
 * and a scroll facet that reports both axes.
 *
 * <p>The horizontal half of that facet is what this class was written for. Until the outline
 * could scroll sideways the tree published a width axis that could not move, hard-coded as
 * nowhere to go; now it publishes its own offset over the width the deepest row asks for. Nothing
 * asserted either, so the cases below pin the two ends of the change — a shallow tree is still
 * exactly its box, and a deep one scrolls and reaches one — and that the vertical half, which the
 * change sat beside, still says what it said before.
 *
 * <p>Geometry is read off what the scene published rather than re-derived from the indent: every
 * row's cell is laid out to the content's far edge, so where a row's box ends <em>is</em> how
 * wide the content is, less the sideways offset. Right to left the far edge is the left one and
 * every row starts there instead. The cases assert whichever invariant they rely on first.
 *
 * <p>The roles are ADR 044 §4's step 1b, published once the AT-SPI numbers for {@code TREE} and
 * {@code TREE_ITEM} came off the Fedora guest; before that the tree said {@code LIST} and
 * {@code LIST_ITEM}. Step 1b did not bring depth or position-in-level, so a row is still numbered
 * in traversal order against every visible row, not among its siblings. The role case pins that
 * too, and if the numbering ever moves to a level, it is the case that has to change,
 * deliberately.
 */
class TreeAccessibilityTest extends AccessibleComponentTestBase {

    private static final double EPS = 1e-6;

    /** The box the tree is given inside the 400 &times; 300 window: narrow enough to run out. */
    private static final float BOX_W = 220;
    private static final float BOX_H = 300;

    /** Rows of this height fit fourteen deep inside {@link #BOX_H}, so the chain scrolls one way. */
    private static final float ROW_H = 20;

    /** How deep a chain has to go before its indent outgrows {@link #BOX_W}. */
    private static final int DEEP = 14;

    // ------------------------------------------------------------------------------ the fixture

    /** A node of the test outline, whose accessible name is a string it holds, not builds. */
    private record Node(I18nString name, List<Node> children) {
        static Node leaf(String name) {
            return new Node(I18nString.literal(name), List.of());
        }

        static Node of(String name, Node... kids) {
            return new Node(I18nString.literal(name), List.of(kids));
        }
    }

    /** A cell of a fixed height that says nothing about itself, so the tree's hook is all there is. */
    private static final class Cell extends Widget {
        private final float rowHeight;

        Cell(float rowHeight) {
            this.rowHeight = rowHeight;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), rowHeight);
        }
    }

    private static final class Outline implements Tree.Model<Node> {
        private final List<Node> roots;
        private final float rowHeight;

        Outline(float rowHeight, List<Node> roots) {
            this.roots = roots;
            this.rowHeight = rowHeight;
        }

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
            return new Cell(rowHeight);
        }

        @Override
        public I18nString nameOf(Node node) {
            return node.name();
        }
    }

    private Tree<Node> tree;

    /** Binds a tree over {@code roots} into a {@link #BOX_W} &times; {@link #BOX_H} box. */
    private Tree<Node> bindTree(float rowHeight, List<Node> roots) {
        return bindTree(rowHeight, roots, LayoutDirection.LTR);
    }

    /** {@link #bindTree} with a layout direction set on the root before the first frame. */
    private Tree<Node> bindTree(float rowHeight, List<Node> roots, LayoutDirection direction) {
        tree = new Tree<>(new Outline(rowHeight, roots));
        Column root = new Column();
        root.setLayoutDirection(direction);
        root.add(new SizedBox(BOX_W, BOX_H, tree));
        bind(root);
        assertEquals(BOX_W, tree.width(), "the fixture really did size the tree");
        assertEquals(BOX_H, tree.height());
        return tree;
    }

    /** A chain of {@code levels} nodes, one child each: the shape that runs out of width. */
    private static Node chain(int levels) {
        Node node = Node.leaf("level-" + levels);
        for (int i = levels - 1; i >= 1; i--) {
            node = Node.of("level-" + i, node);
        }
        return node;
    }

    /** The chain, bound and opened to the bottom. */
    private Tree<Node> bindOpenChain(int levels, float rowHeight) {
        return bindOpenChain(levels, rowHeight, LayoutDirection.LTR);
    }

    /** {@link #bindOpenChain} in a layout direction. */
    private Tree<Node> bindOpenChain(int levels, float rowHeight, LayoutDirection direction) {
        Node top = chain(levels);
        bindTree(rowHeight, List.of(top), direction);
        for (Node node = top; !node.children().isEmpty(); node = node.children().get(0)) {
            tree.expand(node);
        }
        frame();
        assertEquals(levels, tree.visibleRowCount(), "the whole chain is open");
        return tree;
    }

    /** @return the one tree node in the current accessible tree */
    private AccessibleNode treeNode() {
        return node(Accessible.Role.TREE);
    }

    /** @return the tree's children that carry a member-of-a-selection facet, in tree order */
    private List<AccessibleNode> rowNodes() {
        List<AccessibleNode> found = new ArrayList<>();
        for (AccessibleNode child : childrenOf(treeNode())) {
            if (child.selectionItem() != null) {
                found.add(child);
            }
        }
        return found;
    }

    /** Where a published row's box starts, measured from the tree's own left edge. */
    private double rowStart(AccessibleNode row) {
        return row.x() - tree.localToSceneX();
    }

    /** Where a published row's box ends, measured from the tree's own left edge. */
    private double rowEnd(AccessibleNode row) {
        return rowStart(row) + row.width();
    }

    /**
     * Where every published row ends, asserted to be one place: left to right a cell is laid out
     * from its indent to the content's far edge, so that place is the content's width less the
     * offset.
     */
    private double commonRowEnd() {
        return commonRowEdge(this::rowEnd);
    }

    /**
     * Where every published row starts, asserted to be one place: right to left the content's far
     * edge is its left one, and every cell runs from there to its indent.
     */
    private double commonRowStart() {
        return commonRowEdge(this::rowStart);
    }

    private double commonRowEdge(ToDoubleFunction<AccessibleNode> edge) {
        List<AccessibleNode> rows = rowNodes();
        assertFalse(rows.isEmpty(), describe(tree()));
        double shared = edge.applyAsDouble(rows.get(0));
        for (AccessibleNode row : rows) {
            assertEquals(shared, edge.applyAsDouble(row), 1e-3,
                    "every row runs to the content's far edge, whatever its depth: "
                            + describe(tree()));
        }
        return shared;
    }

    private static List<Node> leaves(int count) {
        List<Node> roots = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            roots.add(Node.leaf("row " + i));
        }
        return roots;
    }

    // --------------------------------------------------------------------- the horizontal axis

    @Test
    void aShallowTreeIsExactlyItsBoxAndSaysItCannotScrollSideways() {
        bindOpenChain(2, ROW_H);

        assertEquals(BOX_W, commonRowEnd(), 1e-3,
                "with nothing deep the content is the box, so the maximum offset is zero: "
                        + describe(tree()));
        ScrollFacet atRest = treeNode().scroll();
        assertNotNull(atRest, describe(tree()));
        assertEquals(0, atRest.horizontalPercent(), EPS);
        assertEquals(1, atRest.horizontalViewSize(), EPS, "all of its width is on screen");
        assertFalse(atRest.horizontallyScrollable(),
                "a maximum of zero is nowhere to go, and the facet says so: " + describe(tree()));

        tree.scrollHorizontallyBy(100);
        frame();

        ScrollFacet pushed = treeNode().scroll();
        assertEquals(0, pushed.horizontalPercent(), EPS, "and asking it to go anyway moves nothing");
        assertFalse(pushed.horizontallyScrollable());
        assertEquals(BOX_W, commonRowEnd(), 1e-3, describe(tree()));
    }

    @Test
    void aDeepTreeScrollsSidewaysAndItsPercentIsOneAtTheEnd() {
        bindOpenChain(DEEP, ROW_H);

        double content = commonRowEnd();
        assertTrue(content > BOX_W,
                "fourteen levels of indent outgrow the box, which is the premise: " + content);
        ScrollFacet atRest = treeNode().scroll();
        assertTrue(atRest.horizontallyScrollable(), describe(tree()));
        assertEquals(0, atRest.horizontalPercent(), EPS, "at rest the viewport is at the start");
        assertEquals(BOX_W / content, atRest.horizontalViewSize(), 1e-4,
                "the fraction of the content's width the box shows");

        double max = content - BOX_W;
        tree.scrollHorizontallyBy((float) (max / 2));
        frame();

        assertEquals(0.5, treeNode().scroll().horizontalPercent(), 1e-4,
                "the percent is the offset over the maximum offset, not a flag: " + describe(tree()));
        assertEquals(content - max / 2, commonRowEnd(), 1e-3,
                "and the rows moved by exactly that offset");

        tree.scrollHorizontallyBy((float) content);
        frame();

        ScrollFacet atEnd = treeNode().scroll();
        assertEquals(1, atEnd.horizontalPercent(), EPS,
                "past the end is clamped to the end, and the end is one: " + describe(tree()));
        assertTrue(atEnd.horizontallyScrollable(), "it can still come back");
        assertEquals(BOX_W, commonRowEnd(), 1e-3,
                "which is where the content's far edge meets the box's: " + describe(tree()));

        tree.scrollHorizontallyBy(40);
        frame();

        assertEquals(1, treeNode().scroll().horizontalPercent(), EPS, "and one more push stays there");
    }

    /**
     * The percent is published unflipped, the rule the scroll pane's and the tab strip's steps
     * settled (ADR 039): the offset is a distance from the edge reading starts from, and the
     * mirroring is in where the rows sit. A flipped percent would tell a reader that a tree resting
     * on its roots is scrolled to the end.
     */
    @Test
    void rightToLeftThePercentIsStillMeasuredFromTheLeadingEdgeWhileTheRowsMoveTheOtherWay() {
        bindOpenChain(DEEP, ROW_H);
        double content = commonRowEnd();
        assertTrue(content > BOX_W, "the premise, read left to right: " + content);

        bindOpenChain(DEEP, ROW_H, LayoutDirection.RTL);
        assertTrue(tree.isRightToLeft(), "the fixture really did mirror the tree");

        assertEquals(BOX_W - content, commonRowStart(), 1e-3,
                "the same content hung off the other side: its leading edge is the box's right "
                        + "edge and the rest runs out past the left: " + describe(tree()));
        ScrollFacet atRest = treeNode().scroll();
        assertTrue(atRest.horizontallyScrollable(), describe(tree()));
        assertEquals(0, atRest.horizontalPercent(), EPS,
                "zero is the leading edge, which is the right edge here: " + describe(tree()));
        assertEquals(BOX_W / content, atRest.horizontalViewSize(), 1e-4,
                "how much of the width is on screen does not depend on the direction");

        double max = content - BOX_W;
        tree.scrollHorizontallyBy((float) (max / 2));
        frame();

        assertEquals(0.5, treeNode().scroll().horizontalPercent(), 1e-4, describe(tree()));
        assertEquals(BOX_W - content + max / 2, commonRowStart(), 1e-3,
                "right to left, the rows move right by the offset: " + describe(tree()));

        tree.scrollHorizontallyBy((float) content);
        frame();

        assertEquals(1, treeNode().scroll().horizontalPercent(), EPS,
                "the same number as left to right at the same offset: " + describe(tree()));
        assertEquals(0, commonRowStart(), 1e-3,
                "which is where the content's far edge, the left one here, meets the box's: "
                        + describe(tree()));
    }

    @Test
    void scrollingSidewaysMovesTheRowsAndChangesNothingElseAboutThem() {
        bindOpenChain(DEEP, ROW_H);
        List<AccessibleNode> before = rowNodes();
        assertEquals(DEEP, before.size(), describe(tree()));

        tree.scrollHorizontallyBy(30);
        frame();

        List<AccessibleNode> after = rowNodes();
        assertEquals(DEEP, after.size(), describe(tree()));
        for (int i = 0; i < DEEP; i++) {
            AccessibleNode was = before.get(i);
            AccessibleNode is = after.get(i);
            assertEquals(was.id(), is.id(),
                    "the offset re-keys nothing: a reader standing on a row is still on it: "
                            + describe(tree()));
            assertEquals(was.name(), is.name());
            assertEquals(was.selectionItem(), is.selectionItem());
            assertEquals(was.expand(), is.expand());
            assertEquals(was.x() - 30, is.x(), 1e-3, "the box moved by the offset: " + describe(tree()));
            assertEquals(was.y(), is.y(), EPS, "and only across");
            assertEquals(was.width(), is.width(), EPS);
            assertEquals(was.height(), is.height(), EPS);
        }
    }

    // ----------------------------------------------------------------------- the vertical axis

    @Test
    void theVerticalAxisReportsWhatItDidBeforeTheTreeCouldScrollSideways() {
        int count = 40;
        bindTree(ROW_H, leaves(count));
        double content = count * ROW_H;

        ScrollFacet atRest = treeNode().scroll();
        assertNotNull(atRest, describe(tree()));
        assertTrue(atRest.verticallyScrollable());
        assertEquals(0, atRest.verticalPercent(), EPS);
        assertEquals(BOX_H / content, atRest.verticalViewSize(), 1e-4,
                "the fraction of the rows' height the box shows");
        assertFalse(atRest.horizontallyScrollable(),
                "a long list of leaves has depth zero and nothing to scroll sideways");
        assertEquals(1, atRest.horizontalViewSize(), EPS);

        tree.scrollBy((float) ((content - BOX_H) / 2));
        frame();

        assertEquals(0.5, treeNode().scroll().verticalPercent(), 1e-4, describe(tree()));

        tree.scrollBy((float) content);
        frame();

        ScrollFacet atEnd = treeNode().scroll();
        assertEquals(1, atEnd.verticalPercent(), EPS,
                "the percent is the offset over the maximum offset, so the end is one");
        assertEquals(0, atEnd.horizontalPercent(), EPS, "and the other axis never moved");
        assertFalse(atEnd.horizontallyScrollable());
    }

    @Test
    void aDeepChainThatFitsItsHeightDoesNotSayItScrollsDown() {
        bindOpenChain(DEEP, ROW_H);

        ScrollFacet facet = treeNode().scroll();
        assertTrue(facet.horizontallyScrollable(), "it overflows sideways: " + describe(tree()));
        assertFalse(facet.verticallyScrollable(),
                "fourteen rows of twenty in three hundred fit, and width is not height: "
                        + describe(tree()));
        assertEquals(0, facet.verticalPercent(), EPS);
        assertEquals(1, facet.verticalViewSize(), EPS);
    }

    @Test
    void eachAxisMovesOnlyItsOwnPercent() {
        float tall = 30; // fourteen of these are 420, so this chain overflows both ways
        bindOpenChain(DEEP, tall);
        double contentH = DEEP * tall;

        ScrollFacet atRest = treeNode().scroll();
        assertTrue(atRest.horizontallyScrollable(), describe(tree()));
        assertTrue(atRest.verticallyScrollable(), describe(tree()));
        assertEquals(BOX_H / contentH, atRest.verticalViewSize(), 1e-4);

        tree.scrollHorizontallyBy(10_000);
        frame();

        ScrollFacet sideways = treeNode().scroll();
        assertEquals(1, sideways.horizontalPercent(), EPS, describe(tree()));
        assertEquals(0, sideways.verticalPercent(), EPS, "a sideways scroll is not a downward one");
        assertEquals(BOX_H / contentH, sideways.verticalViewSize(), 1e-4);

        tree.scrollBy((float) ((contentH - BOX_H) / 2));
        frame();

        ScrollFacet down = treeNode().scroll();
        assertEquals(0.5, down.verticalPercent(), 1e-4, describe(tree()));
        assertEquals(1, down.horizontalPercent(), EPS,
                "and a downward scroll, which re-mounts rows, keeps the sideways offset: "
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the roles

    @Test
    void aTreeIsATreeOfItemsThatOpenNumberedInTraversalOrder() {
        Node readme = Node.leaf("readme");
        Node docs = Node.of("docs", Node.leaf("a.md"), Node.leaf("b.md"));
        Node top = Node.of("root", docs, readme);
        bindTree(ROW_H, List.of(top));
        tree.expand(top);
        tree.setSelected(docs);
        frame();

        AccessibleNode outline = treeNode();
        assertFalse(outline.selection().multiSelectable(), "SINGLE is the default: " + describe(tree()));
        assertFalse(outline.selection().required(), "a tree rests with nothing selected");
        List<AccessibleNode> rows = rowNodes();
        assertEquals(3, rows.size(), describe(tree()));
        String[] names = {"root", "docs", "readme"};
        for (int i = 0; i < rows.size(); i++) {
            AccessibleNode row = rows.get(i);
            assertEquals(Accessible.Role.TREE_ITEM, row.role(),
                    "every realized row is an item of the tree, since ADR 044's step 1b: "
                            + describe(tree()));
            assertEquals(names[i], row.name(), describe(tree()));
            assertEquals(i + 1, row.selectionItem().positionInSet(),
                    "numbered in traversal order: " + describe(tree()));
            assertEquals(3, row.selectionItem().sizeOfSet(),
                    "against the rows that are visible, which is what is open");
            assertNull(row.actions(), "the verbs are the tree's, not a row's: " + describe(tree()));
        }

        assertNotNull(rows.get(0).expand(), describe(tree()));
        assertTrue(rows.get(0).expand().expanded(), "the root is open");
        assertNotNull(rows.get(1).expand(), describe(tree()));
        assertFalse(rows.get(1).expand().expanded(), "docs can open and has not");
        assertNull(rows.get(2).expand(), "a leaf has nothing to open, so no facet at all");

        assertTrue(rows.get(1).selectionItem().selected(), describe(tree()));
        assertTrue(rows.get(1).has(Accessible.State.ACTIVE), "the selected row is the cursor");
        assertFalse(rows.get(0).selectionItem().selected());
        assertFalse(rows.get(2).selectionItem().selected());
        assertEquals(rows.get(1).id(), outline.selection().activeDescendant(), describe(tree()));
        assertTrue(outline.actions().actions().contains(Accessible.Action.EXPAND),
                "the lead row is closed, so the tree offers to open it: " + describe(tree()));

        tree.expand(docs);
        frame();

        rows = rowNodes();
        assertEquals(5, rows.size(), describe(tree()));
        AccessibleNode opened = node("docs");
        assertTrue(opened.expand().expanded(), describe(tree()));
        assertEquals(5, opened.selectionItem().sizeOfSet(), "every row is renumbered against five");
        assertEquals(5, node("readme").selectionItem().positionInSet(),
                "and the rows below the opened one move down by its children: " + describe(tree()));
        for (AccessibleNode row : rows) {
            assertEquals(Accessible.Role.TREE_ITEM, row.role(), describe(tree()));
        }
        assertTrue(treeNode().actions().actions().contains(Accessible.Action.COLLAPSE),
                "and the verb turns over with it: " + describe(tree()));
        assertFalse(treeNode().actions().actions().contains(Accessible.Action.EXPAND));
    }

    // ---------------------------------------------------------------------- what a quiet frame costs

    @Test
    void aFrameThatDamagesADeepTreeScrolledSidewaysPublishesNothing() {
        bindOpenChain(DEEP, ROW_H);
        tree.scrollHorizontallyBy(30);
        frame();
        int before = bridge.published.size();

        for (int i = 0; i < 10; i++) {
            tree.invalidate();
            frame();
        }

        assertEquals(before, bridge.published.size(),
                "the horizontal numbers are settled by the layout and read as they are, so a "
                        + "frame that moved nothing publishes no difference");
    }
}

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
 * {@code LIST_ITEM}. Since 2026-09-14 a row is numbered among its siblings (decision 4) and
 * carries its depth and its place in the outline through the hierarchy facet; the role case
 * pins both.
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

    /**
     * A reader's "select this row" lands on the row it addressed: the verb is published on the
     * row's cell, which is the application's own widget, and the tree performs it through the
     * same {@code USER} seam a click takes (ADR 039 §1.5, amended 2026-09-14; §11's per-row
     * actuation reversed). A tree that selects nothing delegates nothing.
     */
    @Test
    void selectingARowThroughItsOwnVerbMovesTheSelectionToThatRow() throws Exception {
        Node readme = Node.leaf("readme");
        Node docs = Node.of("docs", Node.leaf("a.md"), Node.leaf("b.md"));
        Node top = Node.of("root", docs, readme);
        bindTree(ROW_H, List.of(top));
        tree.expand(top);
        tree.setSelected(docs);
        frame();
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));

        assertTrue(perform(node("readme").id(), Accessible.Action.SELECT,
                Accessible.Argument.NONE));
        frame();

        assertEquals(List.of(readme), tree.selectedNodes(), "the row addressed, not the lead");
        assertEquals(readme, tree.leadNode());
        assertEquals(readme, tree.cursorNode(), "and the cursor moved with it, as a click's does");
        assertTrue(node("readme").selectionItem().selected(), describe(tree()));
        assertEquals(List.of(limn.scene.Change.Aspect.ACTIVE, limn.scene.Change.Aspect.SELECTION),
                changes.stream().map(limn.scene.Change::aspect).toList(),
                "announced as a click is, the cursor first and then the selection: " + changes);
        assertEquals(limn.scene.Change.Origin.USER, changes.get(0).origin(),
                "and from the user, which is who a reader is");
        assertEquals(limn.scene.Change.Origin.USER, changes.get(1).origin());

        tree.setSelectionMode(Tree.SelectionMode.NONE);
        frame();
        for (AccessibleNode row : rowNodes()) {
            assertFalse(row.actions().has(Accessible.Action.SELECT),
                    "nothing to select, so no SELECT on any row: " + describe(tree()));
            assertTrue(row.actions().has(Accessible.Action.FOCUS),
                    "but the cursor still moves, so FOCUS stays: " + describe(tree()));
        }
    }

    /**
     * Every verb a row publishes reaches the tree and does what the equivalent gesture does
     * (decisions 7, 20 and 11 of 2026-09-14; ADR 039 §1.5 amended): EXPAND and COLLAPSE act like
     * the triangle and leave the cursor where it is; FOCUS moves the cursor and nothing else,
     * and takes the keyboard so the cursor is published; SCROLL_INTO_VIEW reveals the row; the
     * tree's own PRESS activates the cursor row. Before, a row carried SELECT alone and EXPAND
     * and COLLAPSE sat on the tree, acting on whatever row the cursor was on.
     */
    @Test
    void aRowsVerbsReachTheTreeAndActOnThatRowNotTheCursor() throws Exception {
        Node readme = Node.leaf("readme");
        Node docs = Node.of("docs", Node.leaf("a.md"), Node.leaf("b.md"));
        Node top = Node.of("root", docs, readme);
        bindTree(ROW_H, List.of(top));
        tree.expand(top);
        tree.setSelected(readme);
        List<Node> activated = new ArrayList<>();
        tree.onActivate(activated::add);
        frame();

        assertTrue(perform(node("docs").id(), Accessible.Action.EXPAND, Accessible.Argument.NONE));
        frame();
        assertTrue(tree.isExpanded(docs), "EXPAND on the row opened it: " + describe(tree()));
        assertEquals(readme, tree.cursorNode(), "like the triangle, without moving the cursor");
        assertEquals(List.of(readme), tree.selectedNodes(), "or the selection");
        assertTrue(node("docs").actions().has(Accessible.Action.COLLAPSE), describe(tree()));

        assertTrue(perform(node("docs").id(), Accessible.Action.COLLAPSE, Accessible.Argument.NONE));
        frame();
        assertFalse(tree.isExpanded(docs), "COLLAPSE on the row closed it: " + describe(tree()));
        assertEquals(readme, tree.cursorNode());

        assertTrue(nodesWith(Accessible.State.ACTIVE).isEmpty(),
                "nobody is in the tree yet, so no row is the cursor: " + describe(tree()));
        assertTrue(perform(node("docs").id(), Accessible.Action.FOCUS, Accessible.Argument.NONE));
        frame();
        assertEquals(docs, tree.cursorNode(), "FOCUS moved the cursor onto the row");
        assertEquals(List.of(readme), tree.selectedNodes(), "and selected nothing (decision 11)");
        assertEquals(treeNode().id(), tree().focused(), "and took the keyboard: " + describe(tree()));
        assertTrue(node("docs").has(Accessible.State.ACTIVE),
                "so the row is published as the cursor: " + describe(tree()));
        assertEquals(node("docs").id(), tree().activeDescendant(), describe(tree()));

        assertTrue(perform(treeNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(docs), activated,
                "the tree's PRESS activates the cursor row, which is not the selected one");
    }

    /**
     * A collapse that hides the cursor row moves the cursor onto the row that closed (decision
     * 21), so a reader standing on a child row is told the parent it landed on rather than left
     * with no cursor at all: exactly one {@code ACTIVE_DESCENDANT_CHANGED} on the tree, naming
     * the collapsed row, and that row {@code ACTIVE}; the selection it hid stays selected.
     */
    @Test
    void collapsingTheBranchTheCursorIsInMovesTheCursorOntoItAndSaysSo() throws Exception {
        Node readme = Node.leaf("readme");
        Node docs = Node.of("docs", Node.leaf("a.md"), Node.leaf("b.md"));
        Node top = Node.of("root", docs, readme);
        bindTree(ROW_H, List.of(top));
        tree.expand(top);
        tree.setSelected(readme);
        scene.requestFocus(tree);
        frame();
        assertEquals(node("readme").id(), tree().activeDescendant(), describe(tree()));
        bridge.events.clear();

        assertTrue(perform(node("root").id(), Accessible.Action.COLLAPSE, Accessible.Argument.NONE));
        frame();

        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE);
        assertEquals(1, active.size(), "one row is the cursor: " + describe(tree()));
        assertEquals("root", active.get(0).name(), "the row that closed: " + describe(tree()));
        assertEquals(node("root").id(), tree().activeDescendant());
        assertEquals(List.of(readme), tree.selectedNodes(), "the hidden selection stands");
        List<limn.accessibility.AccessibleEvent> moved = bridge.eventsOf(
                limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        assertEquals(1, moved.size(), "one cursor event, on the tree: " + bridge.events);
        assertEquals(node("root").id(), moved.get(0).newValue());
    }

    /**
     * While the tree holds the keyboard its cursor row stays realized across a wheel scroll that
     * carries it out of the box: published, not {@code SHOWING}, still {@code ACTIVE}, so the
     * reader's cursor never resolves to nothing because of a scroll (decision 22 of 2026-09-14;
     * ADR 039 §1.10's effective-focus rule). It is released when the focus leaves. Before, only
     * a cell holding the focus was spared, and the tree itself holds it, so a wheel recycled the
     * cursor row and the tree lost its active descendant (TREE-NEW-6).
     */
    @Test
    void theCursorRowStaysPublishedAcrossAWheelScrollWhileTheTreeHoldsTheKeyboard() {
        bindTree(ROW_H, leaves(40));
        scene.requestFocus(tree);
        tree.setSelected(Node.leaf("row 2"));
        frame();
        long cursorId = node("row 2").id();
        assertTrue(node("row 2").has(Accessible.State.ACTIVE), describe(tree()));
        assertTrue(node("row 2").has(Accessible.State.SHOWING));

        float x = tree.localToSceneX() + tree.width() / 2;
        float y = tree.localToSceneY() + tree.height() / 2;
        scene.scrolled(0, -20, x, y); // twenty notches: well past the box, clamped to the end
        scene.inputBatchEnded();
        frame();

        assertTrue(treeNode().scroll().verticalPercent() > 0.9,
                "the wheel carried the tree to its end: " + describe(tree()));
        AccessibleNode kept = node("row 2");
        assertEquals(cursorId, kept.id(), "the same node, not a re-minted one");
        assertFalse(kept.has(Accessible.State.SHOWING),
                "outside the box, and it says so: " + describe(tree()));
        assertTrue(kept.has(Accessible.State.ACTIVE), "still the cursor: " + describe(tree()));
        assertEquals(cursorId, tree().activeDescendant(),
                "the tree's cursor never resolves to nothing because of a scroll: "
                        + describe(tree()));
        assertTrue(node("row 40").has(Accessible.State.SHOWING), describe(tree()));

        scene.requestFocus(null);
        frame();

        assertNull(limn.testing.AccessibleTrees.named(tree(), "row 2"),
                "with the focus gone the row is released like any other: " + describe(tree()));
        assertEquals(List.of(Node.leaf("row 2")), tree.selectedNodes(), "the selection stands");
        assertEquals(Node.leaf("row 2"), tree.cursorNode(), "and so does the cursor");
    }

    /** {@code SCROLL_INTO_VIEW} on a row that sits half under the top edge brings it back. */
    @Test
    void scrollIntoViewOnARowRevealsIt() throws Exception {
        bindTree(ROW_H, leaves(40));
        tree.scrollBy(ROW_H / 2);
        frame();
        AccessibleNode first = node("row 1");
        assertEquals(-ROW_H / 2, first.y() - tree.localToSceneY(), 1e-3,
                "half of the first row is above the box: " + describe(tree()));
        assertTrue(first.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));

        assertTrue(perform(first.id(), Accessible.Action.SCROLL_INTO_VIEW, Accessible.Argument.NONE));
        frame();

        assertEquals(0, node("row 1").y() - tree.localToSceneY(), 1e-3,
                "and the reveal scrolled it back into the box: " + describe(tree()));
    }

    /**
     * In {@code MULTI} an unselected row publishes {@code ADD_TO_SELECTION} and a selected one
     * {@code DESELECT} (decisions 10 and 20), each performed as the command-click that toggles
     * the row; the one a row did not publish does nothing, which is what the published list
     * promises (semantics 5).
     */
    @Test
    void inMultiARowOffersToJoinOrLeaveTheSelectionByItsState() throws Exception {
        Node one = Node.leaf("one");
        Node two = Node.leaf("two");
        bindTree(ROW_H, List.of(one, two, Node.leaf("three")));
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        tree.setSelected(one);
        frame();
        assertTrue(treeNode().selection().multiSelectable(), describe(tree()));
        assertTrue(node("one").actions().has(Accessible.Action.DESELECT), describe(tree()));
        assertFalse(node("one").actions().has(Accessible.Action.ADD_TO_SELECTION));
        assertTrue(node("two").actions().has(Accessible.Action.ADD_TO_SELECTION), describe(tree()));
        assertFalse(node("two").actions().has(Accessible.Action.DESELECT));

        assertTrue(perform(node("two").id(), Accessible.Action.ADD_TO_SELECTION,
                Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(one, two), tree.selectedNodes(), "two joined, one stayed");
        assertEquals(two, tree.leadNode());
        assertEquals(two, tree.cursorNode());
        assertTrue(node("two").actions().has(Accessible.Action.DESELECT),
                "and its verb turned over: " + describe(tree()));

        assertTrue(perform(node("one").id(), Accessible.Action.ADD_TO_SELECTION,
                Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(one, two), tree.selectedNodes(),
                "a verb the row did not publish does nothing: " + describe(tree()));

        assertTrue(perform(node("one").id(), Accessible.Action.DESELECT, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(two), tree.selectedNodes(), "one left");
        assertEquals(two, tree.leadNode(), "the lead was already elsewhere");
        assertEquals(one, tree.cursorNode(), "and the cursor is on the row that was addressed");

        tree.setSelectionMode(Tree.SelectionMode.SINGLE);
        frame();
        for (AccessibleNode row : rowNodes()) {
            assertFalse(row.actions().has(Accessible.Action.ADD_TO_SELECTION),
                    "SINGLE has nothing to add to: " + describe(tree()));
            assertFalse(row.actions().has(Accessible.Action.DESELECT));
        }
    }

    /**
     * A control inside a cell keeps its own verbs: the tree claims the row's verbs on the cell
     * and nothing on what the cell holds, so a reader's {@code PRESS} on a button in a row
     * reaches the button, and {@code SELECT} on the row reaches the tree.
     */
    @Test
    void aControlInsideACellKeepsItsOwnVerbs() throws Exception {
        Node one = Node.leaf("one");
        Node two = Node.leaf("two");
        List<String> pressed = new ArrayList<>();
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(one, two);
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public Widget cellFor(Node node) {
                limn.scene.layout.Row row = new limn.scene.layout.Row();
                row.add(limn.scene.layout.Expanded.of(new Label(node.name().english())));
                Button open = new Button("Open " + node.name().english());
                open.onAction(() -> pressed.add(node.name().english()));
                row.add(open);
                return row;
            }
        });
        Column root = new Column();
        root.add(new SizedBox(BOX_W, BOX_H, tree));
        bind(root);
        scene.setTextRuler(RULER);
        frame();

        AccessibleNode rowTwo = rowNodes().get(1);
        AccessibleNode button = node("Open two");
        assertTrue(button.actions().has(Accessible.Action.PRESS), describe(tree()));
        assertFalse(button.actions().has(Accessible.Action.SELECT),
                "the row's verbs are the cell's, not the button's: " + describe(tree()));
        assertTrue(rowTwo.actions().has(Accessible.Action.SELECT), describe(tree()));
        assertFalse(rowTwo.actions().has(Accessible.Action.PRESS),
                "and the button's is not the row's: " + describe(tree()));

        assertTrue(perform(button.id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of("two"), pressed, "the button's own handler ran");
        assertTrue(tree.selectedNodes().isEmpty(), "and the tree selected nothing for it");

        assertTrue(perform(rowTwo.id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(two), tree.selectedNodes(), "SELECT on the row reached the tree");
        assertEquals(List.of("two"), pressed, "and pressed nothing");
    }

    /**
     * A row is numbered among its siblings — "2 of 5" counts the parent's children, not the
     * outline — which is decision 4 of 2026-09-13 and what ADR 044 §4 always meant by "its
     * position among its siblings". Until 2026-09-14 the row's place in the whole outline stood
     * in for it, and the case that pinned that said the numbering was meant to move; where a row
     * stands in the outline is the hierarchy facet's now (ADR 039 §1.2, amended).
     */
    @Test
    void aTreeIsATreeOfItemsThatOpenNumberedAmongTheirSiblings() {
        Node readme = Node.leaf("readme");
        Node docs = Node.of("docs", Node.leaf("a.md"), Node.leaf("b.md"));
        Node top = Node.of("root", docs, readme);
        bindTree(ROW_H, List.of(top));
        tree.expand(top);
        tree.setSelected(docs);
        scene.requestFocus(tree);
        frame();

        AccessibleNode outline = treeNode();
        assertFalse(outline.selection().multiSelectable(), "SINGLE is the default: " + describe(tree()));
        assertFalse(outline.selection().required(), "a tree rests with nothing selected");
        List<AccessibleNode> rows = rowNodes();
        assertEquals(3, rows.size(), describe(tree()));
        String[] names = {"root", "docs", "readme"};
        int[] positions = {1, 1, 2};
        int[] siblings = {1, 2, 2};
        for (int i = 0; i < rows.size(); i++) {
            AccessibleNode row = rows.get(i);
            assertEquals(Accessible.Role.TREE_ITEM, row.role(),
                    "every realized row is an item of the tree, since ADR 044's step 1b: "
                            + describe(tree()));
            assertEquals(names[i], row.name(), describe(tree()));
            assertEquals(positions[i], row.selectionItem().positionInSet(),
                    "numbered among its siblings, not the outline: " + describe(tree()));
            assertEquals(siblings[i], row.selectionItem().sizeOfSet(),
                    "against its parent's children: the root is 1 of 1, and both of its "
                            + "children are of 2: " + describe(tree()));
            assertNotNull(row.actions(), "a row carries the verbs the tree delegated onto it: "
                    + describe(tree()));
        }
        // The row verb set (decision 20): SELECT in SINGLE, EXPAND or COLLAPSE by state on a row
        // that can open, and the free pair on every row, because a cell is not focusable and
        // the cursor is not the selection (decision 11). PRESS is the tree's.
        assertEquals(java.util.Set.of(Accessible.Action.SELECT, Accessible.Action.COLLAPSE,
                        Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW),
                rows.get(0).actions().actions(), "an open row: " + describe(tree()));
        assertEquals(java.util.Set.of(Accessible.Action.SELECT, Accessible.Action.EXPAND,
                        Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW),
                rows.get(1).actions().actions(), "a closed row: " + describe(tree()));
        assertEquals(java.util.Set.of(Accessible.Action.SELECT, Accessible.Action.FOCUS,
                        Accessible.Action.SCROLL_INTO_VIEW),
                rows.get(2).actions().actions(), "a leaf: " + describe(tree()));

        assertNotNull(rows.get(0).expand(), describe(tree()));
        assertTrue(rows.get(0).expand().expanded(), "the root is open");
        assertNotNull(rows.get(1).expand(), describe(tree()));
        assertFalse(rows.get(1).expand().expanded(), "docs can open and has not");
        assertNull(rows.get(2).expand(), "a leaf has nothing to open, so no facet at all");

        assertTrue(rows.get(1).selectionItem().selected(), describe(tree()));
        assertTrue(rows.get(1).has(Accessible.State.ACTIVE),
                "the selected row is the cursor while the tree holds the keyboard");
        assertFalse(rows.get(0).selectionItem().selected());
        assertFalse(rows.get(2).selectionItem().selected());
        assertEquals(outline.id(), tree().focused(), describe(tree()));
        assertEquals(rows.get(1).id(), tree().activeDescendant(),
                "the tree's cursor is the first active node below the focused one: "
                        + describe(tree()));
        assertTrue(outline.actions().has(Accessible.Action.PRESS),
                "the tree's one verb of its own acts on the cursor row: " + describe(tree()));
        assertFalse(outline.actions().has(Accessible.Action.EXPAND),
                "opening and closing a row are the row's, not the tree's (decision 20): "
                        + describe(tree()));
        assertFalse(outline.actions().has(Accessible.Action.COLLAPSE));

        tree.expand(docs);
        frame();

        rows = rowNodes();
        assertEquals(5, rows.size(), describe(tree()));
        AccessibleNode opened = node("docs");
        assertTrue(opened.expand().expanded(), describe(tree()));
        assertEquals(2, opened.selectionItem().sizeOfSet(),
                "opening a row renumbers nothing: docs is still 1 of 2: " + describe(tree()));
        assertEquals(1, opened.selectionItem().positionInSet());
        assertEquals(2, node("readme").selectionItem().positionInSet(),
                "and readme is still 2 of 2, whatever opened above it: " + describe(tree()));
        assertEquals(1, node("a.md").selectionItem().positionInSet(),
                "the children count among themselves: " + describe(tree()));
        assertEquals(2, node("b.md").selectionItem().positionInSet());
        assertEquals(2, node("b.md").selectionItem().sizeOfSet());
        assertEquals(5, node("readme").hierarchy().row(),
                "where readme stands in the outline moved down by the two rows that opened, "
                        + "and that is the hierarchy facet's to say: " + describe(tree()));
        for (AccessibleNode row : rows) {
            assertEquals(Accessible.Role.TREE_ITEM, row.role(), describe(tree()));
        }
        assertTrue(opened.actions().actions().contains(Accessible.Action.COLLAPSE),
                "and the row's verb turns over with it: " + describe(tree()));
        assertFalse(opened.actions().actions().contains(Accessible.Action.EXPAND));
    }

    /**
     * The settled active-state gate (ADR 039 §1.10, amended 2026-09-14): the cursor is the
     * focused node's, resolved downward from it, so a tree nobody is in publishes no
     * {@code ACTIVE} row and raises no cursor event — and the moment the keyboard arrives, the
     * lead row is the cursor and one event on the tree says so.
     */
    @Test
    void anUnfocusedTreeWithASelectedRowPublishesNoCursorAndTakingTheKeyboardAnnouncesIt() {
        Node docs = Node.leaf("docs");
        Node top = Node.of("root", docs, Node.leaf("readme"));
        bindTree(ROW_H, List.of(top));
        tree.expand(top);
        tree.setSelected(docs);
        frame();

        assertTrue(nodesWith(Accessible.State.ACTIVE).isEmpty(),
                "the selection stands, the cursor does not: " + describe(tree()));
        assertEquals(0, tree().activeDescendant(), describe(tree()));
        assertEquals(0, bridge.countOf(
                limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "an unfocused container announces nothing: " + bridge.events);

        scene.requestFocus(tree);
        frame();

        assertEquals(node("docs").id(), tree().activeDescendant(), describe(tree()));
        List<limn.accessibility.AccessibleEvent> moved = bridge.eventsOf(
                limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        assertEquals(1, moved.size(), "one cursor event, on the focused node: " + bridge.events);
        assertEquals(treeNode().id(), moved.get(0).nodeId());
        assertEquals(node("docs").id(), moved.get(0).newValue());
    }

    /**
     * A row opened after the tree is on screen, in a tree whose cells name their own rows — the
     * demo's shape, where the model has no {@code nameOf} and a reader hears whatever the cell
     * says.
     *
     * <p>Cells were bound to row positions, so opening a row left the cells below it where they
     * were: the reader was told the old names at the new positions, and the opened row's children
     * were never in the tree at all. That is the Windows finding in ADR 044 §4's 2026-09-13
     * amendment (patterns offset by exactly the rows hidden) and the one all three platforms
     * shared (Remote's loaded children never appeared), read here without a guest.
     */
    @Test
    void aRowOpenedOnScreenPublishesItsChildrenAndMovesTheRowsBelowItWithTheirNames() {
        Node a = Node.of("a", Node.of("a.1", Node.leaf("a.1.x")), Node.leaf("a.2"));
        List<Node> roots = List.of(a, Node.leaf("b"), Node.of("c", Node.leaf("c.1")));
        tree = new Tree<>(new Tree.Model<Node>() {
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
                return new Label(node.name().english());
            }
        });
        Column root = new Column();
        root.add(new SizedBox(BOX_W, BOX_H, tree));
        bind(root);
        scene.setTextRuler(RULER); // a label with no ruler measures no height, and rows overlap
        frame();
        assertEquals(List.of("a", "b", "c"), rowNodes().stream().map(AccessibleNode::name).toList(),
                describe(tree()));

        tree.expand(a);
        frame();

        List<AccessibleNode> rows = rowNodes();
        assertEquals(List.of("a", "a.1", "a.2", "b", "c"),
                rows.stream().map(AccessibleNode::name).toList(),
                "the children are items, and every row below carries its own name: "
                        + describe(tree()));
        assertTrue(rows.get(0).expand().expanded(), describe(tree()));
        assertNotNull(rows.get(1).expand(), "a.1 can open, and says so on its own row");
        assertNull(rows.get(2).expand(), "a.2 is a leaf, and says so on its own row");
        assertNull(rows.get(3).expand(), "b is a leaf wherever the shift carried it");
        assertNotNull(rows.get(4).expand(), "c can open wherever the shift carried it");

        tree.collapse(a);
        frame();

        assertEquals(List.of("a", "b", "c"), rowNodes().stream().map(AccessibleNode::name).toList(),
                "and closing it takes the children out and moves the rows back: "
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ loading

    /**
     * An open row whose children are on their way is {@code BUSY}, which is what the spinner and
     * the "Loading…" line say to a sighted user (ADR 044 §2). Without it, an open row with nothing
     * under it tells a reader the node is empty.
     *
     * <p>The line itself is not an item. It is not a node and cannot be selected, so a reader that
     * walked onto it would stand somewhere the tree cannot put its cursor. The rows are numbered
     * among the nodes, so the count does not include a line the reader never reaches either.
     * When the children land, the row stops being busy and says so with a state change, which is
     * what a bridge raises on the platform.
     */
    @Test
    void aRowWhoseChildrenAreOnTheirWayIsBusyAndItsLoadingLineIsNotAnItem() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        Node remote = Node.leaf("remote");
        Node below = Node.leaf("b");
        List<Node> fetched = List.of(Node.leaf("one"), Node.leaf("two"));
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(remote, below);
            }

            @Override
            public List<Node> children(Node node) {
                return node == remote ? null : node.children(); // "not known yet"
            }

            @Override
            public limn.concurrent.Work<List<Node>> load(Node node) {
                return limn.concurrent.Ui.work(progress -> fetched);
            }

            @Override
            public Widget cellFor(Node node) {
                return new Cell(ROW_H);
            }

            @Override
            public I18nString nameOf(Node node) {
                return node.name();
            }
        });
        Column root = new Column();
        root.add(new SizedBox(BOX_W, BOX_H, tree));
        bind(root);

        tree.expand(remote);
        frame();

        List<AccessibleNode> rows = rowNodes();
        assertEquals(List.of("remote", "b"), rows.stream().map(AccessibleNode::name).toList(),
                "the loading line is not an item: " + describe(tree()));
        assertFalse(describe(tree()).contains("Loading"),
                "and it is nowhere else in the tree either: " + describe(tree()));
        assertTrue(rows.get(0).has(Accessible.State.BUSY),
                "the open row whose children are on their way is busy: " + describe(tree()));
        assertTrue(rows.get(0).expand().expanded(), "and still open");
        assertFalse(rows.get(1).has(Accessible.State.BUSY), "the row below is not");
        assertEquals(2, rows.get(0).selectionItem().sizeOfSet(),
                "numbered among the siblings, so the line is not counted: " + describe(tree()));
        assertEquals(2, rows.get(1).selectionItem().positionInSet(),
                "and the row below it is the second root, not the third");
        assertEquals(2, rows.get(1).hierarchy().row(),
                "nor is the line a row of the outline: " + describe(tree()));
        bridge.events.clear();

        ui.pumpUntil(() -> tree.visibleRowCount() == 4);
        frame();

        rows = rowNodes();
        assertEquals(List.of("remote", "one", "two", "b"),
                rows.stream().map(AccessibleNode::name).toList(), describe(tree()));
        assertTrue(nodesWith(Accessible.State.BUSY).isEmpty(),
                "once the children land nothing is busy: " + describe(tree()));
        long remoteId = rows.get(0).id();
        assertTrue(bridge.events.stream().anyMatch(event ->
                        event.type() == limn.accessibility.AccessibleEvent.Type.STATE_CHANGED
                                && event.state() == Accessible.State.BUSY
                                && event.nodeId() == remoteId),
                "and the row says it stopped, which is what a platform bridge raises: "
                        + bridge.events);
    }

    /**
     * Every row says how deep it is and which open row of the outline it is, through
     * {@code HierarchyFacet} (ADR 039 §1.2, amended 2026-09-14): one-based, the loading line not
     * counted, and renumbered when a branch opens. Where a row stands among its siblings is
     * {@code SelectionItemFacet}'s (decision 4), pinned in the role case above.
     */
    @Test
    void aTreeItemSaysItsLevelAndWhichOpenRowOfTheOutlineItIs() {
        Node readme = Node.leaf("readme");
        Node docs = Node.of("docs", Node.leaf("a.md"), Node.leaf("b.md"));
        Node top = Node.of("root", docs, readme);
        bindTree(ROW_H, List.of(top));
        tree.expand(top);
        frame();

        assertNull(treeNode().hierarchy(), "the outline itself stands nowhere in it");
        assertEquals(new limn.accessibility.HierarchyFacet(1, 1, 3), node("root").hierarchy(),
                describe(tree()));
        assertEquals(new limn.accessibility.HierarchyFacet(2, 2, 3), node("docs").hierarchy(),
                describe(tree()));
        assertEquals(new limn.accessibility.HierarchyFacet(2, 3, 3), node("readme").hierarchy(),
                describe(tree()));

        tree.expand(docs);
        frame();

        assertEquals(new limn.accessibility.HierarchyFacet(1, 1, 5), node("root").hierarchy());
        assertEquals(new limn.accessibility.HierarchyFacet(2, 2, 5), node("docs").hierarchy());
        assertEquals(new limn.accessibility.HierarchyFacet(3, 3, 5), node("a.md").hierarchy(),
                "one level deeper: " + describe(tree()));
        assertEquals(new limn.accessibility.HierarchyFacet(3, 4, 5), node("b.md").hierarchy());
        assertEquals(new limn.accessibility.HierarchyFacet(2, 5, 5), node("readme").hierarchy(),
                "moved down by the two rows that opened above it: " + describe(tree()));
    }

    /** The loading line is not a row of the outline, so the rows below it are not moved by it. */
    @Test
    void aLoadingLineIsNotARowOfTheOutline() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        Node remote = Node.leaf("remote");
        Node below = Node.leaf("b");
        List<Node> fetched = List.of(Node.leaf("one"), Node.leaf("two"));
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(remote, below);
            }

            @Override
            public List<Node> children(Node node) {
                return node == remote ? null : node.children();
            }

            @Override
            public limn.concurrent.Work<List<Node>> load(Node node) {
                return limn.concurrent.Ui.work(progress -> fetched);
            }

            @Override
            public Widget cellFor(Node node) {
                return new Cell(ROW_H);
            }

            @Override
            public I18nString nameOf(Node node) {
                return node.name();
            }
        });
        Column root = new Column();
        root.add(new SizedBox(BOX_W, BOX_H, tree));
        bind(root);

        tree.expand(remote);
        frame();

        assertEquals(new limn.accessibility.HierarchyFacet(1, 1, 2), node("remote").hierarchy(),
                describe(tree()));
        assertEquals(new limn.accessibility.HierarchyFacet(1, 2, 2), node("b").hierarchy(),
                "the second row, not the third: " + describe(tree()));

        ui.pumpUntil(() -> tree.visibleRowCount() == 4);
        frame();

        assertEquals(new limn.accessibility.HierarchyFacet(2, 2, 4), node("one").hierarchy(),
                describe(tree()));
        assertEquals(new limn.accessibility.HierarchyFacet(2, 3, 4), node("two").hierarchy());
        assertEquals(new limn.accessibility.HierarchyFacet(1, 4, 4), node("b").hierarchy());
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

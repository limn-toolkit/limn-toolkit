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
        private final ToDoubleFunction<Node> rowHeight;

        Outline(float rowHeight, List<Node> roots) {
            this(node -> rowHeight, roots);
        }

        /** An outline whose rows each measure the height {@code rowHeight} gives their node. */
        Outline(ToDoubleFunction<Node> rowHeight, List<Node> roots) {
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
            return new Cell((float) rowHeight.applyAsDouble(node));
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
     *
     * <p><b>And it leaves the cursor where it was</b> (decision 79 of 2026-09-17). Until that day
     * this verb moved the cursor as a click does, and that is the measured cause of the one fight
     * phase 5 left open: on AppKit a selection write does not move the keyboard focus, so
     * VoiceOver mirrors its own cursor into the selection as a matter of course, and every such
     * write dragged this tree's cursor to the reader's previous row — nine unrequested moves in
     * nine, at +37…+69 ms (P5M-1). A click still moves it, because the pointer is where the user
     * is; a client write is not.
     */
    @Test
    void selectingARowThroughItsOwnVerbLeavesTheCursorWhereItWas() throws Exception {
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
        assertEquals(docs, tree.cursorNode(),
                "and the cursor stayed where it was, which a click's does not (decision 79)");
        assertTrue(node("readme").selectionItem().selected(), describe(tree()));
        assertEquals(List.of(limn.scene.Change.Aspect.SELECTION),
                changes.stream().map(limn.scene.Change::aspect).toList(),
                "so the selection alone is announced, with no cursor move before it: " + changes);
        assertEquals(limn.scene.Change.Origin.USER, changes.get(0).origin(),
                "and from the user, which is who a reader is");

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

    /**
     * Every verb the kept cursor row publishes is performed while a wheel holds it out of the box
     * (decision 22 read with semantics 5): the verbs are the tree's, and the tree is on the glass.
     * Until 2026-09-15 the scene gated a delegated verb on the row showing, exempting only
     * {@code SCROLL_INTO_VIEW}, so a reader standing on the row it had scrolled away from was told
     * yes by {@code Host.perform} for {@code EXPAND}, {@code COLLAPSE}, {@code DESELECT},
     * {@code ADD_TO_SELECTION}, {@code SELECT} and {@code FOCUS}, and nothing happened.
     * {@code FOCUS} on the row the cursor is already on changes nothing but where the tree is
     * scrolled, which is the next case's.
     */
    @Test
    void everyVerbTheKeptCursorRowPublishesIsPerformedWhileItIsWheeledOutOfTheBox()
            throws Exception {
        List<Node> roots = leaves(40);
        Node branch = Node.of("row 2", Node.leaf("row 2.1"));
        roots.set(1, branch);
        bindTree(ROW_H, roots);
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        scene.requestFocus(tree);
        tree.setSelected(branch);
        frame();
        float x = tree.localToSceneX() + tree.width() / 2;
        float y = tree.localToSceneY() + tree.height() / 2;
        scene.scrolled(0, -20, x, y);
        scene.inputBatchEnded();
        frame();
        long kept = node("row 2").id();

        assertOutOfTheBox("before EXPAND");
        assertTrue(perform(kept, Accessible.Action.EXPAND, Accessible.Argument.NONE));
        frame();
        assertTrue(tree.isExpanded(branch), "EXPAND opened the kept row: " + describe(tree()));

        assertOutOfTheBox("before COLLAPSE");
        assertTrue(perform(kept, Accessible.Action.COLLAPSE, Accessible.Argument.NONE));
        frame();
        assertFalse(tree.isExpanded(branch), "COLLAPSE closed it: " + describe(tree()));

        assertOutOfTheBox("before DESELECT");
        assertTrue(perform(kept, Accessible.Action.DESELECT, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(), tree.selectedNodes(), "DESELECT took it out of the selection");

        assertOutOfTheBox("before ADD_TO_SELECTION");
        assertTrue(perform(kept, Accessible.Action.ADD_TO_SELECTION, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(branch), tree.selectedNodes(), "ADD_TO_SELECTION put it back");

        assertTrue(perform(node("row 40").id(), Accessible.Action.ADD_TO_SELECTION,
                Accessible.Argument.NONE));
        frame();
        assertOutOfTheBox("before SELECT");
        assertTrue(perform(kept, Accessible.Action.SELECT, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(branch), tree.selectedNodes(),
                "SELECT made it the only selected row: " + describe(tree()));
    }

    /**
     * A verb that reveals the kept cursor row brings it back where it stands, however far the
     * wheel carried the box from it: {@code SCROLL_INTO_VIEW}, {@code SELECT} and {@code FOCUS}
     * through the scene, and Space, the gesture that toggles the cursor row, alike. The kept row is
     * laid out at the viewport's edge and not at its place in the outline (decision 22), and the
     * reveal read that edge as the row's top, so it scrolled one row's height towards it and left
     * it out of the box.
     */
    @Test
    void aRevealOfTheKeptCursorRowBringsItBackIntoTheBox() throws Exception {
        Node second = Node.leaf("row 2");
        bindTree(ROW_H, leaves(40));
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        scene.requestFocus(tree);
        tree.setSelected(second);
        frame();
        long kept = node("row 2").id();

        for (Accessible.Action verb : List.of(Accessible.Action.SCROLL_INTO_VIEW,
                Accessible.Action.SELECT, Accessible.Action.FOCUS)) {
            wheelToTheEnd();
            assertOutOfTheBox("before " + verb);
            assertTrue(perform(kept, verb, Accessible.Argument.NONE));
            frame();
            assertTrue(node("row 2").has(Accessible.State.SHOWING),
                    verb + " brought the kept row back into the box: " + describe(tree()));
            assertEquals(kept, tree().activeDescendant(), describe(tree()));
        }

        wheelToTheEnd();
        assertOutOfTheBox("before Space");
        scene.keyEvent(limn.input.Keys.SPACE, true, false, 0);
        scene.keyEvent(limn.input.Keys.SPACE, false, false, 0);
        scene.inputBatchEnded();
        frame();
        assertEquals(List.of(), tree.selectedNodes(), "Space toggled the cursor row off");
        assertTrue(node("row 2").has(Accessible.State.SHOWING),
                "and revealed it, as the toggle it is: " + describe(tree()));
    }

    /**
     * The reveal of a row outside the viewport lands it exactly at the edge it comes in from, over
     * rows of uneven height: the kept cursor row wheeled out above the box comes back with its top
     * on the box's top, and wheeled out below, with its bottom on the box's bottom. The reveal
     * scrolled by the anchor's estimate — the row's distance from the anchor counted in average
     * rows — and the average is taken over the rows at hand, so with taller rows between the two
     * the scroll stopped short and the row stayed out of the box (the fixtree review, 2026-09-15).
     */
    @Test
    void aRevealOverRowsOfUnevenHeightLandsTheKeptRowAtTheEdgeItComesInFrom() throws Exception {
        List<Node> roots = leaves(40);
        // Twenty-point rows at either end, where the average is taken, and ten of eighty between.
        tree = new Tree<>(new Outline(node -> {
            int n = Integer.parseInt(node.name().toString().substring("row ".length()));
            return n >= 16 && n <= 25 ? 80 : ROW_H;
        }, roots));
        Column root = new Column();
        root.add(new SizedBox(BOX_W, BOX_H, tree));
        bind(root);
        scene.requestFocus(tree);

        for (Accessible.Action verb : List.of(Accessible.Action.SCROLL_INTO_VIEW,
                Accessible.Action.SELECT)) {
            tree.setSelected(Node.leaf("row 2"));
            frame();
            wheelUntilShowing("row 40", -20);
            assertFalse(node("row 2").has(Accessible.State.SHOWING),
                    "the wheel carried row 2 out above the box: " + describe(tree()));
            assertTrue(perform(node("row 2").id(), verb, Accessible.Argument.NONE));
            frame();
            AccessibleNode above = node("row 2");
            assertTrue(above.has(Accessible.State.SHOWING),
                    verb + " brought row 2 back from above: " + describe(tree()));
            assertEquals(treeNode().y(), above.y(), 0.01f,
                    "with its top on the box's top, the least scroll that shows it");

            tree.setSelected(Node.leaf("row 39"));
            frame();
            wheelUntilShowing("row 1", 20);
            assertFalse(node("row 39").has(Accessible.State.SHOWING),
                    "the wheel carried row 39 out below the box: " + describe(tree()));
            assertTrue(perform(node("row 39").id(), verb, Accessible.Argument.NONE));
            frame();
            AccessibleNode below = node("row 39");
            assertTrue(below.has(Accessible.State.SHOWING),
                    verb + " brought row 39 back from below: " + describe(tree()));
            assertEquals(treeNode().y() + BOX_H, below.y() + below.height(), 0.01f,
                    "with its bottom on the box's bottom: " + describe(tree()));
        }
    }

    /**
     * Two reveals before a frame keep the later one. A reveal of a row outside the box is settled
     * by the next pass, and nothing moves until then; End and then Home in one input batch end
     * with the first row in the box and the cursor on it, not with the last row's reveal settled
     * over the first row's; and End then a wheel notch end one notch from the top, the scroll
     * moving from where the rows stand.
     */
    @Test
    void theLaterOfTwoRevealsInOneBatchIsTheOneThatLands() {
        bindTree(ROW_H, leaves(40));
        scene.requestFocus(tree);
        tree.setSelected(Node.leaf("row 1"));
        frame();

        for (int key : new int[] {limn.input.Keys.END, limn.input.Keys.HOME}) {
            scene.keyEvent(key, true, false, 0);
            scene.keyEvent(key, false, false, 0);
        }
        scene.inputBatchEnded();
        frame();

        assertEquals(List.of(Node.leaf("row 1")), tree.selectedNodes(), "Home selected the first row");
        assertTrue(showing("row 1"), "and the first row is in the box: " + describe(tree()));
        assertFalse(showing("row 40"), "not the last: " + describe(tree()));

        // And a scroll after a reveal the pass has not settled moves from where the rows stand.
        scene.keyEvent(limn.input.Keys.END, true, false, 0);
        scene.keyEvent(limn.input.Keys.END, false, false, 0);
        scene.scrolled(0, -1, tree.localToSceneX() + tree.width() / 2,
                tree.localToSceneY() + tree.height() / 2);
        scene.inputBatchEnded();
        frame();
        assertFalse(showing("row 1"), "the notch scrolled from the top: " + describe(tree()));
        assertTrue(showing("row 4"), "by one notch: " + describe(tree()));
        assertFalse(showing("row 40"), "and End's reveal gave way to it: " + describe(tree()));
    }

    /** Wheels {@code notches} at a time, up to ten times, until the named row is in the box. */
    private void wheelUntilShowing(String name, int notches) {
        float x = tree.localToSceneX() + tree.width() / 2;
        float y = tree.localToSceneY() + tree.height() / 2;
        for (int i = 0; i < 10 && !showing(name); i++) {
            scene.scrolled(0, notches, x, y);
            scene.inputBatchEnded();
            frame();
        }
        assertTrue(showing(name), "wheeled to " + name + ": " + describe(tree()));
    }

    /** Whether a row of that name is published and in the box; a row not mounted is not. */
    private boolean showing(String name) {
        AccessibleNode row = limn.testing.AccessibleTrees.named(tree(), name);
        return row != null && row.has(Accessible.State.SHOWING);
    }

    /** Twenty wheel notches over the tree: past the box, clamped to the end. */
    private void wheelToTheEnd() {
        float x = tree.localToSceneX() + tree.width() / 2;
        float y = tree.localToSceneY() + tree.height() / 2;
        scene.scrolled(0, -20, x, y);
        scene.inputBatchEnded();
        frame();
    }

    /** Asserts the kept cursor row is published, the same node, and outside the box. */
    private void assertOutOfTheBox(String when) {
        AccessibleNode row = node("row 2");
        assertFalse(row.has(Accessible.State.SHOWING),
                when + ", the fixture holds the cursor row out of the box: " + describe(tree()));
        assertTrue(row.has(Accessible.State.ACTIVE), when + ": " + describe(tree()));
    }

    /**
     * A refresh releases every cell, and the cursor row comes back fresh from the model at the
     * height it measures, like a placed row. It came back mounted and never laid out, at height
     * zero: a zero-height {@code ACTIVE} node to a reader, and a zero in the average row height
     * that sizes the scroll estimate the tree publishes, until the row scrolled back into the
     * placed run (the review of tree-A, 2026-09-14; decision 22's refresh case).
     */
    @Test
    void theCursorRowComesBackFromARefreshAtItsHeightAndTheScrollEstimateStands() {
        bindTree(ROW_H, leaves(40));
        scene.requestFocus(tree);
        tree.setSelected(Node.leaf("row 2"));
        frame();
        float x = tree.localToSceneX() + tree.width() / 2;
        float y = tree.localToSceneY() + tree.height() / 2;
        scene.scrolled(0, -20, x, y);
        scene.inputBatchEnded();
        frame();
        AccessibleNode spared = node("row 2");
        assertFalse(spared.has(Accessible.State.SHOWING), describe(tree()));
        assertEquals(ROW_H, spared.height(), "spared by the wheel at its height");
        double viewSize = treeNode().scroll().verticalViewSize();

        tree.refresh();
        frame();
        AccessibleNode back = node("row 2");
        assertEquals(ROW_H, back.height(),
                "mounted back at the height it measures: " + describe(tree()));
        assertTrue(back.has(Accessible.State.ACTIVE), "still the cursor: " + describe(tree()));
        assertFalse(back.has(Accessible.State.SHOWING), "still outside the box");
        assertEquals(viewSize, treeNode().scroll().verticalViewSize(), 1e-9,
                "no zero reached the average row height, so the scroll estimate stands");
    }

    /**
     * A refresh releases the identifier of a node the model no longer has, and with it the node:
     * the identifier table was the one place a removed node stayed strongly held, for the tree's
     * life (TREE-NEW-8). Only a bound tree mints identifiers, which is why this case is here.
     * Driven by garbage collection, so it is a loop with a deadline rather than one
     * {@code System.gc()}.
     */
    @Test
    void aRefreshReleasesANodeTheModelNoLongerHas() {
        List<Node> roots = new ArrayList<>(List.of(Node.leaf("keep")));
        java.lang.ref.WeakReference<Node> dropped = addRootToDrop(roots);
        bindTree(ROW_H, roots);
        frame();
        assertNotNull(node("drop").id(), "published, so it holds an identifier: " + describe(tree()));

        roots.remove(1); // by index: a reference held here would keep the node alive itself
        tree.refresh();
        frame();
        assertEquals(1, rowNodes().size(), describe(tree()));

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (dropped.get() != null) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the dropped node is still held after the refresh");
            }
            System.gc();
            Thread.onSpinWait();
        }
    }

    /** In a method of its own so no local of the caller's frame keeps the node alive. */
    private static java.lang.ref.WeakReference<Node> addRootToDrop(List<Node> roots) {
        Node drop = Node.leaf("drop");
        roots.add(drop);
        return new java.lang.ref.WeakReference<>(drop);
    }

    /**
     * A row whose cell is a composite — an icon, a label and a count in a {@code Row}, the
     * demo's shape — is named from the text of its labels, since neither the cell nor the model
     * names it: a nameless tree item is one a reader never speaks (TREE-ROW-NAME, from the L4
     * baseline on Fedora: Orca's name generator yielded nothing for such rows). The model's
     * {@code nameOf} wins where it gives one, a cell that names itself keeps its name, and the
     * derived name follows the labels when they change.
     */
    @Test
    void aRowWhoseCellIsACompositeIsNamedFromItsLabels() {
        Node docs = Node.of("Documents", Node.leaf("notes.md"), Node.leaf("todo.md"));
        Node readme = Node.leaf("README");
        Label[] badge = new Label[1]; // the newest: a cell built before the ruler was set is released
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(docs, readme);
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public Widget cellFor(Node node) {
                if (node.children().isEmpty()) {
                    return new Label(node.name().english()); // names itself
                }
                limn.scene.layout.Row row = new limn.scene.layout.Row();
                row.add(limn.scene.layout.Expanded.of(new Label(node.name().english())));
                Label count = new Label(String.valueOf(node.children().size())).setMuted(true);
                badge[0] = count;
                row.add(count);
                return row;
            }
        });
        Column root = new Column();
        root.add(new SizedBox(BOX_W, BOX_H, tree));
        bind(root);
        scene.setTextRuler(RULER);
        frame();

        List<AccessibleNode> rows = rowNodes();
        assertEquals(List.of("Documents 2", "README"),
                rows.stream().map(AccessibleNode::name).toList(),
                "the composite row is named from its labels, the label row by itself: "
                        + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, rows.get(0).nameFrom());
        int published = bridge.published.size();
        for (int i = 0; i < 3; i++) {
            tree.invalidate();
            frame();
        }
        assertEquals(published, bridge.published.size(),
                "a name read the same is not a change: nothing was published again");

        badge[0].setText("3");
        frame();
        assertEquals("Documents 3", rowNodes().get(0).name(),
                "and the name follows the label: " + describe(tree()));
    }

    /** The model's name wins over the cell's labels, as it does over a cell that names itself. */
    @Test
    void theModelsNameWinsOverTheCellsLabels() {
        Node docs = Node.of("Documents", Node.leaf("notes.md"));
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(docs);
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public Widget cellFor(Node node) {
                limn.scene.layout.Row row = new limn.scene.layout.Row();
                row.add(new Label(node.name().english()));
                row.add(new Label("1"));
                return row;
            }

            @Override
            public I18nString nameOf(Node node) {
                return I18nString.literal("Documents folder");
            }
        });
        Column root = new Column();
        root.add(new SizedBox(BOX_W, BOX_H, tree));
        bind(root);
        scene.setTextRuler(RULER);
        frame();

        assertEquals("Documents folder", rowNodes().get(0).name(), describe(tree()));
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
        assertEquals(one, tree.cursorNode(), "the cursor stays put (decision 20)");
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
        assertEquals(one, tree.cursorNode(), "and the cursor, never moved, is still on it");

        tree.setSelectionMode(Tree.SelectionMode.SINGLE);
        frame();
        for (AccessibleNode row : rowNodes()) {
            assertFalse(row.actions().has(Accessible.Action.ADD_TO_SELECTION),
                    "SINGLE has nothing to add to: " + describe(tree()));
            assertFalse(row.actions().has(Accessible.Action.DESELECT));
        }
    }

    /**
     * {@code ADD_TO_SELECTION} and {@code DESELECT} change what is selected and nothing else: the
     * cursor, the row a reader stands on, and the range anchor Shift extends from stay where they
     * were (decision 20 of 2026-09-14, semantics 5: only {@code SELECT} and {@code FOCUS} move a
     * cursor). Until 2026-09-15 both went through the command-click's seam, which lands the
     * cursor and the anchor on the row clicked — right for the pointer, which is where the user
     * is, and wrong for a reader, who adds a row to the selection without leaving the one it is
     * on. The command-click itself still moves both ({@code TreeTest}).
     */
    @Test
    void addingOrRemovingARowLeavesTheCursorAndTheAnchorWhereTheyWere() throws Exception {
        Node one = Node.leaf("one");
        Node two = Node.leaf("two");
        Node three = Node.leaf("three");
        bindTree(ROW_H, List.of(one, two, three, Node.leaf("four")));
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        scene.requestFocus(tree);
        tree.setSelected(one);
        frame();
        long cursorRow = node("one").id();
        assertEquals(cursorRow, tree().activeDescendant(), describe(tree()));
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));
        bridge.events.clear();

        assertTrue(perform(node("three").id(), Accessible.Action.ADD_TO_SELECTION,
                Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(one, three), tree.selectedNodes(), "three joined the selection");
        assertEquals(one, tree.cursorNode(), "and the cursor stayed on the row it was on");
        assertEquals(cursorRow, tree().activeDescendant(),
                "so the reader is still where it was: " + describe(tree()));
        assertTrue(bridge.eventsOf(limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED)
                .isEmpty(), "and no cursor event was raised: " + bridge.events);

        assertTrue(perform(node("three").id(), Accessible.Action.DESELECT, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(one), tree.selectedNodes(), "three left again");
        assertEquals(one, tree.cursorNode(), "and the cursor did not follow it");
        assertEquals(List.of(limn.scene.Change.Aspect.SELECTION, limn.scene.Change.Aspect.SELECTION),
                changes.stream().map(limn.scene.Change::aspect).toList(),
                "two selection changes and no cursor move: " + changes);

        // The anchor: Shift+Down from the cursor extends from where the anchor is. Had either
        // verb moved it onto "three", the range would run from there.
        scene.keyEvent(limn.input.Keys.DOWN, true, false, limn.input.Keys.MOD_SHIFT);
        scene.keyEvent(limn.input.Keys.DOWN, false, false, limn.input.Keys.MOD_SHIFT);
        scene.inputBatchEnded();
        frame();
        assertEquals(List.of(one, two), tree.selectedNodes(),
                "the range runs from the anchor the verbs left on the first row");
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
        // The row has a PRESS of its own since decision 80 of 2026-09-17 — it opens THAT row,
        // where the tree's own opens the cursor's — so the two nodes both carry the verb and the
        // point of this case is that each keeps its own performer: the button's press runs the
        // button's handler and opens no row.
        assertTrue(rowTwo.actions().has(Accessible.Action.PRESS), describe(tree()));

        List<Node> opened = new ArrayList<>();
        tree.onActivate(opened::add);
        assertTrue(perform(button.id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of("two"), pressed, "the button's own handler ran");
        assertTrue(tree.selectedNodes().isEmpty(), "and the tree selected nothing for it");
        assertTrue(opened.isEmpty(), "and opened no row: the press was the button's");

        assertTrue(perform(rowTwo.id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(two), opened, "the row's own press opens that row");
        assertEquals(List.of("two"), pressed, "and runs nothing of the cell's");

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
        // the cursor is not the selection (decision 11). PRESS is on the row as well as on the
        // tree since decision 80 of 2026-09-17: the row's opens that row, the tree's opens the
        // cursor's, and the pair exists because decision 79 stopped a reader's SELECT from
        // dragging the cursor to the row it selected.
        assertEquals(java.util.Set.of(Accessible.Action.SELECT, Accessible.Action.COLLAPSE,
                        Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW,
                        Accessible.Action.PRESS),
                rows.get(0).actions().actions(), "an open row: " + describe(tree()));
        assertEquals(java.util.Set.of(Accessible.Action.SELECT, Accessible.Action.EXPAND,
                        Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW,
                        Accessible.Action.PRESS),
                rows.get(1).actions().actions(), "a closed row: " + describe(tree()));
        assertEquals(java.util.Set.of(Accessible.Action.SELECT, Accessible.Action.FOCUS,
                        Accessible.Action.SCROLL_INTO_VIEW, Accessible.Action.PRESS),
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
     * A row whose load found nothing is an open branch with no items under it and no longer
     * busy (decision 45 of 2026-09-14): the "Empty" line says so to a sighted user, and to a
     * reader the expanded state over no children says the same thing, so the line is not an
     * item — one a reader could walk onto that is not a node — and the rows below are numbered
     * as though it were not there.
     */
    @Test
    void aRowWhoseLoadFoundNothingIsAnOpenBranchWithNoItemsAndItsEmptyLineIsNotOne() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        Node trash = Node.leaf("trash");
        Node below = Node.leaf("b");
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(trash, below);
            }

            @Override
            public List<Node> children(Node node) {
                return node == trash ? null : node.children(); // "not known yet"
            }

            @Override
            public limn.concurrent.Work<List<Node>> load(Node node) {
                return limn.concurrent.Ui.work(progress -> List.of());
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
        List<limn.scene.Change> changes = new ArrayList<>();
        tree.observeChanges((source, change) -> changes.add(change));

        tree.expand(trash);
        frame();
        ui.pumpUntil(() -> changes.stream().anyMatch(
                c -> c.aspect() == limn.scene.Change.Aspect.CHILDREN
                        && c.origin() == limn.scene.Change.Origin.ADJUSTMENT));
        frame();

        List<AccessibleNode> rows = rowNodes();
        assertEquals(List.of("trash", "b"), rows.stream().map(AccessibleNode::name).toList(),
                "the empty line is not an item: " + describe(tree()));
        assertFalse(describe(tree()).contains("Empty"),
                "and it is nowhere else in the tree either: " + describe(tree()));
        assertTrue(rows.get(0).expand().expanded(), "the row is still open: " + describe(tree()));
        assertTrue(nodesWith(Accessible.State.BUSY).isEmpty(),
                "and busy no longer: " + describe(tree()));
        assertEquals(new limn.accessibility.HierarchyFacet(1, 2, 2), rows.get(1).hierarchy(),
                "the row below is the second of the outline, not the third: " + describe(tree()));
    }

    /** Every announcement the bridge was handed, in order, as spoken text. */
    private List<String> announced() {
        List<String> said = new ArrayList<>();
        for (limn.accessibility.AccessibleEvent event : bridge.events) {
            if (event.type() == limn.accessibility.AccessibleEvent.Type.ANNOUNCEMENT) {
                said.add(String.valueOf(event.newValue()));
            }
        }
        return said;
    }

    /**
     * Decision 73 (2026-09-16): a lazy load's start and its end are <b>announced</b>, naming the
     * branch. Nothing else tells a reader. The row publishes {@code BUSY} and the "Loading…" line
     * is drawn, and on 2026-09-16 both were measured saying nothing: {@code BUSY} reaches Windows
     * as {@code ItemStatus} and NVDA 2024.4.2 has no handler for it — six raises, five received,
     * none spoken — and the line is not focusable, so the cursor steps over it on every platform.
     * The announcement path is the one route all three readers were measured speaking through on
     * that same day. The visual line of decision 45 stays exactly as it is and stays unfocusable,
     * which the two tests above assert and this one does not disturb.
     *
     * <p><b>Both ends, since decision 83 of 2026-09-17</b>, which closed the first case decision
     * 73 left open: a load that lands <em>with</em> children says it landed. Until then it said
     * nothing after its start, so "Loading inbox" and then silence was what a reader got from a
     * load still running and from one that had finished alike.
     */
    @Test
    void aLazyLoadSaysItHasBegunAndSaysHowItEnded() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        Node trash = Node.leaf("trash");
        Node inbox = Node.leaf("inbox");
        List<Node> fetched = List.of(Node.leaf("one"), Node.leaf("two"));
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(trash, inbox);
            }

            @Override
            public List<Node> children(Node node) {
                return node.children().isEmpty() ? null : node.children(); // "not known yet"
            }

            @Override
            public limn.concurrent.Work<List<Node>> load(Node node) {
                return limn.concurrent.Ui.work(progress -> node == trash ? List.of() : fetched);
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
        List<limn.scene.Change> changes = new ArrayList<>();
        tree.observeChanges((source, change) -> changes.add(change));
        bridge.events.clear();

        tree.expand(inbox);
        frame();
        assertEquals(List.of("Loading inbox"), announced(),
                "the start, named, before anything has landed: " + bridge.events);
        ui.pumpUntil(() -> tree.visibleRowCount() == 4);
        frame();
        assertEquals(List.of("Loading inbox", "inbox loaded"), announced(),
                "and a load that found children says that it landed (decision 83): "
                        + bridge.events);
        bridge.events.clear();
        changes.clear();

        tree.expand(trash);
        frame();
        assertEquals(List.of("Loading trash"), announced(), bridge.events.toString());
        ui.pumpUntil(() -> changes.stream().anyMatch(
                c -> c.aspect() == limn.scene.Change.Aspect.CHILDREN
                        && c.origin() == limn.scene.Change.Origin.ADJUSTMENT));
        frame();
        assertEquals(List.of("Loading trash", "trash empty"), announced(),
                "and the end of a load that found nothing: " + bridge.events);

        // The line of decision 45 is untouched by any of it: still drawn, still not a node.
        assertEquals(List.of("trash", "inbox", "one", "two"),
                rowNodes().stream().map(AccessibleNode::name).toList(), describe(tree()));
        assertFalse(describe(tree()).contains("Empty"), describe(tree()));
    }

    /**
     * The second case decision 73 left open, closed by decision 83 of 2026-09-17: an
     * <b>eager</b> empty branch — a model that calls a node a non-leaf over an empty list — says
     * it is empty too.
     *
     * <p>It is the sharper of the two. There is no load and no wait, so the row opens instantly
     * onto the unfocusable "Empty" line of decision 45, which the cursor steps over on every
     * platform: a reader pressed Right and heard <em>nothing at all</em>, with no "Loading" even
     * to say that something had happened. Same silence decision 73 was written to end, and the
     * same sentence ends it. The line itself is untouched, which the last assertion holds.
     */
    @Test
    void anEagerEmptyBranchSaysItIsEmptyToo() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        Node empty = Node.leaf("archive");
        Node full = Node.of("docs", Node.leaf("a.md"));
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(empty, full);
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public boolean isLeaf(Node node) {
                return false; // an empty folder is a folder: the guide's own Entry shape
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
        bridge.events.clear();

        tree.expand(empty);
        frame();
        assertEquals(List.of("archive empty"), announced(),
                "opening onto nothing says so, with no load to wait for: " + bridge.events);
        bridge.events.clear();

        tree.expand(full);
        frame();
        assertEquals(List.of(), announced(),
                "and a branch that opens onto children says nothing: the rows are the answer, "
                        + "and a reader walks into them: " + bridge.events);
        assertFalse(describe(tree()).contains("Empty"),
                "the drawn line is still not a node: " + describe(tree()));
    }

    /**
     * The announcement is named the way a row is (ADR 044 §4): the model's name first, and where
     * the model has none, the text of the cell's own labels. An announcement arrives with no
     * context, so "Loading" alone would name nothing — and a node neither route can name says
     * nothing at all rather than a sentence with a hole in it.
     */
    @Test
    void aLoadAnnouncementTakesTheRowsNameAndSaysNothingWhereThereIsNone() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        Node named = Node.leaf("reports");
        Node blank = Node.leaf("");
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return List.of(named, blank);
            }

            @Override
            public List<Node> children(Node node) {
                return null; // both are branches nobody has read
            }

            @Override
            public limn.concurrent.Work<List<Node>> load(Node node) {
                return limn.concurrent.Ui.work(progress -> List.of());
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
        bridge.events.clear();

        tree.expand(named);
        frame();
        assertEquals(List.of("Loading reports"), announced(),
                "no nameOf, so the cell's label names it: " + bridge.events);
        bridge.events.clear();

        tree.expand(blank);
        frame();
        assertEquals(List.of(), announced(),
                "a branch nothing can name says nothing: " + bridge.events);
    }

    /**
     * A load that lands after the tree is on screen, in the demo's exact shape — {@code Label}
     * cells and no {@code nameOf} — publishes the children under their own names, each with its
     * own expand state, and moves the rows below with theirs (T6's one missing headless pin,
     * 2026-09-14). The lazy case above resolves names through {@code nameOf}, which reads the
     * node at the row's index and not the cell, so a cell left bound to an old index is
     * invisible to it; only a cell that names its own row can catch the mis-binding that ADR 044
     * §4's "later the same day" paragraph records (654632d). Red with the re-binding of mounted
     * cells to their nodes disabled.
     */
    @Test
    void aLoadThatLandsOnScreenPublishesItsChildrenUnderTheirOwnNames() {
        Node remote = Node.leaf("remote");
        Node below = Node.of("b", Node.leaf("b.1"));
        List<Node> fetched = List.of(Node.leaf("one"), Node.of("two", Node.leaf("two.1")));
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
                return new Label(node.name().english());
            }
        });
        Column root = new Column();
        root.add(new SizedBox(BOX_W, BOX_H, tree));
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        assertEquals(List.of("remote", "b"),
                rowNodes().stream().map(AccessibleNode::name).toList(), describe(tree()));

        tree.expand(remote);
        frame();
        assertEquals(List.of("remote", "b"),
                rowNodes().stream().map(AccessibleNode::name).toList(),
                "open and busy, the loading line is not an item: " + describe(tree()));

        ui.pumpUntil(() -> tree.visibleRowCount() == 4);
        frame();

        List<AccessibleNode> rows = rowNodes();
        assertEquals(List.of("remote", "one", "two", "b"),
                rows.stream().map(AccessibleNode::name).toList(),
                "the children landed under their own names and b moved down with its: "
                        + describe(tree()));
        assertTrue(rows.get(0).expand().expanded(), "remote is open: " + describe(tree()));
        assertNull(rows.get(1).expand(), "one is a leaf, and says so on its own row");
        assertNotNull(rows.get(2).expand(), "two can open, and says so on its own row");
        assertFalse(rows.get(2).expand().expanded(), "and is closed");
        assertNotNull(rows.get(3).expand(), "b can open wherever the landing carried it");
        assertEquals(2, rows.get(3).selectionItem().positionInSet(),
                "b is still the second root: " + describe(tree()));
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

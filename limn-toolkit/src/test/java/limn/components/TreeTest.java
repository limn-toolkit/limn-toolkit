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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three things a tree does that a list cannot: an order that is a traversal, a row that opens,
 * and a row that promises children before it can name them (ADR 044) — and, since 2026-09-14,
 * the cursor and the selection it leads (§6), the wheel and the free height (§3), the handlers
 * and the mirrored arrows (§5).
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
        /** Every node whose children the tree asked for, in order. */
        final List<String> childrenAsked = new ArrayList<>();
        /** Held by every load's body before it answers; open by default. */
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(0);
        /** What a load answers instead of {@link #loads}, when set: the reload that changed. */
        volatile Map<String, List<Node>> reloads;

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
            childrenAsked.add(node.name());
            // A node named in `loads` says "not known yet", which is what makes it non-leaf and
            // sends the tree to load().
            return loads.containsKey(node.name()) ? null : node.children();
        }

        @Override
        public Work<List<Node>> load(Node node) {
            loadCalls++;
            Map<String, List<Node>> source = reloads != null ? reloads : loads;
            List<Node> kids = source.get(node.name());
            return Ui.work(progress -> {
                gate.await();
                return kids;
            });
        }

        @Override
        public Widget cellFor(Node node) {
            cellsBuilt.add(node.name());
            Label cell = new Label(node.name());
            return cell;
        }

        /** Every cell handed back, so a case can check the model only ever gets its own. */
        final List<Widget> recycled = new ArrayList<>();

        @Override
        public void recycle(Widget cell) {
            recycled.add(cell);
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

    /**
     * What the tree has laid out inside its box, top to bottom: the text of every cell that sits
     * in the viewport. Read off the widgets and not the model, because a cell bound to the wrong
     * row is exactly what a model-side count cannot see.
     */
    private static List<String> drawn(Tree<Node> tree) {
        List<Widget> cells = new ArrayList<>();
        for (Widget child : tree.children()) {
            if (child instanceof Label && child.y() + child.height() > 0 && child.y() < tree.height()) {
                cells.add(child);
            }
        }
        cells.sort(java.util.Comparator.comparingDouble(Widget::y));
        List<String> names = new ArrayList<>();
        for (Widget cell : cells) {
            names.add(((Label) cell).text());
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

    /**
     * A row that opens once the tree is on screen moves every row below it, and the cells have to
     * move with their nodes. They are mounted per row, so a cell left bound to its old position
     * draws the node that used to be there: opening "a" showed "a, b, c, b, c", and its children
     * were never drawn at all. The first case built the tree already open, which is why nothing
     * saw it.
     */
    @Test
    void theCellsFollowTheirNodesWhenARowOpensOrClosesAboveThem() {
        Node a = Node.of("a", Node.leaf("a.1"), Node.leaf("a.2"));
        CountingModel model = new CountingModel(List.of(a, Node.leaf("b"), Node.leaf("c")));
        Tree<Node> tree = mount(model);
        assertEquals(List.of("a", "b", "c"), drawn(tree));

        tree.expand(a);
        scene.layoutPass(220, 200);
        assertEquals(List.of("a", "a.1", "a.2", "b", "c"), drawn(tree),
                "the children are drawn where they are, and the rows below move down with them");

        tree.collapse(a);
        scene.layoutPass(220, 200);
        assertEquals(List.of("a", "b", "c"), drawn(tree),
                "and closing it moves them back up, drawing each node once");
    }

    /** The same, when the rows move because a load landed rather than because a row opened. */
    @Test
    void theCellsFollowTheirNodesWhenALoadLandsAboveThem() {
        Node remote = new Node("remote", List.of());
        CountingModel model = new CountingModel(List.of(remote, Node.leaf("b"), Node.leaf("c")),
                Map.of("remote", List.of(Node.leaf("one"), Node.leaf("two"))));
        Tree<Node> tree = mount(model);

        tree.expand(remote);
        ui.pumpUntil(() -> tree.visibleRowCount() == 5);
        scene.layoutPass(220, 200);
        assertEquals(List.of("remote", "one", "two", "b", "c"), drawn(tree),
                "what the load brought is drawn under the row that asked for it");
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
        assertEquals(root, tree.cursorNode(), "the first arrow lands on the first row");

        press(Keys.RIGHT);  // opens it
        assertTrue(tree.isExpanded(root), "Right opens a closed row");
        assertEquals(root, tree.cursorNode(), "and stays on it");

        press(Keys.RIGHT);  // steps into it
        assertEquals(docs, tree.cursorNode(), "Right again steps into an open row");

        press(Keys.LEFT);   // docs is closed, so this goes to the parent
        assertEquals(root, tree.cursorNode(), "Left on a closed row goes to its parent");

        press(Keys.LEFT);   // closes the root
        assertFalse(tree.isExpanded(root), "Left on an open row closes it");
    }

    /**
     * The three handlers hear the user's gesture and never the caller's verb (ADR 040):
     * Right and Left reach {@code onExpand} and {@code onCollapse} with the row they opened or
     * closed, Enter reaches {@code onActivate} with the cursor row, and {@code expand()},
     * {@code collapse()} and {@code activate()} reach none of them. No test drove a handler
     * before this one (T8).
     */
    @Test
    void theHandlersHearTheUserAndNeverTheCaller() {
        Node root = forest();
        Tree<Node> tree = mount(new CountingModel(List.of(root)));
        List<String> heard = new ArrayList<>();
        tree.onExpand(node -> heard.add("expand " + node.name()));
        tree.onCollapse(node -> heard.add("collapse " + node.name()));
        tree.onActivate(node -> heard.add("activate " + node.name()));
        scene.requestFocus(tree);

        press(Keys.DOWN);  // onto the root
        press(Keys.RIGHT); // opens it
        assertEquals(List.of("expand root"), heard);
        press(Keys.LEFT);  // closes it
        assertEquals(List.of("expand root", "collapse root"), heard);
        press(Keys.ENTER);
        assertEquals(List.of("expand root", "collapse root", "activate root"), heard);

        heard.clear();
        tree.expand(root);
        tree.collapse(root);
        tree.activate();
        scene.layoutPass(220, 200);
        assertEquals(List.of(), heard, "a caller's verb reaches a watcher, never a handler");
    }

    /**
     * Read right to left the horizontal arrows swap, as every other pair in this toolkit does
     * (ADR 044 §5): Left opens and steps in, Right closes and steps out. The mirror of
     * {@link #theArrowsOpenStepInCloseAndStepOut}, which no RTL case had walked (T8).
     */
    @Test
    void rightToLeftTheArrowsSwap() {
        Node root = forest();
        Tree<Node> tree = mount(new CountingModel(List.of(root)));
        tree.setLayoutDirection(limn.scene.LayoutDirection.RTL);
        scene.layoutPass(220, 200);
        scene.requestFocus(tree);

        press(Keys.DOWN);  // onto the root
        assertEquals(root, tree.cursorNode());
        press(Keys.LEFT);  // opens it: the arrow that goes deeper, mirrored
        assertTrue(tree.isExpanded(root), "Left opens a closed row reading right to left");
        press(Keys.LEFT);  // steps into it
        assertEquals("docs", tree.cursorNode().name(), "and steps into an open one");
        press(Keys.RIGHT); // docs is closed, so this goes to the parent
        assertEquals(root, tree.cursorNode(), "Right steps out to the parent of a closed row");
        press(Keys.RIGHT); // closes the root
        assertFalse(tree.isExpanded(root), "and closes an open one");
    }

    /**
     * {@code NONE} selects nothing and freezes nothing: the cursor walks the outline exactly as
     * it does in the other two modes, and Enter activates the row it stands on (decisions 14 and
     * 32 of 2026-09-14). Before, {@code selectOnly} returned before moving the cursor, so in NONE
     * every arrow, Right, Left and Enter were dead — against the enum's own javadoc.
     */
    @Test
    void inNoneTheCursorStillMovesAndEnterActivatesTheRowItIsOn() {
        Node root = forest();
        Node docs = root.children().get(0);
        CountingModel model = new CountingModel(List.of(root));
        Tree<Node> tree = mount(model);
        List<Node> activated = new ArrayList<>();
        tree.setSelectionMode(Tree.SelectionMode.NONE);
        tree.onActivate(activated::add);
        scene.requestFocus(tree);

        press(Keys.DOWN);
        assertEquals(root, tree.cursorNode(), "the first arrow lands on the first row");
        assertTrue(tree.selectedNodes().isEmpty(), "and selects nothing");
        assertNull(tree.leadNode(), "so there is no lead");

        press(Keys.RIGHT);
        assertTrue(tree.isExpanded(root), "Right opens the row the cursor is on");
        press(Keys.DOWN);
        assertEquals(docs, tree.cursorNode(), "Down walks into it");
        assertTrue(tree.selectedNodes().isEmpty());

        press(Keys.ENTER);
        assertEquals(List.of(docs), activated,
                "Enter activates the cursor row, which was never selected");
    }

    /**
     * The cursor is announced as {@code ACTIVE} before the selection that moved with it, with
     * the gesture's origin, the way {@code Table} announces its focus cell (ADR 040 §7.2); and
     * where nothing is selected the cursor is still announced, so a watcher hears a cursor move
     * in {@code NONE} too. Before, the tree never announced {@code ACTIVE} at all.
     */
    @Test
    void theCursorMovingIsAnnouncedAsActiveBeforeTheSelection() {
        Node root = forest();
        CountingModel model = new CountingModel(List.of(root, Node.leaf("two")));
        Tree<Node> tree = mount(model);
        scene.requestFocus(tree);
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));

        press(Keys.DOWN);
        assertEquals(List.of(limn.scene.Change.Aspect.ACTIVE, limn.scene.Change.Aspect.SELECTION),
                changes.stream().map(limn.scene.Change::aspect).toList(),
                "the cursor first, then the selection: " + changes);
        assertEquals(limn.scene.Change.Origin.USER, changes.get(0).origin());
        assertEquals(limn.scene.Change.Origin.USER, changes.get(1).origin());

        changes.clear();
        tree.setSelectionMode(Tree.SelectionMode.NONE);
        changes.clear();
        press(Keys.DOWN);
        assertEquals(List.of(limn.scene.Change.Aspect.ACTIVE),
                changes.stream().map(limn.scene.Change::aspect).toList(),
                "in NONE the cursor moved and nothing else did: " + changes);

        changes.clear();
        press(Keys.DOWN); // past the end: the cursor stays on the last row
        assertTrue(changes.isEmpty(), "a cursor that did not move is not announced: " + changes);
    }

    /**
     * Space in {@code MULTI} toggles the cursor row off and leaves the cursor on it: a lead that
     * followed the toggle would hand {@code onSelect}'s reader the row that was just deselected,
     * which is what {@code leadNode()} used to answer. The cursor stays so Space can toggle it
     * back and Enter still activates it (decision 14).
     */
    @Test
    void aToggleOffKeepsTheCursorOnTheRowAndTakesTheLeadOffIt() {
        Node root = forest();
        CountingModel model = new CountingModel(List.of(root, Node.leaf("two")));
        Tree<Node> tree = mount(model);
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        List<Node> activated = new ArrayList<>();
        List<List<Node>> selections = new ArrayList<>();
        tree.onActivate(activated::add);
        tree.onSelect(() -> selections.add(tree.selectedNodes()));
        scene.requestFocus(tree);

        press(Keys.DOWN);
        assertEquals(List.of(root), tree.selectedNodes());
        assertEquals(root, tree.leadNode());

        press(Keys.SPACE);
        assertTrue(tree.selectedNodes().isEmpty(), "Space toggled the cursor row off");
        assertNull(tree.leadNode(), "so nothing leads the selection");
        assertEquals(root, tree.cursorNode(), "and the cursor is still on the row");
        assertEquals(List.of(List.of(root), List.of()), selections,
                "the handler heard both moves and read the selection back");

        press(Keys.SPACE);
        assertEquals(List.of(root), tree.selectedNodes(), "Space toggles it back on");
        assertEquals(root, tree.leadNode());

        press(Keys.SPACE);
        press(Keys.ENTER);
        assertEquals(List.of(root), activated, "Enter activates the cursor row, selected or not");
    }

    /**
     * A refresh that drops the node the cursor stands on takes the cursor off it even when
     * nothing is selected — after a toggle-off, or in {@code NONE}, where nothing ever is.
     * Before, {@code pruneSelection} returned on an empty selection before it looked at the
     * cursor, so Enter and a reader's {@code PRESS} went on activating a node the model no longer
     * had.
     */
    @Test
    void aRefreshTakesTheCursorOffANodeTheModelDropped() {
        Node keep = Node.leaf("keep");
        Node drop = Node.leaf("drop");
        List<Node> roots = new ArrayList<>(List.of(keep, drop));
        CountingModel model = new CountingModel(roots);
        Tree<Node> tree = mount(model);
        List<limn.scene.Change> changes = new ArrayList<>();

        tree.setSelected(drop);
        tree.setSelectionMode(Tree.SelectionMode.NONE);
        assertEquals(drop, tree.cursorNode(), "NONE keeps the cursor where it was");
        assertTrue(tree.selectedNodes().isEmpty());
        scene.observeChanges((source, change) -> changes.add(change));

        roots.remove(drop);
        tree.refresh();
        scene.layoutPass(220, 200);

        assertNull(tree.cursorNode(), "the cursor cannot stand on a node the model dropped");
        assertTrue(changes.stream().anyMatch(c -> c.aspect() == limn.scene.Change.Aspect.ACTIVE
                        && c.origin() == limn.scene.Change.Origin.ADJUSTMENT),
                "and the tree said so, as an adjustment of its own: " + changes);
    }

    // ------------------------------------------------------------------------- MULTI

    /** The cell drawing {@code name}, found among the tree's children. */
    private static Widget cellOf(Tree<Node> tree, String name) {
        for (Widget child : tree.children()) {
            if (child instanceof Label label && label.text().equals(name)) {
                return child;
            }
        }
        throw new AssertionError("no cell is drawing " + name);
    }

    /** A press and release at the middle of {@code name}'s cell, with {@code modifiers} held. */
    private void click(Tree<Node> tree, String name, int modifiers) {
        Widget cell = cellOf(tree, name);
        float x = cell.localToSceneX() + cell.width() / 2;
        float y = cell.localToSceneY() + cell.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, modifiers, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, modifiers, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);
    }

    private void press(int key, int modifiers) {
        scene.keyEvent(key, true, false, modifiers);
        scene.keyEvent(key, false, false, modifiers);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);
    }

    /** The open forest as rows: root, docs, a.md, b.md, readme. */
    private Tree<Node> openForest(Node root) {
        Tree<Node> tree = mount(new CountingModel(List.of(root, Node.leaf("two"))));
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        tree.expand(root);
        tree.expand(root.children().get(0));
        scene.layoutPass(220, 200);
        scene.requestFocus(tree);
        return tree;
    }

    /**
     * The command modifier is the platform's — {@link Accelerator#commandModifier()}, Command
     * on macOS and Control elsewhere — and not a fixed Super bit, which is what the press site
     * read and what made the demo's "the command modifier adds a row" false on Windows and
     * Linux (T1). The modifier is injected, so the case reads the same on this Mac and on CI's
     * Ubuntu.
     */
    @Test
    void aCommandClickAddsARowToAMultiSelection() {
        Node root = forest();
        Tree<Node> tree = openForest(root);
        Node docs = root.children().get(0);
        Node readme = root.children().get(1);

        click(tree, "docs", 0);
        click(tree, "readme", Accelerator.commandModifier());
        assertEquals(List.of(docs, readme), tree.selectedNodes(),
                "the second click, with the platform's command modifier, added a row");
        assertEquals(readme, tree.leadNode());

        click(tree, "docs", Accelerator.commandModifier());
        assertEquals(List.of(readme), tree.selectedNodes(), "and a third took one away");
        assertEquals(readme, tree.leadNode(), "the lead was already on the other row");
        assertEquals(docs, tree.cursorNode(), "the cursor is on the row that was clicked");
    }

    /**
     * Shift extends a range from the anchor over the visible rows, replacing the selection as
     * {@code Table}'s does: Shift+click, then Shift+arrows from the same anchor (T2; decision 31).
     * A node hidden under a closed branch is not between two visible rows and leaves when a
     * range replaces the selection, though a collapse alone keeps it (ADR 044 §6).
     */
    @Test
    void shiftClickAndShiftArrowsSelectARangeOverTheVisibleRows() {
        Node root = forest();
        Tree<Node> tree = openForest(root);
        Node docs = root.children().get(0);
        Node aMd = docs.children().get(0);
        Node bMd = docs.children().get(1);
        Node readme = root.children().get(1);

        click(tree, "docs", 0);
        click(tree, "readme", Keys.MOD_SHIFT);
        assertEquals(List.of(docs, aMd, bMd, readme), tree.selectedNodes(),
                "from the anchor to the row clicked, in traversal order, the children included");
        assertEquals(readme, tree.leadNode());
        assertEquals(readme, tree.cursorNode());

        press(Keys.UP, Keys.MOD_SHIFT);
        assertEquals(List.of(docs, aMd, bMd), tree.selectedNodes(),
                "Shift+Up shrinks the range back toward the same anchor");
        assertEquals(bMd, tree.cursorNode());

        press(Keys.UP, Keys.MOD_SHIFT);
        press(Keys.UP, Keys.MOD_SHIFT);
        press(Keys.UP, Keys.MOD_SHIFT);
        assertEquals(List.of(root, docs), tree.selectedNodes(),
                "and past the anchor the range runs the other way from it");

        press(Keys.END, Keys.MOD_SHIFT);
        assertEquals(List.of(docs, aMd, bMd, readme, tree.cursorNode()), tree.selectedNodes(),
                "Shift+End takes everything from the anchor to the last row");

        tree.setSelected(bMd);
        tree.collapse(docs);
        scene.layoutPass(220, 200);
        assertEquals(List.of(bMd), tree.selectedNodes(),
                "a collapse alone keeps a hidden selection (ADR 044 §6)");
        click(tree, "root", 0);
        click(tree, "readme", Keys.MOD_SHIFT);
        assertEquals(List.of(root, docs, readme), tree.selectedNodes(),
                "a range replaces the selection, and the hidden node is not in it");
    }

    /**
     * Ctrl+A or Cmd+A selects every open row and {@link Tree#selectAll()} enters the same seam
     * as a caller's write: what is visible, so a node under a closed branch is not taken. In
     * SINGLE the chord does nothing.
     */
    @Test
    void commandASelectsEveryOpenRowAndSelectAllIsTheCallersWrite() {
        Node root = forest();
        Tree<Node> tree = openForest(root);
        Node docs = root.children().get(0);
        tree.collapse(docs);
        scene.layoutPass(220, 200);
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));

        press(Keys.A, Accelerator.commandModifier());
        assertEquals(4, tree.selectedNodes().size(),
                "the two roots and the open root's children: " + tree.selectedNodes());
        assertEquals(List.of(root, docs, root.children().get(1)), tree.selectedNodes().subList(0, 3),
                "every open row, in traversal order");
        assertFalse(tree.selectedNodes().contains(docs.children().get(0)),
                "a node under a closed branch is not an open row");
        assertEquals(1, changes.size(), "announced once: " + changes);
        assertEquals(limn.scene.Change.Origin.USER, changes.get(0).origin());
        assertEquals(root, tree.leadNode(), "with nothing leading, the first row does");

        tree.clearSelection();
        changes.clear();
        tree.selectAll();
        assertEquals(4, tree.selectedNodes().size());
        assertEquals(limn.scene.Change.Origin.CODE, changes.get(0).origin(),
                "the caller's write announces as the caller's: " + changes);

        tree.setSelectionMode(Tree.SelectionMode.SINGLE);
        tree.setSelected(docs);
        press(Keys.A, Accelerator.commandModifier());
        assertEquals(List.of(docs), tree.selectedNodes(), "SINGLE has no select-all");
    }

    /**
     * The programmatic set: {@link Tree#setSelectedNodes} replaces the selection with the nodes
     * named, the last as the lead and under the cursor, and {@link Tree#clearSelection()} drops
     * it and leaves the cursor; each announces once as {@code CODE}, and neither reaches
     * {@code onSelect}, which is the user's. The modes that cannot hold the set refuse it.
     */
    @Test
    void setSelectedNodesAndClearSelectionAnnounceOnceAsCode() {
        Node root = forest();
        Tree<Node> tree = openForest(root);
        Node docs = root.children().get(0);
        Node readme = root.children().get(1);
        List<limn.scene.Change> changes = new ArrayList<>();
        int[] userHeard = {0};
        tree.onSelect(() -> userHeard[0]++);
        scene.observeChanges((source, change) -> changes.add(change));

        tree.setSelectedNodes(List.of(readme, docs));
        assertEquals(List.of(readme, docs), tree.selectedNodes(), "in the order named");
        assertEquals(docs, tree.leadNode(), "the last named leads");
        assertEquals(docs, tree.cursorNode(), "and is under the cursor");
        assertEquals(List.of(limn.scene.Change.Aspect.ACTIVE, limn.scene.Change.Aspect.SELECTION),
                changes.stream().map(limn.scene.Change::aspect).toList(), changes.toString());
        assertEquals(limn.scene.Change.Origin.CODE, changes.get(1).origin());

        changes.clear();
        tree.setSelectedNodes(List.of(readme, docs));
        assertTrue(changes.isEmpty(), "the same set again moves nothing: " + changes);

        tree.clearSelection();
        assertTrue(tree.selectedNodes().isEmpty());
        assertNull(tree.leadNode());
        assertEquals(docs, tree.cursorNode(), "clearing the selection leaves the cursor");
        assertEquals(List.of(limn.scene.Change.Aspect.SELECTION),
                changes.stream().map(limn.scene.Change::aspect).toList(), changes.toString());
        assertEquals(0, userHeard[0], "none of it was the user's");

        tree.setSelectionMode(Tree.SelectionMode.SINGLE);
        assertThrows(IllegalStateException.class,
                () -> tree.setSelectedNodes(List.of(readme, docs)), "SINGLE holds one");
        tree.setSelectedNodes(List.of(readme));
        assertEquals(List.of(readme), tree.selectedNodes());
        tree.setSelectionMode(Tree.SelectionMode.NONE);
        assertThrows(IllegalStateException.class, () -> tree.setSelectedNodes(List.of(readme)),
                "NONE holds nothing");
    }

    /**
     * A second press on the same row within the table's 400 ms activates it, like Enter
     * (decision 46 of 2026-09-14); a press with a modifier does not, and one outside the window
     * is a first press again.
     */
    @Test
    void aDoubleClickActivatesTheRowLikeEnter() {
        Node root = forest();
        CountingModel model = new CountingModel(List.of(root, Node.leaf("two")));
        long[] now = {1_000_000_000L};
        Tree<Node> tree = new Tree<>(model);
        scene = new Scene(tree, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.layoutPass(220, 200);
        scene.renderFrame(new RecordingTestCanvas(220, 200));
        List<Node> activated = new ArrayList<>();
        tree.onActivate(activated::add);

        click(tree, "root", 0);
        now[0] += 200_000_000L;
        click(tree, "root", 0);
        assertEquals(List.of(root), activated, "two presses 200 ms apart on one row activate it");

        now[0] += 200_000_000L;
        click(tree, "root", 0);
        assertEquals(List.of(root), activated,
                "the third press starts over rather than activating again");

        now[0] += 1_000_000_000L;
        click(tree, "two", 0);
        now[0] += 500_000_000L;
        click(tree, "two", 0);
        assertEquals(List.of(root), activated, "half a second apart is two clicks");

        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        now[0] += 1_000_000_000L;
        click(tree, "two", Accelerator.commandModifier());
        now[0] += 100_000_000L;
        click(tree, "two", Accelerator.commandModifier());
        assertEquals(List.of(root), activated, "a modified double press toggles and activates nothing");
    }

    /**
     * With several rows selected, toggling the lead off moves the lead to the row selected most
     * recently that is still selected, as {@code Table} does (TREE-NEW-10); the cursor stays on
     * the row that was toggled.
     */
    @Test
    void aToggleOffMovesTheLeadToTheRowSelectedLastThatIsStillSelected() {
        Node root = forest();
        Tree<Node> tree = openForest(root);
        Node docs = root.children().get(0);
        Node readme = root.children().get(1);

        click(tree, "root", 0);
        click(tree, "docs", Accelerator.commandModifier());
        click(tree, "readme", Accelerator.commandModifier());
        assertEquals(List.of(root, docs, readme), tree.selectedNodes());
        assertEquals(readme, tree.leadNode());

        press(Keys.SPACE, 0); // toggles the cursor row, readme, off
        assertEquals(List.of(root, docs), tree.selectedNodes());
        assertEquals(docs, tree.leadNode(), "the lead falls back to the last row still selected");
        assertEquals(readme, tree.cursorNode(), "and the cursor stays on the toggled row");

        click(tree, "docs", Accelerator.commandModifier());
        assertEquals(root, tree.leadNode());
        assertEquals(docs, tree.cursorNode());
    }

    /** A press on {@code name}'s triangle: the band before the cell, at the row's middle. */
    private void pressTriangle(Tree<Node> tree, String name) {
        Widget cell = cellOf(tree, name);
        // The band sits immediately before the cell; half a band back from the cell's edge is
        // inside it at every depth, and the other test of this widget aims the same way.
        float x = cell.localToSceneX() - 8;
        float y = cell.localToSceneY() + cell.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);
    }

    /**
     * Closing the branch the cursor is in puts the cursor on the row that closed and leaves the
     * selection where it is, in every mode (decision 21 of 2026-09-14; ADR 044 §6): what
     * Explorer, Finder and GTK do. Before, the cursor stayed on the hidden row, where Left and
     * Right were dead, Up and Down restarted at the viewport's top, no row was ACTIVE, and Enter
     * activated something nobody could see (TREE-MISS-4).
     */
    @Test
    void closingTheBranchTheCursorIsInPutsTheCursorOnTheBranch() {
        Node root = forest();
        Node readme = root.children().get(1);
        Node two = Node.leaf("two");
        CountingModel model = new CountingModel(List.of(root, two));
        Tree<Node> tree = mount(model);
        tree.expand(root);
        scene.layoutPass(220, 200);
        scene.requestFocus(tree);
        press(Keys.DOWN);
        press(Keys.DOWN);
        press(Keys.DOWN);
        assertEquals(readme, tree.cursorNode(), "three arrows down land on readme");
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));

        pressTriangle(tree, "root");
        assertFalse(tree.isExpanded(root), "the triangle closed the root: " + drawn(tree));
        assertEquals(root, tree.cursorNode(), "and the cursor climbed onto it");
        assertEquals(List.of(readme), tree.selectedNodes(), "while the selection stayed hidden");
        assertEquals(readme, tree.leadNode());
        assertTrue(changes.stream().anyMatch(c -> c.aspect() == limn.scene.Change.Aspect.ACTIVE
                        && c.origin() == limn.scene.Change.Origin.USER),
                "announced as the user's cursor move: " + changes);

        press(Keys.DOWN);
        assertEquals(two, tree.cursorNode(), "and the arrows walk on from the row it landed on");

        tree.setSelectionMode(Tree.SelectionMode.NONE);
        tree.expand(root);
        scene.layoutPass(220, 200);
        press(Keys.UP); // from "two" back onto readme, under the re-opened root
        assertEquals(readme, tree.cursorNode());
        tree.collapse(root);
        scene.layoutPass(220, 200);
        assertEquals(root, tree.cursorNode(), "a collapse from code moves it the same way");
    }

    /**
     * Selecting a node under a closed branch selects it where it is — re-opening the branch
     * finds it selected — and neither reveals it nor moves the cursor, which stays on a row the
     * user can see (decision 21). Before, the cursor moved onto the hidden node with all of
     * TREE-MISS-4's consequences.
     */
    @Test
    void selectingAHiddenNodeSelectsItWithoutMovingTheCursor() {
        Node root = forest();
        Node docs = root.children().get(0);
        Node aMd = docs.children().get(0);
        CountingModel model = new CountingModel(List.of(root));
        Tree<Node> tree = mount(model);
        tree.expand(root);
        scene.layoutPass(220, 200);
        scene.requestFocus(tree);
        press(Keys.DOWN);
        assertEquals(root, tree.cursorNode());

        tree.setSelected(aMd);
        assertEquals(List.of(aMd), tree.selectedNodes(), "selected where it is");
        assertEquals(aMd, tree.leadNode());
        assertEquals(root, tree.cursorNode(), "the cursor stays on a row the user can see");
        assertFalse(tree.isExpanded(docs), "and nothing opened to reveal it");

        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        tree.setSelectedNodes(List.of(root, aMd));
        assertEquals(root, tree.cursorNode(), "the same for the programmatic set");

        tree.expand(docs);
        scene.layoutPass(220, 200);
        assertEquals(List.of(root, aMd), tree.selectedNodes(), "re-opening the branch finds it");
        press(Keys.DOWN);
        press(Keys.DOWN);
        assertEquals(aMd, tree.cursorNode(), "and the cursor can walk onto it now");
    }

    /** Whether some cell is drawing {@code name}, mounted anywhere. */
    private static boolean hasCell(Tree<Node> tree, String name) {
        for (Widget child : tree.children()) {
            if (child instanceof Label label && label.text().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** The cell drawing {@code name}, mounted anywhere; fails when none does. */
    private static Widget cell(Tree<Node> tree, String name) {
        for (Widget child : tree.children()) {
            if (child instanceof Label label && label.text().equals(name)) {
                return label;
            }
        }
        throw new AssertionError("no cell draws " + name + ": " + drawn(tree));
    }

    /**
     * The cursor row is kept realized while the tree holds the keyboard: spared by a scroll,
     * and mounted again, fresh from the model, after a refresh released every cell (decision 22
     * of 2026-09-14). When the focus leaves, the next pass releases it like any other row.
     */
    @Test
    void theCursorRowIsKeptRealizedWhileTheTreeHoldsTheKeyboardAndReleasedWhenItLeaves() {
        List<Node> many = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            many.add(Node.leaf("row " + i));
        }
        CountingModel model = new CountingModel(many);
        Tree<Node> tree = mount(model);
        scene.requestFocus(tree);
        press(Keys.DOWN);
        press(Keys.DOWN);
        assertEquals(many.get(1), tree.cursorNode());
        int builtBefore = java.util.Collections.frequency(model.cellsBuilt, "row 2");

        tree.scrollBy(10_000);
        scene.layoutPass(220, 200);
        assertTrue(hasCell(tree, "row 40"), "the wheel reached the end: " + drawn(tree));
        assertTrue(hasCell(tree, "row 2"), "and the cursor row was spared, outside the box");
        assertFalse(drawn(tree).contains("row 2"), "outside, not drawn among the visible rows");
        assertEquals(builtBefore, java.util.Collections.frequency(model.cellsBuilt, "row 2"),
                "spared, so not built again");
        float rowHeight = cell(tree, "row 40").height();
        assertEquals(rowHeight, cell(tree, "row 2").height(), "spared at its height");

        tree.refresh();
        scene.layoutPass(220, 200);
        assertTrue(hasCell(tree, "row 2"), "a refresh releases every cell and mounts it back");
        assertEquals(builtBefore + 1, java.util.Collections.frequency(model.cellsBuilt, "row 2"),
                "fresh from the model, since the refresh may have changed what it draws");
        Widget back = cell(tree, "row 2");
        assertEquals(rowHeight, back.height(),
                "laid out at the height it measures, like a placed row: a fresh cell has no "
                        + "height of its own, and a zero here reached the average row height "
                        + "and the node a reader is handed");
        assertTrue(back.y() + back.height() <= 0, "and wholly above the box: y=" + back.y());

        scene.requestFocus(null);
        scene.layoutPass(220, 200);
        assertFalse(hasCell(tree, "row 2"), "with the focus gone it is released: " + drawn(tree));
        assertEquals(many.get(1), tree.cursorNode(), "though the cursor still stands on it");
    }

    // --------------------------------------------------------------- refresh and pruning

    /**
     * Opening or closing a row asks the model nothing about the branches that stay closed: a
     * collapse cannot remove a node, so there is nothing to prune, and the walk that used to run
     * from every root on each press asked {@code children} of every closed branch — which a
     * generated model answers without end (TREE-NEW-2).
     */
    @Test
    void openingOrClosingARowDoesNotAskTheModelAboutClosedBranches() {
        Node deep = Node.of("closed", Node.of("c.1", Node.of("c.1.1", Node.leaf("c.1.1.1"))));
        Node other = Node.of("other", Node.leaf("o.1"));
        CountingModel model = new CountingModel(List.of(deep, other));
        Tree<Node> tree = mount(model);
        tree.setSelected(other);
        model.childrenAsked.clear();

        tree.expand(other);
        scene.layoutPass(220, 200);
        tree.collapse(other);
        scene.layoutPass(220, 200);

        assertFalse(model.childrenAsked.contains("c.1"),
                "nothing under the closed root was asked about: " + model.childrenAsked);
        assertFalse(model.childrenAsked.contains("c.1.1"), model.childrenAsked.toString());

        // And a model whose children are always new nodes: every expand used to overflow the
        // stack the moment anything was selected.
        Tree.Model<Node> endless = new Tree.Model<>() {
            @Override
            public List<Node> roots() {
                return List.of(Node.of("1"));
            }

            @Override
            public List<Node> children(Node node) {
                int n = Integer.parseInt(node.name());
                return List.of(Node.of(String.valueOf(2 * n)), Node.of(String.valueOf(2 * n + 1)));
            }

            @Override
            public boolean isLeaf(Node node) {
                return false;
            }

            @Override
            public Widget cellFor(Node node) {
                return new Label(node.name());
            }
        };
        Tree<Node> generated = mount(endless);
        generated.setSelected(Node.of("1"));
        generated.expand(Node.of("1"));
        scene.layoutPass(220, 200);
        assertEquals(3, generated.visibleRowCount(), "opened without walking the whole model");
        generated.collapse(Node.of("1"));
        scene.layoutPass(220, 200);
        assertEquals(1, generated.visibleRowCount());
        assertEquals(List.of(Node.of("1")), generated.selectedNodes());
    }

    /**
     * A hidden node's recorded path is the chain of its own ancestors and nothing of the branch
     * before it: a selection under a row that follows an open deeper branch is recorded at
     * {@code [b, b.1]} and confirmed by a refresh. The paths are read off one pass over the rows
     * carrying the ancestors by depth, and a chain that never shed the earlier branch would have
     * looked for {@code b} under {@code a.1.1} and dropped the selection (tree-A review,
     * 2026-09-14).
     */
    @Test
    void aRefreshKeepsASelectionHiddenUnderARowThatFollowsADeeperBranch() {
        Node aOne = Node.of("a.1", Node.leaf("a.1.1"));
        Node a = Node.of("a", aOne);
        Node bOne = Node.leaf("b.1");
        Node b = Node.of("b", bOne);
        CountingModel model = new CountingModel(List.of(a, b));
        Tree<Node> tree = mount(model);
        tree.expand(a);
        tree.expand(aOne);
        tree.expand(b);
        scene.layoutPass(220, 200);
        assertEquals(List.of("a", "a.1", "a.1.1", "b", "b.1"), drawn(tree));
        tree.setSelected(bOne);
        tree.collapse(b);
        scene.layoutPass(220, 200);
        assertEquals(List.of(bOne), tree.selectedNodes(), "hidden by the collapse, still selected");
        assertEquals(b, tree.cursorNode(), "the cursor climbed onto the row that closed over it");

        tree.refresh();
        scene.layoutPass(220, 200);
        assertEquals(List.of(bOne), tree.selectedNodes(),
                "the model still has it under b, at the path it was recorded at; asked: "
                        + model.childrenAsked);
        assertEquals(b, tree.cursorNode());
    }

    /**
     * A refresh under an open row whose children have to be fetched again keeps the selection
     * and the cursor while the load is in flight, and confirms them when it lands: the
     * file-manager case, where a watcher's refresh deselected the user's file on every change
     * (TREE-MISS-2). Before, the reload emptied the cache, the walk found nothing under the row,
     * and everything under it was dropped at once.
     */
    @Test
    void aRefreshKeepsTheSelectionUnderARowThatHasToLoadAgain() {
        Node remote = new Node("remote", List.of());
        Node one = Node.leaf("one");
        CountingModel model = new CountingModel(List.of(remote, Node.leaf("b")),
                Map.of("remote", List.of(one, Node.leaf("two"))));
        Tree<Node> tree = mount(model);
        scene.requestFocus(tree);
        tree.expand(remote);
        ui.pumpUntil(() -> tree.visibleRowCount() == 4);
        scene.layoutPass(220, 200);
        tree.setSelected(one);
        assertEquals(one, tree.cursorNode());
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));

        tree.refresh();
        scene.layoutPass(220, 200);
        assertEquals(2, tree.visibleRowCount(), "the reload is in flight: " + drawn(tree));
        assertEquals(List.of(one), tree.selectedNodes(),
                "kept while the row's children are on their way");
        assertEquals(one, tree.cursorNode(), "and so is the cursor");
        assertTrue(changes.stream().noneMatch(c -> c.aspect() == limn.scene.Change.Aspect.SELECTION),
                "nothing was dropped, so nothing was announced: " + changes);

        ui.pumpUntil(() -> tree.visibleRowCount() == 4);
        scene.layoutPass(220, 200);
        assertEquals(List.of(one), tree.selectedNodes(), "confirmed when the children landed");
        assertEquals(one, tree.cursorNode());
        assertTrue(hasCell(tree, "one"), "and the row is back: " + drawn(tree));
    }

    /**
     * The counterpart: when the reload no longer brings the selected node, it is dropped once
     * the load has landed and said so — one announcement later, as the tree's own adjustment —
     * and not before, when nothing could be known.
     */
    @Test
    void aRefreshDropsASelectionUnderAReloadedRowOnlyOnceTheLoadSaysItIsGone() {
        Node remote = new Node("remote", List.of());
        Node one = Node.leaf("one");
        Node two = Node.leaf("two");
        CountingModel model = new CountingModel(List.of(remote),
                Map.of("remote", List.of(one, two)));
        Tree<Node> tree = mount(model);
        tree.expand(remote);
        ui.pumpUntil(() -> tree.visibleRowCount() == 3);
        scene.layoutPass(220, 200);
        tree.setSelected(one);
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));

        model.reloads = Map.of("remote", List.of(two));
        tree.refresh();
        scene.layoutPass(220, 200);
        assertEquals(List.of(one), tree.selectedNodes(), "unknown until the load lands, so kept");

        ui.pumpUntil(() -> tree.visibleRowCount() == 2);
        scene.layoutPass(220, 200);
        assertTrue(tree.selectedNodes().isEmpty(), "the reload did not bring it, so it is gone");
        assertNull(tree.leadNode());
        assertNull(tree.cursorNode(), "the cursor cannot stand on it either");
        List<limn.scene.Change.Aspect> adjusted = changes.stream()
                .filter(c -> c.origin() == limn.scene.Change.Origin.ADJUSTMENT)
                .map(limn.scene.Change::aspect).toList();
        assertTrue(adjusted.contains(limn.scene.Change.Aspect.SELECTION), adjusted.toString());
        assertTrue(adjusted.contains(limn.scene.Change.Aspect.ACTIVE), adjusted.toString());
    }

    /**
     * A tree taken out of its scene while a row loads drops that load and loads again when it
     * comes back: before, the delivery was refused (the tree had no scene) but the row stayed in
     * {@code loading}, so after the re-attach it spun for good and never asked again
     * (TREE-NEW-3). The same for a tree opened onto a lazy row before it joined any scene, whose
     * load lands before the attach.
     */
    @Test
    void aTreeMovedWhileARowLoadsLoadsItAgainWhenItComesBack() throws Exception {
        Node lazy = new Node("remote", List.of());
        CountingModel model = new CountingModel(List.of(lazy),
                Map.of("remote", List.of(Node.leaf("one"), Node.leaf("two"))));
        Tree<Node> tree = new Tree<>(model);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(tree);
        scene = new Scene(column);
        scene.setTextRuler(RULER);
        scene.layoutPass(220, 200);
        scene.renderFrame(new RecordingTestCanvas(220, 200));

        tree.expand(lazy);
        assertEquals(1, model.loadCalls);
        column.remove(tree);
        ui.pumpUntil(() -> model.loadCalls == 1); // whatever the worker does now goes nowhere
        column.add(tree);
        scene.layoutPass(220, 200);

        ui.pumpUntil(() -> tree.visibleRowCount() == 3);
        scene.layoutPass(220, 200);
        assertEquals(2, model.loadCalls, "asked again on the way back in");
        assertTrue(tree.isExpanded(lazy), "still open");
        assertEquals(List.of("remote", "one", "two"), drawn(tree), "and showing what it has");

        // Never attached: the load lands with nowhere to deliver, and the attach asks again.
        CountingModel early = new CountingModel(List.of(lazy),
                Map.of("remote", List.of(Node.leaf("one"))));
        Tree<Node> opened = new Tree<>(early);
        opened.expand(lazy);
        assertEquals(1, early.loadCalls);
        for (int i = 0; i < 50 && early.loadCalls == 1; i++) {
            Thread.sleep(5); // let the worker finish and post the delivery the tree will refuse
            runtime.drain();
        }
        scene = new Scene(opened);
        scene.setTextRuler(RULER);
        scene.layoutPass(220, 200);
        ui.pumpUntil(() -> opened.visibleRowCount() == 2);
        assertEquals(2, early.loadCalls, "the dropped load was asked for again on attach");
    }

    /**
     * A failed load closes its row through the seam that announces it, as the tree's own
     * adjustment: a watcher hears {@code EXPANDED} for a row that went from open to closed, and
     * nothing about children that never changed (TREE-NEW-9). Before, only {@code CHILDREN} was
     * announced and the row closed silently.
     */
    @Test
    void aFailedLoadSaysTheRowClosed() {
        Node lazy = new Node("remote", List.of());
        Tree.Model<Node> failing = new Tree.Model<>() {
            @Override
            public List<Node> roots() {
                return List.of(lazy);
            }

            @Override
            public List<Node> children(Node node) {
                return null;
            }

            @Override
            public Work<List<Node>> load(Node node) {
                return Ui.work(progress -> {
                    throw new java.io.IOException("unreadable");
                });
            }

            @Override
            public Widget cellFor(Node node) {
                return new Label(node.name());
            }
        };
        Tree<Node> tree = mount(failing);
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));

        tree.expand(lazy);
        assertTrue(tree.isExpanded(lazy));
        ui.pumpUntil(() -> !tree.isExpanded(lazy));
        scene.layoutPass(220, 200);

        assertTrue(changes.stream().anyMatch(c -> c.aspect() == limn.scene.Change.Aspect.EXPANDED
                        && c.origin() == limn.scene.Change.Origin.ADJUSTMENT),
                "the row closing is announced as an adjustment: " + changes);
        assertTrue(changes.stream().noneMatch(c -> c.aspect() == limn.scene.Change.Aspect.CHILDREN
                        && c.origin() == limn.scene.Change.Origin.ADJUSTMENT),
                "and nothing about children, which never changed: " + changes);
        assertEquals(List.of("remote"), drawn(tree), "closed, with no line under it");
    }

    /**
     * A node is unique within a tree, by {@code equals}: two equal nodes in two places would
     * share one selection, one expansion and one accessible identity, all quietly wrong, so the
     * tree refuses them the moment both are visible and names the node (decision 15 of
     * 2026-09-14). Before, the second row was a silent shadow of the first (TREE-NEW-7).
     */
    @Test
    void twoEqualNodesVisibleAtOnceAreRefusedByName() {
        Node shared = Node.leaf("README.md");
        Node a = Node.of("a", shared);
        Node b = Node.of("b", shared);
        CountingModel model = new CountingModel(List.of(a, b));
        Tree<Node> tree = mount(model);

        tree.expand(a);
        scene.layoutPass(220, 200);
        assertEquals(3, tree.visibleRowCount(), "one README.md is fine");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> tree.expand(b), "the second README.md, equal to the first, is refused");
        assertTrue(refused.getMessage().contains("README.md"),
                "and the refusal names the node: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("path identity"),
                "and says what to do about it: " + refused.getMessage());
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
        assertNull(tree.leadNode(), "and nothing leads it");
        assertNull(tree.cursorNode(), "and the cursor does not stand on it either");
    }

    /**
     * A refresh keeps what is open and drops what every load brought, so an open row whose
     * children had to be fetched has to fetch them again. Otherwise it stays open with no
     * children and no job, which reads as "this node has nothing in it" — the statement a failed
     * load closes the row to avoid — and only closing and reopening it would ever read it again.
     *
     * <p>Both states a load can be in when the refresh comes: delivered, and still in flight.
     */
    @Test
    void aRefreshFetchesAgainWhatAnOpenRowHadToLoad() {
        Node lazy = new Node("remote", List.of());
        CountingModel model = new CountingModel(List.of(lazy),
                Map.of("remote", List.of(Node.leaf("one"), Node.leaf("two"))));
        Tree<Node> tree = mount(model);

        tree.expand(lazy);
        ui.pumpUntil(() -> tree.visibleRowCount() == 3);
        scene.layoutPass(220, 200);
        assertEquals(1, model.loadCalls);

        tree.refresh(); // the load has delivered
        scene.layoutPass(220, 200);
        assertTrue(tree.isExpanded(lazy), "a refresh keeps what is open");
        assertEquals(2, model.loadCalls,
                "and an open row whose load had delivered loads again, because the refresh "
                        + "dropped what it brought: " + tree.visibleRowCount() + " rows visible");

        tree.refresh(); // the load the last refresh started has not been delivered yet
        scene.layoutPass(220, 200);
        assertTrue(tree.isExpanded(lazy), "cancelling a load is not closing its row");
        assertEquals(3, model.loadCalls,
                "an open row whose load was still in flight loads again, because the refresh "
                        + "cancelled the job");

        ui.pumpUntil(() -> tree.visibleRowCount() == 3);
        scene.layoutPass(220, 200);
        assertEquals(3, tree.visibleRowCount(), "the children arrive again and are rows again");
        assertEquals(3, model.loadCalls, "and nothing is fetched twice for one refresh");
    }

    /**
     * The open row that must load again may not be a row yet when the refresh comes: under a
     * parent that also had to load, it only reappears once its parent's children arrive. So the
     * reload has to follow the rows as they are walked, not just the rows the refresh can see.
     */
    @Test
    void aRefreshFetchesAgainUnderAnOpenRowThatAlsoHadToLoad() {
        Node inner = new Node("inner", List.of());
        Node outer = new Node("outer", List.of());
        CountingModel model = new CountingModel(List.of(outer),
                Map.of("outer", List.of(inner), "inner", List.of(Node.leaf("deep"))));
        Tree<Node> tree = mount(model);

        tree.expand(outer);
        ui.pumpUntil(() -> tree.visibleRowCount() == 2);
        tree.expand(inner);
        ui.pumpUntil(() -> tree.visibleRowCount() == 3);
        scene.layoutPass(220, 200);
        assertEquals(2, model.loadCalls);

        tree.refresh();
        scene.layoutPass(220, 200);
        assertEquals(3, model.loadCalls, "the outer row, which the refresh can see, loads again");

        ui.pumpUntil(() -> tree.visibleRowCount() >= 2);
        assertEquals(4, model.loadCalls,
                "and the inner row, open again the moment its parent's children arrive, loads "
                        + "again with them rather than sitting open and empty");

        ui.pumpUntil(() -> tree.visibleRowCount() == 3);
        scene.layoutPass(220, 200);
        assertTrue(tree.isExpanded(inner), "still open, and showing what it has");
    }

    /**
     * An open row whose children are on their way says so under itself, in the place its children
     * will take, and stops saying it when they land (ADR 044 §2, his choice of both a spinner and
     * a line). Before, it sat open over nothing, which is what a node with no children looks like.
     *
     * <p>The line is the tree's own widget and not a node: it does not count as a visible row,
     * and it is never handed to the model to recycle, since the model did not build it.
     */
    @Test
    void anOpenRowWhoseChildrenAreOnTheirWaySaysSoUnderItselfUntilTheyLand() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        Node remote = new Node("remote", List.of());
        CountingModel model = new CountingModel(List.of(remote, Node.leaf("b")),
                Map.of("remote", List.of(Node.leaf("one"), Node.leaf("two"))));
        Tree<Node> tree = mount(model);

        tree.expand(remote);
        scene.layoutPass(220, 200);
        assertEquals(List.of("remote", "Loading…", "b"), drawn(tree),
                "the line sits where the children will, and the row below moves down for it");
        assertEquals(2, tree.visibleRowCount(), "the line is not a node");

        ui.pumpUntil(() -> tree.visibleRowCount() == 4);
        scene.layoutPass(220, 200);
        assertEquals(List.of("remote", "one", "two", "b"), drawn(tree),
                "and when the children land they take its place");
        for (Widget cell : model.recycled) {
            assertFalse(cell instanceof Label label && label.text().equals("Loading…"),
                    "the line is the tree's, so the model is never handed it to pool");
        }
    }

    /**
     * The line is not a row anyone stands on. The arrows walk past it in the direction they
     * travel, and a click on it lands on the row it belongs to.
     *
     * <p>Its node is its row's, which is what makes a click right. It is also what made Down
     * wrong: stepping onto the line resolved to the row the cursor was already on, so Down from a
     * loading row could never get below it.
     */
    @Test
    void theArrowsWalkPastALoadingLineAndAClickOnItLandsOnItsRow() {
        Node remote = new Node("remote", List.of());
        Node below = Node.leaf("b");
        CountingModel model = new CountingModel(List.of(remote, below),
                Map.of("remote", List.of(Node.leaf("one"))));
        Tree<Node> tree = mount(model);
        scene.requestFocus(tree);

        press(Keys.DOWN);  // onto remote
        press(Keys.RIGHT); // opens it, and its load starts
        assertTrue(tree.isExpanded(remote));
        assertEquals(remote, tree.cursorNode());

        press(Keys.RIGHT);
        assertEquals(remote, tree.cursorNode(),
                "Right into a row still loading has nothing to step onto, so it stays");

        press(Keys.DOWN);
        assertEquals(below, tree.cursorNode(), "Down goes past the line to the next node");

        press(Keys.UP);
        assertEquals(remote, tree.cursorNode(), "and Up comes back past it to the row it belongs to");

        press(Keys.END);
        assertEquals(below, tree.cursorNode());

        tree.setSelected(below);
        Widget line = null;
        for (Widget child : tree.children()) {
            if (child instanceof Label label && !label.text().equals("remote")
                    && !label.text().equals("b")) {
                line = child;
            }
        }
        assertTrue(line != null, "the loading line is mounted: " + drawn(tree));
        float x = line.localToSceneX() + line.width() / 2;
        float y = line.localToSceneY() + line.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
        assertEquals(List.of(remote), tree.selectedNodes(),
                "a click on the line selects the row it belongs to, the folder that is loading");
    }

    /**
     * A row whose load finds nothing stays an open branch, and the line under it says "Empty"
     * where its children would be, in the place and the voice of the "Loading…" line it replaces
     * (decision 45 of 2026-09-14). Before, the loading line simply vanished and left an open
     * triangle over nothing, which reads as a row that never loaded (TREE-NEW-13). The cached
     * empty answer opens onto the same line without a second load.
     */
    @Test
    void aLoadThatFindsNothingLeavesAnOpenBranchWithAnEmptyLineUnderIt() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        Node trash = new Node("trash", List.of());
        CountingModel model = new CountingModel(List.of(trash, Node.leaf("b")),
                Map.of("trash", List.of()));
        Tree<Node> tree = mount(model);
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));

        tree.expand(trash);
        scene.layoutPass(220, 200);
        assertEquals(List.of("trash", "Loading…", "b"), drawn(tree));

        ui.pumpUntil(() -> changes.stream().anyMatch(
                c -> c.aspect() == limn.scene.Change.Aspect.CHILDREN
                        && c.origin() == limn.scene.Change.Origin.ADJUSTMENT));
        scene.layoutPass(220, 200);
        assertTrue(tree.isExpanded(trash), "the row stays an open branch");
        assertEquals(List.of("trash", "Empty", "b"), drawn(tree),
                "and the line under it says it holds nothing");
        assertEquals(2, tree.visibleRowCount(), "the line is not a node");

        tree.collapse(trash);
        scene.layoutPass(220, 200);
        assertEquals(List.of("trash", "b"), drawn(tree), "closed, the line goes with the row");
        tree.expand(trash);
        scene.layoutPass(220, 200);
        assertEquals(List.of("trash", "Empty", "b"), drawn(tree),
                "and reopened it says so at once, from what the load already found");
        assertEquals(1, model.loadCalls, "without asking the model to load it again");
        for (Widget cell : model.recycled) {
            assertFalse(cell instanceof Label label
                            && (label.text().equals("Empty") || label.text().equals("Loading…")),
                    "neither line is the model's to pool");
        }
    }

    /**
     * Right on an open row with nothing in it stays on the row, whether the model calls an empty
     * folder a branch or a load found it empty (TREE-MISS-1, decision 45): the arrow that steps
     * into a row has no child to step onto. Before, it stepped onto whatever row came next — a
     * sibling, or an ancestor's sibling — because the step-in only asked that a next row existed.
     * The arrows still walk past the line in both directions.
     */
    @Test
    void rightOnAnOpenBranchWithNothingInItStaysOnIt() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        Node folder = new Node("folder", List.of());
        Node next = Node.leaf("next");
        Tree<Node> tree = mount(new Tree.Model<>() {
            @Override
            public List<Node> roots() {
                return List.of(folder, next);
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public boolean isLeaf(Node node) {
                // A folder is a branch whatever it holds, which is TreeExample's shape.
                return node != folder && node.children().isEmpty();
            }

            @Override
            public Widget cellFor(Node node) {
                return new Label(node.name());
            }
        });
        scene.requestFocus(tree);

        press(Keys.DOWN);  // onto the folder
        press(Keys.RIGHT); // opens it
        assertTrue(tree.isExpanded(folder));
        scene.layoutPass(220, 200);
        assertEquals(List.of("folder", "Empty", "next"), drawn(tree),
                "an eager branch with nothing in it opens onto the same line");
        press(Keys.RIGHT);
        assertEquals(folder, tree.cursorNode(), "Right into an empty branch stays on it");
        press(Keys.DOWN);
        assertEquals(next, tree.cursorNode(), "Down walks past the line");
        press(Keys.UP);
        assertEquals(folder, tree.cursorNode(), "and Up back past it to its row");

        Node trash = new Node("trash", List.of());
        CountingModel lazy = new CountingModel(List.of(trash, Node.leaf("after")),
                Map.of("trash", List.of()));
        Tree<Node> loaded = mount(lazy);
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));
        scene.requestFocus(loaded);
        press(Keys.DOWN);  // onto trash
        press(Keys.RIGHT); // opens it, and its load starts
        ui.pumpUntil(() -> changes.stream().anyMatch(
                c -> c.aspect() == limn.scene.Change.Aspect.CHILDREN
                        && c.origin() == limn.scene.Change.Origin.ADJUSTMENT));
        scene.layoutPass(220, 200);
        press(Keys.RIGHT);
        assertEquals(trash, loaded.cursorNode(),
                "and so does Right on a row whose load found nothing");
    }

    /**
     * The spinner is drawn where the open triangle goes, turns on its own while the load runs,
     * and gives the triangle back when the load lands. It turns on the scene's clock rather than
     * on an input, so nothing but its own ticker damages the band in between.
     */
    @Test
    void aLoadingRowTurnsAnArcWhereItsTriangleGoesAndGetsTheTriangleBackWhenItLands() {
        Node remote = new Node("remote", List.of());
        CountingModel model = new CountingModel(List.of(remote, Node.leaf("b")),
                Map.of("remote", List.of(Node.leaf("one"))));
        long[] now = {1_000_000_000L};
        Tree<Node> tree = new Tree<>(model);
        scene = new Scene(tree, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.layoutPass(220, 200);
        scene.renderFrame(new RecordingTestCanvas(220, 200));

        tree.expand(remote);
        scene.layoutPass(220, 200);
        TwistyCanvas opened = new TwistyCanvas(220, 200);
        scene.renderFrame(opened);
        assertEquals(1, opened.twisties.size(), "remote is the one row with a band to draw");
        TwistyCanvas.Painted arc = opened.twisties.get(0);
        assertTrue(arc.points() > 3,
                "a curve where the triangle goes, not the triangle's three points: " + arc);

        now[0] += 100_000_000L;
        TwistyCanvas turned = new TwistyCanvas(220, 200);
        scene.renderFrame(turned);
        assertEquals(1, turned.twisties.size(),
                "a tenth of a second later the spinner repaints its band by itself: " + turned.twisties);
        TwistyCanvas.Painted later = turned.twisties.get(0);
        assertTrue(Math.hypot(later.startX() - arc.startX(), later.startY() - arc.startY()) > 0.5,
                "and the arc has turned: it started at " + arc + " and now at " + later);

        ui.pumpUntil(() -> tree.visibleRowCount() == 3);
        scene.layoutPass(220, 200);
        scene.requestRender();
        TwistyCanvas landed = new TwistyCanvas(220, 200);
        scene.renderFrame(landed);
        assertEquals(1, landed.twisties.size(), landed.twisties.toString());
        assertEquals(3, landed.twisties.get(0).points(),
                "once the children land the row is simply open, with its triangle back");
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
        assertEquals(root, tree.cursorNode(), "the first arrow has to land somewhere");

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

    private static limn.scene.Constraints unbounded() {
        return new limn.scene.Constraints(0, limn.scene.Constraints.UNBOUNDED_LIMIT, 0,
                limn.scene.Constraints.UNBOUNDED_LIMIT);
    }

    /**
     * Under an unbounded height the tree is a count of seed rows tall, and stays so once its
     * rows are measured (decision 44 of 2026-09-14). It shipped answering the mean of the rows
     * it happened to have mounted, which is the seed before the first pass and the rows' own
     * height after it, so its first contained layout inside a column moved its size and every
     * later scroll that mounted rows of another height moved it again (T5).
     */
    @Test
    void theUnboundedHeightIsTheSeedsAndDoesNotMoveOnceRowsAreMeasured() {
        for (limn.scene.ControlSize step : limn.scene.ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            List<Node> many = new ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                many.add(Node.leaf("row " + i));
            }
            Tree<Node> tree = mount(new CountingModel(many));
            tree.setControlSize(step);
            scene.layoutPass(220, 200);
            assertFalse(tree.children().size() <= 2, "the fixture has to have measured rows");

            assertEquals(8 * t.listRowSeed(), tree.measure(unbounded()).height(), 0.01f,
                    step + ": rows of the ruler's height were measured and the preference did"
                            + " not move");
            wheel(tree, -10);
            scene.layoutPass(220, 200);
            assertEquals(8 * t.listRowSeed(), tree.measure(unbounded()).height(), 0.01f,
                    step + ": nor after a scroll realized other rows");
        }
    }

    @Test
    void setVisibleRowsChangesTheUnboundedHeightAndRefusesLessThanOne() {
        List<Node> many = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            many.add(Node.leaf("row " + i));
        }
        Tree<Node> tree = mount(new CountingModel(many));
        SizeTokens t = SizeTokens.of(limn.scene.ControlSize.MEDIUM);
        assertEquals(8, tree.visibleRows(), "the default is the table's");

        tree.setVisibleRows(3);

        assertEquals(3 * t.listRowSeed(), tree.measure(unbounded()).height(), 0.01f,
                "three seed rows: a count of the seed, never of the realized rows");
        assertEquals(t.listWidth(), tree.measure(unbounded()).width(), 0.01f,
                "the width is untouched");
        assertThrows(IllegalArgumentException.class, () -> tree.setVisibleRows(0));
        assertEquals(3, tree.visibleRows(), "a refused count changes nothing");
        assertEquals(200, tree.measure(new limn.scene.Constraints(0, 300, 0, 200)).height(),
                0.01f, "a bounded height from the parent wins over the preference");
    }

    /**
     * The chain with enough leaves after it to overflow the box downward as well: the fixture
     * that scrolls both ways, wrapped in a scroll pane that scrolls only where the tree cannot.
     * The pane's content is the tree at its unbounded preference over a tall filler, so a detent
     * the tree lets through has somewhere visible to go.
     */
    private Tree<Node> chainInAPane(Map<String, Widget> cells) {
        Node top = chain(14);
        List<Node> roots = new ArrayList<>();
        roots.add(top);
        for (int i = 1; i <= 40; i++) {
            roots.add(Node.leaf("row " + i));
        }
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
                Label cell = new Label(node.name());
                cells.put(node.name(), cell);
                return cell;
            }
        });
        for (Node node = top; node != null;
                node = node.children().isEmpty() ? null : node.children().get(0)) {
            tree.expand(node);
        }
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(tree);
        column.add(new Widget() {
            @Override
            protected limn.scene.Size onMeasure(limn.scene.Constraints c) {
                return c.constrain(c.maxWidth(), 400);
            }
        });
        scene = new Scene(new ScrollView(column));
        scene.setTextRuler(RULER);
        scene.layoutPass(220, 200);
        canvas = new RecordingTestCanvas(220, 200);
        scene.renderFrame(canvas);
        return tree;
    }

    /**
     * The cell drawing {@code name} right now: the map holds the newest one the model built,
     * and a row that scrolled out and back comes back as a new cell, so a case reads it after
     * every step rather than holding one.
     */
    private static Widget mounted(Map<String, Widget> cells, String name) {
        Widget cell = cells.get(name);
        assertTrue(cell != null && cell.parent() != null, name + " has to be a mounted row");
        return cell;
    }

    /**
     * A trackpad flick carries both axes in one event, and the tree scrolls both: the version
     * that shipped read scrollX first and dropped the scrollY beside it, so a diagonal flick
     * over a deep tree walked sideways and never down (the table's TABLE-NEW-12, in the same
     * code). Shift still turns a one-wheel mouse's notch sideways. Read off the deepest row,
     * which stays in view across the notches here; the rows above it scroll out and are
     * recycled.
     */
    @Test
    void aWheelCarryingBothAxesScrollsBoth() {
        Map<String, Widget> cells = new java.util.HashMap<>();
        Tree<Node> tree = chainInAPane(cells);
        Widget deepest = mounted(cells, "level-14");
        float xBefore = deepest.x();
        float yBefore = deepest.y();

        float x = tree.localToSceneX() + 20;
        float y = tree.localToSceneY() + 20;
        scene.mouseMoved(x, y);
        scene.scrolled(-1, -1, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);

        deepest = mounted(cells, "level-14");
        assertEquals(xBefore - Strokes.WHEEL_STEP, deepest.x(), 0.01f,
                "the sideways half of the flick walked the outline");
        assertEquals(yBefore - Strokes.WHEEL_STEP, deepest.y(), 0.01f,
                "and the vertical half scrolled the rows in the same event");

        scene.scrolled(0, -1, x, y);
        scene.inputBatchEnded();
        scene.keyEvent(Keys.LEFT_SHIFT, true, false, Keys.MOD_SHIFT);
        scene.scrolled(0, -1, x, y); // a plain vertical notch, Shift held
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);
        deepest = mounted(cells, "level-14");
        assertEquals(xBefore - 2 * Strokes.WHEEL_STEP, deepest.x(), 0.01f,
                "Shift turns a vertical notch sideways");
        assertEquals(yBefore - 2 * Strokes.WHEEL_STEP, deepest.y(), 0.01f,
                "and the plain notch before it scrolled down alone");
    }

    /**
     * Decision 44's second half, the tree's copy: a detent that finds the tree at either end of
     * its scroll is left for the scroller that holds it, so a tree inside a scroll pane is not a
     * wall the wheel cannot get past. The tree consumed every detent while its content
     * overflowed, whichever way the detent pointed; a short tree already let them through.
     */
    @Test
    void aWheelAtEitherEndOfTheTreePassesToTheScrollerThatHoldsIt() {
        Map<String, Widget> cells = new java.util.HashMap<>();
        Tree<Node> tree = chainInAPane(cells);
        ScrollView pane = (ScrollView) scene.root();
        float x = tree.localToSceneX() + 20;
        float y = tree.localToSceneY() + 20;
        scene.mouseMoved(x, y);

        // Up at the top: the tree has nowhere to go, so the pane takes the detent — and it too is
        // at its top, so nothing moves and nothing broke.
        scene.scrolled(0, 1, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);
        assertEquals(0, mounted(cells, "level-1").y(), 0.01f, "the tree stayed at its first row");
        assertEquals(0, pane.offsetY(), 0.01f, "and the pane had nowhere to go either");

        // Down: the tree takes every detent until it rests on its last row, then the pane moves.
        int notches = 0;
        while (pane.offsetY() == 0 && notches < 100) {
            scene.scrolled(0, -1, x, y);
            scene.inputBatchEnded();
            scene.layoutPass(220, 200);
            notches++;
        }
        assertTrue(notches > 1 && notches < 100,
                "the tree scrolled itself first and then let a detent through: " + notches);
        assertEquals(Strokes.WHEEL_STEP, pane.offsetY(), 0.01f, "one notch of the pane");
        Widget last = mounted(cells, "row 40");
        assertTrue(last.y() + last.height() <= tree.height() + 0.01f,
                "the tree is at its end when the pane starts moving");

        // Up: the tree is at its end and not at its top, so it takes the detent back first.
        scene.scrolled(0, 1, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);
        assertEquals(Strokes.WHEEL_STEP, pane.offsetY(), 0.01f,
                "the tree could move up, so it did and the pane did not");

        // Up to the top and past it: the pane takes what the tree cannot. Then sideways at the
        // leading edge, which nothing can use, and toward the trailing edge, which the tree can.
        for (int i = 0; i < 40; i++) {
            scene.scrolled(0, 1, x, y);
            scene.inputBatchEnded();
        }
        scene.layoutPass(220, 200);
        assertEquals(0, pane.offsetY(), 0.01f, "the pane took the detents the tree could not");
        float atStart = mounted(cells, "level-14").x();
        scene.scrolled(1, 0, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);
        assertEquals(atStart, mounted(cells, "level-14").x(), 0.01f,
                "a sideways notch at the leading edge moves nothing");
        scene.scrolled(-1, 0, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(220, 200);
        assertEquals(atStart - Strokes.WHEEL_STEP, mounted(cells, "level-14").x(), 0.01f,
                "and the other way is the tree's");
    }

    /** Where each stroked path was painted, which for these fixtures is only the triangles. */
    private static final class TwistyCanvas extends ComponentTestBase.FakeCanvas {

        /**
         * One painted path, in the tree's own coordinates: its leading point, where it starts,
         * and how many points it flattens to — three for a triangle, many for an arc.
         */
        record Painted(float x, float y, float startX, float startY, int points) {
        }

        final List<Painted> twisties = new ArrayList<>();

        TwistyCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawPath(Path2D path, float strokeWidth, Paint paint) {
            float[] leading = {Float.MAX_VALUE, 0};
            float[] start = {Float.NaN, Float.NaN};
            int[] points = {0};
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
                    if (points[0]++ == 0) {
                        start[0] = x;
                        start[1] = y;
                    }
                    if (x < leading[0]) {
                        leading[0] = x;
                        leading[1] = y;
                    }
                }
            });
            twisties.add(new Painted(leading[0], leading[1], start[0], start[1], points[0]));
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

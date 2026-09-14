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

    /**
     * A triangle painted where the hit test does not look is a control that cannot be pressed,
     * and a sideways offset is exactly the kind of change that separates the two. The band sits
     * immediately before the cell, so the press is aimed from the cell's own position rather than
     * by re-deriving the indent here.
     */
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

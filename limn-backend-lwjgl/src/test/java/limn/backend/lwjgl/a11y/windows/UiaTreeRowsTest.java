package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.backend.lwjgl.a11y.ProbeWindow;
import limn.components.Label;
import limn.components.tree.Tree;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows navigation of a real {@link Tree}'s published rows (decision 4; semantics 6; W4):
 * the tree the widget publishes, not one built by hand, walked the way NVDA 2024.4.2 walks it.
 *
 * <p>NVDA gives a tree item the level it counts from the item's TreeItem ancestors and ignores
 * UIA's Level (readings/nvda-2024.4.2-uia.md §2). A Tree publishes its rows flat under the tree, so
 * without nesting every row would be heard at level one; this pins that the ancestors navigation
 * answers agree with the level the widget published, and that the Level property says the same.
 */
class UiaTreeRowsTest {

    private record Node(String name, List<Node> kids) {
        static Node of(String name, Node... kids) {
            return new Node(name, List.of(kids));
        }
    }

    /** Keeps the last tree published. */
    private static final class Recorder implements AccessibilityBridge {
        AccessibleTree last = AccessibleTree.EMPTY;

        @Override
        public boolean isListening() {
            return true;
        }

        @Override
        public void publish(AccessibleTree tree, boolean reentrant) {
            last = tree;
        }

        @Override
        public void emit(AccessibleEvent event) {
        }
    }

    private final AtomicLong nanos = new AtomicLong();
    private final HeadlessUi ui = new HeadlessUi(nanos::get);

    @AfterEach
    void close() {
        ui.close();
    }

    private Scene scene;
    private Tree<Node> tree;
    private Recorder recorder;

    /** A Tree over {@code roots} in a box {@code height} pixels tall, bound and rendered. */
    private void bind(List<Node> roots, float height) {
        tree = new Tree<>(new Tree.Model<>() {
            @Override
            public List<Node> roots() {
                return roots;
            }

            @Override
            public List<Node> children(Node node) {
                return node.kids();
            }

            @Override
            public Widget cellFor(Node node) {
                // A composite cell of a fixed height (a headless canvas measures text as nothing),
                // so a row has content of its own under it as well as child rows.
                return new SizedBox(200, 20, new Label(node.name()));
            }

            @Override
            public limn.i18n.I18nString nameOf(Node node) {
                return limn.i18n.I18nString.literal(node.name());
            }
        });
        Column root = new Column();
        root.add(new SizedBox(300, height, tree));
        scene = new Scene(root, nanos::get);
        ProbeWindow window = new ProbeWindow();
        recorder = new Recorder();
        window.accessibility = recorder;
        scene.bind(window);
        frames();
    }

    private AccessibleTree frames() {
        for (int frame = 0; frame < 3; frame++) {
            nanos.addAndGet(16_000_000L);
            scene.renderFrame(new NoopCanvas(800, 600));
        }
        return recorder.last;
    }

    /** How many TreeItem ancestors navigation gives a node: what NVDA 2024.4.2 counts. */
    private static int treeItemAncestors(AccessibleTree published, int index) {
        int ancestors = 0;
        for (int at = UiaFragment.navigate(published, index, UiaIds.NAVIGATE_DIRECTION_PARENT);
                at != AccessibleNode.NONE;
                at = UiaFragment.navigate(published, at, UiaIds.NAVIGATE_DIRECTION_PARENT)) {
            if (published.node(at).role() == Accessible.Role.TREE_ITEM) {
                ancestors++;
            }
        }
        return ancestors;
    }

    /** The name of each node's parent in the model, a root's being absent. */
    private static java.util.Map<String, String> parentsOf(List<Node> roots) {
        java.util.Map<String, String> parents = new java.util.HashMap<>();
        java.util.ArrayDeque<Node> pending = new java.util.ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            Node node = pending.poll();
            for (Node kid : node.kids()) {
                parents.put(kid.name(), node.name());
                pending.add(kid);
            }
        }
        return parents;
    }

    @Test
    void aTreeRowsAncestorsInNavigationAreItsPublishedLevelAndSoIsItsLevelProperty() {
        Node reports = Node.of("Reports", Node.of("2025", Node.of("Q1"), Node.of("Q2")),
                Node.of("2026"));
        Node photos = Node.of("Photos", Node.of("Trips"));
        List<Node> roots = List.of(Node.of("Documents"), reports, photos);
        bind(roots, 400);
        tree.expand(reports).expand(reports.kids().get(0)).expand(photos);
        AccessibleTree published = frames();

        List<String> heard = new ArrayList<>();
        int rows = 0;
        for (int i = 0; i < published.nodeCount(); i++) {
            AccessibleNode row = published.node(i);
            if (row.role() != Accessible.Role.TREE_ITEM) {
                continue;
            }
            rows++;
            int ancestors = treeItemAncestors(published, i);
            heard.add(row.name() + " " + (ancestors + 1));
            assertEquals(row.hierarchy().level(), ancestors + 1,
                    row.name() + ": the TreeItem ancestors NVDA counts agree with the published level");
            assertEquals(row.hierarchy().level(), UiaProperties.valueOf(row, UiaIds.LEVEL),
                    row.name() + ": and the Level property answers the same number");
        }
        assertEquals(List.of("Documents 1", "Reports 1", "2025 2", "Q1 3", "Q2 3", "2026 2",
                "Photos 1", "Trips 2"), heard, "every open row, at its depth");
        assertEquals(8, rows, "the fixture published every open row");
    }

    /**
     * What a scroll into a branch leaves of the nesting (the windows-B review, 2026-09-15). The
     * rows above the viewport are not mounted, the branch's own row among them, so its child rows
     * have no published parent row: navigation hangs them under the tree, and NVDA, counting
     * TreeItem ancestors, hears them one level too high up (level 1 for a level-2 row). That is
     * the degradation ADR 039 §2.1 records. What it must never do is nest a row under a published
     * row that is not its parent in the outline: every TreeItem parent navigation answers is the
     * model's parent, and no row counts more ancestors than its level.
     */
    @Test
    void aTreeScrolledIntoABranchNestsNoRowUnderARowThatIsNotItsParent() {
        Node[] kids = new Node[80];
        for (int i = 0; i < kids.length; i++) {
            kids[i] = Node.of("A" + (i + 1), Node.of("A" + (i + 1) + "x"));
        }
        Node a = Node.of("A", kids);
        List<Node> roots = List.of(a, Node.of("B"));
        bind(roots, 200);
        tree.expand(a);
        for (int i = 48; i < 60; i++) {
            tree.expand(kids[i]);
        }
        frames();
        tree.scrollBy(0, 1000);
        AccessibleTree published = frames();

        java.util.Map<String, String> parents = parentsOf(roots);
        List<String> orphans = new ArrayList<>();
        int nested = 0;
        for (int i = 0; i < published.nodeCount(); i++) {
            AccessibleNode row = published.node(i);
            if (row.role() != Accessible.Role.TREE_ITEM) {
                continue;
            }
            int parent = UiaFragment.navigate(published, i, UiaIds.NAVIGATE_DIRECTION_PARENT);
            AccessibleNode up = published.node(parent);
            if (up.role() == Accessible.Role.TREE_ITEM) {
                nested++;
                assertEquals(parents.get(row.name()), up.name(),
                        row.name() + ": a row nests only under its own parent row");
            } else {
                assertEquals(row.parent(), parent, row.name() + ": an orphan hangs where it is stored");
                if (parents.containsKey(row.name())) {
                    orphans.add(row.name());
                }
            }
            assertTrue(treeItemAncestors(published, i) + 1 <= row.hierarchy().level(),
                    row.name() + ": never more ancestors than its level");
        }
        assertNull(limn.testing.AccessibleTrees.named(published, "A"),
                "the precondition: the branch's own row is scrolled away");
        assertTrue(nested > 0, "an A<n>x row still nests under its realized A<n>: " + orphans);
        assertTrue(orphans.stream().anyMatch(n -> n.matches("A\\d+")),
                "the A<n> rows, whose parent row is scrolled away, hang under the tree: " + orphans);
        for (String orphan : orphans) {
            assertNull(limn.testing.AccessibleTrees.named(published, parents.get(orphan)),
                    orphan + " hangs under the tree only because its parent row is not published");
        }
    }

    /**
     * The cursor row a focused tree keeps realized off screen (decision 22) is published among the
     * viewport's rows, and its row index is far from theirs. Before the windows-B review it could
     * be taken for their parent: a root row kept as the cursor, with the viewport inside another
     * root's children, made those children nest under the cursor row, so NVDA heard them as the
     * cursor row's children at the right level and the wrong place.
     */
    @Test
    void theKeptCursorRowIsNeverTheParentOfTheRowsInTheViewport() {
        Node[] kids = new Node[80];
        for (int i = 0; i < kids.length; i++) {
            kids[i] = Node.of("S" + (i + 1));
        }
        Node r = Node.of("R", Node.of("R1"));
        Node s = Node.of("S", kids);
        List<Node> roots = List.of(r, s);
        bind(roots, 200);
        tree.expand(s);
        scene.requestFocus(tree);
        tree.setSelected(r);
        frames();
        tree.scrollBy(0, 1000);
        AccessibleTree published = frames();

        AccessibleNode kept = limn.testing.AccessibleTrees.named(published, "R");
        assertNotNull(kept, "the precondition: the cursor row is kept realized");
        assertFalse(kept.has(Accessible.State.SHOWING), "and scrolled out of the box");
        int keptIndex = published.indexOf(kept.id());
        int viewportRows = 0;
        for (int i = 0; i < published.nodeCount(); i++) {
            AccessibleNode row = published.node(i);
            if (row.role() != Accessible.Role.TREE_ITEM || row == kept) {
                continue;
            }
            viewportRows++;
            assertNotEquals(keptIndex, UiaFragment.navigate(published, i, UiaIds.NAVIGATE_DIRECTION_PARENT),
                    row.name() + " is S's child, not the kept cursor row's");
        }
        assertTrue(viewportRows > 0, "the viewport published rows");
        for (int child = UiaFragment.navigate(published, keptIndex, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD);
                child != AccessibleNode.NONE;
                child = UiaFragment.navigate(published, child, UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING)) {
            assertNotEquals(Accessible.Role.TREE_ITEM, published.node(child).role(),
                    "the kept cursor row, a collapsed R, holds its own cell content and no row: "
                            + published.node(child).name());
        }
    }
}

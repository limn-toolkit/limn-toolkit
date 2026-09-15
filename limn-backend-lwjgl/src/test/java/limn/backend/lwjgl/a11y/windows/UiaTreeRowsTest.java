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

    @Test
    void aTreeRowsAncestorsInNavigationAreItsPublishedLevelAndSoIsItsLevelProperty() {
        Node reports = Node.of("Reports", Node.of("2025", Node.of("Q1"), Node.of("Q2")),
                Node.of("2026"));
        Node photos = Node.of("Photos", Node.of("Trips"));
        List<Node> roots = List.of(Node.of("Documents"), reports, photos);
        Tree<Node> tree = new Tree<>(new Tree.Model<>() {
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
        tree.expand(reports).expand(reports.kids().get(0)).expand(photos);
        Column root = new Column();
        root.add(new SizedBox(300, 400, tree));
        Scene scene = new Scene(root, nanos::get);
        ProbeWindow window = new ProbeWindow();
        Recorder recorder = new Recorder();
        window.accessibility = recorder;
        scene.bind(window);
        for (int frame = 0; frame < 3; frame++) {
            nanos.addAndGet(16_000_000L);
            scene.renderFrame(new NoopCanvas(800, 600));
        }
        AccessibleTree published = recorder.last;

        List<String> heard = new ArrayList<>();
        int rows = 0;
        for (int i = 0; i < published.nodeCount(); i++) {
            AccessibleNode row = published.node(i);
            if (row.role() != Accessible.Role.TREE_ITEM) {
                continue;
            }
            rows++;
            int ancestors = 0;
            for (int at = UiaFragment.navigate(published, i, UiaIds.NAVIGATE_DIRECTION_PARENT);
                    at != AccessibleNode.NONE;
                    at = UiaFragment.navigate(published, at, UiaIds.NAVIGATE_DIRECTION_PARENT)) {
                if (published.node(at).role() == Accessible.Role.TREE_ITEM) {
                    ancestors++;
                }
            }
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
}

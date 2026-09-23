package limn.components;

import limn.components.tree.Tree;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.RowsContract;
import limn.testing.a11y.RowsSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link Tree} under the rows contract (ADR 045 §4): the variant where the cursor is separate
 * from the selection, with a multiple selection to enter, rows that open, and rows it scrolls
 * itself. One branch open at the top, so that the visible rows are its title, its two children
 * and four leaves after it — seven rows of twelve points in a box of fifty.
 */
class TreeRowsContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theRowsContract() {
        return ContractTests.of(RowsContract.cases(new Subject(), runtime));
    }

    private record Node(I18nString name, List<Node> children) {
        static Node leaf(String name) {
            return new Node(I18nString.literal(name), List.of());
        }

        static Node of(String name, Node... kids) {
            return new Node(I18nString.literal(name), List.of(kids));
        }
    }

    private static final class Subject implements RowsSubject {
        private static final float ROW_H = 12;

        private Tree<Node> tree;
        private List<Node> visible;
        private List<String> names;
        private int activated = -1;

        @Override
        public Widget build() {
            Node a = Node.of("A", Node.leaf("A1"), Node.leaf("A2"));
            List<Node> roots = List.of(a, Node.leaf("B"), Node.leaf("C"), Node.leaf("D"),
                    Node.leaf("E"));
            visible = List.of(a, a.children().get(0), a.children().get(1), roots.get(1),
                    roots.get(2), roots.get(3), roots.get(4));
            List<String> out = new ArrayList<>();
            for (Node node : visible) {
                out.add(node.name().english());
            }
            names = List.copyOf(out);
            tree = new Tree<>(new Outline(roots));
            tree.setAccessibleName("Outline");
            tree.expand(a);
            activated = -1;
            tree.onActivate(node -> activated = visible.indexOf(node));
            Column root = new Column();
            root.add(new SizedBox(300, 50, tree));
            return root;
        }

        @Override
        public Widget widget() {
            return tree;
        }

        @Override
        public List<String> rowNames() {
            return names;
        }

        @Override
        public boolean cursorIsTheSelection() {
            return false;
        }

        @Override
        public Scrolling scrolling() {
            return Scrolling.ITSELF;
        }

        @Override
        public boolean rowsActivate() {
            return true;
        }

        @Override
        public boolean enterMultipleSelection() {
            tree.setSelectionMode(SelectionMode.MULTI);
            return true;
        }

        @Override
        public void select(int row) {
            tree.setSelected(visible.get(row));
        }

        @Override
        public List<Integer> selectedRows() {
            List<Integer> rows = new ArrayList<>();
            for (Node node : tree.selectedNodes()) {
                rows.add(visible.indexOf(node));
            }
            rows.sort(null);
            return rows;
        }

        @Override
        public int cursorRow() {
            Node cursor = tree.cursorNode();
            return cursor == null ? -1 : visible.indexOf(cursor);
        }

        @Override
        public int lastActivated() {
            return activated;
        }

        /** A plain cell of a fixed height that cannot take the keyboard. */
        private static final class Cell extends Widget {
            @Override
            protected Size onMeasure(Constraints constraints) {
                return constraints.constrain(constraints.maxWidth(), ROW_H);
            }
        }

        private static final class Outline implements Tree.Model<Node> {
            private final List<Node> roots;

            Outline(List<Node> roots) {
                this.roots = roots;
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
                return new Cell();
            }

            @Override
            public I18nString nameOf(Node node) {
                return node.name();
            }
        }
    }
}

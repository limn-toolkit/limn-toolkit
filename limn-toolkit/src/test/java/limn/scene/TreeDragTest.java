package limn.scene;

import limn.components.ListView;
import limn.components.tree.Tree;
import limn.graphics.Canvas;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nodes of a {@link Tree} dragged (ADR 054 §4): onto a node they go into it, near its edges before
 * or after it, never into themselves; a closed node under a resting pointer opens; Alt+Shift and
 * an arrow do the same from the keyboard, and so does the scene's keyboard drag; and what comes
 * from another widget is handed to the application with the node and the place it was dropped at.
 */
class TreeDragTest {

    private static final float ROW = 20;

    /** A node of the application's own outline. */
    private static final class Node {
        final I18nString name;
        final List<Node> children;

        Node(String name, Node... children) {
            this(name, true, children);
        }

        Node(String name, boolean folder, Node... children) {
            this.name = I18nString.literal(name);
            this.children = folder ? new ArrayList<>(List.of(children)) : null;
        }

        static Node leaf(String name) {
            return new Node(name, false);
        }

        @Override
        public String toString() {
            return name.get();
        }
    }

    private static final class Cell extends Widget<Cell> {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), ROW);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }
    }

    /** Its children side by side, 150 wide each. */
    private static final class Pair extends Widget<Pair> {
        void put(Widget<?> child) {
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), 200);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }

        @Override
        protected void onLayout() {
            float x = 0;
            for (Widget<?> child : children()) {
                child.measure(Constraints.loose(150, height()));
                child.layoutBox(x, 0, 150, height());
                x += 150;
            }
        }
    }

    private final long[] now = {1_000_000_000L};
    private HeadlessUi ui;
    private Scene scene;
    private final List<Node> roots = new ArrayList<>();
    private Node docs;
    private Node pics;
    private Node a;
    private Node b;
    private Node readme;
    private Node notes;
    private Tree<Node> tree;
    private ListView<String> list;
    private final List<String> log = new ArrayList<>();
    private java.util.Locale localeBefore;

    @BeforeEach
    void build() {
        ui = new HeadlessUi(() -> now[0]);
        localeBefore = limn.i18n.I18n.locale();
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        a = Node.leaf("a");
        b = Node.leaf("b");
        docs = new Node("docs", a, b);
        pics = new Node("pics", Node.leaf("p"));
        readme = Node.leaf("readme");
        notes = Node.leaf("notes");
        roots.addAll(List.of(docs, pics, readme, notes));
        tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return roots;
            }

            @Override
            public List<Node> children(Node node) {
                return node.children == null ? List.of() : node.children;
            }

            @Override
            public boolean isLeaf(Node node) {
                return node.children == null;
            }

            @Override
            public Widget<?> cellFor(Node node) {
                return new Cell();
            }

            @Override
            public I18nString nameOf(Node node) {
                return node.name;
            }
        });
        tree.expand(docs);
        tree.setDragEnabled(true);
        tree.onReorder((nodes, parent, index) -> {
            log.add("move " + nodes + " into " + parent + " at " + index);
            for (Node node : nodes) {
                detach(roots, node);
            }
            (parent == null ? roots : parent.children).addAll(index, nodes);
        });
        tree.setDropFormats(ListView.ITEMS);
        tree.onDrop((event, parent, index) -> log.add("drop into " + parent + " at " + index + " " + event.action()));
        list = new ListView<String>(item -> new Cell()).setItems(List.of("x", "y")).setDragEnabled(true);
        Pair pair = new Pair();
        pair.put(tree);
        pair.put(list);
        scene = new Scene(pair);
        scene.layoutPass(300, 200);
    }

    private static boolean detach(List<Node> from, Node node) {
        if (from.remove(node)) {
            return true;
        }
        for (Node each : from) {
            if (each.children != null && detach(each.children, node)) {
                return true;
            }
        }
        return false;
    }

    @AfterEach
    void close() {
        limn.i18n.I18n.setLocale(localeBefore);
        ui.close();
    }

    private void press(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y, 1);
        drive(scene).inputBatchEnded();
    }

    private void move(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
    }

    private void release(float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y, 1);
        drive(scene).inputBatchEnded();
        scene.layoutPass(300, 200);
    }

    private void key(int key, int modifiers) {
        drive(scene).keyEvent(key, 0, true, false, modifiers);
        drive(scene).keyEvent(key, 0, false, false, modifiers);
        drive(scene).inputBatchEnded();
        scene.layoutPass(300, 200);
    }

    private String lastSaid() {
        List<String> said = scene.queuedAnnouncements();
        return said.isEmpty() ? "" : said.get(said.size() - 1);
    }

    @Test
    void aNodeDroppedOnABranchGoesIntoItWhichOpensAndSaysSo() {
        press(100, 90); // readme
        move(100, 80);
        move(100, 70); // the middle of pics
        release(100, 70);

        assertEquals(List.of("move [readme] into pics at 1"), log);
        assertEquals("[p, readme]", pics.children.toString());
        assertTrue(tree.isExpanded(pics), "what was moved is still in sight");
        assertEquals(List.of(readme), tree.selectedNodes());
        assertEquals("readme, 2 of 2, in pics", lastSaid());
    }

    @Test
    void nearAnEdgeOfANodeIsBeforeOrAfterItAtItsOwnLevel() {
        press(100, 30); // a, under docs
        move(100, 20);
        move(100, 2); // the upper edge of docs
        release(100, 2);
        assertEquals(List.of("move [a] into null at 0"), log);
        assertEquals("[a, docs, pics, readme, notes]", roots.toString());

        log.clear();
        press(100, 10); // a, now a root
        move(100, 40);
        move(100, 118); // the lower half of notes, the last row
        release(100, 118);
        assertEquals(List.of("move [a] into null at 4"), log, "counted without the node moved");
        assertEquals("[docs, pics, readme, notes, a]", roots.toString());
    }

    @Test
    void aBranchIsLiftedWithWhatIsUnderItSoItCannotBeDroppedIntoItself() {
        press(100, 10); // docs, with a and b under it
        move(100, 20);
        move(100, 35); // where its own child stood: readme now, the rows having closed up
        release(100, 35);
        assertEquals(List.of("move [docs] into null at 2"), log);
        assertEquals("[pics, readme, docs, notes]", roots.toString());
        assertEquals("[a, b]", docs.children.toString());
    }

    @Test
    void aClosedBranchUnderARestingPointerOpens() {
        press(100, 90); // readme
        move(100, 80);
        move(100, 70); // pics, closed
        assertFalse(tree.isExpanded(pics));
        now[0] += 400_000_000L;
        ui.runtime().drain();
        assertFalse(tree.isExpanded(pics), "not yet");
        now[0] += 400_000_000L;
        ui.runtime().drain();
        assertTrue(tree.isExpanded(pics));
        release(100, 70);
        assertEquals(List.of("move [readme] into pics at 1"), log);
    }

    @Test
    void altShiftAndAnArrowMoveTheNodeAmongItsSiblingsOutAndIn() {
        tree.setSelected(b);
        scene.requestFocus(tree);
        int chord = Keys.MOD_ALT | Keys.MOD_SHIFT;

        key(Keys.UP, chord);
        assertEquals("[b, a]", docs.children.toString());
        assertEquals("b, 1 of 2", lastSaid());
        log.clear();
        key(Keys.UP, chord);
        assertEquals(List.of(), log, "the first has nowhere higher to go");

        key(Keys.LEFT, chord);
        assertEquals("[docs, b, pics, readme, notes]", roots.toString(), "out one level, after what held it");
        assertEquals("b, 2 of 5", lastSaid());

        key(Keys.RIGHT, chord);
        assertEquals("[a, b]", docs.children.toString(), "into the sibling before it, last");
        assertEquals("b, 2 of 2, in docs", lastSaid());

        key(Keys.DOWN, chord);
        key(Keys.UP, chord);
        assertEquals("[b, a]", docs.children.toString());
    }

    @Test
    void theKeyboardDragWalksThePlacesAndSaysEach() {
        tree.setSelected(notes);
        scene.requestFocus(tree);
        int mods = Keys.MOD_SHIFT | (limn.backend.Platform.current().isMacOs() ? Keys.MOD_SUPER : Keys.MOD_CONTROL);
        key(Keys.D, mods);
        assertTrue(scene.isDragging());
        List<String> said = scene.queuedAnnouncements();
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).startsWith("Dragging notes."), said.toString());
        assertTrue(said.get(0).endsWith("Before notes"), said.toString());

        key(Keys.UP, 0);
        assertEquals("Before readme", lastSaid());
        key(Keys.UP, 0);
        assertEquals("Into pics", lastSaid());
        key(Keys.UP, 0);
        assertEquals("Before pics", lastSaid());
        key(Keys.UP, 0);
        assertEquals("After b", lastSaid(), "the end of what docs holds, which no row stands before");
        key(Keys.DOWN, 0);
        key(Keys.DOWN, 0);
        key(Keys.ENTER, 0);
        assertEquals(List.of("move [notes] into pics at 1"), log);
        assertEquals("[p, notes]", pics.children.toString());
    }

    @Test
    void whatComesFromAnotherWidgetIsHandedOverWithTheNodeAndThePlace() {
        press(200, 10); // x, in the list
        move(190, 10);
        move(100, 70); // the middle of pics
        release(100, 70);
        assertEquals(List.of("drop into pics at 1 COPY"), log);

        log.clear();
        tree.setDropFormats();
        press(200, 10);
        move(190, 10);
        move(100, 70);
        release(100, 70);
        assertEquals(List.of(), log, "a tree that names no format takes nothing");
    }
}

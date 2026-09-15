package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.lwjgl.a11y.ProbeWindow;
import limn.components.Label;
import limn.components.ListView;
import limn.components.tree.Tree;
import limn.i18n.I18nString;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real {@link Tree} and a real {@link ListView} read the way AppKit reads them, through a scene
 * over the platform's bridge with the platform left out: the outline and the list answer rows, each
 * row its place, and where the user is follows the cursor row (M1, M2, M3 over real widgets).
 *
 * <p>The outline is the one the native NSOutlineView was read over on the macOS 26.6.2 guest
 * (2026-09-15, {@code scripts/a11y/macos/outline-probe.swift}): Documents open with Reports open
 * under it, Pictures closed, Readme a leaf — so each answer here can be read against what the
 * native outline answered for the same row.
 */
class AxOutlineSceneTest {

    record Node(String name, List<Node> children) {
        static Node of(String name, Node... children) {
            return new Node(name, List.of(children));
        }
    }

    private HeadlessUi ui;
    private final AtomicLong nanos = new AtomicLong();
    private Scene scene;
    private AxBridge bridge;
    private final List<String> trace = new ArrayList<>();

    private final Node q1 = Node.of("Q1");
    private final Node reports = Node.of("Reports", q1);
    private final Node notes = Node.of("Notes");
    private final Node documents = Node.of("Documents", reports, notes);
    private final Node trip = Node.of("Trip");
    private final Node pictures = Node.of("Pictures", trip);
    private final Node readme = Node.of("Readme");

    @BeforeEach
    void clock() {
        ui = new HeadlessUi(nanos::get);
    }

    @AfterEach
    void unbind() {
        if (bridge != null) bridge.detach();
        ui.close();
    }

    private Tree<Node> bindTree() {
        Tree<Node> tree = new Tree<>(new Tree.Model<Node>() {
            @Override public List<Node> roots() {
                return List.of(documents, pictures, readme);
            }

            @Override public List<Node> children(Node node) {
                return node.children();
            }

            @Override public Widget cellFor(Node node) {
                // A fixed height: a label measures nothing without a text ruler, and a row of no
                // height is a row the tree never realizes.
                return new SizedBox(200, 24, new Label(node.name()));
            }

            @Override public I18nString nameOf(Node node) {
                return I18nString.literal(node.name());
            }
        });
        tree.setSelectionMode(Tree.SelectionMode.SINGLE);
        tree.expand(documents);
        tree.expand(reports);
        bind(tree);
        return tree;
    }

    private void bind(Widget widget) {
        Column root = new Column();
        root.add(new SizedBox(300, 400, widget));
        scene = new Scene(root, nanos::get);
        ProbeWindow window = new ProbeWindow();
        bridge = AxBridge.withoutThePlatform();
        bridge.trace(trace::add);
        window.accessibility = bridge;
        scene.bind(window);
        frame();
        bridge.entered();
        frame();
    }

    /** A reader's verb on an element: accepted or not, then run on the user-interface thread, then a frame. */
    private boolean perform(long element, Accessible.Action action) {
        boolean accepted = bridge.host().perform(bridge.nodeFor(element).id(), action,
                Accessible.Argument.NONE);
        ui.runtime().drain();
        frame();
        return accepted;
    }

    private void frame() {
        scene.renderFrame(new NoopCanvas(400, 500));
    }

    private AccessibleNode only(Accessible.Role role) {
        AccessibleTree tree = bridge.tree();
        AccessibleNode found = null;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                assertTrue(found == null, "more than one " + role);
                found = tree.node(i);
            }
        }
        assertTrue(found != null, "no " + role + " published");
        return found;
    }

    /** The names of the rows an element array names, in order. */
    private List<String> names(long[] elements) {
        List<String> names = new ArrayList<>();
        for (long element : elements) names.add(bridge.nodeFor(element).name());
        return names;
    }

    @Test
    void theOutlinesRowsAreItsVisibleRowsInOrderEachAtItsZeroBasedPlace() {
        bindTree();
        AxGrid grid = new AxGrid(bridge);
        AccessibleNode outline = only(Accessible.Role.TREE);
        long[] rows = grid.rows(outline);
        assertEquals(List.of("Documents", "Reports", "Q1", "Notes", "Pictures", "Readme"), names(rows),
                "the rows the native outline answered for the same data, in the same order");
        for (int i = 0; i < rows.length; i++) {
            assertEquals(i, grid.index(bridge.nodeFor(rows[i])),
                    names(rows).get(i) + ": AXIndex as the native outline answered it, 0 to 5");
        }
        assertTrue(AxGate.allows(grid, outline, "accessibilityRows"));
        assertTrue(!AxGate.allows(grid, outline, "accessibilityRowCount"),
                "the native outline answers no AXRowCount");
    }

    @Test
    void theFocusedElementIsTheCursorRowAndACursorMoveIsToldAsAFocusChange() {
        Tree<Node> tree = bindTree();
        scene.requestFocus(tree);
        frame();
        AxGrid grid = new AxGrid(bridge);
        long[] rows = grid.rows(only(Accessible.Role.TREE));
        assertEquals(only(Accessible.Role.TREE).id(), bridge.nodeFor(bridge.focusedElement()).id(),
                "a focused tree with no cursor row yet is itself where the user is");
        assertTrue(perform(rows[0], Accessible.Action.SELECT));
        long cursor = bridge.focusedElement();
        assertEquals("Documents", bridge.nodeFor(cursor).name(),
                "a focused tree's cursor row is where the user is: " + names(rows));
        assertTrue(bridge.isFocused(bridge.nodeFor(cursor)));
        assertTrue(!bridge.isFocused(only(Accessible.Role.TREE)), "the tree only holds the keyboard");

        trace.clear();
        long readmeRow = rows[5];
        assertTrue(perform(readmeRow, Accessible.Action.SELECT), "the row publishes SELECT");
        assertEquals("Readme", bridge.nodeFor(bridge.focusedElement()).name(), "the cursor moved with it");
        List<String> posted = trace.stream().filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
        assertEquals(1, posted.stream()
                        .filter("NSAccessibilityFocusedUIElementChangedNotification"::equals).count(),
                "one focus change for one cursor move, in the frame that moved it: " + trace);
        assertEquals("NSAccessibilityFocusedUIElementChangedNotification", posted.get(posted.size() - 1),
                "told last: " + trace);
        assertEquals(List.of("NSAccessibilitySelectedRowsChangedNotification"), posted.stream()
                        .filter(line -> line.startsWith("NSAccessibilitySelected")).toList(),
                "and the selection it moved, once, on the outline, as a native outline posts it: " + trace);
    }

    @Test
    void aListsRowsAreItsRealizedRowsAndEachIndexIsItsDataPosition() {
        ListView list = new ListView(new ListView.Adapter() {
            @Override public int rowCount() {
                return 5;
            }

            @Override public Widget rowAt(int index) {
                return new SizedBox(200, 24, new Label("Row " + index));
            }
        });
        list.setSelectedIndex(2);
        bind(list);
        AxGrid grid = new AxGrid(bridge);
        AccessibleNode node = only(Accessible.Role.LIST);
        long[] rows = grid.rows(node);
        assertEquals(5, rows.length, "five rows, all realized in a 400-high box");
        for (int i = 0; i < rows.length; i++) {
            AccessibleNode row = bridge.nodeFor(rows[i]);
            assertEquals(row.selectionItem().positionInSet() - 1, grid.index(row));
        }
        long[] selected = grid.selectedRows(node);
        assertEquals(1, selected.length);
        assertEquals(2, grid.index(bridge.nodeFor(selected[0])));

        trace.clear();
        assertTrue(perform(rows[4], Accessible.Action.SELECT));
        assertEquals(4, grid.index(bridge.nodeFor(grid.selectedRows(only(Accessible.Role.LIST))[0])));
        assertTrue(trace.stream().anyMatch(line -> line.startsWith("emitted SELECTION_CHANGED#" + node.id())),
                "the list's own selection change: " + trace);
        assertEquals(List.of("NSAccessibilitySelectedRowsChangedNotification"), trace.stream()
                        .filter(line -> line.startsWith("posted NSAccessibilitySelected"))
                        .map(line -> line.substring("posted ".length())).toList(),
                "posted on the list as its rows' change: " + trace);
    }
}

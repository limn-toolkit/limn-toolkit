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
import org.junit.jupiter.api.extension.ExtendWith;

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
@ExtendWith(PlatformFreeBridges.class)
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
        bridge = PlatformFreeBridges.make();
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
    void aRealOutlinesRowsDiscloseAsTheNativeOutlinesDidAndOpeningOneIsToldOnTheRow() {
        Tree<Node> tree = bindTree();
        AxGrid grid = new AxGrid(bridge);
        long[] rows = grid.rows(only(Accessible.Role.TREE));
        long[] levels = new long[rows.length];
        boolean[] open = new boolean[rows.length];
        for (int i = 0; i < rows.length; i++) {
            levels[i] = grid.disclosureLevel(bridge.nodeFor(rows[i]));
            open[i] = grid.disclosed(bridge.nodeFor(rows[i]));
        }
        assertEquals(List.of(0L, 1L, 2L, 1L, 0L, 0L), java.util.Arrays.stream(levels).boxed().toList(),
                "AXDisclosureLevel as the native outline answered it for the same rows");
        assertEquals(List.of(true, true, false, false, false, false),
                List.of(open[0], open[1], open[2], open[3], open[4], open[5]), "AXDisclosing likewise");
        assertEquals(List.of("Reports", "Notes"), names(grid.disclosedRows(bridge.nodeFor(rows[0]))));
        assertEquals("Reports", bridge.nodeFor(grid.disclosedByRow(bridge.nodeFor(rows[2]))).name());

        trace.clear();
        tree.expand(pictures);
        frame();
        List<String> posted = trace.stream().filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
        assertTrue(posted.contains("NSAccessibilityRowExpandedNotification"), String.valueOf(trace));
        assertTrue(posted.contains("NSAccessibilityRowCountChangedNotification"), String.valueOf(trace));
        assertTrue(!posted.contains("NSAccessibilityValueChangedNotification"),
                "an outline row's opening is not a value change: " + trace);
        long[] after = grid.rows(only(Accessible.Role.TREE));
        assertEquals(List.of("Documents", "Reports", "Q1", "Notes", "Pictures", "Trip", "Readme"), names(after));
        assertEquals(List.of("Trip"), names(grid.disclosedRows(bridge.nodeFor(after[4]))));
        assertEquals(1, grid.disclosureLevel(bridge.nodeFor(after[5])),
                "Trip at level 1, as the native outline answered once Pictures opened");
        assertEquals(6, grid.index(bridge.nodeFor(after[6])), "and Readme moved down to 6");
    }

    private List<String> postedSinceCleared() {
        return trace.stream().filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
    }

    @Test
    void aLoadLandingUnderARowAlreadyOpenIsARowCountChangeOnTheOutline() {
        Node remote = Node.of("Remote");
        List<Node> fetched = List.of(Node.of("One"), Node.of("Two"));
        Tree<Node> tree = new Tree<>(new Tree.Model<Node>() {
            @Override public List<Node> roots() {
                return List.of(remote, readme);
            }

            @Override public List<Node> children(Node node) {
                return node == remote ? null : node.children();   // not known yet: load fetches them
            }

            @Override public limn.concurrent.Work<List<Node>> load(Node node) {
                return limn.concurrent.Ui.work(progress -> fetched);
            }

            @Override public Widget cellFor(Node node) {
                return new SizedBox(200, 24, new Label(node.name()));
            }

            @Override public I18nString nameOf(Node node) {
                return I18nString.literal(node.name());
            }
        });
        bind(tree);
        tree.expand(remote);
        frame();
        AxGrid grid = new AxGrid(bridge);
        assertEquals(List.of("Remote", "Readme"), names(grid.rows(only(Accessible.Role.TREE))),
                "the fixture: the row is open and its children are on their way");

        trace.clear();
        ui.pumpUntil(() -> tree.visibleRowCount() == 4);
        frame();
        assertEquals(List.of("Remote", "One", "Two", "Readme"), names(grid.rows(only(Accessible.Role.TREE))));
        List<String> posted = postedSinceCleared();
        assertEquals(1, posted.stream().filter("NSAccessibilityRowCountChangedNotification"::equals).count(),
                "the outline's rows went from two to four with no row opening or closing, and a native "
                        + "outline tells its rows' change as a row-count change on itself (M1 correction 2): "
                        + trace);
        assertTrue(!posted.contains("NSAccessibilityRowExpandedNotification"), "no row opened: " + trace);
    }

    @Test
    void aListWhoseRowsGrowIsARowCountChangeAndAScrollIsNone() {
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(50);
        ListView list = new ListView(new ListView.Adapter() {
            @Override public int rowCount() {
                return count.get();
            }

            @Override public Widget rowAt(int index) {
                return new SizedBox(200, 24, new Label("Row " + index));
            }
        });
        bind(list);
        AxGrid grid = new AxGrid(bridge);
        long[] rows = grid.rows(only(Accessible.Role.LIST));

        trace.clear();
        assertTrue(perform(rows[rows.length - 1], Accessible.Action.SCROLL_INTO_VIEW));
        assertTrue(only(Accessible.Role.LIST).scroll().verticalPercent() > 0, "the fixture: it scrolled");
        assertTrue(!postedSinceCleared().contains("NSAccessibilityRowCountChangedNotification"),
                "a scroll changes which rows are realized, not how many the list has: " + trace);

        trace.clear();
        count.set(60);
        list.refresh();
        frame();
        assertEquals(1, postedSinceCleared().stream()
                        .filter("NSAccessibilityRowCountChangedNotification"::equals).count(),
                "fifty rows became sixty, told once on the list: " + trace);
    }

    /** A reader's write through the setter half, as the closure makes it: mapped, then performed. */
    private boolean write(AxSetters.Setting setting, long element) {
        if (setting == null) return false;
        boolean accepted = bridge.perform(bridge.nodeFor(element).id(), setting.action(), setting.argument());
        ui.runtime().drain();
        frame();
        return accepted;
    }

    @Test
    void aReaderOpensARowByWritingAXDisclosingAndMovesTheCursorByWritingAXFocused() {
        Tree<Node> tree = bindTree();
        scene.requestFocus(tree);
        frame();
        AxGrid grid = new AxGrid(bridge);
        long[] rows = grid.rows(only(Accessible.Role.TREE));
        long picturesRow = rows[4];
        long readmeRow = rows[5];
        assertTrue(AxSetters.offers(grid, bridge.nodeFor(picturesRow), AxSetters.DISCLOSED),
                "a closed branch's AXDisclosing is settable, as a native outline's is");
        assertTrue(!AxSetters.offers(grid, bridge.nodeFor(readmeRow), AxSetters.DISCLOSED),
                "and a leaf's is not, as the native outline answered for Q1, Notes and Readme");
        assertTrue(write(AxSetters.forBool(grid, bridge.nodeFor(picturesRow), AxSetters.DISCLOSED, true),
                picturesRow));
        assertTrue(tree.isExpanded(pictures), "the write opened Pictures");
        assertEquals(7, grid.rows(only(Accessible.Role.TREE)).length);

        long notesRow = grid.rows(only(Accessible.Role.TREE))[3];
        assertTrue(AxSetters.offers(grid, bridge.nodeFor(notesRow), AxSetters.FOCUSED));
        assertTrue(write(AxSetters.forBool(grid, bridge.nodeFor(notesRow), AxSetters.FOCUSED, true), notesRow));
        assertEquals("Notes", bridge.nodeFor(bridge.focusedElement()).name(),
                "VoiceOver's cursor sync writes AXFocused, and the tree's cursor follows it");
        assertTrue(tree.selectedNodes().isEmpty(), "without selecting (decision 11)");
    }

    @Test
    void aReaderSelectsRowsOfARealMultiSelectTreeByWritingAXSelectedRows() {
        Tree<Node> tree = bindTree();
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        frame();
        AxGrid grid = new AxGrid(bridge);
        AccessibleNode outline = only(Accessible.Role.TREE);
        long[] rows = grid.rows(outline);
        assertTrue(AxSetters.offers(grid, outline, AxSetters.SELECTED_ROWS));
        List<AccessibleNode> written = List.of(bridge.nodeFor(rows[3]), bridge.nodeFor(rows[5]));
        for (AxSetters.RowSetting setting : AxSetters.forSelectedRows(grid, outline, written)) {
            assertTrue(bridge.perform(setting.nodeId(), setting.action()), String.valueOf(setting));
        }
        ui.runtime().drain();
        frame();
        assertEquals(List.of(notes, readme), tree.selectedNodes(),
                "the tree's selection is exactly the two rows written, as the native outline's became");

        trace.clear();
        rows = grid.rows(only(Accessible.Role.TREE));
        for (AxSetters.RowSetting setting : AxSetters.forSelectedRows(grid, only(Accessible.Role.TREE),
                List.of(bridge.nodeFor(rows[0])))) {
            assertTrue(bridge.perform(setting.nodeId(), setting.action()), String.valueOf(setting));
        }
        ui.runtime().drain();
        frame();
        assertEquals(List.of(documents), tree.selectedNodes(), "and one row written replaces them");
    }

    @Test
    void aReadersScrollToVisibleOnAPartlyShownRowScrollsTheListToIt() {
        ListView list = new ListView(new ListView.Adapter() {
            @Override public int rowCount() {
                return 50;
            }

            @Override public Widget rowAt(int index) {
                return new SizedBox(200, 24, new Label("Row " + index));
            }
        });
        bind(list);
        AxGrid grid = new AxGrid(bridge);
        long[] rows = grid.rows(only(Accessible.Role.LIST));
        long last = rows[rows.length - 1];
        assertTrue(AxActions.actionSymbolsFor(bridge.nodeFor(last)).contains(AxActions.SCROLL_TO_VISIBLE_SYMBOL),
                "a list row lists AXScrollToVisible");
        assertEquals(0, only(Accessible.Role.LIST).scroll().verticalPercent(), 1e-9);
        Accessible.Action verb = AxActions.verbForActionSymbol(bridge.nodeFor(last),
                AxActions.SCROLL_TO_VISIBLE_SYMBOL);
        assertEquals(Accessible.Action.SCROLL_INTO_VIEW, verb);
        assertTrue(perform(last, verb));
        assertTrue(only(Accessible.Role.LIST).scroll().verticalPercent() > 0,
                "the row the reader asked for was scrolled into view: " + only(Accessible.Role.LIST).scroll());
    }

    @Test
    void aReaderWritesAFieldsTextAndASlidersValueThroughAXValue() {
        limn.components.TextField field = new limn.components.TextField();
        limn.components.Slider slider = new limn.components.Slider(0, 100);
        Column column = new Column();
        column.add(new SizedBox(200, 30, field));
        column.add(new SizedBox(200, 30, slider));
        bind(column);
        AccessibleNode fieldNode = only(Accessible.Role.TEXT_FIELD);
        AccessibleNode sliderNode = only(Accessible.Role.SLIDER);
        long fieldElement = bridge.elementFor(fieldNode.id());
        long sliderElement = bridge.elementFor(sliderNode.id());
        assertTrue(write(AxSetters.forValue(fieldNode, "hello", null), fieldElement));
        assertEquals("hello", field.text());
        assertTrue(write(AxSetters.forValue(sliderNode, null, 40.0), sliderElement));
        assertEquals(40f, slider.value(), 0.001f);
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
        assertEquals(List.of("NSAccessibilitySelectedRowsChangedNotification",
                        "NSAccessibilityFocusedUIElementChangedNotification"), posted,
                "one cursor move is the selection it moved, once, on the outline, as a native outline "
                        + "posts it, and one focus change told last; no value change on the row the "
                        + "selection or the cursor left or reached (M3 correction f): " + trace);
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

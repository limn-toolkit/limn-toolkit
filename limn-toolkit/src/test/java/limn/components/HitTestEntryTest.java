package limn.components;

import limn.components.table.Column;
import limn.components.table.Table;
import limn.components.tree.Tree;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.SceneAccess;
import limn.scene.layout.Padding;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hit test's one entry: {@code Widget.hitTest} is final and makes the visible, enabled, in
 * reach and inside checks for every widget, and a widget answers only in {@code onHitTest}, past
 * them, reaching its children through {@code hitTestChild}.
 *
 * <p>Run against each of the eight widgets that overrode {@code hitTest} before the entry was
 * final, three of which copied the checks word for word: hidden, disabled, inert and every side
 * outside give nothing. Two of the cases fail against the copies, which is why the entry is one:
 * a point of {@code NaN}, which the old {@code localX < 0 || ...} let through to a list, a tree
 * or a table that then answered itself, and an inert widget, which the copies did not know about
 * and which only the scene filtered, after the walk.
 */
class HitTestEntryTest extends ComponentTestBase {

    private static final float INSET = 20;
    private static final float WIDTH = 300;
    private static final float HEIGHT = 200;

    /** A widget with a size and nothing else. */
    private static final class Plain extends Widget<Plain> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), c.maxHeight());
        }
    }

    private record Node(String name, List<Node> kids) {
    }

    private static Tree<Node> tree() {
        List<Node> roots = List.of(new Node("one", List.of(new Node("one.a", List.of()))),
                new Node("two", List.of()));
        return new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return roots;
            }

            @Override
            public List<Node> children(Node node) {
                return node.kids();
            }

            @Override
            public Widget<?> cellFor(Node node) {
                return new Label(node.name());
            }
        });
    }

    private static Table<String> table() {
        Table<String> table = new Table<>(List.of(Column.<String>text("Name", s -> s).width(100)));
        table.setRows(List.of("a", "b", "c"));
        return table;
    }

    private static ListView<String> list() {
        ListView<String> list = new ListView<>(Label::new);
        list.setItems(List.of("a", "b", "c"));
        return list;
    }

    private static ScrollBar bar() {
        return new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
            private float offset;

            @Override
            public float contentLength() {
                return 400;
            }

            @Override
            public float viewportLength() {
                return 100;
            }

            @Override
            public float offset() {
                return offset;
            }

            @Override
            public void setOffset(float value) {
                offset = value;
            }
        }).setPolicy(ScrollBar.Policy.ALWAYS);
    }

    private static ComboBox<String> multiCombo() {
        ComboBox<String> combo = new ComboBox<>(List.of("one", "two", "three"))
                .setSelectionMode(SelectionMode.MULTI);
        combo.setSelectedIndices(0, 1);
        return combo;
    }

    /**
     * The eight, each as the widget a point is asked of and whether it answers inside its box:
     * the combo box's chip row and the range slider's thumbs are transparent to the pointer by
     * their own decision, and answer nothing anywhere.
     */
    private record Case(Supplier<Widget<?>> mount, java.util.function.Function<Widget<?>, Widget<?>> asked,
                        boolean answersInside) {
    }

    private static Map<String, Case> formerOverrides() {
        Map<String, Case> cases = new LinkedHashMap<>();
        cases.put("ListView", new Case(HitTestEntryTest::list, w -> w, true));
        cases.put("Tree", new Case(HitTestEntryTest::tree, w -> w, true));
        cases.put("Table", new Case(HitTestEntryTest::table, w -> w, true));
        cases.put("SplitPane", new Case(() -> SplitPane.horizontal(new Plain(), new Plain()), w -> w, true));
        cases.put("MenuBar", new Case(() -> new MenuBar().addMenu("File", new Menu().addItem("Quit", () -> { })),
                w -> w, true));
        cases.put("ScrollBar", new Case(HitTestEntryTest::bar, w -> w, true));
        cases.put("ComboBox's chip row", new Case(HitTestEntryTest::multiCombo,
                w -> w.children().get(w.children().size() - 1), false));
        cases.put("RangeSlider's thumb", new Case(() -> new RangeSlider(0, 100), w -> w.children().get(0), false));
        return cases;
    }

    private Scene mount(Widget<?> widget) {
        Scene scene = new Scene(new Padding(Insets.all(INSET), widget));
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH + 2 * INSET, HEIGHT + 2 * INSET);
        scene.renderFrame(new FakeCanvas(WIDTH + 2 * INSET, HEIGHT + 2 * INSET));
        return scene;
    }

    @Test
    void everyFormerOverrideGivesNothingHiddenDisabledInertOrOutside() {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Case> entry : formerOverrides().entrySet()) {
            String name = entry.getKey();
            Case c = entry.getValue();
            Widget<?> mounted = c.mount().get();
            mount(mounted);
            Widget<?> w = c.asked().apply(mounted);
            float midX = w.width() / 2;
            // A menu bar answers only on its titles, which start at its leading edge.
            float atX = mounted instanceof MenuBar ? 8 : midX;
            float midY = w.height() / 2;
            if (w.width() <= 0 || w.height() <= 0) {
                wrong.add(name + ": laid out empty, " + w.width() + "x" + w.height());
                continue;
            }
            Widget<?> inside = w.hitTest(atX, midY);
            if (c.answersInside() ? inside == null : inside != null) {
                wrong.add(name + ": inside answered " + inside);
            }
            check(wrong, name + " outside left", w.hitTest(-0.5f, midY));
            check(wrong, name + " outside top", w.hitTest(midX, -0.5f));
            check(wrong, name + " on its right edge", w.hitTest(w.width(), midY));
            check(wrong, name + " on its bottom edge", w.hitTest(midX, w.height()));
            check(wrong, name + " at NaN", w.hitTest(Float.NaN, Float.NaN));
            check(wrong, name + " at NaN across", w.hitTest(Float.NaN, midY));

            w.setVisible(false);
            check(wrong, name + " hidden", w.hitTest(atX, midY));
            w.setVisible(true);

            w.setEnabled(false);
            check(wrong, name + " disabled", w.hitTest(atX, midY));
            w.setEnabled(true);

            SceneAccess.setInert(w, true);
            check(wrong, name + " inert", w.hitTest(atX, midY));
            SceneAccess.setInert(w, false);
        }
        assertEquals(List.of(), wrong);
    }

    private static void check(List<String> wrong, String what, @Nullable Widget<?> hit) {
        if (hit != null) {
            wrong.add(what + " answered " + hit.getClass().getSimpleName());
        }
    }

    /** A container an application writes: it decides only where inside it a point lands. */
    private static final class AlwaysItself extends Widget<AlwaysItself> {
        private final Plain inner = new Plain();
        private final Plain stranger = new Plain();

        AlwaysItself() {
            add(inner);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            inner.measure(Constraints.tight(40, 40));
            return c.constrain(100, 100);
        }

        @Override
        protected void onLayout() {
            inner.layoutBox(30, 30, 40, 40);
        }

        @Override
        protected @Nullable Widget<?> onHitTest(float localX, float localY) {
            return this;
        }

        @Nullable Widget<?> askInner(float x, float y) {
            return hitTestChild(inner, x, y);
        }

        @Nullable Widget<?> askStranger(float x, float y) {
            return hitTestChild(stranger, x, y);
        }
    }

    @Test
    void aWidgetsOwnAnswerIsAskedOnlyPastThePreamble() {
        AlwaysItself w = new AlwaysItself();
        mount(w);
        assertSame(w, w.hitTest(50, 50));
        assertNull(w.hitTest(w.width() + 10, 50), "outside, whatever onHitTest would say");
        assertNull(w.hitTest(Float.NaN, 50));
        w.setEnabled(false);
        assertNull(w.hitTest(50, 50), "disabled, whatever onHitTest would say");
    }

    @Test
    void hitTestChildMapsIntoTheChildAndRefusesAStranger() {
        AlwaysItself w = new AlwaysItself();
        mount(w);
        assertSame(w.inner, w.askInner(35, 35), "30 in, so 5,5 of the child");
        assertNull(w.askInner(25, 35), "left of the child");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> w.askStranger(35, 35));
        assertNotNull(thrown.getMessage());
    }

    /** A box of a fixed size, for content larger than the pane it scrolls in. */
    private static final class Box extends Widget<Box> {
        private final float w;
        private final float h;

        Box(float w, float h) {
            this.w = w;
            this.h = h;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(w, h);
        }
    }

    /**
     * A scroll pane whose bars hold gutters of their own clips its content to the viewport, and
     * the content running on under a gutter, where it is not drawn, takes no press there: the bar
     * has it while it shows, and the pane while it has faded.
     */
    @Test
    void aChildIsNotHitWhereItsParentClipsItAway() {
        Box content = new Box(1000, 1000);
        ScrollView view = new ScrollView(content, true, true).setBarLayout(ScrollGutters.Layout.RESERVED);
        Scene scene = new Scene(view);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 200);
        float bar = ScrollBar.thickness();
        assertEquals(200 - bar, view.viewportWidth(), 1e-3f, "both gutters held open");
        assertEquals(1000, content.width(), 1e-3f, "the content runs on under the vertical gutter");
        assertSame(content, view.hitTest(50, 50), "inside the viewport, the content");
        Widget<?> inGutter = view.hitTest(200 - bar / 2, 50);
        assertTrue(inGutter != content, "in the vertical gutter, never the content: " + inGutter);
        Widget<?> inOther = view.hitTest(50, 200 - bar / 2);
        assertTrue(inOther != content, "nor in the horizontal one: " + inOther);
    }

    /**
     * A table cuts a widget cell scrolled up under its header at the rows' viewport, and the part
     * cut away, drawn over by the header, takes no press: the table's own hand check of the rows'
     * band went when the entry learned the clip.
     */
    @Test
    void aTableCellScrolledUnderTheHeaderIsNotHitThere() {
        Table<String> table = new Table<>(List.of(
                Column.<String>widget("Mark", s -> new Box(10, 10)).width(120)));
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add("row " + i);
        }
        table.setRows(rows);
        Scene scene = mount(table);
        // The first row's mark sits in the middle of its row, 11 points below the header's edge:
        // 15 points up puts it across the edge.
        table.scrollBy(0, 15);
        scene.layoutPass(WIDTH + 2 * INSET, HEIGHT + 2 * INSET);
        scene.renderFrame(new FakeCanvas(WIDTH + 2 * INSET, HEIGHT + 2 * INSET));
        float header = Theme.current().tokensFor(table).controlHeight();
        Widget<?> under = null;
        for (Widget<?> child : table.children()) {
            if (child instanceof Box && child.y() < header - 2 && child.y() + child.height() > header + 2) {
                under = child;
            }
        }
        assertNotNull(under, "the fixture scrolls a cell part way under the header");
        Widget<?> hit = table.hitTest(under.x() + 2, header - 2);
        assertTrue(!(hit instanceof Box), "on the header, over the cell's hidden part: " + hit);
        assertSame(under, table.hitTest(under.x() + 2, header + 2), "and the cell just below it");
    }

    /** A widget that counts the presses that land on it. */
    private static final class Pressed extends Widget<Pressed> {
        int presses;

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), c.maxHeight());
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            if (event.type() == limn.scene.event.MouseEvent.Type.PRESS) {
                presses++;
                event.consume();
            }
        }
    }

    @Test
    void anInertSubtreeLetsThePointerThroughToWhatIsBeneath() {
        Pressed beneath = new Pressed();
        Pressed above = new Pressed();
        limn.scene.layout.Stack stack = new limn.scene.layout.Stack();
        stack.add(beneath);
        stack.add(above);
        Scene scene = mount(stack);
        float x = INSET + WIDTH / 2;
        float y = INSET + HEIGHT / 2;
        limn.testing.SceneDriver.drive(scene).click(x, y);
        assertEquals(1, above.presses);
        SceneAccess.setInert(above, true);
        limn.testing.SceneDriver.drive(scene).click(x, y);
        assertEquals(1, above.presses, "passed by");
        assertEquals(1, beneath.presses, "onto the widget beneath, as a hidden one would leave it");
    }
}

package limn.components;

import limn.components.table.Column;
import limn.components.table.Table;
import limn.components.tree.Tree;
import limn.input.Keys;
import limn.math.Mat4;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Turned;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import limn.scene.layout.Transform;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every component that turns a pointer's position into a place of its own, driven at turned
 * points, gives what it gives upright: a slider's drag, a text field's caret, a table's column
 * resize, a tree's triangle, the menu bar's titles, a list's rows, a split pane's divider and a
 * range slider's thumbs.
 *
 * <p>Each runs six times on the same scene: upright, rotated in the plane, and turned about its
 * vertical axis in perspective, through {@link Turned}, which hands the scene the matrix and draws
 * nothing turned; and inside the real {@link Transform}, rotated, skewed, and turned in depth in
 * perspective, which draws the component through a picture as an application's would be. The pointer is put where the matrix
 * draws the local point the upright run presses, computed here from the matrix and the layout's
 * own offsets rather than by the conversions under test; for the container, from the matrix its
 * values must make about its middle, written out here. A component that still sums its offsets up
 * the tree, or converts one axis at a time, presses somewhere else at an angle and fails.
 */
class TurnedPointerTest extends ComponentTestBase {

    /** The stub window's size: bound to one, so that a dropdown is drawn in the scene. */
    private static final float W = 400;
    private static final float H = 300;
    private static final float PAD = 40;

    /** The pointer, given a widget's local points, and a key. */
    private interface Pointer {
        void press(Widget<?> on, float localX, float localY);

        void move(Widget<?> on, float localX, float localY);

        void release(Widget<?> on, float localX, float localY);

        void key(int key);

        default void click(Widget<?> on, float localX, float localY) {
            press(on, localX, localY);
            release(on, localX, localY);
        }
    }

    /**
     * One way a case runs: what the padded component is mounted in, and the matrix the pointer is
     * placed through, null upright.
     */
    private record Mount(String name, Function<Widget<?>, Widget<?>> wrap, @Nullable Mat4 matrix) {
    }

    /** A skew along x by 20 degrees, as a general matrix. */
    private static final Mat4 SKEW = Mat4.affine2D(1, 0, (float) Math.tan(Math.toRadians(20)), 1, 0, 0);

    /**
     * The six ways each case runs: upright, rotated in the plane and turned in perspective by the
     * test container; rotated, skewed and turned in perspective by the real one, about its middle,
     * its origin's default.
     */
    private static List<Mount> angles() {
        List<Mount> angles = new ArrayList<>();
        angles.add(new Mount("upright", padded -> new Turned(padded), null));
        Mat4 rotation = Turned.rotation(W / 2, H / 2, 33);
        angles.add(new Mount("rotated in the plane", padded -> new Turned(padded).setMatrix(rotation), rotation));
        Mat4 turn = Turned.turn(W / 2, H / 2, 38, 1200);
        angles.add(new Mount("turned in perspective", padded -> new Turned(padded).setMatrix(turn), turn));
        angles.add(new Mount("rotated by a Transform", padded -> new Transform(padded).setRotation(33),
                rotation));
        Mat4 skew = Turned.translation(W / 2, H / 2).multiply(SKEW).multiply(Turned.translation(-W / 2, -H / 2));
        angles.add(new Mount("skewed by a Transform", padded -> new Transform(padded).setMatrix(SKEW), skew));
        angles.add(new Mount("turned in perspective by a Transform", padded -> new Transform(padded)
                .setPerspective(1200).setRotation3D(limn.math.Vec3.UNIT_Y, 38), turn));
        return angles;
    }

    /**
     * Mounts {@code component} padded inside the mount's container at the scene's origin, and runs
     * {@code drive} with a pointer that presses where the mount's matrix draws a point of a widget's
     * layout; answers what {@code read} reads before and after.
     */
    private <C extends Widget<?>, R> List<R> run(Supplier<C> build, Mount mount,
                                                 BiConsumer<C, Pointer> drive, Function<C, R> read) {
        C component = build.get();
        Widget<?> turned = mount.wrap().apply(new Padding(Insets.all(PAD), component));
        Mat4 matrix = mount.matrix();
        Scene scene = new Scene(turned);
        scene.bind(new limn.testing.StubWindow());
        scene.setTextRuler(RULER);
        scene.layoutPass(W, H);
        scene.renderFrame(new FakeCanvas(W, H));
        Pointer pointer = new Pointer() {
            private float[] at(Widget<?> on, float localX, float localY) {
                // The layout's own offsets, summed by hand, then the matrix: where the point is
                // drawn, which is what the user points at.
                float x = localX;
                float y = localY;
                for (Widget<?> w = on; w != turned; w = w.parent()) {
                    x += w.x();
                    y += w.y();
                }
                return matrix == null ? new float[] {x, y} : Turned.drawnAt(matrix, x, y);
            }

            @Override
            public void press(Widget<?> on, float localX, float localY) {
                float[] p = at(on, localX, localY);
                drive(scene).mouseMoved(p[0], p[1]);
                drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, p[0], p[1], 1);
                drive(scene).inputBatchEnded();
                scene.layoutPass(W, H); // lays out an overlay the press may have put up
                scene.renderFrame(new FakeCanvas(W, H));
            }

            @Override
            public void move(Widget<?> on, float localX, float localY) {
                float[] p = at(on, localX, localY);
                drive(scene).mouseMoved(p[0], p[1]);
                drive(scene).inputBatchEnded();
            }

            @Override
            public void release(Widget<?> on, float localX, float localY) {
                float[] p = at(on, localX, localY);
                drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, p[0], p[1], 1);
                drive(scene).inputBatchEnded();
                scene.layoutPass(W, H);
                scene.renderFrame(new FakeCanvas(W, H));
            }

            @Override
            public void key(int key) {
                drive(scene).press(key);
                drive(scene).inputBatchEnded();
                scene.layoutPass(W, H);
                scene.renderFrame(new FakeCanvas(W, H));
            }
        };
        R before = read.apply(component);
        drive.accept(component, pointer);
        return List.of(before, read.apply(component));
    }

    /** Runs a case at every angle and requires the upright answer, which must differ from before. */
    private <C extends Widget<?>, R> void sameAtEveryAngle(String what, Supplier<C> build,
                                                          BiConsumer<C, Pointer> drive, Function<C, R> read) {
        List<R> answers = new ArrayList<>();
        R before = null;
        List<Mount> mounts = angles();
        for (Mount mount : mounts) {
            List<R> run = run(build, mount, drive, read);
            if (before == null) {
                before = run.get(0);
            }
            answers.add(run.get(1));
        }
        assertNotEquals(before, answers.get(0), what + ": the upright run must change something to compare");
        for (int i = 1; i < mounts.size(); i++) {
            assertEquals(answers.get(0), answers.get(i), what + ": " + mounts.get(i).name());
        }
    }

    @Test
    void aSlidersDrag() {
        sameAtEveryAngle("slider", () -> new Slider(0, 100),
                (s, p) -> {
                    p.press(s, s.width() * 0.25f, s.height() / 2);
                    p.move(s, s.width() * 0.6f, s.height() / 2);
                    p.release(s, s.width() * 0.6f, s.height() / 2);
                },
                s -> Math.round(s.value() * 10) / 10f);
    }

    @Test
    void aTextFieldsCaret() {
        sameAtEveryAngle("text field", () -> new TextField().setText("hello world, turned"),
                (f, p) -> p.click(f, f.width() * 0.3f, f.height() / 2),
                TextField::caretPosition);
    }

    private record City(String name, String state) {
    }

    @Test
    void aTablesColumnResize() {
        sameAtEveryAngle("table", () -> {
                    Table<City> table = new Table<>(List.of(Column.text("Name", City::name).width(120),
                            Column.text("State", City::state).width(100)));
                    table.setRows(List.of(new City("A", "x"), new City("B", "y")));
                    return table;
                },
                (t, p) -> {
                    float header = Theme.current().tokensFor(t).controlHeight() / 2;
                    float divider = t.widthOf(t.columns().get(0));
                    p.press(t, divider, header);
                    p.move(t, divider + 20, header);
                    p.move(t, divider + 45, header);
                    p.release(t, divider + 45, header);
                },
                t -> Math.round(t.widthOf(t.columns().get(0))));
    }

    private record Node(String name, List<Node> kids) {
    }

    /** A row of fixed height, so a row's place is arithmetic on its y. */
    private static final class Cell extends Widget<Cell> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 32);
        }
    }

    @Test
    void aTreesTriangle() {
        Node folder = new Node("folder", List.of(new Node("inside", List.of())));
        List<Node> roots = List.of(new Node("before", List.of()), folder, new Node("after", List.of()));
        sameAtEveryAngle("tree", () -> new Tree<Node>(new Tree.Model<Node>() {
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
                        return new Cell();
                    }
                }),
                (t, p) -> {
                    Widget<?> cell = null;
                    for (Widget<?> child : t.children()) {
                        if (child instanceof Cell) {
                            cell = child;
                            break;
                        }
                    }
                    assertTrue(cell != null && cell.x() > 0, "a root's cell starts after its triangle band");
                    // The second row's triangle: the middle of the band before its cell.
                    p.click(t, cell.x() / 2, 32 + 16);
                },
                t -> t.isExpanded(folder) + " " + t.selectedNodes());
    }

    @Test
    void theMenuBarsTitles() {
        List<String> chosen = new ArrayList<>();
        sameAtEveryAngle("menu bar", () -> {
                    chosen.clear();
                    return new MenuBar()
                            .addMenu("File", new Menu().addItem("Quit", () -> chosen.add("File")))
                            .addMenu("Edit", new Menu().addItem("Copy", () -> chosen.add("Edit")))
                            .setDisplayMode(DisplayMode.IN_SCENE);
                },
                (b, p) -> {
                    // A press inside the second title (each is 64 points wide under the test
                    // ruler) opens Edit, drawn in the scene over everything; the pointer then
                    // moving onto the first title reaches the bar only through the dropdown's
                    // pass-through for titles, and switches to File. Which menu ended up open is
                    // told by the item the keyboard then chooses in it.
                    p.press(b, 96, b.height() / 2);
                    p.release(b, 96, b.height() / 2);
                    p.move(b, 32, b.height() / 2);
                    p.key(Keys.DOWN);
                    p.key(Keys.ENTER);
                },
                b -> String.valueOf(chosen));
        assertEquals(List.of("File"), chosen, "Edit, then File through the dropdown, at the last angle too");
    }

    @Test
    void aListsRows() {
        sameAtEveryAngle("list", () -> {
                    ListView<String> list = new ListView<>(s -> new Cell());
                    list.setItems(List.of("a", "b", "c", "d", "e"));
                    return list;
                },
                (l, p) -> p.click(l, l.width() / 2, 32 * 2 + 10),
                ListView::selectedIndex);
    }

    @Test
    void aSplitPanesDivider() {
        sameAtEveryAngle("split pane", () -> SplitPane.horizontal(new Cell(), new Cell()),
                (s, p) -> {
                    Widget<?> divider = s.children().get(1);
                    float x = divider.x() + divider.width() / 2;
                    p.press(s, x, s.height() / 2);
                    p.move(s, x - 30, s.height() / 2);
                    p.move(s, x - 60, s.height() / 2);
                    p.release(s, x - 60, s.height() / 2);
                },
                s -> Math.round(s.ratio() * 100));
    }

    @Test
    void aRangeSlidersThumbs() {
        sameAtEveryAngle("range slider", () -> new RangeSlider(0, 100).setValues(20, 80),
                (r, p) -> {
                    p.press(r, r.width() * 0.75f, r.height() / 2);
                    p.move(r, r.width() * 0.6f, r.height() / 2);
                    p.release(r, r.width() * 0.6f, r.height() / 2);
                },
                r -> Math.round(r.lower()) + ".." + Math.round(r.upper()));
    }
}

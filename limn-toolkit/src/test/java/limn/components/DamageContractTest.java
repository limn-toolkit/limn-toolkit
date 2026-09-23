package limn.components;

import limn.testfixtures.IndexedRows;

import limn.components.chart.BarChart;
import limn.components.chart.Chart;
import limn.components.chart.ChartSeries;
import limn.components.chart.DonutChart;
import limn.components.chart.LineChart;
import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.graphics.BackdropEffect;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.Rect;
import limn.graphics.SvgIcon;
import limn.graphics.SvgRasterizer;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static limn.testing.SceneDriver.drive;

/**
 * What every public widget's own gestures repaint, with partial rendering on.
 *
 * <p>ADR 043 &sect;9.4: a clip-asserting test per interactive widget. One row per concrete public
 * widget, read from the sources so that a widget added without a row fails here rather than being
 * quietly untested. Each row drives the gestures that widget answers to, runs every frame they
 * cause until the widget settles, and holds three rules against each frame:
 *
 * <ol>
 *   <li><b>Nothing outside the widget.</b> Every repaint pass lies within the widget's box grown
 *       by {@link #REACH}, which is its focus ring and the margin damage carries. This is the rule
 *       that holds for all of them without exception, and the one a widget breaks by damaging its
 *       parent, its siblings or the scene.</li>
 *   <li><b>Never the window.</b> No frame is a full repaint, unless the gesture names why it has
 *       to be one. A full frame is the defect ADR 043 opened with: the whole screen repainting for
 *       something that happened in one control.</li>
 *   <li><b>For a large widget, what changed.</b> A ceiling, as a share of the widget, over the
 *       frames the gesture causes. Only where it means something: for a button the animation
 *       <em>is</em> the button and rule 1 already bounds it.</li>
 * </ol>
 *
 * <p>A focus gesture skips its first two frames for rule 3. Those are {@code Scene.setFocus}
 * damaging the arriving widget's whole box, doubled by the double-buffer union, and they are
 * right: a scene cannot know that this widget's focus draws inside one row.
 *
 * <p>Every widget is placed at its natural size inside a larger window, so that "the widget" and
 * "the window" are different rectangles; a widget stretched to fill the test box measures the
 * harness. The large ones get a fixed box, the way an application would give them one.
 */
class DamageContractTest extends ComponentTestBase {

    private static final int W = 640;
    private static final int H = 480;
    private static final int INSET = 40;
    /** How far past its box a widget may damage: the focus ring and the damage margin. */
    private static final float REACH = 8;
    /** No ceiling: rule 1 bounds the widget, and the widget is its animation. */
    private static final float ANY = Float.POSITIVE_INFINITY;

    /**
     * One thing a user does to a widget.
     *
     * @param name       what it is, for the failure message
     * @param perform    the gesture, through the scene's own input dispatch where there is one
     * @param skip       frames exempt from the ceiling at the start: 2 for a focus arrival
     * @param ceiling    the most of the widget any other frame may repaint
     * @param fullFrame  why this gesture legitimately repaints the window, or {@code null}
     */
    private record Gesture(String name, BiConsumer<Scene, Widget<?>> perform, int skip, float ceiling,
                           String fullFrame) {
        Gesture ceiling(float share) {
            return new Gesture(name, perform, skip, share, fullFrame);
        }

        Gesture fullFrameBecause(String reason) {
            return new Gesture(name, perform, skip, ceiling, reason);
        }
    }

    /**
     * One widget.
     *
     * @param name     the class, as the ratchet reads it
     * @param build    a fresh instance
     * @param width    a fixed box, or 0 for its natural size
     * @param height   a fixed box, or 0 for its natural size
     * @param gestures what it answers to; empty only with a reason
     * @param inert    why it has no gesture of its own, or {@code null}
     */
    private record Row(String name, Supplier<Widget<?>> build, float width, float height,
                       List<Gesture> gestures, String inert) {
    }

    // ------------------------------------------------------------------------------ gestures

    private static Gesture hover() {
        return new Gesture("pointer arrives", (s, w) -> move(s, centreX(w), centreY(w)), 0, ANY,
                null);
    }

    private static Gesture hoverAt(String name, float dx, float dy) {
        return new Gesture(name, (s, w) -> move(s, w.localToSceneX() + dx, w.localToSceneY() + dy),
                0, ANY, null);
    }

    private static Gesture unhover() {
        return new Gesture("pointer leaves", (s, w) -> move(s, 5, 5), 0, ANY, null);
    }

    private static Gesture click() {
        return clickAt("click", -1, -1);
    }

    private static Gesture clickAt(String name, float dx, float dy) {
        return new Gesture(name, (s, w) -> {
            float x = dx < 0 ? centreX(w) : w.localToSceneX() + dx;
            float y = dy < 0 ? centreY(w) : w.localToSceneY() + dy;
            drive(s).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
            drive(s).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
            drive(s).inputBatchEnded();
        }, 0, ANY, null);
    }

    private static Gesture focus() {
        return new Gesture("focus arrives", (s, w) -> w.requestFocus(), 2, ANY, null);
    }

    private static Gesture key(String name, int key) {
        return new Gesture(name, (s, w) -> {
            drive(s).keyEvent(key, true, false, 0);
            drive(s).keyEvent(key, false, false, 0);
            drive(s).inputBatchEnded();
        }, 0, ANY, null);
    }

    private static Gesture wheel() {
        return new Gesture("wheel", (s, w) -> {
            move(s, centreX(w), centreY(w));
            drive(s).scrolled(0, -3, centreX(w), centreY(w));
            drive(s).inputBatchEnded();
        }, 0, ANY, null);
    }

    /** Pointer arrives, leaves, and a click: what every clickable thing answers to. */
    private static List<Gesture> pointer() {
        return List.of(hover(), unhover(), click());
    }

    /** Focus, then the two arrows that move a cursor in every widget that has one. */
    private static List<Gesture> keyboard() {
        return List.of(focus(), key("RIGHT", Keys.RIGHT), key("DOWN", Keys.DOWN));
    }

    private static List<Gesture> both() {
        List<Gesture> all = new ArrayList<>(pointer());
        all.addAll(keyboard());
        return List.copyOf(all);
    }

    private static void move(Scene scene, float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
    }

    private static float centreX(Widget<?> w) {
        return w.localToSceneX() + w.width() / 2;
    }

    private static float centreY(Widget<?> w) {
        return w.localToSceneY() + w.height() / 2;
    }

    // ------------------------------------------------------------------------------ fixtures

    /** A widget with a size and nothing else. */
    private static final class Plain extends Widget<Plain> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(40, 20);
        }
    }

    private static IndexedRows rows(int count) {
        return new IndexedRows() {
            @Override public int rowCount() {
                return count;
            }

            @Override public Widget<?> rowAt(int index) {
                return new Label("row " + index);
            }

            @Override public void recycle(Widget<?> widget) {
            }
        };
    }

    private static <C extends Chart<?>> C chart(C chart) {
        chart.addSeries(ChartSeries.of("v", 3, 17, 37, 12, 25));
        return chart;
    }

    private static Row natural(String name, Supplier<Widget<?>> build, List<Gesture> gestures) {
        return new Row("limn.components." + name, build, 0, 0, gestures, null);
    }

    private static Row boxed(String name, Supplier<Widget<?>> build, List<Gesture> gestures) {
        return new Row("limn.components." + name, build, 360, 240, gestures, null);
    }

    private static Row inert(String name, Supplier<Widget<?>> build, String why) {
        return new Row("limn.components." + name, build, 0, 0, List.of(), why);
    }

    // ------------------------------------------------------------------------------ the rows

    private static final List<Row> ROWS = List.of(
            natural("BackdropPanel", () -> new BackdropPanel(
                    new BackdropEffect.Wash(Color.BLACK, 0.4f, -0.2f), Insets.NONE,
                    new Button("inside")), pointer()),
            natural("Button", () -> new Button("Go"), both()),
            natural("Checkbox", () -> new Checkbox(Checkbox.Variant.BOX, "Opt"), both()),
            // A colour change repaints each part it touches -- the plane, both ramps, the preview
            // and the fields -- and they span the picker, so the union is the picker. Measured,
            // and accepted: it already damages per part (ColorPicker.invalidateAll).
            natural("ColorPicker", ColorPicker::new, keyboard()),
            natural("ColorPickerButton", () -> new ColorPickerButton(Color.rgb(0xF59E0B)), both()),
            natural("ComboBox", () -> new ComboBox(List.of("one", "two", "three")), both()),
            inert("ImageView", () -> new ImageView(new Image(8, 8, new byte[8 * 8 * 4])),
                    "a picture: nothing about it answers to a pointer or a key"),
            inert("Label", () -> new Label("Hello"), "text: nothing to press, focus or hover"),
            boxed("ListView", () -> IndexedRows.list(rows(40)), List.of(
                    focus().ceiling(0.2f), key("DOWN", Keys.DOWN).ceiling(0.2f),
                    click().ceiling(0.2f))),
            inert("MediaControls", () -> new MediaControls(new VideoView()),
                    "a composite of Button and Slider, each with its own row, and with no media "
                            + "loaded its controls take no input"),
            natural("MenuBar", () -> new MenuBar().addMenu("File",
                    new Menu().addItem("Quit", () -> { })), both()),
            natural("PasswordField", PasswordField::new, both()),
            natural("ProgressBar", ProgressBar::new, List.of(new Gesture("turns indeterminate",
                    (s, w) -> ((ProgressBar) w).setIndeterminate(true), 0, ANY, null))),
            natural("RadioButton", () -> new RadioButton("R"), both()),
            new Row("limn.components.ScrollBar", () -> new ScrollBar(ScrollBar.Orientation.VERTICAL,
                    new ScrollBar.Model() {
                        private float offset;

                        @Override public float contentLength() {
                            return 2000;
                        }

                        @Override public float viewportLength() {
                            return 300;
                        }

                        @Override public float offset() {
                            return offset;
                        }

                        @Override public void setOffset(float value) {
                            offset = value;
                        }
                    }).setPolicy(ScrollBar.Policy.ALWAYS),
                    ScrollBar.thickness(), 240, pointer(), null),
            boxed("ScrollView", () -> new ScrollView(new SizedBox(100, 900, new Plain())),
                    List.of(wheel())),
            natural("SearchField", SearchField::new, both()),
            natural("SegmentedControl", () -> new SegmentedControl(List.of("A", "B", "C")), both()),
            inert("Separator", Separator::horizontal, "a rule: nothing to press, focus or hover"),
            natural("Slider", () -> new Slider(0, 100), both()),
            natural("Spinner", () -> new Spinner(0, 99, 1), both()),
            boxed("SplitPane", () -> SplitPane.horizontal(new Plain(), new Plain()), List.of(
                    hover().ceiling(0.15f), unhover().ceiling(0.15f))),
            boxed("TabbedPane", () -> new TabbedPane().addTab("Alpha", new Plain())
                    .addTab("Beta", new Plain()).addTab("Gamma", new Plain()), List.of(
                    hoverAt("pointer onto a tab", 90, 14).ceiling(0.1f),
                    // The switch shows the new content with Widget.setVisible, which repainted
                    // the window until visibility changes were laid out narrowly (ADR 043
                    // §9.4.4); now it is the content area once, then the strip for the slide.
                    clickAt("switch to a tab", 90, 14))),
            boxed("TextArea", () -> {
                TextArea area = new TextArea();
                area.setText("one\ntwo\nthree\nfour");
                return area;
            }, List.of(focus().ceiling(0.25f), key("RIGHT", Keys.RIGHT).ceiling(0.05f),
                    key("DOWN", Keys.DOWN).ceiling(0.05f), click().ceiling(0.05f))),
            natural("TextField", () -> {
                TextField field = new TextField();
                field.setText("hello");
                return field;
            }, both()),
            inert("TokenBox", () -> new TokenBox(SizeTokens::fieldIcon, null, new Plain()),
                    "a sizing box: it lays a child out and draws nothing"),
            inert("TokenColumn", TokenColumn::new, "a layout: it draws nothing"),
            inert("TokenPadding", () -> new TokenPadding(new Plain()), "a layout: it draws nothing"),
            inert("TokenRow", TokenRow::new, "a layout: it draws nothing"),
            inert("ToolBar", ToolBar::new, "a row of other widgets, each with its own row here"),
            inert("VideoView", VideoView::new,
                    "a surface: it has no gesture of its own, and its transport is MediaControls"),
            boxed("Viewport3D", Viewport3D::new, List.of(focus().ceiling(0.05f))),
            new Row("limn.components.chart.BarChart", () -> chart(new BarChart()), 360, 240,
                    List.of(hover().ceiling(0.45f), unhover().ceiling(0.45f)), null),
            new Row("limn.components.chart.DonutChart", () -> chart(new DonutChart()), 360, 240,
                    List.of(hoverAt("pointer onto a slice", 150, 30).ceiling(0.75f),
                            unhover().ceiling(0.75f)), null),
            new Row("limn.components.chart.LineChart", () -> chart(new LineChart()), 360, 240,
                    List.of(hover().ceiling(0.45f), unhover().ceiling(0.45f)), null),
            new Row("limn.components.date.CalendarView",
                    // A fixed today: the cursor starts on today when the month shows it, and from
                    // the 23rd on, RIGHT then DOWN crossed into the next month, whose new page is a
                    // whole repaint. The test failed by the calendar date, not by the code.
                    () -> new CalendarView()
                            .setClock(java.time.Clock.fixed(java.time.Instant.parse("2026-09-09T12:00:00Z"),
                                    java.time.ZoneOffset.UTC))
                            .setVisibleMonth(java.time.LocalDate.of(2026, 9, 9)),
                    0, 0, List.of(hover().ceiling(0.1f), focus().ceiling(0.1f),
                            key("RIGHT", Keys.RIGHT).ceiling(0.1f),
                            key("DOWN", Keys.DOWN).ceiling(0.1f), click().ceiling(0.1f)), null),
            new Row("limn.components.date.DateField",
                    () -> new DateField().setDate(java.time.LocalDate.of(2026, 9, 9)),
                    0, 0, both(), null),
            new Row("limn.components.date.DatePicker",
                    () -> new DatePicker().setDate(java.time.LocalDate.of(2026, 9, 9)),
                    0, 0, pointer(), null),
            // A table that overflows both axes, so the wheel and a horizontal focus move have
            // something to do (TABLE-NEW-6: the row used to hold one column over eight rows,
            // and neither gesture was under the contract). The click at the centre puts the
            // focus cell on the second column; RIGHT then brings the third in from past the
            // edge, which scrolls the columns and repaints the table, and LEFT walks back to
            // a column in view, a row band. The wheels scroll every row or every column.
            // Ceilings measured, two identical runs each, on 2026-09-14.
            new Row("limn.components.table.Table", DamageContractTest::tableFixture, 360, 240,
                    List.of(focus().ceiling(0.2f), key("DOWN", Keys.DOWN).ceiling(0.2f),
                            click().ceiling(0.5f),
                            // Measured at 101% of the box, the whole of it plus the antialiasing
                            // margin the damage carries: scrolling the columns moves every cell.
                            key("RIGHT, scrolling a column in", Keys.RIGHT).ceiling(1.05f),
                            // Measured at 14%: one row band, the ring moving within it.
                            key("LEFT, to a column in view", Keys.LEFT).ceiling(0.2f),
                            // Both measured at 101%, the wheel moving every row or every column;
                            // the vertical one is the gesture whose absence let a tree ship with
                            // no wheel handler at all (the Tree row below).
                            wheel().ceiling(1.05f),
                            tableSidewaysWheel().ceiling(1.05f),
                            // The header's stop (decision 36), each measured at 14% of the box in
                            // both frames of two identical runs on 2026-09-15: the header band,
                            // where its column cursor is drawn; the rows' ring leaving is off
                            // screen after the wheel, so it adds nothing.
                            tableKey("Shift+Tab into the header", Keys.TAB, Keys.MOD_SHIFT)
                                    .ceiling(0.2f),
                            tableKey("RIGHT on the header", Keys.RIGHT, 0).ceiling(0.2f),
                            tableKey("LEFT on the header", Keys.LEFT, 0).ceiling(0.2f),
                            // A sort moves every row: 101%, the box plus the damage margin. It
                            // was the whole window until the sort asked for a contained layout.
                            tableKey("SPACE sorts the header's column", Keys.SPACE, 0)
                                    .ceiling(1.05f)), null),
            // Right is the gesture only a tree has, and it is the expensive one by construction:
            // opening a row asks for a contained layout, and a contained layout damages the
            // widget's bounds (ADR 043), wherever the row sits. Measured at 101% of the box —
            // the whole of it plus the antialiasing margin the damage carries — and the ceiling
            // says so rather than pretending a tree can open a row for less (ADR 044 §7, amended
            // 2026-09-14: the box, not a band from the row down).
            new Row("limn.components.tree.Tree", DamageContractTest::treeFixture, 360, 240,
                    List.of(focus().ceiling(0.2f), key("DOWN", Keys.DOWN).ceiling(0.3f),
                            key("RIGHT", Keys.RIGHT).ceiling(1.05f),
                            // Left closes the row Right opened: the same contained layout, the
                            // same box, measured at 101% (T8's collapse gesture). It was the
                            // whole window: the hidden rows' cells were taken out of the tree
                            // on the spot, outside any pass, which is a global layout.
                            key("LEFT", Keys.LEFT).ceiling(1.05f),
                            key("RIGHT", Keys.RIGHT).ceiling(1.05f),
                            click().ceiling(0.5f),
                            // The command modifier on the row the click selected takes it out
                            // of a MULTI selection: one band, measured at 6% like DOWN; the
                            // fixture's MULTI is what makes it a toggle and not a click (T8, T1).
                            commandClick().ceiling(0.3f),
                            loadingRowOpens(),
                            loadLands().ceiling(1.05f),
                            // The gesture whose absence here is how a tree shipped with no wheel
                            // handler at all, green the whole time. Measured at 101%: a scroll
                            // moves every row, so it is the whole box plus the antialiasing
                            // margin the damage carries, the same number and the same reason as
                            // RIGHT above.
                            wheel().ceiling(1.05f),
                            // Sideways: the fixture's open chain makes the outline wider than
                            // the box, so the notch moves every row, and the number is the
                            // vertical wheel's for the same reason (T8's sideways gesture).
                            wheelSideways().ceiling(1.05f)), null));

    /**
     * A row whose children have to be fetched is opened, and from then on only its spinner moves.
     *
     * <p>The keys walk from the top to "remote", the fixture's row that loads, which sits under
     * "one" now that RIGHT has opened it, and open it. The first two frames are the opening: the
     * loading line moves every row below, doubled by the double buffer, and the ceilings above
     * already hold that. Every frame after that is the spinner turning on its own, since nothing
     * in this harness lands the load, and the claim is that it repaints its band and nothing
     * else.
     *
     * <p>Measured between 0.35% and 0.4% of the box: one row's triangle band plus the margin damage
     * carries. The ceiling is 0.5%, which a spinner damaging its whole row (about 7% here) or the
     * tree fails outright. The row's first run of this gesture is what found the tree registering
     * its strings in the middle of its own layout, a full frame.
     */
    private static Gesture loadingRowOpens() {
        return new Gesture("a row that loads opens, then only its spinner repaints", (s, w) -> {
            for (int key : new int[] {Keys.HOME, Keys.DOWN, Keys.DOWN, Keys.DOWN, Keys.RIGHT}) {
                drive(s).keyEvent(key, true, false, 0);
                drive(s).keyEvent(key, false, false, 0);
            }
            drive(s).inputBatchEnded();
        }, 2, 0.005f, null);
    }

    /** The click with the platform's command modifier held: a toggle in MULTI. */
    private static Gesture commandClick() {
        return new Gesture("command-click", (s, w) -> {
            float x = centreX(w);
            float y = centreY(w);
            int mods = Accelerator.commandModifier();
            drive(s).mouseButton(Keys.MOUSE_LEFT, true, mods, x, y);
            drive(s).mouseButton(Keys.MOUSE_LEFT, false, mods, x, y);
            drive(s).inputBatchEnded();
        }, 0, ANY, null);
    }

    /**
     * The load the gesture above started lands: the UI queue is pumped until the fetched row is
     * a row, and the frames after that are the landing — a contained layout, the box (ADR 044
     * §7, amended), never the window. It was the window: the loading line's cell was taken out
     * of the tree the moment the children arrived, outside any pass, which declared a global
     * layout (the same path a collapse took, see LEFT above). Measured at 101%.
     */
    private static Gesture loadLands() {
        return new Gesture("the load lands", (s, w) -> {
            limn.components.tree.Tree<?> tree = (limn.components.tree.Tree<?>) w;
            int before = tree.visibleRowCount();
            pump.pumpUntil(() -> tree.visibleRowCount() > before);
        }, 0, ANY, null);
    }

    /** A trackpad's sideways notch over the widget's centre. */
    private static Gesture wheelSideways() {
        return new Gesture("wheel sideways", (s, w) -> {
            move(s, centreX(w), centreY(w));
            drive(s).scrolled(-3, 0, centreX(w), centreY(w));
            drive(s).inputBatchEnded();
        }, 0, ANY, null);
    }

    /** Three 150-point columns over twenty rows: wider and taller than the 360 by 240 box. */
    private static Widget<?> tableFixture() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add("row " + i);
        }
        Table<String> table = new Table<>(List.of(
                Column.<String>text("Name", s -> s).width(150),
                Column.<String>text("Again", s -> s).width(150),
                Column.<String>text("Once more", s -> s).width(150)));
        table.setRows(rows);
        return table;
    }

    /**
     * A trackpad's sideways swipe over the table, back toward the first column: three detents
     * of scrollX and no scrollY, after RIGHT has scrolled the columns the other way.
     */
    private static Gesture tableSidewaysWheel() {
        return new Gesture("sideways wheel", (s, w) -> {
            move(s, centreX(w), centreY(w));
            drive(s).scrolled(3, 0, centreX(w), centreY(w));
            drive(s).inputBatchEnded();
        }, 0, ANY, null);
    }

    /** A key with modifiers held, for the table's header stop (decision 36). */
    private static Gesture tableKey(String name, int key, int modifiers) {
        return new Gesture(name, (s, w) -> {
            drive(s).keyEvent(key, true, false, modifiers);
            drive(s).keyEvent(key, false, false, modifiers);
            drive(s).inputBatchEnded();
        }, 0, ANY, null);
    }

    /** A node of the tree fixture: a name and the children it admits to. */
    private record TreeNode(String name, List<TreeNode> kids) {
    }

    /**
     * A forest: enough rows to scroll, a first row that can open, one that loads, and a chain
     * of fourteen levels at the end, open, so the outline is wider than its box and a sideways
     * notch has somewhere to go. The chain sits below the viewport, so the gestures above the
     * fold see the same rows they always did; MULTI, so the command modifier toggles.
     */
    private static Widget<?> treeFixture() {
        // Enough rows to fill the 240-point box, so the click at its centre lands on one: a
        // gesture that reaches nothing repaints nothing, and a ceiling over it asserts nothing.
        List<TreeNode> roots = new ArrayList<>();
        roots.add(new TreeNode("one", List.of(new TreeNode("one.a", List.of()),
                new TreeNode("one.b", List.of()))));
        roots.add(new TreeNode("remote", List.of()));
        for (int i = 3; i <= 20; i++) {
            roots.add(new TreeNode("row " + i, List.of()));
        }
        TreeNode deep = new TreeNode("level-14", List.of());
        for (int i = 13; i >= 1; i--) {
            deep = new TreeNode("level-" + i, List.of(deep));
        }
        roots.add(deep);
        limn.components.tree.Tree<TreeNode> tree = new limn.components.tree.Tree<>(
                new limn.components.tree.Tree.Model<TreeNode>() {
                    @Override
                    public List<TreeNode> roots() {
                        return roots;
                    }

                    @Override
                    public List<TreeNode> children(TreeNode node) {
                        return node.name().equals("remote") ? null : node.kids();
                    }

                    @Override
                    public limn.concurrent.Work<List<TreeNode>> load(TreeNode node) {
                        return limn.concurrent.Ui.work(progress ->
                                List.of(new TreeNode("fetched", List.of())));
                    }

                    @Override
                    public Widget<?> cellFor(TreeNode node) {
                        return new Label(node.name());
                    }
                });
        tree.setSelectionMode(SelectionMode.MULTI);
        for (TreeNode node = deep; node != null;
                node = node.kids().isEmpty() ? null : node.kids().get(0)) {
            tree.expand(node);
        }
        // The bars' first-overflow flash used to be pinned here (their clock held at zero):
        // its fade-out landed in the load gesture's third frame, 12% of the box. mount() now
        // lets the hold elapse and the fade end before the first gesture, for every row.
        return tree;
    }

    // ------------------------------------------------------------------------------ harness

    /** Icons rasterize through the backend, and a test has none: a paint would throw. */
    private static final SvgRasterizer RASTERIZER = (svg, px) -> new Image(1, 1, new byte[4]);

    /** The test's UI queue, reachable from a static gesture that has to land a load. */
    private static limn.testing.HeadlessUi pump;

    @BeforeEach
    void installRasterizer() {
        SvgIcon.installRasterizer(RASTERIZER);
        pump = ui;
    }

    @AfterEach
    void uninstallRasterizer() {
        SvgIcon.uninstallRasterizer(RASTERIZER);
    }

    private Scene scene;
    private AtomicLong nanos;
    private RecordingTestCanvas canvas;

    private Widget<?> mount(Row row) {
        Widget<?> widget = row.build().get();
        Widget<?> placed = row.width() > 0 ? new SizedBox(row.width(), row.height(), widget) : widget;
        // A column, because it places a child at its natural size; a Padding alone would stretch
        // it to the inner box, and a stretched button measures the harness and not the button.
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(placed);
        nanos = new AtomicLong();
        scene = new Scene(new Padding(Insets.all(INSET), column), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        canvas = new RecordingTestCanvas(W, H);
        // A runtime of the row's own, on the scene's clock, pumped while the widget settles: a
        // bar's first-overflow hold is a delayed task, and on the wall clock, never pumped, its
        // fade-out waited for whichever gesture first laid the widget out and landed in that
        // gesture's frames (the tree lane's item 14 hazard). Its own, because an earlier row's
        // focused field keeps re-posting its caret blink. At rest means nothing painted and
        // nothing waiting on the clock.
        ui.close();
        ui = new limn.testing.HeadlessUi(nanos::get);
        runtime = ui.runtime();
        pump = ui;
        for (int i = 0; i < 300; i++) {
            nanos.addAndGet(200_000_000L);
            runtime.drain();
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted() && runtime.nanosUntilNextDeadline() < 0) {
                return widget;
            }
        }
        throw new AssertionError(row.name() + " never settled, so a reading would measure the "
                + "settling and not a gesture");
    }

    /**
     * Runs one gesture to rest and appends the first rule it broke, if any, to {@code offenders}.
     *
     * <p>To rest even after a failure, and that matters: stopping at the first bad frame leaves
     * the double buffer's second frame unrendered, and it lands in the NEXT gesture's reading as
     * a violation that gesture never committed. Found by backing out a fix and watching an
     * innocent click fail beside the arrow key that had.
     */
    private void check(Row row, Widget<?> widget, Gesture gesture, List<String> offenders) {
        gesture.perform().accept(scene, widget);
        float bx = widget.localToSceneX();
        float by = widget.localToSceneY();
        float box = Math.max(1, widget.width() * widget.height());
        String who = row.name().replace("limn.components.", "") + ", " + gesture.name();
        String broke = null;
        int painted = 0;
        for (int frame = 0; frame < 60; frame++) {
            nanos.addAndGet(16_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                break;
            }
            painted++;
            if (broke != null) {
                continue; // draining, so the next gesture starts from rest
            }
            if (canvas.cleared) {
                if (gesture.fullFrame() == null) {
                    broke = "frame " + frame + " repainted the whole window";
                }
                continue;
            }
            for (Rect pass : canvas.passClips()) {
                if (pass.x() < bx - REACH || pass.y() < by - REACH
                        || pass.x() + pass.width() > bx + widget.width() + REACH
                        || pass.y() + pass.height() > by + widget.height() + REACH) {
                    broke = "frame " + frame + " repainted " + pass + ", outside the widget at "
                            + bx + "," + by + " " + widget.width() + "x" + widget.height();
                    break;
                }
            }
            float share = canvas.damagedArea() / box;
            if (broke == null && frame >= gesture.skip() && share > gesture.ceiling()) {
                broke = "frame " + frame + " repainted " + Math.round(share * 100)
                        + "% of the widget, ceiling " + Math.round(gesture.ceiling() * 100) + "%";
            }
        }
        if (broke == null && painted == 0 && gesture.ceiling() != ANY) {
            // A ceiling is a claim about frames, and a gesture that painted none made no claim:
            // most likely it missed what it aimed at, and the row is testing nothing.
            broke = "painted nothing, so its ceiling asserted nothing";
        }
        if (broke != null) {
            offenders.add(who + ": " + broke);
        }
    }

    // ------------------------------------------------------------------------------ the tests

    /**
     * The harness itself: a widget is measured from rest, and a scroll bar's hold is part of
     * getting there. A table that overflows flashes its bars once when mounted; their fade-out
     * must be over before the first gesture, or it is measured as that gesture's.
     */
    @Test
    void aMountedWidgetsBarsHaveFadedBeforeTheFirstGesture() {
        Row table = ROWS.stream().filter(r -> r.name().equals("limn.components.table.Table"))
                .findFirst().orElseThrow();
        Widget<?> widget = mount(table);
        int bars = 0;
        for (Widget<?> child : widget.children()) {
            if (child instanceof ScrollBar bar) {
                bars++;
                assertEquals(0f, bar.shownOpacity(), "bar " + bars
                        + "'s first-overflow flash faded while the widget settled");
            }
        }
        assertEquals(2, bars, "the fixture overflows both axes");
    }

    /**
     * Every concrete public widget has a row, and every row names one that exists. A widget
     * added without a row lands here instead of going untested, and the answer is a row: its
     * gestures, or the sentence saying why it has none.
     */
    @Test
    void everyConcretePublicWidgetHasARow() {
        TreeSet<String> declared = new TreeSet<>();
        for (Class<?> type : PublicWidgets.concrete()) {
            declared.add(type.getName());
        }
        TreeSet<String> rows = new TreeSet<>();
        for (Row row : ROWS) {
            rows.add(row.name());
        }
        assertEquals(declared, rows, "the table and the components disagree: a widget with no "
                + "row, or a row with no widget");
    }

    /** An inert row says why, and a row with gestures does not pretend to be inert. */
    @Test
    void everyRowEitherActsOrSaysWhyItCannot() {
        List<String> offenders = new ArrayList<>();
        for (Row row : ROWS) {
            if (row.gestures().isEmpty() == (row.inert() == null)) {
                offenders.add(row.name());
            }
        }
        assertEquals(List.of(), offenders, "a row with no gesture needs a reason, and only then");
    }

    @Test
    void everyGestureRepaintsItsWidgetAndNotTheWindow() {
        List<String> offenders = new ArrayList<>();
        for (Row row : ROWS) {
            if (row.gestures().isEmpty()) {
                continue;
            }
            // One mount per row and the gestures in the order written, because the sequence is
            // part of what is tested: the arrows come after the focus they need.
            Widget<?> widget = mount(row);
            for (Gesture gesture : row.gestures()) {
                check(row, widget, gesture, offenders);
            }
        }
        assertEquals(List.of(), offenders, "gestures repainted more than they changed");
    }
}

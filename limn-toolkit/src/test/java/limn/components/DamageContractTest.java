package limn.components;

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
    private record Gesture(String name, BiConsumer<Scene, Widget> perform, int skip, float ceiling,
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
    private record Row(String name, Supplier<Widget> build, float width, float height,
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
            s.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
            s.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
            s.inputBatchEnded();
        }, 0, ANY, null);
    }

    private static Gesture focus() {
        return new Gesture("focus arrives", (s, w) -> w.requestFocus(), 2, ANY, null);
    }

    private static Gesture key(String name, int key) {
        return new Gesture(name, (s, w) -> {
            s.keyEvent(key, true, false, 0);
            s.keyEvent(key, false, false, 0);
            s.inputBatchEnded();
        }, 0, ANY, null);
    }

    private static Gesture wheel() {
        return new Gesture("wheel", (s, w) -> {
            move(s, centreX(w), centreY(w));
            s.scrolled(0, -3, centreX(w), centreY(w));
            s.inputBatchEnded();
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
        scene.mouseMoved(x, y);
        scene.inputBatchEnded();
    }

    private static float centreX(Widget w) {
        return w.localToSceneX() + w.width() / 2;
    }

    private static float centreY(Widget w) {
        return w.localToSceneY() + w.height() / 2;
    }

    // ------------------------------------------------------------------------------ fixtures

    /** A widget with a size and nothing else. */
    private static final class Plain extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(40, 20);
        }
    }

    private static ListView.Adapter rows(int count) {
        return new ListView.Adapter() {
            @Override public int rowCount() {
                return count;
            }

            @Override public Widget rowAt(int index) {
                return new Label("row " + index);
            }

            @Override public void recycle(Widget widget) {
            }
        };
    }

    private static <C extends Chart> C chart(C chart) {
        chart.addSeries(ChartSeries.of("v", 3, 17, 37, 12, 25));
        return chart;
    }

    private static Row natural(String name, Supplier<Widget> build, List<Gesture> gestures) {
        return new Row("limn.components." + name, build, 0, 0, gestures, null);
    }

    private static Row boxed(String name, Supplier<Widget> build, List<Gesture> gestures) {
        return new Row("limn.components." + name, build, 360, 240, gestures, null);
    }

    private static Row inert(String name, Supplier<Widget> build, String why) {
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
            boxed("ListView", () -> new ListView(rows(40)), List.of(
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
                    () -> new CalendarView().setVisibleMonth(java.time.LocalDate.of(2026, 9, 9)),
                    0, 0, List.of(hover().ceiling(0.1f), focus().ceiling(0.1f),
                            key("RIGHT", Keys.RIGHT).ceiling(0.1f),
                            key("DOWN", Keys.DOWN).ceiling(0.1f), click().ceiling(0.1f)), null),
            new Row("limn.components.date.DateField",
                    () -> new DateField().setDate(java.time.LocalDate.of(2026, 9, 9)),
                    0, 0, both(), null),
            new Row("limn.components.date.DatePicker",
                    () -> new DatePicker().setDate(java.time.LocalDate.of(2026, 9, 9)),
                    0, 0, pointer(), null),
            new Row("limn.components.table.Table", () -> {
                Table<String> table = new Table<>(List.of(
                        Column.<String>text("Name", s -> s).width(100)));
                table.setRows(List.of("a", "b", "c", "d", "e", "f", "g", "h"));
                return table;
            }, 360, 240, List.of(focus().ceiling(0.2f), key("DOWN", Keys.DOWN).ceiling(0.2f),
                    click().ceiling(0.5f)), null),
            // Right is the gesture only a tree has, and it is the expensive one by construction:
            // opening the first row moves every row under it, so the band runs from that row to
            // the foot of the viewport. Measured at 101% of the box — the whole of it plus the
            // antialiasing margin the damage carries — and the ceiling says so rather than
            // pretending a tree can open a row for less (ADR 044 §7).
            new Row("limn.components.tree.Tree", DamageContractTest::treeFixture, 360, 240,
                    List.of(focus().ceiling(0.2f), key("DOWN", Keys.DOWN).ceiling(0.3f),
                            key("RIGHT", Keys.RIGHT).ceiling(1.05f), click().ceiling(0.5f)), null));

    /** A two-level forest: enough rows to scroll, and a first row that can open. */
    private static Widget treeFixture() {
        record Node(String name, List<Node> kids) {
        }
        // Enough rows to fill the 240-point box, so the click at its centre lands on one: a
        // gesture that reaches nothing repaints nothing, and a ceiling over it asserts nothing.
        List<Node> roots = new ArrayList<>();
        roots.add(new Node("one", List.of(new Node("one.a", List.of()),
                new Node("one.b", List.of()))));
        for (int i = 2; i <= 20; i++) {
            roots.add(new Node("row " + i, List.of()));
        }
        return new limn.components.tree.Tree<Node>(new limn.components.tree.Tree.Model<Node>() {
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
                return new Label(node.name());
            }
        });
    }

    // ------------------------------------------------------------------------------ harness

    /** Icons rasterize through the backend, and a test has none: a paint would throw. */
    private static final SvgRasterizer RASTERIZER = (svg, px) -> new Image(1, 1, new byte[4]);

    @BeforeEach
    void installRasterizer() {
        SvgIcon.installRasterizer(RASTERIZER);
    }

    @AfterEach
    void uninstallRasterizer() {
        SvgIcon.uninstallRasterizer(RASTERIZER);
    }

    private Scene scene;
    private AtomicLong nanos;
    private RecordingTestCanvas canvas;

    private Widget mount(Row row) {
        Widget widget = row.build().get();
        Widget placed = row.width() > 0 ? new SizedBox(row.width(), row.height(), widget) : widget;
        // A column, because it places a child at its natural size; a Padding alone would stretch
        // it to the inner box, and a stretched button measures the harness and not the button.
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(placed);
        nanos = new AtomicLong();
        scene = new Scene(new Padding(Insets.all(INSET), column), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        canvas = new RecordingTestCanvas(W, H);
        for (int i = 0; i < 300; i++) {
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
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
    private void check(Row row, Widget widget, Gesture gesture, List<String> offenders) {
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
            Widget widget = mount(row);
            for (Gesture gesture : row.gestures()) {
                check(row, widget, gesture, offenders);
            }
        }
        assertEquals(List.of(), offenders, "gestures repainted more than they changed");
    }
}

package limn.components;

import limn.backend.CrashHandler;
import limn.backend.CrashPhase;
import limn.backend.Crashes;
import limn.components.chart.BarChart;
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
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.SizedBox;
import limn.testing.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four obligations ADR 040 §1.11 puts on a component author, pinned for every widget the
 * toolkit ships: announce from one seam, last and only when something moved; label the origin;
 * announce no state aspect no accessor answers; and reach the handler through
 * {@code handleUserChange}, chaining to {@code super}.
 *
 * <p><b>Table-driven, and enumerated from the classes the component sources declare rather than
 * listed by hand</b>, exactly as {@code AccessibleCoverageTest} enumerates them: a component added
 * without a row here fails rather than being quietly absent. The subpackages are not a detail --
 * {@code Chart} lives in {@code limn.components.chart} and {@code Table} in
 * {@code limn.components.table}, and an enumeration over one package would have reported a green
 * build for the two widgets the record adds the most to.
 *
 * <p>Each row names the widget, how to build it, one <em>caller's write</em> with the aspect it
 * announces, and, where the widget has a handler, one <em>gesture</em> driven through the scene's
 * own input dispatch with the aspect it announces and the handler it must reach. For each row
 * both halves are asserted: the write announces exactly once as {@code CODE} and reaches no
 * handler; the gesture announces as {@code USER} and reaches the handler <em>after</em> every
 * watcher; the write repeated with the state already held announces nothing; a second watcher
 * disturbs neither the first nor the handler; and painting the widget with a watcher attached
 * announces nothing at all.
 *
 * <p>The five public types that are not widgets -- {@code ButtonGroup}, {@code Menu},
 * {@code MenuItem}, {@code PopupMenu}, {@code Dialog} -- are outside anything an enumeration over
 * {@code Widget} can reach, and are the exception rather than the floor: the radio group is
 * driven through its members below, and the menu paths are asserted on the surface that presents
 * them.
 */
class NotificationContractTest extends ComponentTestBase {

    /** What one widget does with a caller's write and, where it has one, with the user's gesture. */
    private record Row(
            String name,
            Supplier<Widget> build,
            Consumer<Widget> write,
            Change.Aspect written,
            Gesture gesture) {
    }

    /**
     * One gesture through the scene's input dispatch, the aspect it announces and how to arm the
     * handler it must reach.
     */
    private record Gesture(
            Change.Aspect aspect,
            BiConsumer<Widget, Runnable> arm,
            BiConsumer<Scene, Widget> perform) {
    }

    // ------------------------------------------------------------------------------ the rows

    private static final List<Row> ROWS = List.of(
            new Row("limn.components.BackdropPanel",
                    () -> new BackdropPanel(new BackdropEffect.Wash(Color.BLACK, 0.4f, -0.2f),
                            Insets.NONE, new Plain()),
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.Button",
                    () -> new Button("Go"),
                    w -> ((Button) w).setText("Gone"), Change.Aspect.NAME,
                    new Gesture(Change.Aspect.INVOKED,
                            (w, ran) -> ((Button) w).onAction(ran),
                            (scene, w) -> click(scene, w))),
            new Row("limn.components.Checkbox",
                    () -> new Checkbox(Checkbox.Variant.BOX, "Opt"),
                    w -> ((Checkbox) w).setChecked(true), Change.Aspect.VALUE,
                    new Gesture(Change.Aspect.VALUE,
                            (w, ran) -> ((Checkbox) w).onChange(v -> ran.run()),
                            (scene, w) -> click(scene, w))),
            new Row("limn.components.ColorPicker",
                    ColorPicker::new,
                    w -> ((ColorPicker) w).setColor(Color.rgb(0x112233)), Change.Aspect.VALUE, null),
            new Row("limn.components.ColorPickerButton",
                    () -> new ColorPickerButton(Color.rgb(0xF59E0B)),
                    w -> ((ColorPickerButton) w).setColor(Color.rgb(0x112233)), Change.Aspect.VALUE,
                    null),
            new Row("limn.components.ComboBox",
                    () -> new ComboBox(List.of("one", "two", "three")),
                    w -> ((ComboBox) w).setSelectedIndex(1), Change.Aspect.SELECTION,
                    new Gesture(Change.Aspect.SELECTION,
                            (w, ran) -> ((ComboBox) w).onSelect(i -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                key(scene, Keys.SPACE);
                                key(scene, Keys.DOWN);
                                key(scene, Keys.ENTER);
                            })),
            new Row("limn.components.ImageView",
                    () -> new ImageView(new Image(8, 8, new byte[8 * 8 * 4])),
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.Label",
                    () -> new Label("Hello"),
                    w -> ((Label) w).setText("Goodbye"), Change.Aspect.NAME, null),
            new Row("limn.components.ListView",
                    () -> new ListView(rows(4)),
                    w -> ((ListView) w).setSelectedIndex(1), Change.Aspect.SELECTION,
                    new Gesture(Change.Aspect.SELECTION,
                            (w, ran) -> ((ListView) w).onSelect(i -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                key(scene, Keys.DOWN);
                            })),
            new Row("limn.components.MediaControls",
                    () -> new MediaControls(new VideoView()),
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.MenuBar",
                    () -> new MenuBar().addMenu("File", new Menu().addItem("Quit", () -> { })),
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.PasswordField",
                    PasswordField::new,
                    w -> ((PasswordField) w).setRevealed(true), Change.Aspect.VALUE,
                    new Gesture(Change.Aspect.TEXT,
                            (w, ran) -> ((PasswordField) w).onChange(t -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                scene.charTyped('a');
                                scene.inputBatchEnded();
                            })),
            new Row("limn.components.ProgressBar",
                    ProgressBar::new,
                    w -> ((ProgressBar) w).setProgress(0.5f), Change.Aspect.VALUE, null),
            new Row("limn.components.RadioButton",
                    () -> new RadioButton("R"),
                    w -> ((RadioButton) w).select(), Change.Aspect.VALUE,
                    new Gesture(Change.Aspect.VALUE,
                            (w, ran) -> ((RadioButton) w).onChange(v -> ran.run()),
                            (scene, w) -> click(scene, w))),
            new Row("limn.components.ScrollBar",
                    () -> new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
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
                    }),
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.ScrollView",
                    () -> new ScrollView(new SizedBox(100, 600, new Plain())),
                    w -> ((ScrollView) w).scrollTo(0, 40), Change.Aspect.VALUE, null),
            new Row("limn.components.SearchField",
                    SearchField::new,
                    w -> ((SearchField) w).setText("shoes"), Change.Aspect.TEXT,
                    // The inherited gesture: a subclass that overrode handleUserChange for its own
                    // aspect and forgot super would fail here, which is obligation 4.
                    new Gesture(Change.Aspect.TEXT,
                            (w, ran) -> ((SearchField) w).onChange(t -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                scene.charTyped('a');
                                scene.inputBatchEnded();
                            })),
            new Row("limn.components.SegmentedControl",
                    () -> new SegmentedControl(List.of("A", "B", "C")),
                    w -> ((SegmentedControl) w).setSelectedIndex(1), Change.Aspect.SELECTION,
                    new Gesture(Change.Aspect.SELECTION,
                            (w, ran) -> ((SegmentedControl) w).onSelect(i -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                key(scene, Keys.RIGHT);
                            })),
            new Row("limn.components.Separator",
                    Separator::horizontal,
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.Slider",
                    () -> new Slider(0, 100),
                    w -> ((Slider) w).setValue(50), Change.Aspect.VALUE,
                    new Gesture(Change.Aspect.VALUE,
                            (w, ran) -> ((Slider) w).onChange(v -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                key(scene, Keys.RIGHT);
                            })),
            new Row("limn.components.Spinner",
                    () -> new Spinner(0, 99, 1),
                    w -> ((Spinner) w).setValue(5), Change.Aspect.VALUE,
                    new Gesture(Change.Aspect.VALUE,
                            (w, ran) -> ((Spinner) w).onChange(v -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                key(scene, Keys.UP);
                            })),
            new Row("limn.components.SplitPane",
                    () -> SplitPane.horizontal(new Plain(), new Plain()),
                    w -> ((SplitPane) w).setRatio(0.3f), Change.Aspect.VALUE, null),
            new Row("limn.components.TabbedPane",
                    () -> new TabbedPane().addTab("A", new Plain()).addTab("B", new Plain()),
                    w -> ((TabbedPane) w).setSelectedIndex(1), Change.Aspect.SELECTION,
                    new Gesture(Change.Aspect.SELECTION,
                            (w, ran) -> ((TabbedPane) w).onSelect(i -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w.children().get(0).children().get(0));
                                key(scene, Keys.RIGHT);
                            })),
            new Row("limn.components.TextArea",
                    TextArea::new,
                    w -> ((TextArea) w).setText("body"), Change.Aspect.TEXT,
                    new Gesture(Change.Aspect.TEXT,
                            (w, ran) -> ((TextArea) w).onChange(t -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                scene.charTyped('a');
                                scene.inputBatchEnded();
                            })),
            new Row("limn.components.TextField",
                    TextField::new,
                    w -> ((TextField) w).setText("name"), Change.Aspect.TEXT,
                    new Gesture(Change.Aspect.TEXT,
                            (w, ran) -> ((TextField) w).onChange(t -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                scene.charTyped('a');
                                scene.inputBatchEnded();
                            })),
            new Row("limn.components.TokenBox",
                    () -> new TokenBox(SizeTokens::fieldIcon, null, new Plain()),
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.TokenColumn",
                    TokenColumn::new,
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.TokenPadding",
                    () -> new TokenPadding(new Plain()),
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.TokenRow",
                    TokenRow::new,
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.ToolBar",
                    ToolBar::new,
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.VideoView",
                    VideoView::new,
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.Viewport3D",
                    Viewport3D::new,
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.chart.BarChart",
                    () -> chart(new BarChart()),
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.chart.DonutChart",
                    () -> {
                        DonutChart chart = new DonutChart();
                        chart.setAnimationDuration(0);
                        chart.addSeries(ChartSeries.of("v", 3, 5));
                        return chart;
                    },
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            new Row("limn.components.chart.LineChart",
                    () -> chart(new LineChart()),
                    w -> w.setVisible(false), Change.Aspect.VISIBLE, null),
            // ADR 042's three. The calendar's gesture is Enter on the keyboard cursor and not a
            // click: a click needs the grid's geometry to have settled, and Enter reaches the same
            // private pick() through the same USER seam, which is what this test is about.
            new Row("limn.components.date.CalendarView",
                    () -> new CalendarView().setVisibleMonth(java.time.LocalDate.of(2026, 9, 9)),
                    w -> ((CalendarView) w).setSelectedDate(java.time.LocalDate.of(2026, 9, 15)),
                    Change.Aspect.SELECTION,
                    new Gesture(Change.Aspect.SELECTION,
                            (w, ran) -> ((CalendarView) w).onSelect(d -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                key(scene, Keys.ENTER);
                            })),
            new Row("limn.components.date.DateField",
                    () -> new DateField().setDate(java.time.LocalDate.of(2026, 9, 9)),
                    w -> ((DateField) w).setDate(java.time.LocalDate.of(2026, 9, 15)),
                    Change.Aspect.VALUE,
                    new Gesture(Change.Aspect.VALUE,
                            (w, ran) -> ((DateField) w).onChange(d -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                key(scene, Keys.UP);
                            })),
            // The picker's write goes through its field, and the picker forwards what the field
            // announced: that forwarding is the row's subject, since a watcher on the picker must
            // not have to know the picker has children.
            new Row("limn.components.date.DatePicker",
                    () -> new DatePicker().setDate(java.time.LocalDate.of(2026, 9, 9)),
                    w -> ((DatePicker) w).setDate(java.time.LocalDate.of(2026, 9, 15)),
                    Change.Aspect.VALUE,
                    new Gesture(Change.Aspect.VALUE,
                            (w, ran) -> ((DatePicker) w).onSelect(d -> ran.run()),
                            (scene, w) -> {
                                scene.requestFocus(((DatePicker) w).field());
                                key(scene, Keys.UP);
                            })),
            new Row("limn.components.table.Table",
                    () -> {
                        Table<String> table = new Table<>(List.of(
                                Column.<String>text("Name", s -> s).width(100)));
                        table.setRows(List.of("a", "b", "c", "d"));
                        return table;
                    },
                    w -> ((Table<?>) w).setSelectedRow(1), Change.Aspect.SELECTION,
                    new Gesture(Change.Aspect.SELECTION,
                            (w, ran) -> ((Table<?>) w).onSelect(ran),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                key(scene, Keys.DOWN);
                            })),
            new Row("limn.components.tree.Tree",
                    NotificationContractTest::treeFixture,
                    w -> selectSecondRow(w), Change.Aspect.SELECTION,
                    new Gesture(Change.Aspect.SELECTION,
                            (w, ran) -> asTree(w).onSelect(ran),
                            (scene, w) -> {
                                scene.requestFocus(w);
                                key(scene, Keys.DOWN);
                            })));

    /** The fixture's node type, shared by the builder and the write below. */
    private record TreeNode(String name, List<TreeNode> kids) {
    }

    private static final List<TreeNode> TREE_ROOTS = List.of(
            new TreeNode("one", List.of(new TreeNode("one.a", List.of()))),
            new TreeNode("two", List.of()),
            new TreeNode("three", List.of()));

    @SuppressWarnings("unchecked")
    private static limn.components.tree.Tree<TreeNode> asTree(Widget w) {
        return (limn.components.tree.Tree<TreeNode>) w;
    }

    /** A caller's write: the second root, which is not what any gesture below selects. */
    private static void selectSecondRow(Widget w) {
        asTree(w).setSelected(TREE_ROOTS.get(1));
    }

    private static Widget treeFixture() {
        return new limn.components.tree.Tree<TreeNode>(
                new limn.components.tree.Tree.Model<TreeNode>() {
                    @Override
                    public List<TreeNode> roots() {
                        return TREE_ROOTS;
                    }

                    @Override
                    public List<TreeNode> children(TreeNode node) {
                        return node.kids();
                    }

                    @Override
                    public Widget cellFor(TreeNode node) {
                        return new Label(node.name());
                    }
                });
    }

    // -------------------------------------------------------------------------- helpers

    /** A widget with a size and nothing else. */
    private static final class Plain extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(40, 20);
        }
    }

    private static ListView.Adapter rows(int count) {
        return new ListView.Adapter() {
            @Override
            public int rowCount() {
                return count;
            }

            @Override
            public Widget rowAt(int index) {
                return new Plain();
            }
        };
    }

    private static <C extends limn.components.chart.CartesianChart> C chart(C chart) {
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c");
        chart.addSeries(ChartSeries.of("v", 3, 17, 37));
        return chart;
    }

    private static void key(Scene scene, int keyCode) {
        scene.keyEvent(keyCode, true, false, 0);
        scene.keyEvent(keyCode, false, false, 0);
        scene.inputBatchEnded();
    }

    private static void click(Scene scene, Widget w) {
        float x = w.localToSceneX() + w.width() / 2;
        float y = w.localToSceneY() + w.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    /** A scene laid out around the widget, big enough for any row here. */
    private Scene sceneOf(Widget widget) {
        Scene scene = new Scene(widget);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 200);
        return scene;
    }

    /** What one watcher heard about one widget, narrowed to one aspect. */
    private static List<Change.Origin> heard(Widget widget, Change.Aspect aspect) {
        List<Change.Origin> origins = new ArrayList<>();
        widget.observeChanges((source, change) -> {
            if (change.aspect() == aspect) {
                assertTrue(source == widget, "a widget announces itself and nothing else");
                origins.add(change.origin());
            }
        });
        return origins;
    }

    // ------------------------------------------------------------------------- the ratchet

    /**
     * Every concrete public widget the component sources declare, subpackages included, has a
     * row, and every row names a class that exists: the enumeration is the floor, and a widget
     * added without a row lands here rather than being quietly absent.
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
        assertEquals(declared, rows,
                "the table and the components disagree: a widget with no row, or a row with no "
                        + "widget");
    }

    // ------------------------------------------------------------------------ the two halves

    @Test
    void aCallersWriteAnnouncesOnceAsCodeAndReachesNoHandler() {
        for (Row row : ROWS) {
            Widget widget = row.build().get();
            sceneOf(widget);
            AtomicInteger handled = new AtomicInteger();
            if (row.gesture() != null) {
                row.gesture().arm().accept(widget, handled::incrementAndGet);
            }
            List<Change.Origin> heard = heard(widget, row.written());

            row.write().accept(widget);

            assertEquals(List.of(Change.Origin.CODE), heard,
                    row.name() + ": one announcement, and it is a caller's write");
            assertEquals(0, handled.get(), row.name() + ": the handler answers the user");
        }
    }

    @Test
    void theStateAlreadyHeldAnnouncesNothing() {
        for (Row row : ROWS) {
            Widget widget = row.build().get();
            sceneOf(widget);
            row.write().accept(widget);
            List<Change.Origin> heard = heard(widget, row.written());

            row.write().accept(widget);

            assertEquals(List.of(), heard,
                    row.name() + ": a mutator handed the state it already holds announces nothing");
        }
    }

    @Test
    void aGestureAnnouncesAsUserAndReachesTheHandlerAfterEveryWatcher() {
        for (Row row : ROWS) {
            if (row.gesture() == null) {
                continue;
            }
            Widget widget = row.build().get();
            Scene scene = sceneOf(widget);
            List<String> order = new ArrayList<>();
            widget.observeChanges((source, change) -> {
                if (change.aspect() == row.gesture().aspect()) {
                    order.add("watcher/" + change.origin());
                }
            });
            scene.observeChanges((source, change) -> {
                if (source == widget && change.aspect() == row.gesture().aspect()) {
                    order.add("scene/" + change.origin());
                }
            });
            row.gesture().arm().accept(widget, () -> order.add("handler"));

            row.gesture().perform().accept(scene, widget);

            assertEquals(List.of("watcher/USER", "scene/USER", "handler"), order,
                    row.name() + ": the widget's watchers, then the scene's, then the handler, and "
                            + "the origin is the user's");
        }
    }

    @Test
    void aSecondWatcherDisturbsNeitherTheFirstNorTheHandler() {
        for (Row row : ROWS) {
            if (row.gesture() == null) {
                continue;
            }
            Widget widget = row.build().get();
            Scene scene = sceneOf(widget);
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            AtomicInteger handled = new AtomicInteger();
            widget.observeChanges((source, change) -> {
                if (change.aspect() == row.gesture().aspect()) {
                    first.incrementAndGet();
                }
            });
            widget.observeChanges((source, change) -> {
                if (change.aspect() == row.gesture().aspect()) {
                    second.incrementAndGet();
                    throw new IllegalStateException("a watcher that misbehaves");
                }
            });
            row.gesture().arm().accept(widget, handled::incrementAndGet);
            CountingCrashes crashes = CountingCrashes.install();
            try {
                row.gesture().perform().accept(scene, widget);
            } finally {
                crashes.uninstall();
            }

            assertEquals(1, first.get(), row.name());
            assertEquals(1, second.get(), row.name());
            assertEquals(1, handled.get(), row.name()
                    + ": a throwing watcher stops neither the remaining watchers nor the handler");
            assertEquals(1, crashes.observer.get(), row.name()
                    + ": and it is reported exactly once, under OBSERVER");
        }
    }

    // ------------------------------------------------------------------------------- paint

    /** No announcement of any kind is reachable from a paint: every widget, watched, painted. */
    @Test
    void nothingAnnouncesFromAPaint() {
        for (Row row : ROWS) {
            Widget widget = row.build().get();
            Scene scene = sceneOf(widget);
            List<Change> heard = new ArrayList<>();
            widget.observeChanges((source, change) -> heard.add(change));
            scene.observeChanges((source, change) -> {
                if (change.aspect() != Change.Aspect.LAYOUT) {
                    heard.add(change);
                }
            });
            CountingCrashes crashes = CountingCrashes.install();
            try {
                widget.invalidate();
                scene.renderFrame(new FakeCanvas(300, 200));
                widget.invalidate();
                scene.renderFrame(new FakeCanvas(300, 200));
            } finally {
                crashes.uninstall();
            }
            assertEquals(List.of(), heard, row.name() + ": a paint announces nothing");
            // A headless paint may throw for other reasons -- an icon with no rasterizer behind
            // it -- and those are not this test's; what may not appear is the paint rule's throw.
            assertEquals(0, crashes.announcementsFromPaints.get(), row.name()
                    + ": and never trips the paint rule, but threw " + crashes.lastFrameError);
        }
    }

    /**
     * The runtime half of the paint rule: a widget that announces from inside {@code onPaint}
     * throws, <b>with no watcher registered anywhere</b>. That is the placement decision of ADR
     * 040 §1.7 asserted as behaviour: with the check after the nobody-is-watching early return
     * this would pass silently and the guarantee would be empty.
     */
    @Test
    void anAnnouncementFromInsideAPaintThrowsWhetherOrNotAnyoneIsWatching() {
        Widget offender = new Widget() {
            private boolean flag;

            @Override
            protected Size onMeasure(Constraints c) {
                return c.constrain(40, 20);
            }

            @Override
            protected void onPaint(limn.graphics.Canvas canvas) {
                flag = !flag;
                notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
            }
        };
        Scene scene = sceneOf(offender);
        CountingCrashes crashes = CountingCrashes.install();
        try {
            scene.renderFrame(new FakeCanvas(300, 200));
        } finally {
            crashes.uninstall();
        }
        assertEquals(1, crashes.announcementsFromPaints.get(),
                "the throw escapes into the frame and is contained there, loud and reported: "
                        + crashes.lastFrameError);
    }

    /**
     * The layout case is pinned the other way round: a tab pane laid out across its overflow
     * boundary announces {@code VISIBLE} and {@code ENABLED} from inside the pass. ADR 040 §2's
     * non-guarantee is a recorded fact rather than a warning, and the day someone holds those to
     * the end of the pass (§6.6) this is what tells them they changed it.
     */
    @Test
    void aLayoutPassMayAnnounceWhatItsOwnSettersMoved() {
        TabbedPane tabs = new TabbedPane();
        for (int i = 0; i < 8; i++) {
            tabs.addTab("T" + i, new Plain()); // 20 pt of title each under the fixed ruler
        }
        Scene scene = new Scene(tabs);
        scene.setTextRuler(RULER);
        scene.layoutPass(2000, 200); // wide: nothing overflows
        List<Change.Aspect> heard = new ArrayList<>();
        scene.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VISIBLE || change.aspect() == Change.Aspect.ENABLED) {
                heard.add(change.aspect());
            }
        });

        scene.layoutPass(160, 200); // narrow: the overflow chrome appears inside the pass

        assertTrue(heard.contains(Change.Aspect.VISIBLE),
                "the overflow buttons are shown from inside onLayout, and say so: " + heard);
    }

    // ------------------------------------------------------------------------ the named sites

    /** Viewport3D announces INVOKED before it calls onClick: the one hand-written order. */
    @Test
    void theViewportAnnouncesInvokedBeforeItsClickHandler() {
        Viewport3D viewport = new Viewport3D();
        Scene scene = sceneOf(viewport);
        List<String> order = new ArrayList<>();
        viewport.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.INVOKED) {
                order.add("announced/" + change.origin());
            }
        });
        viewport.onClick(ray -> order.add("handled"));

        click(scene, viewport);

        assertEquals(List.of("announced/USER", "handled"), order);
    }

    @Test
    void aTextChangeIsAlwaysATextEdit() {
        assertThrows(IllegalArgumentException.class,
                () -> Change.of(Change.Aspect.TEXT, Change.Origin.CODE),
                "TEXT is the one aspect a watcher may narrow on, so it must never arrive as a State");
        TextField field = new TextField();
        sceneOf(field);
        List<Change> heard = new ArrayList<>();
        field.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.TEXT) {
                heard.add(change);
            }
        });
        field.setText("abc");
        assertEquals(1, heard.size());
        assertTrue(heard.get(0) instanceof Change.TextEdit, "aspect() == TEXT and instanceof agree");
        assertEquals(Change.edit(Change.Origin.CODE, 0, 0, 3), heard.get(0));
    }

    /**
     * The compound order, asserted as an order and not as a set: leaver VALUE, enterer VALUE,
     * leaver handler, enterer handler, group onSelect -- from every entry point. It is the order
     * one indivisible notifyChange per widget could not produce, and the reason the base class
     * has two halves.
     */
    @Test
    void aRadioGroupAnnouncesBothMembersBeforeEitherHandler() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        ButtonGroup group = new ButtonGroup().add(a).add(b);
        limn.scene.layout.Row row = new limn.scene.layout.Row();
        row.add(a);
        row.add(b);
        Scene scene = sceneOf(row);
        List<String> order = new ArrayList<>();
        scene.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                order.add((source == a ? "A" : "B") + "/" + change.origin());
            }
        });
        a.onChange(v -> order.add("A handled"));
        b.onChange(v -> order.add("B handled"));
        group.onSelect(index -> order.add("group " + index));

        group.setSelectedIndex(0); // A enters, from code
        assertEquals(List.of("A/CODE"), order, "from nothing: one enterer, and no handler for code");
        order.clear();

        click(scene, b); // the user moves to B
        assertEquals(List.of("A/USER", "B/USER", "A handled", "B handled", "group 1"), order);
        order.clear();

        scene.requestFocus(b);
        key(scene, Keys.LEFT); // the arrow key, which crosses the public select() and requestFocus()
        assertEquals(List.of("B/USER", "A/USER", "B handled", "A handled", "group 0"), order);
        order.clear();

        b.select(); // the public verb, from code
        assertEquals(List.of("A/CODE", "B/CODE"), order);
        order.clear();

        group.clearSelection();
        assertEquals(List.of("B/CODE"), order, "the reset a form needs: the leaver alone, from code");
    }

    /**
     * announceChange and runHandler have exactly one caller between them, the ButtonGroup seam
     * through RadioButton's two wrappers: asserted the way checkArchitecture asserts a forbidden
     * import, over the source tree. A second caller is a component that has taken the ordering
     * out of the base class, which is the failure the record is about.
     */
    @Test
    void theTwoHalvesHaveOneCallerBetweenThem() {
        Path sources = RepositoryRoot.find().resolve("limn-toolkit/src/main/java");
        Map<String, List<String>> callers = new TreeMap<>();
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(file -> file.getFileName().toString().endsWith(".java")).forEach(file -> {
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                for (String half : List.of("announceChange(", "runHandler(")) {
                    int at = -1;
                    while ((at = text.indexOf(half, at + 1)) >= 0) {
                        boolean definition = text.startsWith("void " + half, at - "void ".length());
                        boolean javadoc = text.lastIndexOf("{@link #", at) > text.lastIndexOf('\n', at);
                        if (!definition && !javadoc) {
                            callers.computeIfAbsent(sources.relativize(file).toString(), k -> new ArrayList<>())
                                    .add(half);
                        }
                    }
                }
            });
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        assertEquals(new TreeSet<>(List.of("limn/components/RadioButton.java", "limn/scene/Widget.java")),
                callers.keySet(),
                "only Widget.notifyChange and RadioButton's two package-private wrappers may call a"
                        + " half alone: " + callers);
    }

    /**
     * Every menu path still runs its item: the assertion that fails the moment activate() is read
     * as CODE. The click and the reader's press are pinned by PopupMenuAccessibilityTest and the
     * accelerator by MenuAcceleratorTest; this is the keyboard, through the in-scene surface.
     */
    @Test
    void everyMenuPathStillRunsItsItem() {
        List<String> ran = new ArrayList<>();
        Menu menu = new Menu()
                .addItem("New", () -> ran.add("new"))
                .addCheck("Wrap", false, on -> ran.add("wrap=" + on));
        Scene scene = sceneOf(new Label("root"));

        PopupMenu popup = new PopupMenu(menu);
        popup.showInSceneForTest(scene, 20, 20, 0, 0);
        scene.layoutPass(300, 200); // lays out the pushed overlay; its first item is highlighted
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(List.of("new"), ran, "Enter on a highlighted item runs it");
        assertFalse(popup.isOpen());

        popup = new PopupMenu(menu);
        popup.showInSceneForTest(scene, 20, 20, 0, 0);
        scene.layoutPass(300, 200);
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(List.of("new", "wrap=true"), ran, "and a check item flips and reports");
    }

    /**
     * Every registrar in the components hands back a Subscription or the widget, never a
     * Runnable: the handle-as-listener trap of ADR 040 §1.7 cannot be spelled.
     */
    @Test
    void noRegistrarHandsBackARunnable() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : PublicWidgets.concrete()) {
            for (Method method : type.getMethods()) {
                String name = method.getName();
                boolean registrar = name.startsWith("observe") || name.startsWith("on") && name.length() > 2
                        && Character.isUpperCase(name.charAt(2));
                if (registrar && method.getReturnType() == Runnable.class) {
                    offenders.add(type.getSimpleName() + "." + name);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }


    /** Counts what the toolkit reports, per phase, so a test can say "exactly one, under OBSERVER". */
    private static final class CountingCrashes implements CrashHandler {
        final AtomicInteger observer = new AtomicInteger();
        final AtomicInteger frame = new AtomicInteger();
        /** Frame crashes that are the paint rule's own throw, and not a headless paint's. */
        final AtomicInteger announcementsFromPaints = new AtomicInteger();
        Throwable lastFrameError;

        static CountingCrashes install() {
            CountingCrashes handler = new CountingCrashes();
            Crashes.install(handler);
            return handler;
        }

        void uninstall() {
            Crashes.uninstall(this);
        }

        @Override
        public boolean crashed(CrashPhase phase, Throwable error) {
            if (phase == CrashPhase.OBSERVER) {
                observer.incrementAndGet();
            } else if (phase == CrashPhase.FRAME) {
                frame.incrementAndGet();
                lastFrameError = error;
                if (error instanceof IllegalStateException
                        && String.valueOf(error.getMessage()).contains("may not announce")) {
                    announcementsFromPaints.incrementAndGet();
                }
            }
            return true;
        }
    }
}

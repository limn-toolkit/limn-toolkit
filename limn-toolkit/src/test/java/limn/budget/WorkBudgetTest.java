package limn.budget;

import limn.components.Button;
import limn.components.Label;
import limn.components.ListView;
import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.components.tree.Tree;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testfixtures.Work;
import limn.testing.AllocationProbe;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The performance budget, in work and not in time. A millisecond is a fact about the machine that
 * measured it, and a budget in milliseconds set on a fast machine lets through what a slow one
 * cannot bear; what is counted here (widgets painted, drawing commands, bytes allocated, keys a
 * sort reads, frames asked for) is the same number wherever it runs. Each scenario holds two
 * things: that the cost of a step does not follow the size of the content, by doing it over ten
 * thousand rows and over a hundred thousand, and that it stays under a ceiling written beside it.
 *
 * <p>A ceiling is what was counted when it was written plus room: about a seventh for a count
 * that is exact, a half for bytes, which a virtual machine's own choices move a little. A change
 * that passes one has made every frame of every application dearer, on purpose or not.
 */
class WorkBudgetTest {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;
    private static final int SMALL = 10_000;
    private static final int LARGE = 100_000;

    record Order(int number, String customer, String status, double total) {
    }

    private HeadlessUi ui;

    @BeforeEach
    void start() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void stop() {
        ui.close();
    }

    private static List<Order> orders(int count) {
        List<Order> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Order(i, "Customer " + (i * 7919L % count), i % 3 == 0 ? "Open" : "Paid", i * 1.5));
        }
        return rows;
    }

    /** One more notch of the wheel over {@code content}, framed. */
    private static Work scrolling(Widget<?> content, long[] asked) {
        Scene scene = new Scene(content);
        scene.setTextRuler(TestRulers.FIXED);
        Work.Counting canvas = new Work.Counting(WIDTH, HEIGHT);
        scene.layoutPass(WIDTH, HEIGHT);
        scene.renderFrame(canvas.canvas);
        return Work.of(scene, canvas, () -> {
            drive(scene).scrolled(0, -1, 100, 300);
            drive(scene).inputBatchEnded();
        }, WIDTH, HEIGHT, () -> asked[0]);
    }

    private static String value(long[] asked, String value) {
        asked[0]++;
        return value;
    }

    private static Work scrollingATableOf(int count) {
        long[] asked = {0};
        Table<Order> table = Table.of(
                Column.<Order>text("Number", order -> value(asked, String.valueOf(order.number()))).width(120),
                Column.<Order>text("Customer", order -> value(asked, order.customer())).width(300),
                Column.<Order>text("Status", order -> value(asked, order.status())).width(120),
                Column.<Order>text("Total", order -> value(asked, String.valueOf(order.total()))).width(120));
        table.setRows(orders(count));
        return scrolling(table, asked);
    }

    private static Work scrollingAListOf(int count) {
        List<String> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add("Item " + i);
        }
        long[] asked = {0};
        return scrolling(new ListView<String>(item -> new Label(value(asked, item))).setItems(items), asked);
    }

    @Test
    void scrollingATableCostsTheRowsOnScreenAndNotTheRowsItHolds() {
        Work small = scrollingATableOf(SMALL);
        Work large = scrollingATableOf(LARGE);
        Work.sameAtAnySize("a notch of the wheel over a table", small, large);
        Work.within("cell values a table reads as it scrolls by a row", large.asked(), 12); // 8: two rows of four cells
        Work.within("drawing commands of a table's frame as it scrolls", large.commands(), 110); // 96 counted
        Work.within("bytes a table's frame allocates as it scrolls", large.allocated(), 43_000); // 28 720
    }

    @Test
    void scrollingAListCostsTheRowsOnScreenAndNotTheRowsItHolds() {
        Work small = scrollingAListOf(SMALL);
        Work large = scrollingAListOf(LARGE);
        Work.sameAtAnySize("a notch of the wheel over a list", small, large);
        Work.within("row widgets a list asks for as it scrolls by a row", large.asked(), 6); // 4
        Work.within("widgets a list's frame paints as it scrolls", large.painted(), 60); // 52
        Work.within("drawing commands of a list's frame as it scrolls", large.commands(), 60); // 51
        Work.within("bytes a list's frame allocates as it scrolls", large.allocated(), 64_000); // 42 496
    }

    /** A tree of {@code count} branches of twenty leaves each, with its third branch opened and closed. */
    private static Work togglingABranchAmong(int count) {
        long[] asked = {0};
        List<Integer> roots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            roots.add(i);
        }
        Tree<Integer> tree = new Tree<>(new Tree.Model<Integer>() {
            @Override
            public List<Integer> roots() {
                return roots;
            }

            @Override
            public List<Integer> children(Integer node) {
                asked[0]++;
                if (node < 0) {
                    return List.of();
                }
                List<Integer> leaves = new ArrayList<>(20);
                for (int leaf = 1; leaf <= 20; leaf++) {
                    leaves.add(-(node * 20 + leaf));
                }
                return leaves;
            }

            @Override
            public boolean isLeaf(Integer node) {
                asked[0]++;
                return node < 0;
            }

            @Override
            public Widget<?> cellFor(Integer node) {
                asked[0]++;
                return new Label("Node " + node);
            }
        });
        Scene scene = new Scene(tree);
        scene.setTextRuler(TestRulers.FIXED);
        Work.Counting canvas = new Work.Counting(WIDTH, HEIGHT);
        scene.layoutPass(WIDTH, HEIGHT);
        scene.renderFrame(canvas.canvas);
        // Opened, framed and closed in one step: measured apart, the least of several runs
        // would be the closing every time, and an opening that walked the whole tree would pass.
        return Work.of(scene, canvas, () -> {
            tree.expand(3);
            scene.layoutPass(WIDTH, HEIGHT);
            scene.renderFrame(canvas.canvas);
            tree.collapse(3);
        }, WIDTH, HEIGHT, () -> asked[0]);
    }

    @Test
    void openingAndClosingABranchCostsTheBranchAndNotTheTreeAroundIt() {
        Work small = togglingABranchAmong(SMALL);
        Work large = togglingABranchAmong(LARGE);
        Work.sameAtAnySize("opening and closing a branch", small, large);
        Work.within("times the model is asked as a branch opens and closes", large.asked(), 80); // 67
        Work.within("drawing commands of a tree's frames as a branch opens and closes", large.commands(), 210); // 182, two frames
        Work.within("bytes a tree allocates as a branch opens and closes", large.allocated(), 410_000); // 273 168
    }

    /** How many times a sort over {@code count} rows read a row's key, and how many more the frames after it did. */
    private static long[] keysReadSorting(int count) {
        long[] read = {0};
        Table<Order> table = Table.of(Column.<Order>text("Customer", Order::customer).width(300)
                .comparator(Comparator.comparing((Order order) -> {
                    read[0]++;
                    return order.customer();
                })));
        table.setRows(orders(count));
        Scene scene = new Scene(table);
        scene.setTextRuler(TestRulers.FIXED);
        Work.Counting canvas = new Work.Counting(WIDTH, HEIGHT);
        scene.layoutPass(WIDTH, HEIGHT);
        scene.renderFrame(canvas.canvas);
        read[0] = 0;
        table.setSort(table.columns().get(0), SortOrder.ASCENDING);
        scene.layoutPass(WIDTH, HEIGHT);
        scene.renderFrame(canvas.canvas);
        long sorting = read[0];
        for (int frame = 0; frame < 10; frame++) {
            drive(scene).scrolled(0, -1, 100, 300);
            drive(scene).inputBatchEnded();
            scene.layoutPass(WIDTH, HEIGHT);
            scene.renderFrame(canvas.canvas);
        }
        return new long[] {sorting, read[0] - sorting};
    }

    @Test
    void aSortComparesAsASortMustAndIsDoneOnce() {
        for (int count : new int[] {SMALL, LARGE}) {
            long[] read = keysReadSorting(count);
            // A comparison reads two keys, and a merge sort makes at most n log2 n of them: 1.82
            // keys per row per doubling were counted, of the 2 that is the worst there is.
            long ceiling = (long) (2.2 * count * (Math.log(count) / Math.log(2)));
            Work.within("keys read to sort " + count + " rows", read[0], ceiling);
            assertEquals(0, read[1], "a table that is sorted is not sorted again by the frames that follow");
        }
    }

    /** Ten frames of 100 ms. */
    private static limn.graphics.Image tenASecond() {
        List<limn.graphics.Image> frames = new ArrayList<>();
        int[] millis = new int[10];
        for (int f = 0; f < 10; f++) {
            frames.add(new limn.graphics.Image(16, 16, new byte[16 * 16 * 4]));
            millis[f] = 100;
        }
        return limn.graphics.Images.animation(frames, millis, 0);
    }

    /**
     * A second of a column holding {@code shown}, after its first frames: how many frames its
     * window asked for, and how many widgets the last of them painted.
     */
    private long[] aSecondOf(Widget<?> shown, Widget<?> beside) {
        ui.close();
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        ui = new HeadlessUi(nanos::get);
        HeadlessWindow window = new HeadlessBackend(ui.runtime()).open("Icons", WIDTH, HEIGHT);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(shown);
        column.add(beside);
        column.add(new Label("Beside them"));
        Scene scene = new Scene(column, nanos::get);
        scene.setTextRuler(TestRulers.FIXED);
        scene.bind(window);
        window.frame();
        window.frame();
        window.takeFrameRequests();
        for (int step = 0; step < 100; step++) {
            nanos.addAndGet(10_000_000L);
            ui.runtime().drain();
            window.frame();
        }
        return new long[] {window.takeFrameRequests(), (long) scene.metrics().paintedWidgets().last()};
    }

    @Test
    void anAnimatedIconAsksForItsOwnFramesAndNoMore() {
        limn.graphics.Icon spinning = limn.graphics.BitmapIcon.picture(tenASecond());
        limn.graphics.Icon still = limn.graphics.BitmapIcon.picture(tenASecond().frame(3));
        long[] moving = aSecondOf(new Button("Saving").setIcon(spinning), new Button("Still").setIcon(still));
        assertEquals(10, moving[0], "ten frames of the icon a second are ten of its window");
        // 4 counted, which is what invalidating the button by hand paints too: the frame of an
        // icon costs a repaint of its widget and not of what is beside it.
        Work.within("widgets a frame of an animated icon paints", moving[1], 4);
        long[] resting = aSecondOf(new Button("Still").setIcon(still), new Label("Nothing moves"));
        assertEquals(0, resting[0], "a still icon asks for no frame");
        long[] unseen = aSecondOf(new Button("Saving").setIcon(spinning).setVisible(false), new Label("Shown"));
        assertEquals(0, unseen[0], "an icon nobody sees asks for no frame");
    }

    @Test
    void anAnimatedSvgAsksForItsOwnFramesAndNoMore() {
        limn.graphics.SvgRenderer renderer = limn.testfixtures.FakeSvg.renderer(
                (svg, side) -> new limn.graphics.Image(side, side, new byte[side * side * 4]));
        limn.graphics.SvgIcon.installRenderer(renderer);
        try {
            limn.graphics.Icon spinner = limn.graphics.SvgIcon.of("<svg xmlns='http://www.w3.org/2000/svg' "
                    + "viewBox='0 0 24 24'><circle cx='12' cy='12' r='4'><animate attributeName='r' values='4;8;4' "
                    + "dur='1s' repeatCount='indefinite'/></circle></svg>").withFrameRate(10);
            long[] moving = aSecondOf(new Button("Saving").setIcon(spinner), new Label("Beside"));
            assertEquals(10, moving[0], "an SVG played at ten frames a second asks for ten of its window");
        } finally {
            limn.graphics.SvgIcon.uninstallRenderer(renderer);
        }
    }

    /**
     * What {@code act} costs in frames of a window holding {@code root}: the frames asked for over
     * the second after it, in ten-millisecond steps, and over the second after that.
     */
    private long[] framesAfter(Widget<?> root, Runnable act) {
        ui.close();
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        ui = new HeadlessUi(nanos::get);
        HeadlessWindow window = new HeadlessBackend(ui.runtime()).open("Motion", WIDTH, HEIGHT);
        Scene scene = new Scene(root, nanos::get);
        scene.setTextRuler(TestRulers.FIXED);
        scene.bind(window);
        window.frame();
        window.frame();
        window.takeFrameRequests();
        act.run();
        long[] asked = new long[2];
        for (int second = 0; second < 2; second++) {
            for (int step = 0; step < 100; step++) {
                nanos.addAndGet(10_000_000L);
                ui.runtime().drain();
                window.frame();
            }
            asked[second] = window.takeFrameRequests();
        }
        return asked;
    }

    @Test
    void aSectionOpeningAsksForTheFramesOfItsSlideAndThenNone() {
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        limn.components.Expander section = new limn.components.Expander("Details", new Label("Inside"));
        column.add(section);
        column.add(new Label("Below"));
        long[] asked = framesAfter(column, () -> section.setExpanded(true));
        // 46 counted: the slide's 0.2 s is twenty frames of ten milliseconds, and each asks
        // twice, its ticker and its layout, the rate a button's focus fading in asks at too (31
        // for its fourteen frames).
        Work.within("frames a section opening asks for", asked[0], 53);
        assertEquals(0, asked[1], "an open section at rest asks for no frame");
    }

    @Test
    void aTabMovingAsksForTheFramesOfItsSlideAndThenNone() {
        limn.components.TabbedPane tabs = new limn.components.TabbedPane();
        for (String title : List.of("Alpha", "Beta", "Gamma")) {
            tabs.addTab(title, new Label(title));
        }
        long[] asked = framesAfter(tabs, () -> tabs.moveTab(0, 2));
        // 70 counted: the tabs slide home in about a quarter of a second while the indicator
        // slides beside them, each frame asking for the two and for the strip's own placing.
        Work.within("frames a tab moving asks for", asked[0], 80);
        assertEquals(0, asked[1], "tabs at rest ask for no frame");
    }

    /**
     * A group drawn as one picture (ADR 071) costs one command, its composite, whatever it holds:
     * the rows inside are drawn as they are at an opacity of 1, into the layer instead of the
     * window, and nothing is drawn per row for the fade.
     */
    @Test
    void aFadingGroupCostsOneCommandWhateverItHolds() {
        for (int rows : new int[] {10, 40}) {
            Work opaque = aGroupOf(rows, 1f);
            Work fading = aGroupOf(rows, 0.5f);
            assertEquals(opaque.commands() + 1, fading.commands(),
                    "a group of " + rows + " rows at one half: its layer, and the rows as they were");
            assertEquals(opaque.painted(), fading.painted(), "the same widgets painted");
        }
    }

    private static Work aGroupOf(int rows, float opacity) {
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        for (int i = 0; i < rows; i++) {
            column.add(new Label("Row " + i));
        }
        column.setOpacity(opacity);
        Scene scene = new Scene(column);
        scene.setTextRuler(TestRulers.FIXED);
        Work.Counting canvas = new Work.Counting(WIDTH, HEIGHT);
        scene.layoutPass(WIDTH, HEIGHT);
        scene.renderFrame(canvas.canvas);
        return Work.of(scene, canvas, scene::requestRender, WIDTH, HEIGHT, () -> 0);
    }

    @Test
    void aScreenNobodyTouchesAsksForNoFrame() {
        HeadlessWindow window = new HeadlessBackend(ui.runtime()).open("At rest", WIDTH, HEIGHT);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(new Label("At rest"));
        column.add(new Button("Does nothing"));
        column.add(new ListView<String>(Label::new).setItems(List.of("One", "Two", "Three")));
        Scene scene = new Scene(column);
        scene.setTextRuler(TestRulers.FIXED);
        scene.bind(window);
        window.frame();
        window.frame();
        window.takeFrameRequests();
        for (int frame = 0; frame < 30; frame++) {
            window.frame();
        }
        assertEquals(0, window.takeFrameRequests(),
                "a frame that changed nothing asks for no other: a screen at rest costs no processor");
        if (AllocationProbe.isSupported()) {
            Work.within("bytes allocated by a frame that had nothing to do",
                    AllocationProbe.leastAllocatedBy(window::frame, 12), 1024); // 208
        }
    }
}

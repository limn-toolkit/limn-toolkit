package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.LayoutOverflow;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Grid;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;
import limn.scene.layout.Wrap;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a layout declares its children paint past it, under partial rendering.
 *
 * <p>A repaint pass skips any subtree whose box, grown by what the subtree's root declares it
 * paints outside itself and a pixel of antialiasing, misses the pass. A button's focus ring is
 * drawn outside the button, so it is drawn outside the row holding the button whenever the button
 * sits at an edge of the row. A row that declared nothing was skipped by a pass landing only in
 * the band where that ring hangs past it (a neighbour of the row repainting), the button under it
 * was never painted, and the pass wiped the ring's ink in that band and drew nothing back.
 *
 * <p>Every ring box here is read off the rectangle the button actually stroked, so a retune of the
 * ring is measured rather than assumed.
 */
class ContainerOverflowTest extends ComponentTestBase {

    private static final int W = 400;
    private static final int H = 200;
    /** Room round the layout under test, so that whatever hangs past it is inside the window. */
    private static final float INSET = 40;
    /** The pixel of analytic antialiasing any shape may feather past its own geometry. */
    private static final float FEATHER = 1;
    /**
     * Between the row and its neighbour: wider than the pixel of antialiasing each box carries on
     * its side, so the neighbour's repaint misses the row's box, and narrower than the ring's
     * reach, so it lands where the ring hangs past the row.
     */
    private static final float GAP = 3;

    private Scene scene;
    private AtomicLong nanos;
    private Recorder canvas;

    private void mount(Widget<?> root) {
        nanos = new AtomicLong();
        scene = new Scene(root, nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        canvas = new Recorder(W, H);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 300; i++) {
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                return;
            }
        }
        throw new AssertionError("never settled");
    }

    private void frame() {
        nanos.addAndGet(16_000_000L);
        canvas.reset();
        scene.renderFrame(canvas);
    }

    /**
     * The box the button's focus ring covers, antialiased edge included, read off a frame that
     * repaints the whole scene, so that no culling decides whether the ring is measured.
     */
    private Rect ringBox() {
        scene.root().invalidate();
        frame();
        assertTrue(canvas.cleared || !canvas.passes.isEmpty(), "the frame painted nothing");
        Rect box = null;
        for (Rect ring : canvas.rings) {
            Rect inked = ring.inflate(Strokes.FOCUS_RING / 2 + FEATHER);
            box = box == null ? inked : box.union(inked);
        }
        assertNotNull(box, "the focused button drew no focus ring");
        settle();
        return box;
    }

    private static Rect sceneBox(Widget<?> w) {
        return new Rect(w.localToSceneX(), w.localToSceneY(), w.width(), w.height());
    }

    private static boolean meets(Rect a, Rect b) {
        Rect both = a.intersect(b);
        return both.width() > 0 && both.height() > 0;
    }

    /**
     * Focuses {@code button}, repaints only what {@code repaint} damages, and holds that the pass
     * missed {@code layout}'s own box, met the ring, and drew the ring again.
     */
    private void assertTheRingSurvivesARepaintBeside(Widget<?> layout, Button button,
                                                     Runnable repaint) {
        button.requestFocus();
        settle();
        Rect ring = ringBox();
        Rect box = sceneBox(layout);
        assertFalse(box.contains(ring.x(), ring.y()) && box.contains(ring.right(), ring.bottom()),
                "the fixture puts the ring across an edge of the layout, or it proves nothing");

        repaint.run();
        frame();
        assertFalse(canvas.cleared, "a whole frame paints everything and tests no culling");
        assertFalse(canvas.passes.isEmpty(), "the repaint produced no pass");
        boolean metTheRing = false;
        for (Rect pass : canvas.passes) {
            assertFalse(meets(pass, box.inflate(FEATHER)), "the pass " + pass + " reaches the "
                    + "layout's own box " + box + ", so it paints the layout for that reason");
            metTheRing |= meets(pass, ring);
        }
        assertTrue(metTheRing, "no pass of " + canvas.passes + " meets the ring " + ring);
        assertFalse(canvas.rings.isEmpty(), "a pass over " + canvas.passes + " wiped the part of "
                + "the ring " + ring + " inside it and did not draw it again");
    }

    // ------------------------------------------------------------------ the defect

    /**
     * A focused button at the trailing edge of a row, and a neighbour of the row whose repaint
     * lands only in the band where the ring hangs past the row. The row has to be painted, or the
     * ring's ink there is lost.
     */
    @Test
    void aNeighbourRepaintingBesideARowRedrawsTheRingOfTheButtonAtItsEdge() {
        Button button = new Button("Save");
        Row row = new Row();
        row.add(button);
        Swatch neighbour = new Swatch();
        Row line = new Row().gap(GAP);
        line.add(row);
        line.add(neighbour);
        mount(new Padding(Insets.all(INSET), line));

        assertTheRingSurvivesARepaintBeside(row, button, neighbour::invalidate);
    }

    /**
     * The same reading right to left, where the row's first child is at its right edge and the
     * neighbour sits to the row's left: the reach is the same on every side, so the direction
     * moves the band and not the rule.
     */
    @Test
    void aNeighbourRepaintingBesideARowRedrawsTheRingReadingRightToLeft() {
        Button button = new Button("Save");
        Row row = new Row();
        row.add(button);
        Swatch neighbour = new Swatch();
        Row line = new Row().gap(GAP);
        line.add(row);
        line.add(neighbour);
        Padding root = new Padding(Insets.all(INSET), line);
        root.setLayoutDirection(LayoutDirection.RTL);
        mount(root);
        assertTrue(neighbour.localToSceneX() < row.localToSceneX(),
                "reading right to left the neighbour is on the row's left");

        assertTheRingSurvivesARepaintBeside(row, button, neighbour::invalidate);
    }

    /**
     * A band on each side of a row holding a focused button: above it, below it, before it and
     * after it, each two points out, which is past the row's pixel of antialiasing and inside the
     * ring's three points.
     */
    @Test
    void aPassInTheBandOnAnySideOfARowRedrawsTheRing() {
        for (int side = 0; side < 4; side++) {
            Button button = new Button("Save");
            Row row = new Row();
            row.add(button);
            // A stack, so that the row is as big as the button and no bigger: a padding would
            // stretch it over the whole window and the button would touch only two of its edges.
            Stack centre = new Stack().alignment(Stack.Alignment.CENTER);
            centre.add(row);
            mount(new Padding(Insets.all(INSET), centre));
            final int which = side;
            assertTheRingSurvivesARepaintBeside(row, button, () -> {
                Rect b = sceneBox(row);
                Rect band = switch (which) {
                    case 0 -> new Rect(b.x() + 4, b.y() - 3, 8, 1);
                    case 1 -> new Rect(b.x() + 4, b.bottom() + 2, 8, 1);
                    case 2 -> new Rect(b.x() - 3, b.y() + 4, 1, 8);
                    default -> new Rect(b.right() + 2, b.y() + 4, 1, 8);
                };
                scene.damage(band);
            });
        }
    }

    // ------------------------------------------------------------------ the declaration

    /**
     * One layout of each kind, with the two calls every one of them answers to; each call's result
     * is handed back so that the test can hold that it is the layout itself.
     */
    private record Case(String name, Widget<?> layout, Function<Float, Object> set,
                        Supplier<Object> fromTheme) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Every layout and wrapper the toolkit ships, each holding one child flush with its box. */
    private static List<Case> cases() {
        List<Case> all = new ArrayList<>();
        Row row = new Row();
        row.add(new Swatch());
        all.add(new Case("Row", row, row::paintOverflow, row::paintOverflowFromTheme));
        Column column = new Column();
        column.add(new Swatch());
        all.add(new Case("Column", column, column::paintOverflow, column::paintOverflowFromTheme));
        Stack stack = new Stack();
        stack.add(new Swatch());
        all.add(new Case("Stack", stack, stack::paintOverflow, stack::paintOverflowFromTheme));
        Wrap wrap = new Wrap();
        wrap.add(new Swatch());
        all.add(new Case("Wrap", wrap, wrap::paintOverflow, wrap::paintOverflowFromTheme));
        Grid grid = new Grid(2);
        grid.add(new Swatch());
        all.add(new Case("Grid", grid, grid::paintOverflow, grid::paintOverflowFromTheme));
        Padding padding = new Padding(Insets.NONE, new Swatch());
        all.add(new Case("Padding", padding, padding::paintOverflow,
                padding::paintOverflowFromTheme));
        Expanded expanded = Expanded.of(new Swatch());
        all.add(new Case("Expanded", expanded, expanded::paintOverflow,
                expanded::paintOverflowFromTheme));
        SizedBox sized = new SizedBox(SizedBox.UNSET, SizedBox.UNSET, new Swatch());
        all.add(new Case("SizedBox", sized, sized::paintOverflow, sized::paintOverflowFromTheme));
        TokenBox token = new TokenBox(null, null, new Swatch());
        all.add(new Case("TokenBox", token, token::paintOverflow, token::paintOverflowFromTheme));
        TokenRow tokenRow = new TokenRow();
        tokenRow.add(new Swatch());
        all.add(new Case("TokenRow", tokenRow, tokenRow::paintOverflow,
                tokenRow::paintOverflowFromTheme));
        TokenColumn tokenColumn = new TokenColumn();
        tokenColumn.add(new Swatch());
        all.add(new Case("TokenColumn", tokenColumn, tokenColumn::paintOverflow,
                tokenColumn::paintOverflowFromTheme));
        return all;
    }

    /**
     * What the scene reads, by reflection because the hook is protected on {@link Widget}: the
     * same call the damage and the culling make, dispatched to whatever the layout overrides.
     */
    private static float declared(Widget<?> widget) {
        try {
            java.lang.reflect.Method hook = Widget.class.getDeclaredMethod("paintOutset");
            hook.setAccessible(true);
            return (Float) hook.invoke(widget);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("paintOutset", e);
        }
    }

    @Test
    void everyLayoutAndWrapperDeclaresTheReachOfTheThemesFocusRing() {
        for (Case c : cases()) {
            assertEquals(Theme.of(c.layout()).focusRingReach(), declared(c.layout()), 0, c.name());
            assertEquals(Strokes.FOCUS_RING_OUTSET, declared(c.layout()), 0, c.name());
        }
    }

    /**
     * The number is the theme's, and it has to cover every focus ring a control of the toolkit
     * draws outside its box, or a layout declaring it would still lose part of one. The same for
     * every palette, a built one included, and the same as what a layout declares before the
     * theme has loaded.
     */
    @Test
    void theThemesReachCoversEveryFocusRingAControlDrawsOutsideItsBox() {
        java.time.Clock day = java.time.Clock.fixed(java.time.Instant.parse("2026-09-27T12:00:00Z"),
                java.time.ZoneOffset.UTC);
        List<Widget<?>> ringed = List.of(new Button("b"), new Checkbox("c"), new RadioButton("r"),
                new SegmentedControl(List.of("a", "b")), new ColorPickerButton(),
                new limn.components.date.DateField().setClock(day),
                new limn.components.date.CalendarView().setClock(day),
                new limn.components.date.DatePicker().setClock(day));
        float reach = Theme.current().focusRingReach();
        for (Widget<?> control : ringed) {
            assertTrue(declared(control) <= reach, control.getClass().getSimpleName()
                    + " draws its ring " + declared(control) + " out, past the theme's " + reach);
        }
        for (Theme palette : Theme.builtins()) {
            assertEquals(reach, palette.focusRingReach(), 0, palette.name());
        }
        assertEquals(reach, Theme.builder("Mine", true).build().focusRingReach(), 0);
        assertEquals(LayoutOverflow.BEFORE_THEME, reach, 0,
                "a layout declares the same before the theme loads as after");
    }

    @Test
    void paintOverflowReplacesTheThemesNumberAndComesBackToIt() {
        for (Case c : cases()) {
            assertSame(c.layout(), c.set().apply(8f), c.name() + " chains");
            assertEquals(8, declared(c.layout()), 0, c.name());
            c.set().apply(0f);
            assertEquals(0, declared(c.layout()), 0, c.name() + " may say nothing leaves it");
            assertSame(c.layout(), c.fromTheme().get(), c.name() + " chains");
            assertEquals(Theme.current().focusRingReach(), declared(c.layout()), 0, c.name());
        }
    }

    /**
     * NaN, a negative number and an infinity are refused where they are given, and a refused one
     * changes nothing: NaN would grow every damage rectangle by NaN, and an infinity would make
     * every pass paint the layout.
     */
    @Test
    void paintOverflowRefusesWhatIsNotADistance() {
        for (Case c : cases()) {
            c.set().apply(8f);
            for (float bad : new float[] {Float.NaN, -1, -0.5f, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY}) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> c.set().apply(bad), c.name() + " took " + bad);
                assertTrue(error.getMessage().contains("paint overflow"), error.getMessage());
                assertEquals(8, declared(c.layout()), 0, c.name() + " kept its number");
            }
        }
    }

    /** Both calls check the UI thread before anything is stored. */
    @Test
    void paintOverflowOffTheUiThreadThrowsAndChangesNothing()
            throws ExecutionException, InterruptedException {
        for (Case c : cases()) {
            IllegalStateException error = ui.workers().submit(() ->
                    assertThrows(IllegalStateException.class, () -> c.set().apply(8f))).get();
            assertTrue(error.getMessage().contains("UI thread"), error.getMessage());
            assertEquals(Strokes.FOCUS_RING_OUTSET, declared(c.layout()), 0, c.name());
            c.set().apply(8f);
            ui.workers().submit(() ->
                    assertThrows(IllegalStateException.class, () -> c.fromTheme().get())).get();
            assertEquals(8, declared(c.layout()), 0, c.name());
        }
    }

    /**
     * A padding declares what is left of the reach past its narrowest inset: a ring that fits in
     * the insets reaches nothing past the padding, so a padded panel adds nothing to its damage.
     * A number set on it is taken as given, and a token padding follows its insets as they are
     * resolved.
     */
    @Test
    void aPaddingDeclaresWhatIsLeftOfTheReachPastItsNarrowestInset() {
        assertEquals(3, declared(new Padding(Insets.NONE, new Swatch())), 0);
        assertEquals(2, declared(new Padding(new Insets(1, 10, 10, 10), new Swatch())), 0);
        assertEquals(1, declared(new Padding(Insets.symmetric(12, 2), new Swatch())), 0);
        assertEquals(0, declared(new Padding(Insets.all(3), new Swatch())), 0);
        assertEquals(0, declared(new Padding(Insets.all(12), new Swatch())), 0);
        assertEquals(7, declared(new Padding(Insets.all(12), new Swatch()).paintOverflow(7)), 0);

        TokenPadding page = new TokenPadding(new Swatch());
        assertEquals(3, declared(page), 0, "before its first measure it holds no insets");
        page.measure(Constraints.loose(200, 200));
        assertTrue(page.insets().left() >= 3, "the page padding is wider than the ring");
        assertEquals(0, declared(page), 0);
    }

    /**
     * The components that lay out children flush with their own box and do not clip declare the
     * reach too: the region a context menu wraps round an application's control, and the transport
     * bar without its backdrop, whose play button is then flush with its leading edge. With the
     * backdrop the padding round the row is wider than the ring at every step.
     */
    @Test
    void aComponentHoldingControlsFlushWithItsEdgeDeclaresTheReach() {
        Widget<?> region = ContextMenus.attach(new Button("Save"), () -> null);
        assertEquals(Strokes.FOCUS_RING_OUTSET, declared(region), 0);

        MediaControls bare = new MediaControls(new VideoView()).setBackdrop(false);
        assertEquals(Strokes.FOCUS_RING_OUTSET, declared(bare), 0);
        for (limn.scene.ControlSize step : limn.scene.ControlSize.values()) {
            MediaControls panel = new MediaControls(new VideoView()).setBackdrop(true);
            panel.setControlSize(step);
            assertEquals(0, declared(panel), 0, step.name());
        }
    }

    /** A spacer has no child to paint past it, so it declares nothing unless it is told to. */
    @Test
    void aSpacerDeclaresNothing() {
        assertEquals(0, declared(Expanded.spacer(1)), 0);
        assertEquals(0, declared(new SizedBox(10, 10)), 0);
        assertEquals(5, declared(new SizedBox(10, 10).paintOverflow(5)), 0);
    }

    /**
     * A widget that clips its children declares nothing for them, because its clip bounds them: a
     * scroll view scrolling both ways, and a container of an application's own that clips, unless
     * it is told a number. And the clip does bound them: a row flush with such a view's viewport,
     * repainting, damages nothing past the view. A view scrolling one way lets its content past
     * the viewport across the other and declares that band, which {@code ScrollAxisClipTest}
     * holds.
     */
    @Test
    void aWidgetThatClipsItsChildrenDeclaresNothingForThem() {
        Row clipping = new Row() {
            @Override
            protected boolean clipsChildren() {
                return true;
            }
        };
        assertEquals(0, declared(clipping), 0);
        assertEquals(4, declared(clipping.paintOverflow(4)), 0, "a number set still counts");

        Row content = new Row();
        content.add(new Button("Save"));
        ScrollView view = new ScrollView(content, true, true);
        assertEquals(0, declared(view), 0);
        Stack centre = new Stack().alignment(Stack.Alignment.CENTER);
        centre.add(new SizedBox(200, 60, view));
        mount(new Padding(Insets.all(INSET), centre));
        content.invalidate();
        frame();
        Rect bounds = sceneBox(view).inflate(FEATHER);
        assertFalse(canvas.passes.isEmpty(), "the row's repaint produced no pass");
        for (Rect pass : canvas.passes) {
            assertEquals(pass, pass.intersect(bounds), "the view's clip bounds the row's reach");
        }
    }

    /**
     * What the declaration costs: a container's own repaint covers its box and the band it
     * declares, with the pixel of antialiasing every box carries, and not a point more.
     */
    @Test
    void aContainersRepaintCoversItsBoxAndTheBandItDeclares() {
        Row row = new Row();
        row.add(new Button("Save"));
        Stack centre = new Stack().alignment(Stack.Alignment.CENTER);
        centre.add(row);
        mount(new Padding(Insets.all(INSET), centre));

        row.invalidate();
        frame();
        assertEquals(List.of(sceneBox(row).inflate(FEATHER + Strokes.FOCUS_RING_OUTSET)),
                canvas.passes);

        row.paintOverflow(0);
        settle();
        row.invalidate();
        frame();
        assertEquals(List.of(sceneBox(row).inflate(FEATHER)), canvas.passes,
                "a row told its children stay inside repaints its box alone");
    }

    /**
     * A declaration already in force repaints nothing, and a new one repaints the layout and the
     * band it now declares, so that whatever a child draws there is drawn at once.
     */
    @Test
    void aNewDeclarationRepaintsTheLayoutAndOneInForceRepaintsNothing() {
        Row row = new Row();
        row.add(new Button("Save"));
        Stack centre = new Stack().alignment(Stack.Alignment.CENTER);
        centre.add(row);
        mount(new Padding(Insets.all(INSET), centre));

        row.paintOverflowFromTheme();
        frame();
        assertTrue(canvas.nothingPainted(), "the theme's number was already in force");

        row.paintOverflow(8);
        frame();
        assertEquals(List.of(sceneBox(row).inflate(FEATHER + 8)), canvas.passes);
        settle();

        row.paintOverflow(8);
        frame();
        assertTrue(canvas.nothingPainted(), "8 was already in force");

        row.paintOverflowFromTheme();
        frame();
        assertFalse(canvas.passes.isEmpty(), "going back to the theme is a new declaration");
    }

    // ------------------------------------------------------------------ doubles

    /** A leaf that fills its box: the neighbour, repainted on demand. */
    private static final class Swatch extends Widget<Swatch> {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(20, 20);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), Color.rgb(0x336699));
        }
    }

    /**
     * Records the scene's own repaint passes (the clips pushed at the first save of a frame, where
     * the scene applies damage) and every rectangle stroked with the focus ring's pen, in scene
     * coordinates by following the translations the paint walk pushes.
     */
    private static final class Recorder extends FakeCanvas {

        final List<Rect> passes = new ArrayList<>();
        final List<Rect> rings = new ArrayList<>();
        boolean cleared;
        private int paints;
        private final Deque<float[]> saved = new ArrayDeque<>();
        private float tx;
        private float ty;

        Recorder(float width, float height) {
            super(width, height);
        }

        void reset() {
            passes.clear();
            rings.clear();
            cleared = false;
            paints = 0;
        }

        boolean nothingPainted() {
            return !cleared && passes.isEmpty() && paints == 0;
        }

        @Override
        public void clear(Color color) {
            cleared = true;
        }

        @Override
        public void save() {
            super.save();
            saved.push(new float[] {tx, ty});
        }

        @Override
        public void restore() {
            if (!saved.isEmpty()) {
                float[] outer = saved.pop();
                tx = outer[0];
                ty = outer[1];
            }
            super.restore();
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > count && !saved.isEmpty()) {
                restore();
            }
            super.restoreToCount(count);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            if (saveCount() == 1) {
                passes.add(new Rect(tx + x, ty + y, w, h));
            }
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            paints++;
        }

        @Override
        public void fillRoundRect(RoundRect r, Paint paint) {
            paints++;
        }

        @Override
        public void drawRoundRect(RoundRect r, float strokeWidth, Paint paint) {
            paints++;
            if (strokeWidth == Strokes.FOCUS_RING) {
                rings.add(new Rect(tx + r.x(), ty + r.y(), r.width(), r.height()));
            }
        }
    }
}

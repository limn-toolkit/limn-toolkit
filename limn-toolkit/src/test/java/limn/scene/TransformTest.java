package limn.scene;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.Rect;
import limn.i18n.I18nString;
import limn.math.Mat4;
import limn.math.Vec4;
import limn.scene.internal.TransformAccess;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Opacity;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;
import limn.scene.layout.Transform;
import limn.testing.AccessibleHarness;
import limn.testing.NoopCanvas;
import limn.testing.TestPreferences;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link Transform} container, stage 1 of the design of transforms (ADR 072 §2, §6): a
 * translation, a scale and an origin, drawn straight into the canvas, the three motions, what a
 * change damages and what a watcher hears, where the pointer and a reader find the child, and the
 * right-to-left rules. The pixels are the backend's ({@code TransformPassSeamTest}); this holds the
 * container's side against a canvas that keeps where each fill lands.
 */
class TransformTest extends SceneTestBase {

    private static final Change TRANSFORM = Change.of(Change.Aspect.TRANSFORM, Change.Origin.CODE);
    private static final float EPS = 1e-3f;

    /**
     * Keeps where every fill lands on the device, through the translations and scales the paint
     * applies, each pass's rectangle, and each layer it is asked to open, on the device too.
     */
    static final class TrackingCanvas extends NoopCanvas {
        final List<Rect> passes = new ArrayList<>();
        final List<Rect> fills = new ArrayList<>();
        final List<Rect> layers = new ArrayList<>();
        final List<String> painted = new ArrayList<>();
        int clears;
        /** The current affine, x' = sx·x + tx and y' = sy·y + ty, and the saved ones. */
        private float tx;
        private float ty;
        private float sx = 1;
        private float sy = 1;
        private final List<float[]> saved = new ArrayList<>();

        TrackingCanvas() {
            super(400, 300);
        }

        void reset() {
            passes.clear();
            fills.clear();
            layers.clear();
            painted.clear();
            clears = 0;
        }

        Rect device(float x, float y, float w, float h) {
            float x0 = sx * x + tx;
            float x1 = sx * (x + w) + tx;
            float y0 = sy * y + ty;
            float y1 = sy * (y + h) + ty;
            return new Rect(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0));
        }

        @Override
        public void clear(Color color) {
            clears++;
        }

        @Override
        public void clearRect(float x, float y, float w, float h, Color color) {
            // A pass's own clear, which is not a widget's fill.
        }

        @Override
        public void save() {
            saved.add(new float[] {tx, ty, sx, sy});
            super.save();
        }

        @Override
        public void restore() {
            if (!saved.isEmpty()) {
                float[] s = saved.remove(saved.size() - 1);
                tx = s[0];
                ty = s[1];
                sx = s[2];
                sy = s[3];
            }
            super.restore();
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > count && saveCount() > 0) {
                restore();
            }
        }

        @Override
        public void saveLayer(float x, float y, float w, float h, float opacity) {
            layers.add(device(x, y, w, h));
            save();
        }

        @Override
        public void translate(float dx, float dy) {
            tx += sx * dx;
            ty += sy * dy;
        }

        @Override
        public void scale(float fx, float fy) {
            sx *= fx;
            sy *= fy;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            if (saveCount() == 1) {
                passes.add(new Rect(x, y, w, h)); // the scene clips each pass at the first save
            }
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            fills.add(device(x, y, w, h));
        }
    }

    /** A focusable leaf that fills its box, says when it is painted and laid out, and has a name. */
    static class Leaf extends Widget<Leaf> {
        final String name;
        final List<String> painted;
        int layouts;
        int presses;
        int described;

        Leaf(String name, List<String> painted) {
            this.name = name;
            this.painted = painted;
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(40, 20);
        }

        @Override
        protected void onLayout() {
            layouts++;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            painted.add(name);
            canvas.fillRect(0, 0, width(), height(), Color.BLACK);
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            if (event.type() == limn.scene.event.MouseEvent.Type.PRESS) {
                presses++;
                event.consume();
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            described++;
            a.role(Accessible.Role.BUTTON);
            a.name(I18nString.literal(name));
        }

        @Override
        protected float baselineOffset() {
            return 13;
        }
    }

    /** Two cells a widget draws itself, published as synthetic children. */
    static final class Cells extends Widget<Cells> {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(40, 20);
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.LIST);
            a.name(I18nString.literal("cells"));
            for (int i = 0; i < 2; i++) {
                a.child(i + 1);
                a.bounds(i * 20, 0, 20, 20);
                a.role(Accessible.Role.LIST_ITEM);
                a.name(I18nString.literal("cell " + i));
                a.endChild();
            }
        }
    }

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<String> painted = new ArrayList<>();
    private final TrackingCanvas canvas = new TrackingCanvas();

    /** A scene in a window, so motions have frames to step them, on a clock this test moves. */
    private Scene sceneOf(Widget<?> root) {
        Scene scene = new Scene(root, () -> now[0]);
        scene.bind(new RecordingWindow());
        scene.setPartialRendering(true);
        frame(scene);
        return scene;
    }

    /** One frame, its buffer one present old, so a pass is exactly what this frame damaged. */
    private void frame(Scene scene) {
        canvas.reset();
        painted.clear();
        scene.renderFrame(canvas, false, Float.NaN, 1);
    }

    private void advance(Scene scene, long millis) {
        now[0] += TimeUnit.MILLISECONDS.toNanos(millis);
        frame(scene);
    }

    /**
     * {@code widget} at its natural size with its corner at {@code (left, top)}: in a stack, which
     * hands its layers loose constraints, so that a root laid out to the window does not stretch it.
     */
    private static Stack at(Widget<?> widget, float top, float left) {
        Stack root = new Stack();
        root.add(new Padding(new Insets(top, 0, 0, left), widget));
        return root;
    }

    private static List<Rect> sortedByX(List<Rect> rects) {
        List<Rect> sorted = new ArrayList<>(rects);
        sorted.sort((a, b) -> Float.compare(a.x(), b.x()));
        return sorted;
    }

    private static Rect boundsOf(List<Rect> rects) {
        float x0 = Float.POSITIVE_INFINITY;
        float y0 = Float.POSITIVE_INFINITY;
        float x1 = Float.NEGATIVE_INFINITY;
        float y1 = Float.NEGATIVE_INFINITY;
        for (Rect r : rects) {
            x0 = Math.min(x0, r.x());
            y0 = Math.min(y0, r.y());
            x1 = Math.max(x1, r.right());
            y1 = Math.max(y1, r.bottom());
        }
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    private static List<Change> transformChanges(AccessibleHarness harness) {
        return harness.changes.stream().filter(c -> c.aspect() == Change.Aspect.TRANSFORM).toList();
    }

    /** The leaf's fill on the device in the last frame; the test fails if it was not drawn once. */
    private Rect fillOf(String name) {
        int at = painted.indexOf(name);
        assertTrue(at >= 0, name + " was painted: " + painted);
        return canvas.fills.get(at);
    }

    private static void assertRect(Rect expected, Rect actual, String message) {
        assertEquals(expected.x(), actual.x(), EPS, message + ": x of " + actual);
        assertEquals(expected.y(), actual.y(), EPS, message + ": y of " + actual);
        assertEquals(expected.width(), actual.width(), EPS, message + ": width of " + actual);
        assertEquals(expected.height(), actual.height(), EPS, message + ": height of " + actual);
    }

    // ------------------------------------------------------------------ the values

    @Test
    void theValuesStartAtRestAreFluentAndRefuseWhatIsNotAFiniteNumber() {
        Transform t = new Transform(new Leaf("w", painted));
        assertEquals(0f, t.translationX());
        assertEquals(0f, t.translationY());
        assertEquals(1f, t.scaleX());
        assertEquals(1f, t.scaleY());
        assertEquals(0.5f, t.originX());
        assertEquals(0.5f, t.originY());
        assertSame(t, t.setTranslation(3, 4).setScale(2).setOrigin(0, 1));
        assertEquals(3f, t.translationX());
        assertEquals(4f, t.translationY());
        assertEquals(2f, t.scaleX());
        assertEquals(2f, t.scaleY());
        assertEquals(0f, t.originX());
        assertEquals(1f, t.originY());
        t.setScale(-1, 0);
        assertEquals(-1f, t.scaleX(), "a negative factor mirrors");
        assertEquals(0f, t.scaleY(), "and 0 collapses");

        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> t.setTranslation(bad, 0));
            assertThrows(IllegalArgumentException.class, () -> t.setTranslation(0, bad));
            assertThrows(IllegalArgumentException.class, () -> t.setScale(bad));
            assertThrows(IllegalArgumentException.class, () -> t.setScale(1, bad));
            assertThrows(IllegalArgumentException.class, () -> t.setOrigin(bad, 0));
            assertThrows(IllegalArgumentException.class, () -> t.slideTo(bad, 0));
            assertThrows(IllegalArgumentException.class, () -> t.scaleTo(bad));
        }
        for (double bad : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> t.slideTo(1, 1, bad, null));
            assertThrows(IllegalArgumentException.class, () -> t.scaleTo(1, 1, bad, null));
            assertThrows(IllegalArgumentException.class,
                    () -> t.flipTo(Transform.Flip.FORWARD, bad, () -> { }, null));
        }
        assertThrows(NullPointerException.class, () -> t.flipTo(Transform.Flip.UP, null));
        assertThrows(NullPointerException.class, () -> t.flipTo(null, () -> { }));
        assertEquals(3f, t.translationX(), "a refused call changes nothing");
        assertEquals(-1f, t.scaleX());

        t.reset();
        assertEquals(0f, t.translationX());
        assertEquals(1f, t.scaleY());
        assertEquals(0.5f, t.originX(), "the origin too");
    }

    @Test
    void onlyTheUiThreadSetsItAndAPaintMayNot() throws InterruptedException {
        Transform t = new Transform(new Leaf("w", painted));
        AtomicReference<Throwable> setting = new AtomicReference<>();
        AtomicReference<Throwable> sliding = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                t.setTranslation(5, 5);
            } catch (Throwable thrown) {
                setting.set(thrown);
            }
            try {
                t.slideTo(5, 5);
            } catch (Throwable thrown) {
                sliding.set(thrown);
            }
        });
        other.start();
        other.join();
        assertInstanceOf(IllegalStateException.class, setting.get());
        assertInstanceOf(IllegalStateException.class, sliding.get());

        Widget.beginPaint();
        try {
            assertThrows(IllegalStateException.class, () -> t.setTranslation(5, 5));
            assertThrows(IllegalStateException.class, () -> t.setScale(1), "even to the value it has");
            assertThrows(IllegalStateException.class, () -> t.setOrigin(0, 0));
            assertThrows(IllegalStateException.class, () -> t.reset());
            assertThrows(IllegalStateException.class, () -> t.scaleTo(2));
            assertThrows(IllegalStateException.class, () -> t.flipTo(Transform.Flip.UP, () -> { }));
        } finally {
            Widget.endPaint();
        }
        assertEquals(0f, t.translationX());
    }

    // ------------------------------------------------------------------ at rest

    @Test
    void theContainerIsNothingToLayoutAndNothingToTheAccessibleTree() {
        Leaf alone = new Leaf("alone", painted);
        Leaf wrapped = new Leaf("wrapped", painted);
        Transform moved = new Transform(wrapped);
        Row row = new Row();
        row.crossAlignment(Flex.CrossAlignment.BASELINE);
        row.add(alone);
        row.add(moved);
        row.add(new Leaf("tall", painted) {
            @Override
            protected Size onMeasure(Constraints constraints) {
                return constraints.constrain(40, 60);
            }

            @Override
            protected float baselineOffset() {
                return 50;
            }
        });
        AccessibleHarness harness = new AccessibleHarness(runtime, row);
        harness.frame();

        assertEquals(alone.width(), moved.width(), "measured as the child alone");
        assertEquals(alone.height(), moved.height());
        assertEquals(0f, wrapped.x());
        assertEquals(0f, wrapped.y(), "the child given the whole box");
        assertEquals(alone.y(), moved.y(), "on the line's baseline as the child alone would be");
        assertEquals(4, harness.tree().nodeCount(), "a window and three buttons, and nothing for the container");
        assertEquals(harness.node("alone").parent(), harness.node("wrapped").parent());

        // Moved, the layout is still the layout at rest.
        float x = moved.x();
        moved.setTranslation(30, 10).setScale(2);
        harness.frame();
        assertEquals(x, moved.x(), "the container's place is layout's");
        assertEquals(0f, wrapped.x(), "and so is the child's");
        assertEquals(alone.width(), wrapped.width());
    }

    /** At rest a container changes nothing that is drawn: the same fills, at the same places. */
    @Test
    void atRestTheFrameAndItsDrawsAreTodays() {
        List<Rect> plain = fillsOfAForm(false, false);
        List<Rect> wrapped = fillsOfAForm(true, false);
        assertEquals(plain, wrapped, "the same draws, at the same places");
        assertEquals(plain, fillsOfAForm(true, true), "and after a move set back by reset()");
    }

    private List<Rect> fillsOfAForm(boolean wrap, boolean movedAndBack) {
        Column column = new Column();
        List<Transform> wraps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Leaf leaf = new Leaf("row " + i, painted);
            if (wrap) {
                Transform t = new Transform(leaf);
                wraps.add(t);
                column.add(t);
            } else {
                column.add(leaf);
            }
        }
        Scene scene = sceneOf(at(column, 12.5f, 30.25f));
        if (movedAndBack) {
            for (Transform t : wraps) {
                t.setTranslation(17, 3).setScale(1.5f, 0.5f);
            }
            frame(scene);
            assertEquals(wraps.size(), scene.transformed().size(), "moved, each is in the scene's set");
            for (Transform t : wraps) {
                t.reset();
            }
        }
        for (Transform t : wraps) {
            assertNull(TransformAccess.childMatrix(t), "at rest: no matrix");
        }
        assertTrue(scene.transformed().isEmpty(), "and none in the scene's set, so every path is today's");
        scene.requestRender();
        frame(scene);
        return new ArrayList<>(canvas.fills);
    }

    // ------------------------------------------------------------------ what is drawn

    @Test
    void theChildIsDrawnTranslatedAndScaledAboutItsOrigin() {
        Leaf leaf = new Leaf("w", painted);
        Transform t = new Transform(leaf);
        Stack root = at(t, 40, 60);
        Scene scene = sceneOf(root);
        assertRect(new Rect(60, 40, 40, 20), fillOf("w"), "at rest");

        t.setTranslation(10, 5);
        scene.requestRender();
        frame(scene);
        assertRect(new Rect(70, 45, 40, 20), fillOf("w"), "moved");

        t.setScale(2); // about the middle, (80, 50) before the move
        scene.requestRender();
        frame(scene);
        assertRect(new Rect(50, 35, 80, 40), fillOf("w"), "scaled about the middle, then moved");

        t.setOrigin(0, 0);
        scene.requestRender();
        frame(scene);
        assertRect(new Rect(70, 45, 80, 40), fillOf("w"), "scaled from the start corner, then moved");

        t.setScale(0, 1);
        scene.requestRender();
        frame(scene);
        assertFalse(painted.contains("w"), "collapsed to a line, it draws nothing");
        assertFalse(leaf.isShowing(), "and is not showing");
        assertFalse(root.hitTest(70, 50) == leaf, "nor takes the pointer");
    }

    @Test
    void inARightToLeftSubtreeTheTranslationAndTheOriginFollowTheDirection() {
        Leaf leaf = new Leaf("w", painted);
        Transform t = new Transform(leaf);
        Padding box = new Padding(new Insets(40, 60, 0, 0), t); // 60 at the right: the start
        box.setLayoutDirection(LayoutDirection.RTL);
        Stack root = new Stack();
        root.add(box);
        Scene scene = sceneOf(root);
        Rect rest = fillOf("w");

        t.setTranslation(10, 5);
        scene.requestRender();
        frame(scene);
        assertRect(new Rect(rest.x() - 10, rest.y() + 5, 40, 20), fillOf("w"),
                "x runs toward the end, which is the left");

        t.setTranslation(0, 0).setOrigin(0, 0).setScale(2);
        scene.requestRender();
        frame(scene);
        assertRect(new Rect(rest.x() + rest.width() - 80, rest.y(), 80, 40), fillOf("w"),
                "the origin's fraction is from the start edge, the right: it grows leftward");
    }

    @Test
    void anOpacityAroundATransformAndATransformAroundAnOpacityBothCoverTheMovedChild() {
        for (boolean opacityOutside : new boolean[] {true, false}) {
            Leaf leaf = new Leaf("w", painted);
            Transform t;
            Widget<?> pair;
            if (opacityOutside) {
                t = new Transform(leaf);
                pair = new Opacity(t).setOpacity(0.5f);
            } else {
                Opacity inner = new Opacity(leaf).setOpacity(0.5f);
                t = new Transform(inner);
                pair = t;
            }
            Scene scene = sceneOf(at(pair, 40, 60));
            t.setTranslation(120, 30).setScale(1.5f);
            scene.requestRender();
            frame(scene);
            Rect drawn = fillOf("w");
            assertEquals(1, canvas.layers.size(), "one picture, the opacity's, in either order");
            Rect layer = canvas.layers.get(0);
            String order = opacityOutside ? "Opacity(Transform)" : "Transform(Opacity)";
            assertTrue(layer.x() <= drawn.x() && layer.y() <= drawn.y()
                    && layer.right() >= drawn.right() && layer.bottom() >= drawn.bottom(),
                    order + ": the layer " + layer + " holds the moved child " + drawn);
            // And no more than the child's antialiased point past it, scaled by 1.5 with it: not as
            // much wider toward the start as the child slid toward the end (stage 2, 2026-10-18).
            float ring = 1.5f + EPS;
            assertTrue(layer.x() >= drawn.x() - ring && layer.y() >= drawn.y() - ring
                    && layer.right() <= drawn.right() + ring && layer.bottom() <= drawn.bottom() + ring,
                    order + ": the layer " + layer + " is the moved child " + drawn + " and its point");
        }
    }

    // ------------------------------------------------------------------ what is damaged

    @Test
    void aSetDamagesWhereTheChildWasAndWhereItIsAndLaysNothingOut() {
        Leaf leaf = new Leaf("w", painted);
        Transform t = new Transform(leaf);
        Stack root = at(t, 40, 60);
        AtomicInteger layoutPasses = new AtomicInteger();
        Scene scene = sceneOf(root);
        scene.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.LAYOUT) {
                layoutPasses.incrementAndGet();
            }
        });
        int layouts = leaf.layouts;

        t.setTranslation(100, 0);
        frame(scene);
        // Where it was and where it is, each a pixel past the box, and nothing between them that
        // a single rectangle would have to cover.
        Rect was = new Rect(59, 39, 42, 22);
        Rect is = new Rect(159, 39, 42, 22);
        assertEquals(List.of(was, is), sortedByX(canvas.passes), "the old place and the new one");
        assertEquals(0, canvas.clears, "not a whole frame");
        assertEquals(layouts, leaf.layouts, "no layout");
        assertEquals(0, layoutPasses.get(), "no layout pass at all");
        assertTrue(painted.contains("w"), "drawn in the new place's pass");

        t.setTranslation(100, 0);
        frame(scene);
        assertTrue(canvas.passes.isEmpty() && canvas.clears == 0, "the value it has damages nothing");

        t.setScale(2, 1); // about its middle, (180, 50): from 140 to 220
        frame(scene);
        Rect union = boundsOf(canvas.passes);
        // The antialiased pixel is scaled with the box: from 160 - 22 to 160 + 62.
        assertRect(new Rect(138, 39, 84, 22), union, "the old box and the wider new one");
        assertEquals(layouts, leaf.layouts);
    }

    @Test
    void eachFrameOfASlideDamagesWhereItWasAndWhereItIsAndNoMore() {
        Leaf leaf = new Leaf("w", painted);
        Transform t = new Transform(leaf);
        Scene scene = sceneOf(at(t, 40, 60));
        int layouts = leaf.layouts;
        t.slideTo(200, 0, 0.3, null);
        advance(scene, 16);
        float before = t.translationX();
        for (int i = 0; i < 30 && t.translationX() < 200; i++) {
            advance(scene, 16);
            float after = t.translationX();
            Rect expected = new Rect(59 + before, 39, after - before + 42, 22);
            Rect union = boundsOf(canvas.passes);
            // Within a pixel: each rectangle is snapped outward to the device's grid.
            assertTrue(union.x() >= expected.x() - 1 && union.right() <= expected.right() + 1,
                    "frame " + i + ": " + union + " within the place left and the place reached " + expected);
            assertEquals(0, canvas.clears);
            before = after;
        }
        assertEquals(200f, t.translationX(), "arrived");
        assertEquals(layouts, leaf.layouts, "and nothing was laid out on the way");
    }

    /**
     * A pass reaches a child slid past its parent's box: the parent's box says nothing of where the
     * transform under it drew the child, and a pass culled by the box left the child's new place
     * blank. Fails with either cull, the scene's or the canvas clip's, left to the boxes alone.
     */
    @Test
    void aPassReachesAChildSlidPastAnUnclippedParentsBox() {
        Leaf leaf = new Leaf("drawer", painted);
        Transform t = new Transform(leaf);
        Padding parent = new Padding(new Insets(40, 0, 0, 60), t);
        SizedBox there = new SizedBox(4, 4);
        Stack root = new Stack();
        root.add(parent);
        root.add(new Padding(new Insets(45, 0, 0, 270), there));
        Scene scene = sceneOf(root);
        t.setTranslation(200, 0); // drawn from 260 to 300, far past the padding's 100
        frame(scene);
        assertTrue(parent.width() <= 100, "the case puts the parent's box short of the drawer: " + parent.width());

        there.invalidate();
        frame(scene);
        assertEquals(1, canvas.passes.size(), "one small pass, in the drawer: " + canvas.passes);
        assertTrue(painted.contains("drawer"), "the drawer repainted in it: " + painted);
    }

    // ------------------------------------------------------------------ the pointer

    @Test
    void thePointerFindsTheChildWhereItIsDrawnAtRestAndAfterArrival() {
        Leaf leaf = new Leaf("w", painted);
        Transform t = new Transform(leaf);
        Padding parent = new Padding(new Insets(40, 0, 0, 60), t);
        Stack root = new Stack();
        root.add(parent);
        Scene scene = sceneOf(root);
        assertSame(leaf, root.hitTest(80, 50), "at rest, where layout put it");

        AtomicInteger arrived = new AtomicInteger();
        t.slideTo(200, 0, 0.2, arrived::incrementAndGet);
        for (int i = 0; i < 20; i++) {
            advance(scene, 16);
        }
        assertEquals(1, arrived.get());
        assertSame(leaf, root.hitTest(280, 50), "after arrival, where it is drawn, past its parent's box");
        assertSame(parent, root.hitTest(80, 50),
                "where it was: not the child, and not the container, which is only a place");
        drive(scene).click(leaf);
        assertEquals(1, leaf.presses, "a click on the widget lands where it is drawn");
    }

    // ------------------------------------------------------------------ readers

    @Test
    void aReaderIsToldWhereTheChildIsOnASetAndAtArrivalAndNotOnTheFramesBetween() {
        Leaf leaf = new Leaf("w", painted);
        Cells cells = new Cells();
        Column held = new Column();
        held.add(leaf);
        held.add(cells);
        Transform t = new Transform(held).setOrigin(0, 0);
        AccessibleHarness harness = new AccessibleHarness(runtime, at(t, 40, 60));
        AccessibleNode node = harness.node("w");
        assertRect(new Rect(60, 40, 40, 20), node.bounds(), "at rest");
        // The second cell, in the container's space at rest, and where a scale of 2 from the corner
        // draws it.
        float cellX = cells.x() + 20;
        float cellY = cells.y();
        assertRect(new Rect(60 + cellX, 40 + cellY, 20, 20), harness.node("cell 1").bounds(), "at rest");

        t.setScale(2);
        harness.frame();
        assertRect(new Rect(60, 40, 80, 40), harness.node("w").bounds(), "a set: scaled at once");
        assertRect(new Rect(60 + 2 * cellX, 40 + 2 * cellY, 40, 40), harness.node("cell 1").bounds(),
                "a synthetic child scaled and moved with its owner, and not only moved by its corner");

        int described = leaf.described;
        Rect before = harness.node("w").bounds();
        AtomicInteger arrived = new AtomicInteger();
        t.slideTo(100, 0, 0.3, arrived::incrementAndGet);
        harness.frame(); // the first frame is its start
        for (int i = 0; i < 6; i++) {
            harness.advanceTime(16, null);
            assertTrue(t.translationX() > 0 && t.translationX() < 100, "in flight: " + t.translationX());
            assertEquals(before, harness.node("w").bounds(), "the published bounds stay on the frames between");
        }
        assertEquals(described, leaf.described, "and no frame of the slide walks the tree");
        harness.advanceTime(400, null);
        assertEquals(1, arrived.get());
        assertRect(new Rect(160, 40, 80, 40), harness.node("w").bounds(), "at arrival, where it came to rest");
        assertRect(new Rect(160 + 2 * cellX, 40 + 2 * cellY, 40, 40), harness.node("cell 1").bounds(),
                "and its synthetic children");
    }

    // ------------------------------------------------------------------ announcements

    @Test
    void aSetAnnouncesEveryChangeResetOnceAndTheSameValueNothing() {
        Transform t = new Transform(new Leaf("w", painted));
        AccessibleHarness harness = new AccessibleHarness(runtime, t);
        t.setTranslation(5, 0);
        t.setScale(2);
        t.setOrigin(0, 0);
        assertEquals(List.of(TRANSFORM, TRANSFORM, TRANSFORM), transformChanges(harness), "one for each set");

        harness.clearObservations();
        t.setTranslation(5, 0).setScale(2).setOrigin(0, 0);
        assertTrue(transformChanges(harness).isEmpty(), "none for the values it has");

        t.reset();
        assertEquals(List.of(TRANSFORM), transformChanges(harness), "reset is one change");
        harness.clearObservations();
        t.reset();
        assertTrue(transformChanges(harness).isEmpty(), "and none at rest");
    }

    @Test
    void aMotionAnnouncesItsStartAndItsEndAndNoFrameBetween() {
        Transform t = new Transform(new Leaf("w", painted));
        AccessibleHarness harness = new AccessibleHarness(runtime, t);
        AtomicInteger arrived = new AtomicInteger();
        t.scaleTo(0.5f, 0.5f, 0.5, arrived::incrementAndGet);
        assertEquals(List.of(TRANSFORM), transformChanges(harness), "the start, at once");
        harness.frame();
        List<Float> seen = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            harness.advanceTime(50, null);
            seen.add(t.scaleX());
        }
        assertEquals(List.of(TRANSFORM, TRANSFORM), transformChanges(harness), "twelve frames, two announcements");
        assertEquals(1, arrived.get());
        assertEquals(0.5f, t.scaleX());
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i) <= seen.get(i - 1), "eased one way: " + seen);
        }
        assertTrue(seen.get(0) < 1f && seen.get(0) > 0.5f, "the frames between move it: " + seen);
    }

    @Test
    void aNewerMotionOrASetSupersedesOneInFlightAndMotionsOfDifferentValuesRunTogether() {
        Transform t = new Transform(new Leaf("w", painted));
        AccessibleHarness harness = new AccessibleHarness(runtime, t);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AtomicInteger scaled = new AtomicInteger();

        t.slideTo(100, 0, 1.0, first::incrementAndGet);
        t.scaleTo(2, 2, 0.4, scaled::incrementAndGet);
        harness.frame();
        harness.advanceTime(200, null);
        float mid = t.translationX();
        assertTrue(mid > 0 && mid < 100, "in flight: " + mid);
        assertTrue(t.scaleX() > 1 && t.scaleX() < 2, "and the scale with it: " + t.scaleX());
        t.slideTo(0, 50, 0.2, second::incrementAndGet);
        assertEquals(mid, t.translationX(), "superseded where it was");
        harness.advanceTime(1500, null);
        assertEquals(0, first.get(), "the superseded slide's onArrive never runs");
        assertEquals(1, second.get());
        assertEquals(1, scaled.get(), "the scale ran on, beside both slides");
        assertEquals(50f, t.translationY());
        assertEquals(2f, t.scaleX());
        assertEquals(6, transformChanges(harness).size(),
                "the first slide's start and end, the scale's two, the second slide's two");

        harness.clearObservations();
        t.slideTo(30, 0, 1.0, first::incrementAndGet);
        harness.frame();
        harness.advanceTime(200, null);
        t.setTranslation(0, 0);
        harness.advanceTime(1500, null);
        assertEquals(0, first.get(), "a set supersedes it too");
        assertEquals(List.of(TRANSFORM, TRANSFORM, TRANSFORM), transformChanges(harness),
                "the slide's start and end, and the set's own change");
    }

    /** What delayed tasks on the UI queue read as now: moved only by {@link #turnAfter}. */
    private final long[] queueNow = {0};

    /** Puts the UI queue's delayed tasks on {@link #queueNow}, which this test moves. */
    private void queueOnTestClock() {
        runtime.setDelayClock(() -> queueNow[0]);
    }

    /** One turn of the loop, {@code millis} after the last on the queue's clock. */
    private void turnAfter(long millis) {
        queueNow[0] += TimeUnit.MILLISECONDS.toNanos(millis);
        runtime.drain();
    }

    @Test
    void underLessMotionAMotionGoesStraightThereAsOneSetAndAFlipSwapsOnTheNextTurn() {
        queueOnTestClock();
        Transform t = new Transform(new Leaf("w", painted));
        AccessibleHarness harness = new AccessibleHarness(runtime, t);
        AtomicInteger arrived = new AtomicInteger();
        List<String> order = new ArrayList<>();
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            t.slideTo(40, 0, 0.5, arrived::incrementAndGet);
            assertEquals(40f, t.translationX(), "straight to the target");
            assertEquals(0, arrived.get(), "onArrive never inside the call");
            assertEquals(List.of(TRANSFORM), transformChanges(harness), "one announcement, as one set");
            runtime.drain();
            turnAfter(499);
            assertEquals(0, arrived.get(), "not on the next turn: after the half second asked for");
            turnAfter(1);
            assertEquals(1, arrived.get(), "then");

            harness.clearObservations();
            t.flipTo(Transform.Flip.FORWARD, 0.5, () -> order.add("edge"), () -> order.add("arrive"));
            assertEquals(List.of(), order, "neither the swap nor the arrival inside the call");
            assertTrue(transformChanges(harness).isEmpty(), "with no turn, nothing moved to announce");
            runtime.drain();
            assertEquals(List.of("edge"), order, "the swap on the next turn: the press answers at once");
            turnAfter(499);
            assertEquals(List.of("edge"), order, "the arrival waits its seconds");
            turnAfter(1);
            assertEquals(List.of("edge", "arrive"), order, "then");
            turnAfter(1000);
            assertEquals(List.of("edge", "arrive"), order, "each once");

            t.rotateTo(30, 0.5, arrived::incrementAndGet);
            assertEquals(30f, t.rotation(), "a rotation straight there too");
        }
        t.scaleTo(3, 3, 0, arrived::incrementAndGet);
        assertEquals(3f, t.scaleX(), "no time: straight there");
        assertEquals(1, arrived.get(), "no time: not inside the call");
        runtime.drain();
        assertEquals(2, arrived.get(), "the scale's on the next turn; the rotation waits its seconds");
        turnAfter(500);
        assertEquals(3, arrived.get(), "the rotation's");
        t.scaleTo(3, 3, 0.5, arrived::incrementAndGet);
        runtime.drain();
        assertEquals(3, arrived.get(), "the value it has, motion allowed: after its seconds");
        turnAfter(500);
        assertEquals(4, arrived.get());
        Transform loose = new Transform(new Leaf("loose", painted));
        loose.slideTo(7, 7, 0.5, arrived::incrementAndGet);
        assertEquals(7f, loose.translationX(), "no scene to tick: straight there");
        Transform windowless = new Transform(new Leaf("windowless", painted));
        new Scene(windowless);
        windowless.slideTo(9, 9, 0.5, arrived::incrementAndGet);
        assertEquals(9f, windowless.translationX(), "a scene with no window: straight there");
        runtime.drain();
        assertEquals(4, arrived.get(), "neither inside its call nor on the next turn");
        turnAfter(500);
        turnAfter(500);
        assertEquals(6, arrived.get(), "both after their seconds, once");
    }

    /**
     * The chain an application may write, the next motion in the last one's {@code onArrive},
     * wherever it goes straight there: run inside the call it recursed until a
     * {@link StackOverflowError} came out of the first; run on the next turn it never let the loop
     * sleep. After the seconds asked for, it keeps its pace and the loop sleeps between steps.
     */
    @Test
    void aMotionChainedInOnArriveSleepsBetweenStepsWhereverItGoesStraightThere() {
        queueOnTestClock();
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            Transform reduced = new Transform(new Leaf("reduced", painted));
            sceneOf(at(reduced, 0, 0));
            chain("less motion, in a window", reduced, 0.5);
        }
        chain("in no scene", new Transform(new Leaf("loose", painted)), 0.5);
        Transform windowless = new Transform(new Leaf("windowless", painted));
        new Scene(windowless);
        assertNotNull(windowless.scene());
        chain("in a scene with no window", windowless, 0.5);
        Transform instant = new Transform(new Leaf("instant", painted));
        sceneOf(at(instant, 0, 0));
        chain("zero seconds, in a window", instant, 0);
    }

    /**
     * Each motion chained on itself for many steps: none inside a call; with seconds, one arrival a
     * motion a period and the loop free to sleep between; with none, one a turn.
     */
    private void chain(String where, Transform t, double seconds) {
        long period = Math.round(seconds * 1000);
        AtomicInteger arrivals = new AtomicInteger();
        AtomicInteger edges = new AtomicInteger();
        Runnable[] slide = new Runnable[1];
        Runnable[] scale = new Runnable[1];
        Runnable[] turn = new Runnable[1];
        Runnable[] flip = new Runnable[1];
        slide[0] = () -> {
            arrivals.incrementAndGet();
            t.slideTo(t.translationX() == 0 ? 40 : 0, 0, seconds, slide[0]);
        };
        scale[0] = () -> {
            arrivals.incrementAndGet();
            t.scaleTo(t.scaleX() == 1 ? 2 : 1, 1, seconds, scale[0]);
        };
        turn[0] = () -> {
            arrivals.incrementAndGet();
            t.rotateTo(t.rotation() == 0 ? 90 : 0, seconds, turn[0]);
        };
        flip[0] = () -> {
            arrivals.incrementAndGet();
            t.flipTo(Transform.Flip.FORWARD, seconds, edges::incrementAndGet, flip[0]);
        };
        t.slideTo(40, 0, seconds, slide[0]);
        t.scaleTo(2, 1, seconds, scale[0]);
        t.rotateTo(90, seconds, turn[0]);
        t.flipTo(Transform.Flip.FORWARD, seconds, edges::incrementAndGet, flip[0]);
        assertEquals(0, arrivals.get() + edges.get(), where + ": nothing run inside a call");
        int steps = period == 0 ? 5_000 : 200; // with no time: past the depth the stack ran out at
        for (int i = 1; i <= steps; i++) {
            if (period > 0) {
                for (int spin = 0; spin < 20; spin++) {
                    runtime.drain();
                }
                assertEquals(4 * (i - 1), arrivals.get(), where + ": no arrival before the period is over");
                assertEquals(i, edges.get(), where + ": the flip's swap on the turn after it was asked for");
                assertEquals(TimeUnit.MILLISECONDS.toNanos(period), runtime.nanosUntilNextDeadline(),
                        where + ": the loop may sleep until the next step");
                turnAfter(period);
            } else {
                runtime.drain();
            }
            assertEquals(4 * i, arrivals.get(), where + ": one arrival a motion a step");
            if (period == 0) {
                assertEquals(i, edges.get(), where + ": the swap with its arrival");
            }
        }
        t.reset(); // supersedes every arrival still waiting
        turnAfter(period);
        assertEquals(4 * steps, arrivals.get(), where + ": a reset supersedes what waited");
    }

    /**
     * Two presses on a card that turns over, a little more than half a flip apart: with motion the
     * first is past its edge when the second comes, so each swaps and the front shows again; under
     * less motion each swaps on the turn after its press, so the face is the same.
     */
    @Test
    void twoFlipsHalfATurnApartShowTheSameFaceWithAndWithoutMotion() {
        queueOnTestClock();
        for (boolean reduced : new boolean[] {false, true}) {
            try (TestPreferences system = new TestPreferences()) {
                system.reduceMotion(reduced);
                String what = reduced ? "less motion" : "with motion";
                Transform card = new Transform(new Leaf("card", painted));
                Scene scene = sceneOf(at(card, 0, 0));
                int[] swaps = {0};
                boolean[] back = {false};
                Runnable press = () -> card.flipTo(Transform.Flip.FORWARD, 0.45, () -> {
                    swaps[0]++;
                    back[0] = !back[0];
                }, null);
                press.run();
                if (reduced) {
                    runtime.drain();
                    assertEquals(1, swaps[0], what + ": the press swaps on the next turn");
                }
                for (int at = 10; at <= 1500; at += 10) {
                    if (at == 300) {
                        press.run();
                    }
                    turnAfter(10);
                    advance(scene, 10);
                }
                assertEquals(2, swaps[0], what + ": each press swapped");
                assertFalse(back[0], what + ": the front shows again");
            }
        }
    }

    /** A newer flip drops a waiting flip's arrival; a swap already made stays made. */
    @Test
    void aNewerFlipDropsTheWaitingArrivalAndKeepsASwapAlreadyMade() {
        queueOnTestClock();
        Transform t = new Transform(new Leaf("w", painted)); // in no scene: straight there
        List<String> ran = new ArrayList<>();
        t.flipTo(Transform.Flip.FORWARD, 0.5, () -> ran.add("first edge"), () -> ran.add("first flip"));
        runtime.drain();
        turnAfter(200);
        t.flipTo(Transform.Flip.FORWARD, 0.5, () -> ran.add("second edge"), () -> ran.add("second flip"));
        turnAfter(400);
        assertEquals(List.of("first edge", "second edge"), ran,
                "the first's swap was made and stays; its arrival is dropped");
        turnAfter(100);
        assertEquals(List.of("first edge", "second edge", "second flip"), ran);
    }

    @Test
    void aStraightThereMotionWaitingForItsSecondsIsSupersededAsOneInFlightIs() {
        queueOnTestClock();
        Transform t = new Transform(new Leaf("w", painted)); // in no scene: straight there
        List<String> ran = new ArrayList<>();
        t.slideTo(10, 0, 0.5, () -> ran.add("first slide"));
        t.scaleTo(2, 2, 0.5, () -> ran.add("scale")); // another kind: superseding no slide
        turnAfter(300);
        t.slideTo(20, 0, 0.5, () -> ran.add("second slide"));
        turnAfter(200);
        assertEquals(List.of("scale"), ran, "the first slide's time came, but it was superseded");
        turnAfter(300);
        assertEquals(List.of("scale", "second slide"), ran, "the newer waits its own seconds");

        ran.clear();
        t.slideTo(30, 0, 0.5, () -> ran.add("slide"));
        t.setTranslation(0, 0);
        t.scaleTo(3, 3, 0.5, () -> ran.add("scale"));
        t.setScale(1, 1);
        t.rotateTo(45, 0.5, () -> ran.add("rotation"));
        t.setRotation(0);
        t.flipTo(Transform.Flip.FORWARD, 0.5, () -> ran.add("edge"), () -> ran.add("flip"));
        t.reset();
        turnAfter(1000);
        assertEquals(List.of(), ran, "a set, or a reset, supersedes each while it waits");
    }

    @Test
    void aStraightThereMotionSupersededBeforeTheNextTurnRunsNothing() {
        Transform t = new Transform(new Leaf("w", painted));
        sceneOf(at(t, 0, 0));
        List<String> ran = new ArrayList<>();

        t.slideTo(10, 0, 0, () -> ran.add("first slide"));
        t.slideTo(20, 0, 0, () -> ran.add("second slide"));
        t.scaleTo(2, 2, 0, () -> ran.add("scale")); // another value: superseding no slide
        runtime.drain();
        assertEquals(List.of("second slide", "scale"), ran, "a newer slide the same turn supersedes the first");

        ran.clear();
        t.slideTo(30, 0, 0, () -> ran.add("slide"));
        t.setTranslation(0, 0);
        t.scaleTo(3, 3, 0, () -> ran.add("scale"));
        t.setScale(1, 1);
        t.rotateTo(45, 0, () -> ran.add("rotation"));
        t.setRotation(0);
        t.slideTo(5, 0, 0, () -> ran.add("slid then moving"));
        t.slideTo(50, 0, 0.3, null); // one that moves supersedes it too
        runtime.drain();
        assertEquals(List.of(), ran, "a set, or a motion that moves, supersedes each");

        t.flipTo(Transform.Flip.FORWARD, 0, () -> ran.add("first edge"), () -> ran.add("first flip"));
        t.flipTo(Transform.Flip.UP, 0, () -> ran.add("second edge"), () -> ran.add("second flip"));
        runtime.drain();
        assertEquals(List.of("second edge", "second flip"), ran,
                "a newer flip the same turn: the first neither swaps nor arrives");

        ran.clear();
        t.flipTo(Transform.Flip.FORWARD, 0, () -> ran.add("edge"), () -> ran.add("flip"));
        t.reset();
        runtime.drain();
        assertEquals(List.of(), ran, "a reset supersedes a flip that waits for the next turn");

        t.flipTo(Transform.Flip.FORWARD, 0, () -> {
            ran.add("edge that resets");
            t.reset();
        }, () -> ran.add("flip"));
        runtime.drain();
        runtime.drain();
        assertEquals(List.of("edge that resets"), ran,
                "a flip its own atEdge superseded never arrives, as one that turns would not");
    }

    // ------------------------------------------------------------------ the flip

    /** The z of a point of the container's space through its matrix: positive toward the reader. */
    private static float zOf(Transform t, float x, float y) {
        Mat4 m = TransformAccess.childMatrix(t);
        assertNotNull(m, "turned: a matrix");
        return m.transform(new Vec4(x, y, 0, 1)).z();
    }

    @Test
    void aFlipTurnsTheChildFlatToEdgeOnAndBackAndSwapsAtTheEdge() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            for (Transform.Flip flip : Transform.Flip.values()) {
                Leaf leaf = new Leaf("w", painted);
                Transform t = new Transform(leaf);
                Padding box = new Padding(new Insets(40, 60, 0, 60), t);
                box.setLayoutDirection(direction);
                Stack root = new Stack();
                root.add(box);
                Scene scene = sceneOf(root);
                boolean rtl = direction == LayoutDirection.RTL;
                AtomicInteger edges = new AtomicInteger();
                AtomicInteger arrivals = new AtomicInteger();
                t.flipTo(flip, 0.4, edges::incrementAndGet, arrivals::incrementAndGet);
                advance(scene, 16);
                advance(scene, 80); // early in the first half
                String what = flip + " in " + direction;
                float w = t.width();
                float h = t.height();
                // Which edge comes up toward the reader in the first half.
                float z = switch (flip) {
                    case FORWARD -> zOf(t, rtl ? 0 : w, h / 2); // the end edge
                    case BACKWARD -> zOf(t, rtl ? w : 0, h / 2); // the start edge
                    case UP -> zOf(t, w / 2, h); // the bottom edge
                    case DOWN -> zOf(t, w / 2, 0); // the top edge
                };
                assertTrue(z > 0, what + ": the named edge comes toward the reader, z " + z);
                Rect narrowed = fillOf("w");
                boolean vertical = flip == Transform.Flip.FORWARD || flip == Transform.Flip.BACKWARD;
                assertTrue(vertical ? narrowed.width() < 40 && narrowed.height() == 20
                                : narrowed.height() < 20 && narrowed.width() == 40,
                        what + ": drawn flat, narrowed about the middle: " + narrowed);
                assertEquals(0, edges.get(), what + ": not yet edge-on");
                for (int i = 0; i < 40; i++) {
                    advance(scene, 16);
                }
                assertEquals(1, edges.get(), what + ": atEdge once");
                assertEquals(1, arrivals.get(), what + ": onArrive once");
                assertNull(TransformAccess.childMatrix(t), what + ": flat again, at rest");
            }
        }
    }

    @Test
    void theContentSwappedAtTheEdgeArrivesTheRightWayRound() {
        Leaf leaf = new Leaf("w", painted);
        Transform t = new Transform(leaf);
        Scene scene = sceneOf(at(t, 40, 60));
        List<Float> widths = new ArrayList<>();
        List<Float> factors = new ArrayList<>();
        AtomicInteger swapped = new AtomicInteger();
        t.flipTo(Transform.Flip.FORWARD, 0.4, swapped::incrementAndGet, null);
        advance(scene, 16);
        for (int i = 0; i < 30; i++) {
            advance(scene, 16);
            if (painted.contains("w")) {
                widths.add(fillOf("w").width());
            }
            Mat4 m = TransformAccess.childMatrix(t);
            if (m != null) {
                factors.add(m.get(0, 0));
            }
        }
        assertEquals(1, swapped.get());
        float narrowest = widths.stream().min(Float::compare).orElseThrow();
        assertTrue(narrowest < 8, "it passes edge-on: " + widths);
        for (float factor : factors) {
            assertTrue(factor >= 0, "never drawn mirrored, on either side of the edge: " + factors);
        }
        assertEquals(40f, widths.get(widths.size() - 1), EPS, "and arrives at its full width");
        assertNull(TransformAccess.childMatrix(t));
    }

    // ------------------------------------------------------------------ tickers and leaving

    @Test
    void motionsAskedForWithNoFrameBetweenThemKeepOneTicker() {
        Transform t = new Transform(new Leaf("w", painted));
        Stack holder = new Stack();
        holder.add(t);
        Scene scene = sceneOf(holder);
        int[] before = scene.tickerCounts();
        for (int i = 0; i < 10_000; i++) {
            t.slideTo(i % 2 == 0 ? 10 : 20, 0, 0.3, null);
            t.scaleTo(i % 2 == 0 ? 2 : 3, 1, 0.3, null);
            t.flipTo(Transform.Flip.UP, 0.3, () -> { }, null);
        }
        int[] during = scene.tickerCounts();
        for (int set = 0; set < during.length; set++) {
            assertEquals(before[set] + 1, during[set], "one ticker, whatever the number of motions: set " + set);
        }
        for (int i = 0; i < 40; i++) {
            advance(scene, 16);
        }
        assertEquals(20f, t.translationX());
        assertEquals(3f, t.scaleX());
        assertEquals(before[0], scene.tickerCounts()[0], "and the ticker is gone with the motions");
    }

    @Test
    void aContainerThatLeavesItsSceneSettlesEveryMotionThere() {
        Transform t = new Transform(new Leaf("w", painted));
        Stack holder = new Stack();
        holder.add(t);
        Scene scene = sceneOf(holder);
        List<String> ran = new ArrayList<>();
        List<Change> heard = new ArrayList<>();
        t.observeChanges((source, change) -> heard.add(change));
        t.slideTo(50, 0, 1.0, () -> ran.add("slid"));
        t.scaleTo(2, 2, 1.0, () -> ran.add("scaled"));
        t.flipTo(Transform.Flip.FORWARD, 1.0, () -> ran.add("edge"), () -> ran.add("flipped"));
        advance(scene, 16);
        advance(scene, 100);
        holder.remove(t);
        assertEquals(50f, t.translationX(), "at its target as it left");
        assertEquals(2f, t.scaleX());
        assertEquals(List.of("edge", "slid", "scaled", "flipped"), ran,
                "the flip's swap first, as it had not reached its edge, then every arrival once");
        assertEquals(6, heard.stream().filter(c -> c.aspect() == Change.Aspect.TRANSFORM).count(),
                "three starts and three ends");
        assertFalse(scene.transformed().contains(t), "and the scene it left keeps nothing of it");
        for (int i = 0; i < 20; i++) {
            advance(scene, 16);
        }
        assertEquals(4, ran.size(), "nothing runs again on that scene's frames");
    }

    // ------------------------------------------------------------------ showing

    @Test
    void aChildSlidOutOfAClippingParentIsNotShowing() {
        Leaf leaf = new Leaf("w", painted);
        Transform t = new Transform(leaf);
        Widget<?> clip = new Clipping(new Padding(new Insets(10, 0, 0, 10), t));
        Stack root = new Stack();
        root.add(clip);
        Scene scene = sceneOf(root);
        assertTrue(leaf.isShowing());
        t.setTranslation(300, 0);
        frame(scene);
        assertFalse(leaf.isShowing(), "drawn past the clip: not on screen");
        t.setTranslation(30, 0);
        assertTrue(leaf.isShowing());
    }

    /** A 100 by 60 box that clips what it holds. */
    private static final class Clipping extends Widget<Clipping> {
        Clipping(Widget<?> child) {
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            children().get(0).measure(Constraints.loose(100, 60));
            return constraints.constrain(100, 60);
        }

        @Override
        protected void onLayout() {
            children().get(0).layoutBox(0, 0, 100, 60);
        }

        @Override
        protected boolean clipsChildren() {
            return true;
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            canvas.save();
            canvas.clipRect(0, 0, width(), height());
            super.paintChildren(canvas);
            canvas.restore();
        }

        @Override
        protected @Nullable Widget<?> onHitTest(float localX, float localY) {
            return super.onHitTest(localX, localY);
        }
    }

    @Test
    void aWidgetNobodyMovesHoldsNoValues() throws ReflectiveOperationException {
        java.lang.reflect.Field held = Widget.class.getDeclaredField("transformGroup");
        held.setAccessible(true);
        Leaf plain = new Leaf("plain", painted);
        assertNull(held.get(plain), "the feature costs a widget nobody moves one null reference");
        assertNotNull(held.get(new Transform(plain)), "and is where the container is");
    }
}

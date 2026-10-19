package limn.scene;

import limn.accessibility.AccessibleNode;
import limn.components.ScrollView;
import limn.components.TextField;
import limn.graphics.Canvas;
import limn.graphics.Rect;
import limn.math.Mat4;
import limn.math.Vec2;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;
import limn.scene.layout.Transform;
import limn.testing.AccessibleHarness;
import limn.testing.NoopCanvas;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link Transform} container's rotation and general matrix, stage 2 of the design of
 * transforms (ADR 072 §2.2, §2.3, §2.7, §2.8, §6): the values and their refusals, the order of the
 * product, the picture a turned child is drawn through, the motion, what a change damages and what
 * a watcher hears, and where the pointer, a reader, a scroll view, the clip test and the input
 * method find a turned child. The pixels are the backend's ({@code GlTurnedLayerTest},
 * {@code TransformRotationPixelTest}); this holds the container's side.
 */
class TransformRotationTest extends SceneTestBase {

    private static final Change TRANSFORM = Change.of(Change.Aspect.TRANSFORM, Change.Origin.CODE);
    private static final float EPS = 1e-3f;

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<String> painted = new ArrayList<>();

    /** Keeps each layer opened through a transform, with its rectangle and its matrix. */
    private static final class LayerCanvas extends NoopCanvas {
        final List<Rect> rects = new ArrayList<>();
        final List<Mat4> matrices = new ArrayList<>();
        final List<Rect> passes = new ArrayList<>();
        int plainLayers;

        LayerCanvas() {
            super(400, 300);
        }

        void reset() {
            rects.clear();
            matrices.clear();
            passes.clear();
            plainLayers = 0;
        }

        @Override
        public void saveLayer(float x, float y, float w, float h, float opacity, Mat4 transform) {
            rects.add(new Rect(x, y, w, h));
            matrices.add(transform);
            save();
        }

        @Override
        public void saveLayer(float x, float y, float w, float h, float opacity) {
            plainLayers++;
            save();
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            if (saveCount() == 1) {
                passes.add(new Rect(x, y, w, h)); // the scene clips each pass at the first save
            }
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > count && saveCount() > 0) {
                restore();
            }
        }
    }

    private final LayerCanvas canvas = new LayerCanvas();

    private Scene sceneOf(Widget<?> root) {
        Scene scene = new Scene(root, () -> now[0]);
        scene.bind(new RecordingWindow());
        scene.setPartialRendering(true);
        frame(scene);
        return scene;
    }

    private void frame(Scene scene) {
        canvas.reset();
        painted.clear();
        scene.renderFrame(canvas, false, Float.NaN, 1);
    }

    private void advance(Scene scene, long millis) {
        now[0] += TimeUnit.MILLISECONDS.toNanos(millis);
        frame(scene);
    }

    private static Stack at(Widget<?> widget, float top, float left) {
        Stack root = new Stack();
        root.add(new Padding(new Insets(top, 0, 0, left), widget));
        return root;
    }

    private static List<Change> transformChanges(AccessibleHarness harness) {
        return harness.changes.stream().filter(c -> c.aspect() == Change.Aspect.TRANSFORM).toList();
    }

    /** The bounds of the four corners of a box through a rotation by {@code degrees} about a pivot. */
    private static Rect turnedBounds(float x, float y, float w, float h, float px, float py, float degrees) {
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double cx = (i == 1 || i == 2 ? x + w : x) - px;
            double cy = (i >= 2 ? y + h : y) - py;
            double tx = px + cos * cx - sin * cy;
            double ty = py + sin * cx + cos * cy;
            x0 = Math.min(x0, tx);
            y0 = Math.min(y0, ty);
            x1 = Math.max(x1, tx);
            y1 = Math.max(y1, ty);
        }
        return new Rect((float) x0, (float) y0, (float) (x1 - x0), (float) (y1 - y0));
    }

    private static void assertRect(Rect expected, Rect actual, float tolerance, String message) {
        assertEquals(expected.x(), actual.x(), tolerance, message + ": x of " + actual);
        assertEquals(expected.y(), actual.y(), tolerance, message + ": y of " + actual);
        assertEquals(expected.width(), actual.width(), tolerance, message + ": width of " + actual);
        assertEquals(expected.height(), actual.height(), tolerance, message + ": height of " + actual);
    }

    // ------------------------------------------------------------------ the values

    @Test
    void theRotationAndTheMatrixStartAtRestAreFluentAndRefuseWhatIsNotFinite() {
        Transform t = new Transform(new TransformTest.Leaf("w", painted));
        assertEquals(0f, t.rotation());
        assertSame(Mat4.IDENTITY, t.matrix());
        Mat4 skew = Mat4.affine2D(1, 0, 0.5f, 1, 0, 0);
        assertSame(t, t.setRotation(30).setMatrix(skew));
        assertEquals(30f, t.rotation());
        assertSame(skew, t.matrix());
        t.setMatrix(Mat4.affine2D(1, 0, 0, 1, 0, 0));
        assertSame(Mat4.IDENTITY, t.matrix(), "the identity is no matrix");

        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> t.setRotation(bad));
            assertThrows(IllegalArgumentException.class, () -> t.rotateTo(bad));
            assertThrows(IllegalArgumentException.class, () -> t.setMatrix(Mat4.affine2D(1, 0, bad, 1, 0, 0)));
        }
        for (double bad : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> t.rotateTo(10, bad, null));
        }
        assertThrows(NullPointerException.class, () -> t.setMatrix(null));
        // A matrix that tilts the plane toward the eye is a perspective of its own, and is taken
        // since the turns in depth came.
        Mat4 tilted = Mat4.perspectiveAt(800)
                .multiply(Mat4.rotation(limn.math.Quat.fromAxisAngle(new limn.math.Vec3(0, 1, 0), 0.5f)));
        t.setMatrix(tilted);
        assertSame(tilted, t.matrix());
        t.setMatrix(skew);
        assertEquals(30f, t.rotation(), "a refused call changes nothing");

        t.reset();
        assertEquals(0f, t.rotation(), "reset brings the rotation back");
        assertSame(Mat4.IDENTITY, t.matrix(), "and the matrix");
    }

    // ------------------------------------------------------------------ the product

    @Test
    void theChildIsDrawnThroughTheMatrixTheScaleTheRotationAndTheTranslationAboutTheOrigin() {
        TransformTest.Leaf leaf = new TransformTest.Leaf("w", painted); // 40 by 20
        float c = 0.4f;
        Transform t = new Transform(leaf).setOrigin(0.25f, 0.75f).setTranslation(10, 5).setRotation(30)
                .setScale(2, 1.5f).setMatrix(Mat4.affine2D(1, 0, c, 1, 0, 0));
        Scene scene = sceneOf(at(t, 40, 60));
        // By hand, for a point of the child: the matrix (a skew along x), the scale and the
        // rotation about the origin at (10, 15) of the 40 by 20 box, then the translation, then
        // the container's place.
        double r = Math.toRadians(30);
        for (float[] p : new float[][] {{0, 0}, {40, 0}, {40, 20}, {0, 20}, {13, 7}}) {
            double x = p[0] - 10;
            double y = p[1] - 15;
            x += c * y;
            x *= 2;
            y *= 1.5;
            double rx = Math.cos(r) * x - Math.sin(r) * y;
            double ry = Math.sin(r) * x + Math.cos(r) * y;
            Vec2 drawn = leaf.localToScene(p[0], p[1]);
            assertEquals(60 + 10 + 10 + rx, drawn.x(), EPS, "x of " + p[0] + "," + p[1]);
            assertEquals(40 + 15 + 5 + ry, drawn.y(), EPS, "y of " + p[0] + "," + p[1]);
        }
        assertNotNull(scene);
    }

    @Test
    void inARightToLeftSubtreeTheRotationAndTheMatrixArePhysical() {
        TransformTest.Leaf leaf = new TransformTest.Leaf("w", painted);
        Transform t = new Transform(leaf).setRotation(90);
        Padding rtl = new Padding(new Insets(40, 0, 0, 60), t);
        rtl.setLayoutDirection(LayoutDirection.RTL);
        Stack root = new Stack();
        root.add(rtl);
        sceneOf(root);
        // A quarter turn clockwise about the middle (20, 10): the top-left corner goes to the
        // top-right of the turned box, in either direction, as a clock turns.
        Vec2 box = t.localToScene(0, 0);
        Vec2 corner = leaf.localToScene(0, 0);
        assertEquals(box.x() + 20 + 10, corner.x(), EPS, "clockwise in a right-to-left subtree too");
        assertEquals(box.y() + 10 - 20, corner.y(), EPS);
    }

    // ------------------------------------------------------------------ the picture

    @Test
    void aTurnedChildIsDrawnUprightIntoAPictureLaidThroughTheMatrixAndAMovedOneIsNot() {
        TransformTest.Leaf leaf = new TransformTest.Leaf("w", painted);
        Transform t = new Transform(leaf);
        Scene scene = sceneOf(at(t, 40, 60));
        t.setTranslation(30, 4).setScale(1.5f);
        frame(scene);
        assertTrue(canvas.matrices.isEmpty(), "a move and a scale are drawn straight: " + canvas.rects);

        t.setRotation(12);
        frame(scene);
        assertEquals(1, canvas.rects.size(), "one picture for the turned child");
        // What the child paints, upright in the container's space: its box grown by the pixel the
        // antialiasing reaches and its outset.
        float o = 1 + leaf.paintOutset();
        assertRect(new Rect(-o, -o, 40 + 2 * o, 20 + 2 * o), canvas.rects.get(0), EPS, "the picture keeps");
        Mat4 m = canvas.matrices.get(0);
        Vec2 corner = leaf.localToScene(0, 0);
        // The matrix lays the child's corner where the conversions say it is drawn.
        float wx = m.get(0, 0) * 0 + m.get(0, 1) * 0 + m.get(0, 3) + 60;
        float wy = m.get(1, 0) * 0 + m.get(1, 1) * 0 + m.get(1, 3) + 40;
        assertEquals(corner.x(), wx, EPS);
        assertEquals(corner.y(), wy, EPS);

        t.setRotation(0);
        frame(scene);
        assertTrue(canvas.matrices.isEmpty(), "back upright, no picture");
    }

    // ------------------------------------------------------------------ damage

    @Test
    void aRotationDamagesWhereTheChildWasAndWhereItIsTurnedAndLaysNothingOut() {
        TransformTest.Leaf leaf = new TransformTest.Leaf("w", painted);
        Transform t = new Transform(leaf);
        Scene scene = sceneOf(at(t, 100, 100));
        int layouts = leaf.layouts;
        t.setRotation(40);
        frame(scene);
        assertEquals(layouts, leaf.layouts, "nothing laid out");
        Rect turned = turnedBounds(100, 100, 40, 20, 120, 110, 40);
        boolean covered = false;
        for (Rect pass : canvas.passes) {
            if (pass.x() <= turned.x() && pass.y() <= turned.y()
                    && pass.right() >= turned.right() && pass.bottom() >= turned.bottom()) {
                covered = true;
            }
        }
        assertTrue(covered, "a pass covers the turned child's bounds " + turned + ": " + canvas.passes);
        boolean coversUpright = false;
        for (Rect pass : canvas.passes) {
            if (pass.x() <= 100 && pass.y() <= 100 && pass.right() >= 140 && pass.bottom() >= 120) {
                coversUpright = true;
            }
        }
        assertTrue(coversUpright, "and where it was upright: " + canvas.passes);
    }

    // ------------------------------------------------------------------ the motion

    @Test
    void aRotationMovesLikeEveryMotionAndRunsBesideASlide() {
        Transform t = new Transform(new TransformTest.Leaf("w", painted));
        AccessibleHarness harness = new AccessibleHarness(runtime, t);
        AtomicInteger turned = new AtomicInteger();
        AtomicInteger slid = new AtomicInteger();
        t.rotateTo(90, 0.5, turned::incrementAndGet);
        t.slideTo(20, 0, 0.3, slid::incrementAndGet);
        assertEquals(List.of(TRANSFORM, TRANSFORM), transformChanges(harness), "each motion's start, at once");
        harness.frame();
        List<Float> seen = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            harness.advanceTime(50, null);
            seen.add(t.rotation());
        }
        assertEquals(1, turned.get());
        assertEquals(1, slid.get(), "the slide ran beside it on the one ticker");
        assertEquals(90f, t.rotation());
        assertEquals(4, transformChanges(harness).size(), "two motions, two announcements each");
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i) >= seen.get(i - 1), "eased one way: " + seen);
        }
        assertTrue(seen.get(0) > 0 && seen.get(0) < 90, "the frames between turn it: " + seen);

        // A set supersedes one in flight, whose onArrive never runs.
        harness.clearObservations();
        AtomicInteger superseded = new AtomicInteger();
        t.rotateTo(0, 1.0, superseded::incrementAndGet);
        harness.frame();
        harness.advanceTime(200, null);
        float mid = t.rotation();
        assertTrue(mid > 0 && mid < 90, "in flight: " + mid);
        t.setRotation(45);
        harness.advanceTime(1500, null);
        assertEquals(0, superseded.get());
        assertEquals(45f, t.rotation());
        assertEquals(List.of(TRANSFORM, TRANSFORM, TRANSFORM), transformChanges(harness),
                "the motion's start and end, and the set's own change");
    }

    @Test
    void underLessMotionARotationGoesStraightThereAsOneSet() {
        long[] queueNow = {0};
        Transform t = new Transform(new TransformTest.Leaf("w", painted));
        AccessibleHarness harness = new AccessibleHarness(runtime, t);
        runtime.setDelayClock(() -> queueNow[0]); // after the harness, which hands it its own
        AtomicInteger arrived = new AtomicInteger();
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            t.rotateTo(30, 0.5, arrived::incrementAndGet);
        }
        assertEquals(30f, t.rotation(), "there at once");
        assertEquals(0, arrived.get(), "onArrive never inside the call");
        assertEquals(List.of(TRANSFORM), transformChanges(harness), "heard as one set");
        runtime.drain();
        assertEquals(0, arrived.get(), "nor on the next turn");
        queueNow[0] += TimeUnit.MILLISECONDS.toNanos(500);
        runtime.drain();
        assertEquals(1, arrived.get(), "arrived after the half second asked for");
    }

    @Test
    void aRotationOnAContainerThatLeavesItsSceneSettlesThere() {
        TransformTest.Leaf leaf = new TransformTest.Leaf("w", painted);
        Transform t = new Transform(leaf);
        Stack holder = new Stack();
        holder.add(t);
        Scene scene = sceneOf(holder);
        AtomicInteger arrived = new AtomicInteger();
        t.rotateTo(60, 1.0, arrived::incrementAndGet);
        advance(scene, 16);
        advance(scene, 100);
        assertTrue(t.rotation() > 0 && t.rotation() < 60);
        holder.remove(t);
        assertEquals(60f, t.rotation(), "settled at its target as it leaves");
        assertEquals(1, arrived.get(), "and its onArrive ran then");
    }

    // ------------------------------------------------------------------ the pointer and readers

    @Test
    void thePointerAndAReaderFindATurnedChildWhereItIsDrawn() {
        TransformTest.Leaf leaf = new TransformTest.Leaf("w", painted);
        Transform t = new Transform(leaf);
        Stack root = at(t, 100, 100);
        AccessibleHarness harness = new AccessibleHarness(runtime, root);
        t.setRotation(90);
        harness.frame();
        // A quarter turn about (120, 110): the 40 by 20 box stands 20 wide and 40 tall.
        assertSame(leaf, root.hitTest(120, 128), "hit where the turned child is drawn");
        assertNotSame(leaf, root.hitTest(135, 112), "and not where the upright one was");
        AccessibleNode node = harness.node("w");
        assertRect(new Rect(110, 90, 20, 40), node.bounds(), 0.01f, "a reader is told the turned bounds");

        // On the frames of a motion the published bounds stay; at arrival they move.
        Rect before = harness.node("w").bounds();
        AtomicInteger arrived = new AtomicInteger();
        t.rotateTo(45, 0.3, arrived::incrementAndGet);
        harness.frame();
        for (int i = 0; i < 6; i++) {
            harness.advanceTime(16, null);
            assertEquals(before, harness.node("w").bounds(), "the bounds stay on the frames between");
        }
        harness.advanceTime(400, null);
        assertEquals(1, arrived.get());
        assertRect(turnedBounds(100, 100, 40, 20, 120, 110, 45), harness.node("w").bounds(), 0.01f,
                "and are the turned box's bounds at arrival");
    }

    @Test
    void aClickOnATurnedWidgetLandsWhereItIsDrawn() {
        TransformTest.Leaf leaf = new TransformTest.Leaf("w", painted);
        Transform t = new Transform(leaf).setRotation(-35);
        Scene scene = sceneOf(at(t, 100, 100));
        drive(scene).click(leaf);
        assertEquals(1, leaf.presses);
    }

    // ------------------------------------------------------------------ clips, reveal, the IME

    @Test
    void aTurnedChildIsShowingOnlyWhereItsTurnedBoundsMeetAClippingParent() {
        TransformTest.Leaf leaf = new TransformTest.Leaf("w", painted);
        // The leaf 30 points in from the container's corner, which the container turns about.
        Transform t = new Transform(new Padding(new Insets(30, 0, 0, 30), leaf)).setOrigin(0, 0);
        ScrollView view = new ScrollView(t);
        Stack root = new Stack();
        root.add(new SizedBox(100, 80, view));
        Scene scene = sceneOf(root);
        assertTrue(leaf.isShowing());
        // A half turn about the corner: drawn up and to the left of the view, far out of it.
        t.setRotation(180);
        frame(scene);
        assertFalse(leaf.isShowing(), "turned out of the viewport: not on screen");
        // A twentieth of a turn: still in it.
        t.setRotation(18);
        assertTrue(leaf.isShowing());
    }

    @Test
    void revealHandsAScrollViewAboveATurnedPanelWhereTheWidgetIsDrawn() {
        Column column = new Column();
        column.add(new SizedBox(100, 200));
        TransformTest.Leaf leaf = new TransformTest.Leaf("w", painted);
        column.add(leaf);
        // The panel turned a half turn about its middle: the leaf, laid at its foot 500 points down
        // the scroll view's content, is drawn at its head, 300 points down.
        Transform t = new Transform(column).setRotation(180);
        ScrollView view = new ScrollView(new Padding(new Insets(300, 0, 0, 0), t));
        Stack root = new Stack();
        root.add(new SizedBox(120, 80, view));
        Scene scene = sceneOf(root);
        assertFalse(leaf.isShowing());
        leaf.revealInView();
        frame(scene);
        assertTrue(leaf.isShowing(), "revealed where it is drawn, turned: it is on screen");
        Rect drawn = leaf.localToSceneBounds(0, 0, leaf.width(), leaf.height());
        Rect viewport = view.localToSceneBounds(0, 0, view.width(), view.height());
        assertTrue(drawn.y() >= viewport.y() - EPS && drawn.bottom() <= viewport.bottom() + EPS,
                "the drawn bounds " + drawn + " lie in the viewport " + viewport);
    }

    @Test
    void theInputMethodsCaretRectangleIsTheTurnedCaretsBounds() {
        TextField field = new TextField().setText("turned");
        Transform t = new Transform(field);
        Scene scene = new Scene(at(t, 50, 50), () -> now[0]);
        RecordingWindow window = new RecordingWindow();
        scene.bind(window);
        frame(scene);
        scene.requestFocus(field);
        frame(scene);
        Rect upright = window.lastCaretRect;
        assertNotNull(upright, "the caret was handed to the input method");
        t.setRotation(30);
        frame(scene);
        Rect reported = window.lastCaretRect;
        // The upright caret's box, turned about the field's middle: what is drawn, and its bounds.
        Vec2 middle = field.localToScene(field.width() / 2, field.height() / 2);
        Rect expected = turnedBounds(upright.x(), upright.y(), upright.width(), upright.height(),
                middle.x(), middle.y(), 30);
        assertRect(expected, reported, 0.01f, "the bounds of the caret where it is drawn, turned");
        assertNotEquals(upright, reported, "and handed over again when the container turned");
    }
}

package limn.scene;

import limn.accessibility.AccessibleNode;
import limn.graphics.Rect;
import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Vec2;
import limn.math.Vec3;
import limn.scene.internal.TransformAccess;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link Transform} container turned in depth, stage 3 of the design of transforms (ADR 072
 * §2.2, §2.3, §2.4, §2.7, §6): the turn, the perspective and the back face, their refusals and
 * what a watcher hears; the handedness of the three axes; the order of the product, worked out by
 * hand; the picture a turned child is drawn through; the projected bounds a turn and a flip damage,
 * the near edge's 1.33 times included; the pointer by inverse projection, with a miss behind the eye
 * and on a hidden back face; a quarter turn that draws nothing and takes nothing; a flip that turns
 * in depth once a perspective is set; and the bounds a reader is told. The pixels are the backend's
 * ({@code GlDepthLayerTest}, {@code TransformPassSeamTest}).
 *
 * <p>Every expected point is worked out here from the angles and the distances, never by the
 * conversions or the composition under test.
 */
class TransformDepthTest extends SceneTestBase {

    private static final Change TRANSFORM = Change.of(Change.Aspect.TRANSFORM, Change.Origin.CODE);
    private static final float EPS = 1e-2f;

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<String> painted = new ArrayList<>();

    /** Keeps each layer opened through a transform, with its matrix, and each pass's rectangle. */
    private static final class LayerCanvas extends NoopCanvas {
        final List<Mat4> matrices = new ArrayList<>();
        final List<Rect> passes = new ArrayList<>();

        LayerCanvas() {
            super(1000, 800);
        }

        void reset() {
            matrices.clear();
            passes.clear();
        }

        @Override
        public void saveLayer(float x, float y, float w, float h, float opacity, Mat4 transform) {
            matrices.add(transform);
            save();
        }

        @Override
        public void saveLayer(float x, float y, float w, float h, float opacity) {
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

    /** A leaf of a given size, painted and pressed as {@link TransformTest.Leaf} is. */
    private static final class Card extends TransformTest.Leaf {
        private final float w;
        private final float h;

        Card(String name, List<String> painted, float w, float h) {
            super(name, painted);
            this.w = w;
            this.h = h;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(w, h);
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

    /**
     * Where a point {@code (x, y)} of a box at {@code (left, top)} is drawn, by hand: taken from
     * the origin {@code (ox, oy)} of the box, turned by {@code degrees} about {@code axis} by
     * Rodrigues' formula, seen from {@code distance} in front of the origin, and moved back. All in
     * double precision; {@code NaN} behind the eye.
     */
    private static double[] drawn(double left, double top, double ox, double oy, double[] axis,
                                  double degrees, double distance, double x, double y) {
        double len = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        double kx = axis[0] / len;
        double ky = axis[1] / len;
        double kz = axis[2] / len;
        double vx = x - ox;
        double vy = y - oy;
        double vz = 0;
        double a = Math.toRadians(degrees);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        double dot = kx * vx + ky * vy + kz * vz;
        double rx = vx * cos + (ky * vz - kz * vy) * sin + kx * dot * (1 - cos);
        double ry = vy * cos + (kz * vx - kx * vz) * sin + ky * dot * (1 - cos);
        double rz = vz * cos + (kx * vy - ky * vx) * sin + kz * dot * (1 - cos);
        double w = Double.isInfinite(distance) ? 1 : 1 - rz / distance;
        if (!(w > 0)) {
            return new double[] {Double.NaN, Double.NaN, rz};
        }
        return new double[] {left + ox + rx / w, top + oy + ry / w, rz};
    }

    private static final double[] X = {1, 0, 0};
    private static final double[] Y = {0, 1, 0};
    private static final double[] Z = {0, 0, 1};

    // ------------------------------------------------------------------ the values

    @Test
    void theTurnThePerspectiveAndTheBackFaceStartAtRestAreFluentAndRefuseWhatTheyCannotBe() {
        Transform t = new Transform(new TransformTest.Leaf("w", painted));
        assertSame(Quat.IDENTITY, t.rotation3D());
        assertEquals(Float.POSITIVE_INFINITY, t.perspective());
        assertTrue(t.isBackfaceVisible());

        assertSame(t, t.setRotation3D(new Vec3(0, 2, 0), 60).setPerspective(700).setBackfaceVisible(false));
        Quat turn = t.rotation3D();
        assertEquals(0, turn.x(), 1e-6f);
        assertEquals((float) Math.sin(Math.toRadians(30)), turn.y(), 1e-6f, "the axis normalized");
        assertEquals((float) Math.cos(Math.toRadians(30)), turn.w(), 1e-6f);
        assertEquals(700f, t.perspective());
        assertFalse(t.isBackfaceVisible());

        // A quaternion of any length names its turn, and of q and -q the one with w not below 0.
        t.setRotation3D(new Quat(0, -2, 0, -2));
        Quat kept = t.rotation3D();
        assertEquals((float) Math.sqrt(0.5), kept.y(), 1e-6f, "-q kept for q: (0, 2, 0, 2) normalized");
        assertEquals((float) Math.sqrt(0.5), kept.w(), 1e-6f);
        t.setRotation3D(Quat.IDENTITY);
        assertSame(Quat.IDENTITY, t.rotation3D(), "the identity is no turn");

        assertThrows(NullPointerException.class, () -> t.setRotation3D((Vec3) null, 10));
        assertThrows(NullPointerException.class, () -> t.setRotation3D((Quat) null));
        assertThrows(IllegalArgumentException.class, () -> t.setRotation3D(Vec3.ZERO, 10), "no axis");
        assertThrows(IllegalArgumentException.class, () -> t.setRotation3D(new Quat(0, 0, 0, 0)));
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> t.setRotation3D(Vec3.UNIT_Y, bad));
            assertThrows(IllegalArgumentException.class, () -> t.setRotation3D(new Vec3(bad, 1, 0), 10));
            assertThrows(IllegalArgumentException.class, () -> t.setRotation3D(new Quat(0, bad, 0, 1)));
        }
        for (float bad : new float[] {0, -100, Float.NaN, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> t.setPerspective(bad), "distance " + bad);
        }
        assertEquals(700f, t.perspective(), "a refused call changes nothing");
        t.setPerspective(Float.POSITIVE_INFINITY);
        assertEquals(Float.POSITIVE_INFINITY, t.perspective(), "infinitely far is none, and allowed");

        t.setRotation3D(Vec3.UNIT_X, 20).setPerspective(500);
        t.reset();
        assertSame(Quat.IDENTITY, t.rotation3D(), "reset brings the turn back");
        assertEquals(Float.POSITIVE_INFINITY, t.perspective(), "and the perspective");
        assertTrue(t.isBackfaceVisible(), "and the back face");
    }

    @Test
    void eachSetThatChangesAValueIsHeardOnceAndTheSameValueNothing() {
        Transform t = new Transform(new TransformTest.Leaf("w", painted));
        AccessibleHarness harness = new AccessibleHarness(runtime, t);
        t.setRotation3D(Vec3.UNIT_Y, 30);
        t.setRotation3D(new Vec3(0, 5, 0), 30); // the same turn
        t.setPerspective(800);
        t.setPerspective(800);
        t.setBackfaceVisible(false);
        t.setBackfaceVisible(false);
        assertEquals(List.of(TRANSFORM, TRANSFORM, TRANSFORM), transformChanges(harness));
        t.reset();
        assertEquals(4, transformChanges(harness).size(), "reset once");
        t.reset();
        assertEquals(4, transformChanges(harness).size(), "and nothing at rest");
    }

    // ------------------------------------------------------------------ the axes and the product

    @Test
    void theHandednessOfTheThreeAxesIsPinned() {
        TransformTest.Leaf leaf = new Card("w", painted, 200, 100);
        Transform t = new Transform(leaf).setPerspective(600);
        sceneOf(at(t, 100, 100));

        // About y, a positive angle takes the right edge away from the viewer: drawn shorter than
        // the left one, which comes toward it.
        t.setRotation3D(Vec3.UNIT_Y, 30);
        float right = leaf.localToScene(200, 100).y() - leaf.localToScene(200, 0).y();
        float left = leaf.localToScene(0, 100).y() - leaf.localToScene(0, 0).y();
        assertTrue(right < 100 && left > 100, "about y: right edge " + right + ", left edge " + left);
        assertTrue(drawn(0, 0, 100, 50, Y, 30, 600, 200, 50)[2] < 0, "and the hand agrees");

        // About x, a positive angle brings the bottom edge toward the viewer: drawn wider than the top.
        t.setRotation3D(Vec3.UNIT_X, 30);
        float bottom = leaf.localToScene(200, 100).x() - leaf.localToScene(0, 100).x();
        float top = leaf.localToScene(200, 0).x() - leaf.localToScene(0, 0).x();
        assertTrue(bottom > 200 && top < 200, "about x: bottom edge " + bottom + ", top edge " + top);
        assertTrue(drawn(0, 0, 100, 50, X, 30, 600, 100, 100)[2] > 0, "and the hand agrees");

        // About z, it turns as the rotation does, clockwise on the screen, perspective or not.
        t.setRotation3D(Vec3.UNIT_Z, 30);
        List<Vec2> turned = new ArrayList<>();
        for (float[] p : new float[][] {{0, 0}, {200, 0}, {200, 100}, {37, 81}}) {
            turned.add(leaf.localToScene(p[0], p[1]));
        }
        t.setRotation3D(Quat.IDENTITY).setRotation(30);
        int i = 0;
        for (float[] p : new float[][] {{0, 0}, {200, 0}, {200, 100}, {37, 81}}) {
            Vec2 rotated = leaf.localToScene(p[0], p[1]);
            assertEquals(rotated.x(), turned.get(i).x(), EPS, "about z as the rotation: x of " + p[0] + "," + p[1]);
            assertEquals(rotated.y(), turned.get(i).y(), EPS, "y of " + p[0] + "," + p[1]);
            i++;
        }
        // The right edge's top corner went down and to the left: clockwise.
        assertTrue(turned.get(1).y() > leaf.localToScene(200, 0).y() - 1000, "drawn");
        double[] corner = drawn(100, 100, 100, 50, Z, 30, 600, 200, 0);
        assertEquals(corner[0], turned.get(1).x(), EPS, "clockwise, by hand");
        assertEquals(corner[1], turned.get(1).y(), EPS);
    }

    @Test
    void aPointIsDrawnThroughTheTranslationThePerspectiveTheTurnTheRotationAndTheScaleInOrder() {
        TransformTest.Leaf leaf = new Card("w", painted, 120, 80);
        double[] axis = {1, 2, 0.5};
        Transform t = new Transform(leaf).setOrigin(0.25f, 0.75f).setTranslation(15, -10)
                .setPerspective(400).setRotation3D(new Vec3(1, 2, 0.5f), 40).setRotation(25).setScale(1.5f, 0.8f);
        sceneOf(at(t, 200, 300));
        // By hand, for a point of the child: the scale and the rotation about the origin at (30, 60)
        // of the 120 by 80 box, then the turn in depth, then the perspective seen from 400 points in
        // front of the origin, then the translation, then the container's place.
        double r = Math.toRadians(25);
        for (float[] p : new float[][] {{0, 0}, {120, 0}, {120, 80}, {0, 80}, {47, 13}}) {
            double x = (p[0] - 30) * 1.5;
            double y = (p[1] - 60) * 0.8;
            double rx = Math.cos(r) * x - Math.sin(r) * y;
            double ry = Math.sin(r) * x + Math.cos(r) * y;
            double[] seen = drawn(300 + 15, 200 - 10, 30, 60, axis, 40, 400, 30 + rx, 60 + ry);
            Vec2 at = leaf.localToScene(p[0], p[1]);
            assertEquals(seen[0], at.x(), EPS, "x of " + p[0] + "," + p[1]);
            assertEquals(seen[1], at.y(), EPS, "y of " + p[0] + "," + p[1]);
        }
    }

    // ------------------------------------------------------------------ the picture and the damage

    @Test
    void aTurnInPerspectiveIsDrawnThroughAPictureAndOneWithNoneAboutAnAxisStraight() {
        TransformTest.Leaf leaf = new Card("w", painted, 200, 100);
        Transform t = new Transform(leaf).setRotation3D(Vec3.UNIT_Y, 40);
        Scene scene = sceneOf(at(t, 100, 100));
        assertTrue(canvas.matrices.isEmpty(), "about y with no perspective: a narrowing, drawn straight");
        assertTrue(painted.contains("w"));
        t.setPerspective(500);
        frame(scene);
        assertEquals(1, canvas.matrices.size(), "in perspective: one picture");
        assertTrue(canvas.matrices.get(0).get(3, 0) != 0, "laid through the perspective");
        t.setPerspective(Float.POSITIVE_INFINITY).setRotation3D(new Vec3(1, 1, 0), 40);
        frame(scene);
        assertEquals(1, canvas.matrices.size(), "a turn about a slanted axis is a skew of the plane: a picture");
    }

    @Test
    void theProjectedBoundsOfACardTurnedNearEdgeOnInPerspectiveAreDamagedAndRepainted() {
        // ADR 072 section 2.4's case: a 400 point card turned about its vertical axis, the eye 800
        // points away, shows its near edge at up to 1.33 times, past its own box above and below.
        TransformTest.Leaf leaf = new Card("w", painted, 400, 300);
        Transform t = new Transform(leaf).setPerspective(800);
        Scene scene = sceneOf(at(t, 250, 300));
        t.setRotation3D(Vec3.UNIT_Y, -80);
        frame(scene);
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (double[] p : new double[][] {{0, 0}, {400, 0}, {400, 300}, {0, 300}}) {
            double[] d = drawn(300, 250, 200, 150, Y, -80, 800, p[0], p[1]);
            x0 = Math.min(x0, d[0]);
            y0 = Math.min(y0, d[1]);
            x1 = Math.max(x1, d[0]);
            y1 = Math.max(y1, d[1]);
        }
        double magnification = (y1 - y0) / 300;
        assertTrue(magnification > 1.32 && magnification < 1.34, "the near edge at 1.33 times: " + magnification);
        assertCovered(new Rect((float) x0, (float) y0, (float) (x1 - x0), (float) (y1 - y0)), "the turned card");
        assertTrue(painted.contains("w"), "and repainted, through its picture");
        Rect published = leaf.localToSceneBounds(0, 0, 400, 300);
        assertEquals(y0, published.y(), EPS, "its bounds are the projection's");
        assertEquals(y1 - y0, published.height(), EPS);
    }

    @Test
    void eachFrameOfAFlipInPerspectiveDamagesTheBoundsItLeavesAndReaches() {
        TransformTest.Leaf leaf = new Card("w", painted, 400, 300);
        Transform t = new Transform(leaf).setPerspective(800);
        Scene scene = sceneOf(at(t, 250, 300));
        AtomicInteger swapped = new AtomicInteger();
        t.flipTo(Transform.Flip.FORWARD, 0.5, swapped::incrementAndGet, null);
        advance(scene, 16);
        double tallest = 0;
        Rect before = null;
        for (int i = 0; i < 40; i++) {
            advance(scene, 16);
            Mat4 m = TransformAccess.childMatrix(t);
            if (m == null) {
                continue;
            }
            // The card's corners through the matrix the frame drew with, by hand.
            float bx0 = Float.POSITIVE_INFINITY;
            float by0 = Float.POSITIVE_INFINITY;
            float bx1 = Float.NEGATIVE_INFINITY;
            float by1 = Float.NEGATIVE_INFINITY;
            for (float[] p : new float[][] {{0, 0}, {400, 0}, {400, 300}, {0, 300}}) {
                float[] d = Turned.drawnAt(m, p[0], p[1]);
                bx0 = Math.min(bx0, d[0] + 300);
                by0 = Math.min(by0, d[1] + 250);
                bx1 = Math.max(bx1, d[0] + 300);
                by1 = Math.max(by1, d[1] + 250);
            }
            if (!(bx1 > bx0)) {
                continue; // edge-on: nothing drawn
            }
            Rect now = new Rect(bx0, by0, bx1 - bx0, by1 - by0);
            tallest = Math.max(tallest, now.height());
            assertCovered(now, "frame " + i + "'s turned card");
            if (before != null) {
                assertCovered(before, "frame " + i + "'s place it left");
            }
            before = now;
        }
        assertEquals(1, swapped.get());
        assertTrue(tallest > 300 * 1.25, "near edge-on, the near edge passes the box: " + tallest);
        assertNull(TransformAccess.childMatrix(t), "flat again at the end");
    }

    @Test
    void aChangeInsideACardTurnedFarRepaintsAllOfItAndInsideOneTurnedLittleOnlyItself() {
        // ADR 072 section 12: turned 75 degrees from 800 points, the card's far edge is seen at an
        // eighth of its near edge's size across, the canvas lays its picture through a mipmap chain,
        // and a dot changing in its middle moves the chain's coarser levels across the card; turned
        // 20 degrees, a third more at most, the chain is not made, and the dot repaints only itself.
        for (float degrees : new float[] {75, 20}) {
            TransformTest.Leaf dot = new Card("dot", painted, 10, 10);
            Stack card = new Stack();
            card.add(new Card("w", painted, 400, 300));
            card.add(new Padding(new Insets(140, 0, 0, 195), dot));
            Transform t = new Transform(card).setPerspective(800).setRotation3D(Vec3.UNIT_Y, degrees);
            Scene scene = sceneOf(at(t, 250, 300));
            dot.invalidate();
            frame(scene);
            Rect whole = card.localToSceneBounds(0, 0, 400, 300);
            Rect own = dot.localToSceneBounds(0, 0, 10, 10);
            assertCovered(own, degrees + " degrees: the dot");
            if (degrees == 75) {
                assertCovered(whole, "turned far, the whole card");
            } else {
                for (Rect pass : canvas.passes) {
                    assertTrue(pass.width() < own.width() + 4 && pass.height() < own.height() + 4,
                            "turned little, a pass the dot's size: " + pass + " for " + own);
                }
            }
        }
    }

    /** Fails unless the passes of the last frame together cover {@code r}, within a hundredth. */
    private void assertCovered(Rect r, String what) {
        // Each corner and the middle inside some pass is enough for the rectangles a scene merges.
        for (float[] p : new float[][] {{r.x() + 0.01f, r.y() + 0.01f}, {r.right() - 0.01f, r.y() + 0.01f},
                {r.right() - 0.01f, r.bottom() - 0.01f}, {r.x() + 0.01f, r.bottom() - 0.01f},
                {r.x() + r.width() / 2, r.y() + r.height() / 2}}) {
            boolean in = false;
            for (Rect pass : canvas.passes) {
                if (p[0] >= pass.x() && p[0] <= pass.right() && p[1] >= pass.y() && p[1] <= pass.bottom()) {
                    in = true;
                }
            }
            assertTrue(in, what + " " + r + ": " + p[0] + "," + p[1] + " in no pass of " + canvas.passes);
        }
    }

    // ------------------------------------------------------------------ the pointer

    @Test
    void thePointerFindsATurnedChildByInverseProjectionAndMissesBehindTheEye() {
        TransformTest.Leaf leaf = new Card("w", painted, 200, 100);
        Transform t = new Transform(leaf).setPerspective(700).setRotation3D(Vec3.UNIT_Y, 50);
        Stack root = at(t, 100, 100);
        Scene scene = sceneOf(root);
        for (double[] p : new double[][] {{5, 5}, {195, 95}, {100, 50}, {170, 12}}) {
            double[] d = drawn(100, 100, 100, 50, Y, 50, 700, p[0], p[1]);
            assertSame(leaf, root.hitTest((float) d[0], (float) d[1]), "drawn at " + d[0] + "," + d[1]);
            Vec2 back = leaf.sceneToLocal((float) d[0], (float) d[1]);
            assertEquals(p[0], back.x(), 0.05, "and that point is the child's own");
            assertEquals(p[1], back.y(), 0.05);
        }
        // Past the narrowed right edge, inside the upright box: nothing of the child is there.
        double[] edge = drawn(100, 100, 100, 50, Y, 50, 700, 200, 50);
        assertNotSame(leaf, root.hitTest((float) edge[0] + 4, (float) edge[1]));
        drive(scene).click(leaf);
        assertEquals(1, leaf.presses, "a click on the turned child lands on it");

        // The eye 60 points away and the card turned 80 degrees: its left part swings past the eye.
        t.setPerspective(60).setRotation3D(Vec3.UNIT_Y, 80);
        double[] behind = drawn(100, 100, 100, 50, Y, 80, 60, 5, 50);
        assertTrue(Double.isNaN(behind[0]), "the fixture puts this point behind the eye, z " + behind[2]);
        assertTrue(Float.isNaN(leaf.localToScene(5, 50).x()), "not drawn");
        // The scene point the ray from the eye through it would come from: its projection with a
        // w below 0. The ray meets the plane there behind the eye, and nowhere in front of it.
        double w = 1 - behind[2] / 60;
        double rx = (5 - 100) * Math.cos(Math.toRadians(80));
        float sceneX = (float) (200 + rx / w);
        float sceneY = 150;
        assertNotSame(leaf, root.hitTest(sceneX, sceneY), "a point that meets the plane behind the eye misses");
        assertTrue(Float.isNaN(leaf.sceneToLocal(sceneX, sceneY).x()));
        double[] front = drawn(100, 100, 100, 50, Y, 80, 60, 190, 50);
        assertSame(leaf, root.hitTest((float) front[0], (float) front[1]), "the part in front is hit");
    }

    @Test
    void aBackFaceIsDrawnAndHitUnlessHiddenAndAMirrorIsNoBackFace() {
        TransformTest.Leaf leaf = new Card("w", painted, 200, 100);
        Transform t = new Transform(leaf).setPerspective(900).setRotation3D(Vec3.UNIT_Y, 150);
        Stack root = at(t, 100, 100);
        Scene scene = sceneOf(root);
        double[] p = drawn(100, 100, 100, 50, Y, 150, 900, 60, 50);
        assertSame(leaf, root.hitTest((float) p[0], (float) p[1]), "a back face is hit by default");
        assertTrue(painted.contains("w"), "and drawn");

        t.setBackfaceVisible(false);
        frame(scene);
        assertFalse(painted.contains("w"), "hidden: not drawn");
        assertTrue(canvas.matrices.isEmpty(), "and no picture made for it");
        assertNotSame(leaf, root.hitTest((float) p[0], (float) p[1]), "and not hit");
        assertFalse(leaf.isShowing(), "nor showing");

        t.setRotation3D(Vec3.UNIT_Y, 30);
        frame(scene);
        assertTrue(painted.contains("w"), "its front is drawn with back faces hidden");

        // A scale of -1 mirrors the same picture a half turn draws, with its face to the viewer.
        t.setRotation3D(Quat.IDENTITY).setScale(-1, 1);
        frame(scene);
        assertTrue(painted.contains("w"), "a mirror is drawn with back faces hidden");
        assertSame(leaf, root.hitTest(150, 150), "and hit");
        t.setScale(1).setRotation3D(Vec3.UNIT_Y, 180);
        frame(scene);
        assertFalse(painted.contains("w"), "the half turn is its back");
        assertNotSame(leaf, root.hitTest(150, 150));
    }

    @Test
    void aTurnOfExactlyNinetyDegreesDrawsNothingAndTakesNothing() {
        for (Vec3 axis : List.of(Vec3.UNIT_Y, Vec3.UNIT_X)) {
            for (float distance : new float[] {Float.POSITIVE_INFINITY, 800}) {
                String what = "about " + axis + " from " + distance;
                TransformTest.Leaf leaf = new Card("w", painted, 200, 100);
                Transform t = new Transform(leaf).setPerspective(distance);
                Stack root = at(t, 100, 100);
                Scene scene = sceneOf(root);
                t.setRotation3D(axis, 90);
                frame(scene);
                assertFalse(painted.contains("w"), what + ": nothing drawn");
                assertTrue(canvas.matrices.isEmpty(), what + ": and no picture made");
                assertNotSame(leaf, root.hitTest(200, 150), what + ": nothing taken in the middle");
                assertNotSame(leaf, root.hitTest(199.99f, 149.99f), what + ": nor beside it");
                assertFalse(leaf.isShowing(), what + ": not showing");
                t.setRotation3D(axis, -90);
                frame(scene);
                assertFalse(painted.contains("w"), what + ": nor the other way");
                t.setRotation3D(axis, 89);
                frame(scene);
                assertTrue(painted.contains("w"), what + ": a degree off, a sliver is drawn");
            }
        }
    }

    // ------------------------------------------------------------------ the flip

    @Test
    void aFlipTurnsInDepthOnceAPerspectiveIsSetAndFlatWithout() {
        for (float distance : new float[] {Float.POSITIVE_INFINITY, 900}) {
            TransformTest.Leaf leaf = new Card("w", painted, 300, 200);
            Transform t = new Transform(leaf).setPerspective(distance);
            Scene scene = sceneOf(at(t, 100, 100));
            AtomicInteger edges = new AtomicInteger();
            AtomicInteger arrivals = new AtomicInteger();
            t.flipTo(Transform.Flip.FORWARD, 0.4, edges::incrementAndGet, arrivals::incrementAndGet);
            advance(scene, 16);
            advance(scene, 80);
            boolean inDepth = distance != Float.POSITIVE_INFINITY;
            assertEquals(inDepth, !canvas.matrices.isEmpty(),
                    "from " + distance + ": a picture in perspective, straight without");
            // Forward in a left-to-right subtree the end edge, the right, comes toward the reader.
            float near = leaf.localToScene(300, 200).y() - leaf.localToScene(300, 0).y();
            float far = leaf.localToScene(0, 200).y() - leaf.localToScene(0, 0).y();
            if (inDepth) {
                assertTrue(near > 200 && far < 200, "the edge coming over grows: " + near + ", " + far);
            } else {
                assertEquals(200, near, EPS, "flat: no edge grows");
                assertEquals(200, far, EPS);
            }
            for (int i = 0; i < 40; i++) {
                advance(scene, 16);
            }
            assertEquals(1, edges.get(), "atEdge once");
            assertEquals(1, arrivals.get(), "onArrive once");
            assertNull(TransformAccess.childMatrix(t), "at rest after");
            assertEquals(distance, t.perspective(), "the perspective left as it was");
        }
    }

    @Test
    void underLessMotionAFlipInPerspectiveSwapsAfterItsSeconds() {
        long[] queueNow = {0};
        runtime.setDelayClock(() -> queueNow[0]);
        TransformTest.Leaf leaf = new Card("w", painted, 300, 200);
        Transform t = new Transform(leaf).setPerspective(900);
        Scene scene = sceneOf(at(t, 100, 100));
        AtomicInteger edges = new AtomicInteger();
        AtomicInteger arrivals = new AtomicInteger();
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            t.flipTo(Transform.Flip.UP, 0.4, edges::incrementAndGet, arrivals::incrementAndGet);
        }
        assertEquals(0, edges.get(), "no swap inside the call");
        assertEquals(0, arrivals.get());
        runtime.drain();
        assertEquals(0, edges.get(), "nor on the next turn");
        queueNow[0] += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(400);
        runtime.drain();
        assertEquals(1, edges.get(), "swapped after the seconds asked for");
        assertEquals(1, arrivals.get());
        advance(scene, 16);
        assertTrue(canvas.matrices.isEmpty(), "with no turn and no picture");
    }

    // ------------------------------------------------------------------ readers

    @Test
    void aReaderIsToldTheBoundsOfTheProjection() {
        TransformTest.Leaf leaf = new Card("w", painted, 200, 100);
        Transform t = new Transform(leaf);
        Stack root = at(t, 100, 100);
        AccessibleHarness harness = new AccessibleHarness(runtime, root);
        t.setPerspective(600).setRotation3D(Vec3.UNIT_X, 50);
        harness.frame();
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (double[] p : new double[][] {{0, 0}, {200, 0}, {200, 100}, {0, 100}}) {
            double[] d = drawn(100, 100, 100, 50, X, 50, 600, p[0], p[1]);
            x0 = Math.min(x0, d[0]);
            y0 = Math.min(y0, d[1]);
            x1 = Math.max(x1, d[0]);
            y1 = Math.max(y1, d[1]);
        }
        AccessibleNode node = harness.node("w");
        Rect bounds = node.bounds();
        assertEquals(x0, bounds.x(), EPS, "a reader is told the projection's bounds: " + bounds);
        assertEquals(y0, bounds.y(), EPS);
        assertEquals(x1 - x0, bounds.width(), EPS);
        assertEquals(y1 - y0, bounds.height(), EPS);
    }

    @Test
    void aTurnedChildInsideAClippingParentIsShowingWhereItsProjectionMeetsTheClip() {
        TransformTest.Leaf leaf = new Card("w", painted, 100, 60);
        Transform t = new Transform(leaf).setPerspective(300).setOrigin(0, 0);
        Stack root = new Stack();
        root.add(new SizedBox(120, 80, new limn.components.ScrollView(t)));
        Scene scene = sceneOf(root);
        assertTrue(leaf.isShowing());
        // Turned about its left edge, the right edge toward the viewer, and moved left past the
        // viewport: the projection lies wholly outside it.
        t.setRotation3D(Vec3.UNIT_Y, -60).setTranslation(-250, 0);
        frame(scene);
        assertFalse(leaf.isShowing(), "projected out of the viewport: not on screen");
        t.setTranslation(0, 0).setRotation3D(Vec3.UNIT_Y, 20);
        assertTrue(leaf.isShowing(), "a little turned, in it");
    }
}

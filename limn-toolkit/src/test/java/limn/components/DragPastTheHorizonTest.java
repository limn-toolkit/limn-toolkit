package limn.components;

import limn.input.Keys;
import limn.math.Vec2;
import limn.math.Vec3;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Transform;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A widget laid back in perspective has a horizon: a pointer dragged past it has no point on the
 * widget's plane at all, and {@code sceneToLocal} answers {@code NaN} there. The scene, which holds
 * the drag, does not hand such a point to the widget that has the pointer: the widget stays where
 * the pointer last was on its plane, the release arrives there, and a drag that comes back in
 * front of the horizon goes on from where it is. Without that rule a slider dragged past the
 * horizon took {@code NaN} as its value and committed it on release.
 */
class DragPastTheHorizonTest extends ComponentTestBase {

    private static final float W = 400;
    private static final float H = 300;

    /** A panel laid back about its middle, as the Animations guide's shapes are. */
    private static Transform laidBack(Widget<?> content) {
        return new Transform(content).setPerspective(150).setRotation3D(Vec3.UNIT_X, 60);
    }

    /** The scene's clock, which a test moves to step a motion. */
    private final long[] nanos = {1_000_000_000L};

    private Scene sceneOf(Widget<?> laidBack) {
        Scene scene = new Scene(new Padding(new Insets(200, 0, 0, 100), laidBack), () -> nanos[0]);
        scene.bind(new limn.testing.StubWindow());
        scene.setTextRuler(RULER);
        frame(scene);
        return scene;
    }

    private static void frame(Scene scene) {
        scene.layoutPass(W, H);
        scene.renderFrame(new FakeCanvas(W, H));
    }

    private static void move(Scene scene, float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
        frame(scene);
    }

    private static void button(Scene scene, boolean down, float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, down, 0, x, y, 1);
        drive(scene).inputBatchEnded();
        frame(scene);
    }

    private static boolean onPlane(Widget<?> w, float x, float y) {
        Vec2 local = w.sceneToLocal(x, y);
        return Float.isFinite(local.x()) && Float.isFinite(local.y());
    }

    @Test
    void aSliderDraggedPastTheHorizonKeepsAndCommitsItsLastValue() {
        Slider slider = new Slider(0, 100);
        List<Float> committed = new ArrayList<>();
        slider.onCommit(committed::add);
        Scene scene = sceneOf(laidBack(slider));
        Vec2 mid = slider.localToScene(slider.width() / 2, slider.height() / 2);
        // The scenario of the finding: past the horizon, which these numbers put well above the
        // middle, the pointer is on no point of the slider's plane.
        assertTrue(onPlane(slider, mid.x() + 30, mid.y() - 40), "40 points up is still in front of the horizon");
        assertFalse(onPlane(slider, mid.x() + 30, mid.y() - 200), "200 points up is past the horizon");

        move(scene, mid.x(), mid.y());
        button(scene, true, mid.x(), mid.y());
        move(scene, mid.x() + 30, mid.y() - 40);
        float lastValid = slider.value();
        assertNotEquals(50, lastValid, 1, "the drag in front of the horizon moves the value");

        move(scene, mid.x() + 30, mid.y() - 200);
        assertEquals(lastValid, slider.value(), "past the horizon the slider stays at its last valid value");
        button(scene, false, mid.x() + 30, mid.y() - 200);

        assertTrue(Float.isFinite(slider.value()), "a value of NaN was committed");
        assertTrue(slider.value() >= 0 && slider.value() <= 100, "the value is within [min, max]");
        assertEquals(lastValid, slider.value(), "the release arrives at the last valid point");
        assertEquals(List.of(lastValid), committed, "the commit carries the value the slider was showing");
    }

    @Test
    void aRangeSlidersThumbDraggedPastTheHorizonKeepsItsLastValue() {
        RangeSlider range = new RangeSlider(0, 100);
        List<String> committed = new ArrayList<>();
        range.onCommit((lo, hi) -> committed.add(lo + ".." + hi));
        Scene scene = sceneOf(laidBack(new SizedBox(200, 60, range)));
        // On the lower thumb, at the track's start.
        Vec2 thumb = range.localToScene(3, range.height() / 2);
        move(scene, thumb.x(), thumb.y());
        button(scene, true, thumb.x(), thumb.y());
        Vec2 along = range.localToScene(range.width() * 0.3f, range.height() / 2);
        move(scene, along.x(), along.y());
        float lastValid = range.lower();
        assertTrue(lastValid > 0, "the drag in front of the horizon moves the lower thumb: " + lastValid);

        assertFalse(onPlane(range, thumb.x() + 20, thumb.y() - 200), "200 points up is past the horizon");
        move(scene, thumb.x() + 20, thumb.y() - 200);
        assertEquals(lastValid, range.lower(), "past the horizon the thumb stays at its last valid value");
        button(scene, false, thumb.x() + 20, thumb.y() - 200);

        assertTrue(Float.isFinite(range.lower()), "a lower value of NaN was committed");
        assertEquals(lastValid, range.lower());
        assertEquals(100, range.upper());
        assertEquals(List.of(lastValid + "..100.0"), committed);
    }

    @Test
    void aDragThatComesBackInFrontOfTheHorizonGoesOn() {
        float straight = dragTo(false);
        assertNotEquals(50, straight, 1, "the control drag moves the value");
        assertEquals(straight, dragTo(true), "back on the plane, the drag goes on from where the pointer is");
    }

    /**
     * Presses a laid-back slider's middle and lets go of it at four fifths of its track, by way of
     * a point past the horizon or not, and answers its value.
     */
    private float dragTo(boolean pastTheHorizon) {
        Slider slider = new Slider(0, 100);
        Scene scene = sceneOf(laidBack(slider));
        Vec2 mid = slider.localToScene(slider.width() / 2, slider.height() / 2);
        Vec2 right = slider.localToScene(slider.width() * 0.8f, slider.height() / 2);
        move(scene, mid.x(), mid.y());
        button(scene, true, mid.x(), mid.y());
        if (pastTheHorizon) {
            move(scene, mid.x() + 30, mid.y() - 200);
            assertEquals(50, slider.value(), 0.01f, "nothing was delivered past the horizon");
        }
        move(scene, right.x(), right.y());
        button(scene, false, right.x(), right.y());
        return slider.value();
    }

    /** Records the local point of every pointer event it receives, and takes the press. */
    private static final class Recorder extends Widget<Recorder> {
        final List<String> events = new ArrayList<>();
        final List<Vec2> points = new ArrayList<>();
        final List<Vec2> scenePoints = new ArrayList<>();

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(200, 40);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            events.add(event.type().name());
            points.add(sceneToLocal(event.x(), event.y()));
            scenePoints.add(new Vec2(event.x(), event.y()));
            if (event.type() == MouseEvent.Type.PRESS) {
                event.consume();
            }
        }

        Vec2 lastScene(String type) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).equals(type)) {
                    return scenePoints.get(i);
                }
            }
            throw new AssertionError("no " + type + " in " + events);
        }

        Vec2 last(String type) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).equals(type)) {
                    return points.get(i);
                }
            }
            throw new AssertionError("no " + type + " in " + events);
        }

        /** The pointer's own events, PRESS to RELEASE, carry points of the plane; a hover's EXIT need not. */
        void assertCapturedFinite() {
            for (int i = 0; i < points.size(); i++) {
                if (events.get(i).equals("EXIT")) {
                    continue;
                }
                Vec2 p = points.get(i);
                assertTrue(Float.isFinite(p.x()) && Float.isFinite(p.y()),
                        events.get(i) + " arrived at " + p + " in " + events);
            }
        }
    }

    @Test
    void everyEventOfACapturedDragMapsOntoTheWidgetsPlane() {
        Recorder recorder = new Recorder();
        Scene scene = sceneOf(laidBack(recorder));
        Vec2 mid = recorder.localToScene(100, 20);
        move(scene, mid.x(), mid.y());
        button(scene, true, mid.x(), mid.y());
        move(scene, mid.x() + 10, mid.y() - 20);
        Vec2 lastDrag = recorder.last("DRAG");
        int delivered = recorder.events.size();
        move(scene, mid.x() + 10, mid.y() - 200);
        move(scene, mid.x() + 40, mid.y() - 260);
        assertEquals(delivered, recorder.events.size(), "no DRAG is delivered past the horizon: " + recorder.events);
        button(scene, false, mid.x() + 40, mid.y() - 260);

        recorder.assertCapturedFinite();
        assertEquals(lastDrag, recorder.last("RELEASE"), "the release arrives at the last point on the plane");
        assertFalse(recorder.events.contains("CLICK"), "a release past the horizon is not a click on the widget");
    }

    @Test
    void aReleaseTheSceneMakesUpPastTheHorizonArrivesOnThePlaneToo() {
        // The window losing the focus mid-drag: the scene releases the press itself, at the
        // pointer's last position, which is past the horizon.
        Recorder recorder = new Recorder();
        Scene scene = sceneOf(laidBack(recorder));
        Vec2 mid = recorder.localToScene(100, 20);
        move(scene, mid.x(), mid.y());
        button(scene, true, mid.x(), mid.y());
        move(scene, mid.x() + 10, mid.y() - 20);
        Vec2 lastDrag = recorder.last("DRAG");
        move(scene, mid.x() + 10, mid.y() - 200);
        drive(scene).windowFocusChanged(false);
        drive(scene).inputBatchEnded();
        frame(scene);

        recorder.assertCapturedFinite();
        assertEquals(lastDrag, recorder.last("RELEASE"));
    }

    @Test
    void aPointerPastTheHorizonWithNothingPressedDoesNotHitTheWidget() {
        Recorder recorder = new Recorder();
        Scene scene = sceneOf(laidBack(recorder));
        Vec2 mid = recorder.localToScene(100, 20);
        move(scene, mid.x(), mid.y());
        assertTrue(recorder.events.contains("ENTER"), "the pointer on the plane hovers the widget");
        move(scene, mid.x() + 10, mid.y() - 200);
        assertNotSame(recorder, scene.root().hitTest(mid.x() + 10, mid.y() - 200));
        int seen = recorder.events.size();
        move(scene, mid.x() + 40, mid.y() - 260);
        assertEquals("EXIT", recorder.events.get(seen - 1), "leaving for past the horizon is an exit");
        assertEquals(seen, recorder.events.size(), "no MOVE reaches the widget past the horizon: " + recorder.events);
    }

    // ---------------------------------------------------------- a plane that moves under a press

    /**
     * Presses a recorder on a panel tilted back 30 degrees and drags it a little; {@code turn} then
     * moves the plane with the pointer held still, and {@code letGo} ends the press. Answers the
     * recorder, whose last DRAG is where the press was left.
     */
    private Recorder pressThenMoveThePlane(java.util.function.Consumer<Transform> turn,
                                           java.util.function.BiConsumer<Scene, Recorder> letGo) {
        Recorder recorder = new Recorder();
        limn.scene.layout.Column holder = new limn.scene.layout.Column();
        holder.add(recorder);
        Transform panel = new Transform(holder).setPerspective(150).setRotation3D(Vec3.UNIT_X, 30);
        Scene scene = sceneOf(panel);
        Vec2 mid = recorder.localToScene(100, 20);
        move(scene, mid.x(), mid.y());
        button(scene, true, mid.x(), mid.y());
        float x = mid.x() + 40;
        float y = mid.y() - 60;
        assertTrue(onPlane(recorder, x, y), "the drag point is on the plane at 30 degrees");
        move(scene, x, y);
        turn.accept(panel);
        frame(scene);
        letGo.accept(scene, recorder);
        frame(scene);
        return recorder;
    }

    private static void letGoInPlace(Scene scene, Recorder recorder) {
        // Where the pointer is: the last point it was moved to.
        Vec2 at = recorder.lastScene("DRAG");
        button(scene, false, at.x(), at.y());
    }

    @Test
    void aReleaseAfterThePlaneTurnedOnUnderAStillPointerArrivesWhereTheWidgetWasLeft() {
        java.util.function.Consumer<Transform> turnOn = panel -> panel.setRotation3D(Vec3.UNIT_X, 75);
        java.util.Map<String, java.util.function.BiConsumer<Scene, Recorder>> endings = new java.util.LinkedHashMap<>();
        endings.put("let go of", DragPastTheHorizonTest::letGoInPlace);
        endings.put("window blurred", (scene, r) -> drive(scene).windowFocusChanged(false));
        endings.put("widget disabled", (scene, r) -> r.setEnabled(false));
        endings.put("widget removed", (scene, r) -> ((limn.scene.layout.Column) r.parent()).remove(r));
        for (var ending : endings.entrySet()) {
            Recorder recorder = pressThenMoveThePlane(turnOn, (scene, r) -> {
                Vec2 at = r.lastScene("DRAG");
                assertFalse(onPlane(r, at.x(), at.y()), "the point the drag was left at is past the new horizon");
                ending.getValue().accept(scene, r);
                drive(scene).inputBatchEnded();
            });
            recorder.assertCapturedFinite();
            if (recorder.parent() == null) {
                continue; // released as it left: no plane of the scene to compare against any more
            }
            Vec2 dragged = recorder.last("DRAG");
            Vec2 released = recorder.last("RELEASE");
            assertEquals(dragged.x(), released.x(), 0.01f, ending.getKey() + ": " + recorder.events);
            assertEquals(dragged.y(), released.y(), 0.01f, ending.getKey());
        }
    }

    @Test
    void aReleaseWhileAFlipTurnsThePlaneArrivesOnIt() {
        Recorder recorder = pressThenMoveThePlane(
                panel -> {
                    panel.flipTo(Transform.Flip.UP, 1.0, () -> { }, null);
                    // A third of the way over, frame by frame: under way and not yet edge-on.
                    for (int ms = 0; ms < 350; ms += 10) {
                        frame(panel.scene());
                        nanos[0] += 10_000_000L;
                    }
                },
                (scene, r) -> {
                    Vec2 at = r.lastScene("DRAG");
                    assertFalse(onPlane(r, at.x(), at.y()), "the flip took the drag's point past the horizon");
                    letGoInPlace(scene, r);
                });
        assertEquals(1, recorder.events.stream().filter("RELEASE"::equals).count(), recorder.events.toString());
        recorder.assertCapturedFinite();
        Vec2 dragged = recorder.last("DRAG");
        Vec2 released = recorder.last("RELEASE");
        assertEquals(dragged.x(), released.x(), 0.05f, "the release arrives where the widget was left");
        assertEquals(dragged.y(), released.y(), 0.05f);
    }

    @Test
    void aPlaneTurnedEdgeOnStillHearsItsRelease() {
        // No point of an edge-on plane can be reached: the release still ends the press, at the
        // last scene point, which MouseEvent documents as the one exception.
        Recorder recorder = pressThenMoveThePlane(panel -> panel.setRotation3D(Vec3.UNIT_X, 90),
                DragPastTheHorizonTest::letGoInPlace);
        assertEquals(1, recorder.events.stream().filter("RELEASE"::equals).count(), recorder.events.toString());
    }
}

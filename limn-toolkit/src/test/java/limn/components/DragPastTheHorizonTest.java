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

    private Scene sceneOf(Widget<?> laidBack) {
        Scene scene = new Scene(new Padding(new Insets(200, 0, 0, 100), laidBack));
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

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(200, 40);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            events.add(event.type().name());
            points.add(sceneToLocal(event.x(), event.y()));
            if (event.type() == MouseEvent.Type.PRESS) {
                event.consume();
            }
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
}

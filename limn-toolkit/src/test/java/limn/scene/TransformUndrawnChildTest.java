package limn.scene;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.math.Vec2;
import limn.math.Vec3;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.Stack;
import limn.scene.layout.Transform;
import limn.testing.AccessibleHarness;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A child a {@link Transform} draws as nothing, its back face turned to the viewer while back faces
 * are hidden, a scale of 0 on either axis, its plane edge-on, is out of reach as a hidden widget is
 * (the owner's decision 16 on the audit of 2026-10-19, finding TR-3; ADR 072 §2.8): not
 * {@link Widget#isShowing() showing}, out of the Tab order and the pointer's reach, the focus, the
 * hover and a press taken out of it, published hidden; and back by itself when the face turns, or
 * the scale returns. And the two ways the record teaches to show two faces one at a time: back to
 * back with back faces hidden, and a flip that swaps them at its edge.
 */
class TransformUndrawnChildTest extends SceneTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final NoopCanvas canvas = new NoopCanvas(1000, 800);

    /** A focusable face that counts its presses and releases and names itself to a reader. */
    private static final class Face extends Widget<Face> {
        final String name;
        int presses;
        int releases;
        int lost;
        /** An application's bug: its focus-lost handler throws. */
        boolean throwOnLost;
        /** Run first thing as the focus is lost, or null. */
        Runnable whenLost;

        Face(String name) {
            this.name = name;
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(120, 60);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), Color.BLACK);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.PRESS) {
                presses++;
                event.consume();
            } else if (event.type() == MouseEvent.Type.RELEASE) {
                releases++;
            }
        }

        @Override
        protected void onFocusLost() {
            lost++;
            if (whenLost != null) {
                whenLost.run();
            }
            if (throwOnLost) {
                throw new IllegalStateException("an application's focus-lost handler throws");
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.BUTTON);
            a.name(I18nString.literal(name));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** The ways a container draws its child as nothing, each with the set that draws it again. */
    enum Undrawn {
        BACK_FACE_TURNED_AWAY {
            @Override
            void prepare(Transform t) {
                t.setPerspective(600).setBackfaceVisible(false);
            }

            @Override
            void undraw(Transform t) {
                t.setRotation3D(Vec3.UNIT_Y, 180);
            }

            @Override
            void redraw(Transform t) {
                t.setRotation3D(Vec3.UNIT_Y, 0);
            }
        },
        BACK_FACE_HIDDEN {
            @Override
            void prepare(Transform t) {
                t.setPerspective(600).setRotation3D(Vec3.UNIT_X, 150);
            }

            @Override
            void undraw(Transform t) {
                t.setBackfaceVisible(false);
            }

            @Override
            void redraw(Transform t) {
                t.setBackfaceVisible(true);
            }
        },
        SCALED_TO_NOTHING_ACROSS {
            @Override
            void undraw(Transform t) {
                t.setScale(0, 1);
            }

            @Override
            void redraw(Transform t) {
                t.setScale(1);
            }
        },
        SCALED_TO_NOTHING_DOWN {
            @Override
            void undraw(Transform t) {
                t.setScale(1, 0);
            }

            @Override
            void redraw(Transform t) {
                t.setScale(1);
            }
        },
        EDGE_ON {
            @Override
            void prepare(Transform t) {
                t.setPerspective(600);
            }

            @Override
            void undraw(Transform t) {
                t.setRotation3D(Vec3.UNIT_Y, 90);
            }

            @Override
            void redraw(Transform t) {
                t.setRotation3D(Vec3.UNIT_Y, 30);
            }
        };

        void prepare(Transform t) {
        }

        abstract void undraw(Transform t);

        abstract void redraw(Transform t);
    }

    private Scene sceneOf(Widget<?> root) {
        Scene scene = new Scene(root, () -> now[0]);
        scene.bind(new RecordingWindow());
        scene.setPartialRendering(true);
        frame(scene);
        return scene;
    }

    private void frame(Scene scene) {
        scene.renderFrame(canvas, false, Float.NaN, 1);
    }

    /** A face before, the transformed one, a face after, in a column. */
    private static Column column(Face before, Transform t, Face after) {
        Column column = new Column();
        column.add(before);
        column.add(new Padding(new Insets(20, 0, 20, 0), t));
        column.add(after);
        return column;
    }

    private static Vec2 middleOf(Widget<?> widget) {
        return widget.localToScene(widget.width() / 2, widget.height() / 2);
    }

    @ParameterizedTest
    @EnumSource(Undrawn.class)
    void aChildDrawnAsNothingIsNotShowingAndLeavesTheTabOrderThePointerAndTheFocus(Undrawn way) {
        Face before = new Face("before");
        Face inside = new Face("inside");
        Face after = new Face("after");
        Transform t = new Transform(inside);
        way.prepare(t);
        Scene scene = sceneOf(column(before, t, after));
        Vec2 middle = middleOf(t); // the container's middle, where the child is drawn at rest
        assertTrue(inside.isShowing(), way + ": drawn, so showing");
        scene.requestFocus(inside);
        assertSame(inside, scene.focusedWidget(), way + ": and focusable");

        way.undraw(t);
        frame(scene);
        assertFalse(inside.isShowing(), way + ": drawn as nothing, not showing");
        assertNull(scene.focusedWidget(), way + ": the focus taken out of it, as out of a hidden widget");
        scene.requestFocus(inside);
        assertNull(scene.focusedWidget(), way + ": and refused it");
        scene.requestFocus(before);
        scene.focusTraverse(false);
        assertSame(after, scene.focusedWidget(), way + ": Tab passes it by");
        scene.focusTraverse(true);
        assertSame(before, scene.focusedWidget(), way + ": and so does Shift+Tab");
        assertNotSame(inside, scene.root().hitTest(middle.x(), middle.y()), way + ": the pointer passes it by");
        drive(scene).click(middle.x(), middle.y());
        assertEquals(0, inside.presses, way + ": a click there does not reach it");

        way.redraw(t);
        frame(scene);
        assertTrue(inside.isShowing(), way + ": drawn again, showing again, by itself");
        scene.requestFocus(before);
        scene.focusTraverse(false);
        assertSame(inside, scene.focusedWidget(), way + ": back in the Tab order");
        Vec2 again = middleOf(inside);
        assertSame(inside, scene.root().hitTest(again.x(), again.y()), way + ": and in the pointer's reach");
    }

    @Test
    void aPressHeldOnAFaceThatTurnsAwayIsReleasedAsOnAHiddenOne() {
        Face face = new Face("face");
        Transform t = new Transform(face).setPerspective(600).setBackfaceVisible(false);
        Scene scene = sceneOf(column(new Face("before"), t, new Face("after")));
        Vec2 middle = middleOf(face);
        limn.testing.SceneDriver driver = drive(scene);
        driver.mouseMoved(middle.x(), middle.y());
        driver.inputBatchEnded();
        driver.mouseButton(Keys.MOUSE_LEFT, true, 0, middle.x(), middle.y(), 1);
        driver.inputBatchEnded();
        assertEquals(1, face.presses);
        t.setRotation3D(Vec3.UNIT_Y, 180);
        assertEquals(1, face.releases, "the press is ended there and then, as a hidden widget's is");
    }

    @Test
    void aReaderIsToldAChildDrawnAsNothingIsHiddenAndThatItIsBack() {
        Face face = new Face("face");
        Transform t = new Transform(face);
        AccessibleHarness harness = new AccessibleHarness(runtime, column(new Face("before"), t, new Face("after")));
        AccessibleNode shown = harness.node("face");
        assertTrue(shown.has(Accessible.State.VISIBLE) && shown.has(Accessible.State.SHOWING)
                && shown.has(Accessible.State.FOCUSABLE), harness.describe());

        t.setScale(0);
        harness.frame();
        AccessibleNode collapsed = harness.node("face");
        assertFalse(collapsed.has(Accessible.State.SHOWING), "not showing: " + collapsed.states());
        assertFalse(collapsed.has(Accessible.State.VISIBLE), "published as a hidden branch is: " + collapsed.states());
        assertFalse(collapsed.has(Accessible.State.FOCUSABLE), "not a tab stop: " + collapsed.states());
        assertFalse(collapsed.accepts(Accessible.Action.FOCUS), "and no verb a reader could send it");
        assertFalse(harness.perform(collapsed.id(), Accessible.Action.FOCUS, Accessible.Argument.NONE)
                && harness.scene.focusedWidget() == face, "a verb from an older snapshot is refused");
        assertNull(harness.scene.focusedWidget());

        t.setScale(1);
        harness.frame();
        AccessibleNode back = harness.node("face");
        assertTrue(back.has(Accessible.State.VISIBLE) && back.has(Accessible.State.SHOWING)
                && back.has(Accessible.State.FOCUSABLE), "back: " + back.states());
    }

    @Test
    void aReaderIsToldOnTheFrameAMotionDrawsTheChildAgainAndNotOnlyAtItsEnd() {
        Face face = new Face("face");
        Transform t = new Transform(face).setScale(0);
        AccessibleHarness harness = new AccessibleHarness(runtime, column(new Face("before"), t, new Face("after")));
        assertFalse(harness.node("face").has(Accessible.State.SHOWING));
        t.scaleTo(1, 1, 0.5, null);
        harness.advanceTime(16, null); // the motion's first frame, its start: still 0
        harness.advanceTime(48, null); // a frame of the way, scale above 0, far from its end
        assertTrue(t.scaleX() > 0 && t.scaleX() < 1, "under way: " + t.scaleX());
        AccessibleNode node = harness.node("face");
        assertTrue(node.has(Accessible.State.SHOWING) && node.has(Accessible.State.FOCUSABLE),
                "published drawn from the frame it is drawn on: " + node.states());
    }

    // ------------------------------------------------------------------ from a motion

    private void step(Scene scene, int frames) {
        for (int i = 0; i < frames; i++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(16);
            frame(scene);
        }
    }

    @Test
    void aMotionFrameThatDrawsAFocusedChildAsNothingTakesTheFocusAfterTheFramesMotionsAndOutlivesAThrow() {
        Face face = new Face("face");
        face.throwOnLost = true;
        Transform t = new Transform(face);
        Scene scene = sceneOf(column(new Face("before"), t, new Face("after")));
        scene.requestFocus(face);
        List<String> seen = new ArrayList<>();
        face.whenLost = () -> seen.add("focus lost at scale " + t.scaleX());
        t.scaleTo(0, 1, 0.1, null);
        step(scene, 12);
        assertEquals(0f, t.scaleX(), "collapsed");
        assertEquals(1, face.lost, "the focus taken out of it once, its handler's throw contained");
        assertNull(scene.focusedWidget());
        assertEquals(List.of("focus lost at scale 0.0"), seen, "after the frame had set the matrix");
        t.scaleTo(1, 1, 0.1, null);
        step(scene, 12);
        assertEquals(1f, t.scaleX(), "a later motion still moves");
        t.slideTo(30, 0, 0.1, null);
        step(scene, 12);
        assertEquals(30f, t.translationX(), "and another kind too");
    }

    @Test
    void aScaleThatArrivesAtNothingTakesTheFocusAndOneThatOnlyPassesThroughItDoesNot() {
        // As scaleTo's Javadoc and the Animations guide say: the frames of a motion do not land on
        // 0 on the way to a mirror, and its arrival lands exactly on its target.
        Face face = new Face("face");
        Transform t = new Transform(face);
        Scene scene = sceneOf(column(new Face("before"), t, new Face("after")));
        scene.requestFocus(face);
        t.scaleTo(-1, 1, 0.3, null);
        step(scene, 30);
        assertEquals(-1f, t.scaleX(), "mirrored");
        assertSame(face, scene.focusedWidget(), "passing through 0 keeps the focus");
        t.scaleTo(0, 1, 0.3, null);
        step(scene, 30);
        assertEquals(0f, t.scaleX());
        assertNull(scene.focusedWidget(), "arriving at 0 takes it");
    }

    @Test
    void aContainerRemovedWhileItsMotionDrawsTheChildAsNothingLosesTheFocusAfterTheDetachWalk() {
        Face face = new Face("face");
        Transform t = new Transform(face);
        Column column = column(new Face("before"), t, new Face("after"));
        Scene scene = sceneOf(column);
        scene.requestFocus(face);
        List<String> seen = new ArrayList<>();
        face.whenLost = () -> seen.add("in a scene: " + (face.scene() != null));
        t.scaleTo(0, 0, 0.3, null);
        step(scene, 2);
        Widget<?> wrapper = t.parent();
        column.remove(wrapper); // settles the scale at 0 inside the walk that takes it out
        assertEquals(1, face.lost, "the focus lost once");
        assertNull(scene.focusedWidget());
        assertEquals(List.of("in a scene: false"), seen, "and only once the walk was over");
    }

    // ------------------------------------------------------------------ two faces

    @Test
    void twoFacesBackToBackShowExactlyOneAtATimeAndNeitherExactlyEdgeOn() {
        Face lead = new Face("lead");
        Face front = new Face("front");
        Face back = new Face("back");
        Transform frontSide = new Transform(front).setPerspective(500).setBackfaceVisible(false);
        Transform backSide = new Transform(back).setPerspective(500).setBackfaceVisible(false);
        Stack card = new Stack();
        card.add(frontSide);
        card.add(backSide);
        Column column = new Column();
        column.add(lead);
        column.add(new Padding(new Insets(20, 0, 0, 0), card));
        Scene scene = sceneOf(column);
        List<Float> angles = new ArrayList<>();
        for (float a = 0; a <= 360; a += 7.5f) {
            angles.add(a);
        }
        angles.addAll(List.of(89.9f, 90.1f, 269.9f, 270.1f));
        for (float a : angles) {
            frontSide.setRotation3D(Vec3.UNIT_Y, a);
            backSide.setRotation3D(Vec3.UNIT_Y, a + 180);
            frame(scene);
            Vec2 middle = middleOf(card);
            Widget<?> hit = scene.root().hitTest(middle.x(), middle.y());
            scene.requestFocus(lead);
            scene.focusTraverse(false);
            Widget<?> tabbed = scene.focusedWidget();
            if (a == 90 || a == 270) {
                // Exactly edge-on, both planes lie along the line of sight: neither is drawn, so
                // neither shows, takes the pointer or the keyboard, for that one angle.
                assertFalse(front.isShowing() || back.isShowing(), a + ": edge-on, neither shows");
                assertSame(lead, tabbed, a + ": and Tab finds neither");
                assertTrue(hit != front && hit != back, a + ": nor the pointer");
                continue;
            }
            Face shown = front.isShowing() ? front : back;
            assertTrue(front.isShowing() != back.isShowing(), a + ": exactly one face shows");
            assertSame(Math.cos(Math.toRadians(a)) > 0 ? front : back, shown, a + ": the one turned to the viewer");
            assertSame(shown, tabbed, a + ": Tab lands on it alone");
            assertSame(shown, hit, a + ": and the pointer");
        }
    }

    @Test
    void aFlipThatSwapsTwoFacesAtItsEdgeShowsAtMostOneOnEveryFrame() {
        Face lead = new Face("lead");
        Face front = new Face("front");
        Face back = new Face("back");
        back.setVisible(false);
        Stack faces = new Stack();
        faces.add(front);
        faces.add(back);
        Transform t = new Transform(faces).setPerspective(500);
        Column column = new Column();
        column.add(lead);
        column.add(new Padding(new Insets(20, 0, 0, 0), t));
        Scene scene = sceneOf(column);
        scene.requestFocus(front);
        t.flipTo(Transform.Flip.FORWARD, 0.4, () -> {
            front.setVisible(false);
            back.setVisible(true);
        }, null);
        boolean swapped = false;
        for (int i = 0; i < 40; i++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(16);
            frame(scene);
            swapped |= back.isVisible();
            boolean neither = !front.isShowing() && !back.isShowing();
            assertFalse(front.isShowing() && back.isShowing(), "frame " + i + ": never both");
            assertTrue(!neither || ((Widget<?>) t).drawsNoChildren(),
                    "frame " + i + ": one shows, unless the plane is exactly edge-on on this frame");
        }
        assertTrue(swapped && back.isShowing() && !front.isShowing(), "flat again, the back shows");
        assertNull(scene.focusedWidget(), "the focus left the front face as it was hidden at the edge");
        scene.requestFocus(lead);
        scene.focusTraverse(false);
        assertSame(back, scene.focusedWidget(), "and Tab reaches the back");
    }
}

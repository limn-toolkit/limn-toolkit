package limn.demo.site;

import limn.demo.site.AnimationExample.Breathing;
import limn.demo.site.AnimationExample.Dial;
import limn.demo.site.AnimationExample.Lamp;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the animations guide says its examples do, on a clock the test moves. */
class AnimationExampleTest {

    private HeadlessUi ui;
    private long nanos = 1_000_000_000L;
    private Scene scene;

    @BeforeEach
    void install() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstall() {
        ui.close();
    }

    private void show(Widget<?> widget) {
        Column root = new Column();
        root.add(widget);
        scene = new Scene(root, () -> nanos);
        scene.setTextRuler(TestRulers.FIXED);
        frame(0);
    }

    /** Lets {@code millis} go by and draws a frame. */
    private void frame(long millis) {
        nanos += millis * 1_000_000L;
        scene.layoutPass(400, 200);
        scene.renderFrame(new NoopCanvas(400, 200));
    }

    @Test
    void aLampOutsideAnySceneIsOnAtOnceAndOneInASceneGetsThereOverItsDuration() {
        Lamp loose = new Lamp().setOn(true);
        assertEquals(1f, loose.brightness(), "with no scene there is no frame to animate over");

        Lamp lamp = new Lamp();
        show(lamp);
        lamp.setOn(true);
        assertEquals(0f, lamp.brightness(), "sent, and not there yet");
        frame(0);      // the transition's first frame starts its clock
        frame(90);
        float halfway = lamp.brightness();
        assertTrue(halfway > 0f && halfway < 1f, "part of the way after half its duration: " + halfway);
        frame(120);
        assertEquals(1f, lamp.brightness(), "and there once the duration has gone by");

        lamp.setOn(false);
        frame(0);
        frame(60);
        lamp.setOn(true);
        float turnedBack = lamp.brightness();
        assertTrue(turnedBack > 0f && turnedBack < 1f,
                "sent somewhere else on the way, it goes from where it is: " + turnedBack);
    }

    @Test
    void aNoticeFadesInAsOnePictureAndIsHiddenWhenItsFadeOutArrives() {
        limn.scene.layout.Opacity notice = AnimationExample.noticeOf(new Lamp());
        assertTrue(!notice.isVisible(), "hidden to start with");
        show(notice);
        scene.bind(new limn.testing.StubWindow()); // a window: frames to fade over
        AnimationExample.showNotice(notice);
        assertTrue(notice.isVisible());
        assertEquals(0f, notice.opacity(), "shown at nothing, fading in");
        frame(0);
        frame(80);
        assertTrue(notice.opacity() > 0f && notice.opacity() < 1f, "part of the way: " + notice.opacity());
        frame(200);
        assertEquals(1f, notice.opacity(), "there once the window animation has gone by");

        AnimationExample.dismissNotice(notice);
        frame(0);
        frame(120);
        assertTrue(notice.isVisible(), "still shown while it fades");
        frame(200);
        assertEquals(0f, notice.opacity());
        assertTrue(!notice.isVisible(), "and hidden when the fade arrives, so nothing invisible takes a click");
    }

    @Test
    void aDrawerSlidesInFromTheEndEdgeAndIsHiddenWhenItSlidesOut() {
        Lamp inside = new Lamp();
        limn.scene.layout.Transform drawer = AnimationExample.drawerOf(inside);
        assertTrue(!drawer.isVisible(), "hidden to start with");
        show(drawer);
        scene.bind(new limn.testing.StubWindow()); // a window: frames to slide over
        AnimationExample.openDrawer(drawer);
        assertTrue(drawer.isVisible());
        assertEquals(AnimationExample.DRAWER_WIDTH, drawer.translationX(), "out of sight, about to come in");
        frame(0);
        frame(80);
        float part = drawer.translationX();
        assertTrue(part > 0 && part < AnimationExample.DRAWER_WIDTH, "part of the way: " + part);
        assertEquals(drawer.localToScene(0, 0).x(), drawer.x(), "where layout put it, which the slide does not move");
        float drawnAt = inside.localToScene(0, 0).x();
        assertEquals(drawer.x() + part, drawnAt, 1e-3f, "the content is drawn, and found, where the slide has it");
        frame(200);
        assertEquals(0f, drawer.translationX(), "in, once the window animation has gone by");

        AnimationExample.closeDrawer(drawer);
        frame(0);
        frame(100);
        assertTrue(drawer.isVisible(), "still shown while it slides out");
        frame(200);
        assertEquals(AnimationExample.DRAWER_WIDTH, drawer.translationX());
        assertTrue(!drawer.isVisible(), "and hidden when it arrives, so nothing out of sight takes a click");
    }

    @Test
    void aCardTurnsOverAndShowsItsBackOnceItIsEdgeOn() {
        Lamp front = new Lamp();
        Lamp back = new Lamp();
        back.setVisible(false);
        limn.scene.layout.Stack faces = new limn.scene.layout.Stack();
        faces.add(front);
        faces.add(back);
        limn.scene.layout.Transform card = new limn.scene.layout.Transform(faces);
        show(card);
        scene.bind(new limn.testing.StubWindow());
        AnimationExample.turnOver(card, front, back);
        frame(0);
        frame(40);
        assertTrue(front.isVisible() && !back.isVisible(), "the front, until the card is edge-on");
        frame(200);
        frame(200);
        assertTrue(!front.isVisible() && back.isVisible(), "and the back once it has turned over");
    }

    @Test
    void aSectionsArrowTurnsDownAsItOpensClockwiseOrNotByTheDirection() {
        Lamp shape = new Lamp();
        limn.scene.layout.Transform arrow = new limn.scene.layout.Transform(shape);
        show(arrow);
        scene.bind(new limn.testing.StubWindow());
        AnimationExample.showOpen(arrow, true);
        frame(0);
        frame(60);
        float part = arrow.rotation();
        assertTrue(part > 0 && part < 90, "part of the way round: " + part);
        frame(300);
        assertEquals(90f, arrow.rotation(), "a quarter turn clockwise, from the right to down");
        AnimationExample.showOpen(arrow, false);
        frame(0);
        frame(300);
        assertEquals(0f, arrow.rotation(), "and back as it closes");

        limn.scene.layout.Transform mirrored = new limn.scene.layout.Transform(new Lamp());
        mirrored.setLayoutDirection(limn.scene.LayoutDirection.RTL);
        show(mirrored);
        scene.bind(new limn.testing.StubWindow());
        AnimationExample.showOpen(mirrored, true);
        frame(0);
        frame(300);
        assertEquals(-90f, mirrored.rotation(), "from the left to down is a quarter turn the other way");
    }

    @Test
    void theGuidesOwnTestOfALampFindsItPartOfTheWay() {
        assertTrue(AnimationExample.isPartWayAfter90Millis(new Lamp()));
    }

    @Test
    void aBreathingDotGoesOnUntilItIsToldToStop() {
        Breathing dot = new Breathing();
        show(dot);
        dot.setWaiting(true);
        for (int i = 0; i < 60; i++) {
            frame(50);   // three seconds: more than three of its 0.9 s legs
        }
        assertTrue(dot.isBreathing(), "a repeating transition never settles by itself");

        dot.setWaiting(false);
        frame(50);
        assertTrue(!dot.isBreathing(), "snapped, it is at rest");
    }

    @Test
    void aDialTurnsWithTheScenesClockAndStandsStillWhileTheSceneIsPaused() {
        Dial dial = new Dial();
        show(dial);
        frame(0);
        for (int i = 0; i < 5; i++) {
            frame(100);
        }
        double turned = dial.turn();
        assertTrue(turned > 0.15 && turned < 0.3, "a quarter of a turn in half a second: " + turned);

        scene.setPaused(true);
        for (int i = 0; i < 5; i++) {
            frame(100);
        }
        assertEquals(turned, dial.turn(), 1e-9, "a paused scene's tickers get no time");

        scene.setPaused(false);
        scene.setTimeScale(0.5);
        for (int i = 0; i < 4; i++) {
            frame(100);
        }
        double slow = dial.turn() - turned;
        assertTrue(slow > 0.05 && slow < 0.15, "and at half speed, half the way: " + slow);
    }
}

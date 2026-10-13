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

package limn.scene;

import limn.animation.Easing;
import limn.animation.Transition;
import limn.concurrent.UiRuntime;
import limn.input.Keys;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a scene comes back to after the UI thread has been parked inside a
 * blocking native call. The system file chooser is the one that happens in
 * practice: it can hold the thread for as long as the user browses, and while
 * it does, no frame runs, no timer fires, and the OS hands input focus to
 * whatever process draws the panel.
 *
 * <p>Nothing here opens a dialog: a chooser needs a user, and on macOS and
 * Linux it is a whole other program. It does not need to: to this side, a
 * dialog <em>is</em> a stretch of wall time with no frames in it, ended by a
 * focus event. A clock that jumps ten seconds and a
 * {@code windowFocusChanged(false)} reproduce it exactly.
 *
 * <p>Each test is one reason a caller has to reset <b>nothing</b> after a
 * blocking call returns. Together they are why the toolkit can afford to say
 * "it blocks" and stop there.
 */
class BlockingNativeCallTest extends SceneTestBase {

    private static final long TEN_SECONDS_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** Root widget that records every event dispatched to it. */
    private static final class Recorder extends FixedBox {
        final List<KeyEvent> keys = new ArrayList<>();
        final List<MouseEvent> mouse = new ArrayList<>();

        Recorder() {
            super(200, 200);
            setFocusable(true);
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            keys.add(event);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            mouse.add(event);
        }

        List<Integer> releasedKeys() {
            List<Integer> out = new ArrayList<>();
            for (KeyEvent event : keys) {
                if (!event.isPressed()) {
                    out.add(event.key());
                }
            }
            return out;
        }

        List<MouseEvent.Type> mouseTypes() {
            List<MouseEvent.Type> out = new ArrayList<>();
            for (MouseEvent event : mouse) {
                out.add(event.type());
            }
            return out;
        }
    }

    private Recorder recorder;
    private Scene scene;

    private Recorder attach() {
        recorder = new Recorder();
        scene = new Scene(recorder);
        scene.bind(new RecordingWindow());
        scene.layoutPass(200, 200);
        return recorder;
    }

    /** The scene sees a batch exactly as the event loop delivers one. */
    private void batch(Runnable events) {
        events.run();
        scene.inputBatchEnded();
    }

    // ------------------------------------------------- keys and the pointer

    @Test
    void everyKeyHeldWhenTheChooserTookFocusComesBackReleased() {
        Recorder r = attach();
        batch(() -> {
            scene.keyEvent(Keys.S, true, false, 0);
            scene.keyEvent(Keys.LEFT_CONTROL, true, false, Keys.MOD_CONTROL);
        });
        r.keys.clear();

        // The panel takes focus; the physical key-ups happen over there.
        batch(() -> scene.windowFocusChanged(false));

        assertEquals(List.of(Keys.S, Keys.LEFT_CONTROL), r.releasedKeys(),
                "both held keys are released, or they stay down for the rest of the session");
    }

    @Test
    void theHeldButtonIsReleasedSoTheNextMoveIsNotStillADrag() {
        Recorder r = attach();
        batch(() -> scene.mouseButton(0, true, 0, 10, 10));
        batch(() -> scene.mouseMoved(20, 20));
        assertTrue(r.mouseTypes().contains(MouseEvent.Type.DRAG),
                "precondition: the button is held, so a move is a drag");

        batch(() -> scene.windowFocusChanged(false));
        assertTrue(r.mouseTypes().contains(MouseEvent.Type.RELEASE),
                "the button comes back released: the real mouse-up happened in another process");

        r.mouse.clear();
        batch(() -> scene.mouseMoved(30, 30));
        assertEquals(List.of(MouseEvent.Type.ENTER, MouseEvent.Type.MOVE), r.mouseTypes(),
                "the drag is over: pointing at the window again is a move, not a resumed drag");
    }

    @Test
    void modifiersAreDroppedOnFocusLossAndTheNextPressIsAuthoritative() {
        attach();
        batch(() -> scene.keyEvent(Keys.LEFT_SHIFT, true, false, 0));
        assertEquals(Keys.MOD_SHIFT, scene.modifiers(), "precondition: Shift is held");

        batch(() -> scene.windowFocusChanged(false));
        assertEquals(0, scene.modifiers(),
                "no key-up for Shift will ever arrive; believing it is still down is forever");

        // The next native event that carries a mask replaces the mirror wholesale,
        // so a modifier pressed while the panel was up is not missed either.
        batch(() -> scene.mouseButton(0, true, Keys.MOD_CONTROL, 10, 10));
        assertEquals(Keys.MOD_CONTROL, scene.modifiers(),
                "a press carries the authoritative native mask");
    }

    // ---------------------------------------------------------- animation

    @Test
    void aTransitionThatSpannedTheBlockLandsExactlyOnItsTarget() {
        AtomicLong nanos = new AtomicLong();
        FixedBox root = new FixedBox(100, 100);
        Scene s = new Scene(root, nanos::get);
        s.layoutPass(100, 100);
        Transition t = new Transition(root, 0f).duration(0.2).easing(Easing.LINEAR);
        t.to(1f);
        s.tickAnimations(); // registration frame: dt == 0 by contract

        nanos.addAndGet(TEN_SECONDS_NANOS); // the panel was up this long
        s.tickAnimations();

        assertEquals(1f, t.value(), 0f,
                "the eased value settles on its target, not past it");
        assertFalse(t.isAnimating(), "and it is done, not left running");
    }

    @Test
    void aTransitionLongerThanTheTickClampResumesInsteadOfJumping() {
        AtomicLong nanos = new AtomicLong();
        FixedBox root = new FixedBox(100, 100);
        Scene s = new Scene(root, nanos::get);
        s.layoutPass(100, 100);
        Transition t = new Transition(root, 0f).duration(4).easing(Easing.LINEAR);
        t.to(1f);
        s.tickAnimations();

        nanos.addAndGet(TEN_SECONDS_NANOS);
        s.tickAnimations();

        // The clamp on dt is what keeps this honest: ten seconds of wall time
        // reach the transition as MAX_TICK_SECONDS, so it picks up where it was
        // instead of teleporting through the states it was meant to show.
        assertEquals(Scene.MAX_TICK_SECONDS / 4, t.value(), 1e-6,
                "one clamped frame of progress, not ten seconds of it");
        assertTrue(t.isAnimating(), "it keeps going rather than snapping to the end");
    }

    // ------------------------------------------------------------- timers

    @Test
    void everyTimerThatFellDueDuringTheBlockRunsInOneDrain() {
        AtomicLong nanos = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        UiRuntime blocked = new UiRuntime(nanos::get, () -> { }, pool);
        blocked.bindToCurrentThread();
        try {
            List<String> ran = new ArrayList<>();
            blocked.post(() -> ran.add("posted"));
            blocked.postDelayed(() -> ran.add("late"), 5_000);
            blocked.postDelayed(() -> ran.add("early"), 100);

            nanos.addAndGet(TEN_SECONDS_NANOS); // no drain happened while the panel was up
            int count = blocked.drain();

            assertEquals(3, count, "one drain clears the whole backlog");
            assertEquals(List.of("posted", "early", "late"), ran,
                    "overdue timers run in deadline order behind what was already queued");
            assertEquals(-1, blocked.nanosUntilNextDeadline(),
                    "nothing is left owing, so the loop may sleep again");
        } finally {
            pool.shutdownNow();
        }
    }
}

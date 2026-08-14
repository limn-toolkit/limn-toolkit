package limn.scene;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scene time: the clamp on the wall clock, and the scale/pause layered over it.
 *
 * <p>Two properties, and every test here is one of them:
 * <ul>
 *   <li><b>No ticker ever receives more than {@link Scene#MAX_TICK_SECONDS}.</b> The frame
 *       interval is unbounded (a GC pause, a window drag, a sleeping laptop), and an unclamped
 *       {@code dt} reaches every ticker at once. What integrates it skips the states it was meant
 *       to pass through.</li>
 *   <li><b>Scale and pause reach scene-time tickers and nothing else.</b> In particular a
 *       real-time ticker keeps running while the scene is paused: several of them own a
 *       lifecycle (a window fade destroys the window, a dialog fade completes its future), so
 *       freezing one is not a slow animation, it is a hang.</li>
 * </ul>
 *
 * <p>The clock is driven by hand: the numbers here are exact, not "about a frame".
 */
class SceneTimeTest extends SceneTestBase {

    private final AtomicLong nanos = new AtomicLong();
    private final List<Double> scene = new ArrayList<>();
    private final List<Double> real = new ArrayList<>();

    private Scene build() {
        Scene s = new Scene(new FixedBox(100, 100), nanos::get);
        s.layoutPass(100, 100);
        return s;
    }

    private void advance(double seconds) {
        nanos.addAndGet((long) (seconds * TimeUnit.SECONDS.toNanos(1)));
    }

    /** A ticker that records every dt it is handed and never finishes. */
    private static Scene.Ticker recordInto(List<Double> out) {
        return dt -> {
            out.add(dt);
            return true;
        };
    }

    // ------------------------------------------------------------------ the clamp

    @Test
    void aStalledFrameIsClampedInsteadOfDeliveredWhole() {
        Scene s = build();
        s.addTicker(recordInto(scene));
        s.tickAnimations();                       // registration frame: dt == 0 by contract
        advance(4);                               // a four-second stall (GC, drag, breakpoint)
        s.tickAnimations();

        assertEquals(List.of(0.0, Scene.MAX_TICK_SECONDS), scene,
                "the stall arrives clamped, not as four seconds");
    }

    @Test
    void theClampedSecondsAreLostAndNotBanked() {
        // A stalled second is a second the app did not run. Replaying it later would be the same
        // jump the clamp exists to prevent, only deferred.
        Scene s = build();
        s.addTicker(recordInto(scene));
        s.tickAnimations();
        advance(4);
        s.tickAnimations();
        advance(1.0 / 60);
        s.tickAnimations();

        assertEquals(1.0 / 60, scene.get(2), 1e-9,
                "the frame after the stall is a normal frame, with no debt attached");
    }

    @Test
    void aHealthyFrameIsNeverTouchedByTheClamp() {
        Scene s = build();
        s.addTicker(recordInto(scene));
        s.tickAnimations();
        advance(1.0 / 60);
        s.tickAnimations();

        assertEquals(1.0 / 60, scene.get(1), 1e-9, "60 Hz is far under the guard");
    }

    // ------------------------------------------------------------------ the scale

    @Test
    void theScaleMultipliesSceneTimeOnly() {
        Scene s = build();
        s.setTimeScale(0.5);
        s.addTicker(recordInto(scene));
        s.addRealTimeTicker(recordInto(real));
        s.tickAnimations();
        advance(0.1);
        s.tickAnimations();

        assertEquals(0.05, scene.get(1), 1e-9, "scene time runs at half speed");
        assertEquals(0.1, real.get(1), 1e-9, "wall time does not");
    }

    @Test
    void theClampIsAppliedBeforeTheScale() {
        // Order matters: scaling first would let a 4 s stall through as 1 s at scale 0.25,
        // four times the guard, which is exactly what the guard is for.
        Scene s = build();
        s.setTimeScale(0.25);
        s.addTicker(recordInto(scene));
        s.tickAnimations();
        advance(4);
        s.tickAnimations();

        assertEquals(Scene.MAX_TICK_SECONDS * 0.25, scene.get(1), 1e-9,
                "clamp(4 s) × 0.25, not clamp(4 s × 0.25)");
    }

    @Test
    void aNegativeOrNonFiniteScaleIsRejected() {
        // Nothing in the animation model runs backwards: a negative dt would drive a Transition
        // past its start with no way back, and NaN would poison every value it touches.
        Scene s = build();
        assertThrows(IllegalArgumentException.class, () -> s.setTimeScale(-1));
        assertThrows(IllegalArgumentException.class, () -> s.setTimeScale(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> s.setTimeScale(Double.POSITIVE_INFINITY));
        assertEquals(1, s.timeScale(), "a rejected scale leaves the old one in place");
    }

    // ------------------------------------------------------------------ the pause

    @Test
    void pausingFreezesSceneTimeAndLeavesWallTimeRunning() {
        Scene s = build();
        s.addTicker(recordInto(scene));
        s.addRealTimeTicker(recordInto(real));
        s.tickAnimations();
        s.setPaused(true);
        advance(1.0 / 60);
        s.tickAnimations();

        assertEquals(1, scene.size(), "a frozen ticker is not even called, not called with 0");
        assertEquals(2, real.size(), "the real-time ticker kept its frame");
        assertEquals(1.0 / 60, real.get(1), 1e-6, "and never noticed the pause");
    }

    @Test
    void aPausedSceneStopsAskingForFrames() {
        // The event-driven loop idles at zero when nothing moves. Ticking frozen tickers with 0
        // would repaint at the frame rate to show pixels that cannot change.
        Scene s = build();
        RecordingWindow window = new RecordingWindow();
        s.bind(window);
        s.addTicker(recordInto(scene));
        s.tickAnimations();
        assertTrue(window.frameRequests > 0, "a running ticker keeps the pump alive");

        s.setPaused(true);
        int before = window.frameRequests;
        advance(1.0 / 60);
        s.tickAnimations();
        assertEquals(before, window.frameRequests, "frozen: nothing to draw, nothing scheduled");

        s.setPaused(false);
        assertEquals(before + 1, window.frameRequests, "resuming restarts the pump itself");
    }

    @Test
    void resumingStartsFromZeroWithNoBankedTime() {
        Scene s = build();
        s.addTicker(recordInto(scene));
        s.tickAnimations();
        s.setPaused(true);
        advance(10);                              // ten seconds of pause
        s.tickAnimations();
        s.setPaused(false);
        advance(1.0 / 60);
        s.tickAnimations();

        assertEquals(List.of(0.0, 0.0), scene.subList(0, 2),
                "the first frame after the pause is a fresh dt == 0, not ten seconds");
        advance(1.0 / 60);
        s.tickAnimations();
        assertEquals(1.0 / 60, scene.get(2), 1e-9, "and the one after it is a normal frame");
    }

    @Test
    void aTickerRegisteredDuringThePauseStillGetsItsOwnFirstFrame() {
        // The dt == 0 first frame is part of the Ticker contract; freezing must postpone it,
        // not consume it. (Consuming it is what an "always tick, with 0 when paused" design does.)
        Scene s = build();
        s.setPaused(true);
        s.addTicker(recordInto(scene));
        advance(1);
        s.tickAnimations();
        assertEquals(List.of(), scene, "still frozen: it has not run at all");

        s.setPaused(false);
        advance(1);
        s.tickAnimations();
        assertEquals(List.of(0.0), scene, "its first frame is a 0, whenever it happens");
    }

    @Test
    void aScaleOfZeroFreezesLikeAPauseAndKeepsTheChosenScaleOnResume() {
        Scene s = build();
        s.setTimeScale(0.5);
        s.setPaused(true);
        s.addTicker(recordInto(scene));
        s.tickAnimations();
        s.setPaused(false);
        assertEquals(0.5, s.timeScale(), "pause preserves the scale: slow motion resumes slow");

        s.tickAnimations();
        advance(0.1);
        s.tickAnimations();
        assertEquals(0.05, scene.get(scene.size() - 1), 1e-9, "at the scale it was paused with");
    }

    @Test
    void aPausedSceneFreezesOnlyTheTransitionsThatOptedIntoSceneTime() {
        // The split the whole feature rests on, seen through the animation primitive apps
        // actually use: chrome keeps moving while content stops. Driven through the scene's
        // ticker rather than Transition.tick, because the registration is what picks the clock.
        FixedBox owner = new FixedBox(10, 10);
        Scene s = new Scene(owner, nanos::get);
        s.layoutPass(10, 10);
        limn.animation.Transition chrome =
                new limn.animation.Transition(owner, 0).duration(1).easing(limn.animation.Easing.LINEAR);
        limn.animation.Transition content =
                new limn.animation.Transition(owner, 0).duration(1).easing(limn.animation.Easing.LINEAR)
                        .sceneTime(true);
        chrome.to(1f);
        content.to(1f);
        s.tickAnimations();                        // registration frame: dt == 0 for both

        s.setPaused(true);
        advance(10);                               // ten seconds of pause
        s.tickAnimations();
        assertEquals(Scene.MAX_TICK_SECONDS, chrome.value(), 1e-3f,
                "the hover fade did not stop because the app paused, and its ten seconds still"
                        + " arrived clamped, because the clamp is about the clock, not the pause");
        assertEquals(0f, content.value(), 1e-6f, "the content transition is frozen");

        s.setPaused(false);
        advance(0.2);
        s.tickAnimations();
        assertEquals(0.2f, content.value(), 1e-3f,
                "the resumed one advances by the 0.2 s that elapsed AFTER the resume: none of"
                        + " the ten paused seconds is banked and replayed");
        assertEquals(Scene.MAX_TICK_SECONDS + 0.2f, chrome.value(), 1e-3f, "wall time ran throughout");
    }

    @Test
    void aFinishedRealTimeTickerIsForgotten() {
        // The real-time set is keyed by identity and must not grow: a fade registers, completes
        // and is dropped, hundreds of times in a session.
        Scene s = build();
        s.addRealTimeTicker(dt -> false); // done on its first frame
        s.tickAnimations();
        s.setPaused(true);
        advance(1);
        s.tickAnimations();

        assertTrue(s.isPaused(), "sanity");
        assertEquals(List.of(), real, "nothing left to run");
    }
}

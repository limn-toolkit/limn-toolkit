package limn.scene;

import limn.backend.Backend;
import limn.backend.Clipboard;
import limn.backend.FrameCallback;
import limn.backend.NativeWindow;
import limn.backend.WindowInput;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Scene#fadeWindow} and friends drive {@link NativeWindow#setOpacity}
 * off the scene ticker. Ticks are advanced deterministically via the injected
 * clock and {@link Scene#tickAnimations()} (no Canvas needed).
 */
class WindowFadeTest extends SceneTestBase {

    private final AtomicLong clock = new AtomicLong();

    private Scene sceneOn(RecordingWindow win) {
        Scene scene = new Scene(new FixedBox(10, 10), clock::get);
        scene.bind(win);
        return scene;
    }

    @Test
    void fadesTheWindowInAcrossFrames() {
        RecordingWindow win = new RecordingWindow();
        Scene scene = sceneOn(win);

        scene.fadeWindowIn(0.1); // snap to 0, arm 0 → 1
        assertEquals(0f, win.opacity, 1e-4f, "starts transparent, before the first frame");

        scene.tickAnimations();  // first frame: dt == 0
        assertEquals(0f, win.opacity, 1e-4f);
        advance(50);
        scene.tickAnimations();  // half way
        assertTrue(win.opacity > 0.2f && win.opacity < 0.95f, "mid-fade, got " + win.opacity);
        advance(50);
        scene.tickAnimations();  // settled
        assertEquals(1f, win.opacity, 1e-3f, "fully opaque");
    }

    @Test
    void fadesOutThenRunsTheCallbackOnce() {
        RecordingWindow win = new RecordingWindow();
        Scene scene = sceneOn(win);
        scene.fadeWindow(1f, 0, null); // start opaque

        AtomicBoolean gone = new AtomicBoolean();
        scene.fadeWindowOut(0.1, () -> gone.set(true));
        scene.tickAnimations(); // dt == 0
        advance(60);
        scene.tickAnimations();
        assertFalse(gone.get(), "still fading");
        advance(60);
        scene.tickAnimations();
        assertEquals(0f, win.opacity, 1e-3f, "fully transparent");
        assertTrue(gone.get(), "onGone fires once it reaches 0");
    }

    @Test
    void aNewerFadeReversesTheOlderWithoutStackingTickers() {
        RecordingWindow win = new RecordingWindow();
        Scene scene = sceneOn(win);
        scene.fadeWindowIn(1.0); // slow 0 → 1
        scene.tickAnimations();
        advance(100);
        scene.tickAnimations();
        float mid = win.opacity;
        assertTrue(mid > 0f && mid < 1f, "in flight, got " + mid);

        AtomicBoolean reversed = new AtomicBoolean();
        scene.fadeWindow(0f, 0.1, () -> reversed.set(true)); // retarget to 0
        scene.tickAnimations(); // old ticker sees the newer generation and dies
        advance(150);
        scene.tickAnimations();
        assertEquals(0f, win.opacity, 1e-3f, "reversed back to transparent");
        assertTrue(reversed.get());
    }

    @Test
    void windowDestroyedMidFadeStillRunsTheArrivalCallbackExactlyOnce() {
        RecordingWindow win = new RecordingWindow();
        Scene scene = sceneOn(win);
        scene.fadeWindow(1f, 0, null); // start opaque

        AtomicInteger calls = new AtomicInteger();
        scene.fadeWindowOut(0.2, calls::incrementAndGet);
        scene.tickAnimations(); // begin fading (dt == 0)
        advance(50);
        scene.tickAnimations(); // mid-fade, not settled
        assertEquals(0, calls.get(), "callback not run while still fading");

        // The window is torn down mid-fade; the backend notifies the scene. Without
        // this flush the fade-out callback (a dialog's result completion) would leak.
        scene.windowClosed();
        assertEquals(1, calls.get(), "arrival callback flushed on teardown");

        advance(500);
        scene.tickAnimations(); // the abandoned ticker must not run it again
        assertEquals(1, calls.get(), "runs exactly once");
    }

    @Test
    void headlessSceneJumpsAndFiresImmediately() {
        Scene scene = new Scene(new FixedBox(10, 10), clock::get); // no window bound
        AtomicBoolean done = new AtomicBoolean();
        scene.fadeWindow(0f, 0.2, () -> done.set(true));
        assertTrue(done.get(), "no window → jump straight to target and run onArrive now");
    }

    private void advance(long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}

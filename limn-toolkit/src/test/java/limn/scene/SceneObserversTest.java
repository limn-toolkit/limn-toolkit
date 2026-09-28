package limn.scene;

import limn.backend.CrashHandler;
import limn.backend.CrashPhase;
import limn.backend.Crashes;
import limn.concurrent.Subscription;
import limn.concurrent.UiRuntime;
import limn.input.Keys;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static limn.testing.SceneDriver.drive;

/**
 * The scene's five observers and the weak axis listener on the one fan-out shape: the press, blur
 * and fullscreen observers' first tests, the window-close list's dropped-registration defect
 * closed, the shortcut chain's containment, and the claim ADR 002 §5.4 leans on and nothing had
 * ever verified -- that a scene dropped and collected purges itself from every process-wide axis.
 */
class SceneObserversTest {

    private HeadlessUi ui;
    private UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
        runtime = ui.runtime();
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
    }

    static final class Stop extends Widget<Stop> {
        Stop() {
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(50, 20);
        }
    }

    private static Scene sceneOf(Widget<?> root) {
        Scene scene = new Scene(root);
        scene.layoutPass(100, 100);
        return scene;
    }

    private static void press(Scene scene, float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        drive(scene).inputBatchEnded();
    }

    // ------------------------------------------------------------------- presses and blur

    @Test
    void aPressObserverHearsTheHitTargetAndItsHandleStopsIt() {
        Stop stop = new Stop();
        Scene scene = sceneOf(stop);
        List<Widget<?>> seen = new ArrayList<>();
        Subscription handle = scene.observePresses(seen::add);

        press(scene, 10, 10);
        assertEquals(List.of(stop), seen);

        handle.cancel();
        handle.cancel(); // idempotent
        press(scene, 10, 10);
        assertEquals(List.of(stop), seen, "cancelled: no further presses");
    }

    @Test
    void aThrowingPressObserverStopsNeitherTheNextOneNorTheDispatch() {
        Stop stop = new Stop();
        Scene scene = sceneOf(stop);
        AtomicInteger next = new AtomicInteger();
        scene.observePresses(target -> {
            throw new IllegalStateException("a press observer that misbehaves");
        });
        scene.observePresses(target -> next.incrementAndGet());
        AtomicInteger reported = new AtomicInteger();
        CrashHandler counting = (phase, error) -> {
            if (phase == CrashPhase.OBSERVER) {
                reported.incrementAndGet();
            }
            return true;
        };
        Crashes.install(counting);
        try {
            press(scene, 10, 10);
        } finally {
            Crashes.uninstall(counting);
        }
        assertEquals(1, next.get());
        assertEquals(1, reported.get());
        assertEquals(stop, scene.focusedWidget(), "the press itself still landed: click-to-focus");
    }

    @Test
    void aBlurObserverRunsWhenTheWindowLosesFocusAndNotWhenItGainsIt() {
        Scene scene = sceneOf(new Stop());
        AtomicInteger blurs = new AtomicInteger();
        Subscription handle = scene.observeWindowBlur(blurs::incrementAndGet);

        drive(scene).windowFocusChanged(true);
        drive(scene).inputBatchEnded();
        assertEquals(0, blurs.get());
        drive(scene).windowFocusChanged(false);
        drive(scene).inputBatchEnded();
        assertEquals(1, blurs.get());

        handle.cancel();
        drive(scene).windowFocusChanged(true);
        drive(scene).inputBatchEnded();
        drive(scene).windowFocusChanged(false);
        drive(scene).inputBatchEnded();
        assertEquals(1, blurs.get());
    }

    // ---------------------------------------------------------------------- fullscreen

    @Test
    void aFullscreenObserverIsToldEachChangeWhenTheBatchEnds() {
        Scene scene = sceneOf(new Stop());
        List<Boolean> heard = new ArrayList<>();
        Subscription handle = scene.observeWindowFullscreen(heard::add);

        drive(scene).windowFullscreenChanged(true);
        assertEquals(List.of(), heard, "queued with the rest of the batch");
        drive(scene).inputBatchEnded();
        assertEquals(List.of(true), heard);
        drive(scene).windowFullscreenChanged(false);
        drive(scene).inputBatchEnded();
        assertEquals(List.of(true, false), heard);

        handle.cancel();
        drive(scene).windowFullscreenChanged(true);
        drive(scene).inputBatchEnded();
        assertEquals(List.of(true, false), heard);
    }

    /**
     * The shape of an application's F11: a key handler makes the window fullscreen while the batch
     * holding that key is being dispatched, and the change it causes is acted on in the same batch
     * rather than waiting for the next input to arrive.
     */
    @Test
    void aChangeMadeByAHandlerIsToldInTheBatchThatMadeIt() {
        StubWindow window = new StubWindow();
        FullscreenKey key = new FullscreenKey(window);
        Scene scene = sceneOf(key);
        scene.bind(window);
        scene.requestFocus(key);
        List<Boolean> heard = new ArrayList<>();
        scene.observeWindowFullscreen(heard::add);

        drive(scene).press(Keys.F11);

        assertEquals(List.of(true), heard);
    }

    /** Makes its window fullscreen on any key, as an application's shortcut would. */
    static final class FullscreenKey extends Widget<FullscreenKey> {
        private final StubWindow window;

        FullscreenKey(StubWindow window) {
            this.window = window;
            setFocusable(true);
        }

        @Override
        protected void onKeyEvent(limn.scene.event.KeyEvent event) {
            if (event.isPressed()) {
                window.enterFullscreen();
                event.consume();
            }
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(50, 20);
        }
    }

    /** What a stub window tells is what a backend tells: each change, once, and never a non-change. */
    @Test
    void aStubWindowTellsItsSceneOfEachFullscreenChangeAndOfNothingElse() {
        StubWindow window = new StubWindow();
        Scene scene = sceneOf(new Stop());
        scene.bind(window);
        List<Boolean> heard = new ArrayList<>();
        scene.observeWindowFullscreen(heard::add);

        window.enterFullscreen();
        window.enterFullscreen(1920, 1080, 60); // already fullscreen: no change to tell
        drive(scene).inputBatchEnded();
        assertEquals(List.of(true), heard);

        window.exitFullscreen();
        window.exitFullscreen();
        drive(scene).inputBatchEnded();
        assertEquals(List.of(true, false), heard);
    }

    // ---------------------------------------------------------------------- window close

    /**
     * The defect ADR 040 §0.7 found: windowClosed walked a copy and then cleared the list, so an
     * observer registered during the walk -- a dialog's completion resolving a second dialog,
     * whose unhook is registered right then -- was dropped unread. Now only the observers that
     * ran are removed, and the late one runs on the next close.
     */
    @Test
    void anObserverRegisteredDuringTheCloseWalkIsNotDroppedUnread() {
        Scene scene = sceneOf(new Stop());
        List<String> ran = new ArrayList<>();
        AtomicReference<Subscription> late = new AtomicReference<>();
        scene.observeWindowClosed(() -> {
            ran.add("first");
            late.set(scene.observeWindowClosed(() -> ran.add("late")));
        });

        drive(scene).windowClosed();
        assertEquals(List.of("first"), ran, "the walk is over the array it started with");

        drive(scene).windowClosed();
        assertEquals(List.of("first", "late"), ran, "and the late registration survived the clear");

        drive(scene).windowClosed();
        assertEquals(List.of("first", "late"), ran, "observers run once and are dropped");
    }

    @Test
    void aThrowingCloseObserverDoesNotStopTheRemainingOnes() {
        Scene scene = sceneOf(new Stop());
        AtomicInteger next = new AtomicInteger();
        scene.observeWindowClosed(() -> {
            throw new IllegalStateException("a close observer that misbehaves");
        });
        scene.observeWindowClosed(next::incrementAndGet);
        CrashHandler quiet = (phase, error) -> true;
        Crashes.install(quiet);
        try {
            drive(scene).windowClosed();
        } finally {
            Crashes.uninstall(quiet);
        }
        assertEquals(1, next.get());
    }

    // ------------------------------------------------------------------------- shortcuts

    @Test
    void aThrowingShortcutHandlerIsReadAsDidNotHandle() {
        Stop stop = new Stop();
        Scene scene = sceneOf(stop);
        AtomicInteger second = new AtomicInteger();
        scene.addShortcutHandler(event -> {
            throw new IllegalStateException("a shortcut handler that misbehaves");
        });
        scene.addShortcutHandler(event -> {
            second.incrementAndGet();
            return true;
        });
        CrashHandler quiet = (phase, error) -> true;
        Crashes.install(quiet);
        try {
            drive(scene).keyEvent(Keys.S, true, false, Keys.MOD_CONTROL);
            drive(scene).keyEvent(Keys.S, false, false, Keys.MOD_CONTROL);
            drive(scene).inputBatchEnded();
        } finally {
            Crashes.uninstall(quiet);
        }
        assertTrue(second.get() >= 1, "the chord went on down the chain");
    }

    // ------------------------------------------------------------------- the weak axis listener

    /**
     * Drop a scene, force a collection, and assert the axes purged: the wrapper holds its own
     * handles, so when its scene is gone the next change on any of the four axes cancels all
     * four. The counts are read on the two axes in this package; one wrapper holds all four
     * handles, so two axes purged is the wrapper released.
     */
    @Test
    void aCollectedSceneIsPurgedFromEveryAxisOnTheNextChange() {
        int controlSizes = ControlSize.listenerCount();
        int directions = LayoutDirection.listenerCount();

        buildAndAbandon();
        assertEquals(controlSizes + 1, ControlSize.listenerCount(), "registered in the constructor");
        assertEquals(directions + 1, LayoutDirection.listenerCount());

        ControlSize was = ControlSize.processDefault();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            // At or below the baseline, not equal to it: other tests' abandoned scenes in this
            // virtual machine are purged by the same changes, and may be collected here too.
            while (ControlSize.listenerCount() > controlSizes) {
                if (System.nanoTime() > deadline) {
                    fail("a collected scene must purge itself from the axes on the next change");
                }
                System.gc();
                // A change is what runs the wrappers, and a dead one releases its handles there.
                ControlSize.setProcessDefault(ControlSize.processDefault() == ControlSize.MEDIUM
                        ? ControlSize.LARGE : ControlSize.MEDIUM);
                Thread.onSpinWait();
            }
        } finally {
            ControlSize.setProcessDefault(was);
        }
        assertTrue(LayoutDirection.listenerCount() <= directions,
                "one wrapper, four handles: the direction axis was purged by the same release");
    }

    /** In a method of its own so the scene cannot stay live on this frame's stack. */
    private static void buildAndAbandon() {
        new Scene(new Column());
    }
}

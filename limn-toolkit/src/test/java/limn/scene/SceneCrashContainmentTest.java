package limn.scene;

import limn.backend.CrashHandler;
import limn.backend.CrashPhase;
import limn.backend.Crashes;
import limn.graphics.Canvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frame-crash containment: an exception in widget paint/layout code must never
 * escape {@link Scene#renderFrame}, must reach the installed
 * {@link CrashHandler}, must self-heal with a full repaint, and must stop
 * self-requesting frames after enough consecutive failures so a deterministic
 * crash cannot spin the loop.
 */
class SceneCrashContainmentTest extends SceneTestBase {

    /** Mirrors Scene.FRAME_CRASH_RECOVERY_LIMIT. */
    private static final int RECOVERY_LIMIT = 8;

    private record Crash(CrashPhase phase, Throwable error) {
    }

    private static final class RecordingHandler implements CrashHandler {
        final List<Crash> crashes = new ArrayList<>();
        boolean keepRunning = true;

        @Override
        public boolean crashed(CrashPhase phase, Throwable error) {
            crashes.add(new Crash(phase, error));
            return keepRunning;
        }
    }

    private static final class CrashingBox extends SceneTestBase.FixedBox {
        boolean shouldThrow = true;
        final RuntimeException boom = new RuntimeException("paint boom (expected in this test)");

        CrashingBox() {
            super(50, 50);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            if (shouldThrow) {
                throw boom;
            }
        }
    }

    private final RecordingHandler handler = new RecordingHandler();

    @AfterEach
    void uninstallHandler() {
        Crashes.uninstall(handler);
    }

    private static Scene boundScene(Widget root, RecordingWindow window) {
        Scene scene = new Scene(root);
        scene.bind(window);
        return scene;
    }

    @Test
    void paintCrashIsContainedReportedAndRepainted() {
        Crashes.install(handler);
        CrashingBox box = new CrashingBox();
        RecordingWindow window = new RecordingWindow();
        Scene scene = boundScene(box, window);
        int baseline = window.frameRequests;

        assertDoesNotThrow(() -> scene.renderFrame(new NoopCanvas(200, 200)));

        assertEquals(1, handler.crashes.size());
        assertEquals(CrashPhase.FRAME, handler.crashes.get(0).phase());
        assertSame(box.boom, handler.crashes.get(0).error());
        assertTrue(window.frameRequests > baseline, "recovery must request a fresh frame");
    }

    @Test
    void aSuccessfulFrameResetsTheCrashStreak() {
        Crashes.install(handler);
        CrashingBox box = new CrashingBox();
        RecordingWindow window = new RecordingWindow();
        Scene scene = boundScene(box, window);

        for (int i = 0; i < RECOVERY_LIMIT - 1; i++) {
            scene.renderFrame(new NoopCanvas(200, 200));
        }
        box.shouldThrow = false;
        scene.renderFrame(new NoopCanvas(200, 200)); // success: streak back to zero
        box.shouldThrow = true;

        int before = window.frameRequests;
        scene.renderFrame(new NoopCanvas(200, 200));
        assertTrue(window.frameRequests > before,
                "after a successful frame the next crash must self-heal again");
    }

    @Test
    void deterministicCrashStopsSelfRequestingAfterTheLimit() {
        Crashes.install(handler);
        CrashingBox box = new CrashingBox();
        RecordingWindow window = new RecordingWindow();
        Scene scene = boundScene(box, window);
        int baseline = window.frameRequests;

        for (int i = 0; i < RECOVERY_LIMIT + 5; i++) {
            scene.renderFrame(new NoopCanvas(200, 200));
        }

        // Every crash was reported…
        assertEquals(RECOVERY_LIMIT + 5, handler.crashes.size());
        // …but only the first RECOVERY_LIMIT self-requested a retry frame.
        assertEquals(baseline + RECOVERY_LIMIT, window.frameRequests,
                "past the limit the scene must stop scheduling its own retries");
    }

    @Test
    void handlerRequestingShutdownRethrows() {
        Crashes.install(handler);
        handler.keepRunning = false;
        CrashingBox box = new CrashingBox();
        Scene scene = boundScene(box, new RecordingWindow());

        Crashes.ShutdownRequested shutdown = assertThrows(Crashes.ShutdownRequested.class,
                () -> scene.renderFrame(new NoopCanvas(200, 200)));
        assertSame(box.boom, shutdown.getCause());
    }

    @Test
    void tickerCrashIsReportedToTheHandler() {
        Crashes.install(handler);
        Scene scene = boundScene(new FixedBox(10, 10), new RecordingWindow());
        RuntimeException boom = new RuntimeException("ticker boom (expected in this test)");
        scene.addTicker(dt -> {
            throw boom;
        });

        assertDoesNotThrow(() -> scene.renderFrame(new NoopCanvas(200, 200)));

        assertEquals(1, handler.crashes.size());
        assertEquals(CrashPhase.TICKER, handler.crashes.get(0).phase());
        assertSame(boom, handler.crashes.get(0).error());
    }

    @Test
    void defaultHandlerContainsWithoutObservers() {
        // No handler installed: the crash must still be contained (log-only).
        CrashingBox box = new CrashingBox();
        Scene scene = boundScene(box, new RecordingWindow());
        assertDoesNotThrow(() -> scene.renderFrame(new NoopCanvas(200, 200)));
    }

    @Test
    void activeTickersCannotReArmTheCrashStormPastTheLimit() {
        Crashes.install(handler);
        CrashingBox box = new CrashingBox();
        RecordingWindow window = new RecordingWindow();
        Scene scene = boundScene(box, window);
        scene.addTicker(dt -> true); // healthy persistent animation (a spinner)

        // Simulate the backend: keep rendering as long as the scene asks for
        // another frame. Without the tick gate this loop never settles: the
        // ticker's scheduleFrame re-arms every crashed frame forever.
        int frames = 0;
        int before = -1;
        while (window.frameRequests != before && frames < 50) {
            before = window.frameRequests;
            scene.renderFrame(new NoopCanvas(200, 200));
            frames++;
        }
        assertTrue(frames < 50, "the crash storm must be bounded even with active tickers");

        // And it stays quiet: an external retry crashes once, no re-arm.
        int settled = window.frameRequests;
        scene.renderFrame(new NoopCanvas(200, 200));
        assertEquals(settled, window.frameRequests);
    }

    @Test
    void disposalCrashHonorsTheShutdownVerdict() {
        Crashes.install(handler);
        handler.keepRunning = false;
        Scene scene = boundScene(new FixedBox(10, 10), new RecordingWindow());
        RuntimeException boom = new RuntimeException("dispose boom (expected in this test)");
        scene.disposeLater(() -> {
            throw boom;
        });

        Crashes.ShutdownRequested shutdown = assertThrows(Crashes.ShutdownRequested.class,
                () -> scene.renderFrame(new NoopCanvas(200, 200)));
        assertSame(boom, shutdown.getCause());
        assertEquals(CrashPhase.FRAME, handler.crashes.get(0).phase());
    }

    @Test
    void oneBadDisposalDoesNotSkipTheRest() {
        List<String> ran = new ArrayList<>();
        Scene scene = boundScene(new FixedBox(10, 10), new RecordingWindow());
        scene.disposeLater(() -> {
            throw new RuntimeException("dispose boom (expected, must be logged not thrown)");
        });
        scene.disposeLater(() -> ran.add("second"));

        assertDoesNotThrow(() -> scene.renderFrame(new NoopCanvas(200, 200)));
        assertEquals(List.of("second"), ran);
    }

    @Test
    void windowClosedRunsAllCleanupDespiteThrowingCallbacks() {
        Crashes.install(handler);
        Scene scene = boundScene(new FixedBox(10, 10), new RecordingWindow());
        List<String> ran = new ArrayList<>();
        RuntimeException boom = new RuntimeException("observer boom (expected in this test)");
        scene.observeWindowClosed(() -> {
            throw boom;
        });
        scene.observeWindowClosed(() -> ran.add("second"));

        assertDoesNotThrow(scene::windowClosed);
        assertEquals(List.of("second"), ran, "later observers must still run");
        assertEquals(1, handler.crashes.size());
        assertEquals(CrashPhase.WINDOW_CLOSE, handler.crashes.get(0).phase());
        assertSame(boom, handler.crashes.get(0).error());
    }
}

package limn.components;

import limn.concurrent.Ui;
import limn.input.Keys;
import limn.scene.Scene;
import limn.testing.AccessibleHarness;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The published fixtures an application tests with take a turn of a backend's loop on each frame:
 * the UI queue drained, then the frame drawn, as a desktop backend's loop does after it has taken
 * the input. What the toolkit runs on the next turn, the end of a fade that goes straight there
 * among it, then reaches a test that only renders frames, as it reaches a user.
 */
class HeadlessFixturesTakeATurnTest {

    @Test
    void aHeadlessWindowsFrameDrainsTheQueueSoADialogClosedUnderLessMotionResolves() {
        long[] now = {0};
        try (HeadlessUi ui = new HeadlessUi(() -> now[0]); TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true); // the way a test makes the toolkit's fades deterministic
            limn.graphics.TextRulers.install(HeadlessWindow.RULER);
            try {
                HeadlessBackend backend = new HeadlessBackend(ui.runtime());
                Scene scene = new Scene(new Label("Order 1042"));
                HeadlessWindow window = backend.open("Orders", 600, 400);
                scene.bind(window);
                window.frame();
                AtomicReference<String> result = new AtomicReference<>("(none)");
                new Dialog("Delete the order?", "It cannot be undone.")
                        .addButton("Keep", "keep")
                        .setCancelResult("cancel")
                        .show(scene).thenAccept(result::set);
                frameEveryWindow(backend, now, 5);
                HeadlessWindow dialogWindow = backend.windows().get(backend.windows().size() - 1);
                dialogWindow.key(Keys.ESCAPE);
                frameEveryWindow(backend, now, 5);
                assertEquals("cancel", result.get(), "frames alone, as an application's test renders them");
            } finally {
                limn.graphics.TextRulers.uninstall(HeadlessWindow.RULER);
            }
        }
    }

    @Test
    void anAccessibleHarnessFrameAndEachStepOfItsTimeTakeATurn() {
        try (HeadlessUi ui = new HeadlessUi()) {
            AccessibleHarness harness = new AccessibleHarness(ui.runtime(), new Label("x"));
            int[] ran = {0};
            Ui.post(() -> ran[0]++);
            harness.frame();
            assertEquals(1, ran[0], "a frame drains the queue first");

            Runnable[] again = new Runnable[1];
            again[0] = () -> {
                ran[0]++;
                Ui.post(again[0]);
            };
            Ui.post(again[0]);
            long step = (long) (Scene.MAX_TICK_SECONDS * 1000);
            harness.advanceTime(3 * step, null);
            assertEquals(4, ran[0], "one turn for each frame time moves through");
        }
    }

    private static void frameEveryWindow(HeadlessBackend backend, long[] now, int frames) {
        for (int i = 0; i < frames; i++) {
            now[0] += 20_000_000L;
            for (HeadlessWindow w : backend.windows()) {
                if (!w.isClosed()) {
                    w.frame();
                }
            }
        }
    }
}

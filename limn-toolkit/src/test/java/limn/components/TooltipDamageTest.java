package limn.components;

import limn.backend.Backend;
import limn.backend.Clipboard;
import limn.backend.Cursor;
import limn.backend.FrameCallback;
import limn.backend.NativeWindow;
import limn.backend.WindowInput;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tooltips under partial rendering: showing, fading and following the pointer
 * must damage only the tooltip's own rect: the tooltip is scene-painted (no
 * widget invalidates for it), and it used to fall back to whole-window
 * requests. Controllable clock, like {@link CaretDamageTest}, so the dwell
 * timer can be fired deterministically.
 */
class TooltipDamageTest {

    private static final TextRuler RULER = (text, font) ->
            new TextMetrics(10f * (int) text.codePoints().count(), 8, 2, 12);

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(nanos::get, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Theme.setCurrent(Theme.dark()); // also installs the tooltip style
    }

    @AfterEach
    void uninstallRuntime() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void tooltipShowAndFadeDamageOnlyItsOwnRect() {
        Button button = new Button("Hover me");
        button.setTooltip("Tip");
        Scene scene = new Scene(button, nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        scene.bind(new StubWindow());
        scene.layoutPass(400, 300);

        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 300);
        scene.renderFrame(canvas);

        // Hover the button; let its hover transition settle WITHIN the 600 ms
        // tooltip dwell (7 × 70 ms = 490 ms < 600).
        scene.mouseMoved(30, 15);
        scene.inputBatchEnded();
        boolean settled = false;
        for (int i = 0; i < 7 && !settled; i++) {
            nanos.addAndGet(70_000_000L);
            runtime.drain();
            canvas.reset();
            scene.renderFrame(canvas);
            settled = canvas.nothingPainted();
        }
        assertTrue(settled, "hover transition must settle before the dwell fires");

        // Cross the dwell deadline: the show task runs and the fade begins.
        nanos.addAndGet(300_000_000L);
        runtime.drain();
        boolean sawTooltipFrame = false;
        for (int i = 0; i < 10; i++) {
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.firstClip != null) {
                sawTooltipFrame = true;
                assertTrue(!canvas.cleared, "tooltip frames must be partial, never full");
                assertTrue(canvas.firstClip.width() < 150 && canvas.firstClip.height() < 60,
                        "tooltip damage must be its own rect, got " + canvas.firstClip);
            }
            nanos.addAndGet(50_000_000L);
            runtime.drain();
        }
        assertTrue(sawTooltipFrame, "the tooltip show/fade must have painted");
    }
}

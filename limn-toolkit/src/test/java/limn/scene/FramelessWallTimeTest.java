package limn.scene;

import limn.scene.layout.Column;
import limn.backend.WindowConfig;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window that draws no frames (covered on macOS, minimized) still finishes its wall-time
 * animations, the fades that complete a dialog's result or take an overlay away: they waited for
 * the user to look at the window again. Scene-time animation, which is content, waits.
 */
class FramelessWallTimeTest {

    private HeadlessUi ui;

    @BeforeEach
    void start() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void stop() {
        ui.close();
    }

    @Test
    void wallTimeTickersAdvanceWithNoFrameAndSceneTimeOnesWait() {
        Scene scene = new Scene(new Column());
        scene.bind(new HeadlessBackend(ui.runtime()).createWindow(WindowConfig.of("covered", 200, 100)));
        double[] wall = {0};
        double[] content = {0};
        scene.addRealTimeTicker(dt -> {
            wall[0] += dt;
            return wall[0] < 0.3;
        });
        scene.addTicker(dt -> {
            content[0] += dt;
            return true;
        });
        // No frame is ever rendered: only the UI queue runs, as in a covered window.
        ui.pumpUntil(() -> wall[0] >= 0.3, 5_000_000_000L);
        assertTrue(wall[0] >= 0.3, "the fade reached its end without a frame: " + wall[0]);
        assertEquals(0, content[0], 1e-9, "content waits for frames");
        assertEquals(0, scene.tickerCounts()[1], "and the finished fade is gone");
    }
}

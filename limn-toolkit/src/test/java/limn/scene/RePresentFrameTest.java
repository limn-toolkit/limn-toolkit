package limn.scene;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The re-present contract. A re-present exists only to draw the <em>same</em>
 * frame into the other buffer (the backend's anti-flicker double present), so
 * it must not advance anything: no animation ticks, no metrics, no new damage.
 * Content frames keep behaving exactly as before: a re-present is invisible to
 * the scene except for the pixels it duplicates.
 */
class RePresentFrameTest extends SceneTestBase {

    private final AtomicLong nanos = new AtomicLong();

    private void advanceClock(long millis) {
        nanos.addAndGet(millis * 1_000_000L);
    }

    private Scene sceneWithClock(Widget root) {
        Scene scene = new Scene(root, nanos::get);
        scene.bind(new RecordingWindow());
        scene.layoutPass(200, 200);
        return scene;
    }

    @Test
    void rePresentFramesDoNotAdvanceTickers() {
        Scene scene = sceneWithClock(new FixedBox(50, 50));
        List<Double> deltas = new ArrayList<>();
        scene.addTicker(dt -> {
            deltas.add(dt);
            return true;
        });
        NoopCanvas canvas = new NoopCanvas(200, 200);

        scene.renderFrame(canvas, false); // first content frame: dt == 0 by contract
        advanceClock(16);
        scene.renderFrame(canvas, false);
        int afterContent = deltas.size();
        assertEquals(2, afterContent, "content frames tick");

        advanceClock(16);
        scene.renderFrame(canvas, true); // re-present
        assertEquals(afterContent, deltas.size(), "a re-present must not tick animations");
    }

    @Test
    void timeSkippedByARePresentIsNotLost() {
        // The next content frame must see the full elapsed time, otherwise an
        // animation would visibly stall for the duration of the re-present.
        Scene scene = sceneWithClock(new FixedBox(50, 50));
        List<Double> deltas = new ArrayList<>();
        scene.addTicker(dt -> {
            deltas.add(dt);
            return true;
        });
        NoopCanvas canvas = new NoopCanvas(200, 200);

        scene.renderFrame(canvas, false);
        advanceClock(10);
        scene.renderFrame(canvas, true);  // 10 ms pass with no tick
        advanceClock(10);
        scene.renderFrame(canvas, false); // content frame sees all 20 ms

        double last = deltas.get(deltas.size() - 1);
        assertEquals(0.020, last, 1e-6, "elapsed time is deferred to the next content frame, not dropped");
    }

    @Test
    void aTickerThatFinishesIsStillRetiredNormally() {
        Scene scene = sceneWithClock(new FixedBox(50, 50));
        int[] ticks = {0};
        scene.addTicker(dt -> {
            ticks[0]++;
            return ticks[0] < 3; // stops after three ticks
        });
        NoopCanvas canvas = new NoopCanvas(200, 200);

        for (int i = 0; i < 6; i++) {
            advanceClock(16);
            scene.renderFrame(canvas, i % 2 == 1); // alternate content / re-present
        }
        assertEquals(3, ticks[0], "the ticker ran exactly its three content frames and retired");
    }

    @Test
    void rePresentDoesNotCountAsAFrameInMetrics() {
        Scene scene = sceneWithClock(new FixedBox(50, 50));
        NoopCanvas canvas = new NoopCanvas(200, 200);

        advanceClock(16);
        scene.renderFrame(canvas, false);
        long framesAfterContent = scene.metrics().totalFrames();

        advanceClock(16);
        scene.renderFrame(canvas, true);
        assertEquals(framesAfterContent, scene.metrics().totalFrames(),
                "re-presents are not content frames");
    }

    @Test
    void animationsStillRunAcrossManyContentFrames() {
        // Guard against the fix over-reaching: normal animation must be
        // untouched when no re-present is involved.
        Scene scene = sceneWithClock(new FixedBox(50, 50));
        double[] total = {0};
        scene.addTicker(dt -> {
            total[0] += dt;
            return true;
        });
        NoopCanvas canvas = new NoopCanvas(200, 200);

        scene.renderFrame(canvas, false);
        for (int i = 0; i < 60; i++) {
            advanceClock(16);
            scene.renderFrame(canvas, false);
        }
        assertTrue(Math.abs(total[0] - 0.96) < 1e-3,
                "60 frames of 16 ms must accumulate ~0.96 s, got " + total[0]);
    }

    @Test
    void disposalsStillDrainOnRePresentFrames() {
        // GPU cleanup is NOT animation: it must keep running on any frame that
        // has the GL context current, re-present included.
        Scene scene = sceneWithClock(new FixedBox(50, 50));
        boolean[] disposed = {false};
        scene.disposeLater(() -> disposed[0] = true);
        scene.renderFrame(new NoopCanvas(200, 200), true);
        assertTrue(disposed[0], "a deferred disposal must not wait for a content frame");
    }
}

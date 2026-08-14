package limn.scene;

import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@link FrameMetrics} ring buffer and the {@link Scene} event-time hook. */
class FrameMetricsTest extends SceneTestBase {

    @Test
    void everySceneMetricsIsDiscoverableProcessWide() {
        // A perf HUD aggregates across windows (max fps among them): every
        // instance must be reachable, each with its own painted-frame count.
        FrameMetrics a = new FrameMetrics();
        FrameMetrics b = new FrameMetrics();
        a.recordFrameTime(1f);
        java.util.List<FrameMetrics> all = FrameMetrics.processInstances();
        org.junit.jupiter.api.Assertions.assertTrue(all.contains(a));
        org.junit.jupiter.api.Assertions.assertTrue(all.contains(b));
        assertEquals(1, a.totalFrames());
        assertEquals(0, b.totalFrames());
    }

    @Test
    void emptyMetricReadsZero() {
        FrameMetrics.Metric m = new FrameMetrics().fps();
        assertEquals(0, m.count());
        assertEquals(0f, m.last());
        assertEquals(0f, m.average());
        assertEquals(0f, m.max());
    }

    @Test
    void tracksLastAverageMaxAndHistory() {
        FrameMetrics fm = new FrameMetrics();
        fm.recordFrameTime(1f);
        fm.recordFrameTime(2f);
        fm.recordFrameTime(3f);
        FrameMetrics.Metric m = fm.frameTime();
        assertEquals(3, m.count());
        assertEquals(3f, m.last());
        assertEquals(2f, m.average(), 1e-6);
        assertEquals(3f, m.max());
        float[] history = new float[3];
        assertEquals(3, m.copyInto(history));
        assertArrayEquals(new float[]{1f, 2f, 3f}, history, 0f);
    }

    @Test
    void evictsOldestBeyondCapacityKeepingNewestOldestFirst() {
        FrameMetrics fm = new FrameMetrics();
        int total = FrameMetrics.HISTORY + 5;
        for (int i = 0; i < total; i++) {
            fm.recordEventTime(i);
        }
        FrameMetrics.Metric m = fm.eventTime();
        assertEquals(FrameMetrics.HISTORY, m.count(), "caps at capacity");
        assertEquals((float) (total - 1), m.last());
        float[] history = new float[FrameMetrics.HISTORY];
        int n = m.copyInto(history);
        assertEquals(FrameMetrics.HISTORY, n);
        assertEquals(5f, history[0], "oldest kept is total-HISTORY");
        assertEquals((float) (total - 1), history[n - 1], "newest is last");
        for (int i = 1; i < n; i++) {
            assertTrue(history[i] > history[i - 1], "oldest → newest order");
        }
    }

    @Test
    void copyIntoAShorterBufferKeepsTheNewest() {
        FrameMetrics fm = new FrameMetrics();
        for (int i = 0; i < 10; i++) {
            fm.recordFps(i);
        }
        float[] history = new float[4];
        assertEquals(4, fm.fps().copyInto(history));
        assertArrayEquals(new float[]{6f, 7f, 8f, 9f}, history, 0f, "newest four, oldest first");
    }

    @Test
    void sceneTimesNonEmptyInputBatchesOnly() {
        Scene scene = new Scene(new FixedBox(10, 10));
        scene.layoutPass(100, 100);
        assertEquals(0, scene.metrics().eventTime().count());

        scene.mouseMoved(5, 5);
        scene.inputBatchEnded();
        assertEquals(1, scene.metrics().eventTime().count(), "one batch with input is timed");

        scene.inputBatchEnded(); // queue empty now
        assertEquals(1, scene.metrics().eventTime().count(), "an empty batch is not recorded");
    }

    @Test
    void gpuSamplesRecordWheneverDeliveredIncludingOnRePresentFrames() {
        Scene scene = new Scene(new FixedBox(10, 10));
        NoopCanvas canvas = new NoopCanvas(100, 100);

        scene.renderFrame(canvas, false, Float.NaN); // no sample delivered
        assertEquals(0, scene.metrics().gpuTime().count());

        scene.renderFrame(canvas, false, 2.5f);
        assertEquals(1, scene.metrics().gpuTime().count());
        assertEquals(2.5f, scene.metrics().gpuTime().last(), 1e-6f);

        // A sample always measures a content frame; the re-present frame is
        // just the courier (with sparse rendering it usually is), so it is recorded.
        scene.renderFrame(canvas, true, 9f);
        assertEquals(2, scene.metrics().gpuTime().count());
        assertEquals(9f, scene.metrics().gpuTime().last(), 1e-6f);
    }

    @Test
    void countsEveryWidgetThatPaintedAndTheRegionsItPaintedIn() {
        Column column = new Column();
        column.add(new FixedBox(50, 20));
        column.add(new FixedBox(50, 20));
        Scene scene = new Scene(column); // root + column + 2 boxes
        NoopCanvas canvas = new NoopCanvas(100, 100);

        scene.renderFrame(canvas);
        assertEquals(3f, scene.metrics().paintedWidgets().last(), "column and its two children");
        assertEquals(1f, scene.metrics().damageRects().last(), "a full frame is one region");
    }

    @Test
    void partialRenderingPaintsFewerWidgetsForTheSameFrame() {
        // The claim the demo's footer makes out loud: with partial rendering on, an
        // invalidation repaints the widgets under the damage instead of the tree.
        Column column = new Column();
        Widget top = new FixedBox(50, 20);
        column.add(top);
        for (int i = 0; i < 8; i++) {
            column.add(new FixedBox(50, 20));
        }
        Scene scene = new Scene(column);
        NoopCanvas canvas = new NoopCanvas(100, 400);

        scene.renderFrame(canvas);
        float whole = scene.metrics().paintedWidgets().last();
        assertEquals(10f, whole, "the column and its nine children");

        // Three damaged frames before the count can fall: enabling the flag asks for one
        // clean full frame, and the frame after it is full too: the back buffer holds
        // the frame from two presents ago, so the previous frame's damage repaints with
        // this one's.
        scene.setPartialRendering(true);
        for (int i = 0; i < 3; i++) {
            top.invalidate();
            scene.renderFrame(canvas);
        }

        float damaged = scene.metrics().paintedWidgets().last();
        assertTrue(damaged < whole, "painted " + damaged + " of " + whole + " widgets");
        assertEquals(1f, scene.metrics().damageRects().last(), "one damaged region");
    }

    @Test
    void aRePresentFrameDoesNotAddToThePaintedCount() {
        // A re-present redraws the same pixels into the other buffer. It is absent from
        // every other metric here, so its widget count must not leak into the next
        // content frame's sample either.
        Scene scene = new Scene(new FixedBox(10, 10));
        NoopCanvas canvas = new NoopCanvas(100, 100);

        scene.renderFrame(canvas);
        int samples = scene.metrics().paintedWidgets().count();
        float painted = scene.metrics().paintedWidgets().last();

        scene.renderFrame(canvas, true);
        assertEquals(samples, scene.metrics().paintedWidgets().count(), "no sample latched");

        scene.requestRender();
        scene.renderFrame(canvas);
        assertEquals(painted, scene.metrics().paintedWidgets().last(), "and none carried over");
    }
}

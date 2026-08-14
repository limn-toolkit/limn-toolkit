package limn.scene;

/**
 * Rolling per-frame performance samples the {@link Scene} collects each frame:
 * frames-per-second, CPU frame time (tick + layout + paint, in ms),
 * input-processing time (ms), and how much of the scene each frame actually
 * repainted. Each {@link Metric} keeps a short history for a sparkline plus the
 * latest value and the windowed average/max.
 *
 * <p>Purely a data holder: no timing logic and no rendering. All access is on
 * the UI thread (recorded during the frame, read while painting a monitor).
 */
public final class FrameMetrics {

    /** Samples kept per metric: the sparkline width. */
    public static final int HISTORY = 120;

    private final Metric fps = new Metric(HISTORY);
    private final Metric frameTimeMs = new Metric(HISTORY);
    private final Metric eventTimeMs = new Metric(HISTORY);
    private final Metric gpuTimeMs = new Metric(HISTORY);
    private final Metric paintedWidgets = new Metric(HISTORY);
    private final Metric damageRects = new Metric(HISTORY);
    private long totalFrames;
    private int paintedThisFrame;

    /**
     * Monotonic count of frames rendered since the scene was created. A monitor
     * can sample this once per second and divide the delta by the elapsed time
     * for a true average FPS, without forcing continuous rendering.
     */
    public long totalFrames() {
        return totalFrames;
    }

    /** Frames per second, derived from the wall-clock period between frames. */
    public Metric fps() {
        return fps;
    }

    /** CPU time to produce one frame (tick + layout + paint), in milliseconds. */
    public Metric frameTime() {
        return frameTimeMs;
    }

    /** Time to dispatch one batch of input events, in milliseconds. */
    public Metric eventTime() {
        return eventTimeMs;
    }

    /**
     * GPU time to execute one frame's draw commands, in milliseconds. Measured
     * by the backend with timer queries, so each sample lags the frame it
     * measured by a few frames. Empty when the backend cannot measure (headless
     * tests, drivers without timer queries).
     */
    public Metric gpuTime() {
        return gpuTimeMs;
    }

    /**
     * Widgets whose {@code onPaint} ran during one frame: the work a frame did,
     * as opposed to how long it took. A widget hidden, or skipped because its
     * subtree missed every repaint region, is not counted; one that falls inside
     * two repaint regions is counted twice, because it painted twice.
     *
     * <p>This is the figure that makes partial rendering visible: the same idle
     * window that repaints its whole tree without it paints a handful of widgets
     * with it, at identical frame times.
     */
    public Metric paintedWidgets() {
        return paintedWidgets;
    }

    /**
     * Repaint regions per frame: {@code 0} for a frame that painted nothing, and
     * the widget count's companion. One rect per damaged region under partial
     * rendering, or {@code 1} for a full-frame repaint, which is what a scene
     * without partial rendering records every frame.
     */
    public Metric damageRects() {
        return damageRects;
    }

    // --------------------------------------------------- recording (scene-only)

    void recordFps(float value) {
        fps.push(value);
    }

    void recordFrameTime(float ms) {
        frameTimeMs.push(ms);
        totalFrames++;
    }

    /**
     * Opens a frame's paint count. At the start rather than at the latch, so a
     * frame that ends without latching (a re-present, which repaints the same
     * pixels into the other buffer and is deliberately absent from every other
     * metric here) discards its count instead of adding it to the next frame's.
     */
    void beginFrame() {
        paintedThisFrame = 0;
    }

    /**
     * Counts one widget's paint. Called from {@code Widget.paintWidget} on every
     * widget that gets past the visibility and paint-cull checks, so it is the
     * hottest write here: a plain field increment, folded into a sample only
     * once the frame ends.
     */
    void countPaintedWidget() {
        paintedThisFrame++;
    }

    /** Closes the frame: latches the widget count and the number of regions it painted. */
    void recordPaintedFrame(int rects) {
        paintedWidgets.push(paintedThisFrame);
        damageRects.push(rects);
    }

    // Every live instance in the process (one per scene): lets a perf HUD
    // aggregate across windows, e.g. the max fps among them. A single-scene
    // reading shows 1-2 fps in an idle main window while auxiliary windows
    // (popups, desktop gadgets) render at full refresh.
    private static final java.util.List<java.lang.ref.WeakReference<FrameMetrics>> INSTANCES =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** An empty set of counters, reset each frame. */
    public FrameMetrics() {
        INSTANCES.add(new java.lang.ref.WeakReference<>(this));
    }

    /** @return every live scene's metrics in this process (dead entries pruned) */
    public static java.util.List<FrameMetrics> processInstances() {
        java.util.List<FrameMetrics> live = new java.util.ArrayList<>();
        synchronized (INSTANCES) {
            java.util.Iterator<java.lang.ref.WeakReference<FrameMetrics>> it = INSTANCES.iterator();
            while (it.hasNext()) {
                FrameMetrics m = it.next().get();
                if (m == null) {
                    it.remove();
                } else {
                    live.add(m);
                }
            }
        }
        return live;
    }

    void recordEventTime(float ms) {
        eventTimeMs.push(ms);
    }

    void recordGpuTime(float ms) {
        gpuTimeMs.push(ms);
    }

    /**
     * A fixed-capacity ring of recent samples (oldest evicted first) exposing
     * the latest value, the windowed average/max, and a copy of the history for
     * a sparkline.
     */
    public static final class Metric {
        private final float[] ring;
        private int count;
        private int next;

        Metric(int capacity) {
            ring = new float[capacity];
        }

        void push(float value) {
            ring[next] = value;
            next = (next + 1) % ring.length;
            if (count < ring.length) {
                count++;
            }
        }

        /** @return how many samples are currently held (up to the capacity) */
        public int count() {
            return count;
        }

        /** @return the most recent sample, or 0 when empty */
        public float last() {
            return count == 0 ? 0f : ring[(next - 1 + ring.length) % ring.length];
        }

        /** @return the mean over the held samples, or 0 when empty */
        public float average() {
            if (count == 0) {
                return 0f;
            }
            double sum = 0;
            for (int i = 0; i < count; i++) {
                sum += at(i);
            }
            return (float) (sum / count);
        }

        /** @return the maximum held sample, or 0 when empty */
        public float max() {
            float m = 0f;
            for (int i = 0; i < count; i++) {
                m = Math.max(m, at(i));
            }
            return m;
        }

        /**
         * Copies the held history, oldest→newest, into {@code dst} (up to its
         * length; the newest samples win if {@code dst} is shorter).
         *
         * @return the number of samples written
         */
        public int copyInto(float[] dst) {
            int n = Math.min(count, dst.length);
            for (int i = 0; i < n; i++) {
                dst[i] = at(count - n + i);
            }
            return n;
        }

        /** @param i 0 = oldest held sample */
        private float at(int i) {
            int start = (next - count + ring.length * 2) % ring.length;
            return ring[(start + i) % ring.length];
        }
    }
}

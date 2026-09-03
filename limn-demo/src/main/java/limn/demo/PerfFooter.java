package limn.demo;

import limn.render3d.Render3DStats;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.scene.Constraints;
import limn.scene.FrameMetrics;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * Performance footer: two rows of live gauges, what a frame costs (FPS, CPU
 * frame time, GPU time, widgets painted, repaint regions) over what the process
 * costs (CPU, heap, GC pause, 3D memory, 3D draw calls), each with the current
 * value and a per-second bar chart of the last 60 ticks (the oldest tick falls
 * off once the window is full). Everything is drawn with the Limn {@link Canvas}.
 *
 * <p><b>Painted and Regions are the pair to watch</b>, because they are the two
 * halves of one claim: turn partial rendering on (View ▸ Partial rendering) and
 * Painted collapses from the whole widget tree to the handful under the damage
 * while Frame does not move, which is the difference between a toolkit that
 * repaints a window and one that repaints a caret.
 *
 * <p><b>Readable &amp; cheap:</b> the readout latches once per second on a
 * {@link Ui#postDelayed} heartbeat (numbers a human can actually read), so an
 * idle app renders ~1 fps; the footer does <em>not</em> force continuous
 * rendering. FPS shows the fastest window in the process (max across every
 * scene's painted-frame delta); auxiliary windows (popups, desktop gadgets)
 * are visible in it too. The per-frame paint allocates nothing: the strings and
 * the unit's measured offset are computed on the 1 Hz latch, not per frame.
 *
 * <p><b>The footer reads with the tree around it — except for time.</b> The demo
 * carries a live direction picker in its toolbar, and a HUD that ignored the
 * axis under it would read as a bug report. Reading right to left, the first
 * card of each row is the rightmost one, and every card right-aligns its text
 * and hangs its chart on the left. The charts themselves keep their axis: the
 * newest tick is the rightmost bar in either direction, because time's flow is
 * not a reading order — the reason a scrub bar and a clock do not mirror
 * (docs/design/direction-axis.md).
 */
public final class PerfFooter extends Widget {

    private static final float HEIGHT = 104;
    // Bar-chart window: the last 30 ticks (one per second). The ring holds
    // exactly 30 samples: the 31st push overwrites the oldest. Thirty and not
    // sixty because a card is about a fifth of the window wide: sixty bars in
    // that space are sub-pixel and read as one smear, which is a chart that
    // shows a shape nobody can follow.
    private static final int SECONDS = 30;
    // Fixed sizes rather than the theme's, and this is the one widget in the demo
    // that should not follow the size axis: the footer is a HUD over a scene whose
    // size step is a control the user changes at will, and at XLARGE a themed label
    // is 17pt; three of those do not fit the row and the card would paint over
    // its neighbour.
    private static final Font NAME_FONT = Font.of(11);
    private static final Font VALUE_FONT = Font.of(15);
    private static final long MB = 1024 * 1024;

    private final Gauge fps = new Gauge("FPS", Color.rgb(0x34D399));
    private final Gauge frame = new Gauge("Frame", Color.rgb(0x4C8DFF));
    private final Gauge gpu = new Gauge("GPU", Color.rgb(0x2DD4BF));
    private final Gauge painted = new Gauge("Painted", Color.rgb(0xFFB454));
    private final Gauge regions = new Gauge("Regions", Color.rgb(0xC792EA));
    private final Gauge cpu = new Gauge("CPU", Color.rgb(0x22C55E));
    private final Gauge mem = new Gauge("Memory", Color.rgb(0x8AB4FF));
    private final Gauge gc = new Gauge("GC", Color.rgb(0xF472B6));
    private final Gauge gpu3d = new Gauge("GPU 3D", Color.rgb(0x38BDF8));
    private final Gauge draws3d = new Gauge("Draws 3D", Color.rgb(0xFB7185));
    private final Gauge[] rowFrame = {fps, frame, gpu, painted, regions};
    private final Gauge[] rowProcess = {cpu, mem, gc, gpu3d, draws3d};

    // Runtime probes (fetched once).
    private final GarbageCollectorMXBean[] gcBeans;
    private final java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

    // 1 Hz sampling state.
    private boolean ticking;
    private boolean latched; // the pre-heartbeat first sample has been taken
    private final java.util.Map<FrameMetrics, Long> lastPerScene = new java.util.WeakHashMap<>();
    private long lastSampleNanos;
    private long lastGcCount;
    private long lastGcTime;

    PerfFooter() {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        gcBeans = beans.toArray(new GarbageCollectorMXBean[0]);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        return constraints.constrain(constraints.maxWidth(), HEIGHT);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        if (!ticking && scene() != null && isShowing()) {
            // Start the 1 Hz heartbeat (also re-armed here if it ever stops).
            ticking = true;
            // The per-window frame deltas are primed by the immediate sample below rather
            // than here: FPS is the one gauge that needs two readings a real interval
            // apart, and it reports a dash until it has them instead of dividing by the
            // microsecond between priming and sampling.
            lastSampleNanos = System.nanoTime();
            // Seed the GC baseline so the first delta is this second's, not since
            // JVM start (and re-seed on every re-show, skipping the hidden gap).
            long[] gcBase = readGcTotals();
            lastGcCount = gcBase[0];
            lastGcTime = gcBase[1];
            scheduleTick();
        }
        // Latch once before the heartbeat's first beat, so the footer does not spend its
        // opening second showing a dash in every card, which is the state every short
        // --screenshot run of this screen was catching.
        //
        // On the first paint of all there is nothing to latch: the scene records a frame
        // when it FINISHES one, so frameTime is still empty while this one is being
        // painted, and sampling here would latch a row of zeroes instead. So it waits for
        // a frame to have landed, which is the next paint, still well inside the warmup
        // frames a capture renders.
        if (ticking && !latched && scene().metrics().frameTime().count() > 0) {
            latched = true;
            sample();
        }

        Theme theme = Theme.current();
        canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface);
        canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, theme.tokensFor(this).radiusMedium(), 1, theme.outline);

        float pad = 10;
        float gap = 6;
        float rowH = (height() - 2 * pad - gap) / 2;
        // Resolved once for the whole paint: cards, alignment and chart anchors
        // must agree about the direction or a card would right-align its text
        // over a chart that stayed on the right.
        boolean rtl = layoutDirection() == LayoutDirection.RTL;
        drawRow(canvas, rowFrame, pad, pad, width() - 2 * pad, rowH, rtl);
        drawRow(canvas, rowProcess, pad, pad + rowH + gap, width() - 2 * pad, rowH, rtl);
    }

    /**
     * Takes a sample now, off the heartbeat, and repaints with it. For the site's capture
     * driver, which photographs this screen: every gauge shows a dash until the first beat
     * and the CPU gauge until the second, because both need a reading a real interval apart
     * from an earlier one, and the driver used to wait 2.6 s of wall clock per shot for the
     * two beats to land. The first reading is the latch the first paint takes; this is the
     * second, over however long the warm-up frames took, which is a real interval measured
     * on the real clock and not a number invented for the picture. No-op off screen.
     */
    public void sampleNow() {
        if (scene() == null || !isShowing() || !ticking) {
            return;
        }
        if (!latched) {
            latched = true; // the baseline the first paint would have taken
        }
        sample();
        invalidate();
    }

    // ------------------------------------------------------------- 1 Hz sampler

    private void scheduleTick() {
        Ui.postDelayed(this::tick, 1000);
    }

    private void tick() {
        if (scene() == null || !isShowing()) {
            ticking = false; // paused; onPaint re-arms when shown again
            return;
        }
        sample();
        invalidate();     // one frame to show the freshly latched values
        scheduleTick();
    }

    private void sample() {
        Scene s = scene();
        FrameMetrics m = s.metrics();
        long now = System.nanoTime();
        double elapsed = Math.max(1e-3, (now - lastSampleNanos) / 1e9);

        // The fastest window in the process: cubes/popups render in their own
        // windows: this scene's own count would sit at 1-2 fps while a gadget
        // runs at full refresh. Max (not sum) reads as "the busiest window".
        double maxFps = 0;
        boolean measured = false;
        for (FrameMetrics fm : FrameMetrics.processInstances()) {
            long total = fm.totalFrames();
            Long prev = lastPerScene.put(fm, total);
            if (prev != null) {
                measured = true;
                maxFps = Math.max(maxFps, (total - prev) / elapsed);
            }
        }
        if (measured) {
            fps.push((float) maxFps, fmt0(maxFps), "fps");
        } else {
            fps.pushText("-", "fps"); // first sample: no previous count to subtract
        }
        lastSampleNanos = now;

        frame.push(m.frameTime().average(), fmt2(m.frameTime().average()), "ms");
        if (m.gpuTime().count() > 0) {
            gpu.push(m.gpuTime().average(), fmt2(m.gpuTime().average()), "ms");
        } else {
            gpu.pushText("-", "ms"); // headless / no timer-query support
        }

        // This scene's own counts, not the process maximum FPS uses: "how much of
        // THIS window a frame repaints" is the question, and a desktop gadget
        // painting six widgets would otherwise flatter it.
        float widgets = m.paintedWidgets().average();
        painted.push(widgets, fmt0(widgets), "widgets/frame");
        float rects = m.damageRects().average();
        regions.push(rects, fmt1(rects), "rects/frame");

        Runtime rt = Runtime.getRuntime();
        float usedMb = (rt.totalMemory() - rt.freeMemory()) / (float) MB;
        float maxMb = rt.maxMemory() / (float) MB;
        mem.push(usedMb, fmt0(usedMb), "of " + fmt0(maxMb) + " MB");

        long[] gcTotals = readGcTotals();
        double pausePerSec = (gcTotals[1] - lastGcTime) / elapsed;
        double countPerSec = (gcTotals[0] - lastGcCount) / elapsed;
        lastGcCount = gcTotals[0];
        lastGcTime = gcTotals[1];
        gc.push((float) pausePerSec, fmt1(pausePerSec) + " ms/s", fmt1(countPerSec) + " gc/s");

        double cpuLoad = -1;
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sun) {
            cpuLoad = sun.getProcessCpuLoad(); // [0..1], or <0 until sampled
        }
        if (cpuLoad >= 0) {
            float pct = (float) (cpuLoad * 100);
            cpu.push(pct, fmt0(pct), "%");
        } else {
            cpu.pushText("-", "%"); // no history point when unavailable
        }

        Render3DStats r3d = s.window() != null ? s.window().backend().render3DStats() : Render3DStats.EMPTY;
        gpu3d.push(r3d.gpuBytes() / (float) MB, fmt1(r3d.gpuBytes() / (float) MB), "MB 3D");
        draws3d.push(r3d.drawCalls(), Integer.toString(r3d.drawCalls()),
                fmtK(r3d.triangles()) + " tris");
    }

    private long[] readGcTotals() {
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean bean : gcBeans) {
            long c = bean.getCollectionCount();
            long t = bean.getCollectionTime();
            if (c > 0) {
                count += c;
            }
            if (t > 0) {
                time += t;
            }
        }
        return new long[]{count, time};
    }

    // --------------------------------------------------------------- rendering

    private void drawRow(Canvas canvas, Gauge[] gauges, float x, float y, float w, float h,
            boolean rtl) {
        Theme theme = Theme.current();
        float cardW = w / gauges.length;
        for (int i = 0; i < gauges.length; i++) {
            // Reading right to left the first gauge takes the rightmost card; the
            // separators sit on the same uniform grid either way.
            float cx = rtl ? x + w - (i + 1) * cardW : x + i * cardW;
            drawCard(canvas, gauges[i], cx, y, cardW, h, rtl);
            if (i > 0) {
                canvas.drawLine(x + i * cardW, y + 4, x + i * cardW, y + h - 4, 1, theme.outline);
            }
        }
    }

    private void drawCard(Canvas canvas, Gauge gauge, float x, float y, float w, float h,
            boolean rtl) {
        Theme theme = Theme.current();
        float pad = 12;
        // Shaped rather than handed over as strings: right-aligning needs the width
        // of the exact line drawn, and the ruler memoizes so the 1 Hz strings cost
        // one lookup per frame.
        ShapedText name = shapeText(gauge.name, NAME_FONT);
        ShapedText value = shapeText(gauge.valueText, VALUE_FONT);
        ShapedText unit = shapeText(gauge.unitText, NAME_FONT);
        float nameX = rtl ? x + w - pad - name.metrics().width() : x + pad;
        float valueX = rtl ? x + w - pad - value.metrics().width() : x + pad;
        float unitX = rtl ? x + w - pad - unit.metrics().width() : x + pad;
        canvas.drawText(name, nameX, y + 11, theme.textMuted);
        canvas.drawText(value, valueX, y + 27, gauge.color);
        canvas.drawText(unit, unitX, y + 38, theme.textMuted);

        // The chart takes 45% on the side reading ends on: the unit line is the
        // widest text here ("of 4096 MB", "12.3k tris") and the two share one card.
        float sx = rtl ? x + pad : x + w * 0.55f;
        float sw = w * 0.45f - pad;
        barChart(canvas, sx, y + 8, sw, h - 14, gauge);
    }

    /**
     * One bar per tick in fixed slots, anchored to the RIGHT edge <b>in both
     * directions</b>: the newest tick is always the rightmost bar and history
     * grows leftward, scrolling left as old ticks fall off the (at most)
     * {@link #SECONDS}-slot window. Which side of the card the chart hangs on
     * mirrors with the row; the axis inside it does not, because time's flow is
     * not a reading order.
     */
    private void barChart(Canvas canvas, float x, float y, float w, float h, Gauge gauge) {
        int n = gauge.count;
        if (n < 1 || w <= 2 || h <= 0) {
            return;
        }
        float scale = gauge.max();
        float slotW = w / SECONDS;
        float barW = Math.max(1f, slotW - 1f); // ~1px gap between bars
        int firstSlot = SECONDS - n; // right-aligned: oldest held tick starts here
        for (int i = 0; i < n; i++) {
            float value = gauge.at(i);
            if (value <= 0) {
                continue; // a zero tick reads as a gap, not a sliver
            }
            float bh = Math.max(1f, Math.min(1f, value / scale) * h);
            canvas.fillRect(x + (firstSlot + i) * slotW, y + h - bh, barW, bh, gauge.fill);
        }
    }

    private static String fmt0(double v) {
        return String.format(Locale.US, "%.0f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** Compact large counts: 27600 → "27.6k", 1_200_000 → "1.2M". */
    private static String fmtK(long v) {
        if (v >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", v / 1e6);
        }
        if (v >= 1_000) {
            return String.format(Locale.US, "%.1fk", v / 1e3);
        }
        return Long.toString(v);
    }

    /** One gauge: a per-second ring for the bar chart plus the latched display text. */
    private static final class Gauge {
        final String name;
        final Color color;
        final Color fill;
        final float[] hist = new float[SECONDS];
        int count;
        int next;
        String valueText = "-";
        String unitText = "";

        Gauge(String name, Color color) {
            this.name = name;
            this.color = color;
            this.fill = color.withAlpha(0.55f); // bar ink: solid enough to read alone
        }

        void push(float sparkValue, String value, String unit) {
            hist[next] = sparkValue;
            next = (next + 1) % hist.length;
            if (count < hist.length) {
                count++;
            }
            valueText = value;
            unitText = unit;
        }

        /** Updates only the text (no history point), for an unavailable metric. */
        void pushText(String value, String unit) {
            valueText = value;
            unitText = unit;
        }

        float at(int i) {
            int start = (next - count + hist.length * 2) % hist.length;
            return hist[(start + i) % hist.length];
        }

        float max() {
            float m = 1e-6f;
            for (int i = 0; i < count; i++) {
                m = Math.max(m, at(i));
            }
            return m;
        }
    }
}

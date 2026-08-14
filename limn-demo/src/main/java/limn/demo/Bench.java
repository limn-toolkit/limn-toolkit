package limn.demo;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.ProgressBar;
import limn.components.ScrollView;
import limn.components.TextField;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Limn half of the heavy-screen benchmark (see scripts/bench/SwingBench.java
 * for the Swing twin: SAME layout, SAME phases, SAME metrics):
 *   750-widget scrollable form + 10 always-visible progress bars.
 *   Phases: startup → idle 4s → 60 Hz animation 10s → 60 Hz scroll 6s.
 * Prints BENCHKV key=value lines and exits. Run:
 *   ./gradlew :limn-demo:run --args="--bench on"   (partial rendering on)
 *   ./gradlew :limn-demo:run --args="--bench off"  (full repaint per frame)
 */
final class Bench {

    private static final long T0 = System.nanoTime();
    private static final int ROWS = 150;
    private static final int TOP_BARS = 10;

    private Bench() {
    }

    /** Samples process CPU per named phase on a daemon thread. */
    private static final class CpuSampler extends Thread {
        volatile String phase = "startup";
        final java.util.Map<String, double[]> acc = new java.util.concurrent.ConcurrentHashMap<>();
        final com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        CpuSampler() {
            setDaemon(true);
        }

        @Override
        public void run() {
            while (true) {
                double load = os.getProcessCpuLoad();
                if (load >= 0) {
                    double[] a = acc.computeIfAbsent(phase, k -> new double[2]);
                    synchronized (a) {
                        a[0] += load;
                        a[1]++;
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        double pct(String name) {
            double[] a = acc.get(name);
            return a == null || a[1] == 0 ? 0 : 100.0 * a[0] / a[1];
        }
    }

    static void run(boolean partialRendering) {
        System.out.println("BENCHPID " + ProcessHandle.current().pid());
        CpuSampler cpu = new CpuSampler();
        cpu.start();
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "BENCHKV partial=%s%n", partialRendering ? 1 : 0));

        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(
                    new WindowConfig("LimnBench", 1100, 800, true, true));

            ProgressBar[] topBars = new ProgressBar[TOP_BARS];
            Row top = new Row();
            top.gap(8);
            for (int i = 0; i < TOP_BARS; i++) {
                topBars[i] = new ProgressBar();
                top.add(Expanded.of(topBars[i], 1));
            }

            Column list = new Column();
            list.gap(4);
            for (int i = 0; i < ROWS; i++) {
                Row row = new Row();
                row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
                row.add(new Label(String.format("Item %03d", i)));
                row.add(Expanded.of(new TextField().setText("Value " + i), 1));
                row.add(new Checkbox(Checkbox.Variant.BOX, "Enabled").setChecked(i % 2 == 0));
                ProgressBar bar = new ProgressBar();
                bar.setProgress((i % 100) / 100f);
                row.add(Expanded.of(bar, 1));
                row.add(new Button("Open"));
                list.add(row);
            }
            ScrollView scroll = new ScrollView(list);
            Label status = new Label("Ready.");

            Column page = new Column();
            page.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
            page.add(top);
            page.add(Expanded.of(scroll, 1));
            page.add(status);

            Scene scene = new Scene(new Padding(limn.scene.Insets.all(8), page));
            scene.setBackground(Theme.current().background);
            scene.setPartialRendering(partialRendering);
            scene.bind(window);
            window.show();

            // Wait for the first painted frame, then run the phase chain.
            Ui.post(new Runnable() {
                @Override
                public void run() {
                    if (scene.metrics().totalFrames() == 0) {
                        Ui.postDelayed(this, 1);
                        return;
                    }
                    out.append(kv("startup_ms", (System.nanoTime() - T0) / 1e6));
                    Ui.postDelayed(() -> startIdle(cpu, out, scene, topBars, status, scroll, window), 1000);
                }
            });

            backend.runEventLoop();
        }
        System.gc();
        System.gc();
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
        Runtime rt = Runtime.getRuntime();
        out.append(kv("heap_mb", (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)));
        System.out.print(out);
        System.out.flush();
    }

    private static void startIdle(CpuSampler cpu, StringBuilder out, Scene scene,
                                  ProgressBar[] topBars, Label status, ScrollView scroll,
                                  NativeWindow window) {
        cpu.phase = "idle";
        long idleStart = scene.metrics().totalFrames();
        Ui.postDelayed(() -> {
            out.append(kv("idle_paints", scene.metrics().totalFrames() - idleStart));
            out.append(kv("idle_cpu_pct", cpu.pct("idle")));
            startAnim(cpu, out, scene, topBars, status, scroll, window);
        }, 4000);
    }

    private static void startAnim(CpuSampler cpu, StringBuilder out, Scene scene,
                                  ProgressBar[] topBars, Label status, ScrollView scroll,
                                  NativeWindow window) {
        cpu.phase = "anim";
        long startPaints = scene.metrics().totalFrames();
        long startNanos = System.nanoTime();
        int[] tick = {0};
        Ui.post(new Runnable() {
            @Override
            public void run() {
                tick[0]++;
                for (int i = 0; i < topBars.length; i++) {
                    topBars[i].setProgress(((tick[0] + i * 10) % 100) / 100f);
                }
                status.setText("tick " + tick[0]);
                if (tick[0] < 600) { // ~10 s
                    Ui.postDelayed(this, 16);
                    return;
                }
                double secs = (System.nanoTime() - startNanos) / 1e9;
                out.append(kv("anim_ticks_per_s", tick[0] / secs));
                out.append(kv("anim_paints_per_s", (scene.metrics().totalFrames() - startPaints) / secs));
                out.append(kv("anim_frame_ms", scene.metrics().frameTime().average()));
                out.append(kv("anim_cpu_pct", cpu.pct("anim")));
                startScroll(cpu, out, scene, scroll, window);
            }
        });
    }

    private static void startScroll(CpuSampler cpu, StringBuilder out, Scene scene,
                                    ScrollView scroll, NativeWindow window) {
        cpu.phase = "scroll";
        long startPaints = scene.metrics().totalFrames();
        long startNanos = System.nanoTime();
        float max = ROWS * 38f; // beyond the real max: scrollTo clamps
        int[] tick = {0};
        Ui.post(new Runnable() {
            @Override
            public void run() {
                tick[0]++;
                float phase = (tick[0] * 40) % (2 * max);
                scroll.scrollTo(0, phase < max ? phase : 2 * max - phase);
                if (tick[0] < 360) { // ~6 s
                    Ui.postDelayed(this, 16);
                    return;
                }
                double secs = (System.nanoTime() - startNanos) / 1e9;
                out.append(kv("scroll_paints_per_s", (scene.metrics().totalFrames() - startPaints) / secs));
                out.append(kv("scroll_frame_ms", scene.metrics().frameTime().average()));
                out.append(kv("scroll_cpu_pct", cpu.pct("scroll")));
                cpu.phase = "end";
                window.requestClose();
            }
        });
    }

    private static String kv(String key, double value) {
        return String.format(Locale.ROOT, "BENCHKV %s=%.2f%n", key, value);
    }
}

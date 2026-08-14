import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.lang.management.ManagementFactory;

/**
 * Swing half of the heavy-screen benchmark (see Bench.java in limn-demo for
 * the Limn twin: SAME layout, SAME phases, SAME metrics):
 *   750-widget scrollable form + 10 always-visible progress bars.
 *   Phases: startup → idle 4s → 60 Hz animation 10s → 60 Hz scroll 6s.
 * Prints BENCHKV key=value lines and exits. Run: java SwingBench.java
 */
public final class SwingBench {

    static final long T0 = System.nanoTime();
    static final int ROWS = 150;
    static final int TOP_BARS = 10;

    static volatile long firstPaintNanos = -1;

    /** Samples process CPU per named phase on a daemon thread. */
    static final class CpuSampler extends Thread {
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
                    acc.computeIfAbsent(phase, k -> new double[2]);
                    double[] a = acc.get(phase);
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
            if (a == null || a[1] == 0) {
                return 0;
            }
            return 100.0 * a[0] / a[1];
        }
    }

    /** Counts EDT paint cycles (≈ frames) through the RepaintManager. */
    static final class CountingRepaintManager extends RepaintManager {
        static volatile long paintCycles;

        @Override
        public void paintDirtyRegions() {
            paintCycles++;
            super.paintDirtyRegions();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("BENCHPID " + ProcessHandle.current().pid());
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        RepaintManager.setCurrentManager(new CountingRepaintManager());
        CpuSampler cpu = new CpuSampler();
        cpu.start();
        SwingUtilities.invokeLater(() -> buildAndRun(cpu));
    }

    static void buildAndRun(CpuSampler cpu) {
        JProgressBar[] topBars = new JProgressBar[TOP_BARS];
        JPanel top = new JPanel(new GridLayout(1, TOP_BARS, 8, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                if (firstPaintNanos < 0) {
                    firstPaintNanos = System.nanoTime();
                }
                super.paintComponent(g);
            }
        };
        for (int i = 0; i < TOP_BARS; i++) {
            topBars[i] = new JProgressBar(0, 100);
            top.add(topBars[i]);
        }

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (int i = 0; i < ROWS; i++) {
            JPanel row = new JPanel(new GridLayout(1, 5, 8, 0));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            row.add(new JLabel(String.format("Item %03d", i)));
            row.add(new JTextField("Value " + i));
            row.add(new JCheckBox("Enabled", i % 2 == 0));
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setValue(i % 100);
            row.add(bar);
            row.add(new JButton("Open"));
            list.add(row);
        }
        list.add(Box.createVerticalGlue());
        JScrollPane scroll = new JScrollPane(list);
        JLabel status = new JLabel("Ready.");

        JFrame frame = new JFrame("SwingBench");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));
        frame.add(top, BorderLayout.NORTH);
        frame.add(scroll, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(1100, 800);
        frame.setVisible(true);

        StringBuilder out = new StringBuilder();
        // Phase chain: idle 4s → anim 10s → scroll 6s → report.
        Timer settle = new Timer(1000, e1 -> {
            ((Timer) e1.getSource()).stop();
            out.append(kv("startup_ms", (firstPaintNanos - T0) / 1e6));
            long idleStartPaints = CountingRepaintManager.paintCycles;
            cpu.phase = "idle";
            Timer idle = new Timer(4000, e2 -> {
                ((Timer) e2.getSource()).stop();
                out.append(kv("idle_paints", CountingRepaintManager.paintCycles - idleStartPaints));
                out.append(kv("idle_cpu_pct", cpu.pct("idle")));
                runAnim(cpu, out, topBars, status, scroll, frame);
            });
            idle.setRepeats(false);
            idle.start();
        });
        settle.setRepeats(false);
        settle.start();
    }

    static void runAnim(CpuSampler cpu, StringBuilder out, JProgressBar[] topBars,
                        JLabel status, JScrollPane scroll, JFrame frame) {
        cpu.phase = "anim";
        long startPaints = CountingRepaintManager.paintCycles;
        long startNanos = System.nanoTime();
        int[] tick = {0};
        Timer anim = new Timer(16, null);
        anim.addActionListener(e -> {
            tick[0]++;
            for (int i = 0; i < topBars.length; i++) {
                topBars[i].setValue((tick[0] + i * 10) % 100);
            }
            status.setText("tick " + tick[0]);
            if (tick[0] >= 600) { // ~10 s
                anim.stop();
                double secs = (System.nanoTime() - startNanos) / 1e9;
                out.append(kv("anim_ticks_per_s", tick[0] / secs));
                out.append(kv("anim_paints_per_s", (CountingRepaintManager.paintCycles - startPaints) / secs));
                out.append(kv("anim_cpu_pct", cpu.pct("anim")));
                runScroll(cpu, out, scroll, frame);
            }
        });
        anim.start();
    }

    static void runScroll(CpuSampler cpu, StringBuilder out, JScrollPane scroll, JFrame frame) {
        cpu.phase = "scroll";
        long startPaints = CountingRepaintManager.paintCycles;
        long startNanos = System.nanoTime();
        int max = scroll.getVerticalScrollBar().getMaximum();
        int[] tick = {0};
        Timer sc = new Timer(16, null);
        sc.addActionListener(e -> {
            tick[0]++;
            int phase = (tick[0] * 40) % (2 * max);
            scroll.getVerticalScrollBar().setValue(phase < max ? phase : 2 * max - phase);
            if (tick[0] >= 360) { // ~6 s
                sc.stop();
                double secs = (System.nanoTime() - startNanos) / 1e9;
                out.append(kv("scroll_paints_per_s", (CountingRepaintManager.paintCycles - startPaints) / secs));
                out.append(kv("scroll_cpu_pct", cpu.pct("scroll")));
                finish(out, frame);
            }
        });
        sc.start();
    }

    static void finish(StringBuilder out, JFrame frame) {
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
        frame.dispose();
        System.exit(0);
    }

    static String kv(String key, double value) {
        return String.format(java.util.Locale.ROOT, "BENCHKV %s=%.2f%n", key, value);
    }
}

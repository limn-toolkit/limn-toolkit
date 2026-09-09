import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.scene.Change;
import limn.scene.ChangeObserver;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The delivery path of ADR 040, end to end: what {@code notifyChange} costs at zero, one and three
 * watchers, with and without a handler, and what {@code notifyTextEdit} costs on a synthetic
 * keystroke run. ADR 040 §0.2 measured eight fan-out <em>shapes</em> and chose the storage; this
 * measures the design's own methods, which add a second array walk for the scene, the paint-depth
 * read, a virtual call, a {@code switch} and the slot invocation on the {@code USER} path. §6.10.
 *
 * <p>The same harness as §0.2: four distinct watcher classes so the call site is megamorphic,
 * allocation read from the per-thread counter {@code AllocationProbe} uses, the least of 6 runs of
 * 100 000 -- least, not mean, because the just-in-time compiler occasionally charges a few
 * kilobytes of its own bookkeeping to whichever thread tripped it -- and escape analysis off,
 * because that is the number to design against. Run from the repository root, after
 * {@code ./gradlew :limn-toolkit:classes}:
 *
 * <pre>
 * java -XX:+UseSerialGC -XX:-DoEscapeAnalysis \
 *      -cp limn-toolkit/build/classes/java/main scripts/bench/NotifyBench.java
 * </pre>
 *
 * <p>Prints one line per shape: nanoseconds per call and bytes per call.
 */
public final class NotifyBench {

    private static final int RUNS = 6;
    private static final int CALLS = 100_000;

    /** A widget with one aspect of its own, announced from one seam. */
    static final class Probe extends Widget {
        int value;
        int handled;

        void write(int next, Change.Origin origin) {
            value = next;
            notifyChange(Change.of(Change.Aspect.VALUE, origin));
        }

        void type(int at, Change.Origin origin) {
            notifyTextEdit(origin, at, 0, 1);
        }

        @Override
        protected void handleUserChange(Change.Aspect aspect) {
            handled++;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(10, 10);
        }
    }

    // Four classes, so the observer call site is megamorphic, as it is in an application with a
    // binding, an inspector, a test and a bridge all watching.
    static int sink;

    static final class W1 implements ChangeObserver {
        public void changed(Widget s, Change c) { sink += 1; }
    }

    static final class W2 implements ChangeObserver {
        public void changed(Widget s, Change c) { sink += 2; }
    }

    static final class W3 implements ChangeObserver {
        public void changed(Widget s, Change c) { sink += 3; }
    }

    static final class W4 implements ChangeObserver {
        public void changed(Widget s, Change c) { sink += c.aspect().ordinal(); }
    }

    public static void main(String[] args) {
        ExecutorService workers = Executors.newFixedThreadPool(1);
        UiRuntime runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        try {
            // Two passes over every shape, printing the second: the compiler has then seen every
            // arity and origin at every site, so no shape is measured while it is still the first
            // to be compiled and no shape profits from being the only one the sites had seen.
            for (int pass = 0; pass < 2; pass++) {
                boolean print = pass == 1;
                if (print) {
                    System.out.println("shape\tns/call\tB/call");
                }
                for (int watchers : new int[] {0, 1, 3}) {
                    for (boolean user : new boolean[] {false, true}) {
                        Probe probe = new Probe();
                        Scene scene = new Scene(probe);
                        attach(probe, scene, watchers);
                        Change.Origin origin = user ? Change.Origin.USER : Change.Origin.CODE;
                        int[] tick = {0};
                        report(print, "notifyChange, " + watchers + " watcher(s), "
                                + (user ? "handler" : "no handler"), () -> probe.write(++tick[0], origin));
                    }
                }
                for (int watchers : new int[] {0, 1, 3}) {
                    Probe probe = new Probe();
                    Scene scene = new Scene(probe);
                    attach(probe, scene, watchers);
                    int[] at = {0};
                    report(print, "notifyTextEdit, " + watchers + " watcher(s), keystroke",
                            () -> probe.type(at[0]++, Change.Origin.USER));
                }
            }
        } finally {
            Ui.uninstall(runtime);
            workers.shutdownNow();
        }
    }

    /** Puts {@code count} watchers on the widget and its scene, alternating the four classes. */
    private static void attach(Probe probe, Scene scene, int count) {
        ChangeObserver[] classes = {new W1(), new W2(), new W3(), new W4()};
        for (int i = 0; i < count; i++) {
            probe.observeChanges(classes[i % 4]);
        }
        if (count > 0) {
            scene.observeChanges(classes[(count + 1) % 4]);
        }
    }

    private static void report(boolean print, String shape, Runnable call) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        // Warm up: load the classes, link the sites, let the compiler see the shape.
        for (int i = 0; i < CALLS * 5; i++) {
            call.run();
        }
        double leastNanos = Double.MAX_VALUE;
        long leastBytes = Long.MAX_VALUE;
        for (int run = 0; run < RUNS; run++) {
            long bytesBefore = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            for (int i = 0; i < CALLS; i++) {
                call.run();
            }
            long elapsed = System.nanoTime() - start;
            long bytes = bean.getThreadAllocatedBytes(thread) - bytesBefore;
            leastNanos = Math.min(leastNanos, elapsed / (double) CALLS);
            leastBytes = Math.min(leastBytes, bytes / CALLS);
        }
        if (print) {
            System.out.println(String.format(Locale.ROOT, "%s\t%.2f\t%d", shape, leastNanos, leastBytes));
        }
    }
}

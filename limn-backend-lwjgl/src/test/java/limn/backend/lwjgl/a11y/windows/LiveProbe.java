package limn.backend.lwjgl.a11y.windows;

import limn.backend.AccessibilityBridge;
import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.backend.lwjgl.a11y.ProbeScene;
import limn.components.Slider;
import limn.concurrent.Ui;
import limn.scene.Scene;
import limn.scene.Widget;

import java.util.Arrays;
import java.util.Locale;

/**
 * The demo this bridge is meant to be read from: real widgets, really painted, in a window the
 * backend opened, read by a real screen reader.
 *
 * <p>Not a test: it does not assert, it stays alive. It is here because the one thing this module's
 * own cases cannot check is the conversation with UI Automation — whether {@code WM_GETOBJECT}
 * reaches this bridge, whether the slot order read off the guest is the order UI Automation calls,
 * and whether what is written into a {@code VARIANT} arrives as what it meant.
 *
 * <p><b>It went through two weaker shapes first, and both were worth leaving behind.</b> The first
 * published a tree built by hand, which tested what a bridge does with a tree and never what a
 * scene puts in one — and both defects a live reader has ever found in this work were on that side.
 * The second put real widgets in a real scene but painted nothing, which read correctly and showed
 * a blank window: true, and impossible to look at and believe. This one is the backend's own
 * window with the backend's own renderer, so what a screen reader says and what a person sees are
 * the same thing.
 *
 * <p><b>With {@code -Dprobe.timing=true} it also measures</b>, for ADR&nbsp;039 &sect;13.5 and
 * &sect;13.19: what one call to {@code UiaClientsAreListening()} costs, which is the per-frame gate
 * every window pays when nobody is reading it; and, with the backend's bridge wrapped in a
 * {@link TimedBridge}, how many events each frame's difference produces and what one raise costs.
 * The count is only meaningful with a reader attached — {@code emit} returns at its first line for
 * a node no client has asked for — so a timing run is an NVDA run.
 *
 * <p><b>The bridge's own trace is separate and needs no probe</b> ({@link UiaTrace}):
 * {@code -Dlimn.a11y.uia.trace=<file>} writes every call into {@code UIAutomationCore}, every
 * decision not to raise, and every provider entry point a client called, one flushed line each,
 * from any run of any application — the demo jar included. It is off unless that property is
 * given. A timing run sets {@link UiaWindow#trace} as well, so the same lines also appear on
 * standard output with the probe's own stamp.
 *
 * <pre>
 * java -jar limn-a11y-windows-probe.jar
 * java -Dprobe.timing=true -Dprobe.cycle=scroll -Dprobe.tickMs=100 -jar limn-a11y-windows-probe.jar
 * java -Dprobe.timing=true -Dprobe.cycle=drag -Dprobe.tickMs=100 -jar limn-a11y-windows-probe.jar
 * java -Dlimn.a11y.uia.trace=C:/limn/uia.log -jar limn-a11y-windows-probe.jar
 * </pre>
 */
public final class LiveProbe {

    private LiveProbe() {
    }

    /**
     * @param args unused
     */
    public static void main(String[] args) {
        boolean timing = Boolean.getBoolean("probe.timing");
        int tickMs = Integer.getInteger("probe.tickMs", 6_000);
        String cycle = System.getProperty("probe.cycle", "focus");
        say("uiautomationcore available: " + Uia.isAvailable());
        say("user32 available:           " + UiaWindow.isAvailable());
        say("oleaut32 available:         " + UiaStrings.isAvailable());
        say("cycle=" + cycle + " tickMs=" + tickMs + " timing=" + timing);
        // Said in the preflight, because a run that believed it was tracing and was not is the
        // one failure a trace switch can have that nothing downstream reveals.
        say("bridge trace:              "
                + (System.getProperty(UiaTrace.PROPERTY) == null ? "off (-D" + UiaTrace.PROPERTY
                        + "=<file> turns it on)" : System.getProperty(UiaTrace.PROPERTY)
                        + (UiaTrace.file == null ? " -- NOT OPENED, see stderr" : " (open)")));

        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(
                    new WindowConfig("Limn accessibility probe", 480, 320, true, true));

            // The two seams an application needs and this bridge was the first to ask for: the
            // window's own handle to attach a provider to, and somewhere to put the bridge that
            // comes back.
            long hwnd = window.nativeHandle();
            say("hwnd: " + Long.toHexString(hwnd));
            // Not installed here any more: the backend opens the platform's bridge on the first
            // ask, so this probe exercises the same path an application does.
            AccessibilityBridge bridge = window.accessibility();
            say("bridge: " + bridge.getClass().getSimpleName()
                    + "  listening=" + bridge.isListening());

            EmitTally tally = null;
            if (timing) {
                timeTheGate();
                if (bridge instanceof UiaBridge real) {
                    // The same bridge, counted at the seam the scene sees. Installed before the
                    // scene binds, because the bind is what hands the bridge its host.
                    tally = new EmitTally();
                    window.setAccessibility(new TimedBridge(real, tally, LiveProbe::say));
                    // And the bridge's own trace, which is where a raise now reports how long
                    // it took and on which thread: the emit the tally times is an enqueue
                    // since §13.28, so the raise's cost is only visible from here.
                    // Every line, stamped: a reader's own log is timestamped, and the question
                    // a live run answers is what we said at the moment the reader asked.
                    long started = System.nanoTime();
                    UiaWindow.trace = line -> say("TRACE +"
                            + (System.nanoTime() - started) / 1_000_000 + "ms " + line.strip());
                } else {
                    say("nothing to time: the bridge is " + bridge.getClass().getName());
                }
            }

            ProbeScene probe = new ProbeScene();
            Scene scene = new Scene(probe.root());
            scene.bind(window);
            window.setFrameCallback((renderer, frame) ->
                    scene.renderFrame(renderer.canvas(), frame.rePresent(), frame.gpuFrameMs()));

            // Something moves every few seconds so a listening reader has events to hear, and each
            // one is raised by the scene from a real focus move or a real toggle rather than
            // published by hand. Posted rather than looped, because the event loop below owns this
            // thread and a widget may only be touched on it.
            EmitTally counted = tally;
            boolean[] subjectFocused = {false};
            Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                // The foreground first, every time. A screen reader announces the focused element
                // of the window in front; a window nobody brought forward is one it never reaches,
                // and on this guest the terminal that launched the probe keeps taking it back.
                window.focus();
                if (!subjectFocused[0]) {
                    // A reader speaks about the control that has the keyboard, so the scroll and
                    // drag cycles put it on the list and the slider first, on a tick of their own:
                    // a frame that also moved the focus would count a focus change among the
                    // events of a scroll.
                    subjectFocused[0] = true;
                    if (focusTheSubjectOf(cycle, probe, scene)) {
                        Ui.postDelayed(tick[0], tickMs);
                        return;
                    }
                }
                probe.tick(scene);
                if (timing && bridge instanceof UiaBridge real) {
                    say("GATE listening=" + real.isListening() + " advised=" + real.advisedEvents());
                }
                if (probe.steps() < 40) {
                    Ui.postDelayed(tick[0], tickMs);
                } else {
                    // Summarised here, before the close: what is printed after the event loop
                    // is printed after the window's teardown, and a teardown that ends in native
                    // code ends the process with it.
                    if (counted != null) {
                        counted.close();
                        say(counted.summary());
                    }
                    window.close();
                }
            };
            Ui.postDelayed(tick[0], 8_000);

            backend.runEventLoop();
            say("DONE");
        }
    }

    /**
     * Puts the keyboard on the widget a cycle moves, so that a reader has a reason to speak about
     * it: NVDA announces a value change on the focused control and says nothing about one on a
     * control nobody is on.
     *
     * @return whether a focus was requested, and so a frame spent on it
     */
    private static boolean focusTheSubjectOf(String cycle, ProbeScene probe, Scene scene) {
        switch (cycle) {
            case "scroll" -> {
                scene.requestFocus(probe.rows());
                say("focused the list");
                return true;
            }
            case "drag" -> {
                for (Widget widget : probe.focusable) {
                    if (widget instanceof Slider slider) {
                        scene.requestFocus(slider);
                        say("focused the slider");
                        return true;
                    }
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** How many calls one batch makes; enough that the clock's resolution is not the answer. */
    private static final int CALLS_PER_BATCH = 100_000;
    /** How many batches, of which the first two warm the call path and are not reported. */
    private static final int BATCHES = 12;
    private static final int WARM_UP = 2;

    /**
     * Times {@code UiaClientsAreListening()}, the one platform call a Windows window pays per
     * frame when nobody is reading it (ADR&nbsp;039 &sect;6).
     *
     * <p>Whether it answered true is printed with the number, because the cost may not be the
     * same in both states and the number is only meaningful with its state beside it.
     */
    private static void timeTheGate() {
        double[] perCall = new double[BATCHES];
        int answeredTrue = 0;
        for (int b = 0; b < BATCHES; b++) {
            long start = System.nanoTime();
            for (int i = 0; i < CALLS_PER_BATCH; i++) {
                if (Uia.clientsAreListening()) {
                    answeredTrue++;
                }
            }
            perCall[b] = (System.nanoTime() - start) / (double) CALLS_PER_BATCH;
        }
        double[] kept = Arrays.copyOfRange(perCall, WARM_UP, BATCHES);
        Arrays.sort(kept);
        say(String.format(Locale.ROOT,
                "TIMING UiaClientsAreListening ns/call min=%.1f median=%.1f max=%.1f"
                        + " (%d batches of %d after %d warm-up; answered true %d of %d times;"
                        + " warm-up batches %.1f %.1f)",
                kept[0], kept[kept.length / 2], kept[kept.length - 1], kept.length,
                CALLS_PER_BATCH, WARM_UP, answeredTrue, BATCHES * CALLS_PER_BATCH,
                perCall[0], perCall[1]));
    }

    private static void say(String line) {
        System.out.println(line);
        System.out.flush();
    }
}

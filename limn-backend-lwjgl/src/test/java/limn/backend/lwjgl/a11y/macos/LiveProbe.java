package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.backend.lwjgl.a11y.ProbeScene;
import limn.concurrent.Ui;
import limn.scene.Scene;
import limn.scene.Widget;

import java.util.ArrayList;
import java.util.List;

/**
 * The demo this bridge is meant to be read from: real widgets, really painted, in a window the
 * backend opened.
 *
 * <p>Not a test: it does not assert, it stays alive. It exists because the one thing this module's
 * own cases cannot check is the conversation with AppKit, and because of what the Windows run
 * proved about the shape of a probe. That probe went through two weaker shapes first and both were
 * worth leaving behind: one published a tree built by hand, which tested what a bridge does with a
 * tree and never what a scene puts in one; the next put real widgets in a real scene and painted
 * nothing, which read correctly and showed a blank window — true, and impossible to look at and
 * believe. This is the third shape from the start: the backend's own window with the backend's own
 * renderer, so that what a reader says and what a person sees are the same thing.
 *
 * <p><b>It is not yet a VoiceOver demo, and it says so on startup.</b> {@link AxBridge} holds a
 * snapshot and vends no elements yet, so what this probe currently shows is the half above the
 * platform: that the scene describes the widgets it is painting, published on the first frame
 * because §2.2 asks this platform for a priming publish. When the element registry lands, the same
 * probe becomes the run VoiceOver reads, unchanged.
 *
 * <p><b>macOS needs the main thread.</b> AppKit will not open a window from anywhere else, so this
 * must run with {@code -XstartOnFirstThread}, and it refuses to start rather than dying obscurely
 * inside GLFW when it was not given.
 *
 * <pre>
 * java -XstartOnFirstThread -jar limn-a11y-macos-probe.jar
 * </pre>
 *
 * <p><b>{@code -Dprobe.timing=true} is the §13.19 run.</b> The platform's bridge is opened the way
 * an application would open it and installed behind a {@link TimingBridge} through
 * {@code NativeWindow#setAccessibility}, so that every frame that published or emitted is a sample:
 * how many events the scene emitted in that frame, what its publish cost, what its drain cost
 * and how many notifications reached AppKit. Every tick prints the samples since the last one
 * and the run ends with the summary; with {@code -Dprobe.cycle=scroll} or {@code drag} and a short
 * {@code -Dprobe.tickMs} that is the count the record asks for, taken with VoiceOver attached.
 * {@code -Dprobe.steps} is how many ticks to run, forty when unsaid.
 *
 * <p><b>{@code -Dprobe.focus=<n>|list} puts the keyboard on one widget before a cycle that does not
 * move it.</b> VoiceOver speaks a value that changed only when its cursor is on the thing that
 * changed: the first scroll, drag and value runs on the guest posted every notification and the
 * caption panel showed the window's title and nothing else, because the cursor had stayed where
 * it landed. A number is an index into the focus cycle's order (6 is the slider, 1 the check
 * box), {@code list} is the list the scroll cycle pages, and the move happens once, three seconds
 * before the first tick, so the reader has landed by the time anything changes. Focus and value
 * are still driven separately, which is the rule the check box taught (§2.4): the focus moves
 * once and then only values move.
 */
public final class LiveProbe {

    private LiveProbe() {
    }

    /**
     * @param args unused
     */
    public static void main(String[] args) {
        // The guest has no accelerated pixel format, so GLFW finds none and the backend falls back
        // to a software NSOpenGLContext -- a handful of frames a second, which is plenty for a
        // window whose job is to be looked at and read. Saying it here means a slow window is not
        // mistaken for a hung one.
        // Its own pid, because `sudo launchctl asuser` puts a wrapper process in front of this one
        // and a client pointed at the wrapper gets kAXErrorCannotComplete in a tenth of a
        // millisecond -- which looks exactly like a bridge that is not answering.
        System.out.println("pid=" + ProcessHandle.current().pid());
        System.out.println("If this window paints slowly, that is the backend's macOS software GL "
                + "fallback and not a stall.");

        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(
                    WindowConfig.of("Limn accessibility probe", 480, 560));

            // The two seams an application needs, which the Windows bridge was the first to ask for
            // and this one needs just as much: the window's own handle, and somewhere to put the
            // bridge that comes back. Without both, an application could add this artifact to its
            // classpath and never install it.
            long nsWindow = window.nativeHandle();
            System.out.println("NSWindow: 0x" + Long.toHexString(nsWindow)
                    + (nsWindow == 0 ? "   !!! zero: the backend has not been taught this platform" : ""));
            // Not installed here any more: the backend opens the platform's bridge on the first
            // ask, so this probe exercises the same path an application does rather than a shortcut
            // only a probe knows about. The timing run is the one exception, and it uses the other
            // half of the same seam: it opens the platform's bridge itself, puts the instrument in
            // front of it and hands the pair to the window, which is exactly what an application
            // installing a bridge of its own does.
            TimingBridge timing = null;
            AccessibilityBridge bridge;
            if (Boolean.getBoolean("probe.timing")) {
                timing = new TimingBridge(AxBridge.openIfEnabled(nsWindow));
                window.setAccessibility(timing);
                bridge = timing.inner();
            } else {
                bridge = window.accessibility();
            }
            // Kept typed as well, because the probe prints what the bridge is holding and the two
            // questions a run must separate are "does the scene describe what it painted" and
            // "does the platform reach what the bridge vends".
            AxBridge ax = bridge instanceof AxBridge opened ? opened : null;
            // The bridge keeps no log of its own (CRIT-5): this run attaches the trace and prints
            // what reached it once per tick, starting the buffer again each time, so the probe's
            // memory is one tick's worth however long VoiceOver keeps asking.
            List<String> traced = new ArrayList<>();
            if (ax != null) ax.trace(traced::add);
            System.out.println("bridge: " + bridge.getClass().getSimpleName()
                    + (timing == null ? "" : " behind TimingBridge")
                    + "  listening=" + bridge.isListening()
                    + "  needsPrimingPublish=" + bridge.needsPrimingPublish()
                    + (bridge == AccessibilityBridge.NONE
                       ? "   !!! NONE: AppKit was not reachable, so nothing will be read" : ""));

            ProbeScene probe = new ProbeScene();
            Scene scene = new Scene(probe.root());
            scene.bind(window);
            window.setFrameCallback((renderer, frame) ->
                    scene.renderFrame(renderer.canvas(), frame));

            // Something moves every few seconds, so that a reader has events to hear and a person
            // has something to watch; each is a real focus move or a real toggle rather than a tree
            // published by hand. Posted rather than looped, because the event loop below owns this
            // thread and a widget may only be touched on it.
            // A reader needs time to finish a phrase before the next one starts, and a run needs to
            // reach every widget: those pull opposite ways, so the interval is a knob.
            int tickMs = Integer.getInteger("probe.tickMs", 6_000);
            int steps = Integer.getInteger("probe.steps", 40);
            Widget<?> first = firstFocus(probe, System.getProperty("probe.focus"));
            if (first != null) {
                Ui.postDelayed(() -> {
                    window.focus();
                    scene.requestFocus(first);
                    System.out.println("--- focus first: " + first.getClass().getSimpleName() + " ---");
                }, 1_000);
            }
            int[] step = {0};
            int[] lastSample = {0};
            TimingBridge measured = timing;
            Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                // The foreground first, every time. A reader announces the focused element of the
                // window in front, and on these guests the terminal that launched the probe keeps
                // taking it back -- which looks exactly like a broken bridge.
                window.focus();
                probe.tick(scene);
                // republishNow() is the platform's path, and this probe is standing in for the
                // platform: it is the user-interface thread, inside the pump, which is exactly the
                // contract that call names. It is here because the listening gate is shut until
                // the elements land, so the priming publish would otherwise be the only tree there
                // ever is.
                if (ax != null) {
                    dump(ax.tree());
                    System.out.println("    listening=" + ax.isListening()
                            + " elements=" + ax.elementCount()
                            + " pushed=" + ax.pushedElements().length
                            + " pushes=" + ax.pushes()
                            + " queued=" + ax.queuedEvents()
                            + " focusedAsks=" + ax.focusedElementAsks()
                            + "/" + ax.focusedElementAsksOnView() + " (element/view)");
                    System.out.println("    focus answers: " + linesAfter(traced, "focused "));
                    System.out.println("    emitted since last step: "
                            + linesAfter(traced, "emitted "));
                    // What was actually posted since the last step. A reader that says nothing
                    // when the focus moves is either not being told or not listening, and only
                    // this line tells the two apart.
                    System.out.println("    posted since last step: "
                            + linesAfter(traced, "posted "));
                    traced.clear();
                }
                if (measured != null) {
                    // One line per frame since the last tick: the frame's event count, what its
                    // publish cost and what the drain at its end cost. A tick faster than the
                    // software-GL frame puts two ticks into one frame, and that shows here as one
                    // sample with both ticks' events in it -- which is the honest per-frame figure.
                    for (TimingBridge.Sample sample : measured.samplesSince(lastSample[0])) {
                        System.out.println("    sample #" + sample.number()
                                + " events=" + sample.events()
                                + " publishes=" + sample.publishes()
                                + " publish=" + sample.publishNanos() / 1_000 + "us"
                                + " drain=" + sample.drainNanos() / 1_000 + "us"
                                + " posted=" + sample.posted()
                                + (sample.reentrant() ? " (reentrant)" : ""));
                    }
                    lastSample[0] = measured.samples().size();
                    if (ax != null) {
                        System.out.println("    collapses so far: " + ax.collapses());
                    }
                }
                System.out.flush();
                if (probe.steps() < steps) {
                    Ui.postDelayed(tick[0], tickMs);
                } else {
                    window.close();
                }
            };
            Ui.postDelayed(tick[0], 4_000);


            backend.runEventLoop();
            if (measured != null) {
                System.out.print(measured.summary());
            }
            System.out.println("DONE");
        }
    }

    /** The trace lines of one kind, with the kind's prefix taken off. */
    private static List<String> linesAfter(List<String> traced, String prefix) {
        List<String> kept = new ArrayList<>();
        for (String line : traced) {
            if (line.startsWith(prefix)) kept.add(line.substring(prefix.length()));
        }
        return kept;
    }

    /**
     * The widget a run puts the keyboard on before its cycle starts, or none.
     *
     * @param probe the scene the cycle runs in
     * @param which {@code null} or empty for none, {@code list} for the list the scroll cycle
     *              pages, or an index into the focus cycle's order
     * @return the widget, or {@code null} when nothing was asked for
     * @throws IllegalArgumentException for anything else, because a run that silently focused
     *         nothing would be read as a reader that says nothing about a value
     */
    static Widget<?> firstFocus(ProbeScene probe, String which) {
        if (which == null || which.isEmpty()) return null;
        if (which.equals("list")) return probe.rows();
        try {
            return probe.focusable.get(Integer.parseInt(which));
        } catch (NumberFormatException | IndexOutOfBoundsException wrong) {
            throw new IllegalArgumentException("-Dprobe.focus=" + which + ": 'list' or 0.."
                    + (probe.focusable.size() - 1), wrong);
        }
    }

    /**
     * The published tree, printed as a client would walk it. What it is for is the comparison a
     * person can make with their eyes: every control in the window should be a line here, with the
     * name it is painted with.
     */
    private static void dump(AccessibleTree tree) {
        System.out.println("tree: " + tree.nodeCount() + " nodes, generation " + tree.generation()
                + ", focused id " + tree.focused());
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            AxRoles.Mapping mapping = AxRoles.of(node.role());
            System.out.println("  #" + node.id() + " " + node.role()
                    + " -> " + mapping.roleSymbol()
                    + (mapping.subroleSymbol() == null ? "" : "/" + mapping.subroleSymbol())
                    + " '" + node.name() + "'"
                    + " as " + AxNames.attributeFor(node.nameFrom())
                    + " bounds " + node.bounds()
                    + " states " + node.states());
        }
    }
}

package limn.backend.lwjgl.a11y.linux;

import limn.backend.AccessibilityBridge;
import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.backend.lwjgl.a11y.ProbeScene;
import limn.concurrent.Ui;
import limn.scene.Scene;

/**
 * The demo this bridge is meant to be read from: real widgets, really painted, in a window the
 * backend opened.
 *
 * <p>Not a test: it does not assert, it stays alive. Point Orca at it, or
 * {@code scripts/a11y/linux/walk-the-probe.py} to read what it publishes and
 * {@code scripts/a11y/linux/press-the-probe.py} to press its Save button from outside and see
 * "Save pressed" appear here.
 *
 * <p><b>It used to paint nothing</b>, on the reasoning that an accessible tree needs no pixels —
 * which is true and was still the wrong shape. It is the second of the three shapes the Windows run
 * went through and rejected: a window that reads correctly and shows nothing is impossible to look
 * at and believe, and a person and a reader disagreeing is exactly what a live run is for. It could
 * not have the first shape while the bridge was an artifact of its own with no backend to reach;
 * now that they live together, it opens a real window like its two siblings and shows the same
 * widgets.
 *
 * <pre>
 * java -jar limn-a11y-linux-probe.jar
 * </pre>
 */
public final class LiveProbe {

    private LiveProbe() {
    }

    /**
     * @param args unused
     */
    public static void main(String[] args) {
        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(
                    new WindowConfig("Limn accessibility probe", 480, 560, true, true));

            // Opened by the backend, not by this probe: the bridges ship with it now, so what runs
            // here is the path an application takes rather than a shortcut only a probe knows.
            AccessibilityBridge bridge = window.accessibility();
            System.out.println("bridge: " + bridge.getClass().getSimpleName()
                    + "  listening=" + bridge.isListening()
                    + (bridge == AccessibilityBridge.NONE
                       ? "   (accessibility is off on this desktop, or the a11y bus refused us)" : ""));

            ProbeScene probe = new ProbeScene();
            Scene scene = new Scene(probe.root());
            scene.bind(window);
            window.setFrameCallback((renderer, frame) ->
                    scene.renderFrame(renderer.canvas(), frame.rePresent(), frame.gpuFrameMs()));

            int tickMs = Integer.getInteger("probe.tickMs", 4_000);
            Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                // The foreground first, every time: a reader announces the window in front, and the
                // terminal that launched this keeps taking it back.
                window.focus();
                probe.tick(scene);
                if (bridge instanceof AtspiBridge atspi) {
                    // Whether it is on the bus, and whether it has even tried. The two are
                    // different failures: never trying means no publish carried a tree, and
                    // trying and failing means the bus refused.
                    System.out.println("    onTheBus=" + atspi.isOnTheBus()
                            + " joinAttempts=" + atspi.joinAttempts()
                            + " listening=" + atspi.isListening());
                }
                if (probe.steps() < 40) {
                    Ui.postDelayed(tick[0], tickMs);
                } else {
                    window.close();
                }
            };
            Ui.postDelayed(tick[0], 4_000);

            backend.runEventLoop();
            System.out.println("DONE");
        }
    }
}

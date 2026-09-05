package limn.a11y.linux;

import limn.backend.AccessibilityBridge;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Canvas;
import limn.scene.Scene;
import limn.scene.layout.Column;

import java.util.concurrent.Executors;

/**
 * Publishes a real Limn scene into this machine's accessibility tree, and stays there.
 *
 * <p>Not a test: a program, run on a Linux guest with a desktop session, so that a platform client
 * can be pointed at it. Everything below the bridge is the toolkit's own — a scene, real widgets,
 * the walk, the snapshot — and the only thing standing in for a window is the fact that this one
 * paints nothing, because an accessible tree needs no pixels.
 *
 * <pre>
 * java -cp &lt;classes&gt; limn.a11y.linux.LiveProbe [seconds]
 * </pre>
 */
public final class LiveProbe {

    private LiveProbe() {
    }

    /**
     * @param args optionally how many seconds to stay on the bus; sixty by default
     * @throws Exception if the scene cannot be built
     */
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        UiRuntime runtime = new UiRuntime(System::nanoTime, () -> { }, Executors.newFixedThreadPool(1));
        runtime.bindToCurrentThread();
        Ui.install(runtime);

        AccessibilityBridge bridge = AtspiBridge.openIfEnabled("Limn probe");
        System.out.println("bridge      : " + bridge.getClass().getSimpleName()
                + "  listening=" + bridge.isListening());
        if (bridge == AccessibilityBridge.NONE) {
            System.out.println("accessibility is off on this session, or the a11y bus refused us");
            return;
        }

        Column root = new Column();
        root.add(new Label("Limn accessibility probe"));
        Button save = new Button("Save");
        save.onAction(() -> System.out.println("*** Save pressed, through the toolkit's own path"));
        root.add(save);
        Checkbox wrap = new Checkbox(Checkbox.Variant.BOX, "Wrap lines");
        wrap.onChange(on -> System.out.println("*** Wrap toggled to " + on));
        root.add(wrap);

        ProbeWindow window = new ProbeWindow();
        window.accessibility = bridge;
        Scene scene = new Scene(root);
        scene.bind(window);

        Canvas nothing = new NoCanvas();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            scene.renderFrame(nothing);
            runtime.drain();
            Thread.sleep(16);
        }
        System.out.println("done; listening=" + bridge.isListening());
    }
}

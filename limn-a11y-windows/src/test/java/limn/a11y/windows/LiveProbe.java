package limn.a11y.windows;

import limn.backend.AccessibilityBridge;
import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.concurrent.Ui;
import limn.scene.Scene;
import limn.scene.layout.Column;

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
 * <pre>
 * java -jar limn-a11y-windows-probe.jar
 * </pre>
 */
public final class LiveProbe {

    private LiveProbe() {
    }

    /**
     * @param args unused
     */
    public static void main(String[] args) {
        System.out.println("uiautomationcore available: " + Uia.isAvailable());
        System.out.println("user32 available:           " + UiaWindow.isAvailable());
        System.out.println("oleaut32 available:         " + UiaStrings.isAvailable());

        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(
                    new WindowConfig("Limn accessibility probe", 480, 320, true, true));

            // The two seams an application needs and this bridge was the first to ask for: the
            // window's own handle to attach a provider to, and somewhere to put the bridge that
            // comes back.
            long hwnd = window.nativeHandle();
            System.out.println("hwnd: " + Long.toHexString(hwnd));
            AccessibilityBridge bridge = UiaBridge.openIfEnabled(hwnd);
            window.setAccessibility(bridge);
            System.out.println("bridge: " + bridge.getClass().getSimpleName()
                    + "  listening=" + bridge.isListening());

            Column root = new Column();
            root.add(new Label("Limn accessibility probe"));
            Button save = new Button("Save");
            save.onAction(() -> System.out.println("*** Save pressed, through the toolkit's path"));
            root.add(save);
            Checkbox wrap = new Checkbox(Checkbox.Variant.BOX, "Wrap lines");
            wrap.onChange(on -> System.out.println("*** Wrap toggled to " + on));
            root.add(wrap);

            Scene scene = new Scene(root);
            scene.bind(window);
            window.setFrameCallback((renderer, frame) ->
                    scene.renderFrame(renderer.canvas(), frame.rePresent(), frame.gpuFrameMs()));

            // Something moves every few seconds so a listening reader has events to hear, and each
            // one is raised by the scene from a real focus move or a real toggle rather than
            // published by hand. Posted rather than looped, because the event loop below owns this
            // thread and a widget may only be touched on it.
            int[] step = {0};
            Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                // The foreground first, every time. A screen reader announces the focused element
                // of the window in front; a window nobody brought forward is one it never reaches,
                // and on this guest the terminal that launched the probe keeps taking it back.
                window.focus();
                switch (step[0]++ % 3) {
                    case 0 -> scene.requestFocus(save);
                    case 1 -> scene.requestFocus(wrap);
                    default -> wrap.setChecked(!wrap.isChecked());
                }
                System.out.println("--- step " + step[0] + " ---");
                System.out.flush();
                if (step[0] < 24) {
                    Ui.postDelayed(tick[0], 6_000);
                } else {
                    window.close();
                }
            };
            Ui.postDelayed(tick[0], 8_000);

            backend.runEventLoop();
            System.out.println("DONE");
        }
    }
}

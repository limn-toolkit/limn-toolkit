package limn.a11y.windows;

import limn.backend.AccessibilityBridge;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Canvas;
import limn.scene.Scene;
import limn.scene.layout.Column;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.util.concurrent.Executors;

/**
 * Real widgets, on a real window, read by a real screen reader.
 *
 * <p>Not a test: it does not assert, it stays alive. It exists because the one thing this module's
 * own cases cannot check is the conversation with UI Automation — whether {@code WM_GETOBJECT}
 * reaches this bridge, whether the slot order read off the guest is the order UI Automation calls,
 * and whether what is written into a {@code VARIANT} arrives as what it meant.
 *
 * <p><b>The tree comes from a scene and not from a builder.</b> An earlier version of this probe
 * published a hand-made tree of three nodes, which tested the bridge and nothing under it — and the
 * two defects the Linux run found with a live reader were both <em>under</em> it, in what a scene
 * publishes rather than in what a bridge does with it. So this is a {@link Button} and a
 * {@link Checkbox} in a {@link Scene}, walked by the toolkit's own pass, and the events a reader
 * hears are the ones a real focus move and a real toggle raise.
 *
 * <p><b>The window is real and paints nothing.</b> UI Automation needs an HWND to attach a provider
 * to, so GLFW opens one with no graphics context; the scene binds to a {@link ProbeWindow} instead,
 * which is a {@code NativeWindow} that answers where that HWND sits and hands over this bridge. A
 * probe that also rendered would be testing the backend, which is not what is in question here.
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
     * @throws InterruptedException if the wait is cut short
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("uiautomationcore available: " + Uia.isAvailable());
        System.out.println("user32 available:           " + UiaWindow.isAvailable());
        System.out.println("oleaut32 available:         " + UiaStrings.isAvailable());

        if (!GLFW.glfwInit()) {
            System.out.println("FAILED: glfwInit");
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        long glfw = GLFW.glfwCreateWindow(480, 320, "Limn accessibility probe", 0, 0);
        if (glfw == 0) {
            System.out.println("FAILED: glfwCreateWindow");
            return;
        }
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(glfw);
        System.out.println("hwnd: " + Long.toHexString(hwnd));

        AccessibilityBridge bridge = UiaBridge.openIfEnabled(hwnd);
        System.out.println("bridge: " + bridge.getClass().getSimpleName()
                + "  listening=" + bridge.isListening());
        if (bridge == AccessibilityBridge.NONE) {
            System.out.println("FAILED: no bridge for this window");
            return;
        }

        UiRuntime runtime = new UiRuntime(System::nanoTime, () -> { },
                Executors.newFixedThreadPool(1));
        runtime.bindToCurrentThread();
        Ui.install(runtime);

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
        int[] x = new int[1];
        int[] y = new int[1];
        GLFW.glfwGetWindowPos(glfw, x, y);
        window.screenX = x[0];
        window.screenY = y[0];
        System.out.println("window at " + x[0] + "," + y[0]);

        Scene scene = new Scene(root);
        scene.bind(window);
        // The window has the user's focus, which a real backend reports and a stub must say for
        // itself: without it the tree is published by a window no client considers active. That is
        // one of the two defects the Linux run found with a live reader.
        scene.windowFocusChanged(true);
        scene.inputBatchEnded();

        GLFW.glfwShowWindow(glfw);
        Canvas nothing = new NoCanvas();
        long end = System.currentTimeMillis() + 180_000;
        long nextStep = System.currentTimeMillis() + 12_000;
        long nextFocus = 0;
        int step = 0;
        while (System.currentTimeMillis() < end && !GLFW.glfwWindowShouldClose(glfw)) {
            // Asking for the foreground, because a reader announces the window in front and one
            // nobody brought forward is one it never reaches.
            if (System.currentTimeMillis() > nextFocus) {
                GLFW.glfwFocusWindow(glfw);
                nextFocus = System.currentTimeMillis() + 8_000;
            }
            // And something moves every few seconds, so a listening reader has events to hear --
            // raised by the scene from a real focus move and a real toggle, not published by hand.
            if (System.currentTimeMillis() > nextStep) {
                nextStep = System.currentTimeMillis() + 9_000;
                switch (step++ % 3) {
                    case 0 -> scene.requestFocus(save);
                    case 1 -> scene.requestFocus(wrap);
                    default -> wrap.setChecked(!wrap.isChecked());
                }
                System.out.println("--- step " + step + " ---");
                System.out.flush();
            }
            scene.renderFrame(nothing);
            runtime.drain();
            GLFW.glfwPollEvents();
            Thread.sleep(16);
        }
        System.out.println("DONE; listening=" + bridge.isListening());
    }
}

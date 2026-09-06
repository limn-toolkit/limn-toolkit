package limn.a11y.macos;

import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
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
        System.out.println("If this window paints slowly, that is the backend's macOS software GL "
                + "fallback and not a stall.");

        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(
                    new WindowConfig("Limn accessibility probe", 480, 320, true, true));

            // The two seams an application needs, which the Windows bridge was the first to ask for
            // and this one needs just as much: the window's own handle, and somewhere to put the
            // bridge that comes back. Without both, an application could add this artifact to its
            // classpath and never install it.
            long nsWindow = window.nativeHandle();
            System.out.println("NSWindow: 0x" + Long.toHexString(nsWindow)
                    + (nsWindow == 0 ? "   !!! zero: the backend has not been taught this platform" : ""));
            AxBridge bridge = new AxBridge();
            window.setAccessibility(bridge);
            System.out.println("bridge: " + bridge.getClass().getSimpleName()
                    + "  listening=" + bridge.isListening()
                    + "  needsPrimingPublish=" + bridge.needsPrimingPublish());

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

            // Something moves every few seconds, so that a reader has events to hear and a person
            // has something to watch; each is a real focus move or a real toggle rather than a tree
            // published by hand. Posted rather than looped, because the event loop below owns this
            // thread and a widget may only be touched on it.
            int[] step = {0};
            Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                // The foreground first, every time. A reader announces the focused element of the
                // window in front, and on these guests the terminal that launched the probe keeps
                // taking it back -- which looks exactly like a broken bridge.
                window.focus();
                switch (step[0]++ % 3) {
                    case 0 -> scene.requestFocus(save);
                    case 1 -> scene.requestFocus(wrap);
                    default -> wrap.setChecked(!wrap.isChecked());
                }
                System.out.println("--- step " + step[0] + " ---");
                // republishNow() is the platform's path, and this probe is standing in for the
                // platform: it is the user-interface thread, inside the pump, which is exactly the
                // contract that call names. It is here because the listening gate is shut until
                // the elements land, so the priming publish would otherwise be the only tree there
                // ever is.
                dump(bridge.host() == null ? AccessibleTree.EMPTY : bridge.host().republishNow());
                System.out.flush();
                if (step[0] < 24) {
                    Ui.postDelayed(tick[0], 6_000);
                } else {
                    window.close();
                }
            };
            Ui.postDelayed(tick[0], 4_000);

            backend.runEventLoop();
            System.out.println("DONE");
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

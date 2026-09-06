package limn.a11y.macos;

import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.components.Button;
import limn.components.ButtonGroup;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.PasswordField;
import limn.components.ProgressBar;
import limn.components.RadioButton;
import limn.components.SearchField;
import limn.components.Separator;
import limn.components.Slider;
import limn.components.TextField;
import limn.scene.Widget;
import limn.concurrent.Ui;
import limn.scene.Scene;
import limn.scene.layout.Column;

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
                    new WindowConfig("Limn accessibility probe", 480, 560, true, true));

            // The two seams an application needs, which the Windows bridge was the first to ask for
            // and this one needs just as much: the window's own handle, and somewhere to put the
            // bridge that comes back. Without both, an application could add this artifact to its
            // classpath and never install it.
            long nsWindow = window.nativeHandle();
            System.out.println("NSWindow: 0x" + Long.toHexString(nsWindow)
                    + (nsWindow == 0 ? "   !!! zero: the backend has not been taught this platform" : ""));
            AccessibilityBridge bridge = AxBridge.openIfEnabled(nsWindow);
            // Kept typed as well, because the probe prints what the bridge is holding and the two
            // questions a run must separate are "does the scene describe what it painted" and
            // "does the platform reach what the bridge vends".
            AxBridge ax = bridge instanceof AxBridge opened ? opened : null;
            window.setAccessibility(bridge);
            System.out.println("bridge: " + bridge.getClass().getSimpleName()
                    + "  listening=" + bridge.isListening()
                    + "  needsPrimingPublish=" + bridge.needsPrimingPublish()
                    + (bridge == AccessibilityBridge.NONE
                       ? "   !!! NONE: AppKit was not reachable, so nothing will be read" : ""));

            // Enough widgets to exercise the role table rather than three rows of it, and chosen
            // for what each one can go wrong at. Half of them carry a value and half do not, which
            // matters more than it looks: a widget with a value announces itself when the value
            // moves, so it can look correctly focused while focus tracking is entirely broken. The
            // button is what showed that, because a button has nothing else to say.
            Column root = new Column();
            root.add(new Label("Limn accessibility probe"));

            Button save = new Button("Save");
            save.onAction(() -> System.out.println("*** Save pressed, through the toolkit's path"));
            root.add(save);

            Checkbox wrap = new Checkbox(Checkbox.Variant.BOX, "Wrap lines");
            wrap.onChange(on -> System.out.println("*** Wrap toggled to " + on));
            root.add(wrap);

            // A switch and a check box are different roles on purpose (§1.12), and this is the
            // platform where the difference is cheapest: AXCheckBox with the switch subrole.
            Checkbox dark = new Checkbox(Checkbox.Variant.SWITCH, "Dark mode");
            root.add(dark);

            TextField name = new TextField();
            name.setPlaceholder("Nome do arquivo");
            name.setText("relatório.txt");
            root.add(name);

            // A password field is a text field with a subrole, and a reader must not read it out.
            PasswordField secret = new PasswordField();
            secret.setText("hunter2");
            root.add(secret);

            SearchField search = new SearchField();
            search.setPlaceholder("Buscar");
            root.add(search);

            // The three value-carrying roles, which share one attribute on this platform and are
            // three facets for exactly that reason.
            Slider volume = new Slider(0, 100);
            volume.setValue(40);
            root.add(volume);

            ProgressBar progress = new ProgressBar();
            progress.setProgress(0.6f);
            root.add(progress);

            ComboBox format = new ComboBox(List.of("PDF", "Markdown", "HTML"));
            format.setSelectedIndex(1);
            root.add(format);

            // A radio group has no container widget, so the grouping is a relation plus position
            // and size of set (§13.13) -- the one place this design deliberately publishes less
            // than the platform would prefer.
            RadioButton daily = new RadioButton("Diário");
            RadioButton weekly = new RadioButton("Semanal");
            new ButtonGroup().add(daily).add(weekly).setSelectedIndex(0);
            root.add(daily);
            root.add(weekly);

            root.add(Separator.horizontal());

            Scene scene = new Scene(root);
            scene.bind(window);
            window.setFrameCallback((renderer, frame) ->
                    scene.renderFrame(renderer.canvas(), frame.rePresent(), frame.gpuFrameMs()));

            // Something moves every few seconds, so that a reader has events to hear and a person
            // has something to watch; each is a real focus move or a real toggle rather than a tree
            // published by hand. Posted rather than looped, because the event loop below owns this
            // thread and a widget may only be touched on it.
            List<Widget> focusable = List.of(save, wrap, dark, name, secret, search,
                    volume, format, daily, weekly);
            // A reader needs time to finish a phrase before the next one starts, and a run needs to
            // reach every widget: those pull opposite ways, so the interval is a knob.
            int tickMs = Integer.getInteger("probe.tickMs", 6_000);
            int[] step = {0};
            int[] lastPosted = {0};
            int[] lastEmitted = {0};
            Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                // The foreground first, every time. A reader announces the focused element of the
                // window in front, and on these guests the terminal that launched the probe keeps
                // taking it back -- which looks exactly like a broken bridge.
                window.focus();
                // The focus walks every focusable widget in turn, and touches nothing else.
                //
                // Nothing else is the point, and it is what the first VoiceOver run of this bridge
                // got wrong. A cycle that also toggled the check box could not tell "the reader
                // does not follow our focus" from "the reader is announcing the value change that
                // travelled with it" -- and the answer was the first, invisible for as long as
                // every step had a value change in it. probe.cycle=value drives the other half.
                int index = step[0]++ % focusable.size();
                if ("value".equals(System.getProperty("probe.cycle"))) {
                    wrap.setChecked(!wrap.isChecked());
                    volume.setValue((volume.value() + 10) % 100);
                } else {
                    scene.requestFocus(focusable.get(index));
                }
                System.out.println("--- step " + step[0] + " ---");
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
                    System.out.println("    focus answers: " + ax.focusedAnswers());
                    java.util.List<String> ev = ax.emittedEvents();
                    System.out.println("    emitted since last step: "
                            + ev.subList(Math.min(lastEmitted[0], ev.size()), ev.size()));
                    lastEmitted[0] = ev.size();
                    // What was actually posted since the last step. A reader that says nothing
                    // when the focus moves is either not being told or not listening, and only
                    // this line tells the two apart.
                    java.util.List<String> all = ax.postedNotifications();
                    System.out.println("    posted since last step: "
                            + all.subList(Math.min(lastPosted[0], all.size()), all.size()));
                    lastPosted[0] = all.size();
                }
                System.out.flush();
                if (step[0] < 40) {
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

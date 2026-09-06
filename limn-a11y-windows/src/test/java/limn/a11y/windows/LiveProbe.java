package limn.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.i18n.I18nString;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.util.Locale;

/**
 * A real window on a real Windows machine, with this bridge behind it, so that a real client can
 * ask it for a tree.
 *
 * <p>Not a test: it does not assert, it stays alive. It exists because the one thing the module's
 * own cases cannot check is the conversation with UI Automation — whether the slot order read off
 * this guest is the order UI Automation actually calls, whether {@code WM_GETOBJECT} reaches this
 * bridge, whether a name written into a {@code VARIANT} arrives as a name. A client walking this
 * window answers all three at once, and nothing else does.
 *
 * <p>The tree is built by hand rather than by a scene, because what is under test is the bridge and
 * not the toolkit: three nodes, a name apiece, one of them pressable.
 *
 * <pre>
 * java -cp ... limn.a11y.windows.LiveProbe
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
        UiaWindow.trace = line -> {
            System.out.println("[wnd] " + line);
            System.out.flush();
        };
        System.out.println("uiautomationcore available: " + Uia.isAvailable());
        System.out.println("user32 available:           " + UiaWindow.isAvailable());
        System.out.println("oleaut32 available:         " + UiaStrings.isAvailable());

        if (!GLFW.glfwInit()) {
            System.out.println("FAILED: glfwInit");
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        long window = GLFW.glfwCreateWindow(480, 320, "Limn accessibility probe", 0, 0);
        if (window == 0) {
            System.out.println("FAILED: glfwCreateWindow");
            return;
        }
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        System.out.println("hwnd: " + Long.toHexString(hwnd));

        AccessibilityBridge bridge = UiaBridge.openIfEnabled(hwnd);
        System.out.println("bridge: " + bridge.getClass().getSimpleName());
        if (!(bridge instanceof UiaBridge live)) {
            System.out.println("FAILED: no bridge for this window");
            return;
        }
        live.publish(threeNodes(), false);
        System.out.println("published " + live.tree().nodeCount() + " nodes; listening: "
                + live.isListening());
        System.out.println("READY");
        System.out.flush();

        GLFW.glfwShowWindow(window);
        // Asking for the foreground repeatedly, because a screen reader announces what it is
        // given: it speaks the focused element of the foreground window, and a window nobody
        // brought forward is a window it never reaches.
        long until = System.currentTimeMillis() + 180_000;
        long nextFocus = 0;
        long moveTheKeyboard = System.currentTimeMillis() + 25_000;
        long uncheckIt = System.currentTimeMillis() + 45_000;
        boolean moved = false;
        boolean unchecked = false;
        while (System.currentTimeMillis() < until && !GLFW.glfwWindowShouldClose(window)) {
            if (System.currentTimeMillis() > nextFocus) {
                GLFW.glfwFocusWindow(window);
                nextFocus = System.currentTimeMillis() + 8_000;
            }
            // Once, after the reader has settled on the button: move the keyboard to the check box
            // and say so. A reader learns of a focus move from the event and not by asking, so
            // this exercises the one path a client cannot prompt -- the path where the Linux run
            // found its two defects.
            if (!moved && System.currentTimeMillis() > moveTheKeyboard) {
                moved = true;
                live.publish(threeNodes(1002), false);
                live.emit(limn.accessibility.AccessibleEvent.of(
                        limn.accessibility.AccessibleEvent.Type.FOCUS_CHANGED, 1002));
                System.out.println("moved the keyboard to the check box and raised FOCUS_CHANGED");
                System.out.flush();
            }
            // And once more, later: change what the check box says about itself and raise the
            // property change. A reader announces a state it was told moved; one that has to ask
            // announces nothing, which is the difference this last path makes.
            if (moved && !unchecked && System.currentTimeMillis() > uncheckIt) {
                unchecked = true;
                live.publish(threeNodes(1002, limn.accessibility.ToggleFacet.State.OFF), false);
                live.emit(limn.accessibility.AccessibleEvent.state(
                        1002, limn.accessibility.Accessible.State.CHECKED, false));
                System.out.println("unchecked the box and raised STATE_CHANGED");
                System.out.flush();
            }
            GLFW.glfwWaitEventsTimeout(0.2);
        }
        System.out.println("DONE");
    }

    /** A window, a button and a check box, which is enough for a client to walk and read. */
    private static AccessibleTree threeNodes() {
        return threeNodes(1001);
    }

    /**
     * @param focusedId which node holds the keyboard, so that a run can move it and hear the
     *                  difference
     * @return the tree
     */
    private static AccessibleTree threeNodes(long focusedId) {
        return threeNodes(focusedId, limn.accessibility.ToggleFacet.State.ON);
    }

    /**
     * @param focusedId which node holds the keyboard
     * @param checked   what the check box says about itself
     * @return the tree
     */
    private static AccessibleTree threeNodes(long focusedId,
                                             limn.accessibility.ToggleFacet.State checked) {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("Limn accessibility probe"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);

        a.begin(1001, 0, Locale.ENGLISH, 20, 40, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.description(I18nString.literal("Writes the file"));
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, focusedId == 1001);
        a.end();

        a.begin(1002, 0, Locale.ENGLISH, 20, 100, 160, 24);
        a.role(Accessible.Role.CHECK_BOX);
        a.name(I18nString.literal("Wrap lines"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.TOGGLE);
        a.toggle(checked);
        a.inherited(true, true, true, true, focusedId == 1002);
        a.end();

        a.end();
        return a.publish(focusedId, 0, 0, 1f, true);
    }
}

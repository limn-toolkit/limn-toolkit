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
        long until = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < until && !GLFW.glfwWindowShouldClose(window)) {
            GLFW.glfwWaitEventsTimeout(0.2);
        }
        System.out.println("DONE");
    }

    /** A window, a button and a check box, which is enough for a client to walk and read. */
    private static AccessibleTree threeNodes() {
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
        a.inherited(true, true, true, true, true);
        a.end();

        a.begin(1002, 0, Locale.ENGLISH, 20, 100, 160, 24);
        a.role(Accessible.Role.CHECK_BOX);
        a.name(I18nString.literal("Wrap lines"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.TOGGLE);
        a.toggle(limn.accessibility.ToggleFacet.State.ON);
        a.inherited(true, true, true, true, false);
        a.end();

        a.end();
        return a.publish(1001, 0, 0, 1f, true);
    }
}

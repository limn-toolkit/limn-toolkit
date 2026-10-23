package limn.backend.lwjgl;

import limn.backend.lwjgl.internal.ObjC;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Lets a transparent window drawn by ANGLE on Metal show what is behind it.
 *
 * <p>The windowing library makes a transparent window's NSWindow non-opaque and hands ANGLE the
 * view's layer, and ANGLE draws into a Metal layer of its own beneath it. That layer is opaque, so
 * every pixel the window cleared to transparent came out black on the screen: read back from the
 * screen of the GPU-less macOS guest on 2026-10-22, a transparent gadget over the desktop showed
 * the cube on black, where desktop OpenGL shows it on the desktop. Marking the view's layer and
 * every layer under it non-opaque, once the surface exists, is what lets the window server
 * composite the alpha the frame carries.
 */
final class MacAngleLayers {

    private static final System.Logger LOG = System.getLogger(MacAngleLayers.class.getName());

    private MacAngleLayers() {
    }

    /** Marks {@code window}'s layers non-opaque; call it after the window's EGL surface exists. */
    static void makeTransparent(long window) {
        if (!ObjC.isAvailable()) {
            return;
        }
        long view = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaView(window);
        long layer = view == NULL ? NULL : ObjC.msg(view, "layer");
        if (layer == NULL) {
            LOG.log(System.Logger.Level.WARNING, "ANGLE window without a layer; transparency left as it is");
            return;
        }
        clear(layer, 0);
    }

    /**
     * Gives {@code window}'s layer the scale of the screen the window is on; call it after the
     * window's EGL surface exists.
     *
     * <p>ANGLE sizes its drawable from the scale of the layer it was handed, and the windowing
     * library sets that scale only when the window's backing changes. A window born on a 1x screen
     * kept the layer's first scale of 2: its drawable was twice the framebuffer the library
     * reported, so every frame filled the lower-left quarter of the window, and moving between 1x
     * screens changed nothing, since the backing never changed.
     */
    static void matchBackingScale(long window) {
        if (!ObjC.isAvailable()) {
            return;
        }
        long view = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaView(window);
        long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(window);
        long layer = view == NULL ? NULL : ObjC.msg(view, "layer");
        if (layer == NULL || nsWindow == NULL) {
            return;
        }
        // Through key-value coding: the scale is a CGFloat, and an NSNumber crosses as an object.
        long scale = ObjC.msg(nsWindow, "valueForKey:", nsString("backingScaleFactor"));
        ObjC.msgVoid(layer, "setValue:forKey:", scale, nsString("contentsScale"));
    }

    private static long nsString(String text) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return ObjC.msg(ObjC.cls("NSString"), "stringWithUTF8String:", MemoryUtil.memAddress(stack.UTF8(text)));
        }
    }

    private static void clear(long layer, int depth) {
        ObjC.msgVoid(layer, "setOpaque:", 0L);
        LOG.log(System.Logger.Level.DEBUG, () -> "ANGLE layer " + "  ".repeat(depth) + className(layer)
                + " made non-opaque");
        long sublayers = ObjC.msg(layer, "sublayers");
        long count = sublayers == NULL ? 0 : ObjC.msg(sublayers, "count");
        for (long i = 0; i < count; i++) {
            clear(ObjC.msg(sublayers, "objectAtIndex:", i), depth + 1);
        }
    }

    private static String className(long object) {
        long name = ObjC.msg(object, "className");
        long utf8 = name == NULL ? NULL : ObjC.msg(name, "UTF8String");
        return utf8 == NULL ? "?" : MemoryUtil.memUTF8(utf8);
    }
}

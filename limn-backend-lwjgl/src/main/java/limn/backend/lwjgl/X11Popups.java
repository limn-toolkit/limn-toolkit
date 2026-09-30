package limn.backend.lwjgl;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.linux.X11;
import org.lwjgl.system.linux.XSetWindowAttributes;

import java.lang.System.Logger.Level;

/**
 * Makes a popup window on X11 one the window manager leaves alone, as GTK's and Qt's menus and
 * drop-down lists are: {@code override_redirect}, set before the window is first mapped. For a
 * popup whose owner keeps the keyboard (a combo's list, a calendar, a menu), which a managed window
 * could not be on GNOME: {@code GLFW_FOCUS_ON_SHOW} only stops GLFW asking for the focus, mutter
 * gave it anyway, and Orca read the owner's frame again at every closing; with
 * {@code _NET_WM_USER_TIME} 0 mutter kept the focus on the owner but GNOME Shell announced the popup
 * as a window that "is ready" (both measured on the Ubuntu guest under Orca, 2026-09-30). An
 * override-redirect window gets no focus, no notification, no frame and no placement of the window
 * manager's, and is shown where it is put.
 *
 * <p>LWJGL binds the attribute structure and not {@code XChangeWindowAttributes}, which is called
 * by address from the Xlib LWJGL already loaded. A failure is logged and leaves the window as GLFW
 * made it.
 */
final class X11Popups {

    private static final System.Logger LOG = System.getLogger(X11Popups.class.getName());

    private static boolean resolved;
    private static long changeAttributes;

    private X11Popups() {
    }

    /**
     * Sets {@code override_redirect} on a window not yet mapped. UI thread.
     *
     * @param display the X display ({@code glfwGetX11Display})
     * @param window  the X window ({@code glfwGetX11Window})
     */
    static void unmanaged(long display, long window) {
        if (display == 0 || window == 0 || !resolve()) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XSetWindowAttributes attributes = XSetWindowAttributes.calloc(stack).override_redirect(true);
            // XChangeWindowAttributes(Display*, Window, unsigned long valuemask, XSetWindowAttributes*):
            // a Window and a mask are C longs, which the pointer-width trampoline carries as they are.
            JNI.invokePPPPI(display, window, X11.CWOverrideRedirect, attributes.address(), changeAttributes);
        } catch (RuntimeException | LinkageError error) {
            LOG.log(Level.WARNING, "override_redirect could not be set; the window manager may focus the popup", error);
        }
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            try {
                changeAttributes = X11.getLibrary().getFunctionAddress("XChangeWindowAttributes");
            } catch (RuntimeException | LinkageError error) {
                LOG.log(Level.WARNING, "Xlib's XChangeWindowAttributes is unreachable", error);
            }
        }
        return changeAttributes != 0;
    }
}

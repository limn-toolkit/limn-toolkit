package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11;

/**
 * Which windowing platform a Linux session gets, which is a decision and not a detail.
 *
 * <p>It was "X11 whenever DISPLAY is set", so that popups could have windows of their own at
 * absolute positions. XWayland sets DISPLAY on every Wayland desktop, so that sent all of them
 * through XWayland — where, on Fedora 44 KDE, the application never draws at all: GLFW's X11 backend
 * waits for a VisibilityNotify that rootless XWayland does not send, and spins at 100% of a core.
 */
class PlatformSelectionTest {

    private static int chosen(String waylandDisplay, String display) {
        return LwjglBackend.platformForSession(waylandDisplay, display, true, true);
    }

    @Test
    void aWaylandSessionIsUsedAsAWaylandSession() {
        // Both variables are set on every Wayland desktop, because XWayland is running: this is the
        // case the old rule got wrong, and it is the common one rather than the corner.
        assertEquals(GLFW_PLATFORM_WAYLAND, chosen("wayland-0", ":0"));
        assertEquals(GLFW_PLATFORM_WAYLAND, chosen("wayland-0", null));
    }

    @Test
    void aGenuineX11SessionStillGetsX11() {
        // Where X11 is not a fallback but the right answer, and where popups do get their own
        // windows: no compositor is running, so nothing is being degraded.
        assertEquals(GLFW_PLATFORM_X11, chosen(null, ":0"));
        assertEquals(GLFW_PLATFORM_X11, chosen("", ":0"));
    }

    @Test
    void aHeadlessSessionAsksForNothingAndLetsGlfwDecide() {
        assertEquals(0, chosen(null, null));
        assertEquals(0, chosen("", ""));
    }

    @Test
    void aPlatformThisGlfwBuildLacksIsNeverAskedFor() {
        // A build without Wayland on a Wayland session falls to X11 rather than to a hint GLFW
        // would refuse; a build with neither asks for nothing.
        assertEquals(GLFW_PLATFORM_X11,
                LwjglBackend.platformForSession("wayland-0", ":0", false, true));
        assertEquals(GLFW_PLATFORM_WAYLAND,
                LwjglBackend.platformForSession("wayland-0", ":0", true, false));
        assertEquals(0, LwjglBackend.platformForSession("wayland-0", ":0", false, false));
    }
}

package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11;

/**
 * Which windowing platform a Linux session gets, which is a decision and not a detail.
 *
 * <p>It was "X11 whenever DISPLAY is set", so that popups could have windows of their own at
 * absolute positions. XWayland sets DISPLAY on every Wayland desktop, so that sent all of them
 * through XWayland — where, on Fedora 44 KDE, the application never draws at all: GLFW's X11 backend
 * waits for a VisibilityNotify that rootless XWayland does not send, and spins at 100% of a core.
 *
 * <p>The second half is what is said when no platform can be chosen at all, which is the same
 * problem one step further on and has the same shape: a string, no compiler to check it, and a
 * reader who has nothing else to go on.
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

    /**
     * The other half of the same problem: when the platform cannot be selected at all,
     * {@code glfwInit} refuses with "Failed to detect any supported platform", which names a GLFW
     * concept and not the machine's condition. A shell with no desktop session is the ordinary way
     * to reach it — a plain SSH login, a CI container — and the advice is what turns that line
     * into a fact somebody can act on.
     */
    @Test
    void aLinuxWithNoDisplayServerIsToldWhichVariablesWereEmpty() {
        String advice = LwjglBackend.startupAdvice("Linux", null, null);

        assertTrue(advice.contains("DISPLAY"), advice);
        assertTrue(advice.contains("WAYLAND_DISPLAY"), advice);
        assertTrue(advice.contains("xvfb-run"), advice);
    }

    /** A session exists, so its absence is not the reason and saying so would send a reader off. */
    @Test
    void aLinuxWithASessionIsToldNothingAboutIt() {
        assertEquals("", LwjglBackend.startupAdvice("Linux", ":0", null));
        assertEquals("", LwjglBackend.startupAdvice("Linux", null, "wayland-0"));
    }

    /**
     * The trap in advice that reads environment variables. Windows sets neither and needs neither:
     * it has one platform and picks it without looking. Telling a Windows user to export DISPLAY
     * would be confidently wrong on the one machine where the message is all they have.
     */
    @Test
    void windowsIsNeverToldToExportADisplay() {
        assertEquals("", LwjglBackend.startupAdvice("Windows 11", null, null));
    }

    /** macOS keeps the advice it always had: there, an unstarted GLFW is a thread, not a session. */
    @Test
    void macOsIsToldAboutTheFirstThread() {
        assertTrue(LwjglBackend.startupAdvice("Mac OS X", null, null)
                .contains("-XstartOnFirstThread"));
    }
}

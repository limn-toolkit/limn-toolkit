package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The windowing platform is chosen before {@code glfwInit}, from a property, and getting it wrong
 * is not a compile error: every value is a string and every wrong answer is a running application
 * placing windows somewhere nobody can see.
 *
 * <p>The second half is what is said when no platform can be chosen at all, which is the same
 * problem one step further on and has the same shape: a string, no compiler to check it, and a
 * reader who has nothing else to go on.
 */
class PlatformSelectionTest {

    @Test
    void eachNameSelectsItsPlatform() {
        assertEquals(GLFW.GLFW_PLATFORM_X11, LwjglBackend.platformFor("x11"));
        assertEquals(GLFW.GLFW_PLATFORM_WAYLAND, LwjglBackend.platformFor("wayland"));
        assertEquals(GLFW.GLFW_ANY_PLATFORM, LwjglBackend.platformFor("any"));
    }

    /**
     * The one that matters. A typo must fall through to the default (which prefers X11 where it
     * exists) rather than resolving to some platform by accident. Selecting Wayland here would
     * hand back the defect the default exists to avoid, on the machine of whoever mistyped it, and
     * it would present as popups in the wrong place rather than as an error.
     */
    @Test
    void anythingElseSelectsNothing() {
        String[] rejected = {
            "",          // not set at all, which is the ordinary case
            " ",         // set to whitespace
            "X11",       // right name, wrong case: the caller lower-cases before asking
            "wyland",    // misspelled "wayland": the case this test exists for
            "xwayland",  // a real thing, and not one of GLFW's platforms
            "win32", "cocoa",  // platforms, but never a choice on Linux
            "true", "0", "null",  // a boolean, a number and a word, in case anyone assumes
        };
        for (String value : rejected) {
            assertEquals(0, LwjglBackend.platformFor(value), "value: '" + value + "'");
        }
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

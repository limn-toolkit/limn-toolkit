package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The windowing platform is chosen before {@code glfwInit}, from a property, and getting it wrong
 * is not a compile error: every value is a string and every wrong answer is a running application
 * placing windows somewhere nobody can see.
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
}

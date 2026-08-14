package limn.backend.lwjgl;

import limn.backend.GraphicsInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report is only worth printing if it is populated: a probe that asks the
 * wrong enum, or runs without the context current, returns blanks and zeroes
 * that read like a real answer. These pin the shape against a live context.
 *
 * <p>Deliberately no assertion that a capability gate is <em>present</em>. Their
 * absence is what the report exists to reveal, so a machine missing one must
 * still build green.
 */
class GraphicsProbeTest {

    @Test
    void reportsTheContextItIsGiven() {
        HeadlessGl.assumeAvailable();
        GraphicsInfo info = GraphicsProbe.read(HeadlessGl.window(), HeadlessGl.capabilities());

        assertNull(info.failure(), "a live context is not a failure");
        assertTrue(info.available());
        assertFalse(info.vendor().isBlank(), "vendor");
        assertFalse(info.renderer().isBlank(), "renderer");
        assertFalse(info.version().isBlank(), "version");
        assertFalse(info.shadingLanguage().isBlank(), "shading language");
        assertTrue(info.maxSamples() >= 1, "a context accepts at least one sample per pixel");
        assertFalse(info.extensions().isEmpty(), "no GL 3.3 context advertises zero extensions");
    }

    /**
     * The window is created with the same hints {@code LwjglWindow} uses, and
     * what comes back is the context that was <em>granted</em>, not the one that
     * was asked for: a driver may hand out more than 3.3, and macOS does. That
     * difference is the reason the report reads the window back instead of
     * restating the hints.
     */
    @Test
    void namesTheGrantedProfileAndNotTheRequestedOne() {
        HeadlessGl.assumeAvailable();
        GraphicsInfo info = GraphicsProbe.read(HeadlessGl.window(), HeadlessGl.capabilities());

        assertTrue(info.api().startsWith("OpenGL "), "client API: " + info.api());
        assertTrue(info.api().endsWith(" core"), "profile: " + info.api());
        assertTrue(HeadlessGl.capabilities().OpenGL33, "the toolkit's floor is GL 3.3");
        assertFalse(info.contextApi().isBlank(), "context creation API");
        assertNotEquals("unknown", info.windowPlatform(), "window platform");
    }

    /**
     * The backend installs a GLFW error callback, and the window-creation failure
     * message reads the error back with {@code glfwGetError} afterwards. If a
     * callback consumed the error, that message would degrade to "no error
     * reported" on exactly the machines it exists to diagnose.
     */
    @Test
    void anInstalledErrorCallbackDoesNotSwallowTheError() {
        HeadlessGl.assumeAvailable();
        org.lwjgl.glfw.GLFWErrorCallback silent =
                org.lwjgl.glfw.GLFWErrorCallback.create((code, description) -> {
                });
        org.lwjgl.glfw.GLFWErrorCallback previous = silent.set();
        try {
            GraphicsProbe.lastError(); // clear anything an earlier test left behind
            org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE,
                    org.lwjgl.glfw.GLFW.GLFW_FALSE);
            // No driver grants OpenGL 9.9: a request nothing can satisfy, which is
            // the shape of the failure on a machine with no usable GL.
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 9);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 9);
            long doomed = org.lwjgl.glfw.GLFW.glfwCreateWindow(16, 16, "unsatisfiable", 0L, 0L);
            assertEquals(0L, doomed, "GL 9.9 must not be granted");

            assertNotEquals("no error reported", GraphicsProbe.lastError(),
                    "glfwGetError must still carry the reason with a callback installed");
        } finally {
            org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
            if (previous != null) {
                previous.set();
            }
            silent.free();
        }
    }

    @Test
    void aReportWithoutAContextCarriesOnlyWhatIsKnowableWithoutOne() {
        GraphicsInfo info = GraphicsInfo.unavailable("Wayland", "3.4.0", "no driver");

        assertFalse(info.available());
        assertEquals("Wayland", info.windowPlatform());
        assertEquals("", info.renderer());
        assertEquals(0, info.maxSamples());
        assertTrue(info.extensions().isEmpty());
    }
}

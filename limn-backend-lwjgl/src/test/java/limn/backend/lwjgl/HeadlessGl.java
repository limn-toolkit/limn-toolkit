package limn.backend.lwjgl;

import org.junit.jupiter.api.Assumptions;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;

import java.util.Locale;

/**
 * A real GL context for tests that have to measure what a GPU actually
 * produces, from a hidden GLFW window created once per JVM. Tests that need one
 * call {@link #assumeAvailable()} first and are skipped where no context can be
 * had: a contributor without a GPU, or a CI box with no display, still gets a
 * green build, and the assertions that need silicon simply do not run there.
 *
 * <p><b>Never disable LWJGL's thread-0 check to get further.</b> On macOS
 * creating a window off the process's first thread does not fail, it aborts the
 * JVM with SIGABRT and takes the whole test run with it. That check is what
 * turns a misconfigured JVM into the exception this class catches, and the
 * Gradle test task passes {@code -XstartOnFirstThread} there so the real path
 * runs instead.
 *
 * <p>Holds no process-wide state of its own beyond the window: the UI runtime
 * the device paths check against is installed and removed per test, because
 * other classes in this module install one too and {@code Ui.install} refuses a
 * second, different runtime.
 */
final class HeadlessGl {

    private static boolean attempted;
    private static boolean available;
    private static String failure = "not attempted";
    private static long window;
    private static org.lwjgl.opengl.GLCapabilities capabilities;

    private HeadlessGl() {
    }

    /** Skips the calling test unless a context exists (and makes it current). */
    static void assumeAvailable() {
        Assumptions.assumeTrue(open(), () -> "no GL context available: " + failure);
    }

    /** @return the GLFW window backing the context; only valid after {@link #assumeAvailable()} */
    static long window() {
        return window;
    }

    /** @return the context's capabilities; only valid after {@link #assumeAvailable()} */
    static org.lwjgl.opengl.GLCapabilities capabilities() {
        return capabilities;
    }

    /**
     * Whether the context is a software rasteriser rather than a GPU.
     *
     * <p>Asked by tests that hold the device to an exact number: what a rasteriser computes in
     * software is a legitimate GL implementation and a different precision, so a test that pins
     * arithmetic has to know which one it is talking to. This is CI's answer, always, and the
     * answer on any machine whose driver fell back to Mesa.
     *
     * <p>Matched on {@code GL_RENDERER}, which is the only handle the API offers: Mesa reports
     * "llvmpipe" or "softpipe", a swrast fallback says so, and Windows' own fallback is the
     * "GDI Generic"/"Software Rasterizer" pair. A device this list misses is treated as a GPU,
     * which fails loudly rather than silently loosening an assertion.
     */
    static boolean isSoftware() {
        if (!open()) {
            return false;
        }
        String renderer = GL33C.glGetString(GL33C.GL_RENDERER);
        if (renderer == null) {
            return false;
        }
        String lower = renderer.toLowerCase(Locale.ROOT);
        return lower.contains("llvmpipe") || lower.contains("softpipe")
                || lower.contains("swrast") || lower.contains("software rasterizer")
                || lower.contains("gdi generic");
    }

    /** @return a short description of the device, for a failure message worth reading */
    static String describe() {
        return open()
                ? GL33C.glGetString(GL33C.GL_RENDERER) + " / " + GL33C.glGetString(GL33C.GL_VERSION)
                : "none (" + failure + ")";
    }

    private static synchronized boolean open() {
        if (attempted) {
            return available;
        }
        attempted = true;
        try {
            if (!GLFW.glfwInit()) {
                failure = "glfwInit() returned false (no display?)";
                return false;
            }
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(16, 16, "limn video tests", 0L, 0L);
            if (window == 0L) {
                failure = "glfwCreateWindow() returned NULL (no GL 3.3 core?)";
                return false;
            }
            GLFW.glfwMakeContextCurrent(window);
            capabilities = GL.createCapabilities();
            // The window outlives every test on purpose: contexts are not
            // shared, so tearing it down between classes would mean recompiling
            // every program, and the process exits soon enough either way.
            available = true;
            return true;
        } catch (Throwable error) {
            failure = error.toString();
            return false;
        }
    }

}

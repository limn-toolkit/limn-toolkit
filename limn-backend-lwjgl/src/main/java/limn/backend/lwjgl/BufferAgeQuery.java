package limn.backend.lwjgl;

import org.lwjgl.egl.EGL10;
import org.lwjgl.egl.EXTBufferAge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeEGL;
import org.lwjgl.glfw.GLFWNativeGLX;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.opengl.GLX13;
import org.lwjgl.opengl.GLX14;
import org.lwjgl.opengl.GLXEXTBufferAge;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Asks the driver how old a window's back buffer is, where the driver can say.
 *
 * <p>Partial rendering repaints what changed since the frame the back buffer last held, so it has
 * to know which frame that was, and a guess is not good enough: on the Linux guests of 2026-09-25 a
 * driver that answered 1 for twenty frames then handed over a buffer it had not presented since the
 * first one and went on alternating 1 and 3, and a window that assumed 2 throughout drew its popups
 * empty. Two platforms answer the question: X11 with a GLX context ({@code GLX_EXT_buffer_age}) and
 * any EGL surface, which is every Wayland window ({@code EGL_EXT_buffer_age}). macOS and Windows
 * have no such query, and neither has a driver without the extension; there {@link #age()} says
 * it does not know and the window keeps its own assumption.
 *
 * <p>One per window, made with the window's context current, because the drawable is the window's
 * and the extension string is read once rather than every frame.
 */
final class BufferAgeQuery {

    /** What {@link #age()} answers when the driver cannot say. */
    static final int UNKNOWN = -1;

    private enum Kind { NONE, GLX, EGL }

    private final Kind kind;
    private final long display;
    private final long drawable;

    private BufferAgeQuery(Kind kind, long display, long drawable) {
        this.kind = kind;
        this.display = display;
        this.drawable = drawable;
    }

    /** The query for a window, or one that always answers {@link #UNKNOWN}. */
    static BufferAgeQuery forWindow(long window) {
        int platform = GLFW.glfwGetPlatform();
        boolean egl = platform == GLFW.GLFW_PLATFORM_WAYLAND
                || GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CONTEXT_CREATION_API) == GLFW.GLFW_EGL_CONTEXT_API;
        if (egl) {
            long display = GLFWNativeEGL.glfwGetEGLDisplay();
            long surface = GLFWNativeEGL.glfwGetEGLSurface(window);
            if (display != NULL && surface != NULL
                    && hasExtension(EGL10.eglQueryString(display, EGL10.EGL_EXTENSIONS), "EGL_EXT_buffer_age")) {
                return new BufferAgeQuery(Kind.EGL, display, surface);
            }
        } else if (platform == GLFW.GLFW_PLATFORM_X11
                && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CONTEXT_CREATION_API) == GLFW.GLFW_NATIVE_CONTEXT_API) {
            long display = GLFWNativeX11.glfwGetX11Display();
            long drawable = GLFWNativeGLX.glfwGetGLXWindow(window);
            if (display != NULL && drawable != NULL
                    && hasExtension(GLX14.glXQueryExtensionsString(display, 0), "GLX_EXT_buffer_age")) {
                return new BufferAgeQuery(Kind.GLX, display, drawable);
            }
        }
        return new BufferAgeQuery(Kind.NONE, NULL, NULL);
    }

    /**
     * Whether an extension string names {@code name} as a whole word: a substring test would take
     * {@code EGL_EXT_buffer_age_foo} for {@code EGL_EXT_buffer_age}.
     */
    static boolean hasExtension(String extensions, String name) {
        if (extensions == null) {
            return false;
        }
        for (String each : extensions.trim().split("\\s+")) {
            if (each.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this window's driver answers at all. */
    boolean known() {
        return kind != Kind.NONE;
    }

    /**
     * @return how many presents old the back buffer is (0 when its contents are undefined, which
     *         repaints the window), or {@link #UNKNOWN}. Call it with the window's context current
     *         and before anything is drawn into the frame.
     */
    int age() {
        if (kind == Kind.NONE) {
            return UNKNOWN;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.ints(0);
            if (kind == Kind.GLX) {
                GLX13.glXQueryDrawable(display, drawable, GLXEXTBufferAge.GLX_BACK_BUFFER_AGE_EXT, value);
            } else if (!EGL10.eglQuerySurface(display, drawable, EXTBufferAge.EGL_BUFFER_AGE_EXT, value)) {
                return 0; // the surface refused: repaint everything rather than trust a stale value
            }
            return Math.max(0, value.get(0));
        }
    }
}

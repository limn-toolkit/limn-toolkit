package limn.backend.lwjgl;

import org.jspecify.annotations.Nullable;

import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;

/**
 * The OpenGL context a window draws with, and the five things the window asks of it, whoever made
 * the context: the windowing library, or the backend itself on a window the library made with no
 * context of its own.
 *
 * <p>The backend makes every context itself, so that one lost with the device can be deleted
 * and made again on the same native window, through entry points looked up after each load of the
 * driver; this seam is where that happens. All of it is UI thread only.
 */
interface WindowContext {

    /** Makes the context current on the calling thread, with this window's surface. */
    void makeCurrent();

    /** Presents the back buffer. */
    void swapBuffers();

    /** Sets vsync: 1 waits for the vertical retrace, 0 runs free. Per context, so set every frame. */
    void setSwapInterval(int interval);

    /**
     * The window's drawable may have changed: its framebuffer was resized, or the window was shown
     * for the first time. A context the windowing library made follows by itself.
     */
    default void drawableChanged() {
    }

    /** Releases the context before its window is destroyed, leaving none current. */
    default void dispose() {
    }

    /**
     * @return how the backend made this context, for the graphics report ("WGL", "EGL", "EGL, ANGLE
     *         on Direct3D 11 (fallback)", "NSOpenGL"); null for a context GLFW made, which GLFW's
     *         window attributes describe
     */
    default @Nullable String createdVia() {
        return null;
    }

    /** @return the context GLFW made with {@code window}, which GLFW keeps and releases */
    static WindowContext glfw(long window) {
        return new WindowContext() {
            @Override
            public void makeCurrent() {
                glfwMakeContextCurrent(window);
            }

            @Override
            public void swapBuffers() {
                glfwSwapBuffers(window);
            }

            @Override
            public void setSwapInterval(int interval) {
                glfwSwapInterval(interval);
            }
        };
    }

    /**
     * @param via how the report names it: the accelerated context, or the software fallback
     * @return the {@code NSOpenGLContext} {@link MacSoftwareGl} made, which is ours to release
     */
    static WindowContext nsgl(long context, String via) {
        return new WindowContext() {
            @Override
            public String createdVia() {
                return via;
            }

            @Override
            public void makeCurrent() {
                MacSoftwareGl.makeCurrent(context);
            }

            @Override
            public void swapBuffers() {
                MacSoftwareGl.swapBuffers(context);
            }

            @Override
            public void setSwapInterval(int interval) {
                MacSoftwareGl.setSwapInterval(context, interval);
            }

            @Override
            public void drawableChanged() {
                MacSoftwareGl.update(context);
            }

            @Override
            public void dispose() {
                // It must let go of the view before the window goes.
                MacSoftwareGl.dispose(context);
            }
        };
    }
}

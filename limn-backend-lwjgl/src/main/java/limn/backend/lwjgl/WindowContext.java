package limn.backend.lwjgl;

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

    /** @return the {@code NSOpenGLContext} {@link MacSoftwareGl} made, which is ours to release */
    static WindowContext nsgl(long context) {
        return new WindowContext() {
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

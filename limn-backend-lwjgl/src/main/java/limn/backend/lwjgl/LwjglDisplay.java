package limn.backend.lwjgl;

import limn.backend.Display;
import limn.backend.Resolution;
import limn.backend.ScreenRect;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.glfwGetMonitorContentScale;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorName;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorPos;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorWorkarea;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetVideoModes;

/**
 * GLFW-backed {@link Display}: wraps a monitor handle and reads its state live
 * (queries are cheap and always reflect the current configuration). UI-thread
 * only, because every method touches GLFW.
 */
final class LwjglDisplay implements Display {

    private final long monitor;
    private final int index;
    private final LwjglBackend backend;

    LwjglDisplay(long monitor, int index, LwjglBackend backend) {
        this.monitor = monitor;
        this.index = index;
        this.backend = backend;
    }

    long handle() {
        return monitor;
    }

    @Override
    public String id() {
        return "display-" + index;
    }

    @Override
    public String name() {
        backend.uiRuntime().checkUiThread();
        String name = glfwGetMonitorName(monitor);
        return name != null && !name.isBlank()
                ? name
                : DisplayStrings.FALLBACK_NAME.format(Integer.toString(index + 1));
    }

    @Override
    public boolean isPrimary() {
        backend.uiRuntime().checkUiThread();
        return monitor == glfwGetPrimaryMonitor();
    }

    @Override
    public Resolution currentResolution() {
        backend.uiRuntime().checkUiThread();
        GLFWVidMode mode = glfwGetVideoMode(monitor);
        return mode == null ? new Resolution(1, 1, 0)
                : new Resolution(mode.width(), mode.height(), mode.refreshRate());
    }

    @Override
    public List<Resolution> availableResolutions() {
        backend.uiRuntime().checkUiThread();
        // GLFW returns modes ascending, often duplicated across colour depths;
        // collapse to distinct (w, h, refresh) preserving that order.
        Set<Resolution> unique = new LinkedHashSet<>();
        GLFWVidMode.Buffer modes = glfwGetVideoModes(monitor);
        if (modes != null) {
            for (int i = 0; i < modes.limit(); i++) {
                GLFWVidMode mode = modes.get(i);
                unique.add(new Resolution(mode.width(), mode.height(), mode.refreshRate()));
            }
        }
        return List.copyOf(unique);
    }

    @Override
    public ScreenRect bounds() {
        backend.uiRuntime().checkUiThread();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer px = stack.mallocInt(1);
            IntBuffer py = stack.mallocInt(1);
            glfwGetMonitorPos(monitor, px, py);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            int w = mode == null ? 0 : mode.width();
            int h = mode == null ? 0 : mode.height();
            return new ScreenRect(px.get(0), py.get(0), w, h);
        }
    }

    @Override
    public ScreenRect workArea() {
        backend.uiRuntime().checkUiThread();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetMonitorWorkarea(monitor, x, y, w, h);
            return new ScreenRect(x.get(0), y.get(0), w.get(0), h.get(0));
        }
    }

    @Override
    public float contentScale() {
        backend.uiRuntime().checkUiThread();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer sx = stack.mallocFloat(1);
            FloatBuffer sy = stack.mallocFloat(1);
            glfwGetMonitorContentScale(monitor, sx, sy);
            return sx.get(0);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LwjglDisplay display && display.monitor == monitor;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(monitor);
    }

    @Override
    public String toString() {
        return "LwjglDisplay[" + id() + "]";
    }
}

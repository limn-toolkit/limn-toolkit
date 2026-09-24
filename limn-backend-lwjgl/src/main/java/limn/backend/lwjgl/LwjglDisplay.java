package limn.backend.lwjgl;

import limn.backend.Display;
import limn.backend.Resolution;
import limn.backend.ScreenRect;
import limn.concurrent.UiRuntime;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

import static org.lwjgl.glfw.GLFW.glfwGetMonitorContentScale;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorName;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorPos;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorWorkarea;
import static org.lwjgl.glfw.GLFW.glfwGetMonitors;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetVideoModes;

/**
 * GLFW-backed {@link Display}: wraps a monitor handle and reads its state live
 * (queries are cheap and always reflect the current configuration). UI-thread
 * only, because every method touches GLFW.
 *
 * <p>A display outlives its monitor. The handle is GLFW's memory, freed when the monitor is
 * unplugged, while the object that wraps it stays wherever an application kept it. So every read
 * first asks GLFW whether the monitor is still listed, and one that is not answers what it last
 * read, with {@link #isPrimary()} false: a display that is gone is nobody's primary.
 */
final class LwjglDisplay implements Display {

    private final long monitor;
    private final int index;
    private final UiRuntime ui;
    private final LongPredicate listed;

    // What the monitor answered the last time it was there to ask. Before the first read, the
    // answers of a display that shows nothing.
    private String lastName;
    private Resolution lastResolution = new Resolution(1, 1, 0);
    private List<Resolution> lastResolutions = List.of();
    private ScreenRect lastBounds = new ScreenRect(0, 0, 0, 0);
    private ScreenRect lastWorkArea = new ScreenRect(0, 0, 0, 0);
    private float lastContentScale = 1f;

    LwjglDisplay(long monitor, int index, LwjglBackend backend) {
        this(monitor, index, backend.uiRuntime(), LwjglDisplay::isListed);
    }

    /**
     * @param listed whether GLFW still lists a monitor handle; the backend asks GLFW, and a test
     *               unplugs a monitor by answering false
     */
    LwjglDisplay(long monitor, int index, UiRuntime ui, LongPredicate listed) {
        this.monitor = monitor;
        this.index = index;
        this.ui = ui;
        this.listed = listed;
    }

    /**
     * Whether {@code monitor} is one GLFW currently lists. A handle that matches is live memory,
     * even when the allocator has since reused an unplugged monitor's address for a new one: the
     * answers are then the new monitor's, which is a different display but not a freed one.
     */
    static boolean isListed(long monitor) {
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                if (monitors.get(i) == monitor) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean connected() {
        ui.checkUiThread();
        return listed.test(monitor);
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
        if (connected()) {
            lastName = glfwGetMonitorName(monitor);
        }
        return lastName != null && !lastName.isBlank()
                ? lastName
                : DisplayStrings.FALLBACK_NAME.format(Integer.toString(index + 1));
    }

    @Override
    public boolean isPrimary() {
        return connected() && monitor == glfwGetPrimaryMonitor();
    }

    @Override
    public Resolution currentResolution() {
        if (connected()) {
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            lastResolution = mode == null ? new Resolution(1, 1, 0)
                    : new Resolution(mode.width(), mode.height(), mode.refreshRate());
        }
        return lastResolution;
    }

    @Override
    public List<Resolution> availableResolutions() {
        if (!connected()) {
            return lastResolutions;
        }
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
        lastResolutions = List.copyOf(unique);
        return lastResolutions;
    }

    @Override
    public ScreenRect bounds() {
        if (!connected()) {
            return lastBounds;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer px = stack.mallocInt(1);
            IntBuffer py = stack.mallocInt(1);
            glfwGetMonitorPos(monitor, px, py);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            int w = mode == null ? 0 : mode.width();
            int h = mode == null ? 0 : mode.height();
            lastBounds = new ScreenRect(px.get(0), py.get(0), w, h);
            return lastBounds;
        }
    }

    @Override
    public ScreenRect workArea() {
        if (!connected()) {
            return lastWorkArea;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetMonitorWorkarea(monitor, x, y, w, h);
            lastWorkArea = new ScreenRect(x.get(0), y.get(0), w.get(0), h.get(0));
            return lastWorkArea;
        }
    }

    @Override
    public float contentScale() {
        if (!connected()) {
            return lastContentScale;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer sx = stack.mallocFloat(1);
            FloatBuffer sy = stack.mallocFloat(1);
            glfwGetMonitorContentScale(monitor, sx, sy);
            lastContentScale = sx.get(0);
            return lastContentScale;
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

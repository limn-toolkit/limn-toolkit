package limn.backend.lwjgl;

import limn.backend.GraphicsInfo;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_EGL_CONTEXT_API;
import static org.lwjgl.glfw.GLFW.GLFW_NATIVE_CONTEXT_API;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.GLFW_NO_ERROR;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_API;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_COMPAT_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_ES_API;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OSMESA_CONTEXT_API;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_COCOA;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_NULL;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WIN32;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11;
import static org.lwjgl.glfw.GLFW.glfwGetError;
import static org.lwjgl.glfw.GLFW.glfwGetPlatform;
import static org.lwjgl.glfw.GLFW.glfwGetVersionString;
import static org.lwjgl.glfw.GLFW.glfwGetWindowAttrib;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Reads what a live GL context says about itself, for {@link limn.backend.Backend#graphicsInfo()}.
 *
 * <p>Everything here is read back from the window and the context rather than
 * restated from the hints that created them: a machine that silently gave a
 * different context than the one asked for is exactly the case this answers.
 */
final class GraphicsProbe {

    private GraphicsProbe() {
    }

    /**
     * @param window the GLFW window whose context is <em>already current</em> on
     *               the calling thread
     * @param caps   that context's capabilities, as returned when it was created
     */
    static GraphicsInfo read(long window, GLCapabilities caps) {
        List<String> extensions = extensions();
        return new GraphicsInfo(
                platformName(),
                glfwGetVersionString(),
                apiName(window),
                contextApiName(window),
                GL33C.glGetString(GL33C.GL_VENDOR),
                GL33C.glGetString(GL33C.GL_RENDERER),
                GL33C.glGetString(GL33C.GL_VERSION),
                GL33C.glGetString(GL33C.GL_SHADING_LANGUAGE_VERSION),
                GL33C.glGetInteger(GL33C.GL_MAX_SAMPLES),
                // Timing became core in desktop 3.3; everywhere else it is an
                // extension, and the ES spelling of it is a different name.
                caps.OpenGL33 || caps.GL_ARB_timer_query
                        || extensions.contains("GL_EXT_disjoint_timer_query"),
                // Rendering to RGBA16F and sampling R16 are both core in desktop
                // 3.0 and both extensions on ES, where a context may have either,
                // neither, or one without the other.
                caps.OpenGL30 || extensions.contains("GL_EXT_color_buffer_float"),
                caps.OpenGL30 || extensions.contains("GL_EXT_texture_norm16"),
                extensions,
                null);
    }

    /** @return the windowing system GLFW selected at startup; never null */
    static String platformName() {
        return switch (glfwGetPlatform()) {
            case GLFW_PLATFORM_WIN32 -> "Win32";
            case GLFW_PLATFORM_COCOA -> "Cocoa";
            case GLFW_PLATFORM_WAYLAND -> "Wayland";
            case GLFW_PLATFORM_X11 -> "X11";
            case GLFW_PLATFORM_NULL -> "Null";
            default -> "unknown";
        };
    }

    /**
     * @return GLFW's last error on this thread as readable text, never null.
     *         Reading it clears it, so call this once, at the failure.
     */
    static String lastError() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer description = stack.mallocPointer(1);
            int code = glfwGetError(description);
            if (code == GLFW_NO_ERROR) {
                return "no error reported";
            }
            long text = description.get(0);
            return (text == NULL ? "unnamed error" : MemoryUtil.memUTF8(text))
                    + " (0x" + Integer.toHexString(code) + ")";
        }
    }

    private static List<String> extensions() {
        int count = GL33C.glGetInteger(GL33C.GL_NUM_EXTENSIONS);
        List<String> names = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            names.add(GL33C.glGetStringi(GL33C.GL_EXTENSIONS, i));
        }
        Collections.sort(names);
        return names;
    }

    private static String apiName(long window) {
        String api = switch (glfwGetWindowAttrib(window, GLFW_CLIENT_API)) {
            case GLFW_OPENGL_API -> "OpenGL";
            case GLFW_OPENGL_ES_API -> "OpenGL ES";
            case GLFW_NO_API -> "none";
            default -> "unknown";
        };
        String profile = switch (glfwGetWindowAttrib(window, GLFW_OPENGL_PROFILE)) {
            case GLFW_OPENGL_CORE_PROFILE -> " core";
            case GLFW_OPENGL_COMPAT_PROFILE -> " compatibility";
            default -> "";
        };
        return api + " " + glfwGetWindowAttrib(window, GLFW_CONTEXT_VERSION_MAJOR)
                + "." + glfwGetWindowAttrib(window, GLFW_CONTEXT_VERSION_MINOR) + profile;
    }

    private static String contextApiName(long window) {
        return switch (glfwGetWindowAttrib(window, GLFW_CONTEXT_CREATION_API)) {
            case GLFW_NATIVE_CONTEXT_API -> "native";
            case GLFW_EGL_CONTEXT_API -> "EGL";
            case GLFW_OSMESA_CONTEXT_API -> "OSMesa";
            default -> "unknown";
        };
    }
}

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

import static org.lwjgl.glfw.GLFW.GLFW_API_UNAVAILABLE;
import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_EGL_CONTEXT_API;
import static org.lwjgl.glfw.GLFW.GLFW_FORMAT_UNAVAILABLE;
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
import static org.lwjgl.glfw.GLFW.GLFW_VERSION_UNAVAILABLE;
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
        return lastFailure().toString();
    }

    /**
     * The same reading as {@link #lastError()}, with the code still a number: a caller that has to
     * <em>act</em> on one specific failure cannot be asked to match on prose.
     *
     * @return GLFW's last error on this thread, never null. Reading it clears it, so take either
     *         this or {@link #lastError()}, once, at the failure.
     */
    static Failure lastFailure() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer description = stack.mallocPointer(1);
            int code = glfwGetError(description);
            if (code == GLFW_NO_ERROR) {
                return new Failure(GLFW_NO_ERROR, "no error reported");
            }
            long text = description.get(0);
            return new Failure(code, text == NULL ? "unnamed error" : MemoryUtil.memUTF8(text));
        }
    }

    /**
     * What to do about a window whose OpenGL 3.3 core context could not be had, appended to GLFW's
     * own description of the refusal; empty where there is nothing better to say than that.
     *
     * <p>Only Windows has an answer. There the OpenGL a machine has is whatever its GPU's driver
     * installed, and without one it is Microsoft's GDI Generic, OpenGL 1.1, which GLFW refuses (ADR
     * 003 §3): a virtual machine, a remote-desktop session, a GPU whose driver is Direct3D only.
     * Microsoft's Compatibility Pack is the fix that reaches all three. It installs OpenGL over
     * Direct3D 12 (Mesa's D3D12 driver), which Windows uses only where no other OpenGL driver is
     * present, so it cannot shadow a working one. Limn does not ship it or anything like it: Mesa's
     * Windows build is 62.8 MB for the x64 OpenGL files alone (measured 2026-09-25), and a driver the
     * machine should have belongs to the machine.
     *
     * <p>Decided by the error's code, not its text. The three are the ones GLFW's WGL path raises
     * when there is no usable driver ("The driver does not appear to support OpenGL"), when the
     * driver is older than 3.3 or lacks the core profile, and when no pixel format qualifies.
     *
     * @param os   the platform, a parameter so that every branch can be tested anywhere
     * @param code the GLFW error code of the failed window creation
     */
    static String contextAdvice(limn.backend.Platform.Os os, int code) {
        if (!lacksDriver(os, code)) {
            return "";
        }
        return "; this Windows machine has no OpenGL 3.3 driver. Install or update the graphics "
                + "driver from the GPU's maker; where none offers OpenGL 3.3 (a virtual machine, a "
                + "remote-desktop session, a GPU with a Direct3D-only driver), install Microsoft's "
                + "free \"OpenCL, OpenGL, and Vulkan Compatibility Pack\" (Microsoft Store "
                + COMPATIBILITY_PACK_STORE_ID + ", or: winget install "
                + COMPATIBILITY_PACK_WINGET_ID + "), which provides OpenGL 3.3 over Direct3D 12";
    }

    /**
     * Whether a failed window creation means this machine has no OpenGL 3.3 driver: Windows, and
     * one of the three refusals {@link #contextAdvice} answers. The same test decides whether a
     * person who launched the application from its icon is shown {@link WindowsDriverDialog}.
     *
     * @param os   the platform, a parameter so that every branch can be tested anywhere
     * @param code the GLFW error code of the failed window creation
     */
    static boolean lacksDriver(limn.backend.Platform.Os os, int code) {
        return os == limn.backend.Platform.Os.WINDOWS
                && (code == GLFW_API_UNAVAILABLE || code == GLFW_VERSION_UNAVAILABLE
                        || code == GLFW_FORMAT_UNAVAILABLE);
    }

    /** The Compatibility Pack's Microsoft Store product ID. */
    static final String COMPATIBILITY_PACK_STORE_ID = "9NQPSL29BFFF";
    /** The Compatibility Pack's winget package identifier. */
    static final String COMPATIBILITY_PACK_WINGET_ID = "Microsoft.OpenCLGLVulkanCompatibilityPack";

    /** A GLFW error code and the description GLFW gave with it. */
    record Failure(int code, String description) {
        @Override
        public String toString() {
            return code == GLFW_NO_ERROR
                    ? description
                    : description + " (0x" + Integer.toHexString(code) + ")";
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
        if (foreignContext(window)) {
            // Every context attribute GLFW holds for this window is zero, and
            // "none 0.0" would read as "no OpenGL here" on a machine that is
            // plainly running some. What it is running is on the version line.
            return "OpenGL, from a context GLFW did not create";
        }
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

    /**
     * @return whether the context these figures were read from is not GLFW's. Only a window asked
     *         for with {@code GLFW_NO_API} can be in that state, and reading a live GL version off
     *         one means somebody supplied a context for it — on macOS, {@link MacSoftwareGl}.
     */
    private static boolean foreignContext(long window) {
        return glfwGetWindowAttrib(window, GLFW_CLIENT_API) == GLFW_NO_API;
    }

    private static String contextApiName(long window) {
        if (foreignContext(window)) {
            return "the application's own";
        }
        return switch (glfwGetWindowAttrib(window, GLFW_CONTEXT_CREATION_API)) {
            case GLFW_NATIVE_CONTEXT_API -> "native";
            case GLFW_EGL_CONTEXT_API -> "EGL";
            case GLFW_OSMESA_CONTEXT_API -> "OSMesa";
            default -> "unknown";
        };
    }
}

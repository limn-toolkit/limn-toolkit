package limn.backend.lwjgl;

import limn.backend.GraphicsInfo;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL33C;
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
     */
    static GraphicsInfo read(long window) {
        List<String> extensions = extensions();
        return new GraphicsInfo(
                platformName(),
                glfwGetVersionString(),
                apiName(window),
                contextApiName(window),
                Gl.glGetString(GL33C.GL_VENDOR),
                Gl.glGetString(GL33C.GL_RENDERER),
                Gl.glGetString(GL33C.GL_VERSION),
                Gl.glGetString(GL33C.GL_SHADING_LANGUAGE_VERSION),
                Gl.glGetInteger(GL33C.GL_MAX_SAMPLES),
                // Each profile answers for its own context: core in desktop 3.3
                // (timing) and 3.0 (the other two), extensions on ES, where a
                // context may have any of them without the others.
                Gl.api().timerQueries(),
                Gl.api().floatColorBuffer(),
                Gl.api().norm16Textures(),
                extensions,
                null);
    }

    /**
     * Whether a context's {@code GL_RENDERER} names a rasterizer that runs on the CPU rather than a
     * GPU, where every fragment the shaders run is paid for in CPU time and a draw call is cheap by
     * comparison. {@code GL_RENDERER} is the only handle the API offers: macOS names its fallback
     * "Apple Software Renderer" (whichever way {@link MacSoftwareGl} came to it, or a VM's only
     * renderer), Mesa says "llvmpipe", "softpipe" or "swrast", Windows' own fallback is "GDI
     * Generic" (OpenGL 1.1, refused before a canvas exists, named for completeness) and Microsoft's
     * WARP adapter "Microsoft Basic Render Driver", which is what Mesa's Direct3D 12 driver reports
     * on a machine without a GPU. A name this misses is taken for a GPU.
     *
     * @param renderer the context's {@code GL_RENDERER}, or null when it answered none
     */
    static boolean isSoftwareRenderer(@Nullable String renderer) {
        if (renderer == null) {
            return false;
        }
        String name = renderer.toLowerCase(java.util.Locale.ROOT);
        return name.contains("apple software renderer") || name.contains("llvmpipe")
                || name.contains("softpipe") || name.contains("swrast")
                || name.contains("software rasterizer") || name.contains("gdi generic")
                || name.contains("microsoft basic render driver");
    }

    /**
     * What the current context says of itself that LWJGL's {@code GL.createCapabilities()} reads,
     * read before the context has capabilities ({@link GlCapabilitiesCache}): through the
     * functions' own addresses, because LWJGL's {@code glGetString} and {@code glGetIntegerv} go
     * through the capabilities this answer is asked for in order to choose. {@code glGetString}
     * and {@code glGetIntegerv} are exported beside every platform's GL 1.1 entry points;
     * {@code glGetStringi} is asked of the function provider for this context, as
     * {@code createCapabilities} asks.
     *
     * @param softwareGl whether the context is the macOS software one
     * @return the key; a part a context does not answer is null or 0
     */
    static GlCapabilitiesCache.Key currentContextKey(boolean softwareGl) {
        org.lwjgl.system.FunctionProvider functions = Gl.api().functionProvider();
        long glGetString = functions == null ? NULL : functions.getFunctionAddress("glGetString");
        long glGetIntegerv = functions == null ? NULL : functions.getFunctionAddress("glGetIntegerv");
        long glGetStringi = functions == null ? NULL : functions.getFunctionAddress("glGetStringi");
        int flags = 0;
        int profile = 0;
        StringBuilder extensions = new StringBuilder();
        if (glGetIntegerv != NULL) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.IntBuffer answer = stack.ints(0);
                long at = MemoryUtil.memAddress(answer);
                // Flags and profile mask are the desktop's; ES 3.0 has neither and would answer
                // with an error, so an ES context's key leaves them 0.
                if (Gl.api() == DesktopGl.INSTANCE) {
                    org.lwjgl.system.JNI.invokePV(GL33C.GL_CONTEXT_FLAGS, at, glGetIntegerv);
                    flags = answer.get(0);
                    answer.put(0, 0);
                    org.lwjgl.system.JNI.invokePV(GL33C.GL_CONTEXT_PROFILE_MASK, at, glGetIntegerv);
                    profile = answer.get(0);
                    answer.put(0, 0);
                }
                org.lwjgl.system.JNI.invokePV(GL33C.GL_NUM_EXTENSIONS, at, glGetIntegerv);
                int count = answer.get(0);
                for (int i = 0; glGetStringi != NULL && i < count; i++) {
                    extensions.append(MemoryUtil.memASCIISafe(
                            org.lwjgl.system.JNI.invokeP(GL33C.GL_EXTENSIONS, i, glGetStringi))).append(' ');
                }
            }
        }
        // A query a context refuses leaves an error behind, which must not be the first thing the
        // window's own drawing reads back.
        long glGetError = functions == null ? NULL : functions.getFunctionAddress("glGetError");
        for (int i = 0; glGetError != NULL && i < 8 && org.lwjgl.system.JNI.invokeI(glGetError) != 0; i++) {
            // drained
        }
        return new GlCapabilitiesCache.Key(softwareGl,
                glGetString == NULL ? null : MemoryUtil.memUTF8Safe(
                        org.lwjgl.system.JNI.invokeP(GL33C.GL_VENDOR, glGetString)),
                glGetString == NULL ? null : MemoryUtil.memUTF8Safe(
                        org.lwjgl.system.JNI.invokeP(GL33C.GL_RENDERER, glGetString)),
                glGetString == NULL ? null : MemoryUtil.memUTF8Safe(
                        org.lwjgl.system.JNI.invokeP(GL33C.GL_VERSION, glGetString)),
                flags, profile, extensions.toString());
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
        int count = Gl.glGetInteger(GL33C.GL_NUM_EXTENSIONS);
        List<String> names = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            names.add(Gl.glGetStringi(GL33C.GL_EXTENSIONS, i));
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
            case GLFW_EGL_CONTEXT_API -> AngleFallback.taken() ? "EGL, " + AngleFallback.describe() : "EGL";
            case GLFW_OSMESA_CONTEXT_API -> "OSMesa";
            default -> "unknown";
        };
    }
}

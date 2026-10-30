package limn.backend.lwjgl;

import limn.backend.lwjgl.internal.NativeLibraries;

import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL10;
import org.lwjgl.egl.EGL11;
import org.lwjgl.egl.EGL12;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeEGL;
import org.lwjgl.glfw.GLFWNativeWayland;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * An OpenGL 3.3 core context the backend makes itself through EGL, on Linux (ADR 082 §1.1).
 *
 * <p><b>On Wayland</b> GLFW makes the window with no context, and everything else is made here: the
 * {@code wl_egl_window} over the window's surface, the EGL surface on it and the context, from a
 * configuration with alpha, depth and stencil. The window's size is this class's to pass on, which
 * {@link #drawableChanged} does.
 *
 * <p><b>On X11</b> the window's visual is chosen when its context is, and a window GLFW makes with no
 * context gets the server's default visual, which has no alpha: a transparent popup would show black
 * where it is meant to show what is behind (measured on 2026-10-29). So GLFW makes the window with an
 * EGL context, which chooses the visual and is never made current, and the context made here takes
 * that context's configuration and draws on GLFW's surface. Nothing is unloaded on Linux, so a
 * context GLFW keeps holds no pointer that could go stale.
 *
 * <p><b>On Windows, ANGLE.</b> When the process draws with ANGLE, from the start or since the
 * system's OpenGL did not come back after a loss (ADR 082 §1.4, step 2), the context is ANGLE's on
 * Direct3D 11, on a window GLFW made with no context: one display for the process, terminated and
 * made again when ANGLE reports the context lost (step 3, measured on 2026-10-29).
 *
 * <p>UI thread only.
 */
final class EglContext implements WindowContext {

    // ---- EGL 1.4/1.5 and EGL_KHR_create_context, verbatim -------------------------------------
    private static final int EGL_OPENGL_BIT = 0x0008;
    private static final int EGL_OPENGL_API = 0x30A2;
    private static final int EGL_CONTEXT_MAJOR_VERSION = 0x3098;
    private static final int EGL_CONTEXT_MINOR_VERSION = 0x30FB;
    private static final int EGL_CONTEXT_FLAGS_KHR = 0x30FC;
    private static final int EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE_BIT_KHR = 0x2;
    private static final int EGL_CONTEXT_OPENGL_PROFILE_MASK = 0x30FD;
    private static final int EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT = 0x1;
    private static final int EGL_PLATFORM_WAYLAND_KHR = 0x31D8;
    private static final int EGL_OPENGL_ES_API = 0x30A0;
    private static final int EGL_OPENGL_ES3_BIT = 0x0040;
    private static final int EGL_CONTEXT_LOST = 0x300E;
    private static final int EGL_PLATFORM_ANGLE_ANGLE = 0x3202;
    private static final int EGL_PLATFORM_ANGLE_TYPE_ANGLE = 0x3203;
    private static final int EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE = 0x3208;
    private static final int EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT = 0x3138;
    private static final int EGL_LOSE_CONTEXT_ON_RESET_EXT = 0x31BF;
    /** EGL 1.5's name for the strategy, which Mesa takes for desktop OpenGL contexts. */
    private static final int EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY = 0x31BD;

    /** ANGLE's display, one for the process; NULL until the first ANGLE context, and after a loss. */
    private static long angleDisplay;
    /** Its extensions: a display's, which the client's capabilities do not carry. */
    private static org.lwjgl.egl.@Nullable EGLCapabilities angleCapabilities;

    private static @Nullable SharedLibrary waylandEgl;
    private static long waylandDisplay;

    private final long window;
    private final long display;
    private final long config;
    private final long eglWindow;
    private final long surface;
    private final boolean ownSurface;
    private long context;
    /** Whether a swap or a make-current said the context is lost: ANGLE's word, which the watch asks. */
    private boolean lost;

    private EglContext(long window, long display, long config, long eglWindow, long surface, boolean ownSurface,
            long context) {
        this.window = window;
        this.display = display;
        this.config = config;
        this.eglWindow = eglWindow;
        this.surface = surface;
        this.ownSurface = ownSurface;
        this.context = context;
    }

    /** The 3.3 core context's attributes, for {@code eglCreateContext}, robust or not. */
    private static IntBuffer contextAttributes(MemoryStack stack, boolean robust) {
        return robust
                ? stack.ints(
                        EGL_CONTEXT_MAJOR_VERSION, 3,
                        EGL_CONTEXT_MINOR_VERSION, 3,
                        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                        EGL_CONTEXT_FLAGS_KHR, EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE_BIT_KHR,
                        EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY, EGL_LOSE_CONTEXT_ON_RESET_EXT,
                        EGL10.EGL_NONE)
                : stack.ints(
                        EGL_CONTEXT_MAJOR_VERSION, 3,
                        EGL_CONTEXT_MINOR_VERSION, 3,
                        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                        EGL_CONTEXT_FLAGS_KHR, EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE_BIT_KHR,
                        EGL10.EGL_NONE);
    }

    /** A 3.3 core context, robust where the driver takes the request (ADR 082 §1.3), plain where not. */
    private static long createDesktopContext(long display, long config, MemoryStack stack) {
        long context = EGL10.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttributes(stack, true));
        if (context == NULL) {
            context = EGL10.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttributes(stack, false));
        }
        return context;
    }

    /**
     * On Wayland: the surface, the EGL window over it and the context, on a window GLFW made with
     * no context. Left not current.
     *
     * @return the context, or null if EGL or the 3.3 core context is missing
     */
    static @Nullable EglContext onWayland(long window) {
        long display = waylandDisplay();
        if (display == NULL) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer wanted = stack.ints(
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                    EGL12.EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 8,
                    EGL10.EGL_NONE);
            PointerBuffer configs = stack.mallocPointer(1);
            IntBuffer count = stack.mallocInt(1);
            if (!EGL10.eglChooseConfig(display, wanted, configs, count) || count.get(0) == 0) {
                return null;
            }
            long config = configs.get(0);
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, width, height);
            SharedLibrary library = waylandEgl();
            long create = NativeLibraries.address(library, "wl_egl_window_create");
            long eglWindow = JNI.invokePP(GLFWNativeWayland.glfwGetWaylandWindow(window),
                    Math.max(1, width.get(0)), Math.max(1, height.get(0)), create);
            if (eglWindow == NULL) {
                return null;
            }
            long surface = EGL10.eglCreateWindowSurface(display, config, eglWindow, (IntBuffer) null);
            if (surface == NULL) {
                JNI.invokePV(eglWindow, NativeLibraries.address(library, "wl_egl_window_destroy"));
                return null;
            }
            EGL12.eglBindAPI(EGL_OPENGL_API);
            long context = createDesktopContext(display, config, stack);
            if (context == NULL) {
                EGL10.eglDestroySurface(display, surface);
                JNI.invokePV(eglWindow, NativeLibraries.address(library, "wl_egl_window_destroy"));
                return null;
            }
            return new EglContext(window, display, config, eglWindow, surface, true, context);
        }
    }

    /**
     * On X11: a context of the configuration GLFW chose for {@code window}, drawing on GLFW's surface,
     * GLFW's own context left idle. Left not current.
     *
     * @return the context, or null if GLFW made the window without EGL
     */
    static @Nullable EglContext onGlfwSurface(long window) {
        long display = GLFWNativeEGL.glfwGetEGLDisplay();
        long surface = GLFWNativeEGL.glfwGetEGLSurface(window);
        if (display == NULL || surface == NULL) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer config = stack.mallocPointer(1);
            if (!GLFWNativeEGL.glfwGetEGLConfig(window, config)) {
                return null;
            }
            EGL12.eglBindAPI(EGL_OPENGL_API);
            long context = createDesktopContext(display, config.get(0), stack);
            if (context == NULL) {
                return null;
            }
            return new EglContext(window, display, config.get(0), NULL, surface, false, context);
        }
    }

    /**
     * On Windows, with ANGLE: the surface and an OpenGL ES 3.0 context on {@code nativeWindow}, a
     * window that has none of GLFW's. Left not current. The caller has loaded ANGLE and moved the
     * process onto the ES profile.
     *
     * @return the context, or null if ANGLE gave none
     */
    static @Nullable EglContext onAngle(long window, long nativeWindow) {
        long display = angleDisplay();
        if (display == NULL) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer wanted = stack.ints(
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                    EGL12.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 8,
                    EGL10.EGL_NONE);
            PointerBuffer configs = stack.mallocPointer(1);
            IntBuffer count = stack.mallocInt(1);
            if (!EGL10.eglChooseConfig(display, wanted, configs, count) || count.get(0) == 0) {
                return null;
            }
            long config = configs.get(0);
            long surface = EGL10.eglCreateWindowSurface(display, config, nativeWindow, (IntBuffer) null);
            if (surface == NULL) {
                return null;
            }
            EGL12.eglBindAPI(EGL_OPENGL_ES_API);
            boolean robust = angleCapabilities != null && angleCapabilities.EGL_EXT_create_context_robustness;
            IntBuffer attributes = robust
                    ? stack.ints(EGL_CONTEXT_MAJOR_VERSION, 3,
                            EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT, EGL_LOSE_CONTEXT_ON_RESET_EXT,
                            EGL10.EGL_NONE)
                    : stack.ints(EGL_CONTEXT_MAJOR_VERSION, 3, EGL10.EGL_NONE);
            long context = EGL10.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attributes);
            if (context == NULL) {
                EGL10.eglDestroySurface(display, surface);
                return null;
            }
            return new EglContext(window, display, config, NULL, surface, true, context);
        }
    }

    /** @return ANGLE's display on Direct3D 11, made and initialised on first use */
    static long angleDisplay() {
        if (angleDisplay != NULL) {
            return angleDisplay;
        }
        long platformDisplay = EGL.getFunctionProvider().getFunctionAddress("eglGetPlatformDisplayEXT");
        if (platformDisplay == NULL) {
            return NULL;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = stack.ints(EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
                    EGL10.EGL_NONE);
            long display = JNI.invokePPP(EGL_PLATFORM_ANGLE_ANGLE, NULL,
                    org.lwjgl.system.MemoryUtil.memAddress(attributes), platformDisplay);
            if (display == NULL || !EGL10.eglInitialize(display, stack.mallocInt(1), stack.mallocInt(1))) {
                return NULL;
            }
            angleCapabilities = EGL.createDisplayCapabilities(display);
            angleDisplay = display;
            return display;
        }
    }

    /**
     * Terminates ANGLE's display after its contexts were lost, so that the next context makes a new
     * Direct3D device (ADR 082 §1.4, step 3). Every ANGLE context and surface must be gone first.
     */
    static void resetAngleDisplay() {
        if (angleDisplay != NULL) {
            EGL10.eglTerminate(angleDisplay);
            angleDisplay = NULL;
            angleCapabilities = null;
        }
    }

    /** @return whether a swap or a make-current reported the context lost */
    boolean reportedLost() {
        return lost;
    }

    private static long waylandDisplay() {
        if (waylandDisplay != NULL) {
            return waylandDisplay;
        }
        long wlDisplay = GLFWNativeWayland.glfwGetWaylandDisplay();
        if (wlDisplay == NULL) {
            return NULL;
        }
        long display = NULL;
        long platformDisplay = EGL.getCapabilities().EGL_EXT_platform_base
                ? EGL.getFunctionProvider().getFunctionAddress("eglGetPlatformDisplayEXT") : NULL;
        if (platformDisplay != NULL) {
            display = JNI.invokePPP(EGL_PLATFORM_WAYLAND_KHR, wlDisplay, NULL, platformDisplay);
        }
        if (display == NULL) {
            display = EGL10.eglGetDisplay(wlDisplay);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (display == NULL || !EGL10.eglInitialize(display, stack.mallocInt(1), stack.mallocInt(1))) {
                return NULL;
            }
        }
        waylandDisplay = display;
        return display;
    }

    private static SharedLibrary waylandEgl() {
        if (waylandEgl == null) {
            waylandEgl = APIUtil.apiCreateLibrary("libwayland-egl.so.1");
        }
        return waylandEgl;
    }

    /** @return the EGL display and surface, for the back buffer's age */
    long display() {
        return display;
    }

    long surface() {
        return surface;
    }

    @Override
    public void makeCurrent() {
        if (!EGL10.eglMakeCurrent(display, surface, surface, context)) {
            lost |= EGL10.eglGetError() == EGL_CONTEXT_LOST;
        }
    }

    @Override
    public void swapBuffers() {
        if (!EGL10.eglSwapBuffers(display, surface)) {
            lost |= EGL10.eglGetError() == EGL_CONTEXT_LOST;
        }
    }

    @Override
    public void setSwapInterval(int interval) {
        EGL11.eglSwapInterval(display, interval);
    }

    @Override
    public void drawableChanged() {
        if (eglWindow != NULL) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                GLFW.glfwGetFramebufferSize(window, width, height);
                JNI.invokePV(eglWindow, Math.max(1, width.get(0)), Math.max(1, height.get(0)), 0, 0,
                        NativeLibraries.address(waylandEgl(), "wl_egl_window_resize"));
            }
        }
    }

    @Override
    public void dispose() {
        EGL10.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
        EGL10.eglDestroyContext(display, context);
        if (ownSurface) {
            EGL10.eglDestroySurface(display, surface);
            if (eglWindow != NULL) {
                JNI.invokePV(eglWindow, NativeLibraries.address(waylandEgl(), "wl_egl_window_destroy"));
            }
        }
    }
}

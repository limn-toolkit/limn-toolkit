package limn.backend.lwjgl;

import limn.graphics.Canvas;
import limn.graphics.Image;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.CGL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

import java.nio.IntBuffer;
import java.util.function.Consumer;

/**
 * The backend's own OpenGL context, with no window shown: what a picture off screen is drawn with
 * when no window of the application's can draw it, none being open, or none ever, the backend having
 * started without a display ({@link LwjglBackend#HEADLESS_PROPERTY}).
 *
 * <p>The context is the same kind the windows draw with, on the same profile, so a picture is drawn
 * by the same canvas program, through the same driver or the same software fallback, and comes out
 * as a window would show it. Which one, measured on 2026-11-03 on every system the toolkit supports:
 *
 * <ul>
 *   <li><b>macOS</b>: a CGL context, which needs no window and no window server, on the GPU and
 *       otherwise on Apple's software renderer.</li>
 *   <li><b>Linux</b>: a hidden window of the windowing library, which with no display at all runs on
 *       its null platform and asks EGL for the context: Mesa's driver of the GPU through its render
 *       node, or llvmpipe on the processor.</li>
 *   <li><b>Windows</b>: a hidden window's OpenGL context where the process draws with the system's
 *       OpenGL; ANGLE's on Direct3D 11, on a one-pixel surface off any window, where it draws with
 *       ANGLE, and in a service's session, where making a window's OpenGL context ends the process
 *       (an access violation in {@code ntdll}, measured over SSH).</li>
 * </ul>
 *
 * <p>Made on first use and kept, with a canvas of its own, until the backend stops. UI thread only.
 */
final class OffscreenContext {

    /** {@code kCGLRendererGenericFloatID}, "Apple Software Renderer", from CGLRenderers.h. */
    private static final int RENDERER_APPLE_SOFTWARE = 0x00020400;

    private final FontStore fonts;
    private final boolean headless;
    private long cglContext;
    private long window;
    private @Nullable EglContext eglContext;
    private Object capabilities;
    private @Nullable GlCanvas canvas;
    private boolean attempted;
    private @Nullable String failure;
    /** The profile the context was made on: the process may move onto ANGLE after it. */
    private @Nullable GlApi profile;

    OffscreenContext(FontStore fonts, boolean headless) {
        this.fonts = fonts;
        this.headless = headless;
    }

    /**
     * Draws a picture off screen with this context, made now if it has not been.
     *
     * @return the picture, or null where no context can be had here ({@link #failure()} says why)
     */
    @Nullable Image paint(int width, int height, float scale, Consumer<Canvas> painter) {
        if (profile != null && profile != Gl.api()) {
            // A window moved the process onto ANGLE since: the context and its canvas are of the
            // binding that went, and are let go of without a call to it, then made again on ANGLE.
            forget();
        }
        if (!open()) {
            return null;
        }
        makeCurrent();
        if (canvas == null) {
            canvas = new GlCanvas(fonts);
        }
        return GlOffscreen.paint(canvas, width, height, scale, painter);
    }

    /** @return why no context could be had, or null */
    @Nullable String failure() {
        return failure;
    }

    /** @return what the context is, for a log line; "none" before one was made */
    String describe() {
        if (capabilities == null) {
            return "none";
        }
        makeCurrent();
        return Gl.glGetString(org.lwjgl.opengl.GL33C.GL_RENDERER) + " / "
                + Gl.glGetString(org.lwjgl.opengl.GL33C.GL_VERSION);
    }

    private boolean open() {
        if (attempted) {
            return capabilities != null;
        }
        attempted = true;
        boolean made = switch (Platform.get()) {
            case MACOSX -> cgl(false) || cgl(true);
            case WINDOWS -> (headless || AngleFallback.taken() || AngleFallback.forced()) ? angle()
                    : hiddenWindow() || angle();
            default -> hiddenWindow();
        };
        if (!made) {
            return false;
        }
        capabilities = Gl.api().createCapabilities();
        profile = Gl.api();
        System.getLogger(OffscreenContext.class.getName()).log(System.Logger.Level.INFO,
                "pictures off screen are drawn with {0}", describe());
        return true;
    }

    /** A CGL context with no window: an accelerated renderer, or Apple's software one by its id. */
    private boolean cgl(boolean software) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = software
                    ? stack.ints(CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
                            CGL.kCGLPFARendererID, RENDERER_APPLE_SOFTWARE, 0)
                    : stack.ints(CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
                            CGL.kCGLPFAAccelerated, 0);
            PointerBuffer format = stack.mallocPointer(1);
            IntBuffer count = stack.mallocInt(1);
            if (CGL.CGLChoosePixelFormat(attributes, format, count) != 0 || format.get(0) == 0L) {
                failure = "CGL gave no " + (software ? "software" : "accelerated") + " pixel format";
                return false;
            }
            PointerBuffer context = stack.mallocPointer(1);
            int error = CGL.CGLCreateContext(format.get(0), 0L, context);
            CGL.CGLDestroyPixelFormat(format.get(0));
            if (error != 0 || context.get(0) == 0L) {
                failure = "CGL made no context: error " + error;
                return false;
            }
            cglContext = context.get(0);
            CGL.CGLSetCurrentContext(cglContext);
            return true;
        }
    }

    /**
     * A hidden window of the windowing library with an OpenGL 3.3 core context: on the null platform,
     * with no display, the context comes from EGL.
     */
    private boolean hiddenWindow() {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_NULL) {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API);
        }
        long made = GLFW.glfwCreateWindow(16, 16, "", 0L, 0L);
        GLFW.glfwDefaultWindowHints();
        if (made == 0L) {
            failure = "no hidden window with an OpenGL 3.3 context: " + GraphicsProbe.lastError();
            return false;
        }
        window = made;
        GLFW.glfwMakeContextCurrent(window);
        return true;
    }

    /** ANGLE's context on Direct3D 11, off any window, the process moved onto its profile. */
    private boolean angle() {
        if (!AngleFallback.takeWithoutWindows()) {
            failure = "no ANGLE: " + AngleFallback.failure();
            return false;
        }
        EglContext made = EglContext.onAnglePbuffer();
        if (made == null) {
            failure = "ANGLE made no OpenGL ES 3.0 context off a window";
            return false;
        }
        eglContext = made;
        made.makeCurrent();
        return true;
    }

    private void makeCurrent() {
        if (cglContext != 0L) {
            CGL.CGLSetCurrentContext(cglContext);
        } else if (eglContext != null) {
            eglContext.makeCurrent();
        } else {
            GLFW.glfwMakeContextCurrent(window);
        }
        Gl.api().setCapabilities(capabilities);
    }

    /**
     * Lets go of a context that may be lost with the device, at a sign that the machine was away or
     * before the windows' contexts are made again: made anew on the next picture. ANGLE's display goes
     * with it where no window holds it, since a lost device leaves it unable to make a context.
     *
     * @param windowsOpen whether windows of the application's are open, whose rebuild resets ANGLE's
     *                    display itself
     */
    void forgetLost(boolean windowsOpen) {
        if (!attempted || capabilities == null) {
            attempted = false;
            return;
        }
        boolean angle = eglContext != null;
        forget();
        if (angle && !windowsOpen) {
            EglContext.resetAngleDisplay();
        }
        System.getLogger(OffscreenContext.class.getName()).log(System.Logger.Level.INFO,
                "the context pictures off screen are drawn with is made again at its next use");
    }

    /** Forgets the context and its canvas without a GL call, their binding being gone. */
    private void forget() {
        if (canvas != null) {
            canvas.contextLost();
            canvas = null;
        }
        if (window != 0L) {
            GLFW.glfwDestroyWindow(window);
            window = 0L;
        }
        cglContext = 0L;
        eglContext = null;
        capabilities = null;
        profile = null;
        attempted = false;
    }

    /** Lets go of the context and its canvas, at the backend's stop. */
    void dispose() {
        if (capabilities == null) {
            return;
        }
        makeCurrent();
        if (canvas != null) {
            canvas.dispose();
            canvas = null;
        }
        if (cglContext != 0L) {
            CGL.CGLSetCurrentContext(0L);
            CGL.CGLDestroyContext(cglContext);
            cglContext = 0L;
        } else if (eglContext != null) {
            eglContext.dispose();
            eglContext = null;
        } else if (window != 0L) {
            GLFW.glfwMakeContextCurrent(0L);
            GLFW.glfwDestroyWindow(window);
            window = 0L;
        }
        capabilities = null;
    }
}

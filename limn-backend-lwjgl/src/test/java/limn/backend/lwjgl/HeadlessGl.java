package limn.backend.lwjgl;

import org.junit.jupiter.api.Assumptions;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.CGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.Locale;


/**
 * A real GL context for tests that have to measure what a GPU actually
 * produces, from a hidden GLFW window created once per JVM. Tests that need one
 * call {@link #assumeAvailable()} first and are skipped where no context can be
 * had: a contributor without a GPU, or a CI box with no display, still gets a
 * green build, and the assertions that need silicon simply do not run there.
 *
 * <p><b>On a Mac without a GPU the context is Apple's software renderer, made
 * through CGL.</b> GLFW's Cocoa path asks AppKit for an accelerated pixel format
 * and for nothing else (the reason {@link MacSoftwareGl} exists for an
 * application), so on a Mac whose only renderer is the software one, a virtual
 * machine's, {@code glfwCreateWindow} returns NULL, and every device test used to
 * skip there. The GPU path is tried first and is unchanged; only where it gives
 * no window does macOS fall back to a CGL context with no window at all, on the
 * software renderer by its id, as the probes' context is ({@link ProbeGl}). The
 * device tests then run, and a test that needs the GLFW window itself, rather
 * than a context, asks {@link #assumeWindow()}. Set
 * {@code -Dlimn.test.softwareGl=true} on a Mac that has a GPU to take that path
 * there: GLFW is then asked for a context no Mac has, gives no window, and the
 * fallback runs as it runs on a Mac without one, which is otherwise hardware
 * nobody develops on.
 *
 * <p><b>Never disable LWJGL's thread-0 check to get further.</b> On macOS
 * creating a window off the process's first thread does not fail, it aborts the
 * JVM with SIGABRT and takes the whole test run with it. That check is what
 * turns a misconfigured JVM into the exception this class catches, and the
 * Gradle test task passes {@code -XstartOnFirstThread} there so the real path
 * runs instead. An exception is not a missing window: it skips the tests, and
 * never falls back.
 *
 * <p>Holds no process-wide state of its own beyond the context: the UI runtime
 * the device paths check against is installed and removed per test, because
 * other classes in this module install one too and {@code Ui.install} refuses a
 * second, different runtime.
 */
final class HeadlessGl {

    /** Set to {@code true} to make GLFW give no window on a Mac, and so take the CGL fallback there. */
    static final String SOFTWARE_PROPERTY = "limn.test.softwareGl";
    /** {@code kCGLRendererGenericFloatID}, "Apple Software Renderer", from CGLRenderers.h. */
    private static final int RENDERER_APPLE_SOFTWARE = 0x00020400;

    private static boolean attempted;
    private static boolean available;
    private static String failure = "not attempted";
    private static long window;
    /** The CGL context the fallback made current, or 0 while the context is the window's. */
    private static long cglContext;
    private static Object capabilities;

    private HeadlessGl() {
    }

    /** Skips the calling test unless a context exists (and makes it current). */
    static void assumeAvailable() {
        Assumptions.assumeTrue(open(), () -> "no GL context available: " + failure);
    }

    /**
     * Skips the calling test unless the context is a GLFW window's: for a test that reads the window
     * itself, its native handle, its monitor or what GLFW says of its context, none of which the CGL
     * context of a Mac without a GPU has.
     */
    static void assumeWindow() {
        assumeAvailable();
        Assumptions.assumeTrue(window != 0L,
                () -> "the context is Apple's software renderer through CGL, with no window: " + failure);
    }

    /** @return the GLFW window backing the context; only valid after {@link #assumeWindow()} */
    static long window() {
        return window;
    }

    /** @return the context's capabilities; only valid after {@link #assumeAvailable()} */
    /** @return the context's capabilities, of the profile the process runs on ({@link Gl#api()}) */
    static Object capabilities() {
        return capabilities;
    }

    /**
     * Skips the calling test on the ES path: for an assertion that reads the device through a query
     * OpenGL ES 3.0 does not have ({@code glGetTexLevelParameteri}, {@code GL_SAMPLES_PASSED}),
     * where what the test proves about the backend is proven by the desktop run.
     */
    static void assumeDesktop(String why) {
        Assumptions.assumeFalse(isEs(), why);
    }

    /** Whether the context is OpenGL ES from ANGLE (ADR 003), which some tests have to know. */
    static boolean isEs() {
        return open() && Gl.api() != DesktopGl.INSTANCE;
    }

    /**
     * Whether the context is a software rasteriser rather than a GPU.
     *
     * <p>Asked by tests that hold the device to an exact number: what a rasteriser computes in
     * software is a legitimate GL implementation and a different precision, so a test that pins
     * arithmetic has to know which one it is talking to. This is CI's answer, always, the answer
     * on any machine whose driver fell back to Mesa, and the answer on a Mac without a GPU.
     *
     * <p>Matched on {@code GL_RENDERER} by the rule the canvas itself uses to choose its draw path
     * ({@link GraphicsProbe#isSoftwareRenderer}), so a test and the canvas never disagree about the
     * device. A device that rule misses is treated as a GPU, which fails loudly rather than
     * silently loosening an assertion.
     */
    static boolean isSoftware() {
        if (!open()) {
            return false;
        }
        return GraphicsProbe.isSoftwareRenderer(Gl.glGetString(GL33C.GL_RENDERER));
    }

    /** @return a short description of the device, for a failure message worth reading */
    static String describe() {
        return open()
                ? Gl.glGetString(GL33C.GL_RENDERER) + " / " + Gl.glGetString(GL33C.GL_VERSION)
                : "none (" + failure + ")";
    }

    /**
     * Runs {@code test} with a software renderer current, then makes this class's context current
     * again: for a test that holds the canvas to what it does on a renderer without a GPU, on a
     * machine that has one. Where this class's context is a software renderer already, it runs
     * there; on a Mac with a GPU, on Apple's software renderer through a CGL context of its own;
     * elsewhere, where no second context can be had without a window, the calling test is skipped.
     */
    static void onSoftwareRenderer(Runnable test) {
        assumeAvailable();
        if (isSoftware()) {
            test.run();
            return;
        }
        Assumptions.assumeTrue(macOs(), "a second, software context is made here only through CGL, on macOS");
        Assumptions.assumeFalse(isEs(), "a software CGL context is desktop OpenGL, and this process runs ES");
        long context = cglContext(true);
        Assumptions.assumeTrue(context != 0L, "no software pixel format through CGL");
        try {
            GL.createCapabilities();
            test.run();
        } finally {
            CGL.CGLSetCurrentContext(0L);
            CGL.CGLDestroyContext(context);
            makeCurrent();
        }
    }

    /**
     * Makes a CGL context with no window current on the calling thread: Apple's software renderer
     * by its id, or an accelerated renderer. The caller creates its capabilities, and destroys it.
     *
     * @return the context, or 0 where no pixel format or no context could be had
     */
    static long cglContext(boolean software) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = software
                    ? stack.ints(CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
                            CGL.kCGLPFARendererID, RENDERER_APPLE_SOFTWARE, 0)
                    : stack.ints(CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
                            CGL.kCGLPFAAccelerated, 0);
            PointerBuffer format = stack.mallocPointer(1);
            IntBuffer count = stack.mallocInt(1);
            if (CGL.CGLChoosePixelFormat(attributes, format, count) != 0 || format.get(0) == 0L) {
                return 0L;
            }
            PointerBuffer context = stack.mallocPointer(1);
            int error = CGL.CGLCreateContext(format.get(0), 0L, context);
            CGL.CGLDestroyPixelFormat(format.get(0));
            if (error != 0 || context.get(0) == 0L) {
                return 0L;
            }
            CGL.CGLSetCurrentContext(context.get(0));
            return context.get(0);
        }
    }

    /** Makes this class's context current again on the calling thread, with its capabilities. */
    private static void makeCurrent() {
        if (cglContext != 0L) {
            CGL.CGLSetCurrentContext(cglContext);
        } else {
            GLFW.glfwMakeContextCurrent(window);
        }
        Gl.api().setCapabilities(capabilities);
    }

    private static boolean macOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static synchronized boolean open() {
        if (attempted) {
            return available;
        }
        attempted = true;
        try {
            if (MacApplication.askedToBeAnAgent()) {
                // A build's process: no icon in the Dock for a window nobody is meant to see.
                GLFW.glfwInitHint(GLFW.GLFW_COCOA_MENUBAR, GLFW.GLFW_FALSE);
            }
            AngleFallback.initHints();
            if (!GLFW.glfwInit()) {
                return fallBack("glfwInit() returned false (no display?)");
            }
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            if (AngleFallback.forced()) {
                // -Dlimn.backend.angle=force: the device tests run on the ES path, as a machine
                // without an OpenGL driver runs them (ADR 003).
                return openAngle(null);
            }
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            if (macOs() && Boolean.getBoolean(SOFTWARE_PROPERTY)) {
                // Asked to fall back: a context no Mac has, so that GLFW gives no window here
                // either and the fallback below is the very one a Mac without a GPU takes.
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 9);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 9);
            }
            window = GLFW.glfwCreateWindow(16, 16, "limn video tests", 0L, 0L);
            if (window == 0L) {
                GraphicsProbe.Failure why = GraphicsProbe.lastFailure();
                if (AngleFallback.answers(limn.backend.Platform.current().os(), why.code())) {
                    // The machine's own OpenGL gave nothing, as on GitHub's GPU-less Windows
                    // runners: the tests go where an application would, to ANGLE.
                    return openAngle(why) || fallBack("glfwCreateWindow() returned NULL (" + why + "), and ANGLE "
                            + AngleFallback.failure());
                }
                return fallBack("glfwCreateWindow() returned NULL (no GL 3.3 core?)");
            }
            AngleFallback.nativeContextMade();
            GLFW.glfwMakeContextCurrent(window);
            capabilities = Gl.api().createCapabilities();
            // The window outlives every test on purpose: contexts are not
            // shared, so tearing it down between classes would mean recompiling
            // every program, and the process exits soon enough either way.
            available = true;
            return true;
        } catch (Throwable error) {
            failure = error.toString();
            return false;
        }
    }

    /** This class's context from ANGLE, OpenGL ES 3.0, as {@link AngleFallback} gives a window one. */
    private static boolean openAngle(GraphicsProbe.Failure why) {
        window = AngleFallback.createWindow(16, 16, "limn video tests", why);
        if (window == 0L) {
            failure = "ANGLE gave no window: " + AngleFallback.failure();
            return false;
        }
        GLFW.glfwMakeContextCurrent(window);
        capabilities = Gl.api().createCapabilities();
        if (((org.lwjgl.opengles.GLESCapabilities) capabilities).GL_KHR_debug) {
            // ANGLE validates OpenGL ES strictly and says what it refused and why; a test that
            // reads glGetError gets that sentence with the code (esErrors), which is what makes a
            // call valid on the desktop and not on ES findable rather than a bare 0x500.
            Gl.glEnable(org.lwjgl.opengles.KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS_KHR);
            Gl.glEnable(org.lwjgl.opengles.KHRDebug.GL_DEBUG_OUTPUT_KHR);
            debugCallback = org.lwjgl.opengles.GLDebugMessageKHRCallback.create(
                    (source, type, id, severity, length, message, user) -> {
                        if (type == org.lwjgl.opengles.KHRDebug.GL_DEBUG_TYPE_ERROR_KHR) {
                            ES_ERRORS.add(org.lwjgl.opengles.GLDebugMessageKHRCallback.getMessage(length, message));
                        }
                    });
            org.lwjgl.opengles.KHRDebug.glDebugMessageCallbackKHR(debugCallback, 0L);
        }
        available = true;
        return true;
    }

    private static final java.util.List<String> ES_ERRORS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static org.lwjgl.opengles.GLDebugMessageKHRCallback debugCallback;

    /** @return what ANGLE said it refused since the last call, oldest first; empty off the ES path */
    static java.util.List<String> takeEsErrors() {
        java.util.List<String> taken = java.util.List.copyOf(ES_ERRORS);
        ES_ERRORS.clear();
        return taken;
    }

    /** GLFW gave no context: on macOS, Apple's software renderer through CGL; elsewhere none. */
    private static boolean fallBack(String why) {
        failure = why;
        return macOs() && openSoftware(why);
    }

    /** Makes Apple's software renderer this class's context, for every test after, as the window is. */
    private static boolean openSoftware(String why) {
        long context = cglContext(true);
        if (context == 0L) {
            failure = why + "; and no software pixel format through CGL either";
            return false;
        }
        failure = why + "; Apple's software renderer through CGL instead";
        cglContext = context;
        capabilities = Gl.api().createCapabilities();
        available = true;
        return true;
    }

}

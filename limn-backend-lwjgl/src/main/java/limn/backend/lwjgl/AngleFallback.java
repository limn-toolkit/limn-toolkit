package limn.backend.lwjgl;

import limn.backend.Platform;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_ANGLE_PLATFORM_TYPE;
import static org.lwjgl.glfw.GLFW.GLFW_ANGLE_PLATFORM_TYPE_D3D11;
import static org.lwjgl.glfw.GLFW.GLFW_ANGLE_PLATFORM_TYPE_METAL;
import static org.lwjgl.glfw.GLFW.GLFW_API_UNAVAILABLE;
import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_EGL_CONTEXT_API;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_FORMAT_UNAVAILABLE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_ANY_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_ES_API;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_VERSION_UNAVAILABLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwInitHint;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The OpenGL ES 3.0 context a window takes from ANGLE when the machine's own OpenGL gives it none:
 * Direct3D 11 underneath on Windows, Metal on macOS. Desktop OpenGL stays the path of every
 * machine that has it; this is the step after it.
 *
 * <p>Which refusals lead here is decided per platform, from what each one's windowing path says
 * when it has nothing to give:
 * <ul>
 *   <li>Windows: {@code GLFW_API_UNAVAILABLE}, "the driver does not appear to support OpenGL",
 *       which is a machine with no OpenGL driver (measured on GitHub's GPU-less runners), and
 *       {@code GLFW_VERSION_UNAVAILABLE}, a driver older than 3.3. Not
 *       {@code GLFW_FORMAT_UNAVAILABLE}: a driver that exists and refused a pixel format is a
 *       request to look at, not a machine to route around.</li>
 *   <li>macOS: {@code GLFW_FORMAT_UNAVAILABLE}, no accelerated renderer (where
 *       {@link MacSoftwareGl} is the next step if this one fails), and
 *       {@code GLFW_API_UNAVAILABLE}, the day the system has no OpenGL at all.</li>
 * </ul>
 *
 * <p>One profile per process: the first window that takes this path switches
 * {@link Gl} to {@link GlesGl} and every window after it comes here directly, without spending a
 * failed native creation each time; a process that has made a desktop context never switches.
 * {@value #PROPERTY} is {@code auto} by default, {@code force} to take the path on a machine whose
 * own OpenGL works (so that it can be looked at somewhere other than where it is the only way
 * through), and {@code off} to refuse it.
 *
 * <p>UI thread only, like every window.
 */
final class AngleFallback {

    /** {@code auto}, {@code force} or {@code off}; see the class comment. */
    static final String PROPERTY = "limn.backend.angle";

    private static final System.Logger LOG = System.getLogger(AngleFallback.class.getName());

    private static boolean taken;
    private static boolean nativeContextMade;
    private static @Nullable String failure;

    private AngleFallback() {
    }

    /** The windowing library's ANGLE hint, which must be set before it is initialised. */
    static void initHints() {
        if (!AngleLibrary.platformHasFallback() || off()) {
            return;
        }
        glfwInitHint(GLFW_ANGLE_PLATFORM_TYPE, Platform.current().os() == Platform.Os.WINDOWS
                ? GLFW_ANGLE_PLATFORM_TYPE_D3D11 : GLFW_ANGLE_PLATFORM_TYPE_METAL);
    }

    /** Whether this process runs on ANGLE: every window from here on comes from {@link #createWindow}. */
    static boolean taken() {
        return taken;
    }

    /** Whether the property asks for ANGLE on a machine whose own OpenGL works. */
    static boolean forced() {
        return AngleLibrary.platformHasFallback() && !nativeContextMade
                && "force".equals(mode());
    }

    private static boolean off() {
        return "off".equals(mode());
    }

    private static String mode() {
        return System.getProperty(PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
    }

    /** Called once a window has a native desktop context, after which the process never switches. */
    static void nativeContextMade() {
        nativeContextMade = true;
    }

    /**
     * Whether a native creation that failed with {@code code} on {@code os} is one ANGLE answers.
     *
     * @param os   the platform, a parameter so that every branch can be tested anywhere
     * @param code the GLFW error code of the failed window creation
     */
    static boolean answers(Platform.Os os, int code) {
        if (off() || nativeContextMade) {
            return false;
        }
        return switch (os) {
            case WINDOWS -> code == GLFW_API_UNAVAILABLE || code == GLFW_VERSION_UNAVAILABLE;
            case MACOS -> code == GLFW_FORMAT_UNAVAILABLE || code == GLFW_API_UNAVAILABLE;
            default -> false;
        };
    }

    /**
     * Makes a window with an OpenGL ES 3.0 context from ANGLE, with every other hint the caller
     * already set left as it is. On the first success the process switches to {@link GlesGl},
     * before any capabilities exist.
     *
     * @param why what the native creation said, for the log; null when forced
     * @return the window, or {@code NULL} with {@link #failure()} saying why
     */
    static long createWindow(int width, int height, CharSequence title, GraphicsProbe.@Nullable Failure why) {
        if (!AngleLibrary.load()) {
            failure = AngleLibrary.failure();
            return NULL;
        }
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_ES_API);
        glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_ANY_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_FALSE);
        long window = glfwCreateWindow(width, height, title, NULL, NULL);
        if (window == NULL) {
            failure = GraphicsProbe.lastFailure().toString();
            return NULL;
        }
        if (!taken) {
            taken = true;
            Gl.use(GlesGl.INSTANCE);
            // Before the first window's programs are compiled, so that every later window finds them.
            EglProgramCache.install(org.lwjgl.glfw.GLFWNativeEGL.glfwGetEGLDisplay());
            LOG.log(System.Logger.Level.INFO, "OpenGL context: {0}, {1}; OpenGL ES 3.0, and taken by "
                    + "every window from here on; -D{2}=off refuses it, =force takes it anywhere",
                    describe(), why == null ? "requested" : "the system''s OpenGL gave none (" + why + ")",
                    PROPERTY);
        }
        return window;
    }

    /** @return why the last {@link #createWindow} gave no window */
    static @Nullable String failure() {
        return failure;
    }

    /** @return "ANGLE on Direct3D 11" or "ANGLE on Metal", and whether it was asked for */
    static String describe() {
        String under = Platform.current().os() == Platform.Os.WINDOWS ? "Direct3D 11" : "Metal";
        return "ANGLE on " + under + ("force".equals(mode()) ? " (forced)" : " (fallback)");
    }
}

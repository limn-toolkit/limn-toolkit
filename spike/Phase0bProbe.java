import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL10;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeEGL;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengles.EXTDisjointTimerQuery;
import org.lwjgl.opengles.GLES;
import org.lwjgl.opengles.GLES20;
import org.lwjgl.opengles.GLES30;
import org.lwjgl.opengles.GLESCapabilities;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * ADR 003's phase 0b on Direct3D 11: what ANGLE answers to the questions that decide whether
 * Limn's renderer is worth porting to OpenGL ES, before any of it is.
 *
 * <ol>
 * <li>The extensions three capability gates need, and whether what they promise works: a
 * half-float framebuffer, a 16-bit normalized texture, a timer query.</li>
 * <li>Buffer age, which partial rendering repaints by, over thirty swaps.</li>
 * <li>A transparent window over an opaque one, read back from the screen, twice: as GLFW makes
 * the surface, and as a DirectComposition surface made here, the way ANGLE composes alpha.</li>
 * <li>Swap intervals of two windows, each alone and interleaved.</li>
 * </ol>
 *
 * Every line it prints starts with {@code P0B}. A question that cannot be answered says why and
 * the probe goes on to the next one.
 */
public class Phase0bProbe {

    private static final int EGL_PLATFORM_ANGLE_ANGLE = 0x3202;
    private static final int EGL_DIRECT_COMPOSITION_ANGLE = 0x33A5;
    private static final int EGL_BUFFER_AGE_EXT = 0x313D;
    private static final int EGL_SWAP_BEHAVIOR = 0x3093;
    private static final int EGL_RENDERABLE_TYPE = 0x3040;
    private static final int EGL_OPENGL_ES3_BIT = 0x40;
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    private static final int GL_R16_EXT = 0x822A;
    private static final int GL_TIME_ELAPSED_EXT = 0x88BF;
    private static final int GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE = 0x8215;

    private static final int X = 100;
    private static final int Y = 100;
    private static final int SIZE = 300;

    public static void main(String[] args) throws Exception {
        String dir = System.getProperty("angle.dir");
        for (String name : new String[] {"z.dll", "libGLESv2.dll", "libEGL.dll"}) {
            System.load(Path.of(dir, name).toAbsolutePath().toString());
        }
        Configuration.EGL_LIBRARY_NAME.set(Path.of(dir, "libEGL.dll").toString());
        Configuration.OPENGLES_LIBRARY_NAME.set(Path.of(dir, "libGLESv2.dll").toString());
        GLFW.glfwSetErrorCallback((code, description) ->
                say("GLFW error 0x" + Integer.toHexString(code) + ": " + MemoryUtil.memUTF8(description)));
        GLFW.glfwInitHint(GLFW.GLFW_ANGLE_PLATFORM_TYPE, GLFW.GLFW_ANGLE_PLATFORM_TYPE_D3D11);
        if (!GLFW.glfwInit()) {
            say("GLFW did not start");
            System.exit(1);
        }
        long info = esWindow("info", false, false);
        long display = GLFWNativeEGL.glfwGetEGLDisplay();
        say("EGL display platform ANGLE D3D11 (EGL_PLATFORM_ANGLE_ANGLE 0x" + Integer.toHexString(EGL_PLATFORM_ANGLE_ANGLE) + ")");
        say("EGL vendor   " + EGL10.eglQueryString(display, EGL10.EGL_VENDOR));
        say("EGL version  " + EGL10.eglQueryString(display, EGL10.EGL_VERSION));
        Set<String> eglExtensions = new TreeSet<>(List.of(EGL10.eglQueryString(display, EGL10.EGL_EXTENSIONS).trim().split("\\s+")));
        for (String wanted : new String[] {"EGL_EXT_buffer_age", "EGL_ANGLE_direct_composition",
                "EGL_KHR_swap_buffers_with_damage", "EGL_EXT_swap_buffers_with_damage", "EGL_KHR_partial_update",
                "EGL_ANGLE_flexible_surface_compatibility"}) {
            say("EGL ext " + wanted + " = " + eglExtensions.contains(wanted));
        }

        GLFW.glfwMakeContextCurrent(info);
        GLESCapabilities caps = GLES.createCapabilities();
        say("GL renderer " + GLES20.glGetString(GLES20.GL_RENDERER));
        say("GL version  " + GLES20.glGetString(GLES20.GL_VERSION));
        extensions();
        halfFloatFramebuffer();
        norm16Texture();
        timerQuery(caps);
        GLFW.glfwDestroyWindow(info);

        bufferAge(display);
        transparency(display);
        swapIntervals();
        GLFW.glfwTerminate();
        say("done");
    }

    // 1. Extensions, and whether each does what its gate needs.

    private static void extensions() {
        Set<String> gl = new TreeSet<>();
        int count = GLES20.glGetInteger(GLES30.GL_NUM_EXTENSIONS);
        for (int i = 0; i < count; i++) {
            gl.add(GLES30.glGetStringi(GLES20.GL_EXTENSIONS, i));
        }
        say("GL extensions " + count);
        for (String wanted : new String[] {"GL_EXT_color_buffer_float", "GL_EXT_color_buffer_half_float",
                "GL_EXT_texture_norm16", "GL_EXT_disjoint_timer_query", "GL_OES_texture_float_linear",
                "GL_EXT_texture_format_BGRA8888", "GL_ANGLE_pack_reverse_row_order", "GL_KHR_debug"}) {
            say("GL ext " + wanted + " = " + gl.contains(wanted));
        }
    }

    private static void halfFloatFramebuffer() {
        int texture = GLES20.glGenTextures();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 64, 64, 0,
                GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, (ByteBuffer) null);
        int fbo = GLES20.glGenFramebuffers();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        int samples = GLES20.glGetInteger(GLES30.GL_MAX_SAMPLES);
        int rb = GLES20.glGenRenderbuffers();
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, rb);
        GLES30.glRenderbufferStorageMultisample(GLES20.GL_RENDERBUFFER, Math.min(4, samples), GLES30.GL_RGBA16F, 64, 64);
        int msaaError = GLES20.glGetError();
        say("RGBA16F framebuffer status 0x" + Integer.toHexString(status)
                + (status == GLES20.GL_FRAMEBUFFER_COMPLETE ? " (complete)" : " (NOT complete)")
                + "; MSAA RGBA16F renderbuffer error 0x" + Integer.toHexString(msaaError) + "; max samples " + samples);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(fbo);
        GLES20.glDeleteRenderbuffers(rb);
        GLES20.glDeleteTextures(texture);
    }

    private static void norm16Texture() {
        GLES20.glGetError();
        int texture = GLES20.glGenTextures();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GL_R16_EXT, 16, 16, 0,
                GLES30.GL_RED, GLES20.GL_UNSIGNED_SHORT, (ByteBuffer) null);
        int error = GLES20.glGetError();
        say("R16 texture error 0x" + Integer.toHexString(error) + (error == 0 ? " (accepted)" : " (refused)"));
        GLES20.glDeleteTextures(texture);
    }

    private static void timerQuery(GLESCapabilities caps) {
        if (!caps.GL_EXT_disjoint_timer_query) {
            say("timer query: no GL_EXT_disjoint_timer_query");
            return;
        }
        int query = EXTDisjointTimerQuery.glGenQueriesEXT();
        EXTDisjointTimerQuery.glBeginQueryEXT(GL_TIME_ELAPSED_EXT, query);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        EXTDisjointTimerQuery.glEndQueryEXT(GL_TIME_ELAPSED_EXT);
        GLES20.glFinish();
        long[] nanos = new long[1];
        EXTDisjointTimerQuery.glGetQueryObjectui64vEXT(query, EXTDisjointTimerQuery.GL_QUERY_RESULT_EXT, nanos);
        say("timer query: " + nanos[0] + " ns for one clear, error 0x" + Integer.toHexString(GLES20.glGetError()));
        EXTDisjointTimerQuery.glDeleteQueriesEXT(query);
    }

    // 2. Buffer age over thirty swaps of a visible window.

    private static void bufferAge(long display) {
        long window = esWindow("buffer age", true, false);
        GLFW.glfwSetWindowPos(window, 500, 100);
        GLFW.glfwMakeContextCurrent(window);
        GLES.createCapabilities();
        long surface = GLFWNativeEGL.glfwGetEGLSurface(window);
        int[] value = new int[1];
        EGL10.eglQuerySurface(display, surface, EGL_SWAP_BEHAVIOR, value);
        say("swap behaviour 0x" + Integer.toHexString(value[0]) + (value[0] == 0x3094 ? " (preserved)" : value[0] == 0x3095 ? " (destroyed)" : ""));
        StringBuilder ages = new StringBuilder();
        for (int frame = 0; frame < 30; frame++) {
            GLFW.glfwPollEvents();
            boolean ok = EGL10.eglQuerySurface(display, surface, EGL_BUFFER_AGE_EXT, value);
            ages.append(ok ? Integer.toString(value[0]) : "err0x" + Integer.toHexString(EGL10.eglGetError())).append(' ');
            GLES20.glClearColor(frame % 2, 0.2f, 0.4f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLFW.glfwSwapBuffers(window);
        }
        say("buffer age per frame: " + ages.toString().trim());
        GLFW.glfwDestroyWindow(window);
    }

    // 3. A transparent window over an opaque red one, read back from the screen.

    private static void transparency(long display) throws Exception {
        long red = esWindow("red", true, false);
        GLFW.glfwSetWindowPos(red, X, Y);
        GLFW.glfwMakeContextCurrent(red);
        GLES.createCapabilities();
        GLES20.glClearColor(1f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLFW.glfwSwapBuffers(red);

        long glass = esWindow("glass", true, true);
        GLFW.glfwSetWindowPos(glass, X, Y);
        say("GLFW window attribute TRANSPARENT_FRAMEBUFFER = "
                + GLFW.glfwGetWindowAttrib(glass, GLFW.GLFW_TRANSPARENT_FRAMEBUFFER));
        GLFW.glfwMakeContextCurrent(glass);
        GLES.createCapabilities();
        say("glass default framebuffer alpha bits = " + GLES30.glGetFramebufferAttachmentParameteri(
                GLES20.GL_FRAMEBUFFER, GLES30.GL_BACK, GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE));
        for (int frame = 0; frame < 5; frame++) {
            drawGlassPattern();
            if (frame == 4) {
                say("glass read back from its own framebuffer: corner " + readPixel(10, 10) + ", centre " + readPixel(150, 150));
            }
            GLFW.glfwSwapBuffers(glass);
            refresh(red);
            GLFW.glfwPollEvents();
            Thread.sleep(100);
        }
        Thread.sleep(700);
        capture("glfw", display);
        GLFW.glfwDestroyWindow(glass);

        directComposition(display, red);
        GLFW.glfwDestroyWindow(red);
    }

    /** The same pattern on a surface made here with EGL_DIRECT_COMPOSITION_ANGLE. */
    private static void directComposition(long display, long red) throws Exception {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "glass (DirectComposition)", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            say("DirectComposition: no window");
            return;
        }
        GLFW.glfwSetWindowPos(window, X, Y);
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer configAttributes = stack.ints(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE);
            PointerBuffer configs = stack.mallocPointer(1);
            IntBuffer count = stack.mallocInt(1);
            if (!EGL10.eglChooseConfig(display, configAttributes, configs, count) || count.get(0) == 0) {
                say("DirectComposition: no RGBA8 ES3 config, error 0x" + Integer.toHexString(EGL10.eglGetError()));
                GLFW.glfwDestroyWindow(window);
                return;
            }
            long config = configs.get(0);
            long surface = EGL10.eglCreateWindowSurface(display, config, hwnd,
                    stack.ints(EGL_DIRECT_COMPOSITION_ANGLE, EGL10.EGL_TRUE, EGL10.EGL_NONE));
            if (surface == EGL10.EGL_NO_SURFACE) {
                say("DirectComposition: surface refused, error 0x" + Integer.toHexString(EGL10.eglGetError()));
                GLFW.glfwDestroyWindow(window);
                return;
            }
            long context = EGL10.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                    stack.ints(EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE));
            EGL10.eglMakeCurrent(display, surface, surface, context);
            GLES.createCapabilities();
            say("DirectComposition: surface made; alpha bits = " + GLES30.glGetFramebufferAttachmentParameteri(
                    GLES20.GL_FRAMEBUFFER, GLES30.GL_BACK, GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE));
            for (int frame = 0; frame < 5; frame++) {
                drawGlassPattern();
                EGL10.eglSwapBuffers(display, surface);
                GLFW.glfwPollEvents();
                Thread.sleep(100);
            }
            Thread.sleep(700);
            capture("directcomposition", display);
            EGL10.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            EGL10.eglDestroySurface(display, surface);
            EGL10.eglDestroyContext(display, context);
        }
        GLFW.glfwDestroyWindow(window);
    }

    /**
     * Transparent everywhere, an opaque green square in the centre, and a premultiplied
     * half-transparent blue band along the bottom.
     */
    private static void drawGlassPattern() {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(100, 100, 100, 100);
        GLES20.glClearColor(0f, 1f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glScissor(0, 0, SIZE, 40);
        GLES20.glClearColor(0f, 0f, 0.5f, 0.5f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    private static void refresh(long red) {
        long current = GLFW.glfwGetCurrentContext();
        GLFW.glfwMakeContextCurrent(red);
        GLES.createCapabilities();
        GLES20.glClearColor(1f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLFW.glfwSwapBuffers(red);
        GLFW.glfwMakeContextCurrent(current);
        GLES.createCapabilities();
    }

    private static String readPixel(int x, int y) {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        String s = "rgba(" + (pixel.get(0) & 255) + "," + (pixel.get(1) & 255) + "," + (pixel.get(2) & 255) + "," + (pixel.get(3) & 255) + ")";
        MemoryUtil.memFree(pixel);
        return s;
    }

    /** Reads the screen where the windows are: red at a corner means the glass let it through. */
    private static void capture(String label, long display) throws Exception {
        BufferedImage shot = new Robot().createScreenCapture(new Rectangle(X, Y, SIZE, SIZE));
        ImageIO.write(shot, "png", new File("results/transparency-" + label + ".png"));
        String corner = rgb(shot.getRGB(20, 20));
        String centre = rgb(shot.getRGB(150, 150));
        String band = rgb(shot.getRGB(150, SIZE - 20));
        boolean through = shot.getRGB(20, 20) == 0xFFFF0000 || ((shot.getRGB(20, 20) >> 16) & 255) > 200 && ((shot.getRGB(20, 20) >> 8) & 255) < 60;
        say("transparency " + label + ": corner " + corner + ", centre " + centre + ", band " + band
                + " -> " + (through ? "the red window shows through (transparent)" : "the red window does NOT show through"));
    }

    private static String rgb(int argb) {
        return "rgb(" + ((argb >> 16) & 255) + "," + ((argb >> 8) & 255) + "," + (argb & 255) + ")";
    }

    // 4. Swap intervals of two windows.

    private static void swapIntervals() {
        long a = esWindow("interval 1", true, false);
        long b = esWindow("interval 0", true, false);
        GLFW.glfwSetWindowPos(a, 100, 450);
        GLFW.glfwSetWindowPos(b, 450, 450);
        GLFW.glfwMakeContextCurrent(a);
        GLES.createCapabilities();
        GLFW.glfwSwapInterval(1);
        GLFW.glfwMakeContextCurrent(b);
        GLES.createCapabilities();
        GLFW.glfwSwapInterval(0);
        say("monitor refresh " + GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor()).refreshRate() + " Hz");
        say("interval 1 alone: " + rate(List.of(a), 120) + " swaps/s");
        say("interval 0 alone: " + rate(List.of(b), 600) + " swaps/s");
        say("interleaved (1 then 0 per round): " + rate(List.of(a, b), 120) + " rounds/s");
        GLFW.glfwDestroyWindow(a);
        GLFW.glfwDestroyWindow(b);
    }

    private static String rate(List<Long> windows, int rounds) {
        for (int warm = 0; warm < 10; warm++) {
            round(windows);
        }
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            round(windows);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        return String.format("%.1f", rounds / seconds);
    }

    private static void round(List<Long> windows) {
        for (long window : windows) {
            GLFW.glfwMakeContextCurrent(window);
            GLES20.glClearColor(0.2f, 0.3f, 0.4f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLFW.glfwSwapBuffers(window);
        }
        GLFW.glfwPollEvents();
    }

    private static long esWindow(String title, boolean visible, boolean transparent) {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, visible ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_ES_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        if (transparent) {
            GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
        }
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, title, MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            say("no window for " + title);
            System.exit(1);
        }
        return window;
    }

    private static void say(String line) {
        System.out.println("P0B " + line);
    }
}

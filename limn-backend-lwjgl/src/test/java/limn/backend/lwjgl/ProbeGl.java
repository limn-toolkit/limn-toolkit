package limn.backend.lwjgl;

import org.junit.jupiter.api.Assumptions;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.CGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.Locale;

/**
 * The GL context a probe measures on: on macOS a CGL context with no window, on this machine's
 * GPU or, with {@code LIMN_PROBE_RENDERER=software}, on Apple's software renderer by its id, as the
 * probes of the layer records were first run; anywhere else the hidden window the device tests
 * use, on whatever driver the system gives it, so that the same probe runs on the Windows and
 * Linux guests. There the software renderer is the system's to choose (Mesa's by
 * {@code LIBGL_ALWAYS_SOFTWARE=1} on Linux), and the variable is ignored.
 *
 * <p>A probe reads its variables in the test's own process, which the build configures once and
 * keeps in its configuration cache: run a probe with {@code --rerun --no-configuration-cache}, or a
 * variable set since the last configuration never reaches it (seen 2026-10-18, a software run that
 * measured the GPU).
 */
final class ProbeGl {

    private ProbeGl() {
    }

    /** @return whether the probe asks for the software renderer */
    static boolean software() {
        return "software".equals(System.getenv("LIMN_PROBE_RENDERER"));
    }

    /**
     * Makes a context current for the probe, or skips it when none can be had.
     *
     * @param software on macOS, Apple's software renderer rather than the GPU
     * @return what {@link #close} takes back: a CGL context, or 0 for the hidden window's
     */
    static long open(boolean software) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            HeadlessGl.assumeAvailable();
            return 0L;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = software
                    ? stack.ints(CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
                            CGL.kCGLPFARendererID, 0x00020400, 0)
                    : stack.ints(CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
                            CGL.kCGLPFAAccelerated, 0);
            PointerBuffer format = stack.mallocPointer(1);
            IntBuffer count = stack.mallocInt(1);
            int error = CGL.CGLChoosePixelFormat(attributes, format, count);
            Assumptions.assumeTrue(error == 0 && format.get(0) != 0L, "no pixel format: " + error);
            PointerBuffer context = stack.mallocPointer(1);
            error = CGL.CGLCreateContext(format.get(0), 0L, context);
            CGL.CGLDestroyPixelFormat(format.get(0));
            Assumptions.assumeTrue(error == 0, "no context: " + error);
            CGL.CGLSetCurrentContext(context.get(0));
            GL.createCapabilities();
            return context.get(0);
        }
    }

    /** @param context what {@link #open} answered */
    static void close(long context) {
        if (context != 0L) {
            CGL.CGLSetCurrentContext(0L);
            CGL.CGLDestroyContext(context);
        }
    }
}

package limn.backend.lwjgl;

import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * The macOS-only <em>fallback</em> GL context: an {@code NSOpenGLContext} built by hand, for the
 * machines where GLFW cannot build one at all.
 *
 * <p><b>Why it exists.</b> GLFW's Cocoa/NSGL path puts {@code NSOpenGLPFAAccelerated} in every
 * pixel format it asks AppKit for. On a Mac with a GPU that is free; in a virtual machine whose
 * only OpenGL renderer is {@code Apple Software Renderer} it is fatal, because that renderer
 * reports {@code kCGLRPAccelerated = 0} and so matches no accelerated format. AppKit then returns
 * a nil pixel format, {@code glfwCreateWindow} fails with {@code GLFW_FORMAT_UNAVAILABLE}
 * ("NSGL: Failed to find a suitable pixel format"), and no Limn window ever opens. Ask for the
 * <em>same</em> format minus that one attribute and the request succeeds, giving a live
 * {@code 4.1 APPLE-23.1.1} context — enough for the GL 3.3 core the renderer targets. That
 * attribution was measured, not read: with only attribute 73 deleted the request succeeds, and
 * with only the other candidate ({@code NSOpenGLPFASampleBuffers}) deleted it still fails.
 *
 * <p><b>What it does not do.</b> It never replaces GLFW's context. {@link LwjglWindow} asks GLFW
 * for a normal accelerated context first, exactly as before, on every platform including macOS;
 * only the specific refusal above (or {@link #PROPERTY}, to exercise the path deliberately) brings
 * this class into the picture. GLFW still makes the {@code NSWindow}, its view, and every input,
 * resize and close callback: what changes hands is the context, and with it three calls that
 * {@code GLFW_NO_API} leaves unimplemented — make-current, swap, and the swap interval.
 *
 * <p>Reached by {@code objc_msgSend} rather than a binding because LWJGL wraps no part of NSGL;
 * the idiom is the one {@code LwjglWindow.setAboveSystemChrome} already uses. Every constant here
 * was read out of the SDK's own {@code AppKit.framework/Headers/NSOpenGL.h}. All of it is UI
 * thread only, and nothing here may be touched off macOS: the class initializer resolves libobjc.
 */
final class MacSoftwareGl {

    /**
     * Set {@code -Dlimn.backend.macSoftwareGl=true} to take this path on any Mac, whether or not
     * GLFW could have built a context. The path is otherwise unreachable on hardware that works,
     * which would make it code nobody can look at until it is the only thing standing between a
     * user and a window.
     */
    static final String PROPERTY = "limn.backend.macSoftwareGl";

    // ---- NSOpenGL.h, verbatim ------------------------------------------------------------------
    private static final int PFA_DOUBLE_BUFFER = 5;
    private static final int PFA_COLOR_SIZE = 8;
    private static final int PFA_ALPHA_SIZE = 11;
    private static final int PFA_DEPTH_SIZE = 12;
    private static final int PFA_STENCIL_SIZE = 13;
    private static final int PFA_CLOSEST_POLICY = 74;
    private static final int PFA_OPENGL_PROFILE = 99;
    /** The newest profile NSGL names. It is what yields the 4.1 context; there is no "3.3". */
    private static final int PROFILE_3_2_CORE = 0x3200;
    private static final int CP_SWAP_INTERVAL = 222;
    private static final int CP_SURFACE_OPACITY = 236;

    private static final long OBJC_MSG_SEND =
            ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

    private MacSoftwareGl() {
    }

    /** @return whether {@link #PROPERTY} asks for this path regardless of what GLFW could do */
    static boolean forced() {
        return Boolean.getBoolean(PROPERTY);
    }

    /**
     * Builds a context for a window created with {@code GLFW_NO_API} and attaches it to that
     * window's view, leaving it <em>not</em> current.
     *
     * @param window      the GLFW window, which must have no client API of its own
     * @param transparent whether the framebuffer's alpha should composite over what is behind,
     *                    which is a context parameter here and a window hint everywhere else
     * @return the {@code NSOpenGLContext}, or {@link org.lwjgl.system.MemoryUtil#NULL} if even the
     *         unaccelerated format has no match — a machine with no usable OpenGL at all
     */
    static long createContext(long window, boolean transparent) {
        long view = GLFWNativeCocoa.glfwGetCocoaView(window);
        if (view == NULL) {
            return NULL;
        }
        long pixelFormat;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // GLFW's own request, minus NSOpenGLPFAAccelerated. The array is not flat: the
            // attributes above take a following value word, the two below stand alone, and a
            // zero ends it.
            IntBuffer attributes = stack.ints(
                    PFA_OPENGL_PROFILE, PROFILE_3_2_CORE,
                    PFA_COLOR_SIZE, 24,
                    PFA_ALPHA_SIZE, 8,
                    PFA_DEPTH_SIZE, 24,
                    PFA_STENCIL_SIZE, 8,
                    PFA_DOUBLE_BUFFER,
                    PFA_CLOSEST_POLICY,
                    0);
            pixelFormat = msg(msg(cls("NSOpenGLPixelFormat"), "alloc"),
                    "initWithAttributes:", memAddress(attributes));
        }
        if (pixelFormat == NULL) {
            return NULL;
        }
        long context = msg(msg(cls("NSOpenGLContext"), "alloc"),
                "initWithFormat:shareContext:", pixelFormat, NULL);
        // -[NSOpenGLContext pixelFormat] is a strong property, so the context owns it from here.
        msgV(pixelFormat, "release");
        if (context == NULL) {
            return NULL;
        }
        if (transparent) {
            setParameter(context, CP_SURFACE_OPACITY, 0);
        }
        msgV(context, "setView:", view);
        return context;
    }

    /** Makes {@code context} current on the calling thread — the {@code glfwMakeContextCurrent}. */
    static void makeCurrent(long context) {
        msgV(context, "makeCurrentContext");
    }

    /** Presents the back buffer — the {@code glfwSwapBuffers}, which does nothing on a NO_API window. */
    static void swapBuffers(long context) {
        msgV(context, "flushBuffer");
    }

    /** Sets vsync — the {@code glfwSwapInterval}; 1 syncs to vertical retrace, 0 runs free. */
    static void setSwapInterval(long context, int interval) {
        setParameter(context, CP_SWAP_INTERVAL, interval);
    }

    /**
     * Tells the context its view's geometry moved. GLFW does this for the contexts it owns; on
     * this path the drawable would otherwise keep the size it was created at, and every frame
     * after a resize would land on a stale backing store.
     */
    static void update(long context) {
        msgV(context, "update");
    }

    /** Detaches the context from its view and releases it. Call before the window is destroyed. */
    static void dispose(long context) {
        msgV(cls("NSOpenGLContext"), "clearCurrentContext");
        msgV(context, "clearDrawable");
        msgV(context, "release");
    }

    private static void setParameter(long context, int parameter, int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            msgV(context, "setValues:forParameter:", memAddress(stack.ints(value)), parameter);
        }
    }

    // ---- objc_msgSend, typed by the shape of the message ---------------------------------------

    private static long cls(String name) {
        return ObjCRuntime.objc_getClass(name);
    }

    private static long sel(String name) {
        return ObjCRuntime.sel_getUid(name);
    }

    private static long msg(long self, String selector) {
        return JNI.invokePPP(self, sel(selector), OBJC_MSG_SEND);
    }

    private static long msg(long self, String selector, long a) {
        return JNI.invokePPPP(self, sel(selector), a, OBJC_MSG_SEND);
    }

    private static long msg(long self, String selector, long a, long b) {
        return JNI.invokePPPPP(self, sel(selector), a, b, OBJC_MSG_SEND);
    }

    private static void msgV(long self, String selector) {
        JNI.invokePPV(self, sel(selector), OBJC_MSG_SEND);
    }

    private static void msgV(long self, String selector, long a) {
        JNI.invokePPPV(self, sel(selector), a, OBJC_MSG_SEND);
    }

    private static void msgV(long self, String selector, long a, long b) {
        JNI.invokePPPPV(self, sel(selector), a, b, OBJC_MSG_SEND);
    }
}

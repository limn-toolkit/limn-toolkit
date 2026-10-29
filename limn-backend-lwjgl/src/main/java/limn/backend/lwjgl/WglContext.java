package limn.backend.lwjgl;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.GDI32;
import org.lwjgl.system.windows.PIXELFORMATDESCRIPTOR;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WinBase;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * An OpenGL 3.3 core context the backend makes itself on a window GLFW made with no context
 * ({@code GLFW_NO_API}), on Windows (ADR 082 §1.1).
 *
 * <p><b>Why not GLFW's.</b> A context lost with the device does not come back while the driver's
 * OpenGL library stays loaded, and unloading it moves {@code opengl32.dll}: every pointer into it
 * kept from an earlier load then calls into nothing. GLFW keeps such pointers for the contexts it
 * makes and calls them when a window is destroyed, as does LWJGL's {@code WGL} class. So every WGL
 * entry point this class calls is looked up again from the library as it is loaded now
 * ({@link Entries}), and none is kept across a reload.
 *
 * <p>What GLFW did for the backend's contexts is done here: a pixel format chosen through
 * {@code WGL_ARB_pixel_format} (with alpha, depth and stencil, fully accelerated), the context made
 * through {@code WGL_ARB_create_context} (3.3 core, forward-compatible), both found through a
 * throw-away context on a throw-away window, since a window takes one pixel format for its life; and
 * the swap interval through {@code WGL_EXT_swap_control}. A transparent window's compositing is
 * GLFW's still: it sets it up on the window whatever the context.
 *
 * <p>All of it is UI thread only, and Windows only.
 */
final class WglContext implements WindowContext {

    private static final System.Logger LOG = System.getLogger(WglContext.class.getName());

    // ---- wingdi.h and WGL_ARB_*, verbatim -------------------------------------------------------
    private static final int PFD_DRAW_TO_WINDOW = 0x4;
    private static final int PFD_SUPPORT_OPENGL = 0x20;
    private static final int PFD_DOUBLEBUFFER = 0x1;
    private static final int WGL_DRAW_TO_WINDOW_ARB = 0x2001;
    private static final int WGL_ACCELERATION_ARB = 0x2003;
    private static final int WGL_SUPPORT_OPENGL_ARB = 0x2010;
    private static final int WGL_DOUBLE_BUFFER_ARB = 0x2011;
    private static final int WGL_PIXEL_TYPE_ARB = 0x2013;
    private static final int WGL_COLOR_BITS_ARB = 0x2014;
    private static final int WGL_ALPHA_BITS_ARB = 0x201B;
    private static final int WGL_DEPTH_BITS_ARB = 0x2022;
    private static final int WGL_STENCIL_BITS_ARB = 0x2023;
    private static final int WGL_FULL_ACCELERATION_ARB = 0x2027;
    private static final int WGL_TYPE_RGBA_ARB = 0x202B;
    private static final int WGL_CONTEXT_MAJOR_VERSION_ARB = 0x2091;
    private static final int WGL_CONTEXT_MINOR_VERSION_ARB = 0x2092;
    private static final int WGL_CONTEXT_FLAGS_ARB = 0x2094;
    private static final int WGL_CONTEXT_FORWARD_COMPATIBLE_BIT_ARB = 0x2;
    private static final int WGL_CONTEXT_PROFILE_MASK_ARB = 0x9126;
    private static final int WGL_CONTEXT_CORE_PROFILE_BIT_ARB = 0x1;

    /**
     * The WGL entry points of {@code opengl32.dll} as it is loaded now, and the extension functions
     * of the driver behind it. Replaced whenever the library's address changes, which is what an
     * unload and a reload do.
     */
    private static final class Entries {
        final long module;
        final long createContext;
        final long makeCurrent;
        final long deleteContext;
        final long getProcAddress;
        long choosePixelFormat;
        long createContextAttribs;
        long swapInterval;
        boolean extensionsLooked;

        Entries(long module) {
            this.module = module;
            createContext = WinBase.GetProcAddress(null, module, "wglCreateContext");
            makeCurrent = WinBase.GetProcAddress(null, module, "wglMakeCurrent");
            deleteContext = WinBase.GetProcAddress(null, module, "wglDeleteContext");
            getProcAddress = WinBase.GetProcAddress(null, module, "wglGetProcAddress");
        }

        /** An extension function of the driver whose context is current, or NULL. */
        long extension(String name) {
            ByteBuffer ascii = MemoryUtil.memASCII(name);
            try {
                long address = JNI.invokePP(MemoryUtil.memAddress(ascii), getProcAddress);
                // Some drivers answer 1, 2, 3 or -1 for "no such function" rather than NULL.
                return address == 1 || address == 2 || address == 3 || address == -1 ? NULL : address;
            } finally {
                MemoryUtil.memFree(ascii);
            }
        }
    }

    private static @Nullable Entries entries;

    /**
     * @return the entry points of {@code opengl32.dll} as loaded now, loading it through LWJGL's
     *         OpenGL binding first if it is not; the backend's capabilities are made from that
     *         binding, so it has to be the same load
     */
    private static Entries entries() {
        Gl.api().loadBinding();
        long module = WinBase.GetModuleHandle(null, "opengl32.dll");
        Entries current = entries;
        if (current == null || current.module != module) {
            current = new Entries(module);
            entries = current;
        }
        if (!current.extensionsLooked) {
            current.extensionsLooked = true;
            lookUpExtensions(current);
        }
        return current;
    }

    /**
     * Finds the pixel-format, context and swap-control extensions through a throw-away context on a
     * throw-away window: they are only reachable with a context current, and a context needs a pixel
     * format, which a window takes once for its life, so the real window cannot be the one.
     */
    private static void lookUpExtensions(Entries wgl) {
        long instance = WinBase.GetModuleHandle(null, (CharSequence) null);
        long helper = User32.CreateWindowEx(null, 0, "STATIC", "", User32.WS_POPUP, 0, 0, 1, 1,
                NULL, NULL, instance, NULL);
        if (helper == NULL) {
            return;
        }
        long dc = User32.GetDC(helper);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PIXELFORMATDESCRIPTOR pfd = legacyDescriptor(stack);
            int format = GDI32.ChoosePixelFormat(null, dc, pfd);
            if (format == 0 || !GDI32.SetPixelFormat(null, dc, format, pfd)) {
                return;
            }
            long dummy = JNI.invokePP(dc, wgl.createContext);
            if (dummy == NULL) {
                return;
            }
            JNI.invokePPI(dc, dummy, wgl.makeCurrent);
            wgl.choosePixelFormat = wgl.extension("wglChoosePixelFormatARB");
            wgl.createContextAttribs = wgl.extension("wglCreateContextAttribsARB");
            wgl.swapInterval = wgl.extension("wglSwapIntervalEXT");
            JNI.invokePPI(NULL, NULL, wgl.makeCurrent);
            JNI.invokePI(dummy, wgl.deleteContext);
        } finally {
            User32.ReleaseDC(helper, dc);
            User32.DestroyWindow(null, helper);
        }
    }

    private static PIXELFORMATDESCRIPTOR legacyDescriptor(MemoryStack stack) {
        return PIXELFORMATDESCRIPTOR.calloc(stack)
                .nSize((short) PIXELFORMATDESCRIPTOR.SIZEOF)
                .nVersion((short) 1)
                .dwFlags(PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER)
                .iPixelType((byte) 0)
                .cColorBits((byte) 24)
                .cAlphaBits((byte) 8)
                .cDepthBits((byte) 24)
                .cStencilBits((byte) 8);
    }

    private static GraphicsProbe.@Nullable Failure lastFailure;

    /** @return why the last {@link #create} returned null, as GLFW would have reported it */
    static GraphicsProbe.@Nullable Failure lastFailure() {
        return lastFailure;
    }

    /**
     * Takes {@code opengl32.dll} and the driver's OpenGL library out of the process, for a context
     * lost with the device (ADR 082 §1.4, step 1): while the driver's library stays loaded no new
     * context presents, and it does stay, alone, after {@code opengl32.dll} has gone, measured on
     * 2026-10-29. Call it only after every context is deleted and LWJGL's binding destroyed.
     *
     * <p>The driver's library is found by what it exports rather than by name: every installable
     * client driver exports {@code DrvCreateContext}, and nothing else in a process does.
     *
     * @return what was unloaded, for the log
     */
    static String unloadDriver() {
        entries = null;
        StringBuilder unloaded = new StringBuilder();
        long opengl32 = WinBase.GetModuleHandle(null, "opengl32.dll");
        if (opengl32 != NULL && freeCompletely(opengl32)) {
            unloaded.append("opengl32.dll");
        }
        for (long module : loadedModules()) {
            if (WinBase.GetProcAddress(null, module, "DrvCreateContext") != NULL && freeCompletely(module)) {
                unloaded.append(unloaded.length() == 0 ? "" : ", ").append("the driver's library at 0x")
                        .append(Long.toHexString(module));
            }
        }
        return unloaded.length() == 0 ? "nothing" : unloaded.toString();
    }

    /**
     * Puts the window class's own procedure back on {@code window} if something else is there, after
     * {@link #unloadDriver}: with every procedure of the backend's own taken off first, anything
     * left above the class's would be the unloaded runtime's, and the next message would call into
     * nothing.
     *
     * @return whether a procedure had to be put back
     */
    static boolean restoreClassProcedure(long window) {
        long user32 = WinBase.GetModuleHandle(null, "user32.dll");
        long getWindowLong = WinBase.GetProcAddress(null, user32, "GetWindowLongPtrW");
        long getClassLong = WinBase.GetProcAddress(null, user32, "GetClassLongPtrW");
        long setWindowLong = WinBase.GetProcAddress(null, user32, "SetWindowLongPtrW");
        long current = JNI.invokePPP(window, -4L, getWindowLong);    // GWLP_WNDPROC
        long classProcedure = JNI.invokePPP(window, -24L, getClassLong); // GCLP_WNDPROC
        if (current == classProcedure || classProcedure == NULL) {
            return false;
        }
        JNI.invokePPPP(window, -4L, classProcedure, setWindowLong);
        return true;
    }

    /** Frees {@code module} until the process no longer has it, at most as many times as it could hold. */
    private static boolean freeCompletely(long module) {
        for (int i = 0; i < 64; i++) {
            if (!WinBase.FreeLibrary(null, module)) {
                break;
            }
            if (!contains(loadedModules(), module)) {
                return true;
            }
        }
        return !contains(loadedModules(), module);
    }

    private static boolean contains(long[] modules, long module) {
        for (long candidate : modules) {
            if (candidate == module) {
                return true;
            }
        }
        return false;
    }

    /** Every module of the process, through kernel32's {@code K32EnumProcessModules}. */
    private static long[] loadedModules() {
        long kernel32 = WinBase.GetModuleHandle(null, "kernel32.dll");
        long enumerate = WinBase.GetProcAddress(null, kernel32, "K32EnumProcessModules");
        if (enumerate == NULL) {
            return new long[0];
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int capacity = 1024;
            java.nio.LongBuffer modules = stack.mallocLong(capacity);
            IntBuffer needed = stack.mallocInt(1);
            long currentProcess = -1L; // GetCurrentProcess()'s pseudo-handle
            if (JNI.invokePPPI(currentProcess, MemoryUtil.memAddress(modules), capacity * Long.BYTES,
                    MemoryUtil.memAddress(needed), enumerate) == 0) {
                return new long[0];
            }
            long[] found = new long[Math.min(capacity, needed.get(0) / Long.BYTES)];
            modules.get(found);
            return found;
        }
    }

    private final long surface;
    private final long dc;
    private final long context;

    private WglContext(long surface, long dc, long context) {
        this.surface = surface;
        this.dc = dc;
        this.context = context;
    }

    /**
     * Makes a 3.3 core context on {@code surface}, which must have no pixel format yet, and leaves
     * it <em>not</em> current.
     *
     * @param surface the window to draw on: a GLFW window's, or a child surface of one
     * @return the context, or null with {@link #lastFailure()} saying why, in GLFW's terms so that
     *         the ANGLE fallback answers it as it answers GLFW's refusals
     */
    static @Nullable WglContext create(long surface) {
        Entries wgl = entries();
        long dc = User32.GetDC(surface);
        if (dc == NULL) {
            lastFailure = new GraphicsProbe.Failure(org.lwjgl.glfw.GLFW.GLFW_PLATFORM_ERROR, "WGL: the window has no device context");
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int format = chooseFormat(wgl, dc, stack);
            PIXELFORMATDESCRIPTOR pfd = PIXELFORMATDESCRIPTOR.calloc(stack);
            if (format == 0
                    || GDI32.DescribePixelFormat(null, dc, format, PIXELFORMATDESCRIPTOR.SIZEOF, pfd) == 0
                    || !GDI32.SetPixelFormat(null, dc, format, pfd)) {
                User32.ReleaseDC(surface, dc);
                lastFailure = new GraphicsProbe.Failure(org.lwjgl.glfw.GLFW.GLFW_API_UNAVAILABLE,
                        "WGL: the driver offers no accelerated pixel format with OpenGL");
                return null;
            }
            if (wgl.createContextAttribs == NULL) {
                User32.ReleaseDC(surface, dc);
                lastFailure = new GraphicsProbe.Failure(org.lwjgl.glfw.GLFW.GLFW_VERSION_UNAVAILABLE,
                        "WGL: WGL_ARB_create_context is unavailable, so no OpenGL 3.3 core context");
                return null;
            }
            IntBuffer attributes = stack.ints(
                    WGL_CONTEXT_MAJOR_VERSION_ARB, 3,
                    WGL_CONTEXT_MINOR_VERSION_ARB, 3,
                    WGL_CONTEXT_PROFILE_MASK_ARB, WGL_CONTEXT_CORE_PROFILE_BIT_ARB,
                    WGL_CONTEXT_FLAGS_ARB, WGL_CONTEXT_FORWARD_COMPATIBLE_BIT_ARB,
                    0);
            long context = JNI.invokePPPP(dc, NULL, MemoryUtil.memAddress(attributes), wgl.createContextAttribs);
            if (context == NULL) {
                User32.ReleaseDC(surface, dc);
                lastFailure = new GraphicsProbe.Failure(org.lwjgl.glfw.GLFW.GLFW_VERSION_UNAVAILABLE,
                        "WGL: the driver does not support OpenGL 3.3 core");
                return null;
            }
            lastFailure = null;
            return new WglContext(surface, dc, context);
        }
    }

    /** A fully accelerated RGBA format with alpha, depth and stencil, or a legacy match without the extension. */
    private static int chooseFormat(Entries wgl, long dc, MemoryStack stack) {
        if (wgl.choosePixelFormat != NULL) {
            IntBuffer attributes = stack.ints(
                    WGL_DRAW_TO_WINDOW_ARB, 1,
                    WGL_SUPPORT_OPENGL_ARB, 1,
                    WGL_DOUBLE_BUFFER_ARB, 1,
                    WGL_PIXEL_TYPE_ARB, WGL_TYPE_RGBA_ARB,
                    WGL_ACCELERATION_ARB, WGL_FULL_ACCELERATION_ARB,
                    WGL_COLOR_BITS_ARB, 24,
                    WGL_ALPHA_BITS_ARB, 8,
                    WGL_DEPTH_BITS_ARB, 24,
                    WGL_STENCIL_BITS_ARB, 8,
                    0);
            IntBuffer format = stack.mallocInt(1);
            IntBuffer count = stack.mallocInt(1);
            int chosen = JNI.invokePPPPPI(dc, MemoryUtil.memAddress(attributes), NULL, 1,
                    MemoryUtil.memAddress(format), MemoryUtil.memAddress(count), wgl.choosePixelFormat);
            if (chosen != 0 && count.get(0) > 0) {
                return format.get(0);
            }
            LOG.log(System.Logger.Level.DEBUG, "wglChoosePixelFormatARB matched nothing; asking GDI");
        }
        return GDI32.ChoosePixelFormat(null, dc, legacyDescriptor(stack));
    }

    /** @return the window this context draws on: the GLFW window's, or a child surface of it */
    long surface() {
        return surface;
    }

    @Override
    public void makeCurrent() {
        JNI.invokePPI(dc, context, entries().makeCurrent);
    }

    @Override
    public void swapBuffers() {
        GDI32.SwapBuffers(null, dc);
    }

    @Override
    public void setSwapInterval(int interval) {
        long swapInterval = entries().swapInterval;
        if (swapInterval != NULL) {
            JNI.invokeI(interval, swapInterval);
        }
    }

    @Override
    public void dispose() {
        Entries wgl = entries();
        JNI.invokePPI(NULL, NULL, wgl.makeCurrent);
        JNI.invokePI(context, wgl.deleteContext);
        User32.ReleaseDC(surface, dc);
    }
}

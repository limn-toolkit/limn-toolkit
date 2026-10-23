package limn.backend.lwjgl;

import limn.backend.Platform;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL10;
import org.lwjgl.egl.EGL11;
import org.lwjgl.egl.EGL12;
import org.lwjgl.glfw.GLFWNativeEGL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The zero-copy video path on the OpenGL ES profile, where {@link IoSurfaces} cannot go: a
 * decoder's IOSurface plane made a texture's storage through ANGLE's
 * {@code EGL_ANGLE_iosurface_client_buffer}, as {@code CGLTexImageIOSurface2D} makes it one on a
 * desktop context.
 *
 * <p>The binding is a pbuffer made from the IOSurface plane and bound with {@code eglBindTexImage}
 * to whichever 2D texture is bound, so the texture samples the decoder's own memory. The target is
 * {@code GL_TEXTURE_2D}, which is what ANGLE on Metal accepts (it has no rectangle target there),
 * so the conversion is {@code video_convert.frag}, the uploaded path's own program; P010's
 * left-justified codes need nothing new, because its sample scale is already the format's.
 * Measured on 2026-10-22 on ANGLE's Metal backend: NV12 and P010 sampled through this binding read
 * back exactly the codes written, and codes rewritten in the IOSurface after the binding read back
 * through the same binding, which is the proof that nothing was copied.
 *
 * <p>A pbuffer lives until the next picture is bound or the surface goes, and is released then.
 */
final class EglIoSurfaces {

    // EGL_ANGLE_iosurface_client_buffer and EGL 1.1's texture binding, which LWJGL's EGL classes
    // carry only in part.
    private static final int EGL_IOSURFACE_ANGLE = 0x3454;
    private static final int EGL_IOSURFACE_PLANE_ANGLE = 0x345A;
    private static final int EGL_TEXTURE_TYPE_ANGLE = 0x345C;
    private static final int EGL_TEXTURE_INTERNAL_FORMAT_ANGLE = 0x345D;
    private static final int EGL_BIND_TO_TEXTURE_TARGET_ANGLE = 0x348D;
    private static final int EGL_TEXTURE_FORMAT = 0x3080;
    private static final int EGL_TEXTURE_TARGET = 0x3081;
    private static final int EGL_TEXTURE_RGBA = 0x305E;
    private static final int EGL_TEXTURE_2D = 0x305F;
    private static final int EGL_BACK_BUFFER = 0x3084;

    private static long configDisplay = NULL;
    private static long config = NULL;

    private EglIoSurfaces() {
    }

    /** @return whether the ES profile's display can bind an IOSurface; macOS and ANGLE only */
    static boolean isAvailable() {
        return unavailableReason() == null;
    }

    /** @return why {@link #isAvailable()} is false, or null when it is true */
    static @Nullable String unavailableReason() {
        if (Platform.current().os() != Platform.Os.MACOS) {
            return "IOSurfaces exist on macOS only";
        }
        long display = GLFWNativeEGL.glfwGetEGLDisplay();
        if (display == NULL) {
            return "no EGL display";
        }
        if (!BufferAgeQuery.hasExtension(EGL10.eglQueryString(display, EGL10.EGL_EXTENSIONS),
                "EGL_ANGLE_iosurface_client_buffer")) {
            return "this EGL display has no EGL_ANGLE_iosurface_client_buffer";
        }
        return config(display) == NULL ? "no EGL config binds a pbuffer to a 2D texture" : null;
    }

    /**
     * Points the 2D texture bound to the active unit at one plane of {@code ioSurface}.
     *
     * @param glFormat {@code GL_RED} or {@code GL_RG}: one component or two
     * @param glType   {@code GL_UNSIGNED_BYTE} or {@code GL_UNSIGNED_SHORT}: 8 bits or 16
     * @return the pbuffer the binding lives in, for {@link #release}
     * @throws IllegalStateException with EGL's error if the plane cannot be bound
     */
    static long bindPlane(int glFormat, int glType, int width, int height, long ioSurface, int plane) {
        long display = GLFWNativeEGL.glfwGetEGLDisplay();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = stack.ints(
                    EGL10.EGL_WIDTH, width, EGL10.EGL_HEIGHT, height,
                    EGL_IOSURFACE_PLANE_ANGLE, plane,
                    EGL_TEXTURE_TARGET, EGL_TEXTURE_2D,
                    EGL_TEXTURE_INTERNAL_FORMAT_ANGLE, glFormat,
                    EGL_TEXTURE_TYPE_ANGLE, glType,
                    EGL_TEXTURE_FORMAT, EGL_TEXTURE_RGBA,
                    EGL10.EGL_NONE);
            long pbuffer = EGL12.eglCreatePbufferFromClientBuffer(display, EGL_IOSURFACE_ANGLE, ioSurface,
                    config(display), attributes);
            if (pbuffer == NULL) {
                throw new IllegalStateException("EGL refused a pbuffer for plane " + plane
                        + " of the IOSurface: 0x" + Integer.toHexString(EGL10.eglGetError()));
            }
            if (!EGL11.eglBindTexImage(display, pbuffer, EGL_BACK_BUFFER)) {
                int error = EGL10.eglGetError();
                EGL10.eglDestroySurface(display, pbuffer);
                throw new IllegalStateException("EGL refused to bind plane " + plane
                        + " of the IOSurface to a texture: 0x" + Integer.toHexString(error));
            }
            return pbuffer;
        }
    }

    /** Lets go of a plane bound by {@link #bindPlane}; the texture keeps its name and loses the memory. */
    static void release(long pbuffer) {
        long display = GLFWNativeEGL.glfwGetEGLDisplay();
        EGL11.eglReleaseTexImage(display, pbuffer, EGL_BACK_BUFFER);
        EGL10.eglDestroySurface(display, pbuffer);
    }

    /** A config that binds a pbuffer to a 2D texture, chosen once per display. */
    private static long config(long display) {
        if (display == configDisplay) {
            return config;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = stack.ints(
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL_BIND_TO_TEXTURE_TARGET_ANGLE, EGL_TEXTURE_2D,
                    EGL10.EGL_NONE);
            PointerBuffer configs = stack.mallocPointer(1);
            IntBuffer count = stack.ints(0);
            boolean chosen = EGL10.eglChooseConfig(display, attributes, configs, count) && count.get(0) > 0;
            configDisplay = display;
            config = chosen ? configs.get(0) : NULL;
            return config;
        }
    }
}

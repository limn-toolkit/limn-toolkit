package limn.backend.lwjgl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.DynamicLinkLoader;

import java.nio.ByteBuffer;

/**
 * Binds a macOS {@code IOSurface} to a GL texture, which is the whole of the zero-copy video path
 * on this platform: a VideoToolbox decode leaves its picture in an IOSurface, and
 * {@code CGLTexImageIOSurface2D} makes a texture <em>be</em> that memory instead of a copy of it.
 *
 * <p><b>It is reached by symbol and not by binding, and that is deliberate.</b> The call lives in
 * {@code /System/Library/Frameworks/OpenGL.framework}, which LWJGL loads anyway, but LWJGL exposes
 * no wrapper for it and its signature (nine arguments, mixed pointers and integers) has no
 * matching {@code JNI.invoke…} overload in LWJGL 3.4.1, in either the base class or the Java-25
 * multi-release variant. What can express it is {@code libffi}, which LWJGL bundles: a call
 * interface is prepared once and invoked per plane. Still {@code org.lwjgl}, still inside the one
 * module allowed to see it.
 *
 * <p><b>It forces {@link #GL_TEXTURE_RECTANGLE}.</b> Passing {@code GL_TEXTURE_2D} returns
 * {@code CGLError 10008}, "invalid numerical value", with no GL error and no texture. That is
 * measured, not assumed. Rectangle textures address texels by integer and have no mip chain, which
 * suits a conversion that already fetches by integer texel; what they cost is a second fragment
 * program, because a rectangle sampler is spelled differently and does not exist in GL ES at all.
 *
 * <p>Everything here is <b>UI thread only, with a GL context current</b>. The call interfaces and
 * their argument storage are allocated once and reused per call rather than per picture, so two
 * threads binding at once would write each other's arguments; nothing on this path is ever off the
 * UI thread, and {@link GlVideoSurface} is where that is enforced.
 *
 * <p>{@link #isAvailable()} answers false on any platform but macOS and on any failure to resolve
 * the symbols, and never throws; a machine without it decodes in software and uploads bytes, which
 * is the path that was there before this one.
 */
final class IoSurfaces {

    /** The only texture target {@code CGLTexImageIOSurface2D} accepts. Not in {@code GL33C}. */
    static final int GL_TEXTURE_RECTANGLE = 0x84F5;

    /** Its own limit, and not {@code GL_MAX_TEXTURE_SIZE}: the two differ on real devices. */
    static final int GL_MAX_RECTANGLE_TEXTURE_SIZE = 0x84F8;

    private static final String OPENGL_FRAMEWORK =
            "/System/Library/Frameworks/OpenGL.framework/OpenGL";

    private static final boolean AVAILABLE;
    private static final String UNAVAILABLE_REASON;

    private static long texImageFunction;
    private static long errorStringFunction;
    private static long currentContextFunction;

    private static FFICIF texImageCif;
    private static FFICIF currentContextCif;
    private static FFICIF errorStringCif;
    private static PointerBuffer texImageValues;
    private static ByteBuffer texImageArguments;
    private static ByteBuffer errorStringArgument;
    private static PointerBuffer errorStringValues;
    private static PointerBuffer noArguments;
    private static ByteBuffer result;

    /**
     * The argument-type lists, held for the life of the process because {@code ffi_prep_cif} stores
     * a <em>pointer</em> to them inside the call interface rather than copying them. They are off
     * the Java heap, so nothing would collect them, but dropping the reference would leave the one
     * fact that makes the calls valid recorded nowhere, and freeing them would leave the CIF
     * pointing at memory that had gone.
     */
    private static PointerBuffer texImageTypes;
    private static PointerBuffer errorStringTypes;

    static {
        String problem = null;
        if (!System.getProperty("os.name", "").startsWith("Mac")) {
            problem = "IOSurface is a macOS interface";
        } else {
            try {
                problem = resolve();
            } catch (Throwable failure) {
                // Any failure at all is an unavailable path and never a broken window: the caller
                // falls back to uploading bytes, which is what every other platform does.
                problem = failure.toString();
            }
        }
        AVAILABLE = problem == null;
        UNAVAILABLE_REASON = problem;
    }

    private IoSurfaces() {
    }

    /**
     * Whether a picture backed by an IOSurface can be bound in this process.
     * Safe from any thread and safe on a frame: it reads a {@code static final}
     * decided once in the class initializer, so it needs no asynchronous form.
     *
     * <p>That one-time initialization is a {@code dlopen} of a framework the GL
     * backend has already loaded, which is a reference-count bump and a symbol
     * lookup rather than a load from disk, plus three {@code libffi} call
     * interfaces prepared in memory. It never throws: every failure becomes a
     * {@code false} here and a sentence in {@link #unavailableReason}.
     */
    static boolean isAvailable() {
        return AVAILABLE;
    }

    /** @return why {@link #isAvailable()} is false, for a message; null when it is true */
    static String unavailableReason() {
        return UNAVAILABLE_REASON;
    }

    /**
     * Points the texture currently bound to {@link #GL_TEXTURE_RECTANGLE} at one plane of
     * {@code ioSurface}. No storage is allocated and nothing is copied: afterwards that texture
     * reads the decoder's memory directly, for exactly as long as that memory holds this picture.
     *
     * <p><b>{@code ioSurface} is trusted and is not validated.</b> A number that is not a live
     * IOSurface is dereferenced inside the framework and takes the process with it. That was
     * measured, in {@code IOSurfaceGetID}, from a test that tried to assert a refusal that does not
     * exist. So the handle must come from the producer that decoded the picture, through a frame
     * that is still held, and never from an application: {@link limn.video.VideoFrame#handle()}
     * throwing on a released frame is the check, and there is no second one here.
     *
     * @param internalFormat what the plane's samples are: {@code R8}/{@code RG8} for NV12,
     *                       {@code R16}/{@code RG16} for P010
     * @param plane          the IOSurface plane index, 0 for luma
     * @return 0 on success, otherwise a CGL error code {@link #errorText} can name
     * @throws IllegalStateException if {@link #isAvailable()} is false, or no CGL context is current
     */
    static int bindPlane(int internalFormat, int width, int height, int glFormat, int glType,
                         long ioSurface, int plane) {
        if (!AVAILABLE) {
            throw new IllegalStateException(
                    "CGLTexImageIOSurface2D is not reachable here: " + UNAVAILABLE_REASON);
        }
        long context = callNoArguments(currentContextCif, currentContextFunction);
        if (context == 0L) {
            throw new IllegalStateException(
                    "no CGL context is current, so an IOSurface cannot be bound to a texture");
        }
        // Order and widths are CGLTexImageIOSurface2D's own: (context, target, internalFormat,
        // width, height, format, type, ioSurface, plane). Each is written as a long into storage
        // allocated once, and libffi narrows it per the type list prepared once above. The writing
        // goes one field at a time rather than through an array, because this runs per plane per
        // picture and the video path allocates nothing there.
        texImageArguments.putLong(0, context);
        texImageArguments.putLong(8, GL_TEXTURE_RECTANGLE);
        texImageArguments.putLong(16, internalFormat);
        texImageArguments.putLong(24, width);
        texImageArguments.putLong(32, height);
        texImageArguments.putLong(40, glFormat);
        texImageArguments.putLong(48, glType);
        texImageArguments.putLong(56, ioSurface);
        texImageArguments.putLong(64, plane);
        LibFFI.ffi_call(texImageCif, texImageFunction, result, texImageValues);
        return (int) result.getLong(0);
    }

    /** @return {@code CGLErrorString}'s words for {@code code}, or the number when it has none */
    static String errorText(int code) {
        if (!AVAILABLE || code == 0) {
            return "CGLError " + code;
        }
        errorStringArgument.putLong(0, code);
        LibFFI.ffi_call(errorStringCif, errorStringFunction, result, errorStringValues);
        long text = result.getLong(0);
        return text == 0L ? "CGLError " + code
                : "CGLError " + code + " (" + MemoryUtil.memUTF8(text) + ")";
    }

    /** @return null when everything resolved, or the sentence saying what did not */
    private static String resolve() {
        long framework = DynamicLinkLoader.dlopen(OPENGL_FRAMEWORK, DynamicLinkLoader.RTLD_LAZY);
        if (framework == 0L) {
            return "cannot dlopen " + OPENGL_FRAMEWORK;
        }
        texImageFunction = DynamicLinkLoader.dlsym(framework, "CGLTexImageIOSurface2D");
        errorStringFunction = DynamicLinkLoader.dlsym(framework, "CGLErrorString");
        currentContextFunction = DynamicLinkLoader.dlsym(framework, "CGLGetCurrentContext");
        if (texImageFunction == 0L || currentContextFunction == 0L) {
            return "OpenGL.framework has no CGLTexImageIOSurface2D";
        }

        FFIType[] texImageArgumentTypes = {
            LibFFI.ffi_type_pointer,  // CGLContextObj
            LibFFI.ffi_type_uint32,   // target
            LibFFI.ffi_type_sint32,   // internal format
            LibFFI.ffi_type_sint32,   // width
            LibFFI.ffi_type_sint32,   // height
            LibFFI.ffi_type_uint32,   // format
            LibFFI.ffi_type_uint32,   // type
            LibFFI.ffi_type_pointer,  // IOSurfaceRef
            LibFFI.ffi_type_uint32,   // plane
        };
        texImageCif = FFICIF.malloc();
        texImageArguments = MemoryUtil.memAlloc(texImageArgumentTypes.length * 8);
        texImageValues = MemoryUtil.memAllocPointer(texImageArgumentTypes.length);
        texImageTypes = MemoryUtil.memAllocPointer(texImageArgumentTypes.length);
        for (int index = 0; index < texImageArgumentTypes.length; index++) {
            texImageTypes.put(index, texImageArgumentTypes[index].address());
            texImageValues.put(index, MemoryUtil.memAddress(texImageArguments) + index * 8L);
        }
        if (LibFFI.ffi_prep_cif(texImageCif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_sint32,
                texImageTypes) != LibFFI.FFI_OK) {
            return "libffi cannot describe CGLTexImageIOSurface2D";
        }

        currentContextCif = FFICIF.malloc();
        noArguments = MemoryUtil.memAllocPointer(1);
        noArguments.limit(0);
        if (LibFFI.ffi_prep_cif(currentContextCif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer,
                noArguments) != LibFFI.FFI_OK) {
            return "libffi cannot describe CGLGetCurrentContext";
        }

        errorStringCif = FFICIF.malloc();
        errorStringArgument = MemoryUtil.memAlloc(8);
        errorStringValues = MemoryUtil.memAllocPointer(1);
        errorStringValues.put(0, MemoryUtil.memAddress(errorStringArgument));
        errorStringTypes = MemoryUtil.memAllocPointer(1);
        errorStringTypes.put(0, LibFFI.ffi_type_sint32.address());
        if (LibFFI.ffi_prep_cif(errorStringCif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_pointer,
                errorStringTypes) != LibFFI.FFI_OK) {
            return "libffi cannot describe CGLErrorString";
        }

        result = MemoryUtil.memAlloc(8);
        return null;
    }

    private static long callNoArguments(FFICIF cif, long function) {
        LibFFI.ffi_call(cif, function, result, noArguments);
        return result.getLong(0);
    }
}

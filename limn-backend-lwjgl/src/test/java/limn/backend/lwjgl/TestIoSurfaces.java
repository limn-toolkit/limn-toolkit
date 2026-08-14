package limn.backend.lwjgl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.CoreFoundation;
import org.lwjgl.system.macosx.DynamicLinkLoader;

import java.nio.ByteBuffer;

/**
 * Makes an IOSurface-backed picture the way VideoToolbox would, so the zero-copy path can be tested
 * without a hardware decoder, without a media file and without FFmpeg.
 *
 * <p>That separation is the point rather than a convenience. Nothing in this repository can encode
 * H.264 or HEVC (ADR 015 §0), so there is no clip to hand a hardware decoder and no round trip to
 * run through one: evidence for the decoder side is linkage and real files, and neither says
 * anything about whether the binding, the rectangle sampler, the P010 normalisation or the release
 * discipline are right. Those are testable, and this is what makes them testable: a CVPixelBuffer
 * whose samples this side wrote, in exactly the two layouts VideoToolbox produces.
 *
 * <p>CoreVideo is reached by {@code dlsym} and {@code libffi} for {@link IoSurfaces}' reason, with
 * none of its care about allocation: this runs a handful of times in a test and allocates its call
 * interface per call.
 *
 * <p>macOS only. Every entry point returns 0 or false elsewhere rather than throwing, so a caller
 * skips the way the GL-backed tests skip.
 */
final class TestIoSurfaces {

    /** {@code kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange}: VideoToolbox's 8-bit output. */
    static final int NV12 = 0x34323076;  // '420v'

    /** {@code kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange}: its 10-bit output, P010. */
    static final int P010 = 0x78343230;  // 'x420'

    private static final String CORE_VIDEO =
            "/System/Library/Frameworks/CoreVideo.framework/CoreVideo";
    private static final String CORE_FOUNDATION =
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    private static long coreVideo;
    private static long coreFoundation;

    private TestIoSurfaces() {
    }

    /** @return whether a pixel buffer can be created here at all */
    static boolean isAvailable() {
        if (!System.getProperty("os.name", "").startsWith("Mac")) {
            return false;
        }
        return frameworks() && IoSurfaces.isAvailable();
    }

    /**
     * One IOSurface-backed pixel buffer whose planes hold {@code luma}, {@code cb} and {@code cr}
     * as a hardware decoder would lay them out: interleaved chroma, and for P010 the code in the
     * <b>top</b> ten bits of its word, which is the layout the shift under test exists for.
     *
     * <p>The caller owns it and must {@link #release} it.
     *
     * @param pixelFormatType {@link #NV12} or {@link #P010}
     * @return a handle carrying both the CVPixelBuffer and the IOSurface it is backed by
     */
    static Surface create(int pixelFormatType, int width, int height,
                          int[] luma, int[] cb, int[] cr) {
        long attributes = ioSurfaceAttributes();
        ByteBuffer out = MemoryUtil.memAlloc(8);
        long buffer;
        try {
            int status = (int) call(symbol(coreVideo, "CVPixelBufferCreate"), LibFFI.ffi_type_sint32,
                    new FFIType[] {LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint64,
                        LibFFI.ffi_type_uint64, LibFFI.ffi_type_uint32,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer},
                    new long[] {0L, width, height, pixelFormatType, attributes,
                        MemoryUtil.memAddress(out)});
            buffer = out.getLong(0);
            if (status != 0 || buffer == 0L) {
                throw new IllegalStateException("CVPixelBufferCreate failed: " + status);
            }
        } finally {
            MemoryUtil.memFree(out);
            CoreFoundation.CFRelease(attributes);
        }
        long surface = call(symbol(coreVideo, "CVPixelBufferGetIOSurface"), LibFFI.ffi_type_pointer,
                new FFIType[] {LibFFI.ffi_type_pointer}, new long[] {buffer});
        if (surface == 0L) {
            release(new Surface(buffer, 0L, width, height, pixelFormatType));
            throw new IllegalStateException(
                    "the pixel buffer has no IOSurface backing; the attributes did not take");
        }
        Surface handle = new Surface(buffer, surface, width, height, pixelFormatType);
        write(handle, luma, cb, cr);
        return handle;
    }

    /**
     * Overwrites {@code surface}'s samples in place: what a decoder does to a buffer that has been
     * handed back to its pool, and therefore the only way to ask whether a picture already bound
     * was actually read before that happened.
     */
    static void write(Surface surface, int[] luma, int[] cb, int[] cr) {
        lock(surface.pixelBuffer, false);
        try {
            boolean deep = surface.pixelFormatType == P010;
            for (int plane = 0; plane < 2; plane++) {
                long base = call(symbol(coreVideo, "CVPixelBufferGetBaseAddressOfPlane"),
                        LibFFI.ffi_type_pointer,
                        new FFIType[] {LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint64},
                        new long[] {surface.pixelBuffer, plane});
                int stride = (int) call(symbol(coreVideo, "CVPixelBufferGetBytesPerRowOfPlane"),
                        LibFFI.ffi_type_uint64,
                        new FFIType[] {LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint64},
                        new long[] {surface.pixelBuffer, plane});
                int columns = plane == 0 ? surface.width : (surface.width + 1) / 2;
                int rows = plane == 0 ? surface.height : (surface.height + 1) / 2;
                ByteBuffer memory = MemoryUtil.memByteBuffer(base, stride * rows);
                for (int row = 0; row < rows; row++) {
                    for (int column = 0; column < columns; column++) {
                        int sample = row * columns + column;
                        if (plane == 0) {
                            put(memory, row * stride + column * (deep ? 2 : 1), luma[sample], deep);
                        } else {
                            int at = row * stride + column * (deep ? 4 : 2);
                            put(memory, at, cb[sample], deep);
                            put(memory, at + (deep ? 2 : 1), cr[sample], deep);
                        }
                    }
                }
            }
        } finally {
            unlock(surface.pixelBuffer, false);
        }
    }

    /** Releases the pixel buffer. The IOSurface goes with it. */
    static void release(Surface surface) {
        if (surface != null && surface.pixelBuffer != 0L) {
            call(symbol(coreVideo, "CVPixelBufferRelease"), LibFFI.ffi_type_void,
                    new FFIType[] {LibFFI.ffi_type_pointer}, new long[] {surface.pixelBuffer});
        }
    }

    /** A pixel buffer and the IOSurface inside it: the handle a decoded hardware picture is. */
    record Surface(long pixelBuffer, long ioSurface, int width, int height, int pixelFormatType) {
    }

    /**
     * The ten-bit code goes in the TOP ten bits, spelled here rather than taken from
     * {@link limn.video.PixelFormat#putComponent}: this stands in for the hardware decoder, and a
     * producer that wrote its input through the layout under test would only prove the layout
     * agrees with itself.
     */
    private static void put(ByteBuffer memory, int at, int code, boolean deep) {
        if (deep) {
            memory.putShort(at, (short) (code << 6));
        } else {
            memory.put(at, (byte) code);
        }
    }

    private static void lock(long buffer, boolean readOnly) {
        call(symbol(coreVideo, "CVPixelBufferLockBaseAddress"), LibFFI.ffi_type_sint32,
                new FFIType[] {LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint64},
                new long[] {buffer, readOnly ? 1L : 0L});
    }

    private static void unlock(long buffer, boolean readOnly) {
        call(symbol(coreVideo, "CVPixelBufferUnlockBaseAddress"), LibFFI.ffi_type_sint32,
                new FFIType[] {LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint64},
                new long[] {buffer, readOnly ? 1L : 0L});
    }

    /** {@code { kCVPixelBufferIOSurfacePropertiesKey: {} }}, what makes the buffer IOSurface-backed. */
    private static long ioSurfaceAttributes() {
        long dictCreate = symbol(coreFoundation, "CFDictionaryCreateMutable");
        long dictSet = symbol(coreFoundation, "CFDictionarySetValue");
        long keyCallbacks = symbol(coreFoundation, "kCFTypeDictionaryKeyCallBacks");
        long valueCallbacks = symbol(coreFoundation, "kCFTypeDictionaryValueCallBacks");
        FFIType[] createTypes = {LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint64,
            LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer};
        long outer = call(dictCreate, LibFFI.ffi_type_pointer, createTypes,
                new long[] {0L, 0L, keyCallbacks, valueCallbacks});
        long inner = call(dictCreate, LibFFI.ffi_type_pointer, createTypes,
                new long[] {0L, 0L, keyCallbacks, valueCallbacks});
        ByteBuffer keyBytes = MemoryUtil.memUTF8("IOSurfaceProperties");
        long key = CoreFoundation.CFStringCreateWithCString(
                0L, keyBytes, CoreFoundation.kCFStringEncodingUTF8);
        call(dictSet, LibFFI.ffi_type_void,
                new FFIType[] {LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                    LibFFI.ffi_type_pointer},
                new long[] {outer, key, inner});
        CoreFoundation.CFRelease(key);
        CoreFoundation.CFRelease(inner);
        MemoryUtil.memFree(keyBytes);
        return outer;
    }

    private static boolean frameworks() {
        if (coreVideo == 0L) {
            coreVideo = DynamicLinkLoader.dlopen(CORE_VIDEO, DynamicLinkLoader.RTLD_LAZY);
        }
        if (coreFoundation == 0L) {
            coreFoundation = DynamicLinkLoader.dlopen(CORE_FOUNDATION, DynamicLinkLoader.RTLD_LAZY);
        }
        return coreVideo != 0L && coreFoundation != 0L;
    }

    private static long symbol(long library, String name) {
        long address = DynamicLinkLoader.dlsym(library, name);
        if (address == 0L) {
            throw new IllegalStateException("no such symbol: " + name);
        }
        return address;
    }

    /** One libffi call: every argument widened to a long, the return read back as one. */
    private static long call(long function, FFIType returnType, FFIType[] argTypes, long[] args) {
        FFICIF cif = FFICIF.malloc();
        PointerBuffer types = MemoryUtil.memAllocPointer(Math.max(1, argTypes.length));
        PointerBuffer values = MemoryUtil.memAllocPointer(Math.max(1, args.length));
        ByteBuffer storage = MemoryUtil.memAlloc(Math.max(8, args.length * 8));
        ByteBuffer result = MemoryUtil.memAlloc(8);
        try {
            for (int index = 0; index < argTypes.length; index++) {
                types.put(index, argTypes[index].address());
                storage.putLong(index * 8, args[index]);
                values.put(index, MemoryUtil.memAddress(storage) + index * 8L);
            }
            types.limit(argTypes.length);
            values.limit(args.length);
            if (LibFFI.ffi_prep_cif(cif, LibFFI.FFI_DEFAULT_ABI, returnType, types)
                    != LibFFI.FFI_OK) {
                throw new IllegalStateException("ffi_prep_cif failed");
            }
            LibFFI.ffi_call(cif, function, result, values);
            return result.getLong(0);
        } finally {
            MemoryUtil.memFree(result);
            MemoryUtil.memFree(storage);
            MemoryUtil.memFree(values);
            MemoryUtil.memFree(types);
            cif.free();
        }
    }
}

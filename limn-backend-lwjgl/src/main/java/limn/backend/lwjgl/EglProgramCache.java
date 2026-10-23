package limn.backend.lwjgl;

import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL10;
import org.lwjgl.egl.EGLGetBlobFuncANDROID;
import org.lwjgl.egl.EGLSetBlobFuncANDROID;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Keeps the programs ANGLE compiled, so that the next window's context does not compile them
 * again: an in-memory cache handed to ANGLE through {@code EGL_ANDROID_blob_cache}.
 *
 * <p>Every window has a context of its own, and every popup is a new window, so every popup
 * compiles the canvas programs from source. On a desktop driver that is cheap. On ANGLE over
 * Direct3D 11 it is not, because each program is translated to HLSL and compiled by Windows' shader
 * compiler: measured on 2026-10-22 on the Windows 11 ARM64 guest, a popup took 152 to 278 ms to its
 * first picture on ANGLE against 45 to 125 ms on the driver's own OpenGL, the window itself being
 * made in about 10 ms. ANGLE stores a linked program's binary through these two functions when an
 * application provides them, keyed by everything that went into it, and loads it back instead of
 * compiling when the same program is linked in another context of the display.
 *
 * <p>The cache lives as long as the process and holds at most {@value #CAPACITY_BYTES} bytes,
 * dropping the least recently used entry first. Nothing is written to disk: a binary is only good
 * for the driver and the ANGLE that made it, and the gain wanted is the second popup's, not the
 * next launch's. ANGLE may call from its own threads, so every access is synchronized.
 */
final class EglProgramCache {

    private static final System.Logger LOG = System.getLogger(EglProgramCache.class.getName());

    /** The most the cache holds; the canvas, video and 3D programs together are far less. */
    static final int CAPACITY_BYTES = 32 << 20;

    private static final Map<String, byte[]> ENTRIES = new LinkedHashMap<>(64, 0.75f, true);
    private static int bytes;
    private static long installedDisplay = NULL;
    // Held for the life of the process: ANGLE keeps the function pointers.
    private static EGLSetBlobFuncANDROID setter;
    private static EGLGetBlobFuncANDROID getter;

    private EglProgramCache() {
    }

    /**
     * Gives {@code display} the cache, once; a display without {@code EGL_ANDROID_blob_cache} is
     * left as it is.
     */
    static synchronized void install(long display) {
        if (display == NULL || display == installedDisplay) {
            return;
        }
        if (!BufferAgeQuery.hasExtension(EGL10.eglQueryString(display, EGL10.EGL_EXTENSIONS),
                "EGL_ANDROID_blob_cache")) {
            return;
        }
        // Through its address: LWJGL resolves a display extension's entry only for a display it
        // made capabilities for, and the windowing library made this one.
        long function = EGL.getFunctionProvider().getFunctionAddress("eglSetBlobCacheFuncsANDROID");
        if (function == NULL) {
            return;
        }
        setter = EGLSetBlobFuncANDROID.create(EglProgramCache::set);
        getter = EGLGetBlobFuncANDROID.create(EglProgramCache::get);
        JNI.invokePPPV(display, setter.address(), getter.address(), function);
        installedDisplay = display;
        LOG.log(System.Logger.Level.DEBUG, "ANGLE program cache installed");
    }

    private static void set(long key, long keySize, long value, long valueSize) {
        if (valueSize > CAPACITY_BYTES) {
            return;
        }
        String name = name(key, keySize);
        byte[] data = new byte[(int) valueSize];
        MemoryUtil.memByteBuffer(value, (int) valueSize).get(data);
        synchronized (EglProgramCache.class) {
            byte[] old = ENTRIES.put(name, data);
            bytes += data.length - (old == null ? 0 : old.length);
            var oldest = ENTRIES.entrySet().iterator();
            while (bytes > CAPACITY_BYTES && oldest.hasNext()) {
                bytes -= oldest.next().getValue().length;
                oldest.remove();
            }
        }
    }

    private static long get(long key, long keySize, long value, long valueSize) {
        byte[] data;
        synchronized (EglProgramCache.class) {
            data = ENTRIES.get(name(key, keySize));
        }
        if (data == null) {
            return 0;
        }
        // The size alone when the buffer is too small: ANGLE asks again with one that fits.
        if (value != NULL && valueSize >= data.length) {
            MemoryUtil.memByteBuffer(value, data.length).put(data);
        }
        return data.length;
    }

    private static String name(long key, long keySize) {
        ByteBuffer bytes = MemoryUtil.memByteBuffer(key, (int) keySize);
        StringBuilder hex = new StringBuilder((int) keySize * 2);
        for (int i = 0; i < keySize; i++) {
            hex.append(Character.forDigit((bytes.get(i) >> 4) & 0xF, 16)).append(Character.forDigit(bytes.get(i) & 0xF, 16));
        }
        return hex.toString();
    }
}

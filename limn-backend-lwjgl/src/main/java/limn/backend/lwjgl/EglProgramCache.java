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
 *
 * <p><b>Closed once the virtual machine starts to shut down.</b> ANGLE may link a program on a
 * worker thread of its own, and a link that finishes while the shutdown hooks run still calls back
 * into Java with its binary, as one that looks for a binary first does. From the first shutdown
 * hook on, the cache answers every ask with "no binary", which makes ANGLE compile instead, takes
 * nothing it is handed, and has let go of what it held: a callback then reads a flag under the
 * lock and returns, copying nothing and allocating nothing while the process is taken down.
 */
final class EglProgramCache {

    private static final System.Logger LOG = System.getLogger(EglProgramCache.class.getName());

    /** The most the cache holds; the canvas, video and 3D programs together are far less. */
    static final int CAPACITY_BYTES = 32 << 20;

    /** The process's cache, the one every display is handed. */
    private static final EglProgramCache PROCESS = new EglProgramCache(CAPACITY_BYTES);

    private static long installedDisplay = NULL;
    private static boolean closesAtShutdown;
    // Held for the life of the process: ANGLE keeps the function pointers.
    private static EGLSetBlobFuncANDROID setter;
    private static EGLGetBlobFuncANDROID getter;

    private final int capacity;
    private final Map<String, byte[]> entries = new LinkedHashMap<>(64, 0.75f, true);
    private int bytes;
    private boolean closed;

    /** @param capacity the most this cache holds, in bytes */
    EglProgramCache(int capacity) {
        this.capacity = capacity;
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
        closeAtShutdown();
        setter = EGLSetBlobFuncANDROID.create(PROCESS::set);
        getter = EGLGetBlobFuncANDROID.create(PROCESS::get);
        JNI.invokePPPV(display, setter.address(), getter.address(), function);
        installedDisplay = display;
        LOG.log(System.Logger.Level.DEBUG, "ANGLE program cache installed");
    }

    /**
     * Has the process's cache closed by a shutdown hook, registered once, before ANGLE can call
     * it; closes it at once when the shutdown has already begun.
     */
    private static void closeAtShutdown() {
        if (closesAtShutdown) {
            return;
        }
        closesAtShutdown = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(PROCESS::close, "limn-angle-program-cache"));
        } catch (IllegalStateException shuttingDown) {
            PROCESS.close();
        }
    }

    /**
     * {@code EGLSetBlobFuncANDROID}: keeps the binary {@code value} under {@code key}, unless this
     * cache is closed or the binary alone is larger than the cache. Any thread.
     */
    void set(long key, long keySize, long value, long valueSize) {
        if (valueSize > capacity) {
            return;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
        }
        // Copied outside the lock, so that one thread's copy does not hold up another's ask.
        String name = name(key, keySize);
        byte[] data = new byte[(int) valueSize];
        MemoryUtil.memByteBuffer(value, (int) valueSize).get(data);
        synchronized (this) {
            if (closed) {
                return;
            }
            byte[] old = entries.put(name, data);
            bytes += data.length - (old == null ? 0 : old.length);
            var oldest = entries.entrySet().iterator();
            while (bytes > capacity && oldest.hasNext()) {
                bytes -= oldest.next().getValue().length;
                oldest.remove();
            }
        }
    }

    /**
     * {@code EGLGetBlobFuncANDROID}: the size of the binary kept under {@code key}, copied into
     * {@code value} when it fits; {@code 0}, "no binary", when none is kept or this cache is
     * closed. Any thread.
     */
    long get(long key, long keySize, long value, long valueSize) {
        byte[] data;
        synchronized (this) {
            if (closed) {
                return 0;
            }
            data = entries.get(name(key, keySize));
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

    /**
     * Answers "no binary" to every ask from now on, takes nothing more, and lets go of what was
     * kept. Idempotent; any thread.
     */
    synchronized void close() {
        closed = true;
        entries.clear();
        bytes = 0;
    }

    /** @return the bytes of the binaries kept now; for tests */
    synchronized int bytes() {
        return bytes;
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

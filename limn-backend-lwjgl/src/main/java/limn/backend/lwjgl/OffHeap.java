package limn.backend.lwjgl;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * A Java byte array handed to native code: copied off the heap, positioned at zero, and freed by
 * whoever asked for it.
 *
 * <p>Nine call sites wrote {@code memAlloc(bytes.length).put(bytes).flip()} for a font, an image,
 * an Ogg stream, a cursor, a window icon, an SVG. The copy is unavoidable &mdash; a Java array
 * can move under a native pointer &mdash; and it goes on the heap rather than the stack because
 * nothing bounds these sizes. The caller still owns the buffer and frees it; this only makes the
 * copy one line and the NUL-terminated variant impossible to get wrong by one.
 */
final class OffHeap {

    private OffHeap() {
    }

    /**
     * @param bytes what to copy
     * @return a buffer holding a copy, positioned at zero; free it with {@link MemoryUtil#memFree}
     */
    static ByteBuffer copyOf(byte[] bytes) {
        return MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
    }

    /**
     * The same with a trailing zero byte, for a C parser that reads a string rather than a length.
     *
     * @param bytes what to copy
     * @return a buffer holding a copy and a NUL, positioned at zero; free it with
     *         {@link MemoryUtil#memFree}
     */
    static ByteBuffer copyOfNulTerminated(byte[] bytes) {
        return MemoryUtil.memAlloc(bytes.length + 1).put(bytes).put((byte) 0).flip();
    }
}

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
 * copy one line.
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
}

package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two callbacks ANGLE is handed through {@code EGL_ANDROID_blob_cache}, called here as ANGLE
 * calls them, with native memory: a binary kept under a key and handed back whole, the size alone
 * for a buffer too small, the least recently used binary dropped past the bound, and nothing kept
 * or handed back once the cache is closed, which the process's cache is from the first shutdown
 * hook on, while ANGLE's worker threads may still call it.
 */
class EglProgramCacheTest {

    @Test
    void aBinaryIsHandedBackWholeAndItsSizeAloneToABufferTooSmall() {
        EglProgramCache cache = new EglProgramCache(EglProgramCache.CAPACITY_BYTES);
        ByteBuffer key = bytes(1, 2, 3);
        ByteBuffer unknown = bytes(9);
        ByteBuffer binary = bytes(10, 20, 30, 40);
        ByteBuffer into = MemoryUtil.memCalloc(4);
        try {
            cache.set(address(key), 3, address(binary), 4);
            assertEquals(4, cache.get(address(key), 3, MemoryUtil.NULL, 0),
                    "the size, asked with no buffer");
            assertEquals(4, cache.get(address(key), 3, address(into), 2),
                    "and with one too small, which is left as it was");
            assertEquals(0, into.get(0));
            assertEquals(4, cache.get(address(key), 3, address(into), 4));
            byte[] read = new byte[4];
            into.get(0, read);
            assertArrayEquals(new byte[] {10, 20, 30, 40}, read);
            assertEquals(0, cache.get(address(unknown), 1, address(into), 4), "an unknown key");
        } finally {
            MemoryUtil.memFree(key);
            MemoryUtil.memFree(unknown);
            MemoryUtil.memFree(binary);
            MemoryUtil.memFree(into);
        }
    }

    @Test
    void theLeastRecentlyUsedBinaryGoesFirstPastTheBound() {
        EglProgramCache cache = new EglProgramCache(10);
        ByteBuffer first = bytes(1);
        ByteBuffer second = bytes(2);
        ByteBuffer third = bytes(3);
        ByteBuffer fourth = bytes(4);
        ByteBuffer binary = bytes(0, 0, 0, 0);
        ByteBuffer tooLarge = MemoryUtil.memCalloc(11);
        try {
            cache.set(address(first), 1, address(binary), 4);
            cache.set(address(second), 1, address(binary), 4);
            assertEquals(4, cache.get(address(first), 1, MemoryUtil.NULL, 0), "the first is used again");
            cache.set(address(third), 1, address(binary), 4);
            assertEquals(8, cache.bytes());
            assertEquals(0, cache.get(address(second), 1, MemoryUtil.NULL, 0), "so the second went");
            assertEquals(4, cache.get(address(first), 1, MemoryUtil.NULL, 0));
            cache.set(address(fourth), 1, address(tooLarge), 11);
            assertEquals(8, cache.bytes(), "and a binary larger than the whole cache is not kept");
        } finally {
            MemoryUtil.memFree(first);
            MemoryUtil.memFree(second);
            MemoryUtil.memFree(third);
            MemoryUtil.memFree(fourth);
            MemoryUtil.memFree(binary);
            MemoryUtil.memFree(tooLarge);
        }
    }

    @Test
    void aClosedCacheAnswersNoBinaryAndKeepsNothing() {
        EglProgramCache cache = new EglProgramCache(EglProgramCache.CAPACITY_BYTES);
        ByteBuffer key = bytes(7, 7);
        ByteBuffer other = bytes(8, 8);
        ByteBuffer binary = bytes(1, 2, 3, 4, 5);
        ByteBuffer into = MemoryUtil.memCalloc(5);
        try {
            cache.set(address(key), 2, address(binary), 5);
            assertEquals(5, cache.get(address(key), 2, MemoryUtil.NULL, 0), "the fixture: it was kept");

            cache.close();

            assertEquals(0, cache.bytes(), "what was kept is let go of");
            assertEquals(0, cache.get(address(key), 2, address(into), 5),
                    "an ask after the close is answered no binary, so ANGLE compiles");
            assertEquals(0, into.get(0), "and nothing is copied out");
            cache.set(address(other), 2, address(binary), 5);
            cache.set(address(key), 2, address(binary), 5);
            assertEquals(0, cache.bytes(), "a binary handed over after the close is not kept");
            assertEquals(0, cache.get(address(other), 2, MemoryUtil.NULL, 0));
            cache.close();
            assertEquals(0, cache.bytes(), "and a second close changes nothing");
        } finally {
            MemoryUtil.memFree(key);
            MemoryUtil.memFree(other);
            MemoryUtil.memFree(binary);
            MemoryUtil.memFree(into);
        }
    }

    /** @return native memory holding {@code values}, to be freed by the caller */
    private static ByteBuffer bytes(int... values) {
        ByteBuffer buffer = MemoryUtil.memAlloc(values.length);
        for (int i = 0; i < values.length; i++) {
            buffer.put(i, (byte) values[i]);
        }
        return buffer;
    }

    private static long address(ByteBuffer buffer) {
        return MemoryUtil.memAddress(buffer);
    }
}

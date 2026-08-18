package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The producer side every decoder shares: memory allocated once, slots handed out and taken back
 * exactly once, and exhaustion reported rather than waited on.
 */
class FramePoolTest {

    @Test
    void planesAreDirectAlignedAndEndAtTheirLastSample() {
        // Width 5 in 4:2:0: luma rows are 5 bytes of picture padded to 8, chroma 3 padded to 4.
        FramePool pool = FramePool.of(2, 5, 7, PixelFormat.I420, VideoColor.BT709_LIMITED);

        assertEquals(8, pool.stride(0), "luma byte width 5 rounded up to four");
        assertEquals(4, pool.stride(1), "chroma byte width 3 rounded up to four");
        for (int plane = 0; plane < 3; plane++) {
            ByteBuffer buffer = pool.planeOf(0, plane);
            assertTrue(buffer.isDirect(), "plane " + plane + " must be device-addressable");
            assertEquals(PixelFormat.I420.minPlaneBytes(plane, 5, 7, pool.stride(plane)),
                    buffer.capacity(),
                    "plane " + plane + " ends at its last sample, with no trailing padding");
        }
    }

    @Test
    void nv12ChromaIsWiderThanLumaAtAnOddWidth() {
        FramePool pool = FramePool.of(1, 5, 4, PixelFormat.NV12, VideoColor.BT709_LIMITED);
        assertEquals(5, PixelFormat.NV12.planeByteWidth(0, 5));
        assertEquals(6, PixelFormat.NV12.planeByteWidth(1, 5),
                "three interleaved chroma samples of two bytes each");
        assertEquals(2, pool.format().planeCount());
        assertEquals(8, pool.stride(1), "six rounded up to four is eight");
    }

    @Test
    void acquireRunsOutAndAReleaseGivesItBack() {
        FramePool pool = FramePool.of(2, 16, 16, PixelFormat.I420, VideoColor.BT709_LIMITED);
        assertEquals(2, pool.freeSlots());

        VideoFrame first = pool.acquire().publish();
        VideoFrame second = pool.acquire().publish();
        assertEquals(0, pool.freeSlots());
        assertNull(pool.acquire(), "an exhausted pool answers with nothing, it does not wait");

        first.release();
        assertEquals(1, pool.freeSlots());
        VideoFrame.Writer again = pool.acquire();
        assertNotNull(again);
        assertSame(first, again.frame(), "the freed slot is the one handed back out");

        second.release();
        again.publish().release();
        assertEquals(2, pool.freeSlots());
    }

    @Test
    void abandoningAnUnpublishedSlotReturnsIt() {
        // What a reader does when it takes a slot and then finds the input has ended: without this
        // the slot is lost, and two ends against a two-slot pool would stall it forever.
        FramePool pool = FramePool.of(1, 8, 8, PixelFormat.I420, VideoColor.BT709_LIMITED);
        VideoFrame.Writer writer = pool.acquire();
        assertEquals(0, pool.freeSlots());
        pool.abandon(writer);
        assertEquals(1, pool.freeSlots());
        assertNotNull(pool.acquire(), "the abandoned slot is acquirable again");
    }

    @Test
    void abandoningAPublishedOrForeignSlotThrows() {
        FramePool pool = FramePool.of(1, 8, 8, PixelFormat.I420, VideoColor.BT709_LIMITED);
        FramePool other = FramePool.of(1, 8, 8, PixelFormat.I420, VideoColor.BT709_LIMITED);
        VideoFrame.Writer writer = pool.acquire();
        writer.publish();
        assertThrows(IllegalStateException.class, () -> pool.abandon(writer),
                "a published slot is the consumer's to release, not the producer's to take back");

        VideoFrame foreign = other.acquire().publish();
        assertThrows(IllegalArgumentException.class, () -> pool.recycle(foreign));
    }

    @Test
    void releasingTwiceIsLoud() {
        FramePool pool = FramePool.of(1, 8, 8, PixelFormat.I420, VideoColor.BT709_LIMITED);
        VideoFrame frame = pool.acquire().publish();
        frame.release();
        assertThrows(IllegalStateException.class, frame::release,
                "a slot returned twice is handed to two producers at once");
        assertEquals(1, pool.freeSlots(), "and the second attempt changed nothing");
        assertThrows(IllegalArgumentException.class, () -> pool.recycle(frame),
                "the pool refuses a slot that is already free, whoever asks");
    }

    @Test
    void rejectsSlotCountsItCannotRepresent() {
        assertThrows(IllegalArgumentException.class,
                () -> FramePool.of(0, 8, 8, PixelFormat.I420, VideoColor.BT709_LIMITED));
        assertThrows(IllegalArgumentException.class,
                () -> FramePool.of(FramePool.MAX_SLOTS + 1, 8, 8, PixelFormat.I420,
                        VideoColor.BT709_LIMITED));
        assertThrows(IllegalArgumentException.class,
                () -> FramePool.of(1, 0, 8, PixelFormat.I420, VideoColor.BT709_LIMITED));
    }

    @Test
    void everySlotOfAFullPoolIsDistinctMemory() {
        // The bit index is the slot index: at the ceiling the free mask is all ones, which the
        // shift that builds it cannot express and which is why that case is written out separately.
        FramePool pool = FramePool.of(FramePool.MAX_SLOTS, 4, 4, PixelFormat.I444,
                VideoColor.BT601_FULL);
        assertEquals(FramePool.MAX_SLOTS, pool.freeSlots());
        java.util.Set<ByteBuffer> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (int slot = 0; slot < FramePool.MAX_SLOTS; slot++) {
            VideoFrame.Writer writer = pool.acquire();
            assertNotNull(writer, "slot " + slot);
            assertTrue(seen.add(pool.planeOf(writer.frame().slot(), 0)),
                    "slot " + slot + " shares its luma plane with another slot");
        }
        assertNull(pool.acquire());
    }
}

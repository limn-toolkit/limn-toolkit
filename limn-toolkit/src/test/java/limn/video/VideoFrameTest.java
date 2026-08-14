package limn.video;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lease {@link VideoFrame} hands a consumer: the publish and release protocol, the recycler
 * running exactly once, the parity of the generation counter, and the plane bindings a producer
 * makes once and reuses for the life of the pool.
 */
class VideoFrameTest {

    @Test
    void exactlyMinimumPlaneBytesPublishes() {
        long minimum = PixelFormat.I420.minPlaneBytes(0, 5, 7, 8);
        assertEquals(8L * 6 + 5, minimum, "six full strides and a last row of picture only");

        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(5, 7, PixelFormat.I420, VideoColor.BT709_LIMITED);
        writer.setPlane(0, ByteBuffer.allocate((int) minimum), 8);
        writer.setPlane(1, ByteBuffer.allocate(chromaBytes(5, 7)), 3);
        writer.setPlane(2, ByteBuffer.allocate(chromaBytes(5, 7)), 3);
        VideoFrame frame = writer.publish();
        assertEquals(minimum, frame.plane(0).limit(), "the plane extends exactly to its last sample");
        frame.release();

        assertThrows(IllegalArgumentException.class,
                () -> writer.setPlane(0, ByteBuffer.allocate((int) minimum - 1), 8),
                "one byte short of the last row is not a plane");
    }

    @Test
    void publishWithAnUnboundPlaneThrows() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED);
        writer.setPlane(0, ByteBuffer.allocate(16), 4);
        writer.setPlane(1, ByteBuffer.allocate(4), 2);
        assertThrows(IllegalStateException.class, writer::publish);
    }

    @Test
    void invalidPlaneBindingsThrow() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED);

        assertThrows(IllegalArgumentException.class,
                () -> writer.setPlane(0, ByteBuffer.allocate(16), -4), "a negative stride");
        assertThrows(IllegalArgumentException.class,
                () -> writer.setPlane(0, ByteBuffer.allocate(16), 3), "a stride below the byte width");
        assertThrows(NullPointerException.class,
                () -> writer.setPlane(0, null, 4), "a null buffer");
        assertThrows(IndexOutOfBoundsException.class,
                () -> writer.setPlane(3, ByteBuffer.allocate(16), 4), "a plane the format does not have");

        writer.setPlane(0, ByteBuffer.allocate(16), 4);
        writer.setPlane(1, ByteBuffer.allocate(4), 2);
        writer.setPlane(2, ByteBuffer.allocate(4), 2);
        VideoFrame frame = writer.publish();
        assertTrue(frame.plane(0).isReadOnly());
        assertEquals(4, frame.stride(0));
        frame.release();
    }

    @Test
    void invalidWriterConstructionThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> VideoFrame.Writer.allocate(-1, VideoFrame.Recycler.NONE), "a negative slot");
        assertThrows(NullPointerException.class,
                () -> VideoFrame.Writer.allocate(0, null), "a frame with nowhere to return its memory");
    }

    @Test
    void invalidConfigurationThrows() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);

        assertThrows(IllegalArgumentException.class,
                () -> writer.configure(0, 4, PixelFormat.I420, VideoColor.BT709_LIMITED), "width 0");
        assertThrows(IllegalArgumentException.class,
                () -> writer.configure(4, 0, PixelFormat.I420, VideoColor.BT709_LIMITED), "height 0");
        assertThrows(IllegalArgumentException.class,
                () -> writer.configure(PixelFormat.MAX_DIMENSION + 1, 4, PixelFormat.I420,
                        VideoColor.BT709_LIMITED), "a width past the geometry limit");
        assertThrows(IllegalArgumentException.class,
                () -> writer.configure(4, PixelFormat.MAX_DIMENSION + 1, PixelFormat.I420,
                        VideoColor.BT709_LIMITED), "a height past the geometry limit");
        assertThrows(NullPointerException.class,
                () -> writer.configure(4, 4, null, VideoColor.BT709_LIMITED), "a null layout");
        assertThrows(NullPointerException.class,
                () -> writer.configure(4, 4, PixelFormat.I420, null), "a null interpretation");
    }

    @Test
    void bindingAPlaneBeforeAnySizeThrows() {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);

        assertThrows(IllegalStateException.class, () -> writer.setPlane(0, ByteBuffer.allocate(64), 8),
                "a plane's minimum size is meaningless until the frame has one");
    }

    @Test
    void publishingTwiceThrows() {
        VideoFrame.Writer writer = writerOf(4, 4, PixelFormat.I420);
        VideoFrame frame = writer.publish();
        long generation = frame.generation();

        assertThrows(IllegalStateException.class, writer::publish,
                "a second publication flips a held frame's generation to even under the consumer");

        assertEquals(generation, frame.generation(), "and leaves the counter where it was");
        frame.release();
    }

    @Test
    void reconfiguringInvalidatesEveryPlaneBinding() {
        VideoFrame.Writer writer = writerOf(64, 64, PixelFormat.I420);
        writer.publish().release();

        writer.configure(16, 16, PixelFormat.I420, VideoColor.BT709_LIMITED);
        writer.setPlane(0, ByteBuffer.allocate(16 * 16), 16);

        assertThrows(IllegalStateException.class, writer::publish,
                "a producer that misses one setPlane after a size change would otherwise publish the"
                        + " old buffer at the old extent");
    }

    @Test
    void aPlaneTheFormatDoesNotHaveThrows() {
        VideoFrame.Writer writer = writerOf(4, 4, PixelFormat.NV12);
        VideoFrame frame = writer.publish();

        assertThrows(IndexOutOfBoundsException.class, () -> frame.plane(2),
                "NV12 carries its chroma interleaved in plane 1; there is no plane 2 to answer with null");
        assertThrows(IndexOutOfBoundsException.class, () -> frame.stride(2));
        assertThrows(IndexOutOfBoundsException.class, () -> frame.stride(-1));

        frame.release();
    }

    @Test
    void aDirectPlaneIsBoundAndReadLikeAHeapOne() {
        ByteBuffer direct = ByteBuffer.allocateDirect(16);
        ByteBuffer heap = ByteBuffer.allocate(16);
        for (int index = 0; index < 16; index++) {
            direct.put(index, (byte) (index * 7));
            heap.put(index, (byte) (index * 7));
        }

        VideoFrame fromDirect = lumaFrame(direct);
        VideoFrame fromHeap = lumaFrame(heap);
        ByteBuffer directPlane = fromDirect.plane(0);
        ByteBuffer heapPlane = fromHeap.plane(0);

        assertTrue(directPlane.isDirect(), "a direct binding stays direct, so a device upload has an address");
        assertTrue(directPlane.isReadOnly());
        assertFalse(heapPlane.isDirect());
        assertTrue(heapPlane.isReadOnly());
        assertFalse(heapPlane.hasArray(), "the read-only view closes the array route on a heap plane too");
        for (int index = 0; index < 16; index++) {
            assertEquals(heapPlane.get(index), directPlane.get(index), "sample " + index);
        }

        fromDirect.release();
        fromHeap.release();
    }

    @Test
    void threadsRacingToReleaseCannotBothSucceed() throws InterruptedException {
        // The threads spin on a flag rather than waiting on a latch, and there are several of them
        // over many attempts: a wake-up from a wait costs enough that they take their turns, and the
        // window this is here to close is the handful of instructions between reading the generation
        // and writing it back.
        int racers = 4;
        for (int attempt = 0; attempt < 500; attempt++) {
            CountingRecycler recycler = new CountingRecycler();
            VideoFrame frame = published(recycler);
            AtomicInteger ready = new AtomicInteger();
            AtomicBoolean go = new AtomicBoolean();
            AtomicInteger refused = new AtomicInteger();
            Runnable release = () -> {
                ready.incrementAndGet();
                while (!go.get()) {
                    Thread.onSpinWait();
                }
                try {
                    frame.release();
                } catch (IllegalStateException alreadyReleased) {
                    refused.incrementAndGet();
                }
            };

            Thread[] threads = new Thread[racers];
            for (int racer = 0; racer < racers; racer++) {
                threads[racer] = new Thread(release, "release-" + racer);
                threads[racer].start();
            }
            while (ready.get() < racers) {
                Thread.onSpinWait();
            }
            go.set(true);
            for (Thread thread : threads) {
                thread.join();
            }

            assertEquals(racers - 1, refused.get(),
                    "attempt " + attempt + ": every release but one must be refused");
            assertEquals(1, recycler.count,
                    "attempt " + attempt + ": the slot was handed back " + recycler.count + " times,"
                            + " so two producers now own the same memory");
        }
    }

    @Test
    void releaseInvokesTheRecyclerExactlyOnce() {
        CountingRecycler recycler = new CountingRecycler();
        VideoFrame frame = published(recycler);

        frame.release();

        assertEquals(1, recycler.count);
        assertSame(frame, recycler.last, "the recycler is handed the frame whose slot came free");
    }

    @Test
    void secondReleaseThrowsAndDoesNotRecycleAgain() {
        CountingRecycler recycler = new CountingRecycler();
        VideoFrame frame = published(recycler);

        frame.release();
        assertThrows(IllegalStateException.class, frame::release,
                "one slot returned twice is handed to two producers at once");
        assertEquals(1, recycler.count, "the second release must not reach the pool");
    }

    @Test
    void releasingAFrameThatWasNeverPublishedThrows() {
        CountingRecycler recycler = new CountingRecycler();
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, recycler);

        assertThrows(IllegalStateException.class, () -> writer.frame().release());
        assertEquals(0, recycler.count);
    }

    @Test
    void planeThrowsAfterReleaseButDescriptorsStillAnswer() {
        VideoFrame frame = published(VideoFrame.Recycler.NONE);
        long generation = frame.generation();
        frame.release();

        assertThrows(IllegalStateException.class, () -> frame.plane(0));
        assertEquals(4, frame.width());
        assertEquals(4, frame.height());
        assertSame(PixelFormat.I420, frame.format());
        assertSame(VideoColor.BT709_LIMITED, frame.color());
        assertEquals(4, frame.stride(0));
        assertEquals(1_500_000L, frame.ptsMicros());
        assertEquals(0, frame.slot());
        assertEquals(generation + 1, frame.generation());
        assertNotNull(frame.toString());
    }

    @Test
    void planesAreReadOnly() {
        VideoFrame frame = published(VideoFrame.Recycler.NONE);
        ByteBuffer plane = frame.plane(0);
        assertTrue(plane.isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> plane.put(0, (byte) 1));
        frame.release();
    }

    @Test
    void everyDeliveryStartsAtPositionZero() {
        VideoFrame.Writer writer = writerOf(4, 4, PixelFormat.I420);
        VideoFrame frame = writer.publish();
        int extent = frame.plane(0).limit();
        frame.plane(0).get();
        frame.plane(0).get();
        assertEquals(2, frame.plane(0).position(), "the buffer instance is shared, so it stays consumed");
        frame.release();

        VideoFrame again = writer.publish();
        assertEquals(0, again.plane(0).position(), "a publication rewinds every plane view");
        assertEquals(extent, again.plane(0).limit());
        again.release();
    }

    @Test
    void republishingReusesTheSameInstances() {
        VideoFrame.Writer writer = writerOf(16, 16, PixelFormat.I420);
        VideoFrame first = writer.publish();
        ByteBuffer luma = first.plane(0);
        ByteBuffer cb = first.plane(1);
        ByteBuffer cr = first.plane(2);
        first.release();

        for (int cycle = 0; cycle < 10_000; cycle++) {
            VideoFrame frame = writer.publish();
            assertSame(first, frame, "the pool republishes one instance per slot");
            assertSame(luma, frame.plane(0));
            assertSame(cb, frame.plane(1));
            assertSame(cr, frame.plane(2));
            frame.release();
        }
    }

    @Test
    void generationIsOddExactlyWhileHeld() {
        VideoFrame.Writer writer = writerOf(4, 4, PixelFormat.I420);
        VideoFrame frame = writer.frame();
        long allocated = frame.generation();
        assertEquals(0, allocated & 1L, "a fresh slot belongs to the producer");

        writer.publish();
        long held = frame.generation();
        assertEquals(1, held & 1L, "odd exactly while a consumer holds it");
        assertTrue(held > allocated);

        frame.release();
        long returned = frame.generation();
        assertEquals(0, returned & 1L);
        assertTrue(returned > held, "the counter never goes backwards");

        writer.publish();
        assertTrue(frame.generation() > returned);
        frame.release();
    }

    @Test
    void configureAndSetPlaneWhilePublishedThrow() {
        VideoFrame.Writer writer = writerOf(4, 4, PixelFormat.I420);
        VideoFrame frame = writer.publish();

        assertThrows(IllegalStateException.class,
                () -> writer.configure(8, 8, PixelFormat.I444, VideoColor.BT601_FULL));
        assertThrows(IllegalStateException.class,
                () -> writer.setPlane(0, ByteBuffer.allocate(16), 4));

        frame.release();
        writer.configure(8, 8, PixelFormat.I444, VideoColor.BT601_FULL);
        assertEquals(8, frame.width(), "retargeting is allowed once the slot is back");
    }

    @Test
    void ptsRoundTripsAndDefaultsToUnknown() {
        assertEquals(Long.MIN_VALUE, VideoFrame.PTS_UNKNOWN);

        VideoFrame.Writer writer = writerOf(4, 4, PixelFormat.I420);
        assertEquals(VideoFrame.PTS_UNKNOWN, writer.frame().ptsMicros());

        writer.setPtsMicros(1_234_567L);
        VideoFrame frame = writer.publish();
        assertEquals(1_234_567L, frame.ptsMicros());
        frame.release();

        writer.setPtsMicros(VideoFrame.PTS_UNKNOWN);
        writer.publish();
        assertEquals(VideoFrame.PTS_UNKNOWN, frame.ptsMicros());
        frame.release();
    }

    @Test
    void croppingIsExpressibleWithStride() {
        int codedStride = 8;
        byte[] coded = new byte[codedStride * 8];
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < codedStride; column++) {
                coded[row * codedStride + column] = (byte) (row * 16 + column);
            }
        }
        ByteBuffer luma = ByteBuffer.wrap(coded);
        luma.position(2 * codedStride + 2);
        ByteBuffer visible = luma.slice();

        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED);
        writer.setPlane(0, visible, codedStride);
        writer.setPlane(1, ByteBuffer.allocate(4 * 2), 4);
        writer.setPlane(2, ByteBuffer.allocate(4 * 2), 4);
        VideoFrame frame = writer.publish();

        ByteBuffer plane = frame.plane(0);
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                assertEquals((byte) ((row + 2) * 16 + column + 2), plane.get(row * frame.stride(0) + column),
                        "visible sample " + column + "," + row + " of the cropped picture");
            }
        }
        frame.release();
    }

    @Test
    void toStringNeverThrows() {
        VideoFrame.Writer writer = writerOf(4, 4, PixelFormat.I420);
        assertNotNull(writer.frame().toString());

        VideoFrame frame = writer.publish();
        assertTrue(frame.toString().contains("slot=0"));
        frame.release();
        assertTrue(frame.toString().contains("4x4"));
    }

    private static int chromaBytes(int width, int height) {
        return (int) PixelFormat.I420.minPlaneBytes(1, width, height,
                PixelFormat.I420.planeByteWidth(1, width));
    }

    private static VideoFrame.Writer writerOf(int width, int height, PixelFormat format) {
        return writerOf(width, height, format, VideoFrame.Recycler.NONE);
    }

    private static VideoFrame.Writer writerOf(int width, int height, PixelFormat format,
                                              VideoFrame.Recycler recycler) {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, recycler);
        writer.configure(width, height, format, VideoColor.BT709_LIMITED);
        for (int plane = 0; plane < format.planeCount(); plane++) {
            int stride = format.planeByteWidth(plane, width);
            long bytes = format.minPlaneBytes(plane, width, height, stride);
            writer.setPlane(plane, ByteBuffer.allocate((int) bytes), stride);
        }
        return writer;
    }

    /** A published 4x4 I420 frame whose luma is the given buffer, direct or heap. */
    private static VideoFrame lumaFrame(ByteBuffer luma) {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(4, 4, PixelFormat.I420, VideoColor.BT709_LIMITED);
        writer.setPlane(0, luma, 4);
        writer.setPlane(1, ByteBuffer.allocate(4), 2);
        writer.setPlane(2, ByteBuffer.allocate(4), 2);
        return writer.publish();
    }

    private static VideoFrame published(VideoFrame.Recycler recycler) {
        VideoFrame.Writer writer = writerOf(4, 4, PixelFormat.I420, recycler);
        writer.setPtsMicros(1_500_000L);
        return writer.publish();
    }

    /** Counts what a pool would count: one return per delivered frame, and which slot came back. */
    private static final class CountingRecycler implements VideoFrame.Recycler {

        private int count;
        private VideoFrame last;

        @Override
        public void recycle(VideoFrame frame) {
            count++;
            last = frame;
        }
    }
}

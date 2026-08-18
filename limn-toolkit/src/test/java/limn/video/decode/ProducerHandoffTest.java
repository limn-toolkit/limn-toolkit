package limn.video.decode;

import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A picture produced on one thread, read on another and released there: the arrangement every real
 * player has and the one phase 1 could describe but not exercise, because describing it needs no
 * producer and exercising it does.
 *
 * <p>What it can prove is that a consumer sees a fully written picture, that releasing from a
 * different thread than the one that published returns the slot, and that the producer goes on
 * reusing that slot for thousands of pictures without a stall or a mismatch. What no test can force
 * is the memory-model edge itself: every handoff Java offers already orders the two threads, so the
 * volatile generation counter is what makes a handoff that does <em>not</em> order them safe, and a
 * stress that reads every sample of every picture is the closest thing to evidence available.
 */
class ProducerHandoffTest {

    private static final int PICTURES = 2_000;

    @Test
    @Timeout(60)
    void picturesCrossThreadsIntactAndTheSlotsComeBack() throws Exception {
        SyntheticSpec spec = SyntheticSpec.of(48, 32)
                .withSlots(2)
                .withPattern(SyntheticPattern.GRADIENT)
                .withFrameCount(PICTURES);
        VideoStreamSource source = SyntheticVideoDecoder.open(spec);
        BlockingQueue<VideoFrame> handoff = new ArrayBlockingQueue<>(4);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger consumed = new AtomicInteger();
        AtomicReference<String> releasedOn = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            try {
                for (int index = 0; index < PICTURES; index++) {
                    VideoFrame frame = handoff.take();
                    assertTrue((frame.generation() & 1L) != 0L,
                            "a delivered picture is held by its consumer");
                    int expectedCorner = index & 0xFF;
                    assertEquals(expectedCorner, frame.plane(0).get(0) & 0xFF,
                            "picture " + index + " arrived incomplete or out of order");
                    assertEquals(spec.pattern().luma(47, 31, 48, 32, index, 8),
                            frame.plane(0).get(31 * frame.stride(0) + 47) & 0xFF,
                            "the last sample of picture " + index);
                    releasedOn.set(Thread.currentThread().getName());
                    frame.release();
                    consumed.incrementAndGet();
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, "video-consumer");
        consumer.start();

        int produced = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (produced < PICTURES && failure.get() == null) {
            if (System.nanoTime() - deadline > 0) {
                break;
            }
            VideoStreamSource.Read read = source.readFrame();
            if (read == VideoStreamSource.Read.PENDING) {
                Thread.onSpinWait(); // the consumer holds both pictures; it will not for long
                continue;
            }
            assertEquals(VideoStreamSource.Read.FRAME, read, "picture " + produced);
            handoff.put(source.frame());
            produced++;
        }
        consumer.join(TimeUnit.SECONDS.toMillis(15));

        if (failure.get() != null) {
            throw new AssertionError("the consumer thread failed", failure.get());
        }
        assertEquals(PICTURES, produced);
        assertEquals(PICTURES, consumed.get());
        assertEquals("video-consumer", releasedOn.get(),
                "a picture is released on whichever thread finished with it");
        assertEquals(VideoStreamSource.Read.END, source.readFrame(),
                "and the pool never stalled on the way there");
        source.close();
        assertNull(source.frame());
    }
}

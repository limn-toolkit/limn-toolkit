package limn.video;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stream with no decoder behind it but a real pool: real {@link VideoFrame}s handed out by a real
 * {@link VideoFrame.Recycler} that throws when a slot comes back twice, so a picture released twice
 * across a thread handoff is loud here exactly as it would be against a decoder, and one never
 * released costs a slot here exactly as it would there.
 *
 * <p>Everything a test steers is a plain field. The two latches are what let a decode thread be
 * driven without sleeping: {@link #entered} counts down when a read begins and {@link #release}
 * holds it there until the test says otherwise.
 */
final class PooledTestStream implements VideoStreamSource {

    private final int width;
    private final int height;
    private final VideoFrame.Writer[] writers;
    private final boolean[] free;
    private final ByteBuffer[][] planeBuffers;
    private final int[] slotWidth;

    /** Pictures before the end; the default is more than any test asks for. */
    int frameCount = Integer.MAX_VALUE;
    /** Microseconds between consecutive presentation times. */
    long ptsStepMicros = 33_333;
    /** False makes every picture report {@link VideoFrame#PTS_UNKNOWN}. */
    boolean timed = true;
    boolean rewindable = true;
    boolean seekable = true;
    /** Reads answer {@link Read#PENDING} while positive, decrementing each time. */
    int pendingReads;
    /** Thrown by the next read and by every read after it. */
    RuntimeException failOnRead;
    /** From this picture on, every one is half size: a resolution change mid-stream. */
    int shrinkAfter = Integer.MAX_VALUE;
    /** Held open while a read must block; null lets every read through. */
    CountDownLatch release;
    /** Counted down as a read begins, before it blocks on {@link #release}. */
    CountDownLatch entered;
    /** Set by a test after it has closed the player: a read past it is a broken shutdown promise. */
    volatile boolean shutdownComplete;

    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger readsAfterShutdown = new AtomicInteger();
    final AtomicInteger seeks = new AtomicInteger();
    /** Every target this stream was asked for, in order, so a test can assert what reached it. */
    final java.util.List<Long> seekTargets =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    int resets;
    int closes;

    private int index;
    private VideoFrame current;
    private boolean closed;

    PooledTestStream(int width, int height, int slots) {
        this.width = width;
        this.height = height;
        this.writers = new VideoFrame.Writer[slots];
        this.free = new boolean[slots];
        this.planeBuffers = new ByteBuffer[slots][PixelFormat.I420.planeCount()];
        this.slotWidth = new int[slots];
        VideoFrame.Recycler recycler = frame -> {
            synchronized (free) {
                int slot = frame.slot();
                if (free[slot]) {
                    throw new IllegalStateException("pool slot " + slot + " was released twice");
                }
                free[slot] = true;
            }
        };
        for (int slot = 0; slot < slots; slot++) {
            VideoFrame.Writer writer = VideoFrame.Writer.allocate(slot, recycler);
            for (int plane = 0; plane < PixelFormat.I420.planeCount(); plane++) {
                int stride = PixelFormat.I420.planeByteWidth(plane, width);
                int bytes = (int) PixelFormat.I420.minPlaneBytes(plane, width, height, stride);
                planeBuffers[slot][plane] = ByteBuffer.allocateDirect(bytes);
            }
            writers[slot] = writer;
            shape(slot, width, height);
            free[slot] = true;
        }
    }

    /**
     * Points a free slot's writer at its buffers for a given size. The buffers are allocated for
     * the largest size this stream ever produces, so shrinking reuses them where they lie.
     */
    private void shape(int slot, int w, int h) {
        VideoFrame.Writer writer = writers[slot];
        writer.configure(w, h, PixelFormat.I420, VideoColor.BT709_LIMITED);
        for (int plane = 0; plane < PixelFormat.I420.planeCount(); plane++) {
            writer.setPlane(plane, planeBuffers[slot][plane],
                    PixelFormat.I420.planeByteWidth(plane, w));
        }
        slotWidth[slot] = w;
    }

    /** @return pooled pictures nobody is holding: every one of them, once a consumer has finished */
    int freeSlots() {
        synchronized (free) {
            int count = 0;
            for (boolean slot : free) {
                if (slot) {
                    count++;
                }
            }
            return count;
        }
    }

    int slots() {
        return free.length;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public PixelFormat pixelFormat() {
        return PixelFormat.I420;
    }

    @Override
    public VideoColor color() {
        return VideoColor.BT709_LIMITED;
    }

    @Override
    public int frameRateNum() {
        return timed ? 30 : 0;
    }

    @Override
    public int frameRateDen() {
        return 1;
    }

    @Override
    public Read readFrame() {
        reads.incrementAndGet();
        if (shutdownComplete) {
            readsAfterShutdown.incrementAndGet();
        }
        CountDownLatch begun = entered;
        if (begun != null) {
            begun.countDown();
        }
        CountDownLatch held = release;
        if (held != null) {
            try {
                held.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Read.END;
            }
        }
        if (failOnRead != null) {
            throw failOnRead;
        }
        if (closed || index >= frameCount) {
            return Read.END;
        }
        if (pendingReads > 0) {
            pendingReads--;
            return Read.PENDING;
        }
        int slot = -1;
        synchronized (free) {
            for (int i = 0; i < free.length && slot < 0; i++) {
                if (free[i]) {
                    slot = i;
                }
            }
            if (slot < 0) {
                return Read.PENDING; // every picture is still held; release one and ask again
            }
            free[slot] = false;
        }
        VideoFrame.Writer writer = writers[slot];
        int wanted = index >= shrinkAfter ? width / 2 : width;
        if (slotWidth[slot] != wanted) {
            shape(slot, wanted, index >= shrinkAfter ? height / 2 : height);
        }
        writer.setPtsMicros(timed ? index * ptsStepMicros : VideoFrame.PTS_UNKNOWN);
        current = writer.publish();
        index++;
        return Read.FRAME;
    }

    @Override
    public VideoFrame frame() {
        return closed ? null : current;
    }

    @Override
    public void reset() {
        if (!rewindable) {
            throw new UnsupportedOperationException("this stream cannot be rewound");
        }
        resets++;
        index = 0;
    }

    @Override
    public boolean canReset() {
        return rewindable;
    }

    @Override
    public boolean canSeek() {
        return seekable;
    }

    /** Lands on the picture whose index covers {@code micros}, and counts the call. */
    @Override
    public void seek(long micros, SeekMode mode) {
        if (!seekable) {
            throw new UnsupportedOperationException("this stream cannot be seeked");
        }
        if (micros < 0) {
            throw new IllegalArgumentException("seek target must not be negative, got " + micros);
        }
        seeks.incrementAndGet();
        seekTargets.add(micros);
        long step = Math.max(1, ptsStepMicros);
        long wanted = mode == SeekMode.EXACT ? (micros + step - 1) / step : micros / step;
        index = (int) Math.min(wanted, Integer.MAX_VALUE);
    }

    @Override
    public void close() {
        closes++;
        closed = true;
        current = null;
    }
}

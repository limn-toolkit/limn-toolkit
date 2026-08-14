package limn.components;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;

import java.nio.ByteBuffer;

/**
 * A stream with no decoder behind it: a real pool of {@link VideoFrame}s handed out and taken back
 * by a real {@link VideoFrame.Recycler}, so a picture a consumer forgets to release costs a slot
 * here exactly as it would against a decoder, and a picture released twice is loud here exactly as
 * it is there.
 *
 * <p>Everything a test wants to steer is a plain field, set before the frame that reads it: how many
 * pictures the stream has, whether it can be rewound, whether it carries timing at all, whether the
 * next read reports "not yet", and whether it throws.
 */
final class TestVideoStream implements VideoStreamSource {

    private final int width;
    private final int height;
    private final VideoFrame.Writer[] writers;
    private final boolean[] free;

    /** Pictures before the end; the default is more than any test asks for. */
    int frameCount = Integer.MAX_VALUE;
    /** Microseconds between consecutive presentation times. */
    long ptsStepMicros = 33_333;
    /** False makes every picture report {@link VideoFrame#PTS_UNKNOWN}. */
    boolean timed = true;
    boolean rewindable = true;
    /** The next read answers {@link Read#PENDING} and clears this. */
    boolean pendingOnce;
    /** Thrown by the next read, and by every read after it. */
    RuntimeException failOnRead;

    boolean seekable = true;
    /** What {@link #rotationDegrees()} reports: how far the picture is turned for display. */
    int rotation;

    int reads;
    int delivered;
    int resets;
    int closes;
    int seeks;
    long seekedTo = -1;
    SeekMode seekedMode;

    private int index;
    private VideoFrame current;
    private boolean closed;

    TestVideoStream(int width, int height) {
        this(width, height, 3);
    }

    TestVideoStream(int width, int height, int slots) {
        this.width = width;
        this.height = height;
        this.writers = new VideoFrame.Writer[slots];
        this.free = new boolean[slots];
        VideoFrame.Recycler recycler = frame -> {
            int slot = frame.slot();
            if (free[slot]) {
                throw new IllegalStateException("pool slot " + slot + " was released twice");
            }
            free[slot] = true;
        };
        for (int slot = 0; slot < slots; slot++) {
            VideoFrame.Writer writer = VideoFrame.Writer.allocate(slot, recycler);
            writer.configure(width, height, PixelFormat.I420, VideoColor.BT709_LIMITED);
            for (int plane = 0; plane < PixelFormat.I420.planeCount(); plane++) {
                int stride = PixelFormat.I420.planeByteWidth(plane, width);
                int bytes = (int) PixelFormat.I420.minPlaneBytes(plane, width, height, stride);
                writer.setPlane(plane, ByteBuffer.allocateDirect(bytes), stride);
            }
            writers[slot] = writer;
            free[slot] = true;
        }
    }

    /** @return pooled pictures nobody is holding: every one of them, once a consumer has finished */
    int freeSlots() {
        int count = 0;
        for (boolean slot : free) {
            if (slot) {
                count++;
            }
        }
        return count;
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
        reads++;
        if (failOnRead != null) {
            throw failOnRead;
        }
        if (closed || index >= frameCount) {
            return Read.END;
        }
        if (pendingOnce) {
            pendingOnce = false;
            return Read.PENDING;
        }
        int slot = -1;
        for (int i = 0; i < free.length && slot < 0; i++) {
            if (free[i]) {
                slot = i;
            }
        }
        if (slot < 0) {
            return Read.PENDING; // every picture is still held; release one and ask again
        }
        free[slot] = false;
        VideoFrame.Writer writer = writers[slot];
        writer.setPtsMicros(timed ? index * ptsStepMicros : VideoFrame.PTS_UNKNOWN);
        current = writer.publish();
        index++;
        delivered++;
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
    public int rotationDegrees() {
        return rotation;
    }

    @Override
    public boolean canSeek() {
        return seekable;
    }

    @Override
    public void seek(long micros, SeekMode mode) {
        if (!seekable) {
            throw new UnsupportedOperationException("this stream cannot be seeked");
        }
        seeks++;
        seekedTo = micros;
        seekedMode = mode;
        long step = Math.max(1, ptsStepMicros);
        index = (int) (mode == SeekMode.EXACT ? (micros + step - 1) / step : micros / step);
    }

    @Override
    public void close() {
        closes++;
        closed = true;
        current = null;
    }
}

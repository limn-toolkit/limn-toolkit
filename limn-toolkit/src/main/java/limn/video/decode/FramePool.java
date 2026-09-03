package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fixed set of pictures a decoder fills and hands out, and the free list that takes them back.
 * Every decoder needs exactly this and none of it is codec-specific: the planes are allocated once,
 * the geometry is configured once, and publishing a picture afterwards writes two primitives.
 *
 * <p>Built for one size and one layout. A stream whose resolution changes builds a new pool; that is
 * a rare event and rebuilding is cheaper than making every steady-state picture pay for the
 * possibility.
 *
 * <p>The planes are direct memory with rows aligned to four bytes, which is what a device upload
 * wants and what lets a picture reach the GPU without an intermediate copy. Each plane holds exactly
 * {@link PixelFormat#minPlaneBytes}: the last row ends at its last sample with no trailing padding,
 * which is legal, is what a tight producer hands over, and is therefore the shape worth exercising
 * rather than the roomier one.
 *
 * <p>Threading: {@link #acquire()} belongs to the single thread that decodes. {@link #recycle} is
 * called by {@link VideoFrame#release()} from whichever thread released, so it is lock-free and
 * never waits; a decoder blocked for a free slot would otherwise stay blocked until it returned.
 */
public final class FramePool implements VideoFrame.Recycler {

    /**
     * Most slots a pool may have. The free list is the bits of one {@code long}, which is what makes
     * handing a slot back a single compare-and-set; a video pool wants a handful of pictures in
     * flight, not sixty-four, so the ceiling costs nothing real.
     */
    public static final int MAX_SLOTS = 64;

    /**
     * Most direct memory one pool may reserve, over all its slots: one gibibyte. A pool is built
     * from a header nobody has checked yet, before a single sample is read, and it commits its
     * pages on construction; without a ceiling, thirty bytes claiming a picture of 32768 by 32768
     * reserved 1.5&nbsp;GiB per slot and took the process down with them. The number is what the
     * largest picture anyone plays needs with room to spare: an 8K frame (7680 by 4320) is
     * 50&nbsp;MiB in 4:2:0 at 8 bits and 200&nbsp;MiB in 4:4:4 at 10 (two bytes a sample), so three of the latter fit.
     */
    public static final long MAX_BYTES = 1L << 30;

    private final int slots;
    private final PixelFormat format;
    private final VideoColor color;
    private final int width;
    private final int height;
    private final VideoFrame.Writer[] writers;
    private final ByteBuffer[][] planes;
    private final int[] strides;

    /** Bit <i>i</i> set means slot <i>i</i> is the producer's to fill. */
    private final AtomicLong free;

    private FramePool(int slots, int width, int height, PixelFormat format, VideoColor color) {
        this.slots = slots;
        this.width = width;
        this.height = height;
        this.format = format;
        this.color = color;
        this.writers = new VideoFrame.Writer[slots];
        this.planes = new ByteBuffer[slots][format.planeCount()];
        this.strides = new int[format.planeCount()];
        for (int plane = 0; plane < format.planeCount(); plane++) {
            strides[plane] = format.alignedStride(plane, width, 4);
        }
        for (int slot = 0; slot < slots; slot++) {
            VideoFrame.Writer writer = VideoFrame.Writer.allocate(slot, this);
            writer.configure(width, height, format, color);
            for (int plane = 0; plane < format.planeCount(); plane++) {
                long wanted = format.minPlaneBytes(plane, width, height, strides[plane]);
                if (wanted > Integer.MAX_VALUE) {
                    // Unreachable under MAX_BYTES, kept because the cast below is the kind that
                    // fails quietly: truncated, it reserved a hundred kilobytes for a plane of
                    // four gigabytes and left the size check downstream to notice.
                    throw new IllegalArgumentException("plane " + plane + " of a " + width + "x"
                            + height + " " + format + " picture needs " + wanted
                            + " bytes, more than one buffer can hold");
                }
                ByteBuffer buffer = ByteBuffer.allocateDirect((int) wanted);
                planes[slot][plane] = buffer;
                writer.setPlane(plane, buffer, strides[plane]);
            }
            writers[slot] = writer;
        }
        this.free = new AtomicLong(slots == MAX_SLOTS ? -1L : (1L << slots) - 1);
    }

    /**
     * Allocates every picture this pool will ever hand out.
     *
     * @param slots  pictures in flight at once, in {@code [1..MAX_SLOTS]}. One means the decoder
     *               stalls until the consumer releases; two lets a consumer hold the picture it is
     *               showing while the next is produced, which is the smallest useful number.
     * @param width  picture width in pixels, in {@code [1..PixelFormat.MAX_DIMENSION]}
     * @param height picture height in pixels, in the same range
     * @throws IllegalArgumentException if {@code slots} or a dimension is out of range, or the
     *                                  pool would reserve more than {@link #MAX_BYTES}
     * @throws NullPointerException     if {@code format} or {@code color} is null
     * @throws OutOfMemoryError         if the direct memory for the planes cannot be reserved
     */
    public static FramePool of(int slots, int width, int height, PixelFormat format,
                               VideoColor color) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(color, "color");
        long bytes = bytesFor(slots, width, height, format);
        if (bytes > MAX_BYTES) {
            throw new IllegalArgumentException(slots + " pictures of " + width + "x" + height
                    + " in " + format + " would reserve " + (bytes >> 20) + " MiB, above the "
                    + (MAX_BYTES >> 20) + " MiB a pool may hold");
        }
        return new FramePool(slots, width, height, format, color);
    }

    /**
     * The direct memory {@link #of} would reserve for these arguments, computed without reserving
     * any of it: what a source that has just read a header, and nothing else, asks before it
     * commits to the header's word.
     *
     * @throws IllegalArgumentException if {@code slots} or a dimension is out of range
     * @throws NullPointerException     if {@code format} is null
     */
    public static long bytesFor(int slots, int width, int height, PixelFormat format) {
        Objects.requireNonNull(format, "format");
        if (slots < 1 || slots > MAX_SLOTS) {
            throw new IllegalArgumentException(
                    "slots must be in [1.." + MAX_SLOTS + "], got " + slots);
        }
        long bytes = 0;
        for (int plane = 0; plane < format.planeCount(); plane++) {
            int stride = format.alignedStride(plane, width, 4);
            bytes += format.minPlaneBytes(plane, width, height, stride);
        }
        return bytes * slots;
    }

    /** @return pictures this pool owns, free or not */
    public int slots() {
        return slots;
    }

    /** @return the layout every picture from this pool uses */
    public PixelFormat format() {
        return format;
    }

    /** @return the interpretation every picture from this pool carries */
    public VideoColor color() {
        return color;
    }

    /** @return picture width in pixels */
    public int width() {
        return width;
    }

    /** @return picture height in pixels */
    public int height() {
        return height;
    }

    /**
     * Distance in <em>bytes</em> between the starts of consecutive rows of {@code plane}: the
     * plane's byte width rounded up to four, not its sample count.
     *
     * @throws IndexOutOfBoundsException if {@code plane} is not a plane of {@link #format()}
     */
    public int stride(int plane) {
        return strides[checkPlane(plane)];
    }

    /**
     * A slot to fill, or null when every picture is still held by a consumer, which a source
     * reports as {@link VideoStreamSource.Read#PENDING} rather than as an end or an error. Cheap and
     * non-blocking either way: it never waits for a consumer, so a caller that gets null must return
     * to its own loop rather than spin here.
     *
     * <p>Decode thread only. The returned writer is the pool's and is valid until the picture it
     * publishes is released, at which point the same writer becomes acquirable again.
     *
     * @return a writer whose planes are the caller's to fill, or null
     */
    public VideoFrame.Writer acquire() {
        while (true) {
            long mask = free.get();
            if (mask == 0) {
                return null;
            }
            int slot = Long.numberOfTrailingZeros(mask);
            if (free.compareAndSet(mask, mask & ~(1L << slot))) {
                return writers[slot];
            }
        }
    }

    /**
     * The writable memory of one plane of one slot: the buffer the caller fills before publishing.
     * The picture's own {@link VideoFrame#plane(int)} is a read-only view of this same memory, so
     * writing here after publishing changes a picture a consumer is already looking at.
     *
     * <p>Position and limit are the caller's to move: they are not shared with the picture's view,
     * which {@link VideoFrame.Writer#publish()} rewinds on its own.
     *
     * @throws IndexOutOfBoundsException if {@code slot} or {@code plane} is not one of this pool's
     */
    public ByteBuffer planeOf(int slot, int plane) {
        if (slot < 0 || slot >= slots) {
            throw new IndexOutOfBoundsException(
                    "pool has slots 0.." + (slots - 1) + ", got " + slot);
        }
        return planes[slot][checkPlane(plane)];
    }

    /**
     * Takes a released picture's slot back. Any thread, lock-free, and never called by a decoder
     * directly; {@link VideoFrame#release()} calls it, exactly once per delivered picture.
     *
     * @throws IllegalArgumentException if the frame belongs to another pool, or its slot was already
     *                                  free. The second is the double-release that hands one slot to
     *                                  two producers, and the picture tearing it causes appears
     *                                  nowhere near the call that caused it.
     */
    @Override
    public void recycle(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        int slot = frame.slot();
        if (slot < 0 || slot >= slots || writers[slot].frame() != frame) {
            throw new IllegalArgumentException(
                    "frame " + frame + " does not belong to this pool");
        }
        freeSlot(slot);
    }

    /**
     * Gives back a slot that was acquired and then not published: the decoder asked for somewhere
     * to put a picture and found there was no picture to put there, which is what reaching the end
     * of an input looks like from the inside. Without it the slot would be lost for the life of the
     * pool, and a source that hit the end twice with a two-slot pool would stall forever afterwards.
     *
     * <p>Decode thread only, and only for a writer this pool handed out and whose frame has not been
     * published since.
     *
     * @throws IllegalArgumentException if the writer is not this pool's, or its slot is already free
     * @throws IllegalStateException    if the frame was published and is held by a consumer
     */
    public void abandon(VideoFrame.Writer writer) {
        Objects.requireNonNull(writer, "writer");
        VideoFrame frame = writer.frame();
        int slot = frame.slot();
        if (slot < 0 || slot >= slots || writers[slot] != writer) {
            throw new IllegalArgumentException("writer for slot " + slot + " is not this pool's");
        }
        if ((frame.generation() & 1L) != 0L) {
            throw new IllegalStateException(
                    "pool slot " + slot + " was published; release it instead of abandoning it");
        }
        freeSlot(slot);
    }

    private void freeSlot(int slot) {
        long bit = 1L << slot;
        while (true) {
            long mask = free.get();
            if ((mask & bit) != 0) {
                throw new IllegalArgumentException("pool slot " + slot + " was already free");
            }
            if (free.compareAndSet(mask, mask | bit)) {
                return;
            }
        }
    }

    /** @return pictures currently free, for a diagnostic or a test; changes under any consumer */
    public int freeSlots() {
        return Long.bitCount(free.get());
    }

    private int checkPlane(int plane) {
        if (plane < 0 || plane >= format.planeCount()) {
            throw new IndexOutOfBoundsException(
                    format + " has planes 0.." + (format.planeCount() - 1) + ", got " + plane);
        }
        return plane;
    }
}

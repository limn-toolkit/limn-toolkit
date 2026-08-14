package limn.video.ffmpeg;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;

import java.nio.ByteBuffer;

/**
 * The pooled pictures a native decoder hands out, {@code FramePool}'s counterpart for memory
 * this side did not allocate.
 *
 * <p>{@code FramePool} is deliberately not reused here, and the reason is the one thing worth
 * knowing about this class: <b>it allocates its own direct buffers</b>. A decoder whose pictures
 * already live in memory it owns would have to copy each one into those buffers, which is a copy
 * per picture (3.1 MB of it at 1080p, thirty times a second) introduced to reuse about eighty
 * lines. So the free list stays where the memory is, on the far side of the boundary, and what
 * lives here is only the bookkeeping that turns a slot index into a {@code VideoFrame}.
 *
 * <p>What that bookkeeping has to get right is <b>when to rebind a plane</b>. A decoded picture
 * lands on whichever buffer libavcodec's own pool had free, so a slot does not keep the same
 * address from one picture to the next, and a frame's planes cannot simply be pointed once at
 * construction. But rebinding is not free either: {@code VideoFrame.Writer.setPlane} stores a
 * read-only view, and creating one per plane per picture is ninety allocations a second.
 *
 * <p>The answer is the epoch the shim reports. It changes only when that slot's planes actually
 * moved, so {@link #publish} rebinds during the first few pictures (while the decoder's pool is
 * still filling) and never again. In steady state publishing a picture writes one {@code long}
 * and flips one counter, which is what the allocation probe over a running decode asserts.
 *
 * <p>Decode thread only, except for the recycler: {@code VideoFrame.release()} reaches the shim
 * from whichever thread released, exactly as the SPI says it may, and carries the slot index and
 * never an address.
 */
final class NativeFrames {

    private final FfmpegMedia media;
    private final PixelFormat format;
    private final VideoFrame.Writer[] writers;

    /** The epoch each slot's planes were last bound at; -1 until they have been bound at all. */
    private final long[] bound;

    /**
     * One scratch array per slot for {@link #download}, allocated here rather than per call.
     * Per slot rather than one shared: a download runs on whichever thread holds the frame, and
     * two consumers holding two pictures may be reading them back at the same moment.
     */
    private final long[][] downloadScratch;

    NativeFrames(FfmpegMedia media, int slots, int width, int height, PixelFormat format,
                 VideoColor color) {
        this.media = media;
        this.format = format;
        this.writers = new VideoFrame.Writer[slots];
        this.bound = new long[slots];
        this.downloadScratch = new long[slots][FfmpegNative.READ_LENGTH];
        for (int slot = 0; slot < slots; slot++) {
            // The recycler carries the slot index and nothing else, which is what the SPI
            // designed it for: "a native producer's implementation is one call passing that
            // integer across the boundary". This is that call.
            VideoFrame.Writer writer = VideoFrame.Writer.allocate(slot, frame ->
                    media.releaseVideo(frame.slot()));
            // Geometry and interpretation are set once. Doing it per picture would invalidate
            // every plane binding and force the rebind this class exists to avoid.
            writer.configure(width, height, format, color);
            // Route A, on demand: what a consumer that cannot bind a device handle gets when it
            // asks. Installed on every stream and not only the hardware ones, because a stream
            // that falls back to software mid-flight is still the same pool of slots.
            writer.setDownloader(this::download);
            writers[slot] = writer;
            bound[slot] = -1;
        }
    }

    /**
     * Publishes the picture the shim just decoded into {@code slot}.
     *
     * <p>Two shapes arrive here and the handle decides which. A hardware picture is one field
     * (the IOSurface) written per picture, which allocates nothing; a planar one is the plane
     * rebinding the epoch governs.
     *
     * @param epoch   the slot's binding epoch, as the shim reported it; a value different from the
     *                one this slot was last bound at means the planes moved and must be re-pointed
     * @param strides the array {@code readVideo} filled, read from {@link FfmpegNative#R_STRIDE_0}
     * @return the frame, now held by whoever receives it and released exactly once
     */
    VideoFrame publish(int slot, long epoch, long ptsMicros, long[] strides) {
        VideoFrame.Writer writer = writers[slot];
        long handle = strides[FfmpegNative.R_HANDLE];
        if (handle != 0L) {
            writer.setHandle(VideoFrame.Kind.IO_SURFACE, handle);
            // The planes this slot may have carried are gone with the picture, so the next planar
            // one (or a download of this one) must rebind rather than trust an epoch.
            bound[slot] = -1;
        } else if (bound[slot] != epoch) {
            for (int plane = 0; plane < format.planeCount(); plane++) {
                ByteBuffer buffer = media.planeBuffer(slot, plane);
                writer.setPlane(plane, buffer,
                        (int) strides[FfmpegNative.R_STRIDE_0 + plane]);
            }
            bound[slot] = epoch;
        }
        writer.setPtsMicros(ptsMicros);
        return writer.publish();
    }

    /**
     * Reads a held hardware picture back into memory and re-points its planes, {@code toPlanar}'s
     * far side.
     *
     * <p>Whichever thread holds the frame, which is why nothing here touches {@link #bound}: that
     * array belongs to the decode thread's publish path, and a download deliberately leaves it at
     * -1 so the next picture in this slot rebinds from scratch.
     */
    private void download(VideoFrame frame) {
        int slot = frame.slot();
        long[] scratch = downloadScratch[slot];
        media.downloadVideo(slot, scratch);
        VideoFrame.Writer writer = writers[slot];
        for (int plane = 0; plane < format.planeCount(); plane++) {
            writer.setPlane(plane, media.planeBuffer(slot, plane),
                    (int) scratch[FfmpegNative.R_STRIDE_0 + plane]);
        }
        writer.downloaded();
    }

    /**
     * Forgets every binding, so the next picture in each slot re-points its planes.
     *
     * <p>Needed after a rewind: flushing the decoder returns its buffers to its pool, and the
     * addresses that come back afterwards may be the ones these slots are already pointing at.
     * The epoch would then be unchanged and the planes would look bound when they are not; that
     * is the one way a stale picture could reach the screen, so it is answered here rather than
     * relied upon not to happen.
     */
    void invalidate() {
        java.util.Arrays.fill(bound, -1);
    }

    /** @return the frame in {@code slot}, for a caller that needs it without publishing */
    VideoFrame frameAt(int slot) {
        return writers[slot].frame();
    }
}

package limn.video.ffmpeg;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;

/**
 * The pictures of an open container, pulled one at a time.
 *
 * <p>Every metadata accessor is a field read of something settled when the container was opened,
 * which is what the SPI requires and what lets a view be laid out before anything is decoded.
 * Reaching that point costs a real read of the file (libavformat decodes actual data to fill in
 * what an MP4 header leaves out), and that cost is paid in {@code openStream} and never in
 * {@code supports}.
 *
 * <p><b>Closing this closes the container</b>, including its soundtrack. A caller that reached
 * this through {@code Videos.open} has never seen the container object and closing what it was
 * handed has to work, so this is the one thing it can mean.
 *
 * <p>Threading is the SPI's: {@link #readFrame()}, {@link #reset()} and {@link #close()} come from
 * one thread, serialized by whoever owns the stream, and {@link VideoFrame#release()} may come
 * from any thread at all.
 */
final class FfmpegVideoStream implements VideoStreamSource {

    private final FfmpegMedia media;
    private final int width;
    private final int height;
    private final PixelFormat format;
    private final VideoColor color;
    private final int rateNum;
    private final int rateDen;
    private final long durationMicros;
    private final int rotationDegrees;
    private final NativeFrames frames;

    /**
     * Filled by every read and never reallocated. A picture crossing the boundary is five
     * {@code long}s written into this, which is what makes the steady state allocation-free.
     */
    private final long[] scratch = new long[FfmpegNative.READ_LENGTH];

    private VideoFrame current;
    private boolean ended;

    FfmpegVideoStream(FfmpegMedia media, int width, int height, PixelFormat format,
                      VideoColor color, int rateNum, int rateDen, long durationMicros,
                      int rotationDegrees, int slots) {
        this.media = media;
        this.width = width;
        this.height = height;
        this.format = format;
        this.color = color;
        this.rateNum = rateNum;
        this.rateDen = rateDen;
        this.durationMicros = durationMicros;
        this.rotationDegrees = rotationDegrees;
        this.frames = new NativeFrames(media, slots, width, height, format, color);
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
        return format;
    }

    @Override
    public VideoColor color() {
        return color;
    }

    @Override
    public int frameRateNum() {
        return rateNum;
    }

    @Override
    public int frameRateDen() {
        return rateDen;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    @Override
    public int rotationDegrees() {
        return rotationDegrees;
    }

    @Override
    public Read readFrame() {
        if (ended) {
            return Read.END;
        }
        int slot = media.readVideo(scratch);
        if (slot == FfmpegNative.READ_PENDING) {
            return Read.PENDING;
        }
        if (slot == FfmpegNative.READ_END) {
            ended = true;
            return Read.END;
        }
        current = frames.publish(slot, scratch[FfmpegNative.R_EPOCH],
                scratch[FfmpegNative.R_PTS_MICROS], scratch);
        return Read.FRAME;
    }

    @Override
    public VideoFrame frame() {
        return current;
    }

    @Override
    public void reset() {
        media.resetVideo();
        // The decoder's buffers went back to its pool when it was flushed, so the addresses that
        // come back may be the ones these slots already point at: an unchanged epoch over
        // re-pointed memory. Forgetting the bindings is what stops that from looking bound.
        frames.invalidate();
        current = null;
        ended = false;
    }

    /**
     * @return true. A container libavformat opened as MP4 has already had its index read from the
     *         end of the file, so the input was seekable before this stream existed; there is no
     *         MP4 this decoder can open and cannot rewind. A seek that fails anyway throws from
     *         {@link #reset()} rather than being reported here as a possibility.
     */
    @Override
    public boolean canReset() {
        return true;
    }

    /**
     * @return true, for the reason {@link #canReset()} gives: the input was seekable before this
     *         stream existed or the container would not have opened
     */
    @Override
    public boolean canSeek() {
        return true;
    }

    /**
     * <b>Costs almost nothing here and the cost lands in the next read.</b> The container is placed
     * on an independently decodable picture at or before the target by this call;
     * {@link SeekMode#EXACT} then makes the next {@link #readFrame()} decode and discard everything
     * between that picture and the target, so a caller timing a seek measures the wrong call.
     */
    @Override
    public void seek(long micros, SeekMode mode) {
        if (micros < 0) {
            throw new IllegalArgumentException("seek target must not be negative, got " + micros);
        }
        media.seekVideo(micros, mode == SeekMode.EXACT);
        // Same reason as reset(): the decoder's buffers went back to its pool when it was flushed,
        // so the addresses coming back may be the ones these slots already point at, which is an
        // unchanged epoch over re-pointed memory.
        frames.invalidate();
        current = null;
        ended = false;
    }

    @Override
    public void close() {
        ended = true;
        current = null;
        media.close();
    }
}

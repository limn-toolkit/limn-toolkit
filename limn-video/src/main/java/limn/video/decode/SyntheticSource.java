package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;

import java.nio.ByteBuffer;

/**
 * Draws {@link SyntheticPattern} pictures into pooled frames. Every sample is a pure function of its
 * coordinates and the picture's index, so the whole stream is reproducible from the spec alone and
 * nothing about it depends on a clock, a file or a random seed.
 */
final class SyntheticSource implements VideoStreamSource {

    private final SyntheticSpec spec;
    private final FramePool pool;

    private int nextIndex;
    private VideoFrame current;
    private boolean closed;

    SyntheticSource(SyntheticSpec spec) {
        this.spec = spec;
        this.pool = FramePool.of(spec.slots(), spec.width(), spec.height(), spec.format(),
                spec.color());
    }

    @Override
    public int width() {
        return spec.width();
    }

    @Override
    public int height() {
        return spec.height();
    }

    @Override
    public PixelFormat pixelFormat() {
        return spec.format();
    }

    @Override
    public VideoColor color() {
        return spec.color();
    }

    @Override
    public int frameRateNum() {
        return spec.frameRateNum();
    }

    @Override
    public int frameRateDen() {
        return spec.frameRateDen();
    }

    @Override
    public long durationMicros() {
        return spec.durationMicros();
    }

    @Override
    public Read readFrame() {
        if (closed || (spec.frameCount() > 0 && nextIndex >= spec.frameCount())) {
            return Read.END;
        }
        VideoFrame.Writer writer = pool.acquire();
        if (writer == null) {
            return Read.PENDING; // every picture is still held; release one and ask again
        }
        int index = nextIndex++;
        draw(writer.frame().slot(), index);
        writer.setPtsMicros(spec.ptsMicrosOf(index));
        current = writer.publish();
        return Read.FRAME;
    }

    @Override
    public VideoFrame frame() {
        return closed ? null : current;
    }

    @Override
    public void reset() {
        nextIndex = 0;
    }

    /**
     * @return always true. Every picture is a pure function of its index, so any of them can be
     *         produced without producing the ones before it, which makes both modes exact and
     *         makes a seek cost nothing at all.
     */
    @Override
    public boolean canSeek() {
        return true;
    }

    /**
     * Costs nothing but an index: no picture here depends on any other, so the seek is the
     * arithmetic that inverts {@link SyntheticSpec#ptsMicrosOf} and no drawing happens until the
     * next read.
     */
    @Override
    public void seek(long micros, SeekMode mode) {
        if (micros < 0) {
            throw new IllegalArgumentException("seek target must not be negative, got " + micros);
        }
        nextIndex = mode == SeekMode.EXACT
                ? FrameIndex.atOrAfter(micros, spec.frameRateNum(), spec.frameRateDen())
                : FrameIndex.atOrBefore(micros, spec.frameRateNum(), spec.frameRateDen());
    }

    @Override
    public void close() {
        closed = true;
        current = null;
    }

    /**
     * Writes picture {@code index} into every plane of {@code slot}. Sample by sample rather than by
     * row block: the pattern is a function of the coordinates, and the whole point of this source is
     * that what lands in memory is what the arithmetic says, with no shortcut in between that could
     * disagree with it.
     */
    private void draw(int slot, int index) {
        PixelFormat format = spec.format();
        SyntheticPattern pattern = spec.pattern();
        int width = spec.width();
        int height = spec.height();

        int bitDepth = format.bitDepth();

        ByteBuffer luma = pool.planeOf(slot, 0);
        int lumaStride = pool.stride(0);
        // Every offset below is in BYTES and every step comes from the layout, because a component
        // is one byte at eight bits and two at ten. Writing through the format is also what keeps
        // the byte order in one place instead of in every producer.
        int lumaStep = format.bytesPerSample(0);
        for (int y = 0; y < height; y++) {
            int row = y * lumaStride;
            for (int x = 0; x < width; x++) {
                format.putComponent(luma, row + x * lumaStep,
                        pattern.luma(x, y, width, height, index, bitDepth));
            }
        }

        int shiftX = format.chromaShiftX();
        int shiftY = format.chromaShiftY();
        int columns = format.planeWidth(1, width);
        int rows = format.planeHeight(1, height);
        boolean interleaved = format.planeCount() == 2;
        ByteBuffer cbPlane = pool.planeOf(slot, 1);
        ByteBuffer crPlane = interleaved ? cbPlane : pool.planeOf(slot, 2);
        int cbStride = pool.stride(1);
        int crStride = interleaved ? cbStride : pool.stride(2);
        int step = format.bytesPerSample(1);
        int crOffset = interleaved ? step / format.componentsPerSample(1) : 0;
        for (int cy = 0; cy < rows; cy++) {
            int cbRow = cy * cbStride;
            int crRow = cy * crStride + crOffset;
            // The luma pixel a chroma sample covers the top-left of. It always exists: a chroma
            // plane's dimensions are the frame's rounded up, so the last sample of an odd-sized
            // picture covers one real pixel and one that is not there.
            int y = cy << shiftY;
            for (int cx = 0; cx < columns; cx++) {
                int x = cx << shiftX;
                format.putComponent(cbPlane, cbRow + cx * step,
                        pattern.cb(x, y, width, height, index, bitDepth));
                format.putComponent(crPlane, crRow + cx * step,
                        pattern.cr(x, y, width, height, index, bitDepth));
            }
        }
    }
}

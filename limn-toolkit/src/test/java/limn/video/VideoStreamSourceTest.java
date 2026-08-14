package limn.video;

import limn.video.VideoStreamSource.Read;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pull protocol of {@link VideoStreamSource} over a fake source: what the end of a stream and a
 * close leave behind, that a pending read is not an end, and that metadata answered before the
 * first picture still answers after the last.
 */
class VideoStreamSourceTest {

    private static final int WIDTH = 8;

    private static final int HEIGHT = 4;

    private static final long FRAME_INTERVAL_MICROS = 33_333;

    @Test
    void endKeepsReturningEndAndPreservesTheLastFrame() {
        FakeSource source = new FakeSource(3, 0);
        VideoFrame last = null;
        for (int picture = 0; picture < 3; picture++) {
            assertSame(Read.FRAME, source.readFrame());
            last = source.frame();
            assertNotNull(last);
            if (picture < 2) {
                last.release();
            }
        }

        assertSame(Read.END, source.readFrame());
        assertSame(Read.END, source.readFrame(), "the end is not a one-off answer");

        long generation = last.generation();
        assertSame(last, source.frame(), "the last picture is still on loan");
        assertEquals(generation, source.frame().generation(),
                "the source may not refill a slot the consumer has not handed back");
        assertEquals(2 * FRAME_INTERVAL_MICROS, source.frame().ptsMicros());
        assertNotNull(source.frame().plane(0), "the final image is still readable, so it can stay on screen");

        last.release();
    }

    @Test
    void closeIsIdempotentAndLeavesFrameNullAndReadEnd() {
        FakeSource source = new FakeSource(2, 0);
        assertSame(Read.FRAME, source.readFrame());
        source.frame().release();

        source.close();
        source.close();

        assertNull(source.frame(), "a cleanup block needs no ordering care");
        assertSame(Read.END, source.readFrame());
    }

    @Test
    void metadataIsConstantAcrossReads() {
        FakeSource source = new FakeSource(2, 0);
        int width = source.width();
        int height = source.height();
        PixelFormat format = source.pixelFormat();
        VideoColor color = source.color();
        int rateNum = source.frameRateNum();
        int rateDen = source.frameRateDen();

        while (source.readFrame() == Read.FRAME) {
            source.frame().release();
        }

        assertEquals(width, source.width());
        assertEquals(height, source.height());
        assertSame(format, source.pixelFormat());
        assertSame(color, source.color());
        assertEquals(rateNum, source.frameRateNum());
        assertEquals(rateDen, source.frameRateDen());
        source.close();
    }

    @Test
    void durationUnknownIsMinusOne() {
        assertEquals(-1L, VideoStreamSource.DURATION_UNKNOWN);

        FakeSource source = new FakeSource(1, 0);
        assertEquals(VideoStreamSource.DURATION_UNKNOWN, source.durationMicros(),
                "a source that does not answer inherits the honest unknown");
        source.close();
    }

    @Test
    void resetIsOfferedUnlessASourceDeclinesIt() {
        FakeSource rewindable = new FakeSource(1, 0);
        assertTrue(rewindable.canReset(),
                "a source that does not answer offers looping; the default is what a player asks first");
        rewindable.reset();
        rewindable.close();

        PipeSource live = new PipeSource();
        assertFalse(live.canReset());
        assertThrows(UnsupportedOperationException.class, live::reset,
                "a player decides up front rather than discovering this as a stall at the end of the input");
        live.close();
    }

    @Test
    void pendingIsNotTheEnd() {
        FakeSource source = new FakeSource(1, 2);

        assertSame(Read.PENDING, source.readFrame());
        assertSame(Read.PENDING, source.readFrame());
        assertNull(source.frame(), "nothing has been produced yet");

        Read read = Read.PENDING;
        for (int attempt = 0; attempt < 10 && read == Read.PENDING; attempt++) {
            read = source.readFrame();
        }

        assertSame(Read.FRAME, read, "a retry loop must reach the picture a pending read promised");
        assertNotNull(source.frame());
        source.frame().release();
        source.close();
    }

    /** A live input with nothing behind it to rewind, so it declines looping before playback starts. */
    private static final class PipeSource implements VideoStreamSource {

        @Override
        public int width() {
            return WIDTH;
        }

        @Override
        public int height() {
            return HEIGHT;
        }

        @Override
        public PixelFormat pixelFormat() {
            return PixelFormat.I420;
        }

        @Override
        public VideoColor color() {
            return VideoColor.unspecified();
        }

        @Override
        public int frameRateNum() {
            return 30;
        }

        @Override
        public int frameRateDen() {
            return 1;
        }

        @Override
        public Read readFrame() {
            return Read.PENDING;
        }

        @Override
        public VideoFrame frame() {
            return null;
        }

        @Override
        public boolean canReset() {
            return false;
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("a live input cannot be rewound");
        }

        @Override
        public void close() {
        }
    }

    /**
     * A one-slot source: it produces a fixed number of pictures, refuses to refill the slot while the
     * consumer holds it, and can be told to answer pending a few times first.
     */
    private static final class FakeSource implements VideoStreamSource {

        private final VideoFrame.Writer writer;
        private final int pictures;

        private int pendingBudget;
        private int produced;
        private boolean held;
        private VideoFrame current;
        private boolean closed;

        FakeSource(int pictures, int pendingBudget) {
            this.pictures = pictures;
            this.pendingBudget = pendingBudget;
            this.writer = VideoFrame.Writer.allocate(0, frame -> held = false);
            writer.configure(WIDTH, HEIGHT, PixelFormat.I420, VideoColor.BT709_LIMITED);
            for (int plane = 0; plane < PixelFormat.I420.planeCount(); plane++) {
                int stride = PixelFormat.I420.planeByteWidth(plane, WIDTH);
                long bytes = PixelFormat.I420.minPlaneBytes(plane, WIDTH, HEIGHT, stride);
                writer.setPlane(plane, ByteBuffer.allocate((int) bytes), stride);
            }
        }

        @Override
        public int width() {
            return WIDTH;
        }

        @Override
        public int height() {
            return HEIGHT;
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
            return 0;
        }

        @Override
        public int frameRateDen() {
            return 1;
        }

        @Override
        public Read readFrame() {
            if (closed || produced == pictures) {
                return Read.END;
            }
            if (pendingBudget > 0) {
                pendingBudget--;
                return Read.PENDING;
            }
            if (held) {
                return Read.PENDING;
            }
            writer.setPtsMicros(produced * FRAME_INTERVAL_MICROS);
            current = writer.publish();
            held = true;
            produced++;
            return Read.FRAME;
        }

        @Override
        public VideoFrame frame() {
            return closed ? null : current;
        }

        @Override
        public void reset() {
            produced = 0;
        }

        @Override
        public void close() {
            closed = true;
            current = null;
        }
    }
}

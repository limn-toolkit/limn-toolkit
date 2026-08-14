package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Writes what a source produces as a YUV4MPEG2 file: the counterpart that makes {@link Y4mDecoder}
 * verifiable, and the only way to get a real video file out of this project without shipping one.
 *
 * <p>It exists because no media may be committed to this repository. A test that needs a file writes
 * one first, from a source whose every sample it can predict, and reads it back; the assertion is
 * then about the reader and the writer rather than about a blob someone downloaded once. It is also
 * how a picture leaves the process for a human to look at, since the format is what every video tool
 * reads.
 *
 * <p><b>Two things do not survive the trip, and both are the container's limits rather than this
 * writer's.</b> YUV4MPEG2 has no way to say which luma/chroma matrix a stream was encoded with, so a
 * BT.601 stream written here reads back as unsignalled; only the range is carried, through FFmpeg's
 * {@code XCOLORRANGE} extension, and only when the source signalled one. And there is no tag for a
 * two-plane layout, so {@link PixelFormat#NV12} is refused rather than silently de-interleaved into
 * something whose samples would no longer be the source's.
 */
public final class Y4mWriter {

    private Y4mWriter() {
    }

    /**
     * Drains {@code source} into {@code file}, creating or truncating it.
     *
     * <p>Every picture is released as soon as it is written, so a source with a single slot works.
     * {@link VideoStreamSource.Read#PENDING} is answered by asking again (the caller holds
     * nothing), and a source that only ever answers that is abandoned rather than spun on forever.
     *
     * @param maxFrames most pictures to write, at least 1. A source that never ends needs this;
     *                  a finite one stops at its own end, whichever comes first.
     * @return pictures written
     * @throws UnsupportedOperationException if the source's layout has no YUV4MPEG2 tag
     * @throws IllegalStateException         if the source answers {@code PENDING} indefinitely
     * @throws UncheckedIOException          if the file cannot be written
     * @throws NullPointerException          if {@code file} or {@code source} is null
     * @throws IllegalArgumentException      if {@code maxFrames} is below 1
     */
    public static int write(Path file, VideoStreamSource source, int maxFrames) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(source, "source");
        if (maxFrames < 1) {
            throw new IllegalArgumentException("maxFrames must be at least 1, got " + maxFrames);
        }
        String colorTag = colorTagOf(source.pixelFormat());
        try (SeekableByteChannel channel = Files.newByteChannel(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writeAscii(channel, header(source, colorTag));
            int written = 0;
            int pending = 0;
            while (written < maxFrames) {
                VideoStreamSource.Read read = source.readFrame();
                if (read == VideoStreamSource.Read.END) {
                    break;
                }
                if (read == VideoStreamSource.Read.PENDING) {
                    if (++pending > 1024) {
                        throw new IllegalStateException(
                                "source produced nothing after 1024 PENDING answers");
                    }
                    continue;
                }
                pending = 0;
                writeAscii(channel, "FRAME\n");
                VideoFrame frame = source.frame();
                try {
                    writePlanes(channel, frame);
                } finally {
                    frame.release();
                }
                written++;
            }
            return written;
        } catch (IOException error) {
            throw new UncheckedIOException("cannot write " + file, error);
        }
    }

    private static String header(VideoStreamSource source, String colorTag) {
        StringBuilder header = new StringBuilder("YUV4MPEG2")
                .append(" W").append(source.width())
                .append(" H").append(source.height());
        if (source.frameRateNum() > 0) {
            // Omitted rather than invented when the source does not know it: a rate written here is
            // read back as fact by every tool that opens the file.
            header.append(" F").append(source.frameRateNum()).append(':')
                    .append(source.frameRateDen());
        }
        header.append(" Ip A1:1 C").append(colorTag);
        VideoColor color = source.color();
        if (color.isSpecified()) {
            header.append(" XCOLORRANGE=").append(color.range());
        }
        return header.append('\n').toString();
    }

    private static String colorTagOf(PixelFormat format) {
        return switch (format) {
            case I420 -> "420";
            case I444 -> "444";
            // The container's own spelling of a wide sample, and it is little-endian, which is
            // also how the planes already lie, so the rows below are copied and never swapped.
            case I420_10LE -> "420p10";
            case I444_10LE -> "444p10";
            case NV12, P010 -> throw new UnsupportedOperationException(
                    "YUV4MPEG2 has no tag for a two-plane layout, so " + format + " cannot be "
                            + "written: convert to I420 first, or write a three-plane source.");
        };
    }

    /** Writes the picture tightly: no row padding, which is what the container is. */
    private static void writePlanes(SeekableByteChannel channel, VideoFrame frame)
            throws IOException {
        PixelFormat format = frame.format();
        for (int plane = 0; plane < format.planeCount(); plane++) {
            // A duplicate, because the plane view's position and limit are shared with whoever else
            // reads this picture and are reset only when the slot is published again.
            ByteBuffer source = frame.plane(plane).duplicate();
            int stride = frame.stride(plane);
            int byteWidth = format.planeByteWidth(plane, frame.width());
            int rows = format.planeHeight(plane, frame.height());
            for (int row = 0; row < rows; row++) {
                source.limit(row * stride + byteWidth).position(row * stride);
                while (source.hasRemaining()) {
                    channel.write(source);
                }
            }
        }
    }

    private static void writeAscii(SeekableByteChannel channel, String text) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }
}

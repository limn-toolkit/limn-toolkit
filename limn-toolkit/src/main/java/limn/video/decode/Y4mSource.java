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
import java.util.Locale;

/**
 * Reads YUV4MPEG2 out of a seekable channel, one picture at a time, into pooled frames.
 *
 * <p>The container is a text header line, then a {@code FRAME} line and the planes back to back per
 * picture, with no padding anywhere. Rows in the file are therefore tight, while the pool's rows are
 * aligned for the device, so each row is transferred on its own rather than the plane in one block.
 */
final class Y4mSource implements VideoStreamSource {

    /** Longest header or {@code FRAME} line accepted, in bytes, before the input is called malformed. */
    private static final int MAX_LINE_BYTES = 4096;

    private static final String MAGIC = "YUV4MPEG2";

    private static final int BUFFER_BYTES = 1 << 16;

    private final Path file;
    private final SeekableByteChannel channel;
    private final ByteBuffer input;
    private final byte[] lineScratch = new byte[MAX_LINE_BYTES];
    private final long dataStart;
    private final boolean resettable;
    private final int width;
    private final int height;
    private final int frameRateNum;
    private final int frameRateDen;
    private final PixelFormat format;
    private final VideoColor color;
    private final FramePool pool;
    /** Payload bytes of one picture: the planes back to back, tight, with no padding anywhere. */
    private final long pictureBytes;

    private int frameIndex;
    private VideoFrame current;
    private boolean ended;
    private boolean closed;

    private Y4mSource(Path file, SeekableByteChannel channel, ByteBuffer input, Header header,
                      long dataStart, boolean resettable, VideoColor color, int slots) {
        this.file = file;
        this.channel = channel;
        this.input = input;
        this.dataStart = dataStart;
        this.resettable = resettable;
        this.width = header.width;
        this.height = header.height;
        this.frameRateNum = header.rateNum;
        this.frameRateDen = header.rateDen;
        this.format = header.format;
        this.color = color;
        this.pool = FramePool.of(slots, header.width, header.height, header.format, color);
        long bytes = 0;
        for (int plane = 0; plane < header.format.planeCount(); plane++) {
            bytes += (long) header.format.planeByteWidth(plane, header.width)
                    * header.format.planeHeight(plane, header.height);
        }
        this.pictureBytes = bytes;
    }

    /**
     * Opens {@code file} and reads its header, so that every metadata accessor is answerable before
     * the first picture is decoded.
     *
     * @param override the interpretation to report, or null to take what the header implies
     */
    static Y4mSource open(Path file, VideoColor override, int slots) {
        SeekableByteChannel channel = null;
        try {
            channel = Files.newByteChannel(file);
            ByteBuffer input = ByteBuffer.allocateDirect(BUFFER_BYTES);
            input.limit(0); // nothing read yet
            Header header = readHeader(file, channel, input);
            long consumed = channel.position() - input.remaining();
            boolean resettable = Files.isRegularFile(file);
            VideoColor color = override != null ? override : header.color;
            return new Y4mSource(file, channel, input, header, consumed, resettable, color, slots);
        } catch (IOException error) {
            closeQuietly(channel);
            throw new UncheckedIOException("cannot read " + file, error);
        } catch (RuntimeException error) {
            closeQuietly(channel);
            throw error;
        }
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
        return frameRateNum;
    }

    @Override
    public int frameRateDen() {
        return frameRateDen;
    }

    /**
     * @return always {@link #DURATION_UNKNOWN}. A picture's payload is a fixed size, but its
     *         {@code FRAME} line is not (parameters may be attached to any of them), so the number
     *         of pictures cannot be divided out of the file size, and counting them means reading
     *         the whole input. An estimate presented as a fact would be worse than not knowing.
     */
    @Override
    public long durationMicros() {
        return DURATION_UNKNOWN;
    }

    @Override
    public Read readFrame() {
        if (closed || ended) {
            return Read.END;
        }
        VideoFrame.Writer writer = pool.acquire();
        if (writer == null) {
            return Read.PENDING; // every picture is still held; release one and ask again
        }
        boolean published = false;
        try {
            String line = readLine();
            if (line == null) {
                ended = true;
                return Read.END;
            }
            if (!line.startsWith("FRAME")) {
                throw malformed("expected a FRAME line, found '" + line + "'");
            }
            readPlanes(writer.frame().slot());
            writer.setPtsMicros(ptsMicrosOf(frameIndex++));
            current = writer.publish();
            published = true;
            return Read.FRAME;
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + file, error);
        } finally {
            if (!published) {
                pool.abandon(writer);
            }
        }
    }

    @Override
    public VideoFrame frame() {
        return closed ? null : current;
    }

    @Override
    public void reset() {
        if (!resettable) {
            throw new UnsupportedOperationException(file + " cannot be rewound");
        }
        rewindToData();
        frameIndex = 0;
        ended = false;
    }

    @Override
    public boolean canReset() {
        return resettable;
    }

    /**
     * @return whether the input is a file rather than a pipe <em>and</em> the header declared a
     *         frame rate. Without a rate the pictures have no presentation times at all, so there is
     *         no time for a target to be expressed in and a seek would be a guess about what the
     *         caller meant by a microsecond.
     */
    @Override
    public boolean canSeek() {
        return resettable && frameRateNum != 0;
    }

    /**
     * Walks to the wanted picture without decoding one: the payload of a picture in this container
     * is a fixed number of bytes, so skipping it is a line read and a position move.
     *
     * <p>The arithmetic that would let a picture be reached in one move (multiply the index by the
     * size of a picture) is deliberately not used, for the reason {@link #durationMicros()} gives
     * for not dividing a picture count out of the file size: a {@code FRAME} line may carry
     * parameters, so the pictures are <em>not</em> at a fixed pitch, and the one file that uses that
     * feature would land on rubbish rather than on a picture. Reading each line is always right and
     * costs no transfer.
     *
     * <p>Seeking backwards rewinds to the first picture and walks forward, because this container
     * has no index and nothing in it can be found by looking backwards from the middle.
     */
    @Override
    public void seek(long micros, SeekMode mode) {
        if (!canSeek()) {
            throw new UnsupportedOperationException(file + " cannot be seeked");
        }
        if (micros < 0) {
            throw new IllegalArgumentException("seek target must not be negative, got " + micros);
        }
        int target = mode == SeekMode.EXACT
                ? FrameIndex.atOrAfter(micros, frameRateNum, frameRateDen)
                : FrameIndex.atOrBefore(micros, frameRateNum, frameRateDen);
        try {
            if (target < frameIndex) {
                rewindToData();
                frameIndex = 0;
            }
            ended = false;
            while (frameIndex < target) {
                if (!skipPicture()) {
                    // Past the last picture. The position is the end of the input and the next read
                    // reports the end, which is what seeking past the end means.
                    ended = true;
                    return;
                }
                frameIndex++;
            }
        } catch (IOException error) {
            throw new UncheckedIOException("cannot seek " + file, error);
        }
    }

    private void rewindToData() {
        try {
            channel.position(dataStart);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot rewind " + file, error);
        }
        input.position(0);
        input.limit(0);
    }

    /** @return false at a clean end of input, having consumed nothing */
    private boolean skipPicture() throws IOException {
        String line = readLine();
        if (line == null) {
            return false;
        }
        if (!line.startsWith("FRAME")) {
            throw malformed("expected a FRAME line, found '" + line + "'");
        }
        return skipBytes(pictureBytes);
    }

    /**
     * Advances the read position by {@code count} bytes: what is buffered is dropped and the rest
     * is a channel move, so skipping a picture costs no transfer at all.
     *
     * @return false when the input ends inside those bytes, leaving the position at its end
     */
    private boolean skipBytes(long count) throws IOException {
        long left = count;
        int buffered = (int) Math.min(input.remaining(), left);
        input.position(input.position() + buffered);
        left -= buffered;
        if (left == 0) {
            return true;
        }
        // The buffer is empty now, so the channel's own position is the next unread byte.
        long wanted = channel.position() + left;
        input.position(0);
        input.limit(0);
        long size = channel.size();
        if (wanted > size) {
            channel.position(size);
            return false;
        }
        channel.position(wanted);
        return true;
    }

    @Override
    public void close() {
        closed = true;
        current = null;
        closeQuietly(channel);
    }

    private long ptsMicrosOf(int index) {
        return frameRateNum == 0
                ? VideoFrame.PTS_UNKNOWN
                : (long) index * 1_000_000L * frameRateDen / frameRateNum;
    }

    /** Reads one picture's planes into {@code slot}, row by row: the file is tight, the pool is not. */
    private void readPlanes(int slot) throws IOException {
        for (int plane = 0; plane < format.planeCount(); plane++) {
            ByteBuffer destination = pool.planeOf(slot, plane);
            int stride = pool.stride(plane);
            int byteWidth = format.planeByteWidth(plane, width);
            int rows = format.planeHeight(plane, height);
            for (int row = 0; row < rows; row++) {
                readInto(destination, row * stride, byteWidth);
            }
        }
    }

    /** Copies exactly {@code count} bytes of input to {@code at} in {@code destination}. */
    private void readInto(ByteBuffer destination, int at, int count) throws IOException {
        int done = 0;
        while (done < count) {
            if (!input.hasRemaining() && !refill()) {
                throw malformed("the input ends inside a picture's planes (picture " + frameIndex
                        + " is short by " + (count - done) + " bytes of one row)");
            }
            int chunk = Math.min(input.remaining(), count - done);
            int limit = input.limit();
            input.limit(input.position() + chunk);
            destination.position(at + done);
            destination.put(input);
            input.limit(limit);
            done += chunk;
        }
    }

    /**
     * @return the next line without its terminator, or null at a clean end of input, which is the
     *         only end this format has, since a stream simply stops after its last picture
     */
    private String readLine() throws IOException {
        return readLine(file, channel, input, lineScratch);
    }

    /**
     * @return the line at the current position without its terminator, or null when the input ends
     *         exactly at a line boundary. Static and handed its own scratch so that the header can
     *         be read before an instance exists, through the very same buffer the instance goes on
     *         to use; the position it stops at is the first byte of the first picture.
     */
    private static String readLine(Path file, SeekableByteChannel channel, ByteBuffer input,
                                   byte[] scratch) throws IOException {
        int length = 0;
        while (true) {
            while (input.hasRemaining()) {
                byte value = input.get();
                if (value == '\n') {
                    return new String(scratch, 0, length, StandardCharsets.US_ASCII);
                }
                if (length == scratch.length) {
                    throw new IllegalStateException("malformed Y4M in " + file + ": a line runs past "
                            + MAX_LINE_BYTES + " bytes with no newline");
                }
                scratch[length++] = value;
            }
            if (!refill(channel, input)) {
                if (length == 0) {
                    return null;
                }
                throw new IllegalStateException("malformed Y4M in " + file
                        + ": the input ends inside a line ('"
                        + shorten(new String(scratch, 0, length, StandardCharsets.US_ASCII)) + "')");
            }
        }
    }

    private boolean refill() throws IOException {
        return refill(channel, input);
    }

    private static boolean refill(SeekableByteChannel channel, ByteBuffer input) throws IOException {
        input.compact();
        int read = channel.read(input);
        input.flip();
        return read > 0;
    }

    private IllegalStateException malformed(String reason) {
        return new IllegalStateException("malformed Y4M in " + file + ": " + reason);
    }

    private static void closeQuietly(SeekableByteChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Closing is best-effort: the caller is already on a failure path or done reading.
            }
        }
    }

    // ------------------------------------------------------------------ header

    /** What the stream header says, before any override is applied. */
    private static final class Header {
        int width;
        int height;
        int rateNum;
        int rateDen = 1;
        PixelFormat format = PixelFormat.I420;
        VideoColor color = VideoColor.unspecified();
    }

    private static Header readHeader(Path file, SeekableByteChannel channel, ByteBuffer input)
            throws IOException {
        String line = readLine(file, channel, input, new byte[MAX_LINE_BYTES]);
        if (line == null) {
            throw new IllegalStateException("malformed Y4M in " + file + ": the input is empty");
        }
        if (!line.startsWith(MAGIC)) {
            throw new IllegalStateException("malformed Y4M in " + file + ": expected '" + MAGIC
                    + "', found '" + shorten(line) + "'");
        }
        Header header = new Header();
        boolean hasWidth = false;
        boolean hasHeight = false;
        String colorTag = null;
        String range = null;
        for (String token : line.substring(MAGIC.length()).split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            char tag = token.charAt(0);
            String value = token.substring(1);
            switch (tag) {
                case 'W' -> {
                    header.width = parsePositive(file, "W", value);
                    hasWidth = true;
                }
                case 'H' -> {
                    header.height = parsePositive(file, "H", value);
                    hasHeight = true;
                }
                case 'F' -> {
                    int colon = value.indexOf(':');
                    if (colon < 1) {
                        throw new IllegalStateException("malformed Y4M in " + file
                                + ": frame rate must be F<num>:<den>, found 'F" + value + "'");
                    }
                    header.rateNum = parseRatePart(file, value.substring(0, colon));
                    header.rateDen = parseRatePart(file, value.substring(colon + 1));
                }
                case 'C' -> colorTag = value;
                case 'X' -> {
                    // FFmpeg's extension for the one thing this container otherwise cannot say.
                    if (value.toUpperCase(Locale.ROOT).startsWith("COLORRANGE=")) {
                        range = value.substring("COLORRANGE=".length()).toUpperCase(Locale.ROOT);
                    }
                }
                default -> {
                    // Interlacing, pixel aspect ratio and anything a later revision adds: skipped
                    // rather than refused, because a reader that rejects a tag it does not use
                    // cannot open a file that is otherwise perfectly readable.
                }
            }
        }
        if (!hasWidth || !hasHeight) {
            throw new IllegalStateException("malformed Y4M in " + file
                    + ": the header has no " + (hasWidth ? "H" : "W") + " tag ('" + shorten(line) + "')");
        }
        header.format = formatOf(file, colorTag);
        header.color = colorOf(range);
        return header;
    }

    /**
     * Maps the {@code C} tag onto a layout. The four 4:2:0 spellings differ only in where a chroma
     * sample sits relative to its luma pixels, which nothing in this toolkit carries and which
     * changes no byte of the data, so they are one layout here.
     */
    private static PixelFormat formatOf(Path file, String tag) {
        if (tag == null) {
            return PixelFormat.I420; // the format's own default when no C tag is present
        }
        return switch (tag) {
            case "420", "420jpeg", "420paldv", "420mpeg2" -> PixelFormat.I420;
            case "444" -> PixelFormat.I444;
            // The container writes a wide sample little-endian and right-justified, which is
            // exactly how the layout stores it, so the planes are copied and never swapped.
            case "420p10" -> PixelFormat.I420_10LE;
            case "444p10" -> PixelFormat.I444_10LE;
            default -> throw new UnsupportedOperationException("C" + tag + " in " + file
                    + " has no equivalent layout: this toolkit reads 8-bit 4:2:0 (C420, C420jpeg, "
                    + "C420paldv, C420mpeg2), 8-bit 4:4:4 (C444), 10-bit 4:2:0 (C420p10) and "
                    + "10-bit 4:4:4 (C444p10). 4:2:2, 12-bit and 16-bit are not decodable here.");
        };
    }

    private static VideoColor colorOf(String range) {
        if (range == null) {
            return VideoColor.unspecified();
        }
        return switch (range) {
            case "FULL" -> VideoColor.of(VideoColor.Matrix.BT709, VideoColor.Range.FULL);
            case "LIMITED" -> VideoColor.of(VideoColor.Matrix.BT709, VideoColor.Range.LIMITED);
            default -> VideoColor.unspecified(); // an XCOLORRANGE nobody writes; treat as unsaid
        };
    }

    /** A frame-rate term: at least 1, and not bounded by a picture dimension the way W and H are. */
    private static int parseRatePart(Path file, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalStateException("malformed Y4M in " + file
                    + ": tag F needs whole numbers, found '" + value + "'");
        }
        if (parsed < 1) {
            throw new IllegalStateException("malformed Y4M in " + file
                    + ": tag F has a term of " + parsed + ", which is not a rate");
        }
        return parsed;
    }

    private static int parsePositive(Path file, String tag, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalStateException("malformed Y4M in " + file + ": tag " + tag
                    + " needs a whole number, found '" + value + "'");
        }
        if (parsed < 1 || parsed > PixelFormat.MAX_DIMENSION) {
            throw new IllegalStateException("malformed Y4M in " + file + ": tag " + tag + " is "
                    + parsed + ", outside [1.." + PixelFormat.MAX_DIMENSION + "]");
        }
        return parsed;
    }

    private static String shorten(String line) {
        return line.length() <= 64 ? line : line.substring(0, 61) + "...";
    }
}

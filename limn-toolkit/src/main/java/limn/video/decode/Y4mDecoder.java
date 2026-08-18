package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoDecoder;
import limn.video.VideoStreamSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Reads YUV4MPEG2: a text header, then a {@code FRAME} line and raw planes per picture, with no
 * compression and no container. What every encoder and every codec test harness reads and writes,
 * which makes it the format a picture can be got into this toolkit in without a codec existing yet.
 *
 * <p>Reads every layout this toolkit has: {@code C420}, {@code C420jpeg}, {@code C420paldv} and
 * {@code C420mpeg2}, which differ only in chroma siting and are one layout here, {@code C444}, and
 * their 10-bit forms {@code C420p10} and {@code C444p10}, whose samples are little-endian 16-bit
 * words. A stream with no {@code C} tag is 8-bit 4:2:0 by the format's own convention. {@code C422},
 * the 12- and 16-bit variants and the monochrome tags are refused when the stream is opened, with a
 * message that names the tag and what is readable instead.
 *
 * <p><b>Colour.</b> YUV4MPEG2 signals neither a matrix nor a range, so a stream reports
 * {@link VideoColor#unspecified()}, which decodes as BT.709 studio range, and says so, rather than
 * claiming a stream stated something it did not. The one exception is FFmpeg's {@code XCOLORRANGE}
 * extension, which some writers do emit and which is honoured when present. Content that is
 * something else (standard-definition BT.601 material is the common case) is opened by a decoder
 * built with {@link #Y4mDecoder(VideoColor)}, whose interpretation wins over anything in the header.
 *
 * <p>Immutable and stateless; one instance serves every file, from any thread.
 */
public final class Y4mDecoder implements VideoDecoder {

    /** What the format's own file extension is; a file so named is claimed without being opened. */
    public static final String EXTENSION = ".y4m";

    private static final byte[] MAGIC = "YUV4MPEG2".getBytes(StandardCharsets.US_ASCII);

    /**
     * Pictures a source keeps in flight. Three: one being shown, one uploaded and waiting, one being
     * filled. Two stalls the reader every time the consumer is slow by a hair; more buys nothing a
     * file-backed reader can use, because it never has to wait for anything to arrive.
     */
    private static final int SLOTS = 3;

    private final VideoColor override;

    /** A decoder that reports what the header implies: {@link VideoColor#unspecified()}, normally. */
    public Y4mDecoder() {
        this(null);
    }

    /**
     * A decoder that reports {@code color} for every stream it opens, whatever the header says.
     *
     * <p>This is not a preference, it is the caller asserting knowledge the container cannot hold:
     * a Y4M file of standard-definition content is BT.601 and nothing in it says so, and decoding it
     * as BT.709 shifts every colour that is not grey. Install one of these ahead of the plain
     * decoder for a directory of such files.
     *
     * @param color the interpretation every stream reports; null for what the header implies
     */
    public Y4mDecoder(VideoColor color) {
        this.override = color;
    }

    @Override
    public String name() {
        return "y4m";
    }

    /**
     * @return whether {@code file} is named {@code .y4m}, or begins with the {@code YUV4MPEG2}
     *         signature. Never throws: a missing, unreadable or surprising input is simply not
     *         claimed, because one bad file that threw here would break the probe for every decoder
     *         behind this one.
     */
    @Override
    public boolean supports(Path file) {
        if (file == null) {
            return false;
        }
        Path name = file.getFileName();
        if (name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            return true;
        }
        return hasSignature(file);
    }

    /**
     * Opens {@code file} and reads its header, so every metadata accessor answers before the first
     * picture is decoded.
     *
     * @throws UnsupportedOperationException if the stream's {@code C} tag names a layout this
     *                                       toolkit has no {@link PixelFormat} for
     * @throws IllegalStateException         if the header is missing, malformed, or gives a size
     *                                       outside {@code [1..PixelFormat.MAX_DIMENSION]}
     * @throws java.io.UncheckedIOException  if the file cannot be read
     * @throws NullPointerException          if {@code file} is null
     */
    @Override
    public VideoStreamSource openStream(Path file) {
        Objects.requireNonNull(file, "file");
        return Y4mSource.open(file, override, SLOTS);
    }

    private static boolean hasSignature(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(MAGIC.length);
            return java.util.Arrays.equals(head, MAGIC);
        } catch (IOException | RuntimeException ignored) {
            return false; // not readable, not a file, not ours
        }
    }
}

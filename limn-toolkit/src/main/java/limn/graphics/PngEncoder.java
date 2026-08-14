package limn.graphics;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The toolkit's built-in {@link ImageEncoder}: PNG, 8-bit RGBA with straight alpha, which is
 * {@link Image}'s pixel layout unchanged, so no channel is dropped, no value is quantized, and
 * decoding the result yields the bytes that went in.
 *
 * <p>Pure Java, on {@code java.util.zip}. It runs with no backend started, no window and no GL
 * context, which is what lets a test produce a reference image and an asset tool run headless.
 * {@link Images} installs it, so {@link ImageFormat#PNG} is always encodable.
 *
 * <p><b>Deterministic:</b> the same {@link Image} produces the same bytes, with no timestamp, no
 * producer string, no adaptive choice that depends on anything but the pixels. Two runs of the same
 * JVM are byte-identical, which is what a test comparing against a checked-in file relies on. The
 * compressed payload is {@code java.util.zip}'s, so a file produced by one JDK is not promised to
 * match one produced by another; a reference file therefore belongs to a pinned toolchain, and a
 * test that must survive a JDK upgrade compares decoded pixels rather than bytes.
 *
 * <p>No metadata is written: no text chunks, no colour profile, no timestamp. A PNG this writes
 * carries pixels and nothing else.
 */
public final class PngEncoder implements ImageEncoder {

    /** The installed instance; pass it to {@link Images#uninstallEncoder} to override PNG. */
    public static final ImageEncoder INSTANCE = new PngEncoder();

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private static final byte[] NO_DATA = new byte[0];

    /**
     * Pinned rather than {@link Deflater#DEFAULT_COMPRESSION}: the default is a property of the
     * runtime, and a runtime that changed it would silently change every byte this class emits.
     */
    private static final int DEFLATE_LEVEL = 6;

    /** Deflated output is emitted in IDAT chunks of at most this size; PNG allows any number. */
    private static final int IDAT_CHUNK = 1 << 16;

    private static final int BYTES_PER_PIXEL = 4; // RGBA8, the only layout Image has

    private PngEncoder() {
    }

    @Override
    public String name() {
        return "png";
    }

    @Override
    public boolean supports(ImageEncodeOptions options) {
        return ImageFormat.PNG.equals(options.format());
    }

    @Override
    public void encode(Image image, ImageEncodeOptions options, OutputStream out) throws IOException {
        int width = image.width();
        int height = image.height();
        int stride = width * BYTES_PER_PIXEL;
        byte[] pixels = image.pixels();

        out.write(SIGNATURE);
        writeHeader(out, width, height);

        Deflater deflater = new Deflater(DEFLATE_LEVEL);
        try {
            byte[] compressed = new byte[IDAT_CHUNK];
            byte[] previous = new byte[stride];
            byte[] current = new byte[stride];
            byte[][] candidates = new byte[5][stride];
            byte[] line = new byte[1 + stride];
            for (int row = 0; row < height; row++) {
                System.arraycopy(pixels, row * stride, current, 0, stride);
                int filter = filterRow(current, previous, candidates);
                line[0] = (byte) filter;
                System.arraycopy(candidates[filter], 0, line, 1, stride);
                deflate(deflater, line, compressed, out);
                byte[] swap = previous;
                previous = current;
                current = swap;
            }
            deflater.finish();
            while (!deflater.finished()) {
                int produced = deflater.deflate(compressed, 0, compressed.length);
                if (produced > 0) {
                    writeChunk(out, "IDAT", compressed, 0, produced);
                }
            }
        } finally {
            deflater.end();
        }

        writeChunk(out, "IEND", NO_DATA, 0, 0);
    }

    private static void writeHeader(OutputStream out, int width, int height) throws IOException {
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;  // bit depth
        ihdr[9] = 6;  // colour type 6: truecolour with alpha
        ihdr[10] = 0; // compression: deflate, the only value PNG defines
        ihdr[11] = 0; // filter method 0: the five per-row filters used below
        ihdr[12] = 0; // not interlaced
        writeChunk(out, "IHDR", ihdr, 0, ihdr.length);
    }

    private static void deflate(Deflater deflater, byte[] input, byte[] compressed, OutputStream out)
            throws IOException {
        deflater.setInput(input);
        while (!deflater.needsInput()) {
            int produced = deflater.deflate(compressed, 0, compressed.length);
            if (produced == 0) {
                break; // deflate() only returns 0 when it wants more input or has finished
            }
            writeChunk(out, "IDAT", compressed, 0, produced);
        }
    }

    /**
     * Filters {@code row} against {@code previous} with all five PNG predictors, into
     * {@code candidates}, and returns the index of the one whose bytes sum smallest when read as
     * signed, which is the heuristic the PNG specification suggests. It reads only pixel data, so
     * the choice is a function of the image and nothing else, which is half of why the output is
     * deterministic.
     */
    private static int filterRow(byte[] row, byte[] previous, byte[][] candidates) {
        int stride = row.length;
        long best = Long.MAX_VALUE;
        int bestFilter = 0;
        for (int filter = 0; filter < 5; filter++) {
            byte[] into = candidates[filter];
            long sum = 0;
            for (int i = 0; i < stride; i++) {
                int x = row[i] & 0xFF;
                int a = i >= BYTES_PER_PIXEL ? row[i - BYTES_PER_PIXEL] & 0xFF : 0;
                int b = previous[i] & 0xFF;
                int c = i >= BYTES_PER_PIXEL ? previous[i - BYTES_PER_PIXEL] & 0xFF : 0;
                int value = switch (filter) {
                    case 1 -> x - a;
                    case 2 -> x - b;
                    case 3 -> x - ((a + b) >> 1);
                    case 4 -> x - paeth(a, b, c);
                    default -> x;
                };
                into[i] = (byte) value;
                sum += Math.abs((int) into[i]);
            }
            if (sum < best) {
                best = sum;
                bestFilter = filter;
            }
        }
        return bestFilter;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    private static void writeChunk(OutputStream out, String type, byte[] data, int offset, int length)
            throws IOException {
        byte[] header = new byte[8];
        putInt(header, 0, length);
        for (int i = 0; i < 4; i++) {
            header[4 + i] = (byte) type.charAt(i);
        }
        out.write(header);
        out.write(data, offset, length);
        CRC32 crc = new CRC32();
        crc.update(header, 4, 4); // the CRC covers the type and the data, not the length
        crc.update(data, offset, length);
        byte[] trailer = new byte[4];
        putInt(trailer, 0, (int) crc.getValue());
        out.write(trailer);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}

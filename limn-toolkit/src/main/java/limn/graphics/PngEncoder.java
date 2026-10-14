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
 *
 * <p>An {@linkplain Image#isAnimated() animated image} is written as an animated PNG: every frame,
 * its time and the turns the image asks for, without loss. The first frame is the picture a reader
 * that does not animate shows; each frame after it carries the rectangle that changed since the
 * one before, laid in place of what was there.
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
        out.write(SIGNATURE);
        writeHeader(out, width, height);
        if (!image.isAnimated()) {
            writePicture(image.pixels(), width, height, (data, length) -> writeChunk(out, "IDAT", data, 0, length));
            writeChunk(out, "IEND", NO_DATA, 0, 0);
            return;
        }

        // An animated PNG: the first frame is the picture every reader shows, and the frames after
        // it carry the rectangle that changed, laid in place of what was there.
        int frames = image.frameCount();
        byte[] control = new byte[8];
        putInt(control, 0, frames);
        putInt(control, 4, image.loopCount());
        writeChunk(out, "acTL", control, 0, control.length);
        int[] sequence = {0};
        byte[] before = null;
        for (int i = 0; i < frames; i++) {
            byte[] pixels = image.frame(i).pixels();
            int[] box = before == null ? new int[] {0, 0, width, height} : changed(before, pixels, width, height);
            byte[] frame = new byte[26];
            putInt(frame, 0, sequence[0]++);
            putInt(frame, 4, box[2]);
            putInt(frame, 8, box[3]);
            putInt(frame, 12, box[0]);
            putInt(frame, 16, box[1]);
            putDelay(frame, 20, image.frameMillis(i));
            frame[24] = 0; // disposal: none, the next frame is laid on this one
            frame[25] = 0; // blend: source, the rectangle replaces what was there
            writeChunk(out, "fcTL", frame, 0, frame.length);
            byte[] part = crop(pixels, width, box);
            if (i == 0) {
                writePicture(part, box[2], box[3], (data, length) -> writeChunk(out, "IDAT", data, 0, length));
            } else {
                writePicture(part, box[2], box[3], (data, length) -> {
                    byte[] numbered = new byte[4 + length];
                    putInt(numbered, 0, sequence[0]++);
                    System.arraycopy(data, 0, numbered, 4, length);
                    writeChunk(out, "fdAT", numbered, 0, numbered.length);
                });
            }
            before = pixels;
        }
        writeChunk(out, "IEND", NO_DATA, 0, 0);
    }

    /** Where deflated picture data goes: an IDAT chunk, or a frame's numbered fdAT. */
    private interface Chunks {
        void write(byte[] data, int length) throws IOException;
    }

    /** Filters and deflates a picture, its data handed out in chunks of at most {@link #IDAT_CHUNK}. */
    private static void writePicture(byte[] pixels, int width, int height, Chunks chunks) throws IOException {
        int stride = width * BYTES_PER_PIXEL;
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
                deflate(deflater, line, compressed, chunks);
                byte[] swap = previous;
                previous = current;
                current = swap;
            }
            deflater.finish();
            while (!deflater.finished()) {
                int produced = deflater.deflate(compressed, 0, compressed.length);
                if (produced > 0) {
                    chunks.write(compressed, produced);
                }
            }
        } finally {
            deflater.end();
        }
    }

    /**
     * @return the smallest rectangle, as x, y, width, height, holding every pixel that differs
     *         between two frames; a pixel's worth at the corner when none does, since a frame
     *         has at least one
     */
    private static int[] changed(byte[] before, byte[] after, int width, int height) {
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            int row = y * width * BYTES_PER_PIXEL;
            for (int x = 0; x < width; x++) {
                int at = row + x * BYTES_PER_PIXEL;
                if (before[at] != after[at] || before[at + 1] != after[at + 1] || before[at + 2] != after[at + 2]
                        || before[at + 3] != after[at + 3]) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < 0) {
            return new int[] {0, 0, 1, 1};
        }
        return new int[] {left, top, right - left + 1, bottom - top + 1};
    }

    private static byte[] crop(byte[] pixels, int width, int[] box) {
        int stride = box[2] * BYTES_PER_PIXEL;
        byte[] out = new byte[stride * box[3]];
        for (int row = 0; row < box[3]; row++) {
            System.arraycopy(pixels, ((box[1] + row) * width + box[0]) * BYTES_PER_PIXEL, out, row * stride, stride);
        }
        return out;
    }

    /** A frame's time as the format holds it: a fraction of two 16-bit numbers, a thousandth of a second where it fits. */
    private static void putDelay(byte[] target, int offset, int millis) {
        int numerator = millis;
        int denominator = 1000;
        while (numerator > 0xFFFF && denominator > 1) {
            numerator = (int) Math.round(numerator / 10.0);
            denominator /= 10;
        }
        numerator = Math.min(numerator, 0xFFFF);
        target[offset] = (byte) (numerator >>> 8);
        target[offset + 1] = (byte) numerator;
        target[offset + 2] = (byte) (denominator >>> 8);
        target[offset + 3] = (byte) denominator;
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

    private static void deflate(Deflater deflater, byte[] input, byte[] compressed, Chunks chunks)
            throws IOException {
        deflater.setInput(input);
        while (!deflater.needsInput()) {
            int produced = deflater.deflate(compressed, 0, compressed.length);
            if (produced == 0) {
                break; // deflate() only returns 0 when it wants more input or has finished
            }
            chunks.write(compressed, produced);
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

package limn.graphics;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A minimal PNG reader, for tests only. Deliberately an independent implementation rather than a
 * mirror of {@link PngEncoder}: a round trip through the same code proves that the code agrees with
 * itself, which is not what the round-trip tests are asking.
 *
 * <p>Handles exactly what {@link PngEncoder} writes (8-bit RGBA, not interlaced) and rejects
 * anything else, so a change that starts emitting something different fails here rather than
 * silently decoding to the wrong thing.
 */
final class TestPngReader {

    private TestPngReader() {
    }

    static Image decode(byte[] file) {
        expect(file.length > 8, "too short to be a PNG");
        long signature = 0;
        for (int i = 0; i < 8; i++) {
            signature = (signature << 8) | (file[i] & 0xFF);
        }
        expect(signature == 0x89504E470D0A1A0AL, "bad PNG signature");

        int width = 0;
        int height = 0;
        boolean sawHeader = false;
        boolean sawEnd = false;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int at = 8;
        while (at + 8 <= file.length) {
            int length = readInt(file, at);
            String type = new String(file, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int dataAt = at + 8;
            expect(dataAt + length + 4 <= file.length, "chunk " + type + " runs past the end");
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(file, at + 4, 4 + length);
            expect((int) crc.getValue() == readInt(file, dataAt + length), "bad CRC on " + type);
            switch (type) {
                case "IHDR" -> {
                    expect(length == 13, "IHDR must be 13 bytes");
                    width = readInt(file, dataAt);
                    height = readInt(file, dataAt + 4);
                    expect(file[dataAt + 8] == 8, "expected bit depth 8");
                    expect(file[dataAt + 9] == 6, "expected colour type 6 (RGBA)");
                    expect(file[dataAt + 10] == 0, "expected deflate compression");
                    expect(file[dataAt + 11] == 0, "expected filter method 0");
                    expect(file[dataAt + 12] == 0, "expected no interlacing");
                    sawHeader = true;
                }
                case "IDAT" -> idat.write(file, dataAt, length);
                case "IEND" -> sawEnd = true;
                default -> throw new AssertionError("unexpected chunk " + type);
            }
            at = dataAt + length + 4;
        }
        expect(sawHeader, "no IHDR");
        expect(sawEnd, "no IEND");
        expect(at == file.length, "trailing bytes after IEND");

        int stride = width * 4;
        byte[] raw = inflate(idat.toByteArray(), (long) (stride + 1) * height);
        byte[] pixels = new byte[stride * height];
        for (int row = 0; row < height; row++) {
            int filter = raw[row * (stride + 1)] & 0xFF;
            int from = row * (stride + 1) + 1;
            int to = row * stride;
            for (int i = 0; i < stride; i++) {
                int x = raw[from + i] & 0xFF;
                int a = i >= 4 ? pixels[to + i - 4] & 0xFF : 0;
                int b = row > 0 ? pixels[to - stride + i] & 0xFF : 0;
                int c = row > 0 && i >= 4 ? pixels[to - stride + i - 4] & 0xFF : 0;
                int value = switch (filter) {
                    case 0 -> x;
                    case 1 -> x + a;
                    case 2 -> x + b;
                    case 3 -> x + ((a + b) >> 1);
                    case 4 -> x + paeth(a, b, c);
                    default -> throw new AssertionError("unknown filter " + filter);
                };
                pixels[to + i] = (byte) value;
            }
        }
        return new Image(width, height, pixels);
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

    private static byte[] inflate(byte[] compressed, long expected) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[(int) expected];
            int produced = inflater.inflate(out);
            expect(produced == expected, "inflated " + produced + " bytes, expected " + expected);
            expect(inflater.finished(), "deflate stream did not finish");
            return out;
        } catch (DataFormatException error) {
            throw new AssertionError("corrupt deflate stream", error);
        } finally {
            inflater.end();
        }
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

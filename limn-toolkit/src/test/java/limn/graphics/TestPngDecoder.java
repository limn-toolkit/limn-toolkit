package limn.graphics;

import limn.io.MalformedContentException;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A PNG decoder for tests, playing the part of the one a backend installs where no backend runs:
 * 8-bit gray, colour, palette with transparency, gray with alpha and colour with alpha,
 * interlaced or not. A fault in a file is the one exception a decoder throws for one.
 *
 * <p>Not {@link TestPngReader}, which reads exactly what the toolkit's encoder writes and refuses
 * the rest, so that a change in what it writes is caught. This one reads what other encoders
 * write, and what it makes of a file is held against another decoder's by the tests that use it.
 * The JDK's reader would do, but nothing here may import the window toolkit its pictures are made
 * of.
 */
final class TestPngDecoder {

    /** Where each of the seven passes of an interlaced picture starts, and its steps. */
    private static final int[][] ADAM7 = {
            {0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};

    private TestPngDecoder() {
    }

    static Image decode(byte[] file) {
        try {
            return read(file);
        } catch (MalformedContentException damaged) {
            throw damaged;
        } catch (RuntimeException damaged) {
            throw new MalformedContentException("PNG", "unreadable", damaged);
        }
    }

    private static Image read(byte[] file) {
        expect(file.length > 8 + 25 && (file[0] & 0xFF) == 0x89 && file[1] == 'P' && file[2] == 'N' && file[3] == 'G',
                "not a PNG");
        int width = 0;
        int height = 0;
        int type = -1;
        boolean interlaced = false;
        byte[] palette = new byte[0];
        byte[] transparency = new byte[0];
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int at = 8; at + 12 <= file.length; ) {
            int length = readInt(file, at);
            expect(length >= 0 && length <= file.length - at - 12, "a chunk past the end");
            String name = new String(file, at + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int body = at + 8;
            switch (name) {
                case "IHDR" -> {
                    expect(length == 13, "a header of " + length + " bytes");
                    width = readInt(file, body);
                    height = readInt(file, body + 4);
                    expect(file[body + 8] == 8, "a depth other than 8");
                    type = file[body + 9];
                    expect(type == 0 || type == 2 || type == 3 || type == 4 || type == 6, "colour type " + type);
                    interlaced = file[body + 12] == 1;
                }
                case "PLTE" -> palette = java.util.Arrays.copyOfRange(file, body, body + length);
                case "tRNS" -> transparency = java.util.Arrays.copyOfRange(file, body, body + length);
                case "IDAT" -> data.write(file, body, length);
                default -> {
                    // a colour space, metadata: nothing a picture's pixels need
                }
            }
            at = body + length + 4;
        }
        expect(width > 0 && height > 0 && (long) width * height <= 1 << 24, "a picture of " + width + " by " + height);
        int channels = switch (type) {
            case 0, 3 -> 1;
            case 4 -> 2;
            case 2 -> 3;
            default -> 4;
        };
        int[][] passes = interlaced ? ADAM7 : new int[][] {{0, 0, 1, 1}};
        long expected = 0;
        for (int[] pass : passes) {
            long w = (width - pass[0] + pass[2] - 1) / pass[2];
            long h = (height - pass[1] + pass[3] - 1) / pass[3];
            if (w > 0 && h > 0) {
                expected += h * (1 + w * channels);
            }
        }
        byte[] raw = inflate(data.toByteArray(), expected);
        byte[] rgba = new byte[width * height * 4];
        int at = 0;
        for (int[] pass : passes) {
            int w = (width - pass[0] + pass[2] - 1) / pass[2];
            int h = (height - pass[1] + pass[3] - 1) / pass[3];
            if (w <= 0 || h <= 0) {
                continue;
            }
            int stride = w * channels;
            byte[] previous = new byte[stride];
            byte[] row = new byte[stride];
            for (int y = 0; y < h; y++) {
                int filter = raw[at++] & 0xFF;
                for (int i = 0; i < stride; i++) {
                    int x = raw[at++] & 0xFF;
                    int a = i >= channels ? row[i - channels] & 0xFF : 0;
                    int b = previous[i] & 0xFF;
                    int c = i >= channels ? previous[i - channels] & 0xFF : 0;
                    row[i] = (byte) switch (filter) {
                        case 0 -> x;
                        case 1 -> x + a;
                        case 2 -> x + b;
                        case 3 -> x + ((a + b) >> 1);
                        case 4 -> x + paeth(a, b, c);
                        default -> throw new MalformedContentException("PNG", "filter " + filter);
                    };
                }
                for (int x = 0; x < w; x++) {
                    int to = ((pass[1] + y * pass[3]) * width + pass[0] + x * pass[2]) * 4;
                    pixel(row, x * channels, type, palette, transparency, rgba, to);
                }
                byte[] swap = previous;
                previous = row;
                row = swap;
            }
        }
        return new Image(width, height, rgba);
    }

    private static void pixel(byte[] row, int from, int type, byte[] palette, byte[] transparency, byte[] rgba, int to) {
        switch (type) {
            case 0 -> {
                rgba[to] = row[from];
                rgba[to + 1] = row[from];
                rgba[to + 2] = row[from];
                boolean clear = transparency.length >= 2 && (transparency[1] & 0xFF) == (row[from] & 0xFF);
                rgba[to + 3] = (byte) (clear ? 0 : 255);
            }
            case 2 -> {
                System.arraycopy(row, from, rgba, to, 3);
                boolean clear = transparency.length >= 6 && transparency[1] == row[from]
                        && transparency[3] == row[from + 1] && transparency[5] == row[from + 2];
                rgba[to + 3] = (byte) (clear ? 0 : 255);
            }
            case 3 -> {
                int index = row[from] & 0xFF;
                expect(index * 3 + 2 < palette.length, "a colour past the palette");
                System.arraycopy(palette, index * 3, rgba, to, 3);
                rgba[to + 3] = index < transparency.length ? transparency[index] : (byte) 255;
            }
            case 4 -> {
                rgba[to] = row[from];
                rgba[to + 1] = row[from];
                rgba[to + 2] = row[from];
                rgba[to + 3] = row[from + 1];
            }
            default -> System.arraycopy(row, from, rgba, to, 4);
        }
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
        expect(expected <= 1 << 26, "a picture too large for a test");
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[(int) expected];
            int produced = 0;
            while (produced < out.length) {
                int step = inflater.inflate(out, produced, out.length - produced);
                if (step == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                produced += step;
            }
            expect(produced == out.length, "the picture's data is cut short");
            return out;
        } catch (DataFormatException damaged) {
            throw new MalformedContentException("PNG", "damaged picture data", damaged);
        } finally {
            inflater.end();
        }
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8)
                | (data[at + 3] & 0xFF);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new MalformedContentException("PNG", message);
        }
    }
}

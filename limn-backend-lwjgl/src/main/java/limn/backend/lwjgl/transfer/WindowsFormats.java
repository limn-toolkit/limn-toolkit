package limn.backend.lwjgl.transfer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The byte layouts the Windows clipboard keeps the standard formats in, to and from the bytes a
 * {@link NativeClipboard} speaks: text as UTF-16, HTML inside the {@code HTML Format} header, a
 * picture as a device-independent bitmap, files as a {@code DROPFILES} block. Pure functions.
 */
final class WindowsFormats {

    private static final Pattern START_FRAGMENT = Pattern.compile("StartFragment:(\\d+)");
    private static final Pattern END_FRAGMENT = Pattern.compile("EndFragment:(\\d+)");
    private static final Pattern START_HTML = Pattern.compile("StartHTML:(\\d+)");
    private static final Pattern END_HTML = Pattern.compile("EndHTML:(\\d+)");

    private WindowsFormats() {
    }

    /** UTF-8 text as {@code CF_UNICODETEXT}: UTF-16LE with its terminator. */
    static byte[] unicodeText(byte[] utf8) {
        byte[] utf16 = new String(utf8, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_16LE);
        return java.util.Arrays.copyOf(utf16, utf16.length + 2);
    }

    /** {@code CF_UNICODETEXT}, up to its terminator, as UTF-8. */
    static byte[] fromUnicodeText(byte[] block) {
        int end = 0;
        while (end + 1 < block.length && (block[end] != 0 || block[end + 1] != 0)) {
            end += 2;
        }
        return new String(block, 0, end, StandardCharsets.UTF_16LE).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * An HTML fragment as {@code HTML Format}: a header of byte offsets, then a document that
     * marks the fragment, all UTF-8.
     */
    static byte[] cfHtml(byte[] fragment) {
        String head = "<html><body>\r\n<!--StartFragment-->";
        String tail = "<!--EndFragment-->\r\n</body></html>";
        String header = "Version:0.9\r\nStartHTML:%010d\r\nEndHTML:%010d\r\n"
                + "StartFragment:%010d\r\nEndFragment:%010d\r\n";
        int headerLength = String.format(header, 0, 0, 0, 0).length();
        int startFragment = headerLength + head.length();
        int endFragment = startFragment + fragment.length;
        int endHtml = endFragment + tail.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(String.format(header, headerLength, endHtml, startFragment, endFragment)
                .getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(head.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(fragment);
        out.writeBytes(tail.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        return out.toByteArray();
    }

    /** The fragment of an {@code HTML Format} block; the whole document where no fragment is marked. */
    static byte[] fromCfHtml(byte[] block) {
        String header = new String(block, 0, Math.min(block.length, 512), StandardCharsets.ISO_8859_1);
        int[] range = range(header, START_FRAGMENT, END_FRAGMENT, block.length);
        if (range == null) {
            range = range(header, START_HTML, END_HTML, block.length);
        }
        if (range == null) {
            int end = block.length;
            while (end > 0 && block[end - 1] == 0) {
                end--;
            }
            return java.util.Arrays.copyOf(block, end);
        }
        return java.util.Arrays.copyOfRange(block, range[0], range[1]);
    }

    private static int[] range(String header, Pattern start, Pattern end, int length) {
        Matcher from = start.matcher(header);
        Matcher to = end.matcher(header);
        if (from.find() && to.find()) {
            try {
                int a = Integer.parseInt(from.group(1));
                int b = Integer.parseInt(to.group(1));
                if (a >= 0 && a <= b && b <= length) {
                    return new int[] {a, b};
                }
            } catch (NumberFormatException tooLong) {
                // not offsets: fall through
            }
        }
        return null;
    }

    /**
     * Straight-alpha RGBA rows, top first, as {@code CF_DIB}: a 40-byte header and 32-bit BGR
     * rows, bottom first. A DIB of this kind has no alpha most programs honour, so the picture is
     * laid over white; the PNG offered beside it keeps the transparency.
     */
    static byte[] dib(int width, int height, byte[] rgba) {
        ByteBuffer out = ByteBuffer.allocate(40 + width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(40).putInt(width).putInt(height).putShort((short) 1).putShort((short) 32)
                .putInt(0).putInt(width * height * 4).putInt(2835).putInt(2835).putInt(0).putInt(0);
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int a = rgba[i + 3] & 0xff;
                out.put((byte) over(rgba[i + 2] & 0xff, a));
                out.put((byte) over(rgba[i + 1] & 0xff, a));
                out.put((byte) over(rgba[i] & 0xff, a));
                out.put((byte) 0xff);
            }
        }
        return out.array();
    }

    private static int over(int channel, int alpha) {
        return (channel * alpha + 255 * (255 - alpha) + 127) / 255;
    }

    /** A {@code CF_DIB} block as a BMP file: the same bytes behind the 14-byte file header. */
    static byte[] bmpFile(byte[] dib) {
        ByteBuffer in = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = in.getInt(0);
        int bitCount = in.getShort(14) & 0xffff;
        int compression = in.getInt(16);
        int colorsUsed = in.getInt(32);
        int tables = bitCount <= 8 ? (colorsUsed != 0 ? colorsUsed : 1 << bitCount) * 4 : colorsUsed * 4;
        if (headerSize == 40 && compression == 3) {
            tables += 12; // the three masks of BI_BITFIELDS follow the short header
        }
        ByteBuffer out = ByteBuffer.allocate(14 + dib.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'B').put((byte) 'M').putInt(14 + dib.length).putInt(0)
                .putInt(14 + headerSize + tables).put(dib);
        return out.array();
    }

    /** File names as {@code CF_HDROP}: a {@code DROPFILES} header and a double-terminated UTF-16 list. */
    static byte[] dropFiles(List<String> paths) {
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        for (String path : paths) {
            names.writeBytes(path.getBytes(StandardCharsets.UTF_16LE));
            names.write(0);
            names.write(0);
        }
        names.write(0);
        names.write(0);
        ByteBuffer out = ByteBuffer.allocate(20 + names.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(20).putInt(0).putInt(0).putInt(0).putInt(1).put(names.toByteArray());
        return out.array();
    }

    /** The file names of a {@code CF_HDROP} block, wide or not. */
    static List<String> fromDropFiles(byte[] block) {
        List<String> paths = new ArrayList<>();
        if (block.length < 20) {
            return paths;
        }
        ByteBuffer in = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        int at = in.getInt(0);
        boolean wide = in.getInt(16) != 0;
        int unit = wide ? 2 : 1;
        while (at >= 20 && at + unit <= block.length) {
            int end = at;
            while (end + unit <= block.length && (block[end] != 0 || (wide && block[end + 1] != 0))) {
                end += unit;
            }
            if (end == at) {
                break;
            }
            paths.add(new String(block, at, end - at,
                    wide ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1));
            at = end + unit;
        }
        return paths;
    }
}

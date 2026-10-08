package limn.backend.lwjgl.transfer;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Windows clipboard's byte layouts, to and from the bytes the clipboard layer speaks. */
class WindowsFormatsTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void textIsUtf16WithItsTerminatorAndStopsAtIt() {
        byte[] block = WindowsFormats.unicodeText(utf8("aç😀"));
        assertEquals(2 + 2 + 4 + 2, block.length);
        assertArrayEquals(utf8("aç😀"), WindowsFormats.fromUnicodeText(block));
        byte[] padded = java.util.Arrays.copyOf(block, block.length + 6);
        padded[block.length + 2] = 'x';
        assertArrayEquals(utf8("aç😀"), WindowsFormats.fromUnicodeText(padded), "what follows the terminator is not text");
    }

    @Test
    void htmlCarriesByteOffsetsThatFindTheFragmentAgain() {
        byte[] fragment = utf8("<b>ação</b>");
        byte[] block = WindowsFormats.cfHtml(fragment);
        String text = new String(block, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("Version:0.9\r\nStartHTML:"));
        int start = Integer.parseInt(text.substring(text.indexOf("StartFragment:") + 14, text.indexOf("StartFragment:") + 24));
        assertEquals("<b>", new String(block, start, 3, StandardCharsets.UTF_8), "a byte offset, past the marker");
        assertArrayEquals(fragment, WindowsFormats.fromCfHtml(block));

        String document = "<html><body>x</body></html>";
        String header = "Version:1.0\r\nStartHTML:%010d\r\nEndHTML:%010d\r\nSourceURL:about:blank\r\n";
        int at = String.format(header, 0, 0).length();
        byte[] word = utf8(String.format(header, at, at + document.length()) + document + "\0");
        assertArrayEquals(utf8(document), WindowsFormats.fromCfHtml(word),
                "no fragment marked: the document between its own offsets");
        assertArrayEquals(utf8("<p>bare</p>"), WindowsFormats.fromCfHtml(utf8("<p>bare</p>\0")));
    }

    @Test
    void aPictureIsABottomUpBgrBitmapOverWhiteAndABmpFileIsItBehindAHeader() {
        // 1x2: an opaque red pixel over a half-transparent blue one
        byte[] rgba = {(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, (byte) 128};
        byte[] dib = WindowsFormats.dib(1, 2, rgba);
        ByteBuffer in = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(40, in.getInt(0));
        assertEquals(2, in.getInt(8), "a positive height: bottom row first");
        assertEquals(32, in.getShort(14));
        assertArrayEquals(new byte[] {(byte) 255, 127, 127, (byte) 255}, java.util.Arrays.copyOfRange(dib, 40, 44),
                "the bottom row first: blue at half over white, as B G R");
        assertArrayEquals(new byte[] {0, 0, (byte) 255, (byte) 255}, java.util.Arrays.copyOfRange(dib, 44, 48));

        byte[] bmp = WindowsFormats.bmpFile(dib);
        assertEquals('B', bmp[0]);
        assertEquals(14 + dib.length, ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN).getInt(2));
        assertEquals(54, ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN).getInt(10), "pixels after both headers");
    }

    @Test
    void filesAreADoubleTerminatedWideListAndAnAnsiOneIsReadToo() {
        List<String> paths = List.of("C:\\Users\\ana\\relatório final.pdf", "D:\\x");
        byte[] block = WindowsFormats.dropFiles(paths);
        assertEquals(20, ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
        assertEquals(paths, WindowsFormats.fromDropFiles(block));

        byte[] ansi = new byte[20 + 9];
        ByteBuffer.wrap(ansi).order(ByteOrder.LITTLE_ENDIAN).putInt(20);
        System.arraycopy("C:\\a\0D:\\b".getBytes(StandardCharsets.ISO_8859_1), 0, ansi, 20, 9);
        assertEquals(List.of("C:\\a", "D:\\b"), WindowsFormats.fromDropFiles(java.util.Arrays.copyOf(ansi, ansi.length + 2)));
    }
}

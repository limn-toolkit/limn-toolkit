package limn.backend.lwjgl.transfer;

import limn.io.MalformedContentException;
import limn.testfixtures.MutationFuzz;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A block another program put on the clipboard or dragged in is read or refused with a
 * {@link MalformedContentException}, and nothing else: the block is whatever that program wrote.
 */
class TransferFormatsFuzzTest {

    private static void run(String what, List<byte[]> seeds, MutationFuzz.Reader reader, boolean mayRefuse) {
        MutationFuzz.Result result = MutationFuzz.run(42, 20000, seeds, reader,
                thrown -> thrown.getClass() == MalformedContentException.class, "limn.backend.lwjgl.transfer.");
        assertEquals(Map.of(), result.escapes(), "a damaged " + what + " escaped its reader");
        assertTrue(result.read() > 0 && (!mayRefuse || result.refused() > 0), "the mutations test nothing: "
                + result.read() + " read, " + result.refused() + " refused");
    }

    @Test
    void aBitmapBlockIsReadOrRefused() {
        byte[] pixels = new byte[4 * 4 * 4];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 13);
        }
        run("bitmap", List.of(WindowsFormats.dib(4, 4, pixels), WindowsFormats.dib(1, 1, new byte[] {1, 2, 3, 4})),
                WindowsFormats::bmpFile, true);
    }

    @Test
    void aListOfFilesIsReadWhateverItsOffsetsSay() {
        run("list of files", List.of(
                WindowsFormats.dropFiles(List.of("C:\\Users\\ana\\report.pdf", "D:\\x.txt")),
                WindowsFormats.dropFiles(List.of("C:\\a"))),
                WindowsFormats::fromDropFiles, false);
    }

    @Test
    void anHtmlBlockIsReadWhateverItsHeaderSays() {
        run("HTML block", List.of(
                WindowsFormats.cfHtml("<b>bold</b> and plain".getBytes(StandardCharsets.UTF_8)),
                WindowsFormats.cfHtml("é".getBytes(StandardCharsets.UTF_8))),
                WindowsFormats::fromCfHtml, false);
    }

    @Test
    void aTextBlockIsReadWhereverItEnds() {
        run("text block", List.of(
                WindowsFormats.unicodeText("two words".getBytes(StandardCharsets.UTF_8)),
                WindowsFormats.unicodeText("日本語".getBytes(StandardCharsets.UTF_8))),
                WindowsFormats::fromUnicodeText, false);
    }

    @Test
    void aListOfAddressesIsReadLineByLine() {
        run("list of addresses", List.of(
                "file:///home/ana/report.pdf\r\nfile:///tmp/a%20b.txt\r\n".getBytes(StandardCharsets.UTF_8),
                "# comment\r\nhttps://example.org/a?b=c\r\n".getBytes(StandardCharsets.UTF_8)),
                UriLists::decode, false);
    }
}

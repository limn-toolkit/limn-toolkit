package limn.backend.lwjgl.transfer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The spellings of a type on the Linux desktops, and files as a URI list. */
class ClipboardTypesTest {

    @Test
    void textIsAnnouncedUnderEverySpellingAndAnyOfThemIsText() {
        assertEquals(List.of("text/plain;charset=utf-8", "UTF8_STRING", "text/plain", "TEXT", "STRING"),
                ClipboardTypes.spellings(ClipboardTypes.TEXT));
        assertEquals(List.of("text/html"), ClipboardTypes.spellings(ClipboardTypes.HTML));
        assertEquals(ClipboardTypes.TEXT, ClipboardTypes.canonical("UTF8_STRING"));
        assertEquals("image/png", ClipboardTypes.canonical("image/png"));
    }

    @Test
    void anotherProgramsTypesAreOneTextAndOneImage() {
        List<String> qt = List.of("image/bmp", "application/x-qt-image", "text/plain", "UTF8_STRING",
                "image/png", "TARGETS");
        assertEquals(List.of("image/png", "application/x-qt-image", "text/plain;charset=utf-8", "TARGETS"),
                ClipboardTypes.canonical(qt));
        assertEquals("image/png", ClipboardTypes.spellingIn(ClipboardTypes.IMAGE, qt), "PNG before BMP");
        assertEquals("UTF8_STRING", ClipboardTypes.spellingIn(ClipboardTypes.TEXT, qt));
        assertNull(ClipboardTypes.spellingIn(ClipboardTypes.HTML, qt));
    }

    @Test
    void filesAreAbsoluteEncodedUrisAndComeBackAsPaths() {
        List<Path> files = List.of(Path.of("/tmp/relatório final.pdf"), Path.of("/etc"));
        byte[] list = UriLists.encode(files);
        assertEquals("file:///tmp/relat%C3%B3rio%20final.pdf\r\nfile:///etc\r\n",
                new String(list, StandardCharsets.US_ASCII));
        assertEquals(files, UriLists.decode(list));
        assertEquals(List.of(Path.of("/home/a/b.txt")), UriLists.decode(
                "# from a file manager\nfile://thishost/home/a/b.txt\n\nmailto:x@example.org\nnot a uri at all\n\0"
                        .getBytes(StandardCharsets.UTF_8)));
    }
}

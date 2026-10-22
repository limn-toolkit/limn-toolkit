package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The print probes' hand-written PDF is a valid file: every cross-reference entry points at its
 * object, the trailer points at the table, and every page carries the marker a reader searches for
 * in what a printer made of it.
 */
class ProbePdfTest {

    @Test
    void theCrossReferenceTablePointsAtEveryObject() {
        byte[] pdf = ProbePdf.write(3, 595.28f, 841.89f, 5);
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.4\n"));
        Matcher start = Pattern.compile("startxref\n(\\d+)\n%%EOF\n$").matcher(text);
        assertTrue(start.find(), "a trailer that ends the file");
        int xref = Integer.parseInt(start.group(1));
        assertTrue(text.startsWith("xref\n0 ", xref), "startxref points at the table");
        Matcher entries = Pattern.compile("(\\d{10}) 00000 n \n").matcher(text.substring(xref));
        int object = 0;
        while (entries.find()) {
            object++;
            int offset = Integer.parseInt(entries.group(1));
            assertTrue(text.startsWith(object + " 0 obj\n", offset), "object " + object + " at " + offset);
        }
        assertEquals(3 + 2 * 3, object, "catalog, pages, font, and a page and its content per page");
    }

    @Test
    void everyPageCarriesTheMarkerAndAStreamOfItsStatedLength() {
        byte[] pdf = ProbePdf.write(2, 612, 792, 3);
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher streams = Pattern.compile("<< /Length (\\d+) >>\nstream\n").matcher(text);
        int pages = 0;
        while (streams.find()) {
            int length = Integer.parseInt(streams.group(1));
            String body = text.substring(streams.end(), streams.end() + length);
            assertTrue(text.startsWith("\nendstream", streams.end() + length), "the length is the stream's");
            assertTrue(body.contains("(" + ProbePdf.MARKER + " page " + (++pages) + " of 2)"));
        }
        assertEquals(2, pages);
    }
}

package limn.backend.lwjgl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A small PDF written by hand for the print doors' probes and tests: pages of text in the standard
 * Helvetica face (no font file embedded), one uncompressed content stream per page, and a cross
 * reference table whose offsets are counted as the file is written. Every page carries the word
 * {@link #MARKER}, which a reader of the printed result searches for to tell text kept as text from
 * text turned into a picture.
 *
 * <p>Not the toolkit's PDF writer (that is {@code limn.print}'s); this one exists so the doors can be
 * measured before and apart from it.
 */
public final class ProbePdf {

    /** The word every page carries, for a search in what came out of the printer. */
    public static final String MARKER = "LIMNPRINTPROBE";

    private ProbePdf() {
    }

    /**
     * @param pages  how many pages
     * @param width  the paper's width in points
     * @param height the paper's height in points
     * @param rows   rows of a table on each page after the heading, 0 for a single line
     * @return a complete PDF file
     */
    public static byte[] write(int pages, float width, float height, int rows) {
        List<String> objects = new ArrayList<>();
        // 1 catalog, 2 page tree, 3 font, then per page: the page and its content stream.
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pages; p++) {
            kids.append(4 + 2 * p).append(" 0 R ");
        }
        objects.add("<< /Type /Pages /Kids [ " + kids + "] /Count " + pages + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        for (int p = 0; p < pages; p++) {
            int contents = 5 + 2 * p;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(width) + " " + num(height) + "]"
                    + " /Resources << /Font << /F1 3 0 R >> >> /Contents " + contents + " 0 R >>");
            String stream = content(p + 1, pages, width, height, rows);
            objects.add("<< /Length " + stream.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n"
                    + stream + "\nendstream");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, "%PDF-1.4\n%âãÏÓ\n");
        int[] offsets = new int[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = out.size();
            put(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            String digits = Integer.toString(offset);
            table.append("0".repeat(10 - digits.length())).append(digits).append(" 00000 n \n");
        }
        put(out, table + "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref
                + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static String content(int page, int pages, float width, float height, int rows) {
        StringBuilder s = new StringBuilder();
        float top = height - 72;
        s.append("BT /F1 18 Tf 72 ").append(num(top)).append(" Td (").append(MARKER)
                .append(" page ").append(page).append(" of ").append(pages).append(") Tj ET\n");
        s.append("BT /F1 11 Tf 72 ").append(num(top - 24))
                .append(" Td (The quick brown fox jumps over the lazy dog.) Tj ET\n");
        float y = top - 56;
        for (int r = 0; r < rows && y > 72; r++, y -= 16) {
            int row = (page - 1) * rows + r + 1;
            s.append("BT /F1 9 Tf 72 ").append(num(y)).append(" Td (Row ").append(row)
                    .append(") Tj ET\n");
            s.append("BT /F1 9 Tf 160 ").append(num(y)).append(" Td (Customer ").append(1000 + row)
                    .append(") Tj ET\n");
            s.append("BT /F1 9 Tf ").append(num(width - 160)).append(' ').append(num(y))
                    .append(" Td (").append(row * 37 % 1000).append(".").append(row % 100 < 10 ? "0" : "")
                    .append(row % 100).append(") Tj ET\n");
            s.append("0.5 w 72 ").append(num(y - 4)).append(" m ").append(num(width - 72)).append(' ')
                    .append(num(y - 4)).append(" l S\n");
        }
        return s.toString();
    }

    private static String num(float v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Float.toString(v);
    }

    private static void put(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }
}

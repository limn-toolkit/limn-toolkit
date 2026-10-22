package limn.print.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The document's text as Type 3 fonts (ADR 078 §7.2): each glyph a drawing of its outline, written
 * once, and each code of a font mapped back to the characters it stands for, so the text of the PDF
 * can be selected, copied and searched.
 *
 * <p>A Type 3 font has 256 codes, so a face is written as as many fonts as it needs: a code is a
 * glyph together with the text it stands for, and the same glyph standing for other text (a mark
 * that belongs to one cluster here and another there) takes a second code that draws the same
 * picture. A glyph's drawing is written the first time a page uses it; the fonts themselves, whose
 * codes are only all known at the end, are written last.
 */
final class PdfFonts {

    /** One Type 3 font: up to 256 codes of one face. */
    static final class Subset {
        final int number;
        final String resource;
        final GlyphBank.Face face;
        final List<Integer> procs = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Float> widths = new ArrayList<>();
        final Map<String, Integer> codes = new HashMap<>();

        Subset(int number, String resource, GlyphBank.Face face) {
            this.number = number;
            this.resource = resource;
            this.face = face;
        }

        boolean full() {
            return procs.size() >= 256;
        }
    }

    /** Where a glyph is: which font and which code. */
    record Placed(Subset subset, int code, float width) {
    }

    private final PdfFile file;
    private final GlyphBank glyphs;
    private final Map<Integer, List<Subset>> byFace = new LinkedHashMap<>();
    private final List<Subset> all = new ArrayList<>();
    private final Map<Long, Integer> procs = new HashMap<>();
    private final Map<Long, Float> procWidths = new HashMap<>();
    /** Each drawing's box, in font units: what a font's box and its descriptor's ascent are made of. */
    private final Map<Integer, float[]> procBoxes = new HashMap<>();

    PdfFonts(PdfFile file, GlyphBank glyphs) {
        this.file = file;
        this.glyphs = glyphs;
    }

    /**
     * @param face    the glyph's face, known to the bank
     * @param glyphId the glyph
     * @param text    the characters it stands for, possibly empty
     * @param width   its advance as first seen, in font units
     * @return the font and code that draw it
     */
    Placed place(GlyphBank.Face face, int glyphId, String text, float width) throws IOException {
        long key = ((long) face.faceId() << 32) | (glyphId & 0xFFFFFFFFL);
        Integer proc = procs.get(key);
        if (proc == null) {
            proc = writeProc(face, glyphId, width);
            procs.put(key, proc);
            procWidths.put(key, width);
        }
        float procWidth = procWidths.get(key);
        String codeKey = glyphId + "\u0000" + text;
        List<Subset> subsets = byFace.computeIfAbsent(face.faceId(), id -> new ArrayList<>());
        for (Subset subset : subsets) {
            Integer code = subset.codes.get(codeKey);
            if (code != null) {
                return new Placed(subset, code, procWidth);
            }
        }
        Subset target = subsets.isEmpty() || subsets.get(subsets.size() - 1).full()
                ? newSubset(face, subsets) : subsets.get(subsets.size() - 1);
        int code = target.procs.size();
        target.procs.add(proc);
        target.texts.add(text);
        target.widths.add(procWidth);
        target.codes.put(codeKey, code);
        return new Placed(target, code, procWidth);
    }

    private Subset newSubset(GlyphBank.Face face, List<Subset> subsets) {
        Subset made = new Subset(file.reserve(), "F" + (all.size() + 1), face);
        subsets.add(made);
        all.add(made);
        return made;
    }

    /**
     * A glyph's drawing: its advance and box ({@code d1}, so it takes the colour of the text), then
     * its outline filled by the non-zero rule, and stroked as well for a synthetic bold.
     */
    private int writeProc(GlyphBank.Face face, int glyphId, float width) throws IOException {
        GlyphBank.Outline outline = glyphs.kept(face.faceId(), glyphId);
        StringBuilder ops = new StringBuilder(64 + outline.points().length * 8);
        float[] p = outline.points();
        float x0 = 0;
        float y0 = 0;
        float x1 = 0;
        float y1 = 0;
        if (p.length > 0) {
            x0 = Float.MAX_VALUE;
            y0 = Float.MAX_VALUE;
            x1 = -Float.MAX_VALUE;
            y1 = -Float.MAX_VALUE;
            for (int i = 0; i + 1 < p.length; i += 2) {
                x0 = Math.min(x0, p[i]);
                x1 = Math.max(x1, p[i]);
                y0 = Math.min(y0, p[i + 1]);
                y1 = Math.max(y1, p[i + 1]);
            }
        }
        float bold = face.syntheticBold() ? face.unitsPerEm() / 24f : 0;
        ops.append(Pdf.num(width)).append(" 0 ").append(Pdf.num(Math.floor(x0 - bold))).append(' ')
                .append(Pdf.num(Math.floor(y0 - bold))).append(' ').append(Pdf.num(Math.ceil(x1 + bold)))
                .append(' ').append(Pdf.num(Math.ceil(y1 + bold))).append(" d1\n");
        if (!outline.isEmpty()) {
            if (bold > 0) {
                ops.append(Pdf.num(bold)).append(" w 1 j\n");
            }
            PdfPaths.outline(outline, ops);
            ops.append(bold > 0 ? "B\n" : "f\n");
        }
        int number = file.stream("", ops.toString().getBytes(StandardCharsets.ISO_8859_1), true);
        procBoxes.put(number, new float[] {x0 - bold, y0 - bold, x1 + bold, y1 + bold});
        return number;
    }

    /** Writes every font and its map back to Unicode; once, at the end of the document. */
    void finish() throws IOException {
        for (Subset subset : all) {
            int toUnicode = file.stream("", toUnicode(subset).getBytes(StandardCharsets.ISO_8859_1), true);
            double scale = 1.0 / subset.face.unitsPerEm();
            // The box of every drawing the font holds, and a descriptor saying how far it reaches
            // above and below the baseline: a reader that lays the text out to extract it (to copy
            // it, to search it) needs an ascent and a descent, and finds no text without them.
            float bx0 = 0;
            float by0 = 0;
            float bx1 = 0;
            float by1 = 0;
            for (int proc : subset.procs) {
                float[] box = procBoxes.get(proc);
                bx0 = Math.min(bx0, box[0]);
                by0 = Math.min(by0, box[1]);
                bx1 = Math.max(bx1, box[2]);
                by1 = Math.max(by1, box[3]);
            }
            float em = subset.face.unitsPerEm();
            float ascent = Math.max(by1, em * 0.7f);
            float descent = Math.min(by0, -em * 0.2f);
            String bbox = "[" + Pdf.num(Math.floor(bx0)) + " " + Pdf.num(Math.floor(descent)) + " "
                    + Pdf.num(Math.ceil(Math.max(bx1, em / 2))) + " " + Pdf.num(Math.ceil(ascent)) + "]";
            String fontName = Pdf.name(subset.resource + "+" + subset.face.name().replaceAll("[^A-Za-z0-9-]", ""));
            int descriptor = file.object("<</Type/FontDescriptor/FontName" + fontName + "/Flags 32/FontBBox" + bbox
                    + "/ItalicAngle 0/Ascent " + Pdf.num(Math.ceil(ascent)) + "/Descent "
                    + Pdf.num(Math.floor(descent)) + "/CapHeight " + Pdf.num(Math.ceil(ascent)) + "/StemV 80>>");
            StringBuilder dict = new StringBuilder(256 + subset.procs.size() * 24);
            dict.append("<</Type/Font/Subtype/Type3/Name/").append(subset.resource)
                    .append("/FontBBox").append(bbox).append("/FontDescriptor ").append(descriptor)
                    .append(" 0 R/FontMatrix[").append(Pdf.fine(scale)).append(" 0 0 ")
                    .append(Pdf.fine(scale)).append(" 0 0]/CharProcs<<");
            for (int code = 0; code < subset.procs.size(); code++) {
                dict.append("/g").append(code).append(' ').append(subset.procs.get(code)).append(" 0 R");
            }
            dict.append(">>/Encoding<</Type/Encoding/Differences[0");
            for (int code = 0; code < subset.procs.size(); code++) {
                dict.append("/g").append(code);
            }
            dict.append("]>>/FirstChar 0/LastChar ").append(subset.procs.size() - 1).append("/Widths[");
            for (int code = 0; code < subset.widths.size(); code++) {
                dict.append(code == 0 ? "" : " ").append(Pdf.num(subset.widths.get(code)));
            }
            dict.append("]/Resources<<>>/ToUnicode ").append(toUnicode).append(" 0 R>>");
            file.object(subset.number, dict.toString());
        }
    }

    /** @return every font, to name them in a page's resources */
    List<Subset> fonts() {
        return all;
    }

    private static String toUnicode(Subset subset) {
        StringBuilder cmap = new StringBuilder(512 + subset.texts.size() * 20);
        cmap.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                .append("/CIDSystemInfo <</Registry (Adobe) /Ordering (UCS) /Supplement 0>> def\n")
                .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
                .append("1 begincodespacerange\n<00> <FF>\nendcodespacerange\n");
        List<Integer> mapped = new ArrayList<>();
        for (int code = 0; code < subset.texts.size(); code++) {
            if (!subset.texts.get(code).isEmpty()) {
                mapped.add(code);
            }
        }
        for (int start = 0; start < mapped.size(); start += 100) {
            int end = Math.min(mapped.size(), start + 100);
            cmap.append(end - start).append(" beginbfchar\n");
            for (int i = start; i < end; i++) {
                int code = mapped.get(i);
                cmap.append(String.format(java.util.Locale.ROOT, "<%02X> <", code));
                String text = subset.texts.get(code);
                for (int c = 0; c < text.length(); c++) {
                    cmap.append(String.format(java.util.Locale.ROOT, "%04X", (int) text.charAt(c)));
                }
                cmap.append(">\n");
            }
            cmap.append("endbfchar\n");
        }
        cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return cmap.toString();
    }
}

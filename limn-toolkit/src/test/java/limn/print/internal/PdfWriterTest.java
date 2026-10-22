package limn.print.internal;

import limn.backend.spi.GlyphOutlines;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.LinearGradient;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.graphics.Shadow;
import limn.graphics.ShapedText;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PDF writer over a recorded page: the file is well formed (every object the table names is
 * where the table says, every reference resolves), the same page gives the same bytes, and the text
 * keeps its characters in the fonts' maps back to Unicode.
 */
class PdfWriterTest {

    /** A face whose every glyph is a square with a hole, 1000 units to the em. */
    static final GlyphOutlines SQUARES = new GlyphOutlines() {
        @Override
        public int unitsPerEm(int faceId) {
            return faceId == 7 ? 1000 : 0;
        }

        @Override
        public String faceName(int faceId) {
            return "Squares";
        }

        @Override
        public boolean isSyntheticBold(int faceId) {
            return false;
        }

        @Override
        public boolean outline(int faceId, int glyphId, Path2D.Visitor out) {
            if (faceId != 7) {
                return false;
            }
            if (glyphId == 3) {
                return true; // a space: no ink
            }
            out.moveTo(50, 0);
            out.lineTo(550, 0);
            out.lineTo(550, 700);
            out.lineTo(50, 700);
            out.close();
            out.moveTo(150, 100);
            out.quadTo(150, 600, 450, 600);
            out.lineTo(450, 100);
            out.close();
            return true;
        }

        @Override
        public List<Piece> cluster(String characters, Font font) {
            return List.of();
        }
    };

    static ShapedText line(String text, Font font) {
        ShapedText.Builder b = ShapedText.builder(text, font, ShapedText.Direction.LTR, text.length());
        b.lineMetrics(font.size() * 0.8f, font.size() * 0.2f, font.size() * 1.2f);
        b.run(7, 0, text.length(), 0);
        for (int i = 0; i < text.length(); i++) {
            b.glyph(text.charAt(i) == ' ' ? 3 : 10 + text.charAt(i), i, font.size() * 0.6f, 0, 0);
        }
        return b.build();
    }

    static byte[] write(Recording page) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // The bank filled while the page was recorded.
        PdfWriter writer = new PdfWriter(out, BANKS.get(page), "Test page", Locale.forLanguageTag("pt-BR"), 300f / 96);
        writer.page(page, 595.28f, 841.89f);
        writer.finish();
        return out.toByteArray();
    }

    static final java.util.Map<Recording, GlyphBank> BANKS = new java.util.IdentityHashMap<>();

    static Recording samplePage() {
        GlyphBank bank = new GlyphBank(SQUARES);
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, 300f / 96, bank);
        Font font = new Font("Squares", 13, false, false);
        canvas.fillRect(10, 10, 100, 50, Color.rgb(0x3366CC));
        canvas.drawRoundRect(RoundRect.of(20, 80, 200, 60, 12), 1, Color.BLACK);
        canvas.drawShadow(RoundRect.of(20, 160, 200, 60, 12), new Shadow(0, 4, 12, Color.rgba(0, 0.3f)));
        canvas.fillRoundRect(RoundRect.of(20, 160, 200, 60, 12), new LinearGradient(20, 160, 220, 160,
                Color.WHITE, Color.rgba(0xFF0000, 0.5f)));
        canvas.save();
        canvas.translate(40, 300);
        canvas.saveLayer(0, 0, 300, 100, 0.5f);
        canvas.fillCircle(50, 50, 40, Color.rgb(0x00AA00));
        canvas.restore();
        canvas.restore();
        byte[] px = new byte[4 * 4 * 4];
        for (int i = 0; i < 16; i++) {
            px[i * 4] = (byte) (i * 16);
            px[i * 4 + 3] = (byte) (i < 8 ? 255 : 128);
        }
        canvas.drawImage(new Image(4, 4, px), 300, 10, 40, 40);
        Path2D path = new Path2D().moveTo(400, 10).lineTo(450, 60).cubicTo(460, 70, 470, 80, 400, 90).close();
        canvas.fillPath(path, Color.BLACK);
        path.reset(); // the recording kept its own copy
        canvas.drawText(line("Pedido 4512", font), 30, 500, Color.BLACK);
        canvas.drawText(line("Total R$ 10", font), 30, 520, Color.rgba(0x000000, 0.6f));
        Recording page = canvas.finish();
        BANKS.put(page, bank);
        return page;
    }

    @Test
    void theFileIsWellFormed() throws IOException {
        byte[] pdf = write(samplePage());
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.7\n"));
        assertTrue(text.endsWith("%%EOF\n"));
        Matcher startxref = Pattern.compile("startxref\n(\\d+)\n%%EOF\n$").matcher(text);
        assertTrue(startxref.find());
        int xref = Integer.parseInt(startxref.group(1));
        assertTrue(text.startsWith("xref\n", xref));
        Matcher size = Pattern.compile("xref\n0 (\\d+)\n").matcher(text.substring(xref));
        assertTrue(size.find());
        int objects = Integer.parseInt(size.group(1));
        int at = xref + size.end() + 20; // past the free entry
        for (int number = 1; number < objects; number++) {
            int offset = Integer.parseInt(text.substring(at, at + 10));
            assertTrue(text.startsWith(number + " 0 obj\n", offset), "object " + number + " is where the table says");
            at += 20;
        }
        Matcher reference = Pattern.compile("(\\d+) 0 R").matcher(text);
        while (reference.find()) {
            int number = Integer.parseInt(reference.group(1));
            assertTrue(number > 0 && number < objects, "reference to " + number + " resolves");
        }
        assertTrue(text.contains("/Lang<FEFF00700074002D00420052>"), "the language of the document");
        assertTrue(text.contains("/Subtype/Type3"));
    }

    @Test
    void theSamePageGivesTheSameBytes() throws IOException {
        assertArrayEquals(write(samplePage()), write(samplePage()));
    }

    @Test
    void theFontsMapTheirCodesBackToTheCharacters() throws IOException {
        byte[] pdf = write(samplePage());
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        // The ToUnicode stream is compressed; inflate every stream and look for the map.
        StringBuilder maps = new StringBuilder();
        Matcher stream = Pattern.compile("(?<!end)stream\n").matcher(text);
        while (stream.find()) {
            int start = stream.end();
            int end = text.indexOf("\nendstream", start);
            byte[] packed = text.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
            try {
                java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                inflater.setInput(packed);
                byte[] buffer = new byte[1 << 16];
                int n = inflater.inflate(buffer);
                maps.append(new String(buffer, 0, n, StandardCharsets.ISO_8859_1));
            } catch (java.util.zip.DataFormatException notCompressed) {
                // An uncompressed stream: not the map.
            }
        }
        String all = maps.toString();
        for (char c : "Pedido 4512Total R$".toCharArray()) {
            if (c != ' ') {
                assertTrue(all.contains(String.format("<%04X>", (int) c)), "a code maps back to " + c);
            }
        }
        assertTrue(all.contains("] TJ"), "the text is drawn with text operators");
    }

    /** Writes the sample page where the build can open it, for a look by eye or by an oracle. */
    @Test
    void writesASampleForOracles() throws IOException {
        Path out = Path.of("build", "print-samples");
        Files.createDirectories(out);
        Files.write(out.resolve("writer-sample.pdf"), write(samplePage()));
        assertEquals(true, Files.size(out.resolve("writer-sample.pdf")) > 500);
    }
}

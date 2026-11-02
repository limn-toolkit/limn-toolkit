package limn.print.internal;

import limn.graphics.Color;
import limn.math.Affine2D;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transform calls of the printing's two canvases: the recording canvas answers the matrix it
 * keeps and replays a shear and a concatenation as the calls they were, and the PDF canvas writes
 * each as one {@code cm} and answers the matrix from the page's corner, inside a layer's form too.
 */
class CanvasTransformPrintTest {

    private static final double EPS = 1e-6;

    private static void assertSame(Affine2D expected, Affine2D actual) {
        assertEquals(expected.a(), actual.a(), EPS, "a of " + actual);
        assertEquals(expected.b(), actual.b(), EPS, "b of " + actual);
        assertEquals(expected.c(), actual.c(), EPS, "c of " + actual);
        assertEquals(expected.d(), actual.d(), EPS, "d of " + actual);
        assertEquals(expected.e(), actual.e(), EPS, "e of " + actual);
        assertEquals(expected.f(), actual.f(), EPS, "f of " + actual);
    }

    @Test
    void theRecordingCanvasAnswersTheMatrixItKeeps() {
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, 1, new GlyphBank(PdfWriterTest.SQUARES));
        assertSame(Affine2D.IDENTITY, canvas.transform());
        canvas.translate(10, 20);
        canvas.save();
        canvas.shear(0.5f, 0);
        canvas.concat(Affine2D.rotation(0.3));
        canvas.rotate(0.2f, 5, 5);
        assertSame(Affine2D.translation(10, 20).shear(0.5f, 0).rotate(0.3).concat(Affine2D.rotation(0.2f, 5, 5)),
                canvas.transform());
        canvas.saveLayer(0, 0, 100, 100, 0.5f);
        assertSame(Affine2D.translation(10, 20).shear(0.5f, 0).rotate(0.3).concat(Affine2D.rotation(0.2f, 5, 5)),
                canvas.transform());
        canvas.restore();
        canvas.restore();
        assertSame(Affine2D.translation(10, 20), canvas.transform());
    }

    @Test
    void aRecordingReplaysAShearAndAConcatenationAsTheCallsTheyWere() {
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, 1, new GlyphBank(PdfWriterTest.SQUARES));
        Affine2D m = new Affine2D(1, 0.25, -0.5, 2, 7, 8);
        canvas.shear(0.5f, 0.1f);
        canvas.concat(m);
        canvas.fillRect(0, 0, 10, 10, Color.BLACK);
        List<String> calls = new ArrayList<>();
        canvas.finish().replay(new NoopCanvas(794, 1123) {
            @Override
            public void shear(float sx, float sy) {
                calls.add("shear " + sx + " " + sy);
            }

            @Override
            public void concat(Affine2D matrix) {
                calls.add("concat " + matrix.equals(m));
            }
        });
        assertEquals(List.of("shear 0.5 0.1", "concat true"), calls);
    }

    @Test
    void thePdfCanvasWritesOneCmPerCallAndAnswersFromThePagesCorner() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GlyphBank bank = new GlyphBank(PdfWriterTest.SQUARES);
        PdfWriter writer = new PdfWriter(out, bank, "Transforms", Locale.ROOT, 1);
        PdfCanvas canvas = new PdfCanvas(writer, 794, 1123, 595.28f, 841.89f);
        canvas.translate(10, 20);
        canvas.shear(0.5f, 0);
        assertSame(Affine2D.translation(10, 20).shear(0.5, 0), canvas.transform());
        canvas.saveLayer(0, 0, 100, 100, 0.5f);
        canvas.concat(new Affine2D(2, 0, 0, 2, 3, 4));
        assertSame(Affine2D.translation(10, 20).shear(0.5, 0).concat(new Affine2D(2, 0, 0, 2, 3, 4)),
                canvas.transform());
        canvas.save();
        canvas.translate(1, 1);
        assertSame(Affine2D.translation(10, 20).shear(0.5, 0).concat(new Affine2D(2, 0, 0, 2, 3, 4)).translate(1, 1),
                canvas.transform());
        canvas.fillRect(0, 0, 10, 10, Color.BLACK);
        canvas.restore();
        canvas.restore();
        assertSame(Affine2D.translation(10, 20).shear(0.5, 0), canvas.transform());
        PdfCanvas.Content content = canvas.finish();
        assertTrue(content.ops().contains("1 0 0.5 1 0 0 cm\n"), content.ops());
        writer.finish();
        String all = inflatedForms(out.toByteArray());
        assertTrue(all.contains("2 0 0 2 3 4 cm\n"), "the concatenation inside the layer's form: " + all);
    }

    /** Every stream of the file, inflated where it is compressed, joined. */
    private static String inflatedForms(byte[] pdf) {
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        StringBuilder all = new StringBuilder();
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
                all.append(new String(buffer, 0, n, StandardCharsets.ISO_8859_1));
            } catch (java.util.zip.DataFormatException notCompressed) {
                all.append(text, start, end);
            }
        }
        return all.toString();
    }
}

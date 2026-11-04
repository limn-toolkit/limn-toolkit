package limn.print.internal;

import limn.graphics.Color;
import limn.graphics.FillRule;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A printed path is filled under its rule: the PDF canvas writes every subpath and then one
 * {@code f} for non-zero or {@code f*} for even-odd, where it once filled each subpath on its own,
 * and a recording replays the path with its rule.
 */
class PdfFillRuleTest {

    private static Path2D squareWithHole(FillRule rule) {
        Path2D path = new Path2D().setFillRule(rule);
        path.moveTo(20, 20).lineTo(100, 20).lineTo(100, 100).lineTo(20, 100).close();
        return path.moveTo(40, 40).lineTo(40, 80).lineTo(80, 80).lineTo(80, 40).close();
    }

    private static String ops(Path2D path) throws IOException {
        PdfWriter writer = new PdfWriter(new ByteArrayOutputStream(), new GlyphBank(PdfWriterTest.SQUARES),
                "Fill rules", Locale.ROOT, 1);
        PdfCanvas canvas = new PdfCanvas(writer, 794, 1123, 595.28f, 841.89f);
        canvas.fillPath(path, Color.BLACK);
        return canvas.finish().ops();
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void everySubpathIsFilledInOneOperatorUnderNonZero() throws IOException {
        String ops = ops(squareWithHole(FillRule.NON_ZERO));
        assertEquals(2, count(ops, "h\n"), ops);
        assertEquals(1, count(ops, "\nf\n"), "one fill for both subpaths: " + ops);
        assertEquals(0, count(ops, "f*"), ops);
    }

    @Test
    void evenOddIsWrittenAsItsOwnOperator() throws IOException {
        String ops = ops(squareWithHole(FillRule.EVEN_ODD));
        assertEquals(2, count(ops, "h\n"), ops);
        assertEquals(1, count(ops, "\nf*\n"), ops);
    }

    @Test
    void anEmptyPathWritesNothing() throws IOException {
        assertTrue(!ops(new Path2D()).contains("f"), "no fill for no path");
    }

    @Test
    void aRecordingReplaysThePathWithItsRule() {
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, 1, new GlyphBank(PdfWriterTest.SQUARES));
        canvas.fillPath(squareWithHole(FillRule.EVEN_ODD), Color.BLACK);
        List<FillRule> rules = new ArrayList<>();
        canvas.finish().replay(new NoopCanvas(794, 1123) {
            @Override
            public void fillPath(Path2D path, Paint paint) {
                rules.add(path.fillRule());
            }
        });
        assertEquals(List.of(FillRule.EVEN_ODD), rules);
    }
}

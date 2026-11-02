package limn.print.internal;

import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.Stroke;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A printed {@link Stroke} is PDF's own line operators, each value read as the stroke reads it, and
 * a recording replays the call with its stroke.
 */
class PdfStrokeTest {

    private static String ops(Path2D path, Stroke stroke) throws IOException {
        PdfWriter writer = new PdfWriter(new ByteArrayOutputStream(), new GlyphBank(PdfWriterTest.SQUARES),
                "Strokes", Locale.ROOT, 1);
        PdfCanvas canvas = new PdfCanvas(writer, 794, 1123, 595.28f, 841.89f);
        canvas.drawPath(path, stroke, Color.BLACK);
        return canvas.finish().ops();
    }

    private static Path2D zigzag() {
        return new Path2D().moveTo(10, 10).lineTo(50, 60).lineTo(90, 10);
    }

    @Test
    void widthCapJoinAndMiterLimitAreTheLineOperators() throws IOException {
        assertTrue(ops(zigzag(), Stroke.of(2)).contains("2 w 0 J 0 j 4 M\n"), "butt, miter, 4");
        assertTrue(ops(zigzag(), Stroke.of(3).withCap(Stroke.Cap.ROUND).withJoin(Stroke.Join.ROUND))
                .contains("3 w 1 J 1 j 4 M\n"));
        assertTrue(ops(zigzag(), Stroke.of(1.5f).withCap(Stroke.Cap.SQUARE).withJoin(Stroke.Join.BEVEL)
                .withMiterLimit(10)).contains("1.5 w 2 J 2 j 10 M\n"));
        String ops = ops(zigzag(), Stroke.of(2));
        assertTrue(ops.endsWith("S\nQ\n"), ops);
        assertFalse(ops.contains(" d\n"), "a solid stroke sets no dash: " + ops);
    }

    @Test
    void aDashPatternAndItsPhaseAreTheDashOperator() throws IOException {
        assertTrue(ops(zigzag(), Stroke.of(2).withDash(4, 2).withDashPhase(1)).contains("[4 2] 1 d\n"));
        assertTrue(ops(zigzag(), Stroke.of(2).withDash(3)).contains("[3 3] 0 d\n"), "an odd pattern doubled");
    }

    @Test
    void aPatternTheStrokeWouldNotFollowIsWrittenSolid() throws IOException {
        Path2D long_ = new Path2D().moveTo(0, 0).lineTo(300_000, 0);
        String ops = ops(long_, Stroke.of(1).withDash(0.5f, 0.5f)); // 300,000 dashes
        assertFalse(ops.contains(" d\n"), ops);
    }

    @Test
    void nothingIsWrittenForNoWidthOrNoPath() throws IOException {
        assertFalse(ops(zigzag(), Stroke.of(0)).contains("S"));
        assertFalse(ops(new Path2D(), Stroke.of(2)).contains("S"));
    }

    @Test
    void aRecordingReplaysTheStroke() {
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, 1, new GlyphBank(PdfWriterTest.SQUARES));
        Stroke stroke = Stroke.of(2).withDash(4, 2);
        canvas.drawLine(0, 0, 10, 0, stroke, Color.BLACK);
        List<Stroke> replayed = new ArrayList<>();
        canvas.finish().replay(new NoopCanvas(794, 1123) {
            @Override
            public void drawPath(Path2D path, Stroke s, Paint paint) {
                replayed.add(s);
            }
        });
        assertEquals(List.of(stroke), replayed);
    }
}

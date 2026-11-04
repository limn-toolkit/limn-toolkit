package limn.print.internal;

import limn.graphics.Color;
import limn.graphics.Gradient;
import limn.graphics.Image;
import limn.graphics.ImagePaint;
import limn.graphics.Paint;
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
 * A printed {@link Gradient} is PDF's own axial or radial shading with a function stitched from its
 * stops, over as many turns as a repeat spans, and a sweep a mesh of triangles; an {@link ImagePaint}
 * is a tiling pattern; and a recording keeps both.
 */
class PdfPaintTest {

    private static final Color RED = Color.rgb(0xFF0000);
    private static final Color GREEN = Color.rgb(0x00FF00);
    private static final Color BLUE = Color.rgb(0x0000FF);

    private static String file(Paint paint) throws IOException {
        GlyphBank bank = new GlyphBank(PdfWriterTest.SQUARES);
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, 1, bank);
        canvas.fillRect(10, 10, 200, 100, paint);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(out, bank, "Paints", Locale.ROOT, 1);
        writer.page(canvas.finish(), 595.28f, 841.89f);
        writer.finish();
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void aGradientPaintedOverAndOverIsWrittenOnce() throws IOException {
        // A hundred striped chips: each wrote its shading, its pattern and its alpha's mask anew,
        // and a repeated gradient's shading spans the page, a function per stripe.
        Gradient stripes = Gradient.linear(0, 0, 4, 0, new float[] {0, 0.5f, 0.5f, 1},
                RED, RED, Color.rgba(0x0000FF, 0.5f), Color.rgba(0x0000FF, 0.5f))
                .withSpread(Gradient.Spread.REPEAT);
        GlyphBank bank = new GlyphBank(PdfWriterTest.SQUARES);
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, 1, bank);
        for (int i = 0; i < 100; i++) {
            canvas.fillRect(10 + (i % 10) * 70, 10 + (i / 10) * 30, 60, 20, stripes);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(out, bank, "Chips", Locale.ROOT, 1);
        writer.page(canvas.finish(), 595.28f, 841.89f);
        writer.finish();
        String pdf = out.toString(StandardCharsets.ISO_8859_1);
        assertEquals(2, count(pdf, "/ShadingType 2"), "one shading of the colours and one of the alpha");
        assertEquals(1, count(pdf, "/PatternType 2"), "one pattern");
        assertEquals(1, count(pdf, "/S/Luminosity"), "one soft mask");
        assertTrue(pdf.length() < 200_000, "the file: " + pdf.length() + " bytes");
    }

    @Test
    void aFadeToClearKeepsItsColourOnThePageAsOnScreen() throws IOException {
        // The canvas runs a gradient's colours premultiplied, so red fading to clear stays red as it
        // thins. PDF runs its colours straight, the alpha in a soft mask: the stops were written as
        // they are and the fade went through dark red to black.
        for (Paint fade : List.<Paint>of(Gradient.linear(0, 0, 100, 0, RED, Color.TRANSPARENT),
                new limn.graphics.LinearGradient(0, 0, 100, 0, RED, Color.TRANSPARENT))) {
            String pdf = file(fade);
            Matcher rgb = Pattern.compile("/C0\\[([0-9.]+) ([0-9.]+) ([0-9.]+)\\]/C1\\[([0-9.]+) ([0-9.]+) ([0-9.]+)\\]")
                    .matcher(pdf);
            int runs = 0;
            while (rgb.find()) {
                runs++;
                for (int g = 1; g <= 6; g++) {
                    float expected = g == 1 || g == 4 ? 1 : 0;
                    assertEquals(expected, Float.parseFloat(rgb.group(g)), 1e-6, fade + ": every colour red, " + rgb.group());
                }
            }
            assertTrue(runs > 0, fade + ": an RGB run written");
        }
    }

    @Test
    void betweenTwoPartlyOpaqueStopsTheColourIsThePremultipliedMix() {
        // Red at alpha 1 to blue at alpha 0.25: at the middle the canvas mixes 0.5 of red's
        // weight 1 with 0.5 of blue's 0.25, so 0.8 red and 0.2 blue, at alpha 0.625.
        PdfGradients.Stops stops = PdfGradients.premultiplied(new float[] {0, 1},
                List.of(RED, Color.rgba(0x0000FF, 0.25f)));
        assertEquals(17, stops.positions().length, "the stretch cut into chords");
        Color middle = stops.colors().get(8);
        assertEquals(0.5f, stops.positions()[8], 1e-6);
        assertEquals(0.8f, middle.r(), 1e-5);
        assertEquals(0.2f, middle.b(), 1e-5);
        assertEquals(0.625f, middle.a(), 1e-5);
        PdfGradients.Stops even = PdfGradients.premultiplied(new float[] {0, 1}, List.of(RED, BLUE));
        assertEquals(2, even.positions().length, "equally opaque stops kept as they are");
    }

    @Test
    void aLinearGradientIsAnAxialShadingStitchedFromItsStops() throws IOException {
        String pdf = file(Gradient.linear(0, 0, 100, 0, new float[] {0, 0.25f, 1}, RED, GREEN, BLUE));
        assertTrue(pdf.contains("/ShadingType 2"), "axial");
        assertTrue(pdf.contains("/Coords[0 0 100 0]/Domain[0 1]"), "from its start to its end");
        assertTrue(pdf.contains("/Bounds[0.25]"), "one bound between two stretches");
        assertTrue(pdf.contains("/C0[1 0 0]/C1[0 1 0]") && pdf.contains("/C0[0 1 0]/C1[0 0 1]"), "each stretch");
    }

    @Test
    void aHardEdgeIsABoundAndNoStretchOfNothing() throws IOException {
        String pdf = file(Gradient.linear(0, 0, 100, 0, new float[] {0, 0.5f, 0.5f, 1}, RED, RED, BLUE, BLUE));
        assertTrue(pdf.contains("/Bounds[0.5]"), pdf.substring(pdf.indexOf("/FunctionType 3")).substring(0, 200));
        assertEquals(2, count(pdf, "/FunctionType 2"), "two stretches: red, then blue");
    }

    @Test
    void aRepeatIsWrittenTurnByTurnOverWhatItPaintsAndAReflectionBackwards() throws IOException {
        // 40 units a turn over a page 794 wide: about twenty turns each way of what may be painted.
        String repeat = file(Gradient.linear(0, 0, 40, 0, RED, BLUE).withSpread(Gradient.Spread.REPEAT));
        Matcher domain = Pattern.compile("/ShadingType 2/ColorSpace/DeviceRGB/Coords\\[[^]]*]/Domain\\[(-?\\d+) (-?\\d+)]")
                .matcher(repeat);
        assertTrue(domain.find(), "a domain of whole turns");
        int turns = Integer.parseInt(domain.group(2)) - Integer.parseInt(domain.group(1));
        assertTrue(turns >= 20, "turns: " + turns);
        assertEquals(turns, count(repeat, "/FunctionType 2"), "a stretch a turn");
        String reflect = file(Gradient.linear(0, 0, 40, 0, RED, BLUE).withSpread(Gradient.Spread.REFLECT));
        assertTrue(reflect.contains("/C0[0 0 1]/C1[1 0 0]"), "a turn read backwards");
    }

    @Test
    void aRadialGradientRunsFromItsFocusAsACircleOfNoRadius() throws IOException {
        String pdf = file(Gradient.radial(100, 60, 50, RED, BLUE).withFocus(80, 60));
        assertTrue(pdf.contains("/ShadingType 3"), "radial");
        assertTrue(pdf.contains("/Coords[80 60 0 100 60 50]"), "from the focus to the circle");
    }

    @Test
    void aSweepIsAMeshOfTrianglesRoundItsCentre() throws IOException {
        String pdf = file(Gradient.sweep(100, 60, 0, RED, GREEN, BLUE));
        assertTrue(pdf.contains("/ShadingType 4"), "free-form triangles");
        assertTrue(pdf.contains("/BitsPerCoordinate 32/BitsPerComponent 16/BitsPerFlag 8"));
        byte[] mesh = PdfGradients.meshData(100, 60, 0, 900, new float[] {0, 0.5f, 1},
                new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}});
        int triangles = mesh.length / (3 * (1 + 8 + 6));
        assertEquals(360, triangles, "a degree each");
    }

    @Test
    void stopsOfUnequalAlphaAreASoftMaskOfTheSameGradient() throws IOException {
        String pdf = file(Gradient.linear(0, 0, 100, 0, RED, Color.rgba(0x0000FF, 0.2f)));
        assertTrue(pdf.contains("/SMask<</S/Luminosity"), "a luminosity mask");
        assertTrue(pdf.contains("/ColorSpace/DeviceGray"), "of the alphas as grey");
    }

    @Test
    void anImagePaintIsATilingPatternOfItsPicture() throws IOException {
        Image image = new Image(4, 2, new byte[4 * 2 * 4]);
        String repeat = file(ImagePaint.of(image));
        assertTrue(repeat.contains("/PatternType 1/PaintType 1/TilingType 1/BBox[0 0 4 2]/XStep 4/YStep 2"), "a cell");
        String mirror = file(ImagePaint.of(image).withTile(ImagePaint.Tile.MIRROR, ImagePaint.Tile.REPEAT));
        assertTrue(mirror.contains("/BBox[0 0 8 2]/XStep 8/YStep 2"), "the picture and its mirror");
        String once = file(ImagePaint.of(image).withTile(ImagePaint.Tile.NONE, ImagePaint.Tile.NONE)
                .withTransform(Affine2D.scaling(10, 10)));
        Matcher step = Pattern.compile("/XStep ([\\d.]+)").matcher(once);
        assertTrue(step.find());
        assertTrue(Float.parseFloat(step.group(1)) > 794 / 10f, "a step past what it paints: " + step.group(1));
    }

    @Test
    void aRecordingKeepsGradientsAndImagePaints() {
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, 1, new GlyphBank(PdfWriterTest.SQUARES));
        Gradient g = Gradient.sweep(0, 0, 0, RED, BLUE);
        ImagePaint p = ImagePaint.of(new Image(1, 1, new byte[4]));
        canvas.fillRect(0, 0, 10, 10, g);
        canvas.fillRect(0, 0, 10, 10, p);
        List<Paint> replayed = new ArrayList<>();
        canvas.finish().replay(new NoopCanvas(794, 1123) {
            @Override
            public void fillRect(float x, float y, float w, float h, Paint paint) {
                replayed.add(paint);
            }
        });
        assertEquals(List.of(g, p), replayed);
    }
}

package limn.print.internal;

import limn.backend.spi.OffscreenPainter;
import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.FillRule;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A printed path clip is PDF's own clip under the path's rule; screen, darken and lighten are PDF's
 * own blend modes; and a layer drawn under a Porter-Duff rule, which PDF has none of, reaches the page
 * as a picture the backend draws, at the page's resolution, laid where the layer stood. Without a
 * picture the calls stay as they were.
 */
class PdfClipBlendTest {

    private static final float SCALE = 300f / 96;

    private static String ops(Consumer<Canvas> paint) throws IOException {
        PdfWriter writer = new PdfWriter(new ByteArrayOutputStream(), new GlyphBank(PdfWriterTest.SQUARES),
                "Clips", Locale.ROOT, 1);
        PdfCanvas canvas = new PdfCanvas(writer, 794, 1123, 595.28f, 841.89f);
        paint.accept(canvas);
        return canvas.finish().ops();
    }

    private static String file(Consumer<Canvas> paint) throws IOException {
        GlyphBank bank = new GlyphBank(PdfWriterTest.SQUARES);
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, SCALE, bank);
        paint.accept(canvas);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(out, bank, "Blends", Locale.ROOT, SCALE);
        writer.page(canvas.finish(), 595.28f, 841.89f);
        writer.finish();
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void aPathClipIsTheClipOperatorUnderThePathsRule() throws IOException {
        Path2D triangle = new Path2D().moveTo(10, 10).lineTo(90, 10).lineTo(50, 80).close();
        String nonZero = ops(c -> c.clipPath(triangle));
        assertTrue(nonZero.contains("10 10 m\n90 10 l\n50 80 l\nh\nW n\n"), nonZero);
        String evenOdd = ops(c -> c.clipPath(triangle.copy().setFillRule(FillRule.EVEN_ODD)));
        assertTrue(evenOdd.endsWith("h\nW* n\n"), evenOdd);
        assertTrue(ops(c -> c.clipPath(new Path2D())).contains("W n\n"), "an empty path still clips, to nothing");
    }

    @Test
    void screenDarkenAndLightenArePdfsOwnModes() throws IOException {
        for (BlendMode mode : new BlendMode[] {BlendMode.SCREEN, BlendMode.DARKEN, BlendMode.LIGHTEN}) {
            String pdf = file(c -> {
                c.setBlendMode(mode);
                c.fillRect(10, 10, 50, 50, Color.rgb(0x3366CC));
            });
            String name = mode == BlendMode.SCREEN ? "/Screen" : mode == BlendMode.DARKEN ? "/Darken" : "/Lighten";
            assertTrue(pdf.contains("/BM" + name), mode + ": " + name);
        }
    }

    @Test
    void aLayerUnderAPorterDuffRuleIsThePictureTheBackendDraws() {
        Painter painter = new Painter();
        Offscreen.install(painter);
        try {
            RecordingCanvas canvas = new RecordingCanvas(794, 1123, SCALE, new GlyphBank(PdfWriterTest.SQUARES));
            canvas.translate(100, 200);
            canvas.scale(2, 2);
            canvas.saveLayer(10, 20, 30, 40, 0.5f);
            canvas.fillRect(10, 20, 30, 40, Color.rgb(0xFF0000));
            canvas.save();
            canvas.setBlendMode(BlendMode.DESTINATION_OUT); // inside a plain save: still the layer's
            canvas.fillCircle(25, 40, 10, Color.BLACK);
            canvas.restore();
            canvas.restore();
            Replay replay = new Replay();
            canvas.finish().replay(replay);
            // At the page's resolution times the scale the layer is drawn at.
            assertEquals(List.of((int) Math.ceil(30 * 2 * SCALE), (int) Math.ceil(40 * 2 * SCALE)), painter.sizes);
            assertEquals(List.of("fillRect", "setBlendMode DESTINATION_OUT", "fillCircle"), painter.drawn,
                    "the layer's calls, drawn off screen");
            assertEquals(List.of("setOpacity 0.5", "drawImage 10,20 30x40"), replay.calls,
                    "the page lays the picture where the layer stood, at its opacity");
        } finally {
            Offscreen.uninstall(painter);
        }
    }

    @Test
    void aPageWhoseOwnDrawingSetsARuleIsOnePicture() {
        Painter painter = new Painter();
        Offscreen.install(painter);
        try {
            RecordingCanvas canvas = new RecordingCanvas(794, 1123, SCALE, new GlyphBank(PdfWriterTest.SQUARES));
            canvas.fillRect(0, 0, 100, 100, Color.rgb(0xFF0000));
            canvas.setBlendMode(BlendMode.CLEAR);
            canvas.fillRect(20, 20, 10, 10, Color.BLACK);
            Replay replay = new Replay();
            canvas.finish().replay(replay);
            assertEquals(List.of("drawImage 0,0 794x1123"), replay.calls);
            assertEquals(List.of((int) Math.ceil(794 * SCALE), (int) Math.ceil(1123 * SCALE)), painter.sizes);
        } finally {
            Offscreen.uninstall(painter);
        }
    }

    @Test
    void withoutAPictureTheCallsStayAndScreenNeedsNone() {
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, SCALE, new GlyphBank(PdfWriterTest.SQUARES));
        canvas.saveLayer(0, 0, 100, 100, 1);
        canvas.fillRect(0, 0, 100, 100, Color.rgb(0xFF0000));
        canvas.setBlendMode(BlendMode.SOURCE_IN);
        canvas.fillRect(20, 20, 10, 10, Color.BLACK);
        canvas.restore();
        canvas.setBlendMode(BlendMode.SCREEN);
        canvas.fillRect(0, 0, 10, 10, Color.BLACK);
        Replay replay = new Replay();
        canvas.finish().replay(replay);
        assertEquals(List.of("fillRect", "setBlendMode SOURCE_IN", "fillRect", "setBlendMode SCREEN", "fillRect"),
                replay.calls, "no backend: the calls as recorded");
    }

    @Test
    void aRecordingReplaysAPathClipWithItsOwnCopy() {
        RecordingCanvas canvas = new RecordingCanvas(794, 1123, 1, new GlyphBank(PdfWriterTest.SQUARES));
        Path2D path = new Path2D().addCircle(50, 50, 20);
        canvas.clipPath(path);
        path.reset(); // a widget reuses its path for the next frame
        List<Path2D> clips = new ArrayList<>();
        canvas.finish().replay(new NoopCanvas(794, 1123) {
            @Override
            public void clipPath(Path2D p) {
                clips.add(p);
            }
        });
        assertEquals(1, clips.size());
        assertFalse(clips.get(0).isEmpty(), "the path as it was when recorded");
        assertEquals(30, clips.get(0).bounds().x(), 1e-3);
    }

    /** A backend's painter that keeps what it was asked to draw and answers a blank picture. */
    private static final class Painter implements OffscreenPainter {
        final List<Integer> sizes = new ArrayList<>();
        final List<String> drawn = new ArrayList<>();

        @Override
        public Image paint(int width, int height, Consumer<Canvas> painter) {
            sizes.add(width);
            sizes.add(height);
            painter.accept(new NoopCanvas(width, height) {
                @Override
                public void fillRect(float x, float y, float w, float h, Paint paint) {
                    drawn.add("fillRect");
                }

                @Override
                public void fillCircle(float cx, float cy, float radius, Paint paint) {
                    drawn.add("fillCircle");
                }

                @Override
                public void setBlendMode(BlendMode mode) {
                    drawn.add("setBlendMode " + mode);
                }
            });
            return new Image(width, height, new byte[width * height * 4]);
        }
    }

    /** A canvas that keeps the drawing calls replayed into it, saves and restores left out. */
    private static final class Replay extends NoopCanvas {
        final List<String> calls = new ArrayList<>();

        Replay() {
            super(794, 1123);
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            calls.add("fillRect");
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
            calls.add("fillCircle");
        }

        @Override
        public void setBlendMode(BlendMode mode) {
            calls.add("setBlendMode " + mode);
        }

        @Override
        public void setOpacity(float opacity) {
            calls.add("setOpacity " + opacity);
        }

        @Override
        public void saveLayer(float x, float y, float width, float height, float opacity) {
        }

        @Override
        public void drawImage(Image image, float x, float y, float w, float h) {
            calls.add("drawImage " + fmt(x) + "," + fmt(y) + " " + fmt(w) + "x" + fmt(h));
        }

        private static String fmt(float v) {
            return v == Math.rint(v) ? Integer.toString((int) v) : Float.toString(v);
        }
    }
}

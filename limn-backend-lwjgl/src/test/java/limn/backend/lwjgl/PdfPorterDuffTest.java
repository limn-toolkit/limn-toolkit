package limn.backend.lwjgl;

import limn.backend.spi.OffscreenPainter;
import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.TextRulers;
import limn.print.Document;
import limn.print.Paper;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A printed layer drawn under a Porter-Duff rule, through the backend's own offscreen painter on a
 * real device: the document asks for the layer's picture at the page's resolution, the canvas draws
 * it with the eraser's hole in it, and the file holds it as an image.
 */
class PdfPorterDuffTest {

    /** A red card with a round hole erased out of it, inside a layer of its own. */
    private static final class Erased extends Widget<Erased> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(80, 60);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.saveLayer(0, 0, 80, 60, 1);
            canvas.fillRect(0, 0, 80, 60, Color.rgb(0xCC2020));
            canvas.setBlendMode(BlendMode.DESTINATION_OUT);
            canvas.fillCircle(40, 30, 20, Color.BLACK);
            canvas.restore();
        }
    }

    @Test
    void anErasedLayerReachesThePageAsItsPictureWithTheHole() throws Exception {
        HeadlessGl.assumeAvailable();
        try (HeadlessUi ui = new HeadlessUi(); FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            TextRulers.install(ruler);
            List<Image> pictures = new ArrayList<>();
            OffscreenPainter painter = (w, h, p) -> {
                Image picture = GlOffscreen.paint(store, w, h, p);
                pictures.add(picture);
                return picture;
            };
            OffscreenPainter.install(painter);
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                AtomicReference<Object> done = new AtomicReference<>();
                Document.of(Paper.A4).title("Eraser").add(new Erased())
                        .writePdf(bytes).onSuccess(done::set).onFailure(done::set).start();
                ui.pumpUntil(() -> done.get() != null, 30_000_000_000L);
                if (done.get() instanceof Throwable failed) {
                    throw new AssertionError(failed);
                }
                assertEquals(1, pictures.size(), "the layer, drawn once off screen");
                Image picture = pictures.get(0);
                float scale = picture.width() / 80f;
                assertTrue(scale > 2, "at the page's resolution, past the screen's: " + scale);
                assertEquals(Math.round(60 * scale), picture.height(), 1);
                byte[] rgba = picture.pixels();
                int w = picture.width();
                int centre = ((picture.height() / 2) * w + w / 2) * 4;
                int corner = (5 * w + 5) * 4;
                assertEquals(0, rgba[centre + 3] & 0xff, "the eraser's hole is clear");
                assertEquals(255, rgba[corner + 3] & 0xff, "the rest is opaque");
                assertEquals(0xCC, rgba[corner] & 0xff, "and red");
                String pdf = bytes.toString(StandardCharsets.ISO_8859_1);
                assertTrue(pdf.contains("/Subtype/Image"), "the file holds it as an image");
            } finally {
                OffscreenPainter.uninstall(painter);
                TextRulers.uninstall(ruler);
            }
        }
    }
}

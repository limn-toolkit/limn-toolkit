package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.graphics.GpuSurface;
import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link GlCanvas#drawSurface} does with a video surface: which shape kind
 * reads its texels, which way up the picture lands, and what happens to a
 * surface the backend did not create.
 */
class GlVideoCompositeTest extends GlVideoTestBase {

    @Test
    void aVideoSurfaceCompositesAsAnOrdinaryPicture() {
        // Drawn 1:1 over the whole target, the composite must hand back exactly
        // the converted picture. It is also what separates the two surface
        // kinds: the HDR branch would tonemap and re-encode these texels, and
        // nothing here would still match.
        int width = 12;
        int height = 8;
        int[] luma = TestPictures.pseudoRandom(width * height, 5);
        int[] cb = TestPictures.pseudoRandom(width * height / 4, 6);
        int[] cr = TestPictures.pseudoRandom(width * height / 4, 7);
        VideoFrame frame = TestPictures.frame(width, height, PixelFormat.I420,
                VideoColor.BT709_LIMITED, luma, cb, cr, 0, true, false);
        GlVideoSurface surface = upload(frame);
        int[] converted = picture(surface);
        frame.release();

        byte[] composited = renderToPicture(width, height, target -> {
            target.clear(Color.BLACK);
            target.drawSurface(surface, 0, 0, width, height);
        });
        surface.dispose();

        for (int index = 0; index < converted.length; index++) {
            assertEquals(converted[index], composited[index] & 0xFF,
                    "byte " + index + ": the composite must not alter the picture; a display"
                            + " transform here would mean the surface took the HDR shape kind");
        }
    }

    @Test
    void theTopRowOfThePictureIsDrawnAtTheTopOfTheRectangle() {
        // Two flips cancel between the conversion and the composite. If either
        // one is dropped the picture is upside down, and no colour assertion
        // anywhere would notice.
        int size = 4;
        int[] luma = new int[size * size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                luma[row * size + column] = row < size / 2 ? 30 : 220;
            }
        }
        VideoFrame frame = TestPictures.frame(size, size, PixelFormat.I444, VideoColor.BT709_FULL,
                luma, TestPictures.filled(size * size, 128), TestPictures.filled(size * size, 128),
                0, true, false);
        GlVideoSurface surface = upload(frame);
        frame.release();

        byte[] composited = renderToPicture(size, size, target -> {
            target.clear(Color.BLACK);
            target.drawSurface(surface, 0, 0, size, size);
        });
        surface.dispose();

        assertEquals(30, composited[0] & 0xFF, "the picture's first row is drawn at the top");
        assertEquals(220, composited[(size * size - 1) * 4] & 0xFF,
                "and its last row at the bottom");
    }

    @Test
    void aResolutionChangeBetweenTwoDrawsKeepsBothPictures() {
        // The batch draws a quad long after it is queued, binding the texture it
        // is tracking at that moment. A picture replaced by a differently sized
        // one in the same frame frees the texture the first quad still refers to,
        // and its id comes straight back as one of the new plane textures, so
        // the earlier quad would show a luma plane, or nothing, with no error.
        GlVideoSurface surface = canvas.glVideo().createSurface();
        VideoFrame first = TestPictures.uniform(4, 4, PixelFormat.I444,
                VideoColor.BT709_FULL, 60, 128, 128);
        surface.upload(first);
        first.release();

        byte[] composited = renderToPicture(8, 4, target -> {
            target.clear(Color.BLACK);
            target.drawSurface(surface, 0, 0, 4, 4);
            VideoFrame second = TestPictures.uniform(6, 6, PixelFormat.I444,
                    VideoColor.BT709_FULL, 200, 128, 128);
            surface.upload(second);
            second.release();
            target.drawSurface(surface, 4, 0, 4, 4);
        });
        surface.dispose();

        assertEquals(60, composited[0] & 0xFF,
                "the first draw must show the picture it was queued with");
        assertEquals(200, composited[4 * 4] & 0xFF,
                "and the second the picture uploaded after it");
        assertNoGlError("a resolution change between two draws of one surface");
    }

    @Test
    void aSurfaceWithNoPictureDrawsNothing() {
        GlVideoSurface empty = canvas.glVideo().createSurface();
        byte[] composited = renderToPicture(4, 4, target -> {
            target.clear(Color.rgb(0xFF0000));
            target.drawSurface(empty, 0, 0, 4, 4);
        });
        empty.dispose();

        for (int pixel = 0; pixel < 16; pixel++) {
            assertEquals(255, composited[pixel * 4] & 0xFF, "pixel " + pixel + " red");
            assertEquals(0, composited[pixel * 4 + 1] & 0xFF, "pixel " + pixel + " green");
        }
    }

    @Test
    void aForeignSurfaceDrawsNothingAndSaysSo() {
        // The trap this defends: drawSurface dispatches on the concrete backend
        // type, so a type it does not recognise has no texture to sample. It
        // must say so: a silent fall-through leaves a blank rectangle with no
        // error, no log and nothing to search for.
        GpuSurface foreign = new GpuSurface() {
            @Override public int widthPx() {
                return 4;
            }

            @Override public int heightPx() {
                return 4;
            }

            @Override public void resize(int widthPx, int heightPx) {
            }

            @Override public void dispose() {
            }
        };

        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(GlCanvas.class.getName());
        Handler collector = new Handler() {
            @Override public void publish(LogRecord record) {
                records.add(record);
            }

            @Override public void flush() {
            }

            @Override public void close() {
            }
        };
        Level previousLevel = logger.getLevel();
        logger.addHandler(collector);
        logger.setLevel(Level.ALL);
        byte[] composited;
        try {
            composited = renderToPicture(4, 4, target -> {
                target.clear(Color.rgb(0x00FF00));
                target.drawSurface(foreign, 0, 0, 4, 4);
            });
        } finally {
            logger.removeHandler(collector);
            logger.setLevel(previousLevel);
        }

        for (int pixel = 0; pixel < 16; pixel++) {
            assertEquals(255, composited[pixel * 4 + 1] & 0xFF,
                    "an unrecognised surface must not draw anything at all");
        }
        assertFalse(records.isEmpty(), "…but it must say so: nothing was logged");
        LogRecord warning = records.get(0);
        assertEquals(Level.WARNING, warning.getLevel());
        assertTrue(String.valueOf(warning.getMessage()).contains("drawSurface"),
                "the message must name the call: " + warning.getMessage());
        assertTrue(String.valueOf(warning.getParameters()[0]).contains(getClass().getName()),
                "and the type that was not recognised: "
                        + java.util.Arrays.toString(warning.getParameters()));
    }
}

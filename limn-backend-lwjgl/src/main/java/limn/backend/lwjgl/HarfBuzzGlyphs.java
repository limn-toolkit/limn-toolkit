package limn.backend.lwjgl;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.harfbuzz.hb_raster_extents_t;

import java.nio.ByteBuffer;

import static org.lwjgl.util.harfbuzz.HarfBuzzRaster.hb_raster_draw_create_or_fail;
import static org.lwjgl.util.harfbuzz.HarfBuzzRaster.hb_raster_draw_glyph;
import static org.lwjgl.util.harfbuzz.HarfBuzzRaster.hb_raster_draw_recycle_image;
import static org.lwjgl.util.harfbuzz.HarfBuzzRaster.hb_raster_draw_render;
import static org.lwjgl.util.harfbuzz.HarfBuzzRaster.hb_raster_draw_reset;
import static org.lwjgl.util.harfbuzz.HarfBuzzRaster.hb_raster_draw_set_transform;
import static org.lwjgl.util.harfbuzz.HarfBuzzRaster.hb_raster_image_get_buffer;
import static org.lwjgl.util.harfbuzz.HarfBuzzRaster.hb_raster_image_get_extents;

/**
 * Glyph coverage drawn by HarfBuzz's own rasterizer, for the one kind of face stb_truetype cannot
 * draw: an instance of a variable face other than its default ({@link StbFont#instance}).
 *
 * <p>The bitmap it gives is the one stb gives for the same glyph of a static face: the same box,
 * the same coverage (measured on Roboto's H at 32 pixels: 19 by 23 and the same sum of coverage
 * from both), only stored bottom row first, with its origin at the box's lower left in y-up
 * units. {@link #rasterize} turns it into stb's layout, top row first with the top bearing in
 * y-down pixels, so the atlas takes either alike.
 *
 * <p>One draw object for the process, made on first need; UI thread only, as every face is.
 */
final class HarfBuzzGlyphs {

    private static final System.Logger LOG = System.getLogger(HarfBuzzGlyphs.class.getName());

    /** The largest glyph that is drawn, as {@link StbFont} bounds its own. */
    private static final long MAX_GLYPH_PIXELS = 1L << 24;

    private static long draw;
    private static boolean tried;

    private HarfBuzzGlyphs() {
    }

    /** Whether HarfBuzz's rasterizer loaded; probed once, logged once. */
    static boolean ready() {
        if (!tried) {
            tried = true;
            try {
                draw = hb_raster_draw_create_or_fail();
            } catch (Throwable failure) {
                // A separate native from the shaper's; a build can carry one and not the other.
                draw = 0;
                LOG.log(System.Logger.Level.WARNING, "HarfBuzz's rasterizer is unavailable; a "
                        + "variable face is drawn at its default instance: " + failure, failure);
            }
        }
        return draw != 0;
    }

    /**
     * Draws one glyph of {@code font} (scaled to font units, as every shaper font is) at
     * {@code sizePx}, or answers {@code null} when the rasterizer is not there. A glyph with no ink
     * gives a {@code null} bitmap with its advance, as {@link StbFont#rasterizeGlyph} does.
     */
    static StbFont.RasterizedGlyph rasterize(long font, int unitsPerEm, int glyph, float sizePx,
                                             float advancePx) {
        if (!ready() || unitsPerEm <= 0) {
            return null;
        }
        float scale = sizePx / unitsPerEm;
        hb_raster_draw_reset(draw);
        hb_raster_draw_set_transform(draw, scale, 0, 0, scale, 0, 0);
        hb_raster_draw_glyph(draw, font, glyph, 0, 0);
        long image = hb_raster_draw_render(draw);
        if (image == 0) {
            return new StbFont.RasterizedGlyph(null, 0, 0, 0, 0, advancePx);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            hb_raster_extents_t extents = hb_raster_extents_t.malloc(stack);
            hb_raster_image_get_extents(image, extents);
            int width = extents.width();
            int height = extents.height();
            int stride = extents.stride();
            if (width <= 0 || height <= 0 || stride < width || (long) width * height > MAX_GLYPH_PIXELS) {
                return new StbFont.RasterizedGlyph(null, 0, 0, 0, 0, advancePx);
            }
            ByteBuffer source = hb_raster_image_get_buffer(image, (long) stride * height);
            if (source == null) {
                return new StbFont.RasterizedGlyph(null, 0, 0, 0, 0, advancePx);
            }
            ByteBuffer bitmap = MemoryUtil.memAlloc(width * height);
            for (int row = 0; row < height; row++) {
                int from = (height - 1 - row) * stride; // bottom row first, into top row first
                for (int x = 0; x < width; x++) {
                    bitmap.put(row * width + x, source.get(from + x));
                }
            }
            return new StbFont.RasterizedGlyph(bitmap, width, height, extents.x_origin(),
                    -(extents.y_origin() + height), advancePx);
        } finally {
            hb_raster_draw_recycle_image(draw, image);
        }
    }
}

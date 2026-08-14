package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.SvgIcon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@link SvgIcon} rasterizes crisply at the exact device size requested (a
 * distinct bitmap per size, cached by identity); this is what keeps vector icons
 * sharp on HiDPI instead of up/down-sampling one fixed bitmap.
 */
class SvgIconSizeTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
            + "viewBox=\"0 0 10 10\"><rect x=\"1\" y=\"1\" width=\"8\" height=\"8\" fill=\"#000\"/></svg>";

    private NanoSvgRasterizer rasterizer;

    @BeforeEach
    void install() {
        rasterizer = new NanoSvgRasterizer();
        SvgIcon.installRasterizer(rasterizer);
    }

    @AfterEach
    void uninstall() {
        SvgIcon.uninstallRasterizer(rasterizer);
    }

    @Test
    void rasterizesADistinctCachedBitmapPerDeviceSize() {
        SvgIcon icon = SvgIcon.of(SVG);
        assertTrue(icon.tintable(), "SVG icons are single-color masks, recolored by tint");

        Image small = icon.image(24, false);
        Image large = icon.image(48, false);
        assertEquals(24, Math.max(small.width(), small.height()), "fit into the 24px box");
        assertEquals(48, Math.max(large.width(), large.height()), "fit into the 48px box");

        // A real per-size raster, not one bitmap resampled; and cached by size.
        assertNotSame(small, large);
        assertSame(small, icon.image(24, false));
        // Theme brightness is irrelevant for SVG (the tint recolors it), so it does
        // not fork the cache.
        assertSame(small, icon.image(24, true));
    }
}

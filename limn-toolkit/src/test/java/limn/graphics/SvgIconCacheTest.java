package limn.graphics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link SvgIcon}'s per-size raster cache must be bounded (LRU): an animated
 * size (hover-grow, zoom) previously pinned one bitmap + GPU texture per pixel
 * size touched, for the life of the icon.
 */
class SvgIconCacheTest {

    private final List<Integer> rasterized = new ArrayList<>();
    private final SvgRasterizer fake = (svg, px) -> {
        rasterized.add(px);
        return new Image(1, 1, new byte[4]);
    };

    @AfterEach
    void uninstall() {
        SvgIcon.uninstallRasterizer(fake);
    }

    @Test
    void repeatedSizeRasterizesOnceAndKeepsIdentity() {
        SvgIcon.installRasterizer(fake);
        SvgIcon icon = SvgIcon.of("<svg/>");
        Image first = icon.image(32);
        assertSame(first, icon.image(32), "stable identity: it is the GPU texture key");
        assertEquals(1, rasterized.size());
    }

    @Test
    void sizesBeyondTheCapEvictTheLeastRecentlyUsed() {
        SvgIcon.installRasterizer(fake);
        SvgIcon icon = SvgIcon.of("<svg/>");
        // Touch many distinct sizes (an animated zoom), then come back to the
        // first and the last.
        for (int px = 1; px <= 40; px++) {
            icon.image(px);
        }
        assertEquals(40, rasterized.size());

        rasterized.clear();
        icon.image(40); // most recent: still cached
        assertEquals(0, rasterized.size(), "the newest size survives the sweep");
        icon.image(1); // oldest: evicted, re-rasterized on demand
        assertEquals(List.of(1), rasterized, "the oldest size was evicted");
    }
}

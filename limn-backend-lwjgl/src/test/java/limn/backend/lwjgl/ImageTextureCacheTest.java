package limn.backend.lwjgl;

import limn.backend.RenderStats;
import limn.graphics.Image;
import limn.graphics.Images;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The image texture cache against a real context: what it reports holding, and that it reads the
 * application's budget. The eviction rules themselves are {@link TextureResidencyTest}'s, without
 * a context; this is the wiring, which only a context can show: the byte count of a texture with
 * its mipmaps, and the budget {@link Images#setTextureBudget} sets reaching a frame boundary.
 */
class ImageTextureCacheTest {

    /** An 8 × 4 picture: 128 bytes at full size, and 44 more for its 4 × 2, 2 × 1 and 1 × 1. */
    private static final long EIGHT_BY_FOUR = 8 * 4 * 4 + 4 * 2 * 4 + 2 * 4 + 4;

    private ImageTextureCache cache;

    @BeforeEach
    void openCache() {
        HeadlessGl.assumeAvailable();
        cache = new ImageTextureCache();
    }

    @AfterEach
    void closeCache() {
        Images.setTextureBudget(Images.DEFAULT_TEXTURE_BUDGET);
        if (cache != null) {
            cache.close();
            cache = null;
        }
    }

    /**
     * The mipmaps are a third of a texture again and were left out of the count, so a performance
     * monitor read 128 bytes for a texture the device held 172 of, and 8 MB for a photograph it
     * held 11 of.
     */
    @Test
    void aTextureIsCountedWithItsMipmaps() {
        cache.beginFrameAndEvict();
        cache.textureFor(picture(8, 4));
        assertEquals(new RenderStats(1, EIGHT_BY_FOUR), cache.stats());
        cache.textureFor(picture(1, 1));
        assertEquals(new RenderStats(2, EIGHT_BY_FOUR + 4), cache.stats());
    }

    /**
     * Over the budget, a frame boundary drops what the frame that just ended did not draw, and
     * deletes its texture; it keeps what it did draw even over the budget, and a dropped image
     * drawn again is uploaded again. Before the budget the cache evicted by age alone, and the
     * second picture here stayed for 300 frames whatever the budget said.
     */
    @Test
    void theApplicationsBudgetReachesTheFrameBoundary() {
        Images.setTextureBudget(EIGHT_BY_FOUR);
        Image kept = picture(8, 4);
        Image dropped = picture(8, 4);

        cache.beginFrameAndEvict();
        cache.textureFor(kept);
        int droppedTexture = cache.textureFor(dropped);
        assertFalse(cache.beginFrameAndEvict(), "both were drawn by the frame that just ended");
        assertEquals(new RenderStats(2, 2 * EIGHT_BY_FOUR), cache.stats(), "over the budget, kept");

        cache.textureFor(kept);
        assertTrue(cache.beginFrameAndEvict(), "the one this frame did not draw goes");
        assertEquals(new RenderStats(1, EIGHT_BY_FOUR), cache.stats());
        assertFalse(GL33C.glIsTexture(droppedTexture), "and its texture with it");

        int again = cache.textureFor(dropped);
        assertTrue(GL33C.glIsTexture(again), "drawn again, it is uploaded again");
        assertEquals(new RenderStats(2, 2 * EIGHT_BY_FOUR), cache.stats());
    }

    private static Image picture(int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        java.util.Arrays.fill(rgba, (byte) 0x7F);
        return new Image(width, height, rgba);
    }
}

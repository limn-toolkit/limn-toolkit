package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BitmapIcon} selects the right variant for the target size and theme, and
 * reports whether it is a tintable mask or a finished picture.
 */
class BitmapIconTest {

    private static Image sized(int w, int h) {
        return new Image(w, h, new byte[w * h * 4]);
    }

    @Test
    void maskIsTintableAndPicksTheSmallestCoveringResolution() {
        Image small = sized(16, 16);
        Image medium = sized(32, 32);
        Image large = sized(64, 64);
        BitmapIcon icon = BitmapIcon.mask(large, small, medium); // any order

        assertTrue(icon.tintable());
        assertSame(small, icon.image(16, false));   // exact cover
        assertSame(medium, icon.image(20, false));  // smallest that still covers 20
        assertSame(medium, icon.image(32, false));
        assertSame(large, icon.image(33, false));
        assertSame(large, icon.image(500, false));  // bigger than all → largest (upscale)
    }

    @Test
    void themedPicksByBrightnessAndIsNotTintable() {
        Image light = sized(10, 10);
        Image dark = sized(10, 10);
        BitmapIcon icon = BitmapIcon.themed(light, dark);

        assertFalse(icon.tintable());
        assertSame(light, icon.image(24, false));
        assertSame(dark, icon.image(24, true));
    }

    @Test
    void pictureIsASingleUntintedImage() {
        Image pic = sized(12, 12);
        BitmapIcon icon = BitmapIcon.picture(pic);

        assertFalse(icon.tintable());
        assertSame(pic, icon.image(8, true));
        assertSame(pic, icon.image(99, false));
    }
}

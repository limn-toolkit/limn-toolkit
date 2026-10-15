package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.Images;
import limn.graphics.SvgIcon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An SVG with SMIL or CSS animation is an animated icon, played a moment a frame by the toolkit
 * and drawn by lunasvg (ADR 066 section 5): 24 frames a second unless its icon or
 * {@code limn.svg.frameRate} says, each frame the drawing of its moment.
 */
class AnimatedSvgTest {

    private final LunaSvgRenderer renderer = new LunaSvgRenderer();

    /** A dot that moves from left to right in one second, without end. */
    private static final String MOVING = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 20 20'>"
            + "<circle cx='2' cy='10' r='2'><animate attributeName='cx' from='2' to='18' dur='1s' repeatCount='indefinite'/>"
            + "</circle></svg>";

    @BeforeEach
    void install() {
        SvgIcon.installRenderer(renderer);
    }

    @AfterEach
    void uninstall() {
        SvgIcon.uninstallRenderer(renderer);
        System.clearProperty(SvgIcon.FRAME_RATE_PROPERTY);
    }

    private static int alphaAt(Image image, int x, int y) {
        return image.pixels()[(y * image.width() + x) * 4 + 3] & 0xFF;
    }

    @Test
    void anAnimatedSvgPlaysAt24FramesASecondUnlessSet() {
        SvgIcon icon = SvgIcon.of(MOVING);
        assertTrue(icon.isAnimated());
        assertEquals(24, icon.frameRate());
        assertEquals(24, icon.frameCount(), "one second at 24");
        assertEquals(0, icon.loopCount(), "without end");
        SvgIcon sixty = icon.withFrameRate(60);
        assertEquals(60, sixty.frameCount());
        assertEquals(17, sixty.frameMillis(0), "a 60th of a second, rounded");
        assertThrows(IllegalArgumentException.class, () -> icon.withFrameRate(0));
        assertThrows(IllegalArgumentException.class, () -> icon.withFrameRate(121));
    }

    @Test
    void theSystemPropertySetsTheRateOfEverySvgThatSaysNone() {
        System.setProperty(SvgIcon.FRAME_RATE_PROPERTY, "10");
        assertEquals(10, SvgIcon.of(MOVING).frameCount());
        assertEquals(30, SvgIcon.of(MOVING).withFrameRate(30).frameCount(), "the icon's own wins");
    }

    @Test
    void eachFrameIsTheDrawingOfItsMoment() {
        SvgIcon icon = SvgIcon.of(MOVING).withFrameRate(4);
        Image start = icon.image(20, false, 0);
        Image half = icon.image(20, false, 2);
        assertEquals(255, alphaAt(start, 2, 10), "at 0 s the dot is at the left");
        assertEquals(0, alphaAt(start, 10, 10));
        assertEquals(255, alphaAt(half, 10, 10), "at 0.5 s in the middle");
        assertEquals(0, alphaAt(half, 2, 10));
        assertFalse(Arrays.equals(start.pixels(), half.pixels()));
        assertEquals(start, icon.image(20, false), "frame 0 is the icon's picture");
    }

    @Test
    void aStillSvgIsNotAnimated() {
        SvgIcon icon = SvgIcon.of("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'/>");
        assertFalse(icon.isAnimated());
        assertEquals(1, icon.frameCount());
    }

    @Test
    void anAnimatedSvgDecodesToAnAnimatedImage() {
        Image image = Images.decode(MOVING.replace("viewBox='0 0 20 20'", "width='20' height='20'")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(image.isAnimated());
        assertEquals(24, image.frameCount());
        assertEquals(255, alphaAt(image.frame(12), 10, 10), "half a second in, the dot is in the middle");
    }
}

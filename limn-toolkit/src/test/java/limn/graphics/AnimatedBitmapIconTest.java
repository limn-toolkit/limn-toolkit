package limn.graphics;

import limn.graphics.internal.IconTimeline;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An animated image makes an animated icon (ADR 065 section 10): it says its frames and times as
 * the image does, answers each frame at every size it has, and is refused when its images would
 * play differently. And the timeline every place plays an icon by.
 */
class AnimatedBitmapIconTest {

    private static Image animation(int side, int[] millis, int turns) {
        List<Image> frames = new ArrayList<>();
        for (int f = 0; f < millis.length; f++) {
            frames.add(new Image(side, side, new byte[side * side * 4]));
        }
        return Images.animation(frames, millis, turns);
    }

    @Test
    void anIconOfAnAnimatedImageIsAnimatedAsTheImageIs() {
        Image image = animation(8, new int[] {40, 80, 120}, 2);
        BitmapIcon icon = BitmapIcon.picture(image);
        assertTrue(icon.isAnimated());
        assertEquals(3, icon.frameCount());
        assertEquals(80, icon.frameMillis(1));
        assertEquals(2, icon.loopCount());
        assertSame(image.frame(2), icon.image(8, false, 2));
        assertSame(image, icon.image(8, false), "its first frame, the image itself");
        assertThrows(IndexOutOfBoundsException.class, () -> icon.image(8, false, 3));
    }

    @Test
    void aStillIconAnswersAsItAlwaysHas() {
        BitmapIcon icon = BitmapIcon.picture(new Image(4, 4, new byte[64]));
        assertFalse(icon.isAnimated());
        assertEquals(1, icon.frameCount());
        assertEquals(0, icon.frameMillis(0));
        assertSame(icon.image(4, false), icon.image(4, false, 0));
    }

    @Test
    void aMaskPicksItsResolutionAndThenTheFrame() {
        Image small = animation(8, new int[] {100, 100}, 0);
        Image large = animation(16, new int[] {100, 100}, 0);
        BitmapIcon icon = BitmapIcon.mask(small, large);
        assertSame(large.frame(1), icon.image(16, false, 1));
        assertSame(small.frame(1), icon.image(8, false, 1));
    }

    @Test
    void imagesThatWouldPlayDifferentlyAreRefused() {
        Image four = animation(8, new int[] {100, 100, 100, 100}, 0);
        assertThrows(IllegalArgumentException.class,
                () -> BitmapIcon.themed(four, animation(8, new int[] {100, 100, 100}, 0)), "frames");
        assertThrows(IllegalArgumentException.class,
                () -> BitmapIcon.themed(four, animation(8, new int[] {100, 100, 100, 90}, 0)), "times");
        assertThrows(IllegalArgumentException.class,
                () -> BitmapIcon.themed(four, animation(8, new int[] {100, 100, 100, 100}, 3)), "turns");
        assertThrows(IllegalArgumentException.class,
                () -> BitmapIcon.mask(four, new Image(16, 16, new byte[1024])), "still beside animated");
        BitmapIcon.themed(four, animation(8, new int[] {100, 100, 100, 100}, 0));
    }

    @Test
    void theTimelinePlaysTheFramesAtTheirTimesAndRestsOnTheLast() {
        IconTimeline time = new IconTimeline(BitmapIcon.picture(animation(4, new int[] {100, 50, 200}, 2)));
        assertEquals(0, time.frame());
        assertEquals(0.1, time.secondsUntilNextFrame(), 1e-9);
        assertFalse(time.advance(0.099));
        assertTrue(time.advance(0.002));
        assertEquals(1, time.frame());
        time.advance(0.05);
        assertEquals(2, time.frame());
        time.advance(0.2);
        assertEquals(0, time.frame(), "the second turn");
        time.advance(10);
        assertEquals(2, time.frame(), "two turns, ending on the last frame");
        assertFalse(time.isPlaying());
        assertTrue(Double.isInfinite(time.secondsUntilNextFrame()));
    }

    @Test
    void aTimelineWithoutEndCrossesHoursInOneStep() {
        IconTimeline time = new IconTimeline(BitmapIcon.picture(animation(4, new int[] {100, 100}, 0)));
        time.advance(3600.15);
        assertEquals(1, time.frame());
        assertTrue(time.isPlaying());
    }

    @Test
    void aStillIconsTimelineHasFinished() {
        IconTimeline time = new IconTimeline(BitmapIcon.picture(new Image(2, 2, new byte[16])));
        assertFalse(time.isPlaying());
        assertFalse(time.advance(5));
        assertEquals(0, time.frame());
    }
}

package limn.animation;

import limn.graphics.Image;
import limn.graphics.Images;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which frame is shown when: the clockwork an image view and an application's own widget share. */
class ImageAnimationTest {

    /** Four frames of 100 ms each, asking for {@code turns} turns. */
    private static Image four(int turns) {
        List<Image> frames = new ArrayList<>();
        for (int f = 0; f < 4; f++) {
            frames.add(new Image(1, 1, new byte[] {(byte) f, 0, 0, (byte) 0xFF}));
        }
        return Images.animation(frames, new int[] {100, 100, 100, 100}, turns);
    }

    @Test
    void timeMovesItFrameByFrameAndRoundAgain() {
        Image image = four(0);
        ImageAnimation animation = new ImageAnimation(image);
        assertSame(image.frame(0), animation.frame());
        assertFalse(animation.advance(0.099), "not yet");
        assertTrue(animation.advance(0.002));
        assertEquals(1, animation.frameIndex());
        assertEquals(0.099, animation.secondsUntilNextFrame(), 1e-9);
        assertTrue(animation.advance(0.25), "one step may cross several frames");
        assertEquals(3, animation.frameIndex());
        animation.advance(0.1);
        assertEquals(0, animation.frameIndex(), "and round again, for an image that asks for no end");
        animation.advance(3600 * 5 + 0.2);
        assertEquals(2, animation.frameIndex(), "hours later it is where the time puts it");
        assertTrue(animation.isPlaying());
    }

    @Test
    void theTurnsAreTheImagesUnlessSetAndTheLastFrameIsWhereItEnds() {
        ImageAnimation twice = new ImageAnimation(four(2));
        twice.advance(0.35);
        assertEquals(3, twice.frameIndex());
        assertFalse(twice.isFinished());
        twice.advance(0.1);
        assertEquals(0, twice.frameIndex(), "the second turn");
        twice.advance(10);
        assertTrue(twice.isFinished());
        assertEquals(3, twice.frameIndex(), "ended on the last frame");
        assertFalse(twice.advance(1), "and time no longer moves it");
        assertEquals(Double.POSITIVE_INFINITY, twice.secondsUntilNextFrame());

        twice.setPlaying(true);
        assertEquals(0, twice.frameIndex(), "played again, it starts again");
        assertFalse(twice.isFinished());

        ImageAnimation once = new ImageAnimation(four(0)).setLoop(ImageAnimation.Loop.ONCE);
        once.advance(10);
        assertTrue(once.isFinished());
        once.setLoop(ImageAnimation.Loop.FOREVER);
        assertFalse(once.isFinished(), "given more turns, it goes on");
        ImageAnimation thrice = new ImageAnimation(four(0)).setLoop(ImageAnimation.Loop.times(3));
        thrice.advance(1.15);
        assertFalse(thrice.isFinished());
        thrice.advance(0.1);
        assertTrue(thrice.isFinished());
    }

    @Test
    void bouncingGoesForwardThenBackWithoutShowingAnEndTwice() {
        ImageAnimation bounce = new ImageAnimation(four(1)).setLoop(ImageAnimation.Loop.BOUNCE);
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            seen.add(bounce.frameIndex());
            bounce.advance(0.1);
        }
        assertEquals(List.of(0, 1, 2, 3, 2, 1, 0, 1, 2), seen);
        assertFalse(bounce.isFinished());
    }

    @Test
    void speedHoldingAndSeeking() {
        ImageAnimation animation = new ImageAnimation(four(0)).setSpeed(2);
        animation.advance(0.1);
        assertEquals(2, animation.frameIndex(), "twice as fast");
        animation.setPlaying(false);
        assertFalse(animation.advance(5), "held");
        assertEquals(2, animation.frameIndex());
        animation.seekFrame(1).setPlaying(true).setSpeed(0.5f);
        animation.advance(0.19);
        assertEquals(1, animation.frameIndex(), "half as fast, from the start of the frame sought");
        animation.advance(0.02);
        assertEquals(2, animation.frameIndex());

        ImageAnimation still = new ImageAnimation(new Image(1, 1, new byte[4]));
        assertTrue(still.isFinished());
        assertFalse(still.advance(1));
        assertEquals(0, still.frameIndex());
    }
}

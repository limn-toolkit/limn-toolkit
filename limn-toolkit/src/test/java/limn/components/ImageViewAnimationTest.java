package limn.components;

import limn.animation.ImageAnimation;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An image view plays an animated image on its scene's clock: by itself, frame by frame, held and
 * sped and looped as asked, and at rest once the image has made its turns.
 */
class ImageViewAnimationTest {

    private final AtomicLong nanos = new AtomicLong();
    private HeadlessUi ui;
    private Scene scene;
    private ImageView view;
    private Image drawn;
    private NoopCanvas canvas;

    /** Three frames of 100 ms each. */
    private static Image three(int turns) {
        List<Image> frames = new ArrayList<>();
        for (int f = 0; f < 3; f++) {
            frames.add(new Image(2, 2, new byte[16]));
        }
        return Images.animation(frames, new int[] {100, 100, 100}, turns);
    }

    @BeforeEach
    void setUp() {
        ui = new HeadlessUi(nanos::get);
        canvas = new NoopCanvas(100, 100) {
            @Override
            public void drawImage(Image image, float x, float y, float w, float h) {
                drawn = image;
            }

            @Override
            public void drawImage(Image image, float x, float y, float w, float h, Color tint) {
                drawn = image;
            }
        };
        view = new ImageView(null);
        scene = new Scene(view, nanos::get);
        scene.setTextRuler(TestRulers.FIXED);
    }

    @AfterEach
    void tearDown() {
        ui.close();
    }

    /**
     * Two frames with the clock still: the paint that shows the image is what starts its clock,
     * and a ticker's first tick is handed no time, so time counts from the frame after.
     */
    private void shown() {
        after(0);
        after(0);
    }

    /** Moves the clock on and renders a frame. */
    private void after(int millis) {
        nanos.addAndGet(millis * 1_000_000L);
        scene.renderFrame(canvas);
    }

    @Test
    void anAnimatedImagePlaysByItselfOnTheScenesClock() {
        Image image = three(0);
        view.setImage(image);
        shown();
        assertSame(image.frame(0), drawn);
        assertTrue(view.isPlaying());
        after(60);
        assertSame(image.frame(0), drawn, "its time has not come");
        after(60);
        assertSame(image.frame(1), drawn);
        assertEquals(1, view.frameIndex());
        after(100);
        after(100);
        assertSame(image.frame(0), drawn, "round again");
    }

    @Test
    void aViewMadeWithAnAnimatedImagePlaysItAsOneGivenItLater() {
        Image image = three(0);
        ImageView made = new ImageView(image);
        scene = new Scene(made, nanos::get);
        scene.setTextRuler(TestRulers.FIXED);
        shown();
        assertTrue(made.isPlaying(), "the constructor is a way of giving an image too");
        after(120);
        assertSame(image.frame(1), drawn);
    }

    @Test
    void itIsHeldSpedAndSoughtAsAsked() {
        Image image = three(0);
        view.setImage(image);
        shown();
        view.setPlaying(false);
        after(500);
        assertSame(image.frame(0), drawn, "held");
        assertFalse(view.isPlaying());

        view.setPlaying(true).setSpeed(2);
        shown();
        after(60);
        assertSame(image.frame(1), drawn, "twice as fast");
        view.seekFrame(2);
        after(0);
        assertSame(image.frame(2), drawn);
    }

    @Test
    void itRestsOnItsLastFrameWhenItsTurnsAreDoneAndSaysSo() {
        Image image = three(1);
        int[] told = {0};
        view.onFinished(() -> told[0]++);
        view.setImage(image);
        shown();
        for (int i = 0; i < 6; i++) {
            after(100);
        }
        assertSame(image.frame(2), drawn);
        assertFalse(view.isPlaying());
        assertEquals(1, told[0], "told once");

        view.setLoop(ImageAnimation.Loop.FOREVER);
        after(0);
        after(100);
        assertTrue(view.isPlaying(), "given more turns, it goes on");
    }

    @Test
    void aStillImageIsDrawnAsItIsAndAViewKeepsWhatItWasToldForTheNextImage() {
        Image still = new Image(2, 2, new byte[16]);
        view.setPlaying(false);
        view.setImage(still);
        after(0);
        assertSame(still, drawn);
        assertFalse(view.isPlaying());
        assertEquals(0, view.frameIndex());

        Image image = three(0);
        view.setImage(image);
        after(0);
        after(500);
        assertSame(image.frame(0), drawn, "held from before it was given");
    }
}

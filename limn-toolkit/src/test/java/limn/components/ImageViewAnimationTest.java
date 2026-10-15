package limn.components;

import limn.animation.ImageAnimation;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.StubWindow;
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
    /** How many times the frame drawn changed. */
    private int seenFrames;
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
                if (drawn != null && drawn != image) {
                    seenFrames++;
                }
                drawn = image;
            }

            @Override
            public void drawImage(Image image, float x, float y, float w, float h, Color tint) {
                drawn = image;
            }
        };
        view = new ImageView((limn.graphics.Image) null);
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

    /** Moves the clock on, runs what fell due, and renders a frame, as a backend's loop does. */
    private void after(int millis) {
        nanos.addAndGet(millis * 1_000_000L);
        ui.runtime().drain();
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

    /**
     * A long animation is decoded as it plays, on a worker: the view shows each frame as it
     * arrives and never one for another, and waits on the frame before for one that is late.
     */
    @Test
    void anAnimationDecodedAsItPlaysShowsEveryFrameInItsTurn() throws Exception {
        List<Image> made = new ArrayList<>();
        int[] millis = new int[12];
        for (int f = 0; f < 12; f++) {
            byte[] rgba = new byte[16 * 16 * 4];
            for (int o = 0; o < rgba.length; o += 4) {
                rgba[o] = (byte) (f * 20);
                rgba[o + 3] = (byte) 0xFF;
            }
            made.add(new Image(16, 16, rgba));
            millis[f] = 100;
        }
        byte[] file = Images.encode(Images.animation(made, millis, 0), limn.graphics.ImageFormat.GIF);
        long budget = Images.animationBudget();
        Images.setAnimationBudget(0);
        Image image;
        try {
            image = Images.decode(file);
        } finally {
            Images.setAnimationBudget(budget);
        }
        view.setImage(image);
        shown();
        List<Integer> seen = new ArrayList<>();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (seen.size() < 30 && System.nanoTime() < deadline) {
            after(100);
            Thread.sleep(5); // the worker's turn
            int which = (drawn.pixels()[0] & 0xFF) / 20;
            if (seen.isEmpty() || seen.get(seen.size() - 1) != which) {
                seen.add(which);
            }
        }
        assertEquals(30, seen.size(), "it never stopped: " + seen);
        for (int i = 1; i < seen.size(); i++) {
            assertEquals((seen.get(i - 1) + 1) % 12, (int) seen.get(i), "every frame in its turn: " + seen);
        }
    }

    /**
     * Between two frames of the picture the scene asks for no frame of the display: a picture of
     * ten frames a second costs ten, whatever the display's rate, and a held one costs none.
     */
    @Test
    void aFrameOfTheDisplayIsAskedForOnlyWhenAFrameOfThePictureIsDue() {
        StubWindow window = new StubWindow();
        scene.bind(window);
        Image image = three(0);
        view.setImage(image);
        shown();
        int before = window.framesRequested;
        for (int t = 0; t < 60; t++) {
            after(16); // a second of a display at 60 frames a second
        }
        int asked = window.framesRequested - before;
        assertTrue(asked >= 9 && asked <= 11, "ten frames of the picture, " + asked + " asked for");
        assertEquals(9, seenFrames, "and each drawn in its turn");

        view.setPlaying(false);
        after(16);
        before = window.framesRequested;
        for (int t = 0; t < 60; t++) {
            after(16);
        }
        assertEquals(before, window.framesRequested, "held, it asks for none");
    }

    /** A sleeping clock is told of a new speed at once, and owes the old speed the time before it. */
    @Test
    void aNewSpeedReachesAClockAsleepAndTheTimeBeforeItKeepsTheOldOne() {
        Image image = three(0);
        view.setImage(image);
        shown();
        after(50);
        view.setSpeed(2);
        after(0);
        after(0);
        after(25);
        assertSame(image.frame(1), drawn, "50 ms at 1, then 25 at 2: 100 ms of the picture");
        assertEquals(1, view.frameIndex());
    }

    /** A paused scene holds the picture and asks for nothing; resumed, the picture goes on. */
    @Test
    void aPausedSceneHoldsThePictureAndAsksForNoFrame() {
        StubWindow window = new StubWindow();
        scene.bind(window);
        Image image = three(0);
        view.setImage(image);
        shown();
        scene.setPaused(true);
        after(150);
        after(0);
        int before = window.framesRequested;
        for (int t = 0; t < 30; t++) {
            after(100);
        }
        assertEquals(before, window.framesRequested, "paused, it asks for none");
        assertSame(image.frame(0), drawn, "and the picture is held");
        scene.setPaused(false);
        after(0);
        after(60);
        after(60);
        assertSame(image.frame(1), drawn, "resumed, it goes on");
    }

    /** A new image is played from its paint, not from the wake set for the one before. */
    @Test
    void aNewImageIsNotKeptWaitingByTheWakeOfTheOneBefore() {
        Image slow = Images.animation(List.of(new Image(2, 2, new byte[16]), new Image(2, 2, new byte[16])),
                new int[] {10_000, 10_000}, 0);
        view.setImage(slow);
        shown();
        Image image = three(0);
        view.setImage(image);
        shown();
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

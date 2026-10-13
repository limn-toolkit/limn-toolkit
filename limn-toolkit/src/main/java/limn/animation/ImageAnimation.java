package limn.animation;

import limn.graphics.Image;

import java.util.Objects;

/**
 * Plays an animated {@link Image}: which frame is shown now, given the time that has passed.
 *
 * <p>It is the clockwork and nothing else: it draws nothing and asks for no frame. Whoever owns
 * it hands it the time ({@link #advance}) and draws {@link #frame()}; an {@code ImageView} does
 * that for a picture, and a widget of an application's own does it from a scene's ticker:
 *
 * <pre>{@code
 * ImageAnimation walking = new ImageAnimation(Images.fromSheet(sheet, 8, 1, 90));
 * scene.addTicker(seconds -> {
 *     if (walking.advance(seconds)) {
 *         invalidate();
 *     }
 *     return scene() != null;
 * });
 * // in onPaint: canvas.drawImage(walking.frame(), x, y, width, height);
 * }</pre>
 *
 * <p>A still image is an animation of one frame that is always finished. Not thread-safe: the
 * UI thread's, like the widget that owns it.
 */
public final class ImageAnimation {

    /** How many turns an animation makes, and which way. */
    public static final class Loop {

        /** As many turns as the image asks for: what a file says of itself. */
        public static final Loop FILE = new Loop(-1, false);
        /** Without end. */
        public static final Loop FOREVER = new Loop(0, false);
        /** One turn, ending on the last frame. */
        public static final Loop ONCE = new Loop(1, false);
        /** Forward and then back, without end. */
        public static final Loop BOUNCE = new Loop(0, true);

        private final int turns;
        private final boolean bounce;

        private Loop(int turns, boolean bounce) {
            this.turns = turns;
            this.bounce = bounce;
        }

        /**
         * @param turns how many turns, above 0
         * @return that many turns, ending on the last frame
         * @throws IllegalArgumentException if {@code turns} is not above 0
         */
        public static Loop times(int turns) {
            if (turns <= 0) {
                throw new IllegalArgumentException("a number of turns is above 0: " + turns);
            }
            return turns == 1 ? ONCE : new Loop(turns, false);
        }
    }

    private final Image image;
    private Loop loop = Loop.FILE;
    private float speed = 1;
    private boolean playing = true;
    private boolean finished;
    private int index;
    /** How long the frame shown has been shown, in milliseconds of the animation's own time. */
    private double shown;
    private int turnsDone;
    private boolean backward;

    /** @param image the image to play; a still one is played as one frame */
    public ImageAnimation(Image image) {
        this.image = Objects.requireNonNull(image, "image");
        this.finished = !image.isAnimated();
    }

    /** @return the image being played */
    public Image image() {
        return image;
    }

    /** @return the frame to draw now */
    public Image frame() {
        return image.frame(index);
    }

    /** @return which frame is shown, from 0 */
    public int frameIndex() {
        return index;
    }

    /** @return whether time moves it: not held by {@link #setPlaying}, and not at its end */
    public boolean isPlaying() {
        return playing && !finished;
    }

    /** @return whether its last turn has ended; never, for one that turns without end */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Plays, or holds the frame shown. Playing an animation that has finished starts it again
     * from its first frame.
     */
    public ImageAnimation setPlaying(boolean play) {
        if (play && finished && image.isAnimated()) {
            restart();
        }
        this.playing = play;
        return this;
    }

    /**
     * @param newSpeed how fast: 1 is the image's own times, 2 twice as fast, 0.5 half
     * @throws IllegalArgumentException if {@code newSpeed} is not above 0
     */
    public ImageAnimation setSpeed(float newSpeed) {
        if (!(newSpeed > 0) || Float.isInfinite(newSpeed)) {
            throw new IllegalArgumentException("a speed is above 0: " + newSpeed);
        }
        this.speed = newSpeed;
        return this;
    }

    /** @return the speed, 1 unless set */
    public float speed() {
        return speed;
    }

    /** Sets how many turns it makes; {@link Loop#FILE} unless set. An animation that had finished can play again. */
    public ImageAnimation setLoop(Loop newLoop) {
        this.loop = Objects.requireNonNull(newLoop, "newLoop");
        if (finished && image.isAnimated() && turnsLeft()) {
            finished = false;
        }
        return this;
    }

    /** @return how many turns it makes */
    public Loop loop() {
        return loop;
    }

    /** Goes back to the first frame of the first turn. */
    public ImageAnimation restart() {
        index = 0;
        shown = 0;
        turnsDone = 0;
        backward = false;
        finished = !image.isAnimated();
        return this;
    }

    /**
     * Shows a frame, from the start of its time. The turns already made are kept.
     *
     * @throws IndexOutOfBoundsException if there is no such frame
     */
    public ImageAnimation seekFrame(int frame) {
        Objects.checkIndex(frame, image.frameCount());
        index = frame;
        shown = 0;
        return this;
    }

    /**
     * Moves time on.
     *
     * @param seconds how much time has passed, as a scene's ticker hands it
     * @return whether the frame to draw changed
     */
    public boolean advance(double seconds) {
        if (!isPlaying() || !(seconds > 0)) {
            return false;
        }
        int before = index;
        shown += seconds * 1000.0 * speed;
        // A long step crosses several frames; a step of hours crosses whole turns, which are
        // taken off at once rather than walked.
        long turn = image.durationMillis();
        int perTurn = loop.bounce ? 2 : 1;
        if (turn > 0 && shown > turn * perTurn * 4.0 && endless()) {
            shown %= turn * perTurn;
        }
        while (!finished && shown >= image.frameMillis(index)) {
            shown -= image.frameMillis(index);
            step();
        }
        return index != before;
    }

    /** @return how long until the frame changes, in seconds at the speed set; infinite when nothing will change it */
    public double secondsUntilNextFrame() {
        if (!isPlaying()) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0, image.frameMillis(index) - shown) / 1000.0 / speed;
    }

    private boolean endless() {
        return (loop == Loop.FILE ? image.loopCount() : loop.turns) == 0;
    }

    private boolean turnsLeft() {
        int turns = loop == Loop.FILE ? image.loopCount() : loop.turns;
        return turns == 0 || turnsDone < turns;
    }

    private void step() {
        int last = image.frameCount() - 1;
        if (loop.bounce) {
            if (!backward && index == last) {
                backward = true;
            } else if (backward && index == 0) {
                backward = false;
            }
            index += backward ? -1 : 1;
            return;
        }
        if (index < last) {
            index++;
            return;
        }
        turnsDone++;
        if (turnsLeft()) {
            index = 0;
        } else {
            finished = true;
            shown = 0;
        }
    }
}

package limn.graphics;

import java.util.Objects;
import limn.internal.lang.Checks;

/**
 * An immutable, backend-independent bitmap: RGBA8 pixels, straight (non
 * premultiplied) alpha, row-major, row 0 at the <em>top</em>. Decoded once
 * (see {@link Images}) and drawn via {@link Canvas#drawImage}; the GPU texture
 * is created lazily by each window's renderer, so one {@code Image} works
 * across multiple windows (whose GL contexts are not shared).
 *
 * <p>Object identity is the texture-cache key; reuse the same instance rather
 * than decoding the same asset repeatedly. A window's cache holds on to the
 * instance, and so to its pixels, for as long as it keeps the texture, which
 * {@link Images#setTextureBudget} bounds.
 *
 * <p><b>An image may be animated</b>: {@link #isAnimated()}, with frames that are each an image
 * and a time each is shown for. Its own pixels are then those of its first frame, so whatever
 * draws an image and knows nothing of animation draws a still picture. Nothing moves unless
 * something plays it: an {@code ImageView} does, and {@code limn.animation.ImageAnimation} is
 * what a widget of an application's own does it with.
 */
public final class Image {

    private final int width;
    private final int height;
    private final byte[] rgba;
    /** The frames of an animated image, or null for a still one. */
    private final Frames frames;

    /**
     * Where an animated image's frames come from. Not API: {@link Images} makes these, from a
     * list that is all there or from a file read as it plays.
     */
    interface Frames {
        int count();

        /** @return the frame, whole and of the animation's size; never null */
        Image frame(int index);

        int millis(int index);

        /** @return how many turns, 0 for no end */
        int loopCount();

        /**
         * @return the frame if it is in memory now, else null; either way it and the ones after
         *         it are wanted, and one that is read as it plays starts reading them
         */
        default @org.jspecify.annotations.Nullable Image ready(int index) {
            return frame(index);
        }
    }

    /**
     * @param width  pixels
     * @param height pixels
     * @param rgba   {@code width*height*4} bytes, straight alpha, top-down
     */
    public Image(int width, int height, byte[] rgba) {
        Checks.positiveSize(width, height, "image size");
        Objects.requireNonNull(rgba, "rgba");
        if (rgba.length != width * height * 4) {
            throw new IllegalArgumentException(
                    "rgba length " + rgba.length + " != " + width + "x" + height + "x4");
        }
        this.width = width;
        this.height = height;
        this.rgba = rgba;
        this.frames = null;
    }

    /** An animated image: its own pixels are its first frame's. */
    Image(Image first, Frames frames) {
        this.width = first.width;
        this.height = first.height;
        this.rgba = first.rgba;
        this.frames = frames;
    }

    /** @return whether this image has more than one frame, which something may play */
    public boolean isAnimated() {
        return frames != null;
    }

    /** @return how many frames; 1 for a still image */
    public int frameCount() {
        return frames == null ? 1 : frames.count();
    }

    /**
     * One frame, as a still image of this image's size: what is shown at that moment, whole,
     * whatever the file built it from. The frame of a still image is the image. For a long
     * animation that is decoded as it plays, a frame that is not in memory is decoded here,
     * on the calling thread, and the frames before it with it: a player asks
     * {@link #frameIfReady} instead.
     *
     * @param index from 0 to {@link #frameCount()} - 1
     * @return the frame; the same object each time for as long as it is kept in memory
     * @throws IndexOutOfBoundsException if there is no such frame
     */
    public Image frame(int index) {
        Objects.checkIndex(index, frameCount());
        return frames == null ? this : frames.frame(index);
    }

    /**
     * One frame, if it is in memory now. A long animation is decoded as it plays, a few frames
     * ahead of the one shown, and this is what a player asks instead of {@link #frame}: it
     * answers at once, and says that the frame and the ones after it are wanted. A frame that
     * is not ready is not waited for; the player shows the one before until it is.
     *
     * @param index from 0 to {@link #frameCount()} - 1
     * @return the frame, or null while it is being decoded; never null for a still image, for an
     *         animation decoded whole, or for the first frame
     * @throws IndexOutOfBoundsException if there is no such frame
     */
    public @org.jspecify.annotations.Nullable Image frameIfReady(int index) {
        Objects.checkIndex(index, frameCount());
        return frames == null ? this : frames.ready(index);
    }

    /**
     * @param index from 0 to {@link #frameCount()} - 1
     * @return how long that frame is shown at the animation's own speed, in milliseconds; 0 for
     *         a still image
     * @throws IndexOutOfBoundsException if there is no such frame
     */
    public int frameMillis(int index) {
        Objects.checkIndex(index, frameCount());
        return frames == null ? 0 : frames.millis(index);
    }

    /** @return how long one turn of the animation takes, in milliseconds; 0 for a still image */
    public long durationMillis() {
        long whole = 0;
        for (int i = 0, n = frames == null ? 0 : frames.count(); i < n; i++) {
            whole += frames.millis(i);
        }
        return whole;
    }

    /**
     * @return how many turns the animation asks for: 0 for one that does not end, which is also
     *         what a still image answers
     */
    public int loopCount() {
        return frames == null ? 0 : frames.loopCount();
    }

    /** @return width in pixels */
    public int width() {
        return width;
    }

    /** @return height in pixels */
    public int height() {
        return height;
    }

    /** @return the raw RGBA bytes (do not mutate) */
    public byte[] pixels() {
        return rgba;
    }
}

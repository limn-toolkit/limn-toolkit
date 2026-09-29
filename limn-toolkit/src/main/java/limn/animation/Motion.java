package limn.animation;

import limn.scene.SystemPreferences;

/**
 * How long the toolkit's motion takes, given what the user asked the system for. While
 * {@link SystemPreferences#reduceMotion()} is set, motion finishes at once: a {@link Transition}
 * jumps to its target, and popups, menus and dialogs open and close without fading, as native
 * toolkits do for a user who asked every application for less motion.
 *
 * <p>An animation that drives its own clock reads this too. One whose motion carries information,
 * such as a progress sweep or a busy spinner, keeps moving but {@link #stepped steps}: it moves a few
 * times a second instead of every frame.
 */
public final class Motion {

    /** How often a stepped animation moves, in seconds: four times a second. */
    public static final double STEP_SECONDS = 0.25;

    private Motion() {
    }

    /** @return whether the user asked for less motion. Any thread */
    public static boolean reduced() {
        return SystemPreferences.current().reduceMotion();
    }

    /**
     * @param seconds how long the motion takes when nothing is reduced
     * @return {@code seconds}, or {@code 0} while motion is reduced. Any thread
     */
    public static double duration(double seconds) {
        return reduced() ? 0 : seconds;
    }

    /**
     * The share of a fade that one frame advances: {@code dt / seconds}, or all of it when the fade
     * takes no time or motion is reduced. Never NaN, even for a frame of zero length.
     *
     * @param dt      the frame's length, in seconds
     * @param seconds how long the whole fade takes when nothing is reduced
     * @return the share to add, {@code 1} or more for a fade that ends now
     */
    public static double progress(double dt, double seconds) {
        double length = duration(seconds);
        return length > 0 ? dt / length : 1;
    }

    /**
     * Holds a stepped animation's time between steps: add each frame's length to {@code held}; the
     * animation moves only when this answers more than zero, by the time it answers, while motion is
     * reduced. Otherwise every frame moves by its own length.
     *
     * <pre>{@code
     * double moved = Motion.stepped(dt, held);
     * held = moved > 0 ? 0 : held + dt;
     * if (moved > 0) { phase += moved / period; invalidate(); }
     * }</pre>
     *
     * @param dt   this frame's length, in seconds
     * @param held the time held since the last step, in seconds
     * @return the time to move by now, or {@code 0} to hold this frame
     */
    public static double stepped(double dt, double held) {
        if (!reduced()) {
            return dt;
        }
        double total = held + dt;
        return total >= STEP_SECONDS ? total : 0;
    }
}

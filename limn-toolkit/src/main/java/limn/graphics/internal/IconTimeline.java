package limn.graphics.internal;

import limn.graphics.Icon;

import java.util.Objects;

/**
 * Which frame of an animated {@link Icon} is shown, given the time that has passed: the frames in
 * order at the icon's own times, as many turns as it asks for, resting on the last one after.
 * Nothing about it can be set, since a place that shows an icon plays it as the icon asks.
 *
 * <p>The clockwork of every place that plays an icon, a widget, a tray and a cursor alike; it
 * draws nothing and schedules nothing. Not thread-safe: its owner's.
 */
public final class IconTimeline {

    private final Icon icon;
    private int index;
    /** How long the frame shown has been shown, in milliseconds. */
    private double shown;
    private int turnsDone;
    private boolean finished;

    /** @param icon the icon to play; a still one is a timeline that has finished */
    public IconTimeline(Icon icon) {
        this.icon = Objects.requireNonNull(icon, "icon");
        this.finished = !icon.isAnimated();
    }

    /** @return the icon played */
    public Icon icon() {
        return icon;
    }

    /** @return the frame shown, from 0 */
    public int frame() {
        return index;
    }

    /** @return whether time still moves it: not at the end of its last turn */
    public boolean isPlaying() {
        return !finished;
    }

    /**
     * Moves time on.
     *
     * @param seconds how much time has passed
     * @return whether the frame shown changed
     */
    public boolean advance(double seconds) {
        if (finished || !(seconds > 0)) {
            return false;
        }
        int before = index;
        shown += seconds * 1000.0;
        long turn = 0;
        for (int f = 0; f < icon.frameCount(); f++) {
            turn += millis(f);
        }
        // A step of hours crosses whole turns, which are taken off at once rather than walked.
        if (icon.loopCount() == 0 && shown > turn * 4.0) {
            shown %= turn;
        }
        while (!finished && shown >= millis(index)) {
            shown -= millis(index);
            if (index < icon.frameCount() - 1) {
                index++;
            } else if (icon.loopCount() == 0 || ++turnsDone < icon.loopCount()) {
                index = 0;
            } else {
                finished = true;
                shown = 0;
            }
        }
        return index != before;
    }

    /** @return seconds until the frame changes; infinite once it has finished */
    public double secondsUntilNextFrame() {
        return finished ? Double.POSITIVE_INFINITY : Math.max(0, millis(index) - shown) / 1000.0;
    }

    /** A frame's time, at least a millisecond: an icon an application wrote may say 0. */
    private int millis(int frame) {
        return Math.max(1, icon.frameMillis(frame));
    }
}

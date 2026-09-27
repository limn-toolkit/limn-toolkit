package limn.backend.lwjgl;

import java.util.List;

/**
 * The refresh period a window's frames are paced to: that of the monitor the window is on.
 *
 * <p>The loop's one vsynced swap blocks to the refresh of the monitor showing the window that took
 * it, and {@link FramePacer} sleeps after a present that came sooner than half a period, taking it
 * for a swap that did not block. A period read off the primary monitor was the wrong one for a
 * window anywhere else: on a 144 Hz monitor beside a 60 Hz primary, a blocking swap arrives 6.9 ms
 * after the last, under half of 16.7 ms, and every frame was slept to 60 fps, with judder.
 *
 * <p><b>The monitor is the one holding the window's centre</b>, where the window is placed when it
 * goes full screen too. Where that cannot be known, the period is the <b>shortest</b> any monitor
 * has: on Wayland, where a client is never told its position, and for a window whose centre is
 * on no monitor or on one whose rate is unknown. The shortest period can only make the pacer sleep
 * less, so a blocking swap is never slowed by it, while a swap that does not block is still held
 * to the fastest rate there is instead of running free.
 *
 * <p><b>Variable refresh</b> (ProMotion, G-Sync, FreeSync, Wayland's adaptive sync) needs nothing
 * of its own here: such a display refreshes at most as often as its mode states and slower when
 * frames come slower, so a present it paces arrives a full period or more after the last and is
 * never slept. It would be slowed only by a mode that stated less than the display's fastest rate.
 */
final class RefreshPeriod {

    private RefreshPeriod() {
    }

    /**
     * A monitor as the pacing sees it: where it is, in the coordinates window positions are
     * given in, and how often it refreshes.
     *
     * @param refreshHz the current mode's rate, or 0 when the platform does not say
     */
    record Monitor(int x, int y, int width, int height, int refreshHz) {

        boolean holds(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    /**
     * The period for a window, in nanoseconds.
     *
     * @param positionKnown whether the window's position is known; false on Wayland
     * @param centreX       the window's centre, when its position is known
     * @param centreY       the window's centre, when its position is known
     * @param monitors      the monitors connected now
     * @return the period, or 0 when no monitor states a rate, which the pacer reads as 60 Hz
     */
    static long forWindow(boolean positionKnown, int centreX, int centreY, List<Monitor> monitors) {
        if (positionKnown) {
            for (Monitor monitor : monitors) {
                if (monitor.holds(centreX, centreY) && monitor.refreshHz() > 0) {
                    return 1_000_000_000L / monitor.refreshHz();
                }
            }
        }
        int fastest = 0;
        for (Monitor monitor : monitors) {
            fastest = Math.max(fastest, monitor.refreshHz());
        }
        return fastest > 0 ? 1_000_000_000L / fastest : 0;
    }
}

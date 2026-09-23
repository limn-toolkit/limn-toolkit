package limn.backend.lwjgl;

import java.util.function.LongSupplier;

/**
 * Holds the event loop to the display's refresh rate when the vsynced swap does not.
 *
 * <p>The loop paces itself by handing one window per iteration a swap interval of one, which blocks
 * until the next vertical blank. That is a request, not a guarantee: macOS does not block the swap of
 * a window that is covered, and a driver or a virtual machine may ignore the interval altogether.
 * Measured on 2026-09-22 with a covered window and an indeterminate progress bar: 2,400–2,900 frames a
 * second and 70 % of a core, where the display refreshes 60 times. So after each vsynced present the
 * loop asks this pacer, and a present that came less than half a refresh period after the previous one
 * — a swap that did not block — is followed by a sleep to that period's end. A swap that did block
 * arrives a whole period after the last and sleeps nothing, and neither does the first present after
 * an idle stretch, so a window rendering on demand pays nothing.
 */
final class FramePacer {

    /** How the pacer sleeps; the real one parks the thread, a test records. */
    interface Sleeper {
        void sleepNanos(long nanos);
    }

    private final LongSupplier clock;
    private final Sleeper sleeper;
    private long periodNanos;
    private long lastPresentNanos;
    private boolean presented;

    FramePacer(LongSupplier clock, Sleeper sleeper, long periodNanos) {
        this.clock = clock;
        this.sleeper = sleeper;
        setPeriodNanos(periodNanos);
    }

    /** @param periodNanos one refresh period; anything not positive falls back to 60 Hz */
    void setPeriodNanos(long periodNanos) {
        this.periodNanos = periodNanos > 0 ? periodNanos : 1_000_000_000L / 60;
    }

    long periodNanos() {
        return periodNanos;
    }

    /**
     * Called after an iteration that presented through the vsynced swap. Sleeps when that swap did
     * not block.
     *
     * @return how long it slept, in nanoseconds
     */
    long afterVsyncedPresent() {
        long now = clock.getAsLong();
        long slept = 0;
        if (presented) {
            long since = now - lastPresentNanos;
            if (since >= 0 && since < periodNanos / 2) {
                slept = periodNanos - since;
                sleeper.sleepNanos(slept);
                now = clock.getAsLong();
            }
        }
        presented = true;
        lastPresentNanos = now;
        return slept;
    }

    /** A pacer on the real clock that parks the calling thread. */
    static FramePacer real(long periodNanos) {
        return new FramePacer(System::nanoTime, nanos -> java.util.concurrent.locks.LockSupport.parkNanos(nanos),
                periodNanos);
    }
}

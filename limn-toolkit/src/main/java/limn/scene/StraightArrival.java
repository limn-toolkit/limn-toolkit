package limn.scene;

import limn.concurrent.Subscription;
import limn.concurrent.Ui;

/**
 * When what a fade or a motion runs at its end runs, once it has gone straight to its target:
 * never inside the call that asked for it, and then after the seconds that call asked for, or on
 * the next turn of the UI queue when it asked for none.
 *
 * <p>Never inside the call, because the next step of a sequence is asked for in the last one's
 * callback, and where every step went straight there, less motion asked for or no window to step
 * it, each callback run inside the call started the next on the same stack until it ran out. And
 * after the seconds asked for, not on the next turn, because a chain run one step a turn flipped
 * its value on every frame a window drew, and with nothing drawn left a task queued on every turn,
 * so the loop never slept: after the seconds, a sequence keeps the pace its application wrote, and
 * the loop sleeps between two steps. A fade asked for no time has no pace to keep, and arrives on
 * the next turn.
 *
 * <p>Whoever posts one keeps the handle and checks, as it runs, that nothing superseded the motion
 * since; the handle withdraws a delayed one, so that what it captures is let go at once.
 */
final class StraightArrival {

    private StraightArrival() {
    }

    /**
     * Posts {@code arrival} on the UI queue: after {@code seconds} when that is more than zero, on
     * the next turn otherwise.
     *
     * @param seconds what the call asked the motion to take, before less motion is applied
     * @param arrival what runs then
     * @return a handle that withdraws it; one for the next turn withdraws nothing, and the poster's
     *         own check drops it
     */
    static Subscription post(double seconds, Runnable arrival) {
        if (seconds > 0) {
            // Rounded to the queue's millisecond; Math.round saturates, so a delay of centuries is
            // "never" as the queue reads Long.MAX_VALUE.
            return Ui.postDelayed(arrival, Math.round(seconds * 1000));
        }
        Ui.post(arrival);
        return () -> { };
    }
}

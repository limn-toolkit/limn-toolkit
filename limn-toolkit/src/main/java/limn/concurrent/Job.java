package limn.concurrent;

/**
 * A started piece of background work, as seen by whoever asked for it. Held so
 * that the request can be withdrawn: a field on the widget that started it,
 * cancelled when the widget starts the next one or is taken off screen.
 *
 * <p>All three methods are safe to call from any thread; {@link #cancel()}
 * guarantees more when it is called on the UI thread.
 */
public interface Job {

    /**
     * Withdraws the request. Returns immediately, and the body is never
     * interrupted.
     *
     * <p><b>Cancel on the UI thread.</b> That is the precondition on the
     * guarantee worth having: called there, no success, failure or progress
     * callback runs after this returns. Called from any other thread it
     * promises less: a delivery may already be executing on the UI thread when
     * the flag is set, and that one finishes. Nothing is delivered after the
     * flag is seen either way, and the reason to cancel (a view closing, the
     * next keystroke) arrives on the UI thread anyway.
     *
     * <p>What holds from any thread: the body does not run at all if the worker
     * pool has not picked it up yet. What does not: that a body already running
     * stops. It keeps going until it returns or notices
     * {@link Progress#isCancelled()}, holding a worker thread until then (so
     * cancelling ten jobs gives back no threads), and whatever it produces is
     * discarded rather than delivered.
     *
     * <p>Calling this more than once, or after the work has finished, is a
     * no-op.
     */
    void cancel();

    /** @return {@code true} once {@link #cancel} has been called */
    boolean isCancelled();

    /**
     * @return {@code true} once the body has returned or thrown, or once it
     *         has been skipped because the job was cancelled before the pool
     *         reached it. It says nothing about whether a callback ran: this
     *         flips on the worker thread the instant the body returns, and the
     *         delivery it triggers runs on a later frame, so polling this and
     *         then reading what a success handler was to set is a race. Do that
     *         work in the handler. A cancelled job is done and delivers
     *         nothing.
     */
    boolean isDone();
}

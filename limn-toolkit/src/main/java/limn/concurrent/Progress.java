package limn.concurrent;

/**
 * The only handle a background body has on the outside world while it runs:
 * it can ask whether it has been cancelled, and it can say how far it has got.
 *
 * <p>Both methods are called from the worker thread running the body. Neither
 * touches widget or scene state, and neither blocks; {@link #report} hands the
 * value over and returns.
 */
public interface Progress {

    /**
     * Whether the job has been cancelled.
     *
     * <p>Cancellation is cooperative: nothing interrupts the worker thread and
     * no exception is thrown into the body. A body that never asks runs to
     * completion, and its result is then discarded instead of delivered. Ask
     * between units of work (per block read, per row parsed) and return
     * early when this answers {@code true}; returning early is not an error and
     * whatever is returned is treated as an undeliverable result.
     *
     * @return {@code true} once cancellation has been requested
     */
    boolean isCancelled();

    /**
     * Reports how much of the work is finished, as a fraction of the whole.
     *
     * <p>Delivery is coalesced: at most one report is in flight to the UI
     * thread at a time, and the one that arrives carries the newest value
     * reported, so intermediate values are skipped when reports outrun frames.
     * Values arrive in the order they were reported, and the last value
     * reported before the body returns is always delivered, ahead of the
     * success callback. Calling this allocates nothing, so a tight loop may
     * report per iteration.
     *
     * @param fraction 0 to 1 inclusive; values outside that range are clamped
     *                 and {@code NaN} is reported as 0
     */
    void report(double fraction);
}

package limn.backend;

/**
 * Application hook for crashes the toolkit caught in the event loop. Install
 * via {@link Crashes#install} to log to your own sink, save state, show your
 * own error UI, or decide that the application should shut down.
 *
 * <p>Runs on the UI thread, inside the loop iteration that caught the crash;
 * keep it fast and never throw (a throwing handler is itself contained and
 * treated as "continue").
 */
@FunctionalInterface
public interface CrashHandler {

    /**
     * Called with every crash the toolkit contains.
     *
     * <p>Whether the return value is honored depends on the <em>containment
     * site</em>, not on the phase, since the same phase can arrive from both
     * kinds:
     * <ul>
     *   <li><b>Honored</b> at coarse sites, where the crash would otherwise
     *       have been fatal: a scene frame and a deferred GPU disposal
     *       ({@code FRAME}), the native event poll ({@code EVENT_POLL}), the
     *       event loop's per-window input/frame backstops ({@code INPUT},
     *       {@code FRAME}), and window teardown as a whole
     *       ({@code WINDOW_CLOSE}, backend side).</li>
     *   <li><b>Ignored</b> at fine-grained sites that contain and continue by
     *       design: one input event ({@code INPUT}), one posted task
     *       ({@code TASK}), one ticker ({@code TICKER}), one window-close
     *       callback ({@code WINDOW_CLOSE}, scene side), reported for
     *       observability only.</li>
     * </ul>
     * A handler deciding shutdown should therefore decide by policy (always /
     * never / by error type), not by phase.
     *
     * @param phase where the application code was executing
     * @param error what it threw
     * @return {@code true} to keep the application running (the toolkit
     *         recovers: repaints, retries, or skips; see {@link Crashes});
     *         {@code false} to request an orderly shutdown where honored
     */
    boolean crashed(CrashPhase phase, Throwable error);
}

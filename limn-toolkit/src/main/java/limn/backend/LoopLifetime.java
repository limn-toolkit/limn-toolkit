package limn.backend;

/**
 * How long {@link Backend#runEventLoop()} runs: set with {@link Backend#setLoopLifetime},
 * {@link #UNTIL_LAST_WINDOW} by default. Either way, {@link Backend#stop()} ends it at once and an
 * agreed {@link Backend#requestQuit()} ends it after closing every window.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum LoopLifetime {

    /** Until no window is open. A hidden window is an open one. The default. */
    UNTIL_LAST_WINDOW,

    /**
     * Until the application ends it, through {@link Backend#requestQuit()} or {@link Backend#stop()}:
     * the loop runs with no window open, as an application that lives in the tray does.
     */
    UNTIL_QUIT
}

package limn.backend;

/**
 * Why {@link Backend#runEventLoop()} returned.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum LoopEnd {

    /** The last open window closed, under {@link LoopLifetime#UNTIL_LAST_WINDOW}. */
    LAST_WINDOW_CLOSED,

    /** {@link Backend#stop()} was called; whatever windows were open still are. */
    STOPPED,

    /** A {@link Backend#requestQuit()} was agreed to, and every window has closed. */
    QUIT
}

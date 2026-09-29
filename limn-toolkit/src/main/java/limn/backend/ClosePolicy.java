package limn.backend;

/**
 * What a user's close of a window does once it is allowed: set with
 * {@link NativeWindow#setClosePolicy}, {@link #DISPOSE} by default.
 *
 * <p>Whether the close is allowed is a separate question, the window's
 * {@link NativeWindow#setCloseRequestHandler handler}'s, so a window can both ask and hide. A
 * program's {@link NativeWindow#requestClose()} is not a user's close: it disposes the window
 * whatever the policy says.
 */
public enum ClosePolicy {

    /** The window is destroyed. The default. */
    DISPOSE,

    /**
     * The window is hidden and kept, to be shown again with {@link NativeWindow#show()}. A hidden
     * window is still an open one, so under {@link LoopLifetime#UNTIL_LAST_WINDOW} it keeps the
     * loop running.
     */
    HIDE,

    /**
     * The close asks to end the whole application instead, as {@link Backend#requestQuit()} does:
     * every window is asked, this one among them, and nothing closes unless all agree.
     */
    END_APPLICATION
}

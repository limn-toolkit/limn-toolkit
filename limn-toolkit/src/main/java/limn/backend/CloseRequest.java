package limn.backend;

/**
 * One question to a window's {@link CloseRequestHandler}: may this window close? Answered exactly
 * once, with {@link #allow()} or {@link #refuse()}, now or later: a handler that shows a dialog keeps
 * the request and answers from the dialog's result.
 *
 * <p>While a request is unanswered, the window is not asked again: a second click on its close
 * button asks nothing new, and an application ending waits for this answer instead of asking its
 * own question. An application may implement this interface to test its handlers.
 */
public interface CloseRequest {

    /**
     * Why the window is asked.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    enum Reason {

        /** The user asked this window to close: its close button, Alt-F4, Cmd-W. */
        USER_CLOSE,

        /**
         * The application is ending ({@link Backend#requestQuit()}): every window is asked, front to
         * back, and none closes unless all agree.
         */
        APPLICATION_END
    }

    /** @return the window asked */
    NativeWindow window();

    /** @return why the window is asked */
    Reason reason();

    /**
     * Lets the window close: under {@link Reason#USER_CLOSE} it does what its
     * {@link ClosePolicy} says; under {@link Reason#APPLICATION_END} the next window is asked. UI
     * thread only.
     *
     * @throws IllegalStateException if the request was already answered
     */
    void allow();

    /**
     * Keeps the window open; under {@link Reason#APPLICATION_END} the whole request ends and every
     * window stays open. UI thread only.
     *
     * @throws IllegalStateException if the request was already answered
     */
    void refuse();

    /** @return whether {@link #allow()} or {@link #refuse()} has been called */
    boolean isAnswered();
}

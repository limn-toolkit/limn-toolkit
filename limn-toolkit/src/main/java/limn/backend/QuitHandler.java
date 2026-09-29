package limn.backend;

/**
 * Decides whether the application may end, for what belongs to no window (a download still
 * running): set with {@link Backend#setQuitHandler}. Asked first by {@link Backend#requestQuit()},
 * before any window; a refusal ends the request there.
 *
 * <p>A handler that throws has refused, as a {@link CloseRequestHandler} that throws has.
 */
@FunctionalInterface
public interface QuitHandler {

    /**
     * Called on the UI thread.
     *
     * @param request the question, to answer once with {@link QuitRequest#allow()} or
     *                {@link QuitRequest#refuse()}
     */
    void quitRequested(QuitRequest request);
}

package limn.backend;

/**
 * Decides whether a window may close, set with {@link NativeWindow#setCloseRequestHandler}. It is
 * asked when the user closes the window and when the application is ending, and answers through the
 * {@link CloseRequest} it is handed, at once or later.
 *
 * <p>A handler that throws has refused: the window stays open, and the exception reaches the crash
 * handler as any exception from application code on the UI thread does.
 */
@FunctionalInterface
public interface CloseRequestHandler {

    /**
     * Called on the UI thread.
     *
     * @param request the question, to answer once with {@link CloseRequest#allow()} or
     *                {@link CloseRequest#refuse()}
     */
    void closeRequested(CloseRequest request);
}

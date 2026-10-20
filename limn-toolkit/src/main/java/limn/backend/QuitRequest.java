package limn.backend;

/**
 * The application-wide question {@link Backend#requestQuit()} asks first, to the
 * {@link QuitHandler}, before any window is asked: may the application end? Answered exactly once,
 * now or later.
 *
 * <p>Only Limn implements this interface. A test asks its handler the way the system does,
 * through the headless backend of {@code limn-test}: {@code Backend.requestQuit()}.
 */
public sealed interface QuitRequest permits limn.backend.internal.Ending.Quit {

    /** Lets the request go on to the windows, front to back. UI thread only. */
    void allow();

    /** Ends the request; every window stays open. UI thread only. */
    void refuse();

    /** @return whether {@link #allow()} or {@link #refuse()} has been called */
    boolean isAnswered();
}

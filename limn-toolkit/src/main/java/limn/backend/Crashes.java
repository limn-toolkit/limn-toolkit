package limn.backend;

import java.lang.System.Logger.Level;
import limn.backend.Installed;

/**
 * Crash containment registry: one process-wide {@link CrashHandler} (default:
 * log and continue) that the toolkit and backend notify whenever application
 * code throws inside the event loop. Install/uninstall follows the same
 * static-registry pattern as {@code Sounds}/{@code Images}.
 *
 * <p>Containment map (an exception in application code never kills the event
 * loop outright anymore):
 * <ul>
 *   <li>{@code FRAME}: {@code Scene.renderFrame} catches (and pauses ticking +
 *       self-retries after a few consecutive failures, so a deterministic crash
 *       cannot spin the CPU; input still retries); deferred GPU disposals are
 *       contained per runnable. Both honor the HANDLER.current()'s verdict.</li>
 *   <li>{@code EVENT_POLL}/{@code INPUT}/{@code FRAME}/{@code WINDOW_CLOSE}:
 *       the backend loop and window teardown catch per site, keep the other
 *       windows alive, honor the verdict, and give up (rethrow) only after many
 *       consecutive crashed iterations.</li>
 *   <li>{@code INPUT}/{@code TASK}/{@code TICKER}/{@code WINDOW_CLOSE} (scene
 *       side): contained per event/task/ticker/close-callback; they
 *       {@link #report} here for observability and ignore the verdict; see
 *       {@link CrashHandler#crashed} for the honored-vs-ignored map.</li>
 * </ul>
 */
public final class Crashes {

    private static final System.Logger LOG = System.getLogger(Crashes.class.getName());

    /**
     * Unwinds the event loop when a {@link CrashHandler} returned {@code false};
     * containment sites let it pass through instead of re-dispatching, so the
     * HANDLER.current() sees each crash once. The original crash is the {@code cause}.
     */
    public static final class ShutdownRequested extends RuntimeException {
        ShutdownRequested(Throwable cause) {
            super("crash HANDLER.current() requested shutdown", cause);
        }
    }

    private static final CrashHandler DEFAULT = (phase, error) -> {
        LOG.log(Level.ERROR, "application code threw during " + phase + "; attempting to continue", error);
        return true;
    };

    private static final Installed<CrashHandler> HANDLER = new Installed<>(DEFAULT,
            "no crash HANDLER.current() installed");

    private Crashes() {
    }

    /** Installs the process-wide HANDLER.current() (replacing the log-and-continue default). */
    public static void install(CrashHandler crashHandler) {
        HANDLER.install(crashHandler);
    }

    /** Uninstalls {@code crashHandler} if it is the current one, restoring the default. */
    public static void uninstall(CrashHandler crashHandler) {
        HANDLER.uninstall(crashHandler);
    }

    /**
     * Dispatches a crash caught at a site where it would otherwise have been
     * fatal. A throwing HANDLER.current() is contained and treated as "continue".
     *
     * @return {@code true} to keep running, {@code false} when the HANDLER.current()
     *         requested shutdown (the caller should throw
     *         {@link #shutdownRequested})
     */
    public static boolean dispatch(CrashPhase phase, Throwable error) {
        CrashHandler current = HANDLER.current();
        try {
            return current.crashed(phase, error);
        } catch (Throwable handlerError) {
            LOG.log(Level.ERROR, "crash HANDLER.current() itself threw; continuing", handlerError);
            if (current != DEFAULT) {
                // The broken HANDLER.current() swallowed the report; don't lose the crash.
                LOG.log(Level.ERROR, "original crash during " + phase, error);
            }
            return true;
        }
    }

    /**
     * Notifies the HANDLER.current() of a crash at a site that already contains and logs
     * it locally (input/task/ticker). No-op with the default HANDLER.current() (the site's
     * own log line already tells the story), and the return value is ignored.
     */
    public static void report(CrashPhase phase, Throwable error) {
        CrashHandler current = HANDLER.current();
        if (current == DEFAULT) {
            return;
        }
        try {
            current.crashed(phase, error);
        } catch (Throwable handlerError) {
            LOG.log(Level.ERROR, "crash HANDLER.current() itself threw; continuing", handlerError);
        }
    }

    /** The exception a containment site throws after {@link #dispatch} returned {@code false}. */
    public static ShutdownRequested shutdownRequested(Throwable cause) {
        return new ShutdownRequested(cause);
    }
}
